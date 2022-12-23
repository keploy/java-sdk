package io.keploy.advice.ksql;

import io.keploy.ksql.KDriver;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

//@Component
public class RegisterDriverAdvice {

    @Advice.OnMethodEnter
    public static void enterMethod(@Advice.Origin Method method, @Advice.Argument(value = 0, readOnly = false) String driverClassName) {
//        System.out.println("Entering method[" + method + "] with argument[" + driverClassName + "] from EnterAdvice");

        if (driverClassName != null && !driverClassName.equals("io.keploy.ksql.KDriver")) {
            KDriver.DriverName = driverClassName;
            switch (driverClassName) {
                case "org.postgresql.Driver":
                    KDriver.Dialect = "org.hibernate.dialect.PostgreSQLDialect";
                    break;
                case "com.mysql.cj.jdbc.Driver":
                    KDriver.Dialect = "org.hibernate.dialect.MySQLDialect";
                    break;
                case "oracle.jdbc.driver.OracleDriver":
                case "oracle.jdbc.OracleDriver":
                    KDriver.Dialect = "org.hibernate.dialect.Oracle10gDialect";
                    break;
                case "org.h2.Driver":
                    KDriver.Dialect = "org.hibernate.dialect.H2Dialect";
                    break;
                default:
                    System.out.println("Dialect for driver: " + driverClassName + " is not supported yet");
            }
        }
//        mode.ModeType KEPLOY_MODE = mode.getMode();
//        System.out.println(KEPLOY_MODE);
//        if (KEPLOY_MODE.equals(mode.ModeType.MODE_OFF)){
//            return;
//        }
        driverClassName = "io.keploy.ksql.KDriver";
    }

    @Advice.OnMethodExit
    public static void exitMethod(@Advice.Origin Method method) {
//        System.out.println("exit advice -> " + method);
    }
}
