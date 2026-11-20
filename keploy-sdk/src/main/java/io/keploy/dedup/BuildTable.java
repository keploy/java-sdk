package io.keploy.dedup;

import org.jacoco.core.analysis.ILine;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.analysis.ISourceNode;
import org.jacoco.core.internal.analysis.KeployBranchCoverage;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import java.util.function.IntSupplier;
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
 *
 * <p>After the table's last frame the probe map follows as a second stream of the same frames, with
 * {@code "part":"probes"} and seq from 0 again: one record per class file name of each measured class, giving
 * for each JaCoCo probe the leaves (indices into its class's leaves, as ranges) that a hit of that probe
 * covers. Keploy turns each test's probe hits, which name a class by its name, into its leaves with it; it is
 * never uploaded. Two class files of one name whose bytes differ share the record when they map their probes
 * alike, and fail their class closed (not measured) when not.
 *
 * <p>The send holds no whole table in the heap: each class's table record goes into the frame being filled
 * as it is made, and its probe-map records into a temp file (owner-only, unlinked once open where the OS
 * allows), which is streamed back as the probe map's frames once the table's last frame is sent. So what a
 * send keeps is the class list (names and ids, no bytes of its own), one frame and one class's analysis.
 * A TABLE asked when the heap is short is not taken: see {@link #answer}.
 */
final class BuildTable {

    private static final Logger LOGGER = Logger.getLogger(BuildTable.class.getName());

    /**
     * log never throws. java.util.logging does not catch what a Handler throws (an app's logging bridge, an OOM
     * while formatting), and a log that throws would end a send, or leave {@link Sends} running with no thread:
     * every later TABLE would be ACKed and never sent.
     */
    static void log(Level level, String message, Throwable thrown) {
        try {
            LOGGER.log(level, message, thrown);
        } catch (Throwable ignored) {
            // Nowhere left to say it: the log is what failed.
        }
    }

    /** The control-socket command Keploy sends for the build table. */
    static final String COMMAND = "TABLE";
    /**
     * The table's format, F of the build id (IndexReport.buildId): the contract's section 3.3 records as this
     * class writes them and the probe map of leaf ranges, with every bound that decides what a class's records
     * are ({@link #MAX_RECORD_BYTES}, {@link #MAX_MAP_BYTES}, {@link #MAX_MAP_STEPS}, how
     * KeployBranchCoverage.Hits counts its steps, and the reason a unit past one is not measured). Raise it in
     * any release where the table or probe-map records written for the same class bytes can change, so they
     * name another build. The KTB1 frames that carry the records are not in it: their header's "v" names them.
     */
    static final int TABLE_FORMAT = 1;
    /** A TABLE frame's size cap, magic and length included. */
    static final int MAX_FRAME_BYTES = 1 << 20;
    /** A record (with its newline) larger than a whole inflated chunk can never be sent: record_too_large. */
    static final int MAX_RECORD_BYTES = 8 << 20;
    /** The reason a unit's record is sent without its methods when it, or its probe map, is past a bound. */
    static final String RECORD_TOO_LARGE = "record_too_large";
    /**
     * The most a unit's probe map may take: its records' bytes, newlines included (a table record's bound),
     * and the steps its probes' walks take (KeployBranchCoverage.Hits#steps, about a second of CPU). A unit
     * past either is sent not_measured record_too_large with no probe record, and its map is given up as
     * soon as it is past one, so neither the heap nor the time it takes grows with it: a method of n early
     * returns maps to about n * n ranges (its probe k covers the k checks before it), 4.4 MB at 1,000 and
     * 43 MB at 3,000. The bound is on the text; what a unit's map holds live until its records are made is
     * its ranges' int bounds (4 bytes each, a bound's text at least about 1.5), so up to about 2.7 times
     * the bound.
     */
    static final int MAX_MAP_BYTES = MAX_RECORD_BYTES;
    static final long MAX_MAP_STEPS = 64L << 20;
    /** The first 4 bytes of a TABLE frame. */
    static final String MAGIC = "KTB1";
    /** The parts a TABLE answer streams, in this order: the table, then the probe map. */
    static final String PART_TABLE = "table";
    static final String PART_PROBES = "probes";

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

    /** The env var, then the system property, that set the free heap a table needs, in MiB; 0 turns it off. */
    static final String MIN_HEAP_ENV = "KEPLOY_COVERAGE_TABLE_MIN_HEAP_MB";
    static final String MIN_HEAP_PROPERTY = "keploy.coverage.table.min.heap.mb";
    /** The free heap a table needs when neither is set: this many MiB, plus {@link #MIN_HEAP_KB_PER_CLASS}. */
    static final long MIN_HEAP_BASE_MB = 64;
    /** ...and this many KiB per class the index holds (113 MiB at 50,000 classes). */
    static final long MIN_HEAP_KB_PER_CLASS = 1;
    /** The reason a TABLE ask is declined when the heap is short: Keploy marks the run not_measured with it. */
    static final String LOW_HEAP = "low_heap";

    /**
     * answer is the control socket's reply to Keploy's TABLE command. When the heap has less free than the
     * table needs ({@link #lowHeap}) the analysis is skipped and the reply is {@code IGNORED proto=2
     * reason=low_heap heap_free_mb=F heap_needed_mb=N}, with a WARNING in the app's log; else it is
     * {@link #request}'s ACK or IGNORED. The heap is read once, here, when Keploy asks: a send that runs after
     * another one was checked when it was asked. The bound takes loaded, the class count of the index when it
     * is read already (the app's start or its first START read it, before Keploy asks for a table); when it is
     * not read yet (loaded is -1: no START read it, the index was not warmed up and the JVM started the SDK
     * from agentmain or start(), or with a JaCoCo of its own), the bound is the base alone, as the count is
     * not known without reading the class files. The answer reads no class file and waits on no lock: classes
     * is read on the send's thread. It never blocks.
     */
    static String answer(IntSupplier loaded, Supplier<List<ClassFile>> classes, File dataSocket) {
        return answer(Heap.read(), loaded.getAsInt(), classes, dataSocket);
    }

    /** answer with the heap read and the index's class count (-1: not read yet) given. */
    static String answer(Heap heap, int loaded, Supplier<List<ClassFile>> classes, File dataSocket) {
        String low = lowHeap(heap, minHeapMb(System.getenv(MIN_HEAP_ENV), System.getProperty(MIN_HEAP_PROPERTY),
                loaded));
        if (low != null) {
            return KeployDedupAgent.UNIX_REPLY_IGNORED + " " + low;
        }
        return request(classes, dataSocket) ? KeployDedupAgent.UNIX_REPLY_TAKEN
                : KeployDedupAgent.UNIX_REPLY_IGNORED;
    }

    /**
     * lowHeap is null when heap has at least minMb MiB free, else what the reply names after IGNORED proto=2
     * ({@code reason=low_heap heap_free_mb=F heap_needed_mb=N}), logged at WARNING.
     */
    static String lowHeap(Heap heap, long minMb) {
        long free = heap.freeMb();
        if (free >= minMb) {
            return null;
        }
        log(Level.WARNING, "Keploy coverage: the build table is not built, as the heap is short: " + free
                + " MiB free of the " + (heap.max >> 20) + " MiB heap cap the JVM reports (in use " + (heap.used >> 20)
                + " MiB" + (heap.afterGc ? " after the last collection" : " now") + "), and it needs "
                + minMb + " MiB. Keploy marks this run's coverage not measured (" + LOW_HEAP + "). Give the JVM"
                + " more heap (-Xmx), or set " + MIN_HEAP_ENV + " (0 turns the check off)", null);
        return "reason=" + LOW_HEAP + " heap_free_mb=" + free + " heap_needed_mb=" + minMb;
    }

    /**
     * minHeapMb is the free heap a table of the given number of classes needs, in MiB: env, else property (as
     * the agent reads its settings), else {@link #defaultMinHeapMb}. A value that is not a whole number of 0 or
     * more is logged and the default holds.
     */
    static long minHeapMb(String env, String property, int classes) {
        String v = env == null || env.trim().isEmpty() ? property : env;
        if (v == null || v.trim().isEmpty()) {
            return defaultMinHeapMb(classes);
        }
        try {
            long mb = Long.parseLong(v.trim());
            if (mb >= 0) {
                return mb;
            }
        } catch (NumberFormatException ignored) {
            // logged below
        }
        log(Level.WARNING, "Keploy coverage: " + MIN_HEAP_ENV + " (or -D" + MIN_HEAP_PROPERTY + ") is '" + v.trim()
                + "', not a whole number of MiB (0 or more); the build table needs " + defaultMinHeapMb(classes)
                + " MiB of free heap", null);
        return defaultMinHeapMb(classes);
    }

    /** defaultMinHeapMb is {@link #MIN_HEAP_BASE_MB} MiB plus 1 KiB per class, rounded up to a whole MiB. */
    static long defaultMinHeapMb(int classes) {
        return MIN_HEAP_BASE_MB + (Math.max(0, classes) * MIN_HEAP_KB_PER_CLASS + 1023) / 1024;
    }

    /**
     * Heap is what the low-heap check reads: the heap cap and the heap in use. In use is what the JVM's newest
     * collection left (the live set and any garbage it did not reach); with no collection seen, or a JVM that
     * does not report one, it is the heap in use now, garbage included. The check never runs a collection: a
     * System.gc() would pause the app.
     */
    static final class Heap {
        /**
         * The heap cap in bytes, as Runtime.maxMemory() reports it (a little under -Xmx with the Serial and
         * Parallel collectors, which leave out a survivor space); Long.MAX_VALUE when the JVM has none.
         */
        final long max;
        final long used;
        /** afterGc is true when used is what the newest collection left, false when it is the heap in use now. */
        final boolean afterGc;

        Heap(long max, long used, boolean afterGc) {
            this.max = max;
            this.used = used;
            this.afterGc = afterGc;
        }

        long freeMb() {
            return max == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0, max - used) >> 20;
        }

        static Heap read() {
            Runtime rt = Runtime.getRuntime();
            long now = rt.totalMemory() - rt.freeMemory();
            long after;
            try {
                after = LastCollection.heapUsedAfter();
            } catch (Throwable t) {
                // No com.sun.management (a trimmed runtime) or a JMX error: the heap as it is now.
                after = -1;
            }
            return of(rt.maxMemory(), now, after);
        }

        /**
         * of is the heap with cap max, now bytes in use now and after bytes in use when the newest collection
         * ended (-1 with none seen): the smaller of the two. More in use now is garbage and objects made since
         * that collection; less means a collection the JVM did not report ran since, so now is the newer figure.
         */
        static Heap of(long max, long now, long after) {
            return after < 0 || after > now ? new Heap(max, now, false) : new Heap(max, after, true);
        }

        /**
         * usedAfterNewest is the heap in use when the newest of ends ended: its use after it, summed over
         * heapPools only (the JVM also reports Metaspace and its code cache, which are not the heap); -1 when
         * ends is empty.
         */
        static long usedAfterNewest(List<GcEnd> ends, Set<String> heapPools) {
            GcEnd newest = null;
            for (GcEnd e : ends) {
                if (newest == null || e.endTime > newest.endTime) {
                    newest = e;
                }
            }
            if (newest == null) {
                return -1;
            }
            long used = 0;
            for (Map.Entry<String, Long> p : newest.usedAfter.entrySet()) {
                if (heapPools.contains(p.getKey())) {
                    used += p.getValue();
                }
            }
            return used;
        }
    }

    /** GcEnd is one collector's last collection: when it ended (ms since JVM start) and each pool's use after it. */
    static final class GcEnd {
        final long endTime;
        final Map<String, Long> usedAfter;

        GcEnd(long endTime, Map<String, Long> usedAfter) {
            this.endTime = endTime;
            this.usedAfter = usedAfter;
        }
    }

    /** LastCollection reads com.sun.management, in a class of its own so a runtime without it fails only here. */
    static final class LastCollection {
        /** heapUsedAfter is the heap in use when the JVM's newest collection ended, or -1 with none seen. */
        static long heapUsedAfter() {
            Set<String> heapPools = new HashSet<>();
            for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
                if (p.getType() == MemoryType.HEAP) {
                    heapPools.add(p.getName());
                }
            }
            List<GcEnd> ends = new ArrayList<>();
            for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (g instanceof com.sun.management.GarbageCollectorMXBean) {
                    com.sun.management.GcInfo info =
                            ((com.sun.management.GarbageCollectorMXBean) g).getLastGcInfo();
                    if (info != null) {
                        Map<String, Long> usedAfter = new HashMap<>();
                        for (Map.Entry<String, MemoryUsage> e : info.getMemoryUsageAfterGc().entrySet()) {
                            usedAfter.put(e.getKey(), e.getValue().getUsed());
                        }
                        ends.add(new GcEnd(info.getEndTime(), usedAfter));
                    }
                }
            }
            return Heap.usedAfterNewest(ends, heapPools);
        }
    }

    /**
     * request starts the table's send: true (ACK) once it is started on its own thread, or, when a send runs
     * already, once this request is set to run after it. It never blocks.
     * When no thread can start it answers false (IGNORED) and does not throw; the agent's command hook catches
     * anything else. The classes are read on that thread.
     */
    static boolean request(Supplier<List<ClassFile>> classes, File dataSocket) {
        return JVM.request(() -> send(classes, new SocketSender(dataSocket)));
    }

    /**
     * requests is how many times the JVM's table send was asked for, started or not: only TABLE asks the heap
     * check let through.
     */
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
     * Sends runs one send at a time. Requests made while one runs are run once after it, as the newest of them:
     * each request names its own index and data socket, and only the newest is the agent's now (a stop() and
     * start() during a send makes a new index and socket). A send that throws is logged, and the request made
     * while it ran still runs. It assumes one caller at a time (the control server handles one command at a
     * time): a thread that fails to start clears {@code pending}, which would drop a request that raced in from
     * a second caller.
     */
    static final class Sends {
        private final ThreadFactory threads;
        private boolean running;
        /** The newest request made while a send ran, to run after it; null for none. */
        private Runnable pending;
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
                    pending = send;
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
                    pending = null;
                }
                log(Level.WARNING, "Keploy coverage: the build table's thread did not start", t);
                return false;
            }
        }

        private void loop(Runnable first) {
            Runnable send = first;
            while (true) {
                try {
                    send.run();
                } catch (Throwable t) {
                    // A failed send does not drop the request made while it ran: that one names the newest
                    // index and socket.
                    log(Level.WARNING, "Keploy coverage: the build table was not sent", t);
                }
                synchronized (this) {
                    if (pending == null) {
                        running = false;
                        return;
                    }
                    send = pending;
                    pending = null;
                }
            }
        }
    }

    /**
     * send analyzes the classes and streams the table through sender, then the probe map, which waits in a
     * temp file in java.io.tmpdir while the table streams ({@link Spill}). It logs what it sent, or why it
     * stopped, and never throws.
     */
    static void send(Supplier<List<ClassFile>> classes, FrameSender sender) {
        send(classes, sender, new File(System.getProperty("java.io.tmpdir", "/tmp")));
    }

    /** send with the probe map's temp file made in spillDir. */
    static void send(Supplier<List<ClassFile>> classes, FrameSender sender, File spillDir) {
        try {
            long cpu0 = threadCpuNanos();
            long t0 = System.nanoTime();
            FrameWriter frames = new FrameWriter(sender, PART_TABLE, MAX_FRAME_BYTES);
            FrameWriter maps = new FrameWriter(sender, PART_PROBES, MAX_FRAME_BYTES);
            Summary s;
            Pacer pacer = Pacer.jvm();
            try (Spill spill = Spill.open(spillDir)) {
                s = write(classes.get(), frames, spill, MAX_RECORD_BYTES, MAX_MAP_BYTES, MAX_MAP_STEPS, pacer);
                frames.finish(s.build, s.records);
                spill.sendTo(maps, s.build);
            }
            maps.finish(s.build, s.probeRecords);
            StringBuilder m = new StringBuilder("Keploy coverage: build table ");
            m.append(s.build.isEmpty() ? "(no app classes)" : s.build).append(" sent (").append(s.records)
                    .append(" classes, ").append(s.notMeasured).append(" not measured");
            if (s.notMapped > 0) {
                // Two copies of a class that differ, or JaCoCo's own steps disagreeing with its public result.
                m.append(" (").append(s.notMapped).append(" of them as their probes could not be mapped: two"
                        + " different class files of one name, or a Keploy bug; the FINE log names each)");
            }
            if (s.tooLarge > 0) {
                m.append(", ").append(s.tooLarge).append(" not measured as too large (").append(RECORD_TOO_LARGE)
                        .append(": a record over ").append(MAX_RECORD_BYTES >> 20).append(" MiB, or a probe map over ")
                        .append(MAX_MAP_BYTES >> 20).append(" MiB or ").append(MAX_MAP_STEPS >> 20)
                        .append(" Mi steps; the FINE log names each)");
            }
            if (s.noLines > 0) {
                m.append(", ").append(s.noLines)
                        .append(" class files without line numbers, so without lines: compile them with -g");
            }
            if (s.unnamed > 0) {
                m.append(", ").append(s.unnamed).append(" index entries without a class name left out");
            }
            m.append("; probe map of ").append(s.probeRecords).append(" class files; ").append(frames.seq + maps.seq)
                    .append(" frames, ").append((System.nanoTime() - t0) / 1_000_000)
                    .append(" ms, cpu ").append((threadCpuNanos() - cpu0) / 1_000_000).append(" ms, paced ")
                    .append(pacer.waitedNanos() / 1_000_000).append(" ms)");
            log(Level.INFO, m.toString(), null);
        } catch (IOException e) {
            // An interrupt ends the send: the Pacer's, or a temp file's channel that it closed. Name it, and not
            // the channel's error, which would point at java.io.tmpdir.
            String why = Thread.currentThread().isInterrupted() ? "its thread was interrupted" : e.getMessage();
            log(Level.WARNING, "Keploy coverage: the build table was not sent: " + why
                    + "; it goes again when Keploy asks for it again", null);
        } catch (Throwable t) {
            // Never let the table take the app down: Keploy names the coverage it did not get.
            log(Level.WARNING, "Keploy coverage: the build table was not sent", t);
        }
    }

    private static long threadCpuNanos() {
        try {
            return java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Pacer paces the table's thread by the JVM's collections, so its garbage does not make the collector grow
     * the app's heap. The analysis allocates fast (about 80-90 KiB a class, over 1 GB/s at full speed, nearly
     * all of it short-lived), and G1 and Parallel commit more heap when collecting takes more than their goal
     * of the time (Parallel 1%; G1 a few percent, as low as 1% while it has committed a small part of its cap).
     * So after each unit the thread owes {@link #WAIT_PER_GC} times the time the collections took since the
     * send began (the app's and its own) less the time since, and waits it out: collections then take at most
     * 1/200 of the send's time, and a JVM that does not collect is not paced at all. The time read is every
     * collector bean's: on a collector whose beans count concurrent cycles (ZGC, Shenandoah) the send is paced
     * at its full allowance.
     *
     * <p>The waits are bounded. They are drawn from an allowance of {@link #ALLOWANCE_NANOS_PER_CLASS} per
     * class file analyzed, of which at most {@link #MAX_BANK_NANOS} is saved up: no one wait is over a second,
     * the waits over any k class files add up to at most a second plus 0.8 k ms, so frames keep coming (Keploy's
     * wait for the table is a bound on the time since its last frame), and all of them to at most 0.8 ms a
     * class file (40 s at 50,000). And no wait is taken that would end the table past {@link #deadlineNanos}
     * (50 s at 50,000 class files), reckoned as the time so far plus the rest of its class files at the rate
     * the work went so far: on a slow or busy CPU the pacing gives way to the table's 60 s bound, with 10 s
     * left for the probe map's frames and the estimate's error. A clock or collector bean that fails ends the
     * pacing, and the rest is sent unpaced. An interrupt ends the send: unitDone throws, and the interrupt is
     * kept for the thread.
     */
    static final class Pacer {
        /** Each millisecond of collection asks for this many of the send's: at most 0.5% of it collecting. */
        static final long WAIT_PER_GC = 200;
        /** The wait each analyzed class file adds to what the thread may wait: the table's time bound. */
        static final long ALLOWANCE_NANOS_PER_CLASS = 800_000L;
        /** The most of the allowance kept for a later wait: the longest wait after a quiet stretch. */
        static final long MAX_BANK_NANOS = 1_000_000_000L;
        /** Waits shorter than this are put off to the next unit, so the thread does not sleep per class. */
        static final long MIN_WAIT_NANOS = 10_000_000L;
        /** The pacing's time bound up to {@link #DEADLINE_CLASS_FILES} class files: see deadlineNanos. */
        static final long DEADLINE_NANOS = 50_000_000_000L;
        static final long DEADLINE_CLASS_FILES = 50_000;
        // The most the thread may owe (an hour), so the sum cannot overflow; the allowance bounds it anyway.
        private static final long MAX_DEBT_NANOS = 3_600_000_000_000L;

        /** The clock, the collections' time and the sleep a Pacer reads; the JVM's, or a row's. */
        interface Clock {
            long nanoTime();

            /** The collectors' total time in ms since the JVM started. */
            long gcMillis();

            void sleep(long nanos) throws InterruptedException;
        }

        private final Clock clock;
        private boolean off;
        private long start;
        private long total;
        private long done;
        private long last;
        private long gc;
        private long debt;
        private long bank;
        private long waited;

        Pacer(Clock clock) {
            this.clock = clock;
            try {
                last = clock.nanoTime();
                start = last;
                gc = clock.gcMillis();
            } catch (RuntimeException e) {
                stop("its clock failed", e);
            }
        }

        /** off is a Pacer that never waits: write's, outside a send. */
        static Pacer off() {
            Pacer p = new Pacer(JvmClock.NONE);
            p.off = true;
            return p;
        }

        /** jvm paces by this JVM's collectors; off when the runtime has no collector beans to read. */
        static Pacer jvm() {
            List<GarbageCollectorMXBean> beans;
            try {
                beans = ManagementFactory.getGarbageCollectorMXBeans();
            } catch (RuntimeException | LinkageError e) {
                log(Level.FINE, "Keploy coverage: the build table is sent unpaced: no collector beans", e);
                return off();
            }
            return new Pacer(new JvmClock(beans));
        }

        /**
         * deadlineNanos is the most the pacing lets a table of classFiles take: 50 s, and 1 ms a class file past
         * 50,000 (the table's bound, 60 s at 50,000, scales the same way).
         */
        static long deadlineNanos(long classFiles) {
            return Math.max(DEADLINE_NANOS, DEADLINE_NANOS / DEADLINE_CLASS_FILES * classFiles);
        }

        /** begin says how many class files the send analyzes, for its deadline; not called, the deadline is 50 s. */
        void begin(long classFiles) {
            total = Math.max(0, classFiles);
        }

        /** waitedNanos is how long the thread waited, in all. */
        long waitedNanos() {
            return waited;
        }

        /**
         * unitDone is called after each unit, whose classFiles were analyzed: it waits what is owed and may be.
         * It throws on an interrupt, which it keeps for the thread: an interrupt ends the send.
         */
        void unitDone(int classFiles) throws InterruptedIOException {
            if (Thread.currentThread().isInterrupted()) {
                throw interrupted();
            }
            if (off) {
                return;
            }
            try {
                long now = clock.nanoTime();
                long g = clock.gcMillis();
                if (g > gc) {
                    long perMs = WAIT_PER_GC * 1_000_000L;
                    debt = Math.min(MAX_DEBT_NANOS, debt + Math.min(g - gc, MAX_DEBT_NANOS / perMs) * perMs);
                    gc = g;
                }
                debt = Math.max(0, debt - Math.max(0, now - last));
                int files = Math.max(0, classFiles);
                bank = Math.min(MAX_BANK_NANOS, bank + ALLOWANCE_NANOS_PER_CLASS * files);
                done += files;
                last = now;
                long wait = Math.min(Math.min(debt, bank), room(now));
                if (wait < MIN_WAIT_NANOS) {
                    return;
                }
                try {
                    clock.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw interrupted();
                }
                long after = clock.nanoTime();
                long took = Math.max(0, after - now);
                // What the sleep overran comes off the bank too, so the bound holds for the time slept; and at
                // least the wait asked for, so it holds on a clock that does not move across a sleep.
                long charged = Math.max(took, wait);
                bank -= charged;
                debt = Math.max(0, debt - charged);
                waited += took;
                last = after;
            } catch (RuntimeException e) {
                stop("its clock failed", e);
            }
        }

        /** room is how long the thread may wait at now and still end the table by its deadline. */
        private long room(long now) {
            long elapsed = Math.max(0, now - start);
            double rest = 0;
            if (done > 0 && total > done) {
                rest = (double) Math.max(0, elapsed - waited) / done * (total - done);
            }
            return (long) Math.max(-1.0, deadlineNanos(total) - elapsed - rest);
        }

        static InterruptedIOException interrupted() {
            return new InterruptedIOException("the build table's thread was interrupted");
        }

        private void stop(String why, Throwable thrown) {
            off = true;
            log(Level.FINE, "Keploy coverage: the build table is sent unpaced: " + why, thrown);
        }

        /** JvmClock reads System.nanoTime and the JVM's collector beans, and sleeps the thread. */
        static final class JvmClock implements Clock {
            static final JvmClock NONE = new JvmClock(Collections.<GarbageCollectorMXBean>emptyList());
            private final List<GarbageCollectorMXBean> beans;

            JvmClock(List<GarbageCollectorMXBean> beans) {
                this.beans = beans;
            }

            @Override
            public long nanoTime() {
                return System.nanoTime();
            }

            @Override
            public long gcMillis() {
                long sum = 0;
                for (GarbageCollectorMXBean b : beans) {
                    // -1: this collector does not report its time.
                    long t = b.getCollectionTime();
                    if (t > 0) {
                        sum += t;
                    }
                }
                return sum;
            }

            @Override
            public void sleep(long nanos) throws InterruptedException {
                Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
            }
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
        /** How many of the not measured records JaCoCo analyzed but whose probes could not be mapped. */
        final int notMapped;
        /** How many of the not measured records are record_too_large: the record or its probe map too large. */
        final int tooLarge;
        /** How many probe-map records went to the probes sink: one per class name of each measured unit. */
        final int probeRecords;

        Summary(String build, int records, int notMeasured, int noLines, int unnamed, int notMapped, int tooLarge,
                int probeRecords) {
            this.build = build;
            this.records = records;
            this.notMeasured = notMeasured;
            this.noLines = noLines;
            this.unnamed = unnamed;
            this.notMapped = notMapped;
            this.tooLarge = tooLarge;
            this.probeRecords = probeRecords;
        }
    }

    /** Where write puts each record (UTF-8 JSON, no newline), in the table's order. */
    interface RecordSink {
        void record(String build, byte[] json) throws IOException;
    }

    /** A sink that drops what it is given: write's probe map when only the table is wanted. */
    static final RecordSink DROP = (build, json) -> { };

    /**
     * write analyzes the classes and hands each table record to sink, in the contract's order, and each
     * measured class's probe-map records to probes as they are made, in the same order: it keeps neither. With
     * no class bytes the build id is "" and there is no record.
     */
    static Summary write(List<ClassFile> classes, RecordSink sink, RecordSink probes) throws IOException {
        return write(classes, sink, probes, MAX_RECORD_BYTES);
    }

    /** write with the probe map dropped. */
    static Summary write(List<ClassFile> classes, RecordSink sink) throws IOException {
        return write(classes, sink, DROP, MAX_RECORD_BYTES);
    }

    static Summary write(List<ClassFile> classes, RecordSink sink, int maxRecord) throws IOException {
        return write(classes, sink, DROP, maxRecord);
    }

    static Summary write(List<ClassFile> classes, RecordSink sink, RecordSink probes, int maxRecord)
            throws IOException {
        return write(classes, sink, probes, maxRecord, MAX_MAP_BYTES, MAX_MAP_STEPS);
    }

    /** write with the bounds of a unit's table record (maxRecord) and probe map (maxMap bytes, maxSteps). */
    static Summary write(List<ClassFile> classes, RecordSink sink, RecordSink probes, int maxRecord, int maxMap,
            long maxSteps) throws IOException {
        return write(classes, sink, probes, maxRecord, maxMap, maxSteps, Pacer.off());
    }

    /** write with the bounds, its thread paced by pacer after each unit. */
    static Summary write(List<ClassFile> classes, RecordSink sink, RecordSink probes, int maxRecord, int maxMap,
            long maxSteps, Pacer pacer) throws IOException {
        Grouped g = group(classes);
        if (g.build.isEmpty()) {
            return new Summary("", 0, 0, 0, g.unnamed, 0, 0, 0);
        }
        String build = g.build;
        List<Unit> units = g.units;
        int notMeasured = 0;
        int noLines = 0;
        int notMapped = 0;
        int tooLarge = 0;
        int probeRecords = 0;
        long classFiles = 0;
        for (Unit u : units) {
            classFiles += u.files.size();
        }
        pacer.begin(classFiles);
        for (int i = 0; i < units.size(); i++) {
            // A unit written is dropped: what stays is the class list (names, ids), not the table.
            Unit u = units.set(i, null);
            byte[] rec = u.record(new MapBudget(maxMap, maxSteps));
            if (rec.length + 1 > maxRecord) {
                rec = u.notMeasured(RECORD_TOO_LARGE);
                u.failed = true;
                u.tooLarge = true;
            }
            if (u.failed) {
                notMeasured++;
                if (u.notMapped) {
                    notMapped++;
                }
                if (u.tooLarge) {
                    tooLarge++;
                }
            } else {
                noLines += u.noLines;
                for (byte[] p : u.probes) {
                    probes.record(build, p);
                    probeRecords++;
                }
            }
            sink.record(build, rec);
            pacer.unitDone(u.files.size());
        }
        return new Summary(build, units.size(), notMeasured, noLines, g.unnamed, notMapped, tooLarge,
                probeRecords);
    }

    /** What group makes of the index's classes: the build id ("" with none), the units in table order. */
    private static final class Grouped {
        final String build;
        final List<Unit> units;
        final int unnamed;

        Grouped(String build, List<Unit> units, int unnamed) {
            this.build = build;
            this.units = units;
            this.unnamed = unnamed;
        }
    }

    /** group folds the classes into units by name, in table order; its lookups end with it. */
    private static Grouped group(List<ClassFile> classes) {
        Map<String, Unit> byId = new HashMap<>();
        Set<String> seen = new HashSet<>();
        long[] ids = new long[classes.size()];
        int n = 0;
        int unnamed = 0;
        for (ClassFile in : classes) {
            if (in == null) {
                continue;
            }
            // Every indexed class is in the build id, named or not: the INDEX record's, by the same function.
            long id = IndexReport.classId(in.bytes);
            ids[n++] = id;
            ClassFile f = new ClassFile(vmName(in), in.bytes);
            Unit u = f.name == null ? null : Unit.of(f.name);
            if (u == null || u.name.isEmpty()) {
                // No unit has an empty name (Go refuses the whole table for one): a file named "a/.class".
                unnamed++;
                continue;
            }
            if (!seen.add(f.name + "\n" + id)) {
                // The same class file again (the index found it under two paths): it is one class file, and
                // the build id and the unit's hash count its id once either way.
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
            return new Grouped("", Collections.<Unit>emptyList(), unnamed);
        }
        List<Unit> units = new ArrayList<>(byId.values());
        Collections.sort(units, UNIT_ORDER);
        return new Grouped(IndexReport.buildId(Arrays.copyOf(ids, n)), units, unnamed);
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
        // Set when JaCoCo analyzed the unit but its probes could not be mapped (so failed is set too).
        boolean notMapped;
        // Set when the unit's record or its probe map is past its bound: record_too_large (failed is set too).
        boolean tooLarge;
        // Class files with code but no line numbers: their methods have no leaf, so they are left out.
        int noLines;
        // The probe map's records for the unit's class files, in file order; set when record() succeeds.
        List<byte[]> probes = Collections.emptyList();

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
            return IndexReport.classIdsSum(ids);
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

        /**
         * record analyzes each of the unit's class files once, all of them first, in name order, then makes
         * the unit's records. The first analysis that fails decides: analyzer_failed when JaCoCo refuses the
         * class file or its probes cannot be mapped, record_too_large when its probe map is past the budget.
         * Only then are the probe-map records made and each charged in place of its text: a record past the
         * budget makes the unit record_too_large, and a second class file of one name that maps its probes
         * otherwise makes it analyzer_failed. A unit with two failures of different kinds gets the first one's
         * reason in that order; either way it is not measured and has no probe record.
         */
        byte[] record(MapBudget budget) {
            Map<String, ClassInfo> infos = new HashMap<>();
            List<Method> methods = new ArrayList<>();
            Map<String, Method> byId = new HashMap<>();
            List<UnitFile> sorted = sortedFiles();
            List<Ranges> ranges = new ArrayList<>(sorted.size());
            UnitFile at = null;
            try {
                String lastName = null;
                for (UnitFile f : sorted) {
                    at = f;
                    ClassInfo info = ClassInfo.read(f.file.bytes);
                    infos.put(f.file.name, info);
                    Ranges r = ProbeMap.analyze(this, f, info, methods, byId, budget);
                    if (f.file.name.equals(lastName)) {
                        // A second class file of this name: its record is the one before it or none (below),
                        // so its text is not one the records take.
                        budget.forget(r.counted());
                    }
                    ranges.add(r);
                    lastName = f.file.name;
                }
            } catch (TooLarge e) {
                log(Level.FINE, "Keploy coverage: the probe map of " + path() + " is past its bound: " + e.getMessage(),
                        null);
                failed = true;
                tooLarge = true;
                return notMeasured(RECORD_TOO_LARGE);
            } catch (KeployBranchCoverage.NotMapped e) {
                // A class whose probes cannot be mapped exactly is not measured: its hits could not be read.
                log(Level.FINE, "Keploy coverage: the probes of " + path() + " could not be mapped", e);
                failed = true;
                notMapped = true;
                return notMeasured("analyzer_failed");
            } catch (Exception | LinkageError e) {
                log(Level.FINE, "Keploy coverage: JaCoCo could not analyze " + at.file.name, e);
                failed = true;
                return notMeasured("analyzer_failed");
            }
            Collections.sort(methods, (a, b) -> {
                int c = Integer.compare(a.line, b.line);
                return c != 0 ? c : compareCodePoints(a.id, b.id);
            });
            int base = 0;
            for (Method m : methods) {
                m.base = base;
                base += m.leaves.length / 3;
            }
            List<byte[]> maps = new ArrayList<>();
            try {
                String lastName = null;
                byte[] last = null;
                for (int i = 0; i < sorted.size(); i++) {
                    UnitFile f = sorted.get(i);
                    Ranges r = ranges.get(i);
                    // Only one class file's ranges stay alive while the records are made.
                    ranges.set(i, null);
                    byte[] p = ProbeMap.record(id, f, r);
                    if (f.file.name.equals(lastName)) {
                        // Two class files of one name whose bytes differ (the index found both). Keploy finds a
                        // class's map by its name, as a test's hits name it, so they share one record: exact
                        // only when a hit of either one covers the same leaves.
                        if (!ProbeMap.sameProbes(p, last)) {
                            throw new IllegalStateException(f.file.name + ": two class files of this name map"
                                    + " their probes differently");
                        }
                        continue;
                    }
                    // The unit's records, newlines included, within the budget, in place of their ranges' text
                    // counted as the analysis wrote it.
                    budget.take(p.length + 1, r.counted());
                    maps.add(p);
                    lastName = f.file.name;
                    last = p;
                }
            } catch (TooLarge e) {
                log(Level.FINE, "Keploy coverage: the probe map of " + path() + " is past its bound: " + e.getMessage(),
                        null);
                failed = true;
                tooLarge = true;
                return notMeasured(RECORD_TOO_LARGE);
            } catch (Exception | LinkageError e) {
                log(Level.FINE, "Keploy coverage: the probes of " + path() + " could not be mapped", e);
                failed = true;
                notMapped = true;
                return notMeasured("analyzer_failed");
            }
            probes = maps;
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
        // The unit-relative index of the method's first leaf (the unit's leaves in record order).
        int base;
        // The lines of the method's code leaves, ascending, and each one's method-relative leaf index.
        private final int[] codeLines;
        private final int[] codeLeaves;

        private Method(String id, String display, int line, int[] leaves) {
            this.id = id;
            this.display = display;
            this.line = line;
            this.leaves = leaves;
            int n = 0;
            for (int i = 0; i < leaves.length; i += 3) {
                if (leaves[i + 2] == 0) {
                    n++;
                }
            }
            codeLines = new int[n];
            codeLeaves = new int[n];
            n = 0;
            for (int i = 0; i < leaves.length; i += 3) {
                if (leaves[i + 2] == 0) {
                    codeLines[n] = leaves[i];
                    codeLeaves[n++] = i / 3;
                }
            }
        }

        /**
         * of makes the method's record from JaCoCo's per-method lines: one code leaf per line with
         * instructions (n = their count), then one branch leaf per branch edge on that line. Lines below 1
         * have no leaf (javac never writes line 0; some bytecode tools do), and the method's line is its first
         * line with a leaf. A method with no such line (a class built without line numbers) has no leaf to
         * show and is left out, as the contract's method count leaves out a method with no code leaf.
         */
        static Method of(String vmClass, String nested, String unitName, IMethodCoverage mc, ClassInfo info) {
            int first = Math.max(1, mc.getFirstLine());
            int last = mc.getLastLine();
            if (last < first) {
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
            if (k == 0) {
                return null;
            }
            boolean varargs = info != null && info.varargs.contains(mc.getName() + mc.getDesc());
            return new Method("m:" + vmClass + "#" + mc.getName() + mc.getDesc(),
                    Names.method(unitName, nested, mc.getName(), mc.getDesc(), varargs), buf[0],
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

        /** codeLeaf is the method-relative index of line l's code leaf, or -1 when the method has none there. */
        int codeLeaf(int l) {
            int at = Arrays.binarySearch(codeLines, l);
            return at < 0 ? -1 : codeLeaves[at];
        }

        /**
         * check throws unless the analysis has the method's leaves: on each line, the code leaf's instruction
         * count and the line's branch edges (lines below 1 have no leaf).
         */
        void check(KeployBranchCoverage.Method a) {
            Map<Integer, int[]> lines = new HashMap<>(); // line -> instructions, branches
            for (int k = 0; k < a.lines.length; k++) {
                if (a.lines[k] < 1) {
                    continue;
                }
                int[] s = lines.get(a.lines[k]);
                if (s == null) {
                    s = new int[2];
                    lines.put(a.lines[k], s);
                }
                s[0]++;
                s[1] += a.branches[k].length;
            }
            Map<Integer, int[]> mine = new HashMap<>();
            for (int i = 0; i < leaves.length; i += 3) {
                int[] s = mine.get(leaves[i]);
                if (s == null) {
                    s = new int[2];
                    mine.put(leaves[i], s);
                }
                if (leaves[i + 2] == 0) {
                    s[0] += leaves[i + 1];
                } else {
                    s[1]++;
                }
            }
            if (lines.size() != mine.size()) {
                throw new IllegalStateException(id + ": " + lines.size() + " lines in the analysis, " + mine.size()
                        + " in the table");
            }
            for (Map.Entry<Integer, int[]> e : lines.entrySet()) {
                int[] t = mine.get(e.getKey());
                if (t == null || !Arrays.equals(t, e.getValue())) {
                    throw new IllegalStateException(id + ": line " + e.getKey() + " differs from the table");
                }
            }
        }

        // Per counted instruction of the class file's analysis of this method (set by prepare, until release):
        // the method-relative index of its line's code leaf, and of its first branch edge (-1: a line below 1,
        // no leaf). A probe's ranges add the method's base once the unit's methods are ordered.
        private int[] codeAt;
        private int[] edgeAt;
        private KeployBranchCoverage.Method prepared;

        /**
         * prepare checks the analysis has the method's leaves ({@link #check}) and notes, per counted
         * instruction, its leaves: its line's code leaf, and its line's edges after the code leaf in the
         * order of the instructions and of each instruction's branches.
         */
        void prepare(KeployBranchCoverage.Method a) {
            check(a);
            codeAt = new int[a.lines.length];
            edgeAt = new int[a.lines.length];
            Map<Integer, Integer> edges = new HashMap<>(); // line -> edges met so far
            for (int k = 0; k < a.lines.length; k++) {
                int l = a.lines[k];
                if (l < 1) {
                    codeAt[k] = -1;
                    edgeAt[k] = -1;
                    continue;
                }
                int leaf = codeLeaf(l);
                if (leaf < 0) {
                    throw new IllegalStateException(id + ": line " + l + " has no code leaf");
                }
                Integer met = edges.get(l);
                int e = met == null ? 0 : met;
                codeAt[k] = leaf;
                edgeAt[k] = leaf + 1 + e;
                edges.put(l, e + a.branches[k].length);
            }
            prepared = a;
        }

        /** release forgets the analysis prepare noted, once its probes are mapped. */
        void release() {
            codeAt = null;
            edgeAt = null;
            prepared = null;
        }

        /**
         * hits sets in out the method-relative leaf indices a probe covers: a line's code leaf when one of its
         * instructions is covered, and the leaf of each covered branch edge.
         */
        void hits(KeployBranchCoverage.Method a, KeployBranchCoverage.Hits h, BitSet out) {
            if (prepared != a) {
                throw new IllegalStateException(id + ": probes of an analysis not prepared");
            }
            for (int j = 0; j < h.count; j++) {
                int k = h.at[j];
                if (codeAt[k] < 0) {
                    continue;
                }
                out.set(codeAt[k]);
                boolean[] b = h.branches[k];
                for (int t = 0; t < b.length; t++) {
                    if (b[t]) {
                        out.set(edgeAt[k] + t);
                    }
                }
            }
        }
    }

    /**
     * ProbeMap maps each JaCoCo probe of a class file to the leaves a hit of that probe covers, from JaCoCo's
     * own analysis (KeployBranchCoverage): a line's code leaf when an instruction of it is covered, and each
     * covered branch edge's leaf. A probe covers more than its own line: JaCoCo marks every instruction on the
     * path that ends at the probe, so a test's leaves are the union of its probes' leaves.
     *
     * <p>The class file is analyzed once, for both its table methods and its map: each probe's leaves are read
     * off that analysis (KeployBranchCoverage walks back from the probe over the edges JaCoCo's builder wired),
     * so the map costs the class's probes' walks and their ranges, with no object per probe. The
     * walks and the text are quadratic in a chain of early returns, so a unit's map has a budget
     * ({@link MapBudget}): past it the unit is record_too_large.
     */
    static final class ProbeMap {
        private ProbeMap() {
        }

        /**
         * record is the class file's probe-map record: {"class":vmName,"id":crc64,"unit":unitId,"probes":[...]},
         * with probes as {@link #analyze} made them, written once into a buffer of its size.
         */
        static byte[] record(String unitId, UnitFile f, Ranges probes) {
            StringBuilder sb = new StringBuilder(64 + f.file.name.length() + unitId.length());
            sb.append("{\"class\":");
            Json.string(sb, f.file.name);
            sb.append(",\"id\":\"").append(String.format("%016x", f.classId)).append("\",\"unit\":");
            Json.string(sb, unitId);
            sb.append(",\"probes\":");
            byte[] head = sb.toString().getBytes(StandardCharsets.UTF_8);
            byte[] rec = new byte[head.length + probes.jsonLength() + 1];
            System.arraycopy(head, 0, rec, 0, head.length);
            int at = probes.json(rec, head.length);
            if (at != rec.length - 1) {
                throw new IllegalStateException(f.file.name + ": its probes wrote " + (at - head.length)
                        + " bytes, not the " + probes.jsonLength() + " counted");
            }
            rec[at] = '}';
            return rec;
        }

        /**
         * sameProbes says whether two records of one class name and unit map their probes alike: they are
         * alike but for the id (16 hex digits), which ends at the same place in both.
         */
        static boolean sameProbes(byte[] a, byte[] b) {
            if (a.length != b.length) {
                return false;
            }
            int id = indexOf(a, ID_KEY) + ID_KEY.length + 16;
            for (int i = id; i < a.length; i++) {
                if (a[i] != b[i]) {
                    return false;
                }
            }
            return true;
        }

        private static final byte[] ID_KEY = ",\"id\":\"".getBytes(StandardCharsets.US_ASCII);

        private static int indexOf(byte[] a, byte[] key) {
            outer:
            for (int i = 0; i + key.length <= a.length; i++) {
                for (int j = 0; j < key.length; j++) {
                    if (a[i + j] != key[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            throw new IllegalStateException("a probe record with no id");
        }

        /**
         * analyze is the class file's one analysis (KeployBranchCoverage): it adds the file's methods to the
         * unit's (methods and byId; the first class file of a name makes a method, a second one of that name
         * is checked against it), counts a file with code but no line in u.noLines, and returns the file's
         * probe map: one entry per probe (the class's probe count), each the probe's leaves as half-open ranges
         * of its method's leaf indices, ascending, no two ranges touching; its record adds the method's base
         * once the unit's methods are ordered ({@link Ranges#json}). It throws NotMapped when the class's lines
         * or branches differ from the unit's methods (the same name under two roots), when a method with lines
         * is in one and not in the other, or when a method's flow is not one KeployBranchCoverage maps; TooLarge
         * as soon as the unit's map is past its budget's bytes or steps; and what JaCoCo throws for a class it
         * does not read.
         */
        static Ranges analyze(Unit u, UnitFile f, ClassInfo info, List<Method> methods, Map<String, Method> byId,
                MapBudget budget) {
            Map<String, KeployBranchCoverage.Method> shape = new HashMap<>();
            String prefix = "m:" + f.file.name + "#";
            String nested = f.file.name.substring(u.path().length());
            Ranges ranges = new Ranges(budget);
            boolean[] seen = new boolean[2]; // a method with code, and one with a line
            int total = Math.max(0, KeployBranchCoverage.analyze(f.file.bytes, shape,
                    new KeployBranchCoverage.EachProbe() {
                        private Method method;

                        @Override
                        public boolean wants(KeployBranchCoverage.Method s) {
                            try {
                                // As JaCoCo sums a class: code with no line when no method of it has one.
                                seen[0] = true;
                                if (s.coverage.getFirstLine() != ISourceNode.UNKNOWN_LINE) {
                                    seen[1] = true;
                                }
                                method = byId.get(prefix + s.name + s.desc);
                                if (method == null) {
                                    method = Method.of(f.file.name, nested, u.name, s.coverage, info);
                                    if (method == null) {
                                        // Checked below: a method with no line has no leaf for its probes.
                                        return false;
                                    }
                                    byId.put(method.id, method);
                                    methods.add(method);
                                }
                                method.prepare(s);
                                return true;
                            } catch (RuntimeException e) {
                                throw notMapped(e);
                            }
                        }

                        @Override
                        public void probe(KeployBranchCoverage.Method s, int r, KeployBranchCoverage.Hits h) {
                            try {
                                budget.step(h.steps);
                                method.hits(s, h, ranges.bits);
                                ranges.put(s.probes[r], method);
                            } catch (RuntimeException e) {
                                throw notMapped(e);
                            }
                        }
                    }));
            if (seen[0] && !seen[1]) {
                u.noLines++;
            }
            try {
                int inShape = 0;
                for (Map.Entry<String, KeployBranchCoverage.Method> e : shape.entrySet()) {
                    Method m = byId.get(prefix + e.getKey());
                    if (m == null) {
                        for (int l : e.getValue().lines) {
                            if (l >= 1) {
                                throw new IllegalStateException(f.file.name + "#" + e.getKey()
                                        + " has lines but no method in the table");
                            }
                        }
                        continue;
                    }
                    m.release();
                    inShape++;
                }
                // And the other way: each of the table's methods of this class file has its probes mapped here.
                int inTable = 0;
                for (String id : byId.keySet()) {
                    if (id.startsWith(prefix)) {
                        inTable++;
                    }
                }
                if (inTable != inShape) {
                    throw new IllegalStateException(f.file.name + ": " + inTable + " methods in the table, "
                            + inShape + " in the probe analysis");
                }
                ranges.close(f.file.name, total);
            } catch (RuntimeException e) {
                throw notMapped(e);
            }
            return ranges;
        }

        /**
         * notMapped is e as a map that failed: TooLarge and NotMapped as they are, a check that failed as
         * NotMapped.
         */
        private static RuntimeException notMapped(RuntimeException e) {
            if (e instanceof TooLarge || e instanceof KeployBranchCoverage.NotMapped) {
                return e;
            }
            return new KeployBranchCoverage.NotMapped(String.valueOf(e.getMessage()), e);
        }
    }

    /**
     * TooLarge ends a unit's probe map that is past its budget (MapBudget): the unit is sent not_measured
     * record_too_large. It carries no stack trace: it is an answer, not a bug.
     */
    static final class TooLarge extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TooLarge(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * MapBudget is what one unit's probe map may still take: bytes (its records', newlines included; its
     * ranges' text counts as it is written, since a record holds it) and walk steps. Past either it throws
     * TooLarge, so a map is given up as soon as it is past, not once it is made.
     */
    static final class MapBudget {
        private final long maxBytes;
        private final long maxSteps;
        private long text;
        private long bytes;
        private long steps;

        MapBudget(long maxBytes, long maxSteps) {
            this.maxBytes = maxBytes;
            this.maxSteps = maxSteps;
        }

        /** step counts a probe's walk. */
        void step(int n) {
            steps += n;
            if (steps > maxSteps) {
                throw new TooLarge("its probes' walks take more than " + maxSteps + " steps");
            }
        }

        /**
         * grew counts ranges' text an analysis wrote for a record not taken yet: at most what the record writes
         * for them, so the budget is past only when the records would be.
         */
        void grew(int n) {
            text += n;
            if (bytes + text > maxBytes) {
                throw new TooLarge("its probe map is more than " + maxBytes + " bytes");
            }
        }

        /** forget takes back the text counted for a record not taken: it shares the record before it. */
        void forget(long counted) {
            text -= counted;
        }

        /** take counts a whole record (with its newline) in place of the text counted for it. */
        void take(int n, long counted) {
            text -= counted;
            bytes += n;
            if (bytes + text > maxBytes) {
                throw new TooLarge("its probe records are more than " + maxBytes + " bytes");
            }
        }
    }

    /**
     * Ranges keeps a class file's probe map as its analysis makes it: each probe's leaf ranges relative to its
     * method, as ints in one buffer, with where each probe's ranges start and end and the probe's method, so a
     * probe costs its ranges and no object. Its record writes them once as ASCII JSON, each plus its method's
     * base, after the unit's methods are ordered ({@link ProbeMap#record}).
     */
    static final class Ranges {
        /** The probe being written: its method-relative leaves, set by the caller and cleared by {@link #put}. */
        final BitSet bits = new BitSet();
        private final MapBudget budget;
        private int[] bounds = new int[64];
        private int length;
        private int[] from = new int[16];
        private int[] to = new int[16];
        private Method[] owner = new Method[16];
        private int total = -1;
        // The text put counted in the budget: the ranges' text before the bases are added, so at most what
        // the record writes for them.
        private long counted;

        Ranges(MapBudget budget) {
            this.budget = budget;
        }

        /**
         * put keeps bits as the given probe's ranges of m's leaves (start, end, ... ascending, none touching),
         * then clears it.
         */
        void put(int probe, Method m) {
            if (probe >= from.length) {
                int size = Math.max(probe + 1, from.length * 2);
                from = Arrays.copyOf(from, size);
                to = Arrays.copyOf(to, size);
                owner = Arrays.copyOf(owner, size);
            }
            if (to[probe] != 0) {
                throw new IllegalStateException("probe " + probe + " mapped twice");
            }
            from[probe] = length;
            owner[probe] = m;
            int at = bits.nextSetBit(0);
            while (at >= 0) {
                int end = bits.nextClearBit(at);
                int n = (length > from[probe] ? 1 : 0) + digits(at) + 1 + digits(end);
                if (length + 2 > bounds.length) {
                    bounds = Arrays.copyOf(bounds, bounds.length * 2);
                }
                bounds[length++] = at;
                bounds[length++] = end;
                counted += n;
                budget.grew(n);
                at = bits.nextSetBit(end);
            }
            // to is one past the ranges' end, so 0 is "not mapped" and an empty range list is still mapped.
            to[probe] = length + 1;
            bits.clear();
        }

        /** counted is the text put counted in the budget for this map. */
        long counted() {
            return counted;
        }

        private static int digits(int v) {
            if (v < 0) {
                throw new IllegalStateException("leaf " + v);
            }
            int digits = 1;
            for (int x = v; x >= 10; x /= 10) {
                digits++;
            }
            return digits;
        }

        /** close ends the map at the class's probe count (total): a probe at or past it is a bug. */
        void close(String className, int total) {
            for (int p = total; p < from.length; p++) {
                if (to[p] != 0) {
                    throw new IllegalStateException(className + ": probe " + p + " of " + total + " probes");
                }
            }
            this.total = total;
        }

        /**
         * jsonLength is the length of {@link #json}'s text: [ ], one [ ] a probe, the commas, and each probe's
         * ranges plus its method's base.
         */
        int jsonLength() {
            long n = 2 + 3L * total - (total > 0 ? 1 : 0);
            for (int p = 0; p < total && p < from.length; p++) {
                if (to[p] == 0) {
                    continue;
                }
                int end = to[p] - 1;
                n += Math.max(0, end - from[p] - 1);
                int base = owner[p].base;
                for (int i = from[p]; i < end; i++) {
                    n += digits(base + bounds[i]);
                }
            }
            if (total < 0 || n > Integer.MAX_VALUE - 1024) {
                throw new IllegalStateException("a probe map of " + n + " bytes");
            }
            return (int) n;
        }

        /**
         * json writes the map into out at at and returns where it ends: one entry per probe of the class,
         * [] for a probe no method mapped, each range's bounds plus its method's base (unit leaf indices).
         */
        int json(byte[] out, int at) {
            out[at++] = '[';
            for (int p = 0; p < total; p++) {
                if (p > 0) {
                    out[at++] = ',';
                }
                out[at++] = '[';
                if (p < from.length && to[p] != 0) {
                    int base = owner[p].base;
                    for (int i = from[p]; i < to[p] - 1; i++) {
                        if (i > from[p]) {
                            out[at++] = ',';
                        }
                        int v = base + bounds[i];
                        int d = digits(v);
                        for (int j = at + d - 1; j >= at; j--) {
                            out[j] = (byte) ('0' + v % 10);
                            v /= 10;
                        }
                        at += d;
                    }
                }
                out[at++] = ']';
            }
            out[at++] = ']';
            return at;
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

        /**
         * send sends the frame in b[off, off + len). The bytes are the writer's own and change once it returns,
         * so a sender that keeps them copies them (as this default does); FrameWriter sends through this one.
         */
        default void send(byte[] b, int off, int len) throws IOException {
            send(Arrays.copyOfRange(b, off, off + len));
        }
    }

    /**
     * FrameWriter cuts the NDJSON stream of records into TABLE frames of at most MAX_FRAME_BYTES each and
     * sends them in order, one at a time.
     */
    static final class FrameWriter implements RecordSink {
        private final FrameSender sender;
        private final String part;
        private final int bodyCap;
        // The frame being filled, made at the first write and reused for every frame: its body from
        // HEADER_ROOM on, its magic, length and header just before it at each flush. A frame allocated anew
        // (twice: the body's copy and the frame) is a G1 humongous object at 1 MiB, for which G1 grows the
        // heap when its free regions run short: 70-150 MiB of a 4 GiB pod's heap at 50,000 classes.
        private byte[] frame;
        private int bodyLen;
        private final MessageDigest digest;
        int seq;
        private String build;

        FrameWriter(FrameSender sender) {
            this(sender, PART_TABLE, MAX_FRAME_BYTES);
        }

        FrameWriter(FrameSender sender, int maxFrame) {
            this(sender, PART_TABLE, maxFrame);
        }

        FrameWriter(FrameSender sender, String part, int maxFrame) {
            this.sender = sender;
            this.part = part;
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
                int room = bodyCap - bodyLen;
                if (room == 0) {
                    flush(false, -1);
                    continue;
                }
                int take = Math.min(room, len);
                System.arraycopy(b, off, frame(), HEADER_ROOM + bodyLen, take);
                bodyLen += take;
                off += take;
                len -= take;
            }
        }

        private byte[] frame() {
            if (frame == null) {
                frame = new byte[HEADER_ROOM + bodyCap];
            }
            return frame;
        }

        /** bytes adds whole records, each ended by its newline, to the stream as they are. */
        void bytes(String build, byte[] b, int off, int len) throws IOException {
            this.build = build;
            digest.update(b, off, len);
            write(b, off, len);
        }

        /** finish sends the last frame, with the record count and the stream's sha256. */
        void finish(String build, int records) throws IOException {
            this.build = build;
            flush(true, records);
        }

        private void flush(boolean last, int records) throws IOException {
            StringBuilder h = new StringBuilder(160);
            h.append("{\"v\":1,\"part\":\"").append(part).append("\",\"build\":");
            Json.string(h, build);
            h.append(",\"seq\":").append(seq).append(",\"last\":").append(last);
            if (last) {
                h.append(",\"records\":").append(records)
                        .append(",\"sha256\":\"").append(IndexReport.hex(digest.digest())).append('"');
            }
            h.append("}\n");
            byte[] header = h.toString().getBytes(StandardCharsets.UTF_8);
            int start = HEADER_ROOM - header.length - 8;
            if (start < 0) {
                // A build id or record count no header can carry: never one this class makes.
                throw new IOException("TABLE frame " + seq + ": a header of " + header.length + " bytes");
            }
            int n = header.length + bodyLen;
            byte[] f = frame();
            System.arraycopy(MAGIC.getBytes(StandardCharsets.US_ASCII), 0, f, start, 4);
            f[start + 4] = (byte) (n >>> 24);
            f[start + 5] = (byte) (n >>> 16);
            f[start + 6] = (byte) (n >>> 8);
            f[start + 7] = (byte) n;
            System.arraycopy(header, 0, f, start + 8, header.length);
            try {
                sender.send(f, start, 8 + n);
            } catch (IOException e) {
                throw new IOException("TABLE frame " + (PART_TABLE.equals(part) ? "" : part + " ") + seq + ": "
                        + e.getMessage(), e);
            }
            bodyLen = 0;
            seq++;
        }
    }

    /**
     * Spill keeps the probe map's records while the table streams, as the wire sends the map after the table's
     * last frame: in a temp file made owner-only, which is unlinked as soon as it is open where the OS allows
     * (so a JVM that dies mid-send leaves no file; one that dies between the file's making and its opening
     * leaves it empty), and is removed on close otherwise. It is opened without following a link, so a link
     * put in its place is never written through. A dir where no such file
     * can be made, or a write that fails (a full disk), ends the send before the table's last frame: Keploy
     * gets no table for that build and names it (table_incomplete), and the WARNING names the dir.
     */
    static final class Spill implements RecordSink, AutoCloseable {
        private final File dir;
        private final Path path;
        private final FileChannel channel;
        private final OutputStream out;

        private Spill(File dir, Path path, FileChannel channel) {
            this.dir = dir;
            this.path = path;
            this.channel = channel;
            this.out = new BufferedOutputStream(Channels.newOutputStream(channel), 1 << 16);
        }

        static Spill open(File dir) throws IOException {
            Path path;
            try {
                path = Files.createTempFile(dir.toPath(), "keploy-probe-map-", ".ndjson");
            } catch (IOException | RuntimeException e) {
                throw new IOException("no temp file for the probe map in " + dir + " (" + e
                        + "); set java.io.tmpdir to a dir the app can write", e);
            }
            try {
                return new Spill(dir, path, openOwned(path));
            } catch (IOException | RuntimeException e) {
                deleteQuietly(path);
                throw new IOException("could not open the probe map's temp file " + path + " (" + e + ")", e);
            }
        }

        /**
         * openOwned opens the file just made at path to write and read back, never through a link (a link put
         * in its place fails the open), and deleted on close.
         */
        static FileChannel openOwned(Path path) throws IOException {
            return FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                    StandardOpenOption.DELETE_ON_CLOSE, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public void record(String build, byte[] json) throws IOException {
            try {
                out.write(json);
                out.write('\n');
            } catch (IOException e) {
                throw new IOException("could not keep the probe map in a temp file in " + dir + ": "
                        + e.getMessage(), e);
            }
        }

        /** sendTo streams what was kept into w, from its first byte. */
        void sendTo(FrameWriter w, String build) throws IOException {
            out.flush();
            channel.position(0);
            InputStream in = Channels.newInputStream(channel);
            byte[] buf = new byte[1 << 16];
            for (int n = in.read(buf); n >= 0; n = in.read(buf)) {
                w.bytes(build, buf, 0, n);
            }
        }

        @Override
        public void close() {
            try {
                channel.close();
            } catch (IOException ignored) {
                // the file is removed below either way
            }
            deleteQuietly(path);
        }

        private static void deleteQuietly(Path path) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException | RuntimeException ignored) {
                // best effort: the file is owner-only, and gone already where it was unlinked once open
            }
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
            send(frame, 0, frame.length);
        }

        @Override
        public void send(byte[] b, int off, int len) throws IOException {
            AFUNIXSocket s = connect();
            try {
                s.setSoTimeout(REPLY_TIMEOUT_MILLIS);
                OutputStream out = s.getOutputStream();
                out.write(b, off, len);
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
