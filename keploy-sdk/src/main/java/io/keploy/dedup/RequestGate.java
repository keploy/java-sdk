package io.keploy.dedup;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
 * cancelled/timed out/drained before running. On a thread in a scheduled job's
 * or message listener's run (JobGate), or in a task such a run handed off, the
 * task counts as a run of that job while it runs (bounded by the original
 * hand-off deadline), so a test whose window it runs in is marked with the
 * job; END does not wait for it. A task handed from any other thread (a plain
 * scheduler's, the app's own), or to an executor whose pool may order tasks,
 * override task/submission hooks, use a non-standard rejection handler, or
 * delegate opaquely (OrderedPools), is not counted.
 *
 * <p>Note on pool queue visibility: {@link #submit} returns the executor's own
 * {@link Future} untouched (preserving casts to {@code Runnable},
 * {@code ScheduledFuture}, {@code ListenableFuture}, etc.), tracking pending
 * futures separately for cancellation, {@link #remove}, and
 * {@link #shutdownNow}. {@link OrderedPools} skips pools with non-standard
 * queues, custom rejection handlers, task-hook/{@code newTaskFor}/
 * {@code execute}/{@code submit} overrides, or opaque delegates, and rewritten
 * {@link #remove}/{@link #shutdownNow} call sites unwrap queued wrappers.
 * Callers that inspect {@code getQueue()} directly on a plain
 * {@code ThreadPoolExecutor}, or unrewritten code calling {@code remove}/
 * {@code shutdownNow} on a pool fed by rewritten {@code execute(Runnable)}
 * sites, may still observe {@code Handed}.
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

    // Weak associations from an executor's returned Future (e.g. FutureTask /
    // ScheduledFutureTask) to its Hand, so remove((Runnable) future) releases
    // its pending permit without wrapping the returned Future.
    private static final Map<Object, Hand> PENDING_BY_FUTURE = new WeakHashMap<>();

    // Weak associations from an Executor to weak tokens of its active Hand
    // instances (stored in a ConcurrentHashMap-backed set whose tokens
    // self-remove in O(1) on release, and dead tokens from cancelled/abandoned
    // tasks are drained via DEAD_HANDS), so shutdownNow() releases every queued
    // task's pending permit without retaining abandoned tasks or pinning pools.
    private static final Map<Executor, Set<HandRef>> PENDING_BY_EXECUTOR = new WeakHashMap<>();
    private static final ReferenceQueue<Hand> DEAD_HANDS = new ReferenceQueue<>();

    // Bounded poll slice in await() only when the window has tracked submit()
    // futures that could be cancelled externally without running.
    private static final long FUTURE_SWEEP_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    // Sweep cancelled/done futures in Window.track() every N inserts so
    // windows that never receive an END cannot grow unboundedly on cancelled
    // futures.
    private static final int FUTURE_SWEEP_STRIDE = 64;

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
        JobGate.Attribution job = window == null ? JobGate.runningAttribution() : null;
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
        JobGate.Attribution job = window == null ? JobGate.runningAttribution() : null;
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
        JobGate.Attribution job = window == null ? JobGate.runningAttribution() : null;
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
     *
     * @param executor target executor
     * @param task runnable to execute
     */
    public static void execute(Executor executor, Runnable task) {
        Runnable handed = hand(executor, task);
        registerForExecutor(executor, handed, task);
        try {
            executor.execute(handed);
        } catch (Throwable t) {
            dropped(handed, task);
            throw t;
        }
    }

    /**
     * Call-site wrapper for {@link ExecutorService#submit(Runnable)}.
     *
     * @param executor target executor service
     * @param task runnable to submit
     * @return the executor's own future
     */
    public static Future<?> submit(ExecutorService executor, Runnable task) {
        Runnable handed = hand(executor, task);
        registerForExecutor(executor, handed, task);
        Future<?> future;
        try {
            future = executor.submit(handed);
        } catch (Throwable t) {
            dropped(handed, task);
            throw t;
        }
        trackSubmitted(future, handed, task);
        return future;
    }

    /**
     * Call-site wrapper for {@link ExecutorService#submit(Runnable, Object)}.
     *
     * @param <T> result type
     * @param executor target executor service
     * @param task runnable to submit
     * @param result result returned on completion
     * @return the executor's own future
     */
    public static <T> Future<T> submit(ExecutorService executor, Runnable task, T result) {
        Runnable handed = hand(executor, task);
        registerForExecutor(executor, handed, task);
        Future<T> future;
        try {
            future = executor.submit(handed, result);
        } catch (Throwable t) {
            dropped(handed, task);
            throw t;
        }
        trackSubmitted(future, handed, task);
        return future;
    }

    /**
     * Call-site wrapper for {@link ExecutorService#submit(Callable)}.
     *
     * @param <T> result type
     * @param executor target executor service
     * @param task callable to submit
     * @return the executor's own future
     */
    public static <T> Future<T> submit(ExecutorService executor, Callable<T> task) {
        Callable<T> handed = hand(executor, task);
        registerForExecutor(executor, handed, task);
        Future<T> future;
        try {
            future = executor.submit(handed);
        } catch (Throwable t) {
            dropped(handed, task);
            throw t;
        }
        trackSubmitted(future, handed, task);
        return future;
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#runAsync(Runnable)}.
     *
     * @param task runnable to execute asynchronously
     * @return the created CompletableFuture
     */
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

    /**
     * Call-site wrapper for {@link CompletableFuture#runAsync(Runnable, Executor)}.
     *
     * @param task runnable to execute asynchronously
     * @param executor target executor
     * @return the created CompletableFuture
     */
    public static CompletableFuture<Void> runAsync(Runnable task, Executor executor) {
        Runnable handed = hand(executor, task);
        registerForExecutor(executor, handed, task);
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

    /**
     * Call-site wrapper for {@link CompletableFuture#supplyAsync(Supplier)}.
     *
     * @param <T> result type
     * @param supplier supplier to execute asynchronously
     * @return the created CompletableFuture
     */
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

    /**
     * Call-site wrapper for {@link CompletableFuture#supplyAsync(Supplier, Executor)}.
     *
     * @param <T> result type
     * @param supplier supplier to execute asynchronously
     * @param executor target executor
     * @return the created CompletableFuture
     */
    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier, Executor executor) {
        Supplier<T> handed = handSupplier(executor, supplier);
        registerForExecutor(executor, handed, supplier);
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
     * and removes either the original task (releasing any tracked submitted
     * Future permit, e.g. {@code tpe.remove((Runnable) future)}) or the
     * wrapped {@link Handed} entry if {@code task} was wrapped, releasing its
     * pending window permit so END does not wait for it.
     *
     * @param executor target thread pool
     * @param task runnable to remove
     * @return {@code true} if the task was removed
     */
    public static boolean remove(ThreadPoolExecutor executor, Runnable task) {
        if (executor.remove(task)) {
            Hand associated = takeFutureHand(task);
            if (associated != null) {
                associated.releaseIfPending();
            }
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
     * pending window permits for all queued tasks of {@code executor} (both
     * direct {@code execute} wrappers and {@code FutureTask}/
     * {@code ScheduledFutureTask}-wrapped submissions) and unwraps any
     * {@link Handed} items in the returned list so callers casting them never
     * see {@link Handed}.
     *
     * @param executor target executor service
     * @return list of tasks that never commenced execution
     */
    public static List<Runnable> shutdownNow(ExecutorService executor) {
        List<Runnable> pending = executor.shutdownNow();
        releaseAllPendingForExecutor(executor);
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
            } else {
                Hand associated = takeFutureHand(item);
                if (associated != null) {
                    associated.releaseIfPending();
                }
            }
        }
        return unwrapped != null ? unwrapped : pending;
    }

    private static void drainDeadHands() {
        for (Reference<? extends Hand> ref; (ref = DEAD_HANDS.poll()) != null; ) {
            HandRef hr = (HandRef) ref;
            hr.set.remove(hr);
        }
    }

    private static void registerForExecutor(Executor executor, Object handed, Object task) {
        drainDeadHands();
        if (executor == null || handed == task || !(handed instanceof Hand)) {
            return;
        }
        Hand hand = (Hand) handed;
        if (hand.window == null || hand.isReleased()) {
            return;
        }
        try {
            Set<HandRef> set;
            synchronized (PENDING_BY_EXECUTOR) {
                set = PENDING_BY_EXECUTOR.get(executor);
                if (set == null) {
                    set = ConcurrentHashMap.newKeySet();
                    PENDING_BY_EXECUTOR.put(executor, set);
                }
            }
            HandRef ref = new HandRef(hand, DEAD_HANDS, set);
            set.add(ref);
            hand.executorRef = ref;
            if (hand.isReleased()) {
                hand.executorRef = null;
                set.remove(ref);
            }
        } catch (Throwable ignored) {
            // Never let a broken Executor.hashCode/equals affect app execution.
        }
    }

    private static void releaseAllPendingForExecutor(Executor executor) {
        drainDeadHands();
        if (executor == null) {
            return;
        }
        Set<HandRef> set;
        try {
            synchronized (PENDING_BY_EXECUTOR) {
                set = PENDING_BY_EXECUTOR.remove(executor);
            }
        } catch (Throwable ignored) {
            return;
        }
        if (set == null) {
            return;
        }
        for (HandRef ref : set) {
            Hand hand = ref.get();
            if (hand != null) {
                hand.releaseIfPending();
            }
        }
        set.clear();
    }

    /**
     * Records a Future returned by a Spring executor's submit method so that if
     * the caller cancels or completes it before it runs, the request window
     * releases its permit without waiting for END to time out.
     *
     * @param result return value from the executor method
     * @param handed what hand returned
     * @param task original task passed to hand
     */
    public static void submitted(Object result, Object handed, Object task) {
        if (result instanceof CompletableFuture) {
            attachCompletionRelease((CompletableFuture<?>) result, handed, task);
        } else if (result instanceof Future) {
            trackSubmitted((Future<?>) result, handed, task);
        }
    }

    private static boolean isFutureTerminated(Future<?> future) {
        if (future == null) {
            return true;
        }
        try {
            return future.isCancelled() || future.isDone();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void trackSubmitted(Future<?> future, Object handed, Object task) {
        if (future == null || handed == task || !(handed instanceof Hand)) {
            return;
        }
        Hand hand = (Hand) handed;
        if (hand.window == null || hand.isReleased()) {
            return;
        }
        if (isFutureTerminated(future)) {
            hand.releaseIfPending();
            return;
        }
        hand.window.track(hand, future);
        if (hand.isReleased()) {
            hand.window.untrack(hand);
            return;
        }
        try {
            synchronized (PENDING_BY_FUTURE) {
                PENDING_BY_FUTURE.put(future, hand);
            }
        } catch (Throwable ignored) {
            // Never let a custom Future.hashCode/equals affect app execution.
        }
    }

    private static Hand takeFutureHand(Object key) {
        if (key == null) {
            return null;
        }
        try {
            synchronized (PENDING_BY_FUTURE) {
                return PENDING_BY_FUTURE.remove(key);
            }
        } catch (Throwable ignored) {
            return null;
        }
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
     * down), so that END does not wait for a task that never runs. Releases
     * only if the task has not already started running on a worker thread.
     *
     * @param handed what hand returned
     * @param task what hand was passed
     */
    public static void dropped(Object handed, Object task) {
        if (handed != task && handed instanceof Hand) {
            ((Hand) handed).releaseIfPending();
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
        Window window = current;
        window.sweepCancelledFutures();
        return window.running.get();
    }

    // await closes the current window and waits until none of its requests
    // runs, for at most timeoutNanos, and says whether none runs. A request
    // that comes in from now on (a readiness probe, a metrics scrape, the
    // next client) counts in a window of its own, so it does not draw the
    // wait out. One thread waits at a time (the worker, under its test lock).
    static boolean await(long timeoutNanos) {
        Window window = current;
        current = new Window();
        window.sweepCancelledFutures();
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
                window.sweepCancelledFutures();
                if (window.running.get() <= 0) {
                    return true;
                }
                long left = deadline - System.nanoTime();
                if (left <= 0 || Thread.currentThread().isInterrupted()) {
                    return false;
                }
                long waitNanos = window.hasPendingFutures()
                        ? Math.min(left, FUTURE_SWEEP_INTERVAL_NANOS)
                        : left;
                LockSupport.parkNanos(RequestGate.class, waitNanos);
            }
            return true;
        } finally {
            window.waiter = null;
        }
    }

    private static final class Window {

        private final AtomicInteger running = new AtomicInteger();
        private volatile Thread waiter;
        // Pending submitted tasks keyed by Hand (self-removed on release in
        // O(1), plus periodically swept on track() for cancelled futures so
        // windows without an END cannot grow unboundedly).
        private volatile ConcurrentHashMap<Hand, WeakReference<Future<?>>> futures;
        private final AtomicInteger insertsSinceSweep = new AtomicInteger();

        private void track(Hand hand, Future<?> future) {
            ConcurrentHashMap<Hand, WeakReference<Future<?>>> map = futures;
            if (map == null) {
                synchronized (this) {
                    map = futures;
                    if (map == null) {
                        map = new ConcurrentHashMap<>();
                        futures = map;
                    }
                }
            }
            map.put(hand, new WeakReference<Future<?>>(future));
            if (insertsSinceSweep.incrementAndGet() >= FUTURE_SWEEP_STRIDE) {
                insertsSinceSweep.set(0);
                sweepCancelledFutures();
            }
        }

        private void untrack(Hand hand) {
            ConcurrentHashMap<Hand, WeakReference<Future<?>>> map = futures;
            if (map != null) {
                map.remove(hand);
            }
        }

        private boolean hasPendingFutures() {
            ConcurrentHashMap<Hand, WeakReference<Future<?>>> map = futures;
            return map != null && !map.isEmpty();
        }

        private void sweepCancelledFutures() {
            ConcurrentHashMap<Hand, WeakReference<Future<?>>> map = futures;
            if (map == null || map.isEmpty()) {
                return;
            }
            for (Map.Entry<Hand, WeakReference<Future<?>>> entry : map.entrySet()) {
                Hand hand = entry.getKey();
                if (hand.isReleased()) {
                    map.remove(hand, entry.getValue());
                    continue;
                }
                Future<?> f = entry.getValue().get();
                if (isFutureTerminated(f)) {
                    hand.releaseIfPending();
                    map.remove(hand, entry.getValue());
                }
            }
        }
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

    private static final class HandRef extends WeakReference<Hand> {

        private final Set<HandRef> set;

        private HandRef(Hand referent, ReferenceQueue<Hand> queue, Set<HandRef> set) {
            super(referent, queue);
            this.set = set;
        }
    }

    // Hand is a task counted in a request's window: done once, when it
    // returns or throws, when the executor did not take it, or when it is
    // cancelled/timed out/removed/drained while still pending. Or, with no
    // window, a task a job's run handed off: a run of that job while it
    // runs (bounded by its carried deadline), and nothing to count done when
    // the executor did not take it.
    private abstract static class Hand {

        private static final int STATE_PENDING = 0;
        private static final int STATE_RUNNING = 1;
        private static final int STATE_DONE = 2;

        private static final AtomicIntegerFieldUpdater<Hand> STATE =
                AtomicIntegerFieldUpdater.newUpdater(Hand.class, "state");

        // The window it counts in, or null for a job's task.
        final Window window;
        // The job attribution whose run handed it off, when window is null.
        final JobGate.Attribution job;
        // Weak token in per-executor tracking set for O(1) self-removal on release.
        volatile HandRef executorRef;
        private volatile int state;

        Hand(Window window, JobGate.Attribution job) {
            this.window = window;
            this.job = job;
        }

        final boolean isReleased() {
            return state == STATE_DONE;
        }

        private void finishRelease() {
            window.untrack(this);
            HandRef ref = executorRef;
            if (ref != null) {
                executorRef = null;
                ref.set.remove(ref);
            }
            done(window);
        }

        // release marks this task done unconditionally (on exit).
        final void release() {
            if (window != null && STATE.getAndSet(this, STATE_DONE) != STATE_DONE) {
                finishRelease();
            }
        }

        // releaseIfPending marks this task done only if it has not started
        // running yet: cancellation/timeout/drop/shutdownNow of an
        // already-running task waits for its finally block's exit() so the
        // window stays open while app code is still executing.
        final void releaseIfPending() {
            if (window != null && STATE.compareAndSet(this, STATE_PENDING, STATE_DONE)) {
                finishRelease();
            }
        }

        // enter makes the running thread count in the task's window, so
        // that what the task hands off counts too, and returns the window
        // the thread counted in before; for a job's task, it starts a run
        // of the job (preserving the original deadline), and returns its token.
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

    private static class Handed extends Hand implements Runnable {

        final Runnable task;

        Handed(Window window, JobGate.Attribution job, Runnable task) {
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

        HandedFuture(Window window, JobGate.Attribution job, Runnable task) {
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

        HandedCall(Window window, JobGate.Attribution job, Callable<T> task) {
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

        HandedSupplier(Window window, JobGate.Attribution job, Supplier<T> task) {
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
