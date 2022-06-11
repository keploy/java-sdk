package grpc;

import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import keploy.KeployInstance;
import keploy.context.Context;
import keploy.keploy.Keploy;
import stubs.RegressionServiceGrpc;
import stubs.Service;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    public String simulate() {
        String targetURL = Service.HttpReq.newBuilder().getURL();
        String body = Service.HttpReq.newBuilder().getBody();
        String method = Service.HttpReq.newBuilder().getMethod();
        HttpURLConnection connection = null;

        try {
            //Create connection
            URL url = new URL(targetURL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded");

            connection.setRequestProperty("Content-Length",
                    Integer.toString(urlParameters.getBytes().length));
            connection.setRequestProperty("Content-Language", "en-US");

            connection.setUseCaches(false);
            connection.setDoOutput(true);
            //Send request
            DataOutputStream wr = new DataOutputStream(
                    connection.getOutputStream());
            wr.writeBytes(urlParameters);
            wr.close();

            //Get Response
            InputStream is = connection.getInputStream();
            BufferedReader rd = new BufferedReader(new InputStreamReader(is));
            StringBuilder response = new StringBuilder(); // or StringBuffer if Java version 5+
            String line;
            while ((line = rd.readLine()) != null) {
                response.append(line);
                response.append('\r');
            }
            rd.close();
            return response.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public void GetResp() {

    }

    public void Test() {

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
