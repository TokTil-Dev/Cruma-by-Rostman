package com.rost.cruma.core.env;

import glm_.vec3.Vec3;

public final class EnvironmentSun {
    private EnvironmentSun() {}

    public static Vec3 direction(float hour, float slopeDegrees) {
        return direction(hour, 180f, slopeDegrees, false);
    }

    public static Vec3 direction(float hour, float longitudeDegrees, float slopeDegrees, boolean reverseSlope) {
        var latitude = hour >= 6f && hour < 24f
                ? (float) ((double) (hour - 6f) * (Math.PI / 18.0) - Math.PI / 2.0)
                : (float) ((double) (hour - 0f) * (Math.PI / 6.0) - Math.PI / 2.0);
        var rotated = rotatedDirection(latitude, longitudeDegrees, slopeDegrees, reverseSlope);
        var rx = rotated.getX();
        var ry = rotated.getY();
        var rz = rotated.getZ();
        var lengthSquared = rx * rx + ry * ry + rz * rz;
        var scale = (float) (1.0 / Math.sqrt(lengthSquared));
        return new Vec3(rx * scale, ry * scale, rz * scale);
    }

    static Vec3 rotatedDirection(float latitude, float longitudeDegrees, float slopeDegrees, boolean reverseSlope) {
        var longitude = (float) ((double) longitudeDegrees * (Math.PI / 180.0));
        var x = (float) (Math.cos(longitude) * Math.sin(latitude));
        var y = (float) (Math.sin(longitude) * Math.sin(latitude));
        var z = (float) Math.cos(latitude);
        var slope = reverseSlope ? -slopeDegrees : slopeDegrees;
        var degrees = slope >= -2147483648f && slope < 2147483648f ? (int) slope : Integer.MIN_VALUE;
        var angle = ((degrees << 15) / 180) & 65535;
        var sine = sineTable(angle);
        var cosine = sineTable(angle + 16384);
        var oneMinus = 1f - cosine;
        var zero = oneMinus * 0f;
        var zeroSine = 0f * sine;
        var rx = ((oneMinus + cosine) * x + (zero - zeroSine) * y) + (zero + zeroSine) * z;
        var ry = (cosine * y + (zero + zeroSine) * x) + (zero - sine) * z;
        var rz = ((zero + sine) * y + (zero - zeroSine) * x) + cosine * z;
        return new Vec3(rx, ry, rz);
    }

    static float sineTable(int angle) {
        var index = (angle >> 2) & 16383;
        return (float) Math.sin((double) (index * 2) * Math.PI * (1.0 / 16384.0));
    }
}
