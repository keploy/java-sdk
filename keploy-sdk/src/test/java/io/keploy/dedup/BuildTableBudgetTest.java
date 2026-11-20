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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
 * is measured; the numbers are for reading, not asserted.
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

    static final class Budget {
        int tableRecords;
        int probeRecords;
        int notMeasured;
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
            return measure(label, classes, heapPools, afterGcMax, collections);
        } finally {
            for (NotificationEmitter e : emitters) {
                e.removeNotificationListener(listener);
            }
        }
    }

    private static Budget measure(String label, List<BuildTable.ClassFile> classes, Set<String> heapPools,
            AtomicLong afterGcMax, AtomicInteger collections) throws Exception {
        System.gc();
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
        // A record may run on into the next frame: not_measured is counted over the table's stream, keeping
        // the end of each frame that could begin a split match.
        String notMeasured = "\"state\":\"not_measured\"";
        String[] carry = {""};
        Budget b = new Budget();
        long gc0 = gcMillis();
        long process0 = processCpuNanos();
        long cpu0 = threads.getCurrentThreadCpuTime();
        long t0 = System.nanoTime();
        BuildTable.send(() -> classes, frame -> {
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
            String f = new String(frame, StandardCharsets.UTF_8);
            int at = f.indexOf("{\"v\":1,");
            String header = f.substring(at, f.indexOf('\n', at));
            boolean probes = header.contains("\"part\":\"probes\"");
            bytes[probes ? 1 : 0] += frame.length;
            if (header.contains("\"last\":true")) {
                int records = Integer.parseInt(header.replaceAll(".*\"records\":(\\d+).*", "$1"));
                if (probes) {
                    b.probeRecords = records;
                } else {
                    b.tableRecords = records;
                }
            }
            if (!probes) {
                String body = carry[0] + f.substring(f.indexOf('\n', at) + 1);
                for (int k = body.indexOf(notMeasured); k >= 0; k = body.indexOf(notMeasured, k + 1)) {
                    b.notMeasured++;
                }
                carry[0] = body.substring(Math.max(0, body.length() - (notMeasured.length() - 1)));
            }
        });
        long wall = System.nanoTime() - t0;
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
                        + " table_bytes=%d probes_bytes=%d not_measured=%d%n",
                label, System.getProperty("java.version"), Runtime.getRuntime().maxMemory() >> 20, classes.size(),
                classBytes, wall / 1_000_000, cpu / 1_000_000, process / 1_000_000, gc, collections.get(),
                base >> 20, afterGc < 0 ? -1 : afterGc >> 20, afterGc < 0 ? -1 : (afterGc - base) >> 20, peak >> 20,
                liveMax[0] < 0 ? -1 : liveMax[0] >> 20, liveMax[0] < 0 ? -1 : (liveMax[0] - base) >> 20,
                bytes[0], bytes[1], b.notMeasured);
        return b;
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
