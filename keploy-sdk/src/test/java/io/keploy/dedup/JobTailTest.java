package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.example.jobs.DigestJobs;
import org.example.jobs.JobApp;
import org.example.jobs.OnDemandScheduler;
import org.example.jobs.PriorityPool;
import org.example.jobs.ReportJob;
import org.example.jobs.SpringJobs;
import org.example.jobs.Work;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A Quartz job and a Spring @Scheduled method that run in a test's window
 * are named in its record's contaminated, and runs between two tests are
 * in neither: JobApp in a JVM of its own with keploy-sdk.jar as its
 * -javaagent, driven as Keploy drives it over its TCP channel. An app with
 * neither scheduler on its class path runs as before.
 */
public class JobTailTest {

    private static final String QUARTZ = "\"quartz:reports.nightly\"";
    private static final String SCHEDULED = "\"scheduled:" + DigestJobs.class.getName() + ".sendDigest\"";
    private static final String POOL = "org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor";
    private static final String JOBS_ASYNC = "a task a scheduled job's run hands off counts as that run, so a test"
            + " whose window it runs in is marked contaminated in its record";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test(timeout = 120000)
    public void jobsThatRunInATestsWindowAreNamedAndRunsBetweenTestsAreNot() throws Exception {
        try (App app = new App(false, null)) {
            String cov = app.test("test-1", "quartz");
            assertEquals(cov, "[" + QUARTZ + "]", contaminated(cov));

            app.command("quartz");
            cov = app.test("test-2");
            assertEquals("a run between two tests is in neither: " + cov, "", contaminated(cov));

            cov = app.test("test-3", "spring");
            assertEquals(cov, "[" + SCHEDULED + "]", contaminated(cov));

            app.command("spring");
            cov = app.test("test-4");
            assertEquals(cov, "", contaminated(cov));

            // Started before the START, still running at it.
            app.command("spring-hold");
            cov = app.test("test-5", "spring-release");
            assertEquals(cov, "[" + SCHEDULED + "]", contaminated(cov));

            cov = app.test("test-6", "quartz", "spring", "quartz");
            assertEquals(cov, "[" + QUARTZ + "," + SCHEDULED + "]", contaminated(cov));

            // The job's hits stay in the record.
            assertTrue(cov, cov.contains("\"" + Work.class.getName().replace('.', '/') + "\""));
            assertTrue(app.log(), app.log().contains("Keploy dedup counts Quartz's job runs (org.quartz.core.JobRunShell):"
                    + " a test whose window a run overlaps is marked contaminated in its record"));
            assertTrue(app.log(), app.log().contains("Keploy dedup counts Spring's job runs"
                    + " (org.springframework.scheduling.support.ScheduledMethodRunnable)"));
            assertFalse(app.log(), app.log().contains("does not see the app's scheduled jobs"));
        }
    }

    @Test(timeout = 120000)
    public void jobsAreNamedWithTheEndWaitOff() throws Exception {
        // KEPLOY_JAVA_END_WAIT_MS=0 turns off the END's wait for requests,
        // not the count of job runs.
        try (App app = new App(false, "0")) {
            String cov = app.test("test-1", "quartz");
            assertEquals(cov, "[" + QUARTZ + "]", contaminated(cov));
            cov = app.test("test-2", "spring");
            assertEquals(cov, "[" + SCHEDULED + "]", contaminated(cov));
            cov = app.test("test-3");
            assertEquals(cov, "", contaminated(cov));
            // A job's async work counts as its run too.
            app.command("spring-publish-hold");
            cov = app.test("test-4", "listener-done");
            assertEquals(cov, "[" + SCHEDULED + "]", contaminated(cov));
            // END never waits with the wait off, for a job's task still
            // running at it either: it is named, and the END not cut.
            app.command("spring-publish-hold");
            cov = app.test("test-5");
            assertEquals(cov, "[" + SCHEDULED + "]", contaminated(cov));
            assertFalse(cov, cov.contains("windowCut"));
            String end = endLine(app, "test-5");
            assertFalse(end, end.contains(" requests="));
            app.command("listener-done");
            assertTrue(app.log(), app.log().contains("Keploy dedup counts Spring's async tasks ("
                    + POOL + "): " + JOBS_ASYNC));
        }
    }

