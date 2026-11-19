package org.example.embedded;

import java.lang.reflect.Method;

/**
 * EmbeddedApp is the app of EmbeddedJacocoTest's own JVMs: it says whether a
 * JaCoCo agent runs (agent=false when none was attached or started), and
 * whether JaCoCo instrumented its own class and a library class it loads only
 * now, after every agent's premain, and the INDEX report the SDK sends.
 */
public final class EmbeddedApp {

    private EmbeddedApp() {
    }

    public static void main(String[] args) throws Exception {
        Object agent;
        try {
            agent = Class.forName("org.jacoco.agent.rt.RT").getMethod("getAgent").invoke(null);
        } catch (ClassNotFoundException e) {
            // No JaCoCo agent was attached or started.
            agent = null;
        }
        System.out.println("agent=" + (agent != null));
        System.out.println("app=" + instrumented(EmbeddedApp.class));
        System.out.println("library=" + instrumented(Class.forName("org.junit.runner.Description")));
        if (System.getenv("EMBEDDED_APP_INDEX") != null) {
            // Reads the index when premain did not.
            System.out.println("index=" + indexReport());
        }
    }

    // indexReport is the INDEX report the SDK sends Keploy, or null when no
    // SDK started.
    private static Object indexReport() throws Exception {
        Method report = Class.forName("io.keploy.dedup.KeployDedupAgent").getDeclaredMethod("indexReport");
        report.setAccessible(true);
        return report.invoke(null);
    }

    private static boolean instrumented(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals("$jacocoInit")) {
                return true;
            }
        }
        return false;
    }
}
