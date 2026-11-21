package org.example.jobs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * PriorityPool is an app's priority pool: a ThreadPoolTaskExecutor whose
 * queue orders the app's own tasks, which are not Comparable, with a
 * Comparator that casts each to their type. {@link #handOff()} holds its
 * one thread busy and hands it two tasks, ranked 2 then 1, so that the
 * queue compares them, and says "ok" once all ran in the queue's order.
 * Armed, a job's run hands them off (ReportJob, DigestJobs).
 */
public final class PriorityPool {

    static volatile boolean armed;

    private static final Pool POOL = new Pool();

    static {
        POOL.setCorePoolSize(1);
        POOL.setMaxPoolSize(1);
        POOL.setDaemon(true);
        POOL.setThreadNamePrefix("app-priority-");
        POOL.initialize();
    }

    private PriorityPool() {
    }

    // fromJob hands the tasks off when armed, and prints what came of it.
    static void fromJob() {
        if (armed) {
            armed = false;
            print("job");
        }
    }

    // print hands the tasks off and prints what came of it, as from.
    static void print(String from) {
        System.out.println("PRIORITY " + from + " " + handOff());
        System.out.flush();
    }

    /**
     * handOff hands the pool its tasks, and returns "ok" once all ran in
     * the queue's order, or what went wrong.
     *
     * @return "ok", or what went wrong
     */
    public static synchronized String handOff() {
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch free = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(3);
        List<Integer> ran = Collections.synchronizedList(new ArrayList<>());
        try {
            POOL.execute(new Task(0, () -> {
                busy.countDown();
                await(free);
            }, ran, done));
            if (!busy.await(30, TimeUnit.SECONDS)) {
                return "the pool's thread did not start";
            }
            POOL.execute(new Task(2, () -> { }, ran, done));
            POOL.execute(new Task(1, () -> { }, ran, done));
            free.countDown();
            if (!done.await(30, TimeUnit.SECONDS)) {
                return "not all ran: " + ran;
            }
            return ran.toString().equals("[0, 1, 2]") ? "ok" : "out of order: " + ran;
        } catch (RuntimeException | InterruptedException e) {
            return "threw " + e;
        } finally {
            free.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Task is the app's task, ranked; it is no Comparable.
    static final class Task implements Runnable {

        final int rank;
        private final Runnable body;
        private final List<Integer> ran;
        private final CountDownLatch done;

        Task(int rank, Runnable body, List<Integer> ran, CountDownLatch done) {
            this.rank = rank;
            this.body = body;
            this.ran = ran;
            this.done = done;
        }

        @Override
        public void run() {
            body.run();
            ran.add(rank);
            done.countDown();
        }
    }

    static final class Pool extends ThreadPoolTaskExecutor {

        private static final long serialVersionUID = 1L;

        @Override
        protected BlockingQueue<Runnable> createQueue(int queueCapacity) {
            return new PriorityBlockingQueue<Runnable>(11, Comparator.comparingInt(task -> ((Task) task).rank));
        }
    }
}
