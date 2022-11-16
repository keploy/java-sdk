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
    Mode.ModeType mode = null;
    public static String DriverName = "";
    private Integer _version = 1;
    private Connection _connection;
    public Boolean _isConnected = false;

    private String _lastInsertId = "-1";

    public KDriver() throws SQLException {
        if (Objects.equals(System.getenv("KEPLOY_MODE"), "record")) {
            mode = Mode.ModeType.MODE_RECORD;
        } else if (Objects.equals(System.getenv("KEPLOY_MODE"), "test")) {
            mode = Mode.ModeType.MODE_TEST;
        }
        wrappedDriver = getWrappedDriver();
//        System.out.println("hello inside no-arg constructor");
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

        if (Objects.equals(System.getenv("KEPLOY_MODE"), "test")) {
            return new KConnection();
        }
        Connection resultSet = null;
        try {
            resultSet = wrappedDriver.connect(url, info);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return new KConnection(resultSet);

    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        boolean acceptsURL = wrappedDriver.acceptsURL(url);
        return acceptsURL;
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        int getMajor = wrappedDriver.getMajorVersion();
        return getMajor;
    }


    @Override
    public int getMinorVersion() {
        int getMinor = wrappedDriver.getMinorVersion();
        return getMinor;
    }

    @Override
    public boolean jdbcCompliant() {
        boolean jdbcCompliant = wrappedDriver.jdbcCompliant();
        return jdbcCompliant;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return wrappedDriver.getParentLogger();
    }
}
