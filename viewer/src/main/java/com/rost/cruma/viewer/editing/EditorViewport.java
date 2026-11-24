package com.rost.cruma.viewer.editing;

import static com.rost.cruma.viewer.editing.EditingPanel.COMMAND_KEY;

import com.rost.cruma.core.editing.ActorTransform;
import com.rost.cruma.core.editing.ActorTransform.Rotator;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.editing.BspSurfaceMapping;
import com.rost.cruma.core.editing.PackageEditDocument;
import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.viewer.editing.CameraOrbit.Pose;
import com.rost.cruma.viewer.editing.ClickCycle.Hit;
import com.rost.cruma.viewer.editing.EditingPanel.Drop;
import com.rost.cruma.viewer.editing.EditingPanel.LibraryDrag;
import com.rost.cruma.viewer.editing.EditingPanel.Tool;
import com.rost.cruma.viewer.editing.EditingSession.Outline;
import com.rost.cruma.viewer.editing.EditingSession.SurfaceRef;
import com.rost.cruma.viewer.editing.Placing.Stroke;
import com.rost.cruma.viewer.geodata.GeoOverlayLines;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.EditorEffectStage;
import com.rost.cruma.viewer.render.LightGlows;
import com.rost.cruma.viewer.render.api.DebugSceneObject.Category;
import com.rost.cruma.viewer.render.api.DebugSelection;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import com.rost.cruma.viewer.spawn.MapPawnStage;
import com.rost.cruma.viewer.ui.GeodataWidgets;
import com.rost.cruma.viewer.ui.HudWidgets;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import glm_.vec2.Vec2;
import glm_.vec3.Vec3;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

public final class EditorViewport {
    private static final int ACCENT = HudWidgets.ACCENT;
    private static final int[][] EDGES = {
        {0, 1},
        {0, 2},
        {0, 4},
        {1, 3},
        {1, 5},
        {2, 3},
        {2, 6},
        {3, 7},
        {4, 5},
        {4, 6},
        {5, 7},
        {6, 7}
    };
    private static final float CLICK_PIXELS = 4;
    private static final long MENU_NANOS = 300_000_000L;
    private static final int ANSWER_FRAMES = 40;
    private static final float FAR = 1_000_000f;
    private static final float WIREFRAME_WIDTH = 1.5f;
    private static final int WORLD_OUTLINE_EDGES = 8_192;
    private static final float ORBIT_AHEAD = 1024;
    private static final float LIBRARY_PICTURE = 48;
    private static final String MENU = "##editingContextMenu";
    private static final float MENU_WIDTH = 272;

    private @Nullable GeoOverlayLines worldLines;
    private @Nullable GeoOverlayLines worldEdges;
    private @Nullable LightGlows lightGlow;
    private final EditingSession session;
    private final ViewerRenderBackend backend;
    private final EditingPanel panel;
    private Map<String, Actor> actors = new LinkedHashMap<>();
    private final Set<String> effects = new HashSet<>();
    private EffectMarks.@Nullable Grid markGrid;
    private long markGridVersion = -1;
    private long actorsVersion;
    private long sceneRevision = Long.MIN_VALUE;
    private long topologyRevision = Long.MIN_VALUE;
    private long selectionRevision = Long.MIN_VALUE;
    private Set<String> residentMaps = Set.of();
    private boolean pressed;
    private boolean rectangle;
    private boolean rectangleAllowed;
    private Mode pressMode = Mode.REPLACE;
    private boolean pressTwice;
    private boolean pressThrough;
    private @Nullable String pressVisible;
    private boolean pressClicks = true;
    private float startX, startY;
    private @Nullable Click click;
    private boolean orbiting;
    private Pose orbitStart;
    private float @Nullable [] orbitCenter;
    private @Nullable PointsJob orbitJob;
    private boolean surfaceDrag;
    private float surfaceBaseX, surfaceBaseY, surfaceBaseZ;
    private float surfaceZ;
    private float lastOffsetX = Float.NaN, lastOffsetY, lastOffsetZ;
    private @Nullable Bounds3f surfaceBounds;
    private List<float[]> snapEdges = List.of();
    private boolean measuring;
    private boolean measureShown;
    private float @Nullable [] measureFrom;
    private float @Nullable [] measureTo;
    private float[] measureNdc = new float[4];
    private int measureFrames;
    private boolean measureKeyUsed;
    private boolean rightPressed;
    private float rightX, rightY;
    private final LightGestures lights;
    private long rightAt;
    private float menuX, menuY;
    private @Nullable String menuActor;
    private String menuMesh = "";
    private String menuMaterial = "";
    private float @Nullable [] menuPoint;
    final Map<String, float[]> menuRows = new HashMap<>();
    private @Nullable String menuLight;
    private @Nullable DebugSelection hovered;
    private final ClickCycle cycle = new ClickCycle();
    private int behindFrame = -1;
    private @Nullable String behindActor;
    private int behindCount;
    private @Nullable String highlighted;
    private @Nullable PointsJob rectangleJob;
    private @Nullable PointsJob dropJob;
    private String rectangleNote = "";
    private @Nullable PointsJob normalJob;
    private float normalX = Float.NaN;
    private float normalY = Float.NaN;
    private float @Nullable [] pointerNormal;
    private boolean scattering;
    private boolean scatterErase;
    private Stroke stroke;
    private final List<float[]> scatterPoints = new ArrayList<>();
    private final List<float @Nullable []> scatterNormals = new ArrayList<>();
    private final LinkedHashSet<String> eraseTargets = new LinkedHashSet<>();
    private @Nullable PointsJob scatterJob;
    private long scatterSeed = 11;
    private float menuPasteX = Float.NaN;
    private float menuPasteY;
    private Drop drop;
    private int dropFrames;
    private float @Nullable [] surfaceClick;
    private boolean surfaceClickAdds;
    private int surfaceClickFrames;
    private @Nullable List<SurfaceRef> outlineChoice;
    private long outlineRevision = Long.MIN_VALUE;
    private Outline outline = Outline.NONE;
    private List<float[]> activePolygons = List.of();
    private List<SurfaceRef> pointedChoice = List.of();
    private long pointedRevision = Long.MIN_VALUE;
    private Outline pointedOutline = Outline.NONE;
    private @Nullable List<SurfaceRef> wireframeChoice;
    private long wireframeRevision = Long.MIN_VALUE;
    private Outline wireframe = Outline.NONE;
    private float[] outlineScreen = new float[0];
    private byte[] outlineCodes = new byte[0];
    private final float[] outlineMatrix = new float[16];
    private final Map<String, float @Nullable []> localBounds = new HashMap<>();
    private Map<String, Integer> meshCounts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public EditorViewport(EditingSession session, ViewerRenderBackend backend, EditingPanel panel) {
        this.session = session;
        this.backend = backend;
        this.panel = panel;
        lights = new LightGestures(session, backend, panel);
        panel.setViewport(this);
    }

    public enum Mode {
        REPLACE,
        TOGGLE,
        ADD
    }

    public record Click(Mode mode, boolean twice, @Nullable String next) {
        public Click(Mode mode, boolean twice) {
            this(mode, twice, null);
        }
    }

    record Actor(Bounds3f bounds, String mesh, Set<String> materials, boolean moving) {
        Actor(Bounds3f bounds, String mesh, Set<String> materials) {
            this(bounds, mesh, materials, false);
        }
    }

    private static final class PointsJob {
        final float[] ndc;
        final List<String> owners;
        int frames;
        boolean additive;
        boolean second;
        boolean slope;
        List<float[]> stamps = List.of();
        int[] indices = new int[0];

        PointsJob(float[] ndc, List<String> owners) {
            this.ndc = ndc;
            this.owners = owners;
        }
    }

    static float @Nullable [] groundPoint(Camera camera, float x, float y, int width, int height, float z) {
        var nx = x / width * 2 - 1;
        var ny = 1 - y / height * 2;
        var origin = camera.getCursorRayOrigin(nx, ny);
        var direction = camera.getCursorRayDirection(nx, ny);
        if (Math.abs(direction.getZ()) < 1e-4f) {
            return null;
        }
        var t = (z - origin.getZ()) / direction.getZ();
        if (!(t > 0) || t > 1_000_000) {
            return null;
        }
        return new float[] {origin.getX() + direction.getX() * t, origin.getY() + direction.getY() * t};
    }

    public static boolean intersects(
            Camera camera, Bounds3f bounds, int width, int height, float x1, float y1, float x2, float y2) {
        var rect = projectedRect(camera, bounds, width, height);
        return rect != null
                && rect[2] >= Math.min(x1, x2)
                && rect[0] <= Math.max(x1, x2)
                && rect[3] >= Math.min(y1, y2)
                && rect[1] <= Math.max(y1, y2);
    }

    static boolean isPawnSeen(float @Nullable [] box, float depth) {
        return box != null && (!Float.isFinite(depth) || box[0] <= depth + 2 + depth * .002f);
    }

    static boolean isPawnInReach(Vec3 eye, boolean ortho, Bounds3f bounds) {
        if (ortho) {
            return true;
        }
        var dx = (bounds.minX() + bounds.maxX()) / 2 - eye.getX();
        var dy = (bounds.minY() + bounds.maxY()) / 2 - eye.getY();
        var dz = (bounds.minZ() + bounds.maxZ()) / 2 - eye.getZ();
        return (double) dx * dx + (double) dy * dy + (double) dz * dz
                <= (double) MapPawnStage.REACH * MapPawnStage.REACH;
    }

    static boolean doesRectangleAdd(Mode mode) {
        return mode != Mode.REPLACE;
    }

    static float @Nullable [] screenPoint(Camera camera, float x, float y, float z, int width, int height) {
        var point = project(camera, x, y, z, width, height);
        return point == null ? null : new float[] {point.getX(), point.getY()};
    }

    static float @Nullable [] screenPoint(Camera camera, Vector3 point, int width, int height) {
        return screenPoint(camera, point.x(), point.y(), point.z(), width, height);
    }

    public void setWorldLines(@Nullable GeoOverlayLines lines) {
        worldLines = lines;
    }

    public void setWorldEdges(@Nullable GeoOverlayLines edges) {
        worldEdges = edges;
    }

    public void setLightGlow(@Nullable LightGlows glow) {
        lightGlow = glow;
    }

    public void setResidentMaps(Set<String> maps) {
        residentMaps = maps;
    }

    public void hover(@Nullable DebugSelection selection) {
        hovered = selection;
    }

    public void highlight(@Nullable String actor) {
        highlighted = actor;
    }

    public @Nullable Click consumeClick() {
        var result = click;
        click = null;
        return result;
    }

    public boolean draw(Camera camera, float left, float top, float right, float bottom) {
        if (session.isExportLocked()) {
            cancelGesture();
            return false;
        }
        var io = ImGui.getIO();
        var x = ImGui.getMousePosX();
        var y = ImGui.getMousePosY();
        var inside = x >= left && x <= right && y >= top && y <= bottom;
        var width = (int) io.getDisplaySizeX();
        var height = (int) io.getDisplaySizeY();
        if (session.getSelection().isEmpty() && panel.isSurfaceSnapArmed()) {
            panel.toggleSurfaceSnap();
        }
        if (session.getSelection().isEmpty() || panel.getTool() != Tool.SELECT) {
            panel.disarmFacePoint();
        }
        if (panel.getTool() != Tool.SURFACES) {
            surfaceClick = null;
        }
        refresh();
        updateRectangleJob(camera);
        if (measureShown && !measuring && inside && ImGui.isMouseClicked(0) && !io.getWantCaptureMouse()) {
            measureShown = false;
        }
        settleMeasure(camera);
        var drop = panel.consumeDropRequest();
        if (drop > 0) {
            startDrop(camera, drop == 2, left, top, right, bottom);
        }
        updateDropJob(camera, left, top, right, bottom);
        updateScatterJob(camera);
        resolveDrop(width, height);
        if (panel.consumePasteRequest()) {
            paste(camera, x, y, inside, width, height);
        }
        var look = camera.getCursorRayDirection(0, 0);
        panel.setCameraLook(new Vector3(look.getX(), look.getY(), look.getZ()));
        var eye = camera.getPosition();
        panel.setCameraEye(new Vector3(eye.getX(), eye.getY(), eye.getZ()));
        var dragged = panel.getLibraryDrag();
        if (dragged != null) {
            var over = dragFromLibrary(camera, dragged, x, y, width, height, inside, left, top, right, bottom);
            drawMarks(camera, left, top, right, bottom);
            return over;
        }
        if (handleEyedropper(camera, x, y, width, height, inside)) {
            return true;
        }
        var capture = contextMenu(camera, inside, left, top, right, bottom);
        var tool = panel.getTool();
        var gizmoDrawn = false;
        if (capture) {
        } else if (pressed && tool != Tool.SELECT) {
            capture = updatePress(camera, x, y, left, top, right, bottom);
        } else if (panel.isSurfaceSnapArmed()) {
            capture = true;
            if (inside) {
                ImGui.setTooltip("Click a visible surface to place the selection pivot. Esc cancels.");
                var nx = x / width * 2 - 1;
                var ny = 1 - y / height * 2;
                var hit = backend.pickDebugSceneObject(nx, ny, Float.MAX_VALUE, EnumSet.allOf(Category.class));
                if (hit != null && ImGui.isMouseClicked(0) && !io.getWantCaptureMouse()) {
                    try {
                        if (session.getSelection().contains(hit.object().actorName())) {
                            throw new IllegalArgumentException("Choose a visible surface outside the selected actors.");
                        }
                        var origin = camera.getCursorRayOrigin(nx, ny);
                        var direction = camera.getCursorRayDirection(nx, ny);
                        var point = new Vector3(
                                origin.getX() + direction.getX() * hit.distance(),
                                origin.getY() + direction.getY() * hit.distance(),
                                origin.getZ() + direction.getZ() * hit.distance());
                        panel.toggleSurfaceSnap();
                        panel.move(() -> session.snapPivotTo(point), null);
                    } catch (RuntimeException failure) {
                        panel.showError(failure.getMessage());
                    }
                }
            }
        } else if (panel.isFacePointArmed()) {
            capture = true;
            if (inside) {
                var nx = x / width * 2 - 1;
                var ny = 1 - y / height * 2;
                var hit = backend.pickDebugSceneObject(nx, ny, Float.MAX_VALUE, EnumSet.allOf(Category.class));
                if (hit != null && ImGui.isMouseClicked(0) && !io.getWantCaptureMouse()) {
                    var origin = camera.getCursorRayOrigin(nx, ny);
                    var direction = camera.getCursorRayDirection(nx, ny);
                    panel.disarmFacePoint();
                    panel.faceTowards(
                            origin.getX() + direction.getX() * hit.distance(),
                            origin.getY() + direction.getY() * hit.distance());
                }
            }
        } else if (scattering
                || panel.isPlacing()
                || !pressed && panel.isPlaceToolActive() && (io.getKeyCtrl() || io.getKeySuper())) {
            capture = place(camera, x, y, width, height, inside, left, top, right, bottom);
        } else if (surfaceDrag) {
            capture = true;
            updateSurfaceDrag(camera, x, y, width, height);
        } else if (measuring) {
            capture = true;
            updateMeasure(camera, x, y, width, height);
        } else if (pressed) {
            capture = updatePress(camera, x, y, left, top, right, bottom);
        } else if (tool == Tool.LIGHTS) {
            capture = lights.update(
                    camera, x, y, width, height, inside, io.getWantCaptureMouse(), left, top, right, bottom);
            if (!capture
                    && inside
                    && ImGui.isMouseClicked(0)
                    && !io.getWantCaptureMouse()
                    && !panel.getLights().isPlacing()) {
                startPress(x, y, true, false);
                capture = true;
            }
        } else if (tool == Tool.SURFACES) {
            capture = pickSurface(camera, x, y, width, height, inside, io.getWantCaptureMouse());
        } else if (tool != null) {
            if (tool == Tool.SELECT) {
                if (measureKeyDown()
                        && inside
                        && ImGui.isMouseClicked(0)
                        && !ImGui.isWindowHovered(ImGuiHoveredFlags.AnyWindow)) {
                    startMeasure(camera, x, y, width, height);
                    capture = true;
                } else if (panel.isSurfaceDragOn()
                        && panel.isGizmoHovered()
                        && inside
                        && ImGui.isMouseClicked(0)
                        && !ImGui.isWindowHovered(ImGuiHoveredFlags.AnyWindow)
                        && startSurfaceDrag()) {
                    capture = true;
                } else {
                    panel.drawGizmo(camera, left, top, right, bottom, true);
                    gizmoDrawn = true;
                    capture = panel.capturesMouse();
                }
            }
            if (!capture && inside && ImGui.isMouseClicked(0) && !io.getWantCaptureMouse()) {
                if (tool == Tool.SELECT && ImGui.isKeyDown(ImGuiKey.V) && startSurfaceDrag()) {
                    capture = true;
                } else {
                    startPress(x, y, tool == Tool.SELECT, true);
                    capture = true;
                }
            }
        }
        if (tool == Tool.SELECT && !gizmoDrawn && !panel.isSurfaceSnapArmed()) {
            panel.drawGizmo(camera, left, top, right, bottom, false);
        }
        drawMarks(camera, left, top, right, bottom);
        return capture;
    }

