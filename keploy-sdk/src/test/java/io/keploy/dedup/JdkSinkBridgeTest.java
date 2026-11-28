package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.keploy.dedup.bootstrap.KeploySinkBridge;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Unit tests for {@link KeploySinkBridge} re-entrancy / agent-internal guards
 * and {@link SinkAllowlist} exact-class rules.
 */
public class JdkSinkBridgeTest {

    @Test
    public void reentrancyGuardPreventsNestedSinkCallbackRecursion() {
        final KeploySinkBridge.Handler previous = KeploySinkBridge.handler;
        final AtomicInteger threadCalls = new AtomicInteger();
        final AtomicInteger tpeCalls = new AtomicInteger();
        final AtomicInteger stpeCalls = new AtomicInteger();
        final AtomicInteger fjpCalls = new AtomicInteger();
        final AtomicInteger timerCalls = new AtomicInteger();
        final AtomicInteger vtCalls = new AtomicInteger();
        try {
            KeploySinkBridge.handler = new KeploySinkBridge.Handler() {
                @Override
                public void onThreadStart(Thread thread) {
                    int n = threadCalls.incrementAndGet();
                    assertTrue("re-entrancy depth must be active inside callback", KeploySinkBridge.isInternal());
                    // Recursive calls back into every bridge method MUST be suppressed.
                    KeploySinkBridge.onThreadStart(thread);
                    KeploySinkBridge.onVirtualThreadStart(thread);
                    KeploySinkBridge.onTpeExecute(this, thread);
                    KeploySinkBridge.onStpeDelayedExecute(this, thread);
                    KeploySinkBridge.onFjpExternalSubmit(this, thread);
                    KeploySinkBridge.onTimerSched(this, thread);
                    assertEquals("must not recurse", 1, n);
                }

                @Override
                public void onVirtualThreadStart(Thread thread) {
                    vtCalls.incrementAndGet();
                    KeploySinkBridge.onVirtualThreadStart(thread);
                }

                @Override
                public void onTpeExecute(Object executor, Object command) {
                    tpeCalls.incrementAndGet();
                    KeploySinkBridge.onTpeExecute(executor, command);
                }

                @Override
                public void onStpeDelayedExecute(Object executor, Object task) {
                    stpeCalls.incrementAndGet();
                    KeploySinkBridge.onStpeDelayedExecute(executor, task);
                }

                @Override
                public void onFjpExternalSubmit(Object pool, Object task) {
                    fjpCalls.incrementAndGet();
                    KeploySinkBridge.onFjpExternalSubmit(pool, task);
                }

                @Override
                public void onTimerSched(Object timer, Object task) {
                    timerCalls.incrementAndGet();
                    KeploySinkBridge.onTimerSched(timer, task);
                }
            };

            assertFalse(KeploySinkBridge.isInternal());
            KeploySinkBridge.onThreadStart(Thread.currentThread());
            assertFalse(KeploySinkBridge.isInternal());
            assertEquals(1, threadCalls.get());
            assertEquals(0, vtCalls.get());
            assertEquals(0, tpeCalls.get());
            assertEquals(0, stpeCalls.get());
            assertEquals(0, fjpCalls.get());
            assertEquals(0, timerCalls.get());

            KeploySinkBridge.onVirtualThreadStart(Thread.currentThread());
            KeploySinkBridge.onTpeExecute(new Object(), new Object());
            KeploySinkBridge.onStpeDelayedExecute(new Object(), new Object());
            KeploySinkBridge.onFjpExternalSubmit(new Object(), new Object());
            KeploySinkBridge.onTimerSched(new Object(), new Object());
            assertEquals(1, vtCalls.get());
            assertEquals(1, tpeCalls.get());
            assertEquals(1, stpeCalls.get());
            assertEquals(1, fjpCalls.get());
            assertEquals(1, timerCalls.get());
        } finally {
            KeploySinkBridge.handler = previous;
            assertFalse(KeploySinkBridge.isInternal());
        }
    }

