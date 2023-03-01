package io.keploy.advice.ksql;

import io.keploy.ksql.KDriver;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

/**
 * This class is used for intercepting method setDriverClassName of DataSourceProperties class and replaces a argument value to
 * a custom value on entry of setDriverClassName method.
 */
public class RegisterDriverAdvice {

    /**
     * This method gets executed before the setDriverClassName method of DataSourceProperties class. According to the
     * driverClassName that is present, Dialect of KDriver is changed and driverClassName value is replaced with KDriver
     * path
     */
    @Advice.OnMethodEnter
    public static void enterMethod(@Advice.Origin Method method, @Advice.Argument(value = 0, readOnly = false) String driverClassName) {

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
        driverClassName = "io.keploy.ksql.KDriver";
    }

    /**
     * This method gets executed after the setDriverClassName method of DataSourceProperties class.This does nothing as we don't
     * want to change anything after the completion of the setDriverClassName method of DataSourceProperties class.
     */
    @Advice.OnMethodExit
    public static void exitMethod(@Advice.Origin Method method) {
    }
}
