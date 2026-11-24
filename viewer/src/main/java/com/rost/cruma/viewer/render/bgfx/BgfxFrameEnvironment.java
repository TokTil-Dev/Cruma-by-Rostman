package com.rost.cruma.viewer.render.bgfx;

import static java.nio.file.Files.isRegularFile;

import com.rost.cruma.core.env.EnvSetupParser;
import com.rost.cruma.core.env.EnvironmentSun;
import com.rost.cruma.core.env.FogRanges;
import com.rost.cruma.core.env.FogRanges.Range;
import com.rost.cruma.core.env.FogRanges.UnderwaterFog;
import com.rost.cruma.core.env.LobbyTimeEnv;
import com.rost.cruma.core.env.MovableSun;
import com.rost.cruma.core.env.ResolvedEnv;
import com.rost.cruma.core.env.TimeEnvHsv;
import com.rost.cruma.core.env.TimeEnvParser;
import com.rost.cruma.core.env.TimeEnvPreset;
import com.rost.cruma.core.env.WorldTimeEnv;
import com.rost.cruma.core.world.Entity;
import com.rost.cruma.core.world.Entity.DistanceFog;
import com.rost.cruma.core.world.Entity.SurfaceType;
import com.rost.cruma.core.world.L2FogInfo;
import com.rost.cruma.core.world.MaterialClient;
import com.rost.cruma.core.world.ModelLightRule;
import com.rost.cruma.core.world.ReflectionBspModel;
import com.rost.cruma.core.world.ReflectionBspModel.ZoneFog;
import com.rost.cruma.core.world.WaterVolume;
import com.rost.cruma.core.world.ZoneStateChoice;
import com.rost.cruma.viewer.config.Config.Viewer.World;
import com.rost.cruma.viewer.render.RenderingContext;
import com.rost.cruma.viewer.render.api.RenderState;
import com.rost.cruma.viewer.render.bgfx.BgfxNativeLightSources.Frame;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class BgfxFrameEnvironment {
    private static final int SHADOW_VARIANT_COUNT = 8;
    private static final Vec3 ZENITH_SUN_DIRECTION = new Vec3(0f, 0f, 1f);
    private static final Vec3 OUTDOOR_FOG_COLOR = new Vec3(128f / 255f);
    private static final float CLIENT_RANGE_UNIT = 32_768f * 0.0625f;
    private static final float MAX_CLIENT_CLIP_RANGE = 8f;
    private static final int TILE_SIZE = 32_768;
    private static final int TILE_X_ORIGIN = 20;
    private static final int TILE_Y_ORIGIN = 18;
    private static final Pattern MODEL_TILE = Pattern.compile("(?:^|\\s)(-?\\d+)_(-?\\d+)(?:\\.|$)");
    private static final String TIME_ENV_FAMILY = "timeenv";
    private static final String BEAST_TIME_ENV_FAMILY = "beasttimeenv";
    private static final String TIME_SETS = "Time";
    private static final String BEAST_TIME_SETS = "BeastTime";

    private static final Logger log = LogManager.getLogger(BgfxFrameEnvironment.class);

    private final RenderingContext renderingContext;
    private final RenderState state;
    private final List<TimeEnvPreset> presets = new ArrayList<>();
    private final List<TimeEnvPreset> beastPresets = new ArrayList<>();
    private List<Path> zoneSetFiles = List.of();
    private List<Path> zoneBeastSetFiles = List.of();
    private final Map<Integer, TimeEnvPreset> zoneSets = new HashMap<>();
    private final Map<Integer, TimeEnvPreset> zoneBeastSets = new HashMap<>();
    private boolean lobbyLight;
    private @Nullable Path clientPath;
    private MaterialClient materialClient = MaterialClient.UNKNOWN;
    private ModelLightRule modelLightRule = ModelLightRule.INTERLUDE;
    private float sunSlope;
    private float shadowTickInterval = -1f;
    private Frame nativeLightFrame;
    private final Map<Long, List<ReflectionBspModel>> reflectionModelsByTile = new HashMap<>();
    private final HfWaterVolumeFogTransition waterFogTransition = new HfWaterVolumeFogTransition();
    private long reflectionModelsRevision = Long.MIN_VALUE;
    private FogRanges fogRanges;
    private UnderwaterFog underwaterFog;
    private int fogRangePreset;

    BgfxFrameEnvironment(RenderingContext renderingContext, RenderState state) {
        this.renderingContext = renderingContext;
        this.state = state;
    }

    static boolean hasLocalFogInfluence(List<L2FogInfo> infos, Vec3 position) {
        for (var info : infos) {
            if (info.getInfluenceAt(position) > 0f) {
                return true;
            }
        }
        return false;
    }

    static int shadowVariantForHour(float hour) {
        return Math.round(shadowSlotForHour(hour)) % SHADOW_VARIANT_COUNT;
    }

    static float shadowSlotForHour(float hour) {
        if (!Float.isFinite(hour)) {
            return 0f;
        }
        var wrapped = hour % 24f;
        if (wrapped < 0f) {
            wrapped += 24f;
        }
        return wrapped / 3f;
    }

    static Vec4 resolveUnderwaterSkyModifier(WaterVolume containingVolume, UnderwaterFog fallback) {
        if (containingVolume == null || !containingVolume.isL2WaterVolume()) {
            return null;
        }
        var settings = containingVolume.getFogSettings();
        if (settings.useOwnValues()) {
            return settings.ownColor();
        }
        return fallback == null ? null : new Vec4(fallback.red(), fallback.green(), fallback.blue(), fallback.alpha());
    }

    static DistanceFog resolveVolumeFog(WaterVolume volume, ZoneFog activeZone) {
        var settings = volume.getFogSettings();
        var target =
                !settings.useOwnValues() && activeZone != null ? activeZone.volumeFallbackFog() : settings.ownValues();
        return new DistanceFog(new Vec3(target.color()), target.start(), target.end());
    }

    public static Range outdoorRange(FogRanges ranges, int configuredPreset, float viewDistance, float cameraHeight) {
        var clipRange = viewDistance / CLIENT_RANGE_UNIT;
        var preset = configuredPreset > 0 ? configuredPreset : presetForClipRange(clipRange);
        var range = ranges.worldRange(preset, cameraHeight);
        var scale = configuredPreset > 0 || clipRange <= MAX_CLIENT_CLIP_RANGE ? 1f : clipRange / MAX_CLIENT_CLIP_RANGE;
        return new Range(range.start() * scale, range.end() * scale);
    }

    static DistanceFog adjustFogStrength(DistanceFog fog, float percentage) {
        if (fog == null) {
            return null;
        }
        var finitePercentage = Float.isFinite(percentage) ? percentage : 100f;
        var strength = Math.max(0f, Math.min(2f, finitePercentage / 100f));
        if (strength <= 0f) {
            return null;
        }
        var width = Math.max(0.0001f, fog.end() - fog.start());
        return new DistanceFog(new Vec3(fog.color()), fog.start(), fog.start() + width / strength);
    }

    static Vec3 sunDirection(float hour, MaterialClient client, float slope) {
        return client == MaterialClient.UNKNOWN ? sunDirection(hour) : EnvironmentSun.direction(hour, slope);
    }

    static Vec3 sunDirection(float hour) {
        var time = hour < 6f ? hour + 24.0 : hour;
        var latitude = (time - 6.0) * Math.toRadians(10.0) - Math.PI / 2.0;
        var x = -Math.sin(latitude);
        var z = Math.cos(latitude);
        var tilt = Math.toRadians(30.0);
        var y = -z * Math.sin(tilt);
        z *= Math.cos(tilt);
        var length = Math.sqrt(x * x + y * y + z * z);
        return length < 1e-8
                ? new Vec3(ZENITH_SUN_DIRECTION)
                : new Vec3((float) (x / length), (float) (y / length), (float) (z / length));
    }

    void load(World worldConfig) {
        waterFogTransition.reset();
        clientPath = worldConfig.clientPath();
        materialClient = MaterialClient.detect(clientPath);
        modelLightRule = ModelLightRule.detect(clientPath, materialClient);
        var lobby = LobbyTimeEnv.files(clientPath, renderingContext.getStandaloneMap());
        lobbyLight = !lobby.isEmpty();
        zoneSets.clear();
        zoneBeastSets.clear();
        zoneSetFiles = WorldTimeEnv.lightSets(clientPath, TIME_SETS);
        zoneBeastSetFiles = WorldTimeEnv.lightSets(clientPath, BEAST_TIME_SETS);
        if (!lobby.isEmpty()) {
            loadLobbyPresets(lobby);
            beastPresets.clear();
        } else {
            loadWorldPresets();
            loadPresets(BEAST_TIME_ENV_FAMILY, beastPresets);
        }
        fogRangePreset = worldConfig.rendering().fogRangePreset();
        var fogPath = worldConfig.clientPath().resolve("system").resolve("env.int");
        sunSlope = 0f;
        shadowTickInterval = -1f;
        if (materialClient != MaterialClient.UNKNOWN) {
            try {
                var setup = EnvSetupParser.parse(fogPath);
                sunSlope = setup.slopeSunAngle();
                shadowTickInterval = setup.shadowTickInterval();
            } catch (RuntimeException error) {
                log.warn(
                        "Unable to read native sun slope from {}; using the engine default 0: {}",
                        fogPath,
                        error.toString());
            }
        }
        try {
            var parsed = FogRanges.parse(fogPath);
            underwaterFog = parsed.underwater();
            fogRanges = parsed.isUsable() ? parsed : null;
            if (fogRanges == null) {
                log.warn("The [FOG] section of env.int is incomplete: {}", fogPath);
            }
        } catch (RuntimeException e) {
            fogRanges = null;
            underwaterFog = null;
            log.warn("Distance fog unavailable at {}: {}", fogPath, e.toString());
        }
    }

    MaterialClient getMaterialClient() {
        return materialClient;
    }

    ModelLightRule getModelLightRule() {
        return modelLightRule;
    }

    float getShadowTickInterval() {
        return shadowTickInterval;
    }

    Frame getNativeLightFrame() {
        return nativeLightFrame;
    }

    Snapshot resolve(boolean interactiveEffects, float deltaSeconds) {
        waterFogTransition.tick(deltaSeconds);
        var orthographic = renderingContext.camera.isOrtho();
        if (presets.isEmpty()) {
            throw new IllegalStateException("client-authored TimeEnv lighting was not loaded");
        }
        var hour = state.settings.getTimeOfDay();
        var zone = state.settings.getSkyZone();
        var zoneSet = lobbyLight || zone == null || !zone.hasTime() ? -1 : zoneSetIndex(zone.timeEnv());
        var presetIndex =
                zoneSet >= 0 ? zoneSet : Math.max(0, Math.min(state.settings.getSkyPreset(), presets.size() - 1));
        var preset = zoneSet >= 0 ? zonePreset(zoneSet) : presets.get(presetIndex);
        var resolved = preset.resolve(hour, materialClient);
        var beastFamily = beastPresets.isEmpty() ? presets : beastPresets;
        var beastPreset = zoneSet >= 0
                ? zoneBeastPreset(zoneSet, beastFamily)
                : beastFamily.get(Math.min(presetIndex, beastFamily.size() - 1));
        var beastLighting = beastPreset.resolve(hour, materialClient);
        var beastResolved = beastPreset.hasBeastWeights()
                ? beastLighting
                : beastFamily.getFirst().resolve(hour, materialClient);
        var terrainAmbient = resolved.terrainAmbient();
        var terrainSun = resolved.terrainSun();
        var actorAmbient = resolved.actorAmbient();
        var actorSun = resolved.actorSun();
        var meshAmbient = resolved.staticMeshAmbient();
        var meshSun = resolved.staticMeshSun();
        var bspAmbient = resolved.bspAmbient();
        var bspSun = resolved.bspSun();
        var sunDirection = sunDirection(hour, materialClient, sunSlope);
        var movableSunDirection =
                materialClient == MaterialClient.UNKNOWN ? sunDirection : MovableSun.incidentDirection(hour, sunSlope);
        nativeLightFrame = new Frame(
                hour,
                presetIndex,
                actorSun,
                beastLighting.actorSun(),
                movableSunDirection,
                TimeEnvHsv.interpColor(hour, preset.hsvActorLight()),
                TimeEnvHsv.interpColor(hour, beastPreset.hsvActorLight()));
        var localAtmosphereActive = interactiveEffects && !orthographic;
        var zoneState = zoneState();
        var fogInfos =
                L2FogInfo.inZoneState(renderingContext.scene.getL2FogInfos(), zoneState, renderingContext.zoneFades);
        if (localAtmosphereActive) {
            resolved = L2FogInfo.blendSky(fogInfos, renderingContext.camera.getPosition(), hour, resolved);
        }
        var viewDistance = (float) Math.max(1, state.settings.getViewDistance());
        var containingVolume =
                renderingContext.scene.findContainingWaterVolume(renderingContext.camera.getPosition(), zoneState);
        var fog = resolveFog(viewDistance, localAtmosphereActive, hour, containingVolume, fogInfos);
        fog = adjustFogStrength(fog, state.settings.getFogStrength());
        var localFogActive = localAtmosphereActive
                && fog != null
                && (containingVolume == null
                        || !containingVolume.getFogSettings().enabled())
                && hasLocalFogInfluence(fogInfos, renderingContext.camera.getPosition());
        var underwaterSkyModifier =
                localAtmosphereActive ? resolveUnderwaterSkyModifier(containingVolume, underwaterFog) : null;
        var signature =
                mix(computeSignature(presetIndex, hour, fog, underwaterSkyModifier, resolved), localFogActive ? 1 : 0);
        return new Snapshot(
                resolved,
                beastResolved,
                terrainAmbient,
                terrainSun,
                sunDirection,
                movableSunDirection,
                actorSun,
                actorAmbient,
                meshSun,
                meshAmbient,
                bspSun,
                bspAmbient,
                fog,
                localFogActive,
                underwaterSkyModifier,
                shadowVariantForHour(hour),
                shadowSlotForHour(hour),
                signature,
                zoneSet >= 0 ? preset.envType() : -1);
    }

    boolean isCameraInAuthoredInterior() {
        if (renderingContext.camera.isOrtho()) {
            return false;
        }
        var zone = cameraZone(renderingContext.camera.getPosition());
        return zone != null && !zone.sunAffected();
    }

    float getTimeSinceLastFogChange() {
        return waterFogTransition.getTimeSinceLastFogChange();
    }

    Vec3 getCurrentSunDirection() {
        return sunDirection(state.settings.getTimeOfDay(), materialClient, sunSlope);
    }

    private int zoneSetIndex(int timeEnv) {
        var count = zoneSetFiles.isEmpty() ? presets.size() : zoneSetFiles.size();
        return timeEnv < 0 || timeEnv >= count ? 0 : timeEnv;
    }

    private TimeEnvPreset zonePreset(int index) {
        if (index < presets.size()) {
            return presets.get(index);
        }
        return zoneSets.computeIfAbsent(index, number -> {
            var file = zoneSetFiles.get(number);
            try {
                return TimeEnvParser.parse(file, materialClient);
            } catch (RuntimeException e) {
                log.warn("The light set {} of a sky zone is unreadable, Normal stands in: {}", file, e.toString());
                return presets.getFirst();
            }
        });
    }

    private TimeEnvPreset zoneBeastPreset(int index, List<TimeEnvPreset> beastFamily) {
        if (index < beastFamily.size()) {
            return beastFamily.get(index);
        }
        if (beastPresets.isEmpty() || index >= zoneBeastSetFiles.size()) {
            return beastFamily == presets ? zonePreset(index) : beastFamily.getLast();
        }
        return zoneBeastSets.computeIfAbsent(index, number -> {
            var file = zoneBeastSetFiles.get(number);
            try {
                return TimeEnvParser.parse(file, materialClient);
            } catch (RuntimeException e) {
                log.warn("The Beast light set {} of a sky zone is unreadable: {}", file, e.toString());
                return beastFamily.getLast();
            }
        });
    }

    private void loadWorldPresets() {
        presets.clear();
        var files = WorldTimeEnv.files(clientPath);
        if (files.isEmpty()) {
            throw new IllegalStateException("required client-authored " + TIME_ENV_FAMILY + "0 is unavailable at "
                    + presetPath(TIME_ENV_FAMILY, 0));
        }
        for (var file : files) {
            try {
                presets.add(TimeEnvParser.parse(file, materialClient));
            } catch (RuntimeException e) {
                throw new IllegalStateException("required client-authored world light is unavailable at " + file, e);
            }
        }
        if (files.size() < 4) {
            log.info("Client has {} world light presets: {}", files.size(), files);
        }
    }

    private void loadPresets(String family, List<TimeEnvPreset> target) {
        target.clear();
        for (var index = 0; index < 4; index++) {
            var path = presetPath(family, index);
            if (!isRegularFile(path)) {
                log.info("Client has no {}{}: {} presets available", family, index, target.size());
                break;
            }
            try {
                target.add(TimeEnvParser.parse(path, materialClient));
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "required client-authored " + family + index + " is unavailable at " + path, e);
            }
        }
    }

    private void loadLobbyPresets(List<Path> files) {
        presets.clear();
        for (var file : files) {
            try {
                presets.add(TimeEnvParser.parse(file, materialClient));
            } catch (RuntimeException e) {
                throw new IllegalStateException("required client-authored lobby light is unavailable at " + file, e);
            }
        }
        log.info("Lobby {} is lit from {}", renderingContext.getStandaloneMap(), files);
    }

    private Path presetPath(String family, int index) {
        return clientPath.resolve("system").resolve(family + index + ".int");
    }

    private DistanceFog resolveFog(
            float viewDistance,
            boolean localAtmosphereActive,
            float hour,
            WaterVolume containingVolume,
            List<L2FogInfo> infos) {
        if (renderingContext.camera.isOrtho()) {
            waterFogTransition.observeInactive(null);
            return null;
        }
        var position = renderingContext.camera.getPosition();
        var zone = cameraZone(position);
        if (containingVolume != null && containingVolume.getFogSettings().enabled()) {
            return waterFogTransition.update(containingVolume, resolveVolumeFog(containingVolume, zone));
        }
        if (fogRanges == null) {
            waterFogTransition.observeInactive(containingVolume);
            return null;
        }
        var clipRange = viewDistance / CLIENT_RANGE_UNIT;
        var extendedOutdoorBase = false;
        DistanceFog base;
        if (zone != null && zone.fog() != null && !zone.terrainZone()) {
            base = zone.fog();
        } else {
            var range = outdoorRange(fogRanges, fogRangePreset, viewDistance, position.getZ());
            var color = zone != null && zone.fog() != null ? zone.fog().color() : OUTDOOR_FOG_COLOR;
            base = new DistanceFog(color, range.start(), range.end());
            extendedOutdoorBase = fogRangePreset <= 0 && clipRange > MAX_CLIENT_CLIP_RANGE;
        }
        if (localAtmosphereActive) {
            var preset = fogRangePreset > 0 ? fogRangePreset : presetForClipRange(clipRange);
            base = extendedOutdoorBase
                    ? L2FogInfo.blendFogOverExtendedView(infos, position, hour, preset, base)
                    : L2FogInfo.blendFog(infos, position, hour, preset, base);
        }
        waterFogTransition.observeInactive(containingVolume);
        return base;
    }

    private ZoneStateChoice zoneState() {
        var events = state.settings.getGameEventStates();
        return events == null ? ZoneStateChoice.EMPTY : events.zoneStates();
    }

    private ZoneFog cameraZone(Vec3 position) {
        var tileX = (int) Math.floor(position.getX() / TILE_SIZE) + TILE_X_ORIGIN;
        var tileY = (int) Math.floor(position.getY() / TILE_SIZE) + TILE_Y_ORIGIN;
        refreshReflectionModelsByTile();
        var models = reflectionModelsByTile.get(tileKey(tileX, tileY));
        if (models == null) {
            return null;
        }
        for (var model : models) {
            var zone = model.getFogAt(position, zoneState());
            if (zone != null) {
                return zone;
            }
        }
        return null;
    }

    private void refreshReflectionModelsByTile() {
        var scene = renderingContext.scene;
        var revision = scene.getReflectionBspModelsRevision();
        if (reflectionModelsRevision == revision) {
            return;
        }
        reflectionModelsByTile.clear();
        for (var model : scene.getReflectionBspModels().values()) {
            var matcher = MODEL_TILE.matcher(model.getId());
            if (!matcher.find()) {
                continue;
            }
            try {
                var tileX = Integer.parseInt(matcher.group(1));
                var tileY = Integer.parseInt(matcher.group(2));
                reflectionModelsByTile
                        .computeIfAbsent(tileKey(tileX, tileY), _ -> new ArrayList<>())
                        .add(model);
            } catch (NumberFormatException ignored) {
            }
        }
        reflectionModelsRevision = revision;
    }

    private static long tileKey(int tileX, int tileY) {
        return (long) tileX << 32 | Integer.toUnsignedLong(tileY);
    }

    private static int presetForClipRange(float clipRange) {
        if (clipRange >= 8f) {
            return 5;
        }
        if (clipRange >= 7f) {
            return 4;
        }
        if (clipRange >= 6f) {
            return 3;
        }
        if (clipRange >= 5f) {
            return 2;
        }
        return 1;
    }

    private static long computeSignature(
            int preset, float hour, DistanceFog fog, Vec4 underwaterSkyModifier, ResolvedEnv resolved) {
        long hash = 0x9e3779b97f4a7c15L;
        hash = mix(hash, preset);
        hash = mix(hash, Float.floatToIntBits(hour));
        if (fog != null) {
            hash = mix(hash, Float.floatToIntBits(fog.color().getX()));
            hash = mix(hash, Float.floatToIntBits(fog.color().getY()));
            hash = mix(hash, Float.floatToIntBits(fog.color().getZ()));
            hash = mix(hash, Float.floatToIntBits(fog.start()));
            hash = mix(hash, Float.floatToIntBits(fog.end()));
        }
        if (underwaterSkyModifier != null) {
            hash = mix(hash, Float.floatToIntBits(underwaterSkyModifier.getX()));
            hash = mix(hash, Float.floatToIntBits(underwaterSkyModifier.getY()));
            hash = mix(hash, Float.floatToIntBits(underwaterSkyModifier.getZ()));
            hash = mix(hash, Float.floatToIntBits(underwaterSkyModifier.getW()));
        }
        hash = mix(hash, resolved.skyBox());
        hash = mix(hash, resolved.haze());
        hash = mix(hash, resolved.cloud1());
        hash = mix(hash, resolved.cloud2());
        hash = mix(hash, resolved.cloud3());
        return hash;
    }

    private static long mix(long hash, Vec3 value) {
        hash = mix(hash, Float.floatToIntBits(value.getX()));
        hash = mix(hash, Float.floatToIntBits(value.getY()));
        return mix(hash, Float.floatToIntBits(value.getZ()));
    }

    private static long mix(long hash, int value) {
        hash ^= Integer.toUnsignedLong(value) + 0x9e3779b9L + (hash << 6) + (hash >>> 2);
        return hash;
    }

    record Snapshot(
            ResolvedEnv resolved,
            ResolvedEnv beastResolved,
            Vec3 terrainAmbient,
            Vec3 terrainSun,
            Vec3 sunDirection,
            Vec3 movableSunDirection,
            Vec3 actorSun,
            Vec3 actorAmbient,
            Vec3 staticMeshSun,
            Vec3 staticMeshAmbient,
            Vec3 bspSun,
            Vec3 bspAmbient,
            DistanceFog fog,
            boolean localFogActive,
            Vec4 underwaterSkyModifier,
            int shadowVariant,
            float shadowSlot,
            long signature,
            int zoneEnvType) {
        Snapshot {
            if (shadowVariant < 0 || shadowVariant >= SHADOW_VARIANT_COUNT) {
                throw new IllegalArgumentException("invalid baked shadow variant " + shadowVariant);
            }
            if (!Float.isFinite(shadowSlot) || shadowSlot < 0f || shadowSlot >= SHADOW_VARIANT_COUNT) {
                throw new IllegalArgumentException("invalid baked shadow slot " + shadowSlot);
            }
            underwaterSkyModifier = underwaterSkyModifier == null ? null : new Vec4(underwaterSkyModifier);
        }

        @Override
        public Vec4 underwaterSkyModifier() {
            return underwaterSkyModifier == null ? null : new Vec4(underwaterSkyModifier);
        }

        Vec3 entitySun(long surfaceType) {
            if ((surfaceType & SurfaceType.CSG.getId()) != 0) {
                return bspSun;
            }
            if ((surfaceType & SurfaceType.STATIC_MESH.getId()) != 0) {
                return staticMeshSun;
            }
            return actorSun;
        }

        Vec3 entityAmbient(long surfaceType) {
            if ((surfaceType & SurfaceType.CSG.getId()) != 0) {
                return bspAmbient;
            }
            if ((surfaceType & SurfaceType.STATIC_MESH.getId()) != 0) {
                return staticMeshAmbient;
            }
            return actorAmbient;
        }

        Vec3 beastAmbient() {
            return beastResolved.staticMeshAmbient();
        }

        float beastLightmap1() {
            return beastResolved.beastLightmap1();
        }

        float beastLightmap2() {
            return beastResolved.beastLightmap2();
        }
    }
}
