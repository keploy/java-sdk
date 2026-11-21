package io.keploy.dedup;

import java.lang.instrument.ClassFileTransformer;
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
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * ServerEntries makes each request of Tomcat, Jetty and Undertow count in
 * {@link RequestGate} while the server runs it: the method each server runs
 * a request in (and each async dispatch of it) becomes a method of the same
 * name and access that calls {@link RequestGate#enter()}, then the server's
 * own code, renamed {@code keploy$request$<name>} and private, then
 * {@link RequestGate#exit(Object)}, however it returns or throws. Nothing
 * else in the server's code changes.
 *
 * <p>It is added as a transformer that can retransform, so the JVM runs it
 * after every transformer that cannot, as JaCoCo's, whatever the order the
 * agents were attached in: JaCoCo instruments, and ids, the server's own
 * class, and this wraps JaCoCo's copy. When another agent retransforms or
 * redefines a class this changed as it loaded, the JVM passes the class as
 * it was before this changed it (a retransform) or the redefiner's bytes (a
 * redefine), and this changes them again, as the JVM refuses a class that
 * has lost a method, unless they already carry the change, which the JVM
 * then takes as they are.
 *
 * <p>Only classes loaded from now on change (the JVM refuses a method added
 * to a loaded class), so a server started before the SDK is not counted, and
 * neither is one whose class loader does not see keploy-sdk.jar's classes,
 * as in a module system (both said in the log). A server in a named module
 * (Tomcat's jar on the module path) is counted: the JVM makes a module whose
 * class an agent changed read the class path's classes. Netty, and so
 * WebFlux, is not counted: END then reads a test's hits as soon as it comes.
 *
 * <p>It makes each run of a scheduled job count in {@link JobGate} the same
 * way: Quartz's {@code JobRunShell.run()} and Spring's
 * {@code ScheduledMethodRunnable.run()} (each {@code @Scheduled} method)
 * become methods that call {@link JobGate#enter(Object, String)}, then their
 * own code, renamed {@code keploy$job$run}, then {@link JobGate#exit(Object)},
 * so that a test whose window a job ran in says so. Those count whether or
 * not END waits for requests.
 *
 * <p>It makes the work a request hands to a Spring TaskExecutor count as
 * the request's (see {@link RequestGate#hand(Runnable)}): the methods of
 * SimpleAsyncTaskExecutor (an async event multicaster's), ThreadPoolTaskExecutor
 * and {@code @Async}'s interceptor that take a task become methods that
 * hand the task to RequestGate, then call their own code, renamed
 * {@code keploy$async$<name>}, with what RequestGate gave back, and when that
 * throws, tell RequestGate the task was dropped. A task a job's run hands
 * off counts as that run whether or not END waits for requests; a
 * request's counts only when it does (servers are changed only then).
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
        // Spring's executors a request hands work to: SimpleAsyncTaskExecutor
        // (an async event multicaster's, and Spring's default for @Async)
        // starts each task in doExecute, whatever method it was handed in;
        // ThreadPoolTaskExecutor (Spring Boot's) passes each to its pool in
        // the method it was handed in (its submitCompletable, from Spring 6,
        // is AsyncTaskExecutor's default, which hands the task to execute);
        // @Async's interceptor hands each call to its executor, whatever
        // that is, in doSubmit.
        addAsync("org/springframework/core/task/SimpleAsyncTaskExecutor", "doExecute");
        addAsync("org/springframework/scheduling/concurrent/ThreadPoolTaskExecutor", "execute", "submit",
                "submitListenable");
        addAsync("org/springframework/aop/interceptor/AsyncExecutionAspectSupport", "doSubmit");
    }

    // Classes of servers that are not counted, so that the log can name them.
    private static final Map<String, String> UNCOUNTED = new HashMap<>();

    static {
        UNCOUNTED.put("io/netty/handler/codec/http/HttpServerCodec", "Netty");
        UNCOUNTED.put("reactor/netty/http/server/HttpServer", "Reactor Netty");
    }

    private static final Set<String> COUNTED = new CopyOnWriteArraySet<>();
    private static final Set<String> SEEN_UNCOUNTED = new CopyOnWriteArraySet<>();

    // The classes this changed as they loaded, by their loader: the ones it
    // changes again when another agent retransforms them.
    private final Map<ClassLoader, Set<String>> changed = new WeakHashMap<>();
    // Whether servers' requests count (END waits for them); jobs' runs
    // count either way.
    private final boolean requests;

    ServerEntries() {
        this(true);
    }

    ServerEntries(boolean requests) {
        this.requests = requests;
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
                LOGGER.log(Level.WARNING, "Keploy dedup does not count " + entry.what() + ": the class loader of "
                        + name + " does not see keploy-sdk.jar's classes, so " + entry.lost(requests));
                return null;
            }
            // Bytes that already carry the change (another agent's copy of
            // the class as this changed it) count as they are.
            boolean carries = carriesChange(classfileBuffer);
            byte[] wrapped = carries ? null : wrap(classfileBuffer, entry);
            if (!carries && wrapped == null) {
                LOGGER.log(Level.WARNING, "Keploy dedup does not count " + entry.what() + ": " + name
                        + " has no method " + entry.methods + " to count them in");
                return null;
            }
            if (entry.job == null && !entry.async) {
                COUNTED.add(entry.server);
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
            LOGGER.log(Level.WARNING, "Keploy dedup does not count " + entry.what() + ": " + name
                    + " could not be changed", t);
            return null;
        }
    }

    // changedAgain is a class another agent retransforms or redefines, when
    // this changed it as it loaded. The JVM passes a retransform the class as
    // it was before this changed it, and a redefine the redefiner's bytes,
    // and refuses a class that has lost a method this added: so either is
    // changed again, unless the bytes already carry the change (a redefiner's
    // copy of the class as this changed it), which the JVM then takes as they
    // are. Any other class is left as it is: it was loaded unchanged, and the
    // JVM refuses a method added to it.
    private byte[] changedAgain(ClassLoader loader, String className, byte[] classfile) {
        Entry entry = ENTRIES.get(className);
        if (entry == null) {
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

    // seesGate says whether code of a class loader defines calls the
    // RequestGate END reads (and so JobGate, of the same jar): one it cannot
    // see would fail each request or job, and a copy of its own would count
    // where END never looks.
    private static boolean seesGate(ClassLoader loader) {
        // The bootstrap loader (null) does not see it either.
        try {
            return Class.forName(RequestGate.class.getName(), false, loader) == RequestGate.class;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isInterface(byte[] classfile) {
        return (new ClassReader(classfile).getAccess() & Opcodes.ACC_INTERFACE) != 0;
    }

    // carriesChange says whether the class already declares a method this
    // renamed: only wrap names a method so.
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

    // wrap returns the class with each method of these names counted as a
    // server's request, or null when it has none to count (abstract and
    // native methods only). transform passes over an interface before it.
    static byte[] wrap(byte[] classfile, Set<String> methods) {
        return wrap(classfile, methods, null, false);
    }

    private static byte[] wrap(byte[] classfile, Entry entry) {
        return wrap(classfile, entry.methods, entry.job, entry.async);
    }

    // wrap with a job kind counts each method's run as a job's run of that
    // kind (JobGate) instead; with async, it hands each method's task (its
    // first Runnable or Callable argument; a method with none is left as
    // it is) to RequestGate instead.
    static byte[] wrap(byte[] classfile, Set<String> methods, String job, boolean async) {
        ClassReader reader = new ClassReader(classfile);
        ClassWriter writer = new ClassWriter(reader, 0);
        Wrapper wrapper = new Wrapper(writer, methods, job, async);
        reader.accept(wrapper, 0);
        return wrapper.wrapped.isEmpty() ? null : writer.toByteArray();
    }

    private static final class Entry {

        // The server or scheduler whose code it is, as the log names it.
        private final String server;
        // The JobGate kind of a job's entry; null for a server's or an executor's.
        private final String job;
        // Whether it is an executor's: the tasks a request hands it count.
        private final boolean async;
        private final Set<String> methods;

        private Entry(String server, String job, boolean async, Set<String> methods) {
            this.server = server;
            this.job = job;
            this.async = async;
            this.methods = Collections.unmodifiableSet(methods);
        }

        // what is what this entry counts, as the log says it.
        private String what() {
            return server + (async ? "'s async tasks" : job == null ? "'s requests" : "'s job runs");
        }

        // effect is what counting them does.
        private String effect(boolean requests) {
            String jobs = "a task a scheduled job's run hands off counts as that run, so a test whose window it"
                    + " runs in is marked contaminated in its record";
            return async ? (requests ? "a test's END waits for the tasks its requests hand off to finish, as for"
                    + " the requests, and " + jobs : jobs)
                    : job == null ? "a test's END waits for them to finish"
                    : "a test whose window a run overlaps is marked contaminated in its record, and Keploy does"
                    + " not judge it";
        }

        // lost is what is lost when they cannot be counted.
        private String lost(boolean requests) {
            String jobs = "a test is not marked contaminated when work a scheduled job hands off runs in its window";
            return async ? (requests ? "a test's END reads its hits without waiting for the tasks its requests"
                    + " hand off, and " + jobs : jobs)
                    : job == null ? "a test's END reads its hits without waiting for its requests to finish"
                    : "a test a job runs in is not marked contaminated";
        }
    }

    // Wrapper renames each method to count and, at the class's end, adds
    // the method that counts it under its old name and access: in
    // RequestGate, with a job kind in JobGate, or with async the task it
    // is handed in RequestGate.
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

        // task is the index of a method's first Runnable or Callable
        // argument: the task an executor's method is handed; -1 when it
        // has none.
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

        // counting adds, under the method's name and access, a method that
        // counts the request in RequestGate around a call of the renamed one.
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
                // The job's object (null for a static method) and its kind.
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

        // handing adds, under an executor's method's name and access, a
        // method that hands its task to RequestGate and calls the renamed
        // one with what RequestGate gave back in its place; when that
        // throws, RequestGate hears the task was dropped.
        private void handing(Method method) {
            int access = method.access & ~Opcodes.ACC_SYNCHRONIZED;
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            Type[] args = Type.getArgumentTypes(method.descriptor);
            Type returns = Type.getReturnType(method.descriptor);
            int task = task(method.descriptor);
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
            for (int i = 0; i < args.length; i++) {
                if (i == task) {
                    taskSlot = slot;
                }
                locals.add(frameType(args[i]));
                slot += args[i].getSize();
            }
            int handed = slot;
            locals.add(args[task].getInternalName());

            mv.visitVarInsn(Opcodes.ALOAD, taskSlot);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, GATE, "hand", "(" + taskType + ")" + taskType, false);
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
            mv.visitMaxs(Math.max(Math.max(slot, returns.getSize()), 3), handed + 1);
            mv.visitEnd();
        }

        // gate is the class whose exit the counting method calls.
        private String gate() {
            return job == null ? GATE : JOB_GATE;
        }

        // frameType is how a stack map frame names a local of this type.
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
