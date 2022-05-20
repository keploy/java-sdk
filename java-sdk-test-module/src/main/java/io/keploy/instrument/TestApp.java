package io.keploy.instrument;

import io.keploy.annotation.Keploy;
import io.keploy.annotation.KeployMethod;
import io.keploy.annotation.NewField;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Keploy
public class TestApp {

    @NewField static int y= 2000;
    private static Logger logger = LogManager.getLogger(io.keploy.app.TestApp.class);

    public void sum() {
        logger.error("Hi There!");
        int x= 50;
        logger.debug("Sum is: " +(x+getY()));
    }

    @KeployMethod
    //This method will be used to check method transformation.
    private static int getY() {
        return y;
    }
}
