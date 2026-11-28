package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.example.servers.AsyncHonestyApp;
import org.example.servers.AsyncTail;
import org.example.servers.Tail;
import org.jacoco.agent.AgentJar;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * End-to-end forked {@code -javaagent} JVM tests verifying v3 protocol honesty across both
 * embedded-JaCoCo index mode AND external-JaCoCo {@code appPackagePrefix} k8s
 * mode, plain JDK executors, {@code CompletableFuture} dependent stages,
 * fail-closed unknown executors, {@code KEPLOY_JAVA_ASYNC_CALLSITES=off},
 * whole-jar fallback, Jetty cold-start, uncounted server
 * {@code no-server-entry}, and Kafka listener attribution in TCP {@code COV}.
 */
public class ForkedAsyncAndHonestyTest {

    private static final String ASYNC_TAIL = AsyncTail.class.getName().replace('.', '/');

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test(timeout = 120000)
    public void forkedIndexModePlainExecutorTailCausesWindowCutAndTailContaminationUntilDrained() throws Exception {
        try (ForkedApp app = startFork("tomcat", "80", false, false, null, null)) {
            JsonObject hello = parseHello(app.initialHello);
            assertEquals(3, hello.get("proto").getAsInt());
            assertTrue("windowCut true", hello.get("windowCut").getAsBoolean());
            assertTrue("asyncExecutors true in index mode", hello.get("asyncExecutors").getAsBoolean());
            assertEquals("pod-uid-1", hello.get("podUid").getAsString());
            assertEquals("pod-name-1", hello.get("podName").getAsString());
            assertEquals("session-1", hello.get("sessionId").getAsString());
            assertFalse("jvmNonce non-empty", hello.get("jvmNonce").getAsString().isEmpty());
            assertFalse("buildId non-empty", hello.get("buildId").getAsString().isEmpty());

            // Test 1: submits a 450ms task to plain Executors.newFixedThreadPool with endWait=80ms -> windowCut:true!
            String cov1 = app.runHttpTest("ts/t1", "/submit-tail?ms=450");
            assertTrue("Test 1 must be windowCut: " + cov1, cov1.contains("\"windowCut\":true"));

            // Test 2 starts IMMEDIATELY while Test 1's 450ms task is STILL executing -> contaminated: ["tail:ts/t1"]!
            String cov2 = app.runHttpTest("ts/t2", "/clean");
            assertTrue("Test 2 must carry tail contamination from cut Test 1: " + cov2,
                    cov2.contains("\"contaminated\":[\"tail:ts/t1\"]"));

            // Wait for Test 1's 450ms task to drain completely -> Test 3 must be clean!
            Thread.sleep(600);
            String cov3 = app.runHttpTest("ts/t3", "/clean");
            assertFalse("Test 3 after drain must not be windowCut: " + cov3, cov3.contains("windowCut"));
            assertFalse("Test 3 after drain must not be contaminated: " + cov3, cov3.contains("contaminated"));
        }
    }

    @Test(timeout = 120000)
    public void forkedExternalJacocoPrefixModeTracksCompletableFutureDependentsAndChainedAsync() throws Exception {
        // Exact k8s mode: -javaagent:jacocoagent.jar FIRST, -javaagent:keploy-sdk.jar SECOND,
        // NO KEPLOY_JAVA_CLASS_DIRS -> premain derives appPackagePrefix ("org/example/servers/")!
        try (ForkedApp app = startFork("tomcat", "80", true, false, null, null)) {
            JsonObject hello = parseHello(app.initialHello);
            assertTrue("k8s external-JaCoCo prefix mode must declare windowCut=true",
                    hello.get("windowCut").getAsBoolean());
            assertTrue("k8s external-JaCoCo prefix mode must declare asyncExecutors=true",
                    hello.get("asyncExecutors").getAsBoolean());

            // 1. Fast supplier with slow synchronous dependent: supplyAsync(20ms, pool).thenAccept(350ms audit)
            //    with endWait=80ms -> MUST report windowCut:true while audit runs!
            String cov1 = app.runHttpTest("ts/cf-dep", "/cf-then-accept?ms=350");
            assertTrue("CF dependent thenAccept(audit) must keep window open and trigger windowCut: " + cov1,
                    cov1.contains("\"windowCut\":true"));

            // Wait for audit to finish, then verify tail contamination was observed and drains.
            Thread.sleep(500);
            String covDrain = app.runHttpTest("ts/drain-after-cf", "/clean");
            assertTrue("window after cut CF dependent must report tail:ts/cf-dep: " + covDrain,
                    covDrain.contains("\"contaminated\":[\"tail:ts/cf-dep\"]"));

            // 2. Chained thenApplyAsync(350ms, pool) with endWait=80ms -> MUST report windowCut:true!
            String cov2 = app.runHttpTest("ts/cf-chain", "/cf-then-apply-async?ms=350");
            assertTrue("CF thenApplyAsync must keep window open and trigger windowCut: " + cov2,
                    cov2.contains("\"windowCut\":true"));
            Thread.sleep(500);
        }

        // Also verify with long endWait (2000ms) that the dependent audit's AsyncTail probe lands INSIDE Test 1's COV!
        try (ForkedApp app = startFork("tomcat", "2000", true, false, null, null)) {
            String cov = app.runHttpTest("ts/cf-dep-captured", "/cf-then-accept?ms=250");
            assertFalse("sufficient wait must not cut: " + cov, cov.contains("windowCut"));
            assertTrue("dependent audit's AsyncTail probe must land in COV: " + cov,
                    cov.contains("\"" + ASYNC_TAIL + "\""));
        }
    }

    @Test(timeout = 120000)
    public void forkedUnknownSerializableExecutorIsUnwrappedAndContaminatesCurrentAndNextWindow() throws Exception {
        try (ForkedApp app = startFork("tomcat", "2000", false, false, null, null)) {
            // 1. Synchronous SerializableOnlyExecutor throws IllegalStateException if task is wrapped.
            String cov = app.runHttpTest("ts/unknown-exec", "/unknown-serializable");
            assertFalse("must not fail or cut: " + cov, cov.contains("windowCut"));
            assertTrue("unknown executor must contaminate COV with untracked-executor: " + cov,
                    cov.contains("\"contaminated\":[\"untracked-executor:"
                            + AsyncHonestyApp.SerializableOnlyExecutor.class.getName() + "\"]"));

            // 2. Async 350ms task on unknown SerializableOnlyExecutor -> Test 1 gets
            //    untracked-executor:... AND Test 2 immediately gets tail:ts/unknown-async!
            String covAsync = app.runHttpTest("ts/unknown-async", "/unknown-serializable-async?ms=350");
            assertTrue("Test 1 must carry untracked-executor: " + covAsync,
                    covAsync.contains("untracked-executor:"
                            + AsyncHonestyApp.SerializableOnlyExecutor.class.getName()));
            String covNext = app.runHttpTest("ts/after-unknown", "/clean");
            assertTrue("Test 2 immediately after untracked async hand-off must carry tail contamination: " + covNext,
                    covNext.contains("\"tail:ts/unknown-async\""));
            Thread.sleep(450);
        }
    }

