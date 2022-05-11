package io.keploy;



//import com.sun.org.slf4j.internal.LoggerFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.instrument.Instrumentation;


public class Agent {
    private static Logger logger = LogManager.getLogger(Agent.class);
    public static void premain(
            String agentArgs, Instrumentation inst) {
        logger.debug("[com.keploy.Agent] In premain method");
        String className = "io.keploy.HelloWorld";

        //TODO instrument io.keploy.HelloWorld

    }





}
