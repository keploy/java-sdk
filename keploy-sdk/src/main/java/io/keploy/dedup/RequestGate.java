package io.keploy.dedup;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * RequestGate counts the requests the app's server is running, so that a
 * test's END can wait until the requests of that test are done before it
 * reads the test's hits.
 *
 * <p>A server writes a request's response before the request is done: a
 * filter's code after the chain, a request log, runs after Keploy has the
 * response and has sent the test's END. Read at once, the END missed those
 * hits, and the next START cleared them as hits between two tests.
 * ServerEntries makes each request of Tomcat, Jetty and Undertow call
 * {@link #enter()} where the server starts running it and {@link #exit(Object)}
 * where it is done with it, the response finished.
 *
 * <p>Public only for the server classes ServerEntries changes, which call it
 * from their own packages; not an API.
 */
public final class RequestGate {

    // The window of the test running, made anew at each START and when END
    // waits: END waits only for the requests that came in since the START,
    // never for one that runs from before (a request of the test before, cut
    // at its bound, or a stream that never ends) or one that comes in after
    // END.
    private static volatile Window current = new Window();

    private RequestGate() {
    }

    /**
     * Counts a request as running, in the window of the test running now.
     *
     * @return the token to pass to {@link #exit(Object)} when it is done
     */
    public static Object enter() {
        Window window = current;
        window.running.incrementAndGet();
        return window;
    }

    /**
     * Counts the request {@link #enter()} returned the token of as done.
     *
     * @param token what {@link #enter()} returned
     */
    public static void exit(Object token) {
        if (token instanceof Window) {
            Window window = (Window) token;
            if (window.running.decrementAndGet() == 0) {
                Thread waiter = window.waiter;
                if (waiter != null) {
                    LockSupport.unpark(waiter);
                }
            }
        }
    }

    // open starts the window of a test: from now on, END waits for the
    // requests that come in, and for no request that came in before.
    static void open() {
        current = new Window();
    }

    // running is how many requests of the current window are running.
    static int running() {
        return current.running.get();
    }

    // await closes the current window and waits until none of its requests
    // runs, for at most timeoutNanos, and says whether none runs. A request
    // that comes in from now on (a readiness probe, a metrics scrape, the
    // next client) counts in a window of its own, so it does not draw the
    // wait out. One thread waits at a time (the worker, under its test lock).
    static boolean await(long timeoutNanos) {
        Window window = current;
        current = new Window();
        if (window.running.get() <= 0) {
            return true;
        }
        // Set before the count is read again: an exit that takes the count
        // to 0 after that read finds the waiter and wakes it, and one that
        // took it to 0 before is seen by the read.
        window.waiter = Thread.currentThread();
        try {
            long deadline = System.nanoTime() + timeoutNanos;
            while (window.running.get() > 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0 || Thread.currentThread().isInterrupted()) {
                    return false;
                }
                LockSupport.parkNanos(RequestGate.class, left);
            }
            return true;
        } finally {
            window.waiter = null;
        }
    }

    private static final class Window {

        private final AtomicInteger running = new AtomicInteger();
        private volatile Thread waiter;
    }
}
