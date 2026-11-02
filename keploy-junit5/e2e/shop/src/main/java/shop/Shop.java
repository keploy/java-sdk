package shop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The code under test: a shop that prices, reserves and charges through a
 * provider over HTTP (the JDK's own client, keep-alive as the JDK sets it).
 */
public final class Shop {
    private static final String BASE = base();

    private Shop() {
    }

    private static String base() {
        String v = System.getenv("SHOP_PROVIDER");
        return v == null || v.isEmpty() ? "http://127.0.0.1:18751" : v;
    }

    public static int price(String item) throws IOException {
        return Integer.parseInt(field(call("GET", "/price/" + item, null), "cents"));
    }

    public static boolean reserve(String item) throws IOException {
        return Boolean.parseBoolean(field(call("POST", "/reserve", "{\"item\":\"" + item + "\"}"), "ok"));
    }

    public static String charge(int cents) throws IOException {
        return field(call("POST", "/charge", "{\"cents\":" + cents + "}"), "status");
    }

    static String call(String method, String path, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setRequestMethod(method);
        if (body != null) {
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("content-type", "application/json");
            c.setFixedLengthStreamingMode(data.length);
            try (OutputStream o = c.getOutputStream()) {
                o.write(data);
            }
        }
        int status = c.getResponseCode();
        InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
        String text = "";
        if (in != null) {
            try (InputStream i = in) {
                text = new String(i.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        if (status != 200) {
            throw new IOException(method + " " + path + ": HTTP " + status + ": " + text);
        }
        return text;
    }

    // The value of a member of a JSON object: a string's, a number's or a
    // boolean's. Enough for the provider's answers.
    static String field(String json, String key) throws IOException {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(?:\"([^\"]*)\"|([-0-9a-z]+))").matcher(json);
        if (!m.find()) {
            throw new IOException("no " + key + " in " + json);
        }
        return m.group(1) != null ? m.group(1) : m.group(2);
    }
}
