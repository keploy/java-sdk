package io.keploy.advice;

import io.keploy.regression.Mode;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import io.keploy.googleMaps.CustomHttpResponses;
import net.bytebuddy.asm.Advice;
import okhttp3.Response;
import okio.BufferedSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.Objects;

public class CustomGoogleResponseAdvice {

    @Advice.OnMethodEnter
    static void enterMethods(@Advice.Origin Method method, @Advice.AllArguments Object[] obj) throws Exception {
        final Logger logger = LogManager.getLogger(CustomGoogleResponseAdvice.class);

        logger.debug("inside OnMethodEnterAdvice of CustomGoogleResponseAdvice for method: {}", method);
        Response response = (Response) obj[1];

        Kcontext kctx = Context.getCtx();

        if (kctx == null) {
            logger.debug("[CustomGoogleResponseAdvice] keploy context is null");
        } else if (kctx.getMode().getModeFromContext().equals(Mode.ModeType.MODE_RECORD)) {
            logger.debug("[CustomGoogleResponseAdvice] keploy mode: " + kctx.getMode());
            CustomHttpResponses.googleMapResponse = response;

            if (response.body() != null) {
                final BufferedSource source = Objects.requireNonNull(response.body()).source();
                source.request(Integer.MAX_VALUE);
                okio.ByteString snapshot = source.buffer().snapshot();
                String body = "";
                if (!response.body().contentType().type().contains("image")) {
                    logger.debug("not an image");
                    body = snapshot.utf8();
                }
                CustomHttpResponses.googleMapResBody = body;
            }
        } else if (kctx.getMode().getModeFromContext().equals(Mode.ModeType.MODE_OFF) || kctx.getMode().getModeFromContext().equals(Mode.ModeType.MODE_TEST)) {
            logger.debug("[CustomGoogleResponseAdvice] keploy mode: " + kctx.getMode());
        }
    }
}
