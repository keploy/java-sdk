package io.keploy.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Event;
import org.junit.platform.testkit.engine.EventType;
import org.opentest4j.TestAbortedException;

class ExtensionTest {
    static final String F = "io.keploy.junit5.Fixtures$";
    FakeAgent agent;
    String project;

    @BeforeEach
    void start(@TempDir Path tmp) throws IOException {
        agent = new FakeAgent();
        FakeAgent.LOG.clear();
        // keploy's project folder through a symlink: the claim names the folder itself.
        Path real = Files.createDirectory(tmp.resolve("real"));
        project = real.toFile().getCanonicalPath();
        KeployExtension.use(new KeployExtension.Run(joining(Files.createSymbolicLink(tmp.resolve("link"), real).toString())));
    }

    @AfterEach
    void stop() {
        KeployExtension.use(null);
        agent.close();
    }

    Joining joining(String projectDir) {
        return Joining.decide(JoiningTest.env("KEPLOY_MOCK_AGENT", agent.url(), "KEPLOY_MOCK_PROJECT", projectDir), 10, p -> false);
    }

    /** Runs one fixture class through JUnit, keploy-junit5 loaded for the whole run, and more parameters. */
    static List<Event> run(Class<?> c, String... params) {
        EngineTestKit.Builder b = EngineTestKit.engine("junit-jupiter").selectors(DiscoverySelectors.selectClass(c))
                .configurationParameter(KeployExtension.AUTODETECT, "true");
        for (int k = 0; k < params.length; k += 2) {
            b.configurationParameter(params[k], params[k + 1]);
        }
        return b.execute().allEvents().list();
    }

