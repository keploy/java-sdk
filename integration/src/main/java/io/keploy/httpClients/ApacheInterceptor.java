package io.keploy.httpClients;

import com.google.protobuf.ByteString;
import com.google.protobuf.ProtocolStringList;
import io.keploy.grpc.stubs.Service;
import io.keploy.regression.Mock;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import io.keploy.regression.Mode;
import lombok.SneakyThrows;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import org.apache.http.*;
import org.apache.http.client.methods.*;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.BufferedHttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;

import static org.apache.http.entity.ContentType.APPLICATION_JSON;

public class ApacheInterceptor {


    private static final Logger logger = LogManager.getLogger(ApacheInterceptor.class);

    private static final String CROSS = new String(Character.toChars(0x274C));

    public static CloseableHttpResponse doProceed(@Origin Method method, @SuperCall Callable<CloseableHttpResponse> callable, @AllArguments Object[] args) {

        logger.debug("[ApacheInterceptor]: inside ApacheInterceptor for method: " + method);

        HttpUriRequest request = null;

        for (Object obj : args) {
            if (obj instanceof HttpUriRequest) {
                request = (HttpUriRequest) obj;
            }
        }


        if (request == null) {
            logger.error(CROSS + " [ApacheInterceptor]: failed to fetch request");
            return null;
        }

//        HttpRequestWrapper wrapRequest = HttpRequestWrapper.wrap(request);

        Kcontext kctx = Context.getCtx();

        if (kctx == null) {
            logger.debug("[ApacheInterceptor]: failed to get keploy context");
            try {
                return callable.call();
            } catch (Exception e) {
                logger.error(CROSS + " [ApacheInterceptor]: unable to execute request", e);
                return null;
            }
        }

        Mode.ModeType modeFromContext = kctx.getMode().getModeFromContext();

        if (modeFromContext.equals(Mode.ModeType.MODE_OFF)) {
            try {
                return callable.call();
            } catch (Exception e) {
                logger.error(CROSS + " [ApacheInterceptor]: unable to execute request", e);
                return null;
            }
        }

        Map<String, String> meta = new HashMap<>();

        meta.put("name", "apache");
        meta.put("type", "HTTP_CLIENT");
        meta.put("operation", request.getMethod());

        String url = "";
        try {
            url = request.getURI().toURL().toString();
        } catch (MalformedURLException e) {
            logger.error("[ApacheInterceptor]: unable to set url for metadata", e);
        }
        meta.put("URL", url);
        meta.put("Header", Arrays.toString(request.getAllHeaders()));
        meta.put("ProtoMajor", String.valueOf(request.getProtocolVersion().getMajor()));
        meta.put("ProtoMinor", String.valueOf(request.getProtocolVersion().getMinor()));

        CloseableHttpResponse response = null;

        switch (modeFromContext) {
            case MODE_TEST:
                if (kctx.getMock().size() > 0 && kctx.getMock().get(0).getKind().equals(Mock.Kind.HTTP_EXPORT.value)) {
                    List<Service.Mock> mocks = kctx.getMock();
                    if (mocks.size() > 0 && mocks.get(0).getSpec().getObjectsCount() > 0) {
                        logger.debug("[ApacheInterceptor]: test mode");

                        ByteString bin = mocks.get(0).getSpec().getObjectsList().get(0).getData();

                        Service.HttpResp httpResp = mocks.get(0).getSpec().getRes();
                        String respbody = httpResp.getBody();
                        long statusCode = httpResp.getStatusCode();
                        Map<String, Service.StrArr> headerMap = httpResp.getHeaderMap();
                        String statusMessage = httpResp.getStatusMessage();
                        long protoMajor = httpResp.getProtoMajor();
                        long protoMinor = httpResp.getProtoMinor();

                        response = new ApacheCustomHttpResponse(new ProtocolVersion("HTTP", (int) protoMajor, (int) protoMinor), (int) statusCode, statusMessage, respbody);
                        mocks.remove(0);
                    }
                    if (response == null) {
                        logger.error(CROSS + " [ApacheInterceptor]: unable to read response");
                        throw new RuntimeException("unable to read response");
                    }
                    return response;
                } else {
                    logger.error(CROSS + " [ApacheInterceptor]: mocks not present");
                    throw new RuntimeException("unable to read mocks from keploy context");
                }
            case MODE_RECORD:
                logger.debug("[ApacheInterceptor]: record mode");

//                wrapping request to re-read its body
//                try {
//                    setBufferEntity(request);
//                } catch (IOException e) {
//                    logger.error(CROSS + " [ApacheInterceptor]: unable to wrap the request", e);
//                }

//                try {
//                    response = callable.call();
//                } catch (Exception e) {
//                    logger.error(CROSS + " [ApacheInterceptor]: unable to execute request", e);
//                    return null;
//                }

                String reqBody = "";
                try {
                    reqBody = getRequestBody(request);
                    meta.put("Body", reqBody);
                } catch (IOException e) {
                    logger.error(CROSS + " [ApacheInterceptor]: unable to read request body", e);
                }

                try {
                    response = callable.call();
                } catch (Exception e) {
                    logger.error(CROSS + " [ApacheInterceptor]: unable to execute request", e);
                    return null;
                }

                String responseBody = getResponseBody(response);

                int statusCode = response.getStatusLine().getStatusCode();
                String statusMsg = response.getStatusLine().getReasonPhrase();
                long ProtoMajor = response.getProtocolVersion().getMajor();
                long ProtoMinor = response.getProtocolVersion().getMinor();

                Map<String, Service.StrArr> resHeaders = getHeadersMap(response.getAllHeaders());

                Service.HttpResp httpResp = Service.HttpResp.newBuilder()
                        .setBody(responseBody)
                        .setStatusCode(statusCode)
                        .setStatusMessage(statusMsg)
                        .setProtoMajor(ProtoMajor)
                        .setProtoMinor(ProtoMinor)
                        .putAllHeader(resHeaders)
                        .build();
                Service.HttpReq httpReq = Service.HttpReq.newBuilder()
                        .setMethod(request.getMethod())
                        .setBody(reqBody)
                        .setURL(url)
                        .setProtoMajor(ProtoMajor)
                        .setProtoMinor(ProtoMinor)
                        .putAllHeader(getHeadersMap(request.getAllHeaders()))
                        .putAllURLParams(getUrlParams(request))
                        .build();

                List<Service.Mock.Object> lobj = new ArrayList<>();
                Service.Mock.Object obj = Service.Mock.Object.newBuilder().setType("error").setData(com.google.protobuf.ByteString.fromHex("")).build();
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

                kctx.getMock().add(httpMock);
                return response;
            default:
                logger.error(CROSS + " integrations: Not in a valid sdk mode");
                try {
                    return callable.call();
                } catch (Exception e) {
                    logger.error(CROSS + " unable to send request", e);
                    return null;
                }
        }
    }

