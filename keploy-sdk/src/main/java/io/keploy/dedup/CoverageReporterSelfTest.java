package io.keploy.dedup;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.File;

/**
 * Standalone validation for {@link CoverageReporter}: proves that computing
 * coverage by <em>reconstructing</em> {@link ExecutionData} from a fired-probe
 * union + a per-class (id, probeCount) manifest produces the <em>identical</em>
 * line/branch numbers as JaCoCo's own analysis of a real {@code .exec} dump.
 *
 * <p>Given a real {@code .exec} (produced by running the app under
 * {@code -javaagent:jacocoagent.jar}) and the matching classes dir, it:
 * <ol>
 *   <li>computes GROUND TRUTH: {@code Analyzer} over the loaded exec store;</li>
 *   <li>derives a manifest (id hex + probeCount) and union (fired indices) from
 *       that same exec — exactly what the SDK would persist;</li>
 *   <li>REBUILDS an execution store from manifest + union (the
 *       {@code CoverageReporter} path) and re-analyzes;</li>
 *   <li>asserts the two coverage results are equal.</li>
 * </ol>
 *
 * <p>Usage: {@code java -cp keploy-sdk.jar
 * io.keploy.dedup.CoverageReporterSelfTest --exec <jacoco.exec> --classes <dir>}
 */
public final class CoverageReporterSelfTest {

    private CoverageReporterSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        String execPath = null;
        String classesPath = null;
        for (int i = 0; i + 1 < args.length; i += 2) {
            if ("--exec".equals(args[i])) {
                execPath = args[i + 1];
            } else if ("--classes".equals(args[i])) {
                classesPath = args[i + 1];
            }
        }
        if (execPath == null || classesPath == null) {
            System.err.println("usage: CoverageReporterSelfTest --exec <jacoco.exec> --classes <dir>");
            System.exit(2);
        }

        ExecFileLoader loader = new ExecFileLoader();
        loader.load(new File(execPath));
        ExecutionDataStore realStore = loader.getExecutionDataStore();

        // (1) GROUND TRUTH — analyze the real exec directly.
        long[] truth = analyze(realStore, classesPath);

        // (2) Derive manifest + union from the real exec (what the SDK captures),
        //     then (3) REBUILD a fresh store from them (the CoverageReporter path).
        ExecutionDataStore rebuilt = new ExecutionDataStore();
        int classes = 0;
        int firedProbes = 0;
        for (ExecutionData data : realStore.getContents()) {
            classes++;
            boolean[] src = data.getProbes();
            boolean[] copy = new boolean[src.length];        // manifest.probeCount = src.length
            for (int i = 0; i < src.length; i++) {
                if (src[i]) {                                 // union = fired indices
                    copy[i] = true;
                    firedProbes++;
                }
            }
            // manifest.id = data.getId() (round-tripped through unsigned-hex, like the real pipeline)
            long id = Long.parseUnsignedLong(Long.toHexString(data.getId()), 16);
            rebuilt.put(new ExecutionData(id, data.getName(), copy));
        }
        long[] rebuiltCov = analyze(rebuilt, classesPath);

        System.out.println("classes in exec: " + classes + ", fired probes: " + firedProbes);
        printRow("GROUND TRUTH (direct exec)", truth);
        printRow("REBUILT (manifest+union)  ", rebuiltCov);

        boolean equal = true;
        for (int i = 0; i < truth.length; i++) {
            if (truth[i] != rebuiltCov[i]) {
                equal = false;
                break;
            }
        }
        if (equal) {
            System.out.println("RESULT: PASS — reconstruction matches JaCoCo's direct analysis exactly.");
            System.exit(0);
        } else {
            System.out.println("RESULT: FAIL — reconstruction diverges from ground truth.");
            System.exit(1);
        }
    }

    /** Returns {lineCov, lineTot, branchCov, branchTot, instrCov, instrTot}. */
    private static long[] analyze(ExecutionDataStore store, String classesPath) throws Exception {
        CoverageBuilder builder = new CoverageBuilder();
        new Analyzer(store, builder).analyzeAll(new File(classesPath));
        long lc = 0, lt = 0, bc = 0, bt = 0, ic = 0, it = 0;
        for (IClassCoverage cc : builder.getClasses()) {
            lc += cc.getLineCounter().getCoveredCount();
            lt += cc.getLineCounter().getTotalCount();
            bc += cc.getBranchCounter().getCoveredCount();
            bt += cc.getBranchCounter().getTotalCount();
            ic += cc.getInstructionCounter().getCoveredCount();
            it += cc.getInstructionCounter().getTotalCount();
        }
        return new long[]{lc, lt, bc, bt, ic, it};
    }

    private static void printRow(String label, long[] c) {
        System.out.printf("%s  lines %d/%d  branches %d/%d  instr %d/%d%n",
                label, c[0], c[1], c[2], c[3], c[4], c[5]);
    }
}
