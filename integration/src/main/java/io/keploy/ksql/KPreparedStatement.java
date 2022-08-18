package com.example.ksql;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.util.Calendar;

public class KPreparedStatement implements java.sql.PreparedStatement {
 PreparedStatement wrappedPreparedStatement;

 public KPreparedStatement(PreparedStatement pst) {
  wrappedPreparedStatement = pst;
 }

 @Override
 public ResultSet executeQuery() throws SQLException {

  ResultSet rs = wrappedPreparedStatement.executeQuery();
  ResultSet krs = new KResultSet(rs);
  return krs;
 }

 @Override
 public int executeUpdate() throws SQLException {
  int k = wrappedPreparedStatement.executeUpdate();
  return k;
 }

 @Override
 public void setNull(int parameterIndex, int sqlType) throws SQLException {
  wrappedPreparedStatement.setNull(parameterIndex, sqlType);
 }

 @Override
 public void setBoolean(int parameterIndex, boolean x) throws SQLException {
  wrappedPreparedStatement.setBoolean(parameterIndex, x);
 }

 @Override
 public void setByte(int parameterIndex, byte x) throws SQLException {
  wrappedPreparedStatement.setByte(parameterIndex, x);
 }

 @Override
 public void setShort(int parameterIndex, short x) throws SQLException {
  wrappedPreparedStatement.setShort(parameterIndex, x);
 }

 @Override
 public void setInt(int parameterIndex, int x) throws SQLException {
  wrappedPreparedStatement.setInt(parameterIndex, x);
 }

 @Override
 public void setLong(int parameterIndex, long x) throws SQLException {
  wrappedPreparedStatement.setLong(parameterIndex, x);
 }

 @Override
 public void setFloat(int parameterIndex, float x) throws SQLException {
  wrappedPreparedStatement.setFloat(parameterIndex, x);
 }

 @Override
 public void setDouble(int parameterIndex, double x) throws SQLException {
  wrappedPreparedStatement.setDouble(parameterIndex, x);
 }

 @Override
 public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
  wrappedPreparedStatement.setBigDecimal(parameterIndex, x);
 }

 @Override
 public void setString(int parameterIndex, String x) throws SQLException {
  wrappedPreparedStatement.setString(parameterIndex, x);
 }

 @Override
 public void setBytes(int parameterIndex, byte[] x) throws SQLException {
  wrappedPreparedStatement.setBytes(parameterIndex, x);
 }

 @Override
 public void setDate(int parameterIndex, Date x) throws SQLException {
  wrappedPreparedStatement.setDate(parameterIndex, x);
 }

 @Override
 public void setTime(int parameterIndex, Time x) throws SQLException {
  wrappedPreparedStatement.setTime(parameterIndex, x);
 }

 @Override
 public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
  wrappedPreparedStatement.setTimestamp(parameterIndex, x);
 }

 @Override
 public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
  wrappedPreparedStatement.setAsciiStream(parameterIndex, x, length);
 }

 @Override
 public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
//deprecated ***
//  wrappedPreparedStatement.setUnicodeStream();
 }

 @Override
 public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
  wrappedPreparedStatement.setBinaryStream(parameterIndex, x, length);
 }

 @Override
 public void clearParameters() throws SQLException {
  wrappedPreparedStatement.clearWarnings();
 }

 @Override
 public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
  wrappedPreparedStatement.setObject(parameterIndex, x, targetSqlType);
 }

 @Override
 public void setObject(int parameterIndex, Object x) throws SQLException {
  wrappedPreparedStatement.setObject(parameterIndex, x);
 }

 @Override
 public boolean execute() throws SQLException {
  System.out.println("Executed ");
  return wrappedPreparedStatement.execute();
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
  System.out.println("second null");
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
  System.out.println("second Execute Query !! in prepared Statement ");
  ResultSet rs = wrappedPreparedStatement.executeQuery();
  ResultSet krs = new KResultSet(rs);
  return krs;
 }

 @Override
 public int executeUpdate(String sql) throws SQLException {
  System.out.println("second Execute Update !! in prepared Statement ");
  int k = wrappedPreparedStatement.executeUpdate();
  return k;
 }

 @Override
 public void close() throws SQLException {
  wrappedPreparedStatement.close();
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
  return wrappedPreparedStatement.execute();
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
  System.out.println("Inside Prepared statement !!");
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
  System.out.println("------ PS ----------");
  wrappedPreparedStatement.addBatch();
 }

 @Override
 public void clearBatch() throws SQLException {
  wrappedPreparedStatement.clearBatch();
 }

 @Override
 public int[] executeBatch() throws SQLException {
  //  return new int[0];
  return wrappedPreparedStatement.executeBatch();
 }

 @Override
 public Connection getConnection() throws SQLException {
  return new KConnection(wrappedPreparedStatement.getConnection());
 }

 @Override
 public boolean getMoreResults(int current) throws SQLException {
  return wrappedPreparedStatement.getMoreResults(current);
 }

 @Override
 public ResultSet getGeneratedKeys() throws SQLException {
  ResultSet rs = wrappedPreparedStatement.getGeneratedKeys();
  return new KResultSet(rs);
 }

 @Override
 public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
  return wrappedPreparedStatement.executeUpdate(sql, autoGeneratedKeys);
 }

 @Override
 public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
  return wrappedPreparedStatement.executeUpdate(sql, columnIndexes);
 }

 @Override
 public int executeUpdate(String sql, String[] columnNames) throws SQLException {
  return wrappedPreparedStatement.executeUpdate(sql, columnNames);
 }

 @Override
 public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
  return wrappedPreparedStatement.execute(sql, autoGeneratedKeys);
 }

 @Override
 public boolean execute(String sql, int[] columnIndexes) throws SQLException {
  return wrappedPreparedStatement.execute(sql, columnIndexes);
 }

 @Override
 public boolean execute(String sql, String[] columnNames) throws SQLException {
  return wrappedPreparedStatement.execute(sql, columnNames);
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
