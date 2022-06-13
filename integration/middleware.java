import regression.context.Context;
import io.github.cdimascio.dotenv.Dotenv;
import regression.keploy.Keploy;
import org.springframework.stereotype.Component;
import regression.KeployInstance;
import regression.mode;
import stubs.Service;
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

        Dotenv dotenv = Dotenv.load();
//        dotenv.get("KEPLOY_MODE") != null && dotenv.get("KEPLOY_MODE")

        if (k == null || ("record").equals(new mode().getMode().MODE_OFF.getTypeName())) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        //setting request context
        Context.setCtx((HttpServletRequest) servletRequest);

        HttpServletRequestWrapper httpServletRequestWrapper = new HttpServletRequestWrapper((HttpServletRequest) servletRequest);

        ServletInputStream reqStream = httpServletRequestWrapper.getInputStream();
        ByteArrayOutputStream byteArrayReq = new ByteArrayOutputStream();

        byte[] reqBody = byteArrayReq.toByteArray();

        //to capture request body
        reqStream.read(reqBody);

        //getting url params map

        Map<String, String> urlParams = setUrlParams(httpServletRequestWrapper.getParameterMap());

        HttpServletResponseWrapper httpServletResponseWrapper = new HttpServletResponseWrapper((HttpServletResponse) servletResponse);

        //calling next
        filterChain.doFilter(httpServletRequestWrapper, httpServletResponseWrapper);

        ServletOutputStream resStream = httpServletResponseWrapper.getOutputStream();

        ByteArrayOutputStream byteArrayRes = new ByteArrayOutputStream();
        byte[] resBody = byteArrayRes.toByteArray();

        //to capture response body
        resStream.write(resBody);

        GrpcClient grpcClient = new GrpcClient();

        Service.HttpResp.Builder builder = Service.HttpResp.newBuilder();
        Map<String, Service.StrArr> headerMap = builder.getHeaderMap();

        setResponseHeaderMap(httpServletResponseWrapper, headerMap);
        Service.HttpResp httpResp = builder.setStatusCode(httpServletResponseWrapper.getStatus()).setBody(resBody.toString()).build();

        try {
            grpcClient.CaptureTestCases(ki, reqBody, resBody, urlParams, httpResp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void setResponseHeaderMap(HttpServletResponseWrapper httpServletResponseWrapper, Map<String, Service.StrArr> headerMap) {

        List<String> headerNames = httpServletResponseWrapper.getHeaderNames().stream().collect(Collectors.toList());

        for (String name : headerNames) {

            List<String> values = httpServletResponseWrapper.getHeaders(name).stream().collect(Collectors.toList());
            Service.StrArr.Builder builder = Service.StrArr.newBuilder();

            for (int i = 0; i < values.size(); i++) {
                builder.setValue(i, values.get(i));
            }
            Service.StrArr value = builder.build();

            headerMap.put(name, value);
        }
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

    @Override
    public void destroy() {

    }
}