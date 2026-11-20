package io.keploy.dedup;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jacoco.core.internal.analysis.KeployBranchCoverage;
import org.jacoco.core.internal.data.CRC64;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Rows for the build table: the java-dedup sample's classes (compiled once with javac --release 8 -g from
 * keploy/samples-java java-dedup, kept as .class.bin) encode to the golden table byte for byte. The golden
 * (java-dedup.table.ndjson, java-dedup.build) is the Go encoder's output (enterprise pkg/service/codecoverage
 * NewTable + EncodeTable) over these records, with the hashes and the build id computed in Go from the class
 * bytes, so the order, the JSON form, the fold and the ids are Go's, not this class's. So is edge.table.ndjson,
 * over a set of classes generated here ({@link #edge}) for the names, packages and escapes the sample lacks.
 *
 * <p>To regenerate a golden: run these rows with -Dbuildtable.dump=DIR (each golden row writes DIR/NAME.ndjson
 * and the class files as DIR/NAME/VM-NAME.class.bin), then in an enterprise checkout read each record with
 * codecoverage's Unit JSON, recompute its hash and the build id from the class files with JaCoCo's CRC64,
 * NewTable(LanguageJava, build, units) and EncodeTable, and write the result back over the golden here.
 */
public class BuildTableTest {

    private static final String RES = "/io/keploy/dedup/buildtable/";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static List<BuildTable.ClassFile> javaDedup() throws IOException, URISyntaxException {
        URL root = BuildTableTest.class.getResource(RES + "java-dedup");
        assertNotNull("java-dedup class files", root);
        Path dir = Paths.get(root.toURI());
        List<BuildTable.ClassFile> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".class.bin")).sorted().collect(Collectors.toList())) {
                String rel = dir.relativize(p).toString().replace(File.separatorChar, '/');
                out.add(new BuildTable.ClassFile(rel.substring(0, rel.length() - ".class.bin".length()),
                        Files.readAllBytes(p)));
            }
        }
        assertEquals(5, out.size());
        return out;
    }

    static byte[] resource(String name) throws IOException {
        try (InputStream in = BuildTableTest.class.getResourceAsStream(RES + name)) {
            assertNotNull(name, in);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                b.write(buf, 0, n);
            }
            return b.toByteArray();
        }
    }

    /** sha256Hex is the sha256 of b as 64 lowercase hex, as a frame's "sha256" says it. */
    static String sha256Hex(byte[] b) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) {
                hex.append(String.format("%02x", x));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Collects the records write hands out, as the NDJSON stream. */
    static final class Collect implements BuildTable.RecordSink {
        final ByteArrayOutputStream ndjson = new ByteArrayOutputStream();
        final List<String> records = new ArrayList<>();
        String build;

        @Override
        public void record(String build, byte[] json) {
            this.build = build;
            records.add(new String(json, StandardCharsets.UTF_8));
            ndjson.write(json, 0, json.length);
            ndjson.write('\n');
        }
    }

    static Collect table(List<BuildTable.ClassFile> files) throws IOException {
        Collect c = new Collect();
        assertNotNull(BuildTable.write(files, c));
        return c;
    }

    /** dump writes a golden row's output and class files under -Dbuildtable.dump, to regenerate its golden. */
    static void dump(String name, List<BuildTable.ClassFile> files, Collect c) throws IOException {
        String dir = System.getProperty("buildtable.dump");
        if (dir == null) {
            return;
        }
        Files.createDirectories(Paths.get(dir));
        Files.write(Paths.get(dir, name + ".ndjson"), c.ndjson.toByteArray());
        for (BuildTable.ClassFile f : files) {
            Path p = Paths.get(dir, name, f.name + ".class.bin");
            Files.createDirectories(p.getParent());
            Files.write(p, f.bytes);
        }
    }

    @Test
    public void javaDedupTableIsTheGoEncodersByteForByte() throws Exception {
        List<BuildTable.ClassFile> files = javaDedup();
        Collect c = table(files);
        dump("java-dedup", files, c);
        assertEquals(new String(resource("java-dedup.build"), StandardCharsets.UTF_8).trim(), c.build);
        assertEquals(new String(resource("java-dedup.table.ndjson"), StandardCharsets.UTF_8),
                new String(c.ndjson.toByteArray(), StandardCharsets.UTF_8));
        // The same classes in another order give the same bytes.
        List<BuildTable.ClassFile> reversed = new ArrayList<>(files);
        java.util.Collections.reverse(reversed);
        assertArrayEquals(c.ndjson.toByteArray(), table(reversed).ndjson.toByteArray());
    }

    static Map<String, JsonObject> byId(List<String> lines) {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        for (String l : lines) {
            if (l.trim().isEmpty()) {
                continue;
            }
            JsonObject o = JsonParser.parseString(l).getAsJsonObject();
            out.put(o.get("id").getAsString(), o);
        }
        return out;
    }

    static Map<String, JsonObject> methods(JsonObject unit) {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        for (JsonElement m : unit.getAsJsonArray("methods")) {
            out.put(m.getAsJsonObject().get("id").getAsString(), m.getAsJsonObject());
        }
        return out;
    }

    // per line: [code leaves, branch edges]
    static Map<Integer, List<Integer>> lineShape(JsonObject method) {
        Map<Integer, List<Integer>> out = new LinkedHashMap<>();
        for (JsonElement e : method.getAsJsonArray("leaves")) {
            JsonArray l = e.getAsJsonArray();
            List<Integer> s = out.computeIfAbsent(l.get(0).getAsInt(), k -> new ArrayList<>(Arrays.asList(0, 0)));
            s.set(l.get(5).getAsInt(), s.get(l.get(5).getAsInt()) + 1);
        }
        return out;
    }

    /**
     * The contract fixture (fixtures/java-dedup) is hand-made: its leaf counts (n = 3), hashes and the model
     * classes' lines are illustrative, it lists only some of DedupController's methods and not Product, and
     * its simpleMessage takes jakarta's HttpServletRequest where the sample imports javax's. What it holds as
     * real must agree: every fixture class is a record with its id, pkg, name and path; every fixture method
     * of DedupController is a method with the same display name and first line; and the two methods with
     * branches have the fixture's lines and branch edges.
     */
    @Test
    public void javaDedupTableAgreesWithTheContractFixture() throws Exception {
        Map<String, JsonObject> ours = byId(table(javaDedup()).records);
        Map<String, JsonObject> fixture = byId(Arrays.asList(
                new String(resource("fixture-java-dedup-table.ndjson"), StandardCharsets.UTF_8).split("\n")));
        assertEquals(4, fixture.size());
        for (JsonObject f : fixture.values()) {
            String id = f.get("id").getAsString();
            JsonObject o = ours.get(id);
            assertNotNull(id, o);
            for (String k : new String[] {"kind", "pkg", "name", "path", "state"}) {
                assertEquals(id + " " + k, f.get(k), o.get(k));
            }
        }
        String ctl = "c:io/keploy/samples/javadedup/DedupController";
        Map<String, JsonObject> fm = methods(fixture.get(ctl));
        Map<String, JsonObject> om = methods(ours.get(ctl));
        for (Map.Entry<String, JsonObject> e : fm.entrySet()) {
            String mid = e.getKey().replace("jakarta/servlet", "javax/servlet");
            JsonObject o = om.get(mid);
            assertNotNull(mid, o);
            assertEquals(mid, e.getValue().get("name"), o.get("name"));
            assertEquals(mid, e.getValue().get("line"), o.get("line"));
        }
        for (String m : new String[] {"messageForPath(Ljava/lang/String;)Ljava/lang/String;",
                "response([Ljava/lang/Object;)Ljava/util/Map;"}) {
            String mid = "m:io/keploy/samples/javadedup/DedupController#" + m;
            assertEquals(mid, lineShape(fm.get(mid)), lineShape(om.get(mid)));
        }
    }

    static byte[] patchedMajor(byte[] bytes, int major) {
        byte[] b = bytes.clone();
        b[6] = (byte) (major >> 8);
        b[7] = (byte) major;
        return b;
    }

    @Test
    public void aClassJacocoCannotAnalyzeIsNotMeasuredAndSoIsItsUnit() throws Exception {
        List<BuildTable.ClassFile> files = new ArrayList<>(javaDedup());
        BuildTable.ClassFile item = null;
        for (BuildTable.ClassFile f : files) {
            if (f.name.endsWith("/model/Item")) {
                item = f;
            }
        }
        assertNotNull(item);
        // A nested class of Item no ASM in this SDK reads (class file version 99), and a class that is
        // not a class file at all.
        files.add(new BuildTable.ClassFile(item.name + "$Later", patchedMajor(item.bytes, 99)));
        files.add(new BuildTable.ClassFile("io/keploy/samples/javadedup/Junk", "not a class".getBytes(StandardCharsets.UTF_8)));
        Map<String, JsonObject> ours = byId(table(files).records);
        Map<String, JsonObject> golden = byId(Arrays.asList(
                new String(resource("java-dedup.table.ndjson"), StandardCharsets.UTF_8).split("\n")));

        JsonObject it = ours.get("c:io/keploy/samples/javadedup/model/Item");
        assertEquals("not_measured", it.get("state").getAsString());
        assertEquals("{\"code\":\"analyzer_failed\"}", it.get("reason").toString());
        assertEquals(0, it.getAsJsonArray("methods").size());
        assertEquals("its own class file still names the path", "io/keploy/samples/javadedup/model/Item.java",
                it.get("path").getAsString());
        assertFalse("the hash takes the new class file's id", golden.get(it.get("id").getAsString())
                .get("hash").equals(it.get("hash")));

        JsonObject junk = ours.get("c:io/keploy/samples/javadedup/Junk");
        assertEquals("not_measured", junk.get("state").getAsString());
        assertEquals("", junk.get("path").getAsString());
        assertEquals("[]", junk.get("methods").toString());

        // The other classes are as they were.
        for (String id : golden.keySet()) {
            if (!id.endsWith("/model/Item")) {
                assertEquals(id, golden.get(id), ours.get(id));
            }
        }
    }

    @Test
    public void aRecordLargerThanAChunkInflatesToIsRecordTooLarge() throws Exception {
        Collect c = new Collect();
        BuildTable.Summary s = BuildTable.write(javaDedup(), c, 1600);
        assertEquals(5, s.records);
        int tooLarge = 0;
        for (String r : c.records) {
            assertTrue(r, r.length() + 1 <= 1600);
            if (r.contains("\"record_too_large\"")) {
                tooLarge++;
                assertTrue(r, r.contains("\"state\":\"not_measured\"") && r.endsWith("\"methods\":[]}"));
            }
        }
        // DedupController and Product are the two records past 1600 bytes.
        assertEquals(2, tooLarge);
        assertEquals(2, s.notMeasured);
    }

    @Test
    public void aProbeMapPastItsBytesOrStepsIsRecordTooLargeWithNoProbeRecord() throws Exception {
        String name = "gen/big/Returns";
        byte[] bytes = BuildTableBudgetTest.returns(name, 200);
        List<BuildTable.ClassFile> one = Collections.singletonList(new BuildTable.ClassFile(name, bytes));
        Collect probes = new Collect();
        BuildTable.write(one, new Collect(), probes, BuildTable.MAX_RECORD_BYTES);
        assertEquals(1, probes.records.size());
        // What the map takes: its record and newline (ASCII), and its probes' walks (the method has a line, so
        // the table's walks are all of them).
        int mapBytes = probes.records.get(0).length() + 1;
        long[] steps = {0};
        KeployBranchCoverage.analyze(bytes, new HashMap<>(), (m, r, h) -> steps[0] += h.steps);
        // A chain of 200 early returns: each probe walks back over the checks before it.
        assertTrue(steps[0] + " steps", steps[0] > 200L * 200);
        assertTrue(mapBytes + " bytes", mapBytes > 100_000);

        long[][] bounds = {{mapBytes, steps[0]}, {mapBytes - 1, steps[0]}, {mapBytes, steps[0] - 1}};
        for (int i = 0; i < bounds.length; i++) {
            boolean at = i == 0;
            Collect table = new Collect();
            Collect map = new Collect();
            BuildTable.Summary s = BuildTable.write(one, table, map, BuildTable.MAX_RECORD_BYTES,
                    (int) bounds[i][0], bounds[i][1]);
            String why = "bytes " + bounds[i][0] + ", steps " + bounds[i][1];
            assertEquals(why, 1, s.records);
            assertEquals(why, at ? 0 : 1, s.notMeasured);
            assertEquals(why, at ? 0 : 1, s.tooLarge);
            assertEquals(why, 0, s.notMapped);
            assertEquals(why, at ? 1 : 0, s.probeRecords);
            assertEquals(why, at ? 1 : 0, map.records.size());
            String rec = table.records.get(0);
            if (at) {
                assertTrue(rec, rec.contains("\"state\":\"ok\""));
                assertEquals(probes.records.get(0), map.records.get(0));
            } else {
                assertTrue(rec, rec.contains("\"state\":\"not_measured\",\"reason\":{\"code\":\"record_too_large\"}")
                        && rec.endsWith("\"methods\":[]}"));
            }
        }
    }

    @Test
    public void theTableAnswerBoundsTheHeapByTheIndexCountHeldAndReadsNoClassFile() {
        long mb = 1 << 20;
        String property = System.getProperty(BuildTable.MIN_HEAP_PROPERTY);
        System.clearProperty(BuildTable.MIN_HEAP_PROPERTY);
        try (Logs logs = new Logs()) {
            // 63 MiB free. The classes are never read here: the count is the index's when it is read already.
            BuildTable.Heap heap = new BuildTable.Heap(512 * mb, 449 * mb, true);
            Supplier<List<BuildTable.ClassFile>> never = () -> {
                throw new AssertionError("the answer read the classes");
            };
            File none = new File("/nonexistent/keploy-data.sock");
            assertEquals(KeployDedupAgent.UNIX_REPLY_IGNORED + " reason=low_heap heap_free_mb=63 heap_needed_mb=66",
                    BuildTable.answer(heap, 1025, never, none));
            assertEquals("50,000 classes", KeployDedupAgent.UNIX_REPLY_IGNORED
                    + " reason=low_heap heap_free_mb=63 heap_needed_mb=113", BuildTable.answer(heap, 50_000, never,
                    none));
            assertEquals("an index not read yet: the base alone", KeployDedupAgent.UNIX_REPLY_IGNORED
                    + " reason=low_heap heap_free_mb=63 heap_needed_mb=64", BuildTable.answer(heap, -1, never, none));
            assertEquals(3, logs.records.size());
        } finally {
            if (property != null) {
                System.setProperty(BuildTable.MIN_HEAP_PROPERTY, property);
            }
        }
    }

    /** Reads frames back: each is KTB1, a length, a header line and the next bytes of the stream. */
    static final class Frames implements BuildTable.FrameSender {
        final List<byte[]> frames = new ArrayList<>();

        @Override
        public void send(byte[] frame) {
            frames.add(frame);
        }
    }

    @Test
    public void framesCarryTheStreamInOrderAndNeverPassTheCap() throws Exception {
        Collect c = table(javaDedup());
        for (int cap : new int[] {1100, 1500, BuildTable.MAX_FRAME_BYTES}) {
            Frames sent = new Frames();
            BuildTable.FrameWriter w = new BuildTable.FrameWriter(sent, cap);
            BuildTable.Summary s = BuildTable.write(javaDedup(), w);
            w.finish(s.build, s.records);
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            for (int i = 0; i < sent.frames.size(); i++) {
                byte[] f = sent.frames.get(i);
                assertTrue("frame " + i + " is " + f.length + " bytes", f.length <= cap);
                assertEquals(BuildTable.MAGIC, new String(f, 0, 4, StandardCharsets.US_ASCII));
                DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(f, 4, f.length - 4));
                int n = in.readInt();
                assertEquals(f.length - 8, n);
                int nl = 8;
                while (f[nl] != '\n') {
                    nl++;
                }
                JsonObject h = JsonParser.parseString(new String(f, 8, nl - 8, StandardCharsets.UTF_8)).getAsJsonObject();
                assertEquals(1, h.get("v").getAsInt());
                assertEquals("table", h.get("part").getAsString());
                assertEquals(c.build, h.get("build").getAsString());
                assertEquals(i, h.get("seq").getAsInt());
                boolean last = i == sent.frames.size() - 1;
                assertEquals(last, h.get("last").getAsBoolean());
                if (last) {
                    assertEquals(5, h.get("records").getAsInt());
                    assertEquals(sha256Hex(c.ndjson.toByteArray()), h.get("sha256").getAsString());
                } else {
                    assertFalse(h.has("records"));
                }
                stream.write(f, nl + 1, f.length - nl - 1);
            }
            if (cap < 2000) {
                assertTrue(sent.frames.size() > 5);
            } else {
                assertEquals(1, sent.frames.size());
            }
            assertArrayEquals(c.ndjson.toByteArray(), stream.toByteArray());
        }
    }

    @Test
    public void theGoJsonFormIsKept() {
        StringBuilder sb = new StringBuilder();
        BuildTable.Json.string(sb, "#<init>&\"\\\b\f\n\r\t\u0001\u001f\u007f  é😀\uD800x");
        assertEquals("\"#<init>&\\\"\\\\\\b\\f\\n\\r\\t\\u0001\\u001f\u007f\\u2028\\u2029é😀�x\"",
                sb.toString());
        assertTrue(BuildTable.comparePkg("a", "a/b") < 0);
        assertTrue(BuildTable.comparePkg("a/b", "a/b/c") < 0);
        assertTrue(BuildTable.comparePkg("a/b/c", "a-x") < 0);
        assertTrue(BuildTable.comparePkg("", "a") < 0);
        // Code point order, as Go orders UTF-8 bytes: U+FF5E sorts before U+1F600.
        assertTrue(BuildTable.compareCodePoints("～", "😀") < 0);
    }

    @Test
    public void namesFollowTheContract() {
        assertEquals("Foo(String, int[])", BuildTable.Names.method("Foo", "", "<init>", "(Ljava/lang/String;[I)V", false));
        assertEquals("Inner.Deep(long)", BuildTable.Names.method("Foo", "$Inner$Deep", "<init>", "(J)V", false));
        assertEquals("Inner.bar(int)", BuildTable.Names.method("Foo", "$Inner", "bar", "(I)V", false));
        assertEquals("static {}", BuildTable.Names.method("Foo", "", "<clinit>", "()V", false));
        assertEquals("bar lambda #0", BuildTable.Names.method("Foo", "", "lambda$bar$0", "(Ljava/lang/Object;)V", false));
        assertEquals("response(Object...)", BuildTable.Names.method("Foo", "", "response", "([Ljava/lang/Object;)V", true));
        assertEquals("of(Map.Entry, byte[][])", BuildTable.Names.method("Foo", "", "of", "(Ljava/util/Map$Entry;[[B)V", false));
        assertEquals("apply()", BuildTable.Names.method("Foo", "$", "apply", "()V", false));
    }

    static String header(byte[] frame) {
        int nl = 8;
        while (frame[nl] != '\n') {
            nl++;
        }
        return new String(frame, 8, nl - 8, StandardCharsets.UTF_8);
    }

    @Test
    public void noClassesIsOneEmptyLastFrameOfEachPart() throws Exception {
        BuildTable.Summary s = BuildTable.write(new ArrayList<BuildTable.ClassFile>(), new Collect());
        assertEquals("", s.build);
        assertEquals(0, s.records);
        Frames f = new Frames();
        BuildTable.send(ArrayList::new, f);
        assertEquals(2, f.frames.size());
        assertEquals("{\"v\":1,\"part\":\"table\",\"build\":\"\",\"seq\":0,\"last\":true,\"records\":0,\"sha256\":"
                + "\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"}", header(f.frames.get(0)));
        assertEquals("{\"v\":1,\"part\":\"probes\",\"build\":\"\",\"seq\":0,\"last\":true,\"records\":0,\"sha256\":"
                + "\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"}", header(f.frames.get(1)));
    }

    // ---------------------------------------------------------------- an edge set, against Go

    /**
     * edgeClass generates a class: a constructor, run (varargs, an if and a switch), a lambda, a method on
     * run's first line, two methods with odd names (a quote, #, $ and U+2028) and a static initializer on line 1; with lines false, none
     * of them has line numbers.
     */
    static byte[] edgeClass(String name, String source, boolean lines, int base) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        if (source != null) {
            cw.visitSource(source, null);
        }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        Label l0 = new Label();
        mv.visitLabel(l0);
        if (lines) {
            mv.visitLineNumber(base, l0);
        }
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        int line = base + 1;
        for (String m : new String[] {"run", "lambda$run$0", "same", "we\"ird#na$me", "a\u2028b"}) {
            boolean run = m.equals("run");
            mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | (run ? Opcodes.ACC_VARARGS : 0), m,
                    run ? "(I[Ljava/lang/String;)I" : "(I)I", null, null);
            mv.visitCode();
            int first = m.equals("same") ? base + 1 : line;
            Label a = new Label();
            mv.visitLabel(a);
            if (lines) {
                mv.visitLineNumber(first, a);
            }
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            Label els = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, els);
            Label b = new Label();
            mv.visitLabel(b);
            if (lines) {
                mv.visitLineNumber(first + 1, b);
            }
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLabel(els);
            Label c = new Label();
            mv.visitLabel(c);
            if (lines) {
                mv.visitLineNumber(first + 2, c);
            }
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            Label d1 = new Label();
            Label d2 = new Label();
            Label d3 = new Label();
            Label dflt = new Label();
            mv.visitTableSwitchInsn(1, 3, dflt, d1, d2, d3);
            mv.visitLabel(d1);
            mv.visitInsn(Opcodes.ICONST_2);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLabel(d2);
            mv.visitInsn(Opcodes.ICONST_3);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLabel(d3);
            mv.visitInsn(Opcodes.ICONST_4);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLabel(dflt);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            line += 10;
        }
        mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        Label z = new Label();
        mv.visitLabel(z);
        if (lines) {
            mv.visitLineNumber(1, z);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The edge set: VM name and SourceFile (null: none). */
    static final String[][] EDGE = {
        {"a/b/Foo", "Foo.java"}, {"a/b/Foo$Bar", "Foo.java"}, {"a/b/Foo$1", "Foo.java"}, {"a/b/Foo$", "Foo.scala"},
        {"a/b/Foo$Bar$Deep", null}, {"a-x/Baz", "Baz.java"}, {"a/Qux", "sub/Qux.java"}, {"a/b/c/Deep", ""},
        {"Default", "Default.java"}, {"$Lead", "Lead.java"}, {"$Lead$In", "Lead.java"},
        {"a/ü/Ünï", "Ünï.java"}, {"a/b/Z～", "Z.java"}, {"a/b/Z😀", "Z.java"},
        {"a/b/Q\"uo\\te<&>", "Q\"uo\\te.java"}, {"a/b/Ctl\u0001\u001f\u007f", "C .java"},
        {"a/b/L S ", "L.java"}, {"a/b/NoLines", "NoLines.java"}, {"a/b/Foo$Bar$", null},
        {"a/b/Foo$$anon$1", "Foo.scala"}, {"a/b/module-info", null}, {"a/b/package-info", "package-info.java"},
    };

    static List<BuildTable.ClassFile> edge() {
        List<BuildTable.ClassFile> out = new ArrayList<>();
        int base = 10;
        for (String[] e : EDGE) {
            out.add(new BuildTable.ClassFile(e[0], edgeClass(e[0], e[1], !e[0].endsWith("NoLines"), base)));
            base += 7;
        }
        return out;
    }

    @Test
    public void anEdgeSetIsTheGoEncodersByteForByte() throws Exception {
        List<BuildTable.ClassFile> files = edge();
        Collect c = table(files);
        dump("edge", files, c);
        assertEquals(new String(resource("edge.build"), StandardCharsets.UTF_8).trim(), c.build);
        assertEquals(new String(resource("edge.table.ndjson"), StandardCharsets.UTF_8),
                new String(c.ndjson.toByteArray(), StandardCharsets.UTF_8));
        BuildTable.Summary s = BuildTable.write(files, new Collect());
        assertEquals(15, s.records);
        assertEquals("NoLines has code and no line numbers", 1, s.noLines);
        assertEquals(0, s.notMeasured);
    }

    @Test
    public void aClassIsNamedByItsBytesNotByWhereTheIndexFoundIt() throws Exception {
        byte[] foo = edgeClass("com/acme/Foo", "Foo.java", true, 10);
        byte[] in = edgeClass("com/acme/Foo$In", "Foo.java", true, 20);
        byte[] junk = "not a class".getBytes(StandardCharsets.UTF_8);
        Collect c = new Collect();
        BuildTable.Summary s = BuildTable.write(Arrays.asList(new BuildTable.ClassFile("classes/com/acme/Foo", foo),
                new BuildTable.ClassFile("a/", in), new BuildTable.ClassFile("a/", junk)), c);
        assertEquals(1, s.records);
        assertEquals("not a class, and its index name names no class", 1, s.unnamed);
        JsonObject u = JsonParser.parseString(c.records.get(0)).getAsJsonObject();
        assertEquals("c:com/acme/Foo", u.get("id").getAsString());
        assertEquals("com/acme", u.get("pkg").getAsString());
        assertEquals("com/acme/Foo.java", u.get("path").getAsString());
        assertEquals(IndexReport.classIdsSum(new long[] {CRC64.classId(foo), CRC64.classId(in)}),
                u.get("hash").getAsString());
        // Every indexed class is in the build id, as in the index record's, named or not.
        assertEquals(IndexReport.buildId(new long[] {CRC64.classId(foo), CRC64.classId(in), CRC64.classId(junk)}),
                s.build);
        assertTrue(c.records.get(0), c.records.get(0).contains("\"id\":\"m:com/acme/Foo$In#run(I[Ljava/lang/String;)I\""));
    }

    // ---------------------------------------------------------------- the data socket

    enum Mode {
        /** reads the whole frame and answers OK */
        OK,
        /** reads at most 512 bytes and closes, as the JSON decoder of an older Keploy does */
        READ_512_AND_CLOSE,
        /** reads to the end of the connection and closes without an answer */
        READ_TO_EOF
    }

    /** A receiver on a unix socket: what it read on each connection, and its answer per mode. */
    static final class Receiver implements AutoCloseable {
        final AFUNIXServerSocket server;
        final List<byte[]> got = new ArrayList<>();

        Receiver(File path, Mode mode) throws IOException {
            server = AFUNIXServerSocket.newInstance();
            server.bind(AFUNIXSocketAddress.of(path));
            Thread thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket s = server.accept()) {
                        s.setSoTimeout(15_000);
                        InputStream in = s.getInputStream();
                        byte[] read;
                        if (mode == Mode.OK) {
                            DataInputStream d = new DataInputStream(in);
                            byte[] magic = new byte[4];
                            d.readFully(magic);
                            byte[] body = new byte[d.readInt()];
                            d.readFully(body);
                            read = body;
                            // Kept before the OK, as Keploy keeps a frame before it answers: once the sender has
                            // its OK, count() holds the frame.
                            synchronized (got) {
                                got.add(read);
                            }
                            s.getOutputStream().write("OK\n".getBytes(StandardCharsets.UTF_8));
                            s.getOutputStream().flush();
                            continue;
                        } else if (mode == Mode.READ_512_AND_CLOSE) {
                            byte[] b = new byte[512];
                            int n = in.read(b);
                            read = Arrays.copyOf(b, Math.max(n, 0));
                        } else {
                            ByteArrayOutputStream all = new ByteArrayOutputStream();
                            byte[] b = new byte[8192];
                            for (int n; (n = in.read(b)) > 0; ) {
                                all.write(b, 0, n);
                            }
                            read = all.toByteArray();
                        }
                        synchronized (got) {
                            got.add(read);
                        }
                    } catch (IOException ignored) {
                        // closed, or the sender went away
                    }
                }
            }, "test-table-receiver");
            thread.setDaemon(true);
            thread.start();
        }

        int count() {
            synchronized (got) {
                return got.size();
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    /** Logs collects what BuildTable logs. */
    static final class Logs extends Handler implements AutoCloseable {
        final List<LogRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final Logger logger = Logger.getLogger(BuildTable.class.getName());

        Logs() {
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
        }
    }

    static List<BuildTable.ClassFile> javaDedupUnchecked() {
        try {
            return javaDedup();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test(timeout = 30_000)
    public void theSocketSenderSendsEveryFrameEachAnsweredOk() throws Exception {
        File sock = new File(tmp.getRoot(), "data.sock");
        try (Receiver r = new Receiver(sock, Mode.OK)) {
            BuildTable.FrameWriter w = new BuildTable.FrameWriter(new BuildTable.SocketSender(sock), 1500);
            BuildTable.Summary s = BuildTable.write(javaDedup(), w);
            w.finish(s.build, s.records);
            assertTrue(w.seq > 1);
            assertEquals(w.seq, r.count());
        }
    }

    @Test(timeout = 30_000)
    public void aReceiverThatClosesWithoutOkEndsTheStreamAtOnceAndNothingThrows() throws Exception {
        for (Mode mode : new Mode[] {Mode.READ_512_AND_CLOSE, Mode.READ_TO_EOF}) {
            File sock = new File(tmp.getRoot(), mode + ".sock");
            try (Receiver r = new Receiver(sock, mode); Logs logs = new Logs()) {
                long t0 = System.nanoTime();
                BuildTable.FrameWriter w = new BuildTable.FrameWriter(new BuildTable.SocketSender(sock), 1500);
                try {
                    BuildTable.Summary s = BuildTable.write(javaDedup(), w);
                    w.finish(s.build, s.records);
                    fail(mode + ": no OK, so the stream stops");
                } catch (IOException e) {
                    assertTrue(mode + ": " + e.getMessage(), e.getMessage().startsWith("TABLE frame 0: "));
                }
                assertEquals(mode.toString(), 0, w.seq);
                assertTrue(mode + ": the sender shut its output, so no read waited for the reply timeout",
                        System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(10));
                if (mode == Mode.READ_TO_EOF) {
                    while (r.count() == 0) {
                        Thread.sleep(10);
                    }
                    synchronized (r.got) {
                        assertEquals(1, r.got.size());
                        byte[] f = r.got.get(0);
                        assertEquals(BuildTable.MAGIC, new String(f, 0, 4, StandardCharsets.US_ASCII));
                        int n = ((f[4] & 0xff) << 24) | ((f[5] & 0xff) << 16) | ((f[6] & 0xff) << 8) | (f[7] & 0xff);
                        assertEquals("the whole frame, then the end of the connection", 8 + n, f.length);
                    }
                }
                // send never throws: it says why at WARNING, with no stack trace.
                BuildTable.send(BuildTableTest::javaDedupUnchecked, new BuildTable.SocketSender(sock));
                LogRecord last = logs.records.get(logs.records.size() - 1);
                assertEquals(Level.WARNING, last.getLevel());
                assertTrue(last.getMessage(), last.getMessage().contains("the build table was not sent: TABLE frame 0: "));
                assertNull(last.getThrown());
            }
        }
    }

    @Test(timeout = 30_000)
    public void noReceiverIsSaidSo() throws Exception {
        try (Logs logs = new Logs()) {
            BuildTable.send(BuildTableTest::javaDedupUnchecked,
                    new BuildTable.SocketSender(new File(tmp.getRoot(), "none.sock")));
            LogRecord last = logs.records.get(logs.records.size() - 1);
            assertTrue(last.getMessage(), last.getMessage().contains("TABLE frame 0: no Keploy listens on the data socket"));
        }
    }

    @Test(timeout = 30_000)
    public void aRequestedTableArrivesWithTheBuildId() throws Exception {
        File sock = new File(tmp.getRoot(), "table.sock");
        try (Receiver r = new Receiver(sock, Mode.OK)) {
            BuildTable.Sends sends = new BuildTable.Sends(BuildTable::thread);
            assertTrue(sends.request(() -> BuildTable.send(BuildTableTest::javaDedupUnchecked,
                    new BuildTable.SocketSender(sock))));
            String last = null;
            String lastProbes = null;
            while (last == null || lastProbes == null) {
                synchronized (r.got) {
                    for (byte[] b : r.got) {
                        String h = new String(b, StandardCharsets.UTF_8);
                        h = h.substring(0, h.indexOf('\n'));
                        if (h.contains("\"last\":true")) {
                            if (h.contains("\"part\":\"table\"")) {
                                last = h;
                            } else {
                                lastProbes = h;
                            }
                        }
                    }
                }
                Thread.sleep(20);
            }
            String build = new String(resource("java-dedup.build"), StandardCharsets.UTF_8).trim();
            assertTrue(last, last.startsWith("{\"v\":1,\"part\":\"table\",\"build\":\"" + build + "\","));
            assertTrue(last, last.contains("\"records\":5,"));
            assertTrue(lastProbes, lastProbes.startsWith("{\"v\":1,\"part\":\"probes\",\"build\":\"" + build + "\","));
            assertTrue(lastProbes, lastProbes.contains("\"records\":5,"));
        }
    }

    @Test(timeout = 30_000)
    public void theRunAfterASendIsTheNewestRequests() throws Exception {
        // A request names its index and data socket: after KeployDedupAgent.stop() and start() during a send, the
        // new server's TABLE must be served from the new index to the new socket, not the first request's again.
        CountDownLatch inFirst = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        BuildTable.Sends sends = new BuildTable.Sends(BuildTable::thread);
        assertTrue(sends.request(() -> {
            ran.add("old");
            inFirst.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(inFirst.await(10, TimeUnit.SECONDS));
        assertTrue(sends.request(() -> ran.add("middle")));
        assertTrue(sends.request(() -> ran.add("new")));
        release.countDown();
        while (ran.size() < 2) {
            Thread.sleep(10);
        }
        Thread.sleep(300);
        assertEquals(Arrays.asList("old", "new"), new ArrayList<>(ran));
    }

    @Test(timeout = 30_000)
    public void aRequestMadeWhileASendFailsStillRuns() throws Exception {
        // A send that throws is logged; the request made while it ran names the agent's newest index and data
        // socket, so it must still run, and Sends must take requests again after it.
        CountDownLatch inFirst = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch next = new CountDownLatch(1);
        CountDownLatch after = new CountDownLatch(1);
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        BuildTable.Sends sends = new BuildTable.Sends(BuildTable::thread);
        assertTrue(sends.request(() -> {
            ran.add("failing");
            inFirst.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("a send that fails (this row's own)");
        }));
        assertTrue(inFirst.await(10, TimeUnit.SECONDS));
        assertTrue(sends.request(() -> {
            ran.add("next");
            next.countDown();
        }));
        release.countDown();
        assertTrue("the request made while the send failed ran", next.await(10, TimeUnit.SECONDS));
        assertTrue(sends.request(() -> {
            ran.add("after");
            after.countDown();
        }));
        assertTrue("a request after them runs", after.await(10, TimeUnit.SECONDS));
        assertEquals(Arrays.asList("failing", "next", "after"), new ArrayList<>(ran));
    }

    /** Throwing is a JUL handler on BuildTable's logger that throws on every record, as an app's bridge can. */
    static final class Throwing extends Handler implements AutoCloseable {
        final AtomicInteger published = new AtomicInteger();
        private final Logger logger = Logger.getLogger(BuildTable.class.getName());

        Throwing() {
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            published.incrementAndGet();
            throw new IllegalStateException("a log handler that throws (this row's own)");
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
        }
    }

    @Test(timeout = 60_000)
    public void aLogHandlerThatThrowsNeitherFailsASendNorWedgesSends() throws Exception {
        List<BuildTable.ClassFile> classes = javaDedup();
        try (Throwing h = new Throwing()) {
            // send logs what it sent (INFO) or why it stopped (WARNING), and never throws.
            List<byte[]> frames = Collections.synchronizedList(new ArrayList<>());
            BuildTable.send(() -> classes, frames::add);
            assertFalse("the table was sent", frames.isEmpty());
            BuildTable.send(() -> {
                throw new IllegalStateException("classes that cannot be read (this row's own)");
            }, f -> fail("nothing to send"));
            assertEquals("both sends logged through the handler", 2, h.published.get());
            // Sends logs a send that throws through the same handler: the request made after it still runs.
            BuildTable.Sends sends = new BuildTable.Sends(BuildTable::thread);
            CountDownLatch failing = new CountDownLatch(1);
            assertTrue(sends.request(() -> {
                failing.countDown();
                throw new IllegalStateException("a send that fails (this row's own)");
            }));
            assertTrue(failing.await(10, TimeUnit.SECONDS));
            CountDownLatch next = new CountDownLatch(1);
            assertTrue(sends.request(next::countDown));
            assertTrue("a request after a send that failed runs", next.await(10, TimeUnit.SECONDS));
            // The production send through Sends, then one more request.
            CountDownLatch sent = new CountDownLatch(1);
            assertTrue(sends.request(() -> BuildTable.send(() -> classes, frame -> sent.countDown())));
            assertTrue(sent.await(20, TimeUnit.SECONDS));
            CountDownLatch last = new CountDownLatch(1);
            assertTrue(sends.request(last::countDown));
            assertTrue("a request after the production send runs", last.await(20, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 30_000)
    public void aRequestDuringASendRunsItOnceMore() throws Exception {
        CountDownLatch inFirst = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        Runnable send = () -> {
            if (runs.incrementAndGet() == 1) {
                inFirst.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        BuildTable.Sends sends = new BuildTable.Sends(BuildTable::thread);
        assertTrue(sends.request(send));
        assertTrue(inFirst.await(10, TimeUnit.SECONDS));
        assertTrue(sends.request(send));
        assertTrue(sends.request(send));
        release.countDown();
        while (runs.get() < 2) {
            Thread.sleep(10);
        }
        Thread.sleep(300);
        assertEquals("two requests during a send: one more run", 2, runs.get());
        assertTrue(sends.request(send));
        while (runs.get() < 3) {
            Thread.sleep(10);
        }
    }

    @Test
    public void aThreadThatCannotStartIsIgnoredNeverThrown() throws Exception {
        AtomicInteger made = new AtomicInteger();
        BuildTable.Sends sends = new BuildTable.Sends(r -> {
            if (made.incrementAndGet() == 1) {
                throw new OutOfMemoryError("unable to create native thread");
            }
            return BuildTable.thread(r);
        });
        CountDownLatch ran = new CountDownLatch(1);
        try (Logs logs = new Logs()) {
            assertFalse(sends.request(ran::countDown));
            assertEquals(Level.WARNING, logs.records.get(logs.records.size() - 1).getLevel());
        }
        assertTrue("nothing is left running: the next request starts", sends.request(ran::countDown));
        assertTrue(ran.await(10, TimeUnit.SECONDS));
    }

    // ---------------------------------------------------------------- what a send holds, and the heap it needs

    /** body is a frame's bytes after its header line. */
    static String body(byte[] frame) {
        int nl = 8;
        while (frame[nl] != '\n') {
            nl++;
        }
        return new String(frame, nl + 1, frame.length - nl - 1, StandardCharsets.UTF_8);
    }

    @Test
    public void eachClassesProbeRecordsGoOutAsItIsWrittenNotKeptForTheEnd() throws Exception {
        // Nothing of the probe map waits for the table's end in the heap: a measured unit's probe records reach
        // their sink just before its own table record, so before the next unit is analyzed.
        List<String> events = new ArrayList<>();
        BuildTable.Summary s = BuildTable.write(javaDedup(),
                (build, json) -> events.add("T" + new String(json, StandardCharsets.UTF_8)),
                (build, json) -> events.add("P" + new String(json, StandardCharsets.UTF_8)));
        assertEquals(5, s.records);
        assertEquals(5, s.probeRecords);
        List<String> since = new ArrayList<>();
        int units = 0;
        for (String e : events) {
            JsonObject o = JsonParser.parseString(e.substring(1)).getAsJsonObject();
            if (e.charAt(0) == 'P') {
                since.add(o.get("unit").getAsString());
                continue;
            }
            units++;
            boolean measured = "ok".equals(o.get("state").getAsString());
            assertEquals(e, measured, !since.isEmpty());
            for (String unit : since) {
                assertEquals("a probe record goes just before its own unit's table record", o.get("id").getAsString(),
                        unit);
            }
            since.clear();
        }
        assertEquals(5, units);
        assertTrue("no probe record after the last table record", since.isEmpty());
    }

    @Test
    public void theProbeMapWaitsInATempFileThatIsGoneOnceTheSendEnds() throws Exception {
        File dir = tmp.newFolder("spill");
        List<byte[]> frames = new ArrayList<>();
        List<String> during = new ArrayList<>();
        BuildTable.send(BuildTableTest::javaDedupUnchecked, frame -> {
            frames.add(frame);
            during.add(Arrays.toString(dir.list()));
        }, dir);
        assertEquals(2, frames.size());
        String build = new String(resource("java-dedup.build"), StandardCharsets.UTF_8).trim();
        byte[] map = resource("java-dedup.probes.ndjson");
        assertEquals("{\"v\":1,\"part\":\"probes\",\"build\":\"" + build + "\",\"seq\":0,\"last\":true,\"records\":5,"
                + "\"sha256\":\"" + sha256Hex(map) + "\"}", header(frames.get(1)));
        assertEquals(new String(map, StandardCharsets.UTF_8), body(frames.get(1)));
        assertEquals(new String(resource("java-dedup.table.ndjson"), StandardCharsets.UTF_8), body(frames.get(0)));
        assertArrayEquals("the temp file is removed", new String[0], dir.list());
        if (File.separatorChar == '/') {
            // Unlinked as soon as it is open: a JVM that dies mid-send leaves no file behind.
            assertEquals(Arrays.asList("[]", "[]"), during);
        }
    }

    @Test
    public void aTempDirTheAppCannotWriteSendsNoFrameAndSaysWhich() throws Exception {
        File notADir = tmp.newFile("not-a-dir");
        List<byte[]> frames = new ArrayList<>();
        AtomicInteger read = new AtomicInteger();
        try (Logs logs = new Logs()) {
            BuildTable.send(() -> {
                read.incrementAndGet();
                return javaDedupUnchecked();
            }, frames::add, notADir);
            assertTrue("no frame: Keploy gets no table for the build, and asks again next run", frames.isEmpty());
            assertEquals("the classes are not read", 0, read.get());
            LogRecord last = logs.records.get(logs.records.size() - 1);
            assertEquals(Level.WARNING, last.getLevel());
            assertTrue(last.getMessage(), last.getMessage().contains(notADir.getPath())
                    && last.getMessage().contains("java.io.tmpdir"));
        }
    }

    @Test
    public void aShortHeapDeclinesTheTableWithLowHeap() {
        long mb = 1 << 20;
        try (Logs logs = new Logs()) {
            assertEquals("reason=low_heap heap_free_mb=112 heap_needed_mb=256",
                    BuildTable.lowHeap(new BuildTable.Heap(512 * mb, 400 * mb, true), 256));
            assertEquals(1, logs.records.size());
            LogRecord r = logs.records.get(0);
            assertEquals(Level.WARNING, r.getLevel());
            assertTrue(r.getMessage(), r.getMessage().contains("112 MiB free of the 512 MiB heap cap the JVM reports"
                    + " (in use 400 MiB after the last collection), and it needs 256 MiB")
                    && r.getMessage().contains("low_heap")
                    && r.getMessage().contains("KEPLOY_COVERAGE_TABLE_MIN_HEAP_MB"));
        }
        assertNull("exactly what it needs is enough", BuildTable.lowHeap(new BuildTable.Heap(512 * mb, 256 * mb,
                true), 256));
        assertEquals("reason=low_heap heap_free_mb=255 heap_needed_mb=256",
                BuildTable.lowHeap(new BuildTable.Heap(512 * mb, 256 * mb + 1, true), 256));
        assertNull("0 turns the check off", BuildTable.lowHeap(new BuildTable.Heap(512 * mb, 512 * mb, false), 0));
        assertEquals("in use past the cap is no free heap", "reason=low_heap heap_free_mb=0 heap_needed_mb=1",
                BuildTable.lowHeap(new BuildTable.Heap(512 * mb, 600 * mb, false), 1));
        assertNull("a JVM with no heap cap", BuildTable.lowHeap(new BuildTable.Heap(Long.MAX_VALUE, 400 * mb,
                true), 256));
    }

    @Test
    public void theFreeHeapATableNeedsIsTheEnvElseThePropertyElse64MiBAnd1KiBAClass() {
        assertEquals(64, BuildTable.minHeapMb(null, null, 0));
        assertEquals(65, BuildTable.minHeapMb(" ", "", 1));
        assertEquals(65, BuildTable.minHeapMb(null, null, 1024));
        assertEquals(66, BuildTable.minHeapMb(null, null, 1025));
        assertEquals("50,000 classes", 113, BuildTable.minHeapMb(null, null, 50_000));
        assertEquals(100, BuildTable.minHeapMb("100", "50", 50_000));
        assertEquals(50, BuildTable.minHeapMb(" ", "50", 50_000));
        assertEquals(7, BuildTable.minHeapMb(" 7 ", null, 50_000));
        assertEquals(0, BuildTable.minHeapMb(null, "0", 50_000));
        try (Logs logs = new Logs()) {
            assertEquals(113, BuildTable.minHeapMb("-1", "50", 50_000));
            assertEquals(64, BuildTable.minHeapMb(null, "1g", 0));
            assertEquals(2, logs.records.size());
            assertEquals(Level.WARNING, logs.records.get(1).getLevel());
            assertTrue(logs.records.get(1).getMessage(), logs.records.get(1).getMessage().contains("'1g'")
                    && logs.records.get(1).getMessage().contains("needs 64 MiB"));
        }
    }

    @Test
    public void aTableOf50000ClassesIsBuiltWith113MiBFreeAndDeclinedWith112() {
        // Both edges of the default bound, as the answer reads it: the count of the index's class list.
        long mb = 1 << 20;
        long need = BuildTable.minHeapMb(null, null, 50_000);
        assertNull("113 MiB free is enough", BuildTable.lowHeap(new BuildTable.Heap(1024 * mb, (1024 - 113) * mb,
                true), need));
        try (Logs logs = new Logs()) {
            assertEquals("reason=low_heap heap_free_mb=112 heap_needed_mb=113", BuildTable.lowHeap(
                    new BuildTable.Heap(1024 * mb, (1024 - 113) * mb + 1, true), need));
        }
        // A 1 GiB pod with no -Xmx caps the heap at about 256 MiB: with its app's live set under 143 MiB, a
        // 50,000-class app's table is built (the 256 MiB bound before declined every such pod).
        assertNull(BuildTable.lowHeap(new BuildTable.Heap(256 * mb, 143 * mb, true), need));
    }

    @Test
    public void theHeapInUseIsWhatTheNewestCollectionLeftInTheHeapPools() {
        long mb = 1 << 20;
        java.util.Set<String> heap = new HashSet<>(Arrays.asList("Eden", "Old"));
        // The newest is neither the first nor the last; the older ones left more in use; Metaspace and the code
        // cache are not the heap.
        BuildTable.GcEnd older = gcEnd(100, "Eden", 0, "Old", 300 * mb, "Metaspace", 40 * mb);
        BuildTable.GcEnd newest = gcEnd(300, "Eden", 2 * mb, "Old", 100 * mb, "Metaspace", 50 * mb,
                "CodeHeap", 20 * mb);
        BuildTable.GcEnd middle = gcEnd(200, "Eden", 0, "Old", 250 * mb);
        assertEquals(102 * mb, BuildTable.Heap.usedAfterNewest(Arrays.asList(older, newest, middle), heap));
        assertEquals(102 * mb, BuildTable.Heap.usedAfterNewest(Arrays.asList(middle, older, newest), heap));
        assertEquals("no collection seen", -1,
                BuildTable.Heap.usedAfterNewest(Collections.<BuildTable.GcEnd>emptyList(), heap));
    }

    @Test
    public void theHeapInUseIsTheSmallerOfNowAndTheNewestCollection() {
        BuildTable.Heap h = BuildTable.Heap.of(512, 400, 300);
        assertEquals(512, h.max);
        assertEquals("garbage made since the collection does not count", 300, h.used);
        assertTrue(h.afterGc);
        h = BuildTable.Heap.of(512, 400, 400);
        assertEquals(400, h.used);
        assertTrue(h.afterGc);
        h = BuildTable.Heap.of(512, 400, 500);
        assertEquals("less in use now than the collection left: now is newer", 400, h.used);
        assertFalse("the figure is the heap in use now", h.afterGc);
        h = BuildTable.Heap.of(512, 400, -1);
        assertEquals("no collection seen", 400, h.used);
        assertFalse(h.afterGc);
    }

    @Test
    public void thisJvmsCollectionsAreRead() {
        // This JVM reports its collections; the check itself never runs one.
        System.gc();
        long after = BuildTable.LastCollection.heapUsedAfter();
        assertTrue("a collection ran: " + after, after > 0);
        BuildTable.Heap h = BuildTable.Heap.read();
        assertEquals(Runtime.getRuntime().maxMemory(), h.max);
        assertTrue(h.used + " of " + h.max, h.used > 0 && h.used <= h.max);
    }

    @Test
    public void theProbeMapsTempFileIsNeverOpenedThroughALink() throws IOException {
        // Another user who swaps the just-made temp file for a link to a file the app can write gets nothing
        // written through it.
        Path target = tmp.newFile("target").toPath();
        Files.write(target, "keep".getBytes(StandardCharsets.UTF_8));
        Path link = tmp.getRoot().toPath().resolve("keploy-probe-map-1.ndjson");
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.Assume.assumeNoException(e);
        }
        try {
            BuildTable.Spill.openOwned(link).close();
            fail("opened through a link");
        } catch (IOException expected) {
            // ELOOP
        }
        assertEquals("keep", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertTrue("the target is not deleted", Files.exists(target));
    }

    private static BuildTable.GcEnd gcEnd(long endTime, Object... poolUsed) {
        Map<String, Long> used = new HashMap<>();
        for (int i = 0; i < poolUsed.length; i += 2) {
            used.put((String) poolUsed[i], ((Number) poolUsed[i + 1]).longValue());
        }
        return new BuildTable.GcEnd(endTime, used);
    }
}
