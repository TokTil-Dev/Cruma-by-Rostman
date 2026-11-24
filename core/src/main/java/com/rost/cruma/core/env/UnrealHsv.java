package com.rost.cruma.core.env;

import glm_.vec3.Vec3;

public final class UnrealHsv {
    private UnrealHsv() {}

    public static Vec3 toRgb(int hue, int saturation, int value) {
        var h = hue & 0xff;
        var whiten = (saturation & 0xff) / 255f;
        var lightness = (value & 0xff) * 1.4f / 255f;
        var gain = Math.clamp((float) (((double) .7f / (Math.sqrt(lightness) + (double) .01f)) * lightness), 0f, 1f);
        var r = h < 86 ? (85 - h) / 85f : h < 171 ? 0f : (h - 170) / 85f;
        var g = h < 86 ? h / 85f : h < 171 ? (170 - h) / 85f : 0f;
        var b = h < 86 ? 0f : h < 171 ? (h - 85) / 85f : (255 - h) / 84f;
        return new Vec3(((1f - r) * whiten + r) * gain, ((1f - g) * whiten + g) * gain, ((1f - b) * whiten + b) * gain);
    }

    public static int[] fromRgb(float red, float green, float blue) {
        var min = Math.min(red, Math.min(green, blue));
        var max = Math.max(red, Math.max(green, blue));
        if (!(max > 0) || !(min >= 0)) {
            return new int[] {0, 255};
        }
        var others = red + green + blue - 2 * min;
        var whiten = min / others;
        if (!(whiten < 1)) {
            return new int[] {0, 255};
        }
        var gain = (others + min) / (1 + whiten);
        float hue;
        if (blue == min) {
            hue = 85 * pure(green, gain, whiten);
        } else if (red == min) {
            hue = 85 + 85 * pure(blue, gain, whiten);
        } else {
            hue = 170 + 85 * pure(red, gain, whiten);
        }
        return new int[] {Math.clamp(Math.round(hue), 0, 255), Math.clamp(Math.round(whiten * 255), 0, 255)};
    }

    private static float pure(float channel, float gain, float whiten) {
        return Math.clamp((channel / gain - whiten) / (1 - whiten), 0f, 1f);
    }
}
