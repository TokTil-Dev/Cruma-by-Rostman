package com.rost.cruma.core.env;

import com.rost.cruma.core.unreal.primitives.URotator;
import glm_.vec3.Vec3;

public final class MovableSun {
    private MovableSun() {}

    public static Vec3 incidentDirection(float hour, float slopeDegrees) {
        var category = hour >= 6f && hour < 24f ? 0 : 3;
        return incidentDirection(rotation(hour, category, 180f, slopeDegrees, false));
    }

    public static URotator rotation(
            float hour, int environmentCategory, float longitudeDegrees, float slopeDegrees, boolean reverseSlope) {
        var latitude = 0f;
        if (environmentCategory == 3) {
            latitude = hour >= 7f && hour < 23f
                    ? (float) -Math.PI
                    : (float) ((double) (hour - 0f) * (Math.PI / 6.0) - Math.PI / 2.0);
        } else if (hour >= 5f && hour < 24f) {
            latitude = (float) ((double) (hour - 6f) * (Math.PI / 18.0) - Math.PI / 2.0);
        } else if (hour < 1f) {
            latitude = (float) ((double) ((hour + 24f) - 6f) * (Math.PI / 18.0) - Math.PI / 2.0);
        } else {
            latitude = (float) -Math.PI;
        }
        var source = EnvironmentSun.rotatedDirection(latitude, longitudeDegrees, slopeDegrees, reverseSlope);
        var x = -source.getX();
        var y = -source.getY();
        var z = -source.getZ();
        var yaw = (int) ((Math.atan2(y, x) * 65535.0) / (2.0 * Math.PI));
        var pitch = (int) ((Math.atan2(z, Math.sqrt(x * x + y * y)) * 65535.0) / (2.0 * Math.PI));
        return new URotator(pitch, yaw, 0);
    }

    public static Vec3 incidentDirection(URotator rotation) {
        var cp = EnvironmentSun.sineTable(rotation.pitch() + 16384);
        var cy = EnvironmentSun.sineTable(rotation.yaw() + 16384);
        var sy = EnvironmentSun.sineTable(rotation.yaw());
        var sp = EnvironmentSun.sineTable(rotation.pitch());
        return new Vec3(-(cp * cy + 0f), -(cp * sy + 0f), -(sp + 0f));
    }
}
