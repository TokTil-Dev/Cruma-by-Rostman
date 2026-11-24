package com.rost.cruma.core.editing;

public record LightColor(float brightness, int hue, int saturation) {
    public static final LightColor DEFAULT = new LightColor(64, 0, 255);

    public static final float MAX_BRIGHTNESS = 1024;

    public LightColor {
        if (!Float.isFinite(brightness)) {
            throw new IllegalArgumentException("Light brightness must be finite");
        }
        if (hue < 0 || hue > 255 || saturation < 0 || saturation > 255) {
            throw new IllegalArgumentException("Light hue and saturation must be between 0 and 255");
        }
    }
}
