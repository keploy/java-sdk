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

    private Duration Delay = Duration.ofSeconds(6);
    private Duration Timeout = Duration.ofSeconds(300);
    private Filter Filter;

    public AppConfig(String name, String host, String port, Duration delay, Duration timeout, Filter filter) {
        Name = name;
        Host = host;
        Port = port;
        Delay = delay;
        Timeout = timeout;
        Filter = filter;

    }
}