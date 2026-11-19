package io.keploy.dedup;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jacoco.core.runtime.RuntimeData;
import org.junit.Test;

/**
 * One pass over the app classes' live probe arrays: each hit is reported by
 * exactly one pass and cleared by it, library classes are left alone, and a
 * hit made while a pass runs is in that pass or the next, never lost and
 * never twice.
 */
public class ProbeScanTest {

    private static final Set<String> APP = new HashSet<>(Arrays.asList("app/A", "app/B", "app/C"));

    // An agent as JaCoCo's: its RuntimeData holds the store the instrumented
    // classes get their probe arrays from.
    static final class Agent {
        private final RuntimeData data = new RuntimeData();
    }

    // probes is the array JaCoCo hands the instrumented class name (id) to write its hits into.
    private static boolean[] probes(Agent agent, long id, String name, int count) {
        return agent.data.getExecutionData(id, name, count).getProbes();
    }

    private static Map<String, List<Integer>> asMap(List<ProbeScan.Hit> hits) {
        Map<String, List<Integer>> byName = new HashMap<>();
        for (ProbeScan.Hit hit : hits) {
            List<Integer> probes = new ArrayList<>();
            for (int probe : hit.probes) {
                probes.add(probe);
            }
            byName.put(hit.name, probes);
        }
        return byName;
    }

    private static List<String> names(List<ProbeScan.Hit> hits) {
        List<String> names = new ArrayList<>();
        for (ProbeScan.Hit hit : hits) {
            names.add(hit.name);
        }
        return names;
    }

    @Test
    public void aHitIsReportedOnceThenCleared() throws Exception {
        Agent agent = new Agent();
        boolean[] a = probes(agent, 1, "app/A", 5);
        boolean[] b = probes(agent, 2, "app/B", 3);
        boolean[] lib = probes(agent, 3, "lib/L", 4);
        // More probes than any other class: its hits need the most room.
        boolean[] c = probes(agent, 4, "app/C", 300);
        ProbeScan scan = ProbeScan.locate(agent);

        a[1] = true;
        a[3] = true;
        lib[0] = true;
        c[5] = true;
        c[299] = true;
        List<ProbeScan.Hit> hits = scan.pass(APP);

        assertEquals(2, hits.size());
        assertEquals("app/C", hits.get(1).name);
        assertArrayEquals(new int[] {5, 299}, hits.get(1).probes);
        assertArrayEquals(new boolean[300], c);
        ProbeScan.Hit hit = hits.get(0);
        assertEquals("app/A", hit.name);
        assertEquals(1L, hit.id);
        assertEquals(5, hit.probeCount);
        assertArrayEquals(new int[] {1, 3}, hit.probes);
        assertArrayEquals("the pass cleared what it reported", new boolean[5], a);
        assertArrayEquals(new boolean[3], b);
        assertTrue("a library class is neither reported nor cleared", lib[0]);
        assertTrue("nothing was hit since", scan.pass(APP).isEmpty());
        assertEquals(3, scan.classes());
    }

    @Test
    public void backgroundHitsNeverReachTheNextTest() throws Exception {
        Agent agent = new Agent();
        boolean[] a = probes(agent, 1, "app/A", 4);
        boolean[] b = probes(agent, 2, "app/B", 4);
        ProbeScan scan = ProbeScan.locate(agent);

        // Between two tests: a scheduled job runs A's probe 0 and B's 2.
        a[0] = true;
        b[2] = true;
        Map<String, List<Integer>> background = asMap(scan.pass(APP));
        assertEquals(Arrays.asList(0), background.get("app/A"));
        assertEquals(Arrays.asList(2), background.get("app/B"));

        // The test runs A's probes 1 and 2 only.
        a[1] = true;
        a[2] = true;
        Map<String, List<Integer>> test = asMap(scan.pass(APP));
        assertEquals(Collections.singletonMap("app/A", Arrays.asList(1, 2)), test);
    }

    @Test
    public void aClassLoadedAfterAPassIsScannedByTheNext() throws Exception {
        Agent agent = new Agent();
        probes(agent, 1, "app/A", 2);
        ProbeScan scan = ProbeScan.locate(agent);
        assertTrue(scan.pass(APP).isEmpty());

        boolean[] c = probes(agent, 7, "app/C", 3);
        boolean[] lib = probes(agent, 8, "lib/M", 3);
        c[2] = true;
        lib[1] = true;
        List<ProbeScan.Hit> hits = scan.pass(APP);
        assertEquals(1, hits.size());
        assertEquals(Collections.singletonMap("app/C", Arrays.asList(2)), asMap(hits));
        assertEquals(2, scan.classes());
        assertTrue(lib[1]);
    }

