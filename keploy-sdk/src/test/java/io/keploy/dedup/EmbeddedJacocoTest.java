package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.example.embedded.EmbeddedApp;
import org.jacoco.agent.AgentJar;
import org.jacoco.core.runtime.WildcardMatcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class EmbeddedJacocoTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void includesTakeInEveryIndexedClassAndNoLibrary() {
        List<String> app = Arrays.asList(
                "org/apache/fineract/portfolio/loan/Loan",
                "org/apache/fineract/portfolio/loan/Loan$1",
                "org/apache/fineract/infrastructure/core/boot/Main",
                "org/acme/A",
                "org/acme/web/B",
                "org/acme/web/deep/C",
                "com/acme/gen/api/Model",
                "acme/Bar",
                "Main",
                "Main$Inner");
        String includes = EmbeddedJacoco.includes(true, app);

        assertEquals("Main:Main$Inner:acme.Bar:com.acme.gen.*:org.acme.*:org.apache.fineract.*", includes);
        WildcardMatcher matcher = new WildcardMatcher(includes);
        for (String name : app) {
            assertTrue(name, matcher.matches(name.replace('/', '.')));
        }
        // Libraries, and the app's neighbours in name, stay out.
        for (String library : new String[] {"org.springframework.web.Servlet", "org.apache.commons.Lang",
                "com.acme.other.X", "com.acmex.gen.Y", "acme.Baz", "acme.sub.Q", "MainX", "org.acmex.Z",
                "com.google.gson.Gson"}) {
            assertFalse(library, matcher.matches(library));
        }
    }

    @Test
    public void includesAreNoneUnlessTheIndexIsTheAppsAlone() {
        // The whole jar or the whole classpath: JaCoCo instruments every
        // class, as it did before the SDK started it.
        assertEquals("", EmbeddedJacoco.includes(false, Collections.singletonList("org/acme/web/B")));
    }

    @Test
    public void anEmptyIndexStartsNoJacoco() {
        // No class passes the index's cut, so JaCoCo would only slow the app.
        assertNull(EmbeddedJacoco.includes(true, Collections.<String>emptyList()));
        assertNull(EmbeddedJacoco.includes(false, Collections.<String>emptyList()));
    }

    @Test
    public void optionsCarryIncludesOnlyWhenThereAreAny() {
        assertEquals("output=none", EmbeddedJacoco.options(""));
        assertEquals("output=none,includes=org.acme.*:Main", EmbeddedJacoco.options("org.acme.*:Main"));
    }

    @Test
    public void aJacocoAgentOnTheClasspathOrAmongTheArgumentsCountsAsAttached() throws Exception {
        File jacoco = tmp.newFile("jacocoagent.jar");
        AgentJar.extractTo(jacoco);
        File other = sdkJar();
        List<String> none = Collections.emptyList();
        try (URLClassLoader withJacoco = new URLClassLoader(new URL[] {jacoco.toURI().toURL()}, null);
             URLClassLoader without = new URLClassLoader(new URL[] {tmp.getRoot().toURI().toURL()}, null)) {
            // Attached before keploy-sdk.jar: on the classpath already.
            assertTrue(EmbeddedJacoco.attached(withJacoco, none));
            assertFalse(EmbeddedJacoco.attached(without, none));
            // Attached after it: only its argument names it.
            assertTrue(EmbeddedJacoco.attached(without, Arrays.asList("-Xmx64m",
                    "-javaagent:" + other.getPath(), "-javaagent:" + jacoco.getPath() + "=output=none")));
            assertTrue(EmbeddedJacoco.attached(without, Collections.singletonList("-javaagent:" + jacoco.getPath())));
            // Another agent, a jar named as an option, a jar not there.
            assertFalse(EmbeddedJacoco.attached(without, Arrays.asList("-javaagent:" + other.getPath(),
                    "-javaagent:" + other.getPath() + "=" + jacoco.getPath(), "-cp", jacoco.getPath(),
                    "-javaagent:" + new File(tmp.getRoot(), "gone.jar").getPath())));
        }
    }

    @Test
    public void theSdkAloneStartsJacocoOnTheAppsClassesOnly() throws Exception {
        Run run = new Run(SDK);
        run.env.put("EMBEDDED_APP_INDEX", "1");
        String output = runApp(run);
        // The INDEX report says what measures the app, and with what includes=.
        assertTrue(output, output.contains("\"source\":\"override\""));
        assertTrue(output, output.contains("\"classes\":1,\"includes\":\"org.example.embedded.*\",\"jacoco\":\"embedded\"}"));
        assertTrue(output, output.contains("agent=true"));
        assertTrue(output, output.contains("app=true"));
        // Loaded after JaCoCo started, outside its includes=.
        assertTrue(output, output.contains("library=false"));
        assertTrue(output, output.contains("on the app's 1 classes alone (includes=org.example.embedded.*)"));
    }

    @Test
    public void aJacocoAgentAttachedAfterTheSdkIsLeftToRunAlone() throws Exception {
        // The command attaches JaCoCo after keploy-sdk.jar, as the README
        // showed: its premain has not run when the SDK's does, and the SDK
        // must not start a second one, whose premain fails the JVM's start.
        assertLeftAlone(runApp(new Run(SDK, JACOCO)));
    }

    @Test
    public void theIndexReportNamesAJacocoAgentTheCommandAttached() throws Exception {
        Run run = new Run(SDK, JACOCO);
        run.env.put("EMBEDDED_APP_INDEX", "1");
        String output = runApp(run);
        assertTrue(output, output.contains("\"classes\":1,\"includes\":\"\",\"jacoco\":\"attached\"}"));
    }

    @Test
    public void aJacocoAgentAfterTheSdkInJavaToolOptionsIsLeftToRunAlone() throws Exception {
        Run run = new Run(SDK, JACOCO);
        run.inEnv = true;
        assertLeftAlone(runApp(run));
    }

    @Test
    public void aJacocoAgentAttachedBeforeTheSdkIsLeftToRunAlone() throws Exception {
        // k8s-proxy's order: JaCoCo, then keploy-sdk.jar, in
        // JAVA_TOOL_OPTIONS. It keeps working as before, with no index read
        // before the app's main.
        Run run = new Run(JACOCO, SDK);
        run.inEnv = true;
        assertLeftAlone(runApp(run));
    }

    @Test
    public void dedupTurnedOffStartsNoJacoco() throws Exception {
        Run run = new Run(SDK);
        run.env.put("KEPLOY_JAVA_DEDUP_DISABLED", "true");
        String output = runApp(run);
        assertTrue(output, output.contains("agent=false"));
        assertTrue(output, output.contains("app=false"));
        assertFalse(output, output.contains("JaCoCo"));
    }

    @Test
    public void aJacocoThatCannotStartLeavesTheAppRunningUnmeasured() throws Exception {
        // The temp dir is a file, so the jar JaCoCo runs from cannot be
        // written: the app still starts, and the log says why it is not
        // measured.
        Run run = new Run(SDK);
        run.tmpdir = tmp.newFile("not-a-dir");
        run.env.put("EMBEDDED_APP_INDEX", "1");
        String output = runApp(run);
        assertTrue(output, output.contains("agent=false"));
        assertTrue(output, output.contains("app=false"));
        assertTrue(output, output.contains("could not start the JaCoCo agent keploy-sdk.jar carries"));
        assertFalse(output, output.contains("started JaCoCo"));
        // The INDEX report says nothing measures the app.
        assertTrue(output, output.contains("\"includes\":\"\",\"jacoco\":\"none\"}"));
    }

    private static void assertLeftAlone(String output) {
        assertTrue(output, output.contains("agent=true"));
        assertTrue(output, output.contains("app=true"));
        // The command's JaCoCo has no includes=, so it takes in libraries.
        assertTrue(output, output.contains("library=true"));
        assertTrue(output, output.contains("this JVM has a JaCoCo agent attached"));
        assertFalse(output, output.contains("started JaCoCo"));
        assertFalse(output, output.contains("Error while instrumenting"));
        // EmbeddedApp sends no START, so only a premain index read says so.
        assertFalse(output, output.contains("fingerprinting the app's"));
    }

    // sdkJar is a jar whose Premain-Class is the SDK's, which is on the
    // classpath it is run with.
    private File sdkJar() throws IOException {
        File sdk = tmp.newFile("keploy-sdk-" + System.nanoTime() + ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", KeployDedupAgent.class.getName());
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(sdk), manifest)) {
            out.putNextEntry(new ZipEntry("META-INF/keploy-test"));
            out.closeEntry();
        }
        return sdk;
    }

    private static final String SDK = "sdk";
    private static final String JACOCO = "jacoco";

    // Run is how runApp starts EmbeddedApp: the agents in order, on the
    // command or in JAVA_TOOL_OPTIONS when inEnv, extra environment, and the
    // JVM's java.io.tmpdir (a new dir when null).
    private static final class Run {
        final List<String> agents;
        boolean inEnv;
        final Map<String, String> env = new HashMap<>();
        File tmpdir;

        Run(String... agents) {
            this.agents = Arrays.asList(agents);
        }
    }

    // runApp runs EmbeddedApp in its own JVM, as run says, from a dir of its
    // own and with none of this JVM's agent or Keploy settings, and returns
    // what it printed.
    private String runApp(Run run) throws Exception {
        String key = EmbeddedApp.class.getName().replace('.', '/') + ".class";
        File classes = tmp.newFolder();
        File copy = new File(classes, key);
        assertTrue(copy.getParentFile().mkdirs());
        try (InputStream input = EmbeddedApp.class.getClassLoader().getResourceAsStream(key)) {
            Files.copy(input, copy.toPath());
        }
        List<String> agents = new ArrayList<>();
        for (String agent : run.agents) {
            if (agent.equals(SDK)) {
                agents.add("-javaagent:" + sdkJar().getPath());
            } else {
                File jacoco = tmp.newFile("jacocoagent-" + System.nanoTime() + ".jar");
                AgentJar.extractTo(jacoco);
                agents.add("-javaagent:" + jacoco.getPath() + "=output=none");
            }
        }
        File tmpdir = run.tmpdir != null ? run.tmpdir : tmp.newFolder();
        List<String> command = new ArrayList<>();
        command.add(new File(System.getProperty("java.home"), "bin/java").getPath());
        command.add("-Djava.io.tmpdir=" + tmpdir.getPath());
        if (!run.inEnv) {
            command.addAll(agents);
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(EmbeddedApp.class.getName());
        File log = tmp.newFile();
        ProcessBuilder builder = new ProcessBuilder(command).directory(tmp.newFolder())
                .redirectErrorStream(true).redirectOutput(log);
        Map<String, String> env = builder.environment();
        env.keySet().removeIf(name -> name.startsWith("KEPLOY_") || name.equals("JAVA_TOOL_OPTIONS")
                || name.equals("JDK_JAVA_OPTIONS") || name.equals("_JAVA_OPTIONS"));
        if (run.inEnv) {
            env.put("JAVA_TOOL_OPTIONS", String.join(" ", agents));
        }
        env.put("KEPLOY_JAVA_CLASS_DIRS", classes.getPath());
        // TCP: no socket file in /tmp. Nothing listens there; the SDK dials
        // in the background, and the app runs on.
        env.put("KEPLOY_COVERAGE_ENDPOINT", "127.0.0.1:1");
        env.putAll(run.env);
        Process process = builder.start();
        boolean exited = process.waitFor(60, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
        String output = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
        assertTrue(output, exited);
        assertEquals(output, 0, process.exitValue());
        return output;
    }
}
