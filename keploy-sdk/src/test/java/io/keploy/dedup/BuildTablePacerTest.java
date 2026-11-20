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
 * time, only after a collection, never more than its allowance of 0.8 ms a class file, saved up to a second,
 * and never past the table's deadline.
 */
public class BuildTablePacerTest {

    private static final long MS = 1_000_000L;
    private static final long A = BuildTable.Pacer.ALLOWANCE_NANOS_PER_CLASS;
    private static final long MIN = BuildTable.Pacer.MIN_WAIT_NANOS;
    // The class files whose allowance first reaches the smallest wait, after a wait used it all.
    private static final long K = (MIN + A - 1) / A;

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
        run(p, c, 1_000, 50_000, 0);
        assertEquals("no wait before a collection", 0, c.sleeps.size());
        // A 4 ms collection during the next unit: 800 ms of the send's time, less that unit's 50 us.
        c.now += 50_000;
        c.gc += 4;
        c.units++;
        p.unitDone(1);
        assertEquals(1, c.sleeps.size());
        assertEquals(800 * MS - 50_000, (long) c.sleeps.get(0));
        run(p, c, 1_000, 50_000, 0);
        assertEquals("paid off: no wait after it", 1, c.sleeps.size());
        assertEquals(800 * MS - 50_000, p.waitedNanos());
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
    public void theWaitsAreBoundedByTheAllowanceOfEachClassFile() throws Exception {
        Clock c = new Clock();
        BuildTable.Pacer p = new BuildTable.Pacer(c);
        long start = c.now;
        // A JVM that collects 10 ms in every unit owes 2 s a unit: only the allowance bounds the waits.
        run(p, c, 50_000, 20_000, 10);
        long slept = c.slept();
        assertTrue("slept " + slept, slept <= 50_000 * A);
        assertTrue("slept " + slept, slept > 50_000 * A - MIN);
        assertEquals(slept, p.waitedNanos());
        assertTrue("the table's time", c.now - start <= 50_000 * A + 50_000 * 20_000L);
        assertEquals("40 s at 50,000 classes", 40_000 * MS, 50_000 * A);
        // Over any run of units, the waits add up to at most a second plus the allowance of its class files.
        long[] sum = new long[c.units + 1];
        for (int i = 0; i < c.sleeps.size(); i++) {
            sum[c.sleptAt.get(i)] += c.sleeps.get(i);
        }
        long low = 0;
        long prefix = 0;
        for (int j = 1; j <= c.units; j++) {
            prefix += sum[j];
            long at = prefix - j * A;
            assertTrue("waits over a run of units, up to unit " + j, at - low <= BuildTable.Pacer.MAX_BANK_NANOS);
            low = Math.min(low, at);
        }
    }

    @Test
    public void aQuietStretchSavesUpAtMostASecond() throws Exception {
        Clock c = new Clock();
        BuildTable.Pacer p = new BuildTable.Pacer(c);
        run(p, c, 5_000, 20_000, 0);
        // 100 ms of collection owes 20 s; 4 s of allowance went by, but a second of it was kept.
        c.now += 20_000;
        c.gc += 100;
        c.units++;
        p.unitDone(1);
        assertEquals(1, c.sleeps.size());
        assertEquals(BuildTable.Pacer.MAX_BANK_NANOS, (long) c.sleeps.get(0));
        // Then a wait each time the allowance reaches the smallest wait: K class files' worth every K of them.
        run(p, c, 100, 20_000, 0);
        assertEquals(1 + 100 / K, c.sleeps.size());
        for (int i = 1; i < c.sleeps.size(); i++) {
            assertEquals(K * A, (long) c.sleeps.get(i));
        }
    }

    @Test
    public void aUnitOfManyClassFilesEarnsItsAllowance() throws Exception {
        Clock c = new Clock();
        BuildTable.Pacer p = new BuildTable.Pacer(c);
        c.now += 20_000;
        c.gc += 1_000;
        p.unitDone(300);
        assertEquals(1, c.sleeps.size());
        assertEquals(300 * A, (long) c.sleeps.get(0));
    }

    @Test
    public void aSleepsOverrunComesOffTheAllowance() throws Exception {
        Clock c = new Clock();
        c.overrun = 5 * MS;
        BuildTable.Pacer p = new BuildTable.Pacer(c);
        long start = c.now;
        run(p, c, 10_000, 20_000, 10);
        long waited = c.now - start - 10_000 * 20_000L;
        assertEquals(waited, p.waitedNanos());
        // The time asleep, overruns included, stays within the allowance (and one overrun past it).
        assertTrue("waited " + waited, waited <= 10_000 * A + c.overrun);
        assertTrue("waited " + waited, waited > 10_000 * A - MIN - c.overrun);
    }

