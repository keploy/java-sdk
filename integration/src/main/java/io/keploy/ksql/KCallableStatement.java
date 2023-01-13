package io.keploy.ksql;

import org.apache.logging.log4j.LogManager;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.util.Calendar;
import java.util.Map;

import static io.keploy.ksql.KDriver.mode;
import static io.keploy.ksql.KDriver.testMode;
import static io.keploy.ksql.KResultSet.msg1;
import static io.keploy.ksql.KResultSet.msg2;

public class KCallableStatement implements CallableStatement {

    CallableStatement wrappedCallableStatement;

    private static boolean firstTime = true;

    private static final org.apache.logging.log4j.Logger logger = LogManager.getLogger(KDatabaseMetaData.class);

    public KCallableStatement(CallableStatement prepareCall) {
        logger.debug("Inside KCallableStatement");
        wrappedCallableStatement = prepareCall;
    }

    public KCallableStatement() {

    }

    @Override
    public void registerOutParameter(int parameterIndex, int sqlType) throws SQLException {
        logger.warn("{} void registerOutParameter(int parameterIndex, int sqlType) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.registerOutParameter(parameterIndex, sqlType);
    }

    @Override
    public void registerOutParameter(int parameterIndex, int sqlType, int scale) throws SQLException {
        logger.warn("{} void registerOutParameter(int parameterIndex, int sqlType, int scale) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.registerOutParameter(parameterIndex, sqlType, scale);
    }

    @Override
    public boolean wasNull() throws SQLException {
        logger.warn("{} boolean wasNull() throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.wasNull();
    }

    @Override
    public String getString(int parameterIndex) throws SQLException {
        logger.warn("{} String getString(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getString(parameterIndex);
    }

    @Override
    public boolean getBoolean(int parameterIndex) throws SQLException {
        logger.warn("{} boolean getBoolean(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getBoolean(parameterIndex);
    }

    @Override
    public byte getByte(int parameterIndex) throws SQLException {
        logger.warn("{} byte getByte(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getByte(parameterIndex);
    }

    @Override
    public short getShort(int parameterIndex) throws SQLException {
        logger.warn("{} short getShort(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getShort(parameterIndex);
    }

    @Override
    public int getInt(int parameterIndex) throws SQLException {
        logger.warn("{} int getInt(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getShort(parameterIndex);
    }

    @Override
    public long getLong(int parameterIndex) throws SQLException {
        logger.warn("{} long getLong(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getLong(parameterIndex);
    }

    @Override
    public float getFloat(int parameterIndex) throws SQLException {
        logger.warn("{} float getFloat(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getFloat(parameterIndex);
    }

    @Override
    public double getDouble(int parameterIndex) throws SQLException {
        logger.warn("{} double getDouble(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getDouble(parameterIndex);
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(int parameterIndex, int scale) throws SQLException {
        logger.warn("{} BigDecimal getBigDecimal(int parameterIndex, int scale) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getBigDecimal(parameterIndex, scale);
    }

    @Override
    public byte[] getBytes(int parameterIndex) throws SQLException {
        logger.warn("{} byte[] getBytes(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getBytes(parameterIndex);
    }

    @Override
    public Date getDate(int parameterIndex) throws SQLException {
        logger.warn("{} Date getDate(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getDate(parameterIndex);
    }

    @Override
    public Time getTime(int parameterIndex) throws SQLException {
        logger.warn("{} Time getTime(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getTime(parameterIndex);
    }

    @Override
    public Timestamp getTimestamp(int parameterIndex) throws SQLException {
        logger.warn("{} Timestamp getTimestamp(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getTimestamp(parameterIndex);
    }

    @Override
    public Object getObject(int parameterIndex) throws SQLException {
        logger.warn("{} Object getObject(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getObject(parameterIndex);
    }

    @Override
    public BigDecimal getBigDecimal(int parameterIndex) throws SQLException {
        logger.warn("{} BigDecimal getBigDecimal(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getBigDecimal(parameterIndex);
    }

    @Override
    public Object getObject(int parameterIndex, Map<String, Class<?>> map) throws SQLException {
        logger.warn("{} Object getObject(int parameterIndex, Map<String, Class<?>> map) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getObject(parameterIndex, map);
    }

    @Override
    public Ref getRef(int parameterIndex) throws SQLException {
        logger.warn("{} Ref getRef(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getRef(parameterIndex);
    }

    @Override
    public Blob getBlob(int parameterIndex) throws SQLException {
        logger.warn("{} Blob getBlob(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getBlob(parameterIndex);
    }

    @Override
    public Clob getClob(int parameterIndex) throws SQLException {
        logger.warn("{} Clob getClob(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getClob(parameterIndex);
    }

    @Override
    public Array getArray(int parameterIndex) throws SQLException {
        logger.warn("{} Array getArray(int parameterIndex) throws SQLException {}", msg1, msg2);

        return wrappedCallableStatement.getArray(parameterIndex);
    }

    @Override
    public Date getDate(int parameterIndex, Calendar cal) throws SQLException {
        logger.warn("{} Date getDate(int parameterIndex, Calendar cal) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getDate(parameterIndex, cal);
    }

    @Override
    public Time getTime(int parameterIndex, Calendar cal) throws SQLException {
        logger.warn("{} Time getTime(int parameterIndex, Calendar cal) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getTime(parameterIndex, cal);
    }

    @Override
    public Timestamp getTimestamp(int parameterIndex, Calendar cal) throws SQLException {
        logger.warn("{} Timestamp getTimestamp(int parameterIndex, Calendar cal) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getTimestamp(parameterIndex, cal);
    }

    @Override
    public void registerOutParameter(int parameterIndex, int sqlType, String typeName) throws SQLException {
        logger.warn("{} void registerOutParameter(int parameterIndex, int sqlType, String typeName) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.registerOutParameter(parameterIndex, sqlType, typeName);
    }

    @Override
    public void registerOutParameter(String parameterName, int sqlType) throws SQLException {
        logger.warn("{} void registerOutParameter(String parameterName, int sqlType) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.registerOutParameter(parameterName, sqlType);
    }

    @Override
    public void registerOutParameter(String parameterName, int sqlType, int scale) throws SQLException {
        logger.warn("{} void registerOutParameter(String parameterName, int sqlType, int scale) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.registerOutParameter(parameterName, sqlType, scale);
    }

    @Override
    public void registerOutParameter(String parameterName, int sqlType, String typeName) throws SQLException {
        logger.warn("{} void registerOutParameter(String parameterName, int sqlType, String typeName) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.registerOutParameter(parameterName, sqlType, typeName);
    }

    @Override
    public URL getURL(int parameterIndex) throws SQLException {
        logger.warn("{} URL getURL(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getURL(parameterIndex);
    }

    @Override
    public void setURL(String parameterName, URL val) throws SQLException {
        logger.warn("{} void setURL(String parameterName, URL val) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setURL(parameterName, val);
    }

    @Override
    public void setNull(String parameterName, int sqlType) throws SQLException {
        logger.warn("{} void setNull(String parameterName, int sqlType) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setNull(parameterName, sqlType);
    }

    @Override
    public void setBoolean(String parameterName, boolean x) throws SQLException {
        logger.warn("{} void setBoolean(String parameterName, boolean x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setBoolean(parameterName, x);
    }

    @Override
    public void setByte(String parameterName, byte x) throws SQLException {
        logger.warn("{} void setByte(String parameterName, byte x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setByte(parameterName, x);
    }

    @Override
    public void setShort(String parameterName, short x) throws SQLException {
        logger.warn("{} void setShort(String parameterName, short x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setShort(parameterName, x);
    }

    @Override
    public void setInt(String parameterName, int x) throws SQLException {
        logger.warn("{} void setInt(String parameterName, int x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setInt(parameterName, x);
    }

    @Override
    public void setLong(String parameterName, long x) throws SQLException {
        logger.warn("{} void setLong(String parameterName, long x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setLong(parameterName, x);
    }

    @Override
    public void setFloat(String parameterName, float x) throws SQLException {
        logger.warn("{} void setFloat(String parameterName, float x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setFloat(parameterName, x);
    }

    @Override
    public void setDouble(String parameterName, double x) throws SQLException {
        logger.warn("{} void setDouble(String parameterName, double x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setDouble(parameterName, x);
    }

    @Override
    public void setBigDecimal(String parameterName, BigDecimal x) throws SQLException {
        logger.warn("{} void setBigDecimal(String parameterName, BigDecimal x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setBigDecimal(parameterName, x);
    }

    @Override
    public void setString(String parameterName, String x) throws SQLException {
        logger.warn("{} void setString(String parameterName, String x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setString(parameterName, x);
    }

    @Override
    public void setBytes(String parameterName, byte[] x) throws SQLException {
        logger.warn("{} void setBytes(String parameterName, byte[] x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setBytes(parameterName, x);
    }

    @Override
    public void setDate(String parameterName, Date x) throws SQLException {
        logger.warn("{} void setDate(String parameterName, Date x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setDate(parameterName, x);
    }

    @Override
    public void setTime(String parameterName, Time x) throws SQLException {
        logger.warn("{} void setTime(String parameterName, Time x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setTime(parameterName, x);
    }

    @Override
    public void setTimestamp(String parameterName, Timestamp x) throws SQLException {
        logger.warn("{} void setTimestamp(String parameterName, Timestamp x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setTimestamp(parameterName, x);
    }

    @Override
    public void setAsciiStream(String parameterName, InputStream x, int length) throws SQLException {
        logger.warn("{} void setAsciiStream(String parameterName, InputStream x, int length) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setAsciiStream(parameterName, x, length);
    }

    @Override
    public void setBinaryStream(String parameterName, InputStream x, int length) throws SQLException {
        logger.warn("{} void setBinaryStream(String parameterName, InputStream x, int length) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setBinaryStream(parameterName, x, length);
    }

    @Override
    public void setObject(String parameterName, Object x, int targetSqlType, int scale) throws SQLException {
        logger.warn("{} void setObject(String parameterName, Object x, int targetSqlType, int scale) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setObject(parameterName, x, targetSqlType, scale);
    }

    @Override
    public void setObject(String parameterName, Object x, int targetSqlType) throws SQLException {
        logger.warn("{} void setObject(String parameterName, Object x, int targetSqlType) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setObject(parameterName, x, targetSqlType);
    }

    @Override
    public void setObject(String parameterName, Object x) throws SQLException {
        logger.warn("{} void setObject(String parameterName, Object x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setObject(parameterName, x);
    }

    @Override
    public void setCharacterStream(String parameterName, Reader reader, int length) throws SQLException {
        logger.warn("{} void setCharacterStream(String parameterName, Reader reader, int length) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setCharacterStream(parameterName, reader, length);
    }

    @Override
    public void setDate(String parameterName, Date x, Calendar cal) throws SQLException {
        logger.warn("{} void setDate(String parameterName, Date x, Calendar cal) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setDate(parameterName, x, cal);
    }

    @Override
    public void setTime(String parameterName, Time x, Calendar cal) throws SQLException {
        logger.warn("{} void setTime(String parameterName, Time x, Calendar cal) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setTime(parameterName, x, cal);
    }

    @Override
    public void setTimestamp(String parameterName, Timestamp x, Calendar cal) throws SQLException {
        logger.warn("{} void setTimestamp(String parameterName, Timestamp x, Calendar cal) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setTimestamp(parameterName, x, cal);
    }

    @Override
    public void setNull(String parameterName, int sqlType, String typeName) throws SQLException {
        logger.warn("{} void setNull(String parameterName, int sqlType, String typeName) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setNull(parameterName, sqlType, typeName);
    }

    @Override
    public String getString(String parameterName) throws SQLException {
        logger.warn("{} String getString(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getString(parameterName);
    }

    @Override
    public boolean getBoolean(String parameterName) throws SQLException {
        logger.warn("{} boolean getBoolean(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getBoolean(parameterName);
    }

    @Override
    public byte getByte(String parameterName) throws SQLException {
        logger.warn("{} byte getByte(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getByte(parameterName);
    }

    @Override
    public short getShort(String parameterName) throws SQLException {
        logger.warn("{} short getShort(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getShort(parameterName);
    }

    @Override
    public int getInt(String parameterName) throws SQLException {
        logger.warn("{} int getInt(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getInt(parameterName);
    }

    @Override
    public long getLong(String parameterName) throws SQLException {
        logger.warn("{} long getLong(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getLong(parameterName);
    }

    @Override
    public float getFloat(String parameterName) throws SQLException {
        logger.warn("{} float getFloat(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getFloat(parameterName);
    }

    @Override
    public double getDouble(String parameterName) throws SQLException {
        logger.warn("{} double getDouble(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getDouble(parameterName);
    }

    @Override
    public byte[] getBytes(String parameterName) throws SQLException {
        logger.warn("{} byte[] getBytes(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getBytes(parameterName);
    }

    @Override
    public Date getDate(String parameterName) throws SQLException {
        logger.warn("{} Date getDate(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getDate(parameterName);
    }

    @Override
    public Time getTime(String parameterName) throws SQLException {
        logger.warn("{} Time getTime(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getTime(parameterName);
    }

    @Override
    public Timestamp getTimestamp(String parameterName) throws SQLException {
        logger.warn("{} Timestamp getTimestamp(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getTimestamp(parameterName);
    }

    @Override
    public Object getObject(String parameterName) throws SQLException {
        logger.warn("{} Object getObject(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getObject(parameterName);
    }

    @Override
    public BigDecimal getBigDecimal(String parameterName) throws SQLException {
        logger.warn("{} BigDecimal getBigDecimal(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getBigDecimal(parameterName);
    }

    @Override
    public Object getObject(String parameterName, Map<String, Class<?>> map) throws SQLException {
        logger.warn("{} Object getObject(String parameterName, Map<String, Class<?>> map) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getObject(parameterName, map);
    }

    @Override
    public Ref getRef(String parameterName) throws SQLException {
        logger.warn("{} Ref getRef(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getRef(parameterName);
    }

    @Override
    public Blob getBlob(String parameterName) throws SQLException {
        logger.warn("{} Blob getBlob(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getBlob(parameterName);
    }

    @Override
    public Clob getClob(String parameterName) throws SQLException {
        logger.warn("{} Clob getClob(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getClob(parameterName);
    }

    @Override
    public Array getArray(String parameterName) throws SQLException {
        logger.warn("{} Array getArray(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getArray(parameterName);
    }

    @Override
    public Date getDate(String parameterName, Calendar cal) throws SQLException {
        logger.warn("{} Date getDate(String parameterName, Calendar cal) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getDate(parameterName, cal);
    }

    @Override
    public Time getTime(String parameterName, Calendar cal) throws SQLException {
        logger.warn("{} Time getTime(String parameterName, Calendar cal) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getTime(parameterName, cal);
    }

    @Override
    public Timestamp getTimestamp(String parameterName, Calendar cal) throws SQLException {
        logger.warn("{} Timestamp getTimestamp(String parameterName, Calendar cal) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getTimestamp(parameterName, cal);
    }

    @Override
    public URL getURL(String parameterName) throws SQLException {
        logger.warn("{} URL getURL(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getURL(parameterName);
    }

    @Override
    public RowId getRowId(int parameterIndex) throws SQLException {
        logger.warn("{} RowId getRowId(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getRowId(parameterIndex);
    }

    @Override
    public RowId getRowId(String parameterName) throws SQLException {
        logger.warn("{} RowId getRowId(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getRowId(parameterName);
    }

    @Override
    public void setRowId(String parameterName, RowId x) throws SQLException {
        logger.warn("{} void setRowId(String parameterName, RowId x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setRowId(parameterName, x);
    }

    @Override
    public void setNString(String parameterName, String value) throws SQLException {
        logger.warn("{} void setNString(String parameterName, String value) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setNString(parameterName, value);
    }

    @Override
    public void setNCharacterStream(String parameterName, Reader value, long length) throws SQLException {
        logger.warn("{} void setNCharacterStream(String parameterName, Reader value, long length) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setNCharacterStream(parameterName, value, length);
    }

    @Override
    public void setNClob(String parameterName, NClob value) throws SQLException {
        logger.warn("{} void setNClob(String parameterName, NClob value) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setNClob(parameterName, value);
    }

    @Override
    public void setClob(String parameterName, Reader reader, long length) throws SQLException {
        logger.warn("{} void setClob(String parameterName, Reader reader, long length) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setClob(parameterName, reader, length);
    }

    @Override
    public void setBlob(String parameterName, InputStream inputStream, long length) throws SQLException {
        logger.warn("{} void setBlob(String parameterName, InputStream inputStream, long length) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setBlob(parameterName, inputStream, length);
    }

    @Override
    public void setNClob(String parameterName, Reader reader, long length) throws SQLException {
        logger.warn("{} void setNClob(String parameterName, Reader reader, long length) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setNClob(parameterName, reader, length);
    }

    @Override
    public NClob getNClob(int parameterIndex) throws SQLException {
        logger.warn("{} NClob getNClob(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getNClob(parameterIndex);
    }

    @Override
    public NClob getNClob(String parameterName) throws SQLException {
        logger.warn("{} NClob getNClob(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getNClob(parameterName);
    }

    @Override
    public void setSQLXML(String parameterName, SQLXML xmlObject) throws SQLException {
        logger.warn("{} void setSQLXML(String parameterName, SQLXML xmlObject) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setSQLXML(parameterName, xmlObject);
    }

    @Override
    public SQLXML getSQLXML(int parameterIndex) throws SQLException {
        logger.warn("{} SQLXML getSQLXML(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getSQLXML(parameterIndex);
    }

    @Override
    public SQLXML getSQLXML(String parameterName) throws SQLException {
        logger.warn("{} SQLXML getSQLXML(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getSQLXML(parameterName);
    }

    @Override
    public String getNString(int parameterIndex) throws SQLException {
        logger.warn("{} String getNString(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getNString(parameterIndex);
    }

    @Override
    public String getNString(String parameterName) throws SQLException {
        logger.warn("{} String getNString(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getNString(parameterName);
    }

    @Override
    public Reader getNCharacterStream(int parameterIndex) throws SQLException {
        logger.warn("{} Reader getNCharacterStream(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getNCharacterStream(parameterIndex);
    }

    @Override
    public Reader getNCharacterStream(String parameterName) throws SQLException {
        logger.warn("{} Reader getNCharacterStream(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getNCharacterStream(parameterName);
    }

    @Override
    public Reader getCharacterStream(int parameterIndex) throws SQLException {
        logger.warn("{} Reader getCharacterStream(int parameterIndex) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getCharacterStream(parameterIndex);
    }

    @Override
    public Reader getCharacterStream(String parameterName) throws SQLException {
        logger.warn("{} Reader getCharacterStream(String parameterName) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getCharacterStream(parameterName);
    }

    @Override
    public void setBlob(String parameterName, Blob x) throws SQLException {
        logger.warn("{} void setBlob(String parameterName, Blob x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setBlob(parameterName, x);
    }

    @Override
    public void setClob(String parameterName, Clob x) throws SQLException {
        logger.warn("{} void setClob(String parameterName, Clob x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setClob(parameterName, x);
    }

    @Override
    public void setAsciiStream(String parameterName, InputStream x, long length) throws SQLException {
        logger.warn("{} void setAsciiStream(String parameterName, InputStream x, long length) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setAsciiStream(parameterName, x, length);
    }

    @Override
    public void setBinaryStream(String parameterName, InputStream x, long length) throws SQLException {
        logger.warn("{} void setBinaryStream(String parameterName, InputStream x, long length) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setBinaryStream(parameterName, x, length);
    }

    @Override
    public void setCharacterStream(String parameterName, Reader reader, long length) throws SQLException {
        logger.warn("{} void setCharacterStream(String parameterName, Reader reader, long length) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setCharacterStream(parameterName, reader, length);
    }

    @Override
    public void setAsciiStream(String parameterName, InputStream x) throws SQLException {
        logger.warn("{} void setAsciiStream(String parameterName, InputStream x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setAsciiStream(parameterName, x);
    }

    @Override
    public void setBinaryStream(String parameterName, InputStream x) throws SQLException {
        logger.warn("{} void setBinaryStream(String parameterName, InputStream x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setBinaryStream(parameterName, x);
    }

    @Override
    public void setCharacterStream(String parameterName, Reader reader) throws SQLException {
        logger.warn("{} void setCharacterStream(String parameterName, Reader reader) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setCharacterStream(parameterName, reader);
    }

    @Override
    public void setNCharacterStream(String parameterName, Reader value) throws SQLException {
        logger.warn("{} void setNCharacterStream(String parameterName, Reader value) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setNCharacterStream(parameterName, value);
    }

    @Override
    public void setClob(String parameterName, Reader reader) throws SQLException {
        logger.warn("{} void setClob(String parameterName, Reader reader) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setClob(parameterName, reader);
    }

    @Override
    public void setBlob(String parameterName, InputStream inputStream) throws SQLException {
        logger.warn("{} void setBlob(String parameterName, InputStream inputStream) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setBlob(parameterName, inputStream);
    }

    @Override
    public void setNClob(String parameterName, Reader reader) throws SQLException {
        logger.warn("{} void setNClob(String parameterName, Reader reader) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setNClob(parameterName, reader);
    }

    @Override
    public <T> T getObject(int parameterIndex, Class<T> type) throws SQLException {
        logger.warn("{} <T> T getObject(int parameterIndex, Class<T> type) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getObject(parameterIndex, type);
    }

    @Override
    public <T> T getObject(String parameterName, Class<T> type) throws SQLException {
        logger.warn("{} <T> T getObject(String parameterName, Class<T> type) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getObject(parameterName, type);
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        if (mode == testMode) {
            return new KResultSet();
        }
        return new KResultSet(wrappedCallableStatement.executeQuery());
    }

    @Override
    public int executeUpdate() throws SQLException {
        if (mode == testMode) {
            return 1;
        }
        return wrappedCallableStatement.executeUpdate();
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        logger.warn("{} void setNull(int parameterIndex, int sqlType) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setNull(parameterIndex, sqlType);
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        logger.warn("{} void setBoolean(int parameterIndex, boolean x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setBoolean(parameterIndex, x);
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        logger.warn("{} void setByte(int parameterIndex, byte x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setByte(parameterIndex, x);
    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        logger.warn("{} void setShort(int parameterIndex, short x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setShort(parameterIndex, x);
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        logger.warn("{} void setInt(int parameterIndex, int x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setInt(parameterIndex, x);
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        logger.warn("{} void setLong(int parameterIndex, long x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setLong(parameterIndex, x);
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        logger.warn("{} void setFloat(int parameterIndex, float x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setFloat(parameterIndex, x);
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        logger.warn("{} void setDouble(int parameterIndex, double x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setDouble(parameterIndex, x);
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        logger.warn("{} void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setBigDecimal(parameterIndex, x);
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        logger.warn("{} void setString(int parameterIndex, String x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setString(parameterIndex, x);
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        logger.warn("{} void setBytes(int parameterIndex, byte[] x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setBytes(parameterIndex, x);
    }

    @Override
    public void setDate(int parameterIndex, Date x) throws SQLException {
        logger.warn("{} void setDate(int parameterIndex, Date x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setDate(parameterIndex, x);
    }

    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException {
        logger.warn("{} void setTime(int parameterIndex, Time x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setTime(parameterIndex, x);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        logger.warn("{} void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setTimestamp(parameterIndex, x);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        logger.warn("{} void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setAsciiStream(parameterIndex, x, length);
    }

    @Override
    @Deprecated
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
        logger.warn("{} void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setUnicodeStream(parameterIndex, x, length);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        logger.warn("{} void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setBinaryStream(parameterIndex, x, length);
    }

    @Override
    public void clearParameters() throws SQLException {
        logger.warn("{} void clearParameters() throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.clearParameters();
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        logger.warn("{} void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setObject(parameterIndex, x, targetSqlType);
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        logger.warn("{} void setObject(int parameterIndex, Object x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setObject(parameterIndex, x);
    }

    @Override
    public boolean execute() throws SQLException {
        logger.warn("{} boolean execute() throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.execute();
    }

    @Override
    public void addBatch() throws SQLException {
        logger.warn("{} void addBatch() throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.addBatch();
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        logger.warn("{} void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setCharacterStream(parameterIndex, reader, length);
    }

    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {
        logger.warn("{} void setRef(int parameterIndex, Ref x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setRef(parameterIndex, x);
    }

    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        logger.warn("{} void setBlob(int parameterIndex, Blob x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setBlob(parameterIndex, x);
    }

    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {
        logger.warn("{} void setClob(int parameterIndex, Clob x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setClob(parameterIndex, x);
    }

    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException {
        logger.warn("{} void setArray(int parameterIndex, Array x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setArray(parameterIndex, x);
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        logger.warn("{} ResultSetMetaData getMetaData() throws SQLException {}", msg1, msg2);
        return new KResultSetMetaData(wrappedCallableStatement.getMetaData());
    }

    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        logger.warn("{} void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setDate(parameterIndex, x, cal);
    }

    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        logger.warn("{} void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setTime(parameterIndex, x, cal);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        logger.warn("{} void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setTimestamp(parameterIndex, x, cal);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        logger.warn("{} void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setNull(parameterIndex, sqlType, typeName);
    }

    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {
        logger.warn("{} void setURL(int parameterIndex, URL x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setURL(parameterIndex, x);
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        logger.warn("{} ParameterMetaData getParameterMetaData() throws SQLException {}", msg1, msg2);
        return new KParameterMetaData(wrappedCallableStatement.getParameterMetaData());
    }

    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        logger.warn("{} void setRowId(int parameterIndex, RowId x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setRowId(parameterIndex, x);
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        logger.warn("{} void setNString(int parameterIndex, String value) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setNString(parameterIndex, value);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        logger.warn("{} void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setNCharacterStream(parameterIndex, value, length);
    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        logger.warn("{} void setNClob(int parameterIndex, NClob value) throws SQLException {}", msg1, msg2);

    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        logger.warn("{} void setClob(int parameterIndex, Reader reader, long length) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setClob(parameterIndex, reader, length);
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        logger.warn("{} void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setBlob(parameterIndex, inputStream, length);
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        logger.warn("{} void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setNClob(parameterIndex, reader, length);
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        logger.warn("{} void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setSQLXML(parameterIndex, xmlObject);
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        logger.warn("{} void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setObject(parameterIndex, x, targetSqlType, scaleOrLength);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        logger.warn("{} void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setAsciiStream(parameterIndex, x, length);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
        logger.warn("{} void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setBinaryStream(parameterIndex, x, length);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        logger.warn("{} void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setCharacterStream(parameterIndex, reader, length);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        logger.warn("{} void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setAsciiStream(parameterIndex, x);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        logger.warn("{} void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setBinaryStream(parameterIndex, x);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        logger.warn("{} void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setCharacterStream(parameterIndex, reader);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        logger.warn("{} void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setNCharacterStream(parameterIndex, value);
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        logger.warn("{} void setClob(int parameterIndex, Reader reader) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setClob(parameterIndex, reader);
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        logger.warn("{} void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setBlob(parameterIndex, inputStream);
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        logger.warn("{} void setNClob(int parameterIndex, Reader reader) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setNClob(parameterIndex, reader);
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        logger.warn("{} ResultSet executeQuery(String sql) throws SQLException {}", msg1, msg2);
        return new KResultSet(wrappedCallableStatement.executeQuery(sql));
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        logger.warn("{} int executeUpdate(String sql) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.executeUpdate(sql);
    }

    @Override
    public void close() throws SQLException {
        logger.warn("{} void close() throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.close();
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        logger.warn("{} int getMaxFieldSize() throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getMaxFieldSize();
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        logger.warn("{} void setMaxFieldSize(int max) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setMaxFieldSize(max);
    }

    @Override
    public int getMaxRows() throws SQLException {
        logger.warn("{} int getMaxRows() throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getMaxRows();
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        logger.warn("{} void setMaxRows(int max) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setMaxRows(max);
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        logger.warn("{} void setEscapeProcessing(boolean enable) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setEscapeProcessing(enable);
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        logger.warn("{} int getQueryTimeout() throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getQueryTimeout();
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        logger.warn("{} void setQueryTimeout(int seconds) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setQueryTimeout(seconds);
    }

    @Override
    public void cancel() throws SQLException {
        logger.warn("{} void cancel() throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.cancel();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        logger.warn("{} SQLWarning getWarnings() throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        logger.warn("{} void clearWarnings() throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.clearWarnings();
    }

    @Override
    public void setCursorName(String name) throws SQLException {
        logger.warn("{} void setCursorName(String name) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setCursorName(name);
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        logger.warn("{} boolean execute(String sql) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.execute(sql);
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        logger.warn("{} ResultSet getResultSet() throws SQLException {}", msg1, msg2);
        return new KResultSet(wrappedCallableStatement.getResultSet());
    }

    @Override
    public int getUpdateCount() throws SQLException {
        logger.warn("{} int getUpdateCount() throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getUpdateCount();
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        logger.warn("{} boolean getMoreResults() throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getMoreResults();
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        logger.warn("{} void setFetchDirection(int direction) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setFetchDirection(direction);
    }

    @Override
    public int getFetchDirection() throws SQLException {
        logger.warn("{} int getFetchDirection() throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getFetchDirection();
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        logger.warn("{} void setFetchSize(int rows) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setFetchDirection(rows);
    }

    @Override
    public int getFetchSize() throws SQLException {
        logger.warn("{} int getFetchSize() throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getFetchSize();
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        logger.warn("{} int getResultSetConcurrency() throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getResultSetConcurrency();
    }

    @Override
    public int getResultSetType() throws SQLException {
        logger.warn("{} int getResultSetType() throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getResultSetType();
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        logger.warn("{} void addBatch(String sql) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.addBatch(sql);
    }

    @Override
    public void clearBatch() throws SQLException {
        logger.warn("{} void clearBatch() throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.clearBatch();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        logger.warn("{} int[] executeBatch() throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.executeBatch();
    }

    @Override
    public Connection getConnection() throws SQLException {
        logger.warn("{} Connection getConnection() throws SQLException {}", msg1, msg2);
        return new KConnection(wrappedCallableStatement.getConnection());
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        logger.warn("{} boolean getMoreResults(int current) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getMoreResults(current);
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        logger.warn("{} ResultSet getGeneratedKeys() throws SQLException {}", msg1, msg2);
        return new KResultSet(wrappedCallableStatement.getGeneratedKeys());
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        logger.warn("{} int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.executeUpdate();
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        logger.warn("{} int executeUpdate(String sql, int[] columnIndexes) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.executeUpdate();
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        logger.warn("{} int executeUpdate(String sql, String[] columnNames) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.executeUpdate(sql, columnNames);
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        logger.warn("{} boolean execute(String sql, int autoGeneratedKeys) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.execute(sql, autoGeneratedKeys);
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        logger.warn("{} boolean execute(String sql, int[] columnIndexes) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.execute(sql, columnIndexes);
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        logger.warn("{} boolean execute(String sql, String[] columnNames) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.execute(sql, columnNames);
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        logger.warn("{} int getResultSetHoldability() throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.getResultSetHoldability();
    }

    @Override
    public boolean isClosed() throws SQLException {
        logger.warn("{} boolean isClosed() throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.isClosed();
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        logger.warn("{} void setPoolable(boolean poolable) throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.setPoolable(poolable);
    }

    @Override
    public boolean isPoolable() throws SQLException {
        logger.warn("{} boolean isPoolable() throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.isPoolable();
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        logger.warn("{} void closeOnCompletion() throws SQLException {}", msg1, msg2);
        wrappedCallableStatement.closeOnCompletion();
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        logger.warn("{} boolean isCloseOnCompletion() throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.isCloseOnCompletion();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        logger.warn("{} <T> T unwrap(Class<T> iface) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        logger.warn("{} boolean isWrapperFor(Class<?> iface) throws SQLException {}", msg1, msg2);
        return wrappedCallableStatement.isWrapperFor(iface);
    }
}
