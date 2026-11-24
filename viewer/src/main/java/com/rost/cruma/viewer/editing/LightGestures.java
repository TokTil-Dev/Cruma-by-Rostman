package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.ActorTransform.Rotator;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.editing.LightColor;
import com.rost.cruma.core.editing.LightPlacement;
import com.rost.cruma.core.editing.LightRecord;
import com.rost.cruma.core.editing.LightShape;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.viewer.editing.EditorViewport.Actor;
import com.rost.cruma.viewer.geodata.GeoOverlayLines;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.EditorEffectStage;
import com.rost.cruma.viewer.render.LightGlow;
import com.rost.cruma.viewer.render.LightGlows;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import com.rost.cruma.viewer.ui.GeodataWidgets;
import com.rost.cruma.viewer.ui.HudWidgets;
import com.rost.cruma.viewer.ui.MarkerDepth;
import com.rost.cruma.viewer.ui.MarkerDepth.Placed;
import com.rost.cruma.viewer.ui.MarkerDepth.Projector;
import glm_.vec3.Vec3;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.extension.imguizmo.ImGuizmo;
import imgui.extension.imguizmo.flag.Mode;
import imgui.extension.imguizmo.flag.Operation;
import imgui.flag.ImGuiHoveredFlags;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

final class LightGestures {
    static final float AIM_PIXELS = 110;

    private static final float GRAB = 12;
    private static final float MARK_FADE = 16384;
    private static final int MAX_OUTLINED = 256;
    private static final float PROBE_PIXELS = 2;
    private static final float NORMAL_PIXELS = 3;
    private static final int ANSWER_FRAMES = 40;
    private static final float FAR = 1_000_000f;
    private static final float FLOOR_DEPTH = 8192;
    private static final float FLOOR_WIDTH = 128;
    private static final float SEGMENT_PIXELS = 6;

    private static final int MIN_SEGMENTS = 24;

    private static final int MAX_SEGMENTS = 256;
    private static final float MOVE_PIXELS = 2;

    private static final float GLOW_FADE = 6;
    private final EditingSession session;
    private final ViewerRenderBackend backend;
    private final EditingPanel panel;
    private @Nullable String markDrag;
    private float markDragZ;
    private float markDragX;
    private float markDragY;
    private float markPressX;
    private float markPressY;
    private float markStartX;
    private float markStartY;
    private boolean markMoved;
    private boolean markCopy;
    private boolean markKeeps;
    private @Nullable String radiusDrag;
    private @Nullable String aimDrag;
    private float aimLength;
    private boolean aimFar;
    private final float[] gizmo = new float[16];
    private @Nullable LightGlows glows;
    private float glowTint;
    private final WorldGizmo worldGizmo = new WorldGizmo();
    private final float[] snap = new float[3];
    private @Nullable String gizmoDrag;
    private boolean gizmoCopy;
    private boolean gizmoCancelled;
    private float @Nullable [] probeNdc;
    private int probeFrames;
    private float probeX = Float.NaN;
    private float probeY = Float.NaN;
    private @Nullable Vector3 surface;
    private Vector3 surfaceNormal = new Vector3(0, 0, 1);
    private float surfaceX = Float.NaN;
    private float surfaceY = Float.NaN;
    private boolean clickPending;
    private boolean clickMore;
    private float clickX;
    private float clickY;
    private @Nullable LightPlacement ghostPlace;
    private String ghostRestriction = "";
    private String floorKey = "";
    private float floorZ = Float.NaN;
    private String reachKey = "";
    private List<Lit> reached = List.of();
    private int reachedCount = -1;

    LightGestures(EditingSession session, ViewerRenderBackend backend, EditingPanel panel) {
        this.session = session;
        this.backend = backend;
        this.panel = panel;
    }

    private record Lit(Bounds3f bounds, float strength) {}

    static float alongAxis(Camera camera, float x, float y, int width, int height, Vector3 at, Vector3 axis) {
        var ndcX = x / width * 2 - 1;
        var ndcY = 1 - y / height * 2;
        var origin = camera.getCursorRayOrigin(ndcX, ndcY);
        var ray = normalize(toVector(camera.getCursorRayDirection(ndcX, ndcY)));
        if (ray == null) {
            return Float.NaN;
        }
        var offset = minus(at, new Vector3(origin.getX(), origin.getY(), origin.getZ()));
        var b = dot(axis, ray);
        var denominator = 1 - b * b;
        if (denominator < 1e-6f) {
            return Float.NaN;
        }
        return (b * dot(ray, offset) - dot(axis, offset)) / denominator;
    }

    static @Nullable Vector3 aimHandle(Camera camera, LightRecord light, int width, int height) {
        if (!light.shape().isSpot()) {
            return null;
        }
        var at = light.location();
        var length = Math.min(light.reach() * .7f, aimLength(camera, at, width, height));
        var direction = light.shape().direction();
        return new Vector3(
                at.x() + direction.x() * length, at.y() + direction.y() * length, at.z() + direction.z() * length);
    }

    static Rotator snap(Rotator rotation, float step) {
        if (!(step > 0)) {
            return rotation;
        }
        var units = step * 65536 / 360.0;
        return new Rotator(
                (int) Math.round(Math.round(rotation.pitch() / units) * units),
                (int) Math.round(Math.round(rotation.yaw() / units) * units),
                rotation.roll());
    }

    static float snapped(float distance, float step) {
        return step > 0 ? Math.round(distance / step) * step : distance;
    }

    static boolean inBox(float x, float y, float x1, float y1, float x2, float y2) {
        return x >= Math.min(x1, x2) && x <= Math.max(x1, x2) && y >= Math.min(y1, y2) && y <= Math.max(y1, y2);
    }

    static double innerAngle(LightShape shape) {
        var sine = 1 - shape.cone() / 256.0;
        return Math.acos(Math.clamp(sine + (1 - sine) * Math.sqrt(.5), -1, 1));
    }

    static double[] silhouette(Vector3 toEye, Vector3 axis, Vector3[] basis, double angle) {
        var a = dot(basis[0], toEye);
        var b = dot(basis[1], toEye);
        var c = Math.tan(angle) * dot(axis, toEye);
        var r = Math.hypot(a, b);
        if (r < 1e-6 || Math.abs(c) > r) {
            return new double[] {0, Math.PI / 2, Math.PI, Math.PI * 1.5};
        }
        var middle = Math.atan2(b, a);
        var spread = Math.acos(c / r);
        return new double[] {middle - spread, middle + spread};
    }

