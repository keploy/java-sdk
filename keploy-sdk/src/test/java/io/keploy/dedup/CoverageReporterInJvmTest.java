package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Rows for the in-JVM coverage mode: the numbers it reports must equal the
 * offline CoverageReporter's (the coverage-runner Job's) for the same union and
 * manifest, and both must equal JaCoCo's own analysis of the real execution
 * data. The sample app (sample.shop.Shop) runs under JaCoCo's instrumenter as
 * two "test cases"; their fired probes are the union, exactly as the collector
 * builds it from the COV frames.
 */
public class CoverageReporterInJvmTest {

    private static final Gson GSON = new Gson();
    private static final String[] SAMPLE = {"sample/shop/Shop", "sample/shop/Shop$Basket"};

    private static Map<String, byte[]> classes;
    private static Map<String, List<Integer>> union;
    private static Map<String, CoverageReporter.ClassMeta> manifest;
    private static long[] groundTruth;

    private Path tempDir;

    @BeforeClass
    public static void runSampleUnderJacoco() throws Exception {
        classes = new LinkedHashMap<>();
        for (String name : SAMPLE) {
            classes.put(name, resource(name + ".class"));
        }

        LoggerRuntime runtime = new LoggerRuntime();
        RuntimeData data = new RuntimeData();
        runtime.startup(data);
        try {
            Instrumenter instrumenter = new Instrumenter(runtime);
            final Map<String, byte[]> instrumented = new HashMap<>();
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                instrumented.put(e.getKey().replace('/', '.'), instrumenter.instrument(e.getValue(), e.getKey()));
            }
            ClassLoader loader = new ClassLoader(CoverageReporterInJvmTest.class.getClassLoader()) {
                @Override
                protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    byte[] bytes = instrumented.get(name);
                    if (bytes == null) {
                        return super.loadClass(name, resolve);
                    }
                    Class<?> loaded = findLoadedClass(name);
                    return loaded != null ? loaded : defineClass(name, bytes, 0, bytes.length);
                }
            };
            Class<?> shop = loader.loadClass("sample.shop.Shop");
            Method price = shop.getMethod("price", int.class, String.class);
            Method parse = shop.getMethod("parse", String.class);

            // Test case 1: a gold order. Test case 2: a large plain order and a
            // bad number. Neither takes the quantity<=0 throw or the silver tier.
            List<ExecutionDataStore> perTest = new ArrayList<>();
            data.reset();
            assertEquals(21, price.invoke(null, 3, "gold"));
            perTest.add(collect(data));
            data.reset();
            assertEquals(90, price.invoke(null, 10, "bronze"));
            assertEquals(-1, parse.invoke(null, "x1"));
            perTest.add(collect(data));

            union = new TreeMap<>();
            manifest = new TreeMap<>();
            ExecutionDataStore merged = new ExecutionDataStore();
            for (ExecutionDataStore store : perTest) {
                for (ExecutionData ed : store.getContents()) {
                    merged.put(new ExecutionData(ed.getId(), ed.getName(), ed.getProbes().clone()));
                    CoverageReporter.ClassMeta meta = new CoverageReporter.ClassMeta();
                    meta.id = Long.toHexString(ed.getId());
                    meta.probeCount = ed.getProbes().length;
                    manifest.put(ed.getName(), meta);
                    TreeSet<Integer> fired = new TreeSet<>(union.containsKey(ed.getName())
                            ? union.get(ed.getName()) : Collections.<Integer>emptyList());
                    for (int i = 0; i < ed.getProbes().length; i++) {
                        if (ed.getProbes()[i]) {
                            fired.add(i);
                        }
                    }
                    union.put(ed.getName(), new ArrayList<>(fired));
                }
            }
            assertEquals("both sample classes ran", 2, manifest.size());

