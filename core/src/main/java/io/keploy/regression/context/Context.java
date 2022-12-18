package io.keploy.regression.context;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class Context {
    private static InheritableThreadLocal<Kcontext> ctx = new InheritableThreadLocal<>();

    public static void setCtx(Kcontext kcontext) {
        ctx.set(kcontext);
    }

    public static Kcontext getCtx() {
        return ctx.get();
    }

    public static void cleanup() {
        if (ctx.get() != null) {
            ctx.remove();
        }
    }
}