package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.world.GameScenes.Action;
import com.rost.cruma.core.world.GameScenes.Actor;
import com.rost.cruma.core.world.GameScenes.Item;
import com.rost.cruma.core.world.SceneItems;
import com.rost.cruma.core.world.SceneItems.Key;
import com.rost.cruma.core.world.SceneOrders;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.viewer.editing.WorldGizmo;
import com.rost.cruma.viewer.geodata.GeoOverlayLines;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.LoadedTiles;
import glm_.vec3.Vec3;
import imgui.ImColor;
import imgui.ImGui;
import imgui.flag.ImGuiHoveredFlags;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntToDoubleFunction;
import org.jetbrains.annotations.Nullable;

final class DirectorGrab {
    private static final float CLICK_PIXELS = 3;
    private static final float DOT_REACH = 10;
    private static final float DOT = 9;
    private static final float HOT_DOT = 12;
    private static final float TURN_DOT = 8;
    private static final float HOT_TURN_DOT = 11;
    private static final float HELPER_DOT = 7;
    private static final float DOT_LIFT = 4;
    private static final float LEAST_HALF_WIDTH = 6;
    private static final double TURN_MARGIN = 22;
    private static final double TURN_NEAREST = 30;
    private static final double TURN_FARTHEST = 70;
    private static final float TURN_LINE_WIDTH = 1.5f;
    private static final double HALF_HEIGHT = 24;
    private static final double RADIUS_SHARE = .6;
    private static final double SHIP_RADIUS = 300;
    private static final double SHIP_HALF_HEIGHT = 120;
    private static final double RING_MARGIN = 8;
    private static final int RING_SIDES = 24;
    private static final float CHOSEN_RING = 3;
    private static final float POINTED_RING = 2;
    private static final int WHITE = ImColor.rgba(255, 255, 255, 225);
    private static final int HOT_WHITE = ImColor.rgba(255, 255, 255, 255);
    private static final int POINTED = ImColor.rgba(255, 255, 255, 170);
    private static final int TURN_COLOR = ImColor.rgba(92, 219, 241, 190);
    private static final int TURN_LINE = ImColor.rgba(92, 219, 241, 150);
    private static final int HELPER_COLOR = ImColor.rgba(255, 255, 255, 120);
    private static final double TURN = 0x10000;
    private static final double PLANE_REACH = 400_000;

    private IntToDoubleFunction heights = _ -> 0;
    private IntConsumer onChosen = _ -> {};
    private @Nullable LoadedTiles tiles;
    private int pointed = -1;
    private @Nullable Grip pointedGrip;
    private @Nullable Grip held;
    private @Nullable Key heldKey;
    private @Nullable Actor heldStart;
    private float pressX;
    private float pressY;
    private double planeZ;
    private double pressWorldX = Double.NaN;
    private double pressWorldY = Double.NaN;
    private double floorStart = Double.NaN;
    private double floorLast = Double.NaN;
    private boolean heldFlies;
    private boolean moved;
    private boolean cancelled;
    private double dotX = Double.NaN;

    enum Grip {
        BODY,
        DOT,
        TURN
    }

    record Figure(int index, double x, double y, double feet, double top, double radius, boolean seen) {}

    interface Screen {
        float @Nullable [] at(double x, double y, double z);
    }

    static int bodyAt(List<Figure> figures, Screen screen, double eyeX, double eyeY, double eyeZ, float x, float y) {
        var best = -1;
        var nearest = Double.MAX_VALUE;
        for (var figure : figures) {
            var foot = screen.at(figure.x(), figure.y(), figure.feet());
            if (foot == null) {
                continue;
            }
            boolean over;
            if (figure.seen()) {
                var head = screen.at(figure.x(), figure.y(), figure.top());
                var middle = (figure.feet() + figure.top()) / 2;
                var side = sidePixels(screen, figure, middle, eyeX, eyeY);
                over = head != null && !Double.isNaN(overBody(foot[0], foot[1], head[0], head[1], side, x, y));
            } else {
                over = Math.hypot(foot[0] - x, foot[1] - y) <= DOT_REACH;
            }
            var distance = Math.sqrt(square(figure.x() - eyeX)
                    + square(figure.y() - eyeY)
                    + square((figure.feet() + figure.top()) / 2 - eyeZ));
            if (over && distance < nearest) {
                nearest = distance;
                best = figure.index();
            }
        }
        return best;
    }

