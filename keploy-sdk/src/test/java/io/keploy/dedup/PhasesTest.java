package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jacoco.core.data.ExecutionData;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

/**
 * KEPLOY_JAVA_DEDUP_DIAGNOSTICS says, for each command, how long it took and
 * how long each of its steps did, so a run on the real app shows where its
 * per-test time goes. Diagnostics off, it says nothing.
 */
public class PhasesTest {

    // The warmup must be on for the START rows, and no build tag set, or a
    // CLASSES frame comes between the TCP END's COV and its ACK.
    private static final String[] PROPERTIES = {"keploy.java.dedup.diagnostics", "keploy.java.class.dirs",
            "keploy.jacoco.port", "keploy.coverage.endpoint", "keploy.java.dedup.warmup.disabled",
            "keploy.build.tag"};
    private static final String TAKEN = "ACK proto=2\n";
    private static final String MS = "\\d+\\.\\d\\d";
    // The one app class, and the probes a test ran in it.
    private static final String SHOP = "com/acme/Shop";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String savedControlPath;
    private String savedDataPath;
    private FakeJacoco jacoco;
    private final List<String> logged = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @Before
    public void setUp() throws IOException {
        for (String name : PROPERTIES) {
            System.clearProperty(name);
        }
        File classes = tmp.newFolder("classes");
        File shop = new File(classes, SHOP + ".class");
        shop.getParentFile().mkdirs();
        Files.write(shop.toPath(), new byte[] {1});
        System.setProperty("keploy.java.class.dirs", classes.getPath());
        savedControlPath = KeployDedupAgent.controlSocketPath;
        savedDataPath = KeployDedupAgent.dataSocketPath;
        File sockets = tmp.newFolder("sockets");
        KeployDedupAgent.controlSocketPath = new File(sockets, "control.sock").getAbsolutePath();
        KeployDedupAgent.dataSocketPath = new File(sockets, "data.sock").getAbsolutePath();
        Logger.getLogger(KeployDedupAgent.class.getName()).addHandler(handler);
        jacoco = new FakeJacoco();
        jacoco.hit = new ExecutionData(0x1234L, SHOP, new boolean[] {true, false, true});
        System.setProperty("keploy.jacoco.port", String.valueOf(jacoco.server.getLocalPort()));
    }

    @After
    public void tearDown() throws Exception {
        KeployDedupAgent.stop();
        Logger.getLogger(KeployDedupAgent.class.getName()).removeHandler(handler);
        KeployDedupAgent.controlSocketPath = savedControlPath;
        KeployDedupAgent.dataSocketPath = savedDataPath;
        for (String name : PROPERTIES) {
            System.clearProperty(name);
        }
        jacoco.close();
    }

