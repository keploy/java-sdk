package io.keploy.dedup;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.internal.data.CRC64;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * ServerEntries counts each request of Tomcat, Jetty and Undertow in
 * RequestGate from the server's request entry, however it returns or throws,
 * and changes no other method, and no class whose loader does not see the
 * RequestGate END reads. Here on stand-ins of the servers' classes; the
 * servers themselves run in ServerTailTest.
 */
public class ServerEntriesTest {

    private static final String BODY_DESCRIPTOR = "(Ljava/lang/Object;JDI[IZBCSF)J";
    private static final String[][] ENTRIES = {
        {"org/apache/catalina/connector/CoyoteAdapter", "service", "Tomcat"},
        {"org/apache/catalina/connector/CoyoteAdapter", "asyncDispatch", "Tomcat"},
        {"org/eclipse/jetty/server/HttpChannel", "handle", "Jetty"},
        {"org/eclipse/jetty/ee10/servlet/ServletChannel", "handle", "Jetty"},
        {"org/eclipse/jetty/ee9/nested/HttpChannel", "handle", "Jetty"},
        {"org/eclipse/jetty/ee8/nested/HttpChannel", "handle", "Jetty"},
        {"io/undertow/server/Connectors", "executeRootHandler", "Undertow"},
        {"org/eclipse/jetty/ee11/servlet/ServletChannel", "handle", "Jetty"},
    };

    // What body saw: the requests running while the server's code ran.
    private static volatile int runningInBody = -1;

    // The scheduled jobs' entries: the class, the method a job's run runs
    // in, and the name a record gives a stand-in's run (its class's: a
    // stand-in has no job to name).
    private static final String[][] JOBS = {
        {"org/quartz/core/JobRunShell", "run", "quartz:org.quartz.core.JobRunShell"},
        {"org/springframework/scheduling/support/ScheduledMethodRunnable", "run",
            "scheduled:org.springframework.scheduling.support.ScheduledMethodRunnable"},
    };
    private static volatile boolean jobThrows;
    private static volatile boolean jobPeeks;
    private static volatile List<String> namedInBody;

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

    @Before
    public void setUp() {
        Logger.getLogger(KeployDedupAgent.class.getName()).addHandler(handler);
        // A window of this test's own: no request of another test counts.
        RequestGate.open();
    }

    @After
    public void tearDown() {
        Logger.getLogger(KeployDedupAgent.class.getName()).removeHandler(handler);
    }

    // body is the server's own code in each stand-in: it says how many
    // requests run, and throws when told to.
    public static long body(Object first, long second, double third, int fourth, int[] fifth, boolean sixth,
            byte seventh, char eighth, short ninth, float tenth) {
        runningInBody = RequestGate.running();
        if (fourth < 0) {
            throw new IllegalStateException("the server's code threw");
        }
        return second + (long) third + fourth + fifth.length + (first == null ? 0 : 1) + (sixth ? 1 : 0)
                + seventh + eighth + ninth + (long) tenth;
    }

    // tick and count are the server's code of entries with no arguments.
    public static void tick() {
        runningInBody = RequestGate.running();
    }

    public static long count() {
        runningInBody = RequestGate.running();
        return 7L;
    }

    private static final Class<?>[] BODY_TYPES = {Object.class, long.class, double.class, int.class, int[].class,
        boolean.class, byte.class, char.class, short.class, float.class};

    private static Object[] args(int fourth) {
        return new Object[] {"x", 2L, 3.5d, fourth, new int[2], true, (byte) 1, (char) 1, (short) 1, 1.5f};
    }

