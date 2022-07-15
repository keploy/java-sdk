package io.keploy;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HelloWorld {
    private static Logger logger = LogManager.getLogger(HelloWorld.class);
    public static void main(String[] args){
        logger.error("Hi There!");
        int x= 50;
        logger.debug("Sum is: " +(x+getY()));
    }
    //This method will be used to check method transformation.
    private static int getY() {
        return 100;
    }
}