    /** Each test's and failed class's result: its outcome and message, by JUnit's name of it. */
    static Map<String, String> results(List<Event> events) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Event e : events) {
            String name = e.getTestDescriptor().getUniqueId().getLastSegment().getValue();
            if (e.getType() == EventType.SKIPPED) {
                m.put(name, "SKIPPED");
            } else if (e.getType() == EventType.FINISHED) {
                TestExecutionResult r = e.getPayload(TestExecutionResult.class).get();
                if (e.getTestDescriptor().isTest() || r.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
                    m.put(name, r.getStatus() + r.getThrowable().map(t -> ": " + t.getMessage()
                            + (t.getSuppressed().length > 0 ? " + " + t.getSuppressed()[0].getMessage() : "")).orElse(""));
                }
            }
        }
        return m;
    }

    static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int k = 0; k < kv.length; k += 2) {
            m.put(kv[k], kv[k + 1]);
        }
        return m;
    }

    static List<String> log() {
        return new ArrayList<>(FakeAgent.LOG);
    }

    void answer(String what, String name, String body) {
        agent.answer = (p, b) -> p.endsWith(what) && name.equals(b.get("name")) ? new FakeAgent.Answer(200, body) : FakeAgent.ok(p, b);
    }

    @Test
    void wrapsEachTestOutsideEveryOtherExtensionWithItsOutcome() {
        assertEquals(map("assumes()", "ABORTED: Assumption failed: not here", "fails()", "FAILED: boom", "passes()", "SUCCESSFUL"),
                results(run(Fixtures.Orders.class)));
        List<String> want = new ArrayList<>(Arrays.asList("claim . " + project, "@BeforeAll"));
        for (String[] t : new String[][] {{"assumes", "skipped"}, {"fails", "failed"}, {"passes", "passed"}}) {
            want.addAll(Arrays.asList("begin " + F + "Orders::" + t[0] + "()", "other.beforeEach", "@BeforeEach " + t[0], t[0],
                    "@AfterEach " + t[0], "other.afterEach", "end " + F + "Orders::" + t[0] + "() " + t[1]));
        }
        want.add("@AfterAll");
        assertEquals(want, log());
    }

    @Test
    void claimsOncePerJvmFromTheCurrentFolderWhenKeployNamesNone() throws IOException {
        KeployExtension.use(new KeployExtension.Run(joining("")));
        run(Fixtures.Payments.class);
        run(Fixtures.AfterEachFails.class);
        assertEquals(Arrays.asList("claim . " + new File(System.getProperty("user.dir")).getCanonicalPath(), "begin " + F + "Payments::charges()",
                "charges", "end " + F + "Payments::charges() passed", "begin " + F + "AfterEachFails::cleans()", "cleans",
                "end " + F + "AfterEachFails::cleans() failed"), log());
    }

    @Test
    void skipsATestKeploySetAsideBeforeAnyOfItsCode() {
        answer("begin", "passes()", "{\"v\":1,\"status\":\"ok\",\"action\":\"skip\",\"reason\":\"quarantined/flaky: set aside by a row\"}");
        assertEquals("ABORTED: keploy: quarantined/flaky: set aside by a row", results(run(Fixtures.Orders.class)).get("passes()"));
        List<String> l = log();
        int at = l.indexOf("begin " + F + "Orders::passes()");
        // JUnit runs every extension's AfterEach callback after a BeforeEach
        // callback threw; none of the test's own methods runs.
        assertEquals(Arrays.asList("begin " + F + "Orders::passes()", "other.afterEach", "@AfterAll"), l.subList(at, l.size()));
        FakeAgent.LOG.clear();
        answer("begin", "charges()", "{\"v\":1,\"status\":\"ok\",\"action\":\"skip\"}");
        assertEquals(map("charges()", "ABORTED: keploy: set aside (the agent gave no reason)"), results(run(Fixtures.Payments.class)));
        answer("begin", "cleans()", "{\"v\":1,\"status\":\"ok\",\"action\":\"skip\",\"reason\":\"keploy: held\"}");
        assertEquals(map("cleans()", "ABORTED: keploy: held"), results(run(Fixtures.AfterEachFails.class)));
        assertEquals(Arrays.asList("begin " + F + "Payments::charges()", "begin " + F + "AfterEachFails::cleans()"), log());
    }

    @Test
    void failsATestKeployFailedAtItsEnd() {
        agent.answer = (p, b) -> p.endsWith("end") && "passes()".equals(b.get("name"))
                ? new FakeAgent.Answer(200, "{\"v\":1,\"status\":\"ok\",\"action\":\"fail\",\"reason\":\"keploy: GET /x had no recorded answer\"}")
                : p.endsWith("end") && "fails()".equals(b.get("name"))
                ? new FakeAgent.Answer(200, "{\"v\":1,\"status\":\"ok\",\"action\":\"fail\",\"reason\":\"unmocked\"}")
                : FakeAgent.ok(p, b);
        Map<String, String> r = results(run(Fixtures.Orders.class));
        assertEquals("FAILED: keploy: GET /x had no recorded answer", r.get("passes()"));
        assertEquals("FAILED: boom + keploy: unmocked", r.get("fails()"));
        answer("end", "charges()", "{\"v\":1,\"status\":\"ok\",\"action\":\"fail\"}");
        assertEquals(map("charges()", "FAILED: keploy: the keploy agent failed this test and gave no reason"), results(run(Fixtures.Payments.class)));
    }

    @Test
    void failsTheTestAnAnswerItCannotTrustBelongsTo() {
        agent.answer = (p, b) -> p.endsWith("begin") && "passes()".equals(b.get("name")) ? new FakeAgent.Answer(500, "down")
                : p.endsWith("end") && "fails()".equals(b.get("name")) ? new FakeAgent.Answer(200, "{\"v\":2}") : FakeAgent.ok(p, b);
        Map<String, String> r = results(run(Fixtures.Orders.class));
        assertEquals("FAILED: keploy: the keploy agent answered 500 to /agent/scope/begin: \"down\"", r.get("passes()"));
        assertTrue(r.get("fails()").startsWith("FAILED: boom + keploy: the keploy agent answered /agent/scope/end in scope protocol v2"));
        List<String> l = log();
        assertEquals(Arrays.asList("begin " + F + "Orders::passes()", "other.afterEach", "@AfterAll"),
                l.subList(l.indexOf("begin " + F + "Orders::passes()"), l.size()));
    }

    @Test
    void failsEveryClassWhenTheClaimFails() {
        agent.answer = (p, b) -> new FakeAgent.Answer(404, "404 page not found");
        assertTrue(results(run(Fixtures.Payments.class)).get(F + "Payments")
                .startsWith("FAILED: keploy: this keploy agent answered 404 to POST /agent/instance"));
        assertTrue(results(run(Fixtures.Orders.class)).get(F + "Orders").startsWith("FAILED: keploy: this keploy agent answered 404"));
        assertEquals(Collections.singletonList("claim . " + project), log());
    }

    @Test
    void failsEveryClassWhenKeploysAgentIsRefused() {
        KeployExtension.use(new KeployExtension.Run(Joining.decide(JoiningTest.env("KEPLOY_MOCK_AGENT", "nope"), 10, p -> false)));
        assertEquals(map(F + "Payments", "FAILED: keploy: KEPLOY_MOCK_AGENT=\"nope\" is neither off nor the http URL of a keploy agent;"
                + " set it to off to run without keploy"), results(run(Fixtures.Payments.class)));
        assertEquals(Collections.emptyList(), log());
    }

    @Test
    void doesNothingWhenInert() {
        KeployExtension.use(new KeployExtension.Run(Joining.INERT));
        assertEquals(map("assumes()", "ABORTED: Assumption failed: not here", "fails()", "FAILED: boom", "passes()", "SUCCESSFUL"),
                results(run(Fixtures.Orders.class, KeployExtension.PARALLEL, "true")));
        assertEquals(map("extended()", "SUCCESSFUL"), results(EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(Fixtures.Extended.class)).execute().allEvents().list()));
        assertTrue(agent.calls.isEmpty());
        assertTrue(!log().contains("claim"));
    }

    @Test
    void refusesARunItDoesNotWrapWhole() {
        String text = "FAILED: keploy: keploy-junit5 wraps every test of the run, outside every other extension, only when JUnit loads it"
                + " for the whole run: set junit.jupiter.extensions.autodetection.enabled=true (in src/test/resources/junit-platform.properties,"
                + " or as a system property) instead of naming it in @ExtendWith";
        assertEquals(map(F + "Extended", text), results(EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(Fixtures.Extended.class)).execute().allEvents().list()));
        assertEquals(map(F + "Extended", text), results(run(Fixtures.Extended.class, KeployExtension.AUTODETECT, "TRUE ")));
        assertEquals(map("extended()", "SUCCESSFUL"), results(run(Fixtures.Extended.class, KeployExtension.AUTODETECT, "True")));
        assertEquals(map(F + "Payments", "FAILED: keploy: keploy runs one test at a time, and this run has JUnit's parallel execution on"
                + " (junit.jupiter.execution.parallel.enabled=true): turn it off for runs under keploy"),
                results(run(Fixtures.Payments.class, KeployExtension.PARALLEL, "TRUE")));
        assertEquals(map("charges()", "SUCCESSFUL"), results(run(Fixtures.Payments.class, KeployExtension.PARALLEL, "false")));
    }

    @Test
    void failsATestBegunASecondTime() {
        run(Fixtures.Payments.class);
        assertEquals(map("charges()", "FAILED: keploy: charges() in " + F + "Payments would begin a second time, and keploy names a test by its"
                + " class and name: run each test once (no reruns, such as Surefire's rerunFailingTestsCount)"), results(run(Fixtures.Payments.class)));
        assertEquals(1, log().stream().filter(s -> s.startsWith("begin ")).count());
    }

    @Test
    void namesEachTestAsJUnitDoes() {
        Map<String, String> r = results(run(Fixtures.Named.class));
        assertTrue(r.get("dynamic()").startsWith("FAILED: keploy: keploy-junit5 begins and ends each test at JUnit's BeforeEach and AfterEach"
                + " callbacks, and cannot name [engine:junit-jupiter]/[class:" + F + "Named]/[test-factory:dynamic()] as one test (a test-factory;"));
        assertEquals(Arrays.asList("claim . " + project,
                "begin " + F + "Named::price(int)[1]", "end " + F + "Named::price(int)[1] passed",
                "begin " + F + "Named::price(int)[2]", "end " + F + "Named::price(int)[2] passed",
                "begin " + F + "Named::repeats()[1]", "end " + F + "Named::repeats()[1] passed",
                "begin " + F + "Named::repeats()[2]", "end " + F + "Named::repeats()[2] passed",
                "begin " + F + "Named$Inner::inner()", "end " + F + "Named$Inner::inner() passed"), log());
    }

    @Test
    void takesAnAbortAsSkipped() {
        assertEquals("passed", KeployExtension.outcome(null));
        assertEquals("skipped", KeployExtension.outcome(new TestAbortedException("x")));
        assertEquals("failed", KeployExtension.outcome(new AssertionError("x")));
        assertEquals("failed", KeployExtension.outcome(new KeployException("x")));
    }
}
