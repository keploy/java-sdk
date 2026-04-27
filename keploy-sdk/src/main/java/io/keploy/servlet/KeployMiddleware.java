package io.keploy.servlet;

import io.keploy.dedup.KeployDedupAgent;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.IOException;

/**
 * Servlet filter that starts and stops the Java dynamic dedup agent with the application lifecycle.
 */
public final class KeployMiddleware implements Filter {

    /**
     * Creates a middleware instance for servlet containers.
     */
    public KeployMiddleware() {
    }

    @Override
    public void init(FilterConfig filterConfig) {
        KeployDedupAgent.start();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
        KeployDedupAgent.stop();
    }
}
