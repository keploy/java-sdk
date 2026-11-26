package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.example.jobs.PriorityPool;
import org.example.servers.AsyncTail;
import org.example.servers.HelloServlet;
import org.example.servers.Retransformer;
import org.example.servers.ServerApp;
import org.example.servers.Tail;
import org.example.servers.TailFilter;
import org.jacoco.agent.AgentJar;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A request's code that runs after its response is sent (a filter's code
 * after the chain) is the test's: its END waits for the request to finish,
 * on Tomcat, Jetty and Undertow, each in a JVM of its own with keploy-sdk.jar
 * as its -javaagent. With the wait off, END misses that code (the next
 * START clears it as hits between tests); past the wait, the record says
 * windowCut.
 */
public class ServerTailTest {

    private static final String TAIL = Tail.class.getName().replace('.', '/');
    private static final String ASYNC_TAIL = AsyncTail.class.getName().replace('.', '/');
    private static final String ADAPTER = "org.apache.catalina.connector.CoyoteAdapter";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void tomcatsEndWaitsForTheFiltersCodeAfterTheResponse() throws Exception {
        Result result = run("tomcat", "2000", 500);
        assertTrue(result.log, endLine(result.log).contains(" requests="));
        assertTrue(result.cov, result.cov.contains("\"" + TAIL + "\""));
        assertFalse(result.cov, result.cov.contains("windowCut"));
        assertTrue(result.log, result.log.contains("Keploy dedup counts Tomcat's requests"
                + " (org.apache.catalina.connector.CoyoteAdapter)"));
        assertFalse(result.log, result.log.contains("does not wait"));
    }

    @Test
    public void jettysEndWaitsForTheFiltersCodeAfterTheResponse() throws Exception {
        Result result = run("jetty", "2000", 500);
        assertTrue(result.log, endLine(result.log).contains(" requests="));
        assertTrue(result.cov, result.cov.contains("\"" + TAIL + "\""));
        assertFalse(result.cov, result.cov.contains("windowCut"));
        assertTrue(result.log, result.log.contains("Keploy dedup counts Jetty's requests"
                + " (org.eclipse.jetty.server.HttpChannel)"));
    }

    @Test
    public void undertowsEndWaitsForTheFiltersCodeAfterTheResponse() throws Exception {
        Result result = run("undertow", "2000", 500);
        assertTrue(result.log, endLine(result.log).contains(" requests="));
        assertTrue(result.cov, result.cov.contains("\"" + TAIL + "\""));
        assertFalse(result.cov, result.cov.contains("windowCut"));
        assertTrue(result.log, result.log.contains("Keploy dedup counts Undertow's requests"
                + " (io.undertow.server.Connectors)"));
    }

    @Test
    public void tomcatInANamedModuleCountsItsRequests() throws Exception {
        // Tomcat's jars on the module path: CoyoteAdapter is in the named
        // module org.apache.tomcat.embed.core, which reads no class path
        // module: its changed code reaches RequestGate because the JVM adds
        // that read when an agent changes a class of a named module.
        Assume.assumeFalse("modules are Java 9's", System.getProperty("java.specification.version")
                .startsWith("1."));
        Fork fork = new Fork("tomcat", "2000", 500);
        fork.modules = true;
        Result result = run(fork);
        assertTrue(result.log, result.log.contains("MODULE module org.apache.tomcat.embed.core"));
        assertTrue(result.cov, result.cov.contains("\"" + TAIL + "\""));
        assertFalse(result.cov, result.cov.contains("windowCut"));
        assertTrue(result.log, result.log.contains("Keploy dedup counts Tomcat's requests"
                + " (org.apache.catalina.connector.CoyoteAdapter)"));
        assertFalse(result.log, result.log.contains("IllegalAccessError"));
        assertTrue(result.log, endLine(result.log).contains(" requests="));
    }

