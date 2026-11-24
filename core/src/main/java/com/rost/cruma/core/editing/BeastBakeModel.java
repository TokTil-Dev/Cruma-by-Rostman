package com.rost.cruma.core.editing;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

public record BeastBakeModel(
        float[] sunDirection,
        float[] sun,
        float[] maskedSun,
        float[] sky,
        float[] litBounce,
        float[] darkBounce,
        float[] local,
        int falloff,
        boolean localShadows,
        float occlusionDistance,
        float gamma,
        float[] limit) {
    public static final int FALLOFFS = 5;

    static final float[] NO_LIMIT = {255f, 255f, 255f, 255f, 255f, 255f};

    public BeastBakeModel(
            float[] sunDirection,
            float[] sun,
            float[] maskedSun,
            float[] sky,
            float[] litBounce,
            float[] darkBounce,
            float[] local,
            int falloff,
            boolean localShadows,
            float occlusionDistance,
            float gamma) {
        this(
                sunDirection,
                sun,
                maskedSun,
                sky,
                litBounce,
                darkBounce,
                local,
                falloff,
                localShadows,
                occlusionDistance,
                gamma,
                NO_LIMIT);
    }

    public BeastBakeModel {
        sunDirection = normalizeDirection(sunDirection);
        sun = requireColor(sun);
        maskedSun = requireColor(maskedSun);
        sky = requireColor(sky);
        litBounce = requireColor(litBounce);
        darkBounce = requireColor(darkBounce);
        local = requireColor(local);
        if (limit.length != 6) {
            throw new IllegalArgumentException("A Beast bake limit needs six channels");
        }
        limit = limit.clone();
        if (falloff < 0 || falloff >= FALLOFFS || !(occlusionDistance > 0) || !(gamma >= 1f && gamma <= 3f)) {
            throw new IllegalArgumentException("Invalid Beast bake model");
        }
    }

    public static BeastBakeModel uncalibrated(float[] sunDirection, float[] sunColor) {
        var sun = new float[3];
        var sky = new float[3];
        for (var c = 0; c < 3; c++) {
            sun[c] = Math.min(255f, sunColor[c] * 255f);
            sky[c] = sun[c] * .25f;
        }
        return new BeastBakeModel(
                sunDirection,
                sun,
                new float[3],
                sky,
                new float[3],
                new float[3],
                new float[] {255f, 255f, 255f},
                0,
                true,
                2048f,
                1f);
    }

    public static float falloff(int shape, float a) {
        if (!(a < 1f) || a < 0f) {
            return 0f;
        }
        return switch (shape) {
            case 0 -> a * a * (2f * a - 3f) + 1f;
            case 1 -> 1f - a;
            case 2 -> (1f - a) * (1f - a);
            case 3 -> (1f - a * a) * (1f - a * a);
            default -> (1f - a * a) * (1f - a * a) / (1f + 25f * a * a);
        };
    }

    public BeastBakeModel withSunDirection(float[] direction) {
        return new BeastBakeModel(
                direction,
                sun,
                maskedSun,
                sky,
                litBounce,
                darkBounce,
                local,
                falloff,
                localShadows,
                occlusionDistance,
                gamma,
                limit);
    }

    public BeastBakeModel withLimit(float[] value) {
        return new BeastBakeModel(
                sunDirection,
                sun,
                maskedSun,
                sky,
                litBounce,
                darkBounce,
                local,
                falloff,
                localShadows,
                occlusionDistance,
                gamma,
                value);
    }

    @Override
    public float[] limit() {
        return limit.clone();
    }

    float limit(int slot, int channel) {
        return limit[slot * 3 + channel];
    }

    @Override
    public float[] sunDirection() {
        return sunDirection.clone();
    }

    @Override
    public float[] sun() {
        return sun.clone();
    }

    @Override
    public float[] maskedSun() {
        return maskedSun.clone();
    }

    @Override
    public float[] sky() {
        return sky.clone();
    }

    @Override
    public float[] litBounce() {
        return litBounce.clone();
    }

    @Override
    public float[] darkBounce() {
        return darkBounce.clone();
    }

    @Override
    public float[] local() {
        return local.clone();
    }

    float sun(int channel) {
        return sun[channel];
    }

    float maskedSun(int channel) {
        return maskedSun[channel];
    }

    float sky(int channel) {
        return sky[channel];
    }

    float litBounce(int channel) {
        return litBounce[channel];
    }

    float darkBounce(int channel) {
        return darkBounce[channel];
    }

    float local(int channel) {
        return local[channel];
    }

    public float sunAzimuth() {
        var degrees = (float) Math.toDegrees(Math.atan2(sunDirection[1], sunDirection[0]));
        return degrees < 0 ? degrees + 360f : degrees;
    }

    public float sunElevation() {
        return (float) Math.toDegrees(Math.asin(Math.clamp(sunDirection[2], -1f, 1f)));
    }

    public String describe() {
        return String.format(
                Locale.ROOT,
                "sun az %.1f el %.1f, sun %s, sun through cut-outs %s, sky %s, lit bounce %s, dark bounce %s, local"
                        + " %s, falloff %d%s, occlusion %.0f, gamma %.1f",
                sunAzimuth(),
                sunElevation(),
                toText(sun),
                toText(maskedSun),
                toText(sky),
                toText(litBounce),
                toText(darkBounce),
                toText(local),
                falloff,
                localShadows ? " with shadows" : " without shadows",
                occlusionDistance,
                gamma);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BeastBakeModel model
                && Arrays.equals(sunDirection, model.sunDirection)
                && Arrays.equals(sun, model.sun)
                && Arrays.equals(maskedSun, model.maskedSun)
                && Arrays.equals(sky, model.sky)
                && Arrays.equals(litBounce, model.litBounce)
                && Arrays.equals(darkBounce, model.darkBounce)
                && Arrays.equals(local, model.local)
                && falloff == model.falloff
                && localShadows == model.localShadows
                && Float.compare(occlusionDistance, model.occlusionDistance) == 0
                && Float.compare(gamma, model.gamma) == 0
                && Arrays.equals(limit, model.limit);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(sunDirection), Arrays.hashCode(sun), falloff, gamma);
    }

    private static String toText(float[] value) {
        return String.format(Locale.ROOT, "(%.0f %.0f %.0f)", value[0], value[1], value[2]);
    }

    private static float[] requireColor(float[] value) {
        if (value.length != 3) {
            throw new IllegalArgumentException("A colour needs three channels");
        }
        for (var channel : value) {
            if (!Float.isFinite(channel)) {
                throw new IllegalArgumentException("A colour channel is not finite");
            }
        }
        return value.clone();
    }

    private static float[] normalizeDirection(float[] value) {
        if (value.length != 3) {
            throw new IllegalArgumentException("A direction needs three components");
        }
        var length = (float) Math.sqrt(value[0] * value[0] + value[1] * value[1] + value[2] * value[2]);
        if (!(length > 1e-6f) || !Float.isFinite(length)) {
            throw new IllegalArgumentException("Invalid sun direction");
        }
        return new float[] {value[0] / length, value[1] / length, value[2] / length};
    }
}
