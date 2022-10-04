package io.keploy.regression;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Component;

@Getter
@Setter
@NoArgsConstructor
@Component
public class Mock {

    public enum Version {
        V1_BETA1("api.keploy.io/v1beta1");

        public final String value;

        Version(String value) {
            this.value = value;
        }
    }

    public enum Kind {
        HTTP_EXPORT("Http"),
        GENERIC_EXPORT("Generic");

        public final String value;

        Kind(String value) {
            this.value = value;
        }
    }
}
