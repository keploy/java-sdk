package io.keploy.dedup;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The lines the SDK logs when it defers the build table's send or the JVM cuts it short: a native keploy test
 * stops the app at each test set's end, and a table that comes late, or never, must say why in the app's log.
 */
public class BuildTableNoticeTest {

    private static final String EXIT_LINE = "Keploy coverage: the JVM is exiting while its build table is being sent."
            + " So far: ";
    private static final String DEFERRED = "Keploy asked for the build table while this JVM sends one";
    private static final String PACED = "Keploy coverage: the build table's send is paced";

    @Test(timeout = 30_000)
    public void anAskWhileASendRunsSaysItIsDeferredOnceEach() throws Exception {
        CountDownLatch inFirst = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(2);
        List<String> ran = java.util.Collections.synchronizedList(new ArrayList<>());
        BuildTable.Sends sends = new BuildTable.Sends(BuildTable::thread);
        try (BuildTableTest.Logs logs = new BuildTableTest.Logs()) {
            assertTrue(sends.request(() -> {
                inFirst.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ran.add("first");
                ended.countDown();
            }));
            assertTrue(inFirst.await(10, TimeUnit.SECONDS));
            assertEquals(0, lines(logs, DEFERRED).size());
            assertTrue(sends.request(() -> ran.add("second")));
            List<String> one = lines(logs, DEFERRED);
            assertEquals(one.toString(), 1, one.size());
            assertTrue(one.get(0), !one.get(0).contains("dropped"));
            assertTrue(sends.request(() -> {
                ran.add("third");
                ended.countDown();
            }));
            List<String> two = lines(logs, DEFERRED);
            assertEquals(two.toString(), 2, two.size());
            assertTrue(two.get(1), two.get(1).contains("(an earlier ask that waited for it is dropped"));
            assertEquals("[INFO, INFO]", levels(logs, DEFERRED));
            release.countDown();
            assertTrue(ended.await(10, TimeUnit.SECONDS));
            // The first send and the newest ask ran; the ask it replaced did not, as its line said.
            assertEquals("[first, third]", ran.toString());
        }
    }

    @Test(timeout = 30_000)
    public void aPacedSendSaysSoOnceAtItsFirstWait() throws Exception {
        BuildTablePacerTest.Clock c = new BuildTablePacerTest.Clock();
        try (BuildTableTest.Logs logs = new BuildTableTest.Logs()) {
            BuildTable.Pacer p = new BuildTable.Pacer(c);
            c.now += 400_000_000L; // the index's read: 400 ms, longer than the 1st unit owes, so the floor is hit
            p.begin(1_000);
            for (int i = 0; i < 1_000; i++) {
                c.now += 1_000_000L;
                c.gc += 1; // 1 ms of collections a unit: over the 0.5% goal, so it waits
                c.units++;
                p.unitDone(1);
            }
            assertTrue("the row's pacer waited " + c.sleeps.size() + " times", c.sleeps.size() > 1);
            List<String> got = lines(logs, PACED);
            assertEquals(got.toString(), 1, got.size());
            // The 1st unit owes 200 ms, less its 401 ms: 0, never below. The 2nd owes 200 ms less its 1 ms: 199 ms,
            // its first wait (200 times the 2 ms of collections less the 402 ms since the start is below 0).
            assertTrue(got.get(0), got.get(0).contains("the JVM's collections took 2 ms in the 402 ms since it began,"
                    + " so it owes 199 ms now (each ms of collection adds 200, and each ms the send works or waits"
                    + " takes one off, never below 0)"));
            assertTrue(got.get(0), got.get(0).contains("its waits add up to at most its own work time (plus one"
                    + " sleep's overrun), so the table takes about twice as long as unpaced at most, and none ends the"
                    + " table past 20000 ms from its start"));
        }
    }

    @Test(timeout = 30_000)
    public void aSendThatIsNotPacedSaysNothingOfIt() throws Exception {
        BuildTablePacerTest.Clock c = new BuildTablePacerTest.Clock();
        try (BuildTableTest.Logs logs = new BuildTableTest.Logs()) {
            BuildTable.Pacer p = new BuildTable.Pacer(c);
            p.begin(1_000);
            for (int i = 0; i < 1_000; i++) {
                c.now += 1_000_000L;
                c.units++;
                p.unitDone(1);
            }
            assertEquals(0, c.sleeps.size());
            assertEquals(0, lines(logs, PACED).size());
        }
    }

