package io.keploy.advice.ksql;


import io.keploy.ksql.KDatabaseMetaData;
import net.bytebuddy.asm.Advice;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
import java.sql.DatabaseMetaData;

public class DataBaseMetaData_Advice {

    @Advice.OnMethodEnter
    static void enterMethods(@Advice.Origin Constructor constructor) throws Exception {
    }

    @Advice.OnMethodExit
    static void exitMethods(@Advice.Origin Constructor constructor, @Advice.FieldValue(value = "inner", readOnly = false) DatabaseMetaData metaData) {
        final Logger logger = LogManager.getLogger(DataBaseMetaData_Advice.class);
        logger.debug("inside OnMethodExitAdvice for constructor: {}", constructor);
        metaData = new KDatabaseMetaData();
    }
}
