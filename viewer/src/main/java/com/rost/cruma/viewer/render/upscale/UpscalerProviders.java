package com.rost.cruma.viewer.render.upscale;

import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.UpscalerAlgorithm;

public final class UpscalerProviders {
    private static final UpscalerProvider NATIVE = new NativeUpscalerProvider();
    private static final UpscalerProvider BILINEAR = new BilinearUpscalerProvider();
    private static final UpscalerProvider FSR1 = new Fsr1UpscalerProvider();

    private UpscalerProviders() {}

    public static UpscalerProvider forAlgorithm(UpscalerAlgorithm algorithm) {
        if (algorithm == null) {
            throw new IllegalArgumentException("upscaler algorithm cannot be null");
        }
        return switch (algorithm) {
            case NATIVE -> NATIVE;
            case BILINEAR -> BILINEAR;
            case FSR1 -> FSR1;
        };
    }
}
