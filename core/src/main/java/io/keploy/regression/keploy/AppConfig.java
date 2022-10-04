package io.keploy.regression.keploy;


import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Duration;

@Getter
@Setter
@NoArgsConstructor
public class AppConfig {

    private String Name = "myApp";

    private String Host = "0.0.0.0";

    private String Port = "8080";

    private String TestPath = "";

    private String MockPath = "";

    private Duration Delay = Duration.ofSeconds(5);

    private Duration Timeout = Duration.ofSeconds(300);

    private Filter Filter;

    public AppConfig(String name, String host, String port, String testPath, String mockPath, Duration delay, Duration timeout, io.keploy.regression.keploy.Filter filter) {
        Name = name;
        Host = host;
        Port = port;
        TestPath = testPath;
        MockPath = mockPath;
        Delay = delay;
        Timeout = timeout;
        Filter = filter;
    }
}