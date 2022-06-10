package grpc;

import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import stubs.RegressionServiceGrpc;
import stubs.Service;


import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

class Keploy {
    private static final Logger logger = Logger.getLogger(Keploy.class.getName());

    private final RegressionServiceGrpc.RegressionServiceBlockingStub blockingStub;

    public Keploy(Channel channel) {
        blockingStub = RegressionServiceGrpc.newBlockingStub(channel);
    }

    //    Service.TestCase[] fetch(){
////        RegressionServiceGrpc.RegressionServiceBlockingStub stub;
////        Service.TestCase testCase = stub.getTC(Service.getTCRequest.newBuilder().setApp("sample-url-shortener").setId("09a39684-c551-42a9-9b64-3f61815a8662").build());
//        for (int i = 0; ; i += 25) {
//            RegressionServiceGrpc.RegressionServiceBlockingStub stub;
//            Service.getTCSResponse testcases =  stub.getTCS(Service.getTCSRequest.newBuilder().setApp("sample-url-shortener").setOffset("0").setLimit("25").build());
//            System.out.println(testcases);
//        }
//    }
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

    public void simulate() {

    }

    public void GetResp() {

    }

    public void Test() {

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
}

public class GrpcClient {
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
        Keploy k = new Keploy(channel);
        List<Service.getTCSResponse> testCases = k.fetch();
        System.out.println(testCases);
        System.out.println(testCases.size());
    }
}
