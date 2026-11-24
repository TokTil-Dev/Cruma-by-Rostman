package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataAccess.Layer;
import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.core.geodata.GeoDataReader;
import com.rost.cruma.core.geodata.GeoDataSnapshot;
import com.rost.cruma.core.geodata.GeoDataWriter;
import com.rost.cruma.core.geodata.GeoEditDocument.CellKey;
import com.rost.cruma.core.geodata.GeoFormat;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.GeoQueryEngine;
import com.rost.cruma.core.geodata.GeoQueryEngine.Direction;
import com.rost.cruma.core.geodata.GeoQueryEngine.Failure;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.application.WindowContext.Keyboard;
import com.rost.cruma.viewer.application.WindowContext.Keyboard.Key;
import com.rost.cruma.viewer.config.ClientProfiles;
import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.config.ConfigLoader.Options;
import com.rost.cruma.viewer.config.OptionsModel;
import com.rost.cruma.viewer.config.UserPaths;
import com.rost.cruma.viewer.geodata.GeoBookmarkStore;
import com.rost.cruma.viewer.geodata.GeoClientRebuild;
import com.rost.cruma.viewer.geodata.GeoDataDisplay;
import com.rost.cruma.viewer.geodata.GeoDataDisplay.Mode;
import com.rost.cruma.viewer.geodata.GeoDataFocusMode;
import com.rost.cruma.viewer.geodata.GeoDataMesh;
import com.rost.cruma.viewer.geodata.GeoDataPicker;
import com.rost.cruma.viewer.geodata.GeoDataPicker.Hit;
import com.rost.cruma.viewer.geodata.GeoEditingSession;
import com.rost.cruma.viewer.geodata.GeoEditingSession.Clip;
import com.rost.cruma.viewer.geodata.GeoInspection;
import com.rost.cruma.viewer.geodata.GeoInspection.Selection;
import com.rost.cruma.viewer.geodata.GeoRegionCatalog;
import com.rost.cruma.viewer.geodata.GeoRegionCatalog.Catalog;
import com.rost.cruma.viewer.launch.FileManager;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.GeodataState;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import com.rost.cruma.viewer.spawn.SpawnStyle;
import com.rost.cruma.viewer.ui.GeodataDiagnosticsPanel.ViewMode;
import com.rost.cruma.viewer.ui.GeodataEditingPanel.AreaAction;
import com.rost.cruma.viewer.ui.GeodataEditingPanel.EditTool;
import com.rost.cruma.viewer.ui.GeodataEditingPanel.Leave;
import com.rost.cruma.viewer.ui.GeodataMapLayer.Action;
import com.rost.cruma.viewer.ui.GeodataMapLayer.Request;
import com.rost.cruma.viewer.ui.GeodataSpawnLink.SpawnMark;
import com.rost.cruma.viewer.ui.GeodataWidgets.ToolTab;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.MarkerDepth.Placed;
import com.rost.cruma.viewer.ui.MarkerDepth.Projector;
import glm_.vec3.Vec3;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImInt;
import imgui.type.ImString;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class GeodataWorkspace implements AutoCloseable {
    static final int SPAWN_MARKS = 2000;

    private static final float SPAWN_LIFT = 8;

    private static final float SPAWN_FADE = 8192;

    private static final float DRAG_PIXELS = 6;

    private static final String UNDO_KEY =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac") ? "Cmd+Z" : "Ctrl+Z";
    private final Path configDirectory;
    private final @Nullable Path clientPath;
    private final GeoRegionCatalog regionCatalogs = new GeoRegionCatalog();
    private GeodataSpawnLink spawnLink = GeodataSpawnLink.NONE;
    private boolean showSpawns = true;
    private List<SpawnMark> spawnMarks = List.of();
    private @Nullable GeoPoint spawnMarksAt;
    private long spawnMarksTime;
    private final GeodataMapLayer mapLayer = new GeodataMapLayer(this);
    private final GeoClientRebuild rebuilder;
    private final ArrayDeque<Request> regionRequests = new ArrayDeque<>();
    private final ExecutorService regionWorker = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "geodata-region-actions");
        thread.setDaemon(true);
        return thread;
    });
    private @Nullable CompletableFuture<Runnable> regionWork;
    private @Nullable GeodataState lastState;
    private @Nullable Camera lastCamera;
    private long editedRevision = Long.MIN_VALUE;
    private Map<GeoTile, Integer> editedByTile = Map.of();
    private final GeodataRoutes routes = new GeodataRoutes();
    private final GeodataEditingPanel editing = new GeodataEditingPanel();
    private final GeodataSurfacePanel areas = new GeodataSurfacePanel();
    private final GeodataDiagnosticsPanel diagnostics = new GeodataDiagnosticsPanel();
    private final GeodataScenarioPanel scenarios = new GeodataScenarioPanel();
    private final GeodataScenarioSuitePanel suite = new GeodataScenarioSuitePanel();
    private final FolderPicker folders = new FolderPicker();
    private final GeodataBuildControls controls = new GeodataBuildControls();
    private boolean reloadForBuild;
    private final ImString typedPath = new ImString(4096);
    private final ImString goToText = new ImString(64);
    private Selection goToFilledFrom;
    private boolean goToEditing;
    private double[] pendingGo;
    private long pendingGoUntil;
    private final GeoBookmarkStore bookmarkStore;
    private Path bookmarksFolder;
    private final ImInt rangeInput = new ImInt();
    private final float[] number = new float[1];
    private final int[] integer = new int[1];
    private Tool tool = Tool.INSPECT;
    private boolean advancedOpen;
    private GeoPoint areaStart;
    private GeoPoint areaEnd;
    private boolean areaDragging;
    private @Nullable Bounds area;
    private @Nullable Selection areaSeed;
    private @Nullable AreaStats areaStats;
    private final ImInt areaHeight = new ImInt(Integer.MIN_VALUE);
    private String areaMessage = "";
    private Clip clip;
    private GeoPoint hoverPoint;
    private final ArrayList<float[]> hintAreas = new ArrayList<>();
    private Selection dragFrom;
    private float dragX;
    private float dragY;
    private boolean dragging;
    private Path remembered;
    private boolean rememberedKnown;
    private boolean applyTyped;
    private boolean sliceActive;
    private String sourceMessage = "";
    private int sourceMessageColor = GeodataWidgets.MUTED;

    private @Nullable Path sourceMessageFolder;

    public GeodataWorkspace(Path configDirectory, @Nullable Path clientPath) {
        this(configDirectory, clientPath, WorldVariant.MAIN);
    }

    public GeodataWorkspace(Path configDirectory, @Nullable Path clientPath, WorldVariant variant) {
        this.configDirectory = configDirectory;
        bookmarkStore = new GeoBookmarkStore(configDirectory.resolve("geodata-bookmarks.txt"));
        this.clientPath = clientPath;
        diagnostics.setEditor(editing);
        rebuilder = new GeoClientRebuild(clientPath, variant);
        controls.setVariant(variant);
        editing.setRebuilder(rebuilder);
    }

    public enum Tool {
        INSPECT("Inspect", Glyph.CURSOR, "Q"),
        ROUTE("Route", Glyph.ROUTE, "R"),
        EDIT("Edit", Glyph.PENCIL, "B"),
        CHECK("Check", Glyph.SHIELD, "C");

        final String label;
        final Glyph glyph;
        final String key;

        Tool(String label, Glyph glyph, String key) {
            this.label = label;
            this.glyph = glyph;
            this.key = key;
        }
    }

    public record Viewport(float left, float top, float right, float bottom) {
        boolean contains(float x, float y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }

    static double[] parseCoordinates(String text) {
        var parts = text.trim().split("[,;\\s]+");
        if (parts.length < 2 || parts.length > 3) {
            return null;
        }
        var result = new double[parts.length];
        try {
            for (var index = 0; index < parts.length; index++) {
                result[index] = Double.parseDouble(parts[index]);
                if (!Double.isFinite(result[index])) {
                    return null;
                }
            }
        } catch (NumberFormatException notNumber) {
            return null;
        }
        return result;
    }

    public static void focus(Camera camera, GeoPoint point) {
        if (camera.isOrtho()) {
            camera.setPosition(
                    (float) point.x(),
                    (float) point.y(),
                    Math.max(camera.getPosition().getZ(), (float) point.z() + 256));
            return;
        }
        var look = camera.getPerspectiveLookDir();
        var yaw = Math.toDegrees(Math.atan2(look.getY(), look.getX()));
        var pitch = Math.toDegrees(Math.asin(Math.clamp(look.getZ(), -1, 1)));
        camera.setDirection(yaw, Math.min(pitch, -35));
        var direction = camera.getCursorRayDirection(0, 0);
        var distance = 560f;
        camera.setPosition(
                (float) point.x() - direction.getX() * distance,
                (float) point.y() - direction.getY() * distance,
                (float) point.z() - direction.getZ() * distance);
    }

    public void setSpawnLink(GeodataSpawnLink link) {
        spawnLink = link;
        editing.setSpawns(link);
        routes.setSpawns(link);
    }

    public MapLayer getMapLayer() {
        return mapLayer;
    }

    @Nullable
    public long getRegionModified(String tile) {
        var catalog = getRegionCatalog();
        if (catalog == null) {
            return -1;
        }
        var parts = tile.split("_");
        try {
            var x = Integer.parseInt(parts[0]);
            var y = Integer.parseInt(parts[1]);
            if (parts.length != 2 || x < 10 || x > 29 || y < 10 || y > 26) {
                return 0;
            }
            var region = catalog.region(new GeoTile(x, y));
            return region.isPresent() ? region.modified() : 0;
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException notATile) {
            return 0;
        }
    }

    Catalog getRegionCatalog() {
        var catalog = regionCatalogs.getCatalog();
        var state = lastState;
        return catalog != null && state != null && GeodataBuildControls.isSameFolder(catalog.folder(), state.path)
                ? catalog
                : null;
    }

    int getEditedCells(GeoTile tile) {
        var state = lastState;
        if (state == null || state.editing == null) {
            return 0;
        }
        var document = state.editing.getDocument();
        if (editedRevision != document.revision()) {
            editedRevision = document.revision();
            var counts = new HashMap<GeoTile, Integer>();
            for (var key : document.getChangedCells().keySet()) {
                counts.merge(GeoTile.fromWorld(key.x(), key.y()), 1, Integer::sum);
            }
            editedByTile = counts;
        }
        return editedByTile.getOrDefault(tile, 0);
    }

    boolean hasUnsavedEdits() {
        var state = lastState;
        return state != null && state.editing != null && state.editing.hasUnsavedChanges();
    }

    float getCameraHeight() {
        return lastCamera == null ? 0 : lastCamera.getPosition().getZ();
    }

    String getUnavailable(Action action, GeoTile tile) {
        var state = lastState;
        var catalog = getRegionCatalog();
        if (state == null || state.path == null || catalog == null) {
            return "Choose a geodata folder first.";
        }
        if (editing.getBusyLabel() != null) {
            return "Wait until the files are written.";
        }
        var loaded =
                state.getSourceSnapshot() != null && state.getSourceSnapshot().getRegion(tile.x(), tile.y()) != null;
        var name = tile.x() + "_" + tile.y();
        var present = catalog.region(tile).isPresent();
        if ((action == Action.REBUILD || action == Action.TAKE_FROM_B) && !present) {
            return "This folder has no file for "
                    + name
                    + " to change. "
                    + (action == Action.REBUILD
                            ? "Build writes the missing regions."
                            : "Copy the file from folder B instead.");
        }
        return switch (action) {
            case REBUILD ->
                clientPath == null
                        ? "Rebuild needs the client: open a client to build from its maps."
                        : !loaded ? "Fly to " + name + " first, so it loads for editing." : "";
            case REVERT -> getEditedCells(tile) == 0 ? name + " has no edits." : "";
            case TAKE_FROM_B ->
                diagnostics.getComparisonFolder() == null
                        ? "Choose folder B first: Check, Compare another source."
                        : !catalog.compared()
                                ? "Folder B is still being compared."
                                : !catalog.differs().contains(tile)
                                        ? "Folder B has the same file."
                                        : !loaded ? "Fly to " + name + " first, so it loads for editing." : "";
            case EXPORT ->
                !catalog.region(tile).isPresent() && getEditedCells(tile) == 0
                        ? name + " has no file to export."
                        : editing.exportFolder(state) == null ? "Set the folder under Save and export first." : "";
            case OPEN_FOLDER -> "";
        };
    }

    void requestRegion(Request request) {
        regionRequests.add(request);
    }

    public Tool getTool() {
        return tool;
    }

    public void setTool(Tool next) {
        if (tool != next) {
            editing.cancelInteraction();
        }
        tool = next;
    }

    public void drawPanel(GeodataState state, Camera camera, ViewerRenderBackend backend) {
        if (!rememberedKnown) {
            remembered = state.path;
            rememberedKnown = true;
        }
        loadBookmarks(state);
        drawHeader(state);
        if (state.path == null) {
            drawEmptySource(state, camera);
            drawFolderPicker(state);
            return;
        }
        drawFolderPicker(state);
        buildProgress(state, camera);
        var data = state.queryData();
        if (data == null || !state.getToolsUnavailableReason().isEmpty()) {
            ImGui.dummy(0, 12);
            ImGui.pushTextWrapPos(0);
            ImGui.textColored(
                    GeodataWidgets.MUTED,
                    state.loading ? "Loading regions around the view…" : state.getToolsUnavailableReason());
            ImGui.popTextWrapPos();
            return;
        }
        resolvePendingGo(state, camera);
        drawTabs();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        var saveBar = editing.saveBarShown(state);
        ImGui.beginChild("##geodataBody/" + tool, 0, saveBar ? -GeodataWidgets.SAVE_BAR : 0, false);
        ImGui.popStyleVar();
        ImGui.dummy(0, GeodataWidgets.BLOCK - 2 * ImGui.getStyle().getItemSpacingY());
        ImGui.pushTextWrapPos(0);
        var comparingB = diagnostics.getViewMode() == ViewMode.SOURCE_B;
        if (comparingB) {
            ImGui.textColored(GeodataWidgets.WARNING, "Showing comparison source B.");
            if (GeodataWidgets.actionButton("geo-back-to-a", null, "Back to source A", -1, true)) {
                diagnostics.showSourceA();
                comparingB = false;
            }
            ImGui.dummy(0, 4);
        }
        ImGui.beginDisabled(comparingB && tool != Tool.CHECK);
        switch (tool) {
            case INSPECT -> drawInspectTab(state, camera);
            case ROUTE -> routes.draw(state, camera);
            case EDIT -> {
                editing.draw(state);
                var flyTo = editing.takeFlyTo();
                if (flyTo != null) {
                    goTo(state, camera, flyTo.x(), flyTo.y(), flyTo.z());
                }
            }
            case CHECK -> checkTab(state, camera, backend);
        }
        ImGui.endDisabled();
        ImGui.popTextWrapPos();
        ImGui.dummy(0, 8);
        ImGui.endChild();
        if (saveBar && editing.saveBar(state)) {
            setTool(Tool.EDIT);
        }
    }

    public void goTo(GeodataState state, Camera camera, double x, double y, double z) {
        pendingGo = null;
        if (!selectAt(state, x, y, z)) {
            pendingGo = new double[] {x, y, z};
            pendingGoUntil = System.nanoTime() + 15_000_000_000L;
        }
        var selected = state.inspection.selected;
        var height = selected != null && pendingGo == null
                ? selected.height()
                : Double.isNaN(z) ? camera.getPosition().getZ() - 512 : z;
        focus(camera, new GeoPoint(x, y, height));
    }

    public void drawViewport(
            GeodataState state,
            Camera camera,
            ViewerRenderBackend backend,
            Viewport viewport,
            boolean panelOpen,
            boolean blocked,
            boolean helpOpen) {
        var data = state.queryData();
        var io = ImGui.getIO();
        var width = (int) io.getDisplaySizeX();
        var height = (int) io.getDisplaySizeY();
        if (!panelOpen
                || blocked
                || data == null
                || state.getComparisonDisplay() != null
                || width <= 0
                || height <= 0) {
            state.hovered = null;
            hoverPoint = null;
            state.lines.clear();
            if (!panelOpen || blocked) {
                editing.cancelInteraction();
            }
            return;
        }
        var mouseX = ImGui.getMousePosX();
        var mouseY = ImGui.getMousePosY();
        var pointerFree = !io.getWantCaptureMouse() && viewport.contains(mouseX, mouseY);
        var hits = List.<Hit>of();
        if (pointerFree) {
            hits = pick(state, camera, mouseX, mouseY, width, height);
        }
        var hit = hits.isEmpty() || !isLayerVisible(state, backend, hits.getFirst(), mouseX, mouseY, width, height)
                ? null
                : hits.getFirst();
        state.hovered = hit == null ? null : toSelection(data, hit);
        hoverPoint = hit == null ? null : hit.position();
        var clicked = pointerFree && ImGui.isMouseClicked(0);
        var shift = io.getKeyShift();
        if (clicked) {
            switch (tool) {
                case INSPECT -> select(state, data, state.hovered == null ? List.of() : hits);
                case CHECK -> diagnostics.pick(data, camera, mouseX, mouseY, width, height);
                case ROUTE -> routes.handleClick(state, state.hovered, shift, isCommandHeld(io));
                case EDIT -> {
                    if (editing.getTool() == EditTool.EDGES && state.hovered != null) {
                        editing.beginEdges(
                                state,
                                state.hovered,
                                GeodataEditingPanel.edgeAt(hoverPoint, state.hovered.ref()),
                                shift);
                    }
                }
            }
        }
        handleDrag(state, clicked, pointerFree, mouseX, mouseY);
        if (tool == Tool.EDIT
                && editing.getTool().isBrush()
                && !editing.isRectangleShape()
                && pointerFree
                && isCommandHeld(io)
                && io.getMouseWheel() != 0) {
            editing.resizeBrush(io.getMouseWheel() > 0);
        }
        editing.setModifiers(io.getKeyShift(), io.getKeyAlt());
        if (pointerFree) {
            var ndcX = mouseX / width * 2f - 1f;
            var ndcY = 1f - mouseY / height * 2f;
            var origin = camera.getCursorRayOrigin(ndcX, ndcY);
            var direction = camera.getCursorRayDirection(ndcX, ndcY);
            editing.setPointerRay(
                    new GeoPoint(origin.getX(), origin.getY(), origin.getZ()),
                    new GeoPoint(direction.getX(), direction.getY(), direction.getZ()));
        } else {
            editing.setPointerRay(null, null);
        }
        if (tool == Tool.EDIT && editing.isBrushActive(state)) {
            if (pointerFree && ImGui.isMouseDown(0)) {
                editing.handlePointer(state, hits, clicked, true, false, false);
            }
            editing.handlePointer(state, List.of(), false, false, ImGui.isMouseReleased(0), false);
        } else if (tool == Tool.EDIT && editing.getTool() == EditTool.EDGES) {
            editing.dragEdges(
                    state,
                    pointerFree ? state.hovered : null,
                    hoverPoint,
                    ImGui.isMouseDown(0),
                    ImGui.isMouseReleased(0));
        } else {
            editing.cancelInteraction();
        }
        routes.tick(state);
        var draw = ImGui.getBackgroundDrawList();
        draw.pushClipRect(viewport.left(), viewport.top(), viewport.right(), viewport.bottom(), true);
        state.lines.begin();
        routes.setDepthSource(backend);
        if (dragging) {
            routes.drawDrag(camera, draw, state.lines, dragFrom, state.hovered, width, height);
        } else {
            routes.drawOverlay(state, camera, draw, width, height);
        }
        drawSpawnMarks(state, camera, width, height);
        state.lines.publish(width);
        diagnostics.drawOverlay(data, camera, draw, width, height);
        editing.drawChanges(state, camera, draw, width, height);
        if (tool == Tool.INSPECT) {
            drawArea(camera, draw, width, height);
        }
        if (tool == Tool.EDIT) {
            editing.drawOverlay(camera, draw, width, height, state.hovered, hoverPoint);
        }
        if (tool != Tool.ROUTE) {
            drawSelectionTag(state, camera, draw, width, height);
        }
        if (state.hovered != null && pointerFree && !ImGui.isMouseDown(0)) {
            drawHoverChip(data, state.hovered, draw, mouseX, mouseY, viewport);
        }
        if (!helpOpen) {
            drawHints(draw, viewport, state);
        }
        draw.popClipRect();
    }

    public void handleKeys(GeodataState state, Camera camera, Keyboard keys, boolean panelOpen) {
        if (keys.isClicked(Key.G) && !keys.isPressed(Key.COMMAND)) {
            state.enabled = !state.enabled;
        }
        if (!panelOpen || state.queryData() == null) {
            return;
        }
        var command = keys.isPressed(Key.COMMAND);
        if (command && keys.isClicked(Key.C) && area != null) {
            copyArea(state);
            return;
        }
        if (command && keys.isClicked(Key.V) && clip != null && (tool == Tool.INSPECT || tool == Tool.EDIT)) {
            pasteClip(state);
            return;
        }
        if (command && keys.isClicked(Key.Z)) {
            if (keys.isPressed(Key.SHIFT)) {
                editing.redo(state);
            } else {
                editing.undo(state);
            }
            return;
        }
        if (command) {
            return;
        }
        if (keys.isClicked(Key.Q)) {
            setTool(Tool.INSPECT);
        }
        if (keys.isClicked(Key.R)) {
            setTool(Tool.ROUTE);
        }
        if (keys.isClicked(Key.B)) {
            setTool(Tool.EDIT);
        }
        if (keys.isClicked(Key.C)) {
            setTool(Tool.CHECK);
        }
        if (keys.isClicked(Key.X)) {
            state.throughGeometry = !state.throughGeometry;
        }
        if (tool == Tool.ROUTE && routes.isWalking() && keys.isClicked(Key.SPACE)) {
            routes.toggleWalk();
        }
        if (tool == Tool.EDIT
                && editing.getTool().isBrush()
                && (keys.isClicked(Key.LEFT_BRACKET) || keys.isClicked(Key.RIGHT_BRACKET))) {
            editing.resizeBrush(keys.isClicked(Key.RIGHT_BRACKET));
            return;
        }
        var selected = state.inspection.selected;
        if (selected == null) {
            return;
        }
        if (keys.isClicked(Key.F)) {
            focus(camera, selected.center());
        }
        if (keys.isClicked(Key.I)) {
            areas.toggle(state);
        }
        if (keys.isClicked(Key.Z)) {
            toggleSlice(state);
        }
        if (keys.isClicked(Key.RIGHT_BRACKET) || keys.isClicked(Key.LEFT_BRACKET)) {
            stepFloor(state, keys.isClicked(Key.RIGHT_BRACKET));
        }
    }

    public boolean handleEscape(GeodataState state) {
        if (editing.hasStroke()) {
            editing.cancelInteraction();
            return true;
        }
        if (routes.isWalking()) {
            routes.stopWalk();
            return true;
        }
        if (areaDragging || area != null) {
            areaDragging = false;
            area = null;
            return true;
        }
        if (tool == Tool.ROUTE && routes.hasPins(state)) {
            routes.clear(state);
            return true;
        }
        if (areas.isActive(state)) {
            areas.clear(state);
            return true;
        }
        if (state.inspection.selected != null) {
            state.inspection.selected = null;
            return true;
        }
        return false;
    }

    public void tick(GeodataState state, Camera camera, ViewerRenderBackend backend) {
        lastState = state;
        lastCamera = camera;
        regionCatalogs.request(
                state.path,
                state.getReloadRevision() * 1_000_003L + state.getMissingRevision(),
                diagnostics.getComparisonFolder());
        handleRegionRequests(state);
        suite.tick(state, camera, backend);
        if (controls.getJob().takeWritten() > 0) {
            reloadForBuild = true;
        }
        if (reloadForBuild
                && GeodataBuildControls.isSameFolder(
                        controls.getJob().getProgress().folder(), state.path)) {
            reloadForBuild = false;
            state.refreshMissing();
        }
    }

    public GeoDataSnapshot getComparison(GeodataState state) {
        return diagnostics.getViewMode() == ViewMode.SOURCE_B ? diagnostics.getSourceB(state.queryData()) : null;
    }

    public boolean capturesPointer(GeodataState state, boolean panelOpen) {
        return panelOpen && state.queryData() != null;
    }

    public void drawGuard(GeodataState state) {
        editing.drawGuard(state);
    }

    public boolean requestLeave(GeodataState state, Leave action) {
        return editing.requestLeave(state, action);
    }

    public Leave consumeApprovedLeave() {
        return editing.consumeApprovedLeave();
    }

    public void cancelInteraction() {
        editing.cancelInteraction();
    }

    public boolean hasStroke() {
        return editing.hasStroke();
    }

    public boolean holdsEdits() {
        return editing.hasStroke() || editing.writesFiles();
    }

    @Override
    public void close() {
        regionCatalogs.close();
        rebuilder.close();
        regionWorker.shutdownNow();
        routes.close();
        editing.close();
        areas.close();
        diagnostics.close();
        scenarios.close();
        suite.close();
    }

    private void handleRegionRequests(GeodataState state) {
        while (!regionRequests.isEmpty()) {
            var request = regionRequests.poll();
            var tile = request.tile();
            var name = tile.x() + "_" + tile.y();
            if (!getUnavailable(request.action(), tile).isEmpty()) {
                continue;
            }
            switch (request.action()) {
                case REBUILD -> {
                    setTool(Tool.EDIT);
                    editing.rebuildRegion(state, tile);
                }
                case REVERT -> {
                    var cells = editing.revertRegion(state, tile);
                    setSourceMessage(
                            cells < 0
                                    ? editing.getProblem()
                                    : String.format(
                                            Locale.ROOT,
                                            "Reverted %s: %,d cells are as in the file again.",
                                            name,
                                            cells),
                            cells < 0);
                }
                case TAKE_FROM_B -> {
                    var folder = diagnostics.getComparisonFolder();
                    setSourceMessage("Reading " + name + " from folder B\u2026", false);
                    regionWork = CompletableFuture.supplyAsync(
                                    () -> {
                                        try {
                                            var file = GeoFormat.find(folder, tile);
                                            if (file == null) {
                                                throw new IOException("Folder B has no file for " + name);
                                            }
                                            return GeoDataReader.read(file);
                                        } catch (IOException failure) {
                                            throw new UncheckedIOException(failure);
                                        }
                                    },
                                    regionWorker)
                            .thenApply(region -> (Runnable) () -> {
                                var cells = editing.replaceRegion(state, tile, region, "Take " + name + " from B");
                                setSourceMessage(
                                        cells < 0
                                                ? editing.getProblem()
                                                : String.format(
                                                        Locale.ROOT,
                                                        "Took %s from folder B: %,d cells differ from this folder."
                                                                + " Review them, then save.",
                                                        name,
                                                        cells),
                                        cells < 0);
                            });
                }
                case EXPORT -> {
                    var folder = editing.exportFolder(state);
                    var base = state.getSourceSnapshot();
                    var edits = state.editing == null
                            ? Map.<CellKey, List<Layer>>of()
                            : state.editing.getDocument().getChangedCells();
                    setSourceMessage("Exporting " + name + "\u2026", false);
                    regionWork = CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    var file = GeoDataWriter.exportRegion(base, edits, tile, null, folder);
                                    return (Runnable) () -> {
                                        setSourceMessage(
                                                "Wrote " + file.getFileName() + " into " + folder + ".", false);
                                        sourceMessageFolder = file;
                                    };
                                } catch (IOException failure) {
                                    throw new UncheckedIOException(failure);
                                }
                            },
                            regionWorker);
                }
                case OPEN_FOLDER -> {
                    try {
                        FileManager.openFolder(state.path);
                    } catch (IOException | RuntimeException failure) {
                        setSourceMessage("Could not open the folder: " + failure.getMessage(), true);
                    }
                }
            }
        }
        var work = regionWork;
        if (work != null && work.isDone()) {
            regionWork = null;
            try {
                work.join().run();
            } catch (RuntimeException failure) {
                var cause = failure.getCause() instanceof UncheckedIOException io
                        ? io.getCause()
                        : failure.getCause() == null ? failure : failure.getCause();
                setSourceMessage(cause.getMessage() == null ? cause.toString() : cause.getMessage(), true);
            }
        }
    }

    private void setSourceMessage(String text, boolean problem) {
        sourceMessage = text;
        sourceMessageColor = problem ? GeodataWidgets.WARNING : GeodataWidgets.MUTED;
        sourceMessageFolder = null;
    }

    private void drawHeader(GeodataState state) {
        var spacing = ImGui.getStyle().getItemSpacingX();
        var viewWidth = Math.max(64, (ImGui.getContentRegionAvailX() - spacing * 2) * .24f);
        var width = (ImGui.getContentRegionAvailX() - spacing * 2 - viewWidth) / 2;
        if (GeodataWidgets.chip("geo-show", Glyph.EYE, "Show", "G", state.enabled, width)) {
            state.enabled = !state.enabled;
        }
        GeodataWidgets.tip("Show the geodata overlay. The panel tools work while it is hidden too.", "G");
        ImGui.sameLine();
        if (GeodataWidgets.chip("geo-xray", Glyph.XRAY, "X-ray", "X", state.throughGeometry, width)) {
            state.throughGeometry = !state.throughGeometry;
        }
        GeodataWidgets.tip(
                "Draw geodata hidden behind walls and floors faintly, to see underground and indoor floors.", "X");
        ImGui.sameLine();
        if (GeodataWidgets.chip("geo-view", Glyph.SLIDERS, "View", ImGui.isPopupOpen("geodata-view"), viewWidth)) {
            ImGui.openPopup("geodata-view");
        }
        GeodataWidgets.tip("Style, opacity, radius, focus and height range.");
        var popoverRight = ImGui.getItemRectMaxX();
        var popoverTop = ImGui.getItemRectMaxY() + 4;
        filterChips(state);
        drawViewPopover(state, popoverRight, ImGui.getItemRectMaxY() + 4);
        drawSourceRow(state);
    }

    private void filterChips(GeodataState state) {
        GeodataWidgets.space(GeodataWidgets.ROW);
        var spacing = ImGui.getStyle().getItemSpacingX();
        var width = (ImGui.getContentRegionAvailX() - spacing * 2) / 3;
        var display = state.display;
        var selected = state.inspection.selected;
        if (GeodataWidgets.chip("geo-heights", Glyph.LAYERS, "Heights", "Z", display.heightFilter(), width)) {
            if (display.heightFilter()) {
                state.display = display.withHeightFilter(false, -32768, 32767, display.dimOtherHeights());
                sliceActive = false;
            } else if (selected != null) {
                slice(state, selected.height());
            } else {
                state.display = display.withHeightFilter(
                        true, display.minHeight(), display.maxHeight(), display.dimOtherHeights());
            }
        }
        GeodataWidgets.tip(
                display.heightFilter()
                        ? (display.dimOtherHeights() ? "Heights " : "Only heights ")
                                + display.minHeight()
                                + " to "
                                + display.maxHeight()
                                + ". Click to show every height again."
                        : "Show only heights near the selected floor, or the range set in View. Visual only.",
                "Z");
        ImGui.sameLine();
        var isolated = areas.isActive(state);
        ImGui.beginDisabled(!isolated && selected == null);
        if (GeodataWidgets.chip("geo-area", Glyph.FILTER, "Area", "I", isolated, width)) {
            if (isolated) {
                areas.clear(state);
            } else {
                areas.toggle(state);
            }
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                isolated
                        ? "Only the floor reachable from the pinned cell is shown. Click to show everything again."
                        : selected == null
                                ? "Select a cell to show only the floor you can walk to from it."
                                : "Show only the floor you can walk to from the selected cell, including stairs."
                                        + " Visual only.",
                "I");
        ImGui.sameLine();
        var spawnsReady = spawnLink.areSpawnsReady();
        ImGui.beginDisabled(!spawnsReady);
        if (GeodataWidgets.chip("geo-spawns", Glyph.NPC, "Spawns", showSpawns && spawnsReady, width)) {
            showSpawns = !showSpawns;
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                spawnsReady
                        ? "Marks where NPCs stand: a dot for a fixed NPC, a ring for a random point, in the colour of"
                                + " its kind."
                        : "Open an npcpos.txt in NPC Spawns to mark its spawn points here.");
    }

    private void drawSourceRow(GeodataState state) {
        var x = ImGui.getCursorScreenPosX();
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var height = 44f;
        var draw = ImGui.getWindowDrawList();
        ImGui.setCursorScreenPos(x, y);
        var clicked = ImGui.invisibleButton("geo-source", width - 30, height);
        var hovered = ImGui.isItemHovered();
        draw.addRectFilled(x, y, x + width, y + height, hovered ? ImColor.rgb(34, 51, 63) : GeodataWidgets.CARD, 6f);
        HudWidgets.glyph(draw, Glyph.FOLDER, x + 9, y + 7, 16, GeodataWidgets.MUTED);
        var name = state.path == null
                ? "No folder"
                : state.path.getFileName() == null
                        ? state.path.toString()
                        : state.path.getFileName().toString();
        draw.pushClipRect(x, y, x + width - 34, y + height, true);
        draw.addText(x + 32, y + 6, GeodataWidgets.TEXT, name);
        var status = statusLine(state);
        var busy = editing.getBusyStatus();
        if (controls.isRunning(state.path)) {
            busy = "Building geodata\u2026";
        }
        if (busy != null) {
            status = new Status(busy, GeodataWidgets.ACCENT, status.detail());
        }
        draw.addText(x + 32, y + 24, status.color, status.text);
        draw.popClipRect();
        if (hovered) {
            HudWidgets.showTooltip((state.path == null ? "" : state.path + "\n\n")
                    + "Click to choose another folder."
                    + (status.detail.isBlank() ? "" : "\n\n" + status.detail));
        }
        if (clicked) {
            openFolderPicker(state);
        }
        ImGui.setCursorScreenPos(x + width - 28, y + (height - 26) * .5f);
        ImGui.beginDisabled(state.path == null);
        if (HudWidgets.button("geo-reload", Glyph.RELOAD, "", 26, 26, false, false)) {
            state.requestReload();
        }
        ImGui.endDisabled();
        GeodataWidgets.tip("Read the folder again after files change on disk. Unsaved edits ask first.");
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        if (clientPath != null && state.path != null && !Objects.equals(state.path, remembered)) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            if (GeodataWidgets.actionButton("geo-remember", null, "Remember this folder for this client", -1, false)) {
                remember(state.path);
            }
            GeodataWidgets.tip("Saves the folder in cruma.conf for " + clientPath + ", so the next start uses it.");
        }
        if (!sourceMessage.isBlank()) {
            ImGui.pushTextWrapPos(0);
            ImGui.textColored(sourceMessageColor, sourceMessage);
            ImGui.popTextWrapPos();
            if (sourceMessageFolder != null) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                GeodataWidgets.showFolder("geo-message-folder", sourceMessageFolder);
            }
        }
        if (state.displayLimitReached) {
            if (state.vertexLimit < GeoDataMesh.MAX_VERTICES) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                if (GeodataWidgets.actionButton("geo-capacity", null, "Partial view · show more", -1, false)) {
                    state.vertexLimit = Math.min(GeoDataMesh.MAX_VERTICES, state.vertexLimit * 2);
                }
                GeodataWidgets.tip(
                        "The display reached its vertex capacity, so the farthest sectors are missing. This doubles it"
                                + " for this scene.");
            } else {
                ImGui.textColored(GeodataWidgets.WARNING, "Partial view: reduce the radius or slice a floor.");
            }
        }
    }

    private record Status(String text, int color, String detail) {}

    private void drawEmptySource(GeodataState state, Camera camera) {
        ImGui.dummy(0, 16);
        var draw = ImGui.getWindowDrawList();
        var width = ImGui.getContentRegionAvailX();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        HudWidgets.glyph(draw, Glyph.GEODATA, x + width * .5f - 18, y, 36, GeodataWidgets.ACCENT);
        ImGui.dummy(width, 44);
        centeredText("Server geodata");
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/geodata");
        ImGui.pushTextWrapPos(0);
        ImGui.textColored(
                GeodataWidgets.MUTED,
                "Choose the folder with the server's regions: L2OFF files such as 22_22_conv.dat or L2J files such as"
                        + " 22_22.l2j. Path search also uses pathnode.idx and pathnode.bin from the same folder.");
        ImGui.popTextWrapPos();
        ImGui.dummy(0, 8);
        if (GeodataWidgets.actionButton("geo-browse", Glyph.FOLDER, "Choose folder…", -1, true)) {
            folders.open(null);
        }
        ImGui.dummy(0, 6);
        ImGui.textColored(GeodataWidgets.MUTED, "or paste a path");
        ImGui.setNextItemWidth(-1);
        if (ImGui.inputTextWithHint(
                "##geo-path", "/path/to/geodata", typedPath, ImGuiInputTextFlags.EnterReturnsTrue)) {
            applyTyped = true;
        }
        if (clientPath == null) {
            guideLinks();
            return;
        }
        controls.startPointSources(spawnLink.getScriptFolder());
        GeodataWidgets.space(GeodataWidgets.SECTION);
        var folder = controls.getFolder(clientPath);
        var built = controls.getBuilt(clientPath);
        if (built > 0) {
            if (GeodataWidgets.callout(
                    "geo-built",
                    Glyph.FOLDER,
                    "Built from this client",
                    built + (built == 1 ? " region" : " regions") + " in " + folder.getFileName(),
                    "Open")) {
                choose(state, folder);
                remember(folder);
            }
            GeodataWidgets.tip("Opens " + folder + ", where the viewer built geodata from this client's maps.");
        } else {
            var open = openRegions(state);
            var chosen = controls.isPartial(clientPath, open)
                    ? controls.getChosenMissing(clientPath, open)
                    : List.<GeoTile>of();
            ImGui.beginDisabled(controls.isServerUnknown(folder));
            if (GeodataWidgets.callout(
                    "geo-build",
                    Glyph.GEODATA,
                    "No server geodata?",
                    chosen.isEmpty()
                            ? "Build it from the client"
                            : "Build " + GeodataBuildControls.formatRegionNames(chosen) + " from the client",
                    "Build")) {
                startBuild(state, camera, chosen.isEmpty() ? null : chosen);
            }
            GeodataWidgets.tip(
                    chosen.isEmpty()
                            ? "Builds a region file for every map of the client into " + folder
                                    + ", the one under the camera first, the way the official builder walks the world."
                                    + " A region takes a few seconds. You can stop and go on later."
                            : "Builds the regions open in the Viewer into " + folder
                                    + ", the way the official builder walks the world. A region takes a few seconds.");
            var all = controls.getMissing(clientPath);
            if (!chosen.isEmpty() && all > chosen.size()) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                if (GeodataWidgets.actionButton(
                        "geo-build-all",
                        Glyph.GEODATA,
                        "Build all " + all + " missing " + (all == 1 ? "region" : "regions"),
                        -1,
                        false)) {
                    startBuild(state, camera, null);
                }
                GeodataWidgets.tip(
                        "Builds every map of the client that has no region file yet, the nearest first. The whole"
                                + " client takes about ten minutes.");
            }
            ImGui.endDisabled();
        }
        controls.formatSwitch("geo-build-format", clientPath, true);
        controls.about("geo-build-about");
        guideLinks();
    }

    private void buildProgress(GeodataState state, Camera camera) {
        var folder = clientPath == null ? null : controls.getFolder(clientPath);
        controls.startPointSources(spawnLink.getScriptFolder());
        rebuilder.startSources(controls.getStartSources());
        if (controls.isRunning(state.path)) {
            GeodataWidgets.space(GeodataWidgets.BLOCK);
            controls.progressCard("geo-build-stop", state.path);
            controls.about("geo-build-about");
            return;
        }
        if (tool != Tool.EDIT && editing.rebuildProgress("geo-rebuild-progress")) {
            GeodataWidgets.space(GeodataWidgets.ROW);
        }
        var outcome = controls.getOutcome(state.path);
        if (outcome != null) {
            sourceMessage = outcome.text();
            sourceMessageColor = outcome.color();
            sourceMessageFolder = outcome.folder();
        }
        controls.pathNodeCallout(
                "geo-pathnode", state.path, folder != null && GeodataBuildControls.isSameFolder(folder, state.path));
        if (folder == null || !GeodataBuildControls.isSameFolder(folder, state.path)) {
            return;
        }
        if (controls.getMissing(clientPath) > 0) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            var position = camera.getPosition();
            if (controls.buildActions(
                    "geo-build-rest",
                    clientPath,
                    openRegions(state),
                    GeoTile.fromWorld((int) position.getX(), (int) position.getY()),
                    false)) {
                remember(folder);
            }
        }
        var focus = state.viewFocus;
        if (focus != null && !rebuilder.getStatus().running()) {
            var tile = GeoTile.fromWorld((int) Math.floor(focus.x()), (int) Math.floor(focus.y()));
            if (state.getSourceSnapshot() != null && state.getSourceSnapshot().getRegion(tile.x(), tile.y()) != null) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                if (GeodataWidgets.actionButton(
                        "geo-rebuild-region",
                        Glyph.GEODATA,
                        "Rebuild region " + tile.x() + "_" + tile.y(),
                        -1,
                        false)) {
                    setTool(Tool.EDIT);
                    editing.rebuildRegion(state, tile);
                }
                GeodataWidgets.tip(
                        "Builds the region under the view again from the client and loads it as an edit, to review and"
                                + " save like other edits. Nothing is written until you save."
                                + GeodataEditingPanel.startPointsNote(rebuilder.getStartSources()));
            }
        }
    }

    private void startBuild(GeodataState state, Camera camera, @Nullable List<GeoTile> only) {
        var position = camera.getPosition();
        var near = GeoTile.fromWorld((int) position.getX(), (int) position.getY());
        if (only == null) {
            controls.start(clientPath, near);
        } else {
            controls.start(clientPath, near, only, false);
        }
        var folder = controls.getFolder(clientPath);
        choose(state, folder);
        remember(folder);
    }

    private void openFolderPicker(GeodataState state) {
        folders.open(state.path);
    }

    private void drawFolderPicker(GeodataState state) {
        if (applyTyped) {
            applyTyped = false;
            try {
                choose(state, typedPath.get().isBlank() ? null : UserPaths.resolve(typedPath.get(), configDirectory));
            } catch (RuntimeException invalid) {
                sourceMessage = "That is not a valid folder path.";
                sourceMessageColor = GeodataWidgets.BLOCKED;
                sourceMessageFolder = null;
            }
        }
        var selected = folders.draw();
        if (selected != null) {
            choose(state, selected);
        }
    }

    private void choose(GeodataState state, Path folder) {
        state.path = folder;
        state.enabled = true;
        sourceMessage = "";
        typedPath.set(folder == null ? "" : folder.toString());
    }

    private void remember(Path folder) {
        try {
            var loader = new ConfigLoader(new Options(configDirectory, false, false));
            var model = new OptionsModel(loader, clientPath);
            model.set(ClientProfiles.GEODATA_PATH, folder.toString());
            model.save();
            remembered = folder;
            sourceMessage = "Saved for this client.";
            sourceMessageColor = GeodataWidgets.OPEN;
            sourceMessageFolder = null;
        } catch (Exception failure) {
            LogManager.getLogger(GeodataWorkspace.class)
                    .warn("Could not save the geodata folder: {}", failure.toString());
            sourceMessage = failure.getMessage() == null ? "Could not save the folder." : failure.getMessage();
            sourceMessageColor = GeodataWidgets.BLOCKED;
            sourceMessageFolder = null;
        }
    }

    private void drawTabs() {
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var tools = Tool.values();
        var tabs = new ArrayList<ToolTab>(tools.length);
        for (var each : tools) {
            tabs.add(new ToolTab(each.label, each.glyph, each.key, ""));
        }
        var clicked = GeodataWidgets.toolTabs("geo-tab", tabs, tool.ordinal());
        if (clicked >= 0) {
            setTool(tools[clicked]);
        }
    }

    private void drawViewPopover(GeodataState state, float right, float top) {
        ImGui.setNextWindowSize(300, 0, ImGuiCond.Always);
        ImGui.setNextWindowPos(right, top, ImGuiCond.Always, 1, 0);
        if (!ImGui.beginPopup("geodata-view")) {
            return;
        }
        ImGui.pushTextWrapPos(0);
        var display = state.display;
        GeodataWidgets.section("STYLE");
        var mode = GeodataWidgets.segmented(
                "view-mode",
                new String[] {"Cells", "Surface", "Walls"},
                switch (display.mode()) {
                    case EXACT_GRID -> 0;
                    case SURFACE -> 1;
                    case CONTOURS -> 2;
                });
        if (mode >= 0) {
            display = display.withMode(
                    switch (mode) {
                        case 0 -> Mode.EXACT_GRID;
                        case 1 -> Mode.SURFACE;
                        default -> Mode.CONTOURS;
                    });
        }
        GeodataWidgets.tip(
                "Cells: every cell with its grid. Surface: tinted ground without the grid. Walls: only blocked edges,"
                        + " steps and ledges.");
        number[0] = display.opacity();
        HudWidgets.sliderFloat("Opacity", number, 0, 1, "%.2f");
        var opacity = number[0];
        number[0] = display.lineWidthPixels();
        HudWidgets.sliderFloat("Line width", number, .5f, 8, "%.1f px");
        display = display.withStyle(opacity, number[0]);
        if (display.mode() != Mode.EXACT_GRID) {
            var local = display.localGrid();
            if (ImGui.checkbox("Cell grid near the focus", local)) {
                local = !local;
            }
            integer[0] = display.localGridRadius();
            if (local) {
                HudWidgets.sliderInt("Grid radius", integer, 16, 1024);
            }
            display = new GeoDataDisplay(
                    display.mode(),
                    display.opacity(),
                    display.lineWidthPixels(),
                    local,
                    Math.clamp(integer[0], 16, 1024),
                    display.heightFilter(),
                    display.minHeight(),
                    display.maxHeight(),
                    display.dimOtherHeights(),
                    display.focus(),
                    display.surface());
        }
        GeodataWidgets.section("AREA");
        integer[0] = state.radius;
        HudWidgets.sliderInt("Radius", integer, 128, 4096);
        state.radius = Math.clamp(integer[0], 128, 4096);
        GeodataWidgets.tip(
                "Geodata is shown in a square around the focus, in world units, and nowhere beyond it. One cell is 16"
                        + " units.");
        ImGui.textColored(GeodataWidgets.MUTED, "Focus follows");
        var follow = GeodataWidgets.segmented(
                "view-follow",
                new String[] {"Mouse", "View center"},
                state.focusMode == GeoDataFocusMode.MOUSE ? 0 : 1);
        if (follow >= 0) {
            state.focusMode = follow == 0 ? GeoDataFocusMode.MOUSE : GeoDataFocusMode.CAMERA;
        }
        GeodataWidgets.section("HEIGHT RANGE");
        var filter = display.heightFilter();
        if (ImGui.checkbox("Limit to heights between", filter)) {
            filter = !filter;
        }
        GeodataWidgets.tip(
                "A visual filter. Queries and editing still use every layer.", "Z slices at the selected floor");
        var min = display.minHeight();
        var max = display.maxHeight();
        var dim = display.dimOtherHeights();
        ImGui.beginDisabled(!filter);
        var halfWidth = GeodataWidgets.columnWidth(2);
        rangeInput.set(min);
        ImGui.setNextItemWidth(halfWidth);
        if (ImGui.inputInt("##view-min", rangeInput, 8, 64)) {
            min = Math.clamp(rangeInput.get(), -32768, max);
        }
        ImGui.sameLine();
        rangeInput.set(max);
        ImGui.setNextItemWidth(halfWidth);
        if (ImGui.inputInt("##view-max", rangeInput, 8, 64)) {
            max = Math.clamp(rangeInput.get(), min, 32767);
        }
        if (ImGui.checkbox("Dim other heights instead of hiding", dim)) {
            dim = !dim;
        }
        ImGui.endDisabled();
        display = display.withHeightFilter(filter, min, max, dim);
        GeodataWidgets.section("CAPACITY");
        ImGui.setNextItemWidth(-1);
        if (ImGui.beginCombo("##view-capacity", capacityLabel(state.vertexLimit))) {
            for (var limit : new int[] {250_000, 500_000, 1_000_000, 2_000_000, 3_000_000, 4_000_000}) {
                if (ImGui.selectable(capacityLabel(limit), state.vertexLimit == limit)) {
                    state.vertexLimit = limit;
                }
            }
            ImGui.endCombo();
        }
        GeodataWidgets.tip(
                "Maximum GPU vertices of the overlay. More capacity shows dense areas further away and uses more"
                        + " memory.");
        state.display = display;
        ImGui.popTextWrapPos();
        ImGui.endPopup();
    }

    private void drawInspectTab(GeodataState state, Camera camera) {
        if (area != null) {
            drawAreaCard(state);
            GeodataWidgets.space(GeodataWidgets.SECTION);
        }
        var data = state.queryData();
        var selected = state.inspection.selected;
        var resolved = GeoInspection.resolve(data, selected);
        if (resolved == null) {
            drawCard(
                    "Hover the ground to read a cell.",
                    "Click selects it. Click the same spot again to reach the floor below.");
            drawBookmarks(state, camera);
            goTo(state, camera);
            return;
        }
        var ref = selected.ref();
        var cell = resolved.cell();
        var size = 84f;
        var top = ImGui.getCursorScreenPosY();
        GeodataWidgets.compass("inspect-compass", selected.nswe(), size, false);
        ImGui.sameLine();
        ImGui.beginGroup();
        var tile = GeoTile.fromWorld(ref.x(), ref.y());
        ImGui.textUnformatted("Z " + selected.height());
        ImGui.textColored(GeodataWidgets.MUTED, ref.x() + ", " + ref.y());
        ImGui.textColored(
                GeodataWidgets.MUTED,
                "Layer " + (ref.layer() + 1) + " of " + cell.layers().size() + " · " + tile.x() + "_" + tile.y());
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/layer");
        if (HudWidgets.button("inspect-copy", Glyph.COPY, "", 26, 26, false, false)) {
            ImGui.setClipboardText(GeodataRoutes.formatCoordinates(selected.center()));
        }
        GeodataWidgets.tip("Copy X, Y, Z");
        ImGui.sameLine(0, 2);
        if (HudWidgets.button("inspect-focus", Glyph.FOCUS, "", 26, 26, false, false)) {
            focus(camera, selected.center());
        }
        GeodataWidgets.tip("Move the camera to this cell", "F");
        ImGui.sameLine(0, 2);
        if (HudWidgets.button("inspect-route", Glyph.ROUTE, "", 26, 26, false, false)) {
            state.inspection.pointA = selected;
            setTool(Tool.ROUTE);
        }
        GeodataWidgets.tip("Start a route here (sets A)");
        ImGui.sameLine(0, 2);
        var bookmark = state.inspection.getBookmarked();
        ImGui.beginDisabled(bookmark < 0 && state.inspection.bookmarks.size() >= 128);
        if (HudWidgets.button("inspect-bookmark", Glyph.BOOKMARK, "", 26, 26, bookmark >= 0, false)) {
            if (bookmark >= 0) {
                state.inspection.bookmarks.remove(bookmark);
            } else {
                state.inspection.bookmark("Cell " + GeodataRoutes.formatCoordinates(selected.center()));
            }
            saveBookmarks(state);
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                bookmark >= 0
                        ? "Remove this cell's bookmark"
                        : state.inspection.bookmarks.size() >= 128
                                ? "128 bookmarks is the most. Remove one under Bookmarks first."
                                : "Bookmark this cell. Bookmarks stay with this geodata folder after a restart.");
        ImGui.endGroup();
        ImGui.setCursorScreenPos(ImGui.getCursorScreenPosX(), Math.max(ImGui.getCursorScreenPosY(), top + size + 6));
        ImGui.dummy(0, 0);
        drawMoves(data, resolved);

        var layers = new ArrayList<>(cell.layers());
        if (layers.size() > 1) {
            GeodataWidgets.section("FLOORS IN THIS CELL");
        }
        layers.sort(Comparator.comparingInt(Layer::height).reversed());
        for (var layer : layers.size() > 1 ? layers : List.<Layer>of()) {
            var current = layer.index() == ref.layer();
            var delta = layer.height() - selected.height();
            var label = String.format(
                    Locale.ROOT, "Z %-7d %s", layer.height(), current ? "" : String.format(Locale.ROOT, "%+d", delta));
            if (ImGui.selectable(label + "##floor-" + layer.index(), current)) {
                state.inspection.select(data, ref.x(), ref.y(), layer.index());
            }
            drawNsweBadge(layer.nswe());
        }
        if (layers.size() > 1) {
            ImGui.textColored(GeodataWidgets.FAINT, "[ and ] step between floors");
        }

        GeodataWidgets.section("VIEW THIS FLOOR");
        var sliced = isSliced(state);
        if (GeodataWidgets.rowButton(sliced ? "Unslice" : "Slice height", 0, 2)) {
            toggleSlice(state);
        }
        GeodataWidgets.tip("Show only heights close to this floor; others are dimmed. Visual only.", "Z");
        var isolated = areas.isActive(state);
        if (GeodataWidgets.rowButton(isolated ? "Show all" : "Isolate area", 1, 2)) {
            areas.toggle(state);
        }
        GeodataWidgets.tip("Show only the floor you can walk to from this cell, including stairs. Visual only.", "I");
        if (isolated || GeodataWidgets.header("Area options")) {
            areas.draw(state);
        }
        if (GeodataWidgets.header("Source data")) {
            GeodataWidgets.value("File", sourceFile(state.getSourceSnapshot(), tile));
            GeodataWidgets.value("Storage", toReadable(cell.storage().name()));
            GeodataWidgets.value("NSWE", selected.nswe() + "  (" + formatNswe(selected.nswe()) + ")");
            GeodataWidgets.value("Revision", Long.toString(data.revision()));
            if (state.editing != null
                    && !cell.layers()
                            .equals(state.getSourceSnapshot()
                                    .cellAt(ref.x(), ref.y())
                                    .layers())) {
                ImGui.textColored(GeodataWidgets.WARNING, "Edited: differs from the source file.");
            }
        }
        drawBookmarks(state, camera);
        goTo(state, camera);
    }

    private void drawBookmarks(GeodataState state, Camera camera) {
        var list = state.inspection.bookmarks;
        if (list.isEmpty() || !GeodataWidgets.header("Bookmarks", ImGuiTreeNodeFlags.DefaultOpen)) {
            return;
        }
        var width = ImGui.getContentRegionAvailX();
        ImGui.pushStyleVar(ImGuiStyleVar.SelectableTextAlign, 0, .5f);
        for (var index = 0; index < list.size(); index++) {
            var bookmark = list.get(index);
            if (ImGui.selectable(bookmark.name() + "##geo-bookmark-" + index, false, 0, width - 30, 22)) {
                goTo(
                        state,
                        camera,
                        bookmark.position().x(),
                        bookmark.position().y(),
                        bookmark.position().z());
            }
            GeodataWidgets.tip("Fly there and select the cell");
            ImGui.sameLine(width - 22);
            if (HudWidgets.button("geo-bookmark-remove-" + index, Glyph.CLOSE, "", 22, 22, false, false)) {
                list.remove(index);
                saveBookmarks(state);
                break;
            }
            GeodataWidgets.tip("Remove this bookmark");
        }
        ImGui.popStyleVar();
    }

    private void loadBookmarks(GeodataState state) {
        if (Objects.equals(state.path, bookmarksFolder)) {
            return;
        }
        bookmarksFolder = state.path;
        state.inspection.bookmarks.clear();
        if (state.path == null) {
            return;
        }
        try {
            state.inspection.bookmarks.addAll(bookmarkStore.load(state.path));
        } catch (IOException failure) {
            LogManager.getLogger(GeodataWorkspace.class)
                    .warn("Could not read the geodata bookmarks: {}", failure.toString());
        }
    }

    private void saveBookmarks(GeodataState state) {
        if (state.path == null) {
            return;
        }
        try {
            bookmarkStore.save(state.path, state.inspection.bookmarks);
        } catch (IOException failure) {
            LogManager.getLogger(GeodataWorkspace.class)
                    .warn("Could not save the geodata bookmarks: {}", failure.toString());
        }
    }

    private void drawCard(String title, String text) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var draw = ImGui.getWindowDrawList();
        draw.channelsSplit(2);
        draw.channelsSetCurrent(1);
        ImGui.setCursorScreenPos(x, y + 8);
        ImGui.indent(10);
        ImGui.pushTextWrapPos(ImGui.getCursorPosX() + width - 20);
        ImGui.textUnformatted(title);
        ImGui.textColored(GeodataWidgets.MUTED, text);
        ImGui.popTextWrapPos();
        ImGui.unindent(10);
        var bottom = ImGui.getCursorScreenPosY() + 4;
        draw.channelsSetCurrent(0);
        draw.addRectFilled(x, y, x + width, bottom, GeodataWidgets.CARD, 6f);
        draw.channelsMerge();
        ImGui.setCursorScreenPos(x, bottom + ImGui.getStyle().getItemSpacingY());
        ImGui.dummy(0, 0);
    }

    private void goTo(GeodataState state, Camera camera) {
        if (!GeodataWidgets.header("Go to coordinates")) {
            return;
        }
        var selected = state.inspection.selected;
        if (selected != null && !selected.equals(goToFilledFrom) && !goToEditing) {
            goToText.set(GeodataRoutes.formatCoordinates(selected.center()));
            goToFilledFrom = selected;
        }
        var target = parseCoordinates(goToText.get());
        var field = GeodataWidgets.actionField(
                "goto",
                "X, Y, Z",
                goToText,
                "Type or paste X, Y and Z, such as 82100, 147500, -3400, as Copy X, Y, Z gives them. "
                        + "Without Z the top floor there is taken.",
                Glyph.GO,
                "Go: fly there and select the cell, also in a region that loads on the way.",
                target != null);
        goToEditing = field.editing();
        if (field.action()) {
            goTo(state, camera, target[0], target[1], target.length > 2 ? target[2] : Double.NaN);
        }
        if (target == null && !goToText.get().isBlank()) {
            ImGui.textColored(GeodataWidgets.WARNING, "Write two or three numbers: X, Y and Z.");
        }
    }

    private void resolvePendingGo(GeodataState state, Camera camera) {
        var target = pendingGo;
        if (target == null) {
            return;
        }
        if (System.nanoTime() > pendingGoUntil) {
            pendingGo = null;
            return;
        }
        if (selectAt(state, target[0], target[1], target[2])) {
            pendingGo = null;
            var selected = state.inspection.selected;
            if (Double.isNaN(target[2]) && selected != null) {
                focus(camera, selected.center());
            }
        }
    }

    private void checkTab(GeodataState state, Camera camera, ViewerRenderBackend backend) {
        diagnostics.draw(state, camera);
        GeodataWidgets.space(GeodataWidgets.SECTION);
        ImGui.setNextItemOpen(advancedOpen, ImGuiCond.Always);
        advancedOpen = GeodataWidgets.header("Advanced##geo-check-advanced");
        GeodataWidgets.tip(
                "Saved query scenarios, scenario suites with screenshots, and diagnostic cases: checks to repeat after"
                        + " changes.");
        if (!advancedOpen) {
            return;
        }
        GeodataWidgets.section("SAVED QUERY SCENARIOS", GeodataWidgets.ROW);
        scenarios.draw(state, camera, backend);
        GeodataWidgets.section("SCENARIO SUITES AND SCREENSHOTS");
        suite.draw(state, camera, backend);
        GeodataWidgets.section("SAVE OR REPLAY DIAGNOSTIC CASE");
        diagnostics.drawCase(state);
    }

    private void drawSpawnMarks(GeodataState state, Camera camera, int width, int height) {
        if (!showSpawns || !spawnLink.areSpawnsReady()) {
            spawnMarks = List.of();
            return;
        }
        var focus = state.viewFocus != null
                ? state.viewFocus
                : new GeoPoint(
                        camera.getPosition().getX(),
                        camera.getPosition().getY(),
                        camera.getPosition().getZ());
        var now = System.nanoTime();
        if (spawnMarksAt == null
                || Math.hypot(focus.x() - spawnMarksAt.x(), focus.y() - spawnMarksAt.y()) > 128
                || now - spawnMarksTime > 500_000_000L) {
            var reach = Math.max(512, state.radius);
            spawnMarks = spawnLink.findSpawnMarks(
                    focus.x() - reach, focus.y() - reach, focus.x() + reach, focus.y() + reach, SPAWN_MARKS);
            spawnMarksAt = focus;
            spawnMarksTime = now;
        }
        var projector = new Projector(camera, width, height);
        var margin = MarkerDepth.MAX_DOT;
        var shown = new ArrayList<PlacedSpawn>(spawnMarks.size());
        for (var mark : spawnMarks) {
            var placed = projector.place((float) mark.x(), (float) mark.y(), (float) mark.z() + SPAWN_LIFT);
            if (placed == null
                    || placed.x() < -margin
                    || placed.x() > width + margin
                    || placed.y() < -margin
                    || placed.y() > height + margin) {
                continue;
            }
            shown.add(new PlacedSpawn(mark, placed));
        }
        shown.sort(
                Comparator.comparingDouble((PlacedSpawn spawn) -> spawn.placed().distance())
                        .reversed());
        for (var spawn : shown) {
            var mark = spawn.mark();
            var placed = spawn.placed();
            var color = SpawnStyle.getImguiColor(mark.argb(), projector.getAlpha(placed.distance(), SPAWN_FADE), 1.1f);
            MarkerDepth.mark(
                    state.lines,
                    (float) mark.x(),
                    (float) mark.y(),
                    (float) mark.z() + SPAWN_LIFT,
                    placed.pixelsPerUnit(),
                    color,
                    mark.fixed(),
                    false);
        }
    }

    private record PlacedSpawn(SpawnMark mark, Placed placed) {}

    private void handleDrag(GeodataState state, boolean clicked, boolean pointerFree, float mouseX, float mouseY) {
        if (tool == Tool.INSPECT
                && (areaDragging
                        || clicked && ImGui.getIO().getKeyShift() && state.hovered != null && hoverPoint != null)) {
            selectArea(state, clicked);
            return;
        }
        if (tool != Tool.INSPECT && tool != Tool.ROUTE) {
            dragFrom = null;
            dragging = false;
            return;
        }
        if (clicked) {
            dragFrom = state.hovered;
            dragX = mouseX;
            dragY = mouseY;
            dragging = false;
        }
        if (dragFrom == null) {
            return;
        }
        if (ImGui.isMouseDown(0)) {
            dragging |= Math.hypot(mouseX - dragX, mouseY - dragY) > DRAG_PIXELS;
            return;
        }
        var to = state.hovered;
        if (dragging && pointerFree && to != null && !to.ref().equals(dragFrom.ref())) {
            routes.setEnds(state, dragFrom, to);
            setTool(Tool.ROUTE);
        }
        dragFrom = null;
        dragging = false;
    }

    private void selectArea(GeodataState state, boolean pressed) {
        if (pressed) {
            areaStart = hoverPoint;
            areaEnd = hoverPoint;
            areaSeed = state.hovered;
            areaDragging = true;
            area = null;
            dragFrom = null;
            return;
        }
        if (ImGui.isMouseDown(0)) {
            if (hoverPoint != null) {
                areaEnd = hoverPoint;
            }
            return;
        }
        areaDragging = false;
        var minX = Math.floorDiv((int) Math.floor(Math.min(areaStart.x(), areaEnd.x())), 16) * 16;
        var minY = Math.floorDiv((int) Math.floor(Math.min(areaStart.y(), areaEnd.y())), 16) * 16;
        var maxX = Math.floorDiv((int) Math.floor(Math.max(areaStart.x(), areaEnd.x())), 16) * 16 + 16;
        var maxY = Math.floorDiv((int) Math.floor(Math.max(areaStart.y(), areaEnd.y())), 16) * 16 + 16;
        area = new Bounds(minX, minY, maxX, maxY);
        areaStats = null;
    }

    private record AreaStats(int cells, int layers, int lowest, int highest, long revision) {}

    private AreaStats areaStats(GeoDataAccess data) {
        var stats = areaStats;
        if (stats != null && stats.revision() == data.revision()) {
            return stats;
        }
        var cells = 0;
        var layers = 0;
        var lowest = Integer.MAX_VALUE;
        var highest = Integer.MIN_VALUE;
        for (var x = area.minX(); x < area.maxX(); x += 16) {
            for (var y = area.minY(); y < area.maxY(); y += 16) {
                var cell = data.cellAt(x, y);
                if (cell.availability() != Availability.AVAILABLE) {
                    continue;
                }
                cells++;
                layers += cell.layers().size();
                for (var layer : cell.layers()) {
                    lowest = Math.min(lowest, layer.height());
                    highest = Math.max(highest, layer.height());
                }
            }
        }
        areaStats = new AreaStats(cells, layers, lowest, highest, data.revision());
        return areaStats;
    }

    private void drawAreaCard(GeodataState state) {
        if (area == null || areaSeed == null) {
            return;
        }
        var data = state.queryData();
        var stats = areaStats(data);
        var columns = (area.maxX() - area.minX()) / 16;
        var rows = (area.maxY() - area.minY()) / 16;
        GeodataWidgets.section("SELECTED AREA", GeodataWidgets.ROW);
        GeodataWidgets.value(
                "Cells", String.format(Locale.ROOT, "%,d \u00b7 %d \u00d7 %d", stats.cells(), columns, rows));
        GeodataWidgets.value("Layers", String.format(Locale.ROOT, "%,d", stats.layers()));
        if (stats.layers() > 0) {
            GeodataWidgets.value(
                    "Height",
                    stats.lowest() == stats.highest()
                            ? "Z " + stats.lowest()
                            : "Z " + stats.lowest() + " to " + stats.highest());
        }
        GeodataWidgets.tip(
                "Actions change the layer near the height of the pressed cell, Z " + areaSeed.height() + ", within "
                        + GeodataEditingPanel.AREA_TOLERANCE + " units, in every cell. Floors above and below stay.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (GeodataWidgets.rowButton("Open all", 0, 2)) {
            reportAreaResult(editing.applyAreaAction(state, area, areaSeed, AreaAction.OPEN_ALL, 0), "opened");
        }
        GeodataWidgets.tip("Opens every direction of the floor in each cell.");
        if (GeodataWidgets.rowButton("Block all", 1, 2)) {
            reportAreaResult(editing.applyAreaAction(state, area, areaSeed, AreaAction.BLOCK_ALL, 0), "blocked");
        }
        GeodataWidgets.tip("Closes every direction of the floor in each cell.");
        if (areaHeight.get() == Integer.MIN_VALUE) {
            areaHeight.set(areaSeed.height());
        }
        GeodataWidgets.integer("Z", areaHeight, 8);
        ImGui.sameLine();
        if (HudWidgets.button(
                "geo-area-height",
                null,
                "Set Z",
                ImGui.getContentRegionAvailX(),
                HudWidgets.CONTROL_HEIGHT,
                false,
                true)) {
            reportAreaResult(
                    editing.applyAreaAction(state, area, areaSeed, AreaAction.SET_HEIGHT, areaHeight.get()),
                    "moved to Z " + Math.round(areaHeight.get() / 8f) * 8);
        }
        if (GeodataWidgets.rowButton("Delete layer", 0, 2)) {
            reportAreaResult(
                    editing.applyAreaAction(state, area, areaSeed, AreaAction.DELETE_LAYER, 0), "lost the layer");
        }
        GeodataWidgets.tip("Deletes the floor near the pressed height in each cell.");
        if (GeodataWidgets.rowButton("Copy", 1, 2)) {
            copyArea(state);
        }
        GeodataWidgets.tip(
                "Copies the cells, heights relative to the pressed floor. Ctrl+V pastes them at the cell under the"
                        + " pointer.",
                UNDO_KEY.replace("Z", "C"));
        if (clip != null) {
            GeodataWidgets.hint(String.format(
                    Locale.ROOT,
                    "Copied %d \u00d7 %d cells. %s pastes them at the cell under the pointer.",
                    clip.columns(),
                    clip.rows(),
                    UNDO_KEY.replace("Z", "V")));
        }
        if (!areaMessage.isBlank()) {
            GeodataWidgets.hint(areaMessage);
        }
    }

    private void reportAreaResult(int cells, String what) {
        areaMessage = cells < 0
                ? editing.getProblem()
                : cells + (cells == 1 ? " cell " : " cells ") + what + ". Undo takes it back.";
        areaStats = null;
    }

    private void copyArea(GeodataState state) {
        try {
            clip = GeoEditingSession.copy(state.queryData(), area, areaSeed.height());
            areaMessage = "";
        } catch (RuntimeException failure) {
            areaMessage = failure.getMessage();
        }
    }

    private void pasteClip(GeodataState state) {
        var at = state.hovered;
        if (at == null) {
            areaMessage = "Point at the cell to paste at, then press " + UNDO_KEY.replace("Z", "V") + ".";
            return;
        }
        var cells = editing.paste(state, clip, at);
        areaMessage = cells < 0
                ? editing.getProblem()
                : "Pasted "
                        + clip.count()
                        + (clip.count() == 1 ? " cell" : " cells")
                        + " at "
                        + at.ref().x()
                        + ", "
                        + at.ref().y()
                        + ". Undo takes it back.";
    }

    private void drawArea(Camera camera, ImDrawList draw, int width, int height) {
        if (areaDragging && areaStart != null && areaEnd != null) {
            var minX = Math.floorDiv((int) Math.floor(Math.min(areaStart.x(), areaEnd.x())), 16) * 16;
            var minY = Math.floorDiv((int) Math.floor(Math.min(areaStart.y(), areaEnd.y())), 16) * 16;
            var maxX = Math.floorDiv((int) Math.floor(Math.max(areaStart.x(), areaEnd.x())), 16) * 16 + 16;
            var maxY = Math.floorDiv((int) Math.floor(Math.max(areaStart.y(), areaEnd.y())), 16) * 16 + 16;
            GeodataEditingPanel.drawOutline(
                    camera,
                    draw,
                    minX,
                    minY,
                    maxX,
                    maxY,
                    (float) areaStart.z() + 3,
                    GeodataWidgets.ACCENT,
                    width,
                    height);
        } else if (area != null && areaSeed != null) {
            GeodataEditingPanel.drawOutline(
                    camera,
                    draw,
                    area.minX(),
                    area.minY(),
                    area.maxX(),
                    area.maxY(),
                    areaSeed.height() + 3,
                    GeodataWidgets.ACCENT,
                    width,
                    height);
        }
    }

    private List<Hit> pick(GeodataState state, Camera camera, float mouseX, float mouseY, int width, int height) {
        var data = state.queryData();
        var ndcX = mouseX / width * 2f - 1f;
        var ndcY = 1f - mouseY / height * 2f;
        var origin = camera.getCursorRayOrigin(ndcX, ndcY);
        var direction = camera.getCursorRayDirection(ndcX, ndcY);
        var horizontal = Math.max(.001, Math.hypot(direction.getX(), direction.getY()));
        var display = state.display;
        var hide = display.heightFilter() && !display.dimOtherHeights();
        var picked = GeoDataPicker.pickBounded(
                data,
                new GeoPoint(origin.getX(), origin.getY(), origin.getZ()),
                new GeoPoint(direction.getX(), direction.getY(), direction.getZ()),
                Math.min(300_000, 16_384 / horizontal),
                hide ? display.minHeight() : Short.MIN_VALUE,
                hide ? display.maxHeight() : Short.MAX_VALUE);
        if (picked.truncated() && picked.hits().isEmpty()) {
            return List.of();
        }
        var shown = new ArrayList<Hit>();
        for (var candidate : picked.hits()) {
            var cell = data.cellAt(candidate.cell().x(), candidate.cell().y());
            var layer = cell.layers().get(candidate.cell().layer());
            if (display.layerAlpha(data, cell, layer) > 0) {
                shown.add(candidate);
            }
        }
        return shown;
    }

    private void drawSelectionTag(GeodataState state, Camera camera, ImDrawList draw, int width, int height) {
        var selected = state.inspection.selected;
        if (selected == null) {
            return;
        }
        var ref = selected.ref();
        var data = state.queryData();
        var heights = data.cellAt(ref.x(), ref.y()).layers().stream()
                .mapToInt(Layer::height)
                .distinct()
                .sorted()
                .toArray();
        var anchor =
                camera.projectToScreenRaw(new Vec3(ref.x() + 8, ref.y() + 8, selected.height() + 4), width, height);
        if (anchor == null) {
            return;
        }
        if (heights.length > 1) {
            var bottom = camera.projectToScreenRaw(new Vec3(ref.x() + 8, ref.y() + 8, heights[0] + 2), width, height);
            var top = camera.projectToScreenRaw(
                    new Vec3(ref.x() + 8, ref.y() + 8, heights[heights.length - 1] + 2), width, height);
            if (bottom != null && top != null) {
                draw.addLine(
                        bottom.getX(), bottom.getY(), top.getX(), top.getY(), ImColor.rgba(255, 255, 255, 120), 1.5f);
                for (var z : heights) {
                    var tick = camera.projectToScreenRaw(new Vec3(ref.x() + 8, ref.y() + 8, z + 2), width, height);
                    if (tick != null) {
                        var color = z == selected.height() ? GeodataWidgets.ACCENT : ImColor.rgba(255, 255, 255, 150);
                        draw.addCircleFilled(tick.getX(), tick.getY(), z == selected.height() ? 4 : 2.5f, color, 12);
                    }
                }
            }
        }
        var label = "Z "
                + selected.height()
                + (heights.length > 1
                        ? "  ·  " + (indexOf(heights, selected.height()) + 1) + "/" + heights.length
                        : "");
        if (!isUnderHints(anchor.getX(), anchor.getY() - 14, label)) {
            tag(draw, anchor.getX(), anchor.getY() - 14, label, GeodataWidgets.ACCENT);
        }
    }

    private boolean isUnderHints(float x, float y, String text) {
        var width = ImGui.calcTextSizeX(text) + 14;
        var top = y - ImGui.getFontSize() - 8;
        for (var area : hintAreas) {
            if (x + width * .5f > area[0] && x - width * .5f < area[2] && y + 6 > area[1] && top < area[3]) {
                return true;
            }
        }
        return false;
    }

    private void drawHints(ImDrawList draw, Viewport viewport, GeodataState state) {
        var items = new ArrayList<String[]>();
        var selected = state.inspection.selected;
        switch (tool) {
            case INSPECT -> {
                items.add(new String[] {"Click", "select"});
                items.add(new String[] {"Click again", "floor below"});
                if (selected != null
                        && state.queryData()
                                        .cellAt(
                                                selected.ref().x(),
                                                selected.ref().y())
                                        .layers()
                                        .size()
                                > 1) {
                    items.add(new String[] {"[ ]", "floor down / up"});
                }
                items.add(new String[] {"Drag", "route A to B"});
                items.add(new String[] {"Shift+Drag", "select area"});
                if (area != null) {
                    items.add(new String[] {UNDO_KEY.replace("Z", "C"), "copy"});
                }
                if (clip != null) {
                    items.add(new String[] {UNDO_KEY.replace("Z", "V"), "paste"});
                }
                items.add(new String[] {"F", "focus"});
                items.add(new String[] {"I", "isolate"});
                items.add(new String[] {"Z", "slice"});
                items.add(new String[] {"X", "x-ray"});
                if (selected != null) {
                    items.add(new String[] {"Esc", "deselect"});
                }
            }
            case ROUTE -> {
                items.add(new String[] {"Click", state.inspection.pointA == null ? "place A" : "place B"});
                items.add(new String[] {"Shift+Click", "move A"});
                if (state.inspection.pointB != null) {
                    items.add(new String[] {"Ctrl+Click", "add stop"});
                }
                items.add(new String[] {"Drag", "A to B"});
                if (routes.isWalking()) {
                    items.add(new String[] {"Space", "pause"});
                }
                items.add(new String[] {"Esc", routes.isWalking() ? "stop walking" : "clear"});
            }
            case EDIT -> {
                var busy = editing.getBusyLabel();
                if (busy != null) {
                    items.add(new String[] {"Wait", busy.toLowerCase(Locale.ROOT) + ", edits are paused"});
                } else if (editing.getTool() == EditTool.EDGES) {
                    items.add(new String[] {"Click a side", "toggle"});
                    items.add(new String[] {"Click the middle", "select"});
                    items.add(new String[] {"Drag", "along a wall"});
                    items.add(new String[] {"Shift", "one side"});
                } else {
                    items.add(new String[] {"Drag", editing.isRectangleShape() ? "rectangle" : "paint"});
                    if (!editing.isRectangleShape()) {
                        items.add(new String[] {"Shift+Drag", "rectangle"});
                        items.add(new String[] {"[ ]", "brush size"});
                        items.add(new String[] {"Ctrl+Wheel", "brush size"});
                    }
                    if (editing.getTool() == EditTool.HEIGHT) {
                        items.add(new String[] {"Alt+Click", "take height"});
                    }
                    items.add(new String[] {"Esc", "cancel stroke"});
                }
                items.add(new String[] {UNDO_KEY, "undo"});
                items.add(new String[] {"Shift+" + UNDO_KEY, "redo"});
            }
            case CHECK -> {
                items.add(new String[] {"Click a mark", "select finding"});
                items.add(new String[] {"X", "x-ray"});
            }
        }
        items.add(new String[] {"Q R B C", "tools"});
        items.add(new String[] {"F1", "all keys"});
        GeodataWidgets.hintStrip(draw, viewport, tool.label.toUpperCase(Locale.ROOT), items, hintAreas);
    }

    private void stepFloor(GeodataState state, boolean up) {
        var data = state.queryData();
        var selected = state.inspection.selected;
        var layers = new ArrayList<>(
                data.cellAt(selected.ref().x(), selected.ref().y()).layers());
        layers.sort(Comparator.comparingInt(Layer::height));
        Layer next = null;
        for (var layer : layers) {
            if (up && layer.height() > selected.height() && (next == null || layer.height() < next.height())) {
                next = layer;
            }
            if (!up && layer.height() < selected.height() && (next == null || layer.height() > next.height())) {
                next = layer;
            }
        }
        if (next != null) {
            state.inspection.select(data, selected.ref().x(), selected.ref().y(), next.index());
            if (isSliced(state)) {
                slice(state, next.height());
            }
        }
    }

    private boolean isSliced(GeodataState state) {
        return state.display.heightFilter() && sliceActive;
    }

    private void toggleSlice(GeodataState state) {
        var selected = state.inspection.selected;
        if (isSliced(state) || selected == null) {
            state.display = state.display.withHeightFilter(false, -32768, 32767, state.display.dimOtherHeights());
            sliceActive = false;
            return;
        }
        slice(state, selected.height());
    }

    private void slice(GeodataState state, int height) {
        state.display =
                state.display.withHeightFilter(true, Math.max(-32768, height - 48), Math.min(32767, height + 48), true);
        sliceActive = true;
    }

    private static Status statusLine(GeodataState state) {
        if (state.path == null) {
            return new Status("Choose a folder with region files", GeodataWidgets.MUTED, "");
        }
        var status = state.status == null ? "" : state.status;
        if (state.loading && state.displayLayers == 0) {
            return new Status("Loading…", GeodataWidgets.MUTED, status);
        }
        var source = state.getSourceSnapshot();
        var regions = source == null
                ? 0
                : (int) source.getTiles().values().stream()
                        .filter(tile -> tile.region() != null)
                        .count();
        var problems = status.contains("Missing") || status.contains("Cannot") || status.contains("not found");
        if (status.contains("not found") || status.startsWith("Cannot load")) {
            return new Status("Folder unavailable", GeodataWidgets.BLOCKED, status);
        }
        var text = String.format(
                Locale.ROOT,
                "%d %s · %,d layers shown",
                regions,
                regions == 1 ? "region" : "regions",
                state.displayLayers);
        if (state.editing != null) {
            text += " · editing";
        }
        return new Status(
                problems ? text + " · some files missing" : text,
                problems ? GeodataWidgets.WARNING : GeodataWidgets.MUTED,
                status);
    }

    private static void guideLinks() {
        GeodataWidgets.section("NEW TO GEODATA?");
        GeodataWidgets.space(GeodataWidgets.ROW);
        HudWidgets.guideLink("What geodata is and where to get it", "geodata/what-is-geodata");
        GeodataWidgets.space(GeodataWidgets.ROW);
        HudWidgets.guideLink("Build or open geodata, step by step", "geodata/get-geodata");
    }

    private static List<GeoTile> openRegions(GeodataState state) {
        return state.tiles == null ? List.of() : state.tiles.getRegions();
    }

    private static String sourceFile(GeoDataSnapshot data, GeoTile tile) {
        var region = data.getRegion(tile.x(), tile.y());
        return region == null ? tile.fileName() : region.getFormat().getFileName(tile);
    }

    private static void centeredText(String text) {
        var width = ImGui.getContentRegionAvailX();
        ImGui.setCursorPosX(ImGui.getCursorPosX() + Math.max(0, (width - ImGui.calcTextSizeX(text)) * .5f));
        ImGui.textUnformatted(text);
    }

    private static String capacityLabel(int limit) {
        return String.format(
                Locale.ROOT, "%.2f M vertices · up to %d MB", limit / 1_000_000.0, limit * 21L / 1_000_000);
    }

    private static void drawMoves(GeoDataAccess data, GeoQueryEngine.Selection resolved) {
        var engine = new GeoQueryEngine(data);
        var directions = new Direction[] {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        var texts = new String[4];
        var details = new String[4];
        var open = new boolean[4];
        for (var index = 0; index < 4; index++) {
            var edge = engine.computeTransition(resolved, directions[index]);
            open[index] = edge.failure() == Failure.NONE && edge.to() != null;
            if (open[index]) {
                var delta = edge.to().layer().height() - resolved.layer().height();
                texts[index] =
                        delta == 0 ? "Walk, same height" : delta > 0 ? "Step up " + delta : "Step down " + -delta;
                details[index] = "Z " + edge.to().layer().height();
            } else {
                texts[index] = switch (edge.failure()) {
                    case DIRECTION_BLOCKED -> "Blocked";
                    case DATA_UNAVAILABLE -> "No data loaded";
                    case NO_LAYER -> "No ground";
                    default -> toReadable(edge.failure().name());
                };
                details[index] = "";
            }
        }
        var same = texts[0].equals(texts[1]) && texts[0].equals(texts[2]) && texts[0].equals(texts[3]);
        for (var index = 0; index < (same ? 1 : 4); index++) {
            ImGui.textColored(
                    open[index] ? GeodataWidgets.OPEN : GeodataWidgets.BLOCKED,
                    same ? "All sides" : GeodataWidgets.letterOf(directions[index]));
            if (same) {
                ImGui.sameLine(0, 8);
            } else {
                ImGui.sameLine(28);
            }
            ImGui.textColored(open[index] ? GeodataWidgets.TEXT : GeodataWidgets.MUTED, texts[index]);
            if (!details[index].isEmpty() && !same) {
                ImGui.sameLine();
                ImGui.textColored(GeodataWidgets.FAINT, details[index]);
            }
        }
    }

    private static void drawNsweBadge(int nswe) {
        var draw = ImGui.getWindowDrawList();
        var maxX = ImGui.getItemRectMaxX();
        var minY = ImGui.getItemRectMinY();
        var size = ImGui.getItemRectMaxY() - minY;
        var cx = maxX - size * .5f - 4;
        var cy = minY + size * .5f;
        var half = size * .26f;
        draw.addRect(cx - half, cy - half, cx + half, cy + half, ImColor.rgba(255, 255, 255, 40), 1f);
        for (var direction : Direction.values()) {
            var color = (nswe & direction.getMask()) != 0 ? GeodataWidgets.OPEN : GeodataWidgets.BLOCKED;
            var dx = direction.getDx() / 16f;
            var dy = direction.getDy() / 16f;
            draw.addLine(
                    cx + dx * half - dy * half,
                    cy + dy * half - dx * half,
                    cx + dx * half + dy * half,
                    cy + dy * half + dx * half,
                    color,
                    2);
        }
    }

    private static boolean selectAt(GeodataState state, double x, double y, double z) {
        var data = state.queryData();
        if (data == null) {
            return false;
        }
        var cell = data.cellAt((int) Math.floor(x), (int) Math.floor(y));
        if (cell.availability() != Availability.AVAILABLE) {
            return false;
        }
        var base = new GeoQueryEngine(data)
                .findBaseCell(cell, Double.isNaN(z) ? Integer.MAX_VALUE - 64 : (int) Math.round(z));
        state.inspection.select(
                data,
                (int) Math.floor(x),
                (int) Math.floor(y),
                base == null ? -1 : base.layer().index());
        return true;
    }

    private static boolean isCommandHeld(ImGuiIO io) {
        return io.getKeyCtrl() || io.getKeySuper();
    }

    private static boolean isLayerVisible(
            GeodataState state,
            ViewerRenderBackend backend,
            Hit hit,
            float mouseX,
            float mouseY,
            int width,
            int height) {
        if (state.throughGeometry || backend == null) {
            return true;
        }
        var scene = backend.getSceneRayDistance(mouseX / width * 2f - 1f, 1f - mouseY / height * 2f, 300_000);
        return !Float.isFinite(scene) || hit.distance() <= scene + 64;
    }

    private static Selection toSelection(GeoDataAccess data, Hit hit) {
        var cell = data.cellAt(hit.cell().x(), hit.cell().y());
        var layer = cell.layers().get(hit.cell().layer());
        return new Selection(hit.cell(), layer.height(), layer.nswe());
    }

    private static void select(GeodataState state, GeoDataAccess data, List<Hit> hits) {
        if (hits.isEmpty()) {
            state.inspection.selected = null;
            return;
        }
        state.inspection.selectNextHit(data, hits);
    }

    private static int indexOf(int[] heights, int height) {
        for (var index = 0; index < heights.length; index++) {
            if (heights[heights.length - 1 - index] == height) {
                return index;
            }
        }
        return 0;
    }

    private static void tag(ImDrawList draw, float x, float y, String text, int accent) {
        var width = ImGui.calcTextSizeX(text) + 14;
        var height = ImGui.getFontSize() + 8;
        var left = x - width * .5f;
        var top = y - height;
        draw.addRectFilled(left, top, left + width, top + height, ImColor.rgba(8, 14, 20, 230), 5f);
        draw.addRect(left, top, left + width, top + height, accent, 5, 0, 1);
        draw.addTriangleFilled(x - 5, top + height, x + 5, top + height, x, top + height + 6, accent);
        draw.addText(left + 7, top + 4, GeodataWidgets.TEXT, text);
    }

    private static void drawHoverChip(
            GeoDataAccess data, Selection hovered, ImDrawList draw, float mouseX, float mouseY, Viewport viewport) {
        var layers = data.cellAt(hovered.ref().x(), hovered.ref().y()).layers().size();
        var text = "Z " + hovered.height() + (layers > 1 ? "  ·  " + layers + " floors" : "");
        var width = ImGui.calcTextSizeX(text) + 38;
        var height = ImGui.getFontSize() + 10;
        var x = Math.min(mouseX + 18, viewport.right() - width - 4);
        var y = Math.min(mouseY + 18, viewport.bottom() - height - 4);
        draw.addRectFilled(x, y, x + width, y + height, ImColor.rgba(8, 14, 20, 225), 5f);
        var cx = x + 12;
        var cy = y + height * .5f;
        var half = 4.5f;
        for (var direction : Direction.values()) {
            var color = (hovered.nswe() & direction.getMask()) != 0 ? GeodataWidgets.OPEN : GeodataWidgets.BLOCKED;
            var dx = direction.getDx() / 16f;
            var dy = direction.getDy() / 16f;
            draw.addLine(
                    cx + dx * half - dy * half,
                    cy + dy * half - dx * half,
                    cx + dx * half + dy * half,
                    cy + dy * half + dx * half,
                    color,
                    2);
        }
        draw.addText(x + 26, y + 5, GeodataWidgets.TEXT, text);
    }

    private static String toReadable(String value) {
        return GeodataRoutes.toReadable(value);
    }

    private static String formatNswe(int nswe) {
        var text = new StringBuilder();
        for (var direction : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            text.append((nswe & direction.getMask()) != 0 ? GeodataWidgets.letterOf(direction) : "·");
        }
        return text.toString();
    }
}
