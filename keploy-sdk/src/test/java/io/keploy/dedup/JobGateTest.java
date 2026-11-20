package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
        for (Object token : open) {
            JobGate.exit(token);
        }
        JobGate.close();
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
