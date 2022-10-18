package io.keploy.utils;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class GenericRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] buffer;

    public GenericRequestWrapper(HttpServletRequest request) throws IOException {
        super(request);

        //Get Stream from Request Body
        InputStream is = request.getInputStream();

        //Convert Stream to byte array and keep it in instance variable
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buff = new byte[1024];
        int read;
        while ((read = is.read(buff)) > 0) {
            baos.write(buff, 0, read);
        }

        this.buffer = baos.toByteArray();
    }

    public byte[] getData() {
        return this.buffer;
    }

    //Replace the body acquisition source with this method
    @Override
    public ServletInputStream getInputStream() {
        //Initialize Stream class and return
        return new BufferedServletInputStream(this.buffer);
    }
}

class BufferedServletInputStream extends ServletInputStream {

    private final ByteArrayInputStream inputStream;

    //Initialize with byte array
    public BufferedServletInputStream(byte[] buffer) {
        this.inputStream = new ByteArrayInputStream(buffer);
    }

    @Override
    public int available() {
        return inputStream.available();
    }

    @Override
    public int read() {
        return inputStream.read();
    }

    @Override
    public int read(byte[] b, int off, int len) {
        return inputStream.read(b, off, len);
    }

    @Override
    public boolean isFinished() {
        return inputStream.available() == 0;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void setReadListener(ReadListener readListener) {

    }
}

