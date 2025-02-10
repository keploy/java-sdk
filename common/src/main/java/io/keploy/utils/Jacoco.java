package io.keploy.utils;

import com.google.gson.Gson;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;
import org.jacoco.report.DirectorySourceFileLocator;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.xml.XMLFormatter;

import java.net.HttpURLConnection;
import java.net.Socket;

import org.jacoco.core.analysis.*;
import java.io.*;
import java.net.URL;

import java.util.*;
import static io.keploy.utils.Helper.ExtractEnv;

public class Jacoco {

    static String apiKey;
    static String Mode;
    static String serverURL;
    static String appName;
    static String Path;
    // <uuid,<filename,"comma separated Line path">>
    static Map<String, List<Map<String, String>>> coverageStore = new HashMap<>();

    public Jacoco() {
        apiKey = ExtractEnv("API_KEY");
        serverURL = ExtractEnv("SERVER_URL");
        appName = ExtractEnv("APP_NAME");
        if (Objects.equals(appName, "") || appName == null) {
            appName = "defaultApp-" + generateRandomUUID();
        }
        if (Objects.equals(serverURL, "") || serverURL == null) {
            serverURL = "https://api.keploy.io";
        }
        Path = ExtractEnv("APP_PATH");
        if (Objects.equals(Path, "") || Path == null) {
            Path = System.getProperty("user.dir");
        }
    }

    static class CoverageResponse {
        Map<String, List<Map<String, String>>> coverageStore = new HashMap<>();
        String SessionID;
    }

    public static void GetLOC(CoverageBuilder coverageBuilder, boolean isLast, String uuid, String sessionID)
            throws IOException {
        System.out.println("------------------------------------------");

        List<Map<String, String>> coverageDataList = new ArrayList<>();

        for (final IClassCoverage cc : coverageBuilder.getClasses()) {
            String className = cc.getName();
            StringBuilder linesCovered = new StringBuilder();

            for (int i = cc.getFirstLine(); i <= cc.getLastLine(); i++) {
                if (getColor(cc.getLine(i).getStatus()).equals("green")) {
                    linesCovered.append(i).append(",");
                }
            }

            if (linesCovered.length() > 0) {
                // Remove trailing comma
                linesCovered.setLength(linesCovered.length() - 1);

                // Create a map for this coverage entry
                Map<String, String> coverageEntry = new HashMap<>();
                coverageEntry.put("fileName", className);
                coverageEntry.put("linesCovered", linesCovered.toString());
                // Add the entry to the list
                coverageDataList.add(coverageEntry);
            }
        }

        // Add the list to the coverageStore map
        coverageStore.put(uuid, coverageDataList);
        System.out.println("CoverageDataList:: " + coverageDataList);
        if (isLast) {
            callCoverageStoreAPI(coverageStore, sessionID);
        }
    }

    private static class CoverageStoreReq {
        Map<String, List<Map<String, String>>> CoverageStore;
        String SessionID;
    }

    public static void callCoverageStoreAPI(Map<String, List<Map<String, String>>> coverageStore, String sessionID) {
        try {
            System.out.println("Sending coverage data to API server...");
            // TODO: change it to api.keploy.io once released
            String endpoint = "http://localhost:8083" + "/coverage/store";
            URL url = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            // Set up the connection properties
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + ApiClient.jwtToken);
            conn.setDoOutput(true);

            // Create request payload in the correct structure
            CoverageStoreReq requestPayload = new CoverageStoreReq();
            requestPayload.CoverageStore = coverageStore;
            requestPayload.SessionID = sessionID;

            System.out.println("After sending the request ...");
            // Convert to JSON and send in request body
            String jsonPayload = new Gson().toJson(requestPayload);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonPayload.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            // Read the response
            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                System.out.println("Coverage data successfully sent to /coverage/store");

                // Read and print the response body
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    System.out.println("Response: " + response.toString());
                }

            } else {
                System.out.println("Failed to send coverage data. HTTP response code: " + responseCode);

                // Read and print the error message from response body
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), "utf-8"))) {
                    StringBuilder errorResponse = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        errorResponse.append(responseLine.trim());
                    }
                    System.out.println("Error response: " + errorResponse.toString());
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Error occurred while calling the API: " + e.getMessage());
        }
    }

    private static String getColor(final int status) {
        switch (status) {
            case ICounter.NOT_COVERED:
                return "red";
            case ICounter.PARTLY_COVERED:
                return "yellow";
            case ICounter.FULLY_COVERED:
                return "green";
        }
        return "";
    }

    public static String generateRandomUUID() {
        // Generate a random UUID
        UUID uuid = UUID.randomUUID();
        // Convert UUID to string and return
        return uuid.toString();
    }

    public static void generateCoverageReport(boolean isLast, String uuid, String sessionId) {
        try {
            ByteArrayOutputStream memoryStream = new ByteArrayOutputStream();
            ExecutionDataWriter localWriter = new ExecutionDataWriter(memoryStream);
            Socket socket = new Socket("localhost", 36320);
            RemoteControlWriter writer = new RemoteControlWriter(socket.getOutputStream());
            RemoteControlReader reader = new RemoteControlReader(socket.getInputStream());
            reader.setSessionInfoVisitor(localWriter);
            reader.setExecutionDataVisitor(localWriter);
            writer.visitDumpCommand(true, true);
            if (!reader.read())
                throw new IOException("Socket closed unexpectedly.");
            socket.close();
            memoryStream.close();

            byte[] executionData = memoryStream.toByteArray();
            ByteArrayInputStream inputStream = new ByteArrayInputStream(executionData);
            ExecutionDataReader execReader = new ExecutionDataReader(inputStream);
            ExecutionDataStore executionDataStore = new ExecutionDataStore();
            SessionInfoStore sessionInfoStore = new SessionInfoStore();
            execReader.setExecutionDataVisitor(executionDataStore);
            execReader.setSessionInfoVisitor(sessionInfoStore);
            while (execReader.read()) {
            }

            // Analyze coverage
            File binDir = new File(Path + "/target/classes");
            CoverageBuilder coverageBuilder = new CoverageBuilder();
            Analyzer analyzer = new Analyzer(executionDataStore, coverageBuilder);
            analyzer.analyzeAll(binDir);

            if (Objects.equals(Mode, "TESTGEN")) {
                GetLOC(coverageBuilder, isLast, uuid, sessionId);
                return;
            }

            // Generate XML coverage report
            File reportFile = new File(
                    Path + "/coverage.xml");
            ByteArrayOutputStream bufferStream = new ByteArrayOutputStream();
            FileOutputStream outputStream = new FileOutputStream(reportFile);
            try {
                XMLFormatter xmlFormatter = new XMLFormatter();
                IReportVisitor visitor = xmlFormatter.createVisitor(bufferStream);
                visitor.visitInfo(sessionInfoStore.getInfos(), executionDataStore.getContents());
                IBundleCoverage bundle = coverageBuilder.getBundle("GeneratedCoverageReport");
                visitor.visitBundle(bundle,
                        new DirectorySourceFileLocator(new File(System.getProperty("user.dir") + "/src"), "utf-8", 4));
                visitor.visitEnd();
                String reportContent = bufferStream.toString("UTF-8");

                outputStream.write(bufferStream.toByteArray());
            } finally {
                outputStream.close();
                bufferStream.close();
            }

            System.out.println("JaCoCo XML coverage report generated at: " + reportFile.getAbsolutePath());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
