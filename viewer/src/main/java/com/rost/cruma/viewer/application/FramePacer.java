package com.rost.cruma.viewer.application;

import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig;

final class FramePacer {
    private final long stallNanos;
    private int frameRateCap;
    private long stepNanos;
    private long maxFrameNanos;
    private long lastFrameAtNs;
    private long carriedNanos;

    FramePacer(int frameRateCap, long maxFrameNanos, long nowNs) {
        stallNanos = maxFrameNanos;
        setFrameRateCap(frameRateCap);
        this.lastFrameAtNs = nowNs;
    }

    long waitNanos(long nowNs) {
        var elapsed = nowNs - lastFrameAtNs;
        return elapsed >= stepNanos ? 0L : stepNanos - elapsed;
    }

    long beginFrame(long nowNs) {
        var elapsed = Math.min(Math.max(nowNs - lastFrameAtNs, 0L), maxFrameNanos);
        lastFrameAtNs = nowNs;
        var total = elapsed + carriedNanos;
        var millis = total / 1_000_000L;
        carriedNanos = total - millis * 1_000_000L;
        return millis;
    }

    void restart(long nowNs) {
        lastFrameAtNs = nowNs - stepNanos;
        carriedNanos = 0L;
    }

    long getStepNanos() {
        return stepNanos;
    }

    int getFrameRateCap() {
        return frameRateCap;
    }

    void setFrameRateCap(int cap) {
        if (cap < ViewerGraphicsConfig.MIN_FRAME_RATE_CAP || cap > ViewerGraphicsConfig.MAX_FRAME_RATE_CAP) {
            throw new IllegalArgumentException(
                    "frame rate cap must be between " + ViewerGraphicsConfig.MIN_FRAME_RATE_CAP + " and "
                            + ViewerGraphicsConfig.MAX_FRAME_RATE_CAP + ", got " + cap);
        }
        frameRateCap = cap;
        stepNanos = 1_000_000_000L / cap;
        maxFrameNanos = Math.max(stepNanos, stallNanos);
    }
}
