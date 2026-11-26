package io.keploy.dedup;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OrderedPools says whether an executor's pool may order the tasks it is
 * handed by their type, so that a task handed to it must reach it as it is:
 * a pool whose queue is a PriorityBlockingQueue, or any queue with a
 * Comparator of the app's, casts each task it compares to the app's own
 * type, and throws on a task in RequestGate's wrapper.
 *
 * <p>An executor's pool is what its public {@code getThreadPoolExecutor()}
 * returns, as Spring's ThreadPoolTaskExecutor has. Its tasks go as they
 * are unless its queue is one of the JDK's queues that keep their tasks in
 * the order they came and never look at one (Spring builds a
 * LinkedBlockingQueue or a SynchronousQueue); a queue of another class, or
 * one that cannot be read, may order them. An executor with no such method
 * (a SimpleAsyncTaskExecutor, which starts a thread per task, or the
 * executor {@code @Async} calls with a Callable, which it never queues as
 * it is) has no queue that sees the task. Nothing thrown while reading a
 * pool or saying so in the log, by the app's own code too (an exception's
 * {@code toString()}, a log Handler), reaches the app's call: that task
 * goes as it is.
 */
final class OrderedPools {

    private static final Logger LOGGER = Logger.getLogger(KeployDedupAgent.class.getName());

    // The queues that never look at a task, by their exact class: a
    // subclass may override how it takes one.
    private static final Set<Class<?>> IN_ORDER = Collections.unmodifiableSet(new HashSet<Class<?>>(Arrays.asList(
            LinkedBlockingQueue.class, SynchronousQueue.class, ArrayBlockingQueue.class, LinkedBlockingDeque.class,
            LinkedTransferQueue.class)));

    // What POOL holds for a class with no public getThreadPoolExecutor().
    private static final Object NONE = new Object();

    // The class whose getThreadPoolExecutor() throws IllegalStateException
    // while its pool is not initialized, as its execute and submit do.
    private static final String SPRING_POOL = "org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor";

    // A class's public getThreadPoolExecutor(), NONE when it has none, or
    // the String of what looking it up threw. getMethod resolves each type
    // the class's public methods name, so one that is missing (an optional
    // dependency) throws a NoClassDefFoundError: the app's own call must
    // never see it. Only a LinkageError or a RuntimeException is kept, as
    // the class's for good: anything else (out of stack or memory for now)
    // leaves computeValue, so nothing is kept, that task goes as it is, and
    // the next one looks the class up again.
    private static final ClassValue<Object> POOL = new ClassValue<Object>() {
        @Override
        protected Object computeValue(Class<?> type) {
            try {
                return type.getMethod("getThreadPoolExecutor");
            } catch (NoSuchMethodException e) {
                return NONE;
            } catch (LinkageError | RuntimeException e) {
                return e.toString();
            }
        }
    };

    // The executor classes whose hand-offs the log said are not counted.
    private static final Set<String> SAID = ConcurrentHashMap.newKeySet();

    private OrderedPools() {
    }

    // orders says whether executor's pool may order its tasks by their
    // type; the first time it does for an executor's class, it says in the
    // log that the tasks handed to that class are not counted. Whatever is
    // thrown on the way, by the app's own code too (the toString() of its
    // exception, a log Handler of its), stays here: the task goes as it is,
    // which is always safe.
    static boolean orders(Object executor) {
        try {
            return read(executor);
        } catch (Throwable e) {
            return true;
        }
    }

    private static boolean read(Object executor) {
        if (executor == null) {
            return false;
        }
        if (executor instanceof ThreadPoolExecutor) {
            Class<?> queue;
            try {
                queue = ((ThreadPoolExecutor) executor).getQueue().getClass();
            } catch (Throwable e) {
                if (e instanceof VirtualMachineError) {
                    return true;
                }
                return uncounted(executor, "its pool's queue cannot be read (" + e + ")");
            }
            if (IN_ORDER.contains(queue)) {
                return false;
            }
            return uncounted(executor, "its pool's queue, a " + queue.getName()
                    + ", may order its tasks by their own type");
        }
        Object pool = POOL.get(executor.getClass());
        if (pool == NONE) {
            return false;
        }
        if (pool instanceof String) {
            return uncounted(executor, "its pool's queue cannot be read (" + pool + ")");
        }
        Class<?> queue;
        try {
            queue = ((ThreadPoolExecutor) ((Method) pool).invoke(executor)).getQueue().getClass();
        } catch (Throwable e) {
            // Not a ThreadPoolExecutor, no queue, or not ours to call: the
            // task goes as it is, and whatever was thrown stays here.
            Throwable why = e instanceof InvocationTargetException ? e.getCause() : e;
            if (why instanceof VirtualMachineError) {
                // Out of stack or memory for now: that says nothing of the
                // class, so the log says nothing, and the next task's pool
                // is read again.
                return true;
            }
            if (e instanceof InvocationTargetException && why instanceof IllegalStateException
                    && SPRING_POOL.equals(((Method) pool).getDeclaringClass().getName())) {
                // Spring's pool, not initialized: the executor's own call
                // throws the same and takes no task, so the log says
                // nothing of its class.
                return true;
            }
            return uncounted(executor, "its pool's queue cannot be read (" + why + ")");
        }
        if (IN_ORDER.contains(queue)) {
            return false;
        }
        return uncounted(executor, "its pool's queue, a " + queue.getName()
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
