package io.keploy.advice.ksql;

import io.keploy.ksql.KDatabaseMetaData;
import net.bytebuddy.asm.Advice;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
import java.sql.DatabaseMetaData;


/**
 * This class is used for intercepting constructor of NewProxyDatabaseMetaData class and to replace value of a field to
 * a custom value i.e. KDatabaseMetaData on exit of that constructor method.
 */
public class DataBaseMetaData_Advice {

    /**
     * This method gets executed before the constructor of NewProxyDatabaseMetaData class.This does nothing as we don't
     * want to change anything before the invocation of NewProxyDatabaseMetaData constructor.
     */
    @Advice.OnMethodEnter
    static void enterMethods(@Advice.Origin Constructor constructor) throws Exception {
    }

    /**
     * This method gets executed after constructor of NewProxyDatabaseMetaData class and replaces the value of field metaData
     * to KDatabaseMetaData .
     *
     * @param metaData - a field in NewProxyDatabaseMetaData class
     */
    @Advice.OnMethodExit
    static void exitMethods(@Advice.Origin Constructor constructor, @Advice.FieldValue(value = "inner", readOnly = false) DatabaseMetaData metaData) {
//        inner is the variable that is intercepted and replaced with metaData
        final Logger logger = LogManager.getLogger(DataBaseMetaData_Advice.class);
        logger.debug("inside OnMethodExitAdvice for constructor: {}", constructor);
        metaData = new KDatabaseMetaData();
    }
}
