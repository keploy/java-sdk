package io.keploy.servlet;

import io.grpc.netty.shaded.io.netty.util.internal.InternalThreadLocalMap;
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
import io.keploy.utils.*;
import lombok.SneakyThrows;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
            String app_name = System.getenv("APP_NAME").trim();
            appConfig.setName(app_name);
        }
        if (System.getenv("APP_PORT") != null) {
            String app_port = System.getenv("APP_PORT").trim();
            appConfig.setPort(app_port);
        }

        //Path for exported tests
        String kpath = System.getenv("KEPLOY_TEST_PATH");
        Path path = Paths.get("");
        if (kpath != null && kpath.length() > 0 && !Paths.get(kpath).isAbsolute()) {
            kpath = kpath.trim();
            Path effectivePath = path.resolve(kpath).toAbsolutePath();
            String absolutePath = effectivePath.normalize().toString();
            appConfig.setTestPath(absolutePath);
        } else if (kpath == null || kpath.length() == 0) {
            String currDir = System.getProperty("user.dir") + "/src/test/e2e/keploy-tests";
            appConfig.setTestPath(currDir);
        } else {
            //if user gives the path
            appConfig.setTestPath(kpath);
        }

        logger.debug("test path: {}", appConfig.getTestPath());

        //Path for exported mocks
        String mpath = System.getenv("KEPLOY_MOCK_PATH");

        if (mpath != null && mpath.length() > 0 && !Paths.get(mpath).isAbsolute()) {
            mpath = mpath.trim();
            Path effectivePath = path.resolve(mpath).toAbsolutePath();
            String absolutePath = effectivePath.normalize().toString();
            appConfig.setMockPath(absolutePath);
        } else if (mpath == null || mpath.length() == 0) {
            String currDir = System.getProperty("user.dir") + "/src/test/e2e/mocks";
            appConfig.setMockPath(currDir);
        } else {
            //if user gives the path
            appConfig.setMockPath(mpath);
        }

        logger.debug("mock path: {}", appConfig.getMockPath());


        //Path for exported assets
        String apath = System.getenv("KEPLOY_ASSET_PATH");

        if (apath != null && apath.length() > 0 && !Paths.get(apath).isAbsolute()) {
            apath = apath.trim();
            Path effectivePath = path.resolve(apath).toAbsolutePath();
            String absolutePath = effectivePath.normalize().toString();
            appConfig.setAssetPath(absolutePath);
        } else if (mpath == null || mpath.length() == 0) {
            String currDir = System.getProperty("user.dir") + "/src/test/e2e/assets";
            appConfig.setAssetPath(currDir);
        } else {
            //if user gives the path
            appConfig.setAssetPath(mpath);
        }

        logger.debug("asset path: {}", appConfig.getAssetPath());

        ServerConfig serverConfig = new ServerConfig();

        if (System.getenv("DENOISE") != null) {
            String denoise = System.getenv("DENOISE").trim();
            serverConfig.setDenoise(Boolean.parseBoolean(denoise));
        }

        if (System.getenv("KEPLOY_URL") != null) {
            String keploy_url = System.getenv("KEPLOY_URL").trim();
            serverConfig.setURL(keploy_url);
        }

        cfg.setApp(appConfig);
        cfg.setServer(serverConfig);
        kp.setCfg(cfg);

        // its mere purpose is to call the constructor to initialize some fields
        new GrpcService();

        final Mode.ModeType KEPLOY_MODE = Mode.getMode();

        if (KEPLOY_MODE != null && KEPLOY_MODE.equals(Mode.ModeType.MODE_TEST)) {
            new Thread(() -> {
                try {
                    logger.debug("starting tests");
                    GrpcService.Test();
                } catch (Exception e) {
                    logger.error(CROSS + " failed to run tests", e);
                }
                //to stop after running all tests
                countDownLatch.countDown(); // when running tests using cmd

                // to avoid memory leak
                Context.cleanup();
                InternalThreadLocalMap.remove();
                try {
                    GrpcService.channel.shutdown();
                    GrpcService.channel.awaitTermination(1, TimeUnit.MINUTES);
                    GrpcService.channel.shutdownNow();
                } catch (InterruptedException e) {
                    logger.error(CROSS + " failed to shut grpc connection properly... ", e);
                }

                try {
                    Thread.sleep(10000);
                    System.exit(0);
                } catch (InterruptedException e) {
                    logger.error(CROSS + " failed to shut test run properly... ", e);
                }

            }).start();
        }

        String runTestBeforeRecord = System.getenv("RUN_TEST_BEFORE_RECORD");
        boolean runTests = false;
        if (runTestBeforeRecord != null) {
            runTests = Boolean.parseBoolean(runTestBeforeRecord);
        }


        if (KEPLOY_MODE != null && KEPLOY_MODE.equals(Mode.ModeType.MODE_RECORD) && runTests) {
            new Thread(this::handleExistingTests).start();
        }

    }

    private static final String SET_PLAIN_TEXT = "\033[0;0m";
    private static final String SET_BOLD_TEXT = "\033[0;1m";

    private static String bold(String str) {
        return (SET_BOLD_TEXT + str + SET_PLAIN_TEXT);
    }

    @SneakyThrows
    private void handleExistingTests() {

        Thread.sleep(2000);

        final String WARN = "\uD83D\uDEA8";

        System.out.println("--------------------------------------------------------------------------------------------\n");
        String startTest = WARN + " Executing existing test cases to maintain the same state, " +
                "kindly do not record any new test cases till these tests get completed.";
        System.out.println(bold(startTest));
        System.out.println("\n--------------------------------------------------------------------------------------------");

        GrpcService.Test();
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
            List<Service.Mock> mocks = k.getMocks().get(keploy_test_id);
            if (mocks != null) {
                kctx.getMock().addAll(mocks);
            }
        }


        GenericRequestWrapper requestWrapper = new GenericRequestWrapper(request);
        GenericResponseWrapper responseWrapper = new GenericResponseWrapper(response);

        Map<String, List<MultipartContent>> formData = new HashMap<>();
        if (request.getContentType() != null && request.getContentType().startsWith("multipart/form-data")) {
            formData = processMultipart(request);
        }


        filterChain.doFilter(requestWrapper, responseWrapper);

        byte[] reqArr = requestWrapper.getData();
        byte[] resArr = responseWrapper.getData();

        String reqEncoding = (request.getCharacterEncoding() == null) ? "UTF-8" : request.getCharacterEncoding();
        String resEncoding = (response.getCharacterEncoding() == null) ? "ISO-8859-1" : response.getCharacterEncoding();

        String requestBody = this.getStringValue(reqArr, reqEncoding);
        String responseBody = this.getStringValue(resArr, resEncoding);
        String resContentType = response.getContentType();

        if (resContentType != null && isBinaryFile(resContentType)) {
            logger.debug("request contains binary file");
            responseBody = "";
        }

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
            Context.cleanup();
            InternalThreadLocalMap.remove();
            logger.debug("response in keploy resp map: {}", k.getResp().get(keploy_test_id));
        } else {
            Mode.ModeType mode = Mode.getMode();
            // to prevent recording testcases in test mode.
            if (mode != null && mode.equals(Mode.ModeType.MODE_TEST)) {
                return;
            }

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
                GrpcService.CaptureTestCases(requestBody, urlParams, httpResp, protocolType, formData);
            } catch (Exception e) {
                logger.error(CROSS + " failed to capture testCases", e);
            }
        }

        responseWrapper.flushBuffer();

        // doing this will save thread-local from memory leak.
        Context.cleanup();
        InternalThreadLocalMap.remove();
        logger.debug("inside middleware: outgoing response");
    }

    private boolean isBinaryFile(String resContentType) {

        switch (resContentType) {
            case "application/octet-stream":
            case "application/pdf":
            case "image/jpeg":
            case "image/jpg":
            case "image/png":
            case "image/gif":
            case "text/plain":
            case "text/html":
                return true;
            default:
                return false;
        }
    }

    private Map<String, List<MultipartContent>> processMultipart(HttpServletRequest request) throws IOException, ServletException {
        Map<String, List<MultipartContent>> data = new HashMap<>();
        Collection<Part> parts = request.getParts();
        for (Part part : parts) {
            final String partName = part.getName();
            logger.debug("partName:{}", partName);

            if (part.getContentType() != null) {
                // read the content of the "file" part and store it in a request attribute
                InputStream inputStream = part.getInputStream();
                byte[] content = IOUtils.toByteArray(inputStream);
                String fileName = part.getSubmittedFileName();

                MultipartContent multipartContent = new MultipartContent(fileName, content);
                data.computeIfAbsent(partName, x -> new ArrayList<>()).add(multipartContent);

                request.setAttribute("fileContent", content);
            } else {
                InputStream inputStream = part.getInputStream();
                byte[] content = IOUtils.toByteArray(inputStream);

                MultipartContent multipartContent = new MultipartContent(null, content);
                data.computeIfAbsent(partName, x -> new ArrayList<>()).add(multipartContent);
                logger.debug("non-file body:{}", getStringValue(content, String.valueOf(StandardCharsets.UTF_8)));
            }
        }
        return data;
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
        InternalThreadLocalMap.destroy();
    }
}