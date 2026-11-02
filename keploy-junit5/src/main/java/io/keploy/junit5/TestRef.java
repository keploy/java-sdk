package io.keploy.junit5;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.platform.engine.UniqueId;

/**
 * A test's name on keploy's wire: keploy builds its ID as file::name. file is
 * the test's class (its binary name, Outer$Inner for a @Nested class); name
 * is JUnit's own name of the method within it, from the test's unique ID:
 * {@code orders()}, {@code price(java.lang.String)}, and a template's
 * invocation index after it, {@code price(java.lang.String)[2]}.
 */
final class TestRef {
    final String file;
    final String name;

    TestRef(String file, String name) {
        this.file = file;
        this.name = name;
    }

    String id() {
        return file + "::" + name;
    }

    Map<String, Object> wire() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("file", file);
        return m;
    }

    static TestRef of(String testClass, String uniqueId) {
        StringBuilder name = new StringBuilder();
        for (UniqueId.Segment s : UniqueId.parse(uniqueId).getSegments()) {
            switch (s.getType()) {
                case "engine":
                case "class":
                case "nested-class":
                    break;
                case "method":
                case "test-template":
                    name.append(s.getValue());
                    break;
                case "test-template-invocation":
                    name.append('[').append(s.getValue().replace("#", "")).append(']');
                    break;
                default:
                    throw new KeployException("keploy: keploy-junit5 begins and ends each test at JUnit's BeforeEach and AfterEach"
                            + " callbacks, and cannot name " + uniqueId + " as one test (a " + s.getType() + "; a @TestFactory's"
                            + " dynamic tests, for one, all run inside one of them): write it as a @Test or a @ParameterizedTest,"
                            + " or run it without keploy");
            }
        }
        return new TestRef(testClass, name.toString());
    }
}
