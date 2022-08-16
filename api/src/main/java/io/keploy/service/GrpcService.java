package io.keploy.service;

import com.google.protobuf.ProtocolStringList;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.keploy.grpc.stubs.RegressionServiceGrpc;
import io.keploy.grpc.stubs.Service;
import io.keploy.regression.KeployInstance;
import io.keploy.regression.context.Context;
import io.keploy.regression.keploy.Keploy;
import io.keploy.utils.HaltThread;
import okhttp3.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class GrpcService {

    private static final Logger logger = LogManager.getLogger(GrpcService.class);
    private final RegressionServiceGrpc.RegressionServiceBlockingStub blockingStub;
    private final Keploy k;

    public static ManagedChannel channel;

    public GrpcService() {
        // Channels are secure by default (via SSL/TLS). For the example we disable TLS to avoid
        // needing certificates.
        channel = ManagedChannelBuilder.forTarget("localhost:8081")
                .usePlaintext()
                .build();
        this.blockingStub = RegressionServiceGrpc.newBlockingStub(channel);
        this.k = KeployInstance.getInstance().getKeploy();
    }

    public void CaptureTestCases(KeployInstance ki, String reqBody, Map<String, String> params, Service.HttpResp httpResp) throws Exception {
        logger.debug("inside CaptureTestCases");

        HttpServletRequest ctxReq = Context.getCtx().getRequest();
        if (ctxReq == null) {
            logger.warn("failed to get keploy context");
            return;
        }

        Service.TestCaseReq.Builder testCaseReqBuilder = Service.TestCaseReq.newBuilder();

        Service.HttpReq.Builder httpReqBuilder = Service.HttpReq.newBuilder();
        String url = ctxReq.getQueryString() == null ? ctxReq.getRequestURI() :
                ctxReq.getRequestURI() + "?" + ctxReq.getQueryString();

        httpReqBuilder.setMethod(ctxReq.getMethod()).setURL(url);
        httpReqBuilder.putAllURLParams(params);
        Map<String, Service.StrArr> headerMap = getRequestHeaderMap(ctxReq);
        httpReqBuilder.putAllHeader(headerMap);
        httpReqBuilder.setBody(reqBody);
        httpReqBuilder.setProtoMajor(2);
        httpReqBuilder.setProtoMinor(1);

        Service.HttpReq httpReq = httpReqBuilder.build();

        testCaseReqBuilder.setAppID(k.getCfg().getApp().getName());
        testCaseReqBuilder.setCaptured(Instant.now().getEpochSecond());

        /*
         * The order of path parameters, we are getting from request is not proper.
         * Storing in different order will not block the existing functionality.
         * It's only for grouping the testcases.
         * Below code gives unordered mapping of path variables or path parameters
         * Map<String, String> pathVariables = ((Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE));
         * Hence we are storing the actual Uri not according to the routing patter.
         * */

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

        boolean noise = k.getCfg().getServer().getDenoise();

        if (noise) {
            try {
                Service.deNoiseResponse deNoiseResponse = blockingStub.deNoise(bin2);
                logger.debug("denoise message received from server {}", deNoiseResponse.getMessage());
            } catch (Exception e) {
                logger.error("failed to send de-noise request to backend", e);
            }
        }
    }

    public Service.HttpResp simulate(Service.TestCase testCase) throws Exception {
        logger.debug("inside simulate");

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.MINUTES) // connect timeout
                .writeTimeout(5, TimeUnit.MINUTES) // write timeout
                .readTimeout(5, TimeUnit.MINUTES) // read timeout
                .build();


        String simResBody = null;
        long statusCode = 0;
        final Map<String, List<String>> responseHeaders = new HashMap<>();

        Request request = getCustomRequest(testCase);
        logger.debug("simulate request: {}", request);

        try (Response response = client.newCall(request).execute()) {

            try (ResponseBody responseBody = response.body()) {
                if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
                assert responseBody != null;
                simResBody = responseBody.string();
            }

            Map<String, List<String>> resHeadMap = response.headers().toMultimap();

            for (String key : resHeadMap.keySet()) {
                List<String> vals = resHeadMap.get(key);
                List<String> values = new ArrayList<>(vals);
                responseHeaders.put(key, values);
            }
            statusCode = response.code();
        } catch (IOException e) {
            logger.error("failed sending testcase request to app", e);
        }

        Service.HttpResp.Builder resp = GetResp(testCase.getId());
        if ((resp.getStatusCode() < 300 || resp.getStatusCode() >= 400) && !resp.getBody().equals(simResBody)) {
            resp.setBody(simResBody);
            resp.setStatusCode(statusCode);
            Map<String, Service.StrArr> resHeaders = getResponseHeaderMap(responseHeaders);

            logger.debug("response headers from GetResp: {}", resHeaders);
            try {
                resp.putAllHeader(resHeaders);
            } catch (Exception e) {
                logger.error("unable to put headers", e);
            }
        }

        return resp.build();
    }

    public Service.HttpResp.Builder GetResp(String id) throws Exception {
        logger.debug("inside GetResp");
        Service.HttpResp httpResp = k.getResp().get(id);
        if (httpResp == null) {
            logger.debug("response is not present in keploy resp map");
            return Service.HttpResp.newBuilder();
        }
        Service.HttpResp.Builder respBuilder = Service.HttpResp.newBuilder();

        try {
            respBuilder.setBody(httpResp.getBody()).setStatusCode(httpResp.getStatusCode()).putAllHeader(httpResp.getHeaderMap());
        } catch (Exception e) {
            logger.error("failed to get response", e);
            throw new Exception(e);
        }

        logger.debug("response from keploy resp map");
        return respBuilder;
    }

    public void Test() throws Exception {
        TimeUnit.SECONDS.sleep(7);
        logger.debug("entering test mode");
        logger.info("test starting in 5 sec");

        List<Service.TestCase> tcs = fetch();
        int total = tcs.size();
        String id;
        try {
            id = start(String.valueOf(total));
        } catch (Exception e) {
            logger.info("failed to start test run ", e);
            return;
        }
        logger.info("starting test execution id: {} total tests: {}", id, total);
        AtomicBoolean ok = new AtomicBoolean(true);

        CountDownLatch wg = null;
        ExecutorService service = Executors.newFixedThreadPool(10);
        // call the service for each test case

        for (int i = 0; i < tcs.size(); i++) {
            Service.TestCase tc = tcs.get(i);
            logger.info("testing {} of {} testcase id: [{}]", (i + 1), total, tc.getId());
            wg = new CountDownLatch(1);
            CountDownLatch finalWg = wg;

            service.submit(() -> {
                boolean pass;
                try {
                    pass = check(id, tc);
                    ok.set(ok.get() & pass);
                } catch (Exception e) {
                    logger.warn("unable to test with testcase id:[{}]", tc.getId());
                    throw new RuntimeException(e);
                }
                logger.info("result : testcase id: [{}]  passed: {}", tc.getId(), pass);
                finalWg.countDown();
            });
        }

        if (wg != null) {
            wg.await();
        }

        String msg = end(id, ok.get());
        if (msg == null) {
            logger.error("failed to end test run");
            return;
        }
        logger.info("test run completed with run id [{}]", id);
        logger.info("|| passed overall: {} ||", String.valueOf(ok.get()).toUpperCase());
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
                logger.warn("RPC failed: {}", e.getStatus());
                return null;
            }
            i += 25;
        }

        //reverse in order to get testcases in which they were recorded.
        Collections.reverse(testCases);
        return testCases;
    }

    public boolean check(String testrunId, Service.TestCase tc) throws Exception {
        logger.debug("running test case with [{}] testrunId ", testrunId);

        Service.HttpResp resp = simulate(tc);
        Service.TestReq testReq = Service.TestReq.newBuilder().setID(tc.getId()).setAppID(k.getCfg().getApp().getName()).setRunID(testrunId).setResp(resp).build();
        Service.testResponse testResponse = blockingStub.test(testReq);
        Map<String, Boolean> res = testResponse.getPassMap();
        logger.info("test result of testrunId [{}]: {} ", testrunId, res.get("pass"));
        return res.get("pass");
    }

    private Map<String, Service.StrArr> getResponseHeaderMap(Map<String, List<String>> srcMap) {

        Map<String, Service.StrArr> map = new HashMap<>();
        for (String key : srcMap.keySet()) {

            if (key == null) continue;
            List<String> headerValues = srcMap.get(key);

            Service.StrArr.Builder builder = Service.StrArr.newBuilder();
            for (String hval : headerValues) {
                builder.addValue(hval);
            }
            Service.StrArr value = builder.build();
            key = convertFirstCapAfterEachDash(key);
            map.put(key, value);
        }
        return map;
    }

    private String convertFirstCapAfterEachDash(String str) {
        StringBuilder sb = new StringBuilder();
        String[] sarr = str.split("-");
        if (sarr.length == 1) {
            sb.append(Character.toUpperCase(sarr[0].charAt(0))).append(sarr[0].substring(1));
        } else {
            for (int i = 0; i < sarr.length - 1; i++) {
                String val = sarr[i];
                sb.append(Character.toUpperCase(val.charAt(0))).append(val.substring(1)).append("-");
            }
            String lval = sarr[sarr.length - 1];
            sb.append(Character.toUpperCase(lval.charAt(0))).append(lval.substring(1));
        }
        return sb.toString();
    }

    private Request getCustomRequest(Service.TestCase testCase) {

        String url = testCase.getHttpReq().getURL();
        String host = k.getCfg().getApp().getHost();
        String port = k.getCfg().getApp().getPort();
        String method = testCase.getHttpReq().getMethod();
        String body = testCase.getHttpReq().getBody();
        String targetUrl = "http://" + host + ":" + port + url;

        logger.debug("simulate request's url: {}", targetUrl);
        Map<String, Service.StrArr> headerMap = testCase.getHttpReq().getHeaderMap();

        Request.Builder reqBuilder = setCustomRequestHeaderMap(headerMap);

        switch (method) {
            case "GET":
                return reqBuilder.get()
                        .url(targetUrl)
                        .addHeader("content-type", "application/json")
                        .addHeader("accept", "application/json")
                        .addHeader("KEPLOY_TEST_ID", testCase.getId()).build();
            case "DELETE":
                return reqBuilder.delete()
                        .url(targetUrl)
                        .addHeader("content-type", "application/json")
                        .addHeader("accept", "application/json")
                        .addHeader("KEPLOY_TEST_ID", testCase.getId()).build();
            default:
                return reqBuilder.method(method, RequestBody.create(body.getBytes(StandardCharsets.UTF_8)))
                        .url(targetUrl)
                        .addHeader("content-type", "application/json")
                        .addHeader("accept", "application/json")
                        .addHeader("KEPLOY_TEST_ID", testCase.getId()).build();
        }
    }

    private Request.Builder setCustomRequestHeaderMap(Map<String, Service.StrArr> srcMap) {
        Request.Builder reqBuilder = new Request.Builder();
        Map<String, List<String>> headerMap = new HashMap<>();

        for (String key : srcMap.keySet()) {
            Service.StrArr values = srcMap.get(key);
            ProtocolStringList valueList = values.getValueList();
            List<String> headerValues = new ArrayList<>(valueList);
            headerMap.put(key, headerValues);
        }

        for (String key : headerMap.keySet()) {
            if (isModifiable(key)) {
                List<String> values = headerMap.get(key);
                for (String value : values) {
                    reqBuilder.addHeader(key, value);
                }
            }
        }
        return reqBuilder;
    }

    private boolean isModifiable(String key) {
        switch (key) {
            case "connection":
                return false;
            case "content-length":
                return false;
            case "date":
                return false;
            case "expect":
                return false;
            case "from":
                return false;
            case "host":
                return false;
            case "upgrade":
                return false;
            case "via":
                return false;
            case "warning":
                return false;
        }
        return true;
    }

    private Map<String, Service.StrArr> getRequestHeaderMap(HttpServletRequest httpServletRequest) {

        Map<String, Service.StrArr> map = new HashMap<>();

        List<String> headerNames = Collections.list(httpServletRequest.getHeaderNames());
        for (String name : headerNames) {

            List<String> values = Collections.list(httpServletRequest.getHeaders(name));
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