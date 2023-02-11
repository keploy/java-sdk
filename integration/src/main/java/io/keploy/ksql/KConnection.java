package io.keploy.ksql;


import io.keploy.regression.Mode;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import org.apache.logging.log4j.LogManager;

import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

import static io.keploy.ksql.KDriver.*;
import static io.keploy.ksql.KResultSet.*;
import static io.keploy.utils.ProcessSQL.convertMap;


public class KConnection implements Connection {

    private Connection wrappedCon = null;

    public static String MyQuery = "";

    static boolean firstTime = true;
    private static final org.apache.logging.log4j.Logger logger = LogManager.getLogger(KConnection.class);

    private static final String THUNDER = "\u26A1\uFE0F";

    private static final String CROSS = new String(Character.toChars(0x274C));


    public KConnection(Connection connection) {
        if (firstTime && connection!=null) {
            logger.info(THUNDER + " Keploy wrapped "+ DriverName + " successfully !" );
            firstTime = false;
        }
        this.wrappedCon = connection;
    }

    public KConnection() throws SQLException {
        if (firstTime) {
            logger.info(THUNDER + " Keploy mock connection initialization during tests");
            firstTime = false;
        }
    }

    @Override
    public Statement createStatement() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.createStatement();
            }
            Statement resultSet = new KStatement();
            return new KStatement(resultSet);
        }
        mode = kctx.getMode();
        logger.debug("KStatement after setting context with query");
        Statement ps = new KStatement();
        switch (mode) {
            case MODE_TEST:
                ps = new KStatement();
                break;
            case MODE_RECORD:
                ps = wrappedCon.createStatement();

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return new KStatement(ps);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.prepareStatement(sql);
            }
            PreparedStatement resultSet = new KPreparedStatement(); // Mockito.mock(PreparedStatement.class);
            return new KPreparedStatement(resultSet);
        }
        mode = kctx.getMode();
        MyQuery = sql;
        logger.debug("KPrepared statement after setting context with query" + sql);
        PreparedStatement ps = new KPreparedStatement();
        switch (mode) {
            case MODE_TEST:
                meta.clear();
                java.util.List<io.keploy.grpc.stubs.Service.Mock> mock = kctx.getMock();
                if (mock.size() > 0) {
                    if (mock.get(0).getKind().equals("SQL") && mock.get(0).getSpec().getMetadataMap().size() > 0) {
                        meta = convertMap(mock.get(0).getSpec().getMetadataMap());
                    }else {
                        logger.debug("Query {} has no metaData", MyQuery);
                    }
                }
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
            CallableStatement resultSet = new KCallableStatement();//Mockito.mock(CallableStatement.class);
            return new KCallableStatement(resultSet);
        }
        Mode.ModeType mode = kctx.getMode();

        CallableStatement rs = new KCallableStatement();
        switch (mode) {
            case MODE_TEST:
                // don't run
                meta.clear();
                java.util.List<io.keploy.grpc.stubs.Service.Mock> mock = kctx.getMock();
                if (mock.size() > 0) {
                    if (mock.get(0).getKind().equals("SQL") && mock.get(0).getSpec().getMetadataMap().size() > 0) {
                        meta = convertMap(mock.get(0).getSpec().getMetadataMap());
                    }else {
                        logger.debug("Query {} has no metaData", MyQuery);
                    }
                }
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
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedCon.isClosed();
            }
            return;
        }
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
            return new KDatabaseMetaData();
        }

        DatabaseMetaData rs = new KDatabaseMetaData();
        mode = kctx.getMode();
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
                return wrappedCon.isReadOnly();
            }
            return false;
        }
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
            Statement resultSet = new KStatement();//Mockito.mock(Statement.class);
            return new KStatement(resultSet);
        }

        Statement rs = new KStatement();
        mode = kctx.getMode();
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
            return new KPreparedStatement();
        }

        mode = kctx.getMode();
        MyQuery = sql;
        PreparedStatement rs = new KPreparedStatement();
        switch (mode) {
            case MODE_TEST:
                // don't run
                meta.clear();
                java.util.List<io.keploy.grpc.stubs.Service.Mock> mock = kctx.getMock();
                if (mock.size() > 0) {
                    if (mock.get(0).getKind().equals("SQL") && mock.get(0).getSpec().getMetadataMap().size() > 0) {
                        meta = convertMap(mock.get(0).getSpec().getMetadataMap());
                    }else {
                        logger.debug("Query {} has no metaData", MyQuery);
                    }
                }
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
            CallableStatement resultSet = new KCallableStatement();//Mockito.mock(CallableStatement.class);
            return new KCallableStatement(resultSet);
        }

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
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedCon.setTypeMap(map);
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
        logger.warn("{} Savepoint setSavepoint() throws SQLException {}", msg1, msg2);
        return wrappedCon.setSavepoint();
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        logger.warn("{} Savepoint setSavepoint(String name) throws SQLException {}", msg1, msg2);
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
        logger.warn("{} void releaseSavepoint(Savepoint savepoint) throws SQLException {}", msg1, msg2);
        wrappedCon.releaseSavepoint(savepoint);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedCon.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
            }
            Statement resultSet = new KStatement();//Mockito.mock(Statement.class);
            return new KStatement(resultSet);
        }

        Statement rs = new KStatement();
        mode = kctx.getMode();
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
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
            }
            return new KPreparedStatement();
        }
        MyQuery = sql;
        mode = kctx.getMode();
        PreparedStatement rs = new KPreparedStatement();
        switch (mode) {
            case MODE_TEST:
                // don't run
                meta.clear();
                java.util.List<io.keploy.grpc.stubs.Service.Mock> mock = kctx.getMock();
                if (mock.size() > 0) {
                    if (mock.get(0).getKind().equals("SQL") && mock.get(0).getSpec().getMetadataMap().size() > 0) {
                        meta = convertMap(mock.get(0).getSpec().getMetadataMap());
                    }else {
                        logger.debug("Query {} has no metaData", MyQuery);
                    }
                }
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
            CallableStatement resultSet = new KCallableStatement();//Mockito.mock(CallableStatement.class);
            return new KCallableStatement(resultSet);
        }

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
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedCon.prepareStatement(sql, autoGeneratedKeys);
            }
            return new KPreparedStatement();
        }
        PreparedStatement rs = null;
        logger.debug("KPrepared statement after setting context with query" + sql + "with autoGeneratedKeys : " + autoGeneratedKeys);
        mode = kctx.getMode();
        switch (mode) {
            case MODE_TEST:
                // don't run
                meta.clear();
                java.util.List<io.keploy.grpc.stubs.Service.Mock> mock = kctx.getMock();
                if (mock.size() > 0) {
                    if (mock.get(0).getKind().equals("SQL") && mock.get(0).getSpec().getMetadataMap().size() > 0) {
                        meta = convertMap(mock.get(0).getSpec().getMetadataMap());
                    }else {
                        logger.debug("Query {} has no metaData", MyQuery);
                    }
                }
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
            return new KPreparedStatement();
        }
        MyQuery = sql;

        mode = kctx.getMode();

        PreparedStatement rs = new KPreparedStatement();
        switch (mode) {
            case MODE_TEST:
                meta.clear();
                java.util.List<io.keploy.grpc.stubs.Service.Mock> mock = kctx.getMock();
                if (mock.size() > 0) {
                    if (mock.get(0).getKind().equals("SQL") && mock.get(0).getSpec().getMetadataMap().size() > 0) {
                        meta = convertMap(mock.get(0).getSpec().getMetadataMap());
                    } else {
                        logger.debug("Query {} has no metaData", MyQuery);
                    }
                }
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
            return new KPreparedStatement();
        }
        mode = kctx.getMode();
        MyQuery = sql;
        PreparedStatement rs = new KPreparedStatement();
        switch (mode) {
            case MODE_TEST:
                // don't run
                meta.clear();
                java.util.List<io.keploy.grpc.stubs.Service.Mock> mock = kctx.getMock();
                if (mock.size() > 0) {
                    if (mock.get(0).getKind().equals("SQL") && mock.get(0).getSpec().getMetadataMap().size() > 0) {
                        meta = convertMap(mock.get(0).getSpec().getMetadataMap());
                    }else {
                        logger.debug("Query {} has no metaData", MyQuery);
                    }
                }
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
        logger.warn("{} Clob createClob() throws SQLException {}", msg1, msg2);
        return wrappedCon.createClob();
    }

    @Override
    public Blob createBlob() throws SQLException {
        logger.warn("{} Blob createBlob() throws SQLException {}", msg1, msg2);
        return wrappedCon.createBlob();
    }

    @Override
    public NClob createNClob() throws SQLException {
        logger.warn("{} NClob createNClob() throws SQLException {}", msg1, msg2);
        return wrappedCon.createNClob();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        logger.warn("{} SQLXML createSQLXML() throws SQLException {}", msg1, msg2);
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
        logger.warn("{} void setClientInfo(String name, String value) throws SQLException {}", msg1, msg2);
        wrappedCon.setClientInfo(name, value);
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        logger.warn("{} void setClientInfo(Properties properties) throws SQLException {}", msg1, msg2);
        wrappedCon.setClientInfo(properties);
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        logger.warn("{} String getClientInfo(String name) throws SQLException {}", msg1, msg2);
        return wrappedCon.getClientInfo(name);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        logger.warn("{} Properties getClientInfo() throws SQLException {}", msg1, msg2);
        return wrappedCon.getClientInfo();
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        logger.warn("{} Array createArrayOf(String typeName, Object[] elements) throws SQLException {}", msg1, msg2);
        return wrappedCon.createArrayOf(typeName, elements);
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        logger.warn("{} Struct createStruct(String typeName, Object[] attributes) throws SQLException {}", msg1, msg2);
        return wrappedCon.createStruct(typeName, attributes);
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        logger.warn("{} void setSchema(String schema) throws SQLException {}", msg1, msg2);
        wrappedCon.setSchema(schema);
    }

    @Override
    public String getSchema() throws SQLException {
        if (mode == testMode) {
            return "KEPLOY_SCHEMA";
        }
        return wrappedCon.getSchema();
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        logger.warn("{} void abort(Executor executor) throws SQLException {}", msg1, msg2);
        wrappedCon.abort(executor);
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        if (mode == testMode) {
            return;
        }
        wrappedCon.setNetworkTimeout(executor, milliseconds);
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        if (mode == testMode) {
            return 0;
        }
        return wrappedCon.getNetworkTimeout();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        logger.warn("{} <T> T unwrap(Class<T> iface) throws SQLException {}", msg1, msg2);
        return wrappedCon.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        logger.warn("{} boolean isWrapperFor(Class<?> iface) throws SQLException {}", msg1, msg2);
        return wrappedCon.isWrapperFor(iface);
    }
}
