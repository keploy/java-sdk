package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.keploy.dedup.bootstrap.KeploySinkBridge;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

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
        ExecutorService plain = Executors.newSingleThreadExecutor();
        Thread plainThread = new Thread(() -> { });
        Runnable plainTask = () -> { };
        String[] callerOut = new String[1];
        try {
            // Behaviour rows: every PetClinic-measured zero-callback infra caller
            // passes the real SinkAllowlist.isAllowlisted* behaviour predicates.
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
                assertFalse("no package prefix wildcards allowed: " + cls, cls.endsWith(".") || cls.endsWith("*"));
                assertTrue("behaviour: cited infra thread caller must be allowed: " + cls,
                        SinkAllowlist.isAllowlistedThreadStart(plainThread, cls, callerOut));
                assertEquals(cls, callerOut[0]);
            }

            String[] expectedPoolCallers = {
                "com.zaxxer.hikari.pool.HikariPool",
                "com.mysql.cj.jdbc.AbandonedConnectionCleanupThread",
            };
            assertEquals("exact count of INFRA_POOL_CALLERS must match reviewed rows",
                    expectedPoolCallers.length, SinkAllowlist.INFRA_POOL_CALLERS.size());
            for (String cls : expectedPoolCallers) {
                assertTrue("behaviour: cited infra pool caller must be allowed at TPE: " + cls,
                        SinkAllowlist.isAllowlistedTpeExecute(plain, plainTask, cls, callerOut));
                assertTrue("behaviour: cited infra pool caller must be allowed at STPE: " + cls,
                        SinkAllowlist.isAllowlistedStpeDelayedExecute(plain, plainTask, cls, callerOut));
                assertTrue("behaviour: cited infra pool caller must be allowed at Timer: " + cls,
                        SinkAllowlist.isAllowlistedTimerSched(new Object(), plainTask, cls, callerOut));
            }

            assertEquals(1, SinkAllowlist.INFRA_TASK_CLASSES.size());
            assertTrue(SinkAllowlist.INFRA_TASK_CLASSES.contains("com.mysql.cj.jdbc.AbandonedConnectionCleanupThread"));

            // Anything that can run user Runnables, lambdas, CompletionHandlers, or
            // listeners — including previously removed entries — MUST return false
            // from every SinkAllowlist behaviour predicate!
            String[] forbidden = {
                "java.util.concurrent.ForkJoinPool",
                "java.util.concurrent.CompletableFuture",
                "org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor",
                "org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler",
                "org.springframework.core.task.SimpleAsyncTaskExecutor",
                "org.springframework.scheduling.support.DelegatingErrorHandlingRunnable",
                "org.springframework.scheduling.support.ReschedulingRunnable",
                "org.xnio.XnioWorker$DefaultThreadPoolExecutor",
                "org.jboss.threads.ManagedThreadPoolExecutor",
                "org.jboss.threads.JBossThread",
                "sun.nio.ch.AsynchronousChannelGroupImpl",
                "reactor.core.scheduler.Schedulers",
                "io.netty.util.concurrent.SingleThreadEventExecutor",
                "com.google.common.eventbus.AsyncEventBus",
                "kotlinx.coroutines.Dispatchers",
                "java.lang.reflect.Method",
            };
            for (String cls : forbidden) {
                assertFalse("behaviour: forbidden class must be rejected at Thread.start: " + cls,
                        SinkAllowlist.isAllowlistedThreadStart(plainThread, cls, callerOut));
                assertFalse("behaviour: forbidden class must be rejected at VirtualThread.start: " + cls,
                        SinkAllowlist.isAllowlistedVirtualThreadStart(plainThread, cls, callerOut));
                assertFalse("behaviour: forbidden class must be rejected at TPE.execute: " + cls,
                        SinkAllowlist.isAllowlistedTpeExecute(plain, plainTask, cls, callerOut));
                assertFalse("behaviour: forbidden class must be rejected at STPE.delayedExecute: " + cls,
                        SinkAllowlist.isAllowlistedStpeDelayedExecute(plain, plainTask, cls, callerOut));
                assertFalse("behaviour: forbidden class must be rejected at FJP.submit: " + cls,
                        SinkAllowlist.isAllowlistedFjpSubmit(ForkJoinPool.commonPool(), plainTask, cls, callerOut));
                assertFalse("behaviour: forbidden class must be rejected at Timer.sched: " + cls,
                        SinkAllowlist.isAllowlistedTimerSched(new Object(), plainTask, cls, callerOut));
                assertFalse("forbidden class must NOT be in SERVER_POOL_CLASSES: " + cls,
                        SinkAllowlist.SERVER_POOL_CLASSES.contains(cls));
                assertFalse("forbidden class must NOT be in SERVER_TASK_CLASSES: " + cls,
                        SinkAllowlist.SERVER_TASK_CLASSES.contains(cls));
                assertFalse("forbidden class must NOT be in SERVER_WORKER_THREAD_CLASSES: " + cls,
                        SinkAllowlist.SERVER_WORKER_THREAD_CLASSES.contains(cls));
                assertFalse("forbidden class must NOT be in JVM_INTERNAL_CALLER_CLASSES: " + cls,
                        SinkAllowlist.JVM_INTERNAL_CALLER_CLASSES.contains(cls));
            }
        } finally {
            plain.shutdownNow();
        }
    }

    @Test
    public void stackWalkerIsUsedOnJdk9PlusAndSurfacesDirectAndReflectiveCallers() throws Exception {
        String spec = System.getProperty("java.specification.version");
        boolean jdk9Plus = !spec.startsWith("1.");
        assertEquals("StackWalker must be active on JDK 9+ and fall back cleanly on JDK 8",
                jdk9Plus, SinkAllowlist.usesStackWalker());

        String directCaller = SinkAllowlist.firstExternalCaller();
        assertEquals("direct caller must resolve to this test class",
                JdkSinkBridgeTest.class.getName(), directCaller);

        Method m = SinkAllowlist.class.getDeclaredMethod("firstExternalCaller");
        m.setAccessible(true);
        String reflectiveCaller = (String) m.invoke(null);
        assertNotNull("reflective caller must not be null", reflectiveCaller);
        assertFalse("reflective caller must never be allowlisted: " + reflectiveCaller,
                SinkAllowlist.isAllowlistedThreadStartCaller(reflectiveCaller));
    }

    public static boolean isTrackedDispatchActiveForTest() {
        return RequestGate.isTrackedDispatchActive();
    }

    @Test
    public void asyncContextInterfaceHookWrapsJavaxJakartaAndTransitiveSubclasses() throws Exception {
        final ServerEntries transformer = new ServerEntries(true);
        final byte[] jakartaIface = generateMarkerInterface("jakarta/servlet/AsyncContext");
        final byte[] javaxImpl = generateAsyncContextImpl(
                "org/example/synthetic/JavaxAsyncCtxImpl", "java/lang/Object",
                new String[] {"javax/servlet/AsyncContext"});
        final byte[] jakartaBase = generateAsyncContextBase(
                "org/example/synthetic/JakartaBaseAsyncCtx", "java/lang/Object",
                new String[] {"jakarta/servlet/AsyncContext"});
        final byte[] jakartaSub = generateAsyncContextImpl(
                "org/example/synthetic/JakartaSubAsyncCtx", "org/example/synthetic/JakartaBaseAsyncCtx",
                null);

        final byte[][] transformedJavax = {null};
        final byte[][] transformedJakartaSub = {null};
        java.util.Set<String> savedUnhooked = ServerEntries.snapshotUnhookedAsyncDispatchers();
        ClassLoader cl = new ClassLoader(JdkSinkBridgeTest.class.getClassLoader()) {
            @Override
            public java.io.InputStream getResourceAsStream(String name) {
                if ("jakarta/servlet/AsyncContext.class".equals(name)) {
                    return new java.io.ByteArrayInputStream(jakartaIface);
                }
                if ("org/example/synthetic/JakartaBaseAsyncCtx.class".equals(name)) {
                    return new java.io.ByteArrayInputStream(jakartaBase);
                }
                return super.getResourceAsStream(name);
            }

            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                String internal = name.replace('.', '/');
                byte[] raw;
                if ("jakarta/servlet/AsyncContext".equals(internal)) {
                    raw = jakartaIface;
                } else if ("org/example/synthetic/JavaxAsyncCtxImpl".equals(internal)) {
                    raw = javaxImpl;
                } else if ("org/example/synthetic/JakartaBaseAsyncCtx".equals(internal)) {
                    raw = jakartaBase;
                } else if ("org/example/synthetic/JakartaSubAsyncCtx".equals(internal)) {
                    raw = jakartaSub;
                } else {
                    throw new ClassNotFoundException(name);
                }
                byte[] out = transformer.transform(this, internal, null,
                        JdkSinkBridgeTest.class.getProtectionDomain(), raw);
                if ("org/example/synthetic/JavaxAsyncCtxImpl".equals(internal)) {
                    transformedJavax[0] = out;
                } else if ("org/example/synthetic/JakartaSubAsyncCtx".equals(internal)) {
                    transformedJakartaSub[0] = out;
                }
                byte[] effective = out != null ? out : raw;
                return defineClass(name, effective, 0, effective.length);
            }
        };

        Class<?> javaxCls = cl.loadClass("org.example.synthetic.JavaxAsyncCtxImpl");
        Class<?> jakartaSubCls = cl.loadClass("org.example.synthetic.JakartaSubAsyncCtx");
        assertNotNull("javax AsyncContext impl must be transformed by interface", transformedJavax[0]);
        assertNotNull("transitive jakarta AsyncContext subclass must be transformed by interface",
                transformedJakartaSub[0]);

        boolean savedLatch = KeployDedupAgent.observedPreCapturedUntrackedExecutor.get();
        KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(false);
        try {
            // 1. Inside an armed window: AsyncContext.start(r) holds the window open until r finishes!
            RequestGate.open("ac-unit-1", null);
            Object reqToken = RequestGate.enter();
            final CountDownLatch taskStarted = new CountDownLatch(1);
            final CountDownLatch releaseTask = new CountDownLatch(1);
            final AtomicBoolean trackedDuringStart = new AtomicBoolean(false);
            final AtomicReference<Thread> workerRef = new AtomicReference<>();

            Object javaxInst = javaxCls.getDeclaredConstructor(AtomicBoolean.class, AtomicReference.class)
                    .newInstance(trackedDuringStart, workerRef);
            javaxCls.getMethod("start", Runnable.class).invoke(javaxInst, (Runnable) () -> {
                taskStarted.countDown();
                try {
                    releaseTask.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            RequestGate.exit(reqToken);
            assertTrue("tracked dispatch must be active inside keploy$async$start", trackedDuringStart.get());
            assertTrue("async task started", taskStarted.await(5, TimeUnit.SECONDS));
            assertEquals("window must stay open (running == 1) while AsyncContext.start task runs",
                    1, RequestGate.running());
            assertFalse("short await while AsyncContext.start task runs must cut",
                    RequestGate.await(TimeUnit.MILLISECONDS.toNanos(40)));
            releaseTask.countDown();
            if (workerRef.get() != null) {
                workerRef.get().join(5000);
            }

            // 2. Transitive jakarta subclass: normal in-window completion drains cleanly,
            //    and throwing from start(r) releases permit via dropped()!
            RequestGate.open("ac-unit-throw", null);
            Object reqToken2 = RequestGate.enter();
            Object jakartaInst = jakartaSubCls.getDeclaredConstructor(AtomicBoolean.class, AtomicReference.class)
                    .newInstance(new AtomicBoolean(), new AtomicReference<Thread>());
            jakartaSubCls.getField("throwOnStart").setBoolean(jakartaInst, true);
            boolean threw = false;
            try {
                jakartaSubCls.getMethod("start", Runnable.class).invoke(jakartaInst, (Runnable) () -> { });
            } catch (java.lang.reflect.InvocationTargetException ite) {
                threw = ite.getCause() instanceof IllegalStateException;
            }
            assertTrue("start() failure must propagate", threw);
            RequestGate.exit(reqToken2);
            assertEquals("dropped() must release permit immediately on exception", 0, RequestGate.running());
            assertTrue("dropped() must let window finish cleanly when AsyncContext.start throws",
                    RequestGate.await(TimeUnit.MILLISECONDS.toNanos(500)));

            // 3. Outside any window: AsyncContext.start(r) latches asyncExecutors=false!
            assertFalse("must not have latched yet", KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());
            Object outsideInst = jakartaSubCls.getDeclaredConstructor(AtomicBoolean.class, AtomicReference.class)
                    .newInstance(new AtomicBoolean(), new AtomicReference<Thread>());
            AtomicReference<Thread> outsideWorker = new AtomicReference<>();
            jakartaSubCls.getField("workerRef").set(outsideInst, outsideWorker);
            jakartaSubCls.getMethod("start", Runnable.class).invoke(outsideInst, (Runnable) () -> { });
            if (outsideWorker.get() != null) {
                outsideWorker.get().join(5000);
            }
            assertTrue("AsyncContext.start outside any window must latch asyncExecutors=false",
                    KeployDedupAgent.observedPreCapturedUntrackedExecutor.get());
        } finally {
            RequestGate.disarm();
            KeployDedupAgent.observedPreCapturedUntrackedExecutor.set(savedLatch);
            ServerEntries.restoreUnhookedAsyncDispatchers(savedUnhooked);
        }
    }

    private static byte[] generateMarkerInterface(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                internalName, null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "start", "(Ljava/lang/Runnable;)V", null, null)
                .visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateAsyncContextBase(String internalName, String superName, String[] interfaces) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                internalName, null, superName, interfaces);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateAsyncContextImpl(String internalName, String superName, String[] interfaces) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, superName, interfaces);
        cw.visitField(Opcodes.ACC_PUBLIC, "trackedDuringStart", "Ljava/util/concurrent/atomic/AtomicBoolean;",
                null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "workerRef", "Ljava/util/concurrent/atomic/AtomicReference;",
                null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "throwOnStart", "Z", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(Ljava/util/concurrent/atomic/AtomicBoolean;Ljava/util/concurrent/atomic/AtomicReference;)V",
                null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, internalName, "trackedDuringStart",
                "Ljava/util/concurrent/atomic/AtomicBoolean;");
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 2);
        init.visitFieldInsn(Opcodes.PUTFIELD, internalName, "workerRef",
                "Ljava/util/concurrent/atomic/AtomicReference;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // public void start(Runnable r) {
        //   trackedDuringStart.set(RequestGate.isTrackedDispatchActive());
        //   if (throwOnStart) throw new IllegalStateException("boom");
        //   Thread t = new Thread(r, "synthetic-async-ctx");
        //   workerRef.set(t);
        //   t.start();
        // }
        MethodVisitor start = cw.visitMethod(Opcodes.ACC_PUBLIC, "start", "(Ljava/lang/Runnable;)V", null, null);
        start.visitCode();
        start.visitVarInsn(Opcodes.ALOAD, 0);
        start.visitFieldInsn(Opcodes.GETFIELD, internalName, "trackedDuringStart",
                "Ljava/util/concurrent/atomic/AtomicBoolean;");
        start.visitMethodInsn(Opcodes.INVOKESTATIC, "io/keploy/dedup/JdkSinkBridgeTest",
                "isTrackedDispatchActiveForTest", "()Z", false);
        start.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/atomic/AtomicBoolean",
                "set", "(Z)V", false);

        org.objectweb.asm.Label noThrow = new org.objectweb.asm.Label();
        start.visitVarInsn(Opcodes.ALOAD, 0);
        start.visitFieldInsn(Opcodes.GETFIELD, internalName, "throwOnStart", "Z");
        start.visitJumpInsn(Opcodes.IFEQ, noThrow);
        start.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        start.visitInsn(Opcodes.DUP);
        start.visitLdcInsn("boom");
        start.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException",
                "<init>", "(Ljava/lang/String;)V", false);
        start.visitInsn(Opcodes.ATHROW);

        start.visitLabel(noThrow);
        start.visitTypeInsn(Opcodes.NEW, "java/lang/Thread");
        start.visitInsn(Opcodes.DUP);
        start.visitVarInsn(Opcodes.ALOAD, 1);
        start.visitLdcInsn("synthetic-async-ctx");
        start.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Thread",
                "<init>", "(Ljava/lang/Runnable;Ljava/lang/String;)V", false);
        start.visitVarInsn(Opcodes.ASTORE, 2);
        start.visitVarInsn(Opcodes.ALOAD, 0);
        start.visitFieldInsn(Opcodes.GETFIELD, internalName, "workerRef",
                "Ljava/util/concurrent/atomic/AtomicReference;");
        start.visitVarInsn(Opcodes.ALOAD, 2);
        start.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/atomic/AtomicReference",
                "set", "(Ljava/lang/Object;)V", false);
        start.visitVarInsn(Opcodes.ALOAD, 2);
        start.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "start", "()V", false);
        start.visitInsn(Opcodes.RETURN);
        start.visitMaxs(0, 0);
        start.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    public void asyncContextHookFailsClosedWhenLoaderCannotSeeRequestGate() {
        final ServerEntries transformer = new ServerEntries(true);
        final byte[] javaxImpl = generateAsyncContextImpl(
                "org/example/isolated/IsolatedJavaxAsyncCtx", "java/lang/Object",
                new String[] {"javax/servlet/AsyncContext"});
        final byte[] jakartaImpl = generateAsyncContextImpl(
                "org/example/isolated/IsolatedJakartaAsyncCtx", "java/lang/Object",
                new String[] {"jakarta/servlet/AsyncContext"});
        ClassLoader isolatedLoader = new ClassLoader(null) { };
        java.util.Set<String> savedUnhooked = ServerEntries.snapshotUnhookedAsyncDispatchers();
        try {
            ServerEntries.setHookStatusForTests(false, false, false, false, false);
            assertFalse("fresh asyncHookFailed must be false", ServerEntries.hadAsyncHookFailure());
            byte[] outJavax = transformer.transform(isolatedLoader,
                    "org/example/isolated/IsolatedJavaxAsyncCtx", null, null, javaxImpl);
            assertEquals("unaccessible loader must leave bytecode untouched", null, outJavax);
            assertTrue("javax AsyncContext in loader without RequestGate must set hadAsyncHookFailure()",
                    ServerEntries.hadAsyncHookFailure());

            ServerEntries.setHookStatusForTests(false, false, false, false, false);
            byte[] outJakarta = transformer.transform(isolatedLoader,
                    "org/example/isolated/IsolatedJakartaAsyncCtx", null, null, jakartaImpl);
            assertEquals("unaccessible loader must leave jakarta bytecode untouched", null, outJakarta);
            assertTrue("jakarta AsyncContext in loader without RequestGate must set hadAsyncHookFailure()",
                    ServerEntries.hadAsyncHookFailure());
        } finally {
            ServerEntries.setHookStatusForTests(false, false, false, false, false);
            ServerEntries.restoreUnhookedAsyncDispatchers(savedUnhooked);
        }
    }

    @Test
    public void jvmInternalAndServerAllowlistEntriesHaveExplicitBehaviourRows() throws Exception {
        ExecutorService plain = Executors.newSingleThreadExecutor();
        Thread plainThread = new Thread(() -> { });
        Runnable plainTask = () -> { };
        String[] callerOut = new String[1];
        try {
            // 1. JVM_INTERNAL_CALLER_CLASSES (exact count + behaviour at Thread/TPE/STPE/Timer sinks).
            String[] expectedJvmCallers = {
                "java.lang.ApplicationShutdownHooks",
                "java.lang.ProcessHandleImpl",
                "java.lang.ProcessImpl",
                "java.lang.UNIXProcess",
                "jdk.internal.ref.CleanerImpl",
                "jdk.internal.ref.CleanerFactory",
                "sun.misc.Cleaner",
                "java.lang.ref.Cleaner",
                "java.util.logging.LogManager",
                "sun.net.www.http.KeepAliveCache",
                "sun.net.www.http.KeepAliveCache$1",
                "java.lang.VirtualThread",
                "java.util.concurrent.DelayScheduler",
                "jdk.internal.misc.Signal",
                "sun.misc.Signal",
            };
            assertEquals("exact count of JVM_INTERNAL_CALLER_CLASSES",
                    expectedJvmCallers.length, SinkAllowlist.JVM_INTERNAL_CALLER_CLASSES.size());
            for (String cls : expectedJvmCallers) {
                assertFalse("no package wildcards: " + cls, cls.endsWith(".") || cls.endsWith("*"));
                assertTrue("behaviour: JVM caller allowed at Thread.start: " + cls,
                        SinkAllowlist.isAllowlistedThreadStart(plainThread, cls, callerOut));
                assertEquals(cls, callerOut[0]);
                assertTrue("behaviour: JVM caller allowed at TPE.execute: " + cls,
                        SinkAllowlist.isAllowlistedTpeExecute(plain, plainTask, cls, callerOut));
                assertTrue("behaviour: JVM caller allowed at STPE.delayedExecute: " + cls,
                        SinkAllowlist.isAllowlistedStpeDelayedExecute(plain, plainTask, cls, callerOut));
                assertTrue("behaviour: JVM caller allowed at Timer.sched: " + cls,
                        SinkAllowlist.isAllowlistedTimerSched(new Object(), plainTask, cls, callerOut));
            }
            assertTrue("behaviour: java.lang.VirtualThread allowed at FJP.submit",
                    SinkAllowlist.isAllowlistedFjpSubmit(ForkJoinPool.commonPool(), plainTask,
                            "java.lang.VirtualThread", callerOut));

            // 2. JVM_INTERNAL_THREAD_CLASSES (exact count + zero wildcards).
            String[] expectedJvmThreads = {
                "java.lang.ref.Reference$ReferenceHandler",
                "java.lang.ref.Finalizer$FinalizerThread",
                "jdk.internal.ref.CleanerImpl$InnocuousThread",
                "jdk.internal.misc.InnocuousThread",
                "sun.misc.InnocuousThread",
                "jdk.internal.misc.CarrierThread",
                "java.util.logging.LogManager$Cleaner",
            };
            assertEquals("exact count of JVM_INTERNAL_THREAD_CLASSES",
                    expectedJvmThreads.length, SinkAllowlist.JVM_INTERNAL_THREAD_CLASSES.size());
            for (String cls : expectedJvmThreads) {
                assertTrue("expected JVM thread class: " + cls,
                        SinkAllowlist.JVM_INTERNAL_THREAD_CLASSES.contains(cls));
                assertFalse("no package wildcards: " + cls, cls.endsWith(".") || cls.endsWith("*"));
            }

            // 3. SERVER_THREAD_START_CALLERS (exact count + behaviour at Thread.start).
            String[] expectedServerThreadCallers = {
                "org.apache.tomcat.util.net.AbstractEndpoint",
                "org.apache.tomcat.util.net.NioEndpoint",
                "org.apache.tomcat.util.net.Nio2Endpoint",
                "org.apache.tomcat.util.net.AprEndpoint",
                "org.apache.tomcat.util.threads.ThreadPoolExecutor",
                "org.apache.tomcat.util.threads.ScheduledThreadPoolExecutor",
                "org.apache.catalina.core.StandardServer",
                "org.apache.catalina.core.ContainerBase",
                "org.apache.catalina.startup.Catalina",
                "org.apache.juli.AsyncFileHandler$LoggerExecutorService",
                "org.eclipse.jetty.util.thread.QueuedThreadPool",
                "org.eclipse.jetty.util.thread.ScheduledExecutorScheduler",
                "org.eclipse.jetty.util.thread.ExecutorThreadPool",
                "org.eclipse.jetty.util.thread.TimerScheduler",
                "org.xnio.nio.NioXnioWorker",
                "org.xnio.XnioWorker",
                "org.quartz.simpl.SimpleThreadPool",
                "org.quartz.core.QuartzScheduler",
            };
            assertEquals("exact count of SERVER_THREAD_START_CALLERS",
                    expectedServerThreadCallers.length, SinkAllowlist.SERVER_THREAD_START_CALLERS.size());
            for (String cls : expectedServerThreadCallers) {
                assertFalse("no package wildcards: " + cls, cls.endsWith(".") || cls.endsWith("*"));
                assertTrue("behaviour: server thread caller allowed at Thread.start: " + cls,
                        SinkAllowlist.isAllowlistedThreadStart(plainThread, cls, callerOut));
                assertEquals(cls, callerOut[0]);
            }

            // 4. SERVER_POOL_CALLERS (exact count + behaviour at TPE & STPE).
            String[] expectedServerPoolCallers = {
                "org.apache.tomcat.util.net.AbstractEndpoint",
                "org.apache.tomcat.util.net.NioEndpoint",
                "org.apache.tomcat.util.net.Nio2Endpoint",
                "org.apache.tomcat.util.net.AprEndpoint",
                "org.apache.tomcat.util.threads.ScheduledThreadPoolExecutor",
                "org.apache.catalina.core.StandardServer",
                "org.apache.catalina.core.ContainerBase",
                "org.eclipse.jetty.util.thread.ScheduledExecutorScheduler",
                "org.eclipse.jetty.util.thread.ExecutorThreadPool",
                "org.xnio.XnioWorker",
            };
            assertEquals("exact count of SERVER_POOL_CALLERS",
                    expectedServerPoolCallers.length, SinkAllowlist.SERVER_POOL_CALLERS.size());
            for (String cls : expectedServerPoolCallers) {
                assertFalse("no package wildcards: " + cls, cls.endsWith(".") || cls.endsWith("*"));
                assertTrue("behaviour: server pool caller allowed at TPE: " + cls,
                        SinkAllowlist.isAllowlistedTpeExecute(plain, plainTask, cls, callerOut));
                assertTrue("behaviour: server pool caller allowed at STPE: " + cls,
                        SinkAllowlist.isAllowlistedStpeDelayedExecute(plain, plainTask, cls, callerOut));
            }

            // 5. VirtualThread & TimerScheduler server caller behaviour rows.
            assertTrue("behaviour: Tomcat VirtualThreadExecutor allowed at VirtualThread.start",
                    SinkAllowlist.isAllowlistedVirtualThreadStart(plainThread,
                            "org.apache.tomcat.util.threads.VirtualThreadExecutor", callerOut));
            assertTrue("behaviour: Tomcat StandardVirtualThreadExecutor allowed at VirtualThread.start",
                    SinkAllowlist.isAllowlistedVirtualThreadStart(plainThread,
                            "org.apache.catalina.core.StandardVirtualThreadExecutor", callerOut));
            assertTrue("behaviour: Jetty TimerScheduler allowed at Timer.sched",
                    SinkAllowlist.isAllowlistedTimerSched(new Object(), plainTask,
                            "org.eclipse.jetty.util.thread.TimerScheduler", callerOut));

            // 6. Real server worker Thread, pool, and JobGate-hooked task instances behave as allowlisted.
            Thread tomcatTaskThread = new org.apache.tomcat.util.threads.TaskThread(
                    Thread.currentThread().getThreadGroup(), plainTask, "test-task-thread");
            assertTrue("behaviour: Tomcat TaskThread allowed at Thread.start even from test caller",
                    SinkAllowlist.isAllowlistedThreadStart(tomcatTaskThread, JdkSinkBridgeTest.class.getName(),
                            callerOut));

            org.apache.tomcat.util.threads.ThreadPoolExecutor tomcatPool =
                    new org.apache.tomcat.util.threads.ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                            new org.apache.tomcat.util.threads.TaskQueue());
            java.util.concurrent.ScheduledExecutorService delegateSched = Executors.newSingleThreadScheduledExecutor();
            org.apache.tomcat.util.threads.ScheduledThreadPoolExecutor tomcatSched =
                    new org.apache.tomcat.util.threads.ScheduledThreadPoolExecutor(delegateSched);
            try {
                assertTrue("behaviour: Tomcat ThreadPoolExecutor allowed at TPE.execute",
                        SinkAllowlist.isAllowlistedTpeExecute(tomcatPool, plainTask,
                                JdkSinkBridgeTest.class.getName(), callerOut));
                assertTrue("behaviour: Tomcat ScheduledThreadPoolExecutor allowed at STPE.delayedExecute",
                        SinkAllowlist.isAllowlistedStpeDelayedExecute(tomcatSched, plainTask,
                                JdkSinkBridgeTest.class.getName(), callerOut));
            } finally {
                tomcatPool.shutdownNow();
                tomcatSched.shutdownNow();
                delegateSched.shutdownNow();
            }

            Runnable schedMethodRunnable = new org.springframework.scheduling.support.ScheduledMethodRunnable(
                    this, JdkSinkBridgeTest.class.getMethod("unarmedHandOffOutsideWindowLatchesAsyncExecutorsFalse"));
            assertTrue("behaviour: JobGate-hooked ScheduledMethodRunnable allowed at TPE & STPE",
                    SinkAllowlist.isAllowlistedTpeExecute(plain, schedMethodRunnable,
                            JdkSinkBridgeTest.class.getName(), callerOut));
            assertTrue("behaviour: JobGate-hooked ScheduledMethodRunnable allowed at STPE",
                    SinkAllowlist.isAllowlistedStpeDelayedExecute(plain, schedMethodRunnable,
                            JdkSinkBridgeTest.class.getName(), callerOut));

            // Pin exact sizes of SERVER_WORKER_THREAD_CLASSES, SERVER_POOL_CLASSES, SERVER_TASK_CLASSES.
            assertEquals(7, SinkAllowlist.SERVER_WORKER_THREAD_CLASSES.size());
            assertEquals(3, SinkAllowlist.SERVER_POOL_CLASSES.size());
            assertEquals(9, SinkAllowlist.SERVER_TASK_CLASSES.size());
        } finally {
            plain.shutdownNow();
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
