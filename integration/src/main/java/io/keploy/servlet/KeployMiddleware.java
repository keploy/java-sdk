package io.keploy.servlet;

import io.grpc.netty.shaded.io.netty.util.internal.InternalThreadLocalMap;
import io.keploy.dedup.KeployDedupAgent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.IOException;

public class KeployMiddleware implements Filter {

    private static final Logger LOGGER = LogManager.getLogger(KeployMiddleware.class);

    @Override
    public void init(FilterConfig filterConfig) {
        LOGGER.debug("Keploy middleware initialized");
        KeployDedupAgent.start();
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        filterChain.doFilter(servletRequest, servletResponse);
    }

    @Override
    public void destroy() {
        InternalThreadLocalMap.destroy();
        KeployDedupAgent.stop();
    }
}
