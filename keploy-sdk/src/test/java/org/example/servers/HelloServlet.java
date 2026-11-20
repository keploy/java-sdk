package org.example.servers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * HelloServlet answers "hello", whole and flushed: the client has it all.
 * With ?async=, it first hands work to Spring as AsyncWork does, for
 * ?sleep= milliseconds.
 */
public final class HelloServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String async = request.getParameter("async");
        if (async != null) {
            try {
                AsyncWork.hand(async, Long.parseLong(request.getParameter("sleep")));
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        response.setContentType("text/plain");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }
}
