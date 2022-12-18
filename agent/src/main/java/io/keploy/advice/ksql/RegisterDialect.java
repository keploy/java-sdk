package io.keploy.advice.ksql;


import io.keploy.ksql.KDriver;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Constructor;
import java.util.Map;

public class RegisterDialect {

    @Advice.OnMethodEnter
    static void enterMethods(@Advice.Origin Constructor constructor) throws Exception {
//        System.out.println("Inside RegisterDialect Enter Advice: " + constructor);
    }

    @Advice.OnMethodExit
    static void exitMethods(@Advice.Origin Constructor constructor, @Advice.FieldValue(readOnly = false, value = "properties") Map<String, String> properties) throws Exception {
//        System.out.println("Inside RegisterDialect Exit Advice: " + constructor);
        properties.put("hibernate.dialect", KDriver.Dialect);
    }
}
