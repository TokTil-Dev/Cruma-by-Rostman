package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.world.CameraPath;
import com.rost.cruma.core.world.CameraPath.Pose;
import com.rost.cruma.core.world.GameScenes.Action;
import com.rost.cruma.core.world.GameScenes.Actor;
import com.rost.cruma.core.world.MapScenes.Vector;
import com.rost.cruma.core.world.SceneDocument;
import com.rost.cruma.core.world.SceneItems;
import com.rost.cruma.core.world.SceneItems.Key;
import com.rost.cruma.core.world.SceneOrders;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.viewer.editing.EditorTransforms;
import com.rost.cruma.viewer.editing.WorldGizmo;
import com.rost.cruma.viewer.geodata.GeoOverlayLines;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.LoadedTiles;
import com.rost.cruma.viewer.render.ScreenLens;
import glm_.vec3.Vec3;
import imgui.ImGui;
import imgui.extension.imguizmo.ImGuizmo;
import imgui.extension.imguizmo.flag.Mode;
import imgui.extension.imguizmo.flag.Operation;
import imgui.flag.ImGuiHoveredFlags;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntToDoubleFunction;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

final class DirectorWorld {
    private static final float LINE_WIDTH = 2;
    private static final float REAL_LINE_WIDTH = 1.5f;
    private static final float KEY_DIAMETER = 9;
    private static final float CHOSEN_DIAMETER = 12;
    private static final float SHOT_DIAMETER = 7;
    private static final double OUTLINE_POINTS = 600;
    private static final double LEAST_OUTLINE_STEP = 32;
    private static final double FRAME_DISTANCE = 300;
    private static final float FRAME_WIDTH = 1.5f;
    private static final double KEY_CAMERA_DISTANCE = 24;
    private static final double CHOSEN_CAMERA_DISTANCE = 32;
    private static final float KEY_CAMERA_WIDTH = 1.5f;
    private static final float CHOSEN_CAMERA_WIDTH = 2;
    private static final float PICK_REACH = 10;
    private static final float CLICK_PIXELS = 2;
    private static final float LEAST_TURN = 1e-5f;
    private static final double NEAREST_HANDLE = 30;
    private static final double FLOOR_REACH = 60_000;
    private static final double FAR_FLOOR_REACH = 400_000;
    private static final double FLOOR_ASK_STEP = 8_192;
    private static final float GHOST_HALF_HEIGHT = 24;
    private static final double GHOST_RADIUS_SHARE = .6;
    private static final double SHIP_GHOST_RADIUS = 300;
    private static final double EFFECT_GHOST_RADIUS = 40;
    private static final int GHOST_SIDES = 16;
    private static final float GHOST_WIDTH = 1.5f;

    private final WorldGizmo gizmo = new WorldGizmo();
    private final DirectorGrab grab = new DirectorGrab();
    private final float[] matrix = new float[16];
    private final float[] start = new float[16];
    private DoubleSupplier frameShape = () -> 16.0 / 9;
    private Supplier<ScreenLens> lens = () -> ScreenLens.WORLD;
    private IntToDoubleFunction heights = _ -> 0;
    private @Nullable LoadedTiles tiles;
    private @Nullable CameraPath outlined;
    private List<List<Vector>> outline = List.of();
    private @Nullable BakedScene lined;
    private List<List<Vector>> lines = List.of();
    private @Nullable SceneDocument endsOf;
    private @Nullable BakedScene endsBaked;
    private List<Pose> ends = List.of();
    private boolean dragging;
    private boolean cancelled;
    private boolean moved;
    private boolean turns;
    private double startYaw;
    private double startPitch;
    private double startRoll;
    private int picking = -1;
    private float pickX;
    private float pickY;
    private boolean pickMoved;
    private boolean pickAdd;
    private int draggedKey = -1;
    private Key draggedShot;
    private @Nullable Key draggedOrder;
    private @Nullable Vec3 pressedFrom;
    private @Nullable Vec3 pressedWay;
    private double handleX = Double.NaN;

