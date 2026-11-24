package com.rost.cruma.core.world;

import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.util.ArrayList;
import java.util.List;

public final class NativeViewFrustum {
    private NativeViewFrustum() {}

    public static List<Vec4> planes(
            int renderMode, float nearClip, float[] worldToScreen, float[] screenToWorld, Vec3 viewOrigin) {
        if (worldToScreen.length != 16 || screenToWorld.length != 16) {
            throw new IllegalArgumentException("native scene matrices require 16 coefficients");
        }
        var positive = BrushTransforms.determinant(worldToScreen) >= 0f;
        var orthographic = renderMode >= 13 && renderMode <= 17 || renderMode == 35 || renderMode == 36;
        var corners = new Vec3[orthographic ? 8 : 4];
        var index = 0;
        for (var x : new float[] {-1f, 1f}) {
            for (var y : new float[] {-1f, 1f}) {
                if (orthographic) {
                    corners[index++] = transform(screenToWorld, x, y, -1f, 1f);
                    corners[index++] = transform(screenToWorld, x, y, 1f, 1f);
                } else {
                    corners[index++] = transform(screenToWorld, x * nearClip, y * nearClip, 0f * nearClip, nearClip);
                }
            }
        }
        var result = new ArrayList<Vec4>(4);
        if (orthographic) {
            var triples = new int[][] {{0, 5, 1}, {4, 7, 5}, {6, 3, 7}, {2, 1, 3}};
            for (var triple : triples) {
                var base = corners[triple[0]];
                var a = corners[triple[positive ? 1 : 2]].minus(base);
                var b = corners[triple[positive ? 2 : 1]].minus(base);
                result.add(planeThrough(base, a, b));
            }
        } else {
            var pairs = new int[][] {{0, 1}, {2, 0}, {3, 2}, {1, 3}};
            for (var pair : pairs) {
                var a = corners[pair[positive ? 0 : 1]].minus(viewOrigin);
                var b = corners[pair[positive ? 1 : 0]].minus(viewOrigin);
                result.add(planeThrough(viewOrigin, a, b));
            }
        }
        return List.copyOf(result);
    }

    private static Vec3 transform(float[] m, float x, float y, float z, float w) {
        return new Vec3(
                (m[0] * x + m[4] * y) + m[8] * z + m[12] * w,
                (m[5] * y + m[1] * x) + m[9] * z + m[13] * w,
                (m[6] * y + m[2] * x) + m[10] * z + m[14] * w);
    }

    private static Vec4 planeThrough(Vec3 base, Vec3 a, Vec3 b) {
        var x = a.getY() * b.getZ() - a.getZ() * b.getY();
        var y = a.getZ() * b.getX() - a.getX() * b.getZ();
        var z = a.getX() * b.getY() - a.getY() * b.getX();
        var square = x * x + y * y + z * z;
        if (square < 1.0e-8) {
            x = y = z = 0f;
        } else {
            var inverse = 1f / (float) Math.sqrt(square);
            x *= inverse;
            y *= inverse;
            z *= inverse;
        }
        return new Vec4(x, y, z, y * base.getY() + base.getX() * x + z * base.getZ());
    }
}
