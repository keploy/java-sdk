package io.keploy.utils;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

public class RequestWrapper extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    public RequestWrapper(HttpServletRequest request) throws IOException {
        super(request);

        // Read and cache the request body
        InputStream inputStream = request.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int bytesRead;
        while ((bytesRead = inputStream.read(data)) != -1) {
            buffer.write(data, 0, bytesRead);
        }
        this.cachedBody = buffer.toByteArray();
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(this.cachedBody);
        return new CachedServletInputStream(byteArrayInputStream);
    }

    public byte[] getCachedBody() {
        return cachedBody;
    }

    public String getMethod() {
        return super.getMethod();
    }

    public int getProtocolMajor() {
        String protocol = super.getProtocol();
        return Character.getNumericValue(protocol.charAt(protocol.length() - 3));
    }

    public int getProtocolMinor() {
        String protocol = super.getProtocol();
        return Character.getNumericValue(protocol.charAt(protocol.length() - 1));
    }

    public Map<String, String> getHeaderMap() {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> headerNames = getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            headers.put(name, getHeader(name));
        }
        return headers;
    }


    // Inner class to provide a custom ServletInputStream
    private static class CachedServletInputStream extends ServletInputStream {
        private final InputStream inputStream;

        public CachedServletInputStream(InputStream inputStream) {
            this.inputStream = inputStream;
        }

        @Override
        public int read() throws IOException {
            return inputStream.read();
        }

        @Override
        public boolean isFinished() {
            try {
                return inputStream.available() == 0;
            } catch (IOException e) {
                return true;
            }
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException();
        }
    }
}
