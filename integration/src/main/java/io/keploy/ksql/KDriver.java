package com.example.ksql;

//import com.mysql.cj.jdbc.Driver;
import org.postgresql.Driver;
import java.sql.Connection;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;



public class KDriver implements java.sql.Driver {
    public Driver wrappedDriver;

    private String _url;
    private String _username;
    private String _password;

    private String _databaseName;

    private Integer _version = 1;
    private Connection _connection;
    public Boolean _isConnected = false;

    private String _lastInsertId = "-1";

    public KDriver(){
//        super();
        System.out.println("hello inside no-arg constructor");
    }

//    public KDriver(String url, String user, String password) throws SQLException {
//        System.out.println("Hi in sql driver constructor");
//        _url = url;
//        _username = user;
//        _password = password;
//
//    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        System.out.println("HI THERE Mocked!");
        wrappedDriver = new Driver();
        _connection = wrappedDriver.connect(url, info);
        Connection kobj = new KConnection(_connection);
        return kobj;
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
