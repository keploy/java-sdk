package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Covers the v3 honest TCP protocol (dynamic HELLO capabilities, backwards-compatible
 * bare ACK + opt-in ACK_ID, NOP/bare NOP resync, reconnect state reset, BACKGROUND
 * probe-scan-off behavior), strict app-only JDK executor call-site rewriting
 * (including branches/stack-map frames, retransform preservation, constant-pool
 * fast skip, cancel/timeout/discard/remove/shutdownNow safety, OrderedPools +
 * ConcurrentTaskExecutor + pass-through decorator guards, bounded job hand-off
 * attribution), and Kafka/RabbitMQ/JMS listener contamination.
 */
public class HonestProtocolAndAsyncTest {

    private static final String ENDPOINT_PROPERTY = "keploy.coverage.endpoint";
    private static final String JACOCO_PORT_PROPERTY = "keploy.jacoco.port";
    private static final String WARMUP_DISABLED_PROPERTY = "keploy.java.dedup.warmup.disabled";
    private static final String END_WAIT_PROPERTY = "keploy.java.end.wait.ms";
    private static final String SCAN_PROPERTY = "keploy.java.scan";
    private static final String CLASS_DIRS_PROPERTY = "keploy.java.class.dirs";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ServerSocket collector;
    private FakeJacoco jacoco;
    private ClassLoader savedContextLoader;
    private boolean savedTransformerAdded;
    private boolean savedExecutorCallSitesEnabled;
    private Set<String> savedCounted;
    private long savedHandedJobMaxNanos;

    @Before
    public void setUp() {
        savedTransformerAdded = KeployDedupAgent.transformerAdded;
        savedExecutorCallSitesEnabled = KeployDedupAgent.executorCallSitesEnabled;
        savedCounted = ServerEntries.snapshotCounted();
        savedHandedJobMaxNanos = JobGate.handedJobMaxNanos;
        RequestGate.open();
        JobGate.close();
    }

    @After
    public void tearDown() throws IOException {
        KeployDedupAgent.stop();
        if (savedContextLoader != null) {
            Thread.currentThread().setContextClassLoader(savedContextLoader);
            savedContextLoader = null;
        }
        KeployDedupAgent.transformerAdded = savedTransformerAdded;
        KeployDedupAgent.executorCallSitesEnabled = savedExecutorCallSitesEnabled;
        ServerEntries.restoreCounted(savedCounted);
        JobGate.handedJobMaxNanos = savedHandedJobMaxNanos;
        System.clearProperty(ENDPOINT_PROPERTY);
        System.clearProperty(JACOCO_PORT_PROPERTY);
        System.clearProperty(WARMUP_DISABLED_PROPERTY);
        System.clearProperty(END_WAIT_PROPERTY);
        System.clearProperty(SCAN_PROPERTY);
        System.clearProperty(CLASS_DIRS_PROPERTY);
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
    public void helloReportsLiveCapabilitiesAccuratelyAcrossRuntimeConfigurations() throws Exception {
        // Case 1: no -javaagent, no in-process JaCoCo -> windowCut/contaminated/background/asyncExecutors/listener false.
        KeployDedupAgent.transformerAdded = false;
        KeployDedupAgent.executorCallSitesEnabled = false;
        ServerEntries.restoreCounted(Collections.<String>emptySet());
        String helloNoAgent = connectAndReadHello(false);
        assertEquals(KeployDedupAgent.buildHelloFrame(false, false, true, false, true, true, false, false),
                helloNoAgent);

        // Case 2: -javaagent attached, server counted, endWait > 0, in-process JaCoCo scan on, app classes indexed.
        KeployDedupAgent.transformerAdded = true;
        KeployDedupAgent.executorCallSitesEnabled = true;
        ServerEntries.restoreCounted(Collections.singleton("Tomcat"));
        String helloFull = connectAndReadHello(true);
        assertEquals(KeployDedupAgent.buildHelloFrame(true, true, true, true, true, true, true, true),
                helloFull);

        // Case 3: endWait == 0 -> windowCut must be false even with agent & counted server.
        System.setProperty(END_WAIT_PROPERTY, "0");
        String helloZeroWait = connectAndReadHello(true);
        assertEquals(KeployDedupAgent.buildHelloFrame(false, true, true, true, true, true, true, true),
                helloZeroWait);
        System.clearProperty(END_WAIT_PROPERTY);

        // Case 4: Netty/WebFlux (no counted server) -> windowCut must be false.
        ServerEntries.restoreCounted(Collections.<String>emptySet());
        String helloUncountedServer = connectAndReadHello(true);
        assertEquals(KeployDedupAgent.buildHelloFrame(false, true, true, true, true, true, true, true),
                helloUncountedServer);

        // Case 5: KEPLOY_JAVA_SCAN=off -> background must be false even with in-process JaCoCo.
        ServerEntries.restoreCounted(Collections.singleton("Tomcat"));
        System.setProperty(SCAN_PROPERTY, "off");
        String helloScanOff = connectAndReadHello(true);
        assertEquals(KeployDedupAgent.buildHelloFrame(true, true, true, false, true, true, true, true),
                helloScanOff);
        System.clearProperty(SCAN_PROPERTY);

        // Case 6: classpath fallback (no app-only index) -> asyncExecutors must be false.
        KeployDedupAgent.executorCallSitesEnabled = false;
        String helloFallback = connectAndReadHello(true);
        assertEquals(KeployDedupAgent.buildHelloFrame(true, true, true, true, true, true, false, true),
                helloFallback);
    }

    @Test(timeout = 60000)
    public void earlyConnectBeforeJacocoAndServerInitDoesNotLatchFailureAndRefreshesHello() throws Exception {
        byte[] shopBytes = new byte[] {11, 12, 13};
        File shop = new File(tmp.newFolder("classes"), "com/acme/Shop.class");
        shop.getParentFile().mkdirs();
        Files.write(shop.toPath(), shopBytes);
        System.setProperty(CLASS_DIRS_PROPERTY, shop.getParentFile().getParentFile().getParent());

        // Simulate premain early connect: -javaagent attached, server class NOT loaded yet,
        // and external JaCoCo RT is on the classpath but its premain has not run yet
        // (RT.getAgent() throws IllegalStateException("JaCoCo agent not started.")).
        KeployDedupAgent.transformerAdded = true;
        KeployDedupAgent.executorCallSitesEnabled = true;
        ServerEntries.restoreCounted(Collections.<String>emptySet());

        InProcessScanTest.Agent realAgent = new InProcessScanTest.Agent();
        boolean[] probes = realAgent.probes(IndexReport.classId(shopBytes), "com/acme/Shop", 2);
        DeferredRtLoader.agent = null; // triggers IllegalStateException("JaCoCo agent not started.")
        savedContextLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new DeferredRtLoader(getClass().getClassLoader()));
        System.setProperty(JACOCO_PORT_PROPERTY, "1"); // unreachable TCP port: must use in-process once started

        collector = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        collector.setSoTimeout(30000);
        System.setProperty(ENDPOINT_PROPERTY, "127.0.0.1:" + collector.getLocalPort());
        System.setProperty(WARMUP_DISABLED_PROPERTY, "true");

        assertTrue(KeployDedupAgent.start());
        try (Socket sdk = collector.accept()) {
            sdk.setSoTimeout(30000);
            OutputStream out = sdk.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));

            // Initial HELLO on early connect honestly reports windowCut:false, background:false.
            String initialHello = in.readLine();
            assertEquals(KeployDedupAgent.buildHelloFrame(false, true, true, false, true, true, true, true),
                    initialHello);

            // Early BACKGROUND + ACK_ID sent BEFORE JaCoCo starts must still take effect once started.
            send(out, "ACK_ID");
            send(out, "BACKGROUND");

            // Now external JaCoCo's premain finishes and Tomcat's CoyoteAdapter loads.
            DeferredRtLoader.agent = realAgent;
            ServerEntries.restoreCounted(Collections.singleton("Tomcat"));

            // Next START must succeed via in-process ProbeScan (NOT latched off!) and emit updated HELLO.
            send(out, "START ts/test-1");
            String updatedHello = in.readLine();
            assertEquals("HELLO must refresh once server and JaCoCo are live",
                    KeployDedupAgent.buildHelloFrame(true, true, true, true, true, true, true, true),
                    updatedHello);
            assertEquals("ACK ts/test-1", in.readLine());

            probes[1] = true;
            send(out, "END ts/test-1");
            String cov = in.readLine();
            assertEquals("COV {\"id\":\"ts/test-1\",\"executedLinesByFile\":{\"com/acme/Shop\":[1]}}", cov);
            assertEquals("ACK ts/test-1", in.readLine());

