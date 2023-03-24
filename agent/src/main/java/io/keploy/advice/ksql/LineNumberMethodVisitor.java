package io.keploy.advice.ksql;

import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

public class LineNumberMethodVisitor extends MethodVisitor {
    private String className;
    private String methodName;

    public LineNumberMethodVisitor(int api, MethodVisitor methodVisitor, String className, String methodName) {
        super(api, methodVisitor);
        this.className = className;
        this.methodName = methodName;
    }

    @Override
    public void visitLineNumber(int line, Label start) {
        String key = className + "." + methodName;
        if (!LineNumberCache.contains(key)) {
            LineNumberCache.put(key, line);
        }
        super.visitLineNumber(line, start);
    }
}
