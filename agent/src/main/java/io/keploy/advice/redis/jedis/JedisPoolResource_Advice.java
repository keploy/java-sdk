package io.keploy.advice.redis.jedis;

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
