package io.keploy.ksql;


import io.keploy.regression.Mode;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import oracle.jdbc.OracleDriver;
import org.apache.logging.log4j.LogManager;

import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

public class KDriver implements Driver {
    public Driver wrappedDriver;

    public final Kcontext kctx = Context.getCtx();
    static Mode.ModeType mode = null;
    public static String DriverName = "";

    public static String Dialect = "";

    public static Mode.ModeType testMode = Mode.ModeType.MODE_TEST;
    public static Mode.ModeType recordMode = Mode.ModeType.MODE_RECORD;
    private static final org.apache.logging.log4j.Logger logger = LogManager.getLogger(KDriver.class);

    private static final String CROSS = new String(Character.toChars(0x274C));

    public KDriver(Driver driver) {
        if (Objects.equals(System.getenv("KEPLOY_MODE"), "record")) {
            mode = Mode.ModeType.MODE_RECORD;
        } else if (Objects.equals(System.getenv("KEPLOY_MODE"), "test")) {
            mode = Mode.ModeType.MODE_TEST;
        }
        logger.debug("KEPLOY DRIVER INITIALIZE");

        this.wrappedDriver = driver;
    }

    public static void WrapDriver() throws SQLException {
        final Enumeration<Driver> drivers = DriverManager.getDrivers();
        ArrayList<Driver> list = Collections.list(drivers);
        logger.debug("Number of Drivers to wrap:{}", list
                .size());
        for (Driver dr : list) {
            logger.debug("wrapping and registering driver:{}", dr);
            DriverManager.deregisterDriver(dr);
            DriverManager.registerDriver(new KDriver(dr));
        }
    }

    public KDriver() throws SQLException {
        if (Objects.equals(System.getenv("KEPLOY_MODE"), "record")) {
            mode = Mode.ModeType.MODE_RECORD;
        } else if (Objects.equals(System.getenv("KEPLOY_MODE"), "test")) {
            mode = Mode.ModeType.MODE_TEST;
        }
        wrappedDriver = getWrappedDriver();
        // set record mode as
        if (Objects.equals(DriverName, "org.h2.Driver")) {
            logger.info("starting test connection for H2 ");
            mode = recordMode;
        }
    }

    private Driver getWrappedDriver() throws SQLException {
        String driver = DriverName;
        Driver d;
        switch (driver) {
            case "org.postgresql.Driver":
                d = new org.postgresql.Driver();
                break;
            case "com.mysql.cj.jdbc.Driver":
                d = new com.mysql.cj.jdbc.Driver();
                break;
            case "org.h2.Driver":
                d = new org.h2.Driver();
                break;
            case "oracle.jdbc.driver.OracleDriver":
                d = new oracle.jdbc.driver.OracleDriver();
                break;
            case "oracle.jdbc.OracleDriver":
                d = new OracleDriver();
                break;
            case "org.mariadb.jdbc.Driver":
                d = new org.mariadb.jdbc.Driver();
                break;
            default:
                d = null;
        }
        return d;
    }


    @Override
    public Connection connect(String url, Properties info) {

        if (mode == testMode) {
            try {
                return new KConnection();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
        Connection conn = null;
        try {
            conn = wrappedDriver.connect(url, info);
        } catch (SQLException e) {
            logger.error(CROSS+ " Keploy cannot establish connection with default DB \n"+  e);
        }
        return new KConnection(conn);

    }

    @Override
    public boolean acceptsURL(String url) {
        if (mode == testMode) {
            return true;
        }
        try {
            return wrappedDriver.acceptsURL(url);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        if (mode == testMode) {
            return 1;
        }
        return wrappedDriver.getMajorVersion();
    }


    @Override
    public int getMinorVersion() {
        if (mode == testMode) {
            return 0;
        }
        return wrappedDriver.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
        if (mode == testMode) {
            return true;
        }
        return wrappedDriver.jdbcCompliant();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        if (mode == testMode) {
            return (Logger) logger;
        }
        return wrappedDriver.getParentLogger();
    }
}
