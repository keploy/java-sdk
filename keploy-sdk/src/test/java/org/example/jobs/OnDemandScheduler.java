package org.example.jobs;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/**
 * OnDemandScheduler is the app's TaskScheduler: it keeps each task Spring
 * schedules (each @Scheduled method's ScheduledMethodRunnable) and runs
 * them, on a scheduling thread, when told to. It stands in for Spring's
 * ThreadPoolTaskScheduler so that a run fires when a row says, not on a
 * clock: that pool runs the same ScheduledMethodRunnable, only wrapped in a
 * DelegatingErrorHandlingRunnable, which the SDK leaves as it is.
 */
public class OnDemandScheduler implements TaskScheduler {

    private final List<Runnable> tasks = new CopyOnWriteArrayList<>();
    private volatile Thread running;

    // start runs each task on a thread of its own, and returns at once.
    void start() {
        Thread thread = new Thread(() -> {
            for (Runnable task : tasks) {
                task.run();
            }
        }, "scheduling-1");
        running = thread;
        thread.start();
    }

    // join waits until the tasks start ran are done.
    void join() throws InterruptedException {
        running.join();
    }

    int tasks() {
        return tasks.size();
    }

    private ScheduledFuture<?> keep(Runnable task) {
        tasks.add(task);
        return null;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
        return keep(task);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Date startTime) {
        return keep(task);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Date startTime, long period) {
        return keep(task);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long period) {
        return keep(task);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Date startTime, long delay) {
        return keep(task);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long delay) {
        return keep(task);
    }
}
