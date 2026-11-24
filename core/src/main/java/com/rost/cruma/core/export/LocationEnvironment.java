package com.rost.cruma.core.export;

import com.rost.cruma.core.env.EnvSetupParser;
import com.rost.cruma.core.env.EnvironmentSun;
import com.rost.cruma.core.env.FogRanges;
import com.rost.cruma.core.env.LobbyTimeEnv;
import com.rost.cruma.core.env.ResolvedEnv;
import com.rost.cruma.core.env.TimeEnvParser;
import com.rost.cruma.core.env.WorldTimeEnv;
import com.rost.cruma.core.export.SkyPanorama.Sprite;
import com.rost.cruma.core.world.Entity.DistanceFog;
import com.rost.cruma.core.world.Entity.TerrainMesh;
import com.rost.cruma.core.world.L2FogInfo;
import com.rost.cruma.core.world.LocalSky;
import com.rost.cruma.core.world.MaterialClient;
import com.rost.cruma.core.world.ReflectionBspModel.ZoneFog;
import com.rost.cruma.core.world.SkyRules;
import com.rost.cruma.core.world.SkyZone;
import com.rost.cruma.core.world.SkyZones;
import com.rost.cruma.core.world.UMap;
import com.rost.cruma.core.world.USky;
import com.rost.cruma.core.world.USky.Celestial;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.core.world.ZoneStateChoice;
import glm_.vec3.Vec3;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

