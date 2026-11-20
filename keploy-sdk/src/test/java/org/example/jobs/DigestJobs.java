package org.example.jobs;

import java.util.concurrent.CountDownLatch;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * DigestJobs has a Spring @Scheduled method. Its schedule is never reached
 * (OnDemandScheduler runs it when told); held, a run waits until released.
 */
public class DigestJobs {

    static volatile CountDownLatch entered;
    static volatile CountDownLatch release;

    @Scheduled(fixedDelay = 3600000L)
    public void sendDigest() throws InterruptedException {
        CountDownLatch hold = release;
        if (hold != null) {
            entered.countDown();
            hold.await();
        }
        Work.digest();
    }
}
