import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import stubs.RegressionServiceGrpc;
import stubs.Service;
class Keploy{
    int a = 1;

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
        Service.TestCase testCase = stub.getTC(Service.getTCRequest.newBuilder().setApp("sample-url-shortener").setId("09a39684-c551-42a9-9b64-3f61815a8662").build());
        System.out.println(testCase);


    }
}
