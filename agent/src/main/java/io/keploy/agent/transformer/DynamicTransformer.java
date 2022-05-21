package io.keploy.agent.transformer;

import io.keploy.agent.transformer.interfaces.Transformer;
import javassist.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;

public class DynamicTransformer implements Transformer {
    private String targetClassName;
    private ClassLoader targetClassLoader;
    private CtMethod method;
    private String methodName;
    private static Logger logger = LogManager.getLogger(DynamicTransformer.class);


    public DynamicTransformer(String targetClassName, ClassLoader targetClassLoader, CtMethod method, String methodName) {
        this.targetClassName = targetClassName;
        this.targetClassLoader = targetClassLoader;
        this.method = method;
        this.methodName = methodName;
    }

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        byte[] byteCode = classfileBuffer;
        String finalTargetClassName = this.targetClassName
                .replaceAll("\\.", "/");
        if (!className.equals(finalTargetClassName)) {
            return byteCode;
        }

        if (className.equals(finalTargetClassName)
                && loader.equals(targetClassLoader)) {

            logger.info("[Agent] Transforming class {}",className);
            try {
                ClassPool cp = ClassPool.getDefault();
                CtClass cc = cp.get(targetClassName);
                CtMethod m = cc.getDeclaredMethod(
                        methodName);
                m.setBody(method,null);
                logger.info("[Agent] Transforming method {}",m.getName());
                logger.info("[Agent] Transforming method details {}",method.toString());
                Thread.sleep(1000L);
                byteCode = cc.toBytecode();
                cc.detach();
            } catch (Exception e) {
                logger.info("Exception", e);
            }
        }
        return byteCode;
    }
}
