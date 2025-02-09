package io.keploy.agent;

import io.keploy.advice.http.HttpServiceAdvice;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

public class KAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        new AgentBuilder.Default()
                .type(ElementMatchers.named("javax.servlet.http.HttpServlet")
                        .or(ElementMatchers.named("jakarta.servlet.http.HttpServlet")))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
                                                            TypeDescription typeDescription,
                                                            ClassLoader classLoader,
                                                            JavaModule module,
                                                            ProtectionDomain protectionDomain) {
                        return builder.method(ElementMatchers.named("service"))
                                .intercept(Advice.to(HttpServiceAdvice.class));
                    }
                })
                .installOn(inst);
    }


}
