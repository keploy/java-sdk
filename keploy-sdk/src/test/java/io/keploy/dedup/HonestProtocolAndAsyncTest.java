package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Covers the v3 honest TCP protocol (HELLO capability declaration, NOP resync),
 * JDK executor call-site async-tail tracking (RequestGate + ServerEntries), and
 * Kafka/message listener contamination (JobGate + ServerEntries).
 */
public class HonestProtocolAndAsyncTest {

    private static final String ENDPOINT_PROPERTY = "keploy.coverage.endpoint";
    private static final String JACOCO_PORT_PROPERTY = "keploy.jacoco.port";
    private static final String WARMUP_DISABLED_PROPERTY = "keploy.java.dedup.warmup.disabled";

    private ServerSocket collector;
    private FakeJacoco jacoco;

    @Before
    public void setUp() {
        RequestGate.open();
        JobGate.close();
    }

    @After
    public void tearDown() throws IOException {
        KeployDedupAgent.stop();
        System.clearProperty(ENDPOINT_PROPERTY);
        System.clearProperty(JACOCO_PORT_PROPERTY);
        System.clearProperty(WARMUP_DISABLED_PROPERTY);
        if (collector != null) {
            collector.close();
            collector = null;
        }
        if (jacoco != null) {
            jacoco.close();
            jacoco = null;
        }
        JobGate.close();
        RequestGate.open();
    }

    @Test(timeout = 60000)
    public void tcpConnectDeclaresHonestyHelloAndAnswersNopResyncWithoutDisturbingActiveTest() throws Exception {
        collector = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        collector.setSoTimeout(30000);
        jacoco = new FakeJacoco();
        System.setProperty(ENDPOINT_PROPERTY, "127.0.0.1:" + collector.getLocalPort());
        System.setProperty(JACOCO_PORT_PROPERTY, String.valueOf(jacoco.server.getLocalPort()));
        System.setProperty(WARMUP_DISABLED_PROPERTY, "true");

        assertTrue(KeployDedupAgent.start());
        try (Socket sdk = collector.accept()) {
            sdk.setSoTimeout(30000);
            OutputStream out = sdk.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));

            String hello = in.readLine();
            assertEquals(KeployDedupAgent.HELLO_FRAME, hello);
            assertTrue(hello, hello.contains("\"proto\":3"));
            assertTrue(hello, hello.contains("\"windowCut\":true"));
            assertTrue(hello, hello.contains("\"contaminated\":true"));
            assertTrue(hello, hello.contains("\"classMismatch\":true"));
            assertTrue(hello, hello.contains("\"background\":true"));
            assertTrue(hello, hello.contains("\"ackId\":true"));
            assertTrue(hello, hello.contains("\"nopResync\":true"));

            // NOP before any test
            send(out, "NOP resync-1");
            assertEquals("ACK resync-1", in.readLine());

