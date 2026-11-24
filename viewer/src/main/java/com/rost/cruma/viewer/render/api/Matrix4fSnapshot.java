package com.rost.cruma.viewer.render.api;

import java.nio.FloatBuffer;

public record Matrix4fSnapshot(
        float m00,
        float m01,
        float m02,
        float m03,
        float m10,
        float m11,
        float m12,
        float m13,
        float m20,
        float m21,
        float m22,
        float m23,
        float m30,
        float m31,
        float m32,
        float m33) {
    public static final Matrix4fSnapshot IDENTITY =
            new Matrix4fSnapshot(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f);

    public static Matrix4fSnapshot copyOf(FloatBuffer source) {
        if (source == null || source.remaining() < 16) {
            throw new IllegalArgumentException("matrix source must contain at least 16 floats");
        }
        var offset = source.position();
        return new Matrix4fSnapshot(
                source.get(offset),
                source.get(offset + 1),
                source.get(offset + 2),
                source.get(offset + 3),
                source.get(offset + 4),
                source.get(offset + 5),
                source.get(offset + 6),
                source.get(offset + 7),
                source.get(offset + 8),
                source.get(offset + 9),
                source.get(offset + 10),
                source.get(offset + 11),
                source.get(offset + 12),
                source.get(offset + 13),
                source.get(offset + 14),
                source.get(offset + 15));
    }

    public void put(float[] target, int offset) {
        if (target == null || offset < 0 || target.length - offset < 16) {
            throw new IllegalArgumentException("matrix destination is too small");
        }
        target[offset] = m00;
        target[offset + 1] = m01;
        target[offset + 2] = m02;
        target[offset + 3] = m03;
        target[offset + 4] = m10;
        target[offset + 5] = m11;
        target[offset + 6] = m12;
        target[offset + 7] = m13;
        target[offset + 8] = m20;
        target[offset + 9] = m21;
        target[offset + 10] = m22;
        target[offset + 11] = m23;
        target[offset + 12] = m30;
        target[offset + 13] = m31;
        target[offset + 14] = m32;
        target[offset + 15] = m33;
    }
}
