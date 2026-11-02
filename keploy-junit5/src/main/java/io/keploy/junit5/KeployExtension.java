package io.keploy.junit5;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.TestAbortedException;

/**
 * keploy-junit5 joins a JUnit 5 (Jupiter) suite to keploy's Universal Mocker.
 *
 * <p>keploy records the calls each test's code makes (keploy mock record) and
 * replays them (keploy mock replay), so the suite runs with every dependency
 * answered from the recording. keploy judges every recorded test and lists
 * the ones it cannot replay, with a reason. This extension is how a test tells
 * keploy where it begins and ends, and how keploy skips a test it set aside
 * or fails a test whose calls changed. It speaks the same scope protocol (v1)
 * as go-sdk's keploytest, @keploy/playwright, @keploy/jest and keploy-pytest;
 * it decides nothing itself.
 *
 * <p>JUnit loads it for the whole run (junit.jupiter.extensions.autodetection.enabled=true),
 * so it wraps every test, outside every other extension. Without keploy
 * (KEPLOY_MOCK_AGENT unset or "off") it does nothing. Under keploy, with one
 * test JVM (keploy's first release runs one test process at a time, and
 * refuses a second):
 * <ul>
 * <li>the JVM claims keploy's run before the first test class's
 * {@code @BeforeAll} (POST /agent/instance), with keploy's project folder
 * (KEPLOY_MOCK_PROJECT); the current folder only when keploy names none;</li>
 * <li>each test begins (POST /agent/scope/begin) once its class's
 * {@code @BeforeAll} methods ran, before any other extension's BeforeEach
 * callback and before its {@code @BeforeEach} methods; a test keploy set
 * aside is aborted (JUnit's skip) with keploy's reason, and none of its
 * {@code @BeforeEach} or {@code @AfterEach} methods runs;</li>
 * <li>the test ends (POST /agent/scope/end) after its {@code @AfterEach}
 * methods and every other extension's AfterEach callback, before its class's
 * {@code @AfterAll}, with its outcome; when keploy saw a call with no
 * recorded answer, or a recorded call the test no longer made, the test fails
 * with keploy's text.</li>
 * </ul>
 * The calls a {@code @BeforeAll} or {@code @AfterAll} method makes are the
 * test process's, outside every test, as JUnit scopes them. Any answer of the
 * agent this extension cannot trust fails the test it belongs to; a claim that
 * fails fails every test class. A test begun a second time in one JVM (a
 * rerun) fails with the reason; none runs unjudged.
 */
public final class KeployExtension implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback {
    static final String AUTODETECT = "junit.jupiter.extensions.autodetection.enabled";
    static final String PARALLEL = "junit.jupiter.execution.parallel.enabled";

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(KeployExtension.class);
    private static final String OPEN = "open";

    // The JVM's run: keploy's claim is one per process.
    private static Run run;

    static synchronized Run run() {
        if (run == null) {
            run = new Run(Joining.decide(System.getenv(), Joining.pid(), Joining::runsInsideProc));
        }
        return run;
    }

    /** Sets the JVM's run (the rows' fake agent). */
    static synchronized void use(Run r) {
        run = r;
    }

    @Override
    public void beforeAll(ExtensionContext ctx) {
        run().ready(ctx);
    }

    @Override
    public void beforeEach(ExtensionContext ctx) {
        Run r = run();
        if (!r.ready(ctx)) {
            return;
        }
        TestRef t = TestRef.of(ctx.getRequiredTestClass().getName(), ctx.getUniqueId());
        String skip = r.begin(t);
        if (skip != null) {
            // JUnit's own skip: no @BeforeEach, test or @AfterEach method
            // runs, and the reason is JUnit's abort reason. No end follows.
            System.err.println("keploy-junit5: skipped " + t.id() + " (" + skip + ")");
            throw new TestAbortedException(skip);
        }
        ctx.getStore(NS).put(OPEN, t);
    }

    @Override
    public void afterEach(ExtensionContext ctx) {
        TestRef t = ctx.getStore(NS).remove(OPEN, TestRef.class);
        if (t == null) {
            return; // not begun: inert, set aside, or its begin failed it
        }
        String failure = run().end(t, outcome(ctx.getExecutionException().orElse(null)));
        if (failure != null) {
            throw new KeployException(failure);
        }
    }

