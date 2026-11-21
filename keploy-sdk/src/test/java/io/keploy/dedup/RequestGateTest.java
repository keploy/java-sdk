package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * RequestGate counts the requests of the test running: END waits for them
 * until the last one is done, or its bound, and never for one that came in
 * before the test's START.
 */
public class RequestGateTest {

    @Before
    public void setUp() {
        RequestGate.open();
    }

    @After
    public void tearDown() {
        // Each row exits a request on the thread that entered it, as a
        // server does: the test thread is left outside every window.
        Runnable task = () -> { };
        assertSame(task, RequestGate.hand(null, task));
    }

    @Test
    public void withNoRequestRunningTheWaitEndsAtOnce() {
        long began = System.nanoTime();
        assertTrue(RequestGate.await(TimeUnit.SECONDS.toNanos(20)));
        assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(10));
    }

    // A wait that never ends fails the row, not the run.
    @Test(timeout = 60000)
    public void aRequestStillRunningAtTheBoundCutsTheWait() {
        Object request = RequestGate.enter();
        try {
            long began = System.nanoTime();
            assertFalse(RequestGate.await(TimeUnit.MILLISECONDS.toNanos(50)));
            assertTrue(System.nanoTime() - began >= TimeUnit.MILLISECONDS.toNanos(50));
            // The END closed the test's window: what comes in now is not its.
            assertEquals(0, RequestGate.running());
        } finally {
            RequestGate.exit(request);
        }
        assertEquals(0, RequestGate.running());
    }

    @Test
    public void aRequestThatComesInWhileEndWaitsIsNotWaitedFor() throws Exception {
        // A readiness probe or a scrape that comes in during the wait is not
        // the test's: the wait ends when the test's own request is done.
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch testsDone = new CountDownLatch(1);
        final CountDownLatch probeDone = new CountDownLatch(1);
        final Thread waiter = Thread.currentThread();
        Thread tests = new Thread(new Runnable() {
            @Override
            public void run() {
                Object request = RequestGate.enter();
                entered.countDown();
                awaitQuietly(testsDone);
                RequestGate.exit(request);
            }
        });
        Thread probe = new Thread(new Runnable() {
            @Override
            public void run() {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (java.util.concurrent.locks.LockSupport.getBlocker(waiter) != RequestGate.class
                        && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                Object request = RequestGate.enter();
                testsDone.countDown();
                awaitQuietly(probeDone);
                RequestGate.exit(request);
            }
        });
        tests.start();
        assertTrue(entered.await(30, TimeUnit.SECONDS));
        probe.start();
        long began = System.nanoTime();
        assertTrue(RequestGate.await(TimeUnit.SECONDS.toNanos(60)));
        assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(30));
        tests.join();
        assertEquals("the probe counts in the window after the END", 1, RequestGate.running());
        probeDone.countDown();
        probe.join();
        assertEquals(0, RequestGate.running());
    }

    @Test
    public void theWaitEndsWhenTheLastRequestIsDoneNotAtItsBound() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        // When the last request began to exit: the wait must end after it.
        final AtomicLong lastExit = new AtomicLong();
        Thread finishing = new Thread(new Runnable() {
            @Override
            public void run() {
                Object first = RequestGate.enter();
                Object second = RequestGate.enter();
                entered.countDown();
                try {
                    Thread.sleep(50);
                    RequestGate.exit(second);
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lastExit.set(System.nanoTime());
                    RequestGate.exit(first);
                }
            }
        });
        finishing.start();
        assertTrue(entered.await(30, TimeUnit.SECONDS));
        long began = System.nanoTime();
        assertTrue(RequestGate.await(TimeUnit.SECONDS.toNanos(60)));
        long returned = System.nanoTime();
        long took = returned - began;
        assertTrue("woken when the count reached 0, not at the bound: " + took,
                took < TimeUnit.SECONDS.toNanos(30));
        long exited = lastExit.get();
        assertTrue("not before the last request was done", exited != 0 && returned - exited >= 0);
        finishing.join();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void aRequestFromBeforeTheTestsStartIsNotWaitedFor() {
        Object before = RequestGate.enter();
        try {
            RequestGate.open();
            assertEquals(0, RequestGate.running());
            assertTrue(RequestGate.await(TimeUnit.SECONDS.toNanos(20)));
            // Done after the START: it does not count in the new window.
            Object during = RequestGate.enter();
            assertEquals(1, RequestGate.running());
            RequestGate.exit(during);
            assertEquals(0, RequestGate.running());
            RequestGate.exit(before);
            before = null;
            assertEquals(0, RequestGate.running());
        } finally {
            RequestGate.exit(before);
        }
    }

    @Test
    public void anInterruptedWaiterStopsWaiting() {
        Object request = RequestGate.enter();
        Thread.currentThread().interrupt();
        try {
            long began = System.nanoTime();
            assertFalse(RequestGate.await(TimeUnit.SECONDS.toNanos(60)));
            assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(30));
        } finally {
            Thread.interrupted();
            RequestGate.exit(request);
        }
    }

    @Test
    public void anExitWithNoTokenOfEnterCountsNothing() {
        Object request = RequestGate.enter();
        RequestGate.exit(null);
        RequestGate.exit("not a token");
        assertEquals(1, RequestGate.running());
        RequestGate.exit(request);
        assertEquals(0, RequestGate.running());
    }

    @Test
    public void manyThreadsCountExactly() throws Exception {
        final AtomicBoolean failed = new AtomicBoolean();
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int n = 0; n < 20000; n++) {
                        Object token = RequestGate.enter();
                        if (RequestGate.running() <= 0) {
                            failed.set(true);
                        }
                        RequestGate.exit(token);
                    }
                }
            });
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertFalse(failed.get());
        assertEquals(0, RequestGate.running());
    }

    @Test
    public void theLogSaysWhyAnEndDoesNotWait() {
        assertTrue(KeployDedupAgent.notCounted(0, Arrays.asList("Netty")).startsWith(
                "Keploy dedup: a test's END does not wait for its requests to finish: KEPLOY_JAVA_END_WAIT_MS is 0."));
        assertTrue(KeployDedupAgent.notCounted(200, Arrays.asList("Netty")).startsWith(
                "Keploy dedup: a test's END does not wait for its requests to finish: the app's server is Netty,"
                        + " whose requests keploy-sdk does not count."));
        assertTrue(KeployDedupAgent.notCounted(200, Collections.<String>emptyList()).startsWith(
                "Keploy dedup: a test's END does not wait for its requests to finish: no Tomcat, Jetty or"
                        + " Undertow request entry loaded after keploy-sdk.jar started"));
    }
}