    static int nearest(float[] xs, float[] ys, float x, float y, float reach) {
        var best = -1;
        var nearest = reach * reach;
        for (var index = 0; index < xs.length; index++) {
            var dx = xs[index] - x;
            var dy = ys[index] - y;
            var distance = dx * dx + dy * dy;
            if (distance <= nearest) {
                nearest = distance;
                best = index;
            }
        }
        return best;
    }

    static float[] keyMatrix(Pose pose) {
        var axes = axes(pose);
        var result = EditorTransforms.identity();
        for (var column = 0; column < 3; column++) {
            result[column * 4] = (float) axes[column].x();
            result[column * 4 + 1] = (float) axes[column].y();
            result[column * 4 + 2] = (float) axes[column].z();
        }
        result[12] = (float) pose.x();
        result[13] = (float) pose.y();
        result[14] = (float) pose.z();
        return result;
    }

    static double[] angles(float[] matrix, double yaw, double roll) {
        var look = unit(new Vector(matrix[0], matrix[1], matrix[2]));
        var top = new Vector(matrix[8], matrix[9], matrix[10]);
        var level = Math.hypot(look.x(), look.y());
        var tilt = Math.clamp(
                Math.toDegrees(Math.asin(Math.clamp(look.z(), -1, 1))),
                -Camera.MAX_PITCH_DEGREES,
                Camera.MAX_PITCH_DEGREES);
        if (level < 1e-6) {
            return new double[] {yaw, tilt, roll};
        }
        var turn = Math.toDegrees(Math.atan2(look.y(), look.x()));
        var axes = axes(new Pose(0, 0, 0, turn, tilt, 0, 1));
        var tip = Math.toDegrees(Math.atan2(dot(top, axes[1]), dot(top, axes[2])));
        return new double[] {near(turn, yaw), tilt, near(tip, roll)};
    }

    static Vector[] frame(Pose pose, double shape, ScreenLens lens) {
        return frame(pose, shape, FRAME_DISTANCE, lens);
    }

    static Vector[] frame(Pose pose, double shape, double distance, ScreenLens lens) {
        var eye = new Vector(pose.x(), pose.y(), pose.z());
        var axes = axes(pose);
        var look = axes[0];
        var side = axes[1];
        var top = axes[2];
        var middle = eye.plus(look.times(distance));
        var across = side.times(distance * lens.computeHalfWidth(pose.fov(), shape));
        var high = top.times(distance * lens.computeHalfHeight(pose.fov(), shape));
        return new Vector[] {
            middle.minus(across).plus(high),
            middle.plus(across).plus(high),
            middle.plus(across).minus(high),
            middle.minus(across).minus(high)
        };
    }

    void setFrameShape(DoubleSupplier shape) {
        frameShape = shape;
    }

    void setLens(Supplier<ScreenLens> lens) {
        this.lens = lens;
    }

    void setHeights(IntToDoubleFunction lookup) {
        heights = lookup;
        grab.setHeights(lookup);
    }

    void setTiles(LoadedTiles loaded) {
        tiles = loaded;
        grab.setTiles(loaded);
    }

    void onChosen(IntConsumer chosen) {
        grab.onChosen(chosen);
    }

    boolean isDragging() {
        return grab.isDragging() || dragging && moved && !cancelled && draggedOrder != null;
    }

    boolean isHolding() {
        return grab.isHolding() || dragging;
    }

    DirectorGrab getGrab() {
        return grab;
    }

    boolean isPointing() {
        return grab.isPointing() || dragging || grab.isHolding();
    }

    boolean cancelDrag(DirectorSession open) {
        if (grab.cancel(open)) {
            return true;
        }
        if (dragging && !cancelled && draggedOrder != null) {
            open.cancelDrag();
            cancelled = true;
            return true;
        }
        return false;
    }

