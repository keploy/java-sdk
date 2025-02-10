package io.keploy.advice.http;

import net.bytebuddy.asm.Advice;

public class HttpServiceAdvice {
    @Advice.OnMethodEnter
    public static void onEnter() {
        System.out.println("[Keploy Agent] Intercepted HttpServlet.service() method");

        try {
            System.out.println("LOADING class for utils");

            // Load Process class explicitly from the bootstrap class loader
            Class<?> processClass = Class.forName("io.keploy.utils.Process", true, ClassLoader.getSystemClassLoader());

            // Invoke method dynamically
            processClass.getDeclaredMethod("method2").invoke(null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
