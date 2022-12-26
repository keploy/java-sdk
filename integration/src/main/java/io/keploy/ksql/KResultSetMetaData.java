package io.keploy.ksql;

import io.keploy.regression.Mode;
import org.apache.logging.log4j.LogManager;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashMap;

import static io.keploy.ksql.KDriver.mode;

public class KResultSetMetaData implements ResultSetMetaData {
    ResultSetMetaData wrappedResultSetMetaData;

    public static HashMap<String, String> PrecisionDict;
    public static HashMap<String, String> ScaleDict;

    private static final org.apache.logging.log4j.Logger logger = LogManager.getLogger(KResultSetMetaData.class);

    public KResultSetMetaData(ResultSetMetaData getMetaData) {
        wrappedResultSetMetaData = getMetaData;
    }

    public KResultSetMetaData() {
    }

    @Override
    public int getColumnCount() throws SQLException {
        if (mode == Mode.ModeType.MODE_TEST) {
            return Integer.parseInt(KResultSet.meta.get("getColumnCount"));
        }
        int gc = wrappedResultSetMetaData.getColumnCount();
        KResultSet.meta.put("getColumnCount", Integer.toString(gc));
        return gc;
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
        String columnLabel = getColumnLabel(column);
        if (mode == Mode.ModeType.MODE_TEST) {
            return Integer.parseInt(PrecisionDict.get(columnLabel));
        }
        Integer getPrecision = wrappedResultSetMetaData.getPrecision(column);
        PrecisionDict.put(columnLabel, String.valueOf(getPrecision));
        return getPrecision;
    }

    @Override
    public int getScale(int column) throws SQLException {
        String columnLabel = getColumnLabel(column);
        if (mode == Mode.ModeType.MODE_TEST) {
            return Integer.parseInt(ScaleDict.get(columnLabel));
        }
        Integer getPrecision = wrappedResultSetMetaData.getScale(column);
        ScaleDict.put(columnLabel, String.valueOf(getPrecision));
        return getPrecision;
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
