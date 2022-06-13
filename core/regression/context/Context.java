package regression.context;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.servlet.http.HttpServletRequest;

@Getter
@Setter
@NoArgsConstructor
public class Context {
    private static ThreadLocal<HttpServletRequest> ctx = new ThreadLocal<>();

    public static void setCtx(HttpServletRequest httpServletRequest){
        ctx.set(httpServletRequest);
    }
    public static HttpServletRequest getCtx(){
        return ctx.get();
    }
    public static void cleanup(){
        ctx.set(null);
    }
}