    static double overBody(float footX, float footY, float topX, float topY, double halfWidth, float x, float y) {
        var length = Math.hypot(topX - footX, topY - footY);
        var t = length < 1e-3
                ? 0
                : Math.clamp(((x - footX) * (topX - footX) + (y - footY) * (topY - footY)) / (length * length), 0, 1);
        var nearX = footX + (topX - footX) * t;
        var nearY = footY + (topY - footY) * t;
        var distance = Math.hypot(x - nearX, y - nearY);
        return distance <= Math.max(LEAST_HALF_WIDTH, halfWidth) ? distance : Double.NaN;
    }

    static double @Nullable [] onPlane(
            double ox, double oy, double oz, double dx, double dy, double dz, double height) {
        if (Math.abs(dz) < 1e-6) {
            return null;
        }
        var along = (height - oz) / dz;
        if (along <= 0 || along * Math.sqrt(dx * dx + dy * dy + dz * dz) > PLANE_REACH) {
            return null;
        }
        return new double[] {ox + dx * along, oy + dy * along};
    }

    static double grabHeight(
            double ox,
            double oy,
            double oz,
            double dx,
            double dy,
            double dz,
            double x,
            double y,
            double feet,
            double top) {
        var level = dx * dx + dy * dy;
        if (level < 1e-9) {
            return feet;
        }
        var along = ((x - ox) * dx + (y - oy) * dy) / level;
        return Math.clamp(oz + dz * along, feet, top);
    }

    static Actor moved(Actor start, double dx, double dy, double floorFrom, double floorTo, boolean flies) {
        var rises = flies || Double.isNaN(floorFrom) || Double.isNaN(floorTo) ? 0 : floorTo - floorFrom;
        return SceneOrders.at(start, start.x() + dx, start.y() + dy, start.z() + rises);
    }

    static int turned(int yaw, double from, double to) {
        var turn = (int) Math.round(Math.IEEEremainder(to - from, 2 * Math.PI) * TURN / (2 * Math.PI));
        var next = yaw + turn;
        return yaw >= 0 && yaw < TURN ? Math.floorMod(next, (int) TURN) : next;
    }

    static boolean turns(Actor order) {
        return order.action().enters();
    }

    static boolean flies(@Nullable Actor entry, Actor order) {
        return order.fly()
                || entry != null && entry.fly()
                || order.action() == Action.AIRSHIP_SPAWN
                || order.action() == Action.AIRSHIP_MOVE;
    }

    static boolean isStillHolding(List<Item> items, Key key, Actor start) {
        if (!SceneItems.holds(items, key)) {
            return false;
        }
        var order = items.get(key.item()).actors().get(key.order());
        return order.index() == start.index() && order.action() == start.action();
    }

    void setHeights(IntToDoubleFunction lookup) {
        heights = lookup;
    }

    void onChosen(IntConsumer chosen) {
        onChosen = chosen;
    }

    void setTiles(LoadedTiles loaded) {
        tiles = loaded;
    }

    boolean isHolding() {
        return held != null;
    }

    boolean isDragging() {
        return held != null && moved && !cancelled;
    }

    @Nullable
    Key getHeldKey() {
        return held == null ? null : heldKey;
    }

    boolean isPointing() {
        return pointed >= 0 || pointedGrip != null;
    }

    double getDotX() {
        return dotX;
    }

    boolean cancel(DirectorSession open) {
        if (held == null) {
            return false;
        }
        if (moved && !cancelled) {
            open.cancelDrag();
        }
        cancelled = true;
        return true;
    }

