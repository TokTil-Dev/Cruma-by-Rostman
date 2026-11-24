package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geodata.GeoFormat;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.viewer.config.Config.Viewer.Camera.Location;
import com.rost.cruma.viewer.geodata.GeoRegionCatalog;
import com.rost.cruma.viewer.geodata.GeoRegionCatalog.Catalog;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiHoveredFlags;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

final class GeodataMapLayer implements MapLayer {
    static final int STALE = ImColor.rgb(255, 98, 86);

    private final GeodataWorkspace geodata;
    private boolean shown = true;
    private @Nullable GeoTile menuTile;

    int lastDrawn;

    long lastDrawNanos;

    GeodataMapLayer(GeodataWorkspace geodata) {
        this.geodata = geodata;
    }

    enum State {
        EDITED("Edited", ImColor.rgb(255, 200, 90)),
        DIFFERS("Differs from B", ImColor.rgb(255, 115, 185)),
        BUILT("Built from the client", ImColor.rgb(92, 219, 241)),
        L2J("L2J file", ImColor.rgb(180, 140, 255)),
        SOURCE("Region file", ImColor.rgb(110, 222, 140)),
        NONE("No file", 0);

        final String label;
        final int color;

        State(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    enum Action {
        REBUILD("Rebuild from the client"),
        REVERT("Revert to the source file"),
        TAKE_FROM_B("Take this region from B"),
        EXPORT("Export only this region"),
        OPEN_FOLDER("Open its file location");

        final String label;

        Action(String label) {
            this.label = label;
        }
    }

    record Request(Action action, GeoTile tile) {}

    static String size(long bytes) {
        return bytes >= 1 << 20
                ? String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
                : String.format(Locale.ROOT, "%,d KB", Math.max(1, bytes / 1024));
    }

    @Override
    public String name() {
        return "Geodata regions";
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
        var started = System.nanoTime();
        lastDrawn = 0;
        var catalog = geodata.getRegionCatalog();
        if (catalog == null) {
            return;
        }
        var thin = map.scale() * GeoTile.WORLD_SIZE < 24;
        for (var index = 0; index < GeoRegionCatalog.COLUMNS * GeoRegionCatalog.ROWS; index++) {
            var tile = GeoRegionCatalog.tileAt(index);
            var left = map.x(tile.worldMinX());
            var right = map.x(tile.worldMinX() + GeoTile.WORLD_SIZE);
            var top = map.y(tile.worldMinY());
            var bottom = map.y(tile.worldMinY() + GeoTile.WORLD_SIZE);
            if (right < map.clipLeft() || left > map.clipRight() || bottom < map.clipTop() || top > map.clipBottom()) {
                continue;
            }
            var state = getState(catalog, tile);
            if (state == State.NONE) {
                continue;
            }
            lastDrawn++;
            draw.addRectFilled(
                    left + 1,
                    top + 1,
                    right - 1,
                    bottom - 1,
                    withAlpha(state.color, state == State.SOURCE ? .16f : .26f));
            draw.addRect(
                    left + 1,
                    top + 1,
                    right - 1,
                    bottom - 1,
                    withAlpha(state.color, .8f),
                    0,
                    0,
                    state == State.EDITED ? 2.5f : 1f);
            if (catalog.isPathnodeStale(tile) || geodata.getEditedCells(tile) > 0 && catalog.pathnodeModified() > 0) {
                var size = thin ? 4 : Math.min(12, (right - left) * .22f);
                draw.addTriangleFilled(right - 1 - size, top + 1, right - 1, top + 1, right - 1, top + 1 + size, STALE);
            }
        }
        lastDrawNanos = System.nanoTime() - started;
    }

    State getState(Catalog catalog, GeoTile tile) {
        var region = catalog.region(tile);
        if (geodata.getEditedCells(tile) > 0) {
            return State.EDITED;
        }
        if (catalog.compared() && catalog.differs().contains(tile)) {
            return State.DIFFERS;
        }
        if (!region.isPresent()) {
            return State.NONE;
        }
        if (region.built()) {
            return State.BUILT;
        }
        return region.format() == GeoFormat.L2J ? State.L2J : State.SOURCE;
    }

    @Override
    public @Nullable String getTooltipAt(MapTransform map, float x, float y) {
        var catalog = geodata.getRegionCatalog();
        var tile = tileAt(map, x, y);
        if (catalog == null || tile == null) {
            return null;
        }
        var region = catalog.region(tile);
        var edited = geodata.getEditedCells(tile);
        if (!region.isPresent()
                && edited == 0
                && !(catalog.compared() && catalog.differs().contains(tile))) {
            return null;
        }
        var text = new StringBuilder(tile.x() + "_" + tile.y());
        if (region.isPresent()) {
            text.append('\n')
                    .append(region.format() == GeoFormat.L2J ? "L2J file" : "L2OFF file")
                    .append(region.built() ? ", built from the client" : "")
                    .append(region.layers() >= 0 ? String.format(Locale.ROOT, " · %,d layers", region.layers()) : "")
                    .append(" · ")
                    .append(size(region.bytes()));
        } else {
            text.append("\nNo file in this folder");
        }
        if (edited > 0) {
            text.append(String.format(
                    Locale.ROOT,
                    "\nEdited: %,d %s %s",
                    edited,
                    edited == 1 ? "cell" : "cells",
                    geodata.hasUnsavedEdits() ? "not saved" : "saved"));
        }
        if (catalog.compared() && catalog.differs().contains(tile)) {
            text.append("\nFolder B has another file");
        }
        if (catalog.isPathnodeStale(tile) || edited > 0 && catalog.pathnodeModified() > 0) {
            text.append("\nPathnode graph is older than the region");
        }
        return text.append("\n\nClick to fly to its centre. Right-click for region actions.")
                .toString();
    }

    @Override
    public @Nullable Click getClickAt(MapTransform map, float x, float y) {
        var catalog = geodata.getRegionCatalog();
        var tile = tileAt(map, x, y);
        if (catalog == null || tile == null || getState(catalog, tile) == State.NONE) {
            return null;
        }
        return new Click(
                new Location(
                        tile.x() + "_" + tile.y(),
                        tile.worldMinX() + GeoTile.WORLD_SIZE / 2f,
                        tile.worldMinY() + GeoTile.WORLD_SIZE / 2f,
                        geodata.getCameraHeight()),
                true);
    }

    @Override
    public boolean openMenu(MapTransform map, float x, float y) {
        var catalog = geodata.getRegionCatalog();
        var tile = tileAt(map, x, y);
        if (catalog == null || tile == null) {
            return false;
        }
        menuTile = tile;
        return true;
    }

    @Override
    public @Nullable Click getMenu() {
        var tile = menuTile;
        var catalog = geodata.getRegionCatalog();
        if (tile == null || catalog == null) {
            return null;
        }
        ImGui.textColored(HudWidgets.MUTED, "REGION " + tile.x() + "_" + tile.y());
        GeodataWidgets.space(GeodataWidgets.ROW);
        Click chosen = null;
        for (var action : Action.values()) {
            var reason = geodata.getUnavailable(action, tile);
            if (ImGui.menuItem(action.label, "", false, reason.isEmpty())) {
                geodata.requestRegion(new Request(action, tile));
                chosen = action == Action.EXPORT || action == Action.OPEN_FOLDER
                        ? new Click(null, false)
                        : Click.HANDLED;
            }
            if (!reason.isEmpty() && ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
                HudWidgets.showTooltip(reason);
            }
        }
        return chosen;
    }

    @Override
    public void drawOptions() {
        GeodataWidgets.space(GeodataWidgets.ROW);
        var catalog = geodata.getRegionCatalog();
        ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.MUTED);
        ImGui.textUnformatted(
                catalog == null
                        ? "Choose a geodata folder in the Geodata panel."
                        : catalog.count() + " region files in "
                                + catalog.folder().getFileName());
        ImGui.popStyleColor();
    }

