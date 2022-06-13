package io.keploy.regression.keploy;

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
    }

}
