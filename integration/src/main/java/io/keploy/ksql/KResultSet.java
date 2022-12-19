package io.keploy.ksql;

import com.google.protobuf.InvalidProtocolBufferException;
import io.keploy.grpc.stubs.Service;
import io.keploy.regression.Mode;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import io.keploy.utils.ProcessSQL;
import org.mockito.Mockito;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Date;
import java.sql.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import static io.keploy.ksql.KDriver.*;

public class KResultSet implements ResultSet {
    ResultSet wrappedResultSet;

    static HashMap<String, String> meta = new HashMap<>();
    private List<Service.SqlCol> sqlColList;

    private Set<Service.SqlCol> colExists;


    private Map<String, String> RowData = new HashMap<>();

    public List<Map<String, String>> preTable = new ArrayList<>();

    List<String> cols = new ArrayList<>();

    List<String> tableRows = new ArrayList<>();

    private Map<String, String> RowDict = new HashMap<>();
    private Service.Table TableData;
    boolean select = false;
    boolean wasNull = true;

    private int index = 0;

    public KResultSet(ResultSet rs) {
        sqlColList = new ArrayList<>();

        colExists = new HashSet<>();

        preTable = new ArrayList<>();
        RowDict = new HashMap<>();
        cols = new ArrayList<>();
        tableRows = new ArrayList<>();

        Kcontext kctx = Context.getCtx();
        if (kctx != null) {
            if (kctx.getMode() == Mode.ModeType.MODE_TEST) {
                rs = Mockito.mock(ResultSet.class);
            }
        }
        KResultSet.meta.clear();
        wrappedResultSet = rs;
    }

    public KResultSet() {

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
//        RowDict not null
        if (RowDict.size() != 0) {
            preTable.add(RowDict);
        }
        // store row data in map and insert it in the list of maps
        RowDict = new HashMap<>();
    }

    // Used in test mode for extracting table from mocks
    void extractTable(int cnt) {
        if (cnt != 0) {
            return;
        }
        Kcontext kctx = Context.getCtx();
        Map<String, String> s = kctx.getMock().get(0).getSpec().getMetadataMap();
        meta = convertMap(s);
        Service.Table testTable;
        try {

            testTable = ProcessSQL.ProcessDep(null, null, 0);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }

        TableData = testTable;
    }

    // Used in test mode for extracting single row in the form of string from mocks
    private boolean extractRows() {
        if (index == 0)
            extractTable(index);
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
        RowData.clear();
        for (int i = 0; i < split.length; i++) {
            Service.SqlCol col = TableData.getCols(i);
            if (!Objects.equals(split[i], "NA")) {
                RowData.put(col.getName(), split[i]);
            }
        }
        return true;
    }


