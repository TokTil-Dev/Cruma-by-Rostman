package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.area.AreaChecks;
import com.rost.cruma.core.area.AreaChecks.Finding;
import com.rost.cruma.core.area.AreaChecks.Kind;
import com.rost.cruma.core.area.AreaCopies;
import com.rost.cruma.core.area.AreaDataFile;
import com.rost.cruma.core.area.AreaDataWriter;
import com.rost.cruma.core.area.AreaDocument;
import com.rost.cruma.core.area.AreaDocument.State;
import com.rost.cruma.core.area.AreaEdits;
import com.rost.cruma.core.area.AreaPoint;
import com.rost.cruma.core.area.AreaReview;
import com.rost.cruma.core.area.AreaSummary;
import com.rost.cruma.core.area.AreaTiles;
import com.rost.cruma.core.area.AreaType;
import com.rost.cruma.core.area.ServerArea;
import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.spawn.Polygons;
import com.rost.cruma.core.spawn.SourceSpan;
import com.rost.cruma.core.spawn.SpawnChecks.Issue;
import com.rost.cruma.core.spawn.SpawnChecks.Severity;
import com.rost.cruma.core.spawn.SpawnEdits;
import com.rost.cruma.core.spawn.SpawnTerritory;
import com.rost.cruma.core.spawn.TerritoryPoint;
import com.rost.cruma.viewer.application.WindowContext.Keyboard;
import com.rost.cruma.viewer.application.WindowContext.Keyboard.Key;
import com.rost.cruma.viewer.geodata.ClientFloors;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.LoadedTiles;
import com.rost.cruma.viewer.render.SpawnOverlayState;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import com.rost.cruma.viewer.spawn.AreaAccess;
import com.rost.cruma.viewer.spawn.AreaSource;
import com.rost.cruma.viewer.spawn.AreaSource.Phase;
import com.rost.cruma.viewer.spawn.AreaStyle;
import com.rost.cruma.viewer.spawn.SpawnGround;
import com.rost.cruma.viewer.spawn.SpawnGround.Drape;
import com.rost.cruma.viewer.spawn.SpawnProjector;
import com.rost.cruma.viewer.spawn.SpawnShapes;
import com.rost.cruma.viewer.spawn.SpawnStyle;
import com.rost.cruma.viewer.ui.FindingWidgets.FindingList;
import com.rost.cruma.viewer.ui.FindingWidgets.FindingList.Group;
import com.rost.cruma.viewer.ui.GeodataEditingPanel.Leave;
import com.rost.cruma.viewer.ui.GeodataWorkspace.Viewport;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.SaveReviewDialog.Answer;
import com.rost.cruma.viewer.ui.SaveReviewDialog.Content;
import com.rost.cruma.viewer.ui.SpawnPanelWidgets.MapPoint;
import com.rost.cruma.viewer.ui.SpawnPanelWidgets.Slice;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImDrawFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.Nullable;

public final class AreaWorkspace implements AutoCloseable, OverlaySwitch {
    private static final float BLOCK = GeodataWidgets.BLOCK;
    private static final float SECTION = GeodataWidgets.SECTION;
    private static final float ROW = GeodataWidgets.ROW;
    private static final float MAP_HEIGHT = 150;
    private static final float FOOTER = SaveFooter.HEIGHT;

    private static final float DRAG_START = 3;

    private static final int NEW_SIZE = 800;

    private static final String UNDO_KEY =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac") ? "Cmd+Z" : "Ctrl+Z";

    private static final float CURTAIN = 44;

    private static final int MAX_AREAS = 240;

    private static final int MAX_TAGS = 10;

    private static final double TAG_DISTANCE = 6000;

    private static final int TAG_STACK = 3;

    private static final double VOLUME_REACH = 3000;

    private static final int LISTED_RESULTS = 200;

    private static final int NEARBY_ROWS = 6;

    private static final float POLE = 120;

    private static final int RESTART_ARGB = 0xFFC8D2DC;
    private static final int TAG_BACKING = ImColor.rgba(10, 16, 22, 205);

    private final AreaSource source = new AreaSource();
    private final SpawnGround ground;
    private final GroundClicks clicks;
    private final EditMerge merge = new EditMerge();
    private final SaveReviewDialog reviewDialog =
            new SaveReviewDialog("area", AreaDataFile.FILE_NAME, "the server drops the area");
    private final SaveFooter footer = new SaveFooter("area", AreaDataFile.FILE_NAME);
    private final AreaFields fields = new AreaFields(new FieldHost());
    private final AreaMapLayer mapLayer = new AreaMapLayer(this);
    private final Clock clock = Clock.systemDefaultZone();

    private final List<int[]> drawnCorners = new ArrayList<>();

    private final FolderPicker folders = FolderPicker.scripts();
    private final SpawnProjector projector = new SpawnProjector();
    private final float[] screen = new float[2];
    private final ImString search = new ImString(96);
    private final int[] distance = {10000};

    private final EnumSet<AreaStyle> hiddenStyles = EnumSet.of(AreaStyle.WATER);

    private final Map<ServerArea, SpawnTerritory> territories = new IdentityHashMap<>();

    private final FindingList findingList = new FindingList(20);
    private final List<float[]> hintAreas = new ArrayList<>();
    private boolean visible;

    private boolean openedOnce;

    private boolean showTags = true;
    private boolean showVolume = true;
    private boolean showInstanced = true;
    private boolean showOff = true;
    private @Nullable OverlaySwitch spawnOverlay;
    private @Nullable Path requestedFolder;

    private @Nullable Path folderRequest;

    private long openedGeneration = -1;
    private int selected = -1;
    private int hovered = -1;

    private int panelHover = -1;

    private List<Integer> stack = List.of();

    private @Nullable GeoPoint aimed;

    private List<Drawn> drawn = List.of();
    private boolean pointerOwned;
    private String message = "";
    private int messageColor = GeodataWidgets.MUTED;
    private @Nullable State checkedState;
    private @Nullable Set<String> checkedTypes;
    private List<Finding> findings = List.of();
    private @Nullable Finding findingSelected;
    private @Nullable State compositionState;
    private long compositionScope = -1;
    private List<Slice<AreaStyle>> composition = List.of();
    private int loadedCount;
    private String searched = "";
    private @Nullable State listedState;
    private List<Result> results = List.of();
    private int searchCursor;
    private int scrolledCursor = -1;
    private int hiddenMatches;
    private double viewReach = 10000;
    private double lastGroundUnder = Double.NaN;
    private @Nullable Camera camera;
    private @Nullable Viewport viewport;
    private @Nullable LoadedTiles tiles;
    private volatile @Nullable ClientFloors clientFloors;
    private @Nullable ImFont mono;
    private @Nullable ViewerRenderBackend backend;
    private @Nullable Content review;
    private @Nullable Leave pendingLeave;
    private @Nullable Leave approvedLeave;

    private @Nullable Path pendingFolder;

    private boolean pendingReload;

    private boolean panelRequested;

    private int editing = -1;

    private @Nullable Drag drag;
    private @Nullable Handle hoveredHandle;
    private List<Handle> handles = List.of();

    private @Nullable String drawingType;

    private int drawingPart = -1;

    private boolean closingHot;

    private @Nullable Placing placing;

    private @Nullable String markedField;

    private int selectedBeforeClick = -1;

    private @Nullable State issuesState;
    private @Nullable State issuesSaved;
    private List<Issue> issues = List.of();

    public AreaWorkspace(SpawnGround ground) {
        this.ground = ground;
        this.clicks = new GroundClicks(ground);
    }

    private record Drawn(
            int index,
            ServerArea area,
            AreaStyle style,
            float[] points,
            int[] corners,
            float groundZ,
            double away,
            boolean water) {}

    private record Result(int index, String title, String detail, double distance) {}

    private enum Grab {
        CORNER,
        MIDPOINT,
        MOVE,
        TOP,
        BOTTOM,
        POINT
    }

    private record Handle(Grab grab, int part, int corner, boolean restart, double x, double y, double z) {}

    private static final class Drag {
        private final Handle handle;
        private final float pressX;
        private final float pressY;
        private final ServerArea area;

        private final double unitsPerPoint;

        private boolean applied;

        private int value;

        private Drag(Handle handle, float pressX, float pressY, ServerArea area, double unitsPerPoint) {
            this.handle = handle;
            this.pressX = pressX;
            this.pressY = pressY;
            this.area = area;
            this.unitsPerPoint = unitsPerPoint;
            this.value = handle.grab() == Grab.TOP ? area.top() : area.bottom();
        }
    }

    private record Placing(int area, boolean restart) {}

    private final class FieldHost implements AreaFields.Host {
        @Override
        public void edit(String key, String label, UnaryOperator<State> change) {
            var document = source.getDocument();
            if (document != null) {
                AreaWorkspace.this.edit(document, key, label, change);
            }
        }

        @Override
        public void apply(String label, UnaryOperator<State> change) {
            var document = source.getDocument();
            if (document != null) {
                document.apply(label, change);
                merge.reset();
            }
        }

        @Override
        public boolean isPlacing(int area, boolean restart) {
            var waiting = placing;
            return waiting != null && waiting.area() == area && waiting.restart() == restart;
        }

        @Override
        public void togglePlacing(int area, boolean restart) {
            var stop = isPlacing(area, restart);
            stopGestures();
            if (!stop) {
                placing = new Placing(area, restart);
                visible = true;
            }
        }

        @Override
        public boolean isDrawingPart(int area) {
            return drawingPart == area;
        }

        @Override
        public void toggleDrawingPart(int area) {
            var stop = drawingPart == area;
            stopGestures();
            if (!stop) {
                drawingPart = area;
                visible = true;
            }
        }

        @Override
        public int @Nullable [] findGroundRange(ServerArea area) {
            return AreaWorkspace.this.findGroundRange(area);
        }

        @Override
        public @Nullable String getMarkedField() {
            return markedField;
        }

        @Override
        public void flyTo(double x, double y, double z) {
            var eye = camera;
            if (eye != null) {
                frame(
                        eye,
                        new Bounds3f(
                                (float) x - 200,
                                (float) y - 200,
                                (float) z,
                                (float) x + 200,
                                (float) y + 200,
                                (float) z + 200),
                        -35);
            }
        }

        @Override
        public void setMessage(String text) {
            AreaWorkspace.this.setMessage(text, GeodataWidgets.WARNING);
        }

        @Override
        public Set<String> getFileTypes() {
            var document = source.getDocument();
            return document == null ? Set.of() : document.getSavedTypes();
        }
    }

    public boolean isWanted() {
        return visible && source.getFolder() != null;
    }

    @Override
    public boolean isOverlayOn() {
        return visible;
    }

    @Override
    public void setOverlayOn(boolean on) {
        visible = on;
    }

    public void setSpawnOverlay(@Nullable OverlaySwitch spawns) {
        spawnOverlay = spawns;
    }

    public void tick(@Nullable Path scriptFolder, @Nullable ClientFloors floors) {
        source.poll();
        clientFloors = floors;
        if (!Objects.equals(scriptFolder, requestedFolder) && !source.isModified()) {
            requestedFolder = scriptFolder;
            source.open(scriptFolder);
        }
        if (openedGeneration != source.getGeneration()) {
            openedGeneration = source.getGeneration();
            selected = -1;
            hovered = -1;
            stack = List.of();
            territories.clear();
            checkedState = null;
            compositionState = null;
            listedState = null;
            findingSelected = null;
            findingList.reset(List.of());
            editing = -1;
            drag = null;
            review = null;
            markedField = null;
            stopGestures();
            merge.reset();
        }
    }

    public @Nullable AreaDocument getDocument() {
        return source.getDocument();
    }

    public AreaSource getSource() {
        return source;
    }

    public AreaAccess createAccess() {
        return new AreaAccess() {
            @Override
            public AreaSource getSource() {
                return source;
            }

            @Override
            public boolean isHeld() {
                return drag != null || review != null;
            }

            @Override
            public boolean hasGeodata() {
                return ground.isAvailable();
            }

            @Override
            public @Nullable GeoDataAccess readArea(double minX, double minY, double maxX, double maxY) {
                return ground.readArea(minX, minY, maxX, maxY);
            }

            @Override
            public double findClientFloor(double x, double y, double z) {
                var floors = clientFloors;
                return floors == null ? Double.NaN : floors.findFloorBelow(x, y, z);
            }
        };
    }

    public @Nullable Path consumeFolderRequest() {
        var request = folderRequest;
        folderRequest = null;
        return request;
    }

    public void setMessage(String text) {
        message = text;
        messageColor = GeodataWidgets.WARNING;
    }

    public boolean capturesPointer() {
        return pointerOwned;
    }

    public void handleKeys(Keyboard keys, boolean panelOpen) {
        if (keys.isClicked(Key.L) && !keys.isPressed(Key.COMMAND)) {
            visible = !visible;
        }
        var document = source.getDocument();
        if (!panelOpen || document == null) {
            return;
        }
        if (keys.isPressed(Key.COMMAND) && keys.isClicked(Key.Z)) {
            if (keys.isPressed(Key.SHIFT)) {
                document.redo();
            } else {
                document.undo();
            }
            drag = null;
            merge.reset();
            return;
        }
        if (keys.isPressed(Key.COMMAND) && keys.isClicked(Key.S)) {
            openReview(document);
            return;
        }
        var handle = hoveredHandle;
        if (keys.isClicked(Key.DELETE) && handle != null && handle.grab() == Grab.CORNER) {
            removeCorner(document, handle);
        }
        if (keys.isClicked(Key.F) && selected >= 0 && camera != null) {
            focus(camera, selected);
        }
    }

    public boolean handleEscape() {
        var active = drag;
        var document = source.getDocument();
        if (active != null) {
            if (active.applied && document != null) {
                document.undo();
            }
            drag = null;
            merge.reset();
            return true;
        }
        if (drawingType != null || drawingPart >= 0 || placing != null) {
            stopGestures();
            return true;
        }
        if (editing >= 0) {
            editing = -1;
            return true;
        }
        if (selected >= 0) {
            select(-1);
            return true;
        }
        return false;
    }

    public boolean requestLeave(Leave action) {
        if (!source.isModified()) {
            return false;
        }
        pendingLeave = action;
        return true;
    }

    public boolean holdFolder(Path folder) {
        if (!source.isModified()) {
            return false;
        }
        pendingLeave = Leave.SOURCE;
        pendingFolder = folder;
        pendingReload = false;
        return true;
    }

    public void drawGuard(@Nullable ImFont monoFont) {
        drawReview(monoFont);
        var leave = pendingLeave;
        if (leave == null) {
            return;
        }
        var document = source.getDocument();
        var errors = document == null ? List.<Issue>of() : SaveReviewDialog.filterErrors(findIssues(document));
        switch (LeaveGuardDialog.draw(
                "Unsaved area changes", AreaDataFile.FILE_NAME + " has edits that are not saved.", errors)) {
            case SAVE -> {
                source.save(reviewDialog.isBackup());
                clearPendingLeave();
            }
            case DISCARD -> {
                if (document != null) {
                    document.discardChanges();
                }
                merge.reset();
                if (pendingFolder != null) {
                    folderRequest = pendingFolder;
                }
                if (pendingReload) {
                    source.reload();
                }
                approvedLeave = leave;
                clearPendingLeave();
            }
            case CANCEL -> clearPendingLeave();
            case NONE -> {}
        }
    }

