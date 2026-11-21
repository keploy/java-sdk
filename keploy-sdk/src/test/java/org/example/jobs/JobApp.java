package org.example.jobs;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * JobApp is an app with a Quartz job and a Spring @Scheduled method, run by
 * JobTailTest with keploy-sdk.jar as its -javaagent. It prints the port it
 * takes commands on ("PORT n"), runs each command line it reads there, and
 * answers "DONE" once the job's run is over (its run() returned):
 * "quartz" fires the Quartz job, "spring" runs the @Scheduled method,
 * "spring-hold" starts it and answers once it waits, "spring-release" lets
 * it go on. "quartz-publish" and "spring-publish-hold" have the job publish
 * an event to an async multicaster and return (the listener held at its
 * start for the latter), "listener-block" and "listener-unblock" hold and
 * free its pool's thread, and "listener-done" lets the listener go on and
 * answers once it is done and its run is over. "priority-quartz" and
 * "priority-spring" have the job hand an app's priority pool its tasks
 * (PriorityPool), and "priority-app" the app's own thread; each prints
 * what came of it. With "plain" as its argument
 * it loads neither scheduler, and neither is on its class path: Schedulers,
 * which uses them, never loads.
 */
public final class JobApp {

    private JobApp() {
    }

    public static void main(String[] args) throws Exception {
        boolean plain = args.length > 0 && "plain".equals(args[0]);
        Schedulers schedulers = plain ? null : Schedulers.start();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            System.out.println("PORT " + server.getLocalPort());
            System.out.flush();
            try (Socket driver = server.accept()) {
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(driver.getInputStream(), StandardCharsets.UTF_8));
                OutputStream out = driver.getOutputStream();
                String line;
                while ((line = in.readLine()) != null) {
                    if (schedulers == null) {
                        throw new IllegalStateException("no scheduler runs: " + line);
                    }
                    schedulers.run(line);
                    out.write("DONE\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } finally {
            if (schedulers != null) {
                schedulers.close();
            }
        }
    }
}
