package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
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

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ServerSocket collector;
    private FakeJacoco jacoco;

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
        System.clearProperty(ENDPOINT_PROPERTY);
        System.clearProperty(JACOCO_PORT_PROPERTY);
        System.clearProperty(WARMUP_DISABLED_PROPERTY);
        System.clearProperty(CLASS_DIRS_PROPERTY);
        System.clearProperty(BUILD_TAG_PROPERTY);
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

            // Two, so that a missing ACK shows as the next test's frame in its place.
            out.write("END test-set-0/test-47\nEND test-set-0/test-48\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            assertEquals("IGNORED test-set-0/test-47", in.readLine());
            assertEquals("ACK", in.readLine());
            assertEquals("IGNORED test-set-0/test-48", in.readLine());
            assertEquals("ACK", in.readLine());
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

            send(out, "START test-set-0/test-1");
            assertEquals("ACK", in.readLine());
            // JaCoCo goes away for the next reset: the counters still hold
            // test-1's lines, so test-2 is not measured.
            jacoco.dropNext.set(1);
            send(out, "START test-set-0/test-2");
            assertEquals("IGNORED test-set-0/test-2", in.readLine());
            assertEquals("ACK", in.readLine());
            // test-1 was left when test-2 started, and test-2 never began.
            send(out, "END test-set-0/test-1");
            assertEquals("IGNORED test-set-0/test-1", in.readLine());
            assertEquals("ACK", in.readLine());
            send(out, "END test-set-0/test-2");
            assertEquals("IGNORED test-set-0/test-2", in.readLine());
            assertEquals("ACK", in.readLine());

            // JaCoCo is back: the next test is measured as before.
            send(out, "START test-set-0/test-3");
            assertEquals("ACK", in.readLine());
            send(out, "END test-set-0/test-3");
            String cov = in.readLine();
            assertTrue("a COV for test-3, got " + cov,
                    cov != null && cov.startsWith("COV ") && cov.contains("test-set-0/test-3"));
            assertEquals("ACK", in.readLine());
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

            send(out, "START test-set-0/test-1");
            assertEquals("ACK", in.readLine());
            // JaCoCo goes away for the dump: test-1's lines never reach the
            // SDK. Before, it sent an empty COV, as for a test that ran no
            // line of the app.
            jacoco.dropNext.set(1);
            send(out, "END test-set-0/test-1");
            assertEquals("IGNORED test-set-0/test-1", in.readLine());
            assertEquals("ACK", in.readLine());
            // That END ended test-1.
            send(out, "END test-set-0/test-1");
            assertEquals("IGNORED test-set-0/test-1", in.readLine());
            assertEquals("ACK", in.readLine());

            send(out, "START test-set-0/test-2");
            assertEquals("ACK", in.readLine());
            send(out, "END test-set-0/test-2");
            String cov = in.readLine();
            assertTrue("a COV for test-2, got " + cov,
                    cov != null && cov.startsWith("COV ") && cov.contains("test-set-0/test-2"));
            assertEquals("ACK", in.readLine());
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

            send(out, "START test-set-0/test-1");
            assertEquals("IGNORED test-set-0/test-1", in.readLine());
            assertEquals("ACK", in.readLine());
            send(out, "END test-set-0/test-1");
            assertEquals("the connection still answers", "IGNORED test-set-0/test-1", in.readLine());
            assertEquals("ACK", in.readLine());
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

            send(out, "START test-set-0/test-1");
            assertEquals("ACK", in.readLine());
            send(out, "END test-set-0/test-1");
            assertEquals("COV {\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{\"com/acme/Shop\":[0]},"
                    + "\"classMismatch\":[\"com/acme/Shop\"]}", in.readLine());
            String classes = in.readLine();
            assertTrue(classes, classes.startsWith("CLASSES "));
            String manifest = new String(Base64.getDecoder().decode(classes.split(" ")[2]), StandardCharsets.UTF_8);
            assertEquals("{\"com/acme/Shop\":{\"id\":\"0000000000000099\",\"probeCount\":2}}", manifest);
            assertEquals("ACK", in.readLine());
        }
    }

    private static void send(OutputStream out, String command) throws IOException {
        out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
