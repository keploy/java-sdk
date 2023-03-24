package io.keploy.advice.ksql;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map.Entry;

public class CoverageLogger {
    private static final long LOG_INTERVAL_MS = 10000; // Adjust this value based on how often you want to log the data (e.g., every 10 seconds)

    public static void startLogging() {
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                logCoverageData();
            }
        }, 0, LOG_INTERVAL_MS);
    }

    private static void logCoverageData() {
        ConcurrentHashMap<String, AtomicInteger> lineCoverage = LineCoverageClassVisitor.LineCoverageMethodVisitor.getLineCoverage();

        System.out.println("Line coverage information:");
        for (Entry<String, AtomicInteger> entry : lineCoverage.entrySet()) {
            System.out.println(entry.getKey() + " - Executed: " + entry.getValue().get() + " times");
        }
    }
}
