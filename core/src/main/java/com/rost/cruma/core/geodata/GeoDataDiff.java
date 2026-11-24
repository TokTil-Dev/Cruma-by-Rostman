package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataAccess.Cell;
import com.rost.cruma.core.geodata.GeoQueryEngine.Completeness;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class GeoDataDiff {
    private GeoDataDiff() {}

    public record Bounds(int minX, int minY, int maxX, int maxY) {
        public Bounds {
            if (minX > maxX || minY > maxY) {
                throw new IllegalArgumentException("Reversed comparison bounds");
            }
        }
    }

    public record Limits(int maxCells, int maxReportedCells, long maxLayerComparisons, BooleanSupplier cancelled) {
        public Limits {
            if (maxCells < 1 || maxReportedCells < 0 || maxLayerComparisons < 1) {
                throw new IllegalArgumentException("Invalid comparison limits");
            }
            Objects.requireNonNull(cancelled, "cancelled");
        }
    }

    public record LayerMatch(
            int beforeIndex, int afterIndex, boolean heightChanged, boolean nsweChanged, boolean ambiguous) {
        public boolean hasChanged() {
            return beforeIndex < 0 || afterIndex < 0 || heightChanged || nsweChanged;
        }
    }

    public record CellDiff(
            int x,
            int y,
            Cell before,
            Cell after,
            List<LayerMatch> matches,
            boolean ambiguous,
            boolean complete,
            boolean selectionOrderChanged,
            boolean storageSemanticsChanged) {
        public CellDiff {
            matches = List.copyOf(matches);
        }

        public CellDiff(
                int x, int y, Cell before, Cell after, List<LayerMatch> matches, boolean ambiguous, boolean complete) {
            this(x, y, before, after, matches, ambiguous, complete, false, false);
        }

        public CellDiff(
                int x,
                int y,
                Cell before,
                Cell after,
                List<LayerMatch> matches,
                boolean ambiguous,
                boolean complete,
                boolean selectionOrderChanged) {
            this(x, y, before, after, matches, ambiguous, complete, selectionOrderChanged, false);
        }

        public boolean hasChanged() {
            if (selectionOrderChanged || storageSemanticsChanged) {
                return true;
            }
            for (var match : matches) {
                if (match.hasChanged()) {
                    return true;
                }
            }
            return false;
        }
    }

    public record Result(
            List<CellDiff> cells,
            int comparedCells,
            int changedCells,
            int ambiguousCells,
            int unavailableCells,
            Completeness completeness,
            boolean reportsTruncated,
            String beforeSource,
            long beforeRevision,
            String afterSource,
            long afterRevision) {
        public Result {
            cells = List.copyOf(cells);
        }
    }

    public static Result compare(GeoDataAccess before, GeoDataAccess after, Bounds bounds, Limits limits) {
        return new Comparison(before, after, limits).compare(bounds);
    }

    private static final class Comparison {
        private final GeoDataAccess before;
        private final GeoDataAccess after;
        private final Limits limits;
        private final String beforeSource;
        private final String afterSource;
        private final long beforeRevision;
        private final long afterRevision;
        private final List<CellDiff> reports = new ArrayList<>();
        private int compared;
        private int changed;
        private int ambiguous;
        private int unavailable;
        private long comparisons;
        private boolean truncated;
        private Completeness completeness = Completeness.COMPLETE;

        Comparison(GeoDataAccess before, GeoDataAccess after, Limits limits) {
            this.before = Objects.requireNonNull(before, "before");
            this.after = Objects.requireNonNull(after, "after");
            this.limits = Objects.requireNonNull(limits, "limits");
            beforeSource = before.getSourceId();
            afterSource = after.getSourceId();
            beforeRevision = before.revision();
            afterRevision = after.revision();
        }

        Result compare(Bounds bounds) {
            if (bounds.minX() == bounds.maxX() || bounds.minY() == bounds.maxY()) {
                return result();
            }
            for (var x = (long) Math.floorDiv(bounds.minX(), 16) * 16; x < bounds.maxX(); x += 16) {
                for (var y = (long) Math.floorDiv(bounds.minY(), 16) * 16; y < bounds.maxY(); y += 16) {
                    if (shouldStop()) {
                        return result();
                    }
                    if (compared >= limits.maxCells()) {
                        completeness = Completeness.LIMIT;
                        return result();
                    }
                    var first = before.cellAt((int) x, (int) y);
                    var second = after.cellAt((int) x, (int) y);
                    compared++;
                    if (first.availability() != Availability.AVAILABLE
                            || second.availability() != Availability.AVAILABLE) {
                        unavailable++;
                        completeness = Completeness.DATA_UNAVAILABLE;
                        report(new CellDiff((int) x, (int) y, first, second, List.of(), false, false));
                        continue;
                    }
                    var cell = compareCells(first, second);
                    if (!cell.complete()) {
                        report(cell);
                        return result();
                    }
                    if (cell.hasChanged()) {
                        changed++;
                    }
                    if (cell.ambiguous()) {
                        ambiguous++;
                    }
                    if (cell.hasChanged() || cell.ambiguous()) {
                        report(cell);
                    }
                }
            }
            shouldStop();
            return result();
        }

        private CellDiff compareCells(Cell first, Cell second) {
            var oldLayers = first.layers();
            var newLayers = second.layers();
            var mapping = new int[oldLayers.size()];
            Arrays.fill(mapping, -1);
            var used = new boolean[newLayers.size()];
            var ambiguousMatches = new boolean[oldLayers.size()];
            var matches = new ArrayList<LayerMatch>();
            for (var phase = 2; phase >= 0; phase--) {
                for (var oldIndex = 0; oldIndex < oldLayers.size(); oldIndex++) {
                    if (mapping[oldIndex] >= 0) {
                        continue;
                    }
                    var oldLayer = oldLayers.get(oldIndex);
                    var best = -1;
                    var bestHeight = Long.MAX_VALUE;
                    var bestFlags = Integer.MAX_VALUE;
                    var ties = 0;
                    for (var newIndex = 0; newIndex < newLayers.size(); newIndex++) {
                        if (used[newIndex]) {
                            continue;
                        }
                        if (++comparisons > limits.maxLayerComparisons() || shouldStop()) {
                            if (completeness != Completeness.CANCELLED && completeness != Completeness.SOURCE_CHANGED) {
                                completeness = Completeness.LIMIT;
                            }
                            return new CellDiff(first.x(), first.y(), first, second, matches, false, false);
                        }
                        var candidate = newLayers.get(newIndex);
                        var delta = Math.abs((long) oldLayer.height() - candidate.height());
                        var flags = Integer.bitCount(oldLayer.nswe() ^ candidate.nswe());
                        if (phase >= 1 && delta != 0 || phase == 2 && flags != 0) {
                            continue;
                        }
                        if (delta < bestHeight || delta == bestHeight && flags < bestFlags) {
                            best = newIndex;
                            bestHeight = delta;
                            bestFlags = flags;
                            ties = 1;
                        } else if (delta == bestHeight && flags == bestFlags) {
                            ties++;
                        }
                    }
                    if (best >= 0) {
                        for (var other = oldIndex + 1; other < oldLayers.size(); other++) {
                            if (mapping[other] >= 0) {
                                continue;
                            }
                            if (++comparisons > limits.maxLayerComparisons() || shouldStop()) {
                                if (completeness != Completeness.CANCELLED
                                        && completeness != Completeness.SOURCE_CHANGED) {
                                    completeness = Completeness.LIMIT;
                                }
                                return new CellDiff(first.x(), first.y(), first, second, matches, false, false);
                            }
                            var alternative = oldLayers.get(other);
                            var candidate = newLayers.get(best);
                            if (Math.abs((long) alternative.height() - candidate.height()) == bestHeight
                                    && Integer.bitCount(alternative.nswe() ^ candidate.nswe()) == bestFlags) {
                                ties++;
                            }
                        }
                        mapping[oldIndex] = best;
                        used[best] = true;
                        ambiguousMatches[oldIndex] = ties > 1;
                    }
                }
            }
            var ambiguousCell = false;
            var selectionOrderChanged = false;
            var greatestMatchedIndex = -1;
            for (var oldIndex = 0; oldIndex < oldLayers.size(); oldIndex++) {
                var newIndex = mapping[oldIndex];
                if (newIndex >= 0) {
                    selectionOrderChanged |= newIndex < greatestMatchedIndex;
                    greatestMatchedIndex = Math.max(greatestMatchedIndex, newIndex);
                }
                var oldLayer = oldLayers.get(oldIndex);
                var newLayer = newIndex < 0 ? null : newLayers.get(newIndex);
                ambiguousCell |= ambiguousMatches[oldIndex];
                matches.add(new LayerMatch(
                        oldIndex,
                        newIndex,
                        newLayer != null && oldLayer.height() != newLayer.height(),
                        newLayer != null && oldLayer.nswe() != newLayer.nswe(),
                        ambiguousMatches[oldIndex]));
            }
            for (var newIndex = 0; newIndex < used.length; newIndex++) {
                if (!used[newIndex]) {
                    matches.add(new LayerMatch(-1, newIndex, false, false, false));
                }
            }
            return new CellDiff(
                    first.x(),
                    first.y(),
                    first,
                    second,
                    matches,
                    ambiguousCell,
                    true,
                    selectionOrderChanged,
                    first.storage() != second.storage());
        }

        private boolean shouldStop() {
            if (before.revision() != beforeRevision
                    || after.revision() != afterRevision
                    || !beforeSource.equals(before.getSourceId())
                    || !afterSource.equals(after.getSourceId())) {
                completeness = Completeness.SOURCE_CHANGED;
                return true;
            }
            if (limits.cancelled().getAsBoolean() || Thread.currentThread().isInterrupted()) {
                completeness = Completeness.CANCELLED;
                return true;
            }
            return false;
        }

        private void report(CellDiff cell) {
            if (reports.size() < limits.maxReportedCells()) {
                reports.add(cell);
            } else {
                truncated = true;
            }
        }

        private Result result() {
            return new Result(
                    reports,
                    compared,
                    changed,
                    ambiguous,
                    unavailable,
                    completeness,
                    truncated,
                    beforeSource,
                    beforeRevision,
                    afterSource,
                    afterRevision);
        }
    }
}
