package io.keploy.advice.ksql;

import net.bytebuddy.asm.Advice;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Component
public class RegisterDriverAdvice {

    @Advice.OnMethodEnter
    public static void enterMethod(@Advice.Origin Method method, @Advice.Argument(value = 0, readOnly = false) String driverClassName) {
        System.out.println("Entering method[" + method + "] with argument[" + driverClassName + "] from EnterAdvice");
//        mode.ModeType KEPLOY_MODE = mode.getMode();
//        System.out.println(KEPLOY_MODE);
//        if (KEPLOY_MODE.equals(mode.ModeType.MODE_OFF)){
//            return;
//        }
        driverClassName = "io.keploy.ksql.KDriver";
    }

    @Advice.OnMethodExit
    public static void exitMethod(@Advice.Origin Method method) {
        System.out.println("exit advice -> " + method);
    }
}
