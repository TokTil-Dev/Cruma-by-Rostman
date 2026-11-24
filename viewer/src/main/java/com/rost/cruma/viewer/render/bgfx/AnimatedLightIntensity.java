package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.world.Light;
import com.rost.cruma.core.world.LightPulse;
import com.rost.cruma.core.world.MaterialClient;
import com.rost.cruma.core.world.NativeLightTemporal;

final class AnimatedLightIntensity {
    private static final double UNREAL_ANGLE_UNITS = 65_536.0;
    private static final double PULSE_TIME_SCALE = 35.0;
    private static final int SIN_TABLE_MASK = 0x3fff;
    private static final double SIN_TABLE_RADIANS = Math.PI * 2.0 / 16_384.0;
    private static final int RAND_MULTIPLIER = 214_013;
    private static final int RAND_INCREMENT = 2_531_011;
    private static final float RAND_DENOMINATOR = 32_767f;

    private int randomSeed;

    AnimatedLightIntensity() {
        this(0);
    }

    AnimatedLightIntensity(int randomSeed) {
        this.randomSeed = randomSeed;
    }

    static float pulse(float levelTimeSeconds, int period, int phase) {
        var clientPeriod = Math.max(period, 1);
        var unrealAngle = (double) levelTimeSeconds * PULSE_TIME_SCALE * UNREAL_ANGLE_UNITS / clientPeriod
                + ((phase & 0xff) << 8);
        var quantizedAngle = (int) (long) Math.rint(unrealAngle);
        var tableIndex = (quantizedAngle >> 2) & SIN_TABLE_MASK;
        var sine = (float) Math.sin(tableIndex * SIN_TABLE_RADIANS);
        return (float) ((double) sine * 0.39 + 0.60);
    }

    float sample(Light light, float levelTimeSeconds) {
        return sample(light, levelTimeSeconds, MaterialClient.UNKNOWN);
    }

    float sample(Light light, float levelTimeSeconds, MaterialClient client) {
        return switch (light.lightType()) {
            case Light.LT_PULSE ->
                switch (client) {
                    case P152, P162 -> LightPulse.sample(levelTimeSeconds, light.period(), light.phase());
                    case UNKNOWN -> pulse(levelTimeSeconds, light.period(), light.phase());
                };
            case Light.LT_FLICKER -> flicker();
            default -> 1f;
        };
    }

    void reset() {
        randomSeed = 0;
    }

    private float flicker() {
        randomSeed = randomSeed * RAND_MULTIPLIER + RAND_INCREMENT;
        var random = (randomSeed >>> 16) & 0x7fff;
        var intensity = (float) ((double) random / RAND_DENOMINATOR);
        return NativeLightTemporal.flicker(intensity);
    }
}
