package context;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.WeakHashMap;

@Getter
@Setter
@NoArgsConstructor
public class Context {
    public ThreadLocal<Map<HttpServletRequest, Context>> bindings = new ThreadLocal<>();

    public KeployContext keployContext;

    public Context(String mode, String testId, Dependency[] deps) {
        this.keployContext = new KeployContext(
                mode, testId, deps
        );
    }

    public void bind(HttpServletRequest request) {
        Context ctx = new Context();
        Map<HttpServletRequest, Context> map = new WeakHashMap<>();
        map.put(request, ctx);
        this.bindings.set(map);
    }

    public Context get(HttpServletRequest request) {
        return this.bindings.get().get(request);
    }

    public void set(HttpServletRequest request, Context ctx) {
        Map<HttpServletRequest, Context> map = new WeakHashMap<>();
        map.put(request, ctx);
        this.bindings.set(map);
    }
}