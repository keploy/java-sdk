package sample.shop;

import java.util.ArrayList;
import java.util.List;

/**
 * A small sample app for the coverage rows: branches, a string switch, a loop,
 * a lambda, a try/catch and a nested class, so the line and branch counters
 * have partly covered lines to get right.
 */
public final class Shop {

    private Shop() {
    }

    public static int price(int quantity, String tier) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        int unit;
        switch (tier) {
            case "gold":
                unit = 7;
                break;
            case "silver":
                unit = 9;
                break;
            default:
                unit = 10;
        }
        Basket basket = new Basket();
        for (int i = 0; i < quantity; i++) {
            basket.add(unit);
        }
        int total = basket.total();
        return quantity >= 10 && !"gold".equals(tier) ? total - total / 10 : total;
    }

    public static int parse(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static final class Basket {
        private final List<Integer> items = new ArrayList<>();

        void add(int price) {
            items.add(price);
        }

        int total() {
            return items.stream().mapToInt(p -> p > 0 ? p : 0).sum();
        }
    }
}