    @Test
    public void eachServersEntryCountsItsRequestWhileItRunsAndWhenItThrows() throws Exception {
        for (String[] entry : ENTRIES) {
            for (boolean isStatic : new boolean[] {false, true}) {
                String label = entry[0] + "." + entry[1] + (isStatic ? " (static)" : "");
                Class<?> type = load(entry[0], transformed(standIn(entry[0], entry[1], isStatic, Opcodes.V1_8, 0)));
                Object owner = isStatic ? null : type.getDeclaredConstructor().newInstance();
                Method method = type.getMethod(entry[1], BODY_TYPES);
                assertEquals(label, isStatic, Modifier.isStatic(method.getModifiers()));

                runningInBody = -1;
                assertEquals(label, 0, RequestGate.running());
                assertEquals(label, 2L + 3L + 4L + 2L + 1L + 1L + 1L + 1L + 1L + 1L, method.invoke(owner, args(4)));
                assertEquals(label + ": counted while the server ran it", 1, runningInBody);
                assertEquals(label + ": done when it returned", 0, RequestGate.running());

                try {
                    method.invoke(owner, args(-1));
                    fail(label);
                } catch (InvocationTargetException e) {
                    assertEquals(label, IllegalStateException.class, e.getCause().getClass());
                }
                assertEquals(label + ": done when it threw", 0, RequestGate.running());

                // The server's own code is still there, renamed and private.
                Method renamed = type.getDeclaredMethod(ServerEntries.RENAMED + entry[1], BODY_TYPES);
                assertTrue(label, Modifier.isPrivate(renamed.getModifiers()) && renamed.isSynthetic());
                assertTrue(label, ServerEntries.counted().contains(entry[2]));
            }
        }
    }

    // jobBody is a job's own code in each stand-in: told to peek, it says
    // which jobs a window opened while it runs names; told to, it throws.
    public static void jobBody() {
        if (jobPeeks) {
            JobGate.open();
            namedInBody = JobGate.close();
        }
        if (jobThrows) {
            throw new IllegalStateException("the job threw");
        }
    }

    @Test
    public void eachJobsRunIsSeenWhileItRunsAndNotOnceItReturnedOrThrew() throws Exception {
        // Java 8 classes as today's schedulers, and a synchronized run() in
        // Java 5 class files as Quartz 1.x jars are built.
        int[][] shapes = {{Opcodes.V1_8, 0}, {Opcodes.V1_5, Opcodes.ACC_SYNCHRONIZED}};
        for (String[] job : JOBS) {
            for (int s = 0; s < shapes.length * 2; s++) {
                boolean throwing = s % 2 == 1;
                int[] shape = shapes[s / 2];
                String label = job[0] + " (class version " + shape[0] + ", run() access " + shape[1] + ")"
                        + (throwing ? " (throws)" : "");
                ServerEntries entries = new ServerEntries();
                byte[] changed = entries.transform(getClass().getClassLoader(), job[0], null, null,
                        jobStandIn(job[0], shape[0], shape[1]));
                assertNotNull(label, changed);
                Class<?> type = load(job[0], changed);
                Runnable run = (Runnable) type.getDeclaredConstructor().newInstance();

                namedInBody = null;
                jobPeeks = true;
                runJob(label, run, throwing);
                assertEquals(label + ": seen running by a window that opens", Collections.singletonList(job[2]),
                        namedInBody);
                JobGate.open();
                runJob(label, run, throwing);
                assertEquals(label + ": named in the window it started in", Collections.singletonList(job[2]),
                        JobGate.close());
                JobGate.open();
                assertEquals(label + ": done once it returned or threw", Collections.<String>emptyList(),
                        JobGate.close());

                // The job's own code is still there, renamed and private.
                Method renamed = type.getDeclaredMethod(ServerEntries.JOB_RENAMED + job[1]);
                assertTrue(label, Modifier.isPrivate(renamed.getModifiers()) && renamed.isSynthetic());
                assertTrue(label, ServerEntries.carriesChange(changed));
                assertFalse("a job's run is no server's request", ServerEntries.counted().contains("Quartz")
                        || ServerEntries.counted().contains("Spring"));
            }
        }
    }

    @Test
    public void withEndsWaitOffAJobsRunIsStillSeenAndNoServerIsChanged() throws Exception {
        ServerEntries entries = new ServerEntries(false);
        ClassLoader loader = getClass().getClassLoader();
        assertNull(entries.transform(loader, ENTRIES[0][0], null, null,
                standIn(ENTRIES[0][0], ENTRIES[0][1], false, Opcodes.V1_8, 0)));
        for (String[] job : JOBS) {
            assertNotNull(job[0], entries.transform(loader, job[0], null, null, jobStandIn(job[0])));
        }
    }

