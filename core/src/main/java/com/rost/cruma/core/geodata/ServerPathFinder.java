package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoQueryEngine.Completeness;
import com.rost.cruma.core.geodata.GeoQueryEngine.Limits;
import com.rost.cruma.core.geodata.GeoQueryEngine.MoveResult;
import com.rost.cruma.core.geodata.PathNodeGraph.Node;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.eclipse.collections.impl.map.mutable.primitive.IntDoubleHashMap;

public final class ServerPathFinder {
    public static final int SERVER_VISIT_POOL = 4200;
    private final PathNodeGraph graph;
    private final GeoDataAccess data;
    private final Movement movement;

    public ServerPathFinder(PathNodeGraph graph, GeoQueryEngine queries, GeoDataAccess data) {
        this(
                graph,
                data,
                (source, destination, cancelled) ->
                        queries.moveStraight(source, destination, 8192.0, new Limits(65536, 0, cancelled)));
    }

    public ServerPathFinder(PathNodeGraph graph, GeoDataAccess data) {
        this(graph, new GeoQueryEngine(data), data);
    }

    ServerPathFinder(PathNodeGraph graph, GeoDataAccess data, Movement movement) {
        this.graph = graph;
        this.data = Objects.requireNonNull(data);
        this.movement = Objects.requireNonNull(movement);
    }

    public enum Status {
        CAN_GO,
        PATH_FOUND,
        NO_START_ANCHOR,
        NO_DESTINATION_ANCHOR,
        NO_PATH,
        VISIT_LIMIT,
        VISIT_POOL_LIMIT,
        QUERY_LIMIT,
        DATA_UNAVAILABLE,
        GRAPH_MISSING,
        INVALID_GRAPH,
        STALE_GRAPH,
        CANCELLED,
        SOURCE_CHANGED
    }

    public enum Phase {
        DIRECT,
        START_ANCHOR,
        DESTINATION_ANCHOR,
        VISIT,
        SHORTCUT,
        EDGE
    }

    public enum ReplayStatus {
        REACHED_DESTINATION,
        STOPPED,
        WAYPOINT_LIMIT,
        CYCLE_DETECTED
    }

    public record Options(
            int maximumVisits, int maximumTrace, boolean graphStale, int visitPool, BooleanSupplier cancelled) {
        public Options {
            if (maximumVisits < 0
                    || maximumVisits > 100000
                    || maximumTrace < 0
                    || maximumTrace > 100000
                    || visitPool < 1
                    || visitPool > 1_000_000) {
                throw new IllegalArgumentException("Invalid path search limits");
            }
            Objects.requireNonNull(cancelled);
        }

        public Options(int maximumVisits, int maximumTrace, boolean graphStale, BooleanSupplier cancelled) {
            this(maximumVisits, maximumTrace, graphStale, poolFor(maximumVisits), cancelled);
        }

        public static int poolFor(int maximumVisits) {
            return maximumVisits <= 1000 ? SERVER_VISIT_POOL : Math.min(1_000_000, maximumVisits * 5);
        }

        public static Options defaults() {
            return new Options(1000, 8192, false, () -> false);
        }
    }

    public record Trace(
            Phase phase,
            int fromIndex,
            int toIndex,
            GeoPoint from,
            GeoPoint to,
            double distance,
            double lowerBound,
            int depth,
            boolean accepted,
            String detail) {}

    public record Result(
            Status status,
            GeoPoint nextWaypoint,
            int visitedNodes,
            int discoveredNodes,
            List<Integer> startAnchors,
            List<Integer> destinationAnchors,
            List<Trace> trace,
            boolean traceTruncated,
            MoveResult directMove,
            String sourceId,
            long revision,
            String graphFingerprint,
            String detail) {
        public Result {
            startAnchors = List.copyOf(startAnchors);
            destinationAnchors = List.copyOf(destinationAnchors);
            trace = List.copyOf(trace);
        }

        public int serverResult() {
            return status == Status.CAN_GO ? 0 : status == Status.PATH_FOUND ? 1 : 2;
        }

        public boolean isSuccess() {
            return status == Status.CAN_GO || status == Status.PATH_FOUND;
        }
    }

    public record Replay(
            ReplayStatus status,
            List<GeoPoint> waypoints,
            List<Result> calls,
            String sourceId,
            long revision,
            String detail) {
        public Replay {
            waypoints = List.copyOf(waypoints);
            calls = List.copyOf(calls);
        }
    }

    public Result findNext(GeoPoint source, GeoPoint destination) {
        return findNext(source, destination, Options.defaults());
    }

