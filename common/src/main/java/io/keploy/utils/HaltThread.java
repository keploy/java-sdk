package io.keploy.utils;

import java.util.concurrent.CountDownLatch;

public class HaltThread {
    private static HaltThread haltThread;
    private static CountDownLatch countDownLatch;
//    private static CountDownLatch haltreq;

    private HaltThread() {

    }

    public static HaltThread getInstance() {
        if (haltThread == null) {
            synchronized (HaltThread.class) {  //thread safe.
                if (haltThread == null) {
                    haltThread = new HaltThread();
                    countDownLatch = new CountDownLatch(2);
//                    haltreq = new CountDownLatch(1);
                }
            }
        }
        return haltThread;
    }

    public static CountDownLatch getCountDownLatch() {
        return countDownLatch;
    }

//    public static CountDownLatch getHaltreq() {
//        return haltreq;
//    }
}