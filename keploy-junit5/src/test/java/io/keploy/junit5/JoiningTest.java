package io.keploy.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongPredicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JoiningTest {
    static final LongPredicate NEVER_ASKED = p -> {
        throw new AssertionError("asked whether this process runs inside " + p);
    };

    static Map<String, String> env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int k = 0; k < kv.length; k += 2) {
            m.put(kv[k], kv[k + 1]);
        }
        return m;
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "off", " OFF "})
    void isInertWithoutAnAgent(String v) {
        assertSame(Joining.INERT, Joining.decide(env("KEPLOY_MOCK_AGENT", v), 10, NEVER_ASKED));
        assertTrue(Joining.INERT.inert());
    }

    @Test
    void isInertWithNoAgentSet() {
        assertSame(Joining.INERT, Joining.decide(env(), 10, NEVER_ASKED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost:16789", "ftp://h:1", "http://", "http://h:abc", "http://a b", "mailto:x@h"})
    void refusesAnAgentThatIsNoHttpURL(String v) {
        Joining j = Joining.decide(env("KEPLOY_MOCK_AGENT", v), 10, NEVER_ASKED);
        assertEquals("keploy: KEPLOY_MOCK_AGENT=" + Json.quote(v) + " is neither off nor the http URL of a keploy agent;"
                + " set it to off to run without keploy", j.refusal);
        assertNull(j.url);
        assertFalse(j.inert());
    }

    @Test
    void joinsWithTheAgentsURLTokenAndProject() {
        Joining j = Joining.decide(env("KEPLOY_MOCK_AGENT", " HTTP://127.0.0.1:16789// ", "KEPLOY_MOCK_AGENT_TOKEN", " t ",
                "KEPLOY_MOCK_PROJECT", " /p "), 10, NEVER_ASKED);
        assertEquals("HTTP://127.0.0.1:16789", j.url);
        assertEquals(" t ", j.token);
        assertEquals("/p", j.project);
        assertNull(j.refusal);
        assertFalse(j.inert());
        j = Joining.decide(env("KEPLOY_MOCK_AGENT", "https://[::1]:1"), 10, NEVER_ASKED);
        assertEquals("https://[::1]:1", j.url);
        assertEquals("", j.token);
        assertEquals("", j.project);
    }

    @Test
    void isAMemberOnlyInsideTheProcessTheMarkerNames() {
        String agent = "http://127.0.0.1:1";
        // Its own pid: the marker it exported itself.
        assertNull(Joining.decide(env("KEPLOY_MOCK_AGENT", agent, "KEPLOY_TEST_PID", " 10 "), 10, NEVER_ASKED).refusal);
        assertFalse(Joining.decide(env("KEPLOY_MOCK_AGENT", agent, "KEPLOY_TEST_PID", "10"), 10, NEVER_ASKED).inert());
        for (String tp : new String[] {"abc", "0", "-5", ""}) {
            assertFalse(Joining.decide(env("KEPLOY_MOCK_AGENT", agent, "KEPLOY_TEST_PID", tp), 10, NEVER_ASKED).inert(), tp);
        }
        long[] asked = {0};
        assertSame(Joining.INERT, Joining.decide(env("KEPLOY_MOCK_AGENT", agent, "KEPLOY_TEST_PID", " 77 "), 10, p -> {
            asked[0] = p;
            return true;
        }));
        assertEquals(77, asked[0]);
        assertFalse(Joining.decide(env("KEPLOY_MOCK_AGENT", agent, "KEPLOY_TEST_PID", "77"), 10, p -> false).inert());
        assertTrue(Joining.decide(env("KEPLOY_MOCK_AGENT", agent, "KEPLOY_TEST_PID", "1"), 10, p -> true).inert());
    }

    @Test
    void readsItsAncestorsFromProc() throws IOException {
        long self = Long.parseLong(new File("/proc/self").getCanonicalFile().getName());
        assertEquals(self, Joining.pid());
        long parent = Joining.parent("self");
        assertTrue(parent > 0);
        assertTrue(Joining.runsInsideProc(parent));
        assertTrue(Joining.runsInsideProc(Joining.parent(Long.toString(parent))));
        assertTrue(Joining.runsInsideProc(1));
        assertFalse(Joining.runsInsideProc(self));
        assertEquals(-1, Joining.parent("no-such-process"));
    }

    @Test
    void readsTheParentPastACommandNameWithParentheses() {
        assertEquals(77, Joining.ppid("123 (a) b) c) S 77 1 1 0"));
        assertEquals(-1, Joining.ppid("123 (a) S"));
        assertEquals(-1, Joining.ppid("123 a S 77"));
        assertEquals(-1, Joining.ppid("123 (a) S x 1"));
    }
}
