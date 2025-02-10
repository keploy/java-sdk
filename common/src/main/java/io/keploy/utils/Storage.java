package io.keploy.utils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.report.DirectorySourceFileLocator;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.xml.XMLFormatter;
import org.json.JSONObject;

public class Storage {

    private final String serverURL;

    public Storage(String serverURL) {
        this.serverURL = serverURL;
    }

    public static void generateXMLReport(
            CoverageBuilder coverageBuilder,
            SessionInfoStore sessionInfoStore,
            ExecutionDataStore executionDataStore,
            File reportFile) throws IOException {

        // Step 4.1: Define the XMLFormatter
        XMLFormatter xmlFormatter = new XMLFormatter();

        // Step 4.2: Use FileOutputStream to write the report
        try (FileOutputStream outputStream = new FileOutputStream(reportFile)) {
            IReportVisitor visitor = xmlFormatter.createVisitor(outputStream);

            // Step 4.3: Provide session and execution data
            visitor.visitInfo(sessionInfoStore.getInfos(), executionDataStore.getContents());

            // Step 4.4: Build the coverage bundle
            IBundleCoverage bundle = coverageBuilder.getBundle("GeneratedCoverageReport");

            // Step 4.5: Populate the coverage report structure
            visitor.visitBundle(
                    bundle,
                    new DirectorySourceFileLocator(new File(System.getProperty("user.dir") + "/src"), "utf-8", 4)
            );

            // Step 4.6: Finalize report generation
            visitor.visitEnd();
        }
    }

    public void upload(InputStream file, String coverageName, String appName, String uniqueId, String token) throws IOException {
        // Prepare the multipart form file upload request
        String boundary = "----Boundary" + System.currentTimeMillis();
        HttpURLConnection connection = (HttpURLConnection) new URL(serverURL + "/coverage/upload").openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setRequestProperty("Authorization", "Bearer " + token);

        // also add an uuid as field to support concurrency
        try (DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream())) {
            // Write the file part
            writeFormFile(outputStream, "coverage", "coverage.xml", file, boundary);

            // Write the appName field
            writeFormField(outputStream, "appName", appName, boundary);

            // Write the mockName field
            writeFormField(outputStream, "coverageName", coverageName, boundary);

            writeFormField(outputStream,"uniqueId",uniqueId,boundary);
            // Write the closing boundary
            outputStream.writeBytes("--" + boundary + "--\r\n");
        }

        // Execute the request
        int responseCode = connection.getResponseCode();
        try (InputStream responseStream = responseCode >= 400
                ? connection.getErrorStream() : connection.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream))) {

            StringBuilder responseBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                responseBuilder.append(line);
            }

            // Parse the response
            String response = responseBuilder.toString();
            JSONObject jsonResponse = new JSONObject(response);

            boolean isSuccess = jsonResponse.optBoolean("isSuccess", false);
            String error = jsonResponse.optString("error", "");

            if (responseCode != 200 || !isSuccess) {
                throw new IOException("Upload failed with status code: " + responseCode + " and error: " + error);
            }

            System.out.println("Coverage uploaded successfully");
        }
    }

    private void writeFormFile(DataOutputStream outputStream, String fieldName, String fileName, InputStream fileStream, String boundary) throws IOException {
        outputStream.writeBytes("--" + boundary + "\r\n");
        outputStream.writeBytes("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + Paths.get(fileName).getFileName() + "\"\r\n");
        outputStream.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = fileStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
        outputStream.writeBytes("\r\n");
    }

    private void writeFormField(DataOutputStream outputStream, String fieldName, String value, String boundary) throws IOException {
        outputStream.writeBytes("--" + boundary + "\r\n");
        outputStream.writeBytes("Content-Disposition: form-data; name=\"" + fieldName + "\"\r\n\r\n");
        outputStream.writeBytes(value + "\r\n");
    }

    public static void main(String[] args) {
        try {
            String serverURL = "http://localhost:8083"; // Replace with your server URL
            Storage storage = new Storage(serverURL);

            String appName = "myAppJava";
            String coverageName = "testCoverage";
            String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VybmFtZSI6IiIsInVzZXJJZCI6IjY2ZWFhMzZhNTVjMmI1Njk4NTI2YjMyZSIsImVtYWlsIjoiYW5vdGhlckBnbWFpbC5jb20iLCJyb2xlIjoiVVNFUiIsInN0YXR1cyI6IkFDVElWRSIsImNpZCI6ImtlcGxveS5pbyIsImZlYXR1cmVGbGFncyI6eyJFTlRFUlBSSVNFX0NPTlNPTEUiOnRydWUsIlVTRVJfTUFOQUdFTUVOVCI6dHJ1ZX0sImV4cCI6MTczNTY0OTEzNX0.A8kfWG6NkdwLp9pNx7CW-l7AXXzEdveXIghQhWWqVHo";
            String uniqueId = "123";
            // Example file as InputStream
            InputStream file = Files.newInputStream(new File("/Users/sarthak_1/Documents/Keploy/Lima-workspace/samples-java/user-manager/coverage.xml").toPath());
            storage.upload(file, coverageName, appName,uniqueId, token);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

