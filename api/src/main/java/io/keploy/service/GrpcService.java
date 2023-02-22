package io.keploy.service;

import com.google.protobuf.ProtocolStringList;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.keploy.grpc.stubs.RegressionServiceGrpc;
import io.keploy.grpc.stubs.Service;
import io.keploy.regression.KeployInstance;
import io.keploy.regression.Mode;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import io.keploy.regression.keploy.Keploy;
import io.keploy.utils.AssertKTests;
import io.keploy.utils.MultipartContent;
import io.keploy.utils.Utility;
import me.tongfei.progressbar.ProgressBar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.keploy.regression.Mock.Kind.HTTP_EXPORT;
import static io.keploy.utils.Utility.createFolder;

public class GrpcService {

    private static final Logger logger = LogManager.getLogger(GrpcService.class);

    private static final String CROSS = new String(Character.toChars(0x274C));
    public static RegressionServiceGrpc.RegressionServiceBlockingStub blockingStub = null;
    private static Keploy k = null;
    public static ManagedChannel channel;

    private static final String SET_PLAIN_TEXT = "\033[0;0m";

    private static final String SET_BOLD_TEXT = "\033[0;1m";


    public GrpcService() {
        // Channels are secure by default (via SSL/TLS). For the example we disable TLS to avoid
        // needing certificates.
        k = KeployInstance.getInstance().getKeploy();
        channel = ManagedChannelBuilder.forTarget(getTarget())
                .usePlaintext()
                .build();
        blockingStub = RegressionServiceGrpc.newBlockingStub(channel);

    }


    private String getTarget() {
        String target;
        URL url;
        try {
            url = new URL(k.getCfg().getServer().getURL());
        } catch (MalformedURLException e) {
            logger.error(CROSS + " unable to make GrpcConnection", e);
            return "localhost:6789";
        }

        return url.getAuthority();
    }

    public static void CaptureTestCases(String reqBody, Map<String, String> params, Service.HttpResp httpResp, String protocolType, Map<String, List<MultipartContent>> formData) {
        logger.debug("inside CaptureTestCases");

        Kcontext kctx = Context.getCtx();
        HttpServletRequest ctxReq = kctx.getRequest();
        if (ctxReq == null) {
            logger.error(CROSS + " failed to get keploy context");
            return;
        }

        Service.TestCaseReq.Builder testCaseReqBuilder = Service.TestCaseReq.newBuilder();

        Service.HttpReq.Builder httpReqBuilder = Service.HttpReq.newBuilder();
        String url = ctxReq.getQueryString() == null ? ctxReq.getRequestURI() :
                ctxReq.getRequestURI() + "?" + ctxReq.getQueryString();

        httpReqBuilder.setMethod(ctxReq.getMethod()).setURL(url);
        httpReqBuilder.putAllURLParams(params);
        Map<String, Service.StrArr> headerMap = getRequestHeaderMap(ctxReq);
        httpReqBuilder.putAllHeader(headerMap);
        httpReqBuilder.setBody(reqBody);
        httpReqBuilder.setProtoMajor(Character.getNumericValue(protocolType.charAt(protocolType.length() - 3)));
        httpReqBuilder.setProtoMinor(Character.getNumericValue(protocolType.charAt(protocolType.length() - 1)));

        testCaseReqBuilder.setAppID(k.getCfg().getApp().getName());
        testCaseReqBuilder.setCaptured(Instant.now().getEpochSecond());

        /*
         * The order of path parameters, we are getting from request is not proper.
         * Storing in different order will not block the existing functionality.
         * It's only for grouping the testcases.
         * Below code gives unordered mapping of path variables or path parameters
         * Map<String, String> pathVariables = ((Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE));
         * Hence we are storing the actual Uri not according to the routing pattern.
         * */

        testCaseReqBuilder.setURI(ctxReq.getRequestURI());
        testCaseReqBuilder.setHttpResp(httpResp);
        testCaseReqBuilder.setTestCasePath(k.getCfg().getApp().getTestPath());
        testCaseReqBuilder.setMockPath(k.getCfg().getApp().getMockPath());
        testCaseReqBuilder.addAllMocks(kctx.getMock());

        Capture(testCaseReqBuilder, formData, httpReqBuilder);
    }

