package io.keploy.googleMaps;

import com.google.maps.ImageResult;
import com.google.maps.internal.OkHttpPendingResult;
import com.google.protobuf.ByteString;
import com.google.protobuf.ProtocolStringList;
import io.keploy.grpc.stubs.Service;
import io.keploy.regression.KeployInstance;
import io.keploy.regression.Mock;
import io.keploy.regression.Mode;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import io.keploy.service.GrpcService;
import io.keploy.service.mock.Config;
import io.keploy.service.mock.MockLib;
import io.keploy.utils.HttpStatusReasons;
import net.bytebuddy.implementation.bind.annotation.*;
import okhttp3.*;
import okio.Buffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;

public class GoogleMapsInterceptor {

    private static final Logger logger = LogManager.getLogger(GoogleMapsInterceptor.class);
    private static final String CROSS = new String(Character.toChars(0x274C));


    @RuntimeType
    public static Object execute(
            @SuperCall
            Callable<Object> client,
            @Origin String method, @FieldValue(value = "request") Request request, @This OkHttpPendingResult okHttpPendingResult) throws Exception {

        logger.debug("inside GoogleMapsInterceptor");

        Kcontext kctx = Context.getCtx();

        if (kctx == null) {
            logger.debug("keploy context is null");
            return client.call();
        }

        Mode.ModeType modeFromContext = kctx.getMode().getModeFromContext();

        if (modeFromContext.equals(Mode.ModeType.MODE_OFF)) {
            return client.call();
        }

        String reqBody = getRequestBody(request);

        Map<String, String> meta = new HashMap<>();

        meta.put("name", "GoogleMaps");
        meta.put("type", request.header("User-Agent"));
        meta.put("operation", request.method());
        meta.put("URL", request.url().toString());
        meta.put("Header", request.headers().toString());
        meta.put("Body", reqBody);

        Response response = null;

        switch (modeFromContext) {
            case MODE_TEST:  //don't call next when not in file export
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

                        String contentType = headerMap.containsKey("content-type") ? headerMap.get("content-type").getValue(0) : "application/json; charset=utf-8";
                        MediaType mediatype = MediaType.parse(contentType);

                        if (mediatype != null && mediatype.type().contains("image")) {
                            ByteArrayInputStream bis = new ByteArrayInputStream(bin.toByteArray());
                            ImageResult img = null;
                            try {
                                ObjectInputStream ois = new ObjectInputStream(bis);
                                img = (ImageResult) ois.readObject();
                            } catch (IOException | ClassNotFoundException e) {
                                logger.error(CROSS + " unable to deserialize dynamodb attribute");
                                throw new RuntimeException(e);
                            }
                            return img;
                        }

//                        ResponseBody
//                                resBody = ResponseBody.create(body, mediatype);
                        ResponseBody resBody = ResponseBody.create(mediatype, body);

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

                    //invoking private parseResponse method using java-reflection
                    Object responseObject = null;
                    Method[] declaredMethods = okHttpPendingResult.getClass().getDeclaredMethods();

                    for (Method declareM : declaredMethods) {
                        if (declareM.getName().equals("parseResponse")) {
                            declareM.setAccessible(true);
                            Object invoke = declareM.invoke(okHttpPendingResult, okHttpPendingResult, response);
                            responseObject = invoke;
                        }
                    }

                    return responseObject;
                } else {
                    logger.error(CROSS + " mocks not present in " + KeployInstance.getInstance().getKeploy().getCfg().getApp().getMockPath() + " directory.");
                    throw new RuntimeException("unable to read mocks from keploy context");
                }
            case MODE_RECORD:
                logger.debug("record mode");
                Object responseObject = client.call();


                response = CustomHttpResponses.googleMapResponse;
                String responseBody = CustomHttpResponses.googleMapResBody;

                if (response.body().contentType().type().contains("image")) {
                    responseBody = Base64.getEncoder().encodeToString(responseBody.getBytes(StandardCharsets.UTF_8));
                }

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


                List<Service.Mock.Object> lobj = new ArrayList<>();
                Service.Mock.Object obj = null;
                if (Objects.requireNonNull(response.body()).contentType().type().contains("image")) {
                    ImageResult img = (ImageResult) responseObject;
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try {
                        ObjectOutputStream oos = new ObjectOutputStream(baos);
                        oos.writeObject(img);
                    } catch (IOException e) {
                        logger.error(CROSS + " unable to serialize image object ");
                    }
                    obj = Service.Mock.Object.newBuilder().setType("imageData").setData(ByteString.copyFrom(baos.toByteArray())).build();
                } else {
                    obj = Service.Mock.Object.newBuilder().setType("error").setData(ByteString.fromHex("")).build();
                }

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
                        .setName("")
                        .setSpec(specSchema)
                        .build();

                // for mock library
                if (GrpcService.blockingStub != null && kctx.getFileExport() && !Config.MockId.containsKey(kctx.getTestId())) {
                    final boolean recorded = MockLib.PutMock(Config.MockPath, httpMock);
                    String CAPTURE = "\uD83D\uDFE0";
                    if (recorded) {
                        logger.info(CAPTURE + " Captured the mocked outputs for Http dependency call with meta: {}", meta);
                    }
                    return responseObject;
                }

                kctx.getMock().add(httpMock);
                return responseObject;
            default:
                logger.error(CROSS + " integrations: Not in a valid sdk mode");
                return client.call();
        }
    }

    private static String getRequestBody(Request request) {
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

    private static long[] getProtoVersion(Protocol protocol) {
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

    private static Protocol getProtocol(long protoMinor, long protoMajor) {

        if (protoMajor == 2) {
            return Protocol.HTTP_2;
        } else if (protoMajor == 1 && protoMinor == 1) {
            return Protocol.HTTP_1_1;
        } else {
            return Protocol.HTTP_1_0;
        }
    }

    private static Map<String, String> getUrlParams(Request request) {
        Map<String, String> map = new HashMap<>();

        for (String key : request.url().queryParameterNames()) {
            String value = request.url().queryParameterValues(key).get(0);
            map.put(key, value);
        }
        return map;
    }

    private static Response setResponseHeaders(Response.Builder resB, Map<String, Service.StrArr> srcMap) {
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


//    private static Map<String, List<String>> getHeadersMultimap(Headers headers) {
//
//        Map<String, List<String>> hmap = new HashMap<>();
//        for (Pair<? extends String, ? extends String> header : headers) {
//            String key = header.getFirst();
//            String value = header.getSecond();
//            hmap.computeIfAbsent(key, x -> new ArrayList<>()).add(value);
//        }
//
//        return hmap;
//    }

    private static Map<String, Service.StrArr> getHeadersMap(Headers headers) {

//        Map<String, List<String>> hmap = getHeadersMultimap(headers);
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
}
