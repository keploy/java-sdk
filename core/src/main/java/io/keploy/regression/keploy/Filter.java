package io.keploy.regression.keploy;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Filter class to filter out few Headers and Urls while recording
 */
@Getter
@Setter
@NoArgsConstructor
public class Filter {
    String[] acceptUrlRegex;
    String[] acceptHeaderRegex;
    String[] rejectHeaderRegex;
    String[] rejectUrlRegex;
    public Filter(String[] acceptUrlRegex, String[] acceptHeaderRegex, String[] rejectHeaderRegex, String[] rejectUrlRegex) {
        this.acceptUrlRegex = acceptUrlRegex;
        this.acceptHeaderRegex = acceptHeaderRegex;
        this.rejectHeaderRegex = rejectHeaderRegex;
        this.rejectUrlRegex = rejectUrlRegex;
    }
}
