import context.Context;
import io.github.cdimascio.dotenv.Dotenv;
import keploy.Keploy;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;


public class middleware implements Filter {


    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        KeployInstance ki = KeployInstance.getInstance();
        Keploy k = ki.getKeploy();

        Dotenv dotenv = Dotenv.load();

        if (k == null || dotenv.get("KEPLOY_MODE") != null && dotenv.get("KEPLOY_MODE").equals(new mode().getMode().MODE_OFF.getTypeName())) {
            filterChain.doFilter(servletRequest, servletResponse);
        }

        //setting request context
        Context.setCtx((HttpServletRequest)servletRequest);

        HttpServletRequestWrapper httpServletRequestWrapper = new HttpServletRequestWrapper((HttpServletRequest) servletRequest);

        ServletInputStream reqStream = httpServletRequestWrapper.getInputStream();
        ByteArrayOutputStream byteArrayReq = new ByteArrayOutputStream();

        byte[] reqBody = byteArrayReq.toByteArray();

        //to capture request body
        reqStream.read(reqBody);

        //getting url params map
        Map params = httpServletRequestWrapper.getParameterMap();

        HttpServletResponseWrapper httpServletResponseWrapper = new HttpServletResponseWrapper((HttpServletResponse) servletResponse);

        filterChain.doFilter(httpServletRequestWrapper, httpServletResponseWrapper);

        ServletOutputStream resStream = httpServletResponseWrapper.getOutputStream();

        ByteArrayOutputStream byteArrayRes = new ByteArrayOutputStream();
        byte[] resBody = byteArrayRes.toByteArray();

        //to capture response body
        resStream.write(resBody);


    }

    @Override
    public void destroy() {

    }
}