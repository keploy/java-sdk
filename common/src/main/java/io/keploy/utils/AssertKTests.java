package io.keploy.utils;

import java.util.concurrent.atomic.AtomicBoolean;

public class AssertKTests {
    public static final AtomicBoolean finalTestResult = new AtomicBoolean(false);

    public static boolean result() {
        return finalTestResult.get();
    }
}