    @Test(timeout = 120000)
    public void aJobsAsyncWorkMarksTheTestWhoseWindowItRunsInAfterTheJobReturned() throws Exception {
        try (App app = new App(false, null)) {
            // A Quartz job publishes to an async multicaster and returns. Its listener waits for the pool's
            // one thread, which the app's own task holds, so it starts only in the next test's window.
            app.command("listener-block");
            app.command("quartz-publish");
            String cov = app.test("test-1", "listener-unblock", "listener-done");
            assertEquals("the app's own task is no job's: " + cov, "[" + QUARTZ + "]", contaminated(cov));
            assertTrue("the listener's hits land in the test: " + cov,
                    cov.contains("\"" + Work.class.getName().replace('.', '/') + "\""));

            // An @Scheduled method publishes and returns; its listener runs as the next test starts.
            app.command("spring-publish-hold");
            cov = app.test("test-2", "listener-done");
            assertEquals(cov, "[" + SCHEDULED + "]", contaminated(cov));

            // END does not wait for a job's task still running at it: it is
            // named, and the END not cut.
            app.command("spring-publish-hold");
            cov = app.test("test-3");
            assertEquals(cov, "[" + SCHEDULED + "]", contaminated(cov));
            assertFalse(cov, cov.contains("windowCut"));
            String end = endLine(app, "test-3");
            assertFalse(end, end.contains(" requests="));
            app.command("listener-done");

            // A job's hand-off that runs and ends between two tests is in neither.
            app.command("quartz-publish");
            app.command("listener-done");
            cov = app.test("test-4");
            assertEquals(cov, "", contaminated(cov));
            assertTrue(app.log(), app.log().contains("Keploy dedup counts Spring's async tasks (" + POOL + "): a"
                    + " test's END waits for the tasks its requests hand off to finish, as for the requests, and "
                    + JOBS_ASYNC));
        }
    }

    @Test(timeout = 120000)
    public void aJobsTasksForAPriorityPoolGoAsTheyAre() throws Exception {
        priorityPool(null);
    }

    @Test(timeout = 120000)
    public void aJobsTasksForAPriorityPoolGoAsTheyAreWithTheEndWaitOff() throws Exception {
        priorityPool("0");
    }

    // priorityPool has a Quartz job, an @Scheduled method and the app's own
    // thread hand an app's priority pool its tasks, which its queue's
    // Comparator casts to the app's type: each goes as it is, so none
    // throws and the queue's order is kept, and the log says once that the
    // pool's tasks are not counted. Wrapped, the jobs' execute threw
    // ClassCastException.
    private void priorityPool(String endWait) throws Exception {
        try (App app = new App(false, endWait)) {
            app.command("priority-quartz");
            app.command("priority-spring");
            app.command("priority-app");
            String log = app.log();
            List<String> results = new ArrayList<>();
            for (String line : log.split("\n")) {
                if (line.startsWith("PRIORITY ")) {
                    results.add(line.trim());
                }
            }
            assertEquals(log, Arrays.asList("PRIORITY job ok", "PRIORITY job ok", "PRIORITY app ok"), results);
            String said = "Keploy dedup does not count the tasks handed to " + PriorityPool.class.getName()
                    + "$Pool: its pool's queue, a java.util.concurrent.PriorityBlockingQueue, may order its tasks by"
                    + " their own type, so each reaches it as it is";
            assertEquals(log, log.indexOf(said), log.lastIndexOf(said));
            assertTrue(log, log.contains(said));
        }
    }

    @Test(timeout = 120000)
    public void anAppWithNoSchedulerRunsAsBefore() throws Exception {
        try (App app = new App(true, null)) {
            String cov = app.test("test-1");
            assertEquals(cov, "", contaminated(cov));
            String log = app.log();
            assertFalse(log, log.contains("job runs"));
            assertFalse(log, log.contains("NoClassDefFoundError"));
        }
    }

