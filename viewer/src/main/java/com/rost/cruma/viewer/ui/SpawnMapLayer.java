package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.spawn.NpcDataTable;
import com.rost.cruma.core.spawn.NpcPosDocument.State;
import com.rost.cruma.core.spawn.SpawnMaker;
import com.rost.cruma.core.spawn.SpawnPosition.Fixed;
import com.rost.cruma.core.spawn.SpawnSummary;
import com.rost.cruma.viewer.spawn.SpawnIndex;
import com.rost.cruma.viewer.spawn.SpawnStyle;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImDrawFlags;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

final class SpawnMapLayer implements MapLayer {
    static final int MAX_PATHS = 3000;

    static final int MAX_FILLS = 1500;

    static final int[] BAND_COLORS = {0xFF7FD3A0, 0xFF8FC6F0, 0xFFF0D264, 0xFFF0965A, 0xFFE6648C};

    private static final float FADED = .3f;

    private static final float DOT_PIXELS = 4;

    private final SpawnWorkspace spawns;
    private boolean shown = true;
    private boolean byLevel;
    private boolean density;
    private final BitSet hiddenBands = new BitSet();
    private @Nullable State builtState;
    private @Nullable WorldMapCoordinates builtFor;
    private List<Shape> shapes = List.of();

    private final BitSet unloaded = new BitSet();

    private @Nullable List<Shape> unloadedShapes;
    private long unloadedRevision = -1;

    private float[][] pixels = new float[0][];

    private float pixelsWidth = -1;
    private float pixelsHeight = -1;
    private final HashMap<Integer, Integer> makerBands = new HashMap<>();

    private int[] drawn = new int[0];

    private int drawnCount;

    long lastDrawNanos;

    int lastPaths;
    int lastDots;

    SpawnMapLayer(SpawnWorkspace spawns) {
        this.spawns = spawns;
    }

    private record Shape(
            int territory,
            int[] makers,
            float[] uv,
            float minU,
            float minV,
            float maxU,
            float maxV,
            double area,
            int npcs,
            String name,
            double minX,
            double minY,
            double maxX,
            double maxY) {}

    static String hoverCardText(SpawnMaker maker, NpcDataTable npcs) {
        var levels = SpawnSummary.computeLevels(maker, npcs);
        var line = new StringBuilder(maker.displayName());
        if (levels != null) {
            line.append(" · ").append(levels.label());
        }
        line.append(" · ").append(maker.totalNpcs()).append(maker.totalNpcs() == 1 ? " NPC" : " NPCs");
        if (!maker.eventName().isEmpty()) {
            line.append(" · event ").append(maker.eventName());
        } else if (!maker.spawnTime().isEmpty()) {
            line.append(" · during ").append(maker.spawnTimeKind());
        }
        var names = new LinkedHashSet<String>();
        for (var npc : maker.npcs()) {
            names.add("×" + npc.total() + " " + npc.npcName());
            if (names.size() == 4) {
                break;
            }
        }
        line.append("\n").append(String.join(", ", names));
        if (maker.npcs().size() > 4) {
            line.append(" +").append(maker.npcs().size() - 4);
        }
        return line.toString();
    }

    @Override
    public String name() {
        return "NPC Spawns";
    }

    @Override
    public boolean isShown() {
        return shown;
    }

    @Override
    public void show(boolean value) {
        shown = value;
    }

    boolean isByLevel() {
        return byLevel;
    }

    void setByLevel(boolean value) {
        byLevel = value;
    }

    void setDensity(boolean value) {
        density = value;
    }

