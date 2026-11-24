package com.rost.cruma.viewer.render.upscale;

import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.Upscaler;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.UpscalerPreset;
import java.util.Set;

public final class NativeUpscalerProvider implements UpscalerProvider {
    @Override
    public String getId() {
        return "native";
    }

    @Override
    public Set<Input> getInputs() {
        return Set.of(Input.COLOR);
    }

    @Override
    public Plan plan(Upscaler requested, Extent displayExtent) {
        return new Plan(getId(), UpscalerPreset.NATIVE, displayExtent, displayExtent, 1.0, 1.0, false, 0.0, 0, "");
    }
}
