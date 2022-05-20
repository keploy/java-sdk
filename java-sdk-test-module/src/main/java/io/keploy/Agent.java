package io.keploy;

import io.keploy.app.TestApp;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.instrument.Instrumentation;

public class Agent{
    private static Logger logger = LogManager.getLogger(Agent.class);
//    public static void premain(
//            String agentArgs, Instrumentation inst) {
//        logger.debug("[com.io.keploy.Agent] In premain method");
//        String className = "io.keploy.app.HelloWorld";

        //TODO instrument io.keploy.app.HelloWorld
    public static void main(String... args){
        logger.info("Hi from agent");
        String className = "io/keploy/instrument/TestApp.class";
        transformClass(className);
        


    }

//    private void checkIfKeployClass(Object object) {
//        if (Objects.isNull(object)) {
//            logger.error("The object to serialize is null");
//        }
//
//        Class<?> clazz = object.getClass();
//        if (!clazz.isAnnotationPresent(JsonSerializable.class)) {
//            throw new JsonSerializationException("The class "
//                    + clazz.getSimpleName()
//                    + " is not annotated with JsonSerializable");
//        }
//    }

    private static void transformClass(String className) {
        Class<?> targetCls = null;
        ClassLoader targetClassLoader = null;
        // see if we can get the class using forName
        try {
            targetCls = Class.forName(className);
            targetClassLoader = targetCls.getClassLoader();
//            transform(targetCls, targetClassLoader, instrumentation);
            return;
        } catch (Exception ex) {
            logger.error("Class [{}] not found with Class.forName");
        }
        // otherwise iterate all loaded classes and find what we want
        Instrumentation instrumentation = null;
        for(Class<?> clazz: Instrumentation.getAllLoadedClasses()) {
            if(clazz.getName().equals(className)) {
                targetCls = clazz;
                targetClassLoader = targetCls.getClassLoader();
//                transform(targetCls, targetClassLoader, instrumentation);
                return;
            }
        }
        throw new RuntimeException(
                "Failed to find class [" + className + "]");
    }

    }





}
