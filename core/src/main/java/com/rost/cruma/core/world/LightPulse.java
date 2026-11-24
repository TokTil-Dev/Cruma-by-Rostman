package com.rost.cruma.core.world;

public final class LightPulse {
    private LightPulse() {}

    public static float sample(float levelTimeSeconds, int period, int phase) {
        var angle = levelTimeSeconds * 35f * 65_536f / Math.max(period & 0xff, 1) + ((phase & 0xff) << 8);
        var quantized = angle >= -0x1p31f && angle < 0x1p31f ? (int) angle : Integer.MIN_VALUE;
        var tableIndex = (quantized >> 2) & 0x3fff;
        var sine = (float) Math.sin((tableIndex * 2.0) * Math.PI / 16_384.0);
        return sine * 0.39f + 0.60f;
    }
}
