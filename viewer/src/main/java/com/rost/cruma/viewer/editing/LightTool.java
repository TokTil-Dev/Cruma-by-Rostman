package com.rost.cruma.viewer.editing;

import static com.rost.cruma.viewer.editing.EditorUi.tip;

import com.rost.cruma.core.editing.ActorTransform.Rotator;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.editing.LightColor;
import com.rost.cruma.core.editing.LightPlacement;
import com.rost.cruma.core.editing.LightRecord;
import com.rost.cruma.core.editing.LightShape;
import com.rost.cruma.core.editing.PackageEditDocument.LightTemplate;
import com.rost.cruma.core.env.UnrealHsv;
import com.rost.cruma.viewer.editing.LightColors.Preset;
import com.rost.cruma.viewer.ui.GeodataWidgets;
import com.rost.cruma.viewer.ui.HudWidgets;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.Motion;
import imgui.ImGui;
import imgui.flag.ImGuiColorEditFlags;
import imgui.flag.ImGuiKey;
import imgui.type.ImString;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

final class LightTool {
    static final List<Preset> PRESETS = LightColors.PRESETS;

    static final int WHITE_SATURATION = LightColors.WHITE_SATURATION;
    static final float NEAR_COLOR = LightColors.NEAR_COLOR;

    static final float MAX_OFFSET = 512;

    static final float SPOT_OFFSET = 256;

    static final float SPOT_RADIUS = 28;

    static final float WARMEST = LightColors.WARMEST;

    static final float COOLEST = LightColors.COOLEST;

    private static final String PICKER = "##editingLightColour";

    private static final float SWATCH_HEIGHT = 28;

    private static final int LIST_ROWS = 5;

    private static final float DIAL_HEIGHT = 96;

    private static final String RELIGHTING = "Relighting";

    private static final String RELIGHTING_TIP =
            "The meshes, walls and ground that the light reaches take its new light in the background, also after a"
                + " copy or a delete. While you drag, they follow a moment behind. Save rebuilds them the same way.";

    private static final int QUARTER = 16384;

    private static final float UPWARD = .17f;

    private final EditingPanel panel;
    private final EditingSession session;
    private String selected = "";
    private final LightChoice choice = new LightChoice();
    private @Nullable String hovered;
    private boolean listScroll;
    private boolean listOpen;
    private final ImString search = new ImString(64);
    private final float[] brightness = {0};
    private final float[] hue = {0};
    private final float[] saturation = {0};
    private final float[] temperature = {0};
    private final ImString[] locationText = {new ImString(32), new ImString(32), new ImString(32)};
    private final float[] location = new float[3];
    private final float[] radius = {0};
    private final float[] yaw = {0};
    private final float[] pitch = {0};
    private final float[] cone = {0};
    private final float[] pickerRgb = new float[3];
    private final float[] pickerOpened = new float[3];
    private boolean placing;
    private boolean placingSpot;
    private final float[] newBrightness = {PRESETS.get(1).brightness()};
    private int newHue = PRESETS.get(1).hue();
    private int newSaturation = PRESETS.get(1).saturation();
    private final float[] newRadius = {16};
    private final float[] newOffset = {64};
    private final float[] newSpotRadius = {SPOT_RADIUS};
    private final float[] newSpotOffset = {SPOT_OFFSET};
    private boolean outlineReach = true;
    private int reachedMeshes = -1;
    private float floorHeight = Float.NaN;

    LightTool(EditingPanel panel, EditingSession session) {
        this.panel = panel;
        this.session = session;
    }

    static String plain(String reason) {
        if (reason.startsWith("The light lists of this map cannot be rebuilt")) {
            return "This map's light data cannot be rebuilt, so its lights cannot "
                    + (reason.contains("cannot be deleted") ? "be deleted" : "move");
        }
        if (reason.contains("solid BSP")) {
            return "The new place is inside a wall, where a light lights nothing";
        }
        if (reason.contains("sky zone")) {
            return "A light cannot move into or out of the sky";
        }
        if (reason.contains("no saved place")) {
            return "Its saved place cannot change";
        }
        if (reason.contains("BSP leaf")) {
            return "Its zone is saved in an unusual way, so it cannot move";
        }
        return reason;
    }

    static LightShape aimed(LightShape shape, Vector3 look) {
        return shape.withRotation(shape.facing(look));
    }

    static float[] kelvinRgb(float kelvin) {
        return LightColors.kelvinRgb(kelvin);
    }

    static LightColor temperatureColor(float kelvin, float brightness) {
        return LightColors.temperatureColor(kelvin, brightness);
    }

    static float temperature(int hue, int saturation) {
        return LightColors.temperature(hue, saturation);
    }

    static LightColor carry(LightColor from, LightColor to, LightColor other) {
        return new LightColor(
                to.brightness() != from.brightness() ? to.brightness() : other.brightness(),
                to.hue() != from.hue() ? to.hue() : other.hue(),
                to.saturation() != from.saturation() ? to.saturation() : other.saturation());
    }

    static LightShape carryShape(LightShape from, LightShape to, LightShape other) {
        var result = other;
        if (!to.rotation().equals(from.rotation())) {
            result = result.withRotation(to.rotation());
        }
        if (to.cone() != from.cone()) {
            result = result.withCone(to.cone());
        }
        return result;
    }

    static boolean isSameColor(LightColor a, LightColor b) {
        var whiteA = a.saturation() >= WHITE_SATURATION;
        var whiteB = b.saturation() >= WHITE_SATURATION;
        if (whiteA || whiteB) {
            return whiteA && whiteB;
        }
        var x = rgb(a.hue(), a.saturation());
        var y = rgb(b.hue(), b.saturation());
        var dr = x[0] - y[0];
        var dg = x[1] - y[1];
        var db = x[2] - y[2];
        return dr * dr + dg * dg + db * db <= NEAR_COLOR * NEAR_COLOR;
    }

    static float poolRadius(float distance, float reach, float halfDegrees) {
        if (!(reach > distance)) {
            return 0;
        }
        var flat = (float) Math.sqrt(reach * reach - distance * distance);
        if (halfDegrees >= 90) {
            return flat;
        }
        return Math.min(flat, distance * (float) Math.tan(Math.toRadians(halfDegrees)));
    }

    static String facing(LightShape shape) {
        var direction = shape.direction();
        var level = Math.hypot(direction.x(), direction.y());
        var pitch = Math.toDegrees(Math.atan2(direction.z(), level));
        var tilt = Math.abs(pitch) < 1
                ? "level"
                : String.format(Locale.ROOT, "%.0f° %s", Math.abs(pitch), pitch < 0 ? "down" : "up");
        if (level < 1e-3) {
            return pitch < 0 ? "facing straight down" : "facing straight up";
        }
        var bearing = Math.toDegrees(Math.atan2(-direction.y(), direction.x()));
        String[] names = {"east", "north-east", "north", "north-west", "west", "south-west", "south", "south-east"};
        var index = (int) Math.floorMod(Math.round(bearing / 45), 8);
        return "facing " + names[index] + ", " + tilt;
    }

    static float[] rgb(int hue, int saturation) {
        return LightColors.rgb(hue, saturation);
    }

    static String colorName(LightColor color) {
        return LightColors.colorName(color);
    }

    static int color(LightColor color) {
        var rgb = rgb(color.hue(), color.saturation());
        return ImGui.colorConvertFloat4ToU32(rgb[0], rgb[1], rgb[2], 1);
    }

    String getSelected() {
        return selected;
    }

