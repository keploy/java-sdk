package org.example.servers;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Real application process launched in forked {@code -javaagent:keploy-sdk.jar}
 * JVMs by {@code ForkedAsyncAndHonestyTest} to verify v3 protocol honesty end-to-end
 * (premain -> index OR appPackagePrefix external-JaCoCo mode -> real app
 * bytecode -> TCP START/END -> HELLO + COV).
 */
public final class AsyncHonestyApp {

    private static final ExecutorService PLAIN_POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "app-plain-pool");
        t.setDaemon(true);
        return t;
    });
    private static final ScheduledExecutorService SCHED = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "app-sched");
        t.setDaemon(true);
        return t;
    });
    private static final ExecutorService SERIALIZABLE_ONLY_POOL = new SerializableOnlyExecutor(false);
    private static final ExecutorService ASYNC_SERIALIZABLE_POOL = new SerializableOnlyExecutor(true);
    private static final ConcreteCustomPool CONCRETE_POOL = new ConcreteCustomPool();
    private static volatile java.util.concurrent.CountDownLatch VIRTUAL_RELEASE_LATCH;
    private static volatile Thread VIRTUAL_THREAD;

    private AsyncHonestyApp() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        AutoCloseable server;
        int port;
        if ("tomcat".equals(mode) || "precaptured-tomcat".equals(mode)) {
            if ("precaptured-tomcat".equals(mode)) {
                PreCapturedDispatcher.touch();
            }
            File base = Files.createTempDirectory("tomcat-honesty").toFile();
            Tomcat tomcat = new Tomcat();
            tomcat.setBaseDir(base.getPath());
            tomcat.setPort(0);
            Context context = tomcat.addContext("", base.getPath());
            Tomcat.addServlet(context, "async", new AsyncServlet());
            context.addServletMappingDecoded("/*", "async");
            tomcat.start();
            port = tomcat.getConnector().getLocalPort();
            server = tomcat::stop;
        } else if ("jetty".equals(mode) || mode.startsWith("handoff-")) {
            if (mode.startsWith("handoff-")) {
                HandOffFixtures.init(mode.substring("handoff-".length()));
            }
            Server jetty = new Server(0);
            ServletContextHandler context = new ServletContextHandler();
            context.addServlet(new ServletHolder(new AsyncServlet()), "/*");
            jetty.setHandler(context);
            jetty.start();
            port = ((ServerConnector) jetty.getConnectors()[0]).getLocalPort();
            server = jetty::stop;
        } else if ("uncounted".equals(mode)) {
            HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            http.createContext("/clean", exchange -> {
                AsyncTail.ran();
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            http.start();
            port = http.getAddress().getPort();
            server = () -> http.stop(0);
        } else if ("kafka".equals(mode)) {
            ServerSocket cmdServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            port = cmdServer.getLocalPort();
            Thread cmdThread = new Thread(() -> runKafkaCommandLoop(cmdServer), "kafka-cmd");
            cmdThread.setDaemon(true);
            cmdThread.start();
            server = cmdServer;
        } else {
            // "whole-jar" or idle mode: just wait for stdin close.
            System.out.println("PORT 0");
            System.out.flush();
            while (System.in.read() >= 0) {
                // Wait for test to close stdin.
            }
            System.exit(0);
            return;
        }
        System.out.println("PORT " + port);
        System.out.flush();
        while (System.in.read() >= 0) {
            // Wait for test to close stdin.
        }
        server.close();
        PLAIN_POOL.shutdownNow();
        SCHED.shutdownNow();
        System.exit(0);
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static final class AsyncServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
            long ms = req.getParameter("ms") == null ? 0L : Long.parseLong(req.getParameter("ms"));
            if ("/submit-tail".equals(path)) {
                PLAIN_POOL.submit(() -> {
                    sleepQuietly(ms);
                    AsyncTail.ran();
                });
            } else if ("/cf-then-accept".equals(path)) {
                // Fast supplier followed by a slow synchronous thenAccept(audit) dependent.
                CompletableFuture.supplyAsync(() -> {
                    sleepQuietly(20);
                    return "result";
                }, PLAIN_POOL).thenAccept(v -> {
                    sleepQuietly(ms);
                    AsyncTail.ran();
                });
            } else if ("/cf-then-apply-async".equals(path)) {
                CompletableFuture.supplyAsync(() -> "seed", PLAIN_POOL)
                        .thenApplyAsync(v -> {
                            sleepQuietly(ms);
                            AsyncTail.ran();
                            return v + "-done";
                        }, PLAIN_POOL);
            } else if ("/unknown-serializable".equals(path)) {
                Runnable serializableTask = (Runnable & Serializable) () -> AsyncTail.ran();
                SERIALIZABLE_ONLY_POOL.execute(serializableTask);
                java.util.concurrent.Callable<String> serializableCall =
                        (java.util.concurrent.Callable<String> & Serializable) () -> {
                            AsyncTail.ran();
                            return "ok";
                        };
                SERIALIZABLE_ONLY_POOL.submit(serializableCall);
            } else if ("/unknown-serializable-async".equals(path)) {
                final long delay = ms;
                Runnable serializableTask = (Runnable & Serializable) () -> {
                    sleepQuietly(delay);
                    AsyncTail.ran();
                };
                ASYNC_SERIALIZABLE_POOL.execute(serializableTask);
            } else if ("/external-cf-dependent".equals(path)) {
                // Simulates an external library future (e.g., HttpClient.sendAsync)
                // completed on an external event-loop thread, with app code attaching
                // a plain .thenAccept(audit) dependent.
                final CompletableFuture<String> externalFuture =
                        io.fakeexternal.FakeExternalHttpClient.fetchAsync(25, "http-200");
                externalFuture.thenAccept(v -> {
                    sleepQuietly(ms);
                    AsyncTail.ran();
                });
            } else if ("/completion-stage-async".equals(path)) {
                CompletionStage<String> stage = CompletableFuture.completedFuture("seed");
                stage.thenApplyAsync(v -> {
                    sleepQuietly(ms);
                    AsyncTail.ran();
                    return v + "-stage";
                }, PLAIN_POOL);
            } else if ("/concrete-pool".equals(path)) {
                CONCRETE_POOL.execute(() -> AsyncTail.ran());
            } else if ("/subclassed-thread".equals(path)) {
                MyWorkerThread t = new MyWorkerThread(() -> AsyncTail.ran());
                t.setDaemon(true);
                t.start();
            } else if ("/fork-join-fork".equals(path)) {
                ForkJoinTask.adapt(() -> AsyncTail.ran()).fork();
            } else if ("/completion-service".equals(path)) {
                ExecutorCompletionService<String> ecs = new ExecutorCompletionService<>(PLAIN_POOL);
                ecs.submit(() -> {
                    AsyncTail.ran();
                    return "ecs";
                });
            } else if ("/method-ref".equals(path)) {
                Collections.<Runnable>singletonList(() -> AsyncTail.ran()).forEach(PLAIN_POOL::execute);
            } else if ("/lazy-method-ref".equals(path)) {
                LazyInWindowDispatcher.dispatch(PLAIN_POOL, () -> {
                    sleepQuietly(ms);
                    AsyncTail.ran();
                });
            } else if ("/spring-multicaster-plain".equals(path)) {
                org.springframework.context.event.SimpleApplicationEventMulticaster mc =
                        new org.springframework.context.event.SimpleApplicationEventMulticaster();
                mc.setTaskExecutor(PLAIN_POOL);
                AsyncTail.ran();
            } else if ("/nio-async-file".equals(path)) {
                NioAsyncFileHelper.touch();
                AsyncTail.ran();
            } else if ("/virtual-thread-latch".equals(path)) {
                final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
                VIRTUAL_RELEASE_LATCH = release;
                VIRTUAL_THREAD = VirtualThreadLauncher.launchDirect(() -> {
                    try {
                        release.await(30, TimeUnit.SECONDS);
                        AsyncTail.ran();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } else if ("/virtual-builder-latch".equals(path)) {
                final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
                VIRTUAL_RELEASE_LATCH = release;
                VIRTUAL_THREAD = VirtualThreadLauncher.launchBuilder(() -> {
                    try {
                        release.await(30, TimeUnit.SECONDS);
                        AsyncTail.ran();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } else if ("/release-virtual".equals(path)) {
                java.util.concurrent.CountDownLatch release = VIRTUAL_RELEASE_LATCH;
                Thread vt = VIRTUAL_THREAD;
                if (release != null) {
                    release.countDown();
                }
                if (vt != null) {
                    try {
                        vt.join(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            } else if (path.startsWith("/handoff-")) {
                try {
                    HandOffFixtures.handle(path.substring("/handoff-".length()), ms);
                } catch (Exception e) {
                    throw new IOException(e);
                }
            } else if ("/untracked-schedule".equals(path)) {
                SCHED.schedule(() -> AsyncTail.ran(), 1, TimeUnit.MILLISECONDS);
            } else if ("/untracked-thread".equals(path)) {
                Thread t = new Thread(() -> AsyncTail.ran(), "app-raw-thread");
                t.setDaemon(true);
                t.start();
            } else {
                Tail.ran();
            }
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            resp.setContentType("text/plain");
            resp.setContentLength(body.length);
            resp.getOutputStream().write(body);
            resp.flushBuffer();
        }
    }

    public static final class PreCapturedDispatcher {
        private static final java.util.function.Consumer<Runnable> DISPATCH = PLAIN_POOL::execute;

        public static void touch() {
            DISPATCH.accept(() -> { });
        }
    }

    public static final class LazyInWindowDispatcher {
        private static volatile java.util.function.Consumer<Runnable> cached;

        public static void dispatch(java.util.concurrent.Executor pool, Runnable task) {
            if (cached == null) {
                cached = pool::execute;
            }
            cached.accept(task);
        }
    }

    public static final class NioAsyncFileHelper {
        public static void touch() throws IOException {
            File f = File.createTempFile("keploy-nio-honesty", ".tmp");
            f.deleteOnExit();
            try (java.nio.channels.AsynchronousFileChannel ch = java.nio.channels.AsynchronousFileChannel.open(
                    f.toPath(), java.nio.file.StandardOpenOption.READ)) {
                ch.size();
            }
        }
    }

    public static final class ConcreteCustomPool extends ThreadPoolExecutor {
        public ConcreteCustomPool() {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), r -> {
                Thread t = new Thread(r, "app-concrete-pool");
                t.setDaemon(true);
                return t;
            });
        }
    }

    /**
     * Dynamically defines a class in {@code org.example.servers} containing real
     * JDK 21 {@code Thread.startVirtualThread(Runnable)} and
     * {@code Thread.ofVirtual().start(Runnable)} bytecode so tests compiled with
     * {@code --release 8} still exercise {@code ServerEntries} rewriting those
     * exact opcodes in a forked JDK 21 JVM.
     */
    public static final class VirtualThreadLauncher {
        private static volatile Class<?> helperClass;

        private static Class<?> helper() throws Exception {
            Class<?> c = helperClass;
            if (c != null) {
                return c;
            }
            String internal = "org/example/servers/VirtualThreadHelper";
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
            MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(1, 1);
            init.visitEnd();

            MethodVisitor direct = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "startDirect",
                    "(Ljava/lang/Runnable;)Ljava/lang/Thread;", null, null);
            direct.visitCode();
            direct.visitVarInsn(Opcodes.ALOAD, 0);
            direct.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "startVirtualThread",
                    "(Ljava/lang/Runnable;)Ljava/lang/Thread;", false);
            direct.visitInsn(Opcodes.ARETURN);
            direct.visitMaxs(1, 1);
            direct.visitEnd();

            MethodVisitor builder = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "startBuilder",
                    "(Ljava/lang/Runnable;)Ljava/lang/Thread;", null, null);
            builder.visitCode();
            builder.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "ofVirtual",
                    "()Ljava/lang/Thread$Builder$OfVirtual;", false);
            builder.visitVarInsn(Opcodes.ALOAD, 0);
            builder.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Thread$Builder$OfVirtual", "start",
                    "(Ljava/lang/Runnable;)Ljava/lang/Thread;", true);
            builder.visitInsn(Opcodes.ARETURN);
            builder.visitMaxs(2, 1);
            builder.visitEnd();

            cw.visitEnd();
            byte[] bytes = cw.toByteArray();
            c = new ClassLoader(AsyncHonestyApp.class.getClassLoader()) {
                Class<?> define() {
                    return defineClass("org.example.servers.VirtualThreadHelper", bytes, 0, bytes.length);
                }
            }.define();
            helperClass = c;
            return c;
        }

        public static Thread launchDirect(Runnable r) {
            try {
                return (Thread) helper().getMethod("startDirect", Runnable.class).invoke(null, r);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public static Thread launchBuilder(Runnable r) {
            try {
                return (Thread) helper().getMethod("startBuilder", Runnable.class).invoke(null, r);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static final class MyWorkerThread extends Thread {
        public MyWorkerThread(Runnable r) {
            super(r, "app-my-worker-thread");
        }
    }

    /**
     * Hazelcast/Ignite-style custom ExecutorService that requires tasks to be
     * {@link Serializable} (throws if wrapped in a non-Serializable wrapper).
     */
    public static final class SerializableOnlyExecutor extends AbstractExecutorService {
        private final boolean async;

        public SerializableOnlyExecutor(boolean async) {
            this.async = async;
        }

        @Override
        public void execute(Runnable command) {
            if (!(command instanceof Serializable)) {
                throw new IllegalStateException("Expected Serializable task, got " + command.getClass().getName());
            }
            if (async) {
                Thread t = new Thread(command, "serializable-async");
                t.setDaemon(true);
                t.start();
            } else {
                command.run();
            }
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
            if (!(task instanceof Serializable)) {
                throw new IllegalStateException("Expected Serializable callable, got " + task.getClass().getName());
            }
            java.util.concurrent.FutureTask<T> f = new java.util.concurrent.FutureTask<>(task);
            f.run();
            return f;
        }

        @Override
        public void shutdown() { }

        @Override
        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    // --- Realistic Spring Kafka ListenerConsumer class loaded AFTER premain ---

    public static final class OrdersListenerBean { }

    public static final class FakeKafkaProps {
        public String[] getTopics() {
            return new String[] {"orders-topic"};
        }

        public Object getMessageListener() {
            return new OrdersListenerBean();
        }
    }

    public static Object makeKafkaProps() {
        return new FakeKafkaProps();
    }

    public static void runListenerBody() {
        sleepQuietly(HANDOFF_LISTENER_MS);
        AsyncTail.ran();
    }

    private static void runKafkaCommandLoop(ServerSocket server) {
        try {
            // Define org.springframework.kafka.listener.KafkaMessageListenerContainer$ListenerConsumer
            // via a child ClassLoader so the JVM invokes ServerEntries.transform() on it!
            byte[] consumerBytes = generateKafkaListenerConsumer();
            Class<?> consumerClass = new ClassLoader(AsyncHonestyApp.class.getClassLoader()) {
                Class<?> define() {
                    return defineClass(
                            "org.springframework.kafka.listener.KafkaMessageListenerContainer$ListenerConsumer",
                            consumerBytes, 0, consumerBytes.length);
                }
            }.define();
            Object consumer = consumerClass.getDeclaredConstructor().newInstance();
            Method invokeRecord = consumerClass.getDeclaredMethod("invokeRecordListener", Object.class);
            invokeRecord.setAccessible(true);

            try (Socket client = server.accept();
                 BufferedReader in = new BufferedReader(
                         new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                 OutputStream out = client.getOutputStream()) {
                String line;
                while ((line = in.readLine()) != null) {
                    if ("fire".equals(line.trim())) {
                        invokeRecord.invoke(consumer, "record-1");
                        out.write("DONE\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
            }
        } catch (Throwable t) {
            t.printStackTrace(System.err);
        }
    }

    private static byte[] generateKafkaListenerConsumer() {
        String name = "org/springframework/kafka/listener/KafkaMessageListenerContainer$ListenerConsumer";
        String app = AsyncHonestyApp.class.getName().replace('.', '/');
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        MethodVisitor props = cw.visitMethod(Opcodes.ACC_PUBLIC, "getContainerProperties", "()Ljava/lang/Object;",
                null, null);
        props.visitCode();
        props.visitMethodInsn(Opcodes.INVOKESTATIC, app, "makeKafkaProps", "()Ljava/lang/Object;", false);
        props.visitInsn(Opcodes.ARETURN);
        props.visitMaxs(1, 1);
        props.visitEnd();

        MethodVisitor invoke = cw.visitMethod(Opcodes.ACC_PRIVATE, "invokeRecordListener", "(Ljava/lang/Object;)V",
                null, null);
        invoke.visitCode();
        invoke.visitMethodInsn(Opcodes.INVOKESTATIC, app, "runListenerBody", "()V", false);
        invoke.visitInsn(Opcodes.RETURN);
        invoke.visitMaxs(1, 2);
        invoke.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    static volatile long HANDOFF_LISTENER_MS = 0L;
    static volatile long HANDOFF_MC_MS = 0L;

    /**
     * Separate lazily-loaded helper so referencing SubmissionPublisher does not
     * enter HandOffFixtures' constant pool for unrelated test cases.
     */
    public static final class SubmissionPublisherFixture {
        private static volatile Object publisher;

        static void init() throws Exception {
            Class<?> sp = Class.forName("java.util.concurrent.SubmissionPublisher");
            Object p = sp.getConstructor().newInstance();
            java.util.function.Consumer<Object> consumer = item -> {
                sleepQuietly((Long) item);
                AsyncTail.ran();
            };
            sp.getMethod("consume", java.util.function.Consumer.class).invoke(p, consumer);
            publisher = p;
        }

        static void submit(long ms) throws Exception {
            publisher.getClass().getMethod("submit", Object.class).invoke(publisher, Long.valueOf(ms));
        }
    }

    public static final class HandOffFixtures {
        private static final LinkedBlockingQueue<Runnable> APP_WORKER_QUEUE = new LinkedBlockingQueue<>();
        private static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> APP_CLQ =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        private static final java.util.Deque<Runnable> APP_DEQUE =
                new java.util.concurrent.LinkedBlockingDeque<>();
        private static final java.util.Collection<Runnable> APP_COLLECTION = new LinkedBlockingQueue<>();
        private static final java.util.Queue<Long> OUTBOX_QUEUE =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        private static volatile org.springframework.context.event.SimpleApplicationEventMulticaster overrideMc;
        private static volatile org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler outboxScheduler;
        private static final java.util.ArrayDeque<Runnable> EXEC_DEQUE = new java.util.ArrayDeque<>();
        private static final java.util.ArrayDeque<Runnable> SPRING_TPTE_DEQUE = new java.util.ArrayDeque<>();
        private static volatile ExecutorService execWorkerPool;
        private static volatile org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor springTptePool;
        private static volatile Object fakeKafkaTemplate;
        private static volatile Method directKafkaSendMethod;

        public static final class OutboxPoller {
            public void drain() {
                Long ms;
                while ((ms = OUTBOX_QUEUE.poll()) != null) {
                    sleepQuietly(ms.longValue());
                    AsyncTail.ran();
                }
            }
        }

        static void init(String kind) throws Exception {
            if ("submission-publisher".equals(kind)) {
                SubmissionPublisherFixture.init();
            } else if ("queue-worker".equals(kind)) {
                Thread worker = new Thread(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            APP_WORKER_QUEUE.take().run();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }, "app-queue-worker");
                worker.setDaemon(true);
                worker.start();
            } else if ("clq-worker".equals(kind)) {
                Thread worker = new Thread(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        Runnable r = APP_CLQ.poll();
                        if (r == null) {
                            java.util.concurrent.locks.LockSupport.parkNanos(2_000_000L);
                            continue;
                        }
                        r.run();
                    }
                }, "app-clq-worker");
                worker.setDaemon(true);
                worker.start();
            } else if ("deque-worker".equals(kind)) {
                Thread worker = new Thread(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            ((java.util.concurrent.BlockingDeque<Runnable>) APP_DEQUE).takeFirst().run();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }, "app-deque-worker");
                worker.setDaemon(true);
                worker.start();
            } else if ("collection-add".equals(kind)) {
                Thread worker = new Thread(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            ((LinkedBlockingQueue<Runnable>) APP_COLLECTION).take().run();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }, "app-collection-worker");
                worker.setDaemon(true);
                worker.start();
            } else if ("scheduled-outbox".equals(kind)) {
                org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler ts =
                        new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
                ts.initialize();
                ts.scheduleWithFixedDelay(
                        new org.springframework.scheduling.support.ScheduledMethodRunnable(new OutboxPoller(), "drain"),
                        250L);
                outboxScheduler = ts;
            } else if ("exec-worker-deque".equals(kind)) {
                ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "app-exec-deque-worker");
                    t.setDaemon(true);
                    return t;
                });
                execWorkerPool = pool;
                pool.execute(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        Runnable task;
                        synchronized (EXEC_DEQUE) {
                            while (EXEC_DEQUE.isEmpty()) {
                                try {
                                    EXEC_DEQUE.wait();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                            task = EXEC_DEQUE.removeFirst();
                        }
                        task.run();
                    }
                });
            } else if ("spring-tpte-worker".equals(kind)) {
                org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor tpte =
                        new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
                tpte.setCorePoolSize(1);
                tpte.setMaxPoolSize(1);
                tpte.setDaemon(true);
                tpte.initialize();
                springTptePool = tpte;
                tpte.execute(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        Runnable task;
                        synchronized (SPRING_TPTE_DEQUE) {
                            while (SPRING_TPTE_DEQUE.isEmpty()) {
                                try {
                                    SPRING_TPTE_DEQUE.wait();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                            task = SPRING_TPTE_DEQUE.removeFirst();
                        }
                        task.run();
                    }
                });
            } else if ("kafka-direct-produce".equals(kind)) {
                // Define a stand-in org/springframework/kafka/core/KafkaTemplate and an
                // app caller class in org/example/servers/ that invokes KafkaTemplate.send()
                // directly in bytecode (no listener running) so W1's own COV must flag!
                final byte[] tplBytes = generateKafkaTemplateStub();
                final byte[] callerBytes = generateKafkaProducerCaller();
                ClassLoader loader = new ClassLoader(AsyncHonestyApp.class.getClassLoader()) {
                    @Override
                    protected Class<?> findClass(String name) throws ClassNotFoundException {
                        if ("org.springframework.kafka.core.KafkaTemplate".equals(name)) {
                            return defineClass(name, tplBytes, 0, tplBytes.length);
                        }
                        if ("org.example.servers.DirectKafkaProducerCaller".equals(name)) {
                            return defineClass(name, callerBytes, 0, callerBytes.length);
                        }
                        throw new ClassNotFoundException(name);
                    }
                };
                Class<?> tplCls = loader.loadClass("org.springframework.kafka.core.KafkaTemplate");
                Class<?> callerCls = loader.loadClass("org.example.servers.DirectKafkaProducerCaller");
                fakeKafkaTemplate = tplCls.getDeclaredConstructor().newInstance();
                directKafkaSendMethod = callerCls.getMethod("produce", Object.class, String.class, Object.class);
            } else if ("kafka-produce".equals(kind)) {
                byte[] consumerBytes = generateKafkaListenerConsumer();
                Class<?> consumerClass = new ClassLoader(AsyncHonestyApp.class.getClassLoader()) {
                    Class<?> define() {
                        return defineClass(
                                "org.springframework.kafka.listener.KafkaMessageListenerContainer$ListenerConsumer",
                                consumerBytes, 0, consumerBytes.length);
                    }
                }.define();
                final Object consumer = consumerClass.getDeclaredConstructor().newInstance();
                final Method invokeRecord = consumerClass.getDeclaredMethod("invokeRecordListener", Object.class);
                invokeRecord.setAccessible(true);
                io.fakeexternal.FakeExternalHttpClient.startBrokerConsumer(150, rec -> {
                    try {
                        HANDOFF_LISTENER_MS = rec;
                        invokeRecord.invoke(consumer, "record-" + rec);
                    } catch (Exception e) {
                        e.printStackTrace(System.err);
                    }
                });
            } else if ("spring-mc-override".equals(kind)) {
                org.springframework.context.event.SimpleApplicationEventMulticaster mc =
                        new org.springframework.context.event.SimpleApplicationEventMulticaster() {
                            @Override
                            protected java.util.concurrent.Executor getTaskExecutor() {
                                return PLAIN_POOL;
                            }
                        };
                mc.addApplicationListener(ev -> {
                    sleepQuietly(HANDOFF_MC_MS);
                    AsyncTail.ran();
                });
                overrideMc = mc;
            }
        }

        private static byte[] generateKafkaTemplateStub() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "org/springframework/kafka/core/KafkaTemplate",
                    null, "java/lang/Object", null);
            MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(1, 1);
            init.visitEnd();
            MethodVisitor send = cw.visitMethod(Opcodes.ACC_PUBLIC, "send",
                    "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", null, null);
            send.visitCode();
            send.visitInsn(Opcodes.ACONST_NULL);
            send.visitInsn(Opcodes.ARETURN);
            send.visitMaxs(1, 3);
            send.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] generateKafkaProducerCaller() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "org/example/servers/DirectKafkaProducerCaller",
                    null, "java/lang/Object", null);
            MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(1, 1);
            init.visitEnd();
            MethodVisitor prod = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "produce",
                    "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)V", null, null);
            prod.visitCode();
            prod.visitVarInsn(Opcodes.ALOAD, 0);
            prod.visitTypeInsn(Opcodes.CHECKCAST, "org/springframework/kafka/core/KafkaTemplate");
            prod.visitVarInsn(Opcodes.ALOAD, 1);
            prod.visitVarInsn(Opcodes.ALOAD, 2);
            prod.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/springframework/kafka/core/KafkaTemplate",
                    "send", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
            prod.visitInsn(Opcodes.POP);
            prod.visitInsn(Opcodes.RETURN);
            prod.visitMaxs(3, 3);
            prod.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        static void handle(String kind, final long ms) throws Exception {
            if ("spring-listenable-callback".equals(kind)) {
                final org.springframework.util.concurrent.SettableListenableFuture<String> f =
                        new org.springframework.util.concurrent.SettableListenableFuture<>();
                io.fakeexternal.FakeExternalHttpClient.runAsync(30, () -> f.set("sent"));
                f.addCallback(v -> {
                    sleepQuietly(ms);
                    AsyncTail.ran();
                }, ex -> { });
            } else if ("queue-worker".equals(kind)) {
                APP_WORKER_QUEUE.offer(() -> {
                    sleepQuietly(ms);
                    AsyncTail.ran();
                });
            } else if ("clq-worker".equals(kind)) {
                APP_CLQ.offer(() -> {
                    sleepQuietly(ms);
                    AsyncTail.ran();
                });
            } else if ("deque-worker".equals(kind)) {
                APP_DEQUE.offerLast(() -> {
                    sleepQuietly(ms);
                    AsyncTail.ran();
                });
            } else if ("collection-add".equals(kind)) {
                APP_COLLECTION.add(() -> {
                    sleepQuietly(ms);
                    AsyncTail.ran();
                });
            } else if ("lib-callback".equals(kind)) {
                io.fakeexternal.FakeExternalHttpClient.runAsync(30, () -> {
                    sleepQuietly(ms);
                    AsyncTail.ran();
                });
            } else if ("scheduled-outbox".equals(kind)) {
                OUTBOX_QUEUE.add(Long.valueOf(ms));
            } else if ("exec-worker-deque".equals(kind)) {
                synchronized (EXEC_DEQUE) {
                    EXEC_DEQUE.addLast(() -> {
                        sleepQuietly(ms);
                        AsyncTail.ran();
                    });
                    EXEC_DEQUE.notify();
                }
            } else if ("spring-tpte-worker".equals(kind)) {
                synchronized (SPRING_TPTE_DEQUE) {
                    SPRING_TPTE_DEQUE.addLast(() -> {
                        sleepQuietly(ms);
                        AsyncTail.ran();
                    });
                    SPRING_TPTE_DEQUE.notify();
                }
            } else if ("lib-subpool".equals(kind)) {
                io.fakeexternal.SubPoolExecutor.fire(() -> {
                    sleepQuietly(ms);
                    AsyncTail.ran();
                });
            } else if ("kafka-direct-produce".equals(kind)) {
                directKafkaSendMethod.invoke(null, fakeKafkaTemplate, "orders-topic", Long.valueOf(ms));
            } else if ("submission-publisher".equals(kind)) {
                SubmissionPublisherFixture.submit(ms);
            } else if ("kafka-produce".equals(kind)) {
                io.fakeexternal.FakeExternalHttpClient.publishToBroker(ms);
            } else if ("spring-mc-override".equals(kind)) {
                HANDOFF_MC_MS = ms;
                overrideMc.multicastEvent(new org.springframework.context.PayloadApplicationEvent<>("src", "x"));
            } else {
                throw new IllegalArgumentException("unknown handoff fixture: " + kind);
            }
        }
    }
}
