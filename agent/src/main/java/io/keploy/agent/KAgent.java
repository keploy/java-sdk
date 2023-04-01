package io.keploy.agent;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.signature.SignatureReader;
import net.bytebuddy.jar.asm.signature.SignatureVisitor;
import net.bytebuddy.jar.asm.signature.SignatureWriter;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.OpenedClassReader;
import org.apache.http.HttpResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.bytebuddy.dynamic.DynamicType.Builder;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.sql.DatabaseMetaData;
import java.util.*;


import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * This KAgent is a specially crafted Class and entry point for instrumentation. It utilizes the Instrumentation API that
 * JVM provides to alter existing byte-code that is loaded in a JVM. This runs before main class. so whatever changes
 * that are needed for running keploy in different modes are done here.
 */
public class KAgent {

    private static final Logger logger = LogManager.getLogger(KAgent.class);

    /**
     * premain is the method which runs before main. This will statically load the agent using -javaagent parameter at
     * JVM startup
     *
     * @param arg             - It is a String that can be used to pass arguments to the agent. These arguments can be used to
     *                        configure the behavior of the agent.
     * @param instrumentation - It is an instance of the Instrumentation class, which provides a mechanism for the agent
     *                        to inspect and modify the byte code of classes that are loaded into the JVM.
     */
    public static void premain(String arg, Instrumentation instrumentation) {

        logger.debug("inside premain method");
        logger.debug("KeployMode:{}", System.getenv("KEPLOY_MODE"));

        if (System.getenv("KEPLOY_MODE") == null || Objects.equals(System.getenv("KEPLOY_MODE"), "off")) {
            return;
        }

        String apacheClient = "org.apache.http.impl.client.CloseableHttpClient";
        String okhttpClientBuilder = "okhttp3.OkHttpClient$Builder";
        String okhttp_java = "com.squareup.okhttp.OkHttpClient";
        String okHttpPendingResult = "com.google.maps.internal.OkHttpPendingResult";
        String jdbc = "org.springframework.boot.autoconfigure.jdbc.DataSourceProperties";
        String jpaHibernate= "org.springframework.boot.autoconfigure.orm.jpa.HibernateProperties";
        String liquibase = "org.springframework.boot.autoconfigure.liquibase.LiquibaseProperties";
        String jpaProperties ="org.springframework.boot.autoconfigure.orm.jpa.JpaProperties";
        String health = "org.springframework.boot.actuate.health.Health$Builder";
        String proxyDB = "com.mchange.v2.c3p0.impl.NewProxyDatabaseMetaData";
        String redisJedisPool = "redis.clients.jedis.JedisPool";
        String redisJedisBinary = "redis.clients.jedis.BinaryClient";
        String jWString = "org.springframework.security.oauth2.provider.token.store.JwtAccessTokenConverter";
        // String mongo= "org.springframework.boot.autoconfigure.data.mongo.MongoDataProperties"; //TODO: add mongo support

        // String asyncApacheClient = "org.apache.http.impl.nio.client.CloseableHttpAsyncClient";
        // String internalhttpasyncClient = "org.apache.http.impl.nio.client.InternalHttpAsyncClient";

        // Using the AgentBuilder class we can define a Java agent.
        new AgentBuilder.Default(new ByteBuddy().with(TypeValidation.DISABLED))

                // to see the full logs in case of debugging, comment out the below line.
                // .with(AgentBuilder.Listener.StreamWriting.toSystemOut())
                // .with(AgentBuilder.Listener.StreamWriting.toSystemOut().withErrorsOnly())
                

                /* Transformer for JWT. This transformer intercepts and runs JwtAdvice Advice before and after the execution of 
                 JwtAccessTokenConverter class constructor. JwtAdvice Advice will modify things according to the Keploy mode and allows to record tests,
                 mocks and test them.
                */
                .type(named(jWString))
                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {
                    if (System.getenv("SKIP_MOCK_JWT") == null ||  !Boolean.parseBoolean(System.getenv("SKIP_MOCK_JWT")) )
                    {
                    return builder.constructor(isDefaultConstructor()).intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.JwtAdvice").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));                            
                    // .on(named("decode")));
                    }
                    return builder;
                })
                

