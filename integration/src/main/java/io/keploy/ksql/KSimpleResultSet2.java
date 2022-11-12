package io.keploy.ksql;

import org.h2.tools.SimpleResultSet;
import org.h2.tools.SimpleRowSource;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.util.Calendar;
import java.util.Map;

public class KSimpleResultSet2 extends SimpleResultSet {
    ResultSet wrappedResultSet = null;

    public KSimpleResultSet2() {
        super();
    }

    public KSimpleResultSet2(ResultSet rs) {
        wrappedResultSet = rs;
    }

    public KSimpleResultSet2(SimpleRowSource source) {
        super(source);
    }

    @Override
    public void addColumn(String name, int sqlType, int precision, int scale) {
        super.addColumn(name, sqlType, precision, scale);
    }

    @Override
    public void addColumn(String name, int sqlType, String sqlTypeName, int precision, int scale) {
        super.addColumn(name, sqlType, sqlTypeName, precision, scale);
    }

    @Override
    public void addRow(Object... row) {
        super.addRow(row);
    }

    @Override
    public int getConcurrency() {
        return super.getConcurrency();
    }

    @Override
    public int getFetchDirection() {
        return super.getFetchDirection();
    }

    @Override
    public int getFetchSize() {
        return super.getFetchSize();
    }

    @Override
    public int getRow() {
        return super.getRow();
    }

    @Override
    public int getType() {
        return super.getType();
    }

