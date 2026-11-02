package io.keploy.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class AgentClientTest {
    static final TestRef T = new TestRef("shop.OrdersTest", "prices()");
    FakeAgent agent;

    @BeforeEach
    void start() throws IOException {
        agent = new FakeAgent();
    }

    @AfterEach
    void stop() {
        agent.close();
    }

    void answer(int status, String body) {
        agent.answer = (p, b) -> new FakeAgent.Answer(status, body);
    }

    String error(Executable call) {
        return assertThrows(KeployException.class, call).getMessage();
    }

    @Test
    void sendsTheWireAndItsToken() {
        AgentClient c = new AgentClient(agent.url(), "tok");
        assertEquals("i-1", c.claim(".", "/p").get("instance"));
        assertEquals("Bearer tok", agent.lastAuth);
        c.begin(T);
        c.end(T, "passed");
        assertEquals("POST /agent/instance {\"key\":\".\",\"dir\":\"/p\"}", agent.calls.get(0));
        assertEquals("POST /agent/scope/begin {\"name\":\"prices()\",\"file\":\"shop.OrdersTest\",\"canSkip\":true}", agent.calls.get(1));
        assertEquals("POST /agent/scope/end {\"name\":\"prices()\",\"file\":\"shop.OrdersTest\",\"outcome\":\"passed\"}", agent.calls.get(2));
        agent.client().begin(T);
        assertNull(agent.lastAuth);
    }

    @Test
    void takesAny2xxAndAVersionThatEqualsOne() {
        answer(299, "{\"v\":1.0,\"status\":\"ok\",\"action\":\"\"}");
        assertEquals("ok", agent.client().begin(T).get("status"));
    }

    @Test
    void takesAnAnswerOfTheLargestSize() {
        String head = "{\"v\":1,\"status\":\"ok\",\"pad\":\"";
        StringBuilder b = new StringBuilder(head);
        while (b.length() < AgentClient.MAX_ANSWER - 2) {
            b.append('x');
        }
        answer(200, b.append("\"}").toString());
        agent.client().begin(T);
        answer(200, b.insert(head.length(), 'x').toString());
        assertEquals("keploy: cannot read the keploy agent's answer to /agent/scope/begin: java.io.IOException: the answer is larger than 1048576 bytes",
                error(() -> agent.client().begin(T)));
    }

    @Test
    void namesTheProtocolsErrorWithItsRemedy() {
        answer(409, "{\"error\":\"not_open\",\"message\":\"no test is open\",\"remedy\":\"begin it first\"}");
        KeployException e = assertThrows(KeployException.class, () -> agent.client().end(T, "passed"));
        assertEquals("keploy: no test is open (begin it first)", e.getMessage());
        assertEquals(409, e.status);
        assertEquals("not_open", e.code);
        answer(400, "{\"message\":\"bad name\",\"remedy\":\"\",\"error\":7}");
        e = assertThrows(KeployException.class, () -> agent.client().end(T, "passed"));
        assertEquals("keploy: bad name", e.getMessage());
        assertEquals("", e.code);
    }

    @Test
    void quotesAnyOtherNon2xxAnswer() {
        answer(500, "  boom  ");
        assertEquals("keploy: the keploy agent answered 500 to /agent/scope/begin: \"boom\"", error(() -> agent.client().begin(T)));
        answer(503, "{\"message\":\"\"}");
        assertEquals("keploy: the keploy agent answered 503 to /agent/scope/begin: \"{\\\"message\\\":\\\"\\\"}\"", error(() -> agent.client().begin(T)));
        answer(502, "[\"message\"]");
        assertEquals("keploy: the keploy agent answered 502 to /agent/scope/begin: \"[\\\"message\\\"]\"", error(() -> agent.client().begin(T)));
        answer(500, "");
        assertEquals("keploy: the keploy agent answered 500 to /agent/scope/begin: \"\"", error(() -> agent.client().begin(T)));
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < AgentClient.MAX_QUOTED; k++) {
            b.append('y');
        }
        answer(500, b.toString());
        assertEquals("keploy: the keploy agent answered 500 to /agent/scope/begin: \"" + b + "\"", error(() -> agent.client().begin(T)));
        answer(500, b + "z");
        assertEquals("keploy: the keploy agent answered 500 to /agent/scope/begin: \"" + b + "\"...", error(() -> agent.client().begin(T)));
    }

    @Test
    void followsNoRedirect() {
        int[] seen = {0};
        agent.answer = (p, b) -> {
            seen[0]++;
            return seen[0] == 1 ? new FakeAgent.Answer(307, "", agent.url() + "/other") : FakeAgent.ok(p, b);
        };
        assertEquals("keploy: the keploy agent answered 307 to /agent/scope/begin: \"\"", error(() -> agent.client().begin(T)));
        answer(300, "{\"v\":1,\"status\":\"ok\"}");
        assertTrue(error(() -> agent.client().begin(T)).startsWith("keploy: the keploy agent answered 300 to /agent/scope/begin: "));
        assertEquals(2, agent.calls.size());
    }

    @Test
    void refusesAnAnswerItCannotTrust() {
        answer(200, "{\"status\":\"ok\"}");
        assertEquals("keploy: the keploy agent answered /agent/scope/begin with \"{\\\"status\\\":\\\"ok\\\"}\", which names no scope protocol"
                + " version; this keploy-junit5 needs a keploy agent that speaks scope protocol v1: upgrade keploy", error(() -> agent.client().begin(T)));
        answer(200, "{\"v\":null}");
        assertTrue(error(() -> agent.client().begin(T)).contains("which names no scope protocol version"));
        answer(200, "{\"v\":2}");
        assertEquals("keploy: the keploy agent answered /agent/scope/begin in scope protocol v2 (\"{\\\"v\\\":2}\"); this keploy-junit5 speaks"
                + " scope protocol v1: use a keploy and a keploy-junit5 of the same release", error(() -> agent.client().begin(T)));
        answer(200, "{\"v\":\"1\"}");
        assertTrue(error(() -> agent.client().begin(T)).startsWith("keploy: the keploy agent answered /agent/scope/begin in scope protocol v\"1\" ("));
        answer(200, "{\"v\":true}");
        assertTrue(error(() -> agent.client().begin(T)).startsWith("keploy: the keploy agent answered /agent/scope/begin in scope protocol vtrue ("));
        answer(200, "[1]");
        assertEquals("keploy: the keploy agent's answer to /agent/scope/begin cannot be read (not an object): \"[1]\"", error(() -> agent.client().begin(T)));
        answer(200, "{\"v\":1");
        assertEquals("keploy: the keploy agent's answer to /agent/scope/begin cannot be read (want '}' at offset 6): \"{\\\"v\\\":1\"",
                error(() -> agent.client().begin(T)));
    }

    @Test
    void refusesABeginOrEndThatIsNotOk() {
        answer(200, "{\"v\":1,\"status\":\"held\"}");
        assertEquals("keploy: the keploy agent answered the begin of prices() with status \"held\"", error(() -> agent.client().begin(T)));
        assertEquals("keploy: the keploy agent answered the end of prices() with status \"held\"", error(() -> agent.client().end(T, "passed")));
        answer(200, "{\"v\":1}");
        assertEquals("keploy: the keploy agent answered the begin of prices() with status \"\"", error(() -> agent.client().begin(T)));
        answer(200, "{\"v\":1,\"status\":2}");
        assertEquals("keploy: the keploy agent answered the end of prices() with status 2", error(() -> agent.client().end(T, "passed")));
        answer(200, "{\"v\":1,\"status\":\"ok\",\"action\":\"fail\"}");
        assertEquals("keploy: the keploy agent answered the begin of prices() with the unknown action \"fail\"", error(() -> agent.client().begin(T)));
        assertEquals("fail", agent.client().end(T, "passed").get("action"));
        answer(200, "{\"v\":1,\"status\":\"ok\",\"action\":\"skip\"}");
        assertEquals("skip", agent.client().begin(T).get("action"));
        assertEquals("keploy: the keploy agent answered the end of prices() with the unknown action \"skip\"", error(() -> agent.client().end(T, "passed")));
        answer(200, "{\"v\":1,\"status\":\"ok\",\"action\":false}");
        assertEquals("keploy: the keploy agent answered the begin of prices() with the unknown action false", error(() -> agent.client().begin(T)));
        answer(200, "{\"v\":1,\"status\":\"ok\",\"action\":null}");
        agent.client().end(T, "passed");
    }

    @Test
    void namesKeploysProvenSwitchOnAPlain404Claim() {
        answer(404, "404 page not found\n");
        KeployException e = assertThrows(KeployException.class, () -> agent.client().claim(".", "/p"));
        assertEquals("keploy: this keploy agent answered 404 to POST /agent/instance, so this JUnit process cannot claim keploy's run:"
                + " keploy speaks keploy-junit5's wire, scope protocol v1, only when it runs with KEPLOY_PROVEN=1"
                + " (KEPLOY_PROVEN=1 keploy mock record|replay -c \"mvn test\"); a keploy run without KEPLOY_PROVEN=1, or one from before"
                + " scope protocol v1, answers 404 there", e.getMessage());
        answer(404, "{\"error\":\"no_run\",\"message\":\"no run\"}");
        assertEquals("keploy: no run", error(() -> agent.client().claim(".", "/p")));
        answer(410, "gone");
        assertEquals("keploy: the keploy agent answered 410 to /agent/instance: \"gone\"", error(() -> agent.client().claim(".", "/p")));
        answer(404, "x");
        assertEquals("keploy: the keploy agent answered 404 to /agent/scope/end: \"x\"", error(() -> agent.client().end(T, "passed")));
    }

    @Test
    void refusesAClaimWithoutAnInstance() {
        for (String body : new String[] {"{\"v\":1}", "{\"v\":1,\"instance\":\"\"}", "{\"v\":1,\"instance\":true}"}) {
            answer(200, body);
            assertEquals("keploy: the keploy agent answered the claim of this test process without an instance",
                    error(() -> agent.client().claim(".", "/p")), body);
        }
    }

    @Test
    void namesAnAgentItCannotReach() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        String url = "http://127.0.0.1:" + port;
        assertTrue(error(() -> new AgentClient(url, "").begin(T)).startsWith("keploy: cannot reach the keploy agent at " + url + ": java.net.ConnectException"));
    }

    @Test
    void readsTheReasonOfAnAnswer() {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("a", "");
        m.put("b", 1);
        m.put("c", "x");
        assertNull(AgentClient.text(m, "a"));
        assertNull(AgentClient.text(m, "b"));
        assertNull(AgentClient.text(m, "z"));
        assertEquals("x", AgentClient.text(m, "c"));
    }
}
