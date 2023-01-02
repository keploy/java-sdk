package io.keploy.utils;

public class MultipartContent {

    private final String fileName;
    private final byte[] body;

    public MultipartContent(String fileName, byte[] body) {
        this.fileName = fileName;
        this.body = body;
    }

    public String getFileName() {
        return fileName;
    }

    public byte[] getBody() {
        return body;
    }

}
