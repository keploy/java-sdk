package io.keploy.advice.ksql;

import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class LineCoverageClassVisitor extends ClassVisitor {
    private String className;

    public LineCoverageClassVisitor(int api, ClassVisitor classVisitor) {
        super(api, classVisitor);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        this.className = name.replace('/', '.');
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        return new LineCoverageMethodVisitor(api, mv, className, name);
    }

    static class LineCoverageMethodVisitor extends MethodVisitor {
        private final String className;
        private final String methodName;
        private static final ConcurrentHashMap<String, AtomicInteger> lineCoverage = new ConcurrentHashMap<>();

        public LineCoverageMethodVisitor(int api, MethodVisitor methodVisitor, String className, String methodName) {
            super(api, methodVisitor);
            this.className = className;
            this.methodName = methodName;
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            super.visitLineNumber(line, start);
            String lineIdentifier = className + "." + methodName + ":" + line;

            // Increment the line coverage counter for the given line
            lineCoverage.computeIfAbsent(lineIdentifier, key -> new AtomicInteger(0)).incrementAndGet();
        }

        public static ConcurrentHashMap<String, AtomicInteger> getLineCoverage() {
            return lineCoverage;
        }
    }
}
