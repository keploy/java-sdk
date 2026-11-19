package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.runtime.RuntimeData;
import org.junit.After;
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
            "keploy.build.tag"};
    private static final String TAKEN = "ACK proto=2\n";
    private static final String IGNORED = "IGNORED proto=2\n";
    private static final String SHOP = "com/acme/Shop";

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
        boolean[] shop = agent.probes(0x1234L, SHOP, 4);
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
            assertTrue("a library class is left alone", lib[0]);

            // Nothing ran between the tests, or in the second one.
            assertEquals(TAKEN, send("START test-set-0/test-2"));
            assertEquals(TAKEN, send("END test-set-0/test-2"));
            assertEquals("{\"id\":\"test-set-0/test-2\",\"executedLinesByFile\":{}}", data.next());
            assertEquals("one background line, for the first START", 1, count("background hits cleared"));
        }
    }

    @Test
    public void aClassLoadedTwiceReportsTheOneWithTheLowerId() throws Exception {
        boolean[] shop = agent.probes(0x1234L, SHOP, 4);
        // The same class from other bytes, in a second class loader.
        boolean[] twin = agent.probes(0x99L, SHOP, 3);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            shop[3] = true;
            twin[1] = true;
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{\"" + SHOP + "\":[1]}}",
                    data.next());
            assertFalse("both were cleared", shop[3] || twin[1]);
        }
    }

    @Test
    public void withDiagnosticsOffTheBackgroundIsNotLogged() throws Exception {
        boolean[] shop = agent.probes(0x1234L, SHOP, 4);
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
    public void anEndWhoseScanFailsTakesJacocosDumpFromThenOn() throws Exception {
        System.setProperty("keploy.java.dedup.diagnostics", "true");
        ProbeScanTest.CountingMap entries = ProbeScanTest.CountingMap.swapInto(agent.data);
        boolean[] shop = agent.probes(0x1234L, SHOP, 4);
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
        boolean[] shop = agent.probes(0x1234L, SHOP, 4);
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
    public void aClassLoadedTwiceReportsTheOneTheManifestNames() throws Exception {
        boolean[] shop = agent.probes(0x1234L, SHOP, 4);
        boolean[] twin = agent.probes(0x99L, SHOP, 3);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            // Only the copy with the higher id runs first: the manifest names it.
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            shop[3] = true;
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{\"" + SHOP + "\":[3]}}",
                    data.next());
            // Both run: the probes sent are still that copy's.
            assertEquals(TAKEN, send("START test-set-0/test-2"));
            shop[2] = true;
            twin[0] = true;
            assertEquals(TAKEN, send("END test-set-0/test-2"));
            assertEquals("{\"id\":\"test-set-0/test-2\",\"executedLinesByFile\":{\"" + SHOP + "\":[2]}}",
                    data.next());
        }
    }

    @Test
    public void aClassLoadedTwiceSendsTheOtherCopysProbesWhenOnlyItRan() throws Exception {
        boolean[] shop = agent.probes(0x1234L, SHOP, 4);
        boolean[] twin = agent.probes(0x99L, SHOP, 3);
        try (DataReceiver data = new DataReceiver(KeployDedupAgent.dataSocketPath)) {
            assertTrue(KeployDedupAgent.start());
            // Only the copy with the higher id runs first: the manifest names it.
            assertEquals(TAKEN, send("START test-set-0/test-1"));
            shop[3] = true;
            assertEquals(TAKEN, send("END test-set-0/test-1"));
            assertEquals("{\"id\":\"test-set-0/test-1\",\"executedLinesByFile\":{\"" + SHOP + "\":[3]}}",
                    data.next());
            // Only the other copy runs: its probes are sent under the class's
            // name, as JaCoCo's dump sends them, not dropped.
            assertEquals(TAKEN, send("START test-set-0/test-2"));
            twin[2] = true;
            assertEquals(TAKEN, send("END test-set-0/test-2"));
            assertEquals("{\"id\":\"test-set-0/test-2\",\"executedLinesByFile\":{\"" + SHOP + "\":[2]}}",
                    data.next());
            assertFalse("the copy's hit was cleared", twin[2]);
        }
    }

    @Test
    public void anEndWhoseScanLostHitsIsNotTaken() throws Exception {
        System.setProperty("keploy.java.dedup.diagnostics", "true");
        boolean[] shop = agent.probes(0x1234L, SHOP, 4);
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
        boolean[] shop = agent.probes(0x1234L, SHOP, 4);
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
    private static final class RtLoader extends ClassLoader {

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

        String next() throws InterruptedException {
            return payloads.poll(30, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
