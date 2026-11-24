package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.world.WorldDimensions;
import com.rost.cruma.viewer.application.OrthoSlice;
import com.rost.cruma.viewer.application.OrthoSlice.Track;
import com.rost.cruma.viewer.application.UiContext.Rendering;
import com.rost.cruma.viewer.config.CaptureView;
import com.rost.cruma.viewer.config.CutCircle;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.CameraView;
import com.rost.cruma.viewer.render.LoadedTiles;
import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImInt;
import imgui.type.ImString;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class OrthoSliceControls {
    private static final String[] AXES = {"X", "Y", "Z"};
    private static final float FIELD_WIDTH = 84f;
    private static final String OFF = "Off";
    private static final String BAR_USE =
            "Only what stands between the knobs shows. Shift makes a drag ten times finer, and Alt with the wheel over"
                    + " the view moves the cut.";
    private static final float WIDE_ROW = 460f;
    private static final float SHOW_ALL_WIDTH = 96f;
    private static final float[] NO_TICKS = {};
    private static final int MOST_TICKS = 64;
    private static final Map<String, BoundField> FIELDS = new HashMap<>();
    private static final float[] LAST_TRACK = new float[3];
    private static Track lastBar = new Track(0, 1);

    private OrthoSliceControls() {}

    public static void draw(Rendering r, Camera camera, @Nullable Bounds3f scene, LoadedTiles tiles) {
        switch (camera.getView()) {
            case TOP -> {
                drawHeightBar(r, camera, scene);
                GeodataWidgets.hint(heightHint(lastBar)
                        + " Scene settings (F3) also keep the terrain or the meshes whole, and cut inside a circle.");
            }
            case FRONT, SIDE -> drawSlice(r.getSlice(camera.getView()), camera, scene, tiles);
            case PERSPECTIVE -> {}
        }
    }

    public static void drawHeightCuts(Rendering r, Camera camera, @Nullable Bounds3f scene) {
        drawHeightBar(r, camera, scene);
        if (r.orthoCutTop || r.orthoCutBottom) {
            r.orthoKeepTerrain = HudWidgets.switchRow(
                    "Keep Terrain",
                    r.orthoKeepTerrain,
                    true,
                    "Draw the terrain whole and cut only the other layers, so the ground stays around a cut building. "
                            + "Capture views call this keep terrain.");
            r.orthoKeepStaticMeshes = HudWidgets.switchRow(
                    "Keep Static Meshes",
                    r.orthoKeepStaticMeshes,
                    true,
                    "Draw static meshes whole, with grass and particle effects. "
                            + "Capture views call this keep static meshes.");
            r.orthoKeepCsg = HudWidgets.switchRow(
                    "Keep CSG Geometry",
                    r.orthoKeepCsg,
                    true,
                    "Draw CSG/BSP building geometry whole. Keep Terrain and Keep CSG Geometry together cut only static"
                            + " meshes. Capture views call this keep CSG.");
            var circle = HudWidgets.switchRow(
                    "Cut Inside Circle",
                    r.orthoCircle,
                    true,
                    "Cut only inside a circle around a world point, for example one tower, so the land, trees, rocks"
                        + " and water around it stay. Capture views call this circle_x, circle_y and circle_radius.");
            if (circle && !r.orthoCircle) {
                centerCircleOnCamera(r, camera);
            }
            r.orthoCircle = circle;
            if (r.orthoCircle) {
                drawNumber("Center X", r.orthoCircleX, 100);
                drawNumber("Center Y", r.orthoCircleY, 100);
                GeodataWidgets.tip(
                        "World X and Y of the circle's centre, like circle_x and circle_y of a capture view.");
                HudWidgets.sliderInt("Radius", r.orthoCircleRadius, 100, 16_000);
                r.orthoCircleRadius[0] = Math.clamp(r.orthoCircleRadius[0], 1, CutCircle.MAX_RADIUS);
                GeodataWidgets.tip(
                        "World units, like the heights. A map tile is 32768 units wide. Capture views call this"
                                + " circle_radius.");
                if (HudWidgets.button("cutCircleOnCamera", null, "Center on camera", -1, 28, false, true)) {
                    centerCircleOnCamera(r, camera);
                }
                GeodataWidgets.tip("Move the circle's centre to the point under the camera.");
            }
        }
        GeodataWidgets.hint(heightHint(lastBar) + " Camera tile " + tileName(camera)
                + ". The same heights work as Top Z and Bottom Z of a tile view in Capture map images.");
    }

    static int @Nullable [] extent(int axis, @Nullable Bounds3f scene, int @Nullable [] tiles) {
        if (axis != 2 && tiles != null) {
            return tiles;
        }
        if (scene == null) {
            return null;
        }
        return switch (axis) {
            case 0 -> new int[] {(int) Math.floor(scene.minX()), (int) Math.ceil(scene.maxX())};
            case 1 -> new int[] {(int) Math.floor(scene.minY()), (int) Math.ceil(scene.maxY())};
            default ->
                new int[] {
                    Math.clamp((int) Math.floor(scene.minZ()), -CaptureView.HEIGHT_LIMIT, CaptureView.HEIGHT_LIMIT),
                    Math.clamp((int) Math.ceil(scene.maxZ()), -CaptureView.HEIGHT_LIMIT, CaptureView.HEIGHT_LIMIT)
                };
        };
    }

    static float[] tileTicks(Track track) {
        var low = Math.min(track.nearEnd(), track.farEnd());
        var high = Math.max(track.nearEnd(), track.farEnd());
        var first = Math.ceilDiv(low, WorldDimensions.TILE_SIZE);
        var last = Math.floorDiv(high, WorldDimensions.TILE_SIZE);
        if (last < first || last - first + 1 > MOST_TICKS) {
            return NO_TICKS;
        }
        var ticks = new float[last - first + 1];
        for (var index = 0; index < ticks.length; index++) {
            ticks[index] = track.position((first + index) * WorldDimensions.TILE_SIZE);
        }
        return ticks;
    }

    public static String describe(Rendering r, CameraView view) {
        var text = new StringBuilder("Slice");
        switch (view) {
            case TOP -> {
                if (r.orthoCutTop) {
                    text.append(" · Above Z ").append(number(r.orthoTop[0]));
                }
                if (r.orthoCutBottom) {
                    text.append(" · Below Z ").append(number(r.orthoBottom[0]));
                }
            }
            case FRONT, SIDE -> {
                var slice = r.getSlice(view);
                var axis = AXES[view.getDepthAxis()];
                if (slice.cutNear) {
                    text.append(" · In front of ").append(axis).append(' ').append(number(slice.near[0]));
                }
                if (slice.cutFar) {
                    text.append(" · Behind ").append(axis).append(' ').append(number(slice.far[0]));
                }
            }
            case PERSPECTIVE -> {}
        }
        return r.isSliced(view) ? text.toString() : "Slice · Nothing is cut. Click to choose what to hide.";
    }

    static @Nullable Integer parseBound(String typed, int limit) {
        try {
            return Math.clamp(Long.parseLong(typed.trim()), -limit, limit);
        } catch (NumberFormatException noNumber) {
            return null;
        }
    }

    public static float[] lastTrack() {
        return LAST_TRACK.clone();
    }

    public static Track getLastBar() {
        return lastBar;
    }

    private static final class Bounds {
        private boolean cutNear;
        private final int[] near;
        private boolean cutFar;
        private final int[] far;

        private Bounds(boolean cutNear, int[] near, boolean cutFar, int[] far) {
            this.cutNear = cutNear;
            this.near = near;
            this.cutFar = cutFar;
            this.far = far;
        }
    }

    private static final class BoundField {
        private final ImString typed = new ImString(24);
        private boolean editing;
    }

    private static void drawHeightBar(Rendering r, Camera camera, @Nullable Bounds3f scene) {
        var bounds = new Bounds(r.orthoCutTop, r.orthoTop, r.orthoCutBottom, r.orthoBottom);
        var heights = extent(2, scene, null);
        var away = CameraView.TOP.getDepthDirection();
        var track = (heights == null
                        ? Track.over(-CaptureView.HEIGHT_LIMIT, CaptureView.HEIGHT_LIMIT, away)
                        : Track.over(heights[0], heights[1], away))
                .holding(r.orthoCutTop, r.orthoTop[0])
                .holding(r.orthoCutBottom, r.orthoBottom[0]);
        drawBounds(
                "height",
                track,
                NO_TICKS,
                mark(track, Math.round(camera.getWorldFocusPosition().getZ())),
                bounds,
                "Cut Above Z",
                "Hide everything above a world height, for example the upper floors of a tower. Capture views call"
                        + " this height Top Z.",
                "Cut Below Z",
                "Hide everything below a world height, for example a lower floor that shows through holes. Capture"
                        + " views call this height Bottom Z.",
                CaptureView.HEIGHT_LIMIT);
        r.orthoCutTop = bounds.cutNear;
        r.orthoCutBottom = bounds.cutFar;
    }

    private static String heightHint(Track track) {
        return "The bar is the height of what is loaded, from Z "
                + number(track.nearEnd())
                + " at the left to Z "
                + number(track.farEnd())
                + " at the right. "
                + BAR_USE;
    }

    private static float mark(Track track, int coordinate) {
        var low = Math.min(track.nearEnd(), track.farEnd());
        var high = Math.max(track.nearEnd(), track.farEnd());
        return coordinate < low || coordinate > high ? Float.NaN : track.position(coordinate);
    }

    private static void drawSlice(OrthoSlice slice, Camera camera, @Nullable Bounds3f scene, LoadedTiles tiles) {
        var view = camera.getView();
        var depthAxis = view.getDepthAxis();
        var axis = AXES[depthAxis];
        var focus = camera.getWorldFocusPosition();
        var place = Math.round(depthAxis == 0 ? focus.getX() : focus.getY());
        var away = view.getDepthDirection();
        var loaded = tiles.getBounds();
        var extent = extent(
                depthAxis,
                scene,
                loaded == null ? null : new int[] {(int) loaded[depthAxis], (int) loaded[depthAxis + 2]});
        var track = (extent == null ? Track.around(place, away) : Track.over(extent[0], extent[1], away))
                .holding(slice.cutNear, slice.near[0])
                .holding(slice.cutFar, slice.far[0]);
        var bounds = new Bounds(slice.cutNear, slice.near, slice.cutFar, slice.far);
        drawBounds(
                axis,
                track,
                tileTicks(track),
                mark(track, place),
                bounds,
                "Cut In Front",
                "Hide everything between the eye and a world " + axis + ", for example the front wall of a house.",
                "Cut Behind",
                "Hide everything beyond a world " + axis + ", for example what stands behind a house.",
                OrthoSlice.LIMIT);
        slice.cutNear = bounds.cutNear;
        slice.cutFar = bounds.cutFar;
        GeodataWidgets.hint("The bar is the loaded world along "
                + axis
                + ", from "
                + number(track.nearEnd())
                + " on the eye's side to "
                + number(track.farEnd())
                + " on the far side, with lines at the tile borders and a triangle at the camera. "
                + BAR_USE);
    }

    private static void drawBounds(
            String id,
            Track track,
            float[] ticks,
            float mark,
            Bounds bounds,
            String nearLabel,
            String nearTip,
            String farLabel,
            String farTip,
            int limit) {
        var width = ImGui.getContentRegionAvailX();
        LAST_TRACK[0] = ImGui.getCursorScreenPosX();
        LAST_TRACK[1] = ImGui.getCursorScreenPosY();
        LAST_TRACK[2] = width;
        lastBar = track;
        float[] low = {bounds.cutNear ? track.position(bounds.near[0]) : 0f};
        float[] high = {bounds.cutFar ? track.position(bounds.far[0]) : 1f};
        var moved = HudWidgets.rangeSlider("##sliceTrack/" + id, low, high, width, ticks, mark);
        if (moved == HudWidgets.RANGE_LOW) {
            bounds.cutNear = low[0] > 0;
            bounds.near[0] = track.coordinate(low[0]);
        } else if (moved == HudWidgets.RANGE_HIGH) {
            bounds.cutFar = high[0] < 1;
            bounds.far[0] = track.coordinate(high[0]);
        }
        var left = ImGui.getCursorScreenPosX();
        if (width >= WIDE_ROW) {
            var top = ImGui.getCursorScreenPosY();
            boundLabel(nearLabel, nearTip);
            ImGui.sameLine();
            boundField(id + "/near", bounds, true, limit);
            ImGui.setCursorScreenPos(
                    left + width - ImGui.calcTextSizeX(farLabel) - GeodataWidgets.ROW - FIELD_WIDTH, top);
            boundField(id + "/far", bounds, false, limit);
            ImGui.sameLine();
            boundLabel(farLabel, farTip);
            ImGui.setCursorScreenPos(left + (width - SHOW_ALL_WIDTH) * .5f, top);
            showAll(id, bounds, SHOW_ALL_WIDTH, ImGui.getFontSize() + 6);
        } else {
            for (var near : new boolean[] {true, false}) {
                boundLabel(near ? nearLabel : farLabel, near ? nearTip : farTip);
                ImGui.sameLine();
                ImGui.setCursorScreenPos(left + width - FIELD_WIDTH, ImGui.getCursorScreenPosY());
                boundField(id + (near ? "/near" : "/far"), bounds, near, limit);
            }
            showAll(id, bounds, width, 26);
        }
        if (bounds.cutNear && bounds.cutFar) {
            OrthoSlice.keepOrder(bounds.near, bounds.far, track.away());
        }
    }

    private static void showAll(String id, Bounds bounds, float width, float height) {
        ImGui.beginDisabled(!bounds.cutNear && !bounds.cutFar);
        if (HudWidgets.button("sliceShowAll/" + id, null, "Show all", width, height, false, true)) {
            bounds.cutNear = false;
            bounds.cutFar = false;
        }
        ImGui.endDisabled();
    }

    private static void boundLabel(String label, String tip) {
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 8, 3);
        ImGui.alignTextToFramePadding();
        ImGui.textColored(HudWidgets.MUTED, label);
        GeodataWidgets.tip(tip);
        ImGui.popStyleVar();
    }

    private static void boundField(String id, Bounds bounds, boolean near, int limit) {
        var field = FIELDS.computeIfAbsent(id, ignored -> new BoundField());
        var value = near ? bounds.near : bounds.far;
        var cut = near ? bounds.cutNear : bounds.cutFar;
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 8, 3);
        if (!field.editing) {
            field.typed.set(cut ? Integer.toString(value[0]) : OFF);
        }
        ImGui.setNextItemWidth(FIELD_WIDTH);
        ImGui.inputText(
                "##bound/" + id, field.typed, ImGuiInputTextFlags.CharsDecimal | ImGuiInputTextFlags.AutoSelectAll);
        if (ImGui.isItemDeactivatedAfterEdit()) {
            var typed = parseBound(field.typed.get(), limit);
            if (typed != null) {
                value[0] = typed;
            }
            if (near) {
                bounds.cutNear = typed != null;
            } else {
                bounds.cutFar = typed != null;
            }
        }
        field.editing = ImGui.isItemActive();
        GeodataWidgets.tip("Type a world coordinate. An empty field cuts nothing.");
        ImGui.popStyleVar();
    }

    private static void drawNumber(String label, ImInt value, int step) {
        ImGui.spacing();
        ImGui.textColored(HudWidgets.MUTED, label);
        ImGui.setNextItemWidth(-1);
        ImGui.inputInt("##" + label, value, step);
    }

    private static void centerCircleOnCamera(Rendering r, Camera camera) {
        var focus = camera.getWorldFocusPosition();
        r.orthoCircleX.set(Math.round(focus.getX()));
        r.orthoCircleY.set(Math.round(focus.getY()));
    }

    private static String tileName(Camera camera) {
        var position = camera.getPosition();
        return (Math.floorDiv(Math.round(position.getX()), WorldDimensions.TILE_SIZE)
                        + WorldDimensions.TILE_ZERO_COORD_X)
                + "_"
                + (Math.floorDiv(Math.round(position.getY()), WorldDimensions.TILE_SIZE)
                        + WorldDimensions.TILE_ZERO_COORD_Y);
    }

    private static String number(int value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
