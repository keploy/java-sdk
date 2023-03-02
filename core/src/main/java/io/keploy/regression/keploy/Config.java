package io.keploy.regression.keploy;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Class to store both Client and Server configurations
 */
@Getter
@Setter
@NoArgsConstructor
public class Config {
    private AppConfig App;
    private ServerConfig Server;

    public Config(AppConfig app, ServerConfig server) {
        App = app;
        Server = server;
    }
}