    @Test
    public void aPassWithNoClassLoadedSinceDoesNotWalkJacocosStore() throws Exception {
        Agent agent = new Agent();
        CountingMap entries = CountingMap.swapInto(agent.data);
        boolean[] a = probes(agent, 1, "app/A", 2);
        probes(agent, 2, "lib/L", 2);
        ProbeScan scan = ProbeScan.locate(agent);

        scan.pass(APP);
        assertEquals(1, entries.walks.get());
        a[0] = true;
        assertEquals(1, scan.pass(APP).size());
        assertEquals("the store did not grow", 1, entries.walks.get());
        probes(agent, 3, "lib/M", 2);
        scan.pass(APP);
        assertEquals(2, entries.walks.get());
    }

    // CountingMap stands in for the map of JaCoCo's store: it counts the
    // walks over it, and fails them when told to.
    static final class CountingMap extends HashMap<Object, Object> {

        final java.util.concurrent.atomic.AtomicInteger walks = new java.util.concurrent.atomic.AtomicInteger();
        volatile boolean fail;
        // error, when set, is thrown by the walk in place of fail's exception.
        volatile Error error;

        // swapInto puts an empty CountingMap in place of the map of data's
        // store, before any class is added to it.
        static CountingMap swapInto(RuntimeData data) throws ReflectiveOperationException {
            Object store = storeOf(data);
            java.lang.reflect.Field entries = store.getClass().getDeclaredField("entries");
            entries.setAccessible(true);
            CountingMap map = new CountingMap();
            entries.set(store, map);
            return map;
        }

        @Override
        public Set<Map.Entry<Object, Object>> entrySet() {
            walks.incrementAndGet();
            if (error != null) {
                throw error;
            }
            if (fail) {
                throw new IllegalStateException("the walk over the store failed");
            }
            return super.entrySet();
        }
    }

    @Test
    public void hitsComeSortedByNameThenIdAcrossEveryPart() throws Exception {
        Agent agent = new Agent();
        Set<String> app = new HashSet<>();
        List<boolean[]> arrays = new ArrayList<>();
        // 103 classes, added out of order, split in 4 parts of 26, 26, 26 and 25.
        for (int i = 102; i >= 0; i--) {
            String name = String.format("app/K%03d", i);
            app.add(name);
            arrays.add(probes(agent, 1000 + i, name, 2));
        }
        // A second class of one name (loaded twice, from other bytes).
        boolean[] twin = probes(agent, 1, "app/K050", 3);
        ProbeScan scan = new ProbeScan(storeOf(agent), entriesOf(agent), 4, 1);
        for (boolean[] probes : arrays) {
            probes[1] = true;
        }
        twin[2] = true;

        List<ProbeScan.Hit> hits = scan.pass(app);

        assertEquals(104, hits.size());
        List<String> expected = new ArrayList<>();
        for (int i = 0; i <= 102; i++) {
            expected.add(String.format("app/K%03d", i));
            if (i == 50) {
                expected.add("app/K050");
            }
        }
        assertEquals(expected, names(hits));
        assertEquals("the twin's lower id comes first", 1L, hits.get(50).id);
        assertEquals(1050L, hits.get(51).id);
        for (boolean[] probes : arrays) {
            assertFalse(probes[1]);
        }
        assertTrue(scan.pass(app).isEmpty());
    }

