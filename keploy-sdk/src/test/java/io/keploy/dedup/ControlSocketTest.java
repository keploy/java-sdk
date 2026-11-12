package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jacoco.core.data.ExecutionData;
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
    private static final String BUILD_TAG_PROPERTY = "keploy.build.tag";
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
    private final Handler severeLog = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.SEVERE.intValue() && severe.size() < 20) {
                severe.add(record.getMessage());
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
        System.clearProperty(BUILD_TAG_PROPERTY);
        if (jacoco != null) {
            jacoco.close();
        }
        if (data != null) {
            data.close();
        }
        File[] left = dir.listFiles();
        if (left != null) {
            for (File f : left) {
                f.delete();
            }
        }
        dir.delete();
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

    // Keploy's agent keeps the app's class bytecode for the coverage report
    // from a frame after a record, on the record's own data connection: the
    // record says one follows. The class zip goes once per JVM, then only a
    // grown manifest, and again after a send that failed.
    @Test(timeout = 60000)
    public void theClassZipFollowsATakenEndsRecordOncePerJvmAndAgainAfterAFailedSend() throws Exception {
        File classes = new File(dir, "classes");
        writeClass(classes, "com/acme/Orders.class", "orders-bytes");
        writeClass(classes, "com/acme/Billing.class", "billing-bytes");
        System.setProperty(CLASS_DIRS_PROPERTY, classes.getAbsolutePath());
        System.setProperty(BUILD_TAG_PROPERTY, "build-7");
        startWithJacoco();
        jacoco.hits.add(new ExecutionData(0x11L, "com/acme/Orders", new boolean[] {true, false}));

        // No one listens on the data socket: the zip polled for test-1 is not delivered.
        assertEquals(TAKEN, send("START test-set-0/test-1"));
        assertEquals(IGNORED, send("END test-set-0/test-1"));

        data = new DataReceiver(KeployDedupAgent.dataSocketPath);
        assertEquals(TAKEN, send("START test-set-0/test-2"));
        assertEquals(TAKEN, send("END test-set-0/test-2"));
        String[] sent = recordThenFrame(data.next());
        assertTrue(sent[0], sent[0].contains("\"id\":\"test-set-0/test-2\""));
        assertTrue("the record says a frame follows: " + sent[0], sent[0].contains("\"bytecodeFrame\":true"));
        String[] frame = sent[1].split(" ", -1);
        assertEquals(sent[1], 4, frame.length);
        assertEquals("CLASSES", frame[0]);
        assertEquals("build-7", base64(frame[1]));
        assertTrue(base64(frame[2]), base64(frame[2]).contains("\"com/acme/Orders\""));
        Map<String, String> zip = unzip(frame[3]);
        assertEquals("orders-bytes", zip.get("com/acme/Orders.class"));
        assertEquals("billing-bytes", zip.get("com/acme/Billing.class"));

        // Sent: the next record, with the same classes hit, has no frame.
        assertEquals(TAKEN, send("START test-set-0/test-3"));
        assertEquals(TAKEN, send("END test-set-0/test-3"));
        String alone = data.next();
        assertNotNull(alone);
        assertTrue(alone, alone.contains("\"id\":\"test-set-0/test-3\""));
        assertFalse("no frame, so the record says none: " + alone, alone.contains("bytecodeFrame"));
        assertFalse("nothing follows the record: " + alone, alone.contains("\n"));

        // A class hit for the first time: only the grown manifest follows.
        jacoco.hits.add(new ExecutionData(0x22L, "com/acme/Billing", new boolean[] {true}));
        assertEquals(TAKEN, send("START test-set-0/test-4"));
        assertEquals(TAKEN, send("END test-set-0/test-4"));
        sent = recordThenFrame(data.next());
        assertTrue(sent[0], sent[0].contains("\"bytecodeFrame\":true"));
        frame = sent[1].split(" ", -1);
        assertEquals(sent[1], 3, frame.length);
        assertEquals("MANIFEST", frame[0]);
        assertEquals("build-7", base64(frame[1]));
        assertTrue(base64(frame[2]), base64(frame[2]).contains("\"com/acme/Billing\""));
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

    private static void writeClass(File root, String key, String bytes) throws IOException {
        File file = new File(root, key);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.write(file.toPath(), bytes.getBytes(StandardCharsets.UTF_8));
    }

    // recordThenFrame splits a data connection's bytes into the record and the
    // bytecode frame after it, one line each.
    private static String[] recordThenFrame(String payload) {
        assertNotNull("the END's coverage reached the data socket", payload);
        int nl = payload.indexOf('\n');
        assertTrue("a newline ends the record before its frame: " + payload, nl > 0);
        String frame = payload.substring(nl + 1);
        assertFalse("one frame, on one line: " + frame, frame.isEmpty() || frame.contains("\n"));
        return new String[] {payload.substring(0, nl), frame};
    }

    private static String base64(String field) {
        return new String(Base64.getDecoder().decode(field), StandardCharsets.UTF_8);
    }

    private static Map<String, String> unzip(String field) throws IOException {
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(field)))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                entries.put(entry.getName(), readAll(in));
            }
        }
        return entries;
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

    /** Keploy's data socket: it keeps each payload sent to it. */
    private static final class DataReceiver implements Closeable {

        final AFUNIXServerSocket server;
        final BlockingQueue<String> payloads = new LinkedBlockingQueue<>();

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
                    payloads.add(readAll(socket.getInputStream()));
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