    @Override
    public void draw(ImDrawList draw, MapTransform map) {
        var started = System.nanoTime();
        var state = spawns.getMapState();
        var index = spawns.getMapIndex();
        drawnCount = 0;
        lastPaths = 0;
        lastDots = 0;
        if (state == null || index == null) {
            return;
        }
        rebuild(state, index, map);
        refreshUnloaded();
        if (pixelsWidth != map.width() || pixelsHeight != map.height()) {
            scale(map.width(), map.height());
        }
        if (drawn.length < shapes.size()) {
            drawn = new int[shapes.size()];
        }
        var selected = spawns.getSelectedMaker();
        var chosen = spawns.getChosenMakers();
        var npcs = spawns.getNpcData();
        var paths = 0;
        var fills = 0;
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
            var maker = shownMaker(state, shape, npcs);
            if (maker < 0) {
                continue;
            }
            drawn[drawnCount++] = i;
            var focused = contains(shape.makers(), selected);
            var picked = !chosen.isEmpty() && isAnyChosen(shape.makers(), chosen);
            var argb = makerColor(state, maker, npcs);
            var alpha = unloaded.get(i) ? FADED : 1f;
            var size = Math.max(right - left, bottom - top);
            if (density && shape.territory() >= 0 && size >= 6 && fills < MAX_FILLS) {
                fill(draw, map, i, argb, shape, alpha);
                fills++;
            }
            if (shape.territory() < 0) {
                var points = pixels[i];
                for (var p = 0; p + 1 < points.length; p += 2) {
                    var x = map.imageX() + points[p];
                    var y = map.imageY() + points[p + 1];
                    draw.addCircleFilled(
                            x,
                            y,
                            focused || picked ? 3.5f : 2.2f,
                            focused ? HudWidgets.ACCENT : SpawnStyle.getImguiColor(argb, .95f * alpha, 1.1f),
                            8);
                    lastDots++;
                }
                continue;
            }
            if (size < DOT_PIXELS || paths >= MAX_PATHS && !focused && !picked) {
                var cx = (left + right) * .5f;
                var cy = (top + bottom) * .5f;
                var cell = (int) ((cy - map.clipTop()) / 2) * cellColumns + (int) ((cx - map.clipLeft()) / 2);
                if (cell >= 0 && !focused && !picked && dotCells.get(cell)) {
                    continue;
                }
                if (cell >= 0) {
                    dotCells.set(cell);
                }
                draw.addRectFilled(
                        cx - 1,
                        cy - 1,
                        cx + 1,
                        cy + 1,
                        focused ? HudWidgets.ACCENT : SpawnStyle.getImguiColor(argb, .9f * alpha, 1.1f));
                lastDots++;
                continue;
            }
            stroke(
                    draw,
                    map,
                    pixels[i],
                    focused
                            ? HudWidgets.ACCENT
                            : picked ? ImColor.rgb(255, 255, 255) : SpawnStyle.getImguiColor(argb, .9f * alpha, 1.1f),
                    focused || picked ? 2.2f : 1.3f);
            paths++;
        }
        lastPaths = paths;
        lastDrawNanos = System.nanoTime() - started;
    }

    @Override
    public @Nullable String getTooltipAt(MapTransform map, float x, float y) {
        var hit = at(map, x, y);
        var state = spawns.getMapState();
        if (hit < 0 || state == null) {
            return null;
        }
        var shape = shapes.get(hit);
        var npcs = spawns.getNpcData();
        var text = new StringBuilder(shape.name());
        var shown = 0;
        for (var makerIndex : shape.makers()) {
            var maker = state.makers().get(makerIndex);
            if (maker == null || !spawns.isMakerShown(state, makerIndex)) {
                continue;
            }
            if (shown++ == 3) {
                text.append("\n+").append(shape.makers().length - 3).append(" more makers");
                break;
            }
            text.append("\n\n").append(hoverCardText(maker, npcs));
        }
        if (unloaded.get(hit)) {
            text.append("\n\nNot loaded in this session, so the world does not show it.");
        }
        return text.append("\n\nClick to fly there and select it. Drag to select many.")
                .toString();
    }

    @Override
    public @Nullable Click getClickAt(MapTransform map, float x, float y) {
        var hit = at(map, x, y);
        var state = spawns.getMapState();
        if (hit < 0 || state == null) {
            return null;
        }
        for (var maker : shapes.get(hit).makers()) {
            if (spawns.isMakerShown(state, maker)) {
                spawns.selectOnMap(maker);
                return Click.HANDLED;
            }
        }
        return null;
    }

    @Override
    public boolean selectsArea() {
        return true;
    }

    @Override
    public void selectArea(MapTransform map, float left, float top, float right, float bottom) {
        var state = spawns.getMapState();
        if (state == null) {
            return;
        }
        var picked = new LinkedHashSet<Integer>();
        for (var d = 0; d < drawnCount; d++) {
            var shape = shapes.get(drawn[d]);
            var cx = map.imageX() + (shape.minU() + shape.maxU()) * .5f * map.width();
            var cy = map.imageY() + (shape.minV() + shape.maxV()) * .5f * map.height();
            if (cx < left || cx > right || cy < top || cy > bottom) {
                continue;
            }
            for (var maker : shape.makers()) {
                if (spawns.isMakerShown(state, maker)) {
                    picked.add(maker);
                }
            }
        }
        spawns.chooseOnMap(picked);
    }

    @Override
    public void drawOptions() {
        GeodataWidgets.space(GeodataWidgets.ROW);
        var mode = GeodataWidgets.segmented("map-spawn-color", new String[] {"By kind", "By level"}, byLevel ? 1 : 0);
        if (mode >= 0) {
            byLevel = mode == 1;
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (ImGui.checkbox("NPC density", density)) {
            density = !density;
        }
        GeodataWidgets.tip(
                "Fill each zone by how many NPCs it holds per 100,000 units²: light for 1, full for 10 or more.");
        if (spawns.hasEvents() && ImGui.checkbox("Event and siege spawns", spawns.areEventsShown())) {
            spawns.showEventsOnMap(!spawns.areEventsShown());
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Levels");
        drawBandChips();
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Kinds");
        var kind = SpawnPanelWidgets.composition(
                "map-spawn-kinds", spawns.getMapComposition(), spawns.getHiddenKinds(), "in the world and on the map");
        if (kind != null && !spawns.getHiddenKinds().remove(kind)) {
            spawns.getHiddenKinds().add(kind);
        }
    }

    @Override
    public float drawLegend(ImDrawList draw, float left, float bottom) {
        if (!byLevel && !density) {
            return 0;
        }
        var line = ImGui.getFontSize() + 4;
        var rows = (byLevel ? 1 : 0) + (density ? 1 : 0);
        var height = rows * line + 12;
        var width = 0f;
        if (byLevel) {
            width = ImGui.calcTextSizeX("Level") + 10;
            for (var label : SpawnSummary.BAND_LABELS) {
                width += 11 + ImGui.calcTextSizeX(label) + 12;
            }
        }
        if (density) {
            width = Math.max(
                    width,
                    ImGui.calcTextSizeX("NPCs per 100k units\u00b2")
                            + 10
                            + ImGui.calcTextSizeX("1")
                            + 6
                            + 90
                            + 6
                            + ImGui.calcTextSizeX("10+"));
        }
        width += 16;
        var top = bottom - height;
        draw.addRectFilled(left, top, left + width, bottom, ImColor.rgba(11, 18, 23, 215), 6f);
        var y = top + 6;
        if (byLevel) {
            var x = left + 8;
            draw.addText(x, y, GeodataWidgets.MUTED, "Level");
            x += ImGui.calcTextSizeX("Level") + 10;
            for (var band = 0; band < BAND_COLORS.length; band++) {
                draw.addCircleFilled(
                        x + 4, HudWidgets.textMiddle(y), 4, SpawnStyle.getImguiColor(BAND_COLORS[band], 1f, 1f), 10);
                draw.addText(x + 11, y, GeodataWidgets.TEXT, SpawnSummary.BAND_LABELS[band]);
                x += 11 + ImGui.calcTextSizeX(SpawnSummary.BAND_LABELS[band]) + 12;
            }
            y += line;
        }
        if (density) {
            var x = left + 8;
            draw.addText(x, y, GeodataWidgets.MUTED, "NPCs per 100k units²");
            x += ImGui.calcTextSizeX("NPCs per 100k units²") + 10;
            draw.addText(x, y, GeodataWidgets.FAINT, "1");
            x += ImGui.calcTextSizeX("1") + 6;
            var barWidth = 90f;
            for (var step = 0; step < 10; step++) {
                var alpha = .08f + .42f * step / 9f;
                draw.addRectFilled(
                        x + step * barWidth / 10,
                        y + 3,
                        x + (step + 1) * barWidth / 10,
                        y + line - 5,
                        SpawnStyle.getImguiColor(SpawnStyle.MONSTER.argb, alpha, 1f));
            }
            draw.addText(x + barWidth + 6, y, GeodataWidgets.FAINT, "10+");
        }
        return height;
    }

    String getReport() {
        return String.format(
                Locale.ROOT,
                "%d shapes, %d in view, %d paths, %d dots, %.2f ms",
                shapes.size(),
                drawnCount,
                lastPaths,
                lastDots,
                lastDrawNanos / 1e6);
    }

    private void fill(ImDrawList draw, MapTransform map, int shapeIndex, int argb, Shape shape, float alpha) {
        var perArea = shape.npcs() / Math.max(1, shape.area() / 100_000);
        var strength = (float) Math.clamp(Math.log10(1 + perArea) / Math.log10(11), 0, 1);
        var points = pixels[shapeIndex];
        for (var p = 0; p + 1 < points.length; p += 2) {
            draw.pathLineTo(map.imageX() + points[p], map.imageY() + points[p + 1]);
        }
        draw.pathFillConcave(SpawnStyle.getImguiColor(argb, (.08f + .42f * strength) * alpha, 1f));
    }

    private int shownMaker(State state, Shape shape, NpcDataTable npcs) {
        for (var maker : shape.makers()) {
            if (spawns.isMakerShown(state, maker) && !hiddenBands.get(levelBand(state, maker, npcs))) {
                return maker;
            }
        }
        return -1;
    }

    private int makerColor(State state, int maker, NpcDataTable npcs) {
        if (!byLevel) {
            return spawns.getMakerStyle(state, maker).argb;
        }
        var band = levelBand(state, maker, npcs);
        return band < 0 ? SpawnStyle.UNKNOWN.argb : BAND_COLORS[band];
    }

    private int levelBand(State state, int maker, NpcDataTable npcs) {
        return makerBands.computeIfAbsent(maker, ignored -> {
            var value = maker < state.makers().size() ? state.makers().get(maker) : null;
            var levels = value == null ? null : SpawnSummary.computeLevels(value, npcs);
            return levels == null ? -1 : SpawnSummary.levelBand(levels.mean());
        });
    }

    private void rebuild(State state, SpawnIndex index, MapTransform map) {
        if (state == builtState && map.coordinates() == builtFor) {
            return;
        }
        builtState = state;
        builtFor = map.coordinates();
        makerBands.clear();
        var built = new ArrayList<Shape>();
        var placed = new BitSet();
        for (var territoryIndex = 0; territoryIndex < state.territories().size(); territoryIndex++) {
            var territory = state.territories().get(territoryIndex);
            if (territory == null || territory.domain() || territory.points().size() < 3) {
                continue;
            }
            var makers = index.getMakersOf(territoryIndex);
            if (makers.length == 0) {
                continue;
            }
            var points = territory.points();
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
            var npcs = 0;
            for (var maker : makers) {
                var value = state.makers().get(maker);
                if (value != null) {
                    npcs += value.totalNpcs();
                    placed.set(maker);
                }
            }
            built.add(new Shape(
                    territoryIndex,
                    makers,
                    uv,
                    minU,
                    minV,
                    maxU,
                    maxV,
                    territory.area(),
                    npcs,
                    territory.name(),
                    territory.minX(),
                    territory.minY(),
                    territory.maxX(),
                    territory.maxY()));
        }
        for (var makerIndex = 0; makerIndex < state.makers().size(); makerIndex++) {
            var maker = state.makers().get(makerIndex);
            if (maker == null || placed.get(makerIndex)) {
                continue;
            }
            var fixed = new ArrayList<float[]>();
            var minX = Double.MAX_VALUE;
            var minY = Double.MAX_VALUE;
            var maxX = -Double.MAX_VALUE;
            var maxY = -Double.MAX_VALUE;
            for (var npc : maker.npcs()) {
                if (npc.position() instanceof Fixed point) {
                    fixed.add(new float[] {map.u(point.x()), map.v(point.y())});
                    minX = Math.min(minX, point.x());
                    minY = Math.min(minY, point.y());
                    maxX = Math.max(maxX, point.x());
                    maxY = Math.max(maxY, point.y());
                }
            }
            if (fixed.isEmpty()) {
                continue;
            }
            var uv = new float[fixed.size() * 2];
            var minU = Float.MAX_VALUE;
            var minV = Float.MAX_VALUE;
            var maxU = -Float.MAX_VALUE;
            var maxV = -Float.MAX_VALUE;
            for (var p = 0; p < fixed.size(); p++) {
                uv[p * 2] = fixed.get(p)[0];
                uv[p * 2 + 1] = fixed.get(p)[1];
                minU = Math.min(minU, uv[p * 2]);
                minV = Math.min(minV, uv[p * 2 + 1]);
                maxU = Math.max(maxU, uv[p * 2]);
                maxV = Math.max(maxV, uv[p * 2 + 1]);
            }
            built.add(new Shape(
                    -1,
                    new int[] {makerIndex},
                    uv,
                    minU,
                    minV,
                    maxU,
                    maxV,
                    0,
                    maker.totalNpcs(),
                    maker.displayName(),
                    minX,
                    minY,
                    maxX,
                    maxY));
        }
        shapes = built;
        pixelsWidth = -1;
    }

    private void refreshUnloaded() {
        var tiles = spawns.getLoadedTiles();
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
            var shape = shapes.get(i);
            if (!tiles.intersects(shape.minX(), shape.minY(), shape.maxX(), shape.maxY())) {
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

    private int at(MapTransform map, float x, float y) {
        var state = spawns.getMapState();
        if (state == null) {
            return -1;
        }
        var worldX = map.worldX(x);
        var worldY = map.worldY(y);
        var best = -1;
        var bestArea = Double.MAX_VALUE;
        var near = 6f;
        for (var d = 0; d < drawnCount; d++) {
            var i = drawn[d];
            if (i >= shapes.size()) {
                continue;
            }
            var shape = shapes.get(i);
            var left = map.imageX() + shape.minU() * map.width();
            var right = map.imageX() + shape.maxU() * map.width();
            var top = map.imageY() + shape.minV() * map.height();
            var bottom = map.imageY() + shape.maxV() * map.height();
            if (x < left - near || x > right + near || y < top - near || y > bottom + near) {
                continue;
            }
            if (shape.territory() >= 0 && right - left >= DOT_PIXELS) {
                var territory = state.territories().get(shape.territory());
                if (territory != null && territory.containsXy(worldX, worldY) && shape.area() < bestArea) {
                    best = i;
                    bestArea = shape.area();
                }
                continue;
            }
            var points = pixels[i];
            for (var p = 0; p + 1 < points.length; p += 2) {
                var px = shape.territory() >= 0 ? (left + right) * .5f : map.imageX() + points[p];
                var py = shape.territory() >= 0 ? (top + bottom) * .5f : map.imageY() + points[p + 1];
                var distance = (float) Math.hypot(px - x, py - y);
                if (distance < near) {
                    near = distance;
                    best = i;
                    bestArea = 0;
                }
                if (shape.territory() >= 0) {
                    break;
                }
            }
        }
        return best;
    }

    private void drawBandChips() {
        var spacing = 4f;
        var width = (ImGui.getContentRegionAvailX() - spacing * 4) / 5;
        for (var band = 0; band < BAND_COLORS.length; band++) {
            if (band > 0) {
                ImGui.sameLine(0, spacing);
            }
            var x = ImGui.getCursorScreenPosX();
            var y = ImGui.getCursorScreenPosY();
            var off = hiddenBands.get(band);
            if (ImGui.invisibleButton("map-band-" + band, width, 22)) {
                hiddenBands.flip(band);
            }
            var hovered = ImGui.isItemHovered();
            var draw = ImGui.getWindowDrawList();
            draw.addRectFilled(x, y, x + width, y + 22, hovered ? GeodataWidgets.HOVER : GeodataWidgets.CARD, 11f);
            var textTop = y + (22 - ImGui.getFontSize()) * .5f;
            draw.addCircleFilled(
                    x + 9,
                    HudWidgets.textMiddle(textTop),
                    3.5f,
                    SpawnStyle.getImguiColor(BAND_COLORS[band], off ? .3f : 1f, 1f),
                    10);
            draw.addText(
                    x + 16, textTop, off ? GeodataWidgets.FAINT : GeodataWidgets.MUTED, SpawnSummary.BAND_LABELS[band]);
            if (hovered) {
                HudWidgets.showTooltip((off ? "Show" : "Hide") + " makers of level " + SpawnSummary.BAND_LABELS[band]
                        + " on the map.");
            }
        }
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

    private static boolean contains(int[] values, int value) {
        for (var candidate : values) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAnyChosen(int[] makers, Set<Integer> chosen) {
        for (var maker : makers) {
            if (chosen.contains(maker)) {
                return true;
            }
        }
        return false;
    }
}
