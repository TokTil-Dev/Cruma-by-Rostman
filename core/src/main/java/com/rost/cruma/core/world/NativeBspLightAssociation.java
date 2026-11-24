package com.rost.cruma.core.world;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class NativeBspLightAssociation {
    private final int[] references;
    private final long[][] surfaces;
    private final long[] root;
    private final long candidateChecks;

    private NativeBspLightAssociation(int[] references, long[][] surfaces, long[] root, long candidateChecks) {
        this.references = references;
        this.surfaces = surfaces;
        this.root = root;
        this.candidateChecks = candidateChecks;
    }

    public record Node(int surface, int front, int back, int coplanar, int frontLeaf, int backLeaf) {}

    @FunctionalInterface
    public interface CandidateFilter {
        boolean mayReceive(int nodeIndex, int lightReference) throws IOException;
    }

    public static NativeBspLightAssociation capture(
            List<Node> nodes, boolean[] twoSided, int[] leafStarts, int[] nativeLights, BooleanSupplier cancelled)
            throws IOException {
        return capture(nodes, twoSided, leafStarts, nativeLights, cancelled, 8_388_608, 100_000_000);
    }

    public static NativeBspLightAssociation capture(
            List<Node> nodes,
            boolean[] twoSided,
            int[] leafStarts,
            int[] nativeLights,
            CandidateFilter filter,
            BooleanSupplier cancelled)
            throws IOException {
        return capture(nodes, twoSided, leafStarts, nativeLights, filter, cancelled, 8_388_608, 100_000_000);
    }

    static NativeBspLightAssociation capture(
            List<Node> nodes,
            boolean[] twoSided,
            int[] leafStarts,
            int[] nativeLights,
            BooleanSupplier cancelled,
            long maximumWords,
            long maximumWork)
            throws IOException {
        return new Builder(nodes, twoSided, leafStarts, nativeLights, null, cancelled, maximumWords, maximumWork)
                .build();
    }

    static NativeBspLightAssociation capture(
            List<Node> nodes,
            boolean[] twoSided,
            int[] leafStarts,
            int[] nativeLights,
            CandidateFilter filter,
            BooleanSupplier cancelled,
            long maximumWords,
            long maximumWork)
            throws IOException {
        return new Builder(
                        nodes,
                        twoSided,
                        leafStarts,
                        nativeLights,
                        Objects.requireNonNull(filter),
                        cancelled,
                        maximumWords,
                        maximumWork)
                .build();
    }

    public long getCandidateChecks() {
        return candidateChecks;
    }

    public boolean mayReceive(int surface, int reference) {
        if (surface < 0 || surface >= surfaces.length) {
            throw new IllegalArgumentException("Invalid BSP surface");
        }
        var index = Arrays.binarySearch(references, reference);
        return index >= 0 && surfaces[surface] != null && (surfaces[surface][index >>> 6] & (1L << index)) != 0;
    }

    public List<Integer> getSurfaceReferences(int surface) {
        if (surface < 0 || surface >= surfaces.length) {
            throw new IllegalArgumentException("Invalid BSP surface");
        }
        return listMembers(surfaces[surface]);
    }

    public List<Integer> getRootReferences() {
        return listMembers(root);
    }

    private List<Integer> listMembers(long[] bits) {
        var result = new ArrayList<Integer>();
        if (bits != null) {
            for (int i = 0; i < references.length; i++) {
                if ((bits[i >>> 6] & (1L << i)) != 0) {
                    result.add(references[i]);
                }
            }
        }
        return List.copyOf(result);
    }

    private static final class Frame {
        final int node;
        int stage;
        long[] front, back;

        Frame(int node) {
            this.node = node;
        }
    }

    private static final class Builder {
        final List<Node> nodes;
        final boolean[] twoSided;
        final int[] starts, lights, references;
        final CandidateFilter filter;
        final BooleanSupplier cancelled;
        final long maximumWords, maximumWork;
        final int words;
        final long[][] surfaces;
        final boolean[] entered, emitted;
        final ArrayDeque<long[]> pool = new ArrayDeque<>();
        final ArrayDeque<Frame> stack = new ArrayDeque<>();
        long allocatedWords, work, visits, candidateChecks;

        Builder(
                List<Node> nodes,
                boolean[] twoSided,
                int[] starts,
                int[] lights,
                CandidateFilter filter,
                BooleanSupplier cancelled,
                long maximumWords,
                long maximumWork)
                throws IOException {
            this.cancelled = Objects.requireNonNull(cancelled);
            this.filter = filter;
            this.maximumWords = maximumWords;
            this.maximumWork = maximumWork;
            if (maximumWords < 0 || maximumWork < 0) {
                throw new IllegalArgumentException("Negative BSP association budget");
            }
            check();
            if (nodes.size() > 1_000_000
                    || twoSided.length > 1_000_000
                    || starts.length > 1_000_000
                    || lights.length > 2_000_000) {
                throw new IOException("BSP association input budget exhausted");
            }
            this.nodes = List.copyOf(nodes);
            this.twoSided = twoSided.clone();
            this.starts = starts.clone();
            this.lights = lights.clone();

            var terminated = false;
            var suffix = new boolean[this.lights.length];
            for (int i = this.lights.length - 1; i >= 0; i--) {
                charge(1);
                if (this.lights[i] == 0) {
                    terminated = true;
                }
                suffix[i] = terminated;
            }
            for (var start : this.starts) {
                charge(1);
                if (start != -1 && (start < 0 || start >= this.lights.length || !suffix[start])) {
                    throw new IOException("Invalid or unterminated BSP leaf light list");
                }
            }
            for (var node : this.nodes) {
                charge(1);
                index(node.surface, this.twoSided.length, false);
                index(node.front, this.nodes.size(), true);
                index(node.back, this.nodes.size(), true);
                index(node.coplanar, this.nodes.size(), true);
                index(node.frontLeaf, this.starts.length, true);
                index(node.backLeaf, this.starts.length, true);
            }
            var sorted = this.lights.clone();
            charge((long) sorted.length * (32 - Integer.numberOfLeadingZeros(Math.max(1, sorted.length))));
            Arrays.sort(sorted);
            var distinct = 0;
            for (var reference : sorted) {
                if (reference != 0 && (distinct == 0 || reference != sorted[distinct - 1])) {
                    sorted[distinct++] = reference;
                }
            }
            references = Arrays.copyOf(sorted, distinct);
            words = (references.length + 63) >>> 6;
            surfaces = new long[this.twoSided.length][];
            entered = new boolean[this.nodes.size()];
            emitted = new boolean[this.nodes.size()];
        }

        static void index(int value, int length, boolean none) throws IOException {
            if (value < (none ? -1 : 0) || value >= length) {
                throw new IOException("Invalid BSP association topology reference");
            }
        }

        NativeBspLightAssociation build() throws IOException {
            if (nodes.isEmpty()) {
                check();
                return new NativeBspLightAssociation(references, surfaces, null, candidateChecks);
            }
            push(0);
            long[] root = null;
            while (!stack.isEmpty()) {
                check();
                var frame = stack.peek();
                var node = nodes.get(frame.node);
                if (frame.stage == 0) {
                    frame.stage = 1;
                    if (node.frontLeaf >= 0) {
                        frame.front = buildLeaf(node.frontLeaf);
                    } else if (node.front >= 0) {
                        push(node.front);
                        continue;
                    }
                }
                if (frame.stage == 1) {
                    frame.stage = 2;
                    if (node.backLeaf >= 0) {
                        frame.back = buildLeaf(node.backLeaf);
                    } else if (node.back >= 0) {
                        push(node.back);
                        continue;
                    }
                }
                for (int coplanar = frame.node; coplanar != -1; coplanar = nodes.get(coplanar).coplanar) {
                    visit();
                    if (emitted[coplanar] || (coplanar != frame.node && entered[coplanar])) {
                        throw new IOException("Cyclic or shared BSP association topology");
                    }
                    emitted[coplanar] = true;
                    var surface = nodes.get(coplanar).surface;
                    add(coplanar, frame.front);
                    if (twoSided[surface]) {
                        add(coplanar, frame.back);
                    }
                }
                var union = merge(frame.front, frame.back);
                stack.pop();
                if (stack.isEmpty()) {
                    root = union;
                } else if (stack.peek().stage == 1) {
                    stack.peek().front = union;
                } else {
                    stack.peek().back = union;
                }
            }
            check();
            return new NativeBspLightAssociation(references, surfaces, root, candidateChecks);
        }

        void push(int node) throws IOException {
            visit();
            if (entered[node] || emitted[node]) {
                throw new IOException("Cyclic or shared BSP association topology");
            }
            entered[node] = true;
            stack.push(new Frame(node));
        }

        void visit() throws IOException {
            charge(1);
            if (++visits > 2L * nodes.size()) {
                throw new IOException("BSP association topology traversal budget exhausted");
            }
        }

        long[] buildLeaf(int leaf) throws IOException {
            var start = starts[leaf];
            if (start == -1 || lights[start] == 0) {
                return null;
            }
            var bits = allocate();
            for (int i = start; lights[i] != 0; i++) {
                charge(1);
                var index = Arrays.binarySearch(references, lights[i]);
                bits[index >>> 6] |= 1L << index;
            }
            return bits;
        }

        void add(int node, long[] bits) throws IOException {
            if (bits == null) {
                return;
            }
            var surface = nodes.get(node).surface;
            if (filter != null) {
                addFiltered(node, surface, bits);
                return;
            }
            if (surfaces[surface] == null) {
                surfaces[surface] = allocate();
            }
            charge(words);
            for (int i = 0; i < words; i++) {
                surfaces[surface][i] |= bits[i];
            }
        }

        void addFiltered(int node, int surface, long[] bits) throws IOException {
            charge(words);
            for (int word = 0; word < words; word++) {
                var remaining = bits[word];
                while (remaining != 0) {
                    var bit = Long.numberOfTrailingZeros(remaining);
                    charge(1);
                    candidateChecks++;
                    var accepted = filter.mayReceive(node, references[(word << 6) + bit]);
                    check();
                    if (accepted) {
                        if (surfaces[surface] == null) {
                            surfaces[surface] = allocate();
                        }
                        surfaces[surface][word] |= 1L << bit;
                    }
                    remaining &= remaining - 1;
                }
            }
        }

        long[] merge(long[] front, long[] back) throws IOException {
            if (front == null) {
                return back;
            }
            if (back == null) {
                return front;
            }
            charge(words);
            for (int i = 0; i < words; i++) {
                front[i] |= back[i];
            }
            charge(words);
            Arrays.fill(back, 0);
            pool.add(back);
            return front;
        }

        long[] allocate() throws IOException {
            charge(words);
            if (!pool.isEmpty()) {
                return pool.remove();
            }
            if (words > maximumWords - allocatedWords) {
                throw new IOException("BSP association retained-memory budget exhausted");
            }
            allocatedWords += words;
            return new long[words];
        }

        void charge(long amount) throws IOException {
            check();
            if (amount > maximumWork - work) {
                throw new IOException("BSP association work budget exhausted");
            }
            work += amount;
        }

        void check() {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("BSP light association capture cancelled");
            }
        }
    }
}