    /**
     * A test's outcome as keploy's end takes it, from what it threw through
     * its {@code @AfterEach} methods: an abort (a failed assumption) is
     * skipped.
     */
    static String outcome(Throwable thrown) {
        if (thrown == null) {
            return "passed";
        }
        return thrown instanceof TestAbortedException ? "skipped" : "failed";
    }

    /**
     * keploy's reason as the test shows it: once prefixed with "keploy: " (a
     * judging reason from the agent already is; a skip reason,
     * "&lt;class&gt;/&lt;reason&gt;: &lt;detail&gt;", is not), or fallback when
     * the agent gave none, as go-sdk's keploytest shows it.
     */
    static String keployText(String reason, String fallback) {
        if (reason == null) {
            return fallback;
        }
        return reason.startsWith("keploy: ") ? reason : "keploy: " + reason;
    }

    /** One JVM's part in keploy's run. */
    static final class Run {
        private final Joining joining;
        private boolean claimed;
        private AgentClient client;
        // Why no test of this JVM can run judged: a claim that failed.
        private String error;
        // Every test ID begun in this JVM: a second begin of one fails.
        private final Set<String> begun = new HashSet<>();

        Run(Joining joining) {
            this.joining = joining;
        }

        /**
         * Whether this JVM's tests report to keploy: false when it is inert;
         * throws when they cannot run judged (a refused KEPLOY_MOCK_AGENT, a
         * run JUnit does not let this extension wrap whole, a failed claim).
         * The claim is made at the first call, once.
         */
        synchronized boolean ready(ExtensionContext ctx) {
            if (joining.inert()) {
                return false;
            }
            if (joining.refusal != null) {
                throw new KeployException(joining.refusal);
            }
            // JUnit reads both with Boolean.parseBoolean.
            if (!ctx.getConfigurationParameter(AUTODETECT).map(Boolean::parseBoolean).orElse(false)) {
                throw new KeployException("keploy: keploy-junit5 wraps every test of the run, outside every other extension,"
                        + " only when JUnit loads it for the whole run: set " + AUTODETECT + "=true (in"
                        + " src/test/resources/junit-platform.properties, or as a system property) instead of naming it in @ExtendWith");
            }
            if (ctx.getConfigurationParameter(PARALLEL).map(Boolean::parseBoolean).orElse(false)) {
                throw new KeployException("keploy: keploy runs one test at a time, and this run has JUnit's parallel execution on ("
                        + PARALLEL + "=true): turn it off for runs under keploy");
            }
            if (!claimed) {
                claimed = true;
                claim();
            }
            if (client == null) {
                throw new KeployException(error);
            }
            return true;
        }

        private void claim() {
            String d = joining.project.isEmpty() ? System.getProperty("user.dir") : joining.project;
            AgentClient c = new AgentClient(joining.url, joining.token);
            try {
                c.claim(".", new File(d).getCanonicalPath());
            } catch (KeployException e) {
                error = e.getMessage();
                return;
            } catch (IOException e) {
                error = "keploy: cannot resolve keploy's project folder " + d + ": " + e;
                return;
            }
            client = c;
        }

        /** Begins t: keploy's skip reason when keploy set it aside, else null. */
        synchronized String begin(TestRef t) {
            if (!begun.add(t.id())) {
                throw new KeployException("keploy: " + t.name + " in " + t.file + " would begin a second time, and keploy names a"
                        + " test by its class and name: run each test once (no reruns, such as Surefire's rerunFailingTestsCount)");
            }
            Map<String, Object> a = client.begin(t);
            if (AgentClient.ACTION_SKIP.equals(a.get("action"))) {
                return keployText(AgentClient.text(a, "reason"), "keploy: set aside (the agent gave no reason)");
            }
            return null;
        }

        /** Ends t: keploy's text when keploy failed it, else null. */
        synchronized String end(TestRef t, String outcome) {
            Map<String, Object> a = client.end(t, outcome);
            if (AgentClient.ACTION_FAIL.equals(a.get("action"))) {
                return keployText(AgentClient.text(a, "reason"), "keploy: the keploy agent failed this test and gave no reason");
            }
            return null;
        }
    }
}
