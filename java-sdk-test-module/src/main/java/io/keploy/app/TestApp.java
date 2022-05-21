package io.keploy.app;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class TestApp {
    private static Logger logger = LogManager.getLogger(TestApp.class);

    public static void main(String[] args){
        TestApp testApp = new TestApp();
        testApp.sum();
    }
    public void sum() {
        logger.error("Hi There!");
        int x= 50;
        logger.debug("Sum is: " +(x+getY()));
    }
    //This method will be used to check method transformation.
    private static int getY() {
        return 100;
    }
}
