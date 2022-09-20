package io.keploy.regression;

import io.keploy.regression.context.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class mode {

    private static final Logger logger = LogManager.getLogger(mode.class);

    private static ModeType Mode;

    public mode() {

    }

    public mode(ModeType mode) {
        Mode = mode;
    }

    public static ModeType getMode() {
        return Mode;
    }

    public static void setMode(ModeType mode) {
        if (!isValid(mode)) {
            return;
        }
        Mode = mode;
    }

    public static boolean isValid(ModeType mode) {
        return mode == ModeType.MODE_RECORD || mode == ModeType.MODE_TEST || mode == ModeType.MODE_OFF;
    }

    public static void setTestMode() {
        setMode(ModeType.MODE_TEST);
    }

    public enum ModeType {
        MODE_RECORD("record"),
        MODE_TEST("test"),
        MODE_OFF("off");

        public final String value;

        ModeType(String val) {
            this.value = val;
        }

        public ModeType getModeFromContext() {
            if (Context.getCtx() == null) {
                logger.error("failed to get keploy context");
                return ModeType.MODE_OFF;
            }
            return Context.getCtx().getMode();
        }
    }
}