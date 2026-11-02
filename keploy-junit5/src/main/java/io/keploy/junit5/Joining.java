package io.keploy.junit5;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.function.LongPredicate;

/**
 * Whether this process reports to keploy: the same rule go-sdk's keploytest
 * follows. keploy mock record and keploy mock replay export KEPLOY_MOCK_AGENT
 * (the agent's URL), KEPLOY_MOCK_AGENT_TOKEN and KEPLOY_MOCK_PROJECT (the
 * project's directory) into the test command. Unset or "off", the extension
 * is inert and every test runs as it would without keploy.
 */
final class Joining {
    static final String ENV_AGENT = "KEPLOY_MOCK_AGENT";
    static final String ENV_TOKEN = "KEPLOY_MOCK_AGENT_TOKEN";
    static final String ENV_PROJECT = "KEPLOY_MOCK_PROJECT";
    // The claimer's pid, exported by a test process (of go-sdk, keploy-pytest,
    // ...) once it has claimed the run, so the processes its tests start know
    // they belong to it and do not claim the run themselves. A JVM cannot
    // change its own environment, so this extension exports none: a process
    // its tests start that claims too is refused by the agent.
    static final String ENV_TEST_PID = "KEPLOY_TEST_PID";

    static final Joining INERT = new Joining(null, "", "", null);

    /** The agent's URL, without a trailing slash; null when inert or refused. */
    final String url;
    final String token;
    /** KEPLOY_MOCK_PROJECT, trimmed; "" when keploy names none. */
    final String project;
    /** Why this process cannot join keploy's run; null when it can. */
    final String refusal;

    private Joining(String url, String token, String project, String refusal) {
        this.url = url;
        this.token = token;
        this.project = project;
        this.refusal = refusal;
    }

    boolean inert() {
        return url == null && refusal == null;
    }

    /** Inert, refused or active, from the environment of process pid. */
    static Joining decide(Map<String, String> env, long pid, LongPredicate runsInside) {
        String v = get(env, ENV_AGENT).trim();
        if (v.isEmpty() || v.equalsIgnoreCase("off")) {
            return INERT;
        }
        if (!httpURL(v)) {
            return new Joining(null, "", "", "keploy: " + ENV_AGENT + "=" + Json.quote(v)
                    + " is neither off nor the http URL of a keploy agent; set it to off to run without keploy");
        }
        String tp = get(env, ENV_TEST_PID).trim();
        if (!tp.equals(Long.toString(pid))) {
            // Another process joined keploy's run. This one is a member only
            // when it runs inside that process; a marker naming any other
            // process was inherited from elsewhere, and this process claims
            // like any test process (the agent refuses a second live one).
            long n = 0;
            try {
                n = Long.parseLong(tp);
            } catch (NumberFormatException e) {
                // no marker ("") or no pid
            }
            if (n > 0 && runsInside.test(n)) {
                return INERT;
            }
        }
        return new Joining(v.replaceAll("/+$", ""), get(env, ENV_TOKEN), get(env, ENV_PROJECT).trim(), null);
    }

    private static String get(Map<String, String> env, String key) {
        String v = env.get(key);
        return v == null ? "" : v;
    }

    private static boolean httpURL(String v) {
        try {
            URI u = new URI(v);
            // getHost is null when the authority is no host[:port] (a port
            // that is not a number, say).
            return ("http".equalsIgnoreCase(u.getScheme()) || "https".equalsIgnoreCase(u.getScheme())) && u.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** This process's pid. */
    static long pid() {
        String n = ManagementFactory.getRuntimeMXBean().getName();
        return Long.parseLong(n.substring(0, n.indexOf('@')));
    }

    /**
     * Whether this process runs inside process pid (pid is an ancestor), read
     * from /proc. Without /proc (a system other than Linux) it cannot tell,
     * and takes the marker at its word.
     */
    static boolean runsInsideProc(long pid) {
        if (!Files.exists(Paths.get("/proc/self/stat"))) {
            return true;
        }
        long p = parent("self");
        // Bounded: a walk that cycles (a pid reused while it is read) ends.
        for (int k = 0; k < 128; k++) {
            if (p == pid) {
                return true;
            }
            p = parent(Long.toString(p));
            if (p < 0) { // past the first process (its parent, 0, has no entry), or gone
                return false;
            }
        }
        return false;
    }

    /** The parent pid of /proc/{proc}; -1 when it cannot be read. */
    static long parent(String proc) {
        try {
            return ppid(new String(Files.readAllBytes(Paths.get("/proc", proc, "stat")), StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            return -1;
        }
    }

    /** The parent pid in a /proc stat line; -1 when it holds none. */
    static long ppid(String stat) {
        try {
            // The command name, in parentheses, may hold spaces and
            // parentheses: the fields after it start at the last ')'.
            String[] rest = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            return Long.parseLong(rest[1]);
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
