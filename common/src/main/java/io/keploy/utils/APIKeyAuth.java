package io.keploy.utils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;

public class APIKeyAuth {

    // Request Object
    public static class ApiKeyReq {
        @JsonProperty("installationID")
        private String installationID;
        @JsonProperty("apikey")
        private String apiKey;
        @JsonProperty("firstTime")
        private boolean firstTime;

        public ApiKeyReq(String installationID, String apiKey, boolean firstTime) {
            this.installationID = installationID;
            this.apiKey = apiKey;
            this.firstTime = firstTime;
        }
    }

    // Response Object
    public static class ApiKeyRes {
        @JsonProperty("isValid")
        private boolean isValid;
        @JsonProperty("cid")
        private String companyId;
        @JsonProperty("email")
        private String emailId;
        @JsonProperty("jwtToken")
        private String jwtToken;
        @JsonProperty("error")
        private String error;

        public boolean isValid() {
            return isValid;
        }

        public String getCompanyId() {
            return companyId;
        }

        public String getEmailId() {
            return emailId;
        }

        public String getJwtToken() {
            return jwtToken;
        }

        public String getError() {
            return error;
        }
    }

    public static ApiKeyRes checkApiKeyAuth(String host, String id, String apiKey, boolean firstTime) throws IOException {
        String url = host + "/auth/apikey";
        ApiKeyReq requestBody = new ApiKeyReq(id, apiKey, firstTime);

        // Serialize the request body to JSON
        ObjectMapper objectMapper = new ObjectMapper();
        String requestJSON = objectMapper.writeValueAsString(requestBody);

        // Create HTTP POST request
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");

        // Write JSON payload
        try (OutputStream os = connection.getOutputStream()) {
            os.write(requestJSON.getBytes("utf-8"));
        }

        // Read the response
        int responseCode = connection.getResponseCode();
        try (InputStream responseStream = responseCode >= 400
                ? connection.getErrorStream() : connection.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream))) {

            StringBuilder responseBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                responseBuilder.append(line);
            }

            // Deserialize the response JSON
            if (responseCode >= 200 && responseCode < 300) {
                return objectMapper.readValue(responseBuilder.toString(), ApiKeyRes.class);
            } else {
                throw new IOException("Failed to authenticate. HTTP Code: " + responseCode +
                        ". Response: " + responseBuilder.toString());
            }
        }
    }

    public static void main(String[] args) {
        try {
            String host = "http://localhost:8083"; // Replace with your host
            String id = "81e4cd33f3194562859056b128c9es7f";
            String apiKey = "Xz9V+StjgAmt4bx0xw==";
            boolean firstTime = true;

            ApiKeyRes response = checkApiKeyAuth(host, id, apiKey, firstTime);

            if (response.isValid()) {
                System.out.println("Authentication successful!");
                System.out.println("Company ID: " + response.getCompanyId());
                System.out.println("Email ID: " + response.getEmailId());
                System.out.println("JWT Token: " + response.getJwtToken());
            } else {
                System.out.println("Authentication failed: " + response.getError());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
