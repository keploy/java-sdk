package context;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class Dependency {
    private String name;
    private String type;
    private Object meta;
    private Object[] data;

    public Dependency(String name, String type, Object meta, Object[] data) {
        this.name = name;
        this.type = type;
        this.meta = meta;
        this.data = data;
    }
}