    void drawMarks(DirectorSession open, GeoOverlayLines marks, float viewportPoints) {
        var path = open.getPath();
        var line = open.getLine();
        var scene = open.isOnPath() ? null : open.getScenePose();
        var ghost = ghost(open);
        var point = open.getPlacing() == null ? open.getPointed() : null;
        if (path != null) {
            drawPath(open, marks, path);
        }
        if (line != null) {
            drawLine(marks, line);
        }
        for (var end : line == null ? List.<Pose>of() : ends(open)) {
            marks.handle((float) end.x(), (float) end.y(), (float) end.z(), HudWidgets.ACCENT, SHOT_DIAMETER);
        }
        var shot = open.getChosenShot();
        var chosenEnd = shot == null ? null : open.getShotEnd(shot);
        if (chosenEnd != null) {
            marks.handle(
                    (float) chosenEnd.x(),
                    (float) chosenEnd.y(),
                    (float) chosenEnd.z(),
                    HudWidgets.TEXT,
                    CHOSEN_DIAMETER);
        }
        if (scene != null) {
            drawFrame(marks, scene, frameShape.getAsDouble(), lens.get());
        }
        if (ghost != null) {
            drawGhost(open, marks, ghost);
        }
        if (point != null) {
            drawPoint(marks, point.x(), point.y(), point.z());
        }
        grab.drawMarks(open, marks);
        marks.publish(viewportPoints);
    }

    boolean drawHandle(DirectorSession open, Camera camera, float left, float top, float right, float bottom) {
        if (open.getPlacing() != null && !dragging && !grab.isHolding()) {
            handleX = Double.NaN;
            picking = -1;
            ImGuizmo.enable(false);
            return place(open, camera, left, top, right, bottom);
        }
        pressedFrom = null;
        pressedWay = null;
        if (grab.isHolding()) {
            ImGuizmo.enable(false);
            return grab.update(open, camera, left, top, right, bottom, true);
        }
        var captured = handle(open, camera, left, top, right, bottom);
        var picked = pick(open, camera, left, top, right, bottom, captured);
        var grabbed = grab.update(open, camera, left, top, right, bottom, captured || picked || dragging);
        if (Double.isNaN(handleX)) {
            handleX = grab.getDotX();
        }
        return picked || captured || grabbed;
    }

    private boolean handle(DirectorSession open, Camera camera, float left, float top, float right, float bottom) {
        var at = dragging ? null : handlePlace(open, camera);
        if (!dragging && at == null) {
            handleX = Double.NaN;
            ImGuizmo.enable(false);
            return false;
        }
        if (!dragging) {
            moved = false;
            draggedKey = open.getSelectedKey();
            draggedShot = open.getChosenShot();
            draggedOrder = open.getChosenOrder();
            turns = draggedOrder == null
                    && draggedKey >= 0
                    && !open.getKeys().get(draggedKey).rides();
            if (turns) {
                var key = open.getKeys().get(draggedKey);
                startYaw = key.yaw();
                startPitch = key.pitch();
                startRoll = key.roll();
                System.arraycopy(
                        keyMatrix(new Pose(at.x(), at.y(), at.z(), startYaw, startPitch, startRoll, key.fov())),
                        0,
                        matrix,
                        0,
                        16);
            } else {
                System.arraycopy(EditorTransforms.identity(), 0, matrix, 0, 16);
                matrix[12] = (float) at.x();
                matrix[13] = (float) at.y();
                matrix[14] = (float) at.z();
            }
            System.arraycopy(matrix, 0, start, 0, 16);
            handleX = at.x();
        }
        var active = dragging || WorldGizmo.isPointerInside(left, top, right, bottom) && !ImGui.isAnyItemActive();
        gizmo.manipulate(
                camera,
                left,
                top,
                right,
                bottom,
                active,
                draggedOrder != null
                        ? Operation.TRANSLATE_Z
                        : turns ? Operation.TRANSLATE | Operation.ROTATE : Operation.TRANSLATE,
                Mode.WORLD,
                matrix,
                null);
        var captured = ImGuizmo.isOver() || ImGuizmo.isUsing();
        if (ImGuizmo.isUsing() && cancelled) {
            dragging = true;
        } else if (ImGuizmo.isUsing()) {
            dragging = true;
            var turned = turns && isTurned(matrix, start);
            moved |= matrix[12] != start[12] || matrix[13] != start[13] || matrix[14] != start[14] || turned;
            if (moved && draggedOrder != null) {
                if (SceneItems.holds(open.getDocument().items(), draggedOrder)) {
                    open.moveOrder(draggedOrder, matrix[12], matrix[13], matrix[14]);
                }
            } else if (moved && turns) {
                var angles =
                        turned ? angles(matrix, startYaw, startRoll) : new double[] {startYaw, startPitch, startRoll};
                open.poseKey(draggedKey, matrix[12], matrix[13], matrix[14], angles[0], angles[1], angles[2]);
            } else if (moved && draggedKey >= 0) {
                open.placeKey(draggedKey, matrix[12], matrix[13], matrix[14]);
            } else if (moved && draggedShot != null) {
                open.moveShotEnd(draggedShot, matrix[12] - start[12], matrix[13] - start[13], matrix[14] - start[14]);
            }
        } else if (dragging) {
            dragging = false;
            if (!cancelled) {
                open.settle();
            }
            cancelled = false;
        }
        return captured;
    }

