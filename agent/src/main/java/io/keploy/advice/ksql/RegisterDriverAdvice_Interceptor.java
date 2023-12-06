package io.keploy.advice.ksql;

import io.keploy.ksql.KDriver;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.SuperCall;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;


/**
 * This class is used for intercepting method determineDriverClassName of DataSourceProperties class and returns a
 * custom value instead of value returned by the determineDriverClassName method .
 */
public class RegisterDriverAdvice_Interceptor {

    /**
     * This method will get called instead of determineDriverClassName
     * @param client - original method client
     * @param method - contains all the details regarding original method
     * @return - path to Driver class
     */
    public static String execute(@SuperCall Callable<String> client, @Origin Method method) throws Exception {

        // Getting actual response from original method
        String s = client.call();

        // Changing KDriver Dialect according to the response from original method
        if (s != null && !s.equals("io.keploy.ksql.KDriver")) {
            KDriver.DriverName = s;
            switch (s) {
                case "org.postgresql.Driver":
                    KDriver.Dialect = "org.hibernate.dialect.PostgreSQLDialect";
                    break;
                case "com.mysql.cj.jdbc.Driver":
                case "com.mysql.jdbc.Driver":
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
                    System.out.println("Dialect for driver: " + s + " is not supported yet");
            }
        }

        // returning wrapped Driver class path
        return "io.keploy.ksql.KDriver";
    }
}