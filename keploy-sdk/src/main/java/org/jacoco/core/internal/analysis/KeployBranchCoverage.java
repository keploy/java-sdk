package org.jacoco.core.internal.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ILine;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.internal.analysis.filter.Filters;
import org.jacoco.core.internal.analysis.filter.IFilter;
import org.jacoco.core.internal.analysis.filter.IFilterOutput;
import org.jacoco.core.internal.analysis.filter.Replacements;
import org.jacoco.core.internal.flow.ClassProbesAdapter;
import org.jacoco.core.internal.flow.MethodProbesVisitor;
import org.jacoco.core.internal.instr.InstrSupport;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;

/**
 * KeployBranchCoverage runs JaCoCo's own analysis of one class (ClassAnalyzer's steps for each method: the
 * instruction graph, the filters, the merges and the branch replacements) and keeps what JaCoCo's public
 * coverage API sums away: which counted instructions are covered and which branch of each one is. Keploy's
 * build table makes each class's methods from the coverage this pass computes ({@link Method#coverage}) and maps
 * each probe to the lines and branch edges that a hit of that probe covers, from the same pass: a class is
 * analyzed once.
 *
 * <p>It lives in JaCoCo's package so that it runs JaCoCo's package-private analysis classes as they are. Each
 * method's per-line sums are checked against the coverage JaCoCo computes for it in the same pass, so a
 * mismatch throws instead of mapping a probe wrong. What one probe covers is read off that same pass (the
 * edges JaCoCo's builder wired and the filters' output), as {@link #analyze(byte[], Map, EachProbe)} says.
 */
public final class KeployBranchCoverage {

    private KeployBranchCoverage() {
    }

    /** One method JaCoCo counts (it has code), with its counted instructions in code order. */
    public static final class Method {
        public final String name;
        public final String desc;
        /** The method's probe ids, in the order JaCoCo met them. */
        public int[] probes = new int[0];
        private int probeCount;
        /** Per counted instruction: its line (-1 when it has none). */
        public int[] lines;
        /** Per counted instruction: whether it is covered. */
        public boolean[] covered;
        /** Per counted instruction: whether each of its branches is covered (empty below two branches). */
        public boolean[][] branches;
        /**
         * JaCoCo's coverage of the method from this pass, computed as ClassAnalyzer computes the coverage it
         * adds to its class (a MethodCoverageCalculator over the same instructions and filter output), with
         * the probes analyze was given: with none executed, what JaCoCo's Analyzer gives with no execution data.
         */
        public IMethodCoverage coverage;

        Method(String name, String desc) {
            this.name = name;
            this.desc = desc;
        }

        void addProbe(int id) {
            if (probeCount == probes.length) {
                int[] grown = new int[Math.max(4, probeCount * 2)];
                System.arraycopy(probes, 0, grown, 0, probeCount);
                probes = grown;
            }
            probes[probeCount++] = id;
        }
    }

    /**
     * analyze runs JaCoCo's analysis over the class bytes with the given probes (null: none executed) and puts
     * each method that has code into out, keyed by name + descriptor. It returns
     * the class's probe count, or -1 for a class JaCoCo's Analyzer does not analyze (module-info,
     * package-info). It throws when the bytes are not a class JaCoCo reads, or when a method's per-line sums
     * differ from JaCoCo's coverage of it.
     */
    public static int analyze(byte[] bytes, boolean[] probes, Map<String, Method> out) {
        return analyze(bytes, probes, out, null);
    }

    /**
     * NotMapped is a class file whose probes cannot be mapped exactly from this pass: a method's flow has a
     * shape the walk does not map, its per-instruction coverage differs from JaCoCo's per-line sums, a check
     * of the caller's fails, or Keploy's own steps of the pass fail (a bug of Keploy's, here as NotMapped with
     * its cause). The caller marks the class not measured; it is not JaCoCo failing to read the class.
     */
    public static final class NotMapped extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public NotMapped(String message) {
            super(message);
        }

        public NotMapped(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Callers carries what a callback of EachProbe threw out of Keploy's steps, so it passes as it is. */
    private static final class Callers extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final RuntimeException thrown;

        Callers(RuntimeException thrown) {
            super(null, null, false, false);
            this.thrown = thrown;
        }
    }

