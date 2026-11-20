package io.keploy.dedup;

import com.google.gson.Gson;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;
import org.jacoco.core.tools.ExecFileLoader;
import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Collects per-testcase JaCoCo coverage and streams the executed probe set per
 * class ({className -&gt; [probeIdx]}) back to Keploy Enterprise.
 */
public final class KeployDedupAgent {

    private static final Logger LOGGER = Logger.getLogger(KeployDedupAgent.class.getName());
    private static final Gson GSON = new Gson();

    // --- package-private wire-format helpers (single source of the collector
    // channel contract; exercised by unit tests without reaching into the
    // private collector) ---

    // toDedupJson serializes a COV payload exactly as the live agent does (same
    // GSON + DedupPayload), so tests can lock the wire shape. The historical
    // "executedLinesByFile" key now carries {vmClassName -> [probeIdx]}.
    static String toDedupJson(String id, Map<String, List<Integer>> probesByClass) {
        return toDedupJson(id, probesByClass, Collections.<String>emptyList());
    }

    static String toDedupJson(String id, Map<String, List<Integer>> probesByClass, List<String> classMismatch) {
        return toDedupJson(id, probesByClass, classMismatch, false);
    }

    static String toDedupJson(String id, Map<String, List<Integer>> probesByClass, List<String> classMismatch,
            boolean windowCut) {
        return toDedupJson(id, probesByClass, classMismatch, windowCut, Collections.<String>emptyList());
    }

    static String toDedupJson(String id, Map<String, List<Integer>> probesByClass, List<String> classMismatch,
            boolean windowCut, List<String> contaminated) {
        return GSON.toJson(new DedupPayload(id,
                new TestCoverage(probesByClass, classMismatch, windowCut, contaminated)));
    }

    // encodeBytecodeFrame builds the bytecode frame the SDK writes to the
    // collector channel. Non-null zip => the full "CLASSES <b64tag> <b64manifest>
    // <b64zip>" frame (sent once per connection); null zip => a lightweight
    // "MANIFEST <b64tag> <b64manifest>" update. Kept as one helper so the frame
    // contract that k8s-proxy parses stays in a single, testable place.
    static String encodeBytecodeFrame(String buildTag, String manifestJson, byte[] zip) {
        java.util.Base64.Encoder enc = java.util.Base64.getEncoder();
        String b64Tag = enc.encodeToString(buildTag.getBytes(StandardCharsets.UTF_8));
        String b64Manifest = enc.encodeToString(manifestJson.getBytes(StandardCharsets.UTF_8));
        if (zip != null) {
            return "CLASSES " + b64Tag + " " + b64Manifest + " " + enc.encodeToString(zip);
        }
        return "MANIFEST " + b64Tag + " " + b64Manifest;
    }

    // The unix control socket's path. Not final only so tests can bind one of
    // their own; the agent never changes it.
    static volatile String controlSocketPath = "/tmp/coverage_control.sock";
    // The unix data socket each test's coverage is sent on. Not final for the
    // same reason.
    static volatile String dataSocketPath = "/tmp/coverage_data.sock";
    private static final String DEFAULT_JACOCO_HOST = "127.0.0.1";
    private static final int DEFAULT_JACOCO_PORT = 36320;
    private static final int SOCKET_TIMEOUT_MILLIS = 3000;
    private static final int SOCKET_BACKLOG = 50;
    // How long a test's END waits for its requests to finish by default.
    static final long DEFAULT_END_WAIT_MILLIS = 200;
    // A longer wait would cost each test more than the SDK's overhead
    // target. Keploy must wait longer than this for an END's answer.
    static final long MAX_END_WAIT_MILLIS = 2000;
    private static final AtomicBoolean END_WAIT_INVALID_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean END_WAIT_CAPPED_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean NOT_COUNTED_LOGGED = new AtomicBoolean(false);
    // The answer to a command not taken (a START that could not clear the
    // counters, an END with no START for that test, as after a restart, an
    // END whose scan lost hits it had cleared): nothing is published
    // for that test, so Keploy must not use its coverage. On the unix socket
    // it starts the whole answer (UNIX_REPLY_IGNORED); on TCP it is a frame
    // naming the test, and ACK still follows (CoverageTcpClient).
    static final String IGNORED_REPLY = "IGNORED";
    // The answers on the unix control socket: one line for every command the
    // SDK reads, written before it closes the connection. ACK says it took the
    // command: it cleared the counters for a START, or sent the test's coverage
    // for an END. IGNORED says it read the command and did not take it.
    // proto=2 says this SDK answers every command it reads, so Keploy can read
    // a close with no answer as a command not taken: on macOS a connection
    // closed with its command unread gives Keploy no error, only the close.
    static final String UNIX_REPLY_TAKEN = "ACK proto=2";
    static final String UNIX_REPLY_IGNORED = IGNORED_REPLY + " proto=2";
    // The unix control command a Keploy that reads BACKGROUND records sends
    // to get them: ACK when this SDK will send them to the receiver on the
    // data socket now, IGNORED when it sees no hits between tests (JaCoCo's
    // probes not read in place) or no receiver is there. An older SDK
    // answers IGNORED too, as to any line that is no command.
    static final String BACKGROUND_COMMAND = "BACKGROUND";

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean(false);
    // The active transport worker: a CommandServer (unix, local/docker) or a
    // CoverageTcpClient (TCP, k8s), so stop() is transport-agnostic.
    private static volatile CoverageWorker coverageWorker;
    // The worker's thread. stop() waits for it to end, and start() does too
    // when stop() gave up waiting (a command still running): until it ends it
    // owns its socket, and a second worker on the same path would lose its
    // socket file to the first one's close. Before the first start() it is a
    // thread never started, which join() does not wait for.
    private static volatile Thread coverageThread = new Thread("keploy-java-dedup-none");
    // The index the running agent was started on, for indexReport.
    private static volatile CoverageIndex startedIndex;
    // How long stop(), and a start() after it, wait for the worker's thread.
    // Not final: the tests shorten it.
    static volatile long workerStopWaitMillis = 5000;
    // How long the first START waits for the warmup before it goes on
    // without it: well inside the 30 s Keploy's k8s collector waits for an
    // answer by default. Not final: the tests shorten it.
    static volatile long warmupWaitMillis = 10000;
    // Makes the warmup's thread. Not final: a test makes one that cannot
    // start.
    static volatile ThreadFactory warmupThreads = runnable -> {
        Thread thread = new Thread(runnable, "keploy-java-dedup-warmup");
        thread.setDaemon(true);
        return thread;
    };

    private KeployDedupAgent() {
    }

