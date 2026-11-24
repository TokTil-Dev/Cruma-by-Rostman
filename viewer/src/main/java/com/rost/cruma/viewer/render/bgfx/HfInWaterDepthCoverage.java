package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.viewer.render.WaterRenderRoute;
import glm_.vec4.Vec4;

final class HfInWaterDepthCoverage {
    private HfInWaterDepthCoverage() {}

    static float planeWorldZ(float authoredPlaneZ, float authoredPlaneW, int route) {
        var worldZ = authoredPlaneZ * authoredPlaneW;
        return route == WaterRenderRoute.PS19 ? worldZ : worldZ - 4f;
    }

    static Vec4 reflectionClipPlane(float planeWorldZ, Vec4 target) {
        target.setX(0f);
        target.setY(0f);
        target.setZ(1f);
        target.setW(-planeWorldZ);
        return target;
    }

    static float smoothCoverage(float planeWorldZ, float worldZ) {
        if (planeWorldZ == 0f) {
            return 1f;
        }
        return 1f - saturate(Math.max(planeWorldZ - worldZ, 0f) / 10f);
    }

    static float sharpCoverage(float planeWorldZ, float worldZ, float materialAlpha) {
        if (planeWorldZ == 0f) {
            return materialAlpha;
        }
        return materialAlpha * (1f - saturate(Math.max(planeWorldZ - worldZ, 0f) * 10_000f));
    }

    private static float saturate(float value) {
        return Math.max(0f, Math.min(value, 1f));
    }
}
