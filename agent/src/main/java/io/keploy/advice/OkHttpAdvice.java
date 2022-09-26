package io.keploy.advice;

import io.keploy.httpClients.OkHttpInterceptor;
import net.bytebuddy.asm.Advice;
import okhttp3.OkHttpClient;

import java.lang.reflect.Constructor;

public class OkHttpAdvice {


    @Advice.OnMethodEnter
    static void enterMethods(@Advice.Origin Constructor constructor) throws Exception {
        System.out.println("Inside OnMethodEnter advice-> " + constructor);
    }

    @Advice.OnMethodExit
    static void exitMethods(@Advice.Origin Constructor constructor, @Advice.This OkHttpClient.Builder builder) throws Exception {
        System.out.println("Inside OnMethodExit advice -> " + constructor);
        OkHttpInterceptor okHttpInterceptor = new OkHttpInterceptor();
//        int ct = AdviceCounter.icount.incrementAndGet();
//        System.out.println("counter: " + ct);
//        if (ct == 1) {
        builder.addInterceptor(okHttpInterceptor);
//        }
//        AdviceCounter.icount.incrementAndGet();
    }
}
