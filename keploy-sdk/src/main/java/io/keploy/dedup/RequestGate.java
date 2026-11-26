package io.keploy.dedup;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

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
 * a ThreadPoolTaskExecutor) or to standard JDK executors/CompletableFuture
 * from rewritten application classes counts as that request's until it is done:
 * ServerEntries passes each task, and the executor it is for, to
 * {@link #hand(Object, Runnable)}, {@link #hand(Object, Callable)}, or the
 * static call-site helpers ({@link #execute}, {@link #submit},
 * {@link #runAsync}, {@link #supplyAsync}), which, on a thread that runs a
 * request or such a task, counts the task in the window that thread counts in,
 * from the hand-off until the task returns, throws, is rejected, or is
 * cancelled/timed out before running. On a thread in a scheduled job's or
 * message listener's run (JobGate), or in a task such a run handed off, the
 * task counts as a run of that job while it runs, so a test whose window it
 * runs in is marked with the job; END does not wait for it. A task handed from
 * any other thread (a plain scheduler's, the app's own), or to an executor
 * whose pool may order tasks, override task hooks, silently discard tasks, or
 * delegate opaquely (OrderedPools), is not counted.
 *
 * <p>Note on pool queue visibility: for {@code submit} and
 * {@code CompletableFuture}, the JDK already wraps user tasks in
 * {@code FutureTask}/{@code AsyncRun}; for direct {@code execute(Runnable)}
 * call sites in rewritten application classes, {@link #remove} and
 * {@link #shutdownNow} preserve identity and unwrap queued wrappers, while
 * custom {@code ThreadPoolExecutor} subclasses that override
 * {@code beforeExecute}/{@code afterExecute} are skipped by
 * {@link OrderedPools}. Direct inspection of {@code getQueue()} on a plain
 * {@code ThreadPoolExecutor} that casts elements to an app-private subtype
 * without {@link Comparable} is the only case where the wrapper could be
 * visible.
 *
 * <p>Public only for the server, Spring, and rewritten application classes
 * ServerEntries changes, which call it from their own packages; not an API.
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
     * already counted, for a task that is {@link Comparable}, or for an
     * executor whose pool may order its tasks by their type, it returns the
     * task (and unwraps an already-wrapped task if the inner executor is
     * ordered/unsafe, releasing its pending window permit).
     *
     * @param executor the executor the task is for, or null
     * @param task the task the executor is handed
     * @return what the executor runs instead
     */
    public static Runnable hand(Object executor, Runnable task) {
        if (task == null || task instanceof Comparable) {
            return task;
        }
        if (task instanceof Handed) {
            if (OrderedPools.orders(executor)) {
                Handed existing = (Handed) task;
                existing.releaseIfPending();
                return existing.task;
            }
            return task;
        }
        Window window = INSIDE.get();
        String job = window == null ? JobGate.running() : null;
        if ((window == null && job == null) || OrderedPools.orders(executor)) {
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
     * Counts a task an executor is handed as {@link #hand(Object, Runnable)}
     * does, and returns it as it is where that does.
     *
     * @param executor the executor the task is for, or null
     * @param task the task the executor is handed
     * @param <T> what the task returns
     * @return what the executor runs instead
     */
    public static <T> Callable<T> hand(Object executor, Callable<T> task) {
        if (task == null || task instanceof Comparable) {
            return task;
        }
        if (task instanceof HandedCall) {
            if (OrderedPools.orders(executor)) {
                @SuppressWarnings("unchecked")
                HandedCall<T> existing = (HandedCall<T>) task;
                existing.releaseIfPending();
                return existing.task;
            }
            return task;
        }
        Window window = INSIDE.get();
        String job = window == null ? JobGate.running() : null;
        if ((window == null && job == null) || OrderedPools.orders(executor)) {
            return task;
        }
        HandedCall<T> handed = new HandedCall<>(window, job, task);
        if (window != null) {
            window.running.incrementAndGet();
        }
        return handed;
    }

    /**
     * Counts a {@link Supplier} handed to {@link CompletableFuture#supplyAsync}
     * on a request or job thread until it completes.
     *
     * @param executor the executor the supplier is for, or null
     * @param supplier the supplier handed off
     * @param <T> what the supplier returns
     * @return what runs instead
     */
    public static <T> Supplier<T> handSupplier(Object executor, Supplier<T> supplier) {
        if (supplier == null || supplier instanceof Comparable) {
            return supplier;
        }
        if (supplier instanceof HandedSupplier) {
            if (OrderedPools.orders(executor)) {
                @SuppressWarnings("unchecked")
                HandedSupplier<T> existing = (HandedSupplier<T>) supplier;
                existing.releaseIfPending();
                return existing.task;
            }
            return supplier;
        }
        Window window = INSIDE.get();
        String job = window == null ? JobGate.running() : null;
        if ((window == null && job == null) || OrderedPools.orders(executor)) {
            return supplier;
        }
        HandedSupplier<T> handed = new HandedSupplier<>(window, job, supplier);
        if (window != null) {
            window.running.incrementAndGet();
        }
        return handed;
    }

    /**
     * Call-site wrapper for {@link Executor#execute(Runnable)}: propagates the
     * current request window (or job run) across standard JDK executors whose
     * classes live in the bootstrap loader.
     */
    public static void execute(Executor executor, Runnable task) {
        Runnable handed = hand(executor, task);
        try {
            executor.execute(handed);
        } catch (Throwable t) {
            dropped(handed, task);
            throw t;
        }
    }

    /** Call-site wrapper for {@link ExecutorService#submit(Runnable)}. */
    public static Future<?> submit(ExecutorService executor, Runnable task) {
        Runnable handed = hand(executor, task);
        Future<?> future;
        try {
            future = executor.submit(handed);
        } catch (Throwable t) {
            dropped(handed, task);
            throw t;
        }
        return wrapFuture(future, handed, task);
    }

    /** Call-site wrapper for {@link ExecutorService#submit(Runnable, Object)}. */
    public static <T> Future<T> submit(ExecutorService executor, Runnable task, T result) {
        Runnable handed = hand(executor, task);
        Future<T> future;
        try {
            future = executor.submit(handed, result);
        } catch (Throwable t) {
            dropped(handed, task);
            throw t;
        }
        return wrapFuture(future, handed, task);
    }

    /** Call-site wrapper for {@link ExecutorService#submit(Callable)}. */
    public static <T> Future<T> submit(ExecutorService executor, Callable<T> task) {
        Callable<T> handed = hand(executor, task);
        Future<T> future;
        try {
            future = executor.submit(handed);
        } catch (Throwable t) {
            dropped(handed, task);
            throw t;
        }
        return wrapFuture(future, handed, task);
    }

    /** Call-site wrapper for {@link CompletableFuture#runAsync(Runnable)}. */
    public static CompletableFuture<Void> runAsync(Runnable task) {
        Runnable handed = hand(null, task);
        CompletableFuture<Void> cf;
        try {
            cf = CompletableFuture.runAsync(handed);
        } catch (Throwable t) {
            dropped(handed, task);
            throw t;
        }
        attachCompletionRelease(cf, handed, task);
        return cf;
    }

    /** Call-site wrapper for {@link CompletableFuture#runAsync(Runnable, Executor)}. */
    public static CompletableFuture<Void> runAsync(Runnable task, Executor executor) {
        Runnable handed = hand(executor, task);
        CompletableFuture<Void> cf;
        try {
            cf = CompletableFuture.runAsync(handed, executor);
        } catch (Throwable t) {
            dropped(handed, task);
            throw t;
        }
        attachCompletionRelease(cf, handed, task);
        return cf;
    }

    /** Call-site wrapper for {@link CompletableFuture#supplyAsync(Supplier)}. */
    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        Supplier<T> handed = handSupplier(null, supplier);
        CompletableFuture<T> cf;
        try {
            cf = CompletableFuture.supplyAsync(handed);
        } catch (Throwable t) {
            dropped(handed, supplier);
            throw t;
        }
        attachCompletionRelease(cf, handed, supplier);
        return cf;
    }

    /** Call-site wrapper for {@link CompletableFuture#supplyAsync(Supplier, Executor)}. */
    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier, Executor executor) {
        Supplier<T> handed = handSupplier(executor, supplier);
        CompletableFuture<T> cf;
        try {
            cf = CompletableFuture.supplyAsync(handed, executor);
        } catch (Throwable t) {
            dropped(handed, supplier);
            throw t;
        }
        attachCompletionRelease(cf, handed, supplier);
        return cf;
    }

    /**
     * Call-site wrapper for {@link ThreadPoolExecutor#remove(Runnable)}: finds
     * and removes the wrapped {@link Handed} entry if {@code task} was wrapped,
     * releasing its pending window permit so END does not wait for it.
     */
    public static boolean remove(ThreadPoolExecutor executor, Runnable task) {
        if (executor.remove(task)) {
            return true;
        }
        if (task == null) {
            return false;
        }
        for (Runnable queued : executor.getQueue()) {
            if (queued instanceof Handed) {
                Handed handed = (Handed) queued;
                if (handed.task == task && executor.remove(handed)) {
                    handed.releaseIfPending();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Call-site wrapper for {@link ExecutorService#shutdownNow()}: releases
     * pending window permits for drained wrappers and returns the app's
     * original tasks so callers casting them never see {@link Handed}.
     */
    public static List<Runnable> shutdownNow(ExecutorService executor) {
        List<Runnable> pending = executor.shutdownNow();
        if (pending == null || pending.isEmpty()) {
            return pending;
        }
        List<Runnable> unwrapped = null;
        for (int i = 0; i < pending.size(); i++) {
            Runnable item = pending.get(i);
            if (item instanceof Handed) {
                Handed handed = (Handed) item;
                handed.releaseIfPending();
                if (unwrapped == null) {
                    unwrapped = new ArrayList<>(pending);
                }
                unwrapped.set(i, handed.task);
            }
        }
        return unwrapped != null ? unwrapped : pending;
    }

    private static <T> Future<T> wrapFuture(Future<T> future, Object handed, Object task) {
        if (future != null && handed != task && handed instanceof Hand) {
            return new CancelAwareFuture<>(future, (Hand) handed);
        }
        return future;
    }

    private static void attachCompletionRelease(CompletableFuture<?> cf, Object handed, Object task) {
        if (cf != null && handed != task && handed instanceof Hand) {
            final Hand hand = (Hand) handed;
            cf.whenComplete((result, error) -> hand.releaseIfPending());
        }
    }

    /**
     * Counts a task {@link #hand(Object, Runnable)} counted as done when the
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
    // returns or throws, when the executor did not take it, or when it is
    // cancelled/timed out/removed/drained while still pending. Or, with no
    // window, a task a job's run handed off: a run of that job while it
    // runs, and nothing to count done when the executor did not take it.
    private abstract static class Hand {

        private static final int STATE_PENDING = 0;
        private static final int STATE_RUNNING = 1;
        private static final int STATE_DONE = 2;

        private static final AtomicIntegerFieldUpdater<Hand> STATE =
                AtomicIntegerFieldUpdater.newUpdater(Hand.class, "state");

        // The window it counts in, or null for a job's task.
        final Window window;
        // The job whose run handed it off, when window is null.
        final String job;
        private volatile int state;

        Hand(Window window, String job) {
            this.window = window;
            this.job = job;
        }

        // release marks this task done unconditionally (on exit or rejected drop).
        final void release() {
            if (window != null && STATE.getAndSet(this, STATE_DONE) != STATE_DONE) {
                done(window);
            }
        }

        // releaseIfPending marks this task done only if it has not started
        // running yet: cancellation/timeout of an already-running task waits
        // for its finally block's exit() so the window stays open while app
        // code is still executing.
        final void releaseIfPending() {
            if (window != null && STATE.compareAndSet(this, STATE_PENDING, STATE_DONE)) {
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
            STATE.compareAndSet(this, STATE_PENDING, STATE_RUNNING);
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

    private static final class CancelAwareFuture<T> implements Future<T> {

        private final Future<T> delegate;
        private final Hand hand;

        CancelAwareFuture(Future<T> delegate, Hand hand) {
            this.delegate = delegate;
            this.hand = hand;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = delegate.cancel(mayInterruptIfRunning);
            if (cancelled) {
                hand.releaseIfPending();
            }
            return cancelled;
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            return delegate.get();
        }

        @Override
        public T get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return delegate.get(timeout, unit);
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

        final Callable<T> task;

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

    private static final class HandedSupplier<T> extends Hand implements Supplier<T> {

        final Supplier<T> task;

        HandedSupplier(Window window, String job, Supplier<T> task) {
            super(window, job);
            this.task = task;
        }

        @Override
        public T get() {
            Object outer = enter();
            try {
                return task.get();
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
