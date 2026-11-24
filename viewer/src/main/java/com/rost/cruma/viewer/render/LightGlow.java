package com.rost.cruma.viewer.render;

public record LightGlow(
        double x,
        double y,
        double z,
        float reach,
        float axisX,
        float axisY,
        float axisZ,
        float threshold,
        float red,
        float green,
        float blue,
        float tint) {
    public boolean isSpot() {
        return !Float.isNaN(threshold);
    }
}
