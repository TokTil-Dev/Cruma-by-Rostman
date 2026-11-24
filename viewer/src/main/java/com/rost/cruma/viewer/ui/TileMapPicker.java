package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.config.Config.Viewer.Camera.Location;
import com.rost.cruma.viewer.launch.StartPlaces;
import com.rost.cruma.viewer.launch.TileBounds;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class TileMapPicker {
    private static final float AXIS = 25f;

    private static final float CORNER_EDGE = 2f;

    private static final float CAPITAL_TOP = .24f;

    private static final float BASELINE = .97f;

    private static final float PADDING = 6f;

    private static final float MAX_CELL = 120f;

    private static final float MIN_CELL = 16f;

    private static final float LABEL_MARGIN = 8f;

    private static final float MOST_ZOOM = 8f;

    private static final float LARGEST_CELL = 480f;

    private static final float ROUNDING = .01f;

    private static final String ZOOM_HELP = "\nWheel to zoom · Right-drag to pan.";

    private static final double TILE = 32768;

    private static final int PLACE_BACKING = ImColor.rgba(10, 16, 22, 205);

    private TileMapPicker() {}

    public static final class Zoom {
        private float factor = 1f;
        private float scrollX;
        private float scrollY;
        private boolean panning;

        private final float[] pointed = {Float.NaN, Float.NaN};

        public boolean isZoomedIn() {
            return factor > 1f;
        }

        private float begin(Layout layout, long columns, long rows, float gap) {
            if (ImGui.isWindowAppearing()) {
                factor = 1f;
                panning = false;
            }
            var io = ImGui.getIO();
            var frameLeft = ImGui.getCursorScreenPosX();
            var frameTop = ImGui.getCursorScreenPosY();
            var hovered = ImGui.isWindowHovered(ImGuiHoveredFlags.ChildWindows | ImGuiHoveredFlags.NoPopupHierarchy)
                    && ImGui.isMouseHoveringRect(
                            frameLeft, frameTop, frameLeft + layout.width(), frameTop + layout.height());
            var most = Math.max(1f, Math.min(MOST_ZOOM, LARGEST_CELL / layout.cell()));
            factor = Math.min(factor, most);
            var cell = cellAt(layout, factor);
            var moved = false;
            if (hovered && io.getMouseWheel() != 0) {
                var pointerX = io.getMousePosX() - frameLeft - PADDING;
                var pointerY = io.getMousePosY() - frameTop - PADDING;
                var stride = cell + gap;
                var u = (pointerX + scrollX - AXIS) / stride;
                var v = (pointerY + scrollY - AXIS) / stride;
                factor = Math.clamp(factor * (float) Math.pow(1.2, io.getMouseWheel()), 1f, most);
                cell = cellAt(layout, factor);
                stride = cell + gap;
                scrollX = AXIS + u * stride - pointerX;
                scrollY = AXIS + v * stride - pointerY;
                moved = true;
            }
            if (hovered && (ImGui.isMouseClicked(1) || ImGui.isMouseClicked(2))) {
                panning = true;
            }
            if (!ImGui.isMouseDown(1) && !ImGui.isMouseDown(2)) {
                panning = false;
            }
            if (panning && (io.getMouseDeltaX() != 0 || io.getMouseDeltaY() != 0)) {
                scrollX -= io.getMouseDeltaX();
                scrollY -= io.getMouseDeltaY();
                moved = true;
            }
            var stride = cell + gap;
            ImGui.setNextWindowContentSize(AXIS + columns * stride, AXIS + rows * stride);
            if (moved) {
                ImGui.setNextWindowScroll(Math.max(0f, Math.round(scrollX)), Math.max(0f, Math.round(scrollY)));
            }
            return cell;
        }

        private void end(float stride) {
            scrollX = ImGui.getScrollX();
            scrollY = ImGui.getScrollY();
            var io = ImGui.getIO();
            var inside = ImGui.isMouseHoveringRect(
                    ImGui.getWindowPosX(),
                    ImGui.getWindowPosY(),
                    ImGui.getWindowPosX() + ImGui.getWindowWidth(),
                    ImGui.getWindowPosY() + ImGui.getWindowHeight());
            var contentX = ImGui.getWindowPosX() + ImGui.getCursorStartPosX();
            var contentY = ImGui.getWindowPosY() + ImGui.getCursorStartPosY();
            pointed[0] = inside ? (io.getMousePosX() - contentX - AXIS) / stride : Float.NaN;
            pointed[1] = inside ? (io.getMousePosY() - contentY - AXIS) / stride : Float.NaN;
        }

        private static float cellAt(Layout layout, float factor) {
            return factor <= 1f ? layout.cell() : (float) Math.floor(layout.cell() * factor);
        }
    }

    public static Layout layout(List<String> available, long previewTexture, float maxWidth, float maxHeight) {
        return layout(available, previewTexture, maxWidth, maxHeight, 1f);
    }

    public static Layout layoutByPixel(List<String> available, long previewTexture, float maxWidth, float maxHeight) {
        return layout(available, previewTexture, maxWidth, maxHeight, hairline());
    }

    public static @Nullable String draw(
            List<String> available,
            List<String> selected,
            String search,
            Layout layout,
            long previewTexture,
            Zoom zoom) {
        var installed = Set.copyOf(available);
        var chosen = Set.copyOf(selected);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (var tile : available) {
            var coordinates = tile.split("_");
            int x = Integer.parseInt(coordinates[0]), y = Integer.parseInt(coordinates[1]);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        if (available.isEmpty()) {
            ImGui.textDisabled("No map tiles are available.");
            return null;
        }
        long columns = (long) maxX - minX + 1, rows = (long) maxY - minY + 1;
        var gap = previewTexture == 0 ? 3f : 0f;
        var cell = zoom.begin(layout, columns, rows, gap);
        var stride = cell + gap;
        var inset = inset(gap);
        var axis = AXIS;
        var name = Name.fitting(cell, maxX, maxY);
        String clicked = null;
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, PADDING, PADDING);
        ImGui.beginChild("##tile-map-grid", layout.width(), layout.height(), true, gridFlags(layout));
        ImGui.popStyleVar();
        var originX = ImGui.getCursorPosX();
        var originY = ImGui.getCursorPosY();
        var scrollX = ImGui.getScrollX();
        var scrollY = ImGui.getScrollY();
        var visibleWidth = ImGui.getWindowWidth();
        var visibleHeight = ImGui.getWindowHeight();
        if (ImGui.isWindowAppearing() && !selected.isEmpty() && selected.size() < available.size()) {
            var focus = selected.getFirst().split("_");
            ImGui.setScrollX(
                    Math.max(0f, axis + (Integer.parseInt(focus[0]) - minX + .5f) * stride - visibleWidth * .5f));
            ImGui.setScrollY(
                    Math.max(0f, axis + (Integer.parseInt(focus[1]) - minY + .5f) * stride - visibleHeight * .5f));
        }
        long firstColumn = Math.max(0, (long) ((scrollX - axis) / stride));
        long lastColumn = Math.min(columns - 1, (long) ((scrollX + visibleWidth) / stride));
        long firstRow = Math.max(0, (long) ((scrollY - axis) / stride));
        long lastRow = Math.min(rows - 1, (long) ((scrollY + visibleHeight) / stride));
        var draw = ImGui.getWindowDrawList();
        var leftEdge = ImGui.getCursorScreenPosX() + scrollX;
        var topEdge = ImGui.getCursorScreenPosY() + scrollY;
        var rightEdge = draw.getClipRectMaxX();
        var bottomEdge = draw.getClipRectMaxY();
        ImGui.pushClipRect(leftEdge + axis, topEdge + axis, rightEdge, bottomEdge, true);
        if (previewTexture != 0) {
            ImGui.setCursorPos(originX + axis, originY + axis);
            var imageLeft = ImGui.getCursorScreenPosX();
            var imageTop = ImGui.getCursorScreenPosY();
            draw.addImage(previewTexture, imageLeft, imageTop, imageLeft + columns * stride, imageTop + rows * stride);
        }
        for (long row = firstRow; row <= lastRow; row++) {
            int y = Math.toIntExact(minY + row);
            for (long column = firstColumn; column <= lastColumn; column++) {
                int x = Math.toIntExact(minX + column);
                var tile = x + "_" + y;
                ImGui.setCursorPos(originX + axis + column * stride + inset, originY + axis + row * stride + inset);
                var left = ImGui.getCursorScreenPosX();
                var top = ImGui.getCursorScreenPosY();
                var label = name.getLabel(tile);
                if (!installed.contains(tile)) {
                    ImGui.dummy(cell, cell);
                    drawCell(draw, left, top, cell, CellLook.ABSENT, previewTexture);
                    drawName(draw, left, top, cell, label, CellLook.ABSENT, previewTexture);
                    continue;
                }
                var checked = chosen.contains(tile);
                var matches = search.isEmpty() || tile.contains(search);
                ImGui.beginDisabled(!matches);
                if (ImGui.invisibleButton("##tile/" + tile, cell, cell)) {
                    clicked = tile;
                }
                var hovered = ImGui.isItemHovered();
                ImGui.endDisabled();
                var look = checked ? CellLook.CHOSEN : hovered ? CellLook.HOVERED : CellLook.PRESENT;
                drawCell(draw, left, top, cell, look, previewTexture);
                drawName(draw, left, top, cell, label, look, previewTexture);
                if (!matches) {
                    draw.addRectFilled(left, top, left + cell, top + cell, ImGui.getColorU32(.07f, .09f, .11f, .7f));
                }
                if (hovered) {
                    HudWidgets.showTooltip(
                            tile + (checked ? " · Selected. Click to remove." : " · Click to select.") + ZOOM_HELP);
                }
            }
        }
        ImGui.popClipRect();
        drawAxes(
                draw,
                minX,
                minY,
                columns,
                rows,
                cell,
                gap,
                originX,
                originY,
                scrollX,
                scrollY,
                leftEdge,
                topEdge,
                rightEdge,
                bottomEdge,
                firstColumn,
                lastColumn,
                firstRow,
                lastRow);
        zoom.end(stride);
        ImGui.endChild();
        return clicked;
    }

    public record Gesture(Kind kind, String tile, @Nullable String from, boolean modified) {
        public enum Kind {
            CLICK,
            DOUBLE_CLICK,
            BOX
        }
    }

    public static final class Pointer {
        private @Nullable String from;
        private @Nullable String over;
        private boolean box;
        private boolean modified;

        private float gridLeft;
        private float gridTop;
        private float stride;
        private int firstX;
        private int firstY;

        private @Nullable String under;

        public boolean isDragging() {
            return box;
        }

        public @Nullable String getUnder() {
            return under;
        }
    }

    public static @Nullable Gesture drawStart(
            List<String> available,
            List<String> selected,
            @Nullable String focus,
            List<Location> places,
            Layout layout,
            long previewTexture,
            Zoom zoom,
            Pointer pointer) {
        if (available.isEmpty()) {
            ImGui.textDisabled("No map tiles are available.");
            return null;
        }
        var installed = Set.copyOf(available);
        var chosen = Set.copyOf(selected);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (var tile : available) {
            var coordinates = tile.split("_");
            int x = Integer.parseInt(coordinates[0]), y = Integer.parseInt(coordinates[1]);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        long columns = (long) maxX - minX + 1, rows = (long) maxY - minY + 1;
        var gap = previewTexture == 0 ? 3f : 0f;
        var cell = zoom.begin(layout, columns, rows, gap);
        var stride = cell + gap;
        var inset = inset(gap);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, PADDING, PADDING);
        ImGui.beginChild("##tile-start-grid", layout.width(), layout.height(), true, gridFlags(layout));
        ImGui.popStyleVar();
        var originX = ImGui.getCursorPosX();
        var originY = ImGui.getCursorPosY();
        var scrollX = ImGui.getScrollX();
        var scrollY = ImGui.getScrollY();
        var visibleWidth = ImGui.getWindowWidth();
        var visibleHeight = ImGui.getWindowHeight();
        if (ImGui.isWindowAppearing() && focus != null && selected.size() < available.size()) {
            var middle = focus.split("_");
            ImGui.setScrollX(
                    Math.max(0f, AXIS + (Integer.parseInt(middle[0]) - minX + .5f) * stride - visibleWidth * .5f));
            ImGui.setScrollY(
                    Math.max(0f, AXIS + (Integer.parseInt(middle[1]) - minY + .5f) * stride - visibleHeight * .5f));
        }
        long firstColumn = Math.max(0, (long) ((scrollX - AXIS) / stride));
        long lastColumn = Math.min(columns - 1, (long) ((scrollX + visibleWidth) / stride));
        long firstRow = Math.max(0, (long) ((scrollY - AXIS) / stride));
        long lastRow = Math.min(rows - 1, (long) ((scrollY + visibleHeight) / stride));
        var draw = ImGui.getWindowDrawList();
        var leftEdge = ImGui.getCursorScreenPosX() + scrollX;
        var topEdge = ImGui.getCursorScreenPosY() + scrollY;
        var rightEdge = draw.getClipRectMaxX();
        var bottomEdge = draw.getClipRectMaxY();
        ImGui.setCursorPos(originX + AXIS, originY + AXIS);
        var gridLeft = ImGui.getCursorScreenPosX();
        var gridTop = ImGui.getCursorScreenPosY();
        pointer.gridLeft = gridLeft;
        pointer.gridTop = gridTop;
        pointer.stride = stride;
        pointer.firstX = minX;
        pointer.firstY = minY;
        ImGui.invisibleButton("##tile-start-map", Math.max(1, columns * stride), Math.max(1, rows * stride));
        var hovered = ImGui.isItemHovered();
        var io = ImGui.getIO();
        var column = (long) Math.floor((io.getMousePosX() - gridLeft) / stride);
        var row = (long) Math.floor((io.getMousePosY() - gridTop) / stride);
        var inside = hovered && column >= 0 && column < columns && row >= 0 && row < rows;
        var under = inside ? (minX + column) + "_" + (minY + row) : null;
        pointer.under = under;
        var gesture = takeGesture(pointer, hovered, under, installed, columns, rows, column, row, minX, minY);
        TileBounds box = pointer.box && pointer.from != null && pointer.over != null
                ? TileBounds.of(List.of(pointer.from, pointer.over))
                : null;
        ImGui.pushClipRect(leftEdge + AXIS, topEdge + AXIS, rightEdge, bottomEdge, true);
        if (previewTexture != 0) {
            draw.addImage(previewTexture, gridLeft, gridTop, gridLeft + columns * stride, gridTop + rows * stride);
        }
        for (long cellRow = firstRow; cellRow <= lastRow; cellRow++) {
            int y = Math.toIntExact(minY + cellRow);
            for (long cellColumn = firstColumn; cellColumn <= lastColumn; cellColumn++) {
                int x = Math.toIntExact(minX + cellColumn);
                var tile = x + "_" + y;
                var left = gridLeft + cellColumn * stride + inset;
                var top = gridTop + cellRow * stride + inset;
                var look = !installed.contains(tile)
                        ? CellLook.ABSENT
                        : chosen.contains(tile)
                                ? CellLook.CHOSEN
                                : tile.equals(under) || (box != null && box.contains(x, y))
                                        ? CellLook.HOVERED
                                        : CellLook.PRESENT;
                drawCell(draw, left, top, cell, look, previewTexture);
            }
        }
        if (box != null) {
            var boxLeft = gridLeft + (box.minX() - minX) * stride + inset;
            var boxTop = gridTop + (box.minY() - minY) * stride + inset;
            var boxRight = gridLeft + (box.maxX() - minX + 1) * stride - gap + inset;
            var boxBottom = gridTop + (box.maxY() - minY + 1) * stride - gap + inset;
            draw.addRectFilled(boxLeft, boxTop, boxRight, boxBottom, ImGui.getColorU32(.36f, .86f, .94f, .18f));
            draw.addRect(boxLeft, boxTop, boxRight, boxBottom, HudWidgets.ACCENT, 0f, 0, 2f);
        }
        drawPlaces(
                draw,
                places,
                installed,
                gridLeft,
                gridTop,
                stride,
                minX,
                minY,
                Math.max(leftEdge + AXIS, gridLeft),
                Math.max(topEdge + AXIS, gridTop),
                Math.min(rightEdge, gridLeft + columns * stride),
                Math.min(bottomEdge, gridTop + rows * stride));
        ImGui.popClipRect();
        if (under != null && !pointer.box && !ImGui.isMouseDown(0)) {
            HudWidgets.showTooltip(describeCell(under, installed.contains(under), places));
        }
        drawAxes(
                draw,
                minX,
                minY,
                columns,
                rows,
                cell,
                gap,
                originX,
                originY,
                scrollX,
                scrollY,
                leftEdge,
                topEdge,
                rightEdge,
                bottomEdge,
                firstColumn,
                lastColumn,
                firstRow,
                lastRow);
        zoom.end(stride);
        ImGui.endChild();
        return gesture;
    }

    public static float @Nullable [] drawArea(
            List<String> available, float @Nullable [] area, Layout layout, long previewTexture, Zoom zoom) {
        if (available.isEmpty()) {
            ImGui.textDisabled("No map tiles are available.");
            return null;
        }
        var installed = Set.copyOf(available);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (var tile : available) {
            var coordinates = tile.split("_");
            int x = Integer.parseInt(coordinates[0]), y = Integer.parseInt(coordinates[1]);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        long columns = (long) maxX - minX + 1, rows = (long) maxY - minY + 1;
        var gap = previewTexture == 0 ? 3f : 0f;
        var cell = zoom.begin(layout, columns, rows, gap);
        var stride = cell + gap;
        var inset = inset(gap);
        var name = Name.fitting(cell, maxX, maxY);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, PADDING, PADDING);
        ImGui.beginChild("##tile-area-grid", layout.width(), layout.height(), true, gridFlags(layout));
        ImGui.popStyleVar();
        var originX = ImGui.getCursorPosX();
        var originY = ImGui.getCursorPosY();
        var scrollX = ImGui.getScrollX();
        var scrollY = ImGui.getScrollY();
        var visibleWidth = ImGui.getWindowWidth();
        var visibleHeight = ImGui.getWindowHeight();
        var size = 32768f;
        if (ImGui.isWindowAppearing() && area != null) {
            var centerX = ((area[0] + area[2]) * .5f / size + 20 - minX) * stride;
            var centerY = ((area[1] + area[3]) * .5f / size + 18 - minY) * stride;
            ImGui.setScrollX(Math.max(0f, AXIS + centerX - visibleWidth * .5f));
            ImGui.setScrollY(Math.max(0f, AXIS + centerY - visibleHeight * .5f));
        }
        long firstColumn = Math.max(0, (long) ((scrollX - AXIS) / stride));
        long lastColumn = Math.min(columns - 1, (long) ((scrollX + visibleWidth) / stride));
        long firstRow = Math.max(0, (long) ((scrollY - AXIS) / stride));
        long lastRow = Math.min(rows - 1, (long) ((scrollY + visibleHeight) / stride));
        var draw = ImGui.getWindowDrawList();
        var leftEdge = ImGui.getCursorScreenPosX() + scrollX;
        var topEdge = ImGui.getCursorScreenPosY() + scrollY;
        var rightEdge = draw.getClipRectMaxX();
        var bottomEdge = draw.getClipRectMaxY();
        ImGui.setCursorPos(originX + AXIS, originY + AXIS);
        var gridLeft = ImGui.getCursorScreenPosX();
        var gridTop = ImGui.getCursorScreenPosY();
        ImGui.pushClipRect(leftEdge + AXIS, topEdge + AXIS, rightEdge, bottomEdge, true);
        if (previewTexture != 0) {
            draw.addImage(previewTexture, gridLeft, gridTop, gridLeft + columns * stride, gridTop + rows * stride);
        }
        for (long row = firstRow; row <= lastRow; row++) {
            int y = Math.toIntExact(minY + row);
            for (long column = firstColumn; column <= lastColumn; column++) {
                int x = Math.toIntExact(minX + column);
                var tile = x + "_" + y;
                var left = gridLeft + column * stride + inset;
                var top = gridTop + row * stride + inset;
                var look = installed.contains(tile) ? CellLook.PRESENT : CellLook.ABSENT;
                drawCell(draw, left, top, cell, look, previewTexture);
                drawName(draw, left, top, cell, name.getLabel(tile), look, previewTexture);
            }
        }
        if (area != null) {
            var frameLeft = gridLeft + (area[0] / size + 20 - minX) * stride;
            var frameTop = gridTop + (area[1] / size + 18 - minY) * stride;
            var frameRight = gridLeft + (area[2] / size + 20 - minX) * stride;
            var frameBottom = gridTop + (area[3] / size + 18 - minY) * stride;
            var middleX = (frameLeft + frameRight) * .5f;
            var middleY = (frameTop + frameBottom) * .5f;
            var halfWidth = Math.max(4f, (frameRight - frameLeft) * .5f);
            var halfHeight = Math.max(4f, (frameBottom - frameTop) * .5f);
            draw.addRectFilled(
                    middleX - halfWidth,
                    middleY - halfHeight,
                    middleX + halfWidth,
                    middleY + halfHeight,
                    ImGui.getColorU32(.36f, .86f, .94f, .22f));
            draw.addRect(
                    middleX - halfWidth,
                    middleY - halfHeight,
                    middleX + halfWidth,
                    middleY + halfHeight,
                    HudWidgets.ACCENT,
                    0f,
                    0,
                    2f);
        }
        ImGui.popClipRect();
        ImGui.setCursorScreenPos(gridLeft, gridTop);
        ImGui.invisibleButton("##tile-area-map", Math.max(1, columns * stride), Math.max(1, rows * stride));
        float[] picked = null;
        if (ImGui.isItemActive() && ImGui.isMouseDown(0)) {
            var mouseX = ImGui.getIO().getMousePosX();
            var mouseY = ImGui.getIO().getMousePosY();
            picked = new float[] {
                ((mouseX - gridLeft) / stride + minX - 20) * size, ((mouseY - gridTop) / stride + minY - 18) * size
            };
        }
        if (ImGui.isItemHovered()) {
            var column = (long) Math.floor((ImGui.getIO().getMousePosX() - gridLeft) / stride);
            var row = (long) Math.floor((ImGui.getIO().getMousePosY() - gridTop) / stride);
            HudWidgets.showTooltip((column >= 0 && column < columns && row >= 0 && row < rows
                            ? (minX + column) + "_" + (minY + row) + " · "
                            : "")
                    + "Click or drag to put the area there."
                    + ZOOM_HELP);
        }
        drawAxes(
                draw,
                minX,
                minY,
                columns,
                rows,
                cell,
                gap,
                originX,
                originY,
                scrollX,
                scrollY,
                leftEdge,
                topEdge,
                rightEdge,
                bottomEdge,
                firstColumn,
                lastColumn,
                firstRow,
                lastRow);
        zoom.end(stride);
        ImGui.endChild();
        return picked;
    }

    public static List<String> withMargin(List<String> available) {
        if (available.isEmpty()) {
            return available;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (var tile : available) {
            var coordinates = tile.split("_");
            int x = Integer.parseInt(coordinates[0]), y = Integer.parseInt(coordinates[1]);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        var result = new ArrayList<>(available);
        result.add(Math.max(0, minX - 1) + "_" + Math.max(0, minY - 1));
        result.add((maxX + 1) + "_" + (maxY + 1));
        return result;
    }

    public static @Nullable String drawPair(
            List<String> installed,
            List<String> bounds,
            @Nullable String source,
            @Nullable String target,
            List<String> targetWorld,
            boolean written,
            Layout layout,
            long previewTexture,
            Zoom zoom) {
        if (bounds.isEmpty()) {
            ImGui.textDisabled("No map tiles are available.");
            return null;
        }
        var present = Set.copyOf(installed);
        var taken = target != null && Set.copyOf(targetWorld).contains(target) && !written;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (var tile : bounds) {
            var coordinates = tile.split("_");
            int x = Integer.parseInt(coordinates[0]), y = Integer.parseInt(coordinates[1]);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        long columns = (long) maxX - minX + 1, rows = (long) maxY - minY + 1;
        var gap = previewTexture == 0 ? 3f : 0f;
        var cell = zoom.begin(layout, columns, rows, gap);
        var stride = cell + gap;
        var inset = inset(gap);
        var name = Name.fitting(cell, maxX, maxY);
        String clicked = null;
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, PADDING, PADDING);
        ImGui.beginChild("##tile-pair-grid", layout.width(), layout.height(), true, gridFlags(layout));
        ImGui.popStyleVar();
        var originX = ImGui.getCursorPosX();
        var originY = ImGui.getCursorPosY();
        var scrollX = ImGui.getScrollX();
        var scrollY = ImGui.getScrollY();
        var visibleWidth = ImGui.getWindowWidth();
        var visibleHeight = ImGui.getWindowHeight();
        long firstColumn = Math.max(0, (long) ((scrollX - AXIS) / stride));
        long lastColumn = Math.min(columns - 1, (long) ((scrollX + visibleWidth) / stride));
        long firstRow = Math.max(0, (long) ((scrollY - AXIS) / stride));
        long lastRow = Math.min(rows - 1, (long) ((scrollY + visibleHeight) / stride));
        var draw = ImGui.getWindowDrawList();
        var leftEdge = ImGui.getCursorScreenPosX() + scrollX;
        var topEdge = ImGui.getCursorScreenPosY() + scrollY;
        var rightEdge = draw.getClipRectMaxX();
        var bottomEdge = draw.getClipRectMaxY();
        ImGui.pushClipRect(leftEdge + AXIS, topEdge + AXIS, rightEdge, bottomEdge, true);
        if (previewTexture != 0) {
            int imageMinX = Integer.MAX_VALUE, imageMinY = Integer.MAX_VALUE;
            int imageMaxX = Integer.MIN_VALUE, imageMaxY = Integer.MIN_VALUE;
            for (var tile : installed) {
                var coordinates = tile.split("_");
                int x = Integer.parseInt(coordinates[0]), y = Integer.parseInt(coordinates[1]);
                imageMinX = Math.min(imageMinX, x);
                imageMaxX = Math.max(imageMaxX, x);
                imageMinY = Math.min(imageMinY, y);
                imageMaxY = Math.max(imageMaxY, y);
            }
            if (!installed.isEmpty()) {
                var imageLeft = ImGui.getCursorScreenPosX() + AXIS + (imageMinX - minX) * stride;
                var imageTop = ImGui.getCursorScreenPosY() + AXIS + (imageMinY - minY) * stride;
                draw.addImage(
                        previewTexture,
                        imageLeft,
                        imageTop,
                        imageLeft + (imageMaxX - imageMinX + 1) * stride,
                        imageTop + (imageMaxY - imageMinY + 1) * stride);
            }
        }
        for (long row = firstRow; row <= lastRow; row++) {
            int y = Math.toIntExact(minY + row);
            for (long column = firstColumn; column <= lastColumn; column++) {
                int x = Math.toIntExact(minX + column);
                var tile = x + "_" + y;
                ImGui.setCursorPos(originX + AXIS + column * stride + inset, originY + AXIS + row * stride + inset);
                var left = ImGui.getCursorScreenPosX();
                var top = ImGui.getCursorScreenPosY();
                if (ImGui.invisibleButton("##pair/" + tile, cell, cell)) {
                    clicked = tile;
                }
                var hovered = ImGui.isItemHovered();
                var installedTile = present.contains(tile);
                var isSource = tile.equals(source);
                var isTarget = tile.equals(target);
                var look = isSource
                        ? CellLook.CHOSEN
                        : hovered ? CellLook.HOVERED : installedTile ? CellLook.PRESENT : CellLook.ABSENT;
                drawCell(draw, left, top, cell, look, previewTexture);
                if (isTarget) {
                    var hair = hairline();
                    draw.addRect(
                            left + hair,
                            top + hair,
                            left + cell - hair,
                            top + cell - hair,
                            taken ? GeodataWidgets.WARNING : GeodataWidgets.OPEN,
                            0f,
                            0,
                            hair * 2);
                }
                var labelColor = isSource
                        ? HudWidgets.ACCENT
                        : isTarget
                                ? (taken ? GeodataWidgets.WARNING : GeodataWidgets.OPEN)
                                : installedTile ? HudWidgets.TEXT : ImGui.getColorU32(.50f, .56f, .61f, .35f);
                var label = name.getLabel(tile);
                var tag = isSource ? "From" : "To";
                var tagged = (isSource || isTarget)
                        && cell >= ImGui.calcTextSizeX(tag) + 10
                        && cell >= 2 * ImGui.getFontSize() + 6;
                var roomy = cell >= 3 * ImGui.getFontSize() + 4;
                var labelTop = previewTexture == 0 && (!tagged || roomy)
                        ? top + (cell - ImGui.getFontSize()) * .5f
                        : top + cell - ImGui.getFontSize() - 3;
                if (previewTexture != 0 && (installedTile || isTarget) && !label.isEmpty()) {
                    draw.addRectFilled(
                            left + 1,
                            top + cell - ImGui.getFontSize() - 4,
                            left + cell - 1,
                            top + cell - 1,
                            ImGui.getColorU32(0f, 0f, 0f, .65f));
                }
                draw.addText(left + (cell - ImGui.calcTextSizeX(label)) * .5f, labelTop, labelColor, label);
                if (tagged) {
                    draw.addText(left + 5, top + 3, labelColor, tag);
                }
                if (hovered) {
                    HudWidgets.showTooltip(tile
                            + (installedTile ? " · map" : " · empty")
                            + (isSource ? " · Source" : isTarget ? " · Target" : "")
                            + "\nClick to pick it."
                            + ZOOM_HELP);
                }
            }
        }
        ImGui.popClipRect();
        drawAxes(
                draw,
                minX,
                minY,
                columns,
                rows,
                cell,
                gap,
                originX,
                originY,
                scrollX,
                scrollY,
                leftEdge,
                topEdge,
                rightEdge,
                bottomEdge,
                firstColumn,
                lastColumn,
                firstRow,
                lastRow);
        zoom.end(stride);
        ImGui.endChild();
        return clicked;
    }

    private enum CellLook {
        PRESENT,
        ABSENT,
        HOVERED,
        CHOSEN
    }

    private static int gridFlags(Layout layout) {
        var scrolling = (layout.scrollX()
                        ? ImGuiWindowFlags.HorizontalScrollbar | ImGuiWindowFlags.AlwaysHorizontalScrollbar
                        : 0)
                | (layout.scrollY() ? ImGuiWindowFlags.AlwaysVerticalScrollbar : 0);
        return (scrolling == 0 ? ImGuiWindowFlags.NoScrollbar : scrolling) | ImGuiWindowFlags.NoScrollWithMouse;
    }

    public record Layout(float cell, float width, float height, boolean scrollX, boolean scrollY) {}

    private static Layout layout(
            List<String> available, long previewTexture, float maxWidth, float maxHeight, float step) {
        maxWidth = (float) Math.floor(maxWidth);
        maxHeight = (float) Math.floor(maxHeight);
        if (available.isEmpty()) {
            return new Layout(40f, maxWidth, ImGui.getFrameHeight(), false, false);
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (var tile : available) {
            var coordinates = tile.split("_");
            int x = Integer.parseInt(coordinates[0]), y = Integer.parseInt(coordinates[1]);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        var gap = previewTexture == 0 ? 3f : 0f;
        long columns = (long) maxX - minX + 1, rows = (long) maxY - minY + 1;
        var frame = AXIS + PADDING * 2 + 2;
        var room = Math.min((maxWidth - frame) / columns, (maxHeight - frame) / rows) - gap;
        var fit = (float) Math.floor(room / step) * step;
        var cell = Math.clamp(fit, MIN_CELL, MAX_CELL);
        var width = (float) Math.ceil(frame + columns * (cell + gap) - ROUNDING);
        var height = (float) Math.ceil(frame + rows * (cell + gap) - ROUNDING);
        var scrollbar = (float) Math.ceil(ImGui.getStyle().getScrollbarSize());
        var scrollY = height > maxHeight;
        if (scrollY) {
            width += scrollbar;
        }
        var scrollX = width > maxWidth;
        if (scrollX) {
            height += scrollbar;
            if (!scrollY && height > maxHeight) {
                scrollY = true;
                width += scrollbar;
            }
        }
        return new Layout(cell, Math.min(width, maxWidth), Math.min(height, maxHeight), scrollX, scrollY);
    }

    private static float inset(float gap) {
        var pixel = hairline();
        return (float) Math.floor(gap * .5f / pixel) * pixel;
    }

    private static float hairline() {
        return 1f / Math.max(1f, ImGui.getIO().getDisplayFramebufferScaleX());
    }

    private static void drawCell(
            ImDrawList draw, float left, float top, float cell, CellLook look, long previewTexture) {
        var image = previewTexture != 0;
        var fill = switch (look) {
            case PRESENT -> ImGui.getColorU32(.11f, .15f, .18f, image ? .12f : 1f);
            case ABSENT -> ImGui.getColorU32(.09f, .12f, .15f, image ? .92f : .5f);
            case HOVERED -> ImGui.getColorU32(.20f, .27f, .33f, image ? .35f : 1f);
            case CHOSEN -> ImGui.getColorU32(.13f, .55f, .65f, image ? .38f : 1f);
        };
        draw.addRectFilled(left, top, left + cell, top + cell, fill);
        var hair = hairline();
        var width = look == CellLook.CHOSEN ? hair * 2 : hair;
        var border = switch (look) {
            case PRESENT, HOVERED -> HudWidgets.BORDER;
            case ABSENT -> ImGui.getColorU32(.24f, .30f, .35f, .35f);
            case CHOSEN -> HudWidgets.ACCENT;
        };
        draw.addRect(
                left + width / 2,
                top + width / 2,
                left + cell - width / 2,
                top + cell - width / 2,
                border,
                0f,
                0,
                width);
    }

    private static void drawName(
            ImDrawList draw, float left, float top, float cell, String label, CellLook look, long previewTexture) {
        if (label.isEmpty()) {
            return;
        }
        var color = switch (look) {
            case ABSENT -> ImGui.getColorU32(.50f, .56f, .61f, .35f);
            case CHOSEN -> previewTexture != 0 ? ImGui.getColorU32(.36f, .86f, .94f, 1f) : HudWidgets.TEXT;
            default -> HudWidgets.TEXT;
        };
        var labelLeft = left + (cell - ImGui.calcTextSizeX(label)) * .5f;
        if (previewTexture == 0) {
            draw.addText(labelLeft, top + (cell - ImGui.getFontSize()) * .5f, color, label);
            return;
        }
        if (look != CellLook.ABSENT) {
            draw.addRectFilled(
                    left + 1,
                    top + cell - ImGui.getFontSize() - 4,
                    left + cell - 1,
                    top + cell - 1,
                    ImGui.getColorU32(0f, 0f, 0f, .65f));
        }
        draw.addText(labelLeft, top + cell - ImGui.getFontSize() - 3, color, label);
    }

    private enum Name {
        FULL,
        X,
        NONE;

        static Name fitting(float cell, int maxX, int maxY) {
            var digit = 0f;
            for (var c = '0'; c <= '9'; c++) {
                digit = Math.max(digit, ImGui.calcTextSizeX(String.valueOf(c)));
            }
            var x = Integer.toString(maxX).length() * digit;
            var full = x + ImGui.calcTextSizeX("_") + Integer.toString(maxY).length() * digit;
            return full + LABEL_MARGIN <= cell ? FULL : x + LABEL_MARGIN <= cell ? X : NONE;
        }

        String getLabel(String tile) {
            return switch (this) {
                case FULL -> tile;
                case X -> tile.substring(0, tile.indexOf('_'));
                case NONE -> "";
            };
        }
    }

    private static @Nullable Gesture takeGesture(
            Pointer pointer,
            boolean hovered,
            @Nullable String under,
            Set<String> installed,
            long columns,
            long rows,
            long column,
            long row,
            int minX,
            int minY) {
        var io = ImGui.getIO();
        var modified = io.getKeyShift() || io.getKeyCtrl() || io.getKeySuper();
        if (hovered && under != null && ImGui.isMouseDoubleClicked(0) && !modified) {
            pointer.from = null;
            pointer.box = false;
            return installed.contains(under) ? new Gesture(Gesture.Kind.DOUBLE_CLICK, under, null, false) : null;
        }
        if (hovered && under != null && ImGui.isMouseClicked(0)) {
            pointer.from = under;
            pointer.over = under;
            pointer.box = false;
            pointer.modified = modified;
        }
        if (pointer.from == null) {
            return null;
        }
        if (ImGui.isMouseDown(0)) {
            var heldColumn = Math.clamp(column, 0, columns - 1);
            var heldRow = Math.clamp(row, 0, rows - 1);
            pointer.over = (minX + heldColumn) + "_" + (minY + heldRow);
            pointer.box |= !pointer.over.equals(pointer.from);
            return null;
        }
        var from = pointer.from;
        var over = pointer.over;
        var box = pointer.box;
        pointer.from = null;
        pointer.box = false;
        if (box && over != null) {
            return new Gesture(Gesture.Kind.BOX, over, from, pointer.modified);
        }
        return installed.contains(from) ? new Gesture(Gesture.Kind.CLICK, from, null, pointer.modified) : null;
    }

    private static void drawPlaces(
            ImDrawList draw,
            List<Location> places,
            Set<String> installed,
            float gridLeft,
            float gridTop,
            float stride,
            int minX,
            int minY,
            float viewLeft,
            float viewTop,
            float viewRight,
            float viewBottom) {
        var shown = new ArrayList<float[]>();
        var points = new ArrayList<float[]>();
        for (var place : places) {
            if (!installed.contains(StartPlaces.tileOf(place))) {
                points.add(null);
                continue;
            }
            var x = gridLeft + (float) ((place.x() / TILE + 20 - minX) * stride);
            var y = gridTop + (float) ((place.y() / TILE + 18 - minY) * stride);
            points.add(new float[] {x, y});
            if (x >= viewLeft && x <= viewRight && y >= viewTop && y <= viewBottom) {
                draw.addCircleFilled(x, y, 3.5f, PLACE_BACKING, 10);
                draw.addCircleFilled(x, y, 2f, HudWidgets.TEXT, 10);
            }
        }
        var font = ImGui.getFontSize();
        for (var index = 0; index < places.size(); index++) {
            var point = points.get(index);
            if (point == null) {
                continue;
            }
            var label = StartPlaces.shortName(places.get(index).name());
            var width = ImGui.calcTextSizeX(label);
            var left = point[0] - width * .5f - 4;
            var right = point[0] + width * .5f + 4;
            var top = point[1] - font - 9;
            var bottom = point[1] - 5;
            if (left < viewLeft || right > viewRight || top < viewTop || bottom > viewBottom) {
                continue;
            }
            var free = true;
            for (var taken : shown) {
                if (left < taken[2] && right > taken[0] && top < taken[3] && bottom > taken[1]) {
                    free = false;
                    break;
                }
            }
            if (!free) {
                continue;
            }
            shown.add(new float[] {left, top, right, bottom});
            draw.addRectFilled(left, top, right, bottom, PLACE_BACKING, 4f);
            draw.addText(left + 4, top + 2, HudWidgets.TEXT, label);
        }
    }

    private static String describeCell(String tile, boolean installed, List<Location> places) {
        if (!installed) {
            return tile + " · Not in this client" + ZOOM_HELP;
        }
        var named = new ArrayList<String>();
        for (var place : places) {
            if (named.size() < 3 && StartPlaces.tileOf(place).equals(tile)) {
                named.add(place.name());
            }
        }
        return tile
                + (named.isEmpty() ? "" : " · " + String.join(", ", named))
                + "\nClick to choose it, double-click to open it at once. Shift, Ctrl or Cmd adds it or takes it"
                + " away."
                + ZOOM_HELP;
    }

    private static void drawAxes(
            ImDrawList draw,
            int minX,
            int minY,
            long columns,
            long rows,
            float cell,
            float gap,
            float originX,
            float originY,
            float scrollX,
            float scrollY,
            float leftEdge,
            float topEdge,
            float rightEdge,
            float bottomEdge,
            long firstColumn,
            long lastColumn,
            long firstRow,
            long lastRow) {
        var axis = AXIS;
        var stride = cell + gap;
        var middle = HudWidgets.textMiddle(0);
        ImGui.pushClipRect(leftEdge + axis, topEdge, rightEdge, topEdge + axis, true);
        for (long column = firstColumn; column <= lastColumn; column++) {
            var label = Integer.toString(Math.toIntExact(minX + column));
            ImGui.setCursorPos(originX + axis + column * stride, originY + scrollY);
            var left = ImGui.getCursorScreenPosX();
            var top = ImGui.getCursorScreenPosY();
            draw.addRectFilled(left - gap, top, left + cell + gap, top + axis, HudWidgets.BACKGROUND);
            var labelLeft = left + (stride - ImGui.calcTextSizeX(label)) * .5f;
            if (labelLeft >= leftEdge + axis && labelLeft + ImGui.calcTextSizeX(label) <= rightEdge) {
                draw.addText(labelLeft, top + axis * .5f - middle, HudWidgets.MUTED, label);
            }
        }
        ImGui.popClipRect();
        ImGui.pushClipRect(leftEdge, topEdge + axis, leftEdge + axis, bottomEdge, true);
        for (long row = firstRow; row <= lastRow; row++) {
            var label = Integer.toString(Math.toIntExact(minY + row));
            ImGui.setCursorPos(originX + scrollX, originY + axis + row * stride);
            var left = ImGui.getCursorScreenPosX();
            var top = ImGui.getCursorScreenPosY();
            draw.addRectFilled(left, top - gap, left + axis, top + cell + gap, HudWidgets.BACKGROUND);
            var labelTop = top + stride * .5f - middle;
            if (labelTop >= topEdge + axis && labelTop + ImGui.getFontSize() <= bottomEdge) {
                draw.addText(left + (axis - ImGui.calcTextSizeX(label)) * .5f, labelTop, HudWidgets.MUTED, label);
            }
        }
        ImGui.popClipRect();
        ImGui.setCursorPos(originX + scrollX, originY + scrollY);
        var cornerX = ImGui.getCursorScreenPosX();
        var cornerY = ImGui.getCursorScreenPosY();
        draw.addRectFilled(cornerX, cornerY, cornerX + axis, cornerY + axis, HudWidgets.BACKGROUND);
        draw.addLine(cornerX, cornerY, cornerX + axis, cornerY + axis, HudWidgets.BORDER, hairline());
        var size = ImGui.getFontSize();
        draw.addText(
                cornerX + axis - CORNER_EDGE - ImGui.calcTextSizeX("X"),
                cornerY + CORNER_EDGE - size * CAPITAL_TOP,
                HudWidgets.MUTED,
                "X");
        draw.addText(cornerX + CORNER_EDGE, cornerY + axis - CORNER_EDGE - size * BASELINE, HudWidgets.MUTED, "Y");
        ImGui.setCursorPos(originX, originY);
        ImGui.dummy(axis + columns * stride, axis + rows * stride);
    }
}
