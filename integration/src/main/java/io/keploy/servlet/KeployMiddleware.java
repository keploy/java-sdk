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
    private static boolean EnableDedup = false;
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
        String keploy_test_set_id = request.getHeader("KEPLOY-TEST-SET-ID");
        // logger.debug("KEPLOY-TEST-ID: {}", keploy_test_id);
        filterChain.doFilter(request, response);
        if (System.getenv("ENABLE_DEDUP") != null) {
            String bool = System.getenv("ENABLE_DEDUP").trim();
            EnableDedup = bool.equals("true");
        }
        // check if dedup is disabled then what should be the goal may be we can extract from header if dedup is enabled or not
        if (keploy_test_id != null && EnableDedup) {

            // Run getCoverage in a separate thread
//            Thread coverageThread = new Thread(() -> {
            try {
                getCoverage(keploy_test_id, keploy_test_set_id);
            } catch (InterruptedException | IOException e) {
                throw new RuntimeException(e);
            }
//            });

//            coverageThread.start();
//            try {
//                Thread.sleep(500);
//            } catch (InterruptedException e) {
//                throw new RuntimeException(e);
//            }
        }

    }

    @Override
    public void destroy() {
        InternalThreadLocalMap.destroy();
    }

    public void execWriter(String keploy_test_id) throws IOException {
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

    public synchronized void execWriter2(String keploy_test_id) throws IOException {
        File directory = new File(System.getProperty("user.dir")+"/target");
        File file = new File(directory, "jacoco-client" + keploy_test_id + ".exec");

        FileOutputStream localFile = null;
        ExecutionDataWriter localWriter = null;
        Socket socket = null;
        RemoteControlWriter writer = null;
        RemoteControlReader reader = null;

        try {
            localFile = new FileOutputStream(file);
            BufferedOutputStream bufferedLocalFile = new BufferedOutputStream(localFile);
            localWriter = new ExecutionDataWriter(bufferedLocalFile);
            socket = new Socket(InetAddress.getByName(ADDRESS), PORT);
            writer = new RemoteControlWriter(socket.getOutputStream());
            reader = new RemoteControlReader(socket.getInputStream());

            reader.setSessionInfoVisitor(localWriter);
            reader.setExecutionDataVisitor(localWriter);

            // Send a dump command and read the response:
            writer.visitDumpCommand(true, true);

            if (!reader.read()) {
                throw new IOException("Socket closed unexpectedly.");
            }
        } finally {
            // Close resources in a finally block to ensure they are closed even if an exception occurs

            if (socket != null && !socket.isClosed()) {
                socket.close();
            }

            if (localFile != null) {
                localFile.close();
            }
        }
    }

    public void getCoverage(String keploy_test_id, String keploy_test_set_id) throws IOException, InterruptedException {

        try {
            execWriter(keploy_test_id);
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            execReader(keploy_test_id, keploy_test_set_id);
        } catch (IOException e) {
            e.printStackTrace(); // Example: print the stack trace
        }

    }

    public void shutdownExecutor() {
        executorService.shutdown();
    }

    private void execReader(String keploy_test_id, String keploy_test_set_id) throws IOException {
        // Together with the original class definition we can calculate coverage
        // information:
        out.println("------------------------------------------");
        Line_Path = "";
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
        testData.put("id", keploy_test_set_id+ "/" + keploy_test_id);
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
//        System.out.println("Lines covered: " + Lines_covered);
        Lines_total = total;
//        System.out.println("Lines total: " + Lines_total);

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
            System.out.println("Dedup YAML file updated successfully:- " + fileName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}