package io.keploy.dedup;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jacoco.core.data.ExecutionData;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The TCP transport, with the test as the collector the SDK dials (k8s). A
 * command the SDK does not take (an END with no START, a START whose reset
 * failed) publishes no COV, so it must say which test went unmeasured, and must
 * still answer ACK, which is what ends the collector's wait.
 */
public class CoverageTcpClientTest {

    private static final String ENDPOINT_PROPERTY = "keploy.coverage.endpoint";
    private static final String JACOCO_PORT_PROPERTY = "keploy.jacoco.port";
    private static final String WARMUP_DISABLED_PROPERTY = "keploy.java.dedup.warmup.disabled";
    private static final String CLASS_DIRS_PROPERTY = "keploy.java.class.dirs";
    private static final String BUILD_TAG_PROPERTY = "keploy.build.tag";
    private static final String END_WAIT_PROPERTY = "keploy.java.end.wait.ms";
    private static final int ROUND_TRIPS = 25;
    // Linux delays an ACK at least 40 ms; an END answered at once takes a few ms.
    private static final long END_ROUND_TRIP_BOUND_MICROS = 20_000;
    // The SDK's reply buffer is 64 KiB. Its thread serving Keploy allocates
    // well under this much (about 2.7 MB on JDK 21) in a connection with a
    // CLASSES frame of about 137 KB (100 KB of class bytes) and 25 round
    // trips; a buffer sized for a large app's CLASSES frame (tens of MiB) is
    // over it, while a buffer of a few MiB would pass.
    private static final long CONNECTION_ALLOCATION_BOUND_BYTES = 16L << 20;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ServerSocket collector;
    private FakeJacoco jacoco;
    private ClassLoader savedContextLoader;

    @Before
    public void setUp() throws IOException {
        collector = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        collector.setSoTimeout(30000);
        System.setProperty(ENDPOINT_PROPERTY, "127.0.0.1:" + collector.getLocalPort());
        // The warmup loads every class on the test classpath; nothing here needs it.
        System.setProperty(WARMUP_DISABLED_PROPERTY, "true");
    }

    @After
    public void tearDown() throws IOException {
        KeployDedupAgent.stop();
        if (savedContextLoader != null) {
            Thread.currentThread().setContextClassLoader(savedContextLoader);
        }
        System.clearProperty(ENDPOINT_PROPERTY);
        System.clearProperty(JACOCO_PORT_PROPERTY);
        System.clearProperty(WARMUP_DISABLED_PROPERTY);
        System.clearProperty(CLASS_DIRS_PROPERTY);
        System.clearProperty(BUILD_TAG_PROPERTY);
        System.clearProperty(END_WAIT_PROPERTY);
        collector.close();
        if (jacoco != null) {
            jacoco.close();
        }
    }

    @Test
    public void endWithNoStartNamesTheTestThenAnswersAck() throws Exception {
        assertTrue(KeployDedupAgent.start());
        try (Socket sdk = collector.accept()) {
            sdk.setSoTimeout(30000);
            OutputStream out = sdk.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));
            assertEquals(KeployDedupAgent.HELLO_FRAME, in.readLine());

