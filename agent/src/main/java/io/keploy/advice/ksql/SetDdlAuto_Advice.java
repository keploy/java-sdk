package io.keploy.advice.ksql;

import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

/**
 * This class is used for intercepting method setDdlAuto of HibernateProperties class and to replace an argument value to
 * a custom value on entry of setDdlAuto method.
 */
public class SetDdlAuto_Advice {

    /**
     * This method gets executed before the setDdlAuto method of HibernateProperties class. According to the
     * Keploy mode that is present, The argument value will be changed.
     */
    @Advice.OnMethodEnter
    public static void enterMethod(@Advice.Origin Method method, @Advice.Argument(value = 0, readOnly = false) String ddlAuto) {
        ddlAuto = (System.getenv("KEPLOY_MODE").equals("test")) ? "none" : ddlAuto;
    }

    /**
     * This method gets executed after the setDdlAuto method of HibernateProperties class.This does nothing as we don't
     * want to change anything after the completion of the setDdlAuto method of HibernateProperties class.
     */
    @Advice.OnMethodExit
    public static void exitMethod(@Advice.Origin Method method) {
    }
}
