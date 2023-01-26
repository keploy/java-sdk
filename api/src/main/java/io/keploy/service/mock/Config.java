package io.keploy.service.mock;

import io.keploy.regression.Mode;
import io.keploy.regression.context.Context;
import io.keploy.regression.context.Kcontext;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class Config {

    public static Mode.ModeType mode;

    public static String Name = "";

    public static Kcontext CTX = Context.getCtx();

    public static String Path = "";

    public static Boolean Overwrite = false;
    public static Map<String, Boolean> MockId = Collections.synchronizedMap(new HashMap<>());
    public static String MockPath = "";

}
