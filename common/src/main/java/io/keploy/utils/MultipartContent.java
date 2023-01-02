package io.keploy.utils;

public class FileContent {
    private String fileName;
    private String partName;
    private byte[] fileBody;

    public FileContent(String fileName, String partName, byte[] fileBody) {
        this.fileName = fileName;
        this.partName = partName;
        this.fileBody = fileBody;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getPartName() {
        return partName;
    }

    public void setPartName(String partName) {
        this.partName = partName;
    }

    public byte[] getFileBody() {
        return fileBody;
    }

    public void setFileBody(byte[] fileBody) {
        this.fileBody = fileBody;
    }
}