    public Result findNext(GeoPoint source, GeoPoint destination, Options options) {
        var search = new Search(source, destination, options);
        try {
            return search.run();
        } catch (Stopped stopped) {
            return search.buildResult(stopped.status, null, stopped.getMessage());
        } catch (IOException | IllegalStateException error) {
            return search.buildResult(Status.INVALID_GRAPH, null, error.getMessage());
        }
    }

    public Replay replay(GeoPoint source, GeoPoint destination, Options options, int maximumWaypoints) {
        if (maximumWaypoints < 1 || maximumWaypoints > 4096) {
            throw new IllegalArgumentException("Invalid waypoint limit");
        }
        var sourceId = data.getSourceId();
        var revision = data.revision();
        var points = new ArrayList<GeoPoint>();
        var calls = new ArrayList<Result>();
        var seen = new HashSet<GeoPoint>();
        var current = source;
        points.add(current);
        seen.add(current);
        for (var step = 0; step < maximumWaypoints; step++) {
            if (revision != data.revision() || !sourceId.equals(data.getSourceId())) {
                return new Replay(ReplayStatus.STOPPED, points, calls, sourceId, revision, "Geodata source changed");
            }
            var result = findNext(current, destination, options);
            calls.add(result);
            if (!result.isSuccess()) {
                return new Replay(ReplayStatus.STOPPED, points, calls, sourceId, revision, result.detail);
            }
            var next = result.nextWaypoint;
            if (result.status == Status.PATH_FOUND) {
                var segment = movement.move(current, next, options.cancelled);
                if (!segment.success()
                        || segment.completeness() == Completeness.DATA_UNAVAILABLE
                        || segment.completeness() == Completeness.CANCELLED
                        || segment.completeness() == Completeness.SOURCE_CHANGED
                        || revision != data.revision()
                        || !sourceId.equals(data.getSourceId())) {
                    return new Replay(
                            ReplayStatus.STOPPED,
                            points,
                            calls,
                            sourceId,
                            revision,
                            "Next-waypoint movement did not complete: " + segment.failure());
                }
                next = segment.finalPosition();
            }
            if (result.status == Status.CAN_GO && result.directMove.reachedDestination()) {
                if (!next.equals(current)) {
                    points.add(next);
                }
                return new Replay(
                        ReplayStatus.REACHED_DESTINATION,
                        points,
                        calls,
                        sourceId,
                        revision,
                        "Repeated server next-waypoint calls reached the destination");
            }
            if (next.distanceSquared(current) <= 0.01 || !seen.add(next)) {
                return new Replay(
                        ReplayStatus.CYCLE_DETECTED,
                        points,
                        calls,
                        sourceId,
                        revision,
                        "Next-waypoint replay repeated a position or made no progress");
            }
            points.add(next);
            current = next;
        }
        return new Replay(
                ReplayStatus.WAYPOINT_LIMIT,
                points,
                calls,
                sourceId,
                revision,
                "Replay stopped at the waypoint limit. This does not prove that no path exists");
    }

    @FunctionalInterface
    interface Movement {
        MoveResult move(GeoPoint source, GeoPoint destination, BooleanSupplier cancelled);
    }

    private final class Search {
        private final GeoPoint source;
        private final GeoPoint destination;
        private final Options options;
        private final String sourceId = data.getSourceId();
        private final long revision = data.revision();
        private final IntDoubleHashMap visited = new IntDoubleHashMap();
        private final ArrayList<Trace> trace = new ArrayList<>();
        private List<Integer> starts = List.of();
        private List<Integer> ends = List.of();
        private MoveResult direct;
        private int popped;
        private boolean traceTruncated;
        private boolean usedGraph;

        private Search(GeoPoint source, GeoPoint destination, Options options) {
            this.source = Objects.requireNonNull(source);
            this.destination = Objects.requireNonNull(destination);
            this.options = Objects.requireNonNull(options);
        }

