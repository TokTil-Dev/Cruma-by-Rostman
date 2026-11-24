package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoEditDocument.CellKey;
import com.rost.cruma.core.geodata.GeoQueryEngine.Completeness;
import com.rost.cruma.core.geodata.GeoQueryEngine.MoveResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

public final class GeoEditedGraphValidator {
    public enum Status {
        LOCAL_SCAN_COMPLETE,
        NODE_LIMIT,
        EDGE_LIMIT,
        TIME_LIMIT,
        CANCELLED,
        SOURCE_CHANGED
    }

    public enum Outcome {
        PASS,
        BLOCKED,
        INCOMPLETE
    }

    public enum Change {
        UNCHANGED,
        LOST,
        GAINED,
        CHANGED,
        INCOMPLETE
    }

    public record Limits(
            int radius, int maximumNodes, int maximumEdges, long maximumMillis, BooleanSupplier cancelled) {
        public Limits {
            if (radius < 16
                    || radius > 4096
                    || maximumNodes < 1
                    || maximumNodes > 100_000
                    || maximumEdges < 1
                    || maximumEdges > 8192
                    || maximumMillis < 1
                    || maximumMillis > 600_000
                    || cancelled == null) {
                throw new IllegalArgumentException("Invalid edited graph validation bounds");
            }
        }
    }

    public record DirectionCheck(MoveResult original, MoveResult preview, Change change) {}

    public record Edge(
            int fromIndex,
            int toIndex,
            GeoPoint from,
            GeoPoint to,
            boolean forwardStored,
            boolean reverseStored,
            DirectionCheck forward,
            DirectionCheck reverse) {
        public boolean hasChanged() {
            return forward.change() != Change.UNCHANGED || reverse.change() != Change.UNCHANGED;
        }
    }

    public record Result(
            Status status,
            List<Edge> edges,
            int scannedNodes,
            int candidateEdges,
            int editedSectors,
            String graphFingerprint,
            String sourceId,
            long originalRevision,
            long previewRevision,
            GeoPoint center,
            int radius) {
        public Result {
            edges = List.copyOf(edges);
        }
    }

    public static Result validate(
            PathNodeGraph graph,
            GeoDataAccess original,
            GeoDataAccess preview,
            Set<CellKey> changedCells,
            GeoPoint center,
            Limits limits)
            throws IOException {
        return new Scan(graph, original, preview, changedCells, center, limits).run();
    }

    public static Outcome toOutcome(MoveResult result) {
        if (result.completeness() != Completeness.COMPLETE || result.distanceExhausted()) {
            return Outcome.INCOMPLETE;
        }
        return result.success() && result.reachedDestination() ? Outcome.PASS : Outcome.BLOCKED;
    }

    private record Sector(int x, int y) {}

    private static final class Scan {
        final PathNodeGraph graph;
        final GeoDataAccess original;
        final GeoDataAccess preview;
        final GeoPoint center;
        final Limits limits;
        final GeoQueryEngine before;
        final GeoQueryEngine after;
        final long originalRevision;
        final long previewRevision;
        final String originalSource;
        final String previewSource;
        final long started = System.nanoTime();
        final Set<Sector> sectors = new HashSet<>();
        final Set<Long> seen = new HashSet<>();
        final List<Edge> results = new ArrayList<>();
        Status status = Status.LOCAL_SCAN_COMPLETE;
        int scannedNodes;
        int candidateEdges;

        Scan(
                PathNodeGraph graph,
                GeoDataAccess original,
                GeoDataAccess preview,
                Set<CellKey> edits,
                GeoPoint center,
                Limits limits) {
            this.graph = graph;
            this.original = original;
            this.preview = preview;
            this.center = center;
            this.limits = limits;
            before = new GeoQueryEngine(original);
            after = new GeoQueryEngine(preview);
            originalRevision = original.revision();
            previewRevision = preview.revision();
            originalSource = original.getSourceId();
            previewSource = preview.getSourceId();
            for (var edit : edits) {
                sectors.add(new Sector(Math.floorDiv(edit.x(), 128) * 128, Math.floorDiv(edit.y(), 128) * 128));
            }
        }