    private boolean pick(
            DirectorSession open, Camera camera, float left, float top, float right, float bottom, boolean captured) {
        if (captured || dragging) {
            picking = -1;
            return false;
        }
        var x = ImGui.getMousePosX();
        var y = ImGui.getMousePosY();
        if (picking >= 0) {
            pickMoved |= Math.hypot(x - pickX, y - pickY) >= CLICK_PIXELS;
            if (ImGui.isMouseDown(0)) {
                return true;
            }
            var index = picking;
            picking = -1;
            if (!pickMoved && WorldGizmo.isPointerInside(left, top, right, bottom)) {
                if (!pickAdd) {
                    open.clearSelection();
                }
                open.select(index, pickAdd);
            }
            return true;
        }
        if (!ImGui.isMouseClicked(0)
                || !WorldGizmo.isPointerInside(left, top, right, bottom)
                || ImGui.isWindowHovered(ImGuiHoveredFlags.AnyWindow)
                || ImGui.isAnyItemActive()) {
            return false;
        }
        var index = keyAt(open, camera, x, y);
        if (index < 0) {
            return false;
        }
        picking = index;
        pickX = x;
        pickY = y;
        pickMoved = false;
        pickAdd = ImGui.getIO().getKeyShift();
        return true;
    }

    private boolean place(DirectorSession open, Camera camera, float left, float top, float right, float bottom) {
        var held = open.getPlacing().held();
        var io = ImGui.getIO();
        if (!held && (!WorldGizmo.isPointerInside(left, top, right, bottom) || io.getWantCaptureMouse())) {
            open.pointAway();
            return false;
        }
        var ndcX = ImGui.getMousePosX() / io.getDisplaySizeX() * 2f - 1f;
        var ndcY = 1f - ImGui.getMousePosY() / io.getDisplaySizeY() * 2f;
        var from = camera.getCursorRayOrigin(ndcX, ndcY);
        var way = camera.getCursorRayDirection(ndcX, ndcY);
        if (held) {
            if (ImGui.isMouseDown(0)) {
                turn(open, from, way);
            } else {
                open.letGo();
            }
            return true;
        }
        var reach = camera.isOrtho() ? FAR_FLOOR_REACH : FLOOR_REACH;
        askFloors(open, from, way, reach);
        var floors = open.getFloors();
        var pressed = ImGui.isMouseClicked(0);
        if (pressedFrom != null && pressedWay != null) {
            if (open.isReadingFloors()) {
                open.pointAt(null);
                return true;
            }
            from = pressedFrom;
            way = pressedWay;
            pressed = true;
            pressedFrom = null;
            pressedWay = null;
        }
        var hit = floors == null
                ? null
                : floors.findRayHit(from.getX(), from.getY(), from.getZ(), way.getX(), way.getY(), way.getZ(), reach);
        open.pointAt(hit);
        if (pressed && hit != null) {
            var eye = camera.getPosition();
            open.press(hit, SceneOrders.yaw(eye.getX() - hit.x(), eye.getY() - hit.y()));
        } else if (pressed && open.isReadingFloors()) {
            pressedFrom = from;
            pressedWay = way;
        }
        return true;
    }

