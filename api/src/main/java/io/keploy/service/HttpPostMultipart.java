package io.keploy.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.HttpURLConnection;

import java.net.URLConnection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class HttpPostMultipart {
    // reference: https://blog.cpming.top/p/httpurlconnection-multipart-form-data
    private static final Logger logger = LogManager.getLogger(HttpPostMultipart.class);

    private static final String CROSS = new String(Character.toChars(0x274C));

    private final String boundary;
    private static final String LINE = "\r\n";
    private final HttpURLConnection httpConn;
    private final String charset;
    private final OutputStream outputStream;
    private final PrintWriter writer;

    /**
     * This constructor initializes a new HTTP POST request with content type
     * is set to multipart/form-data
     *
     * @param charset
     * @throws IOException
     */
    public HttpPostMultipart(String charset, HttpURLConnection httpConn) throws IOException {
        boundary = UUID.randomUUID().toString();
        this.charset = charset;
        this.httpConn = httpConn;
        this.httpConn.setUseCaches(false);
        this.httpConn.setDoOutput(true);    // indicates POST method
        this.httpConn.setDoInput(true);
        this.httpConn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        outputStream = this.httpConn.getOutputStream();
        writer = new PrintWriter(new OutputStreamWriter(outputStream, charset), true);
    }

    /**
     * Adds a form field to the request
     *
     * @param name  field name
     * @param value field value
     */
    public void addFormField(String name, String value) {
        writer.append("--" + boundary).append(LINE);
        writer.append("Content-Disposition: form-data; name=\"" + name + "\"").append(LINE);
        writer.append("Content-Type: text/plain; charset=" + charset).append(LINE);
        writer.append(LINE);
        writer.append(value).append(LINE);
        writer.flush();
    }

    /**
     * Adds a upload file section to the request
     *
     * @param fieldName
     * @param uploadFile
     * @throws IOException
     */
    public void addFilePart(String fieldName, File uploadFile)
            throws IOException {
        String fileName = uploadFile.getName();
        writer.append("--" + boundary).append(LINE);
        writer.append("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"").append(LINE);
        writer.append("Content-Type: " + URLConnection.guessContentTypeFromName(fileName)).append(LINE);
        writer.append("Content-Transfer-Encoding: binary").append(LINE);
        writer.append(LINE);
        writer.flush();

        FileInputStream inputStream = new FileInputStream(uploadFile);
        byte[] buffer = new byte[4096];
        int bytesRead = -1;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
        outputStream.flush();
        inputStream.close();
        writer.append(LINE);
        writer.flush();
    }

    /**
     * Completes the request and receives response from the server.
     *
     * @return String as response in case the server returned
     * status OK, otherwise an exception is thrown.
     * @throws IOException
     */
    public void finish() throws IOException {
        String responseBody = "";
        writer.flush();
        writer.append("--" + boundary + "--").append(LINE);
        writer.close();

        // checks server's status code first
        final int status = this.httpConn.getResponseCode();
        logger.debug("status code got from simulate request: {}", status);

        final Map<String, List<String>> responseHeaders = httpConn.getHeaderFields();
        logger.debug("response headers got from simulate request: {}", responseHeaders);

        if (GrpcService.isSuccessfulResponse(httpConn)) {
            responseBody = GrpcService.getSimulateResponseBody(httpConn);
            logger.debug("response body got from multipart simulate request: {}", responseBody);
        } else {
            throw new IOException("Server returned non-OK status: " + status);
        }
    }
}