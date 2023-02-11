package io.keploy.ksql;

import com.google.protobuf.InvalidProtocolBufferException;
import io.keploy.grpc.stubs.Service;
import io.keploy.regression.Mode;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import io.keploy.utils.ProcessSQL;
import org.apache.logging.log4j.LogManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Date;
import java.sql.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import static io.keploy.ksql.KDriver.*;
import static io.keploy.ksql.KResultSetMetaData.PrecisionDict;
import static io.keploy.ksql.KResultSetMetaData.ScaleDict;

public class KResultSet implements ResultSet {
    ResultSet wrappedResultSet;

    static HashMap<String, String> meta = new HashMap<>();
    private List<Service.SqlCol> sqlColList;

    private Set<Service.SqlCol> colExists;

    // extracted rows in test
    private Map<String, String> TestRow = new HashMap<>();

    public List<Map<String, String>> preTable = new ArrayList<>();

    List<String> cols = new ArrayList<>();

    List<String> tableRows = new ArrayList<>();

    static final String msg1 = "Method";
    static final String msg2 = "not supported yet , If you see such warning Please create an issue on Keploy ";

    private Map<String, String> RowRecord = new HashMap<>();

    private static final org.apache.logging.log4j.Logger logger = LogManager.getLogger(KResultSet.class);

    private static final String CROSS = new String(Character.toChars(0x274C));
    private Service.Table TableData = null;
    boolean select = false;
    boolean wasNull = true;

    private int index = 0;

    private String currentLabel = "";

    public static int commited = 0;

    public KResultSet(ResultSet rs) {
        if (Objects.equals(DriverName, "org.h2.Driver")) {
            logger.debug("starting test connection for H2 ");
            mode = testMode;
        }
        sqlColList = new ArrayList<>();
        PrecisionDict = new HashMap<>();
        ScaleDict = new HashMap<>();
        colExists = new HashSet<>();

        preTable = new ArrayList<>();
        RowRecord = new HashMap<>();
        cols = new ArrayList<>();
        tableRows = new ArrayList<>();
        KResultSet.meta.clear();
        wrappedResultSet = rs;
    }

    public KResultSet(boolean rs) {

    }

    public KResultSet() {
        if (Objects.equals(DriverName, "org.h2.Driver")) {
            logger.info("starting test connection for H2 ");
            mode = testMode;
        }
        sqlColList = new ArrayList<>();

        colExists = new HashSet<>();

        preTable = new ArrayList<>();
        RowRecord = new HashMap<>();
        cols = new ArrayList<>();
        tableRows = new ArrayList<>();

        Kcontext kctx = Context.getCtx();
        KResultSet.meta.clear();
        if (kctx != null) {
            wrappedResultSet = new KResultSet(true);
        }

    }

    private void addSqlColToList(String colName, String colType) {
        final Service.SqlCol sqlCol = Service.SqlCol.newBuilder().setName(colName).setType(colType).build();
        final boolean exist = colExists.contains(sqlCol);

        if (!exist) {
            sqlColList.add(sqlCol);
            colExists.add(sqlCol);
        }
    }

    private void addRows() {
        // RowRecord not null
        if (RowRecord.size() != 0) {
            preTable.add(RowRecord);
        }
        // store row data in map and insert it in the list of maps
        RowRecord = new HashMap<>();
    }

    // Used in test mode for extracting table from mocks
    void extractTable(int cnt) {
        if (cnt == 0) {
            Kcontext kctx = Context.getCtx();
            if (kctx.getMock().size() <= 0) {
                logger.info(CROSS + " Cannot extract tables during test because mocks are unavailable ! \n");
            } else {
                List<Service.Mock> mock = kctx.getMock();
                if (mock.size() > 0 && mock.get(0).getKind().equals("SQL") && mock.get(0).getSpec().getMetadataMap().size() > 0) {
                    meta = ProcessSQL.convertMap(mock.get(0).getSpec().getMetadataMap());
                }

                Service.Table testTable = null;

                try {
                    testTable = ProcessSQL.ProcessDep(null, null, 0);
                } catch (InvalidProtocolBufferException var6) {
                    logger.info(CROSS + " Unable to extract tables during test \n" + var6);
                }

                this.TableData = testTable;
                if (this.TableData != null) {
                    this.GetPreAndScale();
                }
            }
        }
    }

    // Used in test mode for extracting single row in the form of string from mocks
    private boolean extractRows() {
        if (index == 0)
            extractTable(index);
        if (TableData == null) {
            return false;
        }
        List<String> rows = TableData.getRowsList();

        if (index == rows.size()) {
            return false;
        }
        String s = rows.get(index);
        StringBuilder row = new StringBuilder(s);
        for (int i = 0; i < row.length(); i++) {
            if (row.charAt(i) == '`') {
                row.deleteCharAt(i);
            }
        }
        String[] split = row.substring(1, row.length() - 1).split("\\|");
        TestRow.clear();
        for (int i = 0; i < split.length; i++) {
            Service.SqlCol col = TableData.getCols(i);
            if (!Objects.equals(split[i], "NA")) {
                TestRow.put(col.getName(), split[i]);
            }
        }
        logger.debug("ROW-DATA:" + TestRow);
        return true;
    }