    @Test
    public void agentInternalScopeAndPoolsNeverTriggerSinkHandlerOrLatch() {
        final KeploySinkBridge.Handler previous = KeploySinkBridge.handler;
        final AtomicInteger calls = new AtomicInteger();
        ExecutorService agentPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "keploy-test-agent-pool");
            t.setDaemon(true);
            return t;
        });
        try {
            KeploySinkBridge.handler = new KeploySinkBridge.Handler() {
                @Override
                public void onThreadStart(Thread thread) {
                    calls.incrementAndGet();
                }

                @Override
                public void onVirtualThreadStart(Thread thread) {
                    calls.incrementAndGet();
                }

                @Override
                public void onTpeExecute(Object executor, Object command) {
                    calls.incrementAndGet();
                }

                @Override
                public void onStpeDelayedExecute(Object executor, Object task) {
                    calls.incrementAndGet();
                }

                @Override
                public void onFjpExternalSubmit(Object pool, Object task) {
                    calls.incrementAndGet();
                }

                @Override
                public void onTimerSched(Object timer, Object task) {
                    calls.incrementAndGet();
                }
            };

            // Nested enterInternal/exitInternal suppresses all sink notifications.
            KeploySinkBridge.enterInternal();
            try {
                KeploySinkBridge.enterInternal();
                try {
                    KeploySinkBridge.onThreadStart(Thread.currentThread());
                    KeploySinkBridge.onTpeExecute(agentPool, (Runnable) () -> { });
                    KeploySinkBridge.onStpeDelayedExecute(agentPool, new Object());
                    KeploySinkBridge.onFjpExternalSubmit(ForkJoinPool.commonPool(), new Object());
                    KeploySinkBridge.onTimerSched(new Object(), new Object());
                } finally {
                    KeploySinkBridge.exitInternal();
                }
                assertTrue("outer internal scope must still be active", KeploySinkBridge.isInternal());
                KeploySinkBridge.onThreadStart(Thread.currentThread());
            } finally {
                KeploySinkBridge.exitInternal();
            }
            assertFalse("internal depth must clear after matching exits", KeploySinkBridge.isInternal());
            assertEquals("agent-internal scope must never invoke handler", 0, calls.get());

            // Registered agent-internal pool is explicitly allowlisted at TPE/STPE sinks.
            assertFalse("unregistered plain pool must NOT be allowlisted",
                    SinkAllowlist.isAllowlistedTpeExecute(agentPool, (Runnable) () -> { }, new String[1]));
            RequestGate.registerAgentInternalPool(agentPool);
            assertTrue("registered agent-internal pool must be allowlisted at TPE sink",
                    SinkAllowlist.isAllowlistedTpeExecute(agentPool, (Runnable) () -> { }, new String[1]));
            assertTrue("registered agent-internal pool must be allowlisted at STPE sink",
                    SinkAllowlist.isAllowlistedStpeDelayedExecute(agentPool, new Object(), new String[1]));
        } finally {
            KeploySinkBridge.handler = previous;
            agentPool.shutdownNow();
        }
    }

    @Test
    public void userCallbackPoolsAndUntrackedCommonPoolAreNeverAllowlisted() {
        ExecutorService plain = Executors.newSingleThreadExecutor();
        try {
            String[] caller = new String[1];
            assertFalse("plain ThreadPoolExecutor without RequestGate.hand must NOT be allowlisted",
                    SinkAllowlist.isAllowlistedTpeExecute(plain, (Runnable) () -> { }, caller));
            assertFalse("ForkJoinPool.commonPool without RequestGate.hand must NOT be allowlisted",
                    SinkAllowlist.isAllowlistedFjpSubmit(ForkJoinPool.commonPool(), (Runnable) () -> { }, caller));
            assertFalse("plain Thread.start from test class must NOT be allowlisted",
                    SinkAllowlist.isAllowlistedThreadStart(new Thread(() -> { }), caller));
            assertFalse("plain Timer.sched from test class must NOT be allowlisted",
                    SinkAllowlist.isAllowlistedTimerSched(new Object(), new Object(), caller));

            // Inside tracked dispatch, sinks are allowed.
            RequestGate.enterTrackedDispatch();
            try {
                assertTrue(SinkAllowlist.isAllowlistedTpeExecute(plain, (Runnable) () -> { }, caller));
                assertTrue(SinkAllowlist.isAllowlistedFjpSubmit(ForkJoinPool.commonPool(), (Runnable) () -> { }, caller));
                assertTrue(SinkAllowlist.isAllowlistedThreadStart(new Thread(() -> { }), caller));
            } finally {
                RequestGate.exitTrackedDispatch();
            }
        } finally {
            plain.shutdownNow();
        }
    }

    @Test
    public void infraAllowlistContainsOnlyExactCitedZeroCallbackClassesAndExcludesUserCallbackPools() {
        // Every PetClinic-measured zero-callback infra class has an explicit positive row.
        String[] expectedThreadCallers = {
            "com.zaxxer.hikari.pool.HikariPool",
            "com.mysql.cj.jdbc.AbandonedConnectionCleanupThread",
            "org.h2.util.MathUtils",
            "org.springframework.boot.autoconfigure.condition.OnClassCondition$ThreadedOutcomesResolver",
            "org.springframework.boot.autoconfigure.preinitialize.BackgroundPreinitializingApplicationListener",
            "org.springframework.boot.tomcat.TomcatWebServer",
            "org.springframework.boot.tomcat.GracefulShutdown",
            "org.springframework.boot.web.embedded.tomcat.TomcatWebServer",
            "org.springframework.boot.web.embedded.tomcat.GracefulShutdown",
        };
        assertEquals("exact count of INFRA_THREAD_CALLERS must match reviewed rows",
                expectedThreadCallers.length, SinkAllowlist.INFRA_THREAD_CALLERS.size());
        for (String cls : expectedThreadCallers) {
            assertTrue("expected cited infra thread caller: " + cls,
                    SinkAllowlist.INFRA_THREAD_CALLERS.contains(cls));
            assertFalse("no package prefix wildcards allowed: " + cls, cls.endsWith(".") || cls.endsWith("*"));
        }

        String[] expectedPoolCallers = {
            "com.zaxxer.hikari.pool.HikariPool",
            "com.mysql.cj.jdbc.AbandonedConnectionCleanupThread",
        };
        assertEquals("exact count of INFRA_POOL_CALLERS must match reviewed rows",
                expectedPoolCallers.length, SinkAllowlist.INFRA_POOL_CALLERS.size());
        for (String cls : expectedPoolCallers) {
            assertTrue("expected cited infra pool caller: " + cls,
                    SinkAllowlist.INFRA_POOL_CALLERS.contains(cls));
        }

        assertEquals(1, SinkAllowlist.INFRA_TASK_CLASSES.size());
        assertTrue(SinkAllowlist.INFRA_TASK_CLASSES.contains("com.mysql.cj.jdbc.AbandonedConnectionCleanupThread"));

        // Anything that can run user Runnables, lambdas, or listeners is NEVER allowlisted.
        String[] forbidden = {
            "java.util.concurrent.ForkJoinPool",
            "java.util.concurrent.CompletableFuture",
            "org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor",
            "org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler",
            "org.springframework.core.task.SimpleAsyncTaskExecutor",
            "reactor.core.scheduler.Schedulers",
            "io.netty.util.concurrent.SingleThreadEventExecutor",
            "com.google.common.eventbus.AsyncEventBus",
            "kotlinx.coroutines.Dispatchers",
        };
        for (String cls : forbidden) {
            assertFalse("user-callback class must NEVER be in INFRA_THREAD_CALLERS: " + cls,
                    SinkAllowlist.INFRA_THREAD_CALLERS.contains(cls));
            assertFalse("user-callback class must NEVER be in INFRA_POOL_CALLERS: " + cls,
                    SinkAllowlist.INFRA_POOL_CALLERS.contains(cls));
            assertFalse("user-callback class must NEVER be in INFRA_TASK_CLASSES: " + cls,
                    SinkAllowlist.INFRA_TASK_CLASSES.contains(cls));
        }
    }

    @Test
    public void unarmedHandOffOutsideWindowLatchesAsyncExecutorsFalse() {
        boolean savedLatch = KeployDedupAgent.observedPreCapturedUntrackedExecutor.get();
        KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
        RequestGate.disarm();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            assertFalse("fresh state must not be latched", KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());
            RequestGate.hand(pool, (Runnable) () -> { });
            assertTrue("unarmed RequestGate.hand(pool, task) must latch asyncExecutors=false",
                    KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());
        } finally {
            pool.shutdownNow();
            KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(savedLatch);
        }
    }
}