    @Test
    public void aJobsClassChangedAsItLoadedIsChangedAgainWhenRetransformed() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        for (String[] job : JOBS) {
            ServerEntries entries = new ServerEntries();
            byte[] classfile = jobStandIn(job[0]);
            assertNull("not changed as it loaded", entries.transform(loader, job[0], Object.class, null, classfile));
            assertNotNull(entries.transform(loader, job[0], null, null, classfile));
            byte[] again = entries.transform(loader, job[0], Object.class, null, classfile);
            assertNotNull(job[0], again);
            assertTrue(job[0], ServerEntries.carriesChange(again));
            assertNull("bytes that carry the change are taken as they are",
                    entries.transform(loader, job[0], Object.class, null, again));
            Runnable run = (Runnable) load(job[0], again).getDeclaredConstructor().newInstance();
            JobGate.open();
            run.run();
            assertEquals(Collections.singletonList(job[2]), JobGate.close());
        }
    }

    private static void runJob(String label, Runnable run, boolean throwing) {
        jobThrows = throwing;
        try {
            run.run();
            assertFalse(label, throwing);
        } catch (IllegalStateException e) {
            assertTrue(label, throwing);
        } finally {
            jobThrows = false;
            jobPeeks = false;
        }
    }

    // jobStandIn is a Runnable named as a scheduler's job class, whose
    // run() runs jobBody.
    private static byte[] jobStandIn(String internalName) {
        return jobStandIn(internalName, Opcodes.V1_8, 0);
    }

    // ... in class files of this version, with these access flags on run().
    private static byte[] jobStandIn(String internalName, int version, int access) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(version, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object",
                new String[] {"java/lang/Runnable"});
        constructor(writer);
        calls(writer, Opcodes.ACC_PUBLIC | access, "run", "()V", "jobBody", false);
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    public void noOtherMethodIsCounted() throws Exception {
        Class<?> type = load(ENTRIES[0][0], transformed(standIn(ENTRIES[0][0], "service", false, Opcodes.V1_8, 0)));
        Method other = type.getMethod("log", BODY_TYPES);
        runningInBody = -1;
        other.invoke(type.getDeclaredConstructor().newInstance(), args(0));
        assertEquals(0, runningInBody);
    }

    @Test
    public void theCountsHandlerHasItsFrameFromJava6On() throws Exception {
        // A Java 6 class may go without frames only because HotSpot falls
        // back to the old verifier for it: give it its frame as Java 7's.
        for (int version : new int[] {Opcodes.V1_5, Opcodes.V1_6, Opcodes.V1_7}) {
            final int[] frames = new int[1];
            byte[] out = transformed(standIn(ENTRIES[2][0], "handle", false, version, 0));
            new org.objectweb.asm.ClassReader(out).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                    if (!name.equals("handle")) {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitFrame(int type, int locals, Object[] local, int stack, Object[] stackItems) {
                            frames[0]++;
                        }
                    };
                }
            }, 0);
            assertEquals("frames of a class of version " + version, version >= Opcodes.V1_6 ? 1 : 0, frames[0]);
        }
    }

    @Test
    public void aClassFromBeforeStackMapsIsCountedToo() throws Exception {
        Class<?> type = load(ENTRIES[2][0], transformed(standIn(ENTRIES[2][0], "handle", false, Opcodes.V1_5, 0)));
        Method method = type.getMethod("handle", BODY_TYPES);
        runningInBody = -1;
        method.invoke(type.getDeclaredConstructor().newInstance(), args(0));
        assertEquals(1, runningInBody);
        assertEquals(0, RequestGate.running());
    }

    @Test
    public void jacocosCopyOfTheServersClassIsCountedAndKeepsItsIdAtEachClassVersion() throws Exception {
        // The JVM runs this transformer after JaCoCo's, so it wraps the copy
        // JaCoCo instrumented: at Java 8's class version and at 11's, 17's
        // and 21's, as far as this JVM can define them. The copy must pass the
        // verifier (HotSpot verifies by default every class a loader other
        // than the boot loader defines), count its requests, and report its
        // probes under JaCoCo's id of the server's own bytes, the CRC64 of
        // them: the id the index holds, not the id of the wrapped bytes,
        // which JaCoCo would write had it run after this transformer.
        String name = ENTRIES[0][0];
        int feature = Integer.parseInt(System.getProperty("java.specification.version").replaceFirst("^1\\.", ""));
        int ran = 0;
        for (int version : new int[] {Opcodes.V1_8, Opcodes.V11, Opcodes.V17, Opcodes.V21}) {
            if (version > 44 + feature) {
                continue;
            }
            byte[] original = standIn(name, "service", false, version, 0);
            LoggerRuntime runtime = new LoggerRuntime();
            RuntimeData data = new RuntimeData();
            runtime.startup(data);
            try {
                byte[] instrumented = new Instrumenter(runtime).instrument(original, name);
                // Up to Java 10's class version JaCoCo keeps its probes in a
                // field; from 11's on it reads them through a dynamic constant.
                assertEquals("class version " + version, version < Opcodes.V11, hasField(instrumented, "$jacocoData"));
                Class<?> type = load(name, transformed(instrumented));
                Method method = type.getMethod("service", BODY_TYPES);
                runningInBody = -1;
                assertEquals("class version " + version, 2L + 3L + 4L + 2L + 1L + 1L + 1L + 1L + 1L + 1L,
                        method.invoke(type.getDeclaredConstructor().newInstance(), args(4)));
                assertEquals("class version " + version, 1, runningInBody);
                assertEquals("class version " + version, 0, RequestGate.running());
                ExecutionDataStore store = new ExecutionDataStore();
                data.collect(store, new SessionInfoStore(), false);
                assertEquals("class version " + version, 1, store.getContents().size());
                ExecutionData probes = store.getContents().iterator().next();
                assertEquals("class version " + version, name, probes.getName());
                assertEquals("class version " + version, CRC64.classId(original), probes.getId());
                assertNotEquals("class version " + version, CRC64.classId(transformed(original)), probes.getId());
                assertTrue("class version " + version, probes.hasHits());
            } finally {
                runtime.shutdown();
            }
            ran++;
        }
        assertEquals("the class versions this JVM can define", feature >= 21 ? 4 : feature >= 17 ? 3
                : feature >= 11 ? 2 : 1, ran);
    }

    @Test
    public void aSynchronizedEntryKeepsItsLockOnTheServersOwnCode() throws Exception {
        Class<?> type = load(ENTRIES[0][0], transformed(standIn(ENTRIES[0][0], "service", false, Opcodes.V1_8,
                Opcodes.ACC_SYNCHRONIZED)));
        Method method = type.getMethod("service", BODY_TYPES);
        Method renamed = type.getDeclaredMethod(ServerEntries.RENAMED + "service", BODY_TYPES);
        assertFalse(Modifier.isSynchronized(method.getModifiers()));
        assertTrue(Modifier.isSynchronized(renamed.getModifiers()));
        runningInBody = -1;
        method.invoke(type.getDeclaredConstructor().newInstance(), args(0));
        assertEquals(1, runningInBody);
    }

    @Test
    public void entriesWithNoArgumentsAreCountedToo() throws Exception {
        // The least stack an entry's count takes: a static one with no
        // argument (the throw's two slots), and one that returns a long.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, ENTRIES[6][0], null, "java/lang/Object",
                null);
        constructor(writer);
        calls(writer, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "executeRootHandler", "()V", "tick", true);
        writer.visitEnd();
        Class<?> type = load(ENTRIES[6][0], transformed(writer.toByteArray()));
        runningInBody = -1;
        type.getMethod("executeRootHandler").invoke(null);
        assertEquals(1, runningInBody);

        writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, ENTRIES[2][0], null, "java/lang/Object",
                null);
        constructor(writer);
        calls(writer, Opcodes.ACC_PUBLIC, "handle", "()J", "count", false);
        writer.visitEnd();
        type = load(ENTRIES[2][0], transformed(writer.toByteArray()));
        runningInBody = -1;
        assertEquals(7L, type.getMethod("handle").invoke(type.getDeclaredConstructor().newInstance()));
        assertEquals(1, runningInBody);
        assertEquals(0, RequestGate.running());
    }

    @Test
    public void anEntryWithNoCodeOfItsOwnIsNotCounted() throws Exception {
        // Abstract, native, a bridge, or made by the compiler: none is the
        // server's request code.
        int[] flags = {Opcodes.ACC_ABSTRACT, Opcodes.ACC_NATIVE, Opcodes.ACC_BRIDGE, Opcodes.ACC_SYNTHETIC};
        for (int flag : flags) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER | Opcodes.ACC_ABSTRACT, ENTRIES[0][0],
                    null, "java/lang/Object", null);
            constructor(writer);
            if (flag == Opcodes.ACC_ABSTRACT || flag == Opcodes.ACC_NATIVE) {
                writer.visitMethod(Opcodes.ACC_PUBLIC | flag, "service", "()V", null, null).visitEnd();
            } else {
                calls(writer, Opcodes.ACC_PUBLIC | flag, "service", "()V", "tick", false);
            }
            writer.visitEnd();
            assertNull("flag " + flag, transformedOrNull(ENTRIES[0][0], writer.toByteArray(),
                    getClass().getClassLoader()));
        }
    }

    @Test
    public void anyOtherClassIsLeftAlone() throws Exception {
        byte[] other = standIn("org/apache/catalina/core/StandardWrapperValve", "service", false, Opcodes.V1_8, 0);
        assertNull(new ServerEntries().transform(getClass().getClassLoader(),
                "org/apache/catalina/core/StandardWrapperValve", null, null, other));
        // An interface of an entry's name (Jetty 12's HttpChannel) has no
        // code to count.
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                "org/eclipse/jetty/server/HttpChannel", null, "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "handle", "()V", null, null).visitEnd();
        writer.visitEnd();
        assertNull(transformedOrNull("org/eclipse/jetty/server/HttpChannel", writer.toByteArray(),
                getClass().getClassLoader()));
        // Nor one with a default method of the entry's name.
        writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                "org/eclipse/jetty/server/HttpChannel", null, "java/lang/Object", null);
        calls(writer, Opcodes.ACC_PUBLIC, "handle", "()V", "tick", false);
        writer.visitEnd();
        assertNull(transformedOrNull("org/eclipse/jetty/server/HttpChannel", writer.toByteArray(),
                getClass().getClassLoader()));
        assertFalse("only a server's class is named", ServerEntries.uncounted().contains(null));
        // Passed over quietly: on Jetty 12 the requests count in its
        // ServletChannel, so a WARNING would be wrong at each start.
        assertNull(logged.toString(), logged("WARNING"));
        // An entry's class with no method of its names.
        assertNull(transformedOrNull(ENTRIES[0][0], standIn(ENTRIES[0][0], "other", false, Opcodes.V1_8, 0),
                getClass().getClassLoader()));
        assertNotNull(logged("WARNING Keploy dedup does not count Tomcat's requests:"
                + " org.apache.catalina.connector.CoyoteAdapter has no method"));
        // A class retransformed later that was not changed as it loaded (it
        // loaded before the SDK started) is left as it is.
        assertNull(new ServerEntries().transform(getClass().getClassLoader(), ENTRIES[0][0], Object.class, null,
                standIn(ENTRIES[0][0], "service", false, Opcodes.V1_8, 0)));
    }

    @Test
    public void aClassChangedAsItLoadedIsChangedAgainWhenRetransformed() throws Exception {
        ServerEntries entries = new ServerEntries();
        ClassLoader loader = getClass().getClassLoader();
        byte[] classfile = standIn(ENTRIES[0][0], "service", false, Opcodes.V1_8, 0);
        assertNull("loaded before the SDK started",
                entries.transform(loader, ENTRIES[0][0], Object.class, null, classfile));
        byte[] loaded = entries.transform(loader, ENTRIES[0][0], null, null, classfile);
        assertNotNull(loaded);
        // Another agent retransforms it: the JVM passes the class as it was
        // before the SDK changed it, which changes it the same way again.
        byte[] again = entries.transform(loader, ENTRIES[0][0], Object.class, null, classfile);
        assertArrayEquals(loaded, again);
        Class<?> type = load(ENTRIES[0][0], again);
        runningInBody = -1;
        type.getMethod("service", BODY_TYPES).invoke(type.getDeclaredConstructor().newInstance(), args(4));
        assertEquals("counted while the server ran it", 1, runningInBody);
        // Not another loader's class of that name, nor another class of that
        // loader, nor a class another ServerEntries changed.
        try (URLClassLoader other = new URLClassLoader(new URL[0], loader)) {
            assertNull(entries.transform(other, ENTRIES[0][0], Object.class, null, classfile));
        }
        assertNull(entries.transform(loader, ENTRIES[6][0], Object.class, null,
                standIn(ENTRIES[6][0], "executeRootHandler", true, Opcodes.V1_8, 0)));
        assertNull(new ServerEntries().transform(loader, ENTRIES[0][0], Object.class, null, classfile));
        assertNull(logged.toString(), logged("WARNING"));
        // A class it cannot change again: the JVM refuses that retransform,
        // and the log says why.
        assertNull(entries.transform(loader, ENTRIES[0][0], Object.class, null,
                standIn(ENTRIES[0][0], "other", false, Opcodes.V1_8, 0)));
        assertNotNull(logged.toString(), logged("WARNING Keploy dedup cannot count Tomcat's requests in"
                + " org.apache.catalina.connector.CoyoteAdapter as another agent changes it: it has no method"));
        assertNull(entries.transform(loader, ENTRIES[0][0], Object.class, null, new byte[] {1, 2, 3}));
        assertNotNull(logged.toString(), logged("WARNING Keploy dedup cannot count Tomcat's requests in"
                + " org.apache.catalina.connector.CoyoteAdapter as another agent changes it, so the JVM"));
    }

    @Test
    public void bytesThatAlreadyCarryTheChangeAreTakenAsTheyAre() throws Exception {
        ServerEntries entries = new ServerEntries();
        ClassLoader loader = getClass().getClassLoader();
        byte[] classfile = standIn(ENTRIES[0][0], "service", false, Opcodes.V1_8, 0);
        byte[] loaded = entries.transform(loader, ENTRIES[0][0], null, null, classfile);
        assertNotNull(loaded);
        assertTrue(ServerEntries.carriesChange(loaded));
        assertFalse(ServerEntries.carriesChange(classfile));
        // Changed twice, the class would declare the renamed method twice,
        // which the JVM refuses.
        try {
            load(ENTRIES[0][0], ServerEntries.wrap(loaded, Collections.singleton("service")));
            fail("a class changed twice loaded");
        } catch (ClassFormatError e) {
            // As the JVM refuses it.
        }
        // Another agent redefines it with its copy of the class as the SDK
        // changed it (as a tool's dump has it): the JVM takes it as it is.
        assertNull(entries.transform(loader, ENTRIES[0][0], Object.class, null, loaded));
        // A class that loads with the change already in it counts as it is,
        // and a later redefine with the server's own class changes it again.
        ServerEntries fresh = new ServerEntries();
        logged.clear();
        assertNull(fresh.transform(loader, ENTRIES[0][0], null, null, loaded));
        assertNotNull(logged.toString(), logged("INFO Keploy dedup counts Tomcat's requests"));
        Class<?> type = load(ENTRIES[0][0], loaded);
        runningInBody = -1;
        type.getMethod("service", BODY_TYPES).invoke(type.getDeclaredConstructor().newInstance(), args(4));
        assertEquals("counted while the server ran it", 1, runningInBody);
        assertArrayEquals(loaded, fresh.transform(loader, ENTRIES[0][0], Object.class, null, classfile));
        assertNull(logged.toString(), logged("WARNING"));
    }

    @Test
    public void aServerWhoseLoaderDoesNotSeeTheGateIsNotChanged() throws Exception {
        byte[] classfile = standIn(ENTRIES[6][0], "executeRootHandler", true, Opcodes.V1_8, 0);
        // A module system's loader, that sees the JDK alone.
        try (URLClassLoader alone = new URLClassLoader(new URL[0], null)) {
            assertNull(transformedOrNull(ENTRIES[6][0], classfile, alone));
        }
        assertNull(transformedOrNull(ENTRIES[6][0], classfile, null));
        // A loader with a copy of its own, which END never reads.
        URL classes = RequestGate.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader own = new URLClassLoader(new URL[] {classes}, null)) {
            assertTrue(Class.forName(RequestGate.class.getName(), false, own) != RequestGate.class);
            assertNull(transformedOrNull(ENTRIES[6][0], classfile, own));
        }
        assertNotNull(logged("WARNING Keploy dedup does not count Undertow's requests: the class loader of"
                + " io.undertow.server.Connectors does not see keploy-sdk.jar's classes, so a test's END reads its"
                + " hits without waiting for its requests to finish"));
    }

    @Test
    public void aJobWhoseLoaderDoesNotSeeTheGateIsNotChangedAndTheLogSaysWhatIsLost() throws Exception {
        byte[] classfile = jobStandIn(JOBS[0][0]);
        try (URLClassLoader alone = new URLClassLoader(new URL[0], null)) {
            assertNull(transformedOrNull(JOBS[0][0], classfile, alone));
        }
        assertNotNull(logged.toString(), logged("WARNING Keploy dedup does not count Quartz's job runs: the class"
                + " loader of org.quartz.core.JobRunShell does not see keploy-sdk.jar's classes, so a test a job"
                + " runs in is not marked contaminated"));
    }

    @Test
    public void nettyIsNamedAsAServerNotCounted() throws Exception {
        assertNull(new ServerEntries().transform(getClass().getClassLoader(),
                "io/netty/handler/codec/http/HttpServerCodec", null, null, new byte[0]));
        assertTrue(ServerEntries.uncounted().contains("Netty"));
    }

    @Test
    public void aClassThatCannotBeReadLoadsUnchanged() throws Exception {
        assertNull(transformedOrNull(ENTRIES[0][0], new byte[] {1, 2, 3}, getClass().getClassLoader()));
        assertNotNull(logged("WARNING Keploy dedup does not count Tomcat's requests:"
                + " org.apache.catalina.connector.CoyoteAdapter could not be changed"));
    }

    private String logged(String prefix) {
        for (String line : logged) {
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        return null;
    }

    private byte[] transformed(byte[] classfile) throws Exception {
        String name = new org.objectweb.asm.ClassReader(classfile).getClassName();
        byte[] out = transformedOrNull(name, classfile, getClass().getClassLoader());
        assertNotNull(name, out);
        return out;
    }

    private static byte[] transformedOrNull(String name, byte[] classfile, ClassLoader loader) {
        return new ServerEntries().transform(loader, name, null, null, classfile);
    }

    // standIn is a class named as a server's, whose method `name` (and
    // `log`, of no entry's name) runs body with its five arguments.
    private static byte[] standIn(String internalName, String name, boolean isStatic, int version, int extra) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(version, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        callsBody(writer, Opcodes.ACC_PUBLIC | extra | (isStatic ? Opcodes.ACC_STATIC : 0), name, isStatic);
        callsBody(writer, Opcodes.ACC_PUBLIC, "log", false);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void constructor(ClassWriter writer) {
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }

    // calls adds a method that returns what the test's static method
    // target (of the same descriptor, no arguments) returns.
    private static void calls(ClassWriter writer, int access, String name, String descriptor, String target,
            boolean isStatic) {
        MethodVisitor mv = writer.visitMethod(access | (isStatic ? Opcodes.ACC_STATIC : 0), name, descriptor, null,
                null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(ServerEntriesTest.class), target, descriptor,
                false);
        mv.visitInsn(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN));
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void callsBody(ClassWriter writer, int access, String name, boolean isStatic) {
        MethodVisitor mv = writer.visitMethod(access, name, BODY_DESCRIPTOR, null,
                new String[] {"java/lang/Exception"});
        mv.visitCode();
        int slot = isStatic ? 0 : 1;
        for (Type arg : Type.getArgumentTypes(BODY_DESCRIPTOR)) {
            mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
            slot += arg.getSize();
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(ServerEntriesTest.class), "body",
                BODY_DESCRIPTOR, false);
        mv.visitInsn(Opcodes.LRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static boolean hasField(byte[] classfile, final String name) {
        final boolean[] found = new boolean[1];
        new org.objectweb.asm.ClassReader(classfile).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String field, String descriptor,
                    String signature, Object value) {
                found[0] |= field.equals(name);
                return null;
            }
        }, 0);
        return found[0];
    }

    private static Class<?> load(String internalName, byte[] classfile) {
        return new ClassLoader(ServerEntriesTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(internalName.replace('/', '.'), classfile, 0, classfile.length);
            }
        }.define();
    }
}