            // Two, so that a missing ACK shows as the next test's frame in its place.
            out.write("END test-set-0/test-47\nEND test-set-0/test-48\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            assertEquals("IGNORED test-set-0/test-47", in.readLine());
            assertEquals("ACK test-set-0/test-47", in.readLine());
            assertEquals("IGNORED test-set-0/test-48", in.readLine());
            assertEquals("ACK test-set-0/test-48", in.readLine());
        }
    }

    @Test
    public void aStartWhoseResetFailedNamesTheTestThenAnswersAckAndSoDoesItsEnd() throws Exception {
        jacoco = new FakeJacoco();
        System.setProperty(JACOCO_PORT_PROPERTY, String.valueOf(jacoco.server.getLocalPort()));
        assertTrue(KeployDedupAgent.start());
        try (Socket sdk = collector.accept()) {
            sdk.setSoTimeout(30000);
            OutputStream out = sdk.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));
            assertEquals(KeployDedupAgent.HELLO_FRAME, in.readLine());

            send(out, "START test-set-0/test-1");
            assertEquals("ACK test-set-0/test-1", in.readLine());
            // JaCoCo goes away for the next reset: the counters still hold
            // test-1's lines, so test-2 is not measured.
            jacoco.dropNext.set(1);
            send(out, "START test-set-0/test-2");
            assertEquals("IGNORED test-set-0/test-2", in.readLine());
            assertEquals("ACK test-set-0/test-2", in.readLine());
            // test-1 was left when test-2 started, and test-2 never began.
            send(out, "END test-set-0/test-1");
            assertEquals("IGNORED test-set-0/test-1", in.readLine());
            assertEquals("ACK test-set-0/test-1", in.readLine());
            send(out, "END test-set-0/test-2");
            assertEquals("IGNORED test-set-0/test-2", in.readLine());
            assertEquals("ACK test-set-0/test-2", in.readLine());

            // JaCoCo is back: the next test is measured as before.
            send(out, "START test-set-0/test-3");
            assertEquals("ACK test-set-0/test-3", in.readLine());
            send(out, "END test-set-0/test-3");
            String cov = in.readLine();
            assertTrue("a COV for test-3, got " + cov,
                    cov != null && cov.startsWith("COV ") && cov.contains("test-set-0/test-3"));
            assertEquals("ACK test-set-0/test-3", in.readLine());
        }
    }

    @Test
    public void anEndWhoseDumpFailedNamesTheTestThenAnswersAckAndEndsTheTest() throws Exception {
        jacoco = new FakeJacoco();
        System.setProperty(JACOCO_PORT_PROPERTY, String.valueOf(jacoco.server.getLocalPort()));
        assertTrue(KeployDedupAgent.start());
        try (Socket sdk = collector.accept()) {
            sdk.setSoTimeout(30000);
            OutputStream out = sdk.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));
            assertEquals(KeployDedupAgent.HELLO_FRAME, in.readLine());

            send(out, "START test-set-0/test-1");
            assertEquals("ACK test-set-0/test-1", in.readLine());
            // JaCoCo goes away for the dump: test-1's lines never reach the
            // SDK. Before, it sent an empty COV, as for a test that ran no
            // line of the app.
            jacoco.dropNext.set(1);
            send(out, "END test-set-0/test-1");
            assertEquals("IGNORED test-set-0/test-1", in.readLine());
            assertEquals("ACK test-set-0/test-1", in.readLine());
            // That END ended test-1.
            send(out, "END test-set-0/test-1");
            assertEquals("IGNORED test-set-0/test-1", in.readLine());
            assertEquals("ACK test-set-0/test-1", in.readLine());

            send(out, "START test-set-0/test-2");
            assertEquals("ACK test-set-0/test-2", in.readLine());
            send(out, "END test-set-0/test-2");
            String cov = in.readLine();
            assertTrue("a COV for test-2, got " + cov,
                    cov != null && cov.startsWith("COV ") && cov.contains("test-set-0/test-2"));
            assertEquals("ACK test-set-0/test-2", in.readLine());
        }
    }

    @Test
    public void aStartThatThrowsNamesTheTestThenAnswersAckAndTheConnectionGoesOn() throws Exception {
        // A port no socket can have: the reset throws IllegalArgumentException.
        System.setProperty(JACOCO_PORT_PROPERTY, "70000");
        assertTrue(KeployDedupAgent.start());
        try (Socket sdk = collector.accept()) {
            sdk.setSoTimeout(30000);
            OutputStream out = sdk.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));
            assertEquals(KeployDedupAgent.HELLO_FRAME, in.readLine());

            send(out, "START test-set-0/test-1");
            assertEquals("IGNORED test-set-0/test-1", in.readLine());
            assertEquals("ACK test-set-0/test-1", in.readLine());
            send(out, "END test-set-0/test-1");
            assertEquals("the connection still answers", "IGNORED test-set-0/test-1", in.readLine());
            assertEquals("ACK test-set-0/test-1", in.readLine());
        }
    }

    @Test
    public void theManifestWritesAClassIdAs16HexDigitsAndTheCovNamesAClassJacocoSawOtherBytesOf()
            throws Exception {
        File shop = new File(tmp.newFolder("classes"), "com/acme/Shop.class");
        shop.getParentFile().mkdirs();
        Files.write(shop.toPath(), new byte[] {1});
        System.setProperty(CLASS_DIRS_PROPERTY, shop.getParentFile().getParentFile().getParent());
        System.setProperty(BUILD_TAG_PROPERTY, "tag");
        jacoco = new FakeJacoco();
        System.setProperty(JACOCO_PORT_PROPERTY, String.valueOf(jacoco.server.getLocalPort()));
        // JaCoCo saw other bytes of Shop than the index reads: its id is not
        // theirs, and has fewer than 16 hex digits.
        jacoco.hit = new ExecutionData(0x99L, "com/acme/Shop", new boolean[] {true, false});
        assertTrue(KeployDedupAgent.start());
        try (Socket sdk = collector.accept()) {
            sdk.setSoTimeout(30000);
            OutputStream out = sdk.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));
            assertEquals(KeployDedupAgent.HELLO_FRAME, in.readLine());

            send(out, "START test-set-0/test-1");
            assertEquals("ACK test-set-0/test-1", in.readLine());
            send(out, "END test-set-0/test-1");
            assertEquals("COV {\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{\"com/acme/Shop\":[0]},"
                    + "\"classMismatch\":[\"com/acme/Shop\"]}", in.readLine());
            String classes = in.readLine();
            assertTrue(classes, classes.startsWith("CLASSES "));
            String manifest = new String(Base64.getDecoder().decode(classes.split(" ")[2]), StandardCharsets.UTF_8);
            assertEquals("{\"com/acme/Shop\":{\"id\":\"0000000000000099\",\"probeCount\":2}}", manifest);
            assertEquals("ACK test-set-0/test-1", in.readLine());
        }
    }

    @Test
    public void aCutEndSaysSoInItsCovAfterClassMismatch() throws Exception {
        File shop = new File(tmp.newFolder("classes"), "com/acme/Shop.class");
        shop.getParentFile().mkdirs();
        Files.write(shop.toPath(), new byte[] {1});
        System.setProperty(CLASS_DIRS_PROPERTY, shop.getParentFile().getParentFile().getParent());
        System.setProperty(END_WAIT_PROPERTY, "50");
        jacoco = new FakeJacoco();
        System.setProperty(JACOCO_PORT_PROPERTY, String.valueOf(jacoco.server.getLocalPort()));
        jacoco.hit = new ExecutionData(0x99L, "com/acme/Shop", new boolean[] {true, false});
        assertTrue(KeployDedupAgent.start());
        try (Socket sdk = collector.accept()) {
            sdk.setSoTimeout(30000);
            OutputStream out = sdk.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));
            assertEquals(KeployDedupAgent.HELLO_FRAME, in.readLine());

            send(out, "START test-set-0/test-1");
            assertEquals("ACK test-set-0/test-1", in.readLine());
            // The test's request is still running when its END comes, and
            // past END's wait.
            Object request = RequestGate.enter();
            try {
                send(out, "END test-set-0/test-1");
                assertEquals("COV {\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{\"com/acme/Shop\":[0]},"
                        + "\"classMismatch\":[\"com/acme/Shop\"],\"windowCut\":true}", in.readLine());
                assertEquals("ACK test-set-0/test-1", in.readLine());
            } finally {
                RequestGate.exit(request);
            }
            // Its request done, the next test's END is not cut.
            send(out, "START test-set-0/test-2");
            assertEquals("ACK test-set-0/test-2", in.readLine());
            send(out, "END test-set-0/test-2");
            String cov = in.readLine();
            assertTrue(cov, cov.startsWith("COV ") && !cov.contains("windowCut"));
            assertEquals("ACK test-set-0/test-2", in.readLine());
        }
    }

    // Keploy waits on each reply. Sent in two writes (COV, then ACK), the ACK
    // waited in the SDK's kernel (Nagle) until Keploy's kernel acknowledged the
    // COV, which it delays by about 40 ms: once per test.
    @Test
    public void anEndIsAnsweredWithoutWaitingForTheCollectorsDelayedAck() throws Exception {
        inProcessJacoco();
        assertTrue(KeployDedupAgent.start());
        try (Socket sdk = collector.accept()) {
            sdk.setSoTimeout(30000);
            OutputStream out = sdk.getOutputStream();
            InputStream in = sdk.getInputStream();
            assertEquals(KeployDedupAgent.HELLO_FRAME + "\n", readLineRaw(in));
            long[] micros = new long[ROUND_TRIPS];
            for (int i = 0; i < ROUND_TRIPS; i++) {
                String id = "test-set-0/test-" + i;
                send(out, "START " + id);
                assertEquals("ACK " + id + "\n", readReply(in));
                long sent = System.nanoTime();
                send(out, "END " + id);
                String reply = readReply(in);
                micros[i] = (System.nanoTime() - sent) / 1000;
                assertTrue(reply, reply.startsWith("COV {\"id\":\"" + id + "\"")
                        && reply.endsWith("\nACK " + id + "\n"));
            }
            long[] sorted = micros.clone();
            Arrays.sort(sorted);
            assertTrue("median END round trip " + sorted[ROUND_TRIPS / 2] + " us, each: " + Arrays.toString(micros),
                    sorted[ROUND_TRIPS / 2] < END_ROUND_TRIP_BOUND_MICROS);
        }
    }

    // Each reply leaves in one write: the read that wakes on its first byte
    // returns all of it. A reply sent a line at a time is read in parts.
    @Test
    public void eachReplyIsReadInOnePiece() throws Exception {
        inProcessJacoco();
        assertTrue(KeployDedupAgent.start());
        try (Socket sdk = collector.accept()) {
            sdk.setSoTimeout(30000);
            OutputStream out = sdk.getOutputStream();
            InputStream in = sdk.getInputStream();
            assertEquals(KeployDedupAgent.HELLO_FRAME + "\n", readLineRaw(in));
            byte[] buf = new byte[64 * 1024];
            for (int i = 0; i < ROUND_TRIPS; i++) {
                String id = "test-set-0/test-" + i;
                send(out, "START " + id);
                assertEquals("ACK " + id + "\n", readOnce(in, buf));
                send(out, "END " + id);
                String reply = readOnce(in, buf);
                assertTrue("round " + i + " read " + reply,
                        reply.startsWith("COV {\"id\":\"" + id + "\"") && reply.endsWith("}\nACK " + id + "\n"));
                // Not taken (an END with no START): the IGNORED line and the ACK.
                send(out, "END " + id);
                assertEquals("IGNORED " + id + "\nACK " + id + "\n", readOnce(in, buf));
            }
        }
    }

    // The socket to Keploy has Nagle off, so a reply that does not fit in one
    // write (one with a CLASSES or MANIFEST frame larger than the reply
    // buffer) does not wait between its writes.
    @Test
    public void theSocketToKeployHasNagleOff() throws Exception {
        assertTrue(KeployDedupAgent.start());
        try (Socket sdk = collector.accept()) {
            sdk.setSoTimeout(30000);
            OutputStream out = sdk.getOutputStream();
            assertEquals(KeployDedupAgent.HELLO_FRAME + "\n", readLineRaw(sdk.getInputStream()));
            send(out, "END test-set-0/test-1");
            assertEquals("IGNORED test-set-0/test-1\nACK test-set-0/test-1\n", readReply(sdk.getInputStream()));
            Field worker = KeployDedupAgent.class.getDeclaredField("coverageWorker");
            worker.setAccessible(true);
            Object client = worker.get(null);
            Field socket = client.getClass().getDeclaredField("socket");
            socket.setAccessible(true);
            assertTrue("TCP_NODELAY on the SDK's socket to Keploy", ((Socket) socket.get(client)).getTcpNoDelay());
        }
    }

    // A frame larger than the reply buffer (here a CLASSES frame of about
    // 137 KB, from 100 KB of class bytes that do not compress) arrives whole,
    // the ACK follows on a line of its own, and the round trips after it stay
    // fast. The thread that serves Keploy allocates under 16 MiB in all, so it
    // holds no buffer sized for a large app's frame (tens of MiB); a buffer of
    // a few MiB would pass.
    @Test
    public void aFrameLargerThanTheReplyBufferArrivesWholeAndTheRepliesAfterItStayFast() throws Exception {
        byte[] classBytes = new byte[100 * 1024];
        new Random(7).nextBytes(classBytes);
        File big = new File(tmp.newFolder("classes"), "com/acme/Big.class");
        big.getParentFile().mkdirs();
        Files.write(big.toPath(), classBytes);
        System.setProperty(CLASS_DIRS_PROPERTY, big.getParentFile().getParentFile().getParent());
        System.setProperty(BUILD_TAG_PROPERTY, "tag");
        boolean[] probes = inProcessJacoco().probes(IndexReport.classId(classBytes), "com/acme/Big", 2);
        assertTrue(KeployDedupAgent.start());
        try (Socket sdk = collector.accept()) {
            sdk.setSoTimeout(30000);
            OutputStream out = sdk.getOutputStream();
            InputStream in = sdk.getInputStream();
            assertEquals(KeployDedupAgent.HELLO_FRAME + "\n", readLineRaw(in));
            send(out, "START test-set-0/test-0");
            assertEquals("ACK test-set-0/test-0\n", readReply(in));
            probes[0] = true;
            send(out, "END test-set-0/test-0");
            String reply = readReply(in);
            String[] lines = reply.split("\n", -1);
            assertEquals("COV, CLASSES, ACK and the end of the reply; lines of "
                    + lengths(lines), 4, lines.length);
            assertEquals("COV {\"id\":\"test-set-0/test-0\",\"executedLinesByFile\":{\"com/acme/Big\":[0]}}",
                    lines[0]);
            String[] classes = lines[1].split(" ");
            assertEquals("CLASSES", classes[0]);
            assertTrue("the frame is larger than the reply buffer: " + lines[1].length() + " bytes",
                    lines[1].length() > 64 * 1024);
            assertArrayEquals(classBytes, unzipped(Base64.getDecoder().decode(classes[3]), "com/acme/Big.class"));
            assertEquals("ACK test-set-0/test-0", lines[2]);
            assertEquals("", lines[3]);

            long[] micros = new long[ROUND_TRIPS];
            for (int i = 1; i <= ROUND_TRIPS; i++) {
                String id = "test-set-0/test-" + i;
                send(out, "START " + id);
                assertEquals("ACK " + id + "\n", readReply(in));
                long sent = System.nanoTime();
                send(out, "END " + id);
                reply = readReply(in);
                micros[i - 1] = (System.nanoTime() - sent) / 1000;
                assertTrue(reply, reply.startsWith("COV {\"id\":\"" + id + "\"")
                        && reply.endsWith("}\nACK " + id + "\n"));
            }
            long[] sorted = micros.clone();
            Arrays.sort(sorted);
            assertTrue("median END round trip " + sorted[ROUND_TRIPS / 2] + " us, each: " + Arrays.toString(micros),
                    sorted[ROUND_TRIPS / 2] < END_ROUND_TRIP_BOUND_MICROS);

            long allocated = allocatedByTheSdksThread();
            assertTrue("the SDK's thread serving Keploy allocated " + allocated + " bytes",
                    allocated < CONNECTION_ALLOCATION_BOUND_BYTES);
        }
    }

    // END writes its COV into the reply before it builds the CLASSES frame, so
    // a failure there (here, reading the build tag throws) skips only that
    // frame: Keploy still gets the COV, then the ACK. The row also checks that
    // the failure reached END's own handler, so it fails, and asks for another
    // fault, once this one stops escaping the frame's build.
    @Test(timeout = 60000)
    public void aJobThatStartsWhileEndWaitsForTheRequestsOrReadsTheHitsIsNamed() throws Exception {
        // The longest wait: END waits for the request until this row lets it go.
        System.setProperty(END_WAIT_PROPERTY, "2000");
        jacoco = new FakeJacoco();
        System.setProperty(JACOCO_PORT_PROPERTY, String.valueOf(jacoco.server.getLocalPort()));
        assertTrue(KeployDedupAgent.start());
        try (Socket sdk = collector.accept()) {
            sdk.setSoTimeout(30000);
            OutputStream out = sdk.getOutputStream();
            InputStream in = sdk.getInputStream();
            assertEquals(KeployDedupAgent.HELLO_FRAME + "\n", readLineRaw(in));
            send(out, "START test-set-0/test-1");
            assertEquals("ACK test-set-0/test-1\n", readReply(in));
            jacoco.onDump = () -> JobGate.exit(JobGate.enter(new Object(), "scheduled"));
            Object request = RequestGate.enter();
            try {
                send(out, "END test-set-0/test-1");
                // END took the window it waits on: the request no longer counts in the current one.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (RequestGate.running() != 0) {
                    assertTrue("END never waited for the request", System.nanoTime() < deadline);
                    Thread.sleep(1);
                }
                JobGate.exit(JobGate.enter(new Object(), "quartz"));
            } finally {
                RequestGate.exit(request);
            }
            String reply = readReply(in);
            assertFalse("END did not reach its bound: " + reply, reply.contains("windowCut"));
            assertTrue(reply, reply.endsWith(
                    ",\"contaminated\":[\"quartz:java.lang.Object\",\"scheduled:java.lang.Object\"]}\nACK test-set-0/test-1\n"));
        }
    }

    @Test(timeout = 60000)
    public void anEndWhoseClassesFrameFailsStillSendsItsCovThenAckAndWarnsOncePerConnection() throws Exception {
        assumeTrue("the build tag is read from the environment", System.getenv("KEPLOY_BUILD_TAG") == null);
        inProcessJacoco();
        assertTrue(KeployDedupAgent.start());
        Properties jvm = System.getProperties();
        Logger logger = Logger.getLogger(KeployDedupAgent.class.getName());
        Level level = logger.getLevel();
        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.setLevel(Level.FINE);
        logger.addHandler(handler);
        BuildTagUnreadable unreadable = new BuildTagUnreadable(jvm);
        try {
            // Two tests on a connection, then one on the next: each connection warns once, at its first.
            for (int connection = 1, test = 1; connection <= 2; connection++) {
                try (Socket sdk = collector.accept()) {
                    sdk.setSoTimeout(30000);
                    OutputStream out = sdk.getOutputStream();
                    InputStream in = sdk.getInputStream();
                    assertEquals(KeployDedupAgent.HELLO_FRAME + "\n", readLineRaw(in));
                    for (int last = test + (connection == 1 ? 2 : 1); test < last; test++) {
                        String id = "test-set-0/test-" + test;
                        System.setProperties(jvm);
                        send(out, "START " + id);
                        assertEquals("ACK " + id + "\n", readReply(in));
                        System.setProperties(unreadable);
                        int thrown = unreadable.thrown.get();
                        send(out, "END " + id);
                        String reply = readReply(in);
                        assertTrue(reply, reply.startsWith("COV {\"id\":\"" + id + "\"")
                                && reply.endsWith("}\nACK " + id + "\n"));
                        assertEquals("COV, ACK and the end of the reply: " + reply, 3, reply.split("\n", -1).length);
                        assertTrue("the build tag's read threw", unreadable.thrown.get() > thrown);
                        assertEquals("connection " + connection + ", " + id + ": " + warnings(logged),
                                connection, warnings(logged).size());
                    }
                }
            }
            for (LogRecord record : warnings(logged)) {
                assertTrue(String.valueOf(record.getThrown()), record.getThrown() instanceof IllegalStateException
                        && "the build tag cannot be read".equals(record.getThrown().getMessage()));
            }
        } finally {
            System.setProperties(jvm);
            logger.removeHandler(handler);
            logger.setLevel(level);
        }
    }

    @Test(timeout = 60000)
    public void betweenTestsBackgroundProbesAreEmittedOnTcpOnlyWhenRequested() throws Exception {
        byte[] shopBytes = new byte[] {1, 2, 3};
        File shop = new File(tmp.newFolder("classes"), "com/acme/Shop.class");
        shop.getParentFile().mkdirs();
        Files.write(shop.toPath(), shopBytes);
        System.setProperty(CLASS_DIRS_PROPERTY, shop.getParentFile().getParentFile().getParent());
        System.setProperty(END_WAIT_PROPERTY, "30");
        boolean[] probes = inProcessJacoco().probes(IndexReport.classId(shopBytes), "com/acme/Shop", 4);
        assertTrue(KeployDedupAgent.start());
        try (Socket sdk = collector.accept()) {
            sdk.setSoTimeout(30000);
            OutputStream out = sdk.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));
            assertEquals(KeployDedupAgent.HELLO_FRAME, in.readLine());

            // Before requesting BACKGROUND: hits between tests are cleared, no BACKGROUND frame emitted.
            send(out, "START test-set-0/test-0");
            assertEquals("ACK test-set-0/test-0", in.readLine());
            probes[0] = true;
            send(out, "END test-set-0/test-0");
            assertEquals("COV {\"id\":\"test-set-0/test-0\",\"executedLinesByFile\":{\"com/acme/Shop\":[0]}}",
                    in.readLine());
            assertEquals("ACK test-set-0/test-0", in.readLine());

            probes[3] = true; // background hit before BACKGROUND opt-in
            send(out, "START test-set-0/test-1");
            assertEquals("ACK test-set-0/test-1", in.readLine());
            assertFalse("cleared even without BACKGROUND opt-in", probes[3]);

            // Opt in to BACKGROUND (no extra ACK emitted on TCP).
            send(out, "BACKGROUND");
            probes[1] = true;
            send(out, "END test-set-0/test-1");
            assertEquals("COV {\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{\"com/acme/Shop\":[1]}}",
                    in.readLine());
            assertEquals("ACK test-set-0/test-1", in.readLine());

            // Background job fires between END test-1 and START test-2.
            probes[3] = true;
            send(out, "START test-set-0/test-2");
            String bgLine = in.readLine();
            assertTrue("expected BACKGROUND frame before ACK, got: " + bgLine,
                    bgLine != null && bgLine.startsWith("BACKGROUND "));
            assertTrue(bgLine, bgLine.contains("\"kind\":\"background\""));
            assertTrue(bgLine, bgLine.contains("\"after\":\"test-set-0/test-1\""));
            assertTrue(bgLine, bgLine.contains("\"before\":\"test-set-0/test-2\""));
            assertTrue(bgLine, bgLine.contains("\"com/acme/Shop\":[3]"));
            assertEquals("ACK test-set-0/test-2", in.readLine());
            assertFalse("background probe cleared for test-2", probes[3]);

            probes[2] = true;
            send(out, "END test-set-0/test-2");
            assertEquals("COV {\"id\":\"test-set-0/test-2\",\"executedLinesByFile\":{\"com/acme/Shop\":[2]}}",
                    in.readLine());
            assertEquals("ACK test-set-0/test-2", in.readLine());

            // After a windowCut END, hits cleared by the next START are NOT reported as background.
            send(out, "START test-set-0/test-3");
            assertEquals("ACK test-set-0/test-3", in.readLine());
            Object req = RequestGate.enter();
            try {
                send(out, "END test-set-0/test-3");
                String cov3 = in.readLine();
                assertTrue(cov3, cov3.contains("\"windowCut\":true"));
                assertEquals("ACK test-set-0/test-3", in.readLine());
            } finally {
                RequestGate.exit(req);
            }
            probes[3] = true;
            send(out, "START test-set-0/test-4");
            assertEquals("no BACKGROUND frame after cut END", "ACK test-set-0/test-4", in.readLine());
        }
    }

    // warnings are the WARNING records that say a CLASSES frame could not be built.
    private static List<LogRecord> warnings(List<LogRecord> logged) {
        List<LogRecord> out = new java.util.ArrayList<>();
        for (LogRecord record : logged) {
            if (record.getLevel() == Level.WARNING && record.getMessage() != null
                    && record.getMessage().startsWith("Keploy dedup: could not build the CLASSES or MANIFEST frame")) {
                out.add(record);
            }
        }
        return out;
    }

    // The JVM's system properties, but a read of the build tag throws.
    private static final class BuildTagUnreadable extends Properties {

        private static final long serialVersionUID = 1L;

        final AtomicInteger thrown = new AtomicInteger();

        BuildTagUnreadable(Properties jvm) {
            super(jvm);
        }

        @Override
        public String getProperty(String key) {
            if (BUILD_TAG_PROPERTY.equals(key)) {
                thrown.incrementAndGet();
                throw new IllegalStateException("the build tag cannot be read");
            }
            return super.getProperty(key);
        }
    }

    // allocatedByTheSdksThread is what the SDK's thread serving Keploy has
    // allocated so far.
    private static long allocatedByTheSdksThread() throws ReflectiveOperationException {
        Field field = KeployDedupAgent.class.getDeclaredField("coverageThread");
        field.setAccessible(true);
        Thread thread = (Thread) field.get(null);
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long allocated = threads.getThreadAllocatedBytes(thread.getId());
        assertTrue("the JVM counts the SDK thread's allocation", allocated > 0);
        return allocated;
    }

    private static byte[] unzipped(byte[] zip, String name) throws IOException {
        try (ZipInputStream entries = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = entries.getNextEntry()) != null; ) {
                if (entry.getName().equals(name)) {
                    return readAll(entries);
                }
            }
        }
        throw new AssertionError(name + " is not in the zip");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            all.write(buf, 0, n);
        }
        return all.toByteArray();
    }

    private static String lengths(String[] lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line.length() > 60 ? line.substring(0, 60) + "... (" + line.length() + ")" : line)
                    .append(" | ");
        }
        return text.toString();
    }

    // inProcessJacoco has the SDK read coverage from a JaCoCo agent in this
    // JVM, as it reads its own JaCoCo's, so an END waits on no socket but
    // Keploy's. (A JaCoCo reached over TCP has sockets of its own.)
    private InProcessScanTest.Agent inProcessJacoco() throws ReflectiveOperationException {
        // A port nothing listens on: no command may reach JaCoCo over TCP.
        System.setProperty(JACOCO_PORT_PROPERTY, "1");
        savedContextLoader = Thread.currentThread().getContextClassLoader();
        InProcessScanTest.Agent agent = new InProcessScanTest.Agent();
        Thread.currentThread().setContextClassLoader(
                new InProcessScanTest.RtLoader(agent, getClass().getClassLoader()));
        return agent;
    }

    // readReply reads up to the end of a reply: its ACK line ("ACK <id>\n" or "ACK\n").
    private static String readReply(InputStream in) throws IOException {
        StringBuilder reply = new StringBuilder();
        byte[] buf = new byte[64 * 1024];
        while (!endsWithAckLine(reply)) {
            reply.append(readOnce(in, buf));
        }
        return reply.toString();
    }

    private static boolean endsWithAckLine(StringBuilder reply) {
        int len = reply.length();
        if (len < 4 || reply.charAt(len - 1) != '\n') {
            return false;
        }
        int lineStart = reply.lastIndexOf("\n", len - 2) + 1;
        String lastLine = reply.substring(lineStart, len - 1);
        return lastLine.equals("ACK") || lastLine.startsWith("ACK ");
    }

    private static String readLineRaw(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            line.write(b);
            if (b == '\n') {
                break;
            }
        }
        return new String(line.toByteArray(), StandardCharsets.UTF_8);
    }

    // readOnce is one read: what has come of the reply when it wakes.
    private static String readOnce(InputStream in, byte[] buf) throws IOException {
        int n;
        try {
            n = in.read(buf);
        } catch (SocketTimeoutException e) {
            throw new AssertionError("no reply came within the socket's 30 s", e);
        }
        if (n < 0) {
            throw new IOException("the SDK closed the connection");
        }
        return new String(buf, 0, n, StandardCharsets.UTF_8);
    }

    private static void send(OutputStream out, String command) throws IOException {
        out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