    public static void Capture(Service.TestCaseReq.Builder testCaseReqBuilder, Map<String, List<MultipartContent>> formData, Service.HttpReq.Builder httpReqBuilder) {
        new Thread(() -> {
            try {

                // for multipart-request
                List<Service.FormData> form = saveFiles(formData);
                Service.HttpReq httpReq = httpReqBuilder.addAllForm(form).build();
                Service.TestCaseReq testCaseReq = testCaseReqBuilder.setHttpReq(httpReq).setType(HTTP_EXPORT.value).build();

                put(testCaseReq);
            } catch (Exception e) {
                logger.error(CROSS + " failed to send test case to backend", e);
            }
        }).start();
    }

    public static void put(Service.TestCaseReq testCaseReq) {
        Service.postTCResponse postTCResponse;
        try {
            postTCResponse = blockingStub.postTC(testCaseReq);
        } catch (Exception e) {
            logger.error(CROSS + " failed to send testcase to backend, please ensure keploy server is up!", e);
            logger.error(CROSS + " please check keploy server logs if server is up");
            return;
        }
        Map<String, String> tcsId = postTCResponse.getTcsIdMap();
        String id = tcsId.get("id");
        if (id == null) return;

        boolean noise = k.getCfg().getServer().getDenoise();
        if (noise) {
            denoise(id, testCaseReq);
        }
    }

    public static void denoise(String id, Service.TestCaseReq testCaseReq) {
        // run the request again to find noisy fields
        try {
            TimeUnit.SECONDS.sleep(3);
        } catch (InterruptedException e) {
            logger.error(CROSS + " (denoise): unable to sleep", e);
        }

        Service.TestCase.Builder testCaseBuilder = Service.TestCase.newBuilder();
        testCaseBuilder.setId(id);
        testCaseBuilder.setCaptured(testCaseReq.getCaptured());
        testCaseBuilder.setURI(testCaseReq.getURI());
        testCaseBuilder.setHttpReq(testCaseReq.getHttpReq());
        testCaseBuilder.addAllMocks(testCaseReq.getMocksList());
        Service.TestCase testCase = testCaseBuilder.build();

        Service.HttpResp resp2 = simulate(testCase);

        logger.debug("response got from simulate request: {}", resp2);

        Service.TestReq.Builder testReqBuilder = Service.TestReq.newBuilder();
        testReqBuilder.setID(id);
        testReqBuilder.setResp(resp2);
        testReqBuilder.setAppID(k.getCfg().getApp().getName());
        testReqBuilder.setTestCasePath(k.getCfg().getApp().getTestPath());
        testReqBuilder.setMockPath(k.getCfg().getApp().getMockPath());
        testReqBuilder.setType(HTTP_EXPORT.value);
        Service.TestReq bin2 = testReqBuilder.build();

        // send de-noise request to server
        try {
            Service.deNoiseResponse deNoiseResponse = blockingStub.deNoise(bin2);
            logger.debug("denoise message received from server: {}", deNoiseResponse.getMessage());
        } catch (Exception e) {
            logger.error(CROSS + " failed to send de-noise request to backend, please check keploy server logs", e);
        }

    }

    public static Service.HttpResp simulate(Service.TestCase testCase) {
        logger.debug("inside simulate");

        //add mocks to shared context
        k.getMocks().put(testCase.getId(), new ArrayList<>(testCase.getMocksList()));
        k.getMocktime().put(testCase.getId(), testCase.getCaptured());

        //add dependency to shared context
        k.getDeps().put(testCase.getId(), new ArrayList<>(testCase.getDepsList()));

        executeSimulateRequest(testCase);

        Service.HttpResp.Builder resp = GetResp(testCase.getId());

        k.getDeps().remove(testCase.getId());
        k.getMocks().remove(testCase.getId());
        k.getMocktime().remove(testCase.getId());

        return resp.build();
    }

