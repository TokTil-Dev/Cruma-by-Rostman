package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import java.util.HashSet;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class GeoBodyCheck {
    static final int STEP = 8;

    private GeoBodyCheck() {}

    public record Narrow(GeoPoint at, int cellX, int cellY) {}

    public static @Nullable Narrow check(
            GeoDataAccess data, List<GeoPoint> path, int radius, BooleanSupplier cancelled) {
        if (radius <= 0 || path.isEmpty()) {
            return null;
        }
        var engine = new GeoQueryEngine(data);
        var checked = new HashSet<Long>();
        var height = path.getFirst().z();
        for (var index = 0; index < path.size(); index++) {
            var from = path.get(index == 0 ? 0 : index - 1);
            var to = path.get(index);
            var length = Math.hypot(to.x() - from.x(), to.y() - from.y());
            var steps = index == 0 ? 0 : Math.max(1, (int) Math.ceil(length / STEP));
            for (var step = 0; step <= steps; step++) {
                if (cancelled.getAsBoolean()) {
                    return null;
                }
                var t = steps == 0 ? 1 : (double) step / steps;
                var x = from.x() + (to.x() - from.x()) * t;
                var y = from.y() + (to.y() - from.y()) * t;
                var under = engine.findBaseCell(
                        data.cellAt((int) Math.floor(x), (int) Math.floor(y)), (int) Math.round(height));
                if (under != null) {
                    height = under.layer().height();
                }
                var narrow = findNarrowAround(data, engine, x, y, height, radius, checked);
                if (narrow != null) {
                    return narrow;
                }
            }
        }
        return null;
    }

    private static @Nullable Narrow findNarrowAround(
            GeoDataAccess data,
            GeoQueryEngine engine,
            double x,
            double y,
            double z,
            int radius,
            HashSet<Long> checked) {
        var home = cellKey(x, y);
        var fromX = (int) Math.floor((x - radius) / 16);
        var toX = (int) Math.floor((x + radius) / 16);
        var fromY = (int) Math.floor((y - radius) / 16);
        var toY = (int) Math.floor((y + radius) / 16);
        for (var cellX = fromX; cellX <= toX; cellX++) {
            for (var cellY = fromY; cellY <= toY; cellY++) {
                var minX = cellX * 16;
                var minY = cellY * 16;
                var dx = x - Math.clamp(x, minX, minX + 16);
                var dy = y - Math.clamp(y, minY, minY + 16);
                var distance = Math.hypot(dx, dy);
                var key = ((long) cellX << 32) ^ (cellY & 0xffffffffL);
                if (distance > radius || key == home) {
                    continue;
                }
                var band = key * 31 + Math.floorDiv((int) Math.round(z), 64);
                if (checked.contains(band)) {
                    continue;
                }
                var cell = data.cellAt(minX, minY);
                var open = false;
                if (cell.availability() == Availability.AVAILABLE) {
                    var floor = engine.findBaseCell(cell, (int) Math.round(z));
                    open = floor != null
                            && floor.layer().nswe() != 0
                            && Math.abs(floor.layer().height() - z) <= 48 + distance;
                }
                if (!open) {
                    return new Narrow(new GeoPoint(x, y, z), minX, minY);
                }
                checked.add(band);
            }
        }
        return null;
    }

    private static long cellKey(double x, double y) {
        var cellX = (int) Math.floor(x / 16);
        var cellY = (int) Math.floor(y / 16);
        return ((long) cellX << 32) ^ (cellY & 0xffffffffL);
    }
}
