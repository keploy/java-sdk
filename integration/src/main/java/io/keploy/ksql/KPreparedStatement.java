package io.keploy.ksql;

import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import org.apache.logging.log4j.LogManager;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.util.Calendar;

import static io.keploy.ksql.KDriver.*;

public class KPreparedStatement implements PreparedStatement {
    PreparedStatement wrappedPreparedStatement;
    Kcontext kctx = null;

    private static final org.apache.logging.log4j.Logger logger = LogManager.getLogger(KPreparedStatement.class);

    public KPreparedStatement(PreparedStatement pst) {
        kctx = Context.getCtx();
        wrappedPreparedStatement = pst;
    }

    public KPreparedStatement() {

    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedPreparedStatement.executeQuery();
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
                rs = wrappedPreparedStatement.executeQuery();
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

        return new KResultSet(rs);
    }

    @Override
    public int executeUpdate() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedPreparedStatement.executeUpdate();
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
                rs = wrappedPreparedStatement.executeUpdate();
                KResultSet.commited = rs;
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedPreparedStatement.setNull(parameterIndex, sqlType);
            }
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedPreparedStatement.setNull(parameterIndex, sqlType);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedPreparedStatement.setBoolean(parameterIndex, x);
            }
            return;
        }

//        Mode.ModeType mode = kctx.getMode();
        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedPreparedStatement.setBoolean(parameterIndex, x);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedPreparedStatement.setShort(parameterIndex, x);
            }
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedPreparedStatement.setShort(parameterIndex, x);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedPreparedStatement.setShort(parameterIndex, x);
            }
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedPreparedStatement.setShort(parameterIndex, x);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedPreparedStatement.setInt(parameterIndex, x);
            }
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedPreparedStatement.setInt(parameterIndex, x);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedPreparedStatement.setLong(parameterIndex, x);
            }
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedPreparedStatement.setLong(parameterIndex, x);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedPreparedStatement.setFloat(parameterIndex, x);
            }
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedPreparedStatement.setFloat(parameterIndex, x);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedPreparedStatement.setDouble(parameterIndex, x);
            }
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedPreparedStatement.setDouble(parameterIndex, x);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedPreparedStatement.setBigDecimal(parameterIndex, x);
            }
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedPreparedStatement.setBigDecimal(parameterIndex, x);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedPreparedStatement.setString(parameterIndex, x);
            }
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedPreparedStatement.setString(parameterIndex, x);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedPreparedStatement.setBytes(parameterIndex, x);
            }
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedPreparedStatement.setBytes(parameterIndex, x);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public void setDate(int parameterIndex, Date x) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedPreparedStatement.setDate(parameterIndex, x);
            }
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedPreparedStatement.setDate(parameterIndex, x);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedPreparedStatement.setTime(parameterIndex, x);
            }
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedPreparedStatement.setTime(parameterIndex, x);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedPreparedStatement.setTimestamp(parameterIndex, x);
            }
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedPreparedStatement.setTimestamp(parameterIndex, x);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedPreparedStatement.setAsciiStream(parameterIndex, x, length);
            }
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedPreparedStatement.setAsciiStream(parameterIndex, x, length);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    @Deprecated
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
        if (kctx == null) {
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedPreparedStatement.setUnicodeStream(parameterIndex, x, length);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        if (kctx == null) {
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedPreparedStatement.setBinaryStream(parameterIndex, x, length);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public void clearParameters() throws SQLException {
        if (kctx == null) {
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedPreparedStatement.clearWarnings();
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        if (kctx == null) {
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedPreparedStatement.setObject(parameterIndex, x, targetSqlType);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        if (kctx == null) {
            return;
        }
//        Mode.ModeType mode = kctx.getMode();

        switch (mode) {
            case MODE_TEST:
                // don't run
                break;
            case MODE_RECORD:
                wrappedPreparedStatement.setObject(parameterIndex, x);
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public boolean execute() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedPreparedStatement.execute();
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
                rs = wrappedPreparedStatement.execute();

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;
    }

    @Override
    public void addBatch() throws SQLException {
        wrappedPreparedStatement.addBatch();
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        wrappedPreparedStatement.setCharacterStream(parameterIndex, reader, length);
    }

    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {
        wrappedPreparedStatement.setRef(parameterIndex, x);
    }

    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        wrappedPreparedStatement.setBlob(parameterIndex, x);
    }

    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {
        wrappedPreparedStatement.setClob(parameterIndex, x);
    }

    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException {
        wrappedPreparedStatement.setArray(parameterIndex, x);
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        ResultSetMetaData getMetaData = wrappedPreparedStatement.getMetaData();
        return getMetaData;
    }

    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        wrappedPreparedStatement.setDate(parameterIndex, x, cal);
    }

    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        wrappedPreparedStatement.setTime(parameterIndex, x, cal);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        wrappedPreparedStatement.setTimestamp(parameterIndex, x, cal);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        wrappedPreparedStatement.setNull(parameterIndex, sqlType);
    }

    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {
        wrappedPreparedStatement.setURL(parameterIndex, x);
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        return new KParameterMetaData(wrappedPreparedStatement.getParameterMetaData());
    }

    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        wrappedPreparedStatement.setRowId(parameterIndex, x);
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        wrappedPreparedStatement.setNString(parameterIndex, value);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        wrappedPreparedStatement.setNCharacterStream(parameterIndex, value, length);
    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        wrappedPreparedStatement.setNClob(parameterIndex, value);
    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        wrappedPreparedStatement.setClob(parameterIndex, reader, length);
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        wrappedPreparedStatement.setBlob(parameterIndex, inputStream, length);
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        wrappedPreparedStatement.setNClob(parameterIndex, reader, length);
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        wrappedPreparedStatement.setSQLXML(parameterIndex, xmlObject);
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        wrappedPreparedStatement.setObject(parameterIndex, x, targetSqlType, scaleOrLength);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        wrappedPreparedStatement.setAsciiStream(parameterIndex, x, length);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
        wrappedPreparedStatement.setBinaryStream(parameterIndex, x, length);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        wrappedPreparedStatement.setCharacterStream(parameterIndex, reader, length);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        wrappedPreparedStatement.setAsciiStream(parameterIndex, x);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        wrappedPreparedStatement.setBinaryStream(parameterIndex, x);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        wrappedPreparedStatement.setCharacterStream(parameterIndex, reader);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        wrappedPreparedStatement.setNCharacterStream(parameterIndex, value);
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        wrappedPreparedStatement.setClob(parameterIndex, reader);
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        wrappedPreparedStatement.setBlob(parameterIndex, inputStream);
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        wrappedPreparedStatement.setNClob(parameterIndex, reader);
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedPreparedStatement.executeQuery(sql);
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
                rs = wrappedPreparedStatement.executeQuery(sql);
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
                return wrappedPreparedStatement.executeUpdate(sql);
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
                rs = wrappedPreparedStatement.executeUpdate(sql);
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
                wrappedPreparedStatement.close();

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        return wrappedPreparedStatement.getMaxFieldSize();
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        wrappedPreparedStatement.setMaxFieldSize(max);
    }

    @Override
    public int getMaxRows() throws SQLException {
        return wrappedPreparedStatement.getMaxRows();
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        wrappedPreparedStatement.setMaxFieldSize(max);
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        wrappedPreparedStatement.setEscapeProcessing(enable);
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        return wrappedPreparedStatement.getQueryTimeout();
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        wrappedPreparedStatement.setQueryTimeout(seconds);
    }

    @Override
    public void cancel() throws SQLException {
        wrappedPreparedStatement.cancel();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return wrappedPreparedStatement.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        wrappedPreparedStatement.clearWarnings();
    }

    @Override
    public void setCursorName(String name) throws SQLException {
        wrappedPreparedStatement.setCursorName(name);
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedPreparedStatement.execute(sql);
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
                rs = wrappedPreparedStatement.execute(sql);

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        ResultSet getResultSet = wrappedPreparedStatement.getResultSet();
        ResultSet krs = new KResultSet(getResultSet);
        return krs;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        return wrappedPreparedStatement.getUpdateCount();
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        return wrappedPreparedStatement.getMoreResults();

    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        wrappedPreparedStatement.setFetchDirection(direction);
    }

    @Override
    public int getFetchDirection() throws SQLException {
        return wrappedPreparedStatement.getFetchDirection();
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        wrappedPreparedStatement.setFetchSize(rows);
    }

    @Override
    public int getFetchSize() throws SQLException {
        return wrappedPreparedStatement.getFetchSize();
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        return wrappedPreparedStatement.getResultSetConcurrency();
    }

    @Override
    public int getResultSetType() throws SQLException {
        return wrappedPreparedStatement.getResultSetType();
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        wrappedPreparedStatement.addBatch();
    }

    @Override
    public void clearBatch() throws SQLException {
        wrappedPreparedStatement.clearBatch();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        return wrappedPreparedStatement.executeBatch();
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection c = wrappedPreparedStatement.getConnection();
        return new KConnection(c);
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        return wrappedPreparedStatement.getMoreResults(current);
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedPreparedStatement.getGeneratedKeys();
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
                rs = wrappedPreparedStatement.getGeneratedKeys();
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
                return wrappedPreparedStatement.executeUpdate();
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
                rs = wrappedPreparedStatement.executeUpdate();
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
                return wrappedPreparedStatement.executeUpdate(sql, columnIndexes);
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
                rs = wrappedPreparedStatement.executeUpdate(sql, columnIndexes);
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
                return wrappedPreparedStatement.executeUpdate(sql, columnNames);
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
                rs = wrappedPreparedStatement.executeUpdate(sql, columnNames);
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
                return wrappedPreparedStatement.execute(sql, autoGeneratedKeys);
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
                rs = wrappedPreparedStatement.execute(sql, autoGeneratedKeys);

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
                return wrappedPreparedStatement.execute(sql, columnIndexes);
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
                rs = wrappedPreparedStatement.execute(sql, columnIndexes);

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
                return wrappedPreparedStatement.execute(sql, columnNames);
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
                rs = wrappedPreparedStatement.execute(sql, columnNames);

                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }
        return rs;
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return wrappedPreparedStatement.getResultSetHoldability();
    }

    @Override
    public boolean isClosed() throws SQLException {
        return wrappedPreparedStatement.isClosed();
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        wrappedPreparedStatement.setPoolable(poolable);
    }

    @Override
    public boolean isPoolable() throws SQLException {
        return wrappedPreparedStatement.isPoolable();
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        wrappedPreparedStatement.closeOnCompletion();
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        if (mode == testMode) {
            return true;
        }
        return wrappedPreparedStatement.isCloseOnCompletion();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return wrappedPreparedStatement.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return wrappedPreparedStatement.isWrapperFor(iface);
    }
}
