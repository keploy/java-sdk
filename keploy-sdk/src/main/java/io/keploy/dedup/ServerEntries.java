package io.keploy.dedup;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.net.URI;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * ServerEntries instruments:
 * <ul>
 *   <li>Tomcat, Jetty, and Undertow request entries into {@link RequestGate}.</li>
 *   <li>Quartz, Spring {@code @Scheduled}, and Spring Kafka/AMQP/JMS message
 *       listeners into {@link JobGate}.</li>
 *   <li>Spring async executors into {@link RequestGate}.</li>
 *   <li>Application-only JDK executor, {@code CompletableFuture}
 *       ({@code runAsync}, {@code supplyAsync}, and {@code *Async} instance
 *       methods), {@code ScheduledExecutorService.schedule*},
 *       {@code ExecutorService.invokeAny}, and {@code Thread.start} call sites
 *       into {@link RequestGate} static helpers.</li>
 * </ul>
 */
final class ServerEntries implements ClassFileTransformer {

    private static final Logger LOGGER = Logger.getLogger(KeployDedupAgent.class.getName());
    private static final String GATE = Type.getInternalName(RequestGate.class);
    private static final String JOB_GATE = Type.getInternalName(JobGate.class);
    static final String RENAMED = "keploy$request$";
    static final String JOB_RENAMED = "keploy$job$";
    static final String ASYNC_RENAMED = "keploy$async$";
    private static final String RUNNABLE = "Ljava/lang/Runnable;";
    private static final String CALLABLE = "Ljava/util/concurrent/Callable;";
    // The type of the executor a task is for, when a method takes it as an
    // argument, as @Async's interceptor's doSubmit does.
    private static final String EXECUTOR = "Lorg/springframework/core/task/AsyncTaskExecutor;";

    // The servers' request entries: the class, and the methods a request
    // runs in, the response finished when they return.
    private static final Map<String, Entry> ENTRIES = new HashMap<>();

    static {
        // Tomcat 8.5 to 11 (Spring Boot's default): service runs a request,
        // filters, servlet and finished response; asyncDispatch each later
        // dispatch of an async one.
        add("Tomcat", "org/apache/catalina/connector/CoyoteAdapter", "service", "asyncDispatch");
        // Jetty 9.4 to 11: handle runs a request and each async dispatch of it.
        add("Jetty", "org/eclipse/jetty/server/HttpChannel", "handle");
        // Jetty 12, servlets of each EE level (12.1's EE 11 too, as Spring
        // Boot 4 runs it). Its core HttpChannel is an interface, so it is
        // passed over as the 9.4-11 entry.
        add("Jetty", "org/eclipse/jetty/ee11/servlet/ServletChannel", "handle");
        add("Jetty", "org/eclipse/jetty/ee10/servlet/ServletChannel", "handle");
        add("Jetty", "org/eclipse/jetty/ee9/nested/HttpChannel", "handle");
        add("Jetty", "org/eclipse/jetty/ee8/nested/HttpChannel", "handle");
        // Undertow: each dispatch of a request, on the IO thread and on the
        // worker that runs its servlet chain.
        add("Undertow", "io/undertow/server/Connectors", "executeRootHandler");
        // Scheduled jobs, whose runs a test's window is checked for: each
        // Quartz job (1.x and 2.x, and Spring's Quartz support) runs in a
        // JobRunShell, and each Spring @Scheduled method in a
        // ScheduledMethodRunnable, on the scheduler's threads.
        addJob("Quartz", "org/quartz/core/JobRunShell", "quartz", "run");
        addJob("Spring", "org/springframework/scheduling/support/ScheduledMethodRunnable", "scheduled", "run");
        // Message listeners (F10 listener contamination): an inbound message
        // delivered on a consumer thread during a test's window mixes the
        // listener's probes into the test's record, so mark the test contaminated.
        addJob("Kafka", "org/springframework/kafka/listener/KafkaMessageListenerContainer$ListenerConsumer",
                "kafka", "invokeRecordListener", "invokeBatchListener", "invokeOnMessage");
        addJob("RabbitMQ", "org/springframework/amqp/rabbit/listener/AbstractMessageListenerContainer",
                "listener", "doInvokeListener");
        addJob("JMS", "org/springframework/jms/listener/AbstractMessageListenerContainer",
                "listener", "doInvokeListener");
        // Spring's executors a request hands work to.
        addAsync("org/springframework/core/task/SimpleAsyncTaskExecutor", "doExecute", "submitCompletable");
        addAsync("org/springframework/scheduling/concurrent/ThreadPoolTaskExecutor", "execute", "submit",
                "submitListenable", "submitCompletable");
        addAsync("org/springframework/scheduling/concurrent/ThreadPoolTaskScheduler", "execute", "submit",
                "submitListenable", "submitCompletable");
        addAsync("org/springframework/scheduling/concurrent/ConcurrentTaskExecutor", "execute", "submit",
                "submitListenable", "submitCompletable");
        addAsync("org/springframework/aop/interceptor/AsyncExecutionAspectSupport", "doSubmit");
    }

    // Classes of servers that are not counted, so that the log can name them
    // and HELLO/caps can report windowCut=false.
    private static final Map<String, String> UNCOUNTED = new HashMap<>();

    static {
        UNCOUNTED.put("io/netty/handler/codec/http/HttpServerCodec", "Netty");
        UNCOUNTED.put("reactor/netty/http/server/HttpServer", "Reactor Netty");
        UNCOUNTED.put("io/grpc/netty/NettyServer", "gRPC");
        UNCOUNTED.put("io/grpc/netty/shaded/io/grpc/netty/NettyServer", "gRPC");
        UNCOUNTED.put("io/grpc/internal/ServerImpl", "gRPC");
        UNCOUNTED.put("io/vertx/core/http/impl/HttpServerImpl", "Vert.x");
        UNCOUNTED.put("io/micronaut/http/server/netty/NettyHttpServer", "Micronaut");
        UNCOUNTED.put("sun/net/httpserver/ServerImpl", "JDK HttpServer");
    }

    // Classes of messaging consumers that ServerEntries does not hook into
    // JobGate, so HELLO/caps can honestly report listenerContamination=false.
    private static final Map<String, String> UNHOOKED_CONSUMERS = new HashMap<>();

    static {
        UNHOOKED_CONSUMERS.put("org/apache/kafka/streams/KafkaStreams", "Kafka Streams");
        UNHOOKED_CONSUMERS.put("reactor/kafka/receiver/KafkaReceiver", "Reactor Kafka");
        UNHOOKED_CONSUMERS.put("reactor/kafka/receiver/internals/DefaultKafkaReceiver", "Reactor Kafka");
        UNHOOKED_CONSUMERS.put("io/awspring/cloud/sqs/listener/SqsMessageListenerContainer", "Spring Cloud SQS");
        UNHOOKED_CONSUMERS.put("com/amazonaws/services/sqs/AmazonSQSClient", "AWS SQS");
        UNHOOKED_CONSUMERS.put("software/amazon/awssdk/services/sqs/DefaultSqsClient", "AWS SQS");
        UNHOOKED_CONSUMERS.put("software/amazon/awssdk/services/sqs/DefaultSqsAsyncClient", "AWS SQS Async");
        UNHOOKED_CONSUMERS.put("org/springframework/data/redis/listener/RedisMessageListenerContainer",
                "Spring Data Redis");
        UNHOOKED_CONSUMERS.put("org/apache/pulsar/client/impl/ConsumerImpl", "Apache Pulsar");
        UNHOOKED_CONSUMERS.put("org/apache/pulsar/client/impl/MultiTopicsConsumerImpl", "Apache Pulsar");
        UNHOOKED_CONSUMERS.put("com/google/cloud/pubsub/v1/Subscriber", "GCP Pub/Sub");
    }

