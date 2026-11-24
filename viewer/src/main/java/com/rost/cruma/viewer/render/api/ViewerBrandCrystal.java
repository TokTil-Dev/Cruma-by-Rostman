package com.rost.cruma.viewer.render.api;

import org.jetbrains.annotations.Nullable;

public interface ViewerBrandCrystal {
    float MARK_UNITS = 24, TIP_MARGIN = 1;

    record Drawn(long image, float u0, float v0, float u1, float v1) {}

    @Nullable
    Drawn draw(int pixels, double seconds);

    boolean isMoving();
}
