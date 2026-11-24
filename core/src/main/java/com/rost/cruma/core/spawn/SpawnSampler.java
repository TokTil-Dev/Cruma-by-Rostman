package com.rost.cruma.core.spawn;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.GeoQueryEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

public final class SpawnSampler {
    private static final int BASE_DENSITY = 400;
    private static final double STEP_TEST = 100.0;
    private static final int SKY_BOTTOM = -500;
    private static final int SKY_TOP = 0x157c;

    private SpawnSampler() {}

    public record Candidates(
            List<GeoPoint> points, boolean usedFallback, boolean geodataMissing, boolean clientFloors) {
        public Candidates {
            points = List.copyOf(points);
        }

        public Candidates(List<GeoPoint> points, boolean usedFallback, boolean geodataMissing) {
            this(points, usedFallback, geodataMissing, false);
        }
    }

    public static Candidates findCandidates(
            List<SpawnTerritory> territories,
            List<SpawnTerritory> banned,
            int maximumNpc,
            boolean sky,
            GeoDataAccess geodata,
            Random random,
            BooleanSupplier cancelled) {
        var density = Math.max(BASE_DENSITY, maximumNpc);
        if (density > BASE_DENSITY) {
            density *= 4;
        }
        var stepDivisor = (int) Math.sqrt(density);
        var engine = geodata == null ? null : new GeoQueryEngine(geodata);
        var positions = new ArrayList<GeoPoint>();
        var missing = false;
        for (var territory : territories) {
            if (territory.points().isEmpty()) {
                continue;
            }
            var top = territory.top();
            var bottom = territory.bottom();
            if (sky) {
                top = Math.clamp(top, SKY_BOTTOM, SKY_TOP);
                bottom = Math.clamp(bottom, SKY_BOTTOM, SKY_TOP);
            }
            var west = territory.minX();
            var east = territory.maxX();
            var north = territory.minY();
            var south = territory.maxY();
            var stepX = Math.max(1, (east - west) / stepDivisor);
            var stepY = Math.max(1, (south - north) / stepDivisor);
            for (var x = west; x < east; x += stepX) {
                if (cancelled.getAsBoolean()) {
                    return new Candidates(positions, false, missing);
                }
                for (var y = north; y < south; y += stepY) {
                    if (sky) {
                        for (var attempt = 0; attempt < 3; attempt++) {
                            var candidate = new GeoPoint(x, y, randomBetween(random, bottom, top));
                            if (isInside(territory, banned, candidate)) {
                                positions.add(candidate);
                                break;
                            }
                        }
                        continue;
                    }
                    if (engine == null) {
                        missing = true;
                        continue;
                    }
                    var ground = findGround(engine, geodata, x, y, top);
                    if (ground == null) {
                        missing = true;
                        continue;
                    }
                    if (isInside(territory, banned, ground) && hasRoomToStep(engine, ground)) {
                        positions.add(ground);
                    }
                }
            }
        }
        var fallback = false;
        if (!sky && positions.isEmpty() && engine != null) {
            fallback = true;
            for (var territory : territories) {
                var center = findGround(
                        engine, geodata, (int) territory.centerX(), (int) territory.centerY(), territory.top());
                if (center != null && isInside(territory, banned, center)) {
                    positions.add(center);
                }
            }
            for (var attempt = 0; positions.size() < 25 && attempt < 50 && !territories.isEmpty(); attempt++) {
                var territory = territories.get(random.nextInt(territories.size()));
                var x = randomBetween(random, territory.minX(), territory.maxX());
                var y = randomBetween(random, territory.minY(), territory.maxY());
                var ground = findGround(engine, geodata, x, y, territory.top());
                if (ground != null && isInside(territory, banned, ground)) {
                    positions.add(ground);
                }
            }
        }
        return new Candidates(positions, fallback, missing);
    }

    public static List<GeoPoint> pick(List<GeoPoint> candidates, int count, Random random) {
        if (candidates.isEmpty() || count <= 0) {
            return List.of();
        }
        var picked = new ArrayList<GeoPoint>(count);
        for (var i = 0; i < count; i++) {
            picked.add(candidates.get(random.nextInt(candidates.size())));
        }
        return picked;
    }

    public static GeoPoint findGround(GeoQueryEngine engine, GeoDataAccess geodata, int x, int y, int fromZ) {
        var cell = geodata.cellAt(x, y);
        if (cell.availability() != Availability.AVAILABLE || cell.layers().isEmpty()) {
            return null;
        }
        var selection = engine.findBaseCell(cell, fromZ);
        return selection == null ? null : new GeoPoint(x, y, selection.layer().height());
    }

    private static boolean isInside(SpawnTerritory territory, List<SpawnTerritory> banned, GeoPoint point) {
        for (var area : banned) {
            if (area.contains(point.x(), point.y(), point.z())) {
                return false;
            }
        }
        return territory.contains(point.x(), point.y(), point.z());
    }

    private static boolean hasRoomToStep(GeoQueryEngine engine, GeoPoint point) {
        double[][] offsets = {{STEP_TEST, 0}, {-STEP_TEST, 0}, {0, STEP_TEST}, {0, -STEP_TEST}};
        for (var offset : offsets) {
            var target = new GeoPoint(point.x() + offset[0], point.y() + offset[1], point.z());
            var targetCell = engine.findBaseCell(target);
            var destination = targetCell == null
                    ? target
                    : new GeoPoint(target.x(), target.y(), targetCell.layer().height());
            if (!engine.moveStraight(point, destination, STEP_TEST).success()) {
                return false;
            }
        }
        return true;
    }

    private static int randomBetween(Random random, int minimum, int maximum) {
        if (maximum < minimum) {
            var swap = minimum;
            minimum = maximum;
            maximum = swap;
        }
        return minimum + (int) (random.nextDouble() * ((long) maximum - minimum + 1));
    }
}
