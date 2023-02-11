package io.keploy.ksql;

import io.keploy.regression.Mode;
import org.apache.logging.log4j.LogManager;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashMap;

import static io.keploy.ksql.KDriver.mode;
import static io.keploy.ksql.KDriver.testMode;
import static io.keploy.ksql.KResultSet.*;

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
            logger.debug("Stored value of getColumnCount is {} in mock metaData : {} ", meta.get("getColumnCount"), meta);
            int gs = 1;
            if (KResultSet.meta.get("getColumnCount") != null) {
                gs = Integer.parseInt(KResultSet.meta.get("getColumnCount"));
            }
            return gs;
        }
        int gc = wrappedResultSetMetaData.getColumnCount();
        logger.debug("getColumnCount value in KResultSetMetaData {}", gc);
        KResultSet.meta.put("getColumnCount", Integer.toString(gc));
        return gc;
    }

    @Override
    public boolean isAutoIncrement(int column) throws SQLException {
        logger.warn("{} boolean isAutoIncrement(int column) throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.isAutoIncrement(column);
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        logger.warn("{} boolean isCaseSensitive(int column) throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.isCaseSensitive(column);
    }

    @Override
    public boolean isSearchable(int column) throws SQLException {
        logger.warn("{} boolean isSearchable(int column) throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.isSearchable(column);
    }

    @Override
    public boolean isCurrency(int column) throws SQLException {
        if (mode == testMode) {
            return false;
        }
        return wrappedResultSetMetaData.isCurrency(column);
    }

    @Override
    public int isNullable(int column) throws SQLException {

        if (mode == Mode.ModeType.MODE_TEST) {
            logger.debug("Stored value of isNullable is {} in mock metaData : {} ", meta.get("isNullable"), meta);
            int gs = 1;
            if (KResultSet.meta.get("isNullable") != null) {
                gs = Integer.parseInt(KResultSet.meta.get("isNullable"));
            }
            return gs;
        }
        int gc = wrappedResultSetMetaData.isNullable(column);
        logger.debug("isNullable value in KResultSetMetaData {}", gc);
        KResultSet.meta.put("isNullable", Integer.toString(gc));
        return gc;
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        if (mode == testMode) {
            return true;
        }
        return wrappedResultSetMetaData.isSigned(column);
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        if (mode == Mode.ModeType.MODE_TEST) {
            logger.debug("Stored value of getColumnDisplaySize is {} in mock metaData : {} ", meta.get("getColumnDisplaySize"), meta);
            int gs = 1;
            if (KResultSet.meta.get("getColumnDisplaySize") != null) {
                gs = Integer.parseInt(KResultSet.meta.get("getColumnDisplaySize"));
            }
            return gs;
        }
        int gc = wrappedResultSetMetaData.getColumnDisplaySize(column);
        logger.debug("getColumnDisplaySize value in KResultSetMetaData {}", gc);
        KResultSet.meta.put("getColumnDisplaySize", Integer.toString(gc));
        return gc;
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
//        logger.warn("{} String getColumnLabel(int column) throws SQLException {}", msg1, msg2);
        if (mode == Mode.ModeType.MODE_TEST) {
            logger.debug("Stored value of getColumnLabel is {} in mock metaData : {} ", meta.get("getColumnLabel"), meta);
            String gcl = "KEPLOY_LABEL";
            if (KResultSet.meta.get("getColumnLabel") != null) {
                gcl = meta.get("getColumnLabel");
            }
            return gcl;
        }
        String gcl = wrappedResultSetMetaData.getColumnLabel(column);
        logger.debug("getColumnLabel value in KResultSetMetaData {}", gcl);
        KResultSet.meta.put("getColumnLabel", gcl);

        return gcl;
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        //        logger.warn("{} String getColumnLabel(int column) throws SQLException {}", msg1, msg2);
        if (mode == Mode.ModeType.MODE_TEST) {
            logger.debug("Stored value of getColumnName is {} in mock metaData : {} ", meta.get("getColumnName"), meta);
            String gcl = "KEPLOY_getColumnName";
            if (KResultSet.meta.get("getColumnName") != null) {
                gcl = meta.get("getColumnName");
            }
            return gcl;
        }
        String gcl = wrappedResultSetMetaData.getColumnName(column);
        logger.debug("getColumnName value in KResultSetMetaData {}", gcl);
        KResultSet.meta.put("getColumnName", gcl);

        return gcl;
    }

    @Override
    public String getSchemaName(int column) throws SQLException {
        //        logger.warn("{} String getColumnLabel(int column) throws SQLException {}", msg1, msg2);
        if (mode == Mode.ModeType.MODE_TEST) {
            logger.debug("Stored value of getSchemaName is {} in mock metaData : {} ", meta.get("getSchemaName"), meta);
            String gcl = "KEPLOY_getSchemaName";
            if (KResultSet.meta.get("getSchemaName") != null) {
                gcl = meta.get("getSchemaName");
            }
            return gcl;
        }
        String gcl = wrappedResultSetMetaData.getSchemaName(column);
        logger.debug("getSchemaName value in KResultSetMetaData {}", gcl);
        KResultSet.meta.put("getSchemaName", gcl);

        return gcl;
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        String columnLabel = getColumnLabel(column);
        if (mode == Mode.ModeType.MODE_TEST) {
            if (PrecisionDict.get(columnLabel) == null) {
                return 0;
            }
            int i = Integer.parseInt(PrecisionDict.get(columnLabel));
            logger.debug(i + "is the precision for " + PrecisionDict.get(columnLabel));
            return i;
        }
        Integer getPrecision = wrappedResultSetMetaData.getPrecision(column);
        PrecisionDict.put(columnLabel, String.valueOf(getPrecision));
        return getPrecision;
    }

    @Override
    public int getScale(int column) throws SQLException {
        String columnLabel = getColumnLabel(column);
        if (mode == Mode.ModeType.MODE_TEST) {
            if (ScaleDict.get(columnLabel) == null) {
                return 0;
            }
            int i = Integer.parseInt(ScaleDict.get(columnLabel));
            logger.debug(i + "is the scale for " + ScaleDict.get(columnLabel));
            return i;
        }
        Integer getScale = wrappedResultSetMetaData.getScale(column);
        ScaleDict.put(columnLabel, String.valueOf(getScale));
        return getScale;
    }

    @Override
    public String getTableName(int column) throws SQLException {
        //        logger.warn("{} String getColumnLabel(int column) throws SQLException {}", msg1, msg2);
        if (mode == Mode.ModeType.MODE_TEST) {
            logger.debug("Stored value of getTableName is {} in mock metaData : {} ", meta.get("getTableName"), meta);
            String gcl = "KEPLOY_getTableName";
            if (KResultSet.meta.get("getTableName") != null) {
                gcl = meta.get("getTableName");
            }
            return gcl;
        }
        String gcl = wrappedResultSetMetaData.getTableName(column);
        logger.debug("getTableName value in KResultSetMetaData {}", gcl);
        KResultSet.meta.put("getTableName", gcl);

        return gcl;
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        //        logger.warn("{} String getColumnLabel(int column) throws SQLException {}", msg1, msg2);
        if (mode == Mode.ModeType.MODE_TEST) {
            logger.debug("Stored value of getCatalogName is {} in mock metaData : {} ", meta.get("getCatalogName"), meta);
            String gcl = "KEPLOY_getCatalogName";
            if (KResultSet.meta.get("getCatalogName") != null) {
                gcl = meta.get("getCatalogName");
            }
            return gcl;
        }
        String gcl = wrappedResultSetMetaData.getCatalogName(column);
        logger.debug("getCatalogName value in KResultSetMetaData {}", gcl);
        KResultSet.meta.put("getCatalogName", gcl);

        return gcl;
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        if (mode == Mode.ModeType.MODE_TEST) {
            logger.debug("Stored value of getColumnType is {} in mock metaData : {} ", meta.get("getColumnType"), meta);
            int gs = 1;
            if (KResultSet.meta.get("getColumnType") != null) {
                gs = Integer.parseInt(KResultSet.meta.get("getColumnType"));
            }
            return gs;
        }
        int gc = wrappedResultSetMetaData.getColumnType(column);
        logger.debug("getColumnType value in KResultSetMetaData {}", gc);
        KResultSet.meta.put("getColumnType", Integer.toString(gc));
        return gc;
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        //        logger.warn("{} String getColumnLabel(int column) throws SQLException {}", msg1, msg2);
        if (mode == Mode.ModeType.MODE_TEST) {
            logger.debug("Stored value of getColumnTypeName is {} in mock metaData : {} ", meta.get("getColumnTypeName"), meta);
            String gcl = "KEPLOY_getColumnTypeName";
            if (KResultSet.meta.get("getColumnTypeName") != null) {
                gcl = meta.get("getColumnTypeName");
            }
            return gcl;
        }
        String gcl = wrappedResultSetMetaData.getColumnTypeName(column);
        logger.debug("getColumnTypeName value in KResultSetMetaData {}", gcl);
        KResultSet.meta.put("getColumnTypeName", gcl);

        return gcl;
    }

    @Override
    public boolean isReadOnly(int column) throws SQLException {
        if (mode == testMode) {
            return true;
        }
        return wrappedResultSetMetaData.isReadOnly(column);
    }

    @Override
    public boolean isWritable(int column) throws SQLException {
        if (mode == testMode) {
            return false;
        }
        return wrappedResultSetMetaData.isWritable(column);
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        if (mode == testMode) {
            return false;
        }
        return wrappedResultSetMetaData.isDefinitelyWritable(column);
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        if (mode == Mode.ModeType.MODE_TEST) {
            logger.debug("Stored value of getColumnClassName is {} in mock metaData : {} ", meta.get("getColumnClassName"), meta);
            String gcl = "KEPLOY_getColumnClassName";
            if (KResultSet.meta.get("getColumnClassName") != null) {
                gcl = meta.get("getColumnClassName");
            }
            return gcl;
        }
        String gcl = wrappedResultSetMetaData.getColumnClassName(column);
        logger.debug("getColumnClassName value in KResultSetMetaData {}", gcl);
        KResultSet.meta.put("getColumnClassName", gcl);

        return gcl;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        logger.warn("{} <T> T unwrap(Class<T> iface) throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        logger.warn("{} boolean isWrapperFor(Class<?> iface) throws SQLException {}", msg1, msg2);
        return wrappedResultSetMetaData.isWrapperFor(iface);
    }
}
