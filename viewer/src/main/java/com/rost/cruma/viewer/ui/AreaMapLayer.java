package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.area.AreaDocument.State;
import com.rost.cruma.core.area.AreaSummary;
import com.rost.cruma.core.area.AreaTiles;
import com.rost.cruma.viewer.spawn.AreaStyle;
import com.rost.cruma.viewer.ui.SpawnPanelWidgets.Slice;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.flag.ImDrawFlags;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class AreaMapLayer implements MapLayer {
    static final int MAX_PATHS = 3000;

    private static final float FADED = .3f;

    private static final float DOT_PIXELS = 4;

    private final AreaWorkspace areas;
    private @Nullable State builtState;
    private @Nullable WorldMapCoordinates builtFor;

    private List<Shape> shapes = List.of();

    private List<Slice<AreaStyle>> composition = List.of();

    private final BitSet unloaded = new BitSet();

    private @Nullable List<Shape> unloadedShapes;
    private long unloadedRevision = -1;

    private float[][] pixels = new float[0][];

    private float pixelsWidth = -1;
    private float pixelsHeight = -1;

    private int[] drawn = new int[0];

    private int drawnCount;
    private int lastPaths;
    private int lastDots;
    private long lastDrawNanos;

    AreaMapLayer(AreaWorkspace areas) {
        this.areas = areas;
    }

    private record Shape(int index, AreaStyle style, float[] uv, float minU, float minV, float maxU, float maxV) {}

    @Override
    public String name() {
        return "Areas";
    }

    @Override
    public boolean isAvailable() {
        return areas.getDocument() != null;
    }

    @Override
    public boolean isShown() {
        return areas.isOverlayOn();
    }

    @Override
    public void show(boolean shown) {
        areas.setOverlayOn(shown);
    }

    @Override
    public void draw(ImDrawList draw, MapTransform map) {
        var started = System.nanoTime();
        drawnCount = 0;
        lastPaths = 0;
        lastDots = 0;
        var document = areas.getDocument();
        if (document == null) {
            return;
        }
        var state = document.getState();
        rebuild(state, map);
        refreshUnloaded(state);
        if (pixelsWidth != map.width() || pixelsHeight != map.height()) {
            scale(map.width(), map.height());
        }
        if (drawn.length < shapes.size()) {
            drawn = new int[shapes.size()];
        }
        var selected = areas.getSelected();
        var paths = 0;
        Shape chosenShape = null;
        var chosenAt = -1;
        var dotCells = new BitSet();
        var cellColumns = (int) Math.ceil((map.clipRight() - map.clipLeft()) / 2) + 1;
        for (var i = 0; i < shapes.size(); i++) {
            var shape = shapes.get(i);
            var left = map.imageX() + shape.minU() * map.width();
            var right = map.imageX() + shape.maxU() * map.width();
            var top = map.imageY() + shape.minV() * map.height();
            var bottom = map.imageY() + shape.maxV() * map.height();
            if (right < map.clipLeft() || left > map.clipRight() || bottom < map.clipTop() || top > map.clipBottom()) {
                continue;
            }
            var area = state.area(shape.index());
            var chosen = shape.index() == selected;
            if (area == null || !chosen && !areas.isShownOnMap(area)) {
                continue;
            }
            drawn[drawnCount++] = i;
            if (chosen) {
                chosenShape = shape;
                chosenAt = i;
                continue;
            }
            var alpha = (unloaded.get(i) ? FADED : 1f) * (area.isOnByDefault() ? 1f : .55f);
            var size = Math.max(right - left, bottom - top);
            if (size < DOT_PIXELS || paths >= MAX_PATHS) {
                var cx = (left + right) * .5f;
                var cy = (top + bottom) * .5f;
                var cell = (int) ((cy - map.clipTop()) / 2) * cellColumns + (int) ((cx - map.clipLeft()) / 2);
                if (cell >= 0 && dotCells.get(cell)) {
                    continue;
                }
                if (cell >= 0) {
                    dotCells.set(cell);
                }
                draw.addRectFilled(cx - 1, cy - 1, cx + 1, cy + 1, shape.style().getImguiColor(.9f * alpha, 1.1f));
                lastDots++;
                continue;
            }
            stroke(draw, map, pixels[i], shape.style().getImguiColor(.85f * alpha, 1.1f), 1.3f);
            paths++;
        }
        if (chosenShape != null) {
            drawChosen(draw, map, state, chosenShape, chosenAt);
            paths++;
        }
        lastPaths = paths;
        lastDrawNanos = System.nanoTime() - started;
    }

    @Override
    public @Nullable String getTooltipAt(MapTransform map, float x, float y) {
        var hit = findAt(map, x, y);
        var document = areas.getDocument();
        if (hit < 0 || document == null) {
            return null;
        }
        var area = document.getState().area(shapes.get(hit).index());
        if (area == null) {
            return null;
        }
        var text = new StringBuilder(area.name().isEmpty() ? "No name" : area.name())
                .append('\n')
                .append(AreaStyle.title(area))
                .append(" · ")
                .append(AreaTiles.format(area.centerTileX(), area.centerTileY()))
                .append(area.isOnByDefault() ? "" : " · off")
                .append("\n\n")
                .append(AreaSummary.describe(area, document.getFile().getDefaultValues()));
        if (unloaded.get(hit)) {
            text.append("\n\nNot loaded in this session, so the world does not show it.");
        }
        return text.append("\n\nClick to fly there and select it.").toString();
    }

    @Override
    public @Nullable Click getClickAt(MapTransform map, float x, float y) {
        var hit = findAt(map, x, y);
        if (hit < 0) {
            return null;
        }
        areas.selectOnMap(shapes.get(hit).index());
        return Click.HANDLED;
    }

    @Override
    public void drawOptions() {
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (composition.isEmpty()) {
            GeodataWidgets.note("No areas in the file.", GeodataWidgets.FAINT);
        } else {
            var style = SpawnPanelWidgets.compositionOf(
                    "map-area-kinds", composition, areas.getHiddenStyles(), "in the world and on the map");
            if (style != null && !areas.getHiddenStyles().remove(style)) {
                areas.getHiddenStyles().add(style);
            }
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        areas.setInstancedShown(HudWidgets.switchRow(
                "In instance zones",
                areas.areInstancedShown(),
                true,
                "Areas with instantzone_id. They work only inside a copy of an instance zone."));
        areas.setOffShown(HudWidgets.switchRow(
                "Off by default",
                areas.areOffShown(),
                true,
                "Areas with default_status=off, drawn fainter. They do nothing until an event or an AI turns them"
                        + " on."));
    }

    String getReport() {
        return String.format(
                Locale.ROOT,
                "%d areas, %d in view, %d paths, %d dots, %.2f ms",
                shapes.size(),
                drawnCount,
                lastPaths,
                lastDots,
                lastDrawNanos / 1e6);
    }

    private void drawChosen(ImDrawList draw, MapTransform map, State state, Shape shape, int at) {
        var points = pixels[at];
        for (var p = 0; p + 1 < points.length; p += 2) {
            draw.pathLineTo(map.imageX() + points[p], map.imageY() + points[p + 1]);
        }
        draw.pathFillConcave(shape.style().getImguiColor(.28f));
        stroke(draw, map, points, ImColor.rgba(10, 16, 22, 200), 4.6f);
        stroke(draw, map, points, shape.style().getImguiColor(1f, 1.35f), 2.6f);
        var area = state.area(shape.index());
        if (area == null) {
            return;
        }
        for (var part : area.banned()) {
            var corners = new float[part.size() * 2];
            for (var p = 0; p < part.size(); p++) {
                corners[p * 2] = map.u(part.get(p).x()) * map.width();
                corners[p * 2 + 1] = map.v(part.get(p).y()) * map.height();
            }
            stroke(draw, map, corners, GeodataWidgets.BLOCKED, 1.6f);
        }
    }

    private void rebuild(State state, MapTransform map) {
        if (state == builtState && map.coordinates() == builtFor) {
            return;
        }
        builtState = state;
        builtFor = map.coordinates();
        var built = new ArrayList<Shape>();
        var sizes = new ArrayList<Double>();
        var counts = new EnumMap<AreaStyle, Long>(AreaStyle.class);
        for (var area : state.areas()) {
            if (area == null || area.points().size() < 3) {
                continue;
            }
            var style = AreaStyle.of(area);
            counts.merge(style, 1L, Long::sum);
            var points = area.points();
            var uv = new float[points.size() * 2];
            var minU = Float.MAX_VALUE;
            var minV = Float.MAX_VALUE;
            var maxU = -Float.MAX_VALUE;
            var maxV = -Float.MAX_VALUE;
            for (var p = 0; p < points.size(); p++) {
                var u = map.u(points.get(p).x());
                var v = map.v(points.get(p).y());
                uv[p * 2] = u;
                uv[p * 2 + 1] = v;
                minU = Math.min(minU, u);
                minV = Math.min(minV, v);
                maxU = Math.max(maxU, u);
                maxV = Math.max(maxV, v);
            }
            built.add(new Shape(area.index(), style, uv, minU, minV, maxU, maxV));
            sizes.add(area.area());
        }
        var order = new ArrayList<Integer>();
        for (var i = 0; i < built.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble((Integer i) -> sizes.get(i)).reversed());
        shapes = order.stream().map(built::get).toList();
        composition = counts.entrySet().stream()
                .sorted(Map.Entry.<AreaStyle, Long>comparingByValue().reversed())
                .map(entry -> new Slice<>(
                        entry.getKey(),
                        entry.getKey().label,
                        entry.getKey().label.toLowerCase(Locale.ROOT),
                        entry.getKey().argb,
                        entry.getValue()))
                .toList();
        pixelsWidth = -1;
    }

    private void refreshUnloaded(State state) {
        var tiles = areas.getLoadedTiles();
        var revision = tiles == null ? -1 : tiles.getRevision();
        if (unloadedShapes == shapes && unloadedRevision == revision) {
            return;
        }
        unloadedShapes = shapes;
        unloadedRevision = revision;
        unloaded.clear();
        if (tiles == null || tiles.isEmpty()) {
            return;
        }
        for (var i = 0; i < shapes.size(); i++) {
            var area = state.area(shapes.get(i).index());
            if (area != null && !tiles.intersects(area.minX(), area.minY(), area.maxX(), area.maxY())) {
                unloaded.set(i);
            }
        }
    }

    private void scale(float width, float height) {
        pixels = new float[shapes.size()][];
        for (var i = 0; i < shapes.size(); i++) {
            var uv = shapes.get(i).uv();
            var points = new float[uv.length];
            for (var p = 0; p + 1 < uv.length; p += 2) {
                points[p] = uv[p] * width;
                points[p + 1] = uv[p + 1] * height;
            }
            pixels[i] = points;
        }
        pixelsWidth = width;
        pixelsHeight = height;
    }

    private int findAt(MapTransform map, float x, float y) {
        var document = areas.getDocument();
        if (document == null) {
            return -1;
        }
        var state = document.getState();
        var worldX = map.worldX(x);
        var worldY = map.worldY(y);
        var best = -1;
        var bestArea = Double.MAX_VALUE;
        var dot = -1;
        var near = 6f;
        for (var d = 0; d < drawnCount; d++) {
            var i = drawn[d];
            if (i >= shapes.size()) {
                continue;
            }
            var shape = shapes.get(i);
            var area = state.area(shape.index());
            if (area == null) {
                continue;
            }
            var left = map.imageX() + shape.minU() * map.width();
            var right = map.imageX() + shape.maxU() * map.width();
            var top = map.imageY() + shape.minV() * map.height();
            var bottom = map.imageY() + shape.maxV() * map.height();
            if (x < left - near || x > right + near || y < top - near || y > bottom + near) {
                continue;
            }
            if (Math.max(right - left, bottom - top) >= DOT_PIXELS) {
                if (area.containsXy(worldX, worldY) && area.area() < bestArea) {
                    best = i;
                    bestArea = area.area();
                }
                continue;
            }
            var distance = (float) Math.hypot((left + right) * .5f - x, (top + bottom) * .5f - y);
            if (distance < near) {
                near = distance;
                dot = i;
            }
        }
        return best >= 0 ? best : dot;
    }

    private static void stroke(ImDrawList draw, MapTransform map, float[] points, int color, float thickness) {
        var lastX = Float.NaN;
        var lastY = Float.NaN;
        var count = 0;
        for (var p = 0; p + 1 < points.length; p += 2) {
            var x = map.imageX() + points[p];
            var y = map.imageY() + points[p + 1];
            if (count > 0 && Math.abs(x - lastX) < 1.5f && Math.abs(y - lastY) < 1.5f) {
                continue;
            }
            draw.pathLineTo(x, y);
            lastX = x;
            lastY = y;
            count++;
        }
        if (count < 3) {
            draw.pathClear();
            draw.addRectFilled(lastX - 1, lastY - 1, lastX + 1, lastY + 1, color);
            return;
        }
        draw.pathStroke(color, ImDrawFlags.Closed, thickness);
    }
}
