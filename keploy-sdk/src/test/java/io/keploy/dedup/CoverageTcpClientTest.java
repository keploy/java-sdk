package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The TCP transport, with the test as the collector the SDK dials (k8s). An END
 * the SDK does not take publishes no COV, so it must say which test went
 * unmeasured, and must still answer ACK, which is what ends the collector's wait.
 */
public class CoverageTcpClientTest {

    private static final String ENDPOINT_PROPERTY = "keploy.coverage.endpoint";

    private ServerSocket collector;

    @Before
    public void setUp() throws IOException {
        collector = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        collector.setSoTimeout(30000);
        System.setProperty(ENDPOINT_PROPERTY, "127.0.0.1:" + collector.getLocalPort());
    }

    @After
    public void tearDown() throws IOException {
        KeployDedupAgent.stop();
        System.clearProperty(ENDPOINT_PROPERTY);
        collector.close();
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
}
