package shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OrdersTest {
    // SHOP_CHANGE: the change a replay of e2e/lane.sh makes to the code under
    // test. miss: the price test asks for a pen, which no recorded call
    // answers. unconsumed: the reserve test no longer makes its recorded call.
    static final String CHANGE = System.getenv().getOrDefault("SHOP_CHANGE", "");

    // A @Nested class: keploy names its tests by its binary name,
    // shop.OrdersTest$Books.
    @Nested
    class Books {
        @Test
        void pricesABook() throws Exception {
            if (CHANGE.equals("miss")) {
                assertEquals(150, Shop.price("pen"));
            } else {
                assertEquals(4200, Shop.price("book"));
            }
        }

        @Test
        void reservesABook() throws Exception {
            if (CHANGE.equals("unconsumed")) {
                return;
            }
            assertTrue(Shop.reserve("book"));
        }
    }
}
