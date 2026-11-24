package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.BeastTerrainRebake.Calibrated;
import com.rost.cruma.core.editing.NativeClassDefaults.Property;
import com.rost.cruma.core.editing.NativeTerrainSteadyLighting.BaseColor;
import com.rost.cruma.core.editing.NativeTerrainSteadyLighting.WorldQuery;
import com.rost.cruma.core.editing.NativeTerrainTimeLighting.Slot;
import com.rost.cruma.core.editing.NativeTerrainTimeLighting.Window;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import com.rost.cruma.core.editing.TerrainNativeCodec.Box;
import com.rost.cruma.core.editing.TerrainNativeCodec.Sector;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import com.rost.cruma.core.editing.TerrainPackageSnapshot.Terrain;
import com.rost.cruma.core.editing.TerrainShadowQuery.Caster;
import com.rost.cruma.core.editing.TerrainShadowQuery.MeshCaster;
import com.rost.cruma.core.editing.TerrainShadowQuery.MeshTriangles;
import com.rost.cruma.core.editing.TerrainShadowQuery.TerrainCaster;
import com.rost.cruma.core.env.EnvSetupParser;
import com.rost.cruma.core.env.UnrealHsv;
import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.UActor;
import com.rost.cruma.core.unreal.UActor.LightActor;
import com.rost.cruma.core.unreal.UActor.SkyZoneInfoActor;
import com.rost.cruma.core.unreal.UActor.ZoneInfoActor;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import com.rost.cruma.core.unreal.ULevel;
import com.rost.cruma.core.unreal.UStaticMesh;
import com.rost.cruma.core.unreal.primitives.UBox;
import com.rost.cruma.core.world.NativeActorTransformPair;
import com.rost.cruma.core.world.NativeActorTransformPair.Parameters;
import com.rost.cruma.core.world.NativeActorTransformPair.Rotation;
import glm_.vec3.Vec3;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class TerrainLightingScene {
    public static final float SUN_LONGITUDE = 180f;

    public static final int LAST_SECTOR_COLUMN = 240;

    public static final float DEFAULT_SUN_SLOPE = 30f;

    static final Set<String> MESH_CLASSES =
            Set.of("staticmeshactor", "movablestaticmeshactor", "l2movablestaticmeshactor");

    private static final int MESH_SHADOW_SAMPLE_STEP = 7;
    private static final int MESH_SHADOW_MIN_SAMPLES = 64;
    private static final int BASE_COLOR_MIN_VERTICES = 1024;

    private static final int SAVED_RULES = 8;

    private static final Map<String, Boolean> savedMeshShadows = new LinkedHashMap<>(16, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Entry<String, Boolean> eldest) {
            return size() > SAVED_RULES;
        }
    };

    private static final Set<String> LIGHT_CLASSES = Set.of("light", "spotlight", "nmovablesunlight", "sunlight");

    private final String ownerPath;
    private final NativeTerrainSteadyLighting.Owner steadyOwner;
    private final NativeTerrainTimeLighting.Owner timeOwner;
    private final boolean inverted;
    private final BitSet visibleQuads;
    private final BitSet edgeTurns;
    private final List<NativeTerrainSteadyLighting.Source> steadySources;
    private final List<NativeTerrainTimeLighting.Source> timeSources;
    private final List<Slot> slots;
    private final NativeBspRegions regions;
    private final TerrainShadowQuery query;
    private final List<MeshActor> meshActors;
    private final List<Box> bspSurfaces;
    private final Summary summary;
    private final boolean shiftedLastColumn;
    private final boolean sunlightMeshShadows;
    private final SavedSunlight savedSunlight;
    private final BaseColor steadyBaseColor;
    private final @Nullable Calibrated beast;
    private final float levelBrightness;

    private TerrainLightingScene(
            String ownerPath,
            NativeTerrainSteadyLighting.Owner steadyOwner,
            NativeTerrainTimeLighting.Owner timeOwner,
            boolean inverted,
            BitSet visibleQuads,
            BitSet edgeTurns,
            List<NativeTerrainSteadyLighting.Source> steadySources,
            List<NativeTerrainTimeLighting.Source> timeSources,
            List<Slot> slots,
            NativeBspRegions regions,
            TerrainShadowQuery query,
            List<MeshActor> meshActors,
            List<Box> bspSurfaces,
            Summary summary,
            boolean shiftedLastColumn,
            boolean sunlightMeshShadows,
            SavedSunlight savedSunlight,
            BaseColor steadyBaseColor,
            @Nullable Calibrated beast,
            float levelBrightness) {
        this.beast = beast;
        this.levelBrightness = levelBrightness;
        this.ownerPath = ownerPath;
        this.steadyOwner = steadyOwner;
        this.timeOwner = timeOwner;
        this.inverted = inverted;
        this.visibleQuads = (BitSet) visibleQuads.clone();
        this.edgeTurns = (BitSet) edgeTurns.clone();
        this.steadySources = List.copyOf(steadySources);
        this.timeSources = List.copyOf(timeSources);
        this.slots = List.copyOf(slots);
        this.regions = regions;
        this.query = query;
        this.meshActors = List.copyOf(meshActors);
        this.bspSurfaces = List.copyOf(bspSurfaces);
        this.summary = summary;
        this.shiftedLastColumn = shiftedLastColumn;
        this.sunlightMeshShadows = sunlightMeshShadows;
        this.savedSunlight = savedSunlight;
        this.steadyBaseColor = steadyBaseColor;
    }

    public static TerrainLightingScene capture(
            Path clientRoot, TerrainPackageSnapshot snapshot, String ownerPath, BooleanSupplier cancelled)
            throws IOException {
        return capture(clientRoot, snapshot, ownerPath, false, cancelled);
    }

    static TerrainLightingScene capture(
            Path clientRoot,
            TerrainPackageSnapshot snapshot,
            String ownerPath,
            boolean terrainCasts,
            BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(cancelled);
        var terrain = snapshot.getTerrains().stream()
                .filter(t -> t.path().equals(ownerPath))
                .findFirst()
                .orElseThrow(() -> new IOException("Unknown Terrain owner " + ownerPath));
        var source = snapshot.getPackageBytes();
        var notes = new ArrayList<String>();
        var loader = new UArchiveLoader(
                clientRoot,
                List.of(
                        new USearchConfig("StaticMeshes", "usx"),
                        new USearchConfig("Textures", "utx"),
                        new USearchConfig("SysTextures", "utx"),
                        new USearchConfig("System", "u"),
                        new USearchConfig("Maps", "unr")));
        try (var archive =
                new UArchive(source.packageName, ByteBuffer.wrap(source.body).order(ByteOrder.LITTLE_ENDIAN), loader)) {
            checkCancelled(cancelled);
            var level = loadLevel(source, archive);
            UModel model;
            try {
                model = level.getModel().get();
            } catch (RuntimeException missing) {
                model = null;
            }
            NativeBspTrace bsp;
            NativeBspRegions regions;
            if (model == null || model.getNodes() == null || model.getNodes().length == 0) {
                bsp = new NativeBspTrace(List.of(), List.of(), true);
                regions = new NativeBspRegions(List.of(), 0);
            } else {
                try {
                    bsp = NativeBspTrace.shadowSnapshot(model);
                    regions = NativeBspRegions.shadowSnapshot(model);
                } catch (IllegalArgumentException unreadable) {
                    throw new IOException(
                            "The map's BSP model cannot be read for shadow traces: " + unreadable.getMessage(),
                            unreadable);
                }
            }
            var skyZones = model == null ? new BitSet(64) : skyZones(model);
            checkCancelled(cancelled);
            var ownerEntry = source.exports.get(terrain.exportIndex() - 1);
            var ownerTags = readTagMap(source, ownerEntry);
            var ownerLocation = readVector(ownerTags, "Location", new Vector(0, 0, 0));
            var ownerZone = regions.pointRegion(ownerLocation).zone();
            var specialLit = readBool(ownerTags, "bSpecialLit", false);
            var staticLighting = readBool(ownerTags, "bStaticLighting", true);
            var ownerFlags = specialLit ? 0x20 : 0;
            var levelInfo = level.getLevelInfo();
            var levelBrightness = levelInfo == null || !Float.isFinite(levelInfo.getLevelBrightness())
                    ? 1f
                    : levelInfo.getLevelBrightness();

            var steady = new ArrayList<NativeTerrainSteadyLighting.Source>();
            var time = new ArrayList<NativeTerrainTimeLighting.Source>();
            var meshes = new ArrayList<MeshActor>();
            var casters = new ArrayList<Caster>();
            var meshTriangles = new IdentityHashMap<UStaticMesh, MeshTriangles>();
            var seen = new HashSet<Integer>();
            int levelLights = 0, candidates = 0, sunlights = 0, withoutTriangles = 0, terrainCasters = 0;
            var unsupportedLightClasses = new TreeSet<String>();
            for (var ref : level.getActors()) {
                checkCancelled(cancelled);
                var index = ref.getIndex();
                if (index <= 0 || !seen.add(index)) {
                    continue;
                }
                var entry = source.exports.get(index - 1);
                var className = source.getClassName(entry).toLowerCase(Locale.ROOT);
                if (LIGHT_CLASSES.contains(className)) {
                    var tags = readTagMap(source, entry);
                    if (readBool(tags, "bDeleteMe", false)) {
                        continue;
                    }
                    if (!(ref.get() instanceof LightActor light)) {
                        unsupportedLightClasses.add(className);
                        continue;
                    }
                    levelLights++;
                    var movableSun = className.equals("nmovablesunlight");
                    var location = toVector(light.getLocation());
                    if (!Float.isFinite(location.x())
                            || !Float.isFinite(location.y())
                            || !Float.isFinite(location.z())) {
                        notes.add("Light " + source.getObjectPath(index) + " has no finite location");
                        continue;
                    }
                    var region = regions.pointRegion(location);
                    var flags = (readBool(tags, "bStatic", !movableSun) ? 0x80000 : 0)
                            | (readBool(tags, "bNoDelete", true) ? 0x200000 : 0)
                            | (readBool(tags, "bSpecialLit", false) ? 0x20 : 0);
                    var radius = 25f * (light.getLightRadius() + 1f);
                    var hsv = UnrealHsv.toRgb(light.getLightHue(), light.getLightSaturation(), 255);
                    var scale = light.getLightBrightness() / 255f * levelBrightness;
                    var color = new Vector(
                            Math.max(0f, hsv.getX() * scale),
                            Math.max(0f, hsv.getY() * scale),
                            Math.max(0f, hsv.getZ() * scale));
                    var rotation = light.getRotation();
                    var direction = rotationVector(rotation.pitch(), rotation.yaw());
                    var type = Math.clamp(light.getLightType(), 0, 255);
                    var effect = Math.clamp(light.getLightEffect(), 0, 255);
                    if (!Float.isFinite(radius) || radius < 0) {
                        notes.add("Light " + source.getObjectPath(index) + " has an invalid radius");
                        continue;
                    }
                    NativeTerrainSteadyLighting.Source source1;
                    try {
                        source1 = new NativeTerrainSteadyLighting.Source(
                                index,
                                type,
                                flags,
                                region.zone(),
                                skyZones.get(region.zone()),
                                movableSun,
                                effect,
                                location,
                                direction,
                                radius,
                                Math.clamp(light.getLightCone(), 0, 255),
                                color);
                    } catch (IllegalArgumentException unusable) {
                        notes.add("Light " + source.getObjectPath(index)
                                + " has values that the lighting rules cannot use");
                        continue;
                    }
                    steady.add(source1);
                    time.add(new NativeTerrainTimeLighting.Source(
                            index, type, flags & 0x20, region.zone(), movableSun, effect));
                    if (NativeTerrainSteadyLighting.isAdmitted(
                            steadyOwner(ownerFlags, ownerZone, staticLighting), source1)) {
                        candidates++;
                    }
                    if (movableSun
                            && NativeTerrainTimeLighting.isAdmitted(
                                    new NativeTerrainTimeLighting.Owner(ownerFlags, ownerZone), time.getLast())) {
                        sunlights++;
                    }
                } else if (MESH_CLASSES.contains(className)) {
                    var tags = readTagMap(source, entry);
                    if (readBool(tags, "bDeleteMe", false) || !(ref.get() instanceof UActor actor)) {
                        continue;
                    }
                    var mesh = actor.getStaticMesh().get();
                    if (mesh == null || mesh.getBoundingBox() == null) {
                        continue;
                    }
                    NativeActorTransformPair matrices;
                    try {
                        matrices = toTransformPair(actor);
                    } catch (IllegalArgumentException degenerate) {
                        notes.add("Static mesh actor " + source.getObjectPath(index) + " has a degenerate transform");
                        continue;
                    }
                    var bounds = transform(mesh.getBoundingBox(), matrices.getLocalToWorld());
                    var castsShadow = readBool(tags, "bCollideActors", true)
                            && readBool(tags, "bShadowCast", true)
                            && readBool(tags, "bBlockZeroExtentTraces", true);
                    meshes.add(new MeshActor(index, source.getObjectPath(index), bounds, castsShadow));
                    if (!castsShadow) {
                        continue;
                    }
                    var triangles = meshTriangles.computeIfAbsent(mesh, TerrainLightingScene::collectTriangles);
                    if (triangles == null) {
                        withoutTriangles++;
                        continue;
                    }
                    casters.add(new MeshCaster(index, bounds, triangles, matrices.getWorldToLocal()));
                }
            }
            if (!unsupportedLightClasses.isEmpty()) {
                notes.add("Light classes without a reader: " + unsupportedLightClasses);
            }
            for (var other : snapshot.getTerrains()) {
                var tags = readTagMap(source, source.exports.get(other.exportIndex() - 1));
                if ((terrainCasts || readBool(tags, "bCollideActors", false) && readBool(tags, "bShadowCast", false))
                        && !other.heightmap().isEmpty()) {
                    var field = new TerrainHeightField(
                            other.heightmap().getFirst(), other.info().toWorld());
                    var bounds = field.getBounds(0, 0, field.getWidth() - 1, field.getHeight() - 1);
                    casters.add(new TerrainCaster(
                            bounds,
                            new NativeTerrainTrace(
                                    field,
                                    readBitmap(tags, "QuadVisibilityBitmap"),
                                    readBitmap(tags, "EdgeTurnBitmap"),
                                    readBool(tags, "Inverted", false))));
                    terrainCasters++;
                    notes.add("Terrain " + other.path() + " casts shadows on other Terrain vertices");
                }
            }
            var envSlope = sunSlope(clientRoot);
            var slots = buildSunSlots(snapshot, terrain, envSlope);
            var savedSunlight = TerrainNativeCodec.savesSectorTimeMaps(snapshot.getLicenseVersion())
                    ? SavedSunlight.SECTORS
                    : new SavedSunlight(snapshot.findAuthoredTimeMaps(ownerPath));
            var inverted = readBool(ownerTags, "Inverted", false);
            var visibleQuads = readBitmap(ownerTags, "QuadVisibilityBitmap");
            var edgeTurns = readBitmap(ownerTags, "EdgeTurnBitmap");
            var shifted = detectShiftedLastColumn(terrain, edgeTurns, inverted, slots, savedSunlight);
            var timeOwner = new NativeTerrainTimeLighting.Owner(ownerFlags, ownerZone);
            var query = new TerrainShadowQuery(bsp, casters);
            var meshShadows = true;
            if (casters.size() > terrainCasters) {
                var withoutMeshes = new TerrainShadowQuery(
                        bsp,
                        casters.stream().filter(TerrainCaster.class::isInstance).toList());
                var key = snapshot.getOriginalSha256()
                        + '|'
                        + ownerPath
                        + '|'
                        + terrainCasts
                        + '|'
                        + clientRoot.toAbsolutePath().normalize();
                Boolean known;
                synchronized (savedMeshShadows) {
                    known = savedMeshShadows.get(key);
                }
                meshShadows = known != null
                        ? known
                        : doesSunlightShowMeshShadows(
                                terrain,
                                savedSunlight,
                                edgeTurns,
                                inverted,
                                visibleQuads,
                                shifted,
                                timeOwner,
                                time,
                                slots,
                                query,
                                withoutMeshes,
                                cancelled);
                synchronized (savedMeshShadows) {
                    savedMeshShadows.put(key, meshShadows);
                }
                if (!meshShadows) {
                    query = withoutMeshes;
                    notes.add("The saved sunlight has no static mesh shadows, so the rebuild leaves them out as well");
                }
            }
            if (savedSunlight.raster().isPresent()) {
                notes.add("This older map saves its sunlight only in the Terrain time rasters. Export updates them");
            }
            var baseColor = steadyBaseColor(terrain);
            if (!baseColor.equals(BaseColor.BLACK)) {
                notes.add("The saved light colours start from "
                        + baseColor.red()
                        + ", "
                        + baseColor.green()
                        + ", "
                        + baseColor.blue()
                        + ", added by an older lighting build. Edited vertices start from it too");
            }
            if (terrain.info().savedGeometry().isPresent()) {
                notes.add("This older map also saves vertex positions and normals, which the game uses. Export updates"
                        + " them");
            }
            var beast = BeastTerrainRebake.calibrate(
                    clientRoot,
                    archive,
                    snapshot,
                    terrain,
                    edgeTurns == null ? new BitSet() : edgeTurns,
                    visibleQuads,
                    inverted,
                    Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1)),
                    cancelled);
            var summary = new Summary(
                    levelLights,
                    candidates,
                    sunlights,
                    meshShadows ? casters.size() - terrainCasters : 0,
                    withoutTriangles,
                    terrainCasters,
                    notes);
            return new TerrainLightingScene(
                    ownerPath,
                    steadyOwner(ownerFlags, ownerZone, staticLighting),
                    timeOwner,
                    inverted,
                    visibleQuads,
                    edgeTurns,
                    steady,
                    time,
                    slots,
                    regions,
                    query,
                    meshes,
                    model == null ? List.of() : bspSurfaces(model),
                    summary,
                    shifted,
                    meshShadows,
                    savedSunlight,
                    baseColor,
                    beast,
                    levelBrightness);
        } catch (IllegalStateException | IllegalArgumentException | IndexOutOfBoundsException malformed) {
            if (malformed instanceof CancellationException cancellation) {
                throw cancellation;
            }
            throw new IOException("The map's lighting scene cannot be read: " + malformed.getMessage(), malformed);
        } finally {
            loader.clearArchives();
        }
    }

    static BaseColor steadyBaseColor(Terrain terrain) {
        var width = terrain.info().width();
        var height = terrain.info().height();
        var colors = terrain.info().vertexColors();
        if (colors.size() != width * height * 4) {
            return BaseColor.BLACK;
        }
        var lit = new BitSet(width * height);
        for (var sector : terrain.sectors()) {
            var sx = TerrainLightingRebuild.effectiveSize(sector.offsetX(), sector.sizeX(), width);
            var sy = TerrainLightingRebuild.effectiveSize(sector.offsetY(), sector.sizeY(), height);
            for (var light : sector.lights()) {
                for (var local = 0; local < (sx + 1) * (sy + 1); local++) {
                    var index = local >>> 3;
                    if (index < light.visibility().size() && (light.visibility().get(index) & 1 << (local & 7)) != 0) {
                        lit.set((sector.offsetY() + local / (sx + 1)) * width + sector.offsetX() + local % (sx + 1));
                    }
                }
            }
        }
        var counts = new HashMap<Integer, Integer>();
        var unlit = 0;
        for (var vertex = 0; vertex < width * height; vertex++) {
            if (lit.get(vertex)) {
                continue;
            }
            unlit++;
            var offset = vertex * 4;
            counts.merge(
                    colors.get(offset) << 16 | colors.get(offset + 1) << 8 | colors.get(offset + 2), 1, Integer::sum);
        }
        var best = 0;
        var bestCount = 0;
        for (var entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        if (unlit < BASE_COLOR_MIN_VERTICES || (long) bestCount * 100 < (long) unlit * 99) {
            return BaseColor.BLACK;
        }
        return new BaseColor(best >>> 16, best >>> 8 & 255, best & 255);
    }

    static boolean detectShiftedLastColumn(
            Terrain terrain, BitSet edgeTurns, boolean inverted, List<Slot> slots, SavedSunlight saved) {
        if (terrain.heightmap().isEmpty() || slots.isEmpty() || terrain.info().width() != 256) {
            return false;
        }
        var field = new TerrainHeightField(
                terrain.heightmap().getFirst(), terrain.info().toWorld());
        long plain = 0, shifted = 0;
        for (var sector : terrain.sectors()) {
            var maps = sector.offsetX() == LAST_SECTOR_COLUMN ? saved.of(sector) : List.<List<Integer>>of();
            if (maps.size() != slots.size()) {
                continue;
            }
            var sy = TerrainLightingRebuild.effectiveSize(sector.offsetY(), sector.sizeY(), field.getHeight());
            for (var ly = 0; ly <= sy; ly++) {
                for (var lx = 1; lx <= 15; lx++) {
                    var x = LAST_SECTOR_COLUMN + lx;
                    var y = sector.offsetY() + ly;
                    if (!isShifted(x, y)) {
                        continue;
                    }
                    var own = field.getNormalAt(x, y, edgeTurns, inverted);
                    var west = field.getNormalAt(x - 1, y, edgeTurns, inverted);
                    for (var f = 0; f < slots.size(); f++) {
                        var value = maps.get(f).get(ly * 17 + lx);
                        if (value == 0) {
                            continue;
                        }
                        var direction = slots.get(f).safeDirection();
                        if (NativeTerrainTimeLighting.computeIntensity(direction, own) == value) {
                            plain++;
                        }
                        if (NativeTerrainTimeLighting.computeIntensity(direction, west) == value) {
                            shifted++;
                        }
                    }
                }
            }
        }
        return shifted > plain + plain / 20 && shifted > 32;
    }

    static boolean doesSunlightShowMeshShadows(
            Terrain terrain,
            BitSet edgeTurns,
            boolean inverted,
            BitSet visibleQuads,
            boolean shifted,
            NativeTerrainTimeLighting.Owner owner,
            List<NativeTerrainTimeLighting.Source> sources,
            List<Slot> slots,
            WorldQuery withMeshes,
            WorldQuery withoutMeshes,
            BooleanSupplier cancelled) {
        return doesSunlightShowMeshShadows(
                terrain,
                SavedSunlight.SECTORS,
                edgeTurns,
                inverted,
                visibleQuads,
                shifted,
                owner,
                sources,
                slots,
                withMeshes,
                withoutMeshes,
                cancelled);
    }

    static boolean doesSunlightShowMeshShadows(
            Terrain terrain,
            SavedSunlight savedSunlight,
            BitSet edgeTurns,
            boolean inverted,
            BitSet visibleQuads,
            boolean shifted,
            NativeTerrainTimeLighting.Owner owner,
            List<NativeTerrainTimeLighting.Source> sources,
            List<Slot> slots,
            WorldQuery withMeshes,
            WorldQuery withoutMeshes,
            BooleanSupplier cancelled) {
        if (slots.isEmpty() || terrain.heightmap().isEmpty() || terrain.info().width() != 256) {
            return true;
        }
        var field = new TerrainHeightField(
                terrain.heightmap().getFirst(), terrain.info().toWorld());
        var grid = new NativeTerrainTimeLighting.Grid(field, edgeTurns, inverted, visibleQuads, cancelled);
        if (shifted) {
            grid = grid.shiftedFrom(LAST_SECTOR_COLUMN);
        }
        long savedLit = 0, savedDark = 0;
        try {
            for (var index = 0; index < terrain.sectors().size(); index += MESH_SHADOW_SAMPLE_STEP) {
                var sector = terrain.sectors().get(index);
                var maps = savedSunlight.of(sector);
                if (maps.size() != slots.size()) {
                    continue;
                }
                var sx = TerrainLightingRebuild.effectiveSize(sector.offsetX(), sector.sizeX(), field.getWidth());
                var sy = TerrainLightingRebuild.effectiveSize(sector.offsetY(), sector.sizeY(), field.getHeight());
                var window = new Window(sector.offsetX(), sector.offsetY(), sx + 1, sy + 1);
                var with =
                        NativeTerrainTimeLighting.rebuild(grid, owner, window, sources, slots, withMeshes, cancelled);
                var without = NativeTerrainTimeLighting.rebuild(
                        grid, owner, window, sources, slots, withoutMeshes, cancelled);
                for (var f = 0; f < slots.size(); f++) {
                    var shadowed = with.frames().get(f).intensities();
                    var open = without.frames().get(f).intensities();
                    var saved = maps.get(f);
                    for (var ly = 0; ly <= sy; ly++) {
                        for (var lx = 0; lx <= sx; lx++) {
                            var local = ly * (sx + 1) + lx;
                            if (shadowed.get(local) != 0 || open.get(local) == 0) {
                                continue;
                            }
                            if (saved.get(ly * 17 + lx) != 0) {
                                savedLit++;
                            } else {
                                savedDark++;
                            }
                        }
                    }
                }
            }
        } catch (IllegalArgumentException unqualified) {
            return true;
        }
        return !(savedLit >= MESH_SHADOW_MIN_SAMPLES && savedLit > savedDark * 4);
    }

    static List<Slot> buildSunSlots(TerrainPackageSnapshot snapshot, Terrain terrain, float slope) throws IOException {
        var time = snapshot.findAuthoredTimeMaps(terrain.path());
        if (time.isEmpty()) {
            return List.of();
        }
        var codec = time.orElseThrow();
        var result = new ArrayList<Slot>(codec.getFrameCount());
        for (var frame = 0; frame < codec.getFrameCount(); frame++) {
            var hour = codec.getTime(frame);
            result.add(new Slot(hour, NativeTerrainTimeLighting.safeDirection(hour, SUN_LONGITUDE, slope, false)));
        }
        return List.copyOf(result);
    }

    static float sunSlope(Path clientRoot) {
        var env = clientRoot.resolve("System").resolve("env.int");
        if (!Files.isRegularFile(env)) {
            return DEFAULT_SUN_SLOPE;
        }
        try {
            var slope = EnvSetupParser.parse(env).slopeSunAngle();
            return Float.isFinite(slope) ? slope : DEFAULT_SUN_SLOPE;
        } catch (RuntimeException unreadable) {
            return DEFAULT_SUN_SLOPE;
        }
    }

    static MeshTriangles collectTriangles(UStaticMesh mesh) {
        if (mesh.getVertexStream() == null || mesh.getIndexStream() == null || mesh.getSurfaces() == null) {
            return null;
        }
        var vertices = mesh.getVertexStream().getVertices();
        var indices = mesh.getIndexStream().getIndices();
        if (vertices == null || indices == null) {
            return null;
        }
        var values = new float[64];
        var size = 0;
        for (var surface : mesh.getSurfaces()) {
            for (var t = 0; t < surface.getTriangleMax(); t++) {
                var first = surface.getFirstIndex() + t * 3;
                if (first + 2 >= indices.length) {
                    break;
                }
                if (size + 9 > values.length) {
                    values = Arrays.copyOf(values, values.length * 2);
                }
                var valid = true;
                for (var k = 0; k < 3; k++) {
                    var vertex = indices[first + k];
                    if (vertex < 0 || vertex >= vertices.length) {
                        valid = false;
                        break;
                    }
                    var p = vertices[vertex].getLocation();
                    values[size + k * 3] = p.getX();
                    values[size + k * 3 + 1] = p.getY();
                    values[size + k * 3 + 2] = p.getZ();
                }
                if (valid) {
                    size += 9;
                }
            }
        }
        return size == 0 ? null : new MeshTriangles(Arrays.copyOf(values, size));
    }

    static NativeActorTransformPair toTransformPair(UActor actor) {
        var location = actor.getLocation();
        var pivot = actor.getPrePivot();
        var rotation = actor.getRotation();
        var scale = actor.getDrawScale3d();
        return new NativeActorTransformPair(new Parameters(
                new NativeActorTransformPair.Vector(location.getX(), location.getY(), location.getZ()),
                new NativeActorTransformPair.Vector(pivot.getX(), pivot.getY(), pivot.getZ()),
                new Rotation(rotation.pitch(), rotation.yaw(), rotation.roll()),
                actor.getDrawScale(),
                new NativeActorTransformPair.Vector(scale.getX(), scale.getY(), scale.getZ())));
    }

    static Box transform(UBox box, float[] m) {
        var min = new float[] {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        var max = new float[] {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (var i = 0; i < 8; i++) {
            var x = (i & 1) == 0 ? box.min().getX() : box.max().getX();
            var y = (i & 2) == 0 ? box.min().getY() : box.max().getY();
            var z = (i & 4) == 0 ? box.min().getZ() : box.max().getZ();
            for (var axis = 0; axis < 3; axis++) {
                var value = m[axis] * x + m[4 + axis] * y + m[8 + axis] * z + m[12 + axis];
                min[axis] = Math.min(min[axis], value);
                max[axis] = Math.max(max[axis], value);
            }
        }
        return new Box(new Vector(min[0], min[1], min[2]), new Vector(max[0], max[1], max[2]), 1);
    }

    static Vector rotationVector(int pitch, int yaw) {
        var sp = sine(pitch);
        var cp = sine(pitch + 16384);
        var sy = sine(yaw);
        var cy = sine(yaw + 16384);
        return new Vector(cp * cy, cp * sy, sp);
    }

    static Map<String, Property> readTagMap(PackageBytes source, ExportEntry entry) throws IOException {
        var c = new Cursor(source.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
        if ((entry.flags() & 0x02000000) != 0) {
            var node = c.readIndex();
            c.readIndex();
            c.skip(12);
            if (node != 0) {
                c.readIndex();
            }
        }
        var result = new HashMap<String, Property>();
        try {
            readTags(source, c, result, 0);
        } catch (IOException | RuntimeException unreadable) {
            throw new IOException(
                    "Unreadable properties in " + source.getObjectPath(entry.index()) + ": " + unreadable.getMessage(),
                    unreadable);
        }
        return result;
    }

    static boolean readBool(Map<String, Property> tags, String name, boolean fallback) {
        var property = tags.get(name.toLowerCase(Locale.ROOT));
        return property == null || property.type() != 3 ? fallback : property.booleanValue();
    }

    static BitSet readBitmap(Map<String, Property> tags, String name) throws IOException {
        var property = tags.get(name.toLowerCase(Locale.ROOT));
        if (property == null || property.type() != 9) {
            throw new IOException("Terrain has no " + name);
        }
        var bytes = property.payload();
        var c = new Cursor(bytes, 0, bytes.length);
        var words = c.readIndex();
        if (words < 0 || (long) words * 4 != c.end - c.position) {
            throw new IOException("Terrain " + name + " has an invalid size");
        }
        return BitSet.valueOf(Arrays.copyOfRange(bytes, c.position, c.end));
    }

    public TerrainLightingScene withQuads(BitSet visible, BitSet turns) {
        return new TerrainLightingScene(
                ownerPath,
                steadyOwner,
                timeOwner,
                inverted,
                visible,
                turns,
                steadySources,
                timeSources,
                slots,
                regions,
                query,
                meshActors,
                bspSurfaces,
                summary,
                shiftedLastColumn,
                sunlightMeshShadows,
                savedSunlight,
                steadyBaseColor,
                beast,
                levelBrightness);
    }

    record SavedSunlight(Optional<TerrainTimeMapCodec> raster) {
        static final SavedSunlight SECTORS = new SavedSunlight(Optional.empty());

        List<List<Integer>> of(Sector sector) {
            if (sector.hasTimeMaps() != 0) {
                return sector.timeMaps();
            }
            if (raster.isEmpty()) {
                return List.of();
            }
            var codec = raster.orElseThrow();
            var maps = new ArrayList<List<Integer>>(codec.getFrameCount());
            for (var frame = 0; frame < codec.getFrameCount(); frame++) {
                maps.add(codec.getSectorMap(frame, sector.offsetX(), sector.offsetY()));
            }
            return maps;
        }
    }

    public record MeshActor(int reference, String path, Box bounds, boolean castsShadow) {}

    public record Summary(
            int levelLights,
            int steadyCandidates,
            int sunlights,
            int shadowMeshes,
            int meshesWithoutTriangles,
            int terrainCasters,
            List<String> notes) {
        public Summary {
            notes = List.copyOf(notes);
        }
    }

    public @Nullable Calibrated getBeast() {
        return beast;
    }

    public String getOwnerPath() {
        return ownerPath;
    }

    public NativeTerrainSteadyLighting.Owner getSteadyOwner() {
        return steadyOwner;
    }

    public NativeTerrainTimeLighting.Owner getTimeOwner() {
        return timeOwner;
    }

    public boolean isInverted() {
        return inverted;
    }

    public Vector computeSteadyColor(LightColor color) {
        var hsv = UnrealHsv.toRgb(color.hue(), color.saturation(), 255);
        var scale = color.brightness() / 255f * levelBrightness;
        return new Vector(
                Math.max(0f, hsv.getX() * scale), Math.max(0f, hsv.getY() * scale), Math.max(0f, hsv.getZ() * scale));
    }

    public BitSet getVisibleQuads() {
        return (BitSet) visibleQuads.clone();
    }

    public BitSet getEdgeTurns() {
        return (BitSet) edgeTurns.clone();
    }

    public List<NativeTerrainSteadyLighting.Source> getSteadySources() {
        return steadySources;
    }

    public List<NativeTerrainTimeLighting.Source> getTimeSources() {
        return timeSources;
    }

    public List<Slot> getSlots() {
        return slots;
    }

    public NativeBspRegions getRegions() {
        return regions;
    }

    public TerrainShadowQuery getQuery() {
        return query;
    }

    public List<MeshActor> getMeshActors() {
        return meshActors;
    }

    public List<Box> getBspSurfaces() {
        return bspSurfaces;
    }

    public Summary getSummary() {
        return summary;
    }

    public boolean hasShiftedLastColumn() {
        return shiftedLastColumn;
    }

    public BaseColor getSteadyBaseColor() {
        return steadyBaseColor;
    }

    public boolean hasRasterSunlight() {
        return savedSunlight.raster().isPresent();
    }

    public List<List<Integer>> getSavedSunlight(Sector sector) {
        return savedSunlight.of(sector);
    }

    public NativeTerrainSteadyLighting.Grid createSteadyGrid(TerrainHeightField field) {
        var grid = new NativeTerrainSteadyLighting.Grid(
                field, edgeTurns, inverted, visibleQuads, computeOwnerZoneSamples(field));
        return shiftedLastColumn ? grid.shiftedFrom(LAST_SECTOR_COLUMN) : grid;
    }

    public NativeTerrainTimeLighting.Grid createTimeGrid(TerrainHeightField field, BooleanSupplier cancelled) {
        var grid = new NativeTerrainTimeLighting.Grid(field, edgeTurns, inverted, visibleQuads, cancelled);
        return shiftedLastColumn ? grid.shiftedFrom(LAST_SECTOR_COLUMN) : grid;
    }

    public boolean hasSunlightMeshShadows() {
        return sunlightMeshShadows;
    }

    public int getLightingSource(int vertex, int width) {
        return shiftedLastColumn && isShifted(vertex % width, vertex / width) ? vertex - 1 : vertex;
    }

    public BitSet computeOwnerZoneSamples(TerrainHeightField field) {
        var result = new BitSet(field.getWidth() * field.getHeight());
        for (var y = 0; y < field.getHeight(); y++) {
            for (var x = 0; x < field.getWidth(); x++) {
                if (regions.pointRegion(field.getWorldAt(x, y)).zone() == steadyOwner.zoneNumber()) {
                    result.set(y * field.getWidth() + x);
                }
            }
        }
        return result;
    }

    private static boolean isShifted(int x, int y) {
        return x >= LAST_SECTOR_COLUMN && x - LAST_SECTOR_COLUMN + y % 16 >= 16;
    }

    private static NativeTerrainSteadyLighting.Owner steadyOwner(int flags, int zone, boolean staticLighting) {
        return new NativeTerrainSteadyLighting.Owner(flags, zone, staticLighting);
    }

    private static ULevel loadLevel(PackageBytes source, UArchive archive) throws IOException {
        ExportEntry found = null;
        for (var entry : source.exports) {
            if (source.getClassName(entry).equals("Level")) {
                if (found != null) {
                    throw new IOException("The map has more than one Level");
                }
                found = entry;
            }
        }
        if (found == null || !(archive.getObjectLoader().loadObject(found.index()) instanceof ULevel level)) {
            throw new IOException("The map has no readable Level");
        }
        return level;
    }

    private static BitSet skyZones(UModel model) {
        var result = new BitSet(64);
        var zones = model.getZones();
        if (zones == null) {
            return result;
        }
        for (var i = 0; i < zones.length && i < 64; i++) {
            ZoneInfoActor zone;
            try {
                zone = zones[i].getZoneActor().get();
            } catch (RuntimeException missing) {
                zone = null;
            }
            result.set(i, zone instanceof SkyZoneInfoActor);
        }
        return result;
    }

    private static List<Box> bspSurfaces(UModel model) {
        var nodes = model.getNodes();
        var points = model.getPoints();
        var vertices = model.getVertices();
        var surfaces = model.getSurfaces();
        if (nodes == null || points == null || vertices == null || surfaces == null) {
            return List.of();
        }
        var min = new float[surfaces.length * 3];
        var max = new float[surfaces.length * 3];
        Arrays.fill(min, Float.POSITIVE_INFINITY);
        Arrays.fill(max, Float.NEGATIVE_INFINITY);
        for (var node : nodes) {
            var surface = node.getSurfaceIndex();
            if (surface < 0 || surface >= surfaces.length) {
                continue;
            }
            for (var v = 0; v < node.getVertexCount(); v++) {
                var vertex = node.getVertexPoolIndex() + v;
                if (vertex < 0 || vertex >= vertices.length) {
                    continue;
                }
                var point = vertices[vertex].getVertexIndex();
                if (point < 0 || point >= points.length) {
                    continue;
                }
                var p = points[point];
                min[surface * 3] = Math.min(min[surface * 3], p.getX());
                min[surface * 3 + 1] = Math.min(min[surface * 3 + 1], p.getY());
                min[surface * 3 + 2] = Math.min(min[surface * 3 + 2], p.getZ());
                max[surface * 3] = Math.max(max[surface * 3], p.getX());
                max[surface * 3 + 1] = Math.max(max[surface * 3 + 1], p.getY());
                max[surface * 3 + 2] = Math.max(max[surface * 3 + 2], p.getZ());
            }
        }
        var result = new ArrayList<Box>();
        for (var s = 0; s < surfaces.length; s++) {
            if (min[s * 3] <= max[s * 3]) {
                result.add(new Box(
                        new Vector(min[s * 3], min[s * 3 + 1], min[s * 3 + 2]),
                        new Vector(max[s * 3], max[s * 3 + 1], max[s * 3 + 2]),
                        1));
            }
        }
        return result;
    }

    private static float sine(int angle) {
        var index = (angle >> 2) & 16383;
        return (float) Math.sin((double) (index * 2) * Math.PI * (1.0 / 16384.0));
    }

    private static Vector toVector(Vec3 value) {
        return new Vector(value.getX(), value.getY(), value.getZ());
    }

    private static void readTags(PackageBytes source, Cursor c, Map<String, Property> result, int depth)
            throws IOException {
        for (var count = 0; count < 16384; count++) {
            var name = source.getName(c.readIndex());
            if (name.equalsIgnoreCase("None")) {
                return;
            }
            var tag = c.readU8();
            var type = tag & 15;
            if (type < 1 || type > 15) {
                throw new IOException("Invalid property type of " + name);
            }
            var struct = type == 10 ? source.getName(c.readIndex()) : null;
            var size = switch ((tag >> 4) & 7) {
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 4;
                case 3 -> 12;
                case 4 -> 16;
                case 5 -> c.readU8();
                case 6 -> c.readU16();
                default -> c.readI32();
            };
            var index = type != 3 && (tag & 128) != 0 ? c.readArrayIndex() : 0;
            var start = c.position;
            if (type == 9 && name.equalsIgnoreCase("ZoneRenderState") && depth == 0) {
                var tagged = false;
                try {
                    var elements = c.readIndex();
                    for (var i = 0; i < elements; i++) {
                        readTags(source, c, new HashMap<>(), depth + 1);
                    }
                    tagged = c.position <= (long) start + size + 4L * elements + 4;
                } catch (IOException | RuntimeException plain) {
                    tagged = false;
                }
                if (!tagged) {
                    if (size < 0 || size > c.end - start) {
                        throw new IOException("Invalid property size of " + name);
                    }
                    c.position = start;
                    c.skip(size);
                } else if (c.position < start + size) {
                    c.position = start + size;
                }
                continue;
            }
            var length = type == 3 ? 0 : size;
            if (length < 0 || length > c.end - c.position) {
                throw new IOException("Invalid property size of " + name);
            }
            c.skip(length);
            if (index == 0) {
                result.putIfAbsent(
                        name.toLowerCase(Locale.ROOT),
                        new Property(
                                name,
                                type,
                                index,
                                struct,
                                type == 3 && (tag & 128) != 0,
                                Arrays.copyOfRange(c.bytes, start, c.position),
                                null));
            }
        }
        throw new IOException("Too many properties");
    }

    private static Vector readVector(Map<String, Property> tags, String name, Vector fallback) {
        var property = tags.get(name.toLowerCase(Locale.ROOT));
        if (property == null || property.type() != 10 || property.payload().length != 12) {
            return fallback;
        }
        var buffer = ByteBuffer.wrap(property.payload()).order(ByteOrder.LITTLE_ENDIAN);
        var result = new Vector(buffer.getFloat(), buffer.getFloat(), buffer.getFloat());
        return Float.isFinite(result.x()) && Float.isFinite(result.y()) && Float.isFinite(result.z())
                ? result
                : fallback;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Terrain lighting scene capture cancelled");
        }
    }
}
