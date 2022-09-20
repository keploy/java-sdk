package io.keploy.utils;

import java.util.concurrent.CountDownLatch;

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