package io.keploy.servlet;

import io.keploy.grpc.GrpcClient;
import io.keploy.grpc.stubs.Service;
import io.keploy.regression.KeployInstance;
import io.keploy.regression.context.Context;
import io.keploy.regression.keploy.Keploy;
import io.keploy.regression.mode;
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


    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        KeployInstance ki = KeployInstance.getInstance();
        Keploy k = ki.getKeploy();

        System.out.println("Inside Keploy middleware: incoming request");
        System.out.println("Keploy instance-> " + k.getCfg().getApp().toString());

//        Dotenv dotenv = Dotenv.load();
//        dotenv.get("KEPLOY_MODE") != null && dotenv.get("KEPLOY_MODE")

        if (k == null || ("record").equals(new mode().getMode().MODE_OFF.getTypeName())) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        //setting request context
        Context.setCtx(request);


        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);


        filterChain.doFilter(requestWrapper, responseWrapper);


        String requestBody = this.getStringValue(requestWrapper.getContentAsByteArray(), request.getCharacterEncoding());
        String responseBody = this.getStringValue(responseWrapper.getContentAsByteArray(), response.getCharacterEncoding());
        System.out.println("Request-> " + requestBody);
        System.out.println("Response-> " + responseBody);
        responseWrapper.copyBodyToResponse();

        Map<String, String> urlParams = setUrlParams(requestWrapper.getParameterMap());


        GrpcClient grpcClient = new GrpcClient();

        Service.HttpResp.Builder builder = Service.HttpResp.newBuilder();
        Map<String, Service.StrArr> headerMap = builder.getHeaderMap();

        headerMap = getResponseHeaderMap(responseWrapper);
        Service.HttpResp httpResp = builder.setStatusCode(responseWrapper.getStatus()).setBody(responseBody).build();

        System.out.println("Inside Keploy middleware: outgoing response");

        try {
            grpcClient.CaptureTestCases(ki, requestBody, responseBody, urlParams, httpResp);
        } catch (Exception e) {
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
                System.out.println("values-> " + values);
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