package io.keploy;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

//FOR CLI CODE COVERAGE REFERENCE: https://dzone.com/articles/code-coverage-report-generator-for-java-projects-a

// Jacococli & JacocoAgent version: 0.8.12
public class Keploy {
    public static class RunOptions {
        private int delay;
        private boolean debug;
        private int port;
        private String path;
        private String appCmd;

        public RunOptions() {
            this(10, false, 6789, ".");
        }

        public RunOptions(int delay, boolean debug, int port, String path) {
            if (delay < 0) {
                throw new IllegalArgumentException("Delay must be a positive integer.");
            }
            this.delay = delay;

            this.debug = debug;

            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("Path cannot be empty.");
            }
            this.path = path;

            if (port < 0) {
                throw new IllegalArgumentException("Port must be a positive integer.");
            }
            this.port = port;
        }

        // Getters and setters
        public int getDelay() {
            return delay;
        }

        public void setDelay(int delay) {
            if (delay < 0) {
                throw new IllegalArgumentException("Delay must be a positive integer.");
            }
            this.delay = delay;
        }

        public boolean isDebug() {
            return debug;
        }

        public void setDebug(boolean debug) {
            this.debug = debug;
        }

        public void setappCmd(String appCmd) {
            this.appCmd = appCmd;
        }