    public void drawMarks(Camera camera, float left, float top, float right, float bottom) {
        refresh();
        var io = ImGui.getIO();
        var width = (int) io.getDisplaySizeX();
        var height = (int) io.getDisplaySizeY();
        var draw = ImGui.getBackgroundDrawList();
        draw.pushClipRect(left, top, right, bottom, true);
        for (var path : panel.getWarningActors()) {
            var actor = actors.get(path);
            if (actor != null && !session.getSelection().contains(path)) {
                worldBox(draw, camera, actor.bounds(), width, height, GeodataWidgets.WARNING, 1.5f);
            }
        }
        for (var path : panel.getGhostActors()) {
            drawGhost(draw, camera, path, width, height);
        }
        for (var path : session.getSelection()) {
            var actor = actors.get(path);
            if (actor != null && !effects.contains(path)) {
                worldBox(draw, camera, actor.bounds(), width, height, ACCENT, 2);
            }
        }
        if (panel.getTool() == Tool.SELECT && !effects.isEmpty()) {
            var marks = getEffectMarks(camera);
            var pointer = !ImGui.getIO().getWantCaptureMouse()
                    ? EffectMarks.markAt(camera, ImGui.getMousePosX(), ImGui.getMousePosY(), width, height, marks)
                    : null;
            EffectMarks.draw(
                    worldLines,
                    draw,
                    camera,
                    width,
                    height,
                    marks,
                    session.getSelection(),
                    pointer == null ? null : pointer.path());
        }
        if (highlighted != null && !session.getSelection().contains(highlighted)) {
            drawHover(draw, camera, highlighted);
        }
        highlighted = null;
        lights.draw(
                draw, camera, width, height, left, top, right, bottom, worldLines, lightGlow, actors, actorsVersion);
        drawSurfaceMarks(draw, camera, width, height);
        drawSnapEdges(draw, camera, width, height);
        if (panel.getTool() != Tool.SELECT) {
            measureShown = false;
            measuring = false;
        } else if (measureShown) {
            drawMeasure(draw, camera, width, height);
        }
        draw.popClipRect();
    }

    List<EffectMarks.Effect> getEffectMarks(Camera camera) {
        if (markGrid == null || markGridVersion != actorsVersion) {
            var all = new ArrayList<EffectMarks.Effect>(effects.size());
            for (var path : effects) {
                var actor = actors.get(path);
                if (actor != null) {
                    all.add(new EffectMarks.Effect(path, actor.bounds()));
                }
            }
            markGrid = new EffectMarks.Grid(all);
            markGridVersion = actorsVersion;
        }
        var at = camera.getPosition();
        var hidden = session.getHiddenActors();
        var marks = new ArrayList<EffectMarks.Effect>();
        for (var mark : markGrid.getNear(at.getX(), at.getY(), EffectMarks.REACH)) {
            if (!hidden.contains(mark.path())) {
                marks.add(mark);
            }
        }
        return marks;
    }

    Vector3 snapToNeighbors(Bounds3f start, Vector3 offset, boolean[] axes) {
        var moved = new Bounds3f(
                start.minX() + offset.x(),
                start.minY() + offset.y(),
                start.minZ() + offset.z(),
                start.maxX() + offset.x(),
                start.maxY() + offset.y(),
                start.maxZ() + offset.z());
        var neighbors = new ArrayList<Bounds3f>();
        var selection = session.getSelection();
        var hidden = session.getHiddenActors();
        var isolated = panel.getIsolatedActors();
        for (var entry : actors.entrySet()) {
            var path = entry.getKey();
            var bounds = measured(path, entry.getValue());
            if (!selection.contains(path)
                    && !hidden.contains(path)
                    && IsolatedGroup.inView(isolated, path)
                    && BoundsSnap.isNear(moved, bounds)) {
                neighbors.add(bounds);
            }
        }
        var snap = BoundsSnap.snap(moved, neighbors, axes);
        snapEdges = snap.edges();
        return snap.snapped()
                ? new Vector3(offset.x() + snap.dx(), offset.y() + snap.dy(), offset.z() + snap.dz())
                : offset;
    }

    void clearSnapEdges() {
        snapEdges = List.of();
    }

    public boolean isMeasuring() {
        return measuring || panel.getTool() == Tool.SELECT && measureKeyDown();
    }

    public boolean consumeMeasureKey() {
        var used = measureKeyUsed;
        measureKeyUsed = false;
        return used;
    }

    float @Nullable [][] getMeasurePoints() {
        return measureShown && measureFrom != null && measureTo != null
                ? new float[][] {measureFrom.clone(), measureTo.clone()}
                : null;
    }

    public void drawHover(ImDrawList draw, Camera camera, String actor) {
        var found = actors.get(actor);
        if (found == null) {
            return;
        }
        var io = ImGui.getIO();
        worldBox(
                draw,
                camera,
                found.bounds(),
                (int) io.getDisplaySizeX(),
                (int) io.getDisplaySizeY(),
                EditorUi.fill(ACCENT, .4f),
                1);
    }

    public long getActorsVersion() {
        return actorsVersion;
    }

    public @Nullable Bounds3f getActorBounds(String path) {
        var actor = actors.get(path);
        return actor == null ? null : measured(path, actor);
    }

    Map<String, Bounds3f> getCurrentBounds(Set<String> paths) {
        var result = new LinkedHashMap<String, Bounds3f>();
        var moving = new HashSet<String>();
        for (var object : backend.getEditorActorObjects(paths)) {
            result.merge(object.actorName(), object.bounds(), EditorViewport::union);
            if (object.actorAnimated()) {
                moving.add(object.actorName());
            }
        }
        for (var path : moving) {
            var saved = backend.getSavedActorBounds(path);
            if (saved != null) {
                result.put(path, saved);
            }
        }
        return result;
    }

    public String getActorMesh(String path) {
        var actor = actors.get(path);
        return actor == null ? "" : actor.mesh();
    }

    public List<String> findActorsIn(List<float[]> rects) {
        var result = new ArrayList<String>();
        if (rects.isEmpty()) {
            return result;
        }
        actors.forEach((path, actor) -> {
            var b = actor.bounds();
            for (var rect : rects) {
                if (b.maxX() >= rect[0] && b.minX() <= rect[2] && b.maxY() >= rect[1] && b.minY() <= rect[3]) {
                    result.add(path);
                    return;
                }
            }
        });
        return result;
    }

    public Set<String> getSceneActors() {
        return actors.keySet();
    }

    public Set<String> getLoadedMaps() {
        var result = new HashSet<>(residentMaps);
        for (var actor : actors.keySet()) {
            var dot = actor.indexOf('.');
            if (dot > 0) {
                result.add(actor.substring(0, dot));
            }
        }
        return result;
    }

    public Map<String, Integer> getMeshCounts() {
        return meshCounts;
    }

    public List<String> findActorsWithMesh(String mesh) {
        var result = new ArrayList<String>();
        actors.forEach((path, actor) -> {
            if (actor.mesh().equalsIgnoreCase(mesh)) {
                result.add(path);
            }
        });
        return result;
    }

    public float @Nullable [] getLocalBounds(String mesh) {
        if (localBounds.containsKey(mesh)) {
            return localBounds.get(mesh);
        }
        float[] found = null;
        var checked = 0;
        for (var entry : actors.entrySet()) {
            if (!entry.getValue().mesh().equalsIgnoreCase(mesh) || checked++ >= 16) {
                continue;
            }
            try {
                var t = session.getActor(entry.getKey()).transform();
                var b = measured(entry.getKey(), entry.getValue());
                var p = t.location();
                var s3 = t.drawScale3d();
                var sx = t.drawScale() * s3.x();
                var sy = t.drawScale() * s3.y();
                var sz = t.drawScale() * s3.z();
                var plain = t.rotation().pitch() == 0
                        && t.rotation().yaw() == 0
                        && t.rotation().roll() == 0
                        && sx > 0
                        && sy > 0
                        && sz > 0;
                if (!plain && found != null) {
                    continue;
                }
                found = plain
                        ? new float[] {
                            (b.minX() - p.x()) / sx,
                            (b.minY() - p.y()) / sy,
                            (b.minZ() - p.z()) / sz,
                            (b.maxX() - p.x()) / sx,
                            (b.maxY() - p.y()) / sy,
                            (b.maxZ() - p.z()) / sz
                        }
                        : new float[] {
                            b.minX() - p.x(),
                            b.minY() - p.y(),
                            b.minZ() - p.z(),
                            b.maxX() - p.x(),
                            b.maxY() - p.y(),
                            b.maxZ() - p.z()
                        };
                if (plain) {
                    break;
                }
            } catch (RuntimeException unreadable) {
            }
        }
        localBounds.put(mesh, found);
        return found;
    }

    public @Nullable Float getSelectionBottom() {
        Float bottom = null;
        for (var path : session.getSelection()) {
            var actor = actors.get(path);
            if (actor != null) {
                var low = measured(path, actor).minZ();
                bottom = bottom == null ? low : Math.min(bottom, low);
            }
        }
        return bottom;
    }

    public List<String> getSceneSlots(String actor) {
        var result = new ArrayList<String>();
        for (var object : backend.getEditorActorObjects(Set.of(actor))) {
            var slot = object.surfaceOrdinal();
            while (result.size() <= slot) {
                result.add("");
            }
            result.set(slot, object.materialName());
        }
        return result;
    }

    public boolean isScattering() {
        return scattering;
    }

    public boolean cancelGesture() {
        if (lights.cancel()) {
            return true;
        }
        if (measuring || measureShown) {
            measuring = false;
            measureShown = false;
            return true;
        }
        var cancelled =
                pressed || rectangleJob != null || dropJob != null || surfaceDrag || scattering || scatterJob != null;
        pressed = false;
        rectangle = false;
        orbiting = false;
        orbitJob = null;
        rectangleJob = null;
        dropJob = null;
        scattering = false;
        scatterJob = null;
        surfaceClick = null;
        scatterPoints.clear();
        scatterNormals.clear();
        eraseTargets.clear();
        if (surfaceDrag) {
            surfaceDrag = false;
            try {
                session.cancelGesture();
            } catch (RuntimeException locked) {
            } finally {
                session.endStep(null);
            }
        }
        return cancelled;
    }

    void clickSurface(float ndcX, float ndcY, boolean add) {
        surfaceClick = new float[] {ndcX, ndcY};
        surfaceClickAdds = add;
        surfaceClickFrames = 0;
    }

    boolean resolveSurfaceClick(Camera camera) {
        var at = surfaceClick;
        if (at == null) {
            return false;
        }
        var categories = EnumSet.of(Category.CSG);
        var hit = backend.pickDebugSceneObject(at[0], at[1], Float.MAX_VALUE, categories);
        if (hit == null
                && !backend.isScenePickReady(at[0], at[1], Float.MAX_VALUE, categories)
                && ++surfaceClickFrames <= ANSWER_FRAMES) {
            return true;
        }
        surfaceClick = null;
        var add = surfaceClickAdds;
        if (hit == null) {
            panel.pickSurface(null, add);
            return true;
        }
        var origin = camera.getCursorRayOrigin(at[0], at[1]);
        var direction = camera.getCursorRayDirection(at[0], at[1]);
        var point = new Vector3(
                origin.getX() + direction.getX() * hit.distance(),
                origin.getY() + direction.getY() * hit.distance(),
                origin.getZ() + direction.getZ() * hit.distance());
        try {
            var surface = session.pickSurface(hit.object().owner(), point);
            if (surface == null) {
                panel.showError("No BSP surface of " + hit.object().owner() + " is under the pointer.");
            }
            panel.pickSurface(surface, add);
        } catch (RuntimeException failure) {
            panel.showError(failure.getMessage());
        }
        return true;
    }

