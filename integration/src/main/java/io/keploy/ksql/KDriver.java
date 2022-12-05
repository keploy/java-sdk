package io.keploy.ksql;


import io.keploy.regression.Mode;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import oracle.jdbc.driver.OracleDriver;

import java.sql.Connection;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Logger;

public class KDriver implements java.sql.Driver {
    public java.sql.Driver wrappedDriver;


    private String _url;
    private String _username;
    private String _password;

    private String _databaseName;
    public final Kcontext kctx = Context.getCtx();
    static Mode.ModeType mode = null;
    public static String DriverName = "";
    private Integer _version = 1;

    private Connection _connection;

    public static String Dialect = "";

    public Boolean _isConnected = false;

    private String _lastInsertId = "-1";
    public static Mode.ModeType testMode = Mode.ModeType.MODE_TEST;
    public static Mode.ModeType recordMode = Mode.ModeType.MODE_TEST;

    public KDriver() throws SQLException {
        if (Objects.equals(System.getenv("KEPLOY_MODE"), "record")) {
            mode = Mode.ModeType.MODE_RECORD;
        } else if (Objects.equals(System.getenv("KEPLOY_MODE"), "test")) {
            mode = Mode.ModeType.MODE_TEST;
        }
        wrappedDriver = getWrappedDriver();
    }

    private java.sql.Driver getWrappedDriver() throws SQLException {
        String driver = DriverName;

        java.sql.Driver d;
        switch (driver) {
            case "org.postgresql.Driver":
                d =  new org.postgresql.Driver();
                break;
            case "com.mysql.cj.jdbc.Driver":
                d =  new com.mysql.cj.jdbc.Driver();
                break;
            case "org.h2.Driver":
                d = new org.h2.Driver();
                break;
            case "oracle.jdbc.driver.OracleDriver":
                d = new OracleDriver();
                break;
            case "org.mariadb.jdbc.Driver":
                d = new org.mariadb.jdbc.Driver();
                break;
            default:
                d =  null;
        }
        return d;
    }


    @Override
    public Connection connect(String url, Properties info) throws SQLException {

        if (Objects.equals(DriverName, "org.h2.Driver")) {
            return wrappedDriver.connect(url, info);
        }
        if (mode == testMode) {
            return new KConnection();
        }
        Connection conn = null;
        try {
            conn = wrappedDriver.connect(url, info);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return new KConnection(conn);

    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        if (mode == testMode) {
            return true;
        }
        return wrappedDriver.acceptsURL(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        if (Objects.equals(System.getenv("KEPLOY_MODE"), "test")) {
            return 1;
        }
        return wrappedDriver.getMajorVersion();
    }


    @Override
    public int getMinorVersion() {
        if (Objects.equals(System.getenv("KEPLOY_MODE"), "test")) {
            return 1;
        }
        return wrappedDriver.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
        if (Objects.equals(System.getenv("KEPLOY_MODE"), "test")) {
            return true;
        }
        return wrappedDriver.jdbcCompliant();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return wrappedDriver.getParentLogger();
    }
}
