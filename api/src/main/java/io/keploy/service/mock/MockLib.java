package io.keploy.service.mock;

import io.keploy.grpc.stubs.Service;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import io.keploy.service.GrpcService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

import static io.keploy.service.GrpcService.blockingStub;

public class MockLib {
    private static final Logger logger = LogManager.getLogger(MockLib.class);


    public MockLib() {
        Kcontext kctx = Context.getCtx();
        new GrpcService();
    }


    public static boolean StartRecordingMocks(Kcontext kctx, String path, String mode, String name, Boolean overWrite) {
        Service.StartMockReq startMockReq = Service.StartMockReq.newBuilder().setMode(mode).setPath(path).build();
        Service.StartMockResp startMockResp = blockingStub.startMocking(startMockReq);
        if (startMockResp == null) { // TODO - check how to handle this error
            logger.error("Failed to make StartMocking grpc call to keploy server" + name + " mock");
            return false;
        }
        return startMockResp.getExists();
    }

    public static List<Service.Mock> GetAllMocks(Service.GetMockReq getMockReq) {
        final Service.getMockResp resp = blockingStub.getMocks(getMockReq);
        if (resp != null) {
            return resp.getMocksList();
        }

        logger.error("returned nil as array mocks from keploy server");
        return null;
    }

    public static boolean PutMock(String path, Service.Mock mock) {

        Service.PutMockReq putMockReq = Service.PutMockReq.newBuilder().setMock(mock).setPath(path).build();
        Service.PutMockResp putMockResp = blockingStub.putMock(putMockReq);
        if (putMockResp == null) { // check iska error handle
            logger.error("Failed to call the putMock method");
            return false;
        }
        return true;
    }



}
