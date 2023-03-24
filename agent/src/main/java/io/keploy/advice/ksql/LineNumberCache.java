package io.keploy.advice.ksql;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LineNumberCache {
    private static final Map<String, Integer> lineNumberCache = new ConcurrentHashMap<>();

    public static void put(String key, Integer value) {
        lineNumberCache.put(key, value);
    }

    public static Integer get(String key) {
        return lineNumberCache.get(key);
    }

    public static boolean contains(String key) {
        return lineNumberCache.containsKey(key);
    }
}