            // Early BACKGROUND opt-in must emit a BACKGROUND frame on START test-2 after JaCoCo started.
            probes[0] = true;
            send(out, "START ts/test-2");
            assertEquals("ACK ts/test-2", in.readLine());
            String bg = in.readLine();
            assertTrue("early BACKGROUND opt-in must take effect once JaCoCo starts, got: " + bg,
                    bg != null && bg.startsWith("BACKGROUND ") && bg.contains("\"com/acme/Shop\":[0]"));
        } finally {
            DeferredRtLoader.agent = null;
        }
    }

    @Test(timeout = 60000)
    public void defaultBareAckOptInAckIdBareAndTokenNopAndReconnectStateReset() throws Exception {
        byte[] shopBytes = new byte[] {7, 8, 9};
        File shop = new File(tmp.newFolder("classes"), "com/acme/Shop.class");
        shop.getParentFile().mkdirs();
        Files.write(shop.toPath(), shopBytes);
        System.setProperty(CLASS_DIRS_PROPERTY, shop.getParentFile().getParentFile().getParent());
        boolean[] probes = inProcessJacoco().probes(IndexReport.classId(shopBytes), "com/acme/Shop", 4);

        collector = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
        collector.setSoTimeout(30000);
        System.setProperty(ENDPOINT_PROPERTY, "127.0.0.1:" + collector.getLocalPort());
        System.setProperty(WARMUP_DISABLED_PROPERTY, "true");

        assertTrue(KeployDedupAgent.start());

        // Connection 1: verify bare ACK by default, bare NOP, token NOP, then ACK_ID + BACKGROUND opt-in.
        try (Socket sdk = collector.accept()) {
            sdk.setSoTimeout(30000);
            OutputStream out = sdk.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));

            String hello = in.readLine();
            assertTrue(hello, hello != null && hello.startsWith("HELLO {\"proto\":3,"));
            assertTrue(hello, hello.contains("\"background\":true"));

            // Bare NOP (no token) and whitespace-only NOP must reply bare ACK so collector never hangs.
            send(out, "NOP");
            assertEquals("ACK", in.readLine());
            send(out, "NOP   ");
            assertEquals("ACK", in.readLine());
            // Token NOP echoes token even before ACK_ID.
            send(out, "NOP resync-1");
            assertEquals("ACK resync-1", in.readLine());

            // Without ACK_ID: older-collector backwards-compatible bare ACK.
            send(out, "START ts/test-0");
            assertEquals("ACK", in.readLine());
            send(out, "END ts/test-0");
            String cov0 = in.readLine();
            assertTrue(cov0, cov0 != null && cov0.startsWith("COV ") && cov0.contains("\"id\":\"ts/test-0\""));
            assertEquals("ACK", in.readLine());

            // Opt in to correlated ACK_ID + BACKGROUND.
            send(out, "ACK_ID");
            send(out, "BACKGROUND");

            send(out, "START ts/test-1");
            assertEquals("ACK ts/test-1", in.readLine());
            // NOP mid-test must not clear activeTestId.
            send(out, "NOP resync-2");
            assertEquals("ACK resync-2", in.readLine());
            probes[0] = true;
            send(out, "END ts/test-1");
            String cov1 = in.readLine();
            assertTrue(cov1, cov1 != null && cov1.startsWith("COV ") && cov1.contains("\"id\":\"ts/test-1\""));
            assertEquals("ACK ts/test-1", in.readLine());

            // Mismatched END echoes IGNORED <id> + ACK <id> once ACK_ID is active.
            send(out, "END ts/unknown");
            assertEquals("IGNORED ts/unknown", in.readLine());
            assertEquals("ACK ts/unknown", in.readLine());

            // Set a background probe before closing the socket to test reconnect reset.
            probes[3] = true;
        }

        // Connection 2 (after reconnect): ackWithId, backgroundRequested, lastEndedTestId must all be reset.
        try (Socket sdk2 = collector.accept()) {
            sdk2.setSoTimeout(30000);
            OutputStream out = sdk2.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sdk2.getInputStream(), StandardCharsets.UTF_8));

            String hello2 = in.readLine();
            assertTrue(hello2, hello2 != null && hello2.startsWith("HELLO {\"proto\":3,"));

            send(out, "START ts/after-reconnect");
            assertEquals("ackWithId must reset to bare ACK on reconnect", "ACK", in.readLine());
            // Confirm no BACKGROUND frame was emitted after reconnect even though probes[3] was true.
            send(out, "NOP verify-no-bg");
            assertEquals("ACK verify-no-bg", in.readLine());
            send(out, "END ts/after-reconnect");
            String cov = in.readLine();
            assertTrue(cov, cov != null && cov.startsWith("COV ") && cov.contains("\"id\":\"ts/after-reconnect\""));
            assertEquals("ACK", in.readLine());
        }
    }

    @Test(timeout = 60000)
    public void backgroundCommandWithProbeScanOffEmitsNoBackgroundFrame() throws Exception {
        byte[] shopBytes = new byte[] {4, 5, 6};
        File shop = new File(tmp.newFolder("classes"), "com/acme/Shop.class");
        shop.getParentFile().mkdirs();
        Files.write(shop.toPath(), shopBytes);
        System.setProperty(CLASS_DIRS_PROPERTY, shop.getParentFile().getParentFile().getParent());
        System.setProperty(SCAN_PROPERTY, "off");
        boolean[] probes = inProcessJacoco().probes(IndexReport.classId(shopBytes), "com/acme/Shop", 4);

        collector = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        collector.setSoTimeout(30000);
        System.setProperty(ENDPOINT_PROPERTY, "127.0.0.1:" + collector.getLocalPort());
        System.setProperty(WARMUP_DISABLED_PROPERTY, "true");

        assertTrue(KeployDedupAgent.start());
        try (Socket sdk = collector.accept()) {
            sdk.setSoTimeout(30000);
            OutputStream out = sdk.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));

            String hello = in.readLine();
            assertTrue(hello, hello.contains("\"background\":false"));
            send(out, "ACK_ID");
            send(out, "BACKGROUND");

            send(out, "START ts/t1");
            assertEquals("ACK ts/t1", in.readLine());
            send(out, "END ts/t1");
            assertTrue(in.readLine().startsWith("COV "));
            assertEquals("ACK ts/t1", in.readLine());

            probes[2] = true;
            send(out, "START ts/t2");
            assertEquals("ACK ts/t2", in.readLine());
            send(out, "NOP no-bg-when-scan-off");
            assertEquals("ACK no-bg-when-scan-off", in.readLine());
        }
    }

    @Test(timeout = 60000)
    public void appOnlyAllowListExcludesThirdPartyLibrariesAndNonMatchingClassesReturnNull() throws Exception {
        Set<String> allowed = new HashSet<>(Arrays.asList(
                "com/example/MyApp",
                "org/apache/fineract/MyApp",
                "io/netty/util/concurrent/SingleThreadEventExecutor",
                "okhttp3/internal/concurrent/TaskRunner",
                "io/lettuce/core/Client",
                "io/grpc/Channel",
                "software/amazon/awssdk/Client",
                "com/amazonaws/Client",
                "org/springframework/core/task/Custom",
                "com/zaxxer/hikari/Pool"));
        ServerEntries entries = new ServerEntries(true, allowed);
        ClassLoader loader = getClass().getClassLoader();

        // Indexed app classes (including org/apache/fineract/*) are eligible.
        assertTrue(entries.shouldRewriteExecutorCalls("com/example/MyApp"));
        assertTrue(entries.shouldRewriteExecutorCalls("org/apache/fineract/MyApp"));

        // Unindexed classes are never rewritten.
        assertFalse(entries.shouldRewriteExecutorCalls("com/example/Unindexed"));
        assertNull(entries.transform(loader, "com/example/Unindexed", null, null,
                generateExecutorCaller("com/example/Unindexed")));

        // Third-party libraries are NEVER rewritten even if accidentally placed in the allow-list.
        for (String lib : new String[] {
                "io/netty/util/concurrent/SingleThreadEventExecutor",
                "okhttp3/internal/concurrent/TaskRunner",
                "io/lettuce/core/Client",
                "io/grpc/Channel",
                "software/amazon/awssdk/Client",
                "com/amazonaws/Client",
                "org/springframework/core/task/Custom",
                "com/zaxxer/hikari/Pool",
                "kotlinx/coroutines/DefaultExecutor",
                "com/google/common/util/concurrent/MoreExecutors",
                "io/opentelemetry/sdk/trace/BatchSpanProcessor",
                "com/github/benmanes/caffeine/cache/BoundedLocalCache"
        }) {
            assertFalse("library must never be rewritten: " + lib, entries.shouldRewriteExecutorCalls(lib));
            assertNull("library transform must return null: " + lib,
                    entries.transform(loader, lib, null, null, generateExecutorCaller(lib)));
        }

        // Package-prefix mode (used when external JaCoCo defers premain index scan):
        ServerEntries prefixed = new ServerEntries(true, Collections.<String>emptySet(), "com/example/");
        assertTrue(prefixed.shouldRewriteExecutorCalls("com/example/MyApp"));
        assertFalse(prefixed.shouldRewriteExecutorCalls("com/other/Other"));
        assertFalse(prefixed.shouldRewriteExecutorCalls("io/netty/util/concurrent/SingleThreadEventExecutor"));

        // Plain class with no executor references returns null via constant-pool fast path.
        byte[] plain = generatePlainClass("com/example/MyApp");
        assertFalse(ServerEntries.hasExecutorReference(new org.objectweb.asm.ClassReader(plain)));
        assertNull(entries.transform(loader, "com/example/MyApp", null, null, plain));

        // Class that references Executor in a field/method descriptor but invokes no executor method returns null.
        byte[] fieldOnly = generateExecutorFieldOnlyClass("com/example/MyApp");
        assertNull(entries.transform(loader, "com/example/MyApp", null, null, fieldOnly));
    }

    @Test(timeout = 60000)
    public void rewritesAllExecutorAndCompletableFutureFormsWithFramesAndPreservesOnRetransform() throws Exception {
        String className = "com/example/AllAsyncForms";
        byte[] original = generateAllAsyncFormsClass(className);
        ServerEntries entries = new ServerEntries(true, Collections.singleton(className));
        ClassLoader loader = getClass().getClassLoader();

        // Before initial load, retransform must return null.
        assertNull(entries.transform(loader, className, Object.class, null, original));

        // Initial load rewrites call sites.
        byte[] transformed = entries.transform(loader, className, null, null, original);
        assertNotNull("all async forms class must be rewritten", transformed);

        // Retransform with original pre-rewrite bytes must re-apply the rewrite.
        byte[] retransformed = entries.transform(loader, className, Object.class, null, original);
        assertNotNull("retransform must re-apply call-site rewrites", retransformed);

        // Retransform with already-rewritten bytes returns null (idempotent).
        assertNull("already-rewritten bytes need no re-rewrite",
                entries.transform(loader, className, Object.class, null, retransformed));

        Class<?> loaded = new ByteLoader(loader).define(className.replace('/', '.'), retransformed);
        ThreadPoolExecutor tpe = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        ScheduledThreadPoolExecutor stpe = new ScheduledThreadPoolExecutor(1);
        ForkJoinPool fjp = new ForkJoinPool(1);
        try {
            // 1. INVOKEINTERFACE Executor.execute (branch=0), ExecutorService.execute (branch=1),
            //    ScheduledExecutorService.execute (branch=2), INVOKEVIRTUAL ThreadPoolExecutor.execute (branch=3),
            //    INVOKEVIRTUAL ScheduledThreadPoolExecutor.execute (branch=4), ForkJoinPool.execute (branch=5).
            for (int branch = 0; branch <= 5; branch++) {
                RequestGate.open();
                CountDownLatch started = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                Object req = RequestGate.enter();
                try {
                    Runnable task = latchTask(started, release);
                    loaded.getMethod("dispatchExecute", int.class, Executor.class, ExecutorService.class,
                            ScheduledExecutorService.class, ThreadPoolExecutor.class,
                            ScheduledThreadPoolExecutor.class, ForkJoinPool.class, Runnable.class)
                            .invoke(null, branch, tpe, tpe, stpe, tpe, stpe, fjp, task);
                } finally {
                    RequestGate.exit(req);
                }
                assertTrue("branch " + branch + " started", started.await(10, TimeUnit.SECONDS));
                assertEquals("branch " + branch + " keeps window open", 1, RequestGate.running());
                assertFalse("branch " + branch + " causes windowCut",
                        RequestGate.await(TimeUnit.MILLISECONDS.toNanos(25)));
                release.countDown();
            }

            // 2. INVOKEINTERFACE ExecutorService.submit(Runnable/Runnable+result/Callable) &
            //    INVOKEVIRTUAL ThreadPoolExecutor.submit(Runnable).
            for (int mode = 0; mode <= 3; mode++) {
                RequestGate.open();
                CountDownLatch started = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                Object req = RequestGate.enter();
                Future<?> f;
                try {
                    f = (Future<?>) loaded.getMethod("dispatchSubmit", int.class, ExecutorService.class,
                            ThreadPoolExecutor.class, Runnable.class, Callable.class)
                            .invoke(null, mode, tpe, tpe, latchTask(started, release),
                                    (Callable<String>) () -> {
                                        started.countDown();
                                        release.await(10, TimeUnit.SECONDS);
                                        return "res";
                                    });
                } finally {
                    RequestGate.exit(req);
                }
                assertTrue("submit mode " + mode + " started", started.await(10, TimeUnit.SECONDS));
                assertEquals("submit mode " + mode + " keeps window open", 1, RequestGate.running());
                release.countDown();
                f.get(10, TimeUnit.SECONDS);
                assertTrue("submit mode " + mode + " drains", RequestGate.await(TimeUnit.SECONDS.toNanos(5)));
            }

            // 3. CompletableFuture.runAsync (1-arg and 2-arg) & supplyAsync (1-arg and 2-arg).
            for (int mode = 0; mode <= 3; mode++) {
                RequestGate.open();
                CountDownLatch started = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                Object req = RequestGate.enter();
                CompletableFuture<?> cf;
                try {
                    cf = (CompletableFuture<?>) loaded.getMethod("dispatchCompletable", int.class, Executor.class,
                            Runnable.class, Supplier.class)
                            .invoke(null, mode, tpe, latchTask(started, release),
                                    (Supplier<String>) () -> {
                                        started.countDown();
                                        try {
                                            release.await(10, TimeUnit.SECONDS);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                        return "val";
                                    });
                } finally {
                    RequestGate.exit(req);
                }
                assertTrue("cf mode " + mode + " started", started.await(10, TimeUnit.SECONDS));
                assertEquals("cf mode " + mode + " keeps window open", 1, RequestGate.running());
                release.countDown();
                cf.get(10, TimeUnit.SECONDS);
                assertTrue("cf mode " + mode + " drains", RequestGate.await(TimeUnit.SECONDS.toNanos(5)));
            }

            // 4. Rewritten ThreadPoolExecutor.remove and ExecutorService.shutdownNow via bytecode.
            ThreadPoolExecutor single = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<Runnable>());
            CountDownLatch blockStart = new CountDownLatch(1);
            CountDownLatch blockRel = new CountDownLatch(1);
            try {
                single.execute(latchTask(blockStart, blockRel));
                assertTrue(blockStart.await(10, TimeUnit.SECONDS));

                RequestGate.open();
                MyAppTask toRemove = new MyAppTask("bc-remove");
                MyAppTask toDrain = new MyAppTask("bc-drain");
                Object req = RequestGate.enter();
                try {
                    loaded.getMethod("dispatchExecute", int.class, Executor.class, ExecutorService.class,
                            ScheduledExecutorService.class, ThreadPoolExecutor.class,
                            ScheduledThreadPoolExecutor.class, ForkJoinPool.class, Runnable.class)
                            .invoke(null, 3, single, single, stpe, single, stpe, fjp, toRemove);
                    loaded.getMethod("dispatchExecute", int.class, Executor.class, ExecutorService.class,
                            ScheduledExecutorService.class, ThreadPoolExecutor.class,
                            ScheduledThreadPoolExecutor.class, ForkJoinPool.class, Runnable.class)
                            .invoke(null, 3, single, single, stpe, single, stpe, fjp, toDrain);
                } finally {
                    RequestGate.exit(req);
                }
                assertEquals(2, RequestGate.running());
                boolean removed = (Boolean) loaded.getMethod("removeTask", ThreadPoolExecutor.class, Runnable.class)
                        .invoke(null, single, toRemove);
                assertTrue("rewritten remove must succeed", removed);
                assertEquals(1, RequestGate.running());
                @SuppressWarnings("unchecked")
                List<Runnable> drained = (List<Runnable>) loaded.getMethod("drainAll", ExecutorService.class)
                        .invoke(null, single);
                assertEquals(1, drained.size());
                assertSame("rewritten shutdownNow must unwrap Handed", toDrain, drained.get(0));
                assertEquals(0, RequestGate.running());
            } finally {
                blockRel.countDown();
                single.shutdownNow();
            }

            // 5. INVOKESPECIAL (super.execute) must NOT be rewritten (no recursion).
            String subName = "com/example/SubTpe";
            byte[] subBytes = generateSuperExecuteSubclass(subName);
            ServerEntries subEntries = new ServerEntries(true, Collections.singleton(subName));
            assertNull("INVOKESPECIAL super.execute must not be rewritten",
                    subEntries.transform(loader, subName, null, null, subBytes));
        } finally {
            tpe.shutdownNow();
            stpe.shutdownNow();
            fjp.shutdownNow();
        }
    }

    @Test(timeout = 60000)
    public void cancelledTimedOutRemovedAndShutdownNowTasksNeverLeakRequestWindow() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch blockerRelease = new CountDownLatch(1);
        try {
            // Occupy the single worker thread so subsequent tasks stay queued (pending).
            pool.execute(latchTask(blockerStarted, blockerRelease));
            assertTrue(blockerStarted.await(10, TimeUnit.SECONDS));

            // 1. Future.cancel() before task runs releases window immediately.
            RequestGate.open();
            Object req = RequestGate.enter();
            Future<?> f1;
            Future<String> f2;
            CompletableFuture<Void> cfRun;
            CompletableFuture<String> cfSupply;
            MyAppTask removable = new MyAppTask("removable");
            try {
                f1 = RequestGate.submit(pool, () -> { });
                f2 = RequestGate.submit(pool, (Callable<String>) () -> "x");
                cfRun = RequestGate.runAsync(() -> { }, pool);
                cfSupply = RequestGate.supplyAsync(() -> "y", pool);
                RequestGate.execute(pool, removable);
            } finally {
                RequestGate.exit(req);
            }
            assertEquals(5, RequestGate.running());

            assertTrue(f1.cancel(false));
            assertTrue(f2.cancel(false));
            assertTrue(cfRun.cancel(false));
            assertTrue(cfSupply.complete("timed-out-fallback"));
            assertTrue("remove must find and remove wrapped task", RequestGate.remove(pool, removable));
            assertEquals("all 5 cancelled/completed/removed tasks released immediately", 0, RequestGate.running());
            assertTrue(RequestGate.await(TimeUnit.SECONDS.toNanos(2)));
            assertFalse("removed task must not run", removable.ran.get());

            // 2. Completing a CompletableFuture WHILE its task is ALREADY RUNNING keeps window open until done.
            blockerRelease.countDown();
            CountDownLatch runningStarted = new CountDownLatch(1);
            CountDownLatch runningRelease = new CountDownLatch(1);
            RequestGate.open();
            Object req2 = RequestGate.enter();
            CompletableFuture<String> inFlight;
            try {
                inFlight = RequestGate.supplyAsync(() -> {
                    runningStarted.countDown();
                    try {
                        runningRelease.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "real";
                }, pool);
            } finally {
                RequestGate.exit(req2);
            }
            assertTrue(runningStarted.await(10, TimeUnit.SECONDS));
            assertTrue(inFlight.complete("early-override"));
            assertEquals("in-flight task still holds window until finally exits", 1, RequestGate.running());
            runningRelease.countDown();
            assertTrue(RequestGate.await(TimeUnit.SECONDS.toNanos(5)));

            // 3. shutdownNow() unwraps queued execute() tasks (no ClassCastException) AND releases
            //    pending permits for submit()-ed FutureTasks and ScheduledThreadPoolExecutor tasks.
            CountDownLatch hold2 = new CountDownLatch(1);
            CountDownLatch rel2 = new CountDownLatch(1);
            pool.execute(latchTask(hold2, rel2));
            assertTrue(hold2.await(10, TimeUnit.SECONDS));

            RequestGate.open();
            MyAppTask queuedAppTask = new MyAppTask("queued");
            Object req3 = RequestGate.enter();
            Future<?> submittedRunnable;
            Future<String> submittedCallable;
            try {
                RequestGate.execute(pool, queuedAppTask);
                submittedRunnable = RequestGate.submit(pool, () -> { });
                submittedCallable = RequestGate.submit(pool, (Callable<String>) () -> "val");
                // Casting submit() result to Runnable (FutureTask) must work without ClassCastException!
                assertTrue("submit() must return executor's own RunnableFuture", submittedRunnable instanceof Runnable);
                assertTrue("submit(Callable) must return executor's own RunnableFuture",
                        submittedCallable instanceof Runnable);
            } finally {
                RequestGate.exit(req3);
            }
            assertEquals(3, RequestGate.running());
            List<Runnable> drained = RequestGate.shutdownNow(pool);
            rel2.countDown();
            assertEquals(3, drained.size());
            MyAppTask castBack = (MyAppTask) drained.get(0);
            assertSame("shutdownNow must return the app's original Runnable", queuedAppTask, castBack);
            assertEquals("shutdownNow must release pending permits for execute + submit tasks",
                    0, RequestGate.running());
            assertTrue(RequestGate.await(TimeUnit.SECONDS.toNanos(2)));

            // 4. ScheduledThreadPoolExecutor: cast submit() to ScheduledFuture, remove((Runnable) f),
            //    and shutdownNow() releasing pending permits for both execute() and submit().
            ScheduledThreadPoolExecutor stpe = new ScheduledThreadPoolExecutor(1);
            CountDownLatch stpeHold = new CountDownLatch(1);
            CountDownLatch stpeRel = new CountDownLatch(1);
            try {
                stpe.execute(latchTask(stpeHold, stpeRel));
                assertTrue(stpeHold.await(10, TimeUnit.SECONDS));

                RequestGate.open();
                Object req4 = RequestGate.enter();
                Future<?> sf1;
                Future<?> sf2;
                try {
                    sf1 = RequestGate.submit(stpe, () -> { });
                    // Must preserve ScheduledFuture cast!
                    ScheduledFuture<?> asScheduled = (ScheduledFuture<?>) sf1;
                    assertNotNull(asScheduled);
                    // tpe.remove((Runnable) f) idiom must work and release permit!
                    assertTrue(RequestGate.remove(stpe, (Runnable) sf1));
                    assertEquals("only request remains after remove((Runnable) sf1)", 1, RequestGate.running());

                    RequestGate.execute(stpe, () -> { });
                    sf2 = RequestGate.submit(stpe, (Callable<String>) () -> "s");
                } finally {
                    RequestGate.exit(req4);
                }
                assertEquals(2, RequestGate.running());
                RequestGate.shutdownNow(stpe);
                stpeRel.countDown();
                assertEquals("shutdownNow on STPE must release both execute and submit pending permits",
                        0, RequestGate.running());
                assertTrue(RequestGate.await(TimeUnit.SECONDS.toNanos(2)));
            } finally {
                stpeRel.countDown();
                stpe.shutdownNow();
            }

            // 5. dropped() must NOT release a task that has already started running.
            RequestGate.open();
            CountDownLatch runningDropStart = new CountDownLatch(1);
            CountDownLatch runningDropRel = new CountDownLatch(1);
            Object req5 = RequestGate.enter();
            Runnable raw = latchTask(runningDropStart, runningDropRel);
            Runnable handed = RequestGate.hand(null, raw);
            RequestGate.exit(req5);
            Thread t = new Thread(handed);
            t.start();
            try {
                assertTrue(runningDropStart.await(10, TimeUnit.SECONDS));
                RequestGate.dropped(handed, raw);
                assertEquals("dropped() on already-running task must not close window early",
                        1, RequestGate.running());
            } finally {
                runningDropRel.countDown();
                t.join(10000);
            }
            assertEquals(0, RequestGate.running());
        } finally {
            blockerRelease.countDown();
            pool.shutdownNow();
        }
    }

    @Test(timeout = 60000)
    public void orderedPoolsGuardsDiscardPoliciesTaskHooksConcurrentTaskExecutorAndPassThroughDecorators()
            throws Exception {
        // 1. PriorityBlockingQueue raw pool.
        ThreadPoolExecutor priority = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<Runnable>());
        // 2. DiscardPolicy & DiscardOldestPolicy pools.
        ThreadPoolExecutor discard = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(1), new ThreadPoolExecutor.DiscardPolicy());
        ThreadPoolExecutor discardOldest = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(1), new ThreadPoolExecutor.DiscardOldestPolicy());
        // 3. ThreadPoolExecutor subclasses overriding beforeExecute or newTaskFor that cast Runnable to MyAppTask,
        //    and a pool with a custom log-and-drop RejectedExecutionHandler.
        AtomicReference<String> hookSaw = new AtomicReference<>();
        ThreadPoolExecutor customHookPool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>()) {
            @Override
            protected void beforeExecute(Thread t, Runnable r) {
                hookSaw.set(((MyAppTask) r).name);
            }
        };
        AtomicReference<String> newTaskForSaw = new AtomicReference<>();
        ThreadPoolExecutor customNewTaskForPool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>()) {
            @Override
            protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
                newTaskForSaw.set(((MyAppTask) runnable).name);
                return super.newTaskFor(runnable, value);
            }
        };
        ThreadPoolExecutor customDropHandlerPool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(1), (r, e) -> { /* custom log-and-drop */ });
        ThreadPoolExecutor callerRunsPool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(1), new ThreadPoolExecutor.CallerRunsPolicy());
        // 4. Exact ScheduledThreadPoolExecutor & Spring ThreadPoolTaskScheduler ARE safe.
        ScheduledThreadPoolExecutor stpe = new ScheduledThreadPoolExecutor(1);
        ThreadPoolTaskScheduler springScheduler = new ThreadPoolTaskScheduler();
        springScheduler.initialize();
        // 5. ConcurrentTaskExecutor wrapping priority pool vs opaque executor vs normal pool vs default single-thread.
        ThreadPoolExecutor normalPool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        ConcurrentTaskExecutor ctePriority = new ConcurrentTaskExecutor(priority);
        ConcurrentTaskExecutor cteOpaque = new ConcurrentTaskExecutor(Runnable::run);
        ConcurrentTaskExecutor cteNormal = new ConcurrentTaskExecutor(normalPool);
        ConcurrentTaskExecutor cteDefault = new ConcurrentTaskExecutor();
        ExecutorService unconfigurable = Executors.unconfigurableExecutorService(priority);

        try {
            assertTrue(OrderedPools.orders(priority));
            assertTrue(OrderedPools.orders(discard));
            assertTrue(OrderedPools.orders(discardOldest));
            assertTrue(OrderedPools.orders(customHookPool));
            assertTrue("newTaskFor override must be skipped", OrderedPools.orders(customNewTaskForPool));
            assertTrue("custom RejectedExecutionHandler must be skipped", OrderedPools.orders(customDropHandlerPool));
            assertTrue("CallerRunsPolicy silently drops after shutdown so must be skipped",
                    OrderedPools.orders(callerRunsPool));
            assertFalse("exact ScheduledThreadPoolExecutor is safe", OrderedPools.orders(stpe));
            assertFalse("Spring ThreadPoolTaskScheduler is safe", OrderedPools.orders(springScheduler));
            assertTrue("ConcurrentTaskExecutor wrapping priority pool must not wrap",
                    OrderedPools.orders(ctePriority));
            assertTrue("ConcurrentTaskExecutor wrapping opaque delegate must not wrap",
                    OrderedPools.orders(cteOpaque));
            assertFalse("ConcurrentTaskExecutor wrapping LinkedBlockingQueue pool is safe",
                    OrderedPools.orders(cteNormal));
            assertFalse("default ConcurrentTaskExecutor (newSingleThreadExecutor) is safe on JDK 8-21+",
                    OrderedPools.orders(cteDefault));
            assertTrue("unconfigurableExecutorService cannot be inspected",
                    OrderedPools.orders(unconfigurable));

            // Custom beforeExecute & newTaskFor pools receive original MyAppTask without ClassCastException.
            RequestGate.open();
            MyAppTask appTask = new MyAppTask("hooked");
            MyAppTask submittedTask = new MyAppTask("newTaskFor");
            Object req = RequestGate.enter();
            Future<?> submittedFuture;
            try {
                RequestGate.execute(customHookPool, appTask);
                submittedFuture = RequestGate.submit(customNewTaskForPool, submittedTask);
            } finally {
                RequestGate.exit(req);
            }
            customHookPool.shutdown();
            assertTrue(customHookPool.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals("hooked", hookSaw.get());
            assertTrue(appTask.ran.get());
            submittedFuture.get(10, TimeUnit.SECONDS);
            assertEquals("newTaskFor", newTaskForSaw.get());
            assertTrue(submittedTask.ran.get());

            // Pass-through app decorator wrapping a PriorityBlockingQueue pool:
            // outer call wraps into Handed; inner RequestGate.execute(priority, handed) unwraps and releases permit!
            CountDownLatch ran = new CountDownLatch(1);
            Runnable plainTask = ran::countDown;
            Executor decorator = r -> RequestGate.execute(priority, r);
            RequestGate.open();
            Object req2 = RequestGate.enter();
            try {
                RequestGate.execute(decorator, plainTask);
                assertEquals("unwrapped by inner priority pool, only request remains counted",
                        1, RequestGate.running());
            } finally {
                RequestGate.exit(req2);
            }
            assertEquals(0, RequestGate.running());
            assertTrue(ran.await(10, TimeUnit.SECONDS));
        } finally {
            priority.shutdownNow();
            discard.shutdownNow();
            discardOldest.shutdownNow();
            customHookPool.shutdownNow();
            customNewTaskForPool.shutdownNow();
            customDropHandlerPool.shutdownNow();
            callerRunsPool.shutdownNow();
            stpe.shutdownNow();
            springScheduler.shutdown();
            normalPool.shutdownNow();
            if (cteDefault.getConcurrentExecutor() instanceof ExecutorService) {
                ((ExecutorService) cteDefault.getConcurrentExecutor()).shutdownNow();
            }
            unconfigurable.shutdownNow();
        }
    }

    @Test(timeout = 60000)
    public void firstHopQueueWaitDoesNotEatBudgetWhileChainedSelfResubmissionExpiresAtHop1Deadline()
            throws Exception {
        JobGate.handedJobMaxNanos = TimeUnit.MILLISECONDS.toNanos(120);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 1. First-hop task waits in queue LONGER than handedJobMaxNanos:
            //    its budget must start when it begins running, not at hand-off time.
            CountDownLatch blockerStart = new CountDownLatch(1);
            CountDownLatch blockerRel = new CountDownLatch(1);
            pool.execute(latchTask(blockerStart, blockerRel));
            assertTrue(blockerStart.await(10, TimeUnit.SECONDS));

            AtomicReference<String> firstHopRunning = new AtomicReference<>();
            CountDownLatch firstHopDone = new CountDownLatch(1);
            Object job = JobGate.enter(new Object(), "scheduled");
            try {
                RequestGate.execute(pool, () -> {
                    firstHopRunning.set(JobGate.running());
                    firstHopDone.countDown();
                });
            } finally {
                JobGate.exit(job);
            }
            // Wait longer than handedJobMaxNanos while task sits in the queue.
            Thread.sleep(160);
            JobGate.open();
            blockerRel.countDown();
            assertTrue(firstHopDone.await(10, TimeUnit.SECONDS));
            assertNotNull("first hop must still be attributed after long queue wait", firstHopRunning.get());
            assertEquals("first hop marks window contaminated when it runs", 1, JobGate.close().size());

            // 2. Chained self-resubmitting loop starts its budget at hop 1 and MUST expire at hop 1's deadline.
            JobGate.handedJobMaxNanos = TimeUnit.MILLISECONDS.toNanos(200);
            AtomicInteger hops = new AtomicInteger();
            CountDownLatch hop1Attributed = new CountDownLatch(1);
            CountDownLatch expiredHopSeen = new CountDownLatch(1);
            AtomicBoolean keepLooping = new AtomicBoolean(true);
            Runnable[] self = new Runnable[1];
            self[0] = () -> {
                int hop = hops.incrementAndGet();
                String current = JobGate.running();
                if (current != null) {
                    if (hop == 1) {
                        hop1Attributed.countDown();
                    }
                } else if (hop > 1) {
                    expiredHopSeen.countDown();
                    keepLooping.set(false);
                    return;
                }
                if (keepLooping.get()) {
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    RequestGate.execute(pool, self[0]);
                }
            };
            Object job2 = JobGate.enter(new Object(), "scheduled");
            try {
                RequestGate.execute(pool, self[0]);
            } finally {
                JobGate.exit(job2);
            }
            assertTrue("hop 1 must be attributed", hop1Attributed.await(10, TimeUnit.SECONDS));
            assertTrue("chained self-resubmitting job task must expire at hop 1 deadline, hops=" + hops.get(),
                    expiredHopSeen.await(10, TimeUnit.SECONDS));
            assertTrue("multiple hops executed before expiry", hops.get() >= 2);
            JobGate.open();
            assertEquals("no contamination after chain deadline expires",
                    Collections.<String>emptyList(), JobGate.close());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test(timeout = 60000)
    public void trackingStructuresStayBoundedWithoutEndAndBehindLongRunningTasks() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        CountDownLatch sseStarted = new CountDownLatch(1);
        CountDownLatch sseStop = new CountDownLatch(1);
        try {
            // Start 1 long-running task (simulating an SSE stream) on the pool inside a request window.
            RequestGate.open();
            Object req = RequestGate.enter();
            try {
                RequestGate.execute(pool, latchTask(sseStarted, sseStop));
                assertTrue(sseStarted.await(10, TimeUnit.SECONDS));

                // Now execute & submit 200 short tasks on the SAME pool without calling END!
                for (int i = 0; i < 200; i++) {
                    Future<Integer> f = RequestGate.submit(pool, () -> 1);
                    assertEquals(Integer.valueOf(1), f.get(10, TimeUnit.SECONDS));
                    CountDownLatch done = new CountDownLatch(1);
                    RequestGate.execute(pool, done::countDown);
                    assertTrue(done.await(10, TimeUnit.SECONDS));
                }

                // All 400 finished tasks must self-remove promptly even behind the live SSE task.
                // Poll briefly because done::countDown runs inside Handed.run() just before finally exit().
                Field byExecField = RequestGate.class.getDeclaredField("PENDING_BY_EXECUTOR");
                byExecField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Executor, Set<?>> byExec = (Map<Executor, Set<?>>) byExecField.get(null);
                int execAfterCompleted = -1;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline) {
                    synchronized (byExec) {
                        Set<?> set = byExec.get(pool);
                        execAfterCompleted = set == null ? 0 : set.size();
                    }
                    if (execAfterCompleted == 1) {
                        break;
                    }
                    Thread.sleep(1);
                }
                assertEquals("all 400 finished tasks must self-remove immediately behind live SSE task",
                        1, execAfterCompleted);

                // Also submit & cancel 100 futures without calling END (exercises periodic sweep).
                CountDownLatch holdStart = new CountDownLatch(1);
                CountDownLatch holdRel = new CountDownLatch(1);
                pool.execute(latchTask(holdStart, holdRel));
                assertTrue(holdStart.await(10, TimeUnit.SECONDS));
                for (int i = 0; i < 100; i++) {
                    Future<?> cf = RequestGate.submit(pool, () -> { });
                    assertTrue(cf.cancel(false));
                }
                holdRel.countDown();
            } finally {
                RequestGate.exit(req);
            }

            // Inspect current Window's futures map & PENDING_BY_EXECUTOR before END sweep:
            // periodic sweep (stride 64) must keep both bounded even without END.
            Field byExecField = RequestGate.class.getDeclaredField("PENDING_BY_EXECUTOR");
            byExecField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Executor, Set<?>> byExec = (Map<Executor, Set<?>>) byExecField.get(null);
            Field currentField = RequestGate.class.getDeclaredField("current");
            currentField.setAccessible(true);
            Object currentWindow = currentField.get(null);
            Field futuresField = currentWindow.getClass().getDeclaredField("futures");
            futuresField.setAccessible(true);
            Map<?, ?> futuresMap = (Map<?, ?>) futuresField.get(currentWindow);
            int beforeSweepSize = futuresMap == null ? 0 : futuresMap.size();
            assertTrue("window futures map must stay bounded by sweep stride (64) without END, was " + beforeSweepSize,
                    beforeSweepSize <= 64);

            // Once running() sweeps the remaining cancelled futures, only the 1 live SSE task remains.
            assertEquals("only the 1 live SSE task remains counted", 1, RequestGate.running());
            assertEquals("all cancelled futures swept from window map", 0, futuresMap.size());
            int execTrackedSize;
            synchronized (byExec) {
                Set<?> set = byExec.get(pool);
                execTrackedSize = set == null ? 0 : set.size();
            }
            assertEquals("only the 1 live SSE task remains in PENDING_BY_EXECUTOR", 1, execTrackedSize);

            // Spring executor submit() futures cancelled before running must also release window permits (N7).
            String schedClass = "org/springframework/scheduling/concurrent/ThreadPoolTaskScheduler";
            byte[] schedWrapped = new ServerEntries(true).transform(
                    getClass().getClassLoader(), schedClass, null, null, generateSpringSubmitStandIn(schedClass));
            assertNotNull(schedWrapped);
            Class<?> loadedSched = new ByteLoader(getClass().getClassLoader())
                    .define(schedClass.replace('/', '.'), schedWrapped);
            Object schedInstance = loadedSched.getDeclaredConstructor().newInstance();
            RequestGate.open();
            Object reqSpring = RequestGate.enter();
            Future<?> springFuture;
            try {
                springFuture = (Future<?>) loadedSched.getMethod("submit", Runnable.class)
                        .invoke(schedInstance, (Runnable) () -> { });
            } finally {
                RequestGate.exit(reqSpring);
            }
            assertEquals(1, RequestGate.running());
            assertTrue(springFuture.cancel(false));
            assertEquals("cancelled Spring submit() future must release window permit", 0, RequestGate.running());
        } finally {
            sseStop.countDown();
            pool.shutdownNow();
        }
    }

    @Test(timeout = 60000)
    public void namedModuleClassViaClassDirsIsSkippedAndDoesNotThrowIllegalAccessError() throws Exception {
        String spec = System.getProperty("java.specification.version");
        Assume.assumeTrue("JPMS named modules require Java 9+",
                !spec.startsWith("1.") && Integer.parseInt(spec) >= 9);

        // 1. In-process check: boot-layer unreadable module locations must be populated and blocked.
        Set<String> unreadable = ServerEntries.unreadableModuleLocations();
        assertFalse("Java 9+ boot layer must have unreadable named module locations", unreadable.isEmpty());
        String anyBootLocation = unreadable.iterator().next();
        java.net.URL blockedUrl = anyBootLocation.startsWith("jrt:")
                ? URI.create(anyBootLocation).toURL()
                : new File(anyBootLocation).toURI().toURL();
        ProtectionDomain blockedPd = new ProtectionDomain(
                new CodeSource(blockedUrl, (Certificate[]) null), null);
        assertFalse("canAccessGate must reject unreadable named module location: " + anyBootLocation,
                ServerEntries.canAccessGate(getClass().getClassLoader(), blockedPd));

        // 2. End-to-end forked JVM check: run a real modular jar on --module-path with -javaagent:sdk.jar
        //    and KEPLOY_JAVA_CLASS_DIRS pointing to the modular jar. If ServerEntries rewrote the
        //    Executor.execute call site inside the named module, the JVM would throw IllegalAccessError!
        File modJar = tmp.newFile("modapp-" + System.nanoTime() + ".jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(modJar))) {
            jos.putNextEntry(new ZipEntry("module-info.class"));
            jos.write(generateModuleInfo("com.example.modapp"));
            jos.closeEntry();
            jos.putNextEntry(new ZipEntry("com/example/modapp/ModCaller.class"));
            jos.write(generateModularExecutorMain("com/example/modapp/ModCaller"));
            jos.closeEntry();
        }
        File sdkJar = tmp.newFile("keploy-sdk-mod-" + System.nanoTime() + ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", KeployDedupAgent.class.getName());
        manifest.getMainAttributes().putValue("Can-Retransform-Classes", "true");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(sdkJar), manifest)) {
            jos.putNextEntry(new ZipEntry("META-INF/keploy-test"));
            jos.closeEntry();
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(new File(System.getProperty("java.home"), "bin/java").getPath());
        cmd.add("-javaagent:" + sdkJar.getPath());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("--module-path");
        cmd.add(modJar.getPath());
        cmd.add("-m");
        cmd.add("com.example.modapp/com.example.modapp.ModCaller");

        File outFile = tmp.newFile("modapp-out.log");
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(outFile);
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.startsWith("KEPLOY_") || k.equals("JAVA_TOOL_OPTIONS")
                || k.equals("JDK_JAVA_OPTIONS") || k.equals("_JAVA_OPTIONS"));
        env.put("KEPLOY_JAVA_CLASS_DIRS", modJar.getPath());
        env.put("KEPLOY_COVERAGE_ENDPOINT", "127.0.0.1:1");
        env.put("KEPLOY_JAVA_DEDUP_WARMUP_DISABLED", "true");
        Process proc = pb.start();
        assertTrue("modular JVM timed out", proc.waitFor(30, TimeUnit.SECONDS));
        String output = new String(Files.readAllBytes(outFile.toPath()), StandardCharsets.UTF_8);
        assertEquals("modular JVM must exit 0 without IllegalAccessError: " + output, 0, proc.exitValue());
        // Positive control: prove KEPLOY_JAVA_CLASS_DIRS actually indexed the modular class, so
        // it was the named-module guard (not an empty index) that prevented rewriting.
        assertTrue("expected modular jar class to be indexed: " + output,
                output.contains("fingerprinting the app's 1 classes"));
        assertTrue("expected MOD_OK in output: " + output, output.contains("MOD_OK"));
        assertFalse("must not throw IllegalAccessError: " + output, output.contains("IllegalAccessError"));
    }

    @Test(timeout = 60000)
    public void longLivedWorkerLoopStartedFromJobDoesNotKeepJobGateOpenForever() throws Exception {
        JobGate.handedJobMaxNanos = TimeUnit.MILLISECONDS.toNanos(60);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stop = new CountDownLatch(1);
        AtomicReference<String> runningWhileFresh = new AtomicReference<>();
        AtomicReference<String> runningAfterExpiry = new AtomicReference<>();
        CountDownLatch checkExpiry = new CountDownLatch(1);
        CountDownLatch checkedExpiry = new CountDownLatch(1);

        Runnable handed;
        Object job = JobGate.enter(new Object(), "scheduled");
        try {
            handed = RequestGate.hand(null, () -> {
                runningWhileFresh.set(JobGate.running());
                started.countDown();
                try {
                    checkExpiry.await(10, TimeUnit.SECONDS);
                    runningAfterExpiry.set(JobGate.running());
                    checkedExpiry.countDown();
                    stop.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } finally {
            JobGate.exit(job);
        }

        Thread worker = new Thread(handed, "long-lived-worker");
        worker.start();
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            assertNotNull("attributed while fresh", runningWhileFresh.get());

            // Before expiry: window IS marked contaminated.
            JobGate.open();
            assertEquals(1, JobGate.close().size());

            // Wait past handedJobMaxNanos: JobGate.open() evicts the expired handed-off Run.
            Thread.sleep(100);
            JobGate.open();
            assertEquals("expired long-lived handed-off task must not poison later tests",
                    Collections.<String>emptyList(), JobGate.close());

            checkExpiry.countDown();
            assertTrue(checkedExpiry.await(10, TimeUnit.SECONDS));
            assertNull("worker thread JobGate.running() clears after expiry", runningAfterExpiry.get());
        } finally {
            stop.countDown();
            worker.join(10000);
        }
    }

    @Test(timeout = 60000)
    public void kafkaRabbitMqAndJmsListenerHooksMarkWindowContaminatedAndAdapterIsNotHooked() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        ServerEntries entries = new ServerEntries(true);

        // MessagingMessageListenerAdapter must NOT be hooked (spurious duplicate removed).
        String adapter = "org/springframework/kafka/listener/adapter/MessagingMessageListenerAdapter";
        assertNull("MessagingMessageListenerAdapter hook was removed",
                entries.transform(loader, adapter, null, null,
                        generateListenerClass(adapter, Opcodes.ACC_PUBLIC, "onMessage", "(Ljava/lang/Object;)V", false)));

        // Kafka ListenerConsumer with realistic private method signature and nested invokeOnMessage call.
        String kafka = "org/springframework/kafka/listener/KafkaMessageListenerContainer$ListenerConsumer";
        byte[] kafkaWrapped = entries.transform(loader, kafka, null, null,
                generateListenerClass(kafka, Opcodes.ACC_PRIVATE, "invokeRecordListener", "(Ljava/lang/Object;)V", true));
        assertNotNull(kafkaWrapped);
        Class<?> kafkaClass = new ByteLoader(loader).define(kafka.replace('/', '.'), kafkaWrapped);
        Object kafkaInstance = kafkaClass.getDeclaredConstructor().newInstance();
        Method kafkaInvoke = kafkaClass.getDeclaredMethod("invokeRecordListener", Object.class);
        kafkaInvoke.setAccessible(true);
        JobGate.open();
        kafkaInvoke.invoke(kafkaInstance, "record");
        List<String> kafkaContam = JobGate.close();
        assertEquals(1, kafkaContam.size());
        assertTrue(kafkaContam.toString(), kafkaContam.get(0).startsWith("kafka:"));

        // RabbitMQ and JMS AbstractMessageListenerContainer.doInvokeListener (protected signature).
        for (String container : new String[] {
                "org/springframework/amqp/rabbit/listener/AbstractMessageListenerContainer",
                "org/springframework/jms/listener/AbstractMessageListenerContainer"
        }) {
            byte[] wrapped = entries.transform(loader, container, null, null,
                    generateListenerClass(container, Opcodes.ACC_PROTECTED, "doInvokeListener",
                            "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
            assertNotNull(container, wrapped);
            Class<?> cls = new ByteLoader(loader).define(container.replace('/', '.'), wrapped);
            Object instance = cls.getDeclaredConstructor().newInstance();
            Method m = cls.getDeclaredMethod("doInvokeListener", Object.class, Object.class);
            m.setAccessible(true);
            JobGate.open();
            m.invoke(instance, "listener", "msg");
            List<String> contam = JobGate.close();
            assertEquals(container, 1, contam.size());
            assertTrue(container + ": " + contam, contam.get(0).startsWith("listener:"));
        }
    }

    private String connectAndReadHello(boolean withInProcessJacoco) throws Exception {
        KeployDedupAgent.stop();
        if (savedContextLoader != null) {
            Thread.currentThread().setContextClassLoader(savedContextLoader);
            savedContextLoader = null;
        }
        if (collector != null) {
            collector.close();
        }
        if (jacoco != null) {
            jacoco.close();
            jacoco = null;
        }
        collector = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        collector.setSoTimeout(30000);
        System.setProperty(ENDPOINT_PROPERTY, "127.0.0.1:" + collector.getLocalPort());
        System.setProperty(WARMUP_DISABLED_PROPERTY, "true");
        if (withInProcessJacoco) {
            inProcessJacoco();
        } else {
            jacoco = new FakeJacoco();
            System.setProperty(JACOCO_PORT_PROPERTY, String.valueOf(jacoco.server.getLocalPort()));
        }
        assertTrue(KeployDedupAgent.start());
        try (Socket sdk = collector.accept()) {
            sdk.setSoTimeout(30000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));
            return in.readLine();
        } finally {
            KeployDedupAgent.stop();
        }
    }

    private InProcessScanTest.Agent inProcessJacoco() throws ReflectiveOperationException {
        System.setProperty(JACOCO_PORT_PROPERTY, "1");
        savedContextLoader = Thread.currentThread().getContextClassLoader();
        InProcessScanTest.Agent agent = new InProcessScanTest.Agent();
        Thread.currentThread().setContextClassLoader(
                new InProcessScanTest.RtLoader(agent, getClass().getClassLoader()));
        return agent;
    }

    private static Runnable latchTask(CountDownLatch started, CountDownLatch release) {
        return () -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    private static void send(OutputStream out, String command) throws IOException {
        out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static byte[] generatePlainClass(String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        emitDefaultCtor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "noop", "()I", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateExecutorFieldOnlyClass(String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "exec", "Ljava/util/concurrent/Executor;", null, null).visitEnd();
        emitDefaultCtor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getExec", "()Ljava/util/concurrent/Executor;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, className, "exec", "Ljava/util/concurrent/Executor;");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateExecutorCaller(String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        emitDefaultCtor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fire",
                "(Ljava/util/concurrent/Executor;Ljava/lang/Runnable;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/concurrent/Executor", "execute",
                "(Ljava/lang/Runnable;)V", true);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // Generates a Java 8 class WITH branches and real StackMapTable frames (COMPUTE_FRAMES)
    // exercising Executor/ExecutorService/ScheduledExecutorService/ThreadPoolExecutor/
    // ScheduledThreadPoolExecutor/ForkJoinPool execute & submit, plus CompletableFuture.runAsync/supplyAsync.
    private static byte[] generateAllAsyncFormsClass(String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        emitDefaultCtor(cw);

        // dispatchExecute(int branch, Executor, ExecutorService, ScheduledExecutorService,
        //                 ThreadPoolExecutor, ScheduledThreadPoolExecutor, ForkJoinPool, Runnable)
        MethodVisitor exec = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "dispatchExecute",
                "(ILjava/util/concurrent/Executor;Ljava/util/concurrent/ExecutorService;"
                        + "Ljava/util/concurrent/ScheduledExecutorService;Ljava/util/concurrent/ThreadPoolExecutor;"
                        + "Ljava/util/concurrent/ScheduledThreadPoolExecutor;Ljava/util/concurrent/ForkJoinPool;"
                        + "Ljava/lang/Runnable;)V",
                null, null);
        exec.visitCode();
        Label b1 = new Label(), b2 = new Label(), b3 = new Label(), b4 = new Label(), b5 = new Label(), end = new Label();
        exec.visitVarInsn(Opcodes.ILOAD, 0);
        exec.visitJumpInsn(Opcodes.IFNE, b1);
        exec.visitVarInsn(Opcodes.ALOAD, 1);
        exec.visitVarInsn(Opcodes.ALOAD, 7);
        exec.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/concurrent/Executor", "execute",
                "(Ljava/lang/Runnable;)V", true);
        exec.visitJumpInsn(Opcodes.GOTO, end);

        exec.visitLabel(b1);
        exec.visitVarInsn(Opcodes.ILOAD, 0);
        exec.visitInsn(Opcodes.ICONST_1);
        exec.visitJumpInsn(Opcodes.IF_ICMPNE, b2);
        exec.visitVarInsn(Opcodes.ALOAD, 2);
        exec.visitVarInsn(Opcodes.ALOAD, 7);
        exec.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/concurrent/ExecutorService", "execute",
                "(Ljava/lang/Runnable;)V", true);
        exec.visitJumpInsn(Opcodes.GOTO, end);

        exec.visitLabel(b2);
        exec.visitVarInsn(Opcodes.ILOAD, 0);
        exec.visitInsn(Opcodes.ICONST_2);
        exec.visitJumpInsn(Opcodes.IF_ICMPNE, b3);
        exec.visitVarInsn(Opcodes.ALOAD, 3);
        exec.visitVarInsn(Opcodes.ALOAD, 7);
        exec.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/concurrent/ScheduledExecutorService", "execute",
                "(Ljava/lang/Runnable;)V", true);
        exec.visitJumpInsn(Opcodes.GOTO, end);

        exec.visitLabel(b3);
        exec.visitVarInsn(Opcodes.ILOAD, 0);
        exec.visitInsn(Opcodes.ICONST_3);
        exec.visitJumpInsn(Opcodes.IF_ICMPNE, b4);
        exec.visitVarInsn(Opcodes.ALOAD, 4);
        exec.visitVarInsn(Opcodes.ALOAD, 7);
        exec.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ThreadPoolExecutor", "execute",
                "(Ljava/lang/Runnable;)V", false);
        exec.visitJumpInsn(Opcodes.GOTO, end);

        exec.visitLabel(b4);
        exec.visitVarInsn(Opcodes.ILOAD, 0);
        exec.visitInsn(Opcodes.ICONST_4);
        exec.visitJumpInsn(Opcodes.IF_ICMPNE, b5);
        exec.visitVarInsn(Opcodes.ALOAD, 5);
        exec.visitVarInsn(Opcodes.ALOAD, 7);
        exec.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ScheduledThreadPoolExecutor", "execute",
                "(Ljava/lang/Runnable;)V", false);
        exec.visitJumpInsn(Opcodes.GOTO, end);

        exec.visitLabel(b5);
        exec.visitVarInsn(Opcodes.ALOAD, 6);
        exec.visitVarInsn(Opcodes.ALOAD, 7);
        exec.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ForkJoinPool", "execute",
                "(Ljava/lang/Runnable;)V", false);

        exec.visitLabel(end);
        exec.visitInsn(Opcodes.RETURN);
        exec.visitMaxs(0, 0);
        exec.visitEnd();

        // dispatchSubmit(int mode, ExecutorService, ThreadPoolExecutor, Runnable, Callable) -> Future
        MethodVisitor sub = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "dispatchSubmit",
                "(ILjava/util/concurrent/ExecutorService;Ljava/util/concurrent/ThreadPoolExecutor;"
                        + "Ljava/lang/Runnable;Ljava/util/concurrent/Callable;)Ljava/util/concurrent/Future;",
                null, null);
        sub.visitCode();
        Label s1 = new Label(), s2 = new Label(), s3 = new Label();
        sub.visitVarInsn(Opcodes.ILOAD, 0);
        sub.visitJumpInsn(Opcodes.IFNE, s1);
        sub.visitVarInsn(Opcodes.ALOAD, 1);
        sub.visitVarInsn(Opcodes.ALOAD, 3);
        sub.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/concurrent/ExecutorService", "submit",
                "(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;", true);
        sub.visitInsn(Opcodes.ARETURN);

        sub.visitLabel(s1);
        sub.visitVarInsn(Opcodes.ILOAD, 0);
        sub.visitInsn(Opcodes.ICONST_1);
        sub.visitJumpInsn(Opcodes.IF_ICMPNE, s2);
        sub.visitVarInsn(Opcodes.ALOAD, 1);
        sub.visitVarInsn(Opcodes.ALOAD, 3);
        sub.visitLdcInsn("ok");
        sub.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/concurrent/ExecutorService", "submit",
                "(Ljava/lang/Runnable;Ljava/lang/Object;)Ljava/util/concurrent/Future;", true);
        sub.visitInsn(Opcodes.ARETURN);

        sub.visitLabel(s2);
        sub.visitVarInsn(Opcodes.ILOAD, 0);
        sub.visitInsn(Opcodes.ICONST_2);
        sub.visitJumpInsn(Opcodes.IF_ICMPNE, s3);
        sub.visitVarInsn(Opcodes.ALOAD, 1);
        sub.visitVarInsn(Opcodes.ALOAD, 4);
        sub.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/concurrent/ExecutorService", "submit",
                "(Ljava/util/concurrent/Callable;)Ljava/util/concurrent/Future;", true);
        sub.visitInsn(Opcodes.ARETURN);

        sub.visitLabel(s3);
        sub.visitVarInsn(Opcodes.ALOAD, 2);
        sub.visitVarInsn(Opcodes.ALOAD, 3);
        sub.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ThreadPoolExecutor", "submit",
                "(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;", false);
        sub.visitInsn(Opcodes.ARETURN);
        sub.visitMaxs(0, 0);
        sub.visitEnd();

        // dispatchCompletable(int mode, Executor, Runnable, Supplier) -> CompletableFuture
        MethodVisitor cf = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "dispatchCompletable",
                "(ILjava/util/concurrent/Executor;Ljava/lang/Runnable;Ljava/util/function/Supplier;)"
                        + "Ljava/util/concurrent/CompletableFuture;",
                null, null);
        cf.visitCode();
        Label c1 = new Label(), c2 = new Label(), c3 = new Label();
        cf.visitVarInsn(Opcodes.ILOAD, 0);
        cf.visitJumpInsn(Opcodes.IFNE, c1);
        cf.visitVarInsn(Opcodes.ALOAD, 2);
        cf.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/concurrent/CompletableFuture", "runAsync",
                "(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;", false);
        cf.visitInsn(Opcodes.ARETURN);

        cf.visitLabel(c1);
        cf.visitVarInsn(Opcodes.ILOAD, 0);
        cf.visitInsn(Opcodes.ICONST_1);
        cf.visitJumpInsn(Opcodes.IF_ICMPNE, c2);
        cf.visitVarInsn(Opcodes.ALOAD, 2);
        cf.visitVarInsn(Opcodes.ALOAD, 1);
        cf.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/concurrent/CompletableFuture", "runAsync",
                "(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;", false);
        cf.visitInsn(Opcodes.ARETURN);

        cf.visitLabel(c2);
        cf.visitVarInsn(Opcodes.ILOAD, 0);
        cf.visitInsn(Opcodes.ICONST_2);
        cf.visitJumpInsn(Opcodes.IF_ICMPNE, c3);
        cf.visitVarInsn(Opcodes.ALOAD, 3);
        cf.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/concurrent/CompletableFuture", "supplyAsync",
                "(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;", false);
        cf.visitInsn(Opcodes.ARETURN);

        cf.visitLabel(c3);
        cf.visitVarInsn(Opcodes.ALOAD, 3);
        cf.visitVarInsn(Opcodes.ALOAD, 1);
        cf.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/concurrent/CompletableFuture", "supplyAsync",
                "(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
                false);
        cf.visitInsn(Opcodes.ARETURN);
        cf.visitMaxs(0, 0);
        cf.visitEnd();

        // removeTask(ThreadPoolExecutor, Runnable) -> boolean
        MethodVisitor rem = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "removeTask",
                "(Ljava/util/concurrent/ThreadPoolExecutor;Ljava/lang/Runnable;)Z", null, null);
        rem.visitCode();
        rem.visitVarInsn(Opcodes.ALOAD, 0);
        rem.visitVarInsn(Opcodes.ALOAD, 1);
        rem.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ThreadPoolExecutor", "remove",
                "(Ljava/lang/Runnable;)Z", false);
        rem.visitInsn(Opcodes.IRETURN);
        rem.visitMaxs(0, 0);
        rem.visitEnd();

        // drainAll(ExecutorService) -> List
        MethodVisitor drain = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "drainAll",
                "(Ljava/util/concurrent/ExecutorService;)Ljava/util/List;", null, null);
        drain.visitCode();
        drain.visitVarInsn(Opcodes.ALOAD, 0);
        drain.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/concurrent/ExecutorService", "shutdownNow",
                "()Ljava/util/List;", true);
        drain.visitInsn(Opcodes.ARETURN);
        drain.visitMaxs(0, 0);
        drain.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateSuperExecuteSubclass(String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null,
                "java/util/concurrent/ThreadPoolExecutor", null);
        MethodVisitor exec = cw.visitMethod(Opcodes.ACC_PUBLIC, "execute", "(Ljava/lang/Runnable;)V", null, null);
        exec.visitCode();
        exec.visitVarInsn(Opcodes.ALOAD, 0);
        exec.visitVarInsn(Opcodes.ALOAD, 1);
        exec.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/concurrent/ThreadPoolExecutor", "execute",
                "(Ljava/lang/Runnable;)V", false);
        exec.visitInsn(Opcodes.RETURN);
        exec.visitMaxs(2, 2);
        exec.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateListenerClass(String className, int access, String methodName,
            String descriptor, boolean nestedOnMessage) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        emitDefaultCtor(cw);
        if (nestedOnMessage) {
            MethodVisitor inner = cw.visitMethod(Opcodes.ACC_PRIVATE, "invokeOnMessage",
                    "(Ljava/lang/Object;)V", null, null);
            inner.visitCode();
            inner.visitInsn(Opcodes.RETURN);
            inner.visitMaxs(0, 2);
            inner.visitEnd();
        }
        MethodVisitor mv = cw.visitMethod(access, methodName, descriptor, null, null);
        mv.visitCode();
        if (nestedOnMessage) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, className, "invokeOnMessage",
                    "(Ljava/lang/Object;)V", false);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 3);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitDefaultCtor(ClassWriter cw) {
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
    }

    public static Future<?> makePendingFuture(Runnable task) {
        return new FutureTask<Void>(task, null);
    }

    private static byte[] generateSpringSubmitStandIn(String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        emitDefaultCtor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "submit",
                "(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                HonestProtocolAndAsyncTest.class.getName().replace('.', '/'),
                "makePendingFuture", "(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateModuleInfo(String moduleName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V9, Opcodes.ACC_MODULE, "module-info", null, null, null);
        ModuleVisitor mv = cw.visitModule(moduleName, 0, null);
        mv.visitRequire("java.base", Opcodes.ACC_MANDATED, null);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // Generates a modular main class implementing Executor & Runnable whose
    // main() calls INVOKEINTERFACE Executor.execute(Runnable)V and prints MOD_OK.
    private static byte[] generateModularExecutorMain(String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V9, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object",
                new String[] {"java/util/concurrent/Executor", "java/lang/Runnable"});
        emitDefaultCtor(cw);

        MethodVisitor exec = cw.visitMethod(Opcodes.ACC_PUBLIC, "execute", "(Ljava/lang/Runnable;)V", null, null);
        exec.visitCode();
        exec.visitVarInsn(Opcodes.ALOAD, 1);
        exec.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true);
        exec.visitInsn(Opcodes.RETURN);
        exec.visitMaxs(1, 2);
        exec.visitEnd();

        MethodVisitor run = cw.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        run.visitCode();
        run.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        run.visitLdcInsn("MOD_OK");
        run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        run.visitInsn(Opcodes.RETURN);
        run.visitMaxs(2, 1);
        run.visitEnd();

        MethodVisitor main = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main",
                "([Ljava/lang/String;)V", null, null);
        main.visitCode();
        main.visitTypeInsn(Opcodes.NEW, className);
        main.visitInsn(Opcodes.DUP);
        main.visitMethodInsn(Opcodes.INVOKESPECIAL, className, "<init>", "()V", false);
        main.visitVarInsn(Opcodes.ASTORE, 1);
        main.visitVarInsn(Opcodes.ALOAD, 1);
        main.visitVarInsn(Opcodes.ALOAD, 1);
        main.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/concurrent/Executor", "execute",
                "(Ljava/lang/Runnable;)V", true);
        main.visitInsn(Opcodes.RETURN);
        main.visitMaxs(2, 2);
        main.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final class MyAppTask implements Runnable {
        final String name;
        final AtomicBoolean ran = new AtomicBoolean(false);

        MyAppTask(String name) {
            this.name = name;
        }

        @Override
        public void run() {
            ran.set(true);
        }
    }

    private static final class ByteLoader extends ClassLoader {
        ByteLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    // Simulates org.jacoco.agent.rt.RT being on the classpath before its
    // premain starts the agent: getAgent() throws IllegalStateException while
    // DeferredRtLoader.agent is null, then returns the live agent once set.
    public static final class DeferredRtLoader extends ClassLoader {

        public static volatile Object agent;

        public static Object resolveAgent() {
            Object current = agent;
            if (current == null) {
                throw new IllegalStateException("JaCoCo agent not started.");
            }
            return current;
        }

        DeferredRtLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if ("org.jacoco.agent.rt.RT".equals(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    byte[] bytes = generateDeferredRt();
                    loaded = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
            return super.loadClass(name, resolve);
        }

        private static byte[] generateDeferredRt() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "org/jacoco/agent/rt/RT", null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getAgent",
                    "()Ljava/lang/Object;", null, null);
            mv.visitCode();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    DeferredRtLoader.class.getName().replace('.', '/'),
                    "resolveAgent", "()Ljava/lang/Object;", false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(1, 0);
            mv.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }
    }
}
