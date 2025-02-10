package io.keploy.utils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;

public class Download {

    private String serverURL;

    public Download(String serverURL) {
        this.serverURL = serverURL;
    }
    public InputStream download(String mockName, String appName, String userName, String jwtToken) throws IOException {
        // Build the URL
        String urlString = String.format("%s/mock/download?appName=%s&mockName=%s&userName=%s", serverURL, appName, mockName, userName);
        URL url = new URL(urlString);

        // Open connection
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        // Set headers
        connection.setRequestProperty("Authorization", "Bearer " + jwtToken);

        // Connect and get response
        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            try (InputStream errorStream = connection.getErrorStream()) {
                String errorMessage = errorStream != null ? new String(errorStream.readAllBytes()) : "No error message available";
                throw new IOException(String.format("Download failed with status code: %d, message: %s", responseCode, errorMessage.trim()));
            }
        }

        // Return the input stream for the response body
        return connection.getInputStream();
    }

    public static void main(String[] args) {
        Download storage = new Download("http://example.com"); // Replace with your server URL
        String mockName = "mockName";
        String appName = "appName";
        String userName = "userName";
        String jwtToken = "yourJwtToken";
        String localMockPath = "path/to/local/mock/file";

        try {
            // Download the mock file
            InputStream cloudFile = storage.download(mockName, appName, userName, jwtToken);

            // Save the downloaded mock file locally
            File file = new File(localMockPath);
            Files.createDirectories(Paths.get(file.getParent())); // Ensure directories exist

            try (FileOutputStream outputStream = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = cloudFile.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            } catch (IOException e) {

                throw e;
            } finally {
                cloudFile.close();
            }
        } catch (IOException e) {
            System.out.println("Error:::"+e);
        }
    }
}