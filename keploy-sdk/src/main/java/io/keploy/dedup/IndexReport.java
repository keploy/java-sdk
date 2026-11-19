package io.keploy.dedup;

import com.google.gson.Gson;
import org.jacoco.core.internal.data.CRC64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * IndexReport is the INDEX record: what the SDK took as the app's classes,
 * sent to Keploy on the data socket once for each receiver there, before
 * the first test's coverage it gets (KeployDedupAgent's CoveragePublisher).
 * Keploy says from it how the app's classes were found, and when they were
 * not (the whole jar, the whole classpath, none), and keys the build table
 * by its build id.
 * <p>
 * It is one JSON object on its own connection, as each test's coverage
 * record is, told apart by {@code "kind":"index"}. It carries no class list:
 * the build table carries every class.
 */
final class IndexReport {

    static final String KIND = "index";
    static final int VERSION = 1;
    // A report names at most this many roots, and says how many there were.
    static final int MAX_ROOTS = 64;

    // How the index was found.
    static final String SOURCE_OVERRIDE = "override";
    static final String SOURCE_DERIVED = "derived";
    static final String SOURCE_WHOLE_JAR = "whole-jar";
    static final String SOURCE_CLASSPATH = "classpath";
    static final String SOURCE_NONE = "none";

    // What measures the classes: the JaCoCo agent keploy-sdk.jar started
    // (includes= the index), one the command attached (every class its own
    // options take in), none (premain started none: no class, or it failed),
    // or unchecked (the SDK was not started by premain, so it starts none and
    // looked for none).
    static final String JACOCO_EMBEDDED = "embedded";
    static final String JACOCO_ATTACHED = "attached";
    static final String JACOCO_NONE = "none";
    static final String JACOCO_UNCHECKED = "unchecked";

    private static final Gson GSON = new Gson();

    // Serialized in this order, by name.
    final String kind = KIND;
    final int v = VERSION;
    // "jv1-" + 32 hex (buildId), or "" when the index holds no class.
    final String build;
    final String source;
    // How the JVM was launched, or why the app's classes could not be told
    // apart; for an override, the dirs and jars it named.
    final String launch;
    final List<String> roots;
    final int rootCount;
    // The app's package roots ("org.apache.fineract"), when derived.
    final List<String> packages;
    final int classes;
    // includes= of the JaCoCo agent the SDK started, or "".
    final String includes;
    final String jacoco;

    IndexReport(String build, String source, String launch, Collection<String> roots, List<String> packages,
                int classes, String includes, String jacoco) {
        this.build = build;
        this.source = source;
        this.launch = launch;
        List<String> sorted = new ArrayList<>(new TreeSet<>(roots));
        this.rootCount = sorted.size();
        this.roots = sorted.size() > MAX_ROOTS ? new ArrayList<>(sorted.subList(0, MAX_ROOTS)) : sorted;
        this.packages = packages;
        this.classes = classes;
        this.includes = includes;
        this.jacoco = jacoco;
    }

    String toJson() {
        return GSON.toJson(this);
    }

    /**
     * classId is the id JaCoCo gives a class: the CRC64 of the bytes the
     * index read. It equals the id in JaCoCo's ExecutionData only when JaCoCo
     * saw those same bytes: not for a class another agent transformed first,
     * nor for a multi-release jar's META-INF/versions copy the JVM loads in
     * place of the base entry the index reads.
     */
    static long classId(byte[] bytes) {
        return CRC64.classId(bytes);
    }

    /**
     * buildId is the build's id from its classes' JaCoCo ids: "jv1-" and the
     * first 32 hex of the sha256 over the ids sorted as unsigned numbers,
     * de-duplicated, each as 16 lowercase hex, joined by newlines. Keploy's
     * codecoverage.JavaBuildID computes the same from the same ids. "" when
     * there is no id.
     */
    static String buildId(long[] ids) {
        if (ids.length == 0) {
            return "";
        }
        long[] sorted = Arrays.copyOf(ids, ids.length);
        // Unsigned order: flipping the sign bit maps it onto signed order.
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] ^= Long.MIN_VALUE;
        }
        Arrays.sort(sorted);
        StringBuilder joined = new StringBuilder(sorted.length * 17);
        for (int i = 0; i < sorted.length; i++) {
            if (i > 0 && sorted[i] == sorted[i - 1]) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append('\n');
            }
            String hex = Long.toHexString(sorted[i] ^ Long.MIN_VALUE);
            for (int pad = hex.length(); pad < 16; pad++) {
                joined.append('0');
            }
            joined.append(hex);
        }
        byte[] sum;
        try {
            sum = MessageDigest.getInstance("SHA-256").digest(joined.toString().getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform has SHA-256.
            throw new IllegalStateException(e);
        }
        StringBuilder id = new StringBuilder("jv1-");
        for (int i = 0; i < 16; i++) {
            id.append(Character.forDigit((sum[i] >> 4) & 0xf, 16)).append(Character.forDigit(sum[i] & 0xf, 16));
        }
        return id.toString();
    }

    /**
     * rootOf is the dir or jar a class was read from: its location
     * ("/app/classes/org/acme/Foo.class", "/app/app.jar!BOOT-INF/lib/x.jar!org/acme/Foo.class")
     * without the class's own path, or null when the location does not end
     * with it.
     */
    static String rootOf(String location, String className) {
        String suffix = className + ".class";
        if (!location.endsWith(suffix) || location.length() == suffix.length()) {
            return null;
        }
        String root = location.substring(0, location.length() - suffix.length());
        char last = root.charAt(root.length() - 1);
        if (last != '/' && last != '!') {
            return null;
        }
        root = root.substring(0, root.length() - 1);
        return root.isEmpty() ? "/" : root;
    }
}