    private void askFloors(DirectorSession open, Vec3 from, Vec3 way, double reach) {
        var loaded = tiles;
        if (loaded == null) {
            return;
        }
        var map = open.getDocument().map();
        if (!map.isEmpty()) {
            open.readFloors(map);
            return;
        }
        for (var along = 0.0; along <= reach; along += FLOOR_ASK_STEP) {
            var tile = WorldTile.at(
                    (float) (from.getX() + way.getX() * along), (float) (from.getY() + way.getY() * along));
            var name = tile.x() + "_" + tile.y();
            if (loaded.has(name)) {
                open.readFloors(name);
            }
        }
    }

    private @Nullable Actor ghost(DirectorSession open) {
        var placing = open.getPlacing();
        if (placing != null) {
            return placing.shown() ? placing.order() : null;
        }
        var held = grab.isDragging() ? grab.getHeldKey() : dragging && moved && !cancelled ? draggedOrder : null;
        if (held == null || !SceneItems.holds(open.getDocument().items(), held)) {
            return null;
        }
        var order = open.order(held);
        var nudge = open.getNudge();
        var follows = nudge != null && nudge.index() == order.index();
        return follows ? null : order;
    }

    private void drawGhost(DirectorSession open, GeoOverlayLines marks, Actor order) {
        var entry = SceneOrders.entry(open.getDocument().items(), order.index());
        var flies = order.fly()
                || entry != null && entry.fly()
                || order.action() == Action.AIRSHIP_SPAWN
                || order.action() == Action.AIRSHIP_MOVE;
        var floors = open.getFloors();
        var floor = flies || floors == null ? Double.NaN : floors.getSurfaceHeight(order.x(), order.y(), order.z());
        var feet = Double.isNaN(floor) ? order.z() : floor;
        var placing = open.getPlacing();
        if (placing != null && placing.point()) {
            drawPoint(marks, order.x(), order.y(), feet);
            return;
        }
        if (order.action() == Action.EFFECT) {
            ring(marks, order.x(), order.y(), feet, EFFECT_GHOST_RADIUS);
            return;
        }
        var npc = entry == null || order.action().enters() ? order.npc() : entry.npc();
        var height = heights.applyAsDouble(npc);
        var half = height > 0 ? height : GHOST_HALF_HEIGHT;
        var ship = order.action() == Action.AIRSHIP_SPAWN || order.action() == Action.AIRSHIP_MOVE;
        var radius = ship ? SHIP_GHOST_RADIUS : half * GHOST_RADIUS_SHARE;
        ring(marks, order.x(), order.y(), feet, radius);
        ring(marks, order.x(), order.y(), feet + 2 * half, radius);
        for (var side = 0; side < 4; side++) {
            var turn = side * Math.PI / 2;
            var x = order.x() + radius * Math.cos(turn);
            var y = order.y() + radius * Math.sin(turn);
            marks.addLine(
                    new GeoPoint(x, y, feet), new GeoPoint(x, y, feet + 2 * half), 0, HudWidgets.ACCENT, GHOST_WIDTH);
        }
        if (order.action().enters()) {
            var way = order.yaw() * 2 * Math.PI / 0x10000;
            var middle = feet + half;
            marks.addLine(
                    new GeoPoint(order.x(), order.y(), middle),
                    new GeoPoint(
                            order.x() + 2 * radius * Math.cos(way), order.y() + 2 * radius * Math.sin(way), middle),
                    0,
                    HudWidgets.ACCENT,
                    GHOST_WIDTH);
        }
    }

