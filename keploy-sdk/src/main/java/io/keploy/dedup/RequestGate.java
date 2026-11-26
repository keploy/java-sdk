package io.keploy.dedup;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * RequestGate counts the requests the app's server is running, so that a
 * test's END can wait until the requests of that test are done before it
 * reads the test's hits.
 *
 * <p>Key guarantees:
 * <ul>
 *   <li><strong>No-op when Keploy is absent:</strong> runtime tracking is
 *       gated behind {@link #armed}, which becomes {@code true} only when a
 *       collector connects or the first {@code START} arrives. When unarmed,
 *       requests, executor submits, and job hooks perform zero wrapping, zero
 *       allocation, and zero tracking-map writes. If any request or job ran
 *       while unarmed, the first armed window is marked
 *       {@code contaminated: ["armed-mid-flight"]}.</li>
 *   <li><strong>Fail-closed executor wrapping:</strong> only executors
 *       positively verified by {@link OrderedPools} are wrapped. Unknown or
 *       custom executors receive the original task untouched and the current
 *       window is marked {@code untracked-executor:<class>}.</li>
 *   <li><strong>CompletableFuture dependent stages:</strong> for
 *       {@code supplyAsync}/{@code runAsync} and rewritten {@code *Async}
 *       instance stages, the window permit is released via a
 *       {@code whenComplete} registered on the returned future before returning
 *       to the caller (LIFO stack order ensures synchronous dependents execute
 *       before permit release), while still holding the permit if the future
 *       is completed early while the supplier/action is in flight.</li>
 *   <li><strong>Cut-tail contamination:</strong> lingering prior windows (from
 *       a cut END or an unclosed previous test) mark every subsequent window
 *       {@code tail:<prevTestId>} until they finish draining
 *       ({@code running == 0}).</li>
 *   <li><strong>Lock-free hot path:</strong> zero global {@code synchronized}
 *       monitors (safe for virtual threads on JDK 21-23), weak-value
 *       {@code PENDING_BY_FUTURE} removed eagerly on task start and release
 *       (no self-referencing {@code job.future = exec.submit(job)} leak), and
 *       {@link ReferenceQueue} permit recovery for abandoned tasks.</li>
 * </ul>
 *
 * <p>Public only for the server, Spring, and rewritten application classes
 * ServerEntries changes, which call it from their own packages; not an API.
 */
public final class RequestGate {

    /** Feature flag proving CompletableFuture dependent-stage tracking is active. */
    static final boolean CF_DEPENDENT_TRACKING_ENABLED = true;

    // Runtime arming gate: false until a collector connects or START arrives.
    private static volatile boolean armed = false;
    // Set once if any request/job executed while unarmed.
    private static volatile boolean unarmedEntered = false;
    private static volatile boolean pendingArmedMidFlight = false;
    private static final AtomicBoolean ARMED_MID_FLIGHT_CONSUMED = new AtomicBoolean(false);

    // The window of the test running, made anew at each START and when END
    // waits: END waits only for the requests that came in since the START,
    // never for one that runs from before or one that comes in after END.
    private static volatile Window current = new Window(null);

    // Prior windows that were cut at END or still have running > 0: every new
    // window opened while any lingering window remains active (or just after a
    // cut END) is marked contaminated ("tail:<prevTestId>") until drained.
    private static final Set<Window> LINGERING = ConcurrentHashMap.newKeySet();
    private static final int MAX_LINGERING = 64;

    // The window the thread's request, or the task a request handed off it
    // runs, counts in; null on any other thread.
    private static final ThreadLocal<Window> INSIDE = new ThreadLocal<>();

    // Lock-free weak identity map from submitted Future -> WeakReference<Hand>,
    // explicitly removed on BOTH Hand.enter() and Hand.finishRelease(), so
    // self-referencing patterns (job.future = exec.submit(job)) can never leak
    // and virtual-thread carriers are never pinned.
    private static final ConcurrentHashMap<WeakIdentityKey, WeakReference<Hand>> PENDING_BY_FUTURE =
            new ConcurrentHashMap<>();
    private static final ReferenceQueue<Object> DEAD_FUTURE_KEYS = new ReferenceQueue<>();

    // Lock-free weak identity map from Executor -> Set<HandRef> for O(1)
    // self-removal on release, shutdownNow() draining, and ReferenceQueue
    // permit recovery if a task is abandoned via purge()/getQueue().clear().
    private static final ConcurrentHashMap<WeakIdentityKey, Set<HandRef>> PENDING_BY_EXECUTOR =
            new ConcurrentHashMap<>();
    private static final ReferenceQueue<Object> DEAD_EXECUTOR_KEYS = new ReferenceQueue<>();
    private static final ReferenceQueue<Hand> DEAD_HANDS = new ReferenceQueue<>();

    // Bounded poll slice in await() only when the window has tracked submit()
    // futures that could be cancelled externally without running.
    private static final long FUTURE_SWEEP_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    // Sweep cancelled/done futures in Window.track() every N inserts so
    // windows that never receive an END cannot grow unboundedly on cancelled
    // futures.
    private static final int FUTURE_SWEEP_STRIDE = 64;

    // Reflective handles for Java 12+ CompletableFuture methods so keploy-sdk
    // compiles against --release 8 (Java8ApiTest) while rewriting JDK 12+ apps.
    private static final Method CF_EXCEPTIONALLY_ASYNC_1 =
            findCfMethod("exceptionallyAsync", Function.class);
    private static final Method CF_EXCEPTIONALLY_ASYNC_2 =
            findCfMethod("exceptionallyAsync", Function.class, Executor.class);
    private static final Method CF_EXCEPTIONALLY_COMPOSE_ASYNC_1 =
            findCfMethod("exceptionallyComposeAsync", Function.class);
    private static final Method CF_EXCEPTIONALLY_COMPOSE_ASYNC_2 =
            findCfMethod("exceptionallyComposeAsync", Function.class, Executor.class);

    private RequestGate() {
    }

    private static Method findCfMethod(String name, Class<?>... params) {
        try {
            return CompletableFuture.class.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean isArmed() {
        return armed;
    }

    static void noteUnarmedActivity() {
        if (!unarmedEntered) {
            unarmedEntered = true;
        }
    }

    static void arm() {
        if (!armed) {
            if (unarmedEntered) {
                pendingArmedMidFlight = true;
            }
            armed = true;
        }
    }

    static void disarm() {
        armed = false;
        unarmedEntered = false;
        pendingArmedMidFlight = false;
        ARMED_MID_FLIGHT_CONSUMED.set(false);
        LINGERING.clear();
        current = new Window(null);
    }

    static int pendingByFutureSize() {
        drainDeadFutureKeys();
        return PENDING_BY_FUTURE.size();
    }

    static int pendingByExecutorSize(Executor executor) {
        drainDeadHands();
        drainDeadExecutorKeys();
        if (executor == null) {
            return PENDING_BY_EXECUTOR.size();
        }
        Set<HandRef> set = PENDING_BY_EXECUTOR.get(new LookupKey(executor));
        return set == null ? 0 : set.size();
    }

    /**
     * Counts a request as running, in the window of the test running now.
     * No-op returning {@code null} when Keploy is unarmed.
     *
     * @return the token to pass to {@link #exit(Object)} when it is done
     */
    public static Object enter() {
        if (!armed) {
            noteUnarmedActivity();
            return null;
        }
        Window window = current;
        Window outer = INSIDE.get();
        // A request in a request (a server's dispatch on the same thread)
        // gives the thread back its outer window when it is done.
        Object token = outer == null ? window : new Nested(window, outer);
        INSIDE.set(window);
        // Counted last: whatever failed before it (out of memory) leaves
        // nothing to undo.
        window.enteredCount.incrementAndGet();
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
        if (token == null) {
            return;
        }
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
     * thread in a scheduled job's or message listener's run, returns a task
     * that counts as a run of that job while it runs. Fails CLOSED: any
     * executor not verified safe by {@link OrderedPools} receives the original
     * task untouched and marks the current request window contaminated
     * ({@code untracked-executor:<class>}).
     *
     * @param executor the executor the task is for, or null
     * @param task the task the executor is handed
     * @return what the executor runs instead
     */
    public static Runnable hand(Object executor, Runnable task) {
        return handInternal(executor, task, false);
    }

    private static Runnable handInternal(Object executor, Runnable task, boolean deferRelease) {
        if (!armed || task == null) {
            return task;
        }
        if (task instanceof Handed) {
            if (OrderedPools.orders(executor)) {
                Handed existing = (Handed) task;
                existing.releaseIfPending();
                if (existing.window != null && executor != null) {
                    existing.window.contaminate("untracked-executor:" + executor.getClass().getName());
                }
                return existing.task;
            }
            return task;
        }
        Window window = INSIDE.get();
        JobGate.Attribution job = window == null ? JobGate.runningAttribution() : null;
        if (window == null && job == null) {
            return task;
        }
        if (task instanceof Comparable) {
            if (window != null) {
                window.contaminate("untracked-executor:"
                        + (executor != null ? executor.getClass().getName() : "comparable"));
            }
            return task;
        }
        if (OrderedPools.orders(executor)) {
            if (window != null && executor != null) {
                window.contaminate("untracked-executor:" + executor.getClass().getName());
            }
            return task;
        }
        // A task that is a Future stays one, for the executor's cancel at
        // shutdown and its purge of cancelled tasks.
        Handed handed = task instanceof Future
                ? new HandedFuture(window, job, task, deferRelease && window != null)
                : new Handed(window, job, task, deferRelease && window != null);
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
        if (!armed || task == null) {
            return task;
        }
        if (task instanceof HandedCall) {
            if (OrderedPools.orders(executor)) {
                @SuppressWarnings("unchecked")
                HandedCall<T> existing = (HandedCall<T>) task;
                existing.releaseIfPending();
                if (existing.window != null && executor != null) {
                    existing.window.contaminate("untracked-executor:" + executor.getClass().getName());
                }
                return existing.task;
            }
            return task;
        }
        Window window = INSIDE.get();
        JobGate.Attribution job = window == null ? JobGate.runningAttribution() : null;
        if (window == null && job == null) {
            return task;
        }
        if (task instanceof Comparable) {
            if (window != null) {
                window.contaminate("untracked-executor:"
                        + (executor != null ? executor.getClass().getName() : "comparable"));
            }
            return task;
        }
        if (OrderedPools.orders(executor)) {
            if (window != null && executor != null) {
                window.contaminate("untracked-executor:" + executor.getClass().getName());
            }
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
     * on a request or job thread until it (and its synchronous dependents)
     * complete.
     *
     * @param executor the executor the supplier is for, or null
     * @param supplier the supplier handed off
     * @param <T> what the supplier returns
     * @return what runs instead
     */
    public static <T> Supplier<T> handSupplier(Object executor, Supplier<T> supplier) {
        return handSupplierInternal(executor, supplier, true);
    }

    private static <T> Supplier<T> handSupplierInternal(Object executor, Supplier<T> supplier, boolean deferRelease) {
        if (!armed || supplier == null) {
            return supplier;
        }
        if (supplier instanceof HandedSupplier) {
            if (OrderedPools.orders(executor)) {
                @SuppressWarnings("unchecked")
                HandedSupplier<T> existing = (HandedSupplier<T>) supplier;
                existing.releaseIfPending();
                if (existing.window != null && executor != null) {
                    existing.window.contaminate("untracked-executor:" + executor.getClass().getName());
                }
                return existing.task;
            }
            return supplier;
        }
        Window window = INSIDE.get();
        JobGate.Attribution job = window == null ? JobGate.runningAttribution() : null;
        if (window == null && job == null) {
            return supplier;
        }
        if (supplier instanceof Comparable) {
            if (window != null) {
                window.contaminate("untracked-executor:"
                        + (executor != null ? executor.getClass().getName() : "comparable"));
            }
            return supplier;
        }
        if (OrderedPools.orders(executor)) {
            if (window != null && executor != null) {
                window.contaminate("untracked-executor:" + executor.getClass().getName());
            }
            return supplier;
        }
        HandedSupplier<T> handed = new HandedSupplier<>(window, job, supplier, deferRelease && window != null);
        if (window != null) {
            window.running.incrementAndGet();
        }
        return handed;
    }

    /**
     * Call-site wrapper for {@link Executor#execute(Runnable)}: propagates the
     * current request window (or job run) across verified JDK executors.
     *
     * @param executor target executor
     * @param task runnable to execute
     */
    public static void execute(Executor executor, Runnable task) {
        if (!armed) {
            executor.execute(task);
            return;
        }
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
        if (!armed) {
            return executor.submit(task);
        }
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
        if (!armed) {
            return executor.submit(task, result);
        }
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
        if (!armed) {
            return executor.submit(task);
        }
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
     * Holds the window permit until synchronous dependent stages registered on
     * the returned future finish executing (LIFO {@code postComplete()} order).
     *
     * @param task runnable to execute asynchronously
     * @return the created CompletableFuture
     */
    public static CompletableFuture<Void> runAsync(Runnable task) {
        if (!armed) {
            return CompletableFuture.runAsync(task);
        }
        Runnable handed = handInternal(null, task, true);
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
        if (!armed) {
            return CompletableFuture.runAsync(task, executor);
        }
        Runnable handed = handInternal(executor, task, true);
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
        if (!armed) {
            return CompletableFuture.supplyAsync(supplier);
        }
        Supplier<T> handed = handSupplierInternal(null, supplier, true);
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
        if (!armed) {
            return CompletableFuture.supplyAsync(supplier, executor);
        }
        Supplier<T> handed = handSupplierInternal(executor, supplier, true);
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

    // --- CompletableFuture *Async instance method call-site wrappers ---

    private static Hand prepareCfStageHand(CompletableFuture<?> receiver, Executor explicitExecutor, boolean hasExecutor) {
        if (!armed) {
            return null;
        }
        Window window = INSIDE.get();
        JobGate.Attribution job = window == null ? JobGate.runningAttribution() : null;
        if (window == null && job == null) {
            return null;
        }
        if (hasExecutor) {
            if (explicitExecutor == null) {
                return null;
            }
            if (OrderedPools.orders(explicitExecutor)) {
                if (window != null) {
                    window.contaminate("untracked-executor:" + explicitExecutor.getClass().getName());
                }
                return null;
            }
        } else {
            // Default executor: safe only for exact CompletableFuture.class
            // (a custom subclass could override defaultExecutor()).
            if (receiver != null && receiver.getClass() != CompletableFuture.class) {
                if (window != null) {
                    window.contaminate("untracked-executor:" + receiver.getClass().getName());
                }
                return null;
            }
        }
        StageHand hand = new StageHand(window, job, window != null);
        if (window != null) {
            window.running.incrementAndGet();
        }
        if (hasExecutor && explicitExecutor != null) {
            registerForExecutor(explicitExecutor, hand, null);
        }
        return hand;
    }

    private static <T> CompletableFuture<T> finishCfStage(CompletableFuture<T> out, Hand hand) {
        if (hand != null) {
            attachCompletionRelease(out, hand, null);
        }
        return out;
    }

    private static <T, U> Function<T, U> wrapFunction(final Hand hand, final Function<? super T, ? extends U> fn) {
        if (hand == null || fn == null) {
            return null;
        }
        return new Function<T, U>() {
            @Override
            public U apply(T t) {
                Object outer = hand.enter();
                try {
                    return fn.apply(t);
                } finally {
                    hand.exit(outer);
                }
            }
        };
    }

    private static <T> Consumer<T> wrapConsumer(final Hand hand, final Consumer<? super T> action) {
        if (hand == null || action == null) {
            return null;
        }
        return new Consumer<T>() {
            @Override
            public void accept(T t) {
                Object outer = hand.enter();
                try {
                    action.accept(t);
                } finally {
                    hand.exit(outer);
                }
            }
        };
    }

    private static Runnable wrapStageRunnable(final Hand hand, final Runnable action) {
        if (hand == null || action == null) {
            return null;
        }
        return new Runnable() {
            @Override
            public void run() {
                Object outer = hand.enter();
                try {
                    action.run();
                } finally {
                    hand.exit(outer);
                }
            }
        };
    }

    private static <T, U, V> BiFunction<T, U, V> wrapBiFunction(final Hand hand,
            final BiFunction<? super T, ? super U, ? extends V> fn) {
        if (hand == null || fn == null) {
            return null;
        }
        return new BiFunction<T, U, V>() {
            @Override
            public V apply(T t, U u) {
                Object outer = hand.enter();
                try {
                    return fn.apply(t, u);
                } finally {
                    hand.exit(outer);
                }
            }
        };
    }

    private static <T, U> BiConsumer<T, U> wrapBiConsumer(final Hand hand,
            final BiConsumer<? super T, ? super U> action) {
        if (hand == null || action == null) {
            return null;
        }
        return new BiConsumer<T, U>() {
            @Override
            public void accept(T t, U u) {
                Object outer = hand.enter();
                try {
                    action.accept(t, u);
                } finally {
                    hand.exit(outer);
                }
            }
        };
    }

    public static <T, U> CompletableFuture<U> thenApplyAsync(CompletableFuture<T> cf,
            Function<? super T, ? extends U> fn) {
        Hand hand = fn == null ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.thenApplyAsync(fn);
        }
        CompletableFuture<U> out;
        try {
            out = cf.thenApplyAsync(wrapFunction(hand, fn));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T, U> CompletableFuture<U> thenApplyAsync(CompletableFuture<T> cf,
            Function<? super T, ? extends U> fn, Executor executor) {
        Hand hand = fn == null ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.thenApplyAsync(fn, executor);
        }
        CompletableFuture<U> out;
        try {
            out = cf.thenApplyAsync(wrapFunction(hand, fn), executor);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T> CompletableFuture<Void> thenAcceptAsync(CompletableFuture<T> cf,
            Consumer<? super T> action) {
        Hand hand = action == null ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.thenAcceptAsync(action);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.thenAcceptAsync(wrapConsumer(hand, action));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T> CompletableFuture<Void> thenAcceptAsync(CompletableFuture<T> cf,
            Consumer<? super T> action, Executor executor) {
        Hand hand = action == null ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.thenAcceptAsync(action, executor);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.thenAcceptAsync(wrapConsumer(hand, action), executor);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T> CompletableFuture<Void> thenRunAsync(CompletableFuture<T> cf, Runnable action) {
        Hand hand = action == null ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.thenRunAsync(action);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.thenRunAsync(wrapStageRunnable(hand, action));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T> CompletableFuture<Void> thenRunAsync(CompletableFuture<T> cf, Runnable action,
            Executor executor) {
        Hand hand = action == null ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.thenRunAsync(action, executor);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.thenRunAsync(wrapStageRunnable(hand, action), executor);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T, U> CompletableFuture<U> thenComposeAsync(CompletableFuture<T> cf,
            Function<? super T, ? extends CompletionStage<U>> fn) {
        Hand hand = fn == null ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.thenComposeAsync(fn);
        }
        CompletableFuture<U> out;
        try {
            out = cf.thenComposeAsync(wrapFunction(hand, fn));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T, U> CompletableFuture<U> thenComposeAsync(CompletableFuture<T> cf,
            Function<? super T, ? extends CompletionStage<U>> fn, Executor executor) {
        Hand hand = fn == null ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.thenComposeAsync(fn, executor);
        }
        CompletableFuture<U> out;
        try {
            out = cf.thenComposeAsync(wrapFunction(hand, fn), executor);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T, U, V> CompletableFuture<V> thenCombineAsync(CompletableFuture<T> cf,
            CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn) {
        Hand hand = (other == null || fn == null) ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.thenCombineAsync(other, fn);
        }
        CompletableFuture<V> out;
        try {
            out = cf.thenCombineAsync(other, wrapBiFunction(hand, fn));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T, U, V> CompletableFuture<V> thenCombineAsync(CompletableFuture<T> cf,
            CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn,
            Executor executor) {
        Hand hand = (other == null || fn == null) ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.thenCombineAsync(other, fn, executor);
        }
        CompletableFuture<V> out;
        try {
            out = cf.thenCombineAsync(other, wrapBiFunction(hand, fn), executor);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T, U> CompletableFuture<Void> thenAcceptBothAsync(CompletableFuture<T> cf,
            CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) {
        Hand hand = (other == null || action == null) ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.thenAcceptBothAsync(other, action);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.thenAcceptBothAsync(other, wrapBiConsumer(hand, action));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T, U> CompletableFuture<Void> thenAcceptBothAsync(CompletableFuture<T> cf,
            CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action, Executor executor) {
        Hand hand = (other == null || action == null) ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.thenAcceptBothAsync(other, action, executor);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.thenAcceptBothAsync(other, wrapBiConsumer(hand, action), executor);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T> CompletableFuture<Void> runAfterBothAsync(CompletableFuture<T> cf,
            CompletionStage<?> other, Runnable action) {
        Hand hand = (other == null || action == null) ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.runAfterBothAsync(other, action);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.runAfterBothAsync(other, wrapStageRunnable(hand, action));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T> CompletableFuture<Void> runAfterBothAsync(CompletableFuture<T> cf,
            CompletionStage<?> other, Runnable action, Executor executor) {
        Hand hand = (other == null || action == null) ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.runAfterBothAsync(other, action, executor);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.runAfterBothAsync(other, wrapStageRunnable(hand, action), executor);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T, U> CompletableFuture<U> applyToEitherAsync(CompletableFuture<T> cf,
            CompletionStage<? extends T> other, Function<? super T, U> fn) {
        Hand hand = (other == null || fn == null) ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.applyToEitherAsync(other, fn);
        }
        CompletableFuture<U> out;
        try {
            out = cf.applyToEitherAsync(other, wrapFunction(hand, fn));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T, U> CompletableFuture<U> applyToEitherAsync(CompletableFuture<T> cf,
            CompletionStage<? extends T> other, Function<? super T, U> fn, Executor executor) {
        Hand hand = (other == null || fn == null) ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.applyToEitherAsync(other, fn, executor);
        }
        CompletableFuture<U> out;
        try {
            out = cf.applyToEitherAsync(other, wrapFunction(hand, fn), executor);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T> CompletableFuture<Void> acceptEitherAsync(CompletableFuture<T> cf,
            CompletionStage<? extends T> other, Consumer<? super T> action) {
        Hand hand = (other == null || action == null) ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.acceptEitherAsync(other, action);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.acceptEitherAsync(other, wrapConsumer(hand, action));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T> CompletableFuture<Void> acceptEitherAsync(CompletableFuture<T> cf,
            CompletionStage<? extends T> other, Consumer<? super T> action, Executor executor) {
        Hand hand = (other == null || action == null) ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.acceptEitherAsync(other, action, executor);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.acceptEitherAsync(other, wrapConsumer(hand, action), executor);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T> CompletableFuture<Void> runAfterEitherAsync(CompletableFuture<T> cf,
            CompletionStage<?> other, Runnable action) {
        Hand hand = (other == null || action == null) ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.runAfterEitherAsync(other, action);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.runAfterEitherAsync(other, wrapStageRunnable(hand, action));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T> CompletableFuture<Void> runAfterEitherAsync(CompletableFuture<T> cf,
            CompletionStage<?> other, Runnable action, Executor executor) {
        Hand hand = (other == null || action == null) ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.runAfterEitherAsync(other, action, executor);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.runAfterEitherAsync(other, wrapStageRunnable(hand, action), executor);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T, U> CompletableFuture<U> handleAsync(CompletableFuture<T> cf,
            BiFunction<? super T, Throwable, ? extends U> fn) {
        Hand hand = fn == null ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.handleAsync(fn);
        }
        CompletableFuture<U> out;
        try {
            out = cf.handleAsync(wrapBiFunction(hand, fn));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T, U> CompletableFuture<U> handleAsync(CompletableFuture<T> cf,
            BiFunction<? super T, Throwable, ? extends U> fn, Executor executor) {
        Hand hand = fn == null ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.handleAsync(fn, executor);
        }
        CompletableFuture<U> out;
        try {
            out = cf.handleAsync(wrapBiFunction(hand, fn), executor);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T> CompletableFuture<T> whenCompleteAsync(CompletableFuture<T> cf,
            BiConsumer<? super T, ? super Throwable> action) {
        Hand hand = action == null ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.whenCompleteAsync(action);
        }
        CompletableFuture<T> out;
        try {
            out = cf.whenCompleteAsync(wrapBiConsumer(hand, action));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    public static <T> CompletableFuture<T> whenCompleteAsync(CompletableFuture<T> cf,
            BiConsumer<? super T, ? super Throwable> action, Executor executor) {
        Hand hand = action == null ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.whenCompleteAsync(action, executor);
        }
        CompletableFuture<T> out;
        try {
            out = cf.whenCompleteAsync(wrapBiConsumer(hand, action), executor);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    @SuppressWarnings("unchecked")
    public static <T> CompletableFuture<T> exceptionallyAsync(CompletableFuture<T> cf,
            Function<Throwable, ? extends T> fn) {
        Hand hand = fn == null ? null : prepareCfStageHand(cf, null, false);
        Function<Throwable, ? extends T> target = hand == null ? fn : wrapFunction(hand, fn);
        CompletableFuture<T> out;
        try {
            out = (CompletableFuture<T>) invokeCfReflect(CF_EXCEPTIONALLY_ASYNC_1, cf, target);
        } catch (Throwable t) {
            if (hand != null) {
                hand.releaseIfPending();
            }
            throw sneakyThrow(t);
        }
        return finishCfStage(out, hand);
    }

    @SuppressWarnings("unchecked")
    public static <T> CompletableFuture<T> exceptionallyAsync(CompletableFuture<T> cf,
            Function<Throwable, ? extends T> fn, Executor executor) {
        Hand hand = fn == null ? null : prepareCfStageHand(cf, executor, true);
        Function<Throwable, ? extends T> target = hand == null ? fn : wrapFunction(hand, fn);
        CompletableFuture<T> out;
        try {
            out = (CompletableFuture<T>) invokeCfReflect(CF_EXCEPTIONALLY_ASYNC_2, cf, target, executor);
        } catch (Throwable t) {
            if (hand != null) {
                hand.releaseIfPending();
            }
            throw sneakyThrow(t);
        }
        return finishCfStage(out, hand);
    }

    @SuppressWarnings("unchecked")
    public static <T> CompletableFuture<T> exceptionallyComposeAsync(CompletableFuture<T> cf,
            Function<Throwable, ? extends CompletionStage<T>> fn) {
        Hand hand = fn == null ? null : prepareCfStageHand(cf, null, false);
        Function<Throwable, ? extends CompletionStage<T>> target = hand == null ? fn : wrapFunction(hand, fn);
        CompletableFuture<T> out;
        try {
            out = (CompletableFuture<T>) invokeCfReflect(CF_EXCEPTIONALLY_COMPOSE_ASYNC_1, cf, target);
        } catch (Throwable t) {
            if (hand != null) {
                hand.releaseIfPending();
            }
            throw sneakyThrow(t);
        }
        return finishCfStage(out, hand);
    }

    @SuppressWarnings("unchecked")
    public static <T> CompletableFuture<T> exceptionallyComposeAsync(CompletableFuture<T> cf,
            Function<Throwable, ? extends CompletionStage<T>> fn, Executor executor) {
        Hand hand = fn == null ? null : prepareCfStageHand(cf, executor, true);
        Function<Throwable, ? extends CompletionStage<T>> target = hand == null ? fn : wrapFunction(hand, fn);
        CompletableFuture<T> out;
        try {
            out = (CompletableFuture<T>) invokeCfReflect(CF_EXCEPTIONALLY_COMPOSE_ASYNC_2, cf, target, executor);
        } catch (Throwable t) {
            if (hand != null) {
                hand.releaseIfPending();
            }
            throw sneakyThrow(t);
        }
        return finishCfStage(out, hand);
    }

    private static Object invokeCfReflect(Method method, Object target, Object... args) throws Throwable {
        if (method == null) {
            throw new NoSuchMethodError("CompletableFuture async method unavailable on this JDK");
        }
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    // --- Untracked async call-site pass-through contamination helpers ---

    private static void contaminateIfInRequest(String reason) {
        if (!armed) {
            return;
        }
        Window window = INSIDE.get();
        if (window != null) {
            window.contaminate(reason);
        }
    }

    public static ScheduledFuture<?> schedule(ScheduledExecutorService executor, Runnable command,
            long delay, TimeUnit unit) {
        contaminateIfInRequest("untracked-async:schedule");
        return executor.schedule(command, delay, unit);
    }

    public static <V> ScheduledFuture<V> schedule(ScheduledExecutorService executor, Callable<V> callable,
            long delay, TimeUnit unit) {
        contaminateIfInRequest("untracked-async:schedule");
        return executor.schedule(callable, delay, unit);
    }

    public static ScheduledFuture<?> scheduleAtFixedRate(ScheduledExecutorService executor, Runnable command,
            long initialDelay, long period, TimeUnit unit) {
        contaminateIfInRequest("untracked-async:schedule");
        return executor.scheduleAtFixedRate(command, initialDelay, period, unit);
    }

    public static ScheduledFuture<?> scheduleWithFixedDelay(ScheduledExecutorService executor, Runnable command,
            long initialDelay, long delay, TimeUnit unit) {
        contaminateIfInRequest("untracked-async:schedule");
        return executor.scheduleWithFixedDelay(command, initialDelay, delay, unit);
    }

    public static <T> T invokeAny(ExecutorService executor, Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        contaminateIfInRequest("untracked-async:invokeAny");
        return executor.invokeAny(tasks);
    }

    public static <T> T invokeAny(ExecutorService executor, Collection<? extends Callable<T>> tasks,
            long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        contaminateIfInRequest("untracked-async:invokeAny");
        return executor.invokeAny(tasks, timeout, unit);
    }

    public static void startThread(Thread thread) {
        contaminateIfInRequest("untracked-async:thread-start");
        thread.start();
    }

    /**
     * Call-site wrapper for {@link ThreadPoolExecutor#remove(Runnable)}: finds
     * and removes either the original task (releasing any tracked submitted
     * Future permit, e.g. {@code tpe.remove((Runnable) future)}) or the
     * wrapped {@link Handed} entry if {@code task} was wrapped, releasing its
     * pending window permit so END does not wait for it. Skips the second
     * queue scan when this executor has no tracked pending tasks.
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
        if (task == null || !hasPendingForExecutor(executor)) {
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
     * pending window permits for all queued tasks of {@code executor} and
     * unwraps any {@link Handed} items in the returned list so callers casting
     * them never see {@link Handed}.
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
            if (hr.set.remove(hr) && !hr.released) {
                hr.released = true;
                done(hr.window);
            }
        }
    }

    private static void drainDeadExecutorKeys() {
        for (Reference<?> ref; (ref = DEAD_EXECUTOR_KEYS.poll()) != null; ) {
            PENDING_BY_EXECUTOR.remove(ref);
        }
    }

    private static void drainDeadFutureKeys() {
        for (Reference<?> ref; (ref = DEAD_FUTURE_KEYS.poll()) != null; ) {
            PENDING_BY_FUTURE.remove(ref);
        }
    }

    private static boolean hasPendingForExecutor(Executor executor) {
        if (executor == null || PENDING_BY_EXECUTOR.isEmpty()) {
            return false;
        }
        Set<HandRef> set = PENDING_BY_EXECUTOR.get(new LookupKey(executor));
        return set != null && !set.isEmpty();
    }

    private static void registerForExecutor(Executor executor, Object handed, Object task) {
        drainDeadHands();
        drainDeadExecutorKeys();
        if (executor == null || handed == task || !(handed instanceof Hand)) {
            return;
        }
        Hand hand = (Hand) handed;
        if (hand.window == null || hand.isReleased()) {
            return;
        }
        try {
            LookupKey probe = new LookupKey(executor);
            Set<HandRef> set = PENDING_BY_EXECUTOR.get(probe);
            if (set == null) {
                Set<HandRef> fresh = ConcurrentHashMap.newKeySet();
                Set<HandRef> existing = PENDING_BY_EXECUTOR.putIfAbsent(
                        new WeakIdentityKey(executor, DEAD_EXECUTOR_KEYS), fresh);
                set = existing != null ? existing : fresh;
            }
            HandRef ref = new HandRef(hand, DEAD_HANDS, set, hand.window);
            set.add(ref);
            hand.executorRef = ref;
            if (hand.isReleased()) {
                ref.released = true;
                hand.executorRef = null;
                set.remove(ref);
            }
        } catch (Throwable ignored) {
            // Never affect app execution.
        }
    }

    private static void releaseAllPendingForExecutor(Executor executor) {
        drainDeadHands();
        drainDeadExecutorKeys();
        if (executor == null) {
            return;
        }
        Set<HandRef> set = PENDING_BY_EXECUTOR.remove(new LookupKey(executor));
        if (set == null) {
            return;
        }
        for (HandRef ref : set) {
            Hand hand = ref.get();
            if (hand != null) {
                hand.releaseIfPending();
            } else if (set.remove(ref) && !ref.released) {
                ref.released = true;
                done(ref.window);
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
        drainDeadFutureKeys();
        if (future == null || handed == task || !(handed instanceof Hand)) {
            return;
        }
        Hand hand = (Hand) handed;
        if (hand.window == null || hand.isStartedOrReleased()) {
            return;
        }
        if (isFutureTerminated(future)) {
            hand.releaseIfPending();
            return;
        }
        hand.window.track(hand, future);
        if (hand.isStartedOrReleased()) {
            hand.window.untrack(hand);
            return;
        }
        WeakIdentityKey key = new WeakIdentityKey(future, DEAD_FUTURE_KEYS);
        PENDING_BY_FUTURE.put(key, new WeakReference<Hand>(hand));
        hand.futureKey = key;
        if (hand.isStartedOrReleased()) {
            hand.clearFutureKey();
        }
    }

    private static Hand takeFutureHand(Object key) {
        drainDeadFutureKeys();
        if (key == null || PENDING_BY_FUTURE.isEmpty()) {
            return null;
        }
        WeakReference<Hand> ref = PENDING_BY_FUTURE.remove(new LookupKey(key));
        return ref == null ? null : ref.get();
    }

    private static void attachCompletionRelease(CompletableFuture<?> cf, Object handed, Object task) {
        if (cf != null && handed != task && handed instanceof Hand) {
            final Hand hand = (Hand) handed;
            try {
                cf.whenComplete((result, error) -> hand.onFutureCompleted());
            } catch (Throwable t) {
                hand.onFutureCompleted();
            }
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
        open(null, null);
    }

    // open(testId, prevActiveTestId) opens a fresh test window, records
    // armed-mid-flight if unarmed traffic occurred prior to arming, and marks
    // the new window contaminated ("tail:<prevTestId>") if the previous test
    // was cut, unclosed, or any prior window still has running > 0.
    static void open(String testId, String prevActiveTestId) {
        arm();
        drainDeadHands();
        Window prev = current;
        Window next = new Window(testId);
        if ((pendingArmedMidFlight || unarmedEntered)
                && ARMED_MID_FLIGHT_CONSUMED.compareAndSet(false, true)) {
            pendingArmedMidFlight = false;
            next.contaminate("armed-mid-flight");
        }
        if (prev != null) {
            prev.sweepCancelledFutures();
            if (prev.running.get() > 0) {
                if (prev.testId == null && prevActiveTestId != null && !prevActiveTestId.isEmpty()) {
                    prev.fallbackId = prevActiveTestId;
                }
                retainLingering(prev);
            }
        }
        if (prevActiveTestId != null && !prevActiveTestId.isEmpty()) {
            next.contaminate("tail:" + prevActiveTestId);
        }
        for (Window lingering : LINGERING) {
            lingering.sweepCancelledFutures();
            boolean stillRunning = lingering.running.get() > 0;
            boolean reportCut = lingering.wasCut && !lingering.cutReportedOnce;
            if (stillRunning || reportCut) {
                next.contaminate("tail:" + lingering.label());
                lingering.cutReportedOnce = true;
            }
            if (!stillRunning) {
                LINGERING.remove(lingering);
            }
        }
        current = next;
    }

    private static void retainLingering(Window window) {
        if (LINGERING.size() >= MAX_LINGERING) {
            for (Window existing : LINGERING) {
                existing.sweepCancelledFutures();
                if (existing.running.get() <= 0 && (!existing.wasCut || existing.cutReportedOnce)) {
                    LINGERING.remove(existing);
                }
            }
        }
        LINGERING.add(window);
    }

    // running is how many requests of the current window are running.
    static int running() {
        drainDeadHands();
        Window window = current;
        window.sweepCancelledFutures();
        return window.running.get();
    }

    // await closes the current window and waits until none of its requests
    // runs, for at most timeoutNanos, and says whether none runs.
    static boolean await(long timeoutNanos) {
        return awaitWindow(timeoutNanos, true).drained;
    }

    // awaitWindow closes the current window, optionally waits up to
    // timeoutNanos for its requests/tasks to finish, retains the window in
    // LINGERING if cut or still running, and returns its outcome (drained,
    // waited, enteredCount, and accumulated contaminations).
    static WindowOutcome awaitWindow(long timeoutNanos, boolean wait) {
        drainDeadHands();
        Window window = current;
        current = new Window(null);
        window.sweepCancelledFutures();
        int initialRunning = window.running.get();
        if (!wait) {
            if (initialRunning > 0) {
                retainLingering(window);
            }
            return new WindowOutcome(true, false, window.enteredCount.get(), window.contaminations());
        }
        if (initialRunning <= 0) {
            return new WindowOutcome(true, false, window.enteredCount.get(), window.contaminations());
        }
        boolean drained = false;
        // Set before the count is read again: an exit that takes the count
        // to 0 after that read finds the waiter and wakes it, and one that
        // took it to 0 before is seen by the read.
        window.waiter = Thread.currentThread();
        try {
            long deadline = System.nanoTime() + timeoutNanos;
            while (window.running.get() > 0) {
                drainDeadHands();
                window.sweepCancelledFutures();
                if (window.running.get() <= 0) {
                    drained = true;
                    break;
                }
                long left = deadline - System.nanoTime();
                if (left <= 0 || Thread.currentThread().isInterrupted()) {
                    drained = false;
                    break;
                }
                long waitNanos = window.hasPendingFutures()
                        ? Math.min(left, FUTURE_SWEEP_INTERVAL_NANOS)
                        : left;
                LockSupport.parkNanos(RequestGate.class, waitNanos);
            }
            if (window.running.get() <= 0) {
                drained = true;
            }
        } finally {
            window.waiter = null;
        }
        if (!drained) {
            window.wasCut = true;
            retainLingering(window);
        }
        return new WindowOutcome(drained, true, window.enteredCount.get(), window.contaminations());
    }

    static final class WindowOutcome {
        final boolean drained;
        final boolean waited;
        final int enteredCount;
        final List<String> contaminations;

        WindowOutcome(boolean drained, boolean waited, int enteredCount, List<String> contaminations) {
            this.drained = drained;
            this.waited = waited;
            this.enteredCount = enteredCount;
            this.contaminations = contaminations;
        }
    }

    private static final class Window {

        private static final AtomicReferenceFieldUpdater<Window, ConcurrentHashMap> FUTURES_UPDATER =
                AtomicReferenceFieldUpdater.newUpdater(Window.class, ConcurrentHashMap.class, "futures");

        private final String testId;
        private volatile String fallbackId;
        private final AtomicInteger running = new AtomicInteger();
        private final AtomicInteger enteredCount = new AtomicInteger();
        private volatile Thread waiter;
        private volatile boolean wasCut;
        private volatile boolean cutReportedOnce;
        // Pending submitted tasks keyed by Hand (self-removed on release in
        // O(1), plus periodically swept on track() for cancelled futures so
        // windows without an END cannot grow unboundedly).
        @SuppressWarnings("rawtypes")
        private volatile ConcurrentHashMap futures;
        private final AtomicInteger insertsSinceSweep = new AtomicInteger();
        // Lock-free contamination reasons collected during this window.
        private final Set<String> contamSet = ConcurrentHashMap.newKeySet();
        private final ConcurrentLinkedQueue<String> contamOrder = new ConcurrentLinkedQueue<>();
        private final AtomicInteger contamCount = new AtomicInteger();

        private Window(String testId) {
            this.testId = testId;
        }

        private String label() {
            if (testId != null && !testId.isEmpty()) {
                return testId;
            }
            String fb = fallbackId;
            return fb != null && !fb.isEmpty() ? fb : "prev";
        }

        private void contaminate(String reason) {
            if (reason == null || reason.isEmpty()) {
                return;
            }
            if (contamCount.get() < JobGate.KEPT && contamSet.add(reason)) {
                if (contamCount.incrementAndGet() <= JobGate.KEPT) {
                    contamOrder.add(reason);
                } else {
                    contamSet.remove(reason);
                    contamCount.decrementAndGet();
                }
            }
        }

        private List<String> contaminations() {
            if (contamOrder.isEmpty()) {
                return Collections.emptyList();
            }
            List<String> out = new ArrayList<>();
            for (String reason : contamOrder) {
                out.add(reason);
            }
            return out;
        }

        @SuppressWarnings("unchecked")
        private ConcurrentHashMap<Hand, WeakReference<Future<?>>> futuresMap(boolean create) {
            ConcurrentHashMap<Hand, WeakReference<Future<?>>> map =
                    (ConcurrentHashMap<Hand, WeakReference<Future<?>>>) futures;
            if (map != null || !create) {
                return map;
            }
            ConcurrentHashMap<Hand, WeakReference<Future<?>>> fresh = new ConcurrentHashMap<>();
            if (FUTURES_UPDATER.compareAndSet(this, null, fresh)) {
                return fresh;
            }
            return (ConcurrentHashMap<Hand, WeakReference<Future<?>>>) futures;
        }

        private void track(Hand hand, Future<?> future) {
            ConcurrentHashMap<Hand, WeakReference<Future<?>>> map = futuresMap(true);
            map.put(hand, new WeakReference<Future<?>>(future));
            if (insertsSinceSweep.incrementAndGet() >= FUTURE_SWEEP_STRIDE) {
                insertsSinceSweep.set(0);
                sweepCancelledFutures();
            }
        }

        private void untrack(Hand hand) {
            ConcurrentHashMap<Hand, WeakReference<Future<?>>> map = futuresMap(false);
            if (map != null) {
                map.remove(hand);
            }
        }

        private boolean hasPendingFutures() {
            ConcurrentHashMap<Hand, WeakReference<Future<?>>> map = futuresMap(false);
            return map != null && !map.isEmpty();
        }

        private void sweepCancelledFutures() {
            ConcurrentHashMap<Hand, WeakReference<Future<?>>> map = futuresMap(false);
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

    private static final class WeakIdentityKey extends WeakReference<Object> {

        private final int hash;

        private WeakIdentityKey(Object referent, ReferenceQueue<Object> queue) {
            super(referent, queue);
            this.hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            Object mine = get();
            if (mine == null) {
                return false;
            }
            if (obj instanceof WeakIdentityKey) {
                return mine == ((WeakIdentityKey) obj).get();
            }
            if (obj instanceof LookupKey) {
                return mine == ((LookupKey) obj).target;
            }
            return false;
        }
    }

    private static final class LookupKey {

        private final Object target;
        private final int hash;

        private LookupKey(Object target) {
            this.target = target;
            this.hash = System.identityHashCode(target);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof WeakIdentityKey) {
                return target == ((WeakIdentityKey) obj).get();
            }
            if (obj instanceof LookupKey) {
                return target == ((LookupKey) obj).target;
            }
            return false;
        }
    }

    private static final class HandRef extends WeakReference<Hand> {

        private final Set<HandRef> set;
        private final Window window;
        private volatile boolean released;

        private HandRef(Hand referent, ReferenceQueue<Hand> queue, Set<HandRef> set, Window window) {
            super(referent, queue);
            this.set = set;
            this.window = window;
        }
    }

    // Hand is a task counted in a request's window (or a job's run).
    // Bitmask state supports both immediate release on exit (execute/submit)
    // and deferred release on CompletableFuture postComplete (supplyAsync/
    // runAsync/*Async) so synchronous dependent stages execute before permit
    // release while early cf.complete() during execution still waits for exit().
    private abstract static class Hand {

        private static final int FLAG_STARTED = 1;
        private static final int FLAG_EXITED = 2;
        private static final int FLAG_CF_DONE = 4;
        private static final int FLAG_RELEASED = 8;

        private static final AtomicIntegerFieldUpdater<Hand> STATE =
                AtomicIntegerFieldUpdater.newUpdater(Hand.class, "state");

        // The window it counts in, or null for a job's task.
        final Window window;
        // The job attribution whose run handed it off, when window is null.
        final JobGate.Attribution job;
        // True for CompletableFuture stages whose permit is released via
        // whenComplete after LIFO synchronous dependents run.
        final boolean deferRelease;
        // Weak token in per-executor tracking set for O(1) self-removal.
        volatile HandRef executorRef;
        // Weak key in PENDING_BY_FUTURE for O(1) eager removal on start/release.
        volatile WeakIdentityKey futureKey;
        private volatile int state;

        Hand(Window window, JobGate.Attribution job, boolean deferRelease) {
            this.window = window;
            this.job = job;
            this.deferRelease = deferRelease;
        }

        final boolean isReleased() {
            return (state & FLAG_RELEASED) != 0;
        }

        final boolean isStartedOrReleased() {
            return (state & (FLAG_STARTED | FLAG_RELEASED)) != 0;
        }

        final void clearFutureKey() {
            WeakIdentityKey fk = futureKey;
            if (fk != null) {
                futureKey = null;
                PENDING_BY_FUTURE.remove(fk);
            }
        }

        private void finishRelease() {
            clearFutureKey();
            window.untrack(this);
            HandRef ref = executorRef;
            if (ref != null) {
                ref.released = true;
                executorRef = null;
                ref.set.remove(ref);
            }
            done(window);
        }

        // release marks this task done unconditionally (non-deferred exit).
        final void release() {
            if (window == null) {
                return;
            }
            for (;;) {
                int s = state;
                if ((s & FLAG_RELEASED) != 0) {
                    return;
                }
                if (STATE.compareAndSet(this, s, s | FLAG_EXITED | FLAG_RELEASED)) {
                    finishRelease();
                    return;
                }
            }
        }

        // onFutureCompleted is invoked by the CompletableFuture's whenComplete
        // callback (registered immediately before returning the CF, so LIFO
        // postComplete pops caller-registered synchronous dependents first).
        final void onFutureCompleted() {
            if (window == null) {
                return;
            }
            if (!deferRelease) {
                releaseIfPending();
                return;
            }
            for (;;) {
                int s = state;
                if ((s & FLAG_RELEASED) != 0) {
                    return;
                }
                int next = s | FLAG_CF_DONE;
                boolean doRelease = (s & FLAG_STARTED) == 0 || (s & FLAG_EXITED) != 0;
                if (doRelease) {
                    next |= FLAG_RELEASED;
                }
                if (STATE.compareAndSet(this, s, next)) {
                    if (doRelease) {
                        finishRelease();
                    }
                    return;
                }
            }
        }

        // releaseIfPending marks this task done only if it has not started
        // running yet: cancellation/timeout/drop/shutdownNow of an
        // already-running task waits for its finally block's exit() so the
        // window stays open while app code is still executing.
        final void releaseIfPending() {
            if (window == null) {
                return;
            }
            for (;;) {
                int s = state;
                if ((s & (FLAG_STARTED | FLAG_RELEASED)) != 0) {
                    return;
                }
                if (STATE.compareAndSet(this, s, s | FLAG_RELEASED)) {
                    finishRelease();
                    return;
                }
            }
        }

        final Object enter() {
            if (window == null) {
                return JobGate.enterAs(job);
            }
            for (;;) {
                int s = state;
                if ((s & FLAG_STARTED) != 0) {
                    break;
                }
                if (STATE.compareAndSet(this, s, s | FLAG_STARTED)) {
                    break;
                }
            }
            clearFutureKey();
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
            if (!deferRelease) {
                release();
                return;
            }
            for (;;) {
                int s = state;
                if ((s & FLAG_RELEASED) != 0) {
                    return;
                }
                int next = s | FLAG_EXITED;
                boolean doRelease = (next & FLAG_CF_DONE) != 0;
                if (doRelease) {
                    next |= FLAG_RELEASED;
                }
                if (STATE.compareAndSet(this, s, next)) {
                    if (doRelease) {
                        finishRelease();
                    }
                    return;
                }
            }
        }
    }

    private static final class StageHand extends Hand {
        StageHand(Window window, JobGate.Attribution job, boolean deferRelease) {
            super(window, job, deferRelease);
        }
    }

    private static class Handed extends Hand implements Runnable {

        final Runnable task;

        Handed(Window window, JobGate.Attribution job, Runnable task, boolean deferRelease) {
            super(window, job, deferRelease);
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

        HandedFuture(Window window, JobGate.Attribution job, Runnable task, boolean deferRelease) {
            super(window, job, task, deferRelease);
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
            super(window, job, false);
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

        HandedSupplier(Window window, JobGate.Attribution job, Supplier<T> task, boolean deferRelease) {
            super(window, job, deferRelease);
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
