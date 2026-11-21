package org.example.jobs;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Events is the app's async event multicaster, as Fineract's: its
 * listener runs on a pool of one thread, after the job that published the
 * event returned. A job run publishes once when armed. The listener runs
 * Work.listen, held until released when the row asks; the pool's thread
 * can be blocked first, so that the listener starts only once let go.
 */
final class Events {

    private static final ThreadPoolTaskExecutor POOL = new ThreadPoolTaskExecutor();
    private static final SimpleApplicationEventMulticaster MULTICASTER = new SimpleApplicationEventMulticaster();

    static {
        POOL.setCorePoolSize(1);
        POOL.setMaxPoolSize(1);
        POOL.setThreadNamePrefix("listener-");
        POOL.initialize();
        MULTICASTER.setTaskExecutor(POOL);
        MULTICASTER.addApplicationListener(new Listener());
    }

    private static volatile boolean armed;
    private static volatile CountDownLatch entered;
    private static volatile CountDownLatch release;
    private static volatile CountDownLatch done;
    private static volatile CountDownLatch unblock;

    private Events() {
    }

    // arm has the next job run publish an event, and its listener wait at
    // its start until released when held.
    static void arm(boolean held) {
        entered = new CountDownLatch(1);
        release = held ? new CountDownLatch(1) : null;
        done = new CountDownLatch(1);
        armed = true;
    }

    // fromJob publishes the event when armed: a job run calls it, then returns.
    static void fromJob() {
        if (armed) {
            armed = false;
            MULTICASTER.multicastEvent(new Ready());
        }
    }

    // block has the pool's thread wait until unblocked, from the app's own
    // thread (neither a job's nor a request's), and returns once it waits.
    static void block() throws InterruptedException {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        unblock = gate;
        POOL.execute(() -> {
            blocked.countDown();
            awaitOrFail(gate);
        });
        awaitOrFail(blocked);
    }

    static void unblock() {
        unblock.countDown();
    }

    static void awaitEntered() {
        awaitOrFail(entered);
    }

    // finish lets a held listener go on, and returns once it is done and
    // the run that carries it is over: the listener counts done down before
    // that run exits, so finish then waits for a no-op that the pool's one
    // thread runs only after it. The no-op goes to the pool's own executor,
    // which the agent does not change, so it is never counted.
    static void finish() {
        CountDownLatch held = release;
        if (held != null) {
            held.countDown();
        }
        awaitOrFail(done);
        CountDownLatch drained = new CountDownLatch(1);
        POOL.getThreadPoolExecutor().execute(drained::countDown);
        awaitOrFail(drained);
    }

    static void close() {
        POOL.shutdown();
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the listener did not get there");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static final class Ready extends ApplicationEvent {

        private static final long serialVersionUID = 1L;

        Ready() {
            super("job");
        }
    }

    private static final class Listener implements ApplicationListener<Ready> {

        @Override
        public void onApplicationEvent(Ready event) {
            entered.countDown();
            CountDownLatch held = release;
            if (held != null) {
                awaitOrFail(held);
            }
            Work.listen();
            done.countDown();
        }
    }
}
