package ilchev.stefan.binarywrapper;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Supplier;

public abstract class BaseMainActivity extends Activity {

	private enum RequestCode {
		REQUESTED_PERMISSIONS,
		REQUEST_INSTALL_PACKAGES,
		MANAGE_EXTERNAL_STORAGE
	}

	private static final String TAG = "BaseMainActivity";

	@SuppressLint("InlinedApi")
	private static final String ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION =
			Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION;

	@SuppressLint("InlinedApi")
	private static final String FOREGROUND_SERVICE =
			Manifest.permission.FOREGROUND_SERVICE;

	@SuppressLint("InlinedApi")
	private static final String UPDATE_PACKAGES_WITHOUT_USER_ACTION =
			Manifest.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION;

	@SuppressLint("InlinedApi")
	private static final String MANAGE_EXTERNAL_STORAGE =
			Manifest.permission.MANAGE_EXTERNAL_STORAGE;

	private Runnable allowCmdRunnable = null;

	private Dialog allowCmdDialog = null;

	protected abstract Class<?> getForegroundServiceClass();

	protected String getVersionName() {
		return null;
	}

	private boolean isInstallPackagesRequester() {
		return getPackageManager().canRequestPackageInstalls();
	}

	private void setInstallPackagesRequester(boolean value) {
		var intent = getIntent();
		if (intent != null && value) {
			intent.setAction(null);
		}
	}

	private boolean isExternalStorageManager() {
		return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager() ||
				getSharedPreferences(BuildConfig.LIBRARY_PACKAGE_NAME, MODE_PRIVATE)
						.getBoolean(MANAGE_EXTERNAL_STORAGE, false);
	}

	private void setExternalStorageManager(boolean value) {
		getSharedPreferences(BuildConfig.LIBRARY_PACKAGE_NAME, MODE_PRIVATE)
				.edit()
				.putBoolean(MANAGE_EXTERNAL_STORAGE, value)
				.apply();
	}

	private boolean isActivityFound(Intent intent) {
		try {
			var activityInfo = intent.resolveActivityInfo(getPackageManager(), 0);
			return activityInfo != null && activityInfo.isEnabled() && activityInfo.exported;
		} catch (Throwable t) {
			Log.w(TAG, t);
		}
		return false;
	}

	private void tryStartActivityForResult(Intent intent, int requestCode, Bundle options) {
		try {
			startActivityForResult(intent, requestCode, options);
		} catch (Throwable t) {
			Log.w(TAG, t);
		}
	}

	private boolean tryStopService(Intent intent) {
		try {
			return stopService(intent);
		} catch (Throwable t) {
			Log.w(TAG, t);
		}
		return false;
	}

	private void hideAllowCmd() {
		var allowCmdRunnable = this.allowCmdRunnable;
		if (allowCmdRunnable != null) {
			this.allowCmdRunnable = null;
			BaseForegroundService.MAIN_HANDLER.removeCallbacks(allowCmdRunnable);
		}
		var allowCmdDialog = this.allowCmdDialog;
		if (allowCmdDialog != null) {
			this.allowCmdDialog = null;
			allowCmdDialog.dismiss();
		}
	}

	private void showAllowCmd(String permission, Supplier<Boolean> supplier, Consumer<Boolean> consumer) {
		hideAllowCmd();
		var applicationInfo = getApplicationInfo();
		var applicationIcon = applicationInfo.icon;
		var applicationLabel = getPackageManager().getApplicationLabel(applicationInfo);
		var cmd = "adb shell appops set --uid " + getPackageName() + " " + permission + " allow";
		var allowCmdDialog = new AlertDialog.Builder(this)
				.setIcon(applicationIcon)
				.setTitle(applicationLabel)
				.setMessage(cmd)
				.setPositiveButton(R.string.allow, (dialog, which) -> {
					if (!supplier.get()) {
						Toast.makeText(this, R.string.allow_cmd_allow_msg, Toast.LENGTH_SHORT).show();
					}
				})
				.setNegativeButton(R.string.deny, (dialog, which) -> {
					if (!supplier.get()) {
						consumer.accept(true);
					}
				})
				.setNeutralButton(R.string.close_app, (dialog, which) ->
						setIntent(null))
				.setCancelable(false)
				.show();
		this.allowCmdDialog = allowCmdDialog;
		var allowCmdRunnable = new Runnable() {

			@Override
			public void run() {
				if (isFinishing() || isDestroyed()) {
					return;
				}
				var intent = getIntent();
				if (intent == null || intent.getAction() == null) {
					callForegroundServiceAndFinish();
				} else if (allowCmdDialog.isShowing() && !supplier.get()) {
					BaseForegroundService.MAIN_HANDLER.postDelayed(this, 100L);
				} else if (requestRequestedPermissions() == null) {
					callForegroundServiceAndFinish();
				}
			}
		};
		BaseForegroundService.MAIN_HANDLER.post(allowCmdRunnable);
		this.allowCmdRunnable = allowCmdRunnable;
	}