    private static Map<String, String> getUrlParams(HttpUriRequest request) {

        URIBuilder newBuilder = new URIBuilder(request.getURI());
        List<NameValuePair> params = newBuilder.getQueryParams();

        Map<String, String> map = new HashMap<>();

        for (NameValuePair nvp : params) {
            map.put(nvp.getName(), nvp.getValue());
        }
        return map;
    }

    private static Map<String, Service.StrArr> getHeadersMap(Header[] allHeaders) {

        Map<String, List<String>> headerMap = new HashMap<>();

        for (Header header : allHeaders) {
            String key = header.getName();
            String value = header.getValue();
            headerMap.computeIfAbsent(key, x -> new ArrayList<>()).add(value);
        }

        Map<String, Service.StrArr> map = new HashMap<>();
        for (String name : headerMap.keySet()) {

            List<String> values = headerMap.get(name);
            Service.StrArr.Builder builder = Service.StrArr.newBuilder();

            for (String s : values) {
                builder.addValue(s);
            }
            Service.StrArr value = builder.build();

            map.put(name, value);
        }
        return map;
    }

    private static HttpResponse getDummyResponse(String body, long statusCode, Map<String, Service.StrArr> headerMap, String msg, long protoMajor, long protoMinor) {
        final ProtocolVersion protocol = new ProtocolVersion("HTTP", (int) protoMajor, (int) protoMinor);

        HttpResponse response = new BasicHttpResponse(protocol, (int) statusCode, msg);
        response.setEntity(new StringEntity(body, APPLICATION_JSON));
        setResponseHeaders(response, headerMap);

        return response;
    }

    private static String getResponseBody(HttpResponse response) {

        String resBody = "";
        try {
            resBody = EntityUtils.toString(response.getEntity());
        } catch (IOException e) {
            logger.error(" [ApacheInterceptor]: unable to read response body", e);
            return resBody;
        }

        try {
            response.setEntity(new StringEntity(resBody));
        } catch (UnsupportedEncodingException e) {
            logger.error(" [ApacheInterceptor]: unable to read response body", e);
            return resBody;
        }
        return resBody;
    }

