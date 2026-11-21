package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jacoco.core.internal.data.CRC64;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.newsclub.net.unix.AFUNIXSocketChannel;

/**
 * The unix control socket Keploy sends START and END on. Keploy fires its first
 * test as soon as the app answers its health check, so the socket must listen
 * by the time start() (premain, before the app's main) returns. Every command
 * the SDK reads gets one answer line before the close: "ACK proto=2" if it was
 * taken, "IGNORED proto=2" if not. Keploy reads a close with no answer from an
 * SDK that says proto=2 as a command not taken, so only a connection closed
 * with nothing sent may go unanswered.
 *
 * <p>The answers are compared with literals, not the SDK's constants: Keploy
 * reads the words, so a change to them must fail here.
 */
public class ControlSocketTest {

    private static final String JACOCO_PORT_PROPERTY = "keploy.jacoco.port";
    private static final String WARMUP_DISABLED_PROPERTY = "keploy.java.dedup.warmup.disabled";
    private static final String CLASS_DIRS_PROPERTY = "keploy.java.class.dirs";
    private static final String END_WAIT_PROPERTY = "keploy.java.end.wait.ms";
    private static final String TAKEN = "ACK proto=2\n";
    private static final String IGNORED = "IGNORED proto=2\n";

    private String savedControlPath;
    private String savedDataPath;
    private File dir;
    private FakeJacoco jacoco;
    private DataReceiver data;
    // What the agent logged at SEVERE during the test: the first 20 of them,
    // so that a server looping on a closed socket fails a row, not the JVM.
    private final List<String> severe = new CopyOnWriteArrayList<>();
    // And the first 200 messages it logged at INFO or above.
    private final List<String> logged = new CopyOnWriteArrayList<>();
    private final Handler severeLog = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.SEVERE.intValue() && severe.size() < 20) {
                severe.add(record.getMessage());
            }
            if (record.getLevel().intValue() >= Level.INFO.intValue() && logged.size() < 200) {
                logged.add(record.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private AFUNIXServerSocket foreign;
    private static final String INDEX_START = "{\"kind\":\"index\",";

    @Before
    public void setUp() throws IOException {
        Logger.getLogger(KeployDedupAgent.class.getName()).addHandler(severeLog);
        savedControlPath = KeployDedupAgent.controlSocketPath;
        savedDataPath = KeployDedupAgent.dataSocketPath;
        dir = Files.createTempDirectory("keploy-dedup-control").toFile();
        KeployDedupAgent.controlSocketPath = new File(dir, "control.sock").getAbsolutePath();
        KeployDedupAgent.dataSocketPath = new File(dir, "data.sock").getAbsolutePath();
        // The warmup loads every class on the test classpath; nothing here needs it.
        System.setProperty(WARMUP_DISABLED_PROPERTY, "true");
        // A TABLE row answers ACK whatever heap this JVM was given (-DargLine=-Xmx...): the heap check is off
        // unless a row sets its own bound.
        System.setProperty(BuildTable.MIN_HEAP_PROPERTY, "0");
        KeployDedupAgent.transformerAdded = false;
    }

    /** A TABLE row needs the heap check's env var unset: it would win over the property setUp sets. */
    private static void assumeNoMinHeapEnv() {
        String v = System.getenv(BuildTable.MIN_HEAP_ENV);
        org.junit.Assume.assumeTrue(BuildTable.MIN_HEAP_ENV + " is set", v == null || v.trim().isEmpty());
    }

    @After
    public void tearDown() throws Exception {
        KeployDedupAgent.stop();
        KeployDedupAgent.workerStopWaitMillis = 5000;
        Logger.getLogger(KeployDedupAgent.class.getName()).removeHandler(severeLog);
        Logger.getLogger(KeployDedupAgent.class.getName()).setUseParentHandlers(true);
        if (foreign != null) {
            foreign.close();
        }
        KeployDedupAgent.controlSocketPath = savedControlPath;
        KeployDedupAgent.dataSocketPath = savedDataPath;
        System.clearProperty(JACOCO_PORT_PROPERTY);
        System.clearProperty(WARMUP_DISABLED_PROPERTY);
        System.clearProperty(CLASS_DIRS_PROPERTY);
        System.clearProperty(BuildTable.MIN_HEAP_PROPERTY);
        System.clearProperty(END_WAIT_PROPERTY);
        KeployDedupAgent.transformerAdded = false;
        if (jacoco != null) {
            jacoco.close();
        }
        if (data != null) {
            data.close();
        }
        delete(dir);
    }

    private static void delete(File f) {
        File[] left = f.listFiles();
        if (left != null) {
            for (File c : left) {
                delete(c);
            }
        }
        f.delete();
    }

    @Test
    public void controlSocketListensWhenStartReturns() throws Exception {
        assertTrue(KeployDedupAgent.start());
        File socket = new File(KeployDedupAgent.controlSocketPath);
        // No wait and no retry: a command Keploy sends the moment start() has
        // returned must find the socket listening.
        assertTrue("the control socket is bound when start() returns", socket.exists());
        try (AFUNIXSocket client = AFUNIXSocket.newInstance()) {
            client.connect(AFUNIXSocketAddress.of(socket), 3000);
        }
        // Keploy's agent may run as another user (sudo, a container).
        assertTrue("anyone may write to the control socket",
                Files.getPosixFilePermissions(socket.toPath()).contains(PosixFilePermission.OTHERS_WRITE));
    }

    @Test
    public void startReplacesAStaleSocketFile() throws Exception {
        File socket = new File(KeployDedupAgent.controlSocketPath);
        assertTrue(socket.createNewFile());
        assertTrue(KeployDedupAgent.start());
        assertEquals(IGNORED, send("END test-set-0/test-1"));
    }

    @Test
    public void startReportsFailureWhenTheControlSocketCannotBind() {
        KeployDedupAgent.controlSocketPath = new File(new File(dir, "missing"), "control.sock").getAbsolutePath();
        assertFalse("start() with no control socket", KeployDedupAgent.start());
        assertFalse(KeployDedupAgent.isStarted());
    }

    @Test
    public void endWithNoStartIsAnsweredIgnoredNotAck() throws Exception {
        assertTrue(KeployDedupAgent.start());
        assertEquals(IGNORED, send("END test-set-0/test-47"));
    }

    @Test
    public void aTestStartedAndEndedIsAnsweredTakenAndItsCoverageIsSent() throws Exception {
        startWithJacoco();
        data = new DataReceiver(KeployDedupAgent.dataSocketPath);
        assertEquals(TAKEN, send("START test-set-0/test-1"));
        assertEquals(1, jacoco.served.get());
        assertEquals(TAKEN, send("END test-set-0/test-1"));
        String payload = data.next();
        assertNotNull("the END's coverage reached the data socket", payload);
        assertTrue(payload, payload.contains("\"id\":\"test-set-0/test-1\""));
    }

    @Test
    public void aJobRunningInATestsWindowIsNamedInItsRecordAndOneBetweenTestsIsNot() throws Exception {
        startWithJacoco();
        data = new DataReceiver(KeployDedupAgent.dataSocketPath);
        // Running when the START opens the window, done before its END.
        Object running = JobGate.enter(new Object(), "quartz");
        try {
            assertEquals(TAKEN, send("START test-set-0/test-1"));
        } finally {
            JobGate.exit(running);
        }
        assertEquals(TAKEN, send("END test-set-0/test-1"));
        String payload = data.next();
        assertNotNull(payload);
        assertTrue(payload, payload.contains("\"id\":\"test-set-0/test-1\""));
        assertTrue(payload, payload.endsWith(",\"contaminated\":[\"quartz:java.lang.Object\"]}"));

        // A run between two tests is in neither.
        JobGate.exit(JobGate.enter(new Object(), "scheduled"));
        assertEquals(TAKEN, send("START test-set-0/test-2"));
        assertEquals(TAKEN, send("END test-set-0/test-2"));
        payload = data.next();
        assertNotNull(payload);
        assertTrue(payload, payload.contains("\"id\":\"test-set-0/test-2\""));
        assertFalse(payload, payload.contains("contaminated"));
    }

    @Test(timeout = 60000)
    public void aJobThatStartsWhileEndWaitsForTheTestsRequestsIsNamed() throws Exception {
        // The longest wait: END waits for the request until this row lets it go.
        System.setProperty(END_WAIT_PROPERTY, "2000");
        startWithJacoco();
        data = new DataReceiver(KeployDedupAgent.dataSocketPath);
        assertEquals(TAKEN, send("START test-set-0/test-1"));
        Object request = RequestGate.enter();
        ExecutorService ender = Executors.newSingleThreadExecutor();
        try {
            Future<String> end = ender.submit(() -> send("END test-set-0/test-1"));
            // END took the window it waits on: the request no longer counts in the current one.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (RequestGate.running() != 0) {
                assertTrue("END never waited for the request", System.nanoTime() < deadline);
                Thread.sleep(1);
            }
            JobGate.exit(JobGate.enter(new Object(), "quartz"));
            RequestGate.exit(request);
            request = null;
            assertEquals(TAKEN, end.get(30, TimeUnit.SECONDS));
        } finally {
            if (request != null) {
                RequestGate.exit(request);
            }
            ender.shutdownNow();
        }
        String payload = data.next();
        assertNotNull(payload);
        assertFalse("END did not reach its bound: " + payload, payload.contains("windowCut"));
        assertTrue(payload, payload.endsWith(",\"contaminated\":[\"quartz:java.lang.Object\"]}"));
    }

    @Test(timeout = 60000)
    public void aJobThatStartsWhileEndReadsTheHitsIsNamed() throws Exception {
        startWithJacoco();
        data = new DataReceiver(KeployDedupAgent.dataSocketPath);
        assertEquals(TAKEN, send("START test-set-0/test-1"));
        jacoco.onDump = () -> JobGate.exit(JobGate.enter(new Object(), "scheduled"));
        assertEquals(TAKEN, send("END test-set-0/test-1"));
        String payload = data.next();
        assertNotNull(payload);
        assertTrue(payload, payload.endsWith(",\"contaminated\":[\"scheduled:java.lang.Object\"]}"));
    }

    @Test(timeout = 60000)
    public void eachCommandToJacocosTcpServerArrivesWholeWithNoNagleStall() throws Exception {
        // Nagle held all but the command's first byte or two behind the
        // tcpserver's delayed ACK. What this checks is that the whole command
        // is there at the stand-in's first read; on loopback with Nagle off,
        // a command written a byte at a time usually is too.
        startWithJacoco();
        data = new DataReceiver(KeployDedupAgent.dataSocketPath);
        assertEquals(TAKEN, send("START test-set-0/test-1"));
        assertEquals(TAKEN, send("END test-set-0/test-1"));
        // JaCoCo's header (a block byte, two chars) and the dump command (a block byte, two booleans).
        assertTrue(jacoco.firstReads.toString(), jacoco.firstReads.size() >= 2);
        for (int got : jacoco.firstReads) {
            assertEquals("each connection's first read got its whole command: " + jacoco.firstReads, 8, got);
        }
    }

    @Test(timeout = 60000)
    public void aStartAfterTheSdkRanAsAJavaagentDoesNotSayNoJobRunIsSeen() throws Exception {
        List<ClassFileTransformer> added = new ArrayList<>();
        Instrumentation premains = (Instrumentation) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Instrumentation.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "addTransformer":
                            added.add((ClassFileTransformer) args[0]);
                            return null;
                        case "isRetransformClassesSupported":
                            return Boolean.TRUE;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
        KeployDedupAgent.countRequests(premains, false);
        assertEquals("premain added ServerEntries", 1, added.size());
        // A stop() and a start() from the app's code (a context reload): premain's changes still count job runs.
        startWithJacoco();
        for (String line : logged) {
            assertFalse(line, line.startsWith("Keploy dedup does not see the app's scheduled jobs' runs"));
        }
    }

    @Test(timeout = 60000)
    public void startedWithNoInstrumentationItSaysNoJobRunIsSeen() throws Exception {
        startWithJacoco();
        assertTrue(logged.toString(), logged.contains("Keploy dedup does not see the app's scheduled jobs' runs:"
                + " keploy-sdk.jar was not started as a -javaagent, so a test a Quartz job or a Spring @Scheduled"
                + " method ran in is not marked contaminated in its record"));
    }

    @Test(timeout = 60000)
    public void theBuildTableGoesOnlyWhenKeployAsksForIt() throws Exception {
        assumeNoMinHeapEnv();
        // The index is the java-dedup sample's classes, so the table is the Go encoder's golden for them.
        File classes = new File(dir, "classes");
        for (BuildTable.ClassFile f : BuildTableTest.javaDedup()) {
            File out = new File(classes, f.name + ".class");
            assertTrue(out.getParentFile().isDirectory() || out.getParentFile().mkdirs());
            Files.write(out.toPath(), f.bytes);
        }
        System.setProperty(CLASS_DIRS_PROPERTY, classes.getPath());
        startWithJacoco();
        data = new DataReceiver(KeployDedupAgent.dataSocketPath);
        int asked = BuildTable.requests();
        assertEquals(TAKEN, send("START test-set-0/test-1"));
        assertEquals(TAKEN, send("END test-set-0/test-1"));
        String payload = data.next();
        assertNotNull("the END's coverage reached the data socket", payload);
        assertTrue(payload, payload.contains("\"id\":\"test-set-0/test-1\""));
        // Commands are handled one at a time, so once END is answered all START and END asked for is asked.
        // An older Keploy never sends TABLE: it never gets a frame.
        assertEquals("START and END never ask for the table", asked, BuildTable.requests());
        assertNull("no table frame without TABLE", data.poll());

        assertEquals("answered at once; the table goes on its own thread", TAKEN, send("TABLE"));
        assertEquals(asked + 1, BuildTable.requests());
        String frame = data.next();
        assertNotNull("the TABLE's frame reached the data socket", frame);
        assertTrue(frame, frame.startsWith("KTB1"));
        int header = frame.indexOf("{\"v\":1,");
        int body = frame.indexOf('\n', header) + 1;
        assertTrue(frame, header > 0 && body > header);
        byte[] golden = BuildTableTest.resource("java-dedup.table.ndjson");
        String build = new String(BuildTableTest.resource("java-dedup.build"), StandardCharsets.UTF_8).trim();
        assertEquals("{\"v\":1,\"part\":\"table\",\"build\":\"" + build + "\",\"seq\":0,\"last\":true,"
                + "\"records\":5,\"sha256\":\"" + sha256(golden) + "\"}\n", frame.substring(header, body));
        assertEquals(new String(golden, StandardCharsets.UTF_8), frame.substring(body));
        // The probe map follows the table, as its own stream with the same build.
        String probes = data.next();
        assertNotNull("the probe map followed the table", probes);
        byte[] map = BuildTableTest.resource("java-dedup.probes.ndjson");
        header = probes.indexOf("{\"v\":1,");
        body = probes.indexOf('\n', header) + 1;
        assertEquals("{\"v\":1,\"part\":\"probes\",\"build\":\"" + build + "\",\"seq\":0,\"last\":true,"
                + "\"records\":5,\"sha256\":\"" + sha256(map) + "\"}\n", probes.substring(header, body));
        assertEquals(new String(map, StandardCharsets.UTF_8), probes.substring(body));
        assertNull("nothing more", data.payloads.poll(1, TimeUnit.SECONDS));
    }

    @Test(timeout = 60000)
    public void aTableAskedWhenTheHeapIsShortIsDeclinedWithLowHeapAndSendsNothing() throws Exception {
        assumeNoMinHeapEnv();
        File classes = new File(dir, "classes");
        for (BuildTable.ClassFile f : BuildTableTest.javaDedup()) {
            File out = new File(classes, f.name + ".class");
            assertTrue(out.getParentFile().isDirectory() || out.getParentFile().mkdirs());
            Files.write(out.toPath(), f.bytes);
        }
        System.setProperty(CLASS_DIRS_PROPERTY, classes.getPath());
        long needed = Long.MAX_VALUE >> 20;
        System.setProperty(BuildTable.MIN_HEAP_PROPERTY, String.valueOf(needed));
        try {
            startWithJacoco();
            data = new DataReceiver(KeployDedupAgent.dataSocketPath);
            int asked = BuildTable.requests();
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            String reply = send("TABLE");
            assertTrue(reply, reply.matches("IGNORED proto=2 reason=low_heap heap_free_mb=\\d+ heap_needed_mb="
                    + needed + "\n"));
            assertEquals("no send started", asked, BuildTable.requests());
            assertNull("no table frame", data.payloads.poll(2, TimeUnit.SECONDS));
            assertEquals("the control server goes on", TAKEN, send("END test-set-0/test-1"));
        } finally {
            System.clearProperty(BuildTable.MIN_HEAP_PROPERTY);
        }
    }

    @Test(timeout = 60000)
    public void theIndexRecordAndTheTableNameOneBuild() throws Exception {
        assumeNoMinHeapEnv();
        // Keploy finds the table under the build the INDEX record named. The index is the java-dedup sample's
        // classes and one file no class reader reads: both builds take every class the index holds.
        File classes = new File(dir, "classes");
        List<BuildTable.ClassFile> files = new ArrayList<>(BuildTableTest.javaDedup());
        files.add(new BuildTable.ClassFile("com/acme/Junk", "not a class".getBytes(StandardCharsets.UTF_8)));
        TreeSet<String> ids = new TreeSet<>();
        for (BuildTable.ClassFile f : files) {
            File out = new File(classes, f.name + ".class");
            assertTrue(out.getParentFile().isDirectory() || out.getParentFile().mkdirs());
            Files.write(out.toPath(), f.bytes);
            ids.add(String.format("%016x", CRC64.classId(f.bytes)));
        }
        // Keploy's codecoverage.JavaBuildID: the table format, the analyzer's JaCoCo release, then
        // the ids as 16 hex, sorted (as hex: unsigned), joined by newlines.
        String build = "jv1-" + sha256(("keploy-java-table 1\njacoco " + IndexReportTest.jacocoCore() + "\n"
                + String.join("\n", ids)).getBytes(StandardCharsets.UTF_8)).substring(0, 32);
        assertNotEquals("the file no class reader reads is in the build",
                new String(BuildTableTest.resource("java-dedup.build"), StandardCharsets.UTF_8).trim(), build);
        System.setProperty(CLASS_DIRS_PROPERTY, classes.getPath());
        data = new DataReceiver(KeployDedupAgent.dataSocketPath);
        startWithJacoco();
        String index = data.indexes.poll(30, TimeUnit.SECONDS);
        assertNotNull("the INDEX record came once the data socket was bound", index);
        assertTrue(index, index.contains("\"classes\":" + files.size() + ","));
        assertEquals(index, build, field(index, "build"));

        assertEquals(TAKEN, send("TABLE"));
        boolean tableDone = false;
        boolean probesDone = false;
        while (!tableDone || !probesDone) {
            String frame = data.next();
            assertNotNull("the table and the probe map each reached their last frame", frame);
            int at = frame.indexOf("{\"v\":1,");
            assertTrue(frame, frame.startsWith("KTB1") && at > 0);
            String header = frame.substring(at, frame.indexOf('\n', at));
            assertEquals(header, build, field(header, "build"));
            boolean last = header.contains("\"last\":true");
            if (header.contains("\"part\":\"table\"")) {
                tableDone |= last;
            } else {
                assertTrue(header, header.contains("\"part\":\"probes\""));
                probesDone |= last;
            }
        }
        assertNull("nothing more", data.payloads.poll(1, TimeUnit.SECONDS));
    }

    @Test(timeout = 300000)
    public void startAndEndAreAnsweredWhileTheTableIsAnalyzed() throws Exception {
        assumeNoMinHeapEnv();
        // Keploy sends TABLE after the first ACK and goes on with its tests: the table's analysis runs on its own
        // thread, so no START or END waits for it. The index is a generated app large enough that its analysis
        // outlasts many START/END pairs.
        File classes = new File(dir, "classes");
        int n = 5000;
        for (int i = 0; i < n; i++) {
            String name = BuildTableBudgetTest.name(i);
            File out = new File(classes, name + ".class");
            assertTrue(out.getParentFile().isDirectory() || out.getParentFile().mkdirs());
            Files.write(out.toPath(), BuildTableBudgetTest.gen(name, i, BuildTableBudgetTest.BRANCHES));
        }
        System.setProperty(CLASS_DIRS_PROPERTY, classes.getPath());
        data = new DataReceiver(KeployDedupAgent.dataSocketPath);
        startWithJacoco();
        assertNotNull("the INDEX record (the index is read)", data.indexes.poll(60, TimeUnit.SECONDS));
        assertEquals(TAKEN, send("START test-set-0/test-0"));
        assertEquals(TAKEN, send("END test-set-0/test-0"));
        assertNotNull(data.next());

        long t0 = System.nanoTime();
        assertEquals(TAKEN, send("TABLE"));
        long tableAck = System.nanoTime() - t0;
        boolean tableDone = false;
        boolean probesDone = false;
        int during = 0;
        long worst = 0;
        for (int k = 1; !tableDone || !probesDone; k++) {
            long s = System.nanoTime();
            assertEquals(TAKEN, send("START test-set-0/test-" + k));
            assertEquals(TAKEN, send("END test-set-0/test-" + k));
            worst = Math.max(worst, System.nanoTime() - s);
            boolean ended = false;
            for (String p; (p = data.payloads.poll(ended ? 0 : 30, TimeUnit.SECONDS)) != null; ) {
                if (!p.startsWith("KTB1")) {
                    assertTrue(p, p.contains("\"id\":\"test-set-0/test-" + k + "\""));
                    ended = true;
                    continue;
                }
                int at = p.indexOf("{\"v\":1,");
                String header = p.substring(at, p.indexOf('\n', at));
                boolean last = header.contains("\"last\":true");
                tableDone |= last && header.contains("\"part\":\"table\"");
                probesDone |= last && header.contains("\"part\":\"probes\"");
            }
            assertTrue("the END's coverage reached the data socket", ended);
            if (!tableDone || !probesDone) {
                during++;
            }
        }
        long table = System.nanoTime() - t0;
        System.out.printf("START/END pairs answered while the table of %d classes was analyzed and sent: %d in %d ms;"
                + " slowest pair %d ms; TABLE answered in %d ms%n", n, during, table / 1_000_000, worst / 1_000_000,
                tableAck / 1_000_000);
        assertTrue("START and END were answered while the table was analyzed: " + during + " pairs in "
                + table / 1_000_000 + " ms", during >= 3);
        // The bounds are generous (a pair takes about 0.1 s): they fail when a START/END or TABLE waits on a
        // slow analysis. A fast runner can finish the analysis inside them; during >= 3 above is what fails
        // when the control thread does the analysis itself.
        assertTrue("TABLE itself was answered at once: " + tableAck / 1_000_000 + " ms",
                tableAck < TimeUnit.SECONDS.toNanos(5));
        assertTrue("no START/END pair waited on the analysis: the slowest took " + worst / 1_000_000 + " ms",
                worst < TimeUnit.SECONDS.toNanos(5));
    }

    // field is the string value of name in one JSON object's text.
    private static String field(String json, String name) {
        String key = "\"" + name + "\":\"";
        int at = json.indexOf(key);
        assertTrue(json, at >= 0);
        return json.substring(at + key.length(), json.indexOf('"', at + key.length()));
    }

    @Test(timeout = 60000)
    public void aTableThatFailsToStartIsIgnoredAndTheServerGoesOn() throws Exception {
        assumeNoMinHeapEnv();
        startWithJacoco();
        String dataPath = KeployDedupAgent.dataSocketPath;
        data = new DataReceiver(dataPath);
        KeployDedupAgent.dataSocketPath = null; // new File(null) throws on the control thread
        assertEquals(IGNORED, send("TABLE"));
        KeployDedupAgent.dataSocketPath = dataPath;
        assertEquals(TAKEN, send("START test-set-0/test-1"));
        assertEquals(TAKEN, send("END test-set-0/test-1"));
        String payload = data.next();
        assertNotNull("the END's coverage reached the data socket", payload);
        assertTrue(payload, payload.contains("\"id\":\"test-set-0/test-1\""));
    }

    private static String sha256(byte[] b) throws NoSuchAlgorithmException {
        StringBuilder hex = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) {
            hex.append(String.format("%02x", x));
        }
        return hex.toString();
    }

    @Test
    public void anEndForAnotherTestIsIgnoredAndTheTestRunningGoesOn() throws Exception {
        startWithJacoco();
        data = new DataReceiver(KeployDedupAgent.dataSocketPath);
        assertEquals(TAKEN, send("START test-set-0/test-1"));
        assertEquals(IGNORED, send("END test-set-0/test-2"));
        assertNull("an ignored END sends no coverage", data.poll());
        assertEquals(TAKEN, send("END test-set-0/test-1"));
        assertTrue(data.next().contains("\"id\":\"test-set-0/test-1\""));
    }

    @Test
    public void aStartWhoseResetFailedIsIgnoredAndSoIsItsEnd() throws Exception {
        startWithJacoco();
        data = new DataReceiver(KeployDedupAgent.dataSocketPath);
        assertEquals(TAKEN, send("START test-set-0/test-1"));
        jacoco.dropNext.set(1);
        assertEquals(IGNORED, send("START test-set-0/test-2"));
        // JaCoCo answers again, so only the failed START can make these ENDs
        // not taken: the counters still hold test-1's lines and whatever ran
        // after, and test-2's window never began.
        assertEquals(IGNORED, send("END test-set-0/test-1"));
        assertEquals(IGNORED, send("END test-set-0/test-2"));
        assertNull("an ignored END sends no coverage", data.poll());
        assertEquals(TAKEN, send("START test-set-0/test-3"));
        assertEquals(TAKEN, send("END test-set-0/test-3"));
    }

    @Test
    public void anEndWhoseCoverageCouldNotBeSentIsIgnoredAndEndsTheTest() throws Exception {
        startWithJacoco();
        assertEquals(TAKEN, send("START test-set-0/test-1"));
        // No one listens on the data socket.
        assertEquals(IGNORED, send("END test-set-0/test-1"));
        data = new DataReceiver(KeployDedupAgent.dataSocketPath);
        assertEquals("the failed END ended the test", IGNORED, send("END test-set-0/test-1"));
        assertNull(data.poll());
        assertEquals(TAKEN, send("START test-set-0/test-2"));
        assertEquals(TAKEN, send("END test-set-0/test-2"));
    }

    @Test
    public void aLineThatIsNoCommandIsIgnored() throws Exception {
        startWithJacoco();
        String[] lines = {"", "   ", "START", "START   ", "END", "PING test-set-0/test-1",
            "start test-set-0/test-1", "STARTtest-set-0/test-1"};
        for (String line : lines) {
            assertEquals("[" + line + "]", IGNORED, send(line));
        }
        byte[] notUtf8 = {(byte) 0xff, (byte) 0xfe, ' ', 'x', '\n'};
        assertEquals("bytes that are not UTF-8", IGNORED, sendBytes(notUtf8));
        assertEquals("none of them reset the counters", 0, jacoco.served.get());
    }

    @Test(timeout = 60000)
    public void aCommandWithNoNewlineBeforeTheCloseIsRunAndAnswered() throws Exception {
        startWithJacoco();
        assertEquals(TAKEN, sendThenCloseOutput("START test-set-0/test-1".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, jacoco.served.get());
    }

    @Test(timeout = 60000)
    public void aConnectionClosedWithNothingSentGetsNoAnswer() throws Exception {
        assertTrue(KeployDedupAgent.start());
        assertEquals("", sendThenCloseOutput(new byte[0]));
        assertEquals("the server still answers", IGNORED, send("END test-set-0/test-1"));
    }

    @Test
    public void aCommandThatThrowsIsIgnoredAndTheServerGoesOn() throws Exception {
        // A port no socket can have: the reset throws IllegalArgumentException.
        System.setProperty(JACOCO_PORT_PROPERTY, "70000");
        assertTrue(KeployDedupAgent.start());
        assertEquals(IGNORED, send("START test-set-0/test-1"));
        assertEquals("the server still answers", IGNORED, send("END test-set-0/test-1"));
        assertTrue(KeployDedupAgent.isStarted());
    }

    @Test(timeout = 60000)
    public void stopWaitsForTheCommandInFlightAndForItsThread() throws Exception {
        startWithJacoco();
        jacoco.holdMillis = 1000;
        BlockingQueue<String> answer = new LinkedBlockingQueue<>();
        Thread client = new Thread(() -> {
            try {
                answer.add(send("START test-set-0/test-1"));
            } catch (IOException e) {
                answer.add(e.toString());
            }
        });
        client.start();
        // The START is in the SDK, waiting on JaCoCo, when stop() is called.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (jacocoConnections() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        KeployDedupAgent.stop();
        // Before, stop() closed the socket and returned at once, with the
        // thread still inside the START.
        assertFalse("the control thread ended before stop() returned", controlThreadAlive());
        assertEquals("the START was answered", TAKEN, answer.poll(30, TimeUnit.SECONDS));
        client.join();
    }

    @Test(timeout = 60000)
    public void aStartAfterStopOnTheSamePathKeepsItsSocket() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertTrue(KeployDedupAgent.start());
            assertEquals("round " + i, IGNORED, send("END test-set-0/test-1"));
            KeployDedupAgent.stop();
            assertTrue(KeployDedupAgent.start());
            // Bound by the closing server's thread, the file was gone or its
            // socket closed: these were refused.
            assertEquals("round " + i, IGNORED, send("END test-set-0/test-1"));
            assertEquals("round " + i, IGNORED, send("END test-set-0/test-1"));
            KeployDedupAgent.stop();
        }
    }

    @Test(timeout = 60000)
    public void aSocketFileDeletedUnderTheServerEndsItOnceAndSaysSo() throws Exception {
        startWithJacoco();
        jacoco.holdMillis = 500;
        BlockingQueue<String> answer = new LinkedBlockingQueue<>();
        Thread client = new Thread(() -> {
            try {
                answer.add(send("START test-set-0/test-1"));
            } catch (IOException e) {
                answer.add(e.toString());
            }
        });
        client.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (jacocoConnections() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        // Deleted while the START runs: the next accept finds the file gone,
        // and junixsocket closes the socket.
        assertTrue(new File(KeployDedupAgent.controlSocketPath).delete());
        // A server that loops on the closed socket logs without end: kept
        // off the console, so that it fails this row rather than the run.
        Logger.getLogger(KeployDedupAgent.class.getName()).setUseParentHandlers(false);
        assertEquals(TAKEN, answer.poll(30, TimeUnit.SECONDS));
        long ends = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((KeployDedupAgent.isStarted() || controlThreadAlive()) && System.nanoTime() < ends) {
            Thread.sleep(5);
        }
        // Before, the accept loop went on on the closed socket: it spun a core
        // and logged without end, and the agent still said it was started.
        assertFalse("the agent says it is no longer started", KeployDedupAgent.isStarted());
        assertFalse("the control thread ended", controlThreadAlive());
        assertEquals("said once, and nothing else at SEVERE: " + severe, 1, severe.size());
        assertTrue(severe.get(0), severe.get(0).contains("was closed: its file was deleted or replaced"));
        client.join();
    }

    @Test(timeout = 60000)
    public void aStopDuringACommandLeavesTheSocketToItsOwnThread() throws Exception {
        startWithJacoco();
        jacoco.holdMillis = 1500;
        BlockingQueue<String> answer = new LinkedBlockingQueue<>();
        Thread client = new Thread(() -> {
            try {
                answer.add(send("START test-set-0/test-1"));
            } catch (IOException e) {
                answer.add(e.toString());
            }
        });
        client.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (jacocoConnections() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        Thread stopper = new Thread(KeployDedupAgent::stop);
        stopper.start();
        Thread.sleep(300);
        // stop() is waiting for the START. The socket is still open, as only
        // its own thread closes it: closed from here, junixsocket's accept on
        // that thread could close whatever socket took its fd number next.
        assertTrue("stop() is still waiting for the command", stopper.isAlive());
        File socket = new File(KeployDedupAgent.controlSocketPath);
        assertTrue("the socket file is still there", socket.exists());
        assertConnects("the socket still listens", socket);
        stopper.join();
        assertEquals("the START was answered", TAKEN, answer.poll(30, TimeUnit.SECONDS));
        assertFalse("the control thread ended", controlThreadAlive());
        assertFalse("its own thread deleted the socket file", socket.exists());
        assertEquals("nothing at SEVERE: " + severe, 0, severe.size());
        client.join();
    }

    @Test(timeout = 60000)
    public void aStopAfterAnotherServerTookThePathEndsTheThreadAndLeavesTheirSocket() throws Exception {
        KeployDedupAgent.workerStopWaitMillis = 300;
        assertTrue(KeployDedupAgent.start());
        assertEquals(IGNORED, send("END test-set-0/test-1"));
        Thread.sleep(100);
        severe.clear();
        // Another server takes the path while the control thread waits in its
        // accept: close()'s wake-up reaches that server, not this one.
        File socket = new File(KeployDedupAgent.controlSocketPath);
        assertTrue(socket.delete());
        foreign = AFUNIXServerSocket.newInstance();
        foreign.bind(AFUNIXSocketAddress.of(socket));
        KeployDedupAgent.stop();
        assertFalse(KeployDedupAgent.isStarted());
        // stop() closed the socket once its wait was over: the thread ends.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (controlThreadAlive() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertFalse("the control thread ended", controlThreadAlive());
        assertTrue("the other server's socket file is left alone", socket.exists());
        assertConnects("the other server still listens", socket);
        assertEquals("a stop says nothing at SEVERE: " + severe, 0, severe.size());
    }

    @Test(timeout = 60000)
    public void aStopAfterTheSocketFileWasDeletedEndsAtOnce() throws Exception {
        KeployDedupAgent.workerStopWaitMillis = 20000;
        assertTrue(KeployDedupAgent.start());
        assertEquals(IGNORED, send("END test-set-0/test-1"));
        Thread.sleep(100);
        assertTrue(new File(KeployDedupAgent.controlSocketPath).delete());
        long began = System.nanoTime();
        KeployDedupAgent.stop();
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
        // No wake-up can reach the accept, so close() closes the socket at
        // once; left to stop()'s second try, it took the whole wait.
        assertTrue("stop() took " + tookMillis + " ms", tookMillis < 10000);
        assertFalse("the control thread ended", controlThreadAlive());
    }

    @Test(timeout = 60000)
    public void aStartWhileTheStoppedWorkerStillRunsACommandIsRefused() throws Exception {
        KeployDedupAgent.workerStopWaitMillis = 100;
        startWithJacoco();
        // Under the SDK's 3 s read timeout, so the START is answered ACK.
        jacoco.holdMillis = 2000;
        BlockingQueue<String> answer = new LinkedBlockingQueue<>();
        Thread client = new Thread(() -> {
            try {
                answer.add(send("START test-set-0/test-1"));
            } catch (IOException e) {
                answer.add(e.toString());
            }
        });
        client.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (jacocoConnections() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        KeployDedupAgent.stop();
        assertTrue("stop() gave up while the START runs", controlThreadAlive());
        // A second server on the path now would lose its socket file when
        // the first one's thread ends and closes its own.
        assertFalse("start() refuses while the old worker runs", KeployDedupAgent.start());
        assertFalse(KeployDedupAgent.isStarted());
        assertEquals("the START was answered", TAKEN, answer.poll(30, TimeUnit.SECONDS));
        while (controlThreadAlive() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue("start() binds once the old worker has ended", KeployDedupAgent.start());
        assertEquals(IGNORED, send("END test-set-0/test-1"));
        client.join();
    }

    private static void assertConnects(String what, File socket) {
        try (AFUNIXSocket probe = AFUNIXSocket.newInstance()) {
            probe.connect(AFUNIXSocketAddress.of(socket), 3000);
        } catch (IOException e) {
            throw new AssertionError(what + ": " + e, e);
        }
    }

    private int jacocoConnections() {
        return jacoco.accepted.get();
    }

    private static boolean controlThreadAlive() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals("keploy-java-dedup-control") && t.isAlive()) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void theIndexReportReachesTheReceiverOnceBeforeAnyCommand() throws Exception {
        data = new DataReceiver(KeployDedupAgent.dataSocketPath);
        startWithJacoco();
        String index = data.indexes.poll(30, TimeUnit.SECONDS);
        assertNotNull("the INDEX report came with no command sent", index);
        assertEquals(KeployDedupAgent.indexReport(), index);
        assertEquals(TAKEN, send("START test-set-0/test-1"));
        assertEquals(TAKEN, send("END test-set-0/test-1"));
        assertTrue(data.next().contains("\"id\":\"test-set-0/test-1\""));
        assertEquals(TAKEN, send("START test-set-0/test-2"));
        assertEquals(TAKEN, send("END test-set-0/test-2"));
        assertTrue(data.next().contains("\"id\":\"test-set-0/test-2\""));
        assertNull("one INDEX report for one receiver", data.indexes.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    public void aReceiverBoundAnewGetsTheIndexReportBeforeItsFirstRecord() throws Exception {
        startWithJacoco();
        // No receiver yet: the report sent once the socket was bound reached
        // no one, and an ignored END publishes nothing, the report included.
        assertEquals(IGNORED, send("END test-set-0/test-0"));
        data = new DataReceiver(KeployDedupAgent.dataSocketPath);
        assertNull(data.all.poll(200, TimeUnit.MILLISECONDS));
        assertEquals(TAKEN, send("START test-set-0/test-1"));
        assertEquals(TAKEN, send("END test-set-0/test-1"));
        assertEquals(KeployDedupAgent.indexReport(), data.all.poll(30, TimeUnit.SECONDS));
        assertTrue(data.all.poll(30, TimeUnit.SECONDS).contains("\"id\":\"test-set-0/test-1\""));

        // The next test set's receiver (docker compose), on the same path.
        data.close();
        // Keploy unlinks the old receiver's file before it binds the next.
        new File(KeployDedupAgent.dataSocketPath).delete();
        data = new DataReceiver(KeployDedupAgent.dataSocketPath);
        assertEquals(TAKEN, send("START test-set-1/test-1"));
        assertEquals(TAKEN, send("END test-set-1/test-1"));
        assertEquals(KeployDedupAgent.indexReport(), data.all.poll(30, TimeUnit.SECONDS));
        assertTrue(data.all.poll(30, TimeUnit.SECONDS).contains("\"id\":\"test-set-1/test-1\""));
        assertEquals(TAKEN, send("START test-set-1/test-2"));
        assertEquals(TAKEN, send("END test-set-1/test-2"));
        assertTrue(data.all.poll(30, TimeUnit.SECONDS).contains("\"id\":\"test-set-1/test-2\""));
        assertNull(data.all.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    public void aReceiverIsToldApartByItsFileAndTheTimeItWasMade() throws Exception {
        // A file system can give a new socket file the inode of one just
        // removed: the time it was made tells the two receivers apart.
        File file = new File(dir, "receiver");
        Files.write(file.toPath(), new byte[] {1});
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(1_000_000_000_000L));
        Object key = KeployDedupAgent.receiverKey(file);
        assertNotNull(key);
        assertEquals(key, KeployDedupAgent.receiverKey(file));
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(1_000_000_001_000L));
        assertNotEquals(key, KeployDedupAgent.receiverKey(file));
        // Two receivers bound within one tick of the clock share a time: the
        // file's identity tells them apart.
        File other = new File(dir, "other");
        Files.write(other.toPath(), new byte[] {1});
        Files.setLastModifiedTime(other.toPath(), FileTime.fromMillis(1_000_000_001_000L));
        assertNotEquals(KeployDedupAgent.receiverKey(file), KeployDedupAgent.receiverKey(other));
        assertTrue(file.delete());
        assertNull("no file, no receiver", KeployDedupAgent.receiverKey(file));
    }

    private void startWithJacoco() throws IOException {
        jacoco = new FakeJacoco();
        System.setProperty(JACOCO_PORT_PROPERTY, String.valueOf(jacoco.server.getLocalPort()));
        assertTrue(KeployDedupAgent.start());
    }

    private static String send(String command) throws IOException {
        return sendBytes((command + "\n").getBytes(StandardCharsets.UTF_8));
    }

    // sendBytes sends raw as one command, as Keploy does, and returns all the
    // SDK answered before its close.
    private static String sendBytes(byte[] raw) throws IOException {
        try (AFUNIXSocket client = AFUNIXSocket.newInstance()) {
            client.connect(AFUNIXSocketAddress.of(new File(KeployDedupAgent.controlSocketPath)), 3000);
            client.setSoTimeout(30000);
            OutputStream out = client.getOutputStream();
            out.write(raw);
            out.flush();
            return readAll(client.getInputStream());
        }
    }

    // sendThenCloseOutput sends raw, closes its own side, and returns all the
    // SDK answered before its close. A channel: on JDK 21 java.net.Socket's
    // shutdownOutput refuses a junixsocket socket as not connected.
    private static String sendThenCloseOutput(byte[] raw) throws IOException {
        try (AFUNIXSocketChannel client = AFUNIXSocketChannel.open(
                AFUNIXSocketAddress.of(new File(KeployDedupAgent.controlSocketPath)))) {
            ByteBuffer out = ByteBuffer.wrap(raw);
            while (out.hasRemaining()) {
                client.write(out);
            }
            client.shutdownOutput();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            ByteBuffer in = ByteBuffer.allocate(512);
            while (client.read(in) != -1) {
                all.write(in.array(), 0, in.position());
                in.clear();
            }
            return new String(all.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buf = new byte[512];
        int n;
        while ((n = in.read(buf)) != -1) {
            all.write(buf, 0, n);
        }
        return new String(all.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Keploy's data socket: it keeps each payload sent to it, the tests'
     * coverage records (next, poll) apart from the INDEX reports (indexes),
     * and all of them in the order they came (all).
     */
    private static final class DataReceiver implements Closeable {

        final AFUNIXServerSocket server;
        final BlockingQueue<String> payloads = new LinkedBlockingQueue<>();
        final BlockingQueue<String> indexes = new LinkedBlockingQueue<>();
        final BlockingQueue<String> all = new LinkedBlockingQueue<>();

        DataReceiver(String path) throws IOException {
            server = AFUNIXServerSocket.newInstance();
            server.bind(AFUNIXSocketAddress.of(new File(path)), 50);
            Thread thread = new Thread(this::serve, "data-receiver");
            thread.setDaemon(true);
            thread.start();
        }

        private void serve() {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(30000);
                    String payload = readAll(socket.getInputStream());
                    if (payload.startsWith("KTB1")) {
                        // A TABLE frame waits for OK before the next one goes.
                        socket.getOutputStream().write("OK\n".getBytes(StandardCharsets.UTF_8));
                    }
                    all.add(payload);
                    (payload.startsWith(INDEX_START) ? indexes : payloads).add(payload);
                } catch (IOException e) {
                    // Closed.
                }
            }
        }

        // next waits for the next payload. The SDK sends it before it answers
        // the END, so it is there, or on its way, once the END is answered.
        String next() throws InterruptedException {
            return payloads.poll(30, TimeUnit.SECONDS);
        }

        // poll returns a payload already sent, or null.
        String poll() throws InterruptedException {
            return payloads.poll(200, TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
