package io.keploy.utils;

import com.google.protobuf.InvalidProtocolBufferException;
import io.keploy.grpc.stubs.Service;
import io.keploy.regression.Mock;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;
import lombok.NoArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;

@NoArgsConstructor

/// this is sql specific process dep
public class ProcessSQL {

    private static final Logger logger = LogManager.getLogger(ProcessSQL.class);

    //    @SafeVarargs
    public static Service.Table ProcessDep(Map<String, String> meta, Service.Table table, int commits) throws InvalidProtocolBufferException {

        Kcontext kctx = Context.getCtx();
        if (kctx == null) {
            logger.error("dependency mocking failed: failed to get Keploy context");
            return null;
        }
        List<Service.Dependency> deps = kctx.getDeps();
        switch (kctx.getMode()) {
            case MODE_TEST:
//                kctx.getMock().get(0).getKind().equals(Mock.Kind.SQL.value)
                if (kctx.getMock().size() > 0) {
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
                if (commits != 0) {
                    specSchema = Service.Mock.SpecSchema.newBuilder().setInt(commits).setType("INT").build();
                } else {
                    specSchema = Service.Mock.SpecSchema.newBuilder().putAllMetadata(meta).setTable(table).setType("TABLE").build();
                }

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
}
