package com.rost.cruma.core.world;

final class NativeMatrixMath {
    private NativeMatrixMath() {}

    static void multiply(float[] left, float[] right, float[] target) {
        for (var row = 0; row < 4; row++) {
            for (var column = 0; column < 4; column++) {
                target[row * 4 + column] = left[row * 4] * right[column]
                        + left[row * 4 + 1] * right[4 + column]
                        + left[row * 4 + 2] * right[8 + column]
                        + left[row * 4 + 3] * right[12 + column];
            }
        }
    }

    static float[] inverse(float[] m) {
        var a = m[10] * m[15] - m[11] * m[14];
        var b = m[6] * m[15] - m[7] * m[14];
        var c = m[6] * m[11] - m[7] * m[10];
        var d = m[2] * m[15] - m[3] * m[14];
        var e = m[2] * m[11] - m[3] * m[10];
        var f = m[2] * m[7] - m[3] * m[6];
        var v0 = m[5] * a - m[9] * b + m[13] * c;
        var v1 = m[1] * a - d * m[9] + e * m[13];
        var v2 = m[1] * b - d * m[5] + f * m[13];
        var v3 = m[1] * c - e * m[5] + f * m[9];
        var determinant = v0 * m[0] - v1 * m[4] + v2 * m[8] - v3 * m[12];
        if (determinant == 0f) {
            return new float[] {1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f};
        }
        var reciprocal = 1f / determinant;
        var g = m[9] * m[15] - m[13] * m[11];
        var h = m[5] * m[15] - m[13] * m[7];
        var i = m[5] * m[11] - m[9] * m[7];
        var j = m[1] * m[15] - m[3] * m[13];
        var k = m[1] * m[11] - m[3] * m[9];
        var l = m[1] * m[7] - m[3] * m[5];
        var n = m[9] * m[14] - m[13] * m[10];
        var o = m[5] * m[14] - m[13] * m[6];
        var p = m[5] * m[10] - m[9] * m[6];
        var q = m[1] * m[14] - m[2] * m[13];
        var r = m[1] * m[10] - m[2] * m[9];
        var s = m[1] * m[6] - m[2] * m[5];
        return new float[] {
            v0 * reciprocal,
            -(v1 * reciprocal),
            v2 * reciprocal,
            -(v3 * reciprocal),
            -((m[4] * a - m[8] * b + m[12] * c) * reciprocal),
            (m[0] * a - m[8] * d + m[12] * e) * reciprocal,
            -((m[0] * b - m[4] * d + m[12] * f) * reciprocal),
            (m[0] * c - m[4] * e + m[8] * f) * reciprocal,
            (g * m[4] - h * m[8] + i * m[12]) * reciprocal,
            -((g * m[0] - j * m[8] + k * m[12]) * reciprocal),
            (h * m[0] - j * m[4] + l * m[12]) * reciprocal,
            -((i * m[0] - k * m[4] + l * m[8]) * reciprocal),
            -((m[4] * n - m[8] * o + m[12] * p) * reciprocal),
            (m[0] * n - m[8] * q + m[12] * r) * reciprocal,
            -((m[0] * o - m[4] * q + m[12] * s) * reciprocal),
            (m[0] * p - m[4] * r + m[8] * s) * reciprocal
        };
    }
}
