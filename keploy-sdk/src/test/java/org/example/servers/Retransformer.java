package org.example.servers;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Retransformer is a -javaagent of ServerTailTest's JVMs that stands for
 * another agent (an APM agent, a profiler) retransforming Tomcat's
 * CoyoteAdapter once the server runs. With "load", its premain also loads
 * that class, before the agents listed after it start. With "redefine", it
 * keeps that class's bytes as the agents listed before it changed them as
 * it loaded (as a tool's dump has them), and redefines the class with them
 * instead (as a hot-swap tool does with a class it dumped).
 */
public final class Retransformer {

    static final String ADAPTER = "org.apache.catalina.connector.CoyoteAdapter";

    private static volatile Instrumentation instrumentation;
    private static volatile int seen;
    private static volatile byte[] loaded;

    private Retransformer() {
    }

    public static void premain(String args, Instrumentation inst) throws Exception {
        instrumentation = inst;
        if ("load".equals(args)) {
            Class.forName(ADAPTER, false, ClassLoader.getSystemClassLoader());
        }
        if ("redefine".equals(args)) {
            inst.addTransformer(new ClassFileTransformer() {
                @Override
                public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                        ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                    if (classBeingRedefined == null && className.equals(ADAPTER.replace('.', '/'))) {
                        loaded = classfileBuffer.clone();
                    }
                    return null;
                }
            }, true);
        }
    }

    // afterStart retransforms CoyoteAdapter when this agent runs, and says
    // how it went: RETRANSFORMED and how many times its own transformer saw
    // the class, or RETRANSFORM FAILED and why; with "redefine", REDEFINED
    // or REDEFINE FAILED.
    static void afterStart() {
        Instrumentation inst = instrumentation;
        if (inst == null) {
            return;
        }
        byte[] bytes = loaded;
        if (bytes != null) {
            try {
                inst.redefineClasses(new ClassDefinition(Class.forName(ADAPTER), bytes));
                System.out.println("REDEFINED " + ADAPTER);
            } catch (Throwable t) {
                System.out.println("REDEFINE FAILED " + t);
            }
            System.out.flush();
            return;
        }
        ClassFileTransformer counter = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (classBeingRedefined != null && className.equals(ADAPTER.replace('.', '/'))) {
                    seen++;
                }
                return null;
            }
        };
        inst.addTransformer(counter, true);
        try {
            inst.retransformClasses(Class.forName(ADAPTER));
            System.out.println("RETRANSFORMED " + ADAPTER + " seen=" + seen);
        } catch (Throwable t) {
            System.out.println("RETRANSFORM FAILED " + t);
        } finally {
            inst.removeTransformer(counter);
        }
        System.out.flush();
    }
}
