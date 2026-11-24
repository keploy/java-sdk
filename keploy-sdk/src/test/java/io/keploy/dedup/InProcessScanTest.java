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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.runtime.RuntimeData;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

/**
 * With JaCoCo's agent in the app's JVM, a START clears what ran since the last
 * test as background and an END reports the test's own hits, each in one pass
 * over the app's classes. When that pass fails, the SDK goes back to JaCoCo's
 * dump and reset for good.
 */
public class InProcessScanTest {

    private static final String[] PROPERTIES = {"keploy.java.dedup.diagnostics", "keploy.java.class.dirs",
            "keploy.jacoco.port", "keploy.coverage.endpoint", "keploy.java.dedup.warmup.disabled",
            "keploy.build.tag", "keploy.java.scan", "keploy.java.end.wait.ms"};
    private static final String TAKEN = "ACK proto=2\n";
    private static final String IGNORED = "IGNORED proto=2\n";
    private static final String SHOP = "com/acme/Shop";
    private static final String CART = "com/acme/Cart";
    // The JaCoCo ids of the bytes setUp writes for the two classes: the ids
    // JaCoCo gives them when it sees the bytes the index reads.
    private static final long SHOP_ID = IndexReport.classId(new byte[] {1});
    private static final long CART_ID = IndexReport.classId(new byte[] {2});
    // The build id of the two classes setUp writes, as the INDEX report has it.
    private static final String BUILD = IndexReport.buildId(new long[] {SHOP_ID, CART_ID});
    // Ids of copies of a class JaCoCo saw other bytes of (another agent
    // changed them first): one past the sign bit, and one below every id
    // above as an unsigned number.
    private static final long HIGH_ID = 0x8000000000000001L;
    private static final long LOW_ID = 0x99L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String savedControlPath;
    private String savedDataPath;
    private ClassLoader savedContextLoader;
    private Agent agent;
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

    // Agent is JaCoCo's agent as the SDK sees it: getExecutionData and reset,
    // over a RuntimeData whose probe arrays the app's classes write to.
    public static final class Agent {

        private final RuntimeData data = new RuntimeData();

        public byte[] getExecutionData(boolean reset) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ExecutionDataWriter writer = new ExecutionDataWriter(out);
            data.collect(writer, writer, reset);
            return out.toByteArray();
        }

        public void reset() {
            data.reset();
        }