    /**
     * JVM entrypoint used when the SDK is attached with {@code -javaagent}.
     *
     * @param agentArgs optional Java agent arguments
     * @param instrumentation JVM instrumentation handle
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        start(new CoverageIndex(), instrumentation);
    }

    /**
     * JVM entrypoint used when the SDK is attached to an already running JVM.
     *
     * @param agentArgs optional Java agent arguments
     * @param instrumentation JVM instrumentation handle
     */
    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        start();
    }

    /**
     * Starts the background control socket listener used by Keploy replay.
     *
     * @return {@code true} when the agent is running or was already started
     */
    public static boolean start() {
        return start(new CoverageIndex(), null);
    }

    // start starts the agent on index. From premain (instrumentation not
    // null) it also starts the JaCoCo agent keploy-sdk.jar carries, after the
    // socket is bound and before the worker that reads commands runs: a START
    // that comes earlier waits in the socket's backlog, so it never resets a
    // JaCoCo not yet started, or warms up app classes before JaCoCo can
    // instrument them. A start that finds the agent started already starts
    // no JaCoCo: the call that started the agent did.
    private static boolean start(CoverageIndex index, Instrumentation instrumentation) {
        if (isDisabled()) {
            return false;
        }
        if (!STARTED.compareAndSet(false, true)) {
            return true;
        }
        if (!awaitWorkerThread()) {
            STARTED.set(false);
            log(Level.SEVERE, "Java dedup is not started: the worker stopped before is still running a command", null);
            return false;
        }

        startedIndex = index;
        CoverageCollector collector = new CoverageCollector(
                new JacocoClient(resolveHost(), resolvePort()), index, instrumentation);

        CoverageWorker worker;
        String threadName;
        String endpoint = resolveEndpoint();
        if (endpoint != null) {
            // k8s: the collector lives in a different pod, so there is no shared
            // /tmp. Dial the collector's TCP endpoint and run the same protocol.
            // NOTE: host:port is split on the LAST ':', which assumes an IPv4 or
            // DNS host (the only forms the proxy advertises, e.g.
            // "k8s-proxy.keploy.svc.cluster.local.:36340"). A bare or bracketed
            // IPv6 literal would mis-split; add bracket handling if that ever ships.
            int idx = endpoint.lastIndexOf(':');
            if (idx <= 0 || idx == endpoint.length() - 1) {
                STARTED.set(false);
                log(Level.SEVERE, "Invalid KEPLOY_COVERAGE_ENDPOINT '" + endpoint + "', expected host:port", null);
                return false;
            }
            String host = endpoint.substring(0, idx);
            int port;
            try {
                port = Integer.parseInt(endpoint.substring(idx + 1).trim());
            } catch (NumberFormatException e) {
                STARTED.set(false);
                log(Level.SEVERE, "Invalid port in KEPLOY_COVERAGE_ENDPOINT '" + endpoint + "'", e);
                return false;
            }
            CoverageTcpClient client = new CoverageTcpClient(collector, host, port);
            worker = client;
            coverageWorker = client;
            threadName = "keploy-java-dedup-tcp";
            log(Level.INFO, "Keploy dedup: TCP transport enabled, will dial collector at " + host + ":" + port, null);
        } else {
            // local/docker: SDK owns the unix control socket and pushes coverage
            // back over the unix data socket on a pod-shared /tmp.
            //
            // Bind here, on the caller's thread, not on the accept thread:
            // premain runs before the app's main, so the socket is listening
            // before any app code can answer a health check, and Keploy's
            // first START finds it. Bound on the accept thread, it could come
            // up after the app answered and Keploy had fired its first tests,
            // whose START and END then reached no one: their coverage was lost.
            CommandServer server = new CommandServer(collector,
                    new CoveragePublisher(new File(dataSocketPath), index), new File(controlSocketPath));
            try {
                server.bind();
            } catch (Throwable t) {
                // Never let premain fail the app's start: run it without dedup.
                // bind() has closed the socket it opened.
                STARTED.set(false);
                log(Level.SEVERE, "Java dedup control socket server is unavailable", t);
                return false;
            }
            worker = server;
            coverageWorker = server;
            threadName = "keploy-java-dedup-control";
        }

        if (instrumentation != null) {
            // JaCoCo's transformer first: it cannot retransform, so the JVM
            // runs it before ServerEntries, which can, and so in the order
            // they were added where the jar may not retransform: JaCoCo
            // instruments, and ids, a server's class as the index read it,
            // and ServerEntries counts in JaCoCo's copy.
            startJacoco(instrumentation, index);
            countRequests(instrumentation, IndexReport.JACOCO_ATTACHED.equals(index.jacoco));
        } else {
            // Attached to a running JVM, or started from the app's code:
            // ServerEntries cannot change a class, so no job run is seen.
            log(Level.INFO, "Keploy dedup does not see the app's scheduled jobs' runs: keploy-sdk.jar was not"
                    + " started as a -javaagent, so a test a Quartz job or a Spring @Scheduled method ran in is"
                    + " not marked contaminated in its record", null);
        }
        Thread thread = new Thread(worker, threadName);
        thread.setDaemon(true);
        coverageThread = thread;
        thread.start();
        registerShutdownHook();
        return true;
    }

    // countRequests makes the requests of the app's server count in
    // RequestGate (ServerEntries), so that a test's END waits for them to
    // finish, unless KEPLOY_JAVA_END_WAIT_MS is 0, and the runs of its
    // scheduled jobs count in JobGate, so that a test a job ran in is marked
    // contaminated, whatever the wait. From premain, before the
    // app's main loads its server, and after startJacoco. ServerEntries can
    // retransform when keploy-sdk.jar's manifest lets it (Can-Retransform-
    // Classes), so the JVM runs it after JaCoCo's transformer even when a
    // JaCoCo agent was attached after keploy-sdk.jar. In a jar that does not
    // let it, it runs in the order it was added, before such a JaCoCo's,
    // which then ids the changed server class: classMismatch names it when
    // the index holds it, and the log says so when the JVM has a JaCoCo
    // agent of its own (jacocoAttached).
    private static void countRequests(Instrumentation instrumentation, boolean jacocoAttached) {
        boolean requests = endWaitMillis() != 0;
        try {
            boolean retransformable = instrumentation.isRetransformClassesSupported();
            if (!retransformable && jacocoAttached) {
                log(Level.WARNING, "Keploy dedup: the jar keploy-sdk.jar runs from has no Can-Retransform-Classes:"
                        + " true in its manifest, or this JVM cannot retransform, so a JaCoCo agent attached after"
                        + " it instruments the " + (requests ? "server and scheduler" : "scheduler")
                        + " classes it changes, and each test's classMismatch names them when Keploy measures"
                        + " them. Attach JaCoCo before it, or run keploy-sdk.jar as released",
                        null);
            }
            instrumentation.addTransformer(new ServerEntries(requests), retransformable);
        } catch (RuntimeException e) {
            log(Level.WARNING, "Keploy dedup does not count the app's requests or see its scheduled jobs' runs:"
                    + " their classes could not be changed to count them", e);
        }
    }

    // notCounted says, once, why a test's END does not wait for its
    // requests: the wait is off, or no server counts them (uncounted names
    // a server the app loaded whose requests are not counted, as Netty).
    static String notCounted(long endWaitMillis, List<String> uncounted) {
        String why = endWaitMillis == 0 ? "KEPLOY_JAVA_END_WAIT_MS is 0"
                : !uncounted.isEmpty() ? "the app's server is " + uncounted.get(0)
                        + ", whose requests keploy-sdk does not count"
                : "no Tomcat, Jetty or Undertow request entry loaded after keploy-sdk.jar started"
                        + " (a Netty or WebFlux server, one started before the SDK, or the SDK not"
                        + " started as a -javaagent)";
        return "Keploy dedup: a test's END does not wait for its requests to finish: " + why
                + ". Code a request runs after its response is sent can land after the test's END";
    }

    // endWaitMillis is how long a test's END waits for the requests that
    // came in since its START to finish: KEPLOY_JAVA_END_WAIT_MS (or
    // keploy.java.end.wait.ms), 200 by default, 2000 at most; 0 turns the
    // wait and the count off.
    static long endWaitMillis() {
        String value = envOrProperty("KEPLOY_JAVA_END_WAIT_MS", "keploy.java.end.wait.ms", "").trim();
        if (value.isEmpty()) {
            return DEFAULT_END_WAIT_MILLIS;
        }
        try {
            long millis = Long.parseLong(value);
            if (millis > MAX_END_WAIT_MILLIS) {
                if (END_WAIT_CAPPED_LOGGED.compareAndSet(false, true)) {
                    log(Level.WARNING, "Keploy dedup: KEPLOY_JAVA_END_WAIT_MS is " + millis + ", so a test's END"
                            + " waits up to " + MAX_END_WAIT_MILLIS + " ms, its most: a longer wait would cost"
                            + " each test more than the agent's overhead allows", null);
                }
                return MAX_END_WAIT_MILLIS;
            }
            if (millis >= 0) {
                return millis;
            }
        } catch (NumberFormatException e) {
            // Said below.
        }
        if (END_WAIT_INVALID_LOGGED.compareAndSet(false, true)) {
            log(Level.WARNING, "Keploy dedup: KEPLOY_JAVA_END_WAIT_MS is not a number of milliseconds (" + value
                    + "), so a test's END waits up to " + DEFAULT_END_WAIT_MILLIS + " ms", null);
        }
        return DEFAULT_END_WAIT_MILLIS;
    }

    // startJacoco starts the JaCoCo agent keploy-sdk.jar carries, with
    // includes= the app's classes in index (EmbeddedJacoco), unless the JVM
    // has a JaCoCo agent of its own or the index holds no class. It runs in
    // premain, before the app's main loads any app class, so it reads the
    // index there: the app's classes found from the launch, with their
    // bytes, which the first START read before.
    private static void startJacoco(Instrumentation instrumentation, CoverageIndex index) {
        try {
            index.jacoco = IndexReport.JACOCO_NONE;
            String attached = EmbeddedJacoco.attached(ClassLoader.getSystemClassLoader(),
                    ManagementFactory.getRuntimeMXBean().getInputArguments());
            if (attached != null) {
                index.jacoco = IndexReport.JACOCO_ATTACHED;
                index.jacocoVersion = attached;
                log(Level.INFO, "Keploy dedup: this JVM has a JaCoCo agent attached, so Keploy reads coverage"
                        + " from it, and it instruments every class its own options take in. Attach only"
                        + " keploy-sdk.jar to have JaCoCo instrument the app's own classes alone", null);
                return;
            }
            List<String> names = new ArrayList<>();
            for (ClassEntry entry : index.entries()) {
                names.add(entry.className);
            }
            String includes = EmbeddedJacoco.includes(index.isAppOnly(), names);
            if (includes == null) {
                // Said at WARNING, as the index read was: no class can pass
                // the index's cut, so JaCoCo would only slow the app down.
                log(Level.WARNING, "Keploy dedup starts no JaCoCo agent: it found no class to measure", null);
                return;
            }
            String version = EmbeddedJacoco.start(instrumentation, EmbeddedJacoco.options(includes));
            index.includes = includes;
            index.jacoco = IndexReport.JACOCO_EMBEDDED;
            index.jacocoVersion = version;
            log(Level.INFO, "Keploy dedup: started JaCoCo " + version + (includes.isEmpty()
                    ? " on every class"
                    : " on the app's " + names.size() + " classes alone (includes="
                    + (includes.length() > 300 ? includes.substring(0, 300) + "..." : includes) + ")"), null);
        } catch (Throwable t) {
            // Never let premain fail the app's start: run it unmeasured. This
            // must stay Throwable: loading or initializing JaCoCo's PreMain
            // can throw an Error (a LinkageError or an
            // ExceptionInInitializerError), which would abort the JVM. A jar
            // without the agent is agentJar's IllegalStateException, logged
            // here with it.
            log(Level.WARNING, "Keploy dedup could not start the JaCoCo agent keploy-sdk.jar carries, so no"
                    + " test's coverage is measured: attach jacocoagent.jar with -javaagent to measure it", t);
        }
    }

    // indexReport is the INDEX report of the index the agent was last started
    // on, as sent to Keploy, or null before any start (tests).
    static String indexReport() {
        CoverageIndex index = startedIndex;
        return index == null ? null : index.report().toJson();
    }

    /**
     * Returns whether the background control socket listener is active.
     *
     * @return {@code true} when the agent has already been started
     */
    public static boolean isStarted() {
        return STARTED.get();
    }

    /**
     * Stops the background control socket listener.
     */
    public static void stop() {
        CoverageWorker worker = coverageWorker;
        coverageWorker = null;
        if (worker != null) {
            worker.close();
            // Wait for the worker to end, so that a start() after this one
            // does not overlap it. Still running after the wait, it is in a
            // command or in an accept close()'s wake-up did not reach (the
            // socket file was bound again by someone else). Either way it is
            // past the point where closing its socket from here races its
            // accept, so close it: an accept ends, and a command ends on its
            // own once it is answered. A start() waits for it too.
            if (!awaitWorkerThread()) {
                worker.closeNow();
                log(Level.WARNING, "Java dedup worker did not stop within " + workerStopWaitMillis
                        + " ms (a command still running, or its socket file bound again by another process);"
                        + " its socket is closed, and it stops once its command ends", null);
            }
        }
        STARTED.set(false);
    }

    // awaitWorkerThread waits up to workerStopWaitMillis for the last
    // worker's thread to end and says whether it has.
    private static boolean awaitWorkerThread() {
        Thread thread = coverageThread;
        try {
            thread.join(workerStopWaitMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !thread.isAlive();
    }

    private static boolean isDisabled() {
        return isTruthy(System.getenv("KEPLOY_JAVA_DEDUP_DISABLED"))
                || isTruthy(System.getProperty("keploy.java.dedup.disabled"));
    }

    private static void registerShutdownHook() {
        if (!SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    KeployDedupAgent.stop();
                }
            }, "keploy-java-dedup-shutdown"));
        } catch (IllegalStateException ignored) {
            // The VM is already shutting down; the process exit will reclaim resources.
        }
    }

    private static boolean diagnosticsEnabled() {
        return isTruthy(System.getenv("KEPLOY_JAVA_DEDUP_DIAGNOSTICS"))
                || isTruthy(System.getProperty("keploy.java.dedup.diagnostics"));
    }

    private static boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        return "true".equalsIgnoreCase(normalized)
                || "1".equals(normalized)
                || "yes".equalsIgnoreCase(normalized);
    }

    private static String resolveHost() {
        return envOrProperty("KEPLOY_JACOCO_HOST", "keploy.jacoco.host", DEFAULT_JACOCO_HOST);
    }

    private static int resolvePort() {
        String configured = envOrProperty("KEPLOY_JACOCO_PORT", "keploy.jacoco.port",
                String.valueOf(DEFAULT_JACOCO_PORT));
        try {
            return Integer.parseInt(configured);
        } catch (NumberFormatException e) {
            log(Level.SEVERE, "Invalid JaCoCo port '" + configured + "', using " + DEFAULT_JACOCO_PORT, e);
            return DEFAULT_JACOCO_PORT;
        }
    }

    /**
     * Returns the collector's TCP endpoint ("host:port") for k8s mode, or {@code null}
     * to use the unix-socket transport (local/docker). The collector advertises this
     * to the SDK via KEPLOY_COVERAGE_ENDPOINT when app and collector are in different pods.
     */
    private static String resolveEndpoint() {
        String value = envOrProperty("KEPLOY_COVERAGE_ENDPOINT", "keploy.coverage.endpoint", "");
        return value.trim().isEmpty() ? null : value.trim();
    }

    private static String envOrProperty(String envKey, String propertyKey, String defaultValue) {
        String value = System.getenv(envKey);
        if (value == null || value.trim().isEmpty()) {
            value = System.getProperty(propertyKey);
        }
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static String normalizePath(String path) {
        return path.replace(File.separatorChar, '/');
    }

    // Build-constant coverage metadata for one class, serialized into the
    // manifest the offline CoverageReporter consumes. id = CRC64 class id
    // (IndexReport.hexId), probeCount = JaCoCo probe array length. classId
    // is that id as JaCoCo has it, for chosenCopy to compare with a copy's
    // id as a number; transient, so it is never sent.
    private static final class ManifestEntry {
        private String id;
        private int probeCount;
        private transient long classId;
    }

    // fileKey is the file's identity (its device and inode on Linux and
    // macOS), or null when it is gone or the platform has none.
    private static Object fileKey(File file) {
        try {
            return Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .fileKey();
        } catch (IOException e) {
            return null;
        }
    }

    // receiverKey tells apart the receivers Keploy binds, one after another,
    // on the data socket's path: the socket file's identity and the time it
    // was made, as a file system can give a new file the inode of one just
    // removed (and nothing but a new bind changes a socket file's times).
    // Where the platform has no file key, the time alone. Null when there is
    // no file: no receiver to reach.
    static Object receiverKey(File file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return Arrays.asList(attributes.fileKey(), attributes.lastModifiedTime());
        } catch (IOException e) {
            return null;
        }
    }

    private static void deleteSocketFile(File file) {
        if (file.exists() && !file.delete()) {
            log(Level.FINE, "Failed to delete socket file " + file.getAbsolutePath(), null);
        }
    }

    private static void relaxSocketPermissions(File file) {
        if (!file.setReadable(true, false) || !file.setWritable(true, false)) {
            log(Level.FINE, "Failed to relax socket permissions for " + file.getAbsolutePath(), null);
        }
    }

    private static byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(16 * 1024);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void log(Level level, String message, Throwable error) {
        if (error == null) {
            LOGGER.log(level, message);
            return;
        }
        LOGGER.log(level, message, error);
    }

    private static void diagnostic(String message) {
        String formatted = "Java dedup diagnostics: " + message;
        System.err.println(formatted);
        LOGGER.log(Level.INFO, formatted);
    }

    // Phases times the steps of one command for KEPLOY_JAVA_DEDUP_DIAGNOSTICS,
    // so a run on the real app says where its per-test time goes: the probe
    // scan (or JaCoCo's dump and the parse), the filter to the app's classes,
    // the JSON and the send for an END; the warmup and the scan (or JaCoCo's
    // reset) for a START. One line per command,
    // and none when diagnostics are off. A mark only takes the time: report
    // formats every step once the command is done, so no step is charged with
    // the formatting of the one before it.
    private static final class Phases {

        private final boolean on = diagnosticsEnabled();
        private final long began = System.nanoTime();
        private final List<String> names = on ? new ArrayList<>(8) : null;
        private final List<Long> ends = on ? new ArrayList<>(8) : null;

        // mark ends the step named step, which began at the last mark, or when
        // the command did.
        void mark(String step) {
            if (!on) {
                return;
            }
            ends.add(System.nanoTime());
            names.add(step);
        }

        // report says how long the command took, and each of its steps.
        void report(CoverageCommand command) {
            if (!on) {
                return;
            }
            long now = System.nanoTime();
            StringBuilder line = new StringBuilder();
            line.append(command.action).append(' ').append(command.testId)
                    .append(" took ").append(millis(now - began)).append(" ms:");
            long last = began;
            for (int i = 0; i < names.size(); i++) {
                long end = ends.get(i);
                line.append(' ').append(names.get(i)).append('=').append(millis(end - last)).append("ms");
                last = end;
            }
            diagnostic(line.toString());
        }

        static String millis(long nanos) {
            return String.format(Locale.ROOT, "%.2f", nanos / 1e6);
        }
    }

    // A transport worker: run() serves Keploy's commands until close().
    private interface CoverageWorker extends Runnable, Closeable {
        // close asks run() to end; it does not wait for it.
        @Override
        void close();

        // closeNow closes the worker's socket from the caller's thread, for
        // stop() once run() has outlived close().
        void closeNow();
    }

    private static final class CommandServer implements CoverageWorker {

        private final CoverageCollector collector;
        private final CoveragePublisher publisher;
        private final File controlSocket;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Object testCaseLock = new Object();
        private volatile AFUNIXServerSocket serverSocket;
        // The socket file's identity once bound (fileKey), so that run()
        // deletes the file only while it is still this socket's.
        private volatile Object socketFileKey;
        private String activeTestId = "";
        // The id of the test whose END last sent its coverage, set once that
        // record is sent and cleared by every START, and by every END until
        // its record is sent: what the next START clears then ran between
        // two tests. BACKGROUND and lines that are no command leave it (a
        // receiver may ask between an END and a START). Like activeTestId,
        // only the worker thread uses it.
        private String lastEndedTestId;
        // The between-tests hits the last START cleared, for a receiver that
        // asked for them: made into a record and sent once that START's
        // answer is written and its connection closed, off the time Keploy
        // waits for the answer.
        private BackgroundWindow pendingBackground;

        CommandServer(CoverageCollector collector, CoveragePublisher publisher, File controlSocket) {
            this.collector = collector;
            this.publisher = publisher;
            this.controlSocket = controlSocket;
        }

        // bind replaces a stale socket file and listens on the control socket.
        // start() calls it before it returns, so a command sent once start()
        // has returned waits in the backlog for run() instead of being refused.
        // On a failure it closes the socket it opened, as no run() will.
        void bind() throws IOException {
            deleteSocketFile(controlSocket);
            AFUNIXServerSocket socket = AFUNIXServerSocket.newInstance();
            // Not junixsocket's delete on close: it deletes by path, so it
            // deleted the file of a server that had bound the path since.
            socket.setDeleteOnClose(false);
            try {
                socket.bind(AFUNIXSocketAddress.of(controlSocket), SOCKET_BACKLOG);
                socketFileKey = fileKey(controlSocket);
                relaxSocketPermissions(controlSocket);
            } catch (Throwable t) {
                closeQuietly(socket);
                throw t;
            }
            serverSocket = socket;
        }

        // run accepts commands until close(). Only this thread closes the
        // server socket, and it does so once it has left accept: junixsocket's
        // accept holds the socket's raw fd number, and when it finds the
        // socket file gone it closes that number. Closed from another thread
        // while this one was inside accept, the number could already belong
        // to the next server's socket (start() after stop()), which was then
        // closed with its file still there: every command was refused.
        @Override
        public void run() {
            try (AFUNIXServerSocket localServer = serverSocket) {
                // Before any command, when Keploy's receiver is already
                // bound: a START sent meanwhile waits in the socket's
                // backlog. Off premain, as it reads the index's bytes when
                // nothing has yet.
                publisher.sendIndex();
                while (running.get()) {
                    Socket accepted;
                    try {
                        accepted = localServer.accept();
                    } catch (IOException e) {
                        if (!running.get()) {
                            break;
                        }
                        if (localServer.isClosed()) {
                            // junixsocket closes the socket when its file was
                            // deleted or bound again by someone else: no
                            // command can reach it. Said once; looping on it
                            // spun a core and logged without end.
                            STARTED.set(false);
                            log(Level.SEVERE, "Java dedup control socket " + controlSocket.getAbsolutePath()
                                    + " was closed: its file was deleted or replaced, so Keploy's coverage"
                                    + " commands no longer reach this app", e);
                            break;
                        }
                        log(Level.SEVERE, "Failed to accept Java dedup coverage command", e);
                        continue;
                    }
                    // close()'s wake-up is a connection with nothing sent:
                    // handle() answers none, and the loop ends.
                    handle(accepted);
                }
            } catch (Throwable t) {
                STARTED.set(false);
                log(Level.SEVERE, "Java dedup control socket server is unavailable", t);
            }
            // The socket file goes once the socket is closed, only if it is
            // still this socket's: deleted or bound again by someone else, it
            // is theirs. A start() after stop() binds only once this thread
            // has ended (awaitWorkerThread), so it is never the next server's.
            if (Objects.equals(socketFileKey, fileKey(controlSocket))) {
                deleteSocketFile(controlSocket);
            }
        }

        // handle reads one command and answers it before the close: ACK if it
        // was taken, IGNORED if not. Only a connection closed with nothing sent
        // gets no answer, as there is no command to answer.
        private void handle(Socket socket) {
            try (Socket commandSocket = socket;
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(commandSocket.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line == null) {
                    return;
                }

                if (BuildTable.COMMAND.equals(line.trim())) { // the build table: ACK, then sent on its own thread
                    String reply; // or IGNORED, with reason=low_heap when the heap is short
                    try {
                        CoverageIndex index = collector.coverageIndex; // its count only if read: no load here
                        reply = BuildTable.answer(() -> {
                                    List<ClassEntry> read = index.entries;
                                    return read == null ? -1 : read.size();
                                },
                                () -> BuildTable.files(index.entries(), e -> e.className, e -> e.bytes),
                                new File(dataSocketPath));
                    } catch (Throwable t) { // thrown past here, it would end the accept loop: IGNORED instead
                        log(Level.WARNING, "Keploy coverage: the build table was not started", t);
                        reply = UNIX_REPLY_IGNORED;
                    }
                    writeReply(commandSocket.getOutputStream(), reply);
                    return;
                }
                boolean taken;
                try {
                    taken = run(line.trim());
                } catch (RuntimeException e) {
                    // Not taken, and the server goes on: thrown past here, it
                    // would end the accept loop and every later test's command.
                    log(Level.SEVERE, "Failed to run Java dedup coverage command: " + line.trim(), e);
                    taken = false;
                }
                writeReply(commandSocket.getOutputStream(), taken ? UNIX_REPLY_TAKEN : UNIX_REPLY_IGNORED);
            } catch (IOException e) {
                log(Level.SEVERE, "Failed to handle Java dedup coverage command", e);
            }
            BackgroundWindow window = pendingBackground;
            if (window != null) {
                pendingBackground = null;
                publisher.sendBackground(window.after, window.before,
                        () -> collector.backgroundProbes(window.cleared));
            }
        }

        // run runs one command line ("START id", "END id") and says whether it
        // was taken. With diagnostics on, it says how long each step took.
        private boolean run(String line) {
            if (BACKGROUND_COMMAND.equals(line)) {
                return askBackground();
            }
            CoverageCommand command = CoverageCommand.parse(line);
            if (command == null) {
                return false;
            }
            synchronized (testCaseLock) {
                Phases phases = new Phases();
                try {
                    return run(command, phases);
                } finally {
                    phases.report(command);
                }
            }
        }

        // run runs one parsed command under testCaseLock, and marks its steps.
        private boolean run(CoverageCommand command, Phases phases) {
            if (command.action == CommandAction.START) {
                // Set only once the counters are cleared: counters not
                // cleared hold the lines of the tests before this one, so
                // this START is not taken and its END is ignored too.
                activeTestId = "";
                String after = lastEndedTestId;
                lastEndedTestId = null;
                // Warm up app classes once before the first measured window so
                // one-time <clinit> lines aren't charged to the first test.
                collector.warmup();
                phases.mark("warmup");
                List<ProbeScan.Hit> cleared = new ArrayList<>();
                if (!collector.begin(phases, cleared)) {
                    return false;
                }
                activeTestId = command.testId;
                // Only what ran since a test's coverage was read: before the
                // first test it is the app's start and the warmup, and after
                // a START with no END, or an END not taken, that test's own
                // hits.
                if (after != null && !cleared.isEmpty() && publisher.backgroundAsked()) {
                    pendingBackground = new BackgroundWindow(after, command.testId, cleared);
                }
                return true;
            }

            // Set again only once this END's record is sent: after an END not
            // taken (another test's id, its hits lost, its send failed), what
            // the next START clears may be a test's own hits.
            lastEndedTestId = null;
            if (!command.testId.equals(activeTestId)) {
                log(Level.SEVERE,
                        "Ignoring mismatched END command. expected=" + activeTestId + ", actual="
                                + command.testId,
                        null);
                // Not ACK: this END publishes nothing, and an ACK told
                // Keploy it had, so it waited for a record that never came.
                return false;
            }

            try {
                boolean cut = !collector.awaitRequests(command.testId, phases);
                // The job window closes once the hits are read: a job that
                // started while they were read may be in them.
                TestCoverage coverage = collector.capture(phases).ended(cut, JobGate.close());
                if (coverage.probesByClass.isEmpty()) {
                    log(Level.FINE, "No Java coverage collected for " + command.testId, null);
                }
                publisher.publish(command.testId, coverage, phases);
                // After a cut END, what the next START clears holds this
                // test's own late hits: not a window, as after an END not
                // taken.
                lastEndedTestId = cut ? null : command.testId;
                return true;
            } catch (Exception e) {
                // Not ACK for the same reason: nothing reached Keploy.
                log(Level.SEVERE, "Failed to collect Java coverage for " + command.testId, e);
                return false;
            } finally {
                activeTestId = "";
            }
        }

        // askBackground answers BACKGROUND: ACK when the hits each START
        // clears are seen (the scan) and a receiver is on the data socket,
        // which from then on gets them; IGNORED otherwise.
        private boolean askBackground() {
            if (collector.probeScan() == null) {
                log(Level.INFO, "Keploy dedup: BACKGROUND is not taken: JaCoCo's probes are not read in place"
                        + " (KEPLOY_JAVA_SCAN off, JaCoCo over TCP, or the scan failed), so the hits between"
                        + " two tests are not seen", null);
                return false;
            }
            return publisher.askBackground();
        }

        private void writeReply(OutputStream outputStream, String reply) {
            try {
                outputStream.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            } catch (IOException e) {
                log(Level.FINE, "Java dedup control client disconnected before " + reply + " was delivered", e);
            }
        }

        // close stops run() and leaves the socket for run() to close: it
        // wakes run()'s accept with a connection of its own. Only when that
        // connection cannot be made (the file is gone, so no command can come
        // either) does it close the socket from here, as nothing else can
        // wake that accept.
        @Override
        public void close() {
            running.set(false);
            try (AFUNIXSocket wake = AFUNIXSocket.newInstance()) {
                wake.connect(AFUNIXSocketAddress.of(controlSocket), SOCKET_TIMEOUT_MILLIS);
            } catch (IOException e) {
                log(Level.FINE, "Could not wake the Java dedup control socket; closing it", e);
                closeNow();
            }
        }

        @Override
        public void closeNow() {
            closeQuietly(serverSocket);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
            log(Level.FINE, "Failed to close a Java dedup socket", e);
        }
    }

    /**
     * TCP transport (k8s): the SDK dials the collector and keeps one bidirectional
     * connection open for the whole replay. Mirrors {@link CommandServer}'s dispatch
     * but inverts the roles — here the SDK is the client. Wire protocol:
     * <pre>
     *   collector -&gt; SDK : "START &lt;id&gt;" | "END &lt;id&gt;"
     *   SDK -&gt; collector : "ACK"                        (after a START cleared the counters)
     *                      "COV &lt;compact-json&gt;" + "ACK" (after an END read the test's hits)
     *                      "IGNORED &lt;id&gt;" + "ACK"         (after a START that could not
     *                                                     clear them, an END with no START,
     *                                                     or an END whose hits could not
     *                                                     be read)
     * </pre>
     * The collector starts listening only when replay begins, so the connect loop
     * retries until it is reachable.
     */
    private static final class CoverageTcpClient implements CoverageWorker {

        private static final long RECONNECT_DELAY_MILLIS = 1000;
        // A reply up to this size leaves in one write. Above it (the CLASSES
        // frame, sent once per connection with the class files; a MANIFEST
        // frame once the app has hit several hundred classes; a very large
        // COV) a frame goes to the socket as it is, so it is never copied.
        private static final int REPLY_BUFFER_BYTES = 64 * 1024;

        private final CoverageCollector collector;
        private final String host;
        private final int port;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Object testCaseLock = new Object();
        private volatile Socket socket;
        private String activeTestId = "";
        // Connect retries spin ~1/s until the collector is reachable; log the first
        // failure at INFO and the rest at FINE so we don't spam k8s app logs.
        private boolean connectFailureLogged = false;

        CoverageTcpClient(CoverageCollector collector, String host, int port) {
            this.collector = collector;
            this.host = host;
            this.port = port;
        }

        @Override
        public void run() {
            while (running.get()) {
                try {
                    connectAndServe();
                } catch (IOException e) {
                    if (running.get()) {
                        Level level = connectFailureLogged ? Level.FINE : Level.INFO;
                        connectFailureLogged = true;
                        log(level, "Keploy dedup: TCP connect to " + host + ":" + port
                                + " failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + "), retrying", null);
                    }
                }
                if (running.get()) {
                    try {
                        Thread.sleep(RECONNECT_DELAY_MILLIS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        private void connectAndServe() throws IOException {
            Socket open = new Socket();
            // Bounded connect, but NO read timeout: the connection is long-lived and
            // idles between tests waiting for the next START/END command.
            open.connect(new InetSocketAddress(InetAddress.getByName(host), port), SOCKET_TIMEOUT_MILLIS);
            // Keploy waits on each reply, so no part of one may wait in this
            // kernel for Keploy's ACK of the part before it (Nagle), which
            // Keploy's kernel delays by about 40 ms, once per test. Each reply
            // is also sent in one write (see out below).
            try {
                open.setTcpNoDelay(true);
            } catch (SocketException e) {
                log(Level.INFO, "Keploy dedup: TCP_NODELAY could not be set on the socket to " + host + ":" + port
                        + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + "), so a reply sent in"
                        + " more than one write may wait about 40 ms", null);
            }
            socket = open;
            connectFailureLogged = false;
            // Fresh connection (possibly a new proxy replica after a drop): re-arm
            // the one-time zip send so the collector on the other end gets the
            // class bytecode. MinIO first-write-wins makes the re-store idempotent.
            collector.resetBytecodeSentForNewConnection();
            log(Level.INFO, "Keploy dedup: connected to collector at " + host + ":" + port, null);
            try (Socket active = open;
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(active.getInputStream(), StandardCharsets.UTF_8))) {
                // dispatch writes a reply's lines here and ack sends them in one
                // write once the reply is whole.
                OutputStream out = new BufferedOutputStream(active.getOutputStream(), REPLY_BUFFER_BYTES);
                String line;
                while (running.get() && (line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    CoverageCommand command = CoverageCommand.parse(trimmed);
                    if (command == null) {
                        continue;
                    }
                    Phases phases = new Phases();
                    try {
                        dispatch(command, out, phases);
                    } finally {
                        phases.report(command);
                    }
                }
            } finally {
                socket = null;
            }
        }

        private void dispatch(CoverageCommand command, OutputStream out, Phases phases) throws IOException {
            synchronized (testCaseLock) {
                if (command.action == CommandAction.START) {
                    // As on the unix socket, the test is set only once the
                    // counters are cleared: counters not cleared hold the lines
                    // of the tests before this one, so this START is not taken
                    // and its END is ignored too.
                    activeTestId = "";
                    boolean reset;
                    try {
                        // Warm up app classes once before the first measured window so
                        // one-time <clinit> lines aren't charged to the first test.
                        collector.warmup();
                        phases.mark("warmup");
                        reset = collector.begin(phases, new ArrayList<ProbeScan.Hit>());
                    } catch (RuntimeException e) {
                        // Not taken, and the connection goes on: thrown past
                        // here, it ended this thread, and no later command
                        // was answered.
                        log(Level.SEVERE, "Failed to clear Java coverage for " + command.testId, e);
                        reset = false;
                    }
                    if (reset) {
                        activeTestId = command.testId;
                    } else {
                        // Said before the ACK, as for an END not taken: the
                        // collector has no other sign this test went unmeasured.
                        writeLine(out, IGNORED_REPLY + " " + command.testId);
                    }
                    ack(out);
                    return;
                }

                if (command.action == CommandAction.END) {
                    if (!command.testId.equals(activeTestId)) {
                        log(Level.SEVERE,
                                "Ignoring mismatched END command. expected=" + activeTestId + ", actual="
                                        + command.testId,
                                null);
                        // Say so before the ACK: with no COV the collector has no
                        // other way to tell this test went unmeasured. ACK still
                        // follows, as it ends the collector's wait, and a collector
                        // that does not know this frame skips it.
                        writeLine(out, IGNORED_REPLY + " " + command.testId);
                        ack(out);
                        return;
                    }

                    // Capture first; a failure here must NOT skip the COV. The
                    // collector reads COV and ACK off the same line-oriented stream,
                    // so exactly one COV must precede every ACK — a missing COV on an
                    // exception would shift the collector onto the next test's frames.
                    TestCoverage coverage;
                    try {
                        boolean cut = !collector.awaitRequests(command.testId, phases);
                        coverage = collector.capture(phases).ended(cut, JobGate.close());
                    } catch (Exception e) {
                        // Not taken, as on the unix socket: an empty COV is
                        // the frame of a test that ran no line of the app.
                        log(Level.SEVERE, "Failed to collect Java coverage for " + command.testId, e);
                        activeTestId = "";
                        writeLine(out, IGNORED_REPLY + " " + command.testId);
                        ack(out);
                        return;
                    }
                    if (coverage.probesByClass.isEmpty()) {
                        log(Level.FINE, "No Java coverage collected for " + command.testId, null);
                    }
                    try {
                        // Always emit exactly one COV before ACK — even when empty —
                        // so the line-oriented collector stays in lockstep (the unix
                        // transport likewise always publishes).
                        String cov = "COV " + GSON.toJson(new DedupPayload(command.testId, coverage));
                        phases.mark("json");
                        // Past here, nothing can leave this ACK without the COV:
                        // a failure building the CLASSES frame skips only that
                        // frame. A COV that fits the buffer only fills it, so a
                        // reply that fits the buffer still leaves in one write,
                        // at the ACK.
                        writeLine(out, cov);
                        // Ride the bytecode+manifest over this same raw-TCP channel
                        // (the only app->proxy path the replay agent doesn't mock-
                        // intercept), when the manifest has grown. A failure building
                        // it skips only this frame.
                        String classesFrame = collector.pollBytecodeFrame();
                        phases.mark("classes");
                        if (classesFrame != null) {
                            writeLine(out, classesFrame);
                        }
                    } catch (Exception e) {
                        log(Level.FINE, "Failed to send the COV or CLASSES frame for " + command.testId, e);
                    } finally {
                        activeTestId = "";
                        ack(out);
                        phases.mark("send");
                    }
                }
            }
        }

        // writeLine adds a line to the reply; ack ends the reply and sends it.
        private static void writeLine(OutputStream out, String message) throws IOException {
            out.write(message.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
        }

        private static void ack(OutputStream out) throws IOException {
            writeLine(out, "ACK");
            out.flush();
        }

        @Override
        public void close() {
            running.set(false);
            Socket open = socket;
            if (open != null) {
                try {
                    open.close();
                } catch (IOException e) {
                    log(Level.FINE, "Failed to close Java dedup TCP socket", e);
                }
            }
        }
        // closeNow is close(): it already closes the socket from the caller's
        // thread, which no accept races here.
        @Override
        public void closeNow() {
            close();
        }
    }

    private enum CommandAction {
        START,
        END
    }

    private static final class CoverageCommand {

        private final CommandAction action;
        private final String testId;

        private CoverageCommand(CommandAction action, String testId) {
            this.action = action;
            this.testId = testId;
        }

        private static CoverageCommand parse(String raw) {
            String[] parts = raw.split(" ", 2);
            if (parts.length != 2 || parts[1].trim().isEmpty()) {
                log(Level.FINE, "Invalid Java dedup command: " + raw, null);
                return null;
            }

            if ("START".equals(parts[0])) {
                return new CoverageCommand(CommandAction.START, parts[1].trim());
            }
            if ("END".equals(parts[0])) {
                return new CoverageCommand(CommandAction.END, parts[1].trim());
            }

            log(Level.FINE, "Unknown Java dedup command: " + raw, null);
            return null;
        }
    }

    private static final class CoverageCollector {

        private final JacocoClient jacocoClient;
        private final CoverageIndex coverageIndex;
        // premain's, for the warmup to find the app's class loaders; null
        // when the SDK was not started as a -javaagent.
        private final Instrumentation instrumentation;
        private final AtomicBoolean warmed = new AtomicBoolean(false);
        // How long END waits for the test's requests; see awaitRequests.
        private final long endWaitMillis;
        // The VM names of the app's classes, and the classes by those
        // names; see indexedClassNames.
        private volatile Set<String> appClassNames;
        private volatile Map<String, ClassEntry> appClassesByName;
        // The JaCoCo ids of the indexed classes looked up so far; see
        // indexedId.
        private final java.util.concurrent.ConcurrentMap<String, Long> indexedIds =
                new java.util.concurrent.ConcurrentHashMap<>();
        // Coverage manifest ({vmClassName -> {classId, probeCount}}) accumulated
        // from the SAME per-test capture() path that produces dedup fingerprints —
        // so its class matching is proven correct (a separate post-warmup dump
        // raced the START reset and matched 0 classes). Build-constant, grows as
        // new app classes are hit. Uploaded (overwrite) whenever it grows, so the
        // final upload covers every class in the coverage union.
        private final java.util.concurrent.ConcurrentMap<String, ManifestEntry> liveManifest =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicInteger lastUploadedManifestSize =
                new java.util.concurrent.atomic.AtomicInteger(-1);
        // Whether the (constant) class-bytecode zip has already been sent on the
        // CURRENT collector connection. The indexed class set never changes (a
        // one-time classpath scan), so the zip is sent ONCE per connection via a
        // CLASSES frame; subsequent manifest growth rides lightweight MANIFEST
        // frames carrying no zip. Reset on every (re)connect so a fresh collector
        // connection receives the zip again — preserving the reconnect
        // self-healing the old re-send-on-every-growth path gave for free, without
        // the redundant resends or the 16 MiB single-line blow-up on large apps.
        private volatile boolean bytecodeSentThisConnection = false;

        // resetBytecodeSentForNewConnection re-arms the one-time zip send for a
        // freshly (re)established collector connection. Also clears the manifest
        // high-water mark so the first poll on the new connection re-emits the
        // full CLASSES frame even if the manifest hasn't grown since the drop.
        void resetBytecodeSentForNewConnection() {
            bytecodeSentThisConnection = false;
            lastUploadedManifestSize.set(-1);
        }

        private CoverageCollector(JacocoClient jacocoClient, CoverageIndex coverageIndex,
                Instrumentation instrumentation) {
            this.endWaitMillis = endWaitMillis();
            this.jacocoClient = jacocoClient;
            this.coverageIndex = coverageIndex;
            this.instrumentation = instrumentation;
        }

        // The one-pass scan of the app classes' probes, when JaCoCo's agent
        // runs in this JVM and its runtime data could be read; null with
        // KEPLOY_JAVA_SCAN=off, when JaCoCo is reached over TCP, or once the
        // scan failed. Looked up on the first START, END or BACKGROUND; both
        // are guarded by the collector.
        private ProbeScan probeScan;
        private boolean probeScanLookedUp;

        private synchronized ProbeScan probeScan() {
            if (!probeScanLookedUp) {
                probeScanLookedUp = true;
                if (isScanTurnedOff()) {
                    log(Level.INFO, "Keploy dedup: KEPLOY_JAVA_SCAN is off, so each test takes JaCoCo's dump"
                            + " and reset, and no BACKGROUND record is sent", null);
                    return null;
                }
                Object agent = jacocoClient.runtimeAgent();
                if (agent != null) {
                    try {
                        probeScan = ProbeScan.locate(agent);
                    } catch (Throwable e) {
                        // Throwable, as for the in-process agent's lookup: a
                        // LinkageError from an agent not laid out as JaCoCo's
                        // must not end the control server.
                        log(Level.INFO, "Keploy dedup: JaCoCo's probes cannot be read in place, so each test"
                                + " takes JaCoCo's dump and reset", e);
                    }
                }
            }
            return probeScan;
        }

        // dropProbeScan stops using the scan after it failed: every later
        // command takes JaCoCo's dump and reset.
        private synchronized void dropProbeScan(Throwable cause) {
            probeScan = null;
            log(Level.WARNING, "Keploy dedup: the probe scan failed; each test now takes JaCoCo's dump and reset",
                    cause);
        }

        // begin clears what the app's classes ran since the last test ended,
        // so that none of it is charged to the next test, and says whether
        // it did. With the scan, that is one pass over the app's classes, and
        // what it clears is background (a scheduled job, a health check):
        // added to cleared, counted in the diagnostics, never in a test's
        // hits. Without it, or when the pass fails (in any way: a START only
        // has to clear), JaCoCo resets every class, and cleared stays empty.
        private boolean begin(Phases phases, List<ProbeScan.Hit> cleared) {
            // A job running now, or that starts before END reads the hits,
            // runs in this test's window: opened before the clear, as what
            // a job ran before is cleared only once it has ended.
            JobGate.open();
            // This test's requests come in from now on; END waits for them
            // alone.
            RequestGate.open();
            ProbeScan scan = probeScan();
            if (scan != null) {
                try {
                    List<ProbeScan.Hit> background = scan.pass(indexedClassNames());
                    phases.mark("scan");
                    if (diagnosticsEnabled() && !background.isEmpty()) {
                        diagnostic("background hits cleared at START: " + describe(background));
                    }
                    cleared.addAll(background);
                    return true;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    dropProbeScan(e);
                }
            }
            boolean reset = reset();
            phases.mark("reset");
            return reset;
        }

        // awaitRequests waits, for at most endWaitMillis, until the requests
        // that came in since this test's START are done, and says whether
        // they are: a server writes a response before the request is done
        // (a filter's code after the chain, a request log), so Keploy can
        // send END while the test's own code still runs. False, the END is
        // cut: what still runs lands after it.
        private boolean awaitRequests(String testId, Phases phases) {
            if (ServerEntries.countsNone() && NOT_COUNTED_LOGGED.compareAndSet(false, true)) {
                log(Level.INFO, notCounted(endWaitMillis, ServerEntries.uncounted()), null);
            }
            if (endWaitMillis == 0 || RequestGate.running() <= 0) {
                return true;
            }
            boolean done = RequestGate.await(TimeUnit.MILLISECONDS.toNanos(endWaitMillis));
            phases.mark("requests");
            if (!done) {
                log(Level.WARNING, "Keploy dedup: " + testId + "'s requests still ran " + endWaitMillis
                        + " ms after its END, so its record says windowCut and a Keploy that reads it does not"
                        + " judge the test: what they ran after that is not in it (KEPLOY_JAVA_END_WAIT_MS sets the wait)", null);
            }
            return done;
        }

        // describe says how many classes and probes hits holds, and names a few classes.
        private String describe(List<ProbeScan.Hit> hits) {
            int probes = 0;
            List<String> names = new ArrayList<>(hits.size());
            for (ProbeScan.Hit hit : hits) {
                probes += hit.probes.length;
                names.add(hit.name);
            }
            return "classes=" + hits.size() + ", probes=" + probes + ", sampleClasses=" + summarizeStrings(names, 5);
        }

        // reset zeroes JaCoCo's counters and says whether it did.
        private boolean reset() {
            try {
                jacocoClient.dump(false, true);
                return true;
            } catch (IOException e) {
                log(Level.WARNING, "Failed to reset JaCoCo counters", e);
                return false;
            }
        }

        /**
         * Eagerly initialize every indexed application class so their static
         * initializers (&lt;clinit&gt;) run ONCE here, before the first test's
         * coverage window. The very first request to a fresh JVM otherwise pays
         * the one-time class-init cost, and JaCoCo charges those &lt;clinit&gt;
         * lines to whichever test ran first — making the duplicate set
         * non-deterministic run-to-run. Running them now (then letting the START
         * clear the counters) means every test sees only the lines its own
         * request executes. Called on the first START, when the app is fully
         * started. Best-effort: per-class failures are ignored (the class
         * just falls back to lazy init). Disable with
         * KEPLOY_JAVA_DEDUP_WARMUP_DISABLED=true if a static initializer has
         * harmful side effects.
         *
         * <p>The warmup runs on a thread of its own, through the app's class
         * loaders (AppWarmup). This START waits for it at most
         * warmupWaitMillis, then tells it to stop after the class it is
         * initializing and goes on: that class's static initializer goes on
         * while the tests run, and each line it runs from then on counts in
         * the test running at the time (between two tests, in none; the next
         * START clears it). The classes it did not reach initialize when the
         * app first uses them, as with no warmup.
         */
        private void warmup() {
            if (isWarmupDisabled() || !warmed.compareAndSet(false, true)) {
                return;
            }
            final long began = System.nanoTime();
            final AppWarmup warmup = new AppWarmup(instrumentation);
            Thread thread;
            try {
                thread = warmupThreads.newThread(() -> runWarmup(warmup, began));
                thread.start();
            } catch (Throwable t) {
                // Out of threads (a pids or ulimit -u limit): no warmup, and
                // this START, and the server that runs it, go on.
                log(Level.WARNING, "Keploy dedup: no warmup, as its thread could not start; the app's classes"
                        + " initialize when the app first uses them", t);
                return;
            }
            try {
                thread.join(warmupWaitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                warmup.stop();
                log(Level.WARNING, "Keploy dedup: the warmup of the app's classes was still " + warmup.doing()
                        + " after " + Phases.millis(System.nanoTime() - began) + " ms, so the first test goes on"
                        + " without it, and the warmup stops after that step. A static initializer it is running"
                        + " goes on while the tests run, and each line it runs from then on counts in the test"
                        + " running at the time (between two tests, in none); the classes it did not reach"
                        + " initialize when the app first uses them, and their lines count in the test that does",
                        null);
            }
        }

        // runWarmup is the warmup's thread: it reads the app's classes here,
        // not on the START's thread, and never names a library's class.
        private void runWarmup(AppWarmup warmup, long began) {
            try {
                // Only the app's own classes: a library's static initializer can
                // start threads, open connections or self-attach an agent (Mockito
                // through byte-buddy), and the whole classpath of a large app took
                // the warmup to 54 s (105k classes on Fineract).
                if (!coverageIndex.isAppOnly()) {
                    log(Level.INFO, "Keploy dedup: no warmup, as the index is not the app's own classes alone"
                            + " (from " + coverageIndex.source + ")", null);
                    return;
                }
                List<String> names = new ArrayList<>();
                for (ClassEntry entry : coverageIndex.entries()) {
                    names.add(entry.className.replace('/', '.'));
                }
                warmup.run(names);
                log(Level.INFO, "Keploy dedup: warmed up application classes (initialized="
                        + warmup.initialized() + ", skipped=" + warmup.skipped()
                        + (warmup.notReached() > 0 ? ", not reached=" + warmup.notReached() : "")
                        + ") in " + Phases.millis(System.nanoTime() - began) + " ms", null);
            } catch (Throwable t) {
                // Never let the warmup end anything but itself.
                log(Level.WARNING, "Keploy dedup: the warmup of the app's classes failed; they initialize when"
                        + " the app first uses them", t);
            }
        }

        // isScanTurnedOff says whether KEPLOY_JAVA_SCAN (or keploy.java.scan)
        // is off, in any case: the one-pass scan is then never used.
        private boolean isScanTurnedOff() {
            return "off".equalsIgnoreCase(envOrProperty("KEPLOY_JAVA_SCAN", "keploy.java.scan", ""));
        }

        private boolean isWarmupDisabled() {
            return isTruthy(envOrProperty("KEPLOY_JAVA_DEDUP_WARMUP_DISABLED",
                    "keploy.java.dedup.warmup.disabled", ""));
        }

        private TestCoverage capture(Phases phases) throws IOException {
            ProbeScan scan = probeScan();
            if (scan != null) {
                List<ProbeScan.Hit> hits = null;
                try {
                    hits = scan.pass(indexedClassNames());
                } catch (ProbeScan.LostHitsException e) {
                    // Hits this test made were cleared and lost: JaCoCo's
                    // dump would ACK a partial set, so the END is not taken.
                    // The next START takes JaCoCo's reset.
                    dropProbeScan(e);
                    throw e;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // Nothing was cleared: JaCoCo's dump holds this test's hits.
                    dropProbeScan(e);
                }
                if (hits != null) {
                    phases.mark("scan");
                    return probesByClass(hits, phases);
                }
            }
            byte[] dump = jacocoClient.dump(true, true);
            phases.mark("dump");
            if (dump.length == 0) {
                if (diagnosticsEnabled()) {
                    diagnostic("JaCoCo dump returned 0 bytes");
                }
                return TestCoverage.NONE;
            }

            ExecFileLoader loader = new ExecFileLoader();
            loader.load(new ByteArrayInputStream(dump));
            ExecutionDataStore executionDataStore = loader.getExecutionDataStore();
            phases.mark("parse");

            // BRANCH coverage: fingerprint by the set of executed JaCoCo PROBES per
            // class, NOT just executed lines. Each branch is instrumented as a
            // distinct probe, so the executed-probe set distinguishes WHICH branch a
            // test took (true vs false) — line status, and even branch counts, report
            // identically for the true-path and false-path test. The probe set also
            // subsumes line coverage (lines map to probes). Probe indices are stable
            // for a given class bytecode, so the set is comparable across the run.
            // Keyed by VM class name (canonical "com/foo/Bar"; no file-path
            // normalization needed). CoverageIndex is used only to restrict to the
            // app's own classes so JDK/library probes don't pollute the fingerprint.
            Set<String> appClasses = indexedClassNames();
            if (appClasses.isEmpty()) {
                if (diagnosticsEnabled()) {
                    diagnostic("coverage index has no app classes");
                }
                return TestCoverage.NONE;
            }

            // The app's classes the dump holds hits of, sorted as the scan
            // returns them, so that both choose of two copies of one class
            // alike.
            List<ProbeScan.Hit> hits = new ArrayList<>();
            for (ExecutionData executionData : executionDataStore.getContents()) {
                if (executionData.hasHits() && appClasses.contains(executionData.getName())) {
                    hits.add(ProbeScan.Hit.of(executionData.getName(), executionData.getId(),
                            executionData.getProbes()));
                }
            }
            Collections.sort(hits, ProbeScan.HITS_BY_NAME_THEN_ID);
            return probesByClass(hits, phases);
        }

        // probesByClass turns a test's hits (sorted by name, then id) into
        // its probes by class, and adds each class to the coverage manifest.
        // Of two classes of one name (loaded twice, from different bytes)
        // it reports chosenCopy's. In its first and last cases, when the
        // manifest already names another copy, the probes sent are not those
        // of the class the manifest names: a test's record names a class,
        // not its id. The legacy CoverageReporter then credits nothing when
        // the manifest names a copy the index did not read, and credits the
        // other copy's probe indices to the indexed class when the manifest
        // names the indexed copy. Each class whose reported copy is not the
        // class the index read is named in the record's classMismatch, so
        // that Keploy does not read its probes against the indexed bytes.
        private TestCoverage probesByClass(List<ProbeScan.Hit> hits, Phases phases) {
            Map<String, List<Integer>> byClass = new LinkedHashMap<>();
            List<String> mismatch = new ArrayList<>();
            for (int i = 0; i < hits.size(); ) {
                String name = hits.get(i).name;
                int end = copiesEnd(hits, i);
                ProbeScan.Hit chosen = chosenCopy(hits, i, end);
                i = end;
                final int probeCount = chosen.probeCount;
                final long classId = chosen.id;
                liveManifest.computeIfAbsent(name, k -> {
                    ManifestEntry m = new ManifestEntry();
                    m.id = IndexReport.hexId(classId);
                    m.classId = classId;
                    m.probeCount = probeCount;
                    return m;
                });
                byClass.put(name, probeList(chosen));
                if (!isIndexed(name, classId)) {
                    mismatch.add(name);
                }
            }
            phases.mark("filter");
            if (diagnosticsEnabled()) {
                diagnostic("classesWithProbes=" + byClass.size()
                        + ", sampleClasses=" + summarizeStrings(byClass.keySet(), 5));
                if (!mismatch.isEmpty()) {
                    diagnostic("classMismatch=" + mismatch.size()
                            + ", sampleClasses=" + summarizeStrings(mismatch, 5));
                }
            }
            return new TestCoverage(byClass, mismatch);
        }

        // backgroundProbes turns the hits a START cleared into probes by
        // class, of two copies of one class the one probesByClass would
        // report, and names in classMismatch, as probesByClass does, each
        // whose reported copy is not the class the index read: Keploy takes
        // background out of a test's hits probe by probe, so it must know
        // which are not the indexed class's. It adds none of them to the
        // coverage manifest: no test ran them.
        private TestCoverage backgroundProbes(List<ProbeScan.Hit> hits) {
            Map<String, List<Integer>> byClass = new LinkedHashMap<>();
            List<String> mismatch = new ArrayList<>();
            for (int i = 0; i < hits.size(); ) {
                int end = copiesEnd(hits, i);
                ProbeScan.Hit chosen = chosenCopy(hits, i, end);
                i = end;
                byClass.put(chosen.name, probeList(chosen));
                if (!isIndexed(chosen.name, chosen.id)) {
                    mismatch.add(chosen.name);
                }
            }
            return new TestCoverage(byClass, mismatch);
        }

        // copiesEnd returns the end of the run of hits, sorted by name, that
        // share the name of hits[from]: the copies of one class loaded from
        // different bytes.
        private int copiesEnd(List<ProbeScan.Hit> hits, int from) {
            String name = hits.get(from).name;
            int end = from + 1;
            while (end < hits.size() && hits.get(end).name.equals(name)) {
                end++;
            }
            return end;
        }

        // chosenCopy picks, of the copies hits[from, to) of one class, the
        // class the index read (its id is the CRC64 of the indexed bytes),
        // else the one whose id the coverage manifest holds, else the first
        // (the lowest id, compared as unsigned numbers).
        private ProbeScan.Hit chosenCopy(List<ProbeScan.Hit> hits, int from, int to) {
            String name = hits.get(from).name;
            long indexed = indexedId(name);
            ManifestEntry known = liveManifest.get(name);
            ProbeScan.Hit named = null;
            for (int k = from; k < to; k++) {
                ProbeScan.Hit hit = hits.get(k);
                if (hit.id == indexed) {
                    return hit;
                }
                // At most one copy has the manifest's id: JaCoCo holds one
                // class per id.
                if (known != null && known.classId == hit.id) {
                    named = hit;
                }
            }
            return named != null ? named : hits.get(from);
        }

        // isIndexed says whether the class JaCoCo measured under this name
        // and id is the class the index read: false for one another agent
        // changed before JaCoCo saw it, and for a multi-release jar's
        // META-INF/versions copy the JVM loaded in place of the indexed one.
        private boolean isIndexed(String name, long id) {
            return indexedId(name) == id;
        }

        // indexedId is the JaCoCo id of the class the index read under this
        // VM name, the CRC64 of its bytes, worked out the first time a hit
        // of the class is reported: not every indexed class at the first
        // END. Every hit reported is of an indexed class (the scan and the
        // dump keep only those), so the index holds the name.
        private long indexedId(String name) {
            Long id = indexedIds.get(name);
            if (id == null) {
                indexedClassNames();
                id = IndexReport.classId(appClassesByName.get(name).bytes);
                indexedIds.put(name, id);
            }
            return id;
        }

        private List<Integer> probeList(ProbeScan.Hit hit) {
            List<Integer> probes = new ArrayList<>(hit.probes.length);
            for (int probe : hit.probes) {
                probes.add(probe);
            }
            return probes;
        }

        // indexedClassNames returns the VM names of the app's own classes (from the
        // CoverageIndex) so probe collection can skip JDK/library classes. Built
        // once: the index never changes once loaded, and building the set again
        // on every END took 1.4-2.2 ms per test at 50k classes. Two ENDs never
        // race here (testCaseLock), and a set built twice would be the same set.
        private Set<String> indexedClassNames() {
            Set<String> names = appClassNames;
            if (names == null) {
                List<ClassEntry> entries = coverageIndex.entries();
                Map<String, ClassEntry> byName = new HashMap<>(entries.size() * 2);
                for (ClassEntry entry : entries) {
                    byName.putIfAbsent(entry.className, entry);
                }
                // appClassesByName first: a thread that sees appClassNames
                // set sees it too.
                appClassesByName = byName;
                names = byName.keySet();
                appClassNames = names;
            }
            return names;
        }

        private String summarizeStrings(Iterable<String> values, int limit) {
            List<String> sample = new ArrayList<>();
            for (String value : values) {
                sample.add(value);
                if (sample.size() >= limit) {
                    break;
                }
            }
            return sample.toString();
        }

        // pollBytecodeFrame returns a "CLASSES <b64tag> <b64manifest> <b64zip>"
        // line to send over the collector channel (:36340) IF the coverage manifest
        // has grown since the last send, else null. The raw-TCP collector channel
        // is the ONLY app->proxy path that survives the replay agent's mock
        // interception (an HTTPS upload gets 502'd as an unmatched outbound mock),
        // so the bytecode rides it alongside the COV frames. buildTag/manifest/zip
        // are base64'd to keep the whole frame on one line. Called after each
        // capture; a no-op until the manifest grows or when KEPLOY_BUILD_TAG unset.
        String pollBytecodeFrame() {
            final String buildTag = envOrProperty("KEPLOY_BUILD_TAG", "keploy.build.tag", "");
            if (buildTag.isEmpty()) {
                return null;
            }
            final int size = liveManifest.size();
            if (size == 0) {
                return null; // no classes hit yet
            }
            // Send the constant class zip once per connection; after that, only a
            // manifest-only frame when the manifest actually grows. This removes
            // the old per-growth zip resend (O(n^2) bytes) and keeps any single
            // line small once the zip is out, so the collector's line reader never
            // trips its max-line cap mid-replay.
            final boolean sendZip = !bytecodeSentThisConnection;
            if (!sendZip && size <= lastUploadedManifestSize.get()) {
                return null; // zip already sent and manifest unchanged since last send
            }
            try {
                Map<String, ManifestEntry> snapshot = new LinkedHashMap<>(liveManifest);
                String manifestJson = GSON.toJson(snapshot);
                String frame;
                if (sendZip) {
                    byte[] zip = zipIndexedClasses();
                    frame = encodeBytecodeFrame(buildTag, manifestJson, zip);
                    bytecodeSentThisConnection = true;
                    log(Level.INFO, "Keploy dedup: sending CLASSES frame over collector channel "
                            + "(zip once/connection, classes=" + size + ", zipBytes=" + zip.length + ")", null);
                } else {
                    // Manifest-only update — the collector keeps the zip it already
                    // stored for this build tag and just refreshes the manifest.
                    frame = encodeBytecodeFrame(buildTag, manifestJson, null);
                    log(Level.FINE, "Keploy dedup: sending MANIFEST frame (manifest-only update, classes="
                            + size + ")", null);
                }
                lastUploadedManifestSize.set(size);
                return frame;
            } catch (Throwable e) {
                log(Level.WARNING, "Keploy dedup: failed to build bytecode frame: " + e.getMessage(), null);
                return null;
            }
        }

        // zipIndexedClasses packs every indexed app .class (already in memory as
        // ClassEntry.bytes) into a zip keyed by "<vmClassName>.class".
        private byte[] zipIndexedClasses() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(256 * 1024);
            int n = 0;
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                for (ClassEntry entry : coverageIndex.entries()) {
                    zos.putNextEntry(new ZipEntry(entry.className + ".class"));
                    zos.write(entry.bytes);
                    zos.closeEntry();
                    n++;
                }
            }
            log(Level.INFO, "Keploy dedup: zipIndexedClasses entries=" + n + " bytes=" + baos.size(), null);
            return baos.toByteArray();
        }
    }

    private static final class JacocoClient {

        private final String host;
        private final int port;
        private volatile InProcessAgent inProcessAgent;
        private volatile boolean inProcessUnavailable;

        private JacocoClient(String host, int port) {
            this.host = host;
            this.port = port;
        }

        private byte[] dump(boolean dump, boolean reset) throws IOException {
            InProcessAgent agent = inProcessAgent();
            if (agent != null) {
                try {
                    if (!dump) {
                        if (reset) {
                            agent.reset();
                        }
                        return new byte[0];
                    }
                    return agent.getExecutionData(reset);
                } catch (Throwable t) {
                    inProcessUnavailable = true;
                    inProcessAgent = null;
                    log(Level.FINE, "In-process JaCoCo dump failed, falling back to TCP", t);
                }
            }

            return dumpOverTcp(dump, reset);
        }

        // runtimeAgent is JaCoCo's agent object in this JVM, or null when
        // JaCoCo is reached over TCP.
        private Object runtimeAgent() {
            InProcessAgent agent = inProcessAgent();
            return agent == null ? null : agent.agent;
        }

        private InProcessAgent inProcessAgent() {
            if (inProcessUnavailable) {
                return null;
            }
            InProcessAgent cached = inProcessAgent;
            if (cached != null) {
                return cached;
            }
            synchronized (this) {
                if (inProcessUnavailable) {
                    return null;
                }
                if (inProcessAgent != null) {
                    return inProcessAgent;
                }
                try {
                    inProcessAgent = InProcessAgent.locate();
                } catch (Throwable t) {
                    inProcessUnavailable = true;
                    log(Level.FINE,
                            "JaCoCo in-process agent unavailable, will use TCP fallback at "
                                    + host + ":" + port,
                            t);
                }
                return inProcessAgent;
            }
        }

        private byte[] dumpOverTcp(boolean dump, boolean reset) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
            ExecutionDataWriter writer = new ExecutionDataWriter(output);

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getByName(host), port), SOCKET_TIMEOUT_MILLIS);
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);

                RemoteControlWriter remoteWriter = new RemoteControlWriter(socket.getOutputStream());
                RemoteControlReader remoteReader = new RemoteControlReader(socket.getInputStream());
                remoteReader.setSessionInfoVisitor(writer);
                remoteReader.setExecutionDataVisitor(writer);
                remoteWriter.visitDumpCommand(dump, reset);

                if (!remoteReader.read()) {
                    throw new IOException("JaCoCo socket closed before coverage data was returned");
                }
            }

            return output.toByteArray();
        }
    }

    private static final class InProcessAgent {

        private final Object agent;
        private final Method getExecutionData;
        private final Method reset;

        private InProcessAgent(Object agent, Method getExecutionData, Method reset) {
            this.agent = agent;
            this.getExecutionData = getExecutionData;
            this.reset = reset;
        }

        static InProcessAgent locate() throws ReflectiveOperationException {
            ReflectiveOperationException firstFailure = null;
            try {
                InProcessAgent bootstrapAgent = locateWithLoader(null);
                if (bootstrapAgent != null) {
                    return bootstrapAgent;
                }
            } catch (ReflectiveOperationException e) {
                firstFailure = e;
            }

            for (ClassLoader loader : candidateLoaders()) {
                try {
                    InProcessAgent agent = locateWithLoader(loader);
                    if (agent != null) {
                        return agent;
                    }
                } catch (ReflectiveOperationException e) {
                    if (firstFailure == null) {
                        firstFailure = e;
                    }
                }
            }

            if (firstFailure != null) {
                throw firstFailure;
            }
            throw new ClassNotFoundException("org.jacoco.agent.rt.RT");
        }

        private static InProcessAgent locateWithLoader(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> rtClass = Class.forName("org.jacoco.agent.rt.RT", true, loader);
            if (diagnosticsEnabled()) {
                Object source = rtClass.getProtectionDomain() == null
                        || rtClass.getProtectionDomain().getCodeSource() == null
                        ? "bootstrap"
                        : rtClass.getProtectionDomain().getCodeSource().getLocation();
                String loaderName = loader == null ? "bootstrap" : loader.toString();
                diagnostic("resolved JaCoCo RT with loader=" + loaderName + ", source=" + source);
            }
            Object resolved = rtClass.getMethod("getAgent").invoke(null);
            if (resolved == null) {
                return null;
            }

            Method getExecutionData = resolved.getClass().getMethod("getExecutionData", boolean.class);
            Method reset = resolved.getClass().getMethod("reset");
            getExecutionData.setAccessible(true);
            reset.setAccessible(true);
            return new InProcessAgent(resolved, getExecutionData, reset);
        }

        private static List<ClassLoader> candidateLoaders() {
            List<ClassLoader> loaders = new ArrayList<>(3);
            addLoader(loaders, ClassLoader.getSystemClassLoader());
            addLoader(loaders, Thread.currentThread().getContextClassLoader());
            addLoader(loaders, KeployDedupAgent.class.getClassLoader());
            return loaders;
        }

        private static void addLoader(List<ClassLoader> loaders, ClassLoader candidate) {
            if (candidate != null && !loaders.contains(candidate)) {
                loaders.add(candidate);
            }
        }

        byte[] getExecutionData(boolean resetCounters) throws IOException {
            try {
                return (byte[]) getExecutionData.invoke(agent, resetCounters);
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                }
                throw new IOException("Failed to read in-process JaCoCo coverage", cause);
            } catch (ReflectiveOperationException e) {
                throw new IOException("Failed to read in-process JaCoCo coverage", e);
            }
        }

        void reset() throws IOException {
            try {
                reset.invoke(agent);
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                }
                throw new IOException("Failed to reset in-process JaCoCo coverage", cause);
            } catch (ReflectiveOperationException e) {
                throw new IOException("Failed to reset in-process JaCoCo coverage", e);
            }
        }
    }

    private static final class CoverageIndex {

        private final Object lock = new Object();
        private volatile List<ClassEntry> entries;

        private List<ClassEntry> entries() {
            List<ClassEntry> cached = entries;
            if (cached != null) {
                return cached;
            }

            synchronized (lock) {
                cached = entries;
                if (cached == null) {
                    cached = loadEntries();
                    entries = cached;
                }
                return cached;
            }
        }

        // Whether the index holds only the app's own classes (named in
        // KEPLOY_JAVA_CLASS_DIRS, or derived from the launch), and where it
        // came from. Set by loadEntries, before entries() returns.
        private volatile boolean appOnly;
        private volatile String source = "";
        // For the INDEX report: how the index was found (IndexReport's
        // SOURCE_*), how the JVM was launched or why the app's classes could
        // not be told apart, and the app's package roots when derived. Set by
        // loadEntries, before entries() returns.
        private volatile String sourceKind = IndexReport.SOURCE_NONE;
        private volatile String launch = "";
        private volatile List<String> packages = Collections.emptyList();
        // What measures the classes, and the includes= of the JaCoCo agent
        // the SDK started (IndexReport's JACOCO_*), and that agent's
        // version, or the attached one's. Set by premain's startJacoco
        // before the worker that sends the report starts.
        private volatile String jacoco = IndexReport.JACOCO_UNCHECKED;
        private volatile String includes = "";
        private volatile String jacocoVersion = "";
        private volatile IndexReport report;

        // report is the INDEX report of this index: computed once, on the
        // first call, from entries() (so it reads the index if nothing has).
        private IndexReport report() {
            IndexReport cached = report;
            if (cached != null) {
                return cached;
            }
            synchronized (lock) {
                if (report == null) {
                    List<ClassEntry> all = entries();
                    long[] ids = new long[all.size()];
                    Set<String> roots = new LinkedHashSet<>();
                    for (int i = 0; i < ids.length; i++) {
                        ClassEntry entry = all.get(i);
                        ids[i] = IndexReport.classId(entry.bytes);
                        String root = IndexReport.rootOf(entry.location, entry.className);
                        if (root != null) {
                            roots.add(root);
                        }
                    }
                    report = new IndexReport(IndexReport.buildId(ids), sourceKind, launch, roots, packages,
                            all.size(), includes, jacoco, IndexReport.versionOf(IndexReport.class), jacocoVersion);
                }
                return report;
            }
        }

        // isAppOnly says whether the index holds only the app's own classes.
        // When it does not (a whole jar or the whole classpath), fingerprints
        // and the warmup take in library code too.
        private boolean isAppOnly() {
            entries();
            return appOnly;
        }

        private List<ClassEntry> loadEntries() {
            LinkedHashMap<String, ClassEntry> collected = new LinkedHashMap<>();
            List<File> configuredRoots = configuredClassRoots();
            List<File> applicationRoots = applicationRoots();
            List<File> executableArchiveRoots = Collections.emptyList();
            List<File> classpathRoots = Collections.emptyList();

            // KEPLOY_JAVA_CLASS_DIRS's own roots first: the index is the
            // override's only when they hold a class, not when the build
            // output under the working dir does.
            scanRoots(configuredRoots, collected);
            boolean overridden = !collected.isEmpty();
            if (!configuredRoots.isEmpty() && !overridden) {
                log(Level.WARNING, "Keploy dedup found no class in KEPLOY_JAVA_CLASS_DIRS "
                        + summarizeFiles(configuredRoots, 5) + ", so it looks for the app's classes itself."
                        + " Set it to the app's class dirs and jars", null);
            }
            List<File> defaultRoots = new ArrayList<>(applicationRoots);
            defaultRoots.removeAll(configuredRoots);
            scanRoots(defaultRoots, collected);
            List<File> foundRoots = new ArrayList<>();
            // An override that held no class is not where the classes came from.
            for (File root : overridden ? applicationRoots : defaultRoots) {
                if (root.exists()) {
                    foundRoots.add(root);
                }
            }
            String from = "the class dirs " + summarizeFiles(foundRoots, 5);
            String kind = overridden ? IndexReport.SOURCE_OVERRIDE : IndexReport.SOURCE_DERIVED;
            String how = from;
            List<String> pkgRoots = Collections.emptyList();
            boolean onlyApp = true;
            // why says why the index is not the app's own classes alone.
            String why = "";
            if (collected.isEmpty()) {
                AppClasses derived = AppClasses.derive(System.getProperty("java.class.path", ""),
                        System.getProperty("sun.java.command", ""), System.getProperty("user.dir"));
                Map<String, AppClasses.Found> found = new LinkedHashMap<>();
                derived.scan(found, this::shouldSkipClass);
                for (Map.Entry<String, AppClasses.Found> entry : found.entrySet()) {
                    collected.put(entry.getKey(), new ClassEntry(classNameFromKey(entry.getKey()),
                            normalizePath(entry.getValue().location), entry.getValue().bytes));
                }
                from = derived.root == null ? derived.launch
                        : "the package " + derived.root + " (" + derived.launch + ")";
                why = derived.failed ? derived.launch : "no class found from " + from;
                kind = IndexReport.SOURCE_DERIVED;
                how = derived.launch;
                pkgRoots = derived.root == null ? Collections.<String>emptyList()
                        : Collections.singletonList(derived.root);
            }
            if (collected.isEmpty()) {
                onlyApp = false;
                executableArchiveRoots = executableArchiveRoots();
                scanRoots(executableArchiveRoots, collected);
                from = "the whole jar " + summarizeFiles(executableArchiveRoots, 5);
                kind = IndexReport.SOURCE_WHOLE_JAR;
                how = why;
                pkgRoots = Collections.emptyList();
            }
            if (collected.isEmpty() && isClasspathFallbackEnabled()) {
                classpathRoots = classpathRoots();
                scanRoots(classpathRoots, collected);
                from = "the whole classpath";
                kind = IndexReport.SOURCE_CLASSPATH;
            }
            appOnly = onlyApp;
            source = collected.isEmpty() ? "no class" : from;
            sourceKind = collected.isEmpty() ? IndexReport.SOURCE_NONE : kind;
            launch = how;
            packages = collected.isEmpty() ? Collections.<String>emptyList() : pkgRoots;
            if (collected.isEmpty()) {
                // Said at WARNING: no test's coverage is measured at all.
                log(Level.WARNING, "Keploy dedup found none of the app's classes (" + why + "), nor any class"
                        + (isClasspathFallbackEnabled() ? " in the jar or on the classpath"
                        : " in the jar, and KEPLOY_JAVA_CLASSPATH_FALLBACK=false keeps it off the classpath")
                        + ", so no test's coverage is measured. Set KEPLOY_JAVA_CLASS_DIRS to the app's class"
                        + " dirs and jars", null);
                if (diagnosticsEnabled()) {
                    diagnostic("no application classes indexed"
                            + ", applicationRoots=" + summarizeFiles(applicationRoots, 5)
                            + ", executableArchiveRoots=" + summarizeFiles(executableArchiveRoots, 5)
                            + ", classpathFallbackRoots=" + summarizeFiles(classpathRoots, 5)
                            + ", java.class.path=" + System.getProperty("java.class.path", "")
                            + ", sun.java.command=" + System.getProperty("sun.java.command", ""));
                }
            } else if (onlyApp) {
                log(Level.INFO, "Keploy dedup: fingerprinting the app's " + collected.size()
                        + " classes, from " + from + ". Set KEPLOY_JAVA_CLASS_DIRS to the app's class dirs"
                        + " and jars if that is not the app's own code", null);
            } else {
                // Said at WARNING: dedup still runs, but on library code too.
                log(Level.WARNING, "Keploy dedup could not tell the app's own classes from its libraries ("
                        + why + "), so it fingerprints all " + collected.size() + " classes of " + from
                        + ": library code makes duplicate tests look different, so dedup keeps more tests"
                        + " than it should, and the startup warmup is skipped. Set KEPLOY_JAVA_CLASS_DIRS"
                        + " to the app's class dirs and jars", null);
            }

            List<ClassEntry> sorted = new ArrayList<>(collected.values());
            Collections.sort(sorted, new Comparator<ClassEntry>() {
                @Override
                public int compare(ClassEntry left, ClassEntry right) {
                    return left.location.compareTo(right.location);
                }
            });
            return sorted;
        }

        // summarizeFiles lists the first limit of values, and how many more
        // there are.
        private String summarizeFiles(Collection<File> values, int limit) {
            List<String> sample = new ArrayList<>();
            for (File value : values) {
                if (sample.size() == limit) {
                    sample.add("and " + (values.size() - limit) + " more");
                    break;
                }
                sample.add(value.getPath());
            }
            return sample.toString();
        }

        // configuredClassRoots is the dirs and jars KEPLOY_JAVA_CLASS_DIRS
        // names, in its order.
        private List<File> configuredClassRoots() {
            LinkedHashSet<File> roots = new LinkedHashSet<>();
            String configured = envOrProperty("KEPLOY_JAVA_CLASS_DIRS", "keploy.java.class.dirs", "");
            if (!configured.trim().isEmpty()) {
                for (String part : configuredRoots(configured)) {
                    if (!part.trim().isEmpty()) {
                        roots.add(new File(part.trim()));
                    }
                }
            }
            return new ArrayList<>(roots);
        }

        private List<File> applicationRoots() {
            LinkedHashSet<File> roots = new LinkedHashSet<>(configuredClassRoots());

            roots.add(new File(System.getProperty("user.dir"), "target/classes"));
            roots.add(new File(System.getProperty("user.dir"), "build/classes/java/main"));
            return new ArrayList<>(roots);
        }

        private boolean isClasspathFallbackEnabled() {
            return isTruthy(envOrProperty("KEPLOY_JAVA_CLASSPATH_FALLBACK",
                    "keploy.java.classpath.fallback", "true"));
        }

        private List<File> executableArchiveRoots() {
            LinkedHashSet<File> roots = new LinkedHashSet<>();

            addArchiveRoot(roots, AppClasses.firstToken(System.getProperty("sun.java.command", "")));

            String classpath = System.getProperty("java.class.path", "");
            if (classpath.trim().isEmpty()) {
                return new ArrayList<>(roots);
            }

            String[] parts = classpath.split(Pattern.quote(File.pathSeparator));
            if (parts.length == 1) {
                addArchiveRoot(roots, parts[0]);
            }
            return new ArrayList<>(roots);
        }

        private void addArchiveRoot(Set<File> roots, String rawPath) {
            if (rawPath == null) {
                return;
            }

            String path = rawPath.trim();
            if (path.isEmpty()) {
                return;
            }

            File file = new File(path);
            if (!file.isAbsolute()) {
                file = new File(System.getProperty("user.dir"), path);
            }
            if (file.isFile() && AppClasses.isArchive(file)) {
                roots.add(file);
            }
        }

        private String[] configuredRoots(String configured) {
            if (configured.indexOf(',') >= 0) {
                return configured.split(",");
            }
            return configured.split(Pattern.quote(File.pathSeparator));
        }

        private List<File> classpathRoots() {
            LinkedHashSet<File> roots = new LinkedHashSet<>();
            String classpath = System.getProperty("java.class.path", "");
            if (!classpath.trim().isEmpty()) {
                String[] parts = classpath.split(Pattern.quote(File.pathSeparator));
                for (String part : parts) {
                    if (!part.trim().isEmpty()) {
                        File file = new File(part.trim());
                        if (file.isDirectory() || AppClasses.isArchive(file)) {
                            roots.add(file);
                        }
                    }
                }
            }
            return new ArrayList<>(roots);
        }

        private void scanRoots(List<File> roots, Map<String, ClassEntry> output) {
            for (File root : roots) {
                if (!root.exists()) {
                    continue;
                }
                if (root.isDirectory()) {
                    scanDirectory(root, output);
                } else if (root.isFile() && AppClasses.isArchive(root)) {
                    scanJar(root, output);
                }
            }
        }

        private void scanDirectory(File root, Map<String, ClassEntry> output) {
            Path base = root.toPath();
            try {
                AppClasses.walkClassFiles(base, path -> addClassFile(base, path, output));
            } catch (IOException e) {
                log(Level.FINE, "Failed to scan class directory " + root.getAbsolutePath(), e);
            }
        }

        private void addClassFile(Path base, Path file, Map<String, ClassEntry> output) {
            String key = normalizePath(base.relativize(file).toString());
            if (shouldSkipClass(key)) {
                return;
            }

            try {
                output.putIfAbsent(key, new ClassEntry(classNameFromKey(key),
                        normalizePath(file.toAbsolutePath().toString()),
                        Files.readAllBytes(file)));
            } catch (IOException e) {
                log(Level.FINE, "Failed to read class file " + file, e);
            }
        }

        private void scanJar(File jarFile, Map<String, ClassEntry> output) {
            try (JarFile jar = new JarFile(jarFile)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                        continue;
                    }

                    String key = classKeyFromJarEntry(entry.getName());
                    if (key == null || shouldSkipClass(key) || output.containsKey(key)) {
                        continue;
                    }

                    try (InputStream inputStream = jar.getInputStream(entry)) {
                        output.put(key, new ClassEntry(
                                classNameFromKey(key),
                                normalizePath(jarFile.getAbsolutePath() + "!" + entry.getName()),
                                readAllBytes(inputStream)));
                    }
                }
            } catch (IOException e) {
                log(Level.FINE, "Failed to scan jar " + jarFile.getAbsolutePath(), e);
            }
        }

        private String classKeyFromJarEntry(String entryName) {
            if (entryName.startsWith("BOOT-INF/lib/")
                    || entryName.startsWith("WEB-INF/lib/")
                    || entryName.startsWith("META-INF/")
                    || entryName.startsWith("org/springframework/boot/loader/")) {
                return null;
            }
            if (entryName.startsWith("BOOT-INF/classes/")) {
                return entryName.substring("BOOT-INF/classes/".length());
            }
            if (entryName.startsWith("WEB-INF/classes/")) {
                return entryName.substring("WEB-INF/classes/".length());
            }
            return entryName;
        }

        // shouldSkipClass keeps keploy-sdk.jar's own classes out of the index:
        // its code (with the jacocoagent.jar it starts, under io/keploy/dedup),
        // the ASM, Gson and JaCoCo core it carries renamed under
        // io/keploy/shaded, JaCoCo itself (the runtime of any JaCoCo agent,
        // and an app's own copy), and junixsocket, which it carries under
        // its own names because its native library is bound to them. An app's
        // own ASM or Gson is one of the app's libraries, as Jackson is. The
        // shade plugin rewrites every string constant that starts with a
        // package it renames, so no prefix here may start with one.
        private boolean shouldSkipClass(String name) {
            return name.endsWith("module-info.class")
                    || name.endsWith("package-info.class")
                    || name.startsWith("io/keploy/dedup/")
                    || name.startsWith("io/keploy/servlet/")
                    || name.startsWith("io/keploy/shaded/")
                    || name.startsWith("org/jacoco/")
                    || name.startsWith("org/newsclub/")
                    || name.contains("$Mockito")
                    || name.contains("Test.class");
        }

        private String classNameFromKey(String key) {
            return key.substring(0, key.length() - ".class".length());
        }
    }

    private static final class CoveragePublisher {

        private final File socketFile;
        private final CoverageIndex index;
        // The receiver (receiverKey) the INDEX report last reached, null
        // before the first: one Keploy binds anew on the data socket's path (a
        // new run, a docker-compose test set, a restarted Keploy) gets it
        // again before its first record.
        private Object indexSentTo;
        // The receiver that asked this JVM for BACKGROUND records, null when
        // none has, or the receiver there now is another: a new run, a
        // restarted Keploy, or an older Keploy, which would read the record
        // as a test's coverage, get none until they ask. The ask lives in
        // this JVM only: an app started again has none until Keploy asks it.
        private Object backgroundAskedBy;
        // The between-tests windows whose record could not be sent since the
        // last one that was, told to the receiver in that next record.
        private int backgroundLost;

        private CoveragePublisher(File socketFile, CoverageIndex index) {
            this.socketFile = socketFile;
            this.index = index;
        }

        // sendIndex sends the INDEX report unless the receiver on the data
        // socket has it: once right after the control socket is bound, and
        // again before a test's coverage when the receiver there is new. With
        // no socket file there is no receiver to reach, and the report (which
        // reads every indexed class) is not made: a JVM that runs with no
        // Keploy never reads its index for it. A send that fails is tried
        // again before the next test's coverage. It never fails the caller:
        // Keploy then says only that it has no INDEX report.
        synchronized void sendIndex() {
            try {
                Object key = receiverKey(socketFile);
                if (key == null || key.equals(indexSentTo)) {
                    return;
                }
                send(index.report().toJson().getBytes(StandardCharsets.UTF_8));
                indexSentTo = key;
            } catch (Throwable t) {
                // Throwable: an Error here (the class ids' JaCoCo classes
                // missing) must not end the worker, nor fail a test's END.
                log(Level.FINE, "Could not send the Java dedup INDEX report to " + socketFile.getAbsolutePath(), t);
            }
        }

        // askBackground records that the receiver on the data socket now
        // asked for BACKGROUND records, and says whether there is one.
        synchronized boolean askBackground() {
            Object key = receiverKey(socketFile);
            if (key == null) {
                log(Level.INFO, "Keploy dedup: BACKGROUND is not taken: no receiver is on "
                        + socketFile.getAbsolutePath(), null);
                return false;
            }
            if (!key.equals(backgroundAskedBy)) {
                backgroundLost = 0;
            }
            backgroundAskedBy = key;
            return true;
        }

        synchronized boolean backgroundAsked() {
            return backgroundAskedBy != null;
        }

        // sendBackground sends a between-tests window's hits (classes makes
        // them, only when they go out) to the receiver that asked for them,
        // after the INDEX report when that receiver does not have it yet.
        // When the receiver there is another (or none), nothing is sent and
        // none goes to it until it asks. A record that could not be made or
        // sent is not tried again: the next one that goes through counts it
        // in "lost". It never fails the caller.
        synchronized void sendBackground(String after, String before, Supplier<TestCoverage> classes) {
            Object key = receiverKey(socketFile);
            if (key == null || !key.equals(backgroundAskedBy)) {
                backgroundAskedBy = null;
                backgroundLost = 0;
                return;
            }
            try {
                BackgroundRecord record = new BackgroundRecord(index.report().build, after, before, classes.get());
                if (backgroundLost > 0) {
                    record.lost = backgroundLost;
                }
                sendIndex();
                send(GSON.toJson(record).getBytes(StandardCharsets.UTF_8));
                backgroundLost = 0;
                if (diagnosticsEnabled()) {
                    diagnostic("BACKGROUND record sent: after=" + after + ", before=" + before
                            + ", classes=" + record.executedLinesByFile.size());
                }
            } catch (Throwable t) {
                // Throwable, as for the INDEX report: an Error here must not
                // end the worker. Said once while the sends fail, not once
                // per test.
                backgroundLost++;
                log(backgroundLost == 1 ? Level.WARNING : Level.FINE,
                        "Keploy dedup: the BACKGROUND record of the hits before " + before
                                + " could not be sent to " + socketFile.getAbsolutePath(), t);
            }
        }

        // publish sends the test's coverage, after the INDEX report when the
        // receiver does not have it yet: the report's time counts in "send".
        private void publish(String testId, TestCoverage coverage, Phases phases) throws IOException {
            byte[] payload = GSON.toJson(new DedupPayload(testId, coverage))
                    .getBytes(StandardCharsets.UTF_8);
            phases.mark("json");
            sendIndex();
            send(payload);
            phases.mark("send");
        }

        // send writes payload on a connection of its own to the data socket:
        // one record per connection, ended by its close.
        private void send(byte[] payload) throws IOException {
            try (AFUNIXSocket socket = AFUNIXSocket.newInstance()) {
                socket.connect(AFUNIXSocketAddress.of(socketFile), SOCKET_TIMEOUT_MILLIS);
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                socket.getOutputStream().write(payload);
                socket.getOutputStream().flush();
            }
        }
    }

    // DedupPayload is the per-test wire payload (one COV frame).
    //
    // NOTE ON THE FIELD NAME: `executedLinesByFile` is a HISTORICAL wire key. Its
    // value is now the set of executed JaCoCo PROBE indices per VM class name
    // ({"com/foo/Bar": [probeIdx...]}) — NOT source lines by file. The key is kept
    // deliberately for backward compatibility with consumers that deserialize by
    // this exact name (enterprise dedup #2188, k8s-proxy #574), which treat the
    // value opaquely as key -> int-set. Renaming the JSON key is a coordinated
    // cross-repo break and must land in lockstep on all three sides.
    //
    // classMismatch names, in the order of executedLinesByFile, each class
    // there whose JaCoCo id is not the id of the bytes the index read: its
    // probes are not those of the indexed class. Left out when there is
    // none, so that the record is the same as before for every other test.
    // Receivers that do not know the key ignore it.
    //
    // contaminated names the scheduled jobs whose runs overlapped the
    // test's window (JobGate): "quartz:<group>.<name>" or
    // "scheduled:<class>.<method>", at most 8, then "+N" for N more. Their
    // hits are in executedLinesByFile with the test's own. Left out when
    // none ran in it, as classMismatch.
    private static final class DedupPayload {

        private final String id;
        // Probes-by-class (see class note); serialized under the historical key.
        private final Map<String, List<Integer>> executedLinesByFile;
        private final List<String> classMismatch;
        private final Boolean windowCut;
        private final List<String> contaminated;

        private DedupPayload(String id, TestCoverage coverage) {
            this.id = id;
            this.executedLinesByFile = coverage.probesByClass;
            this.classMismatch = coverage.classMismatch.isEmpty() ? null : coverage.classMismatch;
            this.windowCut = coverage.windowCut ? Boolean.TRUE : null;
            this.contaminated = coverage.contaminated.isEmpty() ? null : coverage.contaminated;
        }
    }

    // TestCoverage is a test's coverage as its END read it: the probes hit
    // by class, the classes among them that are not the class the index
    // read, whether the END was cut (its requests still ran at the end of
    // its wait), and the scheduled jobs that ran in its window.
    private static final class TestCoverage {

        static final TestCoverage NONE = new TestCoverage(Collections.<String, List<Integer>>emptyMap(),
                Collections.<String>emptyList());

        final Map<String, List<Integer>> probesByClass;
        final List<String> classMismatch;
        final boolean windowCut;
        final List<String> contaminated;

        TestCoverage(Map<String, List<Integer>> probesByClass, List<String> classMismatch) {
            this(probesByClass, classMismatch, false, Collections.<String>emptyList());
        }

        TestCoverage(Map<String, List<Integer>> probesByClass, List<String> classMismatch, boolean windowCut,
                List<String> contaminated) {
            this.probesByClass = probesByClass;
            this.classMismatch = classMismatch;
            this.windowCut = windowCut;
            this.contaminated = contaminated;
        }

        // ended is this coverage as its END sends it: cut when it was read
        // at the end of a wait its requests outran, and with the jobs that
        // ran in its window.
        TestCoverage ended(boolean cut, List<String> jobs) {
            return new TestCoverage(probesByClass, classMismatch, cut, jobs);
        }
    }

    // BackgroundWindow is what a START cleared after the END of `after`
    // sent that test's coverage, up to the START of `before`, until it is
    // made into a BackgroundRecord.
    private static final class BackgroundWindow {

        private final String after;
        private final String before;
        private final List<ProbeScan.Hit> cleared;

        private BackgroundWindow(String after, String before, List<ProbeScan.Hit> cleared) {
            this.after = after;
            this.before = before;
            this.cleared = cleared;
        }
    }

    // BackgroundRecord is what the app's classes ran between two tests: the
    // hits the START of `before` cleared, made after the END of `after` read
    // that test's. Sent on the data socket, one per connection, only to a
    // receiver that asked with BACKGROUND, and only for a window with hits.
    // build is the INDEX report's. executedLinesByFile is {vmClassName ->
    // [probeIdx]} under a test's record's key and in its shape (DedupPayload),
    // names in order and probes ascending, and classMismatch is as in a
    // test's record, left out when empty, so that Keploy reads both alike.
    // lost, when set, counts the windows before this one whose record could
    // not be made or sent.
    static final class BackgroundRecord {

        final String kind = "background";
        final int v = 1;
        final String build;
        final String after;
        final String before;
        final Map<String, List<Integer>> executedLinesByFile;
        final List<String> classMismatch;
        Integer lost;

        BackgroundRecord(String build, String after, String before, TestCoverage coverage) {
            this.build = build;
            this.after = after;
            this.before = before;
            this.executedLinesByFile = coverage.probesByClass;
            this.classMismatch = coverage.classMismatch.isEmpty() ? null : coverage.classMismatch;
        }
    }

    private static final class ClassEntry {

        private final String className;
        private final String location;
        private final byte[] bytes;

        private ClassEntry(String className, String location, byte[] bytes) {
            this.className = className;
            this.location = location;
            this.bytes = bytes;
        }
    }

}