    private @Nullable Vector handlePlace(DirectorSession open, Camera camera) {
        Vector place = null;
        var order = open.getChosenOrder();
        if (order != null && SceneOrders.hasPlace(open.order(order))) {
            var chosen = open.order(order);
            if (!DirectorGrab.flies(SceneOrders.entry(open.getDocument().items(), chosen.index()), chosen)) {
                return null;
            }
            place = new Vector(chosen.x(), chosen.y(), chosen.z());
        } else if (open.isOnPath()) {
            return null;
        }
        var index = open.getSelectedKey();
        if (index >= 0) {
            var key = open.getKeys().get(index);
            place = new Vector(key.x(), key.y(), key.z());
        } else if (place == null) {
            var shot = open.getChosenShot();
            var end = shot == null ? null : open.getShotEnd(shot);
            if (end != null) {
                place = new Vector(end.x(), end.y(), end.z());
            }
        }
        if (place == null) {
            return null;
        }
        var eye = camera.getPosition();
        var near = place.distance(new Vector(eye.getX(), eye.getY(), eye.getZ())) < NEAREST_HANDLE;
        return near ? null : place;
    }

    private List<Pose> ends(DirectorSession open) {
        if (open.getDocument() != endsOf || open.getBaked() != endsBaked) {
            endsOf = open.getDocument();
            endsBaked = open.getBaked();
            ends = open.getShotEnds();
        }
        return ends;
    }

    private void drawPath(DirectorSession open, GeoOverlayLines marks, CameraPath path) {
        if (path != outlined) {
            outlined = path;
            outline = path.getOutline(Math.max(LEAST_OUTLINE_STEP, path.length() / OUTLINE_POINTS));
        }
        for (var line : outline) {
            for (var index = 1; index < line.size(); index++) {
                marks.addLine(point(line.get(index - 1)), point(line.get(index)), 0, HudWidgets.ACCENT, LINE_WIDTH);
            }
        }
        var keys = path.getKeys();
        var shape = frameShape.getAsDouble();
        var keyLens = lens.get();
        var standing = open.getStandsIn();
        for (var index = 0; index < keys.size(); index++) {
            var key = keys.get(index);
            var chosen = open.getSelected().contains(index);
            if (index != standing && open.isChoosable(index)) {
                drawCamera(
                        marks,
                        new Pose(key.x(), key.y(), key.z(), key.yaw(), key.pitch(), key.roll(), key.fov()),
                        shape,
                        keyLens,
                        chosen ? CHOSEN_CAMERA_DISTANCE : KEY_CAMERA_DISTANCE,
                        chosen ? HudWidgets.TEXT : HudWidgets.ACCENT,
                        chosen ? CHOSEN_CAMERA_WIDTH : KEY_CAMERA_WIDTH);
            }
            marks.handle(
                    (float) key.x(),
                    (float) key.y(),
                    (float) key.z(),
                    chosen ? HudWidgets.TEXT : HudWidgets.ACCENT,
                    chosen ? CHOSEN_DIAMETER : KEY_DIAMETER);
        }
    }

    private void drawLine(GeoOverlayLines marks, BakedScene line) {
        if (line != lined) {
            lined = line;
            lines = line.getLines(LEAST_OUTLINE_STEP);
        }
        for (var part : lines) {
            for (var index = 1; index < part.size(); index++) {
                marks.addLine(
                        point(part.get(index - 1)), point(part.get(index)), 0, GeodataWidgets.MUTED, REAL_LINE_WIDTH);
            }
        }
    }

    private static int keyAt(DirectorSession open, Camera camera, float x, float y) {
        var keys = open.getKeys();
        var io = ImGui.getIO();
        var standing = open.getStandsIn();
        var xs = new float[keys.size()];
        var ys = new float[keys.size()];
        for (var index = 0; index < keys.size(); index++) {
            xs[index] = Float.NaN;
            ys[index] = Float.NaN;
            var key = keys.get(index);
            if (index == standing || !open.isChoosable(index)) {
                continue;
            }
            var screen = camera.projectToScreenRaw(
                    new Vec3((float) key.x(), (float) key.y(), (float) key.z()), (int) io.getDisplaySizeX(), (int)
                            io.getDisplaySizeY());
            if (screen != null) {
                xs[index] = screen.getX();
                ys[index] = screen.getY();
            }
        }
        return nearest(xs, ys, x, y, PICK_REACH);
    }

