package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeTerrainSteadyLighting.Sphere;
import com.rost.cruma.core.editing.NativeTerrainTimeLighting.Window;
import com.rost.cruma.core.editing.TerrainNativeCodec.Light;
import com.rost.cruma.core.editing.TerrainNativeCodec.Sector;
import com.rost.cruma.core.editing.TerrainPackageSnapshot.Terrain;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class TerrainLightingFidelity {
    private TerrainLightingFidelity() {}

    public record Stats(
            int sectors,
            long savedLights,
            long rebuiltLights,
            long sharedLights,
            long maskBits,
            long maskBitsAgree,
            long colorVertices,
            long colorVerticesExact,
            long colorAbsDiffSum,
            int colorMaxDiff,
            long timeSamples,
            long timeSamplesExact,
            long timeAbsDiffSum,
            int timeMaxDiff,
            long timeLitAgree,
            long timeOnlyRebuiltLit,
            long maskOnlyRebuilt,
            long rays) {
        public static final Stats EMPTY = new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        public Stats plus(Stats other) {
            return new Stats(
                    sectors + other.sectors,
                    savedLights + other.savedLights,
                    rebuiltLights + other.rebuiltLights,
                    sharedLights + other.sharedLights,
                    maskBits + other.maskBits,
                    maskBitsAgree + other.maskBitsAgree,
                    colorVertices + other.colorVertices,
                    colorVerticesExact + other.colorVerticesExact,
                    colorAbsDiffSum + other.colorAbsDiffSum,
                    Math.max(colorMaxDiff, other.colorMaxDiff),
                    timeSamples + other.timeSamples,
                    timeSamplesExact + other.timeSamplesExact,
                    timeAbsDiffSum + other.timeAbsDiffSum,
                    Math.max(timeMaxDiff, other.timeMaxDiff),
                    timeLitAgree + other.timeLitAgree,
                    timeOnlyRebuiltLit + other.timeOnlyRebuiltLit,
                    maskOnlyRebuilt + other.maskOnlyRebuilt,
                    rays + other.rays);
        }

        public double maskAgreement() {
            return ratio(maskBitsAgree, maskBits);
        }

        public double colorExactShare() {
            return ratio(colorVerticesExact, colorVertices);
        }

        public double colorMeanDiff() {
            return colorVertices == 0 ? 0 : colorAbsDiffSum / (3.0 * colorVertices);
        }

        public double timeExactShare() {
            return ratio(timeSamplesExact, timeSamples);
        }

        public double timeMeanDiff() {
            return timeSamples == 0 ? 0 : (double) timeAbsDiffSum / timeSamples;
        }

        public double timeLitAgreement() {
            return ratio(timeLitAgree, timeSamples);
        }

        private static double ratio(long part, long whole) {
            return whole == 0 ? 1 : (double) part / whole;
        }
    }

    public static Stats measure(
            TerrainLightingScene scene, Terrain terrain, List<Integer> sectorIndices, BooleanSupplier cancelled) {
        return measure(scene, terrain, sectorIndices, 0, cancelled);
    }

    public static Stats measure(
            TerrainLightingScene scene,
            Terrain terrain,
            List<Integer> sectorIndices,
            int margin,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled);
        var field = new TerrainHeightField(
                terrain.heightmap().getFirst(), terrain.info().toWorld());
        var steadyGrid = scene.createSteadyGrid(field);
        var timeGrid = scene.createTimeGrid(field, cancelled);
        var colors = terrain.info().vertexColors();
        var width = field.getWidth();
        var total = Stats.EMPTY;
        for (var index : sectorIndices) {
            var sector = terrain.sectors().get(index);
            total = total.plus(
                    measureSector(scene, field, steadyGrid, timeGrid, sector, colors, width, margin, cancelled));
        }
        return total;
    }

    private static Stats measureSector(
            TerrainLightingScene scene,
            TerrainHeightField field,
            NativeTerrainSteadyLighting.Grid steadyGrid,
            NativeTerrainTimeLighting.Grid timeGrid,
            Sector sector,
            List<Integer> colors,
            int width,
            int margin,
            BooleanSupplier cancelled) {
        var sx = TerrainLightingRebuild.effectiveSize(sector.offsetX(), sector.sizeX(), field.getWidth());
        var sy = TerrainLightingRebuild.effectiveSize(sector.offsetY(), sector.sizeY(), field.getHeight());
        var limitX = field.getWidth() - 1 - margin;
        var limitY = field.getHeight() - 1 - margin;
        var ox = sector.offsetX();
        var oy = sector.offsetY();
        var vertexCount = (sx + 1) * (sy + 1);
        var sphere = NativeTerrainSectorBounds.lightingSphere(sector.bounds());
        var steady = NativeTerrainSteadyLighting.rebuild(
                steadyGrid,
                scene.getSteadyOwner(),
                new NativeTerrainSteadyLighting.Sector(
                        sector.offsetX(), sector.offsetY(), sx, sy, new Sphere(sphere.center(), sphere.radius())),
                scene.getSteadySources(),
                scene.getQuery(),
                scene.getSteadyBaseColor(),
                cancelled);
        var saved = new HashMap<Integer, Light>();
        for (var light : sector.lights()) {
            saved.put(light.reference(), light);
        }
        long shared = 0, bits = 0, bitsAgree = 0, onlyRebuilt = 0;
        var rebuilt = new HashMap<Integer, Light>();
        for (var light : steady.lights()) {
            rebuilt.put(light.reference(), light);
        }
        var all = new HashSet<Integer>(saved.keySet());
        all.addAll(rebuilt.keySet());
        for (var reference : all) {
            var a = saved.get(reference);
            var b = rebuilt.get(reference);
            if (a != null && b != null) {
                shared++;
            }
            for (var v = 0; v < vertexCount; v++) {
                var gx = ox + v % (sx + 1);
                var gy = oy + v / (sx + 1);
                if (gx < margin || gy < margin || gx > limitX || gy > limitY) {
                    continue;
                }
                bits++;
                if (isVertexVisible(a, v) == isVertexVisible(b, v)) {
                    bitsAgree++;
                } else if (isVertexVisible(b, v)) {
                    onlyRebuilt++;
                }
            }
        }
        long colorExact = 0, colorDiff = 0;
        var colorMax = 0;
        long colorVertices = 0;
        if (colors.size() == width * field.getHeight() * 4) {
            for (var ly = 0; ly <= sy; ly++) {
                for (var lx = 0; lx <= sx; lx++) {
                    if (ox + lx < margin || oy + ly < margin || ox + lx > limitX || oy + ly > limitY) {
                        continue;
                    }
                    var local = (ly * (sx + 1) + lx) * 4;
                    var global = ((sector.offsetY() + ly) * width + sector.offsetX() + lx) * 4;
                    var exact = true;
                    for (var c = 0; c < 3; c++) {
                        var d = Math.abs(steady.colors().get(local + c) - colors.get(global + c));
                        colorDiff += d;
                        colorMax = Math.max(colorMax, d);
                        exact &= d == 0;
                    }
                    colorVertices++;
                    if (exact) {
                        colorExact++;
                    }
                }
            }
        }
        long timeSamples = 0, timeExact = 0, timeDiff = 0, litAgree = 0, onlyRebuiltLit = 0;
        var timeMax = 0;
        long rays = steady.tracedRays();
        var savedMaps = scene.getSavedSunlight(sector);
        if (!scene.getSlots().isEmpty() && savedMaps.size() == scene.getSlots().size()) {
            var time = NativeTerrainTimeLighting.rebuild(
                    timeGrid,
                    scene.getTimeOwner(),
                    new Window(sector.offsetX(), sector.offsetY(), sx + 1, sy + 1),
                    scene.getTimeSources(),
                    scene.getSlots(),
                    scene.getQuery(),
                    cancelled);
            rays += time.tracedRays();
            for (var f = 0; f < scene.getSlots().size(); f++) {
                var values = time.frames().get(f).intensities();
                var savedMap = savedMaps.get(f);
                for (var ly = 0; ly <= sy; ly++) {
                    for (var lx = 0; lx <= sx; lx++) {
                        if (ox + lx < margin || oy + ly < margin || ox + lx > limitX || oy + ly > limitY) {
                            continue;
                        }
                        var mine = values.get(ly * (sx + 1) + lx);
                        var theirs = savedMap.get(ly * 17 + lx);
                        var d = Math.abs(mine - theirs);
                        timeSamples++;
                        timeDiff += d;
                        timeMax = Math.max(timeMax, d);
                        if (d == 0) {
                            timeExact++;
                        }
                        if ((mine == 0) == (theirs == 0)) {
                            litAgree++;
                        } else if (mine != 0) {
                            onlyRebuiltLit++;
                        }
                    }
                }
            }
        }
        return new Stats(
                1,
                saved.size(),
                rebuilt.size(),
                shared,
                bits,
                bitsAgree,
                colorVertices,
                colorExact,
                colorDiff,
                colorMax,
                timeSamples,
                timeExact,
                timeDiff,
                timeMax,
                litAgree,
                onlyRebuiltLit,
                onlyRebuilt,
                rays);
    }

    private static boolean isVertexVisible(Light light, int vertex) {
        if (light == null) {
            return false;
        }
        var bytes = light.visibility();
        var index = vertex >>> 3;
        return index < bytes.size() && (bytes.get(index) & (1 << (vertex & 7))) != 0;
    }
}
