package io.keploy.agent;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import org.apache.http.HttpResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.instrument.Instrumentation;
import java.time.Instant;

import static net.bytebuddy.matcher.ElementMatchers.*;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

public class KAgent {

    private static final Logger logger = LogManager.getLogger(KAgent.class);

    public static void premain(String arg, Instrumentation instrumentation) {

        logger.debug("inside premain method");

        String apacheClient = "org.apache.http.impl.client.CloseableHttpClient";
        String okhttpClientBuilder = "okhttp3.OkHttpClient$Builder";
        String okhttp_java = "com.squareup.okhttp.OkHttpClient";


        new AgentBuilder.Default(new ByteBuddy().with(TypeValidation.DISABLED))
//                .with(AgentBuilder.Listener.StreamWriting.toSystemOut())

                //for okhttp client interceptor upto version 2.7.5
                .type(named(okhttp_java))
                .transform(((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {
                    logger.debug("inside OkHttpTransformer_Java");
                    return builder
                            .constructor(isDefaultConstructor()).intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.OkHttpAdvice_Java").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                }))
                //for okhttp client interceptor for version 3.0+
                .type(named(okhttpClientBuilder))
                .transform(((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {
                    logger.debug("inside OkHttpTransformer_Kotlin");
                    return builder.constructor(isDefaultConstructor()).intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.OkHttpAdvice_Kotlin").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                }))
                //for apache client interceptor
                .type(named(apacheClient))
                .transform(((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {
                    logger.debug("inside apacheInterceptor");
                    try {
                        String interceptor = "io.keploy.httpClients.ApacheInterceptor";
                        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
                        new ClassFileLocator.Compound(ClassFileLocator.ForClassLoader.of(contextClassLoader),
                                ClassFileLocator.ForClassLoader.ofSystemLoader());
                        TypePool.Resolution resolution = TypePool.Default.of(ClassFileLocator.ForClassLoader.of(contextClassLoader)).describe(interceptor);

                        String request = "org.apache.http.client.methods.HttpUriRequest";
                        String context = "org.apache.http.protocol.HttpContext";
                        String host = "org.apache.http.HttpHost";

                        String response = "org.apache.http.client.methods.CloseableHttpResponse";

                        ElementMatcher.Junction<MethodDescription> md1 = takesArgument(0, named(request)).and(takesArgument(1, named(context)));
                        ElementMatcher.Junction<MethodDescription> md2 = takesArgument(0, named(host)).and(takesArgument(1, named(request)));
                        ElementMatcher.Junction<MethodDescription> md3 = takesArgument(0, named(host)).and(takesArgument(1, named(request))).and(takesArgument(2, named(context)));

                        return builder.method(named("execute").and(md1.or(md2).or(md3))
                                        .and(returns(isSubTypeOf(HttpResponse.class))))
//                                .intercept(MethodDelegation.to(resolution.resolve())); // contains spring class loader also.
                                .intercept(MethodDelegation.to(TypePool.Default.ofSystemLoader().describe(interceptor).resolve()));
                    } catch (Exception e) {
                        logger.error("unable to intercept apache client");
                        e.printStackTrace();
                        return builder;
                    }
                }))
                .installOn(instrumentation);
    }
}
