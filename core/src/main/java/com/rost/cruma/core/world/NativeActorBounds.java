package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.primitives.UBox;
import glm_.mat4x4.Mat4;
import glm_.vec3.Vec3;

public final class NativeActorBounds {
    private NativeActorBounds() {}

    public static UBox transformBox(UBox box, Mat4 matrix) {
        var m = matrix.to(new float[16]);
        var min = new float[3];
        var max = new float[3];
        for (var corner = 0; corner < 8; corner++) {
            var x = (corner & 4) == 0 ? box.min().getX() : box.max().getX();
            var y = (corner & 2) == 0 ? box.min().getY() : box.max().getY();
            var z = (corner & 1) == 0 ? box.min().getZ() : box.max().getZ();
            for (var axis = 0; axis < 3; axis++) {
                var value = (m[axis + 8] * z + (m[axis] * x + m[axis + 4] * y)) + m[axis + 12];
                if (corner == 0) {
                    min[axis] = max[axis] = value;
                } else {
                    if (!(value >= min[axis])) {
                        min[axis] = value;
                    }
                    if (!(max[axis] >= value)) {
                        max[axis] = value;
                    }
                }
            }
        }
        return new UBox(new Vec3(min[0], min[1], min[2]), new Vec3(max[0], max[1], max[2]), true);
    }
}
