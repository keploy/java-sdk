package io.keploy.regression.keploy;

import io.keploy.regression.Mode;
import io.keploy.grpc.stubs.Service;
import lombok.Getter;
import lombok.Setter;

import java.util.*;

@Getter
@Setter
public class Keploy {

    private Config cfg;
    private Map<String, Service.HttpResp> resp;
    private Map<String, List<Service.Dependency>> deps;
    private Map<String, List<Service.Mock>> mocks;
    private Map<String, Long> mocktime;


    public Keploy() {
        resp = Collections.synchronizedMap(new HashMap<>());
        deps = Collections.synchronizedMap(new HashMap<>());
        mocks = Collections.synchronizedMap(new HashMap<>());
        mocktime = Collections.synchronizedMap(new HashMap<>());

        initMode();
    }

    private void initMode() {

        // checking because user may be setting while running in test mode
        if (Mode.getMode() == null) {
            String envMode = "off";
            if (System.getenv("KEPLOY_MODE") != null) {
                envMode = System.getenv("KEPLOY_MODE");
            }

            envMode = envMode.trim();

            switch (envMode) {
                case "record":
                    Mode.setMode(Mode.ModeType.MODE_RECORD);
                    break;
                case "test":
                    Mode.setMode(Mode.ModeType.MODE_TEST);
                    break;
                case "off":
                    Mode.setMode(Mode.ModeType.MODE_OFF);
                    break;
            }
        }
    }

}