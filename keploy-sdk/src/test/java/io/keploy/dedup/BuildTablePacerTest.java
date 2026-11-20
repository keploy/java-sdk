package io.keploy.dedup;

import org.junit.Test;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The table's pacing (BuildTable.Pacer) on a clock the rows drive: it waits out 200 times the collections'
 * time, only after a collection, never more in all than the send's own work time, saved up to a second, and
 * never past the table's deadline.
 */
public class BuildTablePacerTest {

    private static final long MS = 1_000_000L;
    private static final long MIN = BuildTable.Pacer.MIN_WAIT_NANOS;
    // The units of 1 ms work whose time first reaches the smallest wait, after a wait used it all.
    private static final long K = MIN / MS;

    /** A clock that moves only as a row says and by what the pacer sleeps (plus overrun a sleep; or not). */
    static final class Clock implements BuildTable.Pacer.Clock {
        long now = 1_000 * MS;
        long gc = 7;
        long overrun;
        /** A sleep does not move the clock (one too coarse to see it). */
        boolean frozen;
        /** The time the last sleep ended. */
        long lastWake;
        int gcReads;
        RuntimeException failGc;
        boolean interrupt;
        final List<Long> sleeps = new ArrayList<>();
        /** The unit after which each sleep came (the count of unitDone calls made by then). */
        final List<Integer> sleptAt = new ArrayList<>();
        int units;

        @Override
        public long nanoTime() {
            return now;
        }

        @Override
        public long gcMillis() {
            gcReads++;
            if (failGc != null) {
                throw failGc;
            }
            return gc;
        }

        @Override
        public void sleep(long nanos) throws InterruptedException {
            if (interrupt) {
                throw new InterruptedException("row");
            }
            sleeps.add(nanos);
            sleptAt.add(units);
            if (!frozen) {
                now += nanos + overrun;
            }
            lastWake = now;
        }

        long slept() {
            long s = 0;
            for (long n : sleeps) {
                s += n;
            }
            return s;
        }
    }

    /** run makes n units of one class file each, workNanos apiece, collecting gcPerUnit ms in each. */
    private static void run(BuildTable.Pacer p, Clock c, int n, long workNanos, long gcPerUnit)
            throws InterruptedIOException {
        for (int i = 0; i < n; i++) {
            c.now += workNanos;
            c.gc += gcPerUnit;
            c.units++;
            p.unitDone(1);
        }
    }

    @Test
    public void aJvmThatDoesNotCollectIsNotPaced() throws Exception {
        Clock c = new Clock();
        BuildTable.Pacer p = new BuildTable.Pacer(c);
        run(p, c, 50_000, 50_000, 0);
        assertEquals(0, c.sleeps.size());
        assertEquals(0, p.waitedNanos());
    }

    @Test
    public void aCollectionIsWaitedOutTo200TimesItsTime() throws Exception {
        Clock c = new Clock();
        BuildTable.Pacer p = new BuildTable.Pacer(c);
        run(p, c, 1_000, MS, 0);
        assertEquals("no wait before a collection", 0, c.sleeps.size());
        // A 4 ms collection during the next unit: 800 ms of the send's time, less that unit's 1 ms (a second of
        // the send's own work came before, so its bound lets it all be waited).
        c.now += MS;
        c.gc += 4;
        c.units++;
        p.unitDone(1);
        assertEquals(1, c.sleeps.size());
        assertEquals(799 * MS, (long) c.sleeps.get(0));
        run(p, c, 1_000, MS, 0);
        assertEquals("paid off: no wait after it", 1, c.sleeps.size());
        assertEquals(799 * MS, p.waitedNanos());
    }

    @Test
    public void workSinceACollectionCountsTowardItsWait() throws Exception {
        Clock c = new Clock();
        BuildTable.Pacer p = new BuildTable.Pacer(c);
        run(p, c, 2_000, 50_000, 0);
        // A 1 ms collection (200 ms owed) during a unit that took 150 ms: 50 ms are left to wait.
        c.now += 150 * MS;
        c.gc += 1;
        c.units++;
        p.unitDone(1);
        assertEquals(1, c.sleeps.size());
        assertEquals(50 * MS, (long) c.sleeps.get(0));
        // Owed less than the smallest wait is never slept.
        c.now += 195 * MS + 1;
        c.gc += 1;
        c.units++;
        p.unitDone(1);
        assertEquals(1, c.sleeps.size());
    }