    /**
     * Hits is what one probe covers in its method: the method's counted instructions that a class analysis
     * with only that probe executed marks covered, and which of their branches. It is valid during
     * {@link EachProbe#probe} only: the arrays are the method's, reused for each of its probes.
     */
    public static final class Hits {
        /** How many of the method's counted instructions the probe covers. */
        public int count;
        /** The first count entries: those instructions' indexes into the method's lines, in no set order. */
        public final int[] at;
        /** Per counted instruction, which of its branches the probe covers; all false for one not in at. */
        public final boolean[][] branches;
        /**
         * The steps this probe's walk took: the instructions it walked back over, plus each branch the
         * filters' replacements fed from them. It is the probe's cost, which a caller can bound: in a method
         * where each probe walks back over all the probes before it (a chain of early returns), the walks
         * sum to the square of its length.
         */
        public int steps;

        Hits(Method m) {
            at = new int[m.lines.length];
            branches = new boolean[m.lines.length][];
            for (int k = 0; k < branches.length; k++) {
                branches[k] = new boolean[m.branches[k].length];
            }
        }

        void clear() {
            for (int j = 0; j < count; j++) {
                Arrays.fill(branches[at[j]], false);
            }
            count = 0;
        }
    }

    /** What {@link #analyze(byte[], Map, EachProbe)} hands over for each probe of each method with code. */
    public interface EachProbe {
        /**
         * probe gets method (as analyze with no probe executed puts it in out), the index r of one of its
         * probes in method.probes, and what that probe alone covers (valid during this call only).
         */
        void probe(Method method, int r, Hits hits);

        /**
         * wants gets each method with code once, in the class's order, as soon as its coverage
         * ({@link Method#coverage}) is computed and before any of its probes, and says whether its probes are
         * walked at all: a method it declines costs no walk, and probe gets none of its probes. Every method
         * with code is wanted unless this is overridden.
         */
        default boolean wants(Method method) {
            return true;
        }
    }

    /**
     * analyze is {@link #analyze(byte[], boolean[], Map)} with no probe executed, and it also hands each
     * probe's own coverage to each, method by method, as a class analysis with only that probe executed gives
     * it, from that one pass: no method is analyzed again. JaCoCo's builder marks an executed probe's
     * instruction and walks back through each instruction's predecessor, the one edge into it that carries no
     * probe (JaCoCo puts a probe on every edge into an instruction that more than one edge reaches), stopping
     * after an instruction it marked already (a loop); its coverage calculator then merges and replaces
     * branches as the filters said, reading only which instructions and branches are marked. Each probe here
     * takes that walk over the edges the pass's builder wired and applies the pass's filter output to what it
     * marked, so a method costs its size plus its probes' walks ({@link Hits#steps}), with no allocation per
     * probe. The walks are short when the method's statements join at labels many edges reach (JaCoCo puts a
     * probe on each of those edges), and quadratic in a chain of early returns, where each probe walks back
     * over every check before it. A method whose
     * flow does not have that shape (an instruction two edges without a probe reach, a replacement that reads
     * another replaced instruction) throws. What setting a method's counted instructions and the probe walk
     * throw is NotMapped; JaCoCo's steps (its instruction graph, filters and coverage calculator, with the hooks
     * Keploy's builder and filter output run inside them) and each's callbacks throw as they do.
     */
    public static int analyze(byte[] bytes, Map<String, Method> out, EachProbe each) {
        return analyze(bytes, null, out, each);
    }

    private static int analyze(byte[] bytes, boolean[] probes, Map<String, Method> out, EachProbe each) {
        ClassReader reader = InstrSupport.classReaderFor(bytes);
        if ((reader.getAccess() & Opcodes.ACC_MODULE) != 0 || reader.getClassName().endsWith("/package-info")) {
            return -1;
        }
        Visitor v = new Visitor(reader.getClassName(), probes, out, each);
        reader.accept(new ClassProbesAdapter(v, false), 0);
        return v.total;
    }

    private static final class Visitor extends ClassAnalyzer {
        private final boolean[] probes;
        private final Map<String, Method> out;
        private final EachProbe each;
        private final IFilter filter = Filters.all();
        int total;

