package io.keploy.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {
    @Test
    void readsEveryKindOfValue() {
        Map<String, Object> want = new LinkedHashMap<>();
        want.put("v", new BigDecimal("1"));
        want.put("s", "a\"\\/\b\f\n\r\té\u00e9");
        want.put("t", true);
        want.put("f", false);
        want.put("n", null);
        want.put("o", Collections.singletonMap("k", Arrays.asList(new BigDecimal("-0.5e+2"), new BigDecimal("2E-1"), new BigDecimal("0"))));
        want.put("e", Collections.emptyMap());
        want.put("a", Collections.emptyList());
        assertEquals(want, Json.parse(" {\"v\" : 1 ,\t\"s\":\"a\\\"\\\\\\/\\b\\f\\n\\r\\té\\u00E9\",\"t\":true,\"f\":false,\"n\":null,"
                + "\"o\":{\"k\":[-0.5e+2, 2E-1 ,0]},\"e\":{ },\"a\":[ ]}\r\n"));
    }

    @Test
    void readsTheDeepestNestingItTakes() {
        String s = "1";
        for (int k = 0; k < Json.MAX_DEPTH; k++) {
            s = "[" + s + "]";
        }
        Json.parse(s);
        String deeper = "[" + s + "]";
        assertEquals("nested deeper than 64 at offset 65", assertThrows(IllegalArgumentException.class, () -> Json.parse(deeper)).getMessage());
    }

    @Test
    void readsEveryDigit() {
        assertEquals(new BigDecimal("1234567890.0987654321e9"), Json.parse("1234567890.0987654321e9"));
        assertEquals(Collections.singletonMap("s", "a b"), Json.parse("{\"s\":\"a b\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "{", "{\"a\"", "{\"a\":", "{\"a\":1", "{\"a\":1,}", "{a:1}", "{a\":1}", "{\"a\" 1}", "[1", "[1,]", "[1 2]",
        "\"abc", "\"a\\", "\"a\\x\"", "\"\\u12\"", "\"\\u12g4\"", "\"\\u+123\"", "\"\\u０１２３\"", "\"a\u0001\"", "\"a\u001f\"", "tru", "fals", "nul", "nulL",
        "01", "+1", "-", ".5", "1.", "1e", "1e+", "--1", "1 2", "{}x", "x"})
    void refusesWhatIsNoJson(String s) {
        assertThrows(IllegalArgumentException.class, () -> Json.parse(s));
    }

    @Test
    void writesAFlatObject() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "a\"b\\c\u0001\u001f ");
        m.put("canSkip", true);
        m.put("n", 2);
        assertEquals("{\"name\":\"a\\\"b\\\\c\\u0001\\u001f \",\"canSkip\":true,\"n\":2}", Json.object(m));
        assertEquals("{}", Json.object(Collections.emptyMap()));
        assertEquals("\"é/\"", Json.quote("é/"));
    }
}
