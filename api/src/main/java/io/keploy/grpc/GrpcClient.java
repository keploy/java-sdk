package io.keploy.grpc;

import com.google.protobuf.ProtocolStringList;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.keploy.grpc.stubs.RegressionServiceGrpc;
import io.keploy.grpc.stubs.Service;
import io.keploy.regression.KeployInstance;
import io.keploy.regression.context.Context;
import io.keploy.regression.keploy.Keploy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class GrpcClient {

    private static final Logger logger = LogManager.getLogger("GrpcClient");
    private final RegressionServiceGrpc.RegressionServiceBlockingStub blockingStub;
    private final Keploy k;

    public GrpcClient() {
        // Channels are secure by default (via SSL/TLS). For the example we disable TLS to avoid
        // needing certificates.
        ManagedChannel channel = ManagedChannelBuilder.forTarget("localhost:8081")
                .usePlaintext()
                .build();
        this.blockingStub = RegressionServiceGrpc.newBlockingStub(channel);
        this.k = KeployInstance.getInstance().getKeploy();
    }

    public void CaptureTestCases(KeployInstance ki, String reqBody, String resBody, Map<String, String> params, Service.HttpResp httpResp) throws Exception {

        HttpServletRequest ctxReq = Context.getCtx();
        if (ctxReq == null) {
            logger.warn("failed to get keploy context");
            return;
        }

        Service.TestCaseReq.Builder testCaseReqBuilder = Service.TestCaseReq.newBuilder();

        Service.HttpReq.Builder httpReqBuilder = Service.HttpReq.newBuilder();
        httpReqBuilder.setMethod(ctxReq.getMethod()).setURL(ctxReq.getRequestURL().toString());
        Map<String, String> urlParamsMap = params;
//        urlParamsMap = params;
        httpReqBuilder.putAllURLParams(urlParamsMap);
        System.out.println("urlParamsMap---> " + urlParamsMap);
        Map<String, Service.StrArr> headerMap = getRequestHeaderMap(ctxReq);
        httpReqBuilder.putAllHeader(headerMap);
        System.out.println("headerMap----> " + headerMap);
        httpReqBuilder.setBody(reqBody);
        httpReqBuilder.setProtoMajor(2);
        httpReqBuilder.setProtoMinor(1);

        Service.HttpReq httpReq = httpReqBuilder.build();


        testCaseReqBuilder.setAppID(k.getCfg().getApp().getName());
        testCaseReqBuilder.setCaptured(Instant.now().getEpochSecond());
        testCaseReqBuilder.setURI(ctxReq.getRequestURI());
        testCaseReqBuilder.setHttpResp(httpResp);
        testCaseReqBuilder.setHttpReq(httpReq);

        Capture(testCaseReqBuilder.build());
    }

    public void Capture(Service.TestCaseReq testCaseReq) throws Exception {
        put(testCaseReq);
    }

    public void put(Service.TestCaseReq testCaseReq) throws Exception {
        Service.postTCResponse postTCResponse = null;
        try {
            postTCResponse = blockingStub.postTC(testCaseReq);
        } catch (Exception e) {
            logger.error("failed to send test case to backend", e);
            throw new Exception();
        }
        Map<String, String> tcsId = postTCResponse.getTcsIdMap();
        String id = tcsId.get("id");

        if (id == null) return;

        denoise(id, testCaseReq);
    }

    public void denoise(String id, Service.TestCaseReq testCaseReq) throws Exception {
        // run the request again to find noisy fields
        TimeUnit.SECONDS.sleep(3);

        Service.TestCase.Builder testCaseBuilder = Service.TestCase.newBuilder();
        testCaseBuilder.setId(id);
        testCaseBuilder.setCaptured(testCaseReq.getCaptured());
        testCaseBuilder.setURI(testCaseReq.getURI());
        testCaseBuilder.setHttpReq(testCaseReq.getHttpReq());
        Service.TestCase testCase = testCaseBuilder.build();

        Service.HttpResp resp2 = simulate(testCase);

        Service.TestReq.Builder testReqBuilder = Service.TestReq.newBuilder();
        testReqBuilder.setID(id);
        testReqBuilder.setResp(resp2);
        testReqBuilder.setAppID(k.getCfg().getApp().getName());
        Service.TestReq bin2 = testReqBuilder.build();

        // send de-noise request to server
        try {
            Service.deNoiseResponse deNoiseResponse = blockingStub.deNoise(bin2);
            System.out.println(deNoiseResponse.getMessage());
        } catch (Exception e) {
            logger.error("failed to send de-noise request to backend");
        }
    }

    public Service.HttpResp simulate(Service.TestCase testCase) throws Exception {

        String targetUrl = testCase.getHttpReq().getURL();
        String host = k.getCfg().getApp().getHost();
        String port = k.getCfg().getApp().getPort();
        String method = testCase.getHttpReq().getMethod();
        String body = testCase.getHttpReq().getBody();


        HttpRequest.Builder headerBuilder = setReqHeaderMap(testCase.getHttpReq().getHeaderMap(), HttpRequest.newBuilder());
        final HttpRequest req = headerBuilder.uri(URI.create(targetUrl)).setHeader("KEPLOY_TEST_ID", testCase.getId()).setHeader("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body)).build();

        System.out.println("Simulate : req Header map \n " + req.headers().map());
//        final Map<String, List<String>> headerMap = convertHeaderMap_StrArrToList(testCase.getHttpReq().getHeaderMap(), new HashMap<>());

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> response = null;
        try {
            response = client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.info("failed sending testcase request to app");
            throw new Exception(e);
        }
        Service.HttpResp.Builder resp = GetResp(testCase.getId());

        if ((resp.getStatusCode() < 300 || resp.getStatusCode() >= 400) && !resp.getBody().equals(response.body())) {
            resp.setBody(response.body());
            resp.setStatusCode(response.statusCode());
//            convertHeaderMap_ListToStrArr(response.headers().map(), resp.getHeaderMap());
        }
        var res = response.headers().map();
        Map<String, Service.StrArr> resHeader = convertHeaderMap_ListToStrArr(res);
        logger.info("inside simulate after converting resHeader");
        resp.putAllHeader(resHeader);
        System.out.println("response headers map : " + response.headers().map());
        System.out.println("response get Headers : " + resp.getHeaderMap());
        return resp.build();
    }

    public Service.HttpResp.Builder GetResp(String id) throws Exception {

        HttpServletResponse httpServletResponse = k.getResp().get(id);
        if (httpServletResponse == null) {
            return Service.HttpResp.newBuilder();
        }


        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(httpServletResponse);

        String resBody = "";
        try {
            resBody = this.getStringValue(responseWrapper.getContentAsByteArray(), httpServletResponse.getCharacterEncoding());
            responseWrapper.copyBodyToResponse();
        } catch (Exception e) {
            logger.error("Failed to get response ", e.getMessage());
            throw new Exception(e);
        }
        Service.HttpResp.Builder builder = Service.HttpResp.newBuilder();
        Map<String, Service.StrArr> headerMap = builder.getHeaderMap();

        headerMap = getResponseHeaderMap(httpServletResponse);
        Service.HttpResp.Builder httpRespBuilder = builder.setStatusCode(httpServletResponse.getStatus()).setBody(resBody);
        return httpRespBuilder;
    }

    public void Test() throws Exception {
        TimeUnit.SECONDS.sleep(5);
        System.out.println("Hi in the test mode");
        logger.info("test starting in 5 sec");

        List<Service.TestCase> tcs = fetch();
        int total = tcs.size();
        String id;
        try {
            id = start(String.valueOf(total));
        } catch (Exception e) {
            logger.info("Failed to start test run ", e);
            return;
        }
        logger.info("starting test execution " + "id: " + id + " total tests: " + total);
        boolean ok = false;
        for (int i = 0; i < tcs.size(); i++) {
            Service.TestCase tc = tcs.get(i);
            logger.info("testing " + (i + 1) + " of " + total + " testcase id : " + tc.getId());
            Service.TestCase tcCopy = tc;
            ok = check(id, tcCopy);
            logger.info("Result : " + " testcase id " + tcCopy.getId() + " passed ", ok);
        }
        String msg = end(id, ok);
        if (msg == null) {
            logger.error("failed to end test run");
            return;
        }
        logger.info("test run completed : " + " run id " + id + "\n passed overall " + ok);
    }

    public String start(String total) {
        Service.startRequest startRequest = Service.startRequest.newBuilder().setApp(k.getCfg().getApp().getName()).setTotal(total).build();
        Service.startResponse startResponse = blockingStub.start(startRequest);
        return startResponse.getId();
    }

    public String end(String id, boolean status) {
        Service.endRequest endRequest = Service.endRequest.newBuilder().setId(id).setStatus(String.valueOf(status)).build();
        Service.endResponse endResponse = blockingStub.end(endRequest);
        return endResponse.getMessage();
    }

    public List<Service.TestCase> fetch() {
        logger.info("inside fetch function");

        List<Service.TestCase> testCases = new ArrayList<>();
        int i = 0;
        while (true) {
            try {
                Service.getTCSRequest tcsRequest = Service.getTCSRequest.newBuilder().setApp(k.getCfg().getApp().getName()).setLimit("25").setOffset(String.valueOf(i)).build();
                Service.getTCSResponse tcs = blockingStub.getTCS(tcsRequest);
                int cnt = tcs.getTcsCount();
                if (cnt == 0) {
                    break;
                }
                List<Service.TestCase> tc = tcs.getTcsList();
                testCases.addAll(tc);
            } catch (StatusRuntimeException e) {
                logger.warn("RPC failed: {0}", e.getStatus());
                return null;
            }
            i += 25;
        }
        return testCases;

    }

    public boolean check(String runId, Service.TestCase tc) throws Exception {
        Service.HttpResp resp = simulate(tc);
        Service.TestReq testReq = Service.TestReq.newBuilder().setID(tc.getId()).setAppID(k.getCfg().getApp().getName()).setRunID(runId).setResp(resp).build();
        Service.testResponse testResponse = blockingStub.test(testReq);
        Map<String, Boolean> res = testResponse.getPassMap();
        System.out.println(res);
        return res.get("pass");
    }

    //converting  Map<String,List<String>> to Map<String,Service.StrArr>
    private Map<String, Service.StrArr> convertHeaderMap_ListToStrArr(Map<String, List<String>> srcMap) {
        Map<String, Service.StrArr> map = new HashMap<>();
        for (String key : srcMap.keySet()) {
            List<String> headerValues = srcMap.get(key);
            Service.StrArr.Builder builder = Service.StrArr.newBuilder();
            for (int i = 0; i < headerValues.size(); i++) {
                builder.addValue(headerValues.get(i));
            }
            Service.StrArr value = builder.build();
            map.put(key, value);
        }
//        destMap.putAll(map);
        System.out.println("This is converted MAp : " + map);
        return map;
    }

    private HttpRequest.Builder setReqHeaderMap(Map<String, Service.StrArr> srcMap, HttpRequest.Builder reqBuilder) {
//        for(String key:srcMap.keySet()){
//             ProtocolStringList valueList = srcMap.get(key).getValueList();
//             for(String val:valueList){
//                 reqBuilder
//             }
//        }
        Map<String,List<String>>headerMap = new HashMap<>();

        for(String key:srcMap.keySet()){
            Service.StrArr values = srcMap.get(key);
            List<String> headerValues = new ArrayList<>();
            ProtocolStringList valueList = values.getValueList();
            for(String val:valueList){
                headerValues.add(val);
            }
            headerMap.put(key,headerValues);
        }

        headerMap.forEach((headerKey,headerValues)->headerValues.forEach(value->reqBuilder.header(headerKey,value))) ;
        return reqBuilder;
    }
//    private Map<String, List<String>> convertHeaderMap_StrArrToList(Map<String, Service.StrArr> srcMap, Map<String, List<String>> destMap) {
//
//        System.out.println(srcMap);
//
//        for (String key : srcMap.keySet()) {
//            Service.StrArr values = srcMap.get(key);
//            List<String> headervalues = new ArrayList<>();
//            int i = 0;
//            ProtocolStringList valueList = values.getValueList();
//            for (String val : valueList) {
//                headervalues.add(val);
//
//            }
//            destMap.put(key, headervalues);
//        }
//        return destMap;
//    }

    private Map<String, Service.StrArr> getResponseHeaderMap(HttpServletResponse httpServletResponse) {

        Map<String, Service.StrArr> map = new HashMap<>();
        List<String> headerNames = httpServletResponse.getHeaderNames().stream().collect(Collectors.toList());

        for (String name : headerNames) {

            List<String> values = httpServletResponse.getHeaders(name).stream().collect(Collectors.toList());
            Service.StrArr.Builder builder = Service.StrArr.newBuilder();

            for (int i = 0; i < values.size(); i++) {
                builder.addValue(values.get(i));
            }
            Service.StrArr value = builder.build();

            map.put(name, value);
        }
        return map;
    }

    private Map<String, Service.StrArr> getRequestHeaderMap(HttpServletRequest httpServletRequest) {

        Map<String, Service.StrArr> map = new HashMap<>();

        List<String> headerNames = Collections.list(httpServletRequest.getHeaderNames());
        for (String name : headerNames) {

            List<String> values = Collections.list(httpServletRequest.getHeaders(name));
            Service.StrArr.Builder builder = Service.StrArr.newBuilder();

            for (int i = 0; i < values.size(); i++) {
                builder.addValue(values.get(i));
            }
            Service.StrArr value = builder.build();

            map.put(name, value);
        }
        return map;
    }

    private String getStringValue(byte[] contentAsByteArray, String characterEncoding) {
        try {
            return new String(contentAsByteArray, 0, contentAsByteArray.length, characterEncoding);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return "";
    }
}
