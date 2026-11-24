package com.rost.cruma.core.spawn;

import java.util.List;

public record SpawnTerritory(
        int index,
        String name,
        boolean domain,
        int domainId,
        List<TerritoryPoint> points,
        int line,
        SourceSpan span,
        SourceSpan nameSpan,
        SourceSpan pointsSpan) {
    public SpawnTerritory {
        points = List.copyOf(points);
    }

    public SpawnTerritory withPoints(List<TerritoryPoint> newPoints) {
        return new SpawnTerritory(index, name, domain, domainId, newPoints, line, span, nameSpan, pointsSpan);
    }

    public SpawnTerritory withName(String newName) {
        return new SpawnTerritory(index, newName, domain, domainId, points, line, span, nameSpan, pointsSpan);
    }

    public int bottom() {
        var bottom = Integer.MAX_VALUE;
        for (var point : points) {
            bottom = Math.min(bottom, point.bottom());
        }
        return points.isEmpty() ? 0 : bottom;
    }

    public int top() {
        var top = Integer.MIN_VALUE;
        for (var point : points) {
            top = Math.max(top, point.top());
        }
        return points.isEmpty() ? 0 : top;
    }

    public int minX() {
        var min = Integer.MAX_VALUE;
        for (var point : points) {
            min = Math.min(min, point.x());
        }
        return points.isEmpty() ? 0 : min;
    }

    public int maxX() {
        var max = Integer.MIN_VALUE;
        for (var point : points) {
            max = Math.max(max, point.x());
        }
        return points.isEmpty() ? 0 : max;
    }

    public int minY() {
        var min = Integer.MAX_VALUE;
        for (var point : points) {
            min = Math.min(min, point.y());
        }
        return points.isEmpty() ? 0 : min;
    }

    public int maxY() {
        var max = Integer.MIN_VALUE;
        for (var point : points) {
            max = Math.max(max, point.y());
        }
        return points.isEmpty() ? 0 : max;
    }

    public boolean containsXy(double x, double y) {
        return Polygons.containsXy(points, x, y);
    }

    public boolean reaches(double minX, double minY, double maxX, double maxY) {
        return insideCorners(minX, minY, maxX, maxY) > 0 || crosses(minX, minY, maxX, maxY);
    }

    public boolean covers(double minX, double minY, double maxX, double maxY) {
        return insideCorners(minX, minY, maxX, maxY) == 4 && !crosses(minX, minY, maxX, maxY);
    }

    public boolean contains(double x, double y, double z) {
        var height = (int) z;
        return height >= bottom() && height <= top() && containsXy(x, y);
    }

    public double area() {
        return Polygons.area(points);
    }

    public double centerX() {
        var sum = 0L;
        for (var point : points) {
            sum += point.x();
        }
        return points.isEmpty() ? 0 : (double) sum / points.size();
    }

    public double centerY() {
        var sum = 0L;
        for (var point : points) {
            sum += point.y();
        }
        return points.isEmpty() ? 0 : (double) sum / points.size();
    }

    private int insideCorners(double minX, double minY, double maxX, double maxY) {
        var inside = 0;
        inside += containsXy(minX, minY) ? 1 : 0;
        inside += containsXy(maxX, minY) ? 1 : 0;
        inside += containsXy(maxX, maxY) ? 1 : 0;
        inside += containsXy(minX, maxY) ? 1 : 0;
        return inside;
    }

    private boolean crosses(double minX, double minY, double maxX, double maxY) {
        var count = points.size();
        for (var current = 0; current < count; current++) {
            var first = points.get(current);
            var second = points.get(current + 1 == count ? 0 : current + 1);
            if (meets(first, second, minX, minY, maxX, maxY)) {
                return true;
            }
        }
        return false;
    }

    private static boolean meets(
            TerritoryPoint first, TerritoryPoint second, double minX, double minY, double maxX, double maxY) {
        double dx = second.x() - first.x();
        double dy = second.y() - first.y();
        double[] steps = {-dx, dx, -dy, dy};
        double[] rooms = {first.x() - minX, maxX - first.x(), first.y() - minY, maxY - first.y()};
        var enters = 0.0;
        var leaves = 1.0;
        for (var side = 0; side < steps.length; side++) {
            if (steps[side] == 0) {
                if (rooms[side] < 0) {
                    return false;
                }
            } else if (steps[side] < 0) {
                enters = Math.max(enters, rooms[side] / steps[side]);
            } else {
                leaves = Math.min(leaves, rooms[side] / steps[side]);
            }
        }
        return enters <= leaves;
    }
}
