package context;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class KeployContext {

    private String mode;
    private String testId;
    private Dependency[] deps;

    public KeployContext(String mode, String testId, Dependency[] deps) {
        this.mode = mode;
        this.testId = testId;
        this.deps = deps;
    }
}