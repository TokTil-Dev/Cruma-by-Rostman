package com.rost.cruma.core.area;

import com.rost.cruma.core.spawn.Polygons;
import com.rost.cruma.core.spawn.TerritoryPoint;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public record AreaShape(
        List<TerritoryPoint> points,
        List<List<TerritoryPoint>> banned,
        @Nullable WaterBox waterBox,
        List<TerritoryPoint> teleportRange,
        List<AreaPoint> teleportPoints,
        List<AreaPoint> restartPoints,
        int bottom,
        int top,
        int minX,
        int maxX,
        int minY,
        int maxY,
        double centerX,
        double centerY,
        double area) {
    public static final AreaShape EMPTY = of(List.of());

    public AreaShape {
        points = List.copyOf(points);
        banned = banned.stream().map(List::copyOf).toList();
        teleportRange = List.copyOf(teleportRange);
        teleportPoints = List.copyOf(teleportPoints);
        restartPoints = List.copyOf(restartPoints);
    }

    public static AreaShape of(List<AreaSetting> settings) {
        String range = null;
        String water = null;
        String banned = null;
        String teleportRange = null;
        String teleportPoints = null;
        String restartPoints = null;
        for (var setting : settings) {
            switch (setting.key()) {
                case "range" -> range = setting.value();
                case "water_range" -> water = setting.value();
                case "banned_range" -> banned = setting.value();
                case "teleport_range" -> teleportRange = setting.value();
                case "teleport_points" -> teleportPoints = setting.value();
                case "restart_points" -> restartPoints = setting.value();
                default -> {}
            }
        }
        var box = water == null ? null : AreaValues.parseWaterBox(water);
        List<TerritoryPoint> points;
        if (box != null) {
            points = box.corners();
        } else {
            points = range == null ? null : AreaValues.parsePoints(range);
        }
        if (points == null) {
            points = List.of();
        }
        var bottom = Integer.MAX_VALUE;
        var top = Integer.MIN_VALUE;
        var minX = Integer.MAX_VALUE;
        var maxX = Integer.MIN_VALUE;
        var minY = Integer.MAX_VALUE;
        var maxY = Integer.MIN_VALUE;
        var sumX = 0.0;
        var sumY = 0.0;
        for (var point : points) {
            bottom = Math.min(bottom, point.bottom());
            top = Math.max(top, point.top());
            minX = Math.min(minX, point.x());
            maxX = Math.max(maxX, point.x());
            minY = Math.min(minY, point.y());
            maxY = Math.max(maxY, point.y());
            sumX += point.x();
            sumY += point.y();
        }
        var empty = points.isEmpty();
        var count = Math.max(1, points.size());
        return new AreaShape(
                points,
                orEmpty(banned == null ? null : AreaValues.parsePolygons(banned)),
                box,
                orEmpty(teleportRange == null ? null : AreaValues.parsePoints(teleportRange)),
                orEmpty(teleportPoints == null ? null : AreaValues.parsePlaces(teleportPoints)),
                orEmpty(restartPoints == null ? null : AreaValues.parsePlaces(restartPoints)),
                empty ? 0 : bottom,
                empty ? 0 : top,
                empty ? 0 : minX,
                empty ? 0 : maxX,
                empty ? 0 : minY,
                empty ? 0 : maxY,
                sumX / count,
                sumY / count,
                Polygons.area(points));
    }

    public boolean containsXy(double x, double y) {
        return Polygons.containsXy(points, x, y);
    }

    public boolean contains(double x, double y, double z) {
        var height = (int) z;
        if (points.isEmpty() || height < bottom || height > top || !containsXy(x, y)) {
            return false;
        }
        for (var polygon : banned) {
            if (polygon.isEmpty()) {
                continue;
            }
            var lowest = Integer.MAX_VALUE;
            var highest = Integer.MIN_VALUE;
            for (var point : polygon) {
                lowest = Math.min(lowest, point.bottom());
                highest = Math.max(highest, point.top());
            }
            if (height >= lowest && height <= highest && Polygons.containsXy(polygon, x, y)) {
                return false;
            }
        }
        return true;
    }

    private static <T> List<T> orEmpty(@Nullable List<T> values) {
        return values == null ? List.of() : values;
    }
}