        boolean[] probes(long id, String name, int count) {
            return data.getExecutionData(id, name, count).getProbes();
        }
    }

    @Before
    public void setUp() throws IOException, ReflectiveOperationException {
        for (String name : PROPERTIES) {
            System.clearProperty(name);
        }
        File classes = tmp.newFolder("classes");
        File shop = new File(classes, SHOP + ".class");
        shop.getParentFile().mkdirs();
        Files.write(shop.toPath(), new byte[] {1});
        Files.write(new File(classes, CART + ".class").toPath(), new byte[] {2});
        System.setProperty("keploy.java.class.dirs", classes.getPath());
        // A port nothing listens on: no command may reach JaCoCo over TCP.
        System.setProperty("keploy.jacoco.port", "1");
        savedControlPath = KeployDedupAgent.controlSocketPath;
        savedDataPath = KeployDedupAgent.dataSocketPath;
        File sockets = tmp.newFolder("sockets");
        KeployDedupAgent.controlSocketPath = new File(sockets, "control.sock").getAbsolutePath();
        KeployDedupAgent.dataSocketPath = new File(sockets, "data.sock").getAbsolutePath();
        Logger.getLogger(KeployDedupAgent.class.getName()).addHandler(handler);
        agent = new Agent();
        // JaCoCo's RT, whose getAgent() returns this agent, in a loader of
        // its own: the context loader of this thread and so of the worker
        // KeployDedupAgent.start() makes, which the SDK looks for RT in.
        savedContextLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new RtLoader(agent, getClass().getClassLoader()));
    }

    @After
    public void tearDown() {
        KeployDedupAgent.stop();
        Thread.currentThread().setContextClassLoader(savedContextLoader);
        Logger.getLogger(KeployDedupAgent.class.getName()).removeHandler(handler);
        KeployDedupAgent.controlSocketPath = savedControlPath;
        KeployDedupAgent.dataSocketPath = savedDataPath;
        for (String name : PROPERTIES) {
            System.clearProperty(name);
        }
    }

    @Test
    public void eachTestGetsItsOwnHitsAndNoneOfTheBackground() throws Exception {
        System.setProperty("keploy.java.dedup.diagnostics", "true");
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        boolean[] lib = agent.probes(0x99L, "org/lib/Lib", 2);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());

            // Before the test: a health check runs Shop's probe 0, and a library.
            shop[0] = true;
            lib[0] = true;
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            assertTrue(logged(" START test-set-0/test-1 took ").contains(" scan="));
            assertFalse(logged(" START test-set-0/test-1 took ").contains("reset="));
            assertNotNull(logged("background hits cleared at START: classes=1, probes=1, sampleClasses=["
                    + SHOP + "]"));

            shop[2] = true;
            shop[3] = true;
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{\"" + SHOP + "\":[2,3]}}",
                    data.next());
            String end = logged(" END test-set-0/test-1 took ");
            assertTrue(end, end.contains(" scan=") && end.contains(" filter=") && !end.contains("dump="));
            assertFalse("no request ran: END did not wait", end.contains(" requests="));
            assertTrue("a library class is left alone", lib[0]);

            // Nothing ran between the tests, or in the second one.
            assertEquals(TAKEN, send("START test-set-0/test-2"));
            assertEquals(TAKEN, send("END test-set-0/test-2"));
            assertEquals("{\"id\":\"test-set-0/test-2\",\"executedLinesByFile\":{}}", data.next());
            assertEquals("one background line, for the first START", 1, count("background hits cleared"));
        }
    }

    @Test
    public void aClassLoadedTwiceReportsTheCopyTheIndexRead() throws Exception {
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        // The same class from other bytes, in a second class loader, with
        // the lower id.
        boolean[] twin = agent.probes(LOW_ID, SHOP, 3);
        assertTrue(Long.compareUnsigned(LOW_ID, SHOP_ID) < 0);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            shop[3] = true;
            twin[1] = true;
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{\"" + SHOP + "\":[3]}}",
                    data.next());
            assertFalse("both were cleared", shop[3] || twin[1]);
        }
    }

    @Test
    public void ofTwoCopiesTheIndexDidNotReadTheLowerIdAsAnUnsignedNumberIsReported() throws Exception {
        // Signed, HIGH_ID is the lower.
        boolean[] high = agent.probes(HIGH_ID, SHOP, 4);
        boolean[] low = agent.probes(LOW_ID, SHOP, 3);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            high[3] = true;
            low[1] = true;
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals(test("test-set-0/test-1", probes(SHOP, "1"), SHOP), data.next());
        }
    }

    @Test
    public void aClassJacocoSawOtherBytesOfIsNamedInClassMismatch() throws Exception {
        System.setProperty("keploy.java.dedup.diagnostics", "true");
        boolean[] cart = agent.probes(CART_ID, CART, 3);
        // Shop as another agent changed it before JaCoCo saw it.
        boolean[] shop = agent.probes(HIGH_ID, SHOP, 4);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            cart[0] = true;
            shop[2] = true;
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals(test("test-set-0/test-1", probes(CART, "0") + "," + probes(SHOP, "2"), SHOP),
                    data.next());
            assertNotNull(logged("classMismatch=1, sampleClasses=[" + SHOP + "]"));
            // A test that runs only the class the index read names none.
            assertEquals(TAKEN, send("START test-set-0/test-2"));
            cart[1] = true;
            assertEquals(TAKEN, send("END test-set-0/test-2"));
            assertEquals(test("test-set-0/test-2", probes(CART, "1"), null), data.next());
            assertEquals("said for the first test alone", 1, count("classMismatch="));
        }
    }

    @Test
    public void withTheScanOffTheDumpChoosesOfTwoCopiesAsTheScanDoes() throws Exception {
        System.setProperty("keploy.java.scan", "off");
        boolean[] cart = agent.probes(CART_ID, CART, 3);
        boolean[] cartTwin = agent.probes(LOW_ID, CART, 2);
        boolean[] shopHigh = agent.probes(HIGH_ID, SHOP, 4);
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        boolean[] shopLow = agent.probes(LOW_ID + 1, SHOP, 3);
        boolean[] lib = agent.probes(0x77L, "org/lib/Lib", 2);
        assertTrue(Long.compareUnsigned(LOW_ID, CART_ID) < 0 && Long.compareUnsigned(LOW_ID + 1, SHOP_ID) < 0);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            lib[1] = true;
            cart[2] = true;
            cartTwin[0] = true;
            shopHigh[0] = true;
            shop[3] = true;
            shopLow[1] = true;
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("the copies the index read",
                    test("test-set-0/test-1", probes(CART, "2") + "," + probes(SHOP, "3"), null), data.next());
            // Of two copies the index did not read, the lower id as an
            // unsigned number, named in classMismatch.
            assertEquals(TAKEN, send("START test-set-0/test-2"));
            shopHigh[1] = true;
            shopLow[2] = true;
            assertEquals(TAKEN, send("END test-set-0/test-2"));
            assertEquals(test("test-set-0/test-2", probes(SHOP, "2"), SHOP), data.next());
        }
    }

    @Test
    public void withDiagnosticsOffTheBackgroundIsNotLogged() throws Exception {
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            shop[0] = true;
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            shop[1] = true;
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{\"" + SHOP + "\":[1]}}",
                    data.next());
            assertEquals(0, count("Java dedup diagnostics"));
        }
    }

    @Test
    public void betweenTwoTestsTheHitsGoOnceToAReceiverThatAskedAndNeverIntoATest() throws Exception {
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        boolean[] cart = agent.probes(CART_ID, CART, 3);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            // The app's start: not between two tests.
            shop[0] = true;
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            assertEquals(TAKEN, send("BACKGROUND"));
            shop[2] = true;
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{\"" + SHOP + "\":[2]}}",
                    data.next());
            assertNull("no window before the first test", data.background.poll());

            // A scheduled job between the tests.
            shop[1] = true;
            cart[2] = true;
            cart[0] = true;
            assertEquals(TAKEN, send("START test-set-0/test-2"));
            assertEquals(background("test-set-0/test-1", "test-set-0/test-2",
                    probes(CART, "0,2") + "," + probes(SHOP, "1")), data.nextBackground());
            assertFalse("cleared", shop[1] || cart[0] || cart[2]);
            shop[3] = true;
            assertEquals(TAKEN, send("END test-set-0/test-2"));
            assertEquals("{\"id\":\"test-set-0/test-2\",\"executedLinesByFile\":{\"" + SHOP + "\":[3]}}",
                    data.next());

            // Nothing between these two: no record.
            assertEquals(TAKEN, send("START test-set-0/test-3"));
            assertEquals(TAKEN, send("END test-set-0/test-3"));
            assertEquals("{\"id\":\"test-set-0/test-3\",\"executedLinesByFile\":{}}", data.next());
            assertNull("one record, for the one window with hits", data.background.poll());
        }
    }

    @Test
    public void betweenTwoTestsAClassLoadedTwiceIsReportedAsATestsHitsWouldBe() throws Exception {
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        boolean[] twin = agent.probes(LOW_ID, SHOP, 3);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            assertEquals(TAKEN, send("BACKGROUND"));
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{}}", data.next());
            shop[3] = true;
            twin[1] = true;
            assertEquals(TAKEN, send("START test-set-0/test-2"));
            assertEquals("the copy the index read",
                    background("test-set-0/test-1", "test-set-0/test-2", probes(SHOP, "3")), data.nextBackground());
            assertFalse("both were cleared", shop[3] || twin[1]);
        }
    }

    @Test
    public void betweenTwoTestsAClassJacocoSawOtherBytesOfIsNamedInClassMismatch() throws Exception {
        boolean[] cart = agent.probes(CART_ID, CART, 3);
        // Shop as another agent changed it before JaCoCo saw it: only this
        // copy runs between the tests.
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        boolean[] woven = agent.probes(HIGH_ID, SHOP, 3);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            assertEquals(TAKEN, send("BACKGROUND"));
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals(test("test-set-0/test-1", "", null), data.next());
            cart[2] = true;
            woven[1] = true;
            assertEquals(TAKEN, send("START test-set-0/test-2"));
            assertEquals(background("test-set-0/test-1", "test-set-0/test-2",
                    probes(CART, "2") + "," + probes(SHOP, "1"), SHOP, 0), data.nextBackground());
            // Between the next two, only the class the index read: none named.
            assertEquals(TAKEN, send("END test-set-0/test-2"));
            assertEquals(test("test-set-0/test-2", "", null), data.next());
            shop[0] = true;
            assertEquals(TAKEN, send("START test-set-0/test-3"));
            assertEquals(background("test-set-0/test-2", "test-set-0/test-3", probes(SHOP, "0")),
                    data.nextBackground());
            assertFalse("all were cleared", cart[2] || woven[1] || shop[0]);
        }
    }

    @Test
    public void noBackgroundRecordReachesAReceiverThatDidNotAsk() throws Exception {
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{}}", data.next());
            shop[1] = true;
            assertEquals(TAKEN, send("START test-set-0/test-2"));
            assertFalse("cleared all the same", shop[1]);
            assertEquals(TAKEN, send("END test-set-0/test-2"));
            assertEquals("{\"id\":\"test-set-0/test-2\",\"executedLinesByFile\":{}}", data.next());
            assertNull(data.background.poll());
        }
    }

    @Test
    public void whatAStartClearsAfterNoTakenEndIsNoBackground() throws Exception {
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            assertEquals(TAKEN, send("BACKGROUND"));
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{}}", data.next());
            assertEquals(TAKEN, send("START test-set-0/test-2"));
            // test-2's END never comes: what test-2 ran is its own, not background.
            shop[1] = true;
            assertEquals(TAKEN, send("START test-set-0/test-3"));
            // test-3's END names another test: not taken.
            shop[2] = true;
            assertEquals(IGNORED, send("END test-set-0/test-9"));
            assertEquals(TAKEN, send("START test-set-0/test-4"));
            shop[3] = true;
            assertEquals(TAKEN, send("END test-set-0/test-4"));
            assertEquals("{\"id\":\"test-set-0/test-4\",\"executedLinesByFile\":{\"" + SHOP + "\":[3]}}",
                    data.next());
            assertNull(data.background.poll());
        }
    }

    @Test
    public void anEndNotTakenWithNoStartBeforeItOpensNoWindow() throws Exception {
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            assertEquals(TAKEN, send("BACKGROUND"));
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{}}", data.next());
            // test-2's START never reached the app, and Keploy ran the test
            // and sent its END all the same: what test-2 ran is its own.
            shop[1] = true;
            assertEquals(IGNORED, send("END test-set-0/test-2"));
            assertEquals(TAKEN, send("START test-set-0/test-3"));
            assertFalse("cleared all the same", shop[1]);
            assertEquals(TAKEN, send("END test-set-0/test-3"));
            assertEquals("{\"id\":\"test-set-0/test-3\",\"executedLinesByFile\":{}}", data.next());
            assertNull(data.background.poll());
            // After an END taken, the next START's hits are background again.
            shop[2] = true;
            assertEquals(TAKEN, send("START test-set-0/test-4"));
            assertEquals(background("test-set-0/test-3", "test-set-0/test-4", probes(SHOP, "2")),
                    data.nextBackground());
        }
    }

    @Test
    public void anEndWhoseRecordCouldNotBeSentOpensNoWindow() throws Exception {
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        File socket = new File(KeployDedupAgent.dataSocketPath);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            assertEquals(TAKEN, send("BACKGROUND"));
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{}}", data.next());
            assertEquals(TAKEN, send("START test-set-0/test-2"));
            shop[1] = true;
            assertTrue(socket.setWritable(false, false) && socket.setReadable(false, false));
            Assume.assumeFalse("root connects to any socket", socket.canWrite());
            // test-2's coverage reaches no one: not taken.
            assertEquals(IGNORED, send("END test-set-0/test-2"));
            assertTrue(socket.setWritable(true, false) && socket.setReadable(true, false));
            shop[2] = true;
            assertEquals(TAKEN, send("START test-set-0/test-3"));
            assertFalse("cleared all the same", shop[1] || shop[2]);
            assertEquals(TAKEN, send("END test-set-0/test-3"));
            assertEquals("{\"id\":\"test-set-0/test-3\",\"executedLinesByFile\":{}}", data.next());
            assertNull("no window after an END whose record was not sent", data.background.poll());
            shop[3] = true;
            assertEquals(TAKEN, send("START test-set-0/test-4"));
            assertEquals(background("test-set-0/test-3", "test-set-0/test-4", probes(SHOP, "3")),
                    data.nextBackground());
        } finally {
            socket.setWritable(true, false);
            socket.setReadable(true, false);
        }
    }

    @Test
    public void aReceiverBoundAnewGetsTheIndexBeforeABackgroundRecord() throws Exception {
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        assertTrue(KeployDedupAgent.start());
        try (DataReceiver first = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{}}", first.next());
        }
        new File(KeployDedupAgent.dataSocketPath).delete();
        try (DataReceiver second = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            // It asks before its first START: its first record is a window's.
            assertEquals(TAKEN, send("BACKGROUND"));
            shop[1] = true;
            assertEquals(TAKEN, send("START test-set-1/test-1"));
            assertEquals(background("test-set-0/test-1", "test-set-1/test-1", probes(SHOP, "1")),
                    second.nextBackground());
            assertEquals(Arrays.asList("index", "background"), second.kinds);
            String index = second.index.poll();
            assertNotNull(index);
            assertTrue(index, index.contains(",\"build\":\"" + BUILD + "\","));
        }
    }

    @Test
    public void aReceiverBoundAnewGetsNoBackgroundRecordUntilItAsks() throws Exception {
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        assertTrue(KeployDedupAgent.start());
        try (DataReceiver first = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            assertEquals(TAKEN, send("BACKGROUND"));
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{}}", first.next());
        }
        // The next test set's receiver, on the same path: it may be an older
        // Keploy, which reads any record as a test's coverage.
        new File(KeployDedupAgent.dataSocketPath).delete();
        try (DataReceiver second = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            shop[1] = true;
            assertEquals(TAKEN, send("START test-set-1/test-1"));
            assertEquals(TAKEN, send("END test-set-1/test-1"));
            assertEquals("{\"id\":\"test-set-1/test-1\",\"executedLinesByFile\":{}}", second.next());
            assertNull(second.background.poll());

            assertEquals(TAKEN, send("BACKGROUND"));
            shop[2] = true;
            assertEquals(TAKEN, send("START test-set-1/test-2"));
            assertEquals(background("test-set-1/test-1", "test-set-1/test-2", probes(SHOP, "2")),
                    second.nextBackground());
        }
    }

    @Test
    public void withNoReceiverBackgroundIsNotTakenAndAWindowGoesNowhere() throws Exception {
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        assertTrue(KeployDedupAgent.start());
        assertEquals(TAKEN, send("START test-set-0/test-1"));
        assertEquals("no receiver to send it to", IGNORED, send("BACKGROUND"));
        assertNotNull(logged("Keploy dedup: BACKGROUND is not taken: no receiver is on "));
        assertEquals("said, not thrown", 0, count("Failed to run Java dedup coverage command"));
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertEquals(TAKEN, send("BACKGROUND"));
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{}}", data.next());
        }
        // The receiver is gone: the window's record goes nowhere, and the server goes on.
        new File(KeployDedupAgent.dataSocketPath).delete();
        shop[1] = true;
        assertEquals(TAKEN, send("START test-set-0/test-2"));
        assertFalse(shop[1]);
        assertEquals(IGNORED, send("END test-set-0/test-2"));
        assertEquals(TAKEN, send("START test-set-0/test-3"));
    }

    @Test
    public void aBackgroundRecordThatCouldNotBeSentIsCountedInTheNext() throws Exception {
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        File socket = new File(KeployDedupAgent.dataSocketPath);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            assertEquals(TAKEN, send("BACKGROUND"));
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{}}", data.next());

            // The same receiver, which no one may connect to for a while: two
            // windows' records are lost, and said once.
            for (int test = 2; test <= 3; test++) {
                assertTrue(socket.setWritable(false, false) && socket.setReadable(false, false));
                Assume.assumeFalse("root connects to any socket", socket.canWrite());
                shop[1] = true;
                assertEquals(TAKEN, send("START test-set-0/test-" + test));
                // The window's record is sent once the START is answered and
                // before the next command is read: this one's answer says it was.
                assertEquals(IGNORED, send("NO-COMMAND"));
                assertTrue(socket.setWritable(true, false) && socket.setReadable(true, false));
                assertEquals(TAKEN, send("END test-set-0/test-" + test));
                assertEquals("{\"id\":\"test-set-0/test-" + test + "\",\"executedLinesByFile\":{}}",
                        data.next());
            }
            assertEquals(1, count("Keploy dedup: the BACKGROUND record of the hits before"));
            assertNotNull(logged("Keploy dedup: the BACKGROUND record of the hits before test-set-0/test-2 could"
                    + " not be sent to "));

            shop[2] = true;
            assertEquals(TAKEN, send("START test-set-0/test-4"));
            assertEquals(background("test-set-0/test-3", "test-set-0/test-4", probes(SHOP, "2"), 2),
                    data.nextBackground());
            assertEquals(TAKEN, send("END test-set-0/test-4"));
            assertEquals("{\"id\":\"test-set-0/test-4\",\"executedLinesByFile\":{}}", data.next());
            shop[3] = true;
            assertEquals(TAKEN, send("START test-set-0/test-5"));
            assertEquals("counted once", background("test-set-0/test-4", "test-set-0/test-5", probes(SHOP, "3")),
                    data.nextBackground());

            // Lost again, and the same receiver asks again: it is still told.
            assertEquals(TAKEN, send("END test-set-0/test-5"));
            assertEquals("{\"id\":\"test-set-0/test-5\",\"executedLinesByFile\":{}}", data.next());
            assertTrue(socket.setWritable(false, false) && socket.setReadable(false, false));
            shop[1] = true;
            assertEquals(TAKEN, send("START test-set-0/test-6"));
            assertEquals(IGNORED, send("NO-COMMAND"));
            assertTrue(socket.setWritable(true, false) && socket.setReadable(true, false));
            assertEquals(TAKEN, send("BACKGROUND"));
            assertEquals(TAKEN, send("END test-set-0/test-6"));
            assertEquals("{\"id\":\"test-set-0/test-6\",\"executedLinesByFile\":{}}", data.next());
            shop[2] = true;
            assertEquals(TAKEN, send("START test-set-0/test-7"));
            assertEquals(background("test-set-0/test-6", "test-set-0/test-7", probes(SHOP, "2"), 1),
                    data.nextBackground());

            // Lost again, then the receiver is replaced: the new one, once it
            // asks, is told of no window lost before it.
            assertEquals(TAKEN, send("END test-set-0/test-7"));
            assertEquals("{\"id\":\"test-set-0/test-7\",\"executedLinesByFile\":{}}", data.next());
            assertTrue(socket.setWritable(false, false) && socket.setReadable(false, false));
            shop[1] = true;
            assertEquals(TAKEN, send("START test-set-0/test-8"));
            assertEquals(IGNORED, send("NO-COMMAND"));
            assertTrue(socket.setWritable(true, false) && socket.setReadable(true, false));
            assertEquals(TAKEN, send("END test-set-0/test-8"));
            assertEquals("{\"id\":\"test-set-0/test-8\",\"executedLinesByFile\":{}}", data.next());
        } finally {
            socket.setWritable(true, false);
            socket.setReadable(true, false);
        }
        socket.delete();
        try (DataReceiver next = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertEquals(TAKEN, send("BACKGROUND"));
            shop[2] = true;
            assertEquals(TAKEN, send("START test-set-1/test-1"));
            assertEquals(background("test-set-0/test-8", "test-set-1/test-1", probes(SHOP, "2")),
                    next.nextBackground());
        }
    }

    @Test
    public void withTheScanOffEachTestTakesJacocosDumpAndBackgroundIsNotTaken() throws Exception {
        System.setProperty("keploy.java.dedup.diagnostics", "true");
        System.setProperty("keploy.java.scan", "Off");
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            shop[0] = true;
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            assertTrue(logged(" START test-set-0/test-1 took ").contains(" reset="));
            assertFalse("JaCoCo's reset cleared it", shop[0]);
            assertEquals(IGNORED, send("BACKGROUND"));
            assertNotNull(logged("Keploy dedup: KEPLOY_JAVA_SCAN is off"));
            shop[1] = true;
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{\"" + SHOP + "\":[1]}}",
                    data.next());
            String end = logged(" END test-set-0/test-1 took ");
            assertTrue(end, end.contains("dump=") && !end.contains(" scan="));
            assertEquals(0, count("background hits cleared"));
        }
    }

    @Test
    public void anyOtherScanValueKeepsTheScan() throws Exception {
        System.setProperty("keploy.java.dedup.diagnostics", "true");
        System.setProperty("keploy.java.scan", "on");
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            assertTrue(logged(" START test-set-0/test-1 took ").contains(" scan="));
            assertEquals(TAKEN, send("BACKGROUND"));
        }
    }

    @Test
    public void anEndWhoseScanFailsTakesJacocosDumpFromThenOn() throws Exception {
        System.setProperty("keploy.java.dedup.diagnostics", "true");
        ProbeScanTest.CountingMap entries = ProbeScanTest.CountingMap.swapInto(agent.data);
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            assertTrue(logged(" START test-set-0/test-1 took ").contains(" scan="));

            // The test loads a class, so the END walks JaCoCo's store, and the walk fails.
            shop[1] = true;
            entries.fail = true;
            agent.probes(0x77L, "org/lib/Loaded", 2);
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{\"" + SHOP + "\":[1]}}",
                    data.next());
            assertTrue(logged(" END test-set-0/test-1 took ").contains("dump="));
            assertNotNull(logged("Keploy dedup: the probe scan failed; each test now takes JaCoCo's dump and reset"));

            // The store could be walked again: the scan stays dropped all the same.
            entries.fail = false;
            assertEquals(TAKEN, send("START test-set-0/test-2"));
            assertTrue(logged(" START test-set-0/test-2 took ").contains(" reset="));
            shop[2] = true;
            assertEquals(TAKEN, send("END test-set-0/test-2"));
            assertEquals("{\"id\":\"test-set-0/test-2\",\"executedLinesByFile\":{\"" + SHOP + "\":[2]}}",
                    data.next());
            assertTrue(logged(" END test-set-0/test-2 took ").contains("dump="));
        }
    }

    @Test
    public void aStartWhoseScanFailsResetsInstead() throws Exception {
        System.setProperty("keploy.java.dedup.diagnostics", "true");
        ProbeScanTest.CountingMap entries = ProbeScanTest.CountingMap.swapInto(agent.data);
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        entries.fail = true;
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            shop[0] = true;
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            assertTrue(logged(" START test-set-0/test-1 took ").contains(" reset="));
            assertFalse("JaCoCo's reset cleared the background", shop[0]);
            shop[3] = true;
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{\"" + SHOP + "\":[3]}}",
                    data.next());
        }
    }

    @Test
    public void ofTwoCopiesTheIndexDidNotReadTheOneTheManifestNamesIsReported() throws Exception {
        boolean[] high = agent.probes(HIGH_ID, SHOP, 4);
        boolean[] low = agent.probes(LOW_ID, SHOP, 3);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            // Only the copy with the higher id runs first: the manifest names it.
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            high[3] = true;
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals(test("test-set-0/test-1", probes(SHOP, "3"), SHOP), data.next());
            // Both run: the probes sent are still that copy's.
            assertEquals(TAKEN, send("START test-set-0/test-2"));
            high[2] = true;
            low[0] = true;
            assertEquals(TAKEN, send("END test-set-0/test-2"));
            assertEquals(test("test-set-0/test-2", probes(SHOP, "2"), SHOP), data.next());
        }
    }

    @Test
    public void theCopyTheManifestNamesIsFoundWhenItsIdHasFewerThan16HexDigits() throws Exception {
        // Neither copy is the one the index read. The one the manifest will
        // name has a 4-digit id and is not the lowest: a hex form compared
        // with the manifest's padded one would never find it.
        long named = 0x1234L;
        boolean[] high = agent.probes(named, SHOP, 4);
        boolean[] low = agent.probes(LOW_ID, SHOP, 3);
        assertTrue(Long.compareUnsigned(LOW_ID, named) < 0);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            high[3] = true;
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals(test("test-set-0/test-1", probes(SHOP, "3"), SHOP), data.next());
            // Both run: the probes sent are still the named copy's, every time.
            for (int n = 2; n <= 3; n++) {
                assertEquals(TAKEN, send("START test-set-0/test-" + n));
                high[2] = true;
                low[0] = true;
                assertEquals(TAKEN, send("END test-set-0/test-" + n));
                assertEquals(test("test-set-0/test-" + n, probes(SHOP, "2"), SHOP), data.next());
            }
        }
    }

    @Test
    public void aClassLoadedTwiceReportsTheCopyTheIndexReadOverTheOneTheManifestNames() throws Exception {
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        boolean[] twin = agent.probes(HIGH_ID, SHOP, 3);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            // Only the other copy runs first: the manifest names it.
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            twin[2] = true;
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals(test("test-set-0/test-1", probes(SHOP, "2"), SHOP), data.next());
            // Both run: the copy the index read.
            assertEquals(TAKEN, send("START test-set-0/test-2"));
            shop[1] = true;
            twin[0] = true;
            assertEquals(TAKEN, send("END test-set-0/test-2"));
            assertEquals(test("test-set-0/test-2", probes(SHOP, "1"), null), data.next());
        }
    }

    @Test
    public void aClassLoadedTwiceSendsTheOtherCopysProbesWhenOnlyItRan() throws Exception {
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        boolean[] twin = agent.probes(LOW_ID, SHOP, 3);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            // Only the copy the index read, with the higher id, runs first:
            // the manifest names it.
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            shop[3] = true;
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{\"" + SHOP + "\":[3]}}",
                    data.next());
            // Only the other copy runs: its probes are sent under the class's
            // name, as JaCoCo's dump sends them, not dropped, and the class
            // is named in classMismatch.
            assertEquals(TAKEN, send("START test-set-0/test-2"));
            twin[2] = true;
            assertEquals(TAKEN, send("END test-set-0/test-2"));
            assertEquals(test("test-set-0/test-2", probes(SHOP, "2"), SHOP), data.next());
            assertFalse("the copy's hit was cleared", twin[2]);
        }
    }

    @Test
    public void anEndWhoseScanLostHitsIsNotTaken() throws Exception {
        System.setProperty("keploy.java.dedup.diagnostics", "true");
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            shop[1] = true;
            ProbeScanTest.breakSlot(scanOf(), 0);
            // JaCoCo's dump would hold only what the scan did not clear: not taken.
            assertEquals(IGNORED, send("END test-set-0/test-1"));
            assertNotNull(logged("Keploy dedup: the probe scan failed; each test now takes JaCoCo's dump and reset"));

            // JaCoCo's reset clears what the failed pass left.
            assertEquals(TAKEN, send("START test-set-0/test-2"));
            assertTrue(logged(" START test-set-0/test-2 took ").contains(" reset="));
            shop[2] = true;
            assertEquals(TAKEN, send("END test-set-0/test-2"));
            assertEquals("no record for the END not taken",
                    "{\"id\":\"test-set-0/test-2\",\"executedLinesByFile\":{\"" + SHOP + "\":[2]}}",
                    data.next());
        }
    }

    @Test
    public void anErrorReadingJacocosClassesAtAnEndTakesJacocosDump() throws Exception {
        ProbeScanTest.CountingMap entries = ProbeScanTest.CountingMap.swapInto(agent.data);
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            shop[1] = true;
            entries.error = new NoClassDefFoundError("org/jacoco/core/data/ExecutionData");
            agent.probes(0x77L, "org/lib/Loaded", 2);
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{\"" + SHOP + "\":[1]}}",
                    data.next());
            entries.error = null;
            assertEquals(TAKEN, send("START test-set-0/test-2"));
        }
    }

    @Test
    public void anAgentWhoseFieldsCannotBeReadKeepsTheServer() throws Exception {
        Thread.currentThread().setContextClassLoader(new RtLoader(brokenAgent(), getClass().getClassLoader()));
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            assertNotNull(logged("Keploy dedup: JaCoCo's probes cannot be read in place"));
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{}}", data.next());
        }
    }

    // brokenAgent is an agent with JaCoCo's getExecutionData and reset, and a
    // field of a type that is not there: reading its fields throws
    // NoClassDefFoundError.
    private static Object brokenAgent() throws ReflectiveOperationException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/BrokenAgent", null,
                "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "data", "Lmissing/RuntimeData;", null, null).visitEnd();
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor dump = writer.visitMethod(Opcodes.ACC_PUBLIC, "getExecutionData", "(Z)[B", null, null);
        dump.visitCode();
        dump.visitInsn(Opcodes.ICONST_0);
        dump.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE);
        dump.visitInsn(Opcodes.ARETURN);
        dump.visitMaxs(0, 0);
        dump.visitEnd();
        MethodVisitor reset = writer.visitMethod(Opcodes.ACC_PUBLIC, "reset", "()V", null, null);
        reset.visitCode();
        reset.visitInsn(Opcodes.RETURN);
        reset.visitMaxs(0, 0);
        reset.visitEnd();
        writer.visitEnd();
        final byte[] bytes = writer.toByteArray();
        ClassLoader loader = new ClassLoader(InProcessScanTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!name.equals("test.BrokenAgent")) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        return Class.forName("test.BrokenAgent", true, loader).getConstructor().newInstance();
    }

    // Request is a request of the app's server, as ServerEntries counts it:
    // in RequestGate from when it comes in until it is done, on a thread of
    // its own. Its response reaches Keploy (responded) before it is done:
    // tail runs after it, once release opens.
    private static final class Request implements Closeable {

        private final CountDownLatch responded = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch done = new CountDownLatch(1);
        private final Thread thread;

        Request(final Runnable before, final Runnable tail) {
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Object token = RequestGate.enter();
                    try {
                        before.run();
                        responded.countDown();
                        release.await();
                        tail.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        RequestGate.exit(token);
                        done.countDown();
                    }
                }
            }, "app-request");
            thread.setDaemon(true);
            thread.start();
        }

        void awaitResponse() throws InterruptedException {
            assertTrue(responded.await(10, TimeUnit.SECONDS));
        }

        // finishOnceEndWaits lets the tail run, from another thread, once a
        // thread (the END the caller sends) waits in RequestGate, or after
        // 10 s; so the END reads the hits only if it waited for the tail.
        void finishOnceEndWaits() {
            Thread later = new Thread(new Runnable() {
                @Override
                public void run() {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (!endWaits() && System.nanoTime() < deadline) {
                        Thread.yield();
                    }
                    release.countDown();
                }
            });
            later.setDaemon(true);
            later.start();
        }

        void finish() throws InterruptedException {
            release.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        }

        @Override
        public void close() {
            release.countDown();
            thread.interrupt();
        }
    }

    private static Runnable hit(final boolean[] probes, final int... indices) {
        return new Runnable() {
            @Override
            public void run() {
                for (int index : indices) {
                    probes[index] = true;
                }
            }
        };
    }

    @Test
    public void anEndWaitsForItsTestsRequestToFinishAfterItsResponse() throws Exception {
        // A filter's code after the chain runs after Keploy has the response
        // and sent END: read at once, END missed it, and the next START
        // cleared it as background, so the test's hits changed run to run.
        System.setProperty("keploy.java.end.wait.ms", "2000");
        System.setProperty("keploy.java.dedup.diagnostics", "true");
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            assertEquals(TAKEN, send("BACKGROUND"));
            try (Request request = new Request(hit(shop, 1), hit(shop, 2))) {
                request.awaitResponse();
                request.finishOnceEndWaits();
                long began = System.nanoTime();
                assertEquals(TAKEN, send("END test-set-0/test-1"));
                assertTrue("END ended when the request did, not at its bound",
                        System.nanoTime() - began < TimeUnit.MILLISECONDS.toNanos(1500));
                assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{\"" + SHOP + "\":[1,2]}}",
                        data.next());
                assertTrue(logged(" END test-set-0/test-1 took ").contains(" requests="));
            }
            assertEquals(TAKEN, send("START test-set-0/test-2"));
            assertEquals(TAKEN, send("END test-set-0/test-2"));
            assertEquals("{\"id\":\"test-set-0/test-2\",\"executedLinesByFile\":{}}", data.next());
            assertNull("the tail is test-1's, not background", data.background.poll());
        }
        assertEquals(0, count("windowCut"));
    }

    @Test
    public void anEndWhoseRequestOutrunsTheWaitIsCutAndOpensNoWindow() throws Exception {
        System.setProperty("keploy.java.end.wait.ms", "50");
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        boolean[] cart = agent.probes(CART_ID, CART, 3);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            assertEquals(TAKEN, send("BACKGROUND"));
            try (Request request = new Request(hit(shop, 1), hit(shop, 2))) {
                request.awaitResponse();
                long began = System.nanoTime();
                assertEquals(TAKEN, send("END test-set-0/test-1"));
                assertTrue("END waited its bound",
                        System.nanoTime() - began >= TimeUnit.MILLISECONDS.toNanos(50));
                // What it read at the cut, and the flag, after classMismatch.
                assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{\"" + SHOP + "\":[1]},"
                        + "\"windowCut\":true}", data.next());
                assertNotNull(logged("test-set-0/test-1's requests still ran 50 ms after its END, so its"
                        + " record says windowCut"));
                // The tail runs after the END: the cut test's own late hit.
                request.finish();
            }
            assertEquals(TAKEN, send("START test-set-0/test-2"));
            assertFalse("cleared", shop[2]);
            assertEquals(TAKEN, send("END test-set-0/test-2"));
            assertEquals("{\"id\":\"test-set-0/test-2\",\"executedLinesByFile\":{}}", data.next());
            // The windows after it count as usual.
            cart[1] = true;
            assertEquals(TAKEN, send("START test-set-0/test-3"));
            assertEquals(TAKEN, send("END test-set-0/test-3"));
            assertEquals("{\"id\":\"test-set-0/test-3\",\"executedLinesByFile\":{}}", data.next());
            assertEquals("a START after a cut END is no window: test-1's late hit is not background",
                    background("test-set-0/test-2", "test-set-0/test-3", probes(CART, "1")), data.nextBackground());
            assertNull(data.background.poll());
        }
    }

    @Test
    public void anEndDoesNotWaitForARequestThatCameInBeforeItsStart() throws Exception {
        // A stream opened before the test, or a request of the test before
        // cut at its bound: never this test's to wait for.
        System.setProperty("keploy.java.end.wait.ms", "2000");
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath);
                Request stream = new Request(hit(shop, 0), hit(shop, 3))) {
            stream.awaitResponse();
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            shop[1] = true;
            long began = System.nanoTime();
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertTrue(System.nanoTime() - began < TimeUnit.MILLISECONDS.toNanos(1500));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{\"" + SHOP + "\":[1]}}",
                    data.next());
        }
    }

    @Test
    public void withAWaitOfZeroAnEndReadsAtOnceAsBefore() throws Exception {
        System.setProperty("keploy.java.end.wait.ms", "0");
        boolean[] shop = agent.probes(SHOP_ID, SHOP, 4);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            try (Request request = new Request(hit(shop, 1), hit(shop, 2))) {
                request.awaitResponse();
                assertEquals(TAKEN, send("END test-set-0/test-1"));
                // The tail is lost to this test, and the record has no flag.
                assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{\"" + SHOP + "\":[1]}}",
                        data.next());
                request.finish();
            }
        }
        assertEquals(0, count("windowCut"));
    }

    @Test
    public void theEndWaitIsReadFromItsSetting() throws ReflectiveOperationException {
        ((java.util.concurrent.atomic.AtomicBoolean) read(KeployDedupAgent.class, null, "END_WAIT_INVALID_LOGGED"))
                .set(false);
        ((java.util.concurrent.atomic.AtomicBoolean) read(KeployDedupAgent.class, null, "END_WAIT_CAPPED_LOGGED"))
                .set(false);
        assertEquals(200, KeployDedupAgent.endWaitMillis());
        System.setProperty("keploy.java.end.wait.ms", " 0 ");
        assertEquals(0, KeployDedupAgent.endWaitMillis());
        System.setProperty("keploy.java.end.wait.ms", "1500");
        assertEquals(1500, KeployDedupAgent.endWaitMillis());
        System.setProperty("keploy.java.end.wait.ms", "2000");
        assertEquals(2000, KeployDedupAgent.endWaitMillis());
        assertEquals(0, count("its most"));
        System.setProperty("keploy.java.end.wait.ms", "2001");
        assertEquals(2000, KeployDedupAgent.endWaitMillis());
        System.setProperty("keploy.java.end.wait.ms", "10000");
        assertEquals(2000, KeployDedupAgent.endWaitMillis());
        assertEquals("said once in a JVM", 1,
                count("KEPLOY_JAVA_END_WAIT_MS is 2001, so a test's END waits up to 2000 ms, its most"));
        assertEquals(1, count("its most"));
        System.setProperty("keploy.java.end.wait.ms", "-1");
        assertEquals(200, KeployDedupAgent.endWaitMillis());
        System.setProperty("keploy.java.end.wait.ms", "soon");
        assertEquals(200, KeployDedupAgent.endWaitMillis());
        assertEquals(200, KeployDedupAgent.endWaitMillis());
        assertEquals("said once in a JVM", 1, count("KEPLOY_JAVA_END_WAIT_MS is not a number"));
    }

    // endWaits says whether a thread waits in RequestGate.
    private static boolean endWaits() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (java.util.concurrent.locks.LockSupport.getBlocker(thread) == RequestGate.class) {
                return true;
            }
        }
        return false;
    }

    // scanOf is the ProbeScan the running agent's collector looked up.
    private static ProbeScan scanOf() throws ReflectiveOperationException {
        Object worker = read(KeployDedupAgent.class, null, "coverageWorker");
        Object collector = read(worker.getClass(), worker, "collector");
        ProbeScan scan = (ProbeScan) read(collector.getClass(), collector, "probeScan");
        assertNotNull("the collector scans JaCoCo's probes in place", scan);
        return scan;
    }

    private static Object read(Class<?> type, Object owner, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    // logged waits for the log line holding text, and returns it.
    private String logged(String text) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            for (String line : logged) {
                if (line.contains(text)) {
                    return line;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no log line holds \"" + text + "\" in " + logged);
    }

    private int count(String text) {
        int n = 0;
        for (String line : logged) {
            if (line.contains(text)) {
                n++;
            }
        }
        return n;
    }

    // background is the BACKGROUND record of the window from the END of
    // after to the START of before, whose probes by class are classes
    // (probes(...) joined by ","), with lost when it is more than 0.
    private static String background(String after, String before, String classes, String mismatch, int lost) {
        return "{\"kind\":\"background\",\"v\":1,\"build\":\"" + BUILD + "\",\"after\":\"" + after
                + "\",\"before\":\"" + before + "\",\"executedLinesByFile\":{" + classes + "}"
                + (mismatch == null ? "" : ",\"classMismatch\":[\"" + mismatch + "\"]")
                + (lost > 0 ? ",\"lost\":" + lost : "") + "}";
    }

    private static String background(String after, String before, String classes, int lost) {
        return background(after, before, classes, null, lost);
    }

    private static String background(String after, String before, String classes) {
        return background(after, before, classes, 0);
    }

    private static String probes(String className, String probes) {
        return "\"" + className + "\":[" + probes + "]";
    }

    // test is a test's coverage record as the SDK sends it: classes as in
    // background, and classMismatch naming mismatch when it is not null.
    private static String test(String id, String classes, String mismatch) {
        return "{\"id\":\"" + id + "\",\"executedLinesByFile\":{" + classes + "}"
                + (mismatch == null ? "" : ",\"classMismatch\":[\"" + mismatch + "\"]") + "}";
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

    // RtLoader defines JacocoRtStub as org.jacoco.agent.rt.RT, with its agent
    // set, and leaves every other class to its parent, which has no RT.
    static final class RtLoader extends ClassLoader {

        private static final String RT = "org.jacoco.agent.rt.RT";

        RtLoader(Object agent, ClassLoader parent) throws ReflectiveOperationException {
            super(parent);
            Class.forName(RT, true, this).getField("agent").set(null, agent);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.equals(RT)) {
                throw new ClassNotFoundException(name);
            }
            String stub = JacocoRtStub.class.getName().replace('.', '/');
            try (InputStream in = getParent().getResourceAsStream(stub + ".class")) {
                ClassWriter writer = new ClassWriter(0);
                new ClassReader(readAll(in)).accept(
                        new ClassRemapper(writer, new SimpleRemapper(stub, RT.replace('.', '/'))), 0);
                byte[] bytes = writer.toByteArray();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }

    private static final class DataReceiver implements Closeable {

        private static final AtomicInteger BOUND = new AtomicInteger();

        private final AFUNIXServerSocket server;
        private final BlockingQueue<String> payloads = new LinkedBlockingQueue<>();
        // The BACKGROUND records, in the order they came.
        final BlockingQueue<String> background = new LinkedBlockingQueue<>();
        // The INDEX reports, in the order they came.
        final BlockingQueue<String> index = new LinkedBlockingQueue<>();
        // The kind of each record ("index", "background", "test"), in the
        // order they came.
        final List<String> kinds = new CopyOnWriteArrayList<>();
        // The build of the last INDEX report taken ("" before one), and the
        // first test record whose build was not it.
        private String indexBuild = "";
        private volatile String buildMismatch;

        DataReceiver(String path) throws IOException {
            server = AFUNIXServerSocket.newInstance();
            server.bind(AFUNIXSocketAddress.of(new File(path)), 50);
            // The socket file keeps a second name until the row's folder is
            // removed, so that a receiver bound anew on the path never gets
            // this one's inode back. Bound anew within one tick of the file
            // system's clock after this one's bind, it would have the same
            // mtime too, and the SDK, which tells receivers apart by the
            // file's inode and mtime, would take it for this one: a stated
            // limit, which a receiver bound anew for a new run or the next
            // test set, long after, never meets.
            try {
                Files.createLink(new File(path + "." + BOUND.incrementAndGet() + ".held").toPath(),
                        new File(path).toPath());
            } catch (IOException | RuntimeException e) {
                server.close();
                throw e;
            }
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
                    if (payload.startsWith("{\"kind\":\"background\",")) {
                        kinds.add("background");
                        background.add(payload);
                    } else if (payload.startsWith("{\"kind\":\"index\",")) {
                        kinds.add("index");
                        indexBuild = RecordBuilds.indexBuild(payload);
                        index.add(payload);
                    } else {
                        kinds.add("test");
                        // Its build is the INDEX's before it (RecordBuilds).
                        if (buildMismatch == null) {
                            buildMismatch = RecordBuilds.mismatch(payload, indexBuild);
                        }
                        payloads.add(RecordBuilds.checked(payload, indexBuild));
                    }
                } catch (IOException e) {
                    // Closed.
                }
            }
        }

        String next() throws InterruptedException {
            String p = payloads.poll(30, TimeUnit.SECONDS);
            RecordBuilds.failIfMismatched(buildMismatch);
            return p;
        }

        String nextBackground() throws InterruptedException {
            return background.poll(30, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws IOException {
            server.close();
            RecordBuilds.failIfMismatched(buildMismatch);
        }
    }
}