    @Test
    public void withDiagnosticsOnEachCommandSaysHowLongEachStepTook() throws Exception {
        System.setProperty("keploy.java.dedup.diagnostics", "true");
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            String payload = data.next();
            assertNotNull("the END's coverage reached the data socket", payload);
            assertTrue(payload, payload.contains("\"" + SHOP + "\":[0,2]"));
        }
        awaitLogged("Keploy dedup: warmed up application classes \\(initialized=0, skipped=1\\) in " + MS + " ms");
        assertStepsAddUp(awaitLogged("Java dedup diagnostics: START test-set-0/test-1 took " + MS + " ms: warmup="
                + MS + "ms reset=" + MS + "ms"));
        assertStepsAddUp(awaitLogged("Java dedup diagnostics: END test-set-0/test-1 took " + MS + " ms: dump=" + MS
                + "ms parse=" + MS + "ms filter=" + MS + "ms json=" + MS + "ms send=" + MS + "ms"));
        assertNoProbeScan();
    }

    @Test
    public void overTcpEachCommandSaysHowLongEachStepTookToo() throws Exception {
        System.setProperty("keploy.java.dedup.diagnostics", "true");
        try (ServerSocket collector = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            collector.setSoTimeout(30000);
            System.setProperty("keploy.coverage.endpoint", "127.0.0.1:" + collector.getLocalPort());
            assertTrue(KeployDedupAgent.start());
            try (Socket sdk = collector.accept()) {
                sdk.setSoTimeout(30000);
                OutputStream out = sdk.getOutputStream();
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));
                out.write("START test-set-0/test-1\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                assertEquals("ACK", in.readLine());
                out.write("END test-set-0/test-1\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                String cov = in.readLine();
                assertTrue(cov, cov != null && cov.startsWith("COV ") && cov.contains("\"" + SHOP + "\":[0,2]"));
                assertEquals("ACK", in.readLine());
            }
        }
        // Said once the command is answered: waited for.
        assertStepsAddUp(awaitLogged("Java dedup diagnostics: START test-set-0/test-1 took " + MS + " ms: warmup="
                + MS + "ms reset=" + MS + "ms"));
        assertStepsAddUp(awaitLogged("Java dedup diagnostics: END test-set-0/test-1 took " + MS + " ms: dump=" + MS
                + "ms parse=" + MS + "ms filter=" + MS + "ms json=" + MS + "ms send=" + MS + "ms classes=" + MS
                + "ms"));
        assertNoProbeScan();
    }

    // With JaCoCo reached over TCP there is no probe scan to look up or to
    // drop, and nothing says there was.
    private void assertNoProbeScan() {
        for (String line : logged) {
            assertTrue("logged: " + line, !line.contains("probe scan") && !line.contains("read in place"));
        }
    }

    @Test
    public void withDiagnosticsOffNoCommandSaysHowLongItTook() throws Exception {
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertNotNull("the END's coverage reached the data socket", data.next());
        }
        // On the unix socket the time is said before the answer, so it would be here by now.
        for (String line : logged) {
            assertTrue("logged: " + line, !line.contains(" took "));
        }
    }

    // awaitLogged waits for a logged line that matches regex, and returns it.
    private String awaitLogged(String regex) throws InterruptedException {
        Pattern pattern = Pattern.compile(regex);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            for (String line : logged) {
                if (pattern.matcher(line).matches()) {
                    return line;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("nothing logged matches: " + regex + "\nlogged: " + logged);
    }

    // assertStepsAddUp checks that a command's steps add up to no more than the
    // time it took: each step is timed from the one before it, not from the
    // command's start. Each number is rounded to 0.01 ms, so the steps may be
    // over by up to that much each.
    private static void assertStepsAddUp(String line) {
        Matcher took = Pattern.compile(" took (" + MS + ") ms:").matcher(line);
        assertTrue(line, took.find());
        double total = Double.parseDouble(took.group(1));
        Matcher step = Pattern.compile(" [a-z]+=(" + MS + ")ms").matcher(line);
        double sum = 0;
        int steps = 0;
        while (step.find()) {
            sum += Double.parseDouble(step.group(1));
            steps++;
        }
        assertTrue(line, steps > 0);
        assertTrue("the steps add up to more than the command took: " + line, sum <= total + 0.01 * steps);
    }

    private static String send(String command) throws IOException {
        try (AFUNIXSocket socket = AFUNIXSocket.newInstance()) {
            socket.connect(AFUNIXSocketAddress.of(new File(KeployDedupAgent.controlSocketPath)), 3000);
            socket.setSoTimeout(30000);
            OutputStream out = socket.getOutputStream();
            out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return new String(readAll(socket.getInputStream()), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            all.write(buffer, 0, n);
        }
        return all.toByteArray();
    }

    // DataReceiver is Keploy's data socket: it keeps each payload sent to it.
    private static final class DataReceiver implements Closeable {

        private final AFUNIXServerSocket server;
        private final BlockingQueue<String> payloads = new LinkedBlockingQueue<>();

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
                    String payload = new String(readAll(socket.getInputStream()), StandardCharsets.UTF_8);
                    // The INDEX report (ControlSocketTest's rows) is not a test's coverage.
                    if (!payload.startsWith("{\"kind\":\"index\",")) {
                        payloads.add(payload);
                    }
                } catch (IOException e) {
                    // Closed.
                }
            }
        }

        // next waits for the next payload: the SDK sends it before it answers the END.
        String next() throws InterruptedException {
            return payloads.poll(30, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
