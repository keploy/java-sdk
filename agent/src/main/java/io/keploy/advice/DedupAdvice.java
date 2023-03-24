package io.keploy.advice;

import io.keploy.agent.KAgent;
import net.bytebuddy.asm.Advice;

public class DedupAdvice {
    @Advice.OnMethodEnter
    public static void onMethodEnter(@Advice.Origin("#t") String className, @Advice.Origin("#m") String methodName) {
        KAgent.logLine(className, methodName);
    }

    @Advice.OnMethodExit
    public static void onMethodExit(@Advice.Origin("#t") String className, @Advice.Origin("#m") String methodName) {
        KAgent.logLine(className, methodName);
    }
}



    // reset after request over ... all static variables
//    @Advice.OnMethodEnter
//    static void enterMethods(@Advice.Origin Method method) throws Exception {
////        System.out.println("Keploy context[" + Context.getCtx() + "] in method:" + method);
//        if (Context.getCtx() != null) {
//
//            if (method.toString().contains("com.example") || method.getDeclaringClass().toString().contains("com.example")) {
//                stackTraceArr.add(method.toString() + "#");
//                metCount.set(1);
//                System.out.println(cnt.get() + " Inside Enter Method:" + method);
//                cnt.incrementAndGet();
//            } else if (metCount.get() > 0 && !method.getDeclaringClass().toString().contains("com.fasterxml") && !method.toString().contains("java.lang.Object.hashCode()") && !method.toString().contains("com.sun.proxy") && !method.toString().contains("java.util.concurrent.ConcurrentHashMap.get") && !method.toString().contains("proxy") && !method.toString().contains("ClassLoader") && !method.toString().contains("org.mockito.internal") && !method.toString().contains("org.mockito.cglib") && !method.toString().contains("LogAdapter") && !method.toString().contains("org.hibernate.internal") && !method.toString().contains("org.hibernate") && !method.toString().contains("org.mockito.asm.") && !method.toString().contains("org.mockito.Answers")
//                    && !method.toString().contains("org.jboss") && !method.toString().contains("org.jboss.logging.Logger.isDebugEnabled")
//                    && !method.toString().contains("com.zaxxer.hikari") && !method.toString().contains("org.apache.juli")
//                    && !method.toString().contains("org.apache.catalina") && !method.toString().contains("org.apache.tomcat")
//                    && !method.toString().contains("io.keploy.ksql") && !method.toString().contains("org.apache.coyote")
//                    && !method.toString().contains("com.google.protobuf") && !method.toString().contains("io.keploy.utils")
////                    && !method.toString().contains("java.sql") && !method.toString().contains("jdbc")
////                    && !method.toString().contains("org.mockito.Answers") && !method.toString().contains("org.mockito.Answers")
//            ) {
//                metCount.decrementAndGet();
//                stackTraceArr.add(method.toString());
//                System.out.println(cnt.get() + " Inside Enter Method:" + method);
//                cnt.incrementAndGet();
//            }
//        }
//    }


//    @Advice.OnMethodEnter
//    public static void logLine2(@Advice.Origin("#t") String className, @Advice.Origin("#m") String methodName, @Advice.Origin("#s") String signature, @Advice.AllArguments Object[] args) {
//        if (className.startsWith("com.example.demo")) { // Replace "your.package.name" with your application's package name
//            StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
//            int lineNumber = -1;
//            for (StackTraceElement element : stackTraceElements) {
//                if (element.getClassName().equals(className) && element.getMethodName().equals(methodName)) {
//                    lineNumber = element.getLineNumber();
//                    break;
//                }
//            }
//            System.out.println("Line executed: " + className + " -> " + lineNumber);
//        }
//    }


//    && (method.getDeclaringClass().toString().contains("java.io") || method.getDeclaringClass().toString().contains("org.springframework.data.jpa") || method.getDeclaringClass().toString().contains("org.apache.logging.log4"))) && !method.getDeclaringClass().toString().contains("com.fasterxml") && !method.toString().contains("java.lang.Object.hashCode()") && !method.toString().contains("com.sun.proxy")
