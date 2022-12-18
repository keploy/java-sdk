package io.keploy.advice;

import io.keploy.httpClients.OkHttpInterceptor_Kotlin;
import net.bytebuddy.asm.Advice;
import okhttp3.OkHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;


public class OkHttpAdvice_Kotlin {


    @Advice.OnMethodEnter
    static void enterMethods(@Advice.Origin Constructor constructor) throws Exception {
    }

    @Advice.OnMethodExit
    static void exitMethods(@Advice.Origin Constructor constructor, @Advice.This OkHttpClient.Builder builder) {
        final Logger logger = LogManager.getLogger(OkHttpAdvice_Kotlin.class);

        logger.debug("inside OnMethodExitAdvice of OkHttpAdvice_Kotlin for constructor: {}", constructor);

        OkHttpInterceptor_Kotlin okHttpInterceptor = new OkHttpInterceptor_Kotlin();
        builder.addInterceptor(okHttpInterceptor);
    }
}