        Visitor(String name, boolean[] probes, Map<String, Method> out, EachProbe each) {
            super(new ClassCoverageImpl(name, 0, false), probes, new StringPool());
            this.probes = probes;
            this.out = out;
            this.each = each;
        }

        @Override
        public void visitTotalProbeCount(int count) {
            super.visitTotalProbeCount(count);
            total = count;
        }

        @Override
        public MethodProbesVisitor visitMethod(int access, final String name, final String desc,
                final String signature, String[] exceptions) {
            InstrSupport.assertNotInstrumented(name, getClassName());
            final Method m = new Method(name, desc);
            final Builder builder = new Builder(probes, m, each != null);
            return new MethodAnalyzer(builder) {
                @Override
                public void accept(MethodNode node, MethodVisitor visitor) {
                    builder.start(node.instructions);
                    super.accept(node, visitor);
                    collect(m, builder, node, signature);
                }
            };
        }

        private void collect(Method m, Builder builder, MethodNode node, String signature) {
            // JaCoCo's steps, as ClassAnalyzer takes them: the filters, then the coverage calculator.
            Output output = new Output();
            filter.filter(node, this, output);
            MethodCoverageCalculator calculator = new MethodCoverageCalculator(builder.getInstructions());
            output.applyTo(calculator);
            MethodCoverageImpl coverage = new MethodCoverageImpl(m.name, m.desc, signature);
            calculator.calculate(coverage);
            if (!coverage.containsCode()) {
                return;
            }
            // Keploy's steps, under one wrap: what fails in them is NotMapped, so a bug of Keploy's is not
            // blamed on JaCoCo. What each's callbacks throw is carried out in a Callers and passes as it is.
            try {
                fill(m, builder, node, output, coverage);
                out.put(m.name + m.desc, m);
                if (each != null && wants(m)) {
                    map(m, builder, node, output);
                }
            } catch (Callers e) {
                throw e.thrown;
            } catch (RuntimeException e) {
                throw keploys(m, e);
            }
        }

        /** wants is each.wants(m), with what it throws carried out in a Callers. */
        private boolean wants(Method m) {
            try {
                return each.wants(m);
            } catch (RuntimeException e) {
                throw new Callers(e);
            }
        }

        /** keploys is e, thrown by Keploy's own steps for m, as NotMapped naming the class and method. */
        private NotMapped keploys(Method m, RuntimeException e) {
            if (e instanceof NotMapped) {
                return (NotMapped) e;
            }
            return new NotMapped(getClassName() + "#" + m.name + m.desc + ": " + e, e);
        }

