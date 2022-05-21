package io.keploy.agent.core;

import io.keploy.agent.Dto.ClassHolder;
import io.keploy.agent.transformer.interfaces.Transformer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.instrument.Instrumentation;


//TODO use interfaces instead of directly using classes
public class Transform {

    private static Logger logger = LogManager.getLogger(Transform.class);

    /**
     * This will take the className , find the class and classLoader which can be used further
     *
     * @param className         Name of the class to transform
     * @param instrumentation   instrumentation library
     * @return classLoader returns corresponding classLoader
     */
    public static ClassHolder getClassLoader(String className, Instrumentation instrumentation) {
        Class<?> targetCls = null;
        ClassLoader targetClassLoader = null;
        // see if we can get the class using forName
        try {
            targetCls = Class.forName(className);
            targetClassLoader = targetCls.getClassLoader();
            return new ClassHolder(targetCls,targetClassLoader);
        } catch (Exception ex) {
            logger.error("Class [{}] not found with Class.forName",className);
        }
        // otherwise iterate all loaded classes and find what we want
        for(Class<?> clazz: instrumentation.getAllLoadedClasses()) {
            logger.error(clazz.getName());
            logger.error(targetClassLoader.toString());
            if(clazz.getName().equals(className)) {
                targetCls = clazz;
                targetClassLoader = targetCls.getClassLoader();
                return new ClassHolder(targetCls,targetClassLoader);
            }
        }
        throw new RuntimeException(
                "Failed to find class [" + className + "]");
    }
    public void transform(
            Class<?> clazz,
            ClassLoader classLoader,
            Instrumentation instrumentation,Transformer dt) {
        instrumentation.addTransformer(dt, true);
        try {
            instrumentation.retransformClasses(clazz);
        } catch (Exception ex) {
            throw new RuntimeException(
                    "Transform failed for: [" + clazz.getName() + "]", ex);
        }
    }
}
