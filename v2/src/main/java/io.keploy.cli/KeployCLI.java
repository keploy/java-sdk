package io.keploy.cli;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

//FOR CLI CODE COVERAGE REFERENCE: https://dzone.com/articles/code-coverage-report-generator-for-java-projects-a

// Jacococli & JacocoAgent version: 0.8.8
public class KeployCLI {

    private static final String GRAPHQL_ENDPOINT = "/query";
    private static final String HOST = "http://localhost:";

    private static final Logger logger = LogManager.getLogger(KeployCLI.class);

    private static int serverPort = 6789;

    private static long userCommandPid = 0;

    private static String jacocoCliPath = "";

    private static String jacocoAgentPath = "";

    public class GraphQLResponse {
        Data data;

        public class Data {
            String[] testSets;
            Boolean stopTest;
            TestSetStatus testSetStatus;
            RunTestSetResponse runTestSet;
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
        FAILED
    }

    public static void StartUserApplication(String runCmd) throws IOException {
        System.out.println("Starting user application:" + runCmd);
        runCmd = attachJacocoAgent(runCmd);

        // Split the runCmd string into command parts
        String[] command = runCmd.split(" ");

        // Start the command using ProcessBuilder
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        // Get the PID of the process
        userCommandPid = getProcessId(process);

        // Start a thread to log the output of the process
        Thread logThread = new Thread(() -> logProcessOutput(process));
        logThread.start();
    }

    private static String attachJacocoAgent(String cmd) {
        String resourcePath = "jacocoagent.jar"; // Relative path in the JAR file

        try (InputStream is = KeployCLI.class.getClassLoader().getResourceAsStream(resourcePath)) {
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
        String dest = "target/" + testSet;
        String runCmd = "java -jar " + getJacococliPath() + " dump --address localhost --port 36320 --destfile "
                + dest + ".exec";

        // Split the runCmd string into command parts
        String[] command = runCmd.split(" ");

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

        try (InputStream is = KeployCLI.class.getClassLoader().getResourceAsStream(resourcePath)) {
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

    public static void StopUserApplication() {
        deleteJacocoFiles();
        killProcessesAndTheirChildren((int) userCommandPid);
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
            logger.debug("File deleted successfully:", filePath);
            // System.out.println("File deleted successfully: " + filePath);
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

    // Fetch the status of testSet
    public static TestRunStatus FetchTestSetStatus(String testRunId) {

        try {
            HttpURLConnection conn = setHttpClient();
            if (conn == null) {
                throw new Exception("Could not initialize HTTP connection.");
            }

            String payload = String.format(
                    "{ \"query\": \"{ testSetStatus(testRunId: \\\"%s\\\") { status } }\" }",
                    testRunId);

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
                if (testStatus.equals("RUNNING")) {
                    return TestRunStatus.RUNNING;
                } else if (testStatus.equals("PASSED")) {
                    return TestRunStatus.PASSED;
                } else if (testStatus.equals("FAILED")) {
                    return TestRunStatus.FAILED;
                }
                return null;
            }

        } catch (Exception e) {
            logger.error("Error fetching test sets", e);
        }
        return null;
    }

    // Run a particular testSet
    public static String RunTestSet(String testSetName) {
        try {
            HttpURLConnection conn = setHttpClient();
            if (conn == null) {
                throw new Exception("Could not initialize HTTP connection.");
            }

            String payload = String.format(
                    "{ \"query\": \"mutation { runTestSet(testSet: \\\"%s\\\") { success testRunId message } }\" }",
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

                return response.data.runTestSet.testRunId;
            }

        } catch (Exception e) {
            logger.error("Error fetching test sets", e);
        }
        return null;

    }

    // Hit GraphQL query to stop the test
    public static Boolean StopTest() {
        try {
            HttpURLConnection conn = setHttpClient();
            if (conn == null) {
                throw new Exception("Could not initialize HTTP connection.");
            }

            String payload = "{ \"query\": \"{ stopTest }\" }";

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

                return response.data.stopTest; // this will return the Boolean value of stopTest
            }

        } catch (Exception e) {
            logger.error("Error stopping the test", e);
        }
        return null;
    }

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

    public static void killProcessOnPort(int port) {
        try {
            Process process = new ProcessBuilder("sh", "-c", "lsof -t -i:" + port).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String pids = reader.readLine();
            if (pids != null) {
                Arrays.stream(pids.split("\n")).forEach(pidStr -> {
                    if (!pidStr.isEmpty()) {
                        int pid = Integer.parseInt(pidStr.trim());
                        killProcessesAndTheirChildren(pid);
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Failed to fetch the process ID on port " + port);
        }
    }

    public static void killProcessesAndTheirChildren(int parentPID) {
        List<Integer> pids = new ArrayList<>();
        findAndCollectChildProcesses(String.valueOf(parentPID), pids);
        for (int childPID : pids) {
            if (childPID != getCurrentPid()) {
                try {
                    new ProcessBuilder("kill", "-15", String.valueOf(childPID)).start();
                    logger.debug("Killed child process " + childPID);
                } catch (Exception e) {
                    e.printStackTrace();
                    System.err.println("Failed to kill child process " + childPID);
                }
            }
        }
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

    private static int getCurrentPid() {
        String processName = ManagementFactory.getRuntimeMXBean().getName();
        return Integer.parseInt(processName.split("@")[0]);
    }

    public static void runTestsAndCoverage(String jarPath, String[] testSets) {
        for (String testSet : testSets) {
            String testRunId = KeployCLI.RunTestSet(testSet);
            startUserApplication(jarPath);
            waitForTestRunCompletion(testRunId);

            try {
                KeployCLI.FindCoverage(testSet);
                Thread.sleep(5000);
            } catch (Exception e) {
                // TODO: handle exception
                e.printStackTrace();
            }
            stopUserApplication();
            // unload the ebpf hooks from the kernel
            StopTest();
        }
    }

    private static void startUserApplication(String jarPath) {
        String[] command = { "java", "-jar", jarPath };
        String userCmd = String.join(" ", command);
        try {
            KeployCLI.StartUserApplication(userCmd);
            System.out.println("Application started ");
        } catch (IOException e) {
            System.err.println("Failed to start user application: " + e.getMessage());
        }
    }

    private static void waitForTestRunCompletion(String testRunId) {
        // Implement the logic to wait for test run completion using KeployCLI
        long MAX_TIMEOUT = 6000000; // 1m
        long startTime = System.currentTimeMillis();

        try {
            KeployCLI.TestRunStatus testRunStatus;

            while (true) {
                Thread.sleep(2000);
                testRunStatus = KeployCLI.FetchTestSetStatus(testRunId);

                if (testRunStatus == KeployCLI.TestRunStatus.RUNNING) {
                    System.out.println("Test run still in progress");

                    if (System.currentTimeMillis() - startTime > MAX_TIMEOUT) {
                        System.out.println("Timeout reached, exiting loop");
                        break;
                    }

                    continue;
                }

                break;
            }

            if (testRunStatus == KeployCLI.TestRunStatus.FAILED
                    || testRunStatus == KeployCLI.TestRunStatus.RUNNING) {
                System.out.println("Test run failed");
            } else if (testRunStatus == KeployCLI.TestRunStatus.PASSED) {
                System.out.println("Test run passed");
            }
        } catch (InterruptedException e) {
            System.err.println("Error waiting for test run completion: " + e.getMessage());
        }
    }

    private static void stopUserApplication() {
        KeployCLI.StopUserApplication();
    }
}