    @Test
    public void theWaitsAreBoundedByTheSendsOwnWork() throws Exception {
        Clock c = new Clock();
        BuildTable.Pacer p = new BuildTable.Pacer(c);
        long start = c.now;
        // A JVM that collects 10 ms in every unit (a busy app's own collections) owes 2 s a unit: only the send's
        // own work, 0.1 ms a unit (5 s in all), bounds the waits.
        long w = MS / 10;
        run(p, c, 50_000, w, 10);
        long slept = c.slept();
        assertTrue("slept " + slept, slept <= 50_000 * w);
        assertTrue("slept " + slept, slept > 50_000 * w - MIN);
        assertEquals(slept, p.waitedNanos());
        assertTrue("the table takes at most twice its work", c.now - start <= 2 * 50_000 * w);
        // Over any run of units, the waits add up to at most a second plus those units' own time.
        long[] sum = new long[c.units + 1];
        for (int i = 0; i < c.sleeps.size(); i++) {
            sum[c.sleptAt.get(i)] += c.sleeps.get(i);
        }
        long low = 0;
        long prefix = 0;
        for (int j = 1; j <= c.units; j++) {
            prefix += sum[j];
            long at = prefix - j * w;
            assertTrue("waits over a run of units, up to unit " + j, at - low <= BuildTable.Pacer.MAX_BANK_NANOS);
            low = Math.min(low, at);
        }
    }

    @Test
    public void aQuietStretchSavesUpAtMostASecond() throws Exception {
        Clock c = new Clock();
        BuildTable.Pacer p = new BuildTable.Pacer(c);
        run(p, c, 5_000, MS, 0);
        // 100 ms of collection owes 20 s; 5 s of the send's work went by, but a second of it was kept.
        c.now += 20_000;
        c.gc += 100;
        c.units++;
        p.unitDone(1);
        assertEquals(1, c.sleeps.size());
        assertEquals(BuildTable.Pacer.MAX_BANK_NANOS, (long) c.sleeps.get(0));
        // Then a wait each time the send's work since reaches the smallest wait: K units' time every K of them.
        run(p, c, 100, MS, 0);
        assertEquals(1 + 100 / K, c.sleeps.size());
        for (int i = 1; i < c.sleeps.size(); i++) {
            assertEquals(K * MS, (long) c.sleeps.get(i));
        }
    }

    @Test
    public void aUnitMayWaitItsOwnTimeNotItsClassFiles() throws Exception {
        Clock c = new Clock();
        BuildTable.Pacer p = new BuildTable.Pacer(c);
        // A unit of 300 class files that took 20 us: a second of collection owes 200 s, and it may wait 20 us.
        c.now += 20_000;
        c.gc += 1_000;
        p.unitDone(300);
        assertEquals(0, c.sleeps.size());
        // One class file that took 300 ms: it may wait that, and the 20 us before it.
        c.now += 300 * MS;
        c.gc += 1_000;
        p.unitDone(1);
        assertEquals(1, c.sleeps.size());
        assertEquals(300 * MS + 20_000, (long) c.sleeps.get(0));
    }

    @Test
    public void aSleepsOverrunComesOffWhatItMayWait() throws Exception {
        Clock c = new Clock();
        c.overrun = 5 * MS;
        BuildTable.Pacer p = new BuildTable.Pacer(c);
        long start = c.now;
        long w = MS / 10;
        run(p, c, 10_000, w, 10);
        long waited = c.now - start - 10_000 * w;
        assertEquals(waited, p.waitedNanos());
        // The time asleep, overruns included, stays within the send's own work (and one overrun past it).
        assertTrue("waited " + waited, waited <= 10_000 * w + c.overrun);
        assertTrue("waited " + waited, waited > 10_000 * w - MIN - c.overrun);
    }

    @Test
    public void anInterruptEndsTheSendAndIsKept() throws Exception {
        try {
            // During a wait.
            Clock c = new Clock();
            BuildTable.Pacer p = new BuildTable.Pacer(c);
            c.interrupt = true;
            try {
                run(p, c, 100, MS, 10);
                fail("an interrupt during a wait ends the send");
            } catch (InterruptedIOException e) {
                assertEquals("the build table's thread was interrupted", e.getMessage());
                assertTrue("the interrupt is kept for the thread", Thread.interrupted());
            }
            assertEquals("it came at the first wait", K, c.units);
            // Between waits, and on a Pacer that never waits.
            for (BuildTable.Pacer q : new BuildTable.Pacer[] {new BuildTable.Pacer(new Clock()),
                    BuildTable.Pacer.off()}) {
                q.unitDone(1);
                Thread.currentThread().interrupt();
                try {
                    q.unitDone(1);
                    fail("an interrupt ends the send at the next unit");
                } catch (InterruptedIOException e) {
                    assertTrue("the interrupt is kept for the thread", Thread.interrupted());
                }
            }
        } finally {
            // A failing row must not leave the runner's thread interrupted for the rows after it.
            Thread.interrupted();
        }
    }

