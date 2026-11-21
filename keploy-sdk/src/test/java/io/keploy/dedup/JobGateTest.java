package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A test's window names each scheduled job whose run overlapped it: one
 * that started in it, or that ran when it opened; never one that ended
 * before it opened or started after it closed.
 */
public class JobGateTest {

    private final List<Object> open = new ArrayList<>();

    @Before
    public void setUp() {
        JobGate.close();
    }

    @After
    public void tearDown() {
        // Last in, first out, as a job's runs end on its thread: so the
        // thread is back in no run.
        for (int i = open.size() - 1; i >= 0; i--) {
            JobGate.exit(open.get(i));
        }
        JobGate.close();
        assertNull("the test thread is in no job's run", JobGate.running());
    }

    // handOff has a run of a job hand task off, as Spring's changed
    // executors do (RequestGate.hand), and returns what they would run; the
    // run is over when it returns.
    private static Runnable handOff(String group, String name, Runnable task) {
        Object run = JobGate.enter(new QuartzShell(group, name), "quartz");
        try {
            Runnable handed = RequestGate.hand(task);
            assertNotSame("a task a job's run hands off is the job's", task, handed);
            return handed;
        } finally {
            JobGate.exit(run);
        }
    }

    @Test(timeout = 30000)
    public void aTaskAJobsRunHandsOffCountsAsThatJobsRunWhereverItRuns() throws Exception {
        Runnable handed = handOff("reports", "nightly", () -> { });
        assertNull("the job's thread is in no run once the job returned", JobGate.running());
        // The job's run ended before the window; the task it handed off runs in it, on another thread.
        JobGate.open();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(handed).get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(Collections.singletonList("quartz:reports.nightly"), JobGate.close());
        JobGate.open();
        assertEquals("the task is done: a later window does not name it", Collections.<String>emptyList(),
                JobGate.close());
    }

