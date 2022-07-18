package io.keploy.regression.keploy;
import io.keploy.regression.mode;
import io.keploy.grpc.stubs.Service;
import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.ConcurrentHashMap;


@Getter
@Setter
public class Keploy {

    private Config cfg;
    private ConcurrentHashMap<String, Service.HttpResp> resp;

    public Keploy() {
        resp = new ConcurrentHashMap<>();
        initMode();
    }

    public void initMode() {

        String envMode = "record";
        if (System.getenv("KEPLOY_MODE") != null) {
            envMode = System.getenv("KEPLOY_MODE");
        }


        switch (envMode) {
            case "record":
                new mode().setMode(mode.ModeType.MODE_RECORD);
                break;
            case "test":
                new mode().setMode(mode.ModeType.MODE_TEST);
                break;
            case "off":
                new mode().setMode(mode.ModeType.MODE_OFF);
                break;
        }

    }

}