        /**
         * map hands each of m's probes to each with what that probe alone covers, from this pass: the edges
         * builder wired (each instruction's predecessor) and the filters' output.
         */
        private void map(Method m, Builder builder, MethodNode node, Output output) {
            if (m.probes.length == 0) {
                return;
            }
            InsnList insns = node.instructions;
            int n = insns.size();
            Map<AbstractInsnNode, Instruction> instructions = builder.getInstructions();
            // Each instruction's predecessor and the branch of it that leads here: its fall-through, or the
            // jump into it. JaCoCo's builder wires both and keeps the last; here none may have two.
            int[] pred = Arrays.copyOf(builder.fall, n);
            int[] predBranch = new int[n];
            for (int j = 0; j < builder.jumps; j++) {
                Integer t = builder.labelAt.get(builder.jumpTarget.get(j));
                if (t == null) {
                    throw shape(m, "a jump to a label with no instruction");
                }
                if (pred[t] >= 0) {
                    throw shape(m, "two edges without a probe into instruction " + t);
                }
                if (builder.jumpFrom[j] < 0) {
                    throw shape(m, "a jump from no instruction");
                }
                pred[t] = builder.jumpFrom[j];
                predBranch[t] = builder.jumpBranch[j];
            }
            // The counted instructions, in fill's order, and each instruction's merge representative.
            int[] counted = new int[n];
            int[] root = new int[n];
            Arrays.fill(counted, -1);
            int c = 0;
            for (AbstractInsnNode i = insns.getFirst(); i != null; i = i.getNext()) {
                if (instructions.get(i) == null) {
                    continue;
                }
                root[insns.indexOf(i)] = insns.indexOf(output.representative(i));
                if (!output.ignored.contains(i)) {
                    counted[insns.indexOf(i)] = c++;
                }
            }
            if (c != m.lines.length) {
                throw shape(m, c + " counted instructions, " + m.lines.length + " in its coverage");
            }
            // A counted instruction's branch numbers in fill's order (its row of branches), and, for each new
            // branch of a replaced one, the instruction branches it reads: key root << 32 | branch, sorted, the
            // new branch (counted instruction << 32 | its index) beside it.
            int[][] numbers = new int[c][];
            long[] reads = new long[8];
            long[] feeds = new long[8];
            int nReads = 0;
            for (AbstractInsnNode i = insns.getFirst(); i != null; i = i.getNext()) {
                int at = insns.indexOf(i);
                int k = counted[at];
                if (k < 0) {
                    continue;
                }
                Replacements r = output.replacements.get(i);
                if (r == null) {
                    // fill checked that a counted instruction's branch numbers are as many as its branches.
                    numbers[k] = new int[m.branches[k].length];
                    for (int x = 0; x < numbers[k].length; x++) {
                        numbers[k][x] = builder.number(at, x);
                    }
                    continue;
                }
                int t = 0;
                for (Collection<Replacements.InstructionBranch> branch : r.values()) {
                    for (Replacements.InstructionBranch from : branch) {
                        if (from.instruction != i && output.replacements.containsKey(from.instruction)) {
                            throw shape(m, "a replaced branch reads another replaced instruction");
                        }
                        if (instructions.get(from.instruction) == null) {
                            throw shape(m, "a replaced branch reads no instruction");
                        }
                        if (nReads == reads.length) {
                            reads = Arrays.copyOf(reads, nReads * 2);
                            feeds = Arrays.copyOf(feeds, nReads * 2);
                        }
                        reads[nReads] = key(root[insns.indexOf(from.instruction)], from.branch);
                        feeds[nReads++] = (long) k << 32 | t;
                    }
                    t++;
                }
            }
            sortTogether(reads, feeds, nReads);
            // Per probe: the walk, by stamp (the probe's round, so nothing is cleared between probes).
            int[] stamp = new int[n];
            int[] walked = new int[16];
            int[] walkedBranch = new int[16];
            int[] newStamp = new int[c];
            Hits hits = new Hits(m);
            for (int r = 0; r < m.probes.length; r++) {
                int round = r + 1;
                int w = 0;
                int i = builder.probeFrom[r];
                int b = builder.probeBranch[r];
                if (i < 0) {
                    throw shape(m, "a probe on no instruction");
                }
                while (i >= 0) {
                    if (w == walked.length) {
                        walked = Arrays.copyOf(walked, w * 2);
                        walkedBranch = Arrays.copyOf(walkedBranch, w * 2);
                    }
                    walked[w] = i;
                    walkedBranch[w++] = b;
                    if (stamp[i] == round) {
                        break;
                    }
                    stamp[i] = round;
                    b = predBranch[i];
                    i = pred[i];
                }
                int fed = 0;
                for (int x = 0; x < w; x++) {
                    int rt = root[walked[x]];
                    int br = walkedBranch[x];
                    int k = counted[rt];
                    if (k >= 0 && numbers[k] != null) {
                        // Merged: the representative carries its group's marks.
                        mark(hits, newStamp, round, k);
                        if (numbers[k].length > 0) {
                            int pos = Arrays.binarySearch(numbers[k], br);
                            if (pos < 0) {
                                throw shape(m, "branch " + br + " marked, not one of its instruction's");
                            }
                            hits.branches[k][pos] = true;
                        }
                    }
                    // Replaced: each new branch that reads this instruction branch.
                    long key = key(rt, br);
                    for (int y = lowerBound(reads, nReads, key); y < nReads && reads[y] == key; y++) {
                        int kk = (int) (feeds[y] >>> 32);
                        int t = (int) feeds[y];
                        fed++;
                        mark(hits, newStamp, round, kk);
                        if (t < hits.branches[kk].length) {
                            hits.branches[kk][t] = true;
                        }
                    }
                }
                hits.steps = w + fed;
                try {
                    each.probe(m, r, hits);
                } catch (RuntimeException e) {
                    throw new Callers(e);
                }
                hits.clear();
            }
        }

