import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import keploy.Keploy;
import keploy.KeployInstance;
import keploy.context.Context;
import stubs.RegressionServiceGrpc;
import stubs.Service;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class GrpcClient {

    private static final Logger logger = Logger.getLogger(GrpcClient.class.getName());

    private RegressionServiceGrpc.RegressionServiceBlockingStub blockingStub;

    public GrpcClient() {

    }

    public GrpcClient(Channel channel) {
        this.blockingStub = RegressionServiceGrpc.newBlockingStub(channel);
    }


    public List<Service.getTCSResponse> fetch() {

        List<Service.getTCSResponse> testCases = new ArrayList<>();
        for (int i = 0; ; i += 25) {
            try {
                Service.getTCSRequest tcsRequest = Service.getTCSRequest.newBuilder().setApp("sample-url-shortener").setLimit("25").setOffset(String.valueOf(i)).build();
                Service.getTCSResponse tcs = blockingStub.getTCS(tcsRequest);
                int cnt = tcs.getTcsCount();
                if (cnt == 0) {
                    break;
                }
                testCases.add(tcs);
            } catch (StatusRuntimeException e) {
                logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
                return null;
            }
        }
        return testCases;

    }

    public void denoise(String id, Service.TestCaseReq testCaseReq) throws Exception {

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
        testReqBuilder.setAppID(KeployInstance.getInstance().getKeploy().getCfg().getApp().getName());
        Service.TestReq bin2 = testReqBuilder.build();

        String url = KeployInstance.getInstance().getKeploy().getCfg().getServer().getURL() + "/regression/denoise";
        HttpRequest r = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(bin2.toString())).setHeader("Content-Type", "application/json").build();


        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response;

        try {
            response = client.send(r, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.info("failed to send de-noise request to backend");
            throw new Exception(e);
        }
    }

    public Service.HttpResp simulate(Service.TestCase testCase) throws Exception {

        String url = testCase.getHttpReq().getURL();
        String host = KeployInstance.getInstance().getKeploy().getCfg().getApp().getHost();
        String port = KeployInstance.getInstance().getKeploy().getCfg().getApp().getPort();
        String method = testCase.getHttpReq().getMethod();
        String body = testCase.getHttpReq().getBody();

        String targetUrl = "http://" + host + ":" + port + url;

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
        Service.HttpResp resp = GetResp(testCase.getId());

        if ((resp.getStatusCode() < 300 || resp.getStatusCode() >= 400) && !resp.getBody().equals(response.body())) {
            resp.newBuilder().setBody(response.body());
            resp.newBuilder().setStatusCode(response.statusCode());
            convertHeaderMap_ListToStrArr(response.headers().map(), resp.getHeaderMap());
        }

        return resp;
    }

    //converting  Map<String,List<String>> to Map<String,Service.StrArr>
    public void convertHeaderMap_ListToStrArr(Map<String, List<String>> srcMap, Map<String, Service.StrArr> destMap) {
        for (String key : srcMap.keySet()) {
            List<String> values = srcMap.get(key);
            Service.StrArr.Builder builder = Service.StrArr.newBuilder();
            for (int i = 0; i < values.size(); i++) {
                builder.setValue(i, values.get(i));
            }
            Service.StrArr value = builder.build();
            destMap.put(key, value);
        }
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

    public Service.HttpResp GetResp(String id) {

        Keploy k = KeployInstance.getInstance().getKeploy();

        HttpServletResponse httpServletResponse = k.getResp().get(id);
        if (httpServletResponse == null) {
            return Service.HttpResp.newBuilder().build();
        }

        ByteArrayOutputStream byteArrayRes = new ByteArrayOutputStream();
        byte[] resBody = byteArrayRes.toByteArray();
        try {
            ServletOutputStream resStream = httpServletResponse.getOutputStream();
            resStream.write(resBody);

        } catch (Exception e) {
            logger.severe("Failed to get response ");
        }
        Service.HttpResp.Builder builder = Service.HttpResp.newBuilder();
        Map<String, Service.StrArr> headerMap = builder.getHeaderMap();

        setResponseHeaderMap(httpServletResponse, headerMap);
        Service.HttpResp httpResp = builder.setStatusCode(httpServletResponse.getStatus()).setBody(resBody.toString()).build();
        return httpResp;
    }

    public void setResponseHeaderMap(HttpServletResponse httpServletResponse, Map<String, Service.StrArr> headerMap) {

        List<String> headerNames = httpServletResponse.getHeaderNames().stream().collect(Collectors.toList());

        for (String name : headerNames) {

            List<String> values = httpServletResponse.getHeaders(name).stream().collect(Collectors.toList());
            Service.StrArr.Builder builder = Service.StrArr.newBuilder();

            for (int i = 0; i < values.size(); i++) {
                builder.setValue(i, values.get(i));
            }
            Service.StrArr value = builder.build();

            headerMap.put(name, value);
        }
    }

    public void Test() {
        logger.info("test starting in 5 sec");
        List<Service.getTCSResponse> tcs = fetch();
    }

    public void CaptureTestCases(KeployInstance ki, byte[] reqBody, byte[] resBody, Map<String, String> params, Service.HttpResp httpResp) {

        HttpServletRequest ctxReq = Context.getCtx();
        if (ctxReq == null) {
            logger.warning("failed to get keploy context");
            return;
        }

        Service.TestCaseReq.Builder testCaseBuilder = Service.TestCaseReq.newBuilder();
        Keploy k = ki.getInstance().getKeploy();

        Service.HttpReq.Builder httpReqBuilder = Service.HttpReq.newBuilder();
        httpReqBuilder.setMethod(ctxReq.getMethod()).setURL(ctxReq.getRequestURL().toString());
        Map<String, String> urlParamsMap = httpReqBuilder.getURLParamsMap();
        urlParamsMap = params;

        Map<String, Service.StrArr> headerMap = httpReqBuilder.getHeaderMap();
        setRequestHeaderMap(ctxReq, headerMap);
        httpReqBuilder.setBody(reqBody.toString());
        Service.HttpReq httpReq = httpReqBuilder.build();


        testCaseBuilder.setAppID(k.getCfg().getApp().getName());
        testCaseBuilder.setCaptured(Instant.now().getEpochSecond());
        testCaseBuilder.setURI(ctxReq.getRequestURI());
        testCaseBuilder.setHttpResp(httpResp);
        testCaseBuilder.setHttpReq(httpReq);

        blockingStub.postTC(testCaseBuilder.build());
    }


    public void setRequestHeaderMap(HttpServletRequest httpServletRequest, Map<String, Service.StrArr> headerMap) {

        List<String> headerNames = Collections.list(httpServletRequest.getHeaderNames());
        for (String name : headerNames) {

            List<String> values = Collections.list(httpServletRequest.getHeaders(name));
            Service.StrArr.Builder builder = Service.StrArr.newBuilder();

            for (int i = 0; i < values.size(); i++) {
                builder.setValue(i, values.get(i));
            }
            Service.StrArr value = builder.build();

            headerMap.put(name, value);
        }
    }

    public String start(int Total) {
        Service.startRequest startRequest = Service.startRequest.newBuilder().setApp("mhApp").setTotal("2").build();
        Service.startResponse startResponse = blockingStub.start(startRequest);
        return startResponse.getId();
    }

    public String end(String id, boolean status) {
        Service.endRequest endRequest = Service.endRequest.newBuilder().setId("123").setStatus("OK").build();
        Service.endResponse endResponse = blockingStub.end(endRequest);
        return endResponse.getMessage();
    }

    public void check() {

    }


    public static void main(String[] args) {
        String target = "localhost:8081";
        ManagedChannel channel = ManagedChannelBuilder.forTarget(target)
                // Channels are secure by default (via SSL/TLS). For the example we disable TLS to avoid
                // needing certificates.
                .usePlaintext()
                .build();

        RegressionServiceGrpc.RegressionServiceBlockingStub stub = RegressionServiceGrpc.newBlockingStub(channel);

        Service.startResponse startResponse = stub.start(Service.startRequest.newBuilder().setApp("test").setTotal("1").build());
        System.out.println(startResponse + "This is the response I am getting !! ");
        Service.endResponse endResponse = stub.end(Service.endRequest.newBuilder().setStatus("OK").setId("123").build());
        System.out.println(endResponse + "This is the response I am getting !! ");
//        Service.TestCase testCase = stub.getTC(Service.getTCRequest.newBuilder().setApp("sample-url-shortener").setId("09a39684-c551-42a9-9b64-3f61815a8662").build());
//        System.out.println(testCase);
//       Service.getTCSResponse testcases =  stub.getTCS(Service.getTCSRequest.newBuilder().setApp("sample-url-shortener").setOffset("0").setLimit("25").build());
//        System.out.println(testcases);
        GrpcClient k = new GrpcClient(channel);
        List<Service.getTCSResponse> testCases = k.fetch();
        System.out.println(testCases);
        System.out.println(testCases.size());
    }


}
