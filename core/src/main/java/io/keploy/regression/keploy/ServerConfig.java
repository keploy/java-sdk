package io.keploy.regression.keploy;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
@Getter
@Setter
@NoArgsConstructor
public class ServerConfig {
//    "https://api.keploy.io";
    private String URL = "http://localhost:8081/api";
    private String LicenseKey;

    public ServerConfig(String URL, String licenseKey) {
        this.URL = URL;
        LicenseKey = licenseKey;
    }
}