    @Test
    public void aHitRacingThePassesIsReportedExactlyOnce() throws Exception {
        final Agent agent = new Agent();
        final int classes = 128;
        final int probeCount = 128;
        Set<String> app = new HashSet<>();
        final boolean[][] arrays = new boolean[classes][];
        for (int i = 0; i < classes; i++) {
            String name = "app/R" + i;
            app.add(name);
            arrays[i] = probes(agent, 100 + i, name, probeCount);
        }
        Set<Thread> before = Thread.getAllStackTraces().keySet();
        ProbeScan scan = new ProbeScan(storeOf(agent), entriesOf(agent), 4, 8);
        assertTrue(scan.pass(app).isEmpty());

        // The app thread sets each probe once, in a random order, while the
        // passes run.
        final List<int[]> order = new ArrayList<>();
        for (int c = 0; c < classes; c++) {
            for (int p = 0; p < probeCount; p++) {
                order.add(new int[] {c, p});
            }
        }
        Collections.shuffle(order, new Random(42));
        // The writes go in batches, and after each batch the writer waits for
        // two passes that ended after the batch began: at least one of them
        // runs after some of the batch's writes, however busy the box is.
        final int batch = 512;
        final AtomicInteger passCount = new AtomicInteger();
        final AtomicBoolean done = new AtomicBoolean();
        final AtomicBoolean stop = new AtomicBoolean();
        Thread app1 = new Thread(() -> {
            int begun = passCount.get();
            for (int i = 0; i < order.size(); i++) {
                int[] hit = order.get(i);
                arrays[hit[0]][hit[1]] = true;
                // About a microsecond between two hits, so that the passes
                // also run while a batch is written.
                long until = System.nanoTime() + 1000;
                while (System.nanoTime() < until) {
                    Thread.yield();
                }
                if ((i + 1) % batch == 0) {
                    while (passCount.get() < begun + 2 && !stop.get()) {
                        Thread.yield();
                    }
                    begun = passCount.get();
                }
            }
            done.set(true);
        }, "probe-writer");
        app1.setDaemon(true);

        Map<String, int[]> reported = new HashMap<>();
        app1.start();
        int passes = 0;
        try {
            while (!done.get()) {
                count(scan.pass(app), reported);
                passes = passCount.incrementAndGet();
            }
        } finally {
            stop.set(true);
        }
        app1.join();
        count(scan.pass(app), reported);
        assertFalse("no pass leaves the caller interrupted", Thread.interrupted());
        int scanThreads = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (!before.contains(thread) && thread.getName().startsWith("keploy-java-dedup-scan-")) {
                scanThreads++;
                assertTrue(thread.isDaemon());
            }
        }
        assertTrue(scanThreads + " scan threads over " + passes + " passes", scanThreads >= 1 && scanThreads <= 3);
        assertTrue("the passes ran through the writes: " + passes, passes >= 2 * order.size() / batch);

