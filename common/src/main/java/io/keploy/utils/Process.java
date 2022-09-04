package io.keploy.utils;

import io.keploy.grpc.stubs.Service;
import io.keploy.regression.context.Context;
import lombok.NoArgsConstructor;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;

@NoArgsConstructor
public class Process {

    private static final Logger logger = LogManager.getLogger(Process.class);

    public static <T> depsobj ProcessDep(Map<String, String> meta, T... outputs) {
        if (Context.getCtx() == null) {
            logger.error("dependency mocking failed: failed to get Keploy context");
            return new depsobj<String>(false, null);
        }

        switch (Context.getCtx().getMode()) {
            case MODE_TEST:
                List<Service.Dependency> deps = Context.getCtx().getDeps();


                if (deps == null || deps.size() == 0) {
                    logger.error("dependency mocking failed: incorrect number of dependencies in keploy context with test id: " + Context.getCtx().getTestId());
                    return new depsobj<>(false, null);
                }
                if (deps.get(0).getDataList().size() != outputs.length) {
                    logger.error("dependency mocking failed: incorrect number of dependencies in keploy context with test id: " + Context.getCtx().getTestId());
                    return new depsobj<>(false, null);
                }

                for(int i=0;i<outputs.length;i++){
                    T output = outputs[i];

                }

            case MODE_RECORD:

        }

        return new depsobj<>(false, null);
    }

}
