package io.keploy.service.mock;

import io.keploy.grpc.stubs.Service;
import io.keploy.regression.KeployInstance;
import io.keploy.regression.Mode;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import io.keploy.regression.keploy.AppConfig;
import io.keploy.regression.keploy.Keploy;
import io.keploy.regression.keploy.ServerConfig;
import io.keploy.service.GrpcService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static io.keploy.service.GrpcService.blockingStub;
import static io.keploy.service.mock.Config.*;

public class MockLib {
    private static final Logger logger = LogManager.getLogger(MockLib.class);
    static Keploy k = null;
    AppConfig appConfig = new AppConfig();

    public MockLib(String name) {
        KeployInstance ki = KeployInstance.getInstance();
        k = ki.getKeploy();
        io.keploy.regression.keploy.Config cfg = new io.keploy.regression.keploy.Config();
        Name = name;
        appConfig.setName(Name);
        cfg.setApp(appConfig);

        ServerConfig serverConfig = new ServerConfig();

        if (System.getenv("DENOISE") != null) {
            serverConfig.setDenoise(Boolean.parseBoolean(System.getenv("DENOISE")));
        }

        if (System.getenv("KEPLOY_URL") != null) {
            serverConfig.setURL(System.getenv("KEPLOY_URL"));
        }

        cfg.setApp(appConfig);
        cfg.setServer(serverConfig);
        k.setCfg(cfg);
        new GrpcService();


        Kcontext ctx = NewContext();
        System.out.println(ctx);
    }

    public Kcontext NewContext() {
        mode = Mode.ModeType.MODE_TEST;

        String mpath = System.getenv("KEPLOY_MOCK_PATH");
        Path path = Paths.get("");
//        AppConfig appConfig = new AppConfig();
        if (mpath != null && mpath.length() > 0 && !Paths.get(mpath).isAbsolute()) {
            Path effectivePath = path.resolve(mpath).toAbsolutePath();
            String absolutePath = effectivePath.normalize().toString();
            appConfig.setMockPath(absolutePath);
        } else if (mpath == null || mpath.length() == 0) {
            String currDir = System.getProperty("user.dir") + "/src/test/e2e/mocks";
            mpath = currDir;
            appConfig.setMockPath(currDir);
        } else {
            //if user gives the path
            appConfig.setMockPath(mpath);
        }
        MockPath = appConfig.getMockPath();
        logger.debug("mock path: {}", appConfig.getMockPath());

        mode = System.getenv().getOrDefault("KEPLOY_MODE", "test").equals("record") ? Mode.ModeType.MODE_RECORD : Mode.ModeType.MODE_TEST;
        ArrayList<Service.Mock> mocks = new ArrayList<>();
        if (mode == Mode.ModeType.MODE_TEST) {
            if (k.getCfg().getApp().getName() == null || k.getCfg().getApp().getName().length() == 0) {
                logger.error("Please enter the auto generated name to mock the dependencies using Keploy !");
//                return;
            }
            Service.GetMockReq request = Service.GetMockReq.newBuilder().setName(k.getCfg().getApp().getName()).setPath(mpath).build();

            mocks = GetAllMocks(request);
            if (mocks == null) {
                logger.error("No mocks found for the given name: {}", k.getCfg().getApp().getName());
                logger.error("Failed to get the mocks from keploy server. Please ensure that keploy server is running.");
            }
        }
        Kcontext kctx = new Kcontext();
        Context.setCtx(kctx);
        kctx.setMock(mocks);
        kctx.setMode(mode);
        kctx.setTestId(appConfig.getName());
        kctx.setFileExport(true);
        String name = "";
        if (k.getCfg().getApp().getName() != null) {
            name = " for " + k.getCfg().getApp().getName();
        }
        System.out.println(name + " -=-==-=-=-= " + mode.value);
        logger.info("Keploy created new mocking context in {} mode {}.If you dont see any logs about your dependencies below, your dependency/s are NOT wrapped.", mode, name);
        boolean exists = StartRecordingMocks(kctx, mpath, mode.value, name, Config.Overwrite);
        if (exists && !Config.Overwrite) {
            logger.error(" Keploy failed to record dependencies because yaml file already exists {} in directory: {}.", name, mpath);
            Config.MockId.put(name, true);
        }

        return kctx;
    }


    public static boolean StartRecordingMocks(Kcontext kctx, String path, String mode, String name, Boolean overWrite) {
        Service.StartMockReq startMockReq = Service.StartMockReq.newBuilder().setMode(mode).setPath(path).setName(name).setOverWrite(overWrite).build();
        Service.StartMockResp startMockResp = blockingStub.startMocking(startMockReq);
        if (startMockResp == null) { // TODO - check how to handle this error
            logger.error("Failed to make StartMocking grpc call to keploy server" + name + " mock");
            return false;
        }
        return startMockResp.getExists();
    }

    public static ArrayList<Service.Mock> GetAllMocks(Service.GetMockReq getMockReq) {
        final Service.getMockResp resp = blockingStub.getMocks(getMockReq);
        if (resp != null) {
            if (resp.getMocksList().size() == 0) {
                logger.info("Mocklist size is zero !!");
                return null;
            }
            return getM(resp.getMocksList());
        }

        logger.error("returned nil as array mocks from keploy server");
        return null;
    }

    private static ArrayList<Service.Mock> getM(List<Service.Mock> mocksList) {
        ArrayList<Service.Mock> mockArrayList = new ArrayList<>();
        for (int i = 0; i < mocksList.size(); i++) {
            mockArrayList.add(mocksList.get(0));
        }
        return mockArrayList;
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