	private boolean request(
			String permission,
			Supplier<Boolean> supplier,
			Consumer<Boolean> consumer,
			String action,
			RequestCode requestCode) {
		if (supplier.get()) {
			return false;
		}
		var intent = new Intent(action, Uri.fromParts("package", getPackageName(), null));
		if (isActivityFound(intent) ||
				isActivityFound(intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))) {
			tryStartActivityForResult(intent, requestCode.ordinal(), null);
		} else {
			showAllowCmd(permission, supplier, consumer);
		}
		return true;
	}

	private String[] requestRequestedPermissions() {
		try {
			var packageInfo = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS);
			var set = packageInfo.requestedPermissions != null
					? new HashSet<>(Arrays.asList(packageInfo.requestedPermissions))
					: Collections.<String>emptySet();
			if (set.remove(Manifest.permission.REQUEST_INSTALL_PACKAGES) &&
					BaseForegroundService.getUpdate(packageInfo, getVersionName()) != null &&
					request(
							"REQUEST_INSTALL_PACKAGES",
							this::isInstallPackagesRequester,
							this::setInstallPackagesRequester,
							Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
							RequestCode.REQUEST_INSTALL_PACKAGES)) {
				tryStopService(new Intent(this, getForegroundServiceClass()));
				return new String[]{Manifest.permission.REQUEST_INSTALL_PACKAGES};
			}
			if (set.remove(MANAGE_EXTERNAL_STORAGE) &&
					request(
							"MANAGE_EXTERNAL_STORAGE",
							this::isExternalStorageManager,
							this::setExternalStorageManager,
							ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
							RequestCode.MANAGE_EXTERNAL_STORAGE)) {
				return new String[]{MANAGE_EXTERNAL_STORAGE};
			}
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
				set.remove(FOREGROUND_SERVICE);
			}
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
				set.remove(UPDATE_PACKAGES_WITHOUT_USER_ACTION);
			}
			if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
				set.remove(Manifest.permission.READ_EXTERNAL_STORAGE);
				set.remove(Manifest.permission.WRITE_EXTERNAL_STORAGE);
			}
			if (!set.isEmpty()) {
				var permissions = set.toArray(new String[]{});
				requestPermissions(permissions, RequestCode.REQUESTED_PERMISSIONS.ordinal());
				return permissions;
			}
		} catch (Throwable t) {
			Log.w(TAG, t);
		}
		return null;
	}

	private void callForegroundServiceAndFinish() {
		var intent = getIntent();
		try {
			var service = (intent != null ? new Intent(intent) : new Intent())
					.setClass(this, getForegroundServiceClass());
			if (service.getAction() == null) {
				tryStopService(service);
				BaseForegroundService.tryShowDifferent(this, getVersionName());
			} else {
				startForegroundService(service);
			}
		} catch (Throwable t) {
			Log.w(TAG, t);
		}
		finish();
	}

	@Override
	protected void onResume() {
		super.onResume();
		var intent = getIntent();
		if (intent == null || intent.getAction() == null ||
				requestRequestedPermissions() == null) {
			callForegroundServiceAndFinish();
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		hideAllowCmd();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
		if (requestCode == RequestCode.REQUESTED_PERMISSIONS.ordinal()) {
			callForegroundServiceAndFinish();
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == RequestCode.REQUEST_INSTALL_PACKAGES.ordinal()) {
			setInstallPackagesRequester(!isInstallPackagesRequester());
		} else if (requestCode == RequestCode.MANAGE_EXTERNAL_STORAGE.ordinal()) {
			setExternalStorageManager(!isExternalStorageManager());
		}
	}

	@Override
	protected void onNewIntent(Intent intent) {
		setIntent(intent);
	}
}
