package org.example.servers;

/**
 * AsyncTail is the app code AsyncWork's tasks run after their sleep: the
 * class a test's record must hold when END waits for the work its request
 * handed to Spring.
 */
public final class AsyncTail {

    private static volatile int runs;

    private AsyncTail() {
    }

    public static void ran() {
        runs++;
    }
}
