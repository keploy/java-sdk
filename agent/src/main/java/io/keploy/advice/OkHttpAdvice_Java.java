package io.keploy.advice;

import com.squareup.okhttp.OkHttpClient;
import io.keploy.httpClients.OkHttpInterceptor_Java;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Constructor;

public class OkHttpAdvice_Java {

    @Advice.OnMethodEnter
    static void enterMethods(@Advice.Origin Constructor constructor) {
        System.out.println("Inside Enter Method of advice: " + constructor);
    }

    @Advice.OnMethodExit
    static void exitMethods(@Advice.Origin Constructor constructor, @Advice.This OkHttpClient okHttpClient){
        System.out.println("Inside Exit Method of advice: "+constructor);

        okHttpClient.interceptors().add(new OkHttpInterceptor_Java());
    }



}
