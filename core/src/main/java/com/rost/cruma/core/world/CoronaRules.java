package com.rost.cruma.core.world;

import glm_.vec3.Vec3;

public final class CoronaRules {
    public static final int MOST_CORONAS = 64;

    public static final float MOST_REACH = 6400f;

    public static final float RECHECK_SECONDS = 0.33f;

    private static final float SIZE_UNITS = 512f;
    private static final float DISTANCE_UNITS = 1000f;
    private static final float LEAST_DISTANCE_FACTOR = 0.01f;
    private static final float MOST_DISTANCE_FACTOR = 10f;
    private static final float FADE_RATE = 3f;
    private static final float ROTATION_UNITS = 16384f;
    private static final int HUE_THIRD = 85;
    private static final float HUE_STEP = 85f;
    private static final float LAST_HUE_STEP = 84f;

    private CoronaRules() {}

    public static float reach(float radius) {
        return (radius + 1f) * 25f;
    }

    public static float distanceFactor(float distance) {
        return clamp(distance / DISTANCE_UNITS, LEAST_DISTANCE_FACTOR, MOST_DISTANCE_FACTOR);
    }

    public static float halfSize(float drawScale, float axisScale, float distanceFactor, float least, float most) {
        return clamp(SIZE_UNITS * drawScale * axisScale * distanceFactor, least, most);
    }

    public static int rotation(float rate, float offset, float distanceFactor) {
        if (rate == 0f && offset == 0f) {
            return 0;
        }
        return (int) (rate * ROTATION_UNITS * distanceFactor + offset);
    }

    public static boolean isFlat(Vec3 drawScale3d) {
        return drawScale3d.getX() != drawScale3d.getY() && drawScale3d.getZ() > 0f;
    }

    public static float flatFactor(Vec3 axisX, Vec3 axisY, Vec3 view) {
        var x = 1f - Math.abs(dot(axisX, view));
        var y = 1f - Math.abs(dot(axisY, view));
        return (float) Math.sqrt(Math.max(0f, x * y));
    }

    public static float[] color(int hue, int saturation) {
        var h = hue & 0xff;
        float red;
        float green;
        float blue;
        if (h < HUE_THIRD + 1) {
            red = (HUE_THIRD - h) / HUE_STEP;
            green = h / HUE_STEP;
            blue = 0f;
        } else if (h < 2 * HUE_THIRD + 1) {
            red = 0f;
            green = (2 * HUE_THIRD - h) / HUE_STEP;
            blue = (h - HUE_THIRD) / HUE_STEP;
        } else {
            red = (h - 2 * HUE_THIRD) / HUE_STEP;
            green = 0f;
            blue = (255 - h) / LAST_HUE_STEP;
        }
        var white = (saturation & 0xff) / 255f;
        return new float[] {red + (1f - red) * white, green + (1f - green) * white, blue + (1f - blue) * white};
    }

    public static float fadeStep(float frameSeconds) {
        return Math.min(1f, FADE_RATE * Math.max(0f, frameSeconds));
    }

    public static float brighten(float brightness, float scaleGlow, float step) {
        return Math.min(1f, brightness + 2f * scaleGlow * step);
    }

    public static float dim(float brightness, float scaleGlow, float step) {
        return brightness - scaleGlow * step;
    }

    public static boolean passesCullDistance(float distanceSquared, float cullDistance, float fovDegrees) {
        if (!(cullDistance > 0f)) {
            return true;
        }
        var tangent = Math.tan(Math.toRadians(fovDegrees) * 0.5);
        return distanceSquared * tangent * tangent <= (double) cullDistance * cullDistance;
    }

    public static float recheckSeconds(float random) {
        return RECHECK_SECONDS * (1f + Math.clamp(random, 0f, 1f));
    }

    public static Vec3 rotateAngleAxis(Vec3 vector, int angle, Vec3 axis) {
        var radians = angle * (2.0 * Math.PI / 65536.0);
        var s = (float) Math.sin(radians);
        var c = (float) Math.cos(radians);
        var x = axis.getX();
        var y = axis.getY();
        var z = axis.getZ();
        var omc = 1f - c;
        var vx = vector.getX();
        var vy = vector.getY();
        var vz = vector.getZ();
        return new Vec3(
                (omc * x * x + c) * vx + (omc * x * y - z * s) * vy + (omc * z * x + y * s) * vz,
                (omc * x * y + z * s) * vx + (omc * y * y + c) * vy + (omc * y * z - x * s) * vz,
                (omc * z * x - y * s) * vx + (omc * y * z + x * s) * vy + (omc * z * z + c) * vz);
    }

    private static float dot(Vec3 a, Vec3 b) {
        return a.getX() * b.getX() + a.getY() * b.getY() + a.getZ() * b.getZ();
    }

    private static float clamp(float value, float least, float most) {
        if (value < least) {
            return least;
        }
        return value < most ? value : most;
    }
}
