package com.rost.cruma.viewer.render;

public class ScenePlayback {
    public static final double STEP_SECONDS = 1d / 60d;
    private double seconds;
    private double speed = 1d;
    private double lastDelta;
    private boolean paused;
    private boolean comparisonLocked;
    private int pendingSteps;
    private long pauseKeyFrame = Long.MIN_VALUE;

    public double advanceMillis(long milliseconds) {
        if (milliseconds < 0) {
            throw new IllegalArgumentException("negative frame duration");
        }
        lastDelta =
                comparisonLocked ? 0d : paused ? pendingSteps > 0 ? STEP_SECONDS : 0d : milliseconds / 1000d * speed;
        if (paused && !comparisonLocked && pendingSteps > 0) {
            pendingSteps--;
        }
        seconds += lastDelta;
        return lastDelta;
    }

    public double advanceExact(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) {
            throw new IllegalArgumentException("invalid frame duration");
        }
        lastDelta = seconds;
        this.seconds += seconds;
        return seconds;
    }

    public void setSeconds(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) {
            throw new IllegalArgumentException("invalid scene time");
        }
        this.seconds = seconds;
    }

    public float getSeconds() {
        return (float) seconds;
    }

    public double getPreciseSeconds() {
        return seconds;
    }

    public double getLastDelta() {
        return lastDelta;
    }

    public boolean isPaused() {
        return paused;
    }

    public double getSpeed() {
        return speed;
    }

    public boolean isComparisonLocked() {
        return comparisonLocked;
    }

    public void setPaused(boolean paused) {
        if (comparisonLocked && !paused) {
            return;
        }
        this.paused = paused;
        pendingSteps = 0;
    }

    public boolean claimPauseKey(long frame) {
        if (frame == pauseKeyFrame) {
            return false;
        }
        pauseKeyFrame = frame;
        return true;
    }

    public void setSpeed(double speed) {
        if (!Double.isFinite(speed) || speed < .1d || speed > 4d) {
            throw new IllegalArgumentException("playback speed must be between 0.1 and 4");
        }
        this.speed = speed;
    }

    public void step() {
        if (comparisonLocked) {
            return;
        }
        paused = true;
        pendingSteps = Math.min(120, pendingSteps + 1);
    }

    public void setComparisonLocked(boolean locked) {
        comparisonLocked = locked;
        if (locked) {
            paused = true;
            pendingSteps = 0;
        }
    }
}
