package com.rost.cruma.viewer.render.upscale;

import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.DynamicResolution;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.Upscaler;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.UpscalerAlgorithm;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.UpscalerPreset;

public final class UpscalerRuntimeState {
    private UpscalerAlgorithm algorithm;
    private UpscalerPreset spatialPreset;
    private final boolean rcasEnabled;
    private final double sharpness;
    private final DynamicResolution dynamicResolution;
    private Upscaler cachedConfig;

    public UpscalerRuntimeState(Upscaler configured) {
        if (configured == null) {
            throw new IllegalArgumentException("configured upscaler is required");
        }
        algorithm = configured.algorithm();
        spatialPreset = configured.preset() == UpscalerPreset.NATIVE ? UpscalerPreset.QUALITY : configured.preset();
        rcasEnabled = configured.rcasEnabled();
        sharpness = configured.sharpness();
        dynamicResolution = configured.dynamicResolution();
    }

    public boolean cycleAlgorithm() {
        algorithm = switch (algorithm) {
            case NATIVE -> UpscalerAlgorithm.BILINEAR;
            case BILINEAR -> UpscalerAlgorithm.FSR1;
            case FSR1 -> UpscalerAlgorithm.NATIVE;
        };
        cachedConfig = null;
        return true;
    }

    public boolean cyclePreset() {
        spatialPreset = switch (spatialPreset) {
            case NATIVE, CUSTOM -> UpscalerPreset.ULTRA_QUALITY;
            case ULTRA_QUALITY -> UpscalerPreset.QUALITY;
            case QUALITY -> UpscalerPreset.BALANCED;
            case BALANCED -> UpscalerPreset.PERFORMANCE;
            case PERFORMANCE -> UpscalerPreset.ULTRA_QUALITY;
        };
        cachedConfig = null;
        return algorithm != UpscalerAlgorithm.NATIVE;
    }

    public UpscalerAlgorithm getAlgorithm() {
        return algorithm;
    }

    public Upscaler getConfig() {
        if (cachedConfig != null) {
            return cachedConfig;
        }
        if (algorithm == UpscalerAlgorithm.NATIVE) {
            cachedConfig = Upscaler.nativeOnly();
            return cachedConfig;
        }
        var scale = spatialPreset.getScale(1.0 / 1.5);
        cachedConfig = new Upscaler(
                algorithm,
                spatialPreset,
                scale,
                algorithm == UpscalerAlgorithm.FSR1 && rcasEnabled,
                sharpness,
                dynamicResolution);
        return cachedConfig;
    }
}
