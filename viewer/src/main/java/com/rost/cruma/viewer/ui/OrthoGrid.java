package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.world.WorldDimensions;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.ui.GeodataWorkspace.Viewport;
import glm_.vec3.Vec3;
import imgui.ImDrawList;
import imgui.ImGui;

public final class OrthoGrid {
    private static final String[] AXES = {"X", "Y", "Z"};
    private static final int FAINT_LINE = Motion.alpha(HudWidgets.MUTED, .2f);
    private static final int STRONG_LINE = Motion.alpha(HudWidgets.MUTED, .32f);
    private static final int TILE_LINE = Motion.alpha(HudWidgets.MUTED, .7f);
    private static final int FAINT_SHADE = Motion.alpha(HudWidgets.BACKGROUND, .3f);
    private static final int STRONG_SHADE = Motion.alpha(HudWidgets.BACKGROUND, .4f);
    private static final int TILE_SHADE = Motion.alpha(HudWidgets.BACKGROUND, .7f);
    private static final float WIDE_TILE_POINTS = 240f;
    private static final int LABEL_BACKING = Motion.alpha(HudWidgets.BACKGROUND, .85f);
    private static final float LABEL_PADDING = 4f;
    private static final float LABEL_GAP = 8f;
    private static final float EDGE_INSET = 4f;

    private OrthoGrid() {}

    public static void draw(
            ImDrawList draw,
            Camera camera,
            float screenWidth,
            float screenHeight,
            Viewport viewport,
            float rowLeft,
            float columnTop,
            float columnBottom,
            int density,
            int fixed) {
        var left = viewport.left();
        var top = viewport.top();
        var right = viewport.right();
        var bottom = viewport.bottom();
        if (right <= left || bottom <= top || screenWidth <= 0 || screenHeight <= 0) {
            return;
        }
        var view = camera.getView();
        var across = view.getRightAxis();
        var along = view.getUpAxis();
        var topLeft = camera.getCursorRayOrigin(-1, 1);
        var bottomRight = camera.getCursorRayOrigin(1, -1);
        var worldLeft = (double) component(topLeft, across);
        var worldTop = (double) component(topLeft, along);
        var unitsPerPointX = (component(bottomRight, across) - worldLeft) / screenWidth;
        var unitsPerPointY = (component(bottomRight, along) - worldTop) / screenHeight;
        var step = OrthoGridSteps.step(unitsPerPointX, density, fixed);
        var labelStep = OrthoGridSteps.labelStep(step, unitsPerPointX);
        var height = ImGui.getFontSize() + 2 * LABEL_PADDING - 2;
        var rowTop = bottom - EDGE_INSET - height;
        var columnLeft = left + GeodataWidgets.BLOCK;
        var letterTop = Math.max(top, columnTop) + EDGE_INSET;

        draw.pushClipRect(left, top, right, bottom, true);
        var fromX = worldLeft + left * unitsPerPointX;
        var toX = worldLeft + right * unitsPerPointX;
        var fromY = Math.min(worldTop + top * unitsPerPointY, worldTop + bottom * unitsPerPointY);
        var toY = Math.max(worldTop + top * unitsPerPointY, worldTop + bottom * unitsPerPointY);
        for (var line = OrthoGridSteps.first(fromX, step); line <= toX; line += step) {
            var strong = OrthoGridSteps.isStrong(line, step);
            var x = thin((line - worldLeft) / unitsPerPointX);
            draw.addLine(x + 1, top, x + 1, bottom, strong ? STRONG_SHADE : FAINT_SHADE, 1f);
            draw.addLine(x, top, x, bottom, strong ? STRONG_LINE : FAINT_LINE, 1f);
        }
        for (var line = OrthoGridSteps.first(fromY, step); line <= toY; line += step) {
            var strong = OrthoGridSteps.isStrong(line, step);
            var y = thin((line - worldTop) / unitsPerPointY);
            draw.addLine(left, y + 1, right, y + 1, strong ? STRONG_SHADE : FAINT_SHADE, 1f);
            draw.addLine(left, y, right, y, strong ? STRONG_LINE : FAINT_LINE, 1f);
        }
        var wide = WorldDimensions.TILE_SIZE / unitsPerPointX >= WIDE_TILE_POINTS;
        var thickness = wide ? 2f : 1f;
        for (var line = OrthoGridSteps.first(fromX, WorldDimensions.TILE_SIZE);
                line <= toX;
                line += WorldDimensions.TILE_SIZE) {
            var x = tileLine((line - worldLeft) / unitsPerPointX, wide);
            draw.addLine(x + thickness, top, x + thickness, bottom, TILE_SHADE, 1f);
            draw.addLine(x, top, x, bottom, TILE_LINE, thickness);
        }
        if (along != 2) {
            for (var line = OrthoGridSteps.first(fromY, WorldDimensions.TILE_SIZE);
                    line <= toY;
                    line += WorldDimensions.TILE_SIZE) {
                var y = tileLine((line - worldTop) / unitsPerPointY, wide);
                draw.addLine(left, y + thickness, right, y + thickness, TILE_SHADE, 1f);
                draw.addLine(left, y, right, y, TILE_LINE, thickness);
            }
        }

        letter(draw, AXES[along], columnLeft, letterTop, height);
        var acrossWidth = ImGui.calcTextSizeX(AXES[across]) + 2 * LABEL_PADDING;
        letter(draw, AXES[across], right - EDGE_INSET - acrossWidth, rowTop, height);
        var lastRight = Math.max(left, rowLeft);
        for (var line = OrthoGridSteps.first(fromX, labelStep); line <= toX; line += labelStep) {
            var text = OrthoGridSteps.label(line);
            var width = ImGui.calcTextSizeX(text) + 2 * LABEL_PADDING;
            var x = (float) ((line - worldLeft) / unitsPerPointX) - width * .5f;
            if (x < lastRight + LABEL_GAP || x + width > right - 2 * EDGE_INSET - acrossWidth) {
                continue;
            }
            label(draw, text, x, rowTop, width, height, OrthoGridSteps.isStrong(line, step));
            lastRight = x + width;
        }
        var columnEnd = Math.min(rowTop, columnBottom);
        for (var line = OrthoGridSteps.first(fromY, labelStep); line <= toY; line += labelStep) {
            var y = (float) ((line - worldTop) / unitsPerPointY) - height * .5f;
            if (y < letterTop + height + LABEL_GAP * .5f || y + height > columnEnd - LABEL_GAP * .5f) {
                continue;
            }
            var text = OrthoGridSteps.label(line);
            label(
                    draw,
                    text,
                    columnLeft,
                    y,
                    ImGui.calcTextSizeX(text) + 2 * LABEL_PADDING,
                    height,
                    OrthoGridSteps.isStrong(line, step));
        }
        draw.popClipRect();
    }

