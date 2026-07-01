package io.keploy.dedup;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Offline, one-shot line + branch coverage computation for the dynamic-dedup
 * "Option B" flow. Runs OUTSIDE the app JVM (as a short-lived job invoked by
 * k8s-proxy at recording stop), so it needs no live JaCoCo agent — only:
 *
 * <ul>
 *   <li><b>classes</b> — the app's compiled {@code .class} files (or a jar),
 *       i.e. the bytecode blob stored once per build tag;</li>
 *   <li><b>union</b> — {@code {vmClassName -> [firedProbeIdx]}}: the union of
 *       every kept test-case fingerprint for the test set (across all
 *       auto-replay cycles). Reuses the exact {@code dedupFingerprints} probe
 *       sets already persisted for cross-cycle dedup;</li>
 *   <li><b>manifest</b> — {@code {vmClassName -> {id, probeCount}}}: the JaCoCo
 *       class id (CRC64, as an unsigned-hex string) and total probe count per
 *       class. These are build-constant and captured by the SDK from the live
 *       {@code ExecutionData} at record time ({@code getId()} /
 *       {@code getProbes().length}), so the reporter never has to touch JaCoCo
 *       internal APIs to derive them.</li>
 * </ul>
 *
 * <p>Reconstruction: for each class in the union we build an
 * {@link ExecutionData} with the manifest's id + a {@code boolean[probeCount]}
 * whose fired indices are set, put it in an {@link ExecutionDataStore}, then run
 * the JaCoCo {@link Analyzer} over the classes. The analyzer computes each
 * class's CRC64 id from its bytecode and matches it against the store; a class
 * with no entry (never hit) is analyzed with all-missed probes so it still
 * contributes to the DENOMINATOR. Summing {@code getLineCounter()} /
 * {@code getBranchCounter()} across all classes yields the whole test set's
 * line + branch coverage.
 *
 * <p>Because dedup is coverage-preserving (it only drops exact/subset
 * duplicates), the union of the KEPT fingerprints equals the union of every
 * replayed test, so this number is the true whole-test-set coverage.
 *
 * <p>Output ({@code --out}) is a JSON object:
 * {@code {"lineCovered":N,"lineTotal":N,"branchCovered":N,"branchTotal":N,
 * "instructionCovered":N,"instructionTotal":N,"methodCovered":N,
 * "methodTotal":N,"classCount":N,"hitClassCount":N}}.
 */
public final class CoverageReporter {

    private static final Gson GSON = new Gson();

    private CoverageReporter() {
    }

    /** JaCoCo class id + total probe count for one class (build-constant). */
    static final class ClassMeta {
        String id;       // CRC64 class id, unsigned-hex (from ExecutionData.getId())
        int probeCount;  // ExecutionData.getProbes().length
    }

    /** Computed coverage counters, serialized to the --out file. */
    static final class CoverageResult {
        long lineCovered, lineTotal;
        long branchCovered, branchTotal;
        long instructionCovered, instructionTotal;
        long methodCovered, methodTotal;
        int classCount;    // classes analyzed (denominator basis)
        int hitClassCount; // classes with at least one fired probe
    }

