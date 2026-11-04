package sample.shop;

/**
 * A sample class no test case runs: its lines and branches still count in the
 * totals, and it counts as a class, as with JaCoCo's own analysis.
 */
public final class Unused {

    private Unused() {
    }

    public static int tier(int n) {
        if (n > 10) {
            return 2;
        }
        return n < 0 ? -1 : 1;
    }
}
