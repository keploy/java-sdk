package io.keploy.dedup;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionService;
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
import java.util.concurrent.atomic.LongAdder;
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

    // Runtime arming gate: false until a collector connects or START arrives.
    private static volatile boolean armed = false;
    // Set once if any request/job executed while unarmed.
    private static volatile boolean unarmedEntered = false;
    private static volatile boolean pendingArmedMidFlight = false;
    private static final AtomicBoolean ARMED_MID_FLIGHT_CONSUMED = new AtomicBoolean(false);
    // Zero-allocation sentinel returned by RequestGate.enter() and
    // JobGate.enter() while unarmed so long-running work started before arming
    // keeps marking every subsequent window "armed-mid-flight" until exit().
    private static final Object UNARMED_TOKEN = new Object();
    private static final LongAdder UNARMED_IN_FLIGHT = new LongAdder();
    // Timestamp of the last unarmed async hand-off (zero-allocation, zero
    // wrapping) so windows opened shortly after arming still carry
    // armed-mid-flight even if the initiating unarmed request already exited.
    private static volatile long lastUnarmedHandOffNanos;

    // Duration after an untracked async hand-off during which subsequent
    // windows are conservatively marked contaminated ("tail:<prevTestId>").
    static volatile long untrackedTailWindowNanos =
            TimeUnit.MILLISECONDS.toNanos(KeployDedupAgent.MAX_END_WAIT_MILLIS);
    // Maximum duration a stuck lingering window (e.g., an abandoned server-
    // lifetime future that never completes) keeps contaminating later windows.
    static volatile long maxLingeringNanos = TimeUnit.SECONDS.toNanos(30);
    // Untracked async tail recorded outside a request window (e.g., from a
    // scheduled job or message listener handing work to an unknown executor).
    private static volatile boolean externalUntrackedTailPending;
    private static volatile long externalUntrackedTailExpiresAtNanos;
    private static volatile String externalUntrackedTailReason;
    // Weak handles to live untracked Threads/ScheduledFutures started from
    // non-request (job) threads so external tails remain active while alive.
    private static final ConcurrentLinkedQueue<UntrackedHandle> EXTERNAL_UNTRACKED_HANDLES =
            new ConcurrentLinkedQueue<>();
    private static final int MAX_UNTRACKED_HANDLES = 64;
    private static final long UNARMED_HANDOFF_THROTTLE_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    // Short grace for a Thread subclass registered right before start() while
    // still in Thread.State.NEW, and safety margin for Timer.cancel() wall-clock
    // vs nanoTime() rounding (covering Linux ~1ms and Windows ~15.6ms clock ticks)
    // so an already-firing one-shot task is never misclassified as cancelled-before-fire.
    private static final long HANDLE_START_GRACE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long TIMER_CANCEL_MARGIN_NANOS = TimeUnit.MILLISECONDS.toNanos(20);
    // Weakly-keyed maps of cancelled TimerTasks and Timers populated by rewritten
    // TimerTask.cancel() / Timer.cancel() call sites (zero JDK-internal reflection,
    // so no JDK 9-15 illegal-access warning when Keploy is absent).
    private static final ConcurrentHashMap<WeakIdentityKey, TimerCancelInfo> CANCELLED_TIMER_TASKS =
            new ConcurrentHashMap<>();
    private static final ReferenceQueue<Object> DEAD_TIMER_TASK_KEYS = new ReferenceQueue<>();
    private static final ConcurrentHashMap<WeakIdentityKey, Long> CANCELLED_TIMERS =
            new ConcurrentHashMap<>();
    private static final ReferenceQueue<Object> DEAD_TIMER_KEYS = new ReferenceQueue<>();

    private static final class TimerCancelInfo {
        final boolean cancelledBeforeRun;
        final long cancelledAtNanos;

        TimerCancelInfo(boolean cancelledBeforeRun, long cancelledAtNanos) {
            this.cancelledBeforeRun = cancelledBeforeRun;
            this.cancelledAtNanos = cancelledAtNanos;
        }
    }

    // The window of the test running, made anew at each START and when END
    // waits: END waits only for the requests that came in since the START,
    // never for one that runs from before or one that comes in after END.
    private static volatile Window current = new Window(null);

    // Prior windows that were cut at END or still have running > 0: every new
    // window opened while any lingering window remains active (or just after a
    // cut END) is marked contaminated ("tail:<prevTestId>") until drained.
    private static final Set<Window> LINGERING = ConcurrentHashMap.newKeySet();
    private static final int MAX_LINGERING = 64;

    // Weak HandRef set for CompletableFuture stage permits not bound to an
    // explicit executor (plain dependents and 1-arg async stages), so abandoned
    // futures collected by GC promptly release their window permits via
    // DEAD_HANDS instead of pinning LINGERING forever.
    private static final Set<HandRef> PENDING_STAGES = ConcurrentHashMap.newKeySet();

    // Whether a CompletableFuture subclass inherits all 14 plain dependent
    // methods and whenComplete directly from CompletableFuture (true for exact
    // CompletableFuture, JDK HttpClient's MinimalFuture, and JDK MinimalStage;
    // false for custom subclasses overriding those methods).
    private static final ClassValue<Boolean> CF_SUBCLASS_SAFE_SYNC = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            if (type == CompletableFuture.class) {
                return Boolean.TRUE;
            }
            for (Class<?> c = type; c != null && c != CompletableFuture.class; c = c.getSuperclass()) {
                if (declaresCfMethod(c, "thenApply", Function.class)
                        || declaresCfMethod(c, "thenAccept", Consumer.class)
                        || declaresCfMethod(c, "thenRun", Runnable.class)
                        || declaresCfMethod(c, "thenCompose", Function.class)
                        || declaresCfMethod(c, "thenCombine", CompletionStage.class, BiFunction.class)
                        || declaresCfMethod(c, "thenAcceptBoth", CompletionStage.class, BiConsumer.class)
                        || declaresCfMethod(c, "runAfterBoth", CompletionStage.class, Runnable.class)
                        || declaresCfMethod(c, "applyToEither", CompletionStage.class, Function.class)
                        || declaresCfMethod(c, "acceptEither", CompletionStage.class, Consumer.class)
                        || declaresCfMethod(c, "runAfterEither", CompletionStage.class, Runnable.class)
                        || declaresCfMethod(c, "handle", BiFunction.class)
                        || declaresCfMethod(c, "whenComplete", BiConsumer.class)
                        || declaresCfMethod(c, "exceptionally", Function.class)
                        || declaresCfMethod(c, "exceptionallyCompose", Function.class)) {
                    return Boolean.FALSE;
                }
            }
            return Boolean.TRUE;
        }
    };

    private static boolean declaresCfMethod(Class<?> type, String name, Class<?>... params) {
        try {
            Method m = type.getDeclaredMethod(name, params);
            // Ignore javac-generated bridge/synthetic methods (e.g. on
            // package-private JDK subclasses like CompletableFuture$MinimalStage).
            return !m.isBridge() && !m.isSynthetic();
        } catch (NoSuchMethodException e) {
            return false;
        } catch (Throwable t) {
            // Conservative fail-closed if inspection fails.
            return true;
        }
    }

    // The window the thread's request, or the task a request handed off it
    // runs, counts in; null on any other thread.
    private static final ThreadLocal<Window> INSIDE = new ThreadLocal<>();

    // Scoped depth > 0 while RequestGate or a hooked Spring executor is
    // delegating a proven permit-holding Hand to an underlying JDK sink
    // (TPE.execute, STPE.delayedExecute, FJP external submit, Thread.start
    // for worker growth or SimpleAsyncTaskExecutor).
    private static final ThreadLocal<Integer> TRACKED_DISPATCH_DEPTH = new ThreadLocal<>();

    // Weak identity set of Keploy's own agent-internal pools (e.g. ProbeScan's
    // scan pool) so agent work never false-latches JDK sinks.
    private static final ConcurrentHashMap<WeakIdentityKey, Boolean> AGENT_INTERNAL_POOLS =
            new ConcurrentHashMap<>();
    private static final ReferenceQueue<Object> DEAD_AGENT_POOLS = new ReferenceQueue<>();

    // Tracked Executor wrapper for 1-arg CompletableFuture.*Async stages when
    // RequestGate holds a permit: delegates to ForkJoinPool.commonPool() inside
    // TRACKED_DISPATCH_DEPTH so even deferred completion from another thread is
    // proven tracked at the ForkJoinPool sink.
    private static final Executor TRACKED_COMMON_POOL = new Executor() {
        @Override
        public void execute(Runnable command) {
            enterTrackedDispatch();
            try {
                java.util.concurrent.ForkJoinPool.commonPool().execute(command);
            } finally {
                exitTrackedDispatch();
            }
        }
    };

    static void registerAgentInternalPool(Object pool) {
        if (pool == null) {
            return;
        }
        drainDeadAgentPools();
        AGENT_INTERNAL_POOLS.put(new WeakIdentityKey(pool, DEAD_AGENT_POOLS), Boolean.TRUE);
    }

    static boolean isAgentInternalPool(Object pool) {
        if (pool == null || AGENT_INTERNAL_POOLS.isEmpty()) {
            return false;
        }
        return AGENT_INTERNAL_POOLS.containsKey(new LookupKey(pool));
    }

    private static void drainDeadAgentPools() {
        Reference<?> ref;
        while ((ref = DEAD_AGENT_POOLS.poll()) != null) {
            AGENT_INTERNAL_POOLS.remove(ref);
        }
    }

    static boolean isTrackedTask(Object task) {
        return task instanceof Hand;
    }

    /**
     * Marks entry into a proven tracked dispatch scope when {@code handed} is a
     * {@link Hand} permit, allowing downstream JDK sinks to recognize it.
     *
     * @param handed task returned by {@link #hand(Object, Runnable)} or {@link #hand(Object, Callable)}
     */
    public static void enterTrackedDispatch(Object handed) {
        if (handed instanceof Hand) {
            enterTrackedDispatch();
        }
    }

    /**
     * Exits a proven tracked dispatch scope entered by {@link #enterTrackedDispatch(Object)}.
     *
     * @param handed task passed to {@link #enterTrackedDispatch(Object)}
     */
    public static void exitTrackedDispatch(Object handed) {
        if (handed instanceof Hand) {
            exitTrackedDispatch();
        }
    }

    static void enterTrackedDispatch() {
        Integer cur = TRACKED_DISPATCH_DEPTH.get();
        TRACKED_DISPATCH_DEPTH.set(cur == null ? Integer.valueOf(1) : Integer.valueOf(cur.intValue() + 1));
    }

    static void exitTrackedDispatch() {
        Integer cur = TRACKED_DISPATCH_DEPTH.get();
        if (cur == null || cur.intValue() <= 1) {
            TRACKED_DISPATCH_DEPTH.remove();
        } else {
            TRACKED_DISPATCH_DEPTH.set(Integer.valueOf(cur.intValue() - 1));
        }
    }

    static boolean isTrackedDispatchActive() {
        Integer cur = TRACKED_DISPATCH_DEPTH.get();
        return cur != null && cur.intValue() > 0;
    }

    static boolean isInsideTrackedWindowOrJob() {
        return INSIDE.get() != null
                || JobGate.runningAttribution() != null
                || JobGate.expiredRunningName() != null;
    }

    private static Executor wrapTrackedExecutor(final Executor delegate) {
        if (delegate == null) {
            return TRACKED_COMMON_POOL;
        }
        return new Executor() {
            @Override
            public void execute(Runnable command) {
                enterTrackedDispatch();
                try {
                    delegate.execute(command);
                } finally {
                    exitTrackedDispatch();
                }
            }
        };
    }

    static void handleUntrackedSink(String sink, Object receiver, Object task, String caller) {
        String target = caller != null ? caller
                : (receiver != null ? receiver.getClass().getName()
                        : (task != null ? task.getClass().getName() : "unknown"));
        String reason = "untracked-sink:" + sink + ":" + target;
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            noteBackgroundWorkerOutsideWindow(reason, false);
            return;
        }
        Window window = INSIDE.get();
        JobGate.Attribution job = currentOrExpiredJob(window);
        if (window == null && job == null) {
            noteBackgroundWorkerOutsideWindow(reason, true);
            return;
        }
        if (receiver instanceof Thread) {
            noteTrackedUntrackedAsync(reason, receiver, false);
        } else if (task instanceof Future) {
            noteTrackedUntrackedAsync(reason, task, false);
            KeployDedupAgent.noteObservedUntrackedAsyncLibrary("untracked async pool/scheduler (" + reason + ")", true);
        } else {
            noteUntrackedAsync(reason, 0L);
            KeployDedupAgent.noteObservedUntrackedAsyncLibrary("untracked async pool/scheduler (" + reason + ")", true);
        }
    }

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

    // Reflective handles for Java 9+ / 12+ CompletableFuture & CompletionStage
    // methods so keploy-sdk compiles against --release 8 (Java8ApiTest) while
    // rewriting JDK 9+ / 12+ apps.
    private static final Method CF_COMPLETE_ASYNC_1 =
            findMethod(CompletableFuture.class, "completeAsync", Supplier.class);
    private static final Method CF_COMPLETE_ASYNC_2 =
            findMethod(CompletableFuture.class, "completeAsync", Supplier.class, Executor.class);
    private static final Method CF_EXCEPTIONALLY_COMPOSE =
            findMethod(CompletableFuture.class, "exceptionallyCompose", Function.class);
    private static final Method CF_EXCEPTIONALLY_ASYNC_1 =
            findMethod(CompletableFuture.class, "exceptionallyAsync", Function.class);
    private static final Method CF_EXCEPTIONALLY_ASYNC_2 =
            findMethod(CompletableFuture.class, "exceptionallyAsync", Function.class, Executor.class);
    private static final Method CF_EXCEPTIONALLY_COMPOSE_ASYNC_1 =
            findMethod(CompletableFuture.class, "exceptionallyComposeAsync", Function.class);
    private static final Method CF_EXCEPTIONALLY_COMPOSE_ASYNC_2 =
            findMethod(CompletableFuture.class, "exceptionallyComposeAsync", Function.class, Executor.class);
    private static final Method CS_EXCEPTIONALLY_COMPOSE =
            findMethod(CompletionStage.class, "exceptionallyCompose", Function.class);
    private static final Method CS_EXCEPTIONALLY_ASYNC_1 =
            findMethod(CompletionStage.class, "exceptionallyAsync", Function.class);
    private static final Method CS_EXCEPTIONALLY_ASYNC_2 =
            findMethod(CompletionStage.class, "exceptionallyAsync", Function.class, Executor.class);
    private static final Method CS_EXCEPTIONALLY_COMPOSE_ASYNC_1 =
            findMethod(CompletionStage.class, "exceptionallyComposeAsync", Function.class);
    private static final Method CS_EXCEPTIONALLY_COMPOSE_ASYNC_2 =
            findMethod(CompletionStage.class, "exceptionallyComposeAsync", Function.class, Executor.class);

    private RequestGate() {
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
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

    static Object enterUnarmed() {
        noteUnarmedActivity();
        UNARMED_IN_FLIGHT.increment();
        return UNARMED_TOKEN;
    }

    static boolean isUnarmedToken(Object token) {
        return token == UNARMED_TOKEN;
    }

    static void exitUnarmed() {
        UNARMED_IN_FLIGHT.decrement();
    }

    static long unarmedInFlight() {
        return UNARMED_IN_FLIGHT.sum();
    }

    static void noteExternalUntrackedTail(String reason) {
        noteExternalUntrackedTail(reason, 0L);
    }

    static void noteExternalUntrackedTail(String reason, long extraTailNanos) {
        if (!armed) {
            return;
        }
        long now = System.nanoTime();
        long boundedExtra = Math.max(0L, Math.min(extraTailNanos, maxLingeringNanos));
        long expires = now + untrackedTailWindowNanos + boundedExtra;
        externalUntrackedTailReason = reason == null || reason.isEmpty() ? "job" : reason;
        long prev = externalUntrackedTailExpiresAtNanos;
        if (prev == 0L || (expires - prev) > 0L) {
            externalUntrackedTailExpiresAtNanos = expires;
        }
        externalUntrackedTailPending = true;
    }

    static void arm() {
        if (!armed) {
            if (unarmedEntered || UNARMED_IN_FLIGHT.sum() > 0 || lastUnarmedHandOffNanos != 0L) {
                pendingArmedMidFlight = true;
            }
            armed = true;
        }
    }

    static void disarm() {
        armed = false;
        unarmedEntered = false;
        pendingArmedMidFlight = false;
        lastUnarmedHandOffNanos = 0L;
        ARMED_MID_FLIGHT_CONSUMED.set(false);
        externalUntrackedTailPending = false;
        externalUntrackedTailExpiresAtNanos = 0L;
        externalUntrackedTailReason = null;
        EXTERNAL_UNTRACKED_HANDLES.clear();
        CANCELLED_TIMER_TASKS.clear();
        CANCELLED_TIMERS.clear();
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
     * When Keploy is unarmed, returns a zero-allocation sentinel token that
     * tracks unarmed in-flight concurrency without allocating or mutating any
     * window structures.
     *
     * @return the token to pass to {@link #exit(Object)} when it is done
     */
    public static Object enter() {
        if (!armed) {
            return enterUnarmed();
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
        window.executing.incrementAndGet();
        window.running.incrementAndGet();
        if (window != current) {
            retainLingering(window, System.nanoTime());
            Window cur = current;
            if (cur != null && cur != window) {
                cur.contaminate("tail:" + window.label());
            }
        }
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
        if (token == UNARMED_TOKEN) {
            exitUnarmed();
            return;
        }
        if (token instanceof Window) {
            INSIDE.remove();
            Window w = (Window) token;
            w.executing.decrementAndGet();
            done(w);
        } else if (token instanceof Nested) {
            Nested nested = (Nested) token;
            INSIDE.set(nested.outer);
            nested.window.executing.decrementAndGet();
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

    /**
     * Wraps a {@link Runnable} dispatched via {@code javax.servlet.AsyncContext#start}
     * or {@code jakarta.servlet.AsyncContext#start} so the dispatching request's
     * window (or tracked job) stays open until the runnable completes—even if
     * the runnable calls {@code AsyncContext.complete()} and continues working.
     * When invoked outside any active window or tracked job (or while unarmed),
     * latches {@code asyncExecutors=false} (fail closed).
     *
     * @param task runnable passed to {@code AsyncContext.start(Runnable)}
     * @return wrapped runnable holding the window permit, or {@code task} unchanged
     */
    public static Runnable handAsyncContext(Runnable task) {
        if (!armed) {
            if (task != null) {
                noteUnarmedHandOffIfInFlight();
                noteBackgroundWorkerOutsideWindow("untracked-async:async-context-outside-window", false);
            }
            return task;
        }
        if (task == null || task instanceof Handed) {
            return task;
        }
        Window window = INSIDE.get();
        JobGate.Attribution job = currentOrExpiredJob(window);
        if (window == null && job == null) {
            noteBackgroundWorkerOutsideWindow("untracked-async:async-context-outside-window", true);
            return task;
        }
        Handed handed = task instanceof Future
                ? new HandedFuture(window, job, task, false)
                : new Handed(window, job, task, false);
        if (window != null) {
            window.running.incrementAndGet();
        }
        return handed;
    }

    private static JobGate.Attribution currentOrExpiredJob(Window window) {
        if (window != null) {
            return null;
        }
        JobGate.Attribution job = JobGate.runningAttribution();
        return job != null ? job : JobGate.expiredAttribution();
    }

    private static void markUntrackedExecutor(Window window, JobGate.Attribution job, Object executor, String fallback) {
        String reason = "untracked-executor:" + (executor != null ? executor.getClass().getName() : fallback);
        if (window != null) {
            window.contaminate(reason);
        } else if (job != null) {
            boolean expired = job.hasDeadline && (System.nanoTime() - job.expiresAtNanos) >= 0L;
            JobGate.markUntracked(expired ? "job-tail:" + job.name : reason);
        }
    }

    private static Runnable handInternal(Object executor, Runnable task, boolean deferRelease) {
        if (!armed) {
            if (task != null) {
                noteUnarmedHandOffIfInFlight();
                noteExecutorHandOffOutsideWindow(executor, false);
            }
            return task;
        }
        if (task == null) {
            return null;
        }
        if (task instanceof Handed) {
            if (OrderedPools.isInlineExecutor(executor)) {
                return task;
            }
            if (OrderedPools.orders(executor)) {
                Handed existing = (Handed) task;
                existing.releaseIfPending();
                if (executor != null) {
                    markUntrackedExecutor(existing.window, existing.job, executor, "unknown");
                }
                return existing.task;
            }
            return task;
        }
        Window window = INSIDE.get();
        JobGate.Attribution job = currentOrExpiredJob(window);
        if (window == null && job == null) {
            noteExecutorHandOffOutsideWindow(executor, true);
            return task;
        }
        if (OrderedPools.isInlineExecutor(executor)) {
            return task;
        }
        if (task instanceof Comparable) {
            markUntrackedExecutor(window, job, executor, "comparable");
            return task;
        }
        if (OrderedPools.orders(executor)) {
            if (executor != null) {
                markUntrackedExecutor(window, job, executor, "unknown");
            }
            return task;
        }
        // A task that is a Future stays one, for the executor's cancel at
        // shutdown and its purge of cancelled tasks.
        boolean defer = deferRelease && (window != null || job != null);
        Handed handed = task instanceof Future
                ? new HandedFuture(window, job, task, defer)
                : new Handed(window, job, task, defer);
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
        if (!armed) {
            if (task != null) {
                noteUnarmedHandOffIfInFlight();
                noteExecutorHandOffOutsideWindow(executor, false);
            }
            return task;
        }
        if (task == null) {
            return null;
        }
        if (task instanceof HandedCall) {
            if (OrderedPools.isInlineExecutor(executor)) {
                return task;
            }
            if (OrderedPools.orders(executor)) {
                @SuppressWarnings("unchecked")
                HandedCall<T> existing = (HandedCall<T>) task;
                existing.releaseIfPending();
                if (executor != null) {
                    markUntrackedExecutor(existing.window, existing.job, executor, "unknown");
                }
                return existing.task;
            }
            return task;
        }
        Window window = INSIDE.get();
        JobGate.Attribution job = currentOrExpiredJob(window);
        if (window == null && job == null) {
            noteExecutorHandOffOutsideWindow(executor, true);
            return task;
        }
        if (OrderedPools.isInlineExecutor(executor)) {
            return task;
        }
        if (task instanceof Comparable) {
            markUntrackedExecutor(window, job, executor, "comparable");
            return task;
        }
        if (OrderedPools.orders(executor)) {
            if (executor != null) {
                markUntrackedExecutor(window, job, executor, "unknown");
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
        if (!armed) {
            if (supplier != null) {
                noteUnarmedHandOffIfInFlight();
                noteExecutorHandOffOutsideWindow(executor, false);
            }
            return supplier;
        }
        if (supplier == null) {
            return null;
        }
        if (supplier instanceof HandedSupplier) {
            if (OrderedPools.isInlineExecutor(executor)) {
                return supplier;
            }
            if (OrderedPools.orders(executor)) {
                @SuppressWarnings("unchecked")
                HandedSupplier<T> existing = (HandedSupplier<T>) supplier;
                existing.releaseIfPending();
                if (executor != null) {
                    markUntrackedExecutor(existing.window, existing.job, executor, "unknown");
                }
                return existing.task;
            }
            return supplier;
        }
        Window window = INSIDE.get();
        JobGate.Attribution job = currentOrExpiredJob(window);
        if (window == null && job == null) {
            noteExecutorHandOffOutsideWindow(executor, true);
            return supplier;
        }
        if (OrderedPools.isInlineExecutor(executor)) {
            return supplier;
        }
        if (supplier instanceof Comparable) {
            markUntrackedExecutor(window, job, executor, "comparable");
            return supplier;
        }
        if (OrderedPools.orders(executor)) {
            if (executor != null) {
                markUntrackedExecutor(window, job, executor, "unknown");
            }
            return supplier;
        }
        boolean defer = deferRelease && (window != null || job != null);
        HandedSupplier<T> handed = new HandedSupplier<>(window, job, supplier, defer);
        if (window != null) {
            window.running.incrementAndGet();
        }
        return handed;
    }

    private static void noteUnarmedHandOffIfInFlight() {
        if (UNARMED_IN_FLIGHT.sum() > 0) {
            long now = System.nanoTime();
            long prev = lastUnarmedHandOffNanos;
            if (prev == 0L || (now - prev) >= UNARMED_HANDOFF_THROTTLE_NANOS) {
                lastUnarmedHandOffNanos = now;
            }
        }
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
            noteUnarmedHandOffIfInFlight();
            noteExecutorHandOffOutsideWindow(executor, false);
            executor.execute(task);
            return;
        }
        Runnable handed = hand(executor, task);
        registerForExecutor(executor, handed, task);
        enterTrackedDispatch(handed);
        try {
            executor.execute(handed);
        } catch (Throwable t) {
            dropped(handed, task);
            throw t;
        } finally {
            exitTrackedDispatch(handed);
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
            noteUnarmedHandOffIfInFlight();
            noteExecutorHandOffOutsideWindow(executor, false);
            return executor.submit(task);
        }
        Runnable handed = hand(executor, task);
        registerForExecutor(executor, handed, task);
        Future<?> future;
        enterTrackedDispatch(handed);
        try {
            future = executor.submit(handed);
        } catch (Throwable t) {
            dropped(handed, task);
            throw t;
        } finally {
            exitTrackedDispatch(handed);
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
            noteUnarmedHandOffIfInFlight();
            noteExecutorHandOffOutsideWindow(executor, false);
            return executor.submit(task, result);
        }
        Runnable handed = hand(executor, task);
        registerForExecutor(executor, handed, task);
        Future<T> future;
        enterTrackedDispatch(handed);
        try {
            future = executor.submit(handed, result);
        } catch (Throwable t) {
            dropped(handed, task);
            throw t;
        } finally {
            exitTrackedDispatch(handed);
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
            noteUnarmedHandOffIfInFlight();
            noteExecutorHandOffOutsideWindow(executor, false);
            return executor.submit(task);
        }
        Callable<T> handed = hand(executor, task);
        registerForExecutor(executor, handed, task);
        Future<T> future;
        enterTrackedDispatch(handed);
        try {
            future = executor.submit(handed);
        } catch (Throwable t) {
            dropped(handed, task);
            throw t;
        } finally {
            exitTrackedDispatch(handed);
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
            noteUnarmedHandOffIfInFlight();
            noteExecutorHandOffOutsideWindow(null, false);
            return CompletableFuture.runAsync(task);
        }
        Runnable handed = handInternal(null, task, true);
        CompletableFuture<Void> cf;
        enterTrackedDispatch(handed);
        try {
            cf = CompletableFuture.runAsync(handed);
        } catch (Throwable t) {
            dropped(handed, task);
            throw t;
        } finally {
            exitTrackedDispatch(handed);
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
            noteUnarmedHandOffIfInFlight();
            noteExecutorHandOffOutsideWindow(executor, false);
            return CompletableFuture.runAsync(task, executor);
        }
        Runnable handed = handInternal(executor, task, true);
        registerForExecutor(executor, handed, task);
        CompletableFuture<Void> cf;
        enterTrackedDispatch(handed);
        try {
            cf = CompletableFuture.runAsync(handed, executor);
        } catch (Throwable t) {
            dropped(handed, task);
            throw t;
        } finally {
            exitTrackedDispatch(handed);
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
            noteUnarmedHandOffIfInFlight();
            noteExecutorHandOffOutsideWindow(null, false);
            return CompletableFuture.supplyAsync(supplier);
        }
        Supplier<T> handed = handSupplierInternal(null, supplier, true);
        CompletableFuture<T> cf;
        enterTrackedDispatch(handed);
        try {
            cf = CompletableFuture.supplyAsync(handed);
        } catch (Throwable t) {
            dropped(handed, supplier);
            throw t;
        } finally {
            exitTrackedDispatch(handed);
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
            noteUnarmedHandOffIfInFlight();
            noteExecutorHandOffOutsideWindow(executor, false);
            return CompletableFuture.supplyAsync(supplier, executor);
        }
        Supplier<T> handed = handSupplierInternal(executor, supplier, true);
        registerForExecutor(executor, handed, supplier);
        CompletableFuture<T> cf;
        enterTrackedDispatch(handed);
        try {
            cf = CompletableFuture.supplyAsync(handed, executor);
        } catch (Throwable t) {
            dropped(handed, supplier);
            throw t;
        } finally {
            exitTrackedDispatch(handed);
        }
        attachCompletionRelease(cf, handed, supplier);
        return cf;
    }

    // --- CompletableFuture & CompletionStage instance method call-site wrappers ---

    private static Hand prepareCfStageHand(CompletableFuture<?> receiver, Executor explicitExecutor, boolean hasExecutor) {
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            return null;
        }
        Window window = INSIDE.get();
        JobGate.Attribution job = currentOrExpiredJob(window);
        if (window == null && job == null) {
            return null;
        }
        if (hasExecutor) {
            if (explicitExecutor == null) {
                return null;
            }
            if (!OrderedPools.isInlineExecutor(explicitExecutor) && OrderedPools.orders(explicitExecutor)) {
                markUntrackedExecutor(window, job, explicitExecutor, "unknown");
                return null;
            }
        } else {
            // Default executor: safe only for exact CompletableFuture.class
            // (a custom subclass could override defaultExecutor()).
            if (receiver != null && receiver.getClass() != CompletableFuture.class) {
                markUntrackedExecutor(window, job, receiver, "unknown");
                return null;
            }
        }
        StageHand hand = new StageHand(window, job, true);
        if (window != null) {
            window.running.incrementAndGet();
        }
        if (hasExecutor && explicitExecutor != null) {
            registerForExecutor(explicitExecutor, hand, null);
        } else {
            registerStageHand(hand);
        }
        return hand;
    }

    // Checks isDone() safely without ever throwing UnsupportedOperationException
    // on JDK 9+ MinimalStage (CompletableFuture.completedStage/failedStage/
    // minimalCompletionStage).
    private static boolean safeIsDone(Object stage) {
        if (stage == null) {
            return false;
        }
        if (stage.getClass() == CompletableFuture.class) {
            return ((CompletableFuture<?>) stage).isDone();
        }
        if (stage instanceof CompletableFuture) {
            try {
                return ((CompletableFuture<?>) stage).isDone();
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    private static Hand prepareCfSyncHand(CompletableFuture<?> receiver, CompletionStage<?> other, boolean either) {
        if (!armed) {
            if (receiver != null) {
                noteUnarmedHandOffIfInFlight();
            }
            return null;
        }
        if (receiver == null) {
            return null;
        }
        Window window = INSIDE.get();
        JobGate.Attribution job = currentOrExpiredJob(window);
        if (window == null && job == null) {
            return null;
        }
        if (other == null) {
            if (safeIsDone(receiver)) {
                return null;
            }
        } else {
            boolean receiverDone = safeIsDone(receiver);
            boolean otherDone = safeIsDone(other);
            if (either ? (receiverDone || otherDone) : (receiverDone && otherDone)) {
                return null;
            }
        }
        // Plain synchronous dependents never invoke defaultExecutor(), so
        // CompletableFuture subclasses that inherit the dependent methods and
        // whenComplete directly (including JDK HttpClient's MinimalFuture and
        // MinimalStage) are safely tracked; only subclasses overriding those
        // methods fail closed.
        if (!Boolean.TRUE.equals(CF_SUBCLASS_SAFE_SYNC.get(receiver.getClass()))) {
            markUntrackedExecutor(window, job, receiver, "unknown");
            return null;
        }
        StageHand hand = new StageHand(window, job, true);
        if (window != null) {
            window.running.incrementAndGet();
            registerStageHand(hand);
        }
        return hand;
    }

    private static void registerStageHand(Object handed) {
        drainDeadHands();
        if (!(handed instanceof Hand)) {
            return;
        }
        Hand hand = (Hand) handed;
        if (hand.window == null || hand.isReleased() || hand.executorRef != null) {
            return;
        }
        try {
            HandRef ref = new HandRef(hand, DEAD_HANDS, PENDING_STAGES, hand.window);
            PENDING_STAGES.add(ref);
            hand.executorRef = ref;
            if (hand.isReleased()) {
                ref.released = true;
                hand.executorRef = null;
                PENDING_STAGES.remove(ref);
            }
        } catch (Throwable ignored) {
            // Never affect app execution.
        }
    }

    private static <T> CompletableFuture<T> finishCfStage(CompletableFuture<T> out, Hand hand) {
        if (hand != null) {
            attachCompletionRelease(out, hand, null);
        }
        return out;
    }

    private static <T> Supplier<T> wrapSupplier(final Hand hand, final Supplier<? extends T> supplier) {
        if (hand == null || supplier == null) {
            return null;
        }
        return new Supplier<T>() {
            @Override
            public T get() {
                Object outer = hand.enter();
                try {
                    return supplier.get();
                } finally {
                    hand.exit(outer);
                }
            }
        };
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

    /**
     * Call-site wrapper for {@code CompletableFuture.completeAsync(Supplier)}.
     *
     * @param <T> future result type
     * @param cf target future
     * @param supplier async supplier
     * @return the target future
     */
    @SuppressWarnings("unchecked")
    public static <T> CompletableFuture<T> completeAsync(CompletableFuture<T> cf,
            Supplier<? extends T> supplier) {
        Hand hand = supplier == null ? null : prepareCfStageHand(cf, null, false);
        Supplier<? extends T> target = hand == null ? supplier : wrapSupplier(hand, supplier);
        CompletableFuture<T> out;
        if (hand != null) {
            enterTrackedDispatch();
        }
        try {
            out = (CompletableFuture<T>) invokeCfReflect(CF_COMPLETE_ASYNC_1, cf, target);
        } catch (Throwable t) {
            if (hand != null) {
                hand.releaseIfPending();
            }
            throw sneakyThrow(t);
        } finally {
            if (hand != null) {
                exitTrackedDispatch();
            }
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@code CompletableFuture.completeAsync(Supplier, Executor)}.
     *
     * @param <T> future result type
     * @param cf target future
     * @param supplier async supplier
     * @param executor target executor
     * @return the target future
     */
    @SuppressWarnings("unchecked")
    public static <T> CompletableFuture<T> completeAsync(CompletableFuture<T> cf,
            Supplier<? extends T> supplier, Executor executor) {
        Hand hand = supplier == null ? null : prepareCfStageHand(cf, executor, true);
        Supplier<? extends T> target = hand == null ? supplier : wrapSupplier(hand, supplier);
        CompletableFuture<T> out;
        try {
            Executor targetExecutor = hand == null ? executor : wrapTrackedExecutor(executor);
            out = (CompletableFuture<T>) invokeCfReflect(CF_COMPLETE_ASYNC_2, cf, target, targetExecutor);
        } catch (Throwable t) {
            if (hand != null) {
                hand.releaseIfPending();
            }
            throw sneakyThrow(t);
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#thenApplyAsync(Function)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param cf target future
     * @param fn stage function
     * @return dependent future
     */
    public static <T, U> CompletableFuture<U> thenApplyAsync(CompletableFuture<T> cf,
            Function<? super T, ? extends U> fn) {
        Hand hand = fn == null ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.thenApplyAsync(fn);
        }
        CompletableFuture<U> out;
        try {
            out = cf.thenApplyAsync(wrapFunction(hand, fn), TRACKED_COMMON_POOL);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#thenApplyAsync(Function, Executor)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param cf target future
     * @param fn stage function
     * @param executor target executor
     * @return dependent future
     */
    public static <T, U> CompletableFuture<U> thenApplyAsync(CompletableFuture<T> cf,
            Function<? super T, ? extends U> fn, Executor executor) {
        Hand hand = fn == null ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.thenApplyAsync(fn, executor);
        }
        CompletableFuture<U> out;
        try {
            out = cf.thenApplyAsync(wrapFunction(hand, fn), wrapTrackedExecutor(executor));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#thenAcceptAsync(Consumer)}.
     *
     * @param <T> input type
     * @param cf target future
     * @param action stage consumer
     * @return dependent future
     */
    public static <T> CompletableFuture<Void> thenAcceptAsync(CompletableFuture<T> cf,
            Consumer<? super T> action) {
        Hand hand = action == null ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.thenAcceptAsync(action);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.thenAcceptAsync(wrapConsumer(hand, action), TRACKED_COMMON_POOL);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#thenAcceptAsync(Consumer, Executor)}.
     *
     * @param <T> input type
     * @param cf target future
     * @param action stage consumer
     * @param executor target executor
     * @return dependent future
     */
    public static <T> CompletableFuture<Void> thenAcceptAsync(CompletableFuture<T> cf,
            Consumer<? super T> action, Executor executor) {
        Hand hand = action == null ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.thenAcceptAsync(action, executor);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.thenAcceptAsync(wrapConsumer(hand, action), wrapTrackedExecutor(executor));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#thenRunAsync(Runnable)}.
     *
     * @param <T> input type
     * @param cf target future
     * @param action stage runnable
     * @return dependent future
     */
    public static <T> CompletableFuture<Void> thenRunAsync(CompletableFuture<T> cf, Runnable action) {
        Hand hand = action == null ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.thenRunAsync(action);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.thenRunAsync(wrapStageRunnable(hand, action), TRACKED_COMMON_POOL);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#thenRunAsync(Runnable, Executor)}.
     *
     * @param <T> input type
     * @param cf target future
     * @param action stage runnable
     * @param executor target executor
     * @return dependent future
     */
    public static <T> CompletableFuture<Void> thenRunAsync(CompletableFuture<T> cf, Runnable action,
            Executor executor) {
        Hand hand = action == null ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.thenRunAsync(action, executor);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.thenRunAsync(wrapStageRunnable(hand, action), wrapTrackedExecutor(executor));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#thenComposeAsync(Function)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param cf target future
     * @param fn composition function
     * @return dependent future
     */
    public static <T, U> CompletableFuture<U> thenComposeAsync(CompletableFuture<T> cf,
            Function<? super T, ? extends CompletionStage<U>> fn) {
        Hand hand = fn == null ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.thenComposeAsync(fn);
        }
        CompletableFuture<U> out;
        try {
            out = cf.thenComposeAsync(wrapFunction(hand, fn), TRACKED_COMMON_POOL);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#thenComposeAsync(Function, Executor)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param cf target future
     * @param fn composition function
     * @param executor target executor
     * @return dependent future
     */
    public static <T, U> CompletableFuture<U> thenComposeAsync(CompletableFuture<T> cf,
            Function<? super T, ? extends CompletionStage<U>> fn, Executor executor) {
        Hand hand = fn == null ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.thenComposeAsync(fn, executor);
        }
        CompletableFuture<U> out;
        try {
            out = cf.thenComposeAsync(wrapFunction(hand, fn), wrapTrackedExecutor(executor));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#thenCombineAsync(CompletionStage, BiFunction)}.
     *
     * @param <T> first input type
     * @param <U> second input type
     * @param <V> output type
     * @param cf target future
     * @param other other stage
     * @param fn combiner function
     * @return dependent future
     */
    public static <T, U, V> CompletableFuture<V> thenCombineAsync(CompletableFuture<T> cf,
            CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn) {
        Hand hand = (other == null || fn == null) ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.thenCombineAsync(other, fn);
        }
        CompletableFuture<V> out;
        try {
            out = cf.thenCombineAsync(other, wrapBiFunction(hand, fn), TRACKED_COMMON_POOL);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#thenCombineAsync(CompletionStage, BiFunction, Executor)}.
     *
     * @param <T> first input type
     * @param <U> second input type
     * @param <V> output type
     * @param cf target future
     * @param other other stage
     * @param fn combiner function
     * @param executor target executor
     * @return dependent future
     */
    public static <T, U, V> CompletableFuture<V> thenCombineAsync(CompletableFuture<T> cf,
            CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn,
            Executor executor) {
        Hand hand = (other == null || fn == null) ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.thenCombineAsync(other, fn, executor);
        }
        CompletableFuture<V> out;
        try {
            out = cf.thenCombineAsync(other, wrapBiFunction(hand, fn), wrapTrackedExecutor(executor));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#thenAcceptBothAsync(CompletionStage, BiConsumer)}.
     *
     * @param <T> first input type
     * @param <U> second input type
     * @param cf target future
     * @param other other stage
     * @param action combiner consumer
     * @return dependent future
     */
    public static <T, U> CompletableFuture<Void> thenAcceptBothAsync(CompletableFuture<T> cf,
            CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) {
        Hand hand = (other == null || action == null) ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.thenAcceptBothAsync(other, action);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.thenAcceptBothAsync(other, wrapBiConsumer(hand, action), TRACKED_COMMON_POOL);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#thenAcceptBothAsync(CompletionStage, BiConsumer, Executor)}.
     *
     * @param <T> first input type
     * @param <U> second input type
     * @param cf target future
     * @param other other stage
     * @param action combiner consumer
     * @param executor target executor
     * @return dependent future
     */
    public static <T, U> CompletableFuture<Void> thenAcceptBothAsync(CompletableFuture<T> cf,
            CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action, Executor executor) {
        Hand hand = (other == null || action == null) ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.thenAcceptBothAsync(other, action, executor);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.thenAcceptBothAsync(other, wrapBiConsumer(hand, action), wrapTrackedExecutor(executor));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#runAfterBothAsync(CompletionStage, Runnable)}.
     *
     * @param <T> first input type
     * @param cf target future
     * @param other other stage
     * @param action stage runnable
     * @return dependent future
     */
    public static <T> CompletableFuture<Void> runAfterBothAsync(CompletableFuture<T> cf,
            CompletionStage<?> other, Runnable action) {
        Hand hand = (other == null || action == null) ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.runAfterBothAsync(other, action);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.runAfterBothAsync(other, wrapStageRunnable(hand, action), TRACKED_COMMON_POOL);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#runAfterBothAsync(CompletionStage, Runnable, Executor)}.
     *
     * @param <T> first input type
     * @param cf target future
     * @param other other stage
     * @param action stage runnable
     * @param executor target executor
     * @return dependent future
     */
    public static <T> CompletableFuture<Void> runAfterBothAsync(CompletableFuture<T> cf,
            CompletionStage<?> other, Runnable action, Executor executor) {
        Hand hand = (other == null || action == null) ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.runAfterBothAsync(other, action, executor);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.runAfterBothAsync(other, wrapStageRunnable(hand, action), wrapTrackedExecutor(executor));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#applyToEitherAsync(CompletionStage, Function)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param cf target future
     * @param other other stage
     * @param fn stage function
     * @return dependent future
     */
    public static <T, U> CompletableFuture<U> applyToEitherAsync(CompletableFuture<T> cf,
            CompletionStage<? extends T> other, Function<? super T, U> fn) {
        Hand hand = (other == null || fn == null) ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.applyToEitherAsync(other, fn);
        }
        CompletableFuture<U> out;
        try {
            out = cf.applyToEitherAsync(other, wrapFunction(hand, fn), TRACKED_COMMON_POOL);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#applyToEitherAsync(CompletionStage, Function, Executor)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param cf target future
     * @param other other stage
     * @param fn stage function
     * @param executor target executor
     * @return dependent future
     */
    public static <T, U> CompletableFuture<U> applyToEitherAsync(CompletableFuture<T> cf,
            CompletionStage<? extends T> other, Function<? super T, U> fn, Executor executor) {
        Hand hand = (other == null || fn == null) ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.applyToEitherAsync(other, fn, executor);
        }
        CompletableFuture<U> out;
        try {
            out = cf.applyToEitherAsync(other, wrapFunction(hand, fn), wrapTrackedExecutor(executor));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#acceptEitherAsync(CompletionStage, Consumer)}.
     *
     * @param <T> input type
     * @param cf target future
     * @param other other stage
     * @param action stage consumer
     * @return dependent future
     */
    public static <T> CompletableFuture<Void> acceptEitherAsync(CompletableFuture<T> cf,
            CompletionStage<? extends T> other, Consumer<? super T> action) {
        Hand hand = (other == null || action == null) ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.acceptEitherAsync(other, action);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.acceptEitherAsync(other, wrapConsumer(hand, action), TRACKED_COMMON_POOL);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#acceptEitherAsync(CompletionStage, Consumer, Executor)}.
     *
     * @param <T> input type
     * @param cf target future
     * @param other other stage
     * @param action stage consumer
     * @param executor target executor
     * @return dependent future
     */
    public static <T> CompletableFuture<Void> acceptEitherAsync(CompletableFuture<T> cf,
            CompletionStage<? extends T> other, Consumer<? super T> action, Executor executor) {
        Hand hand = (other == null || action == null) ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.acceptEitherAsync(other, action, executor);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.acceptEitherAsync(other, wrapConsumer(hand, action), wrapTrackedExecutor(executor));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#runAfterEitherAsync(CompletionStage, Runnable)}.
     *
     * @param <T> input type
     * @param cf target future
     * @param other other stage
     * @param action stage runnable
     * @return dependent future
     */
    public static <T> CompletableFuture<Void> runAfterEitherAsync(CompletableFuture<T> cf,
            CompletionStage<?> other, Runnable action) {
        Hand hand = (other == null || action == null) ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.runAfterEitherAsync(other, action);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.runAfterEitherAsync(other, wrapStageRunnable(hand, action), TRACKED_COMMON_POOL);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#runAfterEitherAsync(CompletionStage, Runnable, Executor)}.
     *
     * @param <T> input type
     * @param cf target future
     * @param other other stage
     * @param action stage runnable
     * @param executor target executor
     * @return dependent future
     */
    public static <T> CompletableFuture<Void> runAfterEitherAsync(CompletableFuture<T> cf,
            CompletionStage<?> other, Runnable action, Executor executor) {
        Hand hand = (other == null || action == null) ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.runAfterEitherAsync(other, action, executor);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.runAfterEitherAsync(other, wrapStageRunnable(hand, action), wrapTrackedExecutor(executor));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#handleAsync(BiFunction)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param cf target future
     * @param fn handler function
     * @return dependent future
     */
    public static <T, U> CompletableFuture<U> handleAsync(CompletableFuture<T> cf,
            BiFunction<? super T, Throwable, ? extends U> fn) {
        Hand hand = fn == null ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.handleAsync(fn);
        }
        CompletableFuture<U> out;
        try {
            out = cf.handleAsync(wrapBiFunction(hand, fn), TRACKED_COMMON_POOL);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#handleAsync(BiFunction, Executor)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param cf target future
     * @param fn handler function
     * @param executor target executor
     * @return dependent future
     */
    public static <T, U> CompletableFuture<U> handleAsync(CompletableFuture<T> cf,
            BiFunction<? super T, Throwable, ? extends U> fn, Executor executor) {
        Hand hand = fn == null ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.handleAsync(fn, executor);
        }
        CompletableFuture<U> out;
        try {
            out = cf.handleAsync(wrapBiFunction(hand, fn), wrapTrackedExecutor(executor));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#whenCompleteAsync(BiConsumer)}.
     *
     * @param <T> result type
     * @param cf target future
     * @param action completion consumer
     * @return dependent future
     */
    public static <T> CompletableFuture<T> whenCompleteAsync(CompletableFuture<T> cf,
            BiConsumer<? super T, ? super Throwable> action) {
        Hand hand = action == null ? null : prepareCfStageHand(cf, null, false);
        if (hand == null) {
            return cf.whenCompleteAsync(action);
        }
        CompletableFuture<T> out;
        try {
            out = cf.whenCompleteAsync(wrapBiConsumer(hand, action), TRACKED_COMMON_POOL);
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#whenCompleteAsync(BiConsumer, Executor)}.
     *
     * @param <T> result type
     * @param cf target future
     * @param action completion consumer
     * @param executor target executor
     * @return dependent future
     */
    public static <T> CompletableFuture<T> whenCompleteAsync(CompletableFuture<T> cf,
            BiConsumer<? super T, ? super Throwable> action, Executor executor) {
        Hand hand = action == null ? null : prepareCfStageHand(cf, executor, true);
        if (hand == null) {
            return cf.whenCompleteAsync(action, executor);
        }
        CompletableFuture<T> out;
        try {
            out = cf.whenCompleteAsync(wrapBiConsumer(hand, action), wrapTrackedExecutor(executor));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@code CompletableFuture.exceptionallyAsync(Function)}.
     *
     * @param <T> result type
     * @param cf target future
     * @param fn recovery function
     * @return dependent future
     */
    @SuppressWarnings("unchecked")
    public static <T> CompletableFuture<T> exceptionallyAsync(CompletableFuture<T> cf,
            Function<Throwable, ? extends T> fn) {
        Hand hand = fn == null ? null : prepareCfStageHand(cf, null, false);
        Function<Throwable, ? extends T> target = hand == null ? fn : wrapFunction(hand, fn);
        CompletableFuture<T> out;
        try {
            out = hand == null ? (CompletableFuture<T>) invokeCfReflect(CF_EXCEPTIONALLY_ASYNC_1, cf, target) : (CompletableFuture<T>) invokeCfReflect(CF_EXCEPTIONALLY_ASYNC_2, cf, target, TRACKED_COMMON_POOL);
        } catch (Throwable t) {
            if (hand != null) {
                hand.releaseIfPending();
            }
            throw sneakyThrow(t);
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@code CompletableFuture.exceptionallyAsync(Function, Executor)}.
     *
     * @param <T> result type
     * @param cf target future
     * @param fn recovery function
     * @param executor target executor
     * @return dependent future
     */
    @SuppressWarnings("unchecked")
    public static <T> CompletableFuture<T> exceptionallyAsync(CompletableFuture<T> cf,
            Function<Throwable, ? extends T> fn, Executor executor) {
        Hand hand = fn == null ? null : prepareCfStageHand(cf, executor, true);
        Function<Throwable, ? extends T> target = hand == null ? fn : wrapFunction(hand, fn);
        CompletableFuture<T> out;
        try {
            out = (CompletableFuture<T>) invokeCfReflect(CF_EXCEPTIONALLY_ASYNC_2, cf, target, hand == null ? executor : wrapTrackedExecutor(executor));
        } catch (Throwable t) {
            if (hand != null) {
                hand.releaseIfPending();
            }
            throw sneakyThrow(t);
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@code CompletableFuture.exceptionallyComposeAsync(Function)}.
     *
     * @param <T> result type
     * @param cf target future
     * @param fn recovery composition function
     * @return dependent future
     */
    @SuppressWarnings("unchecked")
    public static <T> CompletableFuture<T> exceptionallyComposeAsync(CompletableFuture<T> cf,
            Function<Throwable, ? extends CompletionStage<T>> fn) {
        Hand hand = fn == null ? null : prepareCfStageHand(cf, null, false);
        Function<Throwable, ? extends CompletionStage<T>> target = hand == null ? fn : wrapFunction(hand, fn);
        CompletableFuture<T> out;
        try {
            out = hand == null ? (CompletableFuture<T>) invokeCfReflect(CF_EXCEPTIONALLY_COMPOSE_ASYNC_1, cf, target) : (CompletableFuture<T>) invokeCfReflect(CF_EXCEPTIONALLY_COMPOSE_ASYNC_2, cf, target, TRACKED_COMMON_POOL);
        } catch (Throwable t) {
            if (hand != null) {
                hand.releaseIfPending();
            }
            throw sneakyThrow(t);
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@code CompletableFuture.exceptionallyComposeAsync(Function, Executor)}.
     *
     * @param <T> result type
     * @param cf target future
     * @param fn recovery composition function
     * @param executor target executor
     * @return dependent future
     */
    @SuppressWarnings("unchecked")
    public static <T> CompletableFuture<T> exceptionallyComposeAsync(CompletableFuture<T> cf,
            Function<Throwable, ? extends CompletionStage<T>> fn, Executor executor) {
        Hand hand = fn == null ? null : prepareCfStageHand(cf, executor, true);
        Function<Throwable, ? extends CompletionStage<T>> target = hand == null ? fn : wrapFunction(hand, fn);
        CompletableFuture<T> out;
        try {
            out = (CompletableFuture<T>) invokeCfReflect(CF_EXCEPTIONALLY_COMPOSE_ASYNC_2, cf, target, hand == null ? executor : wrapTrackedExecutor(executor));
        } catch (Throwable t) {
            if (hand != null) {
                hand.releaseIfPending();
            }
            throw sneakyThrow(t);
        }
        return finishCfStage(out, hand);
    }

    // --- CompletableFuture plain dependent stage wrappers (tracks external library futures) ---

    /**
     * Call-site wrapper for {@link CompletableFuture#thenApply(Function)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param cf target future
     * @param fn stage function
     * @return dependent future
     */
    public static <T, U> CompletableFuture<U> thenApply(CompletableFuture<T> cf,
            Function<? super T, ? extends U> fn) {
        Hand hand = fn == null ? null : prepareCfSyncHand(cf, null, false);
        if (hand == null) {
            return cf.thenApply(fn);
        }
        CompletableFuture<U> out;
        try {
            out = cf.thenApply(wrapFunction(hand, fn));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#thenAccept(Consumer)}.
     *
     * @param <T> input type
     * @param cf target future
     * @param action stage consumer
     * @return dependent future
     */
    public static <T> CompletableFuture<Void> thenAccept(CompletableFuture<T> cf,
            Consumer<? super T> action) {
        Hand hand = action == null ? null : prepareCfSyncHand(cf, null, false);
        if (hand == null) {
            return cf.thenAccept(action);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.thenAccept(wrapConsumer(hand, action));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#thenRun(Runnable)}.
     *
     * @param <T> input type
     * @param cf target future
     * @param action stage runnable
     * @return dependent future
     */
    public static <T> CompletableFuture<Void> thenRun(CompletableFuture<T> cf, Runnable action) {
        Hand hand = action == null ? null : prepareCfSyncHand(cf, null, false);
        if (hand == null) {
            return cf.thenRun(action);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.thenRun(wrapStageRunnable(hand, action));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#thenCompose(Function)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param cf target future
     * @param fn composition function
     * @return dependent future
     */
    public static <T, U> CompletableFuture<U> thenCompose(CompletableFuture<T> cf,
            Function<? super T, ? extends CompletionStage<U>> fn) {
        Hand hand = fn == null ? null : prepareCfSyncHand(cf, null, false);
        if (hand == null) {
            return cf.thenCompose(fn);
        }
        CompletableFuture<U> out;
        try {
            out = cf.thenCompose(wrapFunction(hand, fn));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#thenCombine(CompletionStage, BiFunction)}.
     *
     * @param <T> first input type
     * @param <U> second input type
     * @param <V> output type
     * @param cf target future
     * @param other other stage
     * @param fn combiner function
     * @return dependent future
     */
    public static <T, U, V> CompletableFuture<V> thenCombine(CompletableFuture<T> cf,
            CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn) {
        Hand hand = (other == null || fn == null) ? null : prepareCfSyncHand(cf, other, false);
        if (hand == null) {
            return cf.thenCombine(other, fn);
        }
        CompletableFuture<V> out;
        try {
            out = cf.thenCombine(other, wrapBiFunction(hand, fn));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#thenAcceptBoth(CompletionStage, BiConsumer)}.
     *
     * @param <T> first input type
     * @param <U> second input type
     * @param cf target future
     * @param other other stage
     * @param action combiner consumer
     * @return dependent future
     */
    public static <T, U> CompletableFuture<Void> thenAcceptBoth(CompletableFuture<T> cf,
            CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) {
        Hand hand = (other == null || action == null) ? null : prepareCfSyncHand(cf, other, false);
        if (hand == null) {
            return cf.thenAcceptBoth(other, action);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.thenAcceptBoth(other, wrapBiConsumer(hand, action));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#runAfterBoth(CompletionStage, Runnable)}.
     *
     * @param <T> first input type
     * @param cf target future
     * @param other other stage
     * @param action stage runnable
     * @return dependent future
     */
    public static <T> CompletableFuture<Void> runAfterBoth(CompletableFuture<T> cf,
            CompletionStage<?> other, Runnable action) {
        Hand hand = (other == null || action == null) ? null : prepareCfSyncHand(cf, other, false);
        if (hand == null) {
            return cf.runAfterBoth(other, action);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.runAfterBoth(other, wrapStageRunnable(hand, action));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#applyToEither(CompletionStage, Function)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param cf target future
     * @param other other stage
     * @param fn stage function
     * @return dependent future
     */
    public static <T, U> CompletableFuture<U> applyToEither(CompletableFuture<T> cf,
            CompletionStage<? extends T> other, Function<? super T, U> fn) {
        Hand hand = (other == null || fn == null) ? null : prepareCfSyncHand(cf, other, true);
        if (hand == null) {
            return cf.applyToEither(other, fn);
        }
        CompletableFuture<U> out;
        try {
            out = cf.applyToEither(other, wrapFunction(hand, fn));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#acceptEither(CompletionStage, Consumer)}.
     *
     * @param <T> input type
     * @param cf target future
     * @param other other stage
     * @param action stage consumer
     * @return dependent future
     */
    public static <T> CompletableFuture<Void> acceptEither(CompletableFuture<T> cf,
            CompletionStage<? extends T> other, Consumer<? super T> action) {
        Hand hand = (other == null || action == null) ? null : prepareCfSyncHand(cf, other, true);
        if (hand == null) {
            return cf.acceptEither(other, action);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.acceptEither(other, wrapConsumer(hand, action));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#runAfterEither(CompletionStage, Runnable)}.
     *
     * @param <T> input type
     * @param cf target future
     * @param other other stage
     * @param action stage runnable
     * @return dependent future
     */
    public static <T> CompletableFuture<Void> runAfterEither(CompletableFuture<T> cf,
            CompletionStage<?> other, Runnable action) {
        Hand hand = (other == null || action == null) ? null : prepareCfSyncHand(cf, other, true);
        if (hand == null) {
            return cf.runAfterEither(other, action);
        }
        CompletableFuture<Void> out;
        try {
            out = cf.runAfterEither(other, wrapStageRunnable(hand, action));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#handle(BiFunction)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param cf target future
     * @param fn handler function
     * @return dependent future
     */
    public static <T, U> CompletableFuture<U> handle(CompletableFuture<T> cf,
            BiFunction<? super T, Throwable, ? extends U> fn) {
        Hand hand = fn == null ? null : prepareCfSyncHand(cf, null, false);
        if (hand == null) {
            return cf.handle(fn);
        }
        CompletableFuture<U> out;
        try {
            out = cf.handle(wrapBiFunction(hand, fn));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#whenComplete(BiConsumer)}.
     *
     * @param <T> result type
     * @param cf target future
     * @param action completion consumer
     * @return dependent future
     */
    public static <T> CompletableFuture<T> whenComplete(CompletableFuture<T> cf,
            BiConsumer<? super T, ? super Throwable> action) {
        Hand hand = action == null ? null : prepareCfSyncHand(cf, null, false);
        if (hand == null) {
            return cf.whenComplete(action);
        }
        CompletableFuture<T> out;
        try {
            out = cf.whenComplete(wrapBiConsumer(hand, action));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@link CompletableFuture#exceptionally(Function)}.
     *
     * @param <T> result type
     * @param cf target future
     * @param fn recovery function
     * @return dependent future
     */
    public static <T> CompletableFuture<T> exceptionally(CompletableFuture<T> cf,
            Function<Throwable, ? extends T> fn) {
        Hand hand = fn == null ? null : prepareCfSyncHand(cf, null, false);
        if (hand == null) {
            return cf.exceptionally(fn);
        }
        CompletableFuture<T> out;
        try {
            out = cf.exceptionally(wrapFunction(hand, fn));
        } catch (Throwable t) {
            hand.releaseIfPending();
            throw t;
        }
        return finishCfStage(out, hand);
    }

    /**
     * Call-site wrapper for {@code CompletableFuture.exceptionallyCompose(Function)}.
     *
     * @param <T> result type
     * @param cf target future
     * @param fn recovery composition function
     * @return dependent future
     */
    @SuppressWarnings("unchecked")
    public static <T> CompletableFuture<T> exceptionallyCompose(CompletableFuture<T> cf,
            Function<Throwable, ? extends CompletionStage<T>> fn) {
        Hand hand = fn == null ? null : prepareCfSyncHand(cf, null, false);
        Function<Throwable, ? extends CompletionStage<T>> target = hand == null ? fn : wrapFunction(hand, fn);
        CompletableFuture<T> out;
        try {
            out = (CompletableFuture<T>) invokeCfReflect(CF_EXCEPTIONALLY_COMPOSE, cf, target);
        } catch (Throwable t) {
            if (hand != null) {
                hand.releaseIfPending();
            }
            throw sneakyThrow(t);
        }
        return finishCfStage(out, hand);
    }

    // --- CompletionStage *Async and plain dependent call-site wrappers ---

    /**
     * Call-site wrapper for {@link CompletionStage#thenApplyAsync(Function)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param stage target stage
     * @param fn stage function
     * @return dependent stage
     */
    public static <T, U> CompletionStage<U> stageThenApplyAsync(CompletionStage<T> stage,
            Function<? super T, ? extends U> fn) {
        if (stage instanceof CompletableFuture) {
            return thenApplyAsync((CompletableFuture<T>) stage, fn);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.thenApplyAsync(fn);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#thenApplyAsync(Function, Executor)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param stage target stage
     * @param fn stage function
     * @param executor target executor
     * @return dependent stage
     */
    public static <T, U> CompletionStage<U> stageThenApplyAsync(CompletionStage<T> stage,
            Function<? super T, ? extends U> fn, Executor executor) {
        if (stage instanceof CompletableFuture) {
            return thenApplyAsync((CompletableFuture<T>) stage, fn, executor);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.thenApplyAsync(fn, executor);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#thenAcceptAsync(Consumer)}.
     *
     * @param <T> input type
     * @param stage target stage
     * @param action stage consumer
     * @return dependent stage
     */
    public static <T> CompletionStage<Void> stageThenAcceptAsync(CompletionStage<T> stage,
            Consumer<? super T> action) {
        if (stage instanceof CompletableFuture) {
            return thenAcceptAsync((CompletableFuture<T>) stage, action);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.thenAcceptAsync(action);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#thenAcceptAsync(Consumer, Executor)}.
     *
     * @param <T> input type
     * @param stage target stage
     * @param action stage consumer
     * @param executor target executor
     * @return dependent stage
     */
    public static <T> CompletionStage<Void> stageThenAcceptAsync(CompletionStage<T> stage,
            Consumer<? super T> action, Executor executor) {
        if (stage instanceof CompletableFuture) {
            return thenAcceptAsync((CompletableFuture<T>) stage, action, executor);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.thenAcceptAsync(action, executor);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#thenRunAsync(Runnable)}.
     *
     * @param <T> input type
     * @param stage target stage
     * @param action stage runnable
     * @return dependent stage
     */
    public static <T> CompletionStage<Void> stageThenRunAsync(CompletionStage<T> stage, Runnable action) {
        if (stage instanceof CompletableFuture) {
            return thenRunAsync((CompletableFuture<T>) stage, action);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.thenRunAsync(action);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#thenRunAsync(Runnable, Executor)}.
     *
     * @param <T> input type
     * @param stage target stage
     * @param action stage runnable
     * @param executor target executor
     * @return dependent stage
     */
    public static <T> CompletionStage<Void> stageThenRunAsync(CompletionStage<T> stage, Runnable action,
            Executor executor) {
        if (stage instanceof CompletableFuture) {
            return thenRunAsync((CompletableFuture<T>) stage, action, executor);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.thenRunAsync(action, executor);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#thenComposeAsync(Function)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param stage target stage
     * @param fn composition function
     * @return dependent stage
     */
    public static <T, U> CompletionStage<U> stageThenComposeAsync(CompletionStage<T> stage,
            Function<? super T, ? extends CompletionStage<U>> fn) {
        if (stage instanceof CompletableFuture) {
            return thenComposeAsync((CompletableFuture<T>) stage, fn);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.thenComposeAsync(fn);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#thenComposeAsync(Function, Executor)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param stage target stage
     * @param fn composition function
     * @param executor target executor
     * @return dependent stage
     */
    public static <T, U> CompletionStage<U> stageThenComposeAsync(CompletionStage<T> stage,
            Function<? super T, ? extends CompletionStage<U>> fn, Executor executor) {
        if (stage instanceof CompletableFuture) {
            return thenComposeAsync((CompletableFuture<T>) stage, fn, executor);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.thenComposeAsync(fn, executor);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#thenCombineAsync(CompletionStage, BiFunction)}.
     *
     * @param <T> first input type
     * @param <U> second input type
     * @param <V> output type
     * @param stage target stage
     * @param other other stage
     * @param fn combiner function
     * @return dependent stage
     */
    public static <T, U, V> CompletionStage<V> stageThenCombineAsync(CompletionStage<T> stage,
            CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn) {
        if (stage instanceof CompletableFuture) {
            return thenCombineAsync((CompletableFuture<T>) stage, other, fn);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.thenCombineAsync(other, fn);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#thenCombineAsync(CompletionStage, BiFunction, Executor)}.
     *
     * @param <T> first input type
     * @param <U> second input type
     * @param <V> output type
     * @param stage target stage
     * @param other other stage
     * @param fn combiner function
     * @param executor target executor
     * @return dependent stage
     */
    public static <T, U, V> CompletionStage<V> stageThenCombineAsync(CompletionStage<T> stage,
            CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn,
            Executor executor) {
        if (stage instanceof CompletableFuture) {
            return thenCombineAsync((CompletableFuture<T>) stage, other, fn, executor);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.thenCombineAsync(other, fn, executor);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#thenAcceptBothAsync(CompletionStage, BiConsumer)}.
     *
     * @param <T> first input type
     * @param <U> second input type
     * @param stage target stage
     * @param other other stage
     * @param action combiner consumer
     * @return dependent stage
     */
    public static <T, U> CompletionStage<Void> stageThenAcceptBothAsync(CompletionStage<T> stage,
            CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) {
        if (stage instanceof CompletableFuture) {
            return thenAcceptBothAsync((CompletableFuture<T>) stage, other, action);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.thenAcceptBothAsync(other, action);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#thenAcceptBothAsync(CompletionStage, BiConsumer, Executor)}.
     *
     * @param <T> first input type
     * @param <U> second input type
     * @param stage target stage
     * @param other other stage
     * @param action combiner consumer
     * @param executor target executor
     * @return dependent stage
     */
    public static <T, U> CompletionStage<Void> stageThenAcceptBothAsync(CompletionStage<T> stage,
            CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action, Executor executor) {
        if (stage instanceof CompletableFuture) {
            return thenAcceptBothAsync((CompletableFuture<T>) stage, other, action, executor);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.thenAcceptBothAsync(other, action, executor);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#runAfterBothAsync(CompletionStage, Runnable)}.
     *
     * @param <T> first input type
     * @param stage target stage
     * @param other other stage
     * @param action stage runnable
     * @return dependent stage
     */
    public static <T> CompletionStage<Void> stageRunAfterBothAsync(CompletionStage<T> stage,
            CompletionStage<?> other, Runnable action) {
        if (stage instanceof CompletableFuture) {
            return runAfterBothAsync((CompletableFuture<T>) stage, other, action);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.runAfterBothAsync(other, action);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#runAfterBothAsync(CompletionStage, Runnable, Executor)}.
     *
     * @param <T> first input type
     * @param stage target stage
     * @param other other stage
     * @param action stage runnable
     * @param executor target executor
     * @return dependent stage
     */
    public static <T> CompletionStage<Void> stageRunAfterBothAsync(CompletionStage<T> stage,
            CompletionStage<?> other, Runnable action, Executor executor) {
        if (stage instanceof CompletableFuture) {
            return runAfterBothAsync((CompletableFuture<T>) stage, other, action, executor);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.runAfterBothAsync(other, action, executor);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#applyToEitherAsync(CompletionStage, Function)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param stage target stage
     * @param other other stage
     * @param fn stage function
     * @return dependent stage
     */
    public static <T, U> CompletionStage<U> stageApplyToEitherAsync(CompletionStage<T> stage,
            CompletionStage<? extends T> other, Function<? super T, U> fn) {
        if (stage instanceof CompletableFuture) {
            return applyToEitherAsync((CompletableFuture<T>) stage, other, fn);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.applyToEitherAsync(other, fn);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#applyToEitherAsync(CompletionStage, Function, Executor)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param stage target stage
     * @param other other stage
     * @param fn stage function
     * @param executor target executor
     * @return dependent stage
     */
    public static <T, U> CompletionStage<U> stageApplyToEitherAsync(CompletionStage<T> stage,
            CompletionStage<? extends T> other, Function<? super T, U> fn, Executor executor) {
        if (stage instanceof CompletableFuture) {
            return applyToEitherAsync((CompletableFuture<T>) stage, other, fn, executor);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.applyToEitherAsync(other, fn, executor);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#acceptEitherAsync(CompletionStage, Consumer)}.
     *
     * @param <T> input type
     * @param stage target stage
     * @param other other stage
     * @param action stage consumer
     * @return dependent stage
     */
    public static <T> CompletionStage<Void> stageAcceptEitherAsync(CompletionStage<T> stage,
            CompletionStage<? extends T> other, Consumer<? super T> action) {
        if (stage instanceof CompletableFuture) {
            return acceptEitherAsync((CompletableFuture<T>) stage, other, action);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.acceptEitherAsync(other, action);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#acceptEitherAsync(CompletionStage, Consumer, Executor)}.
     *
     * @param <T> input type
     * @param stage target stage
     * @param other other stage
     * @param action stage consumer
     * @param executor target executor
     * @return dependent stage
     */
    public static <T> CompletionStage<Void> stageAcceptEitherAsync(CompletionStage<T> stage,
            CompletionStage<? extends T> other, Consumer<? super T> action, Executor executor) {
        if (stage instanceof CompletableFuture) {
            return acceptEitherAsync((CompletableFuture<T>) stage, other, action, executor);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.acceptEitherAsync(other, action, executor);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#runAfterEitherAsync(CompletionStage, Runnable)}.
     *
     * @param <T> input type
     * @param stage target stage
     * @param other other stage
     * @param action stage runnable
     * @return dependent stage
     */
    public static <T> CompletionStage<Void> stageRunAfterEitherAsync(CompletionStage<T> stage,
            CompletionStage<?> other, Runnable action) {
        if (stage instanceof CompletableFuture) {
            return runAfterEitherAsync((CompletableFuture<T>) stage, other, action);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.runAfterEitherAsync(other, action);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#runAfterEitherAsync(CompletionStage, Runnable, Executor)}.
     *
     * @param <T> input type
     * @param stage target stage
     * @param other other stage
     * @param action stage runnable
     * @param executor target executor
     * @return dependent stage
     */
    public static <T> CompletionStage<Void> stageRunAfterEitherAsync(CompletionStage<T> stage,
            CompletionStage<?> other, Runnable action, Executor executor) {
        if (stage instanceof CompletableFuture) {
            return runAfterEitherAsync((CompletableFuture<T>) stage, other, action, executor);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.runAfterEitherAsync(other, action, executor);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#handleAsync(BiFunction)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param stage target stage
     * @param fn handler function
     * @return dependent stage
     */
    public static <T, U> CompletionStage<U> stageHandleAsync(CompletionStage<T> stage,
            BiFunction<? super T, Throwable, ? extends U> fn) {
        if (stage instanceof CompletableFuture) {
            return handleAsync((CompletableFuture<T>) stage, fn);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.handleAsync(fn);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#handleAsync(BiFunction, Executor)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param stage target stage
     * @param fn handler function
     * @param executor target executor
     * @return dependent stage
     */
    public static <T, U> CompletionStage<U> stageHandleAsync(CompletionStage<T> stage,
            BiFunction<? super T, Throwable, ? extends U> fn, Executor executor) {
        if (stage instanceof CompletableFuture) {
            return handleAsync((CompletableFuture<T>) stage, fn, executor);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.handleAsync(fn, executor);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#whenCompleteAsync(BiConsumer)}.
     *
     * @param <T> result type
     * @param stage target stage
     * @param action completion consumer
     * @return dependent stage
     */
    public static <T> CompletionStage<T> stageWhenCompleteAsync(CompletionStage<T> stage,
            BiConsumer<? super T, ? super Throwable> action) {
        if (stage instanceof CompletableFuture) {
            return whenCompleteAsync((CompletableFuture<T>) stage, action);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.whenCompleteAsync(action);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#whenCompleteAsync(BiConsumer, Executor)}.
     *
     * @param <T> result type
     * @param stage target stage
     * @param action completion consumer
     * @param executor target executor
     * @return dependent stage
     */
    public static <T> CompletionStage<T> stageWhenCompleteAsync(CompletionStage<T> stage,
            BiConsumer<? super T, ? super Throwable> action, Executor executor) {
        if (stage instanceof CompletableFuture) {
            return whenCompleteAsync((CompletableFuture<T>) stage, action, executor);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.whenCompleteAsync(action, executor);
    }

    /**
     * Call-site wrapper for {@code CompletionStage.exceptionallyAsync(Function)}.
     *
     * @param <T> result type
     * @param stage target stage
     * @param fn recovery function
     * @return dependent stage
     */
    @SuppressWarnings("unchecked")
    public static <T> CompletionStage<T> stageExceptionallyAsync(CompletionStage<T> stage,
            Function<Throwable, ? extends T> fn) {
        if (stage instanceof CompletableFuture) {
            return exceptionallyAsync((CompletableFuture<T>) stage, fn);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        try {
            return (CompletionStage<T>) invokeCfReflect(CS_EXCEPTIONALLY_ASYNC_1, stage, fn);
        } catch (Throwable t) {
            throw sneakyThrow(t);
        }
    }

    /**
     * Call-site wrapper for {@code CompletionStage.exceptionallyAsync(Function, Executor)}.
     *
     * @param <T> result type
     * @param stage target stage
     * @param fn recovery function
     * @param executor target executor
     * @return dependent stage
     */
    @SuppressWarnings("unchecked")
    public static <T> CompletionStage<T> stageExceptionallyAsync(CompletionStage<T> stage,
            Function<Throwable, ? extends T> fn, Executor executor) {
        if (stage instanceof CompletableFuture) {
            return exceptionallyAsync((CompletableFuture<T>) stage, fn, executor);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        try {
            return (CompletionStage<T>) invokeCfReflect(CS_EXCEPTIONALLY_ASYNC_2, stage, fn, executor);
        } catch (Throwable t) {
            throw sneakyThrow(t);
        }
    }

    /**
     * Call-site wrapper for {@code CompletionStage.exceptionallyComposeAsync(Function)}.
     *
     * @param <T> result type
     * @param stage target stage
     * @param fn recovery composition function
     * @return dependent stage
     */
    @SuppressWarnings("unchecked")
    public static <T> CompletionStage<T> stageExceptionallyComposeAsync(CompletionStage<T> stage,
            Function<Throwable, ? extends CompletionStage<T>> fn) {
        if (stage instanceof CompletableFuture) {
            return exceptionallyComposeAsync((CompletableFuture<T>) stage, fn);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        try {
            return (CompletionStage<T>) invokeCfReflect(CS_EXCEPTIONALLY_COMPOSE_ASYNC_1, stage, fn);
        } catch (Throwable t) {
            throw sneakyThrow(t);
        }
    }

    /**
     * Call-site wrapper for {@code CompletionStage.exceptionallyComposeAsync(Function, Executor)}.
     *
     * @param <T> result type
     * @param stage target stage
     * @param fn recovery composition function
     * @param executor target executor
     * @return dependent stage
     */
    @SuppressWarnings("unchecked")
    public static <T> CompletionStage<T> stageExceptionallyComposeAsync(CompletionStage<T> stage,
            Function<Throwable, ? extends CompletionStage<T>> fn, Executor executor) {
        if (stage instanceof CompletableFuture) {
            return exceptionallyComposeAsync((CompletableFuture<T>) stage, fn, executor);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        try {
            return (CompletionStage<T>) invokeCfReflect(CS_EXCEPTIONALLY_COMPOSE_ASYNC_2, stage, fn, executor);
        } catch (Throwable t) {
            throw sneakyThrow(t);
        }
    }

    /**
     * Call-site wrapper for {@link CompletionStage#thenApply(Function)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param stage target stage
     * @param fn stage function
     * @return dependent stage
     */
    public static <T, U> CompletionStage<U> stageThenApply(CompletionStage<T> stage,
            Function<? super T, ? extends U> fn) {
        if (stage instanceof CompletableFuture) {
            return thenApply((CompletableFuture<T>) stage, fn);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.thenApply(fn);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#thenAccept(Consumer)}.
     *
     * @param <T> input type
     * @param stage target stage
     * @param action stage consumer
     * @return dependent stage
     */
    public static <T> CompletionStage<Void> stageThenAccept(CompletionStage<T> stage,
            Consumer<? super T> action) {
        if (stage instanceof CompletableFuture) {
            return thenAccept((CompletableFuture<T>) stage, action);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.thenAccept(action);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#thenRun(Runnable)}.
     *
     * @param <T> input type
     * @param stage target stage
     * @param action stage runnable
     * @return dependent stage
     */
    public static <T> CompletionStage<Void> stageThenRun(CompletionStage<T> stage, Runnable action) {
        if (stage instanceof CompletableFuture) {
            return thenRun((CompletableFuture<T>) stage, action);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.thenRun(action);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#thenCompose(Function)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param stage target stage
     * @param fn composition function
     * @return dependent stage
     */
    public static <T, U> CompletionStage<U> stageThenCompose(CompletionStage<T> stage,
            Function<? super T, ? extends CompletionStage<U>> fn) {
        if (stage instanceof CompletableFuture) {
            return thenCompose((CompletableFuture<T>) stage, fn);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.thenCompose(fn);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#thenCombine(CompletionStage, BiFunction)}.
     *
     * @param <T> first input type
     * @param <U> second input type
     * @param <V> output type
     * @param stage target stage
     * @param other other stage
     * @param fn combiner function
     * @return dependent stage
     */
    public static <T, U, V> CompletionStage<V> stageThenCombine(CompletionStage<T> stage,
            CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn) {
        if (stage instanceof CompletableFuture) {
            return thenCombine((CompletableFuture<T>) stage, other, fn);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.thenCombine(other, fn);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#thenAcceptBoth(CompletionStage, BiConsumer)}.
     *
     * @param <T> first input type
     * @param <U> second input type
     * @param stage target stage
     * @param other other stage
     * @param action combiner consumer
     * @return dependent stage
     */
    public static <T, U> CompletionStage<Void> stageThenAcceptBoth(CompletionStage<T> stage,
            CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) {
        if (stage instanceof CompletableFuture) {
            return thenAcceptBoth((CompletableFuture<T>) stage, other, action);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.thenAcceptBoth(other, action);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#runAfterBoth(CompletionStage, Runnable)}.
     *
     * @param <T> first input type
     * @param stage target stage
     * @param other other stage
     * @param action stage runnable
     * @return dependent stage
     */
    public static <T> CompletionStage<Void> stageRunAfterBoth(CompletionStage<T> stage,
            CompletionStage<?> other, Runnable action) {
        if (stage instanceof CompletableFuture) {
            return runAfterBoth((CompletableFuture<T>) stage, other, action);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.runAfterBoth(other, action);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#applyToEither(CompletionStage, Function)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param stage target stage
     * @param other other stage
     * @param fn stage function
     * @return dependent stage
     */
    public static <T, U> CompletionStage<U> stageApplyToEither(CompletionStage<T> stage,
            CompletionStage<? extends T> other, Function<? super T, U> fn) {
        if (stage instanceof CompletableFuture) {
            return applyToEither((CompletableFuture<T>) stage, other, fn);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.applyToEither(other, fn);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#acceptEither(CompletionStage, Consumer)}.
     *
     * @param <T> input type
     * @param stage target stage
     * @param other other stage
     * @param action stage consumer
     * @return dependent stage
     */
    public static <T> CompletionStage<Void> stageAcceptEither(CompletionStage<T> stage,
            CompletionStage<? extends T> other, Consumer<? super T> action) {
        if (stage instanceof CompletableFuture) {
            return acceptEither((CompletableFuture<T>) stage, other, action);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.acceptEither(other, action);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#runAfterEither(CompletionStage, Runnable)}.
     *
     * @param <T> input type
     * @param stage target stage
     * @param other other stage
     * @param action stage runnable
     * @return dependent stage
     */
    public static <T> CompletionStage<Void> stageRunAfterEither(CompletionStage<T> stage,
            CompletionStage<?> other, Runnable action) {
        if (stage instanceof CompletableFuture) {
            return runAfterEither((CompletableFuture<T>) stage, other, action);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.runAfterEither(other, action);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#handle(BiFunction)}.
     *
     * @param <T> input type
     * @param <U> output type
     * @param stage target stage
     * @param fn handler function
     * @return dependent stage
     */
    public static <T, U> CompletionStage<U> stageHandle(CompletionStage<T> stage,
            BiFunction<? super T, Throwable, ? extends U> fn) {
        if (stage instanceof CompletableFuture) {
            return handle((CompletableFuture<T>) stage, fn);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.handle(fn);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#whenComplete(BiConsumer)}.
     *
     * @param <T> result type
     * @param stage target stage
     * @param action completion consumer
     * @return dependent stage
     */
    public static <T> CompletionStage<T> stageWhenComplete(CompletionStage<T> stage,
            BiConsumer<? super T, ? super Throwable> action) {
        if (stage instanceof CompletableFuture) {
            return whenComplete((CompletableFuture<T>) stage, action);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.whenComplete(action);
    }

    /**
     * Call-site wrapper for {@link CompletionStage#exceptionally(Function)}.
     *
     * @param <T> result type
     * @param stage target stage
     * @param fn recovery function
     * @return dependent stage
     */
    public static <T> CompletionStage<T> stageExceptionally(CompletionStage<T> stage,
            Function<Throwable, ? extends T> fn) {
        if (stage instanceof CompletableFuture) {
            return exceptionally((CompletableFuture<T>) stage, fn);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        return stage.exceptionally(fn);
    }

    /**
     * Call-site wrapper for {@code CompletionStage.exceptionallyCompose(Function)}.
     *
     * @param <T> result type
     * @param stage target stage
     * @param fn recovery composition function
     * @return dependent stage
     */
    @SuppressWarnings("unchecked")
    public static <T> CompletionStage<T> stageExceptionallyCompose(CompletionStage<T> stage,
            Function<Throwable, ? extends CompletionStage<T>> fn) {
        if (stage instanceof CompletableFuture) {
            return exceptionallyCompose((CompletableFuture<T>) stage, fn);
        }
        noteUntrackedAsync("untracked-async:completion-stage");
        try {
            return (CompletionStage<T>) invokeCfReflect(CS_EXCEPTIONALLY_COMPOSE, stage, fn);
        } catch (Throwable t) {
            throw sneakyThrow(t);
        }
    }

    private static Object invokeCfReflect(Method method, Object target, Object... args) throws Throwable {
        if (method == null) {
            throw new NoSuchMethodError("CompletableFuture/CompletionStage method unavailable on this JDK");
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

    /**
     * Records an untracked async hand-off occurring inside an active request
     * window or scheduled/listener job run without wrapping the task.
     *
     * @param reason contamination reason label
     */
    public static void noteUntrackedAsync(String reason) {
        noteUntrackedAsync(reason, 0L);
    }

    private static void noteUntrackedAsync(String reason, long extraTailNanos) {
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            return;
        }
        if (reason == null || reason.isEmpty()) {
            return;
        }
        Window window = INSIDE.get();
        if (window != null) {
            window.contaminate(reason, extraTailNanos);
            if (window != current) {
                retainLingering(window, System.nanoTime());
            }
            return;
        }
        if (JobGate.runningAttribution() != null) {
            JobGate.markUntracked(reason, extraTailNanos);
            return;
        }
        String expiredJob = JobGate.expiredRunningName();
        if (expiredJob != null) {
            JobGate.markUntracked("job-tail:" + expiredJob, extraTailNanos);
        }
    }

    /**
     * Checks at runtime whether a receiver of {@code execute(Runnable)} is an
     * untracked {@link Executor}, ignoring non-{@link Executor} helpers,
     * allow-listed same-thread inline executors, and hooked/verified Spring
     * executors.
     *
     * @param receiver target receiver of {@code execute(Runnable)}
     */
    public static void noteIfUntrackedExecutor(Object receiver) {
        if (!(receiver instanceof Executor)) {
            return;
        }
        if (OrderedPools.isInlineExecutor(receiver) || OrderedPools.isHookedAndVerifiedSpringExecutor(receiver)) {
            return;
        }
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            noteExecutorHandOffOutsideWindow(receiver, false);
            return;
        }
        if (INSIDE.get() == null && JobGate.runningAttribution() == null && JobGate.expiredRunningName() == null) {
            noteExecutorHandOffOutsideWindow(receiver, true);
            return;
        }
        noteUntrackedAsync("untracked-executor:" + receiver.getClass().getName(), 0L);
    }

    /**
     * Checks at runtime whether a receiver of {@code submit*} is an untracked
     * {@link ExecutorService}, {@link CompletionService}, or Spring
     * {@code AsyncTaskExecutor}, ignoring non-executor helpers, allow-listed
     * same-thread inline executors, and hooked/verified Spring executors.
     *
     * @param receiver target receiver of {@code submit*}
     */
    public static void noteIfUntrackedSubmit(Object receiver) {
        if (!(receiver instanceof ExecutorService)
                && !(receiver instanceof CompletionService)
                && !OrderedPools.isSpringAsyncTaskExecutor(receiver)) {
            return;
        }
        if (OrderedPools.isInlineExecutor(receiver) || OrderedPools.isHookedAndVerifiedSpringExecutor(receiver)) {
            return;
        }
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            noteExecutorHandOffOutsideWindow(receiver, false);
            return;
        }
        if (INSIDE.get() == null && JobGate.runningAttribution() == null && JobGate.expiredRunningName() == null) {
            noteExecutorHandOffOutsideWindow(receiver, true);
            return;
        }
        noteUntrackedAsync("untracked-executor:" + receiver.getClass().getName(), 0L);
    }

    /**
     * Marks an active request window or job run contaminated if {@code receiver}
     * is a JDK concurrent/blocking queue enqueued from application bytecode.
     *
     * @param receiver target queue receiver
     */
    public static void noteIfBlockingQueue(Object receiver) {
        if (!(receiver instanceof java.util.concurrent.BlockingQueue)
                && !(receiver instanceof java.util.concurrent.ConcurrentLinkedQueue)
                && !(receiver instanceof java.util.concurrent.ConcurrentLinkedDeque)) {
            return;
        }
        noteUntrackedAsync("untracked-async:blocking-queue", 0L);
    }

    /**
     * Called from runtime-latch entry hooks on framework/library async methods
     * (such as Caffeine {@code CacheLoader.asyncReload}/{@code LocalAsyncCache}
     * and Spring {@code ConcurrentMapCache.retrieve}): permanently latches
     * {@code asyncExecutors=false} on first invocation and contaminates any
     * currently active request window or job run.
     *
     * @param reason human-readable label for the async dispatch entry
     */
    public static void noteRuntimeUntrackedDispatch(String reason) {
        String label = (reason == null || reason.isEmpty()) ? "library-async" : reason;
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            KeployDedupAgent.noteObservedUntrackedAsyncLibrary(label, false);
            return;
        }
        KeployDedupAgent.noteObservedUntrackedAsyncLibrary(label, true);
        if (INSIDE.get() != null || JobGate.runningAttribution() != null || JobGate.expiredRunningName() != null) {
            noteUntrackedAsync("untracked-async:" + label, 0L);
        }
    }

    /**
     * An executor hand-off made with no request window and no job open (at startup, from a
     * {@code @PostConstruct}, or from any thread no window owns) can start a long-lived worker
     * that later runs work a request hands it through shared state (an {@code ArrayDeque} under a
     * lock, a volatile field, wait/notify): fail closed exactly as {@code Thread.start} does.
     * Same-thread inline executors start no worker and are left alone.
     */
    private static void noteExecutorHandOffOutsideWindow(Object executor, boolean isArmed) {
        if (executor != null && OrderedPools.isInlineExecutor(executor)) {
            return;
        }
        noteBackgroundWorkerOutsideWindow("untracked-async:executor-outside-window", isArmed);
    }

    private static void noteBackgroundWorkerOutsideWindow(String reason, boolean isArmed) {
        String label = (reason == null || reason.isEmpty()) ? "background-worker" : reason;
        KeployDedupAgent.noteObservedUntrackedAsyncLibrary(
                "app background worker/scheduler started outside request window (" + label + ")", isArmed);
    }

    private static void recordUntrackedMethodRef(String reason, String target) {
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            KeployDedupAgent.noteObservedUntrackedMethodRef(target, false);
            return;
        }
        KeployDedupAgent.noteObservedUntrackedMethodRef(target, true);
        if (INSIDE.get() != null || JobGate.runningAttribution() != null || JobGate.expiredRunningName() != null) {
            noteUntrackedAsync(reason, 0L);
        }
    }

    /**
     * Checks at runtime whether a bound {@code execute(Runnable)} method
     * reference receiver is an untracked {@link Executor}, ignoring
     * non-{@link Executor} helpers, allow-listed same-thread inline executors,
     * and hooked/verified Spring executors. Permanently latches
     * {@code asyncExecutors=false} on any capture of a real untracked executor
     * reference (inside or outside a window, because the captured lambda may be
     * stored and invoked in later windows), and contaminates the active window
     * or tracked job when captured inside one.
     *
     * @param receiver captured receiver of {@code execute(Runnable)} method reference
     */
    public static void noteIfUntrackedExecutorMethodRef(Object receiver) {
        if (!(receiver instanceof Executor)) {
            return;
        }
        if (OrderedPools.isInlineExecutor(receiver) || OrderedPools.isHookedAndVerifiedSpringExecutor(receiver)) {
            return;
        }
        String name = receiver.getClass().getName();
        recordUntrackedMethodRef("untracked-executor:" + name, name + "::execute");
    }

    /**
     * Checks at runtime whether a bound {@code submit*} method reference
     * receiver is an untracked {@link ExecutorService},
     * {@link CompletionService}, or Spring {@code AsyncTaskExecutor}, ignoring
     * non-executor helpers, allow-listed same-thread inline executors, and
     * hooked/verified Spring executors. Permanently latches
     * {@code asyncExecutors=false} on any capture of a real untracked executor
     * reference and contaminates the active window or tracked job when captured
     * inside one.
     *
     * @param receiver captured receiver of {@code submit*} method reference
     */
    public static void noteIfUntrackedSubmitMethodRef(Object receiver) {
        if (!(receiver instanceof ExecutorService)
                && !(receiver instanceof CompletionService)
                && !OrderedPools.isSpringAsyncTaskExecutor(receiver)) {
            return;
        }
        if (OrderedPools.isInlineExecutor(receiver) || OrderedPools.isHookedAndVerifiedSpringExecutor(receiver)) {
            return;
        }
        String name = receiver.getClass().getName();
        recordUntrackedMethodRef("untracked-executor:" + name, name + "::submit");
    }

    /**
     * Checks at runtime whether the owner class of an unbound
     * {@code execute(Runnable)} method reference is an untracked
     * {@link Executor}, ignoring non-{@link Executor} types and allow-listed
     * same-thread inline executors. Permanently latches
     * {@code asyncExecutors=false} on any capture and contaminates the active
     * window or tracked job when captured inside one.
     *
     * @param owner declaring owner class of the method reference
     */
    public static void noteIfUntrackedExecutorClass(Class<?> owner) {
        if (owner == null || !Executor.class.isAssignableFrom(owner)) {
            return;
        }
        if (OrderedPools.isInlineExecutorClass(owner)) {
            return;
        }
        String name = owner.getName();
        recordUntrackedMethodRef("untracked-executor:" + name, name + "::execute");
    }

    /**
     * Checks at runtime whether the owner class of an unbound {@code submit*}
     * method reference is an untracked {@link ExecutorService},
     * {@link CompletionService}, or Spring {@code AsyncTaskExecutor}, ignoring
     * non-executor types and allow-listed same-thread inline executors.
     * Permanently latches {@code asyncExecutors=false} on any capture and
     * contaminates the active window or tracked job when captured inside one.
     *
     * @param owner declaring owner class of the method reference
     */
    public static void noteIfUntrackedSubmitClass(Class<?> owner) {
        if (owner == null
                || (!ExecutorService.class.isAssignableFrom(owner)
                        && !CompletionService.class.isAssignableFrom(owner)
                        && !OrderedPools.isSpringAsyncTaskExecutorClass(owner))) {
            return;
        }
        if (OrderedPools.isInlineExecutorClass(owner)) {
            return;
        }
        String name = owner.getName();
        recordUntrackedMethodRef("untracked-executor:" + name, name + "::submit");
    }

    /**
     * Records an untracked async method reference capture (e.g.
     * {@code Thread::start}, {@code CompletableFuture::runAsync},
     * {@code ScheduledExecutorService::schedule}). Permanently latches
     * {@code asyncExecutors=false} on any capture and contaminates the active
     * window or tracked job when captured inside one.
     *
     * @param reason contamination reason label describing the method reference
     */
    public static void noteUntrackedAsyncMethodRef(String reason) {
        if (reason == null || reason.isEmpty()) {
            return;
        }
        String api = reason.startsWith("untracked-async:method-ref:")
                ? reason.substring("untracked-async:method-ref:".length())
                : reason;
        recordUntrackedMethodRef(reason, api);
    }

    /**
     * Called from Spring's {@code SimpleApplicationEventMulticaster.setTaskExecutor}
     * hook. Allows {@code null}, allow-listed inline executors, and hooked
     * Spring executors; for any other executor (such as a plain JDK
     * {@code ThreadPoolExecutor}), latches {@code asyncExecutors=false} so
     * untracked Spring-to-JDK-pool event hand-offs never report honest.
     *
     * @param executor executor configured on the multicaster
     */
    public static void noteSpringMulticasterTaskExecutor(Object executor) {
        if (executor == null) {
            return;
        }
        if (OrderedPools.isInlineExecutor(executor)
                || OrderedPools.isHookedSpringExecutorOrPendingInit(executor)) {
            return;
        }
        String name = executor.getClass().getName();
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            KeployDedupAgent.noteObservedUntrackedAsyncLibrary(
                    "Spring SimpleApplicationEventMulticaster with " + name, false);
            return;
        }
        KeployDedupAgent.noteObservedUntrackedAsyncLibrary(
                "Spring SimpleApplicationEventMulticaster with " + name, true);
        if (INSIDE.get() != null || JobGate.runningAttribution() != null || JobGate.expiredRunningName() != null) {
            noteUntrackedAsync("untracked-executor:" + name, 0L);
        }
    }

    /**
     * Records cancellation of a {@link Timer} with timestamp so tasks that had
     * not started stop driving tail contamination immediately, while any
     * already-running body stays covered for {@code untrackedTailWindowNanos}.
     *
     * @param timer cancelled timer
     */
    public static void noteTimerCancelled(Timer timer) {
        if (timer == null || !armed) {
            return;
        }
        drainDeadTimerKeys();
        CANCELLED_TIMERS.putIfAbsent(new WeakIdentityKey(timer, DEAD_TIMER_KEYS), Long.valueOf(System.nanoTime()));
    }

    /**
     * Records the result of {@link TimerTask#cancel()} when {@code target} is a
     * {@link TimerTask}: {@code cancelled == true} means the task was still
     * scheduled (not yet fired for a one-shot task), whereas {@code false}
     * means it had already fired (and its {@code run()} body may still be
     * executing, so tail protection is kept for {@code untrackedTailWindowNanos}).
     *
     * @param target receiver of {@code cancel()}
     * @param cancelled return value of {@code cancel()}
     */
    public static void noteTimerTaskCancelResult(Object target, boolean cancelled) {
        if (!(target instanceof TimerTask) || !armed) {
            return;
        }
        drainDeadTimerTaskKeys();
        CANCELLED_TIMER_TASKS.putIfAbsent(new WeakIdentityKey(target, DEAD_TIMER_TASK_KEYS),
                new TimerCancelInfo(cancelled, System.nanoTime()));
    }

    private static void drainDeadTimerTaskKeys() {
        for (Reference<?> ref; (ref = DEAD_TIMER_TASK_KEYS.poll()) != null; ) {
            CANCELLED_TIMER_TASKS.remove(ref);
        }
    }

    private static void drainDeadTimerKeys() {
        for (Reference<?> ref; (ref = DEAD_TIMER_KEYS.poll()) != null; ) {
            CANCELLED_TIMERS.remove(ref);
        }
    }

    private static TimerCancelInfo timerTaskCancelInfo(TimerTask task) {
        if (task == null || CANCELLED_TIMER_TASKS.isEmpty()) {
            return null;
        }
        drainDeadTimerTaskKeys();
        return CANCELLED_TIMER_TASKS.get(new LookupKey(task));
    }

    private static Long timerCancelledAtNanos(Timer timer) {
        if (timer == null || CANCELLED_TIMERS.isEmpty()) {
            return null;
        }
        drainDeadTimerKeys();
        return CANCELLED_TIMERS.get(new LookupKey(timer));
    }

    private static long safeScheduledExecutionTime(TimerTask task) {
        if (task == null) {
            return 0L;
        }
        try {
            return task.scheduledExecutionTime();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * Checks whether {@code target} is a {@link Thread} instance before a
     * non-{@code java.lang.Thread} {@code start()} call, flags an untracked
     * thread start if so, and weakly tracks the thread's liveness so tail
     * contamination persists only while the thread remains alive.
     *
     * @param target receiver of {@code start()}
     */
    public static void noteIfThread(Object target) {
        if (!(target instanceof Thread)) {
            return;
        }
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            noteBackgroundWorkerOutsideWindow("untracked-async:thread-start", false);
            return;
        }
        noteTrackedUntrackedAsync("untracked-async:thread-start", target, false);
    }

    /**
     * Weakly tracks a newly started {@link Thread} (e.g., returned by JDK 21
     * {@code Thread.startVirtualThread} or {@code Thread.Builder.start}) so
     * subsequent windows remain marked with tail contamination while the thread
     * is still alive.
     *
     * @param thread returned thread instance
     */
    public static void trackUntrackedThread(Object thread) {
        trackUntrackedThread(thread, null);
    }

    /**
     * Flags and weakly tracks a newly started {@link Thread} so subsequent
     * windows remain marked with tail contamination only while the thread stays
     * alive (no blind 2 s tail once dead).
     *
     * @param thread returned thread instance
     * @param reason contamination label for the originating window
     */
    public static void trackUntrackedThread(Object thread, String reason) {
        if (!armed) {
            if (thread instanceof Thread) {
                noteUnarmedHandOffIfInFlight();
                noteBackgroundWorkerOutsideWindow(reason != null ? reason : "untracked-async:thread-start", false);
            }
            return;
        }
        if (thread instanceof Thread) {
            noteTrackedUntrackedAsync(reason, thread, true);
        } else if (reason != null && !reason.isEmpty()) {
            noteUntrackedAsync(reason, 0L);
        }
    }

    /**
     * Weakly tracks a {@link Future} (e.g., returned by Spring
     * {@code TaskScheduler.schedule*}) so subsequent windows remain marked with
     * tail contamination until the scheduled future completes or is cancelled.
     *
     * @param future returned future instance
     */
    public static void trackUntrackedFuture(Object future) {
        trackUntrackedFuture(future, null);
    }

    /**
     * Flags and weakly tracks a returned {@link Future} so subsequent windows
     * remain marked with tail contamination only while the future is not done
     * or cancelled (no blind 2 s or delay-based tail once done/cancelled).
     *
     * @param future returned future instance
     * @param reason contamination label for the originating window
     */
    public static void trackUntrackedFuture(Object future, String reason) {
        if (!armed) {
            if (future instanceof Future) {
                noteUnarmedHandOffIfInFlight();
                noteBackgroundWorkerOutsideWindow(reason != null ? reason : "untracked-async:schedule", false);
            }
            return;
        }
        if (future instanceof Future) {
            noteTrackedUntrackedAsync(reason, future, true);
        } else if (reason != null && !reason.isEmpty()) {
            noteUntrackedAsync(reason, 0L);
        }
    }

    private static void noteTrackedUntrackedAsync(String reason, Object target, boolean alreadyStarted) {
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            return;
        }
        if (target == null) {
            if (reason != null && !reason.isEmpty()) {
                noteUntrackedAsync(reason, 0L);
            }
            return;
        }
        long now = System.nanoTime();
        noteTrackedHandle(reason, new UntrackedHandle(target, alreadyStarted, now), now);
    }

    private static void noteTrackedTimerTask(Timer timer, TimerTask task, long delayMillis, long periodMillis,
            boolean periodic) {
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            return;
        }
        if (task == null) {
            noteUntrackedAsync("untracked-async:timer", 0L);
            return;
        }
        long now = System.nanoTime();
        UntrackedHandle handle = UntrackedHandle.forTimerTask(timer, task, delayMillis, periodMillis, periodic, now);
        noteTrackedHandle("untracked-async:timer", handle, now);
    }

    private static void noteTrackedHandle(String reason, UntrackedHandle handle, long now) {
        Window window = INSIDE.get();
        if (window != null) {
            if (reason != null && !reason.isEmpty()) {
                window.contaminateTracked(reason);
            }
            window.addUntrackedHandle(handle, now);
            if (window != current) {
                retainLingering(window, now);
            }
            return;
        }
        String effectiveReason = reason;
        if (JobGate.runningAttribution() != null) {
            if (effectiveReason != null && !effectiveReason.isEmpty()) {
                JobGate.markUntrackedWindowOnly(effectiveReason);
            }
            externalUntrackedTailReason = effectiveReason == null || effectiveReason.isEmpty() ? "job" : effectiveReason;
            if (!addHandleToQueue(EXTERNAL_UNTRACKED_HANDLES, handle, now)) {
                KeployDedupAgent.noteObservedUntrackedAsyncLibrary(
                        "untracked async handle queue overflow (>64 live handles)", true);
                noteExternalUntrackedTail(effectiveReason, maxLingeringNanos);
            }
            return;
        }
        String expiredJob = JobGate.expiredRunningName();
        if (expiredJob != null) {
            String jobReason = "job-tail:" + expiredJob;
            JobGate.markUntrackedWindowOnly(jobReason);
            externalUntrackedTailReason = jobReason;
            if (!addHandleToQueue(EXTERNAL_UNTRACKED_HANDLES, handle, now)) {
                KeployDedupAgent.noteObservedUntrackedAsyncLibrary(
                        "untracked async handle queue overflow (>64 live handles)", true);
                noteExternalUntrackedTail(jobReason, maxLingeringNanos);
            }
            return;
        }
        // Rewritten app code started a Thread, ScheduledFuture, or TimerTask
        // completely outside any request window or tracked job (e.g. a long-lived
        // background worker or outbox poller). Latch asyncExecutors=false so
        // later requests handing work to it through shared state cannot be
        // falsely treated as synchronous.
        noteBackgroundWorkerOutsideWindow(effectiveReason, true);
    }

    private static boolean addHandleToQueue(ConcurrentLinkedQueue<UntrackedHandle> queue, UntrackedHandle handle,
            long now) {
        if (queue.size() >= MAX_UNTRACKED_HANDLES) {
            sweepUntrackedHandles(queue, now);
            if (queue.size() >= MAX_UNTRACKED_HANDLES) {
                return false;
            }
        }
        queue.add(handle);
        return true;
    }

    private static boolean sweepUntrackedHandles(ConcurrentLinkedQueue<UntrackedHandle> queue, long now) {
        if (queue == null || queue.isEmpty()) {
            return false;
        }
        boolean anyAlive = false;
        for (UntrackedHandle h : queue) {
            if (h.isActive(now)) {
                anyAlive = true;
            } else {
                queue.remove(h);
            }
        }
        return anyAlive;
    }

    private static Runnable wrapScheduledContext(ScheduledExecutorService executor, Runnable command) {
        if (!armed || command == null || command instanceof Comparable || command instanceof ScheduledContextRunnable) {
            return command;
        }
        Window window = INSIDE.get();
        JobGate.Attribution job = currentOrExpiredJob(window);
        if (window == null && job == null) {
            return command;
        }
        if (OrderedPools.orders(executor)) {
            return command;
        }
        return new ScheduledContextRunnable(window, job, command);
    }

    private static <V> Callable<V> wrapScheduledContext(ScheduledExecutorService executor, Callable<V> callable) {
        if (!armed || callable == null || callable instanceof Comparable
                || callable instanceof ScheduledContextCallable) {
            return callable;
        }
        Window window = INSIDE.get();
        JobGate.Attribution job = currentOrExpiredJob(window);
        if (window == null && job == null) {
            return callable;
        }
        if (OrderedPools.orders(executor)) {
            return callable;
        }
        return new ScheduledContextCallable<>(window, job, callable);
    }

    /**
     * Pass-through call-site wrapper for {@link ScheduledExecutorService#schedule(Runnable, long, TimeUnit)}.
     *
     * @param executor target scheduled executor
     * @param command task to schedule
     * @param delay execution delay
     * @param unit time unit of delay
     * @return scheduled future
     */
    public static ScheduledFuture<?> schedule(ScheduledExecutorService executor, Runnable command,
            long delay, TimeUnit unit) {
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            noteBackgroundWorkerOutsideWindow("untracked-async:schedule", false);
            return executor.schedule(command, delay, unit);
        }
        ScheduledFuture<?> sf = executor.schedule(wrapScheduledContext(executor, command), delay, unit);
        noteTrackedUntrackedAsync("untracked-async:schedule", sf, true);
        return sf;
    }

    /**
     * Pass-through call-site wrapper for {@link ScheduledExecutorService#schedule(Callable, long, TimeUnit)}.
     *
     * @param <V> result type
     * @param executor target scheduled executor
     * @param callable task to schedule
     * @param delay execution delay
     * @param unit time unit of delay
     * @return scheduled future
     */
    public static <V> ScheduledFuture<V> schedule(ScheduledExecutorService executor, Callable<V> callable,
            long delay, TimeUnit unit) {
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            noteBackgroundWorkerOutsideWindow("untracked-async:schedule", false);
            return executor.schedule(callable, delay, unit);
        }
        ScheduledFuture<V> sf = executor.schedule(wrapScheduledContext(executor, callable), delay, unit);
        noteTrackedUntrackedAsync("untracked-async:schedule", sf, true);
        return sf;
    }

    /**
     * Pass-through call-site wrapper for
     * {@link ScheduledExecutorService#scheduleAtFixedRate(Runnable, long, long, TimeUnit)}.
     *
     * @param executor target scheduled executor
     * @param command task to schedule
     * @param initialDelay initial delay
     * @param period execution period
     * @param unit time unit
     * @return scheduled future
     */
    public static ScheduledFuture<?> scheduleAtFixedRate(ScheduledExecutorService executor, Runnable command,
            long initialDelay, long period, TimeUnit unit) {
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            noteBackgroundWorkerOutsideWindow("untracked-async:schedule", false);
            return executor.scheduleAtFixedRate(command, initialDelay, period, unit);
        }
        ScheduledFuture<?> sf = executor.scheduleAtFixedRate(
                wrapScheduledContext(executor, command), initialDelay, period, unit);
        noteTrackedUntrackedAsync("untracked-async:schedule", sf, true);
        return sf;
    }

    /**
     * Pass-through call-site wrapper for
     * {@link ScheduledExecutorService#scheduleWithFixedDelay(Runnable, long, long, TimeUnit)}.
     *
     * @param executor target scheduled executor
     * @param command task to schedule
     * @param initialDelay initial delay
     * @param delay delay between runs
     * @param unit time unit
     * @return scheduled future
     */
    public static ScheduledFuture<?> scheduleWithFixedDelay(ScheduledExecutorService executor, Runnable command,
            long initialDelay, long delay, TimeUnit unit) {
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            noteBackgroundWorkerOutsideWindow("untracked-async:schedule", false);
            return executor.scheduleWithFixedDelay(command, initialDelay, delay, unit);
        }
        ScheduledFuture<?> sf = executor.scheduleWithFixedDelay(
                wrapScheduledContext(executor, command), initialDelay, delay, unit);
        noteTrackedUntrackedAsync("untracked-async:schedule", sf, true);
        return sf;
    }

    private static long dateDelayMillis(Date time) {
        if (time == null) {
            return 0L;
        }
        long diff = time.getTime() - System.currentTimeMillis();
        return diff > 0L ? diff : 0L;
    }

    /**
     * Pass-through call-site wrapper for {@link Timer#schedule(TimerTask, long)}.
     *
     * @param timer target timer
     * @param task timer task
     * @param delay execution delay in milliseconds
     */
    public static void timerSchedule(Timer timer, TimerTask task, long delay) {
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            noteBackgroundWorkerOutsideWindow("untracked-async:timer", false);
            timer.schedule(task, delay);
            return;
        }
        timer.schedule(task, delay);
        noteTrackedTimerTask(timer, task, delay, 0L, false);
    }

    /**
     * Pass-through call-site wrapper for {@link Timer#schedule(TimerTask, Date)}.
     *
     * @param timer target timer
     * @param task timer task
     * @param time execution time
     */
    public static void timerSchedule(Timer timer, TimerTask task, Date time) {
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            noteBackgroundWorkerOutsideWindow("untracked-async:timer", false);
            timer.schedule(task, time);
            return;
        }
        long delay = dateDelayMillis(time);
        timer.schedule(task, time);
        noteTrackedTimerTask(timer, task, delay, 0L, false);
    }

    /**
     * Pass-through call-site wrapper for {@link Timer#schedule(TimerTask, long, long)}.
     *
     * @param timer target timer
     * @param task timer task
     * @param delay initial delay in milliseconds
     * @param period period in milliseconds
     */
    public static void timerSchedule(Timer timer, TimerTask task, long delay, long period) {
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            noteBackgroundWorkerOutsideWindow("untracked-async:timer", false);
            timer.schedule(task, delay, period);
            return;
        }
        timer.schedule(task, delay, period);
        noteTrackedTimerTask(timer, task, delay, period, true);
    }

    /**
     * Pass-through call-site wrapper for {@link Timer#schedule(TimerTask, Date, long)}.
     *
     * @param timer target timer
     * @param task timer task
     * @param firstTime first execution time
     * @param period period in milliseconds
     */
    public static void timerSchedule(Timer timer, TimerTask task, Date firstTime, long period) {
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            noteBackgroundWorkerOutsideWindow("untracked-async:timer", false);
            timer.schedule(task, firstTime, period);
            return;
        }
        long delay = dateDelayMillis(firstTime);
        timer.schedule(task, firstTime, period);
        noteTrackedTimerTask(timer, task, delay, period, true);
    }

    /**
     * Pass-through call-site wrapper for {@link Timer#scheduleAtFixedRate(TimerTask, long, long)}.
     *
     * @param timer target timer
     * @param task timer task
     * @param delay initial delay in milliseconds
     * @param period period in milliseconds
     */
    public static void timerScheduleAtFixedRate(Timer timer, TimerTask task, long delay, long period) {
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            noteBackgroundWorkerOutsideWindow("untracked-async:timer", false);
            timer.scheduleAtFixedRate(task, delay, period);
            return;
        }
        timer.scheduleAtFixedRate(task, delay, period);
        noteTrackedTimerTask(timer, task, delay, period, true);
    }

    /**
     * Pass-through call-site wrapper for {@link Timer#scheduleAtFixedRate(TimerTask, Date, long)}.
     *
     * @param timer target timer
     * @param task timer task
     * @param firstTime first execution time
     * @param period period in milliseconds
     */
    public static void timerScheduleAtFixedRate(Timer timer, TimerTask task, Date firstTime, long period) {
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            noteBackgroundWorkerOutsideWindow("untracked-async:timer", false);
            timer.scheduleAtFixedRate(task, firstTime, period);
            return;
        }
        long delay = dateDelayMillis(firstTime);
        timer.scheduleAtFixedRate(task, firstTime, period);
        noteTrackedTimerTask(timer, task, delay, period, true);
    }

    /**
     * Pass-through call-site wrapper for {@link ExecutorService#invokeAny(Collection)}.
     *
     * @param <T> result type
     * @param executor target executor service
     * @param tasks tasks to invoke
     * @return result of one completed task
     * @throws InterruptedException if interrupted while waiting
     * @throws ExecutionException if no task completes successfully
     */
    public static <T> T invokeAny(ExecutorService executor, Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        noteUntrackedAsync("untracked-async:invokeAny");
        return executor.invokeAny(tasks);
    }

    /**
     * Pass-through call-site wrapper for
     * {@link ExecutorService#invokeAny(Collection, long, TimeUnit)}.
     *
     * @param <T> result type
     * @param executor target executor service
     * @param tasks tasks to invoke
     * @param timeout maximum wait time
     * @param unit time unit of timeout
     * @return result of one completed task
     * @throws InterruptedException if interrupted while waiting
     * @throws ExecutionException if no task completes successfully
     * @throws TimeoutException if timeout elapses before any task completes
     */
    public static <T> T invokeAny(ExecutorService executor, Collection<? extends Callable<T>> tasks,
            long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        noteUntrackedAsync("untracked-async:invokeAny");
        return executor.invokeAny(tasks, timeout, unit);
    }

    /**
     * Pass-through call-site wrapper for {@link Thread#start()}.
     *
     * @param thread thread to start
     */
    public static void startThread(Thread thread) {
        if (!armed) {
            noteUnarmedHandOffIfInFlight();
            noteBackgroundWorkerOutsideWindow("untracked-async:thread-start", false);
            thread.start();
            return;
        }
        thread.start();
        noteTrackedUntrackedAsync("untracked-async:thread-start", thread, true);
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
            registerStageHand(hand);
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
    static boolean open() {
        return open(null, null);
    }

    // open(testId, prevActiveTestId) opens a fresh test window, records
    // armed-mid-flight while any unarmed request/job remains in flight (or
    // once after unarmed traffic occurred prior to arming), and marks the new
    // window contaminated ("tail:<prevTestId>") if the previous test was cut,
    // unclosed, had a blind untracked async hand-off, or any prior window still
    // has running > 0 or live tracked handles. Returns true if any tail:,
    // job-tail:, or armed-mid-flight reason was added to the newly opened window.
    static boolean open(String testId, String prevActiveTestId) {
        arm();
        drainDeadHands();
        long now = System.nanoTime();
        Window next = new Window(testId);
        if (UNARMED_IN_FLIGHT.sum() > 0) {
            pendingArmedMidFlight = false;
            ARMED_MID_FLIGHT_CONSUMED.set(true);
            next.contaminate("armed-mid-flight");
        } else if (lastUnarmedHandOffNanos != 0L && (now - lastUnarmedHandOffNanos) < untrackedTailWindowNanos) {
            pendingArmedMidFlight = false;
            ARMED_MID_FLIGHT_CONSUMED.set(true);
            next.contaminate("armed-mid-flight");
        } else if ((pendingArmedMidFlight || unarmedEntered)
                && ARMED_MID_FLIGHT_CONSUMED.compareAndSet(false, true)) {
            pendingArmedMidFlight = false;
            next.contaminate("armed-mid-flight");
        }
        // Swap current BEFORE inspecting prev so no request can enter prev
        // after we check prev.running.
        Window prev = current;
        current = next;
        if (prev != null) {
            prev.sweepCancelledFutures();
            boolean prevRunning = prev.running.get() > 0;
            boolean prevAliveHandles = prev.hasAliveUntrackedHandles(now);
            boolean prevBlindUntracked = prev.hasBlindUntrackedAsync;
            if (prevRunning || prevAliveHandles || prevBlindUntracked || prev.untrackedHandlesOverflowed) {
                if (prev.testId == null && prevActiveTestId != null && !prevActiveTestId.isEmpty()) {
                    prev.fallbackId = prevActiveTestId;
                }
                if (prevBlindUntracked && prev.untrackedTailExpiresAtNanos == 0L) {
                    prev.extendUntrackedTail(now, 0L);
                }
                retainLingering(prev, now);
            }
        }
        if (prevActiveTestId != null && !prevActiveTestId.isEmpty()) {
            next.contaminate("tail:" + prevActiveTestId);
        }
        if (externalUntrackedTailPending
                || sweepUntrackedHandles(EXTERNAL_UNTRACKED_HANDLES, now)
                || (externalUntrackedTailExpiresAtNanos != 0L
                        && (now - externalUntrackedTailExpiresAtNanos) < 0L)) {
            String extReason = externalUntrackedTailReason;
            next.contaminate("tail:" + (extReason != null && !extReason.isEmpty() ? extReason : "job"));
            externalUntrackedTailPending = false;
            if (externalUntrackedTailExpiresAtNanos != 0L && (now - externalUntrackedTailExpiresAtNanos) >= 0L) {
                externalUntrackedTailExpiresAtNanos = 0L;
            }
        }
        for (Window lingering : LINGERING) {
            lingering.sweepCancelledFutures();
            boolean lingeringExpired = lingering.lingeringSinceNanos != 0L
                    && (now - lingering.lingeringSinceNanos) >= maxLingeringNanos;
            boolean aliveHandles = lingering.hasAliveUntrackedHandles(now);
            // Actively executing work (executing > 0) and live untracked
            // threads/futures/TimerTasks NEVER expire under maxLingeringNanos;
            // only idle uncompleted permits (running > 0 && executing == 0) and
            // overflowed handle caps are bounded by maxLingeringNanos.
            boolean stillRunning = lingering.executing.get() > 0
                    || aliveHandles
                    || (!lingeringExpired && (lingering.running.get() > 0 || lingering.untrackedHandlesOverflowed));
            boolean reportCut = lingering.wasCut && !lingering.cutReportedOnce;
            boolean untrackedActive = lingering.hasBlindUntrackedAsync
                    && (!lingering.untrackedTailReportedOnce
                            || (now - lingering.untrackedTailExpiresAtNanos) < 0L);
            if (stillRunning || reportCut || untrackedActive) {
                next.contaminate("tail:" + lingering.label());
                if (reportCut) {
                    lingering.cutReportedOnce = true;
                }
                if (lingering.hasBlindUntrackedAsync) {
                    lingering.untrackedTailReportedOnce = true;
                }
            }
            boolean cutDone = !lingering.wasCut || lingering.cutReportedOnce;
            boolean untrackedDone = !lingering.hasBlindUntrackedAsync
                    || (lingering.untrackedTailReportedOnce
                            && (now - lingering.untrackedTailExpiresAtNanos) >= 0L);
            if (!stillRunning && cutDone && untrackedDone) {
                LINGERING.remove(lingering);
                // Dekker re-check after remove closes the TOCTOU window with
                // concurrent Hand.enter / addUntrackedHandle / noteUntrackedAsync.
                if (lingering.executing.get() > 0
                        || lingering.hasAliveUntrackedHandles(now)
                        || (lingering.hasBlindUntrackedAsync
                                && (now - lingering.untrackedTailExpiresAtNanos) < 0L)) {
                    LINGERING.add(lingering);
                }
            }
        }
        return next.hasOpeningTail();
    }

    private static void retainLingering(Window window, long now) {
        if (window.lingeringSinceNanos == 0L) {
            window.lingeringSinceNanos = now;
        }
        if (LINGERING.contains(window)) {
            return;
        }
        if (LINGERING.size() >= MAX_LINGERING) {
            for (Window existing : LINGERING) {
                existing.sweepCancelledFutures();
                boolean lingeringExpired = existing.lingeringSinceNanos != 0L
                        && (now - existing.lingeringSinceNanos) >= maxLingeringNanos;
                boolean aliveHandles = existing.hasAliveUntrackedHandles(now);
                boolean stillRunning = existing.executing.get() > 0
                        || aliveHandles
                        || (!lingeringExpired && (existing.running.get() > 0 || existing.untrackedHandlesOverflowed));
                boolean cutDone = !existing.wasCut || existing.cutReportedOnce;
                boolean untrackedDone = !existing.hasBlindUntrackedAsync
                        || (existing.untrackedTailReportedOnce
                                && (now - existing.untrackedTailExpiresAtNanos) >= 0L);
                if (!stillRunning && cutDone && untrackedDone) {
                    LINGERING.remove(existing);
                    if (existing.executing.get() > 0
                            || existing.hasAliveUntrackedHandles(now)
                            || (existing.hasBlindUntrackedAsync
                                    && (now - existing.untrackedTailExpiresAtNanos) < 0L)) {
                        LINGERING.add(existing);
                    }
                }
            }
            // Strictly bound memory if 64+ windows are already stuck lingering;
            // those 64 entries already mark every subsequent window "tail:...".
            if (LINGERING.size() >= MAX_LINGERING) {
                return;
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

    private static boolean hasActiveLingering(long now) {
        if (UNARMED_IN_FLIGHT.sum() > 0) {
            return true;
        }
        if (lastUnarmedHandOffNanos != 0L && (now - lastUnarmedHandOffNanos) < untrackedTailWindowNanos) {
            return true;
        }
        if (externalUntrackedTailPending
                || sweepUntrackedHandles(EXTERNAL_UNTRACKED_HANDLES, now)
                || (externalUntrackedTailExpiresAtNanos != 0L
                        && (now - externalUntrackedTailExpiresAtNanos) < 0L)) {
            return true;
        }
        for (Window lingering : LINGERING) {
            lingering.sweepCancelledFutures();
            boolean lingeringExpired = lingering.lingeringSinceNanos != 0L
                    && (now - lingering.lingeringSinceNanos) >= maxLingeringNanos;
            boolean aliveHandles = lingering.hasAliveUntrackedHandles(now);
            boolean stillRunning = lingering.executing.get() > 0
                    || aliveHandles
                    || (!lingeringExpired && (lingering.running.get() > 0 || lingering.untrackedHandlesOverflowed));
            boolean untrackedActive = lingering.hasBlindUntrackedAsync
                    && (!lingering.untrackedTailReportedOnce
                            || (now - lingering.untrackedTailExpiresAtNanos) < 0L);
            if (stillRunning || untrackedActive) {
                return true;
            }
        }
        return false;
    }

    // awaitWindow closes the current window, optionally waits up to
    // timeoutNanos for its requests/tasks to finish, retains the window in
    // LINGERING if cut, still running, carrying a blind untracked async
    // hand-off, or holding alive tracked handles, and returns its outcome.
    static WindowOutcome awaitWindow(long timeoutNanos, boolean wait) {
        drainDeadHands();
        Window window = current;
        current = new Window(null);
        window.sweepCancelledFutures();
        int initialRunning = window.running.get();
        if (!wait) {
            long now = System.nanoTime();
            if (window.hasBlindUntrackedAsync) {
                window.extendUntrackedTail(now, 0L);
            }
            if (initialRunning > 0 || window.hasBlindUntrackedAsync
                    || window.hasAliveUntrackedHandles(now) || window.untrackedHandlesOverflowed) {
                retainLingering(window, now);
            }
            return new WindowOutcome(true, false, window.enteredCount.get(), window.contaminations(),
                    hasActiveLingering(now));
        }
        if (initialRunning <= 0) {
            long now = System.nanoTime();
            if (window.hasBlindUntrackedAsync) {
                window.extendUntrackedTail(now, 0L);
            }
            if (window.hasBlindUntrackedAsync || window.hasAliveUntrackedHandles(now)
                    || window.untrackedHandlesOverflowed) {
                retainLingering(window, now);
            }
            return new WindowOutcome(true, false, window.enteredCount.get(), window.contaminations(),
                    hasActiveLingering(now));
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
        long now = System.nanoTime();
        boolean needsRetain = !drained || window.hasBlindUntrackedAsync
                || window.hasAliveUntrackedHandles(now) || window.untrackedHandlesOverflowed;
        if (needsRetain) {
            if (!drained) {
                window.wasCut = true;
            }
            if (window.hasBlindUntrackedAsync) {
                window.extendUntrackedTail(now, 0L);
            }
            retainLingering(window, now);
        }
        return new WindowOutcome(drained, true, window.enteredCount.get(), window.contaminations(),
                hasActiveLingering(now));
    }

    static final class WindowOutcome {
        final boolean drained;
        final boolean waited;
        final int enteredCount;
        final List<String> contaminations;
        // True when this window OR any older lingering window still has active
        // async work, live untracked handles, or an active blind untracked tail
        // at END — meaning probes from a test's tail may land in the gap after
        // END and must NOT be emitted as BACKGROUND.
        final boolean tailActiveAtEnd;

        WindowOutcome(boolean drained, boolean waited, int enteredCount, List<String> contaminations,
                boolean tailActiveAtEnd) {
            this.drained = drained;
            this.waited = waited;
            this.enteredCount = enteredCount;
            this.contaminations = contaminations;
            this.tailActiveAtEnd = tailActiveAtEnd;
        }
    }

    private static final class UntrackedHandle {

        private final WeakReference<Object> ref;
        private final WeakReference<Timer> timerRef;
        private final boolean alreadyStarted;
        private final long registeredAtNanos;
        private final boolean timerPeriodic;
        private final long timerExpectedFireAtNanos;
        private final long timerPeriodMillis;
        private final long timerInitialScheduledExecMillis;
        private volatile boolean observedAlive;

        private UntrackedHandle(Object target, boolean alreadyStarted, long registeredAtNanos) {
            this(target, null, alreadyStarted, registeredAtNanos, false, 0L, 0L, 0L);
        }

        private UntrackedHandle(Object target, Timer timer, boolean alreadyStarted, long registeredAtNanos,
                boolean timerPeriodic, long timerExpectedFireAtNanos, long timerPeriodMillis,
                long timerInitialScheduledExecMillis) {
            this.ref = new WeakReference<Object>(target);
            this.timerRef = timer == null ? null : new WeakReference<Timer>(timer);
            this.alreadyStarted = alreadyStarted;
            this.registeredAtNanos = registeredAtNanos;
            this.timerPeriodic = timerPeriodic;
            this.timerExpectedFireAtNanos = timerExpectedFireAtNanos;
            this.timerPeriodMillis = timerPeriodMillis;
            this.timerInitialScheduledExecMillis = timerInitialScheduledExecMillis;
        }

        private static UntrackedHandle forTimerTask(Timer timer, TimerTask task, long delayMillis,
                long periodMillis, boolean periodic, long now) {
            long safeDelayMs = delayMillis > 0L ? delayMillis : 0L;
            long delayNanos = TimeUnit.MILLISECONDS.toNanos(safeDelayMs);
            if (delayNanos < 0L) {
                delayNanos = maxLingeringNanos;
            }
            long expectedFire = now + delayNanos;
            long safePeriodMs = periodMillis > 0L ? periodMillis : 1L;
            long initialExec = safeScheduledExecutionTime(task);
            return new UntrackedHandle(task, timer, true, now, periodic, expectedFire, safePeriodMs, initialExec);
        }

        private boolean isActive(long now) {
            Object target = ref.get();
            if (target == null) {
                return false;
            }
            if (target instanceof Thread) {
                Thread t = (Thread) target;
                if (t.isAlive()) {
                    observedAlive = true;
                    return true;
                }
                if (alreadyStarted || observedAlive || t.getState() == Thread.State.TERMINATED) {
                    return false;
                }
                // Registered immediately before subThread.start() and still NEW:
                // keep active briefly in case the OS thread hasn't transitioned yet.
                long grace = Math.min(HANDLE_START_GRACE_NANOS, untrackedTailWindowNanos);
                return (now - registeredAtNanos) < grace;
            }
            if (target instanceof Future) {
                return !isFutureTerminated((Future<?>) target);
            }
            if (target instanceof TimerTask) {
                TimerTask task = (TimerTask) target;
                TimerCancelInfo cancelInfo = timerTaskCancelInfo(task);
                if (cancelInfo != null) {
                    if (cancelInfo.cancelledBeforeRun) {
                        if (!timerPeriodic) {
                            // One-shot TimerTask.cancel() returned true => still SCHEDULED,
                            // never fired and will never run.
                            return false;
                        }
                        // Periodic TimerTask.cancel() returned true: drop immediately ONLY
                        // if scheduledExecutionTime() never advanced (cancelled before its
                        // first tick); otherwise a tick's run() body may still be executing.
                        long curExec = safeScheduledExecutionTime(task);
                        if (curExec == timerInitialScheduledExecMillis
                                && (cancelInfo.cancelledAtNanos - timerExpectedFireAtNanos) < 0L) {
                            return false;
                        }
                    }
                    // Cancelled after firing (or while a body may still be executing):
                    // keep active for untrackedTailWindowNanos from cancellation, then drop.
                    return (now - cancelInfo.cancelledAtNanos) < untrackedTailWindowNanos;
                }
                Timer t = timerRef == null ? null : timerRef.get();
                Long timerCancelledAt = t == null ? null : timerCancelledAtNanos(t);
                if (timerCancelledAt != null) {
                    boolean cancelledBeforeFirstFire =
                            (timerCancelledAt.longValue() - timerExpectedFireAtNanos) < -TIMER_CANCEL_MARGIN_NANOS
                            && (!timerPeriodic || safeScheduledExecutionTime(task) == timerInitialScheduledExecMillis);
                    if (cancelledBeforeFirstFire) {
                        return false;
                    }
                    return (now - timerCancelledAt.longValue()) < untrackedTailWindowNanos;
                }
                if (!timerPeriodic) {
                    // One-shot TimerTask not cancelled before fire: TimerThread sets
                    // state=EXECUTED before calling run(), so keep active for the full
                    // untrackedTailWindowNanos measured from expected fire time.
                    return (now - timerExpectedFireAtNanos) < untrackedTailWindowNanos;
                }
                if ((now - timerExpectedFireAtNanos) < 0L) {
                    return true;
                }
                long execMillis = safeScheduledExecutionTime(task);
                if (execMillis <= 0L) {
                    return (now - registeredAtNanos) < maxLingeringNanos;
                }
                long tailWindowMs = TimeUnit.NANOSECONDS.toMillis(untrackedTailWindowNanos);
                long slackMillis = Math.max(timerPeriodMillis * 2L, tailWindowMs);
                return System.currentTimeMillis() <= execMillis + timerPeriodMillis + slackMillis;
            }
            return false;
        }
    }

    private static final class Window {

        private static final AtomicReferenceFieldUpdater<Window, ConcurrentHashMap> FUTURES_UPDATER =
                AtomicReferenceFieldUpdater.newUpdater(Window.class, ConcurrentHashMap.class, "futures");
        private static final AtomicReferenceFieldUpdater<Window, ConcurrentLinkedQueue> HANDLES_UPDATER =
                AtomicReferenceFieldUpdater.newUpdater(Window.class, ConcurrentLinkedQueue.class, "untrackedHandles");

        private final String testId;
        private volatile String fallbackId;
        private final AtomicInteger running = new AtomicInteger();
        // Actively executing requests and worker tasks (distinct from queued/
        // idle permits in `running`) so maxLingeringNanos never drops work that
        // is actually executing app code.
        private final AtomicInteger executing = new AtomicInteger();
        private final AtomicInteger enteredCount = new AtomicInteger();
        private volatile Thread waiter;
        private volatile boolean wasCut;
        private volatile boolean cutReportedOnce;
        // True when any untracked async (blind or handle-tracked) occurred in this window.
        private volatile boolean hadUntrackedAsync;
        // True ONLY when an untracked async WITHOUT a tracked liveness handle occurred
        // (drives the time-based untrackedTailWindowNanos fallback).
        private volatile boolean hasBlindUntrackedAsync;
        private volatile long untrackedTailExpiresAtNanos;
        private volatile boolean untrackedTailReportedOnce;
        private volatile long lingeringSinceNanos;
        // Pending submitted tasks keyed by Hand (self-removed on release in
        // O(1), plus periodically swept on track() for cancelled futures so
        // windows without an END cannot grow unboundedly).
        @SuppressWarnings("rawtypes")
        private volatile ConcurrentHashMap futures;
        // Weak handles to live untracked Threads, ScheduledFutures, and
        // TimerTasks started in this window so tail contamination persists
        // strictly while they stay alive.
        @SuppressWarnings("rawtypes")
        private volatile ConcurrentLinkedQueue untrackedHandles;
        private volatile boolean untrackedHandlesOverflowed;
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

        private void extendUntrackedTail(long now, long extraTailNanos) {
            long boundedExtra = Math.max(0L, Math.min(extraTailNanos, maxLingeringNanos));
            long target = now + untrackedTailWindowNanos + boundedExtra;
            long prev = untrackedTailExpiresAtNanos;
            if (prev == 0L || (target - prev) > 0L) {
                untrackedTailExpiresAtNanos = target;
            }
        }

        private void contaminate(String reason) {
            contaminate(reason, 0L);
        }

        private void contaminate(String reason, long extraTailNanos) {
            if (reason == null || reason.isEmpty()) {
                return;
            }
            if (reason.startsWith("untracked-")) {
                hadUntrackedAsync = true;
                hasBlindUntrackedAsync = true;
                extendUntrackedTail(System.nanoTime(), extraTailNanos);
            }
            addContamReason(reason);
        }

        private void contaminateTracked(String reason) {
            if (reason == null || reason.isEmpty()) {
                return;
            }
            if (reason.startsWith("untracked-")) {
                hadUntrackedAsync = true;
            }
            addContamReason(reason);
        }

        private void addContamReason(String reason) {
            if (contamCount.get() < JobGate.KEPT && contamSet.add(reason)) {
                if (contamCount.incrementAndGet() <= JobGate.KEPT) {
                    contamOrder.add(reason);
                } else {
                    contamSet.remove(reason);
                    contamCount.decrementAndGet();
                }
            }
        }

        private boolean hasOpeningTail() {
            for (String reason : contamOrder) {
                if (reason.startsWith("tail:") || reason.startsWith("job-tail:")
                        || "armed-mid-flight".equals(reason)) {
                    return true;
                }
            }
            return false;
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

        @SuppressWarnings("unchecked")
        private ConcurrentLinkedQueue<UntrackedHandle> untrackedHandlesQueue(boolean create) {
            ConcurrentLinkedQueue<UntrackedHandle> q =
                    (ConcurrentLinkedQueue<UntrackedHandle>) untrackedHandles;
            if (q != null || !create) {
                return q;
            }
            ConcurrentLinkedQueue<UntrackedHandle> fresh = new ConcurrentLinkedQueue<>();
            if (HANDLES_UPDATER.compareAndSet(this, null, fresh)) {
                return fresh;
            }
            return (ConcurrentLinkedQueue<UntrackedHandle>) untrackedHandles;
        }

        private void addUntrackedHandle(UntrackedHandle handle, long now) {
            hadUntrackedAsync = true;
            if (!addHandleToQueue(untrackedHandlesQueue(true), handle, now)) {
                untrackedHandlesOverflowed = true;
                KeployDedupAgent.noteObservedUntrackedAsyncLibrary(
                        "untracked async handle queue overflow (>64 live handles)", true);
            }
        }

        private boolean hasAliveUntrackedHandles(long now) {
            return sweepUntrackedHandles(untrackedHandlesQueue(false), now);
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
        // JobGate token kept alive across CompletableFuture postComplete() when
        // window == null && job != null && deferRelease.
        volatile Object deferredJobToken;
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
            if (window != null) {
                clearFutureKey();
                window.untrack(this);
                HandRef ref = executorRef;
                if (ref != null) {
                    ref.released = true;
                    executorRef = null;
                    ref.set.remove(ref);
                }
                done(window);
            } else {
                Object jt = deferredJobToken;
                if (jt != null) {
                    deferredJobToken = null;
                    JobGate.exit(jt);
                }
            }
        }

        // release marks this task done unconditionally (non-deferred exit).
        final void release() {
            if (window == null && job == null) {
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
            if (window == null && job == null) {
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
            if (window == null && job == null) {
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
                for (;;) {
                    int s = state;
                    if ((s & FLAG_STARTED) != 0) {
                        break;
                    }
                    int next = (s & ~FLAG_RELEASED) | FLAG_STARTED;
                    if (STATE.compareAndSet(this, s, next)) {
                        break;
                    }
                }
                Object jt = JobGate.enterAs(job);
                if (deferRelease) {
                    deferredJobToken = jt;
                }
                return jt;
            }
            for (;;) {
                int s = state;
                if ((s & FLAG_STARTED) != 0) {
                    break;
                }
                if ((s & FLAG_RELEASED) != 0) {
                    // Late start after an earlier pending release (e.g.
                    // shutdownNow race or early cf.complete()): re-acquire the
                    // window permit so exit() releases it cleanly when done.
                    if (STATE.compareAndSet(this, s, (s & ~FLAG_RELEASED) | FLAG_STARTED)) {
                        window.running.incrementAndGet();
                        break;
                    }
                } else if (STATE.compareAndSet(this, s, s | FLAG_STARTED)) {
                    break;
                }
            }
            window.executing.incrementAndGet();
            if (window != current) {
                // Re-retain closed windows BEFORE reading `current` so either a
                // concurrent open() sees executing > 0 in LINGERING or our
                // volatile read of `current` sees the newly opened window and
                // contaminates it immediately before any task probe runs.
                retainLingering(window, System.nanoTime());
                Window cur = current;
                if (cur != null && cur != window) {
                    cur.contaminate("tail:" + window.label());
                }
            }
            clearFutureKey();
            Window outer = INSIDE.get();
            INSIDE.set(window);
            return outer;
        }

        final void exit(Object outer) {
            if (window == null) {
                if (!deferRelease) {
                    JobGate.exit(outer);
                    return;
                }
                JobGate.detachThread(outer);
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
            window.executing.decrementAndGet();
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

    // Context-only re-runnable wrappers for verified JDK ScheduledThreadPoolExecutor
    // tasks: hold no window.running permit (END never waits on delayed/periodic
    // tasks), while propagating INSIDE/JobGate attribution and window.executing
    // across multi-hop retries and downstream async fan-outs.
    private static final class ScheduledContextRunnable implements Runnable {

        private final Window window;
        private final JobGate.Attribution job;
        private final Runnable task;

        private ScheduledContextRunnable(Window window, JobGate.Attribution job, Runnable task) {
            this.window = window;
            this.job = job;
            this.task = task;
        }

        @Override
        public void run() {
            if (window != null) {
                window.executing.incrementAndGet();
                if (window != current) {
                    retainLingering(window, System.nanoTime());
                    Window cur = current;
                    if (cur != null && cur != window) {
                        cur.contaminate("tail:" + window.label());
                    }
                }
                Window outer = INSIDE.get();
                INSIDE.set(window);
                try {
                    task.run();
                } finally {
                    window.executing.decrementAndGet();
                    if (outer == null) {
                        INSIDE.remove();
                    } else {
                        INSIDE.set(outer);
                    }
                }
                return;
            }
            Object jt = JobGate.enterAs(job);
            try {
                task.run();
            } finally {
                JobGate.exit(jt);
            }
        }

        @Override
        public String toString() {
            return task.toString();
        }
    }

    private static final class ScheduledContextCallable<V> implements Callable<V> {

        private final Window window;
        private final JobGate.Attribution job;
        private final Callable<V> task;

        private ScheduledContextCallable(Window window, JobGate.Attribution job, Callable<V> task) {
            this.window = window;
            this.job = job;
            this.task = task;
        }

        @Override
        public V call() throws Exception {
            if (window != null) {
                window.executing.incrementAndGet();
                if (window != current) {
                    retainLingering(window, System.nanoTime());
                    Window cur = current;
                    if (cur != null && cur != window) {
                        cur.contaminate("tail:" + window.label());
                    }
                }
                Window outer = INSIDE.get();
                INSIDE.set(window);
                try {
                    return task.call();
                } finally {
                    window.executing.decrementAndGet();
                    if (outer == null) {
                        INSIDE.remove();
                    } else {
                        INSIDE.set(outer);
                    }
                }
            }
            Object jt = JobGate.enterAs(job);
            try {
                return task.call();
            } finally {
                JobGate.exit(jt);
            }
        }

        @Override
        public String toString() {
            return task.toString();
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
