package com.rost.cruma.viewer.render.api;

import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import java.util.Objects;

public record ReadbackRequest(Source source, Extent extent, String label) {
    public ReadbackRequest {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(extent, "extent");
        if (extent.isZero()) {
            throw new IllegalArgumentException("readback extent cannot be zero");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("readback label cannot be blank");
        }
    }

    public enum Source {
        WORLD_COLOR
    }
}