    @Override
    public void close() {
        try {
            wrappedResultSet.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        super.close();
    }

    @Override
    public boolean next() throws SQLException {
        wrappedResultSet.next();
        return super.next();
    }

    @Override
    public void beforeFirst() throws SQLException {
        super.beforeFirst();
    }

    @Override
    public boolean wasNull() {
        return super.wasNull();
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        return super.findColumn(columnLabel);
    }

    @Override
    public ResultSetMetaData getMetaData() {
        return super.getMetaData();
    }

    @Override
    public SQLWarning getWarnings() {
        return super.getWarnings();
    }

    @Override
    public Statement getStatement() {
        return super.getStatement();
    }

    @Override
    public void clearWarnings() {
        super.clearWarnings();
    }

    @Override
    public Array getArray(int columnIndex) throws SQLException {
        return super.getArray(columnIndex);
    }

    @Override
    public Array getArray(String columnLabel) throws SQLException {
        return super.getArray(columnLabel);
    }

    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        return super.getAsciiStream(columnIndex);
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        return super.getAsciiStream(columnLabel);
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        return super.getBigDecimal(columnIndex);
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return super.getBigDecimal(columnLabel);
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        return super.getBigDecimal(columnIndex, scale);
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        return super.getBigDecimal(columnLabel, scale);
    }

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        return super.getBinaryStream(columnIndex);
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        return super.getBinaryStream(columnLabel);
    }

    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        return super.getBlob(columnIndex);
    }

    @Override
    public Blob getBlob(String columnLabel) throws SQLException {
        return super.getBlob(columnLabel);
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        return super.getBoolean(columnIndex);
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return super.getBoolean(columnLabel);
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        return super.getByte(columnIndex);
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return super.getByte(columnLabel);
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        return super.getBytes(columnIndex);
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return super.getBytes(columnLabel);
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        return super.getCharacterStream(columnIndex);
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        return super.getCharacterStream(columnLabel);
    }

    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        return super.getClob(columnIndex);
    }

    @Override
    public Clob getClob(String columnLabel) throws SQLException {
        return super.getClob(columnLabel);
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        return super.getDate(columnIndex);
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        return super.getDate(columnLabel);
    }

    @Override
    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        return super.getDate(columnIndex, cal);
    }

    @Override
    public Date getDate(String columnLabel, Calendar cal) throws SQLException {
        return super.getDate(columnLabel, cal);
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        return super.getDouble(columnIndex);
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return super.getDouble(columnLabel);
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return super.getFloat(columnIndex);
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return super.getFloat(columnLabel);
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        return super.getInt(columnIndex);
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return super.getInt(columnLabel);
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        return super.getLong(columnIndex);
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return super.getLong(columnLabel);
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        return super.getNCharacterStream(columnIndex);
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        return super.getNCharacterStream(columnLabel);
    }

    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        return super.getNClob(columnIndex);
    }

    @Override
    public NClob getNClob(String columnLabel) throws SQLException {
        return super.getNClob(columnLabel);
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        return super.getNString(columnIndex);
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        return super.getNString(columnLabel);
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        return super.getObject(columnIndex);
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return super.getObject(columnLabel);
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        return super.getObject(columnIndex, type);
    }

    @Override
    public <T> T getObject(String columnName, Class<T> type) throws SQLException {
        return super.getObject(columnName, type);
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        return super.getObject(columnIndex, map);
    }

    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        return super.getObject(columnLabel, map);
    }

    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        return super.getRef(columnIndex);
    }

    @Override
    public Ref getRef(String columnLabel) throws SQLException {
        return super.getRef(columnLabel);
    }

    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        return super.getRowId(columnIndex);
    }

    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        return super.getRowId(columnLabel);
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        return super.getShort(columnIndex);
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return super.getShort(columnLabel);
    }

    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        return super.getSQLXML(columnIndex);
    }

    @Override
    public SQLXML getSQLXML(String columnLabel) throws SQLException {
        return super.getSQLXML(columnLabel);
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        return super.getString(columnIndex);
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        return super.getString(columnLabel);
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        return super.getTime(columnIndex);
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        return super.getTime(columnLabel);
    }

    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        return super.getTime(columnIndex, cal);
    }

    @Override
    public Time getTime(String columnLabel, Calendar cal) throws SQLException {
        return super.getTime(columnLabel, cal);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        return super.getTimestamp(columnIndex);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        return super.getTimestamp(columnLabel);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        return super.getTimestamp(columnIndex, cal);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
        return super.getTimestamp(columnLabel, cal);
    }

    @Override
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        return super.getUnicodeStream(columnIndex);
    }

    @Override
    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        return super.getUnicodeStream(columnLabel);
    }

    @Override
    public URL getURL(int columnIndex) throws SQLException {
        return super.getURL(columnIndex);
    }

    @Override
    public URL getURL(String columnLabel) throws SQLException {
        return super.getURL(columnLabel);
    }

    @Override
    public void updateArray(int columnIndex, Array x) throws SQLException {
        super.updateArray(columnIndex, x);
    }

    @Override
    public void updateArray(String columnLabel, Array x) throws SQLException {
        super.updateArray(columnLabel, x);
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
        super.updateAsciiStream(columnIndex, x);
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
        super.updateAsciiStream(columnLabel, x);
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
        super.updateAsciiStream(columnIndex, x, length);
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
        super.updateAsciiStream(columnLabel, x, length);
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
        super.updateAsciiStream(columnIndex, x, length);
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
        super.updateAsciiStream(columnLabel, x, length);
    }

    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
        super.updateBigDecimal(columnIndex, x);
    }

    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
        super.updateBigDecimal(columnLabel, x);
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
        super.updateBinaryStream(columnIndex, x);
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
        super.updateBinaryStream(columnLabel, x);
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
        super.updateBinaryStream(columnIndex, x, length);
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
        super.updateBinaryStream(columnLabel, x, length);
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
        super.updateBinaryStream(columnIndex, x, length);
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
        super.updateBinaryStream(columnLabel, x, length);
    }

    @Override
    public void updateBlob(int columnIndex, Blob x) throws SQLException {
        super.updateBlob(columnIndex, x);
    }

    @Override
    public void updateBlob(String columnLabel, Blob x) throws SQLException {
        super.updateBlob(columnLabel, x);
    }

    @Override
    public void updateBlob(int columnIndex, InputStream x) throws SQLException {
        super.updateBlob(columnIndex, x);
    }

    @Override
    public void updateBlob(String columnLabel, InputStream x) throws SQLException {
        super.updateBlob(columnLabel, x);
    }

    @Override
    public void updateBlob(int columnIndex, InputStream x, long length) throws SQLException {
        super.updateBlob(columnIndex, x, length);
    }

    @Override
    public void updateBlob(String columnLabel, InputStream x, long length) throws SQLException {
        super.updateBlob(columnLabel, x, length);
    }

    @Override
    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        super.updateBoolean(columnIndex, x);
    }

    @Override
    public void updateBoolean(String columnLabel, boolean x) throws SQLException {
        super.updateBoolean(columnLabel, x);
    }

    @Override
    public void updateByte(int columnIndex, byte x) throws SQLException {
        super.updateByte(columnIndex, x);
    }

    @Override
    public void updateByte(String columnLabel, byte x) throws SQLException {
        super.updateByte(columnLabel, x);
    }

    @Override
    public void updateBytes(int columnIndex, byte[] x) throws SQLException {
        super.updateBytes(columnIndex, x);
    }

    @Override
    public void updateBytes(String columnLabel, byte[] x) throws SQLException {
        super.updateBytes(columnLabel, x);
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
        super.updateCharacterStream(columnIndex, x);
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader x) throws SQLException {
        super.updateCharacterStream(columnLabel, x);
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
        super.updateCharacterStream(columnIndex, x, length);
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader x, int length) throws SQLException {
        super.updateCharacterStream(columnLabel, x, length);
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        super.updateCharacterStream(columnIndex, x, length);
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader x, long length) throws SQLException {
        super.updateCharacterStream(columnLabel, x, length);
    }

    @Override
    public void updateClob(int columnIndex, Clob x) throws SQLException {
        super.updateClob(columnIndex, x);
    }

    @Override
    public void updateClob(String columnLabel, Clob x) throws SQLException {
        super.updateClob(columnLabel, x);
    }

    @Override
    public void updateClob(int columnIndex, Reader x) throws SQLException {
        super.updateClob(columnIndex, x);
    }

    @Override
    public void updateClob(String columnLabel, Reader x) throws SQLException {
        super.updateClob(columnLabel, x);
    }

    @Override
    public void updateClob(int columnIndex, Reader x, long length) throws SQLException {
        super.updateClob(columnIndex, x, length);
    }

    @Override
    public void updateClob(String columnLabel, Reader x, long length) throws SQLException {
        super.updateClob(columnLabel, x, length);
    }

    @Override
    public void updateDate(int columnIndex, Date x) throws SQLException {
        super.updateDate(columnIndex, x);
    }

    @Override
    public void updateDate(String columnLabel, Date x) throws SQLException {
        super.updateDate(columnLabel, x);
    }

    @Override
    public void updateDouble(int columnIndex, double x) throws SQLException {
        super.updateDouble(columnIndex, x);
    }

    @Override
    public void updateDouble(String columnLabel, double x) throws SQLException {
        super.updateDouble(columnLabel, x);
    }

    @Override
    public void updateFloat(int columnIndex, float x) throws SQLException {
        super.updateFloat(columnIndex, x);
    }

    @Override
    public void updateFloat(String columnLabel, float x) throws SQLException {
        super.updateFloat(columnLabel, x);
    }

    @Override
    public void updateInt(int columnIndex, int x) throws SQLException {
        super.updateInt(columnIndex, x);
    }

    @Override
    public void updateInt(String columnLabel, int x) throws SQLException {
        super.updateInt(columnLabel, x);
    }

    @Override
    public void updateLong(int columnIndex, long x) throws SQLException {
        super.updateLong(columnIndex, x);
    }

    @Override
    public void updateLong(String columnLabel, long x) throws SQLException {
        super.updateLong(columnLabel, x);
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
        super.updateNCharacterStream(columnIndex, x);
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader x) throws SQLException {
        super.updateNCharacterStream(columnLabel, x);
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        super.updateNCharacterStream(columnIndex, x, length);
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader x, long length) throws SQLException {
        super.updateNCharacterStream(columnLabel, x, length);
    }

    @Override
    public void updateNClob(int columnIndex, NClob x) throws SQLException {
        super.updateNClob(columnIndex, x);
    }

    @Override
    public void updateNClob(String columnLabel, NClob x) throws SQLException {
        super.updateNClob(columnLabel, x);
    }

    @Override
    public void updateNClob(int columnIndex, Reader x) throws SQLException {
        super.updateNClob(columnIndex, x);
    }

    @Override
    public void updateNClob(String columnLabel, Reader x) throws SQLException {
        super.updateNClob(columnLabel, x);
    }

    @Override
    public void updateNClob(int columnIndex, Reader x, long length) throws SQLException {
        super.updateNClob(columnIndex, x, length);
    }

    @Override
    public void updateNClob(String columnLabel, Reader x, long length) throws SQLException {
        super.updateNClob(columnLabel, x, length);
    }

    @Override
    public void updateNString(int columnIndex, String x) throws SQLException {
        super.updateNString(columnIndex, x);
    }

    @Override
    public void updateNString(String columnLabel, String x) throws SQLException {
        super.updateNString(columnLabel, x);
    }

    @Override
    public void updateNull(int columnIndex) throws SQLException {
        super.updateNull(columnIndex);
    }

    @Override
    public void updateNull(String columnLabel) throws SQLException {
        super.updateNull(columnLabel);
    }

    @Override
    public void updateObject(int columnIndex, Object x) throws SQLException {
        super.updateObject(columnIndex, x);
    }

    @Override
    public void updateObject(String columnLabel, Object x) throws SQLException {
        super.updateObject(columnLabel, x);
    }

    @Override
    public void updateObject(int columnIndex, Object x, int scale) throws SQLException {
        super.updateObject(columnIndex, x, scale);
    }

    @Override
    public void updateObject(String columnLabel, Object x, int scale) throws SQLException {
        super.updateObject(columnLabel, x, scale);
    }

    @Override
    public void updateRef(int columnIndex, Ref x) throws SQLException {
        super.updateRef(columnIndex, x);
    }

    @Override
    public void updateRef(String columnLabel, Ref x) throws SQLException {
        super.updateRef(columnLabel, x);
    }

    @Override
    public void updateRowId(int columnIndex, RowId x) throws SQLException {
        super.updateRowId(columnIndex, x);
    }

    @Override
    public void updateRowId(String columnLabel, RowId x) throws SQLException {
        super.updateRowId(columnLabel, x);
    }

    @Override
    public void updateShort(int columnIndex, short x) throws SQLException {
        super.updateShort(columnIndex, x);
    }

    @Override
    public void updateShort(String columnLabel, short x) throws SQLException {
        super.updateShort(columnLabel, x);
    }

    @Override
    public void updateSQLXML(int columnIndex, SQLXML x) throws SQLException {
        super.updateSQLXML(columnIndex, x);
    }

    @Override
    public void updateSQLXML(String columnLabel, SQLXML x) throws SQLException {
        super.updateSQLXML(columnLabel, x);
    }

    @Override
    public void updateString(int columnIndex, String x) throws SQLException {
        super.updateString(columnIndex, x);
    }

    @Override
    public void updateString(String columnLabel, String x) throws SQLException {
        super.updateString(columnLabel, x);
    }

    @Override
    public void updateTime(int columnIndex, Time x) throws SQLException {
        super.updateTime(columnIndex, x);
    }

    @Override
    public void updateTime(String columnLabel, Time x) throws SQLException {
        super.updateTime(columnLabel, x);
    }

    @Override
    public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
        super.updateTimestamp(columnIndex, x);
    }

    @Override
    public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
        super.updateTimestamp(columnLabel, x);
    }

    @Override
    public int getColumnCount() {
        return super.getColumnCount();
    }

    @Override
    public int getColumnDisplaySize(int columnIndex) {
        return super.getColumnDisplaySize(columnIndex);
    }

    @Override
    public int getColumnType(int columnIndex) throws SQLException {
        return super.getColumnType(columnIndex);
    }

    @Override
    public int getPrecision(int columnIndex) throws SQLException {
        return super.getPrecision(columnIndex);
    }

    @Override
    public int getScale(int columnIndex) throws SQLException {
        return super.getScale(columnIndex);
    }

    @Override
    public int isNullable(int columnIndex) {
        return super.isNullable(columnIndex);
    }

    @Override
    public boolean isAutoIncrement(int columnIndex) {
        return super.isAutoIncrement(columnIndex);
    }

    @Override
    public boolean isCaseSensitive(int columnIndex) {
        return super.isCaseSensitive(columnIndex);
    }

    @Override
    public boolean isCurrency(int columnIndex) {
        return super.isCurrency(columnIndex);
    }

    @Override
    public boolean isDefinitelyWritable(int columnIndex) {
        return super.isDefinitelyWritable(columnIndex);
    }

    @Override
    public boolean isReadOnly(int columnIndex) {
        return super.isReadOnly(columnIndex);
    }

    @Override
    public boolean isSearchable(int columnIndex) {
        return super.isSearchable(columnIndex);
    }

    @Override
    public boolean isSigned(int columnIndex) {
        return super.isSigned(columnIndex);
    }

    @Override
    public boolean isWritable(int columnIndex) {
        return super.isWritable(columnIndex);
    }

    @Override
    public String getCatalogName(int columnIndex) {
        return super.getCatalogName(columnIndex);
    }

    @Override
    public String getColumnClassName(int columnIndex) throws SQLException {
        return super.getColumnClassName(columnIndex);
    }

    @Override
    public String getColumnLabel(int columnIndex) throws SQLException {
        return super.getColumnLabel(columnIndex);
    }

    @Override
    public String getColumnName(int columnIndex) throws SQLException {
        return super.getColumnName(columnIndex);
    }

    @Override
    public String getColumnTypeName(int columnIndex) throws SQLException {
        return super.getColumnTypeName(columnIndex);
    }

    @Override
    public String getSchemaName(int columnIndex) {
        return super.getSchemaName(columnIndex);
    }

    @Override
    public String getTableName(int columnIndex) {
        return super.getTableName(columnIndex);
    }

    @Override
    public void afterLast() throws SQLException {
        super.afterLast();
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        super.cancelRowUpdates();
    }

    @Override
    public void deleteRow() throws SQLException {
        super.deleteRow();
    }

    @Override
    public void insertRow() throws SQLException {
        super.insertRow();
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        super.moveToCurrentRow();
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        super.moveToInsertRow();
    }

    @Override
    public void refreshRow() throws SQLException {
        super.refreshRow();
    }

    @Override
    public void updateRow() throws SQLException {
        super.updateRow();
    }

    @Override
    public boolean first() throws SQLException {
        return super.first();
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        return super.isAfterLast();
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        return super.isBeforeFirst();
    }

    @Override
    public boolean isFirst() throws SQLException {
        return super.isFirst();
    }

    @Override
    public boolean isLast() throws SQLException {
        return super.isLast();
    }

    @Override
    public boolean last() throws SQLException {
        return super.last();
    }

    @Override
    public boolean previous() throws SQLException {
        return super.previous();
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        return super.rowDeleted();
    }

    @Override
    public boolean rowInserted() throws SQLException {
        return super.rowInserted();
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        return super.rowUpdated();
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        super.setFetchDirection(direction);
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        super.setFetchSize(rows);
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        return super.absolute(row);
    }

    @Override
    public boolean relative(int offset) throws SQLException {
        return super.relative(offset);
    }

    @Override
    public String getCursorName() throws SQLException {
        return super.getCursorName();
    }

    @Override
    public int getHoldability() {
        return super.getHoldability();
    }

    @Override
    public boolean isClosed() {
        return super.isClosed();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return super.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return super.isWrapperFor(iface);
    }

    @Override
    public void setAutoClose(boolean autoClose) {
        super.setAutoClose(autoClose);
    }

    @Override
    public boolean getAutoClose() {
        return super.getAutoClose();
    }

    @Override
    public void updateObject(int columnIndex, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException {
        super.updateObject(columnIndex, x, targetSqlType, scaleOrLength);
    }

    @Override
    public void updateObject(String columnLabel, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException {
        super.updateObject(columnLabel, x, targetSqlType, scaleOrLength);
    }

    @Override
    public void updateObject(int columnIndex, Object x, SQLType targetSqlType) throws SQLException {
        super.updateObject(columnIndex, x, targetSqlType);
    }

    @Override
    public void updateObject(String columnLabel, Object x, SQLType targetSqlType) throws SQLException {
        super.updateObject(columnLabel, x, targetSqlType);
    }
}
