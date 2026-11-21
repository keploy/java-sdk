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
import java.util.logging.Level;
import java.util.logging.Logger;

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
    // "jv1-" + 32 hex (buildId: the class ids, the table format and the analyzer's JaCoCo), or "" when the
    // index holds no class.
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
    // keploy-sdk.jar's version (versionOf), or "" when it is not known: the
    // SDK's classes come from a build's class dir, or from a jar that is not
    // keploy-sdk.jar.
    final String sdkVersion;
    // The version of the JaCoCo agent that measures the classes: the one the
    // SDK started or the one the command attached, as its jar names it; ""
    // when none does (jacoco none or unchecked) or its jar names none.
    final String jacocoVersion;

    IndexReport(String build, String source, String launch, Collection<String> roots, List<String> packages,
                int classes, String includes, String jacoco, String sdkVersion, String jacocoVersion) {
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
        this.sdkVersion = sdkVersion;
        this.jacocoVersion = jacocoVersion;
    }

    // SDK_TITLE is the Implementation-Title keploy-sdk.jar's build writes
    // in its manifest, next to its version: the project's name.
    static final String SDK_TITLE = "Keploy Java Coverage Agent";

    /**
     * versionOf is the Implementation-Version the manifest of type's jar
     * names, as the class loader read it when it defined type's package,
     * when that manifest is keploy-sdk.jar's: its Implementation-Title is
     * SDK_TITLE. It is "" when the manifest is another jar's (an app jar
     * that shades the SDK's classes, or holds a copy of them), names no
     * version, or type comes from no jar (a class dir). So of IndexReport,
     * it is keploy-sdk.jar's version or "", never an app's.
     */
    static String versionOf(Class<?> type) {
        Package pkg = type.getPackage();
        if (pkg == null || !SDK_TITLE.equals(pkg.getImplementationTitle())) {
            return "";
        }
        String version = pkg.getImplementationVersion();
        return version == null ? "" : version;
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
     * hexId writes a class id as every id the SDK sends is written: the id
     * as an unsigned number in 16 lowercase hex digits, zero-padded.
     */
    static String hexId(long id) {
        String hex = Long.toHexString(id);
        StringBuilder padded = new StringBuilder(16);
        for (int pad = hex.length(); pad < 16; pad++) {
            padded.append('0');
        }
        return padded.append(hex).toString();
    }

    /**
     * buildId is the build's id: "jv1-" and the first 32 lowercase hex of the sha256 over the ASCII bytes
     * {@code "keploy-java-table " F "\n" "jacoco " V "\n"} and the ids as {@link #classIdsSum} joins them, where
     * F is the build table's format ({@link BuildTable#TABLE_FORMAT}) and V the JaCoCo release the SDK's analyzer
     * runs ({@link Analyzer#JACOCO}); "" when there is no id. Keploy's codecoverage.JavaBuildID computes the same
     * from the same bytes. It is the one build id: the INDEX record and the build table (BuildTable.write) both
     * take it over the id of every class the index holds, read or not, so Keploy finds the table under the build
     * the INDEX record named. An SDK whose table or probe map can differ for the same class bytes (another F or
     * V) names another build, so Keploy never holds its table against one an older SDK saved for that build.
     */
    static String buildId(long[] ids) {
        return buildId(ids, BuildTable.TABLE_FORMAT, Analyzer.JACOCO);
    }

    /** buildId is the build id of the ids for a table of format F and an analyzer of JaCoCo release V. */
    static String buildId(long[] ids, int format, String jacoco) {
        if (ids.length == 0) {
            return "";
        }
        return "jv1-" + sha256("keploy-java-table " + format + "\njacoco " + jacoco + "\n", joinedIds(ids))
                .substring(0, 32);
    }

    /**
     * Analyzer holds V of the build id apart from IndexReport, so reading it (JaCoCo's class init and its
     * version resource) is paid by the first build id alone, never by the class-id helpers a testcase's END
     * runs before its ACK, and a JaCoCo core that cannot load leaves those helpers working.
     */
    static final class Analyzer {
        /**
         * JACOCO is the release of the JaCoCo core whose analyzer builds the table (the one keploy-sdk.jar
         * carries renamed, read from its own JaCoCo.VERSION less the build qualifier, as "0.8.15"), not the
         * measuring agent's: an attached JaCoCo agent of another version only records the probes. "" if that
         * core cannot say (its version resource is missing), so the INDEX record and the table still name one
         * build; the release smoke test fails a jar whose analyzer cannot say.
         */
        static final String JACOCO = analyzerJacoco();

        private Analyzer() {
        }

        private static String analyzerJacoco() {
            try {
                return release(org.jacoco.core.JaCoCo.VERSION);
            } catch (Throwable t) {
                // JaCoCo reads its version from a resource bundle when the class loads (MissingResourceException,
                // ExceptionInInitializerError): a jar without it still names one build in INDEX and TABLE.
                // Logged here, not through BuildTable, whose link needs the JaCoCo core that may be missing.
                try {
                    Logger.getLogger(IndexReport.class.getName()).log(Level.INFO, "Keploy coverage: keploy-sdk"
                            + " cannot read its JaCoCo core's version, so the build id names none", t);
                } catch (Throwable ignored) {
                    // Nowhere left to say it: the log is what failed.
                }
                return "";
            }
        }
    }

    /** release is a JaCoCo version less its build qualifier: its first three dot-separated parts. */
    static String release(String version) {
        int dots = 0;
        for (int i = 0; i < version.length(); i++) {
            if (version.charAt(i) == '.' && ++dots == 3) {
                return version.substring(0, i);
            }
        }
        return version;
    }

    /**
     * classIdsSum is the sha256, as 64 lowercase hex, over the ids sorted as
     * unsigned numbers, de-duplicated, each as 16 lowercase hex, joined by
     * newlines: a build table unit's hash, over that unit's class ids.
     */
    static String classIdsSum(long[] ids) {
        return sha256("", joinedIds(ids));
    }

    // joinedIds is the ids sorted as unsigned numbers, de-duplicated, each as 16 lowercase hex, joined by
    // newlines, with none after the last.
    private static String joinedIds(long[] ids) {
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
            joined.append(hexId(sorted[i] ^ Long.MIN_VALUE));
        }
        return joined.toString();
    }

    // sha256 is the sha256, as 64 lowercase hex, over the ASCII bytes of prefix and then of ids, taken in
    // two parts so the ids (about 17 bytes a class) are not copied once more behind the prefix.
    private static String sha256(String prefix, String ids) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform has SHA-256.
            throw new IllegalStateException(e);
        }
        digest.update(prefix.getBytes(StandardCharsets.US_ASCII));
        return hex(digest.digest(ids.getBytes(StandardCharsets.US_ASCII)));
    }

    /** hex is b as lowercase hex, two digits a byte: the build id's and a TABLE frame's sha256. */
    static String hex(byte[] b) {
        StringBuilder hex = new StringBuilder(b.length * 2);
        for (byte x : b) {
            hex.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        }
        return hex.toString();
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