    boolean update(
            DirectorSession open, Camera camera, float left, float top, float right, float bottom, boolean blocked) {
        if (held != null) {
            follow(open, camera);
            return true;
        }
        pointed = -1;
        pointedGrip = null;
        dotX = Double.NaN;
        var document = open.getDocument();
        if (document == null) {
            return false;
        }
        var screen = screen(camera);
        var chosen = open.getChosenOrder();
        var order = chosen == null ? null : open.order(chosen);
        var dot = order != null && SceneOrders.hasPlace(order) ? order : null;
        if (dot != null) {
            dotX = dot.x();
        }
        var io = ImGui.getIO();
        if (blocked
                || open.getPlacing() != null
                || !WorldGizmo.isPointerInside(left, top, right, bottom)
                || ImGui.isWindowHovered(ImGuiHoveredFlags.AnyWindow)
                || ImGui.isAnyItemActive()
                || io.getWantCaptureMouse()) {
            return false;
        }
        var x = ImGui.getMousePosX();
        var y = ImGui.getMousePosY();
        if (dot != null) {
            pointedGrip = gripAt(screen, dot, turnReach(open, dot), x, y);
        }
        var figures = collectFigures(open);
        if (pointedGrip == null) {
            var eye = camera.getPosition();
            pointed = bodyAt(figures, screen, eye.getX(), eye.getY(), eye.getZ(), x, y);
        }
        if (ImGui.isMouseClicked(0) && (pointedGrip != null || pointed >= 0)) {
            press(open, camera, chosen, figures, x, y);
        }
        return held != null || pointedGrip != null;
    }

    void drawMarks(DirectorSession open, GeoOverlayLines marks) {
        var document = open.getDocument();
        if (document == null || open.getPlacing() != null) {
            return;
        }
        var chosen = open.getChosenOrder();
        var order = chosen == null ? null : open.order(chosen);
        var actor = order == null ? -1 : order.index();
        for (var figure : collectFigures(open)) {
            var x = figure.x();
            var y = figure.y();
            var feet = figure.feet();
            if (!figure.seen()) {
                marks.handle(new GeoPoint(x, y, feet), DOT_LIFT, HELPER_COLOR, HELPER_DOT);
            }
            if (figure.index() == actor && actor > 0) {
                ring(marks, x, y, feet, figure.radius() + RING_MARGIN, HudWidgets.ACCENT, CHOSEN_RING);
            } else if (figure.index() == pointed && held == null) {
                ring(marks, x, y, feet, figure.radius() + RING_MARGIN, POINTED, POINTED_RING);
            }
        }
        if (order == null || !SceneOrders.hasPlace(order)) {
            return;
        }
        var hot = held == Grip.DOT || held == Grip.BODY || held == null && pointedGrip == Grip.DOT;
        var place = new GeoPoint(order.x(), order.y(), order.z());
        if (turns(order)) {
            var tip = tip(order, turnReach(open, order));
            marks.addLine(place, tip, DOT_LIFT, TURN_LINE, TURN_LINE_WIDTH);
            var turning = held == Grip.TURN || held == null && pointedGrip == Grip.TURN;
            marks.handle(tip, DOT_LIFT, turning ? HudWidgets.ACCENT : TURN_COLOR, turning ? HOT_TURN_DOT : TURN_DOT);
        }
        marks.handle(place, DOT_LIFT, hot ? HOT_WHITE : WHITE, hot ? HOT_DOT : DOT);
    }

    void hold(
            DirectorSession open,
            Grip grip,
            Key key,
            double height,
            double worldX,
            double worldY,
            float screenX,
            float screenY) {
        var start = open.order(key);
        planeZ = height;
        pressWorldX = worldX;
        pressWorldY = worldY;
        held = grip;
        heldKey = key;
        heldStart = start;
        heldFlies = flies(SceneOrders.entry(open.getDocument().items(), start.index()), start);
        pressX = screenX;
        pressY = screenY;
        moved = false;
        cancelled = false;
        askFloors(open, start.x(), start.y());
        floorStart = floor(open, start.x(), start.y(), start.z());
        floorLast = floorStart;
    }

