package com.rost.cruma.viewer.render.upscale;

import com.rost.cruma.viewer.render.api.RenderDiagnostics;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.Upscaler;

public final class DynamicResolutionController {
    private static final double STEP = 1.0 / 32.0;
    private static final double HYSTERESIS_FRACTION = 0.05;
    private static final double CPU_BOUND_RATIO = 1.10;

    private final ViewerGraphicsConfig.DynamicResolution config;
    private final double floor;
    private final double ceiling;

    private double scale;
    private double errorMs;
    private int cooldownRemaining;
    private boolean active;
    private String disabledReason;

    public DynamicResolutionController(Upscaler upscaler) {
        if (upscaler == null) {
            throw new IllegalArgumentException("upscaler is required");
        }
        config = upscaler.dynamicResolution();
        ceiling = Math.min(config.maxScale(), upscaler.requestedScale());
        floor = Math.min(config.minScale(), ceiling);
        scale = ceiling;
        disabledReason = config.enabled() ? "waiting for valid GPU timing" : "disabled by config";
    }

    public double getScale() {
        return scale;
    }

    public boolean update(double gpuFrameMs, double cpuFrameMs) {
        if (!config.enabled()) {
            active = false;
            disabledReason = "disabled by config";
            return false;
        }
        if (!isValidTiming(gpuFrameMs) || !isValidTiming(cpuFrameMs)) {
            active = false;
            disabledReason = "GPU/CPU frame timing unavailable";
            errorMs = 0.0;
            scale = ceiling;
            cooldownRemaining = 0;
            return false;
        }

        errorMs = gpuFrameMs - config.targetFrameMs();
        if (cooldownRemaining > 0) {
            cooldownRemaining--;
        }

        var cpuBound = cpuFrameMs > config.targetFrameMs() && cpuFrameMs > gpuFrameMs * CPU_BOUND_RATIO;
        if (cpuBound) {
            active = false;
            disabledReason = "CPU-bound frame";
            return moveTowardCeiling();
        }

        active = true;
        disabledReason = "";
        if (cooldownRemaining > 0) {
            return false;
        }
        var deadBand = config.targetFrameMs() * HYSTERESIS_FRACTION;
        double next = scale;
        if (errorMs > deadBand) {
            var predicted = scale * Math.sqrt(config.targetFrameMs() / gpuFrameMs);
            next = Math.max(scale - 2.0 * STEP, predicted);
        } else if (errorMs < -deadBand && gpuFrameMs >= cpuFrameMs * 0.90) {
            next = scale + STEP;
        }
        next = clamp(quantize(next), floor, ceiling);
        if (Double.compare(next, scale) == 0) {
            return false;
        }
        scale = next;
        cooldownRemaining = config.cooldownFrames();
        return true;
    }

    public void reset(String reason) {
        scale = ceiling;
        errorMs = 0.0;
        cooldownRemaining = 0;
        active = false;
        disabledReason = reason == null || reason.isBlank() ? "history reset" : reason;
    }

    public RenderDiagnostics.DynamicResolution getDiagnostics() {
        return new RenderDiagnostics.DynamicResolution(
                config.enabled(), active, config.targetFrameMs(), scale, errorMs, cooldownRemaining, disabledReason);
    }

    private boolean moveTowardCeiling() {
        if (cooldownRemaining > 0 || scale >= ceiling) {
            return false;
        }
        scale = clamp(quantize(Math.min(ceiling, scale + STEP)), floor, ceiling);
        cooldownRemaining = config.cooldownFrames();
        return true;
    }

    private static double quantize(double value) {
        return Math.round(value / STEP) * STEP;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean isValidTiming(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }
}