    @Test
    public void jacocoMeasuresTheServersOwnClassAsTheIndexHasIt() throws Exception {
        // The index (KEPLOY_JAVA_CLASS_DIRS) holds Tomcat's jar, so the
        // embedded JaCoCo instruments CoyoteAdapter too: it must see the
        // class's own bytes, the ones the index ids, not the counted ones,
        // or every test's record names it in classMismatch.
        Fork fork = new Fork("tomcat", "2000", 500);
        fork.indexTomcat = true;
        Result result = run(fork);
        assertTrue(result.cov, result.cov.contains("\"org/apache/catalina/connector/CoyoteAdapter\":["));
        assertTrue(result.cov, result.cov.contains("\"" + TAIL + "\""));
        // The class path has Servlet's API twice (Tomcat's jar and
        // javax.servlet-api), so those classes differ from the index's copy.
        assertFalse(result.cov, mismatched(result.cov).contains("org/apache/catalina/connector/CoyoteAdapter"));
        assertTrue(result.log, endLine(result.log).contains(" requests="));
    }

    @Test
    public void withTheWaitOffTheEndMissesTheFiltersCode() throws Exception {
        // As before 2.2.0: the record has the servlet, not the filter's tail.
        Fork fork = new Fork("tomcat", "0", 1500);
        fork.queries.add("tail=1500");
        // Nor does work handed to Spring: no request counts, so the executors' change counts only a job's
        // hand-offs.
        fork.queries.add("tail=1500&async=event&sleep=10");
        Result result = run(fork);
        for (String cov : result.covs) {
            assertTrue(cov, cov.contains("\"" + HelloServlet.class.getName().replace('.', '/') + "\""));
            assertFalse(cov, cov.contains("\"" + TAIL + "\""));
            assertFalse(cov, cov.contains("windowCut"));
        }
        assertFalse(result.log, result.log.contains("Keploy dedup counts Tomcat"));
        assertTrue(result.log, result.log.contains("Keploy dedup counts Spring's async tasks"
                + " (org.springframework.core.task.SimpleAsyncTaskExecutor): a task a scheduled job's run hands off"
                + " counts as that run"));
        assertFalse(result.log, endLine(result.log).contains(" requests="));
        String why = "a test's END does not wait for its requests to finish: KEPLOY_JAVA_END_WAIT_MS is 0";
        assertTrue(result.log, result.log.contains(why));
        assertEquals("said once", result.log.indexOf(why), result.log.lastIndexOf(why));
    }

    @Test
    public void jacocoAttachedBeforeTheSdkMeasuresTheServersOwnClass() throws Exception {
        assertJacocoMeasuresTheServersOwnClass(jacocoAttached("first", true));
    }

    @Test
    public void jacocoAttachedAfterTheSdkMeasuresTheServersOwnClass() throws Exception {
        // The SDK's transformer can retransform, so the JVM runs it after
        // JaCoCo's, which cannot, whatever the -javaagent order: JaCoCo ids
        // the server's own class, as the index does.
        assertJacocoMeasuresTheServersOwnClass(jacocoAttached("last", true));
    }

    @Test
    public void theSdkInJavaToolOptionsWithJacocoOnTheCommandLine() throws Exception {
        // JAVA_TOOL_OPTIONS' agents start before the command line's.
        assertJacocoMeasuresTheServersOwnClass(jacocoAttached("tool", true));
    }

    @Test
    public void aJarThatCannotRetransformStillCountsTheRequests() throws Exception {
        // A jar that carries the SDK without Can-Retransform-Classes: the
        // requests count, the SDK's transformer runs in the order it was
        // added, and the WARNING says what that costs.
        Result result = jacocoAttached("last", false);
        assertTrue(result.log, endLine(result.log).contains(" requests="));
        assertTrue(result.cov, result.cov.contains("\"" + TAIL + "\""));
        assertTrue(result.cov, mismatched(result.cov).contains("org/apache/catalina/connector/CoyoteAdapter"));
        assertTrue(result.log, result.log.contains("WARNING: Keploy dedup: the jar keploy-sdk.jar runs from has no"
                + " Can-Retransform-Classes: true in its manifest, or this JVM cannot retransform, so a JaCoCo agent"
                + " attached after it instruments the server, scheduler and Spring executor classes it changes"));
    }