    private static boolean isTurned(float[] matrix, float[] start) {
        for (var column = 0; column < 3; column++) {
            for (var row = 0; row < 3; row++) {
                if (Math.abs(matrix[column * 4 + row] - start[column * 4 + row]) > LEAST_TURN) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double near(double angle, double was) {
        return was + Math.IEEEremainder(angle - was, 360);
    }

    private static void turn(DirectorSession open, Vec3 from, Vec3 way) {
        var placing = open.getPlacing();
        var along = (placing.order().z() - from.getZ()) / way.getZ();
        if (way.getZ() != 0 && along > 0) {
            open.turnTo(from.getX() + way.getX() * along, from.getY() + way.getY() * along);
        }
    }

    private static void drawPoint(GeoOverlayLines marks, double x, double y, double z) {
        ring(marks, x, y, z, EFFECT_GHOST_RADIUS);
        for (var side = 0; side < 2; side++) {
            var dx = side == 0 ? EFFECT_GHOST_RADIUS : 0;
            var dy = side == 0 ? 0 : EFFECT_GHOST_RADIUS;
            marks.addLine(
                    new GeoPoint(x - dx, y - dy, z),
                    new GeoPoint(x + dx, y + dy, z),
                    0,
                    HudWidgets.ACCENT,
                    GHOST_WIDTH);
        }
    }

    private static void ring(GeoOverlayLines marks, double x, double y, double z, double radius) {
        for (var side = 0; side < GHOST_SIDES; side++) {
            var from = side * 2 * Math.PI / GHOST_SIDES;
            var to = (side + 1) * 2 * Math.PI / GHOST_SIDES;
            marks.addLine(
                    new GeoPoint(x + radius * Math.cos(from), y + radius * Math.sin(from), z),
                    new GeoPoint(x + radius * Math.cos(to), y + radius * Math.sin(to), z),
                    0,
                    HudWidgets.ACCENT,
                    GHOST_WIDTH);
        }
    }

    private static void drawFrame(GeoOverlayLines marks, Pose pose, double shape, ScreenLens lens) {
        drawCamera(marks, pose, shape, lens, FRAME_DISTANCE, HudWidgets.ACCENT, FRAME_WIDTH);
    }

    private static void drawCamera(
            GeoOverlayLines marks, Pose pose, double shape, ScreenLens lens, double distance, int color, float width) {
        var eye = new Vector(pose.x(), pose.y(), pose.z());
        var corners = frame(pose, shape, distance, lens);
        for (var index = 0; index < corners.length; index++) {
            marks.addLine(point(corners[index]), point(corners[(index + 1) % corners.length]), 0, color, width);
            marks.addLine(point(eye), point(corners[index]), 0, color, width);
        }
    }

    private static Vector[] axes(Pose pose) {
        var w = Math.toRadians(pose.yaw());
        var p = Math.toRadians(pose.pitch());
        var look = new Vector(Math.cos(p) * Math.cos(w), Math.cos(p) * Math.sin(w), Math.sin(p));
        var level = new Vector(-look.x() * look.z(), -look.y() * look.z(), 1 - look.z() * look.z());
        var length = Math.sqrt(level.x() * level.x() + level.y() * level.y() + level.z() * level.z());
        var up = length < 1e-6 ? new Vector(1, 0, 0) : level.times(1 / length);
        var right = new Vector(
                up.y() * look.z() - up.z() * look.y(),
                up.z() * look.x() - up.x() * look.z(),
                up.x() * look.y() - up.y() * look.x());
        var roll = Math.toRadians(pose.roll());
        var top = up.times(Math.cos(roll)).plus(right.times(Math.sin(roll)));
        var side = right.times(Math.cos(roll)).minus(up.times(Math.sin(roll)));
        return new Vector[] {look, side, top};
    }

    private static double dot(Vector a, Vector b) {
        return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
    }

    private static Vector unit(Vector value) {
        var length = Math.sqrt(value.x() * value.x() + value.y() * value.y() + value.z() * value.z());
        return length < 1e-9 ? value : value.times(1 / length);
    }

    private static GeoPoint point(Vector place) {
        return new GeoPoint(place.x(), place.y(), place.z());
    }
}