    public @Nullable Leave consumeApprovedLeave() {
        var leave = approvedLeave;
        approvedLeave = null;
        return leave;
    }

    public MapLayer getMapLayer() {
        return mapLayer;
    }

    public boolean consumePanelRequest() {
        var requested = panelRequested;
        panelRequested = false;
        return requested;
    }

    public @Nullable String getUnavailableReason() {
        return switch (source.getPhase()) {
            case EMPTY -> "Choose the server's script folder in Areas or NPC Spawns first.";
            case LOADING -> "Areas is still reading " + AreaDataFile.FILE_NAME + ". Try again in a moment.";
            case MISSING -> "The script folder has no " + AreaDataFile.FILE_NAME + ".";
            case FAILED -> AreaDataFile.FILE_NAME + " could not be read: " + source.getMessage();
            case READY -> null;
        };
    }

    public @Nullable String showNearestArea(Camera eye) {
        var document = source.getDocument();
        if (document == null) {
            return null;
        }
        var position = eye.getPosition();
        ServerArea best = null;
        var bestAway = Double.MAX_VALUE;
        for (var area : document.getState().areas()) {
            if (area == null
                    || area.points().size() < 3
                    || !isShown(area)
                    || !isInLoadedTiles(area.minX(), area.minY(), area.maxX(), area.maxY())) {
                continue;
            }
            var away = area.contains(position.getX(), position.getY(), position.getZ())
                    ? 0
                    : Polygons.distanceXy(area.points(), position.getX(), position.getY());
            if (away < bestAway || away == bestAway && best != null && area.area() < best.area()) {
                best = area;
                bestAway = away;
            }
        }
        if (best == null) {
            return null;
        }
        clearSearch();
        select(best.index());
        visible = true;
        focus(eye, best.index());
        return best.name().isEmpty() ? "an area without a name" : best.name();
    }

    int getSelected() {
        return selected;
    }

    boolean isShownOnMap(ServerArea area) {
        return isShown(area);
    }

    EnumSet<AreaStyle> getHiddenStyles() {
        return hiddenStyles;
    }

    boolean areInstancedShown() {
        return showInstanced;
    }

    void setInstancedShown(boolean shown) {
        showInstanced = shown;
    }

    boolean areOffShown() {
        return showOff;
    }

    void setOffShown(boolean shown) {
        showOff = shown;
    }

    @Nullable
    LoadedTiles getLoadedTiles() {
        return tiles;
    }

    void selectOnMap(int index) {
        clearSearch();
        select(index);
        visible = true;
        panelRequested = true;
        var eye = camera;
        if (eye != null) {
            focus(eye, index);
        }
    }

    public void drawViewport(
            SpawnOverlayState overlay,
            Camera camera,
            ViewerRenderBackend renderBackend,
            Viewport viewport,
            @Nullable LoadedTiles loadedTiles,
            boolean panelOpen,
            boolean pointerAllowed,
            boolean quiet,
            boolean helpOpen,
            boolean inspecting) {
        this.camera = camera;
        this.viewport = viewport;
        this.tiles = loadedTiles;
        this.backend = renderBackend;
        pointerOwned = false;
        var document = source.getDocument();
        var io = ImGui.getIO();
        var width = io.getDisplaySizeX();
        var height = io.getDisplaySizeY();
        overlay.areasVisible = visible && document != null && width > 0 && height > 0;
        if (!overlay.areasVisible) {
            overlay.clearAreas();
            hovered = -1;
            stack = List.of();
            drawn = List.of();
            handles = List.of();
            hoveredHandle = null;
            drag = null;
            return;
        }
        var state = document.getState();
        dropStaleSelection(state);
        projector.update(camera.getViewMatrix(), camera.getProjectionMatrix(), width, height);
        var eye = camera.getPosition();
        var groundUnder = ground.getGroundAt(eye.getX(), eye.getY(), eye.getZ());
        var floors = clientFloors;
        if (!Double.isFinite(groundUnder) && floors != null) {
            groundUnder = floors.findFloorBelow(eye.getX(), eye.getY(), eye.getZ());
        }
        if (Double.isFinite(groundUnder)) {
            lastGroundUnder = groundUnder;
        } else {
            groundUnder = lastGroundUnder;
        }
        viewReach = distance[0] + (Double.isFinite(groundUnder) ? Math.max(0, eye.getZ() - groundUnder) * 1.5 : 0);
        if (editing != selected) {
            editing = -1;
        }
        drawn = findVisibleAreas(state, camera, eye.getX(), eye.getY(), eye.getZ());

        var mouseX = ImGui.getMousePosX();
        var mouseY = ImGui.getMousePosY();
        var pointerFree = panelOpen
                && pointerAllowed
                && !inspecting
                && !io.getWantCaptureMouse()
                && viewport.contains(mouseX, mouseY);
        var ndcX = mouseX / width * 2f - 1f;
        var ndcY = 1f - mouseY / height * 2f;
        var origin = camera.getCursorRayOrigin(ndcX, ndcY);
        var direction = camera.getCursorRayDirection(ndcX, ndcY);
        var editable = panelOpen && editing >= 0 && !inspecting;
        handles = editable ? createHandles(state, eye.getX(), eye.getY()) : List.of();

        var active = drag;
        if (active != null) {
            pointerOwned = true;
            if (ImGui.isMouseDown(0)) {
                continueDrag(
                        document,
                        active,
                        origin.getX(),
                        origin.getY(),
                        origin.getZ(),
                        direction.getX(),
                        direction.getY(),
                        direction.getZ(),
                        mouseX,
                        mouseY);
            } else {
                endDrag(document, active);
                drag = null;
            }
        }
        var gesture = panelOpen && (drawingType != null || drawingPart >= 0 || placing != null);
        if (gesture) {
            handleGesture(
                    document,
                    renderBackend,
                    pointerFree,
                    ndcX,
                    ndcY,
                    origin.getX(),
                    origin.getY(),
                    origin.getZ(),
                    direction.getX(),
                    direction.getY(),
                    direction.getZ());
        }
        hovered = -1;
        stack = List.of();
        aimed = null;
        hoveredHandle = null;
        if (pointerFree && drag == null && !gesture) {
            hoveredHandle = handleAt(handles, mouseX, mouseY);
            if (hoveredHandle == null) {
                stack = pick(
                        origin.getX(),
                        origin.getY(),
                        origin.getZ(),
                        direction.getX(),
                        direction.getY(),
                        direction.getZ());
                hovered = clickTarget(stack);
            }
            if (ImGui.isMouseDoubleClicked(0) && hoveredHandle == null) {
                var target = stack.contains(selectedBeforeClick)
                        ? selectedBeforeClick
                        : stack.contains(selected) ? selected : hovered;
                if (target >= 0) {
                    select(target);
                    editing = target;
                }
            } else if (ImGui.isMouseClicked(0)) {
                selectedBeforeClick = selected;
                var handle = hoveredHandle;
                if (handle != null) {
                    var area = state.area(selected);
                    if (area != null) {
                        drag = new Drag(handle, mouseX, mouseY, area, computeUnitsPerPoint(handle));
                    }
                } else if (!editable
                        || !tryStartMove(
                                state,
                                origin.getX(),
                                origin.getY(),
                                origin.getZ(),
                                direction.getX(),
                                direction.getY(),
                                direction.getZ(),
                                mouseX,
                                mouseY)) {
                    select(hovered);
                }
            }
        }
        pointerOwned |= hovered >= 0 || hoveredHandle != null || gesture && pointerFree;

        overlay.areaFills.begin();
        overlay.areaLines.begin();
        drawAreas(overlay, eye.getX(), eye.getY(), eye.getZ(), quiet);
        drawHandles(overlay);
        drawGesture(overlay);
        overlay.areaFills.publish();
        overlay.areaLines.publish(width);

        var draw = ImGui.getBackgroundDrawList();
        draw.pushClipRect(viewport.left(), viewport.top(), viewport.right(), viewport.bottom(), false);
        hintAreas.clear();
        if (panelOpen && !helpOpen) {
            GeodataWidgets.hintStrip(draw, viewport, "AREAS", hintItems(), hintAreas);
        }
        drawPartWarnings(draw, state);
        if (showTags && !quiet) {
            drawTags(draw, eye.getX(), eye.getY(), eye.getZ());
        }
        drawPointerTag(draw);
        draw.popClipRect();
        if (pointerFree && hovered >= 0 && drag == null && !gesture) {
            drawHoverCard(document, state);
        }
    }

    public void drawPanel(Camera camera, ViewerRenderBackend renderBackend, @Nullable ImFont monoFont) {
        this.camera = camera;
        this.backend = renderBackend;
        this.mono = monoFont;
        panelHover = -1;
        if (!openedOnce) {
            openedOnce = true;
            visible = true;
        }
        drawHeader();
        drawSourceRow();
        var picked = folders.draw();
        if (picked != null) {
            message = "";
            if (!holdFolder(picked)) {
                folderRequest = picked;
            }
        }
        var document = source.getDocument();
        if (source.getPhase() != Phase.READY || document == null) {
            switch (source.getPhase()) {
                case EMPTY -> drawEmptySource();
                case MISSING -> drawMissingFile();
                case LOADING -> {
                    space(BLOCK);
                    GeodataWidgets.hint("Reading " + AreaDataFile.FILE_NAME + "…");
                }
                default -> {}
            }
            return;
        }
        var state = document.getState();
        dropStaleSelection(state);
        drawCountLine(state);
        if (!message.isBlank()) {
            space(ROW);
            GeodataWidgets.note(message, messageColor);
        }
        drawSearchBox(state);
        drawMakeChips(document);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        var footerShown = footerStatus(document).isShown();
        ImGui.beginChild("##areaBody", 0, footerShown ? -FOOTER : 0, false);
        ImGui.popStyleVar();
        ImGui.dummy(0, BLOCK - 2 * spacing());
        ImGui.pushTextWrapPos(0);
        if (drawingType != null || drawingPart >= 0) {
            drawDrawingCard(document);
            space(BLOCK);
        }
        state = document.getState();
        dropStaleSelection(state);
        if (!searched.isBlank()) {
            drawSearchResults(state, camera);
        } else if (selected >= 0) {
            drawAreaSection(document, state, camera);
        } else {
            drawOverview(document, state, camera);
        }
        ImGui.popTextWrapPos();
        ImGui.dummy(0, 8);
        ImGui.endChild();
        if (footerShown) {
            drawFooter(document);
        }
    }

    @Override
    public void close() {
        source.close();
    }

    private void dropStaleSelection(State state) {
        if (selected >= 0 && state.area(selected) == null) {
            selected = -1;
        }
    }

    private List<Drawn> findVisibleAreas(State state, Camera camera, float eyeX, float eyeY, float eyeZ) {
        var reach = viewReach;
        var frustum = camera.getFrustum();
        var candidates = new ArrayList<ServerArea>();
        var aways = new IdentityHashMap<ServerArea, Double>();
        for (var area : state.areas()) {
            if (area == null || area.points().size() < 3) {
                continue;
            }
            var focused = isFocused(area.index());
            if (!focused && !isShown(area)) {
                continue;
            }
            if (!isInLoadedTiles(area.minX(), area.minY(), area.maxX(), area.maxY())) {
                continue;
            }
            var away = boundsDistance(area, eyeX, eyeY);
            if (!focused && away > reach) {
                continue;
            }
            if (!focused
                    && !frustum.intersects(
                            area.minX(),
                            area.minY(),
                            Math.max(area.bottom(), -40000),
                            area.maxX(),
                            area.maxY(),
                            Math.min(area.top(), 40000))) {
                continue;
            }
            candidates.add(area);
            aways.put(area, away);
        }
        candidates.sort(Comparator.comparingDouble(aways::get));
        var result = new ArrayList<Drawn>();
        for (var area : candidates) {
            var focused = isFocused(area.index());
            if (result.size() >= MAX_AREAS && !focused) {
                continue;
            }
            var away = aways.get(area);
            var water = area.isWater();
            Drape outline;
            if (water) {
                outline = SpawnWorkspace.SpawnGroundFallback.flat(territoryOf(area), area.top());
            } else {
                var drape = !ground.isAvailable()
                        ? null
                        : area.index() == editing
                                ? ground.createDrapeNow(territoryOf(area))
                                : ground.getDrape(territoryOf(area));
                if (drape == null && ground.isAvailable() && !focused && away > reach * .5) {
                    continue;
                }
                outline = drape != null
                        ? drape
                        : SpawnWorkspace.SpawnGroundFallback.flat(territoryOf(area), fallbackHeight(area, eyeZ));
            }
            var points = outline.points();
            var sum = 0.0;
            for (var i = 2; i < points.length; i += 3) {
                sum += points[i];
            }
            var groundZ = (float) (sum / Math.max(1, points.length / 3));
            result.add(
                    new Drawn(area.index(), area, AreaStyle.of(area), points, outline.corners(), groundZ, away, water));
        }
        return result;
    }

    private float fallbackHeight(ServerArea area, float eyeZ) {
        var floors = clientFloors;
        if (floors != null) {
            var floor = floors.findFloorBelow(area.centerX(), area.centerY(), Math.min(area.top(), eyeZ));
            if (Double.isFinite(floor)) {
                return (float) Math.clamp(floor, area.bottom(), area.top());
            }
        }
        return Math.clamp(eyeZ - 160, area.bottom(), area.top());
    }

    private boolean isFocused(int index) {
        return index == selected || index == hovered || index == panelHover;
    }

    private boolean isShown(ServerArea area) {
        return !hiddenStyles.contains(AreaStyle.of(area))
                && (showInstanced || !area.hasSetting("instantzone_id"))
                && (showOff || area.isOnByDefault());
    }

    private List<Integer> pick(double ox, double oy, double oz, double dx, double dy, double dz) {
        var reach = viewReach * 2.5;
        GeoPoint point = null;
        if (Math.abs(dz) > 1e-6) {
            point = ground.findRayGround(new GeoPoint(ox, oy, oz), new GeoPoint(dx, dy, dz), reach);
            var floors = clientFloors;
            if (point == null && floors != null) {
                var hit = floors.findRayHit(ox, oy, oz, dx, dy, dz, reach);
                point = hit == null ? null : new GeoPoint(hit.x(), hit.y(), hit.z());
            }
        }
        var hits = new ArrayList<Drawn>();
        if (point != null) {
            aimed = point;
            for (var area : drawn) {
                if (holdsNear(area.area(), point.x(), point.y(), point.z())) {
                    hits.add(area);
                }
            }
        }
        if (hits.isEmpty() && Math.abs(dz) > 1e-6) {
            var nearest = Double.MAX_VALUE;
            for (var area : drawn) {
                var t = (area.groundZ() - oz) / dz;
                if (t <= 0 || t > reach) {
                    continue;
                }
                var x = ox + dx * t;
                var y = oy + dy * t;
                if (!area.area().containsXy(x, y)) {
                    continue;
                }
                if (t < nearest - 64) {
                    hits.clear();
                    nearest = t;
                }
                if (t <= nearest + 64) {
                    hits.add(area);
                    if (aimed == null) {
                        aimed = new GeoPoint(x, y, area.groundZ());
                    }
                }
            }
        }
        hits.sort(Comparator.comparingDouble(area -> area.area().area()));
        return hits.stream().map(Drawn::index).toList();
    }

