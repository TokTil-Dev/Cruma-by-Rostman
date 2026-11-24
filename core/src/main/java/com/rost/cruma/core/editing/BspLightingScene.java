package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorLightingScene.Settings;
import com.rost.cruma.core.editing.BspLightmapTail.Entry;
import com.rost.cruma.core.editing.BspLightmapTail.Lightmap;
import com.rost.cruma.core.editing.BspShadowScene.Filter;
import com.rost.cruma.core.editing.BspShadowSceneCapture.Result;
import com.rost.cruma.core.editing.NativeBspLightmapTexels.Lit;
import com.rost.cruma.core.editing.NativeBspLightmapTexels.Surface;
import com.rost.cruma.core.editing.NativeBspShadowBitmaps.Profile;
import com.rost.cruma.core.env.UnrealHsv;
import com.rost.cruma.core.texture.DxtMipCodec;
import com.rost.cruma.core.unreal.UActor.LightActor;
import com.rost.cruma.core.unreal.UActor.SkyZoneInfoActor;
import com.rost.cruma.core.unreal.UActor.ZoneInfoActor;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.ULevel;
import com.rost.cruma.core.world.Entity.TextureFormat;
import com.rost.cruma.core.world.NativeBspSunDirection;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

final class BspLightingScene {
    static final int HIDDEN_SURFACES = 0x4002001;

    static final int UNLIT_SURFACES = 0x400080;

    private static final int SAMPLE_ENTRIES = 240;

    private static final int SAVED_RULES = 8;