    @Test
    public void aJarThatCannotRetransformWithTheSdksOwnJacocoSaysNothing() throws Exception {
        // The SDK's own JaCoCo is added before its transformer, so it ids
        // the server's own class in any case: nothing to warn of.
        Fork fork = new Fork("tomcat", "2000", 500);
        fork.indexTomcat = true;
        fork.retransformable = false;
        Result result = run(fork);
        assertTrue(result.log, endLine(result.log).contains(" requests="));
        assertTrue(result.cov, result.cov.contains("\"org/apache/catalina/connector/CoyoteAdapter\":["));
        assertFalse(result.cov, mismatched(result.cov).contains("org/apache/catalina/connector/CoyoteAdapter"));
        assertFalse(result.log, result.log.contains("Can-Retransform-Classes"));
    }

    @Test
    public void anotherAgentsRetransformKeepsTheServerCounted() throws Exception {
        Fork fork = new Fork("tomcat", "2000", 500);
        fork.retransformer = "";
        Result result = run(fork);
        assertTrue(result.log, result.log.contains("RETRANSFORMED " + ADAPTER + " seen=1"));
        assertTrue(result.cov, result.cov.contains("\"" + TAIL + "\""));
        assertTrue(result.log, endLine(result.log).contains(" requests="));
    }

    @Test
    public void aRedefineWithTheClassAsTheSdkChangedItKeepsTheServerCounted() throws Exception {
        // A hot-swap tool redefines CoyoteAdapter with its dump of the class
        // as the SDK changed it: changed again, it would declare the renamed
        // method twice, and the JVM would refuse it.
        Fork fork = new Fork("tomcat", "2000", 500);
        fork.retransformer = "redefine";
        Result result = run(fork);
        assertTrue(result.log, result.log.contains("REDEFINED " + ADAPTER));
        assertTrue(result.cov, result.cov.contains("\"" + TAIL + "\""));
        assertTrue(result.log, endLine(result.log).contains(" requests="));
    }

    @Test
    public void aServerLoadedBeforeTheSdkIsLeftAsItIsWhenRetransformed() throws Exception {
        // The JVM refuses a method added to a loaded class: had the SDK
        // changed it on another agent's retransform, that would fail.
        Fork fork = new Fork("tomcat", "2000", 500);
        fork.retransformer = "load";
        Result result = run(fork);
        assertTrue(result.log, result.log.contains("RETRANSFORMED " + ADAPTER + " seen=1"));
        assertFalse(result.log, endLine(result.log).contains(" requests="));
        assertTrue(result.log, result.log.contains("no Tomcat, Jetty or Undertow request entry loaded after"
                + " keploy-sdk.jar started"));
    }

    // jacocoAttached runs a test on Tomcat with JaCoCo's own agent attached
    // in that order (first or last on the command line, or last with the SDK
    // in JAVA_TOOL_OPTIONS), measuring the app and CoyoteAdapter, which the
    // index holds too.
    private Result jacocoAttached(String order, boolean retransformable) throws Exception {
        Fork fork = new Fork("tomcat", "2000", 500);
        fork.indexTomcat = true;
        fork.jacoco = order;
        fork.retransformable = retransformable;
        return run(fork);
    }

    private static void assertJacocoMeasuresTheServersOwnClass(Result result) {
        assertTrue(result.log, result.log.contains("this JVM has a JaCoCo agent attached"));
        assertTrue(result.cov, result.cov.contains("\"org/apache/catalina/connector/CoyoteAdapter\":["));
        assertTrue(result.cov, result.cov.contains("\"" + TAIL + "\""));
        assertFalse(result.cov, mismatched(result.cov).contains("org/apache/catalina/connector/CoyoteAdapter"));
        assertTrue(result.log, endLine(result.log).contains(" requests="));
        assertFalse(result.log, result.log.contains("Can-Retransform-Classes"));
    }

    @Test
    public void aTailPastTheWaitCutsTheEnd() throws Exception {
        Result result = run("tomcat", "100", 5000);
        assertFalse(result.cov, result.cov.contains("\"" + TAIL + "\""));
        assertTrue(result.cov, result.cov.endsWith(",\"windowCut\":true}"));
        assertTrue(result.log, result.log.contains("test-set-0/test-1's requests still ran 100 ms after its END"));
    }

