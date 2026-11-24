package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.world.WaterVolume;
import glm_.vec4.Vec4;

final class UnderwaterColorTransition {
    private final Vec4 fallback;
    private WaterVolume volume;
    private WaterVolume previousVolume;
    private Vec4 oldEndpoint = new Vec4(0f);
    private Vec4 previousTarget = new Vec4(0f);
    private float fraction;

    UnderwaterColorTransition(Vec4 fallback) {
        this.fallback = new Vec4(fallback);
    }

    boolean admit(WaterVolume selected) {
        if (selected == null || !selected.isL2WaterVolume()) {
            reset();
            return false;
        }
        if (volume == null) {
            bindVolume(selected);
        }
        return true;
    }

    void bindVolume(WaterVolume selected) {
        volume = selected;
    }

    Vec4 render(float timeSinceLastFogChange) {
        var authored = volume == null ? null : volume.getCellophaneColor();
        var target = authored == null ? fallback : authored;
        Vec4 result;
        if (volume != null && previousVolume != null && previousVolume.isL2WaterVolume()) {
            if (previousVolume != volume) {
                if (fraction >= 1f) {
                    fraction = 0f;
                } else {
                    fraction = 1f - fraction;
                    oldEndpoint = new Vec4(previousTarget);
                }
                previousTarget = new Vec4(target);
            }
            fraction = timeSinceLastFogChange * 0.5f;
            if (fraction > 1f) {
                oldEndpoint = new Vec4(target);
                fraction = 1f;
            }
            result = new Vec4(
                    interpolateChannel(oldEndpoint.getX(), target.getX(), fraction),
                    interpolateChannel(oldEndpoint.getY(), target.getY(), fraction),
                    interpolateChannel(oldEndpoint.getZ(), target.getZ(), fraction),
                    interpolateChannel(oldEndpoint.getW(), target.getW(), fraction));
        } else {
            result = new Vec4(target);
        }
        previousVolume = volume;
        return result;
    }

    void reset() {
        fraction = 0f;
        previousVolume = null;
        volume = null;
    }

    private static float interpolateChannel(float oldValue, float target, float fraction) {
        var oldByte = Math.round(oldValue * 255f);
        var newByte = Math.round(target * 255f);
        return ((int) (oldByte + fraction * (newByte - oldByte)) & 0xff) / 255f;
    }
}
