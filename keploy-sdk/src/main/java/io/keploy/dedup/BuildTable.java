package io.keploy.dedup;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICoverageVisitor;
import org.jacoco.core.analysis.ILine;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.analysis.ISourceNode;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.internal.data.CRC64;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * BuildTable is the build table of the code-coverage ingest contract (schema 1, section 3.3) for the app's
 * classes: one record per top-level class (its nested, inner, anonymous, lambda and Scala module classes
 * folded in by name), methods with JaCoCo's per-method lines and branch edges, in the contract's order and
 * written byte for byte as Keploy's Go encoder writes them (enterprise pkg/service/codecoverage).
 *
 * <p>Keploy asks for it with the control-socket command {@code TABLE}, which it sends once its first START
 * is answered ACK. The agent answers ACK at once and never works on the table on the control path: JaCoCo's
 * analyzer runs over the indexed class bytes on a daemon thread of its own, which streams the records to
 * Keploy as TABLE frames on the data socket. An older SDK answers the command IGNORED, and an older Keploy
 * never sends it, so it never gets a frame. A TABLE while a send runs has that send run once more after it.
 *
 * <p>A TABLE frame is one connection to the data socket: the 4 bytes {@code KTB1}, a 4-byte big-endian
 * length n, then n bytes (n plus 8 is at most {@link #MAX_FRAME_BYTES}): a header JSON line, then the next
 * bytes of the table's NDJSON stream (a record may run on into the next frame). The header is
 * {@code {"v":1,"part":"table","build":"jv1-...","seq":k,"last":false}}; the last frame also carries
 * {@code "records"} (the record count) and {@code "sha256"} (hex, over the whole NDJSON stream). With no app
 * class the stream is one frame: build "", last, 0 records. After each frame the SDK shuts its side's output
 * and waits for {@code OK} and a newline; a receiver that closes without it ends the stream, and the table
 * goes again only when Keploy sends TABLE again.
 */
final class BuildTable {

    private static final Logger LOGGER = Logger.getLogger(BuildTable.class.getName());

    /** The control-socket command Keploy sends for the build table. */
    static final String COMMAND = "TABLE";
    /** A TABLE frame's size cap, magic and length included. */
    static final int MAX_FRAME_BYTES = 1 << 20;
    /** A record (with its newline) larger than a whole inflated chunk can never be sent: record_too_large. */
    static final int MAX_RECORD_BYTES = 8 << 20;
    /** The first 4 bytes of a TABLE frame. */
    static final String MAGIC = "KTB1";

    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int REPLY_TIMEOUT_MILLIS = 30_000;
    private static final int CONNECT_ATTEMPTS = 3;
    // Room left in a frame for the magic, the length and the header line.
    private static final int HEADER_ROOM = 1024;

    // The JVM's table sends, one at a time.
    private static final Sends JVM = new Sends(BuildTable::thread);

    private BuildTable() {
    }

    /** One class file the index read: its VM name (org/acme/Foo$Bar) and its bytes. */
    static final class ClassFile {
        final String name;
        final byte[] bytes;

        ClassFile(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }

    /** files maps index entries to class files, so the agent's command hook stays one call. */
    static <T> List<ClassFile> files(List<T> entries, Function<T, String> name, Function<T, byte[]> bytes) {
        List<ClassFile> out = new ArrayList<>(entries.size());
        for (T e : entries) {
            out.add(new ClassFile(name.apply(e), bytes.apply(e)));
        }
        return out;
    }

    /**
     * request answers Keploy's TABLE command: true (ACK) once the table's send is started on its own thread,
     * or, when a send runs already, once that send is set to run once more after it. It never blocks.
     * When no thread can start it answers false (IGNORED) and does not throw; the agent's command hook catches
     * anything else. The classes are read on that thread.
     */
    static boolean request(Supplier<List<ClassFile>> classes, File dataSocket) {
        return JVM.request(() -> send(classes, new SocketSender(dataSocket)));
    }

    /** requests is how many times the JVM's table was asked for, answered or not: only TABLE asks. */
    static int requests() {
        return JVM.requests();
    }

    static Thread thread(Runnable r) {
        Thread t = new Thread(r, "keploy-build-table");
        t.setDaemon(true);
        // A hint only: HotSpot on Linux runs every Java thread at one OS priority unless told otherwise.
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    }

    /**
     * Sends runs one send at a time; a request while one runs has it run once more after it. It assumes one
     * caller at a time (the control server handles one command at a time): a reset below clears {@code again},
     * which would drop a request that raced in from a second caller.
     */
    static final class Sends {
        private final ThreadFactory threads;
        private boolean running;
        private boolean again;
        private int requests;

        Sends(ThreadFactory threads) {
            this.threads = threads;
        }

        synchronized int requests() {
            return requests;
        }

        boolean request(Runnable send) {
            synchronized (this) {
                requests++;
                if (running) {
                    again = true;
                    return true;
                }
                running = true;
            }
            try {
                threads.newThread(() -> loop(send)).start();
                return true;
            } catch (Throwable t) {
                // Called on the control path: a thread that cannot start (a pids limit, a security manager)
                // must not end the control server. Keploy reads IGNORED as no table from this SDK.
                synchronized (this) {
                    running = false;
                    again = false;
                }
                LOGGER.log(Level.WARNING, "Keploy coverage: the build table's thread did not start", t);
                return false;
            }
        }

        private void loop(Runnable send) {
            try {
                while (true) {
                    send.run();
                    synchronized (this) {
                        if (!again) {
                            running = false;
                            return;
                        }
                        again = false;
                    }
                }
            } catch (Throwable t) {
                synchronized (this) {
                    running = false;
                    again = false;
                }
                LOGGER.log(Level.WARNING, "Keploy coverage: the build table was not sent", t);
            }
        }
    }

    /**
     * send analyzes the classes and streams the table through sender. It logs what it sent, or why it stopped,
     * and never throws.
     */
    static void send(Supplier<List<ClassFile>> classes, FrameSender sender) {
        try {
            long cpu0 = threadCpuNanos();
            long t0 = System.nanoTime();
            FrameWriter frames = new FrameWriter(sender);
            Summary s = write(classes.get(), frames);
            frames.finish(s.build, s.records);
            StringBuilder m = new StringBuilder("Keploy coverage: build table ");
            m.append(s.build.isEmpty() ? "(no app classes)" : s.build).append(" sent (").append(s.records)
                    .append(" classes, ").append(s.notMeasured).append(" not measured");
            if (s.noLines > 0) {
                m.append(", ").append(s.noLines)
                        .append(" class files without line numbers, so without lines: compile them with -g");
            }
            if (s.unnamed > 0) {
                m.append(", ").append(s.unnamed).append(" index entries without a class name left out");
            }
            m.append("; ").append(frames.seq).append(" frames, ").append((System.nanoTime() - t0) / 1_000_000)
                    .append(" ms, cpu ").append((threadCpuNanos() - cpu0) / 1_000_000).append(" ms)");
            LOGGER.log(Level.INFO, m.toString());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Keploy coverage: the build table was not sent: " + e.getMessage()
                    + "; it goes again when Keploy asks for it again");
        } catch (Throwable t) {
            // Never let the table take the app down: Keploy names the coverage it did not get.
            LOGGER.log(Level.WARNING, "Keploy coverage: the build table was not sent", t);
        }
    }

    private static long threadCpuNanos() {
        try {
            return java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- the table

    /**
     * What write wrote: the build id, the record count, how many records are not measured, how many class
     * files have code but no line numbers, and how many index entries were left out as no class has a name.
     */
    static final class Summary {
        final String build;
        final int records;
        final int notMeasured;
        final int noLines;
        final int unnamed;

        Summary(String build, int records, int notMeasured, int noLines, int unnamed) {
            this.build = build;
            this.records = records;
            this.notMeasured = notMeasured;
            this.noLines = noLines;
            this.unnamed = unnamed;
        }
    }

    /** Where write puts each record (UTF-8 JSON, no newline), in the table's order. */
    interface RecordSink {
        void record(String build, byte[] json) throws IOException;
    }

    /**
     * write analyzes the classes and hands each table record to sink, in the contract's order. With no class
     * bytes the build id is "" and there is no record.
     */
    static Summary write(List<ClassFile> classes, RecordSink sink) throws IOException {
        return write(classes, sink, MAX_RECORD_BYTES);
    }

    static Summary write(List<ClassFile> classes, RecordSink sink, int maxRecord) throws IOException {
        Map<String, Unit> byId = new HashMap<>();
        long[] ids = new long[classes.size()];
        int n = 0;
        int unnamed = 0;
        for (ClassFile in : classes) {
            if (in == null || in.bytes == null) {
                continue;
            }
            // Every indexed class is in the build id, as in the index record's.
            long id = CRC64.classId(in.bytes);
            ids[n++] = id;
            ClassFile f = new ClassFile(vmName(in), in.bytes);
            Unit u = f.name == null ? null : Unit.of(f.name);
            if (u == null || u.name.isEmpty()) {
                // No unit has an empty name (Go refuses the whole table for one): a file named "a/.class".
                unnamed++;
                continue;
            }
            Unit had = byId.get(u.id);
            if (had == null) {
                byId.put(u.id, u);
                had = u;
            }
            had.files.add(new UnitFile(f, id));
        }
        if (n == 0) {
            return new Summary("", 0, 0, 0, unnamed);
        }
        String build = "jv1-" + classIdsSum(Arrays.copyOf(ids, n)).substring(0, 32);
        List<Unit> units = new ArrayList<>(byId.values());
        Collections.sort(units, UNIT_ORDER);
        int notMeasured = 0;
        int noLines = 0;
        for (Unit u : units) {
            byte[] rec = u.record();
            if (rec.length + 1 > maxRecord) {
                rec = u.notMeasured("record_too_large");
                u.failed = true;
            }
            if (u.failed) {
                notMeasured++;
            } else {
                noLines += u.noLines;
            }
            sink.record(build, rec);
        }
        return new Summary(build, units.size(), notMeasured, noLines, unnamed);
    }

    /**
     * vmName is the class's own name, from its bytes: the name JaCoCo's runtime gives its probes, wherever
     * the index found the file. When the bytes are not a class ASM reads, it is the index's name.
     */
    static String vmName(ClassFile f) {
        try {
            String name = new ClassReader(f.bytes).getClassName();
            return name != null ? name : f.name;
        } catch (RuntimeException e) {
            return f.name;
        }
    }

    /** classIdsSum is sha256, as hex, over the sorted, de-duplicated class ids, each %016x, joined by \n. */
    static String classIdsSum(long[] ids) {
        long[] sorted = ids.clone();
        // Go sorts them as uint64.
        Long[] boxed = new Long[sorted.length];
        for (int i = 0; i < sorted.length; i++) {
            boxed[i] = sorted[i];
        }
        Arrays.sort(boxed, Long::compareUnsigned);
        StringBuilder sb = new StringBuilder(boxed.length * 17);
        Long prev = null;
        for (Long id : boxed) {
            if (prev != null && prev.longValue() == id.longValue()) {
                continue;
            }
            if (prev != null) {
                sb.append('\n');
            }
            String hex = Long.toHexString(id);
            for (int i = hex.length(); i < 16; i++) {
                sb.append('0');
            }
            sb.append(hex);
            prev = id;
        }
        return sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(byte[] b) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return hex(md.digest(b));
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        }
        return sb.toString();
    }

    /** Units by package path, segment by segment ('/' sorts first), then by id: Go's comparePkg order. */
    static final Comparator<Unit> UNIT_ORDER = (a, b) -> {
        int c = comparePkg(a.pkg, b.pkg);
        return c != 0 ? c : compareCodePoints(a.id, b.id);
    };

    /** comparePkg is codecoverage.comparePkg over code points (the order of their UTF-8 bytes). */
    static int comparePkg(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                if (ca == '/') {
                    return -1;
                }
                if (cb == '/') {
                    return 1;
                }
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /** compareCodePoints orders strings as Go's strings.Compare orders their UTF-8 bytes. */
    static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static final class UnitFile {
        final ClassFile file;
        final long classId;

        UnitFile(ClassFile file, long classId) {
            this.file = file;
            this.classId = classId;
        }
    }

    /** A table record: a top-level class and the class files folded into it by name. */
    static final class Unit {
        final String pkg;
        final String name;
        final String id;
        final List<UnitFile> files = new ArrayList<>(1);
        boolean failed;
        // Class files with code but no line numbers: their methods have no leaf, so they are left out.
        int noLines;

        private Unit(String pkg, String name) {
            this.pkg = pkg;
            this.name = name;
            this.id = "c:" + (pkg.isEmpty() ? name : pkg + "/" + name);
        }

        /** of finds a class file's unit by name: its simple name cut at the first $ after its first character. */
        static Unit of(String vmName) {
            int slash = vmName.lastIndexOf('/');
            String pkg = slash < 0 ? "" : vmName.substring(0, slash);
            String simple = vmName.substring(slash + 1);
            int cut = simple.indexOf('$', 1);
            return new Unit(pkg, cut < 0 ? simple : simple.substring(0, cut));
        }

        String path() {
            return pkg.isEmpty() ? name : pkg + "/" + name;
        }

        String hash() {
            long[] ids = new long[files.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = files.get(i).classId;
            }
            return classIdsSum(ids);
        }

        byte[] notMeasured(String code) {
            StringBuilder sb = head(sourcePath(null));
            sb.append(",\"state\":\"not_measured\",\"reason\":{\"code\":");
            Json.string(sb, code);
            sb.append("},\"methods\":[]}");
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }

        private StringBuilder head(String path) {
            StringBuilder sb = new StringBuilder(256);
            sb.append("{\"kind\":\"class\",\"id\":");
            Json.string(sb, id);
            sb.append(",\"pkg\":");
            Json.string(sb, pkg);
            sb.append(",\"name\":");
            Json.string(sb, name);
            sb.append(",\"path\":");
            Json.string(sb, path);
            sb.append(",\"hash\":");
            Json.string(sb, hash());
            return sb;
        }

        /**
         * sourcePath is the build path of the unit's source: the package path and the SourceFile of its
         * first class file (by name, so the top-level class when it is there) whose SourceFile is a bare,
         * non-empty file name; "" when none has one.
         */
        private String sourcePath(Map<String, ClassInfo> infos) {
            List<UnitFile> sorted = sortedFiles();
            for (UnitFile f : sorted) {
                ClassInfo info = infos != null ? infos.get(f.file.name) : ClassInfo.read(f.file.bytes);
                String sf = info == null ? null : info.sourceFile;
                if (sf != null && !sf.isEmpty() && sf.indexOf('/') < 0) {
                    return pkg.isEmpty() ? sf : pkg + "/" + sf;
                }
            }
            return "";
        }

        private List<UnitFile> sortedFiles() {
            List<UnitFile> sorted = new ArrayList<>(files);
            Collections.sort(sorted, (a, b) -> compareCodePoints(a.file.name, b.file.name));
            return sorted;
        }

        /** record analyzes the unit's class files and writes its record; any class JaCoCo refuses fails it. */
        byte[] record() {
            Map<String, ClassInfo> infos = new HashMap<>();
            List<Method> methods = new ArrayList<>();
            Set<String> methodIds = new HashSet<>();
            for (UnitFile f : sortedFiles()) {
                ClassInfo info = ClassInfo.read(f.file.bytes);
                infos.put(f.file.name, info);
                List<IClassCoverage> got;
                try {
                    got = analyze(f.file);
                } catch (Exception | LinkageError e) {
                    LOGGER.log(Level.FINE, "Keploy coverage: JaCoCo could not analyze " + f.file.name, e);
                    failed = true;
                    return notMeasured("analyzer_failed");
                }
                for (IClassCoverage cc : got) {
                    if (cc.getFirstLine() == ISourceNode.UNKNOWN_LINE
                            && cc.getInstructionCounter().getTotalCount() > 0) {
                        noLines++;
                    }
                    String nested = f.file.name.substring(path().length());
                    for (IMethodCoverage mc : cc.getMethods()) {
                        Method m = Method.of(f.file.name, nested, name, mc, info);
                        if (m != null && methodIds.add(m.id)) {
                            methods.add(m);
                        }
                    }
                }
            }
            Collections.sort(methods, (a, b) -> {
                int c = Integer.compare(a.line, b.line);
                return c != 0 ? c : compareCodePoints(a.id, b.id);
            });
            StringBuilder sb = head(sourcePath(infos));
            sb.append(",\"state\":\"ok\",\"methods\":[");
            for (int i = 0; i < methods.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                methods.get(i).json(sb);
            }
            sb.append("]}");
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    /** analyze runs JaCoCo's analyzer over one class file with no execution data. */
    static List<IClassCoverage> analyze(ClassFile f) throws IOException {
        final List<IClassCoverage> out = new ArrayList<>(1);
        Analyzer analyzer = new Analyzer(new ExecutionDataStore(), new ICoverageVisitor() {
            @Override
            public void visitCoverage(IClassCoverage coverage) {
                out.add(coverage);
            }
        });
        analyzer.analyzeClass(f.bytes, f.name);
        return out;
    }

    /** What a cheap ASM pass (no code) gives: the SourceFile and which methods take varargs. */
    static final class ClassInfo {
        String sourceFile;
        final Set<String> varargs = new HashSet<>();

        static ClassInfo read(byte[] bytes) {
            final ClassInfo info = new ClassInfo();
            try {
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitSource(String source, String debug) {
                        info.sourceFile = source;
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                        if ((access & Opcodes.ACC_VARARGS) != 0) {
                            info.varargs.add(name + desc);
                        }
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
            } catch (RuntimeException e) {
                return null;
            }
            return info;
        }
    }

    /** A method of a unit: its id, display name, first line and leaves in the contract's order. */
    static final class Method {
        final String id;
        final String display;
        final int line;
        final int[] leaves; // flat: line, count, kind per leaf

        private Method(String id, String display, int line, int[] leaves) {
            this.id = id;
            this.display = display;
            this.line = line;
            this.leaves = leaves;
        }

        /**
         * of makes the method's record from JaCoCo's per-method lines: one code leaf per line with
         * instructions (n = their count), then one branch leaf per branch edge on that line. A method with no
         * line (a class built without line numbers) has no leaf to show and is left out, as the contract's
         * method count leaves out a method with no code leaf.
         */
        static Method of(String vmClass, String nested, String unitName, IMethodCoverage mc, ClassInfo info) {
            int first = mc.getFirstLine();
            int last = mc.getLastLine();
            if (first < 1 || last < first) {
                return null;
            }
            int[] buf = new int[16];
            int k = 0;
            for (int l = first; l <= last; l++) {
                ILine line = mc.getLine(l);
                int ic = line.getInstructionCounter().getTotalCount();
                if (ic == 0) {
                    continue;
                }
                int bc = line.getBranchCounter().getTotalCount();
                int need = k + 3 * (1 + bc);
                if (need > buf.length) {
                    buf = Arrays.copyOf(buf, Math.max(need, buf.length * 2));
                }
                buf[k++] = l;
                buf[k++] = ic;
                buf[k++] = 0;
                for (int b = 0; b < bc; b++) {
                    buf[k++] = l;
                    buf[k++] = 1;
                    buf[k++] = 1;
                }
            }
            boolean varargs = info != null && info.varargs.contains(mc.getName() + mc.getDesc());
            return new Method("m:" + vmClass + "#" + mc.getName() + mc.getDesc(),
                    Names.method(unitName, nested, mc.getName(), mc.getDesc(), varargs), first,
                    Arrays.copyOf(buf, k));
        }

        void json(StringBuilder sb) {
            sb.append("{\"id\":");
            Json.string(sb, id);
            sb.append(",\"name\":");
            Json.string(sb, display);
            sb.append(",\"line\":").append(line).append(",\"leaves\":[");
            for (int i = 0; i < leaves.length; i += 3) {
                if (i > 0) {
                    sb.append(',');
                }
                int l = leaves[i];
                sb.append('[').append(l).append(",0,").append(l).append(",0,").append(leaves[i + 1]).append(',')
                        .append(leaves[i + 2]).append(']');
            }
            sb.append("]}");
        }
    }

    /** Display names of methods (API-CONTRACT 2.3). */
    static final class Names {
        private Names() {
        }

        /**
         * method names a method for people: "bar(int)", a nested class's "Inner.bar(int)", a constructor as
         * its class's nested name ("Foo(String)", "Inner(int)", "Inner.Deep(long)"), the static initializer as
         * "static {}", a lambda lambda$bar$0 as "bar lambda #0"; a varargs method's last parameter as
         * "Object...".
         */
        static String method(String unitName, String nested, String name, String desc, boolean varargs) {
            List<String> parts = new ArrayList<>();
            for (String p : nested.split("\\$")) {
                if (!p.isEmpty()) {
                    parts.add(p);
                }
            }
            String prefix = parts.isEmpty() ? "" : join(parts, ".") + ".";
            if (name.equals("<init>")) {
                String cls = parts.isEmpty() ? unitName : join(parts, ".");
                return cls + "(" + params(desc, varargs) + ")";
            }
            if (name.equals("<clinit>")) {
                return prefix + "static {}";
            }
            if (name.startsWith("lambda$")) {
                int d = name.lastIndexOf('$');
                String num = name.substring(d + 1);
                if (d > "lambda$".length() && !num.isEmpty() && isDigits(num)) {
                    return prefix + name.substring("lambda$".length(), d) + " lambda #" + num;
                }
            }
            return prefix + name + "(" + params(desc, varargs) + ")";
        }

        private static boolean isDigits(String s) {
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                    return false;
                }
            }
            return true;
        }

        private static String join(List<String> parts, String sep) {
            StringBuilder sb = new StringBuilder();
            for (String p : parts) {
                if (sb.length() > 0) {
                    sb.append(sep);
                }
                sb.append(p);
            }
            return sb.toString();
        }

        /** params lists the descriptor's parameter types by simple name, ", "-joined. */
        static String params(String desc, boolean varargs) {
            List<String> out = new ArrayList<>();
            int i = desc.indexOf('(') + 1;
            int end = desc.indexOf(')');
            if (i <= 0 || end < i) {
                return "";
            }
            while (i < end) {
                int dims = 0;
                while (i < end && desc.charAt(i) == '[') {
                    dims++;
                    i++;
                }
                if (i >= end) {
                    break;
                }
                String t;
                char c = desc.charAt(i);
                if (c == 'L') {
                    int semi = desc.indexOf(';', i);
                    if (semi < 0 || semi > end) {
                        break;
                    }
                    String cls = desc.substring(i + 1, semi);
                    t = cls.substring(cls.lastIndexOf('/') + 1).replace('$', '.');
                    i = semi + 1;
                } else {
                    t = primitive(c);
                    i++;
                }
                StringBuilder sb = new StringBuilder(t);
                for (int d = 0; d < dims; d++) {
                    sb.append("[]");
                }
                out.add(sb.toString());
            }
            if (varargs && !out.isEmpty()) {
                String lastParam = out.get(out.size() - 1);
                if (lastParam.endsWith("[]")) {
                    out.set(out.size() - 1, lastParam.substring(0, lastParam.length() - 2) + "...");
                }
            }
            return join(out, ", ");
        }

        private static String primitive(char c) {
            switch (c) {
                case 'Z':
                    return "boolean";
                case 'B':
                    return "byte";
                case 'C':
                    return "char";
                case 'S':
                    return "short";
                case 'I':
                    return "int";
                case 'J':
                    return "long";
                case 'F':
                    return "float";
                case 'D':
                    return "double";
                case 'V':
                    return "void";
                default:
                    return String.valueOf(c);
            }
        }
    }

    /** JSON strings as Go's encoding/json writes them with HTML escaping off. */
    static final class Json {
        private Json() {
        }

        private static final char[] HEX = "0123456789abcdef".toCharArray();

        static void string(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"':
                        sb.append("\\\"");
                        continue;
                    case '\\':
                        sb.append("\\\\");
                        continue;
                    case '\b':
                        sb.append("\\b");
                        continue;
                    case '\f':
                        sb.append("\\f");
                        continue;
                    case '\n':
                        sb.append("\\n");
                        continue;
                    case '\r':
                        sb.append("\\r");
                        continue;
                    case '\t':
                        sb.append("\\t");
                        continue;
                    case '\u2028':
                        sb.append("\\u2028");
                        continue;
                    case '\u2029':
                        sb.append("\\u2029");
                        continue;
                    default:
                        break;
                }
                if (c < 0x20) {
                    sb.append("\\u00").append(HEX[c >> 4]).append(HEX[c & 0xf]);
                } else if (Character.isHighSurrogate(c) && i + 1 < s.length()
                        && Character.isLowSurrogate(s.charAt(i + 1))) {
                    sb.append(c).append(s.charAt(++i));
                } else if (Character.isSurrogate(c)) {
                    // Not UTF-8: Go reads it as U+FFFD, which it writes as is.
                    sb.append('\ufffd');
                } else {
                    sb.append(c);
                }
            }
            sb.append('"');
        }
    }

    // ---------------------------------------------------------------- frames

    /** Sends one whole frame; it throws when the receiver did not take it. */
    interface FrameSender {
        void send(byte[] frame) throws IOException;
    }

    /**
     * FrameWriter cuts the NDJSON stream of records into TABLE frames of at most MAX_FRAME_BYTES each and
     * sends them in order, one at a time.
     */
    static final class FrameWriter implements RecordSink {
        private final FrameSender sender;
        private final int bodyCap;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final MessageDigest digest;
        int seq;
        private String build;

        FrameWriter(FrameSender sender) {
            this(sender, MAX_FRAME_BYTES);
        }

        FrameWriter(FrameSender sender, int maxFrame) {
            this.sender = sender;
            this.bodyCap = maxFrame - HEADER_ROOM;
            if (bodyCap < 1) {
                throw new IllegalArgumentException("frame cap " + maxFrame + " leaves no room for a body");
            }
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void record(String build, byte[] json) throws IOException {
            this.build = build;
            digest.update(json);
            digest.update((byte) '\n');
            write(json, 0, json.length);
            write(new byte[] {'\n'}, 0, 1);
        }

        private void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int room = bodyCap - body.size();
                if (room == 0) {
                    flush(false, -1);
                    continue;
                }
                int take = Math.min(room, len);
                body.write(b, off, take);
                off += take;
                len -= take;
            }
        }

        /** finish sends the last frame, with the record count and the stream's sha256. */
        void finish(String build, int records) throws IOException {
            this.build = build;
            flush(true, records);
        }

        private void flush(boolean last, int records) throws IOException {
            StringBuilder h = new StringBuilder(160);
            h.append("{\"v\":1,\"part\":\"table\",\"build\":");
            Json.string(h, build);
            h.append(",\"seq\":").append(seq).append(",\"last\":").append(last);
            if (last) {
                h.append(",\"records\":").append(records).append(",\"sha256\":\"").append(hex(digest.digest()))
                        .append('"');
            }
            h.append("}\n");
            byte[] header = h.toString().getBytes(StandardCharsets.UTF_8);
            byte[] payload = body.toByteArray();
            int n = header.length + payload.length;
            byte[] frame = new byte[8 + n];
            System.arraycopy(MAGIC.getBytes(StandardCharsets.US_ASCII), 0, frame, 0, 4);
            frame[4] = (byte) (n >>> 24);
            frame[5] = (byte) (n >>> 16);
            frame[6] = (byte) (n >>> 8);
            frame[7] = (byte) n;
            System.arraycopy(header, 0, frame, 8, header.length);
            System.arraycopy(payload, 0, frame, 8 + header.length, payload.length);
            try {
                sender.send(frame);
            } catch (IOException e) {
                throw new IOException("TABLE frame " + seq + ": " + e.getMessage(), e);
            }
            body.reset();
            seq++;
        }
    }

    /**
     * SocketSender sends each frame on its own connection to the data socket, shuts its output so a receiver
     * reading to the end of the connection is never left waiting, and waits for OK.
     */
    static final class SocketSender implements FrameSender {
        private final File socket;

        SocketSender(File socket) {
            this.socket = socket;
        }

        @Override
        public void send(byte[] frame) throws IOException {
            AFUNIXSocket s = connect();
            try {
                s.setSoTimeout(REPLY_TIMEOUT_MILLIS);
                OutputStream out = s.getOutputStream();
                out.write(frame);
                out.flush();
                // Through the channel: junixsocket's Socket.shutdownOutput says "not connected" after connect.
                s.getChannel().shutdownOutput();
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(),
                        StandardCharsets.UTF_8));
                String reply = in.readLine();
                if (!"OK".equals(reply)) {
                    throw new IOException(reply == null ? "Keploy closed the connection without OK"
                            : "Keploy answered " + reply + ", not OK");
                }
            } finally {
                closeQuietly(s);
            }
        }

        private AFUNIXSocket connect() throws IOException {
            IOException lastErr = null;
            for (int attempt = 0; attempt < CONNECT_ATTEMPTS; attempt++) {
                if (attempt > 0) {
                    sleep(1000L * attempt);
                }
                AFUNIXSocket s = AFUNIXSocket.newInstance();
                try {
                    s.connect(AFUNIXSocketAddress.of(socket), CONNECT_TIMEOUT_MILLIS);
                    return s;
                } catch (IOException e) {
                    lastErr = e;
                    closeQuietly(s);
                }
            }
            throw new IOException("no Keploy listens on the data socket " + socket + " (" + lastErr + ")",
                    lastErr);
        }

        private static void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static void closeQuietly(AFUNIXSocket s) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }
}