    @Test
    public void theWorkARequestHandsToSpringLandsInItsTestAndAPollersDoesNot() throws Exception {
        // Each runs AsyncTail 500 ms after the request handed it off, well
        // after the response: an event to an async multicaster (Fineract's
        // SimpleAsyncTaskExecutor), a task given to a ThreadPoolTaskExecutor
        // (submit, execute, submitListenable) or to a SimpleAsyncTaskExecutor
        // with a concurrency limit, and an @Async call on a plain pool that
        // returns nothing or a CompletableFuture. A scheduler's poller
        // publishes the event while the test's window is open (the request
        // waits for it): END does not wait for it.
        assertSpringHandOffs(new Fork("tomcat", "2000", 0), "scheduled");
    }

    @Test
    public void springSixCountsTheSameHandOffs() throws Exception {
        // The same on Spring 6.2 (Spring Boot 3.4's), which needs Java 17:
        // its throttled SimpleAsyncTaskExecutor wraps the task in a
        // TaskTrackingRunnable, @Async's CompletableFuture goes through
        // the executor's submitCompletable, and the poller of 6.1's
        // SimpleAsyncTaskScheduler, a SimpleAsyncTaskExecutor itself, is
        // started in the changed doExecute by its trigger thread.
        String spec = System.getProperty("java.specification.version");
        Assume.assumeTrue("Spring 6 needs Java 17", !spec.startsWith("1.") && Integer.parseInt(spec) >= 17);
        File target = new File(ServerTailTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .getParentFile();
        Fork fork = new Fork("tomcat", "2000", 0);
        fork.springJars = new File(target, "spring6");
        File[] jars = fork.springJars.listFiles((dir, name) -> name.endsWith(".jar"));
        assertTrue("no Spring 6 jars in " + fork.springJars + " (the build's spring6-rows profile copies them)",
                jars != null && jars.length == 8);
        assertSpringHandOffs(fork, "scheduled", "simplescheduled");
    }

    // assertSpringHandOffs runs a test for each way AsyncWork hands work to
    // Spring, then one for each poller named: each hand-off's work lands in
    // its test, and no poller's is waited for.
    private void assertSpringHandOffs(Fork fork, String... pollers) throws Exception {
        String[] counted = {"event", "pool", "execute", "listenable", "async", "completable", "throttled"};
        for (String how : counted) {
            fork.queries.add("async=" + how + "&sleep=500");
        }
        for (String poller : pollers) {
            fork.queries.add("async=" + poller + "&sleep=4000");
        }
        Result result = run(fork);
        assertEquals(result.log, fork.queries.size(), result.covs.size());
        for (int test = 0; test < counted.length; test++) {
            String cov = result.covs.get(test);
            assertTrue(fork.queries.get(test) + ": " + cov, cov.contains("\"" + ASYNC_TAIL + "\""));
            assertFalse(fork.queries.get(test) + ": " + cov, cov.contains("windowCut"));
        }
        for (int test = counted.length; test < fork.queries.size(); test++) {
            String polled = result.covs.get(test);
            assertFalse(fork.queries.get(test) + ": " + polled, polled.contains("\"" + ASYNC_TAIL + "\""));
            assertFalse("END did not wait for the poller's event: " + polled, polled.contains("windowCut"));
        }
        for (String broken : new String[] {"VerifyError", "LinkageError", "NoSuchMethodError", "NoClassDefFoundError",
            "Exception in thread"}) {
            assertFalse(result.log, result.log.contains(broken));
        }
        for (String executor : new String[] {"org.springframework.core.task.SimpleAsyncTaskExecutor",
            "org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor",
            "org.springframework.aop.interceptor.AsyncExecutionAspectSupport"}) {
            assertTrue(result.log, result.log.contains("Keploy dedup counts Spring's async tasks (" + executor
                    + "): a test's END waits for the tasks its requests hand off to finish"));
        }
    }

    @Test
    public void workARequestHandsToSpringPastTheWaitCutsTheEnd() throws Exception {
        Fork fork = new Fork("tomcat", "100", 0);
        fork.queries.add("async=event&sleep=4000");
        Result result = run(fork);
        assertFalse(result.cov, result.cov.contains("\"" + ASYNC_TAIL + "\""));
        assertTrue(result.cov, result.cov.endsWith(",\"windowCut\":true}"));
        assertTrue(result.log, result.log.contains("test-set-0/test-1's requests still ran 100 ms after its END"));
    }

    @Test
    public void aRequestsTasksForAPriorityPoolGoAsTheyAre() throws Exception {
        // Two requests hand an app's priority pool its tasks, which its
        // queue's Comparator casts to the app's type: they go as they are,
        // so execute does not throw and the queue's order is kept (each
        // request answers "hello"), and the log says once that the pool's
        // tasks are not counted. Wrapped, execute threw ClassCastException.
        Fork fork = new Fork("tomcat", "2000", 0);
        fork.queries.add("async=priority&sleep=0");
        fork.queries.add("async=priority&sleep=0");
        Result result = run(fork);
        assertEquals(result.log, 2, result.covs.size());
        String said = "Keploy dedup does not count the tasks handed to " + PriorityPool.class.getName()
                + "$Pool: its pool's queue, a java.util.concurrent.PriorityBlockingQueue, may order its tasks by"
                + " their own type, so each reaches it as it is";
        assertTrue(result.log, result.log.contains(said));
        assertEquals(result.log, result.log.indexOf(said), result.log.lastIndexOf(said));
        for (String cov : result.covs) {
            assertFalse(cov, cov.contains("windowCut"));
        }
    }

    @Test
    public void anAppWithoutSpringRunsAsBefore() throws Exception {
        Fork fork = new Fork("tomcat", "2000", 500);
        fork.noSpring = true;
        Result result = run(fork);
        assertTrue(result.cov, result.cov.contains("\"" + TAIL + "\""));
        assertFalse(result.cov, result.cov.contains("windowCut"));
        assertFalse(result.log, result.log.contains("Keploy dedup counts Spring"));
        assertFalse(result.log, result.log.contains("NoClassDefFoundError") || result.log.contains("ClassNotFound"));
    }

    private static final class Result {
        String cov;
        final List<String> covs = new ArrayList<>();
        String log;
    }

    // Fork is how ServerTailTest runs ServerApp: on server, with END's
    // wait at endWait, tests tests whose request's filter runs tailMillis
    // after the response; with modules, Tomcat's jars are on the module
    // path instead of the class path; with indexTomcat, the index (and so
    // the embedded JaCoCo) holds Tomcat's classes as well as the app's;
    // jacoco attaches JaCoCo's own agent (first, last, or last with the SDK
    // in JAVA_TOOL_OPTIONS); retransformer, when not null, attaches
    // Retransformer with it as its args ("load": before the SDK; "" and
    // "redefine": after it); without retransformable, the SDK's jar has no
    // Can-Retransform-Classes; queries, when not empty, are the query of
    // each test's request in turn (one test each) instead of ?tail=; with
    // noSpring, Spring's jars are not on the app's class path; with
    // springJars, the jars there are on it in place of the test's Spring.
    private static final class Fork {
        final String server;
        final String endWait;
        final long tailMillis;
        int tests = 1;
        boolean modules;
        boolean indexTomcat;
        String jacoco;
        String retransformer;
        boolean retransformable = true;
        final List<String> queries = new ArrayList<>();
        boolean noSpring;
        File springJars;

        Fork(String server, String endWait, long tailMillis) {
            this.server = server;
            this.endWait = endWait;
            this.tailMillis = tailMillis;
        }
    }

    private Result run(String server, String endWait, long tailMillis) throws Exception {
        return run(new Fork(server, endWait, tailMillis));
    }

    // run starts ServerApp as fork says in a JVM of its own and, as Keploy
    // over its TCP channel, runs its tests, with the SDK's diagnostics on.
    private Result run(Fork fork) throws Exception {
        String server = fork.server;
        long tailMillis = fork.tailMillis;
        int tests = fork.queries.isEmpty() ? fork.tests : fork.queries.size();
        boolean modules = fork.modules;
        File classes = tmp.newFolder();
        for (Class<?> app : new Class<?>[] {ServerApp.class, HelloServlet.class, TailFilter.class, Tail.class,
                AsyncTail.class}) {
            String key = app.getName().replace('.', '/') + ".class";
            File copy = new File(classes, key);
            copy.getParentFile().mkdirs();
            try (InputStream input = app.getClassLoader().getResourceAsStream(key)) {
                Files.copy(input, copy.toPath());
            }
        }
        Result result = new Result();
        try (ServerSocket collector = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            collector.setSoTimeout(60000);
            List<String> command = new ArrayList<>();
            command.add(new File(System.getProperty("java.home"), "bin/java").getPath());
            command.add("-Djava.io.tmpdir=" + tmp.newFolder().getPath());
            String sdkAgent = "-javaagent:" + sdkJar(fork.retransformable).getPath();
            String jacoco = fork.jacoco == null ? null : "-javaagent:" + jacocoJar().getPath()
                    + "=includes=org.example.*:" + ADAPTER + ",output=none";
            if ("load".equals(fork.retransformer)) {
                command.add("-javaagent:" + retransformerJar().getPath() + "=load");
            }
            if ("first".equals(fork.jacoco)) {
                command.add(jacoco);
            }
            if (!"tool".equals(fork.jacoco)) {
                command.add(sdkAgent);
            }
            if ("last".equals(fork.jacoco) || "tool".equals(fork.jacoco)) {
                command.add(jacoco);
            }
            if ("".equals(fork.retransformer)) {
                command.add("-javaagent:" + retransformerJar().getPath());
            }
            if ("redefine".equals(fork.retransformer)) {
                command.add("-javaagent:" + retransformerJar().getPath() + "=redefine");
            }
            List<String> classPath = new ArrayList<>();
            List<String> modulePath = new ArrayList<>();
            // Absolute: the app runs in a dir of its own.
            for (String path : System.getProperty("java.class.path").split(File.pathSeparator)) {
                String entry = new File(path).getAbsolutePath();
                String jar = new File(entry).getName();
                boolean tomcat = jar.startsWith("tomcat-embed-core-") || jar.startsWith("tomcat-annotations-api-");
                if ((fork.noSpring || fork.springJars != null) && jar.startsWith("spring-")) {
                    continue;
                }
                (modules && tomcat ? modulePath : classPath).add(entry);
            }
            if (fork.springJars != null) {
                for (File jar : fork.springJars.listFiles((dir, name) -> name.endsWith(".jar"))) {
                    classPath.add(jar.getAbsolutePath());
                }
            }
            if (modules) {
                assertEquals(modulePath.toString(), 2, modulePath.size());
                command.add("--module-path");
                command.add(String.join(File.pathSeparator, modulePath));
                command.add("--add-modules");
                command.add("org.apache.tomcat.embed.core");
            }
            command.add("-cp");
            command.add(String.join(File.pathSeparator, classPath));
            command.add(ServerApp.class.getName());
            command.add(server);
            File log = tmp.newFile();
            ProcessBuilder builder = new ProcessBuilder(command).directory(tmp.newFolder())
                    .redirectErrorStream(true).redirectOutput(log);
            Map<String, String> env = builder.environment();
            env.keySet().removeIf(name -> name.startsWith("KEPLOY_") || name.equals("JAVA_TOOL_OPTIONS")
                    || name.equals("JDK_JAVA_OPTIONS") || name.equals("_JAVA_OPTIONS"));
            String classDirs = classes.getPath();
            if (fork.indexTomcat) {
                for (String entry : classPath) {
                    if (new File(entry).getName().startsWith("tomcat-embed-core-")) {
                        classDirs += File.pathSeparator + entry;
                    }
                }
            }
            env.put("KEPLOY_JAVA_CLASS_DIRS", classDirs);
            env.put("KEPLOY_JAVA_DEDUP_DIAGNOSTICS", "true");
            env.put("KEPLOY_COVERAGE_ENDPOINT", "127.0.0.1:" + collector.getLocalPort());
            env.put("KEPLOY_JAVA_DEDUP_WARMUP_DISABLED", "true");
            env.put("KEPLOY_JAVA_END_WAIT_MS", fork.endWait);
            if ("tool".equals(fork.jacoco)) {
                env.put("JAVA_TOOL_OPTIONS", sdkAgent);
            }
            Process app = builder.start();
            try {
                int port = port(log, app);
                try (Socket sdk = collector.accept()) {
                    sdk.setSoTimeout(60000);
                    OutputStream out = sdk.getOutputStream();
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(sdk.getInputStream(), StandardCharsets.UTF_8));
                    String hello = in.readLine();
                    assertTrue("expected HELLO frame on connect, got " + hello,
                            hello != null && hello.startsWith("HELLO "));
                    for (int test = 1; test <= tests; test++) {
                        String fullId = "test-set-0/test-" + test;
                        send(out, "START " + fullId);
                        assertEquals("ACK " + fullId, in.readLine());
                        String query = fork.queries.isEmpty() ? "tail=" + tailMillis : fork.queries.get(test - 1);
                        assertEquals("hello", get(port, "/hello?" + query));
                        send(out, "END " + fullId);
                        String cov = in.readLine();
                        assertTrue(cov, cov != null && cov.startsWith("COV "));
                        result.covs.add(cov.substring("COV ".length()));
                        String line;
                        while (!(line = in.readLine()).equals("ACK " + fullId)) {
                            assertTrue(line, line.startsWith("CLASSES ") || line.startsWith("MANIFEST "));
                        }
                    }
                    result.cov = result.covs.get(0);
                }
            } finally {
                app.getOutputStream().close();
                if (!app.waitFor(30, TimeUnit.SECONDS)) {
                    app.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                }
                result.log = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
            }
        }
        return result;
    }

