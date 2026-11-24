package com.rost.cruma.core.world;

import glm_.vec3.Vec3;

public final class NativeWaterReflection {
    private final float[] matrix;
    private final float[] inverse;

    public NativeWaterReflection(float x, float y, float z, float w) {
        var twiceX = x * 2f;
        var twiceY = y * 2f;
        var twiceZ = z * 2f;
        var basisX = y * 0f + x + z * 0f;
        var basisY = y + x * 0f + z * 0f;
        var basisZ = y * 0f + x * 0f + z;
        var m = new float[16];
        m[0] = 1f - twiceX * basisX;
        m[4] = 0f - twiceY * basisX;
        m[8] = 0f - twiceZ * basisX;
        m[1] = 0f - twiceX * basisY;
        m[5] = 1f - twiceY * basisY;
        m[9] = 0f - twiceZ * basisY;
        m[2] = 0f - twiceX * basisZ;
        m[6] = 0f - twiceY * basisZ;
        m[10] = 1f - twiceZ * basisZ;
        var tx = -(x * -2f * -w);
        var ty = -(y * -2f * -w);
        var tz = -(z * -2f * -w);
        m[12] = tx * m[0] + ty * m[4] + tz * m[8];
        m[13] = tx * m[1] + ty * m[5] + tz * m[9];
        m[14] = tx * m[2] + ty * m[6] + m[10] * tz;
        m[15] = 1f;
        matrix = m;
        inverse = NativeMatrixMath.inverse(m);
    }

    public float[] getMatrix() {
        return matrix.clone();
    }

    public float[] getInverseMatrix() {
        return inverse.clone();
    }

    public Vec3 reflect(Vec3 point) {
        var x = point.getX();
        var y = point.getY();
        var z = point.getZ();
        var reflectedX = inverse[0] * x + inverse[4] * y + inverse[8] * z + inverse[12];
        var reflectedY = inverse[1] * x + inverse[5] * y + inverse[9] * z + inverse[13];
        var reflectedZ = inverse[2] * x + inverse[6] * y + inverse[10] * z + inverse[14];
        return new Vec3(reflectedX, reflectedY, reflectedZ);
    }

    public void composeViewMatrix(float[] parent, float[] target) {
        if (parent.length != 16 || target.length != 16 || parent == target) {
            throw new IllegalArgumentException("reflection matrices require distinct arrays of 16 coefficients");
        }
        NativeMatrixMath.multiply(matrix, parent, target);
    }
}
