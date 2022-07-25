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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class GrpcService {

    private static final Logger logger = LogManager.getLogger(GrpcService.class);
    private final RegressionServiceGrpc.RegressionServiceBlockingStub blockingStub;
    private final Keploy k;

    public GrpcService() {
        // Channels are secure by default (via SSL/TLS). For the example we disable TLS to avoid
        // needing certificates.
        ManagedChannel channel = ManagedChannelBuilder.forTarget("localhost:8081")
                .usePlaintext()
                .build();
        this.blockingStub = RegressionServiceGrpc.newBlockingStub(channel);
        this.k = KeployInstance.getInstance().getKeploy();
    }

    public void CaptureTestCases(KeployInstance ki, String reqBody, String resBody, Map<String, String> params, Service.HttpResp httpResp) throws Exception {
        logger.debug("inside CaptureTestCases");

        HttpServletRequest ctxReq = Context.getCtx();
        if (ctxReq == null) {
            logger.warn("failed to get keploy context");
            return;
        }

        Service.TestCaseReq.Builder testCaseReqBuilder = Service.TestCaseReq.newBuilder();

        Service.HttpReq.Builder httpReqBuilder = Service.HttpReq.newBuilder();
        httpReqBuilder.setMethod(ctxReq.getMethod()).setURL(ctxReq.getRequestURL().toString());
        Map<String, String> urlParamsMap = params;
        httpReqBuilder.putAllURLParams(urlParamsMap);
        Map<String, Service.StrArr> headerMap = getRequestHeaderMap(ctxReq);
        httpReqBuilder.putAllHeader(headerMap);
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
            logger.debug("denoise message received from server {}", deNoiseResponse.getMessage());
        } catch (Exception e) {
            logger.error("failed to send de-noise request to backend", e);
        }
    }

    public Service.HttpResp simulate(Service.TestCase testCase) throws Exception {
        logger.debug("inside simulate");

        String targetUrl = testCase.getHttpReq().getURL();
        String host = k.getCfg().getApp().getHost();
        String port = k.getCfg().getApp().getPort();
        String method = testCase.getHttpReq().getMethod();
        String body = testCase.getHttpReq().getBody();


        URL url = null;
        HttpURLConnection connection = null;
        int statusCode;
        StringBuilder response = new StringBuilder();


        try {
            url = new URL(targetUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("content-type", "application/json");
            connection.setRequestProperty("accept", "application/json");
            connection.setRequestProperty("KEPLOY_TEST_ID", testCase.getId());
            setCustomRequestHeaderMap(connection, testCase.getHttpReq().getHeaderMap());

            if (!method.equals("GET") && !method.equals("DELETE")) {
                connection.setDoOutput(true);
                setCustomRequestBody(connection, body);
            }

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "utf-8"))) {
                String responseLine = null;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
                logger.debug("simulate request's response: {} ", response.toString());
            }

            statusCode = connection.getResponseCode();

        } catch (MalformedURLException e) {
            logger.info("failed sending testcase request to app");
            throw new RuntimeException(e);
        } catch (IOException e) {
            logger.info("failed sending testcase request to app");
            throw new RuntimeException(e);
        }

        //wait so that simulate request could be completed.
        while (k.getResp().get(testCase.getId()) == null) {

        }


        Map<String, List<String>> responseHeaders = connection.getHeaderFields();


        logger.debug("response headers from custom request inside simulate: {}", responseHeaders);

        Service.HttpResp.Builder resp = GetResp(testCase.getId());
        if ((resp.getStatusCode() < 300 || resp.getStatusCode() >= 400) && !resp.getBody().equals(response.toString())) {
            resp.setBody(response.toString());
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
            System.out.println("CAN NOT GET RESPONSE FROM KEPLOY MAP");
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

        System.out.println("getting response from keploy resp map");

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
        boolean ok = true;
        for (int i = 0; i < tcs.size(); i++) {
            Service.TestCase tc = tcs.get(i);
            logger.info("testing {} of {} testcase id: [{}]", (i + 1), total, tc.getId());
            Service.TestCase tcCopy = tc;
            ok &= check(id, tcCopy);
            logger.info("result : testcase id: [{}]  passed: {}", tcCopy.getId(), ok);
        }
        String msg = end(id, ok);
        if (msg == null) {
            logger.error("failed to end test run");
            return;
        }
        logger.info("test run completed with run id [{}]", id);
        logger.info("|| passed overall: {} ||", String.valueOf(ok).toUpperCase());
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
            //when use java.net.HttpURLConnection, one of the response headers has key = null.
            if (key == null) continue;

            List<String> headerValues = srcMap.get(key);
            Service.StrArr.Builder builder = Service.StrArr.newBuilder();
            for (int i = 0; i < headerValues.size(); i++) {
                builder.addValue(headerValues.get(i));
            }
            Service.StrArr value = builder.build();
            map.put(key, value);
        }
        return map;
    }

    private void setCustomRequestHeaderMap(HttpURLConnection connection, Map<String, Service.StrArr> srcMap) {
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
                    connection.setRequestProperty(key, value);
                }
            }
        }
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

    private void setCustomRequestBody(HttpURLConnection connection, String body) {
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = body.getBytes("utf-8");
            os.write(input, 0, input.length);
        } catch (UnsupportedEncodingException e) {
            logger.error("unable to set custom request body", e);
            throw new RuntimeException(e);
        } catch (IOException e) {
            logger.error("unable to set custom request body", e);
            throw new RuntimeException(e);
        }
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
}