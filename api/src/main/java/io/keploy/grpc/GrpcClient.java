package io.keploy.grpc;

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

    //    private static final Logger logger = Logger.getLogger(GrpcClient.class.getName());getName
    private static final Logger logger = LogManager.getLogger("GrpcClient");
    private RegressionServiceGrpc.RegressionServiceBlockingStub blockingStub;

    private ManagedChannel channel;

    private Keploy k;

    public GrpcClient() {
        this.channel = ManagedChannelBuilder.forTarget("localhost:8081")
                // Channels are secure by default (via SSL/TLS). For the example we disable TLS to avoid
                // needing certificates.
                .usePlaintext()
                .build();
        this.blockingStub = RegressionServiceGrpc.newBlockingStub(channel);
        this.k = KeployInstance.getInstance().getKeploy();
    }

//    public GrpcClient(Channel channel) {
//        this.blockingStub = RegressionServiceGrpc.newBlockingStub(channel);
//    }


    public List<Service.TestCase> fetch() {
        logger.info("inside fetch function");

        List<Service.TestCase> testCases = new ArrayList<>();
        for (int i = 0; ; i += 25) {
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
        }
        return testCases;

    }

    public void denoise(String id, Service.TestCaseReq testCaseReq) throws Exception {
        TimeUnit.SECONDS.sleep(3);
        Service.TestCase.Builder testCaseBuilder = Service.TestCase.newBuilder();
        testCaseBuilder.setId(id);
        testCaseBuilder.setCaptured(testCaseReq.getCaptured());
        testCaseBuilder.setURI(testCaseReq.getURI());
        testCaseBuilder.setHttpReq(testCaseReq.getHttpReq());
        Service.TestCase testCase = testCaseBuilder.build();

        Service.HttpResp resp2 = simulate(testCase);

        Service.TestReq.Builder testReqBuilder = Service.TestReq.newBuilder();
        testReqBuilder.setAppID(id);
        testReqBuilder.setResp(resp2);
        testReqBuilder.setAppID(k.getCfg().getApp().getName());
        Service.TestReq bin2 = testReqBuilder.build();

        // send de-noise request to server
        Service.deNoiseResponse deNoiseResponse = blockingStub.deNoise(bin2);
        System.out.println(deNoiseResponse.getMessage());
    }

    public Service.HttpResp simulate(Service.TestCase testCase) throws Exception {

        String url = testCase.getHttpReq().getURL();
        String host = k.getCfg().getApp().getHost();
        String port = k.getCfg().getApp().getPort();
        String method = testCase.getHttpReq().getMethod();
        String body = testCase.getHttpReq().getBody();

        String targetUrl = url;

        HttpRequest req = HttpRequest.newBuilder(URI.create(targetUrl)).setHeader("KEPLOY_TEST_ID", testCase.getId()).method(method, HttpRequest.BodyPublishers.ofString(body)).build();

        Map<String, List<String>> headerMap = req.headers().map();
        convertHeaderMap_StrArrToList(testCase.getHttpReq().getHeaderMap(), headerMap);

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response;
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
            convertHeaderMap_ListToStrArr(response.headers().map(), resp.getHeaderMap());
        }
        return resp.build();
    }

    //converting  Map<String,List<String>> to Map<String,Service.StrArr>
    public void convertHeaderMap_ListToStrArr(Map<String, List<String>> srcMap, Map<String, Service.StrArr> destMap) {
        Map<String, Service.StrArr> map = new HashMap<>();
        for (String key : srcMap.keySet()) {
            List<String> values = srcMap.get(key);
            Service.StrArr.Builder builder = Service.StrArr.newBuilder();
            for (int i = 0; i < values.size(); i++) {
                builder.addValue(values.get(i));
            }
            Service.StrArr value = builder.build();
            map.put(key, value);
        }
        destMap = map;
    }

    //converting  Map<String,Service.StrArr> to Map<String,List<String>>
    public void convertHeaderMap_StrArrToList(Map<String, Service.StrArr> srcMap, Map<String, List<String>> destMap) {

        for (String key : srcMap.keySet()) {
            var value = srcMap.get(key);
            List<String> headervalues = new ArrayList<>();
            for (int i = 0; ; i++) {
                try {
                    String v = value.getValue(i);
                    headervalues.add(v);
                } catch (Exception e) {
                    break;
                }
            }
            destMap.put(key, headervalues);
        }
    }

    public Service.HttpResp.Builder GetResp(String id) {

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
        }
        Service.HttpResp.Builder builder = Service.HttpResp.newBuilder();
        Map<String, Service.StrArr> headerMap = builder.getHeaderMap();

        headerMap = getResponseHeaderMap(httpServletResponse);
        Service.HttpResp.Builder httpRespBuilder = builder.setStatusCode(httpServletResponse.getStatus()).setBody(resBody);
        return httpRespBuilder;
    }

    public Map<String, Service.StrArr> getResponseHeaderMap(HttpServletResponse httpServletResponse) {

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

    public void Test() throws Exception {
        TimeUnit.SECONDS.sleep(5);
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
        logger.info("starting test execution " + "id: " + id + "total tests: " + total);
        boolean ok = false;
        for (int i = 0; i < tcs.size(); i++) {
            Service.TestCase tc = tcs.get(i);
            logger.info("testing " + (i + 1) + " of " + total + " testcase id : " + tc.getId());
            Service.TestCase tcCopy = tc;
            ok = check(id, tcCopy);
            logger.info("Result", "testcase id ", tcCopy.getId(), "passed ", ok);
        }
        String msg = end(id, ok);
        if (msg == null) {
            logger.error("failed to end test run");
            return;
        }
        logger.info("test run completed", "run id", id + "passed overall", ok);

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
        Map<String, String> urlParamsMap = httpReqBuilder.getURLParamsMap();
        urlParamsMap = params;

        Map<String, Service.StrArr> headerMap = httpReqBuilder.getHeaderMap();
        headerMap = getRequestHeaderMap(ctxReq);
        httpReqBuilder.setBody(reqBody);
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
        Service.postTCResponse postTCResponse = blockingStub.postTC(testCaseReq);
        Map<String, String> tcsId = postTCResponse.getTcsIdMap();
        String id = tcsId.get("id");

        if (id == null) return;
        denoise(id, testCaseReq);
    }


    public Map<String, Service.StrArr> getRequestHeaderMap(HttpServletRequest httpServletRequest) {

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

    public boolean check(String runId, Service.TestCase tc) throws Exception {
        Service.HttpResp resp = simulate(tc);
        Service.TestReq testReq = Service.TestReq.newBuilder().setID(tc.getId()).setAppID(k.getCfg().getApp().getName()).setRunID(runId).setResp(resp).build();
        Service.testResponse testResponse = blockingStub.test(testReq);
        Map<String, Boolean> res = testResponse.getPassMap();
        return res.get("pass");
    }

    private String getStringValue(byte[] contentAsByteArray, String characterEncoding) {
        try {
            return new String(contentAsByteArray, 0, contentAsByteArray.length, characterEncoding);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return "";
    }


//    public static void main(String[] args) {
////        String target = "localhost:8081";
//        ManagedChannel channel = ManagedChannelBuilder.forTarget("localhost:8081")
//                // Channels are secure by default (via SSL/TLS). For the example we disable TLS to avoid
//                // needing certificates.
//                .usePlaintext()
//                .build();
//
//        RegressionServiceGrpc.RegressionServiceBlockingStub stub = RegressionServiceGrpc.newBlockingStub(channel);
//
//        Service.startResponse startResponse = stub.start(Service.startRequest.newBuilder().setApp("test").setTotal("1").build());
//        System.out.println(startResponse + "This is the response I am getting !! ");
//        Service.endResponse endResponse = stub.end(Service.endRequest.newBuilder().setStatus("OK").setId("123").build());
//        System.out.println(endResponse + "This is the response I am getting !! ");
////        Service.TestCase testCase = stub.getTC(Service.getTCRequest.newBuilder().setApp("sample-url-shortener").setId("09a39684-c551-42a9-9b64-3f61815a8662").build());
////        System.out.println(testCase);
////       Service.getTCSResponse testcases =  stub.getTCS(Service.getTCSRequest.newBuilder().setApp("sample-url-shortener").setOffset("0").setLimit("25").build());
////        System.out.println(testcases);
//        GrpcClient k = new GrpcClient(channel);
//        List<Service.getTCSResponse> testCases = k.fetch();
//        System.out.println(testCases);
//        System.out.println(testCases.size());
//    }
}
