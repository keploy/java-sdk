package keploy;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
@Getter
@Setter
@NoArgsConstructor
class ServerConfig {
    private String URL = "https://api.keploy.io";
    private String LicenseKey;

    public ServerConfig(String URL, String licenseKey) {
        this.URL = URL;
        LicenseKey = licenseKey;
    }
}
