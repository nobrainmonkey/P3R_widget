package app.p3r.dayplanner.v3.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Runs planner calls off the main thread and delivers results back on it.
 *
 * <p>All work shares one daemon thread, so writes issued by the UI are applied in the order the
 * player made them. Callbacks are dropped when their owning activity is finishing or destroyed.
 */
public final class Async {
    private static final String TAG = "P3R.ui";
    private static final ExecutorService DEFAULT = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "p3r-ui-io");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Executor executor = DEFAULT;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Async() {}

    /** Replaces the background executor (tests use a direct executor for determinism). */
    public static void setExecutorForTest(Executor replacement) {
        executor = replacement == null ? DEFAULT : replacement;
    }

    /**
     * Runs {@code work} in the background and passes its result to {@code onDone} on the main
     * thread. Failures go to {@code onError} (also on the main thread), or are logged when null.
     */
    public static <T> void run(Activity owner, Callable<T> work, Consumer<T> onDone, Consumer<Throwable> onError) {
        executor.execute(() -> {
            T result;
            try {
                result = work.call();
            } catch (Throwable error) {
                Log.w(TAG, "background work failed", error);
                deliver(owner, () -> {
                    if (onError != null) {
                        onError.accept(error);
                    }
                });
                return;
            }
            deliver(owner, () -> {
                if (onDone != null) {
                    onDone.accept(result);
                }
            });
        });
    }

    /** Fire-and-forget background work without a result. */
    public static void run(Activity owner, Runnable work) {
        run(owner, () -> {
            work.run();
            return Boolean.TRUE;
        }, null, null);
    }

    private static void deliver(Activity owner, Runnable callback) {
        MAIN.post(() -> {
            if (owner != null && (owner.isFinishing() || owner.isDestroyed())) {
                return;
            }
            callback.run();
        });
    }
}
