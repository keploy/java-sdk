package io.keploy.ksql;

import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import org.apache.logging.log4j.LogManager;

import java.sql.*;

import static io.keploy.ksql.KDriver.*;
import static io.keploy.ksql.KResultSet.msg1;
import static io.keploy.ksql.KResultSet.msg2;

public class KStatement implements Statement {
    public Statement wrappedStatement;
    private static final org.apache.logging.log4j.Logger logger = LogManager.getLogger(KStatement.class);
    public KStatement(Statement st) {
        logger.debug("Inside KStatement !");
        wrappedStatement = st;
    }

    public KStatement() {

    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.executeQuery(sql);
            }
            ResultSet resultSet = new KResultSet();//Mockito.mock(ResultSet.class);
            return new KResultSet(resultSet);
        }
//        Mode.ModeType mode = kctx.getMode();
        ResultSet rs = new KResultSet();
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.executeQuery(sql);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

        return new KResultSet(rs);
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.executeUpdate(sql);
            }
            return 0;
        }
//        Mode.ModeType mode = kctx.getMode();

        int rs = 1;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.executeUpdate(sql);
                KResultSet.commited = rs;
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;
    }

    @Override
    public void close() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            return;
        }
//        Mode.ModeType mode = kctx.getMode();


        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
//    new KResultSet(0);
                wrappedStatement.close();

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.getMaxFieldSize();
            }
            return 0;
        }
//        Mode.ModeType mode = kctx.getMode();

        int rs = 1;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.getMaxFieldSize();

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedStatement.setMaxFieldSize(max);
                return;
            }
            return;
        }
//        Mode.ModeType mode = kctx.getMode();


        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
//    new KResultSet(0);
                wrappedStatement.setMaxFieldSize(max);

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }


    }

    @Override
    public int getMaxRows() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.getMaxRows();
            }
            return 0;
        }
//        Mode.ModeType mode = kctx.getMode();

        int rs = 1;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.getMaxRows();

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        logger.warn("{} void setMaxRows(int max) throws SQLException {}", msg1, msg2);
        wrappedStatement.setMaxRows(max);
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        logger.warn("{} void setEscapeProcessing(boolean enable) throws SQLException {}", msg1, msg2);
        wrappedStatement.setEscapeProcessing(enable);
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.getQueryTimeout();
            }
            return 0;
        }
//        Mode.ModeType mode = kctx.getMode();

        int rs = 1;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.getQueryTimeout();

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedStatement.setQueryTimeout(seconds);
                return;
            }
            return;
        }

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedStatement.setQueryTimeout(seconds);

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }


    }

    @Override
    public void cancel() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedStatement.cancel();
                return;
            }
            return;
        }

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedStatement.cancel();

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return wrappedStatement.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedStatement.clearWarnings();
                return;
            }
            return;
        }

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedStatement.clearWarnings();

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public void setCursorName(String name) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedStatement.setCursorName(name);
                return;
            }
            return;
        }

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedStatement.setCursorName(name);

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public boolean execute(String sql) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.execute(sql);
            }
            return false;
        }
//        Mode.ModeType mode = kctx.getMode();

        boolean rs = true;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.execute(sql);

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.getResultSet();
            }
            ResultSet resultSet = new KResultSet();//Mockito.mock(ResultSet.class);
            return new KResultSet(resultSet);
        }
//        Mode.ModeType mode = kctx.getMode();
        ResultSet rs = new KResultSet();
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.getResultSet();
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

        return new KResultSet(rs);

    }

    @Override
    public int getUpdateCount() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.getUpdateCount();
            }
            return 0;
        }
//        Mode.ModeType mode = kctx.getMode();

        int rs = 1;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.getUpdateCount();

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.getMoreResults();
            }
            return false;
        }
//        Mode.ModeType mode = kctx.getMode();

        boolean rs = true;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.getMoreResults();

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;

    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedStatement.setFetchDirection(direction);
                return;
            }
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedStatement.setFetchDirection(direction);

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public int getFetchDirection() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.getFetchDirection();
            }
            return 0;
        }
//        Mode.ModeType mode = kctx.getMode();

        int rs = 1;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.getFetchDirection();

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedStatement.setFetchSize(rows);
                return;
            }
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedStatement.setFetchSize(rows);

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
    }

    @Override
    public int getFetchSize() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.getFetchSize();
            }
            return 0;
        }
