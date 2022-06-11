package keploy;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
class Filter {
    private String UrlRegex;
    private String[] HeaderRegex;

    public Filter(String urlRegex, String[] headerRegex) {
        UrlRegex = urlRegex;
        HeaderRegex = headerRegex;
    }
}