package com.rost.cruma.viewer.render;

import com.rost.cruma.viewer.config.CutCircle;
import com.rost.cruma.viewer.config.KeptLayers;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public record ShaderHeightCut(
        @Nullable Integer top,
        @Nullable Integer bottom,
        KeptLayers kept,
        @Nullable CutCircle circle) {
    public static final int FLOATS = 8;

    public static void put(@Nullable ShaderHeightCut cut, long surfaceType, float[] target, int offset) {
        var active = cut != null && !cut.kept.keeps(surfaceType);
        target[offset] = cut == null || cut.top == null ? 0f : cut.top;
        target[offset + 1] = cut == null || cut.bottom == null ? 0f : cut.bottom;
        target[offset + 2] = active && cut.top != null ? 1f : 0f;
        target[offset + 3] = active && cut.bottom != null ? 1f : 0f;
        var circle = cut == null ? null : cut.circle;
        target[offset + 4] = circle == null ? 0f : circle.x();
        target[offset + 5] = circle == null ? 0f : circle.y();
        target[offset + 6] = circle == null ? 0f : circle.radius();
        target[offset + 7] = 0f;
    }

    boolean matches(@Nullable Integer top, @Nullable Integer bottom, KeptLayers kept, @Nullable CutCircle circle) {
        return Objects.equals(this.top, top)
                && Objects.equals(this.bottom, bottom)
                && this.kept.equals(kept)
                && Objects.equals(this.circle, circle);
    }
}
