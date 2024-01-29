package io.keploy.servlet;

import io.grpc.netty.shaded.io.netty.util.internal.InternalThreadLocalMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;
import org.jacoco.core.tools.ExecFileLoader;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.reader.UnicodeReader;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.System.out;

public class KeployMiddleware implements Filter {

    private final ExecutorService executorService = Executors.newFixedThreadPool(10);
    public static int Lines_covered = 0;
    public static int Branch_covered = 0;
    public static int Lines_total = 0;
    public static int Branch_total = 0;
    public static int Methods_covered = 0;
    public static int Methods_total = 0;
    public static int Classes_covered = 0;
    public static int Classes_total = 0;
    public static String Line_Path = "";

    private static final Logger logger = LogManager.getLogger(KeployMiddleware.class);
    private static final String CROSS = new String(Character.toChars(0x274C));
    public static ArrayList<String> stackTraceArr = new ArrayList<>();
    private static boolean EnableDedup = true;
    public static AtomicInteger metCount = new AtomicInteger(0);
    public static AtomicInteger reqCount = new AtomicInteger(0);
    public static AtomicInteger cnt = new AtomicInteger(0);
    // public static AtomicInteger linesCovered = new AtomicInteger(0);

    // private static final String DESTFILE = "jacoco-client.exec";

    private static final String ADDRESS = "localhost";

    private static final int PORT = 36320;

    @Override
    public void init(FilterConfig filterConfig) {
        logger.debug("Keploy Middleware initialized");

    }

    private static final String SET_PLAIN_TEXT = "\033[0;0m";
    private static final String SET_BOLD_TEXT = "\033[0;1m";

    private static String bold(String str) {
        return (SET_BOLD_TEXT + str + SET_PLAIN_TEXT);
    }

 

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        String keploy_test_id = request.getHeader("KEPLOY-TEST-ID");
        // logger.debug("KEPLOY-TEST-ID: {}", keploy_test_id);
        filterChain.doFilter(request, response);

        if (keploy_test_id != null) {
            System.out.println("Hi there I am here !!");

            // Run getCoverage in a separate thread
//            Thread coverageThread = new Thread(() -> {
                try {
                    getCoverage(keploy_test_id);
                } catch (InterruptedException | IOException e) {
                    throw new RuntimeException(e);
                }
//            });
////
//            coverageThread.start();
        }
    }

    @Override
    public void destroy() {
        InternalThreadLocalMap.destroy();
    }

    public void execWriter() throws IOException {
        File directory = new File(
                "/Users/sarthak_1/Documents/Keploy/trash/samples-java/target");
        File file = new File(directory, "jacoco-client" + reqCount.get() + ".exec");
        final FileOutputStream localFile = new FileOutputStream(file);

        final ExecutionDataWriter localWriter = new ExecutionDataWriter(
                localFile);

        // Open a socket to the coverage agent:
        // Please try to get overall coverage information by creating another instance
        // and resetting to false
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

    public void getCoverage(String keploy_test_id) throws IOException, InterruptedException {
        System.out.println("Inside get coverage");


                try {
                    execWriter();
                } catch (IOException e) {
                    // Handle or log the IOException here
                    e.printStackTrace(); // Example: print the stack trace
                }

        try {
            execReader(keploy_test_id);
        } catch (IOException e) {
            // Handle or log the IOException here
            e.printStackTrace(); // Example: print the stack trace
        }
            // Call execReader directly in the current thread



    }

    public void shutdownExecutor() {
        executorService.shutdown();
    }

    private void execReader(String keploy_test_id) throws IOException {
        // Together with the original class definition we can calculate coverage
        // information:
        out.println("------------------------------------------");
        Line_Path = "";
        ExecFileLoader loader = new ExecFileLoader();
        // ExecutionDataWriter executionDataWriter = new ExecutionDataWriter(null);
        // ExecutionDataReader reader = new ExecutionDataReader(null);
        // reader.read();
        List<Map<String, Object>> dataList = new ArrayList<>();
        // Load the coverage data file
        File coverageFile = new File(
                "/Users/sarthak_1/Documents/Keploy/trash/samples-java/target/jacoco-client"
                        + reqCount.get() + ".exec");
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

        System.out.println("Line_Path: " + Line_Path);

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

    private void printCounter(final String unit, final ICounter counter) {
        final Integer missed = Integer.valueOf(counter.getMissedCount());
        final Integer total = Integer.valueOf(counter.getTotalCount());
        out.printf("%s of %s %s missed%n", missed, total, unit);
        Lines_covered = total - missed;
        System.out.println("Lines covered: " + Lines_covered);
        Lines_total = total;
        System.out.println("Lines total: " + Lines_total);

    }

    private String getColor(final int status) {
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

    public static String base64Encode(String input) {
        byte[] encodedBytes = Base64.getEncoder().encode(input.getBytes());
        return new String(encodedBytes);
    }

    private static void writeYamlFile(List<Map<String, Object>> dataList, String fileName) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);

        Yaml yaml = new Yaml(options);

        try (FileWriter writer = new FileWriter(fileName)) {
            yaml.dump(dataList, writer);
            System.out.println("YAML file updated successfully: " + fileName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}