            // NOP mid-test must not clear activeTestId
            send(out, "START ts/test-1");
            assertEquals("ACK ts/test-1", in.readLine());
            send(out, "NOP resync-2");
            assertEquals("ACK resync-2", in.readLine());
            send(out, "END ts/test-1");
            String cov = in.readLine();
            assertNotNull(cov);
            assertTrue("expected COV for ts/test-1, got " + cov,
                    cov.startsWith("COV ") && cov.contains("\"id\":\"ts/test-1\""));
            assertEquals("ACK ts/test-1", in.readLine());
        }
    }

    @Test(timeout = 60000)
    public void jdkExecutorAndCompletableFutureAsyncTailsCountInRequestWindow() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 1. Executor.execute async tail outlives request -> END await returns false (windowCut)
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(1);
            Object req = RequestGate.enter();
            try {
                RequestGate.execute(pool, () -> {
                    started.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } finally {
                RequestGate.exit(req);
            }
            assertTrue(started.await(10, TimeUnit.SECONDS));
            assertEquals("async tail still counted after request exit", 1, RequestGate.running());
            assertFalse("END cuts window while async tail still runs",
                    RequestGate.await(TimeUnit.MILLISECONDS.toNanos(40)));
            release.countDown();

            // 2. ExecutorService.submit(Callable) and CompletableFuture.supplyAsync
            RequestGate.open();
            CountDownLatch release2 = new CountDownLatch(1);
            CountDownLatch started2 = new CountDownLatch(1);
            Object req2 = RequestGate.enter();
            Future<String> submitted;
            CompletableFuture<String> supplied;
            try {
                submitted = RequestGate.submit(pool, (Callable<String>) () -> {
                    started2.countDown();
                    release2.await(10, TimeUnit.SECONDS);
                    return "ok";
                });
                supplied = RequestGate.supplyAsync(() -> "done", pool);
            } finally {
                RequestGate.exit(req2);
            }
            assertTrue(started2.await(10, TimeUnit.SECONDS));
            assertFalse("END cuts while submitted Callable + supplyAsync still run",
                    RequestGate.await(TimeUnit.MILLISECONDS.toNanos(40)));
            release2.countDown();
            assertEquals("ok", submitted.get(10, TimeUnit.SECONDS));
            assertEquals("done", supplied.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test(timeout = 60000)
    public void rawPriorityThreadPoolExecutorIsNotWrappedByRequestGate() {
        ThreadPoolExecutor priority = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<Runnable>());
        try {
            assertTrue("OrderedPools must recognize raw ThreadPoolExecutor with PriorityBlockingQueue",
                    OrderedPools.orders(priority));
        } finally {
            priority.shutdownNow();
        }
    }

    @Test(timeout = 60000)
    public void serverEntriesRewritesAppExecutorCallSitesToRequestGate() throws Exception {
        // Generate an app class com/example/AsyncCaller whose fire(Executor, Runnable)
        // invokes INVOKEINTERFACE java/util/concurrent/Executor.execute(Runnable)V.
        String className = "com/example/AsyncCaller";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        MethodVisitor fire = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fire",
                "(Ljava/util/concurrent/Executor;Ljava/lang/Runnable;)V", null, null);
        fire.visitCode();
        fire.visitVarInsn(Opcodes.ALOAD, 0);
        fire.visitVarInsn(Opcodes.ALOAD, 1);
        fire.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/concurrent/Executor", "execute",
                "(Ljava/lang/Runnable;)V", true);
        fire.visitInsn(Opcodes.RETURN);
        fire.visitMaxs(2, 2);
        fire.visitEnd();
        cw.visitEnd();

        byte[] transformed = new ServerEntries(true).transform(
                getClass().getClassLoader(), className, null, null, cw.toByteArray());
        assertNotNull("ServerEntries must rewrite Executor.execute call site", transformed);

        Class<?> loaded = new ByteLoader(getClass().getClassLoader()).define("com.example.AsyncCaller", transformed);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Object req = RequestGate.enter();
            try {
                Runnable task = () -> {
                    started.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                };
                loaded.getMethod("fire", Executor.class, Runnable.class).invoke(null, pool, task);
            } finally {
                RequestGate.exit(req);
            }
            assertTrue(started.await(10, TimeUnit.SECONDS));
            assertEquals("rewritten Executor.execute must keep request window open", 1, RequestGate.running());
            assertFalse("END must detect async tail and report windowCut",
                    RequestGate.await(TimeUnit.MILLISECONDS.toNanos(40)));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test(timeout = 60000)
    public void kafkaListenerInvocationMarksWindowContaminated() throws Exception {
        String className = "org/springframework/kafka/listener/KafkaMessageListenerContainer$ListenerConsumer";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        MethodVisitor invoke = cw.visitMethod(Opcodes.ACC_PUBLIC, "invokeRecordListener", "()V", null, null);
        invoke.visitCode();
        invoke.visitInsn(Opcodes.RETURN);
        invoke.visitMaxs(0, 1);
        invoke.visitEnd();
        cw.visitEnd();

        byte[] transformed = new ServerEntries(true).transform(
                getClass().getClassLoader(), className, null, null, cw.toByteArray());
        assertNotNull("ServerEntries must wrap Kafka ListenerConsumer.invokeRecordListener", transformed);

        Class<?> loaded = new ByteLoader(getClass().getClassLoader())
                .define(className.replace('/', '.'), transformed);
        Object consumer = loaded.getDeclaredConstructor().newInstance();

        JobGate.open();
        loaded.getMethod("invokeRecordListener").invoke(consumer);
        List<String> contaminated = JobGate.close();
        assertEquals(1, contaminated.size());
        assertTrue("expected kafka: contamination entry, got " + contaminated,
                contaminated.get(0).startsWith("kafka:"));
    }

    private static void send(OutputStream out, String command) throws IOException {
        out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static final class ByteLoader extends ClassLoader {
        ByteLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
