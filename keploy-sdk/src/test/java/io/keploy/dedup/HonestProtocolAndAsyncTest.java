package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.ref.WeakReference;
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
import java.util.concurrent.AbstractExecutorService;
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
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
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
    private static final String ASYNC_CALLSITES_PROPERTY = "keploy.java.async.callsites";
    private static final String POD_UID_PROPERTY = "keploy.dedup.pod.uid";
    private static final String POD_NAME_PROPERTY = "keploy.dedup.pod.name";
    private static final String SESSION_ID_PROPERTY = "keploy.dedup.session.id";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ServerSocket collector;
    private FakeJacoco jacoco;
    private ClassLoader savedContextLoader;
    private boolean savedTransformerAdded;
    private boolean savedExecutorCallSitesEnabled;
    private boolean savedJdkSinkBootstrapActive;
    private boolean savedObservedNoServerEntry;
    private boolean savedObservedPreCapturedUntrackedExecutor;
    private Set<String> savedCounted;
    private Set<String> savedUncounted;
    private Set<String> savedUnhookedAsyncDispatchers;
    private long savedHandedJobMaxNanos;
    private String savedControlSocketPath;
    private String savedDataSocketPath;

    @Before
    public void setUp() {
        savedTransformerAdded = KeployDedupAgent.transformerAdded;
        savedExecutorCallSitesEnabled = KeployDedupAgent.executorCallSitesEnabled;
        savedJdkSinkBootstrapActive = JdkSinkBootstrap.isActive();
        JdkSinkBootstrap.setActiveForTest(true);
        savedObservedNoServerEntry = KeployDedupAgent.observedNoServerEntry.get();
        savedObservedPreCapturedUntrackedExecutor = KeployDedupAgent.observedPreCapturedUntrackedExecutor.get();
        savedCounted = ServerEntries.snapshotCounted();
        savedUncounted = ServerEntries.snapshotUncounted();
        savedUnhookedAsyncDispatchers = ServerEntries.snapshotUnhookedAsyncDispatchers();
        savedHandedJobMaxNanos = JobGate.handedJobMaxNanos;
        savedControlSocketPath = KeployDedupAgent.controlSocketPath;
        savedDataSocketPath = KeployDedupAgent.dataSocketPath;
        KeployDedupAgent.observedNoServerEntry.set(false);
        KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
        ServerEntries.restoreUncounted(Collections.<String>emptySet());
        ServerEntries.restoreUnhookedAsyncDispatchers(Collections.<String>emptySet());
        ServerEntries.setHookStatusForTests(false, false, false);
        RequestGate.disarm();
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
        JdkSinkBootstrap.setActiveForTest(savedJdkSinkBootstrapActive);
        KeployDedupAgent.observedNoServerEntry.set(savedObservedNoServerEntry);
        KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(savedObservedPreCapturedUntrackedExecutor);
        ServerEntries.restoreCounted(savedCounted);
        ServerEntries.restoreUncounted(savedUncounted);
        ServerEntries.restoreUnhookedAsyncDispatchers(savedUnhookedAsyncDispatchers);
        ServerEntries.setHookStatusForTests(false, false, false);
        JobGate.handedJobMaxNanos = savedHandedJobMaxNanos;
        KeployDedupAgent.controlSocketPath = savedControlSocketPath;
        KeployDedupAgent.dataSocketPath = savedDataSocketPath;
        KeployDedupAgent.testHelloFailure = null;
        System.clearProperty(ENDPOINT_PROPERTY);
        System.clearProperty(JACOCO_PORT_PROPERTY);
        System.clearProperty(WARMUP_DISABLED_PROPERTY);
        System.clearProperty(END_WAIT_PROPERTY);
        System.clearProperty(SCAN_PROPERTY);
        System.clearProperty(CLASS_DIRS_PROPERTY);
        System.clearProperty(ASYNC_CALLSITES_PROPERTY);
        System.clearProperty(POD_UID_PROPERTY);
        System.clearProperty(POD_NAME_PROPERTY);
        System.clearProperty(SESSION_ID_PROPERTY);
        if (collector != null) {
            collector.close();
            collector = null;
        }
        if (jacoco != null) {
            jacoco.close();
            jacoco = null;
        }
        JobGate.close();
        RequestGate.disarm();
    }

    private static JsonObject parseHello(String line) {
        assertTrue("expected HELLO frame, got: " + line, line != null && line.startsWith("HELLO {"));
        return JsonParser.parseString(line.substring("HELLO ".length())).getAsJsonObject();
    }

    @Test(timeout = 60000)
    public void helloReportsLiveCapabilitiesAndIdentityAccuratelyAcrossRuntimeConfigurations() throws Exception {
        System.setProperty(POD_UID_PROPERTY, "uid-1234");
        System.setProperty(POD_NAME_PROPERTY, "orders-pod-0");
        System.setProperty(SESSION_ID_PROPERTY, "sess-abc");

        // Case 1: no -javaagent, no in-process JaCoCo -> windowCut/contaminated/background/asyncExecutors/listener false.
        KeployDedupAgent.transformerAdded = false;
        KeployDedupAgent.executorCallSitesEnabled = false;
        ServerEntries.restoreCounted(Collections.<String>emptySet());
        ServerEntries.restoreUncounted(Collections.<String>emptySet());
        ServerEntries.setHookStatusForTests(false, false, false);
        JsonObject h1 = parseHello(connectAndReadHello(false));
        assertEquals(3, h1.get("proto").getAsInt());
        assertEquals("uid-1234", h1.get("podUid").getAsString());
        assertEquals("orders-pod-0", h1.get("podName").getAsString());
        assertEquals("sess-abc", h1.get("sessionId").getAsString());
        assertEquals(KeployDedupAgent.jvmNonce(), h1.get("jvmNonce").getAsString());
        assertFalse("buildId must be non-empty", h1.get("buildId").getAsString().isEmpty());
        assertFalse(h1.get("windowCut").getAsBoolean());
        assertFalse(h1.get("contaminated").getAsBoolean());
        assertTrue(h1.get("classMismatch").getAsBoolean());
        assertFalse(h1.get("background").getAsBoolean());
        assertTrue(h1.get("ackId").getAsBoolean());
        assertFalse(h1.get("asyncExecutors").getAsBoolean());
        assertFalse(h1.get("listenerContamination").getAsBoolean());

        // Case 2: -javaagent attached, Jetty/Undertow cold-start (no server loaded yet!),
        // endWait > 0, in-process JaCoCo scan on, app classes indexed -> windowCut & asyncExecutors TRUE!
        KeployDedupAgent.transformerAdded = true;
        KeployDedupAgent.executorCallSitesEnabled = true;
        ServerEntries.restoreCounted(Collections.<String>emptySet());
        JsonObject h2 = parseHello(connectAndReadHello(true));
        assertEquals("jvmNonce stays stable across reconnects", h1.get("jvmNonce").getAsString(),
                h2.get("jvmNonce").getAsString());
        assertTrue("Jetty/Undertow cold start must declare windowCut=true", h2.get("windowCut").getAsBoolean());
        assertTrue(h2.get("contaminated").getAsBoolean());
        assertTrue(h2.get("background").getAsBoolean());
        assertTrue(h2.get("asyncExecutors").getAsBoolean());
        assertTrue(h2.get("listenerContamination").getAsBoolean());

        // Case 3: endWait == 0 -> BOTH windowCut AND asyncExecutors must be false.
        System.setProperty(END_WAIT_PROPERTY, "0");
        JsonObject h3 = parseHello(connectAndReadHello(true));
        assertFalse(h3.get("windowCut").getAsBoolean());
        assertFalse("asyncExecutors must be false when END_WAIT=0", h3.get("asyncExecutors").getAsBoolean());
        assertTrue(h3.get("contaminated").getAsBoolean());
        assertTrue(h3.get("listenerContamination").getAsBoolean());
        System.clearProperty(END_WAIT_PROPERTY);

        // Case 4: Netty/WebFlux detected (SEEN_UNCOUNTED non-empty) -> BOTH windowCut AND asyncExecutors false!
        ServerEntries.restoreUncounted(Collections.singleton("Netty"));
        JsonObject h4 = parseHello(connectAndReadHello(true));
        assertFalse("Netty must declare windowCut=false", h4.get("windowCut").getAsBoolean());
        assertFalse("Netty must declare asyncExecutors=false", h4.get("asyncExecutors").getAsBoolean());
        ServerEntries.restoreUncounted(Collections.<String>emptySet());

        // Case 5: KEPLOY_JAVA_SCAN=off -> background must be false even with in-process JaCoCo.
        System.setProperty(SCAN_PROPERTY, "off");
        JsonObject h5 = parseHello(connectAndReadHello(true));
        assertTrue(h5.get("windowCut").getAsBoolean());
        assertFalse(h5.get("background").getAsBoolean());
        assertTrue(h5.get("asyncExecutors").getAsBoolean());
        System.clearProperty(SCAN_PROPERTY);

        // Case 6: KEPLOY_JAVA_ASYNC_CALLSITES=off -> asyncExecutors must be false.
        System.setProperty(ASYNC_CALLSITES_PROPERTY, "off");
        JsonObject h6 = parseHello(connectAndReadHello(true));
        assertTrue(h6.get("windowCut").getAsBoolean());
        assertFalse("KEPLOY_JAVA_ASYNC_CALLSITES=off must declare asyncExecutors=false",
                h6.get("asyncExecutors").getAsBoolean());
        System.clearProperty(ASYNC_CALLSITES_PROPERTY);

        // Case 7: JPMS guard triggered -> asyncExecutors must be false.
        ServerEntries.setHookStatusForTests(false, false, true);
        JsonObject h7 = parseHello(connectAndReadHello(true));
        assertFalse("JPMS skipped must declare asyncExecutors=false", h7.get("asyncExecutors").getAsBoolean());

        // Case 8: Listener hook failure OR unhooked consumer -> listenerContamination must be false.
        ServerEntries.setHookStatusForTests(false, true, false);
        JsonObject h8 = parseHello(connectAndReadHello(true));
        assertFalse("listener hook failure must declare listenerContamination=false",
                h8.get("listenerContamination").getAsBoolean());
        ServerEntries.setHookStatusForTests(false, false, false);

        // Case 8b: Unhooked consumer (e.g. KafkaStreams) -> listenerContamination must be false.
        ServerEntries entries = new ServerEntries(true);
        entries.transform(getClass().getClassLoader(), "org/apache/kafka/streams/KafkaStreams", null, null,
                generatePlainClass("org/apache/kafka/streams/KafkaStreams"));
        JsonObject h8b = parseHello(connectAndReadHello(true));
        assertFalse("KafkaStreams must declare listenerContamination=false",
                h8b.get("listenerContamination").getAsBoolean());
        ServerEntries.setHookStatusForTests(false, false, false);

        // Case 8c: Uncounted servers (gRPC, Vert.x, Micronaut, JDK HttpServer) -> windowCut & asyncExecutors false.
        for (String srv : new String[] {
                "io/grpc/netty/NettyServer",
                "io/vertx/core/http/impl/HttpServerImpl",
                "io/micronaut/http/server/netty/NettyHttpServer",
                "sun/net/httpserver/ServerImpl"
        }) {
            ServerEntries.restoreUncounted(Collections.<String>emptySet());
            entries.transform(getClass().getClassLoader(), srv, null, null, generatePlainClass(srv));
            JsonObject hs = parseHello(connectAndReadHello(true));
            assertFalse(srv + " must declare windowCut=false", hs.get("windowCut").getAsBoolean());
            assertFalse(srv + " must declare asyncExecutors=false", hs.get("asyncExecutors").getAsBoolean());
        }
        ServerEntries.restoreUncounted(Collections.<String>emptySet());

        // Case 8d: App rewrite failure -> asyncExecutors must be false.
        ServerEntries.setHookStatusForTests(false, false, false, true);
        JsonObject h8d = parseHello(connectAndReadHello(true));
        assertFalse("app rewrite failure must declare asyncExecutors=false",
                h8d.get("asyncExecutors").getAsBoolean());
        ServerEntries.setHookStatusForTests(false, false, false, false);

        // Case 9: classpath fallback (executorCallSitesEnabled=false) -> asyncExecutors must be false.
        KeployDedupAgent.executorCallSitesEnabled = false;
        JsonObject h9 = parseHello(connectAndReadHello(true));
        assertTrue(h9.get("windowCut").getAsBoolean());
        assertFalse(h9.get("asyncExecutors").getAsBoolean());
    }

    @Test(timeout = 60000)
    public void earlyConnectBeforeJacocoAndServerInitDoesNotLatchFailureRefreshesHelloAndMarksNoServerEntry()
            throws Exception {
        byte[] shopBytes = new byte[] {11, 12, 13};
        File shop = new File(tmp.newFolder("classes"), "com/acme/Shop.class");
        shop.getParentFile().mkdirs();
        Files.write(shop.toPath(), shopBytes);
        System.setProperty(CLASS_DIRS_PROPERTY, shop.getParentFile().getParentFile().getParent());

        KeployDedupAgent.transformerAdded = true;
        KeployDedupAgent.executorCallSitesEnabled = true;
        ServerEntries.restoreCounted(Collections.<String>emptySet());
        ServerEntries.restoreUncounted(Collections.<String>emptySet());
        ServerEntries.setHookStatusForTests(false, false, false);

        InProcessScanTest.Agent realAgent = new InProcessScanTest.Agent();
        boolean[] probes = realAgent.probes(IndexReport.classId(shopBytes), "com/acme/Shop", 2);
        DeferredRtLoader.agent = null; // triggers IllegalStateException("JaCoCo agent not started.")
        savedContextLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new DeferredRtLoader(getClass().getClassLoader()));
        System.setProperty(JACOCO_PORT_PROPERTY, "1");

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

            // Initial HELLO: cold-start windowCut=true, background=false (JaCoCo not started yet).
            JsonObject initial = parseHello(in.readLine());
            assertTrue(initial.get("windowCut").getAsBoolean());
            assertFalse(initial.get("background").getAsBoolean());

            send(out, "ACK_ID");
            send(out, "BACKGROUND");

            // External JaCoCo premain finishes.
            DeferredRtLoader.agent = realAgent;

            // Next START refreshes HELLO with background=true.
            send(out, "START ts/test-1");
            JsonObject updated = parseHello(in.readLine());
            assertTrue("HELLO must refresh background=true once JaCoCo is live",
                    updated.get("background").getAsBoolean());
            assertEquals("ACK ts/test-1", in.readLine());

            // Normal counted request (RequestGate.enter()/exit()) -> clean COV!
            Object req = RequestGate.enter();
            probes[1] = true;
            RequestGate.exit(req);
            send(out, "END ts/test-1");
            String cov1 = in.readLine();
            assertEquals("COV {\"id\":\"ts/test-1\",\"executedLinesByFile\":{\"com/acme/Shop\":[1]}}", cov1);
            assertEquals("ACK ts/test-1", in.readLine());

            // Early BACKGROUND opt-in emits BACKGROUND frame on START ts/test-2.
            probes[0] = true;
            send(out, "START ts/test-2");
            assertEquals("ACK ts/test-2", in.readLine());
            String bg = in.readLine();
            assertTrue("early BACKGROUND opt-in must take effect once JaCoCo starts, got: " + bg,
                    bg != null && bg.startsWith("BACKGROUND ") && bg.contains("\"com/acme/Shop\":[0]"));

            // Uncounted server (probes > 0 while enteredCount == 0, e.g. Armeria/Helidon/Play) ->
            // latches observedNoServerEntry, refreshes HELLO with windowCut=false BEFORE COV,
            // and emits unverified: ["no-server-entry"] with NO "contaminated" field!
            probes[1] = true;
            send(out, "END ts/test-2");
            JsonObject downgradedHello = parseHello(in.readLine());
            assertFalse("no-server-entry END must refresh HELLO with windowCut=false before COV",
                    downgradedHello.get("windowCut").getAsBoolean());
            assertFalse("no-server-entry END must refresh HELLO with asyncExecutors=false before COV",
                    downgradedHello.get("asyncExecutors").getAsBoolean());
            String cov2 = in.readLine();
            assertEquals("COV {\"id\":\"ts/test-2\",\"executedLinesByFile\":{\"com/acme/Shop\":[1]},"
                    + "\"unverified\":[\"no-server-entry\"]}", cov2);
            assertFalse("no contaminated field for old enterprise notMeasuredBy compat",
                    cov2.contains("\"contaminated\""));
            assertEquals("ACK ts/test-2", in.readLine());

            // Gap after no-server-entry test-2 MUST suppress BACKGROUND on TCP and increment lost!
            probes[0] = true;
            send(out, "START ts/test-3");
            assertEquals("ACK ts/test-3", in.readLine());
            send(out, "NOP after-unverified-start");
            assertEquals("no BACKGROUND emitted on TCP after no-server-entry test-2",
                    "ACK after-unverified-start", in.readLine());

            // Counted test-3 -> clean gap test-3->test-4 emits BACKGROUND carrying lost:1!
            Object req3 = RequestGate.enter();
            try {
                probes[1] = true;
            } finally {
                RequestGate.exit(req3);
            }
            send(out, "END ts/test-3");
            assertEquals("COV {\"id\":\"ts/test-3\",\"executedLinesByFile\":{\"com/acme/Shop\":[1]}}",
                    in.readLine());
            assertEquals("ACK ts/test-3", in.readLine());

            probes[0] = true;
            send(out, "START ts/test-4");
            assertEquals("ACK ts/test-4", in.readLine());
            String bg34 = in.readLine();
            assertTrue("clean TCP gap after test-3 must emit BACKGROUND with lost:1, got: " + bg34,
                    bg34 != null && bg34.startsWith("BACKGROUND ")
                            && bg34.contains("\"after\":\"ts/test-3\"")
                            && bg34.contains("\"lost\":1"));
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

            // Fail-closed: unknown/library executors (e.g. Hazelcast Serializable-checking executor,
            // custom ExecutorService subclass, pass-through lambda decorator) are NEVER wrapped and
            // mark the current window contaminated ("untracked-executor:<class>").
            AtomicReference<Runnable> unknownSaw = new AtomicReference<>();
            ExecutorService serializableOnlyExec = new AbstractExecutorService() {
                @Override
                public void execute(Runnable command) {
                    if (!(command instanceof Serializable)) {
                        throw new IllegalStateException("task must be Serializable, got: " + command.getClass());
                    }
                    unknownSaw.set(command);
                    command.run();
                }

                @Override public void shutdown() { }
                @Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }
                @Override public boolean isShutdown() { return false; }
                @Override public boolean isTerminated() { return false; }
                @Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
            };
            assertTrue("unknown custom ExecutorService must fail closed",
                    OrderedPools.orders(serializableOnlyExec));

            RequestGate.open("t-unknown", null);
            Runnable serTask = (Runnable & Serializable) () -> { };
            Object reqUnknown = RequestGate.enter();
            try {
                RequestGate.execute(serializableOnlyExec, serTask);
                assertSame("unknown executor must receive original task untouched", serTask, unknownSaw.get());
            } finally {
                RequestGate.exit(reqUnknown);
            }
            RequestGate.WindowOutcome unknownOutcome = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertTrue("unknown executor must contaminate window: " + unknownOutcome.contaminations,
                    unknownOutcome.contaminations.contains("untracked-executor:" + serializableOnlyExec.getClass().getName()));

            // Pass-through app decorator wrapping a PriorityBlockingQueue pool:
            // fails closed at the outer unknown decorator too (never wraps!).
            CountDownLatch ran = new CountDownLatch(1);
            Runnable plainTask = ran::countDown;
            Executor decorator = r -> RequestGate.execute(priority, r);
            RequestGate.open("t-decorator", null);
            Object req2 = RequestGate.enter();
            try {
                RequestGate.execute(decorator, plainTask);
                assertEquals("fail-closed decorator never wraps, only request remains counted",
                        1, RequestGate.running());
            } finally {
                RequestGate.exit(req2);
            }
            RequestGate.WindowOutcome decOutcome = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertTrue("unknown decorator carries tail from prior untracked window: " + decOutcome.contaminations,
                    decOutcome.contaminations.contains("tail:t-unknown"));
            assertTrue("unknown decorator contaminates window: " + decOutcome.contaminations,
                    decOutcome.contaminations.stream().anyMatch(s -> s.startsWith("untracked-executor:")));
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
            // Wait briefly for the expired hop's finally block to call JobGate.exit().
            long exitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (JobGate.activeRunCount() > 0 && System.nanoTime() < exitDeadline) {
                Thread.sleep(5);
            }
            JobGate.open();
            assertEquals("no contamination after expired chain hop exits",
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
                int execAfterCompleted = -1;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline) {
                    execAfterCompleted = RequestGate.pendingByExecutorSize(pool);
                    if (execAfterCompleted == 1) {
                        break;
                    }
                    Thread.sleep(1);
                }
                assertEquals("all 400 finished tasks must self-remove immediately behind live SSE task",
                        1, execAfterCompleted);
                assertEquals("PENDING_BY_FUTURE must be empty after completed submits",
                        0, RequestGate.pendingByFutureSize());

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
            assertEquals("only the 1 live SSE task remains in PENDING_BY_EXECUTOR",
                    1, RequestGate.pendingByExecutorSize(pool));
            assertEquals("all cancelled futures removed from PENDING_BY_FUTURE",
                    0, RequestGate.pendingByFutureSize());

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

            // Before expiry: window IS marked contaminated with job's name.
            JobGate.open();
            List<String> beforeExp = JobGate.close();
            assertEquals(1, beforeExp.size());
            assertFalse(beforeExp.get(0).startsWith("job-tail:"));

            // Wait past handedJobMaxNanos while the worker is STILL EXECUTING:
            // overlapping windows must be marked with "job-tail:<name>"
            // until exit() actually runs!
            Thread.sleep(100);
            JobGate.open();
            List<String> whileStillRunning = JobGate.close();
            assertEquals("still-running expired job must mark overlapping window with job-tail:<name>: "
                    + whileStillRunning, 1, whileStillRunning.size());
            assertTrue(whileStillRunning.get(0), whileStillRunning.get(0).startsWith("job-tail:"));

            checkExpiry.countDown();
            assertTrue(checkedExpiry.await(10, TimeUnit.SECONDS));
            assertNull("worker thread JobGate.running() clears after expiry so chained hops stop",
                    runningAfterExpiry.get());
        } finally {
            stop.countDown();
            worker.join(10000);
        }
        // Once the expired worker actually exits (exit() ran), subsequent windows are clean.
        JobGate.open();
        assertEquals("clean once expired worker actually finishes",
                Collections.<String>emptyList(), JobGate.close());
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

    @Test(timeout = 60000)
    public void unixControlSocketDeclaresProto3CapsMatchingLiveState() throws Exception {
        File sockDir = tmp.newFolder("unix-caps");
        KeployDedupAgent.controlSocketPath = new File(sockDir, "control.sock").getAbsolutePath();
        KeployDedupAgent.dataSocketPath = new File(sockDir, "data.sock").getAbsolutePath();
        System.clearProperty(ENDPOINT_PROPERTY);
        System.setProperty(WARMUP_DISABLED_PROPERTY, "true");
        jacoco = new FakeJacoco();
        System.setProperty(JACOCO_PORT_PROPERTY, String.valueOf(jacoco.server.getLocalPort()));

        KeployDedupAgent.transformerAdded = true;
        KeployDedupAgent.executorCallSitesEnabled = true;
        ServerEntries.restoreUncounted(Collections.<String>emptySet());
        ServerEntries.setHookStatusForTests(false, false, false);
        assertTrue(KeployDedupAgent.start());

        String reply1 = sendUnix(KeployDedupAgent.controlSocketPath, "START ts/u1");
        assertEquals("ACK proto=3 caps=windowCut=true,contaminated=true,classMismatch=true,"
                + "background=false,ackId=true,asyncExecutors=true,listenerContamination=true\n", reply1);
        assertTrue("unix reply must stay well under 1024 bytes", reply1.length() < 1024);
        assertFalse("caps token must contain no spaces",
                reply1.trim().substring("ACK proto=3 ".length()).contains(" "));

        // Flip KEPLOY_JAVA_ASYNC_CALLSITES=off and verify live caps update on next command.
        System.setProperty(ASYNC_CALLSITES_PROPERTY, "off");
        String reply2 = sendUnix(KeployDedupAgent.controlSocketPath, "START ts/u2");
        assertEquals("ACK proto=3 caps=windowCut=true,contaminated=true,classMismatch=true,"
                + "background=false,ackId=true,asyncExecutors=false,listenerContamination=true\n", reply2);
        System.clearProperty(ASYNC_CALLSITES_PROPERTY);
    }

    @Test(timeout = 60000)
    public void completableFutureDependentStagesAndAsyncInstanceMethodsAreTrackedAndUntrackedSitesContaminate()
            throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        ScheduledThreadPoolExecutor stpe = new ScheduledThreadPoolExecutor(1);
        try {
            // 1. Fast supplier with slow synchronous dependent: supplyAsync(fast, pool).thenAccept(slowAudit)
            //    registered before completion MUST hold window permit until slowAudit finishes!
            CountDownLatch supplierDone = new CountDownLatch(1);
            CountDownLatch auditStarted = new CountDownLatch(1);
            CountDownLatch auditRelease = new CountDownLatch(1);
            AtomicInteger runningDuringAudit = new AtomicInteger(-1);

            RequestGate.open("cf-dep", null);
            Object req = RequestGate.enter();
            CompletableFuture<Void> chain;
            CountDownLatch gateSupplier = new CountDownLatch(1);
            try {
                CompletableFuture<String> base = RequestGate.supplyAsync(() -> {
                    try {
                        gateSupplier.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    supplierDone.countDown();
                    return "ok";
                }, pool);
                chain = base.thenAccept(v -> {
                    runningDuringAudit.set(RequestGate.running());
                    auditStarted.countDown();
                    try {
                        auditRelease.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } finally {
                RequestGate.exit(req);
            }
            // Release supplier AFTER RequestGate.exit(req) so audit deterministically
            // observes count == 1 (only the supplier's deferred permit, never req + permit).
            gateSupplier.countDown();
            assertTrue("supplier completed", supplierDone.await(10, TimeUnit.SECONDS));
            assertTrue("dependent audit stage started", auditStarted.await(10, TimeUnit.SECONDS));
            assertEquals("window count must stay 1 while dependent thenAccept(audit) runs",
                    1, runningDuringAudit.get());
            assertEquals("window still open while audit runs", 1, RequestGate.running());
            assertFalse("END must cut if audit outruns wait",
                    RequestGate.await(TimeUnit.MILLISECONDS.toNanos(30)));
            auditRelease.countDown();
            chain.get(10, TimeUnit.SECONDS);

            // 2. Chained *Async instance methods (thenApplyAsync, thenAcceptAsync, thenRunAsync,
            //    thenComposeAsync, thenCombineAsync, handleAsync, whenCompleteAsync).
            RequestGate.open("cf-async-stages", null);
            CountDownLatch stageStarted = new CountDownLatch(1);
            CountDownLatch stageRelease = new CountDownLatch(1);
            Object req2 = RequestGate.enter();
            CompletableFuture<String> chained;
            try {
                CompletableFuture<String> seed = CompletableFuture.completedFuture("seed");
                CompletableFuture<String> s1 = RequestGate.thenApplyAsync(seed, x -> {
                    stageStarted.countDown();
                    try {
                        stageRelease.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return x + "+1";
                }, pool);
                chained = RequestGate.thenComposeAsync(s1,
                        x -> CompletableFuture.completedFuture(x + "+2"), pool);
            } finally {
                RequestGate.exit(req2);
            }
            assertTrue(stageStarted.await(10, TimeUnit.SECONDS));
            assertTrue("chained *Async stages keep window open", RequestGate.running() >= 1);
            stageRelease.countDown();
            assertEquals("seed+1+2", chained.get(10, TimeUnit.SECONDS));
            assertTrue("chained *Async stages drain cleanly",
                    RequestGate.await(TimeUnit.SECONDS.toNanos(5)));

            // 3. Upstream failure releases *Async stage permit immediately (no leak!).
            RequestGate.open("cf-upstream-fail", null);
            Object req3 = RequestGate.enter();
            CompletableFuture<String> failed = new CompletableFuture<>();
            CompletableFuture<String> downstream;
            try {
                downstream = RequestGate.thenApplyAsync(failed, s -> s + "!", pool);
            } finally {
                RequestGate.exit(req3);
            }
            assertEquals(1, RequestGate.running());
            failed.completeExceptionally(new RuntimeException("boom"));
            assertTrue("downstream completed exceptionally", downstream.isCompletedExceptionally());
            assertTrue("upstream failure releases permit without waiting",
                    RequestGate.await(TimeUnit.SECONDS.toNanos(2)));

            // 4. Untracked async submission sites (schedule, invokeAny, Thread.start) contaminate
            //    window without changing app behavior.
            RequestGate.open("untracked-sites", null);
            Object req4 = RequestGate.enter();
            CountDownLatch threadRan = new CountDownLatch(1);
            try {
                ScheduledFuture<?> sf = RequestGate.schedule(stpe, () -> { }, 1, TimeUnit.MILLISECONDS);
                sf.get(5, TimeUnit.SECONDS);
                String any = RequestGate.invokeAny(pool, Collections.<Callable<String>>singletonList(() -> "win"));
                assertEquals("win", any);
                Thread t = new Thread(threadRan::countDown);
                RequestGate.startThread(t);
                t.join(5000);
            } finally {
                RequestGate.exit(req4);
            }
            assertTrue(threadRan.await(5, TimeUnit.SECONDS));
            RequestGate.WindowOutcome out4 = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertTrue("schedule contaminated: " + out4.contaminations,
                    out4.contaminations.contains("untracked-async:schedule"));
            assertTrue("invokeAny contaminated: " + out4.contaminations,
                    out4.contaminations.contains("untracked-async:invokeAny"));
            assertTrue("thread-start contaminated: " + out4.contaminations,
                    out4.contaminations.contains("untracked-async:thread-start"));
        } finally {
            pool.shutdownNow();
            stpe.shutdownNow();
        }
    }

    @Test(timeout = 60000)
    public void pendingByFutureDoesNotLeakSelfReferencingCancellableJob() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        CountDownLatch blockStart = new CountDownLatch(1);
        CountDownLatch blockRel = new CountDownLatch(1);
        try {
            // Block the single worker so job sits queued and actually enters PENDING_BY_FUTURE.
            pool.execute(latchTask(blockStart, blockRel));
            assertTrue(blockStart.await(10, TimeUnit.SECONDS));

            RequestGate.open("leak-check", null);
            Object req = RequestGate.enter();
            WeakReference<SelfReferencingJob> weakJob;
            try {
                SelfReferencingJob job = new SelfReferencingJob();
                weakJob = new WeakReference<>(job);
                job.future = RequestGate.submit(pool, job);
                assertEquals("queued submit must actually populate PENDING_BY_FUTURE",
                        1, RequestGate.pendingByFutureSize());
                blockRel.countDown();
                job.future.get(10, TimeUnit.SECONDS);
                job = null;
            } finally {
                blockRel.countDown();
                RequestGate.exit(req);
            }
            assertTrue(RequestGate.await(TimeUnit.SECONDS.toNanos(5)));
            assertEquals("PENDING_BY_FUTURE must be empty immediately after job ran",
                    0, RequestGate.pendingByFutureSize());

            for (int i = 0; i < 40 && weakJob.get() != null; i++) {
                System.gc();
                Thread.sleep(25);
            }
            assertNull("self-referencing job.future = exec.submit(job) must be GC'd with no leak",
                    weakJob.get());
        } finally {
            blockRel.countDown();
            pool.shutdownNow();
        }
    }

    @Test(timeout = 60000)
    public void referenceQueueReleasesAbandonedHandPermitOnGc() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        CountDownLatch blockStart = new CountDownLatch(1);
        CountDownLatch blockRel = new CountDownLatch(1);
        try {
            pool.execute(latchTask(blockStart, blockRel));
            assertTrue(blockStart.await(10, TimeUnit.SECONDS));

            RequestGate.open("abandoned-hand", null);
            Object req = RequestGate.enter();
            try {
                RequestGate.execute(pool, () -> { });
            } finally {
                RequestGate.exit(req);
            }
            assertEquals(1, RequestGate.running());

            // Simulate unrewritten getQueue().clear(): Hand is dropped without running or drop().
            pool.getQueue().clear();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (RequestGate.running() > 0 && System.nanoTime() < deadline) {
                System.gc();
                Thread.sleep(25);
            }
            assertEquals("ReferenceQueue cleanup must release permit for GC'd abandoned Hand",
                    0, RequestGate.running());
        } finally {
            blockRel.countDown();
            pool.shutdownNow();
        }
    }

    @Test(timeout = 60000)
    public void unarmedKeployAbsentIsNoOpAndMarksArmedMidFlightWhileUnarmedWorkRemainsInFlight() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        long savedTailWindow = RequestGate.untrackedTailWindowNanos;
        try {
            RequestGate.untrackedTailWindowNanos = TimeUnit.MILLISECONDS.toNanos(30);
            RequestGate.disarm();
            assertFalse(RequestGate.isArmed());

            // While unarmed: RequestGate.enter and JobGate.enter return the same static
            // zero-allocation sentinel token, tasks are NOT wrapped, and JobGate has 0 active runs.
            Object reqTok = RequestGate.enter();
            assertTrue("unarmed RequestGate.enter returns static sentinel", RequestGate.isUnarmedToken(reqTok));
            Runnable raw = () -> { };
            assertSame("unarmed RequestGate.hand must not wrap", raw, RequestGate.hand(pool, raw));
            Object jobTok = JobGate.enter(new Object(), "quartz");
            assertSame("unarmed JobGate.enter returns the same zero-allocation sentinel", reqTok, jobTok);
            assertEquals("unarmed JobGate has zero active runs", 0, JobGate.activeRunCount());
            assertEquals("unarmed PENDING_BY_FUTURE stays 0", 0, RequestGate.pendingByFutureSize());
            RequestGate.exit(reqTok);

            // Long-running job started BEFORE arming stays in flight across Tests 1 AND 2 ->
            // BOTH Test 1 AND Test 2 MUST be marked "armed-mid-flight"!
            RequestGate.open("first-armed", null);
            assertTrue(RequestGate.isArmed());
            RequestGate.WindowOutcome first = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertEquals(Collections.singletonList("armed-mid-flight"), first.contaminations);

            RequestGate.open("second-armed-still-in-flight", null);
            RequestGate.WindowOutcome second = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertEquals("in-flight unarmed job must keep marking subsequent windows armed-mid-flight",
                    Collections.singletonList("armed-mid-flight"), second.contaminations);

            // Once the pre-arming job finally exits (and the short unarmed hand-off tail elapses), Test 3 is clean.
            JobGate.exit(jobTok);
            assertEquals(0L, RequestGate.unarmedInFlight());
            Thread.sleep(50);
            RequestGate.open("third-armed-after-exit", null);
            RequestGate.WindowOutcome third = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertEquals(Collections.<String>emptyList(), third.contaminations);
        } finally {
            RequestGate.untrackedTailWindowNanos = savedTailWindow;
            pool.shutdownNow();
        }
    }

    @Test(timeout = 60000)
    public void listenerAttributionExtractsTopicsQueuesDestinationAndCachesPerInstance() {
        KafkaContainerStub kafka = new KafkaContainerStub(new String[] {"orders", "payments"},
                new AdapterStub(new OrdersBean()));
        String k1 = JobGate.name(kafka, "kafka");
        String k2 = JobGate.name(kafka, "kafka");
        assertSame("cached listener name must return identical String instance", k1, k2);
        assertEquals("kafka:orders,payments@" + OrdersBean.class.getName(), k1);

        AmqpContainerStub amqp = new AmqpContainerStub(new String[] {"q.billing"}, new AdapterStub(new BillingBean()));
        String a1 = JobGate.name(amqp, "listener");
        assertSame(a1, JobGate.name(amqp, "listener"));
        assertEquals("listener:q.billing@" + BillingBean.class.getName(), a1);

        JmsContainerStub jms = new JmsContainerStub("topic.audit", new AuditBean());
        String j1 = JobGate.name(jms, "listener");
        assertSame(j1, JobGate.name(jms, "listener"));
        assertEquals("listener:topic.audit@" + AuditBean.class.getName(), j1);
    }

    @Test(timeout = 60000)
    public void shadedAndRelocatedPackagesAreSkippedOnlyInPrefixModeAndVendorIsNotSkipped() {
        ServerEntries prefixed = new ServerEntries(true, Collections.<String>emptySet(), "com/acme/");
        assertTrue(prefixed.shouldRewriteExecutorCalls("com/acme/service/OrderService"));
        assertTrue("app package /vendor/ must NOT be skipped",
                prefixed.shouldRewriteExecutorCalls("com/acme/vendor/VendorService"));
        assertTrue("app package /shadow/ must NOT be skipped",
                prefixed.shouldRewriteExecutorCalls("com/acme/shadow/ShadowService"));
        assertFalse(prefixed.shouldRewriteExecutorCalls("com/acme/shaded/io/grpc/Channel"));
        assertFalse(prefixed.shouldRewriteExecutorCalls("com/acme/internal/shaded/Foo"));
        assertFalse(prefixed.shouldRewriteExecutorCalls("com/acme/relocated/netty/Loop"));
        assertFalse(prefixed.shouldRewriteExecutorCalls("com/acme/repackaged/Pool"));

        // In exact appClasses index mode, shaded filter does NOT drop indexed app classes.
        ServerEntries indexed = new ServerEntries(true,
                new HashSet<>(Arrays.asList("com/acme/shaded/AppClass", "com/acme/vendor/VendorService")), null);
        assertTrue(indexed.shouldRewriteExecutorCalls("com/acme/shaded/AppClass"));
        assertTrue(indexed.shouldRewriteExecutorCalls("com/acme/vendor/VendorService"));
    }

    @Test(timeout = 60000)
    public void untrackedTailsJobDeferredCfSpringSubclassFailClosedAndBytecodeForms() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        long savedTailWindow = RequestGate.untrackedTailWindowNanos;
        try {
            // Untracked async hand-off marks current window AND immediately following window
            // "tail:<id>" until untrackedTailWindowNanos elapses.
            RequestGate.untrackedTailWindowNanos = TimeUnit.MILLISECONDS.toNanos(120);
            RequestGate.open("untracked-tail-1", null);
            Object req1 = RequestGate.enter();
            try {
                RequestGate.noteUntrackedAsync("untracked-executor:com.acme.CustomPool");
            } finally {
                RequestGate.exit(req1);
            }
            RequestGate.WindowOutcome out1 = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertEquals(Collections.singletonList("untracked-executor:com.acme.CustomPool"), out1.contaminations);

            RequestGate.open("untracked-tail-next", null);
            RequestGate.WindowOutcome out2 = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertEquals("next window immediately after untracked hand-off must carry tail",
                    Collections.singletonList("tail:untracked-tail-1"), out2.contaminations);

            Thread.sleep(160);
            RequestGate.open("untracked-tail-after-expiry", null);
            RequestGate.WindowOutcome out3 = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertEquals("clean after untracked tail window expires",
                    Collections.<String>emptyList(), out3.contaminations);

            // Job-originated CompletableFuture.supplyAsync().thenAccept(audit) keeps JobGate run
            // active until audit finishes, and job-originated unknown executor flags contamination!
            CountDownLatch gateSupplier = new CountDownLatch(1);
            CountDownLatch auditEntered = new CountDownLatch(1);
            CountDownLatch auditRelease = new CountDownLatch(1);
            RequestGate.open("job-cf-arm", null);
            JobGate.open();
            Object jt = JobGate.enter(new Object(), "scheduled");
            CompletableFuture<Void> jobChain;
            try {
                CompletableFuture<String> base = RequestGate.supplyAsync(() -> {
                    try {
                        gateSupplier.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "job-val";
                }, pool);
                jobChain = base.thenAccept(v -> {
                    auditEntered.countDown();
                    try {
                        auditRelease.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } finally {
                JobGate.exit(jt);
            }
            JobGate.close();
            gateSupplier.countDown();
            assertTrue("job CF audit entered", auditEntered.await(10, TimeUnit.SECONDS));
            // While audit is still running AFTER supplier returned, JobGate MUST still be active!
            JobGate.open();
            List<String> duringAudit = JobGate.close();
            assertEquals("job CF dependent stage must remain attributed in JobGate: " + duringAudit,
                    1, duringAudit.size());
            auditRelease.countDown();
            jobChain.get(10, TimeUnit.SECONDS);
            long jobDoneDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (JobGate.activeRunCount() > 0 && System.nanoTime() < jobDoneDeadline) {
                Thread.sleep(5);
            }
            assertEquals("JobGate run exits once job CF dependent completes", 0, JobGate.activeRunCount());

            // Job-originated unknown executor flags active JobGate window AND next RequestGate window!
            Object unknownExec = new Object();
            JobGate.open();
            Object jt2 = JobGate.enter(new Object(), "quartz");
            try {
                Runnable task = () -> { };
                assertSame("unknown executor stays unwrapped for job", task, RequestGate.hand(unknownExec, task));
            } finally {
                JobGate.exit(jt2);
            }
            List<String> jobContams = JobGate.close();
            assertTrue("job window must contain untracked-executor: " + jobContams,
                    jobContams.contains("untracked-executor:java.lang.Object"));
            RequestGate.open("after-job-unknown", null);
            RequestGate.WindowOutcome afterJobUnknown = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertTrue("next request window carries tail from job untracked hand-off: "
                    + afterJobUnknown.contaminations,
                    afterJobUnknown.contaminations.contains("tail:untracked-executor:java.lang.Object"));

            // App subclass of ThreadPoolTaskExecutor overriding execute() MUST fail closed,
            // and arbitrary non-Spring class with getThreadPoolExecutor() MUST fail closed!
            org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor overridingSpring =
                    new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor() {
                        private static final long serialVersionUID = 1L;

                        @Override
                        public void execute(Runnable task) {
                            super.execute(task);
                        }
                    };
            overridingSpring.initialize();
            try {
                assertTrue("Spring subclass overriding execute() must fail closed",
                        OrderedPools.orders(overridingSpring));
            } finally {
                overridingSpring.shutdown();
            }
            Object nonSpringWithPoolMethod = new Object() {
                @SuppressWarnings("unused")
                public ThreadPoolExecutor getThreadPoolExecutor() {
                    return pool;
                }
            };
            assertTrue("non-Spring class with getThreadPoolExecutor() must fail closed",
                    OrderedPools.orders(nonSpringWithPoolMethod));

            // Non-Thread start() must NOT false-positive, while Thread subclass start() DOES flag.
            RequestGate.disarm();
            RequestGate.open("non-thread-start-check", null);
            Object reqStart = RequestGate.enter();
            try {
                RequestGate.noteIfThread(new Object());
                RequestGate.noteIfThread(null);
            } finally {
                RequestGate.exit(reqStart);
            }
            assertEquals("non-Thread start() must not contaminate",
                    Collections.<String>emptyList(),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);

            // Direct raw KafkaConsumer.poll in an app class flips hasUnhookedConsumer()!
            ServerEntries.setHookStatusForTests(false, false, false, false);
            assertFalse(ServerEntries.hasUnhookedConsumer());
            ClassWriter kafkaApp = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            kafkaApp.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/acme/RawKafkaPoller", null,
                    "java/lang/Object", null);
            emitDefaultCtor(kafkaApp);
            MethodVisitor pollMv = kafkaApp.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "pollOnce", "(Lorg/apache/kafka/clients/consumer/KafkaConsumer;)V", null, null);
            pollMv.visitCode();
            pollMv.visitVarInsn(Opcodes.ALOAD, 0);
            pollMv.visitLdcInsn(100L);
            pollMv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/apache/kafka/clients/consumer/KafkaConsumer",
                    "poll", "(J)Ljava/lang/Object;", false);
            pollMv.visitInsn(Opcodes.POP);
            pollMv.visitInsn(Opcodes.RETURN);
            pollMv.visitMaxs(3, 1);
            pollMv.visitEnd();
            kafkaApp.visitEnd();
            ServerEntries.wrapExecutorCalls(kafkaApp.toByteArray());
            assertTrue("direct KafkaConsumer.poll in app class must flag unhooked consumer",
                    ServerEntries.hasUnhookedConsumer());
            ServerEntries.setHookStatusForTests(false, false, false, false);
        } finally {
            RequestGate.untrackedTailWindowNanos = savedTailWindow;
            pool.shutdownNow();
        }
    }

    @Test(timeout = 60000)
    public void minimalStageNoUoeMinimalFutureTrackedAbandonedStageGcAndConsumerScanChecks() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        ScheduledThreadPoolExecutor stpe = new ScheduledThreadPoolExecutor(1);
        long savedTailWindow = RequestGate.untrackedTailWindowNanos;
        long savedMaxLingering = RequestGate.maxLingeringNanos;
        long savedJobMax = JobGate.handedJobMaxNanos;
        try {
            // JDK 9+ MinimalStage (completedStage / failedStage / minimalCompletionStage)
            // throws UnsupportedOperationException on isDone(). Rewritten plain dependents
            // MUST NEVER throw UOE either outside or inside a request!
            RequestGate.open("minimal-stage-check", null);
            try {
                Method completedStageM = CompletableFuture.class.getMethod("completedStage", Object.class);
                Method failedStageM = CompletableFuture.class.getMethod("failedStage", Throwable.class);
                Method minimalStageM = CompletableFuture.class.getMethod("minimalCompletionStage");

                @SuppressWarnings("unchecked")
                java.util.concurrent.CompletionStage<String> cs =
                        (java.util.concurrent.CompletionStage<String>) completedStageM.invoke(null, "hi");
                // Outside a request:
                assertEquals("hi!", RequestGate.stageThenApply(cs, s -> s + "!").toCompletableFuture().get());
                CompletableFuture<String> base = CompletableFuture.completedFuture("a");
                assertEquals("a+hi", RequestGate.thenCombine(base, cs, (a, b) -> a + "+" + b).get());

                // Inside an active armed request:
                Object req = RequestGate.enter();
                try {
                    assertEquals("hi?", RequestGate.stageThenApply(cs, s -> s + "?").toCompletableFuture().get());
                    CompletableFuture<String> incomplete = new CompletableFuture<>();
                    CompletableFuture<String> combined = RequestGate.thenCombine(incomplete, cs, (a, b) -> a + ":" + b);
                    incomplete.complete("ok");
                    assertEquals("ok:hi", combined.get(5, TimeUnit.SECONDS));

                    @SuppressWarnings("unchecked")
                    java.util.concurrent.CompletionStage<String> failed =
                            (java.util.concurrent.CompletionStage<String>) failedStageM.invoke(
                                    null, new RuntimeException("boom"));
                    assertEquals("recovered", RequestGate.stageExceptionally(failed, e -> "recovered")
                            .toCompletableFuture().get(5, TimeUnit.SECONDS));

                    CompletableFuture<String> live = new CompletableFuture<>();
                    @SuppressWarnings("unchecked")
                    java.util.concurrent.CompletionStage<String> minimal =
                            (java.util.concurrent.CompletionStage<String>) minimalStageM.invoke(live);
                    java.util.concurrent.CompletionStage<String> mapped =
                            RequestGate.stageThenApply(minimal, s -> s + "-done");
                    live.complete("val");
                    assertEquals("val-done", mapped.toCompletableFuture().get(5, TimeUnit.SECONDS));
                } finally {
                    RequestGate.exit(req);
                }
                RequestGate.WindowOutcome minimalStageOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
                assertTrue("MinimalStage dependents must drain cleanly", minimalStageOut.drained);
                assertEquals("MinimalStage must not spuriously contaminate: " + minimalStageOut.contaminations,
                        Collections.<String>emptyList(), minimalStageOut.contaminations);
            } catch (NoSuchMethodException java8Skip) {
                // Java 8 has no completedStage/minimalCompletionStage.
            }

            // CompletableFuture subclass inheriting thenAccept/whenComplete (like JDK HttpClient's
            // MinimalFuture) IS tracked instead of flagged; subclass overriding thenAccept fails closed!
            class MinimalFutureLike<T> extends CompletableFuture<T> {
                @SuppressWarnings("unused")
                public CompletableFuture<T> newIncompleteFuture() {
                    return new MinimalFutureLike<T>();
                }
            }
            class OverridingCf<T> extends CompletableFuture<T> {
                @Override
                public CompletableFuture<Void> thenAccept(java.util.function.Consumer<? super T> action) {
                    return super.thenAccept(action);
                }
            }
            RequestGate.open("minimal-future-sub", null);
            Object reqMinimal = RequestGate.enter();
            MinimalFutureLike<String> httpCf = new MinimalFutureLike<>();
            AtomicBoolean auditRan = new AtomicBoolean();
            try {
                RequestGate.thenAccept(httpCf, v -> auditRan.set(true));
                assertEquals("MinimalFuture-like subclass must be tracked", 2, RequestGate.running());
            } finally {
                RequestGate.exit(reqMinimal);
            }
            assertEquals("window still waits for MinimalFuture-like dependent", 1, RequestGate.running());
            httpCf.complete("200");
            assertTrue(auditRan.get());
            RequestGate.WindowOutcome minimalOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertTrue(minimalOut.drained);
            assertEquals("MinimalFuture-like dependent must NOT contaminate",
                    Collections.<String>emptyList(), minimalOut.contaminations);

            RequestGate.open("override-cf-sub", null);
            Object reqOverride = RequestGate.enter();
            try {
                RequestGate.thenAccept(new OverridingCf<String>(), v -> { });
            } finally {
                RequestGate.exit(reqOverride);
            }
            RequestGate.WindowOutcome overrideOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertTrue("CF subclass overriding thenAccept must fail closed: " + overrideOut.contaminations,
                    overrideOut.contaminations.stream().anyMatch(s -> s.startsWith("untracked-executor:")));

            // Abandoned plain dependent whose future is GC'd releases its permit via DEAD_HANDS,
            // AND a stuck strongly-referenced future stops poisoning windows after maxLingeringNanos.
            RequestGate.disarm();
            RequestGate.open("abandoned-cf-gc", null);
            Object reqGc = RequestGate.enter();
            try {
                CompletableFuture<String> abandoned = new CompletableFuture<>();
                RequestGate.thenAccept(abandoned, v -> { });
                assertEquals(2, RequestGate.running());
                abandoned = null;
            } finally {
                RequestGate.exit(reqGc);
            }
            long gcDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (RequestGate.running() > 0 && System.nanoTime() < gcDeadline) {
                System.gc();
                Thread.sleep(20);
            }
            assertEquals("GC'd abandoned plain-dependent future must release window permit",
                    0, RequestGate.running());

            RequestGate.maxLingeringNanos = TimeUnit.MILLISECONDS.toNanos(100);
            CompletableFuture<String> neverCompletes = new CompletableFuture<>();
            RequestGate.open("stuck-cf", null);
            Object reqStuck = RequestGate.enter();
            try {
                RequestGate.thenAccept(neverCompletes, v -> { });
            } finally {
                RequestGate.exit(reqStuck);
            }
            assertFalse("stuck future cuts window", RequestGate.await(TimeUnit.MILLISECONDS.toNanos(30)));
            RequestGate.open("while-stuck", null);
            assertEquals(Collections.singletonList("tail:stuck-cf"),
                    RequestGate.awaitWindow(TimeUnit.MILLISECONDS.toNanos(50), true).contaminations);
            Thread.sleep(140);
            RequestGate.open("after-stuck-bound", null);
            assertEquals("stuck lingering window must stop poisoning after maxLingeringNanos",
                    Collections.<String>emptyList(),
                    RequestGate.awaitWindow(TimeUnit.MILLISECONDS.toNanos(50), true).contaminations);
            RequestGate.maxLingeringNanos = savedMaxLingering;

            // Runtime receiver check for hooked Spring executor vs custom TaskExecutor +
            // TaskScheduler.schedule / Timer.schedule / ForkJoinPool.execute(ForkJoinTask) / Guava.
            RequestGate.disarm();
            org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor springExec =
                    new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
            springExec.initialize();
            try {
                ServerEntries.markSpringExecutorHookedForTests(springExec.getClass().getName());
                RequestGate.open("spring-hooked-ok", null);
                Object reqSp = RequestGate.enter();
                try {
                    RequestGate.noteIfUntrackedExecutor(springExec);
                } finally {
                    RequestGate.exit(reqSp);
                }
                assertEquals("hooked & verified Spring executor must NOT false-positive",
                        Collections.<String>emptyList(),
                        RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);

                RequestGate.open("custom-task-exec", null);
                Object reqCustom = RequestGate.enter();
                org.springframework.core.task.TaskExecutor customTe = r -> r.run();
                try {
                    RequestGate.noteIfUntrackedExecutor(customTe);
                } finally {
                    RequestGate.exit(reqCustom);
                }
                RequestGate.WindowOutcome customOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
                assertTrue("unhooked TaskExecutor impl must be flagged by runtime class: "
                        + customOut.contaminations,
                        customOut.contaminations.contains("untracked-executor:" + customTe.getClass().getName()));
            } finally {
                springExec.shutdown();
            }

            // schedule(r, delay, unit) extends untracked tail window by delay!
            RequestGate.disarm();
            RequestGate.untrackedTailWindowNanos = TimeUnit.MILLISECONDS.toNanos(50);
            RequestGate.open("sched-delay-check", null);
            Object reqSched = RequestGate.enter();
            try {
                RequestGate.schedule(stpe, () -> { }, 300, TimeUnit.MILLISECONDS);
            } finally {
                RequestGate.exit(reqSched);
            }
            RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            Thread.sleep(100); // past 50ms base window, still inside 350ms delay-extended tail!
            RequestGate.open("sched-during-delay", null);
            assertEquals("scheduled delay must keep next window contaminated past base tail",
                    Collections.singletonList("tail:sched-delay-check"),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);
            RequestGate.untrackedTailWindowNanos = savedTailWindow;

            // Expired job hop that fans out to an unverified executor flags job-tail on active & next window,
            // and on a verified executor propagates expired Attribution through gen 2 & gen 3!
            RequestGate.disarm();
            JobGate.handedJobMaxNanos = TimeUnit.MILLISECONDS.toNanos(40);
            RequestGate.open("expired-hop-arm", null);
            Object unknownExecHop = new Object();
            Runnable hop1;
            Object jTok = JobGate.enter(new Object(), "scheduled");
            try {
                hop1 = RequestGate.hand(pool, () -> {
                    try {
                        Thread.sleep(70);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    assertNull("hop1 expired so running() is null", JobGate.running());
                    assertNotNull("hop1 expiredRunningName() still knows job", JobGate.expiredRunningName());
                    JobGate.open();
                    RequestGate.hand(unknownExecHop, () -> { });
                });
            } finally {
                JobGate.exit(jTok);
            }
            hop1.run();
            List<String> expiredJobContams = JobGate.close();
            assertTrue("expired hop unverified fan-out must mark JobGate window job-tail: " + expiredJobContams,
                    expiredJobContams.stream().anyMatch(s -> s.startsWith("job-tail:")));
            RequestGate.open("expired-hop-next", null);
            RequestGate.WindowOutcome expiredHopNext = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertTrue("expired hop unverified fan-out must mark next RequestGate window tail:job-tail: "
                    + expiredHopNext.contaminations,
                    expiredHopNext.contaminations.stream().anyMatch(s -> s.startsWith("tail:job-tail:")));

            // Unarmed hand-off made while unarmed keeps marking armed-mid-flight within tail window!
            RequestGate.disarm();
            RequestGate.untrackedTailWindowNanos = TimeUnit.MILLISECONDS.toNanos(200);
            Object unarmedReq = RequestGate.enter();
            CountDownLatch unarmedDone = new CountDownLatch(1);
            RequestGate.execute(pool, unarmedDone::countDown);
            RequestGate.exit(unarmedReq);
            assertTrue(unarmedDone.await(5, TimeUnit.SECONDS));
            RequestGate.open("unarmed-tail-w1", null);
            assertEquals(Collections.singletonList("armed-mid-flight"),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);
            RequestGate.open("unarmed-tail-w2", null);
            assertEquals("unarmed hand-off keeps marking armed-mid-flight within tail window",
                    Collections.singletonList("armed-mid-flight"),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);
            RequestGate.untrackedTailWindowNanos = savedTailWindow;

            // Expanded unhooked consumers + read-only scan works even with empty appClasses!
            for (String consumerCls : new String[] {
                    "org/springframework/data/redis/listener/RedisMessageListenerContainer",
                    "software/amazon/awssdk/services/sqs/DefaultSqsAsyncClient",
                    "org/apache/pulsar/client/impl/ConsumerImpl",
                    "com/google/cloud/pubsub/v1/Subscriber"
            }) {
                ServerEntries.setHookStatusForTests(false, false, false, false);
                new ServerEntries(true).transform(getClass().getClassLoader(), consumerCls, null, null,
                        generatePlainClass(consumerCls));
                assertTrue(consumerCls + " must flip hasUnhookedConsumer",
                        ServerEntries.hasUnhookedConsumer());
            }
            // Read-only raw JMS setMessageListener scan with NO appClasses / callsites off:
            ServerEntries.setHookStatusForTests(false, false, false, false);
            ClassWriter jmsApp = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            jmsApp.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/acme/RawJmsApp", null, "java/lang/Object", null);
            emitDefaultCtor(jmsApp);
            MethodVisitor jmsMv = jmsApp.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "wire", "(Ljavax/jms/MessageConsumer;Ljava/lang/Object;)V", null, null);
            jmsMv.visitCode();
            jmsMv.visitVarInsn(Opcodes.ALOAD, 0);
            jmsMv.visitVarInsn(Opcodes.ALOAD, 1);
            jmsMv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "javax/jms/MessageConsumer",
                    "setMessageListener", "(Ljava/lang/Object;)V", true);
            jmsMv.visitInsn(Opcodes.RETURN);
            jmsMv.visitMaxs(2, 2);
            jmsMv.visitEnd();
            jmsApp.visitEnd();
            assertNull("callsites-off ServerEntries must not modify bytecode",
                    new ServerEntries(true, Collections.<String>emptySet(), null)
                            .transform(getClass().getClassLoader(), "com/acme/RawJmsApp", null, null,
                                    jmsApp.toByteArray()));
            assertTrue("raw JMS setMessageListener must be detected even with callsites off",
                    ServerEntries.hasUnhookedConsumer());
            ServerEntries.setHookStatusForTests(false, false, false, false);

            // Exact indexed app classes under org/springframework/samples/... must NOT be dropped!
            ServerEntries petclinic = new ServerEntries(true,
                    Collections.singleton("org/springframework/samples/petclinic/owner/OwnerController"), null);
            assertTrue("indexed PetClinic class under org/springframework/samples must be rewritten",
                    petclinic.shouldRewriteExecutorCalls(
                            "org/springframework/samples/petclinic/owner/OwnerController"));
        } finally {
            RequestGate.untrackedTailWindowNanos = savedTailWindow;
            RequestGate.maxLingeringNanos = savedMaxLingering;
            JobGate.handedJobMaxNanos = savedJobMax;
            pool.shutdownNow();
            stpe.shutdownNow();
        }
    }

    @Test(timeout = 60000)
    public void executingPastMaxLingeringLiveUntrackedHandlesMultiHopExpiredJobAndBytecodeFlagSites()
            throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        ScheduledThreadPoolExecutor stpe = new ScheduledThreadPoolExecutor(1);
        java.util.concurrent.ForkJoinPool fjp = new java.util.concurrent.ForkJoinPool(2);
        java.util.Timer timer = new java.util.Timer(true);
        long savedTailWindow = RequestGate.untrackedTailWindowNanos;
        long savedMaxLingering = RequestGate.maxLingeringNanos;
        long savedJobMax = JobGate.handedJobMaxNanos;
        try {
            // Actively executing task running PAST maxLingeringNanos MUST keep marking tail:<id>
            // until it actually exits, then clear cleanly!
            RequestGate.disarm();
            RequestGate.maxLingeringNanos = TimeUnit.MILLISECONDS.toNanos(50);
            CountDownLatch taskStarted = new CountDownLatch(1);
            CountDownLatch taskRelease = new CountDownLatch(1);
            RequestGate.open("exec-active", null);
            Object reqActive = RequestGate.enter();
            try {
                RequestGate.execute(pool, latchTask(taskStarted, taskRelease));
            } finally {
                RequestGate.exit(reqActive);
            }
            assertTrue(taskStarted.await(5, TimeUnit.SECONDS));
            assertFalse("cut while task executes", RequestGate.await(TimeUnit.MILLISECONDS.toNanos(20)));
            Thread.sleep(100); // Well past 50ms maxLingeringNanos while task is STILL executing!
            RequestGate.open("exec-past-cap", null);
            assertEquals("actively executing task past maxLingeringNanos must STILL flag tail",
                    Collections.singletonList("tail:exec-active"),
                    RequestGate.awaitWindow(TimeUnit.MILLISECONDS.toNanos(50), true).contaminations);
            taskRelease.countDown();
            long drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (RequestGate.pendingByExecutorSize(pool) > 0 && System.nanoTime() < drainDeadline) {
                Thread.sleep(5);
            }
            Thread.sleep(20);
            RequestGate.open("exec-after-exit", null);
            assertEquals("clean after actively executing task finishes",
                    Collections.<String>emptyList(),
                    RequestGate.awaitWindow(TimeUnit.MILLISECONDS.toNanos(50), true).contaminations);
            RequestGate.maxLingeringNanos = savedMaxLingering;

            // Untracked Thread.start() and periodic ScheduledFuture stay flagged past base tail
            // for as long as Thread.isAlive() / !ScheduledFuture.isDone()!
            RequestGate.disarm();
            RequestGate.untrackedTailWindowNanos = TimeUnit.MILLISECONDS.toNanos(30);
            CountDownLatch rawStarted = new CountDownLatch(1);
            CountDownLatch rawRelease = new CountDownLatch(1);
            Thread rawThread = new Thread(latchTask(rawStarted, rawRelease), "live-raw-thread");
            rawThread.setDaemon(true);
            RequestGate.open("live-thread", null);
            Object reqT = RequestGate.enter();
            try {
                RequestGate.startThread(rawThread);
            } finally {
                RequestGate.exit(reqT);
            }
            assertTrue(rawStarted.await(5, TimeUnit.SECONDS));
            RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            // Consume one-time report first so the next window depends strictly on Thread.isAlive().
            RequestGate.open("live-thread-consume-once", null);
            RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            Thread.sleep(80); // Past 30ms base tail while rawThread.isAlive() is STILL true!
            RequestGate.open("live-thread-still-alive", null);
            assertEquals("live raw thread must keep marking tail past base window",
                    Collections.singletonList("tail:live-thread"),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);
            rawRelease.countDown();
            rawThread.join(5000);
            RequestGate.open("live-thread-dead", null);
            assertEquals("clean once raw thread terminates",
                    Collections.<String>emptyList(),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);

            // Periodic ScheduledFuture:
            RequestGate.disarm();
            RequestGate.untrackedTailWindowNanos = TimeUnit.MILLISECONDS.toNanos(30);
            RequestGate.open("periodic-sched", null);
            Object reqP = RequestGate.enter();
            ScheduledFuture<?> periodic;
            try {
                periodic = RequestGate.scheduleAtFixedRate(stpe, () -> { }, 5, 40, TimeUnit.MILLISECONDS);
            } finally {
                RequestGate.exit(reqP);
            }
            RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            // Consume one-time report first so the next window depends strictly on !ScheduledFuture.isDone().
            RequestGate.open("periodic-sched-consume-once", null);
            RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            Thread.sleep(100); // Past 30ms base tail + 45ms delay/period while periodic is still uncancelled!
            RequestGate.open("periodic-sched-alive", null);
            assertEquals("uncancelled periodic ScheduledFuture keeps marking tail",
                    Collections.singletonList("tail:periodic-sched"),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);
            periodic.cancel(false);
            RequestGate.open("periodic-sched-cancelled", null);
            assertEquals("clean once periodic ScheduledFuture is cancelled",
                    Collections.<String>emptyList(),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);
            RequestGate.untrackedTailWindowNanos = savedTailWindow;

            // Multi-hop expired self-resubmitting job loop (gen 1 -> gen 2 -> gen 3) on verified
            // executor MUST stay marked job-tail across ALL generations until it stops!
            RequestGate.disarm();
            JobGate.handedJobMaxNanos = TimeUnit.MILLISECONDS.toNanos(35);
            RequestGate.open("multihop-arm", null);
            CountDownLatch gen3Entered = new CountDownLatch(1);
            CountDownLatch gen3Release = new CountDownLatch(1);
            CountDownLatch gen1Exited = new CountDownLatch(1);
            CountDownLatch gen2Exited = new CountDownLatch(1);
            AtomicReference<String> gen1Running = new AtomicReference<>("unset");
            AtomicReference<String> gen2Running = new AtomicReference<>("unset");
            AtomicReference<String> gen3Running = new AtomicReference<>("unset");
            Object jTokC = JobGate.enter(new Object(), "scheduled");
            try {
                RequestGate.execute(pool, () -> {
                    try {
                        Thread.sleep(60); // Gen 1 expires past 35ms budget!
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    gen1Running.set(JobGate.running());
                    RequestGate.execute(pool, () -> {
                        try {
                            gen1Exited.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        gen2Running.set(JobGate.running());
                        RequestGate.execute(pool, () -> {
                            try {
                                gen2Exited.await(5, TimeUnit.SECONDS);
                                gen3Running.set(JobGate.running());
                                gen3Entered.countDown();
                                gen3Release.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
                        gen2Exited.countDown();
                    });
                    gen1Exited.countDown();
                });
            } finally {
                JobGate.exit(jTokC);
            }
            assertTrue("gen 3 entered after gen 1 and gen 2 exited", gen3Entered.await(10, TimeUnit.SECONDS));
            assertNull("gen 1 expired so JobGate.running() must be null", gen1Running.get());
            assertNull("gen 2 expired so JobGate.running() must be null", gen2Running.get());
            assertNull("gen 3 expired so JobGate.running() must be null", gen3Running.get());
            long waitSingleRun = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (JobGate.activeRunCount() > 1 && System.nanoTime() < waitSingleRun) {
                Thread.sleep(5);
            }
            assertEquals("only gen 3 run remains active after gen 1 and gen 2 exited", 1, JobGate.activeRunCount());
            JobGate.open();
            List<String> gen3Contams = JobGate.close();
            gen3Release.countDown();
            assertTrue("gen 3 of expired self-resubmitting loop must STILL carry job-tail: " + gen3Contams,
                    gen3Contams.stream().anyMatch(s -> s.startsWith("job-tail:")));
            long jobDrain = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (JobGate.activeRunCount() > 0 && System.nanoTime() < jobDrain) {
                Thread.sleep(5);
            }

            // Real bytecode-rewritten call sites via ServerEntries.wrapExecutorCalls for
            // TaskExecutor interface, TaskScheduler.schedule, Timer.schedule,
            // ForkJoinPool.execute(ForkJoinTask), and ListenableFuture.addListener!
            byte[] asyncSiteBytes = ServerEntries.wrapExecutorCalls(
                    generateAsyncFlagSiteBytecodeClass("com/acme/AsyncFlagCallSites"));
            assertNotNull("AsyncFlagCallSites must be rewritten by ServerEntries.wrapExecutorCalls", asyncSiteBytes);
            Class<?> asyncSiteClass = new ByteLoader(getClass().getClassLoader())
                    .define("com.acme.AsyncFlagCallSites", asyncSiteBytes);

            // 1. TaskExecutor interface with hooked+verified ThreadPoolTaskExecutor (CLEAN)
            //    vs unhooked custom TaskExecutor (FLAGGED).
            org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor springTe =
                    new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
            springTe.initialize();
            try {
                ServerEntries.markSpringExecutorHookedForTests(springTe.getClass().getName());
                RequestGate.disarm();
                RequestGate.open("spring-te-check", null);
                Object r1 = RequestGate.enter();
                CountDownLatch teRan = new CountDownLatch(1);
                try {
                    asyncSiteClass.getMethod("callTaskExecutor",
                            org.springframework.core.task.TaskExecutor.class, Runnable.class)
                            .invoke(null, springTe, (Runnable) teRan::countDown);
                } finally {
                    RequestGate.exit(r1);
                }
                assertTrue(teRan.await(5, TimeUnit.SECONDS));
                assertEquals("rewritten TaskExecutor.execute on hooked Spring pool must stay clean",
                        Collections.<String>emptyList(),
                        RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);

                RequestGate.disarm();
                RequestGate.open("custom-te-check", null);
                Object r2 = RequestGate.enter();
                org.springframework.core.task.TaskExecutor customTe = r -> r.run();
                try {
                    asyncSiteClass.getMethod("callTaskExecutor",
                            org.springframework.core.task.TaskExecutor.class, Runnable.class)
                            .invoke(null, customTe, (Runnable) () -> { });
                } finally {
                    RequestGate.exit(r2);
                }
                List<String> customTeContams = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations;
                assertTrue("rewritten TaskExecutor.execute on unhooked impl must flag: " + customTeContams,
                        customTeContams.contains("untracked-executor:" + customTe.getClass().getName()));
            } finally {
                ServerEntries.unmarkSpringExecutorHookedForTests(springTe.getClass().getName());
                springTe.shutdown();
            }

            // 2. Spring TaskScheduler.schedule(Runnable, Date) flags AND tracks returned ScheduledFuture!
            RequestGate.disarm();
            RequestGate.untrackedTailWindowNanos = TimeUnit.MILLISECONDS.toNanos(30);
            org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler springTs =
                    new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
            springTs.initialize();
            try {
                RequestGate.open("task-scheduler-check", null);
                Object rTs = RequestGate.enter();
                ScheduledFuture<?> tsFut;
                try {
                    tsFut = (ScheduledFuture<?>) asyncSiteClass.getMethod("callTaskScheduler",
                            org.springframework.scheduling.TaskScheduler.class, Runnable.class, java.util.Date.class)
                            .invoke(null, springTs, (Runnable) () -> { },
                                    new java.util.Date(System.currentTimeMillis() + 5000));
                } finally {
                    RequestGate.exit(rTs);
                }
                List<String> tsContams = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations;
                assertTrue("rewritten TaskScheduler.schedule must flag: " + tsContams,
                        tsContams.contains("untracked-async:schedule"));
                // Consume one-time report first so the next window depends strictly on !ScheduledFuture.isDone().
                RequestGate.open("ts-consume-once", null);
                RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
                Thread.sleep(60); // Past 30ms base tail while ScheduledFuture is still pending!
                RequestGate.open("ts-alive", null);
                assertEquals("TaskScheduler ScheduledFuture keeps tail active until cancelled",
                        Collections.singletonList("tail:task-scheduler-check"),
                        RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);
                tsFut.cancel(false);
            } finally {
                springTs.shutdown();
                RequestGate.untrackedTailWindowNanos = savedTailWindow;
            }

            // 3. Timer.schedule(TimerTask, long) flags untracked-async:timer!
            RequestGate.disarm();
            RequestGate.open("timer-flag-check", null);
            Object rTimer = RequestGate.enter();
            CountDownLatch timerRan = new CountDownLatch(1);
            try {
                asyncSiteClass.getMethod("callTimer", java.util.Timer.class, java.util.TimerTask.class, long.class)
                        .invoke(null, timer, new java.util.TimerTask() {
                            @Override
                            public void run() {
                                timerRan.countDown();
                            }
                        }, 1L);
            } finally {
                RequestGate.exit(rTimer);
            }
            assertTrue(timerRan.await(5, TimeUnit.SECONDS));
            assertTrue("rewritten Timer.schedule must flag untracked-async:timer",
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations
                            .contains("untracked-async:timer"));

            // 4. ForkJoinPool.execute(ForkJoinTask) flags untracked-async:forkjoin-task!
            RequestGate.disarm();
            RequestGate.open("fjp-flag-check", null);
            Object rFjp = RequestGate.enter();
            CountDownLatch fjpRan = new CountDownLatch(1);
            try {
                asyncSiteClass.getMethod("callFjpTask", java.util.concurrent.ForkJoinPool.class,
                        java.util.concurrent.ForkJoinTask.class)
                        .invoke(null, fjp, java.util.concurrent.ForkJoinTask.adapt(fjpRan::countDown));
            } finally {
                RequestGate.exit(rFjp);
            }
            assertTrue(fjpRan.await(5, TimeUnit.SECONDS));
            assertTrue("rewritten ForkJoinPool.execute(ForkJoinTask) must flag",
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations
                            .contains("untracked-async:forkjoin-task"));

            // 5. Guava ListenableFuture.addListener(Runnable, Executor) flags untracked-async:guava-future!
            RequestGate.disarm();
            RequestGate.open("guava-flag-check", null);
            Object rGuava = RequestGate.enter();
            try {
                asyncSiteClass.getMethod("callGuavaListener", FakeListenableFuture.class, Runnable.class, Executor.class)
                        .invoke(null, (FakeListenableFuture) (r, e) -> r.run(), (Runnable) () -> { }, pool);
            } finally {
                RequestGate.exit(rGuava);
            }
            assertTrue("rewritten addListener(Runnable, Executor) must flag untracked-async:guava-future",
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations
                            .contains("untracked-async:guava-future"));
        } finally {
            RequestGate.untrackedTailWindowNanos = savedTailWindow;
            RequestGate.maxLingeringNanos = savedMaxLingering;
            JobGate.handedJobMaxNanos = savedJobMax;
            timer.cancel();
            fjp.shutdownNow();
            pool.shutdownNow();
            stpe.shutdownNow();
        }
    }

    public interface FakeListenableFuture {
        void addListener(Runnable listener, Executor executor);
    }

    private static byte[] generateAsyncFlagSiteBytecodeClass(String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        emitDefaultCtor(cw);

        // callTaskExecutor(TaskExecutor, Runnable) -> INVOKEINTERFACE TaskExecutor.execute(Runnable)V
        MethodVisitor te = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "callTaskExecutor",
                "(Lorg/springframework/core/task/TaskExecutor;Ljava/lang/Runnable;)V", null, null);
        te.visitCode();
        te.visitVarInsn(Opcodes.ALOAD, 0);
        te.visitVarInsn(Opcodes.ALOAD, 1);
        te.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/springframework/core/task/TaskExecutor",
                "execute", "(Ljava/lang/Runnable;)V", true);
        te.visitInsn(Opcodes.RETURN);
        te.visitMaxs(2, 2);
        te.visitEnd();

        // callTaskScheduler(TaskScheduler, Runnable, Date) -> ScheduledFuture
        MethodVisitor ts = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "callTaskScheduler",
                "(Lorg/springframework/scheduling/TaskScheduler;Ljava/lang/Runnable;Ljava/util/Date;)"
                        + "Ljava/util/concurrent/ScheduledFuture;", null, null);
        ts.visitCode();
        ts.visitVarInsn(Opcodes.ALOAD, 0);
        ts.visitVarInsn(Opcodes.ALOAD, 1);
        ts.visitVarInsn(Opcodes.ALOAD, 2);
        ts.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/springframework/scheduling/TaskScheduler",
                "schedule", "(Ljava/lang/Runnable;Ljava/util/Date;)Ljava/util/concurrent/ScheduledFuture;", true);
        ts.visitInsn(Opcodes.ARETURN);
        ts.visitMaxs(3, 3);
        ts.visitEnd();

        // callTimer(Timer, TimerTask, long) -> void
        MethodVisitor tm = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "callTimer",
                "(Ljava/util/Timer;Ljava/util/TimerTask;J)V", null, null);
        tm.visitCode();
        tm.visitVarInsn(Opcodes.ALOAD, 0);
        tm.visitVarInsn(Opcodes.ALOAD, 1);
        tm.visitVarInsn(Opcodes.LLOAD, 2);
        tm.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Timer",
                "schedule", "(Ljava/util/TimerTask;J)V", false);
        tm.visitInsn(Opcodes.RETURN);
        tm.visitMaxs(4, 4);
        tm.visitEnd();

        // callFjpTask(ForkJoinPool, ForkJoinTask) -> void
        MethodVisitor fj = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "callFjpTask",
                "(Ljava/util/concurrent/ForkJoinPool;Ljava/util/concurrent/ForkJoinTask;)V", null, null);
        fj.visitCode();
        fj.visitVarInsn(Opcodes.ALOAD, 0);
        fj.visitVarInsn(Opcodes.ALOAD, 1);
        fj.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ForkJoinPool",
                "execute", "(Ljava/util/concurrent/ForkJoinTask;)V", false);
        fj.visitInsn(Opcodes.RETURN);
        fj.visitMaxs(2, 2);
        fj.visitEnd();

        // callGuavaListener(FakeListenableFuture, Runnable, Executor) -> void
        String flf = FakeListenableFuture.class.getName().replace('.', '/');
        MethodVisitor gv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "callGuavaListener",
                "(L" + flf + ";Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)V", null, null);
        gv.visitCode();
        gv.visitVarInsn(Opcodes.ALOAD, 0);
        gv.visitVarInsn(Opcodes.ALOAD, 1);
        gv.visitVarInsn(Opcodes.ALOAD, 2);
        gv.visitMethodInsn(Opcodes.INVOKEINTERFACE, flf,
                "addListener", "(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)V", true);
        gv.visitInsn(Opcodes.RETURN);
        gv.visitMaxs(3, 3);
        gv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test(timeout = 60000)
    public void lateStartContaminatesCurrentWindowTimerDelayAndPeriodicMultiHopScheduledRetryAndBranchyFrames()
            throws Exception {
        ThreadPoolExecutor singlePool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        ThreadPoolExecutor workerPool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        ScheduledThreadPoolExecutor stpe = new ScheduledThreadPoolExecutor(2);
        java.util.Timer oneShotTimer = new java.util.Timer(true);
        java.util.Timer periodicTimer = new java.util.Timer(true);
        long savedTailWindow = RequestGate.untrackedTailWindowNanos;
        long savedMaxLingering = RequestGate.maxLingeringNanos;
        try {
            // A queued task from an old window A whose idle permit expired
            // and was dropped from LINGERING MUST immediately contaminate
            // the CURRENT open window M when it finally starts executing
            // (even if it both starts and exits completely inside M)!
            RequestGate.disarm();
            RequestGate.maxLingeringNanos = TimeUnit.MILLISECONDS.toNanos(50);
            CountDownLatch blockerRunning = new CountDownLatch(1);
            CountDownLatch releaseBlocker = new CountDownLatch(1);
            // External untracked blocker occupying singlePool's sole worker thread.
            singlePool.execute(latchTask(blockerRunning, releaseBlocker));
            assertTrue(blockerRunning.await(5, TimeUnit.SECONDS));

            CountDownLatch lateTaskDone = new CountDownLatch(1);
            RequestGate.open("late-start-orig", null);
            Object reqA = RequestGate.enter();
            try {
                RequestGate.execute(singlePool, lateTaskDone::countDown);
            } finally {
                RequestGate.exit(reqA);
            }
            assertFalse("A is cut while late task sits queued", RequestGate.await(TimeUnit.MILLISECONDS.toNanos(15)));
            Thread.sleep(90); // Past 50ms maxLingeringNanos while executing == 0.
            // First open past cap reports cut once & drops A from LINGERING.
            RequestGate.open("late-start-dropper", null);
            RequestGate.awaitWindow(TimeUnit.MILLISECONDS.toNanos(20), true);

            // Open window M: A is already gone from LINGERING so M opens clean!
            RequestGate.open("late-start-target-m", null);
            // Release blocker so A's late task starts AND completes inside M!
            releaseBlocker.countDown();
            assertTrue("late task completed inside M", lateTaskDone.await(5, TimeUnit.SECONDS));
            List<String> mContams = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations;
            assertTrue("window M open when late task ran must be flagged tail:late-start-orig: " + mContams,
                    mContams.contains("tail:late-start-orig"));
            RequestGate.maxLingeringNanos = savedMaxLingering;

            // Rewritten java.util.Timer one-shot (delay-aware tail) and
            // periodic scheduleAtFixedRate (weak TimerTask liveness).
            byte[] timerBytes = ServerEntries.wrapExecutorCalls(generateTimerAndBranchyClass("com/acme/TimerAndBranchy"));
            assertNotNull("TimerAndBranchy must be rewritten by ServerEntries.wrapExecutorCalls", timerBytes);
            Class<?> timerClass = new ByteLoader(getClass().getClassLoader())
                    .define("com.acme.TimerAndBranchy", timerBytes);

            // One-shot Timer.schedule(TimerTask, long delay) past 30ms base tail
            // remains flagged through delay + base tail!
            RequestGate.disarm();
            RequestGate.untrackedTailWindowNanos = TimeUnit.MILLISECONDS.toNanos(30);
            CountDownLatch oneShotRan = new CountDownLatch(1);
            RequestGate.open("timer-oneshot", null);
            Object reqT1 = RequestGate.enter();
            try {
                timerClass.getMethod("scheduleOneShot", java.util.Timer.class, java.util.TimerTask.class, long.class)
                        .invoke(null, oneShotTimer, new java.util.TimerTask() {
                            @Override
                            public void run() {
                                oneShotRan.countDown();
                            }
                        }, 180L);
            } finally {
                RequestGate.exit(reqT1);
            }
            assertTrue("one-shot Timer.schedule flags untracked-async:timer",
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations
                            .contains("untracked-async:timer"));
            // Consume one-time report first so the next window proves delay-extended expiry (> 30ms base tail).
            RequestGate.open("timer-oneshot-consume-once", null);
            RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            Thread.sleep(75); // Past 30ms base tail, well before 180ms + 30ms extended tail!
            RequestGate.open("timer-oneshot-next", null);
            assertEquals("one-shot Timer delay must extend tail past base window",
                    Collections.singletonList("tail:timer-oneshot"),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);
            assertTrue(oneShotRan.await(5, TimeUnit.SECONDS));

            // Periodic Timer.scheduleAtFixedRate(TimerTask, long, long) stays
            // flagged past base tail + delay + period while scheduled!
            RequestGate.disarm();
            RequestGate.untrackedTailWindowNanos = TimeUnit.MILLISECONDS.toNanos(30);
            RequestGate.open("timer-periodic", null);
            Object reqT2 = RequestGate.enter();
            try {
                timerClass.getMethod("schedulePeriodic", java.util.Timer.class, long.class, long.class)
                        .invoke(null, periodicTimer, 5L, 25L);
            } finally {
                RequestGate.exit(reqT2);
            }
            assertTrue("periodic Timer.scheduleAtFixedRate flags untracked-async:timer",
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations
                            .contains("untracked-async:timer"));
            // Consume one-time report first so the next window depends strictly on periodic TimerTask liveness.
            RequestGate.open("timer-periodic-consume-once", null);
            RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            Thread.sleep(100); // Past 30ms + 5ms + 25ms tail while TimerTask is still in Timer's queue!
            RequestGate.open("timer-periodic-alive", null);
            assertEquals("periodic TimerTask held by Timer queue keeps tail active",
                    Collections.singletonList("tail:timer-periodic"),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);
            periodicTimer.cancel();

            // Multi-hop self-rescheduling retry loop on ScheduledThreadPoolExecutor
            // (gen 1 -> gen 2 -> downstream workerPool.execute) propagates window
            // context across hops without making END wait for delayed tasks!
            RequestGate.disarm();
            RequestGate.untrackedTailWindowNanos = TimeUnit.MILLISECONDS.toNanos(30);
            CountDownLatch gen1Done = new CountDownLatch(1);
            CountDownLatch gen2Started = new CountDownLatch(1);
            CountDownLatch gen2Release = new CountDownLatch(1);
            CountDownLatch downstreamStarted = new CountDownLatch(1);
            CountDownLatch downstreamRelease = new CountDownLatch(1);
            RequestGate.open("sched-retry", null);
            Object reqSchedRetry = RequestGate.enter();
            try {
                RequestGate.schedule(stpe, () -> {
                    // Gen 1 finishes quickly after scheduling Gen 2 with a 120ms delay.
                    RequestGate.schedule(stpe, () -> {
                        gen2Started.countDown();
                        try {
                            gen2Release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        RequestGate.execute(workerPool, latchTask(downstreamStarted, downstreamRelease));
                    }, 120, TimeUnit.MILLISECONDS);
                    gen1Done.countDown();
                }, 10, TimeUnit.MILLISECONDS);
            } finally {
                RequestGate.exit(reqSchedRetry);
            }
            RequestGate.WindowOutcome retryOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertTrue("END must not block on delayed scheduled task", retryOut.drained);
            assertTrue("flags untracked-async:schedule", retryOut.contaminations.contains("untracked-async:schedule"));
            assertTrue("gen 1 completed", gen1Done.await(5, TimeUnit.SECONDS));
            // Consume one-time report first so the next window proves gen-2 ScheduledFuture / delay tracking!
            RequestGate.open("sched-retry-consume-once", null);
            RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            Thread.sleep(50); // Past gen 1 completion + 30ms base tail while gen 2 is still pending!
            RequestGate.open("sched-retry-gen2-pending", null);
            assertEquals("gen 2 scheduled via propagated context keeps tail active past gen 1 base tail",
                    Collections.singletonList("tail:sched-retry"),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);
            assertTrue("gen 2 started", gen2Started.await(5, TimeUnit.SECONDS));
            gen2Release.countDown();
            assertTrue("downstream workerPool task started from gen 2", downstreamStarted.await(5, TimeUnit.SECONDS));
            Thread.sleep(50); // Past 30ms base tail after gen 2 exited while downstream workerPool task still runs!
            RequestGate.open("sched-retry-downstream", null);
            assertEquals("downstream workerPool task launched by gen 2 keeps tail:sched-retry",
                    Collections.singletonList("tail:sched-retry"),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);
            downstreamRelease.countDown();

            // Branchy + try/catch StackMapTable verifier fixture (V17 on JDK 17+, V1_8 on JDK 8):
            // proves post-call DUP + trackUntrackedFuture passes strict JVM verification with
            // branches and exception handlers!
            org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler springTs =
                    new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
            springTs.initialize();
            try {
                RequestGate.disarm();
                RequestGate.open("branchy-schedule", null);
                Object reqBr = RequestGate.enter();
                ScheduledFuture<?> brFut;
                try {
                    brFut = (ScheduledFuture<?>) timerClass.getMethod("branchySchedule",
                            org.springframework.scheduling.TaskScheduler.class, Runnable.class,
                            java.util.Date.class, boolean.class)
                            .invoke(null, springTs, (Runnable) () -> { },
                                    new java.util.Date(System.currentTimeMillis() + 5000), Boolean.TRUE);
                } finally {
                    RequestGate.exit(reqBr);
                }
                assertNotNull(brFut);
                brFut.cancel(false);
                assertTrue("branchy rewritten TaskScheduler.schedule flags untracked-async:schedule",
                        RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations
                                .contains("untracked-async:schedule"));
            } finally {
                springTs.shutdown();
            }
        } finally {
            RequestGate.untrackedTailWindowNanos = savedTailWindow;
            RequestGate.maxLingeringNanos = savedMaxLingering;
            oneShotTimer.cancel();
            periodicTimer.cancel();
            singlePool.shutdownNow();
            workerPool.shutdownNow();
            stpe.shutdownNow();
        }
    }

    public static final class PlainSyncTxHelper {
        public void execute(Runnable action) {
            action.run();
        }

        public Object submit(Runnable action) {
            action.run();
            return "ok";
        }

        public Object submit(Runnable action, Object result) {
            action.run();
            return result;
        }
    }

    public interface SubtypeFuture<V> extends Future<V> {
    }

    public interface ListenableLikeExecutorService extends ExecutorService {
        <T> SubtypeFuture<T> submit(Runnable task, T result);
    }

    public static final class CustomListenableLikeExecutor extends AbstractExecutorService
            implements ListenableLikeExecutorService {
        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public <T> SubtypeFuture<T> submit(Runnable task, T result) {
            task.run();
            final FutureTask<T> ft = new FutureTask<>(task, result);
            ft.run();
            return new SubtypeFuture<T>() {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    return ft.cancel(mayInterruptIfRunning);
                }

                @Override
                public boolean isCancelled() {
                    return ft.isCancelled();
                }

                @Override
                public boolean isDone() {
                    return ft.isDone();
                }

                @Override
                public T get() throws InterruptedException, java.util.concurrent.ExecutionException {
                    return ft.get();
                }

                @Override
                public T get(long timeout, TimeUnit unit)
                        throws InterruptedException, java.util.concurrent.ExecutionException,
                        java.util.concurrent.TimeoutException {
                    return ft.get(timeout, unit);
                }
            };
        }
    }

    public static final class CustomWorkerThread extends Thread {
        public CustomWorkerThread(Runnable r) {
            super(r, "custom-worker-thread");
            setDaemon(true);
        }
    }

    @Test(timeout = 60000)
    public void nonExecutorSyncHelperTrackedHandleLivenessInlineExecutorsAndBackgroundSuppression()
            throws Exception {
        ScheduledThreadPoolExecutor stpe = new ScheduledThreadPoolExecutor(1);
        java.util.Timer timer = new java.util.Timer(true);
        long savedTailWindow = RequestGate.untrackedTailWindowNanos;
        try {
            // Bytecode-rewritten plain synchronous helper TxHelper.execute(Runnable)
            // and TxHelper.submit(Runnable) (NOT implementing Executor/ExecutorService) MUST NOT
            // contaminate the current window or any subsequent window!
            byte[] callerRaw = generateTxHelperAndTimerCancelCaller("com/acme/SyncHelperAndTimerCaller");
            byte[] callerRewritten = ServerEntries.wrapExecutorCalls(callerRaw);
            assertNotNull("SyncHelperAndTimerCaller must be rewritten by ServerEntries.wrapExecutorCalls", callerRewritten);
            Class<?> caller = new ByteLoader(getClass().getClassLoader())
                    .define("com.acme.SyncHelperAndTimerCaller", callerRewritten);

            RequestGate.disarm();
            RequestGate.open("sync-helper", null);
            Object reqSync = RequestGate.enter();
            AtomicBoolean execRan = new AtomicBoolean(false);
            AtomicBoolean submitRan = new AtomicBoolean(false);
            try {
                PlainSyncTxHelper helper = new PlainSyncTxHelper();
                caller.getMethod("callSyncHelper", PlainSyncTxHelper.class, Runnable.class, Runnable.class)
                        .invoke(null, helper,
                                (Runnable) () -> execRan.set(true),
                                (Runnable) () -> submitRan.set(true));
            } finally {
                RequestGate.exit(reqSync);
            }
            assertTrue("TxHelper.execute ran", execRan.get());
            assertTrue("TxHelper.submit ran", submitRan.get());
            assertEquals("non-Executor TxHelper.execute/submit must NOT contaminate current window",
                    Collections.<String>emptyList(),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);
            RequestGate.open("sync-helper-next", null);
            assertEquals("non-Executor TxHelper.execute/submit must NOT taint subsequent window",
                    Collections.<String>emptyList(),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);

            // Positive control: a real custom Executor/ExecutorService still gets flagged!
            RequestGate.open("real-custom-exec", null);
            Object reqCustomExec = RequestGate.enter();
            Executor customExec = r -> r.run();
            try {
                RequestGate.noteIfUntrackedExecutor(customExec);
            } finally {
                RequestGate.exit(reqCustomExec);
            }
            RequestGate.WindowOutcome customExecOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertTrue("real custom Executor must still be flagged: " + customExecOut.contaminations,
                    customExecOut.contaminations.contains("untracked-executor:" + customExec.getClass().getName()));

            // Request schedules a 30s timeout and cancels it before returning ->
            // next request MUST be CLEAN (no 32s tail: taint)!
            RequestGate.disarm();
            RequestGate.open("cancelled-timeout", null);
            Object reqTimeout = RequestGate.enter();
            try {
                ScheduledFuture<?> timeout = RequestGate.schedule(stpe, () -> { }, 30, TimeUnit.SECONDS);
                assertTrue("30s timeout cancelled before returning", timeout.cancel(false));
            } finally {
                RequestGate.exit(reqTimeout);
            }
            RequestGate.WindowOutcome timeoutOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertTrue("originating window still notes schedule",
                    timeoutOut.contaminations.contains("untracked-async:schedule"));
            RequestGate.open("cancelled-timeout-next", null);
            assertEquals("cancelled 30s timeout must NOT taint subsequent window",
                    Collections.<String>emptyList(),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);

            // Thread.start() + join() before response -> next request MUST be CLEAN!
            RequestGate.disarm();
            RequestGate.open("thread-join", null);
            Object reqJoin = RequestGate.enter();
            try {
                Thread t = new Thread(() -> { }, "joined-worker");
                RequestGate.startThread(t);
                t.join(5000);
                assertFalse("thread terminated before END", t.isAlive());
            } finally {
                RequestGate.exit(reqJoin);
            }
            RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            RequestGate.open("thread-join-next", null);
            assertEquals("joined Thread.start() must NOT leave 2s tail taint on next window",
                    Collections.<String>emptyList(),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);

            // Also verify bytecode-rewritten Thread subclass start() + join() before response -> CLEAN!
            RequestGate.disarm();
            RequestGate.open("subthread-join", null);
            Object reqSubJoin = RequestGate.enter();
            try {
                CustomWorkerThread sub = new CustomWorkerThread(() -> { });
                caller.getMethod("startCustomThread", CustomWorkerThread.class).invoke(null, sub);
                sub.join(5000);
                assertFalse("subclass thread terminated before END", sub.isAlive());
            } finally {
                RequestGate.exit(reqSubJoin);
            }
            RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            RequestGate.open("subthread-join-next", null);
            assertEquals("joined Thread subclass must NOT taint next window",
                    Collections.<String>emptyList(),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);

            // TimerTask cancellation & running-body liveness:
            // (i) Periodic TimerTask cancelled BEFORE first fire (retained strongly in a field) -> CLEAN immediately!
            RequestGate.disarm();
            java.util.TimerTask retainedTask = new NoopTimerTask();
            RequestGate.open("timer-cancel-before-fire", null);
            Object reqTimer = RequestGate.enter();
            try {
                caller.getMethod("scheduleAndCancelPeriodic", java.util.Timer.class, java.util.TimerTask.class)
                        .invoke(null, timer, retainedTask);
            } finally {
                RequestGate.exit(reqTimer);
            }
            RequestGate.WindowOutcome timerCancelOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertTrue("originating window notes untracked-async:timer",
                    timerCancelOut.contaminations.contains("untracked-async:timer"));
            RequestGate.open("timer-cancel-next", null);
            assertEquals("cancelled periodic TimerTask retained in a field must NOT taint next window",
                    Collections.<String>emptyList(),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);
            assertNotNull("keep strong reference alive past assertion", retainedTask);

            // (ii) One-shot TimerTask(delay=0) with a 300ms body STILL RUNNING
            //      at +100ms MUST contaminate t2 (never dropped at 50ms or on state=EXECUTED)!
            RequestGate.disarm();
            CountDownLatch bodyStarted = new CountDownLatch(1);
            CountDownLatch releaseBody = new CountDownLatch(1);
            java.util.TimerTask slowOneShot = new java.util.TimerTask() {
                @Override
                public void run() {
                    bodyStarted.countDown();
                    try {
                        releaseBody.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            RequestGate.open("slow-oneshot", null);
            Object reqSlowOneShot = RequestGate.enter();
            try {
                caller.getMethod("scheduleOneShotDelay", java.util.Timer.class, java.util.TimerTask.class, long.class)
                        .invoke(null, timer, slowOneShot, 0L);
            } finally {
                RequestGate.exit(reqSlowOneShot);
            }
            assertTrue("slow one-shot TimerTask started", bodyStarted.await(5, TimeUnit.SECONDS));
            RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            Thread.sleep(100); // Well past old 50ms grace while body is STILL running!
            RequestGate.open("during-slow-body", null);
            assertEquals("running one-shot TimerTask body MUST contaminate t2",
                    Collections.singletonList("tail:slow-oneshot"),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);
            // Calling cancel() while body is running returns false -> MUST STILL keep tail active!
            boolean cancelWhileRunning = (Boolean) caller.getMethod("cancelTask", java.util.TimerTask.class)
                    .invoke(null, slowOneShot);
            assertFalse("cancel() on already-running one-shot returns false", cancelWhileRunning);
            RequestGate.open("after-cancel-while-running", null);
            assertEquals("cancel() while body is running MUST NOT drop tail immediately",
                    Collections.singletonList("tail:slow-oneshot"),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);
            releaseBody.countDown();

            // (iii) Periodic TimerTask that ran >= 1 tick, then cancelled & retained in a field ->
            //       active during untrackedTailWindowNanos, then CLEAN (never stuck forever)!
            RequestGate.disarm();
            RequestGate.untrackedTailWindowNanos = TimeUnit.MILLISECONDS.toNanos(80);
            CountDownLatch periodicRanOnce = new CountDownLatch(1);
            java.util.TimerTask retainedRanPeriodic = new java.util.TimerTask() {
                @Override
                public void run() {
                    periodicRanOnce.countDown();
                }
            };
            RequestGate.open("periodic-ran-then-cancel", null);
            Object reqRanPer = RequestGate.enter();
            try {
                caller.getMethod("schedulePeriodicDelay", java.util.Timer.class, java.util.TimerTask.class,
                        long.class, long.class).invoke(null, timer, retainedRanPeriodic, 0L, 20L);
            } finally {
                RequestGate.exit(reqRanPer);
            }
            assertTrue("periodic TimerTask ran at least once", periodicRanOnce.await(5, TimeUnit.SECONDS));
            RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            caller.getMethod("cancelTask", java.util.TimerTask.class).invoke(null, retainedRanPeriodic);
            // Immediately after cancelling a periodic task that already ticked, the tail MUST
            // stay active during untrackedTailWindowNanos (proving curExec != initial guards a
            // potentially running tick body!).
            RequestGate.open("periodic-right-after-cancel", null);
            assertEquals("periodic TimerTask cancelled after ticking MUST keep tail active during tail window",
                    Collections.singletonList("tail:periodic-ran-then-cancel"),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);
            Thread.sleep(120); // Past 80ms untrackedTailWindowNanos after cancel!
            RequestGate.open("periodic-clean-after-tail", null);
            assertEquals("cancelled periodic TimerTask retained in field must become clean after tail window",
                    Collections.<String>emptyList(),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);
            assertNotNull("keep strong reference alive", retainedRanPeriodic);
            RequestGate.untrackedTailWindowNanos = savedTailWindow;

            // (iii-b) Rewritten Timer.cancel()V:
            //   1. Cancelling a 30s one-shot Timer BEFORE it fires -> immediately CLEAN!
            //   2. Cancelling a Timer while a 0ms one-shot body is STILL RUNNING -> keeps tail active!
            RequestGate.disarm();
            java.util.Timer cancelBeforeFireTimer = new java.util.Timer(true);
            RequestGate.open("timer-cancel-before-fire-2", null);
            Object reqTcb = RequestGate.enter();
            try {
                caller.getMethod("scheduleOneShotDelay", java.util.Timer.class, java.util.TimerTask.class, long.class)
                        .invoke(null, cancelBeforeFireTimer, new NoopTimerTask(), 30000L);
                caller.getMethod("cancelTimer", java.util.Timer.class).invoke(null, cancelBeforeFireTimer);
            } finally {
                RequestGate.exit(reqTcb);
            }
            RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            RequestGate.open("timer-cancel-before-fire-2-next", null);
            assertEquals("Timer.cancel() well before scheduled fire time leaves next window clean",
                    Collections.<String>emptyList(),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);

            RequestGate.disarm();
            java.util.Timer cancelWhileRunningTimer = new java.util.Timer(true);
            CountDownLatch timerBodyStarted = new CountDownLatch(1);
            CountDownLatch timerBodyRelease = new CountDownLatch(1);
            RequestGate.open("timer-cancel-running", null);
            Object reqTcr = RequestGate.enter();
            try {
                caller.getMethod("scheduleOneShotDelay", java.util.Timer.class, java.util.TimerTask.class, long.class)
                        .invoke(null, cancelWhileRunningTimer, new java.util.TimerTask() {
                            @Override
                            public void run() {
                                timerBodyStarted.countDown();
                                try {
                                    timerBodyRelease.await(5, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }, 0L);
                assertTrue("Timer body started before Timer.cancel()", timerBodyStarted.await(5, TimeUnit.SECONDS));
                caller.getMethod("cancelTimer", java.util.Timer.class).invoke(null, cancelWhileRunningTimer);
            } finally {
                RequestGate.exit(reqTcr);
            }
            RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            RequestGate.open("timer-cancel-running-next", null);
            assertEquals("Timer.cancel() while body is running MUST keep tail active",
                    Collections.singletonList("tail:timer-cancel-running"),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);
            timerBodyRelease.countDown();

            // (iv) One-shot TimerTask with 30s delay cancelled before returning -> immediately CLEAN!
            RequestGate.disarm();
            java.util.TimerTask retainedOneShot = new NoopTimerTask();
            RequestGate.open("oneshot-cancel", null);
            Object reqOneShot = RequestGate.enter();
            try {
                caller.getMethod("scheduleAndCancelOneShot", java.util.Timer.class, java.util.TimerTask.class)
                        .invoke(null, timer, retainedOneShot);
            } finally {
                RequestGate.exit(reqOneShot);
            }
            RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            RequestGate.open("oneshot-next-clean", null);
            assertEquals("cancelled one-shot 30s TimerTask must NOT taint next window",
                    Collections.<String>emptyList(),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);
            assertNotNull("keep strong reference alive", retainedOneShot);

            // Two-arg submit(Runnable, T) returning a Future subtype (like Guava ListenableFuture)
            // MUST flag untracked-executor on a custom ExecutorService, while ignoring PlainSyncTxHelper!
            RequestGate.disarm();
            RequestGate.open("listenable-submit2", null);
            Object reqSubmit2 = RequestGate.enter();
            CustomListenableLikeExecutor listenableExec = new CustomListenableLikeExecutor();
            try {
                Object res = caller.getMethod("callTwoArgSubmit", ListenableLikeExecutorService.class,
                        Runnable.class, Object.class).invoke(null, listenableExec, (Runnable) () -> { }, "val");
                assertNotNull("two-arg submit returned SubtypeFuture", res);
            } finally {
                RequestGate.exit(reqSubmit2);
            }
            RequestGate.WindowOutcome submit2Out = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertTrue("two-arg ListenableFuture submit must flag untracked-executor: " + submit2Out.contaminations,
                    submit2Out.contaminations.contains("untracked-executor:" + listenableExec.getClass().getName()));

            // Known same-thread inline executors (Spring SyncTaskExecutor,
            // ConcurrentTaskExecutor(SyncTaskExecutor), Guava DirectExecutor, and DirectExecutorService)
            // are NEITHER wrapped NOR flagged untracked-executor, AND *Async(fn, inlineExec) holds permit
            // while upstream CF is incomplete!
            RequestGate.disarm();
            org.springframework.core.task.SyncTaskExecutor syncExec =
                    new org.springframework.core.task.SyncTaskExecutor();
            org.springframework.scheduling.concurrent.ConcurrentTaskExecutor concurrentDefault =
                    new org.springframework.scheduling.concurrent.ConcurrentTaskExecutor(syncExec);
            Class<?> guavaDirectCls = new ByteLoader(getClass().getClassLoader()).define(
                    "com.google.common.util.concurrent.DirectExecutor",
                    generateInlineExecutorClass("com/google/common/util/concurrent/DirectExecutor"));
            Executor guavaDirect = (Executor) guavaDirectCls.getDeclaredConstructor().newInstance();
            Class<?> guavaDirectSvcCls = new ByteLoader(getClass().getClassLoader()).define(
                    "com.google.common.util.concurrent.DirectExecutorService",
                    generateInlineExecutorServiceClass("com/google/common/util/concurrent/DirectExecutorService"));
            ExecutorService guavaDirectSvc = (ExecutorService) guavaDirectSvcCls.getDeclaredConstructor().newInstance();
            Class<?> legacyGuavaDirectSvcCls = new ByteLoader(getClass().getClassLoader()).define(
                    "com.google.common.util.concurrent.MoreExecutors$DirectExecutorService",
                    generateInlineExecutorServiceClass(
                            "com/google/common/util/concurrent/MoreExecutors$DirectExecutorService"));
            ExecutorService legacyGuavaDirectSvc =
                    (ExecutorService) legacyGuavaDirectSvcCls.getDeclaredConstructor().newInstance();

            RequestGate.open("inline-exec-check", null);
            Object reqInline = RequestGate.enter();
            AtomicBoolean inlineRan1 = new AtomicBoolean(false);
            AtomicBoolean inlineRan2 = new AtomicBoolean(false);
            AtomicBoolean inlineRan3 = new AtomicBoolean(false);
            CompletableFuture<String> upstream = new CompletableFuture<>();
            CompletableFuture<String> asyncWithInline;
            try {
                Runnable raw1 = () -> inlineRan1.set(true);
                Runnable raw2 = () -> inlineRan2.set(true);
                Runnable raw3 = () -> inlineRan3.set(true);
                assertSame("SyncTaskExecutor must NOT wrap task", raw1, RequestGate.hand(syncExec, raw1));
                assertSame("Guava DirectExecutor must NOT wrap task", raw2, RequestGate.hand(guavaDirect, raw2));
                assertSame("Guava DirectExecutorService must NOT wrap task", raw3,
                        RequestGate.hand(guavaDirectSvc, raw3));
                assertSame("Guava MoreExecutors$DirectExecutorService must NOT wrap task", raw3,
                        RequestGate.hand(legacyGuavaDirectSvc, raw3));
                RequestGate.execute(syncExec, raw1);
                RequestGate.execute(guavaDirect, raw2);
                RequestGate.submit(guavaDirectSvc, raw3);
                RequestGate.noteIfUntrackedExecutor(syncExec);
                RequestGate.noteIfUntrackedExecutor(guavaDirect);
                RequestGate.noteIfUntrackedSubmit(guavaDirectSvc);
                RequestGate.noteIfUntrackedSubmit(legacyGuavaDirectSvc);
                assertFalse("ConcurrentTaskExecutor(SyncTaskExecutor) must be allow-listed",
                        OrderedPools.orders(concurrentDefault));
                asyncWithInline = RequestGate.thenApplyAsync(upstream, v -> v + "-done", syncExec);
            } finally {
                RequestGate.exit(reqInline);
            }
            assertTrue("SyncTaskExecutor task executed", inlineRan1.get());
            assertTrue("Guava DirectExecutor task executed", inlineRan2.get());
            assertTrue("Guava DirectExecutorService task executed", inlineRan3.get());
            assertEquals("thenApplyAsync(fn, inlineExec) must hold permit while upstream CF is incomplete",
                    1, RequestGate.running());
            upstream.complete("ok");
            assertEquals("ok-done", asyncWithInline.get(5, TimeUnit.SECONDS));
            assertEquals("permit released once upstream CF completes", 0, RequestGate.running());
            assertEquals("known inline executors must NOT contaminate current window",
                    Collections.<String>emptyList(),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);
            RequestGate.open("inline-exec-next", null);
            assertEquals("known inline executors must NOT taint next window",
                    Collections.<String>emptyList(),
                    RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);

            // Real CoverageTcpClient connect test with injected RuntimeException in
            // helloFrame() -> falls back to minimal empty-identity HELLO, stays connected (NO reconnect loop),
            // and serves NOP on the first connection!
            collector = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
            collector.setSoTimeout(15000);
            System.setProperty(ENDPOINT_PROPERTY, "127.0.0.1:" + collector.getLocalPort());
            System.setProperty(WARMUP_DISABLED_PROPERTY, "true");
            KeployDedupAgent.testHelloFailure = new RuntimeException("simulated connect-time HELLO failure");
            try {
                assertTrue(KeployDedupAgent.start());
                try (Socket sdk = collector.accept()) {
                    sdk.setSoTimeout(15000);
                    OutputStream out = sdk.getOutputStream();
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));
                    String fallbackHello = in.readLine();
                    JsonObject minObj = parseHello(fallbackHello);
                    assertEquals(3, minObj.get("proto").getAsInt());
                    assertEquals("", minObj.get("sdkVersion").getAsString());
                    assertEquals("", minObj.get("jvmNonce").getAsString());
                    assertEquals("", minObj.get("buildId").getAsString());
                    assertFalse(minObj.get("windowCut").getAsBoolean());
                    assertTrue(minObj.get("ackId").getAsBoolean());
                    assertTrue(minObj.get("nopResync").getAsBoolean());
                    // Must stay connected and serve commands without disconnect/reconnect loop!
                    send(out, "NOP after-hello-fallback");
                    assertEquals("ACK after-hello-fallback", in.readLine());
                }
            } finally {
                KeployDedupAgent.testHelloFailure = null;
                KeployDedupAgent.stop();
                collector.close();
                collector = null;
                System.clearProperty(ENDPOINT_PROPERTY);
            }
        } finally {
            RequestGate.untrackedTailWindowNanos = savedTailWindow;
            timer.cancel();
            stpe.shutdownNow();
        }
    }

    @Test(timeout = 60000)
    public void untrackedAsyncThreadSuppressesBackgroundAndIncrementsBackgroundLostOnTcpAndUnix() throws Exception {
        byte[] shopBytes = new byte[] {31, 32, 33};
        File shop = new File(tmp.newFolder("bg-suppress-classes"), "com/acme/Shop.class");
        shop.getParentFile().mkdirs();
        Files.write(shop.toPath(), shopBytes);
        System.setProperty(CLASS_DIRS_PROPERTY, shop.getParentFile().getParentFile().getParent());
        boolean[] probes = inProcessJacoco().probes(IndexReport.classId(shopBytes), "com/acme/Shop", 4);

        // 1. TCP transport: test-1 calls RequestGate.startThread(audit), audit hits probe[2]
        //    in the gap after END test-1 -> START test-2 MUST NOT emit BACKGROUND(after=test-1, before=test-2)
        //    and MUST increment backgroundLost so the next clean gap reports "lost":1!
        collector = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
        collector.setSoTimeout(30000);
        System.setProperty(ENDPOINT_PROPERTY, "127.0.0.1:" + collector.getLocalPort());
        System.setProperty(WARMUP_DISABLED_PROPERTY, "true");
        assertTrue(KeployDedupAgent.start());
        try (Socket sdk = collector.accept()) {
            sdk.setSoTimeout(30000);
            OutputStream out = sdk.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));
            assertNotNull(parseHello(in.readLine()));
            send(out, "ACK_ID");
            send(out, "BACKGROUND");

            send(out, "START ts/test-1");
            assertEquals("ACK ts/test-1", in.readLine());
            CountDownLatch allowAudit = new CountDownLatch(1);
            CountDownLatch auditDone = new CountDownLatch(1);
            Thread audit;
            Object req1 = RequestGate.enter();
            try {
                probes[0] = true;
                audit = new Thread(() -> {
                    try {
                        allowAudit.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    probes[2] = true;
                    auditDone.countDown();
                }, "tcp-audit-thread");
                audit.setDaemon(true);
                RequestGate.startThread(audit);
            } finally {
                RequestGate.exit(req1);
            }
            send(out, "END ts/test-1");
            String cov1 = in.readLine();
            assertTrue("test-1 flags untracked-async:thread-start: " + cov1,
                    cov1.contains("\"untracked-async:thread-start\""));
            assertEquals("ACK ts/test-1", in.readLine());

            // Audit thread runs in the gap between END test-1 and START test-2 and finishes.
            allowAudit.countDown();
            assertTrue("audit ran in gap", auditDone.await(5, TimeUnit.SECONDS));
            audit.join(5000);
            assertFalse("audit thread must exit before START ts/test-2", audit.isAlive());

            // START test-2 clears probe[2] but MUST NOT emit BACKGROUND!
            send(out, "START ts/test-2");
            assertEquals("ACK ts/test-2", in.readLine());
            assertFalse("gap probe cleared", probes[2]);
            send(out, "NOP check-no-bg");
            assertEquals("no BACKGROUND emitted for gap holding test-1's audit tail",
                    "ACK check-no-bg", in.readLine());

            // Clean test-2 -> END test-2 -> real background hit -> START test-3 emits BACKGROUND with lost:1!
            Object req2 = RequestGate.enter();
            try {
                probes[1] = true;
            } finally {
                RequestGate.exit(req2);
            }
            send(out, "END ts/test-2");
            String cov2 = in.readLine();
            assertTrue("test-2 COV received", cov2.startsWith("COV "));
            assertEquals("ACK ts/test-2", in.readLine());

            probes[3] = true; // genuine background hit between test-2 and test-3
            send(out, "START ts/test-3");
            assertEquals("ACK ts/test-3", in.readLine());
            String bgLine = in.readLine();
            assertNotNull("clean gap emits BACKGROUND", bgLine);
            assertTrue("BACKGROUND frame expected, got: " + bgLine, bgLine.startsWith("BACKGROUND "));
            assertTrue("BACKGROUND carries after=ts/test-2: " + bgLine,
                    bgLine.contains("\"after\":\"ts/test-2\""));
            assertTrue("BACKGROUND reports lost:1 from suppressed gap: " + bgLine,
                    bgLine.contains("\"lost\":1"));

            // Active lingering thread across A -> B -> C: test-3 starts a live thread still alive at END test-4,
            // finishing inside the test-4 -> test-5 gap! BOTH gaps (test-3->test-4 AND
            // test-4->test-5) MUST be suppressed!
            CountDownLatch allowLongAudit = new CountDownLatch(1);
            CountDownLatch longAuditDone = new CountDownLatch(1);
            Thread longAudit;
            Object req3 = RequestGate.enter();
            try {
                probes[0] = true;
                longAudit = new Thread(() -> {
                    try {
                        allowLongAudit.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    probes[2] = true;
                    longAuditDone.countDown();
                }, "tcp-long-audit");
                longAudit.setDaemon(true);
                RequestGate.startThread(longAudit);
            } finally {
                RequestGate.exit(req3);
            }
            send(out, "END ts/test-3");
            assertTrue(in.readLine().startsWith("COV "));
            assertEquals("ACK ts/test-3", in.readLine());

            // test-4 runs while longAudit is STILL ALIVE at END test-4.
            send(out, "START ts/test-4");
            assertEquals("ACK ts/test-4", in.readLine());
            Object req4 = RequestGate.enter();
            try {
                probes[1] = true;
            } finally {
                RequestGate.exit(req4);
            }
            send(out, "END ts/test-4");
            String cov4 = in.readLine();
            assertTrue("test-4 carries tail:ts/test-3: " + cov4, cov4.contains("\"tail:ts/test-3\""));
            assertEquals("ACK ts/test-4", in.readLine());

            // Now longAudit finishes in the gap between END test-4 and START test-5!
            allowLongAudit.countDown();
            assertTrue(longAuditDone.await(5, TimeUnit.SECONDS));
            longAudit.join(5000);
            assertFalse("longAudit thread must exit before START ts/test-5", longAudit.isAlive());
            send(out, "START ts/test-5");
            assertEquals("ACK ts/test-5", in.readLine());
            send(out, "NOP check-no-bg-bc");
            assertEquals("B->C gap holding A's finishing audit MUST suppress BACKGROUND",
                    "ACK check-no-bg-bc", in.readLine());

            // Opposite case: test-5 starts a thread AND joins it before END ->
            // next gap IS clean and DOES emit BACKGROUND (with lost:1 from test-4->test-5)!
            Object req5 = RequestGate.enter();
            try {
                probes[1] = true;
                Thread joined = new Thread(() -> { }, "tcp-joined-thread");
                RequestGate.startThread(joined);
                joined.join(5000);
            } finally {
                RequestGate.exit(req5);
            }
            send(out, "END ts/test-5");
            assertTrue(in.readLine().startsWith("COV "));
            assertEquals("ACK ts/test-5", in.readLine());

            probes[3] = true;
            send(out, "START ts/test-6");
            assertEquals("ACK ts/test-6", in.readLine());
            String bg56 = in.readLine();
            assertTrue("joined thread before END must NOT suppress next clean BACKGROUND: " + bg56,
                    bg56 != null && bg56.startsWith("BACKGROUND ")
                            && bg56.contains("\"after\":\"ts/test-5\"")
                            && bg56.contains("\"lost\":1"));

            // Independent suppressBackground[0] branch: test-6 ends completely clean
            // (prevMayEmitTail == false), then an expired job starts in the gap before START test-7
            // so ONLY suppressBackground[0] suppresses BACKGROUND!
            Object req6 = RequestGate.enter();
            try {
                probes[0] = true;
            } finally {
                RequestGate.exit(req6);
            }
            send(out, "END ts/test-6");
            assertTrue(in.readLine().startsWith("COV "));
            assertEquals("ACK ts/test-6", in.readLine());

            long savedJobMax = JobGate.handedJobMaxNanos;
            Object expiredToken = null;
            try {
                JobGate.handedJobMaxNanos = 1L;
                expiredToken = JobGate.enterAs(new JobGate.Attribution("scheduled:gap.Job.run", false, 0L));
                Thread.sleep(5);
                probes[2] = true;
                send(out, "START ts/test-7");
                assertEquals("ACK ts/test-7", in.readLine());
                send(out, "NOP check-suppress-only");
                assertEquals("suppressBackground[0] alone MUST suppress BACKGROUND when expired job active",
                        "ACK check-suppress-only", in.readLine());
            } finally {
                JobGate.exit(expiredToken);
                JobGate.handedJobMaxNanos = savedJobMax;
            }
            Object req7 = RequestGate.enter();
            try {
                probes[1] = true;
            } finally {
                RequestGate.exit(req7);
            }
            send(out, "END ts/test-7");
            String cov7 = in.readLine();
            assertTrue("test-7 carries job-tail: " + cov7, cov7.contains("\"job-tail:scheduled:gap.Job.run\""));
            assertEquals("ACK ts/test-7", in.readLine());

            probes[3] = true;
            send(out, "START ts/test-8");
            assertEquals("ACK ts/test-8", in.readLine());
            String bg78 = in.readLine();
            assertTrue("next clean gap after suppressBackground[0] reports lost:1: " + bg78,
                    bg78 != null && bg78.startsWith("BACKGROUND ") && bg78.contains("\"lost\":1"));
        } finally {
            KeployDedupAgent.stop();
            collector.close();
            collector = null;
        }

        // 2. Unix transport: verify BOTH (a) BACKGROUND suppression + lost:1 AND
        //    (b) no-server-entry end-to-end on Unix (unverified: ["no-server-entry"], NO "contaminated")!
        System.clearProperty(ENDPOINT_PROPERTY);
        KeployDedupAgent.transformerAdded = true;
        File sockDir = tmp.newFolder("unix-bg-suppress");
        KeployDedupAgent.controlSocketPath = new File(sockDir, "ctrl.sock").getAbsolutePath();
        KeployDedupAgent.dataSocketPath = new File(sockDir, "data.sock").getAbsolutePath();
        java.util.concurrent.BlockingQueue<String> records = new java.util.concurrent.LinkedBlockingQueue<>();
        org.newsclub.net.unix.AFUNIXServerSocket dataServer = org.newsclub.net.unix.AFUNIXServerSocket.newInstance();
        dataServer.bind(org.newsclub.net.unix.AFUNIXSocketAddress.of(new File(KeployDedupAgent.dataSocketPath)));
        Thread dataReader = new Thread(() -> {
            while (!dataServer.isClosed()) {
                try (Socket conn = dataServer.accept();
                     BufferedReader r = new BufferedReader(
                             new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = r.readLine()) != null) {
                        sb.append(l);
                    }
                    if (sb.length() > 0) {
                        records.add(sb.toString());
                    }
                } catch (IOException ignored) {
                }
            }
        }, "unix-data-reader");
        dataReader.setDaemon(true);
        dataReader.start();
        try {
            assertTrue(KeployDedupAgent.start());
            assertTrue(sendUnixControl("BACKGROUND").startsWith("ACK proto=3"));
            assertTrue(sendUnixControl("START ts/u1").startsWith("ACK proto=3"));
            CountDownLatch allowAudit = new CountDownLatch(1);
            CountDownLatch auditDone = new CountDownLatch(1);
            Thread audit;
            Object req1 = RequestGate.enter();
            try {
                probes[0] = true;
                audit = new Thread(() -> {
                    try {
                        allowAudit.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    probes[2] = true;
                    auditDone.countDown();
                }, "unix-audit-thread");
                audit.setDaemon(true);
                RequestGate.startThread(audit);
            } finally {
                RequestGate.exit(req1);
            }
            assertTrue(sendUnixControl("END ts/u1").startsWith("ACK proto=3"));
            allowAudit.countDown();
            assertTrue(auditDone.await(5, TimeUnit.SECONDS));
            audit.join(5000);
            assertFalse("unix audit thread must exit before START ts/u2", audit.isAlive());

            assertTrue(sendUnixControl("START ts/u2").startsWith("ACK proto=3"));
            // Clean counted request on Unix -> emits BACKGROUND(u2->u3) with lost:1 from u1->u2!
            Object reqU2 = RequestGate.enter();
            try {
                probes[1] = true;
            } finally {
                RequestGate.exit(reqU2);
            }
            assertTrue(sendUnixControl("END ts/u2").startsWith("ACK proto=3"));

            probes[3] = true;
            String startU3Reply = sendUnixControl("START ts/u3");
            assertTrue("Unix START before no-server-entry reports windowCut=true: " + startU3Reply,
                    startU3Reply.startsWith("ACK proto=3") && startU3Reply.contains("windowCut=true"));
            // Uncounted server on Unix (probes > 0 while enteredCount == 0):
            // MUST emit "unverified":["no-server-entry"] with NO "contaminated" field,
            // MUST latch windowCut=false on this END ACK and subsequent ACKs,
            // AND MUST suppress BACKGROUND(u3->u4) because an uncounted request's tail may run in the gap!
            probes[1] = true;
            String endU3Reply = sendUnixControl("END ts/u3");
            assertTrue("Unix END seeing no-server-entry must report windowCut=false: " + endU3Reply,
                    endU3Reply.startsWith("ACK proto=3") && endU3Reply.contains("windowCut=false")
                            && endU3Reply.contains("asyncExecutors=false"));

            probes[3] = true; // gap after uncounted u3: MUST be suppressed (lost increments to 1)!
            String startU4Reply = sendUnixControl("START ts/u4");
            assertTrue("Unix subsequent START after no-server-entry stays windowCut=false: " + startU4Reply,
                    startU4Reply.startsWith("ACK proto=3") && startU4Reply.contains("windowCut=false"));
            Object reqU4 = RequestGate.enter();
            try {
                probes[0] = true;
            } finally {
                RequestGate.exit(reqU4);
            }
            String endU4Reply = sendUnixControl("END ts/u4");
            assertTrue("Unix subsequent END stays windowCut=false: " + endU4Reply,
                    endU4Reply.startsWith("ACK proto=3") && endU4Reply.contains("windowCut=false"));

            probes[3] = true; // clean gap u4->u5 carries lost:1 from suppressed u3->u4!
            assertTrue(sendUnixControl("START ts/u5").startsWith("ACK proto=3"));

            // Drain data records and verify:
            // - no BACKGROUND for u1->u2 or u3->u4
            // - BACKGROUND for u2->u3 has lost:1
            // - u3 carries "unverified":["no-server-entry"] and NO "contaminated"
            // - BACKGROUND for u4->u5 has lost:1 (proving u3 no-server-entry suppressed u3->u4!)
            String recU3 = null;
            String bgU2U3 = null;
            String bgU4U5 = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline && (recU3 == null || bgU2U3 == null || bgU4U5 == null)) {
                String rec = records.poll(200, TimeUnit.MILLISECONDS);
                if (rec == null) {
                    continue;
                }
                assertFalse("must NOT emit BACKGROUND for gap after u1: " + rec,
                        rec.contains("\"kind\":\"background\"") && rec.contains("\"after\":\"ts/u1\""));
                assertFalse("must NOT emit BACKGROUND for gap after no-server-entry u3: " + rec,
                        rec.contains("\"kind\":\"background\"") && rec.contains("\"after\":\"ts/u3\""));
                if (rec.contains("\"id\":\"ts/u3\"")) {
                    recU3 = rec;
                }
                if (rec.contains("\"kind\":\"background\"") && rec.contains("\"after\":\"ts/u2\"")) {
                    bgU2U3 = rec;
                }
                if (rec.contains("\"kind\":\"background\"") && rec.contains("\"after\":\"ts/u4\"")) {
                    bgU4U5 = rec;
                }
            }
            assertNotNull("expected Unix record for ts/u3", recU3);
            assertTrue("Unix uncounted-server record must carry unverified no-server-entry: " + recU3,
                    recU3.contains("\"unverified\":[\"no-server-entry\"]"));
            assertFalse("Unix uncounted-server record must NOT carry contaminated: " + recU3,
                    recU3.contains("\"contaminated\""));
            assertNotNull("expected BACKGROUND for clean gap u2->u3", bgU2U3);
            assertTrue("Unix BACKGROUND u2->u3 must carry lost:1 for suppressed u1->u2 gap: " + bgU2U3,
                    bgU2U3.contains("\"lost\":1"));
            assertNotNull("expected BACKGROUND for clean gap u4->u5", bgU4U5);
            assertTrue("Unix BACKGROUND u4->u5 must carry lost:1 for suppressed no-server-entry u3->u4 gap: " + bgU4U5,
                    bgU4U5.contains("\"lost\":1"));
        } finally {
            KeployDedupAgent.stop();
            dataServer.close();
        }
    }

    public static final class MethodRefTxHelper {
        public void execute(Runnable r) {
            r.run();
        }

        public Object submit(Runnable r) {
            r.run();
            return "ok1";
        }

        public Object submit(Runnable r, Object result) {
            r.run();
            return result;
        }
    }

    public static final class CustomMethodRefExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    public static final class CustomMethodRefExecutorService extends AbstractExecutorService {
        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public Future<?> submit(Runnable task) {
            return super.submit(task);
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            return super.submit(task, result);
        }
    }

    @FunctionalInterface
    public interface TriSubmitFn<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    public static final class BoundExecuteMethodRefCaller {
        public static void run(MethodRefTxHelper h, Runnable r) {
            java.util.function.Consumer<Runnable> c = h::execute;
            c.accept(r);
        }
    }

    public static final class UnboundExecuteMethodRefCaller {
        public static void run(MethodRefTxHelper h, Runnable r) {
            java.util.function.BiConsumer<MethodRefTxHelper, Runnable> b = MethodRefTxHelper::execute;
            b.accept(h, r);
        }
    }

    public static final class BoundSubmitMethodRefCaller {
        public static Object run(MethodRefTxHelper h, Runnable r) {
            java.util.function.Function<Runnable, Object> f = h::submit;
            Object r1 = f.apply(r);
            java.util.function.BiFunction<Runnable, Object, Object> f2 = h::submit;
            return r1 + ":" + f2.apply(r, "ok2");
        }
    }

    public static final class UnboundSubmitMethodRefCaller {
        public static Object run(MethodRefTxHelper h, Runnable r) {
            java.util.function.BiFunction<MethodRefTxHelper, Runnable, Object> bf = MethodRefTxHelper::submit;
            Object r1 = bf.apply(h, r);
            TriSubmitFn<MethodRefTxHelper, Runnable, Object, Object> bf2 = MethodRefTxHelper::submit;
            return r1 + ":" + bf2.apply(h, r, "ok2");
        }
    }

    public static final class RealBoundExecMethodRefCaller {
        public static void run(Executor e, Runnable r) {
            java.util.function.Consumer<Runnable> c = e::execute;
            c.accept(r);
        }
    }

    public static final class RealUnboundExecMethodRefCaller {
        public static void run(CustomMethodRefExecutor e, Runnable r) {
            java.util.function.BiConsumer<CustomMethodRefExecutor, Runnable> b = CustomMethodRefExecutor::execute;
            b.accept(e, r);
        }
    }

    public static final class RealBoundSubmitMethodRefCaller {
        public static Object run(ExecutorService s, Runnable r) {
            java.util.function.Function<Runnable, Future<?>> f = s::submit;
            Future<?> f1 = f.apply(r);
            java.util.function.BiFunction<Runnable, String, Future<String>> f2 = s::submit;
            Future<String> res2 = f2.apply(r, "res");
            return f1 != null && res2 != null ? "ok" : "bad";
        }
    }

    public static final class RealUnboundSubmitMethodRefCaller {
        public static Object run(CustomMethodRefExecutorService s, Runnable r) {
            java.util.function.BiFunction<CustomMethodRefExecutorService, Runnable, Future<?>> bf =
                    CustomMethodRefExecutorService::submit;
            Future<?> f1 = bf.apply(s, r);
            TriSubmitFn<CustomMethodRefExecutorService, Runnable, String, Future<String>> bf2 =
                    CustomMethodRefExecutorService::submit;
            Future<String> f2 = bf2.apply(s, r, "res");
            return f1 != null && f2 != null ? "ok" : "bad";
        }
    }

    public static final class UnboundInlineExecMethodRefCaller {
        public static void run(org.springframework.core.task.SyncTaskExecutor e, Runnable r) {
            java.util.function.BiConsumer<org.springframework.core.task.SyncTaskExecutor, Runnable> b =
                    org.springframework.core.task.SyncTaskExecutor::execute;
            b.accept(e, r);
        }
    }

    private static final class ChildFirstClassLoader extends ClassLoader {
        private final String targetName;
        private final byte[] bytes;

        ChildFirstClassLoader(ClassLoader parent, String targetName, byte[] bytes) {
            super(parent);
            this.targetName = targetName;
            this.bytes = bytes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (targetName.equals(name)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    private static Class<?> rewriteClassWithWrapExecutorCalls(Class<?> cls) throws Exception {
        String name = cls.getName();
        byte[] raw;
        try (java.io.InputStream in = cls.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            assertNotNull("class bytes not found for " + name, in);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            raw = out.toByteArray();
        }
        byte[] rewritten = ServerEntries.wrapExecutorCalls(raw);
        assertNotNull("ServerEntries.wrapExecutorCalls must rewrite " + cls.getSimpleName(), rewritten);
        return new ChildFirstClassLoader(cls.getClassLoader(), name, rewritten).loadClass(name);
    }

    @Test(timeout = 60000)
    public void methodReferencesToNonExecutorHelpersAreNotFlaggedWhileRealExecutorsStillFlag() throws Exception {
        Class<?> boundExec = rewriteClassWithWrapExecutorCalls(BoundExecuteMethodRefCaller.class);
        Class<?> unboundExec = rewriteClassWithWrapExecutorCalls(UnboundExecuteMethodRefCaller.class);
        Class<?> boundSubmit = rewriteClassWithWrapExecutorCalls(BoundSubmitMethodRefCaller.class);
        Class<?> unboundSubmit = rewriteClassWithWrapExecutorCalls(UnboundSubmitMethodRefCaller.class);
        Class<?> realBoundExec = rewriteClassWithWrapExecutorCalls(RealBoundExecMethodRefCaller.class);
        Class<?> realUnboundExec = rewriteClassWithWrapExecutorCalls(RealUnboundExecMethodRefCaller.class);
        Class<?> realBoundSubmit = rewriteClassWithWrapExecutorCalls(RealBoundSubmitMethodRefCaller.class);
        Class<?> realUnboundSubmit = rewriteClassWithWrapExecutorCalls(RealUnboundSubmitMethodRefCaller.class);
        Class<?> unboundInlineExec = rewriteClassWithWrapExecutorCalls(UnboundInlineExecMethodRefCaller.class);

        MethodRefTxHelper helper = new MethodRefTxHelper();

        // 1. Disarmed non-Executor method refs must be complete no-ops (must NOT touch unarmed hand-off state).
        RequestGate.disarm();
        Object unarmedTok = RequestGate.enter();
        try {
            boundExec.getMethod("run", MethodRefTxHelper.class, Runnable.class).invoke(null, helper, (Runnable) () -> { });
            unboundExec.getMethod("run", MethodRefTxHelper.class, Runnable.class).invoke(null, helper, (Runnable) () -> { });
            boundSubmit.getMethod("run", MethodRefTxHelper.class, Runnable.class).invoke(null, helper, (Runnable) () -> { });
            unboundSubmit.getMethod("run", MethodRefTxHelper.class, Runnable.class).invoke(null, helper, (Runnable) () -> { });
        } finally {
            RequestGate.exit(unarmedTok);
        }
        // First armed window sees the one-time armed-mid-flight from unarmedTok, second window MUST be clean
        // (proving lastUnarmedHandOffNanos was NOT set by non-Executor method refs).
        RequestGate.open("disarmed-check-1", null);
        RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
        RequestGate.open("disarmed-check-2", null);
        assertEquals("non-Executor method refs while disarmed must NOT trigger unarmed hand-off tail",
                Collections.<String>emptyList(),
                RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);

        // 2. Bound & unbound execute/submit method refs on plain non-Executor helper -> 0 contamination, 0 tail:!
        Class<?>[] helperCallers = new Class<?>[] {boundExec, unboundExec, boundSubmit, unboundSubmit};
        for (Class<?> caller : helperCallers) {
            String label = caller.getName();
            RequestGate.disarm();
            RequestGate.open("mref-helper-" + label + "-1", null);
            AtomicInteger ran = new AtomicInteger();
            Object tok = RequestGate.enter();
            try {
                caller.getMethod("run", MethodRefTxHelper.class, Runnable.class)
                        .invoke(null, helper, (Runnable) ran::incrementAndGet);
            } finally {
                RequestGate.exit(tok);
            }
            assertTrue(label + " invoked target", ran.get() > 0);
            RequestGate.WindowOutcome out1 = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertEquals(label + " must have 0 contamination",
                    Collections.<String>emptyList(), out1.contaminations);
            assertFalse(label + " must have tailActiveAtEnd=false", out1.tailActiveAtEnd);

            RequestGate.open("mref-helper-" + label + "-2", null);
            Object tok2 = RequestGate.enter();
            RequestGate.exit(tok2);
            RequestGate.WindowOutcome out2 = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertEquals(label + " must leave 0 tail: on next window",
                    Collections.<String>emptyList(), out2.contaminations);
        }

        // 3. Unbound inline executor method ref (SyncTaskExecutor::execute) -> 0 contamination, 0 tail:!
        RequestGate.disarm();
        RequestGate.open("mref-inline-1", null);
        AtomicBoolean inlineRan = new AtomicBoolean(false);
        Object tokInline = RequestGate.enter();
        try {
            unboundInlineExec.getMethod("run", org.springframework.core.task.SyncTaskExecutor.class, Runnable.class)
                    .invoke(null, new org.springframework.core.task.SyncTaskExecutor(),
                            (Runnable) () -> inlineRan.set(true));
        } finally {
            RequestGate.exit(tokInline);
        }
        assertTrue("SyncTaskExecutor::execute ran", inlineRan.get());
        assertEquals("unbound SyncTaskExecutor::execute must NOT contaminate",
                Collections.<String>emptyList(),
                RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true).contaminations);

        // 4. Positive controls: bound & unbound method refs to real custom Executor / ExecutorService STILL flag!
        CustomMethodRefExecutor customExec = new CustomMethodRefExecutor();
        CustomMethodRefExecutorService customSvc = new CustomMethodRefExecutorService();

        RequestGate.disarm();
        RequestGate.open("mref-real-bound-exec", null);
        Object tokRbe = RequestGate.enter();
        try {
            realBoundExec.getMethod("run", Executor.class, Runnable.class)
                    .invoke(null, customExec, (Runnable) () -> { });
        } finally {
            RequestGate.exit(tokRbe);
        }
        RequestGate.WindowOutcome rbeOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
        assertTrue("bound real Executor::execute must flag untracked-executor: " + rbeOut.contaminations,
                rbeOut.contaminations.contains("untracked-executor:" + CustomMethodRefExecutor.class.getName()));

        RequestGate.disarm();
        RequestGate.open("mref-real-unbound-exec", null);
        Object tokRue = RequestGate.enter();
        try {
            realUnboundExec.getMethod("run", CustomMethodRefExecutor.class, Runnable.class)
                    .invoke(null, customExec, (Runnable) () -> { });
        } finally {
            RequestGate.exit(tokRue);
        }
        RequestGate.WindowOutcome rueOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
        assertTrue("unbound real Executor::execute must flag untracked-executor: " + rueOut.contaminations,
                rueOut.contaminations.contains("untracked-executor:" + CustomMethodRefExecutor.class.getName()));

        RequestGate.disarm();
        RequestGate.open("mref-real-bound-submit", null);
        Object tokRbs = RequestGate.enter();
        try {
            realBoundSubmit.getMethod("run", ExecutorService.class, Runnable.class)
                    .invoke(null, customSvc, (Runnable) () -> { });
        } finally {
            RequestGate.exit(tokRbs);
        }
        RequestGate.WindowOutcome rbsOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
        assertTrue("bound real ExecutorService::submit must flag untracked-executor: " + rbsOut.contaminations,
                rbsOut.contaminations.contains("untracked-executor:" + CustomMethodRefExecutorService.class.getName()));

        RequestGate.disarm();
        RequestGate.open("mref-real-unbound-submit", null);
        Object tokRus = RequestGate.enter();
        try {
            realUnboundSubmit.getMethod("run", CustomMethodRefExecutorService.class, Runnable.class)
                    .invoke(null, customSvc, (Runnable) () -> { });
        } finally {
            RequestGate.exit(tokRus);
        }
        RequestGate.WindowOutcome rusOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
        assertTrue("unbound real ExecutorService::submit must flag untracked-executor: " + rusOut.contaminations,
                rusOut.contaminations.contains("untracked-executor:" + CustomMethodRefExecutorService.class.getName()));
        assertTrue("in-window real executor method refs must also latch asyncExecutors=false",
                KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());
    }

    public static final class AsyncMethodRefThreadStartCaller {
        private static volatile java.util.function.Consumer<Thread> captured;

        public static void capture() {
            captured = Thread::start;
        }

        public static void invoke(Thread t) {
            captured.accept(t);
        }
    }

    public static final class AsyncMethodRefCfRunAsyncCaller {
        private static volatile java.util.function.Function<Runnable, CompletableFuture<Void>> captured;

        public static void capture() {
            captured = CompletableFuture::runAsync;
        }

        public static CompletableFuture<Void> invoke(Runnable r) {
            return captured.apply(r);
        }
    }

    public static final class AsyncMethodRefScheduleCaller {
        private static volatile ScheduleTriFn captured;

        @FunctionalInterface
        public interface ScheduleTriFn {
            java.util.concurrent.ScheduledFuture<?> apply(Runnable r, long delay, TimeUnit unit);
        }

        public static void capture(java.util.concurrent.ScheduledExecutorService sched) {
            captured = sched::schedule;
        }

        public static java.util.concurrent.ScheduledFuture<?> invoke(Runnable r, long delay, TimeUnit unit) {
            return captured.apply(r, delay, unit);
        }
    }

    public static final class LazyInWindowExecutorRefCaller {
        private static volatile java.util.function.Consumer<Runnable> cachedBound;
        private static volatile java.util.function.BiConsumer<Executor, Runnable> cachedUnbound;

        public static void reset() {
            cachedBound = null;
            cachedUnbound = null;
        }

        public static void callBound(Executor pool, Runnable task) {
            if (cachedBound == null) {
                cachedBound = pool::execute;
            }
            cachedBound.accept(task);
        }

        public static void callUnbound(Executor pool, Runnable task) {
            if (cachedUnbound == null) {
                cachedUnbound = Executor::execute;
            }
            cachedUnbound.accept(pool, task);
        }
    }

    public static final class FakeNioAsyncChannelApp {
        public static java.nio.channels.AsynchronousFileChannel openReadOnly(java.nio.file.Path p) throws IOException {
            return java.nio.channels.AsynchronousFileChannel.open(p, java.nio.file.StandardOpenOption.READ);
        }
    }

    public static final class PreCapturedBoundExecCaller {
        private static volatile java.util.function.Consumer<Runnable> captured;

        public static void capture(Object e) {
            captured = ((Executor) e)::execute;
        }

        public static void invoke(Runnable r) {
            captured.accept(r);
        }
    }

    public static final class PreCapturedBoundSubmitCaller {
        private static volatile java.util.function.Function<Runnable, Future<?>> captured;

        public static void capture(Object s) {
            captured = ((ExecutorService) s)::submit;
        }

        public static Future<?> invoke(Runnable r) {
            return captured.apply(r);
        }
    }

    public static final class PreCapturedUnboundExecCaller {
        private static volatile java.util.function.BiConsumer<Executor, Runnable> captured;

        public static void capture() {
            captured = Executor::execute;
        }

        public static void invoke(Executor e, Runnable r) {
            captured.accept(e, r);
        }
    }

    public static final class PreCapturedUnboundSubmitCaller {
        private static volatile java.util.function.BiFunction<ExecutorService, Runnable, Future<?>> captured;

        public static void capture() {
            captured = ExecutorService::submit;
        }

        public static Future<?> invoke(ExecutorService s, Runnable r) {
            return captured.apply(s, r);
        }
    }

    public static final class PreCapturedNonExecHelperCaller {
        private static volatile java.util.function.Consumer<Runnable> boundExec;
        private static volatile java.util.function.BiConsumer<MethodRefTxHelper, Runnable> unboundExec;
        private static volatile java.util.function.Function<Runnable, Object> boundSubmit;
        private static volatile java.util.function.BiFunction<MethodRefTxHelper, Runnable, Object> unboundSubmit;

        public static void capture(MethodRefTxHelper h) {
            boundExec = h::execute;
            unboundExec = MethodRefTxHelper::execute;
            boundSubmit = h::submit;
            unboundSubmit = MethodRefTxHelper::submit;
        }

        public static void invokeAll(MethodRefTxHelper h, Runnable r) {
            boundExec.accept(r);
            unboundExec.accept(h, r);
            boundSubmit.apply(r);
            unboundSubmit.apply(h, r);
        }
    }

    public static final class PreCapturedInlineAndSpringCaller {
        private static volatile java.util.function.Consumer<Runnable> boundInline;
        private static volatile java.util.function.BiConsumer<org.springframework.core.task.SyncTaskExecutor, Runnable> unboundInline;
        private static volatile java.util.function.Consumer<Runnable> boundSpring;

        public static void capture(org.springframework.core.task.SyncTaskExecutor inline,
                org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor spring) {
            boundInline = ((Executor) inline)::execute;
            unboundInline = org.springframework.core.task.SyncTaskExecutor::execute;
            boundSpring = ((Executor) spring)::execute;
        }

        public static void invokeInline(org.springframework.core.task.SyncTaskExecutor inline, Runnable r) {
            boundInline.accept(r);
            unboundInline.accept(inline, r);
        }

        public static void invokeSpring(Runnable r) {
            boundSpring.accept(r);
        }
    }

    @Test(timeout = 60000)
    public void preCapturedExecutorMethodRefLatchesAsyncExecutorsFalseOnTcpAndUnix() throws Exception {
        Class<?> boundExec = rewriteClassWithWrapExecutorCalls(PreCapturedBoundExecCaller.class);
        Class<?> boundSubmit = rewriteClassWithWrapExecutorCalls(PreCapturedBoundSubmitCaller.class);
        Class<?> unboundExec = rewriteClassWithWrapExecutorCalls(PreCapturedUnboundExecCaller.class);
        Class<?> unboundSubmit = rewriteClassWithWrapExecutorCalls(PreCapturedUnboundSubmitCaller.class);

        byte[] shopBytes = new byte[] {61, 62, 63};
        File shop = new File(tmp.newFolder("precaptured-exec-classes"), "com/acme/Shop.class");
        shop.getParentFile().mkdirs();
        Files.write(shop.toPath(), shopBytes);
        System.setProperty(CLASS_DIRS_PROPERTY, shop.getParentFile().getParentFile().getParent());
        boolean[] probes = inProcessJacoco().probes(IndexReport.classId(shopBytes), "com/acme/Shop", 2);

        ThreadPoolExecutor tpe = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        try {
            // 1. TCP transport: initial HELLO declares asyncExecutors=true; capturing ((Executor) tpe)::execute
            //    outside any window latches asyncExecutors=false, refreshes HELLO before START/COV,
            //    and stays permanently latched across reconnect and subsequent windows.
            KeployDedupAgent.transformerAdded = true;
            KeployDedupAgent.executorCallSitesEnabled = true;
            KeployDedupAgent.observedNoServerEntry.set(false);
            KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
            ServerEntries.restoreCounted(Collections.<String>emptySet());
            ServerEntries.restoreUncounted(Collections.<String>emptySet());

            collector = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
            collector.setSoTimeout(30000);
            System.setProperty(ENDPOINT_PROPERTY, "127.0.0.1:" + collector.getLocalPort());
            System.setProperty(WARMUP_DISABLED_PROPERTY, "true");
            assertTrue(KeployDedupAgent.start());
            try {
                try (Socket sdk = collector.accept()) {
                    sdk.setSoTimeout(30000);
                    OutputStream out = sdk.getOutputStream();
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));
                    JsonObject initial = parseHello(in.readLine());
                    assertTrue("initial HELLO starts with windowCut=true",
                            initial.get("windowCut").getAsBoolean());
                    assertTrue("initial HELLO starts with asyncExecutors=true",
                            initial.get("asyncExecutors").getAsBoolean());

                    send(out, "ACK_ID");
                    // Capture bound ((Executor) tpe)::execute outside any request window (no RequestGate.enter).
                    boundExec.getMethod("capture", Object.class).invoke(null, tpe);
                    assertTrue("pre-captured bound Executor::execute must latch observedPreCapturedUntrackedExecutor",
                            KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());

                    // Next START refreshes HELLO with asyncExecutors=false BEFORE ACK!
                    send(out, "START ts/precaptured-1");
                    JsonObject refreshed = parseHello(in.readLine());
                    assertTrue("windowCut stays true when only pre-captured executor latches",
                            refreshed.get("windowCut").getAsBoolean());
                    assertFalse("HELLO must refresh asyncExecutors=false after pre-capture",
                            refreshed.get("asyncExecutors").getAsBoolean());
                    assertEquals("ACK ts/precaptured-1", in.readLine());

                    // Invoke the pre-captured method ref inside a request window.
                    CountDownLatch ran = new CountDownLatch(1);
                    Object tok = RequestGate.enter();
                    try {
                        probes[1] = true;
                        boundExec.getMethod("invoke", Runnable.class).invoke(null, (Runnable) ran::countDown);
                    } finally {
                        RequestGate.exit(tok);
                    }
                    assertTrue("pre-captured executor ran task", ran.await(5, TimeUnit.SECONDS));
                    send(out, "END ts/precaptured-1");
                    String cov1 = in.readLine();
                    assertTrue("COV emitted for precaptured-1", cov1.startsWith("COV "));
                    assertEquals("ACK ts/precaptured-1", in.readLine());

                    // Subsequent clean window keeps asyncExecutors=false (no revert HELLO).
                    send(out, "START ts/precaptured-2");
                    assertEquals("ACK ts/precaptured-2", in.readLine());
                    Object tok2 = RequestGate.enter();
                    try {
                        probes[1] = true;
                    } finally {
                        RequestGate.exit(tok2);
                    }
                    send(out, "END ts/precaptured-2");
                    assertTrue(in.readLine().startsWith("COV "));
                    assertEquals("ACK ts/precaptured-2", in.readLine());
                }

                // Connection 2 (after reconnect): asyncExecutors=false stays permanently latched!
                try (Socket sdk2 = collector.accept()) {
                    sdk2.setSoTimeout(30000);
                    BufferedReader in2 = new BufferedReader(
                            new InputStreamReader(sdk2.getInputStream(), StandardCharsets.UTF_8));
                    JsonObject reconnected = parseHello(in2.readLine());
                    assertTrue("windowCut remains true after reconnect",
                            reconnected.get("windowCut").getAsBoolean());
                    assertFalse("asyncExecutors=false stays permanently latched across reconnect",
                            reconnected.get("asyncExecutors").getAsBoolean());
                }
            } finally {
                KeployDedupAgent.stop();
                collector.close();
                collector = null;
                System.clearProperty(ENDPOINT_PROPERTY);
            }

            // 2. Startup (disarmed) AND armed-outside-window capture for ALL four executor
            //    method-ref forms (bound/unbound execute/submit) latch asyncExecutors=false!
            String[] variants = new String[] {"boundExec", "boundSubmit", "unboundExec", "unboundSubmit"};
            for (boolean armedState : new boolean[] {false, true}) {
                for (String variant : variants) {
                    KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
                    if (armedState) {
                        RequestGate.arm();
                    } else {
                        RequestGate.disarm();
                    }
                    if ("boundExec".equals(variant)) {
                        boundExec.getMethod("capture", Object.class).invoke(null, tpe);
                    } else if ("boundSubmit".equals(variant)) {
                        boundSubmit.getMethod("capture", Object.class).invoke(null, tpe);
                    } else if ("unboundExec".equals(variant)) {
                        unboundExec.getMethod("capture").invoke(null);
                    } else {
                        unboundSubmit.getMethod("capture").invoke(null);
                    }
                    assertTrue(variant + " (armed=" + armedState + ") must latch observedPreCapturedUntrackedExecutor",
                            KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());
                    JsonObject h = parseHello(connectAndReadHello(true));
                    assertTrue(variant + " keeps windowCut=true", h.get("windowCut").getAsBoolean());
                    assertFalse(variant + " (armed=" + armedState + ") must report asyncExecutors=false on HELLO",
                            h.get("asyncExecutors").getAsBoolean());
                }
            }

            // 3. Unix transport: caps flip from asyncExecutors=true to asyncExecutors=false after
            //    pre-capture and stay false on subsequent windows.
            KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
            System.clearProperty(ENDPOINT_PROPERTY);
            File sockDir = tmp.newFolder("unix-precaptured-exec");
            KeployDedupAgent.controlSocketPath = new File(sockDir, "ctrl.sock").getAbsolutePath();
            KeployDedupAgent.dataSocketPath = new File(sockDir, "data.sock").getAbsolutePath();
            org.newsclub.net.unix.AFUNIXServerSocket dataServer =
                    org.newsclub.net.unix.AFUNIXServerSocket.newInstance();
            dataServer.bind(org.newsclub.net.unix.AFUNIXSocketAddress.of(new File(KeployDedupAgent.dataSocketPath)));
            Thread dataReader = new Thread(() -> {
                while (!dataServer.isClosed()) {
                    try (Socket conn = dataServer.accept();
                         BufferedReader r = new BufferedReader(
                                 new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        while (r.readLine() != null) {
                            // Drain published Unix coverage records.
                        }
                    } catch (IOException ignored) {
                    }
                }
            }, "unix-precaptured-data-reader");
            dataReader.setDaemon(true);
            dataReader.start();
            try {
                assertTrue(KeployDedupAgent.start());
                String ack1 = sendUnixControl("START ts/u-pre-1");
                assertTrue("Unix START before pre-capture reports asyncExecutors=true: " + ack1,
                        ack1.startsWith("ACK proto=3") && ack1.contains("windowCut=true")
                                && ack1.contains("asyncExecutors=true"));
                String end1 = sendUnixControl("END ts/u-pre-1");
                assertTrue("Unix END before pre-capture reports asyncExecutors=true: " + end1,
                        end1.startsWith("ACK proto=3") && end1.contains("asyncExecutors=true"));

                // Capture outside any window.
                boundExec.getMethod("capture", Object.class).invoke(null, tpe);
                assertTrue(KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());

                String ack2 = sendUnixControl("START ts/u-pre-2");
                assertTrue("Unix START after pre-capture must refresh asyncExecutors=false: " + ack2,
                        ack2.startsWith("ACK proto=3") && ack2.contains("windowCut=true")
                                && ack2.contains("asyncExecutors=false"));
                CountDownLatch unixRan = new CountDownLatch(1);
                Object tokU = RequestGate.enter();
                try {
                    boundExec.getMethod("invoke", Runnable.class).invoke(null, (Runnable) unixRan::countDown);
                } finally {
                    RequestGate.exit(tokU);
                }
                assertTrue(unixRan.await(5, TimeUnit.SECONDS));
                String end2 = sendUnixControl("END ts/u-pre-2");
                assertTrue("Unix END stays asyncExecutors=false: " + end2,
                        end2.startsWith("ACK proto=3") && end2.contains("asyncExecutors=false"));

                String ack3 = sendUnixControl("START ts/u-pre-3");
                assertTrue("Unix subsequent window stays asyncExecutors=false: " + ack3,
                        ack3.startsWith("ACK proto=3") && ack3.contains("asyncExecutors=false"));
            } finally {
                KeployDedupAgent.stop();
                dataServer.close();
            }
        } finally {
            tpe.shutdownNow();
        }
    }

    @Test(timeout = 60000)
    public void preCapturedNonExecutorAndInlineMethodRefsDoNotLatchAsyncExecutors() throws Exception {
        Class<?> helperCaller = rewriteClassWithWrapExecutorCalls(PreCapturedNonExecHelperCaller.class);
        Class<?> inlineAndSpringCaller = rewriteClassWithWrapExecutorCalls(PreCapturedInlineAndSpringCaller.class);

        byte[] shopBytes = new byte[] {64, 65, 66};
        File shop = new File(tmp.newFolder("nonexec-precaptured-classes"), "com/acme/Shop.class");
        shop.getParentFile().mkdirs();
        Files.write(shop.toPath(), shopBytes);
        System.setProperty(CLASS_DIRS_PROPERTY, shop.getParentFile().getParentFile().getParent());

        MethodRefTxHelper helper = new MethodRefTxHelper();
        org.springframework.core.task.SyncTaskExecutor syncExec = new org.springframework.core.task.SyncTaskExecutor();
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor springExec =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        springExec.initialize();
        try {
            ServerEntries.markSpringExecutorHookedForTests(springExec.getClass().getName());
            KeployDedupAgent.transformerAdded = true;
            KeployDedupAgent.executorCallSitesEnabled = true;
            KeployDedupAgent.observedNoServerEntry.set(false);
            KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
            ServerEntries.restoreCounted(Collections.<String>emptySet());
            ServerEntries.restoreUncounted(Collections.<String>emptySet());

            // 1. Capture outside any window while DISARMED (startup) -> must NOT latch!
            RequestGate.disarm();
            helperCaller.getMethod("capture", MethodRefTxHelper.class).invoke(null, helper);
            inlineAndSpringCaller.getMethod("capture",
                    org.springframework.core.task.SyncTaskExecutor.class,
                    org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class)
                    .invoke(null, syncExec, springExec);
            assertFalse("disarmed pre-capture of TxHelper/inline/hooked-Spring must NOT latch",
                    KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());

            // 2. Capture outside any window while ARMED -> must NOT latch!
            RequestGate.arm();
            helperCaller.getMethod("capture", MethodRefTxHelper.class).invoke(null, helper);
            inlineAndSpringCaller.getMethod("capture",
                    org.springframework.core.task.SyncTaskExecutor.class,
                    org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class)
                    .invoke(null, syncExec, springExec);
            assertFalse("armed outside-window capture of TxHelper/inline/hooked-Spring must NOT latch",
                    KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());

            // 3. TCP HELLO keeps asyncExecutors=true!
            JsonObject hello = parseHello(connectAndReadHello(true));
            assertTrue("windowCut stays true", hello.get("windowCut").getAsBoolean());
            assertTrue("asyncExecutors stays true for non-Executor/inline/hooked-Spring pre-captures",
                    hello.get("asyncExecutors").getAsBoolean());

            // 4. Unix ACK keeps asyncExecutors=true!
            File sockDir = tmp.newFolder("unix-nonexec-precaptured");
            KeployDedupAgent.controlSocketPath = new File(sockDir, "ctrl.sock").getAbsolutePath();
            KeployDedupAgent.dataSocketPath = new File(sockDir, "data.sock").getAbsolutePath();
            System.clearProperty(ENDPOINT_PROPERTY);
            System.setProperty(WARMUP_DISABLED_PROPERTY, "true");
            assertTrue(KeployDedupAgent.start());
            try {
                String ack = sendUnixControl("START ts/u-nonexec-pre");
                assertTrue("Unix ACK keeps asyncExecutors=true: " + ack,
                        ack.startsWith("ACK proto=3") && ack.contains("asyncExecutors=true"));
            } finally {
                KeployDedupAgent.stop();
            }

            // 5. Invoking all captured non-Executor and inline refs inside a window produces 0 contaminations!
            RequestGate.disarm();
            RequestGate.open("nonexec-precaptured-call", null);
            AtomicInteger count = new AtomicInteger();
            Object tok = RequestGate.enter();
            try {
                helperCaller.getMethod("invokeAll", MethodRefTxHelper.class, Runnable.class)
                        .invoke(null, helper, (Runnable) count::incrementAndGet);
                inlineAndSpringCaller.getMethod("invokeInline",
                        org.springframework.core.task.SyncTaskExecutor.class, Runnable.class)
                        .invoke(null, syncExec, (Runnable) count::incrementAndGet);
            } finally {
                RequestGate.exit(tok);
            }
            assertEquals("all 6 helper + inline invocations ran", 6, count.get());
            RequestGate.WindowOutcome out = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertEquals("pre-captured TxHelper/inline must produce 0 contaminations",
                    Collections.<String>emptyList(), out.contaminations);
            assertFalse("pre-captured TxHelper/inline must leave tailActiveAtEnd=false", out.tailActiveAtEnd);
            assertFalse("latch remains false after in-window invocations",
                    KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());
        } finally {
            ServerEntries.unmarkSpringExecutorHookedForTests(springExec.getClass().getName());
            springExec.shutdown();
        }
    }

    @Test(timeout = 60000)
    public void inWindowLazyExecutorMethodRefCaptureLatchesAndCoversLaterWindowsOnTcpAndUnix() throws Exception {
        Class<?> lazyCaller = rewriteClassWithWrapExecutorCalls(LazyInWindowExecutorRefCaller.class);

        byte[] shopBytes = new byte[] {71, 72, 73};
        File shop = new File(tmp.newFolder("lazy-inwindow-exec-classes"), "com/acme/Shop.class");
        shop.getParentFile().mkdirs();
        Files.write(shop.toPath(), shopBytes);
        System.setProperty(CLASS_DIRS_PROPERTY, shop.getParentFile().getParentFile().getParent());
        boolean[] probes = inProcessJacoco().probes(IndexReport.classId(shopBytes), "com/acme/Shop", 3);

        ThreadPoolExecutor tpe = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        try {
            // 1. TCP transport (bound and unbound lazy capture in W1 -> W2 invoke -> W3 clean).
            for (String methodName : new String[] {"callBound", "callUnbound"}) {
                lazyCaller.getMethod("reset").invoke(null);
                KeployDedupAgent.transformerAdded = true;
                KeployDedupAgent.executorCallSitesEnabled = true;
                KeployDedupAgent.observedNoServerEntry.set(false);
                KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
                ServerEntries.restoreCounted(Collections.<String>emptySet());
                ServerEntries.restoreUncounted(Collections.<String>emptySet());

                collector = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
                collector.setSoTimeout(30000);
                System.setProperty(ENDPOINT_PROPERTY, "127.0.0.1:" + collector.getLocalPort());
                System.setProperty(WARMUP_DISABLED_PROPERTY, "true");
                assertTrue(KeployDedupAgent.start());
                try {
                    try (Socket sdk = collector.accept()) {
                        sdk.setSoTimeout(30000);
                        OutputStream out = sdk.getOutputStream();
                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));
                        JsonObject initial = parseHello(in.readLine());
                        assertTrue(methodName + " initial HELLO asyncExecutors=true",
                                initial.get("asyncExecutors").getAsBoolean());
                        send(out, "ACK_ID");

                        // W1: lazy capture inside request window -> contaminates W1 AND refreshes HELLO before COV!
                        send(out, "START ts/w1");
                        assertEquals("ACK ts/w1", in.readLine());
                        CountDownLatch w1Ran = new CountDownLatch(1);
                        Object tok1 = RequestGate.enter();
                        try {
                            probes[1] = true;
                            lazyCaller.getMethod(methodName, Executor.class, Runnable.class)
                                    .invoke(null, tpe, (Runnable) w1Ran::countDown);
                        } finally {
                            RequestGate.exit(tok1);
                        }
                        assertTrue(w1Ran.await(5, TimeUnit.SECONDS));
                        send(out, "END ts/w1");
                        JsonObject refreshed = parseHello(in.readLine());
                        assertTrue(methodName + " keeps windowCut=true",
                                refreshed.get("windowCut").getAsBoolean());
                        assertFalse(methodName + " W1 in-window capture must refresh HELLO asyncExecutors=false BEFORE COV",
                                refreshed.get("asyncExecutors").getAsBoolean());
                        String cov1 = in.readLine();
                        assertTrue(methodName + " W1 COV must carry untracked-executor: " + cov1,
                                cov1.contains("untracked-executor:"));
                        assertEquals("ACK ts/w1", in.readLine());

                        // W2: invokes already-cached ref -> HELLO already says asyncExecutors=false (never reverts).
                        send(out, "START ts/w2");
                        assertEquals("ACK ts/w2", in.readLine());
                        CountDownLatch w2Ran = new CountDownLatch(1);
                        Object tok2 = RequestGate.enter();
                        try {
                            probes[1] = true;
                            lazyCaller.getMethod(methodName, Executor.class, Runnable.class)
                                    .invoke(null, tpe, (Runnable) () -> {
                                        probes[0] = true;
                                        w2Ran.countDown();
                                    });
                        } finally {
                            RequestGate.exit(tok2);
                        }
                        assertTrue(w2Ran.await(5, TimeUnit.SECONDS));
                        send(out, "END ts/w2");
                        assertTrue(in.readLine().startsWith("COV "));
                        assertEquals("ACK ts/w2", in.readLine());
                    }

                    // Reconnect keeps asyncExecutors=false permanently latched.
                    try (Socket sdk2 = collector.accept()) {
                        sdk2.setSoTimeout(30000);
                        BufferedReader in2 = new BufferedReader(
                                new InputStreamReader(sdk2.getInputStream(), StandardCharsets.UTF_8));
                        JsonObject reconn = parseHello(in2.readLine());
                        assertFalse(methodName + " reconnect keeps asyncExecutors=false",
                                reconn.get("asyncExecutors").getAsBoolean());
                    }
                } finally {
                    KeployDedupAgent.stop();
                    collector.close();
                    collector = null;
                    System.clearProperty(ENDPOINT_PROPERTY);
                }
            }

            // 2. Unix transport: lazy in-window capture flips END ACK caps to asyncExecutors=false.
            lazyCaller.getMethod("reset").invoke(null);
            KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
            File sockDir = tmp.newFolder("unix-lazy-inwindow");
            KeployDedupAgent.controlSocketPath = new File(sockDir, "ctrl.sock").getAbsolutePath();
            KeployDedupAgent.dataSocketPath = new File(sockDir, "data.sock").getAbsolutePath();
            org.newsclub.net.unix.AFUNIXServerSocket dataServer =
                    org.newsclub.net.unix.AFUNIXServerSocket.newInstance();
            dataServer.bind(org.newsclub.net.unix.AFUNIXSocketAddress.of(new File(KeployDedupAgent.dataSocketPath)));
            Thread dataReader = new Thread(() -> {
                while (!dataServer.isClosed()) {
                    try (Socket conn = dataServer.accept();
                         BufferedReader r = new BufferedReader(
                                 new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        while (r.readLine() != null) {
                            // Drain.
                        }
                    } catch (IOException ignored) {
                    }
                }
            }, "unix-lazy-inwindow-reader");
            dataReader.setDaemon(true);
            dataReader.start();
            try {
                assertTrue(KeployDedupAgent.start());
                String start1 = sendUnixControl("START ts/u-lazy-1");
                assertTrue("Unix START before lazy capture reports asyncExecutors=true: " + start1,
                        start1.contains("asyncExecutors=true"));
                CountDownLatch ran = new CountDownLatch(1);
                Object tok = RequestGate.enter();
                try {
                    lazyCaller.getMethod("callBound", Executor.class, Runnable.class)
                            .invoke(null, tpe, (Runnable) ran::countDown);
                } finally {
                    RequestGate.exit(tok);
                }
                assertTrue(ran.await(5, TimeUnit.SECONDS));
                String end1 = sendUnixControl("END ts/u-lazy-1");
                assertTrue("Unix END after in-window lazy capture must report asyncExecutors=false: " + end1,
                        end1.contains("asyncExecutors=false"));
                String start2 = sendUnixControl("START ts/u-lazy-2");
                assertTrue("Unix W2 stays asyncExecutors=false: " + start2,
                        start2.contains("asyncExecutors=false"));
            } finally {
                KeployDedupAgent.stop();
                dataServer.close();
            }
        } finally {
            tpe.shutdownNow();
        }
    }

    @Test(timeout = 60000)
    public void asyncMethodRefsThreadStartCfRunAsyncAndScheduleLatchAndContaminate() throws Exception {
        Class<?> threadCaller = rewriteClassWithWrapExecutorCalls(AsyncMethodRefThreadStartCaller.class);
        Class<?> cfCaller = rewriteClassWithWrapExecutorCalls(AsyncMethodRefCfRunAsyncCaller.class);
        Class<?> schedCaller = rewriteClassWithWrapExecutorCalls(AsyncMethodRefScheduleCaller.class);

        byte[] shopBytes = new byte[] {74, 75, 76};
        File shop = new File(tmp.newFolder("async-mref-classes"), "com/acme/Shop.class");
        shop.getParentFile().mkdirs();
        Files.write(shop.toPath(), shopBytes);
        System.setProperty(CLASS_DIRS_PROPERTY, shop.getParentFile().getParentFile().getParent());

        java.util.concurrent.ScheduledExecutorService sched =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
            KeployDedupAgent.transformerAdded = true;
            KeployDedupAgent.executorCallSitesEnabled = true;
            KeployDedupAgent.observedNoServerEntry.set(false);
            ServerEntries.restoreCounted(Collections.<String>emptySet());
            ServerEntries.restoreUncounted(Collections.<String>emptySet());

            String[][] cases = new String[][] {
                {"thread", "untracked-async:method-ref:java.lang.Thread.start"},
                {"cf", "untracked-async:method-ref:java.util.concurrent.CompletableFuture.runAsync"},
                {"sched", "untracked-async:method-ref:java.util.concurrent.ScheduledExecutorService.schedule"}
            };
            for (String[] tc : cases) {
                String kind = tc[0];
                String wantReason = tc[1];

                // 1. Disarmed capture -> latches asyncExecutors=false on TCP HELLO.
                KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
                RequestGate.disarm();
                invokeAsyncMethodRefCapture(kind, threadCaller, cfCaller, schedCaller, sched);
                assertTrue(kind + " disarmed capture must latch",
                        KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());
                JsonObject hDisarmed = parseHello(connectAndReadHello(true));
                assertFalse(kind + " disarmed HELLO must say asyncExecutors=false",
                        hDisarmed.get("asyncExecutors").getAsBoolean());

                // 2. Armed outside-window capture -> latches asyncExecutors=false on Unix ACK.
                KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
                RequestGate.arm();
                invokeAsyncMethodRefCapture(kind, threadCaller, cfCaller, schedCaller, sched);
                assertTrue(kind + " armed-outside capture must latch",
                        KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());
                File sockDir = tmp.newFolder("unix-async-mref-" + kind);
                KeployDedupAgent.controlSocketPath = new File(sockDir, "ctrl.sock").getAbsolutePath();
                KeployDedupAgent.dataSocketPath = new File(sockDir, "data.sock").getAbsolutePath();
                System.clearProperty(ENDPOINT_PROPERTY);
                System.setProperty(WARMUP_DISABLED_PROPERTY, "true");
                assertTrue(KeployDedupAgent.start());
                try {
                    String ack = sendUnixControl("START ts/u-mref-" + kind);
                    assertTrue(kind + " Unix ACK must say asyncExecutors=false: " + ack,
                            ack.startsWith("ACK proto=3") && ack.contains("asyncExecutors=false"));
                } finally {
                    KeployDedupAgent.stop();
                }

                // 3. Armed inside-window capture -> contaminates window with wantReason AND latches!
                KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
                RequestGate.disarm();
                RequestGate.open("mref-async-" + kind, null);
                Object tok = RequestGate.enter();
                try {
                    invokeAsyncMethodRefCapture(kind, threadCaller, cfCaller, schedCaller, sched);
                } finally {
                    RequestGate.exit(tok);
                }
                RequestGate.WindowOutcome out = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
                assertTrue(kind + " in-window capture must contaminate with " + wantReason + ": " + out.contaminations,
                        out.contaminations.contains(wantReason));
                assertTrue(kind + " in-window capture must also latch asyncExecutors=false",
                        KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());
            }
        } finally {
            sched.shutdownNow();
        }
    }

    private static byte[] readClassBytes(Class<?> cls) throws IOException {
        String resource = cls.getName().replace('.', '/') + ".class";
        try (java.io.InputStream in = cls.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull("class bytes not found for " + resource, in);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void invokeAsyncMethodRefCapture(String kind, Class<?> threadCaller, Class<?> cfCaller,
            Class<?> schedCaller, java.util.concurrent.ScheduledExecutorService sched) throws Exception {
        if ("thread".equals(kind)) {
            threadCaller.getMethod("capture").invoke(null);
        } else if ("cf".equals(kind)) {
            cfCaller.getMethod("capture").invoke(null);
        } else {
            schedCaller.getMethod("capture", java.util.concurrent.ScheduledExecutorService.class).invoke(null, sched);
        }
    }

    @Test(timeout = 60000)
    public void springMulticasterPlainPoolAndUnhookedLibraryAsyncDispatchersDropAsyncExecutors() throws Exception {
        byte[] shopBytes = new byte[] {77, 78, 79};
        File shop = new File(tmp.newFolder("unhooked-async-dispatchers"), "com/acme/Shop.class");
        shop.getParentFile().mkdirs();
        Files.write(shop.toPath(), shopBytes);
        System.setProperty(CLASS_DIRS_PROPERTY, shop.getParentFile().getParentFile().getParent());

        KeployDedupAgent.transformerAdded = true;
        KeployDedupAgent.executorCallSitesEnabled = true;
        KeployDedupAgent.observedNoServerEntry.set(false);
        KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
        ServerEntries.restoreCounted(Collections.<String>emptySet());
        ServerEntries.restoreUncounted(Collections.<String>emptySet());
        ServerEntries.restoreUnhookedAsyncDispatchers(Collections.<String>emptySet());

        // 1. Spring SimpleApplicationEventMulticaster.setTaskExecutor bytecode hook:
        //    - null, SyncTaskExecutor, and hooked ThreadPoolTaskExecutor (initialized OR pending init) keep asyncExecutors=true.
        //    - plain JDK ThreadPoolExecutor latches asyncExecutors=false (both disarmed and armed) and contaminates inside window.
        byte[] multicasterBytes = readClassBytes(
                org.springframework.context.event.SimpleApplicationEventMulticaster.class);
        byte[] hookedBytes = ServerEntries.wrapSpringEventMulticaster(multicasterBytes);
        assertNotNull("SimpleApplicationEventMulticaster.setTaskExecutor must be instrumented", hookedBytes);
        // Re-wrapping must be idempotent.
        byte[] hookedAgain = ServerEntries.wrapSpringEventMulticaster(hookedBytes);
        assertNotNull(hookedAgain);

        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if ("org.springframework.context.event.SimpleApplicationEventMulticaster".equals(name)) {
                    Class<?> c = defineClass(name, hookedBytes, 0, hookedBytes.length);
                    if (resolve) {
                        resolveClass(c);
                    }
                    return c;
                }
                return super.loadClass(name, resolve);
            }
        };
        Class<?> hookedMulticaster = loader.loadClass(
                "org.springframework.context.event.SimpleApplicationEventMulticaster");
        Object mc = hookedMulticaster.getConstructor().newInstance();
        java.lang.reflect.Method setExec = hookedMulticaster.getMethod("setTaskExecutor", Executor.class);

        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor springPool =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        ThreadPoolExecutor plainPool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        try {
            ServerEntries.markSpringExecutorHookedForTests(springPool.getClass().getName());
            RequestGate.disarm();
            setExec.invoke(mc, new Object[] {null});
            setExec.invoke(mc, new org.springframework.core.task.SyncTaskExecutor());
            // Pending-init hooked Spring ThreadPoolTaskExecutor must NOT falsely latch!
            setExec.invoke(mc, springPool);
            springPool.initialize();
            setExec.invoke(mc, springPool);
            assertFalse("null / SyncTaskExecutor / hooked Spring executor must NOT latch",
                    KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());

            // Plain JDK ThreadPoolExecutor on SimpleApplicationEventMulticaster MUST latch while disarmed!
            setExec.invoke(mc, plainPool);
            assertTrue("SimpleApplicationEventMulticaster.setTaskExecutor(plainPool) must latch asyncExecutors=false",
                    KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());
            JsonObject hMc = parseHello(connectAndReadHello(true));
            assertFalse("HELLO must report asyncExecutors=false after plain-pool multicaster",
                    hMc.get("asyncExecutors").getAsBoolean());

            // Initialized hooked Spring ThreadPoolTaskExecutor with non-AbortPolicy (orders() == true)
            // MUST be rejected by isHookedSpringExecutorOrPendingInit and latch (kills MC_PENDING_ALLOW)!
            org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor badSpringPool =
                    new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
            badSpringPool.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
            badSpringPool.initialize();
            try {
                KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
                RequestGate.disarm();
                assertFalse("initialized Spring pool failing orders() must be rejected",
                        OrderedPools.isHookedSpringExecutorOrPendingInit(badSpringPool));
                setExec.invoke(mc, badSpringPool);
                assertTrue("initialized Spring pool failing orders() must latch via setTaskExecutor",
                        KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());
            } finally {
                badSpringPool.shutdown();
            }

            // Subclass overriding getTaskExecutor() to return a plain JDK pool (never calling setTaskExecutor)
            // MUST be caught by the multicastEvent() hook!
            KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
            RequestGate.disarm();
            RequestGate.open("mc-override-window", null);
            Object overrideMc = hookedMulticaster.getConstructor().newInstance();
            // Create a small dynamic subclass in the same loader overriding getTaskExecutor() -> plainPool:
            java.lang.reflect.Field taskExecField = hookedMulticaster.getDeclaredField("taskExecutor");
            taskExecField.setAccessible(true);
            taskExecField.set(overrideMc, plainPool);
            java.lang.reflect.Method multicast = hookedMulticaster.getMethod("multicastEvent",
                    org.springframework.context.ApplicationEvent.class);
            Object tokOverride = RequestGate.enter();
            try {
                multicast.invoke(overrideMc,
                        new org.springframework.context.PayloadApplicationEvent<String>("src", "ev"));
            } finally {
                RequestGate.exit(tokOverride);
            }
            RequestGate.WindowOutcome overrideOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertTrue("multicastEvent with plain taskExecutor must contaminate: " + overrideOut.contaminations,
                    overrideOut.contaminations.contains("untracked-executor:" + ThreadPoolExecutor.class.getName()));
            assertTrue("multicastEvent with plain taskExecutor must latch asyncExecutors=false",
                    KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());

            // Inside an active window, setTaskExecutor also contaminates!
            KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
            RequestGate.disarm();
            RequestGate.open("mc-plain-window", null);
            Object tok = RequestGate.enter();
            try {
                setExec.invoke(mc, plainPool);
            } finally {
                RequestGate.exit(tok);
            }
            RequestGate.WindowOutcome out = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertTrue("in-window plain-pool setTaskExecutor must contaminate: " + out.contaminations,
                    out.contaminations.contains("untracked-executor:" + ThreadPoolExecutor.class.getName()));
            assertTrue("in-window plain-pool setTaskExecutor must also latch",
                    KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());
        } finally {
            ServerEntries.unmarkSpringExecutorHookedForTests(springPool.getClass().getName());
            springPool.shutdown();
            plainPool.shutdownNow();
        }

        // 2. JVM-wide JDK class loads (e.g. Tomcat 9's NIO2 connector loading AsynchronousSocketChannel /
        //    AsynchronousServerSocketChannel) must NOT false-latch asyncExecutors=false!
        ServerEntries transformer = new ServerEntries(true, Collections.singleton("com/acme/App"), null);
        KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
        ServerEntries.restoreUnhookedAsyncDispatchers(Collections.<String>emptySet());
        transformer.transform(getClass().getClassLoader(), "java/nio/channels/AsynchronousSocketChannel", null, null,
                new byte[0]);
        transformer.transform(getClass().getClassLoader(), "java/nio/channels/AsynchronousServerSocketChannel", null,
                null, new byte[0]);
        assertFalse("JVM-wide NIO2 channel loads (Tomcat connector) must NOT latch hasUnhookedAsyncDispatcher",
                ServerEntries.hasUnhookedAsyncDispatcher());
        JsonObject hTomcat = parseHello(connectAndReadHello(true));
        assertTrue("HELLO must keep asyncExecutors=true after Tomcat NIO2 class loads",
                hTomcat.get("asyncExecutors").getAsBoolean());

        // 2b. asyncHookFailed must drop asyncExecutors=false in HELLO (kills CAPS_HOOKFAIL)!
        ServerEntries.setHookStatusForTests(false, false, false, false, true);
        JsonObject hAsyncHookFail = parseHello(connectAndReadHello(true));
        assertFalse("asyncHookFailed must declare asyncExecutors=false",
                hAsyncHookFail.get("asyncExecutors").getAsBoolean());
        ServerEntries.setHookStatusForTests(false, false, false, false, false);

        // 3. App-class constant-pool scan (tag 7 class refs AND tag 8 string refs) catches RxJava 1/2/3,
        //    Kotlin coroutines, OkHttp Dispatcher, NIO Asynchronous*, and SubmissionPublisher!
        String[] unhookedInternalNames = new String[] {
            "rx/schedulers/Schedulers",
            "io/reactivex/schedulers/Schedulers",
            "io/reactivex/rxjava3/schedulers/Schedulers",
            "kotlinx/coroutines/Dispatchers",
            "okhttp3/Dispatcher",
            "java/nio/channels/AsynchronousChannelGroup",
            "java/util/concurrent/SubmissionPublisher"
        };
        for (String internalName : unhookedInternalNames) {
            KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
            ServerEntries.restoreUnhookedAsyncDispatchers(Collections.<String>emptySet());
            byte[] appReferencingClass = generateAppReferencingClass("com/acme/App", internalName);
            transformer.transform(getClass().getClassLoader(), "com/acme/App", null, null, appReferencingClass);
            assertTrue("app class referencing " + internalName + " must mark hasUnhookedAsyncDispatcher",
                    ServerEntries.hasUnhookedAsyncDispatcher());
            JsonObject h = parseHello(connectAndReadHello(true));
            assertFalse("HELLO must report asyncExecutors=false when app references " + internalName,
                    h.get("asyncExecutors").getAsBoolean());
        }

        // 3b. Reflective Class.forName("java.util.concurrent.SubmissionPublisher") string literal in app class!
        KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
        ServerEntries.restoreUnhookedAsyncDispatchers(Collections.<String>emptySet());
        byte[] reflectiveSpApp = generateAppWithStringConstant("com/acme/App",
                "java.util.concurrent.SubmissionPublisher");
        transformer.transform(getClass().getClassLoader(), "com/acme/App", null, null, reflectiveSpApp);
        assertTrue("reflective SubmissionPublisher literal in app class must mark hasUnhookedAsyncDispatcher",
                ServerEntries.hasUnhookedAsyncDispatcher());

        // 3c. App-class constant-pool scan catches JDK AsynchronousFileChannel even when JDK class loaded before premain!
        KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
        ServerEntries.restoreUnhookedAsyncDispatchers(Collections.<String>emptySet());
        byte[] nioAppBytes = readClassBytes(FakeNioAsyncChannelApp.class);
        transformer.transform(getClass().getClassLoader(), "com/acme/App", null, null, nioAppBytes);
        assertTrue("app class referencing AsynchronousFileChannel must mark hasUnhookedAsyncDispatcher",
                ServerEntries.hasUnhookedAsyncDispatcher());
        JsonObject hNio = parseHello(connectAndReadHello(true));
        assertFalse("HELLO must report asyncExecutors=false when app uses AsynchronousFileChannel",
                hNio.get("asyncExecutors").getAsBoolean());

        // 4. Untracked handle queue overflow (>64 live handles) permanently latches asyncExecutors=false!
        KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
        ServerEntries.restoreUnhookedAsyncDispatchers(Collections.<String>emptySet());
        RequestGate.disarm();
        RequestGate.open("overflow-window", null);
        Object tokOv = RequestGate.enter();
        try {
            for (int i = 0; i < 66; i++) {
                java.util.concurrent.CompletableFuture<Void> pending = new java.util.concurrent.CompletableFuture<>();
                RequestGate.trackUntrackedFuture(pending, "untracked-async:schedule");
            }
        } finally {
            RequestGate.exit(tokOv);
        }
        RequestGate.awaitWindow(TimeUnit.MILLISECONDS.toNanos(50), true);
        assertTrue(">64 live untracked handles overflow must latch asyncExecutors=false",
                KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());
    }

    @Test(timeout = 60000)
    public void perClassDispatchAllowlistFailClosedAndLatchOnCallHooks() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        ServerEntries transformer = new ServerEntries(true, Collections.singleton("com/acme/App"), null);

        // 1. Every entry in ALLOWLISTED_DISPATCH_CLASSES must be an exact internal
        //    class name (no wildcards/prefixes) and must never include unhooked
        //    async frameworks.
        Set<String> allowlisted = ServerEntries.allowlistedDispatchClasses();
        assertFalse("allowlist must be non-empty", allowlisted.isEmpty());
        String[] forbiddenPrefixes = {
            "kotlinx/", "kotlin/", "reactor/", "okhttp3/", "com/google/common/",
            "org/springframework/integration/", "rx/", "io/reactivex/", "io/grpc/",
            "io/netty/", "org/apache/hc/", "org/apache/http/", "akka/",
            "org/apache/pekko/", "io/nats/", "io/fakeexternal/", "org/h2/store/fs/async/"
        };
        for (String cls : allowlisted) {
            assertFalse("allowlist entry must not end with '/': " + cls, cls.endsWith("/"));
            assertFalse("allowlist entry must not contain '*': " + cls, cls.contains("*"));
            assertFalse("allowlist entry must use internal '/' format: " + cls, cls.contains("."));
            for (String bad : forbiddenPrefixes) {
                assertFalse("unhooked async framework must never be allowlisted: " + cls, cls.startsWith(bad));
            }
            // Positive control: allowlisted class referencing Executor.execute must NOT latch.
            ServerEntries.restoreUnhookedAsyncDispatchers(Collections.<String>emptySet());
            transformer.transform(loader, cls, null, null, generateExecutorCaller(cls));
            assertFalse("allowlisted class must not false-latch on load: " + cls,
                    ServerEntries.hasUnhookedAsyncDispatcher());
        }

        // 2. Any non-allowlisted library class calling a concurrency dispatch primitive
        //    (Executor.execute, Thread.start, CompletableFuture.supplyAsync, BlockingQueue.offer,
        //    ConcurrentLinkedQueue.add, HttpClient.sendAsync) latches asyncExecutors=false
        //    without any package exclusion hiding it.
        String[][] unhookedLibCallers = {
            {"kotlinx/coroutines/scheduling/CoroutineScheduler", "java/lang/Thread", "start", "()V", "virtual"},
            {"kotlin/concurrent/ThreadsKt", "java/lang/Thread", "start", "()V", "virtual"},
            {"reactor/core/scheduler/ParallelScheduler", "java/util/concurrent/ScheduledExecutorService",
                "schedule", "(Ljava/lang/Runnable;JLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;", "interface"},
            {"okhttp3/internal/concurrent/TaskRunner", "java/util/concurrent/Executor", "execute", "(Ljava/lang/Runnable;)V", "interface"},
            {"com/google/common/eventbus/AsyncEventBus", "java/util/concurrent/Executor", "execute", "(Ljava/lang/Runnable;)V", "interface"},
            {"org/springframework/integration/channel/ExecutorChannel", "java/util/concurrent/Executor", "execute", "(Ljava/lang/Runnable;)V", "interface"},
            {"io/fakeexternal/CustomCallbackClient", "java/util/concurrent/LinkedBlockingQueue", "offer", "(Ljava/lang/Object;)Z", "virtual"},
            {"com/thirdparty/ClqBus", "java/util/concurrent/ConcurrentLinkedQueue", "add", "(Ljava/lang/Object;)Z", "virtual"},
            {"com/thirdparty/CfHelper", "java/util/concurrent/CompletableFuture", "supplyAsync",
                "(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;", "static"},
            {"com/thirdparty/JdkHttpAsync", "java/net/http/HttpClient", "sendAsync",
                "(Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;)Ljava/util/concurrent/CompletableFuture;", "virtual"},
        };
        for (String[] spec : unhookedLibCallers) {
            ServerEntries.restoreUnhookedAsyncDispatchers(Collections.<String>emptySet());
            byte[] bytes = generateMethodRefCaller(spec[0], spec[1], spec[2], spec[3], spec[4]);
            assertNull("non-app library class must never be bytecode-rewritten: " + spec[0],
                    transformer.transform(loader, spec[0], null, null, bytes));
            assertTrue("non-allowlisted library class calling " + spec[1] + "." + spec[2]
                    + " must latch asyncExecutors=false: " + spec[0],
                    ServerEntries.hasUnhookedAsyncDispatcher());
        }

        // 2b. Even a rewritten app class calling JDK HttpClient.sendAsync latches asyncExecutors=false.
        ServerEntries.restoreUnhookedAsyncDispatchers(Collections.<String>emptySet());
        byte[] appHttpAsync = generateMethodRefCaller("com/acme/App", "java/net/http/HttpClient", "sendAsync",
                "(Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;)Ljava/util/concurrent/CompletableFuture;",
                "virtual");
        transformer.transform(loader, "com/acme/App", null, null, appHttpAsync);
        assertTrue("app class calling HttpClient.sendAsync must latch asyncExecutors=false",
                ServerEntries.hasUnhookedAsyncDispatcher());

        // 2c. Non-allowlisted library Thread subclass (e.g. Kotlin's kotlin.concurrent.thread
        //     anonymous Thread subclass whose INVOKEVIRTUAL targets itself rather than java/lang/Thread)
        //     must latch asyncExecutors=false on load by construction.
        ServerEntries.restoreUnhookedAsyncDispatchers(Collections.<String>emptySet());
        ClassWriter threadSubCw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        threadSubCw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "kotlin/concurrent/ThreadsKt$thread$thread$1",
                null, "java/lang/Thread", null);
        emitDefaultCtor(threadSubCw);
        threadSubCw.visitEnd();
        assertNull("non-allowlisted Thread subclass must not be rewritten",
                transformer.transform(loader, "kotlin/concurrent/ThreadsKt$thread$thread$1", null, null,
                        threadSubCw.toByteArray()));
        assertTrue("non-allowlisted library Thread subclass must latch asyncExecutors=false",
                ServerEntries.hasUnhookedAsyncDispatcher());

        // 3. LATCH_ON_CALL hooks: loading sync Caffeine / CacheLoader / LocalAsyncCache /
        //    Spring ConcurrentMapCache does NOT latch; invoking their async methods DOES
        //    latch asyncExecutors=false and contaminates the active window!
        String[][] latchHooks = {
            {"com/github/benmanes/caffeine/cache/Caffeine", "buildAsync", "class"},
            {"com/github/benmanes/caffeine/cache/CacheLoader", "asyncReload", "interface"},
            {"com/github/benmanes/caffeine/cache/LocalAsyncCache", "get", "interface"},
            {"org/springframework/cache/concurrent/ConcurrentMapCache", "retrieve", "class"},
        };
        for (String[] hook : latchHooks) {
            ServerEntries.restoreUnhookedAsyncDispatchers(Collections.<String>emptySet());
            KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
            byte[] raw = generateLatchHookStandIn(hook[0], hook[1], "interface".equals(hook[2]));
            byte[] hooked = transformer.transform(loader, hook[0], null, null, raw);
            assertNotNull("LATCH_ON_CALL class must be instrumented: " + hook[0], hooked);
            assertFalse("loading " + hook[0] + " alone must not latch asyncExecutors",
                    ServerEntries.hasUnhookedAsyncDispatcher());
            assertFalse("loading " + hook[0] + " alone must not latch observedPreCapturedUntrackedExecutor",
                    KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());

            // Invoke the hooked method inside an active window via a concrete subclass or instance.
            ByteLoader bl = new ByteLoader(loader);
            Class<?> defined = bl.define(hook[0].replace('/', '.'), hooked);
            Object instance;
            if ("interface".equals(hook[2])) {
                String implName = hook[0] + "$TestImpl";
                byte[] implBytes = generateInterfaceImpl(implName, hook[0]);
                instance = bl.define(implName.replace('/', '.'), implBytes).getDeclaredConstructor().newInstance();
            } else {
                instance = defined.getDeclaredConstructor().newInstance();
            }
            RequestGate.disarm();
            RequestGate.open("latch-hook-" + hook[1], null);
            Object tok = RequestGate.enter();
            try {
                defined.getMethod(hook[1]).invoke(instance);
            } finally {
                RequestGate.exit(tok);
            }
            RequestGate.WindowOutcome out = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertTrue("invoking " + hook[0] + "." + hook[1] + " must latch asyncExecutors=false",
                    KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());
            assertFalse("invoking " + hook[0] + "." + hook[1] + " inside window must contaminate: "
                    + out.contaminations, out.contaminations.isEmpty());
        }
    }

    @Test(timeout = 60000)
    public void isolatedCallSiteAndJobGateLatchesForListenableFutureDirectQueueAndScheduledJobs() throws Exception {
        ClassLoader loader = getClass().getClassLoader();

        // 1. Direct LinkedBlockingQueue.offer call site (static owner IS LinkedBlockingQueue,
        //    not java/util/Queue) must contaminate with untracked-async:blocking-queue.
        String bqClsName = "com/acme/DirectBlockingQueueCaller";
        ClassWriter bqCw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        bqCw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, bqClsName, null, "java/lang/Object", null);
        emitDefaultCtor(bqCw);
        MethodVisitor bqMv = bqCw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "enqueue",
                "(Ljava/util/concurrent/LinkedBlockingQueue;Ljava/lang/Object;)Z", null, null);
        bqMv.visitCode();
        bqMv.visitVarInsn(Opcodes.ALOAD, 0);
        bqMv.visitVarInsn(Opcodes.ALOAD, 1);
        bqMv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/LinkedBlockingQueue",
                "offer", "(Ljava/lang/Object;)Z", false);
        bqMv.visitInsn(Opcodes.IRETURN);
        bqMv.visitMaxs(2, 2);
        bqMv.visitEnd();
        bqCw.visitEnd();

        ServerEntries bqEntries = new ServerEntries(true, Collections.singleton(bqClsName), null);
        byte[] bqRewritten = bqEntries.transform(loader, bqClsName, null, null, bqCw.toByteArray());
        assertNotNull("direct LinkedBlockingQueue caller must be rewritten", bqRewritten);
        Class<?> bqCls = new ByteLoader(loader).define(bqClsName.replace('/', '.'), bqRewritten);
        RequestGate.disarm();
        RequestGate.open("direct-bq", null);
        Object tok = RequestGate.enter();
        try {
            bqCls.getMethod("enqueue", LinkedBlockingQueue.class, Object.class)
                    .invoke(null, new LinkedBlockingQueue<Object>(), "item");
        } finally {
            RequestGate.exit(tok);
        }
        RequestGate.WindowOutcome bqOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
        assertTrue("direct LinkedBlockingQueue.offer must contaminate: " + bqOut.contaminations,
                bqOut.contaminations.contains("untracked-async:blocking-queue"));

        // 2. Spring ListenableFuture.addCallback call site in a rewritten app class must
        //    contaminate with untracked-async:spring-listenable-future.
        String lfCbIface = "org/springframework/util/concurrent/ListenableFutureCallback";
        String lfIface = "org/springframework/util/concurrent/ListenableFuture";
        String lfImpl = "com/acme/StubListenableFuture";
        String lfCaller = "com/acme/SpringListenableCaller";
        String addCbDesc = "(L" + lfCbIface + ";)V";
        ByteLoader lfLoader = new ByteLoader(loader);

        ClassWriter lfCbCw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        lfCbCw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                lfCbIface, null, "java/lang/Object", null);
        lfCbCw.visitEnd();
        Class<?> lfCbCls = lfLoader.define(lfCbIface.replace('/', '.'), lfCbCw.toByteArray());

        ClassWriter lfIfaceCw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        lfIfaceCw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                lfIface, null, "java/lang/Object", null);
        lfIfaceCw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "addCallback",
                addCbDesc, null, null).visitEnd();
        lfIfaceCw.visitEnd();
        Class<?> lfIfaceCls = lfLoader.define(lfIface.replace('/', '.'), lfIfaceCw.toByteArray());

        ClassWriter lfImplCw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        lfImplCw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, lfImpl, null, "java/lang/Object", new String[] {lfIface});
        emitDefaultCtor(lfImplCw);
        MethodVisitor implMv = lfImplCw.visitMethod(Opcodes.ACC_PUBLIC, "addCallback",
                addCbDesc, null, null);
        implMv.visitCode();
        implMv.visitInsn(Opcodes.RETURN);
        implMv.visitMaxs(0, 2);
        implMv.visitEnd();
        lfImplCw.visitEnd();
        Object lfInstance = lfLoader.define(lfImpl.replace('/', '.'), lfImplCw.toByteArray())
                .getDeclaredConstructor().newInstance();

        ClassWriter lfCallerCw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        lfCallerCw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, lfCaller, null, "java/lang/Object", null);
        emitDefaultCtor(lfCallerCw);
        MethodVisitor callerMv = lfCallerCw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "attach",
                "(L" + lfIface + ";L" + lfCbIface + ";)V", null, null);
        callerMv.visitCode();
        callerMv.visitVarInsn(Opcodes.ALOAD, 0);
        callerMv.visitVarInsn(Opcodes.ALOAD, 1);
        callerMv.visitMethodInsn(Opcodes.INVOKEINTERFACE, lfIface, "addCallback", addCbDesc, true);
        callerMv.visitInsn(Opcodes.RETURN);
        callerMv.visitMaxs(2, 2);
        callerMv.visitEnd();
        lfCallerCw.visitEnd();

        ServerEntries lfEntries = new ServerEntries(true, Collections.singleton(lfCaller), null);
        byte[] lfRewritten = lfEntries.transform(lfLoader, lfCaller, null, null, lfCallerCw.toByteArray());
        assertNotNull("Spring ListenableFuture caller must be rewritten", lfRewritten);
        Class<?> lfCallerCls = lfLoader.define(lfCaller.replace('/', '.'), lfRewritten);
        RequestGate.disarm();
        RequestGate.open("spring-lf", null);
        tok = RequestGate.enter();
        try {
            lfCallerCls.getMethod("attach", lfIfaceCls, lfCbCls).invoke(null, lfInstance, null);
        } finally {
            RequestGate.exit(tok);
        }
        RequestGate.WindowOutcome lfOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
        assertTrue("Spring ListenableFuture.addCallback must contaminate: " + lfOut.contaminations,
                lfOut.contaminations.contains("untracked-async:spring-listenable-future"));

        // 3. Scheduled job classes (ScheduledMethodRunnable / JobRunShell) latch on load.
        ServerEntries schedEntries = new ServerEntries(true, Collections.singleton("com/acme/App"), null);
        for (String jobCls : new String[] {
                "org/springframework/scheduling/support/ScheduledMethodRunnable",
                "org/quartz/core/JobRunShell"
        }) {
            ServerEntries.restoreUnhookedAsyncDispatchers(Collections.<String>emptySet());
            ClassWriter jobCw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            jobCw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, jobCls, null, "java/lang/Object", null);
            emitDefaultCtor(jobCw);
            MethodVisitor runMv = jobCw.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
            runMv.visitCode();
            runMv.visitInsn(Opcodes.RETURN);
            runMv.visitMaxs(0, 1);
            runMv.visitEnd();
            jobCw.visitEnd();
            assertNotNull("scheduled job class must still be hooked: " + jobCls,
                    schedEntries.transform(loader, jobCls, null, null, jobCw.toByteArray()));
            assertTrue("loading scheduled job class must latch asyncExecutors=false: " + jobCls,
                    ServerEntries.hasUnhookedAsyncDispatcher());
        }

        // 4. JobGate.enter for async message listeners ("kafka", "listener") and scheduled jobs
        //    ("scheduled", "quartz") must each independently latch observedPreCapturedUntrackedExecutor.
        RequestGate.arm();
        try {
            for (String kind : new String[] {"kafka", "listener", "scheduled", "quartz"}) {
                KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
                Object jobTok = JobGate.enter("unit-" + kind, kind);
                try {
                    assertTrue("JobGate.enter(" + kind + ") must latch asyncExecutors=false",
                            KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());
                } finally {
                    JobGate.exit(jobTok);
                }
            }
        } finally {
            RequestGate.disarm();
            ServerEntries.restoreUnhookedAsyncDispatchers(Collections.<String>emptySet());
            KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
        }
    }

    @Test(timeout = 60000)
    public void isolatedCollectionAndDequePreScanExecutorOutsideWindowAndJdkPoolSubclasses() throws Exception {
        ClassLoader loader = getClass().getClassLoader();

        // 1. Isolated Collection.add-only caller (no Queue/Deque in constant pool).
        String colClsName = "com/acme/CollectionOnlyCaller";
        ClassWriter colCw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        colCw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, colClsName, null, "java/lang/Object", null);
        emitDefaultCtor(colCw);
        MethodVisitor colMv = colCw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "push",
                "(Ljava/util/Collection;Ljava/lang/Object;)Z", null, null);
        colMv.visitCode();
        colMv.visitVarInsn(Opcodes.ALOAD, 0);
        colMv.visitVarInsn(Opcodes.ALOAD, 1);
        colMv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Collection", "add", "(Ljava/lang/Object;)Z", true);
        colMv.visitInsn(Opcodes.IRETURN);
        colMv.visitMaxs(2, 2);
        colMv.visitEnd();
        colCw.visitEnd();
        ServerEntries colEntries = new ServerEntries(true, Collections.singleton(colClsName), null);
        byte[] colRewritten = colEntries.transform(loader, colClsName, null, null, colCw.toByteArray());
        assertNotNull("Collection.add-only caller must be rewritten", colRewritten);
        Class<?> colCls = new ByteLoader(loader).define(colClsName.replace('/', '.'), colRewritten);
        RequestGate.disarm();
        RequestGate.open("col-only-bq", null);
        Object tok = RequestGate.enter();
        try {
            colCls.getMethod("push", java.util.Collection.class, Object.class)
                    .invoke(null, new LinkedBlockingQueue<Object>(), "x");
        } finally {
            RequestGate.exit(tok);
        }
        RequestGate.WindowOutcome colOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
        assertTrue("Collection.add backed by LinkedBlockingQueue must contaminate: " + colOut.contaminations,
                colOut.contaminations.contains("untracked-async:blocking-queue"));

        // 2. Isolated Deque.addFirst-only caller (no Queue/Collection/offer in constant pool).
        String dqClsName = "com/acme/DequeOnlyCaller";
        ClassWriter dqCw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        dqCw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, dqClsName, null, "java/lang/Object", null);
        emitDefaultCtor(dqCw);
        MethodVisitor dqMv = dqCw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pushFirst",
                "(Ljava/util/Deque;Ljava/lang/Object;)V", null, null);
        dqMv.visitCode();
        dqMv.visitVarInsn(Opcodes.ALOAD, 0);
        dqMv.visitVarInsn(Opcodes.ALOAD, 1);
        dqMv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Deque", "addFirst", "(Ljava/lang/Object;)V", true);
        dqMv.visitInsn(Opcodes.RETURN);
        dqMv.visitMaxs(2, 2);
        dqMv.visitEnd();
        dqCw.visitEnd();
        ServerEntries dqEntries = new ServerEntries(true, Collections.singleton(dqClsName), null);
        byte[] dqRewritten = dqEntries.transform(loader, dqClsName, null, null, dqCw.toByteArray());
        assertNotNull("Deque.addFirst-only caller must be rewritten", dqRewritten);
        Class<?> dqCls = new ByteLoader(loader).define(dqClsName.replace('/', '.'), dqRewritten);
        RequestGate.disarm();
        RequestGate.open("dq-only-bq", null);
        tok = RequestGate.enter();
        try {
            dqCls.getMethod("pushFirst", java.util.Deque.class, Object.class)
                    .invoke(null, new java.util.concurrent.LinkedBlockingDeque<Object>(), "x");
        } finally {
            RequestGate.exit(tok);
        }
        RequestGate.WindowOutcome dqOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
        assertTrue("Deque.addFirst backed by LinkedBlockingDeque must contaminate: " + dqOut.contaminations,
                dqOut.contaminations.contains("untracked-async:blocking-queue"));

        // 3. Any non-inline executor hand-off outside a window and job (armed or unarmed)
        //    must latch asyncExecutors=false; inline executors must NOT latch.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        org.springframework.core.task.SyncTaskExecutor inlineExec =
                new org.springframework.core.task.SyncTaskExecutor();
        try {
            // Inline executor outside window: armed and unarmed must stay clean.
            RequestGate.disarm();
            KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
            RequestGate.execute(inlineExec, () -> { });
            assertFalse("unarmed inline executor must not latch",
                    KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());
            RequestGate.arm();
            RequestGate.execute(inlineExec, () -> { });
            assertFalse("armed inline executor must not latch",
                    KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());

            // Non-inline execute / submit / runAsync / supplyAsync outside window (armed + unarmed): ALL latch!
            for (boolean armedMode : new boolean[] {false, true}) {
                if (armedMode) {
                    RequestGate.arm();
                } else {
                    RequestGate.disarm();
                }
                KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
                RequestGate.execute(pool, () -> { });
                assertTrue("execute outside window (armed=" + armedMode + ") must latch",
                        KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());

                KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
                RequestGate.submit(pool, () -> { }).get(5, TimeUnit.SECONDS);
                assertTrue("submit(Runnable) outside window (armed=" + armedMode + ") must latch",
                        KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());

                KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
                RequestGate.submit(pool, () -> "ok").get(5, TimeUnit.SECONDS);
                assertTrue("submit(Callable) outside window (armed=" + armedMode + ") must latch",
                        KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());

                KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
                RequestGate.runAsync(() -> { }, pool).get(5, TimeUnit.SECONDS);
                assertTrue("runAsync outside window (armed=" + armedMode + ") must latch",
                        KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());

                KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
                RequestGate.supplyAsync(() -> "ok", pool).get(5, TimeUnit.SECONDS);
                assertTrue("supplyAsync outside window (armed=" + armedMode + ") must latch",
                        KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());
            }

            // 4. Non-allowlisted library subclasses of JDK concurrency types must latch on load.
            ServerEntries subEntries = new ServerEntries(true, Collections.singleton("com/acme/App"), null);
            String[][] jdkSubclasses = {
                {"io/fakeexternal/CustomTpe", "java/util/concurrent/ThreadPoolExecutor"},
                {"io/fakeexternal/CustomStpe", "java/util/concurrent/ScheduledThreadPoolExecutor"},
                {"io/fakeexternal/CustomFjp", "java/util/concurrent/ForkJoinPool"},
                {"io/fakeexternal/CustomTimer", "java/util/Timer"},
                {"io/fakeexternal/CustomAes", "java/util/concurrent/AbstractExecutorService"},
            };
            for (String[] spec : jdkSubclasses) {
                ServerEntries.restoreUnhookedAsyncDispatchers(Collections.<String>emptySet());
                ClassWriter subCw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                subCw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, spec[0], null, spec[1], null);
                emitDefaultCtor(subCw);
                subCw.visitEnd();
                assertNull("library subclass must not be rewritten: " + spec[0],
                        subEntries.transform(loader, spec[0], null, null, subCw.toByteArray()));
                assertTrue("non-allowlisted subclass of " + spec[1] + " must latch asyncExecutors=false",
                        ServerEntries.hasUnhookedAsyncDispatcher());
            }
        } finally {
            pool.shutdownNow();
            RequestGate.disarm();
            ServerEntries.restoreUnhookedAsyncDispatchers(Collections.<String>emptySet());
            KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
        }
    }

    @Test(timeout = 60000)
    public void queueRuntimeCheckDirectSubmissionPublisherMessagingProducerAndExternalOverflow() throws Exception {
        ClassLoader loader = getClass().getClassLoader();

        // 1. Runtime Queue/Deque/Collection instanceof check (kills QUEUE_RUNTIME_NOOP):
        //    static java.util.Queue / Deque / Collection call sites must contaminate for
        //    BlockingQueue & ConcurrentLinkedQueue, and stay clean for ArrayDeque / ArrayList.
        String qClsName = "com/acme/QueueRuntimeCaller";
        ServerEntries qEntries = new ServerEntries(true, Collections.singleton(qClsName), null);
        byte[] qRewritten = qEntries.transform(loader, qClsName, null, null, generateQueueRuntimeCaller(qClsName));
        assertNotNull("Queue/Deque/Collection caller must be rewritten", qRewritten);
        Class<?> qCls = new ByteLoader(loader).define(qClsName.replace('/', '.'), qRewritten);

        // BlockingQueue via static java.util.Queue -> contaminates!
        RequestGate.disarm();
        RequestGate.open("q-blocking", null);
        Object tok = RequestGate.enter();
        try {
            qCls.getMethod("offerQueue", java.util.Queue.class, Object.class)
                    .invoke(null, new LinkedBlockingQueue<Object>(), "item");
        } finally {
            RequestGate.exit(tok);
        }
        RequestGate.WindowOutcome qOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
        assertTrue("static Queue backed by LinkedBlockingQueue must contaminate: " + qOut.contaminations,
                qOut.contaminations.contains("untracked-async:blocking-queue"));

        // LinkedBlockingDeque via static java.util.Deque -> contaminates!
        RequestGate.disarm();
        RequestGate.open("q-deque", null);
        tok = RequestGate.enter();
        try {
            qCls.getMethod("offerLastDeque", java.util.Deque.class, Object.class)
                    .invoke(null, new java.util.concurrent.LinkedBlockingDeque<Object>(), "item");
        } finally {
            RequestGate.exit(tok);
        }
        RequestGate.WindowOutcome dOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
        assertTrue("static Deque backed by LinkedBlockingDeque must contaminate: " + dOut.contaminations,
                dOut.contaminations.contains("untracked-async:blocking-queue"));

        // LinkedBlockingQueue via static java.util.Collection.add -> contaminates!
        RequestGate.disarm();
        RequestGate.open("q-collection", null);
        tok = RequestGate.enter();
        try {
            qCls.getMethod("addCollection", java.util.Collection.class, Object.class)
                    .invoke(null, new LinkedBlockingQueue<Object>(), "item");
        } finally {
            RequestGate.exit(tok);
        }
        RequestGate.WindowOutcome cOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
        assertTrue("static Collection backed by LinkedBlockingQueue must contaminate: " + cOut.contaminations,
                cOut.contaminations.contains("untracked-async:blocking-queue"));

        // ConcurrentLinkedQueue via static java.util.Queue -> contaminates!
        RequestGate.disarm();
        RequestGate.open("q-clq", null);
        tok = RequestGate.enter();
        try {
            qCls.getMethod("offerQueue", java.util.Queue.class, Object.class)
                    .invoke(null, new java.util.concurrent.ConcurrentLinkedQueue<Object>(), "item");
        } finally {
            RequestGate.exit(tok);
        }
        RequestGate.WindowOutcome clqOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
        assertTrue("static Queue backed by ConcurrentLinkedQueue must contaminate: " + clqOut.contaminations,
                clqOut.contaminations.contains("untracked-async:blocking-queue"));

        // Negative controls: ArrayDeque & ArrayList must NOT contaminate!
        RequestGate.disarm();
        RequestGate.open("q-plain", null);
        tok = RequestGate.enter();
        try {
            qCls.getMethod("offerQueue", java.util.Queue.class, Object.class)
                    .invoke(null, new java.util.ArrayDeque<Object>(), "item");
            qCls.getMethod("offerLastDeque", java.util.Deque.class, Object.class)
                    .invoke(null, new java.util.ArrayDeque<Object>(), "item");
            qCls.getMethod("addCollection", java.util.Collection.class, Object.class)
                    .invoke(null, new ArrayList<Object>(), "item");
        } finally {
            RequestGate.exit(tok);
        }
        RequestGate.WindowOutcome plainOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
        assertTrue("plain ArrayDeque/ArrayList must not contaminate: " + plainOut.contaminations,
                plainOut.contaminations.isEmpty());

        // 2. Direct bytecode SubmissionPublisher.submit call site (kills SUBPUB_FLAG_DROP):
        String spClsName = "com/acme/DirectSubPubCaller";
        ServerEntries spEntries = new ServerEntries(true, Collections.singleton(spClsName), null);
        byte[] spRewritten = spEntries.transform(loader, spClsName, null, null,
                generateDirectSubmissionPublisherCaller(spClsName));
        assertNotNull("direct SubmissionPublisher.submit caller must be rewritten", spRewritten);
        assertTrue("rewritten SubmissionPublisher caller must emit untracked-async:submission-publisher",
                containsLdcString(spRewritten, "untracked-async:submission-publisher"));
        String spec = System.getProperty("java.specification.version", "1.8");
        int major = spec.startsWith("1.") ? 8 : Integer.parseInt(spec);
        if (major >= 9) {
            Class<?> spCaller = new ByteLoader(loader).define(spClsName.replace('/', '.'), spRewritten);
            Class<?> spType = Class.forName("java.util.concurrent.SubmissionPublisher");
            Object pub = spType.getConstructor().newInstance();
            try {
                RequestGate.disarm();
                RequestGate.open("sp-direct", null);
                tok = RequestGate.enter();
                try {
                    spCaller.getMethod("publish", Object.class, Object.class).invoke(null, pub, "msg");
                } finally {
                    RequestGate.exit(tok);
                }
                RequestGate.WindowOutcome spOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
                assertTrue("direct SubmissionPublisher.submit must contaminate W1: " + spOut.contaminations,
                        spOut.contaminations.contains("untracked-async:submission-publisher"));
            } finally {
                spType.getMethod("close").invoke(pub);
            }
        }

        // 3. Direct bytecode messaging producer call sites (kills MSG_PRODUCE_FLAG_DROP)
        //    with NO listener running: KafkaTemplate, KafkaOperations, RabbitOperations,
        //    JmsMessagingTemplate, StreamBridge, and Reactor KafkaSender!
        String[][] msgProducers = {
            {"org/springframework/kafka/core/KafkaTemplate", "send", "class"},
            {"org/springframework/kafka/core/KafkaOperations", "send", "interface"},
            {"org/springframework/amqp/rabbit/core/RabbitOperations", "convertAndSend", "interface"},
            {"org/springframework/jms/core/JmsMessagingTemplate", "convertAndSend", "class"},
            {"org/springframework/cloud/stream/function/StreamBridge", "send", "class"},
            {"reactor/kafka/sender/KafkaSender", "send", "interface"},
        };
        for (int i = 0; i < msgProducers.length; i++) {
            final String owner = msgProducers[i][0];
            final String method = msgProducers[i][1];
            final boolean isIface = "interface".equals(msgProducers[i][2]);
            final String callerName = "com/acme/MsgProducerCaller" + i;
            ServerEntries msgEntries = new ServerEntries(true, Collections.singleton(callerName), null);
            byte[] callerBytes = generateMsgProducerCaller(callerName, owner, method, isIface);
            byte[] rewrittenCaller = msgEntries.transform(loader, callerName, null, null, callerBytes);
            assertNotNull("messaging producer caller must be rewritten for " + owner, rewrittenCaller);

            final byte[] ownerBytes = generateMsgProducerStub(owner, method, isIface);
            final String implName = owner + "$StubImpl";
            final byte[] implBytes = isIface ? generateMsgProducerImpl(implName, owner, method) : null;
            ClassLoader msgLoader = new ClassLoader(loader) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    String internal = name.replace('.', '/');
                    if (owner.equals(internal)) {
                        return defineClass(name, ownerBytes, 0, ownerBytes.length);
                    }
                    if (isIface && implName.equals(internal)) {
                        return defineClass(name, implBytes, 0, implBytes.length);
                    }
                    if (callerName.equals(internal)) {
                        return defineClass(name, rewrittenCaller, 0, rewrittenCaller.length);
                    }
                    throw new ClassNotFoundException(name);
                }
            };
            Object producer = msgLoader.loadClass((isIface ? implName : owner).replace('/', '.'))
                    .getDeclaredConstructor().newInstance();
            Class<?> callerCls = msgLoader.loadClass(callerName.replace('/', '.'));

            RequestGate.disarm();
            RequestGate.open("msg-prod-" + i, null);
            tok = RequestGate.enter();
            try {
                callerCls.getMethod("produce", Object.class).invoke(null, producer);
            } finally {
                RequestGate.exit(tok);
            }
            RequestGate.WindowOutcome mOut = RequestGate.awaitWindow(TimeUnit.SECONDS.toNanos(2), true);
            assertTrue("direct " + owner + "." + method + " must contaminate W1 with untracked-async:messaging-produce: "
                    + mOut.contaminations, mOut.contaminations.contains("untracked-async:messaging-produce"));
        }

        // 4. External untracked handle queue overflow (>64 live handles) for BOTH active-job
        //    and expired-job branches (kills OVERFLOW_EXT_NOLATCH deterministically).
        RequestGate.disarm();
        RequestGate.open("ext-ov-active-init", null);
        Object jobTok = JobGate.enter(new Object(), "quartz");
        try {
            KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
            for (int i = 0; i < 66; i++) {
                RequestGate.trackUntrackedFuture(new CompletableFuture<Void>(), "untracked-async:schedule");
            }
            assertTrue("active-job >64 external handle overflow must latch asyncExecutors=false",
                    KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());
        } finally {
            JobGate.exit(jobTok);
        }

        RequestGate.disarm();
        RequestGate.open("ext-ov-expired-init", null);
        JobGate.Attribution expiredAttr = new JobGate.Attribution(
                "quartz:expired.job", true, System.nanoTime() - TimeUnit.SECONDS.toNanos(5));
        Object expTok = JobGate.enterAs(expiredAttr);
        try {
            KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
            for (int i = 0; i < 66; i++) {
                RequestGate.trackUntrackedFuture(new CompletableFuture<Void>(), "untracked-async:schedule");
            }
            assertTrue("expired-job >64 external handle overflow must latch asyncExecutors=false",
                    KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());
        } finally {
            JobGate.exit(expTok);
        }

        // 5. App code starting a background thread outside any request window or job
        //    must latch asyncExecutors=false (closes app-owned queue/outbox workers).
        RequestGate.disarm();
        KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
        Thread bg = new Thread(() -> { }, "app-outside-bg");
        RequestGate.startThread(bg);
        bg.join(5000);
        assertTrue("Thread.start outside window/job while unarmed must latch asyncExecutors=false",
                KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());

        RequestGate.open("bg-armed-outside", null);
        KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
        Thread bgArmed = new Thread(() -> { }, "app-outside-bg-armed");
        RequestGate.startThread(bgArmed);
        bgArmed.join(5000);
        assertTrue("Thread.start outside window/job while armed must latch asyncExecutors=false",
                KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());
    }

    private static boolean containsLdcString(byte[] classfile, final String needle) {
        final boolean[] found = {false};
        new org.objectweb.asm.ClassReader(classfile).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (needle.equals(value)) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, 0);
        return found[0];
    }

    private static byte[] generateMethodRefCaller(String clsName, String owner, String mname, String desc,
            String kind) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, clsName, null, "java/lang/Object", null);
        emitDefaultCtor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", "()V", null, null);
        mv.visitCode();
        int op = "static".equals(kind) ? Opcodes.INVOKESTATIC
                : "interface".equals(kind) ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
        if (op != Opcodes.INVOKESTATIC) {
            mv.visitInsn(Opcodes.ACONST_NULL);
        }
        org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(desc);
        for (org.objectweb.asm.Type arg : args) {
            if (arg.getSort() == org.objectweb.asm.Type.LONG) {
                mv.visitInsn(Opcodes.LCONST_0);
            } else {
                mv.visitInsn(Opcodes.ACONST_NULL);
            }
        }
        mv.visitMethodInsn(op, owner, mname, desc, op == Opcodes.INVOKEINTERFACE);
        org.objectweb.asm.Type ret = org.objectweb.asm.Type.getReturnType(desc);
        if (ret.getSort() != org.objectweb.asm.Type.VOID) {
            mv.visitInsn(ret.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateLatchHookStandIn(String clsName, String methodName, boolean isInterface) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        int acc = Opcodes.ACC_PUBLIC | (isInterface ? (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT) : 0);
        cw.visit(Opcodes.V1_8, acc, clsName, null, "java/lang/Object", null);
        if (!isInterface) {
            emitDefaultCtor(cw);
        }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateInterfaceImpl(String implName, String ifaceName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, implName, null, "java/lang/Object", new String[] {ifaceName});
        emitDefaultCtor(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateQueueRuntimeCaller(String clsName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, clsName, null, "java/lang/Object", null);
        emitDefaultCtor(cw);

        MethodVisitor m1 = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "offerQueue",
                "(Ljava/util/Queue;Ljava/lang/Object;)Z", null, null);
        m1.visitCode();
        m1.visitVarInsn(Opcodes.ALOAD, 0);
        m1.visitVarInsn(Opcodes.ALOAD, 1);
        m1.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Queue", "offer", "(Ljava/lang/Object;)Z", true);
        m1.visitInsn(Opcodes.IRETURN);
        m1.visitMaxs(2, 2);
        m1.visitEnd();

        MethodVisitor m2 = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "offerLastDeque",
                "(Ljava/util/Deque;Ljava/lang/Object;)Z", null, null);
        m2.visitCode();
        m2.visitVarInsn(Opcodes.ALOAD, 0);
        m2.visitVarInsn(Opcodes.ALOAD, 1);
        m2.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Deque", "offerLast", "(Ljava/lang/Object;)Z", true);
        m2.visitInsn(Opcodes.IRETURN);
        m2.visitMaxs(2, 2);
        m2.visitEnd();

        MethodVisitor m3 = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "addCollection",
                "(Ljava/util/Collection;Ljava/lang/Object;)Z", null, null);
        m3.visitCode();
        m3.visitVarInsn(Opcodes.ALOAD, 0);
        m3.visitVarInsn(Opcodes.ALOAD, 1);
        m3.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Collection", "add", "(Ljava/lang/Object;)Z", true);
        m3.visitInsn(Opcodes.IRETURN);
        m3.visitMaxs(2, 2);
        m3.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateDirectSubmissionPublisherCaller(String clsName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, clsName, null, "java/lang/Object", null);
        emitDefaultCtor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "publish",
                "(Ljava/lang/Object;Ljava/lang/Object;)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/concurrent/SubmissionPublisher");
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/SubmissionPublisher",
                "submit", "(Ljava/lang/Object;)I", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateMsgProducerStub(String owner, String method, boolean isIface) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        int acc = Opcodes.ACC_PUBLIC | (isIface ? (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT) : 0);
        cw.visit(Opcodes.V1_8, acc, owner, null, "java/lang/Object", null);
        if (!isIface) {
            emitDefaultCtor(cw);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method, "(Ljava/lang/String;)V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 2);
            mv.visitEnd();
        } else {
            cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, method, "(Ljava/lang/String;)V", null, null)
                    .visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateMsgProducerImpl(String implName, String ifaceName, String method) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, implName, null, "java/lang/Object", new String[] {ifaceName});
        emitDefaultCtor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method, "(Ljava/lang/String;)V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateMsgProducerCaller(String callerName, String owner, String method, boolean isIface) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, callerName, null, "java/lang/Object", null);
        emitDefaultCtor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "produce",
                "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, owner);
        mv.visitLdcInsn("payload");
        mv.visitMethodInsn(isIface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL,
                owner, method, "(Ljava/lang/String;)V", isIface);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateAppReferencingClass(String appInternalName, String targetInternalName) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, appInternalName, null,
                "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "ref", "()Ljava/lang/Class;", null, null);
        mv.visitCode();
        mv.visitLdcInsn(org.objectweb.asm.Type.getObjectType(targetInternalName));
        mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateAppWithStringConstant(String appInternalName, String literal) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, appInternalName, null,
                "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "name", "()Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitLdcInsn(literal);
        mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test(timeout = 60000)
    public void helloRefreshFailureSendsFailClosedFallbackHelloAndTagsCovUnverifiedWhenLatched() throws Exception {
        byte[] shopBytes = new byte[] {80, 81, 82};
        File shop = new File(tmp.newFolder("hello-refresh-fail-classes"), "com/acme/Shop.class");
        shop.getParentFile().mkdirs();
        Files.write(shop.toPath(), shopBytes);
        System.setProperty(CLASS_DIRS_PROPERTY, shop.getParentFile().getParentFile().getParent());
        boolean[] probes = inProcessJacoco().probes(IndexReport.classId(shopBytes), "com/acme/Shop", 2);

        KeployDedupAgent.transformerAdded = true;
        KeployDedupAgent.executorCallSitesEnabled = true;
        KeployDedupAgent.observedNoServerEntry.set(false);
        KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
        ServerEntries.restoreCounted(Collections.<String>emptySet());
        ServerEntries.restoreUncounted(Collections.<String>emptySet());

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
            JsonObject initial = parseHello(in.readLine());
            assertTrue("initial HELLO starts honest", initial.get("asyncExecutors").getAsBoolean());
            send(out, "ACK_ID");

            // Latch asyncExecutors=false AND inject HELLO refresh failure on END!
            send(out, "START ts/refresh-fail");
            assertEquals("ACK ts/refresh-fail", in.readLine());
            Object tok = RequestGate.enter();
            try {
                probes[1] = true;
                KeployDedupAgent.noteObservedUntrackedMethodRef("java.util.concurrent.ThreadPoolExecutor::execute", true);
            } finally {
                RequestGate.exit(tok);
            }
            KeployDedupAgent.testHelloFailure = new RuntimeException("simulated mid-session HELLO refresh failure");
            try {
                send(out, "END ts/refresh-fail");
                // Must emit fail-closed all-false fallback HELLO preserving cached identity before COV!
                JsonObject fallback = parseHello(in.readLine());
                assertEquals("mid-session fallback HELLO must preserve cached jvmNonce",
                        KeployDedupAgent.jvmNonce(), fallback.get("jvmNonce").getAsString());
                assertFalse("fallback HELLO must set windowCut=false", fallback.get("windowCut").getAsBoolean());
                assertFalse("fallback HELLO must set asyncExecutors=false",
                        fallback.get("asyncExecutors").getAsBoolean());
                String cov = in.readLine();
                assertTrue("COV must carry unverified untracked-async backstop on HELLO refresh failure: " + cov,
                        cov.contains("\"unverified\":[\"untracked-async\"]"));
                assertEquals("ACK ts/refresh-fail", in.readLine());
            } finally {
                KeployDedupAgent.testHelloFailure = null;
            }
        }

        // Also test initial-connect HELLO failure (before any identity was cached) ->
        // exercises the static FAIL_CLOSED_FALLBACK_HELLO constant directly.
        KeployDedupAgent.stop();
        KeployDedupAgent.testHelloFailure = new RuntimeException("simulated initial-connect HELLO failure");
        try {
            assertTrue(KeployDedupAgent.start());
            try (Socket sdk2 = collector.accept()) {
                sdk2.setSoTimeout(30000);
                BufferedReader in2 = new BufferedReader(
                        new InputStreamReader(sdk2.getInputStream(), StandardCharsets.UTF_8));
                JsonObject initialFallback = parseHello(in2.readLine());
                assertEquals("initial fallback HELLO has empty jvmNonce when no identity was cached",
                        "", initialFallback.get("jvmNonce").getAsString());
                assertFalse("initial fallback HELLO must set windowCut=false",
                        initialFallback.get("windowCut").getAsBoolean());
                assertFalse("initial fallback HELLO must set asyncExecutors=false",
                        initialFallback.get("asyncExecutors").getAsBoolean());
            }
        } finally {
            KeployDedupAgent.testHelloFailure = null;
        }
    }

    @Test(timeout = 60000)
    public void observedNoServerEntryLatchesWindowCutFalseBeforeCovAndAcrossReconnect() throws Exception {
        byte[] shopBytes = new byte[] {41, 42, 43};
        File shop = new File(tmp.newFolder("armeria-uncounted-classes"), "com/acme/Shop.class");
        shop.getParentFile().mkdirs();
        Files.write(shop.toPath(), shopBytes);
        System.setProperty(CLASS_DIRS_PROPERTY, shop.getParentFile().getParentFile().getParent());
        boolean[] probes = inProcessJacoco().probes(IndexReport.classId(shopBytes), "com/acme/Shop", 2);

        // Simulate Armeria / Helidon / Play: -javaagent attached, endWait > 0, and server is NOT
        // in ServerEntries.UNCOUNTED so initial HELLO starts with windowCut=true, asyncExecutors=true.
        KeployDedupAgent.transformerAdded = true;
        KeployDedupAgent.executorCallSitesEnabled = true;
        KeployDedupAgent.observedNoServerEntry.set(false);
        ServerEntries.restoreCounted(Collections.<String>emptySet());
        ServerEntries.restoreUncounted(Collections.<String>emptySet());

        collector = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
        collector.setSoTimeout(30000);
        System.setProperty(ENDPOINT_PROPERTY, "127.0.0.1:" + collector.getLocalPort());
        System.setProperty(WARMUP_DISABLED_PROPERTY, "true");
        assertTrue(KeployDedupAgent.start());

        // Connection 1: initial HELLO has windowCut=true; first uncounted END refreshes HELLO
        // with windowCut=false BEFORE COV and emits "unverified":["no-server-entry"] without "contaminated".
        try (Socket sdk = collector.accept()) {
            sdk.setSoTimeout(30000);
            OutputStream out = sdk.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));
            JsonObject initial = parseHello(in.readLine());
            assertTrue("initial cold-start HELLO starts with windowCut=true",
                    initial.get("windowCut").getAsBoolean());
            assertTrue("initial cold-start HELLO starts with asyncExecutors=true",
                    initial.get("asyncExecutors").getAsBoolean());

            send(out, "ACK_ID");
            send(out, "START ts/armeria-1");
            assertEquals("ACK ts/armeria-1", in.readLine());
            // Uncounted server executes app code without calling RequestGate.enter().
            probes[1] = true;
            send(out, "END ts/armeria-1");

            String beforeCov = in.readLine();
            JsonObject refreshed = parseHello(beforeCov);
            assertFalse("END seeing noServerEntry must refresh HELLO windowCut=false BEFORE COV",
                    refreshed.get("windowCut").getAsBoolean());
            assertFalse("END seeing noServerEntry must refresh HELLO asyncExecutors=false BEFORE COV",
                    refreshed.get("asyncExecutors").getAsBoolean());

            String cov = in.readLine();
            assertEquals("COV {\"id\":\"ts/armeria-1\",\"executedLinesByFile\":{\"com/acme/Shop\":[1]},"
                    + "\"unverified\":[\"no-server-entry\"]}", cov);
            assertFalse("unverified no-server-entry must NOT emit contaminated field",
                    cov.contains("\"contaminated\""));
            assertEquals("ACK ts/armeria-1", in.readLine());
        }

        // Connection 2 (after reconnect): observedNoServerEntry stays permanently latched so the
        // new connection's initial HELLO immediately declares windowCut=false!
        try (Socket sdk2 = collector.accept()) {
            sdk2.setSoTimeout(30000);
            BufferedReader in2 = new BufferedReader(
                    new InputStreamReader(sdk2.getInputStream(), StandardCharsets.UTF_8));
            JsonObject reconnected = parseHello(in2.readLine());
            assertFalse("windowCut=false must stay permanently latched after reconnect",
                    reconnected.get("windowCut").getAsBoolean());
            assertFalse("asyncExecutors=false must stay permanently latched after reconnect",
                    reconnected.get("asyncExecutors").getAsBoolean());
        }
    }

    @Test(timeout = 60000)
    public void lateStartingExpiredLingeringTaskSuppressesBackgroundViaRequestGateOpenOnTcp()
            throws Exception {
        byte[] shopBytes = new byte[] {51, 52, 53};
        File shop = new File(tmp.newFolder("open-tail-tcp-bg-classes"), "com/acme/Shop.class");
        shop.getParentFile().mkdirs();
        Files.write(shop.toPath(), shopBytes);
        System.setProperty(CLASS_DIRS_PROPERTY, shop.getParentFile().getParentFile().getParent());
        System.setProperty(WARMUP_DISABLED_PROPERTY, "true");
        System.setProperty(END_WAIT_PROPERTY, "20");
        boolean[] probes = inProcessJacoco().probes(IndexReport.classId(shopBytes), "com/acme/Shop", 4);

        long savedMaxLingering = RequestGate.maxLingeringNanos;
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        try {
            RequestGate.maxLingeringNanos = TimeUnit.MILLISECONDS.toNanos(40);

            // TCP transport: older lingering window L expires past maxLingeringNanos with a queued
            // task not yet running at END N (so hasActiveLingering == false -> lastEndedMayEmitTail == false).
            // The task starts in the N->N+1 gap (Hand.enter retains L again and hits probe[2]);
            // START N+1 adds tail:L and MUST suppress BACKGROUND(N->N+1) via RequestGate.open()
            // alone, incrementing lost to 1!
            collector = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
            collector.setSoTimeout(30000);
            System.setProperty(ENDPOINT_PROPERTY, "127.0.0.1:" + collector.getLocalPort());
            assertTrue(KeployDedupAgent.start());
            try (Socket sdk = collector.accept()) {
                sdk.setSoTimeout(30000);
                OutputStream out = sdk.getOutputStream();
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));
                assertNotNull(parseHello(in.readLine()));
                send(out, "ACK_ID");
                send(out, "BACKGROUND");

                // Window L: creates a queued task (handed, not yet started) and cuts at END L.
                send(out, "START ts/open-tail-L");
                assertEquals("ACK ts/open-tail-L", in.readLine());
                CountDownLatch taskStarted = new CountDownLatch(1);
                CountDownLatch allowTaskFinish = new CountDownLatch(1);
                Runnable handed;
                Object reqL = RequestGate.enter();
                try {
                    probes[0] = true;
                    handed = RequestGate.hand(pool, () -> {
                        probes[2] = true;
                        taskStarted.countDown();
                        try {
                            allowTaskFinish.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                } finally {
                    RequestGate.exit(reqL);
                }
                send(out, "END ts/open-tail-L");
                String covL = in.readLine();
                assertTrue("L must be cut while queued task is pending: " + covL,
                        covL.contains("\"windowCut\":true"));
                assertEquals("ACK ts/open-tail-L", in.readLine());

                // Wait strictly past maxLingeringNanos so idle window L expires.
                Thread.sleep(70);

                // Window N: consumes L's one-time cut report and evicts expired idle L from LINGERING;
                // END N drains cleanly with hasActiveLingering == false (lastEndedMayEmitTail == false).
                send(out, "START ts/open-tail-N");
                assertEquals("ACK ts/open-tail-N", in.readLine());
                Object reqN = RequestGate.enter();
                try {
                    probes[0] = true;
                } finally {
                    RequestGate.exit(reqN);
                }
                send(out, "END ts/open-tail-N");
                String covN = in.readLine();
                assertTrue("N reports one-time cut tail from L: " + covN, covN.contains("\"tail:ts/open-tail-L\""));
                assertFalse("N itself is not cut: " + covN, covN.contains("\"windowCut\":true"));
                assertEquals("ACK ts/open-tail-N", in.readLine());

                // Gap N -> N+1: queued task from L now starts! Hand.enter() re-retains L with executing=1
                // and records L's probe[2] in the gap.
                Future<?> handedFuture = pool.submit(handed);
                assertTrue("queued task from L started in N->N+1 gap", taskStarted.await(5, TimeUnit.SECONDS));

                // START N+1: ONLY RequestGate.open()'s return value (next.hasOpeningTail()) can suppress
                // BACKGROUND(N->N+1) here because prevMayEmitTail == false and JobGate.open() == false!
                send(out, "START ts/open-tail-N1");
                assertEquals("ACK ts/open-tail-N1", in.readLine());
                send(out, "NOP check-open-tail-no-bg");
                assertEquals("RequestGate.open open-time tail MUST suppress TCP BACKGROUND(N->N+1)",
                        "ACK check-open-tail-no-bg", in.readLine());

                allowTaskFinish.countDown();
                // Wait on Future.get() so Hand.exit() (executing=0, running=0) happens-before END N+1.
                handedFuture.get(5, TimeUnit.SECONDS);

                Object reqN1 = RequestGate.enter();
                try {
                    probes[1] = true;
                } finally {
                    RequestGate.exit(reqN1);
                }
                send(out, "END ts/open-tail-N1");
                String covN1 = in.readLine();
                assertTrue("N+1 carries tail:ts/open-tail-L: " + covN1, covN1.contains("\"tail:ts/open-tail-L\""));
                assertEquals("ACK ts/open-tail-N1", in.readLine());

                // Clean gap N+1 -> N+2 emits BACKGROUND carrying lost:1 from suppressed N->N+1!
                probes[3] = true;
                send(out, "START ts/open-tail-N2");
                assertEquals("ACK ts/open-tail-N2", in.readLine());
                String bgN1N2 = in.readLine();
                assertTrue("clean TCP gap after N+1 must emit BACKGROUND with lost:1, got: " + bgN1N2,
                        bgN1N2 != null && bgN1N2.startsWith("BACKGROUND ")
                                && bgN1N2.contains("\"after\":\"ts/open-tail-N1\"")
                                && bgN1N2.contains("\"lost\":1"));
            } finally {
                KeployDedupAgent.stop();
                collector.close();
                collector = null;
            }
        } finally {
            RequestGate.maxLingeringNanos = savedMaxLingering;
            pool.shutdownNow();
        }
    }

    @Test(timeout = 60000)
    public void lateStartingExpiredLingeringTaskSuppressesBackgroundViaRequestGateOpenOnUnix()
            throws Exception {
        byte[] shopBytes = new byte[] {54, 55, 56};
        File shop = new File(tmp.newFolder("open-tail-unix-bg-classes"), "com/acme/Shop.class");
        shop.getParentFile().mkdirs();
        Files.write(shop.toPath(), shopBytes);
        System.setProperty(CLASS_DIRS_PROPERTY, shop.getParentFile().getParentFile().getParent());
        System.setProperty(WARMUP_DISABLED_PROPERTY, "true");
        System.setProperty(END_WAIT_PROPERTY, "20");
        boolean[] probes = inProcessJacoco().probes(IndexReport.classId(shopBytes), "com/acme/Shop", 4);

        long savedMaxLingering = RequestGate.maxLingeringNanos;
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        try {
            RequestGate.maxLingeringNanos = TimeUnit.MILLISECONDS.toNanos(40);
            System.clearProperty(ENDPOINT_PROPERTY);
            File sockDir = tmp.newFolder("unix-open-tail-bg");
            KeployDedupAgent.controlSocketPath = new File(sockDir, "ctrl.sock").getAbsolutePath();
            KeployDedupAgent.dataSocketPath = new File(sockDir, "data.sock").getAbsolutePath();
            java.util.concurrent.BlockingQueue<String> records = new java.util.concurrent.LinkedBlockingQueue<>();
            org.newsclub.net.unix.AFUNIXServerSocket dataServer =
                    org.newsclub.net.unix.AFUNIXServerSocket.newInstance();
            dataServer.bind(org.newsclub.net.unix.AFUNIXSocketAddress.of(new File(KeployDedupAgent.dataSocketPath)));
            Thread dataReader = new Thread(() -> {
                while (!dataServer.isClosed()) {
                    try (Socket conn = dataServer.accept();
                         BufferedReader r = new BufferedReader(
                                 new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String l;
                        while ((l = r.readLine()) != null) {
                            sb.append(l);
                        }
                        if (sb.length() > 0) {
                            records.add(sb.toString());
                        }
                    } catch (IOException ignored) {
                    }
                }
            }, "unix-open-tail-data-reader");
            dataReader.setDaemon(true);
            dataReader.start();
            try {
                assertTrue(KeployDedupAgent.start());
                assertTrue(sendUnixControl("BACKGROUND").startsWith("ACK proto=3"));
                assertTrue(sendUnixControl("START ts/u-open-tail-L").startsWith("ACK proto=3"));
                CountDownLatch uTaskStarted = new CountDownLatch(1);
                CountDownLatch uAllowFinish = new CountDownLatch(1);
                Runnable uHanded;
                Object reqUL = RequestGate.enter();
                try {
                    probes[0] = true;
                    uHanded = RequestGate.hand(pool, () -> {
                        probes[2] = true;
                        uTaskStarted.countDown();
                        try {
                            uAllowFinish.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                } finally {
                    RequestGate.exit(reqUL);
                }
                assertTrue(sendUnixControl("END ts/u-open-tail-L").startsWith("ACK proto=3"));
                Thread.sleep(70);

                assertTrue(sendUnixControl("START ts/u-open-tail-N").startsWith("ACK proto=3"));
                Object reqUN = RequestGate.enter();
                try {
                    probes[0] = true;
                } finally {
                    RequestGate.exit(reqUN);
                }
                assertTrue(sendUnixControl("END ts/u-open-tail-N").startsWith("ACK proto=3"));

                Future<?> uHandedFuture = pool.submit(uHanded);
                assertTrue("Unix queued task from L started in N->N+1 gap", uTaskStarted.await(5, TimeUnit.SECONDS));

                assertTrue(sendUnixControl("START ts/u-open-tail-N1").startsWith("ACK proto=3"));
                uAllowFinish.countDown();
                uHandedFuture.get(5, TimeUnit.SECONDS);

                Object reqUN1 = RequestGate.enter();
                try {
                    probes[1] = true;
                } finally {
                    RequestGate.exit(reqUN1);
                }
                assertTrue(sendUnixControl("END ts/u-open-tail-N1").startsWith("ACK proto=3"));

                probes[3] = true;
                assertTrue(sendUnixControl("START ts/u-open-tail-N2").startsWith("ACK proto=3"));

                String recN1 = null;
                String bgN1N2 = null;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline && (recN1 == null || bgN1N2 == null)) {
                    String rec = records.poll(200, TimeUnit.MILLISECONDS);
                    if (rec == null) {
                        continue;
                    }
                    assertFalse("RequestGate.open open-time tail MUST suppress Unix BACKGROUND(u-open-tail-N->u-open-tail-N1): " + rec,
                            rec.contains("\"kind\":\"background\"") && rec.contains("\"after\":\"ts/u-open-tail-N\""));
                    if (rec.contains("\"id\":\"ts/u-open-tail-N1\"")) {
                        recN1 = rec;
                    }
                    if (rec.contains("\"kind\":\"background\"") && rec.contains("\"after\":\"ts/u-open-tail-N1\"")) {
                        bgN1N2 = rec;
                    }
                }
                assertNotNull("expected Unix record for ts/u-open-tail-N1", recN1);
                assertTrue("Unix ts/u-open-tail-N1 carries tail:ts/u-open-tail-L: " + recN1,
                        recN1.contains("\"tail:ts/u-open-tail-L\""));
                assertNotNull("expected Unix BACKGROUND for clean gap u-open-tail-N1->u-open-tail-N2", bgN1N2);
                assertTrue("Unix BACKGROUND u-open-tail-N1->u-open-tail-N2 must carry lost:1: " + bgN1N2,
                        bgN1N2.contains("\"lost\":1"));
            } finally {
                KeployDedupAgent.stop();
                dataServer.close();
            }
        } finally {
            RequestGate.maxLingeringNanos = savedMaxLingering;
            pool.shutdownNow();
        }
    }

    private static String sendUnixControl(String command) throws IOException {
        try (org.newsclub.net.unix.AFUNIXSocket s = org.newsclub.net.unix.AFUNIXSocket.newInstance()) {
            s.connect(org.newsclub.net.unix.AFUNIXSocketAddress.of(new File(KeployDedupAgent.controlSocketPath)));
            s.setSoTimeout(10000);
            OutputStream out = s.getOutputStream();
            out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            return r.readLine();
        }
    }

    private static byte[] generateInlineExecutorClass(String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object",
                new String[] {"java/util/concurrent/Executor"});
        emitDefaultCtor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "execute", "(Ljava/lang/Runnable;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateInlineExecutorServiceClass(String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null,
                "java/util/concurrent/AbstractExecutorService", null);
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/concurrent/AbstractExecutorService",
                "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        MethodVisitor exec = cw.visitMethod(Opcodes.ACC_PUBLIC, "execute", "(Ljava/lang/Runnable;)V", null, null);
        exec.visitCode();
        exec.visitVarInsn(Opcodes.ALOAD, 1);
        exec.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true);
        exec.visitInsn(Opcodes.RETURN);
        exec.visitMaxs(1, 2);
        exec.visitEnd();

        MethodVisitor sh = cw.visitMethod(Opcodes.ACC_PUBLIC, "shutdown", "()V", null, null);
        sh.visitCode();
        sh.visitInsn(Opcodes.RETURN);
        sh.visitMaxs(0, 1);
        sh.visitEnd();

        MethodVisitor shNow = cw.visitMethod(Opcodes.ACC_PUBLIC, "shutdownNow", "()Ljava/util/List;", null, null);
        shNow.visitCode();
        shNow.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false);
        shNow.visitInsn(Opcodes.ARETURN);
        shNow.visitMaxs(1, 1);
        shNow.visitEnd();

        for (String boolMethod : new String[] {"isShutdown", "isTerminated"}) {
            MethodVisitor bm = cw.visitMethod(Opcodes.ACC_PUBLIC, boolMethod, "()Z", null, null);
            bm.visitCode();
            bm.visitInsn(Opcodes.ICONST_0);
            bm.visitInsn(Opcodes.IRETURN);
            bm.visitMaxs(1, 1);
            bm.visitEnd();
        }

        MethodVisitor aw = cw.visitMethod(Opcodes.ACC_PUBLIC, "awaitTermination",
                "(JLjava/util/concurrent/TimeUnit;)Z", null, null);
        aw.visitCode();
        aw.visitInsn(Opcodes.ICONST_1);
        aw.visitInsn(Opcodes.IRETURN);
        aw.visitMaxs(1, 4);
        aw.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateTxHelperAndTimerCancelCaller(String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        emitDefaultCtor(cw);

        String txOwner = PlainSyncTxHelper.class.getName().replace('.', '/');
        MethodVisitor m1 = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "callSyncHelper",
                "(L" + txOwner + ";Ljava/lang/Runnable;Ljava/lang/Runnable;)V", null, null);
        m1.visitCode();
        m1.visitVarInsn(Opcodes.ALOAD, 0);
        m1.visitVarInsn(Opcodes.ALOAD, 1);
        m1.visitMethodInsn(Opcodes.INVOKEVIRTUAL, txOwner, "execute", "(Ljava/lang/Runnable;)V", false);
        m1.visitVarInsn(Opcodes.ALOAD, 0);
        m1.visitVarInsn(Opcodes.ALOAD, 2);
        m1.visitMethodInsn(Opcodes.INVOKEVIRTUAL, txOwner, "submit",
                "(Ljava/lang/Runnable;)Ljava/lang/Object;", false);
        m1.visitInsn(Opcodes.POP);
        m1.visitVarInsn(Opcodes.ALOAD, 0);
        m1.visitVarInsn(Opcodes.ALOAD, 2);
        m1.visitLdcInsn("res");
        m1.visitMethodInsn(Opcodes.INVOKEVIRTUAL, txOwner, "submit",
                "(Ljava/lang/Runnable;Ljava/lang/Object;)Ljava/lang/Object;", false);
        m1.visitInsn(Opcodes.POP);
        m1.visitInsn(Opcodes.RETURN);
        m1.visitMaxs(0, 0);
        m1.visitEnd();

        String subOwner = CustomWorkerThread.class.getName().replace('.', '/');
        MethodVisitor m2 = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "startCustomThread",
                "(L" + subOwner + ";)V", null, null);
        m2.visitCode();
        m2.visitVarInsn(Opcodes.ALOAD, 0);
        m2.visitMethodInsn(Opcodes.INVOKEVIRTUAL, subOwner, "start", "()V", false);
        m2.visitInsn(Opcodes.RETURN);
        m2.visitMaxs(0, 0);
        m2.visitEnd();

        // Schedule with 5s initial delay and cancel immediately (before first tick)
        MethodVisitor m3 = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "scheduleAndCancelPeriodic",
                "(Ljava/util/Timer;Ljava/util/TimerTask;)V", null, null);
        m3.visitCode();
        m3.visitVarInsn(Opcodes.ALOAD, 0);
        m3.visitVarInsn(Opcodes.ALOAD, 1);
        m3.visitLdcInsn(5000L);
        m3.visitLdcInsn(5000L);
        m3.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Timer",
                "scheduleAtFixedRate", "(Ljava/util/TimerTask;JJ)V", false);
        m3.visitVarInsn(Opcodes.ALOAD, 1);
        m3.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/TimerTask", "cancel", "()Z", false);
        m3.visitInsn(Opcodes.POP);
        m3.visitInsn(Opcodes.RETURN);
        m3.visitMaxs(0, 0);
        m3.visitEnd();

        MethodVisitor m4 = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "scheduleAndCancelOneShot",
                "(Ljava/util/Timer;Ljava/util/TimerTask;)V", null, null);
        m4.visitCode();
        m4.visitVarInsn(Opcodes.ALOAD, 0);
        m4.visitVarInsn(Opcodes.ALOAD, 1);
        m4.visitLdcInsn(30000L);
        m4.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Timer",
                "schedule", "(Ljava/util/TimerTask;J)V", false);
        m4.visitVarInsn(Opcodes.ALOAD, 1);
        m4.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/TimerTask", "cancel", "()Z", false);
        m4.visitInsn(Opcodes.POP);
        m4.visitInsn(Opcodes.RETURN);
        m4.visitMaxs(0, 0);
        m4.visitEnd();

        MethodVisitor m5 = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "scheduleOneShotDelay",
                "(Ljava/util/Timer;Ljava/util/TimerTask;J)V", null, null);
        m5.visitCode();
        m5.visitVarInsn(Opcodes.ALOAD, 0);
        m5.visitVarInsn(Opcodes.ALOAD, 1);
        m5.visitVarInsn(Opcodes.LLOAD, 2);
        m5.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Timer",
                "schedule", "(Ljava/util/TimerTask;J)V", false);
        m5.visitInsn(Opcodes.RETURN);
        m5.visitMaxs(0, 0);
        m5.visitEnd();

        MethodVisitor m6 = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "schedulePeriodicDelay",
                "(Ljava/util/Timer;Ljava/util/TimerTask;JJ)V", null, null);
        m6.visitCode();
        m6.visitVarInsn(Opcodes.ALOAD, 0);
        m6.visitVarInsn(Opcodes.ALOAD, 1);
        m6.visitVarInsn(Opcodes.LLOAD, 2);
        m6.visitVarInsn(Opcodes.LLOAD, 4);
        m6.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Timer",
                "scheduleAtFixedRate", "(Ljava/util/TimerTask;JJ)V", false);
        m6.visitInsn(Opcodes.RETURN);
        m6.visitMaxs(0, 0);
        m6.visitEnd();

        MethodVisitor m7 = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "cancelTask",
                "(Ljava/util/TimerTask;)Z", null, null);
        m7.visitCode();
        m7.visitVarInsn(Opcodes.ALOAD, 0);
        m7.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/TimerTask", "cancel", "()Z", false);
        m7.visitInsn(Opcodes.IRETURN);
        m7.visitMaxs(0, 0);
        m7.visitEnd();

        MethodVisitor m7b = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "cancelTimer",
                "(Ljava/util/Timer;)V", null, null);
        m7b.visitCode();
        m7b.visitVarInsn(Opcodes.ALOAD, 0);
        m7b.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Timer", "cancel", "()V", false);
        m7b.visitInsn(Opcodes.RETURN);
        m7b.visitMaxs(0, 0);
        m7b.visitEnd();

        String listenableIface = ListenableLikeExecutorService.class.getName().replace('.', '/');
        String subFutureIface = SubtypeFuture.class.getName().replace('.', '/');
        MethodVisitor m8 = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "callTwoArgSubmit",
                "(L" + listenableIface + ";Ljava/lang/Runnable;Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        m8.visitCode();
        m8.visitVarInsn(Opcodes.ALOAD, 0);
        m8.visitVarInsn(Opcodes.ALOAD, 1);
        m8.visitVarInsn(Opcodes.ALOAD, 2);
        m8.visitMethodInsn(Opcodes.INVOKEINTERFACE, listenableIface, "submit",
                "(Ljava/lang/Runnable;Ljava/lang/Object;)L" + subFutureIface + ";", true);
        m8.visitInsn(Opcodes.ARETURN);
        m8.visitMaxs(0, 0);
        m8.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateTimerAndBranchyClass(String className) {
        String spec = System.getProperty("java.specification.version", "1.8");
        int classVersion = (!spec.startsWith("1.") && Integer.parseInt(spec) >= 17) ? Opcodes.V17 : Opcodes.V1_8;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(classVersion, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        emitDefaultCtor(cw);

        // scheduleOneShot(Timer, TimerTask, long) -> void
        MethodVisitor m1 = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "scheduleOneShot",
                "(Ljava/util/Timer;Ljava/util/TimerTask;J)V", null, null);
        m1.visitCode();
        m1.visitVarInsn(Opcodes.ALOAD, 0);
        m1.visitVarInsn(Opcodes.ALOAD, 1);
        m1.visitVarInsn(Opcodes.LLOAD, 2);
        m1.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Timer", "schedule", "(Ljava/util/TimerTask;J)V", false);
        m1.visitInsn(Opcodes.RETURN);
        m1.visitMaxs(0, 0);
        m1.visitEnd();

        // schedulePeriodic(Timer, long delay, long period) -> creates unreferenced TimerTask so only Timer holds it
        MethodVisitor m2 = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "schedulePeriodic",
                "(Ljava/util/Timer;JJ)V", null, null);
        m2.visitCode();
        m2.visitVarInsn(Opcodes.ALOAD, 0);
        String noopTask = NoopTimerTask.class.getName().replace('.', '/');
        m2.visitTypeInsn(Opcodes.NEW, noopTask);
        m2.visitInsn(Opcodes.DUP);
        m2.visitMethodInsn(Opcodes.INVOKESPECIAL, noopTask, "<init>", "()V", false);
        m2.visitVarInsn(Opcodes.LLOAD, 1);
        m2.visitVarInsn(Opcodes.LLOAD, 3);
        m2.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Timer",
                "scheduleAtFixedRate", "(Ljava/util/TimerTask;JJ)V", false);
        m2.visitInsn(Opcodes.RETURN);
        m2.visitMaxs(0, 0);
        m2.visitEnd();

        // branchySchedule(TaskScheduler, Runnable, Date, boolean) with if/else + try/catch + StackMapTable frames
        MethodVisitor m3 = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "branchySchedule",
                "(Lorg/springframework/scheduling/TaskScheduler;Ljava/lang/Runnable;Ljava/util/Date;Z)"
                        + "Ljava/util/concurrent/ScheduledFuture;", null, null);
        m3.visitCode();
        org.objectweb.asm.Label tryStart = new org.objectweb.asm.Label();
        org.objectweb.asm.Label tryEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label catchBlock = new org.objectweb.asm.Label();
        org.objectweb.asm.Label elseBranch = new org.objectweb.asm.Label();
        m3.visitTryCatchBlock(tryStart, tryEnd, catchBlock, "java/lang/RuntimeException");
        m3.visitLabel(tryStart);
        m3.visitVarInsn(Opcodes.ILOAD, 3);
        m3.visitJumpInsn(Opcodes.IFEQ, elseBranch);
        m3.visitVarInsn(Opcodes.ALOAD, 0);
        m3.visitVarInsn(Opcodes.ALOAD, 1);
        m3.visitVarInsn(Opcodes.ALOAD, 2);
        m3.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/springframework/scheduling/TaskScheduler",
                "schedule", "(Ljava/lang/Runnable;Ljava/util/Date;)Ljava/util/concurrent/ScheduledFuture;", true);
        m3.visitLabel(tryEnd);
        m3.visitInsn(Opcodes.ARETURN);
        m3.visitLabel(elseBranch);
        m3.visitInsn(Opcodes.ACONST_NULL);
        m3.visitInsn(Opcodes.ARETURN);
        m3.visitLabel(catchBlock);
        m3.visitInsn(Opcodes.POP);
        m3.visitInsn(Opcodes.ACONST_NULL);
        m3.visitInsn(Opcodes.ARETURN);
        m3.visitMaxs(0, 0);
        m3.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    public static final class NoopTimerTask extends java.util.TimerTask {
        @Override
        public void run() {
        }
    }

    @Test(timeout = 60000)
    public void concurrentRequestExecutorAndJobGateContentionStaysBoundedAndLeakFree() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        try {
            RequestGate.open("contention", null);
            JobGate.open();
            int threads = 8;
            int iterations = 1000;
            CountDownLatch start = new CountDownLatch(1);
            Thread[] workers = new Thread[threads];
            AtomicReference<Throwable> failure = new AtomicReference<>();
            for (int t = 0; t < threads; t++) {
                final KafkaContainerStub stub = new KafkaContainerStub(new String[] {"t-" + t}, new OrdersBean());
                workers[t] = new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            Object req = RequestGate.enter();
                            try {
                                Future<?> f = RequestGate.submit(pool, () -> { });
                                f.get(10, TimeUnit.SECONDS);
                            } finally {
                                RequestGate.exit(req);
                            }
                            Object jt = JobGate.enter(stub, "kafka");
                            JobGate.exit(jt);
                        }
                    } catch (Throwable err) {
                        failure.compareAndSet(null, err);
                    }
                });
                workers[t].start();
            }
            long t0 = System.nanoTime();
            start.countDown();
            for (Thread w : workers) {
                w.join(30000);
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertNull("no concurrent failure: " + failure.get(), failure.get());
            assertTrue("window drains cleanly", RequestGate.await(TimeUnit.SECONDS.toNanos(10)));
            assertEquals("zero leaked PENDING_BY_FUTURE entries", 0, RequestGate.pendingByFutureSize());
            assertEquals("zero leaked PENDING_BY_EXECUTOR entries", 0, RequestGate.pendingByExecutorSize(pool));
            assertEquals("zero leaked JobGate runs", 0, JobGate.activeRunCount());
            assertEquals("all 8 listeners attributed", 8, JobGate.close().size());
            assertTrue("contention benchmark finished promptly: " + elapsedMs + " ms", elapsedMs < 30_000);
        } finally {
            pool.shutdownNow();
        }
    }

    private static String sendUnix(String socketPath, String command) throws IOException {
        try (AFUNIXSocket socket = AFUNIXSocket.newInstance()) {
            socket.connect(AFUNIXSocketAddress.of(new File(socketPath)), 3000);
            socket.setSoTimeout(30000);
            OutputStream out = socket.getOutputStream();
            out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            return line == null ? "" : line + "\n";
        }
    }

    private static final class SelfReferencingJob implements Runnable {
        Future<?> future;

        @Override
        public void run() {
            // Self-referencing cancellable job pattern.
        }
    }

    public static final class OrdersBean { }
    public static final class BillingBean { }
    public static final class AuditBean { }

    public static final class AdapterStub {
        private final Object bean;
        AdapterStub(Object bean) { this.bean = bean; }
        public Object getBean() { return bean; }
    }

    public static final class KafkaPropsStub {
        private final String[] topics;
        private final Object listener;
        KafkaPropsStub(String[] topics, Object listener) { this.topics = topics; this.listener = listener; }
        public String[] getTopics() { return topics; }
        public Object getMessageListener() { return listener; }
    }

    public static final class KafkaContainerStub {
        private final KafkaPropsStub props;
        KafkaContainerStub(String[] topics, Object listener) { this.props = new KafkaPropsStub(topics, listener); }
        public KafkaPropsStub getContainerProperties() { return props; }
    }

    public static final class AmqpContainerStub {
        private final String[] queues;
        private final Object listener;
        AmqpContainerStub(String[] queues, Object listener) { this.queues = queues; this.listener = listener; }
        public String[] getQueueNames() { return queues; }
        public Object getMessageListener() { return listener; }
    }

    public static final class JmsContainerStub {
        private final String dest;
        private final Object listener;
        JmsContainerStub(String dest, Object listener) { this.dest = dest; this.listener = listener; }
        public String getDestinationName() { return dest; }
        public Object getMessageListener() { return listener; }
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

    private static final ScheduledThreadPoolExecutor STANDIN_STPE = new ScheduledThreadPoolExecutor(1);

    public static ScheduledThreadPoolExecutor standInScheduledPool() {
        return STANDIN_STPE;
    }

    private static byte[] generateSpringSubmitStandIn(String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        emitDefaultCtor(cw);
        MethodVisitor pool = cw.visitMethod(Opcodes.ACC_PUBLIC, "getScheduledThreadPoolExecutor",
                "()Ljava/util/concurrent/ScheduledThreadPoolExecutor;", null, null);
        pool.visitCode();
        pool.visitMethodInsn(Opcodes.INVOKESTATIC,
                HonestProtocolAndAsyncTest.class.getName().replace('.', '/'),
                "standInScheduledPool", "()Ljava/util/concurrent/ScheduledThreadPoolExecutor;", false);
        pool.visitInsn(Opcodes.ARETURN);
        pool.visitMaxs(1, 1);
        pool.visitEnd();
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
