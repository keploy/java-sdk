package io.keploy.httpClients;

import com.google.protobuf.ByteString;
import com.google.protobuf.ProtocolStringList;
import io.keploy.grpc.stubs.Service;
import io.keploy.regression.KeployInstance;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import io.keploy.regression.Mock;
import io.keploy.regression.Mode;
import io.keploy.service.GrpcService;
import io.keploy.service.mock.Config;
import io.keploy.service.mock.MockLib;
import io.keploy.utils.HttpStatusReasons;
import okhttp3.*;
import okio.Buffer;
import okio.BufferedSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.*;

public class OkHttpInterceptor_Kotlin implements Interceptor {
    private static final Logger logger = LogManager.getLogger(OkHttpInterceptor_Kotlin.class);

    private static final String CROSS = new String(Character.toChars(0x274C));

    @Override
    public Response intercept(Chain chain) throws IOException {

        logger.debug("inside OkHttpInterceptor");

        Request request = chain.request();

        Kcontext kctx = Context.getCtx();

        if (kctx == null) {
            logger.debug("simulate call");
            return chain.proceed(request);
        }

        Mode.ModeType modeFromContext = kctx.getMode().getModeFromContext();

        if (modeFromContext.equals(Mode.ModeType.MODE_OFF)) {
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
                        logger.debug("test mode");

                        ByteString bin = mocks.get(0).getSpec().getObjectsList().get(0).getData();

                        Service.HttpResp httpResp = mocks.get(0).getSpec().getRes();
                        String body = httpResp.getBody();
                        long statusCode = httpResp.getStatusCode();
                        Map<String, Service.StrArr> headerMap = httpResp.getHeaderMap();
                        String statusMsg = httpResp.getStatusMessage();
                        MediaType mediaType = MediaType.parse("application/json; charset=utf-8");

//                        ResponseBody
//                                resBody = ResponseBody.create(body, mediaType);
                        ResponseBody
                                resBody = ResponseBody.create(mediaType, body);

                        final long protoMajor = httpResp.getProtoMajor();
                        final long protoMinor = httpResp.getProtoMinor();

                        Protocol protocol = getProtocol(protoMinor, protoMajor);

                        Response.Builder resBuilder = new Response.Builder().body(resBody)
                                .code((int) statusCode)
                                .message(statusMsg)
                                .request(request)
                                .protocol(protocol);
                        response = setResponseHeaders(resBuilder, headerMap);

                        //since okhttp request doesn't give protocol hence setting here.
                        meta.put("ProtoMajor", String.valueOf(protoMajor));
                        meta.put("ProtoMinor", String.valueOf(protoMinor));

                        mocks.remove(0);
                    }

                    if (response == null) {
                        logger.error(CROSS + " unable to read response");
                        throw new RuntimeException("unable to read response");
                    }

                    return response;
                } else {
                    logger.error(CROSS + " mocks not present in " + KeployInstance.getInstance().getKeploy().getCfg().getApp().getMockPath() + " directory.");
                    throw new RuntimeException("unable to read mocks from keploy context");
                }
            case MODE_RECORD:
                logger.debug("record mode");

                response = chain.proceed(request);
                String responseBody = getResponseBody(response);
                int statuscode = response.code();
                String statusMsg = HttpStatusReasons.getStatusMsg(statuscode);

                long[] protocol = getProtoVersion(response.protocol());
                long ProtoMinor = protocol[0];
                long ProtoMajor = protocol[1];

                Map<String, Service.StrArr> resHeaders = getHeadersMap(response.headers());

                Service.HttpResp httpResp = Service.HttpResp.newBuilder()
                        .setBody(responseBody)
                        .setStatusCode(statuscode)
                        .setStatusMessage(statusMsg)
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
                        .putAllHeader(getHeadersMap(request.headers()))
                        .putAllURLParams(getUrlParams(request))
                        .build();


                meta.put("ProtoMajor", String.valueOf(ProtoMajor));
                meta.put("ProtoMinor", String.valueOf(ProtoMinor));

                Service.Mock.Object obj = Service.Mock.Object.newBuilder().setType("error").setData(com.google.protobuf.ByteString.fromHex("")).build();
                List<Service.Mock.Object> lobj = new ArrayList<>();
                lobj.add(obj);

                Service.Mock.SpecSchema specSchema = Service.Mock.SpecSchema.newBuilder()
                        .setReq(httpReq)
                        .setRes(httpResp)
                        .putAllMetadata(meta)
                        .addAllObjects(lobj)
                        .build();

                Service.Mock httpMock = Service.Mock.newBuilder()
                        .setVersion(Mock.Version.V1_BETA1.value)
                        .setKind(Mock.Kind.HTTP_EXPORT.value)
                        .setName(Config.Name)
                        .setSpec(specSchema)
                        .build();

                // for mock library to work
                if (GrpcService.blockingStub != null && kctx.getFileExport() && !Config.MockId.containsKey(kctx.getTestId())) {
                    final boolean recorded = MockLib.PutMock(Config.MockPath, httpMock);
                    String CAPTURE = "\uD83D\uDFE0";
                    if (recorded) {
                        logger.info(CAPTURE + " Captured the mocked outputs for Http dependency call with meta: {}", meta);
                    }
                    return response;
                }

                kctx.getMock().add(httpMock);
                return response;
            default:
                logger.error(CROSS + " integrations: Not in a valid sdk mode");
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


//    private Map<String, List<String>> getHeadersMultimap(Headers headers) {
//
//        Map<String, List<String>> hmap = new HashMap<>();
//
//        for (Pair<? extends String, ? extends String> header : headers) {
//            String key = header.getFirst();
//            String value = header.getSecond();
//            hmap.computeIfAbsent(key, x -> new ArrayList<>()).add(value);
//        }
//
//        return hmap;
//    }

    private Map<String, Service.StrArr> getHeadersMap(Headers headers) {

        Map<String, List<String>> hmap = headers.toMultimap();

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
                logger.error(CROSS + " unable to read request body", e);
            }
        }
        return "";
    }

    private String getResponseBody(Response response) throws IOException {
        final BufferedSource source = Objects.requireNonNull(response.body()).source();
        source.request(Integer.MAX_VALUE);
        okio.ByteString snapshot = source.buffer().snapshot();
        return snapshot.utf8();
    }
}