        public String getappCmd() {
            return this.appCmd;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            if (port < 0) {
                throw new IllegalArgumentException("Port must be a positive integer.");
            }
            this.port = port;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("Path cannot be empty.");
            }
            this.path = path;
        }
    }

    private static class StartHooksData {
        String appId;
        String testRunId;
    }

    // Class to hold the result of startHooks
    public static class StartHooksResult {
        String appId;
        String testRunId;
        String error;

        public StartHooksResult(String appId, String testRunId, String error) {
            this.appId = appId;
            this.testRunId = testRunId;
            this.error = error;
        }
    }

    private static final String GRAPHQL_ENDPOINT = "/query";
    private static final String HOST = "http://localhost:";

    private static final Logger logger = LogManager.getLogger(Keploy.class);

    private static int serverPort = 6789;

    private static String jacocoCliPath = "";

    private static String jacocoAgentPath = "";

    public class GraphQLResponse {
        Data data;

        Map<String, Object>[] errors;

        public class Data {
            String[] testSets;
            Boolean stopTest;
            TestSetStatus testSetStatus;
            Boolean runTestSet;
            StartHooksData startHooks;
            Boolean startApp;
            Boolean stopHooks;
            Boolean stopApp;
        }

        public class TestSetStatus {
            String status;
        }

        public class RunTestSetResponse {
            Boolean success;
            String testRunId;
            String message;
        }
    }

    public enum TestRunStatus {
        RUNNING,
        PASSED,
        FAILED,
        APP_HALTED,
        USER_ABORT,
        APP_FAULT,
        INTERNAL_ERR
    }

    public static TestRunStatus getTestRunStatus(String statusStr) {
        switch (statusStr) {
            case "RUNNING":
                return TestRunStatus.RUNNING;
            case "PASSED":
                return TestRunStatus.PASSED;
            case "FAILED":
                return TestRunStatus.FAILED;
            case "APP_HALTED":
                return TestRunStatus.APP_HALTED;
            case "USER_ABORT":
                return TestRunStatus.USER_ABORT;
            case "APP_FAULT":
                return TestRunStatus.APP_FAULT;
            case "INTERNAL_ERR":
                return TestRunStatus.INTERNAL_ERR;
            default:
                return null; // or throw an exception if an unknown status is encountered
        }
    }

    private static String attachJacocoAgent(String cmd) {
        String resourcePath = "jacocoagent.jar"; // Relative path in the JAR file

        try (InputStream is = Keploy.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("jacocoagent.jar not found in resources");
            }

            Path tempFile = Files.createTempFile("jacocoagent", ".jar");

            // Using Files.copy for robust file copying
            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            is.close();
            String agentString = "-javaagent:" + tempFile.toAbsolutePath()
                    + "=address=localhost,port=36320,destfile=coverage.exec,output=tcpserver";

            jacocoAgentPath = tempFile.toAbsolutePath().toString();

            return cmd.replaceFirst("java", "java " + agentString);
        } catch (IOException e) {
            e.printStackTrace();
            throw new IllegalStateException("Error setting up JaCoCo agent", e);
        }
    }

    public static void FindCoverage(String testSet) throws IOException, InterruptedException {
        String dest = "target/" + testSet + ".exec";
        String jacocoCliPath = getJacococliPath();
        List<String> command = Arrays.asList(
                "java",
                "-jar",
                jacocoCliPath,
                "dump",
                "--address",
                "localhost",
                "--port",
                "36320",
                "--destfile",
                dest);

        // Start the command using ProcessBuilder
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        // Start a thread to log the output of the process
        Thread logThread = new Thread(() -> logProcessOutput(process));
        logThread.start();
    }

    private static String getJacococliPath() {
        String resourcePath = "jacococli.jar"; // Relative path in the JAR file

        try (InputStream is = Keploy.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("jacococli.jar not found in resources");
            }

            Path tempFile = Files.createTempFile("jacococli", ".jar");

            // Using Files.copy for robust file copying
            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            is.close();
            jacocoCliPath = tempFile.toAbsolutePath().toString();
            return jacocoCliPath;
        } catch (IOException e) {
            e.printStackTrace();
            throw new IllegalStateException("Error setting up JacocoCli", e);
        }
    }

    public static String StopUserApplication(String appId) {
        HttpURLConnection conn = setHttpClient();
        if (conn == null) {
            return "Failed to set up HTTP client";
        }

        String payload = "{\"query\": \"mutation StopApp { stopApp(appId: " + appId + ") }\"}";

        try {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes());
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            logger.debug("Status code received: " + responseCode);

            if (responseCode >= 200 && responseCode < 300) {
                String resBody = getResponseBody(conn);
                logger.debug("Response body received: " + resBody);

                // Parse the response body using Gson
                Gson gson = new Gson();
                GraphQLResponse response = gson.fromJson(resBody, GraphQLResponse.class);

                if (response.data == null) {
                    return gson.toJson(response.errors);
                }

                Boolean stopAppResult = response.data.stopApp;
                logger.debug("stopApp result: " + stopAppResult);
            } else {
                return "Failed to stop user application. Status code: " + responseCode;
            }
        } catch (Exception e) {
            logger.error("Error stopping user application: " + e.getMessage(), e);
            return "Error stopping user application: " + e.getMessage();
        }

        return null;
    }

    private static void deleteJacocoFiles() {
        deleteFile(jacocoAgentPath);
        deleteFile(jacocoCliPath);
    }

    private static boolean deleteFile(String filePath) {
        File file = new File(filePath);

        // Check if the file exists
        if (!file.exists()) {
            System.out.println("File not found: " + filePath);
            return false;
        }

        // Attempt to delete the file
        if (file.delete()) {
            return true;
        } else {
            System.out.println("Failed to delete the file: " + filePath);
            return false;
        }
    }

    private static long getProcessId(Process process) {
        // Java 9 and later
        if (process.getClass().getName().equals("java.lang.ProcessImpl")) {
            return process.pid();
        }

        // Java 8 and earlier
        try {
            java.lang.reflect.Field f = process.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getLong(process);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Unable to get process ID", e);
        }
    }

    private static void logProcessOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Set the HTTP client
    private static HttpURLConnection setHttpClient() {
        try {
            URL obj = new URL(HOST + serverPort + GRAPHQL_ENDPOINT);
            logger.debug("Connecting to: " + obj.toString());
            HttpURLConnection conn = (HttpURLConnection) obj.openConnection();
            conn.setReadTimeout(15000);
            conn.setConnectTimeout(10000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setInstanceFollowRedirects(false);

            return conn;
        } catch (Exception e) {
            logger.error("Error setting up HttpURLConnection", e);
            return null;
        }
    }

    // Hit GraphQL query to fetch testSets
    public static String[] FetchTestSets() {
        try {
            HttpURLConnection conn = setHttpClient();
            if (conn == null) {
                throw new Exception("Could not initialize HTTP connection.");
            }

            String payload = "{ \"query\": \"{ testSets }\" }";

            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes());
                os.flush();
            }

            final int responseCode = conn.getResponseCode();
            logger.debug("status code received: {}", responseCode);

            if (isSuccessfulResponse(conn)) {
                String resBody = getSimulateResponseBody(conn);
                logger.debug("response body received: {}", resBody);
                // Parse the response body using Gson
                Gson gson = new Gson();
                GraphQLResponse response = gson.fromJson(resBody, GraphQLResponse.class);

                return response.data.testSets; // this will return the testSets array
            }

        } catch (Exception e) {
            logger.error("Error fetching test sets", e);
        }
        return null;
    }

    // Run a particular testSet
    public static Boolean RunTestSet(String testSetName) {
        try {
            HttpURLConnection conn = setHttpClient();
            if (conn == null) {
                throw new Exception("Could not initialize HTTP connection.");
            }

            String payload = String.format(
                    "{ \"query\": \"mutation { runTestSet(testSet: \\\"%s\\\") }\" }",
                    testSetName);

            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes());
                os.flush();
            }

            final int responseCode = conn.getResponseCode();
            logger.debug("status code received: {}", responseCode);

            if (isSuccessfulResponse(conn)) {
                String resBody = getSimulateResponseBody(conn);
                logger.debug("response body received: {}", resBody);
                // Parse the response body using Gson
                Gson gson = new Gson();
                GraphQLResponse response = gson.fromJson(resBody, GraphQLResponse.class);

                return response.data.runTestSet; // Return the boolean value
            }

        } catch (Exception e) {
            logger.error("Error running test set", e);
        }
        return null; // Return null in case of an error
    }

    // Hit GraphQL query to stop the test

    private static boolean isSuccessfulResponse(HttpURLConnection conn) throws IOException {
        int responseCode = conn.getResponseCode();
        return (responseCode >= 200 && responseCode < 300);
    }

    private static String getSimulateResponseBody(HttpURLConnection conn) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String inputLine;
        StringBuilder content = new StringBuilder();
        while ((inputLine = in.readLine()) != null) {
            content.append(inputLine);
        }
        in.close();
        return content.toString();
    }

    public static void findAndCollectChildProcesses(String parentPID, List<Integer> pids) {
        try {
            pids.add(Integer.parseInt(parentPID));
            Process process = new ProcessBuilder("pgrep", "-P", parentPID).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String output = reader.readLine();
            if (output != null) {
                Arrays.stream(output.split("\n")).forEach(childPID -> {
                    if (!childPID.isEmpty()) {
                        findAndCollectChildProcesses(childPID, pids);
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static class RunTestSetResult {
        boolean success;
        String error;

        public RunTestSetResult(boolean success, String error) {
            this.success = success;
            this.error = error;
        }
    }

    public static void runTests(String jarPath, RunOptions runOptions) {
        String runCmd = "java -jar " + jarPath;
        if (runOptions.getPort() != 0) {
            serverPort = runOptions.getPort();
        }
        try {
            runCmd = attachJacocoAgent(runCmd);
            startKeploy(runCmd, runOptions.getDelay(), runOptions.isDebug(), serverPort);
            Thread.sleep(5000);
            String[] testSets = Keploy.FetchTestSets();

            if (testSets == null) {
                throw new IllegalStateException("Test sets are null");
            }

            System.out.println("TestSets: " + Arrays.asList(testSets));
            Keploy.StartHooksResult result = Keploy.startHooks();
            if (result.error != null) {
                throw new AssertionError("error starting hooks: " + result.error);
            }
            String appId = result.appId;
            String testRunId = result.testRunId;

            for (String testSet : testSets) {
                runTestSet(testRunId, testSet, appId);
                startUserApplication(appId);
                String path = new File(runOptions.getPath()).getAbsolutePath();
                Path reportPath = Paths.get(path, "keploy", "reports", testRunId, testSet + "-report.yaml").normalize();

                String reportPathString = reportPath.toString();
                String err = checkReportFile(reportPathString, runOptions.getDelay() + 10);
                if (err != null) {
                    String appErr = stopUserApplication(appId);
                    if (appErr != null) {
                        throw new AssertionError("error stopping user application: " + appErr);
                    }
                    logger.error(
                            "error getting report file: " + testRunId + ", testSetId: " + testSet + ". Error: " + err);
                    continue;
                }

                waitForTestRunCompletion(testRunId, testSet, appId);

                try {
                    Keploy.FindCoverage(testSet);

                    Thread.sleep(5000);

                } catch (Exception e) {
                    e.printStackTrace();
                }

                String appErr = stopUserApplication(appId);

                if (appErr != null) {
                    throw new AssertionError("error stopping user application: " + appErr);
                }

                Boolean updateReportResult = udpateReportWithCoverage(testRunId, testSet);
                if (!updateReportResult) {
                    throw new AssertionError("error updating report with coverage data");
                }
            }
            // unload the ebpf hooks from the kernel
            // delete jacoco files
        } catch (Exception e) {
            logger.error("Error occurred while fetching test sets: " + e.getMessage(), e);
        }
        stopKeploy();
        deleteJacocoFiles();
    }

    public static void startKeploy(String runCmd, int delay, boolean debug, int port) {
        Runnable task = () -> runKeploy(runCmd, delay, debug, port);
        Thread thread = new Thread(task);
        thread.start();
        return;
    }

    public static void runKeploy(String runCmd, int delay, boolean debug, int port) {
        final String overallCmd = String.format(
                "sudo -E env \"PATH=$PATH\" /usr/local/bin/keploybin test -c \"%s\" --coverage --delay %d --port %d%s",
                runCmd, delay, port, debug ? " --debug" : "");

        logger.debug("Executing command: " + overallCmd);

        new Thread(() -> {
            ProcessBuilder processBuilder = new ProcessBuilder("sh", "-c", overallCmd);
            processBuilder.redirectErrorStream(true); // Redirect error stream to standard output stream
            Process process;

            try {
                process = processBuilder.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }

            } catch (IOException e) {
                logger.error("Error occurred while running Keploy: " + e.getMessage());
            }
        }).start();
    }

    public static StartHooksResult startHooks() {
        HttpURLConnection conn = setHttpClient();
        if (conn == null) {
            return new StartHooksResult(null, null, "Failed to set up HTTP client");
        }

        String payload = "{\"query\": \"mutation StartHooks { startHooks { appId testRunId } }\"}";

        try {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes());
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            logger.debug("Status code received: " + responseCode);

            if (responseCode >= 200 && responseCode < 300) {
                String resBody = getResponseBody(conn);
                logger.debug("Response body received: " + resBody);

                // Parse the response body using Gson
                Gson gson = new Gson();
                GraphQLResponse response = gson.fromJson(resBody, GraphQLResponse.class);

                if (response.data == null) {
                    return new StartHooksResult(null, null, gson.toJson(response.errors));
                }

                StartHooksData startHooksData = response.data.startHooks;
                if (startHooksData == null) {
                    return new StartHooksResult(null, null, "Failed to get start Hooks data");
                }

                return new StartHooksResult(startHooksData.appId, startHooksData.testRunId, null);
            } else {
                return new StartHooksResult(null, null, "Failed to start hooks. Status code: " + responseCode);
            }
        } catch (Exception e) {
            logger.error("Error starting hooks: " + e.getMessage(), e);
            return new StartHooksResult(null, null, "Error starting hooks: " + e.getMessage());
        }
    }

    public static class StartAppResult {
        boolean success;
        String error;

        public StartAppResult(boolean success, String error) {
            this.success = success;
            this.error = error;
        }
    }

    public static StartAppResult startUserApplication(String appId) {
        HttpURLConnection conn = setHttpClient();
        if (conn == null) {
            return new StartAppResult(false, "Failed to set up HTTP client");
        }

        String payload = "{\"query\": \"mutation StartApp { startApp(appId: " + appId + ") }\"}";

        try {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes());
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            logger.debug("Status code received: " + responseCode);

            if (responseCode >= 200 && responseCode < 300) {
                String resBody = getResponseBody(conn);
                logger.debug("Response body received: " + resBody);

                // Parse the response body using Gson
                Gson gson = new Gson();
                GraphQLResponse response = gson.fromJson(resBody, GraphQLResponse.class);

                if (response.data == null) {
                    return new StartAppResult(false, gson.toJson(response.errors));
                }

                Boolean startAppResult = response.data.startApp;
                return new StartAppResult(startAppResult, null);
            } else {
                return new StartAppResult(false, "Failed to start user application. Status code: " + responseCode);
            }
        } catch (Exception e) {
            logger.error("Error starting user application: " + e.getMessage(), e);
            return new StartAppResult(false, "Error starting user application: " + e.getMessage());
        }
    }

    public static TestRunStatus FetchTestSetStatus(String testRunId, String testSetId) {
        try {
            HttpURLConnection conn = setHttpClient();
            if (conn == null) {
                throw new Exception("Could not initialize HTTP connection.");
            }

            String payload = String.format(
                    "{ \"query\": \"query GetTestSetStatus { testSetStatus(testRunId: \\\"%s\\\", testSetId: \\\"%s\\\") { status } }\" }",
                    testRunId, testSetId);

            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes());
                os.flush();
            }

            final int responseCode = conn.getResponseCode();
            logger.debug("status code received: {}", responseCode);

            if (isSuccessfulResponse(conn)) {
                String resBody = getSimulateResponseBody(conn);
                logger.debug("response body received: {}", resBody);
                // Parse the response body using Gson
                Gson gson = new Gson();
                GraphQLResponse response = gson.fromJson(resBody, GraphQLResponse.class);

                String testStatus = response.data.testSetStatus.status;
                return getTestRunStatus(testStatus);
            } else {
                logger.error("Failed to fetch test set status. Status code: " + responseCode);
                return null;
            }
        } catch (Exception e) {
            logger.error("Error fetching test set status: " + e.getMessage(), e);
            return null;
        }
    }

    private static void waitForTestRunCompletion(String testRunId, String testSet, String appId) {
        // Implement the logic to wait for test run completion using KeployCLI
        long MAX_TIMEOUT = 6000000; // 1m
        long startTime = System.currentTimeMillis();

        try {
            TestRunStatus testRunStatus = null;
            while (true) {
                Thread.sleep(2000);
                testRunStatus = Keploy.FetchTestSetStatus(testRunId, testSet);

                if (testRunStatus == Keploy.TestRunStatus.RUNNING) {
                    if (System.currentTimeMillis() - startTime > MAX_TIMEOUT) {
                        System.out.println("Timeout reached, exiting loop");
                        break;
                    }

                    continue;
                }
                break;
            }
            if (testRunStatus == TestRunStatus.FAILED || testRunStatus == TestRunStatus.APP_HALTED
                    || testRunStatus == TestRunStatus.USER_ABORT || testRunStatus == TestRunStatus.APP_FAULT
                    || testRunStatus == TestRunStatus.INTERNAL_ERR) {
                logger.error("Test set: " + testSet + " failed with status: " + testRunStatus);
            } else if (testRunStatus == TestRunStatus.PASSED) {
                logger.info("Test set: " + testSet + " passed");
            }
        } catch (InterruptedException e) {
            System.err.println("Error waiting for test run completion: " + e.getMessage());
        }
    }

    public static RunTestSetResult runTestSet(String testRunId, String testSetId, String appId) {
        HttpURLConnection conn = setHttpClient();
        if (conn == null) {
            return new RunTestSetResult(false, "Failed to set up HTTP client");
        }

        String payload = String.format(
                "{\"query\": \"mutation RunTestSet { runTestSet(testSetId: \\\"%s\\\", testRunId: \\\"%s\\\", appId: %s) }\"}",
                testSetId, testRunId, appId);

        try {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes());
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            logger.debug("Status code received: " + responseCode);

            if (responseCode >= 200 && responseCode < 300) {
                String resBody = getResponseBody(conn);
                logger.debug("Response body received: " + resBody);

                // Parse the response body using Gson
                Gson gson = new Gson();
                GraphQLResponse response = gson.fromJson(resBody, GraphQLResponse.class);

                if (response.data == null) {
                    return new RunTestSetResult(false, gson.toJson(response.errors));
                }
                return new RunTestSetResult(true, null);
            } else {
                return new RunTestSetResult(false, "Failed to run test set. Status code: " + responseCode);
            }
        } catch (Exception e) {
            logger.error("Error running test set: " + e.getMessage(), e);
            return new RunTestSetResult(false, "Error running test set: " + e.getMessage());
        }
    }

    public static Boolean udpateReportWithCoverage(String testRunId, String testSetId) {
        HttpURLConnection conn = setHttpClient();
        if (conn == null) {
            logger.error("Could not initialize HTTP connection.");
            return false;
        }

        String payload = "{\"query\": \"mutation UpdateReportWithCov { UpdateReportWithCov(testRunId: \\\"%s\\\", testSetId: \\\"%s\\\", language: \"java\") }\"}";

        try {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes());
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            logger.debug("Status code received: " + responseCode);

            if (responseCode >= 200 && responseCode < 300) {
                String resBody = getResponseBody(conn);
                logger.debug("Response body received: " + resBody);

                // Parse the response body using Gson
                Gson gson = new Gson();
                GraphQLResponse response = gson.fromJson(resBody, GraphQLResponse.class);

                if (response.data == null) {
                    return false;
                }

                return true;
            } else {
                logger.error("Failed to update report with coverage data. Status code: " + responseCode);
                return false;
            }
        } catch (Exception e) {
            logger.error("Error updating report with coverage data: " + e.getMessage(), e);
            return false;
        }
    }

    public static class StopHooksResult {
        Boolean success;
        String error;

        public StopHooksResult(Boolean success, String error) {
            this.success = success;
            this.error = error;
        }
    }

    private static String checkReportFile(String reportPath, int timeout) {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeout * 1000L) {
            File reportFile = new File(reportPath);
            if (reportFile.exists()) {
                return null;
            }
            try {
                Thread.sleep(1000); // Wait for 1 second before checking again
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Interrupted while waiting for report file";
            }
        }

        return "Report file not created within " + timeout + " seconds";
    }

    public static StopHooksResult stopKeploy() {
        HttpURLConnection conn = setHttpClient();
        if (conn == null) {
            return new StopHooksResult(null, "Failed to set up HTTP client");
        }

        String payload = "{\"query\": \"mutation { stopHooks }\"}";

        try {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes());
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            logger.debug("Status code received: " + responseCode);

            if (responseCode >= 200 && responseCode < 300) {
                String resBody = getResponseBody(conn);
                logger.debug("Response body received: " + resBody);

                // Parse the response body using Gson
                Gson gson = new Gson();
                GraphQLResponse response = gson.fromJson(resBody, GraphQLResponse.class);

                if (response.data == null) {
                    return new StopHooksResult(null, gson.toJson(response.errors));
                }

                Boolean stopHooksResult = response.data.stopHooks;
                return new StopHooksResult(stopHooksResult, null);
            } else {
                return new StopHooksResult(null, "Failed to stop hooks. Status code: " + responseCode);
            }
        } catch (Exception e) {
            logger.error("Error stopping hooks: " + e.getMessage(), e);
            return new StopHooksResult(null, "Error stopping hooks: " + e.getMessage());
        }
    }

    private static String getResponseBody(HttpURLConnection conn) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String inputLine;
        StringBuilder content = new StringBuilder();
        while ((inputLine = in.readLine()) != null) {
            content.append(inputLine);
        }
        in.close();
        return content.toString();
    }

    public static String stopUserApplication(String appId) {
        String result = Keploy.StopUserApplication(appId);
        return result;
    }
}
