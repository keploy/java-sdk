package io.keploy;




import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;




public class Agent {
    private static Logger logger = LogManager.getLogger(Agent.class);
//    public static void premain(
//            String agentArgs, Instrumentation inst) {
//        logger.debug("[com.keploy.Agent] In premain method");
//        String className = "io.keploy.HelloWorld";

    //TODO instrument io.keploy.HelloWorld
    public static void main(String... args){
        System.out.println("Hi from agent");
//        HelloWorld hw= new HelloWorld();
//        hw.sum();


    }





}