    void select(String path) {
        selected = path;
        choice.replace(path);
        listScroll = true;
        listOpen = false;
        placing = false;
        reachedMeshes = -1;
        floorHeight = Float.NaN;
        if (!path.isEmpty()) {
            panel.releaseObjects();
        }
    }

    void toggle(String path) {
        syncChoice();
        choice.toggle(path);
        afterChoice();
    }

    void add(String path) {
        syncChoice();
        choice.add(path);
        afterChoice();
    }

    void choose(List<String> paths, boolean additive) {
        syncChoice();
        choice.choose(paths, additive);
        afterChoice();
    }

    void chooseSameColor(String path) {
        var light = session.getLight(path).orElse(null);
        if (light == null) {
            return;
        }
        var family = new ArrayList<String>();
        for (var other : getMarks()) {
            if (isSameColor(light.color(), other.color())) {
                family.add(other.sourcePath());
            }
        }
        select(path);
        choice.choose(family, true);
    }

    List<String> getChosen() {
        if (selected.isEmpty()) {
            return List.of();
        }
        syncChoice();
        var result = new ArrayList<String>(choice.size());
        for (var path : choice.getAll()) {
            if (session.getLight(path).isPresent()) {
                result.add(path);
            }
        }
        return result;
    }

    boolean isChosen(String path) {
        return !selected.isEmpty()
                && (path.equals(selected) || choice.getActive().equals(selected) && choice.contains(path));
    }

    @Nullable
    String getChosenName() {
        if (selected.isEmpty() || session.getLight(selected).isEmpty()) {
            return null;
        }
        var count = getChosen().size();
        return count > 1 ? count + " lights" : EditingSession.shortName(selected);
    }

    void updateStatus() {
        panel.setBackgroundStatus(session.isLightingPreviewBusy() ? RELIGHTING : "", RELIGHTING_TIP);
    }

    @Nullable
    String getHovered() {
        return hovered;
    }

    boolean isPlacing() {
        return placing;
    }

    boolean isPlacingSpot() {
        return placingSpot;
    }

    LightColor getNewColor() {
        return new LightColor(Math.round(newBrightness[0]), newHue, newSaturation);
    }

    float getNewRadius() {
        return Math.round((placingSpot ? newSpotRadius : newRadius)[0]);
    }

    float getNewOffset() {
        return (placingSpot ? newSpotOffset : newOffset)[0];
    }

    boolean isOutlineReach() {
        return outlineReach;
    }

    void viewInfo(int meshes, float height) {
        reachedMeshes = meshes;
        floorHeight = height;
    }

    @Nullable
    String getMap() {
        if (!selected.isEmpty() && session.getLight(selected).isPresent()) {
            return EditingSession.mapOfPath(selected);
        }
        var map = panel.getCameraMap();
        return map == null || map.isEmpty() ? null : map;
    }