record LocationEnvironment(
        int hour,
        Vec3 toSun,
        Vec3 sun,
        Vec3 ambient,
        @Nullable RgbaImage sky,
        @Nullable Vec3 fogColor,
        float fogStart,
        float fogEnd,
        String timeEnv,
        Vec3 meshSun,
        Vec3 meshAmbient,
        float beastSun,
        float beastStatic,
        float lightHour,
        @Nullable String skyZone) {
    static final int DEFAULT_HOUR = 13;

    static final int SKY_WIDTH = 4096;

    private static final int FOG_PRESET = 5;

    private static final float SUN_SIZE = 50;

    private static final float MOON_SIZE = 30;

    private static final String WORLD_TIME_ENV = "timeenv0.int";

    private static final Vec3 OUTDOOR_FOG = new Vec3(128f / 255f);

    private static final double GAMMA = 2.2;

    LocationEnvironment(
            int hour,
            Vec3 toSun,
            Vec3 sun,
            Vec3 ambient,
            @Nullable RgbaImage sky,
            @Nullable Vec3 fogColor,
            float fogStart,
            float fogEnd) {
        this(hour, toSun, sun, ambient, sky, fogColor, fogStart, fogEnd, WORLD_TIME_ENV);
    }

    LocationEnvironment(
            int hour,
            Vec3 toSun,
            Vec3 sun,
            Vec3 ambient,
            @Nullable RgbaImage sky,
            @Nullable Vec3 fogColor,
            float fogStart,
            float fogEnd,
            String timeEnv) {
        this(hour, toSun, sun, ambient, sky, fogColor, fogStart, fogEnd, timeEnv, sun, ambient);
    }

    LocationEnvironment(
            int hour,
            Vec3 toSun,
            Vec3 sun,
            Vec3 ambient,
            @Nullable RgbaImage sky,
            @Nullable Vec3 fogColor,
            float fogStart,
            float fogEnd,
            String timeEnv,
            Vec3 meshSun,
            Vec3 meshAmbient) {
        this(hour, toSun, sun, ambient, sky, fogColor, fogStart, fogEnd, timeEnv, meshSun, meshAmbient, 0, 0);
    }

    LocationEnvironment(
            int hour,
            Vec3 toSun,
            Vec3 sun,
            Vec3 ambient,
            @Nullable RgbaImage sky,
            @Nullable Vec3 fogColor,
            float fogStart,
            float fogEnd,
            String timeEnv,
            Vec3 meshSun,
            Vec3 meshAmbient,
            float beastSun,
            float beastStatic) {
        this(
                hour,
                toSun,
                sun,
                ambient,
                sky,
                fogColor,
                fogStart,
                fogEnd,
                timeEnv,
                meshSun,
                meshAmbient,
                beastSun,
                beastStatic,
                hour,
                null);
    }

    static float toLinear(float value) {
        return (float) Math.pow(Math.max(0f, value), GAMMA);
    }

    static final class Reader {
        private final Path client;
        private final UnrealLoader loader;
        private final float x;
        private final float y;
        private final int hour;
        private final ZoneStateChoice zoneState;
        private final List<L2FogInfo> fogInfos = new ArrayList<>();
        private final List<SkyZone> skyZones = new ArrayList<>();
        private @Nullable LocalSky localSky;
        private @Nullable ZoneFog zone;
        private float ground = Float.NaN;
        private boolean centerRead;
        private boolean anyRead;

        private @Nullable String lobby;

        Reader(Path client, UnrealLoader loader, float x, float y, int hour) {
            this(client, loader, x, y, hour, ZoneStateChoice.EMPTY);
        }

        Reader(Path client, UnrealLoader loader, float x, float y, int hour, ZoneStateChoice zoneState) {
            this.client = client;
            this.loader = loader;
            this.x = x;
            this.y = y;
            this.hour = hour;
            this.zoneState = zoneState;
        }

        void add(UMap map) {
            fogInfos.addAll(L2FogInfo.inZoneState(map.l2FogInfos(), zoneState));
            if (map.localSky() != null) {
                skyZones.addAll(map.localSky().zones());
            }
            if (LobbyTimeEnv.isLobby(map.name())) {
                lobby = map.name();
            }
            var center = isCenter(map);
            if (centerRead || anyRead && !center) {
                return;
            }
            anyRead = true;
            centerRead = center;
            localSky = map.localSky();
            ground = groundHeight(map, x, y);
            var point = point();
            zone = null;
            for (var model : map.reflectionBspModels()) {
                var found = model.getFogAt(point, zoneState);
                if (found != null) {
                    zone = found;
                    break;
                }
            }
        }

        @Nullable
        LocationEnvironment read(BooleanSupplier cancelled) {
            var system = client.resolve("system");
            var lobbyFiles = LobbyTimeEnv.files(client, lobby);
            var files = lobbyFiles.isEmpty() ? WorldTimeEnv.files(client) : lobbyFiles;
            if (files.isEmpty()) {
                return null;
            }
            var timeEnv = files.getFirst();
            var point = point();
            var skyZone = SkyZones.settledAt(skyZones, zoneState, null, point);
            var hour = skyZone.hasTime() ? skyZone.hour() : (float) this.hour;
            if (skyZone.hasTime() && lobbyFiles.isEmpty()) {
                var sets = WorldTimeEnv.lightSets(client, "Time");
                var set = skyZone.timeEnv() < sets.size() ? skyZone.timeEnv() : 0;
                if (set < sets.size()) {
                    timeEnv = sets.get(set);
                }
            }
            var materialClient = MaterialClient.detect(client);
            var preset = TimeEnvParser.parse(timeEnv, materialClient);
            var resolved = preset.resolve(hour, materialClient);
            resolved = L2FogInfo.blendSky(fogInfos, point, hour, resolved);
            var envInt = system.resolve("env.int");
            var slope = 0f;
            if (materialClient != MaterialClient.UNKNOWN) {
                try {
                    slope = EnvSetupParser.parse(envInt).slopeSunAngle();
                } catch (RuntimeException unreadable) {
                    slope = 0f;
                }
            }
            var toSun =
                    materialClient == MaterialClient.UNKNOWN ? legacySun(hour) : EnvironmentSun.direction(hour, slope);
            var fog = readFog(envInt, point, hour);
            var env = resolved;
            var own = localSky == null ? null : localSky.sky();
            var overlay = 0f;
            USky sky;
            if (own == null
                    || !skyZone.found() && !localSky.ownSky()
                    || skyZone.found() && skyZone.sharedWeight() >= 1f) {
                sky = loader.loadSky("SkyLevel");
            } else {
                sky = own;
                overlay = skyZone.found() ? skyZone.sharedWeight() : 0f;
            }
            var panorama = bakeSky(sky, env, preset.envType(), hour, skyZone.showSun(), cancelled);
            if (overlay > 0f && panorama != null) {
                var shared =
                        bakeSky(loader.loadSky("SkyLevel"), env, preset.envType(), hour, skyZone.showSun(), cancelled);
                panorama = blend(panorama, shared, overlay);
            }
            return new LocationEnvironment(
                    this.hour,
                    toSun,
                    env.terrainSun(),
                    env.terrainAmbient(),
                    panorama,
                    fog == null ? null : fog.color(),
                    fog == null ? 0f : fog.start(),
                    fog == null ? 0f : fog.end(),
                    timeEnv.getFileName().toString(),
                    env.staticMeshSun(),
                    env.staticMeshAmbient(),
                    env.beastLightmap1(),
                    env.beastLightmap2(),
                    hour,
                    skyZone.hasTime() ? skyZone.timeZone().name() : null);
        }

        private @Nullable RgbaImage bakeSky(
                @Nullable USky sky,
                ResolvedEnv env,
                int envType,
                float hour,
                boolean showSun,
                BooleanSupplier cancelled) {
            RgbaImage panorama = null;
            if (sky != null) {
                var shells = SkyPanorama.collectShells(
                        sky,
                        (name, cloud, authored) -> SkyRules.tint(name, cloud, env.skyBox(), env.haze(), env, authored),
                        zoneState);
                var sprites = new ArrayList<Sprite>();
                var sun = showSun ? sky.sun() : null;
                if (sun != null) {
                    var radius = sun.radius() > 0 ? sun.radius() : 300f;
                    var offset = SkyRules.celestialOffset(hour, true, radius);
                    if (offset != null && offset.getZ() > 0) {
                        var size = SUN_SIZE * env.sunScale() * sun.drawScale();
                        sprites.add(new Sprite(
                                sun.sprite(), normalized(offset), 2 * Math.atan(size / 2 / radius), new float[] {
                                    env.sunColor().getX(),
                                    env.sunColor().getY(),
                                    env.sunColor().getZ()
                                }));
                    }
                }
                if (showSun && !sky.moons().isEmpty()) {
                    var moon = findMoon(sky, envType);
                    var radius = moon.radius() > 0 ? moon.radius() : 300f;
                    var offset = SkyRules.celestialOffset(hour, false, radius);
                    if (offset != null && offset.getZ() > 0) {
                        var size = MOON_SIZE * env.moonScale() * moon.drawScale();
                        sprites.add(new Sprite(
                                moon.sprite(), normalized(offset), 2 * Math.atan(size / 2 / radius), new float[] {
                                    1, 1, 1
                                }));
                    }
                }
                panorama = SkyPanorama.bake(shells, sprites, SKY_WIDTH, cancelled);
            }
            return panorama;
        }

        private boolean isCenter(UMap map) {
            try {
                return WorldTile.fromMapName(map.name()).equals(WorldTile.at(x, y));
            } catch (IllegalArgumentException notATile) {
                return false;
            }
        }

        private Vec3 point() {
            return new Vec3(x, y, Float.isNaN(ground) ? 0f : ground + 200f);
        }

        private @Nullable DistanceFog readFog(Path envInt, Vec3 point, float hour) {
            DistanceFog base = null;
            if (zone != null && zone.fog() != null && !zone.terrainZone()) {
                base = zone.fog();
            } else {
                try {
                    var ranges = FogRanges.parse(envInt);
                    if (ranges.isUsable()) {
                        var range = ranges.worldRange(FOG_PRESET, point.getZ());
                        var color =
                                zone != null && zone.fog() != null ? zone.fog().color() : OUTDOOR_FOG;
                        base = new DistanceFog(color, range.start(), range.end());
                    }
                } catch (RuntimeException unreadable) {
                    base = null;
                }
            }
            return L2FogInfo.blendFog(fogInfos, point, hour, FOG_PRESET, base);
        }

        private static RgbaImage blend(RgbaImage own, @Nullable RgbaImage shared, float weight) {
            if (shared == null || shared.width() != own.width() || shared.height() != own.height()) {
                return own;
            }
            var pixels = new byte[own.rgba().length];
            for (var index = 0; index < pixels.length; index++) {
                var under = own.rgba()[index] & 0xFF;
                var over = shared.rgba()[index] & 0xFF;
                pixels[index] = (byte) Math.round(under + (over - under) * weight);
            }
            return new RgbaImage(own.width(), own.height(), pixels);
        }

        private static Celestial findMoon(USky sky, int envType) {
            Celestial fallback = sky.moons().getFirst();
            for (var moon : sky.moons()) {
                if (moon.envType() == envType) {
                    return moon;
                }
                if (moon.envType() == 0) {
                    fallback = moon;
                }
            }
            return fallback;
        }
    }

    static float groundHeight(UMap map, float x, float y) {
        var best = Float.POSITIVE_INFINITY;
        var height = Float.NaN;
        for (var entity : map.entities()) {
            if (!(entity.getMesh() instanceof TerrainMesh terrain)) {
                continue;
            }
            var m = entity.getModelMatrix().to(new float[16]);
            var vertices = terrain.vertexBuffer();
            for (var index = 0; index < vertices.size(); index++) {
                var lx = vertices.getPositionX(index);
                var ly = vertices.getPositionY(index);
                var lz = vertices.getPositionZ(index);
                var wx = m[0] * lx + m[4] * ly + m[8] * lz + m[12];
                var wy = m[1] * lx + m[5] * ly + m[9] * lz + m[13];
                var distance = (wx - x) * (wx - x) + (wy - y) * (wy - y);
                if (distance < best) {
                    best = distance;
                    height = m[2] * lx + m[6] * ly + m[10] * lz + m[14];
                }
            }
        }
        return height;
    }

    static Vec3 legacySun(float hour) {
        var time = hour < 6f ? hour + 24.0 : hour;
        var latitude = (time - 6.0) * Math.toRadians(10.0) - Math.PI / 2.0;
        var x = -Math.sin(latitude);
        var z = Math.cos(latitude);
        var tilt = Math.toRadians(30.0);
        var y = -z * Math.sin(tilt);
        z *= Math.cos(tilt);
        return normalized(new Vec3((float) x, (float) y, (float) z));
    }

    float[] ambientLinear() {
        return linear(ambient);
    }

    float[] meshAmbientLinear() {
        return linear(meshAmbient);
    }

    float[] meshSunLinear() {
        return sunOver(meshAmbient, meshSun);
    }

    float[] sunLinear() {
        return new float[] {
            Math.max(0f, toLinear(ambient.getX() + sun.getX()) - toLinear(ambient.getX())),
            Math.max(0f, toLinear(ambient.getY() + sun.getY()) - toLinear(ambient.getY())),
            Math.max(0f, toLinear(ambient.getZ() + sun.getZ()) - toLinear(ambient.getZ()))
        };
    }

    float @Nullable [] fogLinear() {
        return fogColor == null
                ? null
                : new float[] {toLinear(fogColor.getX()), toLinear(fogColor.getY()), toLinear(fogColor.getZ())};
    }

    private static float[] linear(Vec3 color) {
        return new float[] {toLinear(color.getX()), toLinear(color.getY()), toLinear(color.getZ())};
    }

    private static float[] sunOver(Vec3 ambient, Vec3 sun) {
        return new float[] {
            Math.max(0f, toLinear(ambient.getX() + sun.getX()) - toLinear(ambient.getX())),
            Math.max(0f, toLinear(ambient.getY() + sun.getY()) - toLinear(ambient.getY())),
            Math.max(0f, toLinear(ambient.getZ() + sun.getZ()) - toLinear(ambient.getZ()))
        };
    }

    private static Vec3 normalized(Vec3 value) {
        var length = (float)
                Math.sqrt(value.getX() * value.getX() + value.getY() * value.getY() + value.getZ() * value.getZ());
        return length < 1e-8f
                ? new Vec3(0f, 0f, 1f)
                : new Vec3(value.getX() / length, value.getY() / length, value.getZ() / length);
    }
}