    @Test
    public void theWaitsGiveWayToTheDeadline() throws Exception {
        // 50,000 class files of 0.3 ms work each (15 s) in a JVM that collects 10 ms in each: the work bound
        // alone would wait 15 s, a table of 30 s. The deadline holds it to 20 s, waiting all the room left.
        long w = 3 * MS / 10;
        Clock c = new Clock();
        BuildTable.Pacer p = new BuildTable.Pacer(c);
        p.begin(50_000);
        long start = c.now;
        run(p, c, 50_000, w, 10);
        long took = c.now - start;
        assertTrue("took " + took, took <= BuildTable.Pacer.deadlineNanos(50_000));
        assertTrue("waited " + p.waitedNanos(), p.waitedNanos() > 5_000 * MS - 2 * MIN);
        // Not told its class files, a Pacer still waits no later than 20 s into the table.
        Clock d = new Clock();
        BuildTable.Pacer q = new BuildTable.Pacer(d);
        start = d.now;
        run(q, d, 50_000, w, 10);
        assertTrue("woke at " + (d.lastWake - start), d.lastWake - start <= BuildTable.Pacer.deadlineNanos(0));
        assertTrue("waited " + q.waitedNanos(), q.waitedNanos() > 5_000 * MS);
        assertEquals(20_000 * MS, BuildTable.Pacer.deadlineNanos(50_000));
        assertEquals("0.4 ms a class file past 50,000", 40_000 * MS, BuildTable.Pacer.deadlineNanos(100_000));
        assertEquals(20_000 * MS, BuildTable.Pacer.deadlineNanos(10));
    }

    @Test
    public void theRateLeavesOutTheTimeBeforeTheAnalysis() throws Exception {
        // The index's read takes 4 s before the analysis begins; then 50,000 class files of 0.2 ms work each
        // (10 s), 10 ms of collection in each. The rate is the analysis's own (0.2 ms a class file), so the waits
        // begin at once (the read is the send's own work too); the deadline still counts the 4 s, so the waits
        // fill the 6 s left and no more.
        Clock c = new Clock();
        BuildTable.Pacer p = new BuildTable.Pacer(c);
        long start = c.now;
        c.now += 4_000 * MS;
        p.begin(50_000);
        run(p, c, 50_000, MS / 5, 10);
        // The read's 4 s paid off the first unit's 2 s owed, and saved a second up: the first wait is at the
        // second unit, a second long. Had the rate counted the read, the deadline would have left no room.
        assertEquals("the first wait at the second unit", 2, (long) c.sleptAt.get(0));
        assertEquals(BuildTable.Pacer.MAX_BANK_NANOS, (long) c.sleeps.get(0));
        assertTrue("took " + (c.now - start), c.now - start <= BuildTable.Pacer.deadlineNanos(50_000));
        assertTrue("waited " + p.waitedNanos(), p.waitedNanos() > 6_000 * MS - 2 * MIN);
    }

    @Test
    public void aTableWhoseWorkFillsItsDeadlineIsNotPaced() throws Exception {
        Clock c = new Clock();
        BuildTable.Pacer p = new BuildTable.Pacer(c);
        p.begin(50_000);
        run(p, c, 50_000, MS, 10);
        assertEquals(0, c.sleeps.size());
    }

    @Test
    public void aClockThatDoesNotMoveAcrossASleepIsChargedTheWait() throws Exception {
        Clock c = new Clock();
        c.frozen = true;
        BuildTable.Pacer p = new BuildTable.Pacer(c);
        run(p, c, 10_000, 20_000, 10);
        long slept = c.slept();
        assertTrue("slept " + slept, slept <= 10_000 * 20_000L);
        assertTrue("slept " + slept, slept > 10_000 * 20_000L - MIN);
    }

    @Test
    public void aCollectorBeanThatFailsEndsThePacing() throws Exception {
        Clock c = new Clock();
        BuildTable.Pacer p = new BuildTable.Pacer(c);
        run(p, c, 100, MS, 10);
        int before = c.sleeps.size();
        assertTrue(before > 0);
        c.failGc = new UnsupportedOperationException("row");
        run(p, c, 1, MS, 10);
        c.failGc = null;
        run(p, c, 1_000, MS, 10);
        assertEquals(before, c.sleeps.size());
    }

