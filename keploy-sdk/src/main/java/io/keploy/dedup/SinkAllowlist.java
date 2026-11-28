package io.keploy.dedup;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * Explicit, closed, exact-class allowlist for JDK concurrency sinks hooked via
 * {@code io.keploy.dedup.bootstrap.KeploySinkBridge}.
 *
 * <p>No package wildcards or prefixes are used. A sink invocation keeps
 * {@code asyncExecutors=true} ONLY if:
 * <ol>
 *   <li>it carries proven {@link RequestGate} tracking ({@code RequestGate.Hand}
 *       or active {@code TRACKED_DISPATCH_DEPTH}),</li>
 *   <li>it is Keploy's own agent-internal thread/pool,</li>
 *   <li>it is a reviewed JVM/JDK bootstrap housekeeping class,</li>
 *   <li>it is an explicitly reviewed HTTP server request-hosting worker pool
 *       (Tomcat, Jetty, Undertow/XNIO) whose requests enter a hooked
 *       {@link ServerEntries} entry, or a {@link JobGate}-hooked scheduler, or</li>
 *   <li>it is a reviewed infrastructure class verified from source never to
 *       execute an application-supplied Runnable, lambda, or listener.</li>
 * </ol>
 * Everything else fails closed.
 */
final class SinkAllowlist {

    static final boolean AUDIT = "1".equals(System.getenv("KEPLOY_JAVA_SINK_AUDIT"))
            || "true".equalsIgnoreCase(System.getenv("KEPLOY_JAVA_SINK_AUDIT"))
            || "true".equalsIgnoreCase(System.getProperty("keploy.java.sink.audit"));

