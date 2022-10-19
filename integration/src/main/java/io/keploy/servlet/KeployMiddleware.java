package io.keploy.servlet;

import io.keploy.grpc.stubs.Service;
import io.keploy.regression.KeployInstance;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import io.keploy.regression.keploy.AppConfig;
import io.keploy.regression.keploy.Config;
import io.keploy.regression.keploy.Keploy;
import io.keploy.regression.keploy.ServerConfig;
import io.keploy.regression.Mode;
import io.keploy.service.GrpcService;
import io.keploy.utils.GenericRequestWrapper;
import io.keploy.utils.GenericResponseWrapper;
import io.keploy.utils.HaltThread;
import io.keploy.utils.HttpStatusReasons;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public class KeployMiddleware implements Filter {

    private static final Logger logger = LogManager.getLogger(KeployMiddleware.class);
    private static final String CROSS = new String(Character.toChars(0x274C));

    @Override
    public void init(FilterConfig filterConfig) {
        //just like wait groups, used in testfile
        CountDownLatch countDownLatch = HaltThread.getInstance().getCountDownLatch();

        logger.debug("initializing keploy");
        KeployInstance ki = KeployInstance.getInstance();
        Keploy kp = ki.getKeploy();
        Config cfg = new Config();
        AppConfig appConfig = new AppConfig();
        if (System.getenv("APP_NAME") != null) {
            appConfig.setName(System.getenv("APP_NAME"));
        }
        if (System.getenv("APP_PORT") != null) {
            appConfig.setPort(System.getenv("APP_PORT"));
        }

        //Path for exported tests
        String kpath = System.getenv("KEPLOY_PATH");

        if (kpath != null && kpath.length() > 0 && !Paths.get(kpath).isAbsolute()) {
            Path effectivePath = Paths.get("").resolve(kpath).toAbsolutePath();
            String absolutePath = effectivePath.normalize().toString();
            appConfig.setTestPath(absolutePath);
        } else if (kpath == null || kpath.length() == 0) {
            String currDir = System.getProperty("user.dir") + "/src/test/e2e/keploy-tests";
            appConfig.setTestPath(currDir);
        }

        logger.debug("test path: {}", appConfig.getTestPath());

        //Path for exported mocks
        String mpath = System.getenv("MOCK_PATH");

        if (mpath != null && mpath.length() > 0 && !Paths.get(mpath).isAbsolute()) {
            Path effectivePath = Paths.get("").resolve(mpath).toAbsolutePath();
            String absolutePath = effectivePath.normalize().toString();
            appConfig.setMockPath(absolutePath);
        } else if (mpath == null || mpath.length() == 0) {
            String currDir = System.getProperty("user.dir") + "/src/test/e2e/mocks";
            appConfig.setMockPath(currDir);
        }

        logger.debug("mock path: {}", appConfig.getMockPath());

        ServerConfig serverConfig = new ServerConfig();

        if (System.getenv("DENOISE") != null) {
            serverConfig.setDenoise(Boolean.parseBoolean(System.getenv("DENOISE")));
        }

        if (System.getenv("KEPLOY_URL") != null) {
            serverConfig.setURL(System.getenv("KEPLOY_URL"));
        }

        cfg.setApp(appConfig);
        cfg.setServer(serverConfig);
        kp.setCfg(cfg);

        // its mere purpose is to call the constructor to initialize some fields
        new GrpcService();

        final Mode.ModeType KEPLOY_MODE = Mode.getMode();

        new Thread(() -> {
            if (KEPLOY_MODE != null && KEPLOY_MODE.equals(Mode.ModeType.MODE_TEST)) {
                try {
                    logger.debug("starting tests");
                    GrpcService.Test();
                } catch (Exception e) {
                    logger.error(CROSS + " failed to run tests", e);
                }
                //to stop after running all tests
                countDownLatch.countDown(); // when running tests using cmd
                try {
                    Thread.sleep(10000);
                    System.exit(0);
                } catch (InterruptedException e) {
                    logger.error(CROSS + " failed to shut test run properly... ", e);
                }
            }
        }).start();
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;


        KeployInstance ki = KeployInstance.getInstance();
        Keploy k = ki.getKeploy();

        logger.debug("inside middleware: incoming request");

        logger.debug("mode: {}", Mode.getMode());

        if (k == null || Mode.getMode() != null && (Mode.getMode()).equals(Mode.ModeType.MODE_OFF)) {
            filterChain.doFilter(request, response);
            return;
        }

        //setting  context
        Kcontext kctx = new Kcontext();
        kctx.setRequest(request);

        Context.setCtx(kctx);

        String keploy_test_id = request.getHeader("KEPLOY_TEST_ID");
        logger.debug("KEPLOY_TEST_ID: {}", keploy_test_id);

        if (keploy_test_id != null) {
            kctx.setTestId(keploy_test_id);
            kctx.setMode(Mode.ModeType.MODE_TEST);
            kctx.getMock().addAll(k.getMocks().get(keploy_test_id));
        }

//        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request);
//        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        GenericRequestWrapper requestWrapper = new GenericRequestWrapper(request);
        GenericResponseWrapper responseWrapper = new GenericResponseWrapper(response);

        filterChain.doFilter(requestWrapper, responseWrapper);

        byte[] reqArr = requestWrapper.getData();
        byte[] resArr = responseWrapper.getData();

        String reqEncoding = (request.getCharacterEncoding() == null) ? "UTF-8" : request.getCharacterEncoding();
        String resEncoding = (response.getCharacterEncoding() == null) ? "ISO-8859-1" : response.getCharacterEncoding();

        String requestBody = this.getStringValue(reqArr, reqEncoding);
        String responseBody = this.getStringValue(resArr, resEncoding);

        logger.debug("request body inside middleware: {}", requestBody);
        logger.debug("response body inside middleware: {}", responseBody);

        String statusMsg = HttpStatusReasons.getStatusMsg(responseWrapper.getStatus());
        String protocolType = requestWrapper.getProtocol();
        int protoMajor = Character.getNumericValue(protocolType.charAt(protocolType.length() - 3));
        int protoMinor = Character.getNumericValue(protocolType.charAt(protocolType.length() - 1));


        Map<String, Service.StrArr> simResponseHeaderMap = getResponseHeaderMap(responseWrapper);

        Service.HttpResp simulateResponse = Service.HttpResp.newBuilder()
                .setStatusCode(responseWrapper.getStatus())
                .setBody(responseBody)
                .setStatusMessage(statusMsg)
                .setProtoMajor(protoMajor)
                .setProtoMinor(protoMinor)
                .putAllHeader(simResponseHeaderMap).build();

        logger.debug("simulate response inside middleware: {}", simulateResponse);

        if (keploy_test_id != null) {
            k.getResp().put(keploy_test_id, simulateResponse);
//            Context.cleanup();
            logger.debug("response in keploy resp map: {}", k.getResp().get(keploy_test_id));
        } else {

            Map<String, String> urlParams = setUrlParams(requestWrapper.getParameterMap());

            Service.HttpResp.Builder builder = Service.HttpResp.newBuilder();
            Map<String, Service.StrArr> headerMap = getResponseHeaderMap(responseWrapper);
            Service.HttpResp httpResp = builder
                    .setStatusCode(responseWrapper.getStatus())
                    .setBody(responseBody)
                    .setStatusMessage(statusMsg)
                    .setProtoMajor(protoMajor)
                    .setProtoMinor(protoMinor)
                    .putAllHeader(headerMap).build();

            try {
                GrpcService.CaptureTestCases(requestBody, urlParams, httpResp, protocolType);
            } catch (Exception e) {
                logger.error(CROSS + " failed to capture testCases", e);
            }
        }
        // this will also flush the headers and make response committed.
//        responseWrapper.copyBodyToResponse();

        responseWrapper.flushBuffer();

//        OutputStream out = response.getOutputStream();
//        out.write(resArr);
//        out.close();
//        // to write headers from buffer
//        response.flushBuffer();


        logger.debug("inside middleware: outgoing response");
    }


    private Map<String, Service.StrArr> getResponseHeaderMap(GenericResponseWrapper responseWrapper) {

        Map<String, Service.StrArr> map = new HashMap<>();

        List<String> headerNames = new ArrayList<>(responseWrapper.getHeaderNames());

        for (String name : headerNames) {

            if (name == null) continue;

            List<String> values = new ArrayList<>(responseWrapper.getHeaders(name));
            Service.StrArr.Builder builder = Service.StrArr.newBuilder();

            for (String s : values) {
                builder.addValue(s);
            }
            Service.StrArr value = builder.build();

            map.put(name, value);
        }
        return map;
    }

    private Map<String, String> setUrlParams(Map<String, String[]> param) {
        Map<String, String> urlParams = new HashMap<>();

        for (String key : param.keySet()) {
            //taking only value of the parameter
            String value = param.get(key)[0];
            if (key == null || value == null) continue;
            urlParams.put(key, value);
        }
        return urlParams;
    }

    private String getStringValue(byte[] contentAsByteArray, String characterEncoding) {
        try {
            return new String(contentAsByteArray, 0, contentAsByteArray.length, characterEncoding);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return "";
    }

    @Override
    public void destroy() {

    }
}