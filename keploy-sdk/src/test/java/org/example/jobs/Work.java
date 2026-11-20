package org.example.jobs;

/** Work is the app's own code each job runs, so that its runs have hits. */
public final class Work {

    private static volatile long done;

    private Work() {
    }

    public static void report() {
        done++;
    }

    public static void digest() {
        done += 2;
    }
}
