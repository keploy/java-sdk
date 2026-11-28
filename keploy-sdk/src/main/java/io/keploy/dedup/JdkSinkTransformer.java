package io.keploy.dedup;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Retransforms the 5+1 JDK concurrency sinks to invoke
 * {@code io.keploy.dedup.bootstrap.KeploySinkBridge} at method entry using
 * {@code ClassWriter(reader, 0)} (zero frame recomputation, zero class loading
 * during transform, 100% legal under {@code Instrumentation.retransformClasses}
 * on JDK 8, 11, 17, and 21).
 */
final class JdkSinkTransformer implements ClassFileTransformer {

    private static final Logger LOGGER = Logger.getLogger(KeployDedupAgent.class.getName());
    static final String BRIDGE_INTERNAL = "io/keploy/dedup/bootstrap/KeploySinkBridge";

    static final String THREAD = "java/lang/Thread";
    static final String VIRTUAL_THREAD = "java/lang/VirtualThread";
    static final String TPE = "java/util/concurrent/ThreadPoolExecutor";
    static final String STPE = "java/util/concurrent/ScheduledThreadPoolExecutor";
    static final String FJP = "java/util/concurrent/ForkJoinPool";
    static final String TIMER = "java/util/Timer";

    // Volatile diagnostics counters for unit/mutant verification that each sink
    // was actually instrumented in the running JVM.
    static volatile boolean hookedThreadStart;
    static volatile boolean hookedVirtualThreadStart;
    static volatile boolean hookedTpeExecute;
    static volatile boolean hookedStpeDelayedExecute;
    static volatile boolean hookedFjpSubmit;
    static volatile boolean hookedTimerSched;

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (loader != null || className == null || classfileBuffer == null) {
            return null;
        }
        if (!isTargetSinkClass(className)) {
            return null;
        }
        try {
            return instrumentSinkClass(className, classfileBuffer);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Keploy dedup failed to instrument JDK sink " + className, t);
            return null;
        }
    }

    static boolean isTargetSinkClass(String internalName) {
        return THREAD.equals(internalName)
                || VIRTUAL_THREAD.equals(internalName)
                || TPE.equals(internalName)
                || STPE.equals(internalName)
                || FJP.equals(internalName)
                || TIMER.equals(internalName);
    }

    static byte[] instrumentSinkClass(final String className, byte[] classfile) {
        ClassReader reader = new ClassReader(classfile);
        ClassWriter writer = new ClassWriter(reader, 0);
        final boolean[] changed = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, final String name, final String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_STATIC)) != 0) {
                    return mv;
                }
                final SinkHook hook = matchHook(className, name, descriptor);
                if (hook == null) {
                    return mv;
                }
                changed[0] = true;
                recordHooked(className);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    private boolean injected;

                    @Override
                    public void visitCode() {
                        super.visitCode();
                        if (!injected) {
                            injected = true;
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            if (hook.secondArgIndex > 0) {
                                super.visitVarInsn(Opcodes.ALOAD, hook.secondArgIndex);
                            }
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_INTERNAL,
                                    hook.bridgeMethod, hook.bridgeDesc, false);
                        }
                    }

                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        int needed = hook.secondArgIndex > 0 ? 2 : 1;
                        super.visitMaxs(Math.max(maxStack, needed), maxLocals);
                    }
                };
            }
        }, 0);
        return changed[0] ? writer.toByteArray() : null;
    }

    private static SinkHook matchHook(String className, String name, String desc) {
        if (THREAD.equals(className)) {
            if ("start".equals(name)
                    && ("()V".equals(desc) || "(Ljdk/internal/vm/ThreadContainer;)V".equals(desc))) {
                return new SinkHook("onThreadStart", "(Ljava/lang/Thread;)V", 0);
            }
            return null;
        }
        if (VIRTUAL_THREAD.equals(className)) {
            if ("start".equals(name)
                    && ("()V".equals(desc) || "(Ljdk/internal/vm/ThreadContainer;)V".equals(desc))) {
                return new SinkHook("onVirtualThreadStart", "(Ljava/lang/Thread;)V", 0);
            }
            return null;
        }
        if (TPE.equals(className)) {
            if ("execute".equals(name) && "(Ljava/lang/Runnable;)V".equals(desc)) {
                return new SinkHook("onTpeExecute", "(Ljava/lang/Object;Ljava/lang/Object;)V", 1);
            }
            return null;
        }
        if (STPE.equals(className)) {
            if ("delayedExecute".equals(name)
                    && "(Ljava/util/concurrent/RunnableScheduledFuture;)V".equals(desc)) {
                return new SinkHook("onStpeDelayedExecute", "(Ljava/lang/Object;Ljava/lang/Object;)V", 1);
            }
            return null;
        }
        if (FJP.equals(className)) {
            if (("externalPush".equals(name) || "externalSubmit".equals(name) || "lazySubmit".equals(name))
                    && desc.startsWith("(Ljava/util/concurrent/ForkJoinTask;)")) {
                return new SinkHook("onFjpExternalSubmit", "(Ljava/lang/Object;Ljava/lang/Object;)V", 1);
            }
            if ("poolSubmit".equals(name)
                    && desc.startsWith("(ZLjava/util/concurrent/ForkJoinTask;)")) {
                return new SinkHook("onFjpExternalSubmit", "(Ljava/lang/Object;Ljava/lang/Object;)V", 2);
            }
            return null;
        }
        if (TIMER.equals(className)) {
            if ("sched".equals(name) && "(Ljava/util/TimerTask;JJ)V".equals(desc)) {
                return new SinkHook("onTimerSched", "(Ljava/lang/Object;Ljava/lang/Object;)V", 1);
            }
            return null;
        }
        return null;
    }

    private static void recordHooked(String className) {
        if (THREAD.equals(className)) {
            hookedThreadStart = true;
        } else if (VIRTUAL_THREAD.equals(className)) {
            hookedVirtualThreadStart = true;
        } else if (TPE.equals(className)) {
            hookedTpeExecute = true;
        } else if (STPE.equals(className)) {
            hookedStpeDelayedExecute = true;
        } else if (FJP.equals(className)) {
            hookedFjpSubmit = true;
        } else if (TIMER.equals(className)) {
            hookedTimerSched = true;
        }
    }

    private static final class SinkHook {
        final String bridgeMethod;
        final String bridgeDesc;
        final int secondArgIndex;

        SinkHook(String bridgeMethod, String bridgeDesc, int secondArgIndex) {
            this.bridgeMethod = bridgeMethod;
            this.bridgeDesc = bridgeDesc;
            this.secondArgIndex = secondArgIndex;
        }
    }
}
