package io.keploy.junit5;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The client of keploy's agent: scope protocol v1, the same wire go-sdk's
 * keploytest, @keploy/playwright, @keploy/jest and keploy-pytest speak (POST
 * /agent/instance, /agent/scope/begin, /agent/scope/end).
 *
 * <p>Every answer of the agent carries the protocol version as "v"; an answer
 * without it, or with another version, is an error, so this extension on an
 * old agent (or the reverse) fails loudly instead of running unjudged. Every
 * way an answer can fail to be trusted is an error. Nothing is retried, and no
 * clock decides anything: there is no timeout, as the agent answers once the
 * work it owes is done.
 */
final class AgentClient {
    static final int PROTOCOL_VERSION = 1;

    static final String PATH_INSTANCE = "/agent/instance";
    static final String PATH_BEGIN = "/agent/scope/begin";
    static final String PATH_END = "/agent/scope/end";

    static final String ACTION_SKIP = "skip";
    static final String ACTION_FAIL = "fail";

    // Bounds what is read of one answer, and how much of one an error quotes.
    static final int MAX_ANSWER = 1 << 20;
    static final int MAX_QUOTED = 512;

    private final String url;
    private final String token;

    AgentClient(String url, String token) {
        this.url = url;
        this.token = token;
    }

    static String quoted(String text) {
        String s = text.trim();
        if (s.length() > MAX_QUOTED) {
            return Json.quote(s.substring(0, MAX_QUOTED)) + "...";
        }
        return Json.quote(s);
    }

    /** Sends one call and reads its answer: the answer's object. */
    Map<String, Object> post(String path, Map<String, ?> body) {
        byte[] out = Json.object(body).getBytes(StandardCharsets.UTF_8);
        int status;
        InputStream in;
        try {
            // No proxy from the environment (the agent is keploy's own, on
            // this machine), and no redirect: a redirect is an answer like
            // any other non-2xx, and following it would send the call again,
            // or to somewhere else.
            HttpURLConnection c = (HttpURLConnection) new URL(url + path).openConnection(Proxy.NO_PROXY);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            // A fixed length streams the body, and HttpURLConnection never
            // sends a streamed request a second time (it may resend a POST
            // whose answer it failed to read otherwise).
            c.setFixedLengthStreamingMode(out.length);
            c.setRequestProperty("content-type", "application/json");
            if (!token.isEmpty()) {
                c.setRequestProperty("authorization", "Bearer " + token);
            }
            try (OutputStream o = c.getOutputStream()) {
                o.write(out);
            }
            status = c.getResponseCode();
            in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        } catch (IOException e) {
            throw new KeployException("keploy: cannot reach the keploy agent at " + url + ": " + e, 0, "");
        }
        String data;
        try {
            data = readBounded(in);
        } catch (IOException e) {
            throw new KeployException("keploy: cannot read the keploy agent's answer to " + path + ": " + e, status, "");
        }
        if (status < 200 || status > 299) {
            Object e = parseOrNull(data);
            if (e instanceof Map && text((Map<?, ?>) e, "message") != null) {
                Map<?, ?> m = (Map<?, ?>) e;
                String code = text(m, "error");
                String remedy = text(m, "remedy");
                throw new KeployException("keploy: " + text(m, "message") + (remedy == null ? "" : " (" + remedy + ")"),
                        status, code == null ? "" : code);
            }
            throw new KeployException("keploy: the keploy agent answered " + status + " to " + path + ": "
                    + quoted(data), status, "");
        }
        Object a;
        try {
            a = Json.parse(data);
        } catch (IllegalArgumentException e) {
            throw new KeployException("keploy: the keploy agent's answer to " + path + " cannot be read (" + e.getMessage()
                    + "): " + quoted(data), status, "");
        }
        if (!(a instanceof Map)) {
            throw new KeployException("keploy: the keploy agent's answer to " + path + " cannot be read (not an object): "
                    + quoted(data), status, "");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) a;
        if (m.get("v") == null) {
            throw new KeployException("keploy: the keploy agent answered " + path + " with " + quoted(data)
                    + ", which names no scope protocol version; this keploy-junit5 needs a keploy agent that speaks scope protocol v"
                    + PROTOCOL_VERSION + ": upgrade keploy", status, "");
        }
        Object v = m.get("v");
        if (!(v instanceof BigDecimal) || ((BigDecimal) v).compareTo(BigDecimal.valueOf(PROTOCOL_VERSION)) != 0) {
            throw new KeployException("keploy: the keploy agent answered " + path + " in scope protocol v"
                    + (v instanceof String ? Json.quote((String) v) : String.valueOf(v)) + " (" + quoted(data)
                    + "); this keploy-junit5 speaks scope protocol v" + PROTOCOL_VERSION
                    + ": use a keploy and a keploy-junit5 of the same release", status, "");
        }
        return m;
    }