    static float strength(Vector3 light, LightShape shape, float reach, Bounds3f box) {
        var best = 0f;
        float[] xs = {
            Math.clamp(light.x(), box.minX(), box.maxX()), (box.minX() + box.maxX()) / 2, box.minX(), box.maxX()
        };
        float[] ys = {
            Math.clamp(light.y(), box.minY(), box.maxY()), (box.minY() + box.maxY()) / 2, box.minY(), box.maxY()
        };
        float[] zs = {
            Math.clamp(light.z(), box.minZ(), box.maxZ()), (box.minZ() + box.maxZ()) / 2, box.minZ(), box.maxZ()
        };
        for (var i = 0; i < 10; i++) {
            var px = i < 2 ? xs[i] : xs[2 + ((i - 2) & 1)];
            var py = i < 2 ? ys[i] : ys[2 + ((i - 2) >> 1 & 1)];
            var pz = i < 2 ? zs[i] : zs[2 + ((i - 2) >> 2 & 1)];
            var dx = px - light.x();
            var dy = py - light.y();
            var dz = pz - light.z();
            var distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance >= reach) {
                continue;
            }
            var a = distance / reach;
            var value = 2 * a * a * a - 3 * a * a + 1;
            if (shape.isSpot()) {
                if (distance < 1e-3f) {
                    best = 1;
                    continue;
                }
                var axis = shape.direction();
                var cosine = (dx * axis.x() + dy * axis.y() + dz * axis.z()) / distance;
                var sine = 1 - shape.cone() / 256f;
                if (!(cosine > sine) || sine >= 1) {
                    continue;
                }
                var cone = (cosine - sine) / (1 - sine);
                value *= cone * cone;
            }
            best = Math.max(best, value);
        }
        return best;
    }

    static int segmentsFor(float pixels) {
        if (!Float.isFinite(pixels)) {
            return MAX_SEGMENTS;
        }
        return (int) Math.clamp(Math.ceil(Math.PI * 2 * pixels / SEGMENT_PIXELS), MIN_SEGMENTS, MAX_SEGMENTS);
    }

    static int alpha(int color, float opacity) {
        var a = Math.round(Math.clamp(opacity, 0f, 1f) * ((color >>> 24) & 0xff));
        return color & 0x00ffffff | a << 24;
    }

    private record World(
            ImDrawList draw,
            Camera camera,
            int width,
            int height,
            @Nullable GeoOverlayLines lines,
            boolean surfaces) {
        void line(Vector3 a, Vector3 b, int color, float thickness) {
            if (lines != null) {
                lines.addLine(
                        new GeoPoint(a.x(), a.y(), a.z()), new GeoPoint(b.x(), b.y(), b.z()), 0, color, thickness);
                return;
            }
            var p = EditorViewport.screenPoint(camera, a.x(), a.y(), a.z(), width, height);
            var q = EditorViewport.screenPoint(camera, b.x(), b.y(), b.z(), width, height);
            if (p != null && q != null) {
                draw.addLine(p[0], p[1], q[0], q[1], color, thickness);
            }
        }

        void stroke(Vector3 a, Vector3 b, int color, float thickness) {
            line(a, b, alpha(HudWidgets.BACKGROUND, .6f), thickness + 2);
            line(a, b, color, thickness);
        }

        void path(List<Vector3> points, int color, float thickness) {
            for (var i = 1; i < points.size(); i++) {
                line(points.get(i - 1), points.get(i), alpha(HudWidgets.BACKGROUND, .6f), thickness + 2);
            }
            for (var i = 1; i < points.size(); i++) {
                line(points.get(i - 1), points.get(i), color, thickness);
            }
        }

        int segments(Vector3 center, Vector3 u, Vector3 v, float radius) {
            var middle = EditorViewport.screenPoint(camera, center.x(), center.y(), center.z(), width, height);
            var a = EditorViewport.screenPoint(
                    camera,
                    center.x() + u.x() * radius,
                    center.y() + u.y() * radius,
                    center.z() + u.z() * radius,
                    width,
                    height);
            var b = EditorViewport.screenPoint(
                    camera,
                    center.x() + v.x() * radius,
                    center.y() + v.y() * radius,
                    center.z() + v.z() * radius,
                    width,
                    height);
            if (middle == null || a == null || b == null) {
                return MAX_SEGMENTS;
            }
            var pixels = Math.max(
                    Math.hypot(a[0] - middle[0], a[1] - middle[1]), Math.hypot(b[0] - middle[0], b[1] - middle[1]));
            return segmentsFor((float) pixels);
        }

        void ring(Vector3 center, Vector3 u, Vector3 v, float radius, int color, float thickness) {
            if (!(radius > 0)) {
                return;
            }
            var previous = center;
            var segments = segments(center, u, v, radius);
            for (var i = 0; i <= segments; i++) {
                var turn = Math.PI * 2 * i / segments;
                var c = (float) Math.cos(turn) * radius;
                var s = (float) Math.sin(turn) * radius;
                var point = new Vector3(
                        center.x() + u.x() * c + v.x() * s,
                        center.y() + u.y() * c + v.y() * s,
                        center.z() + u.z() * c + v.z() * s);
                if (i > 0) {
                    line(previous, point, color, thickness);
                }
                previous = point;
            }
        }

        void box(Bounds3f box, int color, float thickness) {
            var corners = new Vector3[8];
            for (var corner = 0; corner < 8; corner++) {
                corners[corner] = new Vector3(
                        (corner & 1) == 0 ? box.minX() : box.maxX(),
                        (corner & 2) == 0 ? box.minY() : box.maxY(),
                        (corner & 4) == 0 ? box.minZ() : box.maxZ());
            }
            for (var edge : GeoOverlayLines.BOX_EDGES) {
                line(corners[edge[0]], corners[edge[1]], color, thickness);
            }
        }
    }

    boolean update(
            Camera camera,
            float x,
            float y,
            int width,
            int height,
            boolean inside,
            boolean uiHasMouse,
            float left,
            float top,
            float right,
            float bottom) {
        var tool = panel.getLights();
        if (tool.isPlacing()) {
            endDrags();
            return place(camera, x, y, width, height, inside && !uiHasMouse);
        }
        clickPending = false;
        if (radiusDrag != null) {
            updateRadiusDrag(camera, x, y, width, height);
            return true;
        }
        if (markDrag != null) {
            updateMarkDrag(camera, x, y, width, height);
            return true;
        }
        if (aimDrag != null) {
            updateAimDrag(camera, x, y, width, height);
            return true;
        }
        var onAim = inside
                && !ImGui.isWindowHovered(ImGuiHoveredFlags.AnyWindow)
                && gizmoDrag == null
                && isOnAimHandle(camera, x, y, width, height);
        if (onAim && ImGui.isMouseClicked(0)) {
            pressAimHandle(camera, x, y, width, height);
            return true;
        }
        if (drawGizmo(
                camera,
                left,
                top,
                right,
                bottom,
                inside && !onAim && !ImGui.isWindowHovered(ImGuiHoveredFlags.AnyWindow))) {
            return true;
        }
        if (!inside || uiHasMouse || !ImGui.isMouseClicked(0)) {
            return false;
        }
        return pressRadiusHandle(camera, x, y, width, height) || pressMark(camera, x, y, width, height);
    }

    boolean isDragging() {
        return markDrag != null || radiusDrag != null || gizmoDrag != null || aimDrag != null;
    }

    boolean cancel() {
        var cancelled = isDragging() || clickPending;
        clickPending = false;
        gizmoCancelled = gizmoDrag != null;
        if (isDragging()) {
            markDrag = null;
            radiusDrag = null;
            gizmoDrag = null;
            aimDrag = null;
            session.cancelLightPlacement();
            session.cancelLightShape();
        }
        return cancelled;
    }

    @Nullable
    String findLightAt(Camera camera, float x, float y, int width, int height) {
        var light = markAt(camera, x, y, width, height);
        return light == null ? null : light.sourcePath();
    }

    void chooseIn(Camera camera, float x1, float y1, float x2, float y2, int width, int height, boolean additive) {
        var inside = new ArrayList<String>();
        for (var light : panel.getLightMarks()) {
            var at = light.location();
            var point = EditorViewport.screenPoint(camera, at.x(), at.y(), at.z(), width, height);
            if (point != null && inBox(point[0], point[1], x1, y1, x2, y2)) {
                inside.add(light.sourcePath());
            }
        }
        panel.getLights().choose(inside, additive);
    }

    void draw(
            ImDrawList draw,
            Camera camera,
            int width,
            int height,
            float left,
            float top,
            float right,
            float bottom,
            @Nullable GeoOverlayLines lines,
            @Nullable LightGlows glows,
            Map<String, Actor> actors,
            long actorsVersion) {
        var tool = panel.getLights();
        var marks = panel.getLightMarks();
        if (marks.isEmpty() && !tool.isPlacing()) {
            return;
        }
        var moving = isDragging() || tool.isPlacing() || session.isLightingPreviewBusy();
        glowTint += ((moving ? 1 : 0) - glowTint) * Math.min(1, ImGui.getIO().getDeltaTime() * GLOW_FADE);
        this.glows = glows;
        var chosen = tool.getSelected();
        var listed = tool.getHovered();
        var x = ImGui.getMousePosX();
        var y = ImGui.getMousePosY();
        var pointerFree = x >= left
                && x <= right
                && y >= top
                && y <= bottom
                && !ImGui.getIO().getWantCaptureMouse()
                && !isDragging()
                && !tool.isPlacing();
        var under = pointerFree ? markAt(camera, x, y, width, height) : null;
        LightRecord current = null;
        var all = new HashSet<>(tool.getChosen());
        var projector = new Projector(camera, width, height);
        var placedMarks = new ArrayList<PlacedMark>(marks.size());
        for (var light : marks) {
            var path = light.sourcePath();
            if (path.equals(chosen)) {
                current = light;
            }
            var selected = all.contains(path);
            var hovered =
                    path.equals(listed) || under != null && under.sourcePath().equals(path);
            if (lines == null) {
                drawMark(draw, camera, width, height, light, selected, hovered);
                continue;
            }
            var at = light.location();
            var placed = projector.place(at.x(), at.y(), at.z());
            if (placed != null) {
                placedMarks.add(new PlacedMark(light, placed, selected, hovered));
            }
        }
        placedMarks.sort(
                Comparator.comparingDouble((PlacedMark mark) -> mark.placed().distance())
                        .reversed());
        for (var mark : placedMarks) {
            drawMark(lines, projector, mark);
        }
        var world = new World(draw, camera, width, height, lines, glows != null && glows.isDrawn());
        if (tool.isPlacing()) {
            drawGhost(world);
        } else if (current != null) {
            drawChosen(world, current, actors, actorsVersion);
        } else {
            tool.viewInfo(-1, Float.NaN);
        }
        if (under != null && !under.sourcePath().equals(chosen)) {
            HudWidgets.showHoverCard(
                    under.sourcePath().hashCode(),
                    EditingSession.shortName(under.sourcePath()),
                    List.of(
                            under.className() + " · " + under.shape().describe(),
                            "Reaches "
                                    + EditorUi.unitsAndMeters(under.reach())
                                    + (under.isChanged() ? " · changed" : "")),
                    "Click to choose · " + EditingPanel.COMMAND_KEY
                            + "+click to add · Drag to move · Alt+drag to copy");
        }
    }

    private record PlacedMark(LightRecord light, Placed placed, boolean selected, boolean hovered) {}

    private void endDrags() {
        if (isDragging()) {
            markDrag = null;
            radiusDrag = null;
            gizmoDrag = null;
            aimDrag = null;
            panel.getLights().finishDrag();
        }
    }

    private boolean place(Camera camera, float x, float y, int width, int height, boolean inside) {
        if (!inside) {
            clickPending = false;
            surface = null;
            surfaceX = Float.NaN;
            surfaceY = Float.NaN;
            probeNdc = null;
            return false;
        }
        probe(camera, x, y, width, height);
        var io = ImGui.getIO();
        if (ImGui.isMouseClicked(0)) {
            clickPending = true;
            clickMore = io.getKeyShift();
            clickX = x;
            clickY = y;
        }
        if (clickPending && Math.hypot(x - clickX, y - clickY) > GRAB) {
            clickPending = false;
        }
        if (clickPending && Math.hypot(surfaceX - clickX, surfaceY - clickY) < PROBE_PIXELS) {
            clickPending = false;
            if (surface == null) {
                panel.showError("Click a wall, a floor or the ground to add a light there.");
            } else {
                panel.getLights()
                        .place(
                                panel.getLights().getNewPlacement(surface, surfaceNormal),
                                scale(surfaceNormal, -1),
                                clickMore);
            }
        }
        return true;
    }

    private void probe(Camera camera, float x, float y, int width, int height) {
        if (probeNdc == null) {
            if (Math.hypot(x - surfaceX, y - surfaceY) < PROBE_PIXELS) {
                return;
            }
        } else if (Math.hypot(x - probeX, y - probeY) < PROBE_PIXELS) {
        } else {
            probeNdc = null;
        }
        if (probeNdc == null) {
            float[][] offsets = {
                {0, 0}, {-NORMAL_PIXELS, 0}, {NORMAL_PIXELS, 0}, {0, -NORMAL_PIXELS}, {0, NORMAL_PIXELS}
            };
            probeNdc = new float[offsets.length * 2];
            for (var i = 0; i < offsets.length; i++) {
                probeNdc[i * 2] = (x + offsets[i][0]) / width * 2 - 1;
                probeNdc[i * 2 + 1] = 1 - (y + offsets[i][1]) / height * 2;
            }
            probeX = x;
            probeY = y;
            probeFrames = 0;
        }
        var ndc = probeNdc;
        var distances = backend.getSceneRayDistances(ndc, FAR);
        if (distances == null) {
            if (++probeFrames > ANSWER_FRAMES) {
                probeNdc = null;
                surface = null;
                surfaceX = probeX;
                surfaceY = probeY;
            }
            return;
        }
        probeNdc = null;
        surfaceX = probeX;
        surfaceY = probeY;
        var points = new Vector3[5];
        for (var i = 0; i < points.length; i++) {
            if (Float.isFinite(distances[i])) {
                var origin = camera.getCursorRayOrigin(ndc[i * 2], ndc[i * 2 + 1]);
                var direction = camera.getCursorRayDirection(ndc[i * 2], ndc[i * 2 + 1]);
                points[i] = new Vector3(
                        origin.getX() + direction.getX() * distances[i],
                        origin.getY() + direction.getY() * distances[i],
                        origin.getZ() + direction.getZ() * distances[i]);
            }
        }
        surface = points[0];
        if (surface == null) {
            return;
        }
        var ray = camera.getCursorRayDirection(ndc[0], ndc[1]);
        var toward = new Vector3(-ray.getX(), -ray.getY(), -ray.getZ());
        var side = camera.getCursorRayDirection(ndc[2], ndc[3]);
        var apart = distances[0]
                * (float) Math.sqrt(
                        sq(side.getX() - ray.getX()) + sq(side.getY() - ray.getY()) + sq(side.getZ() - ray.getZ()));
        var near = apart * 4 + .5f;
        var across = span(points[1], points[2], points[0], near);
        var down = span(points[3], points[4], points[0], near);
        surfaceNormal = toward;
        if (across != null && down != null) {
            var normal = normalize(cross(across, down));
            if (normal != null) {
                surfaceNormal = dot(normal, toward) < 0 ? scale(normal, -1) : normal;
            }
        }
    }

    private boolean drawGizmo(Camera camera, float left, float top, float right, float bottom, boolean interactive) {
        if (gizmoCancelled) {
            if (ImGui.isMouseDown(0)) {
                ImGuizmo.enable(false);
                return true;
            }
            gizmoCancelled = false;
        }
        var tool = panel.getLights();
        var path = gizmoDrag != null ? gizmoDrag : tool.getSelected();
        var light = path.isEmpty() ? null : session.getLight(path).orElse(null);
        if (light == null || !tool.canDrag(path)) {
            if (gizmoDrag != null) {
                gizmoDrag = null;
                tool.finishDrag();
            }
            return false;
        }
        if (gizmoDrag == null) {
            var at = light.location();
            System.arraycopy(EditorTransforms.identity(), 0, gizmo, 0, 16);
            gizmo[12] = at.x();
            gizmo[13] = at.y();
            gizmo[14] = at.z();
        }
        var step = panel.getMoveSnap();
        snap[0] = snap[1] = snap[2] = step;
        worldGizmo.manipulate(
                camera,
                left,
                top,
                right,
                bottom,
                gizmoDrag != null || interactive && !ImGui.isAnyItemActive(),
                Operation.TRANSLATE,
                Mode.WORLD,
                gizmo,
                step > 0 ? snap : null);
        var taken = ImGuizmo.isOver() || ImGuizmo.isUsing();
        if (ImGuizmo.isUsing()) {
            if (gizmoDrag == null) {
                gizmoDrag = path;
                gizmoCopy = ImGui.getIO().getKeyAlt();
            }
            var to = new Vector3(gizmo[12], gizmo[13], gizmo[14]);
            if (gizmoCopy && !to.equals(light.location())) {
                gizmoCopy = false;
                var copy = tool.copyForDrag(gizmoDrag);
                if (copy != null) {
                    gizmoDrag = copy;
                }
            }
            if (!gizmoCopy) {
                tool.drag(gizmoDrag, to);
            }
        } else if (gizmoDrag != null) {
            gizmoDrag = null;
            tool.finishDrag();
        }
        return taken;
    }

    private boolean pressRadiusHandle(Camera camera, float x, float y, int width, int height) {
        var tool = panel.getLights();
        var light = session.getLight(tool.getSelected()).orElse(null);
        if (light == null || !tool.canDrag(light.sourcePath())) {
            return false;
        }
        var handle = EditorViewport.screenPoint(camera, radiusHandle(camera, light), width, height);
        if (handle == null || Math.hypot(handle[0] - x, handle[1] - y) > GRAB) {
            return false;
        }
        radiusDrag = light.sourcePath();
        return true;
    }

    private void updateRadiusDrag(Camera camera, float x, float y, int width, int height) {
        var path = radiusDrag;
        var light = session.getLight(path).orElse(null);
        if (light == null || !ImGui.isMouseDown(0)) {
            radiusDrag = null;
            panel.getLights().finishDrag();
            return;
        }
        var at = light.location();
        var axis = light.shape().isSpot() ? normalize(light.shape().direction()) : null;
        var reach = Float.NaN;
        if (axis != null) {
            reach = alongAxis(camera, x, y, width, height, at, axis);
        } else {
            var point = EditorViewport.groundPoint(camera, x, y, width, height, at.z());
            if (point != null) {
                reach = (float) Math.hypot(point[0] - at.x(), point[1] - at.y());
            }
        }
        if (Float.isFinite(reach)) {
            panel.getLights()
                    .dragRadius(path, Math.clamp(Math.round(reach / 25 - 1), 0, (int) LightPlacement.MAX_RADIUS));
        }
        var now = session.getLight(path).orElse(light);
        HudWidgets.showTooltip(String.format(
                Locale.ROOT, "Radius %.0f · reaches %s", now.radius(), EditorUi.unitsAndMeters(now.reach())));
    }

    private boolean isOnAimHandle(Camera camera, float x, float y, int width, int height) {
        var tool = panel.getLights();
        var light = session.getLight(tool.getSelected()).orElse(null);
        if (light == null || !tool.canTurn(light.sourcePath())) {
            return false;
        }
        var handle = aimHandle(camera, light, width, height);
        var point = handle == null
                ? null
                : EditorViewport.screenPoint(camera, handle.x(), handle.y(), handle.z(), width, height);
        return point != null && Math.hypot(point[0] - x, point[1] - y) <= GRAB;
    }

    private void pressAimHandle(Camera camera, float x, float y, int width, int height) {
        var light = session.getLight(panel.getLights().getSelected()).orElseThrow();
        var handle = Objects.requireNonNull(aimHandle(camera, light, width, height));
        var at = light.location();
        aimLength = length(minus(handle, at));
        var near = aimPoint(camera, x, y, width, height, at, aimLength, false);
        var far = aimPoint(camera, x, y, width, height, at, aimLength, true);
        aimFar = near != null && far != null && length(minus(far, handle)) < length(minus(near, handle));
        aimDrag = light.sourcePath();
    }

    private void updateAimDrag(Camera camera, float x, float y, int width, int height) {
        var path = aimDrag;
        var light = session.getLight(path).orElse(null);
        if (light == null || !ImGui.isMouseDown(0)) {
            aimDrag = null;
            panel.getLights().finishDrag();
            return;
        }
        var at = light.location();
        var point = aimPoint(camera, x, y, width, height, at, aimLength, aimFar);
        var direction = point == null ? null : normalize(minus(point, at));
        if (direction != null) {
            var shape = light.shape();
            panel.getLights().turn(path, shape.withRotation(snap(shape.facing(direction), panel.getAngleSnapStep())));
        }
        var now = session.getLight(path).orElse(light);
        HudWidgets.showTooltip(capitalize(LightTool.facing(now.shape()))
                + String.format(Locale.ROOT, " · cone %.0f°", now.shape().coneDegrees() * 2));
    }

    private boolean pressMark(Camera camera, float x, float y, int width, int height) {
        var tool = panel.getLights();
        var nearest = markAt(camera, x, y, width, height);
        if (nearest == null) {
            return false;
        }
        var io = ImGui.getIO();
        if (io.getKeyCtrl() || io.getKeySuper()) {
            tool.toggle(nearest.sourcePath());
            return true;
        }
        if (io.getKeyShift()) {
            tool.add(nearest.sourcePath());
            return true;
        }
        markKeeps = tool.isChosen(nearest.sourcePath()) && tool.getChosen().size() > 1 && !io.getKeyAlt();
        if (!markKeeps) {
            tool.select(nearest.sourcePath());
        }
        if (tool.canDrag(nearest.sourcePath())) {
            var at = nearest.location();
            var point = EditorViewport.groundPoint(camera, x, y, width, height, at.z());
            if (point != null) {
                markDrag = nearest.sourcePath();
                markDragZ = at.z();
                markDragX = point[0] - at.x();
                markDragY = point[1] - at.y();
                markPressX = x;
                markPressY = y;
                markStartX = at.x();
                markStartY = at.y();
                markMoved = false;
                markCopy = ImGui.getIO().getKeyAlt();
            }
        }
        if (markDrag == null && markKeeps) {
            markKeeps = false;
            tool.select(nearest.sourcePath());
        }
        return true;
    }

    private void updateMarkDrag(Camera camera, float x, float y, int width, int height) {
        var tool = panel.getLights();
        if (!ImGui.isMouseDown(0)) {
            var moved = markMoved;
            var path = markDrag;
            markDrag = null;
            if (moved) {
                tool.finishDrag();
            } else if (markKeeps && path != null) {
                tool.select(path);
            }
            markKeeps = false;
            return;
        }
        if (!markMoved) {
            if (Math.hypot(x - markPressX, y - markPressY) <= MOVE_PIXELS) {
                return;
            }
            markMoved = true;
            if (markCopy) {
                var copy = tool.copyForDrag(markDrag);
                if (copy == null) {
                    markDrag = null;
                    return;
                }
                markDrag = copy;
            }
        }
        var point = EditorViewport.groundPoint(camera, x, y, width, height, markDragZ);
        if (point != null) {
            var step = panel.getMoveSnap();
            var dx = snapped(point[0] - markDragX - markStartX, step);
            var dy = snapped(point[1] - markDragY - markStartY, step);
            tool.drag(markDrag, new Vector3(markStartX + dx, markStartY + dy, markDragZ));
        }
    }

    private @Nullable LightRecord markAt(Camera camera, float x, float y, int width, int height) {
        var projector = new Projector(camera, width, height);
        LightRecord nearest = null;
        var best = Float.MAX_VALUE;
        for (var light : panel.getLightMarks()) {
            var at = light.location();
            var placed = projector.place(at.x(), at.y(), at.z());
            if (placed == null) {
                continue;
            }
            var dx = placed.x() - x;
            var dy = placed.y() - y;
            var reach = Math.max(GRAB, MarkerDepth.pickRadius(placed.dotDiameter()));
            if (dx * dx + dy * dy <= reach * reach && placed.distance() < best) {
                best = placed.distance();
                nearest = light;
            }
        }
        return nearest;
    }

    private void drawChosen(World world, LightRecord light, Map<String, Actor> actors, long actorsVersion) {
        var at = light.location();
        var reach = light.reach();
        var shape = light.shape();
        var glow = LightTool.color(light.color());
        var floor = floor(light, actors, actorsVersion);
        var height = Float.isFinite(floor) ? at.z() - floor : Float.NaN;
        var ground = Float.isFinite(floor) ? new Plane(new Vector3(at.x(), at.y(), floor), new Vector3(0, 0, 1)) : null;
        showGlow(at, reach, shape, light.color());
        if (shape.isSpot()) {
            drawCone(world, at, shape, reach, ground, glow);
        } else {
            if (!world.surfaces()) {
                world.ring(at, new Vector3(1, 0, 0), new Vector3(0, 1, 0), reach, alpha(glow, .8f), 1.5f);
            }
            drawOutline(world, at, reach, alpha(glow, .45f));
        }
        if (ground != null) {
            var foot = new Vector3(at.x(), at.y(), floor);
            world.line(at, foot, alpha(glow, .4f), 1.5f);
            world.ring(foot, new Vector3(1, 0, 0), new Vector3(0, 1, 0), Math.min(12, reach), alpha(glow, .4f), 1.5f);
            if (!shape.isSpot() && height < reach && !world.surfaces()) {
                drawPool(world, at, reach, floor, alpha(glow, .7f), 1.5f);
                if (height < reach / 2) {
                    drawPool(world, at, reach / 2, floor, glow, 2.5f);
                }
            }
        }
        var count = -1;
        if (panel.getLights().isOutlineReach() && !isDragging()) {
            count = drawReached(world, light, actors, actorsVersion);
        } else if (!isDragging()) {
            reached(light, actors, actorsVersion);
            count = reachedCount;
        }
        panel.getLights().viewInfo(count, height);
        var accent = HudWidgets.ACCENT;
        if (panel.getLights().canDrag(light.sourcePath())) {
            var handle = EditorViewport.screenPoint(
                    world.camera(), radiusHandle(world.camera(), light), world.width(), world.height());
            if (handle != null) {
                var hot = radiusDrag != null
                        || !isDragging()
                                && Math.hypot(handle[0] - ImGui.getMousePosX(), handle[1] - ImGui.getMousePosY())
                                        <= GRAB;
                var size = hot ? 6f : 4.5f;
                world.draw()
                        .addRectFilled(
                                handle[0] - size - 1.5f,
                                handle[1] - size - 1.5f,
                                handle[0] + size + 1.5f,
                                handle[1] + size + 1.5f,
                                HudWidgets.BACKGROUND,
                                2f);
                world.draw()
                        .addRectFilled(
                                handle[0] - size, handle[1] - size, handle[0] + size, handle[1] + size, accent, 2f);
                if (hot && radiusDrag == null) {
                    HudWidgets.showTooltip("Drag to change the radius · reaches " + EditorUi.unitsAndMeters(reach));
                }
            }
        }
        if (panel.getLights().canTurn(light.sourcePath())) {
            drawAimHandle(world, light, accent);
        }
    }

    private void showGlow(Vector3 at, float reach, LightShape shape, LightColor color) {
        if (glows == null) {
            return;
        }
        var rgb = LightTool.rgb(color.hue(), color.saturation());
        var axis = shape.direction();
        glows.set(new LightGlow(
                at.x(),
                at.y(),
                at.z(),
                reach,
                axis.x(),
                axis.y(),
                axis.z(),
                shape.isSpot() ? 1 - shape.cone() / 256f : Float.NaN,
                rgb[0],
                rgb[1],
                rgb[2],
                glowTint));
    }

    private void drawAimHandle(World world, LightRecord light, int accent) {
        var tip = aimHandle(world.camera(), light, world.width(), world.height());
        if (tip == null) {
            return;
        }
        var handle =
                EditorViewport.screenPoint(world.camera(), tip.x(), tip.y(), tip.z(), world.width(), world.height());
        if (handle == null) {
            return;
        }
        var draw = world.draw();
        var hot = aimDrag != null
                || !isDragging()
                        && Math.hypot(handle[0] - ImGui.getMousePosX(), handle[1] - ImGui.getMousePosY()) <= GRAB;
        var size = hot ? 7f : 5.5f;
        draw.addCircleFilled(handle[0], handle[1], size + 1.5f, HudWidgets.BACKGROUND, 16);
        draw.addCircleFilled(handle[0], handle[1], size, accent, 16);
        draw.addCircle(handle[0], handle[1], size - 2.5f, HudWidgets.BACKGROUND, 12, 1.5f);
        if (hot && aimDrag == null) {
            HudWidgets.showTooltip("Drag to turn · " + LightTool.facing(light.shape()));
        }
    }

    private int drawReached(World world, LightRecord light, Map<String, Actor> actors, long actorsVersion) {
        var base = LightTool.color(light.color());
        for (var lit : reached(light, actors, actorsVersion)) {
            world.box(lit.bounds(), alpha(base, .2f + .7f * lit.strength()), 1 + lit.strength());
        }
        return reachedCount;
    }

    private List<Lit> reached(LightRecord light, Map<String, Actor> actors, long actorsVersion) {
        var at = light.location();
        var shape = light.shape();
        var key = light.sourcePath() + '|' + at + '|' + light.radius() + '|' + shape + '|' + actorsVersion;
        if (key.equals(reachKey)) {
            return reached;
        }
        reachKey = key;
        var prefix = EditingSession.mapOfPath(light.sourcePath()) + ".";
        var reach = light.reach();
        var found = new ArrayList<Lit>();
        for (var entry : actors.entrySet()) {
            if (!entry.getKey().startsWith(prefix)
                    || EditorEffectStage.ROW_MESH.equals(entry.getValue().mesh())) {
                continue;
            }
            var box = entry.getValue().bounds();
            var strength = strength(at, shape, reach, box);
            if (strength > 0) {
                found.add(new Lit(box, strength));
            }
        }
        reachedCount = found.size();
        found.sort(Comparator.comparingDouble(lit -> -lit.strength()));
        reached = found.size() > MAX_OUTLINED ? List.copyOf(found.subList(0, MAX_OUTLINED)) : List.copyOf(found);
        return reached;
    }

    private float floor(LightRecord light, Map<String, Actor> actors, long actorsVersion) {
        var at = light.location();
        var key = light.sourcePath() + '|' + at + '|' + actorsVersion;
        if (key.equals(floorKey)) {
            return floorZ;
        }
        var map = EditingSession.mapOfPath(light.sourcePath());
        var below = Float.NaN;
        try {
            below = session.getSolidBelow(map, at, FLOOR_DEPTH);
        } catch (RuntimeException unreadable) {
        }
        var floor = Float.isFinite(below) ? at.z() - below : Float.NaN;
        var terrain = panel.getGroundProbe().getCurrent(at.x(), at.y());
        if (terrain != null && terrain <= at.z() + 1 && !(floor >= terrain)) {
            floor = terrain;
        }
        var prefix = EditingSession.mapOfPath(light.sourcePath()) + ".";
        for (var entry : actors.entrySet()) {
            var box = entry.getValue().bounds();
            if (box.maxZ() <= at.z() + 1
                    && !(floor >= box.maxZ())
                    && box.maxX() - box.minX() >= FLOOR_WIDTH
                    && box.maxY() - box.minY() >= FLOOR_WIDTH
                    && at.x() >= box.minX()
                    && at.x() <= box.maxX()
                    && at.y() >= box.minY()
                    && at.y() <= box.maxY()
                    && entry.getKey().startsWith(prefix)) {
                floor = box.maxZ();
            }
        }
        if (panel.getGroundProbe().isReady(at.x(), at.y())) {
            floorKey = key;
        }
        floorZ = floor;
        return floor;
    }

    private void drawGhost(World world) {
        var tool = panel.getLights();
        if (surface == null) {
            return;
        }
        var placement = tool.getNewPlacement(surface, surfaceNormal);
        if (!placement.equals(ghostPlace)) {
            ghostPlace = placement;
            ghostRestriction = tool.getNewLightRestriction(placement);
        }
        var blocked = !ghostRestriction.isEmpty();
        var color = blocked ? GeodataWidgets.BLOCKED : HudWidgets.ACCENT;
        var at = placement.location();
        var reach = placement.reach();
        var shape = tool.getNewShape(surfaceNormal);
        if (!blocked) {
            showGlow(at, reach, shape == null ? LightShape.DEFAULT : shape, tool.getNewColor());
        }
        if (shape != null) {
            drawCone(
                    world,
                    at,
                    shape,
                    reach,
                    new Plane(surface, surfaceNormal),
                    blocked ? GeodataWidgets.BLOCKED : LightTool.color(tool.getNewColor()));
        } else {
            world.line(surface, at, alpha(color, .7f), 1.5f);
            world.ring(at, new Vector3(1, 0, 0), new Vector3(0, 1, 0), reach, alpha(color, .6f), 1.5f);
            if (!blocked) {
                drawOutline(world, at, reach, alpha(color, .35f));
            }
        }
        var point = EditorViewport.screenPoint(world.camera(), at.x(), at.y(), at.z(), world.width(), world.height());
        var foot = EditorViewport.screenPoint(
                world.camera(), surface.x(), surface.y(), surface.z(), world.width(), world.height());
        if (foot != null) {
            world.draw().addCircleFilled(foot[0], foot[1], 3, color, 12);
        }
        if (point != null) {
            var fill = blocked ? GeodataWidgets.BLOCKED : LightTool.color(tool.getNewColor());
            world.draw().addCircleFilled(point[0], point[1], 7.5f, HudWidgets.BACKGROUND, 16);
            world.draw().addCircleFilled(point[0], point[1], 6, fill, 16);
            world.draw().addCircle(point[0], point[1], 10, color, 20, 2);
        }
        if (blocked) {
            HudWidgets.showTooltip(ghostRestriction + ".");
        }
    }

    private static @Nullable Vector3 span(@Nullable Vector3 from, @Nullable Vector3 to, Vector3 middle, float near) {
        var a = from != null && length(minus(from, middle)) <= near ? from : null;
        var b = to != null && length(minus(to, middle)) <= near ? to : null;
        if (a != null && b != null) {
            return minus(b, a);
        }
        if (b != null) {
            return minus(b, middle);
        }
        return a == null ? null : minus(middle, a);
    }

    private static float aimLength(Camera camera, Vector3 at, int width, int height) {
        var view = camera.getViewMatrix();
        var right = normalize(new Vector3(view.get(0), view.get(4), view.get(8)));
        if (right == null) {
            right = new Vector3(1, 0, 0);
        }
        var probe = 100f;
        var a = EditorViewport.screenPoint(camera, at.x(), at.y(), at.z(), width, height);
        var b = EditorViewport.screenPoint(
                camera,
                at.x() + right.x() * probe,
                at.y() + right.y() * probe,
                at.z() + right.z() * probe,
                width,
                height);
        if (a == null || b == null) {
            return probe;
        }
        var pixels = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
        return pixels < 1e-3f ? probe : AIM_PIXELS * probe / pixels;
    }

    private static @Nullable Vector3 aimPoint(
            Camera camera, float x, float y, int width, int height, Vector3 center, float radius, boolean far) {
        var ndcX = x / width * 2 - 1;
        var ndcY = 1 - y / height * 2;
        var origin = camera.getCursorRayOrigin(ndcX, ndcY);
        var ray = normalize(toVector(camera.getCursorRayDirection(ndcX, ndcY)));
        if (ray == null) {
            return null;
        }
        var start = new Vector3(origin.getX(), origin.getY(), origin.getZ());
        var offset = minus(start, center);
        var b = dot(ray, offset);
        var c = dot(offset, offset) - radius * radius;
        var discriminant = b * b - c;
        float t;
        if (discriminant >= 0) {
            var root = (float) Math.sqrt(discriminant);
            t = far ? -b + root : -b - root;
        } else {
            t = -b;
        }
        var point = new Vector3(start.x() + ray.x() * t, start.y() + ray.y() * t, start.z() + ray.z() * t);
        if (discriminant >= 0) {
            return point;
        }
        var side = normalize(minus(point, center));
        return side == null
                ? null
                : new Vector3(
                        center.x() + side.x() * radius, center.y() + side.y() * radius, center.z() + side.z() * radius);
    }

    private static Vector3 toVector(Vec3 value) {
        return new Vector3(value.getX(), value.getY(), value.getZ());
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static Vector3 radiusHandle(Camera camera, LightRecord light) {
        if (light.shape().isSpot()) {
            var axis = normalize(light.shape().direction());
            if (axis != null) {
                return along(light.location(), axis, light.reach());
            }
        }
        var view = camera.getViewMatrix();
        var rx = view.get(0);
        var ry = view.get(4);
        var length = (float) Math.hypot(rx, ry);
        if (length < 1e-4f) {
            rx = 1;
            ry = 0;
            length = 1;
        }
        var at = light.location();
        return new Vector3(at.x() + rx / length * light.reach(), at.y() + ry / length * light.reach(), at.z());
    }

    private static void drawMark(GeoOverlayLines lines, Projector projector, PlacedMark mark) {
        var light = mark.light();
        var placed = mark.placed();
        var at = light.location();
        var big = mark.selected() || mark.hovered();
        var alpha = big ? 1f : projector.getAlpha(placed.distance(), MARK_FADE);
        var color = MarkerDepth.faded(LightTool.color(light.color()), alpha);
        var ppu = placed.pixelsPerUnit();
        var shape = light.shape();
        if (shape.isSpot()) {
            var direction = shape.direction();
            var size = (float) Math.sqrt(
                    direction.x() * direction.x() + direction.y() * direction.y() + direction.z() * direction.z());
            if (size > 1e-3f) {
                var length = (placed.dotDiameter() * (big ? 1.4f : 1f) * 1.5f + 6) / ppu / size;
                var from = new GeoPoint(at.x(), at.y(), at.z());
                var to = new GeoPoint(
                        at.x() + direction.x() * length,
                        at.y() + direction.y() * length,
                        at.z() + direction.z() * length);
                lines.addLine(from, to, 0, MarkerDepth.faded(HudWidgets.BACKGROUND, alpha), 4);
                lines.addLine(from, to, 0, color, 2);
            }
        }
        if (light.isChanged()) {
            MarkerDepth.ring(
                    lines,
                    at.x(),
                    at.y(),
                    at.z(),
                    ppu,
                    MarkerDepth.faded(GeodataWidgets.WARNING, alpha),
                    big,
                    big ? 2 : 1);
        }
        if (big) {
            MarkerDepth.ring(lines, at.x(), at.y(), at.z(), ppu, HudWidgets.ACCENT, true, 1);
        }
        MarkerDepth.mark(lines, at.x(), at.y(), at.z(), ppu, color, true, big);
    }

    private static void drawMark(
            ImDrawList draw,
            Camera camera,
            int width,
            int height,
            LightRecord light,
            boolean selected,
            boolean hovered) {
        var at = light.location();
        var point = EditorViewport.screenPoint(camera, at.x(), at.y(), at.z(), width, height);
        if (point == null) {
            return;
        }
        var color = LightTool.color(light.color());
        var shape = light.shape();
        if (shape.isSpot()) {
            var direction = shape.direction();
            var tip = EditorViewport.screenPoint(
                    camera,
                    at.x() + direction.x() * 64,
                    at.y() + direction.y() * 64,
                    at.z() + direction.z() * 64,
                    width,
                    height);
            if (tip != null) {
                var dx = tip[0] - point[0];
                var dy = tip[1] - point[1];
                var length = (float) Math.hypot(dx, dy);
                if (length > 1e-3f) {
                    var reach = selected ? 22 : 16;
                    draw.addLine(
                            point[0],
                            point[1],
                            point[0] + dx / length * reach,
                            point[1] + dy / length * reach,
                            HudWidgets.BACKGROUND,
                            4);
                    draw.addLine(
                            point[0],
                            point[1],
                            point[0] + dx / length * reach,
                            point[1] + dy / length * reach,
                            color,
                            2);
                }
            }
        }
        var radius = selected ? 6f : 4.5f;
        draw.addCircleFilled(point[0], point[1], radius + 1.5f, HudWidgets.BACKGROUND, 16);
        draw.addCircleFilled(point[0], point[1], radius, color, 16);
        if (selected || hovered) {
            draw.addCircle(point[0], point[1], radius + 4, HudWidgets.ACCENT, 20, selected ? 2 : 1.5f);
        }
        if (light.isChanged()) {
            draw.addCircleFilled(point[0] + radius + 1, point[1] - radius - 1, 2.5f, GeodataWidgets.WARNING, 8);
        }
    }

    private static void drawOutline(World world, Vector3 at, float reach, int color) {
        var camera = world.camera();
        if (camera.isOrtho()) {
            var forward = camera.getCursorRayDirection(0, 0);
            var axis = new Vector3(forward.getX(), forward.getY(), forward.getZ());
            var basis = basis(axis);
            world.ring(at, basis[0], basis[1], reach, color, 1.5f);
            return;
        }
        var eye = camera.getPosition();
        var toEye = new Vector3(eye.getX() - at.x(), eye.getY() - at.y(), eye.getZ() - at.z());
        var distance = length(toEye);
        if (!(distance > reach)) {
            return;
        }
        var ratio = reach * reach / (distance * distance);
        var center = new Vector3(at.x() + toEye.x() * ratio, at.y() + toEye.y() * ratio, at.z() + toEye.z() * ratio);
        var basis = basis(toEye);
        world.ring(center, basis[0], basis[1], reach * (float) Math.sqrt(1 - ratio), color, 1.5f);
    }

    private static void drawCone(
            World world, Vector3 at, LightShape shape, float reach, @Nullable Plane end, int color) {
        var axis = normalize(shape.direction());
        if (axis == null) {
            return;
        }
        var basis = basis(axis);
        var outer = Math.toRadians(shape.coneDegrees());
        if (!world.surfaces()) {
            world.path(rim(world, at, axis, basis, outer, reach, end), color, 2);
            world.path(rim(world, at, axis, basis, innerAngle(shape), reach, end), alpha(color, .55f), 1.5f);
        }
        for (var turn : silhouette(world.camera(), at, axis, basis, outer)) {
            var ray = generator(axis, basis, outer, turn);
            world.stroke(at, along(at, ray, reachOf(at, ray, reach, end)), color, 2);
        }
        var length = reachOf(at, axis, reach, end);
        var tip = along(at, axis, length);
        world.stroke(at, tip, color, 2);
        if (length < reach && end != null) {
            drawArrowHead(world, at, tip, color);
            var plane = basis(end.normal());
            var mark = Math.clamp(reach * .04f, 6, 32);
            world.ring(tip, plane[0], plane[1], mark, color, 2);
            world.line(along(tip, plane[0], -mark * 1.4f), along(tip, plane[0], mark * 1.4f), color, 1.5f);
            world.line(along(tip, plane[1], -mark * 1.4f), along(tip, plane[1], mark * 1.4f), color, 1.5f);
        } else {
            drawArrowHead(world, at, along(at, axis, length * .85f), color);
        }
    }

    private static void drawArrowHead(World world, Vector3 from, Vector3 tip, int color) {
        var backward = normalize(minus(from, tip));
        if (backward == null) {
            return;
        }
        var back = along(tip, backward, Math.min(length(minus(from, tip)), 16));
        var p = EditorViewport.screenPoint(world.camera(), tip.x(), tip.y(), tip.z(), world.width(), world.height());
        var q = EditorViewport.screenPoint(world.camera(), back.x(), back.y(), back.z(), world.width(), world.height());
        if (p == null || q == null) {
            return;
        }
        var dx = p[0] - q[0];
        var dy = p[1] - q[1];
        var size = (float) Math.hypot(dx, dy);
        if (size < 1e-3f) {
            return;
        }
        dx /= size;
        dy /= size;
        var draw = world.draw();
        for (var pass = 0; pass < 2; pass++) {
            var grow = pass == 0 ? 2f : 0f;
            var head = 11 + grow;
            var wide = 6 + grow;
            var bx = p[0] + dx * grow - dx * head;
            var by = p[1] + dy * grow - dy * head;
            draw.addTriangleFilled(
                    p[0] + dx * grow,
                    p[1] + dy * grow,
                    bx - dy * wide,
                    by + dx * wide,
                    bx + dy * wide,
                    by - dx * wide,
                    pass == 0 ? HudWidgets.BACKGROUND : color);
        }
    }

    private static Vector3 generator(Vector3 axis, Vector3[] basis, double angle, double turn) {
        var sine = (float) Math.sin(angle);
        var cosine = (float) Math.cos(angle);
        var c = (float) Math.cos(turn) * sine;
        var s = (float) Math.sin(turn) * sine;
        return new Vector3(
                axis.x() * cosine + basis[0].x() * c + basis[1].x() * s,
                axis.y() * cosine + basis[0].y() * c + basis[1].y() * s,
                axis.z() * cosine + basis[0].z() * c + basis[1].z() * s);
    }

    private static float reachOf(Vector3 at, Vector3 ray, float reach, @Nullable Plane end) {
        return end == null ? reach : Math.min(reach, end.along(at, ray));
    }

    private static List<Vector3> rim(
            World world, Vector3 at, Vector3 axis, Vector3[] basis, double angle, float reach, @Nullable Plane end) {
        var across = (float) Math.sin(angle) * reach;
        var segments = world.segments(along(at, axis, (float) Math.cos(angle) * reach), basis[0], basis[1], across);
        var points = new ArrayList<Vector3>(segments + 1);
        for (var i = 0; i <= segments; i++) {
            var ray = generator(axis, basis, angle, Math.PI * 2 * i / segments);
            points.add(along(at, ray, reachOf(at, ray, reach, end)));
        }
        return points;
    }

    private static double[] silhouette(Camera camera, Vector3 at, Vector3 axis, Vector3[] basis, double angle) {
        Vector3 toEye;
        if (camera.isOrtho()) {
            var forward = camera.getCursorRayDirection(0, 0);
            toEye = new Vector3(-forward.getX(), -forward.getY(), -forward.getZ());
        } else {
            var eye = camera.getPosition();
            toEye = new Vector3(eye.getX() - at.x(), eye.getY() - at.y(), eye.getZ() - at.z());
        }
        return silhouette(toEye, axis, basis, angle);
    }

    private static void drawPool(World world, Vector3 at, float reach, float floor, int color, float thickness) {
        var height = at.z() - floor;
        var flat = (float) Math.sqrt(Math.max(0, reach * reach - height * height));
        world.ring(
                new Vector3(at.x(), at.y(), floor), new Vector3(1, 0, 0), new Vector3(0, 1, 0), flat, color, thickness);
    }

    record Plane(Vector3 point, Vector3 normal) {
        float along(Vector3 from, Vector3 ray) {
            var toward = dot(ray, normal);
            if (toward > -1e-4f) {
                return Float.POSITIVE_INFINITY;
            }
            var t = dot(minus(point, from), normal) / toward;
            return t >= 0 ? t : Float.POSITIVE_INFINITY;
        }
    }

    private static float sq(float value) {
        return value * value;
    }

    private static Vector3 minus(Vector3 a, Vector3 b) {
        return new Vector3(a.x() - b.x(), a.y() - b.y(), a.z() - b.z());
    }

    private static Vector3 scale(Vector3 a, float factor) {
        return new Vector3(a.x() * factor, a.y() * factor, a.z() * factor);
    }

    private static Vector3 along(Vector3 from, Vector3 ray, float distance) {
        return new Vector3(from.x() + ray.x() * distance, from.y() + ray.y() * distance, from.z() + ray.z() * distance);
    }

    private static float dot(Vector3 a, Vector3 b) {
        return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
    }

    private static Vector3 cross(Vector3 a, Vector3 b) {
        return new Vector3(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
    }

    private static float length(Vector3 a) {
        return (float) Math.sqrt(dot(a, a));
    }

    private static @Nullable Vector3 normalize(Vector3 a) {
        var length = length(a);
        return length > 1e-6f ? scale(a, 1 / length) : null;
    }

    private static Vector3[] basis(Vector3 axis) {
        var unit = normalize(axis);
        if (unit == null) {
            unit = new Vector3(0, 0, 1);
        }
        var helper = Math.abs(unit.z()) < .9f ? new Vector3(0, 0, 1) : new Vector3(1, 0, 0);
        var first = normalize(cross(unit, helper));
        var second = cross(unit, first);
        return new Vector3[] {first, second};
    }
}
