package io.keploy.agent.transformer;

import io.keploy.agent.transformer.interfaces.Transformer;
import javassist.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.security.ProtectionDomain;

public class StaticTransformer implements Transformer {
    private String targetClassName;

    ClassLoader targetClassLoader;
    private static Logger logger = LogManager.getLogger(StaticTransformer.class);


    public StaticTransformer(String targetClassName, ClassLoader targetClassLoader) {
        this.targetClassName = targetClassName;
        this.targetClassLoader = targetClassLoader;
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
                        "sum");
                logger.info("[Agent] Transforming class {}",m.getName());

                m.addLocalVariable(
                        "startTime", CtClass.longType);
                m.insertBefore(
                        "startTime = System.currentTimeMillis();");

                StringBuilder endBlock = new StringBuilder();

                m.addLocalVariable("endTime", CtClass.longType);
                m.addLocalVariable("opTime", CtClass.longType);
                //To add delay to check whether time logger is working properly
                endBlock.append(" try {  Thread.sleep(3000L);   } catch (InterruptedException e) {   throw new RuntimeException(e); }");
                endBlock.append(
                        "endTime = System.currentTimeMillis();");
                endBlock.append(
                        "opTime = (endTime-startTime)/1000;");
                endBlock.append(
                        "System.out.println(\"[Application] Added Logs: " +
                                "\" + opTime + \" seconds!\");");
                //TODO not working with error, it is erroring out , need to figure that out!
//                endBlock.append(
//                        "logger.error(\"[Application] Added Logs:" +
//                                "\" + opTime + \" seconds!\");");

                m.insertAfter(endBlock.toString());

                byteCode = cc.toBytecode();
                cc.detach();
            } catch (NotFoundException | CannotCompileException | IOException e) {
                logger.error("Exception", e);
            }
        }
        return byteCode;
    }
}