    @Test(timeout = 120000)
    public void forkedExternalCfDependentAndConcreteAsyncFormsAreTrackedOrFlagged() throws Exception {
        try (ForkedApp app = startFork("tomcat", "80", false, false, null, null)) {
            // External library CompletableFuture.thenAccept(350ms audit) with endWait=80ms -> windowCut:true!
            String covExtCut = app.runHttpTest("ts/ext-cf-cut", "/external-cf-dependent?ms=350");
            assertTrue("external CF plain thenAccept must hold window permit and cut at 80ms: " + covExtCut,
                    covExtCut.contains("\"windowCut\":true"));
            Thread.sleep(450);
            String covAfterExt = app.runHttpTest("ts/after-ext-cf", "/clean");
            assertTrue("window after cut external CF must carry tail:ts/ext-cf-cut: " + covAfterExt,
                    covAfterExt.contains("\"tail:ts/ext-cf-cut\""));

            // CompletionStage-typed thenApplyAsync(350ms) with endWait=80ms -> windowCut:true!
            String covStage = app.runHttpTest("ts/stage-async", "/completion-stage-async?ms=350");
            assertTrue("CompletionStage.thenApplyAsync must hold window permit and cut at 80ms: " + covStage,
                    covStage.contains("\"windowCut\":true"));
            Thread.sleep(450);
            app.runHttpTest("ts/after-stage", "/clean");
        }

        // With 2000ms endWait: external CF dependent's AsyncTail probe lands cleanly inside COV,
        // and concrete custom pool / Thread subclass / ForkJoinTask.fork / CompletionService / method-ref all flag!
        try (ForkedApp app = startFork("tomcat", "2000", false, false, null, null)) {
            String covExt = app.runHttpTest("ts/ext-cf-ok", "/external-cf-dependent?ms=200");
            assertFalse("external CF with sufficient wait must not cut: " + covExt, covExt.contains("windowCut"));
            assertTrue("unallowlisted external library thread start must be flagged by JDK sink: " + covExt,
                    covExt.contains("untracked-sink:thread-start:io.fakeexternal.FakeExternalHttpClient"));
            assertTrue("external CF audit probe must land in COV: " + covExt,
                    covExt.contains("\"" + ASYNC_TAIL + "\""));

            String covConcrete = app.runHttpTest("ts/concrete-pool", "/concrete-pool");
            assertTrue("concrete ThreadPoolExecutor subclass must contaminate: " + covConcrete,
                    covConcrete.contains("untracked-executor:" + AsyncHonestyApp.ConcreteCustomPool.class.getName()));

            String covSubThread = app.runHttpTest("ts/sub-thread", "/subclassed-thread");
            assertTrue("subclassed Thread.start() must contaminate: " + covSubThread,
                    covSubThread.contains("\"untracked-async:thread-start\""));

            String covFork = app.runHttpTest("ts/fj-fork", "/fork-join-fork");
            assertTrue("ForkJoinTask.fork() must contaminate: " + covFork,
                    covFork.contains("\"untracked-async:fork\""));

            String covEcs = app.runHttpTest("ts/ecs-submit", "/completion-service");
            assertTrue("ExecutorCompletionService.submit() must contaminate: " + covEcs,
                    covEcs.contains("untracked-executor:java.util.concurrent.ExecutorCompletionService"));

            String covMethodRef = app.runHttpTest("ts/method-ref", "/method-ref");
            assertTrue("executor::execute method ref must contaminate: " + covMethodRef,
                    covMethodRef.contains("untracked-executor:java.util.concurrent.ThreadPoolExecutor"));
        }
    }

    @Test(timeout = 120000)
    public void forkedUntrackedScheduleAndThreadStartContaminateCov() throws Exception {
        try (ForkedApp app = startFork("tomcat", "2000", false, false, null, null)) {
            String covSched = app.runHttpTest("ts/sched", "/untracked-schedule");
            assertTrue("ScheduledExecutorService.schedule must contaminate COV: " + covSched,
                    covSched.contains("\"untracked-async:schedule\""));

            String covThread = app.runHttpTest("ts/thread", "/untracked-thread");
            assertTrue("Thread.start() must contaminate COV: " + covThread,
                    covThread.contains("\"untracked-async:thread-start\""));
        }
    }

    @Test(timeout = 120000)
    public void forkedAsyncCallSitesOffSwitchLeavesTasksUnwrappedAndDeclaresAsyncExecutorsFalse() throws Exception {
        try (ForkedApp app = startFork("tomcat", "80", false, true, null, null)) {
            JsonObject hello = parseHello(app.initialHello);
            assertTrue("windowCut still true for Tomcat", hello.get("windowCut").getAsBoolean());
            assertFalse("KEPLOY_JAVA_ASYNC_CALLSITES=off must declare asyncExecutors=false",
                    hello.get("asyncExecutors").getAsBoolean());

            // Plain ExecutorService.submit is NOT rewritten when KEPLOY_JAVA_ASYNC_CALLSITES=off.
            String cov = app.runHttpTest("ts/off", "/submit-tail?ms=350");
            assertFalse("plain executor must NOT be wrapped when callsites=off: " + cov,
                    cov.contains("windowCut"));
            Thread.sleep(450);
        }
    }

