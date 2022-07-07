package io.keploy.regression.keploy;

import io.github.cdimascio.dotenv.Dotenv;
import io.keploy.regression.mode;
import lombok.Getter;
import lombok.Setter;

import javax.servlet.http.HttpServletResponse;
import java.util.concurrent.ConcurrentHashMap;


@Getter
@Setter
public class Keploy {

    private Config cfg;
    private ConcurrentHashMap<String, HttpServletResponse> resp;

    public Keploy() {
        resp = new ConcurrentHashMap<>();
        initMode();
    }

    public void initMode() {
        Dotenv dotenv = Dotenv.load();
        String envMode = dotenv.get("KEPLOY_MODE");

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
