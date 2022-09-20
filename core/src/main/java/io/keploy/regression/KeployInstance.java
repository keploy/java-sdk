package io.keploy.regression;

import io.keploy.regression.keploy.Keploy;

public class KeployInstance {
    private static volatile KeployInstance keployInstance;

    private final Keploy keploy;


    private KeployInstance() {
        keploy = new Keploy();
    }

    public static KeployInstance getInstance() {
        if (keployInstance == null) {
            synchronized (KeployInstance.class) {  //thread safe.
                if (keployInstance == null) {
                    keployInstance = new KeployInstance();
                }
            }
        }
        return keployInstance;
    }

    public Keploy getKeploy() {
        return keploy;
    }
}