    @Test(timeout = 120000)
    public void forkedWholeJarFallbackDeclaresAsyncExecutorsFalse() throws Exception {
        // Build an executable jar that carries foreign META-INF/maven metadata and no
        // package root for a known launcher (forces IndexReport.SOURCE_WHOLE_JAR, appOnly=false).
        File wholeJar = tmp.newFile("whole-jar-" + System.nanoTime() + ".jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, AsyncHonestyApp.class.getName());
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(wholeJar), mf)) {
            // No classes under org/example/servers inside this jar -> AppClasses.derive finds 0
            // classes under root, falling back to whole-jar on a dummy class inside the jar.
            jos.putNextEntry(new ZipEntry("com/thirdparty/Dummy.class"));
            jos.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 52});
            jos.closeEntry();
            jos.putNextEntry(new ZipEntry("META-INF/maven/com.thirdparty/dummy1/pom.properties"));
            jos.write("groupId=com.thirdparty\nartifactId=dummy1\n".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
            jos.putNextEntry(new ZipEntry("META-INF/maven/org.other/dummy2/pom.properties"));
            jos.write("groupId=org.other\nartifactId=dummy2\n".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        try (ForkedApp app = startFork("whole-jar", "200", false, false, wholeJar, null)) {
            JsonObject hello = parseHello(app.initialHello);
            assertFalse("whole-jar fallback must declare asyncExecutors=false, got: " + app.initialHello,
                    hello.get("asyncExecutors").getAsBoolean());
            String log = readFile(app.log);
            assertTrue("positive control: whole-jar fallback logged: " + log,
                    log.contains("could not tell the app's own classes from its libraries"));
        }
    }

    @Test(timeout = 120000)
    public void forkedJettyColdStartDeclaresWindowCutTrueAndUncountedServerMarksNoServerEntry() throws Exception {
        String jettyNonce;
        // 1. Jetty cold-start: BEFORE any HTTP request is made, HELLO already declares windowCut=true!
        try (ForkedApp jetty = startFork("jetty", "2000", false, false, null, null)) {
            JsonObject hello = parseHello(jetty.initialHello);
            jettyNonce = hello.get("jvmNonce").getAsString();
            assertTrue("Jetty cold-start must declare windowCut=true before first request: " + jetty.initialHello,
                    hello.get("windowCut").getAsBoolean());
            String cov = jetty.runHttpTest("ts/jetty-1", "/clean");
            assertFalse("Jetty request is counted, no no-server-entry: " + cov, cov.contains("no-server-entry"));
        }

        // 2. Uncounted HTTP server (JDK HttpServer): sun/net/httpserver/ServerImpl is in UNCOUNTED
        //    (so HELLO reports windowCut=false once loaded) AND runtime probes with enteredCount == 0
        //    still fail closed with no-server-entry!
        try (ForkedApp uncounted = startFork("uncounted", "2000", false, false, null, null)) {
            JsonObject hello = parseHello(uncounted.initialHello);
            assertNotEquals("distinct JVM restart must generate a distinct jvmNonce",
                    jettyNonce, hello.get("jvmNonce").getAsString());
            String cov = uncounted.runHttpTest("ts/uncounted-1", "/clean");
            assertTrue("uncounted server recording probes must emit unverified no-server-entry: " + cov,
                    cov.contains("\"unverified\":[\"no-server-entry\"]"));
            assertFalse("uncounted server must NOT put no-server-entry in contaminated (enterprise compat): " + cov,
                    cov.contains("\"contaminated\""));
            assertNotNull("JDK HttpServer must refresh HELLO with windowCut=false", uncounted.lastHello);
            JsonObject latest = parseHello(uncounted.lastHello);
            assertFalse("JDK HttpServer must declare windowCut=false: " + uncounted.lastHello,
                    latest.get("windowCut").getAsBoolean());
            assertFalse("JDK HttpServer must declare asyncExecutors=false: " + uncounted.lastHello,
                    latest.get("asyncExecutors").getAsBoolean());
        }
    }

    @Test(timeout = 120000)
    public void forkedVirtualThreadStartAndBuilderFlagAndTrackLiveness() throws Exception {
        String spec = System.getProperty("java.specification.version");
        org.junit.Assume.assumeTrue("Virtual threads require JDK 21+",
                !spec.startsWith("1.") && Integer.parseInt(spec) >= 21);

        // External-JaCoCo prefix mode ("org/example/servers/") automatically rewrites
        // the dynamically defined org/example/servers/VirtualThreadHelper on load!
        // For each virtual-thread form:
        //   1. Start a latch-held virtual thread during window 1 (assert untracked-async:*).
        //   2. Immediately run a throwaway /clean window to CONSUME the one-time
        //      untrackedTailReportedOnce report.
        //   3. Sleep 2200ms (strictly past the default 2000ms untrackedTailWindowNanos)
        //      while the virtual thread remains blocked on the latch.
        //   4. Run /clean and assert tail:<window-1> — this CAN ONLY PASS if the
        //      post-call DUP + trackUntrackedThread tracked Thread.isAlive().
        //   5. Release the latch via /release-virtual and assert the next /clean is clean.
        try (ForkedApp app = startFork("tomcat", "2000", true, false, null, null)) {
            String covDirect = app.runHttpTest("ts/vthread-1", "/virtual-thread-latch");
            assertTrue("Thread.startVirtualThread must contaminate COV: " + covDirect,
                    covDirect.contains("\"untracked-async:virtual-thread\""));

            // Consume one-time report so the later assertion genuinely depends on Thread.isAlive().
            app.runHttpTest("ts/vthread-consume-once", "/clean");
            Thread.sleep(2200); // Strictly past default 2000ms base tail while virtual thread is held on latch!
            String covAfterDirect = app.runHttpTest("ts/vthread-still-alive", "/clean");
            assertTrue("window past 2s base tail & after one-time report while virtual thread is alive "
                    + "must carry tail: " + covAfterDirect,
                    covAfterDirect.contains("\"tail:ts/vthread-1\""));

            app.runHttpTest("ts/vthread-release", "/release-virtual");
            String covDirectDone = app.runHttpTest("ts/vthread-done", "/clean");
            assertFalse("window after virtual thread exits must be clean: " + covDirectDone,
                    covDirectDone.contains("\"tail:ts/vthread-1\""));

            String covBuilder = app.runHttpTest("ts/vbuilder-1", "/virtual-builder-latch");
            assertTrue("Thread.ofVirtual().start() must contaminate COV: " + covBuilder,
                    covBuilder.contains("\"untracked-async:thread-builder\""));

            // Consume one-time report so the later assertion genuinely depends on Thread.isAlive().
            app.runHttpTest("ts/vbuilder-consume-once", "/clean");
            Thread.sleep(2200); // Strictly past default 2000ms base tail while builder virtual thread is held on latch!
            String covAfterBuilder = app.runHttpTest("ts/vbuilder-still-alive", "/clean");
            assertTrue("window past 2s base tail & after one-time report while builder virtual thread is alive "
                    + "must carry tail: " + covAfterBuilder,
                    covAfterBuilder.contains("\"tail:ts/vbuilder-1\""));

            app.runHttpTest("ts/vbuilder-release", "/release-virtual");
            String covBuilderDone = app.runHttpTest("ts/vbuilder-done", "/clean");
            assertFalse("window after builder virtual thread exits must be clean: " + covBuilderDone,
                    covBuilderDone.contains("\"tail:ts/vbuilder-1\""));
        }
    }

    @Test(timeout = 120000)
    public void forkedPreCapturedAndLazyMethodRefsAndLibraryHandOffsDropAsyncExecutorsAcrossReconnect()
            throws Exception {
        // 1. Pre-captured field Consumer<Runnable> dispatch = pool::execute initialized at startup
        //    latches asyncExecutors=false in HELLO and stays false across TCP reconnect.
        try (ForkedApp app = startFork("precaptured-tomcat", "2000", false, false, null, null)) {
            // Trigger one START/END to ensure any startup-captured latch is reflected in HELLO.
            app.runHttpTest("ts/pre-1", "/clean");
            JsonObject latest = parseHello(app.lastHello);
            assertTrue("windowCut stays true on Tomcat", latest.get("windowCut").getAsBoolean());
            assertFalse("startup pre-captured pool::execute must latch asyncExecutors=false: " + app.lastHello,
                    latest.get("asyncExecutors").getAsBoolean());

            String reconnHello = app.reconnectAndReadHello();
            JsonObject reconn = parseHello(reconnHello);
            assertTrue("windowCut stays true across reconnect", reconn.get("windowCut").getAsBoolean());
            assertFalse("asyncExecutors=false must stay latched across reconnect: " + reconnHello,
                    reconn.get("asyncExecutors").getAsBoolean());
        }

        // 2. Lazy in-window capture (W1 captures pool::execute, W2 invokes cached ref) flips HELLO
        //    to asyncExecutors=false before W1's COV and stays false across W2 and reconnect.
        //    First assert Tomcat keeps asyncExecutors=true after a /clean window (proves Tomcat's
        //    own NIO2 connector never false-latches and the subsequent latch is real).
        try (ForkedApp app = startFork("tomcat", "2000", false, false, null, null)) {
            JsonObject initial = parseHello(app.initialHello);
            assertTrue("initial HELLO starts with asyncExecutors=true",
                    initial.get("asyncExecutors").getAsBoolean());
            app.runHttpTest("ts/lazy-clean", "/clean");
            assertTrue("Tomcat must keep asyncExecutors=true after /clean window: " + app.lastHello,
                    parseHello(app.lastHello).get("asyncExecutors").getAsBoolean());
            String cov1 = app.runHttpTest("ts/lazy-w1", "/lazy-method-ref?ms=50");
            assertTrue("W1 lazy capture must contaminate COV: " + cov1,
                    cov1.contains("untracked-executor:"));
            JsonObject afterW1 = parseHello(app.lastHello);
            assertFalse("W1 lazy capture must refresh HELLO asyncExecutors=false: " + app.lastHello,
                    afterW1.get("asyncExecutors").getAsBoolean());
            app.runHttpTest("ts/lazy-w2", "/lazy-method-ref?ms=50");
            JsonObject reconn = parseHello(app.reconnectAndReadHello());
            assertFalse("lazy capture stays asyncExecutors=false across reconnect",
                    reconn.get("asyncExecutors").getAsBoolean());
        }

        // 3. Spring SimpleApplicationEventMulticaster.setTaskExecutor(plainPool) drops asyncExecutors=false.
        try (ForkedApp app = startFork("tomcat", "2000", false, false, null, null)) {
            assertTrue(parseHello(app.initialHello).get("asyncExecutors").getAsBoolean());
            app.runHttpTest("ts/mc-clean", "/clean");
            assertTrue("Tomcat must keep asyncExecutors=true after /clean before multicaster test: " + app.lastHello,
                    parseHello(app.lastHello).get("asyncExecutors").getAsBoolean());
            app.runHttpTest("ts/spring-mc", "/spring-multicaster-plain");
            JsonObject afterMc = parseHello(app.lastHello);
            assertFalse("Spring SimpleApplicationEventMulticaster with plain JDK pool must drop asyncExecutors=false: "
                    + app.lastHello, afterMc.get("asyncExecutors").getAsBoolean());
        }

        // 4. JDK AsynchronousFileChannel usage drops asyncExecutors=false.
        try (ForkedApp app = startFork("tomcat", "2000", false, false, null, null)) {
            assertTrue(parseHello(app.initialHello).get("asyncExecutors").getAsBoolean());
            app.runHttpTest("ts/nio-clean", "/clean");
            assertTrue("Tomcat must keep asyncExecutors=true after /clean before NIO test: " + app.lastHello,
                    parseHello(app.lastHello).get("asyncExecutors").getAsBoolean());
            app.runHttpTest("ts/nio-afc", "/nio-async-file");
            JsonObject afterNio = parseHello(app.lastHello);
            assertFalse("JDK AsynchronousFileChannel must drop asyncExecutors=false: " + app.lastHello,
                    afterNio.get("asyncExecutors").getAsBoolean());
        }
    }

    @Test(timeout = 420000)
    public void forkedLibraryAndJdkHandOffsNeverSilentlyDropAsyncLines() throws Exception {
        String spec = System.getProperty("java.specification.version");
        int major = spec.startsWith("1.") ? 8 : Integer.parseInt(spec);
        // Each tuple: {name, minJdk, expectBaselineHonest}
        // Cases whose startup creates an unhooked library/background worker or
        // scheduled outbox poller fail closed at startup (expectBaselineHonest=false);
        // in-window-only hand-offs keep asyncExecutors=true on baseline /clean.
        String[][] cases = {
            {"kafka-produce", "8", "false"},
            {"kafka-direct-produce", "8", "true"},
            {"spring-listenable-callback", "8", "true"},
            {"submission-publisher", "9", "false"},
            {"spring-mc-override", "8", "true"},
            {"queue-worker", "8", "false"},
            {"clq-worker", "8", "false"},
            {"deque-worker", "8", "false"},
            {"collection-add", "8", "false"},
            {"lib-callback", "8", "true"},
            {"scheduled-outbox", "8", "false"},
            {"exec-worker-deque", "8", "false"},
            {"spring-tpte-worker", "8", "false"},
            {"lib-subpool", "8", "true"},
        };
        for (boolean prefixMode : new boolean[] {true, false}) {
            for (String[] tc : cases) {
                String name = tc[0];
                if (major < Integer.parseInt(tc[1])) {
                    continue;
                }
                boolean expectBaseTrue = Boolean.parseBoolean(tc[2]);
                try (ForkedApp app = startFork("handoff-" + name, "80", prefixMode, false, null, null)) {
                    app.runHttpTest("ho/" + name + "-warm", "/clean");
                    // In index mode, DirectKafkaProducerCaller is defined dynamically
                    // outside KEPLOY_JAVA_CLASS_DIRS, so the read-only scan latches
                    // asyncExecutors=false immediately at class load (fail closed).
                    boolean dynamicNonIndexedProducer = "kafka-direct-produce".equals(name) && !prefixMode;
                    if (expectBaseTrue && !dynamicNonIndexedProducer) {
                        assertTrue("mode=" + (prefixMode ? "prefix" : "index") + " case=" + name
                                + " baseline /clean must keep asyncExecutors=true: " + app.lastHello,
                                parseHello(app.lastHello).get("asyncExecutors").getAsBoolean());
                    }
                    if (dynamicNonIndexedProducer) {
                        assertFalse("mode=index case=kafka-direct-produce must fail closed on non-indexed producer load: " + app.lastHello,
                                parseHello(app.lastHello).get("asyncExecutors").getAsBoolean());
                    }
                    String cov1 = app.runHttpTest("ho/" + name + "-w1", "/handoff-" + name + "?ms=400");
                    JsonObject helloAfter1 = parseHello(app.lastHello);
                    String cov2 = app.runHttpTest("ho/" + name + "-next", "/clean");
                    Thread.sleep(700);
                    String cov3 = app.runHttpTest("ho/" + name + "-after700", "/clean");
                    boolean flagged1 = cov1.contains("\"windowCut\":true")
                            || cov1.contains("\"contaminated\"")
                            || cov1.contains("\"unverified\"");
                    boolean asyncOffAtW1 = !helloAfter1.get("asyncExecutors").getAsBoolean();
                    // Strict W1 honesty: W1's own COV must be flagged OR under a non-honest
                    // HELLO BEFORE W1's COV (never relying on a late latch at helloEnd).
                    assertTrue("mode=" + (prefixMode ? "prefix" : "index") + " case=" + name
                            + " must be W1-flagged or latched BEFORE W1 COV: W1=" + cov1
                            + " helloAfter1=" + helloAfter1,
                            flagged1 || asyncOffAtW1);
                    if ("kafka-direct-produce".equals(name) && prefixMode) {
                        assertTrue("kafka-direct-produce must flag W1 COV at the call site: " + cov1,
                                cov1.contains("untracked-async:messaging-produce"));
                    }
                    for (String later : new String[] {cov2, cov3}) {
                        boolean laterFlagged = later.contains("\"windowCut\":true")
                                || later.contains("\"contaminated\"")
                                || later.contains("\"unverified\"");
                        if (!asyncOffAtW1 && later.contains("\"" + ASYNC_TAIL + "\"")) {
                            assertTrue("mode=" + (prefixMode ? "prefix" : "index") + " case=" + name
                                    + " clean later window must not silently hold async lines: " + later,
                                    laterFlagged);
                        }
                    }
                }
            }
        }
    }

    @Test(timeout = 120000)
    public void forkedKafkaListenerHookEmitsTopicAndBeanAttributionInTcpCov() throws Exception {
        try (ForkedApp kafka = startFork("kafka", "2000", false, false, null, null)) {
            JsonObject hello = parseHello(kafka.initialHello);
            assertTrue("listenerContamination must be true", hello.get("listenerContamination").getAsBoolean());
            String cov = kafka.runKafkaTest("ts/kafka-1");
            assertTrue("Kafka listener COV must carry topic + bean attribution: " + cov,
                    cov.contains("\"contaminated\":[\"kafka:orders-topic@"
                            + AsyncHonestyApp.OrdersListenerBean.class.getName() + "\"]"));
        }
    }

    @Test(timeout = 120000)
    public void forkedBootstrapBridgeLoadsInBootstrapClassLoaderAndAgentThreadsNeverLatch() throws Exception {
        try (ForkedApp app = startFork("tomcat", "2000", false, false, null, null)) {
            JsonObject initial = parseHello(app.initialHello);
            assertTrue("initial HELLO must declare asyncExecutors=true with bootstrap sink bridge: " + app.initialHello,
                    initial.get("asyncExecutors").getAsBoolean());
            String covCheck = app.runHttpTest("ts/bootstrap-cl", "/bootstrap-check");
            assertFalse("bootstrap-check must not contaminate COV: " + covCheck, covCheck.contains("contaminated"));
            assertTrue("asyncExecutors must remain true after /bootstrap-check and agent-internal threads: "
                    + app.lastHello, parseHello(app.lastHello).get("asyncExecutors").getAsBoolean());
            String covClean = app.runHttpTest("ts/bootstrap-clean", "/clean");
            assertFalse("clean baseline must not contaminate COV: " + covClean, covClean.contains("contaminated"));
            assertTrue("asyncExecutors must remain true after /clean: " + app.lastHello,
                    parseHello(app.lastHello).get("asyncExecutors").getAsBoolean());
        }
    }

    @Test(timeout = 300000)
    public void forkedDirectJdkSinkBypassesFailClosedWithExactSinkReasons() throws Exception {
        // Each tuple: {fixture, expectedSinkPrefix, expectAsyncFalseLatch, warmCommonPoolFirst}
        // These invoke JDK sinks via reflection/MethodHandle with zero call-site
        // rewrites, proving each of the 5 core JDK sink hooks independently fails closed.
        String[][] cases = {
            {"reflect-thread-start", "untracked-sink:thread-start:", "false", "false"},
            {"mh-thread-start", "untracked-sink:thread-start:", "false", "false"},
            {"mh-tpe-execute", "untracked-sink:tpe-execute:", "true", "false"},
            {"reflect-stpe-schedule", "untracked-sink:stpe-delayed-execute:", "true", "false"},
            {"reflect-fjp-submit", "untracked-sink:fjp-submit:", "true", "true"},
            {"reflect-timer-schedule", "untracked-sink:timer-sched:", "true", "false"},
        };
        for (String[] tc : cases) {
            String name = tc[0];
            String expectedSink = tc[1];
            boolean expectLatch = Boolean.parseBoolean(tc[2]);
            boolean warmFjp = Boolean.parseBoolean(tc[3]);
            try (ForkedApp app = startFork("sink-" + name, "80", false, false, null, null)) {
                app.runHttpTest("sink/" + name + "-warm", warmFjp ? "/warm-common-pool" : "/clean");
                assertTrue("case=" + name + " baseline must keep asyncExecutors=true: " + app.lastHello,
                        parseHello(app.lastHello).get("asyncExecutors").getAsBoolean());
                String cov1 = app.runHttpTest("sink/" + name + "-w1", "/sink-" + name + "?ms=250");
                assertTrue("case=" + name + " must fail closed with " + expectedSink + ", got W1=" + cov1,
                        cov1.contains(expectedSink));
                if (expectLatch) {
                    assertFalse("case=" + name + " must latch asyncExecutors=false: " + app.lastHello,
                            parseHello(app.lastHello).get("asyncExecutors").getAsBoolean());
                }
            }
        }
    }

    @Test(timeout = 180000)
    public void forkedJdk21VirtualThreadSinkBypassesFailClosed() throws Exception {
        String spec = System.getProperty("java.specification.version");
        org.junit.Assume.assumeTrue("Virtual threads require JDK 21+",
                !spec.startsWith("1.") && Integer.parseInt(spec) >= 21);

        try (ForkedApp app = startFork("sink-reflect-vthread-start", "80", false, false, null, null)) {
            app.runHttpTest("vt/reflect-warm", "/clean");
            assertTrue("baseline must keep asyncExecutors=true: " + app.lastHello,
                    parseHello(app.lastHello).get("asyncExecutors").getAsBoolean());
            String cov1 = app.runHttpTest("vt/reflect-w1", "/sink-reflect-vthread-start?ms=250");
            assertTrue("reflective VirtualThread.start must fail closed with virtual-thread-start sink: " + cov1,
                    cov1.contains("untracked-sink:virtual-thread-start:"));
        }

        for (String name : new String[] {"vthread-exec-worker", "vthread-exec-inwindow"}) {
            try (ForkedApp app = startFork("sink-" + name, "80", false, false, null, null)) {
                app.runHttpTest("vt/" + name + "-warm", "/clean");
                String cov1 = app.runHttpTest("vt/" + name + "-w1", "/sink-" + name + "?ms=300");
                boolean flagged = cov1.contains("\"contaminated\"") || cov1.contains("\"windowCut\":true");
                boolean asyncOff = !parseHello(app.lastHello).get("asyncExecutors").getAsBoolean();
                assertTrue("JDK 21 virtual-thread executor case=" + name + " must fail closed: W1=" + cov1
                        + " hello=" + app.lastHello, flagged || asyncOff);
            }
        }
    }

    @Test(timeout = 360000)
    public void forkedServletAsyncContextStartAfterCompleteNeverLeaksAcrossServers() throws Exception {
        // Servlet AsyncContext.start(r) where r calls ctx.complete() and then keeps
        // executing AsyncTail.ran() must NEVER silently drop lines from W1 or leak
        // them cleanly into W2, across Tomcat, Jetty, and Undertow in both modes.
        String[] servers = {"tomcat", "jetty", "undertow"};
        for (boolean prefixMode : new boolean[] {true, false}) {
            for (String server : servers) {
                String label = "server=" + server + " mode=" + (prefixMode ? "prefix" : "index");
                boolean isUndertow = "undertow".equals(server);
                // 1. Short endWait=80ms: post-complete runnable outlives END -> W1 must be
                //    windowCut:true AND W2 must carry tail contamination (zero silent leakage).
                try (ForkedApp app = startFork(server, "80", prefixMode, false, null, null)) {
                    JsonObject hello = parseHello(app.initialHello);
                    assertTrue(label + " must declare windowCut=true: " + app.initialHello,
                            hello.get("windowCut").getAsBoolean());
                    app.runHttpTest("ac/" + server + "-warm", "/clean");
                    if (!isUndertow) {
                        assertTrue(label + " must keep asyncExecutors=true after /clean: " + app.lastHello,
                                parseHello(app.lastHello).get("asyncExecutors").getAsBoolean());
                    }
                    String cov1 = app.runHttpTest("ac/" + server + "-cut", "/async-after-complete?ms=400");
                    assertTrue(label + " W1 must report windowCut:true: " + cov1,
                            cov1.contains("\"windowCut\":true"));
                    String cov2 = app.runHttpTest("ac/" + server + "-next", "/clean");
                    assertTrue(label + " W2 while post-complete task runs must carry tail:ac/"
                            + server + "-cut: " + cov2,
                            cov2.contains("\"tail:ac/" + server + "-cut\""));
                    Thread.sleep(600);
                    String cov3 = app.runHttpTest("ac/" + server + "-drained", "/clean");
                    assertFalse(label + " W3 after drain must not be windowCut: " + cov3,
                            cov3.contains("windowCut"));
                    if (!isUndertow) {
                        assertFalse(label + " W3 after drain must not carry tail:ac/" + server + "-cut: " + cov3,
                                cov3.contains("\"tail:ac/" + server + "-cut\""));
                        assertFalse(label + " W3 after drain must not be contaminated: " + cov3,
                                cov3.contains("contaminated"));
                    }
                }

                // 2. Sufficient endWait=2000ms: END waits until the post-complete runnable
                //    finishes -> AsyncTail lands IN-WINDOW inside W1 with zero silent leakage!
                try (ForkedApp app = startFork(server, "2000", prefixMode, false, null, null)) {
                    String warmCov = app.runHttpTest("ac/" + server + "-warm2", "/clean");
                    String cov1 = app.runHttpTest("ac/" + server + "-inwindow", "/async-after-complete?ms=250");
                    assertFalse(label + " sufficient wait must not cut: " + cov1, cov1.contains("windowCut"));
                    assertTrue(label + " post-complete AsyncTail must land IN-WINDOW in W1: " + cov1,
                            cov1.contains("\"" + ASYNC_TAIL + "\""));
                    String cov2 = app.runHttpTest("ac/" + server + "-clean", "/clean");
                    assertFalse(label + " W2 must not contain AsyncTail: " + cov2,
                            cov2.contains("\"" + ASYNC_TAIL + "\""));
                    if (!isUndertow) {
                        assertFalse(label + " sufficient wait must not contaminate W1: " + cov1,
                                cov1.contains("contaminated"));
                        assertFalse(label + " W2 after in-window completion must be clean: " + cov2,
                                cov2.contains("contaminated"));
                    } else {
                        // Undertow's EnhancedQueueExecutor/JBossThread is NOT allowlisted: either
                        // the warm request flags untracked-sink:thread-start or startup latched
                        // asyncExecutors=false.
                        boolean failClosed = warmCov.contains("untracked-sink:thread-start:")
                                || !parseHello(app.lastHello).get("asyncExecutors").getAsBoolean();
                        assertTrue(label + " Undertow JBossThread must not be allowlisted: warm=" + warmCov
                                + " hello=" + app.lastHello, failClosed);
                    }
                }
            }
        }
    }

    @Test(timeout = 420000)
    public void forkedAppSubPoolsAndReflectiveStartupWorkersFailClosed() throws Exception {
        // Isolated forks in both prefix and index modes for app/library JDK pool
        // subclasses and reflective startup workers.
        String[][] cases = {
            {"app-tpe-sub-worker", "false"},
            {"app-tpe-sub-submit-worker", "false"},
            {"app-fjp-sub-worker", "false"},
            {"app-fjp-sub-inwindow", "true"},
            {"fjtask-worker", "false"},
            {"app-timer-sub-poller", "false"},
            {"app-timer-sub-inwindow", "false"},
            {"app-stpe-sub-poller", "false"},
            {"lib-fjp-sub", "true"},
            {"reflect-worker-startup", "false"},
        };
        for (boolean prefixMode : new boolean[] {true, false}) {
            for (String[] tc : cases) {
                String name = tc[0];
                boolean expectBaseTrue = Boolean.parseBoolean(tc[1]);
                try (ForkedApp app = startFork("sink-" + name, "80", prefixMode, false, null, null)) {
                    app.runHttpTest("sp/" + name + "-warm", "/clean");
                    if (expectBaseTrue) {
                        assertTrue("mode=" + (prefixMode ? "prefix" : "index") + " case=" + name
                                + " baseline must keep asyncExecutors=true: " + app.lastHello,
                                parseHello(app.lastHello).get("asyncExecutors").getAsBoolean());
                    } else {
                        assertFalse("mode=" + (prefixMode ? "prefix" : "index") + " case=" + name
                                + " startup worker/timer must latch asyncExecutors=false at boot: " + app.lastHello,
                                parseHello(app.lastHello).get("asyncExecutors").getAsBoolean());
                    }
                    String cov1 = app.runHttpTest("sp/" + name + "-w1", "/sink-" + name + "?ms=300");
                    boolean flagged = cov1.contains("\"contaminated\"") || cov1.contains("\"windowCut\":true");
                    boolean asyncOff = !parseHello(app.lastHello).get("asyncExecutors").getAsBoolean();
                    assertTrue("mode=" + (prefixMode ? "prefix" : "index") + " case=" + name
                            + " must fail closed at or before W1: W1=" + cov1 + " hello=" + app.lastHello,
                            flagged || asyncOff);
                }
            }
        }
    }

    private static JsonObject parseHello(String line) {
        assertNotNull("HELLO line must not be null", line);
        assertTrue("expected HELLO frame, got: " + line, line.startsWith("HELLO {"));
        return JsonParser.parseString(line.substring("HELLO ".length())).getAsJsonObject();
    }

    private ForkedApp startFork(String mode, String endWait, boolean externalJacocoPrefixMode,
            boolean asyncCallSitesOff, File wholeJarOverride, String extraClassDirs) throws Exception {
        File classes = tmp.newFolder();
        java.nio.file.Path serversDir = new File(AsyncHonestyApp.class.getProtectionDomain().getCodeSource()
                .getLocation().getPath()).toPath().resolve("org/example/servers");
        java.nio.file.Path dstServers = classes.toPath().resolve("org/example/servers");
        Files.createDirectories(dstServers);
        try (java.util.stream.Stream<java.nio.file.Path> entries = Files.list(serversDir)) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) entries::iterator) {
                String fn = p.getFileName().toString();
                if (fn.endsWith(".class") && (fn.startsWith("AsyncHonestyApp")
                        || fn.equals("AsyncTail.class") || fn.equals("Tail.class"))) {
                    Files.copy(p, dstServers.resolve(fn));
                }
            }
        }

        ServerSocket collector = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        collector.setSoTimeout(60000);
        List<String> cmd = new ArrayList<>();
        cmd.add(new File(System.getProperty("java.home"), "bin/java").getPath());
        cmd.add("-Djava.io.tmpdir=" + tmp.newFolder().getPath());
        if (externalJacocoPrefixMode) {
            File jacocoJar = tmp.newFile("jacocoagent-" + System.nanoTime() + ".jar");
            AgentJar.extractTo(jacocoJar);
            cmd.add("-javaagent:" + jacocoJar.getPath() + "=includes=org.example.*,output=none");
        }
        cmd.add("-javaagent:" + sdkJar().getPath());

        List<String> cp = new ArrayList<>();
        if (wholeJarOverride != null) {
            cp.add(wholeJarOverride.getAbsolutePath());
        }
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            cp.add(new File(entry).getAbsolutePath());
        }
        cmd.add("-cp");
        cmd.add(String.join(File.pathSeparator, cp));
        if (wholeJarOverride != null) {
            // Launch directly with -jar would omit test classpath; simulate whole-jar by passing
            // KEPLOY_JAVA_CLASS_DIRS unset and working dir without target/classes while
            // sun.java.command points to wholeJarOverride via a separate class? Instead, pass
            // wholeJarOverride as the single classpath jar via -cp would miss AsyncHonestyApp,
            // so pass KEPLOY_JAVA_CLASS_DIRS unset AND a custom main? Even cleaner: run with
            // -cp <wholeJar>:<testCP> and no KEPLOY_JAVA_CLASS_DIRS, and have wholeJar contain
            // the main class! Wait: AsyncHonestyApp needs only JDK classes in "whole-jar" mode!
            cmd.set(cmd.size() - 1, wholeJarOverride.getAbsolutePath() + File.pathSeparator
                    + String.join(File.pathSeparator, cp));
        }
        cmd.add(AsyncHonestyApp.class.getName());
        cmd.add(mode);

        File log = tmp.newFile("fork-" + mode + "-" + System.nanoTime() + ".log");
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(tmp.newFolder())
                .redirectErrorStream(true)
                .redirectOutput(log);
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.startsWith("KEPLOY_") || k.equals("JAVA_TOOL_OPTIONS")
                || k.equals("JDK_JAVA_OPTIONS") || k.equals("_JAVA_OPTIONS"));
        if (!externalJacocoPrefixMode && wholeJarOverride == null) {
            env.put("KEPLOY_JAVA_CLASS_DIRS", extraClassDirs != null ? extraClassDirs : classes.getPath());
        }
        if (wholeJarOverride != null) {
            // Point KEPLOY_JAVA_CLASS_DIRS to a non-existent dir AND run from wholeJar
            // so AppClasses.derive fails (main class not in jar's own package) and
            // CoverageIndex falls back to whole-jar/classpath (appOnly=false)!
            env.put("KEPLOY_JAVA_CLASS_DIRS", new File(tmp.getRoot(), "nonexistent-dir").getPath());
            // Use a launcher class name that AppClasses treats as a known framework launcher
            // by running org.springframework.boot.loader? Even simpler: override sun.java.command
            // by running a jar whose main is in default/unknown launcher or with -D!
            // Actually: for wholeJarOverride, let's put a launcher class in wholeJarOverride and run `-jar`!
            cmd.clear();
            cmd.add(new File(System.getProperty("java.home"), "bin/java").getPath());
            cmd.add("-Djava.io.tmpdir=" + tmp.newFolder().getPath());
            cmd.add("-javaagent:" + sdkJar().getPath());
            cmd.add("-cp");
            cmd.add(String.join(File.pathSeparator, cp));
            // Force AppClasses.derive failure using a known launcher class name? No, run -jar wholeJar
            // with keploy-sdk on boot/agent classpath!
            File shadedJar = buildWholeJarLauncher(wholeJarOverride);
            cmd.clear();
            cmd.add(new File(System.getProperty("java.home"), "bin/java").getPath());
            cmd.add("-Djava.io.tmpdir=" + tmp.newFolder().getPath());
            cmd.add("-javaagent:" + sdkJarWithClasses().getPath());
            cmd.add("-jar");
            cmd.add(shadedJar.getPath());
            pb.command(cmd);
            env.remove("KEPLOY_JAVA_CLASS_DIRS");
        }
        env.put("KEPLOY_COVERAGE_ENDPOINT", "127.0.0.1:" + collector.getLocalPort());
        env.put("KEPLOY_JAVA_DEDUP_WARMUP_DISABLED", "true");
        env.put("KEPLOY_JAVA_END_WAIT_MS", endWait);
        env.put("KEPLOY_DEDUP_POD_UID", "pod-uid-1");
        env.put("KEPLOY_DEDUP_POD_NAME", "pod-name-1");
        env.put("KEPLOY_DEDUP_SESSION_ID", "session-1");
        if (asyncCallSitesOff) {
            env.put("KEPLOY_JAVA_ASYNC_CALLSITES", "off");
        }

        Process proc = pb.start();
        try {
            int port = waitForPort(log, proc);
            Socket sdk = collector.accept();
            sdk.setSoTimeout(60000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = sdk.getOutputStream();
            String hello = in.readLine();
            return new ForkedApp(proc, log, collector, sdk, in, out, port, hello);
        } catch (Throwable t) {
            proc.destroyForcibly();
            collector.close();
            throw t;
        }
    }

    // Builds an executable jar whose Main-Class is in the default package (no
    // package root -> AppClasses.derive fails, CoverageIndex falls back to
    // whole-jar with appOnly=false).
    private File buildWholeJarLauncher(File outJar) throws IOException {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, "RootMain",
                null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "main", "([Ljava/lang/String;)V", null, new String[] {"java/lang/Exception"});
        mv.visitCode();
        mv.visitFieldInsn(org.objectweb.asm.Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("PORT 0");
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                "(Ljava/lang/String;)V", false);
        mv.visitFieldInsn(org.objectweb.asm.Opcodes.GETSTATIC, "java/lang/System", "in", "Ljava/io/InputStream;");
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/io/InputStream", "read", "()I", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.POP);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();
        cw.visitEnd();

        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "RootMain");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(outJar), mf)) {
            jos.putNextEntry(new ZipEntry("RootMain.class"));
            jos.write(cw.toByteArray());
            jos.closeEntry();
            jos.putNextEntry(new ZipEntry("META-INF/maven/com.thirdparty/dummy1/pom.properties"));
            jos.write("groupId=com.thirdparty\nartifactId=dummy1\n".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
            jos.putNextEntry(new ZipEntry("META-INF/maven/org.other/dummy2/pom.properties"));
            jos.write("groupId=org.other\nartifactId=dummy2\n".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        return outJar;
    }

    private File sdkJarWithClasses() throws IOException {
        File jar = tmp.newFile("keploy-sdk-cp-" + System.nanoTime() + ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", KeployDedupAgent.class.getName());
        manifest.getMainAttributes().putValue("Can-Retransform-Classes", "true");
        StringBuilder cp = new StringBuilder();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (cp.length() > 0) {
                cp.append(' ');
            }
            cp.append(new File(entry).toURI().toASCIIString());
        }
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, cp.toString());
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar), manifest)) {
            out.putNextEntry(new ZipEntry("META-INF/keploy-test"));
            out.closeEntry();
        }
        return jar;
    }

    private File sdkJar() throws IOException {
        File jar = tmp.newFile("keploy-sdk-" + System.nanoTime() + ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", KeployDedupAgent.class.getName());
        manifest.getMainAttributes().putValue("Can-Retransform-Classes", "true");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar), manifest)) {
            out.putNextEntry(new ZipEntry("META-INF/keploy-test"));
            out.closeEntry();
        }
        return jar;
    }

    private static int waitForPort(File log, Process proc) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            for (String line : Files.readAllLines(log.toPath(), StandardCharsets.UTF_8)) {
                if (line.startsWith("PORT ")) {
                    return Integer.parseInt(line.substring("PORT ".length()).trim());
                }
            }
            assertTrue("forked JVM exited early:\n" + readFile(log), proc.isAlive());
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for PORT:\n" + readFile(log));
    }

    private static String readFile(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static String httpGet(int port, String path) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(30000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            String status = readHttpLine(in);
            assertTrue("expected HTTP 200 for " + path + ", got: " + status, status.contains(" 200 "));
            int length = -1;
            String header;
            while (!(header = readHttpLine(in)).isEmpty()) {
                if (header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    length = Integer.parseInt(header.substring("content-length:".length()).trim());
                }
            }
            if (length >= 0) {
                byte[] body = new byte[length];
                int read = 0;
                while (read < length) {
                    int n = in.read(body, read, length - read);
                    assertTrue(n > 0);
                    read += n;
                }
                return new String(body, StandardCharsets.UTF_8);
            }
            ByteArrayOutputStream rest = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                rest.write(buf, 0, n);
            }
            return new String(rest.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String readHttpLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0 && b != '\n') {
            if (b != '\r') {
                line.write(b);
            }
        }
        return new String(line.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static final class ForkedApp implements AutoCloseable {
        final Process proc;
        final File log;
        final ServerSocket collector;
        Socket sdk;
        BufferedReader in;
        OutputStream out;
        final int port;
        final String initialHello;
        String lastHello;

        ForkedApp(Process proc, File log, ServerSocket collector, Socket sdk,
                BufferedReader in, OutputStream out, int port, String initialHello) {
            this.proc = proc;
            this.log = log;
            this.collector = collector;
            this.sdk = sdk;
            this.in = in;
            this.out = out;
            this.port = port;
            this.initialHello = initialHello;
            this.lastHello = initialHello;
        }

        String reconnectAndReadHello() throws IOException {
            try {
                sdk.close();
            } catch (IOException ignored) {
            }
            Socket next = collector.accept();
            next.setSoTimeout(60000);
            this.sdk = next;
            this.in = new BufferedReader(new InputStreamReader(next.getInputStream(), StandardCharsets.UTF_8));
            this.out = next.getOutputStream();
            String hello = this.in.readLine();
            this.lastHello = hello;
            return hello;
        }

        String runHttpTest(String testId, String path) throws IOException {
            send("START " + testId);
            String ack = nextNonHello();
            assertEquals("ACK", ack);
            assertEquals("ok", httpGet(port, path));
            send("END " + testId);
            String cov = nextNonHello();
            assertTrue("expected COV, got: " + cov, cov != null && cov.startsWith("COV "));
            String line;
            while (!(line = nextNonHello()).equals("ACK")) {
                assertTrue(line, line.startsWith("CLASSES ") || line.startsWith("MANIFEST "));
            }
            return cov.substring("COV ".length());
        }

        String runKafkaTest(String testId) throws IOException {
            send("START " + testId);
            assertEquals("ACK", nextNonHello());
            try (Socket cmd = new Socket(InetAddress.getLoopbackAddress(), port)) {
                cmd.setSoTimeout(30000);
                cmd.getOutputStream().write("fire\n".getBytes(StandardCharsets.UTF_8));
                cmd.getOutputStream().flush();
                BufferedReader cmdIn = new BufferedReader(
                        new InputStreamReader(cmd.getInputStream(), StandardCharsets.UTF_8));
                assertEquals("DONE", cmdIn.readLine());
            }
            send("END " + testId);
            String cov = nextNonHello();
            assertTrue("expected COV, got: " + cov, cov != null && cov.startsWith("COV "));
            String line;
            while (!(line = nextNonHello()).equals("ACK")) {
                assertTrue(line, line.startsWith("CLASSES ") || line.startsWith("MANIFEST "));
            }
            return cov.substring("COV ".length());
        }

        private String nextNonHello() throws IOException {
            String line = in.readLine();
            while (line != null && line.startsWith("HELLO ")) {
                lastHello = line;
                line = in.readLine();
            }
            return line;
        }

        private void send(String cmd) throws IOException {
            out.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        @Override
        public void close() throws Exception {
            try { sdk.close(); } catch (IOException ignored) { }
            try { collector.close(); } catch (IOException ignored) { }
            try { proc.getOutputStream().close(); } catch (IOException ignored) { }
            if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                proc.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        }
    }
}
