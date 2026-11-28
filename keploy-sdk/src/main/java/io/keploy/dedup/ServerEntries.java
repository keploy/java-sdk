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
        // Message listeners: an inbound message
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
        addAsync("org/springframework/scheduling/concurrent/SimpleAsyncTaskScheduler", "execute", "submit",
                "submitListenable", "submitCompletable");
        addAsync("org/springframework/scheduling/concurrent/ThreadPoolTaskExecutor", "execute", "submit",
                "submitListenable", "submitCompletable");
        addAsync("org/springframework/scheduling/concurrent/ThreadPoolTaskScheduler", "execute", "submit",
                "submitListenable", "submitCompletable");
        addAsync("org/springframework/scheduling/concurrent/ConcurrentTaskExecutor", "execute", "submit",
                "submitListenable", "submitCompletable");
        addAsync("org/springframework/aop/interceptor/AsyncExecutionAspectSupport", "doSubmit");
    }

    // Classes/interfaces whose async entry methods are hooked to latch
    // asyncExecutors=false on first invocation (instead of on class load),
    // so synchronous-only users (e.g. Spring Boot PetClinic using sync
    // Caffeine/@Cacheable) retain asyncExecutors=true while actual async
    // cache refresh/load fails closed before W1's COV.
    private static final Map<String, LatchEntry> LATCH_ON_CALL = new HashMap<>();

    static {
        addLatchOnCall("com/github/benmanes/caffeine/cache/Caffeine",
                "caffeine-async-cache", "buildAsync", "executor", "removalListener",
                "evictionListener", "refreshAfterWrite", "scheduler");
        addLatchOnCall("com/github/benmanes/caffeine/cache/CacheLoader",
                "caffeine-async-cache", "asyncLoad", "asyncLoadAll", "asyncReload");
        addLatchOnCall("com/github/benmanes/caffeine/cache/LocalAsyncCache",
                "caffeine-async-cache", "get", "getAll", "put");
        addLatchOnCall("org/springframework/cache/concurrent/ConcurrentMapCache",
                "spring-async-cache", "retrieve");
    }

    private static void addLatchOnCall(String className, String label, String... methods) {
        LATCH_ON_CALL.put(className, new LatchEntry(label, new HashSet<>(Arrays.asList(methods))));
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

    private static final Map<String, String> UNHOOKED_ASYNC_DISPATCHERS = new HashMap<>();

    static {
        UNHOOKED_ASYNC_DISPATCHERS.put("rx/schedulers/Schedulers", "RxJava 1 Schedulers");
        UNHOOKED_ASYNC_DISPATCHERS.put("io/reactivex/schedulers/Schedulers", "RxJava 2 Schedulers");
        UNHOOKED_ASYNC_DISPATCHERS.put("io/reactivex/rxjava3/schedulers/Schedulers", "RxJava 3 Schedulers");
        UNHOOKED_ASYNC_DISPATCHERS.put("kotlinx/coroutines/Dispatchers", "Kotlin Coroutines Dispatchers");
        UNHOOKED_ASYNC_DISPATCHERS.put("kotlinx/coroutines/GlobalScope", "Kotlin Coroutines GlobalScope");
        UNHOOKED_ASYNC_DISPATCHERS.put("kotlin/concurrent/ThreadsKt", "Kotlin Thread Helper");
        UNHOOKED_ASYNC_DISPATCHERS.put("reactor/core/scheduler/Schedulers", "Project Reactor Schedulers");
        UNHOOKED_ASYNC_DISPATCHERS.put("com/google/common/eventbus/AsyncEventBus", "Guava AsyncEventBus");
        UNHOOKED_ASYNC_DISPATCHERS.put("okhttp3/Dispatcher", "OkHttp Dispatcher");
        UNHOOKED_ASYNC_DISPATCHERS.put("java/nio/channels/AsynchronousChannelGroup", "JDK AsynchronousChannelGroup");
        UNHOOKED_ASYNC_DISPATCHERS.put("java/nio/channels/AsynchronousFileChannel", "JDK AsynchronousFileChannel");
        UNHOOKED_ASYNC_DISPATCHERS.put("java/nio/channels/AsynchronousSocketChannel", "JDK AsynchronousSocketChannel");
        UNHOOKED_ASYNC_DISPATCHERS.put("java/nio/channels/AsynchronousServerSocketChannel",
                "JDK AsynchronousServerSocketChannel");
        UNHOOKED_ASYNC_DISPATCHERS.put("sun/nio/ch/SimpleAsynchronousFileChannelImpl", "JDK AsynchronousFileChannel");
        UNHOOKED_ASYNC_DISPATCHERS.put("sun/nio/ch/AsynchronousChannelGroupImpl", "JDK AsynchronousChannelGroup");
        UNHOOKED_ASYNC_DISPATCHERS.put("java/util/concurrent/SubmissionPublisher", "JDK SubmissionPublisher");
    }

    private static final Set<String> BLOCKING_QUEUE_OWNERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "java/util/concurrent/BlockingQueue",
            "java/util/concurrent/BlockingDeque",
            "java/util/concurrent/TransferQueue",
            "java/util/concurrent/LinkedBlockingQueue",
            "java/util/concurrent/ArrayBlockingQueue",
            "java/util/concurrent/LinkedBlockingDeque",
            "java/util/concurrent/PriorityBlockingQueue",
            "java/util/concurrent/DelayQueue",
            "java/util/concurrent/SynchronousQueue",
            "java/util/concurrent/LinkedTransferQueue",
            "java/util/concurrent/ConcurrentLinkedQueue",
            "java/util/concurrent/ConcurrentLinkedDeque")));

    private static final Set<String> BLOCKING_QUEUE_ENQUEUE_METHODS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("offer", "put", "add", "offerFirst", "offerLast", "putFirst", "putLast",
                    "addFirst", "addLast", "transfer", "tryTransfer")));

    // JDK HTTP/WebSocket async methods checked even in rewritten app classes
    // (since java/net/http/* is skipped by isInternalOrAgentClass). Must be
    // initialized before DISPATCH_PRIMITIVE_METHODS, which merges it via putAll.
    private static final Map<String, Set<String>> JDK_ASYNC_HTTP_METHODS = buildJdkAsyncHttpMethods();
    // Concurrency dispatch primitives checked by Methodref/InterfaceMethodref
    // constant-pool scan on every non-JDK, non-agent, non-allowlisted loaded class.
    private static final Map<String, Set<String>> DISPATCH_PRIMITIVE_METHODS = buildDispatchPrimitiveMethods();

    private static Map<String, Set<String>> buildJdkAsyncHttpMethods() {
        Map<String, Set<String>> m = new HashMap<>();
        m.put("java/net/http/HttpClient", setOf("sendAsync"));
        m.put("java/net/http/WebSocket$Builder", setOf("buildAsync"));
        m.put("java/net/http/WebSocket", setOf("sendText", "sendBinary", "sendPing", "sendPong", "sendClose"));
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, Set<String>> buildDispatchPrimitiveMethods() {
        Map<String, Set<String>> m = new HashMap<>();
        Set<String> exec = setOf("execute");
        Set<String> execSvc = setOf("execute", "submit", "invokeAll", "invokeAny");
        Set<String> schedSvc = setOf("execute", "submit", "invokeAll", "invokeAny",
                "schedule", "scheduleAtFixedRate", "scheduleWithFixedDelay");
        Set<String> fjp = setOf("execute", "submit", "invokeAll", "invokeAny", "invoke");
        Set<String> stageAsync = setOf(
                "thenApplyAsync", "thenAcceptAsync", "thenRunAsync", "thenComposeAsync",
                "thenCombineAsync", "thenAcceptBothAsync", "runAfterBothAsync",
                "applyToEitherAsync", "acceptEitherAsync", "runAfterEitherAsync",
                "handleAsync", "whenCompleteAsync", "exceptionallyAsync", "exceptionallyComposeAsync");
        Set<String> cfAsync = new HashSet<>(stageAsync);
        cfAsync.add("runAsync");
        cfAsync.add("supplyAsync");
        cfAsync.add("completeAsync");
        Set<String> clq = setOf("offer", "add", "offerFirst", "offerLast", "addFirst", "addLast");

        m.put("java/util/concurrent/Executor", exec);
        m.put("java/util/concurrent/ExecutorService", execSvc);
        m.put("java/util/concurrent/AbstractExecutorService", execSvc);
        m.put("java/util/concurrent/ThreadPoolExecutor", execSvc);
        m.put("java/util/concurrent/ScheduledExecutorService", schedSvc);
        m.put("java/util/concurrent/ScheduledThreadPoolExecutor", schedSvc);
        m.put("java/util/concurrent/ForkJoinPool", fjp);
        m.put("java/util/concurrent/ForkJoinTask", setOf("fork"));
        m.put("java/util/concurrent/ExecutorCompletionService", setOf("submit"));
        m.put("java/lang/Thread", setOf("start", "startVirtualThread"));
        m.put("java/lang/Thread$Builder", setOf("start"));
        m.put("java/lang/Thread$Builder$OfVirtual", setOf("start"));
        m.put("java/lang/Thread$Builder$OfPlatform", setOf("start"));
        m.put("java/util/Timer", setOf("schedule", "scheduleAtFixedRate"));
        m.put("java/util/concurrent/CompletableFuture", Collections.unmodifiableSet(cfAsync));
        m.put("java/util/concurrent/CompletionStage", stageAsync);
        m.put("java/util/concurrent/SubmissionPublisher", setOf("submit", "offer"));
        // Messaging producers: if a library or dynamically generated proxy outside
        // rewritten app classes invokes a broker send/publish, fail closed on load.
        m.put("org/springframework/kafka/core/KafkaTemplate", setOf("send", "sendDefault"));
        m.put("org/springframework/kafka/core/KafkaOperations", setOf("send", "sendDefault"));
        m.put("org/apache/kafka/clients/producer/KafkaProducer", setOf("send"));
        m.put("org/apache/kafka/clients/producer/Producer", setOf("send"));
        m.put("org/springframework/amqp/rabbit/core/RabbitTemplate", setOf("send", "convertAndSend", "convertSendAndReceive"));
        m.put("org/springframework/amqp/rabbit/core/RabbitOperations", setOf("send", "convertAndSend", "convertSendAndReceive"));
        m.put("org/springframework/amqp/core/AmqpTemplate", setOf("send", "convertAndSend", "convertSendAndReceive"));
        m.put("org/springframework/jms/core/JmsTemplate", setOf("send", "convertAndSend"));
        m.put("org/springframework/jms/core/JmsOperations", setOf("send", "convertAndSend"));
        m.put("org/springframework/jms/core/JmsMessagingTemplate", setOf("send", "convertAndSend"));
        m.put("org/springframework/cloud/stream/function/StreamBridge", setOf("send"));
        m.put("reactor/kafka/sender/KafkaSender", setOf("send"));
        for (String q : BLOCKING_QUEUE_OWNERS) {
            if ("java/util/concurrent/ConcurrentLinkedQueue".equals(q)
                    || "java/util/concurrent/ConcurrentLinkedDeque".equals(q)) {
                m.put(q, clq);
            } else {
                m.put(q, BLOCKING_QUEUE_ENQUEUE_METHODS);
            }
        }
        m.putAll(JDK_ASYNC_HTTP_METHODS);
        return Collections.unmodifiableMap(m);
    }

    // Explicit per-class allowlist (no package wildcards): every entry is either
    // (a) a hooked & tested Spring async executor or its internal delegate,
    // (b) a hooked servlet server connector/lifecycle class (Tomcat, Jetty,
    //     Undertow/XNIO/JBoss Threads) whose request dispatch enters a hooked
    //     request entry, or
    // (c) a verified non-app-dispatching JDBC pool/driver/DB/Hibernate/
    //     Micrometer/Spring Boot startup-shutdown infrastructure class observed
    //     on Spring PetClinic.
    // Unhooked async libraries (Kotlin coroutines, Reactor, OkHttp, Guava,
    // Spring Integration, RxJava, Akka/Pekko, gRPC, Netty, HttpAsyncClient,
    // NATS, custom callbacks, H2 FileAsync) are absent and fail closed by
    // construction.
    private static final Set<String> ALLOWLISTED_DISPATCH_CLASSES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    // (a) Hooked Spring async executors & delegates
                    "org/springframework/context/event/SimpleApplicationEventMulticaster",
                    "org/springframework/core/task/AsyncTaskExecutor",
                    "org/springframework/core/task/SimpleAsyncTaskExecutor",
                    "org/springframework/scheduling/concurrent/SimpleAsyncTaskScheduler",
                    "org/springframework/scheduling/concurrent/ThreadPoolTaskExecutor",
                    "org/springframework/scheduling/concurrent/ThreadPoolTaskExecutor$1",
                    "org/springframework/scheduling/concurrent/ThreadPoolTaskScheduler",
                    "org/springframework/scheduling/concurrent/ThreadPoolTaskScheduler$1",
                    "org/springframework/scheduling/concurrent/ConcurrentTaskExecutor",
                    "org/springframework/aop/interceptor/AsyncExecutionAspectSupport",
                    // (b1) Tomcat (request dispatch enters hooked CoyoteAdapter)
                    "org/apache/catalina/core/ContainerBase",
                    "org/apache/catalina/core/StandardServer",
                    "org/apache/catalina/core/StandardVirtualThreadExecutor",
                    "org/apache/catalina/servlets/CGIServlet$CGIRunner",
                    "org/apache/catalina/startup/Catalina$CatalinaShutdownHook",
                    "org/apache/catalina/startup/ContextConfig",
                    "org/apache/catalina/startup/HostConfig",
                    "org/apache/catalina/startup/UserConfig",
                    "org/apache/catalina/util/TimeBucketCounter",
                    "org/apache/catalina/util/TimeBucketCounter$MaintenanceMonitor",
                    "org/apache/catalina/util/TimeBucketCounterBase",
                    "org/apache/catalina/util/TimeBucketCounterBase$MaintenanceMonitor",
                    "org/apache/coyote/AbstractProtocol",
                    "org/apache/juli/AsyncFileHandler$DropFlushPolicy",
                    "org/apache/juli/AsyncFileHandler$DropLastPolicy",
                    "org/apache/juli/ClassLoaderLogManager$Cleaner",
                    "org/apache/juli/FileHandler",
                    "org/apache/tomcat/util/net/AbstractEndpoint",
                    "org/apache/tomcat/util/net/AprEndpoint",
                    "org/apache/tomcat/util/net/AprEndpoint$Poller",
                    "org/apache/tomcat/util/net/AprEndpoint$Sendfile",
                    "org/apache/tomcat/util/net/NioEndpoint",
                    "org/apache/tomcat/util/net/Nio2Channel",
                    "org/apache/tomcat/util/net/Nio2Endpoint",
                    "org/apache/tomcat/util/net/Nio2Endpoint$Nio2Acceptor",
                    "org/apache/tomcat/util/net/Nio2Endpoint$Nio2SocketWrapper",
                    "org/apache/tomcat/util/net/Nio2Endpoint$SocketProcessor",
                    "org/apache/tomcat/util/net/SecureNio2Channel",
                    "org/apache/tomcat/util/net/SecureNio2Channel$1",
                    "org/apache/tomcat/util/net/SecureNio2Channel$2",
                    "org/apache/tomcat/util/net/SecureNio2Channel$3",
                    "org/apache/tomcat/util/net/SecureNio2Channel$4",
                    "org/apache/tomcat/util/net/SecureNio2Channel$FutureFlush",
                    "org/apache/tomcat/util/net/SecureNio2Channel$FutureRead",
                    "org/apache/tomcat/util/net/SecureNio2Channel$FutureWrite",
                    "org/apache/tomcat/util/net/SocketProperties",
                    "org/apache/tomcat/util/net/SocketWrapperBase",
                    "org/apache/tomcat/util/net/SocketWrapperBase$OperationState",
                    "org/apache/tomcat/util/net/WriteBuffer",
                    "org/apache/tomcat/util/threads/ScheduledThreadPoolExecutor",
                    "org/apache/tomcat/util/threads/TaskQueue",
                    "org/apache/tomcat/util/threads/TaskThread",
                    "org/apache/tomcat/util/threads/ThreadPoolExecutor",
                    "org/apache/tomcat/websocket/WsWebSocketContainer",
                    // (b2) Jetty (request dispatch enters hooked HttpChannel/ServletChannel)
                    "org/eclipse/jetty/io/AbstractConnection",
                    "org/eclipse/jetty/io/SelectorManager",
                    "org/eclipse/jetty/io/ssl/SslConnection$DecryptedEndPoint",
                    "org/eclipse/jetty/io/ssl/SslConnection$DecryptedEndPoint$IncompleteWriteCallback",
                    "org/eclipse/jetty/server/AbstractConnector",
                    "org/eclipse/jetty/server/AsyncRequestLogWriter",
                    "org/eclipse/jetty/server/AsyncRequestLogWriter$WriterThread",
                    "org/eclipse/jetty/server/HttpChannel",
                    "org/eclipse/jetty/server/HttpConnection",
                    "org/eclipse/jetty/server/HttpInput",
                    "org/eclipse/jetty/server/ShutdownMonitor",
                    "org/eclipse/jetty/server/handler/ShutdownHandler$1",
                    "org/eclipse/jetty/util/thread/ExecutorThreadPool",
                    "org/eclipse/jetty/util/thread/QueuedThreadPool",
                    "org/eclipse/jetty/util/thread/ReservedThreadExecutor",
                    "org/eclipse/jetty/util/thread/ScheduledExecutorScheduler",
                    "org/eclipse/jetty/util/thread/ShutdownThread",
                    "org/eclipse/jetty/util/thread/TimerScheduler",
                    "org/eclipse/jetty/util/thread/TryExecutor$NoTryExecutor",
                    "org/eclipse/jetty/util/thread/strategy/EatWhatYouKill",
                    "org/eclipse/jetty/util/thread/strategy/ExecuteProduceConsume",
                    "org/eclipse/jetty/util/thread/strategy/ProduceConsume",
                    "org/eclipse/jetty/util/thread/strategy/ProduceExecuteConsume",
                    // (b3) Undertow / XNIO / JBoss Threads (request dispatch enters hooked Connectors)
                    "io/undertow/protocols/ssl/SslConduit",
                    "io/undertow/server/Connectors",
                    "io/undertow/server/DefaultByteBufferPool",
                    "io/undertow/server/HttpServerExchange",
                    "io/undertow/server/protocol/framed/AbstractFramedChannel",
                    "io/undertow/server/protocol/framed/ShutdownFallbackExecutor",
                    "io/undertow/server/protocol/http/HttpReadListener",
                    "io/undertow/servlet/spec/AsyncContextImpl",
                    "io/undertow/util/PipeliningExecutor",
                    "io/undertow/util/SimpleObjectPool$SimplePooledObject",
                    "org/xnio/AbstractIoFuture",
                    "org/xnio/AutomaticReference$Cleaner$1",
                    "org/xnio/ByteBufferPool",
                    "org/xnio/ChannelListeners",
                    "org/xnio/ChannelListeners$13",
                    "org/xnio/XnioIoThread",
                    "org/xnio/XnioWorker$ExecutorServiceTaskPool",
                    "org/xnio/XnioWorker$ThreadPoolExecutorTaskPool",
                    "org/jboss/threads/DelegatingExecutor",
                    "org/jboss/threads/DelegatingExecutorService",
                    "org/jboss/threads/DelegatingScheduledExecutorService",
                    "org/jboss/threads/EnhancedQueueExecutor",
                    "org/jboss/threads/HandoffRejectedExecutionHandler",
                    "org/jboss/threads/JBossThread",
                    "org/jboss/threads/ManagedThreadPoolExecutor",
                    "org/jboss/threads/ViewExecutor",
                    "org/jboss/threads/ViewExecutor$TaskWrapper",
                    // (c) Verified non-app-dispatching JDBC/DB/Hibernate/Caffeine/Micrometer/Spring Boot infra
                    //     (Caffeine async entry points are hooked via LATCH_ON_CALL on Caffeine/CacheLoader/LocalAsyncCache)
                    "com/github/benmanes/caffeine/cache/BoundedLocalCache",
                    "com/github/benmanes/caffeine/cache/UnboundedLocalCache",
                    "com/zaxxer/hikari/pool/HikariPool",
                    "com/zaxxer/hikari/pool/ProxyLeakTask",
                    "com/zaxxer/hikari/util/ConcurrentBag",
                    "com/mysql/cj/jdbc/AbandonedConnectionCleanupThread",
                    "org/postgresql/Driver",
                    "org/h2/mvstore/FileStore",
                    "org/h2/mvstore/MVStore",
                    "org/h2/mvstore/db/LobStorageMap",
                    "org/h2/util/MathUtils",
                    "org/h2/util/Task",
                    "org/h2/util/Utils",
                    "org/hibernate/internal/util/collections/BoundedConcurrentHashMap$LIRS",
                    "org/hibernate/internal/util/collections/BoundedConcurrentHashMap$LRU",
                    "io/micrometer/core/instrument/HighCardinalityTagsDetector",
                    "org/springframework/beans/factory/support/DefaultListableBeanFactory",
                    "org/springframework/boot/actuate/context/ShutdownEndpoint",
                    "org/springframework/boot/autoconfigure/condition/OnClassCondition$ThreadedOutcomesResolver",
                    "org/springframework/boot/autoconfigure/preinitialize/BackgroundPreinitializingApplicationListener",
                    "org/springframework/boot/autoconfigure/ssl/FileWatcher$WatcherThread",
                    "org/springframework/boot/context/metrics/buffering/BufferingApplicationStartup",
                    "org/springframework/boot/tomcat/GracefulShutdown",
                    "org/springframework/boot/tomcat/TomcatWebServer",
                    "org/springframework/boot/tomcat/TomcatWebServer$1",
                    "org/springframework/boot/web/embedded/tomcat/GracefulShutdown",
                    "org/springframework/boot/web/embedded/tomcat/TomcatWebServer",
                    "org/springframework/boot/web/embedded/tomcat/TomcatWebServer$1",
                    "org/springframework/context/support/AbstractApplicationContext$1",
                    "org/springframework/context/support/DefaultLifecycleProcessor",
                    "org/springframework/jmx/support/ConnectorServerFactoryBean$1")));

    static Set<String> allowlistedDispatchClasses() {
        return ALLOWLISTED_DISPATCH_CLASSES;
    }

    private static final String SPRING_EVENT_MULTICASTER =
            "org/springframework/context/event/SimpleApplicationEventMulticaster";

    private static final Set<String> RAW_CONSUMER_OWNERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "org/apache/kafka/clients/consumer/KafkaConsumer",
            "org/apache/kafka/clients/consumer/Consumer",
            "com/rabbitmq/client/Channel",
            "javax/jms/MessageConsumer",
            "jakarta/jms/MessageConsumer")));

    private static final Set<String> COUNTED = new CopyOnWriteArraySet<>();
    private static final Set<String> SEEN_UNCOUNTED = new CopyOnWriteArraySet<>();
    private static final Set<String> SEEN_UNHOOKED_CONSUMERS = new CopyOnWriteArraySet<>();
    private static final Set<String> SEEN_UNHOOKED_ASYNC_DISPATCHERS = new CopyOnWriteArraySet<>();
    private static final Set<String> HOOKED_SPRING_CLASSES = new CopyOnWriteArraySet<>();
    private static volatile boolean serverHookFailed;
    private static volatile boolean listenerHookFailed;
    private static volatile boolean asyncHookFailed;
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
            "java/util/TimerTask",
            "org/springframework/scheduling/TaskScheduler",
            "org/springframework/util/concurrent/ListenableFuture",
            "org/springframework/util/concurrent/SettableListenableFuture",
            "java/util/concurrent/SubmissionPublisher",
            "java/util/concurrent/BlockingQueue",
            "java/util/concurrent/BlockingDeque",
            "java/util/concurrent/TransferQueue",
            "java/util/concurrent/LinkedBlockingQueue",
            "java/util/concurrent/ArrayBlockingQueue",
            "java/util/concurrent/LinkedBlockingDeque",
            "java/util/concurrent/PriorityBlockingQueue",
            "java/util/concurrent/DelayQueue",
            "java/util/concurrent/SynchronousQueue",
            "java/util/concurrent/LinkedTransferQueue",
            "java/util/concurrent/ConcurrentLinkedQueue",
            "java/util/concurrent/ConcurrentLinkedDeque",
            "java/util/Queue",
            "java/util/Deque",
            "org/springframework/kafka/core/KafkaTemplate",
            "org/springframework/kafka/core/KafkaOperations",
            "org/apache/kafka/clients/producer/KafkaProducer",
            "org/apache/kafka/clients/producer/Producer",
            "org/springframework/amqp/rabbit/core/RabbitTemplate",
            "org/springframework/amqp/rabbit/core/RabbitOperations",
            "org/springframework/amqp/core/AmqpTemplate",
            "org/springframework/jms/core/JmsTemplate",
            "org/springframework/jms/core/JmsOperations",
            "org/springframework/jms/core/JmsMessagingTemplate",
            "org/springframework/cloud/stream/function/StreamBridge",
            "reactor/kafka/sender/KafkaSender",
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
            "invokeAny", "fork", "subscribe", "start", "startVirtualThread", "cancel",
            "addListener", "addCallback", "transform", "transformAsync", "catching", "catchingAsync",
            "poll", "basicConsume", "receive", "setMessageListener",
            "offer", "put", "transfer", "tryTransfer",
            "send", "sendDefault", "convertAndSend", "convertSendAndReceive", "sendAndReceive")));

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

    static boolean hasUnhookedAsyncDispatcher() {
        return !SEEN_UNHOOKED_ASYNC_DISPATCHERS.isEmpty();
    }

    static boolean hadAsyncHookFailure() {
        return asyncHookFailed;
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

    static Set<String> snapshotUnhookedAsyncDispatchers() {
        return new HashSet<>(SEEN_UNHOOKED_ASYNC_DISPATCHERS);
    }

    static void restoreUnhookedAsyncDispatchers(Set<String> snapshot) {
        SEEN_UNHOOKED_ASYNC_DISPATCHERS.clear();
        if (snapshot != null) {
            SEEN_UNHOOKED_ASYNC_DISPATCHERS.addAll(snapshot);
        }
    }

    static void setHookStatusForTests(boolean serverFailed, boolean listenerFailed, boolean jpmsSkipped) {
        setHookStatusForTests(serverFailed, listenerFailed, jpmsSkipped, false);
    }

    static void setHookStatusForTests(boolean serverFailed, boolean listenerFailed, boolean jpmsSkipped,
            boolean rewriteFailed) {
        setHookStatusForTests(serverFailed, listenerFailed, jpmsSkipped, rewriteFailed, false);
    }

    static void setHookStatusForTests(boolean serverFailed, boolean listenerFailed, boolean jpmsSkipped,
            boolean rewriteFailed, boolean asyncFailed) {
        serverHookFailed = serverFailed;
        listenerHookFailed = listenerFailed;
        asyncHookFailed = asyncFailed;
        jpmsGuardTriggered = jpmsSkipped;
        appRewriteFailed = rewriteFailed;
        SEEN_UNHOOKED_CONSUMERS.clear();
        SEEN_UNHOOKED_ASYNC_DISPATCHERS.clear();
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
        if (SPRING_EVENT_MULTICASTER.equals(className)) {
            return hookSpringEventMulticaster(loader, className, classfileBuffer);
        }
        LatchEntry latchEntry = LATCH_ON_CALL.get(className);
        if (latchEntry != null) {
            return hookLatchOnCall(loader, className, classfileBuffer, latchEntry);
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
                scanUnhookedReferences(className, classfileBuffer, false, false);
                if (!canAccessGate(loader, protectionDomain)) {
                    if (seesGate(loader)) {
                        jpmsGuardTriggered = true;
                    } else {
                        appRewriteFailed = true;
                    }
                    scanUnhookedReferences(className, classfileBuffer, true, true);
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
            } else if (className != null && !isInternalOrAgentClass(className)) {
                // Fail-closed read-only constant-pool scan of EVERY non-JDK,
                // non-agent loaded class (both app and library classes). Any
                // class referencing a concurrency dispatch primitive or unhooked
                // async dispatcher latches asyncExecutors=false UNLESS its exact
                // name is in ALLOWLISTED_DISPATCH_CLASSES.
                boolean checkDispatch = !ALLOWLISTED_DISPATCH_CLASSES.contains(className);
                scanUnhookedReferences(className, classfileBuffer, !isKnownMessagingPoolWrapper(className),
                        checkDispatch);
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
            } else if ("scheduled".equals(entry.job) || "quartz".equals(entry.job)) {
                // Loading a scheduled job runner (@Scheduled or Quartz) means
                // outbox/background ticks can execute app lines written by an
                // earlier request; fail closed on asyncExecutors before W1 COV.
                recordUnhookedAsyncDispatcher("scheduled-job:" + entry.job, className);
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
        if (SPRING_EVENT_MULTICASTER.equals(className)) {
            synchronized (changed) {
                Set<String> names = changed.get(loader);
                if (names == null || !names.contains(className)) {
                    return null;
                }
            }
            try {
                return wrapSpringEventMulticaster(classfile);
            } catch (Throwable t) {
                asyncHookFailed = true;
                LOGGER.log(Level.WARNING, "Keploy dedup could not re-hook " + className, t);
                return null;
            }
        }
        LatchEntry latchEntry = LATCH_ON_CALL.get(className);
        if (latchEntry != null) {
            synchronized (changed) {
                Set<String> names = changed.get(loader);
                if (names == null || !names.contains(className)) {
                    return null;
                }
            }
            try {
                return wrapLatchOnCall(classfile, latchEntry);
            } catch (Throwable t) {
                asyncHookFailed = true;
                LOGGER.log(Level.WARNING, "Keploy dedup could not re-hook " + className, t);
                return null;
            }
        }
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
            } else if (tag == 10 || tag == 11) {
                String owner = reader.readClass(offset, buf);
                if ("java/util/Collection".equals(owner)) {
                    int ntOffset = reader.getItem(reader.readUnsignedShort(offset + 2));
                    if (ntOffset > 0 && "add".equals(reader.readUTF8(ntOffset, buf))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private byte[] hookLatchOnCall(ClassLoader loader, String className, byte[] classfileBuffer,
            LatchEntry latchEntry) {
        String name = className.replace('/', '.');
        try {
            if (!seesGate(loader)) {
                asyncHookFailed = true;
                LOGGER.log(Level.WARNING, "Keploy dedup cannot hook " + name
                        + ": its class loader does not see keploy-sdk.jar", (Throwable) null);
                return null;
            }
            byte[] wrapped = wrapLatchOnCall(classfileBuffer, latchEntry);
            if (wrapped == null) {
                // Only fail closed if this class version actually references a
                // dispatch primitive without exposing the expected methods (e.g.
                // Spring 5.3 ConcurrentMapCache has no async retrieve method).
                if (hasDispatchPrimitiveReference(classfileBuffer)) {
                    asyncHookFailed = true;
                    LOGGER.log(Level.WARNING, "Keploy dedup cannot hook " + name
                            + ": none of " + latchEntry.methods + " found");
                }
                return null;
            }
            synchronized (changed) {
                Set<String> names = changed.get(loader);
                if (names == null) {
                    names = new HashSet<>();
                    changed.put(loader, names);
                }
                names.add(className);
            }
            return wrapped;
        } catch (Throwable t) {
            asyncHookFailed = true;
            LOGGER.log(Level.WARNING, "Keploy dedup could not hook " + name, t);
            return null;
        }
    }

    static byte[] wrapLatchOnCall(byte[] classfile, final LatchEntry latchEntry) {
        ClassReader reader = new ClassReader(classfile);
        ClassWriter writer = new ClassWriter(reader, 0);
        final boolean[] found = {false};
        final boolean[] alreadyHooked = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                        || !latchEntry.methods.contains(name)) {
                    return mv;
                }
                found[0] = true;
                final String reason = latchEntry.label + ":" + name;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    private boolean injected;

                    @Override
                    public void visitCode() {
                        super.visitCode();
                        if (!injected) {
                            injected = true;
                            super.visitLdcInsn(reason);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE,
                                    "noteRuntimeUntrackedDispatch", "(Ljava/lang/String;)V", false);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname, String desc,
                            boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC && GATE.equals(owner)
                                && "noteRuntimeUntrackedDispatch".equals(mname)) {
                            alreadyHooked[0] = true;
                        }
                        super.visitMethodInsn(opcode, owner, mname, desc, isInterface);
                    }

                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        super.visitMaxs(Math.max(maxStack, 1), maxLocals);
                    }
                };
            }
        }, 0);
        if (!found[0]) {
            return null;
        }
        return alreadyHooked[0] ? classfile : writer.toByteArray();
    }

    private static boolean hasDispatchPrimitiveReference(byte[] classfile) {
        if (classfile == null || classfile.length < 16) {
            return false;
        }
        try {
            ClassReader reader = new ClassReader(classfile);
            int itemCount = reader.getItemCount();
            char[] buf = new char[reader.getMaxStringLength()];
            for (int i = 1; i < itemCount; i++) {
                int offset = reader.getItem(i);
                if (offset == 0) {
                    continue;
                }
                int tag = reader.readByte(offset - 1);
                if (tag == 10 || tag == 11) {
                    String owner = reader.readClass(offset, buf);
                    Set<String> methods = DISPATCH_PRIMITIVE_METHODS.get(owner);
                    if (methods != null) {
                        int ntOffset = reader.getItem(reader.readUnsignedShort(offset + 2));
                        if (ntOffset > 0 && methods.contains(reader.readUTF8(ntOffset, buf))) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            return true;
        }
        return false;
    }

    private byte[] hookSpringEventMulticaster(ClassLoader loader, String className, byte[] classfileBuffer) {
        String name = className.replace('/', '.');
        try {
            if (isInterface(classfileBuffer)) {
                return null;
            }
            if (!seesGate(loader)) {
                asyncHookFailed = true;
                LOGGER.log(Level.WARNING, "Keploy dedup cannot inspect " + name
                        + ".setTaskExecutor: its class loader does not see keploy-sdk.jar", (Throwable) null);
                return null;
            }
            byte[] wrapped = wrapSpringEventMulticaster(classfileBuffer);
            if (wrapped == null) {
                asyncHookFailed = true;
                LOGGER.log(Level.WARNING, "Keploy dedup cannot inspect " + name
                        + ": setTaskExecutor(Executor) not found");
                return null;
            }
            synchronized (changed) {
                Set<String> names = changed.get(loader);
                if (names == null) {
                    names = new HashSet<>();
                    changed.put(loader, names);
                }
                names.add(className);
            }
            return wrapped;
        } catch (Throwable t) {
            asyncHookFailed = true;
            LOGGER.log(Level.WARNING, "Keploy dedup could not hook " + name + ".setTaskExecutor", t);
            return null;
        }
    }

    static byte[] wrapSpringEventMulticaster(byte[] classfile) {
        ClassReader reader = new ClassReader(classfile);
        ClassWriter writer = new ClassWriter(reader, 0);
        final boolean[] found = {false};
        final boolean[] alreadyHooked = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_STATIC)) != 0) {
                    return mv;
                }
                final boolean isSetExec = "setTaskExecutor".equals(name)
                        && "(Ljava/util/concurrent/Executor;)V".equals(descriptor);
                final boolean isMulticast = "multicastEvent".equals(name)
                        && ("(Lorg/springframework/context/ApplicationEvent;)V".equals(descriptor)
                                || "(Lorg/springframework/context/ApplicationEvent;Lorg/springframework/core/ResolvableType;)V"
                                        .equals(descriptor));
                if (!isSetExec && !isMulticast) {
                    return mv;
                }
                if (isSetExec) {
                    found[0] = true;
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    private boolean injected;

                    @Override
                    public void visitCode() {
                        super.visitCode();
                        if (!injected) {
                            injected = true;
                            if (isSetExec) {
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                            } else {
                                // Invoke this.getTaskExecutor() virtually so subclasses overriding
                                // getTaskExecutor() without calling setTaskExecutor() are still caught.
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SPRING_EVENT_MULTICASTER,
                                        "getTaskExecutor", "()Ljava/util/concurrent/Executor;", false);
                            }
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE,
                                    "noteSpringMulticasterTaskExecutor", "(Ljava/lang/Object;)V", false);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname, String desc,
                            boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC && GATE.equals(owner)
                                && "noteSpringMulticasterTaskExecutor".equals(mname)) {
                            alreadyHooked[0] = true;
                        }
                        super.visitMethodInsn(opcode, owner, mname, desc, isInterface);
                    }

                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        super.visitMaxs(Math.max(maxStack, 1), maxLocals);
                    }
                };
            }
        }, 0);
        if (!found[0]) {
            return null;
        }
        return alreadyHooked[0] ? classfile : writer.toByteArray();
    }

    private static void recordUnhookedAsyncDispatcher(String dispatcher, String sourceClass) {
        if (SEEN_UNHOOKED_ASYNC_DISPATCHERS.add(dispatcher)) {
            String from = (sourceClass == null || sourceClass.isEmpty())
                    ? "<unknown>" : sourceClass.replace('/', '.');
            LOGGER.log(Level.INFO, "Keploy dedup: observed reference to unhooked async dispatcher "
                    + dispatcher + " in " + from + "; declaring asyncExecutors=false");
        }
    }

    private static void scanUnhookedReferences(String className, byte[] classfile, boolean includeRawConsumers,
            boolean checkDispatchPrimitives) {
        if (classfile == null || classfile.length < 16) {
            return;
        }
        boolean allowlisted = className != null && ALLOWLISTED_DISPATCH_CLASSES.contains(className);
        try {
            ClassReader reader = new ClassReader(classfile);
            if (!allowlisted && checkDispatchPrimitives && "java/lang/Thread".equals(reader.getSuperName())) {
                recordUnhookedAsyncDispatcher("java.lang.Thread subclass", className);
            }
            int itemCount = reader.getItemCount();
            char[] buf = new char[reader.getMaxStringLength()];
            boolean hasRawConsumerOwner = false;
            for (int i = 1; i < itemCount; i++) {
                int offset = reader.getItem(i);
                if (offset == 0) {
                    continue;
                }
                int tag = reader.readByte(offset - 1);
                if (tag == 7) {
                    String cls = reader.readUTF8(offset, buf);
                    if (!allowlisted) {
                        String unhookedAsync = UNHOOKED_ASYNC_DISPATCHERS.get(cls);
                        if (unhookedAsync != null) {
                            recordUnhookedAsyncDispatcher(unhookedAsync, className);
                        }
                    }
                    if (includeRawConsumers && RAW_CONSUMER_OWNERS.contains(cls)) {
                        hasRawConsumerOwner = true;
                    }
                } else if (tag == 8) {
                    // Catch reflective Class.forName("java.util.concurrent.SubmissionPublisher")
                    // and similar string-literal lookups in non-allowlisted classes.
                    if (!allowlisted) {
                        String literal = reader.readUTF8(offset, buf);
                        if (literal != null && literal.indexOf('.') > 0) {
                            String slash = literal.replace('.', '/');
                            String unhookedAsync = UNHOOKED_ASYNC_DISPATCHERS.get(slash);
                            if (unhookedAsync != null) {
                                recordUnhookedAsyncDispatcher(unhookedAsync, className);
                            }
                        }
                    }
                } else if ((tag == 10 || tag == 11) && !allowlisted) {
                    String owner = reader.readClass(offset, buf);
                    Set<String> methods = checkDispatchPrimitives
                            ? DISPATCH_PRIMITIVE_METHODS.get(owner)
                            : JDK_ASYNC_HTTP_METHODS.get(owner);
                    if (methods != null) {
                        int ntOffset = reader.getItem(reader.readUnsignedShort(offset + 2));
                        if (ntOffset > 0) {
                            String mname = reader.readUTF8(ntOffset, buf);
                            if (methods.contains(mname)) {
                                recordUnhookedAsyncDispatcher(owner.replace('/', '.') + "." + mname, className);
                            }
                        }
                    }
                }
            }
            if (!hasRawConsumerOwner) {
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

    private static boolean isSingleArgExecutorExecute(String mname, String desc) {
        return "execute".equals(mname) && "(Ljava/lang/Runnable;)V".equals(desc);
    }

    private static boolean isSingleArgExecutorSubmit(String mname, String desc) {
        return ("submit".equals(mname) || "submitListenable".equals(mname) || "submitCompletable".equals(mname))
                && (desc.startsWith("(Ljava/lang/Runnable;)")
                        || desc.startsWith("(Ljava/util/concurrent/Callable;)"));
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

    private static boolean isSpringListenableFutureCallback(int opcode, String owner, String mname, String desc) {
        return (opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEVIRTUAL)
                && "addCallback".equals(mname)
                && desc != null
                && desc.startsWith("(Lorg/springframework/util/concurrent/");
    }

    private static boolean isSubmissionPublisherCall(String owner, String mname) {
        return "java/util/concurrent/SubmissionPublisher".equals(owner)
                && ("submit".equals(mname) || "offer".equals(mname));
    }

    private static boolean isBlockingQueueEnqueue(String owner, String mname) {
        return BLOCKING_QUEUE_OWNERS.contains(owner) && BLOCKING_QUEUE_ENQUEUE_METHODS.contains(mname);
    }

    private static boolean isMessagingProducerCall(String owner, String mname) {
        if (owner == null || mname == null) {
            return false;
        }
        if ("org/springframework/kafka/core/KafkaTemplate".equals(owner)
                || "org/springframework/kafka/core/KafkaOperations".equals(owner)) {
            return "send".equals(mname) || "sendDefault".equals(mname);
        }
        if ("org/apache/kafka/clients/producer/KafkaProducer".equals(owner)
                || "org/apache/kafka/clients/producer/Producer".equals(owner)) {
            return "send".equals(mname);
        }
        if ("org/springframework/amqp/rabbit/core/RabbitTemplate".equals(owner)
                || "org/springframework/amqp/rabbit/core/RabbitOperations".equals(owner)
                || "org/springframework/amqp/core/AmqpTemplate".equals(owner)) {
            return "send".equals(mname) || "convertAndSend".equals(mname) || "convertSendAndReceive".equals(mname);
        }
        if ("org/springframework/jms/core/JmsTemplate".equals(owner)
                || "org/springframework/jms/core/JmsOperations".equals(owner)
                || "org/springframework/jms/core/JmsMessagingTemplate".equals(owner)) {
            return "send".equals(mname) || "convertAndSend".equals(mname)
                    || "sendAndReceive".equals(mname) || "convertSendAndReceive".equals(mname);
        }
        if ("org/springframework/cloud/stream/function/StreamBridge".equals(owner)) {
            return "send".equals(mname);
        }
        if ("reactor/kafka/sender/KafkaSender".equals(owner)) {
            return "send".equals(mname) || "createOutbound".equals(mname);
        }
        return false;
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
                || "org/springframework/scheduling/concurrent/SimpleAsyncTaskScheduler".equals(owner)
                || "org/springframework/scheduling/concurrent/ThreadPoolTaskExecutor".equals(owner)
                || "org/springframework/scheduling/concurrent/ThreadPoolTaskScheduler".equals(owner)
                || "org/springframework/scheduling/concurrent/ConcurrentTaskExecutor".equals(owner);
    }

    private static boolean isInstanceMethodHandle(Handle h) {
        int tag = h.getTag();
        return tag == Opcodes.H_INVOKEVIRTUAL
                || tag == Opcodes.H_INVOKEINTERFACE
                || tag == Opcodes.H_INVOKESPECIAL;
    }

    private static boolean isExecutorExecuteMethodRefTarget(Handle h) {
        return isInstanceMethodHandle(h)
                && !isHookedSpringMethodRefOwner(h.getOwner())
                && isSingleArgExecutorExecute(h.getName(), h.getDesc());
    }

    private static boolean isExecutorSubmitMethodRefTarget(Handle h) {
        return isInstanceMethodHandle(h)
                && !isHookedSpringMethodRefOwner(h.getOwner())
                && (isSingleArgExecutorSubmit(h.getName(), h.getDesc())
                        || isTwoArgExecutorSubmit(h.getName(), h.getDesc()));
    }

    private static boolean isAsyncMethodRefTarget(Handle h) {
        String owner = h.getOwner();
        String mname = h.getName();
        String desc = h.getDesc();
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
        if (isSubmissionPublisherCall(owner, mname)
                || isBlockingQueueEnqueue(owner, mname)
                || isMessagingProducerCall(owner, mname)
                || ("addCallback".equals(mname) && desc != null
                        && desc.startsWith("(Lorg/springframework/util/concurrent/"))) {
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
                                    boolean execRef = isExecutorExecuteMethodRefTarget(h);
                                    boolean submitRef = !execRef && isExecutorSubmitMethodRefTarget(h);
                                    if (execRef || submitRef) {
                                        Type[] indyArgs = Type.getArgumentTypes(indyDesc);
                                        boolean isBoundMethodRef = bsm != null
                                                && "java/lang/invoke/LambdaMetafactory".equals(bsm.getOwner())
                                                && indyArgs.length == 1
                                                && indyArgs[0].getSort() == Type.OBJECT;
                                        if (isBoundMethodRef) {
                                            // Bound method ref: captured receiver sits on top of stack.
                                            // DUP receiver and verify its runtime Executor/submit type
                                            // so plain non-Executor helpers (e.g. txHelper::execute)
                                            // are ignored with zero contamination, while real untracked
                                            // executors captured outside any window latch asyncExecutors=false.
                                            changed[0] = true;
                                            ensureExtraStack(1);
                                            super.visitInsn(Opcodes.DUP);
                                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE,
                                                    execRef ? "noteIfUntrackedExecutorMethodRef"
                                                            : "noteIfUntrackedSubmitMethodRef",
                                                    "(Ljava/lang/Object;)V", false);
                                        } else {
                                            // Unbound method ref (e.g. TxHelper::execute as BiConsumer)
                                            // or non-standard capture: LDC the handle's owner Class<?>
                                            // and verify assignability at runtime (note: checks only the
                                            // declaring owner class, not runtime subclasses passed later).
                                            changed[0] = true;
                                            ensureExtraStack(1);
                                            super.visitLdcInsn(Type.getObjectType(h.getOwner()));
                                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE,
                                                    execRef ? "noteIfUntrackedExecutorClass"
                                                            : "noteIfUntrackedSubmitClass",
                                                    "(Ljava/lang/Class;)V", false);
                                        }
                                        break;
                                    }
                                    if (isAsyncMethodRefTarget(h)) {
                                        changed[0] = true;
                                        ensureExtraStack(1);
                                        super.visitLdcInsn("untracked-async:method-ref:"
                                                + h.getOwner().replace('/', '.') + "." + h.getName());
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE,
                                                "noteUntrackedAsyncMethodRef",
                                                "(Ljava/lang/String;)V", false);
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
                            if ("cancel".equals(mname) && "()V".equals(desc)) {
                                // Record Timer.cancel() AFTER cancel() returns so
                                // cancelledAtNanos never precedes an already-started fire.
                                changed[0] = true;
                                ensureExtraStack(1);
                                super.visitInsn(Opcodes.DUP);
                                super.visitMethodInsn(opcode, owner, mname, desc, isInterface);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "noteTimerCancelled",
                                        "(Ljava/util/Timer;)V", false);
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
                            changed[0] = true;
                            ensureExtraStack(2);
                            super.visitMethodInsn(opcode, owner, mname, desc, isInterface);
                            super.visitInsn(Opcodes.DUP);
                            super.visitLdcInsn("untracked-async:virtual-thread");
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "trackUntrackedThread",
                                    "(Ljava/lang/Object;Ljava/lang/String;)V", false);
                            return;
                        }
                        if ((opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEVIRTUAL)
                                && "start".equals(mname)
                                && "(Ljava/lang/Runnable;)Ljava/lang/Thread;".equals(desc)) {
                            changed[0] = true;
                            ensureExtraStack(2);
                            super.visitMethodInsn(opcode, owner, mname, desc, isInterface);
                            super.visitInsn(Opcodes.DUP);
                            super.visitLdcInsn("untracked-async:thread-builder");
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "trackUntrackedThread",
                                    "(Ljava/lang/Object;Ljava/lang/String;)V", false);
                            return;
                        }
                        // Flag-only async submission sites (task untouched, 100% app-safe).
                        if (isGuavaAsyncCall(opcode, owner, mname, desc)) {
                            emitUntrackedFlag("untracked-async:guava-future");
                        } else if (isSpringListenableFutureCallback(opcode, owner, mname, desc)) {
                            emitUntrackedFlag("untracked-async:spring-listenable-future");
                        } else if (isSubmissionPublisherCall(owner, mname)) {
                            emitUntrackedFlag("untracked-async:submission-publisher");
                        } else if (isBlockingQueueEnqueue(owner, mname)) {
                            emitUntrackedFlag("untracked-async:blocking-queue");
                        } else if (isMessagingProducerCall(owner, mname)) {
                            emitUntrackedFlag("untracked-async:messaging-produce");
                        } else if ((opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEVIRTUAL)
                                && (("java/util/Queue".equals(owner)
                                                && ("offer".equals(mname) || "add".equals(mname))
                                                && "(Ljava/lang/Object;)Z".equals(desc))
                                        || ("java/util/Deque".equals(owner)
                                                && ((("offer".equals(mname) || "add".equals(mname)
                                                                || "offerFirst".equals(mname)
                                                                || "offerLast".equals(mname))
                                                        && "(Ljava/lang/Object;)Z".equals(desc))
                                                        || (("addFirst".equals(mname) || "addLast".equals(mname)
                                                                || "push".equals(mname))
                                                                && "(Ljava/lang/Object;)V".equals(desc))))
                                        || ("java/util/Collection".equals(owner)
                                                && "add".equals(mname)
                                                && "(Ljava/lang/Object;)Z".equals(desc)))) {
                            changed[0] = true;
                            ensureExtraStack(2);
                            super.visitInsn(Opcodes.DUP2);
                            super.visitInsn(Opcodes.POP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "noteIfBlockingQueue",
                                    "(Ljava/lang/Object;)V", false);
                        } else if (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE) {
                            if (isSingleArgExecutorExecute(mname, desc)) {
                                // Decide at runtime by actual receiver (must be java.util.concurrent.Executor,
                                // neither an allow-listed inline executor nor a hooked+verified Spring executor).
                                changed[0] = true;
                                ensureExtraStack(2);
                                super.visitInsn(Opcodes.DUP2);
                                super.visitInsn(Opcodes.POP);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "noteIfUntrackedExecutor",
                                        "(Ljava/lang/Object;)V", false);
                            } else if (isSingleArgExecutorSubmit(mname, desc)) {
                                // Decide at runtime by actual receiver (must be ExecutorService,
                                // CompletionService, or Spring AsyncTaskExecutor).
                                changed[0] = true;
                                ensureExtraStack(2);
                                super.visitInsn(Opcodes.DUP2);
                                super.visitInsn(Opcodes.POP);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "noteIfUntrackedSubmit",
                                        "(Ljava/lang/Object;)V", false);
                            } else if (isTwoArgExecutorSubmit(mname, desc)) {
                                // Stack: [..., recv, runnable, result] (all cat-1 refs).
                                // Inspect recv at runtime without locals so Guava/Netty
                                // submit(Runnable, T) returning subtype futures are caught
                                // while non-executor helpers are ignored.
                                changed[0] = true;
                                ensureExtraStack(2);
                                super.visitInsn(Opcodes.DUP2_X1);
                                super.visitInsn(Opcodes.POP2);
                                super.visitInsn(Opcodes.DUP_X2);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "noteIfUntrackedSubmit",
                                        "(Ljava/lang/Object;)V", false);
                            } else if (isForkJoinTaskSubmission(owner, mname, desc)) {
                                emitUntrackedFlag("untracked-async:forkjoin-task");
                            } else if (isTimerSchedule(owner, mname, desc)) {
                                emitUntrackedFlag("untracked-async:timer");
                            } else if (isUntrackedScheduleOrInvokeAny(mname, desc)) {
                                if (!"invokeAny".equals(mname)
                                        && desc.endsWith(")Ljava/util/concurrent/ScheduledFuture;")) {
                                    changed[0] = true;
                                    ensureExtraStack(2);
                                    super.visitMethodInsn(opcode, owner, mname, desc, isInterface);
                                    super.visitInsn(Opcodes.DUP);
                                    super.visitLdcInsn("untracked-async:schedule");
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "trackUntrackedFuture",
                                            "(Ljava/lang/Object;Ljava/lang/String;)V", false);
                                    return;
                                }
                                emitUntrackedFlag("untracked-async:" + ("invokeAny".equals(mname)
                                        ? "invokeAny" : "schedule"));
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
                            } else if (opcode == Opcodes.INVOKEVIRTUAL
                                    && "cancel".equals(mname) && "()Z".equals(desc)) {
                                // Track TimerTask.cancel() along with its boolean return value
                                // (true => cancelled before firing; false => already fired/running):
                                // [recv] -> DUP -> [recv, recv] -> INVOKEVIRTUAL -> [recv, z]
                                // -> DUP_X1 -> [z, recv, z] -> INVOKESTATIC -> [z].
                                changed[0] = true;
                                ensureExtraStack(2);
                                super.visitInsn(Opcodes.DUP);
                                super.visitMethodInsn(opcode, owner, mname, desc, isInterface);
                                super.visitInsn(Opcodes.DUP_X1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "noteTimerTaskCancelResult",
                                        "(Ljava/lang/Object;Z)V", false);
                                return;
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

    static final class LatchEntry {

        final String label;
        final Set<String> methods;

        private LatchEntry(String label, Set<String> methods) {
            this.label = label;
            this.methods = Collections.unmodifiableSet(methods);
        }
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
