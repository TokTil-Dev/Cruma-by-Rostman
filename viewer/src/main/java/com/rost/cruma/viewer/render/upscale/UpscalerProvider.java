package com.rost.cruma.viewer.render.upscale;

import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.Upscaler;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.UpscalerPreset;
import java.util.Set;

public interface UpscalerProvider {
    String getId();

    Set<Input> getInputs();

    Plan plan(Upscaler requested, Extent displayExtent);

    default boolean isTemporal() {
        return getInputs().contains(Input.DEPTH) || getInputs().contains(Input.MOTION_VECTORS);
    }

    enum Input {
        COLOR,
        DEPTH,
        MOTION_VECTORS,
        JITTER,
        PREVIOUS_MATRICES,
        EXPOSURE,
        REACTIVE_MASK,
        TRANSPARENCY_MASK,
        FRAME_DELTA,
        HISTORY_RESET
    }

    record Plan(
            String provider,
            UpscalerPreset preset,
            Extent renderExtent,
            Extent displayExtent,
            double actualScaleX,
            double actualScaleY,
            boolean rcas,
            double sharpness,
            int passCount,
            String fallbackReason) {
        public Plan {
            if (provider == null
                    || provider.isBlank()
                    || preset == null
                    || renderExtent == null
                    || displayExtent == null
                    || !Double.isFinite(actualScaleX)
                    || !Double.isFinite(actualScaleY)
                    || actualScaleX <= 0.0
                    || actualScaleY <= 0.0
                    || actualScaleX > 1.0
                    || actualScaleY > 1.0
                    || !Double.isFinite(sharpness)
                    || sharpness < 0.0
                    || sharpness > 1.0
                    || passCount < 0) {
                throw new IllegalArgumentException("invalid upscaler plan");
            }
            fallbackReason = fallbackReason == null ? "" : fallbackReason;
        }

        public boolean isFallback() {
            return !fallbackReason.isEmpty();
        }
    }
}
