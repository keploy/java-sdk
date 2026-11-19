package io.keploy.dedup;

/**
 * A stand-in for JaCoCo's org.jacoco.agent.rt.RT in the tests: the SDK finds
 * JaCoCo's agent in its own JVM through RT.getAgent(). It is never on the
 * test classpath under RT's name, since a class there named
 * org/jacoco/agent/rt/RT.class tells the SDK, in this JVM and in every JVM a
 * test starts with this classpath, that a JaCoCo agent is attached
 * (EmbeddedJacoco.attached). InProcessScanTest defines it as RT in a class
 * loader of its own, the thread's context loader, which the SDK looks in.
 */
public final class JacocoRtStub {

    // agent is what getAgent returns.
    public static volatile Object agent;

    private JacocoRtStub() {
    }

    public static Object getAgent() {
        return agent;
    }
}