        private static long key(int instruction, int branch) {
            return (long) instruction << 32 | (branch & 0xffffffffL);
        }

        /** lowerBound is the first index of the n sorted keys whose key is at least key. */
        private static int lowerBound(long[] keys, int n, long key) {
            int lo = 0;
            int hi = n;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (keys[mid] < key) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }

        /** sortTogether sorts the first n keys ascending, moving each value with its key (a merge sort). */
        private static void sortTogether(long[] keys, long[] values, int n) {
            long[] k2 = new long[n];
            long[] v2 = new long[n];
            for (int width = 1; width < n; width *= 2) {
                for (int lo = 0; lo < n; lo += 2 * width) {
                    int mid = Math.min(lo + width, n);
                    int hi = Math.min(lo + 2 * width, n);
                    int a = lo;
                    int b = mid;
                    for (int o = lo; o < hi; o++) {
                        if (a < mid && (b >= hi || keys[a] <= keys[b])) {
                            k2[o] = keys[a];
                            v2[o] = values[a++];
                        } else {
                            k2[o] = keys[b];
                            v2[o] = values[b++];
                        }
                    }
                }
                System.arraycopy(k2, 0, keys, 0, n);
                System.arraycopy(v2, 0, values, 0, n);
            }
        }

        private static void mark(Hits hits, int[] newStamp, int round, int k) {
            if (newStamp[k] != round) {
                newStamp[k] = round;
                hits.at[hits.count++] = k;
            }
        }

        private NotMapped shape(Method m, String what) {
            return new NotMapped(getClassName() + "#" + m.name + m.desc + ": " + what);
        }

        /**
         * fill sets m's counted instructions from builder's graph and the filters' output, as ClassAnalyzer
         * computes a method's coverage, and checks them against coverage, the method's coverage JaCoCo
         * computed from them (it has code).
         */
        private void fill(Method m, Builder builder, MethodNode node, Output output, MethodCoverageImpl coverage) {
            Map<AbstractInsnNode, Instruction> instructions = builder.getInstructions();
            int[] p = new int[m.probeCount];
            System.arraycopy(m.probes, 0, p, 0, m.probeCount);
            m.probes = p;
            InsnList insns = node.instructions;
            int[] lines = new int[insns.size()];
            boolean[] covered = new boolean[insns.size()];
            boolean[][] branches = new boolean[insns.size()][];
            int c = 0;
            Map<Integer, int[]> sums = new HashMap<>(); // line -> instructions, covered, branches, covered
            for (AbstractInsnNode i = insns.getFirst(); i != null; i = i.getNext()) {
                if (output.ignored.contains(i)) {
                    continue;
                }
                final Instruction insn = instructions.get(i);
                if (insn == null) {
                    continue;
                }
                boolean hit = insn.getInstructionCounter().getCoveredCount() > 0;
                int n = insn.getBranchCounter().getTotalCount();
                // The instruction's branch numbers: those its builder gave it (a switch's may skip numbers),
                // or 0..k-1 for the k new branches a filter replaced them with.
                Integer replaced = output.replaced.get(i);
                int at = insns.indexOf(i);
                int count = replaced != null ? replaced : builder.numbers(at);
                if (n > 0 && count != n) {
                    throw new NotMapped("JaCoCo counts " + n + " branches at " + getClassName() + "#"
                            + m.name + m.desc + " line " + insn.getLine() + ", its builder gave " + count);
                }
                boolean[] b = new boolean[n];
                // An instruction not covered has no covered branch (JaCoCo covers an instruction when one of
                // its branches is), so only a covered one's branches are read.
                for (int k = 0; hit && k < n; k++) {
                    // replaceBranches with one new branch fed by this branch alone is covered exactly when the
                    // branch is: JaCoCo's own way to read one branch.
                    Replacements one = new Replacements();
                    one.add(i, i, replaced != null ? k : builder.number(at, k));
                    b[k] = insn.replaceBranches(one, x -> insn).getInstructionCounter().getCoveredCount() > 0;
                }
                lines[c] = insn.getLine();
                covered[c] = hit;
                branches[c++] = b;
                int[] s = sums.get(insn.getLine());
                if (s == null) {
                    s = new int[4];
                    sums.put(insn.getLine(), s);
                }
                s[0]++;
                s[1] += hit ? 1 : 0;
                s[2] += n;
                for (boolean x : b) {
                    s[3] += x ? 1 : 0;
                }
            }
            for (Map.Entry<Integer, int[]> e : sums.entrySet()) {
                int l = e.getKey();
                if (l < 1) {
                    continue;
                }
                ILine line = coverage.getLine(l);
                int[] s = e.getValue();
                if (!same(line.getInstructionCounter(), s[0], s[1]) || !same(line.getBranchCounter(), s[2], s[3])) {
                    throw new NotMapped("JaCoCo's coverage of " + getClassName() + "#" + m.name + m.desc
                            + " line " + l + " differs from its instructions: " + Arrays.toString(s) + " against "
                            + line.getInstructionCounter() + " " + line.getBranchCounter());
                }
            }
            m.lines = Arrays.copyOf(lines, c);
            m.covered = Arrays.copyOf(covered, c);
            m.branches = Arrays.copyOf(branches, c);
            m.coverage = coverage;
        }

