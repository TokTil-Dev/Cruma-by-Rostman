package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoCellRef;
import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoPoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class GeoDataPicker {
    private static final int MAX_CELLS = 65_536;
    private static final int MAX_LAYERS = 65_536;
    private static final int MAX_HITS = 64;

    private GeoDataPicker() {}

    public record Hit(GeoCellRef cell, GeoPoint position, int nswe, double distance) {}

    public record PickResult(List<Hit> hits, boolean truncated) {
        public PickResult {
            hits = List.copyOf(hits);
        }
    }

    public static List<Hit> pick(
            GeoDataAccess data, GeoPoint origin, GeoPoint direction, double maxDistance, int minHeight, int maxHeight) {
        return pickBounded(data, origin, direction, maxDistance, minHeight, maxHeight)
                .hits();
    }

    public static PickResult pickBounded(
            GeoDataAccess data, GeoPoint origin, GeoPoint direction, double maxDistance, int minHeight, int maxHeight) {
        return walk(data, origin, direction, maxDistance, minHeight, maxHeight, false);
    }

    public static @Nullable Hit firstHit(GeoDataAccess data, GeoPoint origin, GeoPoint direction, double maxDistance) {
        var hits = walk(data, origin, direction, maxDistance, Integer.MIN_VALUE, Integer.MAX_VALUE, true)
                .hits();
        return hits.isEmpty() ? null : hits.getFirst();
    }

    private static PickResult walk(
            GeoDataAccess data,
            GeoPoint origin,
            GeoPoint direction,
            double maxDistance,
            int minHeight,
            int maxHeight,
            boolean firstCell) {
        if (data == null || !Double.isFinite(maxDistance) || maxDistance <= 0 || minHeight > maxHeight) {
            return new PickResult(List.of(), false);
        }
        var length = Math.sqrt(direction.distanceSquared(new GeoPoint(0, 0, 0)));
        if (length < 1e-12 || Math.abs(origin.x()) > 1_000_000_000 || Math.abs(origin.y()) > 1_000_000_000) {
            return new PickResult(List.of(), false);
        }
        var dx = direction.x() / length;
        var dy = direction.y() / length;
        var dz = direction.z() / length;
        var x = (int) Math.floor(origin.x() / 16) * 16;
        var y = (int) Math.floor(origin.y() / 16) * 16;
        var stepX = dx < 0 ? -16 : 16;
        var stepY = dy < 0 ? -16 : 16;
        var tx = dx == 0 ? Double.POSITIVE_INFINITY : ((dx < 0 ? x : x + 16) - origin.x()) / dx;
        var ty = dy == 0 ? Double.POSITIVE_INFINITY : ((dy < 0 ? y : y + 16) - origin.y()) / dy;
        var dtx = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(16 / dx);
        var dty = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(16 / dy);
        var hits = new ArrayList<Hit>();
        var entered = 0.0;
        var visited = 0;
        var checkedLayers = 0;
        while (entered <= maxDistance) {
            if (visited++ >= MAX_CELLS || hits.size() >= MAX_HITS) {
                return finish(hits, true);
            }
            var exit = Math.min(maxDistance, Math.min(tx, ty));
            var cell = data.cellAt(x, y);
            for (var layer : cell.layers()) {
                if (checkedLayers++ >= MAX_LAYERS) {
                    return finish(hits, true);
                }
                if (layer.height() < minHeight || layer.height() > maxHeight) {
                    continue;
                }
                var t = Math.abs(dz) < 1e-12
                        ? (Math.abs(origin.z() - layer.height()) < 1e-7 ? entered : Double.POSITIVE_INFINITY)
                        : (layer.height() - origin.z()) / dz;
                if (!Double.isFinite(t) || t < 0 || t < entered - 1e-7 || t > exit + 1e-7) {
                    continue;
                }
                var hitX = origin.x() + t * dx;
                var hitY = origin.y() + t * dy;
                if (hitX < x || hitX >= x + 16 || hitY < y || hitY >= y + 16) {
                    continue;
                }
                if (hits.size() == MAX_HITS) {
                    return finish(hits, true);
                }
                hits.add(new Hit(
                        new GeoCellRef(data.getSourceId(), data.revision(), x, y, layer.index()),
                        new GeoPoint(hitX, hitY, layer.height()),
                        layer.nswe(),
                        t));
            }
            if (exit >= maxDistance || firstCell && !hits.isEmpty()) {
                return finish(hits, false);
            }
            var crossX = tx <= ty;
            var crossY = ty <= tx;
            entered = Math.min(tx, ty);
            if (crossX) {
                x += stepX;
                tx += dtx;
            }
            if (crossY) {
                y += stepY;
                ty += dty;
            }
        }
        return finish(hits, false);
    }

    private static PickResult finish(ArrayList<Hit> hits, boolean truncated) {
        hits.sort(Comparator.comparingDouble(Hit::distance)
                .thenComparingInt(hit -> hit.cell().layer()));
        return new PickResult(hits, truncated);
    }
}
