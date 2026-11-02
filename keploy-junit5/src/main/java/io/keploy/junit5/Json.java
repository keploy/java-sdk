package io.keploy.junit5;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON of keploy's wire, without a library on the test suite's class
 * path: a strict reader (RFC 8259; anything else is an error) and a writer of
 * flat objects. An object reads as a Map, an array as a List, a number as a
 * BigDecimal, a string as a String, true and false as Boolean, null as null.
 */
final class Json {
    // A deeper answer is no answer of keploy's: bounded so that one cannot
    // overflow the stack.
    static final int MAX_DEPTH = 64;

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** Reads one JSON text; IllegalArgumentException says why it is none. */
    static Object parse(String s) {
        Json j = new Json(s);
        Object v = j.value(0);
        j.space();
        if (j.i != s.length()) {
            throw j.error("text after the value");
        }
        return v;
    }

    /** Writes a flat object of String, Boolean and Number values, in order. */
    static String object(Map<String, ?> m) {
        StringBuilder b = new StringBuilder("{");
        for (Map.Entry<String, ?> e : m.entrySet()) {
            if (b.length() > 1) {
                b.append(',');
            }
            b.append(quote(e.getKey())).append(':');
            Object v = e.getValue();
            b.append(v instanceof String ? quote((String) v) : String.valueOf(v));
        }
        return b.append('}').toString();
    }

    /** s as a JSON string. */
    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c < 0x20) {
                b.append(String.format("\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException(what + " at offset " + i);
    }

    private void space() {
        while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("nested deeper than " + MAX_DEPTH);
        }
        space();
        if (i >= s.length()) {
            throw error("no value");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                return word("true", Boolean.TRUE);
            case 'f':
                return word("false", Boolean.FALSE);
            case 'n':
                return word("null", null);
            default:
                return number();
        }
    }

    private Object word(String w, Object v) {
        if (!s.startsWith(w, i)) {
            throw error("not a value");
        }
        i += w.length();
        return v;
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        space();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            space();
            if (i >= s.length() || s.charAt(i) != '"') {
                throw error("no member name");
            }
            String k = string();
            space();
            expect(':');
            m.put(k, value(depth + 1));
            space();
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
                continue;
            }
            expect('}');
            return m;
        }
    }

    private List<Object> array(int depth) {
        List<Object> l = new ArrayList<>();
        i++;
        space();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            l.add(value(depth + 1));
            space();
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
                continue;
            }
            expect(']');
            return l;
        }
    }

    private void expect(char c) {
        if (i >= s.length() || s.charAt(i) != c) {
            throw error("want '" + c + "'");
        }
        i++;
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) {
                throw error("unterminated string");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c < 0x20) {
                throw error("control character in a string");
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) {
                throw error("unterminated escape");
            }
            char e = s.charAt(i++);
            int at = "\"\\/bfnrt".indexOf(e);
            if (at >= 0) {
                b.append("\"\\/\b\f\n\r\t".charAt(at));
            } else if (e == 'u') {
                int u = 0;
                for (int k = 0; k < 4; k++) {
                    // ASCII hex digits only (Character.digit takes any
                    // script's digits).
                    int d = i < s.length() ? "0123456789abcdef".indexOf(Character.toLowerCase(s.charAt(i++))) : -1;
                    if (d < 0) {
                        throw error("bad \\u escape");
                    }
                    u = u * 16 + d;
                }
                b.append((char) u);
            } else {
                throw error("bad escape");
            }
        }
    }

    private BigDecimal number() {
        int start = i;
        if (i < s.length() && s.charAt(i) == '-') {
            i++;
        }
        // JSON's grammar: no leading zero, no leading '+', no bare '.'.
        if (i < s.length() && s.charAt(i) == '0') {
            i++;
        } else if (!digits()) {
            throw error("not a value");
        }
        if (i < s.length() && s.charAt(i) == '.') {
            i++;
            if (!digits()) {
                throw error("no digit after '.'");
            }
        }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            if (!digits()) {
                throw error("no digit in the exponent");
            }
        }
        return new BigDecimal(s.substring(start, i));
    }

    private boolean digits() {
        int start = i;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        return i > start;
    }
}
