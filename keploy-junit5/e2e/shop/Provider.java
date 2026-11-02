// The provider the shop calls: up while keploy records, stopped while it
// replays. Prints "ready" once it listens.
//
//   java Provider.java PORT
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Provider {
    static final Map<String, Integer> PRICES = Map.of("book", 4200, "pen", 150);

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), 0);
        server.createContext("/", Provider::handle);
        server.start();
        System.out.println("ready");
        System.out.flush();
    }

    static void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String out = null;
        if (method.equals("GET") && path.startsWith("/price/")) {
            out = "{\"cents\":" + PRICES.getOrDefault(path.substring("/price/".length()), 0) + "}";
        } else if (method.equals("POST") && path.equals("/reserve")) {
            out = "{\"ok\":" + PRICES.containsKey(field(body, "item")) + "}";
        } else if (method.equals("POST") && path.equals("/charge")) {
            String cents = field(body, "cents");
            out = "{\"status\":\"" + (cents != null && Integer.parseInt(cents) > 0 ? "captured" : "declined") + "\"}";
        }
        byte[] data = (out == null ? "no route" : out).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("content-type", out == null ? "text/plain" : "application/json");
        ex.sendResponseHeaders(out == null ? 404 : 200, data.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(data);
        }
    }

    // The value of a member of a JSON object, a string or an integer: enough
    // for the shop's bodies.
    static String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(?:\"([^\"]*)\"|(-?[0-9]+))").matcher(json);
        if (!m.find()) {
            return null;
        }
        return m.group(1) != null ? m.group(1) : m.group(2);
    }
}
