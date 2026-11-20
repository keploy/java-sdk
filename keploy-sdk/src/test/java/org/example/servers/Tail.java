package org.example.servers;

/**
 * Tail is the app code TailFilter runs after the response is sent: the
 * class a test's record must hold when END waits for the request.
 */
public final class Tail {

    private static volatile int runs;

    private Tail() {
    }

    public static void ran() {
        runs++;
    }
}