                /*
                  Transformer for okhttp client up to version 2.7.5. This transformer intercepts and runs
                  OkHttpAdvice_Java Advice before and after the execution of OkHttpClient class constructor.
                  OkHttpAdvice_Java Advice will modify things according to the Keploy mode and allows to record tests,
                  mocks and test them.
                 */
                .type(named(okhttp_java))
                .transform(((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {
                    logger.debug("inside OkHttpInterceptor_Java transformer");

                    // if the service (for e.g.: OKHTTP) is not set or set to true, then mock it
                    if (System.getenv("SKIP_MOCK_OKHTTP") == null ||  !Boolean.parseBoolean(System.getenv("SKIP_MOCK_OKHTTP")) )
                    {
                        logger.debug("mocking OKHTTP");
                        return builder
                                .constructor(isDefaultConstructor()).intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.OkHttpAdvice_Java").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                    }
                    logger.debug("skip mocking OKHTTP");
                    return builder;
                }))

                /*
                  Transformer for okhttp client for version 3.0+. This transformer intercepts and runs
                  OkHttpAdvice_Kotlin Advice before and after the execution of OkHttpClient$Builder class constructor.
                  OkHttpAdvice_Kotlin Advice will modify things according to the Keploy mode and allows to record tests,
                  mocks and test them.
                 */
                .type(named(okhttpClientBuilder))
                .transform(((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {

                    logger.debug("inside OkHttpInterceptor_Kotlin transformer");

                    if (System.getenv("SKIP_MOCK_OKHTTP") == null ||  !Boolean.parseBoolean(System.getenv("SKIP_MOCK_OKHTTP")) )
                    {
                        logger.debug("mocking OKHTTP");
                        return builder.constructor(isDefaultConstructor())
                            .intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.OkHttpAdvice_Kotlin").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                    }
                    logger.debug("skip mocking OKHTTP");
                    return builder;
                }))

                /*
                  Transformer for apache client. This transformer runs methods of ApacheInterceptor instead of method
                  execute of CloseableHttpClient class. ApacheInterceptor methods modify things according to the Keploy
                  mode and allows to record tests, mocks and test them.
                 */
                .type(named(apacheClient))
                .transform(((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {

                    logger.debug("inside ApacheInterceptor transformer");

                    if (System.getenv("SKIP_MOCK_APACHE") == null || !Boolean.parseBoolean(System.getenv("SKIP_MOCK_APACHE")) )
                    {
                        logger.debug("mocking APACHE");
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
                    }
                    logger.debug("skip mocking APACHE");
                    return builder;
                }))

                /*
                  Transformer for google-maps-services.This transformer intercepts and runs CustomGoogleResponseAdvice
                  before parseResponse method of OkHttpPendingResult class and runs GoogleMapsInterceptor methods
                  instead of await method of OkHttpPendingResult. These will modify things according to the Keploy mode
                  and allows to record tests, mocks and test them.
                */
                .type(named(okHttpPendingResult))
                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {

                    logger.debug("inside GoogleMapsInterceptor transformer");

                    if (System.getenv("SKIP_MOCK_GOOGLE_MAPS") == null || !Boolean.parseBoolean(System.getenv("SKIP_MOCK_GOOGLE_MAPS")) )
                    {
                        logger.debug("mocking google maps");
                        return builder
                                .method(named("await")).intercept(MethodDelegation.to(TypePool.Default.ofSystemLoader().describe("io.keploy.googleMaps.GoogleMapsInterceptor").resolve()))
                                .method(named("parseResponse")).intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.CustomGoogleResponseAdvice").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                    }
                    logger.debug("skip mocking google maps");
                    return builder;
                })

                // Following Transformers are for sql mocking.
                .type(named(jdbc))
                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {

                    logger.debug("Inside RegisterDriverAdvice1 Transformer");

                    if (System.getenv("SKIP_MOCK_SQL") == null || !Boolean.parseBoolean(System.getenv("SKIP_MOCK_SQL")) )
                    {
                        logger.debug("mocking sql RegisterDriverAdvice1");
                        return builder.method(named("setDriverClassName"))
                                .intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.ksql.RegisterDriverAdvice").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                    }
                    logger.debug("skip mocking sql");
                    return builder;
                })
                .type(named(jdbc))
                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {

                    logger.debug("Inside RegisterDriverAdvice2 Transformer");

                    if (System.getenv("SKIP_MOCK_SQL") == null || !Boolean.parseBoolean(System.getenv("SKIP_MOCK_SQL")) )
                    {
                        logger.debug("mocking sql RegisterDriverAdvice2");
                        return builder.method(named("determineDriverClassName"))
                                .intercept(MethodDelegation.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.ksql.RegisterDriverAdvice_Interceptor").resolve()));
                    }
                    logger.debug("skip mocking sql");
                    return builder;
                })
                .type(named(jpaHibernate))
                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {
                    logger.debug("Inside HibernateProperties Transformer for setDdlAuto");
                    if (System.getenv("SKIP_MOCK_SQL") == null || !Boolean.parseBoolean(System.getenv("SKIP_MOCK_SQL")) )
                    {
                        logger.debug("mocking sql HibernateProperties");
                        return builder.method(named("setDdlAuto").and(takesArgument(0, String.class)))
                            .intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.ksql.SetDdlAuto_Advice").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                    }
                    logger.debug("skip mocking sql");
                    return builder;
                })
                .type(named(liquibase))
                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {

                    logger.debug("Inside LiquibaseProperties Transformer for setEnabled");

                    if (System.getenv("SKIP_MOCK_SQL") == null || !Boolean.parseBoolean(System.getenv("SKIP_MOCK_SQL")) )
                    {
                        logger.debug("mocking sql LiquibaseProperties");
                        return builder.method(named("setEnabled")
                                    .and(takesArgument(0, Boolean.class)))
                            .intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.ksql.SetEnabled_Advice").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                    }
                    logger.debug("skip mocking sql");
                    return builder;
                })
                .type(named(jpaProperties))
                .transform(((builder, typeDescription, classLoader, module, protectionDomain) -> {

                    logger.debug("Inside RegisterDialect Transformer");

                    if (System.getenv("SKIP_MOCK_SQL") == null || !Boolean.parseBoolean(System.getenv("SKIP_MOCK_SQL")) )
                    {
                        logger.debug("mocking sql RegisterDialect");
                        return builder.constructor(isDefaultConstructor())
                            .intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.ksql.RegisterDialect").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                    }
                    logger.debug("skip mocking sql");
                    return builder;
                }))
                .type(named(health))
                .transform(((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {

                    logger.debug("Inside HealthEndpoint Transformer");

                    if (System.getenv("SKIP_MOCK_SQL") == null || !Boolean.parseBoolean(System.getenv("SKIP_MOCK_SQL")) )
                    {
                        logger.debug("mocking sql HealthEndpoint");
                        return builder.method(named("withDetail"))
                            .intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.ksql.HealthCheckInterceptor").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                    }
                    logger.debug("skip mocking sql");
                    return builder;
                }))
                .type(named(proxyDB))
                .transform(((builder, typeDescription, classLoader, module, protectionDomain) -> {

                    logger.debug("Inside DatabaseMetaData transformer");

                    if (System.getenv("SKIP_MOCK_SQL") == null || !Boolean.parseBoolean(System.getenv("SKIP_MOCK_SQL")) )
                    {
                        logger.debug("mocking sql DatabaseMetaData");
                        return builder.constructor(takesArgument(0, DatabaseMetaData.class))
                            .intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.ksql.DataBaseMetaData_Advice").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                    }
                    logger.debug("skip mocking sql");
                    return builder;
                }))

                /*
                  Intercepting getResource method of JedisPool. getResource is a method where the redis client(Jedis)
                  returns a Jedis object and also checks the connection with the server. As connection should not be
                  established when Keploy is in TEST_MODE this method should be intercepted and return a Jedis object
                  without checking connection.
                 */
                .type(named(redisJedisPool))
                .transform(((builder, typeDescription, classLoader, module, protectionDomain) -> {
                    if (System.getenv("SKIP_MOCK_REDIS") == null || !Boolean.parseBoolean(System.getenv("SKIP_MOCK_REDIS")) )
                    {
                        logger.debug("mocking redis");
                        return builder.method(named("getResource"))
                            .intercept(Advice.to(TypePool.Default.ofSystemLoader().describe("io.keploy.advice.redis.jedis.JedisPoolResource_Advice").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader()));
                    }
                    logger.debug("skip mocking redis");
                    return builder;
                }))

                /*
                  The whole logic and connection with Redis Server boils down to one Class that is Connection. But
                  Connection is not directly used rather used as a super class for a Class BinaryClient. This
                  interceptor wraps the super class of BinaryClient i.e. Connection . As a final result BinaryClient
                  will be extended to a wrapped class of Connection.
                 */
                .type(named(redisJedisBinary))
                .transform(((builder, typeDescription, classLoader, module, protectionDomain) -> {
                    if (System.getenv("SKIP_MOCK_REDIS") == null || !Boolean.parseBoolean(System.getenv("SKIP_MOCK_REDIS")) )
                    {
                        logger.debug("mocking redis");
                        return getBuilderForClassWrapper(builder, "redis/clients/jedis/Connection", "io/keploy/redis/jedis/KConnection");
                    }
                    logger.debug("skip mocking redis");
                    return builder;
                }))

                // Interceptor for apache async-client
                // .type(named(asyncApacheClient))
                // .transform(new AgentBuilder.Transformer() {
                //     @Override
                //     public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription, ClassLoader classLoader, JavaModule javaModule, ProtectionDomain protectionDomain) {
                //         logger.debug("inside Async-ApacheInterceptor");
                //         System.out.println("inside Async-ApacheInterceptor");
                //
                //         String context = "org.apache.http.protocol.HttpContext";
                //         String host = "org.apache.http.HttpHost";
                //         String response = "org.apache.http.HttpResponse";
                //
                //         String asyncApacheInterceptor = "io.keploy.httpClients.AsyncApacheInterceptor";
                //
                //         ElementMatcher.Junction<MethodDescription> futureResponse = returnsGeneric(type -> type.asErasure().represents(Future.class)
                //                 && type.getSort().isParameterized()
                //                 && type.getTypeArguments().get(0).represents(HttpResponse.class));
                //
                //
                //         ElementMatcher.Junction<MethodDescription> args = takesArgument(0, named(host)).and(takesArgument(1, isSubTypeOf(HttpRequest.class))).and(takesArgument(2, named(context))).and(takesGenericArgument(3, type ->
                //                 type.asErasure().represents(FutureCallback.class)
                //                         && type.getSort().isParameterized()
                //                         && type.getTypeArguments().get(0).represents(HttpResponse.class)
                //         ));
                //
                //         return builder.method(named("execute").and(args)
                //                         .and(futureResponse))
                //                 .intercept(MethodDelegation.to(TypePool.Default.ofSystemLoader().describe(asyncApacheInterceptor).resolve()));
                //     }
                // })

                // Interceptor for elastic search -> apache InternalHttpAsyncClient
                // .type(named(internalhttpasyncClient))
                // .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> {
                //     System.out.println("Inside InternalHttpAsyncClient transformer");
                //     logger.debug("inside InternalHttpAsyncClient transformer");
                //
                //     System.out.println("Methods inside internalhttpAsync: " + typeDescription.getDeclaredMethods());
                //
                //     String requestProducer = "org.apache.http.nio.protocol.HttpAsyncRequestProducer";
                //     String context = "org.apache.http.protocol.HttpContext";
                //     String internalAsyncInterceptor = "io.keploy.httpClients.ElasticSearchInterceptor";
                //
                //
                //     ElementMatcher.Junction<MethodDescription> args = takesArgument(0, named(requestProducer))
                //             .and(takesGenericArgument(1, type -> type.asErasure().represents(HttpAsyncResponseConsumer.class)))
                //             .and(takesArgument(2, named(context)))
                //             .and(takesGenericArgument(3, type -> type.asErasure().represents(FutureCallback.class)));
                //
                //     return builder.method(named("execute").and(returnsGeneric(type -> type.asErasure().represents(Future.class))).and(args))
                //             .intercept(MethodDelegation.to(TypePool.Default.ofSystemLoader().describe(internalAsyncInterceptor).resolve()));
                //
                // })

                .installOn(instrumentation);
    }

    // TODO Add Java Doc
    // private static boolean isJUnitTest() {
    //     for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
    //         if (element.getClassName().startsWith("org.junit.")) {
    //             return true;
    //         }
    //     }
    //     return false;
    // }

    // TODO Add Java Doc
    // protected static void setEnv(Map<String, String> newenv) throws Exception {
    //     try {
    //         Class<?> processEnvironmentClass = Class.forName("java.lang.ProcessEnvironment");
    //         Field theEnvironmentField = processEnvironmentClass.getDeclaredField("theEnvironment");
    //         theEnvironmentField.setAccessible(true);
    //         Map<String, String> env = (Map<String, String>) theEnvironmentField.get(null);
    //         env.putAll(newenv);
    //         Field theCaseInsensitiveEnvironmentField = processEnvironmentClass.getDeclaredField("theCaseInsensitiveEnvironment");
    //         theCaseInsensitiveEnvironmentField.setAccessible(true);
    //         Map<String, String> cienv = (Map<String, String>) theCaseInsensitiveEnvironmentField.get(null);
    //         cienv.putAll(newenv);
    //     } catch (NoSuchFieldException e) {
    //         Class[] classes = Collections.class.getDeclaredClasses();
    //         Map<String, String> env = System.getenv();
    //         for (Class cl : classes) {
    //             if ("java.util.Collections$UnmodifiableMap".equals(cl.getName())) {
    //                 Field field = cl.getDeclaredField("m");
    //                 field.setAccessible(true);
    //                 Object obj = field.get(env);
    //                 Map<String, String> map = (Map<String, String>) obj;
    //                 map.clear();
    //                 map.putAll(newenv);
    //             }
    //         }
    //     }
    // }

    // A class will be replaced by another class in run time using this builder
    private static Builder getBuilderForClassWrapper(Builder builder, String host, String guest) {
        return builder.visit(
                new AsmVisitorWrapper() {
                    @Override
                    public int mergeWriter(int arg0) {
                        return arg0;
                    }

                    @Override
                    public int mergeReader(int arg0) {
                        return arg0;
                    }

                    @Override
                    public ClassVisitor wrap(TypeDescription instrumentedType,
                                             ClassVisitor classVisitor,
                                             net.bytebuddy.implementation.Implementation.Context implementationContext,
                                             TypePool typePool,
                                             FieldList<FieldDescription.InDefinedShape> fields,
                                             MethodList<?> methods,
                                             int writerFlags,
                                             int readerFlags) {
                        return new ClassVisitor(OpenedClassReader.ASM_API, classVisitor) {
                            private boolean wasMarked = false;

                            @Override
                            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                                if (host.equals(superName)) {
                                    superName = guest;
                                    if (signature != null) {
                                        SignatureWriter sw = new SignatureWriter() {
                                            private boolean superclass = false;

                                            @Override
                                            public void visitFormalTypeParameter(String name) {
                                                superclass = false;
                                                super.visitFormalTypeParameter(name);
                                            }

                                            @Override
                                            public SignatureVisitor visitSuperclass() {
                                                superclass = true;
                                                return super.visitSuperclass();
                                            }

                                            @Override
                                            public void visitEnd() {
                                                superclass = false;
                                                super.visitEnd();
                                            }

                                            @Override
                                            public SignatureVisitor visitInterface() {
                                                superclass = false;
                                                return super.visitInterface();
                                            }

                                            @Override
                                            public void visitClassType(String name) {
                                                if (superclass && host.equals(name)) {
                                                    name = guest;
                                                }
                                                super.visitClassType(name);
                                            }
                                        };
                                        new SignatureReader(signature).accept(sw);
                                        signature = sw.toString();
                                    }
                                    wasMarked = true;
                                }
                                super.visit(version, access, name, signature, superName, interfaces);
                            }

                            @Override
                            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                                if (wasMarked && "<init>".equals(name)) {
                                    return new MethodVisitor(OpenedClassReader.ASM_API, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                                        @Override
                                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                                            if (host.equals(owner)) {
                                                owner = guest;
                                            }
                                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                                        }
                                    };
                                }
                                return super.visitMethod(access, name, descriptor, signature, exceptions);
                            }
                        };
                    }
                }
        );
    }

}