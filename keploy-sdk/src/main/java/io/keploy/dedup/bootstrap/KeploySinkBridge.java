package io.keploy.dedup.bootstrap;

/**
 * Bootstrap-classloader bridge invoked from bytecode injected into JDK
 * concurrency sinks ({@code Thread.start}, {@code VirtualThread.start},
 * {@code ThreadPoolExecutor.execute}, {@code ScheduledThreadPoolExecutor.delayedExecute},
 * {@code ForkJoinPool} external submission, and {@code Timer.sched}).
 *
 * <p>Loaded exclusively by the bootstrap class loader (via
 * {@code Instrumentation.appendToBootstrapClassLoaderSearch}) and uses only
 * {@code java.lang.*} types so {@code java.base} classes can invoke it across
 * JDK 8, 11, 17, and 21 without any JVM CLI flags.
 */
public final class KeploySinkBridge {

    /**
     * Runtime sink handler registered by the Keploy agent once initialized.
     * Uses only {@code java.lang.*} types so this interface stays loadable by
     * the bootstrap class loader.
     */
    public interface Handler {
        /**
         * Invoked at entry to {@code Thread.start}.
         *
         * @param thread the platform thread being started
         */
        void onThreadStart(Thread thread);

        /**
         * Invoked at entry to {@code VirtualThread.start} (JDK 21+).
         *
         * @param thread the virtual thread being started
         */
        void onVirtualThreadStart(Thread thread);

        /**
         * Invoked at entry to {@code ThreadPoolExecutor.execute}.
         *
         * @param executor target pool instance
         * @param command runnable being executed
         */
        void onTpeExecute(Object executor, Object command);

        /**
         * Invoked at entry to {@code ScheduledThreadPoolExecutor.delayedExecute}.
         *
         * @param executor target scheduled pool instance
         * @param task scheduled task
         */
        void onStpeDelayedExecute(Object executor, Object task);

        /**
         * Invoked at entry to {@code ForkJoinPool} external submission.
         *
         * @param pool target fork-join pool instance
         * @param task submitted task
         */
        void onFjpExternalSubmit(Object pool, Object task);

        /**
         * Invoked at entry to {@code Timer.sched}.
         *
         * @param timer target timer instance
         * @param task timer task being scheduled
         */
        void onTimerSched(Object timer, Object task);
    }

    /** Active runtime handler installed by the Keploy agent. */
    public static volatile Handler handler;

    /** Diagnostic flag set if a sink callback ever threw unexpectedly. */
    public static volatile Throwable lastCallbackError;

    // Re-entrancy and agent-internal depth guard. Always > 0 while executing
    // inside a sink callback or inside Keploy's own agent threads/pools, so
    // Keploy's own threads never latch and sink callbacks can never recurse.
    private static final ThreadLocal<Integer> INTERNAL_DEPTH = new ThreadLocal<Integer>();

    private static void reportError(Throwable t) {
        if (lastCallbackError == null) {
            lastCallbackError = t;
            try {
                System.err.println("KEPLOY_SINK_BRIDGE_ERROR: " + t);
                t.printStackTrace(System.err);
            } catch (Throwable ignored) {
                // Never disrupt JDK caller.
            }
        }
    }

    private KeploySinkBridge() {
    }

    /** Marks the current thread as executing Keploy-internal work. */
    public static void enterInternal() {
        Integer cur = INTERNAL_DEPTH.get();
        INTERNAL_DEPTH.set(cur == null ? Integer.valueOf(1) : Integer.valueOf(cur.intValue() + 1));
    }

    /** Unmarks one level of Keploy-internal work on the current thread. */
    public static void exitInternal() {
        Integer cur = INTERNAL_DEPTH.get();
        if (cur == null || cur.intValue() <= 1) {
            INTERNAL_DEPTH.remove();
        } else {
            INTERNAL_DEPTH.set(Integer.valueOf(cur.intValue() - 1));
        }
    }

    /**
     * Returns whether the current thread is inside Keploy-internal work or a
     * sink callback.
     *
     * @return {@code true} when internal depth is positive
     */
    public static boolean isInternal() {
        Integer cur = INTERNAL_DEPTH.get();
        return cur != null && cur.intValue() > 0;
    }

    /**
     * Bridge entry point for {@code Thread.start}.
     *
     * @param thread the platform thread being started
     */
    public static void onThreadStart(Thread thread) {
        Handler h = handler;
        if (h == null || isInternal()) {
            return;
        }
        enterInternal();
        try {
            h.onThreadStart(thread);
        } catch (Throwable t) {
            reportError(t);
        } finally {
            exitInternal();
        }
    }

    /**
     * Bridge entry point for {@code VirtualThread.start} (JDK 21+).
     *
     * @param thread the virtual thread being started
     */
    public static void onVirtualThreadStart(Thread thread) {
        Handler h = handler;
        if (h == null || isInternal()) {
            return;
        }
        enterInternal();
        try {
            h.onVirtualThreadStart(thread);
        } catch (Throwable t) {
            reportError(t);
        } finally {
            exitInternal();
        }
    }

    /**
     * Bridge entry point for {@code ThreadPoolExecutor.execute}.
     *
     * @param executor target pool instance
     * @param command runnable being executed
     */
    public static void onTpeExecute(Object executor, Object command) {
        Handler h = handler;
        if (h == null || isInternal()) {
            return;
        }
        enterInternal();
        try {
            h.onTpeExecute(executor, command);
        } catch (Throwable t) {
            reportError(t);
        } finally {
            exitInternal();
        }
    }

    /**
     * Bridge entry point for {@code ScheduledThreadPoolExecutor.delayedExecute}.
     *
     * @param executor target scheduled pool instance
     * @param task scheduled task
     */
    public static void onStpeDelayedExecute(Object executor, Object task) {
        Handler h = handler;
        if (h == null || isInternal()) {
            return;
        }
        enterInternal();
        try {
            h.onStpeDelayedExecute(executor, task);
        } catch (Throwable t) {
            reportError(t);
        } finally {
            exitInternal();
        }
    }

    /**
     * Bridge entry point for {@code ForkJoinPool} external submission.
     *
     * @param pool target fork-join pool instance
     * @param task submitted task
     */
    public static void onFjpExternalSubmit(Object pool, Object task) {
        Handler h = handler;
        if (h == null || isInternal()) {
            return;
        }
        enterInternal();
        try {
            h.onFjpExternalSubmit(pool, task);
        } catch (Throwable t) {
            reportError(t);
        } finally {
            exitInternal();
        }
    }

    /**
     * Bridge entry point for {@code Timer.sched}.
     *
     * @param timer target timer instance
     * @param task timer task being scheduled
     */
    public static void onTimerSched(Object timer, Object task) {
        Handler h = handler;
        if (h == null || isInternal()) {
            return;
        }
        enterInternal();
        try {
            h.onTimerSched(timer, task);
        } catch (Throwable t) {
            reportError(t);
        } finally {
            exitInternal();
        }
    }
}
