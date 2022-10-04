package io.keploy.advice;

import io.keploy.httpClients.OkHttpInterceptor_Kotlin;
import net.bytebuddy.asm.Advice;
import okhttp3.OkHttpClient;

import java.lang.reflect.Constructor;


public class OkHttpAdvice_Kotlin {


    @Advice.OnMethodEnter
    static void enterMethods(@Advice.Origin Constructor constructor) throws Exception {
    }

    @Advice.OnMethodExit
    static void exitMethods(@Advice.Origin Constructor constructor, @Advice.This OkHttpClient.Builder builder) throws Exception {

        OkHttpInterceptor_Kotlin okHttpInterceptor = new OkHttpInterceptor_Kotlin();
//        int ct = AdviceCounter.icount.incrementAndGet();
//        System.out.println("counter: " + ct);
//        if (ct == 1) {
        builder.addInterceptor(okHttpInterceptor);
//        }
//        AdviceCounter.icount.incrementAndGet();
    }
}
