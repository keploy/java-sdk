package io.keploy.advice.http;

import net.bytebuddy.asm.Advice;

public class HttpServiceAdvice {
    @Advice.OnMethodEnter
    public static void onEnter() {
        System.out.println("[Keploy Agent] Intercepted HttpServlet.service() method");
        io.keploy.advice.http.Process.method();
    }
}