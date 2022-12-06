package io.keploy.advice.ksql;

import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

public class HealthCheckInterceptor {

    @Advice.OnMethodEnter
    public static void enterMethod(@Advice.Origin Method method, @Advice.Argument(value = 0, readOnly = false) String key, @Advice.Argument(value = 1, readOnly = false) Object value) {
//        System.out.println("Inside Enter Advice: " + method);
//        System.out.println("Health Checkk !!");
        if (value == null) {
            value = "HI";
        }
    }

    @Advice.OnMethodExit
    public static void exitMethod(@Advice.Origin Method method) {
//        System.out.println("Inside Exit Advice: " + method);
    }
}
