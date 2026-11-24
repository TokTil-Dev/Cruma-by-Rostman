package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.primitives.UBox;
import glm_.vec3.Vec3;

public final class NativeActorDrawDistance {
    private NativeActorDrawDistance() {}

    public static float squaredDistance(UBox bounds, Vec3 origin) {
        var result = 0f;
        for (var axis = 0; axis < 3; axis++) {
            var value = origin.get(axis);
            if (bounds.min().get(axis) > value) {
                var delta = value - bounds.min().get(axis);
                result = delta * delta + result;
            } else if (value > bounds.max().get(axis)) {
                var delta = value - bounds.max().get(axis);
                result = delta * delta + result;
            }
        }
        return result;
    }

    public static boolean isVisible(UBox bounds, Vec3 origin, float cullDistance, float fovDegrees, int precaching) {
        if (cullDistance == 0f || precaching == 1) {
            return true;
        }
        var scaledDistance = scaledSquaredDistance(bounds, origin, fovDegrees);
        var limit = cullDistance * cullDistance;
        return cullDistance > 0f ? scaledDistance <= limit : -limit <= scaledDistance;
    }

    public static float scaledSquaredDistance(UBox bounds, Vec3 origin, float fovDegrees) {
        var tangent = Math.tan((double) fovDegrees * 0.008726646259971648);
        return (float) ((double) squaredDistance(bounds, origin) * (tangent * tangent));
    }
}
