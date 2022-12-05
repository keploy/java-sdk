package io.keploy.advice.ksql;

import io.keploy.agent.KAgent;
import io.keploy.ksql.KDriver;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import oracle.jdbc.driver.OracleDriver;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

@Component
public class RegisterDriverAdvice_Interceptor {

    public static String execute(@SuperCall Callable<String> client, @Origin Method method) throws Exception {
//        System.out.println("Inside RegisterDriverAdvice_Interceptor -> " + method);
        String s = client.call();
//        System.out.println("determineDriverClassName returns : " + s);
        if (s != null && !s.equals("io.keploy.ksql.KDriver")) {
            KDriver.DriverName = s;

            switch (s) {
                case "org.postgresql.Driver":
                    KDriver.Dialect = "org.hibernate.dialect.PostgreSQLDialect";
                    break;
                case "com.mysql.cj.jdbc.Driver":
                    KDriver.Dialect = "org.hibernate.dialect.MySQLDialect";
                    break;
                case "oracle.jdbc.driver.OracleDriver":
                    KDriver.Dialect = "org.hibernate.dialect.OracleDialect";
                    break;
                default:
                    System.out.println("Dialect for driver: " + s + " is not supported yet");
            }
        }

//        mode.ModeType KEPLOY_MODE = mode.getMode();
//        System.out.println(KEPLOY_MODE);
//        if (KEPLOY_MODE.equals(mode.ModeType.MODE_OFF)){
//            return "";
//        }
        return "io.keploy.ksql.KDriver";
    }
}