//        Mode.ModeType mode = kctx.getMode();

        int rs = 1;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.getFetchSize();
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.getResultSetConcurrency();
            }
            return 0;
        }
//        Mode.ModeType mode = kctx.getMode();

        int rs = 1;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.getResultSetConcurrency();
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;

    }

    @Override
    public int getResultSetType() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.getResultSetType();
            }
            return 0;
        }
//        Mode.ModeType mode = kctx.getMode();

        int rs = 1;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.getResultSetType();
                KResultSet.commited = rs;
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedStatement.addBatch(sql);
                return;
            }
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedStatement.addBatch(sql);

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
    }

    @Override
    public void clearBatch() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedStatement.clearBatch();
                return;
            }
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedStatement.clearBatch();

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
    }

    @Override
    public int[] executeBatch() throws SQLException {
        if (mode == testMode) {
            return null;
        }
        return wrappedStatement.executeBatch();
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (mode == testMode) {
            return new KConnection();
        }
        return new KConnection(wrappedStatement.getConnection());
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.getMoreResults(current);
            }
            return false;
        }
//        Mode.ModeType mode = kctx.getMode();

        boolean rs = true;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.getMoreResults(current);

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;

    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.getGeneratedKeys();
            }
            return new KResultSet(false);
        }
//        Mode.ModeType mode = kctx.getMode();

        ResultSet rs = new KResultSet();
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.getGeneratedKeys();
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return new KResultSet(rs);

    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.executeUpdate(sql, autoGeneratedKeys);
            }
            return 0;
        }
//        Mode.ModeType mode = kctx.getMode();

        int rs = 1;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.executeUpdate(sql, autoGeneratedKeys);
                KResultSet.commited = rs;
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.executeUpdate(sql, columnIndexes);
            }
            return 0;
        }
//        Mode.ModeType mode = kctx.getMode();

        int rs = 1;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.executeUpdate(sql, columnIndexes);
                KResultSet.commited = rs;
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.executeUpdate(sql, columnNames);
            }
            return 0;
        }
//        Mode.ModeType mode = kctx.getMode();

        int rs = 1;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.executeUpdate(sql, columnNames);
                KResultSet.commited = rs;
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.execute(sql, autoGeneratedKeys);
            }
            return false;
        }
//        Mode.ModeType mode = kctx.getMode();

        boolean rs = true;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.execute(sql, autoGeneratedKeys);

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.execute(sql, columnIndexes);
            }
            return false;
        }
//        Mode.ModeType mode = kctx.getMode();

        boolean rs = true;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.execute(sql, columnIndexes);

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.execute(sql, columnNames);
            }
            return false;
        }
//        Mode.ModeType mode = kctx.getMode();

        boolean rs = true;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.execute(sql, columnNames);

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.getResultSetHoldability();
            }
            return 0;
        }
//        Mode.ModeType mode = kctx.getMode();

        int rs = 0;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.getResultSetHoldability();

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;

    }

    @Override
    public boolean isClosed() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.isClosed();
            }
            return false;
        }
//        Mode.ModeType mode = kctx.getMode();

        boolean rs = true;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.isClosed();

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;

    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedStatement.setPoolable(poolable);
            }
            return;
        }
//        Mode.ModeType mode = kctx.getMode();


        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedStatement.setPoolable(poolable);

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public boolean isPoolable() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.isPoolable();
            }
            return false;
        }
//        Mode.ModeType mode = kctx.getMode();

        boolean rs = true;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.isPoolable();

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;

    }

    @Override
    public void closeOnCompletion() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedStatement.closeOnCompletion();
                return;
            }
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedStatement.closeOnCompletion();
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedStatement.isCloseOnCompletion();
            }
            return false;
        }

        boolean rs = true;
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                rs = wrappedStatement.isCloseOnCompletion();
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        logger.warn("{} <T> T unwrap(Class<T> iface) throws SQLException {}", msg1, msg2);
        return wrappedStatement.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        logger.warn("{} boolean isWrapperFor(Class<?> iface) throws SQLException {}", msg1, msg2);

        return wrappedStatement.isWrapperFor(iface);
    }
}
