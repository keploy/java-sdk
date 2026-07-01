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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Collects per-testcase JaCoCo coverage and streams the executed probe set per
 * class ({className -&gt; [probeIdx]}) back to Keploy Enterprise.
 */
public final class KeployDedupAgent {

    private static final Logger LOGGER = Logger.getLogger(KeployDedupAgent.class.getName());
    private static final Gson GSON = new Gson();

    private static final String CONTROL_SOCKET_PATH = "/tmp/coverage_control.sock";
    private static final String DATA_SOCKET_PATH = "/tmp/coverage_data.sock";
    private static final String DEFAULT_JACOCO_HOST = "127.0.0.1";
    private static final int DEFAULT_JACOCO_PORT = 36320;
    private static final int SOCKET_TIMEOUT_MILLIS = 3000;
    private static final int SOCKET_BACKLOG = 50;

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean(false);
    // The active transport worker: a CommandServer (unix, local/docker) or a
    // CoverageTcpClient (TCP, k8s). Both are Closeable so stop() is transport-agnostic.
    private static volatile Closeable coverageWorker;

    private KeployDedupAgent() {
    }

    /**
     * JVM entrypoint used when the SDK is attached with {@code -javaagent}.
     *
     * @param agentArgs optional Java agent arguments
     * @param instrumentation JVM instrumentation handle
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        start();
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
        if (isDisabled()) {
            return false;
        }
        if (!STARTED.compareAndSet(false, true)) {
            return true;
        }

        CoverageCollector collector = new CoverageCollector(
                new JacocoClient(resolveHost(), resolvePort()),
                new CoverageIndex());

        Runnable worker;
        String threadName;
        String endpoint = resolveEndpoint();
        if (endpoint != null) {
            // k8s: the collector lives in a different pod, so there is no shared
            // /tmp. Dial the collector's TCP endpoint and run the same protocol.
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
            CommandServer server = new CommandServer(collector, new CoveragePublisher(new File(DATA_SOCKET_PATH)));
            worker = server;
            coverageWorker = server;
            threadName = "keploy-java-dedup-control";
        }

        Thread thread = new Thread(worker, threadName);
        thread.setDaemon(true);
        thread.start();
        registerShutdownHook();
        return true;
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
        Closeable worker = coverageWorker;
        if (worker != null) {
            try {
                worker.close();
            } catch (IOException e) {
                log(Level.FINE, "Failed to close Java dedup coverage worker", e);
            }
        }
        coverageWorker = null;
        STARTED.set(false);
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
    // (unsigned-hex), probeCount = JaCoCo probe array length.
    private static final class ManifestEntry {
        private String id;
        private int probeCount;
    }

    // bytecodeAlreadyStored HEAD-checks whether k8s-proxy already holds the
    // bytecode blob for this build tag, so each ephemeral replay pod uploads
    // at most once per build. Best-effort: any error => treat as absent and
    // attempt the upload (the server dedupes idempotently by buildTag anyway).
    private static boolean bytecodeAlreadyStored(String baseUrl, String buildTag) {
        try {
            URL u = new URL(baseUrl + (baseUrl.contains("?") ? "&" : "?") + "buildTag=" + urlEncode(buildTag));
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            relaxTlsIfHttps(conn);
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(SOCKET_TIMEOUT_MILLIS);
            conn.setReadTimeout(SOCKET_TIMEOUT_MILLIS);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == HttpURLConnection.HTTP_OK;
        } catch (Exception e) {
            return false;
        }
    }

    // postBytecode uploads the manifest JSON + classes zip as a multipart form
    // to k8s-proxy's bytecode endpoint, tagged with the build tag.
    private static void postBytecode(String baseUrl, String buildTag, String manifestJson, byte[] zipBytes)
            throws IOException {
        String boundary = "keployBytecodeBoundary" + Integer.toHexString(System.identityHashCode(zipBytes));
        URL u = new URL(baseUrl + (baseUrl.contains("?") ? "&" : "?") + "buildTag=" + urlEncode(buildTag));
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        relaxTlsIfHttps(conn);
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(SOCKET_TIMEOUT_MILLIS);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream out = conn.getOutputStream()) {
            writeAscii(out, "--" + boundary + "\r\n");
            writeAscii(out, "Content-Disposition: form-data; name=\"manifest\"; filename=\"manifest.json\"\r\n");
            writeAscii(out, "Content-Type: application/json\r\n\r\n");
            out.write(manifestJson.getBytes(StandardCharsets.UTF_8));
            writeAscii(out, "\r\n--" + boundary + "\r\n");
            writeAscii(out, "Content-Disposition: form-data; name=\"classes\"; filename=\"classes.zip\"\r\n");
            writeAscii(out, "Content-Type: application/zip\r\n\r\n");
            out.write(zipBytes);
            writeAscii(out, "\r\n--" + boundary + "--\r\n");
            out.flush();
        }
        int code = conn.getResponseCode();
        conn.disconnect();
        if (code / 100 != 2) {
            throw new IOException("bytecode upload returned HTTP " + code);
        }
    }

    private static void writeAscii(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.US_ASCII));
    }

    // relaxTlsIfHttps makes the bytecode upload tolerate k8s-proxy's self-signed
    // in-cluster cert. The upload is a best-effort, cluster-internal data-plane
    // call (like the raw-TCP coverage collector), so trust-all here is acceptable
    // and avoids depending on the app JVM's truststore chaining to the proxy CA.
    private static volatile javax.net.ssl.SSLSocketFactory trustAllFactory;

    private static void relaxTlsIfHttps(HttpURLConnection conn) {
        if (!(conn instanceof javax.net.ssl.HttpsURLConnection)) {
            return;
        }
        try {
            if (trustAllFactory == null) {
                javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
                ctx.init(null, new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {
                    }

                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {
                    }

                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                }}, null);
                trustAllFactory = ctx.getSocketFactory();
            }
            javax.net.ssl.HttpsURLConnection https = (javax.net.ssl.HttpsURLConnection) conn;
            https.setSSLSocketFactory(trustAllFactory);
            https.setHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            // Leave the default factory; the upload is best-effort.
        }
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
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

    private static final class CommandServer implements Runnable, Closeable {

        private final CoverageCollector collector;
        private final CoveragePublisher publisher;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Object testCaseLock = new Object();
        private volatile AFUNIXServerSocket serverSocket;
        private String activeTestId = "";

        CommandServer(CoverageCollector collector, CoveragePublisher publisher) {
            this.collector = collector;
            this.publisher = publisher;
        }

        @Override
        public void run() {
            File controlSocket = new File(CONTROL_SOCKET_PATH);
            deleteSocketFile(controlSocket);

            try (AFUNIXServerSocket localServer = AFUNIXServerSocket.newInstance()) {
                localServer.bind(AFUNIXSocketAddress.of(controlSocket), SOCKET_BACKLOG);
                relaxSocketPermissions(controlSocket);
                serverSocket = localServer;

                while (running.get()) {
                    try {
                        handle(localServer.accept());
                    } catch (IOException e) {
                        if (running.get()) {
                            log(Level.SEVERE, "Failed to accept Java dedup coverage command", e);
                        }
                    }
                }
            } catch (Throwable t) {
                STARTED.set(false);
                log(Level.SEVERE, "Java dedup control socket server is unavailable", t);
            } finally {
                deleteSocketFile(controlSocket);
            }
        }

        private void handle(Socket socket) {
            try (Socket commandSocket = socket;
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(commandSocket.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line == null || line.trim().isEmpty()) {
                    return;
                }

                CoverageCommand command = CoverageCommand.parse(line.trim());
                if (command == null) {
                    return;
                }

                dispatch(command, commandSocket.getOutputStream());
            } catch (IOException e) {
                log(Level.SEVERE, "Failed to handle Java dedup coverage command", e);
            }
        }

        private void dispatch(CoverageCommand command, OutputStream outputStream) {
            synchronized (testCaseLock) {
                if (command.action == CommandAction.START) {
                    activeTestId = command.testId;
                    // Warm up app classes once before the first measured window so
                    // one-time <clinit> lines aren't charged to the first test.
                    collector.warmup();
                    // Best-effort, async, once-per-build: ship bytecode + manifest so
                    // k8s-proxy can compute line/branch coverage offline (Option B).
                    collector.exportBuildArtifactsOnce();
                    collector.reset();
                    writeAck(outputStream);
                    return;
                }

                if (command.action == CommandAction.END) {
                    if (!command.testId.equals(activeTestId)) {
                        log(Level.SEVERE,
                                "Ignoring mismatched END command. expected=" + activeTestId + ", actual="
                                        + command.testId,
                                null);
                        writeAck(outputStream);
                        return;
                    }

                    try {
                        Map<String, List<Integer>> executedLinesByFile = collector.capture();
                        if (executedLinesByFile.isEmpty()) {
                            log(Level.FINE, "No Java coverage lines collected for " + command.testId, null);
                        }
                        publisher.publish(command.testId, executedLinesByFile);
                    } catch (Exception e) {
                        log(Level.SEVERE, "Failed to collect Java coverage for " + command.testId, e);
                    } finally {
                        activeTestId = "";
                        writeAck(outputStream);
                    }
                    return;
                }
            }
        }

        private void writeAck(OutputStream outputStream) {
            try {
                outputStream.write("ACK\n".getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            } catch (IOException e) {
                log(Level.FINE, "Java dedup control client disconnected before ACK was delivered", e);
            }
        }

        @Override
        public void close() {
            running.set(false);
            AFUNIXServerSocket localServer = serverSocket;
            if (localServer != null) {
                try {
                    localServer.close();
                } catch (IOException e) {
                    log(Level.FINE, "Failed to close Java dedup control socket", e);
                }
            }
        }
    }

    /**
     * TCP transport (k8s): the SDK dials the collector and keeps one bidirectional
     * connection open for the whole replay. Mirrors {@link CommandServer}'s dispatch
     * but inverts the roles — here the SDK is the client. Wire protocol:
     * <pre>
     *   collector -&gt; SDK : "START &lt;id&gt;" | "END &lt;id&gt;"
     *   SDK -&gt; collector : "ACK"                        (after START reset)
     *                      "COV &lt;compact-json&gt;" + "ACK" (after END dump)
     * </pre>
     * The collector starts listening only when replay begins, so the connect loop
     * retries until it is reachable.
     */
    private static final class CoverageTcpClient implements Runnable, Closeable {

