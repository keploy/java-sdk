package io.keploy.utils;

import java.util.concurrent.CountDownLatch;

public class HaltThread {

    private static CountDownLatch countDownLatch;

    private static HaltThread haltThread;

    private HaltThread() {

    }

    public static HaltThread getInstance() {
        if (haltThread == null) {
            synchronized (HaltThread.class) {  //thread safe.
                if (haltThread == null) {
                    haltThread = new HaltThread();
                    countDownLatch = new CountDownLatch(2);
                }
            }
        }
        return haltThread;
    }

    public static CountDownLatch getCountDownLatch() {
        return countDownLatch;
    }
}