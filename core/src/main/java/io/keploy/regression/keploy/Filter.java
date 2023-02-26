package io.keploy.regression.keploy;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class Filter {
    private String acceptUrlRegex;
    private String[] HeaderRegex;
    private String[] rejectUrlRegex;

    public Filter(String acceptUrlRegex, String[] headerRegex, String[] rejectUrlRegex) {
        this.acceptUrlRegex = acceptUrlRegex;
        HeaderRegex = headerRegex;
        this.rejectUrlRegex = rejectUrlRegex;
    }
}