        for (int c = 0; c < classes; c++) {
            int[] times = reported.get("app/R" + c);
            for (int p = 0; p < probeCount; p++) {
                assertEquals("app/R" + c + " probe " + p + " (over " + passes + " passes)", 1,
                        times == null ? 0 : times[p]);
            }
        }
    }

    private static void count(List<ProbeScan.Hit> hits, Map<String, int[]> reported) {
        for (ProbeScan.Hit hit : hits) {
            int[] times = reported.get(hit.name);
            if (times == null) {
                times = new int[hit.probeCount];
                reported.put(hit.name, times);
            }
            for (int probe : hit.probes) {
                times[probe]++;
            }
        }
    }

    @Test
    public void anAgentNotLaidOutAsJacocosIsRefused() {
        assertRefused(new Object(), "no RuntimeData field");
        assertRefused(new TwoData(), "two RuntimeData fields");
        assertRefused(new NullData(), "is null");
        assertRefused(new NoStore(), "no ExecutionDataStore field");
    }

    // NoStore's RuntimeData is not JaCoCo's: it holds no store.
    static final class NoStore {
        private final RuntimeData data = new RuntimeData();

        static final class RuntimeData {
            private final String store = "none";
        }
    }

    @Test
    public void aStaticFieldOfTheTypeIsNotTheAgents() throws Exception {
        StaticToo agent = new StaticToo();
        agent.data.getExecutionData(1L, "app/A", 2).getProbes()[1] = true;
        assertEquals(Collections.singletonMap("app/A", Arrays.asList(1)), asMap(ProbeScan.locate(agent).pass(APP)));
    }

    static final class StaticToo {
        private static final RuntimeData SHARED = new RuntimeData();
        private final RuntimeData data = new RuntimeData();
    }

    @Test
    public void aFieldInASuperclassIsFound() throws Exception {
        Sub agent = new Sub();
        agent.data.getExecutionData(1L, "app/B", 2).getProbes()[0] = true;
        assertEquals(Collections.singletonMap("app/B", Arrays.asList(0)), asMap(ProbeScan.locate(agent).pass(APP)));
    }

    static class Base {
        final RuntimeData data = new RuntimeData();
    }

    static final class Sub extends Base {
        private final String name = "sub";
    }

    static final class TwoData {
        private final RuntimeData data = new RuntimeData();
        private final RuntimeData other = new RuntimeData();
    }

    static final class NullData {
        private final RuntimeData data = null;
    }

    private static void assertRefused(Object agent, String why) {
        try {
            ProbeScan.locate(agent);
            fail("located a store in " + agent.getClass().getName());
        } catch (ReflectiveOperationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(why));
        }
    }

    // breakSlot puts a null in place of the probe array of the class at index
    // of the scan's sorted classes, so the part that scans it fails there.
    static void breakSlot(ProbeScan scan, int index) throws ReflectiveOperationException {
        java.lang.reflect.Field arrays = ProbeScan.class.getDeclaredField("arrays");
        arrays.setAccessible(true);
        ((boolean[][]) arrays.get(scan))[index] = null;
    }

    // classesF makes the classes app/F000..app/F127 in agent, two probes
    // each, adds them to app, and returns their probe arrays.
    private static boolean[][] classesF(Agent agent, Set<String> app) {
        boolean[][] arrays = new boolean[128][];
        for (int i = 0; i < arrays.length; i++) {
            String name = String.format("app/F%03d", i);
            app.add(name);
            arrays[i] = probes(agent, 500 + i, name, 2);
        }
        return arrays;
    }

    // usePool gives scan a pool of 3 threads made by threads.
    private static void usePool(ProbeScan scan, java.util.concurrent.ThreadFactory threads)
            throws ReflectiveOperationException {
        java.lang.reflect.Field pool = ProbeScan.class.getDeclaredField("pool");
        pool.setAccessible(true);
        java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(3, 3, 60,
                java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<Runnable>(),
                threads);
        executor.allowCoreThreadTimeOut(true);
        pool.set(scan, executor);
    }

    // useLatePool gives scan a pool of 3 threads that starts the tasks it is
    // handed 0, step and 2 * step ms late, in turn, on every pass: the delay
    // is the task's, not its thread's, so threads kept from an earlier pass
    // keep it.
    private static void useLatePool(ProbeScan scan, final long step) throws ReflectiveOperationException {
        java.lang.reflect.Field pool = ProbeScan.class.getDeclaredField("pool");
        pool.setAccessible(true);
        final Map<Runnable, Long> late = new java.util.concurrent.ConcurrentHashMap<>();
        final AtomicInteger handed = new AtomicInteger();
        java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(3, 3, 60,
                java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<Runnable>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "late-scan");
                    thread.setDaemon(true);
                    return thread;
                }) {
            @Override
            public void execute(Runnable task) {
                late.put(task, step * (handed.getAndIncrement() % 3));
                super.execute(task);
            }

            @Override
            protected void beforeExecute(Thread thread, Runnable task) {
                Long millis = late.remove(task);
                if (millis != null && millis > 0) {
                    try {
                        Thread.sleep(millis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        executor.allowCoreThreadTimeOut(true);
        pool.set(scan, executor);
    }

    @Test
    public void aFailedPartLosesItsHitsAndEndsThePassOnlyOnceEveryPartHas() throws Exception {
        // 4 parts of 32 classes; the second, a pool thread's, fails at its 9th class,
        // and then the caller's.
        for (int broken : new int[] {40, 8}) {
            Agent agent = new Agent();
            Set<String> app = new HashSet<>();
            boolean[][] arrays = classesF(agent, app);
            ProbeScan scan = new ProbeScan(storeOf(agent), entriesOf(agent), 4, 8);
            // Each pass's pool parts start 0, 300 and 600 ms late, so the
            // parts after the one that fails are still to run when it does.
            useLatePool(scan, 300);
            assertTrue(scan.pass(app).isEmpty());
            for (boolean[] probes : arrays) {
                probes[0] = true;
            }
            breakSlot(scan, broken);
            try {
                scan.pass(app);
                fail("a pass whose part failed returned its hits");
            } catch (ProbeScan.LostHitsException e) {
                assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof NullPointerException);
            }
            int part = broken / 32;
            for (int i = 0; i < arrays.length; i++) {
                boolean leftByTheFailure = i >= broken && i < (part + 1) * 32;
                assertEquals("broken " + broken + ", app/F" + i, leftByTheFailure, arrays[i][0]);
            }
            assertFalse("no pass leaves the caller interrupted", Thread.interrupted());
        }
    }

    @Test
    public void anInterruptedPassStillWaitsForEveryPartAndKeepsTheInterrupt() throws Exception {
        Agent agent = new Agent();
        Set<String> app = new HashSet<>();
        boolean[][] arrays = classesF(agent, app);
        ProbeScan scan = new ProbeScan(storeOf(agent), entriesOf(agent), 4, 8);
        // The pool's threads start 300 ms late: the caller waits on them.
        usePool(scan, runnable -> {
            Thread thread = new Thread(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                runnable.run();
            }, "late-scan");
            thread.setDaemon(true);
            return thread;
        });
        for (boolean[] probes : arrays) {
            probes[1] = true;
        }
        Thread.currentThread().interrupt();
        List<ProbeScan.Hit> hits;
        try {
            hits = scan.pass(app);
        } finally {
            assertTrue("the caller is interrupted again", Thread.interrupted());
        }
        assertEquals(128, hits.size());
        for (boolean[] probes : arrays) {
            assertFalse(probes[1]);
        }
    }

    @Test
    public void aPartThePoolCannotTakeIsScannedOnTheCallersThread() throws Exception {
        Agent agent = new Agent();
        Set<String> app = new HashSet<>();
        boolean[][] arrays = classesF(agent, app);
        ProbeScan scan = new ProbeScan(storeOf(agent), entriesOf(agent), 4, 8);
        // A pool that can make no thread, as at a container's limit on them.
        final AtomicInteger asked = new AtomicInteger();
        usePool(scan, runnable -> {
            asked.incrementAndGet();
            throw new OutOfMemoryError("unable to create native thread");
        });
        assertTrue(scan.pass(app).isEmpty());
        for (int i = 0; i < arrays.length; i++) {
            arrays[i][i % 2] = true;
        }
        List<ProbeScan.Hit> hits = scan.pass(app);
        assertEquals(128, hits.size());
        for (int i = 0; i < hits.size(); i++) {
            assertEquals(String.format("app/F%03d", i), hits.get(i).name);
            assertArrayEquals(new int[] {i % 2}, hits.get(i).probes);
            assertFalse(arrays[i][i % 2]);
        }
        assertTrue("the pool was asked for a thread: " + asked.get(), asked.get() >= 3);
    }

    @Test
    public void anErrorReadingJacocosClassesClearsNothing() throws Exception {
        Agent agent = new Agent();
        CountingMap entries = CountingMap.swapInto(agent.data);
        Set<String> app = new HashSet<>();
        boolean[][] arrays = classesF(agent, app);
        arrays[3][1] = true;
        ProbeScan scan = new ProbeScan(storeOf(agent), entries, 4, 8);
        entries.error = new NoClassDefFoundError("org/jacoco/core/data/ExecutionData");
        try {
            scan.pass(app);
            fail("a pass that could not read JaCoCo's classes returned");
        } catch (ProbeScan.LostHitsException e) {
            fail("nothing was cleared, yet the pass said hits were lost");
        } catch (IllegalStateException e) {
            assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof NoClassDefFoundError);
        }
        assertTrue("the hit is left for JaCoCo's dump", arrays[3][1]);
    }

    @Test
    public void defaultWorkersIsHalfTheCoresUpToFour() {
        int cores = Runtime.getRuntime().availableProcessors();
        assertEquals(Math.max(1, Math.min(4, cores / 2)), ProbeScan.defaultWorkers());
    }

    // storeOf and entriesOf reach the same objects locate does, for a scan
    // made with other worker counts.
    private static Object storeOf(Agent agent) throws ReflectiveOperationException {
        return storeOf(agent.data);
    }

    static Object storeOf(RuntimeData data) throws ReflectiveOperationException {
        java.lang.reflect.Field store = RuntimeData.class.getDeclaredField("store");
        store.setAccessible(true);
        return store.get(data);
    }

    private static Map<?, ?> entriesOf(Agent agent) throws ReflectiveOperationException {
        Object store = storeOf(agent);
        java.lang.reflect.Field entries = store.getClass().getDeclaredField("entries");
        entries.setAccessible(true);
        return (Map<?, ?>) entries.get(store);
    }
}
