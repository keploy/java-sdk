package io.keploy.dedup;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.locks.LockSupport;

/**
 * RequestGate counts the requests the app's server is running, so that a
 * test's END can wait until the requests of that test are done before it
 * reads the test's hits.
 *
 * <p>A server writes a request's response before the request is done: a
 * filter's code after the chain, a request log, runs after Keploy has the
 * response and has sent the test's END. Read at once, the END missed those
 * hits, and the next START cleared them as hits between two tests.
 * ServerEntries makes each request of Tomcat, Jetty and Undertow call
 * {@link #enter()} where the server starts running it and {@link #exit(Object)}
 * where it is done with it, the response finished.
 *
 * <p>The work a request hands to a Spring TaskExecutor (an event listener
 * on an async multicaster, an {@code @Async} method, a task submitted to
 * a ThreadPoolTaskExecutor) counts as that request's until it is done:
 * ServerEntries makes those executors pass each task they are handed to
 * {@link #hand(Runnable)} or {@link #hand(Callable)}, which, on a thread
 * that runs a request or such a task, counts the task in the window that
 * thread counts in, from the hand-off until the task returns or throws. On
 * a thread in a scheduled job's run (JobGate), or in a task such a run
 * handed off, the task counts as a run of that job while it runs, so a
 * test whose window it runs in is marked with the job; END does not wait
 * for it. A task handed from any other thread (a plain scheduler's, the
 * app's own) is not counted.
 *
 * <p>Public only for the server and Spring classes ServerEntries changes,
 * which call it from their own packages; not an API.
 */
public final class RequestGate {

    // The window of the test running, made anew at each START and when END
    // waits: END waits only for the requests that came in since the START,
    // never for one that runs from before (a request of the test before, cut
    // at its bound, or a stream that never ends) or one that comes in after
    // END.
    private static volatile Window current = new Window();

    // The window the thread's request, or the task a request handed off it
    // runs, counts in; null on any other thread.
    private static final ThreadLocal<Window> INSIDE = new ThreadLocal<>();

    private RequestGate() {
    }

    /**
     * Counts a request as running, in the window of the test running now.
     *
     * @return the token to pass to {@link #exit(Object)} when it is done
     */
    public static Object enter() {
        Window window = current;
        Window outer = INSIDE.get();
        // A request in a request (a server's dispatch on the same thread)
        // gives the thread back its outer window when it is done.
        Object token = outer == null ? window : new Nested(window, outer);
        INSIDE.set(window);
        // Counted last: whatever failed before it (out of memory) leaves
        // nothing to undo.
        window.running.incrementAndGet();
        return token;
    }

    /**
     * Counts the request {@link #enter()} returned the token of as done, on
     * the thread that entered it, after any request it entered since.
     *
     * @param token what {@link #enter()} returned
     */
    public static void exit(Object token) {
        if (token instanceof Window) {
            INSIDE.remove();
            done((Window) token);
        } else if (token instanceof Nested) {
            Nested nested = (Nested) token;
            INSIDE.set(nested.outer);
            done(nested.window);
        }
    }

    /**
     * Counts a task an executor is handed on a thread that runs a request,
     * or a task a request handed off, in that thread's window until the
     * task is done, and returns what the executor runs instead of it. On a
     * thread in a scheduled job's run, it returns a task that counts as a
     * run of that job while it runs. On any other thread, for a task
     * already counted, or for a task that is {@link Comparable}, it returns
     * the task.
     *
     * <p>A Comparable task is not counted: an executor can order its tasks
     * by their own type (a pool whose queue is a PriorityBlockingQueue), and
     * one handed a task of another type throws. END does not wait for such a
     * task, and it is no job's run.
     *
     * @param task the task the executor is handed
     * @return what the executor runs instead
     */
    public static Runnable hand(Runnable task) {
        if (task == null || task instanceof Handed || task instanceof Comparable) {
            return task;
        }
        Window window = INSIDE.get();
        String job = window == null ? JobGate.running() : null;
        if (window == null && job == null) {
            return task;
        }
        // A task that is a Future stays one, for the executor's cancel at
        // shutdown and its purge of cancelled tasks.
        Handed handed = task instanceof Future ? new HandedFuture(window, job, task)
                : new Handed(window, job, task);
        // Counted last, as enter() counts: a failed allocation (out of
        // memory) leaves nothing counted that no task would release.
        if (window != null) {
            window.running.incrementAndGet();
        }
        return handed;
    }

    /**
     * Counts a task an executor is handed as {@link #hand(Runnable)} does,
     * and returns a Comparable task as it is.
     *
     * @param task the task the executor is handed
     * @param <T> what the task returns
     * @return what the executor runs instead
     */
    public static <T> Callable<T> hand(Callable<T> task) {
        if (task == null || task instanceof HandedCall || task instanceof Comparable) {
            return task;
        }
        Window window = INSIDE.get();
        String job = window == null ? JobGate.running() : null;
        if (window == null && job == null) {
            return task;
        }
        HandedCall<T> handed = new HandedCall<>(window, job, task);
        if (window != null) {
            window.running.incrementAndGet();
        }
        return handed;
    }

