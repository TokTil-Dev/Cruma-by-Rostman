package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.world.Entity.DistanceFog;
import com.rost.cruma.core.world.WaterVolume;
import glm_.vec3.Vec3;

final class HfWaterVolumeFogTransition {
    private static final float RATE = 0.5f;

    private WaterVolume previousVolume;
    private DistanceFog oldEndpoint = zeroFog();
    private DistanceFog targetEndpoint = zeroFog();
    private float elapsed;
    private float fraction;

    static DistanceFog interpolate(DistanceFog oldFog, DistanceFog newFog, float fraction) {
        var p = Math.max(0f, Math.min(1f, fraction));
        var oldColor = oldFog.color();
        var newColor = newFog.color();
        return new DistanceFog(
                new Vec3(
                        interpolateByte(oldColor.getX(), newColor.getX(), p),
                        interpolateByte(oldColor.getY(), newColor.getY(), p),
                        interpolateByte(oldColor.getZ(), newColor.getZ(), p)),
                lerp(oldFog.start(), newFog.start(), p),
                lerp(oldFog.end(), newFog.end(), p));
    }

    void tick(float deltaSeconds) {
        elapsed += finiteDelta(deltaSeconds);
    }

    DistanceFog update(WaterVolume volume, DistanceFog target, float deltaSeconds) {
        tick(deltaSeconds);
        return update(volume, target);
    }

    DistanceFog update(WaterVolume volume, DistanceFog target) {
        if (volume == null || target == null) {
            throw new IllegalArgumentException("an active distance-fog volume and target are required");
        }
        var safeTarget = copy(target);
        if (previousVolume == null || !previousVolume.isL2WaterVolume()) {
            previousVolume = volume;
            return volume.getFogSettings().ownValues();
        }

        if (previousVolume != volume) {
            if (fraction >= 1f) {
                elapsed = 0f;
                fraction = 0f;
            } else {
                oldEndpoint = copy(targetEndpoint);
                fraction = 1f - fraction;
                elapsed = fraction / RATE;
            }
            targetEndpoint = copy(safeTarget);
        }

        fraction = elapsed * RATE;
        if (fraction > 1f) {
            fraction = 1f;
            oldEndpoint = copy(safeTarget);
            elapsed = 1_000_000f;
        }
        previousVolume = volume;
        return interpolate(oldEndpoint, safeTarget, fraction);
    }

    void observeInactive(WaterVolume volume) {
        previousVolume = volume;
    }

    float getTimeSinceLastFogChange() {
        return elapsed;
    }

    void reset() {
        previousVolume = null;
        oldEndpoint = zeroFog();
        targetEndpoint = zeroFog();
        elapsed = 0f;
        fraction = 0f;
    }

    private static float interpolateByte(float oldValue, float newValue, float fraction) {
        var oldByte = clampByte(Math.round(oldValue * 255f));
        var newByte = clampByte(Math.round(newValue * 255f));
        return clampByte((int) (oldByte + fraction * (newByte - oldByte))) / 255f;
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static float lerp(float oldValue, float newValue, float fraction) {
        return oldValue + fraction * (newValue - oldValue);
    }

    private static float finiteDelta(float deltaSeconds) {
        return Float.isFinite(deltaSeconds) && deltaSeconds > 0f ? deltaSeconds : 0f;
    }

    private static DistanceFog copy(DistanceFog fog) {
        return new DistanceFog(new Vec3(fog.color()), fog.start(), fog.end());
    }

    private static DistanceFog zeroFog() {
        return new DistanceFog(new Vec3(0f), 0f, 0f);
    }
}
