package io.keploy.ksql;


import io.keploy.regression.Mode;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import org.mockito.Mockito;

import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

import static io.keploy.ksql.KDriver.*;


public class KConnection implements Connection {

    private Connection wrappedCon = null;

    public KConnection(Connection connection) {
        System.out.println("init kconnection");
        this.wrappedCon = connection;
    }

    public KConnection() throws SQLException {

    }

    @Override
    public Statement createStatement() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.createStatement();
            }
            Statement resultSet = Mockito.mock(Statement.class);
            return new KStatement(resultSet);
        }
        Statement st = wrappedCon.createStatement();
        Statement kst = new KStatement(st);
        return kst;
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.prepareStatement(sql);
            }
            PreparedStatement resultSet = Mockito.mock(PreparedStatement.class);
            return new KPreparedStatement(resultSet);
        }
        Mode.ModeType mode = kctx.getMode();
        PreparedStatement ps = new KPreparedStatement();
        switch (mode) {
            case MODE_TEST:
                ps = Mockito.mock(PreparedStatement.class);
                break;
            case MODE_RECORD:
                ps = wrappedCon.prepareStatement(sql);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return new KPreparedStatement(ps);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.prepareCall(sql);
            }
            CallableStatement resultSet = Mockito.mock(CallableStatement.class);
            return new KCallableStatement(resultSet);
        }
        Mode.ModeType mode = kctx.getMode();

        CallableStatement rs = null;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedCon.prepareCall(sql);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return new KCallableStatement(rs);
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.nativeSQL(sql);
            }
        }
        String st = wrappedCon.nativeSQL(sql);
        return st;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedCon.setAutoCommit(autoCommit);
            }
            return;
        }
        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedCon.setAutoCommit(autoCommit);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.getAutoCommit();
            }
            return false;
        }
        Mode.ModeType mode = kctx.getMode();

        boolean rs = false;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedCon.getAutoCommit();
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;

    }

    @Override
    public void commit() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedCon.commit();
            }
            return;
        }
        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedCon.commit();
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public void rollback() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedCon.rollback();
            }
            return;
        }
        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedCon.rollback();
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public void close() throws SQLException {

        wrappedCon.close();

    }

    @Override
    public boolean isClosed() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.isClosed();
            }
            return true;
        }
        Mode.ModeType mode = kctx.getMode();
        boolean rs = true;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedCon.isClosed();
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;

    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.getMetaData();
            }
            return new KDatabaseMetaData(Mockito.mock(DatabaseMetaData.class));
        }
        Mode.ModeType mode = kctx.getMode();

        DatabaseMetaData rs = null;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedCon.getMetaData();
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;

    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedCon.setReadOnly(readOnly);
            }
            return;
        }
        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedCon.setReadOnly(readOnly);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public boolean isReadOnly() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedCon.isReadOnly();
            }
            return true;
        }
        Mode.ModeType mode = kctx.getMode();
        boolean rs = false;

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedCon.isReadOnly();
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;

    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedCon.setCatalog(catalog);
            }
            return;
        }
        Mode.ModeType mode = kctx.getMode();


        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedCon.setCatalog(catalog);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }


    }

    @Override
    public String getCatalog() throws SQLException {
        if (mode == testMode) {
            return "KEPLOY_CATALOG";
        }
        return wrappedCon.getCatalog();
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedCon.setTransactionIsolation(level);
            }
            return;
        }
        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedCon.setTransactionIsolation(level);
                return;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.getTransactionIsolation();
            }
            return 2;
        }
        Mode.ModeType mode = kctx.getMode();

        int rs = 2;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedCon.getTransactionIsolation();
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;

    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.getWarnings();
            }
            return null;
        }
        Mode.ModeType mode = kctx.getMode();
        SQLWarning rs = null;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedCon.getWarnings();
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;

    }

    @Override
    public void clearWarnings() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedCon.clearWarnings();
            }
            return;
        }
        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedCon.clearWarnings();
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.createStatement(resultSetType, resultSetConcurrency);
            }
            Statement resultSet = Mockito.mock(Statement.class);
            return new KStatement(resultSet);
        }
        Mode.ModeType mode = kctx.getMode();

        Statement rs = new KStatement();
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedCon.createStatement(resultSetType, resultSetConcurrency);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

        return rs;
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.prepareStatement(sql, resultSetType, resultSetConcurrency);
            }
        }
        assert kctx != null;
        Mode.ModeType mode = kctx.getMode();

        PreparedStatement rs = new KPreparedStatement();
        switch (mode) {
            case MODE_TEST:
                // don't run
//                rs = testconn.prepareStatement(sql, resultSetType, resultSetConcurrency);
                break;
            case MODE_RECORD:
                rs = wrappedCon.prepareStatement(sql, resultSetType, resultSetConcurrency);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.prepareCall(sql, resultSetType, resultSetConcurrency);
            }
            CallableStatement resultSet = Mockito.mock(CallableStatement.class);
            return new KCallableStatement(resultSet);
        }
        Mode.ModeType mode = kctx.getMode();

        CallableStatement rs = new KCallableStatement();
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedCon.prepareCall(sql, resultSetType, resultSetConcurrency);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return new KCallableStatement(rs);
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.getTypeMap();
            }
            return null;
        }
        Mode.ModeType mode = kctx.getMode();

        Map<String, Class<?>> rs = null;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedCon.getTypeMap();
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

        return rs;

    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedCon.setTypeMap(map);
            }
            return;
        }
        Mode.ModeType mode = kctx.getMode();


        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedCon.setTypeMap(map);
                ;
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedCon.setHoldability(holdability);
            }
            return;
        }
        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedCon.setHoldability(holdability);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
    }

    @Override
    public int getHoldability() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.getHoldability();
            }
            return 0;
        }
        Mode.ModeType mode = kctx.getMode();

        int rs = 0;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedCon.getHoldability();
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

        return rs;

    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        return wrappedCon.setSavepoint();
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        return wrappedCon.setSavepoint(name);
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedCon.rollback(savepoint);
            }
            return;
        }
        Mode.ModeType mode = kctx.getMode();


        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedCon.rollback(savepoint);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        wrappedCon.releaseSavepoint(savepoint);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedCon.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
            }
            Statement resultSet = Mockito.mock(Statement.class);
            return new KStatement(resultSet);
        }
        Mode.ModeType mode = kctx.getMode();

        Statement rs = new KStatement();
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedCon.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return new KStatement(rs);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
//        System.out.println("INSIDE PREPARED STATEMENT of connection !! " + sql);
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
            }
        }
        assert kctx != null;
        Mode.ModeType mode = kctx.getMode();

        PreparedStatement rs = new KPreparedStatement();
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = new KPreparedStatement(wrappedCon.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability));
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

        return rs;
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
            }
            CallableStatement resultSet = Mockito.mock(CallableStatement.class);
            return new KCallableStatement(resultSet);
        }
        Mode.ModeType mode = kctx.getMode();

        CallableStatement rs = new KCallableStatement();
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedCon.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return new KCallableStatement(rs);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
//        System.out.println("INSIDE PREPARED STATEMENT of connection !! " + sql+ " **** " +autoGeneratedKeys);
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.prepareStatement(sql, autoGeneratedKeys);
            }
            PreparedStatement resultSet = Mockito.mock(PreparedStatement.class);
            return new KPreparedStatement(resultSet);
        }
        Mode.ModeType mode = kctx.getMode();
        PreparedStatement rs = new KPreparedStatement();

        switch (mode) {
            case MODE_TEST:
                // don't run
                rs = Mockito.mock(PreparedStatement.class);
                break;
            case MODE_RECORD:
                rs = wrappedCon.prepareStatement(sql, autoGeneratedKeys);

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return new KPreparedStatement(rs);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.prepareStatement(sql, columnIndexes);
            }
        }
        assert kctx != null;
        Mode.ModeType mode = kctx.getMode();

        PreparedStatement rs = new KPreparedStatement();
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = new KPreparedStatement(wrappedCon.prepareStatement(sql, columnIndexes));
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

        return rs;
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.prepareStatement(sql, columnNames);
            }
        }
        assert kctx != null;
        Mode.ModeType mode = kctx.getMode();

        PreparedStatement rs = new KPreparedStatement();
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = new KPreparedStatement(wrappedCon.prepareStatement(sql, columnNames));
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return new KPreparedStatement(rs);

    }

    @Override
    public Clob createClob() throws SQLException {
        return wrappedCon.createClob();
    }

    @Override
    public Blob createBlob() throws SQLException {
        return wrappedCon.createBlob();
    }

    @Override
    public NClob createNClob() throws SQLException {
        return wrappedCon.createNClob();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        return wrappedCon.createSQLXML();
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.isValid(timeout);
            }
            return true;
        }
        Mode.ModeType mode = kctx.getMode();

        boolean rs = true;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedCon.isValid(timeout);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;

    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        wrappedCon.setClientInfo(name, value);
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        wrappedCon.setClientInfo(properties);
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        return wrappedCon.getClientInfo(name);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        return wrappedCon.getClientInfo();
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        return wrappedCon.createArrayOf(typeName, elements);
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        return wrappedCon.createStruct(typeName, attributes);
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        wrappedCon.setSchema(schema);
    }

    @Override
    public String getSchema() throws SQLException {
        return wrappedCon.getSchema();
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        wrappedCon.abort(executor);
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        wrappedCon.setNetworkTimeout(executor, milliseconds);
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        return wrappedCon.getNetworkTimeout();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return wrappedCon.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return wrappedCon.isWrapperFor(iface);
    }
}