    public boolean isSurfaceDragging() {
        return surfaceDrag;
    }

    public boolean isRectangleActive() {
        return pressed && rectangle;
    }

    public String getRectangleNote() {
        return rectangleNote;
    }

    public int countObjectsBehind(Camera camera, @Nullable String actor) {
        var frame = ImGui.getFrameCount();
        if (frame == behindFrame && Objects.equals(actor, behindActor)) {
            return behindCount;
        }
        behindFrame = frame;
        behindActor = actor;
        behindCount = 0;
        if (actor == null || panel.getTool() != Tool.SELECT || !actors.containsKey(actor)) {
            return 0;
        }
        var io = ImGui.getIO();
        behindCount = stack(
                                camera,
                                ImGui.getMousePosX(),
                                ImGui.getMousePosY(),
                                (int) io.getDisplaySizeX(),
                                (int) io.getDisplaySizeY(),
                                actor)
                        .size()
                - 1;
        return behindCount;
    }

    int getObjectsBehindNow() {
        return behindFrame >= ImGui.getFrameCount() - 1 ? behindCount : 0;
    }

    public boolean isOrbiting() {
        return pressed && orbiting;
    }

    void refresh() {
        var topology = backend.getDebugSceneTopologyRevision();
        if (topology != topologyRevision) {
            var changed =
                    topologyRevision == Long.MIN_VALUE ? null : backend.getEditorTopologyActorsSince(topologyRevision);
            if (changed == null) {
                refreshAll();
            } else {
                refreshActors(changed);
            }
            topologyRevision = topology;
        }
        var revision = backend.getDebugSceneRevision();
        if (revision == sceneRevision && session.getRevision() == selectionRevision) {
            return;
        }
        var moved = new LinkedHashMap<String, Bounds3f>();
        var meshes = new HashMap<String, String>();
        var materials = new HashMap<String, Set<String>>();
        var moving = new HashMap<String, Boolean>();
        for (var object : backend.getEditorActorObjects(session.getSelection())) {
            moved.merge(object.actorName(), object.bounds(), EditorViewport::union);
            moving.merge(object.actorName(), object.actorAnimated(), Boolean::logicalOr);
            meshes.putIfAbsent(object.actorName(), object.assetName());
            materials
                    .computeIfAbsent(object.actorName(), ignored -> new LinkedHashSet<>())
                    .add(object.materialName());
        }
        if (!moved.isEmpty()) {
            for (var entry : moved.entrySet()) {
                var path = entry.getKey();
                var known = actors.get(path);
                if (known == null) {
                    countMesh(meshes.get(path), 1);
                }
                var movesNow = moving.getOrDefault(path, false);
                actors.put(
                        path,
                        known == null
                                ? new Actor(
                                        entry.getValue(), meshes.get(path), Set.copyOf(materials.get(path)), movesNow)
                                : new Actor(entry.getValue(), known.mesh(), known.materials(), movesNow));
            }
            actorsVersion++;
        }
        sceneRevision = revision;
        selectionRevision = session.getRevision();
    }

    private void drawSnapEdges(ImDrawList draw, Camera camera, int width, int height) {
        if (!surfaceDrag && !panel.isDragging()) {
            snapEdges = List.of();
        }
        for (var edge : snapEdges) {
            var from = screenPoint(camera, edge[0], edge[1], edge[2], width, height);
            var to = screenPoint(camera, edge[3], edge[4], edge[5], width, height);
            if (from != null && to != null) {
                draw.addLine(from[0], from[1], to[0], to[1], HudWidgets.BACKGROUND, 5);
                draw.addLine(from[0], from[1], to[0], to[1], ACCENT, 2.5f);
            }
        }
    }

    private void startMeasure(Camera camera, float x, float y, int width, int height) {
        var nx = x / width * 2 - 1;
        var ny = 1 - y / height * 2;
        measureNdc = new float[] {nx, ny, nx, ny};
        measureFrom = pickedPoint(camera, nx, ny);
        measureTo = measureFrom;
        measureFrames = 0;
        measuring = true;
        measureShown = true;
        measureKeyUsed = true;
    }

