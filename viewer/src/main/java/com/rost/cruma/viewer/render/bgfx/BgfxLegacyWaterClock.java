package com.rost.cruma.viewer.render.bgfx;

final class BgfxLegacyWaterClock {
    private double firstBindSeconds = Double.NaN;

    BumpMatrix sample(double seconds, float amplitude) {
        if (Double.isNaN(firstBindSeconds)) {
            firstBindSeconds = seconds;
        }
        var angle = (float) ((seconds - firstBindSeconds) * (double) 0.9f);
        return new BumpMatrix((float) Math.cos(angle) * amplitude, (float) Math.sin(angle) * amplitude);
    }

    record BumpMatrix(float cosine, float sine) {}
}
