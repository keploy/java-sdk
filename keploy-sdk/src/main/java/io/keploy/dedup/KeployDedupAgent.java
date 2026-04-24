package io.keploy.dedup;

import com.google.gson.Gson;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Collects per-testcase JaCoCo coverage and streams executed lines back to Keploy Enterprise.
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
    private static volatile CommandServer commandServer;

    private KeployDedupAgent() {
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
        CommandServer server = new CommandServer(collector, new CoveragePublisher(new File(DATA_SOCKET_PATH)));
        Thread thread = new Thread(server, "keploy-java-dedup-control");
        thread.setDaemon(true);
        commandServer = server;
        thread.start();
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
        CommandServer server = commandServer;
        if (server != null) {
            server.close();
        }
        commandServer = null;
        STARTED.set(false);
    }

    private static boolean isDisabled() {
        return isTruthy(System.getenv("KEPLOY_JAVA_DEDUP_DISABLED"))
                || isTruthy(System.getProperty("keploy.java.dedup.disabled"));
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

    private static final class CommandServer implements Runnable, Closeable {

        private final CoverageCollector collector;
        private final CoveragePublisher publisher;
        private final ExecutorService workers;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Object testCaseLock = new Object();
        private volatile AFUNIXServerSocket serverSocket;
        private String activeTestId = "";

        CommandServer(CoverageCollector collector, CoveragePublisher publisher) {
            this.collector = collector;
            this.publisher = publisher;
            this.workers = Executors.newCachedThreadPool(new NamedDaemonFactory("keploy-java-dedup-worker"));
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
                        final Socket socket = localServer.accept();
                        workers.execute(new Runnable() {
                            @Override
                            public void run() {
                                handle(socket);
                            }
                        });
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
                workers.shutdownNow();
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
                    writeAck(commandSocket.getOutputStream());
                    return;
                }

                dispatch(command, commandSocket.getOutputStream());
            } catch (IOException e) {
                log(Level.SEVERE, "Failed to handle Java dedup coverage command", e);
            }
        }

        private void dispatch(CoverageCommand command, OutputStream outputStream) throws IOException {
            synchronized (testCaseLock) {
                if (command.action == CommandAction.START) {
                    activeTestId = command.testId;
                    collector.reset();
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

            writeAck(outputStream);
        }

        private void writeAck(OutputStream outputStream) throws IOException {
            outputStream.write("ACK\n".getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
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
            workers.shutdownNow();
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

        private Map<String, List<Integer>> capture() throws IOException {
            byte[] dump = jacocoClient.dump(true, true);
            if (dump.length == 0) {
                return Collections.emptyMap();
            }

            ExecFileLoader loader = new ExecFileLoader();
            loader.load(new ByteArrayInputStream(dump));
            ExecutionDataStore executionDataStore = loader.getExecutionDataStore();
            Set<String> hitClasses = hitClassNames(executionDataStore);
            if (hitClasses.isEmpty()) {
                return Collections.emptyMap();
            }

            CoverageBuilder coverageBuilder = new CoverageBuilder();
            Analyzer analyzer = new Analyzer(executionDataStore, coverageBuilder);
            for (ClassEntry classEntry : coverageIndex.entries()) {
                if (!hitClasses.contains(classEntry.className)) {
                    continue;
                }
                try {
                    analyzer.analyzeClass(classEntry.bytes, classEntry.location);
                } catch (IOException | RuntimeException e) {
                    log(Level.FINE, "Skipping unreadable Java class " + classEntry.location, e);
                }
            }

            Map<String, Set<Integer>> raw = new LinkedHashMap<>();
            for (IClassCoverage classCoverage : coverageBuilder.getClasses()) {
                if (!classCoverage.containsCode()) {
                    continue;
                }

                List<Integer> executedLines = executedLines(classCoverage);
                if (executedLines.isEmpty()) {
                    continue;
                }

                String sourcePath = resolveSourcePath(classCoverage);
                Set<Integer> merged = raw.get(sourcePath);
                if (merged == null) {
                    merged = new LinkedHashSet<>();
                    raw.put(sourcePath, merged);
                }
                merged.addAll(executedLines);
            }

            return toSortedMap(raw);
        }

        private Set<String> hitClassNames(ExecutionDataStore executionDataStore) {
            Set<String> names = new LinkedHashSet<>();
            for (ExecutionData executionData : executionDataStore.getContents()) {
                if (executionData.hasHits()) {
                    names.add(executionData.getName());
                }
            }
            return names;
        }

        private List<Integer> executedLines(IClassCoverage classCoverage) {
            int firstLine = classCoverage.getFirstLine();
            int lastLine = classCoverage.getLastLine();
            if (firstLine < 0 || lastLine < firstLine) {
                return Collections.emptyList();
            }

            List<Integer> lines = new ArrayList<>();
            for (int line = firstLine; line <= lastLine; line++) {
                int status = classCoverage.getLine(line).getStatus();
                if (status != ICounter.EMPTY && status != ICounter.NOT_COVERED) {
                    lines.add(line);
                }
            }
            return lines;
        }

        private String resolveSourcePath(IClassCoverage classCoverage) {
            String sourceFile = classCoverage.getSourceFileName();
            if (sourceFile == null || sourceFile.trim().isEmpty()) {
                return normalizePath(classCoverage.getName() + ".java");
            }

            String packageName = classCoverage.getPackageName();
            String relativePath = packageName == null || packageName.isEmpty()
                    ? sourceFile
                    : packageName + "/" + sourceFile;
            File localSource = new File(System.getProperty("user.dir"), "src/main/java/" + relativePath);
            if (localSource.exists()) {
                return normalizePath(localSource.getAbsolutePath());
            }
            return normalizePath(relativePath);
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
    }

    private static final class JacocoClient {

        private final String host;
        private final int port;

        private JacocoClient(String host, int port) {
            this.host = host;
            this.port = port;
        }

        private byte[] dump(boolean dump, boolean reset) throws IOException {
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
            scanRoots(applicationRoots(), collected);
            if (collected.isEmpty() && isClasspathFallbackEnabled()) {
                scanRoots(classpathRoots(), collected);
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
                    "keploy.java.classpath.fallback", "false"));
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
                        if (file.isDirectory() || file.getName().endsWith(".jar")) {
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
                } else if (root.isFile() && root.getName().endsWith(".jar")) {
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

    private static final class NamedDaemonFactory implements ThreadFactory {

        private final String prefix;
        private int counter;

        private NamedDaemonFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            counter++;
            Thread thread = new Thread(runnable, prefix + "-" + counter);
            thread.setDaemon(true);
            return thread;
        }
    }
}
