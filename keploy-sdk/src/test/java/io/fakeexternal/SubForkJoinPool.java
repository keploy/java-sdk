package io.fakeexternal;

import java.util.concurrent.ForkJoinPool;

/**
 * Simulated external library dispatching through its own {@link ForkJoinPool}
 * subclass so tests verify unallowlisted library FJP subclasses fail closed.
 */
public final class SubForkJoinPool extends ForkJoinPool {

    private static final SubForkJoinPool INSTANCE = new SubForkJoinPool();

    private SubForkJoinPool() {
        super(2);
    }

    public static void fire(Runnable task) {
        INSTANCE.execute(task);
    }
}