    @Override
    public boolean next() {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                try {
                    return wrappedResultSet.next();
                } catch (SQLException e) {
                    logger.error(CROSS + " Unable to move sql cursor during next" + e);
                }
            }
            return false;
        }
        // Mode.ModeType mode = kctx.getMode();

        boolean hasNext = false;
        switch (mode) {
            case MODE_TEST:
                hasNext = extractRows();
                index++;
                break;
            case MODE_RECORD:
                try {
                    hasNext = wrappedResultSet.next();
                } catch (SQLException e) {
                    logger.error(CROSS + " Unable to move sql cursor during next" + e);
                }
                addRows();
                if (!hasNext) {
                    select = true;
                    Service.Table.Builder tableBuilder = Service.Table.newBuilder();
                    sqlColList = SetPreAndScale();
                    logger.debug("sqlColList : " + sqlColList);
                    tableBuilder.addAllCols(sqlColList);
                    cols = ProcessSQL.toColumnList(sqlColList);
                    logger.debug("cols : " + cols);
                    tableRows = ProcessSQL.toRowList(preTable, cols);
                    tableBuilder.addAllRows(tableRows);
                    Service.Table table = tableBuilder.build();
                    logger.debug("table : " + table);
                    try {
                        meta.put("method", "next()");
                        meta.put("SQL-Query", KConnection.MyQuery);
                        ProcessSQL.ProcessDep(meta, table, commited);
                        commited = 0;
                    } catch (InvalidProtocolBufferException e) {
                        throw new RuntimeException(e);
                    }
                }
                break;
            default:
                System.out.println("integrations: Not in a valid sdk mode");
        }

        return hasNext;
    }

    @Override
    public void close() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                wrappedResultSet.close();
            }
            return;
        }
        if (mode == testMode) {
            return;
        }

        // This portion will be executed only at the time of record when context is not
        // null
        if (!select) {
            addRows();
            Service.Table.Builder tableBuilder = Service.Table.newBuilder();
            sqlColList = SetPreAndScale();
            logger.debug("sqlColList : " + sqlColList);
            tableBuilder.addAllCols(sqlColList);
            cols = ProcessSQL.toColumnList(sqlColList);
            logger.debug("cols : " + cols);
            tableRows = ProcessSQL.toRowList(preTable, cols);
            tableBuilder.addAllRows(tableRows);
            Service.Table table = tableBuilder.build();
            logger.debug("table : " + table);
            try {
                meta.put("method", "close()");
                ProcessSQL.ProcessDep(meta, table, commited);
                commited = 0;
                meta.put("SQL-Query", KConnection.MyQuery);
            } catch (InvalidProtocolBufferException e) {
                throw new RuntimeException(e);
            }
        }
        wrappedResultSet.close();
    }

    @Override
    public boolean wasNull() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedResultSet.wasNull();
            }
            return false;
        }
        if (mode == Mode.ModeType.MODE_TEST) {
            logger.debug("WasNull value is " + wasNull);
            return wasNull;
        }
        wasNull = wrappedResultSet.wasNull();
        if (wasNull) {
            if (RowRecord.containsKey(currentLabel) && !RowRecord.get(currentLabel).equals("Null")) {
                RowRecord.put(currentLabel, "Null");
                currentLabel = "";
            }
        }
        logger.debug("WasNull value is " + wasNull);
        return wasNull;
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        Kcontext kctx = Context.getCtx();
        // Mode.ModeType mode = kctx.getMode();
        String columnlabel = String.valueOf(columnIndex);
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            String gs = TestRow.get(columnlabel);
            if (TestRow.get(columnlabel) == null || Objects.equals(gs, "Null")) {
                wasNull = true;
                return null;
            }
            return gs;
        }
        String res = null;
        currentLabel = columnlabel;
        String gs = wrappedResultSet.getString(columnIndex);
        res = gs;
        if (isNullValue(gs)) {
            res = "Null";
        }
        RowRecord.put(columnlabel, res);
        addSqlColToList(String.valueOf(columnIndex), "String");
        return gs;
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        Kcontext kctx = Context.getCtx();
        // Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            if (TestRow.get(String.valueOf(columnIndex)) == null
                    || Objects.equals(TestRow.get(String.valueOf(columnIndex)), "Null")) {
                wasNull = true;
                return false;
            }
            return Boolean.parseBoolean(TestRow.get(String.valueOf(columnIndex)));
        }
        Boolean gb = wrappedResultSet.getBoolean(columnIndex);
        RowRecord.put(String.valueOf(columnIndex), String.valueOf(gb));
        addSqlColToList(String.valueOf(columnIndex), gb.getClass().getSimpleName());
        return gb;
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        Kcontext kctx = Context.getCtx();
        // Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            if (TestRow.get(String.valueOf(columnIndex)) == null
                    || Objects.equals(TestRow.get(String.valueOf(columnIndex)), "Null")) {
                wasNull = true;
                return 0;
            }
            wasNull = false;
            return Byte.parseByte(TestRow.get(String.valueOf(columnIndex)));
        }
        Byte gb = wrappedResultSet.getByte(columnIndex);
        RowRecord.put(String.valueOf(columnIndex), String.valueOf(gb));
        addSqlColToList(String.valueOf(columnIndex), gb.getClass().getSimpleName());
        return gb;
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        Kcontext kctx = Context.getCtx();
        String columnLabel = String.valueOf(columnIndex);
        // Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                return 0;
            }
            wasNull = false;
            return Short.parseShort(TestRow.get(columnLabel));
        }
        currentLabel = columnLabel;
        Short gs = wrappedResultSet.getShort(columnIndex);
        RowRecord.put(columnLabel, String.valueOf(gs));
        addSqlColToList(columnLabel, gs.getClass().getSimpleName());
        return gs;
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        Kcontext kctx = Context.getCtx();
        String columnLabel = String.valueOf(columnIndex);
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedResultSet.getInt(columnIndex);
            }
            return 0;
        }
        if (mode == Mode.ModeType.MODE_TEST) {
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                return 0;
            }
            wasNull = false;
            return Integer.parseInt(TestRow.get(String.valueOf(columnIndex)));
        }
        currentLabel = columnLabel;
        Integer gs = wrappedResultSet.getInt(columnIndex);
        RowRecord.put(String.valueOf(columnIndex), String.valueOf(gs));
        addSqlColToList(String.valueOf(columnIndex), gs.getClass().getSimpleName());
        return gs;
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        Kcontext kctx = Context.getCtx();
        String columnLabel = String.valueOf(columnIndex);
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedResultSet.getLong(columnIndex);
            }
            return 0;
        }
        if (mode == Mode.ModeType.MODE_TEST) {
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                logger.debug("getLong wasNull for column " + columnLabel);
                wasNull = true;
                return 0;
            }
            wasNull = false;
            logger.debug("getLong value is " + TestRow.get(columnLabel));
            return Long.parseLong(TestRow.get(columnLabel));
        }
        currentLabel = columnLabel;
        Long gs = wrappedResultSet.getLong(columnIndex);
        RowRecord.put(columnLabel, String.valueOf(gs));
        addSqlColToList(columnLabel, gs.getClass().getSimpleName());
        return gs;
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        Kcontext kctx = Context.getCtx();
        String columnLabel = String.valueOf(columnIndex);
        // Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                return 0;
            }
            wasNull = false;
            return Float.parseFloat(columnLabel);
        }
        Float gs = wrappedResultSet.getFloat(columnIndex);
        RowRecord.put(String.valueOf(columnIndex), String.valueOf(gs));
        addSqlColToList(String.valueOf(columnIndex), gs.getClass().getSimpleName());
        return gs;
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        Kcontext kctx = Context.getCtx();
        String columnLabel = String.valueOf(columnIndex);
        if (mode == Mode.ModeType.MODE_TEST) {
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                return 0;
            }
            wasNull = false;
            return Double.parseDouble(TestRow.get(String.valueOf(columnIndex)));
        }
        currentLabel = columnLabel;
        Double gs = wrappedResultSet.getDouble(columnIndex);
        RowRecord.put(String.valueOf(columnIndex), String.valueOf(gs));
        addSqlColToList(String.valueOf(columnIndex), gs.getClass().getSimpleName());
        return gs;
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        Kcontext kctx = Context.getCtx();
        // Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            if (TestRow.get(String.valueOf(columnIndex)) == null || Objects.equals(TestRow.get(String.valueOf(columnIndex)), "Null")) {
                wasNull = true;
                return null;
            }
            wasNull = false;
            return new BigDecimal(Double.parseDouble(TestRow.get(String.valueOf(columnIndex))));
        }

        BigDecimal gl = wrappedResultSet.getBigDecimal(String.valueOf(columnIndex));

        String res = String.valueOf(gl);
        if (isNullValue(gl)) {
            res = "Null";
        }
        RowRecord.put(String.valueOf(columnIndex), res);
        addSqlColToList(String.valueOf(columnIndex), "BigDecimal");
        return gl;
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        Kcontext kctx = Context.getCtx();
        String columnLabel = String.valueOf(columnIndex);
        // Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                return null;
            }
            wasNull = false;
            return TestRow.get(columnLabel).getBytes();
        }
        byte[] gs = wrappedResultSet.getBytes(columnIndex);
        RowRecord.put(columnLabel, String.valueOf(gs));
        addSqlColToList(columnLabel, gs.getClass().getSimpleName());
        return gs;
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        String columnLabel = String.valueOf(columnIndex);
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedResultSet.getDate(columnIndex);
            }
            return null;
        }
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(String.valueOf(columnIndex)), "Null")) {
                wasNull = true;
                return null;
            }
            String parseDateTime = ParseDateTime(TestRow.get(String.valueOf(columnIndex)));
            if (Objects.equals(parseDateTime, "") || parseDateTime == null) {
                wasNull = true;
                return null;
            }
            SimpleDateFormat formatter = new SimpleDateFormat(parseDateTime);

            try {
                Date x = new Date(formatter.parse(TestRow.get(String.valueOf(columnIndex))).getTime());
                return x;
            } catch (ParseException e) {
                logger.error(CROSS + "Failed to parse Date object from the stored mock due to \n" + e);
            }
        }
        currentLabel = columnLabel;
        Date gd = wrappedResultSet.getDate(columnIndex);
        String res = String.valueOf(gd);
        if (isNullValue(gd)) {
            res = "Null";
        }
        RowRecord.put(String.valueOf(columnIndex), res);
        addSqlColToList(String.valueOf(columnIndex), "Date");
        return gd;
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        String columnLabel = String.valueOf(columnIndex);
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedResultSet.getTime(columnIndex);
            }
            return null;
        }
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            String parseDateTime = ParseDateTime(TestRow.get(String.valueOf(columnIndex)));
            if (TestRow.get(columnLabel) == null || Objects.equals(parseDateTime, "") || parseDateTime == null) {
                wasNull = true;
                return null;
            }
            SimpleDateFormat formatter = new SimpleDateFormat(parseDateTime);
            // SimpleDateFormat formatter = new
            // SimpleDateFormat(ParseDateTime(TestRow.get(String.valueOf(columnIndex))));
            try {
                return new Time(formatter.parse(TestRow.get(String.valueOf(columnIndex))).getTime());
            } catch (ParseException e) {
                logger.error(CROSS + "Failed to parse Time object from the stored mock due to \n" + e);
            }
        }
        currentLabel = columnLabel;
        Time gt = wrappedResultSet.getTime(columnIndex);

        String res = String.valueOf(gt);
        if (isNullValue(gt)) {
            res = "Null";
        }

        RowRecord.put(String.valueOf(columnIndex), res);
        addSqlColToList(String.valueOf(columnIndex), "Time");
        return gt;
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        String columnLabel = String.valueOf(columnIndex);
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedResultSet.getTimestamp(columnIndex);
            }
            return null;
        }
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(String.valueOf(columnIndex)), "Null")) {
                wasNull = true;
                return null;
            }
            String parseDateTime = ParseDateTime(TestRow.get(String.valueOf(columnIndex)));
            if (Objects.equals(parseDateTime, "") || parseDateTime == null) {
                wasNull = true;
                return null;
            }
            SimpleDateFormat formatter = new SimpleDateFormat(parseDateTime);
            try {
                return new Timestamp(formatter.parse(TestRow.get(String.valueOf(columnIndex))).getTime());
            } catch (ParseException e) {
                logger.error(CROSS + "Failed to parse TimeStamp object from the stored mock due to \n" + e);
            }
        }
        currentLabel = columnLabel;
        Timestamp gts = wrappedResultSet.getTimestamp(columnIndex);
        String res = String.valueOf(gts);
        if (isNullValue(gts)) {
            res = "Null";
        }
        RowRecord.put(String.valueOf(columnIndex), res);
        addSqlColToList(String.valueOf(columnIndex), "Timestamp");
        return gts;
    }

    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        logger.warn("{} InputStream getAsciiStream(int columnIndex) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getAsciiStream(columnIndex);
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        logger.warn("{} InputStream getUnicodeStream(int columnIndex) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getUnicodeStream(columnIndex);
    }

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        logger.warn("{} InputStream getBinaryStream(int columnIndex) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getBinaryStream(columnIndex);
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        // Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                return null;
            }
            return TestRow.get(columnLabel);
        }
        String res;
        currentLabel = columnLabel;
        String gs = wrappedResultSet.getString(columnLabel);
        res = gs;
        if (isNullValue(gs)) {
            res = "Null";
        }
        RowRecord.put(columnLabel, res);
        addSqlColToList(columnLabel, "String");
        return gs;
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        // Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                return false;
            }
            wasNull = false;
            return Boolean.parseBoolean(TestRow.get(columnLabel));
        }
        Boolean gb = wrappedResultSet.getBoolean(columnLabel);
        RowRecord.put(columnLabel, String.valueOf(gb));
        addSqlColToList(columnLabel, gb.getClass().getSimpleName());
        return gb;

    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        // Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                return 0;
            }
            wasNull = false;
            return Byte.parseByte(TestRow.get(columnLabel));
        }
        Byte gb = wrappedResultSet.getByte(columnLabel);

        RowRecord.put(columnLabel, String.valueOf(gb));
        addSqlColToList(columnLabel, gb.getClass().getSimpleName());
        return gb;
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        // Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                return 0;
            }
            wasNull = false;
            return Short.parseShort(TestRow.get(columnLabel));
        }
        Short gs = wrappedResultSet.getShort(columnLabel);
        RowRecord.put(columnLabel, String.valueOf(gs));
        addSqlColToList(columnLabel, gs.getClass().getSimpleName());
        return gs;
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedResultSet.getInt(columnLabel);
            }
            return 0;
        }
        if (mode == Mode.ModeType.MODE_TEST) {
            if (TestRow.get(columnLabel) == null
                    || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                return 0;
            }
            wasNull = false;
            int x = Integer.parseInt(TestRow.get(columnLabel));
            return x;
        }
        currentLabel = columnLabel;
        Integer gs = wrappedResultSet.getInt(columnLabel);
        RowRecord.put(columnLabel, String.valueOf(gs));
        addSqlColToList(columnLabel, gs.getClass().getSimpleName());
        return gs;
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedResultSet.getLong(columnLabel);
            }
            return 0;
        }
        if (mode == Mode.ModeType.MODE_TEST) {
            if (TestRow.get(columnLabel) == null
                    || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                logger.debug("getLong wasNull for {}", columnLabel);
                return 0;
            }
            wasNull = false;
            long x = Long.parseLong(TestRow.get(columnLabel));
            logger.debug("getLong value is {} for column {}", x, columnLabel);
            return x;
        }
        currentLabel = columnLabel;
        Long gl = wrappedResultSet.getLong(columnLabel); // this will never be null
        RowRecord.put(columnLabel, String.valueOf(gl));
        addSqlColToList(columnLabel, gl.getClass().getSimpleName());
        return gl;
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        // Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                logger.debug("getFloat wasNull for {}", columnLabel);
                return 0;
            }
            wasNull = false;
            logger.debug("getFloat value is {} for column {}", TestRow.get(columnLabel), columnLabel);
            return Float.parseFloat(TestRow.get(columnLabel));
        }
        currentLabel = columnLabel;
        Float gf = wrappedResultSet.getFloat(columnLabel);
        RowRecord.put(columnLabel, String.valueOf(gf));
        addSqlColToList(columnLabel, gf.getClass().getSimpleName());
        return gf;
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        // Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                logger.debug("getDouble wasNull for {}", columnLabel);
                return 0;
            }
            wasNull = false;
            logger.debug("getDouble value is {} for column {}", TestRow.get(columnLabel), columnLabel);
            return Double.parseDouble(TestRow.get(columnLabel));
        }
        currentLabel = columnLabel;
        Double gd = wrappedResultSet.getDouble(columnLabel);
        RowRecord.put(columnLabel, String.valueOf(gd));
        addSqlColToList(columnLabel, gd.getClass().getSimpleName());
        return gd;
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        Kcontext kctx = Context.getCtx();
        // Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                logger.debug("getBigDecimal wasNull for {}", columnLabel);
                return null;
            }
            wasNull = false;
            logger.debug("getBigDecimal value is {} for column {}", TestRow.get(columnLabel), columnLabel);
            return new BigDecimal(Double.parseDouble(TestRow.get(columnLabel)));
        }
        currentLabel = columnLabel;
        BigDecimal gl = wrappedResultSet.getBigDecimal(columnLabel);
        String res = String.valueOf(gl);
        if (isNullValue(gl)) {
            res = "Null";
        }
        RowRecord.put(columnLabel, res);
        addSqlColToList(columnLabel, "BigDecimal");
        return gl;
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        // Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                logger.debug("getBytes wasNull for {}", columnLabel);
                return null;
            }
            wasNull = false;
            logger.debug("getBytes value is {} for column {}", TestRow.get(columnLabel), columnLabel);
            return TestRow.get(columnLabel).getBytes();
        }
        currentLabel = columnLabel;
        byte[] gb = wrappedResultSet.getBytes(columnLabel);
        if (isNullValue(gb)) {
            RowRecord.put(columnLabel, "Null");
        } else {
            RowRecord.put(columnLabel, new String(gb));
        }
        addSqlColToList(columnLabel, "byte[]");
        return gb;
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedResultSet.getDate(columnLabel);
            }
            return null;
        }

        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                logger.debug("getDate wasNull for {}", columnLabel);
                return null;
            }
            String parseDateTime = ParseDateTime(TestRow.get(String.valueOf(columnLabel)));
            if (Objects.equals(parseDateTime, "") || parseDateTime == null) {
                wasNull = true;
                logger.debug("getDate wasNull for {}", columnLabel);
                return null;
            }
            SimpleDateFormat formatter = new SimpleDateFormat(parseDateTime);
            try {
                Date x = new Date(formatter.parse(TestRow.get(columnLabel)).getTime());
                logger.debug("getDate value is {} for column {}", x, columnLabel);
                return x;
            } catch (ParseException e) {
                logger.error(CROSS + " Failed to parse Date object from the stored mock due to \n" + e);
            }
        }
        currentLabel = columnLabel;
        Date gd = wrappedResultSet.getDate(columnLabel);
        String res = String.valueOf(gd);
        if (isNullValue(gd)) {
            res = "Null";
        }
        RowRecord.put(columnLabel, res);
        addSqlColToList(columnLabel, "Date");
        return gd;
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedResultSet.getTime(columnLabel);
            }
            return null;
        }
        if (mode == Mode.ModeType.MODE_TEST) {

            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                logger.debug("getTime wasNull for {}", columnLabel);
                wasNull = true;
                return null;
            }
            String parseDateTime = ParseDateTime(TestRow.get(String.valueOf(columnLabel)));
            if (Objects.equals(parseDateTime, "") || parseDateTime == null) {
                logger.debug("getTime wasNull for {}", columnLabel);
                wasNull = true;
                return null;
            }
            wasNull = false;
            SimpleDateFormat formatter = new SimpleDateFormat(parseDateTime);

            try {
                logger.debug("getTime value is {} for column {}", formatter.parse(TestRow.get(columnLabel)), columnLabel);
                return new Time(formatter.parse(TestRow.get(columnLabel)).getTime());
            } catch (ParseException e) {
                logger.error(CROSS + " Failed to parse Time object from the stored mock due to \n" + e);
            }
        }
        currentLabel = columnLabel;
        Time gt = wrappedResultSet.getTime(columnLabel);
        String res = String.valueOf(gt);
        if (isNullValue(gt)) {
            res = "Null";
        }
        RowRecord.put(columnLabel, res);
        addSqlColToList(columnLabel, "Time");
        return gt;
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedResultSet.getTimestamp(columnLabel);
            }
            return null;
        }
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                logger.debug("getTimestamp wasNull for {}", columnLabel);
                wasNull = true;
                return null;
            }
            String parseDateTime = ParseDateTime(TestRow.get(String.valueOf(columnLabel)));
            if (Objects.equals(parseDateTime, "") || parseDateTime == null) {
                wasNull = true;
                logger.debug("getTimestamp wasNull for {}", columnLabel);
                return null;
            }
            SimpleDateFormat formatter = new SimpleDateFormat(parseDateTime);

            try {
                logger.debug("getTimestamp value is {} for column {}", formatter.parse(TestRow.get(columnLabel)), columnLabel);
                return new Timestamp(formatter.parse(TestRow.get(columnLabel)).getTime());
            } catch (ParseException e) {
                logger.error(CROSS + " Failed to parse TimeStamp object from the stored mock due to \n" + e);
            }
        }
        currentLabel = columnLabel;
        Timestamp gts = wrappedResultSet.getTimestamp(columnLabel);
        String res = String.valueOf(gts);
        if (isNullValue(gts)) {
            res = "Null";
        }
        RowRecord.put(columnLabel, res);
        addSqlColToList(columnLabel, "Timestamp");
        return gts;
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedResultSet.getAsciiStream(columnLabel);
            }
            return null;
        }
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                return null;
            }
            return new ByteArrayInputStream(TestRow.get(columnLabel).getBytes());
        }
        currentLabel = columnLabel;
        InputStream gas = wrappedResultSet.getAsciiStream(columnLabel);
        String res = String.valueOf(gas);
        if (isNullValue(gas)) {
            res = "Null";
        }
        RowRecord.put(columnLabel, res);
        addSqlColToList(columnLabel, "InputStream");
        return gas;
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedResultSet.getUnicodeStream(columnLabel);
            }
            return null;
        }
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                return null;
            }
            return new ByteArrayInputStream(TestRow.get(columnLabel).getBytes());
        }
        currentLabel = columnLabel;
        InputStream gus = wrappedResultSet.getUnicodeStream(columnLabel);
        String res = String.valueOf(gus);
        if (isNullValue(gus)) {
            res = "Null";
        }
        RowRecord.put(columnLabel, res);
        addSqlColToList(columnLabel, "InputStream");
        return gus;
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        logger.warn("{} InputStream getBinaryStream(String columnLabel) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getBinaryStream(columnLabel);
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        logger.warn("{} SQLWarning getWarnings() throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        logger.warn("{} void clearWarnings() throws SQLException {}", msg1, msg2);
        wrappedResultSet.clearWarnings();
    }

    @Override
    public String getCursorName() throws SQLException {
        logger.warn("{} String getCursorName() throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getCursorName();
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        Kcontext kctx = Context.getCtx();
        // Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            return new KResultSetMetaData();
        }

        logger.debug("getMetaData for Query {}", KConnection.MyQuery);
        ResultSetMetaData getMetaData = wrappedResultSet.getMetaData();
        return new KResultSetMetaData(getMetaData);
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        logger.warn("{} Object getObject(int columnIndex) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getObject(columnIndex);
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedResultSet.getObject(columnLabel);
            }
            return null;
        }
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                return null;
            }
            return TestRow.get(columnLabel);
        }
        currentLabel = columnLabel;
        Object go = wrappedResultSet.getObject(columnLabel);
        String res = String.valueOf(go);
        if (isNullValue(go)) {
            res = "Null";
        }
        RowRecord.put(columnLabel, res);
        addSqlColToList(columnLabel, "Object");
        return go;
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedResultSet.findColumn(columnLabel);
            }
            return 0;
        }
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                return 0;
            }
            return Integer.parseInt(TestRow.get(columnLabel));
        }
        int fc = wrappedResultSet.findColumn(columnLabel);
        String res = String.valueOf(fc);
        if (isNullValue(fc)) {
            res = "Null";
        }
        RowRecord.put(columnLabel, res);
        addSqlColToList(columnLabel, "int");
        return fc;
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        Kcontext kctx = Context.getCtx();
        String columnLabel = String.valueOf(columnIndex);
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedResultSet.getCharacterStream(columnIndex);
            }
            return null;
        }
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                return null;
            }
            return new StringReader(TestRow.get(columnLabel));
        }
        currentLabel = columnLabel;
        Reader gcs = wrappedResultSet.getCharacterStream(columnIndex);
        String res = String.valueOf(gcs);
        if (isNullValue(gcs)) {
            res = "Null";
        }
        RowRecord.put(columnLabel, res);
        addSqlColToList(columnLabel, "Reader");
        return gcs;
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        logger.warn("{} Reader getCharacterStream(String columnLabel) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getCharacterStream(columnLabel);
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        Kcontext kctx = Context.getCtx();
        // Mode.ModeType mode = kctx.getMode();
        String columnLabel = String.valueOf(columnIndex);
        if (mode == Mode.ModeType.MODE_TEST) {
            if (TestRow.get(String.valueOf(columnIndex)) == null
                    || Objects.equals(TestRow.get(String.valueOf(columnIndex)), "Null")) {
                wasNull = true;
                return null;
            }
            wasNull = false;
            return new BigDecimal(Double.parseDouble(TestRow.get(String.valueOf(columnIndex))));
        }
        currentLabel = columnLabel;
        BigDecimal gs = wrappedResultSet.getBigDecimal(columnIndex);
        String res = String.valueOf(gs);
        if (isNullValue(gs)) {
            res = "Null";
        }
        RowRecord.put(String.valueOf(columnIndex), res);
        addSqlColToList(String.valueOf(columnIndex), "BigDecimal");
        return gs;
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        // Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            if (TestRow.get(columnLabel) == null || Objects.equals(TestRow.get(columnLabel), "Null")) {
                wasNull = true;
                return null;
            }
            wasNull = false;
            return new BigDecimal(Double.parseDouble(TestRow.get(columnLabel)));
        }
        currentLabel = columnLabel;
        BigDecimal gl = wrappedResultSet.getBigDecimal(columnLabel);
        String res = String.valueOf(gl);
        if (isNullValue(gl)) {
            res = "Null";
        }
        RowRecord.put(columnLabel, res);
        addSqlColToList(columnLabel, "BigDecimal");
        return gl;
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        logger.warn("{} boolean isBeforeFirst() throws SQLException {}", msg1, msg2);
        return wrappedResultSet.isBeforeFirst();
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        logger.warn("{} boolean isAfterLast() throws SQLException {}", msg1, msg2);

        return wrappedResultSet.isAfterLast();
    }

    @Override
    public boolean isFirst() throws SQLException {
        logger.warn("{} boolean isFirst() throws SQLException {}", msg1, msg2);
        return wrappedResultSet.isFirst();
    }

    @Override
    public boolean isLast() throws SQLException {
        logger.warn("{} boolean isLast() throws SQLException {}", msg1, msg2);
        return wrappedResultSet.isLast();
    }

    @Override
    public void beforeFirst() throws SQLException {
        logger.warn("{} void beforeFirst() throws SQLException {}", msg1, msg2);
        wrappedResultSet.beforeFirst();
    }

    @Override
    public void afterLast() throws SQLException {
        logger.warn("{} void afterLast() throws SQLException {}", msg1, msg2);
        wrappedResultSet.afterLast();
    }

    @Override
    public boolean first() throws SQLException {
        logger.warn("{} boolean first() throws SQLException {}", msg1, msg2);
        return wrappedResultSet.first();
    }

    @Override
    public boolean last() throws SQLException {
        logger.warn("{} boolean last() throws SQLException {}", msg1, msg2);
        return wrappedResultSet.last();
    }

    @Override
    public int getRow() throws SQLException {
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getRow();
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        logger.warn("{} boolean absolute(int row) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.absolute(row);
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        logger.warn("{} boolean relative(int rows) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.relative(rows);
    }

    @Override
    public boolean previous() throws SQLException {
        logger.warn("{} boolean previous() throws SQLException {}", msg1, msg2);
        return wrappedResultSet.previous();
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        logger.warn("{} void setFetchDirection(int direction) throws SQLException {}", msg1, msg2);
        wrappedResultSet.setFetchDirection(direction);
    }

    @Override
    public int getFetchDirection() throws SQLException {
        logger.warn("{} int getFetchDirection() throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getFetchDirection();
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        logger.warn("{} int getRow() throws SQLException {}", msg1, msg2);
        wrappedResultSet.setFetchSize(rows);
    }

    @Override
    public int getFetchSize() throws SQLException {
        logger.warn("{} int getFetchSize() throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getFetchSize();
    }

    @Override
    public int getType() throws SQLException {
        logger.warn("{} int getType() throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getType();
    }

    @Override
    public int getConcurrency() throws SQLException {
        logger.warn("{} int getConcurrency() throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getConcurrency();
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        logger.warn("{} boolean rowUpdated() throws SQLException {}", msg1, msg2);
        return wrappedResultSet.rowUpdated();
    }

    @Override
    public boolean rowInserted() throws SQLException {
        logger.warn("{} boolean rowInserted() throws SQLException {}", msg1, msg2);
        return wrappedResultSet.rowInserted();
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        logger.warn("{} boolean rowDeleted() throws SQLException {}", msg1, msg2);
        return wrappedResultSet.rowDeleted();
    }

    @Override
    public void updateNull(int columnIndex) throws SQLException {
        logger.warn("{} void updateNull(int columnIndex) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateNull(columnIndex);
    }

    @Override
    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        logger.warn("{} void updateBoolean(int columnIndex, boolean x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateBoolean(columnIndex, x);
    }

    @Override
    public void updateByte(int columnIndex, byte x) throws SQLException {
        logger.warn("{} void updateByte(int columnIndex, byte x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateByte(columnIndex, x);
    }

    @Override
    public void updateShort(int columnIndex, short x) throws SQLException {
        logger.warn("{} void updateShort(int columnIndex, short x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateShort(columnIndex, x);
    }

    @Override
    public void updateInt(int columnIndex, int x) throws SQLException {
        logger.warn("{} void updateInt(int columnIndex, int x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateInt(columnIndex, x);
    }

    @Override
    public void updateLong(int columnIndex, long x) throws SQLException {
        logger.warn("{} void updateLong(int columnIndex, long x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateLong(columnIndex, x);
    }

    @Override
    public void updateFloat(int columnIndex, float x) throws SQLException {
        logger.warn("{} void updateFloat(int columnIndex, float x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateFloat(columnIndex, x);
    }

    @Override
    public void updateDouble(int columnIndex, double x) throws SQLException {
        logger.warn("{} void updateDouble(int columnIndex, double x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateDouble(columnIndex, x);
    }

    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
        logger.warn("{} void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateBigDecimal(columnIndex, x);
    }

    @Override
    public void updateString(int columnIndex, String x) throws SQLException {
        logger.warn("{} void updateString(int columnIndex, String x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateString(columnIndex, x);
    }

    @Override
    public void updateBytes(int columnIndex, byte[] x) throws SQLException {
        logger.warn("{} void updateBytes(int columnIndex, byte[] x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateBytes(columnIndex, x);
    }

    @Override
    public void updateDate(int columnIndex, Date x) throws SQLException {
        logger.warn("{} void updateDate(int columnIndex, Date x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateDate(columnIndex, x);
    }

    @Override
    public void updateTime(int columnIndex, Time x) throws SQLException {
        logger.warn("{} void updateTime(int columnIndex, Time x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateTime(columnIndex, x);
    }

    @Override
    public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
        logger.warn("{} void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateTimestamp(columnIndex, x);
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
        logger.warn("{} void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {}",
                msg1, msg2);
        wrappedResultSet.updateAsciiStream(columnIndex, x, length);
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
        logger.warn("{} void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {}",
                msg1, msg2);
        wrappedResultSet.updateBinaryStream(columnIndex, x, length);
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
        logger.warn("{} void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {}", msg1,
                msg2);
        wrappedResultSet.updateCharacterStream(columnIndex, x, length);
    }

    @Override
    public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
        logger.warn("{} void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {}", msg1,
                msg2);
        wrappedResultSet.updateObject(columnIndex, x, scaleOrLength);
    }

    @Override
    public void updateObject(int columnIndex, Object x) throws SQLException {
        logger.warn("{} void updateObject(int columnIndex, Object x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateObject(columnIndex, x);
    }

    @Override
    public void updateNull(String columnLabel) throws SQLException {
        logger.warn("{} void updateNull(String columnLabel) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateNull(columnLabel);
    }

    @Override
    public void updateBoolean(String columnLabel, boolean x) throws SQLException {
        logger.warn("{} void updateBoolean(String columnLabel, boolean x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateBoolean(columnLabel, x);
    }

    @Override
    public void updateByte(String columnLabel, byte x) throws SQLException {
        logger.warn("{} void updateByte(String columnLabel, byte x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateByte(columnLabel, x);
    }

    @Override
    public void updateShort(String columnLabel, short x) throws SQLException {
        logger.warn("{} void updateShort(String columnLabel, short x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateShort(columnLabel, x);
    }

    @Override
    public void updateInt(String columnLabel, int x) throws SQLException {
        logger.warn("{} void updateInt(String columnLabel, int x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateInt(columnLabel, x);
    }

    @Override
    public void updateLong(String columnLabel, long x) throws SQLException {
        logger.warn("{} void updateLong(String columnLabel, long x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateLong(columnLabel, x);
    }

    @Override
    public void updateFloat(String columnLabel, float x) throws SQLException {
        logger.warn("{} void updateFloat(String columnLabel, float x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateFloat(columnLabel, x);
    }

    @Override
    public void updateDouble(String columnLabel, double x) throws SQLException {
        logger.warn("{} void updateDouble(String columnLabel, double x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateDouble(columnLabel, x);
    }

    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
        logger.warn("{} void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateBigDecimal(columnLabel, x);
    }

    @Override
    public void updateString(String columnLabel, String x) throws SQLException {
        logger.warn("{} void updateString(String columnLabel, String x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateString(columnLabel, x);
    }

    @Override
    public void updateBytes(String columnLabel, byte[] x) throws SQLException {
        logger.warn("{} void updateBytes(String columnLabel, byte[] x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateBytes(columnLabel, x);
    }

    @Override
    public void updateDate(String columnLabel, Date x) throws SQLException {
        logger.warn("{} void updateDate(String columnLabel, Date x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateDate(columnLabel, x);
    }

    @Override
    public void updateTime(String columnLabel, Time x) throws SQLException {
        logger.warn("{} void updateTime(String columnLabel, Time x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateTime(columnLabel, x);
    }

    @Override
    public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
        logger.warn("{} void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateTimestamp(columnLabel, x);
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
        logger.warn("{} void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {}",
                msg1, msg2);
        wrappedResultSet.updateAsciiStream(columnLabel, x, length);
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
        logger.warn("{} void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {}",
                msg1, msg2);
        wrappedResultSet.updateBinaryStream(columnLabel, x, length);
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {
        logger.warn(
                "{} void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {}",
                msg1, msg2);
        wrappedResultSet.updateCharacterStream(columnLabel, reader, length);
    }

    @Override
    public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
        logger.warn("{} void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {}",
                msg1, msg2);
        wrappedResultSet.updateObject(columnLabel, x, scaleOrLength);
    }

    @Override
    public void updateObject(String columnLabel, Object x) throws SQLException {
        logger.warn("{} void updateObject(String columnLabel, Object x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateObject(columnLabel, x);
    }

    @Override
    public void insertRow() throws SQLException {
        logger.warn("{} void insertRow() throws SQLException {}", msg1, msg2);
        wrappedResultSet.insertRow();
    }

    @Override
    public void updateRow() throws SQLException {
        logger.warn("{} void updateRow() throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateRow();
    }

    @Override
    public void deleteRow() throws SQLException {
        logger.warn("{} void deleteRow() throws SQLException {}", msg1, msg2);
        wrappedResultSet.deleteRow();
    }

    @Override
    public void refreshRow() throws SQLException {
        logger.warn("{} void refreshRow() throws SQLException {}", msg1, msg2);
        wrappedResultSet.refreshRow();
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        logger.warn("{} void cancelRowUpdates() throws SQLException {}", msg1, msg2);
        wrappedResultSet.cancelRowUpdates();
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        logger.warn("{} void moveToInsertRow() throws SQLException {}", msg1, msg2);
        wrappedResultSet.moveToCurrentRow();
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        logger.warn("{} void moveToCurrentRow() throws SQLException {}", msg1, msg2);
        wrappedResultSet.moveToCurrentRow();
    }

    @Override
    public Statement getStatement() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (mode == testMode) {
            return new KStatement();
        }
        return new KStatement(wrappedResultSet.getStatement());
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        logger.warn("{} Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {}", msg1,
                msg2);
        return wrappedResultSet.getObject(columnIndex, map);
    }

    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        logger.warn("{} Ref getRef(int columnIndex) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getRef(columnIndex);
    }

    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        logger.warn("{} Blob getBlob(int columnIndex) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getBlob(columnIndex);
    }

    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        logger.warn("{} Clob getClob(int columnIndex) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getClob(columnIndex);
    }

    @Override
    public Array getArray(int columnIndex) throws SQLException {
        logger.warn("{} Array getArray(int columnIndex) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getArray(columnIndex);
    }

    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        logger.warn("{} Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {}", msg1,
                msg2);
        return wrappedResultSet.getObject(columnLabel, map);
    }

    @Override
    public Ref getRef(String columnLabel) throws SQLException {
        logger.warn("{} Ref getRef(String columnLabel) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getRef(columnLabel);
    }

    @Override
    public Blob getBlob(String columnLabel) throws SQLException {
        logger.warn("{} Blob getBlob(String columnLabel) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getBlob(columnLabel);
    }

    @Override
    public Clob getClob(String columnLabel) throws SQLException {
        logger.warn("{} Clob getClob(String columnLabel) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getClob(columnLabel);
    }

    @Override
    public Array getArray(String columnLabel) throws SQLException {
        logger.warn("{} Array getArray(String columnLabel) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getArray(columnLabel);
    }

    @Override
    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        logger.warn("{} Date getDate(int columnIndex, Calendar cal) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getDate(columnIndex, cal);
    }

    @Override
    public Date getDate(String columnLabel, Calendar cal) throws SQLException {
        logger.warn("{} Date getDate(String columnLabel, Calendar cal) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getDate(columnLabel, cal);
    }

    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        logger.warn("{} Time getTime(int columnIndex, Calendar cal) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getTime(columnIndex, cal);
    }

    @Override
    public Time getTime(String columnLabel, Calendar cal) throws SQLException {
        logger.warn("{} Time getTime(String columnLabel, Calendar cal) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getTime(columnLabel, cal);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        logger.warn("{} Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getTimestamp(columnIndex, cal);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
        logger.warn("{} Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getTimestamp(columnLabel, cal);
    }

    @Override
    public URL getURL(int columnIndex) throws SQLException {
        logger.warn("{} URL getURL(int columnIndex) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getURL(columnIndex);
    }

    @Override
    public URL getURL(String columnLabel) throws SQLException {
        logger.warn("{} URL getURL(String columnLabel) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getURL(columnLabel);
    }

    @Override
    public void updateRef(int columnIndex, Ref x) throws SQLException {
        logger.warn("{} void updateRef(int columnIndex, Ref x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateRef(columnIndex, x);
    }

    @Override
    public void updateRef(String columnLabel, Ref x) throws SQLException {
        logger.warn("{} void updateRef(String columnLabel, Ref x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateRef(columnLabel, x);
    }

    @Override
    public void updateBlob(int columnIndex, Blob x) throws SQLException {
        logger.warn("{}void updateBlob(int columnIndex, Blob x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateBlob(columnIndex, x);
    }

    @Override
    public void updateBlob(String columnLabel, Blob x) throws SQLException {
        logger.warn("{} void updateBlob(String columnLabel, Blob x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateBlob(columnLabel, x);
    }

    @Override
    public void updateClob(int columnIndex, Clob x) throws SQLException {
        logger.warn("{} void updateClob(int columnIndex, Clob x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateClob(columnIndex, x);
    }

    @Override
    public void updateClob(String columnLabel, Clob x) throws SQLException {
        logger.warn("{} void updateClob(String columnLabel, Clob x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateClob(columnLabel, x);
    }

    @Override
    public void updateArray(int columnIndex, Array x) throws SQLException {
        logger.warn("{} void updateArray(int columnIndex, Array x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateArray(columnIndex, x);
    }

    @Override
    public void updateArray(String columnLabel, Array x) throws SQLException {
        logger.warn("{} void updateArray(String columnLabel, Array x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateArray(columnLabel, x);
    }

    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        logger.warn("{} RowId getRowId(int columnIndex) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getRowId(columnIndex);
    }

    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        logger.warn("{} RowId getRowId(String columnLabel) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getRowId(columnLabel);
    }

    @Override
    public void updateRowId(int columnIndex, RowId x) throws SQLException {
        logger.warn("{} void updateRowId(int columnIndex, RowId x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateRowId(columnIndex, x);
    }

    @Override
    public void updateRowId(String columnLabel, RowId x) throws SQLException {
        logger.warn("{} void updateRowId(String columnLabel, RowId x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateRowId(columnLabel, x);
    }

    @Override
    public int getHoldability() throws SQLException {
        logger.warn("{} int getHoldability() throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getHoldability();
    }

    @Override
    public boolean isClosed() throws SQLException {
        logger.warn("{} boolean isClosed() throws SQLException {}", msg1, msg2);
        return wrappedResultSet.isClosed();
    }

    @Override
    public void updateNString(int columnIndex, String nString) throws SQLException {
        logger.warn("{} void updateNString(int columnIndex, String nString) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateNString(columnIndex, nString);
    }

    @Override
    public void updateNString(String columnLabel, String nString) throws SQLException {
        logger.warn("{}  void updateNString(String columnLabel, String nString) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateNString(columnLabel, nString);
    }

    @Override
    public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
        logger.warn("{}  void updateNClob(int columnIndex, NClob nClob) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateNClob(columnIndex, nClob);
    }

    @Override
    public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
        logger.warn("{} void updateNClob(String columnLabel, NClob nClob) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateNClob(columnLabel, nClob);
    }

    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        logger.warn("{} NClob getNClob(int columnIndex) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getNClob(columnIndex);
    }

    @Override
    public NClob getNClob(String columnLabel) throws SQLException {
        logger.warn("{}  NClob getNClob(String columnLabel) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getNClob(columnLabel);
    }

    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        logger.warn("{} SQLXML getSQLXML(int columnIndex) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getSQLXML(columnIndex);
    }

    @Override
    public SQLXML getSQLXML(String columnLabel) throws SQLException {
        logger.warn("{} SQLXML getSQLXML(String columnLabel) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getSQLXML(columnLabel);
    }

    @Override
    public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
        logger.warn("{} void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateSQLXML(columnIndex, xmlObject);
    }

    @Override
    public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
        logger.warn("{} void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateSQLXML(columnLabel, xmlObject);
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        logger.warn("{} String getNString(int columnIndex) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getNString(columnIndex);
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        logger.warn("{} String getNString(String columnLabel) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getNString(columnLabel);
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        logger.warn("{} Reader getNCharacterStream(int columnIndex) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getNCharacterStream(columnIndex);
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        logger.warn("{} Reader getNCharacterStream(String columnLabel) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getNCharacterStream(columnLabel);
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        logger.warn("{} void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {}",
                msg1, msg2);
        wrappedResultSet.updateNCharacterStream(columnIndex, x, length);
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        logger.warn(
                "{} void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {}",
                msg1, msg2);
        wrappedResultSet.updateNCharacterStream(columnLabel, reader, length);
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
        logger.warn("{} void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {}",
                msg1, msg2);
        wrappedResultSet.updateAsciiStream(columnIndex, x, length);
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
        logger.warn("{} void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {}",
                msg1, msg2);
        wrappedResultSet.updateBinaryStream(columnIndex, x, length);
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        logger.warn("{} void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {}",
                msg1, msg2);
        wrappedResultSet.updateCharacterStream(columnIndex, x, length);
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
        logger.warn("{} void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {}",
                msg1, msg2);
        wrappedResultSet.updateAsciiStream(columnLabel, x, length);
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
        logger.warn("{} void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {}",
                msg1, msg2);
        wrappedResultSet.updateBinaryStream(columnLabel, x, length);
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        logger.warn(
                "{} void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {}",
                msg1, msg2);
        wrappedResultSet.updateCharacterStream(columnLabel, reader, length);
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {
        logger.warn("{}  void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {}",
                msg1, msg2);
        wrappedResultSet.updateBlob(columnIndex, inputStream, length);
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {
        logger.warn(
                "{} void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {}",
                msg1, msg2);
        wrappedResultSet.updateBlob(columnLabel, inputStream, length);
    }

    @Override
    public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
        logger.warn("{} void updateClob(int columnIndex, Reader reader, long length) throws SQLException {}", msg1,
                msg2);
        wrappedResultSet.updateClob(columnIndex, reader, length);
    }

    @Override
    public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
        logger.warn("{} void updateClob(String columnLabel, Reader reader, long length) throws SQLException {}", msg1,
                msg2);
        wrappedResultSet.updateClob(columnLabel, reader, length);
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
        logger.warn("{} void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {}", msg1,
                msg2);
        wrappedResultSet.updateNClob(columnIndex, reader, length);
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
        logger.warn("{} void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {}", msg1,
                msg2);
        wrappedResultSet.updateNClob(columnLabel, reader, length);
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
        logger.warn("{} void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateNCharacterStream(columnIndex, x);
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
        logger.warn("{} void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {}", msg1,
                msg2);
        wrappedResultSet.updateNCharacterStream(columnLabel, reader);
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
        logger.warn("{} void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateAsciiStream(columnIndex, x);
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
        logger.warn("{} void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateBinaryStream(columnIndex, x);
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
        logger.warn("{} void updateCharacterStream(int columnIndex, Reader x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateCharacterStream(columnIndex, x);
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
        logger.warn("{} void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateAsciiStream(columnLabel, x);
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
        logger.warn("{} void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateBinaryStream(columnLabel, x);
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
        logger.warn("{}  void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {}", msg1,
                msg2);
        wrappedResultSet.updateCharacterStream(columnLabel, reader);
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
        logger.warn("{} void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateBlob(columnIndex, inputStream);
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
        logger.warn("{} void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {}", msg1,
                msg2);
        wrappedResultSet.updateBlob(columnLabel, inputStream);
    }

    @Override
    public void updateClob(int columnIndex, Reader reader) throws SQLException {
        logger.warn("{} void updateClob(int columnIndex, Reader reader) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateClob(columnIndex, reader);
    }

    @Override
    public void updateClob(String columnLabel, Reader reader) throws SQLException {
        logger.warn("{} void updateClob(String columnLabel, Reader reader) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateClob(columnLabel, reader);
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader) throws SQLException {
        logger.warn("{} void updateNClob(int columnIndex, Reader reader) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateNClob(columnIndex, reader);
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader) throws SQLException {
        logger.warn("{} void updateNClob(String columnLabel, Reader reader) throws SQLException {}", msg1, msg2);
        wrappedResultSet.updateNClob(columnLabel, reader);
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        logger.warn("{} <T> T getObject(int columnIndex, Class<T> type) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getObject(columnIndex, type);
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        logger.warn("{} <T> T getObject(String columnLabel, Class<T> type) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.getObject(columnLabel, type);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        logger.warn("{} <T> T unwrap(Class<T> iface) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        logger.warn("{} boolean isWrapperFor(Class<?> iface) throws SQLException {}", msg1, msg2);
        return wrappedResultSet.isWrapperFor(iface);
    }

    boolean isNullValue(Object obj) {
        return obj == null;
    }

    // During Test Mode
    public void GetPreAndScale() {
        PrecisionDict = new HashMap<>();
        ScaleDict = new HashMap<>();
        if (TableData == null) {
            logger.debug("Returning as TableData not found !");
            return;
        }
        List<Service.SqlCol> columns = TableData.getColsList();
        if (columns.size() == 0) {
            return;
        }
        for (Service.SqlCol column : columns) {
            if (column == null) {
                continue;
            }
            PrecisionDict.put(column.getName(), String.valueOf(column.getPrecision()));
            ScaleDict.put(column.getName(), String.valueOf(column.getScale()));
        }
    }

    // During Record Mode
    private List<Service.SqlCol> SetPreAndScale() {
        List<Service.SqlCol> sqlColList1 = new ArrayList<>();
        if (sqlColList == null) {
            logger.debug(CROSS + " Found empty sqlColList !!");
            return null;
        }
        for (Service.SqlCol col : sqlColList) {
            if (col == null) {
                logger.debug(CROSS + " Found null column in SQL column List !!");
                continue;
            }
            String colname = col.getName();
            String type = col.getType();
            long precision = 0, scale = 0;
            if (PrecisionDict.size() > 0) {
                precision = Long.parseLong(PrecisionDict.get(colname));
            }
            if (ScaleDict.size() > 0) {
                scale = Long.parseLong(ScaleDict.get(colname));
            }
            Service.SqlCol sqlCol = Service.SqlCol.newBuilder().setName(colname).setType(type).setPrecision(precision)
                    .setScale(scale).build();
            sqlColList1.add(sqlCol);
        }
        PrecisionDict.clear();
        ScaleDict.clear();
        return sqlColList1;
    }

    String ParseDateTime(String formattedDate) {
        if (formattedDate == null || formattedDate.equals("")) {
            logger.debug("Found empty formatted Date to Parse during test\n");
            return "";
        }
        // Try different date and time patterns until a pattern is found that can parse
        // the given string
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd HH:mm:ss.SSS",
                "yyyy-MM-dd HH:mm:ss.S",
                "yyyy-MM-dd HH:mm:ss",
                "dd/MM/yyyy HH:mm:ss.SSS",
                "dd/MM/yyyy HH:mm:ss",
                "dd.MM.yyyy HH:mm:ss.SSS",
                "dd.MM.yyyy HH:mm:ss",
                "dd-MM-yyyy HH:mm:ss.SSS",
                "dd-MM-yyyy HH:mm:ss",
                "yyyy:MM:dd HH:mm:ss.SSS",
                "yyyy:MM:dd HH:mm:ss",
                "HH:mm:ss",
                "yyyy-MM-dd",
                "dd/MM/yyyy"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(pattern);
                java.util.Date date = sdf.parse(formattedDate);
                return pattern;
            } catch (ParseException e) {
                // Do nothing, try the next pattern
                logger.debug(
                        " ParseDateTime method cannot parse the formatted string provided... trying next!\n for date "
                                + formattedDate);
            }
        }
        return "";
    }

}