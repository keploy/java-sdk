package com.example.ksql;


//import org.postgresql.jdbc.PgConnection;

import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

public class KConnection implements java.sql.Connection {

    private final Connection wrappedCon;

    public KConnection(Connection pgConnection) throws SQLException {
        this.wrappedCon = pgConnection;
        System.out.println("ihoooo connection !!");
    }

    @Override
    public Statement createStatement() throws SQLException {
        System.out.println("Mocked create Statement ! in connection ");
        Statement st = wrappedCon.createStatement();
        Statement kst = new KStatement(st);
        return kst;
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        PreparedStatement pst = wrappedCon.prepareStatement(sql);
        PreparedStatement kpst = new KPreparedStatement(pst);
        return kpst;
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        CallableStatement prepareCall = wrappedCon.prepareCall(sql);
        return prepareCall;
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        String nativeSQL = wrappedCon.nativeSQL(sql);
        return nativeSQL;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        wrappedCon.setAutoCommit(autoCommit);
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        boolean getAutoCommit = wrappedCon.getAutoCommit();
        return getAutoCommit;
    }

    @Override
    public void commit() throws SQLException {
        wrappedCon.commit();
    }

    @Override
    public void rollback() throws SQLException {
        wrappedCon.rollback();
    }

    @Override
    public void close() throws SQLException {
        wrappedCon.close();
    }

    @Override
    public boolean isClosed() throws SQLException {
        return wrappedCon.isClosed();
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        DatabaseMetaData getMetaData = wrappedCon.getMetaData();
        return getMetaData;
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        wrappedCon.setReadOnly(readOnly);
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return wrappedCon.isReadOnly();
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        wrappedCon.setCatalog(catalog);
    }

    @Override
    public String getCatalog() throws SQLException {
        return wrappedCon.getCatalog();
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        wrappedCon.setTransactionIsolation(level);
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        return wrappedCon.getTransactionIsolation();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return wrappedCon.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        wrappedCon.clearWarnings();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return new KStatement(wrappedCon.createStatement(resultSetType, resultSetConcurrency));
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return new KPreparedStatement(wrappedCon.prepareStatement(sql, resultSetType, resultSetConcurrency));
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return null;
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        return wrappedCon.getTypeMap();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        wrappedCon.setTypeMap(map);
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        wrappedCon.setHoldability(holdability);
    }

    @Override
    public int getHoldability() throws SQLException {
        return wrappedCon.getHoldability();
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        return new KSavepoint(wrappedCon.setSavepoint());
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        return new KSavepoint(wrappedCon.setSavepoint(name));
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        wrappedCon.rollback(savepoint);
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        wrappedCon.releaseSavepoint(savepoint);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return wrappedCon.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        PreparedStatement ps = wrappedCon.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        return ps;
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return new KCallableStatement(wrappedCon.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability));
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        return null;
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        return null;
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        return null;
    }

    @Override
    public Clob createClob() throws SQLException {
        return null;
    }

    @Override
    public Blob createBlob() throws SQLException {
        return null;
    }

    @Override
    public NClob createNClob() throws SQLException {
        return null;
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        return null;
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        return false;
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {

    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {

    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        return null;
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        return null;
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        return null;
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        return null;
    }

    @Override
    public void setSchema(String schema) throws SQLException {

    }

    @Override
    public String getSchema() throws SQLException {
        return null;
    }

    @Override
    public void abort(Executor executor) throws SQLException {

    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {

    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        return 0;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return null;
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return false;
    }
}
