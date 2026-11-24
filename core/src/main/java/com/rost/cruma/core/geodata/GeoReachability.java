package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoQueryEngine.Completeness;
import com.rost.cruma.core.geodata.GeoQueryEngine.Direction;
import com.rost.cruma.core.geodata.GeoQueryEngine.Selection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class GeoReachability {
    private final GeoDataAccess data;
    private final GeoQueryEngine engine;
    private final LongSupplier clock;

    public GeoReachability(GeoDataAccess data) {
        this(data, System::nanoTime);
    }

    GeoReachability(GeoDataAccess data, LongSupplier clock) {
        this.data = data;
        engine = new GeoQueryEngine(data);
        this.clock = clock;
    }

    public enum Access {
        MUTUAL,
        OUTBOUND_ONLY,
        INBOUND_ONLY
    }

    public enum FrontierReason {
        RADIUS,
        UNAVAILABLE,
        NODE_LIMIT,
        TIME_LIMIT,
        CANCELLED,
        SOURCE_CHANGED
    }

    public record Frontier(Node from, int x, int y, boolean reverse, FrontierReason reason) {}

    public record Node(int x, int y, int layer, int height) {
        public static Node of(Selection selection) {
            return new Node(
                    selection.cell().x(),
                    selection.cell().y(),
                    selection.layer().index(),
                    selection.layer().height());
        }

        public GeoPoint center() {
            return new GeoPoint(x + 8, y + 8, height);
        }
    }

    public record Limits(int radius, int maxNodes, long maxMillis, BooleanSupplier cancelled) {
        public Limits(int radius, int maxNodes, BooleanSupplier cancelled) {
            this(radius, maxNodes, 5000, cancelled);
        }

        public Limits {
            if (radius < 16 || radius > 32768 || maxNodes < 1 || maxMillis < 1 || maxMillis > 600_000) {
                throw new IllegalArgumentException("Invalid reachability limits");
            }
            if (cancelled == null) {
                throw new IllegalArgumentException("Cancellation is required");
            }
        }
    }

    public record Result(
            Map<Node, Access> nodes,
            Completeness completeness,
            int outgoingCount,
            int incomingCount,
            int unavailableCells,
            boolean boundaryReached,
            String sourceId,
            long revision,
            List<Frontier> frontier,
            boolean frontierTruncated) {
        public Result {
            nodes = Map.copyOf(nodes);
            frontier = List.copyOf(frontier);
        }
    }

    public Result explore(GeoPoint origin, Limits limits) {
        var search = new Search(origin, limits);
        var selected = engine.findBaseCell(origin);
        if (selected == null) {
            var cell = data.cellAt(GeoQueryEngine.serverInt(origin.x()), GeoQueryEngine.serverInt(origin.y()));
            search.completeness = cell.availability() == Availability.AVAILABLE
                    ? Completeness.COMPLETE
                    : Completeness.DATA_UNAVAILABLE;
            if (search.completeness == Completeness.DATA_UNAVAILABLE) {
                search.frontier.add(new Frontier(
                        new Node(cell.x(), cell.y(), -1, GeoQueryEngine.serverInt(origin.z())),
                        cell.x(),
                        cell.y(),
                        false,
                        FrontierReason.UNAVAILABLE));
            }
            return search.createResult(Set.of(), Set.of());
        }
        var outgoing = search.flood(selected, false);
        var incoming = search.flood(selected, true);
        return search.createResult(outgoing, incoming);
    }

    public Result explore(GeoCellRef origin, Limits limits) {
        if (!origin.belongsTo(data)) {
            throw new IllegalArgumentException("Surface selection belongs to a different data revision");
        }
        var cell = data.cellAt(origin.x(), origin.y());
        if (cell.x() != origin.x() || cell.y() != origin.y() || cell.availability() != Availability.AVAILABLE) {
            throw new IllegalArgumentException("Selected surface cell is unavailable or misaligned");
        }
        for (var layer : cell.layers()) {
            if (layer.index() == origin.layer()) {
                var selected = new Selection(cell, layer);
                var search = new Search(selected.center(), limits);
                var outgoing = search.flood(selected, false);
                var incoming = search.flood(selected, true);
                return search.createResult(outgoing, incoming);
            }
        }
        throw new IllegalArgumentException("Selected surface layer is unavailable");
    }

    private final class Search {
        private final GeoPoint origin;
        private final Limits limits;
        private final long revision = data.revision();
        private final String sourceId = data.getSourceId();
        private final HashSet<Long> unavailable = new HashSet<>();
        private Completeness completeness = Completeness.COMPLETE;
        private boolean boundary;
        private final ArrayList<Frontier> frontier = new ArrayList<>();
        private boolean frontierTruncated;
        private final long started = clock.getAsLong();
        private FrontierReason stopReason;

        Search(GeoPoint origin, Limits limits) {
            this.origin = origin;
            this.limits = limits;
        }

        Set<Node> flood(Selection seed, boolean reverse) {
            var visited = new HashSet<Node>();
            var queue = new ArrayDeque<Selection>();
            visited.add(Node.of(seed));
            queue.add(seed);
            while (!queue.isEmpty() && !isStopped()) {
                var current = queue.removeFirst();
                for (var direction : Direction.values()) {
                    if (!reverse && (current.layer().nswe() & direction.getMask()) == 0) {
                        continue;
                    }
                    var x = current.cell().x() + direction.getDx();
                    var y = current.cell().y() + direction.getDy();
                    if (Math.abs(x + 8 - origin.x()) > limits.radius()
                            || Math.abs(y + 8 - origin.y()) > limits.radius()) {
                        boundary = true;
                        frontier(current, x, y, reverse, FrontierReason.RADIUS);
                        if (completeness == Completeness.COMPLETE) {
                            completeness = Completeness.LIMIT;
                        }
                        continue;
                    }
                    var neighbor = data.cellAt(x + 8, y + 8);
                    if (neighbor.availability() != Availability.AVAILABLE) {
                        unavailable.add((long) x << 32 | (y & 0xffffffffL));
                        completeness = Completeness.DATA_UNAVAILABLE;
                        frontier(current, x, y, reverse, FrontierReason.UNAVAILABLE);
                        continue;
                    }
                    if (reverse) {
                        var checked = 0;
                        for (var layer : neighbor.layers()) {
                            if ((checked++ & 63) == 0 && isStopped()) {
                                frontier(current, x, y, true, stopReason);
                                queuedFrontier(queue, true, stopReason);
                                return visited;
                            }
                            var candidate = new Selection(neighbor, layer);
                            var edge = engine.computeTransition(candidate, direction.opposite());
                            if (edge.accepted()
                                    && GeoQueryEngine.isSameCell(edge.to(), current)
                                    && !enqueue(candidate, visited, queue)) {
                                frontier(current, x, y, reverse, FrontierReason.NODE_LIMIT);
                                queuedFrontier(queue, reverse, FrontierReason.NODE_LIMIT);
                                return visited;
                            }
                        }
                    } else {
                        var edge = engine.computeTransition(current, direction);
                        if (edge.accepted() && !enqueue(edge.to(), visited, queue)) {
                            frontier(current, x, y, reverse, FrontierReason.NODE_LIMIT);
                            queuedFrontier(queue, reverse, FrontierReason.NODE_LIMIT);
                            return visited;
                        }
                    }
                }
            }
            if (!queue.isEmpty()) {
                queuedFrontier(queue, reverse, stopReason);
            }
            return visited;
        }

        boolean enqueue(Selection candidate, Set<Node> visited, ArrayDeque<Selection> queue) {
            var node = Node.of(candidate);
            if (visited.contains(node)) {
                return true;
            }
            if (visited.size() >= limits.maxNodes()) {
                completeness = Completeness.LIMIT;
                return false;
            }
            visited.add(node);
            queue.add(candidate);
            return true;
        }

        boolean isStopped() {
            if (limits.cancelled().getAsBoolean() || Thread.currentThread().isInterrupted()) {
                completeness = Completeness.CANCELLED;
                stopReason = FrontierReason.CANCELLED;
                return true;
            }
            if (revision != data.revision() || !sourceId.equals(data.getSourceId())) {
                completeness = Completeness.SOURCE_CHANGED;
                stopReason = FrontierReason.SOURCE_CHANGED;
                return true;
            }
            if (clock.getAsLong() - started >= limits.maxMillis() * 1_000_000L) {
                completeness = Completeness.LIMIT;
                stopReason = FrontierReason.TIME_LIMIT;
                return true;
            }
            return false;
        }

        void frontier(Selection from, int x, int y, boolean reverse, FrontierReason reason) {
            if (frontier.size() < 8192) {
                frontier.add(new Frontier(Node.of(from), x, y, reverse, reason));
            } else {
                frontierTruncated = true;
            }
        }

        void queuedFrontier(ArrayDeque<Selection> queue, boolean reverse, FrontierReason reason) {
            for (var pending : queue) {
                frontier(pending, pending.cell().x(), pending.cell().y(), reverse, reason);
            }
        }

        Result createResult(Set<Node> outgoing, Set<Node> incoming) {
            isStopped();
            var nodes = new LinkedHashMap<Node, Access>();
            for (var node : outgoing) {
                nodes.put(node, incoming.contains(node) ? Access.MUTUAL : Access.OUTBOUND_ONLY);
            }
            for (var node : incoming) {
                nodes.putIfAbsent(node, Access.INBOUND_ONLY);
            }
            return new Result(
                    nodes,
                    completeness,
                    outgoing.size(),
                    incoming.size(),
                    unavailable.size(),
                    boundary,
                    sourceId,
                    revision,
                    frontier,
                    frontierTruncated);
        }
    }
}
