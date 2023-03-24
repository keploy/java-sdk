package io.keploy.advice.ksql;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;

public class LoggingInterceptor {

    @RuntimeType
    public static void log(@Advice.Origin("#t") String className, @Advice.Origin("#m") String methodName) {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement stackTraceElement : stackTrace) {
            if (stackTraceElement.getClassName().equals(className) && stackTraceElement.getMethodName().equals(methodName)) {
                System.out.println("Executing line " + stackTraceElement.getLineNumber() + " in " + className + "." + methodName);
                break;
            }
        }
    }
}