    private static final Map<String, boolean[]> savedRules = new LinkedHashMap<>(16, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, boolean[]> eldest) {
            return size() > SAVED_RULES;
        }
    };

    final String map;
    final int modelExport;
    final BspLightmapTail tail;
    final SurfaceInfo[] surfaces;
    final boolean[] sunZones;
    final int[] zoneBases;
    final List<Light> lights;
    final Map<Integer, Integer> lightsByReference;
    final Map<Integer, Integer> movedTo;
    final Set<Integer> placed;
    final int[][] candidates;
    final Result shadows;
    final boolean pointMeshes;
    final boolean sunMeshes;
    final float[][] slotToward;
    final int[] sunBases;
    final float @Nullable [] sunColor;
    final boolean beastLighting;
    final List<String> notes;

    private BspLightingScene(
            String map,
            int modelExport,
            BspLightmapTail tail,
            SurfaceInfo[] surfaces,
            boolean[] sunZones,
            int[] zoneBases,
            List<Light> lights,
            Map<Integer, Integer> lightsByReference,
            Map<Integer, Integer> movedTo,
            Set<Integer> placed,
            int[][] candidates,
            Result shadows,
            boolean pointMeshes,
            boolean sunMeshes,
            float[][] slotToward,
            int[] sunBases,
            float @Nullable [] sunColor,
            boolean beastLighting,
            List<String> notes) {
        this.map = map;
        this.modelExport = modelExport;
        this.tail = tail;
        this.surfaces = surfaces;
        this.sunZones = sunZones;
        this.zoneBases = zoneBases;
        this.lights = lights;
        this.lightsByReference = lightsByReference;
        this.movedTo = Map.copyOf(movedTo);
        this.placed = Set.copyOf(placed);
        this.candidates = candidates;
        this.shadows = shadows;
        this.pointMeshes = pointMeshes;
        this.sunMeshes = sunMeshes;
        this.slotToward = slotToward;
        this.sunBases = sunBases;
        this.sunColor = sunColor;
        this.beastLighting = beastLighting;
        this.notes = List.copyOf(notes);
    }

    record Light(
            int reference,
            String name,
            NativeBspLightAssociation.Light association,
            NativeBspLightmapTexels.Light texel,
            float[] towardAuthored,
            boolean sunlightColor,
            float colorScale) {
        boolean sun() {
            return texel.effect() == NativeBspLightmapTexels.LE_SUNLIGHT;
        }
    }

    record SurfaceInfo(float[] plane, int flags, float[] normal) {
        Surface texelSurface() {
            return new Surface(plane[0], plane[1], plane[2], plane[3], (flags & 0x100) != 0);
        }
    }

    static BspLightingScene capture(
            String name,
            UArchive map,
            PackageBytes source,
            Settings settings,
            Map<String, TerrainHeightField> terrainHeights,
            BooleanSupplier cancelled)
            throws IOException {
        return capture(name, map, null, source, settings, terrainHeights, Set.of(), cancelled);
    }

    static BspLightingScene capture(
            String name,
            UArchive map,
            @Nullable UArchive original,
            PackageBytes source,
            Settings settings,
            Map<String, TerrainHeightField> terrainHeights,
            BooleanSupplier cancelled)
            throws IOException {
        return capture(name, map, original, source, settings, terrainHeights, Set.of(), cancelled);
    }

    static BspLightingScene capture(
            String name,
            UArchive map,
            @Nullable UArchive original,
            PackageBytes source,
            Settings settings,
            Map<String, TerrainHeightField> terrainHeights,
            Set<Integer> moved,
            BooleanSupplier cancelled)
            throws IOException {
        return capture(name, map, original, source, settings, terrainHeights, moved, null, cancelled);
    }

    static BspLightingScene capture(
            String name,
            UArchive map,
            @Nullable UArchive original,
            PackageBytes source,
            Settings settings,
            Map<String, TerrainHeightField> terrainHeights,
            Set<Integer> moved,
            @Nullable String savedSha256,
            BooleanSupplier cancelled)
            throws IOException {
        var levels = new ArrayList<ULevel>();
        map.loadObjects("Level", levels);
        if (levels.isEmpty() || levels.getFirst().getModel().isNull()) {
            throw new IOException("The map has no level model");
        }
        var level = levels.getFirst();
        var model = level.getModel().get();
        if (model == null || model.getNodes() == null || model.getSurfaces() == null) {
            throw new IOException("The level model has no BSP");
        }
        var modelExport = level.getModel().getIndex();
        var tail = BspLightmapTail.read(source, modelExport);
        var notes = new ArrayList<String>();
        var beast = model.getBeastLightingFlag() != null && model.getBeastLightingFlag() != 0;
        var levelInfo = level.getLevelInfo();
        var levelBrightness = levelInfo != null && Float.isFinite(levelInfo.getLevelBrightness())
                ? Math.max(0f, levelInfo.getLevelBrightness())
                : 1f;
        var modelSurfaces = model.getSurfaces();
        var vectors = model.getVectors();
        var surfaces = new SurfaceInfo[modelSurfaces.length];
        for (var i = 0; i < surfaces.length; i++) {
            var surface = modelSurfaces[i];
            var plane = surface.getPlane();
            var normalIndex = surface.getNormalIndex();
            var normal = vectors != null && normalIndex >= 0 && normalIndex < vectors.length
                    ? new float[] {vectors[normalIndex].getX(), vectors[normalIndex].getY(), vectors[normalIndex].getZ()
                    }
                    : new float[] {plane.getX(), plane.getY(), plane.getZ()};
            surfaces[i] = new SurfaceInfo(
                    new float[] {plane.getX(), plane.getY(), plane.getZ(), plane.getW()},
                    (int) surface.getPolygonFlags(),
                    normal);
        }
        var modelZones = model.getZones();
        var zoneCount = Math.max(64, modelZones == null ? 0 : modelZones.length);
        var sunZones = new boolean[zoneCount];
        var zoneBases = new int[zoneCount];
        for (var i = 0; i < zoneCount; i++) {
            ZoneInfoActor zone = null;
            if (modelZones != null && i < modelZones.length) {
                try {
                    zone = modelZones[i].getZoneActor().get();
                } catch (RuntimeException unreadable) {
                    zone = null;
                }
            }
            if (zone == null) {
                zone = levelInfo;
            }
            sunZones[i] = zone == null || zone.isSunAffect();
            var hsv = zone == null
                    ? UnrealHsv.toRgb(0, 255, 0)
                    : UnrealHsv.toRgb(zone.getAmbientHue(), zone.getAmbientSaturation(), zone.getAmbientBrightness());
            zoneBases[i] = NativeBspLightmapTexels.toColor(hsv.getX() * .5f, hsv.getY() * .5f, hsv.getZ() * .5f, .5f);
        }
        var lightSource = original != null ? original : map;
        var lights = new ArrayList<Light>();
        var byReference = new HashMap<Integer, Integer>();
        var movedTo = new HashMap<Integer, Integer>();
        var placed = new HashSet<Integer>();
        var newPlace = new HashMap<Integer, Integer>();
        for (var reference : moved) {
            var before = findOrAddLight(lightSource, reference, levelBrightness, lights, byReference);
            var after = new HashMap<Integer, Integer>();
            var index = findOrAddLight(map, reference, levelBrightness, lights, after);
            if (index >= 0) {
                newPlace.put(reference, index);
                placed.add(index);
                if (before >= 0) {
                    movedTo.put(before, index);
                }
            }
        }
        var references = model.getLightActors();
        var slots = new int[references == null ? 0 : references.size()];
        for (var i = 0; i < slots.length; i++) {
            var reference = references.get(i);
            slots[i] = reference.isNull()
                    ? -1
                    : newPlace.containsKey(reference.getIndex())
                            ? newPlace.get(reference.getIndex())
                            : findOrAddLight(lightSource, reference.getIndex(), levelBrightness, lights, byReference);
        }
        for (var lightmap : tail.lightmaps) {
            for (var entry : lightmap.entries()) {
                findOrAddLight(lightSource, entry.light(), levelBrightness, lights, byReference);
            }
        }
        var associationLights = new NativeBspLightAssociation.Light[lights.size()];
        for (var i = 0; i < associationLights.length; i++) {
            associationLights[i] = lights.get(i).association();
        }
        var candidates = NativeBspLightAssociation.build(model, associationLights, slots);
        checkCancelled(cancelled);
        var shadows = BspShadowSceneCapture.capture(map, level, model, terrainHeights, cancelled);
        var variantCount = 1;
        for (var texture : tail.textures) {
            variantCount = Math.max(variantCount, texture.variants().size());
        }
        var slotToward = new float[variantCount][];
        for (var v = 0; v < variantCount; v++) {
            var slot = NativeBspSunDirection.directionForSlot(
                    v, variantCount, settings.sunLongitude(), settings.sunSlope(), settings.reverseSlope());
            slotToward[v] = new float[] {
                slot.towardLight().x(),
                slot.towardLight().y(),
                slot.towardLight().z()
            };
        }
        var key = savedSha256 != null && original != null ? savedSha256 + '|' + settings : null;
        boolean[] rules;
        synchronized (savedRules) {
            rules = key == null ? null : savedRules.get(key);
        }
        if (rules == null) {
            var bakedShadows = shadows;
            if (original != null && (original != map || !terrainHeights.isEmpty())) {
                var originalLevels = new ArrayList<ULevel>();
                original.loadObjects("Level", originalLevels);
                var originalLevel = originalLevels.getFirst();
                bakedShadows = BspShadowSceneCapture.capture(
                        original, originalLevel, originalLevel.getModel().get(), Map.of(), cancelled);
            }
            var draft = new BspLightingScene(
                    name,
                    modelExport,
                    tail,
                    surfaces,
                    sunZones,
                    zoneBases,
                    List.copyOf(lights),
                    Map.copyOf(byReference),
                    movedTo,
                    placed,
                    candidates,
                    bakedShadows,
                    true,
                    true,
                    slotToward,
                    new int[variantCount],
                    null,
                    beast,
                    notes);
            rules = new boolean[] {
                draft.doesSavedBakeHaveMeshShadows(false, cancelled),
                draft.doesSavedBakeHaveMeshShadows(true, cancelled)
            };
            if (key != null) {
                synchronized (savedRules) {
                    savedRules.put(key, rules);
                }
            }
        }
        var pointMeshes = rules[0];
        var sunMeshes = rules[1];
        if (!pointMeshes) {
            notes.add("The saved bake of " + name
                    + " has no static mesh shadows from ordinary lights, so the rebuild leaves them out too");
        }
        if (!sunMeshes) {
            notes.add("The saved bake of " + name
                    + " has no static mesh shadows from sunlight, so the rebuild leaves them out too");
        }
        var calibrated = new BspLightingScene(
                name,
                modelExport,
                tail,
                surfaces,
                sunZones,
                zoneBases,
                List.copyOf(lights),
                Map.copyOf(byReference),
                movedTo,
                placed,
                candidates,
                shadows,
                pointMeshes,
                sunMeshes,
                slotToward,
                new int[variantCount],
                null,
                beast,
                notes);
        var fit = calibrated.fitSunColors(cancelled);
        return new BspLightingScene(
                name,
                modelExport,
                tail,
                surfaces,
                sunZones,
                zoneBases,
                List.copyOf(lights),
                Map.copyOf(byReference),
                movedTo,
                placed,
                candidates,
                shadows,
                pointMeshes,
                sunMeshes,
                slotToward,
                fit.bases(),
                fit.color(),
                beast,
                notes);
    }

    static float typeFactor(LightActor actor) {
        return switch (actor.getLightType()) {
            case 1 -> 1f;
            case 2 -> sine(actor.getLightPhase() << 8) * .39f + .6f;
            case 7 -> sine(actor.getLightPhase() << 8) * .09f + .9f;
            default -> 0f;
        };
    }

    static boolean isHidden(BspShadowScene scene, float[] surfaceFlags, int triangle, boolean sun) {
        var flags = scene.getFlags(triangle);
        if ((flags & BspShadowScene.BSP) == 0) {
            return false;
        }
        var bits = Float.floatToRawIntBits(surfaceFlags[scene.getOwner(triangle)]);
        return (bits & HIDDEN_SURFACES) != 0 || sun && (bits & 0x80) != 0;
    }

    static List<List<Entry>> groupByLight(Lightmap lightmap) {
        var groups = new ArrayList<List<Entry>>();
        for (var entry : lightmap.entries()) {
            if (groups.isEmpty() || groups.getLast().getFirst().light() != entry.light()) {
                groups.add(new ArrayList<>());
            }
            groups.getLast().add(entry);
        }
        return groups;
    }

    BspLightingScene withShadows(Result replacement) {
        return new BspLightingScene(
                map,
                modelExport,
                tail,
                surfaces,
                sunZones,
                zoneBases,
                lights,
                lightsByReference,
                movedTo,
                placed,
                candidates,
                replacement,
                pointMeshes,
                sunMeshes,
                slotToward,
                sunBases,
                sunColor,
                beastLighting,
                notes);
    }

    Profile getProfile(boolean sun) {
        return new Profile(sun ? 2 : 1, sun ? sunMeshes : pointMeshes);
    }

    boolean isSunZone(Lightmap lightmap) {
        return lightmap.zone() >= 0 && lightmap.zone() < sunZones.length && sunZones[lightmap.zone()];
    }

    int getVariants(Lightmap lightmap) {
        var texture = lightmap.texture() < tail.textures.size() ? tail.textures.get(lightmap.texture()) : null;
        return texture == null ? 1 : Math.max(1, texture.variants().size());
    }

    NativeBspLightmapTexels.Light getTexelLight(Light light) {
        var texel = light.texel();
        if (light.sunlightColor() && sunColor != null) {
            return new NativeBspLightmapTexels.Light(
                    texel.effect(),
                    texel.x(),
                    texel.y(),
                    texel.z(),
                    texel.radius(),
                    sunColor[0],
                    sunColor[1],
                    sunColor[2],
                    texel.directionX(),
                    texel.directionY(),
                    texel.directionZ(),
                    texel.cone());
        }
        return texel;
    }

    NativeBspLightmapTexels.Light getTexelLight(Light light, LightColor color) {
        var texel = getTexelLight(light);
        var base = UnrealHsv.toRgb(color.hue(), color.saturation(), 255);
        var scale = color.brightness() / 255f * light.colorScale();
        return new NativeBspLightmapTexels.Light(
                texel.effect(),
                texel.x(),
                texel.y(),
                texel.z(),
                texel.radius(),
                base.getX() * scale,
                base.getY() * scale,
                base.getZ() * scale,
                texel.directionX(),
                texel.directionY(),
                texel.directionZ(),
                texel.cone());
    }

    int getBase(Lightmap lightmap, int variant) {
        if (isSunZone(lightmap)) {
            return variant < sunBases.length ? sunBases[variant] : 0;
        }
        return lightmap.zone() >= 0 && lightmap.zone() < zoneBases.length ? zoneBases[lightmap.zone()] : 0;
    }

    Entry getEntry(Lightmap lightmap, Light light, int variant, Filter extra) {
        var surface = surfaces[lightmap.surface()];
        var scene = shadows.scene();
        var surfaceFlags = shadows.surfaceFlags();
        if (light.sun()) {
            var toward =
                    isSunZone(lightmap) ? slotToward[Math.min(variant, slotToward.length - 1)] : light.towardAuthored();
            return NativeBspShadowBitmaps.buildSunBitmap(
                    lightmap,
                    surface.plane(),
                    surface.normal(),
                    light.reference(),
                    toward,
                    scene,
                    getProfile(true),
                    triangle -> isHidden(scene, surfaceFlags, triangle, true) || extra != null && extra.skip(triangle));
        }
        var texel = light.texel();
        var rect = NativeBspShadowBitmaps.pointRect(
                lightmap, surface.plane(), texel.x(), texel.y(), texel.z(), texel.radius());
        return NativeBspShadowBitmaps.buildPointBitmap(
                lightmap,
                surface.plane(),
                light.reference(),
                texel.x(),
                texel.y(),
                texel.z(),
                rect,
                scene,
                getProfile(false),
                triangle -> isHidden(scene, surfaceFlags, triangle, false) || extra != null && extra.skip(triangle));
    }

    boolean isWritable() {
        return !beastLighting && !tail.textures.isEmpty();
    }

    private boolean doesSavedBakeHaveMeshShadows(boolean sun, BooleanSupplier cancelled) {
        var samples = new ArrayList<int[]>();
        for (var i = 0; i < tail.lightmaps.size(); i++) {
            var lightmap = tail.lightmaps.get(i);
            var groups = groupByLight(lightmap);
            for (var group : groups) {
                var index = lightsByReference.get(group.getFirst().light());
                if (index == null || index < 0 || lights.get(index).sun() != sun) {
                    continue;
                }
                if (sun && isSunZone(lightmap) && group.size() != getVariants(lightmap)) {
                    continue;
                }
                samples.add(new int[] {i, index, group.size()});
            }
        }
        if (samples.isEmpty()) {
            return true;
        }
        var step = Math.max(1, samples.size() / SAMPLE_ENTRIES);
        long with = 0;
        long without = 0;
        long total = 0;
        var noMesh = (Filter) triangle -> (shadows.scene().getFlags(triangle) & BspShadowScene.MESH) != 0;
        for (var s = 0; s < samples.size(); s += step) {
            checkCancelled(cancelled);
            var sample = samples.get(s);
            var lightmap = tail.lightmaps.get(sample[0]);
            var light = lights.get(sample[1]);
            var saved = groupByLight(lightmap).stream()
                    .filter(group -> group.getFirst().light() == light.reference())
                    .findFirst()
                    .orElseThrow();
            var variant = sun && isSunZone(lightmap) ? s / step % saved.size() : 0;
            var expected = saved.get(variant);
            var a = getEntry(lightmap, light, variant, null);
            var b = getEntry(lightmap, light, variant, noMesh);
            for (var y = 0; y < expected.sizeY(); y++) {
                for (var x = 0; x < expected.sizeX(); x++) {
                    var bit = expected.bit(x, y);
                    with += a.bit(x, y) == bit ? 1 : 0;
                    without += b.bit(x, y) == bit ? 1 : 0;
                    total++;
                }
            }
        }
        return total == 0 || without <= with + total / 50;
    }

    record SunFit(int[] bases, float @Nullable [] color) {}

    private SunFit fitSunColors(BooleanSupplier cancelled) {
        var variants = slotToward.length;
        var sums = new double[variants][9];
        var decoded = new HashMap<String, byte[]>();
        for (var lightmap : tail.lightmaps) {
            checkCancelled(cancelled);
            if (!isSunZone(lightmap) || lightmap.texture() >= tail.textures.size()) {
                continue;
            }
            var texture = tail.textures.get(lightmap.texture());
            var count = texture.variants().size();
            var groups = groupByLight(lightmap);
            var usable = true;
            for (var group : groups) {
                if (group.size() != count) {
                    usable = false;
                }
            }
            if (!usable) {
                continue;
            }
            var surface = surfaces[lightmap.surface()].texelSurface();
            for (var v = 0; v < Math.min(count, variants); v++) {
                var sunLit = new ArrayList<Lit>();
                var otherLit = new ArrayList<Lit>();
                for (var group : groups) {
                    var index = lightsByReference.get(group.getFirst().light());
                    if (index == null || index < 0) {
                        continue;
                    }
                    var light = lights.get(index);
                    var texel = light.texel();
                    var white = new NativeBspLightmapTexels.Light(
                            texel.effect(),
                            texel.x(),
                            texel.y(),
                            texel.z(),
                            texel.radius(),
                            1f,
                            1f,
                            1f,
                            texel.directionX(),
                            texel.directionY(),
                            texel.directionZ(),
                            texel.cone());
                    (light.sunlightColor() ? sunLit : otherLit).add(new Lit(group.get(v), white));
                }
                var sunOnly = NativeBspLightmapTexels.computeTexels(lightmap, surface, 0, sunLit);
                var others = NativeBspLightmapTexels.computeTexels(lightmap, surface, 0, otherLit);
                var variant = texture.variants().get(v);
                if (variant.format() != 3 || variant.width() <= 0) {
                    continue;
                }
                var atlas = decoded.computeIfAbsent(
                        lightmap.texture() + ":" + v,
                        key -> DxtMipCodec.decodeToRgba(
                                TextureFormat.TEXTURE_DXT1, variant.width(), variant.height(), variant.full()));
                for (var y = 0; y < lightmap.sizeY(); y++) {
                    for (var x = 0; x < lightmap.sizeX(); x++) {
                        var p = (y * lightmap.sizeX() + x) * 4;
                        if (others[p] != 0 || others[p + 1] != 0 || others[p + 2] != 0) {
                            continue;
                        }
                        var px = lightmap.offsetX() + x;
                        var py = lightmap.offsetY() + y;
                        if (px >= variant.width() || py >= variant.height()) {
                            continue;
                        }
                        var intensity = sunOnly[p + 2] & 0xff;
                        var d = (py * variant.width() + px) * 4;
                        var f = sums[v];
                        f[0]++;
                        f[1] += intensity;
                        f[2] += (double) intensity * intensity;
                        for (var c = 0; c < 3; c++) {
                            var value = atlas[d + c] & 0xff;
                            f[3 + c] += value;
                            f[6 + c] += value * (double) intensity;
                        }
                    }
                }
            }
        }
        var numerator = new double[3];
        var denominator = 0.0;
        for (var f : sums) {
            if (f[0] < 16) {
                continue;
            }
            var det = f[0] * f[2] - f[1] * f[1];
            if (det <= 0) {
                continue;
            }
            for (var c = 0; c < 3; c++) {
                numerator[c] += f[0] * f[6 + c] - f[1] * f[3 + c];
            }
            denominator += det;
        }
        float[] color = null;
        if (denominator > 0) {
            color = new float[3];
            for (var c = 0; c < 3; c++) {
                color[c] = (float) Math.max(0, numerator[c] / denominator);
            }
        }
        var bases = new int[variants];
        for (var v = 0; v < variants; v++) {
            var f = sums[v];
            if (f[0] < 1) {
                continue;
            }
            var channels = new int[3];
            for (var c = 0; c < 3; c++) {
                var k = color == null ? 0 : color[c];
                channels[c] = Math.clamp(Math.round((f[3 + c] - k * f[1]) / f[0] + (color == null ? 0 : .5)), 0, 255);
            }
            bases[v] = channels[2] | channels[1] << 8 | channels[0] << 16;
        }
        return new SunFit(bases, color);
    }

    private static int findOrAddLight(
            UArchive map, int reference, float levelBrightness, List<Light> lights, Map<Integer, Integer> byReference) {
        var known = byReference.get(reference);
        if (known != null) {
            return known;
        }
        Object object;
        try {
            object = map.getObjectLoader().loadObject(reference);
        } catch (RuntimeException failure) {
            object = null;
        }
        if (!(object instanceof LightActor actor)) {
            return -2;
        }
        var base = UnrealHsv.toRgb(actor.getLightHue(), actor.getLightSaturation(), 255);
        var colorScale = levelBrightness * typeFactor(actor);
        var scale = actor.getLightBrightness() / 255f * colorScale;
        var rotation = actor.getRotation();
        var raw = NativeBspSunDirection.rawTowardFromRotation(rotation.pitch(), rotation.yaw());
        var toward = NativeBspSunDirection.fromRotation(rotation.pitch(), rotation.yaw());
        var location = actor.getLocation();
        var radius = (actor.getLightRadius() + 1f) * 25f;
        var effect = actor.getLightEffect();
        var association = new NativeBspLightAssociation.Light(
                effect,
                actor.isSpecialLit(),
                actor.isDeleteMe() || actor.isDynamicLight(),
                Math.max(0, actor.getRegionZoneNumber()),
                actor.getRegionZone() instanceof SkyZoneInfoActor,
                location.getX(),
                location.getY(),
                location.getZ(),
                radius,
                -raw.x(),
                -raw.y(),
                -raw.z());
        var texel = new NativeBspLightmapTexels.Light(
                effect,
                location.getX(),
                location.getY(),
                location.getZ(),
                radius,
                base.getX() * scale,
                base.getY() * scale,
                base.getZ() * scale,
                -raw.x(),
                -raw.y(),
                -raw.z(),
                actor.getLightCone());
        var index = lights.size();
        lights.add(new Light(
                reference,
                actor.getFullName(),
                association,
                texel,
                new float[] {toward.x(), toward.y(), toward.z()},
                actor.isSunlightColor() && effect == 19,
                colorScale));
        byReference.put(reference, index);
        return index;
    }

    private static float sine(int index) {
        return (float) Math.sin((index >> 2 & 0x3fff) * (2 * Math.PI / 16384));
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("BSP lighting build cancelled");
        }
    }
}