    public static void main(String[] args) {
        try {
            Args parsed = Args.parse(args);
            CoverageResult result = compute(parsed.classesPath, parsed.unionPath, parsed.manifestPath);
            String json = GSON.toJson(result);
            if (parsed.outPath != null) {
                Files.write(parsed.outPath, json.getBytes(StandardCharsets.UTF_8));
            } else {
                System.out.println(json);
            }
        } catch (Exception e) {
            System.err.println("CoverageReporter failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Computes coverage from a classes dir/jar, a fired-probe union, and the
     * per-class id/probe-count manifest. Package-visible for unit testing.
     */
    static CoverageResult compute(Path classesPath, Path unionPath, Path manifestPath) throws IOException {
        Map<String, List<Integer>> union = readUnion(unionPath);
        Map<String, ClassMeta> manifest = readManifest(manifestPath);

        ExecutionDataStore store = new ExecutionDataStore();
        int hitClasses = 0;
        for (Map.Entry<String, ClassMeta> entry : manifest.entrySet()) {
            String className = entry.getKey();
            ClassMeta meta = entry.getValue();
            if (meta == null || meta.id == null || meta.probeCount < 0) {
                continue;
            }
            boolean[] probes = new boolean[meta.probeCount];
            List<Integer> fired = union.get(className);
            if (fired != null) {
                for (Integer idx : fired) {
                    if (idx != null && idx >= 0 && idx < probes.length) {
                        probes[idx] = true;
                    }
                }
                if (!fired.isEmpty()) {
                    hitClasses++;
                }
            }
            long id = Long.parseUnsignedLong(meta.id, 16);
            store.put(new ExecutionData(id, className, probes));
        }

        CoverageBuilder builder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(store, builder);
        File classesFile = classesPath.toFile();
        if (!classesFile.exists()) {
            throw new IOException("classes path does not exist: " + classesPath);
        }
        // analyzeAll walks a dir tree (or a jar) and analyzes every .class,
        // computing each class's CRC64 id and matching it against the store.
        analyzer.analyzeAll(classesFile);

        CoverageResult result = new CoverageResult();
        int classCount = 0;
        for (IClassCoverage cc : builder.getClasses()) {
            classCount++;
            result.lineCovered += cc.getLineCounter().getCoveredCount();
            result.lineTotal += cc.getLineCounter().getTotalCount();
            result.branchCovered += cc.getBranchCounter().getCoveredCount();
            result.branchTotal += cc.getBranchCounter().getTotalCount();
            result.instructionCovered += cc.getInstructionCounter().getCoveredCount();
            result.instructionTotal += cc.getInstructionCounter().getTotalCount();
            result.methodCovered += cc.getMethodCounter().getCoveredCount();
            result.methodTotal += cc.getMethodCounter().getTotalCount();
        }
        result.classCount = classCount;
        result.hitClassCount = hitClasses;
        return result;
    }

    private static Map<String, List<Integer>> readUnion(Path path) throws IOException {
        Type type = new TypeToken<Map<String, List<Integer>>>() {
        }.getType();
        Map<String, List<Integer>> union = GSON.fromJson(readString(path), type);
        return union == null ? java.util.Collections.<String, List<Integer>>emptyMap() : union;
    }

    private static Map<String, ClassMeta> readManifest(Path path) throws IOException {
        Type type = new TypeToken<Map<String, ClassMeta>>() {
        }.getType();
        Map<String, ClassMeta> manifest = GSON.fromJson(readString(path), type);
        if (manifest == null) {
            throw new IOException("manifest is empty or invalid: " + path);
        }
        return manifest;
    }

    private static String readString(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** Minimal --flag value arg parser. */
    private static final class Args {
        Path classesPath;
        Path unionPath;
        Path manifestPath;
        Path outPath;

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i + 1 < argv.length; i += 2) {
                String flag = argv[i];
                String val = argv[i + 1];
                switch (flag) {
                    case "--classes":
                        a.classesPath = Paths.get(val);
                        break;
                    case "--union":
                        a.unionPath = Paths.get(val);
                        break;
                    case "--manifest":
                        a.manifestPath = Paths.get(val);
                        break;
                    case "--out":
                        a.outPath = Paths.get(val);
                        break;
                    default:
                        throw new IllegalArgumentException("unknown flag: " + flag);
                }
            }
            if (a.classesPath == null || a.unionPath == null || a.manifestPath == null) {
                throw new IllegalArgumentException(
                        "usage: CoverageReporter --classes <dir|jar> --union <union.json> "
                                + "--manifest <manifest.json> [--out <cov.json>]");
            }
            return a;
        }
    }

    // Referenced to keep the ICounter import meaningful for readers scanning
    // deps; getLineCounter()/getBranchCounter() return ICounter instances.
    @SuppressWarnings("unused")
    private static long coveredOf(ICounter counter) {
        return counter.getCoveredCount();
    }
}
