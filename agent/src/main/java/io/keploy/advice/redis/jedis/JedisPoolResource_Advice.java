package io.keploy.advice.redis.jedis;

import io.keploy.grpc.stubs.Service.Dependency;
import io.keploy.regression.Mode;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import net.bytebuddy.asm.Advice;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Objects;

import static net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC;

/**
 * Class {@link JedisPoolResource_Advice} is used for intercepting method {@link JedisPool#getResource()} and returning
 * {@link Jedis} object when Keploy is in TEST_MODE.
 *
 * @author charankamarapu
 */
public class JedisPoolResource_Advice {

    /**
     * This method gets executed before the method {@link JedisPool#getResource()}. Based on the mode of Kelpoy it skips
     * the invocation of the method {@link JedisPool#getResource()}
     *
     * @skipOn {@link Advice.OnNonDefaultValue} - this indicates that if any other value except default value of the
     *           return type is returned then skip method invocation of intercepting method i.e.
     *           {@link JedisPool#getResource()}
     * @return Boolean - Default value false
     */
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enterMethods() {
        final Logger logger = LoggerFactory.getLogger(JedisPoolResource_Advice.class);
        Kcontext kCtx = Context.getCtx();
        if (Objects.isNull(kCtx)) {
            logger.debug("Keploy context is null");
            return false;
        } else {
            // get DEPENDENCY from environment variable
            // String listDependency = System.getenv("DEPENDENCY"); // List of dependencies separated by comma
            // logger.debug(listDependency);
            // logger.debug(listDependency, kCtx.getDeps().get(0));

            // Remove those dependencies from the list of dependencies in the context object 
            // if the dependency is not in the list of dependencies from the environment variable
            // if (listDependency != null) {
            //     logger.debug("I have got dependencies to be excluded");
            //     String[] listDependencyArray = listDependency.split(",");
            //     for (int i = 0; i < kCtx.getDeps().size(); i++) {
            //         Dependency dep = kCtx.getDeps().get(i);
            //         boolean found = false;
            //         for (int j = 0; j < listDependencyArray.length; j++) {
            //             if (dep.getName().equals(listDependencyArray[j])) {
            //                 logger.debug("Found !");
            //                 found = true;
            //                 break;
            //             }
            //         }
            //         if (!found) {
            //             kCtx.getDeps().remove(i);
            //         }
            //     }
            // }

            return kCtx.getMode().equals(Mode.ModeType.MODE_TEST);
        }
    }

    /**
     * This method gets executed after intercepting method {@link JedisPool#getResource()} irrespective of invocation of
     * intercepting method. Based on the return value of the {@link JedisPoolResource_Advice#enterMethods()} it provides
     * {@link Jedis} object as return value to the intercepting method.
     *
     * @param returned - the return object for intercepting method
     * @param enter - the value returned from {@link JedisPoolResource_Advice#enterMethods()}
     */
    @Advice.OnMethodExit()
    static void enterMethods(@Advice.Return(readOnly = false, typing = DYNAMIC) Object returned,
                             @Advice.Enter boolean enter ) {
        if(enter){
           returned =  new Jedis();
        }
    }
}