    public static long step(Camera camera, float screenWidth, int density, int fixed) {
        var left = camera.getCursorRayOrigin(-1, 1);
        var right = camera.getCursorRayOrigin(1, -1);
        var across = camera.getView().getRightAxis();
        return OrthoGridSteps.step(
                (component(right, across) - component(left, across)) / (double) screenWidth, density, fixed);
    }

    private static void letter(ImDrawList draw, String text, float x, float y, float height) {
        var width = ImGui.calcTextSizeX(text) + 2 * LABEL_PADDING;
        draw.addRectFilled(x, y, x + width, y + height, LABEL_BACKING, 4f);
        draw.addText(x + LABEL_PADDING, y + LABEL_PADDING - 2, HudWidgets.ACCENT, text);
    }

    private static void label(
            ImDrawList draw, String text, float x, float y, float width, float height, boolean strong) {
        draw.addRectFilled(x, y, x + width, y + height, LABEL_BACKING, 4f);
        draw.addText(x + LABEL_PADDING, y + LABEL_PADDING - 2, strong ? HudWidgets.TEXT : HudWidgets.MUTED, text);
    }

    private static float thin(double place) {
        return (float) Math.floor(place) + .5f;
    }

    private static float tileLine(double place, boolean wide) {
        return wide ? Math.round((float) place) : thin(place);
    }

    private static float component(Vec3 vector, int axis) {
        return axis == 0 ? vector.getX() : axis == 1 ? vector.getY() : vector.getZ();
    }
}
