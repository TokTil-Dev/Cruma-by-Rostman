package com.rost.cruma.core.unreal.primitives;

public record UColorFade(int color1, int color2, float period, float phase, int type) {
    public static int argb(UColor color) {
        return (color.a() & 255) << 24 | (color.r() & 255) << 16 | (color.g() & 255) << 8 | color.b() & 255;
    }

    public boolean isSupported() {
        return type != 0 && type != 1 || period != 0 && Float.isFinite(period) && Float.isFinite(phase);
    }

    public boolean isAnimated() {
        return (type == 0 || type == 1) && isSupported();
    }

    public int argb(float time) {
        if (type != 0 && type != 1) {
            return color1;
        }
        if (!isSupported() || !Float.isFinite(time)) {
            throw new IllegalArgumentException("unsupported FadeColor time domain");
        }
        var position = (phase + time) / period;
        final float weight;
        if (type == 0) {
            var cell = floor(position);
            var fraction = position - (float) cell;
            weight = cell % 2 != 0 ? fraction : 1f - fraction;
        } else {
            weight = (float) (Math.cos(((double) position * Math.PI) * 0.5) * 0.5 + 0.5);
        }
        var inverse = 1f - weight;
        var result = 0;
        for (var shift = 0; shift < 32; shift += 8) {
            var first = (color1 >>> shift & 255) / 255f;
            var second = (color2 >>> shift & 255) / 255f;
            var channel = (first * weight + second * inverse) * 255f;
            result |= Math.clamp(floor(channel), 0, 255) << shift;
        }
        return result;
    }

    private static int floor(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("nonfinite FadeColor arithmetic needs native CRT handling");
        }
        if (value >= 2147483648f || value < -2147483648f) {
            return Integer.MIN_VALUE;
        }
        return (int) Math.floor(value);
    }
}
