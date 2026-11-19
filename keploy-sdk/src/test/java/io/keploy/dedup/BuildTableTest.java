package io.keploy.dedup;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                    assertEquals(BuildTable.sha256Hex(c.ndjson.toByteArray()), h.get("sha256").getAsString());
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
    public void noClassesIsOneEmptyLastFrame() throws Exception {
        BuildTable.Summary s = BuildTable.write(new ArrayList<BuildTable.ClassFile>(), new Collect());
        assertEquals("", s.build);
        assertEquals(0, s.records);
        Frames f = new Frames();
        BuildTable.send(ArrayList::new, f);
        assertEquals(1, f.frames.size());
        assertEquals("{\"v\":1,\"part\":\"table\",\"build\":\"\",\"seq\":0,\"last\":true,\"records\":0,\"sha256\":"
                + "\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"}", header(f.frames.get(0)));
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
        assertEquals(BuildTable.classIdsSum(new long[] {CRC64.classId(foo), CRC64.classId(in)}),
                u.get("hash").getAsString());
        // Every indexed class is in the build id, as in the index record's, named or not.
        assertEquals("jv1-" + BuildTable.classIdsSum(new long[] {CRC64.classId(foo), CRC64.classId(in),
                CRC64.classId(junk)}).substring(0, 32), s.build);
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
                            s.getOutputStream().write("OK\n".getBytes(StandardCharsets.UTF_8));
                            s.getOutputStream().flush();
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
            while (last == null) {
                synchronized (r.got) {
                    for (byte[] b : r.got) {
                        String h = new String(b, StandardCharsets.UTF_8);
                        if (h.contains("\"last\":true")) {
                            last = h.substring(0, h.indexOf('\n'));
                        }
                    }
                }
                Thread.sleep(20);
            }
            String build = new String(resource("java-dedup.build"), StandardCharsets.UTF_8).trim();
            assertTrue(last, last.startsWith("{\"v\":1,\"part\":\"table\",\"build\":\"" + build + "\","));
            assertTrue(last, last.contains("\"records\":5,"));
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
}