    List<LightRecord> getMarks() {
        var map = getMap();
        if (map == null) {
            return List.of();
        }
        try {
            return session.getLights(map);
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }

    @Nullable
    LightTemplate getTemplate() {
        var map = getMap();
        return map == null ? null : getTemplate(map);
    }

    LightPlacement getNewPlacement(Vector3 surface, Vector3 normal) {
        var offset = getNewOffset();
        return new LightPlacement(
                new Vector3(
                        surface.x() + normal.x() * offset,
                        surface.y() + normal.y() * offset,
                        surface.z() + normal.z() * offset),
                getNewRadius());
    }

    @Nullable
    LightShape getNewShape(Vector3 normal) {
        if (!placingSpot) {
            return null;
        }
        var template = getTemplate();
        if (template == null || template.path().isEmpty()) {
            return null;
        }
        var shape = session.getLight(template.path()).map(LightRecord::shape).orElse(null);
        return shape == null
                ? null
                : shape.withRotation(shape.facing(new Vector3(-normal.x(), -normal.y(), -normal.z())));
    }

    String getNewLightRestriction(LightPlacement placement) {
        var template = getTemplate(mapAt(placement.location()));
        if (!template.restriction().isEmpty()) {
            return plain(template.restriction());
        }
        try {
            return plain(session.getLightPlacementRestriction(template.path(), placement));
        } catch (RuntimeException unreadable) {
            return unreadable.getMessage();
        }
    }

    void place(LightPlacement placement, Vector3 aim, boolean more) {
        var reason = getNewLightRestriction(placement);
        if (!reason.isEmpty()) {
            panel.showError(plain(reason) + ".");
            return;
        }
        var template = getTemplate(mapAt(placement.location()));
        var path = new String[1];
        panel.run(() -> path[0] = session.addLight(
                template.path(),
                placement,
                getNewColor(),
                placingSpot ? aim : null,
                placingSpot ? "Add spotlight" : "Add light"));
        if (path[0] != null) {
            var keep = more;
            select(path[0]);
            placing = keep;
        }
    }

    void togglePlacing(boolean spot) {
        if (placing && placingSpot == spot) {
            placing = false;
            return;
        }
        var wasSpot = placingSpot;
        placingSpot = spot;
        var template = getTemplate();
        if (template == null || !template.restriction().isEmpty()) {
            placingSpot = wasSpot;
            panel.showError(
                    template == null ? "Fly over a map to add a light to it." : plain(template.restriction()) + ".");
            return;
        }
        placing = true;
    }

    boolean handleKeys(boolean shift, boolean alt) {
        if (alt) {
            return false;
        }
        if (ImGui.isKeyPressed(ImGuiKey.N, false)) {
            togglePlacing(shift);
            return true;
        }
        var light = session.getLight(selected).orElse(null);
        if (light == null) {
            return false;
        }
        if (ImGui.isKeyPressed(ImGuiKey.F, false)) {
            focusChosen();
            return true;
        }
        if (EditingPanel.deletePressed()) {
            var paths = getChosen();
            if (paths.size() > 1) {
                deleteChosen();
            } else if (session.getLightDeleteRestriction(selected).isEmpty()) {
                delete(selected);
            }
            return true;
        }
        float dx = 0, dy = 0, dz = 0;
        if (ImGui.isKeyPressed(ImGuiKey.LeftArrow, true)) {
            dx = -1;
        } else if (ImGui.isKeyPressed(ImGuiKey.RightArrow, true)) {
            dx = 1;
        } else if (ImGui.isKeyPressed(ImGuiKey.UpArrow, true)) {
            dy = -1;
        } else if (ImGui.isKeyPressed(ImGuiKey.DownArrow, true)) {
            dy = 1;
        } else if (ImGui.isKeyPressed(ImGuiKey.PageUp, true)) {
            dz = 1;
        } else if (ImGui.isKeyPressed(ImGuiKey.PageDown, true)) {
            dz = -1;
        } else {
            return false;
        }
        var step = panel.getGridStep() * (shift ? 10 : 1);
        var at = light.location();
        moveChosen(selected, new Vector3(at.x() + dx * step, at.y() + dy * step, at.z() + dz * step));
        return true;
    }

    boolean copySelected() {
        var light = session.getLight(selected).orElse(null);
        if (light == null) {
            return false;
        }
        if (getChosen().size() > 1) {
            copyChosen();
            return true;
        }
        var reason = session.getLightCopyRestriction(selected);
        if (reason.isEmpty()) {
            copy(light);
        } else {
            panel.showError(plain(reason) + ".");
        }
        return true;
    }

    boolean isAimFromCamera() {
        var light = session.getLight(selected).orElse(null);
        var look = panel.getCameraLook();
        if (light == null || !light.shape().isSpot()) {
            return false;
        }
        if (look == null) {
            panel.showError("The camera's direction is not known yet.");
            return true;
        }
        var shapes = new LinkedHashMap<String, LightShape>();
        for (var path : getChosen()) {
            var each = session.getLight(path).orElse(null);
            if (each == null || !each.shape().isSpot()) {
                continue;
            }
            var next = aimed(each.shape(), look);
            var reason = session.getLightShapeRestriction(path, next);
            if (!reason.isEmpty()) {
                panel.showError(named(path) + plain(reason) + ".");
                return true;
            }
            shapes.put(path, next);
        }
        panel.run(() -> session.setLightShapes(shapes, EditingSession.turnLabel(shapes.size())));
        return true;
    }

    void release() {
        selected = "";
        choice.clear();
    }

    boolean cancel() {
        if (placing) {
            placing = false;
            return true;
        }
        if (!selected.isEmpty()) {
            selected = "";
            choice.clear();
            return true;
        }
        return false;
    }

    String getHints(List<String[]> items, String undo) {
        if (placing) {
            items.add(new String[] {"Click", placingSpot ? "add a spotlight" : "add a light"});
            items.add(new String[] {"Shift+Click", "add more"});
            items.add(new String[] {"Esc", "stop"});
            return placingSpot ? "NEW SPOTLIGHT" : "NEW LIGHT";
        }
        items.add(new String[] {"Click", "choose a light"});
        items.add(new String[] {EditingPanel.COMMAND_KEY + "+Click", "add"});
        items.add(new String[] {"Drag", "move it"});
        items.add(new String[] {"Alt+drag", "copy"});
        var spot = !selected.isEmpty() && canTurn(selected);
        if (spot) {
            items.add(new String[] {EditingPanel.COMMAND_KEY + "+Shift+F", "aim"});
        } else {
            items.add(new String[] {"N", "new light"});
        }
        if (!selected.isEmpty()) {
            items.add(new String[] {"Del", "delete"});
        }
        return "LIGHTS";
    }

    void draw() {
        hovered = null;
        var map = getMap();
        if (map == null) {
            EditorUi.firstSection("Lights");
            EditorUi.muted("Fly over a map to list its lights.");
            return;
        }
        List<LightRecord> lights;
        try {
            lights = session.getLights(map);
        } catch (RuntimeException unreadable) {
            EditorUi.firstSection("Lights");
            EditorUi.muted(
                    unreadable.getMessage() == null
                            ? "The lights of " + map + " cannot be read."
                            : unreadable.getMessage());
            return;
        }
        var current = lights.stream()
                .filter(light -> light.sourcePath().equals(selected))
                .findFirst()
                .orElse(null);
        var foldable = current != null && !placing;
        if (drawTitle(map, lights.size(), foldable)) {
            listOpen = !listOpen;
            listScroll = listOpen;
        }
        drawNewButtons(map);
        if (placing) {
            drawNewLight();
            return;
        }
        if (lights.isEmpty()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            EditorUi.muted("This map has no Light or Spotlight actors.");
            return;
        }
        if (current == null) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.note("Click a light in the world, or New light", GeodataWidgets.FAINT);
            tip("A light's mark in the viewport or its row in the list chooses it. New light (N) adds one where"
                    + " you click.");
        }
        if (current == null || listOpen) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            drawList(lights);
        }
        if (current == null) {
            return;
        }
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        drawInspector(current);
    }

    void applyColor(LightColor next) {
        var colors = new LinkedHashMap<String, LightColor>();
        for (var path : getChosen()) {
            var restriction = session.getLightColorRestriction(path, next);
            if (!restriction.isEmpty()) {
                panel.showError(named(path) + plain(restriction) + ".");
                return;
            }
            colors.put(path, next);
        }
        panel.run(() -> session.setLightColors(colors, EditingSession.ofLights("Light colour", colors.size())));
    }

    void moveChosen(String path, Vector3 to) {
        var placements = moved(path, to);
        if (placements != null && !placements.isEmpty()) {
            panel.run(() -> session.setLightPlacements(placements, EditingSession.moveLabel(placements.size())));
        }
    }

    boolean canDrag(String path) {
        try {
            var light = session.getLight(path);
            return light.isPresent()
                    && light.orElseThrow().editable()
                    && session.getLightMoveRestriction(path).isEmpty();
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    boolean drag(String path, Vector3 to) {
        var light = session.getLight(path).orElse(null);
        if (light == null || light.location().equals(to)) {
            return false;
        }
        var placements = moved(path, to);
        if (placements == null) {
            return false;
        }
        panel.run(() -> session.previewLightPlacements(placements));
        return true;
    }

    void dragRadius(String path, float value) {
        var light = session.getLight(path).orElse(null);
        if (light == null || light.radius() == value) {
            return;
        }
        var placements = new LinkedHashMap<String, LightPlacement>();
        for (var each : reached(path)) {
            var next = new LightPlacement(session.getLight(each).orElseThrow().location(), value);
            var restriction = session.getLightPlacementRestriction(each, next);
            if (!restriction.isEmpty()) {
                panel.showError(named(each) + plain(restriction) + ".");
                return;
            }
            placements.put(each, next);
        }
        panel.run(() -> session.previewLightPlacements(placements));
    }

    void finishDrag() {
        panel.run(() -> {
            session.finishLightPlacement();
            session.finishLightShape();
        });
    }

    boolean canTurn(String path) {
        try {
            var light = session.getLight(path).orElse(null);
            return light != null
                    && light.editable()
                    && light.shape().isSpot()
                    && session.getLightShapeRestriction(path, nudged(light.shape()))
                            .isEmpty();
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    void turn(String path, LightShape next) {
        var light = session.getLight(path).orElse(null);
        if (light == null || light.shape().equals(next)) {
            return;
        }
        var from = light.shape();
        var shapes = new LinkedHashMap<String, LightShape>();
        for (var each : reached(path)) {
            var other = session.getLight(each).orElseThrow();
            if (!each.equals(path) && !other.shape().isSpot()) {
                continue;
            }
            var shape = each.equals(path) ? next : carryShape(from, next, other.shape());
            var restriction = session.getLightShapeRestriction(each, shape);
            if (!restriction.isEmpty()) {
                panel.showError(named(each) + plain(restriction) + ".");
                return;
            }
            shapes.put(each, shape);
        }
        panel.run(() -> session.previewLightShapes(shapes));
    }

    @Nullable
    String copyForDrag(String path) {
        var light = session.getLight(path).orElse(null);
        if (light == null || !session.getLightCopyRestriction(path).isEmpty()) {
            return null;
        }
        var copy = new String[1];
        panel.run(() -> copy[0] = session.duplicateLight(path, light.placement(), "Copy light"));
        if (copy[0] != null) {
            select(copy[0]);
        }
        return copy[0];
    }

    void focusChosen() {
        var paths = getChosen();
        if (paths.size() == 1) {
            focus(session.getLight(paths.getFirst()).orElseThrow());
            return;
        }
        if (paths.isEmpty()) {
            return;
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (var path : paths) {
            var at = session.getLight(path).orElseThrow().location();
            minX = Math.min(minX, at.x());
            minY = Math.min(minY, at.y());
            minZ = Math.min(minZ, at.z());
            maxX = Math.max(maxX, at.x());
            maxY = Math.max(maxY, at.y());
            maxZ = Math.max(maxZ, at.z());
        }
        var size = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ)) * .5f + 256;
        panel.flyTo((minX + maxX) * .5f, (minY + maxY) * .5f, (minZ + maxZ) * .5f, size);
    }

    void copyChosen() {
        var placements = new LinkedHashMap<String, LightPlacement>();
        for (var path : getChosen()) {
            var light = session.getLight(path).orElseThrow();
            var reason = session.getLightCopyRestriction(path);
            var place = reason.isEmpty() ? beside(light) : null;
            if (place == null) {
                panel.showError(named(path) + plain(reason.isEmpty() ? "No place beside it is free" : reason) + ".");
                return;
            }
            placements.put(path, place);
        }
        if (placements.isEmpty()) {
            return;
        }
        var copies = new ArrayList<String>();
        panel.run(() -> copies.addAll(session.duplicateLights(
                placements, placements.size() == 1 ? "Copy light" : "Copy " + placements.size() + " lights")));
        if (!copies.isEmpty()) {
            select(copies.getFirst());
            choose(copies, true);
        }
    }

    void deleteChosen() {
        var paths = getChosen();
        var reason = firstRestriction(paths, session::getLightDeleteRestriction);
        if (!reason.isEmpty()) {
            panel.showError(reason);
            return;
        }
        if (paths.isEmpty()) {
            return;
        }
        panel.run(() -> {
            session.deleteLights(paths, paths.size() == 1 ? "Delete light" : "Delete " + paths.size() + " lights");
            selected = "";
            choice.clear();
        });
    }

    void revertChosen() {
        var paths = revertible(getChosen());
        if (paths.isEmpty()) {
            return;
        }
        panel.run(() ->
                session.resetLights(paths, paths.size() == 1 ? "Revert light" : "Revert " + paths.size() + " lights"));
    }

    private void syncChoice() {
        if (!choice.getActive().equals(selected)) {
            choice.replace(selected);
        }
    }

    private void afterChoice() {
        selected = choice.getActive();
        listScroll = true;
        placing = false;
        reachedMeshes = -1;
        floorHeight = Float.NaN;
        if (!selected.isEmpty()) {
            panel.releaseObjects();
        }
    }

    private LightTemplate getTemplate(String map) {
        try {
            return session.getLightTemplate(map, placingSpot);
        } catch (RuntimeException unreadable) {
            return new LightTemplate(
                    "",
                    unreadable.getMessage() == null
                            ? "The lights of " + map + " cannot be read"
                            : unreadable.getMessage());
        }
    }

    private boolean drawTitle(String map, int count, boolean foldable) {
        GeodataWidgets.space(GeodataWidgets.ROW);
        var camera = panel.getCameraMap();
        var away = camera != null && !camera.isEmpty() && !camera.equals(map);
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var height = ImGui.getTextLineHeight();
        var clicked = false;
        var hot = false;
        if (foldable) {
            clicked = ImGui.invisibleButton("editingLightListFold", Math.max(1, width), height);
            hot = ImGui.isItemHovered();
        } else {
            ImGui.dummy(Math.max(1, width), height);
        }
        var draw = ImGui.getWindowDrawList();
        var room = width - (foldable ? 24 : 0);
        var title = HudWidgets.truncateToWidth(
                "LIGHTS OF " + map + " · " + EditorUi.plural(count, "light", "lights"), room);
        draw.addText(x, y, hot ? HudWidgets.TEXT : HudWidgets.MUTED, title);
        var after = ImGui.calcTextSizeX(title);
        if (away && room - after > 40) {
            draw.addText(
                    x + after,
                    y,
                    GeodataWidgets.FAINT,
                    HudWidgets.truncateToWidth(" · camera over " + camera, room - after));
        }
        if (foldable) {
            var turn = Motion.toggle("editing/lightListFold", listOpen, Motion.QUICK * 1.5f);
            HudWidgets.chevron(
                    draw,
                    x + width - 8,
                    HudWidgets.textMiddle(y),
                    turn,
                    hot ? HudWidgets.TEXT : HudWidgets.MUTED,
                    1.2f);
        }
        var esc = away
                ? "The camera is over " + camera + ". Esc lets go of " + EditingSession.shortName(selected)
                        + " to list the lights there."
                : "The lights of the map under the camera, or of the chosen light's map.";
        tip(foldable ? (listOpen ? "Fold the list away. " : "Show the list of lights, with a search. ") + esc : esc);
        return clicked;
    }

    private void drawList(List<LightRecord> lights) {
        GeodataWidgets.searchField("editingLightSearch", "Search lights", search, -1, false);
        GeodataWidgets.space(GeodataWidgets.ROW);
        var query = search.get().trim().toLowerCase(Locale.ROOT);
        var shown = lights.stream()
                .filter(light -> query.isEmpty()
                        || EditingSession.shortName(light.sourcePath())
                                .toLowerCase(Locale.ROOT)
                                .contains(query))
                .toList();
        var rows = Math.min(LIST_ROWS, Math.max(1, shown.size()));
        if (ImGui.beginChild("editingLightList", 0, rows * ImGui.getTextLineHeightWithSpacing(), false)) {
            for (var light : shown) {
                var path = light.sourcePath();
                var label = "     "
                        + EditingSession.shortName(path)
                        + " · "
                        + (light.shape().isSpot() ? "spotlight" : "light")
                        + " · "
                        + colorName(light.color())
                        + (light.isChanged() ? " · changed" : "");
                var note = EditorUi.unitsAndMeters(light.reach());
                var chosen = isChosen(path);
                var x = ImGui.getCursorScreenPosX();
                var y = ImGui.getCursorScreenPosY();
                if (GeodataWidgets.listItem("editingLight" + path, label, note, chosen)) {
                    var io = ImGui.getIO();
                    if (io.getKeyCtrl() || io.getKeySuper()) {
                        toggle(path);
                    } else if (io.getKeyShift()) {
                        add(path);
                    } else {
                        select(path);
                    }
                }
                var middle = HudWidgets.textMiddle(y);
                ImGui.getWindowDrawList().addCircleFilled(x + 8, middle, 4, color(light.color()));
                if (ImGui.isItemHovered()) {
                    hovered = path;
                }
                if (path.equals(selected) && listScroll) {
                    ImGui.setScrollHereY();
                    listScroll = false;
                }
            }
        }
        ImGui.endChild();
    }

    private void drawNewButtons(String map) {
        var width = GeodataWidgets.columnWidth(2);
        for (var spot : new boolean[] {false, true}) {
            LightTemplate template;
            try {
                template = session.getLightTemplate(map, spot);
            } catch (RuntimeException unreadable) {
                template = new LightTemplate(
                        "",
                        unreadable.getMessage() == null
                                ? "The lights of " + map + " cannot be read"
                                : unreadable.getMessage());
            }
            var reason = template.restriction();
            var on = placing && placingSpot == spot;
            if (spot) {
                ImGui.sameLine();
            }
            ImGui.beginDisabled(!reason.isEmpty() && !on);
            if (GeodataWidgets.actionButton(
                    spot ? "editingNewSpot" : "editingNewLight",
                    Glyph.PLUS,
                    spot ? "New spotlight" : "New light",
                    width,
                    on)) {
                togglePlacing(spot);
            }
            ImGui.endDisabled();
            if (!reason.isEmpty()) {
                tip(plain(reason) + ".");
            } else {
                tip((spot
                                ? "Adds spotlights: click a wall, a floor or the ground in the viewport (Shift+N). "
                                : "Adds lights: click a wall, a floor or the ground in the viewport (N). ")
                        + "It copies "
                        + EditingSession.shortName(template.path())
                        + ", the most ordinary "
                        + (spot ? "spotlight" : "light")
                        + " of this map, with the colour and radius you choose.");
            }
        }
    }

    private void drawNewLight() {
        ImGui.pushID("editingNewLight");
        EditorUi.section(placingSpot ? "New spotlight" : "New light");
        var preset = drawPresets("editingNewPresets", getNewColor(), ImGui.getContentRegionAvailX());
        if (preset != null) {
            newHue = preset.hue();
            newSaturation = preset.saturation();
            newBrightness[0] = preset.brightness();
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        HudWidgets.gradientSlider(
                "Radius",
                placingSpot ? newSpotRadius : newRadius,
                0,
                LightPlacement.MAX_RADIUS,
                "%.0f",
                2,
                reachStops(getNewColor()),
                "");
        tip("How far the new light reaches: 25 units for each step of the radius and one more, "
                + EditorUi.unitsAndMeters(25 * (getNewRadius() + 1)) + " now. One unit is 4 cm. (LightRadius)");
        HudWidgets.gradientSlider(
                "Distance from the surface",
                placingSpot ? newSpotOffset : newOffset,
                0,
                MAX_OFFSET,
                "%.0f",
                1,
                reachStops(getNewColor()),
                "");
        var template = getTemplate();
        var shape = template == null || template.path().isEmpty()
                ? null
                : session.getLight(template.path()).map(LightRecord::shape).orElse(null);
        var distance = placingSpot
                ? "How far the new spotlight stands out from the wall, floor or ground that the click lands on. It"
                        + " faces the point clicked."
                : "How far the new light stands from the wall, floor or ground that the click lands on, along the"
                        + " surface's normal.";
        if (placingSpot && shape != null) {
            var pool = poolRadius(getNewOffset(), 25 * (getNewRadius() + 1), shape.coneDegrees());
            distance += String.format(
                    Locale.ROOT,
                    " It lights a pool about %,.0f units across there, in a cone of %.0f°.",
                    pool * 2,
                    shape.coneDegrees() * 2);
        }
        tip(distance);
        GeodataWidgets.space(GeodataWidgets.ROW);
        var copies = template != null && !template.path().isEmpty();
        EditorUi.muted((copies ? "Copies " + EditingSession.shortName(template.path()) + " · c" : "C")
                + "lick a wall, a floor or the ground");
        tip((copies
                        ? "A new light is a copy of a saved light of the map: its class, its flags and its other"
                                + " values, such as its type, its effect"
                                + (placingSpot ? " and its cone" : "")
                                + ". This is the most ordinary "
                                + (placingSpot ? "spotlight" : "light")
                                + " of the map. "
                        : "")
                + "Shift+click adds more, and Esc stops.");
        ImGui.popID();
    }

    private void drawInspector(LightRecord light) {
        var paths = getChosen();
        drawCard(light, paths);
        if (!light.editable()) {
            return;
        }
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        drawColor(light);
        drawPlacement(light);
        drawDirection(light);
    }

    private void drawCard(LightRecord light, List<String> paths) {
        var path = light.sourcePath();
        var shape = light.shape();
        var many = paths.size() > 1;
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var pad = GeodataWidgets.BLOCK;
        var inner = width - 2 * pad;
        var left = x + pad;
        var button = HudWidgets.CONTROL_HEIGHT;
        draw.channelsSplit(2);
        draw.channelsSetCurrent(1);
        ImGui.setCursorScreenPos(left, y + pad);
        ImGui.indent(pad);
        var top = ImGui.getCursorScreenPosY();
        var count = shape.isSpot() ? 5 : 4;
        var icons = count * button + (count - 1) * 4;
        var room = inner - icons - GeodataWidgets.ROW;
        var fonts = panel.getFonts();
        var font = fonts == null ? null : fonts.getHeadingFont(15);
        if (font != null) {
            ImGui.pushFont(font, 15f);
        }
        draw.addText(
                left,
                top + (button - ImGui.getFontSize()) * .5f - 1,
                HudWidgets.TEXT,
                HudWidgets.truncateToWidth(
                        many ? EditorUi.plural(paths.size(), "light", "lights") : EditingSession.shortName(path),
                        room));
        if (font != null) {
            ImGui.popFont();
        }
        ImGui.dummy(Math.max(1, room), button);
        tip(
                many
                        ? names(paths)
                                + ". The fields show "
                                + EditingSession.shortName(path)
                                + ", and each change reaches all of them. "
                                + EditingPanel.COMMAND_KEY
                                + "+click on a mark or a row adds or takes away a light."
                        : path
                                + " ("
                                + light.className()
                                + ")"
                                + (light.created()
                                        ? ", new in this session."
                                        : light.isChanged() ? ", changed." : "."));
        ImGui.setCursorScreenPos(left + inner - icons, top);
        drawActions(light, paths);
        ImGui.setCursorScreenPos(left, top + button);
        ImGui.dummy(0, 0);
        if (many) {
            GeodataWidgets.note(
                    kinds(paths) + " · the fields show " + EditingSession.shortName(path), HudWidgets.MUTED, inner);
            tip("A slider or a preset gives every chosen light the value it sets. Location moves them all as far"
                    + " as the active light moves.");
        } else {
            drawFacts(light, inner);
        }
        if (!light.editable()) {
            var reason = light.restriction();
            GeodataWidgets.note(plain(reason) + ".", HudWidgets.MUTED, inner);
            if (!plain(reason).equals(reason)) {
                tip(reason + ".");
            }
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        drawSwatch(light, inner);
        if (light.editable()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            drawPresetRow(light, inner);
        }
        var bottom = ImGui.getItemRectMaxY() + pad;
        ImGui.unindent(pad);
        draw.channelsSetCurrent(0);
        draw.addRectFilled(x, y, x + width, bottom, GeodataWidgets.CARD, 8f);
        draw.channelsMerge();
        ImGui.setCursorScreenPos(x, bottom);
        ImGui.dummy(width, 0);
    }

    private String kinds(List<String> paths) {
        var spots = 0;
        for (var path : paths) {
            if (session.getLight(path).map(each -> each.shape().isSpot()).orElse(false)) {
                spots++;
            }
        }
        return spots == paths.size() ? "All spotlights" : spots == 0 ? "All lights" : "Lights and spotlights";
    }

    private void drawFacts(LightRecord light, float inner) {
        var shape = light.shape();
        GeodataWidgets.note(
                light.className()
                        + " · "
                        + shape.describe()
                        + " · reaches "
                        + EditorUi.unitsAndMeters(light.reach())
                        + (shape.isSpot()
                                ? String.format(Locale.ROOT, " in a cone of %.0f°", shape.coneDegrees() * 2)
                                : ""),
                HudWidgets.MUTED,
                inner);
        tip("It reaches 25 units for each step of its radius and one more. One unit is 4 cm"
                + (shape.isSpot() ? ", inside its cone" : ", all around")
                + "."
                + (Float.isFinite(floorHeight)
                        ? String.format(Locale.ROOT, " It stands %,.0f units over the floor under it.", floorHeight)
                        : ""));
        if (shape.isSpot()) {
            var facing = facing(shape);
            GeodataWidgets.note(Character.toUpperCase(facing.charAt(0)) + facing.substring(1), HudWidgets.MUTED, inner);
            tip("North is up on the world map.");
            if (shape.direction().z() > UPWARD) {
                GeodataWidgets.note(
                        "It faces up, so it lights the walls and roofs above it, not the ground.",
                        GeodataWidgets.WARNING,
                        inner);
            }
        }
    }

    private void drawActions(LightRecord light, List<String> paths) {
        var path = light.sourcePath();
        var many = paths.size() > 1;
        if (HudWidgets.iconButton("editingLightFocus", Glyph.FOCUS, false, HudWidgets.TEXT)) {
            focusChosen();
        }
        tip(
                many
                        ? "Focus (F): fly the camera to the chosen lights, so that all of them show."
                        : "Focus (F): fly the camera to the light, so that its reach shows.");
        ImGui.sameLine(0, 4);
        var copyReason = firstRestriction(paths, session::getLightCopyRestriction);
        ImGui.beginDisabled(!copyReason.isEmpty());
        if (HudWidgets.iconButton("editingLightCopy", Glyph.COPY, false, HudWidgets.TEXT)) {
            if (many) {
                copyChosen();
            } else {
                copy(light);
            }
        }
        ImGui.endDisabled();
        tip(
                !copyReason.isEmpty()
                        ? copyReason
                        : many
                                ? "Copy (" + EditingPanel.COMMAND_KEY
                                        + "+D): add a copy of each chosen light beside it."
                                : "Copy (" + EditingPanel.COMMAND_KEY
                                        + "+D): add a copy of the light beside it, with its colour and radius."
                                        + " Alt+drag of its mark or its arrows copies it too.");
        ImGui.sameLine(0, 4);
        var deleteReason = firstRestriction(paths, session::getLightDeleteRestriction);
        ImGui.beginDisabled(!deleteReason.isEmpty());
        if (HudWidgets.iconButton("editingLightDelete", Glyph.TRASH, false, GeodataWidgets.BLOCKED)) {
            if (many) {
                deleteChosen();
            } else {
                delete(path);
            }
        }
        ImGui.endDisabled();
        tip(
                !deleteReason.isEmpty()
                        ? deleteReason
                        : many
                                ? "Delete (Del): take the chosen lights out of their map. Undo brings them back."
                                : "Delete (Del): take the light out of its map. Save takes its light away from the"
                                        + " meshes, the walls and the ground. Undo brings it back.");
        ImGui.sameLine(0, 4);
        if (many) {
            var reverted = revertible(paths);
            ImGui.beginDisabled(reverted.isEmpty());
            if (HudWidgets.iconButton("editingLightRevert", Glyph.UNDO, false, HudWidgets.TEXT)) {
                revertChosen();
            }
            ImGui.endDisabled();
            tip(
                    reverted.isEmpty()
                            ? "Revert: the chosen lights are as in the client."
                            : "Revert: give " + EditorUi.plural(reverted.size(), "changed light", "changed lights")
                                    + " their values in the client back.");
        } else {
            ImGui.beginDisabled(!light.isChanged() || !light.editable() || light.created());
            if (HudWidgets.iconButton("editingLightRevert", Glyph.UNDO, false, HudWidgets.TEXT)) {
                panel.run(() -> session.resetLight(path, "Revert light"));
            }
            ImGui.endDisabled();
            tip(
                    light.created()
                            ? "Revert: a new light has no values in the client. Delete takes it away."
                            : !light.editable()
                                    ? plain(light.restriction()) + "."
                                    : light.isChanged()
                                            ? "Revert: give the light its colour, place, radius, direction and cone in"
                                                    + " the client back."
                                            : "Revert: the light is as in the client.");
        }
        if (light.shape().isSpot()) {
            ImGui.sameLine(0, 4);
            var canAim = canTurn(path);
            ImGui.beginDisabled(!canAim);
            if (HudWidgets.iconButton("editingLightAim", Glyph.CAMERA, false, HudWidgets.TEXT)) {
                isAimFromCamera();
            }
            ImGui.endDisabled();
            tip(
                    canAim
                            ? "Aim from the camera ("
                                    + EditingPanel.COMMAND_KEY
                                    + "+Shift+F): face the "
                                    + (many ? "chosen spotlights" : "spotlight")
                                    + " the way the camera looks."
                            : "Aim from the camera: this spotlight cannot turn.");
        }
    }

    private List<String> revertible(List<String> paths) {
        var result = new ArrayList<String>();
        for (var path : paths) {
            var light = session.getLight(path).orElse(null);
            if (light != null && light.isChanged() && light.editable() && !light.created()) {
                result.add(path);
            }
        }
        return result;
    }

    private void drawPresetRow(LightRecord light, float width) {
        var preset = drawPresets("editingLightPresets", light.color(), width);
        if (preset != null) {
            applyColor(preset.color());
        }
    }

    private void drawColor(LightRecord light) {
        var path = light.sourcePath();
        var color = light.color();
        if (GeodataWidgets.colorPopup(PICKER, pickerRgb)) {
            session.finishLightColor();
        }
        if (ImGui.isPopupOpen(PICKER) && !Arrays.equals(pickerRgb, pickerOpened)) {
            var picked = UnrealHsv.fromRgb(pickerRgb[0], pickerRgb[1], pickerRgb[2]);
            var next = new LightColor(color.brightness(), picked[0], picked[1]);
            if (!next.equals(color)) {
                previewColor(path, next);
            }
        }
        var next = colorSliders(color, temperature, hue, saturation, brightness);
        if (!next.equals(color)) {
            previewColor(path, next);
        }
        if (!ImGui.isMouseDown(0) && !ImGui.isAnyItemActive() && !ImGui.isPopupOpen(PICKER)) {
            session.finishLightColor();
        }
    }

    private void drawSwatch(LightRecord light, float width) {
        var color = light.color();
        var rgb = rgb(color.hue(), color.saturation());
        var canColor = light.editable();
        ImGui.beginDisabled(!canColor);
        if (ImGui.colorButton(
                "##editingLightSwatch",
                rgb[0],
                rgb[1],
                rgb[2],
                1,
                ImGuiColorEditFlags.NoAlpha | ImGuiColorEditFlags.NoTooltip | ImGuiColorEditFlags.NoDragDrop,
                width,
                SWATCH_HEIGHT)) {
            System.arraycopy(rgb, 0, pickerRgb, 0, 3);
            System.arraycopy(rgb, 0, pickerOpened, 0, 3);
            ImGui.openPopup(PICKER);
        }
        ImGui.endDisabled();
        tip(canColor ? "Click to choose its colour on a wheel. The brightness stays." : light.restriction() + ".");
    }

    private void previewColor(String path, LightColor next) {
        var from = session.getLight(path).map(LightRecord::color).orElse(next);
        var colors = new LinkedHashMap<String, LightColor>();
        for (var each : reached(path)) {
            var color = each.equals(path)
                    ? next
                    : carry(from, next, session.getLight(each).orElseThrow().color());
            var restriction = session.getLightColorRestriction(each, color);
            if (!restriction.isEmpty()) {
                panel.showError(named(each) + plain(restriction) + ".");
                return;
            }
            colors.put(each, color);
        }
        panel.run(() -> session.previewLightColors(colors));
    }

    private List<String> reached(String path) {
        var paths = getChosen();
        return paths.contains(path) ? paths : List.of(path);
    }

    private String named(String path) {
        return getChosen().size() > 1 ? EditingSession.shortName(path) + ": " : "";
    }

    private void drawPlacement(LightRecord light) {
        var path = light.sourcePath();
        EditorUi.section("Place");
        tip("Drag the arrows in the viewport, or the mark to move the light at its height. A light does not move"
                + " into a wall, or into or out of the sky.");
        var reason = session.getLightMoveRestriction(path);
        if (reason.isEmpty()) {
            drawLocation(light);
        } else {
            restrictionLine(reason);
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        radius[0] = light.radius();
        ImGui.beginDisabled(!reason.isEmpty());
        HudWidgets.gradientSlider(
                "Radius", radius, 0, LightPlacement.MAX_RADIUS, "%.0f", 2, reachStops(light.color()), "");
        ImGui.endDisabled();
        tip(
                reason.isEmpty()
                        ? "How far the light reaches: 25 units for each step of the radius and one more, "
                                + EditorUi.unitsAndMeters(light.reach())
                                + " now. One unit is 4 cm. The square handle in the viewport drags it too. The left"
                                + " half of the track holds 0 to 64. (LightRadius)"
                        : "It reaches " + EditorUi.unitsAndMeters(light.reach()) + ". " + plain(reason) + ".");
        if (reason.isEmpty() && radius[0] != light.radius() && Math.round(radius[0]) != light.radius()) {
            dragRadius(path, Math.round(radius[0]));
        }
        if (!ImGui.isMouseDown(0) && !ImGui.isAnyItemActive()) {
            session.finishLightPlacement();
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        var label = reachedMeshes < 0
                ? "Outline the meshes it reaches"
                : reachedMeshes == 1
                        ? "Outline the 1 mesh it reaches"
                        : String.format(Locale.ROOT, "Outline the %,d meshes it reaches", reachedMeshes);
        outlineReach = HudWidgets.switchRow(
                label,
                outlineReach,
                true,
                "Boxes around the static meshes of the loaded tiles whose bounds this light reaches"
                        + (light.shape().isSpot() ? ", inside its cone" : "")
                        + ", in its colour. The brighter a box, the more light its best lit point gets.");
    }

    private void drawLocation(LightRecord light) {
        var path = light.sourcePath();
        var at = light.location();
        location[0] = at.x();
        location[1] = at.y();
        location[2] = at.z();
        if (!ImGui.isAnyItemActive()) {
            for (var i = 0; i < 3; i++) {
                locationText[i].set(EditorUi.compact(location[i]));
            }
        }
        var help = "Where the light stands in the world, in units (1 unit is 4 cm), now "
                + EditorUi.metersXyz(at.x(), at.y(), at.z()) + ". " + RelativeNumber.RULE
                + " Enter or leaving the field applies it as one Undo step.";
        if (panel.axisRow(
                "lightLocation",
                "Location",
                help,
                new String[] {"X", "Y", "Z"},
                locationText,
                location,
                new String[] {"World X.", "World Y.", "World Z, up."},
                null)) {
            moveChosen(path, new Vector3(location[0], location[1], location[2]));
        }
    }

    private void drawDirection(LightRecord light) {
        var shape = light.shape();
        if (!shape.isSpot()) {
            return;
        }
        var path = light.sourcePath();
        GeodataWidgets.space(GeodataWidgets.SECTION);
        var turnReason = session.getLightShapeRestriction(path, nudged(shape));
        var coneReason = session.getLightShapeRestriction(
                path, shape.withCone(shape.cone() == LightShape.MAX_CONE ? shape.cone() - 1 : shape.cone() + 1));
        if (!turnReason.isEmpty() && !coneReason.isEmpty()) {
            restrictionLine(turnReason);
            return;
        }
        var base = Math.abs(units(shape.rotation().pitch())) <= QUARTER
                ? shape.rotation()
                : shape.facing(shape.direction());
        var shownYaw = degrees(base.yaw());
        var shownPitch = degrees(base.pitch());
        var current = session.getLight(path).orElse(light).shape();
        var shownCone = current.coneDegrees() * 2;
        yaw[0] = shownYaw;
        pitch[0] = shownPitch;
        cone[0] = shownCone;
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var pad = GeodataWidgets.BLOCK;
        var inner = width - 2 * pad;
        draw.channelsSplit(2);
        draw.channelsSetCurrent(1);
        ImGui.setCursorScreenPos(x + pad, y + pad);
        ImGui.indent(pad);
        ImGui.beginDisabled(!turnReason.isEmpty());
        HudWidgets.directionDials(
                "##editingLightDials",
                yaw,
                pitch,
                current.coneDegrees(),
                (float) Math.toDegrees(LightGestures.innerAngle(current)),
                color(light.color()),
                inner,
                DIAL_HEIGHT);
        ImGui.endDisabled();
        if (!turnReason.isEmpty()) {
            tip(plain(turnReason) + ".");
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        var third = (inner - 2 * ImGui.getStyle().getItemSpacingX()) / 3;
        GeodataWidgets.columns(
                inner,
                () -> {
                    ImGui.beginDisabled(!turnReason.isEmpty());
                    GeodataWidgets.compactDrag(
                            "Yaw", "##editingLightYaw", yaw, third, .5f, -180, 180, "%.0f°", HudWidgets.MUTED);
                    ImGui.endDisabled();
                    tip(
                            turnReason.isEmpty()
                                    ? "Rotation yaw: which way the spotlight faces around the vertical. 0 is east"
                                            + " (+X), 90 south (+Y), -90 north. Drag it, double-click to type, or drag"
                                            + " around the compass."
                                    : plain(turnReason) + ".");
                    return false;
                },
                () -> {
                    ImGui.beginDisabled(!turnReason.isEmpty());
                    GeodataWidgets.compactDrag(
                            "Pitch", "##editingLightPitch", pitch, third, .5f, -90, 90, "%.0f°", HudWidgets.MUTED);
                    ImGui.endDisabled();
                    tip(
                            turnReason.isEmpty()
                                    ? "Rotation pitch: -90 faces straight down, 0 level, 90 straight up. Drag it,"
                                            + " double-click to type, or drag in the side view."
                                    : plain(turnReason) + ".");
                    return false;
                },
                () -> {
                    ImGui.beginDisabled(!coneReason.isEmpty());
                    GeodataWidgets.compactDrag(
                            "Cone", "##editingLightCone", cone, third, .5f, 0, 179, "%.0f°", HudWidgets.MUTED);
                    ImGui.endDisabled();
                    tip(
                            coneReason.isEmpty()
                                    ? String.format(
                                            Locale.ROOT,
                                            "How wide the spotlight opens, as the full angle: the wedge in the side"
                                                    + " view. (LightCone %d of 255, where 128 opens 120°)",
                                            current.cone())
                                    : plain(coneReason) + ".");
                    return false;
                });
        var bottom = ImGui.getItemRectMaxY() + pad;
        ImGui.unindent(pad);
        draw.channelsSetCurrent(0);
        draw.addRectFilled(x, y, x + width, bottom, GeodataWidgets.CARD, 8f);
        draw.channelsMerge();
        ImGui.setCursorScreenPos(x, bottom);
        ImGui.dummy(width, 0);
        if (yaw[0] != shownYaw) {
            turn(path, shape.withRotation(new Rotator(base.pitch(), toUnits(yaw[0]), base.roll())));
        } else if (pitch[0] != shownPitch) {
            turn(path, shape.withRotation(new Rotator(toUnits(pitch[0]), base.yaw(), base.roll())));
        } else if (cone[0] != shownCone) {
            var next = LightShape.coneFor(cone[0] / 2);
            if (next != current.cone()) {
                turn(path, current.withCone(next));
            }
        }
        if (!ImGui.isMouseDown(0) && !ImGui.isAnyItemActive()) {
            session.finishLightShape();
        }
    }

    private @Nullable Map<String, LightPlacement> moved(String path, Vector3 to) {
        var light = session.getLight(path).orElse(null);
        if (light == null) {
            return null;
        }
        var from = light.location();
        var placements = new LinkedHashMap<String, LightPlacement>();
        for (var each : reached(path)) {
            var other = session.getLight(each).orElseThrow();
            var at = other.location();
            var place = each.equals(path)
                    ? to
                    : new Vector3(at.x() + to.x() - from.x(), at.y() + to.y() - from.y(), at.z() + to.z() - from.z());
            var next = new LightPlacement(place, other.radius());
            var restriction = session.getLightPlacementRestriction(each, next);
            if (!restriction.isEmpty()) {
                panel.showError(named(each) + plain(restriction) + ".");
                return null;
            }
            placements.put(each, next);
        }
        return placements;
    }

    private void focus(LightRecord light) {
        var at = light.location();
        panel.flyTo(at.x(), at.y(), at.z(), Math.clamp(light.reach(), 128, 1024));
    }

    private void copy(LightRecord light) {
        var place = beside(light);
        if (place != null) {
            panel.run(() -> select(session.duplicateLight(light.sourcePath(), place, "Copy light")));
            return;
        }
        var at = light.location();
        panel.showError(plain(session.getLightPlacementRestriction(
                        light.sourcePath(),
                        new LightPlacement(new Vector3(at.x(), at.y() - 64, at.z()), light.radius())))
                + ".");
    }

    private @Nullable LightPlacement beside(LightRecord light) {
        return session.placeBeside(light.sourcePath());
    }

    private void delete(String path) {
        panel.run(() -> {
            session.deleteLight(path);
            if (path.equals(selected)) {
                selected = "";
                choice.clear();
            }
        });
    }

    private String mapAt(Vector3 location) {
        return session.getMapAt(location.x(), location.y());
    }

    private static void restrictionLine(String reason) {
        var shown = plain(reason);
        EditorUi.muted(shown + ".");
        if (!shown.equals(reason)) {
            tip(reason + ".");
        }
    }

    private static @Nullable Preset drawPresets(String id, LightColor current, float width) {
        var colors = new float[PRESETS.size()][];
        var names = new String[PRESETS.size()];
        var chosen = -1;
        for (var i = 0; i < PRESETS.size(); i++) {
            var preset = PRESETS.get(i);
            colors[i] = rgb(preset.hue(), preset.saturation());
            names[i] = preset.name();
            if (preset.hue() == current.hue()
                    && preset.saturation() == current.saturation()
                    && preset.brightness() == current.brightness()) {
                chosen = i;
            }
        }
        var clicked = GeodataWidgets.swatchRow(id, colors, names, chosen, width);
        return clicked < 0 ? null : PRESETS.get(clicked);
    }

    private static String names(List<String> paths) {
        var result = new StringBuilder();
        for (var i = 0; i < paths.size(); i++) {
            if (i > 0) {
                result.append(i == paths.size() - 1 ? " and " : ", ");
            }
            result.append(EditingSession.shortName(paths.get(i)));
        }
        return result.toString();
    }

    private static String firstRestriction(List<String> paths, Function<String, String> restriction) {
        for (var path : paths) {
            var reason = restriction.apply(path);
            if (!reason.isEmpty()) {
                return (paths.size() > 1 ? EditingSession.shortName(path) + ": " : "") + plain(reason) + ".";
            }
        }
        return "";
    }

    private static LightColor colorSliders(
            LightColor color, float[] kelvin, float[] hues, float[] whites, float[] level) {
        var full = rgb(color.hue(), color.saturation());
        var temperatureStops = new int[12];
        for (var i = 0; i < temperatureStops.length; i++) {
            var warm = kelvinRgb(WARMEST + (COOLEST - WARMEST) * i / (temperatureStops.length - 1));
            temperatureStops[i] = ImGui.colorConvertFloat4ToU32(warm[0], warm[1], warm[2], 1);
        }
        var result = color;
        kelvin[0] = temperature(color.hue(), color.saturation());
        if (HudWidgets.gradientSlider(
                "Temperature", kelvin, WARMEST, COOLEST, "%.0f K", 1, temperatureStops, "custom")) {
            result = temperatureColor(kelvin[0], color.brightness());
        }
        tip("The colour of a real light source: about 1,900 K a candle, 2,700 K a house lamp, 4,100 K moonlight,"
                + " 5,500 K daylight and 9,000 K a blue sky. It sets Hue and Saturation; 'custom' means a colour"
                + " that no glowing body has.");
        var hueStops = new int[13];
        for (var i = 0; i < hueStops.length; i++) {
            var pure = rgb(Math.round(i * 255f / (hueStops.length - 1)), 0);
            hueStops[i] = ImGui.colorConvertFloat4ToU32(pure[0], pure[1], pure[2], 1);
        }
        hues[0] = color.hue();
        if (HudWidgets.gradientSlider("Hue", hues, 0, 255, "%.0f", 1, hueStops, "")) {
            result = new LightColor(color.brightness(), Math.round(hues[0]), color.saturation());
        }
        tip("The colour around the circle, from red at 0 through yellow, green and blue back to red at 255."
                + " (LightHue)");
        var white = rgb(color.hue(), 255);
        var half = rgb(color.hue(), 128);
        var pure = rgb(color.hue(), 0);
        var saturationStops = new int[] {
            ImGui.colorConvertFloat4ToU32(white[0], white[1], white[2], 1),
            ImGui.colorConvertFloat4ToU32(half[0], half[1], half[2], 1),
            ImGui.colorConvertFloat4ToU32(pure[0], pure[1], pure[2], 1)
        };
        whites[0] = (255 - color.saturation()) * 100f / 255;
        if (HudWidgets.gradientSlider("Saturation", whites, 0, 100, "%.0f%%", 1, saturationStops, "")) {
            result = new LightColor(
                    color.brightness(), color.hue(), Math.clamp(Math.round(255 - whites[0] * 255 / 100), 0, 255));
        }
        tip(String.format(
                Locale.ROOT,
                "How much colour the light has: 0%% is white, 100%% the full colour of its hue. (LightSaturation %d,"
                        + " which counts from 255 for white down to 0)",
                color.saturation()));
        var brightnessStops = new int[5];
        for (var i = 0; i < brightnessStops.length; i++) {
            var place = i / (float) (brightnessStops.length - 1);
            var times = place * place * LightColor.MAX_BRIGHTNESS / 255;
            brightnessStops[i] = ImGui.colorConvertFloat4ToU32(
                    Math.min(1, full[0] * times), Math.min(1, full[1] * times), Math.min(1, full[2] * times), 1);
        }
        level[0] = color.brightness();
        if (HudWidgets.gradientSlider(
                "Brightness", level, 0, LightColor.MAX_BRIGHTNESS, "%.0f", 2, brightnessStops, "")) {
            result = new LightColor(Math.round(level[0]), color.hue(), color.saturation());
        }
        tip("How strong the light is. At 255 it gives its colour in full to what it reaches. Above that it washes"
                + " out towards white. The left half of the track holds 0 to 255. (LightBrightness)");
        return result;
    }

    private static int[] reachStops(LightColor color) {
        var full = rgb(color.hue(), color.saturation());
        return new int[] {
            ImGui.colorConvertFloat4ToU32(full[0] * .18f, full[1] * .18f, full[2] * .18f, 1),
            ImGui.colorConvertFloat4ToU32(full[0], full[1], full[2], 1)
        };
    }

    private static int units(int value) {
        return (short) value;
    }

    private static float degrees(int value) {
        return units(value) * 360f / 65536;
    }

    private static int toUnits(float degrees) {
        return Math.round(degrees * 65536 / 360);
    }

    private static LightShape nudged(LightShape shape) {
        var rotation = shape.rotation();
        return shape.withRotation(new Rotator(rotation.pitch(), rotation.yaw() + 1, rotation.roll()));
    }
}
