package io.keploy.httpClients;

import io.keploy.grpc.stubs.Service;
import io.keploy.regression.context.Context;
import io.keploy.regression.mode;
import io.keploy.service.GrpcService;
import io.keploy.utils.HaltThread;
import io.keploy.utils.Process;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okio.Buffer;
import okio.BufferedSource;
import okio.ByteString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class okhttp implements Interceptor {
    private static final Logger logger = LogManager.getLogger(okhttp.class);


    @NotNull
    @Override
    public Response intercept(@NotNull Chain chain) throws IOException {
        Request request = chain.request();

        mode.ModeType modeFromContext;
        try {
            modeFromContext = Context.getCtx().getMode().getModeFromContext();
        } catch (Exception e) {
            logger.error("failed to get context", e);
            throw new RuntimeException(e);
        }

        String reqBody = getRequestBody(request);

        if (modeFromContext.equals(mode.ModeType.MODE_OFF)) {
            return chain.proceed(request);
        }

        Map<String, String> meta = new HashMap<>();

        meta.put("name", "okhttp");
        meta.put("type", "HTTP_CLIENT");
        meta.put("operation", request.method());
        meta.put("URL", request.url().toString());
        meta.put("Header", request.headers().toString());
        meta.put("Body", reqBody);
        meta.put("Proto", "");
        meta.put("ProtoMajor", "");
        meta.put("ProtoMinor", "");


        Response response = null;

        switch (modeFromContext) {
            case MODE_TEST:  //don't call chain.proceed(request).
                break;
            case MODE_RECORD:
                response = chain.proceed(request);
            default:
                throw new RuntimeException("Integrations: Not in a valid sdk mode");
        }

//         = Process.ProcessDep(meta, response);
//
//        if (mock) {
//
//        }


        return response;
    }

    private String getRequestBody(Request request) {
        if (request.body() != null) {
            try {
                final Request copy = request.newBuilder().build();
                final Buffer buffer = new Buffer();
                copy.body().writeTo(buffer);
                return buffer.readUtf8();
            } catch (final IOException e) {
                System.out.println("Failed to stringify request body");
            }
        }
        return "";
    }

    private String getResponseBody(Response response) throws IOException {
        final BufferedSource source = response.body().source();
        source.request(Integer.MAX_VALUE);
        ByteString snapshot = source.getBuffer().snapshot();
        return snapshot.utf8();
    }
}
