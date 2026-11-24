package com.rost.cruma.viewer.render.bgfx;

import java.nio.FloatBuffer;

final class BgfxMatrices {
    private BgfxMatrices() {}

    static FloatBuffer firstSixteenFloats(FloatBuffer source) {
        var duplicate = source.duplicate();
        duplicate.position(0);
        duplicate.limit(16);
        return duplicate;
    }

    static FloatBuffer toBackendProjection(FloatBuffer source, boolean homogeneousDepth, FloatBuffer target) {
        if (target == null || target.capacity() < 16) {
            throw new IllegalArgumentException("projection target requires sixteen floats");
        }
        var input = firstSixteenFloats(source);
        target.clear();
        target.put(input);
        adaptProjection(target, homogeneousDepth);
        target.position(0);
        target.limit(16);
        return target;
    }

    static float[] toBackendProjection(FloatBuffer source, boolean homogeneousDepth, float[] target) {
        if (target == null || target.length < 16) {
            throw new IllegalArgumentException("projection target requires sixteen floats");
        }
        var input = firstSixteenFloats(source);
        input.get(target, 0, 16);
        adaptProjection(target, homogeneousDepth);
        return target;
    }

    static void adaptProjection(float[] matrix, boolean homogeneousDepth) {
        if (!homogeneousDepth) {
            return;
        }
        for (var column = 0; column < 4; column++) {
            var z = column * 4 + 2;
            var w = column * 4 + 3;
            matrix[z] = 2f * matrix[z] - matrix[w];
        }
    }

    private static void adaptProjection(FloatBuffer matrix, boolean homogeneousDepth) {
        if (!homogeneousDepth) {
            return;
        }
        for (var column = 0; column < 4; column++) {
            var z = column * 4 + 2;
            var w = column * 4 + 3;
            matrix.put(z, 2f * matrix.get(z) - matrix.get(w));
        }
    }
}
