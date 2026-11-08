package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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
    private static final String TAKEN = "ACK proto=2\n";
    private static final String IGNORED = "IGNORED proto=2\n";

    private String savedControlPath;
    private String savedDataPath;
    private File dir;
    private FakeJacoco jacoco;
    private DataReceiver data;

    @Before
    public void setUp() throws IOException {
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
        KeployDedupAgent.controlSocketPath = savedControlPath;
        KeployDedupAgent.dataSocketPath = savedDataPath;
        System.clearProperty(JACOCO_PORT_PROPERTY);
        System.clearProperty(WARMUP_DISABLED_PROPERTY);
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
        assertEquals(TAKEN, answer.poll(30, TimeUnit.SECONDS));
        while ((KeployDedupAgent.isStarted() || controlThreadAlive()) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        // Before, the accept loop went on on the closed socket: it spun a core
        // and logged without end, and the agent still said it was started.
        assertFalse("the agent says it is no longer started", KeployDedupAgent.isStarted());
        assertFalse("the control thread ended", controlThreadAlive());
        client.join();
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
