package io.keploy.httpClients;

import com.google.protobuf.ProtocolStringList;
import io.keploy.grpc.stubs.Service;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import io.keploy.regression.Mock;
import io.keploy.regression.mode;
import okhttp3.*;
import okio.Buffer;
import okio.BufferedSource;
import okio.ByteString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

@Component
public class okhttp implements Interceptor {
    private static final Logger logger = LogManager.getLogger(okhttp.class);


    @NotNull
    @Override
    public Response intercept(@NotNull Chain chain) throws IOException {

        System.out.println("inside okhttp interceptor");
        Request request = chain.request();

        Kcontext kctx = Context.getCtx();

        mode.ModeType modeFromContext = kctx.getMode().getModeFromContext();

        if (modeFromContext.equals(mode.ModeType.MODE_OFF)) {
            return chain.proceed(request);
        }

        String reqBody = getRequestBody(request);

        Map<String, String> meta = new HashMap<>();

        meta.put("name", "okhttp");
        meta.put("type", "HTTP_CLIENT");
        meta.put("operation", request.method());
        meta.put("URL", request.url().toString());
        meta.put("Header", request.headers().toString());
        meta.put("Body", reqBody);

        Response response = null;

        switch (modeFromContext) {
            case MODE_TEST:  //don't call chain.proceed(request) when not in file export
                if (kctx.getMock().size() > 0 && kctx.getMock().get(0).getKind().equals(Mock.Kind.HTTP_EXPORT.value)) {
                    List<Service.Mock> mocks = kctx.getMock();
                    if (mocks.size() > 0 && mocks.get(0).getSpec().getObjectsCount() > 0) {
                        com.google.protobuf.ByteString bin = mocks.get(0).getSpec().getObjectsList().get(0).getData();

                        Service.HttpResp httpResp = mocks.get(0).getSpec().getRes();
                        String body = httpResp.getBody();
                        long statusCode = httpResp.getStatusCode();
                        Map<String, Service.StrArr> headerMap = httpResp.getHeaderMap();
                        String msg = httpResp.getStatusMessage();
//                        HttpStatus.valueOf((int) statusCode).getReasonPhrase();

                        ResponseBody
                                resBody = ResponseBody.create(body, MediaType.parse("application/json; charset=utf-8"));

                        final long protoMajor = httpResp.getProtoMajor();
                        final long protoMinor = httpResp.getProtoMinor();

                        Protocol protocol = getProtocol(protoMinor, protoMajor);

                        Response.Builder resBuilder = new Response.Builder().body(resBody)
                                .code((int) statusCode)
                                .message(msg)
                                .request(request)
                                .protocol(protocol);
                        response = setResponseHeaders(resBuilder, headerMap);

                        //since okhttp request doesn't give protocol hence setting here.
                        meta.put("ProtoMajor", String.valueOf(protoMajor));
                        meta.put("ProtoMinor", String.valueOf(protoMinor));

                        if (kctx.getFileExport()) {
                            logger.info("🤡 Returned the mocked outputs for Http dependency call with meta: {}", meta);
                        }
                        // add comment
                        mocks.remove(0);
                    }
                    assert response != null;
                    return response;
                }
            case MODE_RECORD:
                response = chain.proceed(request);
                String responseBody = getResponseBody(response);
                int statuscode = response.code();
                String msg = response.message();

                long[] protocol = getProtoVersion(response.protocol());
                long ProtoMinor = protocol[0];
                long ProtoMajor = protocol[1];

                Map<String, Service.StrArr> resHeaders = getHeadersMap(response.headers().toMultimap());

                Service.HttpResp httpResp = Service.HttpResp.newBuilder()
                        .setBody(responseBody)
                        .setStatusCode(statuscode)
                        .setStatusMessage(msg)
                        .setProtoMajor(ProtoMajor)
                        .setProtoMinor(ProtoMinor)
                        .putAllHeader(resHeaders)
                        .build();

                Service.HttpReq httpReq = Service.HttpReq.newBuilder()
                        .setMethod(request.method())
                        .setBody(reqBody)
                        .setURL(String.valueOf(request.url()))
                        .setProtoMajor(ProtoMajor)
                        .setProtoMinor(ProtoMinor)
                        .putAllHeader(getHeadersMap(request.headers().toMultimap()))
                        .putAllURLParams(getUrlParams(request))
                        .build();

                meta.put("ProtoMajor", String.valueOf(ProtoMajor));
                meta.put("ProtoMinor", String.valueOf(ProtoMinor));

                Service.Mock.SpecSchema specSchema = Service.Mock.SpecSchema.newBuilder()
                        .setReq(httpReq)
                        .setRes(httpResp)
                        .putAllMetadata(meta)
                        .build();

                //add enums for version, kind (refer models of keploy-server)

                Service.Mock httpMock = Service.Mock.newBuilder()
                        .setVersion(Mock.Version.V1_BETA1.value)
                        .setKind(Mock.Kind.HTTP_EXPORT.value)
                        .setName(kctx.getTestId())
                        .setSpec(specSchema)
                        .build();
                kctx.getMock().add(httpMock);
                return response;
            default:
                logger.error("integrations: Not in a valid sdk mode");
                return chain.proceed(request);
        }
    }