    private int clickTarget(List<Integer> under) {
        if (under.isEmpty()) {
            return -1;
        }
        var at = under.indexOf(selected);
        return at < 0 ? under.getFirst() : under.get((at + 1) % under.size());
    }

    private void drawAreas(SpawnOverlayState overlay, float eyeX, float eyeY, float eyeZ, boolean quiet) {
        for (var item : drawn) {
            var area = item.area();
            var index = item.index();
            var chosen = index == selected;
            var focused = isFocused(index);
            var style = item.style();
            var points = item.points();
            var far = item.away() > viewReach * .5;
            var range = Math.hypot(item.away(), eyeZ - item.groundZ());
            var lift = 4 + (float) range * .004f;
            float alpha;
            float width;
            if (quiet && !focused) {
                alpha = .5f;
                width = 1.3f;
            } else {
                alpha = chosen ? 1f : focused ? .95f : far ? .62f : .82f;
                width = chosen ? 3.2f : focused ? 2.6f : 1.8f;
            }
            if (!area.isOnByDefault()) {
                alpha *= .55f;
            }
            var color = style.getImguiColor(alpha, focused ? 1.15f : 1f);
            var step = focused ? 1 : Math.clamp(Integer.highestOneBit(Math.max(1, (int) (range / 2500))), 1, 32);
            addOutline(overlay, points, item.corners(), step, lift, color, width);
            if (item.water()) {
                if (!quiet || focused) {
                    overlay.areaFills.addQuad(
                            points[0],
                            points[1],
                            points[2] + lift,
                            style.getImguiColor(focused ? .1f : .045f),
                            points[3],
                            points[4],
                            points[5] + lift,
                            style.getImguiColor(focused ? .1f : .045f),
                            points[6],
                            points[7],
                            points[8] + lift,
                            style.getImguiColor(focused ? .1f : .045f),
                            points[9],
                            points[10],
                            points[11] + lift,
                            style.getImguiColor(focused ? .1f : .045f));
                }
            } else if ((!quiet || focused) && (focused || item.away() <= distance[0])) {
                var curtain = focused ? CURTAIN * 1.25f : CURTAIN;
                var fill = (chosen ? .34f : focused ? .28f : far ? .1f : .16f) * (area.isOnByDefault() ? 1f : .55f);
                SpawnShapes.addCurtain(overlay.areaFills, points, lift, curtain, style.argb, fill);
                var edge = style.getImguiColor(Math.min(1f, alpha + .1f), 1.25f);
                addOutline(overlay, points, item.corners(), step, lift + curtain, edge, focused ? 1.6f : 1.2f);
            }
            if (focused && !quiet) {
                if (showVolume || index == editing) {
                    drawVolume(overlay, area, style, item.groundZ(), chosen);
                }
                drawBanned(overlay, area, eyeZ, lift);
                drawPlaces(overlay, area.teleportPoints(), style.argb);
                drawPlaces(overlay, area.restartPoints(), RESTART_ARGB);
            }
        }
    }

    private void drawVolume(
            SpawnOverlayState overlay, ServerArea area, AreaStyle style, float groundZ, boolean chosen) {
        var bottom = (float) Math.max(area.bottom(), groundZ - VOLUME_REACH);
        var top = (float) Math.min(area.top(), groundZ + VOLUME_REACH);
        var color = style.getImguiColor(chosen ? .8f : .55f, 1.1f);
        var width = chosen ? 1.5f : 1.2f;
        var corners = area.points();
        for (var i = 0; i < corners.size(); i++) {
            var a = corners.get(i);
            var b = corners.get((i + 1) % corners.size());
            overlay.areaLines.addLine(
                    new GeoPoint(a.x(), a.y(), bottom), new GeoPoint(b.x(), b.y(), bottom), 0, color, width);
            overlay.areaLines.addLine(
                    new GeoPoint(a.x(), a.y(), top), new GeoPoint(b.x(), b.y(), top), 0, color, width);
            overlay.areaLines.addLine(
                    new GeoPoint(a.x(), a.y(), bottom), new GeoPoint(a.x(), a.y(), top), 0, color, width);
        }
    }

    private void drawBanned(SpawnOverlayState overlay, ServerArea area, float eyeZ, float lift) {
        var banned = area.banned();
        for (var part = 0; part < banned.size(); part++) {
            var outline = findPartOutline(area, part, eyeZ);
            if (outline == null) {
                continue;
            }
            addOutline(overlay, outline.points(), outline.corners(), 1, lift + 1, GeodataWidgets.BLOCKED, 2.2f);
            SpawnShapes.addCurtain(overlay.areaFills, outline.points(), lift + 1, CURTAIN, 0xFFFF6256, .22f);
        }
    }

    private @Nullable Drape findPartOutline(ServerArea area, int part, float eyeZ) {
        var polygon = area.banned().get(part);
        if (polygon.size() < 3) {
            return null;
        }
        var territory = new SpawnTerritory(
                -1,
                "area " + area.index() + " excluded " + part,
                false,
                0,
                polygon,
                area.line(),
                SourceSpan.NONE,
                SourceSpan.NONE,
                SourceSpan.NONE);
        var drape = !ground.isAvailable()
                ? null
                : area.index() == editing ? ground.createDrapeNow(territory) : ground.getDrape(territory);
        return drape != null
                ? drape
                : SpawnWorkspace.SpawnGroundFallback.flat(
                        territory, Math.clamp(eyeZ - 160, territory.bottom(), territory.top()));
    }

    private void drawTags(ImDrawList draw, float eyeX, float eyeY, float eyeZ) {
        var ordered = new ArrayList<Drawn>(drawn);
        ordered.sort(Comparator.comparingInt((Drawn item) ->
                        item.index() == hovered ? 0 : item.index() == selected ? 1 : item.index() == panelHover ? 2 : 3)
                .thenComparingDouble(item -> tagDistance(item, eyeX, eyeY, eyeZ)));
        var placed = new ArrayList<float[]>(hintAreas);
        var shown = 0;
        var height = ImGui.getFontSize() + 6;
        for (var item : ordered) {
            if (shown >= MAX_TAGS) {
                break;
            }
            var area = item.area();
            var focused = isFocused(item.index());
            var away = tagDistance(item, eyeX, eyeY, eyeZ);
            if (!focused && (away > TAG_DISTANCE || item.water())) {
                continue;
            }
            if (!projector.project(area.centerX(), area.centerY(), item.groundZ() + CURTAIN, screen)) {
                continue;
            }
            var view = viewport;
            if (view != null
                    && (screen[0] < view.left()
                            || screen[0] > view.right()
                            || screen[1] < view.top()
                            || screen[1] > view.bottom())) {
                continue;
            }
            var word = AreaStyle.tagWord(area);
            var name = area.name().isEmpty() ? "no name" : area.name();
            var off = area.isOnByDefault() ? "" : "off";
            var width = ImGui.calcTextSizeX(word)
                    + 8
                    + ImGui.calcTextSizeX(name)
                    + (off.isEmpty() ? 0 : 8 + ImGui.calcTextSizeX(off))
                    + 14;
            float[] box = null;
            for (var row = 0; row < TAG_STACK && box == null; row++) {
                var top = screen[1] - height + row * (height + 3);
                var candidate = new float[] {screen[0] - width * .5f, top, screen[0] + width * .5f, top + height};
                if (!overlapsAny(candidate, placed)) {
                    box = candidate;
                }
            }
            if (box == null) {
                continue;
            }
            placed.add(box);
            shown++;
            var fade = focused ? 1f : (float) Math.clamp(1.3 - away / TAG_DISTANCE, .6, .95);
            drawTag(draw, box, item.style(), word, name, off, fade, item.index() == selected);
        }
    }

    private void drawHoverCard(AreaDocument document, State state) {
        var area = state.area(hovered);
        if (area == null) {
            return;
        }
        var details = new ArrayList<String>();
        details.add(AreaStyle.title(area) + " · " + tileOf(area) + (area.isOnByDefault() ? "" : " · off"));
        details.add(AreaSummary.describe(area, document.getFile().getDefaultValues()));
        if (!area.note().isBlank()) {
            details.add("Note: " + area.note());
        }
        var point = aimed;
        if (point != null
                && (AreaTiles.tileX(point.x()) != area.centerTileX()
                        || AreaTiles.tileY(point.y()) != area.centerTileY())) {
            details.add("The server files it under " + tileOf(area) + " only, so it does nothing here.");
        }
        if (stack.size() > 1) {
            var others = new ArrayList<String>();
            for (var index : stack) {
                var other = state.area(index);
                if (index != hovered && other != null && others.size() < 3) {
                    others.add(AreaStyle.title(other) + " " + other.name());
                }
            }
            details.add("Also here: " + String.join(", ", others) + (stack.size() - 1 > others.size() ? " …" : ""));
        }
        var actions = stack.size() > 1
                ? "Click to select · click again for the next of " + stack.size() + " here"
                : hovered == selected ? "Selected · Esc deselects" : "Click to select";
        HudWidgets.showHoverCard(
                ("area/" + hovered).hashCode(), area.name().isEmpty() ? "No name" : area.name(), details, actions);
    }

    private List<String[]> hintItems() {
        var items = new ArrayList<String[]>();
        if (drawingType != null || drawingPart >= 0) {
            items.add(new String[] {"Click", "add a corner"});
            items.add(new String[] {"Backspace", "take the last back"});
            items.add(new String[] {"Enter", "close it"});
            items.add(new String[] {"Shift", "off the 16-unit grid"});
            items.add(new String[] {"Esc", "cancel"});
        } else if (placing != null) {
            items.add(new String[] {"Click", "place the point"});
            items.add(new String[] {"Esc", "stop"});
        } else if (drag != null) {
            items.add(new String[] {"Esc", "take the drag back"});
        } else if (editing >= 0) {
            items.add(new String[] {"Drag a corner", "move it"});
            items.add(new String[] {"Click an edge", "add a corner"});
            items.add(new String[] {"Del", "remove a corner"});
            items.add(new String[] {"Drag inside", "move it"});
            items.add(new String[] {"Blue dots", "bottom, top"});
            items.add(new String[] {"Esc", "stop"});
            items.add(new String[] {"F1", "all keys"});
            return items;
        } else if (selected >= 0) {
            items.add(new String[] {"Double-click", "edit the shape"});
            items.add(new String[] {"Click again", "next area there"});
            items.add(new String[] {"F", "focus"});
            items.add(new String[] {"Esc", "deselect"});
        } else {
            items.add(new String[] {"Point", "see what it does"});
            items.add(new String[] {"Click", "select"});
            items.add(new String[] {"Click again", "next area there"});
        }
        items.add(new String[] {"L", "show or hide"});
        items.add(new String[] {"F1", "all keys"});
        return items;
    }

    private void drawHeader() {
        var spacing = ImGui.getStyle().getItemSpacingX();
        var width = (ImGui.getContentRegionAvailX() - spacing) / 2;
        if (GeodataWidgets.chip("area-show", Glyph.EYE, "Show", "L", visible, width)) {
            visible = !visible;
        }
        GeodataWidgets.tip("Show the server's areas in the world.", "L");
        ImGui.sameLine();
        if (GeodataWidgets.chip("area-view", Glyph.SLIDERS, "View", ImGui.isPopupOpen("area-view"), width)) {
            ImGui.openPopup("area-view");
        }
        GeodataWidgets.tip("Draw distance, names, the height box, NPC spawn zones and which areas show.");
        drawViewPopover(ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY() + 4);
    }

    private void drawViewPopover(float right, float top) {
        ImGui.setNextWindowPos(right, top, ImGuiCond.Always, 1, 0);
        ImGui.setNextWindowSize(268, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 12, 10);
        if (ImGui.beginPopup("area-view")) {
            GeodataFields.slider(
                    "Draw distance",
                    distance,
                    2000,
                    20000,
                    "Areas farther than this from the camera are not drawn. From high up they show farther.");
            space(ROW);
            showTags = HudWidgets.switchRow(
                    "Area names", showTags, true, "A tag with the type and the name over the nearest areas.");
            showVolume = HudWidgets.switchRow(
                    "Height box of the selection",
                    showVolume,
                    true,
                    "The selected or pointed area's outline at its bottom and its top, with a line up each corner,"
                            + " so you see which floor it works on.");
            var spawns = spawnOverlay;
            var spawnsOn = spawns != null && spawns.isOverlayOn();
            var nextSpawns = HudWidgets.switchRow(
                    "Spawn zones and NPCs",
                    spawnsOn,
                    spawns != null,
                    "The zones and NPCs of NPC Spawns, as N shows and hides them. While this panel is open they are"
                            + " a quiet reference: thin outlines that take no clicks.");
            if (spawns != null && nextSpawns != spawnsOn) {
                spawns.setOverlayOn(nextSpawns);
            }
            space(ROW);
            ImGui.textColored(GeodataWidgets.MUTED, "Show");
            showInstanced = HudWidgets.switchRow(
                    "In instance zones",
                    showInstanced,
                    true,
                    "Areas with instantzone_id. They work only inside a copy of an instance zone.");
            showOff = HudWidgets.switchRow(
                    "Off by default",
                    showOff,
                    true,
                    "Areas with default_status=off. They do nothing until an event or an AI turns them on, and they"
                            + " are drawn fainter.");
            space(ROW);
            ImGui.textColored(GeodataWidgets.MUTED, "Colors");
            for (var style : AreaStyle.values()) {
                dotText(style.getImguiColor(1f), style.label);
            }
            ImGui.endPopup();
        }
        ImGui.popStyleVar();
    }

    private void drawSourceRow() {
        space(BLOCK);
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var height = 44f;
        var draw = ImGui.getWindowDrawList();
        var clicked = ImGui.invisibleButton("area-source", width - 30, height);
        var hovering = ImGui.isItemHovered();
        draw.addRectFilled(x, y, x + width, y + height, hovering ? GeodataWidgets.HOVER : GeodataWidgets.CARD, 6f);
        HudWidgets.glyph(draw, Glyph.FOLDER, x + 9, y + 7, 16, GeodataWidgets.MUTED);
        var folder = source.getFolder();
        var name = folder == null
                ? "No script folder"
                : folder.getFileName() == null
                        ? folder.toString()
                        : folder.getFileName().toString();
        draw.pushClipRect(x, y, x + width - 34, y + height, true);
        draw.addText(x + 32, y + 6, GeodataWidgets.TEXT, name);
        var file = source.getFile();
        var status = switch (source.getPhase()) {
            case EMPTY -> "Choose the server script folder";
            case LOADING -> "Reading…";
            case MISSING -> "No " + AreaDataFile.FILE_NAME;
            case FAILED -> "Cannot read " + AreaDataFile.FILE_NAME;
            case READY ->
                file == null || file.getFileName() == null
                        ? AreaDataFile.FILE_NAME
                        : file.getFileName().toString();
        };
        var statusColor = switch (source.getPhase()) {
            case MISSING -> GeodataWidgets.WARNING;
            case FAILED -> GeodataWidgets.BLOCKED;
            default -> GeodataWidgets.MUTED;
        };
        draw.addText(x + 32, y + 24, statusColor, status);
        draw.popClipRect();
        if (hovering) {
            HudWidgets.showTooltip((folder == null ? "" : folder + "\n\n")
                    + "NPC Spawns and Areas share this folder. Click to choose another.");
        }
        if (clicked) {
            folders.open(folder);
        }
        ImGui.setCursorScreenPos(x + width - 28, y + (height - 26) * .5f);
        ImGui.beginDisabled(folder == null || source.isSaving());
        if (HudWidgets.button("area-reload", Glyph.RELOAD, "", 26, 26, false, false)) {
            if (source.isModified()) {
                pendingLeave = Leave.SOURCE;
                pendingReload = true;
                pendingFolder = null;
            } else {
                source.reload();
            }
        }
        ImGui.endDisabled();
        GeodataWidgets.tip("Read areadata.txt again after it changes on disk.");
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        if (source.isMessageError() && !source.getMessage().isBlank()) {
            space(ROW);
            GeodataWidgets.note(source.getMessage(), GeodataWidgets.BLOCKED);
        }
    }

