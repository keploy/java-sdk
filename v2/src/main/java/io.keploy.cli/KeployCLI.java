package io.keploy.cli;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;


public class KeployCLI {

    private static final String GRAPHQL_ENDPOINT = "/query";
    private static final String HOST = "http://localhost:";

    private static final Logger logger = LogManager.getLogger(KeployCLI.class);

    private static int serverPort = 6789;

    private static Process kprocess;

    private static Thread kLogThread;

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

//    public static void main(String[] args) throws IOException, InterruptedException {
//   }


    // Run Keploy server
    public static void RunKeployServer(long pid, int delay, String testPath, int port) throws InterruptedException, IOException {
        // Registering a shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutdown hook executed!");
            kprocess.destroy();
            try {
                Thread.sleep(1000);
                kLogThread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }));


//        String password = "keploy@123";  // Ensure this isn't hardcoded in production code!
//        String commandString = "echo '" + password + "' | sudo -S /usr/local/bin/keploy serve --pid=" + pid + " -p=" + testPath + " -d=" + delay + " --port=" + port;
//        String[] command = {
//                commandString
//        };

        // Construct the keploy command
        String[] command = {
                "sudo",
                "-S",
                "/usr/local/bin/keploy",
                "serve",
                "--pid=" + pid,
                "-p=" + testPath,
                "-d=" + delay,
                "--port=" + port,
                "--language=java"
        };


        if (port != 0) {
            serverPort = port;
        }

        // Start the keploy command
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        kprocess = processBuilder.start();

//        // When running without root user
//        String password = "keploy@123";
//        try (OutputStream os = kprocess.getOutputStream()) {
//            os.write((password + "\n").getBytes());
//            os.flush();
//        }

        // Read the output in real-time

        Thread logThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(kprocess.getInputStream()))) {
                String line;
                while (kprocess.isAlive() && (line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            } catch (IOException e) {
                // Since the stream might get closed due to process termination,
                // we can handle this specific error more gracefully
                if (!"Stream closed".equals(e.getMessage())) {
                    e.printStackTrace();
                }
            }
        });


        logThread.start();

        kLogThread = logThread;
        // Wait for the command to finish and get its exit code
//        int exitCode = process.waitFor();
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
                    testRunId
            );

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


    //     Run a particular testSet
    public static String RunTestSet(String testSetName) {
        try {
            HttpURLConnection conn = setHttpClient();
            if (conn == null) {
                throw new Exception("Could not initialize HTTP connection.");
            }

            String payload = String.format(
                    "{ \"query\": \"mutation { runTestSet(testSet: \\\"%s\\\") { success testRunId message } }\" }",
                    testSetName
            );

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



    public static void StopKeployServer() {
//        kprocess.destroy();
        killProcessOnPort(serverPort);
    }

    public static void killProcessOnPort(int port) {
        logger.debug("trying to kill process running on port:{}",port);
        String command = "lsof -t -i:" + port;

        try {
            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                forceKillProcessByPID(line.trim());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void forceKillProcessByPID(String pid) {
        try {
            String cmd = "kill -9 "+pid;
            logger.debug("cmd to kill:{}",cmd);
            Runtime.getRuntime().exec("kill -9 " + pid);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
