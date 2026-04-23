package io.keploy.dedup;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
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
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class KeployDedupAgent {

    private static final Logger LOGGER = LogManager.getLogger(KeployDedupAgent.class);
    private static final String CONTROL_SOCKET_PATH = "/tmp/coverage_control.sock";
    private static final String DATA_SOCKET_PATH = "/tmp/coverage_data.sock";
    private static final String DEFAULT_JACOCO_HOST = "127.0.0.1";
    private static final int DEFAULT_JACOCO_PORT = 36320;
    private static final int SOCKET_BACKLOG = 50;
    private static final int SOCKET_TIMEOUT_MILLIS = 3000;
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final Gson GSON = new Gson();
    private static volatile ControlServer server;
    private static volatile Thread serverThread;

    private KeployDedupAgent() {
    }

    public static boolean start() {
        if (isDisabled()) {
            return false;
        }
        if (!STARTED.compareAndSet(false, true)) {
            return true;
        }

        ControlServer controlServer = new ControlServer(new JacocoCoverageCollector(), new UnixSocketCoverageSender());
        Thread thread = new Thread(controlServer, "keploy-java-dedup-control");
        thread.setDaemon(true);
        server = controlServer;
        serverThread = thread;
        thread.start();
        LOGGER.debug("Keploy Java dynamic dedup agent started");
        return true;
    }

    public static boolean isStarted() {
        return STARTED.get();
    }

    public static void stop() {
        ControlServer controlServer = server;
        if (controlServer != null) {
            controlServer.close();
        }
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
        String trimmed = value.trim();
        return "true".equalsIgnoreCase(trimmed) || "1".equals(trimmed) || "yes".equalsIgnoreCase(trimmed);
    }

    private static final class ControlServer implements Runnable, Closeable {

        private final JacocoCoverageCollector collector;
        private final UnixSocketCoverageSender sender;
        private final ExecutorService workers;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Object stateLock = new Object();
        private volatile AFUNIXServerSocket socket;
        private String currentTestId = "";

        ControlServer(JacocoCoverageCollector collector, UnixSocketCoverageSender sender) {
            this.collector = collector;
            this.sender = sender;
            this.workers = Executors.newCachedThreadPool(new DaemonThreadFactory("keploy-java-dedup-worker"));
        }

        @Override
        public void run() {
            File socketFile = new File(CONTROL_SOCKET_PATH);
            if (socketFile.exists() && !socketFile.delete()) {
                LOGGER.warn("failed to remove old Keploy control socket: {}", CONTROL_SOCKET_PATH);
            }

            try (AFUNIXServerSocket serverSocket = AFUNIXServerSocket.newInstance()) {
                serverSocket.bind(AFUNIXSocketAddress.of(socketFile), SOCKET_BACKLOG);
                if (!socketFile.setReadable(true, false) || !socketFile.setWritable(true, false)) {
                    LOGGER.debug("failed to relax Keploy control socket permissions: {}", CONTROL_SOCKET_PATH);
                }
                socket = serverSocket;
                while (running.get()) {
                    try {
                        final Socket accepted = serverSocket.accept();
                        workers.submit(new Runnable() {
                            @Override
                            public void run() {
                                handle(accepted);
                            }
                        });
                    } catch (IOException e) {
                        if (running.get()) {
                            LOGGER.warn("failed to accept Keploy coverage command", e);
                        }
                    }
                }
            } catch (Throwable t) {
                STARTED.set(false);
                LOGGER.warn("Keploy Java dynamic dedup socket server is unavailable", t);
            } finally {
                workers.shutdownNow();
                if (socketFile.exists() && !socketFile.delete()) {
                    LOGGER.debug("failed to delete Keploy control socket on shutdown: {}", CONTROL_SOCKET_PATH);
                }
            }
        }

        private void handle(Socket accepted) {
            try (Socket commandSocket = accepted;
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(commandSocket.getInputStream(), StandardCharsets.UTF_8))) {
                String command = reader.readLine();
                if (command == null || command.trim().isEmpty()) {
                    return;
                }
                handleCommand(command.trim(), commandSocket.getOutputStream());
            } catch (IOException e) {
                LOGGER.warn("failed to handle Keploy coverage command", e);
            }
        }

        private void handleCommand(String command, OutputStream outputStream) throws IOException {
            String[] parts = command.split(" ", 2);
            if (parts.length != 2) {
                LOGGER.warn("invalid Keploy coverage command: {}", command);
                writeAck(outputStream);
                return;
            }

            String action = parts[0];
            String testId = parts[1];
            synchronized (stateLock) {
                if ("START".equals(action)) {
                    currentTestId = testId;
                    collector.reset();
                    writeAck(outputStream);
                    return;
                }
                if ("END".equals(action)) {
                    if (!testId.equals(currentTestId)) {
                        LOGGER.warn("mismatched Keploy coverage END command. expected={}, actual={}", currentTestId, testId);
                        writeAck(outputStream);
                        return;
                    }
                    try {
                        Map<String, List<Integer>> executedLinesByFile = collector.collect();
                        if (!executedLinesByFile.isEmpty()) {
                            sender.send(new DedupRecord(testId, executedLinesByFile));
                        } else {
                            LOGGER.debug("no Java coverage lines found for Keploy test {}", testId);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("failed to collect Java coverage for Keploy test {}", testId, e);
                    } finally {
                        currentTestId = "";
                        writeAck(outputStream);
                    }
                    return;
                }
            }

            LOGGER.warn("unknown Keploy coverage command action: {}", action);
            writeAck(outputStream);
        }

        private void writeAck(OutputStream outputStream) throws IOException {
            outputStream.write("ACK\n".getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        }

        @Override
        public void close() {
            running.set(false);
            AFUNIXServerSocket serverSocket = socket;
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    LOGGER.debug("failed to close Keploy control socket", e);
                }
            }
            workers.shutdownNow();
        }
    }

    private static final class JacocoCoverageCollector {

        private final String host;
        private final int port;
        private final Object classCacheLock = new Object();
        private volatile List<ClassResource> classResources;

        JacocoCoverageCollector() {
            this.host = envOrProperty("KEPLOY_JACOCO_HOST", "keploy.jacoco.host", DEFAULT_JACOCO_HOST);
            this.port = parsePort(envOrProperty("KEPLOY_JACOCO_PORT", "keploy.jacoco.port",
                    String.valueOf(DEFAULT_JACOCO_PORT)));
        }

        void reset() {
            try {
                dump(false, true);
            } catch (IOException e) {
                LOGGER.debug("failed to reset JaCoCo coverage counters", e);
            }
        }

        Map<String, List<Integer>> collect() throws IOException {
            byte[] executionData = dump(true, true);
            if (executionData.length == 0) {
                return Collections.emptyMap();
            }

            ExecFileLoader loader = new ExecFileLoader();
            loader.load(new ByteArrayInputStream(executionData));

            CoverageBuilder coverageBuilder = new CoverageBuilder();
            Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), coverageBuilder);
            for (ClassResource classResource : getClassResources()) {
                try {
                    analyzer.analyzeClass(classResource.bytes, classResource.location);
                } catch (IOException | RuntimeException e) {
                    LOGGER.debug("failed to analyze Java class for Keploy dynamic dedup: {}",
                            classResource.location, e);
                }
            }

            Map<String, Set<Integer>> linesByFile = new LinkedHashMap<>();
            for (IClassCoverage coverage : coverageBuilder.getClasses()) {
                if (!coverage.containsCode()) {
                    continue;
                }
                List<Integer> coveredLines = coveredLines(coverage);
                if (coveredLines.isEmpty()) {
                    continue;
                }
                String sourcePath = sourcePath(coverage);
                Set<Integer> lines = linesByFile.get(sourcePath);
                if (lines == null) {
                    lines = new LinkedHashSet<>();
                    linesByFile.put(sourcePath, lines);
                }
                lines.addAll(coveredLines);
            }

            return sortedLines(linesByFile);
        }

        private byte[] dump(boolean dump, boolean reset) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
            ExecutionDataWriter localWriter = new ExecutionDataWriter(output);

            try (Socket jacocoSocket = new Socket()) {
                jacocoSocket.connect(new InetSocketAddress(InetAddress.getByName(host), port), SOCKET_TIMEOUT_MILLIS);
                jacocoSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                RemoteControlWriter writer = new RemoteControlWriter(jacocoSocket.getOutputStream());
                RemoteControlReader reader = new RemoteControlReader(jacocoSocket.getInputStream());
                reader.setSessionInfoVisitor(localWriter);
                reader.setExecutionDataVisitor(localWriter);
                writer.visitDumpCommand(dump, reset);
                if (!reader.read()) {
                    throw new IOException("JaCoCo remote socket closed before coverage data was returned");
                }
            }

            return output.toByteArray();
        }

        private List<ClassResource> getClassResources() {
            List<ClassResource> cached = classResources;
            if (cached != null) {
                return cached;
            }
            synchronized (classCacheLock) {
                cached = classResources;
                if (cached == null) {
                    cached = ClasspathScanner.scan();
                    classResources = cached;
                    LOGGER.debug("cached {} Java class resources for Keploy dynamic dedup", cached.size());
                }
                return cached;
            }
        }

        private static List<Integer> coveredLines(IClassCoverage coverage) {
            List<Integer> lines = new ArrayList<>();
            for (int line = coverage.getFirstLine(); line <= coverage.getLastLine(); line++) {
                int status = coverage.getLine(line).getStatus();
                if (status != ICounter.EMPTY && status != ICounter.NOT_COVERED) {
                    lines.add(line);
                }
            }
            return lines;
        }

        private static Map<String, List<Integer>> sortedLines(Map<String, Set<Integer>> linesByFile) {
            Map<String, List<Integer>> result = new LinkedHashMap<>();
            List<String> files = new ArrayList<>(linesByFile.keySet());
            Collections.sort(files);
            for (String file : files) {
                List<Integer> lines = new ArrayList<>(linesByFile.get(file));
                Collections.sort(lines);
                result.put(file, lines);
            }
            return result;
        }

        private static String sourcePath(IClassCoverage coverage) {
            String packageName = coverage.getPackageName();
            String sourceFileName = coverage.getSourceFileName();
            if (sourceFileName == null || sourceFileName.trim().isEmpty()) {
                return normalizePath(coverage.getName() + ".java");
            }

            String relative = packageName == null || packageName.isEmpty()
                    ? sourceFileName
                    : packageName + "/" + sourceFileName;
            File source = new File(System.getProperty("user.dir"), "src/main/java/" + relative);
            if (source.exists()) {
                return normalizePath(source.getAbsolutePath());
            }
            return normalizePath(relative);
        }

        private static int parsePort(String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                LOGGER.warn("invalid JaCoCo port '{}', using {}", value, DEFAULT_JACOCO_PORT);
                return DEFAULT_JACOCO_PORT;
            }
        }
    }

    private static final class UnixSocketCoverageSender {

        void send(DedupRecord record) throws IOException {
            byte[] payload = GSON.toJson(record).getBytes(StandardCharsets.UTF_8);
            try (AFUNIXSocket socket = AFUNIXSocket.newInstance()) {
                socket.connect(AFUNIXSocketAddress.of(new File(DATA_SOCKET_PATH)), SOCKET_TIMEOUT_MILLIS);
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                socket.getOutputStream().write(payload);
                socket.getOutputStream().flush();
            }
        }
    }

    private static final class ClasspathScanner {

        private static List<ClassResource> scan() {
            Map<String, ClassResource> resources = new LinkedHashMap<>();
            scanRoots(applicationRoots(), resources);
            if (resources.isEmpty()) {
                scanRoots(classpathRoots(), resources);
            }
            List<ClassResource> result = new ArrayList<>(resources.values());
            Collections.sort(result, new Comparator<ClassResource>() {
                @Override
                public int compare(ClassResource left, ClassResource right) {
                    return left.location.compareTo(right.location);
                }
            });
            return result;
        }

        private static void scanRoots(List<File> roots, Map<String, ClassResource> resources) {
            for (File root : roots) {
                if (!root.exists()) {
                    continue;
                }
                if (root.isDirectory()) {
                    scanDirectory(root, resources);
                } else if (root.isFile() && root.getName().endsWith(".jar")) {
                    scanJar(root, resources);
                }
            }
        }

        private static List<File> applicationRoots() {
            LinkedHashSet<File> roots = new LinkedHashSet<>();
            addConfiguredRoots(roots);
            addDefaultRoot(roots, "target/classes");
            addDefaultRoot(roots, "build/classes/java/main");
            return new ArrayList<>(roots);
        }

        private static List<File> classpathRoots() {
            LinkedHashSet<File> roots = new LinkedHashSet<>();
            addClasspathRoots(roots);
            return new ArrayList<>(roots);
        }

        private static void addConfiguredRoots(Set<File> roots) {
            String configured = envOrProperty("KEPLOY_JAVA_CLASS_DIRS", "keploy.java.class.dirs", "");
            if (configured == null || configured.trim().isEmpty()) {
                return;
            }
            String[] parts = configured.split(Pattern.quote(File.pathSeparator));
            for (String part : parts) {
                if (!part.trim().isEmpty()) {
                    roots.add(new File(part.trim()));
                }
            }
        }

        private static void addDefaultRoot(Set<File> roots, String relativePath) {
            roots.add(new File(System.getProperty("user.dir"), relativePath));
        }

        private static void addClasspathRoots(Set<File> roots) {
            String classpath = System.getProperty("java.class.path", "");
            if (classpath.trim().isEmpty()) {
                return;
            }
            String[] entries = classpath.split(Pattern.quote(File.pathSeparator));
            for (String entry : entries) {
                if (entry.trim().isEmpty()) {
                    continue;
                }
                File file = new File(entry.trim());
                if (file.isDirectory() || file.getName().endsWith(".jar")) {
                    roots.add(file);
                }
            }
        }

        private static void scanDirectory(File root, Map<String, ClassResource> resources) {
            Path rootPath = root.toPath();
            try (Stream<Path> stream = Files.walk(rootPath)) {
                stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class"))
                    .forEach(path -> addClassFile(rootPath, path, resources));
            } catch (IOException e) {
                LOGGER.debug("failed to scan Java class directory {}", root, e);
            }
        }

        private static void addClassFile(Path rootPath, Path path, Map<String, ClassResource> resources) {
            String relative = normalizePath(rootPath.relativize(path).toString());
            if (shouldSkipClass(relative)) {
                return;
            }
            try {
                resources.putIfAbsent(relative, new ClassResource(normalizePath(path.toAbsolutePath().toString()),
                        Files.readAllBytes(path)));
            } catch (IOException e) {
                LOGGER.debug("failed to read Java class file {}", path, e);
            }
        }

        private static void scanJar(File jar, Map<String, ClassResource> resources) {
            try (JarFile jarFile = new JarFile(jar)) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().endsWith(".class") || shouldSkipClass(entry.getName())) {
                        continue;
                    }
                    String key = jarClassKey(entry.getName());
                    if (key == null || resources.containsKey(key)) {
                        continue;
                    }
                    try (InputStream inputStream = jarFile.getInputStream(entry)) {
                        resources.put(key, new ClassResource(jar.getAbsolutePath() + "!" + entry.getName(),
                                readAllBytes(inputStream)));
                    }
                }
            } catch (IOException e) {
                LOGGER.debug("failed to scan Java jar {}", jar, e);
            }
        }

        private static String jarClassKey(String entryName) {
            if (entryName.startsWith("BOOT-INF/lib/") || entryName.startsWith("WEB-INF/lib/")
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

        private static boolean shouldSkipClass(String name) {
            return name.endsWith("module-info.class") || name.endsWith("package-info.class")
                    || name.contains("$Mockito") || name.contains("Test.class");
        }
    }

    private static final class DedupRecord {

        private final String id;
        private final Map<String, List<Integer>> executedLinesByFile;

        DedupRecord(String id, Map<String, List<Integer>> executedLinesByFile) {
            this.id = id;
            this.executedLinesByFile = executedLinesByFile;
        }
    }

    private static final class ClassResource {

        private final String location;
        private final byte[] bytes;

        ClassResource(String location, byte[] bytes) {
            this.location = location;
            this.bytes = bytes;
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {

        private final String prefix;
        private int count;

        DaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            count++;
            Thread thread = new Thread(runnable, prefix + "-" + count);
            thread.setDaemon(true);
            return thread;
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

    private static byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(16 * 1024);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
