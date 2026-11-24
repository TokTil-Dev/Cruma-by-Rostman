package com.rost.cruma.viewer.render.upscale;

import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.Upscaler;
import java.util.Set;

public final class Fsr1UpscalerProvider implements UpscalerProvider {
    @Override
    public String getId() {
        return "fsr1";
    }

    @Override
    public Set<Input> getInputs() {
        return Set.of(Input.COLOR);
    }

    @Override
    public Plan plan(Upscaler requested, Extent displayExtent) {
        var render = displayExtent.scaled(requested.requestedScale());
        return BilinearUpscalerProvider.plan(
                getId(), requested, displayExtent, render, requested.rcasEnabled(), requested.rcasEnabled() ? 2 : 1);
    }
}
