package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeTerrainSteadyLighting.Source;
import com.rost.cruma.core.editing.NativeTerrainTimeLighting.Window;
import com.rost.cruma.core.editing.TerrainActorShadows.Selection;
import com.rost.cruma.core.editing.TerrainNativeCodec.Light;
import com.rost.cruma.core.editing.TerrainNativeCodec.Sector;
import com.rost.cruma.core.editing.TerrainPackageSnapshot.Terrain;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class TerrainLightingRebuild {
    private static final int KEEP = -1;

    private TerrainLightingRebuild() {}

    private enum Mode {
        SAVED,
        DROP,
        CHANGED_FRESH,
        FRESH
    }

    public record Stats(
            int changedSamples,
            int changedVertices,
            int affectedSectors,
            int movedBounds,
            int lightsAdded,
            int lightsRemoved,
            int steadyBitsChanged,
            int colorsChanged,
            int timeSamplesChanged,
            long rays) {}

    public record Result(
            Map<Integer, Sector> sectors,
            List<Integer> colors,
            Optional<TerrainTimeMapCodec> time,
            Set<Integer> changedVertices,
            Stats stats) {
        public Result {
            sectors = Map.copyOf(sectors);
            colors = List.copyOf(colors);
            changedVertices = Set.copyOf(changedVertices);
        }
    }

    public static Result rebuild(
            TerrainLightingScene scene,
            Terrain terrain,
            Optional<TerrainTimeMapCodec> savedTime,
            TerrainHeightField original,
            TerrainHeightField edited,
            BooleanSupplier cancelled) {
        return rebuild(scene, terrain, savedTime, original, edited, Selection.NONE, cancelled);
    }

    public static Result rebuild(
            TerrainLightingScene scene,
            Terrain terrain,
            Optional<TerrainTimeMapCodec> savedTime,
            TerrainHeightField original,
            TerrainHeightField edited,
            Selection shadows,
            BooleanSupplier cancelled) {
        return rebuild(scene, scene, terrain, savedTime, original, edited, shadows, cancelled);
    }

    public static Result rebuild(
            TerrainLightingScene scene,
            TerrainLightingScene after,
            Terrain terrain,
            Optional<TerrainTimeMapCodec> savedTime,
            TerrainHeightField original,
            TerrainHeightField edited,
            Selection shadows,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(scene);
        Objects.requireNonNull(after);
        Objects.requireNonNull(shadows);
        Objects.requireNonNull(cancelled);
        var width = original.getWidth();
        var height = original.getHeight();
        if (edited.getWidth() != width
                || edited.getHeight() != height
                || terrain.info().width() != width
                || terrain.info().height() != height) {
            throw new IllegalArgumentException("Edited Terrain grid differs from its source");
        }
        if (scene.getSummary().terrainCasters() > 0) {
            throw new IllegalArgumentException(
                    "This Terrain casts shadows on itself, and Cruma cannot yet find every vertex whose shadow"
                            + " changes");
        }
        var edgeTurns = scene.getEdgeTurns();
        var visibleQuads = scene.getVisibleQuads();
        var newTurns = after.getEdgeTurns();
        var newVisible = after.getVisibleQuads();
        var inverted = scene.isInverted();
        var changedQuads = (BitSet) visibleQuads.clone();
        changedQuads.xor(newVisible);
        var turned = (BitSet) edgeTurns.clone();
        turned.xor(newTurns);
        changedQuads.or(turned);

        var moved = new TreeSet<Integer>();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                if (original.getHeightAt(x, y) != edited.getHeightAt(x, y)) {
                    moved.add(y * width + x);
                }
            }
        }
        var changed = new TreeSet<Integer>();
        if (moved.isEmpty() && shadows.isEmpty() && changedQuads.isEmpty()) {
            return new Result(
                    Map.of(),
                    terrain.info().vertexColors(),
                    savedTime,
                    Set.of(),
                    new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        }
        var oldZones = scene.computeOwnerZoneSamples(original);
        var newZones = scene.computeOwnerZoneSamples(edited);
        var touched = new TreeSet<>(moved);
        for (var quad = changedQuads.nextSetBit(0); quad >= 0; quad = changedQuads.nextSetBit(quad + 1)) {
            var qx = quad % width;
            var qy = quad / width;
            for (var corner = 0; corner < 4; corner++) {
                var cx = Math.min(width - 1, qx + (corner & 1));
                var cy = Math.min(height - 1, qy + (corner >> 1));
                touched.add(cy * width + cx);
            }
        }
        for (var vertex : expandByRadius(touched, width, height, 3)) {
            checkCancelled(cancelled);
            var x = vertex % width;
            var y = vertex / width;
            var source = scene.getLightingSource(vertex, width);
            var sx = source % width;
            if (moved.contains(source)
                    || !original.getNormalAt(sx, y, edgeTurns, inverted)
                            .equals(edited.getNormalAt(sx, y, newTurns, inverted))
                    || isSteadyEligible(oldZones, visibleQuads, width, height, x, y)
                            != isSteadyEligible(newZones, newVisible, width, height, x, y)
                    || isDrawn(visibleQuads, width, height, x, y) != isDrawn(newVisible, width, height, x, y)) {
                changed.add(vertex);
            }
        }

        var sectors = terrain.sectors();
        var affected = new TreeSet<Integer>();
        var freshSources = shadows.mergeSources(scene.getSteadySources());
        for (var i = 0; i < sectors.size(); i++) {
            var sector = sectors.get(i);
            if (sector.lights().stream()
                            .anyMatch(light -> shadows.getMoved().containsKey(light.reference())
                                    || shadows.getDeleted().contains(light.reference()))
                    || shadows.getMoved().values().stream()
                            .anyMatch(source -> isAdmitted(
                                    scene, source, NativeTerrainSectorBounds.lightingSphere(sector.bounds())))) {
                affected.add(i);
            }
            var sx = effectiveSize(sector.offsetX(), sector.sizeX(), width);
            var sy = effectiveSize(sector.offsetY(), sector.sizeY(), height);
            for (var vertex : changed) {
                var x = vertex % width;
                var y = vertex / width;
                if (x >= sector.offsetX()
                        && x <= sector.offsetX() + sx
                        && y >= sector.offsetY()
                        && y <= sector.offsetY() + sy) {
                    affected.add(i);
                    break;
                }
            }
            if (!affected.contains(i)
                    && sector.lights().stream()
                            .anyMatch(light -> shadows.getRecolored().containsKey(light.reference()))) {
                affected.add(i);
            }
            for (var ly = 0; ly <= sy && shadows.getVertexCount() > 0 && !affected.contains(i); ly++) {
                for (var lx = 0; lx <= sx; lx++) {
                    if (shadows.touches((sector.offsetY() + ly) * width + sector.offsetX() + lx)) {
                        affected.add(i);
                        break;
                    }
                }
            }
        }

        var query = shadows.query(scene);
        var steadyGrid = after.createSteadyGrid(edited);
        var oldSteadyGrid = scene.createSteadyGrid(original);
        var timeGrid = after.createTimeGrid(edited, cancelled);
        var compare = shadows.getVertexCount() > 0 && !shadows.isFullRebuild();
        var oldTimeGrid = compare ? scene.createTimeGrid(original, cancelled) : null;
        var colors = terrain.info().vertexColors();
        var steadySaved = colors.size() == width * height * 4;
        var newColors = new ArrayList<>(colors);
        var replacements = new LinkedHashMap<Integer, Sector>();
        var freshTime = new HashMap<Integer, int[]>();
        long rays = 0;
        int movedBounds = 0, added = 0, removed = 0, bitsChanged = 0, colorsChanged = 0;
        var colorOwner = new HashMap<Integer, Integer>();
        var colorMasks = new HashMap<Integer, Map<Integer, Boolean>>();
        var sectorLists = new HashMap<Integer, List<Light>>();
        for (var index : affected) {
            checkCancelled(cancelled);
            var sector = sectors.get(index);
            var sx = effectiveSize(sector.offsetX(), sector.sizeX(), width);
            var sy = effectiveSize(sector.offsetY(), sector.sizeY(), height);
            var vertexCount = (sx + 1) * (sy + 1);
            var cornersMoved = false;
            for (var corner : new int[][] {{0, 0}, {0, sy}, {sx, sy}, {sx, 0}}) {
                var x = sector.offsetX() + corner[0];
                var y = sector.offsetY() + corner[1];
                cornersMoved |= original.getHeightAt(x, y) != edited.getHeightAt(x, y);
            }
            var bounds = sector.bounds();
            if (cornersMoved) {
                bounds = NativeTerrainSectorBounds.compute(edited, sector).bounds();
                if (!bounds.equals(sector.bounds())) {
                    movedBounds++;
                }
            }
            var lights = sector.lights();
            if (steadySaved) {
                var oldSphere = NativeTerrainSectorBounds.lightingSphere(sector.bounds());
                var newSphere = NativeTerrainSectorBounds.lightingSphere(bounds);
                var original0 = NativeTerrainSteadyLighting.rebuild(
                        oldSteadyGrid,
                        scene.getSteadyOwner(),
                        new NativeTerrainSteadyLighting.Sector(
                                sector.offsetX(),
                                sector.offsetY(),
                                sx,
                                sy,
                                new NativeTerrainSteadyLighting.Sphere(oldSphere.center(), oldSphere.radius())),
                        scene.getSteadySources(),
                        scene.getQuery(),
                        cancelled);
                var fresh = NativeTerrainSteadyLighting.rebuild(
                        steadyGrid,
                        scene.getSteadyOwner(),
                        new NativeTerrainSteadyLighting.Sector(
                                sector.offsetX(),
                                sector.offsetY(),
                                sx,
                                sy,
                                new NativeTerrainSteadyLighting.Sphere(newSphere.center(), newSphere.radius())),
                        freshSources,
                        query,
                        cancelled);
                rays += original0.tracedRays() + fresh.tracedRays();
                var explained = new HashSet<Integer>();
                var originalBits = new HashMap<Integer, Light>();
                original0.lights().forEach(light -> {
                    explained.add(light.reference());
                    originalBits.put(light.reference(), light);
                });
                var freshBits = new HashMap<Integer, Light>();
                fresh.lights().forEach(light -> freshBits.put(light.reference(), light));
                var savedBits = new HashMap<Integer, Light>();
                sector.lights().forEach(light -> savedBits.put(light.reference(), light));
                var composed = new ArrayList<Light>();
                for (var source : freshSources) {
                    var reference = source.reference();
                    var inSaved = savedBits.containsKey(reference);
                    var inOriginal = explained.contains(reference);
                    var mode = shadows.getMoved().containsKey(reference)
                            ? isAdmitted(scene, source, newSphere) ? Mode.FRESH : Mode.DROP
                            : inSaved && !inOriginal
                                    ? Mode.SAVED
                                    : !isAdmitted(scene, source, newSphere)
                                            ? Mode.DROP
                                            : inSaved ? Mode.CHANGED_FRESH : inOriginal ? Mode.DROP : Mode.FRESH;
                    if (mode == Mode.DROP) {
                        continue;
                    }
                    if (mode == Mode.SAVED) {
                        composed.add(savedBits.get(reference));
                        continue;
                    }
                    var mask = new int[(vertexCount + 7) / 8];
                    var any = false;
                    for (var local = 0; local < vertexCount; local++) {
                        var global =
                                (sector.offsetY() + local / (sx + 1)) * width + sector.offsetX() + local % (sx + 1);
                        var bit = switch (mode) {
                            case SAVED -> isVertexVisible(savedBits.get(reference), local);
                            case CHANGED_FRESH ->
                                changed.contains(global)
                                                || shadows.isFullRebuild()
                                                || shadows.isSteadySelected(global, reference)
                                                        && isVertexVisible(freshBits.get(reference), local)
                                                                != isVertexVisible(originalBits.get(reference), local)
                                        ? isVertexVisible(freshBits.get(reference), local)
                                        : isVertexVisible(savedBits.get(reference), local);
                            default -> isVertexVisible(freshBits.get(reference), local);
                        };
                        if (bit) {
                            mask[local >>> 3] |= 1 << (local & 7);
                            any = true;
                        }
                        if (bit != isVertexVisible(savedBits.get(reference), local)) {
                            bitsChanged++;
                        }
                    }
                    if (any) {
                        var saved = savedBits.get(reference);
                        if (saved != null && saved.visibility().size() == mask.length && (vertexCount & 7) != 0) {
                            var unused = 0xff << (vertexCount & 7) & 0xff;
                            mask[mask.length - 1] |= saved.visibility().getLast() & unused;
                        }
                        composed.add(new Light(reference, toIntegerList(mask)));
                        if (!inSaved) {
                            added++;
                        }
                    }
                }
                var sourceReferences = new HashSet<Integer>();
                scene.getSteadySources().forEach(source -> sourceReferences.add(source.reference()));
                for (var light : sector.lights()) {
                    if (!sourceReferences.contains(light.reference())) {
                        composed.add(light);
                    }
                }
                composed = savedOrder(composed, sector.lights(), freshSources);
                for (var light : sector.lights()) {
                    if (composed.stream().noneMatch(next -> next.reference() == light.reference())) {
                        removed++;
                        for (var local = 0; local < vertexCount; local++) {
                            if (isVertexVisible(light, local)) {
                                bitsChanged++;
                            }
                        }
                    }
                }
                lights = composed;
                sectorLists.put(index, composed);
            }
            var timeMaps = sector.timeMaps();
            if ((sector.hasTimeMaps() != 0 || scene.hasRasterSunlight())
                    && !scene.getSlots().isEmpty()) {
                if (sector.hasTimeMaps() != 0
                        && sector.timeMaps().size() != scene.getSlots().size()) {
                    throw new IllegalArgumentException("Terrain sector time maps differ from the owner's time slots");
                }
                var time = NativeTerrainTimeLighting.rebuild(
                        timeGrid,
                        scene.getTimeOwner(),
                        new Window(sector.offsetX(), sector.offsetY(), sx + 1, sy + 1),
                        scene.getTimeSources(),
                        scene.getSlots(),
                        query,
                        cancelled);
                rays += time.tracedRays();
                var old = compare
                        ? NativeTerrainTimeLighting.rebuild(
                                oldTimeGrid,
                                scene.getTimeOwner(),
                                new Window(sector.offsetX(), sector.offsetY(), sx + 1, sy + 1),
                                scene.getTimeSources(),
                                scene.getSlots(),
                                scene.getQuery(),
                                cancelled)
                        : null;
                if (old != null) {
                    rays += old.tracedRays();
                }
                for (var local = 0; local < vertexCount; local++) {
                    var global = (sector.offsetY() + local / (sx + 1)) * width + sector.offsetX() + local % (sx + 1);
                    if ((changed.contains(global) || shadows.touches(global)) && !freshTime.containsKey(global)) {
                        var values = new int[scene.getSlots().size()];
                        var any = false;
                        for (var f = 0; f < values.length; f++) {
                            int value = time.frames().get(f).intensities().get(local);
                            if (changed.contains(global)
                                    || shadows.isFullRebuild()
                                    || shadows.isSunSelected(global, f)
                                            && old != null
                                            && value
                                                    != old.frames()
                                                            .get(f)
                                                            .intensities()
                                                            .get(local)
                                                            .intValue()) {
                                values[f] = value;
                                any = true;
                            } else {
                                values[f] = KEEP;
                            }
                        }
                        if (any) {
                            freshTime.put(global, values);
                        }
                    }
                }
            }
            replacements.put(
                    index,
                    new Sector(
                            sector.owner(),
                            sector.sizeX(),
                            sector.sizeY(),
                            sector.offsetX(),
                            sector.offsetY(),
                            bounds,
                            lights,
                            sector.hasTimeMaps(),
                            timeMaps,
                            sector.layerUniformity(),
                            sector.quadRenderPasses()));
        }

        var timeChanged = 0;
        Optional<TerrainTimeMapCodec> composedTime = savedTime;
        if (!freshTime.isEmpty()) {
            for (var i = 0; i < sectors.size(); i++) {
                var sector = replacements.containsKey(i) ? replacements.get(i) : sectors.get(i);
                if (sector.hasTimeMaps() == 0) {
                    continue;
                }
                List<List<Integer>> maps = null;
                for (var entry : freshTime.entrySet()) {
                    var x = entry.getKey() % width - sector.offsetX();
                    var y = entry.getKey() / width - sector.offsetY();
                    if (x < 0 || y < 0 || x > 16 || y > 16) {
                        continue;
                    }
                    if (maps == null) {
                        maps = new ArrayList<>();
                        for (var map : sector.timeMaps()) {
                            maps.add(new ArrayList<>(map));
                        }
                    }
                    for (var f = 0; f < maps.size(); f++) {
                        if (entry.getValue()[f] == KEEP) {
                            continue;
                        }
                        var previous = maps.get(f).set(y * 17 + x, entry.getValue()[f]);
                        if (i == firstSectorShowing(sectors, entry.getKey(), width)
                                && previous != entry.getValue()[f]) {
                            timeChanged++;
                        }
                    }
                }
                if (maps != null) {
                    replacements.put(
                            i,
                            new Sector(
                                    sector.owner(),
                                    sector.sizeX(),
                                    sector.sizeY(),
                                    sector.offsetX(),
                                    sector.offsetY(),
                                    sector.bounds(),
                                    sector.lights(),
                                    sector.hasTimeMaps(),
                                    maps,
                                    sector.layerUniformity(),
                                    sector.quadRenderPasses()));
                }
            }
            if (savedTime.isPresent()) {
                var saved = savedTime.orElseThrow();
                var samples = new HashMap<Integer, int[]>();
                for (var entry : freshTime.entrySet()) {
                    var values = entry.getValue().clone();
                    for (var f = 0; f < values.length; f++) {
                        if (values[f] == KEEP) {
                            values[f] = saved.sample(f, entry.getKey() % width, entry.getKey() / width);
                        }
                    }
                    samples.put(entry.getKey(), values);
                }
                composedTime = Optional.of(saved.withSamples(samples));
                if (scene.hasRasterSunlight()) {
                    for (var entry : samples.entrySet()) {
                        for (var f = 0; f < entry.getValue().length; f++) {
                            if (saved.sample(f, entry.getKey() % width, entry.getKey() / width)
                                    != entry.getValue()[f]) {
                                timeChanged++;
                            }
                        }
                    }
                }
            }
        }

        if (steadySaved) {
            for (var index : affected) {
                var sector = sectors.get(index);
                var sx = effectiveSize(sector.offsetX(), sector.sizeX(), width);
                var sy = effectiveSize(sector.offsetY(), sector.sizeY(), height);
                for (var ly = 0; ly <= sy; ly++) {
                    for (var lx = 0; lx <= sx; lx++) {
                        colorOwner.merge((sector.offsetY() + ly) * width + sector.offsetX() + lx, index, Math::max);
                    }
                }
            }
            var sources = new HashMap<Integer, Source>();
            freshSources.forEach(source -> sources.put(source.reference(), source));
            for (var entry : colorOwner.entrySet()) {
                checkCancelled(cancelled);
                var vertex = entry.getKey();
                var ownerIndex = lastSectorContaining(sectors, vertex, width, height);
                if (ownerIndex != entry.getValue()) {
                    continue;
                }
                var sector = sectors.get(ownerIndex);
                var sx = effectiveSize(sector.offsetX(), sector.sizeX(), width);
                var local = (vertex / width - sector.offsetY()) * (sx + 1) + vertex % width - sector.offsetX();
                var finalLights = sectorLists.get(ownerIndex);
                var sameSet = !changed.contains(vertex) && hasSameVisibleSet(sector.lights(), finalLights, local);
                if (sameSet
                        && finalLights.stream()
                                .noneMatch(light -> (shadows.getRecolored().containsKey(light.reference())
                                                || shadows.getMoved().containsKey(light.reference()))
                                        && isVertexVisible(light, local))) {
                    continue;
                }
                var lit = scene.getLightingSource(vertex, width);
                var point = edited.getWorldAt(lit % width, lit / width);
                var normal = edited.getNormalAt(lit % width, lit / width, newTurns, inverted);
                var base = scene.getSteadyBaseColor();
                int r = base.red(), g = base.green(), b = base.blue();
                for (var light : finalLights) {
                    if (!isVertexVisible(light, local)) {
                        continue;
                    }
                    var source = sources.get(light.reference());
                    if (source == null) {
                        continue;
                    }
                    float intensity;
                    try {
                        intensity = NativeTerrainSteadyLighting.sampleIntensity(source, point, normal) * 0.5f;
                    } catch (IllegalArgumentException unsupported) {
                        continue;
                    }
                    var color = shadows.getRecolored().getOrDefault(light.reference(), source.color());
                    r = Math.min(255, r + toChannel(color.x() * intensity));
                    g = Math.min(255, g + toChannel(color.y() * intensity));
                    b = Math.min(255, b + toChannel(color.z() * intensity));
                }
                var offset = vertex * 4;
                if (newColors.get(offset) != r
                        || newColors.get(offset + 1) != g
                        || newColors.get(offset + 2) != b
                        || newColors.get(offset + 3) != 255) {
                    colorsChanged++;
                }
                newColors.set(offset, r);
                newColors.set(offset + 1, g);
                newColors.set(offset + 2, b);
                newColors.set(offset + 3, 255);
            }
        }
        checkCancelled(cancelled);
        var stats = new Stats(
                moved.size(),
                changed.size(),
                affected.size(),
                movedBounds,
                added,
                removed,
                bitsChanged,
                colorsChanged,
                timeChanged,
                rays);
        return new Result(replacements, newColors, composedTime, changed, stats);
    }

    static int effectiveSize(int offset, int size, int samples) {
        return Math.min(size, samples - 1 - offset);
    }

    static boolean isSteadyEligible(BitSet zones, BitSet visibleQuads, int width, int height, int x, int y) {
        var region = false;
        for (var ny = Math.max(0, y - 1); ny <= Math.min(height - 1, y + 1) && !region; ny++) {
            for (var nx = Math.max(0, x - 1); nx <= Math.min(width - 1, x + 1); nx++) {
                if (zones.get(ny * width + nx)) {
                    region = true;
                    break;
                }
            }
        }
        if (!region) {
            return false;
        }
        var index = y * width + x;
        return x == 0
                || y == 0
                || x == width - 1
                || y == height - 1
                || visibleQuads.get(index)
                || visibleQuads.get(index - 1)
                || visibleQuads.get(index - width)
                || visibleQuads.get(index - width - 1);
    }

    private static boolean isDrawn(BitSet visibleQuads, int width, int height, int x, int y) {
        if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
            return true;
        }
        var index = y * width + x;
        return visibleQuads.get(index)
                || visibleQuads.get(index - 1)
                || visibleQuads.get(index - width)
                || visibleQuads.get(index - width - 1);
    }

    private static Set<Integer> expandByRadius(Set<Integer> vertices, int width, int height, int radius) {
        var result = new TreeSet<Integer>();
        for (var vertex : vertices) {
            var x = vertex % width;
            var y = vertex / width;
            for (var ny = Math.max(0, y - radius); ny <= Math.min(height - 1, y + radius); ny++) {
                for (var nx = Math.max(0, x - radius); nx <= Math.min(width - 1, x + radius); nx++) {
                    result.add(ny * width + nx);
                }
            }
        }
        return result;
    }

    private static boolean isAdmitted(
            TerrainLightingScene scene, Source source, NativeTerrainSectorBounds.Sphere sphere) {
        if (!NativeTerrainSteadyLighting.isAdmitted(scene.getSteadyOwner(), source)) {
            return false;
        }
        var dx = source.position().x() - sphere.center().x();
        var dy = source.position().y() - sphere.center().y();
        var dz = source.position().z() - sphere.center().z();
        var radius = source.radius() + sphere.radius();
        return dy * dy + dx * dx + dz * dz < radius * radius;
    }

    private static ArrayList<Light> savedOrder(List<Light> composed, List<Light> saved, List<Source> registry) {
        var order = new HashMap<Integer, Integer>();
        for (var i = 0; i < registry.size(); i++) {
            order.put(registry.get(i).reference(), i);
        }
        var position = new HashMap<Integer, Integer>();
        for (var i = 0; i < saved.size(); i++) {
            position.putIfAbsent(saved.get(i).reference(), i);
        }
        var kept = new ArrayList<Light>();
        var added = new ArrayList<Light>();
        for (var light : composed) {
            (position.containsKey(light.reference()) ? kept : added).add(light);
        }
        kept.sort((a, b) -> Integer.compare(position.get(a.reference()), position.get(b.reference())));
        var result = new ArrayList<Light>(composed.size());
        var next = 0;
        for (var light : kept) {
            var rank = order.getOrDefault(light.reference(), Integer.MAX_VALUE);
            while (next < added.size() && order.get(added.get(next).reference()) < rank) {
                result.add(added.get(next++));
            }
            result.add(light);
        }
        while (next < added.size()) {
            result.add(added.get(next++));
        }
        return result;
    }

    private static boolean hasSameVisibleSet(List<Light> saved, List<Light> composed, int local) {
        var before = new HashSet<Integer>();
        for (var light : saved) {
            if (isVertexVisible(light, local)) {
                before.add(light.reference());
            }
        }
        var after = new HashSet<Integer>();
        for (var light : composed) {
            if (isVertexVisible(light, local)) {
                after.add(light.reference());
            }
        }
        if (!before.equals(after)) {
            return false;
        }
        var orderBefore =
                saved.stream().map(Light::reference).filter(before::contains).toList();
        var orderAfter =
                composed.stream().map(Light::reference).filter(after::contains).toList();
        return orderBefore.equals(orderAfter);
    }

    private static int lastSectorContaining(List<Sector> sectors, int vertex, int width, int height) {
        var x = vertex % width;
        var y = vertex / width;
        var result = -1;
        for (var i = 0; i < sectors.size(); i++) {
            var sector = sectors.get(i);
            var sx = effectiveSize(sector.offsetX(), sector.sizeX(), width);
            var sy = effectiveSize(sector.offsetY(), sector.sizeY(), height);
            if (x >= sector.offsetX()
                    && x <= sector.offsetX() + sx
                    && y >= sector.offsetY()
                    && y <= sector.offsetY() + sy) {
                result = i;
            }
        }
        return result;
    }

    private static int firstSectorShowing(List<Sector> sectors, int vertex, int width) {
        var x = vertex % width;
        var y = vertex / width;
        for (var i = 0; i < sectors.size(); i++) {
            var sector = sectors.get(i);
            if (sector.hasTimeMaps() != 0
                    && x >= sector.offsetX()
                    && x <= sector.offsetX() + 16
                    && y >= sector.offsetY()
                    && y <= sector.offsetY() + 16) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isVertexVisible(Light light, int local) {
        if (light == null) {
            return false;
        }
        var index = local >>> 3;
        return index < light.visibility().size() && (light.visibility().get(index) & (1 << (local & 7))) != 0;
    }

    private static int toChannel(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite native lighting contribution");
        }
        return (int) Math.max(0, Math.min(255, Math.floor(value * 255f)));
    }

    private static List<Integer> toIntegerList(int[] values) {
        var result = new ArrayList<Integer>(values.length);
        for (var value : values) {
            result.add(value);
        }
        return result;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Terrain lighting rebuild cancelled");
        }
    }
}