        private static boolean same(ICounter c, int total, int covered) {
            return c.getTotalCount() == total && c.getCoveredCount() == covered;
        }
    }

    /**
     * Builder is JaCoCo's InstructionsBuilder that also notes the method's probe ids and each instruction's
     * branch numbers, as JaCoCo's builder gives them: 0 to the instruction an instruction falls through to,
     * and the number MethodAnalyzer passes with each jump and probe.
     */
    private static final class Builder extends InstructionsBuilder {
        private final Method method;
        private AbstractInsnNode current;
        private InsnList insns;
        // Each branch number the builder gave an instruction, as index << 32 | number; sorted, de-duplicated
        // and indexed (noteStart: where each instruction's numbers start; one more for the end) on first read.
        private long[] notes = new long[16];
        private int noteCount;
        private int[] noteStart;
        private Map<AbstractInsnNode, Instruction> wired;
        // With flow kept (for map), by index in the method's instruction list: each instruction's fall-through
        // predecessor (-1: none), each jump (from, branch, target label, in the order the builder wires them),
        // the instruction each label is at, and each probe's instruction and branch (in method.probes order).
        private final boolean flow;
        int[] fall;
        int jumps;
        int[] jumpFrom = new int[0];
        int[] jumpBranch = new int[0];
        final List<Label> jumpTarget = new ArrayList<>();
        final Map<Label, Integer> labelAt = new IdentityHashMap<>();
        private final List<Label> pending = new ArrayList<>();
        int[] probeFrom = new int[0];
        int[] probeBranch = new int[0];

        Builder(boolean[] probes, Method method, boolean flow) {
            super(probes);
            this.method = method;
            this.flow = flow;
        }

        void start(InsnList list) {
            insns = list;
            if (flow) {
                fall = new int[list.size()];
                Arrays.fill(fall, -1);
            }
        }

        /** numbers is how many branch numbers the builder gave the instruction at index i. */
        int numbers(int i) {
            index();
            return noteStart[i + 1] - noteStart[i];
        }

        /** number is the x-th of them, ascending. */
        int number(int i, int x) {
            index();
            return (int) notes[noteStart[i] + x];
        }

        private void index() {
            if (noteStart != null) {
                return;
            }
            Arrays.sort(notes, 0, noteCount);
            int w = 0;
            for (int r = 0; r < noteCount; r++) {
                if (w == 0 || notes[r] != notes[w - 1]) {
                    notes[w++] = notes[r];
                }
            }
            noteCount = w;
            noteStart = new int[insns.size() + 1];
            int r = 0;
            for (int i = 0; i < noteStart.length; i++) {
                while (r < noteCount && (int) (notes[r] >>> 32) < i) {
                    r++;
                }
                noteStart[i] = r;
            }
        }

        /** getInstructions wires the jumps once (JaCoCo's builder wires them again on each call). */
        @Override
        Map<AbstractInsnNode, Instruction> getInstructions() {
            if (wired == null) {
                wired = super.getInstructions();
            }
            return wired;
        }

