package com.rost.cruma.viewer.render;

public final class PictureClock {
    private static final double NANOS = 1e9;

    private boolean fixed;
    private long fixedAt;
    private double seconds;

    public long getNanos() {
        return fixed ? fixedAt + Math.round(seconds * NANOS) : System.nanoTime();
    }

    public double getSeconds() {
        return getNanos() * 1e-9;
    }

    public void fix() {
        if (!fixed) {
            fixedAt = System.nanoTime();
            fixed = true;
        }
    }

    public void advance(double delta) {
        seconds += delta;
    }
}
