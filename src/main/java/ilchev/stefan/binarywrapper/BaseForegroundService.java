package ilchev.stefan.binarywrapper;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public abstract class BaseForegroundService extends Service {

	private static final String TAG = "BaseForegroundService";

	private static final int NOTIFICATION_ID = 1;

	private static final Executor WORK_EXECUTOR = Executors.newSingleThreadExecutor();

	public static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

	public static String getUpdate(PackageInfo packageInfo, String versionName) {
		return versionName == null || packageInfo.versionName.equals(versionName) ? null : versionName;
	}

	public static void tryShowDifferent(Context context, String versionName) {
		try {
			var packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
			if (getUpdate(packageInfo, versionName) != null) {
				Toast.makeText(context, packageInfo.versionName + " \u2260 " + versionName, Toast.LENGTH_LONG).show();
			}
		} catch (Throwable t) {
			Log.w(TAG, t);
		}
	}

	private BaseDaemonRunnable daemonRunnable = null;

	private volatile String updateVersionName = null;

	private String updateVersionNameMsg = null;

	private BroadcastReceiver updateInstallReceiver = null;

	private int updateInstallId = 0;

	private BroadcastReceiver updateDownloadReceiver = null;

	private long updateDownloadId = 0L;

	private MediaSession mediaSession = null;

	protected abstract Class<?> getMainActivityClass();

	protected abstract BaseDaemonRunnable getDaemonRunnable(Uri data);

	protected String getVersionName(Uri data) {
		return null;
	}

	protected String getUpdateFileName(String versionName) {
		return null;
	}

	protected Uri getUpdateDownloadUri(String versionName) {
		return null;
	}

	private Uri getData(Intent intent) {
		var sharedPreferences = getSharedPreferences(BuildConfig.LIBRARY_PACKAGE_NAME, MODE_PRIVATE);
		if (intent == null) {
			var result = sharedPreferences.getString(BuildConfig.LIBRARY_PACKAGE_NAME, null);
			return result != null ? Uri.parse(result) : null;
		}
		var result = intent.getData();
		sharedPreferences.edit()
				.putString(BuildConfig.LIBRARY_PACKAGE_NAME, result != null ? result.toString() : null)
				.apply();
		return result;
	}

	private String getUpdateVersionName(Uri data) {
		try {
			var packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
			return getUpdate(packageInfo, getVersionName(data));
		} catch (Throwable t) {
			Log.w(TAG, t);
		}
		return null;
	}

	private void tryStartActivity(Intent intent, Bundle options) {
		try {
			startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options);
		} catch (Throwable t) {
			Log.w(TAG, t);
		}
	}

	private void startForeground(PendingIntent stopIntent) {
		var applicationInfo = getApplicationInfo();
		var applicationIcon = applicationInfo.icon;
		var applicationLabel = getPackageManager().getApplicationLabel(applicationInfo);
		var stop = getString(R.string.stop);
		var builder = new Notification.Builder(this, BuildConfig.LIBRARY_PACKAGE_NAME)
				.setSmallIcon(applicationIcon)
				.setContentTitle(applicationLabel)
				.setContentText(stop)
				.setContentIntent(stopIntent);
		var manager = getSystemService(NotificationManager.class);
		if (manager != null) {
			var channel = new NotificationChannel(
					BuildConfig.LIBRARY_PACKAGE_NAME,
					applicationLabel,
					NotificationManager.IMPORTANCE_LOW);
			manager.createNotificationChannel(channel);
		}
		if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
			builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
		}
		startForeground(NOTIFICATION_ID, builder.build());
	}

	private void stopForeground() {
		updateVersionNameMsg = updateVersionName;
		stopForeground(STOP_FOREGROUND_REMOVE);
		stopSelf();
	}

	private void stopDaemon() {
		var daemonRunnable = this.daemonRunnable;
		if (daemonRunnable != null) {
			this.daemonRunnable = null;
			daemonRunnable.destroy();
		}
	}

	private void startDaemon(BaseDaemonRunnable daemonRunnable) {
		stopDaemon();
		WORK_EXECUTOR.execute(() -> {
			daemonRunnable.run();
			MAIN_HANDLER.post(() -> {
				if (!daemonRunnable.isDestroyed()) {
					stopForeground();
				}
			});
		});
		this.daemonRunnable = daemonRunnable;
	}

	private void postUpdateStop(String versionName) {
		MAIN_HANDLER.post(() -> {
			if (versionName.equals(updateVersionName)) {
				stopForeground();
			}
		});
	}

	private boolean unregisterUpdateReceiver(String versionName, BroadcastReceiver receiver) {
		if (versionName.equals(updateVersionName)) {
			return false;
		}
		unregisterReceiver(receiver);
		return true;
	}

	private void stopUpdateInstall() {
		var updateInstallReceiver = this.updateInstallReceiver;
		if (updateInstallReceiver != null) {
			this.updateInstallReceiver = null;
			unregisterReceiver(updateInstallReceiver);
		}
		var updateInstallId = this.updateInstallId;
		if (updateInstallId != 0) {
			this.updateInstallId = 0;
			getPackageManager().getPackageInstaller().abandonSession(updateInstallId);
		}
	}

	private void startUpdateInstall(File file, String versionName) throws Exception {
		if (!file.isFile()) {
			stopForeground();
			return;
		}
		var receiver = new BroadcastReceiver() {

			@Override
			public void onReceive(Context context, Intent intent) {
				var id = intent != null ? intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, 0) : 0;
				if (unregisterUpdateReceiver(versionName, this) || id == 0 || id != updateInstallId) {
					return;
				}
				updateInstallId = 0;
				updateInstallReceiver = null;
				unregisterReceiver(this);
				var status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
				var activity = status == PackageInstaller.STATUS_PENDING_USER_ACTION
						? intent.<Intent>getParcelableExtra(Intent.EXTRA_INTENT)
						: null;
				if (activity != null) {
					updateVersionName = null;
					tryStartActivity(activity, null);
				} else if (status == PackageInstaller.STATUS_SUCCESS) {
					updateVersionName = null;
				}
				stopForeground();
			}
		};
		registerReceiver(receiver, new IntentFilter(BuildConfig.LIBRARY_PACKAGE_NAME), null, MAIN_HANDLER);
		updateInstallReceiver = receiver;
		var installer = getPackageManager().getPackageInstaller();
		var params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
		params.setSize(file.length());
		if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
			params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
		}
		var updateInstallId = installer.createSession(params);
		this.updateInstallId = updateInstallId;
		var statusReceiver = PendingIntent.getBroadcast(
				this,
				updateInstallId,
				new Intent(BuildConfig.LIBRARY_PACKAGE_NAME),
				Build.VERSION.SDK_INT > Build.VERSION_CODES.R ? PendingIntent.FLAG_MUTABLE : 0)
				.getIntentSender();
		WORK_EXECUTOR.execute(() -> {
			try (var session = installer.openSession(updateInstallId)) {
				try (var out = session.openWrite(file.getName(), 0L, file.length())) {
					Files.copy(file.toPath(), out);
					session.fsync(out);
				}
				session.commit(statusReceiver);
			} catch (Throwable t) {
				Log.w(TAG, t);
				postUpdateStop(versionName);
			}
		});
	}

	private int stopUpdateDownload() {
		var updateDownloadReceiver = this.updateDownloadReceiver;
		if (updateDownloadReceiver != null) {
			this.updateDownloadReceiver = null;
			unregisterReceiver(updateDownloadReceiver);
		}
		var updateDownloadId = this.updateDownloadId;
		if (updateDownloadId != 0L) {
			this.updateDownloadId = 0L;
			var manager = getSystemService(DownloadManager.class);
			return manager != null ? manager.remove(updateDownloadId) : 0;
		}
		return 0;
	}

	private void startUpdateDownload(String versionName) {
		var manager = getSystemService(DownloadManager.class);
		var downloadUri = getUpdateDownloadUri(versionName);
		var dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
		var fileName = getUpdateFileName(versionName);
		var file = dir != null && fileName != null ? new File(dir, fileName) : null;
		if (manager == null || downloadUri == null || file == null) {
			stopForeground();
			return;
		}
		var receiver = new BroadcastReceiver() {

			@Override
			public void onReceive(Context context, Intent intent) {
				if (unregisterUpdateReceiver(versionName, this)) {
					return;
				}
				var action = intent != null ? intent.getAction() : null;
				if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
					var id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0L);
					if (id != 0L && id == updateDownloadId) {
						updateDownloadId = 0L;
						updateDownloadReceiver = null;
						unregisterReceiver(this);
						try {
							startUpdateInstall(file, versionName);
						} catch (Throwable t) {
							Log.w(TAG, t);
							stopForeground();
						}
					}
				} else if (DownloadManager.ACTION_NOTIFICATION_CLICKED.equals(action)) {
					var id = updateDownloadId;
					var ids = intent.getLongArrayExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS);
					if (id != 0L && ids != null && Arrays.stream(ids).anyMatch(it -> it == id)) {
						stopForeground();
					}
				}
			}
		};
		var filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
		filter.addAction(DownloadManager.ACTION_NOTIFICATION_CLICKED);
		registerReceiver(receiver, filter, null, MAIN_HANDLER);
		updateDownloadReceiver = receiver;
		var applicationInfo = getApplicationInfo();
		var applicationLabel = getPackageManager().getApplicationLabel(applicationInfo);
		var stop = getString(R.string.stop);
		var request = new DownloadManager.Request(downloadUri)
				.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
				.setTitle(applicationLabel)
				.setDescription(stop);
		var updateDownloadId = manager.enqueue(request);
		this.updateDownloadId = updateDownloadId;
		WORK_EXECUTOR.execute(() -> {
			var query = new DownloadManager.Query().setFilterById(updateDownloadId);
			try {
				for (; ; Thread.sleep(1_000L)) {
					try (var cursor = manager.query(query)) {
						var status = cursor != null && cursor.moveToFirst()
								? cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
								: DownloadManager.STATUS_FAILED;
						if (status == DownloadManager.STATUS_SUCCESSFUL || !versionName.equals(updateVersionName)) {
							return;
						}
						if (status == DownloadManager.STATUS_FAILED) {
							break;
						}
					}
				}
			} catch (Throwable t) {
				Log.w(TAG, t);
			}
			postUpdateStop(versionName);
		});
	}

	private void stopUpdate() {
		var versionName = updateVersionNameMsg;
		updateVersionName = null;
		updateVersionNameMsg = null;
		tryShowDifferent(this, versionName);
		stopUpdateInstall();
		stopUpdateDownload();
		var dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
		if (dir != null && dir.exists()) {
			try (var stream = Files.walk(dir.toPath())) {
				stream.sorted(Comparator.reverseOrder())
						.map(Path::toFile)
						.forEach(File::delete);
			} catch (Throwable t) {
				Log.w(TAG, t);
			}
		}
	}

	private void startUpdate(String versionName) {
		stopUpdate();
		updateVersionName = versionName;
		startUpdateDownload(versionName);
	}

	private void stopMediaSession() {
		var mediaSession = this.mediaSession;
		if (mediaSession != null) {
			this.mediaSession = null;
			mediaSession.setActive(false);
			mediaSession.release();
		}
	}

	private void startMediaSession(PendingIntent stopIntent) {
		stopMediaSession();
		var applicationInfo = getApplicationInfo();
		var applicationIcon = BitmapFactory.decodeResource(getResources(), applicationInfo.icon);
		var applicationLabel = getPackageManager().getApplicationLabel(applicationInfo).toString();
		var stop = getString(R.string.stop);
		var mediaSession = new MediaSession(this, BuildConfig.LIBRARY_PACKAGE_NAME);
		this.mediaSession = mediaSession;
		mediaSession.setMetadata(new MediaMetadata.Builder()
				.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, applicationIcon)
				.putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, applicationLabel)
				.putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, stop)
				.putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, stop)
				.putString(MediaMetadata.METADATA_KEY_TITLE, applicationLabel)
				.putString(MediaMetadata.METADATA_KEY_ARTIST, stop)
				.build());
		mediaSession.setPlaybackState(new PlaybackState.Builder()
				.setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0F)
				.build());
		mediaSession.setSessionActivity(stopIntent);
		mediaSession.setActive(true);
	}

	@Override
	public void onCreate() {
		try {
			var stopIntent = PendingIntent.getActivity(
					this,
					NOTIFICATION_ID,
					new Intent(this, getMainActivityClass()),
					PendingIntent.FLAG_IMMUTABLE);
			startForeground(stopIntent);
			startMediaSession(stopIntent);
		} catch (Throwable t) {
			Log.w(TAG, t);
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		var data = getData(intent);
		try {
			var versionName = getUpdateVersionName(data);
			if (versionName == null) {
				var daemonRunnable = getDaemonRunnable(data);
				if (!daemonRunnable.equals(this.daemonRunnable)) {
					stopUpdate();
					startDaemon(daemonRunnable);
				}
			} else if (!versionName.equals(updateVersionName)) {
				stopDaemon();
				startUpdate(versionName);
			}
		} catch (Throwable t) {
			Log.w(TAG, t);
			stopForeground();
		}
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		try {
			stopDaemon();
			stopUpdate();
			stopMediaSession();
		} catch (Throwable t) {
			Log.w(TAG, t);
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}
