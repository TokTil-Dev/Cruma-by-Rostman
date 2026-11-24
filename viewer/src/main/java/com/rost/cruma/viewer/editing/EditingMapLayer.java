package com.rost.cruma.viewer.editing;

import com.rost.cruma.viewer.config.Config.Viewer.Camera.Location;
import com.rost.cruma.viewer.editing.EditingSession.Change;
import com.rost.cruma.viewer.editing.EditingSession.ChangeKind;
import com.rost.cruma.viewer.ui.GeodataWidgets;
import com.rost.cruma.viewer.ui.HudWidgets;
import com.rost.cruma.viewer.ui.MapLayer;
import com.rost.cruma.viewer.ui.MapTransform;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

public final class EditingMapLayer implements MapLayer {
    private static final float PICK = 8;
    private static final float ABOVE = 800;
    private final Supplier<@Nullable EditingPanel> panel;
    private boolean shown = true;

    int lastDrawn;

    final float[] lastDot = new float[2];

    public EditingMapLayer(Supplier<@Nullable EditingPanel> panel) {
        this.panel = panel;
    }

    @Override
    public String name() {
        return "Edit changes";
    }

    @Override
    public boolean isAvailable() {
        return panel.get() != null;
    }

    @Override
    public boolean isShown() {
        return shown;
    }

    @Override
    public void show(boolean value) {
        shown = value;
    }

    @Override
    public void draw(ImDrawList draw, MapTransform map) {
        lastDrawn = 0;
        var editing = panel.get();
        if (editing == null) {
            return;
        }
        var fill = ImColor.rgba(255, 200, 90, 40);
        var edge = ImColor.rgba(255, 200, 90, 200);
        for (var rect : editing.getChangedTerrain()) {
            if (!map.isVisible(rect[0], rect[1], rect[2], rect[3])) {
                continue;
            }
            var left = Math.min(map.x(rect[0]), map.x(rect[2]));
            var right = Math.max(map.x(rect[0]), map.x(rect[2]));
            var top = Math.min(map.y(rect[1]), map.y(rect[3]));
            var bottom = Math.max(map.y(rect[1]), map.y(rect[3]));
            lastDrawn++;
            draw.addRectFilled(left, top, right, bottom, fill);
            draw.addRect(left, top, right, bottom, edge, 0, 0, 1);
            hatch(draw, left, top, right, bottom, edge);
        }
        var shadow = ImColor.rgba(8, 14, 20, 200);
        for (var change : editing.getSession().getChanges()) {
            var location = change.location();
            var x = map.x(location.x());
            var y = map.y(location.y());
            if (!map.isInView(x, y)) {
                continue;
            }
            lastDrawn++;
            lastDot[0] = x;
            lastDot[1] = y;
            if (change.kinds().contains(ChangeKind.DELETED)) {
                draw.addCircle(x, y, 4.5f, shadow, 12, 3);
                draw.addCircle(x, y, 4, GeodataWidgets.WARNING, 12, 1.5f);
            } else {
                draw.addCircleFilled(x, y, 5.5f, shadow, 12);
                draw.addCircleFilled(x, y, 4, GeodataWidgets.WARNING, 12);
            }
        }
    }

    @Override
    public @Nullable String getTooltipAt(MapTransform map, float x, float y) {
        var editing = panel.get();
        if (editing == null) {
            return null;
        }
        var change = changeAt(editing, map, x, y);
        if (change != null) {
            return EditingSession.shortName(change.path()) + "\n" + change.detail() + "\n\nClick to fly there.";
        }
        var rows = editing.getTerrainChanges();
        for (var rect : editing.getChangedTerrain()) {
            if (isInside(map, rect, x, y)) {
                var detail =
                        rows.isEmpty() ? "Changed Terrain" : rows.getFirst().detail();
                return "Changed Terrain\n" + detail + "\n\nClick to fly there.";
            }
        }
        return null;
    }

