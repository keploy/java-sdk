package io.keploy.httpClients;

import com.google.protobuf.ByteString;
import io.keploy.grpc.stubs.Service;
import io.keploy.regression.KeployInstance;
import io.keploy.regression.Mock;
import io.keploy.regression.Mode;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import io.keploy.regression.keploy.Keploy;
import io.keploy.service.GrpcService;
import io.keploy.service.mock.Config;
import io.keploy.service.mock.MockLib;
import io.keploy.utils.MagicBytes;
import io.keploy.utils.MultipartContent;
import io.keploy.utils.Utility;
import lombok.SneakyThrows;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import org.apache.commons.io.IOUtils;
import org.apache.http.*;
import org.apache.http.client.methods.*;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.BufferedHttpEntity;
import org.apache.http.entity.ByteArrayEntity;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

import static io.keploy.utils.Utility.createFolder;
import static org.apache.http.entity.ContentType.APPLICATION_JSON;

public class ApacheInterceptor {


    private static final Logger logger = LogManager.getLogger(ApacheInterceptor.class);

    private static final String CROSS = new String(Character.toChars(0x274C));

    private static final Keploy k = KeployInstance.getInstance().getKeploy();


    public static CloseableHttpResponse doProceed(@Origin Method method, @SuperCall Callable<CloseableHttpResponse> callable, @AllArguments Object[] args) {

        logger.debug("inside ApacheInterceptor for method: " + method);

        HttpUriRequest request = null;

        for (Object obj : args) {
            if (obj instanceof HttpUriRequest) {
                request = (HttpUriRequest) obj;
            }
        }


        if (request == null) {
            logger.error(CROSS + " failed to fetch request");
            return null;
        }

//        HttpRequestWrapper wrapRequest = HttpRequestWrapper.wrap(request);

        Kcontext kctx = Context.getCtx();

        if (kctx == null) {
            logger.debug("failed to get keploy context");
            try {
                return callable.call();
            } catch (Exception e) {
                logger.error(CROSS + " unable to execute request", e);
                return null;
            }
        }

        Mode.ModeType modeFromContext = kctx.getMode().getModeFromContext();

        if (modeFromContext.equals(Mode.ModeType.MODE_OFF)) {
            try {
                return callable.call();
            } catch (Exception e) {
                logger.error(CROSS + " unable to execute request", e);
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
            logger.error(CROSS + " unable to set url for metadata", e);
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
                        logger.debug("test mode");

                        ByteString bin = mocks.get(0).getSpec().getObjectsList().get(0).getData();

                        Service.HttpResp httpResp = mocks.get(0).getSpec().getRes();
                        String respbody = httpResp.getBody();
                        long statusCode = httpResp.getStatusCode();
                        Map<String, Service.StrArr> headerMap = httpResp.getHeaderMap();
                        String statusMessage = httpResp.getStatusMessage();
                        long protoMajor = httpResp.getProtoMajor();
                        long protoMinor = httpResp.getProtoMinor();
                        String filePath = httpResp.getBinary();
                        String contentType = "";
                        if (headerMap.containsKey("Content-Type")) {
                            contentType = headerMap.get("Content-Type").getValue(0);
                        }

                        ContentType contentTypeEntity = getContentTypeEntity(contentType);

                        if (isBinaryFile(contentType)) {
                            byte[] fileData = getFileData(filePath);
                            response = new ApacheCustomHttpResponse(new ProtocolVersion("HTTP", (int) protoMajor, (int) protoMinor), (int) statusCode, statusMessage, fileData, contentTypeEntity);
                            setResponseHeaders(response, headerMap);
                        } else {
                            response = new ApacheCustomHttpResponse(new ProtocolVersion("HTTP", (int) protoMajor, (int) protoMinor), (int) statusCode, statusMessage, respbody);
                            setResponseHeaders(response, headerMap);
                        }
                        mocks.remove(0);
                    }
                    if (response == null) {
                        logger.error(CROSS + " unable to read response");
                        throw new RuntimeException("unable to read response");
                    }
                    return response;
                } else {
                    logger.error(CROSS + " mocks not present");
                    throw new RuntimeException("unable to read mocks from keploy context");
                }
            case MODE_RECORD:
                logger.debug("record mode");

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
                    Header c_t = request.getFirstHeader("Content-Type");
                    String contentType = "";
                    if (c_t != null) {
                        contentType = c_t.getValue();
                    }
                    if (!isBinaryFile(contentType)) {
                        reqBody = getRequestBody(request);
                    }
                    meta.put("Body", reqBody);
                } catch (IOException e) {
                    logger.error(CROSS + " unable to read request body", e);
                }

