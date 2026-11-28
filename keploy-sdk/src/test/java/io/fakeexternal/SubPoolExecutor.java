package io.fakeexternal;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * External library stand-in that dispatches work through its own
 * {@link ThreadPoolExecutor} subclass type so call sites reference the subclass
 * owner rather than a JDK executor interface.
 */
public final class SubPoolExecutor extends ThreadPoolExecutor {
    private static final SubPoolExecutor INSTANCE = new SubPoolExecutor();

    private SubPoolExecutor() {
        super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
    }

    public static void fire(Runnable task) {
        INSTANCE.execute(task);
    }
}
