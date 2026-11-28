package io.keploy.dedup;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Timer;

/**
 * Installs {@code io.keploy.dedup.bootstrap.KeploySinkBridge} onto the
 * bootstrap class loader (before it is ever loaded by any other class loader),
 * opens {@code java.base} read-access to the bootstrap unnamed module on JDK 9+
 * via {@code Instrumentation.redefineModule}, registers the runtime sink
 * handler, and retransforms the 5+1 JDK concurrency sink classes.
 *
 * <p>NOTE: This class intentionally avoids static imports or direct bytecode
 * references to {@code KeploySinkBridge} inside {@link #install(Instrumentation)}
 * until AFTER {@code appendToBootstrapClassLoaderSearch} completes, guaranteeing
 * parent-first resolution resolves the single bootstrap-loaded class everywhere.
 */
final class JdkSinkBootstrap {

    private static final Logger LOGGER = Logger.getLogger(KeployDedupAgent.class.getName());
    private static final String BRIDGE_CLASS_NAME = "io.keploy.dedup.bootstrap.KeploySinkBridge";
    private static final String BRIDGE_CLASS_RESOURCE = "io/keploy/dedup/bootstrap/KeploySinkBridge.class";
    private static final String BRIDGE_HANDLER_RESOURCE = "io/keploy/dedup/bootstrap/KeploySinkBridge$Handler.class";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static volatile boolean active;
    private static volatile boolean failed;

    private JdkSinkBootstrap() {
    }

    static boolean isActive() {
        return active;
    }

    static boolean hasFailed() {
        return failed || (active && BridgeAccess.hasCallbackError());
    }

    static void setActiveForTest(boolean value) {
        active = value;
        if (value) {
            failed = false;
        }
    }

    static boolean install(Instrumentation instrumentation) {
        if (instrumentation == null || !instrumentation.isRetransformClassesSupported()) {
            failed = true;
            return false;
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            return active;
        }
        try {
            File jarFile = buildBootstrapBridgeJar();
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(jarFile));

            // Load KeploySinkBridge AND KeploySinkBridge$Handler explicitly via
            // the bootstrap class loader (null) so both java.base and
            // io.keploy.dedup.* share the exact same bootstrap classes with zero
            // loader constraint conflicts.
            Class<?> bridgeClass = Class.forName(BRIDGE_CLASS_NAME, true, null);
            Class<?> handlerClass = Class.forName(BRIDGE_CLASS_NAME + "$Handler", true, null);
            if (bridgeClass.getClassLoader() != null || handlerClass.getClassLoader() != null) {
                LOGGER.log(Level.WARNING,
                        "Keploy dedup: KeploySinkBridge was loaded by non-bootstrap classloader "
                                + bridgeClass.getClassLoader() + "/" + handlerClass.getClassLoader()
                                + "; JDK sink hooks disabled (fail-closed)");
                failed = true;
                return false;
            }

            // On JDK 9+, java.base is a named module and does not read the bootstrap
            // class loader's unnamed module unless granted via Instrumentation.redefineModule.
            grantJavaBaseReadAccess(instrumentation, bridgeClass);

            // Register RequestGate's handler BEFORE retransforming JDK classes,
            // with internal guard active during setup.
            BridgeAccess.installHandler();

            JdkSinkTransformer transformer = new JdkSinkTransformer();
            instrumentation.addTransformer(transformer, true);

            List<Class<?>> targets = new ArrayList<>();
            targets.add(Thread.class);
            targets.add(ThreadPoolExecutor.class);
            targets.add(ScheduledThreadPoolExecutor.class);
            targets.add(ForkJoinPool.class);
            targets.add(Timer.class);
            try {
                Class<?> vt = Class.forName("java.lang.VirtualThread", false, null);
                if (instrumentation.isModifiableClass(vt)) {
                    targets.add(vt);
                }
            } catch (ClassNotFoundException ignored) {
                // JDK < 21: VirtualThread does not exist.
            }

            List<Class<?>> modifiable = new ArrayList<>();
            for (Class<?> c : targets) {
                if (instrumentation.isModifiableClass(c)) {
                    modifiable.add(c);
                } else {
                    LOGGER.log(Level.WARNING, "Keploy dedup: JDK sink class is not modifiable: " + c.getName());
                    failed = true;
                    return false;
                }
            }

            BridgeAccess.enterInternal();
            try {
                instrumentation.retransformClasses(modifiable.toArray(new Class<?>[0]));
            } finally {
                BridgeAccess.exitInternal();
            }

            boolean allCoreHooked = JdkSinkTransformer.hookedThreadStart
                    && JdkSinkTransformer.hookedTpeExecute
                    && JdkSinkTransformer.hookedStpeDelayedExecute
                    && JdkSinkTransformer.hookedFjpSubmit
                    && JdkSinkTransformer.hookedTimerSched;
            if (!allCoreHooked) {
                LOGGER.log(Level.WARNING,
                        "Keploy dedup: incomplete JDK sink instrumentation (thread="
                                + JdkSinkTransformer.hookedThreadStart
                                + ", tpe=" + JdkSinkTransformer.hookedTpeExecute
                                + ", stpe=" + JdkSinkTransformer.hookedStpeDelayedExecute
                                + ", fjp=" + JdkSinkTransformer.hookedFjpSubmit
                                + ", timer=" + JdkSinkTransformer.hookedTimerSched + ")");
                failed = true;
                return false;
            }
            active = true;
            if (SinkAllowlist.AUDIT) {
                System.err.println("KEPLOY_SINK_BOOTSTRAP_INSTALLED bridgeCL=" + bridgeClass.getClassLoader()
                        + " thread=" + JdkSinkTransformer.hookedThreadStart
                        + " vthread=" + JdkSinkTransformer.hookedVirtualThreadStart
                        + " tpe=" + JdkSinkTransformer.hookedTpeExecute
                        + " stpe=" + JdkSinkTransformer.hookedStpeDelayedExecute
                        + " fjp=" + JdkSinkTransformer.hookedFjpSubmit
                        + " timer=" + JdkSinkTransformer.hookedTimerSched
                        + " handler=" + (BridgeAccess.hasHandler()));
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.log(Level.WARNING,
                    "Keploy dedup: could not install bootstrap JDK sink bridge; asyncExecutors will fail closed", t);
            return false;
        }
    }

