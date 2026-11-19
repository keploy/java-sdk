package org.jacoco.core.internal.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ILine;
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
     * each method that has code into out, keyed by name + descriptor. With only non-null, only the methods
     * named there are analyzed (the others still take their probe ids, as JaCoCo's analyzer does). It returns
     * the class's probe count, or -1 for a class JaCoCo's Analyzer does not analyze (module-info,
     * package-info). It throws when the bytes are not a class JaCoCo reads, or when a method's per-line sums
     * differ from JaCoCo's coverage of it.
     */
    public static int analyze(byte[] bytes, boolean[] probes, Set<String> only, Map<String, Method> out) {
        ClassReader reader = InstrSupport.classReaderFor(bytes);
        if ((reader.getAccess() & Opcodes.ACC_MODULE) != 0 || reader.getClassName().endsWith("/package-info")) {
            return -1;
        }
        Visitor v = new Visitor(reader.getClassName(), probes, only, out);
        reader.accept(new ClassProbesAdapter(v, false), 0);
        return v.total;
    }

    private static final class Visitor extends ClassAnalyzer {
        private final boolean[] probes;
        private final Set<String> only;
        private final Map<String, Method> out;
        private final IFilter filter = Filters.all();
        int total;

        Visitor(String name, boolean[] probes, Set<String> only, Map<String, Method> out) {
            super(new ClassCoverageImpl(name, 0, false), probes, new StringPool());
            this.probes = probes;
            this.only = only;
            this.out = out;
        }

        @Override
        public void visitTotalProbeCount(int count) {
            super.visitTotalProbeCount(count);
            total = count;
        }

        @Override
        public MethodProbesVisitor visitMethod(int access, final String name, final String desc,
                final String signature, String[] exceptions) {
            // Leaving a method out keeps the others' results only while JaCoCo's filters carry nothing from one
            // method to the next that changes them. One does: KotlinSerializableFilter keeps the line of a
            // $Companion's <init>()V for serializer(). That method has one probe, so it is only ever mapped in
            // the first round, which analyzes every method (only is null). Any other difference fails the
            // class: the caller checks each method's lines and branches against the table in every round.
            if (only != null && !only.contains(name + desc)) {
                return null;
            }
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
            Map<AbstractInsnNode, Instruction> instructions = builder.getInstructions();
            MethodCoverageCalculator calculator = new MethodCoverageCalculator(instructions);
            Output output = new Output(calculator);
            filter.filter(node, this, output);
            MethodCoverageImpl coverage = new MethodCoverageImpl(m.name, m.desc, signature);
            calculator.calculate(coverage);
            if (!coverage.containsCode()) {
                return;
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
            out.put(m.name + m.desc, m);
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

    /**
     * Output passes the filters' decisions on to JaCoCo's calculator and keeps the instructions it leaves out:
     * the ignored ones and those merged into another (the calculator's own rule, findRepresentative).
     */
    private static final class Output implements IFilterOutput {
        private final MethodCoverageCalculator calculator;
        final Set<AbstractInsnNode> ignored = new HashSet<>();
        private final Map<AbstractInsnNode, AbstractInsnNode> merged = new HashMap<>();
        // Instructions a filter replaced the branches of, with how many new branches each has.
        final Map<AbstractInsnNode, Integer> replaced = new HashMap<>();

        Output(MethodCoverageCalculator calculator) {
            this.calculator = calculator;
        }

        @Override
        public void ignore(AbstractInsnNode fromInclusive, AbstractInsnNode toInclusive) {
            calculator.ignore(fromInclusive, toInclusive);
            for (AbstractInsnNode i = fromInclusive; i != toInclusive; i = i.getNext()) {
                ignored.add(i);
            }
            ignored.add(toInclusive);
        }

        @Override
        public void merge(AbstractInsnNode i1, AbstractInsnNode i2) {
            calculator.merge(i1, i2);
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
            calculator.replaceBranches(source, replacements);
            int n = 0;
            for (Object ignoredValue : replacements.values()) {
                n++;
            }
            replaced.put(source, n);
        }
    }
}
