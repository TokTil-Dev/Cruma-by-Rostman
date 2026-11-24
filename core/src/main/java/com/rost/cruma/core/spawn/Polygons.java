package com.rost.cruma.core.spawn;

import java.util.ArrayList;
import java.util.List;

public final class Polygons {
    private Polygons() {}

    public static boolean containsXy(List<TerritoryPoint> points, double x, double y) {
        var inside = false;
        var count = points.size();
        for (var current = 0; current < count; current++) {
            var first = points.get(current);
            var second = points.get(current + 1 == count ? 0 : current + 1);
            if ((first.y() < y && y <= second.y() || second.y() < y && y <= first.y())
                    && (double) (second.x() - first.x()) * (y - first.y()) / (second.y() - first.y()) < x - first.x()) {
                inside = !inside;
            }
        }
        return inside;
    }

    public static double area(List<TerritoryPoint> points) {
        var sum = 0.0;
        var count = points.size();
        for (int i = 0, j = count - 1; i < count; j = i++) {
            sum += (double) points.get(j).x() * points.get(i).y()
                    - (double) points.get(i).x() * points.get(j).y();
        }
        return Math.abs(sum) / 2.0;
    }

    public static boolean hasCrossingSides(List<TerritoryPoint> points) {
        var count = points.size();
        for (var i = 0; i < count; i++) {
            for (var j = i + 2; j < count; j++) {
                if (i == 0 && j == count - 1) {
                    continue;
                }
                var a = points.get(i);
                var b = points.get((i + 1) % count);
                var c = points.get(j);
                var d = points.get((j + 1) % count);
                if (side(a, b, c) * side(a, b, d) < 0 && side(c, d, a) * side(c, d, b) < 0) {
                    return true;
                }
            }
        }
        return false;
    }

    public static double distanceXy(List<TerritoryPoint> points, double x, double y) {
        if (points.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        if (containsXy(points, x, y)) {
            return 0;
        }
        var nearest = Double.POSITIVE_INFINITY;
        var count = points.size();
        for (var i = 0; i < count; i++) {
            var a = points.get(i);
            var b = points.get((i + 1) % count);
            double dx = b.x() - a.x();
            double dy = b.y() - a.y();
            var length = dx * dx + dy * dy;
            var t = length == 0 ? 0 : Math.clamp(((x - a.x()) * dx + (y - a.y()) * dy) / length, 0, 1);
            nearest = Math.min(nearest, Math.hypot(x - (a.x() + dx * t), y - (a.y() + dy * t)));
        }
        return nearest;
    }

    public static List<TerritoryPoint> clip(
            List<TerritoryPoint> points, double minX, double minY, double maxX, double maxY) {
        if (points.isEmpty()) {
            return List.of();
        }
        var bottom = points.getFirst().bottom();
        var top = points.getFirst().top();
        var xs = new ArrayList<Double>();
        var ys = new ArrayList<Double>();
        for (var point : points) {
            xs.add((double) point.x());
            ys.add((double) point.y());
        }
        double[][] edges = {{0, minX, 1}, {0, maxX, -1}, {1, minY, 1}, {1, maxY, -1}};
        for (var edge : edges) {
            var axis = (int) edge[0];
            var limit = edge[1];
            var keep = edge[2];
            var nextXs = new ArrayList<Double>();
            var nextYs = new ArrayList<Double>();
            var count = xs.size();
            for (var i = 0; i < count; i++) {
                var x1 = xs.get(i);
                var y1 = ys.get(i);
                var x2 = xs.get((i + 1) % count);
                var y2 = ys.get((i + 1) % count);
                var v1 = axis == 0 ? x1 : y1;
                var v2 = axis == 0 ? x2 : y2;
                var in1 = (v1 - limit) * keep >= 0;
                var in2 = (v2 - limit) * keep >= 0;
                if (in1) {
                    nextXs.add(x1);
                    nextYs.add(y1);
                }
                if (in1 != in2) {
                    var t = (limit - v1) / (v2 - v1);
                    nextXs.add(axis == 0 ? limit : x1 + (x2 - x1) * t);
                    nextYs.add(axis == 0 ? y1 + (y2 - y1) * t : limit);
                }
            }
            xs = nextXs;
            ys = nextYs;
            if (xs.isEmpty()) {
                return List.of();
            }
        }
        var clipped = new ArrayList<TerritoryPoint>();
        for (var i = 0; i < xs.size(); i++) {
            var point = new TerritoryPoint((int) Math.round(xs.get(i)), (int) Math.round(ys.get(i)), bottom, top);
            if (clipped.isEmpty() || !isSamePlace(clipped.getLast(), point)) {
                clipped.add(point);
            }
        }
        while (clipped.size() > 1 && isSamePlace(clipped.getFirst(), clipped.getLast())) {
            clipped.removeLast();
        }
        return clipped;
    }

    private static boolean isSamePlace(TerritoryPoint a, TerritoryPoint b) {
        return a.x() == b.x() && a.y() == b.y();
    }

    private static int side(TerritoryPoint a, TerritoryPoint b, TerritoryPoint c) {
        return Long.signum((long) (b.x() - a.x()) * (c.y() - a.y()) - (long) (b.y() - a.y()) * (c.x() - a.x()));
    }
}
