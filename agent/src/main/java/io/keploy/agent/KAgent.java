package io.keploy.agent;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.pool.TypePool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.instrument.Instrumentation;
import java.util.Objects;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class KAgent {

    private static final Logger logger = LogManager.getLogger(KAgent.class);
//    public static String driverName = "";

    public static void premain(String arg, Instrumentation instrumentation) {

        logger.debug("inside premain method");

        System.out.println(System.getenv("KEPLOY_MODE"));
//        setMode();
        if (Objects.equals(System.getenv("KEPLOY_MODE"), "off")) {
            return;
        }

        String apacheClient = "org.apache.http.impl.client.CloseableHttpClient";
        String asyncApacheClient = "org.apache.http.impl.nio.client.CloseableHttpAsyncClient";
        String okhttpClientBuilder = "okhttp3.OkHttpClient$Builder";
        String okhttp_java = "com.squareup.okhttp.OkHttpClient";
        String internalhttpasyncClient = "org.apache.http.impl.nio.client.InternalHttpAsyncClient";


        new AgentBuilder.Default(new ByteBuddy().with(TypeValidation.DISABLED))
                .with(new AgentBuilder.InitializationStrategy.SelfInjection.Eager())
//                .with(AgentBuilder.Listener.StreamWriting.toSystemOut())

                //for okhttp client interceptor upto version 2.7.5
                .type(named(okhttp_java))
                .transform(((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {
                    logger.debug("inside OkHttpTransformer_Java transformer");
                    return builder
                            .constructor(isDefaultConstructor()).intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.OkHttpAdvice_Java").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                }))
                //for okhttp client interceptor for version 3.0+
                .type(named(okhttpClientBuilder))
                .transform(((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {
                    logger.debug("inside OkHttpTransformer_Kotlin transformer");
                    return builder.constructor(isDefaultConstructor()).intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.OkHttpAdvice_Kotlin").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                }))
                //for apache client interceptor
//                .type(named(apacheClient))
//                .transform(((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {
//                    logger.debug("inside apacheInterceptor transformer");
//                    System.out.println("inside apacheInterceptor transformer");
//                    try {
//                        String apacheInterceptor = "io.keploy.httpClients.ApacheInterceptor";
//                        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
//                        new ClassFileLocator.Compound(ClassFileLocator.ForClassLoader.of(contextClassLoader),
//                                ClassFileLocator.ForClassLoader.ofSystemLoader());
//                        TypePool.Resolution resolution = TypePool.Default.of(ClassFileLocator.ForClassLoader.of(contextClassLoader)).describe(apacheInterceptor);
//
//                        String request = "org.apache.http.client.methods.HttpUriRequest";
//                        String context = "org.apache.http.protocol.HttpContext";
//                        String host = "org.apache.http.HttpHost";
//
//                        String response = "org.apache.http.client.methods.CloseableHttpResponse";
//
//                        ElementMatcher.Junction<MethodDescription> md1 = takesArgument(0, named(request)).and(takesArgument(1, named(context)));
//                        ElementMatcher.Junction<MethodDescription> md2 = takesArgument(0, named(host)).and(takesArgument(1, named(request)));
//                        ElementMatcher.Junction<MethodDescription> md3 = takesArgument(0, named(host)).and(takesArgument(1, named(request))).and(takesArgument(2, named(context)));
//
//                        return builder.method(named("execute").and(md1.or(md2).or(md3))
//                                        .and(returns(isSubTypeOf(HttpResponse.class))))
////                                .intercept(MethodDelegation.to(resolution.resolve())); // contains spring class loader also.
//                                .intercept(MethodDelegation.to(TypePool.Default.ofSystemLoader().describe(apacheInterceptor).resolve()));
//                    } catch (Exception e) {
//                        logger.error("unable to intercept apache client");
//                        e.printStackTrace();
//                        return builder;
//                    }
//                }))
                //for apache async-client interceptor
//                .type(named(asyncApacheClient))
//                .transform(new AgentBuilder.Transformer() {
//                    @Override
//                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription, ClassLoader classLoader, JavaModule javaModule, ProtectionDomain protectionDomain) {
//                        logger.debug("inside Async-ApacheInterceptor");
//                        System.out.println("inside Async-ApacheInterceptor");
//
//                        String context = "org.apache.http.protocol.HttpContext";
//                        String host = "org.apache.http.HttpHost";
//                        String response = "org.apache.http.HttpResponse";
//
//                        String asyncApacheInterceptor = "io.keploy.httpClients.AsyncApacheInterceptor";
//
//                        ElementMatcher.Junction<MethodDescription> futureResponse = returnsGeneric(type -> type.asErasure().represents(Future.class)
//                                && type.getSort().isParameterized()
//                                && type.getTypeArguments().get(0).represents(HttpResponse.class));
//
//
//                        ElementMatcher.Junction<MethodDescription> args = takesArgument(0, named(host)).and(takesArgument(1, isSubTypeOf(HttpRequest.class))).and(takesArgument(2, named(context))).and(takesGenericArgument(3, type ->
//                                type.asErasure().represents(FutureCallback.class)
//                                        && type.getSort().isParameterized()
//                                        && type.getTypeArguments().get(0).represents(HttpResponse.class)
//                        ));
//
//                        return builder.method(named("execute").and(args)
//                                        .and(futureResponse))
//                                .intercept(MethodDelegation.to(TypePool.Default.ofSystemLoader().describe(asyncApacheInterceptor).resolve()));
//                    }
//                })
                //for elastic search -> apache InternalHttpAsyncClient
//                .type(named(internalhttpasyncClient))
//                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {
//                    System.out.println("Inside InternalHttpAsyncClient transformer");
//                    logger.debug("inside InternalHttpAsyncClient transformer");
//
//                    System.out.println("Methods inside internalhttpAsync: " + typeDescription.getDeclaredMethods());
//
//                    String requestProducer = "org.apache.http.nio.protocol.HttpAsyncRequestProducer";
//                    String context = "org.apache.http.protocol.HttpContext";
//                    String internalAsyncInterceptor = "io.keploy.httpClients.ElasticSearchInterceptor";
//
//
//                    ElementMatcher.Junction<MethodDescription> args = takesArgument(0, named(requestProducer))
//                            .and(takesGenericArgument(1, type -> type.asErasure().represents(HttpAsyncResponseConsumer.class)))
//                            .and(takesArgument(2, named(context)))
//                            .and(takesGenericArgument(3, type -> type.asErasure().represents(FutureCallback.class)));
//
//                    return builder.method(named("execute").and(returnsGeneric(type -> type.asErasure().represents(Future.class))).and(args))
//                            .intercept(MethodDelegation.to(TypePool.Default.ofSystemLoader().describe(internalAsyncInterceptor).resolve()));
//
//                })
                // FOR KSQL INTEGRATION ....

                .type(named("org.springframework.boot.autoconfigure.jdbc.DataSourceProperties"))
                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {

                    System.out.println("Inside RegisterDriverAdvice1 Transformer");
                    return builder.method(named("setDriverClassName"))
                            .intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.ksql.RegisterDriverAdvice").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                })
                .type(named("org.springframework.boot.autoconfigure.jdbc.DataSourceProperties"))
                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {

                    System.out.println("Inside RegisterDriverAdvice2 Transformer");
                    return builder.method(named("determineDriverClassName"))
                            .intercept(MethodDelegation.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.ksql.RegisterDriverAdvice_Interceptor").resolve()));
                })
                .type(named("org.springframework.boot.autoconfigure.orm.jpa.HibernateProperties"))
                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {
                    System.out.println("Inside HibernateProperties Transformer");
                    return builder.method(named("setDdlAuto").and(takesArgument(0, String.class))).intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.ksql.SetDdlAuto_Advice").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                })
                .type(named("org.springframework.boot.autoconfigure.orm.jpa.JpaProperties"))
                .transform(((builder, typeDescription, classLoader, module, protectionDomain) -> {
                    System.out.println("Inside RegisterDialect Transformer");
                    return builder.constructor(isDefaultConstructor()).intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.ksql.RegisterDialect").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                }))
                .installOn(instrumentation);
    }
}