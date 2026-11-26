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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
 * JVMs by {@code ForkedAsyncAndHonestyTest} to prove Ruling #3 end-to-end
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
    private static final ExecutorService SERIALIZABLE_ONLY_POOL = new SerializableOnlyExecutor();

    private AsyncHonestyApp() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        AutoCloseable server;
        int port;
        if ("tomcat".equals(mode)) {
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
        } else if ("jetty".equals(mode)) {
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
                // Reproduces plg-87's exact bug: fast supplier, slow synchronous thenAccept(audit).
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

    /**
     * Hazelcast/Ignite-style custom ExecutorService that requires tasks to be
     * {@link Serializable} (throws if wrapped in a non-Serializable wrapper).
     */
    public static final class SerializableOnlyExecutor extends AbstractExecutorService {
        @Override
        public void execute(Runnable command) {
            if (!(command instanceof Serializable)) {
                throw new IllegalStateException("Expected Serializable task, got " + command.getClass().getName());
            }
            command.run();
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
}