    @Test
    public void aPacerOffNeverReadsOrWaits() throws Exception {
        BuildTable.Pacer p = BuildTable.Pacer.off();
        for (int i = 0; i < 1_000; i++) {
            p.unitDone(1_000);
        }
        assertEquals(0, p.waitedNanos());
    }

    @Test
    public void aPacerCountsItsClassFilesPacedOrNot() throws Exception {
        for (BuildTable.Pacer p : new BuildTable.Pacer[] {BuildTable.Pacer.off(), new BuildTable.Pacer(new Clock())}) {
            assertEquals(0, p.totalClassFiles());
            p.begin(5);
            p.unitDone(2);
            p.unitDone(3);
            assertEquals(5, p.doneClassFiles());
            assertEquals(5, p.totalClassFiles());
        }
    }

    @Test
    public void theJvmsPacerReadsItsCollectors() throws Exception {
        BuildTable.Pacer.JvmClock jvm = new BuildTable.Pacer.JvmClock(
                java.lang.management.ManagementFactory.getGarbageCollectorMXBeans());
        long before = jvm.gcMillis();
        assertTrue("a collection's time is read: " + before, before >= 0);
        System.gc();
        assertTrue(jvm.gcMillis() >= before);
        assertEquals(0, BuildTable.Pacer.JvmClock.NONE.gcMillis());
    }

    @Test
    public void writePacesAfterEachUnitByItsTime() throws Exception {
        List<BuildTable.ClassFile> app = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            app.add(new BuildTable.ClassFile(BuildTableBudgetTest.name(i),
                    BuildTableBudgetTest.gen(BuildTableBudgetTest.name(i), i, 3)));
        }
        Clock c = new Clock();
        int[] records = {0};
        BuildTable.Pacer.Clock collecting = new BuildTable.Pacer.Clock() {
            @Override
            public long nanoTime() {
                return c.nanoTime();
            }

            @Override
            public long gcMillis() {
                // Each read finds a second more of collection: the send's own work alone bounds the waits.
                c.gc += 1_000;
                return c.gcMillis();
            }

            @Override
            public void sleep(long nanos) throws InterruptedException {
                c.units = records[0];
                c.sleep(nanos);
            }
        };
        // Each unit's record takes 1 ms of the send's work.
        BuildTable.Summary s = BuildTable.write(app, (build, json) -> {
            records[0]++;
            c.now += MS;
        }, BuildTable.DROP, BuildTable.MAX_RECORD_BYTES, BuildTable.MAX_MAP_BYTES, BuildTable.MAX_MAP_STEPS,
                new BuildTable.Pacer(collecting));
        assertEquals(100, s.records);
        assertEquals("one read at the start, one after each unit", 101, c.gcReads);
        // The work of K units at a time: after every Kth unit's record.
        assertEquals(100 / K, c.sleeps.size());
        for (int i = 0; i < c.sleeps.size(); i++) {
            assertEquals(K * MS, (long) c.sleeps.get(i));
            assertEquals((i + 1) * K, (int) c.sleptAt.get(i));
        }
    }

    @Test
    public void writeTellsThePacerItsClassFilesForTheDeadline() throws Exception {
        // 100 classes of 0.9 s work each: 90 s, past the 20 s deadline from the first, so never a wait. A Pacer
        // not told how many class files come would wait through its first 20 s.
        List<BuildTable.ClassFile> app = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            app.add(new BuildTable.ClassFile(BuildTableBudgetTest.name(i),
                    BuildTableBudgetTest.gen(BuildTableBudgetTest.name(i), i, 3)));
        }
        Clock c = new Clock();
        BuildTable.Pacer.Clock slow = new BuildTable.Pacer.Clock() {
            @Override
            public long nanoTime() {
                return c.nanoTime();
            }

            @Override
            public long gcMillis() {
                // Read once a unit: a second more of collection.
                c.gc += 1_000;
                return c.gcMillis();
            }

            @Override
            public void sleep(long nanos) throws InterruptedException {
                c.sleep(nanos);
            }
        };
        // Each unit's record takes 0.9 s of the send's work.
        BuildTable.Summary s = BuildTable.write(app, (build, json) -> c.now += 900 * MS, BuildTable.DROP,
                BuildTable.MAX_RECORD_BYTES, BuildTable.MAX_MAP_BYTES, BuildTable.MAX_MAP_STEPS,
                new BuildTable.Pacer(slow));
        assertEquals(100, s.records);
        assertEquals(0, c.sleeps.size());
    }
}