            // Ground truth: JaCoCo's own analysis of the real merged execution data.
            CoverageBuilder builder = new CoverageBuilder();
            Analyzer analyzer = new Analyzer(merged, builder);
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                analyzer.analyzeClass(e.getValue(), e.getKey());
            }
            groundTruth = sums(builder);
        } finally {
            runtime.shutdown();
        }
        // The sample is only a useful row if lines and branches are partly covered.
        assertTrue(groundTruth[0] > 0 && groundTruth[0] < groundTruth[1]);
        assertTrue(groundTruth[2] > 0 && groundTruth[2] < groundTruth[3]);
    }

    @After
    public void cleanUp() throws IOException {
        KeployDedupAgent.stop();
        System.clearProperty("keploy.coverage.endpoint");
        System.clearProperty("keploy.java.class.dirs");
        System.clearProperty("keploy.java.classpath.fallback");
        if (tempDir != null) {
            List<Path> paths = new ArrayList<>();
            Files.walk(tempDir).forEach(paths::add);
            Collections.reverse(paths);
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Test
    public void inJvmEqualsOfflineAndJacoco() throws Exception {
        CoverageReporter.CoverageResult inJvm = CoverageReporter.computeInJvm(classes, union, manifest);
        CoverageReporter.CoverageResult offline = offline();

        assertEquals(GSON.toJson(offline), GSON.toJson(inJvm));
        assertEquals(groundTruth[0], inJvm.lineCovered);
        assertEquals(groundTruth[1], inJvm.lineTotal);
        assertEquals(groundTruth[2], inJvm.branchCovered);
        assertEquals(groundTruth[3], inJvm.branchTotal);
        assertEquals(groundTruth[4], inJvm.instructionCovered);
        assertEquals(groundTruth[5], inJvm.instructionTotal);
        assertEquals(groundTruth[6], inJvm.methodCovered);
        assertEquals(groundTruth[7], inJvm.methodTotal);
        assertEquals(2, inJvm.classCount);
        assertEquals(2, inJvm.hitClassCount);
    }

    @Test
    public void offlineOutputKeepsItsShape() throws Exception {
        // The offline --out JSON gains no key: testSetId is set only on a REPORT reply.
        JsonObject json = GSON.fromJson(GSON.toJson(offline()), JsonObject.class);
        assertEquals(new TreeSet<>(java.util.Arrays.asList("lineCovered", "lineTotal", "branchCovered",
                        "branchTotal", "instructionCovered", "instructionTotal", "methodCovered", "methodTotal",
                        "classCount", "hitClassCount")),
                new TreeSet<>(json.keySet()));
    }

    @Test
    public void totalsCountAClassOnceLikeCoverageBuilder() throws Exception {
        // The same class twice (same name, same id) counts once, as with
        // CoverageBuilder, which the offline reporter used before.
        Map<String, List<Integer>> u = union;
        ExecutionDataStore store = storeFor(manifest, u);
        CoverageBuilder builder = new CoverageBuilder();
        CoverageReporter.Totals totals = new CoverageReporter.Totals();
        for (org.jacoco.core.analysis.ICoverageVisitor visitor
                : new org.jacoco.core.analysis.ICoverageVisitor[] {builder, totals}) {
            Analyzer analyzer = new Analyzer(store, visitor);
            for (int pass = 0; pass < 2; pass++) {
                for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                    analyzer.analyzeClass(e.getValue(), e.getKey());
                }
            }
        }
        long[] want = sums(builder);
        assertEquals(builder.getClasses().size(), totals.result.classCount);
        assertEquals(want[0], totals.result.lineCovered);
        assertEquals(want[1], totals.result.lineTotal);
        assertEquals(want[2], totals.result.branchCovered);
        assertEquals(want[3], totals.result.branchTotal);
        assertEquals(want[6], totals.result.methodCovered);
        assertEquals(want[7], totals.result.methodTotal);
        assertEquals(groundTruth[0], want[0]);
    }

    @Test
    public void totalsRejectADifferentClassWithTheSameName() throws Exception {
        // A second class named like the first but with other bytes (another id)
        // fails with CoverageBuilder's own message.
        Map<String, byte[]> clash = new LinkedHashMap<>();
        clash.put("sample/shop/Shop", classes.get("sample/shop/Shop"));
        clash.put("sample/shop/Shop.copy", renamed(classes.get("sample/shop/Shop$Basket"), "sample/shop/Shop"));
        try {
            CoverageReporter.computeInJvm(clash, union, manifest);
            fail("expected a duplicate-class failure");
        } catch (IOException e) {
            Throwable cause = e.getCause();
            assertNotNull(e.toString(), cause);
            assertEquals("Can't add different class with same name: sample/shop/Shop", cause.getMessage());
        }
    }

    @Test
    public void reportReplyCarriesTheCoverageAndTheTestSet() {
        String reply = KeployDedupAgent.reportReply("test-set-3", request(union, manifest), () -> classes,
                () -> Collections.<String, CoverageReporter.ClassMeta>emptyMap());
        JsonObject json = replyJson(reply);
        assertEquals("test-set-3", json.get("testSetId").getAsString());
        assertEquals(groundTruth[0], json.get("lineCovered").getAsLong());
        assertEquals(groundTruth[1], json.get("lineTotal").getAsLong());
        assertEquals(groundTruth[2], json.get("branchCovered").getAsLong());
        assertEquals(groundTruth[3], json.get("branchTotal").getAsLong());
        assertFalse(json.has("error"));
    }

    @Test
    public void reportReplyWithoutAManifestUsesTheLiveOne() {
        String reply = KeployDedupAgent.reportReply("ts", request(union, null), () -> classes, () -> manifest);
        JsonObject json = replyJson(reply);
        assertEquals(groundTruth[0], json.get("lineCovered").getAsLong());
        assertEquals(groundTruth[2], json.get("branchCovered").getAsLong());
    }

    @Test
    public void reportReplyPrefersTheRequestManifest() {
        // An empty live manifest would leave every class unhit.
        Map<String, CoverageReporter.ClassMeta> live = new HashMap<>();
        String reply = KeployDedupAgent.reportReply("ts", request(union, manifest), () -> classes, () -> live);
        assertEquals(groundTruth[0], replyJson(reply).get("lineCovered").getAsLong());
        String liveOnly = KeployDedupAgent.reportReply("ts", request(union, null), () -> classes, () -> live);
        assertEquals(0, replyJson(liveOnly).get("lineCovered").getAsLong());
    }

    @Test
    public void reportReplyFailsClosedWithNoCounters() {
        String noUnion = "report request has no union";
        String[][] bad = {
            {"", noUnion},
            {"null", noUnion},
            {"{}", noUnion},
            {"{\"manifest\":{}}", noUnion},
            {"{not json", "JsonSyntaxException: "},
            {"[1,2]", "JsonSyntaxException: "},
        };
        for (String[] row : bad) {
            assertErrorReply(row[0], row[1], KeployDedupAgent.reportReply("ts", row[0], () -> classes,
                    () -> manifest));
        }
        Map<String, byte[]> garbage = new LinkedHashMap<>();
        garbage.put("sample/shop/Broken", new byte[] {(byte) 0xCA, (byte) 0xFE, 0, 1});
        assertErrorReply("garbage class", "IOException: Error while analyzing sample/shop/Broken.class",
                KeployDedupAgent.reportReply("ts", request(union, manifest), () -> garbage, () -> manifest));
        Map<String, CoverageReporter.ClassMeta> badId = new HashMap<>();
        CoverageReporter.ClassMeta meta = new CoverageReporter.ClassMeta();
        meta.id = "not-hex";
        meta.probeCount = 3;
        badId.put("sample/shop/Shop", meta);
        assertErrorReply("bad id", "NumberFormatException: ",
                KeployDedupAgent.reportReply("ts", request(union, badId), () -> classes, () -> manifest));
        // The app's classes or its live manifest cannot be read: still one error reply.
        assertErrorReply("classes unreadable", "IllegalStateException: index failed",
                KeployDedupAgent.reportReply("ts", request(union, manifest), () -> {
                    throw new IllegalStateException("index failed");
                }, () -> manifest));
        assertErrorReply("live manifest unreadable", "IllegalStateException: manifest failed",
                KeployDedupAgent.reportReply("ts", request(union, null), () -> classes, () -> {
                    throw new IllegalStateException("manifest failed");
                }));
    }

    @Test
    public void offlineRefusesAMissingClassesPath() throws Exception {
        CoverageReporter.CoverageResult ok = offline();
        assertEquals(groundTruth[0], ok.lineCovered);
        Path missing = tempDir.resolve("no-such-classes");
        try {
            CoverageReporter.compute(missing, tempDir.resolve("union.json"), tempDir.resolve("manifest.json"));
            fail("expected a missing-classes failure");
        } catch (IOException e) {
            assertEquals("classes path does not exist: " + missing, e.getMessage());
        }
    }

    @Test
    public void reportOverTheTcpChannelEqualsOffline() throws Exception {
        tempDir = Files.createTempDirectory("keploy-injvm");
        File classDir = tempDir.resolve("classes").toFile();
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            File f = new File(classDir, e.getKey() + ".class");
            assertTrue(f.getParentFile().isDirectory() || f.getParentFile().mkdirs());
            Files.write(f.toPath(), e.getValue());
        }
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(20000);
            System.setProperty("keploy.java.class.dirs", classDir.getAbsolutePath());
            System.setProperty("keploy.java.classpath.fallback", "false");
            System.setProperty("keploy.coverage.endpoint", "127.0.0.1:" + server.getLocalPort());
            assertTrue(KeployDedupAgent.start());
            try (Socket conn = server.accept()) {
                conn.setSoTimeout(20000);
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(),
                        StandardCharsets.UTF_8));
                OutputStream out = conn.getOutputStream();
                assertEquals("the SDK names REPORT first", "CAPS REPORT", in.readLine());

                // A REPORT with no test set id is a malformed command: no reply,
                // as for any malformed command. The next REPORT's reply comes first.
                send(out, "REPORT");
                // A REPORT with no body is answered with an error, then ACK.
                send(out, "REPORT test-set-1");
                assertErrorReply("no body", "report request has no union", in.readLine());
                assertEquals("ACK", in.readLine());

                send(out, "REPORT  test-set-0   " + request(union, manifest));
                JsonObject json = replyJson(in.readLine());
                assertEquals("ACK", in.readLine());
                assertEquals("test-set-0", json.get("testSetId").getAsString());
                json.remove("testSetId");
                assertEquals(GSON.toJson(offline()), GSON.toJson(json));
                assertEquals(groundTruth[0], json.get("lineCovered").getAsLong());
                assertEquals(groundTruth[2], json.get("branchCovered").getAsLong());
            }
        }
    }

    // --- helpers ---

    private CoverageReporter.CoverageResult offline() throws IOException {
        Path dir = tempDir != null ? tempDir : (tempDir = Files.createTempDirectory("keploy-injvm"));
        Path zip = dir.resolve("classes.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey() + ".class"));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        Path unionFile = dir.resolve("union.json");
        Path manifestFile = dir.resolve("manifest.json");
        Files.write(unionFile, GSON.toJson(union).getBytes(StandardCharsets.UTF_8));
        Files.write(manifestFile, GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
        return CoverageReporter.compute(zip, unionFile, manifestFile);
    }

    private static String request(Map<String, List<Integer>> u, Map<String, CoverageReporter.ClassMeta> m) {
        KeployDedupAgent.ReportRequest r = new KeployDedupAgent.ReportRequest();
        r.union = u;
        r.manifest = m;
        return GSON.toJson(r);
    }

    private static JsonObject replyJson(String reply) {
        assertNotNull("a reply line", reply);
        assertTrue(reply, reply.startsWith("REPORT {"));
        assertFalse(reply, reply.contains("\n"));
        return GSON.fromJson(reply.substring("REPORT ".length()), JsonObject.class);
    }

    private static void assertErrorReply(String what, String errorPrefix, String reply) {
        JsonObject json = replyJson(reply);
        assertTrue(what + ": " + reply, json.has("error") && json.get("error").getAsString().startsWith(errorPrefix));
        assertTrue(what + ": the reply names its test set: " + reply, json.has("testSetId"));
        assertFalse(what + ": a failure carries no counters: " + reply, json.has("lineCovered"));
        assertNull(what, json.get("branchTotal"));
    }

    private static void send(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static ExecutionDataStore collect(RuntimeData data) {
        ExecutionDataStore store = new ExecutionDataStore();
        data.collect(store, new SessionInfoStore(), false);
        return store;
    }

    private static ExecutionDataStore storeFor(Map<String, CoverageReporter.ClassMeta> m,
                                               Map<String, List<Integer>> u) {
        ExecutionDataStore store = new ExecutionDataStore();
        for (Map.Entry<String, CoverageReporter.ClassMeta> e : m.entrySet()) {
            boolean[] probes = new boolean[e.getValue().probeCount];
            for (Integer i : u.get(e.getKey())) {
                probes[i] = true;
            }
            store.put(new ExecutionData(Long.parseUnsignedLong(e.getValue().id, 16), e.getKey(), probes));
        }
        return store;
    }

    private static long[] sums(CoverageBuilder builder) {
        long[] s = new long[8];
        for (IClassCoverage cc : builder.getClasses()) {
            s[0] += cc.getLineCounter().getCoveredCount();
            s[1] += cc.getLineCounter().getTotalCount();
            s[2] += cc.getBranchCounter().getCoveredCount();
            s[3] += cc.getBranchCounter().getTotalCount();
            s[4] += cc.getInstructionCounter().getCoveredCount();
            s[5] += cc.getInstructionCounter().getTotalCount();
            s[6] += cc.getMethodCounter().getCoveredCount();
            s[7] += cc.getMethodCounter().getTotalCount();
        }
        return s;
    }

    // renamed rewrites a class's own name, so it is a different class (other
    // bytes, other id) that carries the given name.
    private static byte[] renamed(byte[] bytes, final String newName) {
        org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(bytes);
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        final String oldName = reader.getClassName();
        reader.accept(new org.objectweb.asm.commons.ClassRemapper(writer,
                new org.objectweb.asm.commons.SimpleRemapper(oldName, newName)), 0);
        return writer.toByteArray();
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = CoverageReporterInJvmTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(name, in);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