    private static void setResponseHeaders(HttpResponse httpResponse, Map<String, Service.StrArr> srcMap) {
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
                httpResponse.addHeader(key, value);
            }
        }
    }

    @SneakyThrows
    private static void setBufferEntity(HttpUriRequest request) throws IOException {
        String METHOD = request.getMethod();

        switch (METHOD) {
            case "POST":
                HttpPost httpPost = (HttpPost) request;
                ((HttpPost) request).setEntity(new BufferedHttpEntity(((HttpPost) request).getEntity()));
                break;
            case "PUT":
                HttpPut httpPut = (HttpPut) request;
                ((HttpPut) request).setEntity(new BufferedHttpEntity(((HttpPut) request).getEntity()));
                break;
            case "PATCH":
                HttpPatch httpPatch = (HttpPatch) request;
                ((HttpPatch) request).setEntity(new BufferedHttpEntity(((HttpPatch) request).getEntity()));
                break;
        }
    }

    @SneakyThrows
    private static String getRequestBody(HttpUriRequest request) throws IOException {

        String METHOD = request.getMethod();

        InputStream reqStream;
        BufferedHttpEntity bufferedHttpEntity;
        String actualBody;
        switch (METHOD) {
            case "POST":
                HttpPost httpPost = (HttpPost) request;
//                bufferedHttpEntity = new BufferedHttpEntity(httpPost.getEntity());
//                reqStream = bufferedHttpEntity.getContent();
                reqStream = httpPost.getEntity().getContent();
                actualBody = getActualRequestBody(reqStream);
                httpPost.setEntity(new StringEntity(actualBody));
                break;
            case "PUT":
                HttpPut httpPut = (HttpPut) request;
//                bufferedHttpEntity = new BufferedHttpEntity(httpPut.getEntity());
//                reqStream = bufferedHttpEntity.getContent();
                reqStream = httpPut.getEntity().getContent();
                actualBody = getActualRequestBody(reqStream);
                httpPut.setEntity(new StringEntity(actualBody));
                break;
            case "PATCH":
                HttpPatch httpPatch = (HttpPatch) request;
//                bufferedHttpEntity = new BufferedHttpEntity(httpPatch.getEntity());
//                reqStream = bufferedHttpEntity.getContent();
                reqStream = httpPatch.getEntity().getContent();
                actualBody = getActualRequestBody(reqStream);
                httpPatch.setEntity(new StringEntity(actualBody));
                break;
            default:
                return "";
        }


//
//        StringBuilder requestBody = new StringBuilder();
//        BufferedReader br = new BufferedReader(
//                new InputStreamReader(reqStream));
//
//        String responseLine;
//        while ((responseLine = br.readLine()) != null) {
//            requestBody.append(responseLine.trim());
//        }
//        String reqBody = requestBody.toString();
//        String mimeType = ((HttpEntityEnclosingRequestBase) request).getEntity().getContentType().getValue();
//        ContentType contentType = getContentTypeEntity(mimeType);
//        ((HttpEntityEnclosingRequestBase) request).setEntity(new StringEntity(reqBody, contentType));
        return actualBody;
    }

    private static String getActualRequestBody(InputStream reqStream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        org.apache.commons.io.IOUtils.copy(reqStream, baos);
        byte[] reqArr = baos.toByteArray();
        return getStringValue(reqArr, StandardCharsets.UTF_8.toString());
    }

    private static ContentType getContentTypeEntity(String contentType) {
        switch (contentType) {
//for application
            case "application/octet-stream":
                return ContentType.APPLICATION_OCTET_STREAM;
            case "application/x-www-form-urlencoded":
                return ContentType.APPLICATION_FORM_URLENCODED;
            case "application/json":
                return ContentType.APPLICATION_JSON;
//for multipart
            case "multipart/form-data":
                return ContentType.MULTIPART_FORM_DATA;
//for images
            case "image/gif":
                return ContentType.IMAGE_GIF;
            case "image/jpeg":
                return ContentType.IMAGE_JPEG;
            case "image/png":
                return ContentType.IMAGE_PNG;
//for text
            case "text/html":
                return ContentType.TEXT_HTML;
            case "text/xml":
                return ContentType.TEXT_XML;
            default:
                return ContentType.DEFAULT_TEXT;
        }
    }

    private static class ApacheCustomHttpResponse extends BasicHttpResponse implements CloseableHttpResponse {

        public ApacheCustomHttpResponse(ProtocolVersion ver, int code, String reason) {
            super(ver, code, reason);
        }

        public ApacheCustomHttpResponse(ProtocolVersion ver, int statusCode, String statusMsg, String body) {
            this(ver, statusCode, statusMsg);
            setEntity(new StringEntity(body, APPLICATION_JSON));
        }

        @Override
        public void close() {
        }
    }

    private static String getStringValue(byte[] contentAsByteArray, String characterEncoding) {
        try {
            return new String(contentAsByteArray, 0, contentAsByteArray.length, characterEncoding);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return "";
    }
}
