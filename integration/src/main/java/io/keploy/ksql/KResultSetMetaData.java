package io.keploy.ksql;

import io.keploy.regression.Mode;
import org.apache.logging.log4j.LogManager;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashMap;

import static io.keploy.ksql.KDriver.mode;
import static io.keploy.ksql.KResultSet.msg1;
import static io.keploy.ksql.KResultSet.msg2;

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
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.isAutoIncrement(column);
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.isCaseSensitive(column);
    }

    @Override
    public boolean isSearchable(int column) throws SQLException {
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.isSearchable(column);
    }

    @Override
    public boolean isCurrency(int column) throws SQLException {
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.isCurrency(column);
    }

    @Override
    public int isNullable(int column) throws SQLException {
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.isNullable(column);
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.isSigned(column);
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.getColumnDisplaySize(column);
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.getColumnLabel(column);
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        String getColumnName = wrappedResultSetMetaData.getColumnName(column);
        return getColumnName;
    }

    @Override
    public String getSchemaName(int column) throws SQLException {
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        String getSchemaName = wrappedResultSetMetaData.getSchemaName(column);
        return getSchemaName;
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        String columnLabel = getColumnLabel(column);
        if (mode == Mode.ModeType.MODE_TEST) {
            int i = Integer.parseInt(PrecisionDict.get(columnLabel));
            logger.debug(i + "is the precision for " + PrecisionDict.get(columnLabel));
        }
        Integer getPrecision = wrappedResultSetMetaData.getPrecision(column);
        PrecisionDict.put(columnLabel, String.valueOf(getPrecision));
        return getPrecision;
    }

    @Override
    public int getScale(int column) throws SQLException {
        String columnLabel = getColumnLabel(column);
        if (mode == Mode.ModeType.MODE_TEST) {
            int i = Integer.parseInt(ScaleDict.get(columnLabel));
            logger.debug(i + "is the scale for " + ScaleDict.get(columnLabel));
            return i;
        }
        Integer getPrecision = wrappedResultSetMetaData.getScale(column);
        ScaleDict.put(columnLabel, String.valueOf(getPrecision));
        return getPrecision;
    }

    @Override
    public String getTableName(int column) throws SQLException {
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.getTableName(column);
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.getCatalogName(column);
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.getColumnType(column);
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.getColumnTypeName(column);
    }

    @Override
    public boolean isReadOnly(int column) throws SQLException {
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.isReadOnly(column);
    }

    @Override
    public boolean isWritable(int column) throws SQLException {
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.isWritable(column);
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.isDefinitelyWritable(column);
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.getColumnClassName(column);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.isWrapperFor(iface);
    }
}
