package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.core.geodata.GeoDataScanner.ClientSample;
import com.rost.cruma.core.geodata.GeoDataScanner.ClientSurface;
import com.rost.cruma.core.geodata.GeoDataScanner.Passage;
import com.rost.cruma.core.geodata.GeoDataScanner.SurfaceProbe;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.GeoQueryEngine;
import com.rost.cruma.core.geodata.GeoQueryEngine.Completeness;
import com.rost.cruma.core.world.UMap;
import com.rost.cruma.viewer.geodata.GeoClientSurfaceIndex.Limits;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GeoClientSurfaceCache {
    private static final int[] PASSAGE_HEIGHTS = {24, 56};

    private final int maxMaps;
    private final long maxBytes;
    private final Limits buildLimits;
    private final Map<String, GeoClientSurfaceIndex> indices = new HashMap<>();
    private volatile long epoch;
    private volatile long revision;
    private double focusX;
    private double focusY;
    private boolean focused;

    public GeoClientSurfaceCache() {
        this(4, 192L * 1024 * 1024, new Limits(500_000, 2_000_000, 48L * 1024 * 1024));
    }

    public GeoClientSurfaceCache(int maxMaps, long maxBytes, Limits buildLimits) {
        if (maxMaps < 1 || maxMaps > 16 || maxBytes < 1024) {
            throw new IllegalArgumentException("Invalid client surface cache limits");
        }
        this.maxMaps = maxMaps;
        this.maxBytes = maxBytes;
        this.buildLimits = buildLimits;
    }

    public record Prepared(long epoch, GeoClientSurfaceIndex index) {}

    public record Status(int maps, int triangles, long retainedBytes, int truncatedMaps, long revision) {}

    public Prepared prepare(UMap map) {
        long epoch;
        Bounds bounds;
        synchronized (this) {
            epoch = this.epoch;
            var minX = GeoQueryEngine.serverInt(map.position().getX());
            var minY = GeoQueryEngine.serverInt(map.position().getY());
            var x = focused ? Math.clamp(focusX, minX, minX + 32768) : minX + 16384;
            var y = focused ? Math.clamp(focusY, minY, minY + 32768) : minY + 16384;
            bounds = new Bounds(
                    (int) Math.max(minX, x - 8192),
                    (int) Math.max(minY, y - 8192),
                    (int) Math.min(minX + 32768, x + 8192),
                    (int) Math.min(minY + 32768, y + 8192));
        }
        GeoClientSurfaceIndex index;
        try {
            index = GeoClientSurfaceIndex.build(map, bounds, buildLimits, () -> epoch != this.epoch);
        } catch (RuntimeException failure) {
            index = GeoClientSurfaceIndex.unavailable(map.name(), bounds, failure);
        }
        return new Prepared(epoch, index);
    }

    public synchronized void publish(Prepared prepared) {
        if (prepared == null || prepared.epoch() != epoch) {
            return;
        }
        var index = prepared.index();
        indices.remove(index.getOwner());
        indices.put(index.getOwner(), index);
        while (indices.size() > maxMaps || bytes() > maxBytes) {
            GeoClientSurfaceIndex farthest = null;
            for (var candidate : indices.values()) {
                if (farthest == null
                        || squaredDistance(candidate.getBounds()) > squaredDistance(farthest.getBounds())
                        || squaredDistance(candidate.getBounds()) == squaredDistance(farthest.getBounds())
                                && candidate.getOwner().compareTo(farthest.getOwner()) > 0) {
                    farthest = candidate;
                }
            }
            indices.remove(farthest.getOwner());
        }
        revision++;
    }

    public synchronized void focus(double x, double y) {
        if (Double.isFinite(x) && Double.isFinite(y)) {
            focusX = x;
            focusY = y;
            focused = true;
        }
    }

    public synchronized void remove(String owner) {
        if (indices.remove(owner) != null) {
            revision++;
        }
    }

    public synchronized void clear() {
        epoch++;
        revision++;
        indices.clear();
    }

    public synchronized Status getStatus() {
        var triangles = 0;
        var truncated = 0;
        for (var index : indices.values()) {
            triangles += index.getTriangleCount();
            if (index.isTruncated()) {
                truncated++;
            }
        }
        return new Status(indices.size(), triangles, bytes(), truncated, revision);
    }

    public synchronized SurfaceProbe snapshot(int maxTriangleTests) {
        if (maxTriangleTests < 1 || maxTriangleTests > 20_000_000) {
            throw new IllegalArgumentException("Invalid client query budget");
        }
        var captured = new ArrayList<>(indices.values());
        captured.sort(Comparator.comparing(GeoClientSurfaceIndex::getOwner));
        return new Probe(List.copyOf(captured), revision, maxTriangleTests);
    }

    private long bytes() {
        var bytes = 0L;
        for (var index : indices.values()) {
            bytes += index.getRetainedBytes();
        }
        return bytes;
    }

    private double squaredDistance(Bounds bounds) {
        var x = focusX - Math.clamp(focusX, bounds.minX(), bounds.maxX());
        var y = focusY - Math.clamp(focusY, bounds.minY(), bounds.maxY());
        return x * x + y * y;
    }

    private final class Probe implements SurfaceProbe {
        private final List<GeoClientSurfaceIndex> captured;
        private final long sourceRevision;
        private final int maximumTests;
        private int remaining;

        Probe(List<GeoClientSurfaceIndex> captured, long sourceRevision, int remaining) {
            this.captured = captured;
            this.sourceRevision = sourceRevision;
            this.remaining = remaining;
            maximumTests = remaining;
        }

        @Override
        public boolean isValid() {
            return sourceRevision == revision;
        }

        @Override
        public int getTriangleTestLimit() {
            return maximumTests;
        }

        @Override
        public List<ClientSurface> getSurfacesAt(int x, int y) {
            return sampleAt(x, y).surfaces();
        }

        @Override
        public synchronized ClientSample sampleAt(int x, int y) {
            if (!isValid()) {
                return new ClientSample(List.of(), Completeness.SOURCE_CHANGED, "Client scene changed");
            }
            var surfaces = new ArrayList<ClientSurface>();
            var covered = false;
            for (var index : captured) {
                if (!index.contains(x, y)) {
                    continue;
                }
                covered = true;
                var sample = index.sample(x, y, Math.min(4096, remaining));
                remaining -= sample.testedTriangles();
                if (sample.completeness() != Completeness.COMPLETE) {
                    return new ClientSample(List.of(), sample.completeness(), sample.detail());
                }
                surfaces.addAll(sample.surfaces());
            }
            return covered
                    ? new ClientSample(surfaces, Completeness.COMPLETE, "")
                    : new ClientSample(
                            List.of(),
                            Completeness.DATA_UNAVAILABLE,
                            "Client map is outside the retained triangle cache. Focus this area and reload the scene");
        }

        @Override
        public synchronized Passage checkPassage(GeoPoint from, GeoPoint to) {
            if (!isValid()) {
                return Passage.UNKNOWN;
            }
            var floor = Math.max(from.z(), to.z());
            var known = false;
            for (var index : captured) {
                if (!index.contains((int) Math.floor(from.x()), (int) Math.floor(from.y()))
                        || !index.contains((int) Math.floor(to.x()), (int) Math.floor(to.y()))) {
                    continue;
                }
                for (var height : PASSAGE_HEIGHTS) {
                    var crossing = index.findCrossing(
                            from.x(), from.y(), to.x(), to.y(), floor + height, Math.min(4096, remaining));
                    remaining -= crossing.testedTriangles();
                    if (crossing.completeness() != Completeness.COMPLETE) {
                        return Passage.UNKNOWN;
                    }
                    if (crossing.blocked()) {
                        return Passage.BLOCKED;
                    }
                }
                known = true;
            }
            return known ? Passage.OPEN : Passage.UNKNOWN;
        }
    }
}