    private static final Set<String> RAW_CONSUMER_OWNERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "org/apache/kafka/clients/consumer/KafkaConsumer",
            "org/apache/kafka/clients/consumer/Consumer",
            "com/rabbitmq/client/Channel",
            "javax/jms/MessageConsumer",
            "jakarta/jms/MessageConsumer")));

    private static final Set<String> COUNTED = new CopyOnWriteArraySet<>();
    private static final Set<String> SEEN_UNCOUNTED = new CopyOnWriteArraySet<>();
    private static final Set<String> SEEN_UNHOOKED_CONSUMERS = new CopyOnWriteArraySet<>();
    private static final Set<String> HOOKED_SPRING_CLASSES = new CopyOnWriteArraySet<>();
    private static volatile boolean serverHookFailed;
    private static volatile boolean listenerHookFailed;
    private static volatile boolean jpmsGuardTriggered;
    private static volatile boolean appRewriteFailed;

    // Per-loader cache for seesGate(loader) to avoid repeated Class.forName
    // calls and ClassNotFoundExceptions during startup class loading.
    private static final Map<ClassLoader, Boolean> SEES_GATE = new WeakHashMap<>();
    // On Java 9+, normalized code-source locations of boot-layer named modules
    // that do not read RequestGate's unnamed module: rewriting classes inside
    // those named modules would throw IllegalAccessError at call sites. Empty
    // on Java 8 or when no unreadable named modules exist.
    private static final Set<String> UNREADABLE_MODULE_LOCATIONS = findUnreadableModuleLocations();

    // Constant-pool class owners targeted by wrapExecutorCalls.
    private static final Set<String> TARGET_EXECUTOR_OWNERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "java/util/concurrent/Executor",
            "java/util/concurrent/ExecutorService",
            "java/util/concurrent/ScheduledExecutorService",
            "java/util/concurrent/AbstractExecutorService",
            "java/util/concurrent/ThreadPoolExecutor",
            "java/util/concurrent/ScheduledThreadPoolExecutor",
            "java/util/concurrent/ForkJoinPool",
            "java/util/concurrent/ForkJoinTask",
            "java/util/concurrent/ExecutorCompletionService",
            "java/util/concurrent/CompletableFuture",
            "java/util/concurrent/CompletionStage",
            "java/lang/Thread",
            "java/lang/Thread$Builder",
            "java/lang/Thread$Builder$OfVirtual",
            "java/lang/Thread$Builder$OfPlatform",
            "java/util/Timer",
            "org/springframework/scheduling/TaskScheduler",
            "com/google/common/util/concurrent/Futures",
            "com/google/common/util/concurrent/ListenableFuture",
            "reactor/core/publisher/Mono",
            "reactor/core/publisher/Flux",
            "reactor/core/publisher/ParallelFlux")));

    // Constant-pool method names (CONSTANT_NameAndType, tag 12) targeted by
    // wrapExecutorCalls for non-JDK owners (custom executor subtypes, Thread
    // subclasses, Spring TaskScheduler, Guava, Reactor subscribe, method
    // references, and raw consumer polling). Methods whose owners are always
    // in TARGET_EXECUTOR_OWNERS (CompletableFuture/CompletionStage/remove/
    // shutdownNow) are matched by tag 7 above to avoid unnecessary ASM passes.
    private static final Set<String> TARGET_ASYNC_METHOD_NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "execute", "submit", "submitListenable", "submitCompletable",
            "schedule", "scheduleAtFixedRate", "scheduleWithFixedDelay",
            "invokeAny", "fork", "subscribe", "start", "startVirtualThread",
            "addListener", "addCallback", "transform", "transformAsync", "catching", "catchingAsync",
            "poll", "basicConsume", "receive", "setMessageListener")));

    // CompletableFuture *Async instance methods rewritten to RequestGate.
    private static final Map<String, Set<String>> CF_ASYNC_INSTANCE_METHODS =
            buildStageAsyncMethods("Ljava/util/concurrent/CompletableFuture;");
    // CompletableFuture plain dependent instance methods rewritten to RequestGate.
    private static final Map<String, Set<String>> CF_SYNC_INSTANCE_METHODS =
            buildStageSyncMethods("Ljava/util/concurrent/CompletableFuture;");
    // CompletionStage *Async interface methods rewritten to RequestGate.
    private static final Map<String, Set<String>> CS_ASYNC_INSTANCE_METHODS =
            buildStageAsyncMethods("Ljava/util/concurrent/CompletionStage;");
    // CompletionStage plain dependent interface methods rewritten to RequestGate.
    private static final Map<String, Set<String>> CS_SYNC_INSTANCE_METHODS =
            buildStageSyncMethods("Ljava/util/concurrent/CompletionStage;");

    private static Map<String, Set<String>> buildStageAsyncMethods(String ret) {
        Map<String, Set<String>> m = new HashMap<>();
        String cs = "Ljava/util/concurrent/CompletionStage;";
        String ex = "Ljava/util/concurrent/Executor;";
        String fn = "Ljava/util/function/Function;";
        String cons = "Ljava/util/function/Consumer;";
        String run = "Ljava/lang/Runnable;";
        String bifn = "Ljava/util/function/BiFunction;";
        String bicons = "Ljava/util/function/BiConsumer;";
        m.put("thenApplyAsync", setOf("(" + fn + ")" + ret, "(" + fn + ex + ")" + ret));
        m.put("thenAcceptAsync", setOf("(" + cons + ")" + ret, "(" + cons + ex + ")" + ret));
        m.put("thenRunAsync", setOf("(" + run + ")" + ret, "(" + run + ex + ")" + ret));
        m.put("thenComposeAsync", setOf("(" + fn + ")" + ret, "(" + fn + ex + ")" + ret));
        m.put("thenCombineAsync", setOf("(" + cs + bifn + ")" + ret, "(" + cs + bifn + ex + ")" + ret));
        m.put("thenAcceptBothAsync", setOf("(" + cs + bicons + ")" + ret, "(" + cs + bicons + ex + ")" + ret));
        m.put("runAfterBothAsync", setOf("(" + cs + run + ")" + ret, "(" + cs + run + ex + ")" + ret));
        m.put("applyToEitherAsync", setOf("(" + cs + fn + ")" + ret, "(" + cs + fn + ex + ")" + ret));
        m.put("acceptEitherAsync", setOf("(" + cs + cons + ")" + ret, "(" + cs + cons + ex + ")" + ret));
        m.put("runAfterEitherAsync", setOf("(" + cs + run + ")" + ret, "(" + cs + run + ex + ")" + ret));
        m.put("handleAsync", setOf("(" + bifn + ")" + ret, "(" + bifn + ex + ")" + ret));
        m.put("whenCompleteAsync", setOf("(" + bicons + ")" + ret, "(" + bicons + ex + ")" + ret));
        m.put("exceptionallyAsync", setOf("(" + fn + ")" + ret, "(" + fn + ex + ")" + ret));
        m.put("exceptionallyComposeAsync", setOf("(" + fn + ")" + ret, "(" + fn + ex + ")" + ret));
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, Set<String>> buildStageSyncMethods(String ret) {
        Map<String, Set<String>> m = new HashMap<>();
        String cs = "Ljava/util/concurrent/CompletionStage;";
        String fn = "Ljava/util/function/Function;";
        String cons = "Ljava/util/function/Consumer;";
        String run = "Ljava/lang/Runnable;";
        String bifn = "Ljava/util/function/BiFunction;";
        String bicons = "Ljava/util/function/BiConsumer;";
        m.put("thenApply", setOf("(" + fn + ")" + ret));
        m.put("thenAccept", setOf("(" + cons + ")" + ret));
        m.put("thenRun", setOf("(" + run + ")" + ret));
        m.put("thenCompose", setOf("(" + fn + ")" + ret));
        m.put("thenCombine", setOf("(" + cs + bifn + ")" + ret));
        m.put("thenAcceptBoth", setOf("(" + cs + bicons + ")" + ret));
        m.put("runAfterBoth", setOf("(" + cs + run + ")" + ret));
        m.put("applyToEither", setOf("(" + cs + fn + ")" + ret));
        m.put("acceptEither", setOf("(" + cs + cons + ")" + ret));
        m.put("runAfterEither", setOf("(" + cs + run + ")" + ret));
        m.put("handle", setOf("(" + bifn + ")" + ret));
        m.put("whenComplete", setOf("(" + bicons + ")" + ret));
        m.put("exceptionally", setOf("(" + fn + ")" + ret));
        m.put("exceptionallyCompose", setOf("(" + fn + ")" + ret));
        return Collections.unmodifiableMap(m);
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    // The classes this changed as they loaded, by their loader: the ones it
    // changes again when another agent retransforms them.
    private final Map<ClassLoader, Set<String>> changed = new WeakHashMap<>();
    // Application classes whose executor call sites were rewritten on load,
    // by their loader: re-applied if another agent retransforms them.
    private final Map<ClassLoader, Set<String>> rewritten = new WeakHashMap<>();
    // Whether servers' requests count (END waits for them); jobs' runs
    // count either way.
    private final boolean requests;
    // Allow-list of indexed application VM class names eligible for JDK
    // executor call-site rewriting; empty when app-only indexing was not run
    // eagerly (e.g. external JaCoCo attached) or failed.
    private final Set<String> appClasses;
    // Lightweight package prefix ("com/acme/") derived from AppClasses when
    // appClasses is empty because an external JaCoCo agent skipped premain
    // index loading; null when unavailable.
    private final String appPackagePrefix;

    ServerEntries() {
        this(true, Collections.<String>emptySet(), null);
    }

    ServerEntries(boolean requests) {
        this(requests, Collections.<String>emptySet(), null);
    }

    ServerEntries(boolean requests, Set<String> appClasses) {
        this(requests, appClasses, null);
    }

    ServerEntries(boolean requests, Set<String> appClasses, String appPackagePrefix) {
        this.requests = requests;
        this.appClasses = appClasses == null || appClasses.isEmpty()
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new HashSet<>(appClasses));
        this.appPackagePrefix = appPackagePrefix == null || appPackagePrefix.isEmpty() ? null : appPackagePrefix;
    }

    private static void add(String server, String className, String... methods) {
        ENTRIES.put(className, new Entry(server, null, false, new HashSet<>(Arrays.asList(methods))));
    }

    private static void addJob(String scheduler, String className, String kind, String... methods) {
        ENTRIES.put(className, new Entry(scheduler, kind, false, new HashSet<>(Arrays.asList(methods))));
    }

    private static void addAsync(String className, String... methods) {
        ENTRIES.put(className, new Entry("Spring", null, true, new HashSet<>(Arrays.asList(methods))));
    }

    // counted names the servers whose requests count, in the order their
    // classes loaded; empty when none did.
    static List<String> counted() {
        return new ArrayList<>(COUNTED);
    }

    // countsNone says whether no server's requests count.
    static boolean countsNone() {
        return COUNTED.isEmpty();
    }

    static boolean hasUncountedServer() {
        return !SEEN_UNCOUNTED.isEmpty();
    }

    static boolean hasUnhookedConsumer() {
        return !SEEN_UNHOOKED_CONSUMERS.isEmpty();
    }

    static boolean isSpringExecutorHooked(String binaryClassName) {
        return HOOKED_SPRING_CLASSES.contains(binaryClassName);
    }

    static void markSpringExecutorHookedForTests(String binaryClassName) {
        HOOKED_SPRING_CLASSES.add(binaryClassName);
    }

    static void unmarkSpringExecutorHookedForTests(String binaryClassName) {
        HOOKED_SPRING_CLASSES.remove(binaryClassName);
    }

    static boolean hadServerHookFailure() {
        return serverHookFailed;
    }

    static boolean hadListenerHookFailure() {
        return listenerHookFailed;
    }

    static boolean wasJpmsGuardTriggered() {
        return jpmsGuardTriggered;
    }

    static boolean hadAppRewriteFailure() {
        return appRewriteFailed;
    }

    static void markJpmsGuardTriggered() {
        jpmsGuardTriggered = true;
    }

    // Snapshot/restore helpers for unit tests exercising dynamic HELLO/caps flags.
    static Set<String> snapshotCounted() {
        return new HashSet<>(COUNTED);
    }

    static void restoreCounted(Set<String> snapshot) {
        COUNTED.clear();
        if (snapshot != null) {
            COUNTED.addAll(snapshot);
        }
    }

    static Set<String> snapshotUncounted() {
        return new HashSet<>(SEEN_UNCOUNTED);
    }

    static void restoreUncounted(Set<String> snapshot) {
        SEEN_UNCOUNTED.clear();
        if (snapshot != null) {
            SEEN_UNCOUNTED.addAll(snapshot);
        }
    }

    static Set<String> snapshotUnhookedConsumers() {
        return new HashSet<>(SEEN_UNHOOKED_CONSUMERS);
    }

    static void restoreUnhookedConsumers(Set<String> snapshot) {
        SEEN_UNHOOKED_CONSUMERS.clear();
        if (snapshot != null) {
            SEEN_UNHOOKED_CONSUMERS.addAll(snapshot);
        }
    }

    static void setHookStatusForTests(boolean serverFailed, boolean listenerFailed, boolean jpmsSkipped) {
        setHookStatusForTests(serverFailed, listenerFailed, jpmsSkipped, false);
    }

    static void setHookStatusForTests(boolean serverFailed, boolean listenerFailed, boolean jpmsSkipped,
            boolean rewriteFailed) {
        serverHookFailed = serverFailed;
        listenerHookFailed = listenerFailed;
        jpmsGuardTriggered = jpmsSkipped;
        appRewriteFailed = rewriteFailed;
        SEEN_UNHOOKED_CONSUMERS.clear();
    }

    // uncounted names the servers whose requests do not count that the app
    // loaded, as Netty.
    static List<String> uncounted() {
        return new ArrayList<>(SEEN_UNCOUNTED);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (classBeingRedefined != null) {
            return changedAgain(loader, className, classfileBuffer);
        }
        Entry entry = ENTRIES.get(className);
        if (entry == null) {
            String uncounted = UNCOUNTED.get(className);
            if (uncounted != null) {
                SEEN_UNCOUNTED.add(uncounted);
            }
            String unhookedConsumer = UNHOOKED_CONSUMERS.get(className);
            if (unhookedConsumer != null) {
                SEEN_UNHOOKED_CONSUMERS.add(unhookedConsumer);
            }
            if (shouldRewriteExecutorCalls(className)) {
                if (!canAccessGate(loader, protectionDomain)) {
                    if (seesGate(loader)) {
                        jpmsGuardTriggered = true;
                    } else {
                        appRewriteFailed = true;
                    }
                    scanRawConsumerCalls(classfileBuffer);
                    return null;
                }
                try {
                    byte[] updated = wrapExecutorCalls(classfileBuffer);
                    if (updated != null) {
                        recordRewritten(loader, className);
                    }
                    return updated;
                } catch (Throwable t) {
                    appRewriteFailed = true;
                    LOGGER.log(Level.FINE, "Keploy dedup skipped executor call-site wrap for " + className, t);
                }
            } else if (className != null && isRewriteAllowedPackage(className)
                    && !isKnownMessagingPoolWrapper(className)) {
                // Even when KEPLOY_JAVA_ASYNC_CALLSITES=off or no app allow-list
                // is configured, read-only CP scan still detects direct raw
                // Kafka/RabbitMQ/JMS consumer calls so listenerContamination
                // stays honest without modifying any bytecode (skipping known
                // JMS/RabbitMQ pooling/recovery wrappers used by Spring Boot).
                scanRawConsumerCalls(classfileBuffer);
            }
            return null;
        }
        // With the wait off, servers are left as they are, and so no task
        // counts as a request's; the executors still count a task a job's
        // run hands off as that run (RequestGate.hand).
        if (entry.job == null && !entry.async && !requests) {
            return null;
        }
        String name = className.replace('/', '.');
        try {
            if (isInterface(classfileBuffer)) {
                // Jetty 12's core HttpChannel: no code of its own to count,
                // and its requests count in a ServletChannel.
                LOGGER.log(Level.FINE, "Keploy dedup passes over " + name + ": an interface");
                return null;
            }
            if (!seesGate(loader)) {
                noteEntryHookFailure(entry);
                LOGGER.log(Level.WARNING, "Keploy dedup does not count " + entry.what() + ": the class loader of "
                        + name + " does not see keploy-sdk.jar's classes, so " + entry.lost(requests));
                return null;
            }
            // Bytes that already carry the change (another agent's copy of
            // the class as this changed it) count as they are.
            boolean carries = carriesChange(classfileBuffer);
            byte[] wrapped = carries ? null : wrap(classfileBuffer, entry);
            if (!carries && wrapped == null) {
                noteEntryHookFailure(entry);
                LOGGER.log(Level.WARNING, "Keploy dedup does not count " + entry.what() + ": " + name
                        + " has no method " + entry.methods + " to count them in");
                return null;
            }
            if (entry.job == null && !entry.async) {
                COUNTED.add(entry.server);
            } else if (entry.async) {
                HOOKED_SPRING_CLASSES.add(name);
            }
            synchronized (changed) {
                Set<String> names = changed.get(loader);
                if (names == null) {
                    names = new HashSet<>();
                    changed.put(loader, names);
                }
                names.add(className);
            }
            LOGGER.log(Level.INFO, "Keploy dedup counts " + entry.what() + " (" + name + "): "
                    + entry.effect(requests));
            return wrapped;
        } catch (Throwable t) {
            // Never fail the server's class: it loads unchanged.
            noteEntryHookFailure(entry);
            LOGGER.log(Level.WARNING, "Keploy dedup does not count " + entry.what() + ": " + name
                    + " could not be changed", t);
            return null;
        }
    }

    private static void noteEntryHookFailure(Entry entry) {
        if (entry.job == null && !entry.async) {
            serverHookFailed = true;
        } else if ("kafka".equals(entry.job) || "listener".equals(entry.job)) {
            listenerHookFailed = true;
        }
    }

    private void recordRewritten(ClassLoader loader, String className) {
        synchronized (rewritten) {
            Set<String> names = rewritten.get(loader);
            if (names == null) {
                names = new HashSet<>();
                rewritten.put(loader, names);
            }
            names.add(className);
        }
    }

    private boolean wasRewritten(ClassLoader loader, String className) {
        if (!shouldRewriteExecutorCalls(className)) {
            return false;
        }
        synchronized (rewritten) {
            Set<String> names = rewritten.get(loader);
            return names != null && names.contains(className);
        }
    }

    private byte[] changedAgain(ClassLoader loader, String className, byte[] classfile) {
        Entry entry = ENTRIES.get(className);
        if (entry == null) {
            if (wasRewritten(loader, className)) {
                try {
                    return wrapExecutorCalls(classfile);
                } catch (Throwable t) {
                    LOGGER.log(Level.FINE, "Keploy dedup skipped executor call-site re-wrap for " + className, t);
                }
            }
            // Most classes an agent retransforms: no lock taken for them.
            return null;
        }
        synchronized (changed) {
            Set<String> names = changed.get(loader);
            if (names == null || !names.contains(className)) {
                return null;
            }
        }
        String name = className.replace('/', '.');
        try {
            if (carriesChange(classfile)) {
                return null;
            }
            byte[] wrapped = wrap(classfile, entry);
            if (wrapped == null) {
                LOGGER.log(Level.WARNING, "Keploy dedup cannot count " + entry.what() + " in " + name
                        + " as another agent changes it: it has no method " + entry.methods + " any more, so"
                        + " the JVM refuses that agent's change");
            }
            return wrapped;
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Keploy dedup cannot count " + entry.what() + " in " + name
                    + " as another agent changes it, so the JVM refuses that agent's change", t);
            return null;
        }
    }

    private static boolean seesGate(ClassLoader loader) {
        // The bootstrap loader (null) does not see keploy-sdk.jar.
        if (loader == null) {
            return false;
        }
        Boolean cached;
        synchronized (SEES_GATE) {
            cached = SEES_GATE.get(loader);
        }
        if (cached != null) {
            return cached;
        }
        boolean sees;
        try {
            sees = Class.forName(RequestGate.class.getName(), false, loader) == RequestGate.class;
        } catch (Throwable t) {
            sees = false;
        }
        synchronized (SEES_GATE) {
            SEES_GATE.put(loader, sees);
        }
        return sees;
    }

    private static boolean isInterface(byte[] classfile) {
        return (new ClassReader(classfile).getAccess() & Opcodes.ACC_INTERFACE) != 0;
    }

    static boolean carriesChange(byte[] classfile) {
        final boolean[] carries = {false};
        new ClassReader(classfile).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if (name.startsWith(RENAMED) || name.startsWith(JOB_RENAMED) || name.startsWith(ASYNC_RENAMED)) {
                    carries[0] = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return carries[0];
    }

    static byte[] wrap(byte[] classfile, Set<String> methods) {
        return wrap(classfile, methods, null, false);
    }

    private static byte[] wrap(byte[] classfile, Entry entry) {
        return wrap(classfile, entry.methods, entry.job, entry.async);
    }

    boolean shouldRewriteExecutorCalls(String className) {
        if (className == null || !isRewriteAllowedPackage(className)) {
            return false;
        }
        if (!appClasses.isEmpty()) {
            // Exact indexed app class allow-list: do NOT apply shaded-package
            // filtering; isRewriteAllowedPackage still blocks framework/library
            // classes if misconfigured in KEPLOY_JAVA_CLASS_DIRS while allowing
            // org/springframework/samples/... (e.g. Spring PetClinic).
            return appClasses.contains(className);
        }
        return appPackagePrefix != null
                && className.startsWith(appPackagePrefix)
                && !isShadedOrRelocated(className);
    }

    private static boolean isKnownMessagingPoolWrapper(String className) {
        return className.startsWith("org/messaginghub/pooled/")
                || className.startsWith("org/apache/activemq/jms/pool/")
                || className.startsWith("com/rabbitmq/client/impl/recovery/");
    }

    private static boolean isInternalOrAgentClass(String className) {
        return className.startsWith("java/")
                || className.startsWith("javax/")
                || className.startsWith("sun/")
                || className.startsWith("jdk/")
                || className.startsWith("com/sun/")
                || className.startsWith("io/keploy/")
                || className.startsWith("org/jacoco/")
                || className.startsWith("org/newsclub/");
    }

    static boolean canAccessGate(ClassLoader loader, ProtectionDomain protectionDomain) {
        if (!seesGate(loader)) {
            return false;
        }
        if (protectionDomain != null && !UNREADABLE_MODULE_LOCATIONS.isEmpty()) {
            CodeSource cs = protectionDomain.getCodeSource();
            URL location = cs == null ? null : cs.getLocation();
            if (location != null) {
                try {
                    String normalized = normalizeLocation(location.toURI());
                    if (normalized != null && UNREADABLE_MODULE_LOCATIONS.contains(normalized)) {
                        return false;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return true;
    }

    static Set<String> unreadableModuleLocations() {
        return UNREADABLE_MODULE_LOCATIONS;
    }

    static boolean isLocationInUnreadableModule(String location) {
        if (location == null || location.isEmpty() || UNREADABLE_MODULE_LOCATIONS.isEmpty()) {
            return false;
        }
        int bang = location.indexOf('!');
        String root = bang > 0 ? location.substring(0, bang) : location;
        try {
            String normalized = normalizeLocation(new File(root).toURI());
            if (normalized != null && UNREADABLE_MODULE_LOCATIONS.contains(normalized)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return UNREADABLE_MODULE_LOCATIONS.contains(root);
    }

    private static String normalizeLocation(URI uri) {
        if (uri == null) {
            return null;
        }
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            try {
                return new File(uri).getCanonicalPath();
            } catch (Throwable ignored) {
                try {
                    return new File(uri).getAbsolutePath();
                } catch (Throwable ignored2) {
                }
            }
        }
        return uri.normalize().toString();
    }

    private static Set<String> findUnreadableModuleLocations() {
        try {
            java.lang.reflect.Method getModule = Class.class.getMethod("getModule");
            Object gateModule = getModule.invoke(RequestGate.class);
            if (gateModule == null) {
                return Collections.emptySet();
            }
            Class<?> moduleClass = gateModule.getClass();
            java.lang.reflect.Method isNamed = moduleClass.getMethod("isNamed");
            java.lang.reflect.Method canRead = moduleClass.getMethod("canRead", moduleClass);
            java.lang.reflect.Method getName = moduleClass.getMethod("getName");
            Class<?> layerClass = Class.forName("java.lang.ModuleLayer");
            Object bootLayer = layerClass.getMethod("boot").invoke(null);
            Set<?> modules = (Set<?>) layerClass.getMethod("modules").invoke(bootLayer);
            Object config = layerClass.getMethod("configuration").invoke(bootLayer);
            java.lang.reflect.Method findModule = config.getClass().getMethod("findModule", String.class);
            Set<String> unreadable = new HashSet<>();
            for (Object mod : modules) {
                if (!Boolean.TRUE.equals(isNamed.invoke(mod))) {
                    continue;
                }
                if (Boolean.TRUE.equals(canRead.invoke(mod, gateModule))) {
                    continue;
                }
                String modName = (String) getName.invoke(mod);
                Object optResolved = findModule.invoke(config, modName);
                if (optResolved == null) {
                    continue;
                }
                if (Boolean.TRUE.equals(optResolved.getClass().getMethod("isPresent").invoke(optResolved))) {
                    Object resolved = optResolved.getClass().getMethod("get").invoke(optResolved);
                    Object ref = resolved.getClass().getMethod("reference").invoke(resolved);
                    Object optLoc = ref.getClass().getMethod("location").invoke(ref);
                    if (optLoc != null
                            && Boolean.TRUE.equals(optLoc.getClass().getMethod("isPresent").invoke(optLoc))) {
                        Object uri = optLoc.getClass().getMethod("get").invoke(optLoc);
                        if (uri instanceof URI) {
                            String normalized = normalizeLocation((URI) uri);
                            if (normalized != null) {
                                unreadable.add(normalized);
                            }
                        }
                    }
                }
            }
            return unreadable.isEmpty() ? Collections.<String>emptySet() : Collections.unmodifiableSet(unreadable);
        } catch (Throwable t) {
            // Java 8 (no Module/ModuleLayer) or restricted reflection.
            return Collections.emptySet();
        }
    }

    static boolean isShadedOrRelocated(String className) {
        return className.contains("/shaded/")
                || className.contains("/relocated/")
                || className.contains("/repackaged/")
                || className.startsWith("shaded/")
                || className.startsWith("relocated/");
    }

    // Belt-and-suspenders guard in case KEPLOY_JAVA_CLASS_DIRS or
    // appPackagePrefix includes framework/library classes; note
    // org/apache/ is NOT blanket-excluded so Apache-hosted applications (e.g.
    // org/apache/fineract/) remain eligible. Shaded/relocated filtering is
    // applied only in prefix mode (not exact appClasses index mode).
    static boolean isRewriteAllowedPackage(String className) {
        return className != null
                && !isInternalOrAgentClass(className)
                && !className.startsWith("io/netty/")
                && !className.startsWith("reactor/")
                && !className.startsWith("okhttp3/")
                && !className.startsWith("okio/")
                && !className.startsWith("io/lettuce/")
                && !className.startsWith("io/grpc/")
                && !className.startsWith("software/amazon/")
                && !className.startsWith("com/amazonaws/")
                && (!className.startsWith("org/springframework/")
                        || className.startsWith("org/springframework/samples/"))
                && !className.startsWith("org/apache/catalina/")
                && !className.startsWith("org/apache/coyote/")
                && !className.startsWith("org/apache/tomcat/")
                && !className.startsWith("org/apache/http/")
                && !className.startsWith("org/apache/hc/")
                && !className.startsWith("org/apache/kafka/")
                && !className.startsWith("org/apache/logging/log4j/")
                && !className.startsWith("org/eclipse/jetty/")
                && !className.startsWith("io/undertow/")
                && !className.startsWith("org/quartz/")
                && !className.startsWith("com/zaxxer/")
                && !className.startsWith("kotlin/")
                && !className.startsWith("kotlinx/")
                && !className.startsWith("com/google/common/")
                && !className.startsWith("com/github/benmanes/caffeine/")
                && !className.startsWith("io/vertx/")
                && !className.startsWith("io/micrometer/")
                && !className.startsWith("io/opentelemetry/")
                && !className.startsWith("ch/qos/logback/")
                && !className.startsWith("org/slf4j/")
                && !className.startsWith("com/mongodb/")
                && !className.startsWith("redis/clients/");
    }

    // Fast constant-pool scan: checks CONSTANT_Class (tag 7) against
    // TARGET_EXECUTOR_OWNERS AND CONSTANT_NameAndType (tag 12) method names
    // against TARGET_ASYNC_METHOD_NAMES so custom executor subtypes, Thread
    // subclasses, CompletionStage, method references, and direct consumer
    // polling are never missed while non-async classes skip ClassWriter.
    static boolean hasExecutorReference(ClassReader reader) {
        int itemCount = reader.getItemCount();
        char[] buf = new char[reader.getMaxStringLength()];
        for (int i = 1; i < itemCount; i++) {
            int offset = reader.getItem(i);
            if (offset == 0) {
                continue;
            }
            int tag = reader.readByte(offset - 1);
            if (tag == 7) {
                String owner = reader.readUTF8(offset, buf);
                if (TARGET_EXECUTOR_OWNERS.contains(owner)) {
                    return true;
                }
            } else if (tag == 12) {
                String memberName = reader.readUTF8(offset, buf);
                if (TARGET_ASYNC_METHOD_NAMES.contains(memberName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void scanRawConsumerCalls(byte[] classfile) {
        if (classfile == null || classfile.length < 16) {
            return;
        }
        try {
            ClassReader reader = new ClassReader(classfile);
            int itemCount = reader.getItemCount();
            char[] buf = new char[reader.getMaxStringLength()];
            boolean hasOwner = false;
            for (int i = 1; i < itemCount; i++) {
                int offset = reader.getItem(i);
                if (offset != 0 && reader.readByte(offset - 1) == 7
                        && RAW_CONSUMER_OWNERS.contains(reader.readUTF8(offset, buf))) {
                    hasOwner = true;
                    break;
                }
            }
            if (!hasOwner) {
                return;
            }
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String mname, String desc,
                                boolean isInterface) {
                            if (isDirectRawConsumerCall(owner, mname)) {
                                SEEN_UNHOOKED_CONSUMERS.add(owner.replace('/', '.') + "." + mname);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Throwable ignored) {
            // Read-only heuristic scan must never affect class loading.
        }
    }

    private static boolean isSingleArgExecutorSubmitOrExecute(String mname, String desc) {
        if ("execute".equals(mname) && "(Ljava/lang/Runnable;)V".equals(desc)) {
            return true;
        }
        if (("submit".equals(mname) || "submitListenable".equals(mname) || "submitCompletable".equals(mname))
                && (desc.startsWith("(Ljava/lang/Runnable;)")
                        || desc.startsWith("(Ljava/util/concurrent/Callable;)"))) {
            return true;
        }
        return false;
    }

    private static boolean isTwoArgExecutorSubmit(String mname, String desc) {
        return "submit".equals(mname) && desc.startsWith("(Ljava/lang/Runnable;Ljava/lang/Object;)");
    }

    private static boolean isForkJoinTaskSubmission(String owner, String mname, String desc) {
        if ("java/util/concurrent/ForkJoinPool".equals(owner)
                && ("execute".equals(mname) || "submit".equals(mname))
                && desc.startsWith("(Ljava/util/concurrent/ForkJoinTask;)")) {
            return true;
        }
        return false;
    }

    private static boolean isUntrackedScheduleOrInvokeAny(String mname, String desc) {
        if ("schedule".equals(mname)
                && (desc.startsWith("(Ljava/lang/Runnable;")
                        || desc.startsWith("(Ljava/util/concurrent/Callable;"))) {
            return true;
        }
        if (("scheduleAtFixedRate".equals(mname) || "scheduleWithFixedDelay".equals(mname))
                && desc.startsWith("(Ljava/lang/Runnable;")) {
            return true;
        }
        if ("invokeAny".equals(mname)
                && (desc.startsWith("(Ljava/util/Collection;)")
                        || desc.startsWith("(Ljava/util/Collection;JLjava/util/concurrent/TimeUnit;)"))) {
            return true;
        }
        return false;
    }

    private static boolean isTimerSchedule(String owner, String mname, String desc) {
        return "java/util/Timer".equals(owner)
                && ("schedule".equals(mname) || "scheduleAtFixedRate".equals(mname))
                && desc.startsWith("(Ljava/util/TimerTask;");
    }

    private static boolean isGuavaAsyncCall(int opcode, String owner, String mname, String desc) {
        if ((opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEVIRTUAL)
                && "addListener".equals(mname)
                && "(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)V".equals(desc)) {
            return true;
        }
        if (opcode == Opcodes.INVOKESTATIC
                && "com/google/common/util/concurrent/Futures".equals(owner)
                && ("addCallback".equals(mname)
                        || "transform".equals(mname)
                        || "transformAsync".equals(mname)
                        || "catching".equals(mname)
                        || "catchingAsync".equals(mname))) {
            return true;
        }
        return false;
    }

    private static boolean isReactorSubscribe(String owner, String mname) {
        return "subscribe".equals(mname)
                && ("reactor/core/publisher/Mono".equals(owner)
                        || "reactor/core/publisher/Flux".equals(owner)
                        || "reactor/core/publisher/ParallelFlux".equals(owner));
    }

    private static boolean isDirectRawConsumerCall(String owner, String mname) {
        if ("poll".equals(mname)
                && ("org/apache/kafka/clients/consumer/KafkaConsumer".equals(owner)
                        || "org/apache/kafka/clients/consumer/Consumer".equals(owner))) {
            return true;
        }
        if ("basicConsume".equals(mname) && "com/rabbitmq/client/Channel".equals(owner)) {
            return true;
        }
        if (("receive".equals(mname) || "setMessageListener".equals(mname))
                && ("javax/jms/MessageConsumer".equals(owner)
                        || "jakarta/jms/MessageConsumer".equals(owner))) {
            return true;
        }
        return false;
    }

    private static boolean isHookedSpringMethodRefOwner(String owner) {
        return "org/springframework/core/task/SimpleAsyncTaskExecutor".equals(owner)
                || "org/springframework/scheduling/concurrent/ThreadPoolTaskExecutor".equals(owner)
                || "org/springframework/scheduling/concurrent/ThreadPoolTaskScheduler".equals(owner)
                || "org/springframework/scheduling/concurrent/ConcurrentTaskExecutor".equals(owner);
    }

    private static boolean isAsyncMethodRefTarget(Handle h) {
        String owner = h.getOwner();
        String mname = h.getName();
        String desc = h.getDesc();
        if (!isHookedSpringMethodRefOwner(owner)
                && (isSingleArgExecutorSubmitOrExecute(mname, desc) || isTwoArgExecutorSubmit(mname, desc))) {
            return true;
        }
        if (isUntrackedScheduleOrInvokeAny(mname, desc)
                || isTimerSchedule(owner, mname, desc)
                || isForkJoinTaskSubmission(owner, mname, desc)) {
            return true;
        }
        if ("java/util/concurrent/CompletableFuture".equals(owner)) {
            if ("runAsync".equals(mname) || "supplyAsync".equals(mname) || "completeAsync".equals(mname)) {
                return true;
            }
            Set<String> asyncDescs = CF_ASYNC_INSTANCE_METHODS.get(mname);
            if (asyncDescs != null && asyncDescs.contains(desc)) {
                return true;
            }
            Set<String> syncDescs = CF_SYNC_INSTANCE_METHODS.get(mname);
            if (syncDescs != null && syncDescs.contains(desc)) {
                return true;
            }
        }
        if ("java/util/concurrent/CompletionStage".equals(owner)) {
            Set<String> asyncDescs = CS_ASYNC_INSTANCE_METHODS.get(mname);
            if (asyncDescs != null && asyncDescs.contains(desc)) {
                return true;
            }
            Set<String> syncDescs = CS_SYNC_INSTANCE_METHODS.get(mname);
            if (syncDescs != null && syncDescs.contains(desc)) {
                return true;
            }
        }
        if ("fork".equals(mname) && "()Ljava/util/concurrent/ForkJoinTask;".equals(desc)) {
            return true;
        }
        if ("java/lang/Thread".equals(owner)
                && (("start".equals(mname) && "()V".equals(desc))
                        || ("startVirtualThread".equals(mname)
                                && "(Ljava/lang/Runnable;)Ljava/lang/Thread;".equals(desc)))) {
            return true;
        }
        if ("start".equals(mname) && "(Ljava/lang/Runnable;)Ljava/lang/Thread;".equals(desc)) {
            return true;
        }
        return isReactorSubscribe(owner, mname);
    }

    private static String stageHelperName(String mname) {
        return "stage" + Character.toUpperCase(mname.charAt(0)) + mname.substring(1);
    }

    // wrapExecutorCalls redirects standard JDK executor hand-off call sites,
    // CompletableFuture (runAsync, supplyAsync, completeAsync, *Async and plain
    // dependent stages), CompletionStage (*Async and plain dependent stages),
    // remove/shutdownNow, and untracked async sites (schedule*, invokeAny,
    // Thread.start, custom executor subtypes, ForkJoinTask.fork, Reactor
    // subscribe, async method references) to RequestGate static helpers.
    // Returns null when the class contains no matching call sites.
    static byte[] wrapExecutorCalls(byte[] classfile) {
        ClassReader reader = new ClassReader(classfile);
        if ((reader.getAccess() & Opcodes.ACC_INTERFACE) != 0) {
            return null;
        }
        if (!hasExecutorReference(reader)) {
            return null;
        }
        ClassWriter writer = new ClassWriter(reader, 0);
        final boolean[] changed = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    private int extraStack;

                    private void ensureExtraStack(int needed) {
                        if (extraStack < needed) {
                            extraStack = needed;
                        }
                    }

                    private void emitUntrackedFlag(String reason) {
                        changed[0] = true;
                        ensureExtraStack(1);
                        super.visitLdcInsn(reason);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "noteUntrackedAsync",
                                "(Ljava/lang/String;)V", false);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String indyName, String indyDesc, Handle bsm,
                            Object... bsmArgs) {
                        if (bsmArgs != null) {
                            for (Object arg : bsmArgs) {
                                if (arg instanceof Handle) {
                                    Handle h = (Handle) arg;
                                    if (isAsyncMethodRefTarget(h)) {
                                        emitUntrackedFlag("untracked-async:method-ref:"
                                                + h.getOwner().replace('/', '.') + "." + h.getName());
                                        break;
                                    }
                                }
                            }
                        }
                        super.visitInvokeDynamicInsn(indyName, indyDesc, bsm, bsmArgs);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname, String desc,
                            boolean isInterface) {
                        if (isDirectRawConsumerCall(owner, mname)) {
                            SEEN_UNHOOKED_CONSUMERS.add(owner.replace('/', '.') + "." + mname);
                        }
                        if (opcode == Opcodes.INVOKEINTERFACE
                                && ("java/util/concurrent/Executor".equals(owner)
                                        || "java/util/concurrent/ExecutorService".equals(owner)
                                        || "java/util/concurrent/ScheduledExecutorService".equals(owner))
                                && "execute".equals(mname) && "(Ljava/lang/Runnable;)V".equals(desc)) {
                            changed[0] = true;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "execute",
                                    "(Ljava/util/concurrent/Executor;Ljava/lang/Runnable;)V", false);
                            return;
                        }
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && ("java/util/concurrent/AbstractExecutorService".equals(owner)
                                        || "java/util/concurrent/ThreadPoolExecutor".equals(owner)
                                        || "java/util/concurrent/ScheduledThreadPoolExecutor".equals(owner)
                                        || "java/util/concurrent/ForkJoinPool".equals(owner))
                                && "execute".equals(mname) && "(Ljava/lang/Runnable;)V".equals(desc)) {
                            changed[0] = true;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "execute",
                                    "(Ljava/util/concurrent/Executor;Ljava/lang/Runnable;)V", false);
                            return;
                        }
                        if ((opcode == Opcodes.INVOKEINTERFACE
                                        && ("java/util/concurrent/ExecutorService".equals(owner)
                                                || "java/util/concurrent/ScheduledExecutorService".equals(owner)))
                                || (opcode == Opcodes.INVOKEVIRTUAL
                                        && ("java/util/concurrent/AbstractExecutorService".equals(owner)
                                                || "java/util/concurrent/ThreadPoolExecutor".equals(owner)
                                                || "java/util/concurrent/ScheduledThreadPoolExecutor".equals(owner)
                                                || "java/util/concurrent/ForkJoinPool".equals(owner)))) {
                            if ("submit".equals(mname)) {
                                if ("(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;".equals(desc)) {
                                    changed[0] = true;
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "submit",
                                            "(Ljava/util/concurrent/ExecutorService;Ljava/lang/Runnable;)"
                                                    + "Ljava/util/concurrent/Future;",
                                            false);
                                    return;
                                }
                                if ("(Ljava/lang/Runnable;Ljava/lang/Object;)Ljava/util/concurrent/Future;"
                                        .equals(desc)) {
                                    changed[0] = true;
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "submit",
                                            "(Ljava/util/concurrent/ExecutorService;Ljava/lang/Runnable;"
                                                    + "Ljava/lang/Object;)Ljava/util/concurrent/Future;",
                                            false);
                                    return;
                                }
                                if ("(Ljava/util/concurrent/Callable;)Ljava/util/concurrent/Future;".equals(desc)) {
                                    changed[0] = true;
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "submit",
                                            "(Ljava/util/concurrent/ExecutorService;Ljava/util/concurrent/Callable;)"
                                                    + "Ljava/util/concurrent/Future;",
                                            false);
                                    return;
                                }
                            }
                            if ("invokeAny".equals(mname)
                                    && ("(Ljava/util/Collection;)Ljava/lang/Object;".equals(desc)
                                            || "(Ljava/util/Collection;JLjava/util/concurrent/TimeUnit;)Ljava/lang/Object;"
                                                    .equals(desc))) {
                                changed[0] = true;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "invokeAny",
                                        "(Ljava/util/concurrent/ExecutorService;" + desc.substring(1), false);
                                return;
                            }
                        }
                        if ((opcode == Opcodes.INVOKEINTERFACE
                                        && "java/util/concurrent/ScheduledExecutorService".equals(owner))
                                || (opcode == Opcodes.INVOKEVIRTUAL
                                        && "java/util/concurrent/ScheduledThreadPoolExecutor".equals(owner))) {
                            if ("schedule".equals(mname)
                                    && ("(Ljava/lang/Runnable;JLjava/util/concurrent/TimeUnit;)"
                                                    + "Ljava/util/concurrent/ScheduledFuture;").equals(desc)
                                    || "schedule".equals(mname)
                                            && ("(Ljava/util/concurrent/Callable;JLjava/util/concurrent/TimeUnit;)"
                                                    + "Ljava/util/concurrent/ScheduledFuture;").equals(desc)
                                    || ("scheduleAtFixedRate".equals(mname) || "scheduleWithFixedDelay".equals(mname))
                                            && ("(Ljava/lang/Runnable;JJLjava/util/concurrent/TimeUnit;)"
                                                    + "Ljava/util/concurrent/ScheduledFuture;").equals(desc)) {
                                changed[0] = true;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, mname,
                                        "(Ljava/util/concurrent/ScheduledExecutorService;" + desc.substring(1), false);
                                return;
                            }
                        }
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && ("java/util/concurrent/ThreadPoolExecutor".equals(owner)
                                        || "java/util/concurrent/ScheduledThreadPoolExecutor".equals(owner))
                                && "remove".equals(mname) && "(Ljava/lang/Runnable;)Z".equals(desc)) {
                            changed[0] = true;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "remove",
                                    "(Ljava/util/concurrent/ThreadPoolExecutor;Ljava/lang/Runnable;)Z", false);
                            return;
                        }
                        if (((opcode == Opcodes.INVOKEINTERFACE
                                        && ("java/util/concurrent/ExecutorService".equals(owner)
                                                || "java/util/concurrent/ScheduledExecutorService".equals(owner)))
                                || (opcode == Opcodes.INVOKEVIRTUAL
                                        && ("java/util/concurrent/AbstractExecutorService".equals(owner)
                                                || "java/util/concurrent/ThreadPoolExecutor".equals(owner)
                                                || "java/util/concurrent/ScheduledThreadPoolExecutor".equals(owner)
                                                || "java/util/concurrent/ForkJoinPool".equals(owner))))
                                && "shutdownNow".equals(mname) && "()Ljava/util/List;".equals(desc)) {
                            changed[0] = true;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "shutdownNow",
                                    "(Ljava/util/concurrent/ExecutorService;)Ljava/util/List;", false);
                            return;
                        }
                        if (opcode == Opcodes.INVOKESTATIC
                                && "java/util/concurrent/CompletableFuture".equals(owner)) {
                            if ("runAsync".equals(mname)
                                    && ("(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;".equals(desc)
                                            || ("(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)"
                                                    + "Ljava/util/concurrent/CompletableFuture;").equals(desc))) {
                                changed[0] = true;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "runAsync", desc, false);
                                return;
                            }
                            if ("supplyAsync".equals(mname)
                                    && ("(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;"
                                                    .equals(desc)
                                            || ("(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)"
                                                    + "Ljava/util/concurrent/CompletableFuture;").equals(desc))) {
                                changed[0] = true;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "supplyAsync", desc, false);
                                return;
                            }
                        }
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && "java/util/concurrent/CompletableFuture".equals(owner)) {
                            if ("completeAsync".equals(mname)
                                    && ("(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;"
                                                    .equals(desc)
                                            || ("(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)"
                                                    + "Ljava/util/concurrent/CompletableFuture;").equals(desc))) {
                                changed[0] = true;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "completeAsync",
                                        "(Ljava/util/concurrent/CompletableFuture;" + desc.substring(1), false);
                                return;
                            }
                            Set<String> descs = CF_ASYNC_INSTANCE_METHODS.get(mname);
                            if (descs != null && descs.contains(desc)) {
                                changed[0] = true;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, mname,
                                        "(Ljava/util/concurrent/CompletableFuture;" + desc.substring(1), false);
                                return;
                            }
                            Set<String> syncDescs = CF_SYNC_INSTANCE_METHODS.get(mname);
                            if (syncDescs != null && syncDescs.contains(desc)) {
                                changed[0] = true;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, mname,
                                        "(Ljava/util/concurrent/CompletableFuture;" + desc.substring(1), false);
                                return;
                            }
                        }
                        if (opcode == Opcodes.INVOKEINTERFACE
                                && "java/util/concurrent/CompletionStage".equals(owner)) {
                            Set<String> descs = CS_ASYNC_INSTANCE_METHODS.get(mname);
                            if (descs != null && descs.contains(desc)) {
                                changed[0] = true;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, stageHelperName(mname),
                                        "(Ljava/util/concurrent/CompletionStage;" + desc.substring(1), false);
                                return;
                            }
                            Set<String> syncDescs = CS_SYNC_INSTANCE_METHODS.get(mname);
                            if (syncDescs != null && syncDescs.contains(desc)) {
                                changed[0] = true;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, stageHelperName(mname),
                                        "(Ljava/util/concurrent/CompletionStage;" + desc.substring(1), false);
                                return;
                            }
                        }
                        if (opcode == Opcodes.INVOKEVIRTUAL && "java/util/Timer".equals(owner)) {
                            if ("schedule".equals(mname)
                                    && ("(Ljava/util/TimerTask;J)V".equals(desc)
                                            || "(Ljava/util/TimerTask;Ljava/util/Date;)V".equals(desc)
                                            || "(Ljava/util/TimerTask;JJ)V".equals(desc)
                                            || "(Ljava/util/TimerTask;Ljava/util/Date;J)V".equals(desc))) {
                                changed[0] = true;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "timerSchedule",
                                        "(Ljava/util/Timer;" + desc.substring(1), false);
                                return;
                            }
                            if ("scheduleAtFixedRate".equals(mname)
                                    && ("(Ljava/util/TimerTask;JJ)V".equals(desc)
                                            || "(Ljava/util/TimerTask;Ljava/util/Date;J)V".equals(desc))) {
                                changed[0] = true;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "timerScheduleAtFixedRate",
                                        "(Ljava/util/Timer;" + desc.substring(1), false);
                                return;
                            }
                        }
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && "java/lang/Thread".equals(owner)
                                && "start".equals(mname)
                                && "()V".equals(desc)) {
                            changed[0] = true;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "startThread",
                                    "(Ljava/lang/Thread;)V", false);
                            return;
                        }
                        if (opcode == Opcodes.INVOKESTATIC
                                && "java/lang/Thread".equals(owner)
                                && "startVirtualThread".equals(mname)
                                && "(Ljava/lang/Runnable;)Ljava/lang/Thread;".equals(desc)) {
                            emitUntrackedFlag("untracked-async:virtual-thread");
                            super.visitMethodInsn(opcode, owner, mname, desc, isInterface);
                            super.visitInsn(Opcodes.DUP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "trackUntrackedThread",
                                    "(Ljava/lang/Object;)V", false);
                            return;
                        }
                        if ((opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEVIRTUAL)
                                && "start".equals(mname)
                                && "(Ljava/lang/Runnable;)Ljava/lang/Thread;".equals(desc)) {
                            emitUntrackedFlag("untracked-async:thread-builder");
                            super.visitMethodInsn(opcode, owner, mname, desc, isInterface);
                            super.visitInsn(Opcodes.DUP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "trackUntrackedThread",
                                    "(Ljava/lang/Object;)V", false);
                            return;
                        }
                        // Flag-only async submission sites (task untouched, 100% app-safe).
                        if (isGuavaAsyncCall(opcode, owner, mname, desc)) {
                            emitUntrackedFlag("untracked-async:guava-future");
                        } else if (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE) {
                            if (isSingleArgExecutorSubmitOrExecute(mname, desc)) {
                                // Decide at runtime by actual receiver so Spring interfaces
                                // (TaskExecutor/AsyncTaskExecutor) and subclasses have zero
                                // false positives when hooked+verified AND zero false negatives
                                // when backed by VirtualThreadTaskExecutor/custom pools.
                                changed[0] = true;
                                ensureExtraStack(2);
                                super.visitInsn(Opcodes.DUP2);
                                super.visitInsn(Opcodes.POP);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "noteIfUntrackedExecutor",
                                        "(Ljava/lang/Object;)V", false);
                            } else if (isTwoArgExecutorSubmit(mname, desc)) {
                                emitUntrackedFlag("untracked-executor:" + owner.replace('/', '.'));
                            } else if (isForkJoinTaskSubmission(owner, mname, desc)) {
                                emitUntrackedFlag("untracked-async:forkjoin-task");
                            } else if (isTimerSchedule(owner, mname, desc)) {
                                emitUntrackedFlag("untracked-async:timer");
                            } else if (isUntrackedScheduleOrInvokeAny(mname, desc)) {
                                emitUntrackedFlag("untracked-async:" + ("invokeAny".equals(mname)
                                        ? "invokeAny" : "schedule"));
                                if (desc.endsWith(")Ljava/util/concurrent/ScheduledFuture;")) {
                                    super.visitMethodInsn(opcode, owner, mname, desc, isInterface);
                                    super.visitInsn(Opcodes.DUP);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "trackUntrackedFuture",
                                            "(Ljava/lang/Object;)V", false);
                                    return;
                                }
                            } else if ("fork".equals(mname)
                                    && "()Ljava/util/concurrent/ForkJoinTask;".equals(desc)) {
                                emitUntrackedFlag("untracked-async:fork");
                            } else if (isReactorSubscribe(owner, mname)) {
                                emitUntrackedFlag("untracked-async:reactor-subscribe");
                            } else if (opcode == Opcodes.INVOKEVIRTUAL
                                    && "start".equals(mname) && "()V".equals(desc)) {
                                // Only flag if runtime receiver is a Thread subclass (zero false
                                // positives on non-Thread start() methods).
                                changed[0] = true;
                                ensureExtraStack(1);
                                super.visitInsn(Opcodes.DUP);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "noteIfThread",
                                        "(Ljava/lang/Object;)V", false);
                            }
                        }
                        super.visitMethodInsn(opcode, owner, mname, desc, isInterface);
                    }

                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        super.visitMaxs(maxStack + extraStack, maxLocals);
                    }
                };
            }
        }, 0);
        return changed[0] ? writer.toByteArray() : null;
    }

    static byte[] wrap(byte[] classfile, Set<String> methods, String job, boolean async) {
        ClassReader reader = new ClassReader(classfile);
        ClassWriter writer = new ClassWriter(reader, 0);
        Wrapper wrapper = new Wrapper(writer, methods, job, async);
        reader.accept(wrapper, 0);
        return wrapper.wrapped.isEmpty() ? null : writer.toByteArray();
    }

    private static final class Entry {

        private final String server;
        private final String job;
        private final boolean async;
        private final Set<String> methods;

        private Entry(String server, String job, boolean async, Set<String> methods) {
            this.server = server;
            this.job = job;
            this.async = async;
            this.methods = Collections.unmodifiableSet(methods);
        }

        private String what() {
            return server + (async ? "'s async tasks" : job == null ? "'s requests" : "'s job runs");
        }

        private String effect(boolean requests) {
            String jobs = "a task a scheduled job's run hands off counts as that run, so a test whose window it"
                    + " runs in is marked contaminated in its record";
            return async ? (requests ? "a test's END waits for the tasks its requests hand off to finish, as for"
                    + " the requests, and " + jobs : jobs)
                    : job == null ? "a test's END waits for them to finish"
                    : "a test whose window a run overlaps is marked contaminated in its record, and Keploy does"
                    + " not judge it";
        }

        private String lost(boolean requests) {
            String jobs = "a test is not marked contaminated when work a scheduled job hands off runs in its window";
            return async ? (requests ? "a test's END reads its hits without waiting for the tasks its requests"
                    + " hand off, and " + jobs : jobs)
                    : job == null ? "a test's END reads its hits without waiting for its requests to finish"
                    : "a test a job runs in is not marked contaminated";
        }
    }

    private static final class Wrapper extends ClassVisitor {

        private static final int NOT_COUNTED = Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_BRIDGE
                | Opcodes.ACC_SYNTHETIC;

        private final Set<String> methods;
        private final String job;
        private final boolean async;
        private final String renamedPrefix;
        private final List<Method> wrapped = new ArrayList<>();
        private String owner;
        private int version;

        private Wrapper(ClassVisitor next, Set<String> methods, String job, boolean async) {
            super(Opcodes.ASM9, next);
            this.methods = methods;
            this.job = job;
            this.async = async;
            this.renamedPrefix = async ? ASYNC_RENAMED : job == null ? RENAMED : JOB_RENAMED;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                String[] interfaces) {
            this.version = version & 0xFFFF;
            this.owner = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            if (!methods.contains(name) || (access & NOT_COUNTED) != 0 || (async && task(descriptor) < 0)) {
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
            wrapped.add(new Method(access, name, descriptor, signature, exceptions));
            int renamed = (access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE
                    | Opcodes.ACC_SYNTHETIC;
            return super.visitMethod(renamed, renamedPrefix + name, descriptor, signature, exceptions);
        }

        @Override
        public void visitEnd() {
            for (Method method : wrapped) {
                counting(method);
            }
            super.visitEnd();
        }

        private static int task(String descriptor) {
            Type[] args = Type.getArgumentTypes(descriptor);
            for (int i = 0; i < args.length; i++) {
                String arg = args[i].getDescriptor();
                if (arg.equals(RUNNABLE) || arg.equals(CALLABLE)) {
                    return i;
                }
            }
            return -1;
        }

        private void counting(Method method) {
            if (async) {
                handing(method);
                return;
            }
            int access = method.access & ~Opcodes.ACC_SYNCHRONIZED;
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            Type[] args = Type.getArgumentTypes(method.descriptor);
            Type returns = Type.getReturnType(method.descriptor);
            MethodVisitor mv = super.visitMethod(access, method.name, method.descriptor, method.signature,
                    method.exceptions);
            mv.visitCode();
            Label start = new Label();
            Label end = new Label();
            Label thrown = new Label();
            mv.visitTryCatchBlock(start, end, thrown, null);

            List<Object> locals = new ArrayList<>();
            int slot = 0;
            if (!isStatic) {
                locals.add(owner);
                slot++;
            }
            for (Type arg : args) {
                locals.add(frameType(arg));
                slot += arg.getSize();
            }
            int token = slot;
            locals.add("java/lang/Object");

            if (job == null) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "enter", "()Ljava/lang/Object;", false);
            } else {
                if (isStatic) {
                    mv.visitInsn(Opcodes.ACONST_NULL);
                } else {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                }
                mv.visitLdcInsn(job);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, JOB_GATE, "enter",
                        "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            }
            mv.visitVarInsn(Opcodes.ASTORE, token);
            mv.visitLabel(start);
            slot = 0;
            if (!isStatic) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                slot++;
            }
            for (Type arg : args) {
                mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
                slot += arg.getSize();
            }
            mv.visitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL, owner,
                    renamedPrefix + method.name, method.descriptor, false);
            mv.visitLabel(end);
            mv.visitVarInsn(Opcodes.ALOAD, token);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, gate(), "exit", "(Ljava/lang/Object;)V", false);
            mv.visitInsn(returns.getOpcode(Opcodes.IRETURN));

            mv.visitLabel(thrown);
            if (version >= Opcodes.V1_6) {
                mv.visitFrame(Opcodes.F_FULL, locals.size(), locals.toArray(), 1,
                        new Object[] {"java/lang/Throwable"});
            }
            mv.visitVarInsn(Opcodes.ALOAD, token);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, gate(), "exit", "(Ljava/lang/Object;)V", false);
            mv.visitInsn(Opcodes.ATHROW);
            mv.visitMaxs(Math.max(Math.max(slot, returns.getSize() + 1), 2), token + 1);
            mv.visitEnd();
        }

        private static int executor(Type[] args) {
            for (int i = 0; i < args.length; i++) {
                if (args[i].getDescriptor().equals(EXECUTOR)) {
                    return i;
                }
            }
            return -1;
        }

        private void handing(Method method) {
            int access = method.access & ~Opcodes.ACC_SYNCHRONIZED;
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            Type[] args = Type.getArgumentTypes(method.descriptor);
            Type returns = Type.getReturnType(method.descriptor);
            int task = task(method.descriptor);
            int executor = executor(args);
            String taskType = args[task].getDescriptor();
            MethodVisitor mv = super.visitMethod(access, method.name, method.descriptor, method.signature,
                    method.exceptions);
            mv.visitCode();
            Label start = new Label();
            Label end = new Label();
            Label thrown = new Label();
            mv.visitTryCatchBlock(start, end, thrown, null);

            List<Object> locals = new ArrayList<>();
            int slot = 0;
            if (!isStatic) {
                locals.add(owner);
                slot++;
            }
            int taskSlot = -1;
            int executorSlot = -1;
            for (int i = 0; i < args.length; i++) {
                if (i == task) {
                    taskSlot = slot;
                }
                if (i == executor) {
                    executorSlot = slot;
                }
                locals.add(frameType(args[i]));
                slot += args[i].getSize();
            }
            int handed = slot;
            locals.add(args[task].getInternalName());

            if (executorSlot >= 0) {
                mv.visitVarInsn(Opcodes.ALOAD, executorSlot);
            } else if (isStatic) {
                mv.visitInsn(Opcodes.ACONST_NULL);
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
            }
            mv.visitVarInsn(Opcodes.ALOAD, taskSlot);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "hand", "(Ljava/lang/Object;" + taskType + ")" + taskType,
                    false);
            mv.visitVarInsn(Opcodes.ASTORE, handed);
            mv.visitLabel(start);
            slot = 0;
            if (!isStatic) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                slot++;
            }
            for (int i = 0; i < args.length; i++) {
                mv.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), i == task ? handed : slot);
                slot += args[i].getSize();
            }
            mv.visitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL, owner,
                    renamedPrefix + method.name, method.descriptor, false);
            mv.visitLabel(end);
            if (returns.getSort() == Type.OBJECT) {
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, handed);
                mv.visitVarInsn(Opcodes.ALOAD, taskSlot);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "submitted",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false);
            }
            mv.visitInsn(returns.getOpcode(Opcodes.IRETURN));

            mv.visitLabel(thrown);
            if (version >= Opcodes.V1_6) {
                mv.visitFrame(Opcodes.F_FULL, locals.size(), locals.toArray(), 1,
                        new Object[] {"java/lang/Throwable"});
            }
            mv.visitVarInsn(Opcodes.ALOAD, handed);
            mv.visitVarInsn(Opcodes.ALOAD, taskSlot);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "dropped", "(Ljava/lang/Object;Ljava/lang/Object;)V",
                    false);
            mv.visitInsn(Opcodes.ATHROW);
            mv.visitMaxs(Math.max(Math.max(slot, returns.getSize() + 3), 4), handed + 1);
            mv.visitEnd();
        }

        private String gate() {
            return job == null ? GATE : JOB_GATE;
        }

        private static Object frameType(Type type) {
            switch (type.getSort()) {
                case Type.BOOLEAN:
                case Type.BYTE:
                case Type.CHAR:
                case Type.SHORT:
                case Type.INT:
                    return Opcodes.INTEGER;
                case Type.FLOAT:
                    return Opcodes.FLOAT;
                case Type.LONG:
                    return Opcodes.LONG;
                case Type.DOUBLE:
                    return Opcodes.DOUBLE;
                default:
                    return type.getInternalName();
            }
        }
    }

    private static final class Method {

        private final int access;
        private final String name;
        private final String descriptor;
        private final String signature;
        private final String[] exceptions;

        private Method(int access, String name, String descriptor, String signature, String[] exceptions) {
            this.access = access;
            this.name = name;
            this.descriptor = descriptor;
            this.signature = signature;
            this.exceptions = exceptions;
        }
    }
}
