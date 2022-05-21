package io.keploy.agent.instrument;


import io.keploy.agent.annotation.Keploy;
import io.keploy.agent.annotation.KeployMethod;
import io.keploy.agent.annotation.NewField;

@Keploy(PackageName="io.keploy.app.TestApp")
public class TestApp {

    @NewField
    static int y= 2000;


    @KeployMethod
    private static int getY() {
        return 20000;
    }
}
