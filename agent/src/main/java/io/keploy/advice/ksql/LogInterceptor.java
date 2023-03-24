package io.keploy.advice.ksql;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.implementation.bind.annotation.This;

public class LogInterceptor {

    @RuntimeType
    public static Object log(@This(optional = true) Object obj,
                             @Origin Method method,
                             @SuperCall Callable<?> callable) throws Exception {
        String key = method.getDeclaringClass().getName().replace('.', '/') + "." + method.getName();
        Integer lineNumber = LineNumberCache.get(key);
        System.out.println("Entering method: " + method + (lineNumber != null ? " at line number: " + lineNumber : ""));

        Object result = callable.call();

        System.out.println("Exiting method: " + method + (lineNumber != null ? " at line number: " + lineNumber : ""));
        return result;
    }
}
