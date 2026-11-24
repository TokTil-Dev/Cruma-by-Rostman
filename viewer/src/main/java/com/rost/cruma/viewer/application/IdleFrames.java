package com.rost.cruma.viewer.application;

import java.util.concurrent.TimeUnit;

final class IdleFrames {
    static final long QUIET_NANOS = TimeUnit.MILLISECONDS.toNanos(500L);

    static final double WAIT_SECONDS = 1.0;

    static final double MARK_WAIT_SECONDS = 1.0 / 30.0;

    private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(1L);

    private final boolean allowed;

    IdleFrames(boolean allowed) {
        this.allowed = allowed;
    }

    record State(
            boolean paused,
            boolean inputHeld,
            long lastInputNanos,
            boolean loading,
            boolean animating,
            boolean pendingWork,
            boolean markMoving) {}

    boolean waits(State state, long nowNanos) {
        return allowed
                && state.paused()
                && !state.inputHeld()
                && !state.loading()
                && !state.animating()
                && !state.pendingWork()
                && nowNanos - state.lastInputNanos() >= QUIET_NANOS;
    }

    double waitSeconds(State state) {
        return state.markMoving() ? MARK_WAIT_SECONDS : WAIT_SECONDS;
    }

    boolean drawsInterfaceOnly(State state, long nowNanos, long worldNanos) {
        return waits(state, nowNanos) && state.markMoving() && nowNanos - worldNanos < WAIT_NANOS;
    }
}
