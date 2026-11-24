package com.rost.cruma.core.world;

import glm_.vec3.Vec3;
import glm_.vec4.Vec4;

public record WaterPlane(float x, float y, float z, float d) {
    private static final float NORMAL_QUANTIZATION = 4096f;
    private static final float DISTANCE_QUANTIZATION = 16f;
    private static final float MIN_NORMAL_LENGTH = 1.0e-6f;

    public static WaterPlane from(Vec3 normal, Vec3 point) {
        var length = normal.length();
        if (!Float.isFinite(length) || length < MIN_NORMAL_LENGTH) {
            throw new IllegalArgumentException("Water plane requires a finite non-zero normal");
        }
        var nx = normal.getX() / length;
        var ny = normal.getY() / length;
        var nz = normal.getZ() / length;

        nx = quantize(nx, NORMAL_QUANTIZATION);
        ny = quantize(ny, NORMAL_QUANTIZATION);
        nz = quantize(nz, NORMAL_QUANTIZATION);
        var quantizedLength = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= quantizedLength;
        ny /= quantizedLength;
        nz /= quantizedLength;
        var distance = -(nx * point.getX() + ny * point.getY() + nz * point.getZ());

        if (nx < -MIN_NORMAL_LENGTH
                || (Math.abs(nx) <= MIN_NORMAL_LENGTH && ny < -MIN_NORMAL_LENGTH)
                || (Math.abs(nx) <= MIN_NORMAL_LENGTH && Math.abs(ny) <= MIN_NORMAL_LENGTH && nz < 0f)) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
            distance = -distance;
        }
        return new WaterPlane(
                canonicalZero(nx), canonicalZero(ny), canonicalZero(nz), quantize(distance, DISTANCE_QUANTIZATION));
    }

    public float signedDistance(Vec3 point) {
        return x * point.getX() + y * point.getY() + z * point.getZ() + d;
    }

    public Vec3 reflect(Vec3 point) {
        var distance = signedDistance(point);
        return new Vec3(
                point.getX() - 2f * x * distance, point.getY() - 2f * y * distance, point.getZ() - 2f * z * distance);
    }

    public Vec3 reflectDirection(Vec3 direction) {
        var normalProjection = x * direction.getX() + y * direction.getY() + z * direction.getZ();
        return new Vec3(
                direction.getX() - 2f * x * normalProjection,
                direction.getY() - 2f * y * normalProjection,
                direction.getZ() - 2f * z * normalProjection);
    }

    public Vec4 orientedToward(Vec3 point) {
        return orientedToward(point, new Vec4());
    }

    public Vec4 orientedToward(Vec3 point, Vec4 target) {
        if (target == null) {
            throw new IllegalArgumentException("Water plane target cannot be null");
        }
        var side = signedDistance(point) < 0f ? -1f : 1f;
        target.setX(x * side);
        target.setY(y * side);
        target.setZ(z * side);
        target.setW(d * side);
        return target;
    }

    private static float quantize(float value, float scale) {
        var rounded = Math.round(value * scale) / scale;
        return canonicalZero(rounded);
    }

    private static float canonicalZero(float value) {
        return value == 0f ? 0f : value;
    }
}
