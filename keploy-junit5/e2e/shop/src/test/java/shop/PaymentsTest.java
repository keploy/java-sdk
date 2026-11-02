package shop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentsTest {
    // A parameterized test: keploy names each invocation by its index,
    // chargesACard(int)[1].
    @ParameterizedTest
    @ValueSource(ints = {4200})
    void chargesACard(int cents) throws Exception {
        assertEquals("captured", Shop.charge(cents));
    }
}
