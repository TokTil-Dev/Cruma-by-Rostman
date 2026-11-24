package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.primitives.URotator;
import glm_.mat4x4.Mat4;
import glm_.vec3.Vec3;

public final class BrushTransforms {
    private static final float[] SINES = sineTable();

    private BrushTransforms() {}

    public static Mat4 actorMatrix(Vec3 location, Vec3 pivot, URotator rotation, Vec3 scale) {
        var sr = SINES[(rotation.roll() >> 2) & 0x3fff];
        var sp = SINES[(rotation.pitch() >> 2) & 0x3fff];
        var sy = SINES[(rotation.yaw() >> 2) & 0x3fff];
        var cr = SINES[((rotation.roll() + 16384) >> 2) & 0x3fff];
        var cp = SINES[((rotation.pitch() + 16384) >> 2) & 0x3fff];
        var cy = SINES[((rotation.yaw() + 16384) >> 2) & 0x3fff];
        var sx = scale.getX();
        var ys = scale.getY();
        var zs = scale.getZ();
        var px = pivot.getX();
        var py = pivot.getY();
        var pz = pivot.getZ();
        var cycr = cy * cr;
        var m00 = cy * cp * sx;
        var m22 = cp * cr * zs;
        var tx = ((location.getX() - m00 * px + cycr * zs * pz * sp) - ys * cy * py * sp * sr)
                + ys * cr * py * sy
                + zs * pz * sr * sy;
        var ty = location.getY()
                - (((zs * cy * pz * sr + cycr * ys * py + sx * cp * px * sy) - zs * cr * pz * sp * sy)
                        + ys * py * sp * sr * sy);
        var tz = location.getZ() - ((sx * px * sp + m22 * pz) - ys * cp * py * sr);
        return new Mat4(
                m00,
                sx * cp * sy,
                sx * sp,
                0f,
                (cy * sp * sr - cr * sy) * ys,
                (sp * sr * sy + cycr) * ys,
                -(ys * cp * sr),
                0f,
                -((cycr * sp + sy * sr) * zs),
                (cy * sr - cr * sp * sy) * zs,
                m22,
                0f,
                tx,
                ty,
                tz,
                1f);
    }

    static float[] worldPlanes(Mat4 brushToWorld, float[] planes) {
        var m = new float[16];
        for (var i = 0; i < m.length; i++) {
            m[i] = brushToWorld.get(i / 4, i % 4);
        }
        var a00 = m[5] * m[10] - m[6] * m[9];
        var a01 = m[8] * m[6] - m[4] * m[10];
        var a02 = m[4] * m[9] - m[8] * m[5];
        var a10 = m[2] * m[9] - m[1] * m[10];
        var a11 = m[0] * m[10] - m[2] * m[8];
        var a12 = m[1] * m[8] - m[0] * m[9];
        var a20 = m[1] * m[6] - m[2] * m[5];
        var a21 = m[2] * m[4] - m[0] * m[6];
        var a22 = m[0] * m[5] - m[1] * m[4];
        var reflected = determinant(m) < 0f;
        var result = new float[planes.length];
        for (var i = 0; i < planes.length; i += 4) {
            var x = planes[i];
            var y = planes[i + 1];
            var z = planes[i + 2];
            var w = planes[i + 3];
            var nx = a00 * x + a10 * y + a20 * z + 0f;
            var ny = a01 * x + a11 * y + a21 * z + 0f;
            var nz = a02 * x + a12 * y + a22 * z + 0f;
            var square = nx * nx + ny * ny + nz * nz;
            if (square < 1.0e-8) {
                nx = ny = nz = 0f;
            } else {
                var reciprocal = 1f / (float) Math.sqrt(square);
                nx *= reciprocal;
                ny *= reciprocal;
                nz *= reciprocal;
            }
            if (reflected) {
                nx *= -1f;
                ny *= -1f;
                nz *= -1f;
            }
            var px = x * w;
            var py = y * w;
            var pz = z * w;
            var wx = m[0] * px + m[4] * py + m[8] * pz + m[12];
            var wy = m[1] * px + m[5] * py + m[9] * pz + m[13];
            var wz = m[2] * px + m[6] * py + m[10] * pz + m[14];
            result[i] = nx;
            result[i + 1] = ny;
            result[i + 2] = nz;
            result[i + 3] = nx * wx + wy * ny + wz * nz;
        }
        return result;
    }

    static float determinant(float[] m) {
        var a = m[10] * m[15] - m[11] * m[14];
        var b = m[6] * m[15] - m[7] * m[14];
        var c = m[6] * m[11] - m[7] * m[10];
        var d = m[2] * m[15] - m[3] * m[14];
        var e = m[2] * m[11] - m[3] * m[10];
        var f = m[2] * m[7] - m[3] * m[6];
        return ((m[5] * a - m[9] * b + m[13] * c) * m[0] - (m[1] * a - d * m[9] + e * m[13]) * m[4])
                + (m[1] * b - d * m[5] + f * m[13]) * m[8]
                - (m[1] * c - e * m[5] + f * m[9]) * m[12];
    }

    private static float[] sineTable() {
        var table = new float[16384];
        for (var i = 0; i < table.length; i++) {
            table[i] = (float) StrictMath.sin((i * 2.0) * Math.PI / 16384.0);
        }
        return table;
    }
}