    void moveTo(DirectorSession open, double worldX, double worldY) {
        var start = heldStart;
        var key = heldKey;
        var document = open.getDocument();
        if (held == null
                || cancelled
                || start == null
                || key == null
                || document == null
                || Double.isNaN(pressWorldX)) {
            return;
        }
        if (!isStillHolding(document.items(), key, start)) {
            cancelled = true;
            return;
        }
        moved = true;
        var met = new double[] {worldX, worldY};
        Actor next;
        if (held == Grip.TURN) {
            var from = Math.atan2(pressWorldY - start.y(), pressWorldX - start.x());
            var to = Math.atan2(met[1] - start.y(), met[0] - start.x());
            next = SceneOrders.facing(start, turned(start.yaw(), from, to));
        } else {
            var dx = met[0] - pressWorldX;
            var dy = met[1] - pressWorldY;
            if (Double.isNaN(floorStart)) {
                floorStart = floor(open, start.x(), start.y(), start.z());
                floorLast = floorStart;
            }
            var floorNow = heldFlies || Double.isNaN(floorLast)
                    ? Double.NaN
                    : floor(open, start.x() + dx, start.y() + dy, floorLast);
            if (!Double.isNaN(floorNow)) {
                floorLast = floorNow;
            }
            next = moved(start, dx, dy, floorStart, floorNow, heldFlies);
        }
        open.dragOrder(key, next);
    }

    void release(DirectorSession open) {
        if (moved && !cancelled) {
            open.settle();
        }
        held = null;
        heldKey = null;
        heldStart = null;
        cancelled = false;
    }

    List<Figure> collectFigures(DirectorSession open) {
        var run = open.getBaked();
        var document = open.getDocument();
        if (run == null || document == null) {
            return List.of();
        }
        var figures = new ArrayList<Figure>();
        var nudge = open.getNudge();
        for (var standing : open.getStanding()) {
            var index = standing.index();
            var place = run.place(index, open.getPlayhead());
            if (place == null) {
                continue;
            }
            var entry = SceneOrders.entry(document.items(), index);
            var size = run.getSize(index);
            var ship = entry != null && entry.action() == Action.AIRSHIP_SPAWN;
            double half;
            double radius;
            if (size != null) {
                half = size.halfHeight();
                radius = size.radius();
            } else if (ship) {
                half = SHIP_HALF_HEIGHT;
                radius = SHIP_RADIUS;
            } else {
                var known = entry == null ? 0 : heights.applyAsDouble(entry.npc());
                half = known > 0 ? known : HALF_HEIGHT;
                radius = half * RADIUS_SHARE;
            }
            var helper = entry != null && !entry.player() && entry.npc() > 0 && entry.npc() == open.getHelper();
            var moved = nudge != null && nudge.index() == index;
            var middle = place.z() + (moved ? nudge.dz() : 0);
            figures.add(new Figure(
                    index,
                    place.x() + (moved ? nudge.dx() : 0),
                    place.y() + (moved ? nudge.dy() : 0),
                    middle - half,
                    middle + half,
                    radius,
                    !helper));
        }
        return figures;
    }