    private void updateMeasure(Camera camera, float x, float y, int width, int height) {
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            measuring = false;
            measureShown = false;
            return;
        }
        var nx = x / width * 2 - 1;
        var ny = 1 - y / height * 2;
        if (ImGui.isMouseDown(0) && (nx != measureNdc[2] || ny != measureNdc[3])) {
            measureNdc = new float[] {measureNdc[0], measureNdc[1], nx, ny};
            measureFrames = 0;
            var picked = pickedPoint(camera, nx, ny);
            if (picked != null) {
                measureTo = picked;
            }
            if (measureFrom == null) {
                measureFrom = pickedPoint(camera, measureNdc[0], measureNdc[1]);
            }
        }
        if (!ImGui.isMouseDown(0)) {
            measuring = false;
        }
    }

    private void settleMeasure(Camera camera) {
        if (!measureShown || measureFrames > ANSWER_FRAMES) {
            return;
        }
        var distances = backend.getSceneRayDistances(measureNdc, FAR);
        if (distances == null) {
            measureFrames++;
            if (!measuring && measureFrom == null && measureFrames > ANSWER_FRAMES) {
                measureShown = false;
            }
            return;
        }
        measureFrames = ANSWER_FRAMES + 1;
        for (var index = 0; index < 2; index++) {
            if (Float.isFinite(distances[index])) {
                var point = rayPoint(camera, measureNdc[index * 2], measureNdc[index * 2 + 1], distances[index]);
                if (index == 0) {
                    measureFrom = point;
                } else {
                    measureTo = point;
                }
            }
        }
    }

    private float @Nullable [] pickedPoint(Camera camera, float ndcX, float ndcY) {
        var hit = backend.pickDebugSceneObject(ndcX, ndcY, Float.MAX_VALUE, EnumSet.allOf(Category.class));
        return hit == null ? null : rayPoint(camera, ndcX, ndcY, hit.distance());
    }

    private void drawMeasure(ImDrawList draw, Camera camera, int width, int height) {
        var from = measureFrom;
        var to = measureTo;
        if (from == null || to == null) {
            return;
        }
        var a = screenPoint(camera, from[0], from[1], from[2], width, height);
        var b = screenPoint(camera, to[0], to[1], to[2], width, height);
        if (a == null || b == null) {
            return;
        }
        draw.addLine(a[0], a[1], b[0], b[1], HudWidgets.BACKGROUND, 5);
        draw.addLine(a[0], a[1], b[0], b[1], ACCENT, 2.5f);
        for (var end : new float[][] {a, b}) {
            draw.addCircleFilled(end[0], end[1], 5, HudWidgets.BACKGROUND, 16);
            draw.addCircleFilled(end[0], end[1], 3.5f, ACCENT, 16);
        }
        var text = EditorUi.measure(to[0] - from[0], to[1] - from[1], to[2] - from[2]);
        var pad = GeodataWidgets.ROW;
        var line = ImGui.getFontSize();
        var tagWidth = Math.max(ImGui.calcTextSizeX(text[0]), ImGui.calcTextSizeX(text[1])) + 2 * pad;
        var tagHeight = 2 * line + 4 + 2 * pad;
        var left = Math.round((a[0] + b[0]) * .5f - tagWidth * .5f);
        var top = Math.round((a[1] + b[1]) * .5f - tagHeight - pad);
        draw.addRectFilled(left, top, left + tagWidth, top + tagHeight, EditorUi.fill(HudWidgets.BACKGROUND, .94f), 6f);
        draw.addRect(left, top, left + tagWidth, top + tagHeight, GeodataWidgets.DIVIDER, 6f, 0, 1);
        draw.addText(left + pad, top + pad, HudWidgets.TEXT, text[0]);
        draw.addText(left + pad, top + pad + line + 4, GeodataWidgets.MUTED, text[1]);
    }

    private boolean pickSurface(
            Camera camera, float x, float y, int width, int height, boolean inside, boolean uiHasMouse) {
        if (inside && !uiHasMouse && ImGui.isMouseClicked(0)) {
            var io = ImGui.getIO();
            clickSurface(
                    x / width * 2 - 1, 1 - y / height * 2, io.getKeyCtrl() || io.getKeySuper() || io.getKeyShift());
        }
        return resolveSurfaceClick(camera);
    }

    private void drawSurfaceMarks(ImDrawList draw, Camera camera, int width, int height) {
        var surfaces = panel.getSelectedSurfaces();
        var revision = session.getRevision();
        var pointed = panel.getPointedSurfaces();
        if (pointedRevision != revision || !pointed.equals(pointedChoice)) {
            pointedChoice = pointed;
            pointedRevision = revision;
            pointedOutline = session.getSurfaceOutline(pointed);
        }
        drawOutline(draw, camera, pointedOutline, width, height, GeodataWidgets.WARNING, 2.5f, 2.5f);
        if (!panel.isBrushWireframeShown()) {
            wireframeChoice = null;
            wireframe = Outline.NONE;
        } else if (surfaces != wireframeChoice || wireframeRevision != revision) {
            wireframeChoice = surfaces;
            wireframeRevision = revision;
            wireframe = session.getBrushWireframe(session.getSurfaceBrushActors(surfaces));
        }
        drawWorldOutline(
                draw,
                camera,
                wireframe,
                width,
                height,
                GeodataWidgets.TEXT,
                faint(GeodataWidgets.TEXT),
                WIREFRAME_WIDTH,
                WIREFRAME_WIDTH);
        if (surfaces != outlineChoice || outlineRevision != revision) {
            refreshOutline(surfaces, revision);
        }
        drawWorldOutline(draw, camera, outline, width, height, ACCENT, ACCENT, 2, 1.5f);
        if (surfaces.isEmpty() || activePolygons.isEmpty()) {
            return;
        }
        BspSurfaceMapping mapping;
        float[] tile;
        try {
            mapping = session.getSurfaceMapping(surfaces.getFirst());
            tile = session.getSurfaceTile(surfaces.getFirst());
        } catch (IOException | RuntimeException unreadable) {
            return;
        }
        if (tile == null) {
            return;
        }
        for (var polygon : activePolygons) {
            tileLines(
                    draw,
                    camera,
                    polygon,
                    mapping.base(),
                    mapping.u(),
                    tile[0],
                    width,
                    height,
                    faint(GeodataWidgets.OPEN));
            tileLines(
                    draw,
                    camera,
                    polygon,
                    mapping.base(),
                    mapping.v(),
                    tile[1],
                    width,
                    height,
                    faint(GeodataWidgets.WARNING));
        }
        var center = EditingSession.center(activePolygons);
        axis(draw, camera, center, mapping.u(), tile[0], "U", width, height, GeodataWidgets.OPEN);
        axis(draw, camera, center, mapping.v(), tile[1], "V", width, height, GeodataWidgets.WARNING);
    }

    private void drawWorldOutline(
            ImDrawList draw,
            Camera camera,
            Outline lines,
            int width,
            int height,
            int color,
            int screenColor,
            float firstThickness,
            float thickness) {
        var world = worldEdges;
        var edges = lines.edges();
        if (world == null || edges.length > 2 * WORLD_OUTLINE_EDGES) {
            drawOutline(draw, camera, lines, width, height, screenColor, firstThickness, thickness);
            return;
        }
        var points = lines.points();
        var first = lines.firstEdges() * 2;
        for (var e = 0; e < edges.length; e += 2) {
            var a = edges[e] * 3;
            var b = edges[e + 1] * 3;
            world.addLine(
                    points[a],
                    points[a + 1],
                    points[a + 2],
                    points[b],
                    points[b + 1],
                    points[b + 2],
                    color,
                    e < first ? firstThickness : thickness);
        }
    }

    private void refreshOutline(List<SurfaceRef> surfaces, long revision) {
        outlineChoice = surfaces;
        outlineRevision = revision;
        outline = session.getSurfaceOutline(surfaces);
        activePolygons = List.of();
        if (surfaces.isEmpty()) {
            return;
        }
        try {
            activePolygons = session.getSurfacePolygons(surfaces.getFirst());
        } catch (IOException | RuntimeException unreadable) {
            activePolygons = List.of();
        }
    }

    private void drawOutline(
            ImDrawList draw,
            Camera camera,
            Outline lines,
            int width,
            int height,
            int color,
            float firstThickness,
            float thickness) {
        var points = lines.points();
        var count = points.length / 3;
        if (count == 0) {
            return;
        }
        if (outlineCodes.length < count) {
            outlineScreen = new float[count * 2];
            outlineCodes = new byte[count];
        }
        var view = camera.getViewMatrix();
        var projection = camera.getProjectionMatrix();
        var m = outlineMatrix;
        for (var column = 0; column < 4; column++) {
            for (var row = 0; row < 4; row++) {
                var sum = 0f;
                for (var k = 0; k < 4; k++) {
                    sum += projection.get(row + 4 * k) * view.get(k + 4 * column);
                }
                m[row + 4 * column] = sum;
            }
        }
        var halfWidth = width * .5f;
        var halfHeight = height * .5f;
        for (var i = 0; i < count; i++) {
            var x = points[i * 3];
            var y = points[i * 3 + 1];
            var z = points[i * 3 + 2];
            var clipX = m[0] * x + m[4] * y + m[8] * z + m[12];
            var clipY = m[1] * x + m[5] * y + m[9] * z + m[13];
            var clipZ = m[2] * x + m[6] * y + m[10] * z + m[14];
            var w = m[3] * x + m[7] * y + m[11] * z + m[15];
            var code = w <= 0 || clipZ < 0 || clipZ > w ? 16 : 0;
            if (clipX < -w) {
                code |= 1;
            } else if (clipX > w) {
                code |= 2;
            }
            if (clipY < -w) {
                code |= 4;
            } else if (clipY > w) {
                code |= 8;
            }
            outlineCodes[i] = (byte) code;
            if (code < 16) {
                outlineScreen[i * 2] = (clipX / w + 1) * halfWidth;
                outlineScreen[i * 2 + 1] = (1 - clipY / w) * halfHeight;
            }
        }
        var edges = lines.edges();
        var first = lines.firstEdges() * 2;
        for (var e = 0; e < edges.length; e += 2) {
            var a = edges[e];
            var b = edges[e + 1];
            var codeA = outlineCodes[a];
            var codeB = outlineCodes[b];
            if (((codeA | codeB) & 16) != 0 || (codeA & codeB) != 0) {
                continue;
            }
            draw.addLine(
                    outlineScreen[a * 2],
                    outlineScreen[a * 2 + 1],
                    outlineScreen[b * 2],
                    outlineScreen[b * 2 + 1],
                    color,
                    e < first ? firstThickness : thickness);
        }
    }

    private void tileLines(
            ImDrawList draw,
            Camera camera,
            float[] polygon,
            Vector3 base,
            Vector3 axis,
            float tile,
            int width,
            int height,
            int color) {
        var count = polygon.length / 3;
        var tiles = new float[count];
        var min = Float.POSITIVE_INFINITY;
        var max = Float.NEGATIVE_INFINITY;
        for (var k = 0; k < count; k++) {
            tiles[k] = (axis.x() * (polygon[k * 3] - base.x())
                            + axis.y() * (polygon[k * 3 + 1] - base.y())
                            + axis.z() * (polygon[k * 3 + 2] - base.z()))
                    / tile;
            min = Math.min(min, tiles[k]);
            max = Math.max(max, tiles[k]);
        }
        if (!Float.isFinite(min) || !Float.isFinite(max) || max - min > 64) {
            return;
        }
        for (var n = (float) Math.ceil(min); n <= max; n++) {
            var ends = new float[6];
            var found = 0;
            for (var k = 0; k < count && found < 2; k++) {
                var j = (k + 1) % count;
                var a = tiles[k] - n;
                var b = tiles[j] - n;
                if (a == b || (a > 0) == (b > 0) && a != 0) {
                    continue;
                }
                var t = a / (a - b);
                for (var c = 0; c < 3; c++) {
                    ends[found * 3 + c] = polygon[k * 3 + c] + t * (polygon[j * 3 + c] - polygon[k * 3 + c]);
                }
                found++;
            }
            if (found == 2) {
                segment(draw, camera, ends[0], ends[1], ends[2], ends[3], ends[4], ends[5], width, height, color, 1);
            }
        }
    }

    private void axis(
            ImDrawList draw,
            Camera camera,
            Vector3 from,
            Vector3 axis,
            float tile,
            String letter,
            int width,
            int height,
            int color) {
        var length = (float) Math.sqrt(axis.x() * axis.x() + axis.y() * axis.y() + axis.z() * axis.z());
        if (!(length > 0)) {
            return;
        }
        var world = Math.clamp(tile / length, 32f, 512f);
        var tx = from.x() + axis.x() / length * world;
        var ty = from.y() + axis.y() / length * world;
        var tz = from.z() + axis.z() / length * world;
        segment(draw, camera, from.x(), from.y(), from.z(), tx, ty, tz, width, height, color, 2.5f);
        var tip = project(camera, tx, ty, tz, width, height);
        if (tip != null) {
            draw.addCircleFilled(tip.getX(), tip.getY(), 3, color);
            draw.addText(tip.getX() + 5, tip.getY() - ImGui.getTextLineHeight() / 2, color, letter);
        }
    }

    private void startPress(float x, float y, boolean allowRectangle, boolean clicks) {
        var io = ImGui.getIO();
        pressed = true;
        rectangle = false;
        orbiting = false;
        orbitJob = null;
        rectangleAllowed = allowRectangle;
        pressClicks = clicks;
        pressMode = io.getKeyCtrl() || io.getKeySuper() ? Mode.TOGGLE : io.getKeyShift() ? Mode.ADD : Mode.REPLACE;
        pressTwice = ImGui.isMouseDoubleClicked(0);
        pressThrough = io.getKeyAlt();
        pressVisible = hoveredActor();
        startX = x;
        startY = y;
    }

    private boolean updatePress(Camera camera, float x, float y, float left, float top, float right, float bottom) {
        if (!rectangle && !orbiting && Math.hypot(x - startX, y - startY) > CLICK_PIXELS) {
            if (pressThrough && !camera.isOrtho()) {
                startOrbit(camera);
            } else if (rectangleAllowed) {
                rectangle = true;
            }
        }
        if (orbiting) {
            updateOrbit(camera, x, y);
            if (!ImGui.isMouseDown(0)) {
                pressed = false;
                orbiting = false;
                orbitJob = null;
            }
            return true;
        }
        var endX = Math.clamp(x, left, right);
        var endY = Math.clamp(y, top, bottom);
        if (rectangle) {
            var draw = ImGui.getForegroundDrawList();
            draw.addRectFilled(
                    Math.min(startX, endX),
                    Math.min(startY, endY),
                    Math.max(startX, endX),
                    Math.max(startY, endY),
                    EditorUi.fill(ACCENT, .14f));
            draw.addRect(
                    Math.min(startX, endX),
                    Math.min(startY, endY),
                    Math.max(startX, endX),
                    Math.max(startY, endY),
                    ACCENT);
        }
        if (ImGui.isMouseDown(0)) {
            return true;
        }
        pressed = false;
        if (rectangle) {
            rectangle = false;
            if (panel.getTool() == Tool.LIGHTS) {
                var io = ImGui.getIO();
                lights.chooseIn(
                        camera,
                        startX,
                        startY,
                        endX,
                        endY,
                        (int) io.getDisplaySizeX(),
                        (int) io.getDisplaySizeY(),
                        doesRectangleAdd(pressMode));
                return true;
            }
            finishRectangle(camera, endX, endY, ImGui.getIO().getKeyAlt() || pressThrough, left, top, right, bottom);
            return true;
        }
        if (pressClicks) {
            click = new Click(pressMode, pressTwice, nextBehind(camera));
        }
        return false;
    }

    private @Nullable String nextBehind(Camera camera) {
        var again = cycle.isRepeatClick(startX, startY, ImGui.getTime());
        var selection = session.getSelection();
        if (!again
                || panel.getTool() != Tool.SELECT
                || pressMode != Mode.REPLACE
                || pressTwice
                || selection.size() != 1) {
            return null;
        }
        var visible = pressVisible;
        if (visible == null) {
            return null;
        }
        var io = ImGui.getIO();
        var order = stack(camera, startX, startY, (int) io.getDisplaySizeX(), (int) io.getDisplaySizeY(), visible);
        return ClickCycle.next(order, selection.iterator().next());
    }

    private @Nullable String hoveredActor() {
        var target = hovered;
        if (target == null
                || target.sceneObject() == null
                || target.sceneObject().category() != Category.STATIC_MESH) {
            return null;
        }
        return actors.containsKey(target.actor()) ? target.actor() : null;
    }

    private List<String> stack(Camera camera, float x, float y, int width, int height, String visible) {
        var nx = x / width * 2 - 1;
        var ny = 1 - y / height * 2;
        var origin = camera.getCursorRayOrigin(nx, ny);
        var direction = camera.getCursorRayDirection(nx, ny);
        var hidden = session.getHiddenActors();
        var locked = session.getLockedActors();
        var isolated = panel.getIsolatedActors();
        var hits = new ArrayList<Hit>();
        for (var entry : actors.entrySet()) {
            var path = entry.getKey();
            if (hidden.contains(path) || locked.contains(path) || isolated != null && !isolated.contains(path)) {
                continue;
            }
            var span = rayBox(origin, direction, entry.getValue().bounds(), 0);
            if (span != null) {
                hits.add(new Hit(path, span[0]));
            }
        }
        return ClickCycle.order(hits, visible);
    }

    private void startOrbit(Camera camera) {
        orbiting = true;
        rectangle = false;
        var eye = camera.getPosition();
        orbitStart = new Pose(eye.getX(), eye.getY(), eye.getZ(), camera.getYawDegrees(), camera.getPitchDegrees());
        orbitCenter = orbitPivot();
        orbitJob = null;
        if (orbitCenter == null) {
            var io = ImGui.getIO();
            orbitJob = new PointsJob(
                    new float[] {startX / io.getDisplaySizeX() * 2 - 1, 1 - startY / io.getDisplaySizeY() * 2},
                    List.of());
        }
    }

    private float @Nullable [] orbitPivot() {
        if (panel.getTool() == Tool.LIGHTS) {
            var paths = panel.getLights().getChosen();
            if (paths.isEmpty()) {
                return null;
            }
            var sum = new float[3];
            for (var path : paths) {
                var at = session.getLight(path).orElseThrow().location();
                sum[0] += at.x();
                sum[1] += at.y();
                sum[2] += at.z();
            }
            return new float[] {sum[0] / paths.size(), sum[1] / paths.size(), sum[2] / paths.size()};
        }
        Bounds3f union = null;
        for (var bounds : getCurrentBounds(session.getSelection()).values()) {
            union = union == null ? bounds : union(union, bounds);
        }
        return union == null
                ? null
                : new float[] {
                    (union.minX() + union.maxX()) * .5f,
                    (union.minY() + union.maxY()) * .5f,
                    (union.minZ() + union.maxZ()) * .5f
                };
    }

    private void updateOrbit(Camera camera, float x, float y) {
        var start = orbitStart;
        if (start == null) {
            return;
        }
        if (orbitCenter == null) {
            orbitCenter = orbitPoint(camera);
            if (orbitCenter == null) {
                return;
            }
        }
        var turn = CameraOrbit.drag(x - startX, y - startY);
        var pose = CameraOrbit.orbit(start, orbitCenter[0], orbitCenter[1], orbitCenter[2], turn[0], turn[1]);
        camera.setPosition((float) pose.x(), (float) pose.y(), (float) pose.z());
        camera.setDirection(pose.yaw(), pose.pitch());
        var io = ImGui.getIO();
        var pivot =
                screenPoint(camera, orbitCenter[0], orbitCenter[1], orbitCenter[2], (int) io.getDisplaySizeX(), (int)
                        io.getDisplaySizeY());
        if (pivot != null) {
            var draw = ImGui.getForegroundDrawList();
            draw.addCircle(pivot[0], pivot[1], 7, HudWidgets.BACKGROUND, 20, 4);
            draw.addCircle(pivot[0], pivot[1], 7, ACCENT, 20, 2);
            draw.addCircleFilled(pivot[0], pivot[1], 2.5f, ACCENT);
        }
    }

    private float @Nullable [] orbitPoint(Camera camera) {
        var job = orbitJob;
        if (job == null) {
            return null;
        }
        var distances = backend.getSceneRayDistances(job.ndc, FAR);
        var distance = Float.NaN;
        if (distances != null) {
            distance = distances[0];
        } else if (++job.frames <= ANSWER_FRAMES) {
            return null;
        }
        orbitJob = null;
        var ndcX = job.ndc[0];
        var ndcY = job.ndc[1];
        if (!Float.isFinite(distance)) {
            var hit = backend.pickDebugSceneObject(ndcX, ndcY, Float.MAX_VALUE, EnumSet.allOf(Category.class));
            distance = hit == null ? ORBIT_AHEAD : hit.distance();
        }
        var origin = camera.getCursorRayOrigin(ndcX, ndcY);
        var direction = camera.getCursorRayDirection(ndcX, ndcY);
        return new float[] {
            origin.getX() + direction.getX() * distance,
            origin.getY() + direction.getY() * distance,
            origin.getZ() + direction.getZ() * distance
        };
    }

    private void finishRectangle(
            Camera camera, float endX, float endY, boolean through, float left, float top, float right, float bottom) {
        var io = ImGui.getIO();
        var width = (int) io.getDisplaySizeX();
        var height = (int) io.getDisplaySizeY();
        var candidates = new ArrayList<String>();
        var rects = new ArrayList<float[]>();
        var isolated = panel.getIsolatedActors();
        for (var entry : actors.entrySet()) {
            if (session.getLockedActors().contains(entry.getKey()) || !IsolatedGroup.inView(isolated, entry.getKey())) {
                continue;
            }
            var rect = projectedRect(camera, entry.getValue().bounds(), width, height);
            if (rect != null
                    && rect[2] >= Math.min(startX, endX)
                    && rect[0] <= Math.max(startX, endX)
                    && rect[3] >= Math.min(startY, endY)
                    && rect[1] <= Math.max(startY, endY)) {
                candidates.add(entry.getKey());
                rects.add(new float[] {
                    Math.max(rect[0], Math.max(Math.min(startX, endX), left)),
                    Math.max(rect[1], Math.max(Math.min(startY, endY), top)),
                    Math.min(rect[2], Math.min(Math.max(startX, endX), right)),
                    Math.min(rect[3], Math.min(Math.max(startY, endY), bottom))
                });
            }
        }
        rectangleNote = "";
        if (through || candidates.isEmpty()) {
            select(candidates);
            return;
        }
        if (candidates.size() > ViewerRenderBackend.RAY_POINTS) {
            rectangleNote = "The rectangle held " + candidates.size() + " objects, more than the "
                    + ViewerRenderBackend.RAY_POINTS + " whose visibility it tests, so it took the hidden ones too.";
            select(candidates);
            return;
        }
        var each = Math.clamp(ViewerRenderBackend.RAY_POINTS / candidates.size(), 1, 5);
        var ndc = new float[candidates.size() * each * 2];
        var owners = new ArrayList<String>();
        var offsets = new float[][] {{0, 0}, {-.25f, -.25f}, {.25f, -.25f}, {-.25f, .25f}, {.25f, .25f}};
        var index = 0;
        for (var i = 0; i < candidates.size(); i++) {
            var rect = rects.get(i);
            var cx = (rect[0] + rect[2]) * .5f;
            var cy = (rect[1] + rect[3]) * .5f;
            for (var k = 0; k < each; k++) {
                var sx = cx + offsets[k][0] * (rect[2] - rect[0]);
                var sy = cy + offsets[k][1] * (rect[3] - rect[1]);
                ndc[index++] = sx / width * 2 - 1;
                ndc[index++] = 1 - sy / height * 2;
                owners.add(candidates.get(i));
            }
        }
        rectangleJob = new PointsJob(ndc, owners);
        rectangleJob.additive = doesRectangleAdd(pressMode);
        updateRectangleJob(camera);
    }

    private void updateRectangleJob(Camera camera) {
        var job = rectangleJob;
        if (job == null) {
            return;
        }
        var distances = backend.getSceneRayDistances(job.ndc, FAR);
        var additive = job.additive;
        if (distances == null) {
            if (++job.frames > ANSWER_FRAMES) {
                rectangleJob = null;
                rectangleNote =
                        "The visibility test got no answer from the renderer, so the rectangle took the hidden objects"
                                + " too.";
                select(new ArrayList<>(new LinkedHashSet<>(job.owners)), additive);
            }
            return;
        }
        rectangleJob = null;
        var visible = new LinkedHashSet<String>();
        for (var i = 0; i < job.owners.size(); i++) {
            var owner = job.owners.get(i);
            var actor = actors.get(owner);
            var distance = distances[i];
            if (actor == null || visible.contains(owner)) {
                continue;
            }
            var origin = camera.getCursorRayOrigin(job.ndc[i * 2], job.ndc[i * 2 + 1]);
            var direction = camera.getCursorRayDirection(job.ndc[i * 2], job.ndc[i * 2 + 1]);
            if (session.isPawn(owner)) {
                if (isPawnInReach(camera.getPosition(), camera.isInteractiveOrtho(), actor.bounds())
                        && isPawnSeen(rayBox(origin, direction, actor.bounds(), 0), distance)) {
                    visible.add(owner);
                }
                continue;
            }
            if (!Float.isFinite(distance)) {
                continue;
            }
            var tolerance = 2 + distance * .002f;
            var span = rayBox(origin, direction, actor.bounds(), tolerance);
            if (span != null && distance >= span[0] - tolerance && distance <= span[1] + tolerance) {
                visible.add(owner);
            }
        }
        select(new ArrayList<>(visible), additive);
    }

    private void select(List<String> paths) {
        select(paths, doesRectangleAdd(pressMode));
    }

    private void select(List<String> paths, boolean additive) {
        try {
            session.selectMany(paths, additive);
        } catch (RuntimeException failure) {
            panel.showError(failure.getMessage());
        }
    }

    private boolean startSurfaceDrag() {
        var bounds = movableBounds();
        if (bounds == null) {
            return false;
        }
        surfaceBaseX = (bounds.minX() + bounds.maxX()) * .5f;
        surfaceBaseY = (bounds.minY() + bounds.maxY()) * .5f;
        surfaceBaseZ = bounds.minZ();
        surfaceZ = surfaceBaseZ;
        surfaceBounds = bounds;
        snapEdges = List.of();
        lastOffsetX = Float.NaN;
        try {
            session.beginStep();
            session.beginGesture();
        } catch (RuntimeException failure) {
            session.endStep(null);
            panel.showError(failure.getMessage());
            return false;
        }
        surfaceDrag = true;
        return true;
    }

    private void updateSurfaceDrag(Camera camera, float x, float y, int width, int height) {
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            cancelGesture();
            return;
        }
        if (!ImGui.isMouseDown(0)) {
            surfaceDrag = false;
            session.finishGesture();
            panel.endMove(null);
            return;
        }
        var nx = x / width * 2 - 1;
        var ny = 1 - y / height * 2;
        var origin = camera.getCursorRayOrigin(nx, ny);
        var direction = camera.getCursorRayDirection(nx, ny);
        float px, py, pz;
        var hit = backend.pickDebugSceneObject(
                nx, ny, Float.MAX_VALUE, EnumSet.of(Category.TERRAIN, Category.CSG, Category.STATIC_MESH));
        if (hit != null && !session.getSelection().contains(hit.object().actorName())) {
            px = origin.getX() + direction.getX() * hit.distance();
            py = origin.getY() + direction.getY() * hit.distance();
            pz = origin.getZ() + direction.getZ() * hit.distance();
            surfaceZ = pz;
        } else {
            if (Math.abs(direction.getZ()) < 1e-4f) {
                return;
            }
            var t = (surfaceZ - origin.getZ()) / direction.getZ();
            if (!(t > 0)) {
                return;
            }
            px = origin.getX() + direction.getX() * t;
            py = origin.getY() + direction.getY() * t;
            pz = surfaceZ;
        }
        var ox = px - surfaceBaseX;
        var oy = py - surfaceBaseY;
        var oz = pz - surfaceBaseZ;
        if (Float.isFinite(lastOffsetX)
                && Math.abs(ox - lastOffsetX) < .5f
                && Math.abs(oy - lastOffsetY) < .5f
                && Math.abs(oz - lastOffsetZ) < .5f) {
            return;
        }
        lastOffsetX = ox;
        lastOffsetY = oy;
        lastOffsetZ = oz;
        var offset = new Vector3(ox, oy, oz);
        if (surfaceBounds != null) {
            offset = snapToNeighbors(surfaceBounds, offset, new boolean[] {true, true, true});
        }
        try {
            session.previewTranslationGesture(offset);
        } catch (RuntimeException failure) {
            panel.showError(failure.getMessage());
        }
    }

    private void startDrop(Camera camera, boolean slope, float left, float top, float right, float bottom) {
        var bounds = movableBounds();
        if (bounds == null) {
            panel.showError("The selected objects are locked or not drawn yet.");
            return;
        }
        var job = groundJob(camera, bounds, bounds.minZ(), left, top, right, bottom);
        if (job == null) {
            dropOnTerrain(bounds, slope);
            return;
        }
        job.slope = slope;
        dropJob = job;
    }

    private void updateDropJob(Camera camera, float left, float top, float right, float bottom) {
        var job = dropJob;
        if (job == null) {
            return;
        }
        var distances = backend.getSceneRayDistances(job.ndc, FAR);
        if (distances == null) {
            if (++job.frames > ANSWER_FRAMES) {
                dropJob = null;
                panel.showError(
                        "The renderer did not tell where the ground is. Hold the camera still and press End again.");
            }
            return;
        }
        var bounds = movableBounds();
        if (bounds == null) {
            dropJob = null;
            return;
        }
        var cx = (bounds.minX() + bounds.maxX()) * .5f;
        var cy = (bounds.minY() + bounds.maxY()) * .5f;
        var radius = footprint(bounds);
        var lift = Math.min(32, (bounds.maxZ() - bounds.minZ()) * .5f);
        var second = job.second;
        var reach = second ? radius * 2.5f + 64 : Math.max(radius * 4 + 256, 4096);
        var hits = new ArrayList<float[]>();
        for (var i = 0; i < distances.length; i++) {
            if (!Float.isFinite(distances[i])) {
                continue;
            }
            var origin = camera.getCursorRayOrigin(job.ndc[i * 2], job.ndc[i * 2 + 1]);
            var direction = camera.getCursorRayDirection(job.ndc[i * 2], job.ndc[i * 2 + 1]);
            var hx = origin.getX() + direction.getX() * distances[i];
            var hy = origin.getY() + direction.getY() * distances[i];
            var hz = origin.getZ() + direction.getZ() * distances[i];
            if (hz > bounds.minZ() + lift || Math.hypot(hx - cx, hy - cy) > reach || isInsideSelection(hx, hy, hz)) {
                continue;
            }
            hits.add(new float[] {hx, hy, hz});
        }
        var ground = GroundFit.fit(hits, cx, cy);
        var slope = job.slope;
        if (!second) {
            if (ground == null) {
                dropJob = null;
                dropOnTerrain(bounds, slope);
                return;
            }
            var next = groundJob(camera, bounds, ground.z(), left, top, right, bottom);
            if (next == null) {
                dropJob = null;
                panel.applyDrop(ground.z() - bounds.minZ(), null);
                return;
            }
            next.second = true;
            next.slope = slope;
            dropJob = next;
            return;
        }
        dropJob = null;
        if (ground == null) {
            panel.showError(
                    "No ground below the selection is in view. Turn the camera so the ground around it shows and press"
                            + " End again.");
            return;
        }
        var normal = slope ? ground.normal() : null;
        panel.applyDrop(ground.z() - bounds.minZ(), normal);
        if (slope && normal == null) {
            panel.showNote("Dropped to the ground. The ground around it gave no usable slope, so it kept its turn.");
        }
    }

    private void dropOnTerrain(Bounds3f bounds, boolean slope) {
        var probe = panel.getGroundProbe();
        var cx = (bounds.minX() + bounds.maxX()) * .5f;
        var cy = (bounds.minY() + bounds.maxY()) * .5f;
        var height = probe.getCurrent(cx, cy);
        if (height == null) {
            panel.showError(
                    probe.isReady(cx, cy)
                            ? "No ground below the selection is in view, and no Terrain lies under it. Turn the camera"
                                    + " so the ground around it shows and press End again."
                            : "No ground below the selection is in view. Reading the Terrain of "
                                    + probe.getMapAt(cx, cy) + "; press End again in a moment.");
            return;
        }
        float[] normal = null;
        if (slope) {
            var reach = Math.max(32, footprint(bounds) * .5f);
            var east = probe.getCurrent(cx + reach, cy);
            var west = probe.getCurrent(cx - reach, cy);
            var north = probe.getCurrent(cx, cy + reach);
            var south = probe.getCurrent(cx, cy - reach);
            if (east != null && west != null && north != null && south != null) {
                var nx = -(east - west) / (2 * reach);
                var ny = -(north - south) / (2 * reach);
                var length = (float) Math.sqrt(nx * nx + ny * ny + 1);
                normal = new float[] {nx / length, ny / length, 1 / length};
                if (normal[2] < Math.cos(Math.toRadians(60))) {
                    normal = null;
                }
            }
        }
        panel.applyDrop(height - bounds.minZ(), normal);
        panel.showNote(
                "No ground was in view, so the selection went onto the Terrain height under it. Level geometry and"
                        + " other meshes were not looked at.");
    }

    private @Nullable PointsJob groundJob(
            Camera camera, Bounds3f bounds, float height, float left, float top, float right, float bottom) {
        var io = ImGui.getIO();
        var width = io.getDisplaySizeX();
        var screenHeight = io.getDisplaySizeY();
        var cx = (bounds.minX() + bounds.maxX()) * .5f;
        var cy = (bounds.minY() + bounds.maxY()) * .5f;
        var radius = footprint(bounds);
        var ndc = new ArrayList<Float>();
        for (var ring : new float[] {1.15f, 1.6f, 2.2f}) {
            for (var step = 0; step < 16; step++) {
                var angle = Math.PI * 2 * step / 16 + ring;
                var point = new Vec3(
                        cx + (float) Math.cos(angle) * radius * ring,
                        cy + (float) Math.sin(angle) * radius * ring,
                        height);
                var projected = camera.projectToNdc(point);
                if (projected == null) {
                    continue;
                }
                var sx = (projected.getX() * .5f + .5f) * width;
                var sy = (.5f - projected.getY() * .5f) * screenHeight;
                if (sx < left || sx > right || sy < top || sy > bottom) {
                    continue;
                }
                ndc.add(projected.getX());
                ndc.add(projected.getY());
            }
        }
        if (ndc.size() < 6) {
            return null;
        }
        var values = new float[ndc.size()];
        for (var i = 0; i < values.length; i++) {
            values[i] = ndc.get(i);
        }
        return new PointsJob(values, List.of());
    }

    private boolean isInsideSelection(float x, float y, float z) {
        for (var path : session.getSelection()) {
            var actor = actors.get(path);
            if (actor == null) {
                continue;
            }
            var b = actor.bounds();
            if (x >= b.minX() - 4
                    && x <= b.maxX() + 4
                    && y >= b.minY() - 4
                    && y <= b.maxY() + 4
                    && z >= b.minZ() - 4
                    && z <= b.maxZ() + 4) {
                return true;
            }
        }
        return false;
    }

    private @Nullable Bounds3f movableBounds() {
        Bounds3f union = null;
        for (var path : session.getSelection()) {
            var actor = actors.get(path);
            if (actor != null && !session.getLockedActors().contains(path)) {
                var box = measured(path, actor);
                union = union == null ? box : union(union, box);
            }
        }
        return union;
    }

    private Bounds3f measured(String path, Actor actor) {
        var saved = actor.moving() ? backend.getSavedActorBounds(path) : null;
        return saved != null ? saved : actor.bounds();
    }

    private boolean contextMenu(Camera camera, boolean inside, float left, float top, float right, float bottom) {
        var io = ImGui.getIO();
        var x = ImGui.getMousePosX();
        var y = ImGui.getMousePosY();
        var free = !io.getWantCaptureMouse()
                || panel.isGizmoHovered() && !ImGui.isWindowHovered(ImGuiHoveredFlags.AnyWindow);
        if (ImGui.isMouseClicked(1) && inside && free && !pressed && !surfaceDrag) {
            rightPressed = true;
            rightX = x;
            rightY = y;
            rightAt = System.nanoTime();
        }
        if (rightPressed && !ImGui.isMouseDown(1)) {
            rightPressed = false;
            if (Math.hypot(x - rightX, y - rightY) <= CLICK_PIXELS && System.nanoTime() - rightAt <= MENU_NANOS) {
                var light = panel.getTool() == Tool.LIGHTS && !panel.getLights().isPlacing()
                        ? lights.findLightAt(camera, x, y, (int) io.getDisplaySizeX(), (int) io.getDisplaySizeY())
                        : null;
                if (light != null) {
                    openLightMenu(light, x, y);
                } else {
                    openMenu(camera, x, y);
                }
            }
        }
        var actor = menuActor;
        var light = menuLight;
        var rows = light != null ? 6 : actor == null ? 6 : 18 + (moveTile() == null ? 0 : 1);
        var rules = light != null ? 2 : actor == null ? 3 : 7;
        if (!HudWidgets.beginMenuAt(MENU, MENU_WIDTH, HudWidgets.menuHeight(rows, rules), menuX, menuY)) {
            return false;
        }
        if (ImGui.isKeyPressed(ImGuiKey.Escape)) {
            ImGui.closeCurrentPopup();
        }
        if (light != null) {
            drawLightMenu(light);
        } else if (actor == null) {
            if (HudWidgets.menuRow("menuSelectInView", Glyph.CURSOR, "Select all in view", "")) {
                selectInView(camera, left, top, right, bottom);
            }
            GeodataWidgets.tip(
                    "Select every object whose bounds show in the viewport, also those behind others. Locked objects"
                            + " stay out.");
            HudWidgets.menuRule();
            if (HudWidgets.menuRow(
                    "menuShowAll",
                    Glyph.EYE,
                    "Show all",
                    "Alt+H",
                    !session.getHiddenActors().isEmpty())) {
                panel.showAll();
            }
            GeodataWidgets.tip(
                    session.getHiddenActors().isEmpty() ? "Nothing is hidden." : "Draw every hidden object again.");
            if (HudWidgets.menuRow("menuPaste", Glyph.COPY, "Paste", COMMAND_KEY + "+V", panel.canPaste())) {
                menuPasteX = menuX;
                menuPasteY = menuY;
                panel.requestPaste();
            }
            GeodataWidgets.tip(
                    panel.canPaste()
                            ? "Paste the copied actors here, standing on the surface."
                            : "Nothing is copied. " + COMMAND_KEY + "+C copies the selected actors.");
        } else {
            drawActorMenu(actor);
        }
        if (light == null) {
            HudWidgets.menuRule();
            drawCopyPlace(actor != null);
        }
        HudWidgets.menuRule();
        if (HudWidgets.menuRow("menuCheck", Glyph.SHIELD, "Check", "")) {
            panel.runCheck();
        }
        GeodataWidgets.tip(
                "Open the save review and check the loaded maps: meshes that float or sink, objects outside their map,"
                        + " copies in one place, NPC Spawns in meshes and old geodata.");
        HudWidgets.endMenu();
        return true;
    }

    private void openLightMenu(String light, float x, float y) {
        var tool = panel.getLights();
        if (!tool.isChosen(light)) {
            tool.select(light);
        }
        menuLight = light;
        menuActor = null;
        menuX = x;
        menuY = y;
        ImGui.openPopup(MENU);
    }

    private void drawLightMenu(String light) {
        var tool = panel.getLights();
        var paths = tool.getChosen();
        var many = paths.size() > 1;
        if (HudWidgets.menuRow("menuLightFocus", Glyph.FOCUS, "Focus", "F")) {
            tool.focusChosen();
        }
        if (HudWidgets.menuRow("menuLightCopy", Glyph.COPY, "Copy", COMMAND_KEY + "+D")) {
            tool.copySelected();
        }
        GeodataWidgets.tip(many ? "Add a copy of each chosen light beside it." : "Add a copy of the light beside it.");
        if (HudWidgets.menuRow("menuLightDelete", Glyph.TRASH, "Delete", "Del")) {
            tool.deleteChosen();
        }
        if (HudWidgets.menuRow("menuLightRevert", Glyph.RELOAD, "Revert", "")) {
            tool.revertChosen();
        }
        GeodataWidgets.tip(
                many
                        ? "Give the changed lights among the chosen ones their values in the client back."
                        : "Give the light its values in the client back.");
        HudWidgets.menuRule();
        if (HudWidgets.menuRow("menuLightColour", Glyph.SUN, "Select all lights with this colour", "")) {
            tool.chooseSameColor(light);
        }
        GeodataWidgets.tip("Choose every light of this map whose colour " + EditingSession.shortName(light)
                + " shares, as the name of a preset reaches, at any brightness.");
    }

    private void drawCopyPlace(boolean object) {
        var point = object && menuActor != null ? actorLocation(menuActor) : menuPoint;
        var known = point != null;
        var what = object ? "the object's location" : "the point under the pointer";
        if (HudWidgets.menuRow("menuCopyPlace", Glyph.COPY, "Copy coordinates", "", known)) {
            var text = TeleportCommand.coordinates(point[0], point[1], point[2]);
            ImGui.setClipboardText(text);
            panel.showDone("Copied " + text);
        }
        menuRows.put("coordinates", new float[] {
            ImGui.getItemRectMinX(), ImGui.getItemRectMinY(), ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY()
        });
        GeodataWidgets.tip(
                known
                        ? "Put X Y Z of " + what + " on the clipboard, in whole units."
                        : "Nothing of the scene is under the pointer here.");
        var template = panel.getTeleportCommand();
        var ready = known && !template.isBlank();
        if (HudWidgets.menuRow("menuCopyTeleport", Glyph.GO, "Copy teleport command", "", ready)) {
            var text = TeleportCommand.format(template, point[0], point[1], point[2]);
            ImGui.setClipboardText(text);
            panel.showDone("Copied " + text);
        }
        menuRows.put("teleport", new float[] {
            ImGui.getItemRectMinX(), ImGui.getItemRectMinY(), ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY()
        });
        GeodataWidgets.tip(
                !known
                        ? "Nothing of the scene is under the pointer here."
                        : template.isBlank()
                                ? "No teleport command is set. Set the command of your server in Settings, Advanced,"
                                        + " Edit."
                                : "Put the server's admin command for " + what + " on the clipboard, such as "
                                        + TeleportCommand.format(template, point[0], point[1], point[2])
                                        + ". Settings, Advanced, Edit sets the command.");
    }

    private float @Nullable [] actorLocation(String path) {
        try {
            var at = session.getActor(path).transform().location();
            return new float[] {at.x(), at.y(), at.z()};
        } catch (RuntimeException gone) {
            return null;
        }
    }

    private void openMenu(Camera camera, float x, float y) {
        var target = hovered;
        menuLight = null;
        menuActor = null;
        menuMesh = "";
        menuMaterial = "";
        menuPoint = null;
        if (target != null && target.sceneObject() != null && Float.isFinite(target.hitDistance())) {
            var io = ImGui.getIO();
            var nx = x / io.getDisplaySizeX() * 2 - 1;
            var ny = 1 - y / io.getDisplaySizeY() * 2;
            var origin = camera.getCursorRayOrigin(nx, ny);
            var direction = camera.getCursorRayDirection(nx, ny);
            var distance = target.hitDistance();
            menuPoint = new float[] {
                origin.getX() + direction.getX() * distance,
                origin.getY() + direction.getY() * distance,
                origin.getZ() + direction.getZ() * distance
            };
        }
        if (target != null
                && target.sceneObject() != null
                && target.sceneObject().category() == Category.STATIC_MESH
                && actors.containsKey(target.actor())) {
            menuActor = target.actor();
            menuMesh = actors.get(menuActor).mesh();
            menuMaterial = target.materialName();
            if (!session.getSelection().contains(menuActor)) {
                try {
                    session.select(menuActor, false);
                } catch (RuntimeException failure) {
                    panel.showError(failure.getMessage());
                    menuActor = null;
                }
            }
        }
        var primary = session.getPrimary();
        if (menuActor == null && panel.isGizmoHovered() && primary != null && actors.containsKey(primary)) {
            menuActor = primary;
            menuMesh = actors.get(primary).mesh();
            menuMaterial = actors.get(primary).materials().stream().findFirst().orElse("");
        }
        menuX = x;
        menuY = y;
        ImGui.openPopup(MENU);
    }

    private void drawActorMenu(String actor) {
        if (HudWidgets.menuRow("menuFocus", Glyph.FOCUS, "Focus", "F")) {
            panel.requestFocus();
        }
        if (HudWidgets.menuRow("menuDuplicate", Glyph.COPY, "Duplicate", COMMAND_KEY + "+D")) {
            panel.duplicate();
        }
        if (HudWidgets.menuRow("menuDelete", Glyph.CLOSE, "Delete", "Del")) {
            panel.deleteSelection();
        }
        HudWidgets.menuRule();
        if (HudWidgets.menuRow("menuCopy", Glyph.COPY, "Copy", COMMAND_KEY + "+C")) {
            panel.copySelection();
        }
        if (HudWidgets.menuRow("menuPasteHere", Glyph.COPY, "Paste", COMMAND_KEY + "+V", panel.canPaste())) {
            menuPasteX = menuX;
            menuPasteY = menuY;
            panel.requestPaste();
        }
        GeodataWidgets.tip(
                panel.canPaste()
                        ? "Paste the copied objects here, standing on the surface."
                        : "Nothing is copied yet.");
        HudWidgets.menuRule();
        if (HudWidgets.menuRow("menuSameMesh", Glyph.BOX, "Select all with this mesh", "")) {
            selectWhere(each -> each.mesh().equalsIgnoreCase(menuMesh));
        }
        GeodataWidgets.tip("Select every object of " + EditingSession.shortName(menuMesh) + " in the loaded maps.");
        if (HudWidgets.menuRow(
                "menuSameMaterial", Glyph.LAYERS, "Select all with this material", "", !menuMaterial.isBlank())) {
            selectWhere(each -> each.materials().contains(menuMaterial));
        }
        GeodataWidgets.tip(
                menuMaterial.isBlank()
                        ? "This part has no material name."
                        : "Select every object that draws " + menuMaterial + ".");
        HudWidgets.menuRule();
        if (HudWidgets.menuRow("menuHide", Glyph.EYE_OFF, "Hide", "H")) {
            panel.hideSelection();
        }
        if (HudWidgets.menuRow("menuIsolate", Glyph.FILTER, "Isolate", "Shift+H")) {
            panel.isolateSelection();
        }
        if (HudWidgets.menuRow(
                "menuShowAll",
                Glyph.EYE,
                "Show all",
                "Alt+H",
                !session.getHiddenActors().isEmpty())) {
            panel.showAll();
        }
        var locked = session.getLockedActors().contains(actor);
        if (HudWidgets.menuRow("menuLock", Glyph.LOCK, locked ? "Unlock" : "Lock", "L")) {
            panel.toggleLock();
        }
        HudWidgets.menuRule();
        if (HudWidgets.menuRow("menuDrop", Glyph.PLACE, "Drop to ground", "End")) {
            panel.requestDrop(false);
        }
        var pawns = session.getSelection().stream().anyMatch(EditorUi::isPawn);
        var nonStatic = session.getSelection().stream().anyMatch(session::isNonStatic);
        var effects = session.getSelection().stream().anyMatch(session::isEmitter);
        var tile = moveTile();
        if (tile != null && HudWidgets.menuRow("menuMoveTo", Glyph.GO, "Move to " + tile, "", !effects)) {
            panel.moveSelectionTo(tile);
        }
        if (tile != null && effects) {
            GeodataWidgets.tip(PackageEditDocument.EFFECT_KEEPS);
        }
        if (HudWidgets.menuRow("menuReplaceMesh", Glyph.BOX, "Replace mesh…", "", !pawns && !nonStatic && !effects)) {
            panel.requestReplaceMesh();
        }
        GeodataWidgets.tip(
                pawns
                        ? PackageEditDocument.PAWN_LOOK
                        : effects
                                ? PackageEditDocument.EFFECT_KEEPS
                                : nonStatic
                                        ? PackageEditDocument.MOVING_KEEPS
                                        : "Choose another mesh for the selection in the Library. Place, turn, size"
                                                + " and materials stay.");
        if (HudWidgets.menuRow("menuSavePrefab", Glyph.FILE, "Save as prefab…", "", !effects)) {
            panel.savePrefab();
        }
        GeodataWidgets.tip(
                effects
                        ? EditorUi.EFFECT_PREFAB
                        : "Save the selection as a prefab file. The Library lists the prefabs of its folder, and the"
                                + " Place tool places the whole group.");
        if (HudWidgets.menuRow("menuReset", Glyph.RELOAD, "Revert", "")) {
            panel.resetSelection();
        }
        GeodataWidgets.tip("Give the selected objects their placement and materials in the client back.");
    }

    private @Nullable String moveTile() {
        var actor = menuActor;
        if (actor == null) {
            return null;
        }
        try {
            return session.getTileOutsideMap(actor);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private void selectWhere(Predicate<Actor> test) {
        var result = new ArrayList<String>();
        var isolated = panel.getIsolatedActors();
        actors.forEach((path, actor) -> {
            if (test.test(actor) && IsolatedGroup.inView(isolated, path)) {
                result.add(path);
            }
        });
        select(result, false);
    }

    private void selectInView(Camera camera, float left, float top, float right, float bottom) {
        var io = ImGui.getIO();
        var result = new ArrayList<String>();
        var isolated = panel.getIsolatedActors();
        actors.forEach((path, actor) -> {
            if (IsolatedGroup.inView(isolated, path)
                    && intersects(
                            camera,
                            actor.bounds(),
                            (int) io.getDisplaySizeX(),
                            (int) io.getDisplaySizeY(),
                            left,
                            top,
                            right,
                            bottom)) {
                result.add(path);
            }
        });
        select(result, false);
    }

    private boolean place(
            Camera camera,
            float x,
            float y,
            int width,
            int height,
            boolean inside,
            float left,
            float top,
            float right,
            float bottom) {
        var io = ImGui.getIO();
        var nx = x / width * 2 - 1;
        var ny = 1 - y / height * 2;
        if (scattering) {
            updateScatter(camera, nx, ny, width, height, left, top, right, bottom);
            return true;
        }
        if (!inside || ImGui.isMouseDown(1) || io.getWantCaptureMouse()) {
            return false;
        }
        if (io.getKeyAlt() && ImGui.isMouseClicked(0) && !camera.isOrtho()) {
            startPress(x, y, false, false);
            return true;
        }
        var hit = backend.pickDebugSceneObject(nx, ny, Float.MAX_VALUE, EnumSet.allOf(Category.class));
        var draw = ImGui.getBackgroundDrawList();
        draw.pushClipRect(left, top, right, bottom, true);
        try {
            if (io.getKeyCtrl() || io.getKeySuper()) {
                var actor = hit == null || hit.object().category() != Category.STATIC_MESH
                        ? null
                        : hit.object().actorName();
                if (actor != null && actors.containsKey(actor)) {
                    drawHover(draw, camera, actor);
                    ImGui.setTooltip(
                            "Take " + EditingSession.shortName(actors.get(actor).mesh())
                                    + "\nClick to place more of this mesh");
                    if (ImGui.isMouseClicked(0)) {
                        panel.takeMeshOf(actor);
                    }
                }
                return true;
            }
            if (hit == null) {
                return true;
            }
            var origin = camera.getCursorRayOrigin(nx, ny);
            var direction = camera.getCursorRayDirection(nx, ny);
            var point = new Vector3(
                    origin.getX() + direction.getX() * hit.distance(),
                    origin.getY() + direction.getY() * hit.distance(),
                    origin.getZ() + direction.getZ() * hit.distance());
            if (panel.alignsToGround()) {
                probeNormal(camera, x, y, width, height);
            }
            var ghost = EditorUi.fill(ACCENT, .4f);
            if (panel.isScatterBrush()) {
                drawCircle(draw, camera, point, panel.getScatterSpacing(), width, height, ACCENT);
                if (ImGui.isMouseClicked(0)) {
                    startScatter(io.getKeyShift(), point);
                }
                return true;
            }
            var next = panel.nextPlacement(point);
            var bounds = panel.getPlaceBounds();
            if (next != null && bounds != null) {
                drawCorners(draw, camera, Placing.corners(next, bounds), width, height, ghost, 1);
            }
            for (var corners : panel.getPrefabGhost(point)) {
                drawCorners(draw, camera, corners, width, height, ghost, 1);
            }
            var foreground = ImGui.getForegroundDrawList();
            foreground.addCircle(x, y, 7, ACCENT, 20, 2);
            foreground.addCircleFilled(x, y, 2.5f, ACCENT);
            ImGui.setTooltip(panel.getPlaceName() + "\nClick to place it here");
            if (ImGui.isMouseClicked(0)) {
                panel.placeAt(point, this::baseZ, mapsAt(hit.object().owner()));
            }
            return true;
        } finally {
            draw.popClipRect();
        }
    }

    private boolean dragFromLibrary(
            Camera camera,
            LibraryDrag dragged,
            float x,
            float y,
            int width,
            int height,
            boolean inside,
            float left,
            float top,
            float right,
            float bottom) {
        var over = inside
                && !ImGui.isWindowHovered(ImGuiHoveredFlags.AnyWindow | ImGuiHoveredFlags.AllowWhenBlockedByActiveItem);
        Vector3 point = null;
        String owner = null;
        if (over) {
            var nx = x / width * 2 - 1;
            var ny = 1 - y / height * 2;
            var hit = backend.pickDebugSceneObject(nx, ny, Float.MAX_VALUE, EnumSet.allOf(Category.class));
            if (hit != null) {
                var origin = camera.getCursorRayOrigin(nx, ny);
                var direction = camera.getCursorRayDirection(nx, ny);
                point = new Vector3(
                        origin.getX() + direction.getX() * hit.distance(),
                        origin.getY() + direction.getY() * hit.distance(),
                        origin.getZ() + direction.getZ() * hit.distance());
                owner = hit.object().owner();
                if (panel.alignsToGround()) {
                    probeNormal(camera, x, y, width, height);
                }
                var draw = ImGui.getBackgroundDrawList();
                draw.pushClipRect(left, top, right, bottom, true);
                var ghost = EditorUi.fill(ACCENT, .4f);
                var next = panel.nextPlacement(point, dragged.mesh());
                var bounds = panel.placeBounds(dragged.mesh());
                if (next != null && bounds != null) {
                    drawCorners(draw, camera, Placing.corners(next, bounds), width, height, ghost, 1);
                }
                for (var corners : panel.getPrefabGhost(point, dragged.prefab())) {
                    drawCorners(draw, camera, corners, width, height, ghost, 1);
                }
                draw.popClipRect();
            }
        }
        var foreground = ImGui.getForegroundDrawList();
        var size = LIBRARY_PICTURE;
        var hint = point != null ? "Let go to place it here" : over ? "Let go over a surface" : "Let go over the view";
        var textWidth = Math.max(ImGui.calcTextSizeX(dragged.name()), ImGui.calcTextSizeX(hint));
        var cardLeft = x + 14;
        var cardTop = y + 14;
        var pad = GeodataWidgets.ROW;
        var cardRight = cardLeft + pad + size + pad + textWidth + pad;
        var cardBottom = cardTop + pad + size + pad;
        foreground.addRectFilled(cardLeft, cardTop, cardRight, cardBottom, HudWidgets.BACKGROUND, 8f);
        foreground.addRect(
                cardLeft,
                cardTop,
                cardRight,
                cardBottom,
                point != null ? ACCENT : GeodataWidgets.DIVIDER,
                8f,
                0,
                point != null ? 2 : 1);
        GeodataWidgets.picture(
                foreground,
                cardLeft + pad,
                cardTop + pad,
                size,
                dragged.texture(),
                dragged.prefab() != null ? Glyph.LAYERS : Glyph.BOX);
        var textLeft = cardLeft + pad + size + pad;
        var lineGap = (size - 2 * ImGui.getFontSize()) / 3;
        foreground.addText(textLeft, cardTop + pad + lineGap, HudWidgets.TEXT, dragged.name());
        foreground.addText(textLeft, cardTop + pad + 2 * lineGap + ImGui.getFontSize(), HudWidgets.MUTED, hint);
        if (!ImGui.isMouseDown(0)) {
            if (point != null) {
                panel.dropLibraryDrag(point, this::baseZ, mapsAt(owner));
            } else {
                panel.cancelLibraryDrag();
            }
        }
        return over;
    }

    private Set<String> mapsAt(String owner) {
        var maps = new HashSet<String>();
        maps.add(session.getMapFiles().getSource(owner));
        maps.addAll(getLoadedMaps());
        return maps;
    }

    private void startScatter(boolean erase, Vector3 point) {
        scattering = true;
        scatterErase = erase;
        scatterPoints.clear();
        scatterNormals.clear();
        eraseTargets.clear();
        stroke = new Stroke(panel.getScatterSpacing(), panel.getScatterJitter(), scatterSeed++);
        addScatterPoint(point);
    }

    private void updateScatter(
            Camera camera,
            float nx,
            float ny,
            int width,
            int height,
            float left,
            float top,
            float right,
            float bottom) {
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            cancelGesture();
            return;
        }
        var hit = backend.pickDebugSceneObject(
                nx, ny, Float.MAX_VALUE, EnumSet.of(Category.TERRAIN, Category.CSG, Category.STATIC_MESH));
        var draw = ImGui.getBackgroundDrawList();
        draw.pushClipRect(left, top, right, bottom, true);
        if (hit != null) {
            var origin = camera.getCursorRayOrigin(nx, ny);
            var direction = camera.getCursorRayDirection(nx, ny);
            var point = new Vector3(
                    origin.getX() + direction.getX() * hit.distance(),
                    origin.getY() + direction.getY() * hit.distance(),
                    origin.getZ() + direction.getZ() * hit.distance());
            addScatterPoint(point);
            drawCircle(
                    draw,
                    camera,
                    point,
                    panel.getScatterSpacing(),
                    width,
                    height,
                    scatterErase ? EditorUi.ERROR : ACCENT);
        }
        var ghost = EditorUi.fill(ACCENT, .4f);
        var bounds = panel.getPlaceBounds();
        for (var p : scatterPoints) {
            if (bounds != null) {
                var t = Placing.standing(
                        new ActorTransform(
                                new Vector3(p[0], p[1], p[2]), new Rotator(0, 0, 0), 1, Vector3.ONE, Vector3.ZERO),
                        bounds,
                        p[2]);
                drawCorners(draw, camera, Placing.corners(t, bounds), width, height, ghost, 1);
            } else {
                var screen = camera.projectToScreenRaw(new Vec3(p[0], p[1], p[2]), width, height);
                if (screen != null) {
                    draw.addCircleFilled(screen.getX(), screen.getY(), 3, ACCENT);
                }
            }
        }
        for (var path : eraseTargets) {
            var actor = actors.get(path);
            if (actor != null) {
                drawBox(draw, camera, actor.bounds(), width, height, EditorUi.ERROR, 2);
            }
        }
        draw.popClipRect();
        if (!ImGui.isMouseDown(0)) {
            finishScatter(camera);
        }
    }

    private void addScatterPoint(Vector3 point) {
        var mesh = panel.getPlaceMesh();
        if (mesh == null || stroke == null) {
            return;
        }
        if (scatterErase) {
            var radius = panel.getScatterSpacing();
            actors.forEach((path, actor) -> {
                var b = actor.bounds();
                if (actor.mesh().equalsIgnoreCase(mesh.meshPath())
                        && !session.getLockedActors().contains(path)
                        && !session.getHiddenActors().contains(path)
                        && Math.hypot((b.minX() + b.maxX()) * .5f - point.x(), (b.minY() + b.maxY()) * .5f - point.y())
                                <= radius) {
                    eraseTargets.add(path);
                }
            });
            return;
        }
        for (var stamp : stroke.add(point.x(), point.y(), point.z())) {
            scatterPoints.add(stamp);
            scatterNormals.add(pointerNormal == null ? null : pointerNormal.clone());
        }
    }

    private void finishScatter(Camera camera) {
        scattering = false;
        if (scatterErase) {
            var targets = List.copyOf(eraseTargets);
            eraseTargets.clear();
            panel.erase(targets);
            return;
        }
        if (scatterPoints.isEmpty()) {
            return;
        }
        var ndc = new ArrayList<Float>();
        var indices = new ArrayList<Integer>();
        for (var i = 0; i < scatterPoints.size() && indices.size() < ViewerRenderBackend.RAY_POINTS; i++) {
            var p = scatterPoints.get(i);
            var projected = camera.projectToNdc(new Vec3(p[0], p[1], p[2]));
            if (projected != null && Math.abs(projected.getX()) <= 1 && Math.abs(projected.getY()) <= 1) {
                ndc.add(projected.getX());
                ndc.add(projected.getY());
                indices.add(i);
            }
        }
        if (indices.isEmpty()) {
            commitScatter();
            return;
        }
        var values = new float[ndc.size()];
        for (var i = 0; i < values.length; i++) {
            values[i] = ndc.get(i);
        }
        var job = new PointsJob(values, List.of());
        job.stamps = List.copyOf(scatterPoints);
        job.indices = indices.stream().mapToInt(Integer::intValue).toArray();
        scatterJob = job;
    }

    private void updateScatterJob(Camera camera) {
        var job = scatterJob;
        if (job == null) {
            return;
        }
        var distances = backend.getSceneRayDistances(job.ndc, FAR);
        if (distances == null) {
            if (++job.frames > ANSWER_FRAMES) {
                scatterJob = null;
                commitScatter();
            }
            return;
        }
        scatterJob = null;
        for (var k = 0; k < job.indices.length && k < distances.length; k++) {
            if (!Float.isFinite(distances[k])) {
                continue;
            }
            var origin = camera.getCursorRayOrigin(job.ndc[k * 2], job.ndc[k * 2 + 1]);
            var direction = camera.getCursorRayDirection(job.ndc[k * 2], job.ndc[k * 2 + 1]);
            var point = scatterPoints.get(job.indices[k]);
            point[2] = origin.getZ() + direction.getZ() * distances[k];
        }
        commitScatter();
    }

    private void commitScatter() {
        var points = List.copyOf(scatterPoints);
        var normals = new ArrayList<>(scatterNormals);
        scatterPoints.clear();
        scatterNormals.clear();
        var maps = new HashSet<>(getLoadedMaps());
        var files = session.getMapFiles();
        for (var p : points) {
            var tile = WorldTile.at(p[0], p[1]);
            var map = files.getSource(tile.x() + "_" + tile.y());
            if (residentMaps.contains(map)) {
                maps.add(map);
            }
        }
        panel.scatter(points, normals, this::baseZ, maps);
    }

    private void probeNormal(Camera camera, float x, float y, int width, int height) {
        if (scatterJob != null || rectangleJob != null || dropJob != null) {
            return;
        }
        if (normalJob == null) {
            if (Math.hypot(x - normalX, y - normalY) < 3) {
                return;
            }
            var ndc = new float[18];
            var index = 0;
            for (var dy = -1; dy <= 1; dy++) {
                for (var dx = -1; dx <= 1; dx++) {
                    ndc[index++] = (x + dx * 14) / width * 2 - 1;
                    ndc[index++] = 1 - (y + dy * 14) / height * 2;
                }
            }
            normalJob = new PointsJob(ndc, List.of());
            normalX = x;
            normalY = y;
        }
        var job = normalJob;
        var distances = backend.getSceneRayDistances(job.ndc, FAR);
        if (distances == null) {
            if (++job.frames > ANSWER_FRAMES) {
                normalJob = null;
            }
            return;
        }
        normalJob = null;
        var hits = new ArrayList<float[]>();
        float cx = Float.NaN, cy = Float.NaN;
        for (var i = 0; i < distances.length; i++) {
            if (!Float.isFinite(distances[i])) {
                continue;
            }
            var origin = camera.getCursorRayOrigin(job.ndc[i * 2], job.ndc[i * 2 + 1]);
            var direction = camera.getCursorRayDirection(job.ndc[i * 2], job.ndc[i * 2 + 1]);
            var hit = new float[] {
                origin.getX() + direction.getX() * distances[i],
                origin.getY() + direction.getY() * distances[i],
                origin.getZ() + direction.getZ() * distances[i]
            };
            hits.add(hit);
            if (i == 4) {
                cx = hit[0];
                cy = hit[1];
            }
        }
        if (hits.size() < 3 || !Float.isFinite(cx)) {
            return;
        }
        var ground = GroundFit.fit(hits, cx, cy);
        pointerNormal = ground == null ? null : ground.normal();
        panel.setPointerNormal(pointerNormal);
    }

    private boolean handleEyedropper(Camera camera, float x, float y, int width, int height, boolean inside) {
        if (!panel.isEyedropperArmed() || !inside || ImGui.isWindowHovered(ImGuiHoveredFlags.AnyWindow)) {
            return false;
        }
        var alt = ImGui.getIO().getKeyAlt();
        if (panel.doesEyedropperNeedAlt() && !alt) {
            return false;
        }
        var surfaces = panel.isSurfaceEyedropper();
        var hit = backend.pickDebugSceneObject(
                x / width * 2 - 1,
                1 - y / height * 2,
                Float.MAX_VALUE,
                surfaces ? EnumSet.of(Category.STATIC_MESH, Category.CSG) : EnumSet.of(Category.STATIC_MESH));
        if (surfaces && hit != null && hit.object().category() == Category.CSG) {
            ImGui.setTooltip(hit.object().materialName() + "\nClick to take the material of this surface");
            if (!ImGui.isMouseClicked(0)) {
                return !panel.doesEyedropperNeedAlt();
            }
            var origin = camera.getCursorRayOrigin(x / width * 2 - 1, 1 - y / height * 2);
            var direction = camera.getCursorRayDirection(x / width * 2 - 1, 1 - y / height * 2);
            var point = new Vector3(
                    origin.getX() + direction.getX() * hit.distance(),
                    origin.getY() + direction.getY() * hit.distance(),
                    origin.getZ() + direction.getZ() * hit.distance());
            try {
                panel.takeSurfaceMaterial(session.pickSurface(hit.object().owner(), point));
            } catch (RuntimeException failure) {
                panel.showError(failure.getMessage());
            }
            return true;
        }
        var actor = hit == null ? null : hit.object().actorName();
        if (actor != null && actors.containsKey(actor)) {
            var draw = ImGui.getBackgroundDrawList();
            drawHover(draw, camera, actor);
            ImGui.setTooltip(EditingSession.shortName(actor) + " · "
                    + hit.object().materialName() + "\nClick to take its material");
        }
        if (!ImGui.isMouseClicked(0)) {
            return !panel.doesEyedropperNeedAlt();
        }
        if (actor == null || !actors.containsKey(actor)) {
            panel.showError(
                    surfaces
                            ? "Click a surface or an object to take its material."
                            : "Click an object to take its material.");
            return true;
        }
        panel.takeMaterial(actor, hit.object().surfaceOrdinal(), hit.object().materialName());
        return true;
    }

    private void paste(Camera camera, float x, float y, boolean inside, int width, int height) {
        if (Float.isFinite(menuPasteX)) {
            x = menuPasteX;
            y = menuPasteY;
            inside = true;
            menuPasteX = Float.NaN;
        }
        var nx = inside ? x / width * 2 - 1 : 0;
        var ny = inside ? 1 - y / height * 2 : 0;
        var origin = camera.getCursorRayOrigin(nx, ny);
        var direction = camera.getCursorRayDirection(nx, ny);
        var hit = backend.pickDebugSceneObject(
                nx, ny, Float.MAX_VALUE, EnumSet.of(Category.TERRAIN, Category.CSG, Category.STATIC_MESH));
        var distance = hit == null ? 1024 : hit.distance();
        var point = new Vector3(
                origin.getX() + direction.getX() * distance,
                origin.getY() + direction.getY() * distance,
                origin.getZ() + direction.getZ() * distance);
        var maps = new HashSet<>(getLoadedMaps());
        if (hit != null) {
            maps.add(session.getMapFiles().getSource(hit.object().owner()));
        }
        panel.paste(point, maps);
    }

    private void resolveDrop(int width, int height) {
        var next = panel.consumeDrop();
        if (next != null) {
            drop = next;
            dropFrames = 0;
        }
        var pending = drop;
        if (pending == null) {
            return;
        }
        var hit = backend.pickDebugSceneObject(
                pending.x() / width * 2 - 1,
                1 - pending.y() / height * 2,
                Float.MAX_VALUE,
                EnumSet.of(Category.STATIC_MESH));
        if (hit != null && actors.containsKey(hit.object().actorName())) {
            drop = null;
            panel.openImport(
                    pending.image(),
                    List.of(hit.object().actorName()),
                    hit.object().surfaceOrdinal());
        } else if (++dropFrames > ANSWER_FRAMES) {
            drop = null;
            panel.showError("Drop the image on an object in the viewport or on a slot of the Materials part.");
        }
    }

    private Float baseZ(String actor) {
        Float bottom = null;
        for (var object : backend.getEditorActorObjects(Set.of(actor))) {
            bottom = bottom == null
                    ? object.bounds().minZ()
                    : Math.min(bottom, object.bounds().minZ());
        }
        return bottom;
    }

    private void drawGhost(ImDrawList draw, Camera camera, String path, int width, int height) {
        var actor = actors.get(path);
        var source = session.getSourceTransform(path);
        if (actor == null || source == null) {
            return;
        }
        var local = actor.mesh().isBlank() ? null : getLocalBounds(actor.mesh());
        if (local != null) {
            drawCorners(draw, camera, Placing.corners(source, local), width, height, HudWidgets.BORDER, 1);
            return;
        }
        var now = session.getActor(path).transform().location();
        var then = source.location();
        var b = actor.bounds();
        var dx = then.x() - now.x();
        var dy = then.y() - now.y();
        var dz = then.z() - now.z();
        worldBox(
                draw,
                camera,
                new Bounds3f(b.minX() + dx, b.minY() + dy, b.minZ() + dz, b.maxX() + dx, b.maxY() + dy, b.maxZ() + dz),
                width,
                height,
                HudWidgets.BORDER,
                1);
    }

    private void worldBox(
            ImDrawList draw, Camera camera, Bounds3f box, int width, int height, int color, float thickness) {
        if (worldLines == null) {
            drawBox(draw, camera, box, width, height, color, thickness);
            return;
        }
        worldLines.addBox(box, color, thickness);
    }

    private void refreshAll() {
        var bounds = new LinkedHashMap<String, Bounds3f>();
        var meshes = new LinkedHashMap<String, String>();
        var materials = new LinkedHashMap<String, Set<String>>();
        var moving = new HashMap<String, Boolean>();
        effects.clear();
        for (var object : backend.getDebugSceneObjects()) {
            if (object.category() == Category.STATIC_MESH) {
                if (EditorEffectStage.isEffectRow(object)) {
                    effects.add(object.actorName());
                }
                bounds.merge(object.actorName(), object.bounds(), EditorViewport::union);
                moving.merge(object.actorName(), object.actorAnimated(), Boolean::logicalOr);
                meshes.putIfAbsent(object.actorName(), object.assetName());
                materials
                        .computeIfAbsent(object.actorName(), ignored -> new LinkedHashSet<>())
                        .add(object.materialName());
            }
        }
        var next = new LinkedHashMap<String, Actor>();
        bounds.forEach((path, box) -> next.put(
                path,
                new Actor(box, meshes.get(path), Set.copyOf(materials.get(path)), moving.getOrDefault(path, false))));
        actors = next;
        actorsVersion++;
        countMeshes();
        localBounds.clear();
    }

    private void refreshActors(Set<String> changed) {
        if (changed.isEmpty()) {
            return;
        }
        var bounds = new HashMap<String, Bounds3f>();
        var meshes = new HashMap<String, String>();
        var materials = new HashMap<String, Set<String>>();
        var moving = new HashMap<String, Boolean>();
        effects.removeAll(changed);
        for (var object : backend.getEditorActorObjects(changed)) {
            if (object.category() == Category.STATIC_MESH) {
                if (EditorEffectStage.isEffectRow(object)) {
                    effects.add(object.actorName());
                }
                bounds.merge(object.actorName(), object.bounds(), EditorViewport::union);
                moving.merge(object.actorName(), object.actorAnimated(), Boolean::logicalOr);
                meshes.putIfAbsent(object.actorName(), object.assetName());
                materials
                        .computeIfAbsent(object.actorName(), ignored -> new LinkedHashSet<>())
                        .add(object.materialName());
            }
        }
        for (var path : changed) {
            var box = bounds.get(path);
            var old = box == null
                    ? actors.remove(path)
                    : actors.put(
                            path,
                            new Actor(
                                    box,
                                    meshes.get(path),
                                    Set.copyOf(materials.get(path)),
                                    moving.getOrDefault(path, false)));
            if (old != null) {
                countMesh(old.mesh(), -1);
                localBounds.remove(old.mesh());
            }
            if (box != null) {
                countMesh(meshes.get(path), 1);
                localBounds.remove(meshes.get(path));
            }
        }
        actorsVersion++;
    }

    private void countMesh(String mesh, int change) {
        if (!mesh.isBlank()) {
            meshCounts.merge(mesh, change, (count, delta) -> count + delta == 0 ? null : count + delta);
        }
    }

    private void countMeshes() {
        var counts = new TreeMap<String, Integer>(String.CASE_INSENSITIVE_ORDER);
        for (var actor : actors.values()) {
            if (!actor.mesh().isBlank()) {
                counts.merge(actor.mesh(), 1, Integer::sum);
            }
        }
        meshCounts = counts;
    }

    private static boolean measureKeyDown() {
        return ImGui.isKeyDown(ImGuiKey.M) && !ImGui.getIO().getWantTextInput();
    }

    private static float[] rayPoint(Camera camera, float ndcX, float ndcY, float distance) {
        var origin = camera.getCursorRayOrigin(ndcX, ndcY);
        var direction = camera.getCursorRayDirection(ndcX, ndcY);
        return new float[] {
            origin.getX() + direction.getX() * distance,
            origin.getY() + direction.getY() * distance,
            origin.getZ() + direction.getZ() * distance
        };
    }

    private static void segment(
            ImDrawList draw,
            Camera camera,
            float ax,
            float ay,
            float az,
            float bx,
            float by,
            float bz,
            int width,
            int height,
            int color,
            float thickness) {
        var a = project(camera, ax, ay, az, width, height);
        var b = project(camera, bx, by, bz, width, height);
        if (a != null && b != null) {
            draw.addLine(a.getX(), a.getY(), b.getX(), b.getY(), color, thickness);
        }
    }

    private static int faint(int color) {
        return color & 0x00ffffff | 0x80 << 24;
    }

    private static float footprint(Bounds3f bounds) {
        return Math.max(16, (float) Math.hypot(bounds.maxX() - bounds.minX(), bounds.maxY() - bounds.minY()) * .5f);
    }

    private static void drawBox(
            ImDrawList draw, Camera camera, Bounds3f box, int width, int height, int color, float thickness) {
        var corners = projectCorners(camera, box, width, height);
        for (var edge : EDGES) {
            var a = corners[edge[0]];
            var b = corners[edge[1]];
            if (a != null && b != null) {
                draw.addLine(a.getX(), a.getY(), b.getX(), b.getY(), color, thickness);
            }
        }
    }

    private static void drawCorners(
            ImDrawList draw, Camera camera, float[][] corners, int width, int height, int color, float thickness) {
        var screen = new Vec2[8];
        for (var i = 0; i < 8; i++) {
            screen[i] = project(camera, corners[i][0], corners[i][1], corners[i][2], width, height);
        }
        for (var edge : EDGES) {
            var a = screen[edge[0]];
            var b = screen[edge[1]];
            if (a != null && b != null) {
                draw.addLine(a.getX(), a.getY(), b.getX(), b.getY(), color, thickness);
            }
        }
    }

    private static void drawCircle(
            ImDrawList draw, Camera camera, Vector3 center, float radius, int width, int height, int color) {
        Vec2 previous = null;
        for (var step = 0; step <= 48; step++) {
            var angle = Math.PI * 2 * step / 48;
            var point = project(
                    camera,
                    center.x() + (float) Math.cos(angle) * radius,
                    center.y() + (float) Math.sin(angle) * radius,
                    center.z(),
                    width,
                    height);
            if (previous != null && point != null) {
                draw.addLine(previous.getX(), previous.getY(), point.getX(), point.getY(), color, 2);
            }
            previous = point;
        }
    }

    private static float @Nullable [] projectedRect(Camera camera, Bounds3f bounds, int width, int height) {
        var minX = Float.POSITIVE_INFINITY;
        var minY = Float.POSITIVE_INFINITY;
        var maxX = Float.NEGATIVE_INFINITY;
        var maxY = Float.NEGATIVE_INFINITY;
        for (var corner : projectCorners(camera, bounds, width, height)) {
            if (corner != null) {
                minX = Math.min(minX, corner.getX());
                minY = Math.min(minY, corner.getY());
                maxX = Math.max(maxX, corner.getX());
                maxY = Math.max(maxY, corner.getY());
            }
        }
        return minX > maxX ? null : new float[] {minX, minY, maxX, maxY};
    }

    private static float @Nullable [] rayBox(Vec3 origin, Vec3 direction, Bounds3f box, float margin) {
        var near = Float.NEGATIVE_INFINITY;
        var far = Float.POSITIVE_INFINITY;
        float[] o = {origin.getX(), origin.getY(), origin.getZ()};
        float[] d = {direction.getX(), direction.getY(), direction.getZ()};
        float[] lo = {box.minX() - margin, box.minY() - margin, box.minZ() - margin};
        float[] hi = {box.maxX() + margin, box.maxY() + margin, box.maxZ() + margin};
        for (var axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) < 1e-8f) {
                if (o[axis] < lo[axis] || o[axis] > hi[axis]) {
                    return null;
                }
                continue;
            }
            var t1 = (lo[axis] - o[axis]) / d[axis];
            var t2 = (hi[axis] - o[axis]) / d[axis];
            near = Math.max(near, Math.min(t1, t2));
            far = Math.min(far, Math.max(t1, t2));
        }
        return far < near || far < 0 ? null : new float[] {Math.max(0, near), far};
    }

    private static Vec2[] projectCorners(Camera camera, Bounds3f box, int width, int height) {
        var result = new Vec2[8];
        for (var i = 0; i < 8; i++) {
            var x = (i & 1) == 0 ? box.minX() : box.maxX();
            var y = (i & 2) == 0 ? box.minY() : box.maxY();
            var z = (i & 4) == 0 ? box.minZ() : box.maxZ();
            result[i] = project(camera, x, y, z, width, height);
        }
        return result;
    }

    private static @Nullable Vec2 project(Camera camera, float x, float y, float z, int width, int height) {
        var view = camera.getViewMatrix();
        var projection = camera.getProjectionMatrix();
        var vx = view.get(0) * x + view.get(4) * y + view.get(8) * z + view.get(12);
        var vy = view.get(1) * x + view.get(5) * y + view.get(9) * z + view.get(13);
        var vz = view.get(2) * x + view.get(6) * y + view.get(10) * z + view.get(14);
        var clipZ = projection.get(2) * vx + projection.get(6) * vy + projection.get(10) * vz + projection.get(14);
        var w = projection.get(3) * vx + projection.get(7) * vy + projection.get(11) * vz + projection.get(15);
        if (w <= 0 || clipZ < 0 || clipZ > w) {
            return null;
        }
        var clipX = projection.get(0) * vx + projection.get(4) * vy + projection.get(8) * vz + projection.get(12);
        var clipY = projection.get(1) * vx + projection.get(5) * vy + projection.get(9) * vz + projection.get(13);
        return new Vec2((clipX / w + 1) * width / 2, (1 - clipY / w) * height / 2);
    }

    private static Bounds3f union(Bounds3f a, Bounds3f b) {
        return new Bounds3f(
                Math.min(a.minX(), b.minX()),
                Math.min(a.minY(), b.minY()),
                Math.min(a.minZ(), b.minZ()),
                Math.max(a.maxX(), b.maxX()),
                Math.max(a.maxY(), b.maxY()),
                Math.max(a.maxZ(), b.maxZ()));
    }
}