    /** Claims keploy's run for this test process. */
    Map<String, Object> claim(String key, String dir) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key);
        body.put("dir", dir);
        Map<String, Object> a;
        try {
            a = post(PATH_INSTANCE, body);
        } catch (KeployException e) {
            if (e.status == 404 && e.code.isEmpty()) {
                // keploy speaks scope protocol v1 only under its proven
                // switch. With it off (keploy's default) the agent has no
                // claim route and answers the mux's plain 404, as a keploy
                // from before the protocol does. The claim is this
                // extension's first call, so it is the one that names the
                // switch.
                throw new KeployException("keploy: this keploy agent answered 404 to POST " + PATH_INSTANCE
                        + ", so this JUnit process cannot claim keploy's run: keploy speaks keploy-junit5's wire, scope protocol v1,"
                        + " only when it runs with KEPLOY_PROVEN=1 (KEPLOY_PROVEN=1 keploy mock record|replay -c \"mvn test\");"
                        + " a keploy run without KEPLOY_PROVEN=1, or one from before scope protocol v1, answers 404 there",
                        404, "");
            }
            throw e;
        }
        if (text(a, "instance") == null) {
            throw new KeployException("keploy: the keploy agent answered the claim of this test process without an instance", 0, "");
        }
        return a;
    }

    /** Asks the agent to open a test. */
    Map<String, Object> begin(TestRef t) {
        Map<String, Object> body = t.wire();
        body.put("canSkip", Boolean.TRUE);
        Map<String, Object> a = post(PATH_BEGIN, body);
        if (!"ok".equals(a.get("status"))) {
            throw new KeployException("keploy: the keploy agent answered the begin of " + t.name + " with status "
                    + shown(a.get("status")), 0, "");
        }
        if (!none(a.get("action")) && !ACTION_SKIP.equals(a.get("action"))) {
            throw new KeployException("keploy: the keploy agent answered the begin of " + t.name + " with the unknown action "
                    + shown(a.get("action")), 0, "");
        }
        return a;
    }

    /** Tells the agent the test ended, with its outcome. */
    Map<String, Object> end(TestRef t, String outcome) {
        Map<String, Object> body = t.wire();
        body.put("outcome", outcome);
        Map<String, Object> a = post(PATH_END, body);
        if (!"ok".equals(a.get("status"))) {
            throw new KeployException("keploy: the keploy agent answered the end of " + t.name + " with status "
                    + shown(a.get("status")), 0, "");
        }
        if (!none(a.get("action")) && !ACTION_FAIL.equals(a.get("action"))) {
            throw new KeployException("keploy: the keploy agent answered the end of " + t.name + " with the unknown action "
                    + shown(a.get("action")), 0, "");
        }
        return a;
    }

    /** Whether an answer's action is none: absent, null or "" (anything else is an action). */
    static boolean none(Object action) {
        return action == null || "".equals(action);
    }

    /** A string member of an answer, or null when it is absent, empty or no string. */
    static String text(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v instanceof String && !((String) v).isEmpty() ? (String) v : null;
    }

    private static String shown(Object v) {
        if (v == null) {
            return "\"\"";
        }
        return v instanceof String ? Json.quote((String) v) : String.valueOf(v);
    }

    private static Object parseOrNull(String data) {
        try {
            return Json.parse(data);
        } catch (IllegalArgumentException e) {
            return null; // not the protocol's error body
        }
    }

    private static String readBounded(InputStream in) throws IOException {
        if (in == null) {
            return ""; // an answer with no body
        }
        try (InputStream r = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = r.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > MAX_ANSWER) {
                    throw new IOException("the answer is larger than " + MAX_ANSWER + " bytes");
                }
            }
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