                try {
                    response = callable.call();
                } catch (Exception e) {
                    logger.error(CROSS + " unable to execute request", e);
                    return null;
                }


                String binaryFilePath = "";
                String responseBody = "";
                Header type = response.getFirstHeader("Content-Type");

                String contentType = "";
                if (type != null) {
                    contentType = type.getValue();
                }

                if (isBinaryFile(contentType)) {
                    MultipartContent fileInfo = getFileInfo(response, url);
                    binaryFilePath = fileInfo.getFileName();
                    // to improve performance
                    new Thread(() -> GrpcService.saveFile(fileInfo.getFileName(), fileInfo.getBody())).start();
                } else {
                    responseBody = getResponseBody(response);
                }

                int statusCode = response.getStatusLine().getStatusCode();
                String statusMsg = response.getStatusLine().getReasonPhrase();
                long ProtoMajor = response.getProtocolVersion().getMajor();
                long ProtoMinor = response.getProtocolVersion().getMinor();

                Map<String, Service.StrArr> resHeaders = getHeadersMap(response.getAllHeaders());

                Service.HttpResp httpResp = Service.HttpResp.newBuilder()
                        .setBody(responseBody)
                        .setBinary(binaryFilePath)
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
                        .setName(Config.Name)
                        .setSpec(specSchema)
                        .build();


