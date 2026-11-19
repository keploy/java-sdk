package org.jacoco.core.internal.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ILine;
import org.jacoco.core.internal.analysis.filter.Filters;
import org.jacoco.core.internal.analysis.filter.IFilter;
import org.jacoco.core.internal.analysis.filter.IFilterOutput;
import org.jacoco.core.internal.analysis.filter.Replacements;
import org.jacoco.core.internal.flow.ClassProbesAdapter;
import org.jacoco.core.internal.flow.IProbeIdGenerator;
import org.jacoco.core.internal.flow.MethodProbesAdapter;
import org.jacoco.core.internal.flow.MethodProbesVisitor;
import org.jacoco.core.internal.instr.InstrSupport;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * KeployBranchCoverage runs JaCoCo's own analysis of one class (ClassAnalyzer's steps for each method: the
 * instruction graph, the filters, the merges and the branch replacements) and keeps what JaCoCo's public
 * coverage API sums away: which counted instructions are covered and which branch of each one is. Keploy's
 * build table uses it to map each probe to the lines and branch edges that a hit of that probe covers.
 *
 * <p>It lives in JaCoCo's package so that it runs JaCoCo's package-private analysis classes as they are: the
 * result is JaCoCo's own, not a copy of its rules. Each method's per-line sums are checked against the
 * coverage JaCoCo computes for it in the same pass, so a mismatch throws instead of mapping a probe wrong.
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

    /** What {@link #analyze(byte[], Map, EachProbe)} hands over for each probe of each method with code. */
    public interface EachProbe {
        /**
         * probe gets method (as analyze with no probe executed puts it in out), the index r of one of its
         * probes in method.probes, and the method's coverage when that probe alone was executed.
         */
        void probe(Method method, int r, Method got);

        /**
         * wants says whether method's probes are replayed at all: a method it declines costs no replay, and
         * probe gets none of its probes. Every method with code is wanted unless this is overridden.
         */
        default boolean wants(Method method) {
            return true;
        }
    }

    /**
     * analyze is {@link #analyze(byte[], boolean[], Map)} with no probe executed, and it also hands each
     * probe's own coverage to each, method by method, as a class analysis with only that probe executed gives
     * it. It parses the class and runs the filters once: each probe replays only its method's instructions
     * through JaCoCo's probe adapter, instruction builder and coverage calculator, with the filters' output of
     * that one pass. So a class costs, summed over its methods, each method's probes times that method's size,
     * not the whole class once per probe. A replay that meets other probes than the pass did throws.
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
            final Builder builder = new Builder(probes, m);
            return new MethodAnalyzer(builder) {
                @Override
                public void accept(MethodNode node, MethodVisitor visitor) {
                    super.accept(node, visitor);
                    collect(m, builder, node, signature);
                }
            };
        }

        private void collect(Method m, Builder builder, MethodNode node, String signature) {
            Output output = new Output();
            filter.filter(node, this, output);
            if (!fill(m, builder, node, signature, output)) {
                return;
            }
            out.put(m.name + m.desc, m);
            if (each != null && each.wants(m)) {
                replay(m, node, signature, output);
            }
        }

        /**
         * replay hands each of m's probes to each with m's coverage when that probe alone is executed: the
         * method's node again through a new probe adapter (its probe ids from the method's first) and analyzer,
         * and the filters' output of the pass that made m.
         */
        private void replay(Method m, MethodNode node, String signature, Output output) {
            if (m.probes.length == 0) {
                return;
            }
            int first = Integer.MAX_VALUE;
            int last = -1;
            for (int id : m.probes) {
                first = Math.min(first, id);
                last = Math.max(last, id);
            }
            boolean[] one = new boolean[last + 1];
            for (int r = 0; r < m.probes.length; r++) {
                one[m.probes[r]] = true;
                Method got = new Method(m.name, m.desc);
                Builder builder = new Builder(one, got);
                MethodAnalyzer analyzer = new MethodAnalyzer(builder);
                analyzer.accept(node, new MethodProbesAdapter(analyzer, new Ids(first)));
                one[m.probes[r]] = false;
                if (!fill(got, builder, node, signature, output) || !Arrays.equals(got.probes, m.probes)) {
                    throw new IllegalStateException("JaCoCo's replay of " + getClassName() + "#" + m.name + m.desc
                            + " met probes " + Arrays.toString(got.probes) + ", its analysis "
                            + Arrays.toString(m.probes));
                }
                each.probe(m, r, got);
            }
        }

        /**
         * fill sets m's counted instructions from builder's graph and the filters' output, as ClassAnalyzer
         * computes a method's coverage. It returns false for a method without code.
         */
        private boolean fill(Method m, Builder builder, MethodNode node, String signature, Output output) {
            Map<AbstractInsnNode, Instruction> instructions = builder.getInstructions();
            MethodCoverageCalculator calculator = new MethodCoverageCalculator(instructions);
            output.applyTo(calculator);
            MethodCoverageImpl coverage = new MethodCoverageImpl(m.name, m.desc, signature);
            calculator.calculate(coverage);
            if (!coverage.containsCode()) {
                return false;
            }
            int[] p = new int[m.probeCount];
            System.arraycopy(m.probes, 0, p, 0, m.probeCount);
            m.probes = p;
            List<Integer> lines = new ArrayList<>();
            List<Boolean> covered = new ArrayList<>();
            List<boolean[]> branches = new ArrayList<>();
            Map<Integer, int[]> sums = new HashMap<>(); // line -> instructions, covered, branches, covered
            for (AbstractInsnNode i = node.instructions.getFirst(); i != null; i = i.getNext()) {
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
                Set<Integer> numbers = replaced != null ? range(replaced) : builder.branches.get(i);
                if (n > 0 && (numbers == null || numbers.size() != n)) {
                    throw new IllegalStateException("JaCoCo counts " + n + " branches at " + getClassName() + "#"
                            + m.name + m.desc + " line " + insn.getLine() + ", its builder gave " + numbers);
                }
                boolean[] b = new boolean[n];
                int k = 0;
                for (int number : n > 0 ? numbers : new TreeSet<Integer>()) {
                    // replaceBranches with one new branch fed by this branch alone is covered exactly when the
                    // branch is: JaCoCo's own way to read one branch.
                    Replacements one = new Replacements();
                    one.add(i, i, number);
                    b[k++] = insn.replaceBranches(one, x -> insn).getInstructionCounter().getCoveredCount() > 0;
                }
                lines.add(insn.getLine());
                covered.add(hit);
                branches.add(b);
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
                    throw new IllegalStateException("JaCoCo's coverage of " + getClassName() + "#" + m.name + m.desc
                            + " line " + l + " differs from its instructions: " + Arrays.toString(s) + " against "
                            + line.getInstructionCounter() + " " + line.getBranchCounter());
                }
            }
            m.lines = new int[lines.size()];
            m.covered = new boolean[lines.size()];
            m.branches = new boolean[lines.size()][];
            for (int k = 0; k < lines.size(); k++) {
                m.lines[k] = lines.get(k);
                m.covered[k] = covered.get(k);
                m.branches[k] = branches.get(k);
            }
            return true;
        }

        private static boolean same(ICounter c, int total, int covered) {
            return c.getTotalCount() == total && c.getCoveredCount() == covered;
        }

        private static Set<Integer> range(int n) {
            Set<Integer> out = new TreeSet<>();
            for (int k = 0; k < n; k++) {
                out.add(k);
            }
            return out;
        }
    }

    /**
     * Builder is JaCoCo's InstructionsBuilder that also notes the method's probe ids and each instruction's
     * branch numbers, as JaCoCo's builder gives them: 0 to the instruction an instruction falls through to,
     * and the number MethodAnalyzer passes with each jump and probe.
     */
    private static final class Builder extends InstructionsBuilder {
        private final Method method;
        final Map<AbstractInsnNode, Set<Integer>> branches = new HashMap<>();
        private AbstractInsnNode current;

        Builder(boolean[] probes, Method method) {
            super(probes);
            this.method = method;
        }

        private void note(int branch) {
            if (current != null) {
                Set<Integer> s = branches.get(current);
                if (s == null) {
                    s = new TreeSet<>();
                    branches.put(current, s);
                }
                s.add(branch);
            }
        }

        @Override
        void addInstruction(AbstractInsnNode node) {
            note(0);
            super.addInstruction(node);
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
        }

        @Override
        void addProbe(int probeId, int branch) {
            method.addProbe(probeId);
            note(branch);
            super.addProbe(probeId, branch);
        }
    }

    /** Ids counts probe ids up from a method's first, as ClassProbesAdapter counted them for the class. */
    private static final class Ids implements IProbeIdGenerator {
        private int next;

        Ids(int first) {
            next = first;
        }

        @Override
        public int nextId() {
            return next++;
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
        // Instructions a filter replaced the branches of, with how many new branches each has.
        final Map<AbstractInsnNode, Integer> replaced = new HashMap<>();

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
        }
    }
}
