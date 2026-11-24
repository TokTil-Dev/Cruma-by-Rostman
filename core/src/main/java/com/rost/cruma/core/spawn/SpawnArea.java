package com.rost.cruma.core.spawn;

import com.rost.cruma.core.spawn.NpcPosDocument.State;
import com.rost.cruma.core.spawn.SpawnPosition.Fixed;
import com.rost.cruma.core.spawn.SpawnPosition.Weighted;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

public record SpawnArea(double minX, double minY, double maxX, double maxY, boolean round) {
    public record Hit(int index, double distance) {}

    public static SpawnArea around(double x, double y, double radius) {
        return new SpawnArea(x - radius, y - radius, x + radius, y + radius, true);
    }

    public static SpawnArea box(double minX, double minY, double maxX, double maxY) {
        return new SpawnArea(minX, minY, maxX, maxY, false);
    }

    public double x() {
        return (minX + maxX) * .5;
    }

    public double y() {
        return (minY + maxY) * .5;
    }

    public boolean holds(double pointX, double pointY) {
        if (round) {
            return Math.hypot(pointX - x(), pointY - y()) <= (maxX - minX) * .5;
        }
        return pointX >= minX && pointX <= maxX && pointY >= minY && pointY <= maxY;
    }

    public boolean reaches(SpawnTerritory zone) {
        if (zone.points().isEmpty()) {
            return false;
        }
        return round ? distanceTo(zone) <= (maxX - minX) * .5 : zone.reaches(minX, minY, maxX, maxY);
    }

    public double distanceTo(SpawnTerritory zone) {
        if (zone.containsXy(x(), y())) {
            return 0;
        }
        var points = zone.points();
        var nearest = Double.MAX_VALUE;
        for (var current = 0; current < points.size(); current++) {
            var first = points.get(current);
            var second = points.get(current + 1 == points.size() ? 0 : current + 1);
            nearest = Math.min(nearest, distanceToSide(first, second));
        }
        return nearest;
    }

    public List<Hit> zones(State state) {
        var hits = new ArrayList<Hit>();
        for (var zone : state.territories()) {
            if (zone != null && !zone.domain() && reaches(zone)) {
                hits.add(new Hit(zone.index(), distanceTo(zone)));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::distance).thenComparingInt(Hit::index));
        return hits;
    }

    public List<Hit> makers(State state) {
        var zones = new HashMap<String, Hit>();
        var seen = new HashMap<String, Boolean>();
        for (var zone : state.territories()) {
            if (zone == null || zone.domain() || seen.putIfAbsent(zone.name(), true) != null) {
                continue;
            }
            if (reaches(zone)) {
                zones.put(zone.name(), new Hit(zone.index(), distanceTo(zone)));
            }
        }
        var hits = new ArrayList<Hit>();
        for (var maker : state.makers()) {
            if (maker == null) {
                continue;
            }
            var nearest = Double.MAX_VALUE;
            for (var name : maker.territoryNames()) {
                var zone = zones.get(name);
                if (zone != null) {
                    nearest = Math.min(nearest, zone.distance());
                }
            }
            for (var npc : maker.npcs()) {
                nearest = Math.min(nearest, distanceOfPlace(npc.position()));
            }
            if (nearest != Double.MAX_VALUE) {
                hits.add(new Hit(maker.index(), nearest));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::distance).thenComparingInt(Hit::index));
        return hits;
    }

    private double distanceToSide(TerritoryPoint first, TerritoryPoint second) {
        double dx = second.x() - first.x();
        double dy = second.y() - first.y();
        var length = dx * dx + dy * dy;
        var along = length == 0 ? 0 : ((x() - first.x()) * dx + (y() - first.y()) * dy) / length;
        var clamped = Math.clamp(along, 0, 1);
        return Math.hypot(first.x() + clamped * dx - x(), first.y() + clamped * dy - y());
    }

    private double distanceOfPlace(SpawnPosition position) {
        var nearest = Double.MAX_VALUE;
        if (position instanceof Fixed fixed && holds(fixed.x(), fixed.y())) {
            nearest = Math.hypot(fixed.x() - x(), fixed.y() - y());
        } else if (position instanceof Weighted weighted) {
            for (var point : weighted.points()) {
                if (holds(point.x(), point.y())) {
                    nearest = Math.min(nearest, Math.hypot(point.x() - x(), point.y() - y()));
                }
            }
        }
        return nearest;
    }
}
