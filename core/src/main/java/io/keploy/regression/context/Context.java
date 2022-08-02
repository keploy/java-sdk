package io.keploy.regression.context;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.servlet.http.HttpServletRequest;

@Getter
@Setter
@NoArgsConstructor
public class Context {
    private static ThreadLocal<Kcontext> ctx = new ThreadLocal<>();

    public static void setCtx(Kcontext kcontext) {
        ctx.set(kcontext);
    }

    public static Kcontext getCtx() {
        return ctx.get();
    }

    public static void cleanup() {
        ctx.remove();
    }
}