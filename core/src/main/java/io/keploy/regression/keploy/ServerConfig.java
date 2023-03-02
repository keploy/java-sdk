package io.keploy.regression.keploy;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
@Getter
@Setter
@NoArgsConstructor
public class ServerConfig {
//    "https://api.keploy.io";
    private String URL = "http://localhost:6789/api";
    private String LicenseKey;
    private Boolean Denoise = false;

    public ServerConfig(String URL, String licenseKey,Boolean denoise) {
        this.URL = URL;
        LicenseKey = licenseKey;
        Denoise = denoise;
    }
}
