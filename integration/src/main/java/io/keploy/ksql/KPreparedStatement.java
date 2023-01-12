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
import static io.keploy.ksql.KResultSet.msg1;
import static io.keploy.ksql.KResultSet.msg2;

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
        logger.warn("{} void addBatch() throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.addBatch();
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        logger.warn("{} void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setCharacterStream(parameterIndex, reader, length);
    }

    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {
        logger.warn("{} void setRef(int parameterIndex, Ref x) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setRef(parameterIndex, x);
    }

    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        logger.warn("{} void setBlob(int parameterIndex, Blob x) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setBlob(parameterIndex, x);
    }

    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {
        logger.warn("{} void setClob(int parameterIndex, Clob x) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setClob(parameterIndex, x);
    }

    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException {
        logger.warn("{} void setArray(int parameterIndex, Array x) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setArray(parameterIndex, x);
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        logger.warn("{} ResultSetMetaData getMetaData() throws SQLException {}", msg1, msg2);
        ResultSetMetaData getMetaData = wrappedPreparedStatement.getMetaData();
        return getMetaData;
    }

    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        logger.warn("{} void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setDate(parameterIndex, x, cal);
    }

    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        logger.warn("{} void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setTime(parameterIndex, x, cal);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        logger.warn("{} void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setTimestamp(parameterIndex, x, cal);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        logger.warn("{} void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setNull(parameterIndex, sqlType);
    }

    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {
        logger.warn("{} void setURL(int parameterIndex, URL x) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setURL(parameterIndex, x);
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        logger.warn("{} ParameterMetaData getParameterMetaData() throws SQLException {}", msg1, msg2);
        return new KParameterMetaData(wrappedPreparedStatement.getParameterMetaData());
    }

    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        logger.warn("{} void setRowId(int parameterIndex, RowId x) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setRowId(parameterIndex, x);
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        logger.warn("{} void setNString(int parameterIndex, String value) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setNString(parameterIndex, value);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        logger.warn("{} void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setNCharacterStream(parameterIndex, value, length);
    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        logger.warn("{} void setNClob(int parameterIndex, NClob value) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setNClob(parameterIndex, value);
    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        logger.warn("{} void setClob(int parameterIndex, Reader reader, long length) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setClob(parameterIndex, reader, length);
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        logger.warn("{} void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setBlob(parameterIndex, inputStream, length);
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        logger.warn("{} void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setNClob(parameterIndex, reader, length);
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        logger.warn("{} void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setSQLXML(parameterIndex, xmlObject);
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        logger.warn("{} void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setObject(parameterIndex, x, targetSqlType, scaleOrLength);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        logger.warn("{} void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setAsciiStream(parameterIndex, x, length);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
        logger.warn("{} void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setBinaryStream(parameterIndex, x, length);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        logger.warn("{} void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setCharacterStream(parameterIndex, reader, length);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        logger.warn("{} void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setAsciiStream(parameterIndex, x);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        logger.warn("{} void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setBinaryStream(parameterIndex, x);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        logger.warn("{} void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setCharacterStream(parameterIndex, reader);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        logger.warn("{} void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setNCharacterStream(parameterIndex, value);
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        logger.warn("{} void setClob(int parameterIndex, Reader reader) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setClob(parameterIndex, reader);
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        logger.warn("{} void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setBlob(parameterIndex, inputStream);
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        logger.warn("{} void setNClob(int parameterIndex, Reader reader) throws SQLException {}", msg1, msg2);
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
        logger.warn("{} int getMaxFieldSize() throws SQLException {}", msg1, msg2);
        return wrappedPreparedStatement.getMaxFieldSize();
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        logger.warn("{} void setMaxFieldSize(int max) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setMaxFieldSize(max);
    }

    @Override
    public int getMaxRows() throws SQLException {
       if (mode==testMode){
           return 0;
       }
        return wrappedPreparedStatement.getMaxRows();
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        logger.warn("{} void setMaxRows(int max) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setMaxFieldSize(max);
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        logger.warn("{} void setEscapeProcessing(boolean enable) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setEscapeProcessing(enable);
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        if (mode==testMode){
            return 0;
        }
        return wrappedPreparedStatement.getQueryTimeout();
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        logger.warn("{} void setQueryTimeout(int seconds) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setQueryTimeout(seconds);
    }

    @Override
    public void cancel() throws SQLException {
        logger.warn("{} void cancel() throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.cancel();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        logger.warn("{} SQLWarning getWarnings() throws SQLException {}", msg1, msg2);
        return wrappedPreparedStatement.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        logger.warn("{} void clearWarnings() throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.clearWarnings();
    }

    @Override
    public void setCursorName(String name) throws SQLException {
        logger.warn("{} void setCursorName(String name) throws SQLException {}", msg1, msg2);
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
        logger.warn("{} ResultSet getResultSet() throws SQLException {}", msg1, msg2);
        ResultSet getResultSet = wrappedPreparedStatement.getResultSet();
        ResultSet krs = new KResultSet(getResultSet);
        return krs;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        logger.warn("{} int getUpdateCount() throws SQLException {}", msg1, msg2);
        return wrappedPreparedStatement.getUpdateCount();
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        logger.warn("{} boolean getMoreResults() throws SQLException {}", msg1, msg2);
        return wrappedPreparedStatement.getMoreResults();

    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        logger.warn("{} void setFetchDirection(int direction) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setFetchDirection(direction);
    }

    @Override
    public int getFetchDirection() throws SQLException {
        logger.warn("{} int getFetchDirection() throws SQLException {}", msg1, msg2);
        return wrappedPreparedStatement.getFetchDirection();
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        logger.warn("{} void setFetchSize(int rows) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setFetchSize(rows);
    }

    @Override
    public int getFetchSize() throws SQLException {
        logger.warn("{} int getFetchSize() throws SQLException {}", msg1, msg2);
        return wrappedPreparedStatement.getFetchSize();
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        logger.warn("{} int getResultSetConcurrency() throws SQLException {}", msg1, msg2);
        return wrappedPreparedStatement.getResultSetConcurrency();
    }

    @Override
    public int getResultSetType() throws SQLException {
        logger.warn("{} int getResultSetType() throws SQLException {}", msg1, msg2);
        return wrappedPreparedStatement.getResultSetType();
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        logger.warn("{} void addBatch(String sql) throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.addBatch();
    }

    @Override
    public void clearBatch() throws SQLException {
        logger.warn("{} void clearBatch() throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.clearBatch();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        logger.warn("{} int[] executeBatch() throws SQLException {}", msg1, msg2);
        return wrappedPreparedStatement.executeBatch();
    }

    @Override
    public Connection getConnection() throws SQLException {
        logger.warn("{} Connection getConnection() throws SQLException {}", msg1, msg2);
        Connection c = wrappedPreparedStatement.getConnection();
        return new KConnection(c);
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        logger.warn("{} boolean getMoreResults(int current) throws SQLException {}", msg1, msg2);
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
        logger.warn("{} int getResultSetHoldability() throws SQLException {}", msg1, msg2);
        return wrappedPreparedStatement.getResultSetHoldability();
    }

    @Override
    public boolean isClosed() throws SQLException {
        logger.warn("{} boolean isClosed() throws SQLException {}", msg1, msg2);
        return wrappedPreparedStatement.isClosed();
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        logger.warn("{} void setPoolable(boolean poolable throws SQLException {}", msg1, msg2);
        wrappedPreparedStatement.setPoolable(poolable);
    }

    @Override
    public boolean isPoolable() throws SQLException {
        logger.warn("{} boolean isPoolable() throws SQLException {}", msg1, msg2);
        return wrappedPreparedStatement.isPoolable();
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        logger.warn("{} void closeOnCompletion() throws SQLException {}", msg1, msg2);
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
        logger.warn("{} <T> T unwrap(Class<T> iface) throws SQLException {}", msg1, msg2);
        return wrappedPreparedStatement.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        logger.warn("{} isWrapperFor(Class<?> iface) throws SQLException {}", msg1, msg2);
        return wrappedPreparedStatement.isWrapperFor(iface);
    }
}
