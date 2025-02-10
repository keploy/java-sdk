package io.keploy.utils;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class ResponseWrapper extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final PrintWriter writer = new PrintWriter(new OutputStreamWriter(buffer));
    private ServletOutputStream servletOutputStream;

    public ResponseWrapper(HttpServletResponse response) {
        super(response);

        this.servletOutputStream = new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
                // Not implemented
            }

            @Override
            public void write(int b) throws IOException {
                buffer.write(b);
            }
        };
    }

    @Override
    public ServletOutputStream getOutputStream() {
        return servletOutputStream;
    }

    @Override
    public PrintWriter getWriter() {
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        writer.flush();
        buffer.flush();
    }

    /**
     * Gets the captured response body as a string.
     */
    public String getCapturedResponseBody() {
        flushBufferSilently();
        return buffer.toString(); // Default encoding or specify: buffer.toString("UTF-8");
    }

    public Map<String, String> getHeaderMap() {
        Map<String, String> headers = new HashMap<>();
        Collection<String> headerNames = getHeaderNames();
        for (String name : headerNames) {
            headers.put(name, getHeader(name));
        }
        return headers;
    }

    /**
     * Writes the captured response back to the original response.
     */
    public void writeResponseToClient() throws IOException {
        // Write the captured content back to the original response output stream
        ServletOutputStream originalOutputStream = super.getOutputStream();
        buffer.writeTo(originalOutputStream);
        originalOutputStream.flush();
    }

    private void flushBufferSilently() {
        try {
            flushBuffer();
        } catch (IOException e) {
            // Ignored
        }
    }
}
