package io.keploy.dedup;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JobGate sees the runs of the app's scheduled jobs and message listeners, so
 * that a test whose window one ran in says so: JaCoCo's probes carry no thread,
 * so the lines a Quartz job, a Spring {@code @Scheduled} method, or a Spring
 * Kafka/RabbitMQ/JMS message listener runs while a test runs land in that
 * test's hits. A test's record names the jobs and listeners whose runs
 * overlapped its window, from its START to the read of its hits at END, in
 * {@code contaminated}, and Keploy does not judge such a test.
 *
 * <p>Lock-free hot path: uses concurrent sets/queues without global
 * {@code synchronized} monitors (safe for virtual threads on JDK 21-23), caches
 * listener container attribution (topic/queue/destination + listener bean) per
 * container instance, and is a fast no-op when Keploy is unarmed.
 *
 * <p>Expired handed-off runs ({@link #handedJobMaxNanos}) stop propagating
 * attribution to newly chained hops, but remain tracked while they are still
 * executing so every window opened before {@link #exit(Object)} actually runs
 * is marked contaminated ({@code job-tail:<name>}).
 *
 * <p>Public only for the classes ServerEntries changes, which call it from
 * their own packages; not an API.
 */
public final class JobGate {

    private static final Logger LOGGER = Logger.getLogger(KeployDedupAgent.class.getName());
    // The most job names a record carries; past them, one more name says
    // how many more there were ("+3").
    static final int NAMES = 8;
    // The most distinct names a window keeps to count: an app has a few
    // jobs, and a window past this many counts each further run as one more.
    static final int KEPT = 1024;
    // Maximum duration a handed-off async task (enterAs) keeps attributing
    // chained hops before it is marked expired; while still running (until
    // exit() actually runs), every window opened is marked "job-tail:<name>".
    static volatile long handedJobMaxNanos = TimeUnit.SECONDS.toNanos(30);
    private static final Set<String> EXPIRED_LOGGED = ConcurrentHashMap.newKeySet();

    // Lock-free set of active runs (including expired still-running runs until
    // exit() actually runs). Publish-then-scan ordering with volatile `window`
    // guarantees no concurrent run is missed when a window opens.
    private static final Set<Run> RUNNING = ConcurrentHashMap.newKeySet();
    private static volatile Window window;
    // The run this thread is in: a job's own, or that of a task a job's run
    // handed off; null on any other thread.
    private static final ThreadLocal<Run> CURRENT = new ThreadLocal<>();

    // Per-instance cached listener attribution names (topic/queue/destination
    // + listener bean class) keyed by weak identity so repeated messages incur
    // zero reflection and zero String allocation.
    private static final ConcurrentHashMap<WeakIdentityKey, String> LISTENER_NAME_CACHE =
            new ConcurrentHashMap<>();
    private static final ReferenceQueue<Object> DEAD_LISTENERS = new ReferenceQueue<>();

    private JobGate() {
    }

    static void arm() {
        RequestGate.arm();
    }

    static int activeRunCount() {
        return RUNNING.size();
    }

    static boolean hasExpiredRunning() {
        if (RUNNING.isEmpty()) {
            return false;
        }
        long now = System.nanoTime();
        for (Run run : RUNNING) {
            if (run.isExpired(now)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Counts a job's run as going on, in the window of the test running now
     * if one is open. Never throws: the job runs whatever happens here.
     * No-op when Keploy is unarmed.
     *
     * @param job the object whose {@code run()} runs the job
     * @param kind "quartz", "scheduled", "kafka", or "listener"
     * @return the token to pass to {@link #exit(Object)} when it is done
     */
    public static Object enter(Object job, String kind) {
        if (!RequestGate.isArmed()) {
            return RequestGate.enterUnarmed();
        }
        Run run = null;
        try {
            run = new Run(name(job, kind), CURRENT.get(), false, 0L);
            begin(run);
            if ("kafka".equals(kind) || "listener".equals(kind)) {
                // An async message listener firing during a round may be consuming
                // a message produced by an earlier HTTP test whose window already
                // closed; fail closed on asyncExecutors so earlier windows are not
                // hard-removed without their downstream listener coverage.
                KeployDedupAgent.noteObservedUntrackedAsyncLibrary(
                        "async message listener (" + run.name + ")", true);
            } else if ("scheduled".equals(kind) || "quartz".equals(kind)) {
                // A scheduled job or Quartz run firing during a round may be
                // draining an outbox or queue written by an earlier HTTP test
                // whose window already closed; fail closed on asyncExecutors.
                KeployDedupAgent.noteObservedUntrackedAsyncLibrary(
                        "scheduled job (" + run.name + ")", true);
            }
        } catch (Throwable t) {
            // The job runs on. A run counted as going on before this (an
            // OutOfMemoryError on the job's thread as the window counts it)
            // is still handed back, so that its exit counts it done: else
            // every later window would name it.
        }
        return run;
    }

    // enterAs counts a task a job's run handed off as a run of that job, as
    // enter does, from when the task starts; exit counts it done. A first-hop
    // task handed off directly from a scheduled job or listener starts a fresh
    // handedJobMaxNanos budget when it begins running (so queue wait time in a
    // busy pool does not eat into its budget); chained hops inherit the first
    // hop's original deadline. Even if a chained hop starts after its deadline,
    // it is still tracked as an expired Run ("job-tail:<name>") until exit()
    // runs so overlapping windows are honestly flagged while runningAttribution()
    // stops propagating further.
    static Object enterAs(Attribution attribution) {
        if (attribution == null || !RequestGate.isArmed()) {
            return null;
        }
        Run run = null;
        try {
            long now = System.nanoTime();
            long deadline = attribution.hasDeadline
                    ? attribution.expiresAtNanos
                    : now + handedJobMaxNanos;
            run = new Run(attribution.name, CURRENT.get(), true, deadline);
            begin(run);
        } catch (Throwable t) {
            // As in enter: the task runs on, and a run counted is handed back.
        }
        return run;
    }

    // markUntracked records an untracked async hand-off originated by a job's
    // run (e.g., an unknown executor or expired job fan-out) on the currently
    // open window (if any) and requests tail contamination on subsequent
    // windows.
    static void markUntracked(String reason) {
        markUntracked(reason, 0L);
    }

    static void markUntracked(String reason, long extraTailNanos) {
        if (reason == null || reason.isEmpty() || !RequestGate.isArmed()) {
            return;
        }
        Window active = window;
        if (active != null) {
            active.mark(reason);
        }
        RequestGate.noteExternalUntrackedTail(reason, extraTailNanos);
    }

    // Marks the currently open JobGate window without starting a blind
    // time-based tail when the caller is tracking a concrete handle's liveness.
    static void markUntrackedWindowOnly(String reason) {
        if (reason == null || reason.isEmpty() || !RequestGate.isArmed()) {
            return;
        }
        Window active = window;
        if (active != null) {
            active.mark(reason);
        }
    }

    // expiredAttribution returns an already-expired Attribution if this thread
    // is currently executing an expired handed-off job Run. Verified executors
    // wrap hand-offs with this expired Attribution so multi-hop self-resubmitting
    // loops and long fan-out tasks keep marking overlapping windows "job-tail:"
    // until they actually exit, while JobGate.running() still returns null.
    static Attribution expiredAttribution() {
        Run run = CURRENT.get();
        if (run == null) {
            return null;
        }
        long now = System.nanoTime();
        while (run != null) {
            if (!run.over && run.isExpired(now)) {
                return new Attribution(run.name, true, run.expiresAtNanos);
            }
            run = run.outer;
        }
        return null;
    }

    // expiredRunningName returns the job name if this thread is currently
    // executing an expired handed-off job Run.
    static String expiredRunningName() {
        Attribution expired = expiredAttribution();
        return expired == null ? null : expired.name;
    }

    // detachThread restores CURRENT without removing the Run from RUNNING, so
    // a CompletableFuture's synchronous dependent stages keep JobGate tracking
    // active until whenComplete calls exit(token).
    static void detachThread(Object token) {
        if (token instanceof Run) {
            Run run = (Run) token;
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

    // begin counts run as going on, in the window open now if any, and as
    // the run this thread is in until its exit. Publish-then-read with open():
    // RUNNING.add(run) happens-before reading volatile `window`; open() writes
    // volatile `window` before scanning RUNNING.
    private static void begin(Run run) {
        RUNNING.add(run);
        CURRENT.set(run);
        Window active = window;
        if (active != null) {
            active.mark(run.isExpired(System.nanoTime()) ? "job-tail:" + run.name : run.name);
        }
    }

    // running is the name of the job whose run this thread is in (the job's
    // own, or a non-expired task it handed off), or null when it is in none.
    static String running() {
        Attribution attr = runningAttribution();
        return attr == null ? null : attr.name;
    }

    // runningAttribution returns the active job name and optional carried
    // deadline: a direct job run returns hasDeadline=false so the first hop
    // starts its handedJobMaxNanos budget when it begins running; a handed-off
    // task carries its existing deadline unchanged (hasDeadline=true) so
    // chained re-submissions can never extend attribution past hop 1's bound.
    static Attribution runningAttribution() {
        Run run = CURRENT.get();
        if (run == null) {
            return null;
        }
        long now = System.nanoTime();
        while (run != null && (run.over || run.isExpired(now))) {
            run = run.outer;
        }
        if (run == null) {
            return null;
        }
        return run.handed
                ? new Attribution(run.name, true, run.expiresAtNanos)
                : new Attribution(run.name, false, 0L);
    }

    // Attribution carries a job's name and (for chained hops) its hop-1
    // nanoTime expiry across async hand-offs.
    static final class Attribution {

        final String name;
        final boolean hasDeadline;
        final long expiresAtNanos;

        Attribution(String name, boolean hasDeadline, long expiresAtNanos) {
            this.name = name;
            this.hasDeadline = hasDeadline;
            this.expiresAtNanos = expiresAtNanos;
        }
    }

    /**
     * Counts the run {@link #enter(Object, String)} returned the token of as
     * done.
     *
     * @param token what {@link #enter(Object, String)} returned
     */
    public static void exit(Object token) {
        if (token == null) {
            return;
        }
        if (RequestGate.isUnarmedToken(token)) {
            RequestGate.exitUnarmed();
            return;
        }
        if (token instanceof Run) {
            Run run = (Run) token;
            RUNNING.remove(run);
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
    // here on lands in the test. Handed-off tasks that have run past
    // handedJobMaxNanos are NOT forgotten while they are still executing:
    // they remain in RUNNING until exit() runs and mark every window opened
    // while still alive with "job-tail:<name>". Returns true if any expired
    // job-tail run was added to the newly opened window.
    static boolean open() {
        RequestGate.arm();
        long now = System.nanoTime();
        List<String> expired = null;
        boolean hasJobTail = false;
        Window opened = new Window();
        window = opened;
        for (Run run : RUNNING) {
            if (run.isExpired(now)) {
                hasJobTail = true;
                opened.mark("job-tail:" + run.name);
                if (EXPIRED_LOGGED.add(run.name)) {
                    if (expired == null) {
                        expired = new ArrayList<>();
                    }
                    expired.add(run.name);
                }
                continue;
            }
            opened.mark(run.name);
        }
        if (expired != null) {
            for (String jobName : expired) {
                LOGGER.log(Level.INFO, "Keploy dedup: handed-off task from " + jobName
                        + " is still executing after " + TimeUnit.NANOSECONDS.toMillis(handedJobMaxNanos)
                        + " ms; marking overlapping windows with job-tail:" + jobName + " until it finishes");
            }
        }
        return hasJobTail;
    }

    // close ends the window, after its END read the test's hits, and names
    // the jobs whose runs overlapped it: at most NAMES, then "+N" for the N
    // more; empty when none ran in it or no window was open.
    static List<String> close() {
        Window closed = window;
        window = null;
        return closed == null ? Collections.<String>emptyList() : closed.names();
    }

    // name is how a record names a job: "quartz:<group>.<name>" for a
    // Quartz job, "scheduled:<class>.<method>" for a Spring @Scheduled
    // method, cached "kafka:<topics/listener>" / "listener:<queues/dest/listener>"
    // for a message listener, or the kind and the class running it when that
    // cannot be read: the run still counts.
    static String name(Object job, String kind) {
        if ("kafka".equals(kind) || "listener".equals(kind)) {
            return cachedListenerName(job, kind);
        }
        String name;
        try {
            if ("quartz".equals(kind)) {
                name = quartzJob(job);
            } else if ("scheduled".equals(kind)) {
                name = scheduledMethod(job);
            } else {
                name = job == null ? "?" : job.getClass().getName();
            }
        } catch (Throwable t) {
            name = job == null ? "?" : job.getClass().getName();
        }
        return kind + ":" + name;
    }

    private static String cachedListenerName(Object job, String kind) {
        if (job == null) {
            return kind + ":?";
        }
        drainDeadListeners();
        LookupKey probe = new LookupKey(job);
        String cached = LISTENER_NAME_CACHE.get(probe);
        if (cached != null) {
            return cached;
        }
        String computed = computeListenerName(job, kind);
        WeakIdentityKey key = new WeakIdentityKey(job, DEAD_LISTENERS);
        String existing = LISTENER_NAME_CACHE.putIfAbsent(key, computed);
        return existing != null ? existing : computed;
    }

    private static void drainDeadListeners() {
        for (Reference<?> ref; (ref = DEAD_LISTENERS.poll()) != null; ) {
            LISTENER_NAME_CACHE.remove(ref);
        }
    }

    private static String computeListenerName(Object container, String kind) {
        String destination = null;
        String listener = null;
        try {
            if ("kafka".equals(kind)) {
                Object props = invokeNoArg(container, "getContainerProperties");
                if (props == null) {
                    props = readField(container, "containerProperties");
                }
                if (props == null) {
                    Object outer = readField(container, "this$0");
                    if (outer != null) {
                        props = invokeNoArg(outer, "getContainerProperties");
                    }
                }
                if (props != null) {
                    destination = extractTopics(props);
                    listener = extractListenerClass(invokeNoArg(props, "getMessageListener"));
                }
                if (destination == null) {
                    destination = formatStrings(readField(container, "topics"));
                }
                if (listener == null) {
                    Object l = readField(container, "genericListener");
                    if (l == null) {
                        l = readField(container, "listener");
                    }
                    listener = extractListenerClass(l);
                }
            } else {
                // Spring AMQP: getQueueNames() + getMessageListener()
                destination = formatStrings(invokeNoArg(container, "getQueueNames"));
                if (destination == null) {
                    // Spring JMS: getDestinationName() + getMessageListener()
                    Object destName = invokeNoArg(container, "getDestinationName");
                    if (destName instanceof String && !((String) destName).isEmpty()) {
                        destination = (String) destName;
                    }
                }
                listener = extractListenerClass(invokeNoArg(container, "getMessageListener"));
            }
        } catch (Throwable ignored) {
            // Fall through to container class name below.
        }
        if (destination != null && listener != null) {
            return kind + ":" + destination + "@" + listener;
        }
        if (destination != null) {
            return kind + ":" + destination;
        }
        if (listener != null) {
            return kind + ":" + listener;
        }
        return kind + ":" + container.getClass().getName();
    }

    private static String extractTopics(Object containerProps) {
        String topics = formatStrings(invokeNoArg(containerProps, "getTopics"));
        if (topics != null) {
            return topics;
        }
        Object pattern = invokeNoArg(containerProps, "getTopicPattern");
        if (pattern != null) {
            String s = pattern.toString();
            if (!s.isEmpty()) {
                return s;
            }
        }
        Object tpos = invokeNoArg(containerProps, "getTopicPartitions");
        if (tpos != null && tpos.getClass().isArray()) {
            int len = Array.getLength(tpos);
            if (len > 0) {
                StringBuilder sb = new StringBuilder();
                int limit = Math.min(len, 4);
                for (int i = 0; i < limit; i++) {
                    Object elem = Array.get(tpos, i);
                    Object topic = elem == null ? null : invokeNoArg(elem, "getTopic");
                    String part = topic != null ? topic.toString() : String.valueOf(elem);
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(part);
                }
                if (len > limit) {
                    sb.append(",+").append(len - limit);
                }
                return sb.toString();
            }
        }
        return null;
    }

    private static String formatStrings(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String[]) {
            String[] arr = (String[]) value;
            if (arr.length == 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            int limit = Math.min(arr.length, 4);
            for (int i = 0; i < limit; i++) {
                if (arr[i] == null || arr[i].isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(arr[i]);
            }
            if (arr.length > limit) {
                sb.append(",+").append(arr.length - limit);
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        if (value instanceof Collection) {
            Collection<?> col = (Collection<?>) value;
            if (col.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (Object item : col) {
                if (item == null) {
                    continue;
                }
                if (count < 4) {
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(item.toString());
                }
                count++;
            }
            if (count > 4) {
                sb.append(",+").append(count - 4);
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return null;
    }

    private static String extractListenerClass(Object listener) {
        if (listener == null) {
            return null;
        }
        // Unwrap Spring messaging adapters (getBean() / getDelegate()).
        Object target = invokeNoArg(listener, "getBean");
        if (target == null) {
            target = invokeNoArg(listener, "getDelegate");
        }
        Object chosen = target != null ? target : listener;
        return chosen.getClass().getName();
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(methodName);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object readField(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
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

    private static final class WeakIdentityKey extends WeakReference<Object> {

        private final int hash;

        private WeakIdentityKey(Object referent, ReferenceQueue<Object> queue) {
            super(referent, queue);
            this.hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            Object mine = get();
            if (mine == null) {
                return false;
            }
            if (obj instanceof WeakIdentityKey) {
                return mine == ((WeakIdentityKey) obj).get();
            }
            if (obj instanceof LookupKey) {
                return mine == ((LookupKey) obj).target;
            }
            return false;
        }
    }

    private static final class LookupKey {

        private final Object target;
        private final int hash;

        private LookupKey(Object target) {
            this.target = target;
            this.hash = System.identityHashCode(target);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof WeakIdentityKey) {
                return target == ((WeakIdentityKey) obj).get();
            }
            if (obj instanceof LookupKey) {
                return target == ((LookupKey) obj).target;
            }
            return false;
        }
    }

    private static final class Run {

        private final String name;
        // The run the thread was in when this one began, or null.
        private final Run outer;
        // True for tasks handed off via enterAs (bounded by expiresAtNanos).
        private final boolean handed;
        private final long expiresAtNanos;
        // Whether its exit came; volatile because deferred CF release can
        // finish on another thread via whenComplete.
        private volatile boolean over;

        private Run(String name, Run outer, boolean handed, long expiresAtNanos) {
            this.name = name;
            this.outer = outer;
            this.handed = handed;
            this.expiresAtNanos = expiresAtNanos;
        }

        private boolean isExpired(long now) {
            return handed && (now - expiresAtNanos) >= 0;
        }
    }

    // Window is the job names that ran in a test's window, in the order
    // they first ran in it. Lock-free so JobGate.enter never synchronizes.
    private static final class Window {

        // Declared as Set<String> (backed by ConcurrentHashMap.newKeySet) so
        // JobGateTest's OOM-injection test can still substitute a custom Set.
        private final Set<String> names = ConcurrentHashMap.newKeySet();
        private final ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();
        private final AtomicInteger kept = new AtomicInteger();
        // Runs of names past KEPT, each counted as one more name.
        private final AtomicInteger more = new AtomicInteger();

        private void mark(String name) {
            if (kept.get() < KEPT) {
                if (names.add(name)) {
                    int n = kept.incrementAndGet();
                    if (n <= KEPT) {
                        order.add(name);
                    } else {
                        names.remove(name);
                        kept.decrementAndGet();
                        more.incrementAndGet();
                    }
                }
            } else if (!names.contains(name)) {
                more.incrementAndGet();
            }
        }

        private List<String> names() {
            List<String> out = new ArrayList<>(NAMES + 1);
            int total = 0;
            for (String name : order) {
                if (out.size() < NAMES) {
                    out.add(name);
                }
                total++;
            }
            int left = (total - out.size()) + more.get();
            if (left > 0) {
                out.add("+" + left);
            }
            return out;
        }
    }
}
