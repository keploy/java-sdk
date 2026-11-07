package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

/**
 * The unix control socket Keploy sends START and END on. Keploy fires its first
 * test as soon as the app answers its health check, so the socket must listen
 * by the time start() (premain, before the app's main) returns; and an END the
 * SDK does not take must not be answered as taken.
 */
public class ControlSocketTest {

    private String savedPath;
    private File dir;

    @Before
    public void setUp() throws IOException {
        savedPath = KeployDedupAgent.controlSocketPath;
        dir = Files.createTempDirectory("keploy-dedup-control").toFile();
        KeployDedupAgent.controlSocketPath = new File(dir, "control.sock").getAbsolutePath();
    }

    @After
    public void tearDown() {
        KeployDedupAgent.stop();
        KeployDedupAgent.controlSocketPath = savedPath;
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
        assertEquals(KeployDedupAgent.IGNORED_REPLY, send("END test-set-0/test-1"));
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
        assertEquals(KeployDedupAgent.IGNORED_REPLY, send("END test-set-0/test-47"));
    }

    private static String send(String command) throws IOException {
        try (AFUNIXSocket client = AFUNIXSocket.newInstance()) {
            client.connect(AFUNIXSocketAddress.of(new File(KeployDedupAgent.controlSocketPath)), 3000);
            client.setSoTimeout(30000);
            OutputStream out = client.getOutputStream();
            out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            return in.readLine();
        }
    }
}