    static void enterInternal() {
        if (active) {
            BridgeAccess.enterInternal();
        }
    }

    static void exitInternal() {
        if (active) {
            BridgeAccess.exitInternal();
        }
    }

    static boolean isInternal() {
        return active && BridgeAccess.isInternal();
    }

    private static void grantJavaBaseReadAccess(Instrumentation instrumentation, Class<?> bridgeClass)
            throws Exception {
        Method getModule;
        try {
            getModule = Class.class.getMethod("getModule");
        } catch (NoSuchMethodException jdk8) {
            // JDK 8 has no JPMS modules.
            return;
        }
        Object javaBaseModule = getModule.invoke(Object.class);
        Object bridgeModule = getModule.invoke(bridgeClass);
        if (javaBaseModule == null || bridgeModule == null) {
            return;
        }
        Class<?> moduleClass = Class.forName("java.lang.Module");
        Method redefineModule = Instrumentation.class.getMethod("redefineModule",
                moduleClass, Set.class, Map.class, Map.class, Set.class, Map.class);
        redefineModule.invoke(instrumentation,
                javaBaseModule,
                Collections.singleton(bridgeModule),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptySet(),
                Collections.emptyMap());
    }

    private static File buildBootstrapBridgeJar() throws IOException {
        ClassLoader cl = JdkSinkBootstrap.class.getClassLoader();
        byte[] bridgeBytes = readResource(cl, BRIDGE_CLASS_RESOURCE);
        byte[] handlerBytes = readResource(cl, BRIDGE_HANDLER_RESOURCE);
        File tempJar = Files.createTempFile("keploy-sink-bridge-", ".jar").toFile();
        tempJar.deleteOnExit();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(tempJar))) {
            writeJarEntry(jos, BRIDGE_CLASS_RESOURCE, bridgeBytes);
            writeJarEntry(jos, BRIDGE_HANDLER_RESOURCE, handlerBytes);
        }
        return tempJar;
    }

    private static byte[] readResource(ClassLoader cl, String resource) throws IOException {
        try (InputStream is = cl != null ? cl.getResourceAsStream(resource)
                : ClassLoader.getSystemResourceAsStream(resource)) {
            if (is == null) {
                throw new IOException("Missing bootstrap bridge resource: " + resource);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void writeJarEntry(JarOutputStream jos, String name, byte[] bytes) throws IOException {
        jos.putNextEntry(new JarEntry(name));
        jos.write(bytes);
        jos.closeEntry();
    }

    // Separate nested holder so KeploySinkBridge is NEVER referenced by
    // JdkSinkBootstrap's constant pool until AFTER appendToBootstrapClassLoaderSearch.
    private static final class BridgeAccess {
        static void installHandler() {
            io.keploy.dedup.bootstrap.KeploySinkBridge.handler = new JdkSinkHandler();
        }

        static void enterInternal() {
            io.keploy.dedup.bootstrap.KeploySinkBridge.enterInternal();
        }

        static void exitInternal() {
            io.keploy.dedup.bootstrap.KeploySinkBridge.exitInternal();
        }

        static boolean isInternal() {
            return io.keploy.dedup.bootstrap.KeploySinkBridge.isInternal();
        }

        static boolean hasHandler() {
            return io.keploy.dedup.bootstrap.KeploySinkBridge.handler != null;
        }

        static boolean hasCallbackError() {
            return io.keploy.dedup.bootstrap.KeploySinkBridge.lastCallbackError != null;
        }
    }
}