        Result run() throws IOException {
            if (!graph.isSourceUnchanged()) {
                status = Status.SOURCE_CHANGED;
            }
            outer:
            for (var x = Math.floorDiv(GeoQueryEngine.serverInt(center.x()) - limits.radius(), 256) * 256;
                    x <= center.x() + limits.radius();
                    x += 256) {
                for (var y = Math.floorDiv(GeoQueryEngine.serverInt(center.y()) - limits.radius(), 256) * 256;
                        y <= center.y() + limits.radius();
                        y += 256) {
                    if (isStopped()) {
                        break outer;
                    }
                    var section = graph.getSection(x, y);
                    for (var index = section.firstIndex(); index < section.firstIndex() + section.count(); index++) {
                        if (isStopped()) {
                            break outer;
                        }
                        if (scannedNodes == limits.maximumNodes()) {
                            status = Status.NODE_LIMIT;
                            break outer;
                        }
                        scannedNodes++;
                        var node = graph.getNode(index);
                        if (Math.abs(node.position().x() - center.x()) > limits.radius()
                                || Math.abs(node.position().y() - center.y()) > limits.radius()) {
                            continue;
                        }
                        for (var neighborIndex : node.neighbors()) {
                            if (neighborIndex == 0) {
                                break;
                            }
                            if (isStopped()) {
                                break outer;
                            }
                            var key = ((long) Math.min(index, neighborIndex) << 32) | Math.max(index, neighborIndex);
                            if (!seen.add(key)) {
                                continue;
                            }
                            candidateEdges++;
                            var neighbor = graph.getNode(neighborIndex);
                            if (!touchesEdits(node.position(), neighbor.position())) {
                                continue;
                            }
                            if (results.size() == limits.maximumEdges()) {
                                status = Status.EDGE_LIMIT;
                                break outer;
                            }
                            var forward = check(node.position(), neighbor.position());
                            var reverse = check(neighbor.position(), node.position());
                            results.add(new Edge(
                                    index,
                                    neighborIndex,
                                    node.position(),
                                    neighbor.position(),
                                    true,
                                    hasNeighbor(neighbor.neighbors(), index),
                                    forward,
                                    reverse));
                        }
                    }
                }
            }
            isStopped();
            if (!graph.isSourceUnchanged()) {
                status = Status.SOURCE_CHANGED;
            }
            if (status == Status.SOURCE_CHANGED) {
                results.clear();
            }
            return new Result(
                    status,
                    results,
                    scannedNodes,
                    candidateEdges,
                    sectors.size(),
                    graph.getMetadata().fingerprint(),
                    originalSource,
                    originalRevision,
                    previewRevision,
                    center,
                    limits.radius());
        }

        boolean isStopped() {
            if (limits.cancelled().getAsBoolean() || Thread.currentThread().isInterrupted()) {
                status = Status.CANCELLED;
            } else if (originalRevision != original.revision()
                    || previewRevision != preview.revision()
                    || !originalSource.equals(original.getSourceId())
                    || !previewSource.equals(preview.getSourceId())) {
                status = Status.SOURCE_CHANGED;
            } else if (System.nanoTime() - started >= limits.maximumMillis() * 1_000_000L) {
                status = Status.TIME_LIMIT;
            }
            return status != Status.LOCAL_SCAN_COMPLETE;
        }

        DirectionCheck check(GeoPoint from, GeoPoint to) {
            var queryLimits = new GeoQueryEngine.Limits(65_536, 0, this::isStopped);
            var originalResult = before.moveStraight(from, to, GeoQueryEngine.DEFAULT_DISTANCE, queryLimits);
            var previewResult = after.moveStraight(from, to, GeoQueryEngine.DEFAULT_DISTANCE, queryLimits);
            var a = toOutcome(originalResult);
            var b = toOutcome(previewResult);
            var change = a == Outcome.INCOMPLETE || b == Outcome.INCOMPLETE
                    ? Change.INCOMPLETE
                    : a == Outcome.PASS && b == Outcome.BLOCKED
                            ? Change.LOST
                            : a == Outcome.BLOCKED && b == Outcome.PASS
                                    ? Change.GAINED
                                    : originalResult.failure() != previewResult.failure()
                                                    || !originalResult
                                                            .finalPosition()
                                                            .equals(previewResult.finalPosition())
                                            ? Change.CHANGED
                                            : Change.UNCHANGED;
            return new DirectionCheck(originalResult, previewResult, change);
        }

        boolean touchesEdits(GeoPoint a, GeoPoint b) {
            var inspected = 0;
            for (var sector : sectors) {
                if ((inspected++ & 63) == 0 && isStopped()) {
                    return false;
                }
                if (intersects(a, b, sector.x() - 16, sector.y() - 16, sector.x() + 144, sector.y() + 144)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean hasNeighbor(List<Integer> neighbors, int target) {
        for (var neighbor : neighbors) {
            if (neighbor == 0) {
                return false;
            }
            if (neighbor == target) {
                return true;
            }
        }
        return false;
    }

    private static boolean intersects(GeoPoint a, GeoPoint b, int minX, int minY, int maxX, int maxY) {
        var start = 0.0;
        var end = 1.0;
        for (var axis = 0; axis < 2; axis++) {
            var origin = axis == 0 ? a.x() : a.y();
            var delta = (axis == 0 ? b.x() : b.y()) - origin;
            var min = axis == 0 ? minX : minY;
            var max = axis == 0 ? maxX : maxY;
            if (delta == 0) {
                if (origin < min || origin > max) {
                    return false;
                }
            } else {
                var near = (min - origin) / delta;
                var far = (max - origin) / delta;
                if (near > far) {
                    var swap = near;
                    near = far;
                    far = swap;
                }
                start = Math.max(start, near);
                end = Math.min(end, far);
                if (start > end) {
                    return false;
                }
            }
        }
        return true;
    }
}
