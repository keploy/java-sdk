package io.keploy.cli;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class KeployCLI {

    private static final String GRAPHQL_ENDPOINT = "/query";
    private static final String HOST = "http://localhost:";

    private static final Logger logger = LogManager.getLogger(KeployCLI.class);

    private static int serverPort = 6789;

    private static long userCommandPid = 0;

    public class GraphQLResponse {
        Data data;

        public class Data {
            String[] testSets;
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

    public static void StopUserApplication() {
        killProcessesAndTheirChildren((int) userCommandPid);
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
                    new ProcessBuilder("sudo", "kill", "-15", String.valueOf(childPID)).start();
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
}
