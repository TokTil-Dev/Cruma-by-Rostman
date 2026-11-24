package com.rost.cruma.core.world;

import com.rost.cruma.core.env.UnrealHsv;
import com.rost.cruma.core.unreal.UActor.EmitterActor;
import glm_.vec3.Vec3;
import org.jetbrains.annotations.Nullable;

public record EmitterLight(
        int lightType,
        float brightness,
        int hue,
        int saturation,
        int lightingType,
        float radius,
        float lifeSpan,
        float initialDelay,
        float speedRate) {
    public static final int BODIES = 0;

    public static final int WORLD = 1;

    private static final float EXPIRED = 0.0001f;
    private static final int PERIOD = 32;
    private static final int PHASE = 0;
    private static final float CLASS_LIFE_SPAN = 0f;

    public static @Nullable EmitterLight of(EmitterActor actor) {
        if (!actor.isUseLight()) {
            return null;
        }
        return new EmitterLight(
                actor.getLightType(),
                actor.getLightBrightness(),
                actor.getLightHue(),
                actor.getLightSaturation(),
                actor.getEmitterLightingType(),
                actor.getLightRadius(),
                actor.getLightLifeSpan(),
                actor.getLightInitialDelay(),
                actor.getSpeedRate());
    }

    public static EmitterLight adjusted(EmitterLight light, float seconds, float longest) {
        return new EmitterLight(
                light.lightType,
                light.brightness,
                light.hue,
                light.saturation,
                light.lightingType,
                light.radius,
                light.lifeSpan + (seconds - longest),
                light.initialDelay,
                light.speedRate);
    }

    public float getSpawnSeconds() {
        if (initialDelay == 0f) {
            return 0f;
        }
        if (!(initialDelay > 0f) || !(speedRate > 0f)) {
            return Float.POSITIVE_INFINITY;
        }
        return initialDelay / speedRate;
    }

    public boolean isLitAt(float seconds) {
        if (!(seconds > 0f)) {
            return false;
        }
        var spawn = getSpawnSeconds();
        if (!(seconds >= spawn)) {
            return false;
        }
        return lifeSpan == 0f || getRemaining(seconds) > EXPIRED;
    }

    public float getRemaining(float seconds) {
        return lifeSpan == 0f ? 0f : lifeSpan - (seconds - getSpawnSeconds());
    }

    public float computePower(float seconds, NativeLightTemporal temporal) {
        if (!isLitAt(seconds)) {
            return 0f;
        }
        var lived = seconds - getSpawnSeconds();
        var fadeRate = (float) (Math.PI / 2 / lifeSpan);
        var inputs = new NativeLightTemporal.Inputs(
                lightType, PERIOD, PHASE, getRemaining(seconds), CLASS_LIFE_SPAN, fadeRate, false, 0f, null);
        var phase = lightType == 11 ? fadeRate * lived : 0f;
        var power = temporal.update(inputs, new NativeLightTemporal.ActorState(phase), seconds, null)
                .power();
        return Float.isFinite(power) ? power : 0f;
    }

    public Vec3 computeColor(MaterialClient client) {
        return UnrealLoader.lightColor(hue, saturation, brightness, client);
    }

    public Vec3 computeNativeColor() {
        return UnrealHsv.toRgb(hue, saturation, 255).times(brightness / 255f);
    }

    public float getWorldRadius() {
        return NativeLightTemporal.worldRadius(lightType, radius);
    }

    public boolean isOnBodies() {
        return lightingType != WORLD;
    }

    public boolean isOnWorld() {
        return lightingType != BODIES;
    }
}