        private Result run() throws IOException {
            check();
            if (options.graphStale) {
                return buildResult(Status.STALE_GRAPH, null, "The path graph predates geodata edits");
            }
            direct = move(source, destination);
            recordEvent(
                    Phase.DIRECT,
                    0,
                    0,
                    source,
                    destination,
                    0,
                    0,
                    0,
                    direct.success(),
                    direct.failure().name());
            if (direct.success()) {
                return buildResult(
                        Status.CAN_GO,
                        direct.finalPosition(),
                        direct.distanceExhausted()
                                ? "Server CAN_GO with an exhausted distance budget"
                                : "Server CAN_GO");
            }
            if (graph == null) {
                return buildResult(Status.GRAPH_MISSING, null, "Path graph is not loaded");
            }
            usedGraph = true;
            if (!graph.isSourceUnchanged()) {
                return buildResult(Status.SOURCE_CHANGED, null, "Path-node files changed since loading");
            }
            starts = findAnchors(source, true);
            if (starts.isEmpty()) {
                return buildResult(
                        Status.NO_START_ANCHOR, null, "No reachable source anchor in the surrounding sections");
            }
            ends = findAnchors(destination, false);
            if (ends.isEmpty()) {
                return buildResult(Status.NO_DESTINATION_ANCHOR, null, "No destination anchor can reach the target");
            }
            var queue = new ServerHeap();
            for (var index : starts) {
                var node = graph.getNode(index);
                var distance = source.distance(node.position());
                queue.add(new Entry(node, node, distance, destination.distance(node.position()), 1));
                visit(index, distance, true);
            }
            while (!queue.isEmpty()) {
                check();
                if (popped > options.maximumVisits) {
                    return buildResult(Status.VISIT_LIMIT, null, "Server visit limit reached. Search is incomplete");
                }
                var current = queue.remove();
                popped++;
                recordEvent(
                        Phase.VISIT,
                        current.start.index(),
                        current.node.index(),
                        current.start.position(),
                        current.node.position(),
                        current.distance,
                        current.lowerBound,
                        current.depth,
                        true,
                        "pop");
                if (ends.contains(current.node.index())) {
                    return buildResult(Status.PATH_FOUND, current.start.position(), "Server next waypoint found");
                }
                for (var index : current.node.neighbors()) {
                    if (index == 0) {
                        break;
                    }
                    var node = graph.getNode(index);
                    if (current.depth == 1) {
                        var shortcutDistance = source.distance(node.position());
                        if (!visit(index, shortcutDistance, false)) {
                            continue;
                        }
                        if (shortcutDistance > 0.1) {
                            var shortcut = move(source, node.position());
                            recordEvent(
                                    Phase.SHORTCUT,
                                    0,
                                    index,
                                    source,
                                    node.position(),
                                    shortcutDistance,
                                    destination.distance(node.position()),
                                    1,
                                    shortcut.success(),
                                    shortcut.failure().name());
                            if (shortcut.success()) {
                                visit(index, shortcutDistance, true);
                                queue.add(new Entry(
                                        node, node, shortcutDistance, destination.distance(node.position()), 1));
                                continue;
                            }
                        }
                    }
                    var distance = current.distance + nodeDistance(current.node, node);
                    var accepted = visit(index, distance, true);
                    recordEvent(
                            Phase.EDGE,
                            current.node.index(),
                            index,
                            current.node.position(),
                            node.position(),
                            distance,
                            destination.distance(node.position()),
                            current.depth + 1,
                            accepted,
                            "trusted graph edge");
                    if (accepted) {
                        queue.add(new Entry(
                                node,
                                current.start,
                                distance,
                                destination.distance(node.position()),
                                current.depth + 1));
                    }
                }
            }
            return buildResult(Status.NO_PATH, null, "No route connects the reachable anchors in this graph");
        }

        private List<Integer> findAnchors(GeoPoint point, boolean start) throws IOException {
            var nodes = new int[16];
            var distances = new double[16];
            var centerX = GeoQueryEngine.serverInt(point.x());
            var centerY = GeoQueryEngine.serverInt(point.y());
            for (var x = (long) centerX - 256; x <= (long) centerX + 256; x += 256) {
                for (var y = (long) centerY - 256; y <= (long) centerY + 256; y += 256) {
                    check();
                    if (x < Integer.MIN_VALUE
                            || x > Integer.MAX_VALUE
                            || y < Integer.MIN_VALUE
                            || y > Integer.MAX_VALUE) {
                        continue;
                    }
                    var section = graph.getSection((int) x, (int) y);
                    for (var index = section.firstIndex(); index < section.firstIndex() + section.count(); index++) {
                        var node = graph.getNode(index);
                        var angle = Math.atan2(
                                node.position().y() - centerY, node.position().x() - centerX);
                        var sector = Math.clamp(8 - GeoQueryEngine.serverInt(angle / -3.141592 * 8.0), 0, 15);
                        var distanceSquared = point.distanceSquared(node.position());
                        if (distanceSquared <= 0.010000000000000002
                                || (nodes[sector] != 0 && distances[sector] * distances[sector] <= distanceSquared)) {
                            continue;
                        }
                        var from = start ? point : node.position();
                        var to = start ? node.position() : point;
                        var moved = move(from, to);
                        recordEvent(
                                start ? Phase.START_ANCHOR : Phase.DESTINATION_ANCHOR,
                                start ? 0 : index,
                                start ? index : 0,
                                from,
                                to,
                                Math.sqrt(distanceSquared),
                                0,
                                0,
                                moved.success(),
                                moved.failure().name());
                        if (moved.success()) {
                            nodes[sector] = index;
                            distances[sector] = Math.sqrt(distanceSquared);
                        }
                    }
                }
            }
            var selected = new ArrayList<Integer>();
            for (var node : nodes) {
                if (node != 0) {
                    selected.add(node);
                }
            }
            return selected;
        }