    @Override
    public float drawLegend(ImDrawList draw, float left, float bottom) {
        var catalog = geodata.getRegionCatalog();
        if (catalog == null) {
            return 0;
        }
        var present = new boolean[State.values().length];
        var stale = false;
        for (var index = 0; index < GeoRegionCatalog.COLUMNS * GeoRegionCatalog.ROWS; index++) {
            var tile = GeoRegionCatalog.tileAt(index);
            present[getState(catalog, tile).ordinal()] = true;
            stale |=
                    catalog.isPathnodeStale(tile) || geodata.getEditedCells(tile) > 0 && catalog.pathnodeModified() > 0;
        }
        var line = ImGui.getFontSize() + 4;
        var entries = 0;
        var width = ImGui.calcTextSizeX("Geodata regions");
        for (var state : State.values()) {
            if (state != State.NONE && present[state.ordinal()]) {
                entries++;
                width = Math.max(width, 18 + ImGui.calcTextSizeX(state.label));
            }
        }
        if (stale) {
            entries++;
            width = Math.max(width, 18 + ImGui.calcTextSizeX("Pathnode older than the file"));
        }
        if (entries == 0) {
            return 0;
        }
        width += 16;
        var height = (entries + 1) * line + 12;
        var top = bottom - height;
        draw.addRectFilled(left, top, left + width, bottom, ImColor.rgba(11, 18, 23, 215), 6f);
        var y = top + 6;
        draw.addText(left + 8, y, GeodataWidgets.MUTED, "Geodata regions");
        y += line;
        for (var state : State.values()) {
            if (state == State.NONE || !present[state.ordinal()]) {
                continue;
            }
            var middle = HudWidgets.textMiddle(y);
            draw.addRectFilled(left + 8, middle - 5, left + 18, middle + 5, withAlpha(state.color, .5f), 2f);
            draw.addRect(left + 8, middle - 5, left + 18, middle + 5, state.color, 2, 0, state == State.EDITED ? 2 : 1);
            draw.addText(left + 26, y, GeodataWidgets.TEXT, state.label);
            y += line;
        }
        if (stale) {
            var middle = HudWidgets.textMiddle(y);
            draw.addTriangleFilled(left + 8, middle - 5, left + 18, middle - 5, left + 18, middle + 5, STALE);
            draw.addText(left + 26, y, GeodataWidgets.TEXT, "Pathnode older than the file");
        }
        return height;
    }

    String getReport() {
        return String.format(Locale.ROOT, "%d regions drawn, %.3f ms", lastDrawn, lastDrawNanos / 1e6);
    }

    private static @Nullable GeoTile tileAt(MapTransform map, float x, float y) {
        var tile = GeoTile.fromWorld((int) Math.floor(map.worldX(x)), (int) Math.floor(map.worldY(y)));
        return tile.x() < 10 || tile.x() > 29 || tile.y() < 10 || tile.y() > 26 ? null : tile;
    }

    private static int withAlpha(int color, float alpha) {
        return (color & 0x00ffffff) | Math.round(255 * alpha) << 24;
    }
}
