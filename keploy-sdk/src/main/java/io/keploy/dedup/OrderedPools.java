package io.keploy.dedup;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OrderedPools enforces a fail-closed allow-list of executors whose task
 * handling is positively verified not to cast, serialize, deduplicate,
 * reorder-by-type, or silently discard tasks handed to them.
 *
 * <p>Only positively verified safe executors are wrapped:
 * <ul>
 *   <li>Exact {@link ThreadPoolExecutor} with exact
 *       {@link ThreadPoolExecutor.AbortPolicy} and an exact {@code IN_ORDER}
 *       JDK queue ({@link LinkedBlockingQueue}, {@link SynchronousQueue},
 *       {@link ArrayBlockingQueue}, {@link LinkedBlockingDeque},
 *       {@link LinkedTransferQueue}).</li>
 *   <li>Exact {@link ScheduledThreadPoolExecutor} with exact
 *       {@link ThreadPoolExecutor.AbortPolicy}.</li>
 *   <li>Exact {@link ForkJoinPool}.</li>
 *   <li>Exact JDK {@code FinalizableDelegatedExecutorService} /
 *       {@code AutoShutdownDelegatedExecutorService} (constructed only by
 *       {@code Executors.newSingleThreadExecutor}), and JDK
 *       {@code DelegatedExecutorService} / {@code DelegatedScheduledExecutorService}
 *       only when their underlying delegate can be reflectively read and
 *       verified through to an allow-listed pool.</li>
 *   <li>Exact JDK 21+ {@code java.util.concurrent.ThreadPerTaskExecutor}.</li>
 *   <li>Known Spring executor types ({@code SimpleAsyncTaskExecutor},
 *       {@code ThreadPoolTaskExecutor}, {@code ThreadPoolTaskScheduler},
 *       {@code ConcurrentTaskExecutor}, and Spring's own anonymous inner
 *       {@code ThreadPoolTaskExecutor$} / {@code ThreadPoolTaskScheduler$}
 *       subclasses) verified through to an allow-listed inner pool.</li>
 * </ul>
 *
 * <p>Any unknown or unclassified executor (e.g., Hazelcast
 * {@code IExecutorService}, Ignite, Redisson, striped/keyed executors, custom
 * {@code ExecutorService} or {@code ThreadPoolExecutor} subclasses, priority
 * queues, non-{@code AbortPolicy} handlers, coalescing executors) fails CLOSED:
 * {@link #orders(Object)} returns {@code true}, the app's task reaches the
 * executor untouched, and {@link RequestGate} marks the current test window
 * contaminated ({@code untracked-executor:<class>}).
 */
final class OrderedPools {

    private static final Logger LOGGER = Logger.getLogger(KeployDedupAgent.class.getName());
    private static final int MAX_DELEGATE_DEPTH = 8;

    // The queues that never look at a task, by their exact class: a
    // subclass may override how it takes one.
    private static final Set<Class<?>> IN_ORDER = Collections.unmodifiableSet(new HashSet<Class<?>>(Arrays.asList(
            LinkedBlockingQueue.class, SynchronousQueue.class, ArrayBlockingQueue.class, LinkedBlockingDeque.class,
            LinkedTransferQueue.class)));

    // What lookup caches hold for a class with no matching public method.
    private static final Object NONE = new Object();

    // Known same-thread inline executors whose execute/submit runs synchronously
    // on the calling thread: neither wrapped nor flagged as untracked-executor.
    private static final Set<String> INLINE_EXECUTORS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "org.springframework.core.task.SyncTaskExecutor",
            "com.google.common.util.concurrent.DirectExecutor",
            "com.google.common.util.concurrent.MoreExecutors$DirectExecutor",
            "com.google.common.util.concurrent.DirectExecutorService")));

    private static final String SPRING_ASYNC_TASK_EXECUTOR =
            "org.springframework.core.task.AsyncTaskExecutor";

    // Whether a class implements Spring's AsyncTaskExecutor (which extends
    // java.util.concurrent.Executor, not ExecutorService, and defines submit/
    // submitListenable/submitCompletable).
    private static final ClassValue<Boolean> IS_SPRING_ASYNC_TASK_EXECUTOR = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return implementsInterfaceByName(type, SPRING_ASYNC_TASK_EXECUTOR);
        }
    };

    private static boolean implementsInterfaceByName(Class<?> type, String interfaceName) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (interfaceName.equals(c.getName())) {
                return true;
            }
            for (Class<?> iface : c.getInterfaces()) {
                if (implementsInterfaceByName(iface, interfaceName)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isInlineExecutor(Object executor) {
        return executor != null && INLINE_EXECUTORS.contains(executor.getClass().getName());
    }

    static boolean isSpringAsyncTaskExecutor(Object receiver) {
        return receiver instanceof java.util.concurrent.Executor
                && Boolean.TRUE.equals(IS_SPRING_ASYNC_TASK_EXECUTOR.get(receiver.getClass()));
    }

    // Spring classes whose pool accessor throws IllegalStateException while
    // not initialized, as their execute and submit do.
    private static final String SPRING_POOL = "org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor";
    private static final String SPRING_SCHEDULER = "org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler";
    private static final String SPRING_CONCURRENT = "org.springframework.scheduling.concurrent.ConcurrentTaskExecutor";
    private static final String SPRING_SIMPLE_ASYNC = "org.springframework.core.task.SimpleAsyncTaskExecutor";
    private static final String SPRING_TASK_EXECUTOR_ADAPTER =
            "org.springframework.core.task.support.TaskExecutorAdapter";
    private static final String SPRING_ASYNC_ASPECT =
            "org.springframework.aop.interceptor.AsyncExecutionAspectSupport";
    private static final String JDK_FINALIZABLE_DELEGATED =
            "java.util.concurrent.Executors$FinalizableDelegatedExecutorService";
    private static final String JDK_AUTO_SHUTDOWN_DELEGATED =
            "java.util.concurrent.Executors$AutoShutdownDelegatedExecutorService";
    private static final String JDK_DELEGATED_SCHEDULED =
            "java.util.concurrent.Executors$DelegatedScheduledExecutorService";
    private static final String JDK_DELEGATED =
            "java.util.concurrent.Executors$DelegatedExecutorService";
    private static final String JDK_THREAD_PER_TASK =
            "java.util.concurrent.ThreadPerTaskExecutor";

    // Cached reflective handle to Executors$DelegatedExecutorService.e when
    // accessible (Java 8, or Java 9+ with --add-opens). Null when inaccessible,
    // in which case DelegatedExecutorService / DelegatedScheduledExecutorService
    // fail closed.
    private static final Field DELEGATED_INNER_FIELD = findDelegatedInnerField();

    // A class's public getThreadPoolExecutor(), NONE when it has none, or
    // the String of what looking it up threw. getMethod resolves each type
    // the class's public methods name, so one that is missing (an optional
    // dependency) throws a NoClassDefFoundError: the app's own call must
    // never see it. Only a LinkageError or a RuntimeException is kept, as
    // the class's for good: anything else (out of stack or memory for now)
    // leaves computeValue, so nothing is kept, that task goes as it is, and
    // the next one looks the class up again.
    private static final ClassValue<Object> POOL = methodLookup("getThreadPoolExecutor");
    private static final ClassValue<Object> SCHEDULED_POOL = methodLookup("getScheduledThreadPoolExecutor");
    private static final ClassValue<Object> CONCURRENT_EXECUTOR = methodLookup("getConcurrentExecutor");
    // Spring's TaskExecutorAdapter (used by @Async's AsyncExecutionInterceptor
    // to wrap plain JDK ExecutorServices) stores its delegate in a private
    // concurrentExecutor field with no public getter.
    private static final ClassValue<Object> ADAPTER_FIELD = new ClassValue<Object>() {
        @Override
        protected Object computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                if (SPRING_TASK_EXECUTOR_ADAPTER.equals(c.getName())) {
                    try {
                        Field f = c.getDeclaredField("concurrentExecutor");
                        f.setAccessible(true);
                        return f;
                    } catch (Throwable e) {
                        return e.toString();
                    }
                }
            }
            return NONE;
        }
    };

    // Whether a Spring executor subclass overrides execute/submit/doExecute
    // (outside Spring's own executor base classes), which may cast or inspect
    // the task before delegating to the inner pool.
    private static final ClassValue<Boolean> OVERRIDES_SPRING_SUBMIT = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                String name = c.getName();
                if (isKnownSpringBaseClass(name)) {
                    break;
                }
                if (declares(c, "execute", Runnable.class)
                        || declares(c, "execute", Runnable.class, long.class)
                        || declares(c, "submit", Runnable.class)
                        || declares(c, "submit", Callable.class)
                        || declares(c, "submitListenable", Runnable.class)
                        || declares(c, "submitListenable", Callable.class)
                        || declares(c, "submitCompletable", Runnable.class)
                        || declares(c, "submitCompletable", Callable.class)
                        || declares(c, "doExecute", Runnable.class)) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        }
    };

    private static boolean isKnownSpringBaseClass(String name) {
        return SPRING_POOL.equals(name)
                || SPRING_SCHEDULER.equals(name)
                || SPRING_CONCURRENT.equals(name)
                || SPRING_SIMPLE_ASYNC.equals(name)
                || SPRING_TASK_EXECUTOR_ADAPTER.equals(name)
                || SPRING_ASYNC_ASPECT.equals(name);
    }

    private static boolean isSpringExecutorHierarchy(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            if (isKnownSpringBaseClass(c.getName())) {
                return true;
            }
        }
        return false;
    }

    // Returns true iff executor extends one of the Spring executor classes that
    // ServerEntries.addAsync actually instrumented, does not override execute/
    // submit/doExecute, and passes OrderedPools' fail-closed verification.
    static boolean isHookedAndVerifiedSpringExecutor(Object executor) {
        if (executor == null) {
            return false;
        }
        Class<?> cls = executor.getClass();
        String hookedBase = null;
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            String name = c.getName();
            if (SPRING_POOL.equals(name)
                    || SPRING_SCHEDULER.equals(name)
                    || SPRING_CONCURRENT.equals(name)
                    || SPRING_SIMPLE_ASYNC.equals(name)) {
                hookedBase = name;
                break;
            }
        }
        if (hookedBase == null || !ServerEntries.isSpringExecutorHooked(hookedBase)) {
            return false;
        }
        try {
            if (Boolean.TRUE.equals(OVERRIDES_SPRING_SUBMIT.get(cls))) {
                return false;
            }
            return !orders(executor);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // Whether a ThreadPoolExecutor subclass overrides beforeExecute,
    // afterExecute, newTaskFor, execute, submit, or (for
    // ScheduledThreadPoolExecutor) decorateTask, which may cast the queued
    // Runnable/Callable to an app-specific subtype. Spring's internal
    // ThreadPoolTaskExecutor$ and ThreadPoolTaskScheduler$ anonymous subclasses
    // only track Spring lifecycle hooks and never cast the task, so they are
    // allowed.
    private static final ClassValue<Boolean> OVERRIDES_TASK_HOOKS = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            if (type == ThreadPoolExecutor.class || type == ScheduledThreadPoolExecutor.class) {
                return Boolean.FALSE;
            }
            for (Class<?> c = type; c != null && c != ThreadPoolExecutor.class; c = c.getSuperclass()) {
                if (c == ScheduledThreadPoolExecutor.class) {
                    break;
                }
                String name = c.getName();
                if (name.startsWith(SPRING_POOL + "$") || name.startsWith(SPRING_SCHEDULER + "$")) {
                    continue;
                }
                if (declares(c, "beforeExecute", Thread.class, Runnable.class)
                        || declares(c, "afterExecute", Runnable.class, Throwable.class)
                        || declares(c, "newTaskFor", Runnable.class, Object.class)
                        || declares(c, "newTaskFor", Callable.class)
                        || declares(c, "execute", Runnable.class)
                        || declares(c, "submit", Runnable.class)
                        || declares(c, "submit", Runnable.class, Object.class)
                        || declares(c, "submit", Callable.class)
                        || declares(c, "decorateTask", Runnable.class, RunnableScheduledFuture.class)
                        || declares(c, "decorateTask", Callable.class, RunnableScheduledFuture.class)) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        }
    };

    // The executor classes whose hand-offs the log said are not counted.
    private static final Set<String> SAID = ConcurrentHashMap.newKeySet();

    private OrderedPools() {
    }

    private static Field findDelegatedInnerField() {
        try {
            Class<?> cls = Class.forName(JDK_DELEGATED);
            Field f = cls.getDeclaredField("e");
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private static ClassValue<Object> methodLookup(final String methodName) {
        return new ClassValue<Object>() {
            @Override
            protected Object computeValue(Class<?> type) {
                try {
                    return type.getMethod(methodName);
                } catch (NoSuchMethodException e) {
                    return NONE;
                } catch (LinkageError | RuntimeException e) {
                    return e.toString();
                }
            }
        };
    }

    private static boolean declares(Class<?> type, String name, Class<?>... params) {
        try {
            type.getDeclaredMethod(name, params);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        } catch (LinkageError | RuntimeException e) {
            // Conservative: if we cannot inspect the subclass, treat it as overriding.
            return true;
        }
    }

    // orders returns true (do NOT wrap) unless executor is positively verified
    // on the safe allow-list. The first time an executor class is rejected, it
    // logs once at INFO. Whatever is thrown on the way (including an
    // exception's toString() or a throwing log Handler) stays here: the task
    // goes untouched, which is always app-safe.
    static boolean orders(Object executor) {
        try {
            return read(executor, 0, true);
        } catch (Throwable e) {
            return true;
        }
    }

    private static boolean read(Object executor, int depth, boolean requireVerified) {
        if (executor == null) {
            // Top-level null is passed only for default commonPool (1-arg
            // CompletableFuture.runAsync/supplyAsync) and static Spring
            // helpers; a null delegate at depth > 0 always fails closed.
            return depth > 0;
        }
        if (depth > MAX_DELEGATE_DEPTH) {
            return uncounted(executor, "its delegate executor chain is too deep to inspect");
        }
        Class<?> cls = executor.getClass();
        if (executor instanceof ThreadPoolExecutor) {
            return checkThreadPool((ThreadPoolExecutor) executor, executor);
        }
        if (cls == ForkJoinPool.class) {
            return false;
        }
        String className = cls.getName();
        if (JDK_FINALIZABLE_DELEGATED.equals(className)
                || JDK_AUTO_SHUTDOWN_DELEGATED.equals(className)
                || JDK_THREAD_PER_TASK.equals(className)) {
            return false;
        }
        if (depth > 0 && INLINE_EXECUTORS.contains(className)) {
            return false;
        }
        if (JDK_DELEGATED.equals(className) || JDK_DELEGATED_SCHEDULED.equals(className)) {
            return readDelegated(executor, depth);
        }
        if (isSpringExecutorHierarchy(cls)) {
            Object pool = POOL.get(cls);
            if (pool instanceof String) {
                return readViaPoolMethod(executor, pool, SPRING_POOL);
            }
            if (Boolean.TRUE.equals(OVERRIDES_SPRING_SUBMIT.get(cls))) {
                return uncounted(executor, "it is a Spring executor subclass (" + className
                        + ") that overrides execute/submit/doExecute");
            }
            if (pool != NONE) {
                return readViaPoolMethod(executor, pool, SPRING_POOL);
            }
            if (SPRING_SIMPLE_ASYNC.equals(className)) {
                return false;
            }
            Object scheduled = SCHEDULED_POOL.get(cls);
            if (scheduled != NONE) {
                return readViaPoolMethod(executor, scheduled, SPRING_SCHEDULER);
            }
            Object concurrent = CONCURRENT_EXECUTOR.get(cls);
            if (concurrent != NONE) {
                return readViaConcurrentExecutor(executor, concurrent, depth);
            }
            Object adapterField = ADAPTER_FIELD.get(cls);
            if (adapterField != NONE) {
                return readViaAdapterField(executor, adapterField, depth);
            }
        }
        if (requireVerified) {
            return uncounted(executor, "its executor class is not on the verified safe allow-list");
        }
        return false;
    }

    private static boolean readViaAdapterField(Object executor, Object fieldOrError, int depth) {
        if (fieldOrError instanceof String) {
            return uncounted(executor, "its delegate executor cannot be read (" + fieldOrError + ")");
        }
        Object delegate;
        try {
            delegate = ((Field) fieldOrError).get(executor);
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError) {
                return true;
            }
            return uncounted(executor, "its delegate executor cannot be read (" + e + ")");
        }
        if (delegate == null || delegate == executor) {
            return uncounted(executor, "its delegate executor cannot be inspected");
        }
        return read(delegate, depth + 1, true);
    }

    private static boolean readDelegated(Object executor, int depth) {
        Field f = DELEGATED_INNER_FIELD;
        if (f == null) {
            return uncounted(executor, "its wrapped ExecutorService cannot be inspected for queue ordering");
        }
        Object inner;
        try {
            inner = f.get(executor);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError) {
                return true;
            }
            return uncounted(executor, "its wrapped ExecutorService cannot be read (" + t + ")");
        }
        if (inner == null || inner == executor) {
            return uncounted(executor, "its wrapped ExecutorService cannot be inspected");
        }
        return read(inner, depth + 1, true);
    }

    private static boolean readViaPoolMethod(Object executor, Object methodOrError, String springClass) {
        if (methodOrError instanceof String) {
            return uncounted(executor, "its pool's queue cannot be read (" + methodOrError + ")");
        }
        ThreadPoolExecutor tpe;
        try {
            tpe = (ThreadPoolExecutor) ((Method) methodOrError).invoke(executor);
        } catch (Throwable e) {
            Throwable why = e instanceof InvocationTargetException ? e.getCause() : e;
            if (why instanceof VirtualMachineError) {
                return true;
            }
            if (e instanceof InvocationTargetException && why instanceof IllegalStateException
                    && springClass.equals(((Method) methodOrError).getDeclaringClass().getName())) {
                // Spring's pool, not initialized: the executor's own call
                // throws the same and takes no task, so the log says nothing.
                return true;
            }
            return uncounted(executor, "its pool's queue cannot be read (" + why + ")");
        }
        if (tpe == null) {
            return uncounted(executor, "its pool's queue cannot be read (java.lang.NullPointerException)");
        }
        return checkThreadPool(tpe, executor);
    }

    private static boolean readViaConcurrentExecutor(Object executor, Object methodOrError, int depth) {
        if (methodOrError instanceof String) {
            return uncounted(executor, "its delegate executor cannot be read (" + methodOrError + ")");
        }
        Object delegate;
        try {
            delegate = ((Method) methodOrError).invoke(executor);
        } catch (Throwable e) {
            Throwable why = e instanceof InvocationTargetException ? e.getCause() : e;
            if (why instanceof VirtualMachineError) {
                return true;
            }
            return uncounted(executor, "its delegate executor cannot be read (" + why + ")");
        }
        if (delegate == null || delegate == executor) {
            return uncounted(executor, "its delegate executor cannot be inspected");
        }
        return read(delegate, depth + 1, true);
    }

    private static boolean isAllowedThreadPoolClass(Class<?> cls) {
        if (cls == ThreadPoolExecutor.class || cls == ScheduledThreadPoolExecutor.class) {
            return true;
        }
        String name = cls.getName();
        return name.startsWith(SPRING_POOL + "$") || name.startsWith(SPRING_SCHEDULER + "$");
    }

    private static boolean checkThreadPool(ThreadPoolExecutor pool, Object reportedOwner) {
        Class<?> poolClass = pool.getClass();
        if (Boolean.TRUE.equals(OVERRIDES_TASK_HOOKS.get(poolClass))) {
            return uncounted(reportedOwner, "its pool (" + poolClass.getName()
                    + ") overrides task hooks that may inspect or cast the task");
        }
        try {
            RejectedExecutionHandler handler = pool.getRejectedExecutionHandler();
            if (handler == null || handler.getClass() != ThreadPoolExecutor.AbortPolicy.class) {
                String handlerName = handler == null ? "null" : handler.getClass().getName();
                return uncounted(reportedOwner, "its rejected execution handler, a "
                        + handlerName + ", may silently discard or inspect tasks");
            }
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError) {
                return true;
            }
            return uncounted(reportedOwner, "its pool's rejected execution handler cannot be read (" + e + ")");
        }
        if (poolClass == ScheduledThreadPoolExecutor.class
                || (pool instanceof ScheduledThreadPoolExecutor
                        && poolClass.getName().startsWith(SPRING_SCHEDULER + "$"))) {
            // Exact ScheduledThreadPoolExecutor (and Spring's own
            // ThreadPoolTaskScheduler internal subclass) always wraps
            // execute/submit tasks in its own ScheduledFutureTask before
            // enqueueing into DelayedWorkQueue, so the queue never casts the
            // caller's task.
            return false;
        }
        Class<?> queue;
        try {
            queue = pool.getQueue().getClass();
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError) {
                return true;
            }
            return uncounted(reportedOwner, "its pool's queue cannot be read (" + e + ")");
        }
        if (!isAllowedThreadPoolClass(poolClass)) {
            if (pool instanceof ScheduledThreadPoolExecutor) {
                return uncounted(reportedOwner, "it is a ScheduledThreadPoolExecutor subclass ("
                        + poolClass.getName() + ") whose task handling cannot be assumed safe");
            }
            return uncounted(reportedOwner, "it is a ThreadPoolExecutor subclass ("
                    + poolClass.getName() + ") whose task handling cannot be assumed safe");
        }
        if (IN_ORDER.contains(queue)) {
            return false;
        }
        return uncounted(reportedOwner, "its pool's queue, a " + queue.getName()
                + ", may order its tasks by their own type");
    }

    private static boolean uncounted(Object executor, String why) {
        String name = executor.getClass().getName();
        if (SAID.add(name)) {
            LOGGER.log(Level.INFO, "Keploy dedup does not count the tasks handed to " + name + ": " + why
                    + ", so each reaches it as it is; a test's END does not wait for one a request hands it, and"
                    + " one a scheduled job's run hands it marks no test");
        }
        return true;
    }
}
