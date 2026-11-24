package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.env.EnvSetupParser;
import com.rost.cruma.core.env.EnvironmentSun;
import com.rost.cruma.core.env.FogRanges;
import com.rost.cruma.core.env.TimeEnvParser;
import com.rost.cruma.core.env.WorldTimeEnv;
import com.rost.cruma.core.world.MaterialClient;
import glm_.vec3.Vec3;
import java.nio.file.Path;
import org.jetbrains.annotations.Nullable;

public record TileFlightLight(
        float[] sunDirection,
        float[] meshSun,
        float[] meshAmbient,
        float[] groundSun,
        float[] groundAmbient,
        float[] fog,
        float fogStart,
        float fogEnd,
        float[] water,
        float[] sky,
        float[] haze) {
    private static final float VIEW_DISTANCE = 48_000;
    private static final float OUTDOOR_FOG = 128f / 255f;
    private static final float[] WATER = {38 / 255f, 56 / 255f, 64 / 255f, 0};
    private static final float REFLECTION = .6f;

    public static @Nullable TileFlightLight of(Path client, float hour, float height) {
        try {
            var files = WorldTimeEnv.files(client);
            var envInt = client.resolve("system").resolve("env.int");
            if (files.isEmpty() || !envInt.toFile().isFile()) {
                return null;
            }
            var kind = MaterialClient.detect(client);
            var env = TimeEnvParser.parse(files.getFirst(), kind).resolve(hour, kind);
            var slope = kind == MaterialClient.UNKNOWN
                    ? 0f
                    : EnvSetupParser.parse(envInt).slopeSunAngle();
            var sun = kind == MaterialClient.UNKNOWN
                    ? BgfxFrameEnvironment.sunDirection(hour)
                    : EnvironmentSun.direction(hour, slope);
            var ranges = FogRanges.parse(envInt);
            float start = 0, end = 0;
            if (ranges.isUsable()) {
                var range = BgfxFrameEnvironment.outdoorRange(ranges, 0, VIEW_DISTANCE, height);
                start = range.start();
                end = range.end();
            }
            var day = Math.clamp(Math.max(env.sunScale(), sun.getZ() * 2), 0f, 1f);
            var haze = four(env.haze());
            float[] water = new float[4];
            for (var channel = 0; channel < 3; channel++) {
                water[channel] = WATER[channel] * day + (haze[channel] - WATER[channel] * day) * REFLECTION;
            }
            return new TileFlightLight(
                    new float[] {sun.getX(), sun.getY(), sun.getZ(), 0},
                    four(env.staticMeshSun()),
                    four(env.staticMeshAmbient()),
                    four(env.terrainSun()),
                    four(env.terrainAmbient()),
                    new float[] {OUTDOOR_FOG, OUTDOOR_FOG, OUTDOOR_FOG, 0},
                    start,
                    end,
                    water,
                    four(env.skyBox()),
                    haze);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static float[] four(Vec3 color) {
        return new float[] {color.getX(), color.getY(), color.getZ(), 0};
    }
}