    private void drawCountLine(State state) {
        refreshComposition(state);
        var all = 0;
        for (var area : state.areas()) {
            if (area != null) {
                all++;
            }
        }
        space(ROW);
        var current = tiles;
        var text = String.format(Locale.ROOT, "%,d %s", all, all == 1 ? "area" : "areas")
                + (current == null || current.isEmpty()
                        ? ""
                        : String.format(Locale.ROOT, " · %,d on loaded tiles", loadedCount));
        GeodataWidgets.note(text, GeodataWidgets.FAINT);
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/areadata");
    }

    private void drawEmptySource() {
        ImGui.dummy(0, 16);
        var draw = ImGui.getWindowDrawList();
        var width = ImGui.getContentRegionAvailX();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        HudWidgets.glyph(draw, Glyph.AREA, x + width * .5f - 18, y, 36, GeodataWidgets.ACCENT);
        ImGui.dummy(width, 44);
        centeredText("Server areas");
        GeodataWidgets.hint("Choose the L2OFF script folder with areadata.txt. Peace zones, battle zones, water and"
                + " the server's other areas appear in the world; point at one to see what it does.");
        space(BLOCK);
        if (GeodataWidgets.actionButton("area-browse", Glyph.FOLDER, "Choose folder…", -1, true)) {
            folders.open(null);
        }
        GeodataWidgets.tip("NPC Spawns opens the same folder: the two tools share it.");
        drawNewcomerLink();
    }

    private void drawMissingFile() {
        space(BLOCK);
        var folder = source.getFolder();
        var name = folder == null || folder.getFileName() == null
                ? ""
                : folder.getFileName().toString();
        if (GeodataWidgets.callout(
                "area-missing", Glyph.FILE, "No " + AreaDataFile.FILE_NAME, "Not in " + name, "Choose…")) {
            folders.open(folder);
        }
        GeodataWidgets.tip("Areas reads areadata.txt from the script folder that NPC Spawns has open. Choose a"
                + " folder that has it, and NPC Spawns opens it too.");
        drawNewcomerLink();
    }

    private void drawNewcomerLink() {
        GeodataWidgets.section("NEW TO AREAS?");
        space(ROW);
        HudWidgets.guideLink("What a server area is", "areas/what-is-an-area");
    }

