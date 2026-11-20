package org.example.servers;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

/**
 * TailFilter runs app code after the chain, once the response is with the
 * client (as a request log or an authentication filter does), after
 * the request's ?tail= milliseconds: a thread descheduled under load.
 */
public final class TailFilter implements Filter {

    @Override
    public void init(FilterConfig config) {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        chain.doFilter(request, response);
        response.flushBuffer();
        String tail = request.getParameter("tail");
        if (tail != null) {
            try {
                Thread.sleep(Long.parseLong(tail));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Tail.ran();
        }
    }

    @Override
    public void destroy() {
    }
}
