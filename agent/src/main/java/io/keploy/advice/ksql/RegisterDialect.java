package io.keploy.advice.ksql;


import io.keploy.ksql.KDriver;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Constructor;
import java.util.Map;

/**
 * This class is used for intercepting constructor of JpaProperties class and to modify the value of a field value of
 * the class on exit of that constructor method.
 */
public class RegisterDialect {

    /**
     * This method gets executed before the constructor of JpaProperties class.This does nothing as we don't
     * want to change anything before the invocation of JpaProperties constructor.
     */
    @Advice.OnMethodEnter
    static void enterMethods(@Advice.Origin Constructor constructor) throws Exception {
    }

    /**
     * This method gets executed after constructor of JpaProperties class and modifies the value of the field - properties.
     *
     * @param properties - a field in JpaProperties class
     */
    @Advice.OnMethodExit
    static void exitMethods(@Advice.Origin Constructor constructor, @Advice.FieldValue(readOnly = false, value = "properties") Map<String, String> properties) throws Exception {
        properties.put("hibernate.dialect", KDriver.Dialect);
    }
}
