package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.viewer.geodata.GeoOverlayLines;
import com.rost.cruma.viewer.render.api.DebugSceneObject.Category;
import com.rost.cruma.viewer.render.api.DebugSelection;
import org.jetbrains.annotations.Nullable;

public final class SelectionMark {
    public static final float WIDTH = 2;

    public static final float HOVER_WIDTH = 1;

    public static final int HOVER_COLOR = Motion.alpha(HudWidgets.ACCENT, .4f);

    private SelectionMark() {}

    public static @Nullable Bounds3f box(@Nullable DebugSelection chosen) {
        if (chosen == null
                || chosen.sceneObject() != null && chosen.sceneObject().category() == Category.TERRAIN) {
            return null;
        }
        var box = chosen.bounds();
        var finite = Float.isFinite(box.minX())
                && Float.isFinite(box.minY())
                && Float.isFinite(box.minZ())
                && Float.isFinite(box.maxX())
                && Float.isFinite(box.maxY())
                && Float.isFinite(box.maxZ());
        return finite ? box : null;
    }

    public static void draw(
            GeoOverlayLines lines,
            @Nullable DebugSelection chosen,
            @Nullable DebugSelection hovered,
            boolean hoverBox) {
        var box = box(chosen);
        if (box != null) {
            lines.addBox(box, HudWidgets.ACCENT, WIDTH);
        }
        var hoveredBox = hoverBox && !isSame(hovered, chosen) ? box(hovered) : null;
        if (hoveredBox != null) {
            lines.addBox(hoveredBox, HOVER_COLOR, HOVER_WIDTH);
        }
    }

    public static boolean tints(@Nullable DebugSelection hovered, @Nullable DebugSelection chosen) {
        return hovered != null && hovered.sceneObject() != null && !isSame(hovered, chosen);
    }

    private static boolean isSame(@Nullable DebugSelection a, @Nullable DebugSelection b) {
        return a != null && b != null && a.key().equals(b.key());
    }
}
