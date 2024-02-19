package io.keploy.servlet;

import me.tongfei.progressbar.ProgressBar;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.System.out;

public class Experiments {
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

    private static void execReader() throws IOException {
        // Together with the original class definition we can calculate coverage
        // information:
        out.println("------------------------------------------");
        String Line_Path = "";
        ExecFileLoader loader = new ExecFileLoader();
        // ExecutionDataWriter executionDataWriter = new ExecutionDataWriter(null);
        // ExecutionDataReader reader = new ExecutionDataReader(null);
        // reader.read();
        List<Map<String, Object>> dataList = new ArrayList<>();
        // Load the coverage data file
        File coverageFile = new File(
                "/Users/sarthak_1/Documents/Keploy/trash/samples-java/target/jacoco-clienttest-188.exec");
        loader.load(coverageFile);
        File binDir = new File(
                "/Users/sarthak_1/Documents/Keploy/trash/samples-java/target/classes");
        final CoverageBuilder coverageBuilder = new CoverageBuilder();
        final Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), coverageBuilder);
        analyzer.analyzeAll(binDir);
        int x = 0;
        Map<String, List<Integer>> executedLinesByFile = new HashMap<>();

        for (final IClassCoverage cc : coverageBuilder.getClasses()) {
            // out.printf("Coverage of class %s%n", cc.getName());
            String ClassName = cc.getName(); // base64Encode(cc.getName());
             System.out.println(cc.getMethods());
            java.util.Collection<org.jacoco.core.analysis.IMethodCoverage> method = cc.getMethods();

            cc.getInstructionCounter().getTotalCount();
            List<Integer> ls = new ArrayList<>();
            for (int i = cc.getFirstLine(); i <= cc.getLastLine(); i++) {
                // out.printf("Line %s: %s%n", Integer.valueOf(i),
                // getColor(cc.getLine(i).getStatus()));
                if (getColor(cc.getLine(i).getStatus()).equals("green")) {
                    Line_Path += ClassName + i + ",";
                    out.println("LINE PATH " + Line_Path);
                    ls.add(i);
                }
            }
            if (ls.size() != 0) {
                executedLinesByFile.put(ClassName, ls);
            }

        }

        System.out.println("Line_Path: " + Line_Path);

//        Map<String, Object> testData = new HashMap<>();
//        testData.put("id", keploy_test_id);
//        // Map<String, Object> test1 = createTestData("test-1",testData);
//        testData.put("executedLinesByFile", executedLinesByFile);
//
//        dataList.add(testData);

//        List<Map<String, Object>> existingData = readYamlFile("dedupData.yaml");
//        // Append new data to the existing data
//        existingData.addAll(dataList);
//
//        // Write data to YAML file
//        writeYamlFile(existingData, "dedupData.yaml");
    }
    public static void main(String[] args) throws IOException {
//        int totalTasks = 1000;
//
//        ProgressBar progressBar = new ProgressBar("Progress", totalTasks);
//        progressBar.start();
//
//        for (int i = 0; i <= totalTasks; i++) {
//            // Simulate some task
//            try {
//                Thread.sleep(50);
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            }
//
//            // Increment the progress bar
//            progressBar.step();
//        }
//
//        progressBar.stop();
        execReader();
    }
}
