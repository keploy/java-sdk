package io.keploy.utils;

import com.google.protobuf.InvalidProtocolBufferException;
import io.keploy.grpc.stubs.Service;
import io.keploy.regression.Mock;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import lombok.NoArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@NoArgsConstructor

/// this is sql specific process dep
public class ProcessSQL {

    private static final Logger logger = LogManager.getLogger(ProcessSQL.class);

    //    @SafeVarargs
    public static Service.Table ProcessDep(Map<String, String> meta, Service.Table table, int id) throws InvalidProtocolBufferException {

        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            logger.error("dependency mocking failed: failed to get Keploy context");
            return null;
        }
        switch (kctx.getMode()) {
            case MODE_TEST:
                if (kctx.getMock().size() > 0 && kctx.getMock().get(0).getKind().equals("SQL")) {
                    List<Service.Mock> mocks = kctx.getMock();
                    if (mocks.size() > 0) {
                        final Service.Table ttable = mocks.get(0).getSpec().getTable();
                        mocks.remove(0);
                        return ttable;
                    }
                    // for int
                }

                break;
            case MODE_RECORD:

                Service.Mock.SpecSchema specSchema = null;

                specSchema = Service.Mock.SpecSchema.newBuilder().putAllMetadata(meta).setInt(id).setTable(table).setType("TABLE").build();

                Service.Mock mock = Service.Mock.newBuilder()
                        .setVersion(Mock.Version.V1_BETA1.value)
                        .setName("")
                        .setKind(Mock.Kind.SQL.value)
                        .setSpec(specSchema)
                        .build();

                kctx.getMock().add(mock);
                break;
        }
        return null;
    }

    public static List<String> toRowList(List<Map<String, String>> preTable, List<String> columns) {
        List<String> rows = new ArrayList<>();
        for (Map<String, String> stringStringMap : preTable) {
            StringBuilder row = new StringBuilder();
            for (String column : columns) {
                if (stringStringMap.get(column) != null) {
                    row.append("`").append(stringStringMap.get(column)).append("`|");
                } else {
                    row.append("`NA`|");
                }
            }
            row.deleteCharAt(row.length() - 1);
            row.insert(0, "[");
            row.append("]");
            rows.add(String.valueOf(row));
        }
        return rows;
    }

    public static List<String> toColumnList(List<Service.SqlCol> sqlColList) {
        List<String> col = new ArrayList<>();
        for (Service.SqlCol v : sqlColList) {
            col.add(v.getName());
        }
        return col;
    }

    public static HashMap<String, String> convertMap(Map<String, String> s) {
        return new HashMap<>(s);
    }

}
