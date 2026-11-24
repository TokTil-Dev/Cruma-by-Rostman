package com.rost.cruma.viewer.config;

public record CutCircle(int x, int y, int radius) {
    public static final int MAX_RADIUS = 32_768;

    public CutCircle {
        if (radius < 1 || radius > MAX_RADIUS) {
            throw new IllegalArgumentException(
                    "Cut circle radius must be within 1.." + MAX_RADIUS + " world units, got " + radius);
        }
    }
}
