package io.keploy.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.keploy.utils.models.Http;
import io.keploy.utils.models.TestData;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ApiClient {

    private static final String API_ENDPOINT = "https://api.keploy.io/e2e/tests";
    public static String sessionID = "";
    public static String basePath = "";
    public static String jwtToken = "";
    /**
     * Sends a TestData object containing the captured HTTP request and response
     * to the API endpoint.
     *
     * @param testData the TestData object containing HTTP request and response details
     */
    public void sendTestDataToAPI(TestData testData) {
        try {
            // Serialize the TestData object to JSON
            testData.id = sessionID;
            ObjectMapper mapper = new ObjectMapper();
            String jsonPayload = mapper.writeValueAsString(testData);

            // Open connection to the API endpoint
            URL url = new URL(API_ENDPOINT);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            // Configure the connection
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + jwtToken);
            connection.setDoOutput(true);

            // Send the JSON payload
            try (OutputStream os = connection.getOutputStream()) {
                os.write(jsonPayload.getBytes());
                os.flush();
            }

            // Read the response
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                System.out.println("[Keploy Agent] Data sent successfully to the cloud.");
            } else {
                System.err.println("[Keploy Agent] Failed to send data. Response code: " + responseCode);
            }

            connection.disconnect();
        } catch (Exception e) {
            System.err.println("[ApiClient] Error sending data to the API: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
