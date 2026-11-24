package com.rost.cruma.viewer.render.upscale;

import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.Upscaler;
import java.util.Set;

public final class BilinearUpscalerProvider implements UpscalerProvider {
    static Plan plan(String provider, Upscaler requested, Extent display, Extent render, boolean rcas, int passCount) {
        var scaleX = display.isZero() ? 1.0 : (double) render.width() / display.width();
        var scaleY = display.isZero() ? 1.0 : (double) render.height() / display.height();
        return new Plan(
                provider,
                requested.preset(),
                render,
                display,
                scaleX,
                scaleY,
                rcas,
                requested.sharpness(),
                passCount,
                "");
    }

    @Override
    public String getId() {
        return "bilinear";
    }

    @Override
    public Set<Input> getInputs() {
        return Set.of(Input.COLOR);
    }

    @Override
    public Plan plan(Upscaler requested, Extent displayExtent) {
        var render = displayExtent.scaled(requested.requestedScale());
        return plan(getId(), requested, displayExtent, render, false, 1);
    }
}
