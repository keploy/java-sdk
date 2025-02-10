package io.keploy.utils;

import java.util.Objects;

public class Helper {

//    public static Object wrapRequest(Object request, String requestClassName) {
//        try {
//            Class<?> wrapperClass = Class.forName("io.keploy.agent.RequestWrapper", true, Thread.currentThread().getContextClassLoader());
//            Constructor<?> constructor = wrapperClass.getConstructor(Class.forName(requestClassName, true, Thread.currentThread().getContextClassLoader()));
//            return constructor.newInstance(request);
//        } catch (Exception e) {
//            throw new RuntimeException("Failed to wrap HttpServletRequest", e);
//        }
//    }

    public static String ExtractEnv(String Key){
        String value = System.getProperty(Key);
        // If not set in system properties, fallback to environment variables
        if (value == null) {
            value = System.getenv(Key);
        }
        System.out.println("ExtractEnv ExtractEnv");
//        System.out.println(Key + Objects.requireNonNullElse(value, "is not present please that in env variable"));
        return value;
    }

//    public static Object wrapResponse(Object response, String responseClassName) {
//        try {
//            Class<?> wrapperClass = Class.forName("io.keploy.agent.ResponseWrapper", true, Thread.currentThread().getContextClassLoader());
//            Constructor<?> constructor = wrapperClass.getConstructor(Object.class);
//            Object wrappedResponse = constructor.newInstance(response);
//
//            // Cast the wrapped object to the correct type
//            Class<?> responseClass = Class.forName(responseClassName, true, Thread.currentThread().getContextClassLoader());
//            return responseClass.cast(wrappedResponse);
//        } catch (Exception e) {
//            throw new RuntimeException("Failed to wrap HttpServletResponse", e);
//        }
//    }
//
//    public static void processResponse(Object request, Object response) {
//        try {
//            Class<?> requestWrapperClass = Class.forName("io.keploy.agent.RequestWrapper", true, Thread.currentThread().getContextClassLoader());
//            Class<?> responseWrapperClass = Class.forName("io.keploy.agent.ResponseWrapper", true, Thread.currentThread().getContextClassLoader());
//            Class<?> agentProcessorClass = Class.forName("io.keploy.agent.AgentProcessor", true, Thread.currentThread().getContextClassLoader());
//
//            Method processResponseMethod = agentProcessorClass.getMethod("processResponse", requestWrapperClass, responseWrapperClass);
//            processResponseMethod.invoke(null, request, response);
//        } catch (Exception e) {
//            throw new RuntimeException("Failed to process response", e);
//        }
//    }
}
