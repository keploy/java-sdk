package io.keploy.dedup;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ILine;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.internal.analysis.KeployBranchCoverage;
import org.jacoco.core.internal.data.CRC64;
import org.jacoco.core.runtime.IExecutionDataAccessorGenerator;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Rows for the probe map: for every class file of the java-dedup sample and of a class of code shapes (if and
 * &amp;&amp;, loops, an int, a String and an enum switch, try/finally, try-with-resources, synchronized, a lambda,
 * an assert), the leaves the map gives a set of probes are what JaCoCo's own public Analyzer counts for that
 * set, line by line: a line's code leaf is hit when JaCoCo covers an instruction of it, and as many of its
 * branch leaves as JaCoCo covers branches. The sets are no probe, each probe alone, every pair of probes and
 * all of them, so the branch leaves of a line keep their identity across probes (a union of two probes counts
 * two edges only when JaCoCo does). The Shapes source is shapes/Shapes.java.txt (javac --release 8 -g); the
 * Modern source (records, sealed types, pattern and enum switches, a String switch with yield) is
 * modern/Modern.java.txt (javac --release 21 -g).
 */
public class ProbeMapTest {

    private static final String RES = "/io/keploy/dedup/buildtable/";

    static List<BuildTable.ClassFile> load(String dir) throws Exception {
        URL root = ProbeMapTest.class.getResource(RES + dir);
        assertNotNull(dir, root);
        Path base = Paths.get(root.toURI());
        List<BuildTable.ClassFile> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".class.bin")).sorted().collect(Collectors.toList())) {
                String rel = base.relativize(p).toString().replace(File.separatorChar, '/');
                out.add(new BuildTable.ClassFile(rel.substring(0, rel.length() - ".class.bin".length()),
                        Files.readAllBytes(p)));
            }
        }
        return out;
    }

    /** probes writes the table and returns the probe map's NDJSON stream. */
    static String probes(List<BuildTable.ClassFile> files, BuildTableTest.Collect table) throws IOException {
        BuildTable.Summary s = BuildTable.write(files, table);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] r : s.probes) {
            b.write(r, 0, r.length);
            b.write('\n');
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void javaDedupProbeMapIsTheGolden() throws Exception {
        List<BuildTable.ClassFile> files = BuildTableTest.javaDedup();
        String got = probes(files, new BuildTableTest.Collect());
        String dir = System.getProperty("buildtable.dump");
        if (dir != null) {
            Files.write(Paths.get(dir, "java-dedup.probes.ndjson"), got.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(new String(BuildTableTest.resource("java-dedup.probes.ndjson"), StandardCharsets.UTF_8), got);
        // One record per class file, each with its class id and as many entries as JaCoCo has probes in it.
        String[] lines = got.split("\n");
        assertEquals(files.size(), lines.length);
        for (int i = 0; i < lines.length; i++) {
            JsonObject r = JsonParser.parseString(lines[i]).getAsJsonObject();
            assertEquals(files.get(i).name, r.get("class").getAsString());
            assertEquals(String.format("%016x", CRC64.classId(files.get(i).bytes)), r.get("id").getAsString());
            assertEquals(files.get(i).name, instrumentedProbes(files.get(i)), r.getAsJsonArray("probes").size());
        }
    }

    @Test
    public void aMethodTheCallerDeclinesIsNotReplayed() {
        // The map declines the methods the table has no line of (a class built without -g): their probes cover
        // no leaf, so their replays would be thrown away. The class pass still analyzes them.
        byte[] bytes = BuildTableBudgetTest.gen("gen/app/Declined", 0, 40);
        Map<String, KeployBranchCoverage.Method> shape = new HashMap<>();
        List<String> replayed = new ArrayList<>();
        int total = KeployBranchCoverage.analyze(bytes, shape, new KeployBranchCoverage.EachProbe() {
            @Override
            public boolean wants(KeployBranchCoverage.Method m) {
                return !m.name.equals("run");
            }

            @Override
            public void probe(KeployBranchCoverage.Method m, int r, KeployBranchCoverage.Method got) {
                replayed.add(m.name);
            }
        });
        assertTrue(shape.containsKey("run(I)I"));
        assertTrue("run has probes to replay", shape.get("run(I)I").probes.length > 40);
        assertEquals(total - shape.get("run(I)I").probes.length, replayed.size());
        assertFalse("a declined method is not replayed", replayed.contains("run"));
        assertTrue(replayed.contains("<init>"));
    }

    @Test(timeout = 300000)
    public void eachProbesReplayIsTheClassAnalyzedWithThatProbeExecuted() throws Exception {
        // The oracle is the class analyzed whole, once per round r, with the r-th probe of every method executed
        // (a method's coverage reads only its own probes). Against it, each probe's one-pass replay, on real
        // libraries' classes: JaCoCo core's and Gson's, the jars on this test's classpath.
        int classes = 0;
        int probes = 0;
        for (Class<?> c : new Class<?>[] {Analyzer.class, JsonParser.class}) {
            File jar = new File(c.getProtectionDomain().getCodeSource().getLocation().toURI());
            assertTrue(jar + " is a jar", jar.isFile());
            for (BuildTable.ClassFile f : jarClasses(jar)) {
                Map<String, KeployBranchCoverage.Method> shape = new HashMap<>();
                Map<String, List<KeployBranchCoverage.Method>> replayed = new HashMap<>();
                int total = KeployBranchCoverage.analyze(f.bytes, shape, (m, r, got) -> {
                    List<KeployBranchCoverage.Method> l = replayed.computeIfAbsent(m.name + m.desc,
                            k -> new ArrayList<>());
                    assertEquals(f.name + "#" + m.name + m.desc, r, l.size());
                    l.add(got);
                });
                if (total < 0) {
                    continue;
                }
                classes++;
                int rounds = 0;
                int replays = 0;
                for (Map.Entry<String, KeployBranchCoverage.Method> e : shape.entrySet()) {
                    int n = e.getValue().probes.length;
                    rounds = Math.max(rounds, n);
                    replays += n;
                    List<KeployBranchCoverage.Method> l = replayed.get(e.getKey());
                    assertEquals(f.name + "#" + e.getKey(), n, l == null ? 0 : l.size());
                }
                // A method whose code the filters leave out entirely (an enum's values()) keeps its probes: no leaf.
                assertTrue(f.name + ": " + replays + " probes replayed of " + total, replays <= total);
                for (int r = 0; r < rounds; r++) {
                    boolean[] set = new boolean[total];
                    for (KeployBranchCoverage.Method m : shape.values()) {
                        if (m.probes.length > r) {
                            set[m.probes[r]] = true;
                        }
                    }
                    Map<String, KeployBranchCoverage.Method> run = new HashMap<>();
                    KeployBranchCoverage.analyze(f.bytes, set, run);
                    for (Map.Entry<String, KeployBranchCoverage.Method> e : shape.entrySet()) {
                        if (e.getValue().probes.length <= r) {
                            continue;
                        }
                        KeployBranchCoverage.Method want = run.get(e.getKey());
                        KeployBranchCoverage.Method got = replayed.get(e.getKey()).get(r);
                        String at = f.name + "#" + e.getKey() + " probe " + r;
                        assertArrayEquals(at, want.lines, got.lines);
                        assertArrayEquals(at, want.covered, got.covered);
                        assertEquals(at, want.branches.length, got.branches.length);
                        for (int k = 0; k < want.branches.length; k++) {
                            assertArrayEquals(at, want.branches[k], got.branches[k]);
                        }
                        probes++;
                    }
                }
            }
        }
        assertTrue("classes: " + classes, classes > 300);
        assertTrue("probes: " + probes, probes > 5000);
    }

    static List<BuildTable.ClassFile> jarClasses(File jar) throws IOException {
        List<BuildTable.ClassFile> out = new ArrayList<>();
        try (ZipFile z = new ZipFile(jar)) {
            for (ZipEntry e : Collections.list(z.entries())) {
                String name = e.getName();
                if (!name.endsWith(".class") || name.startsWith("META-INF/")) {
                    continue;
                }
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                try (InputStream in = z.getInputStream(e)) {
                    byte[] buf = new byte[8192];
                    for (int k; (k = in.read(buf)) > 0; ) {
                        b.write(buf, 0, k);
                    }
                }
                out.add(new BuildTable.ClassFile(name.substring(0, name.length() - 6), b.toByteArray()));
            }
        }
        return out;
    }

    @Test
    public void javaDedupProbesCoverWhatJacocoCounts() throws Exception {
        agree(BuildTableTest.javaDedup());
    }

    @Test
    public void everyCodeShapeCoversWhatJacocoCounts() throws Exception {
        List<BuildTable.ClassFile> files = load("shapes");
        assertEquals(3, files.size());
        int edges = agree(files);
        assertTrue("the shapes have branch edges to tell apart: " + edges, edges >= 40);
    }

    @Test
    public void java21CodeShapesCoverWhatJacocoCounts() throws Exception {
        // Filters Java 8 code never meets: records, pattern switches (typeSwitch, MatchException), record
        // patterns, exhaustive switches. ASM reads the class version on every JDK the SDK runs on.
        List<BuildTable.ClassFile> files = load("modern");
        assertEquals(7, files.size());
        int edges = agree(files);
        assertTrue("the shapes have branch edges to tell apart: " + edges, edges >= 40);
    }

    /**
     * instrumentedProbes is the length of the probe array JaCoCo's instrumenter gives the class: the array the
     * agent fills, read without the analysis the map is built from.
     */
    static int instrumentedProbes(BuildTable.ClassFile f) throws IOException {
        int[] count = {0};
        new Instrumenter(new IExecutionDataAccessorGenerator() {
            @Override
            public int generateDataAccessor(long classid, String classname, int probecount, MethodVisitor mv) {
                count[0] = probecount;
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "[Z");
                return 1;
            }
        }).instrument(f.bytes, f.name);
        return count[0];
    }

    /**
     * agree checks every class file's map against JaCoCo for no probe, each probe, each pair and all probes,
     * and returns how many branch leaves the classes have.
     */
    static int agree(List<BuildTable.ClassFile> files) throws Exception {
        BuildTableTest.Collect table = new BuildTableTest.Collect();
        String[] maps = probes(files, table).split("\n");
        Map<String, JsonObject> units = new HashMap<>();
        for (String r : table.records) {
            JsonObject o = JsonParser.parseString(r).getAsJsonObject();
            assertEquals(r, "ok", o.get("state").getAsString());
            units.put(o.get("id").getAsString(), o);
        }
        Map<String, BuildTable.ClassFile> byName = new HashMap<>();
        for (BuildTable.ClassFile f : files) {
            byName.put(f.name, f);
        }
        assertEquals(files.size(), maps.length);
        int edges = 0;
        for (String line : maps) {
            JsonObject m = JsonParser.parseString(line).getAsJsonObject();
            BuildTable.ClassFile f = byName.get(m.get("class").getAsString());
            JsonObject unit = units.get(m.get("unit").getAsString());
            assertNotNull(line, f);
            assertNotNull(line, unit);
            List<int[]> probes = new ArrayList<>();
            for (JsonElement p : m.getAsJsonArray("probes")) {
                JsonArray a = p.getAsJsonArray();
                int[] r = new int[a.size()];
                for (int i = 0; i < r.length; i++) {
                    r[i] = a.get(i).getAsInt();
                }
                assertEquals("ranges come in pairs", 0, r.length % 2);
                for (int i = 0; i + 1 < r.length; i++) {
                    assertTrue(f.name + ": ranges ascend and never touch " + Arrays.toString(r), r[i] < r[i + 1]);
                }
                probes.add(r);
            }
            int n = probes.size();
            assertEquals(f.name, instrumentedProbes(f), n);
            check(f, unit, probes, new boolean[n]);
            boolean[] all = new boolean[n];
            Arrays.fill(all, true);
            check(f, unit, probes, all);
            for (int p = 0; p < n; p++) {
                for (int q = p; q < n; q++) {
                    boolean[] set = new boolean[n];
                    set[p] = true;
                    set[q] = true;
                    check(f, unit, probes, set);
                }
            }
            for (List<Integer> v : jacoco(f, new boolean[n]).values()) {
                edges += v.get(2);
            }
        }
        return edges;
    }

    static void check(BuildTable.ClassFile f, JsonObject unit, List<int[]> probes, boolean[] set) throws IOException {
        Map<String, List<Integer>> want = jacoco(f, set);
        Map<String, List<Integer>> got = mapped(f.name, unit, probes, set);
        List<Integer> on = new ArrayList<>();
        for (int i = 0; i < set.length; i++) {
            if (set[i]) {
                on.add(i);
            }
        }
        assertEquals(f.name + " probes " + on, want, got);
    }

    /** jacoco is what JaCoCo's Analyzer counts with these probes: per method and line, code hit, branches hit, branches. */
    static Map<String, List<Integer>> jacoco(BuildTable.ClassFile f, boolean[] probes) throws IOException {
        ExecutionDataStore store = new ExecutionDataStore();
        store.put(new ExecutionData(CRC64.classId(f.bytes), f.name, probes));
        List<IClassCoverage> got = new ArrayList<>();
        new Analyzer(store, got::add).analyzeClass(f.bytes, f.name);
        Map<String, List<Integer>> out = new TreeMap<>();
        for (IClassCoverage cc : got) {
            for (IMethodCoverage mc : cc.getMethods()) {
                for (int l = Math.max(1, mc.getFirstLine()); l <= mc.getLastLine(); l++) {
                    ILine line = mc.getLine(l);
                    if (line.getInstructionCounter().getTotalCount() == 0) {
                        continue;
                    }
                    out.put("m:" + f.name + "#" + mc.getName() + mc.getDesc() + "@" + l, Arrays.asList(
                            line.getInstructionCounter().getCoveredCount() > 0 ? 1 : 0,
                            line.getBranchCounter().getCoveredCount(), line.getBranchCounter().getTotalCount()));
                }
            }
        }
        return out;
    }

    /** mapped is the same count from the map: the union of the set's leaves, read back through the table. */
    static Map<String, List<Integer>> mapped(String name, JsonObject unit, List<int[]> probes, boolean[] set) {
        List<String> keys = new ArrayList<>();
        List<Integer> kinds = new ArrayList<>();
        Map<String, List<Integer>> out = new TreeMap<>();
        for (JsonElement me : unit.getAsJsonArray("methods")) {
            JsonObject m = me.getAsJsonObject();
            String id = m.get("id").getAsString();
            for (JsonElement le : m.getAsJsonArray("leaves")) {
                JsonArray leaf = le.getAsJsonArray();
                String key = id + "@" + leaf.get(0).getAsInt();
                keys.add(key);
                kinds.add(leaf.get(5).getAsInt());
                if (id.startsWith("m:" + name + "#")) {
                    List<Integer> v = out.get(key);
                    if (v == null) {
                        v = new ArrayList<>(Arrays.asList(0, 0, 0));
                        out.put(key, v);
                    }
                    if (leaf.get(5).getAsInt() == 1) {
                        v.set(2, v.get(2) + 1);
                    }
                }
            }
        }
        BitSet hit = new BitSet();
        for (int p = 0; p < set.length; p++) {
            if (set[p]) {
                int[] r = probes.get(p);
                for (int i = 0; i < r.length; i += 2) {
                    hit.set(r[i], r[i + 1]);
                }
            }
        }
        assertTrue(name + ": a leaf past the unit's", hit.length() <= keys.size());
        for (int i = hit.nextSetBit(0); i >= 0; i = hit.nextSetBit(i + 1)) {
            List<Integer> v = out.get(keys.get(i));
            assertNotNull(name + ": a probe of it covers another class's leaf " + keys.get(i), v);
            if (kinds.get(i) == 0) {
                v.set(0, 1);
            } else {
                v.set(1, v.get(1) + 1);
            }
        }
        return out;
    }

    @Test
    public void aUnitThatIsNotMeasuredHasNoProbeRecord() throws Exception {
        List<BuildTable.ClassFile> files = new ArrayList<>(BuildTableTest.javaDedup());
        files.add(new BuildTable.ClassFile("io/keploy/samples/javadedup/model/Item$Later",
                "not a class".getBytes(StandardCharsets.UTF_8)));
        String got = probes(files, new BuildTableTest.Collect());
        assertFalse(got, got.contains("\"unit\":\"c:io/keploy/samples/javadedup/model/Item\""));
        assertEquals(4, got.split("\n").length);
    }

    /** copy is a class d/C with one method per line given (m0 at the first, ...), and an int field when field. */
    static byte[] copy(boolean field, int... lines) {
        int[][] methods = new int[lines.length][];
        for (int i = 0; i < lines.length; i++) {
            methods[i] = new int[] {lines[i]};
        }
        return cls(field, false, methods);
    }

    /**
     * cls is a class d/C with a method mi per entry of methods, one instruction on each of its lines in order
     * (NOPs, then a RETURN), an int field when field, and the methods written last to first when reversed.
     */
    static byte[] cls(boolean field, boolean reversed, int[]... methods) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "d/C", null, "java/lang/Object", null);
        cw.visitSource("C.java", null);
        if (field) {
            cw.visitField(Opcodes.ACC_STATIC, "x", "I", null, null).visitEnd();
        }
        for (int j = 0; j < methods.length; j++) {
            int i = reversed ? methods.length - 1 - j : j;
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "m" + i, "()V", null, null);
            mv.visitCode();
            for (int k = 0; k < methods[i].length; k++) {
                Label l = new Label();
                mv.visitLabel(l);
                mv.visitLineNumber(methods[i][k], l);
                mv.visitInsn(k + 1 < methods[i].length ? Opcodes.NOP : Opcodes.RETURN);
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    public void twoCopiesOfAClassThatDifferAreNotMeasured() throws Exception {
        // The same class under two roots, as the index can find it. The table's methods come from one copy,
        // so the other's probes cannot be mapped onto them: the unit fails closed in the table and the map.
        byte[][][] cases = {
            {copy(false, 10), copy(false, 20)}, // a line moved
            {copy(false, 10, 11), copy(false, 10)}, // a method the other copy lacks
            {copy(false, 10), copy(false, 10, 11)},
            // The same methods and lines, but their probes in another order: a hit of probe 0 of one copy is
            // not a hit of probe 0 of the other, and a test's hits name only the class.
            {cls(false, false, new int[] {10}, new int[] {12}), cls(false, true, new int[] {10}, new int[] {12})},
        };
        for (byte[][] c : cases) {
            List<BuildTable.ClassFile> files = new ArrayList<>();
            files.add(new BuildTable.ClassFile("d/C", c[0]));
            files.add(new BuildTable.ClassFile("d/C", c[1]));
            BuildTableTest.Collect table = new BuildTableTest.Collect();
            BuildTable.Summary s = BuildTable.write(files, table);
            assertEquals(1, table.records.size());
            String rec = table.records.get(0);
            assertTrue(rec, rec.contains("\"id\":\"c:d/C\""));
            assertTrue(rec, rec.contains("\"state\":\"not_measured\",\"reason\":{\"code\":\"analyzer_failed\"}"));
            assertEquals(1, s.notMeasured);
            assertEquals("counted as a probe map failure", 1, s.notMapped);
            assertTrue("no probe record for a unit that is not measured", s.probes.isEmpty());
        }
    }

    @Test
    public void twoCopiesOfAClassThatMapAlikeShareOneRecord() throws Exception {
        // Two class files of one name whose bytes differ but whose probes map alike: a test's hits name the
        // class only, and a hit of either copy covers the same leaves, so the map has one record per name
        // (the Go decoder refuses a name listed twice). Its id is the first copy the index listed.
        byte[] a = copy(false, 10, 12);
        byte[] b = copy(true, 10, 12);
        assertNotEquals(CRC64.classId(a), CRC64.classId(b));
        for (byte[][] c : new byte[][][] {{a, b}, {b, a}}) {
            List<BuildTable.ClassFile> files = new ArrayList<>();
            files.add(new BuildTable.ClassFile("d/C", c[0]));
            files.add(new BuildTable.ClassFile("d/C", c[1]));
            BuildTableTest.Collect table = new BuildTableTest.Collect();
            String[] got = probes(files, table).split("\n");
            assertTrue(table.records.get(0), table.records.get(0).contains("\"state\":\"ok\""));
            assertEquals(1, got.length);
            JsonObject r = JsonParser.parseString(got[0]).getAsJsonObject();
            assertEquals("d/C", r.get("class").getAsString());
            assertEquals(String.format("%016x", CRC64.classId(c[0])), r.get("id").getAsString());
            assertEquals("[[0,1],[1,2]]", r.getAsJsonArray("probes").toString());
        }
    }

    @Test
    public void theSameClassFileTwiceIsOneClassFile() throws Exception {
        // The index can find one class file under two paths: the table, its build id and the map are those of
        // one copy.
        byte[] a = copy(false, 10, 12);
        BuildTableTest.Collect once = new BuildTableTest.Collect();
        String onceMap = probes(Collections.singletonList(new BuildTable.ClassFile("d/C", a)), once);
        BuildTableTest.Collect twice = new BuildTableTest.Collect();
        String twiceMap = probes(Arrays.asList(new BuildTable.ClassFile("d/C", a),
                new BuildTable.ClassFile("x/d/C", a)), twice);
        assertEquals(once.build, twice.build);
        assertEquals(once.records, twice.records);
        assertEquals(onceMap, twiceMap);
        assertEquals(1, twiceMap.split("\n").length);
    }

    @Test
    public void aLineBelowOneHasNoLeafAndItsMethodStays() throws Exception {
        // javac never writes line 0, but bytecode tools do. The table leaves such a line out and keeps the
        // method's other lines: the map must agree, not fail the class as a method missing from the table.
        int[][][] cases = {
            {{0, 5}, {7}},
            {{5, 0}, {7}},
            {{0}, {7}}, // a method with no other line has no leaf: left out of both
        };
        for (int[][] c : cases) {
            List<BuildTable.ClassFile> files = Collections.singletonList(new BuildTable.ClassFile("d/C",
                    cls(false, false, c)));
            BuildTableTest.Collect table = new BuildTableTest.Collect();
            BuildTable.Summary s = BuildTable.write(files, table);
            assertEquals(0, s.notMeasured);
            assertEquals(0, s.notMapped);
            JsonObject unit = JsonParser.parseString(table.records.get(0)).getAsJsonObject();
            JsonArray methods = unit.getAsJsonArray("methods");
            assertEquals(Arrays.deepToString(c), c[0].length == 1 ? 1 : 2, methods.size());
            if (c[0].length > 1) {
                JsonObject m0 = methods.get(0).getAsJsonObject();
                assertEquals("m:d/C#m0()V", m0.get("id").getAsString());
                assertEquals(5, m0.get("line").getAsInt());
                assertEquals(1, m0.getAsJsonArray("leaves").size());
            }
            agree(files);
        }
    }

    @Test
    public void theProbeMapFollowsTheTableAsItsOwnStream() throws Exception {
        BuildTableTest.Collect table = new BuildTableTest.Collect();
        String golden = probes(BuildTableTest.javaDedup(), table);
        {
            BuildTableTest.Frames sent = new BuildTableTest.Frames();
            List<BuildTable.ClassFile> files = BuildTableTest.javaDedup();
            BuildTable.send(() -> files, frame -> {
                assertTrue(frame.length <= BuildTable.MAX_FRAME_BYTES);
                sent.send(frame);
            });
            int tables = 0;
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            int seq = 0;
            for (byte[] f : sent.frames) {
                String h = BuildTableTest.header(f);
                JsonObject o = JsonParser.parseString(h).getAsJsonObject();
                if (o.get("part").getAsString().equals("table")) {
                    assertEquals("the table comes first", 0, stream.size());
                    tables++;
                    continue;
                }
                assertEquals("probes", o.get("part").getAsString());
                assertEquals(table.build, o.get("build").getAsString());
                assertEquals(seq++, o.get("seq").getAsInt());
                stream.write(f, 8 + h.length() + 1, f.length - 8 - h.length() - 1);
                if (o.get("last").getAsBoolean()) {
                    assertEquals(5, o.get("records").getAsInt());
                    assertEquals(BuildTableTest.sha256Hex(golden.getBytes(StandardCharsets.UTF_8)),
                            o.get("sha256").getAsString());
                    assertEquals("the last frame ends the answer", f, sent.frames.get(sent.frames.size() - 1));
                }
            }
            assertEquals(1, tables);
            assertTrue(seq >= 1);
            assertArrayEquals(golden.getBytes(StandardCharsets.UTF_8), stream.toByteArray());
        }
    }
}
