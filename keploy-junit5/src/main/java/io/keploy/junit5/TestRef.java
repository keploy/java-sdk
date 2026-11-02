package io.keploy.junit5;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.platform.engine.UniqueId;

/**
 * A test's name on keploy's wire: keploy builds its ID as file::name. file is
 * the test's class (its binary name, Outer$Inner for a @Nested class); name
 * is JUnit's own name of the method within it, from the test's unique ID:
 * {@code orders()}, {@code price(java.lang.String)}, and a template's
 * invocation index after it, {@code price(java.lang.String)[2]}. A test of a
 * class template (a @ParameterizedClass, JUnit 5.13 and later) has the class's
 * invocation index before it, {@code [2]price(java.lang.String)}, one per
 * class template it runs in, outermost first: each invocation of the class
 * runs each of its tests in a BeforeEach/AfterEach pair of its own. A test run
 * through a JUnit Platform @Suite is named as when it runs directly: the
 * suite and its engine are not the test.
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
                case "suite":
                case "class":
                case "nested-class":
                case "class-template":
                case "nested-class-template":
                    break;
                case "method":
                case "test-template":
                    name.append(s.getValue());
                    break;
                case "test-template-invocation":
                case "class-template-invocation":
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