        private void note(int branch) {
            if (current != null) {
                if (noteCount == notes.length) {
                    notes = Arrays.copyOf(notes, noteCount * 2);
                }
                notes[noteCount++] = (long) insns.indexOf(current) << 32 | (branch & 0xffffffffL);
            }
        }

        @Override
        void addLabel(Label label) {
            if (flow) {
                pending.add(label);
            }
            super.addLabel(label);
        }

        @Override
        void addInstruction(AbstractInsnNode node) {
            note(0);
            super.addInstruction(node);
            if (flow) {
                int at = insns.indexOf(node);
                fall[at] = current == null ? -1 : insns.indexOf(current);
                for (Label l : pending) {
                    labelAt.put(l, at);
                }
                pending.clear();
            }
            current = node;
        }

        @Override
        void noSuccessor() {
            super.noSuccessor();
            current = null;
        }

        @Override
        void addJump(Label target, int branch) {
            note(branch);
            super.addJump(target, branch);
            if (flow) {
                if (jumps == jumpFrom.length) {
                    jumpFrom = Arrays.copyOf(jumpFrom, Math.max(8, jumps * 2));
                    jumpBranch = Arrays.copyOf(jumpBranch, jumpFrom.length);
                }
                jumpFrom[jumps] = current == null ? -1 : insns.indexOf(current);
                jumpBranch[jumps++] = branch;
                jumpTarget.add(target);
            }
        }

        @Override
        void addProbe(int probeId, int branch) {
            if (flow) {
                int r = method.probeCount;
                if (r == probeFrom.length) {
                    probeFrom = Arrays.copyOf(probeFrom, Math.max(4, r * 2));
                    probeBranch = Arrays.copyOf(probeBranch, probeFrom.length);
                }
                probeFrom[r] = current == null ? -1 : insns.indexOf(current);
                probeBranch[r] = branch;
            }
            method.addProbe(probeId);
            note(branch);
            super.addProbe(probeId, branch);
        }
    }

    /**
     * Output keeps what the filters said of one method, in their order, to be given to the coverage calculator
     * of each analysis of that method: the filters read only the method's code and the class, never coverage.
     */
    private static final class Output implements IFilterOutput {
        private final List<Consumer<IFilterOutput>> calls = new ArrayList<>();
        final Set<AbstractInsnNode> ignored = new HashSet<>();
        private final Map<AbstractInsnNode, AbstractInsnNode> merged = new HashMap<>();
        // Instructions a filter replaced the branches of, with how many new branches each has, and the
        // replacements themselves.
        final Map<AbstractInsnNode, Integer> replaced = new HashMap<>();
        final Map<AbstractInsnNode, Replacements> replacements = new HashMap<>();

        void applyTo(IFilterOutput calculator) {
            for (Consumer<IFilterOutput> call : calls) {
                call.accept(calculator);
            }
        }

        @Override
        public void ignore(AbstractInsnNode fromInclusive, AbstractInsnNode toInclusive) {
            calls.add(c -> c.ignore(fromInclusive, toInclusive));
            for (AbstractInsnNode i = fromInclusive; i != toInclusive; i = i.getNext()) {
                ignored.add(i);
            }
            ignored.add(toInclusive);
        }

        @Override
        public void merge(AbstractInsnNode i1, AbstractInsnNode i2) {
            final AbstractInsnNode a = i1;
            final AbstractInsnNode b = i2;
            calls.add(c -> c.merge(a, b));
            i1 = representative(i1);
            i2 = representative(i2);
            if (i1 != i2) {
                merged.put(i2, i1);
                ignored.add(i2);
            }
        }

        private AbstractInsnNode representative(AbstractInsnNode i) {
            AbstractInsnNode r;
            while ((r = merged.get(i)) != null) {
                i = r;
            }
            return i;
        }

        @Override
        public void replaceBranches(AbstractInsnNode source, Replacements replacements) {
            calls.add(c -> c.replaceBranches(source, replacements));
            int n = 0;
            for (Object ignoredValue : replacements.values()) {
                n++;
            }
            replaced.put(source, n);
            this.replacements.put(source, replacements);
        }
    }
}
