package io.keploy.servlet;

import com.google.protobuf.ProtocolStringList;
import io.keploy.regression.context.Kcontext;

import io.keploy.service.GrpcService;
import io.keploy.grpc.stubs.Service;
import io.keploy.regression.KeployInstance;
import io.keploy.regression.context.Context;
import io.keploy.regression.keploy.AppConfig;
import io.keploy.regression.keploy.Config;
import io.keploy.regression.keploy.Keploy;
import io.keploy.regression.keploy.ServerConfig;
import io.keploy.regression.mode;
import io.keploy.utils.GenericResponseWrapper;
import io.keploy.utils.HaltThread;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


@Component
public class middleware extends HttpFilter {

    private static final Logger logger = LogManager.getLogger(middleware.class);

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        //just like wait groups, used in testfile
        CountDownLatch countDownLatch = HaltThread.getInstance().getCountDownLatch();

        logger.debug("initializing keploy");
        KeployInstance ki = KeployInstance.getInstance();
        Keploy kp = new Keploy();
        Config cfg = new Config();
        AppConfig appConfig = new AppConfig();
        if (System.getenv("APP_NAME") != null) {
            appConfig.setName(System.getenv("APP_NAME"));
        }
        if (System.getenv("APP_PORT") != null) {
            appConfig.setPort(System.getenv("APP_PORT"));
        }

        ServerConfig serverConfig = new ServerConfig();

        if (System.getenv("KEPLOY_URL") != null) {
            serverConfig.setURL(System.getenv("KEPLOY_URL"));
        }

        cfg.setApp(appConfig);
        cfg.setServer(serverConfig);
        kp.setCfg(cfg);
        ki.setKeploy(kp);

        GrpcService grpcService = new GrpcService();

        final mode.ModeType KEPLOY_MODE = mode.getMode();

        new Thread(() -> {
            if (KEPLOY_MODE != null && KEPLOY_MODE.equals(mode.ModeType.MODE_TEST)) {
                try {
                    logger.debug("calling test Method");
                    grpcService.Test();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                //to stop after running all tests
                countDownLatch.countDown(); // when running tests using cmd
            }
        }).start();
    }

    @Override
    public void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        KeployInstance ki = KeployInstance.getInstance();
        Keploy k = ki.getKeploy();

        logger.debug("inside middleware: incoming request");

        logger.debug("mode: {}", mode.getMode());

        if (k == null || mode.getMode() != null && (mode.getMode()).equals(mode.ModeType.MODE_OFF)) {
            filterChain.doFilter(request, response);
            return;
        }

        //setting request context
        Context.setCtx(new Kcontext(request, null, null, null));


        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request);
        GenericResponseWrapper responseWrapper = new GenericResponseWrapper(response);

        //calling next
        filterChain.doFilter(requestWrapper, responseWrapper);

        byte[] reqArr = requestWrapper.getContentAsByteArray();
        byte[] resArr = responseWrapper.getData();

        String requestBody = this.getStringValue(reqArr, request.getCharacterEncoding());
        String responseBody = this.getStringValue(resArr, response.getCharacterEncoding());

        logger.debug("request body inside middleware: {}", requestBody);
        logger.debug("response body inside middleware: {}", responseBody);

        OutputStream out = response.getOutputStream();

//        response.setHeader("Content-Length", String.valueOf(resArr.length));
        out.write(resArr);
        out.close();

        // to write headers from buffer
        response.flushBuffer();


        Map<String, Service.StrArr> simResponseHeaderMap = getResponseHeaderMap(responseWrapper);
        Service.HttpResp simulateResponse = Service.HttpResp.newBuilder().setStatusCode(responseWrapper.getStatus()).setBody(responseBody).putAllHeader(simResponseHeaderMap).build();

        logger.debug("simulate response inside middleware: {}", simulateResponse);

        String keploy_test_id = request.getHeader("KEPLOY_TEST_ID");

        logger.debug("KEPLOY_TEST_ID: {}", keploy_test_id);

        if (keploy_test_id != null) {
            k.getResp().put(keploy_test_id, simulateResponse);
            logger.debug("response in keploy resp map: {} ", k.getResp().get(keploy_test_id));
        } else {

            Map<String, String> urlParams = setUrlParams(requestWrapper.getParameterMap());

            Service.HttpResp.Builder builder = Service.HttpResp.newBuilder();
            Map<String, Service.StrArr> headerMap = getResponseHeaderMap(responseWrapper);
            Service.HttpResp httpResp = builder.setStatusCode(responseWrapper.getStatus()).setBody(responseBody).putAllHeader(headerMap).build();


            GrpcService grpcService = new GrpcService();

            try {
                grpcService.CaptureTestCases(ki, requestBody, urlParams, httpResp);
            } catch (Exception e) {
                logger.error("failed to capture testCases");
                throw new RuntimeException(e);
            }
        }

        logger.debug("inside middleware: outgoing response");
    }


    public Map<String, Service.StrArr> getResponseHeaderMap(GenericResponseWrapper contentCachingResponseWrapper) {

        Map<String, Service.StrArr> map = new HashMap<>();

        List<String> headerNames = contentCachingResponseWrapper.getHeaderNames().stream().collect(Collectors.toList());

        for (String name : headerNames) {

            if (name == null) continue;

            List<String> values = contentCachingResponseWrapper.getHeaders(name).stream().collect(Collectors.toList());
            Service.StrArr.Builder builder = Service.StrArr.newBuilder();

            for (int i = 0; i < values.size(); i++) {
                builder.addValue(values.get(i));
            }
            Service.StrArr value = builder.build();

            map.put(name, value);
        }
        return map;
    }

    public Map<String, String> setUrlParams(Map<String, String[]> param) {
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
