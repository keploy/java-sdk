package org.example.jobs;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.quartz.JobBuilder;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.impl.DirectSchedulerFactory;
import org.quartz.simpl.RAMJobStore;
import org.quartz.simpl.SimpleThreadPool;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** Schedulers is JobApp's Quartz scheduler and Spring context, and the commands that fire their jobs. */
final class Schedulers {

    private final Scheduler quartz;
    private final SimpleThreadPool pool;
    private final AnnotationConfigApplicationContext context;
    private final OnDemandScheduler spring;

    private Schedulers(Scheduler quartz, SimpleThreadPool pool, AnnotationConfigApplicationContext context) {
        this.quartz = quartz;
        this.pool = pool;
        this.context = context;
        this.spring = context.getBean(OnDemandScheduler.class);
        if (spring.tasks() != 1) {
            throw new IllegalStateException("Spring scheduled " + spring.tasks() + " tasks, not 1");
        }
    }

    // start starts a Quartz scheduler with one worker and the job
    // reports.nightly (fired only when told), and the Spring context.
    static Schedulers start() throws Exception {
        SimpleThreadPool pool = new SimpleThreadPool(1, Thread.NORM_PRIORITY);
        pool.setThreadNamePrefix("quartz-worker");
        DirectSchedulerFactory.getInstance().createScheduler("jobs", "jobs-1", pool, new RAMJobStore());
        Scheduler quartz = DirectSchedulerFactory.getInstance().getScheduler("jobs");
        quartz.addJob(JobBuilder.newJob(ReportJob.class).withIdentity("nightly", "reports").storeDurably().build(),
                false);
        quartz.start();
        return new Schedulers(quartz, pool, new AnnotationConfigApplicationContext(SpringJobs.class));
    }

    // run runs a command and returns once the job's run it started is over
    // (for spring-hold: once the run waits).
    void run(String command) throws Exception {
        switch (command) {
            case "quartz":
                CountDownLatch ran = new CountDownLatch(1);
                ReportJob.ran = ran;
                quartz.triggerJob(JobKey.jobKey("nightly", "reports"));
                if (!ran.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the Quartz job did not run");
                }
                // The worker is given back only once JobRunShell.run() returned.
                pool.blockForAvailableThreads();
                return;
            case "spring":
                spring.start();
                spring.join();
                return;
            case "spring-hold":
                DigestJobs.entered = new CountDownLatch(1);
                DigestJobs.release = new CountDownLatch(1);
                spring.start();
                if (!DigestJobs.entered.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the @Scheduled method did not start");
                }
                return;
            case "quartz-publish":
                Events.arm(false);
                run("quartz");
                return;
            case "spring-publish-hold":
                Events.arm(true);
                run("spring");
                Events.awaitEntered();
                return;
            case "listener-block":
                Events.block();
                return;
            case "listener-unblock":
                Events.unblock();
                return;
            case "listener-done":
                Events.finish();
                return;
            case "spring-release":
                CountDownLatch release = DigestJobs.release;
                DigestJobs.release = null;
                release.countDown();
                spring.join();
                return;
            default:
                throw new IllegalArgumentException(command);
        }
    }

    void close() throws Exception {
        quartz.shutdown(true);
        context.close();
        Events.close();
    }
}
