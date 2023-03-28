package io.keploy.advice.ksql;

import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

/**
 * This class is used for intercepting method setEnabled of LiquibaseProperties class and to replace an argument value to
 * a custom value on entry of setEnabled method.
 */
public class SetEnabled_Advice {

    /**
     * This method gets executed before the setEnabled method of LiquibaseProperties class. According to the
     * Keploy mode that is present, The argument value will be changed
     */
    @Advice.OnMethodEnter
    public static void enterMethod(@Advice.Origin Method method, @Advice.Argument(value = 0, readOnly = false) boolean enabled) {
        enabled = (System.getenv("KEPLOY_MODE").equals("test")) ? false : enabled;
    }

    /**
     * This method gets executed after the setEnabled method of LiquibaseProperties class.This does nothing as we don't
     * want to change anything after the completion of the setEnabled method of LiquibaseProperties class.
     */
    @Advice.OnMethodExit
    public static void exitMethod(@Advice.Origin Method method) {
    }
}