    @Test
    public void anInterruptEndsTheSendAndIsKept() throws Exception {
        try {
            // During a wait.
            Clock c = new Clock();
            BuildTable.Pacer p = new BuildTable.Pacer(c);
            c.interrupt = true;
            try {
                run(p, c, 100, 20_000, 10);
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
        // 50,000 class files of 0.5 ms work each (25 s) in a JVM that collects 10 ms in each: the allowance
        // alone would wait 40 s, a table of 65 s. The deadline holds it to 50 s, waiting all the room left.
        Clock c = new Clock();
        BuildTable.Pacer p = new BuildTable.Pacer(c);
        p.begin(50_000);
        long start = c.now;
        run(p, c, 50_000, MS / 2, 10);
        long took = c.now - start;
        assertTrue("took " + took, took <= BuildTable.Pacer.deadlineNanos(50_000));
        assertTrue("waited " + p.waitedNanos(), p.waitedNanos() > 25_000 * MS - 2 * MIN);
        // Not told its class files, a Pacer still waits no later than 50 s into the table.
        Clock d = new Clock();
        BuildTable.Pacer q = new BuildTable.Pacer(d);
        start = d.now;
        run(q, d, 50_000, MS / 2, 10);
        assertTrue("woke at " + (d.lastWake - start), d.lastWake - start <= BuildTable.Pacer.deadlineNanos(0));
        assertTrue("waited " + q.waitedNanos(), q.waitedNanos() > 25_000 * MS);
        assertEquals(50_000 * MS, BuildTable.Pacer.deadlineNanos(50_000));
        assertEquals("1 ms a class file past 50,000", 100_000 * MS, BuildTable.Pacer.deadlineNanos(100_000));
        assertEquals(50_000 * MS, BuildTable.Pacer.deadlineNanos(10));
    }

    @Test
    public void theRateLeavesOutTheTimeBeforeTheAnalysis() throws Exception {
        // The index's read takes 10 s before the analysis begins; then 50,000 class files of 0.5 ms work each
        // (25 s), 10 ms of collection in each. The rate is the analysis's own (0.5 ms a class file), so the first
        // wait comes as soon as the allowance reaches the smallest wait; the deadline still counts the 10 s, so
        // the waits fill the 15 s left and no more.
        Clock c = new Clock();
        BuildTable.Pacer p = new BuildTable.Pacer(c);
        long start = c.now;
        c.now += 10_000 * MS;
        p.begin(50_000);
        run(p, c, 50_000, MS / 2, 10);
        assertEquals("the first wait as soon as the allowance allows", K, (long) c.sleptAt.get(0));
        assertTrue("took " + (c.now - start), c.now - start <= BuildTable.Pacer.deadlineNanos(50_000));
        assertTrue("waited " + p.waitedNanos(), p.waitedNanos() > 15_000 * MS - 2 * MIN);
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
        assertTrue("slept " + slept, slept <= 10_000 * A);
        assertTrue("slept " + slept, slept > 10_000 * A - MIN);
    }

    @Test
    public void aCollectorBeanThatFailsEndsThePacing() throws Exception {
        Clock c = new Clock();
        BuildTable.Pacer p = new BuildTable.Pacer(c);
        run(p, c, 100, 20_000, 10);
        int before = c.sleeps.size();
        assertTrue(before > 0);
        c.failGc = new UnsupportedOperationException("row");
        run(p, c, 1, 20_000, 10);
        c.failGc = null;
        run(p, c, 1_000, 20_000, 10);
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
    public void writePacesAfterEachUnitByItsClassFiles() throws Exception {
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
                // Each read finds a second more of collection: the allowance alone bounds the waits.
                c.gc += 1_000;
                return c.gcMillis();
            }

            @Override
            public void sleep(long nanos) throws InterruptedException {
                c.units = records[0];
                c.sleep(nanos);
            }
        };
        BuildTable.Summary s = BuildTable.write(app, (build, json) -> records[0]++, BuildTable.DROP,
                BuildTable.MAX_RECORD_BYTES, BuildTable.MAX_MAP_BYTES, BuildTable.MAX_MAP_STEPS,
                new BuildTable.Pacer(collecting));
        assertEquals(100, s.records);
        assertEquals("one read at the start, one after each unit", 101, c.gcReads);
        // The allowance of K class files at a time: after every Kth unit's record.
        assertEquals(100 / K, c.sleeps.size());
        for (int i = 0; i < c.sleeps.size(); i++) {
            assertEquals(K * A, (long) c.sleeps.get(i));
            assertEquals((i + 1) * K, (int) c.sleptAt.get(i));
        }
    }

    @Test
    public void writeTellsThePacerItsClassFilesForTheDeadline() throws Exception {
        // 100 classes of 0.9 s work each: 90 s, past the 50 s deadline from the first, so never a wait. A Pacer
        // not told how many class files come would wait through its first 50 s.
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
                // Read once a unit: a unit's work, and a second more of collection.
                c.now += 900 * MS;
                c.gc += 1_000;
                return c.gcMillis();
            }

            @Override
            public void sleep(long nanos) throws InterruptedException {
                c.sleep(nanos);
            }
        };
        BuildTable.Summary s = BuildTable.write(app, (build, json) -> { }, BuildTable.DROP,
                BuildTable.MAX_RECORD_BYTES, BuildTable.MAX_MAP_BYTES, BuildTable.MAX_MAP_STEPS,
                new BuildTable.Pacer(slow));
        assertEquals(100, s.records);
        assertEquals(0, c.sleeps.size());
    }
}
