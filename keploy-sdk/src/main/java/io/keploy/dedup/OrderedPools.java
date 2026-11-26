package io.keploy.dedup;

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
 * OrderedPools says whether an executor's pool may order the tasks it is
 * handed by their type, inspect/cast tasks in overridden lifecycle hooks,
 * silently discard tasks without throwing, or wrap an opaque delegate whose
 * queue cannot be inspected — so that a task handed to it must reach it as it
 * is.
 *
 * <p>A pool whose queue is a PriorityBlockingQueue, or any queue with a
 * Comparator of the app's, casts each task it compares to the app's own type
 * and throws on a task in RequestGate's wrapper. A custom ThreadPoolExecutor
 * subclass that overrides {@code beforeExecute}/{@code afterExecute} (or
 * {@code decorateTask} on ScheduledThreadPoolExecutor) may likewise cast the
 * task. A pool configured with {@link ThreadPoolExecutor.DiscardPolicy} or
 * {@link ThreadPoolExecutor.DiscardOldestPolicy} can drop a task without
 * throwing, which would otherwise leave a request window open until END times
 * out.
 *
 * <p>Exact {@link ScheduledThreadPoolExecutor} instances are safe for
 * immediate {@code execute}/{@code submit} calls (unless configured with a
 * discard policy) because they always wrap each task in their own
 * {@code ScheduledFutureTask} before enqueueing into {@code DelayedWorkQueue}.
 * Spring's {@code ConcurrentTaskExecutor} and {@code ThreadPoolTaskScheduler}
 * are inspected through their public delegate accessors; an opaque delegate
 * whose queue cannot be verified is left unwrapped. Nothing thrown while
 * reading a pool or saying so in the log, by the app's own code too (an
 * exception's {@code toString()}, a log Handler), reaches the app's call: that
 * task goes as it is.
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

    // Spring classes whose pool accessor throws IllegalStateException while
    // not initialized, as their execute and submit do.
    private static final String SPRING_POOL = "org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor";
    private static final String SPRING_SCHEDULER = "org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler";
    private static final String SPRING_SIMPLE_ASYNC = "org.springframework.core.task.SimpleAsyncTaskExecutor";
    private static final String JDK_FINALIZABLE_DELEGATED =
            "java.util.concurrent.Executors$FinalizableDelegatedExecutorService";
    private static final String JDK_DELEGATED_SCHEDULED =
            "java.util.concurrent.Executors$DelegatedScheduledExecutorService";
    private static final String JDK_DELEGATED =
            "java.util.concurrent.Executors$DelegatedExecutorService";

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

    // Whether a ThreadPoolExecutor subclass overrides beforeExecute,
    // afterExecute, or (for ScheduledThreadPoolExecutor) decorateTask, which
    // may cast the queued Runnable/Callable to an app-specific subtype.
    // Spring 6's ThreadPoolTaskExecutor inner ThreadPoolExecutor subclass only
    // tracks Spring lifecycle hooks and never casts the task, so it is allowed.
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
                if (c.getName().startsWith(SPRING_POOL + "$")) {
                    continue;
                }
                if (declares(c, "beforeExecute", Thread.class, Runnable.class)
                        || declares(c, "afterExecute", Runnable.class, Throwable.class)
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

    // orders says whether executor's pool may order its tasks by their
    // type (or override task hooks, silently discard tasks, or wrap an opaque
    // delegate); the first time it does for an executor's class, it says in
    // the log that the tasks handed to that class are not counted. Whatever is
    // thrown on the way, by the app's own code too (the toString() of its
    // exception, a log Handler of its), stays here: the task goes as it is,
    // which is always safe.
    static boolean orders(Object executor) {
        try {
            return read(executor, 0, false);
        } catch (Throwable e) {
            return true;
        }
    }

    private static boolean read(Object executor, int depth, boolean requireVerified) {
        if (executor == null) {
            return requireVerified;
        }
        if (depth > MAX_DELEGATE_DEPTH) {
            return uncounted(executor, "its delegate executor chain is too deep to inspect");
        }
        if (executor instanceof ThreadPoolExecutor) {
            return checkThreadPool((ThreadPoolExecutor) executor, executor);
        }
        if (executor instanceof ForkJoinPool) {
            return false;
        }
        String className = executor.getClass().getName();
        if (JDK_FINALIZABLE_DELEGATED.equals(className)
                || JDK_DELEGATED_SCHEDULED.equals(className)) {
            return false;
        }
        if (JDK_DELEGATED.equals(className)) {
            return uncounted(executor, "its wrapped ExecutorService cannot be inspected for queue ordering");
        }
        Object pool = POOL.get(executor.getClass());
        if (pool != NONE) {
            return readViaPoolMethod(executor, pool, SPRING_POOL);
        }
        if (SPRING_SIMPLE_ASYNC.equals(className)) {
            return false;
        }
        Object scheduled = SCHEDULED_POOL.get(executor.getClass());
        if (scheduled != NONE) {
            return readViaPoolMethod(executor, scheduled, SPRING_SCHEDULER);
        }
        Object concurrent = CONCURRENT_EXECUTOR.get(executor.getClass());
        if (concurrent != NONE) {
            return readViaConcurrentExecutor(executor, concurrent, depth);
        }
        if (requireVerified) {
            return uncounted(executor, "its delegate executor cannot be inspected for queue ordering");
        }
        return false;
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

    private static boolean checkThreadPool(ThreadPoolExecutor pool, Object reportedOwner) {
        if (Boolean.TRUE.equals(OVERRIDES_TASK_HOOKS.get(pool.getClass()))) {
            return uncounted(reportedOwner, "its pool (" + pool.getClass().getName()
                    + ") overrides task hooks that may inspect or cast the task");
        }
        try {
            RejectedExecutionHandler handler = pool.getRejectedExecutionHandler();
            if (handler instanceof ThreadPoolExecutor.DiscardPolicy
                    || handler instanceof ThreadPoolExecutor.DiscardOldestPolicy) {
                return uncounted(reportedOwner, "its rejected execution handler, a "
                        + handler.getClass().getName() + ", may silently discard tasks");
            }
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError) {
                return true;
            }
            return uncounted(reportedOwner, "its pool's rejected execution handler cannot be read (" + e + ")");
        }
        if (pool.getClass() == ScheduledThreadPoolExecutor.class) {
            // Exact ScheduledThreadPoolExecutor always wraps execute/submit
            // tasks in its own ScheduledFutureTask before enqueueing into
            // DelayedWorkQueue, so the queue never casts the caller's task.
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
        if (IN_ORDER.contains(queue)) {
            return false;
        }
        if (pool instanceof ScheduledThreadPoolExecutor) {
            return uncounted(reportedOwner, "it is a ScheduledThreadPoolExecutor subclass ("
                    + pool.getClass().getName() + ") whose task handling cannot be assumed safe");
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