        private MoveResult move(GeoPoint from, GeoPoint to) {
            check();
            var result = movement.move(from, to, options.cancelled);
            var status = switch (result.completeness()) {
                case COMPLETE -> null;
                case LIMIT -> result.success() ? null : Status.QUERY_LIMIT;
                case DATA_UNAVAILABLE -> Status.DATA_UNAVAILABLE;
                case CANCELLED -> Status.CANCELLED;
                case SOURCE_CHANGED -> Status.SOURCE_CHANGED;
            };
            if (status != null) {
                throw new Stopped(status, "Movement check incomplete: " + result.failure());
            }
            check();
            return result;
        }

        private boolean visit(int index, double distance, boolean update) {
            if (visited.containsKey(index)) {
                if (visited.get(index) <= distance) {
                    return false;
                }
            } else if (update && visited.size() == options.visitPool) {
                throw new Stopped(Status.VISIT_POOL_LIMIT, "Server visit pool capacity reached. Search is incomplete");
            }
            if (update) {
                visited.put(index, distance);
            }
            return true;
        }

        private void check() {
            if (options.cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new Stopped(Status.CANCELLED, "Path search cancelled");
            }
            if (revision != data.revision() || !sourceId.equals(data.getSourceId())) {
                throw new Stopped(Status.SOURCE_CHANGED, "Geodata source changed during the query");
            }
        }

        private void recordEvent(
                Phase phase,
                int fromIndex,
                int toIndex,
                GeoPoint from,
                GeoPoint to,
                double distance,
                double bound,
                int depth,
                boolean accepted,
                String detail) {
            if (trace.size() < options.maximumTrace) {
                trace.add(new Trace(phase, fromIndex, toIndex, from, to, distance, bound, depth, accepted, detail));
            } else {
                traceTruncated = true;
            }
        }

        private Result buildResult(Status status, GeoPoint waypoint, String detail) {
            if (revision != data.revision() || !sourceId.equals(data.getSourceId())) {
                status = Status.SOURCE_CHANGED;
                waypoint = null;
                detail = "Geodata source changed during the query";
            }
            if (usedGraph) {
                try {
                    if (!graph.isSourceUnchanged()) {
                        status = Status.SOURCE_CHANGED;
                        waypoint = null;
                        detail = "Path-node files changed during the query";
                    }
                } catch (IOException error) {
                    status = Status.SOURCE_CHANGED;
                    waypoint = null;
                    detail = "Path-node source unavailable: " + error.getMessage();
                }
            }
            return new Result(
                    status,
                    waypoint,
                    popped,
                    visited.size(),
                    starts,
                    ends,
                    trace,
                    traceTruncated,
                    direct,
                    sourceId,
                    revision,
                    graph == null ? "" : graph.getMetadata().fingerprint(),
                    detail);
        }
    }

    private static double nodeDistance(Node first, Node second) {
        var x = (double) ((int) first.position().x() - (int) second.position().x());
        var y = (double) ((int) first.position().y() - (int) second.position().y());
        var z = (double) ((int) first.position().z() - (int) second.position().z());
        return Math.sqrt(x * x + y * y + z * z);
    }

    private record Entry(Node node, Node start, double distance, double lowerBound, int depth) {
        private double score() {
            return distance + lowerBound;
        }
    }

    private static final class ServerHeap {
        private final ArrayList<Entry> entries = new ArrayList<>();

        private boolean isEmpty() {
            return entries.isEmpty();
        }

        private void add(Entry value) {
            var hole = entries.size();
            entries.add(value);
            push(hole, value);
        }

        private void push(int hole, Entry value) {
            while (hole > 0) {
                var parent = (hole - 1) / 2;
                if (entries.get(parent).score() <= value.score()) {
                    break;
                }
                entries.set(hole, entries.get(parent));
                hole = parent;
            }
            entries.set(hole, value);
        }

        private Entry remove() {
            var result = entries.getFirst();
            var value = entries.removeLast();
            if (!entries.isEmpty()) {
                var hole = 0;
                while (hole * 2 + 2 < entries.size()) {
                    var child = hole * 2 + 2;
                    if (entries.get(child).score() > entries.get(child - 1).score()) {
                        child--;
                    }
                    entries.set(hole, entries.get(child));
                    hole = child;
                }
                if (hole * 2 + 1 < entries.size()) {
                    entries.set(hole, entries.get(hole * 2 + 1));
                    hole = hole * 2 + 1;
                }
                push(hole, value);
            }
            return result;
        }
    }

    private static final class Stopped extends RuntimeException {
        private final Status status;

        private Stopped(Status status, String detail) {
            super(detail);
            this.status = status;
        }
    }
}
