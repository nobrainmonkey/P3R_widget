package app.p3r.dayplanner.v3.widget;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The widget's single background thread. All snapshot reads, outcome writes, cursor changes and
 * RemoteViews building run here in order, so concurrent taps on several widgets never interleave.
 */
public final class WidgetWorker {

    /** Delayed execution (fade settle). */
    public interface Scheduler {
        void schedule(Runnable task, long delayMillis);
    }

    private static volatile Executor executorOverride;
    private static volatile Scheduler schedulerOverride;

    private WidgetWorker() {}

    private static final class DefaultHolder {
        static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "p3r-widget");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Runs {@code task} on the widget thread (or the test executor). */
    public static void run(Runnable task) {
        Executor test = executorOverride;
        if (test != null) test.execute(task);
        else DefaultHolder.EXECUTOR.execute(task);
    }

    /** Runs {@code task} on the widget thread after {@code delayMillis}. */
    public static void schedule(Runnable task, long delayMillis) {
        Scheduler test = schedulerOverride;
        if (test != null) test.schedule(task, delayMillis);
        else DefaultHolder.EXECUTOR.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
    }

    /** Test hook: e.g. a direct executor and a recording scheduler; nulls restore the real thread. */
    public static void setForTest(Executor executor, Scheduler scheduler) {
        executorOverride = executor;
        schedulerOverride = scheduler;
    }
}
