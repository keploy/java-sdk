package io.keploy.agent;
import io.keploy.agent.Dto.ClassHolder;
import io.keploy.agent.annotation.Keploy;
import io.keploy.agent.annotation.KeployMethod;
import io.keploy.agent.core.Transform;
import io.keploy.agent.transformer.DynamicTransformer;
import io.keploy.agent.transformer.StaticTransformer;
import io.keploy.agent.transformer.interfaces.Transformer;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Annotation;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Agent{
    private static Logger logger = LogManager.getLogger(Agent.class);
    private static Transform transform = new Transform();

    public static void premain(
            String agentArgs, Instrumentation inst) {
        logger.info("[Agent] In premain method");
        startDynamicTransform(agentArgs, inst);
    }
    //TODO figure out how to code using main in case of java-agent , can't always build and test!
    public static void main(String[] args){

    }
    //TODO attach to running app using agentmain
    public static void agentmain(String agentArgs, Instrumentation inst) {
        logger.info("[Agent] In agentmain method");
        startStaticTransform(agentArgs,inst);
    }
    private static void startStaticTransform(String agentArgs, Instrumentation inst){
        String className = "io.keploy.app.TestApp";
        ClassHolder classHolder = transform.getClassLoader(className,inst);
        Transformer staticTransformer = new StaticTransformer(className, classHolder.getTargetClassLoader());
        transform.transform(classHolder.getTargetCls(),classHolder.getTargetClassLoader(),inst,staticTransformer);
    }

    private static void startDynamicTransform(String agentArgs, Instrumentation inst){


        //from instrument package load the class
        //TODO figure out how to get class names from defined modules
        String className = "io.keploy.agent.instrument.TestApp";
        ClassHolder classHolder = transform.getClassLoader(className,inst);
        // from annotation get package name
        String packageName = classHolder.getTargetCls().getAnnotation(Keploy.class).PackageName();
        // from annotation get method name
        ClassPool cp = ClassPool.getDefault();
        try {
            CtClass cc = cp.get(className);

            Method[] methods = classHolder.getTargetCls().getDeclaredMethods();
            Map<String, CtMethod> mapOfMethodNameAndMethod = new ConcurrentHashMap<>();
            for (Method method : methods) {
                Annotation[] annotations = method.getAnnotations();
                for (Annotation annotation : annotations) {
                    if (annotation.annotationType(). equals(KeployMethod.class)) {
                        mapOfMethodNameAndMethod.put(method.getName(), cc.getDeclaredMethod(method.getName()));
                        logger.info("Method: {} ", method.getName());
                        break;
                    }
                }
            }


            // get target class
            ClassHolder targetClassHolder = transform.getClassLoader(packageName, inst);
            // replace target class method with annotated method
            Transformer staticTransformer = new DynamicTransformer(packageName, targetClassHolder.getTargetClassLoader(), mapOfMethodNameAndMethod.get("getY"), "getY");
            transform.transform(targetClassHolder.getTargetCls(), targetClassHolder.getTargetClassLoader(), inst, staticTransformer);

        }catch (Exception e){
            logger.error("Exception e",e);
        }



    }




}






