package io.keploy.regression;

import io.keploy.regression.context.Context;

public class mode {

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
        if (mode == ModeType.MODE_RECORD || mode == ModeType.MODE_TEST || mode == ModeType.MODE_OFF) {
            return true;
        }
        return false;
    }

    public static void setTestMode() {
        setMode(ModeType.MODE_TEST);
    }

    public enum ModeType {
        MODE_RECORD,
        MODE_TEST,
        MODE_OFF;

        public String getTypeName() {
            switch (this) {
                case MODE_RECORD:
                    return "record";
                case MODE_TEST:
                    return "test";
                case MODE_OFF:
                    return "off";
            }
            return "unknown";
        }

        public ModeType getModeFromContext() {
            if (Context.getCtx() == null) {
                return ModeType.MODE_OFF;
            }
            return Context.getCtx().getMode();
        }
    }
}