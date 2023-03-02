package io.keploy.advice.ksql;

import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

/**
 * This class is used for intercepting method withDetail of Health$Builder class and to replace an argument value to
 * a custom value on entry of that withDetail method.
 */
public class HealthCheckInterceptor {

    /**
     * This method gets executed before the withDetail method of Health$Builder class.In test mode of keploy this method
     * will throw error as we don't get health check from database. so we mock the health check response and feed it to
     * the method. Whenever the value of the health check argument is NULL we replace it with "HI" so that the method
     * never fails
     */
    @Advice.OnMethodEnter
    public static void enterMethod(@Advice.Origin Method method, @Advice.Argument(value = 0, readOnly = false) String key, @Advice.Argument(value = 1, readOnly = false) Object value) {
        if (value == null) {
            value = "HI";
        }
    }

    /**
     * This method gets executed after the withDetail method of Health$Builder class.This does nothing as we don't
     * want to change anything after the completion of withDetail method of Health$Builder class.
     */
    @Advice.OnMethodExit
    public static void exitMethod(@Advice.Origin Method method) {
    }
}
