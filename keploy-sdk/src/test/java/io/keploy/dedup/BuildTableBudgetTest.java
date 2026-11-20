package io.keploy.dedup;

import com.sun.management.GarbageCollectionNotificationInfo;
import org.jacoco.core.analysis.Analyzer;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The build table's cost inside the app's JVM, measured and reported (a BUDGET line on stdout): on a generated
 * app (classes of one if/else chain method each, as the large-app profile generates them), on one class with a
 * single long method, and on a real library. -Dkeploy.budget.classes=50000 and -Dkeploy.budget.branches=2000
 * measure the large app and a 2000-branch method; the defaults keep the rows quick. It checks that every class
 * is measured, what a generated class allocates (bounded below), that the generated app's send, paced by the
 * collections, grows the committed heap by no more than maxCommittedGrowth (BUDGET-COMMITTED committed_growth_mb,
 * over the heap just after the row's own full collection) within 60 s at 50,000 classes with no frame more than
 * 10 s after the one before, and that a 3000-branch method maps in under a second of CPU; the other numbers are
 * for reading. committed_growth_from_pre_gc_mb is over the heap before that collection, which the row's own
 * class generation sized: it is not asserted.
 *
 * <p>heap_after_gc_max_mb is the most heap any collection during the send left in use: an upper bound of what
 * the analysis keeps live (a young collection also counts old garbage not collected yet). It is -1 when no
 * collection ran. heap_delta_mb is that less the heap in use before the send (heap_before_mb, after a
 * collection: the classes' bytes and the test's own): what the send kept live at most. heap_peak_mb sums each
 * heap pool's peak during the send (garbage included, so it follows the collector and the cap, not the live
 * set). heap_max_mb is the heap cap the JVM ran with (-Xmx, or its default from the machine's RAM).
 *
 * <p>With -Dkeploy.budget.live=true each frame, as it is sent, first runs a full collection and reads the heap
 * in use: live_max_mb is the most of those (what the send holds live at a frame boundary, the table's last
 * frame included), live_delta_mb that less heap_before_mb. Those collections slow the send: read its times
 * from a run without it. Without it both are -1.
 */
public class BuildTableBudgetTest {

    static final int BRANCHES = 15;

    @Test(timeout = 1_800_000)
    public void aGeneratedAppsTableIsMeasured() throws Exception {
        int n = Integer.getInteger("keploy.budget.classes", 2000);
        List<BuildTable.ClassFile> app = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            app.add(new BuildTable.ClassFile(name(i), gen(name(i), i, BRANCHES)));
        }
        Budget b = measure("app-" + n, app);
        assertEquals(n, b.tableRecords);
        assertEquals(n, b.probeRecords);
        assertEquals("every class is measured", 0, b.notMeasured);
        // What the send allocates per class: about 1.9 MiB when each probe re-analyzed its method, 123-141 KiB
        // when JaCoCo analyzed each class twice (once for its table, once for its probe map), and 84-96 KiB
        // since one analysis gives both (and the records' text).
        long perClass = b.allocated / n;
        assertTrue("allocated " + perClass + " bytes a class", perClass < ALLOCATED_PER_CLASS);
        // Paced by the collections, the send grows the committed heap over what it was just after a full
        // collection by no more than maxCommittedGrowth, within 60 s at 50,000 classes, and its frames keep
        // coming. A live run collects at each frame, which the pacing waits out: read its numbers only.
        if (Boolean.getBoolean("keploy.budget.live")) {
            return;
        }
        long max = maxCommittedGrowth(gcNames().contains("G1"), Runtime.getRuntime().maxMemory(),
                System.getProperty("java.specification.version"));
        assertTrue("committed heap growth " + (b.committedGrowth >> 20) + " MiB, over " + (max >> 20),
                b.committedGrowth <= max);
        assertTrue("took " + b.wallNanos / 1_000_000 + " ms", b.wallNanos < maxWallNanos(n));
        assertTrue("a frame came " + b.maxFrameGapNanos / 1_000_000 + " ms after the one before",
                b.maxFrameGapNanos < MAX_FRAME_GAP_NANOS);
    }

    /**
     * maxCommittedGrowth is the most the send may add to an app pod's committed heap over the heap just after a
     * full collection: 128 MiB, but more on G1 with a heap cap of 512 MiB or more before JDK 21. There the
     * collection has shrunk G1's heap, its pause raises G1's pause-time ratio, and G1 grows the heap back while
     * the table is sent, paced or not: at 50,000 classes in a 4 GiB pod, 300 MiB on JDK 8 (unpaced 422-707) and
     * 145 MiB on JDK 17, paced by the send's own work time (one run each; earlier pacers saw 420-421 and 13-166).
     * Unpaced, JDK 21's G1 there grew 175-343 MiB (paced, 59), past the bound.
     */
    static long maxCommittedGrowth(boolean g1, long maxHeap, String javaSpec) {
        if (g1 && maxHeap >= 512L << 20) {
            if (javaSpec.startsWith("1.")) {
                return 448L << 20;
            }
            if (Integer.parseInt(javaSpec) < 21) {
                return 192L << 20;
            }
        }
        return 128L << 20;
    }

    /** The longest a frame may come after the one before (or the send's start): Keploy waits 30 s for one. */
    static final long MAX_FRAME_GAP_NANOS = 10_000_000_000L;

    /**
     * maxWallNanos is the table's time bound: 60 s at 50,000 classes, at least 60 s for fewer, and 1.2 ms a class
     * past 50,000. At 50,000 that is 40 s past the pacing's own deadline (BuildTable.Pacer.deadlineNanos), which
     * leaves a slow or busy CPU's own work that time; past 50,000 it leaves more.
     */
    static long maxWallNanos(int classes) {
        return Math.max(60_000_000_000L, 60_000_000_000L / 50_000 * classes);
    }

    /** The most a generated class's table and probe map may allocate on the send's thread: below two analyses'. */
    static final long ALLOCATED_PER_CLASS = 112 << 10;

    /** The busy app's allocation, in MB/s over its threads: a JVM serving a test run's requests. */
    static final int BUSY_MBPS = 400;
    static final int BUSY_THREADS = 2;
    static final int BUSY_ARRAY = 64 << 10;
    /** How long the app's load runs before Keploy asks for the table. */
    static final long BUSY_SETTLE_MS = 2_000;
    /** What the waits may run past the send's own work: one sleep's overrun (a collection's pause in it). */
    static final long BUSY_OVERRUN_MS = 500;
    /** The send's INFO line: its classes, those not measured, its time, its CPU and its waits. */
    static final Pattern SENT = Pattern.compile("sent \\((\\d+) classes, (\\d+) not measured.*; \\d+ frames,"
            + " (\\d+) ms, cpu -?\\d+ ms, paced (\\d+) ms\\)$");

    /**
     * A busy app's table, the native keploy test's case: app threads allocate BUSY_MBPS of short-lived garbage
     * while the table is sent, so the JVM collects often for the app's own sake, and the pacing (which reads
     * every collection, the app's too) is owed far more than it may wait. Its waits must stay within the send's
     * own work time (one sleep's overrun past it at most), so a busy app's table takes at most about twice as
     * long as unpaced: before, they took the whole allowance of 0.8 ms a class (40 s at 50,000), and the app's
     * test set ended before its table was sent. The class count is -Dkeploy.budget.classes (5,000 by default).
     */
    @Test(timeout = 900_000)
    public void aBusyAppsTableIsPacedAtMostItsOwnWork() throws Exception {
        int n = Integer.getInteger("keploy.budget.classes", 5000);
        List<BuildTable.ClassFile> app = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            app.add(new BuildTable.ClassFile(name(i), gen(name(i), i, BRANCHES)));
        }
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong appBytes = new AtomicLong();
        // Each thread keeps a ring of its newest arrays, a churning retained set as request handling has: 64 MiB
        // a thread, and at most 1/16 of the heap's cap in all.
        int slots = (int) Math.min(1024, Runtime.getRuntime().maxMemory() / 16 / BUSY_THREADS / BUSY_ARRAY);
        // The load's threads and the sampler: each ends at stop, and finally stops and joins them all.
        List<Thread> threads = new ArrayList<>();
        try (BuildTableTest.Logs logs = new BuildTableTest.Logs()) {
            for (int t = 0; t < BUSY_THREADS; t++) {
                Thread th = new Thread(() -> allocate(stop, appBytes, slots), "busy-app-" + t);
                th.setDaemon(true);
                th.start();
                threads.add(th);
            }
            Thread.sleep(BUSY_SETTLE_MS);
            long committed0 = heapCommitted();
            AtomicLong committedMax = new AtomicLong(committed0);
            AtomicBoolean sending = new AtomicBoolean(true);
            Thread sampler = new Thread(() -> {
                while (sending.get() && !stop.get()) {
                    committedMax.accumulateAndGet(heapCommitted(), Math::max);
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "busy-committed");
            sampler.setDaemon(true);
            sampler.start();
            threads.add(sampler);
            long gc0 = gcMillis();
            long app0 = appBytes.get();
            long t0 = System.nanoTime();
            long[] frames = {t0, 0}; // the last frame's time, the longest gap
            BuildTable.send(() -> app, new BuildTable.FrameSender() {
                @Override
                public void send(byte[] frame) {
                    send(frame, 0, frame.length);
                }

                @Override
                public void send(byte[] frame, int off, int len) {
                    long now = System.nanoTime();
                    frames[1] = Math.max(frames[1], now - frames[0]);
                    frames[0] = now;
                }
            });
            long wall = System.nanoTime() - t0;
            long gc = gcMillis() - gc0;
            sending.set(false);
            sampler.join();
            String sent = null;
            for (LogRecord r : logs.mine()) {
                if (r.getMessage() != null && r.getMessage().contains(" sent (")) {
                    sent = r.getMessage();
                }
            }
            assertTrue("the send's INFO line", sent != null);
            Matcher m = SENT.matcher(sent);
            assertTrue(sent, m.find());
            long sendMs = Long.parseLong(m.group(3));
            long pacedMs = Long.parseLong(m.group(4));
            long workMs = sendMs - pacedMs;
            System.out.printf(Locale.ROOT, "BUSY app-%d java=%s gc=%s heap_max_mb=%d app_mbps=%d wall_ms=%d"
                            + " send_ms=%d paced_ms=%d work_ms=%d gc_ms=%d gc_pct=%.2f max_frame_gap_ms=%d"
                            + " committed_start_mb=%d committed_max_mb=%d committed_growth_mb=%d app_mb=%d%n",
                    n, System.getProperty("java.version"), gcNames(), Runtime.getRuntime().maxMemory() >> 20,
                    BUSY_MBPS, wall / 1_000_000, sendMs, pacedMs, workMs, gc,
                    100.0 * gc / Math.max(1, wall / 1_000_000), frames[1] / 1_000_000, committed0 >> 20,
                    committedMax.get() >> 20, (committedMax.get() - committed0) >> 20,
                    (appBytes.get() - app0) >> 20);
            assertEquals(sent, n, Integer.parseInt(m.group(1)));
            assertEquals(sent, 0, Integer.parseInt(m.group(2)));
            assertTrue("paced " + pacedMs + " ms, past the send's own " + workMs + " ms of work",
                    pacedMs <= workMs + BUSY_OVERRUN_MS);
            assertTrue("took " + wall / 1_000_000 + " ms", wall < maxWallNanos(n));
            assertTrue("a frame came " + frames[1] / 1_000_000 + " ms after the one before",
                    frames[1] < MAX_FRAME_GAP_NANOS);
        } finally {
            stop.set(true);
            for (Thread th : threads) {
                th.join();
            }
        }
    }

    /** allocate is a busy app's thread: BUSY_MBPS / BUSY_THREADS of BUSY_ARRAY arrays, a ring of slots kept. */
    private static void allocate(AtomicBoolean stop, AtomicLong appBytes, int slots) {
        long perMs = (long) BUSY_MBPS * 1_000_000 / 1000 / BUSY_THREADS;
        byte[][] ring = new byte[Math.max(1, slots)][];
        long t0 = System.nanoTime();
        long made = 0;
        int k = 0;
        while (!stop.get()) {
            for (int j = 0; j < 16; j++) {
                byte[] b = new byte[BUSY_ARRAY];
                b[0] = (byte) k;
                ring[k] = b;
                k = k + 1 == ring.length ? 0 : k + 1;
            }
            made += 16L * BUSY_ARRAY;
            appBytes.addAndGet(16L * BUSY_ARRAY);
            long ahead = made / perMs - (System.nanoTime() - t0) / 1_000_000;
            if (ahead > 0) {
                try {
                    Thread.sleep(ahead);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    @Test(timeout = 600_000)
    public void aClassWithOneLongMethodIsMeasured() throws Exception {
        int branches = Integer.getInteger("keploy.budget.branches", 500);
        List<BuildTable.ClassFile> one = new ArrayList<>();
        one.add(new BuildTable.ClassFile("gen/big/Long", gen("gen/big/Long", 0, branches)));
        Budget b = measure("one-method-" + branches + "-branches", one);
        assertEquals(1, b.probeRecords);
        assertEquals(0, b.notMeasured);
    }

    @Test(timeout = 600_000)
    public void aMethodOf3000BranchesMapsInUnderASecond() throws Exception {
        // An if/else chain's statements join at labels two edges reach, so its probes' walks are short. When
        // each probe re-analyzed its method, 3,500 took about a minute, past the CLI's 30 s wait for a frame,
        // so one such class left every run's table incomplete.
        List<BuildTable.ClassFile> one = new ArrayList<>();
        one.add(new BuildTable.ClassFile("gen/big/Long3000", gen("gen/big/Long3000", 0, 3000)));
        Budget b = measure("one-method-3000-branches", one);
        assertEquals(1, b.probeRecords);
        assertEquals(0, b.notMeasured);
        assertTrue("the send took " + (b.cpuNanos / 1_000_000) + " ms of CPU", b.cpuNanos < 1_000_000_000L);
    }

    @Test(timeout = 600_000)
    public void aChainOfEarlyReturnsIsMappedOrTooComplexAndNeverUnbounded() throws Exception {
        // Probe k of a chain of n early returns walks back over the k checks before it and covers each one's
        // line and branch, so the map is about n * n ranges: 4.4 MB at 1,000 and 43 MB at 3,000; 4,500 ran a
        // 256 MiB heap out of memory (and 3,000 a 128 MiB one) before the map had a bound. Past it the class
        // is unit_too_complex, given up as its map grows past the bound.
        for (int n : new int[] {1000, 3000, 4500}) {
            String name = "gen/big/Returns" + n;
            List<BuildTable.ClassFile> one = new ArrayList<>();
            one.add(new BuildTable.ClassFile(name, returns(name, n)));
            Budget b = measure("early-returns-" + n, one);
            boolean mapped = n == 1000;
            assertEquals(1, b.tableRecords);
            assertEquals(name, mapped ? 1 : 0, b.probeRecords);
            assertEquals(name, mapped ? 0 : 1, b.notMeasured);
            assertEquals(name, mapped ? 0 : 1, b.tooComplex);
            assertTrue(name + ": the send took " + (b.cpuNanos / 1_000_000) + " ms of CPU",
                    b.cpuNanos < 1_000_000_000L);
            assertTrue(name + ": the send allocated " + (b.allocated >> 20) + " MiB",
                    b.allocated < MAP_ALLOCATED);
        }
    }

    /** The most one class's table and probe map may allocate, its map at the bound: 8 MiB of text. */
    static final long MAP_ALLOCATED = 64 << 20;

    @Test(timeout = 600_000)
    public void aRealLibraryIsMeasured() throws Exception {
        File jar = new File(Analyzer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<BuildTable.ClassFile> lib = ProbeMapTest.jarClasses(jar);
        Budget b = measure("jacoco-core-jar", lib);
        assertTrue(b.tableRecords > 100);
        assertEquals(0, b.notMeasured);
    }

    static String name(int i) {
        return String.format(Locale.ROOT, "gen/app/p%03d/C%05d", i / 100, i);
    }

    /** gen is a class with a static run(int) of branches if/else pairs, a line each (about 2*branches+1 probes). */
    static byte[] gen(String name, int i, int branches) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitSource(name.substring(name.lastIndexOf('/') + 1) + ".java", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        Label first = new Label();
        init.visitLabel(first);
        init.visitLineNumber(5, first);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(I)I", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 1);
        for (int b = 0; b < branches; b++) {
            Label line = new Label();
            mv.visitLabel(line);
            mv.visitLineNumber(10 + b, line);
            Label orElse = new Label();
            Label next = new Label();
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitIntInsn(Opcodes.SIPUSH, (b * 7 + i) % 64);
            mv.visitJumpInsn(Opcodes.IF_ICMPLE, orElse);
            mv.visitIincInsn(1, b % 100 + 1);
            mv.visitJumpInsn(Opcodes.GOTO, next);
            mv.visitLabel(orElse);
            mv.visitIincInsn(1, -1);
            mv.visitLabel(next);
        }
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Matches counts an ASCII text over a stream of byte chunks, a match split over chunks included. */
    static final class Matches {
        private final byte[] what;
        // The stream's last what.length - 1 bytes (fewer at its start), where a split match begins.
        private final byte[] tail;
        private int tailLen;
        int n;

        Matches(String what) {
            this.what = what.getBytes(StandardCharsets.US_ASCII);
            this.tail = new byte[this.what.length - 1];
        }

        void add(byte[] b, int from, int to) {
            // Matches that begin in the tail and end in this chunk.
            for (int i = 0; i < tailLen; i++) {
                int inTail = tailLen - i;
                if (inTail + (to - from) >= what.length && same(tail, i, 0, inTail)
                        && same(b, from, inTail, what.length - inTail)) {
                    n++;
                }
            }
            for (int i = from; i + what.length <= to; i++) {
                if (same(b, i, 0, what.length)) {
                    n++;
                }
            }
            int len = to - from;
            if (len >= tail.length) {
                System.arraycopy(b, to - tail.length, tail, 0, tail.length);
                tailLen = tail.length;
            } else {
                int keep = Math.min(tailLen, tail.length - len);
                System.arraycopy(tail, tailLen - keep, tail, 0, keep);
                System.arraycopy(b, from, tail, keep, len);
                tailLen = keep + len;
            }
        }

        // same says whether b[at, at + len) is what[whatAt, whatAt + len).
        private boolean same(byte[] b, int at, int whatAt, int len) {
            for (int k = 0; k < len; k++) {
                if (b[at + k] != what[whatAt + k]) {
                    return false;
                }
            }
            return true;
        }
    }

    /** returns is a class with a static run(int) of n lines "if (x == k) return k;", then "return -1;". */
    static byte[] returns(String name, int n) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitSource(name.substring(name.lastIndexOf('/') + 1) + ".java", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(I)I", null, null);
        mv.visitCode();
        for (int k = 0; k < n; k++) {
            Label line = new Label();
            mv.visitLabel(line);
            mv.visitLineNumber(10 + k, line);
            Label next = new Label();
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitIntInsn(Opcodes.SIPUSH, k);
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, next);
            mv.visitIntInsn(Opcodes.SIPUSH, k);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLabel(next);
        }
        Label end = new Label();
        mv.visitLabel(end);
        mv.visitLineNumber(10 + n, end);
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    static final class Budget {
        int tableRecords;
        int probeRecords;
        int notMeasured;
        /** How many of the table's records are unit_too_complex. */
        int tooComplex;
        /** Bytes the send allocated on its thread (the sink's own allocation left out). */
        long allocated;
        /** The send thread's CPU time, in nanoseconds. */
        long cpuNanos;
        /** The send's wall time, in nanoseconds. */
        long wallNanos;
        /** The most the heap's committed size grew over what it was just before the send (after a collection). */
        long committedGrowth;
        /** The longest time between two frames, or the send's start and its first frame. */
        long maxFrameGapNanos;
    }

    /** measure sends the table of classes to a sink that counts, as the table's thread does, and reports. */
    static Budget measure(String label, List<BuildTable.ClassFile> classes) throws Exception {
        Set<String> heapPools = new HashSet<>();
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == MemoryType.HEAP) {
                heapPools.add(p.getName());
            }
        }
        AtomicLong afterGcMax = new AtomicLong(-1);
        AtomicLong committedGcMax = new AtomicLong(-1);
        AtomicInteger collections = new AtomicInteger();
        NotificationListener listener = (n, handback) -> {
            if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(n.getType())) {
                return;
            }
            GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from(
                    (CompositeData) n.getUserData());
            if ("System.gc()".equals(info.getGcCause())) {
                // This method's own collection before the send, whose notification can come late.
                return;
            }
            long used = 0;
            for (Map.Entry<String, MemoryUsage> e : info.getGcInfo().getMemoryUsageAfterGc().entrySet()) {
                if (heapPools.contains(e.getKey())) {
                    used += e.getValue().getUsed();
                }
            }
            long before = 0;
            long after = 0;
            for (Map.Entry<String, MemoryUsage> e : info.getGcInfo().getMemoryUsageBeforeGc().entrySet()) {
                if (heapPools.contains(e.getKey())) {
                    before += e.getValue().getCommitted();
                }
            }
            for (Map.Entry<String, MemoryUsage> e : info.getGcInfo().getMemoryUsageAfterGc().entrySet()) {
                if (heapPools.contains(e.getKey())) {
                    after += e.getValue().getCommitted();
                }
            }
            committedGcMax.accumulateAndGet(Math.max(before, after), Math::max);
            collections.incrementAndGet();
            afterGcMax.accumulateAndGet(used, Math::max);
        };
        List<NotificationEmitter> emitters = new ArrayList<>();
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (g instanceof NotificationEmitter) {
                ((NotificationEmitter) g).addNotificationListener(listener, null, null);
                emitters.add((NotificationEmitter) g);
            }
        }
        try {
            return measure(label, classes, heapPools, afterGcMax, committedGcMax, collections);
        } finally {
            for (NotificationEmitter e : emitters) {
                e.removeNotificationListener(listener);
            }
        }
    }

    private static Budget measure(String label, List<BuildTable.ClassFile> classes, Set<String> heapPools,
            AtomicLong afterGcMax, AtomicLong committedGcMax, AtomicInteger collections) throws Exception {
        long committedPre = heapCommitted();
        System.gc();
        long committedBase = heapCommitted();
        AtomicLong committedSampled = new AtomicLong(committedBase);
        java.util.concurrent.atomic.AtomicBoolean sending = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread sampler = new Thread(() -> {
            while (sending.get()) {
                committedSampled.accumulateAndGet(heapCommitted(), Math::max);
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "budget-committed");
        sampler.setDaemon(true);
        long base = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (heapPools.contains(p.getName())) {
                base += p.getUsage().getUsed();
            }
        }
        long classBytes = 0;
        for (BuildTable.ClassFile f : classes) {
            classBytes += f.bytes.length;
        }
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (heapPools.contains(p.getName())) {
                p.resetPeakUsage();
            }
        }
        long[] bytes = new long[2];
        boolean live = Boolean.getBoolean("keploy.budget.live");
        long[] liveMax = {-1};
        // A record may run on into the next frame: not_measured and unit_too_complex are counted over the
        // table's stream, a match split over two frames included. The sink reads each frame where the writer
        // keeps it, as the socket's does: a copy or a String of a 1 MiB frame is a G1 humongous object, which
        // grows the heap this row measures.
        Matches notMeasured = new Matches("\"state\":\"not_measured\"");
        Matches tooComplex = new Matches("\"reason\":{\"code\":\"unit_too_complex\"}");
        Budget b = new Budget();
        long gc0 = gcMillis();
        long process0 = processCpuNanos();
        com.sun.management.ThreadMXBean allocation = (com.sun.management.ThreadMXBean) threads;
        long self = Thread.currentThread().getId();
        long[] sinkAllocated = {0};
        long allocated0 = allocation.getThreadAllocatedBytes(self);
        long cpu0 = threads.getCurrentThreadCpuTime();
        long t0 = System.nanoTime();
        long[] lastFrame = {t0, 0};
        sampler.start();
        BuildTable.send(() -> classes, new BuildTable.FrameSender() {
            @Override
            public void send(byte[] frame) throws java.io.IOException {
                send(frame, 0, frame.length);
            }

            @Override
            public void send(byte[] frame, int off, int len) {
                long sink0 = allocation.getThreadAllocatedBytes(self);
                long at0 = System.nanoTime();
                lastFrame[1] = Math.max(lastFrame[1], at0 - lastFrame[0]);
                if (live) {
                    System.gc();
                    long used = 0;
                    for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
                        if (heapPools.contains(p.getName())) {
                            used += p.getUsage().getUsed();
                        }
                    }
                    liveMax[0] = Math.max(liveMax[0], used);
                }
                // The header is the line after the 8 bytes of magic and length.
                int nl = off + 8;
                while (frame[nl] != '\n') {
                    nl++;
                }
                String header = new String(frame, off + 8, nl - off - 8, StandardCharsets.UTF_8);
                boolean probes = header.contains("\"part\":\"probes\"");
                bytes[probes ? 1 : 0] += len;
                if (header.contains("\"last\":true")) {
                    int records = Integer.parseInt(header.replaceAll(".*\"records\":(\\d+).*", "$1"));
                    if (probes) {
                        b.probeRecords = records;
                    } else {
                        b.tableRecords = records;
                    }
                }
                if (!probes) {
                    notMeasured.add(frame, nl + 1, off + len);
                    tooComplex.add(frame, nl + 1, off + len);
                }
                sinkAllocated[0] += allocation.getThreadAllocatedBytes(self) - sink0;
                // The sink's own time (a live run's collection) is not the send's gap.
                lastFrame[0] = System.nanoTime();
            }
        });
        b.notMeasured = notMeasured.n;
        b.tooComplex = tooComplex.n;
        long wall = System.nanoTime() - t0;
        long allocated = allocation.getThreadAllocatedBytes(self) - allocated0 - sinkAllocated[0];
        sending.set(false);
        sampler.join();
        long committedEnd = heapCommitted();
        long cpu = threads.getCurrentThreadCpuTime() - cpu0;
        long process = processCpuNanos() - process0;
        long gc = gcMillis() - gc0;
        long peak = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (heapPools.contains(p.getName())) {
                peak += p.getPeakUsage().getUsed();
            }
        }
        // The JVM sends a collection's notification after it ends, from a thread of its own.
        Thread.sleep(200);
        long afterGc = afterGcMax.get();
        System.out.printf(Locale.ROOT, "BUDGET %s java=%s heap_max_mb=%d classes=%d class_bytes=%d wall_ms=%d"
                        + " thread_cpu_ms=%d process_cpu_ms=%d gc_ms=%d collections=%d heap_before_mb=%d"
                        + " heap_after_gc_max_mb=%d heap_delta_mb=%d heap_peak_mb=%d live_max_mb=%d live_delta_mb=%d"
                        + " table_bytes=%d probes_bytes=%d not_measured=%d allocated_mb=%d allocated_kb_per_class=%d%n",
                label, System.getProperty("java.version"), Runtime.getRuntime().maxMemory() >> 20, classes.size(),
                classBytes, wall / 1_000_000, cpu / 1_000_000, process / 1_000_000, gc, collections.get(),
                base >> 20, afterGc < 0 ? -1 : afterGc >> 20, afterGc < 0 ? -1 : (afterGc - base) >> 20, peak >> 20,
                liveMax[0] < 0 ? -1 : liveMax[0] >> 20, liveMax[0] < 0 ? -1 : (liveMax[0] - base) >> 20,
                bytes[0], bytes[1], b.notMeasured, allocated >> 20, (allocated / Math.max(1, classes.size())) >> 10);
        b.allocated = allocated;
        b.cpuNanos = cpu;
        b.wallNanos = wall;
        b.maxFrameGapNanos = lastFrame[1];
        long committedPeak = Math.max(committedSampled.get(), committedGcMax.get());
        b.committedGrowth = committedPeak - committedBase;
        System.out.printf(Locale.ROOT, "BUDGET-COMMITTED %s java=%s gc=%s heap_max_mb=%d committed_pre_gc_mb=%d"
                        + " committed_before_mb=%d committed_peak_mb=%d committed_end_mb=%d committed_growth_mb=%d"
                        + " committed_growth_from_pre_gc_mb=%d max_frame_gap_ms=%d%n",
                label, System.getProperty("java.version"), gcNames(), Runtime.getRuntime().maxMemory() >> 20,
                committedPre >> 20, committedBase >> 20, committedPeak >> 20, committedEnd >> 20,
                (committedPeak - committedBase) >> 20, (committedPeak - committedPre) >> 20, lastFrame[1] / 1_000_000);
        return b;
    }

    private static long heapCommitted() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted();
    }

    private static String gcNames() {
        StringBuilder names = new StringBuilder();
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            names.append(names.length() == 0 ? "" : "+").append(g.getName().replace(' ', '_'));
        }
        return names.toString();
    }

    private static long gcMillis() {
        long t = 0;
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            t += Math.max(0, g.getCollectionTime());
        }
        return t;
    }

    private static long processCpuNanos() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuTime();
        }
        return -1;
    }
}
