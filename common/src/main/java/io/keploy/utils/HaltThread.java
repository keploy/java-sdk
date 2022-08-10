package io.keploy.utils;

import java.util.concurrent.CountDownLatch;

public class HaltThread {
    private static volatile HaltThread haltThread;
    private static CountDownLatch countDownLatch;


    private HaltThread() {

    }

    public static void getInstance() {
        if (haltThread == null) {
            synchronized (HaltThread.class) {  //thread safe.
                if (haltThread == null) {
                    haltThread = new HaltThread();
                    countDownLatch = new CountDownLatch(2);
                }
            }
        }
    }

    public static CountDownLatch getCountDownLatch() {
        return countDownLatch;
    }
}