    @Override
    public @Nullable Click getClickAt(MapTransform map, float x, float y) {
        var editing = panel.get();
        if (editing == null) {
            return null;
        }
        var change = changeAt(editing, map, x, y);
        if (change != null) {
            var location = change.location();
            return new Click(new Location(change.map(), location.x(), location.y(), location.z() + ABOVE), true);
        }
        for (var rect : editing.getChangedTerrain()) {
            if (isInside(map, rect, x, y)) {
                var cx = (rect[0] + rect[2]) * .5f;
                var cy = (rect[1] + rect[3]) * .5f;
                var ground = editing.getGroundProbe().getCurrent(cx, cy);
                return new Click(
                        new Location(
                                editing.getGroundProbe().getMapAt(cx, cy),
                                cx,
                                cy,
                                (ground == null ? 0 : ground) + ABOVE * 2),
                        true);
            }
        }
        return null;
    }

    @Override
    public float drawLegend(ImDrawList draw, float left, float bottom) {
        var editing = panel.get();
        if (editing == null) {
            return 0;
        }
        var actors = !editing.getSession().getChanges().isEmpty();
        var terrain = !editing.getChangedTerrain().isEmpty();
        if (!actors && !terrain) {
            return 0;
        }
        var line = ImGui.getFontSize() + 4;
        var entries = (actors ? 1 : 0) + (terrain ? 1 : 0);
        var width = Math.max(
                        ImGui.calcTextSizeX("Edit changes"),
                        18 + Math.max(ImGui.calcTextSizeX("Changed actor"), ImGui.calcTextSizeX("Changed Terrain")))
                + 16;
        var height = (entries + 1) * line + 12;
        var top = bottom - height;
        draw.addRectFilled(left, top, left + width, bottom, ImColor.rgba(11, 18, 23, 215), 6f);
        var y = top + 6;
        draw.addText(left + 8, y, GeodataWidgets.MUTED, "Edit changes");
        y += line;
        if (actors) {
            draw.addCircleFilled(left + 13, HudWidgets.textMiddle(y), 4, GeodataWidgets.WARNING, 12);
            draw.addText(left + 26, y, HudWidgets.TEXT, "Changed actor");
            y += line;
        }
        if (terrain) {
            var middle = HudWidgets.textMiddle(y);
            draw.addRectFilled(left + 8, middle - 5, left + 18, middle + 5, ImColor.rgba(255, 200, 90, 40));
            draw.addRect(left + 8, middle - 5, left + 18, middle + 5, ImColor.rgba(255, 200, 90, 200), 0, 0, 1);
            hatch(draw, left + 8, middle - 5, left + 18, middle + 5, ImColor.rgba(255, 200, 90, 200));
            draw.addText(left + 26, y, HudWidgets.TEXT, "Changed Terrain");
        }
        return height + 6;
    }

    private static Change changeAt(EditingPanel editing, MapTransform map, float x, float y) {
        Change nearest = null;
        var best = PICK;
        for (var change : editing.getSession().getChanges()) {
            var location = change.location();
            var distance = (float) Math.hypot(map.x(location.x()) - x, map.y(location.y()) - y);
            if (distance <= best) {
                best = distance;
                nearest = change;
            }
        }
        return nearest;
    }

    private static boolean isInside(MapTransform map, float[] rect, float x, float y) {
        var left = Math.min(map.x(rect[0]), map.x(rect[2]));
        var right = Math.max(map.x(rect[0]), map.x(rect[2]));
        var top = Math.min(map.y(rect[1]), map.y(rect[3]));
        var bottom = Math.max(map.y(rect[1]), map.y(rect[3]));
        return x >= left - 2 && x <= right + 2 && y >= top - 2 && y <= bottom + 2;
    }

    private static void hatch(ImDrawList draw, float left, float top, float right, float bottom, int color) {
        if (right - left < 6 || bottom - top < 6) {
            return;
        }
        draw.pushClipRect(left, top, right, bottom, true);
        var size = (right - left) + (bottom - top);
        for (var offset = 6f; offset < size; offset += 6) {
            draw.addLine(left + offset, top, left, top + offset, color, 1);
        }
        draw.popClipRect();
    }
}
