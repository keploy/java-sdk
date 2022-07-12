package io.keploy.servlet;

import io.keploy.client.GrpcClient;
import io.keploy.grpc.stubs.Service;
import io.keploy.regression.KeployInstance;
import io.keploy.regression.context.Context;
import io.keploy.regression.keploy.Keploy;
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

    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        KeployInstance ki = KeployInstance.getInstance();
        Keploy k = ki.getKeploy();

        logger.debug("inside middleware: incoming request");

        System.out.println(mode.getMode());
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

            if (name.equals("Date")) continue;

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
