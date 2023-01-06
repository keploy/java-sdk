package io.keploy.advice.ksql;

import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

public class SetEnabled_Advice {

    @Advice.OnMethodEnter
    public static void enterMethod(@Advice.Origin Method method, @Advice.Argument(value = 0, readOnly = false) boolean enabled) {
//        System.out.println("Entering method[" + method + "] with argument[" + enabled + "] from EnterAdvice");
        enabled = (System.getenv("KEPLOY_MODE").equals("test")) ? false : enabled;
    }

    @Advice.OnMethodExit
    public static void exitMethod(@Advice.Origin Method method) {
//        System.out.println("exit advice -> " + method);
    }
}
