package io.keploy.junit5;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/** The suites the rows run through JUnit (EngineTestKit). Static nested: Surefire runs none of them itself. */
final class Fixtures {
    static void log(String s) {
        FakeAgent.LOG.add(s);
    }

    static String method(TestInfo i) {
        return i.getTestMethod().get().getName();
    }

    /** Another extension, which keploy's must wrap. */
    static final class Other implements BeforeEachCallback, AfterEachCallback {
        @Override
        public void beforeEach(ExtensionContext ctx) {
            log("other.beforeEach");
        }

        @Override
        public void afterEach(ExtensionContext ctx) {
            log("other.afterEach");
        }
    }

    @ExtendWith(Other.class)
    @TestMethodOrder(MethodOrderer.MethodName.class)
    static class Orders {
        @BeforeAll
        static void beforeAll() {
            log("@BeforeAll");
        }

        @BeforeEach
        void beforeEach(TestInfo i) {
            log("@BeforeEach " + method(i));
        }

        @Test
        void assumes() {
            log("assumes");
            assumeTrue(false, "not here");
        }

        @Test
        void fails() {
            log("fails");
            fail("boom");
        }

        @Test
        void passes() {
            log("passes");
        }

        @AfterEach
        void afterEach(TestInfo i) {
            log("@AfterEach " + method(i));
        }

        @AfterAll
        static void afterAll() {
            log("@AfterAll");
        }
    }

    static class Payments {
        @Test
        void charges() {
            log("charges");
        }
    }

    static class AfterEachFails {
        @Test
        void cleans() {
            log("cleans");
        }

        @AfterEach
        void afterEach() {
            throw new IllegalStateException("cleanup broke");
        }
    }

    @TestMethodOrder(MethodOrderer.MethodName.class)
    static class Named {
        @ParameterizedTest
        @ValueSource(ints = {1, 2})
        void price(int n) {
        }

        @RepeatedTest(2)
        void repeats() {
        }

        @TestFactory
        List<DynamicTest> dynamic() {
            return Collections.singletonList(DynamicTest.dynamicTest("d", () -> log("dynamic ran")));
        }

        @Nested
        class Inner {
            @Test
            void inner() {
            }
        }
    }

    /** Named, run through a JUnit Platform suite. */
    @Suite
    @SelectClasses(Named.class)
    static class NamedSuite {
    }

    /** Two suites that run one class: each test of it runs twice in one JVM. */
    @Suite
    @SelectClasses(Payments.class)
    static class PaymentsSuite {
    }

    @Suite
    @SelectClasses(Payments.class)
    static class PaymentsSuiteAgain {
    }

    @ExtendWith(KeployExtension.class)
    static class Extended {
        @Test
        void extended() {
            log("extended");
        }
    }
}
