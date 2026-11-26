package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The work a request hands to a Spring TaskExecutor counts in the request's
 * window until it is done (RequestGate.hand), and ServerEntries makes
 * Spring's executors hand each task they are given: here on RequestGate
 * itself and on stand-ins of the executors' classes; Spring's own run in
 * ServerTailTest.
 */
public class AsyncHandTest {

    private static final String SIMPLE = "org/springframework/core/task/SimpleAsyncTaskExecutor";
    private static final String POOL = "org/springframework/scheduling/concurrent/ThreadPoolTaskExecutor";
    private static final String ASYNC = "org/springframework/aop/interceptor/AsyncExecutionAspectSupport";

    // What the stand-ins' own code was handed, and whether it throws.
    private static volatile Object lastTask;
    private static volatile long lastLong;
    private static volatile String lastString;
    private static volatile boolean executorThrows;
    // The pool the stand-ins' getThreadPoolExecutor() returns: one whose
    // queue takes tasks in order unless a row sets another.
    private static volatile ThreadPoolExecutor standInPool;

    @Before
    public void setUp() {
        RequestGate.open();
        lastTask = null;
        executorThrows = false;
        standInPool = pool(new LinkedBlockingQueue<>());
    }

    @After
    public void tearDown() {
        // No row leaves its thread inside a window.
        Runnable task = () -> { };
        assertSame("the test thread is outside every window", task, RequestGate.hand(null, task));
    }

    @Test
    public void aTaskARequestHandsOffCountsUntilItIsDone() {
        Object request = RequestGate.enter();
        Runnable handed;
        AtomicReference<Runnable> nested = new AtomicReference<>();
        try {
            handed = RequestGate.hand(null, () -> {
                if (nested.get() == null) {
                    nested.set(RequestGate.hand(null, () -> { }));
                }
            });
            assertEquals("the request and its task", 2, RequestGate.running());
            assertSame("counted once", handed, RequestGate.hand(null, handed));
            // No task: passed on for the executor to throw on, as before.
            assertNull(RequestGate.hand(null, (Runnable) null));
            assertEquals(2, RequestGate.running());
        } finally {
            RequestGate.exit(request);
        }
        assertEquals("the task outlives its request", 1, RequestGate.running());
        handed.run();
        // What the task handed off counts in the request's window too.
        assertEquals(1, RequestGate.running());
        nested.get().run();
        assertEquals(0, RequestGate.running());
        handed.run();
        assertEquals("a task run twice is done once", 0, RequestGate.running());
    }

    @Test
    public void aTaskHandedOffAThreadThatRunsNoRequestIsNotCounted() {
        Runnable task = () -> { };
        Callable<String> call = () -> "x";
        assertSame(task, RequestGate.hand(null, task));
        assertSame(call, RequestGate.hand(null, call));
        assertNull(RequestGate.hand(null, (Runnable) null));
        assertEquals(0, RequestGate.running());
    }

