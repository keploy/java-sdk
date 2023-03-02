package io.keploy.utils;

import java.util.concurrent.CountDownLatch;

/**
 * While Keploy is running tests in unit test file all the test recorded will be running in separate threads. Threads
 * are needed to be halted at the end as we need to capture the progress at once . HaltThread class is used for halting
 * threads
 */
public class HaltThread {
    private static volatile HaltThread haltThread;
    private final CountDownLatch countDownLatch;

    private HaltThread() {
        countDownLatch = new CountDownLatch(2);
    }

    public static HaltThread getInstance() {
        if (haltThread == null) {
            synchronized (HaltThread.class) {  //thread safe.
                if (haltThread == null) {
                    haltThread = new HaltThread();
                }
            }
        }
        return haltThread;
    }

    public CountDownLatch getCountDownLatch() {
        return countDownLatch;
    }
}