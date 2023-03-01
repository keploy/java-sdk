package io.keploy.advice;

import io.keploy.httpClients.OkHttpInterceptor_Kotlin;
import net.bytebuddy.asm.Advice;
import okhttp3.OkHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;


/**
 * This class is used for intercepting constructor of OkHttpClient$Builder class and Adds a interceptor to its builder
 */
public class OkHttpAdvice_Kotlin {

    /**
     * This method gets executed before the constructor of OkHttpClient$Builder class.This does nothing as we don't
     * want to change anything before the invocation of OkHttpClient$Builder constructor.
     */
    @Advice.OnMethodEnter
    static void enterMethods(@Advice.Origin Constructor constructor) throws Exception {
    }

    /**
     * This method gets executed after constructor of OkHttpClient$Builder class and Adds a interceptor to its builder
     *
     * @param builder - OkHttpClient.Builder
     */
    @Advice.OnMethodExit
    static void exitMethods(@Advice.Origin Constructor constructor, @Advice.This OkHttpClient.Builder builder) {
        final Logger logger = LogManager.getLogger(OkHttpAdvice_Kotlin.class);

        logger.debug("inside OnMethodExitAdvice of OkHttpAdvice_Kotlin for constructor: {}", constructor);

        OkHttpInterceptor_Kotlin okHttpInterceptor = new OkHttpInterceptor_Kotlin();
        builder.addInterceptor(okHttpInterceptor);
    }
}