    private void drawSearchBox(State state) {
        space(BLOCK);
        var hasText = !search.get().isEmpty();
        ImGui.setNextItemWidth(hasText ? ImGui.getContentRegionAvailX() - 30 : -1);
        ImGui.inputTextWithHint("##area-search", "Find an area, a type, a note or a skill", search);
        var typing = ImGui.isItemActive();
        var left = ImGui.isItemDeactivated();
        var entered = left && (ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter));
        if (hasText) {
            ImGui.sameLine(0, 4);
            if (HudWidgets.button("area-search-clear", Glyph.CLOSE, "", 26, ImGui.getFrameHeight(), false, false)) {
                clearSearch();
            }
            GeodataWidgets.tip("Clear the search.", "Esc");
        }
        var query = search.get().strip().toLowerCase(Locale.ROOT);
        if (!query.equals(searched) || state != listedState && !query.isEmpty()) {
            if (!query.equals(searched)) {
                searchCursor = 0;
                scrolledCursor = 0;
            }
            searched = query;
            listedState = state;
            results = query.isEmpty() ? List.of() : find(state, query);
        }
        if (typing && !results.isEmpty()) {
            if (ImGui.isKeyPressed(ImGuiKey.DownArrow)) {
                searchCursor = Math.min(Math.min(results.size(), LISTED_RESULTS) - 1, searchCursor + 1);
            }
            if (ImGui.isKeyPressed(ImGuiKey.UpArrow)) {
                searchCursor = Math.max(0, searchCursor - 1);
            }
        }
        if ((typing || left) && ImGui.isKeyPressed(ImGuiKey.Escape)) {
            clearSearch();
        }
        if (entered && !results.isEmpty() && camera != null) {
            pickResult(results.get(Math.clamp(searchCursor, 0, results.size() - 1)), camera);
        }
    }

    private void clearSearch() {
        search.set("");
        searched = "";
        results = List.of();
        searchCursor = 0;
    }

    private void pickResult(Result result, Camera eye) {
        select(result.index());
        clearSearch();
        focus(eye, result.index());
    }

    private List<Result> find(State state, String query) {
        var found = new ArrayList<Result>();
        var hidden = 0;
        var eye = camera == null ? null : camera.getPosition();
        for (var area : state.areas()) {
            if (area == null || !matches(area, query)) {
                continue;
            }
            if (!isInLoadedTiles(area.minX(), area.minY(), area.maxX(), area.maxY())) {
                hidden++;
                continue;
            }
            var away = eye == null ? Double.MAX_VALUE : Polygons.distanceXy(area.points(), eye.getX(), eye.getY());
            var where = area.name().toLowerCase(Locale.ROOT).contains(query) ? "" : findMatch(area, query);
            found.add(new Result(
                    area.index(), area.name(), describeBriefly(area) + (where.isEmpty() ? "" : " · " + where), away));
        }
        found.sort(Comparator.comparing((Result result) -> !result.title().equalsIgnoreCase(query))
                .thenComparingDouble(Result::distance));
        hiddenMatches = hidden;
        return found;
    }

    private void drawSearchResults(State state, Camera eye) {
        if (results.isEmpty()) {
            var where = hiddenMatches > 0 ? " on the loaded tiles" : "";
            GeodataWidgets.note("Nothing matches “" + searched + "”" + where + ".", GeodataWidgets.MUTED);
            GeodataWidgets.note(
                    hiddenMatches > 0 ? notLoadedMatches() : "Search looks at names, types, notes and skills.",
                    GeodataWidgets.FAINT);
            return;
        }
        var count = results.size() == 1 ? "1 match" : String.format(Locale.ROOT, "%,d matches", results.size());
        GeodataWidgets.note(count + ", nearest first · ↑↓ and Enter", GeodataWidgets.FAINT);
        space(ROW);
        var shown = Math.min(results.size(), LISTED_RESULTS);
        Result clicked = null;
        for (var i = 0; i < shown; i++) {
            var result = results.get(i);
            var area = state.area(result.index());
            if (area == null) {
                continue;
            }
            ImGui.pushID(i);
            var x = ImGui.getCursorScreenPosX();
            var y = ImGui.getCursorScreenPosY();
            if (ImGui.selectable("##result", i == searchCursor, 0, 0, SpawnPanelWidgets.listRowHeight())) {
                clicked = result;
            }
            if (ImGui.isItemHovered() || i == searchCursor) {
                panelHover = result.index();
            }
            if (i == searchCursor && scrolledCursor != searchCursor) {
                var windowTop = ImGui.getWindowPosY();
                if (ImGui.getItemRectMinY() < windowTop) {
                    ImGui.setScrollHereY(0);
                } else if (ImGui.getItemRectMaxY() > windowTop + ImGui.getWindowHeight()) {
                    ImGui.setScrollHereY(1);
                }
                scrolledCursor = searchCursor;
            }
            SpawnPanelWidgets.listRow(
                    ImGui.getWindowDrawList(),
                    x,
                    y,
                    ImGui.getItemRectMaxX() - x,
                    AreaStyle.of(area).argb,
                    result.title().isEmpty() ? "No name" : result.title(),
                    result.detail(),
                    away(result.distance()),
                    searched);
            ImGui.popID();
        }
        if (clicked != null) {
            pickResult(clicked, eye);
            return;
        }
        if (results.size() > shown) {
            space(ROW);
            GeodataWidgets.note(
                    String.format(Locale.ROOT, "%,d more. Narrow the search.", results.size() - shown),
                    GeodataWidgets.FAINT);
        }
        if (hiddenMatches > 0) {
            space(ROW);
            GeodataWidgets.note(notLoadedMatches(), GeodataWidgets.FAINT);
        }
    }

    private String notLoadedMatches() {
        return String.format(
                Locale.ROOT,
                "%,d %s on tiles that are not loaded.",
                hiddenMatches,
                hiddenMatches == 1 ? "match lies" : "matches lie");
    }

    private void drawOverview(AreaDocument document, State state, Camera eye) {
        ImGui.textColored(GeodataWidgets.MUTED, "IN THE LOADED WORLD");
        space(ROW);
        refreshComposition(state);
        if (composition.isEmpty()) {
            GeodataWidgets.note("No area lies on the loaded tiles.", GeodataWidgets.FAINT);
        } else {
            var style = SpawnPanelWidgets.compositionOf(
                    "area-kinds", composition, hiddenStyles, "in the world and in Near the camera");
            if (style != null && !hiddenStyles.remove(style)) {
                hiddenStyles.add(style);
            }
        }
        var here = drawAtCamera(document, state, eye);
        drawNearby(state, eye, here);
        space(SECTION);
        if (GeodataWidgets.actionButton("area-paste", Glyph.COPY, "Paste area here", -1, false)) {
            pasteAreas(document, ImGui.getClipboardText());
        }
        GeodataWidgets.tip("Adds areas copied as " + AreaDataFile.FILE_NAME + " text in the middle of the view, with"
                + " free names: Copy as text on an area, or records copied from a file.");
        drawCheck(document, state, eye);
        var problems = document.getFile().getProblems();
        if (!problems.isEmpty()) {
            space(SECTION);
            if (GeodataWidgets.header("Problems while reading (" + problems.size() + ")")) {
                for (var problem : problems.subList(0, Math.min(problems.size(), 200))) {
                    GeodataWidgets.note("Line " + problem.line() + ": " + problem.message(), GeodataWidgets.WARNING);
                }
            }
        }
    }

    private void refreshComposition(State state) {
        var current = tiles;
        var scope = current == null ? -1 : current.getRevision();
        if (state == compositionState && scope == compositionScope) {
            return;
        }
        var counts = new EnumMap<AreaStyle, Long>(AreaStyle.class);
        var loaded = 0;
        for (var area : state.areas()) {
            if (area != null && isInLoadedTiles(area.minX(), area.minY(), area.maxX(), area.maxY())) {
                counts.merge(AreaStyle.of(area), 1L, Long::sum);
                loaded++;
            }
        }
        composition = counts.entrySet().stream()
                .sorted(Map.Entry.<AreaStyle, Long>comparingByValue().reversed())
                .map(entry -> new Slice<>(
                        entry.getKey(),
                        entry.getKey().label,
                        phrase(entry.getKey()),
                        entry.getKey().argb,
                        entry.getValue()))
                .toList();
        loadedCount = loaded;
        compositionState = state;
        compositionScope = scope;
    }

    private List<ServerArea> drawAtCamera(AreaDocument document, State state, Camera eye) {
        GeodataWidgets.section("AT THE CAMERA");
        space(ROW);
        var position = eye.getPosition();
        var tileX = AreaTiles.tileX(position.getX());
        var tileY = AreaTiles.tileY(position.getY());
        var holding = new ArrayList<ServerArea>();
        var elsewhere = 0;
        for (var area : state.areas()) {
            if (area == null || !area.contains(position.getX(), position.getY(), position.getZ())) {
                continue;
            }
            if (area.centerTileX() == tileX && area.centerTileY() == tileY) {
                holding.add(area);
            } else {
                elsewhere++;
            }
        }
        holding.sort(Comparator.comparingDouble(ServerArea::area));
        if (holding.isEmpty()) {
            GeodataWidgets.note("No area here.", GeodataWidgets.FAINT);
        }
        var defaults = document.getFile().getDefaultValues();
        for (var area : holding) {
            if (sentenceRow("at" + area.index(), area, AreaSummary.describe(area, defaults))) {
                select(area.index());
            }
        }
        if (elsewhere > 0) {
            space(ROW);
            GeodataWidgets.note(
                    elsewhere == 1
                            ? "1 more reaches here from another tile, and does nothing here."
                            : elsewhere + " more reach here from other tiles, and do nothing here.",
                    GeodataWidgets.FAINT);
            GeodataWidgets.tip("The server files an area under the one tile of its middle, and looks only at the"
                    + " areas of the tile where a character stands.");
        }
        return holding;
    }

    private boolean sentenceRow(String id, ServerArea area, String sentence) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var pad = SpawnPanelWidgets.ROW_PAD;
        var textLeft = SpawnPanelWidgets.listRowTextLeft();
        var room = width - textLeft - pad;
        var line = ImGui.getFontSize();
        var body = ImGui.calcTextSizeY(sentence, false, room);
        var height = 4 + line + 2 + body + 6;
        ImGui.pushID(id);
        var clicked = ImGui.selectable("##row", false, 0, width, height);
        var hovered = ImGui.isItemHovered();
        ImGui.popID();
        var draw = ImGui.getWindowDrawList();
        var style = AreaStyle.of(area);
        draw.addCircleFilled(x + pad + 4, HudWidgets.textMiddle(y + 4), 4.5f, style.getImguiColor(1f), 14);
        var title = AreaStyle.title(area);
        draw.addText(x + textLeft, y + 4, HudWidgets.TEXT, title);
        var nameLeft = x + textLeft + ImGui.calcTextSizeX(title) + 8;
        draw.addText(
                nameLeft,
                y + 4,
                GeodataWidgets.FAINT,
                HudWidgets.truncateToWidth(area.name(), x + width - pad - nameLeft));
        ImGui.setCursorScreenPos(x + textLeft, y + 4 + line + 2);
        ImGui.pushTextWrapPos(ImGui.getCursorPosX() + room);
        ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.MUTED);
        ImGui.textUnformatted(sentence);
        ImGui.popStyleColor();
        ImGui.popTextWrapPos();
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(0, 0);
        if (hovered) {
            panelHover = area.index();
        }
        return clicked;
    }

    private void drawNearby(State state, Camera eye, List<ServerArea> here) {
        GeodataWidgets.section("NEAR THE CAMERA");
        space(ROW);
        var position = eye.getPosition();
        var nearest = new ArrayList<ServerArea>();
        var distances = new IdentityHashMap<ServerArea, Double>();
        for (var area : state.areas()) {
            if (area == null
                    || here.contains(area)
                    || !isShown(area)
                    || area.points().size() < 3
                    || !isInLoadedTiles(area.minX(), area.minY(), area.maxX(), area.maxY())) {
                continue;
            }
            if (nearest.size() >= NEARBY_ROWS
                    && boundsDistance(area, position.getX(), position.getY()) > distances.get(nearest.getLast())) {
                continue;
            }
            var away = Polygons.distanceXy(area.points(), position.getX(), position.getY());
            distances.put(area, away);
            nearest.add(area);
            nearest.sort(Comparator.comparingDouble(distances::get));
            if (nearest.size() > NEARBY_ROWS) {
                nearest.removeLast();
            }
        }
        if (nearest.isEmpty()) {
            GeodataWidgets.note("No area the legend shows lies on the loaded tiles.", GeodataWidgets.FAINT);
            return;
        }
        for (var area : nearest) {
            ImGui.pushID("near" + area.index());
            var x = ImGui.getCursorScreenPosX();
            var y = ImGui.getCursorScreenPosY();
            if (ImGui.selectable("##near", false, 0, 0, SpawnPanelWidgets.listRowHeight())) {
                select(area.index());
                focus(eye, area.index());
            }
            if (ImGui.isItemHovered()) {
                panelHover = area.index();
            }
            var away = distances.get(area);
            SpawnPanelWidgets.listRow(
                    ImGui.getWindowDrawList(),
                    x,
                    y,
                    ImGui.getItemRectMaxX() - x,
                    AreaStyle.of(area).argb,
                    area.name().isEmpty() ? "No name" : area.name(),
                    describeBriefly(area),
                    away == 0 ? "here" : away(away),
                    "");
            ImGui.popID();
        }
    }

    private void drawCheck(AreaDocument document, State state, Camera eye) {
        if (state != checkedState || document.getSavedTypes() != checkedTypes) {
            findings = AreaChecks.findAll(state, document.getSavedTypes());
            checkedState = state;
            checkedTypes = document.getSavedTypes();
            if (findingSelected != null && !findings.contains(findingSelected)) {
                findingSelected = null;
            }
        }
        GeodataWidgets.section("CHECK");
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("areas/check-areas");
        space(ROW);
        if (findings.isEmpty()) {
            GeodataWidgets.dot(GeodataWidgets.OPEN);
            GeodataWidgets.note("No problems in " + AreaDataFile.FILE_NAME + ".", GeodataWidgets.OPEN);
            return;
        }
        var counts = new EnumMap<Kind, Integer>(Kind.class);
        for (var finding : findings) {
            counts.merge(finding.kind(), 1, Integer::sum);
        }
        var groups = new ArrayList<Group>();
        counts.forEach((kind, count) ->
                groups.add(new Group(kind.name(), kind.getTitle(), severity(kind), count, helpText(kind))));
        findingList.groupCheckboxes("area-check", groups);
        Kind fixAll = null;
        for (var entry : counts.entrySet()) {
            var kind = entry.getKey();
            if (!kind.hasFix()) {
                continue;
            }
            space(ROW);
            var label = "Fix all " + entry.getValue() + " · " + kind.getFixLabel();
            if (GeodataWidgets.actionButton("area-fix-all/" + kind, null, label, -1, false)) {
                fixAll = kind;
            }
            GeodataWidgets.tip(kind.getTitle() + ": " + kind.getFixLabel() + " for each of them, as one step that Undo"
                    + " takes back.");
        }
        if (fixAll != null) {
            var kind = fixAll;
            var types = document.getSavedTypes();
            document.apply("Fix all: " + kind.getFixLabel(), next -> AreaChecks.fixAll(next, kind, types));
            merge.reset();
            return;
        }
        var shown = findings.stream()
                .filter(finding -> findingList.isShown(finding.kind().name()))
                .toList();
        space(ROW);
        var page = findingList.drawPager("area-check", shown.size());
        for (var i = page[0]; i < page[1]; i++) {
            var finding = shown.get(i);
            space(ROW);
            ImGui.pushID("finding" + i);
            ImGui.beginGroup();
            var fix = finding.kind().getFixLabel();
            var pressed = FindingWidgets.card(
                    "area-finding",
                    severity(finding.kind()),
                    finding.title(),
                    (finding.name().isEmpty() ? "No name" : finding.name()) + " · " + finding.detail(),
                    fix == null ? List.of() : List.of(fix),
                    finding.equals(findingSelected));
            ImGui.endGroup();
            ImGui.popID();
            if (ImGui.isItemHovered()) {
                panelHover = finding.area();
            }
            if (pressed == FindingWidgets.FLY && state.area(finding.area()) != null) {
                findingSelected = finding;
                select(finding.area());
                focus(eye, finding.area());
            } else if (pressed == 0 && fix != null) {
                document.apply(fix, next -> AreaChecks.fix(next, finding));
                merge.reset();
                return;
            }
        }
    }

    private void drawAreaSection(AreaDocument document, State state, Camera eye) {
        var area = state.area(selected);
        if (area == null) {
            return;
        }
        if (markedField != null && area.hasSetting(markedField)) {
            markedField = null;
        }
        var style = AreaStyle.of(area);
        if (!drawAreaCard(document, area, style, eye)) {
            return;
        }
        space(ROW);
        GeodataWidgets.note(AreaSummary.describe(area, document.getFile().getDefaultValues()), HudWidgets.TEXT);
        drawOwnFindings(document, state, area);
        fields.drawMarked(document, area);
        if (!isInLoadedTiles(area.minX(), area.minY(), area.maxX(), area.maxY())) {
            space(ROW);
            GeodataWidgets.note(
                    "Tile " + tileOf(area) + " is not loaded in this session, so the area is not drawn. Load it from"
                            + " the main menu to see it.",
                    GeodataWidgets.WARNING);
        }
        space(BLOCK);
        drawAreaMap(area, style, eye);
        GeodataWidgets.section("SETTINGS");
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("areas/area-settings");
        fields.drawSettings(document, area);
        GeodataWidgets.section("SHAPE");
        space(ROW);
        var reached = otherTiles(area);
        fact(
                "Tile",
                tileOf(area) + (reached.isEmpty() ? "" : " · reaches " + reached),
                "The server files the area under the tile of its middle and looks only there, so a part on another"
                        + " tile does nothing. map_no follows the middle by itself.");
        fact(
                "Size",
                formatNumber(area.maxX() - area.minX()) + " × " + formatNumber(area.maxY() - area.minY()),
                "Across the X and Y axes, in world units.");
        space(ROW);
        fields.drawShape(area);
        GeodataWidgets.section("IN THE FILE");
        space(ROW);
        GeodataWidgets.commandField(
                "area-record",
                area.line() < 0 ? "Not in the file yet" : "Line " + area.line(),
                recordText(document, area),
                mono);
    }

    private void drawOwnFindings(AreaDocument document, State state, ServerArea area) {
        var own = new ArrayList<>(AreaChecks.check(state, area.index(), document.getSavedTypes()));
        own.sort(Comparator.comparing((Finding finding) -> finding.kind() != Kind.OTHER_TILE)
                .thenComparing(finding -> !finding.isError()));
        Finding fixed = null;
        for (var finding : own) {
            space(ROW);
            ImGui.pushID("own" + finding.kind());
            var fix = finding.kind().getFixLabel();
            var pressed = FindingWidgets.card(
                    "area-own",
                    severity(finding.kind()),
                    finding.title(),
                    finding.detail(),
                    fix == null ? List.of() : List.of(fix),
                    false,
                    false);
            ImGui.popID();
            if (pressed == 0) {
                fixed = finding;
            }
        }
        if (fixed != null) {
            var finding = fixed;
            document.apply(Objects.requireNonNull(finding.kind().getFixLabel()), next -> AreaChecks.fix(next, finding));
            merge.reset();
        }
    }

    private boolean drawAreaCard(AreaDocument document, ServerArea area, AreaStyle style, Camera eye) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var titleHeight = ImGui.getFontSize() * 2 + 22;
        var height = titleHeight + HudWidgets.CONTROL_HEIGHT + 10;
        draw.addRectFilled(x, y, x + width, y + height, GeodataWidgets.CARD, 8f);
        draw.addRectFilled(x, y, x + 4, y + height, style.getImguiColor(1f), 8f, ImDrawFlags.RoundCornersLeft);
        var room = width - 14 - 96;
        var detail = AreaStyle.title(area)
                + " · "
                + tileOf(area)
                + " · "
                + (area.line() < 0 ? "new" : "line " + area.line())
                + (area.isOnByDefault() ? "" : " · off");
        draw.addText(
                x + 14,
                y + 9,
                HudWidgets.TEXT,
                HudWidgets.truncateToWidth(area.name().isEmpty() ? "No name" : area.name(), room));
        var detailTop = y + ImGui.getFontSize() + 13;
        draw.addText(x + 14, detailTop, GeodataWidgets.MUTED, HudWidgets.truncateToWidth(detail, room));
        if (ImGui.isWindowHovered()
                && ImGui.isMouseHoveringRect(x + 14, detailTop, x + 14 + room, detailTop + ImGui.getFontSize())) {
            HudWidgets.showTooltip("In the file: type=" + area.typeWord() + ", map_no "
                    + (area.hasMap() ? AreaTiles.format(area.mapX(), area.mapY()) : "not written")
                    + ". The server files the area under the tile of its middle, " + tileOf(area) + ".");
        }
        ImGui.setCursorScreenPos(x + width - 92, y + (titleHeight - 26) * .5f);
        var editingThis = editing == area.index();
        if (HudWidgets.button("area-edit", Glyph.PENCIL, "", 26, 26, editingThis, false)) {
            editing = editingThis ? -1 : area.index();
        }
        GeodataWidgets.tip(
                editingThis
                        ? "Stop editing: the shape can no longer be dragged in the world."
                        : "Edit the shape in the world: drag its corners, its edges, the area and its height box.",
                editingThis ? "Esc" : "Double-click the area");
        ImGui.sameLine(0, 4);
        if (HudWidgets.button("area-focus", Glyph.FOCUS, "", 26, 26, false, false)) {
            focus(eye, area.index());
        }
        GeodataWidgets.tip("Move the camera to this area.", "F");
        ImGui.sameLine(0, 4);
        if (HudWidgets.button("area-deselect", Glyph.CLOSE, "", 26, 26, false, false)) {
            select(-1);
            return false;
        }
        GeodataWidgets.tip("Back to the overview.", "Esc");
        var rowTop = y + titleHeight;
        ImGui.setCursorScreenPos(x + 10, rowTop);
        var duplicate = HudWidgets.iconButton("area-duplicate", Glyph.COPY, false, HudWidgets.TEXT);
        GeodataWidgets.tip("Duplicate here: a copy of the area in the middle of the view, on the ground there, with a"
                + " free name. Its settings stay.");
        ImGui.sameLine(0, 4);
        if (HudWidgets.iconButton("area-copy-text", Glyph.FILE, false, HudWidgets.TEXT)) {
            ImGui.setClipboardText(AreaCopies.copyAsText(document.getFile(), area));
            setMessage(
                    "Copied " + area.name() + " as " + AreaDataFile.FILE_NAME
                            + " text. Paste area here adds it elsewhere, or paste it into another file.",
                    GeodataWidgets.MUTED);
        }
        GeodataWidgets.tip("Copy as text: the area in " + AreaDataFile.FILE_NAME + " syntax, with its note, for Paste"
                + " area here or another file.");
        ImGui.sameLine(0, 4);
        var delete = HudWidgets.iconButton("area-delete", Glyph.TRASH, false, GeodataWidgets.BLOCKED);
        GeodataWidgets.tip("Delete: take the area out of the file. Undo brings it back.");
        ImGui.setCursorScreenPos(x + width - 10 - HudWidgets.CONTROL_HEIGHT, rowTop);
        if (HudWidgets.iconButton("area-more", Glyph.MORE, ImGui.isPopupOpen("area-more-menu"), HudWidgets.TEXT)) {
            ImGui.openPopup("area-more-menu");
        }
        GeodataWidgets.tip("More: paste areas copied as text, and split the area at tile borders.");
        drawMoreMenu(document, area);
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        if (duplicate) {
            duplicateHere(document, area);
        }
        if (delete) {
            var name = area.name();
            var index = area.index();
            document.apply("Delete area", state -> AreaEdits.delete(state, index));
            merge.reset();
            select(-1);
            setMessage("Deleted " + name + ". Undo brings it back.", GeodataWidgets.MUTED);
            return false;
        }
        return true;
    }

    private void drawMoreMenu(AreaDocument document, ServerArea area) {
        if (!HudWidgets.beginFittedMenu("area-more-menu", 236, HudWidgets.menuHeight(2, 0))) {
            return;
        }
        if (HudWidgets.menuRow("area-more-paste", Glyph.COPY, "Paste area here", "")) {
            pasteAreas(document, ImGui.getClipboardText());
        }
        GeodataWidgets.tip("Adds areas copied as " + AreaDataFile.FILE_NAME + " text in the middle of the view, with"
                + " free names: Copy as text, or records copied from a file.");
        var reaches = !otherTiles(area).isEmpty();
        if (HudWidgets.menuRow("area-more-split", Glyph.SLICE, "Split at tile borders", "", reaches)) {
            var index = area.index();
            document.apply("Split at tile borders", state -> AreaEdits.splitAtTileBorders(state, index));
            merge.reset();
        }
        GeodataWidgets.tip(
                reaches
                        ? "Cuts the area at the borders of its tiles: the server looks only at the tile of its middle,"
                                + " so each part becomes an area of its own tile."
                        : "The area lies on one tile.");
        HudWidgets.endMenu();
    }

    private void drawAreaMap(ServerArea area, AreaStyle style, Camera eye) {
        var banned = new ArrayList<SpawnTerritory>();
        for (var part = 0; part < area.banned().size(); part++) {
            banned.add(new SpawnTerritory(
                    -1,
                    "excluded " + (part + 1),
                    false,
                    0,
                    area.banned().get(part),
                    area.line(),
                    SourceSpan.NONE,
                    SourceSpan.NONE,
                    SourceSpan.NONE));
        }
        var points = new ArrayList<MapPoint>();
        var teleports = area.teleportPoints();
        for (var i = 0; i < teleports.size(); i++) {
            var place = teleports.get(i);
            points.add(new MapPoint(place.x(), place.y(), place.z(), style.argb, true, i, "Teleport point " + (i + 1)));
        }
        var restarts = area.restartPoints();
        for (var i = 0; i < restarts.size(); i++) {
            var place = restarts.get(i);
            points.add(new MapPoint(
                    place.x(),
                    place.y(),
                    place.z(),
                    RESTART_ARGB,
                    true,
                    teleports.size() + i,
                    "Restart point " + (i + 1)));
        }
        var look = eye.getPerspectiveLookDir();
        var picked = SpawnPanelWidgets.zoneMap(
                "area-map",
                List.of(territoryOf(area)),
                banned,
                style.argb,
                points,
                -1,
                MAP_HEIGHT,
                eye.getPosition().getX(),
                eye.getPosition().getY(),
                Math.toDegrees(Math.atan2(look.getY(), look.getX())));
        if (picked != null && picked.twice()) {
            var point = picked.point();
            frame(
                    eye,
                    new Bounds3f(
                            (float) point.x() - 200,
                            (float) point.y() - 200,
                            (float) point.z(),
                            (float) point.x() + 200,
                            (float) point.y() + 200,
                            (float) point.z() + 200),
                    -35);
        }
    }

    private void focus(Camera eye, int index) {
        var document = source.getDocument();
        var area = document == null ? null : document.getState().area(index);
        if (area == null || area.points().isEmpty()) {
            return;
        }
        var z = area.isWater() ? area.top() : groundOf(area, eye);
        frame(
                eye,
                new Bounds3f(area.minX(), area.minY(), (float) z, area.maxX(), area.maxY(), (float)
                        Math.min(z + 300, Math.max(z, area.top()))),
                -50);
    }

    private double groundOf(ServerArea area, Camera eye) {
        for (var item : drawn) {
            if (item.index() == area.index()) {
                return item.groundZ();
            }
        }
        var drape = ground.isAvailable() ? ground.getDrape(territoryOf(area)) : null;
        if (drape != null) {
            return drape.points()[2];
        }
        return fallbackHeight(area, eye.getPosition().getZ());
    }

    private void frame(Camera eye, Bounds3f bounds, double pitch) {
        var look = eye.getPerspectiveLookDir();
        eye.setDirection(Math.toDegrees(Math.atan2(look.getY(), look.getX())), pitch);
        var io = ImGui.getIO();
        var width = io.getDisplaySizeX();
        var height = io.getDisplaySizeY();
        var view = viewport;
        if (view == null || width <= 0 || height <= 0 || view.right() <= view.left() || view.bottom() <= view.top()) {
            eye.focus(bounds);
            return;
        }
        eye.focus(
                bounds,
                (view.left() + view.right()) / width - 1f,
                1f - (view.top() + view.bottom()) / height,
                (view.right() - view.left()) / width,
                (view.bottom() - view.top()) / height);
    }

    private SpawnTerritory territoryOf(ServerArea area) {
        if (territories.size() > 8192) {
            territories.clear();
        }
        return territories.computeIfAbsent(
                area,
                key -> new SpawnTerritory(
                        -1,
                        "area " + key.index(),
                        false,
                        0,
                        key.points(),
                        key.line(),
                        SourceSpan.NONE,
                        SourceSpan.NONE,
                        SourceSpan.NONE));
    }

    private boolean isInLoadedTiles(double minX, double minY, double maxX, double maxY) {
        var current = tiles;
        return current == null || current.isEmpty() || current.intersects(minX, minY, maxX, maxY);
    }

    private void select(int index) {
        if (index != selected) {
            editing = -1;
            markedField = null;
            var waiting = placing;
            if (waiting != null && waiting.area() != index) {
                placing = null;
                clicks.cancel();
            }
            if (drawingPart >= 0 && drawingPart != index) {
                drawingPart = -1;
                drawnCorners.clear();
                clicks.cancel();
            }
        }
        selected = index;
    }

    private void stopGestures() {
        drawingType = null;
        drawingPart = -1;
        placing = null;
        closingHot = false;
        drawnCorners.clear();
        clicks.clear();
    }

    private void clearPendingLeave() {
        pendingLeave = null;
        pendingFolder = null;
        pendingReload = false;
    }

    private void setMessage(String text, int color) {
        message = text;
        messageColor = color;
    }

    private void edit(AreaDocument document, String key, String label, UnaryOperator<State> change) {
        var now = System.nanoTime();
        if (merge.shouldAmend(key, now, document.canUndo(), document.getRevision())) {
            document.amend(change);
        } else {
            document.apply(label, change);
        }
        merge.record(key, now, document.getRevision());
    }

    private List<Handle> createHandles(State state, float eyeX, float eyeY) {
        var area = state.area(editing);
        Drawn item = null;
        for (var candidate : drawn) {
            if (candidate.index() == editing) {
                item = candidate;
            }
        }
        if (area == null || item == null || area.points().size() < 3) {
            return List.of();
        }
        var result = new ArrayList<Handle>();
        addOutlineHandles(result, -1, item.points(), item.corners(), !item.water());
        var eyeZ = camera == null ? item.groundZ() : camera.getPosition().getZ();
        for (var part = 0; part < area.banned().size(); part++) {
            var outline = findPartOutline(area, part, eyeZ);
            if (outline != null) {
                addOutlineHandles(result, part, outline.points(), outline.corners(), true);
            }
        }
        var corners = area.points();
        var nearest = 0;
        var best = Double.MAX_VALUE;
        for (var i = 0; i < corners.size(); i++) {
            var a = corners.get(i);
            var b = corners.get((i + 1) % corners.size());
            var away = Math.hypot((a.x() + b.x()) * .5 - eyeX, (a.y() + b.y()) * .5 - eyeY);
            if (away < best) {
                best = away;
                nearest = i;
            }
        }
        var a = corners.get(nearest);
        var b = corners.get((nearest + 1) % corners.size());
        var middleX = (a.x() + b.x()) * .5;
        var middleY = (a.y() + b.y()) * .5;
        result.add(new Handle(
                Grab.BOTTOM, -1, -1, false, middleX, middleY, Math.max(area.bottom(), item.groundZ() - VOLUME_REACH)));
        result.add(new Handle(
                Grab.TOP, -1, -1, false, middleX, middleY, Math.min(area.top(), item.groundZ() + VOLUME_REACH)));
        addPointHandles(result, area.teleportPoints(), false, eyeX, eyeY);
        addPointHandles(result, area.restartPoints(), true, eyeX, eyeY);
        return result;
    }

    private void addPointHandles(List<Handle> result, List<AreaPoint> points, boolean restart, float eyeX, float eyeY) {
        for (var i = 0; i < points.size(); i++) {
            var point = points.get(i);
            if (Math.hypot(point.x() - eyeX, point.y() - eyeY) <= viewReach
                    && isInLoadedTiles(point.x(), point.y(), point.x(), point.y())) {
                result.add(new Handle(Grab.POINT, -1, i, restart, point.x(), point.y(), point.z()));
            }
        }
    }

    private @Nullable Handle handleAt(List<Handle> candidates, float mouseX, float mouseY) {
        Handle best = null;
        var bestDistance = (double) WorldHandles.PIXELS;
        for (var handle : candidates) {
            if (!projector.project(handle.x(), handle.y(), handle.z() + 6, screen)) {
                continue;
            }
            var distance =
                    Math.hypot(screen[0] - mouseX, screen[1] - mouseY) + (handle.grab() == Grab.MIDPOINT ? 2 : 0);
            if (distance <= bestDistance) {
                best = handle;
                bestDistance = distance;
            }
        }
        return best;
    }

    private double computeUnitsPerPoint(Handle handle) {
        var eye = camera;
        if (eye == null) {
            return 1;
        }
        var position = eye.getPosition();
        var away = Math.hypot(
                Math.hypot(handle.x() - position.getX(), handle.y() - position.getY()), handle.z() - position.getZ());
        var points = projector.getPointsTall(1, Math.max(1, away));
        return points > 0 ? 1 / points : 1;
    }

    private boolean tryStartMove(
            State state, double ox, double oy, double oz, double dx, double dy, double dz, float mouseX, float mouseY) {
        var area = state.area(editing);
        Drawn item = null;
        for (var candidate : drawn) {
            if (candidate.index() == editing) {
                item = candidate;
            }
        }
        if (area == null || item == null || Math.abs(dz) < 1e-6) {
            return false;
        }
        var t = (item.groundZ() - oz) / dz;
        if (t <= 0) {
            return false;
        }
        var x = ox + dx * t;
        var y = oy + dy * t;
        if (!area.containsXy(x, y)) {
            return false;
        }
        var part = -1;
        for (var i = 0; i < area.banned().size(); i++) {
            if (Polygons.containsXy(area.banned().get(i), x, y)) {
                part = i;
            }
        }
        drag = new Drag(new Handle(Grab.MOVE, part, -1, false, x, y, item.groundZ()), mouseX, mouseY, area, 1);
        return true;
    }

    private void continueDrag(
            AreaDocument document,
            Drag active,
            double ox,
            double oy,
            double oz,
            double dx,
            double dy,
            double dz,
            float mouseX,
            float mouseY) {
        if (!active.applied && Math.hypot(mouseX - active.pressX, mouseY - active.pressY) < DRAG_START) {
            return;
        }
        var handle = active.handle;
        var pressed = active.area;
        var index = pressed.index();
        if (handle.grab() == Grab.TOP || handle.grab() == Grab.BOTTOM) {
            var change = (int) Math.round((active.pressY - mouseY) * active.unitsPerPoint);
            var top = handle.grab() == Grab.TOP;
            var value = top
                    ? Math.max(pressed.top() + change, pressed.bottom() + 1)
                    : Math.min(pressed.bottom() + change, pressed.top() - 1);
            active.value = value;
            applyDrag(
                    document,
                    active,
                    top ? pressed.isWater() ? "Drag water surface" : "Drag area top" : "Drag area bottom",
                    state -> AreaEdits.setHeights(state, index, top ? null : value, top ? value : null));
            return;
        }
        if (Math.abs(dz) < 1e-6) {
            return;
        }
        var t = (handle.z() - oz) / dz;
        if (t <= 0) {
            return;
        }
        var x = (int) Math.round(ox + dx * t);
        var y = (int) Math.round(oy + dy * t);
        switch (handle.grab()) {
            case CORNER ->
                applyDrag(
                        document,
                        active,
                        handle.part() < 0 ? "Move area corner" : "Move excluded corner",
                        state -> AreaFields.moveCorner(state, index, handle.part(), handle.corner(), x, y));
            case MIDPOINT -> {
                if (!active.applied) {
                    insertCorner(document, handle, x, y);
                    active.applied = true;
                    return;
                }
                applyDrag(
                        document,
                        active,
                        "Add area corner",
                        state -> AreaFields.moveCorner(state, index, handle.part(), handle.corner() + 1, x, y));
            }
            case MOVE -> {
                var shiftX = (int) Math.round(x - handle.x());
                var shiftY = (int) Math.round(y - handle.y());
                if (handle.part() < 0) {
                    applyDrag(
                            document,
                            active,
                            "Move area",
                            state -> state.withArea(AreaEdits.moved(pressed, shiftX, shiftY)));
                } else {
                    var parts = new ArrayList<>(pressed.banned());
                    parts.set(handle.part(), SpawnEdits.shifted(parts.get(handle.part()), shiftX, shiftY, 0));
                    applyDrag(
                            document, active, "Move excluded part", state -> AreaEdits.setBanned(state, index, parts));
                }
            }
            case POINT -> {
                var floor = ground.getGroundAt(x, y, handle.z() + 64);
                var place = new AreaPoint(
                        x, y, Double.isNaN(floor) ? (int) Math.round(handle.z()) : (int) Math.round(floor));
                applyDrag(
                        document,
                        active,
                        handle.restart() ? "Move restart point" : "Move teleport point",
                        state -> AreaFields.setPoint(state, index, handle.restart(), handle.corner(), place));
            }
            default -> {}
        }
    }

    private void applyDrag(AreaDocument document, Drag active, String label, UnaryOperator<State> change) {
        if (active.applied) {
            document.amend(change);
        } else {
            var revision = document.getRevision();
            document.apply(label, change);
            active.applied = document.getRevision() != revision;
        }
        merge.reset();
    }

    private void endDrag(AreaDocument document, Drag active) {
        if (!active.applied && active.handle.grab() == Grab.MIDPOINT) {
            insertCorner(document, active.handle, active.handle.x(), active.handle.y());
        }
    }

    private void insertCorner(AreaDocument document, Handle handle, double x, double y) {
        var index = editing;
        document.apply(
                handle.part() < 0 ? "Add area corner" : "Add excluded corner",
                state -> AreaFields.insertCorner(
                        state, index, handle.part(), handle.corner(), (int) Math.round(x), (int) Math.round(y)));
        merge.reset();
    }

    private void removeCorner(AreaDocument document, Handle handle) {
        var area = document.getState().area(editing);
        if (area == null) {
            return;
        }
        if (handle.part() < 0 && area.isWater()) {
            setMessage("Water stays a box of four corners: drag a corner to change it.", GeodataWidgets.WARNING);
            return;
        }
        var points = handle.part() < 0 ? area.points() : area.banned().get(handle.part());
        if (points.size() <= 3) {
            setMessage(
                    handle.part() < 0
                            ? "An area needs at least three corners."
                            : "An excluded part needs at least three corners. Remove the part in the panel instead.",
                    GeodataWidgets.WARNING);
            return;
        }
        var index = area.index();
        document.apply(
                handle.part() < 0 ? "Remove area corner" : "Remove excluded corner",
                state -> AreaFields.removeCorner(state, index, handle.part(), handle.corner()));
        merge.reset();
        hoveredHandle = null;
    }

    private void handleGesture(
            AreaDocument document,
            ViewerRenderBackend renderBackend,
            boolean pointerFree,
            float ndcX,
            float ndcY,
            double ox,
            double oy,
            double oz,
            double dx,
            double dy,
            double dz) {
        var drawing = drawingType != null || drawingPart >= 0;
        var snap = drawing && !ImGui.getIO().getKeyShift();
        closingHot = drawing && isOnFirstCorner(pointerFree);
        if (closingHot && ImGui.isMouseClicked(0)) {
            var waiting = clicks.resolveNow(lastGroundUnder);
            if (waiting != null) {
                applyClick(document, waiting);
            }
            closeDrawing(document);
            return;
        }
        for (var point : clicks.update(
                renderBackend, camera, pointerFree, ndcX, ndcY, ox, oy, oz, dx, dy, dz, snap, lastGroundUnder)) {
            applyClick(document, point);
        }
        if (drawing && !ImGui.getIO().getWantTextInput()) {
            if ((ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter))
                    && drawnCorners.size() + (clicks.isPending() ? 1 : 0) >= 3) {
                var waiting = clicks.resolveNow(lastGroundUnder);
                if (waiting != null) {
                    applyClick(document, waiting);
                }
                closeDrawing(document);
            } else if (ImGui.isKeyPressed(ImGuiKey.Backspace)) {
                if (clicks.isPending()) {
                    clicks.cancel();
                } else if (!drawnCorners.isEmpty()) {
                    drawnCorners.removeLast();
                }
            }
        }
    }

    private boolean isOnFirstCorner(boolean pointerFree) {
        if (!pointerFree || drawnCorners.size() < 3) {
            return false;
        }
        var first = drawnCorners.getFirst();
        return projector.project(first[0], first[1], first[3] + 6, screen)
                && Math.hypot(screen[0] - ImGui.getMousePosX(), screen[1] - ImGui.getMousePosY())
                        <= WorldHandles.CLOSE_PIXELS;
    }

    private void applyClick(AreaDocument document, double[] point) {
        if (drawingType != null || drawingPart >= 0) {
            drawnCorners.add(new int[] {
                (int) Math.round(point[0]),
                (int) Math.round(point[1]),
                (int) Math.round(point[2]),
                (int) Math.round(point[3])
            });
        } else if (placing != null) {
            placePoint(document, point);
        }
    }

    private void closeDrawing(AreaDocument document) {
        if (drawnCorners.size() < 3) {
            return;
        }
        var low = Integer.MAX_VALUE;
        var high = Integer.MIN_VALUE;
        var sumX = 0.0;
        var sumY = 0.0;
        for (var corner : drawnCorners) {
            low = Math.min(low, corner[2]);
            high = Math.max(high, corner[2]);
            sumX += corner[0];
            sumY += corner[1];
        }
        var part = drawingPart;
        var type = drawingType;
        if (part >= 0) {
            var area = document.getState().area(part);
            if (area != null) {
                var points = new ArrayList<TerritoryPoint>();
                for (var corner : drawnCorners) {
                    points.add(new TerritoryPoint(corner[0], corner[1], area.bottom(), area.top()));
                }
                var parts = new ArrayList<>(area.banned());
                parts.add(points);
                document.apply("Draw excluded part", state -> AreaEdits.setBanned(state, part, parts));
                merge.reset();
            }
        } else if (type != null) {
            var water = AreaType.WATER.getKeyword().equals(type);
            var bottom = water ? low - 400 : low - 200;
            var top = water ? high : high + 400;
            var points = new ArrayList<TerritoryPoint>();
            for (var corner : drawnCorners) {
                points.add(new TerritoryPoint(corner[0], corner[1], bottom, top));
            }
            createArea(document, type, points, sumX / drawnCorners.size(), sumY / drawnCorners.size(), "Draw area");
        }
        drawingType = null;
        drawingPart = -1;
        closingHot = false;
        drawnCorners.clear();
        clicks.cancel();
    }

    private void createArea(
            AreaDocument document, String type, List<TerritoryPoint> points, double x, double y, String label) {
        var state = document.getState();
        var name = AreaEdits.newName(state, type, AreaTiles.tileX(x), AreaTiles.tileY(y));
        var index = state.nextIndex();
        var file = document.getFile();
        document.apply(label, current -> AreaEdits.newArea(current, file, type, name, points));
        merge.reset();
        clearSearch();
        select(index);
        editing = index;
        markedField = neededSetting(type);
        visible = true;
    }

    private void addNewArea(AreaDocument document, String type) {
        var target = findViewTarget();
        if (target == null) {
            return;
        }
        var water = AreaType.WATER.getKeyword().equals(type);
        var z = (int) Math.round(target[2]);
        var points = SpawnEdits.square(
                (int) Math.round(target[0]),
                (int) Math.round(target[1]),
                NEW_SIZE / 2,
                water ? z - 400 : z - 200,
                water ? z : z + 400);
        createArea(document, type, points, target[0], target[1], "New area");
    }

    private double @Nullable [] findViewTarget() {
        var eye = camera;
        if (eye == null) {
            return null;
        }
        var origin = eye.getCursorRayOrigin(0, 0);
        var direction = eye.getCursorRayDirection(0, 0);
        var render = backend;
        var hit = render == null ? Float.NaN : render.getSceneRayDistance(0, 0, 60_000);
        if (Float.isFinite(hit) && hit > 0) {
            return new double[] {
                origin.getX() + direction.getX() * hit,
                origin.getY() + direction.getY() * hit,
                origin.getZ() + direction.getZ() * hit
            };
        }
        var geodata = ground.findRayGround(
                new GeoPoint(origin.getX(), origin.getY(), origin.getZ()),
                new GeoPoint(direction.getX(), direction.getY(), direction.getZ()),
                60_000);
        if (geodata != null) {
            return new double[] {geodata.x(), geodata.y(), geodata.z()};
        }
        var x = origin.getX() + direction.getX() * 800;
        var y = origin.getY() + direction.getY() * 800;
        var groundZ = ground.getGroundAt(x, y, origin.getZ());
        return new double[] {x, y, Double.isNaN(groundZ) ? origin.getZ() - 200 : groundZ};
    }

    private void duplicateHere(AreaDocument document, ServerArea area) {
        var target = findViewTarget();
        var eye = camera;
        if (target == null || eye == null) {
            return;
        }
        var shiftX = (int) Math.round(target[0] - (area.minX() + (double) area.maxX()) / 2);
        var shiftY = (int) Math.round(target[1] - (area.minY() + (double) area.maxY()) / 2);
        var shiftZ = (int) Math.round(target[2] - groundOf(area, eye));
        var index = document.getState().nextIndex();
        var original = area.index();
        document.apply("Duplicate area", state -> {
            var next = AreaEdits.duplicate(state, original, shiftX, shiftY);
            var copy = next.area(index);
            return copy == null ? next : next.withArea(AreaEdits.raised(copy, shiftZ));
        });
        merge.reset();
        select(index);
        editing = index;
        visible = true;
    }

    private void pasteAreas(AreaDocument document, @Nullable String text) {
        if (text == null || !text.contains("area_begin")) {
            setMessage(
                    "The clipboard holds no area. Copy one with Copy as text, or copy its lines from "
                            + AreaDataFile.FILE_NAME + ".",
                    GeodataWidgets.WARNING);
            return;
        }
        var target = findViewTarget();
        if (target == null) {
            return;
        }
        var pasted =
                AreaCopies.paste(document.getState(), document.getFile(), text, target[0], target[1], target[2] + 100);
        if (pasted == null) {
            setMessage("The clipboard holds no area with three corners or more.", GeodataWidgets.WARNING);
            return;
        }
        var count = pasted.indexes().size();
        document.apply(count == 1 ? "Paste area" : "Paste areas", unused -> pasted.state());
        merge.reset();
        clearSearch();
        var first = pasted.indexes().getFirst();
        select(first);
        editing = first;
        visible = true;
        setMessage(
                count == 1 ? "Pasted 1 area." : "Pasted " + count + " areas. The first is selected.",
                GeodataWidgets.MUTED);
    }

    private void placePoint(AreaDocument document, double[] point) {
        var target = placing;
        placing = null;
        if (target == null) {
            return;
        }
        var area = document.getState().area(target.area());
        if (area == null) {
            return;
        }
        var points = new ArrayList<>(target.restart() ? area.restartPoints() : area.teleportPoints());
        points.add(new AreaPoint((int) Math.round(point[0]), (int) Math.round(point[1]), (int) Math.round(point[2])));
        document.apply(
                target.restart() ? "Add restart point" : "Add teleport point",
                state -> target.restart()
                        ? AreaEdits.setRestartPoints(state, target.area(), points)
                        : AreaEdits.setTeleportPoints(state, target.area(), points));
        merge.reset();
    }

    private int @Nullable [] findGroundRange(ServerArea area) {
        if (ground.isAvailable()) {
            var drape = ground.createDrapeNow(territoryOf(area));
            if (drape != null && drape.grounded()) {
                var low = Float.MAX_VALUE;
                var high = -Float.MAX_VALUE;
                for (var i = 2; i < drape.points().length; i += 3) {
                    low = Math.min(low, drape.points()[i]);
                    high = Math.max(high, drape.points()[i]);
                }
                return new int[] {Math.round(low), Math.round(high)};
            }
        }
        var floors = clientFloors;
        var eye = camera;
        if (floors == null || eye == null) {
            return null;
        }
        var low = Double.MAX_VALUE;
        var high = -Double.MAX_VALUE;
        for (var corner : area.points()) {
            var floor = floors.findFloorBelow(
                    corner.x(), corner.y(), eye.getPosition().getZ());
            if (Double.isFinite(floor)) {
                low = Math.min(low, floor);
                high = Math.max(high, floor);
            }
        }
        return low > high ? null : new int[] {(int) Math.round(low), (int) Math.round(high)};
    }

    private void drawHandles(SpawnOverlayState overlay) {
        var active = drag;
        for (var handle : handles) {
            var hot = isSameHandle(handle, hoveredHandle) || active != null && isSameHandle(handle, active.handle);
            var point = new GeoPoint(handle.x(), handle.y(), handle.z());
            switch (handle.grab()) {
                case CORNER -> {
                    if (handle.part() < 0) {
                        WorldHandles.corner(overlay.areaLines, point, hot);
                    } else {
                        WorldHandles.blockedCorner(overlay.areaLines, point, hot);
                    }
                }
                case MIDPOINT -> WorldHandles.midpoint(overlay.areaLines, point, hot);
                case TOP, BOTTOM -> WorldHandles.height(overlay.areaLines, point, hot);
                case POINT -> WorldHandles.point(overlay.areaLines, point, hot);
                case MOVE -> {}
            }
        }
    }

    private void drawGesture(SpawnOverlayState overlay) {
        var pointer = clicks.getPointer();
        if (drawingType != null || drawingPart >= 0) {
            var color = drawingPart >= 0 ? GeodataWidgets.BLOCKED : HudWidgets.ACCENT;
            var closable = drawnCorners.size() >= 3;
            GeoPoint start = null;
            GeoPoint previous = null;
            for (var corner : drawnCorners) {
                var point = new GeoPoint(corner[0], corner[1], corner[3]);
                if (previous != null) {
                    overlay.areaLines.addLine(previous, point, 6, color, 2.6f);
                }
                if (start == null) {
                    start = point;
                }
                if (closable && point == start) {
                    WorldHandles.closingCorner(overlay.areaLines, point, color, closingHot);
                } else {
                    WorldHandles.corner(overlay.areaLines, point, false);
                }
                previous = point;
            }
            if (closingHot && start != null && previous != null) {
                overlay.areaLines.addLine(previous, start, 6, color, 2.6f);
            } else if (pointer != null) {
                var at = new GeoPoint(pointer[0], pointer[1], pointer[3]);
                if (previous != null) {
                    overlay.areaLines.addLine(previous, at, 6, Motion.alpha(color, .7f), 2f);
                }
                if (drawnCorners.size() >= 2) {
                    var first = drawnCorners.getFirst();
                    overlay.areaLines.addLine(
                            at, new GeoPoint(first[0], first[1], first[3]), 6, Motion.alpha(color, .32f), 1.5f);
                }
                overlay.areaLines.handle(at, 6, color, 9);
            }
        } else if (placing != null && pointer != null) {
            var segments = 24;
            for (var i = 0; i < segments; i++) {
                var a = Math.PI * 2 * i / segments;
                var b = Math.PI * 2 * (i + 1) / segments;
                overlay.areaLines.addLine(
                        new GeoPoint(pointer[0] + Math.cos(a) * 30, pointer[1] + Math.sin(a) * 30, pointer[3]),
                        new GeoPoint(pointer[0] + Math.cos(b) * 30, pointer[1] + Math.sin(b) * 30, pointer[3]),
                        4,
                        HudWidgets.ACCENT,
                        2.5f);
            }
            overlay.areaLines.handle(new GeoPoint(pointer[0], pointer[1], pointer[3]), 4, HudWidgets.ACCENT, 8);
        }
    }

    private void drawPointerTag(ImDrawList draw) {
        String text = null;
        var active = drag;
        if (active != null
                && active.applied
                && (active.handle.grab() == Grab.TOP || active.handle.grab() == Grab.BOTTOM)) {
            var top = active.handle.grab() == Grab.TOP;
            var high = top ? active.value - active.area.bottom() : active.area.top() - active.value;
            text = (top ? active.area.isWater() ? "Surface " : "Top " : "Bottom ")
                    + formatNumber(active.value)
                    + " · "
                    + formatNumber(high)
                    + " high";
        } else if (clicks.getPointer() != null) {
            if (drawingType != null || drawingPart >= 0) {
                text = closingHot
                        ? drawingType != null ? "Click to close the area" : "Click to close the part"
                        : describeDrawing();
            } else if (placing != null) {
                text = placing.restart()
                        ? "Click to place the restart point here"
                        : "Click to place the teleport point here";
            }
        }
        if (text == null) {
            return;
        }
        var width = ImGui.calcTextSizeX(text) + 14;
        var x = ImGui.getMousePosX() + 18;
        var y = ImGui.getMousePosY() + 22;
        var view = viewport;
        if (view != null && x + width > view.right() - 8) {
            x = ImGui.getMousePosX() - 18 - width;
        }
        draw.addRectFilled(x, y, x + width, y + ImGui.getFontSize() + 6, TAG_BACKING, 5f);
        draw.addText(x + 7, y + 3, HudWidgets.TEXT, text);
    }

    private void drawPartWarnings(ImDrawList draw, State state) {
        var area = state.area(selected);
        Drawn item = null;
        for (var candidate : drawn) {
            if (candidate.index() == selected) {
                item = candidate;
            }
        }
        if (area == null || item == null) {
            return;
        }
        for (var part = 0; part < area.banned().size(); part++) {
            var polygon = area.banned().get(part);
            var outside = false;
            var sumX = 0.0;
            var sumY = 0.0;
            for (var point : polygon) {
                outside |= !area.containsXy(point.x(), point.y());
                sumX += point.x();
                sumY += point.y();
            }
            if (!outside
                    || polygon.isEmpty()
                    || !projector.project(
                            sumX / polygon.size(), sumY / polygon.size(), item.groundZ() + CURTAIN, screen)) {
                continue;
            }
            var text = "Excluded part " + (part + 1) + " reaches outside: the server drops the area";
            var width = ImGui.calcTextSizeX(text) + 14;
            var x = screen[0] - width * .5f;
            var y = screen[1] - ImGui.getFontSize() - 6;
            draw.addRectFilled(x, y, x + width, y + ImGui.getFontSize() + 6, TAG_BACKING, 5f);
            draw.addRect(x, y, x + width, y + ImGui.getFontSize() + 6, GeodataWidgets.BLOCKED, 5f, 0, 1.2f);
            draw.addText(x + 7, y + 3, GeodataWidgets.BLOCKED, text);
            hintAreas.add(new float[] {x, y, x + width, y + ImGui.getFontSize() + 6});
        }
    }

    private String describeDrawing() {
        var corners = new ArrayList<int[]>(drawnCorners);
        var pointer = clicks.getPointer();
        if (pointer != null) {
            corners.add(new int[] {(int) pointer[0], (int) pointer[1]});
        }
        var minX = Integer.MAX_VALUE;
        var minY = Integer.MAX_VALUE;
        var maxX = Integer.MIN_VALUE;
        var maxY = Integer.MIN_VALUE;
        for (var corner : corners) {
            minX = Math.min(minX, corner[0]);
            minY = Math.min(minY, corner[1]);
            maxX = Math.max(maxX, corner[0]);
            maxY = Math.max(maxY, corner[1]);
        }
        var count = drawnCorners.size();
        return (count == 1 ? "1 corner" : count + " corners")
                + (corners.size() >= 2 ? " · " + formatNumber(maxX - minX) + " × " + formatNumber(maxY - minY) : "")
                + (count < 3
                        ? " · click " + (3 - count) + " more on the ground"
                        : " · the first corner or Enter closes it");
    }

    private void drawMakeChips(AreaDocument document) {
        space(ROW);
        var spacing = ImGui.getStyle().getItemSpacingX();
        var width = (ImGui.getContentRegionAvailX() - spacing) / 2;
        if (GeodataWidgets.chip("area-new", Glyph.PLUS, "New area", ImGui.isPopupOpen("area-new-type"), width)) {
            ImGui.openPopup("area-new-type");
        }
        GeodataWidgets.tip("Choose a type: an 800 × 800 square of it stands on the ground in the middle of the view,"
                + " ready to shape.");
        var type = fields.drawTypeMenu("area-new-type", document.getSavedTypes());
        if (type != null) {
            stopGestures();
            addNewArea(document, type);
        }
        ImGui.sameLine();
        var drawing = drawingType != null;
        if (GeodataWidgets.chip(
                "area-draw", Glyph.PENCIL, "Draw area", drawing || ImGui.isPopupOpen("area-draw-type"), width)) {
            if (drawing) {
                stopGestures();
            } else {
                ImGui.openPopup("area-draw-type");
            }
        }
        GeodataWidgets.tip("Choose a type, then click its corners on the ground, snapped to 16-unit geodata cells."
                + " A click on the first corner or Enter closes it, Esc cancels.");
        var drawn = fields.drawTypeMenu("area-draw-type", document.getSavedTypes());
        if (drawn != null) {
            stopGestures();
            editing = -1;
            drawingType = drawn;
            visible = true;
        }
    }

    private void drawDrawingCard(AreaDocument document) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var type = drawingType;
        var part = document.getState().area(drawingPart);
        draw.channelsSplit(2);
        draw.channelsSetCurrent(1);
        ImGui.setCursorScreenPos(x + 10, y + 8);
        ImGui.beginGroup();
        if (type != null) {
            var known = AreaType.find(type);
            GeodataWidgets.dot(AreaStyle.of(known).getImguiColor(1f));
            ImGui.textUnformatted("Drawing a new area · " + (known == null ? type : AreaStyle.title(known)));
        } else {
            GeodataWidgets.dot(GeodataWidgets.BLOCKED);
            ImGui.textUnformatted("Drawing an excluded part of " + (part == null ? "the area" : part.name()));
        }
        ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.MUTED);
        ImGui.textUnformatted(describeDrawing());
        ImGui.popStyleColor();
        space(ROW);
        var half = (width - 20 - ImGui.getStyle().getItemSpacingX()) / 2;
        ImGui.beginDisabled(drawnCorners.size() < 3);
        var close = HudWidgets.button(
                "area-draw-close", null, type != null ? "Close area" : "Close part", half, 26, true, true);
        ImGui.endDisabled();
        GeodataWidgets.tip(type != null ? "Makes the area, selected and ready to shape." : "Adds the part.", "Enter");
        ImGui.sameLine();
        var cancel = HudWidgets.button("area-draw-cancel", null, "Cancel", half, 26, false, true);
        GeodataWidgets.tip("Stop drawing.", "Esc");
        ImGui.endGroup();
        var bottom = ImGui.getItemRectMaxY() + 8;
        draw.channelsSetCurrent(0);
        draw.addRectFilled(x, y, x + width, bottom, GeodataWidgets.CARD, 8f);
        draw.addRect(x, y, x + width, bottom, ImColor.rgba(92, 219, 241, 90), 8, 0, 1);
        draw.channelsMerge();
        ImGui.setCursorScreenPos(x, bottom);
        ImGui.dummy(width, 0);
        if (close) {
            closeDrawing(document);
        } else if (cancel) {
            stopGestures();
        }
    }

    private List<Issue> findIssues(AreaDocument document) {
        if (document.getState() != issuesState || document.getSavedState() != issuesSaved) {
            issues =
                    AreaChecks.findIntroduced(document.getSavedState(), document.getState(), document.getSavedTypes())
                            .stream()
                            .map(Finding::toIssue)
                            .toList();
            issuesState = document.getState();
            issuesSaved = document.getSavedState();
        }
        return issues;
    }

    private void openReview(AreaDocument document) {
        if (!document.isModified() || source.isSaving()) {
            return;
        }
        var found = AreaReview.of(document);
        reviewDialog.foldAll();
        review = new Content(
                found.changes(),
                found.sections(),
                found.removed(),
                found.added(),
                source.hasChangedOnDisk(),
                found.errors(),
                found.warnings());
    }

    private void drawReview(@Nullable ImFont monoFont) {
        var answer = reviewDialog.draw(review, monoFont, clock);
        if (answer == Answer.SAVE) {
            source.save(reviewDialog.isBackup());
        }
        if (answer != Answer.NONE) {
            review = null;
        }
    }

    private void drawFooter(AreaDocument document) {
        switch (footer.draw(footerStatus(document))) {
            case UNDO -> {
                document.undo();
                drag = null;
                merge.reset();
            }
            case REDO -> {
                document.redo();
                drag = null;
                merge.reset();
            }
            case DISCARD -> {
                document.discardChanges();
                merge.reset();
            }
            case SAVE -> openReview(document);
            case NONE -> {}
        }
    }

    private SaveFooter.Status footerStatus(AreaDocument document) {
        var modified = document.isModified();
        return new SaveFooter.Status(
                document.canUndo(),
                document.canRedo(),
                document.getUndoLabel(),
                document.getRedoLabel(),
                modified,
                source.isSaving(),
                modified ? SaveReviewDialog.filterErrors(findIssues(document)).size() : 0,
                source.getMessage(),
                source.isMessageError(),
                source.getFile());
    }

    private static void addOutline(
            SpawnOverlayState overlay, float[] points, int[] corners, int step, float lift, int color, float width) {
        var kept = SpawnShapes.thinOutline(points.length / 3, corners, step);
        for (var k = 0; k + 1 < kept.length; k++) {
            var i = kept[k];
            var j = kept[k + 1];
            overlay.areaLines.addLine(
                    new GeoPoint(points[i * 3], points[i * 3 + 1], points[i * 3 + 2]),
                    new GeoPoint(points[j * 3], points[j * 3 + 1], points[j * 3 + 2]),
                    lift,
                    color,
                    width);
        }
    }

    private static void drawPlaces(SpawnOverlayState overlay, List<AreaPoint> places, int argb) {
        var color = SpawnStyle.getImguiColor(argb, 1f, 1.1f);
        for (var place : places) {
            var foot = new GeoPoint(place.x(), place.y(), place.z());
            var head = new GeoPoint(place.x(), place.y(), place.z() + POLE);
            overlay.areaLines.addLine(foot, head, 0, SpawnStyle.getImguiColor(argb, .8f, 1f), 1.6f);
            overlay.areaLines.addDot(head, 0, color, 10);
            overlay.areaLines.addDot(foot, 2, color, 6);
        }
    }

    private static boolean overlapsAny(float[] box, List<float[]> others) {
        for (var other : others) {
            if (box[0] < other[2] && box[2] > other[0] && box[1] < other[3] && box[3] > other[1]) {
                return true;
            }
        }
        return false;
    }

    private static double tagDistance(Drawn item, float eyeX, float eyeY, float eyeZ) {
        var area = item.area();
        return Math.hypot(Math.hypot(area.centerX() - eyeX, area.centerY() - eyeY), eyeZ - item.groundZ());
    }

    private static void drawTag(
            ImDrawList draw,
            float[] box,
            AreaStyle style,
            String word,
            String name,
            String off,
            float alpha,
            boolean chosen) {
        draw.addRectFilled(box[0], box[1], box[2], box[3], Motion.alpha(TAG_BACKING, alpha), 5f);
        if (chosen) {
            draw.addRect(box[0], box[1], box[2], box[3], style.getImguiColor(.9f), 5f, 0, 1.2f);
        }
        var x = box[0] + 7;
        var y = box[1] + 3;
        draw.addText(x, y, style.getImguiColor(alpha, 1.15f), word);
        x += ImGui.calcTextSizeX(word) + 8;
        draw.addText(x, y, Motion.alpha(HudWidgets.TEXT, alpha), name);
        if (!off.isEmpty()) {
            x += ImGui.calcTextSizeX(name) + 8;
            draw.addText(x, y, Motion.alpha(GeodataWidgets.FAINT, Math.max(alpha, .7f)), off);
        }
    }

    private static String findMatch(ServerArea area, String query) {
        if (area.note().toLowerCase(Locale.ROOT).contains(query)) {
            return area.note();
        }
        for (var key : List.of("skill_name", "skill_list")) {
            for (var value : area.values(key)) {
                if (value.toLowerCase(Locale.ROOT).contains(query)) {
                    return value.replace("[", "").replace("]", "").replace("@", "");
                }
            }
        }
        return "";
    }

    private static boolean matches(ServerArea area, String query) {
        if (area.name().toLowerCase(Locale.ROOT).contains(query)
                || area.typeWord().toLowerCase(Locale.ROOT).contains(query)
                || AreaStyle.title(area).toLowerCase(Locale.ROOT).contains(query)
                || area.note().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        for (var key : List.of("skill_name", "skill_list")) {
            for (var value : area.values(key)) {
                if (value.toLowerCase(Locale.ROOT).contains(query)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void fact(String label, String value, String tip) {
        ImGui.beginGroup();
        GeodataWidgets.value(label, value);
        ImGui.endGroup();
        GeodataWidgets.tip(tip);
    }

    private static String recordText(AreaDocument document, ServerArea area) {
        var span = area.span();
        var text = document.getFile().getSource().text();
        var record = span.isPresent()
                        && span.end() <= text.length()
                        && area.equals(document.getFile().getAreas().get(area.index()))
                ? text.substring(span.start(), span.end())
                : AreaDataWriter.formatArea(area);
        return record.replace('\t', ' ').replace("\r", "").replace('\n', ' ');
    }

    private static String describeBriefly(ServerArea area) {
        return AreaStyle.title(area)
                + " · "
                + tileOf(area)
                + (area.isOnByDefault() ? "" : " · off")
                + (area.hasSetting("instantzone_id") ? " · instance zone" : "");
    }

    private static boolean holdsNear(ServerArea area, double x, double y, double z) {
        if (z < area.bottom() - 64 || z > area.top() + 64 || !area.containsXy(x, y)) {
            return false;
        }
        for (var polygon : area.banned()) {
            if (Polygons.containsXy(polygon, x, y)) {
                return false;
            }
        }
        return true;
    }

    private static String otherTiles(ServerArea area) {
        var tileX = area.centerTileX();
        var tileY = area.centerTileY();
        var reached = new ArrayList<String>();
        var slack = AreaChecks.BORDER_SLACK;
        for (var x = AreaTiles.tileX(area.minX() + slack); x <= AreaTiles.tileX(area.maxX() - slack); x++) {
            for (var y = AreaTiles.tileY(area.minY() + slack); y <= AreaTiles.tileY(area.maxY() - slack); y++) {
                if ((x != tileX || y != tileY) && reached.size() < 3) {
                    reached.add(AreaTiles.format(x, y));
                }
            }
        }
        return String.join(", ", reached);
    }

    private static String tileOf(ServerArea area) {
        return AreaTiles.format(area.centerTileX(), area.centerTileY());
    }

    private static double boundsDistance(ServerArea area, double x, double y) {
        var dx = Math.max(0, Math.max(area.minX() - x, x - area.maxX()));
        var dy = Math.max(0, Math.max(area.minY() - y, y - area.maxY()));
        return Math.hypot(dx, dy);
    }

    private static String phrase(AreaStyle style) {
        return switch (style) {
            case MOTHER_TREE, SEVEN_SIGNS -> style.label;
            case WATER -> "water";
            case OTHER -> "the other types";
            default -> style.label.toLowerCase(Locale.ROOT);
        };
    }

    private static void addOutlineHandles(
            List<Handle> handles, int part, float[] points, int[] corners, boolean midpoints) {
        for (var i = 0; i < corners.length; i++) {
            var at = corners[i] * 3;
            handles.add(new Handle(Grab.CORNER, part, i, false, points[at], points[at + 1], points[at + 2]));
            if (midpoints) {
                var next = (i + 1 < corners.length ? corners[i + 1] : points.length / 3 - 1) * 3;
                handles.add(new Handle(
                        Grab.MIDPOINT,
                        part,
                        i,
                        false,
                        (points[at] + points[next]) * .5,
                        (points[at + 1] + points[next + 1]) * .5,
                        (points[at + 2] + points[next + 2]) * .5));
            }
        }
    }

    private static boolean isSameHandle(Handle a, @Nullable Handle b) {
        return b != null
                && a.grab() == b.grab()
                && a.part() == b.part()
                && a.corner() == b.corner()
                && a.restart() == b.restart();
    }

    private static @Nullable String neededSetting(String type) {
        var known = AreaType.find(type);
        return known == null
                ? null
                : switch (known) {
                    case POISON, INSTANT_SKILL -> "skill_name";
                    case TELEPORT -> "teleport_points";
                    default -> null;
                };
    }

    private static FindingWidgets.Severity severity(Kind kind) {
        return kind.getSeverity() == Severity.ERROR ? FindingWidgets.Severity.ERROR : FindingWidgets.Severity.WARNING;
    }

    private static String helpText(Kind kind) {
        return switch (kind) {
            case BAD_NAME -> "The name is empty or holds [ or ], so the file or a search by name cannot read it.";
            case NO_TYPE -> "Without type= the server does not know what the area does.";
            case FEW_CORNERS -> "An outline needs 3 corners at least.";
            case HEIGHTS_SWAPPED -> "A corner's bottom stands above its top.";
            case MAP_MISMATCH ->
                "map_no names another tile than the one of the area's middle, so the server refuses it.";
            case OUTSIDE_TABLE ->
                "The middle lies outside the tiles 10_10 to 29_26 that the server's area table holds.";
            case BANNED_OUTSIDE -> "An excluded part reaches outside the area, so the server drops the area.";
            case TOO_MANY_BANNED -> "The server takes " + AreaChecks.MOST_BANNED + " excluded parts at most.";
            case BAD_UNIT_TICK -> "The server divides by unit_tick, so it must be 1 or more.";
            case BAD_SKILL_PROB -> "skill_prob is a chance from 0 to 100.";
            case OTHER_TILE ->
                "The server files an area under the tile of its middle only, so a part on another tile does nothing.";
            case SAME_NAME -> "Two areas share a name, and a search by name finds only the last of them.";
            case CROSSING_SIDES -> "The sides cross, so the inside is not what the outline suggests.";
            case LOST_UNIT_TICK -> "type= comes after unit_tick= and sets a tick of its own, so the server drops it.";
            case NO_SKILL -> "A poison or instant skill area casts its skill_name or skill_list, and it has neither.";
            case NO_DESTINATION -> "A teleport area without teleport_points or teleport_range sends nobody anywhere.";
            case FLAT -> "Bottom and top are the same, so only that exact height is inside.";
            case UNKNOWN_TYPE ->
                "The type is not in this file and this tool does not know it. Check that your server reads it.";
            case TYPE_NEW_TO_FILE ->
                "No other area of the file has this type. A server of another version may not know it, and then it"
                        + " cannot read the area.";
        };
    }

    private static String away(double distance) {
        return distance == Double.MAX_VALUE ? "" : SpawnPanelWidgets.compact(Math.round(distance)) + " away";
    }

    private static String formatNumber(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static void dotText(int color, String text) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        ImGui.getWindowDrawList().addCircleFilled(x + 4, HudWidgets.textMiddle(y), 4, color, 12);
        ImGui.setCursorScreenPos(x + 13, y);
        ImGui.textUnformatted(text);
    }

    private static void centeredText(String text) {
        var width = ImGui.getContentRegionAvailX();
        ImGui.setCursorPosX(ImGui.getCursorPosX() + Math.max(0, (width - ImGui.calcTextSizeX(text)) * .5f));
        ImGui.textUnformatted(text);
    }

    private static float spacing() {
        return ImGui.getStyle().getItemSpacingY();
    }

    private static void space(float pixels) {
        GeodataWidgets.space(pixels);
    }
}
