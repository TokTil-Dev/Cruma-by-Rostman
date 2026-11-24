package com.rost.cruma.core.env;

import glm_.vec3.Vec3;

public final class TimeEnvHsv {
    private TimeEnvHsv() {}

    public static Vec3 interpColor(float hour, TimeHsvKey[] keys) {
        for (var i = 0; i + 1 < keys.length; i++) {
            var low = keys[i];
            var high = keys[i + 1];
            if (low.t() <= hour && hour < high.t()) {
                var fraction = (hour - low.t()) / (high.t() - low.t());
                var a = UnrealHsv.toRgb(low.hue(), low.sat(), 255);
                var b = UnrealHsv.toRgb(high.hue(), high.sat(), 255);
                var brightness = (high.bri() - low.bri()) * fraction + low.bri();
                var intensity = brightness / 255f;
                return new Vec3(
                        ((b.getX() - a.getX()) * fraction + a.getX()) * intensity,
                        ((b.getY() - a.getY()) * fraction + a.getY()) * intensity,
                        ((b.getZ() - a.getZ()) * fraction + a.getZ()) * intensity);
            }
        }
        return new Vec3();
    }
}