    @Test(timeout = 30000)
    public void aJobsTaskStillRunningWhenTheWindowOpensIsNamed() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> runningIn = new AtomicReference<>();
        Runnable handed = handOff("reports", "held", () -> {
            runningIn.set(JobGate.running());
            started.countDown();
            await(release);
        });
        Thread thread = new Thread(handed, "listener");
        thread.start();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        assertEquals("the task's thread is in a run of the job", "quartz:reports.held", runningIn.get());
        JobGate.open();
        release.countDown();
        thread.join(10000);
        assertEquals(Collections.singletonList("quartz:reports.held"), JobGate.close());
    }

    @Test(timeout = 30000)
    public void aJobsTaskThatReturnedBeforeTheWindowOpenedIsNotNamed() throws Exception {
        Thread thread = new Thread(handOff("reports", "early", () -> { }), "listener");
        thread.start();
        thread.join(10000);
        JobGate.open();
        assertEquals(Collections.<String>emptyList(), JobGate.close());
    }

    @Test(timeout = 30000)
    public void whatAJobsTaskHandsOffIsTheJobsToo() throws Exception {
        AtomicReference<Runnable> inner = new AtomicReference<>();
        Runnable outer = handOff("reports", "chain", () -> inner.set(RequestGate.hand(() -> { })));
        Thread thread = new Thread(outer, "listener");
        thread.start();
        thread.join(10000);
        assertTrue("the task's hand-off is wrapped", inner.get() != null);
        JobGate.open();
        Thread last = new Thread(inner.get(), "listener-2");
        last.start();
        last.join(10000);
        assertEquals(Collections.singletonList("quartz:reports.chain"), JobGate.close());
    }

    @Test
    public void aJobsTaskRunOnItsOwnThreadLeavesTheThreadInTheJobsRun() {
        Object run = enter(new QuartzShell("reports", "inline"), "quartz");
        // A caller-runs pool, or a sync executor, runs the task on the job's thread.
        RequestGate.hand(() -> { }).run();
        assertEquals("back in the job's own run", "quartz:reports.inline", JobGate.running());
        // A job's run in a job's run (one calls the other's run()) gives the thread back the outer run too.
        JobGate.exit(JobGate.enter(new QuartzShell("reports", "nested"), "quartz"));
        assertEquals("back in the outer run", "quartz:reports.inline", JobGate.running());
        Runnable task = () -> { };
        assertNotSame("what it hands off after is still the job's", task, RequestGate.hand(task));
        JobGate.exit(run);
        assertNull(JobGate.running());
    }

    @Test(timeout = 30000)
    public void endDoesNotWaitForAJobsTaskAndAFutureOrACallableStaysOne() throws Exception {
        RequestGate.open();
        JobGate.open();
        Object run = enter(new QuartzShell("reports", "future"), "quartz");
        FutureTask<String> future = new FutureTask<>(() -> "done");
        Runnable handed = RequestGate.hand(future);
        Callable<String> call = () -> "called";
        Callable<String> handedCall = RequestGate.hand(call);
        JobGate.exit(run);
        assertTrue("a Future stays one", handed instanceof Future);
        assertNotSame(call, handedCall);
        assertEquals("END waits for no job's task", 0, RequestGate.running());
        assertTrue(RequestGate.await(TimeUnit.SECONDS.toNanos(5)));
        // An executor that did not take them: nothing to count done.
        RequestGate.dropped(handed, future);
        ((Future<?>) handed).cancel(false);
        assertTrue("its cancel reaches the task", future.isCancelled());
        assertEquals("called", handedCall.call());
        assertEquals(Collections.singletonList("quartz:reports.future"), JobGate.close());
    }

    @Test
    public void aTaskHandedOffOutsideAnyRunOrRequestIsTheTaskItself() {
        Runnable task = () -> { };
        Callable<String> call = () -> "x";
        try {
            assertSame(task, RequestGate.hand(task));
            assertSame(call, RequestGate.hand(call));
        } catch (RuntimeException e) {
            throw new AssertionError("hand threw off a job's run", e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Object enter(Object job, String kind) {
        Object token = JobGate.enter(job, kind);
        open.add(token);
        return token;
    }

    @Test
    public void aRunThatStartsInTheWindowIsNamed() {
        JobGate.open();
        JobGate.exit(enter(new QuartzShell("reports", "nightly"), "quartz"));
        assertEquals(Collections.singletonList("quartz:reports.nightly"), JobGate.close());
    }

    @Test
    public void aRunGoingOnWhenTheWindowOpensIsNamedEvenIfItEndsBeforeTheClose() {
        Object token = enter(new QuartzShell("reports", "nightly"), "quartz");
        JobGate.open();
        JobGate.exit(token);
        assertEquals(Collections.singletonList("quartz:reports.nightly"), JobGate.close());
    }

    @Test
    public void aRunThatEndedBeforeTheWindowOpenedIsNotNamed() {
        JobGate.exit(enter(new QuartzShell("reports", "nightly"), "quartz"));
        JobGate.open();
        assertEquals(Collections.<String>emptyList(), JobGate.close());
    }

    @Test
    public void aRunThatStartsAfterTheWindowClosedIsNotNamedInItOrTheNext() {
        JobGate.open();
        assertEquals(Collections.<String>emptyList(), JobGate.close());
        JobGate.exit(enter(new QuartzShell("reports", "nightly"), "quartz"));
        assertEquals("no window was open", Collections.<String>emptyList(), JobGate.close());
        JobGate.open();
        assertEquals(Collections.<String>emptyList(), JobGate.close());
    }

    @Test
    public void eachWindowIsItsOwn() {
        JobGate.open();
        JobGate.exit(enter(new QuartzShell("a", "one"), "quartz"));
        JobGate.open();
        JobGate.exit(enter(new QuartzShell("b", "two"), "quartz"));
        assertEquals(Collections.singletonList("quartz:b.two"), JobGate.close());
    }

    @Test
    public void namesAreKeptOnceInTheOrderTheyFirstRanThenCountedPastEight() {
        JobGate.open();
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 11; i++) {
                JobGate.exit(enter(new QuartzShell("g", "job" + i), "quartz"));
            }
        }
        List<String> names = JobGate.close();
        assertEquals(JobGate.NAMES + 1, names.size());
        for (int i = 0; i < JobGate.NAMES; i++) {
            assertEquals("quartz:g.job" + i, names.get(i));
        }
        assertEquals("+3", names.get(JobGate.NAMES));

        JobGate.open();
        for (int i = 0; i < JobGate.NAMES; i++) {
            JobGate.exit(enter(new QuartzShell("g", "job" + i), "quartz"));
        }
        assertEquals("exactly eight names need no count", JobGate.NAMES, JobGate.close().size());
    }

    @Test
    public void pastTheKeptNamesEachRunOfANewNameCountsAsOneMore() {
        JobGate.open();
        for (int i = 0; i < JobGate.KEPT; i++) {
            JobGate.exit(enter(new QuartzShell("g", "job" + i), "quartz"));
        }
        // A kept name again counts nothing; a name past them counts each run.
        JobGate.exit(enter(new QuartzShell("g", "job0"), "quartz"));
        JobGate.exit(enter(new QuartzShell("g", "late"), "quartz"));
        JobGate.exit(enter(new QuartzShell("g", "late"), "quartz"));
        List<String> names = JobGate.close();
        assertEquals("quartz:g.job0", names.get(0));
        assertEquals("+" + (JobGate.KEPT + 2 - JobGate.NAMES), names.get(JobGate.NAMES));
    }

    @Test
    public void aQuartzJobIsNamedByItsKeyOrItsFullName() {
        assertEquals("quartz:reports.nightly", JobGate.name(new QuartzShell("reports", "nightly"), "quartz"));
        // A shell of a class of its own, its context in its superclass.
        assertEquals("quartz:a.b", JobGate.name(new QuartzShell("a", "b") { }, "quartz"));
        assertEquals("quartz:old.job", JobGate.name(new QuartzOneShell(), "quartz"));
    }

    @Test
    public void aScheduledMethodIsNamedByTheClassThatDeclaresIt() throws Exception {
        Method method = Digest.class.getMethod("send");
        assertEquals("scheduled:" + Digest.class.getName() + ".send",
                JobGate.name(new ScheduledRunnable(method), "scheduled"));
    }

    @Test
    public void aJobWhoseNameCannotBeReadIsNamedByItsClassAndStillCounts() {
        assertEquals("quartz:" + Object.class.getName(), JobGate.name(new Object(), "quartz"));
        assertEquals("scheduled:" + Thrower.class.getName(), JobGate.name(new Thrower(), "scheduled"));
        assertEquals("quartz:" + QuartzShell.class.getName(),
                JobGate.name(new QuartzShell("a", "b").withNoContext(), "quartz"));
        assertEquals("scheduled:?", JobGate.name(null, "scheduled"));

        JobGate.open();
        JobGate.exit(enter(new Thrower(), "scheduled"));
        assertEquals(Collections.singletonList("scheduled:" + Thrower.class.getName()), JobGate.close());
    }

    @Test
    public void runsExitedOutOfOrderLeaveTheThreadInNoRun() {
        Object outer = enter(new QuartzShell("a", "outer"), "quartz");
        Object inner = enter(new QuartzShell("a", "inner"), "quartz");
        JobGate.exit(outer);
        assertEquals("the thread is still in the inner run", "quartz:a.inner", JobGate.running());
        JobGate.exit(inner);
        assertNull("the inner run's exit does not put the thread back in the outer run, which is over",
                JobGate.running());
        JobGate.open();
        assertEquals(Collections.<String>emptyList(), JobGate.close());
    }

    @Test
    public void anExitOfNoRunDoesNothing() {
        JobGate.exit(null);
        JobGate.exit("not a token");
        Object token = enter(new QuartzShell("a", "b"), "quartz");
        JobGate.exit(token);
        JobGate.exit(token);
        JobGate.open();
        assertEquals(Collections.<String>emptyList(), JobGate.close());
    }

    @Test
    public void aRunWhoseCountInTheWindowFailsStillEnds() throws Exception {
        JobGate.open();
        failMarks();
        // The Error stands in for an OutOfMemoryError on the job's thread
        // once its run counts as going on: the job runs on, and its exit
        // must still count it done.
        Object token = enter(new QuartzShell("a", "b"), "quartz");
        JobGate.exit(token);
        JobGate.close();
        JobGate.open();
        assertEquals("no job runs", Collections.<String>emptyList(), JobGate.close());
    }

    // failMarks makes the open window's count of a name throw an Error.
    private static void failMarks() throws ReflectiveOperationException {
        Field windowField = JobGate.class.getDeclaredField("window");
        windowField.setAccessible(true);
        Object window = windowField.get(null);
        Field names = window.getClass().getDeclaredField("names");
        names.setAccessible(true);
        names.set(window, new LinkedHashSet<String>() {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean add(String name) {
                throw new OutOfMemoryError("a stand-in");
            }
        });
    }

    @Test
    public void twoRunsOfOneJobAreTwoRuns() {
        Object first = enter(new QuartzShell("a", "b"), "quartz");
        Object second = enter(new QuartzShell("a", "b"), "quartz");
        JobGate.exit(first);
        JobGate.open();
        assertEquals("the second still runs", Collections.singletonList("quartz:a.b"), JobGate.close());
        JobGate.exit(second);
        JobGate.open();
        assertEquals(Collections.<String>emptyList(), JobGate.close());
    }

    @Test
    public void runsOnManyThreadsAreAllSeenAndAllEnd() throws Exception {
        final int threads = 8;
        final int runs = 2000;
        JobGate.open();
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final String name = "t" + t;
            workers[t] = new Thread(() -> {
                for (int i = 0; i < runs; i++) {
                    JobGate.exit(JobGate.enter(new QuartzShell("g", name), "quartz"));
                }
            });
            workers[t].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
        List<String> names = JobGate.close();
        Collections.sort(names);
        assertEquals(Arrays.asList("quartz:g.t0", "quartz:g.t1", "quartz:g.t2", "quartz:g.t3", "quartz:g.t4",
                "quartz:g.t5", "quartz:g.t6", "quartz:g.t7"), names);
        JobGate.open();
        assertEquals("none still runs", Collections.<String>emptyList(), JobGate.close());
    }

    @Test
    public void closingWithNoWindowNamesNone() {
        assertTrue(JobGate.close().isEmpty());
    }

    // Stand-ins for what JobGate reads of Quartz and Spring, laid out as
    // they are: a JobRunShell's protected field jec, its context's
    // JobDetail, and a Quartz 2 JobDetail's key or a Quartz 1 one's full
    // name; a ScheduledMethodRunnable's getMethod().

    public static class QuartzShell {

        protected Context jec;

        QuartzShell(String group, String name) {
            this.jec = new Context(new Detail(new Key(group, name)));
        }

        QuartzShell withNoContext() {
            jec = null;
            return this;
        }
    }

    public static class QuartzOneShell {

        protected OldContext jec = new OldContext();
    }

    public static class Context {

        private final Detail detail;

        Context(Detail detail) {
            this.detail = detail;
        }

        public Detail getJobDetail() {
            return detail;
        }
    }

    public static class Detail {

        private final Key key;

        Detail(Key key) {
            this.key = key;
        }

        public Key getKey() {
            return key;
        }
    }

    public static class Key {

        private final String group;
        private final String name;

        Key(String group, String name) {
            this.group = group;
            this.name = name;
        }

        @Override
        public String toString() {
            return group + "." + name;
        }
    }

    public static class OldContext {

        public OldDetail getJobDetail() {
            return new OldDetail();
        }
    }

    public static class OldDetail {

        public String getFullName() {
            return "old.job";
        }
    }

    public static class ScheduledRunnable {

        private final Method method;

        ScheduledRunnable(Method method) {
            this.method = method;
        }

        public Method getMethod() {
            return method;
        }
    }

    public static class Digest {

        public void send() {
        }
    }

    public static class Thrower {

        public Method getMethod() {
            throw new IllegalStateException("not laid out as Spring's");
        }
    }
}
