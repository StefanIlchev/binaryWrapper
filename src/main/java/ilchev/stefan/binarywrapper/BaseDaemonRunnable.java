package ilchev.stefan.binarywrapper;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

import javax.security.auth.Destroyable;

public abstract class BaseDaemonRunnable implements Runnable, Destroyable {

	private static final String TAG = "BaseDaemonRunnable";

	private final AssetManager assetManager;

	private final File assetsMarker;

	private final File bin;

	private volatile boolean isDestroyed = false;

	private Process process = null;

	private final Runnable destroyProcessRunnable = () -> {
		var process = this.process;
		if (process != null) {
			this.process = null;
			process.destroy();
		}
	};

	private final Runnable clearProcessRunnable = () ->
			process = null;

	public BaseDaemonRunnable(Context context) {
		assetManager = context.getAssets();
		assetsMarker = new File(context.getCodeCacheDir(), BuildConfig.LIBRARY_PACKAGE_NAME);
		bin = new File(context.getApplicationInfo().nativeLibraryDir);
	}

	protected Map<String, File> getSubprocessAssets() {
		return Collections.emptyMap();
	}

	protected abstract List<String> getSubprocessCmd();

	protected Map<String, String> getSubprocessEnv() {
		return Collections.emptyMap();
	}

	protected Set<Integer> getSubprocessExitValuesEnd() {
		return Collections.emptySet();
	}

	protected Set<Integer> getSubprocessExitValuesSkip() {
		return Collections.emptySet();
	}

	protected Set<Integer> getSubprocessExitValuesStart() {
		return Collections.emptySet();
	}

	protected int getSubprocessRetriesCount() {
		return 0;
	}

	protected long getSubprocessRetryDelay() {
		return 0L;
	}

	protected String getSubprocessTag() {
		return "Subprocess";
	}

	@Override
	public boolean equals(Object obj) {
		var daemonRunnable = obj instanceof BaseDaemonRunnable ? (BaseDaemonRunnable) obj : null;
		return daemonRunnable == this || daemonRunnable != null &&
				daemonRunnable.getSubprocessAssets().equals(getSubprocessAssets()) &&
				daemonRunnable.getSubprocessCmd().equals(getSubprocessCmd()) &&
				daemonRunnable.getSubprocessEnv().equals(getSubprocessEnv()) &&
				daemonRunnable.getSubprocessExitValuesEnd().equals(getSubprocessExitValuesEnd()) &&
				daemonRunnable.getSubprocessExitValuesSkip().equals(getSubprocessExitValuesSkip()) &&
				daemonRunnable.getSubprocessExitValuesStart().equals(getSubprocessExitValuesStart()) &&
				daemonRunnable.getSubprocessRetriesCount() == getSubprocessRetriesCount() &&
				daemonRunnable.getSubprocessRetryDelay() == getSubprocessRetryDelay() &&
				daemonRunnable.getSubprocessTag().equals(getSubprocessTag());
	}

	@Override
	public boolean isDestroyed() {
		return isDestroyed;
	}

	@Override
	public void destroy() {
		isDestroyed = true;
		if (Looper.myLooper() == BaseForegroundService.MAIN_HANDLER.getLooper()) {
			destroyProcessRunnable.run();
		} else {
			BaseForegroundService.MAIN_HANDLER.post(destroyProcessRunnable);
		}
	}

	private void extract(String src, File dst) throws Exception {
		if (dst.exists()) {
			if (assetsMarker.exists() || isDestroyed()) {
				return;
			}
			try (var stream = Files.walk(dst.toPath())) {
				stream.sorted(Comparator.reverseOrder())
						.map(Path::toFile)
						.forEach(File::delete);
			}
		} else {
			assetsMarker.delete();
		}
		var parent = dst.getParent();
		var deque = new ArrayDeque<String>();
		for (String node = src, name = dst.getName(); node != null && !isDestroyed(); node = name = deque.pollFirst()) {
			var children = assetManager.list(node);
			if (children != null) {
				for (var child : children) {
					deque.add(node + "/" + child);
				}
			}
			var path = parent != null ? Paths.get(parent, name) : Paths.get(name);
			try (var in = assetManager.open(node)) {
				Files.copy(in, path);
			} catch (FileNotFoundException ignore) {
				Files.createDirectories(path);
			}
		}
	}

	private void extract() throws Exception {
		for (var entry : getSubprocessAssets().entrySet()) {
			extract(entry.getKey(), entry.getValue());
			if (isDestroyed()) {
				return;
			}
		}
		assetsMarker.mkdirs();
	}

	private ProcessBuilder build() {
		var builder = new ProcessBuilder(getSubprocessCmd())
				.directory(bin)
				.redirectErrorStream(true);
		builder.environment()
				.putAll(getSubprocessEnv());
		return builder;
	}

	private Runnable toSetProcessRunnable(Process value) {
		return () -> {
			if (isDestroyed()) {
				value.destroy();
			} else {
				process = value;
			}
		};
	}

	private void execute() throws Exception {
		var builder = build();
		for (var attempt = 0; !isDestroyed(); Thread.sleep(getSubprocessRetryDelay())) {
			var process = builder.start();
			if (!BaseForegroundService.MAIN_HANDLER.post(toSetProcessRunnable(process))) {
				process.destroy();
				break;
			}
			try (var scanner = new Scanner(process.getInputStream())) {
				while (scanner.hasNextLine()) {
					var line = scanner.nextLine();
					Log.v(getSubprocessTag(), line);
				}
			}
			var exitValue = process.waitFor();
			Log.v(TAG, "SUBPROCESS_EXIT_VALUE = " + exitValue);
			if (isDestroyed() ||
					!BaseForegroundService.MAIN_HANDLER.post(clearProcessRunnable) ||
					getSubprocessExitValuesEnd().contains(exitValue)) {
				break;
			}
			if (getSubprocessExitValuesSkip().contains(exitValue)) {
				continue;
			}
			if (getSubprocessExitValuesStart().contains(exitValue)) {
				attempt = 0;
			} else if (++attempt > getSubprocessRetriesCount()) {
				break;
			}
		}
	}

	@Override
	public void run() {
		try {
			extract();
			execute();
		} catch (Throwable t) {
			Log.w(TAG, t);
		}
	}
}
