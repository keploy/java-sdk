package io.keploy.servlet;

import io.grpc.netty.shaded.io.netty.util.internal.InternalThreadLocalMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import io.keploy.utils.CoverageHandler;


import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        String keploy_test_id = request.getHeader("KEPLOY-TEST-ID");
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
                CoverageHandler.getCoverage(keploy_test_id);
                
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

    public void shutdownExecutor() {
        executorService.shutdown();
    }




    public static String base64Encode(String input) {
        byte[] encodedBytes = Base64.getEncoder().encode(input.getBytes());
        return new String(encodedBytes);
    }


}