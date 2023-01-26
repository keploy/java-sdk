package io.keploy.agent;

import io.keploy.service.GrpcService;
import io.keploy.service.mock.MockLib;
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
import java.lang.reflect.Field;
import java.sql.DatabaseMetaData;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;


import static net.bytebuddy.matcher.ElementMatchers.*;

public class KAgent {

    private static final Logger logger = LogManager.getLogger(KAgent.class);

    public static void premain(String arg, Instrumentation instrumentation) {

        logger.debug("inside premain method");
        logger.debug("KeployMode:{}", System.getenv("KEPLOY_MODE"));
        if (System.getenv("KEPLOY_MODE") != null && Objects.equals(System.getenv("KEPLOY_MODE"), "off")) {
            return;
        }

        if (System.getenv("KEPLOY_MODE") == null) {
            return;
        }

//        if (System.getenv("MOCK_LIB") != null && Objects.equals(System.getenv("MOCK_LIB"), "true")) {
//            new MockLib(); // this will initialize the mock lib
//        }


        String apacheClient = "org.apache.http.impl.client.CloseableHttpClient";
        String asyncApacheClient = "org.apache.http.impl.nio.client.CloseableHttpAsyncClient";
        String okhttpClientBuilder = "okhttp3.OkHttpClient$Builder";
        String okhttp_java = "com.squareup.okhttp.OkHttpClient";
        String internalhttpasyncClient = "org.apache.http.impl.nio.client.InternalHttpAsyncClient";
        String okHttpPendingResult = "com.google.maps.internal.OkHttpPendingResult";


        new AgentBuilder.Default(new ByteBuddy().with(TypeValidation.DISABLED))
//                .ignore(none())
                // to see the full logs in case of debugging, comment out the below line.
//                .with(AgentBuilder.Listener.StreamWriting.toSystemOut())
//                .with(AgentBuilder.Listener.StreamWriting.toSystemOut().withErrorsOnly())

                //for okhttp client interceptor upto version 2.7.5
                .type(named(okhttp_java))
                .transform(((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {
                    logger.debug("inside OkHttpInterceptor_Java transformer");
                    return builder
                            .constructor(isDefaultConstructor()).intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.OkHttpAdvice_Java").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                }))
                //for okhttp client interceptor for version 3.0+
                .type(named(okhttpClientBuilder))
                .transform(((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {
                    logger.debug("inside OkHttpInterceptor_Kotlin transformer");
                    return builder.constructor(isDefaultConstructor()).intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.OkHttpAdvice_Kotlin").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                }))
//                for apache client interceptor
                .type(named(apacheClient))
                .transform(((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {
                    logger.debug("inside ApacheInterceptor transformer");
                    try {
                        String apacheInterceptor = "io.keploy.httpClients.ApacheInterceptor";

                        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
                        new ClassFileLocator.Compound(ClassFileLocator.ForClassLoader.of(contextClassLoader),
                                ClassFileLocator.ForClassLoader.ofSystemLoader());
                        TypePool.Resolution resolution = TypePool.Default.of(ClassFileLocator.ForClassLoader.of(contextClassLoader)).describe(apacheInterceptor);

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
                                .intercept(MethodDelegation.to(TypePool.Default.ofSystemLoader().describe(apacheInterceptor).resolve()));
                    } catch (Exception e) {
                        logger.error("unable to intercept apache client");
                        e.printStackTrace();
                        return builder;
                    }
                }))
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
                //for google-maps-services
                .type(named(okHttpPendingResult))
                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {
                    logger.debug("inside GoogleMapsInterceptor transformer");
                    return builder
                            .method(named("await")).intercept(MethodDelegation.to(TypePool.Default.ofSystemLoader().describe("io.keploy.googleMaps.GoogleMapsInterceptor").resolve()))
                            .method(named("parseResponse")).intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.CustomGoogleResponseAdvice").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                })
                // for sql mocking
                .type(named("org.springframework.boot.autoconfigure.jdbc.DataSourceProperties"))
                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {
                    logger.debug("Inside RegisterDriverAdvice1 Transformer");
                    return builder.method(named("setDriverClassName"))
                            .intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.ksql.RegisterDriverAdvice").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                })
                .type(named("org.springframework.boot.autoconfigure.jdbc.DataSourceProperties"))
                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {
                    logger.debug("Inside RegisterDriverAdvice2 Transformer");
                    return builder.method(named("determineDriverClassName"))
                            .intercept(MethodDelegation.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.ksql.RegisterDriverAdvice_Interceptor").resolve()));
                })
                .type(named("org.springframework.boot.autoconfigure.orm.jpa.HibernateProperties"))
                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {
                    logger.debug("Inside HibernateProperties Transformer for setDdlAuto");
                    return builder.method(named("setDdlAuto").and(takesArgument(0, String.class))).intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.ksql.SetDdlAuto_Advice").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                })
                .type(named("org.springframework.boot.autoconfigure.liquibase.LiquibaseProperties"))
                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {
                    logger.debug("Inside LiquibaseProperties Transformer for setEnabled");
                    return builder.method(named("setEnabled").and(takesArgument(0, Boolean.class))).intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.ksql.SetEnabled_Advice").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                })
                .type(named("org.springframework.boot.autoconfigure.orm.jpa.JpaProperties"))
                .transform(((builder, typeDescription, classLoader, module, protectionDomain) -> {
                    logger.debug("Inside RegisterDialect Transformer");
                    return builder.constructor(isDefaultConstructor()).intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.ksql.RegisterDialect").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                }))
                .type(named("org.springframework.boot.actuate.health.Health$Builder"))
                .transform(((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {
                    logger.debug("Inside HealthEndpoint Transformer");
                    return builder.method(named("withDetail")).intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.ksql.HealthCheckInterceptor").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                }))
                .type(named("com.mchange.v2.c3p0.impl.NewProxyDatabaseMetaData"))
                .transform(((builder, typeDescription, classLoader, module, protectionDomain) -> {
                    logger.debug("Inside DatabaseMetaData transformer");
                    return builder.constructor(takesArgument(0, DatabaseMetaData.class)).intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.ksql.DataBaseMetaData_Advice").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                }))
                .installOn(instrumentation);
    }

    private static boolean isJUnitTest() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            if (element.getClassName().startsWith("org.junit.")) {
                return true;
            }
        }
        return false;
    }

    protected static void setEnv(Map<String, String> newenv) throws Exception {
        try {
            Class<?> processEnvironmentClass = Class.forName("java.lang.ProcessEnvironment");
            Field theEnvironmentField = processEnvironmentClass.getDeclaredField("theEnvironment");
            theEnvironmentField.setAccessible(true);
            Map<String, String> env = (Map<String, String>) theEnvironmentField.get(null);
            env.putAll(newenv);
            Field theCaseInsensitiveEnvironmentField = processEnvironmentClass.getDeclaredField("theCaseInsensitiveEnvironment");
            theCaseInsensitiveEnvironmentField.setAccessible(true);
            Map<String, String> cienv = (Map<String, String>) theCaseInsensitiveEnvironmentField.get(null);
            cienv.putAll(newenv);
        } catch (NoSuchFieldException e) {
            Class[] classes = Collections.class.getDeclaredClasses();
            Map<String, String> env = System.getenv();
            for (Class cl : classes) {
                if ("java.util.Collections$UnmodifiableMap".equals(cl.getName())) {
                    Field field = cl.getDeclaredField("m");
                    field.setAccessible(true);
                    Object obj = field.get(env);
                    Map<String, String> map = (Map<String, String>) obj;
                    map.clear();
                    map.putAll(newenv);
                }
            }
        }
    }

}