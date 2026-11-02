package io.keploy.junit5;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import com.sun.net.httpserver.HttpServer;

/** A keploy agent for the rows: it logs each call and answers as told. */
final class FakeAgent implements AutoCloseable {
    /** What the agent saw and the fixtures did, in order. */
    static final List<String> LOG = Collections.synchronizedList(new ArrayList<>());

    static final class Answer {
        final int status;
        final String body;
        final String location;

        Answer(int status, String body) {
            this(status, body, null);
        }

        Answer(int status, String body, String location) {
            this.status = status;
            this.body = body;
            this.location = location;
        }
    }

    /** keploy's answers to a call that went well. */
    static Answer ok(String path, Map<String, Object> body) {
        if (path.equals(AgentClient.PATH_INSTANCE)) {
            return new Answer(200, "{\"v\":1,\"instance\":\"i-1\",\"timeFile\":\"\"}");
        }
        return new Answer(200, "{\"v\":1,\"status\":\"ok\"}");
    }

    final HttpServer server;
    volatile BiFunction<String, Map<String, Object>, Answer> answer = FakeAgent::ok;
    volatile String lastAuth;
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    FakeAgent() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            String body = read(x.getRequestBody());
            lastAuth = x.getRequestHeaders().getFirst("authorization");
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) Json.parse(body);
            String path = x.getRequestURI().getPath();
            String line = x.getRequestMethod() + " " + path + " " + body;
            calls.add(line);
            String what = path.substring(path.lastIndexOf('/') + 1);
            LOG.add(what.equals("instance") ? "claim " + m.get("key") + " " + m.get("dir")
                    : what + " " + m.get("file") + "::" + m.get("name") + (m.containsKey("outcome") ? " " + m.get("outcome") : ""));
            Answer a = answer.apply(path, m);
            byte[] out = a.body.getBytes(StandardCharsets.UTF_8);
            if (a.location != null) {
                x.getResponseHeaders().set("location", a.location);
            }
            x.sendResponseHeaders(a.status, out.length == 0 ? -1 : out.length);
            try (OutputStream o = x.getResponseBody()) {
                o.write(out);
            }
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    AgentClient client() {
        return new AgentClient(url(), "");
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
}
