package grpc;

import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import keploy.Keploy;
import keploy.KeployInstance;
import keploy.context.Context;
import stubs.RegressionServiceGrpc;
import stubs.Service;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    public String denoise(String id, Service.TestCaseReq testCaseReq) throws Exception {

        Service.TestCase.Builder testCaseBuilder = Service.TestCase.newBuilder();
        testCaseBuilder.setId(id);
        testCaseBuilder.setCaptured(testCaseReq.getCaptured());
        testCaseBuilder.setURI(testCaseReq.getURI());
        testCaseBuilder.setHttpReq(testCaseReq.getHttpReq());
        Service.TestCase testCase = testCaseBuilder.build();

        simulate(testCase);


        return "denoise successfull";
    }

    public String simulate(Service.TestCase testCase) throws Exception {

        String url = testCase.getHttpReq().getURL();
        String host = KeployInstance.getInstance().getKeploy().getCfg().getApp().getHost();
        String port = KeployInstance.getInstance().getKeploy().getCfg().getApp().getPort();
        String method = testCase.getHttpReq().getMethod();
        String body = testCase.getHttpReq().getBody();

        String targetUrl = "http://" + host + ":" + port + url;

        HttpRequest req = HttpRequest.newBuilder(URI.create(targetUrl)).setHeader("KEPLOY_TEST_ID", testCase.getId()).build();
        Map<String, List<String>> headerMap = req.headers().map();
        convertHeaderMap(testCase.getHttpReq().getHeaderMap(), headerMap);

        HttpClient client = HttpClient.newHttpClient();
        try {
            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.info("failed sending testcase request to app");
            throw new Exception(e);
        }

        return "success";
    }

    //converting  Map<String,Service.StrArr> to Map<String,List<String>
    public void convertHeaderMap(Map<String, Service.StrArr> srcMap, Map<String, List<String>> destMap) {
//        for(String key:srcMap.keySet()){
//            srcMap.get(key).
//            destMap.put(key,null);
//        }
        for(String key:srcMap.keySet()){
           var value = srcMap.get(key);
            List<String> headervalues = new ArrayList<>();
            for (int i = 0;; i++) {
                try {
                    String v = value.getValue(i);
                    headervalues.add(v);
                }catch (Exception e){
                    break;
                }
            }
            destMap.put(key,headervalues);
        }
    }

    public void GetResp() {

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
