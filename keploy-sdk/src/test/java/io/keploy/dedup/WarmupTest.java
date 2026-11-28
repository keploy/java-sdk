package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The first START's warmup initializes the app's classes through the loader
 * the app runs them with (a fat jar's, which the system loader cannot reach),
 * on a thread of its own that the START waits for only so long.
 */
public class WarmupTest {

    private static final String[] PROPERTIES = {"keploy.java.class.dirs", "keploy.java.dedup.warmup.disabled",
            "keploy.jacoco.port", "keploy.coverage.endpoint", "keploy.java.dedup.diagnostics"};
    private static final String MAIN = "com/acme/shop/Main";
    private static final String CONTROLLER = "com/acme/shop/web/Controller";

    // What the generated classes' static initializers saw: class name to the
    // initializing thread's context loader.
    private static final Map<String, ClassLoader> INITIALIZED = new ConcurrentHashMap<>();
    // Each run of a generated static initializer: the class name and the
    // loader that defined the copy it ran in.
    private static final List<Map.Entry<String, ClassLoader>> RUNS = new CopyOnWriteArrayList<>();
    private static volatile CountDownLatch slow = new CountDownLatch(0);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Map<String, String> saved = new HashMap<>();
    private String savedControlPath;
    private String savedDataPath;
    private long savedWait;
    private java.util.concurrent.ThreadFactory savedThreads;
    private FakeJacoco jacoco;
    private Thread appMain;
    private final CountDownLatch appEnds = new CountDownLatch(1);
    private final List<String> logged = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record.getLevel() + " " + record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    /** Called by the generated classes' static initializers. */
    public static void initialized(String name, Class<?> self) throws InterruptedException {
        INITIALIZED.put(name, Thread.currentThread().getContextClassLoader());
        RUNS.add(new AbstractMap.SimpleImmutableEntry<>(name, self.getClassLoader()));
        if (name.endsWith("Slow")) {
            slow.await(20, TimeUnit.SECONDS);
        }
        if (name.endsWith("Broken")) {
            throw new IllegalStateException("Broken's static initializer fails");
        }
    }

    @Before
    public void setUp() throws IOException {
        for (String name : PROPERTIES) {
            saved.put(name, System.getProperty(name));
            System.clearProperty(name);
        }
        INITIALIZED.clear();
        RUNS.clear();
        savedWait = KeployDedupAgent.warmupWaitMillis;
        savedThreads = KeployDedupAgent.warmupThreads;
        savedControlPath = KeployDedupAgent.controlSocketPath;
        savedDataPath = KeployDedupAgent.dataSocketPath;
        File sockets = tmp.newFolder("sockets");
        KeployDedupAgent.controlSocketPath = new File(sockets, "control.sock").getAbsolutePath();
        KeployDedupAgent.dataSocketPath = new File(sockets, "data.sock").getAbsolutePath();
        Logger.getLogger(KeployDedupAgent.class.getName()).addHandler(handler);
        jacoco = new FakeJacoco();
        System.setProperty("keploy.jacoco.port", String.valueOf(jacoco.server.getLocalPort()));
    }

    @After
    public void tearDown() throws Exception {
        slow.countDown();
        KeployDedupAgent.stop();
        KeployDedupAgent.transformerAdded = false;
        KeployDedupAgent.executorCallSitesEnabled = false;
        ServerEntries.setHookStatusForTests(false, false, false);
        appEnds.countDown();
        if (appMain != null) {
            appMain.join(5000);
        }
        Logger.getLogger(KeployDedupAgent.class.getName()).removeHandler(handler);
        KeployDedupAgent.warmupWaitMillis = savedWait;
        KeployDedupAgent.warmupThreads = savedThreads;
        KeployDedupAgent.controlSocketPath = savedControlPath;
        KeployDedupAgent.dataSocketPath = savedDataPath;
        for (String name : PROPERTIES) {
            if (saved.get(name) == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, saved.get(name));
            }
        }
        jacoco.close();
    }

    @Test
    public void aFatJarsClassesWarmUpThroughTheLoaderTheAppRunsThem() throws Exception {
        // As a Spring Boot launcher does: the app's classes are reachable only
        // through the loader it made, set as its main thread's context loader.
        File classes = appClasses(MAIN, CONTROLLER);
        URLClassLoader app = new URLClassLoader(new URL[] {classes.toURI().toURL()},
                WarmupTest.class.getClassLoader());
        runAppMain(app);
        System.setProperty("keploy.java.class.dirs", classes.getPath());

        assertTrue(KeployDedupAgent.start());
        assertTaken(send("START test-set-0/test-1"));

        assertLogged("INFO Keploy dedup: warmed up application classes (initialized=2, skipped=0) in ");
        assertNotLogged("the warmup of the app's classes was still");
        assertSame(app, INITIALIZED.get(MAIN));
        assertSame(app, INITIALIZED.get(CONTROLLER));
        app.close();
    }

    @Test
    public void aSlowStaticInitializerHoldsTheFirstStartOnlySoLong() throws Exception {
        // Sorted by name, Slow is warmed up first; its <clinit> runs until
        // the test lets it end, past the START's wait.
        String slowName = "com/acme/a/Slow";
        String next = "com/acme/b/Next";
        File classes = appClasses(slowName, next);
        URLClassLoader app = new URLClassLoader(new URL[] {classes.toURI().toURL()},
                WarmupTest.class.getClassLoader());
        runAppMain(app);
        System.setProperty("keploy.java.class.dirs", classes.getPath());
        slow = new CountDownLatch(1);
        long waitMillis = 500;
        KeployDedupAgent.warmupWaitMillis = waitMillis;

        assertTrue(KeployDedupAgent.start());
        long began = System.nanoTime();
        assertTaken(send("START test-set-0/test-1"));
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);

        assertTrue("the START was answered after " + tookMillis + " ms",
                tookMillis >= waitMillis && tookMillis < 10000);
        // It names what it was doing when the START stopped waiting: Slow,
        // or, on a box too busy to reach it in the wait, an earlier step.
        String warning = loggedStartingWith("WARNING Keploy dedup: the warmup of the app's classes was still ");
        assertTrue(warning, warning.matches("WARNING Keploy dedup: the warmup of the app's classes was still"
                + " (initializing com\\.acme\\.a\\.Slow \\(class 1 of 2\\)|finding the app's class loaders"
                + "|reading the app's class files) after [0-9]+\\.[0-9]{2} ms, so the first test goes on without it,"
                + " and the warmup stops after that step\\. A static initializer it is running goes on while"
                + " the tests run, and each line it runs from then on counts in the test running at the time"
                + " \\(between two tests, in none\\); .*"));
        // A second START neither warms up again nor waits for the warmup:
        // it is answered while Slow's initializer still holds (it holds for
        // 20 s), and no second bounded wait ends in a second WARNING. Not a
        // wall-clock bound under the wait: a busy box can delay any answer
        // that long. With the wait raised to 60 s, any wait on the warmup,
        // bounded or not, lasts until Slow's 20 s hold ends.
        KeployDedupAgent.warmupWaitMillis = 60000;
        began = System.nanoTime();
        assertTaken(send("START test-set-0/test-2"));
        tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
        assertTrue("the second START was answered after " + tookMillis + " ms", tookMillis < 10000);
        assertEquals(1, countLogged("WARNING Keploy dedup: the warmup of the app's classes was still "));

        slow.countDown();
        awaitLogged("INFO Keploy dedup: warmed up application classes (");
        // Slow's initializer had begun by the stop; else, on a box too busy
        // to reach it in the wait, the warmup stopped before it.
        boolean slowBegan = INITIALIZED.containsKey(slowName);
        // The warmup names a class only once it has passed the stop check
        // for it, so a WARNING that names Slow means Slow ran.
        assertTrue(warning, slowBegan || !warning.contains("com.acme.a.Slow"));
        assertLogged("INFO Keploy dedup: warmed up application classes ("
                + (slowBegan ? "initialized=1, skipped=0, not reached=1)" : "initialized=0, skipped=0, not reached=2)"));
        assertFalse("the warmup went on past the class it was initializing: " + INITIALIZED,
                INITIALIZED.containsKey(next));
        app.close();
    }

    @Test
    public void premainsInstrumentationFindsTheLoaderOfTheAppsLoadedClasses() throws Exception {
        // Started as a -javaagent, with no live thread holding the app's
        // loader: the app's Main, loaded, names it. The stand-in
        // Instrumentation cannot start a JaCoCo, which premain says, and
        // goes on.
        File classes = appClasses(MAIN, CONTROLLER);
        try (URLClassLoader app = new URLClassLoader(new URL[] {classes.toURI().toURL()},
                WarmupTest.class.getClassLoader())) {
            Class<?> main = Class.forName(binary(MAIN), false, app);
            System.setProperty("keploy.java.class.dirs", classes.getPath());

            KeployDedupAgent.premain(null, instrumentation(main));
            assertTrue(KeployDedupAgent.isStarted());
            assertTaken(send("START test-set-0/test-1"));

            assertLogged("INFO Keploy dedup: warmed up application classes (initialized=2, skipped=0) in ");
            assertSame(app, INITIALIZED.get(MAIN));
            assertSame(app, INITIALIZED.get(CONTROLLER));
        }
    }

    @Test
    public void aClassTheAppLoadedIsInitializedThroughItsOwnLoader() throws Exception {
        // With premain's Instrumentation and no thread holding the app's
        // loader: Main is loaded, not initialized; its loader is the one
        // Controller, not loaded yet, is found with.
        File classes = appClasses(MAIN, CONTROLLER);
        try (URLClassLoader app = new URLClassLoader(new URL[] {classes.toURI().toURL()},
                WarmupTest.class.getClassLoader())) {
            Class<?> main = Class.forName(binary(MAIN), false, app);
            assertTrue(INITIALIZED.isEmpty());

            AppWarmup warmup = new AppWarmup(instrumentation(main, String.class));
            warmup.run(Arrays.asList(binary(MAIN), binary(CONTROLLER)));

            assertEquals(2, warmup.initialized());
            assertEquals(0, warmup.skipped());
            assertSame(app, INITIALIZED.get(MAIN));
            assertSame(app, INITIALIZED.get(CONTROLLER));
        }
    }

    @Test
    public void aLoadedClassIsInitializedInTheLoaderThatLoadedItNotInAnother() throws Exception {
        // Two loaders can load every class; Cart is loaded in the one that
        // defined fewer of them, and is initialized there, not loaded again.
        String cart = "com/acme/shop/Cart";
        File classes = appClasses(MAIN, CONTROLLER, cart);
        ClassLoader parent = WarmupTest.class.getClassLoader();
        try (URLClassLoader one = new URLClassLoader(new URL[] {classes.toURI().toURL()}, parent);
             URLClassLoader two = new URLClassLoader(new URL[] {classes.toURI().toURL()}, parent)) {
            Class<?> main = Class.forName(binary(MAIN), false, one);
            Class<?> controller = Class.forName(binary(CONTROLLER), false, one);
            Class<?> cartInTwo = Class.forName(binary(cart), false, two);

            AppWarmup warmup = new AppWarmup(instrumentation(main, controller, cartInTwo));
            warmup.run(Arrays.asList(binary(cart), binary(MAIN), binary(CONTROLLER)));

            assertEquals(3, warmup.initialized());
            assertSame(two, INITIALIZED.get(cart));
            assertSame(one, INITIALIZED.get(MAIN));
        }
    }

    @Test
    public void theLoaderThatDefinedMostOfTheAppsClassesIsTriedFirst() throws Exception {
        File classes = appClasses(MAIN, CONTROLLER, "com/acme/shop/Cart");
        File library = appClasses("org/lib/Util");
        ClassLoader parent = WarmupTest.class.getClassLoader();
        try (URLClassLoader one = new URLClassLoader(new URL[] {classes.toURI().toURL()}, parent);
             URLClassLoader two = new URLClassLoader(new URL[] {classes.toURI().toURL()}, parent);
             URLClassLoader lib = new URLClassLoader(new URL[] {library.toURI().toURL()}, parent);
             URLClassLoader context = new URLClassLoader(new URL[0], parent)) {
            Class<?> cartInOne = Class.forName(binary("com/acme/shop/Cart"), false, one);
            Class<?> mainInTwo = Class.forName(binary(MAIN), false, two);
            Class<?> controllerInTwo = Class.forName(binary(CONTROLLER), false, two);
            Class<?> util = Class.forName(binary("org/lib/Util"), false, lib);
            Map<String, Class<?>> loadedApp = new HashMap<>();

            List<ClassLoader> loaders = AppWarmup.loaders(
                    new Class<?>[] {cartInOne, util, mainInTwo, String.class, controllerInTwo},
                    // String, named as an app class, has no loader: none is added for it.
                    new HashSet<>(Arrays.asList(binary(MAIN), binary(CONTROLLER), binary("com/acme/shop/Cart"),
                            "java.lang.String")),
                    loadedApp, Arrays.<ClassLoader>asList(context, one), parent, null);

            assertEquals(Arrays.<ClassLoader>asList(two, one, context, parent), loaders);
            assertEquals(3, loadedApp.size());
            assertSame(mainInTwo, loadedApp.get(binary(MAIN)));
            assertSame(cartInOne, loadedApp.get(binary("com/acme/shop/Cart")));
        }
    }

    @Test
    public void contextLoadersAreOrderedOldestThreadFirst() throws Exception {
        try (URLClassLoader app = new URLClassLoader(new URL[0], WarmupTest.class.getClassLoader())) {
            runAppMain(app);
            List<ClassLoader> loaders = AppWarmup.contextLoaders();
            assertTrue(loaders.contains(app));
            assertEquals(new HashSet<>(loaders).size(), loaders.size());
            // The oldest live thread's loader leads; this JVM's main thread
            // is older than the app's stand-in.
            Thread oldest = null;
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.getContextClassLoader() != null && (oldest == null || thread.getId() < oldest.getId())) {
                    oldest = thread;
                }
            }
            assertSame(oldest.getContextClassLoader(), loaders.get(0));
        }
    }

    @Test
    public void ofAClassLoadedTwiceTheCopyOfTheLoaderThatDefinedMoreIsInitialized() throws Exception {
        // Cart and Order are loaded in both loaders; one defined more of the
        // app's classes. Whichever copy the JVM lists first, one's is
        // initialized, and two's never is.
        String cart = "com/acme/shop/Cart";
        String order = "com/acme/shop/Order";
        File classes = appClasses(MAIN, CONTROLLER, cart, order);
        ClassLoader parent = WarmupTest.class.getClassLoader();
        try (URLClassLoader one = new URLClassLoader(new URL[] {classes.toURI().toURL()}, parent);
             URLClassLoader two = new URLClassLoader(new URL[] {classes.toURI().toURL()}, parent)) {
            Class<?> main = Class.forName(binary(MAIN), false, one);
            Class<?> controller = Class.forName(binary(CONTROLLER), false, one);
            Class<?> cartInOne = Class.forName(binary(cart), false, one);
            Class<?> cartInTwo = Class.forName(binary(cart), false, two);
            Class<?> orderInOne = Class.forName(binary(order), false, one);
            Class<?> orderInTwo = Class.forName(binary(order), false, two);

            AppWarmup warmup = new AppWarmup(
                    instrumentation(cartInTwo, main, orderInOne, cartInOne, controller, orderInTwo));
            warmup.run(Arrays.asList(binary(cart), binary(order), binary(MAIN), binary(CONTROLLER)));

            assertEquals(4, warmup.initialized());
            assertEquals(Arrays.asList(one), runsOf(cart));
            assertEquals(Arrays.asList(one), runsOf(order));
        }
    }

    @Test
    public void aClassFoundButNotInitializedIsNotLoadedAgainInAnotherLoader() throws Exception {
        // Broken's static initializer throws. one, tried first, finds it
        // and runs it; two, which also sees its bytes, is not tried, so it
        // runs once. Found, which only two sees, is tried there.
        String broken = "com/acme/shop/Broken";
        String found = "com/acme/shop/Found";
        String loadedName = "com/acme/shop/Loaded";
        File first = appClasses(broken, loadedName);
        File second = appClasses(broken, found);
        ClassLoader parent = WarmupTest.class.getClassLoader();
        try (URLClassLoader one = new URLClassLoader(new URL[] {first.toURI().toURL()}, parent);
             URLClassLoader two = new URLClassLoader(new URL[] {second.toURI().toURL()}, parent)) {
            // one defined a class of the app's, so it is tried first; two is
            // the context loader of a live thread, tried after.
            Class<?> loaded = Class.forName(binary(loadedName), false, one);
            Thread holder = new Thread(() -> {
                try {
                    appEnds.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "app-worker");
            holder.setContextClassLoader(two);
            holder.setDaemon(true);
            holder.start();
            try {
                AppWarmup warmup = new AppWarmup(instrumentation(loaded));
                warmup.run(Arrays.asList(binary(broken), binary(found), binary(loadedName)));
                assertEquals(2, warmup.initialized());
                assertEquals(1, warmup.skipped());
                assertEquals(Arrays.asList(one), runsOf(broken));
                assertEquals(Arrays.asList(two), runsOf(found));
                assertEquals(Arrays.asList(one), runsOf(loadedName));
            } finally {
                appEnds.countDown();
                holder.join(5000);
            }
        }
    }

    @Test
    public void aWarmupThreadThatCannotStartEndsNeitherTheStartNorTheServer() throws Exception {
        File classes = appClasses(MAIN);
        System.setProperty("keploy.java.class.dirs", classes.getPath());
        KeployDedupAgent.warmupThreads = runnable -> new Thread(runnable) {
            @Override
            public synchronized void start() {
                throw new OutOfMemoryError("unable to create native thread: possibly out of memory");
            }
        };

        assertTrue(KeployDedupAgent.start());
        assertTaken(send("START test-set-0/test-1"));
        assertLogged("WARNING Keploy dedup: no warmup, as its thread could not start");
        assertTrue(INITIALIZED.isEmpty());
        // The control server still answers.
        assertTaken(send("START test-set-0/test-2"));
    }

    private static void assertTaken(String reply) {
        assertTrue("expected ACK proto=3 caps=..., got: " + reply,
                reply != null && reply.startsWith("ACK proto=3 caps=") && reply.endsWith("\n"));
    }

    @Test
    public void anEmptyClassListAsksTheJvmNothing() {
        final List<String> asked = new CopyOnWriteArrayList<>();
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                WarmupTest.class.getClassLoader(), new Class<?>[] {Instrumentation.class},
                (proxy, method, args) -> {
                    asked.add(method.getName());
                    return new Class<?>[0];
                });
        AppWarmup warmup = new AppWarmup(instrumentation);
        warmup.run(new ArrayList<String>());
        assertEquals(0, warmup.initialized());
        assertTrue("asked: " + asked, asked.isEmpty());
    }

    @Test
    public void itSaysWhatItIsDoing() throws Exception {
        String slowName = "com/acme/a/Slow";
        File classes = appClasses(slowName, MAIN);
        slow = new CountDownLatch(1);
        try (URLClassLoader app = new URLClassLoader(new URL[] {classes.toURI().toURL()},
                WarmupTest.class.getClassLoader())) {
            runAppMain(app);
            // The JVM's list of loaded classes comes only once the test has
            // seen the warmup ask for it.
            final CountDownLatch asked = new CountDownLatch(1);
            final CountDownLatch answer = new CountDownLatch(1);
            Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                    WarmupTest.class.getClassLoader(), new Class<?>[] {Instrumentation.class},
                    (proxy, method, args) -> {
                        asked.countDown();
                        answer.await(10, TimeUnit.SECONDS);
                        return new Class<?>[0];
                    });
            final AppWarmup warmup = new AppWarmup(instrumentation);
            assertEquals("reading the app's class files", warmup.doing());
            Thread thread = new Thread(() -> warmup.run(Arrays.asList(binary(slowName), binary(MAIN))));
            thread.start();
            assertTrue(asked.await(10, TimeUnit.SECONDS));
            assertEquals("finding the app's class loaders", warmup.doing());
            answer.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!INITIALIZED.containsKey(slowName) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals("initializing com.acme.a.Slow (class 1 of 2)", warmup.doing());
            warmup.stop();
            slow.countDown();
            thread.join(10000);
            assertEquals(1, warmup.initialized());
            assertEquals(1, warmup.notReached());
        }
    }

    @Test
    public void everyLiveThreadIsSeenFromTheRootGroup() throws Exception {
        try (URLClassLoader app = new URLClassLoader(new URL[0], WarmupTest.class.getClassLoader())) {
            runAppMain(app);
            Thread referenceHandler = null;
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.getName().equals("Reference Handler")) {
                    referenceHandler = thread;
                }
            }
            // The JVM's own threads are in the root group, above this one's.
            List<Thread> live = AppWarmup.liveThreads();
            assertTrue(live.contains(referenceHandler));
            assertTrue(live.contains(appMain));
            assertTrue(live.contains(Thread.currentThread()));
            // An array too small at first grows until every thread fits.
            ThreadGroup root = Thread.currentThread().getThreadGroup();
            while (root.getParent() != null) {
                root = root.getParent();
            }
            List<Thread> grown = AppWarmup.liveThreads(root, 1);
            assertTrue(grown.contains(referenceHandler));
            assertTrue(grown.contains(appMain));
            assertTrue(grown.contains(Thread.currentThread()));
        }
    }

    private static List<ClassLoader> runsOf(String name) {
        List<ClassLoader> loaders = new ArrayList<>();
        for (Map.Entry<String, ClassLoader> run : RUNS) {
            if (run.getKey().equals(name)) {
                loaders.add(run.getValue());
            }
        }
        return loaders;
    }

    // runAppMain starts the app's stand-in main thread, holding app as its
    // context loader until the test ends.
    private void runAppMain(ClassLoader app) {
        appMain = new Thread(() -> {
            try {
                appEnds.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "app-main");
        appMain.setContextClassLoader(app);
        appMain.setDaemon(true);
        appMain.start();
    }

    // appClasses writes, under a new dir not on the test's classpath, a class
    // per name whose <clinit> calls initialized(name).
    private File appClasses(String... names) throws IOException {
        File dir = tmp.newFolder();
        for (String name : names) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
            MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.visitCode();
            clinit.visitLdcInsn(name);
            clinit.visitLdcInsn(org.objectweb.asm.Type.getObjectType(name));
            clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "io/keploy/dedup/WarmupTest", "initialized",
                    "(Ljava/lang/String;Ljava/lang/Class;)V", false);
            clinit.visitInsn(Opcodes.RETURN);
            clinit.visitMaxs(0, 0);
            clinit.visitEnd();
            writer.visitEnd();
            File file = new File(dir, name + ".class");
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), writer.toByteArray());
        }
        return dir;
    }

    private static String binary(String name) {
        return name.replace('/', '.');
    }

    // instrumentation answers getAllLoadedClasses with loaded, as premain's
    // would; nothing else is called.
    private static Instrumentation instrumentation(final Class<?>... loaded) {
        return (Instrumentation) Proxy.newProxyInstance(WarmupTest.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if (method.getName().equals("getAllLoadedClasses")) {
                            return loaded.clone();
                        }
                        throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private void assertLogged(String prefix) {
        for (String line : logged) {
            if (line.startsWith(prefix)) {
                return;
            }
        }
        throw new AssertionError("nothing logged starts with: " + prefix + "\nlogged: " + logged);
    }

    private int countLogged(String prefix) {
        int count = 0;
        for (String line : logged) {
            if (line.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private String loggedStartingWith(String prefix) {
        for (String line : logged) {
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        throw new AssertionError("nothing logged starts with: " + prefix + "\nlogged: " + logged);
    }

    private void assertNotLogged(String part) {
        for (String line : logged) {
            assertFalse("logged: " + line, line.contains(part));
        }
    }

    private void awaitLogged(String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            for (String line : logged) {
                if (line.startsWith(prefix)) {
                    return;
                }
            }
            Thread.sleep(20);
        }
        assertLogged(prefix);
    }

    private static String send(String command) throws IOException {
        try (AFUNIXSocket socket = AFUNIXSocket.newInstance()) {
            socket.connect(AFUNIXSocketAddress.of(new File(KeployDedupAgent.controlSocketPath)), 3000);
            socket.setSoTimeout(30000);
            OutputStream out = socket.getOutputStream();
            out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            StringBuilder answer = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                answer.append((char) b);
            }
            return answer.toString();
        }
    }
}
