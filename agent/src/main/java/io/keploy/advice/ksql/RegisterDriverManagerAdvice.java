package io.keploy.advice.ksql;

import io.keploy.ksql.KDriver;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;
import java.sql.Driver;
import java.sql.SQLException;

public class RegisterDriverManagerAdvice {
    @Advice.OnMethodEnter
    public static void enterMethod(@Advice.Origin Method method, @Advice.Argument(value = 0, readOnly = false) Driver driverName) {
        System.out.println("Inside register driver manager advice !!!");
        System.out.println("Entering method[" + method + "] with argument[" + driverName + "] from EnterAdvice");
        System.out.println("Here goes our Driver: " + driverName);
        driverName = new KDriver(driverName);
    }

    @Advice.OnMethodExit
    public static void exitMethod(@Advice.Origin Method method) {
        System.out.println("exit advice -> " + method);
    }
}