        private static final long RECONNECT_DELAY_MILLIS = 1000;

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
            socket = open;
            connectFailureLogged = false;
            log(Level.INFO, "Keploy dedup: connected to collector at " + host + ":" + port, null);
            try (Socket active = open;
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(active.getInputStream(), StandardCharsets.UTF_8))) {
                OutputStream out = active.getOutputStream();
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
                    dispatch(command, out);
                }
            } finally {
                socket = null;
            }
        }

        private void dispatch(CoverageCommand command, OutputStream out) throws IOException {
            synchronized (testCaseLock) {
                if (command.action == CommandAction.START) {
                    activeTestId = command.testId;
                    // Warm up app classes once before the first measured window so
                    // one-time <clinit> lines aren't charged to the first test.
                    collector.warmup();
                    // Best-effort, async, once-per-build: ship bytecode + manifest so
                    // k8s-proxy can compute line/branch coverage offline (Option B).
                    collector.exportBuildArtifactsOnce();
                    collector.reset();
                    writeLine(out, "ACK");
                    return;
                }

                if (command.action == CommandAction.END) {
                    if (!command.testId.equals(activeTestId)) {
                        log(Level.SEVERE,
                                "Ignoring mismatched END command. expected=" + activeTestId + ", actual="
                                        + command.testId,
                                null);
                        writeLine(out, "ACK");
                        return;
                    }

                    try {
                        Map<String, List<Integer>> executedProbesByClass = collector.capture();
                        if (executedProbesByClass.isEmpty()) {
                            log(Level.FINE, "No Java coverage collected for " + command.testId, null);
                        }
                        // Always emit COV before ACK — even when empty — so the
                        // line-oriented collector reads exactly one COV per END (the
                        // unix transport likewise always publishes). The payload is
                        // recorded before the ACK releases the caller.
                        writeLine(out, "COV " + GSON.toJson(
                                new DedupPayload(command.testId, executedProbesByClass)));
                    } catch (Exception e) {
                        log(Level.SEVERE, "Failed to collect Java coverage for " + command.testId, e);
                    } finally {
                        activeTestId = "";
                        writeLine(out, "ACK");
                    }
                }
            }
        }

        private void writeLine(OutputStream out, String message) throws IOException {
            out.write((message + "\n").getBytes(StandardCharsets.UTF_8));
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
        private final AtomicBoolean warmed = new AtomicBoolean(false);
        // Once-per-JVM guard for the build-artifact (bytecode+manifest) upload.
        private static final AtomicBoolean ARTIFACTS_EXPORTED = new AtomicBoolean(false);

        private CoverageCollector(JacocoClient jacocoClient, CoverageIndex coverageIndex) {
            this.jacocoClient = jacocoClient;
            this.coverageIndex = coverageIndex;
        }

        private void reset() {
            try {
                jacocoClient.dump(false, true);
            } catch (IOException e) {
                log(Level.FINE, "Failed to reset JaCoCo counters", e);
            }
        }

        /**
         * Eagerly initialize every indexed application class so their static
         * initializers (&lt;clinit&gt;) run ONCE here, before the first test's
         * coverage window. The very first request to a fresh JVM otherwise pays
         * the one-time class-init cost, and JaCoCo charges those &lt;clinit&gt;
         * lines to whichever test ran first — making the duplicate set
         * non-deterministic run-to-run. Running them now (then letting the START
         * reset clear the counters) means every test sees only the lines its own
         * request executes. Called once, on the first START, when the app is
         * fully started. Best-effort: per-class failures are ignored (the class
         * just falls back to lazy init). Disable with
         * KEPLOY_JAVA_DEDUP_WARMUP_DISABLED=true if a static initializer has
         * harmful side effects.
         */
        private void warmup() {
            if (isWarmupDisabled() || !warmed.compareAndSet(false, true)) {
                return;
            }
            ClassLoader[] loaders = warmupLoaders();
            int initialized = 0;
            int failed = 0;
            for (ClassEntry entry : coverageIndex.entries()) {
                if (initializeClass(entry.className.replace('/', '.'), loaders)) {
                    initialized++;
                } else {
                    failed++;
                }
            }
            log(Level.INFO, "Keploy dedup: warmed up application classes (initialized="
                    + initialized + ", skipped=" + failed + ")", null);
        }

        private boolean isWarmupDisabled() {
            return isTruthy(envOrProperty("KEPLOY_JAVA_DEDUP_WARMUP_DISABLED",
                    "keploy.java.dedup.warmup.disabled", ""));
        }

        private ClassLoader[] warmupLoaders() {
            List<ClassLoader> loaders = new ArrayList<>(3);
            ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            if (ctx != null) {
                loaders.add(ctx);
            }
            ClassLoader sys = ClassLoader.getSystemClassLoader();
            if (sys != null && !loaders.contains(sys)) {
                loaders.add(sys);
            }
            ClassLoader own = KeployDedupAgent.class.getClassLoader();
            if (own != null && !loaders.contains(own)) {
                loaders.add(own);
            }
            return loaders.toArray(new ClassLoader[0]);
        }

        private boolean initializeClass(String binaryName, ClassLoader[] loaders) {
            for (ClassLoader loader : loaders) {
                try {
                    Class.forName(binaryName, true, loader);
                    return true;
                } catch (Throwable ignored) {
                    // Try the next loader; a class that no loader can initialize
                    // (or whose <clinit> throws) is simply left to lazy init.
                }
            }
            return false;
        }

        private Map<String, List<Integer>> capture() throws IOException {
            byte[] dump = jacocoClient.dump(true, true);
            if (dump.length == 0) {
                if (diagnosticsEnabled()) {
                    diagnostic("JaCoCo dump returned 0 bytes");
                }
                return Collections.emptyMap();
            }

            ExecFileLoader loader = new ExecFileLoader();
            loader.load(new ByteArrayInputStream(dump));
            ExecutionDataStore executionDataStore = loader.getExecutionDataStore();

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
                return Collections.emptyMap();
            }

            Map<String, Set<Integer>> raw = new LinkedHashMap<>();
            for (ExecutionData executionData : executionDataStore.getContents()) {
                if (!executionData.hasHits()) {
                    continue;
                }
                if (!appClasses.contains(executionData.getName())) {
                    continue;
                }
                boolean[] probes = executionData.getProbes();
                Set<Integer> fired = new LinkedHashSet<>();
                for (int i = 0; i < probes.length; i++) {
                    if (probes[i]) {
                        fired.add(i);
                    }
                }
                if (!fired.isEmpty()) {
                    raw.put(executionData.getName(), fired);
                }
            }

            if (diagnosticsEnabled()) {
                diagnostic("classesWithProbes=" + raw.size()
                        + ", sampleClasses=" + summarizeStrings(raw.keySet(), 5));
            }

            return toSortedMap(raw);
        }

        // indexedClassNames returns the VM names of the app's own classes (from the
        // CoverageIndex) so probe collection can skip JDK/library classes.
        private Set<String> indexedClassNames() {
            Set<String> names = new LinkedHashSet<>();
            for (ClassEntry entry : coverageIndex.entries()) {
                names.add(entry.className);
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

        private Map<String, List<Integer>> toSortedMap(Map<String, Set<Integer>> raw) {
            List<String> files = new ArrayList<>(raw.keySet());
            Collections.sort(files);

            Map<String, List<Integer>> sorted = new LinkedHashMap<>();
            for (String file : files) {
                List<Integer> lines = new ArrayList<>(raw.get(file));
                Collections.sort(lines);
                sorted.put(file, lines);
            }
            return sorted;
        }

        // exportBuildArtifactsOnce uploads (ONCE per JVM/build) the app's class
        // bytecode + a {className -> (classId, probeCount)} manifest to k8s-proxy,
        // so the offline CoverageReporter can later reconstruct line/branch
        // coverage from the persisted dedup-fingerprint probe union (Option B).
        // Best-effort and fully async: coverage reporting must never affect the
        // app or the per-test dedup path. Gated by KEPLOY_BYTECODE_UPLOAD_URL +
        // KEPLOY_BUILD_TAG (both injected by the webhook); absent => no-op, so
        // existing dedup-only deployments are unchanged. Triggered after warmup()
        // so every app class is loaded and appears in the manifest.
        void exportBuildArtifactsOnce() {
            final String url = envOrProperty("KEPLOY_BYTECODE_UPLOAD_URL", "keploy.bytecode.upload.url", "");
            final String buildTag = envOrProperty("KEPLOY_BUILD_TAG", "keploy.build.tag", "");
            if (url.isEmpty() || buildTag.isEmpty()) {
                return;
            }
            if (!ARTIFACTS_EXPORTED.compareAndSet(false, true)) {
                return;
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (bytecodeAlreadyStored(url, buildTag)) {
                            log(Level.FINE, "Keploy dedup: bytecode already stored for buildTag " + buildTag, null);
                            return;
                        }
                        Map<String, ManifestEntry> manifest = buildBuildManifest();
                        if (manifest.isEmpty()) {
                            // No app classes visible yet — allow a later START to retry.
                            ARTIFACTS_EXPORTED.set(false);
                            return;
                        }
                        byte[] zip = zipIndexedClasses();
                        postBytecode(url, buildTag, GSON.toJson(manifest), zip);
                        log(Level.INFO, "Keploy dedup: uploaded bytecode+manifest for buildTag "
                                + buildTag + " (" + manifest.size() + " classes)", null);
                    } catch (Throwable e) {
                        log(Level.FINE, "Keploy dedup: bytecode export failed (non-fatal)", null);
                    }
                }
            }, "keploy-dedup-bytecode-export");
            t.setDaemon(true);
            t.start();
        }

        // buildBuildManifest reads a non-resetting JaCoCo dump and records each
        // app class's runtime classId (== the CRC64 the offline Analyzer computes
        // for the same bytecode) and probe count. These are build-constant, so a
        // single post-warmup dump captures the whole manifest — and it means the
        // reporter never needs JaCoCo internal APIs to derive id/probeCount.
        private Map<String, ManifestEntry> buildBuildManifest() throws IOException {
            Map<String, ManifestEntry> manifest = new LinkedHashMap<>();
            byte[] dump = jacocoClient.dump(true, false);
            if (dump.length == 0) {
                return manifest;
            }
            ExecFileLoader loader = new ExecFileLoader();
            loader.load(new ByteArrayInputStream(dump));
            Set<String> appClasses = indexedClassNames();
            for (ExecutionData data : loader.getExecutionDataStore().getContents()) {
                if (!appClasses.contains(data.getName())) {
                    continue;
                }
                ManifestEntry entry = new ManifestEntry();
                entry.id = Long.toHexString(data.getId());
                entry.probeCount = data.getProbes().length;
                manifest.put(data.getName(), entry);
            }
            return manifest;
        }

        // zipIndexedClasses packs every indexed app .class (already in memory as
        // ClassEntry.bytes) into a zip keyed by "<vmClassName>.class".
        private byte[] zipIndexedClasses() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(256 * 1024);
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                for (ClassEntry entry : coverageIndex.entries()) {
                    zos.putNextEntry(new ZipEntry(entry.className + ".class"));
                    zos.write(entry.bytes);
                    zos.closeEntry();
                }
            }
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

        private List<ClassEntry> loadEntries() {
            LinkedHashMap<String, ClassEntry> collected = new LinkedHashMap<>();
            List<File> applicationRoots = applicationRoots();
            List<File> executableArchiveRoots = Collections.emptyList();
            List<File> classpathRoots = Collections.emptyList();

            scanRoots(applicationRoots, collected);
            if (collected.isEmpty()) {
                executableArchiveRoots = executableArchiveRoots();
                scanRoots(executableArchiveRoots, collected);
            }
            if (collected.isEmpty() && isClasspathFallbackEnabled()) {
                classpathRoots = classpathRoots();
                scanRoots(classpathRoots, collected);
            }
            if (collected.isEmpty() && diagnosticsEnabled()) {
                diagnostic("no application classes indexed"
                        + ", applicationRoots=" + summarizeFiles(applicationRoots, 5)
                        + ", executableArchiveRoots=" + summarizeFiles(executableArchiveRoots, 5)
                        + ", classpathFallbackRoots=" + summarizeFiles(classpathRoots, 5)
                        + ", java.class.path=" + System.getProperty("java.class.path", "")
                        + ", sun.java.command=" + System.getProperty("sun.java.command", ""));
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

        private String summarizeFiles(Iterable<File> values, int limit) {
            List<String> sample = new ArrayList<>();
            for (File value : values) {
                sample.add(value.getPath());
                if (sample.size() >= limit) {
                    break;
                }
            }
            return sample.toString();
        }

        private List<File> applicationRoots() {
            LinkedHashSet<File> roots = new LinkedHashSet<>();

            String configured = envOrProperty("KEPLOY_JAVA_CLASS_DIRS", "keploy.java.class.dirs", "");
            if (!configured.trim().isEmpty()) {
                for (String part : configuredRoots(configured)) {
                    if (!part.trim().isEmpty()) {
                        roots.add(new File(part.trim()));
                    }
                }
            }

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

            addArchiveRoot(roots, firstCommandToken(System.getProperty("sun.java.command", "")));

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
            if (file.isFile() && isArchive(file)) {
                roots.add(file);
            }
        }

        private boolean isArchive(File file) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            return name.endsWith(".jar")
                    || name.endsWith(".war")
                    || name.endsWith(".ear")
                    || name.endsWith(".zip");
        }

        private String firstCommandToken(String command) {
            if (command == null) {
                return "";
            }

            String trimmed = command.trim();
            if (trimmed.isEmpty()) {
                return "";
            }

            char first = trimmed.charAt(0);
            if (first == '"' || first == '\'') {
                int end = trimmed.indexOf(first, 1);
                return end > 0 ? trimmed.substring(1, end) : trimmed.substring(1);
            }

            int end = 0;
            while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))) {
                end++;
            }
            return trimmed.substring(0, end);
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
                        if (file.isDirectory() || isArchive(file)) {
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
                } else if (root.isFile() && isArchive(root)) {
                    scanJar(root, output);
                }
            }
        }

        private void scanDirectory(File root, Map<String, ClassEntry> output) {
            Path base = root.toPath();
            try (Stream<Path> stream = Files.walk(base)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".class"))
                        .forEach(path -> addClassFile(base, path, output));
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

        private boolean shouldSkipClass(String name) {
            return name.endsWith("module-info.class")
                    || name.endsWith("package-info.class")
                    || name.startsWith("io/keploy/dedup/")
                    || name.startsWith("io/keploy/servlet/")
                    || name.startsWith("org/jacoco/")
                    || name.startsWith("org/objectweb/asm/")
                    || name.startsWith("org/newsclub/net/unix/")
                    || name.startsWith("com/google/gson/")
                    || name.contains("$Mockito")
                    || name.contains("Test.class");
        }

        private String classNameFromKey(String key) {
            return key.substring(0, key.length() - ".class".length());
        }
    }

    private static final class CoveragePublisher {

        private final File socketFile;

        private CoveragePublisher(File socketFile) {
            this.socketFile = socketFile;
        }

        private void publish(String testId, Map<String, List<Integer>> executedLinesByFile) throws IOException {
            byte[] payload = GSON.toJson(new DedupPayload(testId, executedLinesByFile))
                    .getBytes(StandardCharsets.UTF_8);

            try (AFUNIXSocket socket = AFUNIXSocket.newInstance()) {
                socket.connect(AFUNIXSocketAddress.of(socketFile), SOCKET_TIMEOUT_MILLIS);
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                socket.getOutputStream().write(payload);
                socket.getOutputStream().flush();
            }
        }
    }

    private static final class DedupPayload {

        private final String id;
        private final Map<String, List<Integer>> executedLinesByFile;

        private DedupPayload(String id, Map<String, List<Integer>> executedLinesByFile) {
            this.id = id;
            this.executedLinesByFile = executedLinesByFile;
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