    /**
     * Counts a task {@link #hand(Runnable)} counted as done when the
     * executor threw instead of taking it (it rejected it, or was shut
     * down), so that END does not wait for a task that never runs.
     *
     * @param handed what hand returned
     * @param task what hand was passed
     */
    public static void dropped(Object handed, Object task) {
        if (handed != task && handed instanceof Hand) {
            ((Hand) handed).release();
        }
    }

    private static void done(Window window) {
        if (window.running.decrementAndGet() == 0) {
            Thread waiter = window.waiter;
            if (waiter != null) {
                LockSupport.unpark(waiter);
            }
        }
    }

    // open starts the window of a test: from now on, END waits for the
    // requests that come in, and for no request that came in before.
    static void open() {
        current = new Window();
    }

    // running is how many requests of the current window are running.
    static int running() {
        return current.running.get();
    }

    // await closes the current window and waits until none of its requests
    // runs, for at most timeoutNanos, and says whether none runs. A request
    // that comes in from now on (a readiness probe, a metrics scrape, the
    // next client) counts in a window of its own, so it does not draw the
    // wait out. One thread waits at a time (the worker, under its test lock).
    static boolean await(long timeoutNanos) {
        Window window = current;
        current = new Window();
        if (window.running.get() <= 0) {
            return true;
        }
        // Set before the count is read again: an exit that takes the count
        // to 0 after that read finds the waiter and wakes it, and one that
        // took it to 0 before is seen by the read.
        window.waiter = Thread.currentThread();
        try {
            long deadline = System.nanoTime() + timeoutNanos;
            while (window.running.get() > 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0 || Thread.currentThread().isInterrupted()) {
                    return false;
                }
                LockSupport.parkNanos(RequestGate.class, left);
            }
            return true;
        } finally {
            window.waiter = null;
        }
    }

    private static final class Window {

        private final AtomicInteger running = new AtomicInteger();
        private volatile Thread waiter;
    }

    // Nested is the token of a request entered on a thread that already
    // counted in a window.
    private static final class Nested {

        private final Window window;
        private final Window outer;

        private Nested(Window window, Window outer) {
            this.window = window;
            this.outer = outer;
        }
    }

    // Hand is a task counted in a request's window: done once, when it
    // returns or throws, or when the executor did not take it. Or, with no
    // window, a task a job's run handed off: a run of that job while it
    // runs, and nothing to count done when the executor did not take it.
    private abstract static class Hand {

        private static final AtomicIntegerFieldUpdater<Hand> RELEASED =
                AtomicIntegerFieldUpdater.newUpdater(Hand.class, "released");

        // The window it counts in, or null for a job's task.
        final Window window;
        // The job whose run handed it off, when window is null.
        final String job;
        private volatile int released;

        Hand(Window window, String job) {
            this.window = window;
            this.job = job;
        }

        final void release() {
            if (window != null && RELEASED.compareAndSet(this, 0, 1)) {
                done(window);
            }
        }

        // enter makes the running thread count in the task's window, so
        // that what the task hands off counts too, and returns the window
        // the thread counted in before; for a job's task, it starts a run
        // of the job (so what the task hands off is the job's too), and
        // returns its token.
        final Object enter() {
            if (window == null) {
                return JobGate.enterAs(job);
            }
            Window outer = INSIDE.get();
            INSIDE.set(window);
            return outer;
        }

        final void exit(Object outer) {
            if (window == null) {
                JobGate.exit(outer);
                return;
            }
            if (outer == null) {
                INSIDE.remove();
            } else {
                INSIDE.set((Window) outer);
            }
            release();
        }
    }

    private static class Handed extends Hand implements Runnable {

        final Runnable task;

        Handed(Window window, String job, Runnable task) {
            super(window, job);
            this.task = task;
        }

        @Override
        public final void run() {
            Object outer = enter();
            try {
                task.run();
            } finally {
                exit(outer);
            }
        }

        @Override
        public String toString() {
            return String.valueOf(task);
        }
    }

    private static final class HandedFuture extends Handed implements RunnableFuture<Object> {

        HandedFuture(Window window, String job, Runnable task) {
            super(window, job, task);
        }

        @SuppressWarnings("unchecked")
        private Future<Object> future() {
            return (Future<Object>) task;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return future().cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return future().isCancelled();
        }

        @Override
        public boolean isDone() {
            return future().isDone();
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            return future().get();
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return future().get(timeout, unit);
        }
    }

    private static final class HandedCall<T> extends Hand implements Callable<T> {

        private final Callable<T> task;

        HandedCall(Window window, String job, Callable<T> task) {
            super(window, job);
            this.task = task;
        }

        @Override
        public T call() throws Exception {
            Object outer = enter();
            try {
                return task.call();
            } finally {
                exit(outer);
            }
        }

        @Override
        public String toString() {
            return String.valueOf(task);
        }
    }
}
