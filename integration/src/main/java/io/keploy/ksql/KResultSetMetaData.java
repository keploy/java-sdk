package com.example.ksql;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;

public class KResultSetMetaData implements java.sql.ResultSetMetaData {
    ResultSetMetaData wrappedResultSetMetaData;

    public KResultSetMetaData(ResultSetMetaData getMetaData) {
        wrappedResultSetMetaData = getMetaData;
    }

    @Override
    public int getColumnCount() throws SQLException {
        return wrappedResultSetMetaData.getColumnCount();
    }

    @Override
    public boolean isAutoIncrement(int column) throws SQLException {
        return wrappedResultSetMetaData.isAutoIncrement(column);
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        return wrappedResultSetMetaData.isCaseSensitive(column);
    }

    @Override
    public boolean isSearchable(int column) throws SQLException {
        return wrappedResultSetMetaData.isSearchable(column);
    }

    @Override
    public boolean isCurrency(int column) throws SQLException {
        return wrappedResultSetMetaData.isCurrency(column);
    }

    @Override
    public int isNullable(int column) throws SQLException {
        return wrappedResultSetMetaData.isNullable(column);
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        return wrappedResultSetMetaData.isSigned(column);
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        return wrappedResultSetMetaData.getColumnDisplaySize(column);
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        return wrappedResultSetMetaData.getColumnLabel(column);
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        System.out.println("inside getColumnMethod");
        String getColumnName = wrappedResultSetMetaData.getColumnName(column);
        return getColumnName;
    }

    @Override
    public String getSchemaName(int column) throws SQLException {
        String getSchemaName = wrappedResultSetMetaData.getSchemaName(column);
        return getSchemaName;
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        int getPrecision = wrappedResultSetMetaData.getPrecision(column);
        return getPrecision;
    }

    @Override
    public int getScale(int column) throws SQLException {
        return wrappedResultSetMetaData.getScale(column);
    }

    @Override
    public String getTableName(int column) throws SQLException {
        return wrappedResultSetMetaData.getTableName(column);
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        return wrappedResultSetMetaData.getCatalogName(column);
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        return wrappedResultSetMetaData.getColumnType(column);
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        return wrappedResultSetMetaData.getColumnTypeName(column);
    }

    @Override
    public boolean isReadOnly(int column) throws SQLException {
        return wrappedResultSetMetaData.isReadOnly(column);
    }

    @Override
    public boolean isWritable(int column) throws SQLException {
        return wrappedResultSetMetaData.isWritable(column);
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        return wrappedResultSetMetaData.isDefinitelyWritable(column);
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        return wrappedResultSetMetaData.getColumnClassName(column);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return wrappedResultSetMetaData.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return wrappedResultSetMetaData.isWrapperFor(iface);
    }
}