    @Test(timeout = 60000)
    public void aComparableTaskGoesAsItIsSoAPriorityPoolStillOrdersIt() throws Exception {
        // A pool whose queue orders its tasks by their own type, its one
        // thread busy: tasks handed off in a request's window, in a job's
        // run and off both queue as they are, and run in their order.
        // Wrapped, the queue threw ClassCastException in execute.
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch free = new CountDownLatch(1);
        List<Integer> ran = new CopyOnWriteArrayList<>();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new PriorityBlockingQueue<Runnable>());
        try {
            pool.execute(() -> {
                busy.countDown();
                try {
                    free.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(busy.await(30, TimeUnit.SECONDS));
            Ranked last = new Ranked(3, ran);
            Ranked first = new Ranked(1, ran);
            Ranked second = new Ranked(2, ran);
            RankedCall call = new RankedCall();
            Object request = RequestGate.enter();
            try {
                assertSame("a request's", last, RequestGate.hand(null, last));
                assertSame("a request's", call, RequestGate.hand(null, call));
                pool.execute(RequestGate.hand(null, last));
                assertEquals("not counted: END does not wait for it", 1, RequestGate.running());
            } finally {
                RequestGate.exit(request);
            }
            Object run = JobGate.enter(new Object(), "scheduled");
            try {
                assertSame("a job's", first, RequestGate.hand(null, first));
                assertSame("a job's", call, RequestGate.hand(null, call));
                pool.execute(RequestGate.hand(null, first));
            } finally {
                JobGate.exit(run);
            }
            pool.execute(RequestGate.hand(null, second));
            free.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
            assertEquals("in their order", Arrays.asList(1, 2, 3), ran);
        } finally {
            free.countDown();
            pool.shutdownNow();
        }
    }

    // Ranked is an app's task that a priority pool orders by its rank.
    private static final class Ranked implements Runnable, Comparable<Ranked> {

        private final int rank;
        private final List<Integer> ran;

        Ranked(int rank, List<Integer> ran) {
            this.rank = rank;
            this.ran = ran;
        }

        @Override
        public void run() {
            ran.add(rank);
        }

        @Override
        public int compareTo(Ranked other) {
            return Integer.compare(rank, other.rank);
        }
    }

    private static final class RankedCall implements Callable<String>, Comparable<RankedCall> {

        @Override
        public String call() {
            return "x";
        }

        @Override
        public int compareTo(RankedCall other) {
            return 0;
        }
    }

    @Test
    public void aCallableIsCountedAndItsValueAndThrowKept() throws Exception {
        Object request = RequestGate.enter();
        Callable<String> value;
        Callable<String> throwing;
        try {
            value = RequestGate.hand(null, () -> "x");
            throwing = RequestGate.hand(null, () -> {
                throw new IllegalStateException("thrown");
            });
            assertSame(value, RequestGate.hand(null, value));
            assertNull(RequestGate.hand(null, (Callable<String>) null));
        } finally {
            RequestGate.exit(request);
        }
        assertEquals(2, RequestGate.running());
        assertEquals("x", value.call());
        try {
            throwing.call();
            fail();
        } catch (IllegalStateException e) {
            assertEquals("thrown", e.getMessage());
        }
        assertEquals(0, RequestGate.running());
    }

    @Test
    public void aRunnableThatThrowsIsDoneAndItsThreadLeftOutsideTheWindow() throws Exception {
        Object request = RequestGate.enter();
        Runnable handed;
        try {
            handed = RequestGate.hand(null, (Runnable) () -> {
                throw new IllegalStateException("thrown");
            });
        } finally {
            RequestGate.exit(request);
        }
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Runnable> after = new AtomicReference<>();
        Runnable probe = () -> { };
        Thread pool = new Thread(() -> {
            try {
                handed.run();
            } catch (Throwable t) {
                thrown.set(t);
            }
            after.set(RequestGate.hand(null, probe));
        });
        pool.start();
        pool.join(10000);
        assertTrue(thrown.get() instanceof IllegalStateException);
        assertSame("the pool's thread is outside the window again", probe, after.get());
        assertEquals(0, RequestGate.running());
    }

    @Test
    public void aDroppedTaskIsDoneOnceAndATaskNotHandedIsNotDone() {
        Object request = RequestGate.enter();
        try {
            Runnable task = () -> { };
            Runnable handed = RequestGate.hand(null, task);
            assertEquals(2, RequestGate.running());
            RequestGate.dropped(handed, task);
            assertEquals(1, RequestGate.running());
            RequestGate.dropped(handed, task);
            handed.run();
            assertEquals("done once", 1, RequestGate.running());

            // A task handed on, counted by the executor that took it first,
            // is that executor's to drop.
            Runnable outer = RequestGate.hand(null, task);
            assertEquals(2, RequestGate.running());
            RequestGate.dropped(outer, outer);
            RequestGate.dropped(task, task);
            RequestGate.dropped(null, task);
            assertEquals(2, RequestGate.running());
            outer.run();
            assertEquals(1, RequestGate.running());
        } finally {
            RequestGate.exit(request);
        }
        assertEquals(0, RequestGate.running());
    }

    @Test
    public void aFutureHandedOffStaysOneForTheExecutorsCancel() throws Exception {
        Object request = RequestGate.enter();
        FutureTask<String> future = new FutureTask<>(() -> "x");
        FutureTask<String> cancelled = new FutureTask<>(() -> "y");
        Runnable handed;
        Runnable handedCancelled;
        try {
            handed = RequestGate.hand(null, future);
            handedCancelled = RequestGate.hand(null, cancelled);
        } finally {
            RequestGate.exit(request);
        }
        assertTrue(handed instanceof Future);
        Future<?> asFuture = (Future<?>) handed;
        assertFalse(asFuture.isDone());
        handed.run();
        assertTrue(asFuture.isDone());
        assertEquals("x", asFuture.get());
        assertEquals("x", asFuture.get(1, TimeUnit.SECONDS));
        assertFalse(asFuture.isCancelled());

        Future<?> other = (Future<?>) handedCancelled;
        assertTrue(other.cancel(false));
        assertTrue(other.isCancelled() && cancelled.isCancelled());
        // The executor still runs it (ThreadPoolExecutor runs a cancelled
        // task it has queued): done then.
        assertEquals(1, RequestGate.running());
        handedCancelled.run();
        assertEquals(0, RequestGate.running());
        assertFalse("a plain task is no Future", RequestGate.hand(null, (Runnable) () -> { }) instanceof Future);
    }

    @Test
    public void aRequestInARequestGivesItsThreadBackItsOuterWindow() {
        Object outer = RequestGate.enter();
        Runnable task = () -> { };
        try {
            RequestGate.open();
            Object inner = RequestGate.enter();
            RequestGate.exit(inner);
            assertEquals(0, RequestGate.running());
            Runnable handed = RequestGate.hand(null, task);
            assertNotSame("still inside the outer window", task, handed);
            assertEquals("counted in the outer window, not the new one", 0, RequestGate.running());
            handed.run();
        } finally {
            RequestGate.exit(outer);
        }
        assertSame(task, RequestGate.hand(null, task));
    }

    @Test
    public void aTaskRunOnTheRequestsOwnThreadLeavesItInsideTheWindow() {
        // An executor that runs a task in the caller's thread (a
        // CallerRunsPolicy pool full, a SyncTaskExecutor).
        Object request = RequestGate.enter();
        Runnable task = () -> { };
        try {
            RequestGate.hand(null, task).run();
            assertEquals(1, RequestGate.running());
            Runnable handed = RequestGate.hand(null, task);
            assertNotSame("still inside the request's window", task, handed);
            handed.run();
            assertEquals(1, RequestGate.running());
        } finally {
            RequestGate.exit(request);
        }
        assertEquals(0, RequestGate.running());
    }

    // A wait that never ends fails the row, not the run.
    @Test(timeout = 60000)
    public void endWaitsForTheTaskARequestHandedOffAndCutsItAtTheBound() throws Exception {
        Object request = RequestGate.enter();
        CountDownLatch go = new CountDownLatch(1);
        Runnable handed;
        try {
            handed = RequestGate.hand(null, () -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } finally {
            RequestGate.exit(request);
        }
        Thread pool = new Thread(handed);
        pool.start();
        assertFalse("still running at the bound", RequestGate.await(TimeUnit.MILLISECONDS.toNanos(100)));
        go.countDown();
        pool.join(10000);

        // And when the task ends within the bound, END sees it end.
        RequestGate.open();
        request = RequestGate.enter();
        CountDownLatch go2 = new CountDownLatch(1);
        try {
            handed = RequestGate.hand(null, () -> {
                try {
                    go2.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } finally {
            RequestGate.exit(request);
        }
        Thread pool2 = new Thread(handed);
        pool2.start();
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            }
            go2.countDown();
        });
        releaser.start();
        long began = System.nanoTime();
        assertTrue(RequestGate.await(TimeUnit.SECONDS.toNanos(30)));
        assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(20));
        pool2.join(10000);
        releaser.join(10000);
    }

    @Test
    public void aTaskForAPoolThatMayOrderItGoesAsItIsAndAnyOtherIsCounted() throws Exception {
        // Each executor's method hands RequestGate the executor its task
        // is for: its executor argument (@Async's doSubmit), else itself.
        // A pool whose queue may order its tasks takes them as they are,
        // in a request's window and in a job's run alike: wrapped, its
        // Comparator threw ClassCastException in execute.
        ThreadPoolExecutor ordered = pool(new PriorityBlockingQueue<>());
        ThreadPoolExecutor inOrder = pool(new LinkedBlockingQueue<>());
        for (String name : new String[] {SIMPLE, POOL, ASYNC}) {
            Class<?> type = load(name, new ServerEntries().transform(getClass().getClassLoader(), name, null, null,
                    executorStandIn(name, Opcodes.V1_8)));
            Object executor = type.getDeclaredConstructor().newInstance();
            Method runnable = type.getMethod(method(name), Runnable.class, long.class);
            Method callable = type.getMethod(method(name), Callable.class);
            Method forExecutor = type.getMethod(method(name), Callable.class, AsyncTaskExecutor.class, Class.class);
            Method onStatic = type.getMethod(method(name), long.class, Runnable.class);
            Runnable task = () -> { };
            Callable<String> call = () -> "x";
            for (boolean job : new boolean[] {false, true}) {
                String label = name + (job ? " in a job's run" : " in a request's window");
                RequestGate.open();
                Object request = job ? JobGate.enter(new Object(), "scheduled") : RequestGate.enter();
                try {
                    standInPool = ordered;
                    runnable.invoke(executor, task, 7L);
                    assertSame(label + ": its own pool may order it", task, lastTask);
                    callable.invoke(executor, call);
                    assertSame(label + ": its own pool may order it", call, lastTask);
                    forExecutor.invoke(executor, call, new Executor(inOrder), String.class);
                    assertNotSame(label + ": the executor argument's pool takes it in order", call, lastTask);
                    ((Callable<?>) lastTask).call();
                    standInPool = inOrder;
                    forExecutor.invoke(executor, call, new Executor(ordered), String.class);
                    assertSame(label + ": the executor argument's pool may order it", call, lastTask);
                    runnable.invoke(executor, task, 7L);
                    assertNotSame(label + ": its own pool takes it in order", task, lastTask);
                    ((Runnable) lastTask).run();
                    callable.invoke(executor, call);
                    assertNotSame(label + ": its own pool takes it in order", call, lastTask);
                    ((Callable<?>) lastTask).call();
                    onStatic.invoke(null, 7L, task);
                    assertNotSame(label + ": a static method's task is for no pool", task, lastTask);
                    ((Runnable) lastTask).run();
                    assertEquals(label + ": only the request runs", job ? 0 : 1, RequestGate.running());
                } finally {
                    if (job) {
                        JobGate.exit(request);
                    } else {
                        RequestGate.exit(request);
                    }
                }
            }
        }
    }

    @Test
    public void aPoolMayOrderItsTasksUnlessItsQueueIsAJdkQueueInOrder() {
        Logger logger = Logger.getLogger(KeployDedupAgent.class.getName());
        List<String> logged = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getLevel() + " " + record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            assertFalse("no executor", OrderedPools.orders(null));
            for (BlockingQueue<Runnable> queue : Arrays.<BlockingQueue<Runnable>>asList(new LinkedBlockingQueue<>(),
                    new SynchronousQueue<>(), new ArrayBlockingQueue<>(1), new LinkedBlockingDeque<>(),
                    new LinkedTransferQueue<>())) {
                assertFalse(queue.getClass().getName(), OrderedPools.orders(new Executor(pool(queue))));
            }
            ThreadPoolTaskExecutor spring = new ThreadPoolTaskExecutor();
            spring.initialize();
            try {
                assertFalse("Spring's own pool", OrderedPools.orders(spring));
            } finally {
                spring.shutdown();
            }
            assertEquals(logged.toString(), 0, logged.size());

            assertTrue("unknown object fails closed", OrderedPools.orders(new Object()));
            logged.clear();

            assertTrue(OrderedPools.orders(new Priority(pool(new PriorityBlockingQueue<>()))));
            assertTrue("said once", OrderedPools.orders(new Priority(pool(new PriorityBlockingQueue<>()))));
            assertEquals(Collections.singletonList("INFO Keploy dedup does not count the tasks handed to "
                    + Priority.class.getName() + ": its pool's queue, a java.util.concurrent.PriorityBlockingQueue,"
                    + " may order its tasks by their own type, so each reaches it as it is; a test's END does not"
                    + " wait for one a request hands it, and one a scheduled job's run hands it marks no test"),
                    logged);
            logged.clear();
            assertTrue("a subclass of a queue in order", OrderedPools.orders(
                    new Subclassed(pool(new LinkedBlockingQueue<Runnable>() { }))));
            assertTrue(logged.toString(), logged.size() == 1 && logged.get(0).contains(": its pool's queue, a "
                    + AsyncHandTest.class.getName() + "$"));
            logged.clear();
            // Not initialized: its own call throws, and the log says
            // nothing, so a class initialized later is not said uncounted.
            assertTrue("not initialized", OrderedPools.orders(new Uninitialized()));
            assertEquals(logged.toString(), 0, logged.size());
            assertTrue("no pool", OrderedPools.orders(new NoPool()));
            assertEquals(1, logged.size());
            assertTrue(logged.get(0), logged.get(0).contains(NoPool.class.getName() + ": its pool's queue cannot be"
                    + " read (java.lang.NullPointerException"));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    public void nothingThrownReadingAPoolReachesTheAppsCall() throws Exception {
        // Each executor's pool cannot be read: its class names a type that
        // is missing (getMethod throws NoClassDefFoundError), its
        // getThreadPoolExecutor() or its pool's getQueue() throws, or its
        // queue is null. Its tasks go as they are, the log says so once,
        // and the app's call sees nothing thrown. A class loader's
        // RuntimeException is the class's for good, as a missing type is. An
        // app's own getThreadPoolExecutor() that throws IllegalStateException
        // is said too: only Spring's says its pool is not initialized, and
        // only by that. A getThreadPoolExecutor() declared by a class named
        // as Spring's ThreadPoolTaskExecutor (a later or patched Spring)
        // that throws anything else, or whose pool's getQueue() throws
        // IllegalStateException itself, is said.
        Object[][] cases = {
            {missingTypeExecutor(), "java.lang.NoClassDefFoundError: org/example/pools/Absent"},
            {new PoolError(), "java.lang.NoClassDefFoundError: missing/Pool"},
            {loaderExecutor("LoaderRuntime", new IllegalArgumentException("loader")),
                "java.lang.IllegalArgumentException: loader"},
            {new PoolUnsupported(), "java.lang.UnsupportedOperationException"},
            {new PoolHidden(), "java.lang.IllegalStateException: hidden"},
            {springNamed("SpringNamedUnsupported", false), "java.lang.UnsupportedOperationException"},
            {springNamed("SpringNamedClosed", true), "java.lang.IllegalStateException: closed"},
            {new QueueError(), "java.lang.NoClassDefFoundError: missing/Queue"},
            {new QueueState(), "java.lang.IllegalStateException: closed"},
            {new QueueNull(), "java.lang.NullPointerException"},
        };
        Logger logger = Logger.getLogger(KeployDedupAgent.class.getName());
        List<String> logged = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getLevel() + " " + record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        Object request = RequestGate.enter();
        try {
            for (Object[] each : cases) {
                Object executor = each[0];
                String label = executor.getClass().getName();
                for (int i = 0; i < 2; i++) {
                    Runnable task = () -> { };
                    Callable<String> call = () -> "x";
                    try {
                        assertSame(label, task, RequestGate.hand(executor, task));
                        assertSame(label, call, RequestGate.hand(executor, call));
                    } catch (AssertionError e) {
                        throw e;
                    } catch (Throwable e) {
                        throw new AssertionError(label + ": the app's call threw " + e, e);
                    }
                }
                assertEquals(label + ": said once " + logged, 1, logged.size());
                assertTrue(logged.get(0), logged.get(0).startsWith("INFO Keploy dedup does not count the tasks handed"
                        + " to " + label + ": its pool's queue cannot be read (" + each[1]));
                logged.clear();
            }
            assertEquals("only the request runs", 1, RequestGate.running());
        } finally {
            RequestGate.exit(request);
            logger.removeHandler(handler);
        }
    }

    @Test
    public void aFailureForNowIsNeitherKeptNorSaid() throws Exception {
        // Reading each executor's pool runs out of stack once: in its
        // getThreadPoolExecutor(), or in its class loader as getMethod
        // resolves a type its class names. That task goes as it is, the log
        // says nothing, and the next task the executor is handed is counted.
        Logger logger = Logger.getLogger(KeployDedupAgent.class.getName());
        List<String> logged = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getLevel() + " " + record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        Object request = RequestGate.enter();
        try {
            for (Object executor : new Object[] {new PoolOnce(),
                loaderExecutor("LoaderStack", new StackOverflowError("for now"))}) {
                String label = executor.getClass().getName();
                Runnable first = () -> { };
                assertSame(label + ": out of stack, as it is", first, handed(executor, first));
                assertEquals(label + ": nothing said " + logged, 0, logged.size());
                Runnable task = () -> { };
                Callable<String> call = () -> "x";
                Runnable counted = handed(executor, task);
                Callable<String> countedCall = handed(executor, call);
                assertNotSame(label + ": the next task is counted", task, counted);
                assertNotSame(label + ": the next task is counted", call, countedCall);
                assertEquals(label, 3, RequestGate.running());
                RequestGate.dropped(counted, task);
                RequestGate.dropped(countedCall, call);
                assertEquals(label + ": nothing said " + logged, 0, logged.size());
            }
            assertEquals("only the request runs", 1, RequestGate.running());
        } finally {
            RequestGate.exit(request);
            logger.removeHandler(handler);
        }
    }

    @Test
    public void nothingTheAppsOwnCodeThrowsReachesItsCall() throws Exception {
        // QueueRude's getQueue() throws an exception of the app's whose
        // toString() throws; LoudPriority's pool orders its tasks, and the
        // app's log Handler throws on the line that says so. Each task goes
        // as it is, and the app's call sees nothing thrown.
        Logger logger = Logger.getLogger(KeployDedupAgent.class.getName());
        Handler loud = new Handler() {
            @Override
            public void publish(LogRecord record) {
                throw new IllegalStateException("the app's handler");
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(loud);
        Object request = RequestGate.enter();
        try {
            for (Object executor : new Object[] {new QueueRude(),
                new LoudPriority(pool(new PriorityBlockingQueue<>()))}) {
                String label = executor.getClass().getName();
                for (int i = 0; i < 2; i++) {
                    Runnable task = () -> { };
                    Callable<String> call = () -> "x";
                    assertSame(label, task, handed(executor, task));
                    assertSame(label, call, handed(executor, call));
                }
            }
            assertEquals("only the request runs", 1, RequestGate.running());
        } finally {
            RequestGate.exit(request);
            logger.removeHandler(loud);
        }
    }

    // The app's call to its executor: anything hand throws fails the row.
    private static Runnable handed(Object executor, Runnable task) {
        try {
            return RequestGate.hand(executor, task);
        } catch (Throwable e) {
            throw new AssertionError(executor.getClass().getName() + ": the app's call threw " + e, e);
        }
    }

    private static <T> Callable<T> handed(Object executor, Callable<T> task) {
        try {
            return RequestGate.hand(executor, task);
        } catch (Throwable e) {
            throw new AssertionError(executor.getClass().getName() + ": the app's call threw " + e, e);
        }
    }

    // An executor whose class's public method names a type its class
    // loader throws first on the first time it is asked for it, and loads
    // after that; its pool keeps its tasks in order.
    private static Object loaderExecutor(String simpleName, Throwable first) throws Exception {
        String name = "org.example.pools." + simpleName;
        String late = name + "Late";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        String parent = Type.getInternalName(InOrder.class);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name.replace('.', '/'), null, parent,
                null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor use = writer.visitMethod(Opcodes.ACC_PUBLIC, "use", "(L" + late.replace('.', '/') + ";)V", null,
                null);
        use.visitCode();
        use.visitInsn(Opcodes.RETURN);
        use.visitMaxs(0, 0);
        use.visitEnd();
        writer.visitEnd();
        byte[] type = writer.toByteArray();
        ClassWriter lateWriter = new ClassWriter(0);
        lateWriter.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, late.replace('.', '/'), null,
                "java/lang/Object", null);
        lateWriter.visitEnd();
        byte[] lateType = lateWriter.toByteArray();
        AtomicBoolean failed = new AtomicBoolean();
        ClassLoader loader = new ClassLoader(AsyncHandTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String binary) throws ClassNotFoundException {
                if (binary.equals(name)) {
                    return defineClass(binary, type, 0, type.length);
                }
                if (binary.equals(late)) {
                    if (failed.compareAndSet(false, true)) {
                        if (first instanceof Error) {
                            throw (Error) first;
                        }
                        throw (RuntimeException) first;
                    }
                    return defineClass(binary, lateType, 0, lateType.length);
                }
                return super.findClass(binary);
            }
        };
        Object executor = loader.loadClass(name).getDeclaredConstructor().newInstance();
        assertFalse("not loaded yet", failed.get());
        return executor;
    }

    // An executor whose class has a public getThreadPoolExecutor() and a
    // public method that names a type no class loader has.
    private static Object missingTypeExecutor() throws Exception {
        String name = "org/example/pools/MissingType";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor pool = writer.visitMethod(Opcodes.ACC_PUBLIC, "getThreadPoolExecutor",
                "()Ljava/util/concurrent/ThreadPoolExecutor;", null, null);
        pool.visitCode();
        pool.visitInsn(Opcodes.ACONST_NULL);
        pool.visitInsn(Opcodes.ARETURN);
        pool.visitMaxs(0, 0);
        pool.visitEnd();
        MethodVisitor use = writer.visitMethod(Opcodes.ACC_PUBLIC, "use", "(Lorg/example/pools/Absent;)V", null, null);
        use.visitCode();
        use.visitInsn(Opcodes.RETURN);
        use.visitMaxs(0, 0);
        use.visitEnd();
        writer.visitEnd();
        return load(name, writer.toByteArray()).getDeclaredConstructor().newInstance();
    }

    // An executor of class org.example.pools.simpleName, whose public
    // getThreadPoolExecutor() is declared by its superclass, a class named
    // as Spring's ThreadPoolTaskExecutor that a loader of its own defines
    // (Spring's own is never that class). The method throws
    // UnsupportedOperationException, or, when closed, returns a pool whose
    // getQueue() throws IllegalStateException itself.
    private static Object springNamed(String simpleName, boolean closed) throws Exception {
        String spring = "org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor";
        String name = "org.example.pools." + simpleName;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, spring.replace('.', '/'), null,
                "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor pool = writer.visitMethod(Opcodes.ACC_PUBLIC, "getThreadPoolExecutor",
                "()Ljava/util/concurrent/ThreadPoolExecutor;", null, null);
        pool.visitCode();
        if (closed) {
            pool.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(AsyncHandTest.class), "closedPool",
                    "()Ljava/util/concurrent/ThreadPoolExecutor;", false);
            pool.visitInsn(Opcodes.ARETURN);
        } else {
            String thrown = Type.getInternalName(UnsupportedOperationException.class);
            pool.visitTypeInsn(Opcodes.NEW, thrown);
            pool.visitInsn(Opcodes.DUP);
            pool.visitMethodInsn(Opcodes.INVOKESPECIAL, thrown, "<init>", "()V", false);
            pool.visitInsn(Opcodes.ATHROW);
        }
        pool.visitMaxs(0, 0);
        pool.visitEnd();
        writer.visitEnd();
        byte[] springType = writer.toByteArray();
        ClassWriter subWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        subWriter.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name.replace('.', '/'), null,
                spring.replace('.', '/'), null);
        MethodVisitor subInit = subWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        subInit.visitCode();
        subInit.visitVarInsn(Opcodes.ALOAD, 0);
        subInit.visitMethodInsn(Opcodes.INVOKESPECIAL, spring.replace('.', '/'), "<init>", "()V", false);
        subInit.visitInsn(Opcodes.RETURN);
        subInit.visitMaxs(0, 0);
        subInit.visitEnd();
        subWriter.visitEnd();
        byte[] subType = subWriter.toByteArray();
        // Child-first for the two names: the subclass's superclass must be
        // this loader's stand-in, not Spring's own from the parent.
        ClassLoader loader = new ClassLoader(AsyncHandTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String binary, boolean resolve) throws ClassNotFoundException {
                byte[] type = binary.equals(spring) ? springType : binary.equals(name) ? subType : null;
                if (type == null) {
                    return super.loadClass(binary, resolve);
                }
                synchronized (getClassLoadingLock(binary)) {
                    Class<?> found = findLoadedClass(binary);
                    if (found == null) {
                        found = defineClass(binary, type, 0, type.length);
                    }
                    if (resolve) {
                        resolveClass(found);
                    }
                    return found;
                }
            }
        };
        Object executor = loader.loadClass(name).getDeclaredConstructor().newInstance();
        Class<?> declaring = executor.getClass().getMethod("getThreadPoolExecutor").getDeclaringClass();
        assertEquals("named as Spring's", spring, declaring.getName());
        assertNotSame("not Spring's own", ThreadPoolTaskExecutor.class, declaring);
        return executor;
    }

    // The pool the closed stand-in's getThreadPoolExecutor() returns. Public:
    // the stand-in is in another runtime package.
    public static ThreadPoolExecutor closedPool() {
        return new Broken(new IllegalStateException("closed"), null);
    }

    // A pool whose getQueue() throws what it is made with, or returns null.
    private static final class Broken extends ThreadPoolExecutor {

        private final RuntimeException runtime;
        private final Error error;

        Broken(RuntimeException runtime, Error error) {
            super(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
            this.runtime = runtime;
            this.error = error;
        }

        @Override
        public BlockingQueue<Runnable> getQueue() {
            if (runtime != null) {
                throw runtime;
            }
            if (error != null) {
                throw error;
            }
            return null;
        }
    }

    public static final class PoolError extends Executor {

        private static final long serialVersionUID = 1L;

        PoolError() {
            super(null);
        }

        @Override
        public ThreadPoolExecutor getThreadPoolExecutor() {
            throw new NoClassDefFoundError("missing/Pool");
        }
    }

    public static final class PoolUnsupported extends Executor {

        private static final long serialVersionUID = 1L;

        PoolUnsupported() {
            super(null);
        }

        @Override
        public ThreadPoolExecutor getThreadPoolExecutor() {
            throw new UnsupportedOperationException();
        }
    }

    // An app's executor that hides its pool the way Spring's does before
    // it is initialized, but is not Spring's.
    public static final class PoolHidden extends Executor {

        private static final long serialVersionUID = 1L;

        PoolHidden() {
            super(null);
        }

        @Override
        public ThreadPoolExecutor getThreadPoolExecutor() {
            throw new IllegalStateException("hidden");
        }
    }

    // An executor whose getThreadPoolExecutor() runs out of stack the
    // first time, and returns its pool, in order, after that.
    public static final class PoolOnce extends Executor {

        private static final long serialVersionUID = 1L;
        private final AtomicBoolean thrown = new AtomicBoolean();

        PoolOnce() {
            super(pool(new LinkedBlockingQueue<>()));
        }

        @Override
        public ThreadPoolExecutor getThreadPoolExecutor() {
            if (thrown.compareAndSet(false, true)) {
                throw new StackOverflowError("for now");
            }
            return super.getThreadPoolExecutor();
        }
    }

    // An executor whose pool keeps its tasks in order, for a class of
    // another package to extend.
    public static class InOrder extends Executor {

        private static final long serialVersionUID = 1L;

        public InOrder() {
            super(pool(new LinkedBlockingQueue<>()));
        }
    }

    // An exception of the app's whose message, and so its toString(),
    // throws.
    static final class Rude extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private String detail;

        @Override
        public String getMessage() {
            return detail.trim();
        }
    }

    public static final class QueueRude extends Executor {

        private static final long serialVersionUID = 1L;

        QueueRude() {
            super(new Broken(new Rude(), null));
        }
    }

    public static final class LoudPriority extends Executor {

        private static final long serialVersionUID = 1L;

        LoudPriority(ThreadPoolExecutor pool) {
            super(pool);
        }
    }

    public static final class QueueError extends Executor {

        private static final long serialVersionUID = 1L;

        QueueError() {
            super(new Broken(null, new NoClassDefFoundError("missing/Queue")));
        }
    }

    public static final class QueueState extends Executor {

        private static final long serialVersionUID = 1L;

        QueueState() {
            super(new Broken(new IllegalStateException("closed"), null));
        }
    }

    public static final class QueueNull extends Executor {

        private static final long serialVersionUID = 1L;

        QueueNull() {
            super(new Broken(null, null));
        }
    }

    private static ThreadPoolExecutor pool(BlockingQueue<Runnable> queue) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, queue);
    }

    // Executor is an executor whose pool is the one it is made with, as
    // Spring's ThreadPoolTaskExecutor's.
    public static class Executor extends SimpleAsyncTaskExecutor {

        private static final long serialVersionUID = 1L;
        private final ThreadPoolExecutor pool;

        Executor(ThreadPoolExecutor pool) {
            this.pool = pool;
        }

        public ThreadPoolExecutor getThreadPoolExecutor() {
            return pool;
        }
    }

    public static final class Priority extends Executor {

        private static final long serialVersionUID = 1L;

        Priority(ThreadPoolExecutor pool) {
            super(pool);
        }
    }

    public static final class Subclassed extends Executor {

        private static final long serialVersionUID = 1L;

        Subclassed(ThreadPoolExecutor pool) {
            super(pool);
        }
    }

    public static final class Uninitialized extends ThreadPoolTaskExecutor {

        private static final long serialVersionUID = 1L;
    }

    public static final class NoPool extends Executor {

        private static final long serialVersionUID = 1L;

        NoPool() {
            super(null);
        }
    }

    @Test
    public void eachExecutorsMethodHandsItsTaskAndDropsItWhenItThrows() throws Exception {
        for (String name : new String[] {SIMPLE, POOL, ASYNC}) {
            for (int version : new int[] {Opcodes.V1_8, Opcodes.V1_5}) {
                String label = name + " (class version " + version + ")";
                ServerEntries entries = new ServerEntries();
                byte[] changed = entries.transform(getClass().getClassLoader(), name, null, null,
                        executorStandIn(name, version));
                assertNotNull(label, changed);
                assertTrue(label, ServerEntries.carriesChange(changed));
                Class<?> type = load(name, changed);
                Object executor = type.getDeclaredConstructor().newInstance();
                Method runnable = type.getMethod(method(name), Runnable.class, long.class);
                Method callable = type.getMethod(method(name), Callable.class);
                Method none = type.getMethod(method(name), String.class);
                assertTrue(label, Modifier.isPrivate(type.getDeclaredMethod(ServerEntries.ASYNC_RENAMED + method(name),
                        Runnable.class, long.class).getModifiers()));
                RequestGate.open();

                Runnable task = () -> { };
                Callable<String> call = () -> "x";
                runnable.invoke(executor, task, 7L);
                assertSame(label + ": off a request's thread, as handed", task, lastTask);
                assertEquals(0, RequestGate.running());

                Object request = RequestGate.enter();
                Runnable handedTask;
                Callable<?> handedCall;
                try {
                    runnable.invoke(executor, task, 7L);
                    handedTask = (Runnable) lastTask;
                    assertNotSame(label, task, handedTask);
                    assertEquals(label, 7L, lastLong);
                    assertEquals(label, "taken", callable.invoke(executor, call));
                    handedCall = (Callable<?>) lastTask;
                    assertNotSame(label, call, handedCall);
                    assertEquals(label + ": the request and its two tasks", 3, RequestGate.running());
                    assertEquals(label, "y", none.invoke(executor, "y"));
                    assertEquals(label, "y", lastString);

                    executorThrows = true;
                    try {
                        runnable.invoke(executor, task, 7L);
                        fail(label);
                    } catch (InvocationTargetException e) {
                        assertTrue(label, e.getCause() instanceof RejectedExecutionException);
                    }
                    try {
                        callable.invoke(executor, call);
                        fail(label);
                    } catch (InvocationTargetException e) {
                        assertTrue(label, e.getCause() instanceof RejectedExecutionException);
                    }
                    executorThrows = false;
                    assertEquals(label + ": a task the executor threw on is not waited for", 3,
                            RequestGate.running());
                } finally {
                    RequestGate.exit(request);
                }
                handedTask.run();
                assertEquals(label, "x", handedCall.call());
                assertEquals(label, 0, RequestGate.running());
                assertFalse("an executor is no server", ServerEntries.counted().contains("Spring"));
            }
        }
    }

    @Test
    public void withEndsWaitOffEachExecutorCountsOnlyAJobsHandOffs() throws Exception {
        // With the wait off no server is changed, so no thread runs a
        // request; a task a job's run hands off still counts as that run.
        for (String name : new String[] {SIMPLE, POOL, ASYNC}) {
            byte[] changed = new ServerEntries(false).transform(getClass().getClassLoader(), name, null, null,
                    executorStandIn(name, Opcodes.V1_8));
            assertNotNull(name, changed);
            Class<?> type = load(name, changed);
            Object executor = type.getDeclaredConstructor().newInstance();
            Method runnable = type.getMethod(method(name), Runnable.class, long.class);
            Runnable task = () -> { };
            runnable.invoke(executor, task, 7L);
            assertSame(name + ": off a job's run, as handed", task, lastTask);
            Object run = JobGate.enter(new Object(), "scheduled");
            try {
                runnable.invoke(executor, task, 7L);
                assertNotSame(name + ": a job's hand-off", task, lastTask);
            } finally {
                JobGate.exit(run);
            }
            assertEquals(name + ": END waits for no job's task", 0, RequestGate.running());
            JobGate.open();
            ((Runnable) lastTask).run();
            assertEquals(name, Collections.singletonList("scheduled:java.lang.Object"), JobGate.close());
        }
    }

    @Test
    public void anExecutorChangedAsItLoadedIsChangedAgainWhenRetransformed() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        ServerEntries entries = new ServerEntries();
        byte[] classfile = executorStandIn(POOL, Opcodes.V1_8);
        assertNull("not changed as it loaded", entries.transform(loader, POOL, Object.class, null, classfile));
        assertNotNull(entries.transform(loader, POOL, null, null, classfile));
        byte[] again = entries.transform(loader, POOL, Object.class, null, classfile);
        assertNotNull(again);
        assertTrue(ServerEntries.carriesChange(again));
        assertNull("bytes that carry the change are taken as they are",
                entries.transform(loader, POOL, Object.class, null, again));
    }

    // method is the name the stand-in of an executor class gives its
    // methods: one of the names its entry counts.
    private static String method(String name) {
        return name.equals(SIMPLE) ? "doExecute" : name.equals(POOL) ? "submit" : "doSubmit";
    }

    // The stand-ins' own code: it keeps what it was handed, and, told to,
    // throws as an executor that rejects a task.
    public static void tookRunnable(Runnable task, long timeout) {
        lastTask = task;
        lastLong = timeout;
        if (executorThrows) {
            throw new RejectedExecutionException("rejected");
        }
    }

    public static Object tookCallable(Callable<?> task) {
        lastTask = task;
        if (executorThrows) {
            throw new RejectedExecutionException("rejected");
        }
        return "taken";
    }

    public static Object tookForExecutor(Callable<?> task, AsyncTaskExecutor executor, Class<?> returns) {
        return tookCallable(task);
    }

    public static void tookStatic(long timeout, Runnable task) {
        tookRunnable(task, timeout);
    }

    public static ThreadPoolExecutor standInPool() {
        return standInPool;
    }

    public static Object tookString(String value) {
        lastString = value;
        return value;
    }

    // executorStandIn is a class named as an executor's, with methods of
    // the name its entry counts: one handed a Runnable and a long, one a
    // Callable, one no task (left as it is), one a Callable and the
    // executor it is for, and a static one a long and a Runnable; and a
    // getThreadPoolExecutor() that returns standInPool.
    static byte[] executorStandIn(String internalName, int version) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(version, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        String name = method(internalName);
        forwards(writer, Opcodes.ACC_PUBLIC, name, "(Ljava/lang/Runnable;J)V", "tookRunnable");
        forwards(writer, Opcodes.ACC_PUBLIC, name, "(Ljava/util/concurrent/Callable;)Ljava/lang/Object;",
                "tookCallable");
        forwards(writer, Opcodes.ACC_PUBLIC, name, "(Ljava/lang/String;)Ljava/lang/Object;", "tookString");
        forwards(writer, Opcodes.ACC_PUBLIC, name, "(Ljava/util/concurrent/Callable;"
                + "Lorg/springframework/core/task/AsyncTaskExecutor;Ljava/lang/Class;)Ljava/lang/Object;",
                "tookForExecutor");
        forwards(writer, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "(JLjava/lang/Runnable;)V", "tookStatic");
        forwards(writer, Opcodes.ACC_PUBLIC, "getThreadPoolExecutor", "()Ljava/util/concurrent/ThreadPoolExecutor;",
                "standInPool");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void forwards(ClassWriter writer, int access, String name, String descriptor, String target) {
        MethodVisitor mv = writer.visitMethod(access, name, descriptor, null, new String[] {"java/lang/Exception"});
        mv.visitCode();
        int slot = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (Type arg : Type.getArgumentTypes(descriptor)) {
            mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
            slot += arg.getSize();
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(AsyncHandTest.class), target, descriptor,
                false);
        mv.visitInsn(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN));
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static Class<?> load(String internalName, byte[] classfile) {
        return new ClassLoader(AsyncHandTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(internalName.replace('/', '.'), classfile, 0, classfile.length);
            }
        }.define();
    }
}