    private static void executeSimulateRequest(Service.TestCase testCase) {
        String url = testCase.getHttpReq().getURL();
        String host = k.getCfg().getApp().getHost();
        String port = k.getCfg().getApp().getPort();
        String method = testCase.getHttpReq().getMethod();
        String body = testCase.getHttpReq().getBody();
        String targetUrl = "http://" + host + ":" + port + url;
        String testId = testCase.getId();
        Map<String, Service.StrArr> headerMap = testCase.getHttpReq().getHeaderMap();

        logger.debug("simulate request's url: {}", targetUrl);
        logger.debug("simulate request's method: {}", method);
        logger.debug("simulate request's headers: {}", headerMap);

        String contentType = headerMap.containsKey("content-type") ? headerMap.get("content-type").getValue(0) : "application/json; charset=utf-8";


        try {
            URL obj = new URL(targetUrl);
            HttpURLConnection conn = (HttpURLConnection) obj.openConnection();
//            conn.setReadTimeout(60000);
//            conn.setConnectTimeout(60000);
            conn.setRequestMethod(method);
            conn.setInstanceFollowRedirects(false);

            setCustomRequestHeaderMap(conn, headerMap);
            conn.setRequestProperty("KEPLOY_TEST_ID", testId);

            if (contentType.contains("multipart")) {
                HttpPostMultipart multipart = new HttpPostMultipart("utf-8", conn);

                List<Service.FormData> formList = testCase.getHttpReq().getFormList();
                for (Service.FormData part : formList) {
                    List<String> vals = new ArrayList<>(part.getValuesList());
                    List<String> paths = new ArrayList<>(part.getPathsList());

                    if (!paths.isEmpty()) {
                        for (String path : paths) {
                            File file = new File(path);
                            multipart.addFilePart(part.getKey(), file);
                        }
                    } else if (!vals.isEmpty()) {
                        for (String val : vals) {
                            multipart.addFormField(part.getKey(), val);
                        }
                    }
                }
                //execute multipart request
                multipart.finish();
                conn.disconnect();
                return;
            }

            if ((method.equals("GET") || method.equals("DELETE")) && !body.isEmpty()) {
                logger.warn("keploy doesn't support {} request with body", method);
            }


            //POST, PUT, PATCH <- requests containing body
            if (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
                conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes());
                os.flush();
                os.close();
                logger.debug("simulate request body set");
            }

            final int responseCode = conn.getResponseCode();
            logger.debug("status code got from simulate request: {}", responseCode);

            final Map<String, List<String>> responseHeaders = conn.getHeaderFields();
            logger.debug("response headers got from simulate request: {}", responseHeaders);

            if (isSuccessfulResponse(conn)) {
                String resBody = getSimulateResponseBody(conn);
                logger.debug("response body got from simulate request: {}", resBody);
            }

            conn.disconnect();
        } catch (IOException e) {
            logger.error(CROSS + " failed sending testcase request to app", e);
        }
    }

    public static boolean isSuccessfulResponse(HttpURLConnection connection) {
        try {
            int responseCode = connection.getResponseCode();
            return responseCode >= 200 && responseCode < 300;
        } catch (IOException e) {
            return false;
        }
    }

    public static String getSimulateResponseBody(HttpURLConnection httpConn) throws IOException {
        String responseBody;
        BufferedReader in = new BufferedReader(new InputStreamReader(httpConn.getInputStream()));
        String inputLine;
        StringBuilder response = new StringBuilder();

        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();

        responseBody = response.toString();
        return responseBody;
    }

    public static Service.HttpResp.Builder GetResp(String id) {

        logger.debug("inside GetResp");
        Service.HttpResp httpResp = k.getResp().get(id);
        if (httpResp == null) {
            logger.debug("response is not present in keploy resp map");
            return Service.HttpResp.newBuilder();
        }

        Service.HttpResp.Builder respBuilder = Service.HttpResp.newBuilder();

        try {
            respBuilder.setBody(httpResp.getBody())
                    .setStatusCode(httpResp.getStatusCode())
                    .setStatusMessage(httpResp.getStatusMessage())
                    .setProtoMajor(httpResp.getProtoMajor())
                    .setProtoMinor(httpResp.getProtoMinor())
                    .putAllHeader(httpResp.getHeaderMap());
        } catch (Exception e) {
            logger.error(CROSS + " failed getting response for http request", e);
            return Service.HttpResp.newBuilder();
        }

        logger.debug("response from keploy resp map");
        return respBuilder;
    }

    public static void Test() {
        String delay = "5";
        try {
            delay = System.getenv("DELAY");
            if (delay != null) {
                k.getCfg().getApp().setDelay(Duration.ofSeconds(Long.parseLong(delay)));
            } else {
                delay = "5";
            }
            TimeUnit.SECONDS.sleep(k.getCfg().getApp().getDelay().getSeconds());
        } catch (InterruptedException e) {
            logger.error(CROSS + " (Test): unable to sleep", e);
        }
        logger.debug("entering test mode");
        logger.info("test starting in " + delay + " sec");

        List<Service.TestCase> tcs = fetch();

        final String RED_CIRCLE = "\uD83D\uDD34";

        if (Mode.getMode().equals(Mode.ModeType.MODE_RECORD) && tcs == null) {
            logger.info("No existing tests found at {} directory", k.getCfg().getApp().getTestPath());
            System.out.println("--------------------------------------------------------------------------------------------\n");
            String endTest = RED_CIRCLE + " You can record your new test cases now.";
            System.out.println(bold(endTest));
            System.out.println("\n--------------------------------------------------------------------------------------------");
            return;
        }

        int total = tcs.size();
        String id;
        try {
            id = start(String.valueOf(total));
        } catch (Exception e) {
            logger.error(CROSS + " failed to start test run", e);
            return;
        }
        logger.info("starting test execution id: {} total tests: {}", id, total);

        AtomicBoolean ok = new AtomicBoolean(true);
        AtomicInteger failedtestCount = new AtomicInteger(0);
        CountDownLatch wg = new CountDownLatch(tcs.size());

        String async_test = System.getenv("ASYNC_TESTING");
        int nThreads = (Boolean.parseBoolean(async_test)) ? 10 : 1;

        ExecutorService service = Executors.newFixedThreadPool(nThreads);
        // call the service for each test case

        String runTestBeforeRecord = System.getenv("RUN_TEST_BEFORE_RECORD");
        boolean runExistingTests = true;
        if (runTestBeforeRecord != null) {
            runExistingTests = Boolean.parseBoolean(runTestBeforeRecord);
        }

        //running tests in record mode in order to maintain the same state of database.
        if (Mode.getMode().equals(Mode.ModeType.MODE_RECORD) && runExistingTests) {
            try (ProgressBar pb = new ProgressBar("KEPLOY-TESTS", total)) {
                runTests(service, pb, ok, wg, total, tcs, id, failedtestCount);
                pb.setExtraMessage("Tests Completed");
            }
        } else if (Mode.getMode().equals(Mode.ModeType.MODE_TEST)) {
            runTests(service, null, ok, wg, total, tcs, id, failedtestCount);
        }

        // wait for all tests to get completed.
        try {
            wg.await();
        } catch (InterruptedException e) {
            logger.error(CROSS + " (Test): unable to wait for tests to get completed", e);
            AssertKTests.finalTestResult.set(false);
        }

        Boolean finalResult = ok.get();
        AssertKTests.finalTestResult.set(finalResult);
        end(id, finalResult);

        logger.info("test run completed with run id [{}]", id);
        logger.info("|| passed overall: {} ||", String.valueOf(finalResult).toUpperCase());

        if (Mode.getMode().equals(Mode.ModeType.MODE_RECORD) && runExistingTests) {
            if (!finalResult) {
                final String test = (failedtestCount.get() > 1) ? "tests" : "test";
                String WARN = "\u26A0\uFE0F";
                String inconsistentState = WARN + " " + bold(failedtestCount.get() + " " + test + " failed, Please make sure your database state is consistent.");
                System.out.println(inconsistentState);
            }
            System.out.println("--------------------------------------------------------------------------------------------\n");
            String endTest = RED_CIRCLE + " Tests have been completed, You can record your new test cases now.";
            System.out.println(bold(endTest));
            System.out.println("\n--------------------------------------------------------------------------------------------");
        }
    }

    private static void runTests(ExecutorService service, ProgressBar pb, AtomicBoolean ok, CountDownLatch wg, int total, List<Service.TestCase> tcs, String id, AtomicInteger failedtestCount) {
        for (int i = 0; i < tcs.size(); i++) {
            Service.TestCase tc = tcs.get(i);
            logger.info("testing {} of {} testcase id: [{}]", (i + 1), total, tc.getId());
            service.submit(() -> {
                boolean pass = check(id, tc);
                if (!pass) {
                    failedtestCount.getAndIncrement();
                    ok.set(false);
                }

                logger.info("result : testcase id: [{}]  passed: {}", tc.getId(), pass);
                wg.countDown();
            });
            if (Mode.getMode().equals(Mode.ModeType.MODE_RECORD)) {
                pb.step(); // for progress bar
            }
        }
    }

    private static String bold(String str) {
        return (SET_BOLD_TEXT + str + SET_PLAIN_TEXT);
    }

    public static String start(String total) {
        logger.debug("inside start function");
        Service.startRequest startRequest = Service.startRequest.newBuilder()
                .setApp(k.getCfg().getApp().getName())
                .setTestCasePath(k.getCfg().getApp().getTestPath())
                .setMockPath(k.getCfg().getApp().getMockPath())
                .setTotal(total).build();

        Service.startResponse startResponse = null;

        try {
            startResponse = blockingStub.start(startRequest);
        } catch (Exception e) {
            logger.error(CROSS + " failed to start test run, please check keploy server logs", e);
            AssertKTests.finalTestResult.set(false);
            System.exit(1);
        }

        return (startResponse != null) ? startResponse.getId() : "";
    }

    public static void end(String id, boolean status) {
        logger.debug("inside end function");
        Service.endRequest endRequest = Service.endRequest.newBuilder().setId(id).setStatus(String.valueOf(status)).build();
        Service.endResponse endResponse;
        try {
            endResponse = blockingStub.end(endRequest);
            logger.debug("response after ending test run: {}", endResponse);
        } catch (Exception e) {
            logger.error(CROSS + " failed to complete test runs, please check keploy server logs", e);
            AssertKTests.finalTestResult.set(false);
            System.exit(1);
        }
    }

    public static List<Service.TestCase> fetch() {
        logger.debug("inside fetch function");

        List<Service.TestCase> testCases = new ArrayList<>();
        int i = 0;
        while (true) {
            Service.getTCSRequest tcsRequest = Service.getTCSRequest.newBuilder()
                    .setApp(k.getCfg().getApp().getName())
                    .setLimit("25")
                    .setOffset(String.valueOf(i))
                    .setTestCasePath(k.getCfg().getApp().getTestPath())
                    .setMockPath(k.getCfg().getApp().getMockPath())
                    .build();

            Service.getTCSResponse tcs = null;

            try {
                tcs = blockingStub.getTCS(tcsRequest);
            } catch (Exception e) {
                if (e.getMessage().contains("no such file or directory")) {
                    if (Mode.getMode().equals(Mode.ModeType.MODE_RECORD)) {
                        return null;
                    } else if (Mode.getMode().equals(Mode.ModeType.MODE_TEST)) {
                        logger.info("No existing tests found at {} directory", k.getCfg().getApp().getTestPath());
                    }
                } else {
                    logger.error(CROSS + " failed to fetch testcases from keploy cloud, please ensure keploy server is up!", e);
                }
                AssertKTests.finalTestResult.set(false);
                System.exit(1);
            }

            if (tcs == null) {
                break;
            }

            int cnt = tcs.getTcsCount();
            if (cnt == 0) {
                break;
            }
            List<Service.TestCase> tc = tcs.getTcsList();
            testCases.addAll(tc);

            boolean eof = tcs.getEof();
            if (eof) {
                break;
            }

            i += 25;
        }

        //reverse in order to get testcases in which they were recorded.
        Collections.reverse(testCases);
        return testCases;
    }

    public static boolean check(String testrunId, Service.TestCase tc) {
        logger.debug("running test case with [{}] testrunId", testrunId);

        Service.HttpResp resp;
        try {
            resp = simulate(tc);
            logger.debug("response got from simulate request: {}", resp);
        } catch (Exception e) {
            logger.error(CROSS + " failed to simulate request on local server", e);
            AssertKTests.finalTestResult.set(false);
            return false;
        }
        Service.TestReq testReq = Service.TestReq.newBuilder()
                .setID(tc.getId())
                .setAppID(k.getCfg().getApp().getName())
                .setRunID(testrunId)
                .setResp(resp)
                .setTestCasePath(k.getCfg().getApp().getTestPath())
                .setMockPath(k.getCfg().getApp().getMockPath())
                .setType(HTTP_EXPORT.value)
                .build();

        Service.testResponse testResponse;
        try {
            testResponse = blockingStub.test(testReq);
        } catch (Exception e) {
            logger.error(CROSS + " failed to send test request to backend, please check keploy server logs", e);
            return false;
        }

        if (testResponse == null) {
            return false;
        }

        Map<String, Boolean> res = testResponse.getPassMap();
        logger.debug("(check): test result of testrunId [{}]: {}", testrunId, res.get("pass"));
        return res.getOrDefault("pass", false);
    }

    private static void setCustomRequestHeaderMap(HttpURLConnection conn, Map<String, Service.StrArr> srcMap) {

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
                    conn.addRequestProperty(key, value);
                }
            }
        }
    }

    private static boolean isModifiable(String key) {
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

    private static Map<String, Service.StrArr> getRequestHeaderMap(HttpServletRequest httpServletRequest) {

        Map<String, Service.StrArr> map = new HashMap<>();

        List<String> headerNames = Collections.list(httpServletRequest.getHeaderNames());
        for (String name : headerNames) {

            List<String> values = Collections.list(httpServletRequest.getHeaders(name));
            Service.StrArr.Builder builder = Service.StrArr.newBuilder();

            for (String s : values) {
                builder.addValue(s);
            }
            Service.StrArr value = builder.build();

            map.put(name, value);
        }
        return map;
    }

    private static List<Service.FormData> saveFiles(Map<String, List<MultipartContent>> multipartData) {
        //TODO: same file but different size, handle that case also, just override the file and give a warning for the same.
        List<Service.FormData> data = new ArrayList<>(multipartData.size());

        for (String partName : multipartData.keySet()) {

            List<MultipartContent> contents = multipartData.get(partName);
            Service.FormData.Builder formDataBuilder = Service.FormData.newBuilder().setKey(partName);

            List<String> values = new ArrayList<>();

            boolean isFile = false;

            for (MultipartContent content : contents) {
                String fileName = content.getFileName();
                byte[] body = content.getBody();
                if (fileName != null) {
                    isFile = true;
                    String filePath = determineFilePath(fileName);
                    saveFile(filePath, body);
                    values.add(filePath);
                } else {
                    isFile = false;
                    String nonFileBody = getStringValue(body, String.valueOf(StandardCharsets.UTF_8));
                    values.add(nonFileBody);
                }
            }

            if (isFile) {
                formDataBuilder.addAllPaths(values);
            } else {
                formDataBuilder.addAllValues(values);
            }

            Service.FormData formData = formDataBuilder.build();
            data.add(formData);
        }
        return data;
    }

    public static String saveFile(String filePath, byte[] body) {

        File file = new File(filePath);
        String fileName = Utility.getFileNameFromPath(filePath);
        if (file.exists()) {
            logger.warn("file {} already exist at location {}", fileName, k.getCfg().getApp().getAssetPath());
        }

        FileOutputStream fos;
        try {
            fos = new FileOutputStream(filePath);
            fos.write(body);
            fos.close();
            logger.debug("saved file at location {}", filePath);
        } catch (
                IOException e) {
            logger.error(CROSS + " location not found", e);
        }
        return filePath;
    }

    public static String determineFilePath(String fileName) {
        String folderPath = k.getCfg().getApp().getAssetPath();
        createFolder(folderPath);
        String filePath = folderPath + "/" + fileName;
        return filePath;
    }

    private static String getStringValue(byte[] contentAsByteArray, String characterEncoding) {
        try {
            return new String(contentAsByteArray, 0, contentAsByteArray.length, characterEncoding);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return "";
    }
}
