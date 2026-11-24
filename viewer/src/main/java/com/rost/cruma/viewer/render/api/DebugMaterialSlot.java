package com.rost.cruma.viewer.render.api;

import com.rost.cruma.core.world.Entity.Surface;

public record DebugMaterialSlot(
        int slot, String name, String blend, String materialType, String uvAnimation, boolean animated) {
    public DebugMaterialSlot(int slot, String name, String blend, String materialType, String uvAnimation) {
        this(slot, name, blend, materialType, uvAnimation, false);
    }

    public static DebugMaterialSlot fromSurface(int slot, Surface surface) {
        return new DebugMaterialSlot(
                slot,
                orUnavailable(surface.materialName()),
                surface.blendMode().name(),
                surface.material() == null
                        ? "Unavailable"
                        : surface.material().getClass().getSimpleName(),
                surface.uvAnimation() == null ? "None" : surface.uvAnimation().toString(),
                DebugMaterialAnimation.hasAnimation(surface));
    }

    public static String orUnavailable(String value) {
        return value == null || value.isBlank() ? "Unavailable" : value;
    }
}