    private void press(
            DirectorSession open, Camera camera, @Nullable Key chosen, List<Figure> figures, float x, float y) {
        var grip = pointedGrip;
        var key = chosen;
        Figure figure = null;
        if (grip == null) {
            key = open.getPlacer(pointed);
            for (var each : figures) {
                if (each.index() == pointed) {
                    figure = each;
                }
            }
            if (key == null) {
                return;
            }
            if (!key.equals(chosen)) {
                open.select(key, false);
                onChosen.accept(pointed);
            }
            grip = Grip.BODY;
        }
        var start = open.order(key);
        var ray = ray(camera, x, y);
        var height = figure == null
                ? start.z()
                : grabHeight(
                        ray[0],
                        ray[1],
                        ray[2],
                        ray[3],
                        ray[4],
                        ray[5],
                        figure.x(),
                        figure.y(),
                        figure.feet(),
                        figure.top());
        var met = onPlane(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], height);
        hold(open, grip, key, height, met == null ? Double.NaN : met[0], met == null ? Double.NaN : met[1], x, y);
    }

    private void follow(DirectorSession open, Camera camera) {
        if (!ImGui.isMouseDown(0)) {
            release(open);
            return;
        }
        var x = ImGui.getMousePosX();
        var y = ImGui.getMousePosY();
        if (cancelled || Double.isNaN(pressWorldX)) {
            return;
        }
        if (!moved && Math.hypot(x - pressX, y - pressY) < CLICK_PIXELS) {
            return;
        }
        var ray = ray(camera, x, y);
        var met = onPlane(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], planeZ);
        if (met != null) {
            moveTo(open, met[0], met[1]);
        }
    }

    private @Nullable Grip gripAt(Screen screen, Actor order, double reach, float x, float y) {
        if (turns(order)) {
            var tip = tip(order, reach);
            var at = screen.at(tip.x(), tip.y(), tip.z() + DOT_LIFT);
            if (at != null && Math.hypot(at[0] - x, at[1] - y) <= DOT_REACH) {
                return Grip.TURN;
            }
        }
        var at = screen.at(order.x(), order.y(), order.z() + DOT_LIFT);
        return at != null && Math.hypot(at[0] - x, at[1] - y) <= DOT_REACH ? Grip.DOT : null;
    }

    private double turnReach(DirectorSession open, Actor order) {
        var run = open.getBaked();
        var size = run == null ? null : run.getSize(order.index());
        var entry = SceneOrders.entry(open.getDocument().items(), order.index());
        var known = entry == null ? 0 : heights.applyAsDouble(entry.npc());
        var ship = entry != null && entry.action() == Action.AIRSHIP_SPAWN;
        var radius =
                size != null ? size.radius() : ship ? SHIP_RADIUS : (known > 0 ? known : HALF_HEIGHT) * RADIUS_SHARE;
        return Math.clamp(radius + TURN_MARGIN, TURN_NEAREST, TURN_FARTHEST);
    }

    private void askFloors(DirectorSession open, double x, double y) {
        var loaded = tiles;
        var document = open.getDocument();
        if (loaded == null || document == null) {
            return;
        }
        if (!document.map().isEmpty()) {
            open.readFloors(document.map());
            return;
        }
        var tile = WorldTile.at((float) x, (float) y);
        var name = tile.x() + "_" + tile.y();
        if (loaded.has(name)) {
            open.readFloors(name);
        }
    }

    private static GeoPoint tip(Actor order, double reach) {
        var way = order.yaw() * 2 * Math.PI / TURN;
        return new GeoPoint(order.x() + reach * Math.cos(way), order.y() + reach * Math.sin(way), order.z());
    }

    private static double sidePixels(Screen screen, Figure figure, double middle, double eyeX, double eyeY) {
        var awayX = figure.x() - eyeX;
        var awayY = figure.y() - eyeY;
        var level = Math.hypot(awayX, awayY);
        var sideX = level < 1e-6 ? 1 : -awayY / level;
        var sideY = level < 1e-6 ? 0 : awayX / level;
        var at = screen.at(figure.x(), figure.y(), middle);
        var edge = screen.at(figure.x() + sideX * figure.radius(), figure.y() + sideY * figure.radius(), middle);
        return at == null || edge == null ? 0 : Math.hypot(edge[0] - at[0], edge[1] - at[1]);
    }

    private static double floor(DirectorSession open, double x, double y, double z) {
        var floors = open.getFloors();
        return floors == null ? Double.NaN : floors.getSurfaceHeight(x, y, z);
    }

    private static void ring(
            GeoOverlayLines marks, double x, double y, double z, double radius, int color, float width) {
        for (var side = 0; side < RING_SIDES; side++) {
            var from = side * 2 * Math.PI / RING_SIDES;
            var to = (side + 1) * 2 * Math.PI / RING_SIDES;
            marks.addLine(
                    new GeoPoint(x + radius * Math.cos(from), y + radius * Math.sin(from), z),
                    new GeoPoint(x + radius * Math.cos(to), y + radius * Math.sin(to), z),
                    2,
                    color,
                    width);
        }
    }

    private static Screen screen(Camera camera) {
        var io = ImGui.getIO();
        var width = (int) io.getDisplaySizeX();
        var height = (int) io.getDisplaySizeY();
        return (x, y, z) -> {
            var at = camera.projectToScreenRaw(new Vec3((float) x, (float) y, (float) z), width, height);
            return at == null ? null : new float[] {at.getX(), at.getY()};
        };
    }

    private static double[] ray(Camera camera, float x, float y) {
        var io = ImGui.getIO();
        var ndcX = x / io.getDisplaySizeX() * 2f - 1f;
        var ndcY = 1f - y / io.getDisplaySizeY() * 2f;
        var from = camera.getCursorRayOrigin(ndcX, ndcY);
        var way = camera.getCursorRayDirection(ndcX, ndcY);
        return new double[] {from.getX(), from.getY(), from.getZ(), way.getX(), way.getY(), way.getZ()};
    }

    private static double square(double value) {
        return value * value;
    }
}
