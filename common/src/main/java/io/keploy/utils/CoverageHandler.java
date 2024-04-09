package io.keploy.utils;

import java.io.File;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;
import org.yaml.snakeyaml.DumperOptions;
import java.net.Socket;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.reader.UnicodeReader;



public class CoverageHandler {
    private static final String ADDRESS = "localhost";
    public static String Line_Path = "";


    private static final int PORT = 36320;

        public static void getCoverage(String keploy_test_id) throws IOException, InterruptedException {

        try {
            execWriter(keploy_test_id);
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            execReader(keploy_test_id);
        } catch (IOException e) {
            e.printStackTrace(); // Example: print the stack trace
        }

    }

public static void execWriter(String keploy_test_id) throws IOException {
    File directory = new File(
            System.getProperty("user.dir") + "/target");
    File file = new File(directory, "jacoco-client" + keploy_test_id + ".exec");
//        File file = new File(directory, "jacoco-client.exec");

    final FileOutputStream localFile = new FileOutputStream(file);

    final ExecutionDataWriter localWriter = new ExecutionDataWriter(
            localFile);

    // Open a socket to the coverage agent:
    final Socket socket = new Socket(InetAddress.getByName(ADDRESS), PORT);
    final RemoteControlWriter writer = new RemoteControlWriter(
            socket.getOutputStream());
    final RemoteControlReader reader = new RemoteControlReader(
            socket.getInputStream());
    reader.setSessionInfoVisitor(localWriter);
    reader.setExecutionDataVisitor(localWriter);

    // Send a dump command and read the response:
    writer.visitDumpCommand(true, true);
    if (!reader.read()) {
        throw new IOException("Socket closed unexpectedly.");
    }

    socket.close();
    localFile.close();
}
private static void execReader(String keploy_test_id) throws IOException {
    // Together with the original class definition we can calculate coverage
    // information:
    System.out.println("------------------------------------------");
    ExecFileLoader loader = new ExecFileLoader();

    List<Map<String, Object>> dataList = new ArrayList<>();
    // Load the coverage data file
    File coverageFile = new File(
            System.getProperty("user.dir") +
                    "/target/jacoco-client" + keploy_test_id + ".exec");
//                File coverageFile = new File(
//                System.getProperty("user.dir") +
//                        "/target/jacoco-client.exec");
    loader.load(coverageFile);
    File binDir = new File(
            System.getProperty("user.dir")+ "/target/classes");
    final CoverageBuilder coverageBuilder = new CoverageBuilder();
    final Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), coverageBuilder);
    analyzer.analyzeAll(binDir);
    int x = 0;
    Map<String, List<Integer>> executedLinesByFile = new HashMap<>();

    for (final IClassCoverage cc : coverageBuilder.getClasses()) {
        // out.printf("Coverage of class %s%n", cc.getName());
        String ClassName = cc.getName(); // base64Encode(cc.getName());
        // System.out.println(cc.getMethods());
        java.util.Collection<org.jacoco.core.analysis.IMethodCoverage> method = cc.getMethods();

        cc.getInstructionCounter().getTotalCount();
        List<Integer> ls = new ArrayList<>();
        for (int i = cc.getFirstLine(); i <= cc.getLastLine(); i++) {
            // out.printf("Line %s: %s%n", Integer.valueOf(i),
            // getColor(cc.getLine(i).getStatus()));
            if (getColor(cc.getLine(i).getStatus()).equals("green")) {
                Line_Path += ClassName + i + ",";
                ls.add(i);
            }
        }
        if (ls.size() != 0) {
            executedLinesByFile.put(ClassName, ls);
        }

    }

//        System.out.println("Line_Path: " + Line_Path);

    Map<String, Object> testData = new HashMap<>();
    testData.put("id", keploy_test_id);
    // Map<String, Object> test1 = createTestData("test-1",testData);
    testData.put("executedLinesByFile", executedLinesByFile);

    dataList.add(testData);

    List<Map<String, Object>> existingData = readYamlFile("dedupData.yaml");
    // Append new data to the existing data
    existingData.addAll(dataList);

    // Write data to YAML file
    writeYamlFile(existingData, "dedupData.yaml");
}
private static List<Map<String, Object>> readYamlFile(String fileName) {
    List<Map<String, Object>> existingData = new ArrayList<>();

    try (InputStream input = new FileInputStream(fileName);
            UnicodeReader reader = new UnicodeReader(input)) {

        Yaml yaml = new Yaml();
        existingData = yaml.load(reader);

    } catch (IOException e) {
        e.printStackTrace();
    }

    return existingData != null ? existingData : new ArrayList<>();
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


    private static void writeYamlFile(List<Map<String, Object>> dataList, String fileName) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);

        Yaml yaml = new Yaml(options);

        try (FileWriter writer = new FileWriter(fileName)) {
            yaml.dump(dataList, writer);
            System.out.println("Dedup YAML file updated successfully:- " + fileName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }}