    // endLine is the diagnostics line of a test's END, which the SDK logs
    // once its reply is sent: waited for.
    private static String endLine(App app, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        String log;
        do {
            log = app.log();
            for (String line : log.split("\n")) {
                if (line.contains("END test-set-0/" + id + " took ")) {
                    return line;
                }
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("no diagnostics line of " + id + "'s END: " + log);
    }

    // contaminated is the contaminated list of a record, "" when it has none.
    private static String contaminated(String cov) {
        int at = cov.indexOf("\"contaminated\":");
        return at < 0 ? "" : cov.substring(at + "\"contaminated\":".length(), cov.indexOf(']', at) + 1);
    }

    // App is JobApp in a JVM of its own, and the two channels this drives
    // it on: Keploy's, which the SDK connects to, and JobApp's commands.
    private final class App implements AutoCloseable {

        private final Process process;
        private final File log;
        private final ServerSocket collector;
        private final Socket sdk;
        private final OutputStream sdkOut;
        private final BufferedReader sdkIn;
        private final Socket commands;
        private final OutputStream commandOut;
        private final BufferedReader commandIn;

        // App runs JobApp, with neither scheduler on its class path when
        // plain, and KEPLOY_JAVA_END_WAIT_MS set to endWait unless null.
        App(boolean plain, String endWait) throws Exception {
            File classes = tmp.newFolder();
            for (Class<?> type : new Class<?>[] {JobApp.class, Work.class, ReportJob.class, DigestJobs.class,
                OnDemandScheduler.class, SpringJobs.class, Class.forName("org.example.jobs.Schedulers"),
                Class.forName("org.example.jobs.Events"), Class.forName("org.example.jobs.Events$Ready"),
                Class.forName("org.example.jobs.Events$Listener")}) {
                String key = type.getName().replace('.', '/') + ".class";
                File copy = new File(classes, key);
                copy.getParentFile().mkdirs();
                try (InputStream input = type.getClassLoader().getResourceAsStream(key)) {
                    Files.copy(input, copy.toPath());
                }
            }
            collector = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            collector.setSoTimeout(60000);
            List<String> command = new ArrayList<>();
            command.add(new File(System.getProperty("java.home"), "bin/java").getPath());
            command.add("-Djava.io.tmpdir=" + tmp.newFolder().getPath());
            command.add("-javaagent:" + sdkJar().getPath());
            List<String> classPath = new ArrayList<>();
            for (String path : System.getProperty("java.class.path").split(File.pathSeparator)) {
                String jar = new File(path).getName();
                if (plain && (jar.startsWith("quartz-") || jar.startsWith("spring-"))) {
                    continue;
                }
                // Absolute: the app runs in a dir of its own.
                classPath.add(new File(path).getAbsolutePath());
            }
            command.add("-cp");
            command.add(String.join(File.pathSeparator, classPath));
            command.add(JobApp.class.getName());
            if (plain) {
                command.add("plain");
            }
            log = tmp.newFile();
            ProcessBuilder builder = new ProcessBuilder(command).directory(tmp.newFolder())
                    .redirectErrorStream(true).redirectOutput(log);
            Map<String, String> env = builder.environment();
            env.keySet().removeIf(name -> name.startsWith("KEPLOY_") || name.equals("JAVA_TOOL_OPTIONS")
                    || name.equals("JDK_JAVA_OPTIONS") || name.equals("_JAVA_OPTIONS"));
            env.put("KEPLOY_JAVA_CLASS_DIRS", classes.getPath());
            env.put("KEPLOY_COVERAGE_ENDPOINT", "127.0.0.1:" + collector.getLocalPort());
            env.put("KEPLOY_JAVA_DEDUP_WARMUP_DISABLED", "true");
            env.put("KEPLOY_JAVA_DEDUP_DIAGNOSTICS", "true");
            if (endWait != null) {
                env.put("KEPLOY_JAVA_END_WAIT_MS", endWait);
            }
            process = builder.start();
            try {
                int port = port();
                sdk = collector.accept();
                sdk.setSoTimeout(60000);
                sdkOut = sdk.getOutputStream();
                sdkIn = new BufferedReader(new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));
                String hello = sdkIn.readLine();
                assertTrue("expected HELLO frame on connect, got " + hello,
                        hello != null && hello.startsWith("HELLO "));
                commands = new Socket(InetAddress.getLoopbackAddress(), port);
                commands.setSoTimeout(60000);
                commandOut = commands.getOutputStream();
                commandIn = new BufferedReader(
                        new InputStreamReader(commands.getInputStream(), StandardCharsets.UTF_8));
            } catch (Exception | AssertionError e) {
                close();
                throw e;
            }
        }

        // test runs one test that fires these jobs between its START and its
        // END, and returns its record.
        String test(String id, String... jobs) throws IOException {
            String fullId = "test-set-0/" + id;
            send(sdkOut, "START " + fullId);
            assertEquals("ACK", nextNonHello());
            for (String job : jobs) {
                command(job);
            }
            send(sdkOut, "END " + fullId);
            String cov = nextNonHello();
            assertTrue(cov, cov != null && cov.startsWith("COV "));
            String line;
            while (!(line = nextNonHello()).equals("ACK")) {
                assertTrue(line, line.startsWith("CLASSES ") || line.startsWith("MANIFEST "));
            }
            cov = cov.substring("COV ".length());
            assertTrue(cov, cov.contains("\"id\":\"" + fullId + "\""));
            return cov;
        }

        private String nextNonHello() throws IOException {
            String line;
            while ((line = sdkIn.readLine()) != null && line.startsWith("HELLO ")) {
                // Skip asynchronous HELLO capability refresh frames.
            }
            return line;
        }

        // command has JobApp fire a job, and returns once its run is over.
        void command(String job) throws IOException {
            send(commandOut, job);
            assertEquals(job + ": " + log(), "DONE", commandIn.readLine());
        }

        String log() throws IOException {
            return new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
        }

        // port is the port JobApp prints once it takes commands.
        private int port() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (System.nanoTime() < deadline) {
                for (String line : Files.readAllLines(log.toPath(), StandardCharsets.UTF_8)) {
                    if (line.startsWith("PORT ")) {
                        return Integer.parseInt(line.substring("PORT ".length()).trim());
                    }
                }
                assertTrue(log(), process.isAlive());
                Thread.sleep(50);
            }
            throw new AssertionError(log());
        }

        @Override
        public void close() throws Exception {
            closeQuietly(commands);
            closeQuietly(sdk);
            closeQuietly(collector);
            process.getOutputStream().close();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            // Closing on the way out.
        }
    }

    private static void send(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // sdkJar is a jar whose Premain-Class is the SDK's, which is on the
    // class path it is run with, and that may retransform, as keploy-sdk.jar
    // may.
    private File sdkJar() throws IOException {
        File jar = tmp.newFile("keploy-sdk-" + System.nanoTime() + ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", KeployDedupAgent.class.getName());
        manifest.getMainAttributes().putValue("Can-Retransform-Classes", "true");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar), manifest)) {
            out.putNextEntry(new ZipEntry("META-INF/keploy-test"));
            out.closeEntry();
        }
        return jar;
    }
}
