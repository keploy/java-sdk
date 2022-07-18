package io.keploy.servlet;

import io.keploy.client.GrpcClient;
import io.keploy.grpc.stubs.Service;
import io.keploy.regression.KeployInstance;
import io.keploy.regression.context.Context;
import io.keploy.regression.keploy.AppConfig;
import io.keploy.regression.keploy.Config;
import io.keploy.regression.keploy.Keploy;
import io.keploy.regression.keploy.ServerConfig;
import io.keploy.regression.mode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Component
public class middleware implements Filter {

    private static final Logger logger = LogManager.getLogger("Filter");

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        logger.debug("Initializing Keploy Instance");

        new Thread(() -> {
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

            GrpcClient grpcClient = new GrpcClient();

            String KEPLOY_MODE = "record";
            if (System.getenv("KEPLOY_MODE") != null) {
                KEPLOY_MODE = System.getenv("KEPLOY_MODE");
            }


            if (KEPLOY_MODE != null && KEPLOY_MODE.equals(mode.ModeType.MODE_TEST.getTypeName())) {
                try {
                    logger.debug("calling test Method");
                    grpcClient.Test();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                System.exit(0);
            }

        }).start();
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        KeployInstance ki = KeployInstance.getInstance();
        Keploy k = ki.getKeploy();

        logger.debug("inside middleware: incoming request");

        logger.debug(mode.getMode());

        if (k == null || mode.getMode() != null && (mode.getMode()).equals(mode.ModeType.MODE_OFF)) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }


        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        //setting request context
        Context.setCtx(request);


        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        //calling next
        filterChain.doFilter(requestWrapper, responseWrapper);


        String requestBody = this.getStringValue(requestWrapper.getContentAsByteArray(), request.getCharacterEncoding());
        String responseBody = this.getStringValue(responseWrapper.getContentAsByteArray(), response.getCharacterEncoding());

        responseWrapper.copyBodyToResponse();

        Service.HttpResp simulateResponse = Service.HttpResp.newBuilder().setStatusCode(responseWrapper.getStatus()).setBody(responseBody).putAllHeader(getResponseHeaderMap(responseWrapper)).build();

        String keploy_test_id = request.getHeader("KEPLOY_TEST_ID");
        if (keploy_test_id != null) {
            k.getResp().put(keploy_test_id, simulateResponse);
            return;
        }

        Map<String, String> urlParams = setUrlParams(requestWrapper.getParameterMap());

        Service.HttpResp.Builder builder = Service.HttpResp.newBuilder();
        Map<String, Service.StrArr> headerMap = builder.getHeaderMap();


        headerMap = getResponseHeaderMap(responseWrapper);
        Service.HttpResp httpResp = builder.setStatusCode(responseWrapper.getStatus()).setBody(responseBody).putAllHeader(headerMap).build();

        logger.debug("inside middleware: outgoing response");

        GrpcClient grpcClient = new GrpcClient();

        try {
            grpcClient.CaptureTestCases(ki, requestBody, responseBody, urlParams, httpResp);
        } catch (Exception e) {
            logger.error("failed to capture testCases");
            throw new RuntimeException(e);
        }
    }

    public Map<String, Service.StrArr> getResponseHeaderMap(ContentCachingResponseWrapper contentCachingResponseWrapper) {

        Map<String, Service.StrArr> map = new HashMap<>();

        List<String> headerNames = contentCachingResponseWrapper.getHeaderNames().stream().collect(Collectors.toList());

        for (String name : headerNames) {

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
