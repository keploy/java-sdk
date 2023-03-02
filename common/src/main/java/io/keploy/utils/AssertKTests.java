package io.keploy.utils;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class help in providing the result of test recorded by keploy when run along the unit tests
 */
public class AssertKTests {
    public static final AtomicBoolean finalTestResult = new AtomicBoolean(false);

    public static boolean result() {
        return finalTestResult.get();
    }
}