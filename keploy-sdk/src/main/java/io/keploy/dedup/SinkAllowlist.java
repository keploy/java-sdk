package io.keploy.dedup;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.function.Function;
import java.util.stream.Stream;

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
 *   <li>it is a reviewed JVM/JDK bootstrap housekeeping class that never runs
 *       user callbacks,</li>
 *   <li>it is an explicitly reviewed HTTP server request-hosting worker pool
 *       (Tomcat, Jetty, Undertow/XNIO) whose requests enter a hooked
 *       {@link ServerEntries} entry (and whose {@code AsyncContext.start}
 *       dispatches enter {@link RequestGate#handAsyncContext}), or a
 *       {@link JobGate}-hooked Quartz/Spring {@code @Scheduled} scheduler, or</li>
 *   <li>it is a reviewed infrastructure class verified from its JAR source
 *       never to execute an application-supplied Runnable, lambda, or listener.</li>
 * </ol>
 * Everything else fails closed.
 */
final class SinkAllowlist {

    static final boolean AUDIT = "1".equals(System.getenv("KEPLOY_JAVA_SINK_AUDIT"))
            || "true".equalsIgnoreCase(System.getenv("KEPLOY_JAVA_SINK_AUDIT"))
            || "true".equalsIgnoreCase(System.getProperty("keploy.java.sink.audit"));

    // Exact JVM/JDK internal Thread subclasses (bootstrap/platform CL only).
    // Every class below is a closed JDK runtime daemon/housekeeping thread that
    // never runs arbitrary unhooked application Runnables directly:
    static final Set<String> JVM_INTERNAL_THREAD_CLASSES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    // OpenJDK java.base/java/lang/ref/Reference.java (ReferenceHandler):
                    // processes JVM GC ReferenceQueue pending list.
                    "java.lang.ref.Reference$ReferenceHandler",
                    // OpenJDK java.base/java/lang/ref/Finalizer.java (FinalizerThread):
                    // runs JVM GC finalize() queue.
                    "java.lang.ref.Finalizer$FinalizerThread",
                    // OpenJDK java.base/jdk/internal/ref/CleanerImpl.java &
                    // jdk/internal/misc/InnocuousThread.java (JDK 9+) /
                    // sun/misc/InnocuousThread.java (JDK 8): Cleaner & innocuous daemon threads.
                    "jdk.internal.ref.CleanerImpl$InnocuousThread",
                    "jdk.internal.misc.InnocuousThread",
                    "sun.misc.InnocuousThread",
                    // OpenJDK 21 java.base/jdk/internal/misc/CarrierThread.java:
                    // VirtualThread DEFAULT_SCHEDULER carrier thread (every VirtualThread.start
                    // is independently audited at onVirtualThreadStart).
                    "jdk.internal.misc.CarrierThread",
                    // OpenJDK java.logging/java/util/logging/LogManager.java (Cleaner):
                    // JVM shutdown hook resetting java.util.logging handlers.
                    "java.util.logging.LogManager$Cleaner"
            )));

    // Exact JVM/JDK internal callers that spawn housekeeping Threads (bootstrap/platform CL only).
    // Note: sun.nio.ch.AsynchronousChannelGroupImpl is intentionally ABSENT because
    // it executes user CompletionHandler callbacks.
    static final Set<String> JVM_INTERNAL_CALLER_CLASSES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    // OpenJDK java.base/java/lang/ApplicationShutdownHooks.java (runHooks):
                    // starts registered Runtime shutdown hooks during JVM exit.
                    "java.lang.ApplicationShutdownHooks",
                    // OpenJDK java.base/java/lang/ProcessHandleImpl.java, ProcessImpl.java,
                    // UNIXProcess.java: OS child-process reaper thread (waitpid loop only).
                    "java.lang.ProcessHandleImpl",
                    "java.lang.ProcessImpl",
                    "java.lang.UNIXProcess",
                    // OpenJDK java.base/jdk/internal/ref/CleanerImpl.java, CleanerFactory.java,
                    // java/lang/ref/Cleaner.java, sun/misc/Cleaner.java: PhantomReference cleaners.
                    "jdk.internal.ref.CleanerImpl",
                    "jdk.internal.ref.CleanerFactory",
                    "sun.misc.Cleaner",
                    "java.lang.ref.Cleaner",
                    // OpenJDK java.logging/java/util/logging/LogManager.java: logging shutdown cleaner.
                    "java.util.logging.LogManager",
                    // OpenJDK java.base/sun/net/www/http/KeepAliveCache.java (JDK 8 uses anonymous
                    // PrivilegedAction KeepAliveCache$1): idle HTTP keep-alive socket eviction timer.
                    "sun.net.www.http.KeepAliveCache",
                    "sun.net.www.http.KeepAliveCache$1",
                    // OpenJDK 21 java.base/java/lang/VirtualThread.java &
                    // java/util/concurrent/DelayScheduler.java: VirtualThread carrier/unparker internals.
                    "java.lang.VirtualThread",
                    "java.util.concurrent.DelayScheduler",
                    // OpenJDK java.base/jdk/internal/misc/Signal.java (JDK 9+) /
                    // sun/misc/Signal.java (JDK 8): OS signal dispatcher (SIGTERM handler).
                    "jdk.internal.misc.Signal",
                    "sun.misc.Signal"
            )));

    // Exact reviewed HTTP server request-hosting worker Thread subclasses
    // (requests on these threads enter hooked CoyoteAdapter / HttpChannel / Connectors,
    // and AsyncContext.start dispatches enter RequestGate.handAsyncContext).
    // Note: org.jboss.threads.JBossThread is intentionally ABSENT because generic
    // EnhancedQueueExecutor/WildFly managed pools also spawn JBossThread workers.
    static final Set<String> SERVER_WORKER_THREAD_CLASSES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    // Apache Tomcat 9.0.121 / 11.0.27 (org/apache/tomcat/util/threads/TaskThread.java):
                    // Tomcat connector & container utility threads.
                    "org.apache.tomcat.util.threads.TaskThread",
                    // Apache Tomcat (org/apache/catalina/startup/Catalina.java): shutdown hook thread.
                    "org.apache.catalina.startup.Catalina$CatalinaShutdownHook",
                    // Apache Tomcat JULI (org/apache/juli/ClassLoaderLogManager.java): log cleaner.
                    "org.apache.juli.ClassLoaderLogManager$Cleaner",
                    // Eclipse Jetty 9.4.57 (org/eclipse/jetty/util/thread/ShutdownThread.java): lifecycle shutdown.
                    "org.eclipse.jetty.util.thread.ShutdownThread",
                    // XNIO 3.8.x (org/xnio/nio/WorkerThread.java): Undertow I/O selector threads.
                    "org.xnio.nio.WorkerThread",
                    // Quartz 2.3.2 (org/quartz/simpl/SimpleThreadPool.java, QuartzSchedulerThread.java):
                    // JobGate-hooked via JobRunShell.run.
                    "org.quartz.simpl.SimpleThreadPool$WorkerThread",
                    "org.quartz.core.QuartzSchedulerThread"
            )));

    // Exact reviewed HTTP server request-hosting classes that start plain java.lang.Thread
    // instances (acceptors, pollers, request-hosting worker growth, container background).
    static final Set<String> SERVER_THREAD_START_CALLERS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    // Apache Tomcat 9.0.121 / 11.0.27 (AbstractEndpoint, NioEndpoint, Nio2Endpoint,
                    // AprEndpoint, ThreadPoolExecutor, ScheduledThreadPoolExecutor, StandardServer,
                    // ContainerBase, Catalina, AsyncFileHandler): request dispatch enters CoyoteAdapter.
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
                    // Eclipse Jetty 9.4.57 (QueuedThreadPool, ScheduledExecutorScheduler,
                    // ExecutorThreadPool, TimerScheduler): request dispatch enters HttpChannel.
                    "org.eclipse.jetty.util.thread.QueuedThreadPool",
                    "org.eclipse.jetty.util.thread.ScheduledExecutorScheduler",
                    "org.eclipse.jetty.util.thread.ExecutorThreadPool",
                    "org.eclipse.jetty.util.thread.TimerScheduler",
                    // XNIO 3.8.x (NioXnioWorker, XnioWorker): Undertow request dispatch enters Connectors.
                    "org.xnio.nio.NioXnioWorker",
                    "org.xnio.XnioWorker",
                    // Quartz 2.3.2 (SimpleThreadPool, QuartzScheduler): JobGate-hooked via JobRunShell.run.
                    "org.quartz.simpl.SimpleThreadPool",
                    "org.quartz.core.QuartzScheduler"
            )));

    // Exact reviewed HTTP server request-hosting classes that dispatch onto
    // ThreadPoolExecutor / ScheduledThreadPoolExecutor for server I/O or
    // maintenance (requests enter hooked CoyoteAdapter / HttpChannel / Connectors).
    static final Set<String> SERVER_POOL_CALLERS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    // Apache Tomcat 9.0.121 / 11.0.27: connector I/O & ScheduledThreadPoolExecutor.java:45-120
                    // delegates Tomcat container maintenance tasks to its internal ScheduledExecutorService.
                    "org.apache.tomcat.util.net.AbstractEndpoint",
                    "org.apache.tomcat.util.net.NioEndpoint",
                    "org.apache.tomcat.util.net.Nio2Endpoint",
                    "org.apache.tomcat.util.net.AprEndpoint",
                    "org.apache.tomcat.util.threads.ScheduledThreadPoolExecutor",
                    "org.apache.catalina.core.StandardServer",
                    "org.apache.catalina.core.ContainerBase",
                    // Eclipse Jetty 9.4.57: internal idle-timeout & connector executor dispatch.
                    "org.eclipse.jetty.util.thread.ScheduledExecutorScheduler",
                    "org.eclipse.jetty.util.thread.ExecutorThreadPool",
                    // XNIO 3.8.x: Undertow worker dispatch into Connectors.
                    "org.xnio.XnioWorker"
            )));

    // Exact reviewed HTTP server request-hosting ThreadPoolExecutor / STPE subclasses.
    // Note: org.xnio.XnioWorker$DefaultThreadPoolExecutor and
    // org.jboss.threads.ManagedThreadPoolExecutor are intentionally ABSENT because
    // they can execute arbitrary user Runnables without entering a hooked entry.
    static final Set<String> SERVER_POOL_CLASSES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    // Apache Tomcat 9.0.121 / 11.0.27 (org/apache/tomcat/util/threads/*):
                    // connector request pool (CoyoteAdapter) & async JULI file logger.
                    "org.apache.tomcat.util.threads.ThreadPoolExecutor",
                    "org.apache.tomcat.util.threads.ScheduledThreadPoolExecutor",
                    "org.apache.juli.AsyncFileHandler$LoggerExecutorService"
            )));

    // Exact reviewed server or JobGate-hooked task classes that never execute
    // arbitrary unhooked application callbacks.
    // Note: Spring's generic wrappers DelegatingErrorHandlingRunnable and
    // ReschedulingRunnable are intentionally ABSENT because they can wrap
    // arbitrary unhooked user Runnables.
    static final Set<String> SERVER_TASK_CLASSES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    // Apache Tomcat 9.0.121 / 11.0.27: container maintenance & NIO socket processors.
                    "org.apache.catalina.core.ContainerBase$ContainerBackgroundProcessorMonitor",
                    "org.apache.catalina.core.StandardServer$1",
                    "org.apache.catalina.util.TimeBucketCounterBase$MaintenanceMonitor",
                    "org.apache.catalina.util.TimeBucketCounter$MaintenanceMonitor",
                    "org.apache.tomcat.util.net.NioEndpoint$SocketProcessor",
                    "org.apache.tomcat.util.net.Nio2Endpoint$SocketProcessor",
                    // Eclipse Jetty 9.4.57: internal idle-timeout scheduled task.
                    "org.eclipse.jetty.util.thread.ScheduledExecutorScheduler$ScheduledFutureTask",
                    "org.eclipse.jetty.io.IdleTimeout$1",
                    // Spring 5.3.39 / 6.2.12 (ScheduledMethodRunnable.java): directly hooked into JobGate.
                    "org.springframework.scheduling.support.ScheduledMethodRunnable"
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

    // Reflective JDK 9+ StackWalker handle so caller lookup walks only top frames
    // lazily without materializing a full StackTraceElement[]; falls back cleanly
    // to Thread.getStackTrace() on JDK 8 while compiling with --release 8.
    private static final Object STACK_WALKER;
    private static final Method STACK_WALKER_WALK;
    private static final Method STACK_FRAME_GET_CLASS_NAME;

    static {
        Object walker = null;
        Method walkMethod = null;
        Method getClassNameMethod = null;
        try {
            Class<?> swClass = Class.forName("java.lang.StackWalker");
            Class<?> sfClass = Class.forName("java.lang.StackWalker$StackFrame");
            Class<?> optClass = Class.forName("java.lang.StackWalker$Option");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object showReflect = Enum.valueOf((Class<? extends Enum>) optClass, "SHOW_REFLECT_FRAMES");
            Method getInstance = swClass.getMethod("getInstance", optClass);
            walker = getInstance.invoke(null, showReflect);
            walkMethod = swClass.getMethod("walk", Function.class);
            getClassNameMethod = sfClass.getMethod("getClassName");
        } catch (Throwable ignored) {
            // JDK 8: fall back to Thread.currentThread().getStackTrace().
            walker = null;
            walkMethod = null;
            getClassNameMethod = null;
        }
        STACK_WALKER = walker;
        STACK_WALKER_WALK = walkMethod;
        STACK_FRAME_GET_CLASS_NAME = getClassNameMethod;
    }

    private SinkAllowlist() {
    }

    static boolean usesStackWalker() {
        return STACK_WALKER != null && STACK_WALKER_WALK != null && STACK_FRAME_GET_CLASS_NAME != null;
    }

    static boolean isAllowlistedThreadStart(Thread thread, String[] callerOut) {
        return isAllowlistedThreadStart(thread, null, callerOut);
    }

    static boolean isAllowlistedThreadStart(Thread thread, String explicitCaller, String[] callerOut) {
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
            String caller = explicitCaller != null ? explicitCaller : firstExternalCaller();
            if (callerOut != null && callerOut.length > 0) {
                callerOut[0] = caller;
            }
            return isAllowlistedJvmCaller(caller);
        }
        String caller = explicitCaller != null ? explicitCaller : firstExternalCaller();
        if (callerOut != null && callerOut.length > 0) {
            callerOut[0] = caller;
        }
        return isAllowlistedThreadStartCaller(caller);
    }

    static boolean isAllowlistedThreadStartCaller(String caller) {
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
        return isAllowlistedVirtualThreadStart(thread, null, callerOut);
    }

    static boolean isAllowlistedVirtualThreadStart(Thread thread, String explicitCaller, String[] callerOut) {
        if (thread == null) {
            return true;
        }
        if (RequestGate.isTrackedDispatchActive()) {
            return true;
        }
        String caller = explicitCaller != null ? explicitCaller : firstExternalCaller(true);
        if (callerOut != null && callerOut.length > 0) {
            callerOut[0] = caller;
        }
        // Tomcat virtual-thread request executor enters hooked CoyoteAdapter
        // (and AsyncContext.start enters RequestGate.handAsyncContext).
        if ("org.apache.tomcat.util.threads.VirtualThreadExecutor".equals(caller)
                || "org.apache.catalina.core.StandardVirtualThreadExecutor".equals(caller)) {
            return true;
        }
        return false;
    }

    static boolean isAllowlistedTpeExecute(Object executor, Object command, String[] callerOut) {
        return isAllowlistedTpeExecute(executor, command, null, callerOut);
    }

    static boolean isAllowlistedTpeExecute(Object executor, Object command, String explicitCaller, String[] callerOut) {
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
        String caller = explicitCaller != null ? explicitCaller : firstExternalCaller();
        if (callerOut != null && callerOut.length > 0) {
            callerOut[0] = caller;
        }
        return isAllowlistedPoolCaller(caller);
    }

    static boolean isAllowlistedStpeDelayedExecute(Object executor, Object task, String[] callerOut) {
        return isAllowlistedStpeDelayedExecute(executor, task, null, callerOut);
    }

    static boolean isAllowlistedStpeDelayedExecute(Object executor, Object task, String explicitCaller,
            String[] callerOut) {
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
        String caller = explicitCaller != null ? explicitCaller : firstExternalCaller();
        if (callerOut != null && callerOut.length > 0) {
            callerOut[0] = caller;
        }
        return isAllowlistedPoolCaller(caller);
    }

    static boolean isAllowlistedPoolCaller(String caller) {
        return caller != null && (SERVER_POOL_CALLERS.contains(caller)
                || INFRA_POOL_CALLERS.contains(caller)
                || isAllowlistedJvmCaller(caller));
    }

    static boolean isAllowlistedFjpSubmit(Object pool, Object task, String[] callerOut) {
        return isAllowlistedFjpSubmit(pool, task, null, callerOut);
    }

    static boolean isAllowlistedFjpSubmit(Object pool, Object task, String explicitCaller, String[] callerOut) {
        if (RequestGate.isTrackedDispatchActive() || RequestGate.isTrackedTask(task)) {
            return true;
        }
        // JDK 21 VirtualThread continuations run on VirtualThread.DEFAULT_SCHEDULER;
        // VirtualThread.start() was already audited (and latched/contaminated if
        // non-allowlisted) before submitting the continuation.
        if (task != null && "java.lang.VirtualThread$VThreadContinuation".equals(task.getClass().getName())) {
            return true;
        }
        String caller = explicitCaller != null ? explicitCaller : firstExternalCaller();
        if (callerOut != null && callerOut.length > 0) {
            callerOut[0] = caller;
        }
        if ("java.lang.VirtualThread".equals(caller)) {
            return true;
        }
        return false;
    }

    static boolean isAllowlistedTimerSched(Object timer, Object task, String[] callerOut) {
        return isAllowlistedTimerSched(timer, task, null, callerOut);
    }

    static boolean isAllowlistedTimerSched(Object timer, Object task, String explicitCaller, String[] callerOut) {
        if (RequestGate.isTrackedDispatchActive()) {
            return true;
        }
        String caller = explicitCaller != null ? explicitCaller : firstExternalCaller();
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

    static String firstExternalCaller() {
        return firstExternalCaller(false);
    }

    // Walks the current thread's stack (lazily via JDK 9+ StackWalker, or via
    // Thread.getStackTrace() on JDK 8) to find the first caller outside the
    // sink bridge, Keploy's own RequestGate/SinkAllowlist, and the JDK sink
    // wrapper methods themselves (e.g. ThreadPoolExecutor.addWorker -> execute,
    // AbstractExecutorService.submit -> execute, Timer.schedule -> sched).
    // Reflection (java.lang.reflect.Method.invoke) and MethodHandles are NEVER
    // skipped — they return their own class name, which is NOT in any allowlist,
    // so reflective/MethodHandle dispatch fails closed by construction.
    static String firstExternalCaller(final boolean skipVirtualThreadFrames) {
        if (STACK_WALKER != null && STACK_WALKER_WALK != null && STACK_FRAME_GET_CLASS_NAME != null) {
            try {
                Function<Stream<?>, String> finder = new Function<Stream<?>, String>() {
                    @Override
                    public String apply(Stream<?> stream) {
                        final boolean[] seenSelf = {false};
                        Optional<String> found = stream
                                .map(SinkAllowlist::frameClassName)
                                .filter(cn -> {
                                    if (cn == null) {
                                        return false;
                                    }
                                    if (!seenSelf[0]) {
                                        if ("io.keploy.dedup.SinkAllowlist".equals(cn)) {
                                            seenSelf[0] = true;
                                        }
                                        return false;
                                    }
                                    return !isSkippedCallerFrame(cn, skipVirtualThreadFrames);
                                })
                                .findFirst();
                        return found.orElse(null);
                    }
                };
                return (String) STACK_WALKER_WALK.invoke(STACK_WALKER, finder);
            } catch (Throwable ignored) {
                // Fall back to getStackTrace below.
            }
        }
        StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        for (int i = 1; i < frames.length; i++) {
            String cn = frames[i].getClassName();
            if (!isSkippedCallerFrame(cn, skipVirtualThreadFrames)) {
                return cn;
            }
        }
        return null;
    }

    private static String frameClassName(Object stackFrame) {
        try {
            return (String) STACK_FRAME_GET_CLASS_NAME.invoke(stackFrame);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isSkippedCallerFrame(String cn, boolean skipVirtualThreadFrames) {
        return "io.keploy.dedup.bootstrap.KeploySinkBridge".equals(cn)
                || "io.keploy.dedup.SinkAllowlist".equals(cn)
                || "io.keploy.dedup.SinkAllowlist$1".equals(cn)
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
                || "java.util.Timer".equals(cn);
    }
}
