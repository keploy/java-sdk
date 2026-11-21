package org.example.jobs;

import java.util.concurrent.CountDownLatch;
import org.quartz.Job;
import org.quartz.JobExecutionContext;

/** ReportJob is a Quartz job: it runs Work and says it ran. */
public class ReportJob implements Job {

    static volatile CountDownLatch ran = new CountDownLatch(1);

    @Override
    public void execute(JobExecutionContext context) {
        Work.report();
        Events.fromJob();
        ran.countDown();
    }
}