    // mismatched is the classMismatch list of a record, "" when it has none.
    private static String mismatched(String cov) {
        int at = cov.indexOf("\"classMismatch\":[");
        return at < 0 ? "" : cov.substring(at, cov.indexOf(']', at) + 1);
    }

    // endLine is the diagnostics line of test-1's END: it names a
    // requests step only when END waited for the test's requests.
    private static String endLine(String log) {
        for (String line : log.split("\n")) {
            if (line.contains("END test-set-0/test-1 took ")) {
                return line;
            }
        }
        throw new AssertionError("no diagnostics line of test-1's END: " + log);
    }

    // port is the port ServerApp prints once its server listens.
    private static int port(File log, Process app) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            for (String line : Files.readAllLines(log.toPath(), StandardCharsets.UTF_8)) {
                if (line.startsWith("PORT ")) {
                    return Integer.parseInt(line.substring("PORT ".length()).trim());
                }
            }
            assertTrue(new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8), app.isAlive());
            Thread.sleep(50);
        }
        throw new AssertionError(new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8));
    }

    // get returns the body of a GET of path, read to its Content-Length, as
    // Keploy reads a response: the request may still run on the server.
    private static String get(int port, String path) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(30000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            int length = -1;
            String header;
            while (!(header = readLine(in)).isEmpty()) {
                if (header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    length = Integer.parseInt(header.substring("content-length:".length()).trim());
                }
            }
            assertTrue("a Content-Length", length >= 0);
            byte[] body = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(body, read, length - read);
                assertTrue(n > 0);
                read += n;
            }
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0 && b != '\n') {
            if (b != '\r') {
                line.write(b);
            }
        }
        return new String(line.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static void send(OutputStream out, String command) throws IOException {
        out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // sdkJar is a jar whose Premain-Class is the SDK's, which is on the
    // classpath it is run with, and that may retransform, as keploy-sdk.jar
    // may, when retransformable.
    private File sdkJar(boolean retransformable) throws IOException {
        return agentJar("keploy-sdk", KeployDedupAgent.class, retransformable);
    }

    // retransformerJar is a jar whose Premain-Class is Retransformer, which
    // may retransform and redefine classes.
    private File retransformerJar() throws IOException {
        return agentJar("retransformer", Retransformer.class, true, true);
    }

    // jacocoJar is JaCoCo's own agent, jacocoagent.jar.
    private File jacocoJar() throws IOException {
        File jacoco = tmp.newFile("jacocoagent-" + System.nanoTime() + ".jar");
        AgentJar.extractTo(jacoco);
        return jacoco;
    }

    private File agentJar(String name, Class<?> premain, boolean retransformable) throws IOException {
        return agentJar(name, premain, retransformable, false);
    }

    private File agentJar(String name, Class<?> premain, boolean retransformable, boolean redefinable)
            throws IOException {
        File jar = tmp.newFile(name + "-" + System.nanoTime() + ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", premain.getName());
        if (retransformable) {
            manifest.getMainAttributes().putValue("Can-Retransform-Classes", "true");
        }
        if (redefinable) {
            manifest.getMainAttributes().putValue("Can-Redefine-Classes", "true");
        }
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar), manifest)) {
            out.putNextEntry(new ZipEntry("META-INF/keploy-test"));
            out.closeEntry();
        }
        return jar;
    }
}