                // for mock library
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
                try {
                    return callable.call();
                } catch (Exception e) {
                    logger.error(CROSS + " unable to send request", e);
                    return null;
                }
        }
    }

    private static MultipartContent getFileInfo(CloseableHttpResponse response, String url) {
        String assetsDirectory = k.getCfg().getApp().getAssetPath();

        //create asset folder if it doesn't exist
        createFolder(assetsDirectory);
        String file = "";
        byte[] fileBody = getFileDataFromStream(response);

        //TODO: handle naming of the file where you could find the fileName just use that name otherwise use asset-x.ext

        // from Content-Disposition header
        if (response.getFirstHeader("Content-Disposition") != null) {
            String content_disposition = response.getFirstHeader("Content-Disposition").getValue();
            String fname = Utility.getFileNameFromHeader(content_disposition);
            if (!fname.isEmpty()) {
                file = assetsDirectory + "/" + fname;
                logger.debug("getting file name from Content-Disposition");
            }
        } else {
            logger.debug("content-disposition header not found");
        }

        // from url
        if (file.isEmpty()) {
            //NOTE: guessing content-type from url is only for Amazon s3.
            String fileFromPath = Utility.getFileNameFromPath(url);
            String ext = Utility.getFileExtensionFromPath(url);
            if (!ext.isEmpty()) {
                file = assetsDirectory + "/" + fileFromPath;
                logger.debug("getting file name from url");
            } else {
                logger.debug("couldn't find name of file from url");
            }
        }

        // from magic numbers
        if (file.isEmpty()) {
            MagicBytes.Header matches = MagicBytes.matches(fileBody);
            if (matches != null) {
                String fileExt = MagicBytes.getContentType(matches);
                if (fileExt.isEmpty()) {
                    logger.warn("add support for {} in MagicBytes ", matches.getName());
                } else {
                    logger.debug("getting file extension from magic numbers of the file");
                    String fName = Utility.resolveFileName(assetsDirectory);
                    file = fName + "." + fileExt;
                }
            } else {
                logger.debug("couldn't find extension of file its body");
            }
        }

        //could not get file name or ext from any above method
        //hence saving its data in someFileName.data in base64 format
        if (file.isEmpty()) {
            String fileExt = "data";
            String fName = Utility.resolveFileName(assetsDirectory);
            file = fName + "." + fileExt;
            logger.debug("could not get file extension hence saving file with .data extension");
        }
        logger.debug("returning fileName from getFileInfo():{}", file);
        return new MultipartContent(file, fileBody);
    }

    private static byte[] getFileData(String filePath) {
        File file = new File(filePath);

        // Get the file path
        Path path = file.toPath();

        // Read the contents of the file into a byte array
        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(path);
        } catch (IOException e) {
            logger.error(CROSS + " unable to read data from file");
            throw new RuntimeException(e);
        }

        return fileBytes;
    }

    private static byte[] getFileDataFromStream(CloseableHttpResponse response) {
        byte[] resBody = new byte[0];
        String contentType = "";

        try {
            InputStream is = response.getEntity().getContent();
            resBody = EntityUtils.toByteArray(response.getEntity());
            is.close();
        } catch (IOException e) {
            logger.error(" unable to read file body from response", e);
            return resBody;
        }

        response.setEntity(new ByteArrayEntity(resBody));
        return resBody;
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
            if (response.getEntity() == null || response.getEntity().getContent() == null) {
                logger.debug("no response body found");
                return resBody;
            }
            resBody = EntityUtils.toString(response.getEntity());
        } catch (IOException e) {
            logger.error(" unable to read response body", e);
            return resBody;
        }

        try {
            response.setEntity(new StringEntity(resBody));
        } catch (UnsupportedEncodingException e) {
            logger.error(" unable to read response body", e);
            return resBody;
        }
        return resBody;
    }

    private static void setResponseHeaders(HttpResponse httpResponse, Map<String, Service.StrArr> srcMap) {
        Map<String, List<String>> headerMap = new HashMap<>();

        for (String key : srcMap.keySet()) {
            Service.StrArr values = srcMap.get(key);
            List<String> headerValues = new ArrayList<>(values.getValueList());
            headerMap.put(key, headerValues);
        }

        for (String key : headerMap.keySet()) {
            List<String> values = headerMap.get(key);
            // since checksum can be changed with little to no changes in the mock, therefore removing it while testing.
            if (isCheckSumHeader(key)) continue;
            for (String value : values) {
                httpResponse.addHeader(key, value);
            }
        }
    }

    private static boolean isCheckSumHeader(String checksum) {
        //TODO: add more checksums

        if (checksum.contains("ETag")) {
            return true;
        } else if (checksum.contains("crc32")) {
            return true;
        } else if (checksum.contains("sha256")) {
            return true;
        }

        return false;
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
//        BufferedHttpEntity bufferedHttpEntity;
        String actualBody = "";
        switch (METHOD) {
            case "POST":
                HttpPost httpPost = (HttpPost) request;
                HttpEntity postEntity = httpPost.getEntity();
                if (postEntity != null) {
                    reqStream = postEntity.getContent();
                    if (reqStream != null) {
                        actualBody = getActualRequestBody(reqStream);
                        httpPost.setEntity(new StringEntity(actualBody));
                    }
                }
                break;
            case "PUT":
                HttpPut httpPut = (HttpPut) request;
                HttpEntity putEntity = httpPut.getEntity();
                if (putEntity != null) {
                    reqStream = putEntity.getContent();
                    if (reqStream != null) {
                        actualBody = getActualRequestBody(reqStream);
                        httpPut.setEntity(new StringEntity(actualBody));
                    }
                }
                break;
            case "PATCH":
                HttpPatch httpPatch = (HttpPatch) request;
                HttpEntity patchEntity = httpPatch.getEntity();
                if (patchEntity != null) {
                    reqStream = patchEntity.getContent();
                    if (reqStream != null) {
                        actualBody = getActualRequestBody(reqStream);
                        httpPatch.setEntity(new StringEntity(actualBody));
                    }
                }
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
        return IOUtils.toString(reqArr, StandardCharsets.UTF_8.toString());
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
            case "image/jpg":
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

    private static boolean isBinaryFile(String resContentType) {

        switch (resContentType) {
            case "application/octet-stream":
            case "application/pdf":
            case "image/jpeg":
            case "image/jpg":
            case "image/png":
            case "image/gif":
            case "text/plain":
            case "text/html":
                return true;
            default:
                return false;
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

        //for binary file
        public ApacheCustomHttpResponse(ProtocolVersion ver, int statusCode, String statusMsg, byte[] body, ContentType contentType) {
            this(ver, statusCode, statusMsg);
            if (contentType.equals(ContentType.APPLICATION_OCTET_STREAM)) {
                setEntity(new ByteArrayEntity(body));
            } else {
                setEntity(new ByteArrayEntity(body, contentType));
            }
        }

        @Override
        public void close() {
        }
    }
}
