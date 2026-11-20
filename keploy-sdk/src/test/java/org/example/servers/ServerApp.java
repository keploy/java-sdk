package org.example.servers;

import io.undertow.Undertow;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentManager;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.EnumSet;
import javax.servlet.DispatcherType;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

/**
 * ServerApp is the app of ServerTailTest's own JVMs: HelloServlet behind
 * TailFilter on the server args[0] names (tomcat, jetty or undertow), on a
 * port it prints, until its stdin closes.
 */
public final class ServerApp {

    private ServerApp() {
    }

    public static void main(String[] args) throws Exception {
        AutoCloseable server;
        int port;
        if (args[0].equals("tomcat")) {
            File base = Files.createTempDirectory("tomcat").toFile();
            Tomcat tomcat = new Tomcat();
            tomcat.setBaseDir(base.getPath());
            tomcat.setPort(0);
            Context context = tomcat.addContext("", base.getPath());
            Tomcat.addServlet(context, "hello", new HelloServlet());
            context.addServletMappingDecoded("/hello", "hello");
            FilterDef filter = new FilterDef();
            filter.setFilterName("tail");
            filter.setFilter(new TailFilter());
            context.addFilterDef(filter);
            FilterMap map = new FilterMap();
            map.setFilterName("tail");
            map.addURLPattern("/*");
            context.addFilterMap(map);
            tomcat.start();
            port = tomcat.getConnector().getLocalPort();
            server = tomcat::stop;
            try {
                // The module CoyoteAdapter is in, on Java 9 and later.
                System.out.println("MODULE " + Class.class.getMethod("getModule")
                        .invoke(Class.forName("org.apache.catalina.connector.CoyoteAdapter")));
            } catch (NoSuchMethodException e) {
                // Java 8.
            }
            Retransformer.afterStart();
        } else if (args[0].equals("jetty")) {
            Server jetty = new Server(0);
            ServletContextHandler context = new ServletContextHandler();
            context.addServlet(new ServletHolder(new HelloServlet()), "/hello");
            context.addFilter(new FilterHolder(new TailFilter()), "/*", EnumSet.of(DispatcherType.REQUEST));
            jetty.setHandler(context);
            jetty.start();
            port = ((ServerConnector) jetty.getConnectors()[0]).getLocalPort();
            server = jetty::stop;
        } else {
            DeploymentManager deployment = Servlets.defaultContainer().addDeployment(Servlets.deployment()
                    .setClassLoader(ServerApp.class.getClassLoader()).setContextPath("/").setDeploymentName("app")
                    .addServlet(Servlets.servlet("hello", HelloServlet.class).addMapping("/hello"))
                    .addFilter(Servlets.filter("tail", TailFilter.class))
                    .addFilterUrlMapping("tail", "/*", DispatcherType.REQUEST));
            deployment.deploy();
            Undertow undertow = Undertow.builder().addHttpListener(0, "127.0.0.1").setHandler(deployment.start())
                    .build();
            undertow.start();
            port = ((InetSocketAddress) undertow.getListenerInfo().get(0).getAddress()).getPort();
            server = undertow::stop;
        }
        System.out.println("PORT " + port);
        System.out.flush();
        while (System.in.read() >= 0) {
            // Until the test closes stdin.
        }
        server.close();
        System.exit(0);
    }
}
