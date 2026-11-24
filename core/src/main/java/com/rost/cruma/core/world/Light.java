package com.rost.cruma.core.world;

import glm_.vec3.Vec3;

public record Light(
        Vec3 position,
        Vec3 color,
        float radius,
        int lightEffect,
        Vec3 direction,
        int cone,
        int lightType,
        int period,
        int phase) {
    public static final int LT_PULSE = 2;
    public static final int LT_FLICKER = 4;

    private static final Vec3 DEFAULT_DIRECTION = new Vec3(1f, 0f, 0f);
    private static final int DEFAULT_CONE = 128;
    private static final int DEFAULT_LIGHT_TYPE = 1;
    private static final int DEFAULT_PERIOD = 32;
    private static final int DEFAULT_PHASE = 0;

    public Light(Vec3 position, Vec3 color, float radius) {
        this(
                position,
                color,
                radius,
                0,
                DEFAULT_DIRECTION,
                DEFAULT_CONE,
                DEFAULT_LIGHT_TYPE,
                DEFAULT_PERIOD,
                DEFAULT_PHASE);
    }

    public Light(Vec3 position, Vec3 color, float radius, int lightEffect) {
        this(
                position,
                color,
                radius,
                lightEffect,
                DEFAULT_DIRECTION,
                DEFAULT_CONE,
                DEFAULT_LIGHT_TYPE,
                DEFAULT_PERIOD,
                DEFAULT_PHASE);
    }

    public Light(Vec3 position, Vec3 color, float radius, int lightEffect, Vec3 direction, int cone) {
        this(position, color, radius, lightEffect, direction, cone, DEFAULT_LIGHT_TYPE, DEFAULT_PERIOD, DEFAULT_PHASE);
    }

    public boolean isAnimated() {
        return lightType == LT_PULSE || lightType == LT_FLICKER;
    }
}