    private long[] getProtoVersion(Protocol protocol) {
        long[] proto = new long[2];
        String pname = protocol.name();
        if (pname.length() == 6) {
            proto[1] = Character.getNumericValue(pname.charAt(5));
        } else {
            proto[0] = Character.getNumericValue(pname.charAt(7));
            proto[1] = Character.getNumericValue(pname.charAt(5));
        }
        return proto;
    }

    private Protocol getProtocol(long protoMinor, long protoMajor) {

        if (protoMajor == 2) {
            return Protocol.HTTP_2;
        } else if (protoMajor == 1 && protoMinor == 1) {
            return Protocol.HTTP_1_1;
        } else {
            return Protocol.HTTP_1_0;
        }
    }

    private Map<String, String> getUrlParams(Request request) {
        Map<String, String> map = new HashMap<>();

        for (String key : request.url().queryParameterNames()) {
            String value = request.url().queryParameterValues(key).get(0);
            map.put(key, value);
        }
        return map;
    }

    private Response setResponseHeaders(Response.Builder resB, Map<String, Service.StrArr> srcMap) {
        Map<String, List<String>> headerMap = new HashMap<>();

        for (String key : srcMap.keySet()) {
            Service.StrArr values = srcMap.get(key);
            ProtocolStringList valueList = values.getValueList();
            List<String> headerValues = new ArrayList<>(valueList);
            headerMap.put(key, headerValues);
        }

        for (String key : headerMap.keySet()) {
            List<String> values = headerMap.get(key);
            for (String value : values) {
                resB.addHeader(key, value);
            }
        }
        return resB.build();
    }

    private Map<String, Service.StrArr> getHeadersMap(Map<String, List<String>> hmap) {
        Map<String, Service.StrArr> map = new HashMap<>();

        for (String name : hmap.keySet()) {

            List<String> values = hmap.get(name);
            Service.StrArr.Builder builder = Service.StrArr.newBuilder();

            for (String s : values) {
                builder.addValue(s);
            }
            Service.StrArr value = builder.build();

            map.put(name, value);
        }
        return map;
    }

    private String getRequestBody(Request request) {
        if (request.body() != null) {
            try {
                final Request copy = request.newBuilder().build();
                final Buffer buffer = new Buffer();
                Objects.requireNonNull(copy.body()).writeTo(buffer);
                return buffer.readUtf8();
            } catch (final IOException e) {
                logger.error("Unable to read request body", e);
            }
        }
        return "";
    }

    private String getResponseBody(Response response) throws IOException {
        final BufferedSource source = Objects.requireNonNull(response.body()).source();
        source.request(Integer.MAX_VALUE);
        ByteString snapshot = source.getBuffer().snapshot();
        return snapshot.utf8();
    }
}