    @Override
    public boolean next() throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedResultSet.next();
            }
            return false;
        }
        Mode.ModeType mode = kctx.getMode();

        boolean hasNext = false;
        switch (mode) {
            case MODE_TEST:
                hasNext = extractRows();
                index++;
                break;
            case MODE_RECORD:
                hasNext = wrappedResultSet.next();
                addRows();
                if (!hasNext) {
                    select = true;
                    Service.Table.Builder tableBuilder = Service.Table.newBuilder();
                    tableBuilder.addAllCols(sqlColList);
                    cols = ProcessSQL.toColumnList(sqlColList);
                    tableRows = ProcessSQL.toRowList(preTable, cols);
                    System.out.println(tableRows);
                    tableBuilder.addAllRows(tableRows);
                    Service.Table table = tableBuilder.build();
                    try {
                        meta.put("method", "next()");
                        ProcessSQL.ProcessDep(meta, table, 0);
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

        //This portion will be executed only at the time of record when context is not null
        if (!select) {
            addRows();
            Service.Table.Builder tableBuilder = Service.Table.newBuilder();
            tableBuilder.addAllCols(sqlColList);
            cols = ProcessSQL.toColumnList(sqlColList);
            tableRows = ProcessSQL.toRowList(preTable, cols);
//            System.out.println(tableRows);
            tableBuilder.addAllRows(tableRows);
            Service.Table table = tableBuilder.build();
//            System.out.println(table);
            try {
                meta.put("method", "close()");
                ProcessSQL.ProcessDep(meta, table, 0);
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
        }
        assert kctx != null;
        Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            return wasNull;
        }
        boolean val = wrappedResultSet.wasNull();
        return val;
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        Kcontext kctx = Context.getCtx();
        Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            return RowData.get(String.valueOf(columnIndex));
        }
        String gs = wrappedResultSet.getString(columnIndex);
        RowDict.put(String.valueOf(columnIndex), String.valueOf(gs));
        addSqlColToList(String.valueOf(columnIndex), gs.getClass().getSimpleName());
        return gs;
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedResultSet.getBoolean(columnIndex);
            }
        }
        return wrappedResultSet.getBoolean(columnIndex);
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedResultSet.getByte(columnIndex);
            }
        }
        byte gb = wrappedResultSet.getByte(columnIndex);
        return gb;
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            if (mode == recordMode) {
                return wrappedResultSet.getShort(columnIndex);
            }
        }
        short gs = wrappedResultSet.getShort(columnIndex);
        return gs;
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        Kcontext kctx = Context.getCtx();
        Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            return Integer.parseInt(RowData.get(String.valueOf(columnIndex)));
        }
        Integer gs = wrappedResultSet.getInt(columnIndex);
        RowDict.put(String.valueOf(columnIndex), String.valueOf(gs));
        addSqlColToList(String.valueOf(columnIndex), gs.getClass().getSimpleName());
        return gs;
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        Kcontext kctx = Context.getCtx();
        Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            return Long.parseLong(RowData.get(String.valueOf(columnIndex)));
        }
        Long gs = wrappedResultSet.getLong(columnIndex);
        RowDict.put(String.valueOf(columnIndex), String.valueOf(gs));
        addSqlColToList(String.valueOf(columnIndex), gs.getClass().getSimpleName());
        return gs;
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return wrappedResultSet.getFloat(columnIndex);
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        return wrappedResultSet.getDouble(columnIndex);
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        return wrappedResultSet.getBigDecimal(columnIndex, scale);
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        return wrappedResultSet.getBytes(columnIndex);
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        return wrappedResultSet.getDate(columnIndex);
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        return wrappedResultSet.getTime(columnIndex);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        return wrappedResultSet.getTimestamp(columnIndex);
    }

    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        return wrappedResultSet.getAsciiStream(columnIndex);
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        return wrappedResultSet.getUnicodeStream(columnIndex);
    }

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        return wrappedResultSet.getBinaryStream(columnIndex);
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            return RowData.get(columnLabel);
        }
        String gs = wrappedResultSet.getString(columnLabel);

        RowDict.put(columnLabel, gs);
        addSqlColToList(columnLabel, gs.getClass().getSimpleName());
        return gs;
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            return Boolean.parseBoolean(RowData.get(columnLabel));
        }
        Boolean gb = wrappedResultSet.getBoolean(columnLabel);
        RowDict.put(columnLabel, String.valueOf(gb));
        addSqlColToList(columnLabel, gb.getClass().getSimpleName());
        return gb;

    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            return Byte.parseByte(RowData.get(columnLabel));
        }
        Byte gb = wrappedResultSet.getByte(columnLabel);

        RowDict.put(columnLabel, String.valueOf(gb));
        addSqlColToList(columnLabel, gb.getClass().getSimpleName());
        return gb;
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            return Short.parseShort(RowData.get(columnLabel));
        }
        Short gs = wrappedResultSet.getShort(columnLabel);
        RowDict.put(columnLabel, String.valueOf(gs));
        addSqlColToList(columnLabel, gs.getClass().getSimpleName());
        return gs;
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            if (RowData.get(columnLabel) == null || Integer.parseInt(RowData.get(columnLabel)) == 0) {
                wasNull = true;
                return 0;
            }
            int x = Integer.parseInt(RowData.get(columnLabel));

            return x;
        }
        Integer gs = wrappedResultSet.getInt(columnLabel);
        RowDict.put(columnLabel, String.valueOf(gs));
        addSqlColToList(columnLabel, gs.getClass().getSimpleName());
        return gs;
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            return Long.parseLong(RowData.get(columnLabel));
        }
        Long gl = wrappedResultSet.getLong(columnLabel);
        RowDict.put(columnLabel, String.valueOf(gl));
        addSqlColToList(columnLabel, gl.getClass().getSimpleName());
        return gl;
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            return Float.parseFloat(RowData.get(columnLabel));
        }
        Float gf = wrappedResultSet.getFloat(columnLabel);
        RowDict.put(columnLabel, String.valueOf(gf));
        addSqlColToList(columnLabel, gf.getClass().getSimpleName());
        return gf;
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            return Double.parseDouble(RowData.get(columnLabel));
        }
        Double gd = wrappedResultSet.getDouble(columnLabel);
        RowDict.put(columnLabel, String.valueOf(gd));
        addSqlColToList(columnLabel, gd.getClass().getSimpleName());
        return gd;
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        BigDecimal gbd = wrappedResultSet.getBigDecimal(columnLabel, scale);
        addSqlColToList(columnLabel, gbd.getClass().getSimpleName());
        return gbd;
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        byte[] gb = wrappedResultSet.getBytes(columnLabel);
        addSqlColToList(columnLabel, gb.getClass().getSimpleName());
        return gb;
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
            if (RowData.get(columnLabel) == null) {
                wasNull = true;
                return null;
            }
            try {
                Date x = new Date(formatter.parse(RowData.get(columnLabel)).getTime());
                return x;
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }
        }
        Date gd = wrappedResultSet.getDate(columnLabel);
        RowDict.put(columnLabel, String.valueOf(gd));
        addSqlColToList(columnLabel, gd.getClass().getSimpleName());
        return gd;
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        Mode.ModeType mode = kctx.getMode();
//  if (mode == Mode.ModeType.MODE_TEST) {
//   return
//  }
        Time gt = wrappedResultSet.getTime(columnLabel);
        RowDict.put(columnLabel, String.valueOf(gt));
        addSqlColToList(columnLabel, gt.getClass().getSimpleName());
        return gt;
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        Kcontext kctx = Context.getCtx();
        Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            wasNull = false;
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss.SSS");
            try {
                return new Timestamp(formatter.parse(RowData.get(columnLabel)).getTime());
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }

        }
        Timestamp gts = wrappedResultSet.getTimestamp(columnLabel);
        RowDict.put(columnLabel, String.valueOf(gts));
        addSqlColToList(columnLabel, gts.getClass().getSimpleName());
        return gts;
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        return wrappedResultSet.getAsciiStream(columnLabel);
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        return wrappedResultSet.getUnicodeStream(columnLabel);
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        return wrappedResultSet.getBinaryStream(columnLabel);
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return wrappedResultSet.getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
        wrappedResultSet.clearWarnings();
    }

    @Override
    public String getCursorName() throws SQLException {
        return wrappedResultSet.getCursorName();
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        Kcontext kctx = Context.getCtx();
        Mode.ModeType mode = kctx.getMode();
        if (mode == Mode.ModeType.MODE_TEST) {
            return new KResultSetMetaData(Mockito.mock(ResultSetMetaData.class));
        }
        ResultSetMetaData getMetaData = wrappedResultSet.getMetaData();
        return new KResultSetMetaData(getMetaData);
    }

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        return wrappedResultSet.getObject(columnIndex);
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return wrappedResultSet.getObject(columnLabel);
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        return wrappedResultSet.findColumn(columnLabel);
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        return wrappedResultSet.getCharacterStream(columnIndex);
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        return wrappedResultSet.getCharacterStream(columnLabel);
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        return wrappedResultSet.getBigDecimal(columnIndex);
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return wrappedResultSet.getBigDecimal(columnLabel);
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        return wrappedResultSet.isBeforeFirst();
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        return wrappedResultSet.isAfterLast();
    }

    @Override
    public boolean isFirst() throws SQLException {
        return wrappedResultSet.isFirst();
    }

    @Override
    public boolean isLast() throws SQLException {
        return wrappedResultSet.isLast();
    }

    @Override
    public void beforeFirst() throws SQLException {
        wrappedResultSet.beforeFirst();
    }

    @Override
    public void afterLast() throws SQLException {
        wrappedResultSet.afterLast();
    }

    @Override
    public boolean first() throws SQLException {
        return wrappedResultSet.first();
    }

    @Override
    public boolean last() throws SQLException {
        return wrappedResultSet.last();
    }

    @Override
    public int getRow() throws SQLException {
        return wrappedResultSet.getRow();
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        return wrappedResultSet.absolute(row);
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        return wrappedResultSet.relative(rows);
    }

    @Override
    public boolean previous() throws SQLException {
        return wrappedResultSet.previous();
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        wrappedResultSet.setFetchDirection(direction);
    }

    @Override
    public int getFetchDirection() throws SQLException {
        return wrappedResultSet.getFetchDirection();
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        wrappedResultSet.setFetchSize(rows);
    }

    @Override
    public int getFetchSize() throws SQLException {
        return wrappedResultSet.getFetchSize();
    }

    @Override
    public int getType() throws SQLException {
        return wrappedResultSet.getType();
    }

    @Override
    public int getConcurrency() throws SQLException {
        return wrappedResultSet.getConcurrency();
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        return wrappedResultSet.rowUpdated();
    }

    @Override
    public boolean rowInserted() throws SQLException {
        return wrappedResultSet.rowInserted();
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        return wrappedResultSet.rowDeleted();
    }

    @Override
    public void updateNull(int columnIndex) throws SQLException {
        wrappedResultSet.updateNull(columnIndex);
    }

    @Override
    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        wrappedResultSet.updateBoolean(columnIndex, x);
    }

    @Override
    public void updateByte(int columnIndex, byte x) throws SQLException {
        wrappedResultSet.updateByte(columnIndex, x);
    }

    @Override
    public void updateShort(int columnIndex, short x) throws SQLException {
        wrappedResultSet.updateShort(columnIndex, x);
    }

    @Override
    public void updateInt(int columnIndex, int x) throws SQLException {
        wrappedResultSet.updateInt(columnIndex, x);
    }

    @Override
    public void updateLong(int columnIndex, long x) throws SQLException {
        wrappedResultSet.updateLong(columnIndex, x);
    }

    @Override
    public void updateFloat(int columnIndex, float x) throws SQLException {
        wrappedResultSet.updateFloat(columnIndex, x);
    }

    @Override
    public void updateDouble(int columnIndex, double x) throws SQLException {
        wrappedResultSet.updateDouble(columnIndex, x);
    }

    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
        wrappedResultSet.updateBigDecimal(columnIndex, x);
    }

    @Override
    public void updateString(int columnIndex, String x) throws SQLException {
        wrappedResultSet.updateString(columnIndex, x);
    }

    @Override
    public void updateBytes(int columnIndex, byte[] x) throws SQLException {
        wrappedResultSet.updateBytes(columnIndex, x);
    }

    @Override
    public void updateDate(int columnIndex, Date x) throws SQLException {
        wrappedResultSet.updateDate(columnIndex, x);
    }

    @Override
    public void updateTime(int columnIndex, Time x) throws SQLException {
        wrappedResultSet.updateTime(columnIndex, x);
    }

    @Override
    public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
        wrappedResultSet.updateTimestamp(columnIndex, x);
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
        wrappedResultSet.updateAsciiStream(columnIndex, x, length);
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
        wrappedResultSet.updateBinaryStream(columnIndex, x, length);
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
        wrappedResultSet.updateCharacterStream(columnIndex, x, length);
    }

    @Override
    public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
        wrappedResultSet.updateObject(columnIndex, x, scaleOrLength);
    }

    @Override
    public void updateObject(int columnIndex, Object x) throws SQLException {
        wrappedResultSet.updateObject(columnIndex, x);
    }

    @Override
    public void updateNull(String columnLabel) throws SQLException {
        wrappedResultSet.updateNull(columnLabel);
    }

    @Override
    public void updateBoolean(String columnLabel, boolean x) throws SQLException {
        wrappedResultSet.updateBoolean(columnLabel, x);
    }

    @Override
    public void updateByte(String columnLabel, byte x) throws SQLException {
        wrappedResultSet.updateByte(columnLabel, x);
    }

    @Override
    public void updateShort(String columnLabel, short x) throws SQLException {
        wrappedResultSet.updateShort(columnLabel, x);
    }

    @Override
    public void updateInt(String columnLabel, int x) throws SQLException {
        wrappedResultSet.updateInt(columnLabel, x);
    }

    @Override
    public void updateLong(String columnLabel, long x) throws SQLException {
        wrappedResultSet.updateLong(columnLabel, x);
    }

    @Override
    public void updateFloat(String columnLabel, float x) throws SQLException {
        wrappedResultSet.updateFloat(columnLabel, x);
    }

    @Override
    public void updateDouble(String columnLabel, double x) throws SQLException {
        wrappedResultSet.updateDouble(columnLabel, x);
    }

    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
        wrappedResultSet.updateBigDecimal(columnLabel, x);
    }

    @Override
    public void updateString(String columnLabel, String x) throws SQLException {
        wrappedResultSet.updateString(columnLabel, x);
    }

    @Override
    public void updateBytes(String columnLabel, byte[] x) throws SQLException {
        wrappedResultSet.updateBytes(columnLabel, x);
    }

    @Override
    public void updateDate(String columnLabel, Date x) throws SQLException {
        wrappedResultSet.updateDate(columnLabel, x);
    }

    @Override
    public void updateTime(String columnLabel, Time x) throws SQLException {
        wrappedResultSet.updateTime(columnLabel, x);
    }

    @Override
    public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
        wrappedResultSet.updateTimestamp(columnLabel, x);
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
        wrappedResultSet.updateAsciiStream(columnLabel, x, length);
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
        wrappedResultSet.updateBinaryStream(columnLabel, x, length);
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {
        wrappedResultSet.updateCharacterStream(columnLabel, reader, length);
    }

    @Override
    public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
        wrappedResultSet.updateObject(columnLabel, x, scaleOrLength);
    }

    @Override
    public void updateObject(String columnLabel, Object x) throws SQLException {
        wrappedResultSet.updateObject(columnLabel, x);
    }

    @Override
    public void insertRow() throws SQLException {
        wrappedResultSet.insertRow();
    }

    @Override
    public void updateRow() throws SQLException {
        wrappedResultSet.updateRow();
    }

    @Override
    public void deleteRow() throws SQLException {
        wrappedResultSet.deleteRow();
    }

    @Override
    public void refreshRow() throws SQLException {
        wrappedResultSet.refreshRow();
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        wrappedResultSet.cancelRowUpdates();
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        wrappedResultSet.moveToCurrentRow();
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        wrappedResultSet.moveToCurrentRow();
    }

    @Override
    public Statement getStatement() throws SQLException {
        Kcontext kctx = Context.getCtx();
//  Mode.ModeType mode = kctx.getMode();
        if (kctx == null) {
            return new KStatement(Mockito.mock(Statement.class));
        }
        return new KStatement(wrappedResultSet.getStatement());
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        return wrappedResultSet.getObject(columnIndex, map);
    }

    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        return wrappedResultSet.getRef(columnIndex);
    }

    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        return wrappedResultSet.getBlob(columnIndex);
    }

    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        return wrappedResultSet.getClob(columnIndex);
    }

    @Override
    public Array getArray(int columnIndex) throws SQLException {
        return wrappedResultSet.getArray(columnIndex);
    }

    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        return wrappedResultSet.getObject(columnLabel, map);
    }

    @Override
    public Ref getRef(String columnLabel) throws SQLException {
        return wrappedResultSet.getRef(columnLabel);
    }

    @Override
    public Blob getBlob(String columnLabel) throws SQLException {
        return wrappedResultSet.getBlob(columnLabel);
    }

    @Override
    public Clob getClob(String columnLabel) throws SQLException {
        return wrappedResultSet.getClob(columnLabel);
    }

    @Override
    public Array getArray(String columnLabel) throws SQLException {
        return wrappedResultSet.getArray(columnLabel);
    }

    @Override
    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        return wrappedResultSet.getDate(columnIndex, cal);
    }

    @Override
    public Date getDate(String columnLabel, Calendar cal) throws SQLException {
        return wrappedResultSet.getDate(columnLabel, cal);
    }

    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        return wrappedResultSet.getTime(columnIndex, cal);
    }

    @Override
    public Time getTime(String columnLabel, Calendar cal) throws SQLException {
        return wrappedResultSet.getTime(columnLabel, cal);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        return wrappedResultSet.getTimestamp(columnIndex, cal);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
        return wrappedResultSet.getTimestamp(columnLabel, cal);
    }

    @Override
    public URL getURL(int columnIndex) throws SQLException {
        return wrappedResultSet.getURL(columnIndex);
    }

    @Override
    public URL getURL(String columnLabel) throws SQLException {
        return wrappedResultSet.getURL(columnLabel);
    }

    @Override
    public void updateRef(int columnIndex, Ref x) throws SQLException {
        wrappedResultSet.updateRef(columnIndex, x);
    }

    @Override
    public void updateRef(String columnLabel, Ref x) throws SQLException {
        wrappedResultSet.updateRef(columnLabel, x);
    }

    @Override
    public void updateBlob(int columnIndex, Blob x) throws SQLException {
        wrappedResultSet.updateBlob(columnIndex, x);
    }

    @Override
    public void updateBlob(String columnLabel, Blob x) throws SQLException {
        wrappedResultSet.updateBlob(columnLabel, x);
    }

    @Override
    public void updateClob(int columnIndex, Clob x) throws SQLException {
        wrappedResultSet.updateClob(columnIndex, x);
    }

    @Override
    public void updateClob(String columnLabel, Clob x) throws SQLException {
        wrappedResultSet.updateClob(columnLabel, x);
    }

    @Override
    public void updateArray(int columnIndex, Array x) throws SQLException {
        wrappedResultSet.updateArray(columnIndex, x);
    }

    @Override
    public void updateArray(String columnLabel, Array x) throws SQLException {
        wrappedResultSet.updateArray(columnLabel, x);
    }

    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        return wrappedResultSet.getRowId(columnIndex);
    }

    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        return wrappedResultSet.getRowId(columnLabel);
    }

    @Override
    public void updateRowId(int columnIndex, RowId x) throws SQLException {
        wrappedResultSet.updateRowId(columnIndex, x);
    }

    @Override
    public void updateRowId(String columnLabel, RowId x) throws SQLException {
        wrappedResultSet.updateRowId(columnLabel, x);
    }

    @Override
    public int getHoldability() throws SQLException {
        return wrappedResultSet.getHoldability();
    }

    @Override
    public boolean isClosed() throws SQLException {
        return wrappedResultSet.isClosed();
    }

    @Override
    public void updateNString(int columnIndex, String nString) throws SQLException {
        wrappedResultSet.updateNString(columnIndex, nString);
    }

    @Override
    public void updateNString(String columnLabel, String nString) throws SQLException {
        wrappedResultSet.updateNString(columnLabel, nString);
    }

    @Override
    public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
        wrappedResultSet.updateNClob(columnIndex, nClob);
    }

    @Override
    public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
        wrappedResultSet.updateNClob(columnLabel, nClob);
    }

    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        return wrappedResultSet.getNClob(columnIndex);
    }

    @Override
    public NClob getNClob(String columnLabel) throws SQLException {
        return wrappedResultSet.getNClob(columnLabel);
    }

    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        return wrappedResultSet.getSQLXML(columnIndex);
    }

    @Override
    public SQLXML getSQLXML(String columnLabel) throws SQLException {
        return wrappedResultSet.getSQLXML(columnLabel);
    }

    @Override
    public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
        wrappedResultSet.updateSQLXML(columnIndex, xmlObject);
    }

    @Override
    public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
        wrappedResultSet.updateSQLXML(columnLabel, xmlObject);
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        return wrappedResultSet.getNString(columnIndex);
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        return wrappedResultSet.getNString(columnLabel);
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        return wrappedResultSet.getNCharacterStream(columnIndex);
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        return wrappedResultSet.getNCharacterStream(columnLabel);
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        wrappedResultSet.updateNCharacterStream(columnIndex, x, length);
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        wrappedResultSet.updateNCharacterStream(columnLabel, reader, length);
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
        wrappedResultSet.updateAsciiStream(columnIndex, x, length);
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
        wrappedResultSet.updateBinaryStream(columnIndex, x, length);
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        wrappedResultSet.updateCharacterStream(columnIndex, x, length);
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
        wrappedResultSet.updateAsciiStream(columnLabel, x, length);
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
        wrappedResultSet.updateBinaryStream(columnLabel, x, length);
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        wrappedResultSet.updateCharacterStream(columnLabel, reader, length);
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {
        wrappedResultSet.updateBlob(columnIndex, inputStream, length);
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {
        wrappedResultSet.updateBlob(columnLabel, inputStream, length);
    }

    @Override
    public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
        wrappedResultSet.updateClob(columnIndex, reader, length);
    }

    @Override
    public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
        wrappedResultSet.updateClob(columnLabel, reader, length);
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
        wrappedResultSet.updateNClob(columnIndex, reader, length);
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
        wrappedResultSet.updateNClob(columnLabel, reader, length);
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
        wrappedResultSet.updateNCharacterStream(columnIndex, x);
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
        wrappedResultSet.updateNCharacterStream(columnLabel, reader);
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
        wrappedResultSet.updateAsciiStream(columnIndex, x);
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
        wrappedResultSet.updateBinaryStream(columnIndex, x);
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
        wrappedResultSet.updateCharacterStream(columnIndex, x);
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
        wrappedResultSet.updateAsciiStream(columnLabel, x);
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
        wrappedResultSet.updateBinaryStream(columnLabel, x);
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
        wrappedResultSet.updateCharacterStream(columnLabel, reader);
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
        wrappedResultSet.updateBlob(columnIndex, inputStream);
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
        wrappedResultSet.updateBlob(columnLabel, inputStream);
    }

    @Override
    public void updateClob(int columnIndex, Reader reader) throws SQLException {
        wrappedResultSet.updateClob(columnIndex, reader);
    }

    @Override
    public void updateClob(String columnLabel, Reader reader) throws SQLException {
        wrappedResultSet.updateClob(columnLabel, reader);
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader) throws SQLException {
        wrappedResultSet.updateNClob(columnIndex, reader);
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader) throws SQLException {
        wrappedResultSet.updateNClob(columnLabel, reader);
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        return wrappedResultSet.getObject(columnIndex, type);
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return wrappedResultSet.getObject(columnLabel, type);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return wrappedResultSet.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return wrappedResultSet.isWrapperFor(iface);
    }

    HashMap<String, String> convertMap(Map<String, String> s) {
        return new HashMap<>(s);
    }

}