    @Test(timeout = 120_000)
    public void aJvmThatExitsMidSendSaysSoOnce() throws Exception {
        String out = runChild("mid");
        assertEquals(out, 1, count(out, EXIT_LINE));
        assertTrue(out, out.contains(" class files analyzed; 0 frames sent in "));
        assertTrue(out, out.contains("CHILD exiting"));
    }

    @Test(timeout = 120_000)
    public void aSendThatEndsBeforeTheJvmExitsSaysNothing() throws Exception {
        String out = runChild("after");
        assertEquals(out, 0, count(out, EXIT_LINE));
        assertTrue(out, out.contains("CHILD sent"));
    }

    @Test(timeout = 60_000)
    public void aSendRemovesItsExitHookWhenItEnds() throws Exception {
        List<BuildTable.ClassFile> app = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String n = BuildTableBudgetTest.name(i);
            app.add(new BuildTable.ClassFile(n, BuildTableBudgetTest.gen(n, i, 3)));
        }
        // A send that ends whole, and one whose socket fails at its first frame.
        BuildTable.FrameSender whole = frame -> { };
        BuildTable.FrameSender failing = frame -> {
            throw new IOException("row: the data socket is closed");
        };
        try {
            for (BuildTable.FrameSender sender : new BuildTable.FrameSender[] {whole, failing}) {
                BuildTable.ExitNotice.LAST_HOOK.remove();
                BuildTable.send(() -> app, sender); // on this thread, so LAST_HOOK is this send's
                Thread hook = BuildTable.ExitNotice.LAST_HOOK.get();
                assertTrue("the send added an exit hook", hook != null);
                // Removing it again finds none: the send removed it (else this removes it, for the rows after).
                assertFalse("the send left its exit hook registered",
                        Runtime.getRuntime().removeShutdownHook(hook));
            }
        } finally {
            BuildTable.ExitNotice.LAST_HOOK.remove();
        }
    }

    /** runChild runs ExitMain in a JVM of its own (an exit ends it), and returns its stdout and stderr. */
    private static String runChild(String mode) throws Exception {
        String javaBin = new java.io.File(System.getProperty("java.home"), "bin/java").getPath();
        Process process = new ProcessBuilder(javaBin, "-Xmx512m", "-cp", System.getProperty("java.class.path"),
                ExitMain.class.getName(), mode).redirectErrorStream(true).start();
        String out = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
        assertTrue(out, process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(out, 0, process.exitValue());
        return out;
    }

    /**
     * ExitMain sends a table of generated classes on the SDK's own thread. "mid": its first frame never returns,
     * and the JVM exits mid-send. "after": the send ends, then the JVM exits.
     */
    public static final class ExitMain {
        public static void main(String[] args) throws Exception {
            boolean mid = "mid".equals(args[0]);
            List<BuildTable.ClassFile> app = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                String n = BuildTableBudgetTest.name(i);
                app.add(new BuildTable.ClassFile(n, BuildTableBudgetTest.gen(n, i, BuildTableBudgetTest.BRANCHES)));
            }
            CountDownLatch inFrame = new CountDownLatch(1);
            CountDownLatch sent = new CountDownLatch(1);
            BuildTable.thread(() -> {
                BuildTable.send(() -> app, frame -> {
                    inFrame.countDown();
                    if (mid) {
                        try {
                            new CountDownLatch(1).await(); // the frame never returns: the exit cuts the send
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
                sent.countDown();
            }).start();
            if (mid) {
                if (!inFrame.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("no frame");
                }
                System.out.println("CHILD exiting");
            } else {
                if (!sent.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("not sent");
                }
                System.out.println("CHILD sent");
            }
            System.out.flush();
            System.exit(0);
        }
    }

    /** lines are the messages with part that this row's thread logged (a send of another class may log too). */
    private static List<String> lines(BuildTableTest.Logs logs, String part) {
        List<String> out = new ArrayList<>();
        for (LogRecord r : logs.mine()) {
            if (r.getMessage() != null && r.getMessage().contains(part)) {
                out.add(r.getMessage());
            }
        }
        return out;
    }

    private static String levels(BuildTableTest.Logs logs, String part) {
        List<Level> out = new ArrayList<>();
        for (LogRecord r : logs.mine()) {
            if (r.getMessage() != null && r.getMessage().contains(part)) {
                out.add(r.getLevel());
            }
        }
        return out.toString();
    }

    private static int count(String s, String part) {
        int n = 0;
        for (int i = s.indexOf(part); i >= 0; i = s.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) {
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }
}
