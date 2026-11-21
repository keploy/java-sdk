package io.keploy.dedup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * JobGate sees the runs of the app's scheduled jobs, so that a test whose
 * window a job ran in says so: JaCoCo's probes carry no thread, so the lines
 * a Quartz job or a Spring {@code @Scheduled} method runs while a test runs
 * land in that test's hits. A test's record names the jobs whose runs
 * overlapped its window, from its START to the read of its hits at END, in
 * {@code contaminated}, and Keploy does not judge such a test.
 *
 * <p>ServerEntries makes Quartz's {@code JobRunShell.run()} and Spring's
 * {@code ScheduledMethodRunnable.run()} call {@link #enter(Object, String)}
 * where a job's run starts and {@link #exit(Object)} where it is done,
 * however it returns or throws. A run is in a window when it ran when the
 * window opened or started before it closed: so a job that started before a
 * test and still runs at its START counts in it, and one that ended before
 * the START does not (the START clears what it ran).
 *
 * <p>The work a job's run hands to a Spring TaskExecutor (an event listener
 * on an async multicaster, a task submitted to a ThreadPoolTaskExecutor,
 * an {@code @Async} method) counts as a run of that job, named the same,
 * from when the task starts until it returns or throws: RequestGate's
 * {@link RequestGate#hand(Object, Runnable)} wraps it as the job's when the
 * thread handing it off is in the job's run, so a test whose window the task runs in is marked
 * with the job even when the job's own run ended before the test began.
 * What that task hands off counts the same way.
 *
 * <p>Jobs the app runs on threads of its own, a custom executor or a
 * {@code java.util.Timer} are not seen, and neither are message listeners or
 * a request's async work that runs past its END.
 *
 * <p>Public only for the classes ServerEntries changes, which call it from
 * their own packages; not an API.
 */
public final class JobGate {

    // The most job names a record carries; past them, one more name says
    // how many more there were ("+3").
    static final int NAMES = 8;
    // The most distinct names a window keeps to count: an app has a few
    // jobs, and a window past this many counts each further run as one more.
    static final int KEPT = 1024;

    private static final Object LOCK = new Object();
    // The runs going on now, and the window of the test running (null
    // between two tests). Both guarded by LOCK: a job's run and a test's
    // START or END take it once each, so a run that starts as a window
    // opens is either seen running by the opening or sees the window open.
    private static final Set<Run> RUNNING = new HashSet<>();
    private static Window window;
    // The run this thread is in: a job's own, or that of a task a job's run
    // handed off; null on any other thread.
    private static final ThreadLocal<Run> CURRENT = new ThreadLocal<>();

    private JobGate() {
    }

    /**
     * Counts a job's run as going on, in the window of the test running now
     * if one is open. Never throws: the job runs whatever happens here.
     *
     * @param job the object whose {@code run()} runs the job
     * @param kind "quartz" or "scheduled"
     * @return the token to pass to {@link #exit(Object)} when it is done
     */
    public static Object enter(Object job, String kind) {
        Run run = null;
        try {
            run = new Run(name(job, kind), CURRENT.get());
            begin(run);
        } catch (Throwable t) {
            // The job runs on. A run counted as going on before this (an
            // OutOfMemoryError on the job's thread as the window counts it)
            // is still handed back, so that its exit counts it done: else
            // every later window would name it.
        }
        return run;
    }

    // enterAs counts a task a job's run handed off as a run of that job, as
    // enter does, from when the task starts; exit counts it done. Never
    // throws, as enter.
    static Object enterAs(String name) {
        Run run = null;
        try {
            run = new Run(name, CURRENT.get());
            begin(run);
        } catch (Throwable t) {
            // As in enter: the task runs on, and a run counted is handed back.
        }
        return run;
    }

    // begin counts run as going on, in the window open now if any, and as
    // the run this thread is in until its exit.
    private static void begin(Run run) {
        synchronized (LOCK) {
            RUNNING.add(run);
            if (window != null) {
                window.mark(run.name);
            }
        }
        CURRENT.set(run);
    }

    // running is the name of the job whose run this thread is in (the job's
    // own, or a task it handed off), or null when it is in none.
    static String running() {
        Run run = CURRENT.get();
        return run == null ? null : run.name;
    }

    /**
     * Counts the run {@link #enter(Object, String)} returned the token of as
     * done.
     *
     * @param token what {@link #enter(Object, String)} returned
     */
    public static void exit(Object token) {
        if (token instanceof Run) {
            Run run = (Run) token;
            synchronized (LOCK) {
                RUNNING.remove(run);
            }
            run.over = true;
            // The thread is back in the run it was in before, if any. A run
            // exited out of order leaves the thread in the run it is in, and
            // never puts it back in a run that is over: else each task the
            // thread hands off from then on would name that job.
            if (CURRENT.get() != run) {
                return;
            }
            Run back = run.outer;
            while (back != null && back.over) {
                back = back.outer;
            }
            if (back == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(back);
            }
        }
    }

    // open starts the window of a test, before its START clears what ran
    // before it: each run going on now counts in it, as what it runs from
    // here on lands in the test.
    static void open() {
        synchronized (LOCK) {
            Window opened = new Window();
            for (Run run : RUNNING) {
                opened.mark(run.name);
            }
            window = opened;
        }
    }

    // close ends the window, after its END read the test's hits, and names
    // the jobs whose runs overlapped it: at most NAMES, then "+N" for the N
    // more; empty when none ran in it or no window was open.
    static List<String> close() {
        Window closed;
        synchronized (LOCK) {
            closed = window;
            window = null;
        }
        return closed == null ? Collections.<String>emptyList() : closed.names();
    }

    // name is how a record names a job: "quartz:<group>.<name>" for a
    // Quartz job, "scheduled:<class>.<method>" for a Spring @Scheduled
    // method, or the kind and the class running it when that cannot be read
    // (an unknown version, a class not laid out as expected): the run still
    // counts.
    static String name(Object job, String kind) {
        String name;
        try {
            name = "quartz".equals(kind) ? quartzJob(job) : scheduledMethod(job);
        } catch (Throwable t) {
            name = job == null ? "?" : job.getClass().getName();
        }
        return kind + ":" + name;
    }

    // quartzJob is the "<group>.<name>" of the job a JobRunShell runs: its
    // context's JobDetail's key (Quartz 2), or its full name (Quartz 1).
    private static String quartzJob(Object shell) throws ReflectiveOperationException {
        for (Class<?> type = shell.getClass(); type != null; type = type.getSuperclass()) {
            Field field;
            try {
                field = type.getDeclaredField("jec");
            } catch (NoSuchFieldException e) {
                continue;
            }
            field.setAccessible(true);
            Object context = field.get(shell);
            Object detail = context.getClass().getMethod("getJobDetail").invoke(context);
            Method key;
            try {
                key = detail.getClass().getMethod("getKey");
            } catch (NoSuchMethodException e) {
                return detail.getClass().getMethod("getFullName").invoke(detail).toString();
            }
            return key.invoke(detail).toString();
        }
        throw new NoSuchFieldException(shell.getClass().getName() + ".jec");
    }

    // scheduledMethod is the "<class>.<method>" a ScheduledMethodRunnable
    // runs: the class that declares the method, not a proxy of it.
    private static String scheduledMethod(Object runnable) throws ReflectiveOperationException {
        Method target = (Method) runnable.getClass().getMethod("getMethod").invoke(runnable);
        return target.getDeclaringClass().getName() + "." + target.getName();
    }

    private static final class Run {

        private final String name;
        // The run the thread was in when this one began, or null.
        private final Run outer;
        // Whether its exit came; only its own thread reads or sets it.
        private boolean over;

        private Run(String name, Run outer) {
            this.name = name;
            this.outer = outer;
        }
    }

    // Window is the job names that ran in a test's window, in the order
    // they first ran in it.
    private static final class Window {

        private final Set<String> names = new LinkedHashSet<>();
        // Runs of names past KEPT, each counted as one more name.
        private int more;

        private void mark(String name) {
            if (names.size() < KEPT) {
                names.add(name);
            } else if (!names.contains(name)) {
                more++;
            }
        }

        private List<String> names() {
            List<String> out = new ArrayList<>(Math.min(names.size(), NAMES) + 1);
            int left = names.size() + more;
            for (String name : names) {
                if (out.size() == NAMES) {
                    break;
                }
                out.add(name);
                left--;
            }
            if (left > 0) {
                out.add("+" + left);
            }
            return out;
        }
    }
}