    // Exact JVM/JDK internal Thread subclasses (bootstrap/platform CL only).
    private static final Set<String> JVM_INTERNAL_THREAD_CLASSES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    "java.lang.ref.Reference$ReferenceHandler",
                    "java.lang.ref.Finalizer$FinalizerThread",
                    "jdk.internal.ref.CleanerImpl$InnocuousThread",
                    "jdk.internal.misc.InnocuousThread",
                    "jdk.internal.misc.CarrierThread",
                    "sun.misc.InnocuousThread",
                    "java.util.logging.LogManager$Cleaner"
            )));

    // Exact JVM/JDK internal callers that spawn housekeeping Threads (bootstrap/platform CL only).
    private static final Set<String> JVM_INTERNAL_CALLER_CLASSES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
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
                    "sun.nio.ch.AsynchronousChannelGroupImpl",
                    "java.lang.VirtualThread",
                    "java.util.concurrent.DelayScheduler",
                    "jdk.internal.misc.Signal",
                    "sun.misc.Signal"
            )));

    // Exact reviewed HTTP server request-hosting worker Thread subclasses
    // (requests on these threads enter hooked CoyoteAdapter / HttpChannel / Connectors).
    private static final Set<String> SERVER_WORKER_THREAD_CLASSES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    // Tomcat (CoyoteAdapter)
                    "org.apache.tomcat.util.threads.TaskThread",
                    "org.apache.catalina.startup.Catalina$CatalinaShutdownHook",
                    "org.apache.juli.ClassLoaderLogManager$Cleaner",
                    // Jetty (HttpChannel / ServletChannel)
                    "org.eclipse.jetty.util.thread.ShutdownThread",
                    // Undertow / XNIO / JBoss Threads (Connectors)
                    "org.xnio.nio.WorkerThread",
                    "org.jboss.threads.JBossThread",
                    // JobGate-hooked Quartz scheduler threads
                    "org.quartz.simpl.SimpleThreadPool$WorkerThread",
                    "org.quartz.core.QuartzSchedulerThread"
            )));

    // Exact reviewed HTTP server request-hosting classes that start plain java.lang.Thread
    // instances (acceptors, pollers, Jetty QueuedThreadPool workers, Tomcat container background).
    private static final Set<String> SERVER_THREAD_START_CALLERS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    // Tomcat (CoyoteAdapter)
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
                    // Jetty (HttpChannel / ServletChannel)
                    "org.eclipse.jetty.util.thread.QueuedThreadPool",
                    "org.eclipse.jetty.util.thread.ScheduledExecutorScheduler",
                    "org.eclipse.jetty.util.thread.ExecutorThreadPool",
                    "org.eclipse.jetty.util.thread.TimerScheduler",
                    // Undertow / XNIO / JBoss Threads (Connectors)
                    "org.xnio.nio.NioXnioWorker",
                    "org.xnio.XnioWorker",
                    "org.jboss.threads.EnhancedQueueExecutor",
                    "org.jboss.threads.JBossScheduledThreadPoolExecutor",
                    // JobGate-hooked Quartz scheduler
                    "org.quartz.simpl.SimpleThreadPool",
                    "org.quartz.core.QuartzScheduler"
            )));

    // Exact reviewed HTTP server request-hosting classes that dispatch onto
    // ThreadPoolExecutor / ScheduledThreadPoolExecutor for server I/O or
    // maintenance (requests enter hooked CoyoteAdapter / HttpChannel / Connectors).
    private static final Set<String> SERVER_POOL_CALLERS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    // Tomcat (CoyoteAdapter)
                    "org.apache.tomcat.util.net.AbstractEndpoint",
                    "org.apache.tomcat.util.net.NioEndpoint",
                    "org.apache.tomcat.util.net.Nio2Endpoint",
                    "org.apache.tomcat.util.net.AprEndpoint",
                    "org.apache.tomcat.util.threads.ScheduledThreadPoolExecutor",
                    "org.apache.catalina.core.StandardServer",
                    "org.apache.catalina.core.ContainerBase",
                    // Jetty (HttpChannel / ServletChannel)
                    "org.eclipse.jetty.util.thread.ScheduledExecutorScheduler",
                    "org.eclipse.jetty.util.thread.ExecutorThreadPool",
                    // Undertow / XNIO / JBoss Threads (Connectors)
                    "org.xnio.XnioWorker",
                    "org.jboss.threads.JBossScheduledThreadPoolExecutor"
            )));

    // Exact reviewed HTTP server request-hosting ThreadPoolExecutor / STPE subclasses.
    private static final Set<String> SERVER_POOL_CLASSES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    // Tomcat (CoyoteAdapter)
                    "org.apache.tomcat.util.threads.ThreadPoolExecutor",
                    "org.apache.tomcat.util.threads.ScheduledThreadPoolExecutor",
                    "org.apache.juli.AsyncFileHandler$LoggerExecutorService",
                    // Undertow / XNIO / JBoss Threads (Connectors)
                    "org.xnio.XnioWorker$DefaultThreadPoolExecutor",
                    "org.jboss.threads.JBossScheduledThreadPoolExecutor",
                    "org.jboss.threads.ManagedThreadPoolExecutor"
            )));

    // Exact reviewed ScheduledThreadPoolExecutor / TPE task classes from
    // servers or JobGate-hooked schedulers that never execute arbitrary unhooked app callbacks.
    private static final Set<String> SERVER_TASK_CLASSES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    // Tomcat container utility/maintenance tasks
                    "org.apache.catalina.core.ContainerBase$ContainerBackgroundProcessorMonitor",
                    "org.apache.catalina.core.StandardServer$1",
                    "org.apache.catalina.util.TimeBucketCounterBase$MaintenanceMonitor",
                    "org.apache.catalina.util.TimeBucketCounter$MaintenanceMonitor",
                    "org.apache.tomcat.util.net.NioEndpoint$SocketProcessor",
                    "org.apache.tomcat.util.net.Nio2Endpoint$SocketProcessor",
                    // Jetty internal scheduled tasks
                    "org.eclipse.jetty.util.thread.ScheduledExecutorScheduler$ScheduledFutureTask",
                    "org.eclipse.jetty.io.IdleTimeout$1",
                    // Spring @Scheduled (hooked into JobGate via ScheduledMethodRunnable)
                    "org.springframework.scheduling.support.ScheduledMethodRunnable",
                    "org.springframework.scheduling.support.DelegatingErrorHandlingRunnable",
                    "org.springframework.scheduling.concurrent.ReschedulingRunnable"
            )));

    // Exact zero-app-callback infrastructure classes measured on Spring PetClinic
    // (each entry carries an exact library version + class/method/source-line
    // citation verified from the dependency JAR proving no user Runnable,
    // lambda, or listener executes on it; exact class names only, no prefixes).
    static final Set<String> INFRA_THREAD_CALLERS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    // HikariCP 7.1.0 (HikariPool.java:90-132): private addConnectionExecutor,
                    // closeConnectionExecutor, and houseKeepingExecutorService spawn internal
                    // housekeeper/connection-adder/connection-closer threads only.
                    "com.zaxxer.hikari.pool.HikariPool",
                    // MySQL Connector/J 26.10.0 (AbandonedConnectionCleanupThread.java:83-105):
                    // private cleanupThreadExecutorService polls ReferenceQueue<MysqlConnection>
                    // to close leaked sockets; never runs user callbacks.
                    "com.mysql.cj.jdbc.AbandonedConnectionCleanupThread",
                    // H2 2.5.252 (MathUtils.java:65-122): getSecureRandom() spawns one
                    // "Generate Seed" daemon thread calling SecureRandom.generateSeed().
                    "org.h2.util.MathUtils",
                    // Spring Boot AutoConfigure 4.2.0 (OnClassCondition.java:141-166):
                    // ThreadedOutcomesResolver evaluates @ConditionalOnClass checks on a
                    // startup thread joined in resolveOutcomes(); never runs user callbacks.
                    "org.springframework.boot.autoconfigure.condition.OnClassCondition$ThreadedOutcomesResolver",
                    // Spring Boot AutoConfigure 4.2.0
                    // (BackgroundPreinitializingApplicationListener.java:70-98): spawns one
                    // "background-preinit" startup thread warming built-in framework
                    // initializers and joined before startup finishes; never runs user tasks.
                    "org.springframework.boot.autoconfigure.preinitialize.BackgroundPreinitializingApplicationListener",
                    // Spring Boot Tomcat 4.2.0 (TomcatWebServer.java:102-141): spawns one
                    // non-daemon "container-0" await thread calling Tomcat.getServer().await().
                    "org.springframework.boot.tomcat.TomcatWebServer",
                    // Spring Boot Tomcat 4.2.0 (GracefulShutdown.java:42-69): spawns one
                    // "tomcat-shutdown" thread closing Tomcat connectors on shutdown.
                    "org.springframework.boot.tomcat.GracefulShutdown",
                    // Spring Boot 2/3 equivalent package for TomcatWebServer / GracefulShutdown.
                    "org.springframework.boot.web.embedded.tomcat.TomcatWebServer",
                    "org.springframework.boot.web.embedded.tomcat.GracefulShutdown"
            )));

    static final Set<String> INFRA_POOL_CALLERS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    // HikariCP 7.1.0 (HikariPool.java:90-132): dispatches only private
                    // HouseKeeper, PoolEntryCreator, and closeConnection tasks.
                    "com.zaxxer.hikari.pool.HikariPool",
                    // MySQL Connector/J 26.10.0 (AbandonedConnectionCleanupThread.java:83-105):
                    // submits only its own singleton ReferenceQueue cleanup Runnable.
                    "com.mysql.cj.jdbc.AbandonedConnectionCleanupThread"
            )));

    static final Set<String> INFRA_TASK_CLASSES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    // MySQL Connector/J 26.10.0 (AbandonedConnectionCleanupThread.java:83-105).
                    "com.mysql.cj.jdbc.AbandonedConnectionCleanupThread"
            )));

    private SinkAllowlist() {
    }

    static boolean isAllowlistedThreadStart(Thread thread, String[] callerOut) {
        if (thread == null) {
            return true;
        }
        if (RequestGate.isTrackedDispatchActive()) {
            return true;
        }
        Class<?> cls = thread.getClass();
        String clsName = cls.getName();
        if (isBootstrapOrPlatform(cls) && JVM_INTERNAL_THREAD_CLASSES.contains(clsName)) {
            return true;
        }
        if (SERVER_WORKER_THREAD_CLASSES.contains(clsName)) {
            return true;
        }
        if (thread instanceof ForkJoinWorkerThread) {
            // A ForkJoinWorkerThread is only allowed if its pool was triggered
            // by an active RequestGate tracked dispatch (handled above) or
            // commonPool compensation while already inside a tracked window/job;
            // any other FJP worker start (e.g. unhooked ForkJoinPool subclass or
            // ForkJoinTask.adapt outside window) fails closed.
            if (RequestGate.isInsideTrackedWindowOrJob()
                    && ((ForkJoinWorkerThread) thread).getPool() == java.util.concurrent.ForkJoinPool.commonPool()) {
                return true;
            }
            String caller = firstExternalCaller();
            if (callerOut != null && callerOut.length > 0) {
                callerOut[0] = caller;
            }
            return isAllowlistedJvmCaller(caller);
        }
        String caller = firstExternalCaller();
        if (callerOut != null && callerOut.length > 0) {
            callerOut[0] = caller;
        }
        if (caller == null) {
            return false;
        }
        if (isAllowlistedJvmCaller(caller)) {
            return true;
        }
        if (SERVER_THREAD_START_CALLERS.contains(caller)) {
            return true;
        }
        if (INFRA_THREAD_CALLERS.contains(caller)) {
            return true;
        }
        return false;
    }

    static boolean isAllowlistedVirtualThreadStart(Thread thread, String[] callerOut) {
        if (thread == null) {
            return true;
        }
        if (RequestGate.isTrackedDispatchActive()) {
            return true;
        }
        String caller = firstExternalCaller(true);
        if (callerOut != null && callerOut.length > 0) {
            callerOut[0] = caller;
        }
        // Tomcat virtual-thread request executor enters hooked CoyoteAdapter.
        if ("org.apache.tomcat.util.threads.VirtualThreadExecutor".equals(caller)
                || "org.apache.catalina.core.StandardVirtualThreadExecutor".equals(caller)) {
            return true;
        }
        return false;
    }

    static boolean isAllowlistedTpeExecute(Object executor, Object command, String[] callerOut) {
        if (RequestGate.isTrackedDispatchActive() || RequestGate.isTrackedTask(command)) {
            return true;
        }
        if (RequestGate.isAgentInternalPool(executor)) {
            return true;
        }
        if (executor != null && SERVER_POOL_CLASSES.contains(executor.getClass().getName())) {
            return true;
        }
        if (command != null && (SERVER_TASK_CLASSES.contains(command.getClass().getName())
                || INFRA_TASK_CLASSES.contains(command.getClass().getName()))) {
            return true;
        }
        String caller = firstExternalCaller();
        if (callerOut != null && callerOut.length > 0) {
            callerOut[0] = caller;
        }
        if (caller != null && (SERVER_POOL_CALLERS.contains(caller)
                || INFRA_POOL_CALLERS.contains(caller)
                || isAllowlistedJvmCaller(caller))) {
            return true;
        }
        return false;
    }

    static boolean isAllowlistedStpeDelayedExecute(Object executor, Object task, String[] callerOut) {
        if (RequestGate.isTrackedDispatchActive() || RequestGate.isTrackedTask(task)) {
            return true;
        }
        if (RequestGate.isAgentInternalPool(executor)) {
            return true;
        }
        if (executor != null && SERVER_POOL_CLASSES.contains(executor.getClass().getName())) {
            return true;
        }
        if (task != null && (SERVER_TASK_CLASSES.contains(task.getClass().getName())
                || INFRA_TASK_CLASSES.contains(task.getClass().getName()))) {
            return true;
        }
        String caller = firstExternalCaller();
        if (callerOut != null && callerOut.length > 0) {
            callerOut[0] = caller;
        }
        if (caller != null && (SERVER_POOL_CALLERS.contains(caller)
                || INFRA_POOL_CALLERS.contains(caller)
                || isAllowlistedJvmCaller(caller))) {
            return true;
        }
        return false;
    }

    static boolean isAllowlistedFjpSubmit(Object pool, Object task, String[] callerOut) {
        if (RequestGate.isTrackedDispatchActive() || RequestGate.isTrackedTask(task)) {
            return true;
        }
        // JDK 21 VirtualThread continuations run on VirtualThread.DEFAULT_SCHEDULER;
        // VirtualThread.start() was already audited (and latched/contaminated if
        // non-allowlisted) before submitting the continuation.
        if (task != null && "java.lang.VirtualThread$VThreadContinuation".equals(task.getClass().getName())) {
            return true;
        }
        String caller = firstExternalCaller();
        if (callerOut != null && callerOut.length > 0) {
            callerOut[0] = caller;
        }
        if ("java.lang.VirtualThread".equals(caller)) {
            return true;
        }
        return false;
    }

    static boolean isAllowlistedTimerSched(Object timer, Object task, String[] callerOut) {
        if (RequestGate.isTrackedDispatchActive()) {
            return true;
        }
        String caller = firstExternalCaller();
        if (callerOut != null && callerOut.length > 0) {
            callerOut[0] = caller;
        }
        if (caller != null && (isAllowlistedJvmCaller(caller)
                || "org.eclipse.jetty.util.thread.TimerScheduler".equals(caller)
                || INFRA_POOL_CALLERS.contains(caller))) {
            return true;
        }
        return false;
    }

    static void audit(String sink, boolean allowed, String detail, Object recv, Object task, String caller) {
        if (!AUDIT) {
            return;
        }
        System.err.println("KEPLOY_SINK_AUDIT sink=" + sink
                + " allowed=" + allowed
                + " detail=" + detail
                + " recv=" + (recv == null ? "null" : recv.getClass().getName())
                + " task=" + (task == null ? "null" : task.getClass().getName())
                + " caller=" + (caller == null ? "null" : caller));
    }

    private static boolean isAllowlistedJvmCaller(String caller) {
        return caller != null && JVM_INTERNAL_CALLER_CLASSES.contains(caller);
    }

    private static boolean isBootstrapOrPlatform(Class<?> cls) {
        ClassLoader cl = cls.getClassLoader();
        if (cl == null) {
            return true;
        }
        // JDK 9+ platform classloader (e.g. java.logging).
        ClassLoader sys = ClassLoader.getSystemClassLoader();
        return sys != null && cl == sys.getParent();
    }

    // Walks the current thread's stack to find the first caller outside the
    // sink bridge, Keploy's own RequestGate/SinkAllowlist, and the JDK sink
    // wrapper methods themselves (e.g. ThreadPoolExecutor.addWorker -> execute,
    // AbstractExecutorService.submit -> execute, Timer.schedule -> sched).
    // Note: reflection (java.lang.reflect.Method.invoke) and MethodHandles are
    // NOT skipped — they return their own class name, which is NOT in any
    // allowlist, so reflective/MethodHandle dispatch fails closed by construction.
    static String firstExternalCaller() {
        return firstExternalCaller(false);
    }

    static String firstExternalCaller(boolean skipVirtualThreadFrames) {
        StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        for (int i = 1; i < frames.length; i++) {
            String cn = frames[i].getClassName();
            if ("io.keploy.dedup.bootstrap.KeploySinkBridge".equals(cn)
                    || "io.keploy.dedup.SinkAllowlist".equals(cn)
                    || "io.keploy.dedup.RequestGate".equals(cn)
                    || "io.keploy.dedup.JdkSinkHandler".equals(cn)
                    || "io.keploy.dedup.JdkSinkBootstrap".equals(cn)
                    || "java.lang.Thread".equals(cn)
                    || "java.lang.System$2".equals(cn)
                    || "jdk.internal.vm.ThreadContainers".equals(cn)
                    || "jdk.internal.vm.SharedThreadContainer".equals(cn)
                    || (skipVirtualThreadFrames && ("java.lang.VirtualThread".equals(cn)
                            || "java.lang.ThreadBuilders$VirtualThreadBuilder".equals(cn)
                            || "java.lang.ThreadBuilders$PlatformThreadBuilder".equals(cn)
                            || "java.lang.ThreadBuilders$BaseThreadBuilder".equals(cn)))
                    || "java.util.concurrent.ThreadPoolExecutor".equals(cn)
                    || "java.util.concurrent.ThreadPoolExecutor$Worker".equals(cn)
                    || "java.util.concurrent.ScheduledThreadPoolExecutor".equals(cn)
                    || "java.util.concurrent.AbstractExecutorService".equals(cn)
                    || "java.util.concurrent.Executors$DelegatedExecutorService".equals(cn)
                    || "java.util.concurrent.Executors$FinalizableDelegatedExecutorService".equals(cn)
                    || "java.util.concurrent.Executors$DelegatedScheduledExecutorService".equals(cn)
                    || "java.util.concurrent.ForkJoinPool".equals(cn)
                    || "java.util.concurrent.ForkJoinTask".equals(cn)
                    || "java.util.concurrent.CompletableFuture".equals(cn)
                    || "java.util.Timer".equals(cn)) {
                continue;
            }
            return cn;
        }
        return null;
    }
}
