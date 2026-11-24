package com.rost.cruma.core.world;

import glm_.vec3.Vec3;
import org.jetbrains.annotations.Nullable;

public record SkyZone(
        String name,
        Vec3 location,
        Vec3 boxMax,
        Vec3 boxMin,
        int timeEnv,
        float time,
        boolean useDefaultMap,
        boolean useTimeZone,
        boolean realTime,
        boolean showSun,
        float zoneStateChangeTime,
        boolean listed,
        ZoneRender zoneRender) {
    public SkyZone {
        location = new Vec3(location);
        boxMax = new Vec3(boxMax);
        boxMin = new Vec3(boxMin);
    }

    public static int maskOf(int[] states) {
        var mask = 0;
        for (var state : states) {
            if (state == 0) {
                return 0;
            }
            if (state >= 1 && state <= 32) {
                mask |= 1 << (state - 1);
            }
        }
        return mask;
    }

    public int checkActive(ZoneStateChoice choice, @Nullable ZoneFades fades) {
        var mask = zoneRender.mask();
        if (mask == 0 || zoneRender.zoneStart() == ZoneRender.NO_ZONE_STATES) {
            return ZoneFade.INACTIVE;
        }
        var fade = fades == null || !fades.getChoice().equals(choice) || zoneRender.blend() == null
                ? null
                : fades.find(zoneRender);
        if (fade != null) {
            return fade.checkActive(mask);
        }
        return zoneRender.shows(choice) ? ZoneFade.CURRENT : ZoneFade.INACTIVE;
    }

    public boolean contains(Vec3 point) {
        return Math.abs(point.getX() - location.getX()) <= boxMax.getX()
                && Math.abs(point.getY() - location.getY()) <= boxMax.getY()
                && Math.abs(point.getZ() - location.getZ()) <= boxMax.getZ();
    }

    public float getWeight(Vec3 point) {
        var weight = Math.min(
                axisWeight(point.getX() - location.getX(), boxMin.getX(), boxMax.getX()),
                Math.min(
                        axisWeight(point.getY() - location.getY(), boxMin.getY(), boxMax.getY()),
                        axisWeight(point.getZ() - location.getZ(), boxMin.getZ(), boxMax.getZ())));
        return weight < 0f ? 0f : Math.min(weight, 1f);
    }

    private static float axisWeight(float delta, float inner, float outer) {
        var rest = outer - Math.abs(delta);
        var ramp = outer - inner;
        if (ramp == 0f) {
            return rest > 0f ? Float.POSITIVE_INFINITY : rest < 0f ? Float.NEGATIVE_INFINITY : 0f;
        }
        return rest / ramp;
    }
}
