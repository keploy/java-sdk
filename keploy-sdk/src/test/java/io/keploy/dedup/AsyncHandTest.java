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
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

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

    @Before
    public void setUp() {
        RequestGate.open();
        lastTask = null;
        executorThrows = false;
    }

    @After
    public void tearDown() {
        // No row leaves its thread inside a window.
        Runnable task = () -> { };
        assertSame("the test thread is outside every window", task, RequestGate.hand(task));
    }

    @Test
    public void aTaskARequestHandsOffCountsUntilItIsDone() {
        Object request = RequestGate.enter();
        Runnable handed;
        AtomicReference<Runnable> nested = new AtomicReference<>();
        try {
            handed = RequestGate.hand(() -> {
                if (nested.get() == null) {
                    nested.set(RequestGate.hand(() -> { }));
                }
            });
            assertEquals("the request and its task", 2, RequestGate.running());
            assertSame("counted once", handed, RequestGate.hand(handed));
            // No task: passed on for the executor to throw on, as before.
            assertNull(RequestGate.hand((Runnable) null));
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
        assertSame(task, RequestGate.hand(task));
        assertSame(call, RequestGate.hand(call));
        assertNull(RequestGate.hand((Runnable) null));
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
                assertSame("a request's", last, RequestGate.hand(last));
                assertSame("a request's", call, RequestGate.hand(call));
                pool.execute(RequestGate.hand(last));
                assertEquals("not counted: END does not wait for it", 1, RequestGate.running());
            } finally {
                RequestGate.exit(request);
            }
            Object run = JobGate.enter(new Object(), "scheduled");
            try {
                assertSame("a job's", first, RequestGate.hand(first));
                assertSame("a job's", call, RequestGate.hand(call));
                pool.execute(RequestGate.hand(first));
            } finally {
                JobGate.exit(run);
            }
            pool.execute(RequestGate.hand(second));
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
            value = RequestGate.hand(() -> "x");
            throwing = RequestGate.hand(() -> {
                throw new IllegalStateException("thrown");
            });
            assertSame(value, RequestGate.hand(value));
            assertNull(RequestGate.hand((Callable<String>) null));
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
            handed = RequestGate.hand((Runnable) () -> {
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
            after.set(RequestGate.hand(probe));
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
            Runnable handed = RequestGate.hand(task);
            assertEquals(2, RequestGate.running());
            RequestGate.dropped(handed, task);
            assertEquals(1, RequestGate.running());
            RequestGate.dropped(handed, task);
            handed.run();
            assertEquals("done once", 1, RequestGate.running());

            // A task handed on, counted by the executor that took it first,
            // is that executor's to drop.
            Runnable outer = RequestGate.hand(task);
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
            handed = RequestGate.hand(future);
            handedCancelled = RequestGate.hand(cancelled);
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
        assertFalse("a plain task is no Future", RequestGate.hand((Runnable) () -> { }) instanceof Future);
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
            Runnable handed = RequestGate.hand(task);
            assertNotSame("still inside the outer window", task, handed);
            assertEquals("counted in the outer window, not the new one", 0, RequestGate.running());
            handed.run();
        } finally {
            RequestGate.exit(outer);
        }
        assertSame(task, RequestGate.hand(task));
    }

    @Test
    public void aTaskRunOnTheRequestsOwnThreadLeavesItInsideTheWindow() {
        // An executor that runs a task in the caller's thread (a
        // CallerRunsPolicy pool full, a SyncTaskExecutor).
        Object request = RequestGate.enter();
        Runnable task = () -> { };
        try {
            RequestGate.hand(task).run();
            assertEquals(1, RequestGate.running());
            Runnable handed = RequestGate.hand(task);
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
            handed = RequestGate.hand(() -> {
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
            handed = RequestGate.hand(() -> {
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

    public static Object tookString(String value) {
        lastString = value;
        return value;
    }

    // executorStandIn is a class named as an executor's, with three
    // methods of the name its entry counts: one handed a Runnable and a
    // long, one a Callable, and one no task (left as it is).
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
        forwards(writer, name, "(Ljava/lang/Runnable;J)V", "tookRunnable");
        forwards(writer, name, "(Ljava/util/concurrent/Callable;)Ljava/lang/Object;", "tookCallable");
        forwards(writer, name, "(Ljava/lang/String;)Ljava/lang/Object;", "tookString");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void forwards(ClassWriter writer, String name, String descriptor, String target) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null,
                new String[] {"java/lang/Exception"});
        mv.visitCode();
        int slot = 1;
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
