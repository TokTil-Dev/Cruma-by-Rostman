package com.rost.cruma.viewer.editing;

import static com.rost.cruma.viewer.editing.EditingPanel.COMMAND_KEY;
import static com.rost.cruma.viewer.editing.EditorUi.tip;

import com.rost.cruma.core.editing.BeastTerrainRebake.Calibrated;
import com.rost.cruma.core.editing.TerrainBrushStamp;
import com.rost.cruma.core.editing.TerrainBrushes;
import com.rost.cruma.core.editing.TerrainBrushes.Falloff;
import com.rost.cruma.core.editing.TerrainBrushes.RampPoint;
import com.rost.cruma.core.editing.TerrainBrushes.Shape;
import com.rost.cruma.core.editing.TerrainDecorationSources.Decoration;
import com.rost.cruma.core.editing.TerrainHeightField;
import com.rost.cruma.core.editing.TerrainHeightField.Brush;
import com.rost.cruma.core.editing.TerrainLayerMapping;
import com.rost.cruma.core.editing.TerrainNativeCodec.Info;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import com.rost.cruma.core.editing.TerrainPaintHistory;
import com.rost.cruma.core.editing.TerrainPaintHistory.Layer;
import com.rost.cruma.core.editing.TerrainQuadHistory;
import com.rost.cruma.core.editing.TerrainSoftSelection;
import com.rost.cruma.core.editing.TerrainTileLink;
import com.rost.cruma.viewer.editing.EditorUi.Tone;
import com.rost.cruma.viewer.editing.OpenGround.Part;
import com.rost.cruma.viewer.editing.TerrainEditingController.Capability;
import com.rost.cruma.viewer.editing.TerrainEditingController.Tool;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.api.DebugSceneObject.Category;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import com.rost.cruma.viewer.ui.GeodataWidgets;
import com.rost.cruma.viewer.ui.HudWidgets;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.Motion;
import glm_.vec3.Vec3;
import imgui.ImColor;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiSelectableFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImFloat;
import imgui.type.ImString;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

final class TerrainCapabilityPanel {
    private static final float MAX_FRAME_SECONDS = .1f;
    private static final int CIRCLE_POINTS = 48;
    private static final double INNER_WEIGHT = .75;
    private static final float PRESET_HEIGHT = 18;
    private static final String CLOSE_POPUP = "Close Terrain";
    private static final String MORE_MENU = "##terrainMore";
    private static final String LIGHTING_POPUP = "##terrainLighting";
    private static final float LABEL_WIDTH = 64;
    private static final float LAYER_SWATCH = 40;
    private static final int WEIGHT_CELL = 2;
    private final EditingSession session;
    private final ImString mapName = new ImString(128);
    private final ImString mapFilter = new ImString(64);
    private CompletableFuture<Result> pending;
    private Result result;
    private TerrainViewportSession viewport;
    private final ImFloat radius = new ImFloat(4);
    private final Map<String, float[]> presetAreas = new HashMap<>();
    private final ImFloat speed = new ImFloat(400);
    private final ImFloat strength = new ImFloat(.5f);
    private Tool tool = Tool.SCULPT;
    private SculptBrush sculptBrush = SculptBrush.RAISE;
    private Falloff falloff = Falloff.SMOOTH;
    private FlattenTarget target = FlattenTarget.DRAG_START;
    private final ImFloat targetHeight = new ImFloat(0);
    private final ImFloat rampWidth = new ImFloat(512);
    private final ImFloat noiseAmplitude = new ImFloat(64);
    private final ImFloat noiseScale = new ImFloat(512);
    private @Nullable RampPoint rampStart;
    private @Nullable String rampOwner;
    private @Nullable Shape strokeShape;
    private long noiseSeeds = 1;
    private boolean showWeights;
    private final ImFloat slopeDegrees = new ImFloat(35);
    private final ImFloat heightFrom = new ImFloat(0);
    private final ImFloat heightTo = new ImFloat(500);
    private @Nullable MeshLibrary library;
    private @Nullable Host host;
    private TerrainPaintHistory.Brush paintBrush = TerrainPaintHistory.Brush.PAINT;
    private int paintLayer = -1;
    private PaintTarget paintTarget = PaintTarget.LAYERS;
    private int paintDecoration = -1;
    private boolean coverAbove = true;
    private boolean dragging, finishRequested, cancelRequested, otherMap;
    private final TerrainSelectTool select = new TerrainSelectTool();
    private SelectDrag selectDrag = SelectDrag.NONE;
    private float pendingMove = Float.NaN;
    private float moveStartY;
    private float unitsPerPixel;
    private float @Nullable [] moveWeights;
    private String openOnInspect = "";
    private String strokeLabel = "";
    private @Nullable String strokeOwner;
    private final TerrainNeighbors neighbors = new TerrainNeighbors();
    private String besideMap = "";
    private String besideFrom = "";
    private @Nullable CompletableFuture<Result> besideRead;
    private @Nullable CompletableFuture<String> besideOpen;
    private boolean closeRequested;
    private boolean closeAll;
    private boolean moreRequested;
    private @Nullable String pendingPopup;
    private float popupRight, popupBottom;
    private boolean chooserOpen;
    private boolean mappingOpen, fillOpen;
    private float lastX, lastY;
    private final List<TerrainBrushStamp> queued = new ArrayList<>();
    private boolean queuedInverse;
    private final Map<String, Ground> grounds = new HashMap<>();
    private String feedback = "";
    private String cameraMap = "";
    private Estimate estimate;
    private @Nullable List<float[]> sectorRects;
    private long sectorKey = -1;
    private List<TerrainChange> terrainRows = List.of();
    private long rowsKey = -1;
    private final ImFloat scaleU = new ImFloat(1);
    private final ImFloat scaleV = new ImFloat(1);
    private final ImFloat panU = new ImFloat(0);
    private final ImFloat panV = new ImFloat(0);
    private final ImFloat rotation = new ImFloat(0);
    private int mappingLayer = -1;
    private long mappingRevision = -1;
    private String mappingOwner = "";
    private String paintOwner = "";
    private final Map<String, Integer> paintLayers = new HashMap<>();
    private Calibrated beastChecked;
    private boolean beastFlat;

    TerrainCapabilityPanel(EditingSession session) {
        this.session = session;
    }

    interface Host {
        @Nullable
        Float getSelectionBottom();

        void drawFloatingMeshes();

        void showNote(String text);
    }

    enum SculptBrush {
        RAISE(
                "Raise",
                Brush.RAISE,
                "Raise ground",
                Glyph.TERRAIN,
                "Shift lowers",
                "Shift+drag lowers",
                "Lifts the ground under the brush while you hold the mouse button."),
        FLATTEN(
                "Flatten",
                Brush.FLATTEN,
                "Flatten ground",
                Glyph.EQUAL,
                "Alt+click picks the height",
                "Alt+click picks a height",
                "Pulls the ground to its target height."),
        SMOOTH(
                "Smooth",
                Brush.SMOOTH,
                "Smooth ground",
                Glyph.WAVES,
                "softens bumps",
                "",
                "Softens the bumps under the brush."),
        RAMP(
                "Ramp",
                null,
                "Ramp ground",
                Glyph.SLOPE,
                "a slope between two clicks",
                "Click, then click",
                "Lays a straight slope from the first click on the ground to the second."),
        NOISE(
                "Noise",
                Brush.NOISE,
                "Add noise to ground",
                Glyph.SHUFFLE,
                "Shift turns the bumps over",
                "Shift+drag turns the bumps over",
                "Roughens the ground with random bumps."),
        SET_HEIGHT(
                "Set height",
                Brush.SET_HEIGHT,
                "Set ground height",
                Glyph.LEVEL,
                "the whole circle at once",
                "Alt+click picks a height",
                "Puts the ground under the whole circle at the target height at once."),
        SELECT(
                "Select",
                null,
                "Move selection",
                Glyph.LASSO,
                "Shift deselects · Alt moves",
                "Shift+drag deselects · Alt+drag moves",
                "Marks ground with a soft edge, to move it up or down. It does not sculpt."),
        HOLES(
                "Holes",
                null,
                "Hide ground",
                Glyph.CIRCLE_OFF,
                "Shift shows cells again",
                "Shift+drag shows again",
                "Hides the cells under the brush: the game does not draw them, and everyone passes through them."),
        EDGES(
                "Edges",
                null,
                "Turn edges",
                Glyph.TRIANGLE,
                "Shift keeps the saved diagonal",
                "Shift+drag as saved",
                "Turns the diagonal of each cell under the brush, which decides its two triangles, its light and "
                        + "its collision.");

        final String label;
        final @Nullable Brush brush;
        final String step;
        final Glyph glyph;
        final String note;
        final String keys;
        final String about;

        SculptBrush(
                String label, @Nullable Brush brush, String step, Glyph glyph, String note, String keys, String about) {
            this.label = label;
            this.brush = brush;
            this.step = step;
            this.glyph = glyph;
            this.note = note;
            this.keys = keys;
            this.about = about;
        }

        String getTip() {
            return label + (keys.isEmpty() ? "" : "  ·  " + keys) + "\n" + about;
        }
    }

    private enum SelectDrag {
        NONE,
        ADD,
        REMOVE,
        MOVE
    }

    enum FlattenTarget {
        DRAG_START("Where the drag starts"),
        HEIGHT("Height"),
        PICKED("Picked"),
        SELECTION("Under the selection");

        final String label;

        FlattenTarget(String label) {
            this.label = label;
        }
    }

    record TerrainChange(String title, String detail, float[] rect, int layer, ChangeKind kind, String owner) {}

    enum ChangeKind {
        HEIGHTS,
        WEIGHTS,
        DECORATIONS,
        MAPPING,
        QUADS
    }

    private record Result(String map, List<Capability> owners, String error) {}

    private record Estimate(String owner, long revision, int samples, int sectors, int bspSurfaces, int meshActors) {}

    private record Ground(short[] samples, TerrainHeightField field) {}

    private record Aim(TerrainDocument document, TerrainHeightField ground, Vector hit, double distance) {}

    static float innerShare(Falloff falloff) {
        var low = 0d;
        var high = 1d;
        for (var step = 0; step < 40; step++) {
            var middle = (low + high) * .5;
            if (falloff.getWeight(middle) >= INNER_WEIGHT) {
                high = middle;
            } else {
                low = middle;
            }
        }
        return (float) (1 - high);
    }

    static boolean showsFalloff(Tool tool, SculptBrush brush) {
        return tool == Tool.SCULPT
                && (brush == SculptBrush.RAISE
                        || brush == SculptBrush.FLATTEN
                        || brush == SculptBrush.SMOOTH
                        || brush == SculptBrush.NOISE);
    }

    static float @Nullable [] samplesOf(Info from, Info to, float x, float y) {
        if (from == to) {
            return new float[] {x, y};
        }
        var link = TerrainTileLink.of(from, to);
        return link.fits() ? new float[] {link.sampleX(x), link.sampleY(y)} : null;
    }

    static String besideNote(String map, String why) {
        var reason = why.strip();
        if (!reason.isEmpty() && !reason.endsWith(".")) {
            reason += ".";
        }
        return "The Terrain of "
                + map
                + " does not open by itself, so the brush stops at the border."
                + (reason.isEmpty() ? "" : " " + reason);
    }

    static String pickedNote(float height) {
        return String.format(Locale.ROOT, "Height %,.0f units picked · Flatten and Set height use it", height);
    }

    static float oneDecimal(float value) {
        return Math.round(value * 10) / 10f;
    }

    static Result inspect(Path root, EditingMaps mapFiles, String name) {
        if (!name.matches("[A-Za-z0-9_-]+")) {
            return new Result(name, List.of(), "Enter a map name without a folder or extension.");
        }
        var source = mapFiles.getSource(name);
        try (var files = Files.list(root.resolve("Maps"));
                var controller = new TerrainEditingController(root)) {
            var matches = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(source + ".unr"))
                    .toList();
            if (matches.size() != 1) {
                return new Result(name, List.of(), "The source map is missing or ambiguous: " + name);
            }
            return new Result(name, controller.inspect(matches.getFirst()), "");
        } catch (IOException | RuntimeException failure) {
            return new Result(name, List.of(), "Terrain editing unavailable: " + failure.getMessage());
        }
    }

    void setViewport(TerrainViewportSession value) {
        viewport = value;
    }

    void setHost(Host value) {
        host = value;
    }

    void setLibrary(@Nullable MeshLibrary value) {
        library = value;
    }

    void updateProbe(GroundProbe probe) {
        if (viewport != null) {
            viewport.updateProbe(probe);
        } else {
            probe.setOpen(null, null, null, null);
        }
    }

    boolean isPaintingDecorations() {
        return tool == Tool.PAINT && paintTarget == PaintTarget.DECORATIONS;
    }

    boolean isRamp() {
        return tool == Tool.SCULPT && sculptBrush == SculptBrush.RAMP;
    }

    boolean isRampStarted() {
        return rampStart != null;
    }

    boolean usesSpeed() {
        return tool == Tool.SCULPT && sculptBrush == SculptBrush.RAISE;
    }

    String @Nullable [][] getBrushHints() {
        if (tool != Tool.SCULPT) {
            return null;
        }
        return switch (sculptBrush) {
            case SELECT ->
                new String[][] {
                    {"Drag", "select"},
                    {"Shift+Drag", "deselect"},
                    {"Alt+Drag", "move up or down"},
                    {"[ ]", "size"},
                    {"Esc", "select tool"}
                };
            case HOLES ->
                new String[][] {{"Drag", "hide"}, {"Shift+Drag", "show"}, {"[ ]", "size"}, {"Esc", "select tool"}};
            case EDGES ->
                new String[][] {
                    {"Drag", "turn edges"}, {"Shift+Drag", "as saved"}, {"[ ]", "size"}, {"Esc", "select tool"}
                };
            default -> null;
        };
    }

    List<float[]> getChangedSectors() {
        if (!isAnyOpen()) {
            return List.of();
        }
        var controller = viewport.getController();
        var key = heightKey(controller);
        if (hasHeightStroke(controller) || sectorRects != null && sectorKey == key) {
            return sectorRects == null ? List.of() : sectorRects;
        }
        var rects = new ArrayList<float[]>();
        for (var document : controller.getDocuments()) {
            rects.addAll(changedSectors(document));
        }
        sectorRects = List.copyOf(rects);
        sectorKey = key;
        return sectorRects;
    }

    List<TerrainChange> getTerrainChanges() {
        if (!isAnyOpen()) {
            return List.of();
        }
        var controller = viewport.getController();
        if (controller.isStrokeActive()) {
            return terrainRows;
        }
        var key = 17L;
        for (var document : controller.getDocuments()) {
            var paint = document.getPaint();
            var layers = document.getMapping();
            var bits = document.getQuads();
            var density = document.getDecorations();
            key = key * 31 + System.identityHashCode(document.getHistory());
            key = key * 31 + (density == null ? -1 : density.getRevision());
            key = key * 31 + document.getHistory().getRevision();
            key = key * 31 + (paint == null ? -1 : paint.getRevision());
            key = key * 31 + (layers == null ? -1 : layers.getRevision());
            key = key * 31 + (bits == null ? -1 : bits.getRevision());
        }
        if (key == rowsKey) {
            return terrainRows;
        }
        var rows = new ArrayList<TerrainChange>();
        for (var document : controller.getDocuments()) {
            rows.addAll(terrainChanges(document));
        }
        terrainRows = List.copyOf(rows);
        rowsKey = key;
        return terrainRows;
    }

    void revert(TerrainChange change) {
        if (!isEditorOpen() || viewport.getController().findDocument(change.owner()) == null) {
            return;
        }
        var owner = change.owner();
        switch (change.kind()) {
            case HEIGHTS -> run(() -> viewport.revertHeights(owner, "Revert " + change.title()));
            case WEIGHTS -> run(() -> viewport.revertLayer(owner, change.layer(), "Revert " + change.title()));
            case MAPPING ->
                run(() -> viewport.revertLayerMapping(owner, change.layer(), "Revert mapping of " + change.title()));
            case QUADS -> run(() -> viewport.revertQuads(owner, "Revert " + change.title()));
            case DECORATIONS -> run(() -> viewport.revertDecoration(owner, change.layer(), "Revert " + change.title()));
        }
    }

    boolean canRevert() {
        return isEditorOpen() && !viewport.isBusy() && !viewport.getController().isStrokeActive() && !dragging;
    }

    int getChangedSamples() {
        if (!isAnyOpen()) {
            return 0;
        }
        var count = 0;
        for (var document : viewport.getController().getDocuments()) {
            count += document.getHistory().getSourceChanges().size();
        }
        return count;
    }

    long getHeightRevision() {
        return isAnyOpen() ? heightKey(viewport.getController()) : -1;
    }

    String getExportSummary() {
        if (!isAnyOpen()) {
            return "";
        }
        var parts = new ArrayList<String>();
        for (var document : viewport.getController().getDocuments()) {
            if (!document.isDirty() && !document.hasSourceChanges()) {
                continue;
            }
            var height = document.getHeightPackage();
            var paint = document.getPaint();
            var painted = paint != null && paint.hasSourceChanges()
                    || document.getMapping() != null && document.getMapping().hasSourceChanges()
                    || document.getQuads() != null && document.getQuads().hasSourceChanges();
            var heights = document.getHistory().getSourceChanges().size() != 0;
            var decorations = document.getDecorations() != null
                    && document.getDecorations().hasSourceChanges();
            if (!heights && !painted && decorations) {
                parts.add(document.getOwner() + ": painted decorations");
                continue;
            }
            parts.add(document.getOwner()
                    + ": "
                    + (heights && painted
                            ? "heights, layers and lighting"
                            : painted ? "changed layers" : "heights and lighting")
                    + (decorations ? ", painted decorations" : "")
                    + (height.isEmpty() ? "" : ", " + height));
        }
        return String.join("; ", parts);
    }

    boolean isStroking() {
        return dragging;
    }

    boolean cancelStroke() {
        if (rampStart != null) {
            rampStart = null;
            rampOwner = null;
            return true;
        }
        if (!dragging) {
            return false;
        }
        cancelRequested = true;
        dragging = false;
        selectDrag = SelectDrag.NONE;
        return true;
    }

    boolean isTerrainDirty() {
        return viewport != null && viewport.getController().isDirty();
    }

    List<String> getDirtyOwners() {
        if (viewport == null) {
            return List.of();
        }
        var owners = new ArrayList<String>();
        for (var document : viewport.getController().getDocuments()) {
            if (document.isDirty()) {
                owners.add(document.getOwner());
            }
        }
        return owners;
    }

    List<String> getChangedMaps() {
        if (!isAnyOpen()) {
            return List.of();
        }
        var maps = new ArrayList<String>();
        for (var document : viewport.getController().getDocuments()) {
            if (document.getHistory().getSourceChanges().size() > 0) {
                maps.add(document.getMap());
            }
        }
        return maps;
    }

    String getOpenOwner() {
        return isEditorOpen() ? viewport.getController().getSelectedOwner() : "";
    }

    boolean isEditorOpen() {
        return viewport != null
                && !viewport.isOpening()
                && viewport.getController().isOpen();
    }

    void draw(Tool active) {
        if (!dragging) {
            tool = active;
        }
        pollInspect();
        if (tool == Tool.PAINT && library != null && (isEditorOpen() || viewport != null && viewport.isOpening())) {
            library.startMaterials();
        }
        if (viewport != null && viewport.isOpening()) {
            var status = viewport.getStatus().isEmpty() ? "Preparing the areas of the Terrain" : viewport.getStatus();
            GeodataWidgets.note(withMovingDots(status), GeodataWidgets.ACCENT);
        }
        if (viewport != null) {
            EditorUi.message(Tone.ERROR, viewport.getError());
        }
        EditorUi.message(Tone.ERROR, feedback);
        if (isEditorOpen()) {
            drawControls();
            return;
        }
        if (viewport != null && viewport.isOpening() && viewport.getController().isOpen()) {
            return;
        }
        EditorUi.firstSection("Terrain");
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/terrain");
        drawCameraTerrain();
        drawMapChooser();
    }

    @Nullable
    Capability getCameraTerrain() {
        if (result == null
                || cameraMap.isEmpty()
                || !result.map().equals(cameraMap)
                || !result.error().isEmpty()) {
            return null;
        }
        var editable = result.owners().stream()
                .filter(owner -> owner.heightsDecoded() && owner.isNativeExportSupported())
                .toList();
        return editable.size() == 1 ? editable.getFirst() : null;
    }

    void drawClosePopup() {
        if (closeRequested) {
            closeRequested = false;
            ImGui.openPopup(CLOSE_POPUP);
        }
        var io = ImGui.getIO();
        ImGui.setNextWindowPos(io.getDisplaySizeX() / 2, io.getDisplaySizeY() / 2, ImGuiCond.Always, .5f, .5f);
        ImGui.setNextWindowSize(Math.max(280, Math.min(420, io.getDisplaySizeX() - 32)), 0);
        if (!ImGui.beginPopupModal(
                CLOSE_POPUP,
                ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.AlwaysAutoResize)) {
            return;
        }
        Motion.dialog();
        if (!isEditorOpen()) {
            ImGui.closeCurrentPopup();
            ImGui.endPopup();
            return;
        }
        var owners = closeAll ? getDirtyOwners() : List.of(getOpenOwner());
        EditorUi.message(Tone.WARNING, "The strokes on " + joined(owners) + " are not saved. Closing drops them.");
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var half = GeodataWidgets.columnWidth(2);
        if (EditorUi.dangerButton("Close##terrainCloseConfirm", half)) {
            close();
            ImGui.closeCurrentPopup();
        }
        EditorUi.tip(
                closeAll
                        ? "Close every open Terrain and drop the strokes that are not saved. Saved folders stay."
                        : "Close the Terrain and drop its strokes that are not saved. Saved folders stay.");
        ImGui.sameLine();
        if (HudWidgets.button("terrainKeepEditing", null, "Keep editing", half, EditorUi.BUTTON_HEIGHT, true, true)
                || ImGui.isKeyPressed(ImGuiKey.Escape)
                || GeodataWidgets.wasClickedOutside()) {
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
    }

    String getCameraMap() {
        return cameraMap;
    }

    boolean drawViewport(
            Camera camera,
            ViewerRenderBackend backend,
            float left,
            float top,
            float right,
            float bottom,
            @Nullable Tool active) {
        var position = camera.getPosition();
        cameraMap = session.getMapAt(position.getX(), position.getY());
        pollInspect();
        pollBeside();
        if (viewport == null || viewport.getController().isExportLocked()) {
            return false;
        }
        if (active != null && !dragging) {
            tool = active;
        }
        var controller = viewport.getController();
        if (active != Tool.SCULPT
                || sculptBrush != SculptBrush.RAMP
                || rampOwner != null && controller.findDocument(rampOwner) == null) {
            rampStart = null;
            rampOwner = null;
        }
        if (!isEditorOpen()) {
            dragging = false;
            queued.clear();
            return active != null && openUnderPointer(camera, backend, left, top, right, bottom);
        }
        if (!viewport.isBusy()) {
            if (!queued.isEmpty() && !cancelRequested) {
                flushStamps();
            } else if (!Float.isNaN(pendingMove) && !cancelRequested && moveWeights != null) {
                var weights = moveWeights;
                var raw = pendingMove;
                var owner = strokeOwner;
                pendingMove = Float.NaN;
                run(() -> viewport.moveSelection(owner, weights, raw, ""));
            } else if (cancelRequested) {
                queued.clear();
                pendingMove = Float.NaN;
                cancelRequested = false;
                finishRequested = false;
                run(viewport::cancelStroke);
            } else if (finishRequested) {
                finishRequested = false;
                var label = strokeLabel;
                run(() -> viewport.finishStroke(label));
            }
        }
        if (active == null && !dragging) {
            return false;
        }
        var io = ImGui.getIO();
        var typing = io.getWantTextInput();
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false) && !typing) {
            cancelStroke();
        }
        if (dragging && active == null) {
            cancelStroke();
        }
        if (dragging && !ImGui.isMouseDown(0)) {
            finishRequested = true;
            dragging = false;
            selectDrag = SelectDrag.NONE;
        }
        var command = io.getKeyCtrl() || io.getKeySuper();
        if (!typing && !command) {
            var up = ImGui.isKeyPressed(ImGuiKey.RightBracket, true);
            var down = ImGui.isKeyPressed(ImGuiKey.LeftBracket, true);
            if (up || down) {
                if (io.getKeyShift()) {
                    changeStrength(up);
                } else {
                    changeSize(up);
                }
            }
        }
        if (ImGui.isMouseDown(1) && !dragging) {
            return false;
        }
        var x = ImGui.getMousePosX();
        var y = ImGui.getMousePosY();
        var inside = x >= left && x <= right && y >= top && y <= bottom;
        if (!inside || io.getWantCaptureMouse() && !dragging || ImGui.isMouseDown(1)) {
            return true;
        }
        var wheel = io.getMouseWheel() != 0 ? io.getMouseWheel() : io.getMouseWheelH();
        if (wheel != 0 && command) {
            changeSize(wheel > 0);
        } else if (wheel != 0 && io.getKeyShift()) {
            changeStrength(wheel > 0);
        }
        var width = io.getDisplaySizeX();
        var height = io.getDisplaySizeY();
        var nx = x / width * 2 - 1;
        var ny = 1 - y / height * 2;
        var origin = camera.getCursorRayOrigin(nx, ny);
        var direction = camera.getCursorRayDirection(nx, ny);
        var crossing = crossesBorders();
        var held = isTargetHeld(typing);
        Aim aim;
        if (held) {
            var anchor = strokeOwner == null ? null : controller.findDocument(strokeOwner);
            var document = anchor != null ? anchor : controller.getActive();
            if (document == null || !viewport.isResident(document.getOwner())) {
                return true;
            }
            aim = crossing ? nearestAim(controller, origin, direction, document) : null;
            if (aim == null && crossing) {
                aim = seamAim(controller, backend, origin, direction, nx, ny, document);
            }
            if (aim == null) {
                var ground = ground(document);
                if (ground == null) {
                    return true;
                }
                aim = new Aim(
                        document,
                        ground,
                        crossing ? null : intersect(document.getInfo(), ground, origin, direction),
                        0);
            }
        } else {
            var start = rampStart == null || rampOwner == null ? null : controller.findDocument(rampOwner);
            aim = nearestAim(controller, origin, direction, start);
            if (aim == null && crossing) {
                aim = seamAim(controller, backend, origin, direction, nx, ny, start);
            }
            if (aim == null) {
                if (active != null && offerOther(camera, backend, nx, ny)) {
                    return true;
                }
                var current = controller.getActive();
                var ground = current == null || !viewport.isResident(current.getOwner()) ? null : ground(current);
                if (ground == null) {
                    return true;
                }
                aim = new Aim(current, ground, null, 0);
            } else if (aim.document() != controller.getActive()) {
                viewport.activate(aim.document().getOwner());
            }
        }
        if (tool == Tool.PAINT) {
            followPaintLayer(controller);
        }
        var info = aim.document().getInfo();
        var ground = aim.ground();
        var open = openGround(controller, aim.document(), ground);
        if (!held && crossing && aim.hit() != null && !dragging) {
            openBesideNear(controller, aim.document(), aim.hit());
        }
        var projection = ScreenProjection.of(camera, width, height);
        var background = ImGui.getBackgroundDrawList();
        background.pushClipRect(left, top, right, bottom, true);
        try {
            if (tool == Tool.PAINT && showWeights) {
                drawWeights(projection, info, ground, controller);
            }
            var hit = aim.hit();
            if (tool == Tool.SCULPT && sculptBrush == SculptBrush.SELECT) {
                return drawSelectTool(projection, open, hit);
            }
            if (hit == null) {
                var start = rampOwner == null ? null : controller.findDocument(rampOwner);
                var startGround = start == null ? null : ground(start);
                if (rampStart != null && startGround != null) {
                    drawRamp(projection, openGround(controller, start, startGround), rampStart, null);
                }
                return true;
            }
            var inverse = io.getKeyShift();
            var paintable = tool == Tool.SCULPT && (!isQuadBrush() || controller.getQuads() != null)
                    || tool == Tool.PAINT && isPaintReady(controller);
            var picking = io.getKeyAlt() && !dragging && !isQuadBrush();
            var color = !paintable ? EditorUi.WARNING : HudWidgets.ACCENT;
            if (tool == Tool.SCULPT && sculptBrush == SculptBrush.RAMP) {
                return drawRampTool(projection, open, aim.document(), hit, picking);
            }
            if (isQuadBrush() && controller.getQuads() != null) {
                drawQuadMarks(projection, info, ground, controller.getQuads(), hit);
            }
            if (showsFalloff(tool, sculptBrush)) {
                drawCircle(
                        projection,
                        open,
                        hit.x(),
                        hit.y(),
                        radius.get() * innerShare(falloff),
                        EditorUi.fill(color, .6f),
                        1.5f,
                        false);
            }
            var ring = drawOutline(projection, open, hit.x(), hit.y(), color);
            drawReadout(ring, inverse);
            if (picking) {
                HudWidgets.showTooltip(
                        tool == Tool.PAINT
                                ? "Click to choose the layer that shows most here"
                                : "Click to pick this height, "
                                        + Math.round(open.getWorldAt(hit.x(), hit.y())
                                                .z()) + " units");
                if (ImGui.isMouseClicked(0) && !io.getWantCaptureMouse()) {
                    pick(controller, open, hit.x(), hit.y());
                }
                return true;
            }
            if (!dragging && !paintable) {
                HudWidgets.showTooltip(tooltip(controller));
            }
            if (ImGui.isMouseClicked(0) && !dragging && paintable && !io.getWantCaptureMouse()) {
                dragging = true;
                strokeOwner = aim.document().getOwner();
                strokeLabel = strokeName(inverse, controller);
                lastX = hit.x();
                lastY = hit.y();
                strokeShape = startShape(open, hit);
                queue(hit.x(), hit.y(), 1 / 60f, inverse);
            } else if (dragging && ImGui.isMouseDown(0)) {
                var anchor = strokeOwner == null ? null : controller.findDocument(strokeOwner);
                var point =
                        anchor == null ? null : samplesOf(aim.document().getInfo(), anchor.getInfo(), hit.x(), hit.y());
                if (point == null) {
                    return true;
                }
                var distance = (float) Math.hypot(point[0] - lastX, point[1] - lastY);
                var steps = Math.max(1, (int) Math.ceil(distance / Math.max(.25f, radius.get() * .25f)));
                var seconds = Math.min(MAX_FRAME_SECONDS, io.getDeltaTime());
                for (var step = 1; step <= steps; step++) {
                    var t = step / (float) steps;
                    queue(lastX + (point[0] - lastX) * t, lastY + (point[1] - lastY) * t, seconds / steps, inverse);
                }
                lastX = point[0];
                lastY = point[1];
            }
            return true;
        } finally {
            background.popClipRect();
        }
    }

    private boolean isAnyOpen() {
        return viewport != null && viewport.getController().isOpen();
    }

    private void drawCameraTerrain() {
        var owner = getCameraTerrain();
        if (owner == null) {
            return;
        }
        ImGui.beginDisabled(viewport == null || viewport.isBusy());
        if (GeodataWidgets.actionButton(
                "terrainOpenCamera", Glyph.PENCIL, "Edit the Terrain of " + cameraMap, -1, true)) {
            openOwner(cameraMap, owner.owner());
        }
        ImGui.endDisabled();
        tip("Open the Terrain of the map under the camera to sculpt its heights and paint its layers. The chooser"
                + " below opens the Terrain of another map. ("
                + owner.owner() + ")");
        GeodataWidgets.space(GeodataWidgets.BLOCK);
    }

    private void drawMapChooser() {
        var loaded = new TreeSet<>(session.getOpenedMaps());
        if (!otherMap && (mapName.get().isBlank() || !loaded.contains(mapName.get()) && !loaded.isEmpty())) {
            mapName.set(loaded.contains(cameraMap) ? cameraMap : loaded.isEmpty() ? "" : loaded.first());
        }
        var read = false;
        if (!otherMap && !loaded.isEmpty()) {
            var filter = GeodataWidgets.searchList(
                    "##terrainCapabilityMapChoice",
                    mapName.get().isBlank() ? "Choose a map" : label(mapName.get()),
                    mapFilter,
                    "Filter maps",
                    -1);
            if (filter != null) {
                for (var name : loaded) {
                    if (name.contains(filter)
                            && GeodataWidgets.listItem(
                                    name, name, name.equals(cameraMap) ? "camera" : "", name.equals(mapName.get()))) {
                        mapName.set(name);
                    }
                }
                if (GeodataWidgets.listItem("another", "Another map", "type its name", false)) {
                    otherMap = true;
                    mapName.set("");
                }
                ImGui.endCombo();
            }
            tip("Maps loaded in the viewport. The map under the camera is chosen first. Choose Another map to type"
                    + " a name.");
        } else {
            var field = GeodataWidgets.actionField(
                    "terrainCapabilityMap",
                    "Map name, for example 22_22",
                    mapName,
                    "The map tile name, without folder or extension.",
                    Glyph.SEARCH,
                    "Read this map",
                    pending == null && !mapName.get().isBlank());
            read = field.action();
            if (!loaded.isEmpty()) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                if (GeodataWidgets.actionButton("terrainLoadedMaps", null, "Choose a loaded map instead", -1, false)) {
                    otherMap = false;
                    mapName.set("");
                }
            }
        }
        var name = mapName.get().trim();
        if (!otherMap
                && pending == null
                && loaded.contains(name)
                && (result == null || !result.map().equals(name))) {
            startInspect(name);
        }
        if (read && pending == null && !name.isBlank()) {
            startInspect(name);
        }
        if (pending != null) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.note(withMovingDots("Reading Terrain"), GeodataWidgets.ACCENT);
        }
        if (result == null || !result.map().equals(name)) {
            return;
        }
        if (!result.error().isEmpty()) {
            EditorUi.message(Tone.ERROR, result.error());
            return;
        }
        if (result.owners().isEmpty()) {
            EditorUi.message(Tone.INFO, "This map has no Terrain.");
            return;
        }
        for (var owner : result.owners()) {
            GeodataWidgets.space(GeodataWidgets.BLOCK);
            drawOwner(owner, name);
        }
    }

    private void drawOwner(Capability owner, String map) {
        var editable = owner.heightsDecoded() && owner.isNativeExportSupported();
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
        EditorUi.colored(HudWidgets.TEXT, HudWidgets.truncateToWidth(EditingSession.shortName(owner.owner()), inner));
        var heights =
                owner.heightPackage().isEmpty() ? "heights inside the map" : "heights in " + owner.heightPackage();
        var about =
                owner.owner() + (owner.lit() ? ". Save rebuilds its baked lighting." : ". It has no baked lighting.");
        tip(about);
        var line = "Terrain of " + map + (editable ? " · editable · " + heights : " · read-only");
        EditorUi.colored(HudWidgets.MUTED, HudWidgets.truncateToWidth(line, inner));
        tip(about);
        if (!editable) {
            GeodataWidgets.note(owner.exportRestriction(), HudWidgets.MUTED, inner);
        } else {
            var open = isEditorOpen()
                    && owner.owner().equals(viewport.getController().getSelectedOwner());
            ImGui.beginDisabled(viewport == null || viewport.isBusy() || open);
            GeodataWidgets.space(GeodataWidgets.ROW);
            if (GeodataWidgets.actionButton(
                    "terrainOpen" + owner.owner(), Glyph.PENCIL, open ? "Open" : "Edit Terrain", inner, true)) {
                openOwner(map, owner.owner());
            }
            tip("Open this Terrain to sculpt its heights and paint its layers. Save takes its changes together"
                    + " with the actor changes.");
            ImGui.endDisabled();
        }
        var bottom = ImGui.getItemRectMaxY() + pad;
        ImGui.unindent(pad);
        draw.channelsSetCurrent(0);
        draw.addRectFilled(x, y, x + width, bottom, GeodataWidgets.CARD, 8f);
        draw.channelsMerge();
        ImGui.setCursorScreenPos(x, bottom);
        ImGui.dummy(width, 0);
    }

    private void openOwner(String map, String owner) {
        try {
            viewport.open(findMap(session.getClientRoot(), session.getMapFiles(), map), owner);
            estimate = null;
            feedback = "";
            chooserOpen = false;
        } catch (IOException | RuntimeException failure) {
            feedback = UserMessages.plain(failure.getMessage());
        }
    }

    private void pollInspect() {
        if (pending == null || !pending.isDone()) {
            return;
        }
        result = pending.join();
        pending = null;
        if (!result.map().equals(openOnInspect)) {
            return;
        }
        openOnInspect = "";
        var editable = result.owners().stream()
                .filter(owner -> owner.heightsDecoded() && owner.isNativeExportSupported())
                .toList();
        if (result.error().isEmpty() && editable.size() == 1 && viewport != null && !viewport.isBusy()) {
            openOwner(result.map(), editable.getFirst().owner());
        } else if (isEditorOpen()) {
            chooserOpen = true;
        }
    }

    private void drawControls() {
        var controller = viewport.getController();
        var dirty = controller.getActive().isDirty();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var button = HudWidgets.CONTROL_HEIGHT;
        var room = width - button - GeodataWidgets.ROW;
        var owner = controller.getSelectedOwner();
        var draw = ImGui.getWindowDrawList();
        ImGui.dummy(Math.max(1, room), button);
        draw.addText(
                x,
                y + (button - ImGui.getFontSize()) * .5f - 1,
                HudWidgets.TEXT,
                HudWidgets.truncateToWidth("Terrain · " + EditingSession.mapOfPath(owner), room));
        var height = controller.getHeightPackage();
        tip("The Terrain of "
                + EditingSession.mapOfPath(owner)
                + ". Its heights are kept "
                + (height.isEmpty() ? "inside the map" : "in " + height)
                + ". ("
                + owner
                + ")");
        ImGui.setCursorScreenPos(x + width - button, y);
        if (HudWidgets.iconButton("terrainMore", Glyph.MORE, ImGui.isPopupOpen(MORE_MENU), HudWidgets.TEXT)
                || moreRequested) {
            moreRequested = false;
            ImGui.openPopup(MORE_MENU);
        }
        tip("More: the lighting, another Terrain, and Close Terrain.");
        drawMoreMenu(controller, dirty);
        ImGui.setCursorScreenPos(x, y + button);
        ImGui.dummy(0, 0);
        drawOpenTerrains(controller);
        for (var note : neighbors.getNotes(controller.getDocuments(), session.getOpenedMaps())) {
            GeodataWidgets.note(note, HudWidgets.MUTED);
            GeodataWidgets.space(GeodataWidgets.ROW);
        }
        EditorUi.colored(dirty ? EditorUi.WARNING : HudWidgets.MUTED, dirty ? "Changes not saved" : "Changes: none");
        tip(
                controller.getDocuments().size() > 1
                        ? "The changes of the Terrain of " + EditingSession.mapOfPath(owner)
                                + ". Save takes every open Terrain together with the actor changes."
                        : "Save takes the Terrain together with the actor changes.");
        drawLightingPopup();
        var facts = controller.getLighting();
        if (facts != null && !facts.error().isEmpty()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            EditorUi.message(Tone.ERROR, "Save is blocked: " + facts.error());
        }
        if (chooserOpen) {
            if (titleRow(
                    "OPEN ANOTHER TERRAIN", "terrainChooserClose", Glyph.CLOSE, false, "Fold the list of maps away.")) {
                chooserOpen = false;
            }
            if (chooserOpen) {
                drawMapChooser();
            }
        }
        if (tool == Tool.SCULPT) {
            drawSculptBrush(controller);
        } else {
            drawPaintBrush(controller);
        }
    }

    private void drawOpenTerrains(TerrainEditingController controller) {
        var open = controller.getDocuments();
        if (open.size() < 2) {
            return;
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        var width = GeodataWidgets.columnWidth(3);
        var busy = viewport.isBusy() || controller.isStrokeActive() || dragging;
        var draw = ImGui.getWindowDrawList();
        for (var index = 0; index < open.size(); index++) {
            var document = open.get(index);
            if (index % 3 != 0) {
                ImGui.sameLine();
            } else if (index > 0) {
                GeodataWidgets.space(GeodataWidgets.ROW);
            }
            var active = document == controller.getActive();
            var dirty = document.isDirty();
            if (GeodataWidgets.chip("terrainTab" + document.getOwner(), null, document.getMap(), "", active, width)
                    && !active
                    && !busy) {
                run(() -> viewport.activate(document.getOwner()));
            }
            if (dirty) {
                var top = ImGui.getItemRectMinY() + (ImGui.getItemRectSizeY() - ImGui.getFontSize()) * .5f - 1;
                draw.addCircleFilled(ImGui.getItemRectMaxX() - 10, HudWidgets.textMiddle(top), 3, EditorUi.WARNING);
            }
            tip("The Terrain of "
                    + document.getMap()
                    + (dirty ? ", with changes not saved." : ".")
                    + (active
                            ? " The brush works on it."
                            : busy
                                    ? " Wait until the running stroke is done."
                                    : " Click to work on it, or point at its ground with the brush.")
                    + " ("
                    + document.getOwner()
                    + ")");
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
    }

    private void drawMoreMenu(TerrainEditingController controller, boolean dirty) {
        popupRight = ImGui.getItemRectMaxX();
        popupBottom = ImGui.getItemRectMaxY();
        var several = controller.getDocuments().size() > 1;
        if (!HudWidgets.beginFittedMenu(MORE_MENU, 236, HudWidgets.menuHeight(several ? 4 : 3, 1))) {
            return;
        }
        closeOnEscape();
        if (HudWidgets.menuRow("terrainMoreLighting", Glyph.SUN, "Lighting…", "")) {
            pendingPopup = LIGHTING_POPUP;
        }
        GeodataWidgets.tip("The lights, sun and shadows of this map, and what Save rebuilds of its baked light.");
        var full = controller.getDocuments().size() >= TerrainEditingController.MOST_OPEN;
        if (HudWidgets.menuRow("terrainMoreAnother", Glyph.FOLDER, "Open another Terrain…", "", !full)) {
            chooserOpen = true;
        }
        GeodataWidgets.tip(
                full
                        ? TerrainEditingController.MOST_OPEN + " Terrains are open, the most at once. Close one first."
                        : "Choose another map and open its Terrain too. The open Terrains stay open, each with its"
                                + " changes.");
        HudWidgets.menuRule();
        var busy = viewport.isBusy() || controller.isStrokeActive() || dragging;
        if (HudWidgets.menuRow(
                "terrainMoreClose", Glyph.CLOSE, several ? "Close this Terrain" : "Close Terrain", "", !busy)) {
            requestClose(false, dirty);
        }
        GeodataWidgets.tip(
                busy
                        ? "Wait until the running stroke is done."
                        : dirty
                                ? "Close this Terrain. It asks first, because its strokes are not saved yet."
                                : several
                                        ? "Close the Terrain of "
                                                + EditingSession.mapOfPath(controller.getSelectedOwner())
                                                + ". The other Terrains stay open. Files you already exported are not"
                                                + " touched."
                                        : "Close this Terrain to open another one. Files you already exported are not"
                                                + " touched.");
        if (several) {
            var anyDirty = controller.isDirty();
            if (HudWidgets.menuRow("terrainMoreCloseAll", Glyph.CLOSE, "Close all Terrains", "", !busy)) {
                requestClose(true, anyDirty);
            }
            GeodataWidgets.tip(
                    busy
                            ? "Wait until the running stroke is done."
                            : anyDirty
                                    ? "Close every open Terrain. It asks first, because some strokes are not saved yet."
                                    : "Close every open Terrain. Files you already exported are not touched.");
        }
        HudWidgets.endMenu();
    }

    private void requestClose(boolean all, boolean dirty) {
        closeAll = all;
        if (dirty) {
            closeRequested = true;
        } else {
            close();
        }
    }

    private void drawLightingPopup() {
        if (pendingPopup != null) {
            ImGui.openPopup(pendingPopup);
            pendingPopup = null;
        }
        if (HudWidgets.beginPopupBelow(LIGHTING_POPUP, 320, popupRight, popupBottom)) {
            closeOnEscape();
            ImGui.textColored(HudWidgets.MUTED, "LIGHTING");
            drawLighting();
            ImGui.endPopup();
        }
    }

    private void close() {
        var all = closeAll;
        closeAll = false;
        var owner = viewport.getController().getSelectedOwner();
        run(() -> {
            if (all || owner == null) {
                viewport.discard();
                select.reset();
                paintLayers.clear();
                paintOwner = "";
                paintLayer = -1;
            } else {
                viewport.closeDocument(owner);
                select.reset(owner);
                paintLayers.remove(owner);
            }
            estimate = null;
            queued.clear();
        });
    }

    private void drawSculptBrush(TerrainEditingController controller) {
        EditorUi.section("Brush");
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/heightmap");
        var brushes = SculptBrush.values();
        var glyphs = new Glyph[brushes.length];
        var tips = new String[brushes.length];
        for (var i = 0; i < brushes.length; i++) {
            glyphs[i] = brushes[i].glyph;
            tips[i] = brushes[i].getTip();
        }
        var picked = GeodataWidgets.toolTiles(
                "terrainBrushTiles",
                glyphs,
                tips,
                sculptBrush.ordinal(),
                SculptBrush.SELECT.ordinal(),
                ImGui.getContentRegionAvailX());
        if (picked >= 0 && !dragging) {
            sculptBrush = brushes[picked];
            rampStart = null;
            rampOwner = null;
        }
        EditorUi.colored(
                HudWidgets.MUTED,
                HudWidgets.truncateToWidth(
                        sculptBrush.label + " · " + sculptBrush.note, ImGui.getContentRegionAvailX()));
        GeodataWidgets.space(GeodataWidgets.ROW);
        drawSize(controller);
        if (sculptBrush == SculptBrush.SELECT) {
            drawSelect(controller);
            return;
        }
        if (isQuadBrush()) {
            drawQuads(controller);
            return;
        }
        if (sculptBrush == SculptBrush.RAISE) {
            HudWidgets.sliderFloat("Speed (units per second)", speed.getData(), 20, 4000, "%.0f");
            tip("How fast the ground moves at the brush centre while you hold the mouse button. The edge moves"
                    + " less. Shift+[ and Shift+] or Shift+wheel change it.");
        } else if (sculptBrush != SculptBrush.RAMP && sculptBrush != SculptBrush.SET_HEIGHT) {
            HudWidgets.sliderFloat("Strength", strength.getData(), .05f, 1, "%.2f");
            tip((sculptBrush == SculptBrush.FLATTEN
                            ? "How fast the ground moves to the target height."
                            : sculptBrush == SculptBrush.NOISE
                                    ? "How fast the ground takes the noise."
                                    : "How fast bumps are softened.")
                    + " 1 does it in about a quarter of a second. Shift+[ and Shift+] or Shift+wheel change it.");
        }
        drawFalloff(sculptBrush == SculptBrush.SET_HEIGHT);
        switch (sculptBrush) {
            case FLATTEN, SET_HEIGHT -> drawTarget(controller);
            case RAMP -> {
                GeodataWidgets.space(GeodataWidgets.ROW);
                if (GeodataWidgets.decimal("Ramp width", rampWidth, " units")) {
                    rampWidth.set(oneDecimal(
                            Math.clamp(rampWidth.get(), sampleSpacing(controller), 256 * sampleSpacing(controller))));
                }
                tip("How wide the slope is, in world units. A soft edge of a quarter of the width on each side"
                        + " blends it into the ground beside it.");
                GeodataWidgets.space(GeodataWidgets.ROW);
                GeodataWidgets.hint(
                        rampStart == null
                                ? "Click the start of the ramp on the ground, then its end."
                                : "Click the end of the ramp. Esc starts again.");
            }
            case NOISE -> {
                GeodataWidgets.space(GeodataWidgets.ROW);
                if (GeodataWidgets.decimal("Amplitude", noiseAmplitude, " units")) {
                    noiseAmplitude.set(oneDecimal(Math.clamp(noiseAmplitude.get(), 1, 4096)));
                }
                tip("How far the noise lifts or lowers the ground at most, in world units. Shift with a drag turns"
                        + " the bumps upside down.");
                GeodataWidgets.space(GeodataWidgets.ROW);
                if (GeodataWidgets.decimal("Scale", noiseScale, " units")) {
                    noiseScale.set(oneDecimal(Math.clamp(noiseScale.get(), sampleSpacing(controller), 65536)));
                }
                tip("How far apart the bumps of the noise are, in world units. Each drag takes new bumps; going"
                        + " over the same place again does not pile them up.");
            }
            default -> {}
        }
        if (host != null) {
            host.drawFloatingMeshes();
        }
    }

    private void drawSelect(TerrainEditingController controller) {
        GeodataWidgets.space(GeodataWidgets.ROW);
        var info = controller.getOwnerInfo();
        var selection = select.getSelection(controller.getSelectedOwner(), info);
        GeodataWidgets.value("Selected", TerrainSelectTool.describe(selection));
        tip("Drag on the ground to select it, and Shift+drag to take ground out of the selection. Nothing of the"
                + " selection is saved; only a move changes heights.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        var half = GeodataWidgets.columnWidth(2);
        var edited = GeodataWidgets.columns(
                () -> GeodataWidgets.compactDecimal("Inner", "##terrainSelectInner", select.inner, " units", half),
                () -> GeodataWidgets.compactDecimal("Outer", "##terrainSelectOuter", select.outer, " units", half));
        if (edited[0] || edited[1]) {
            var limit = 64 * sampleSpacing(controller);
            select.inner.set(oneDecimal(Math.clamp(select.inner.get(), 0, limit)));
            select.outer.set(oneDecimal(Math.clamp(select.outer.get(), 0, limit)));
        }
        tip("The soft edge around the selection, in world units. Ground within Inner of the selection moves fully;"
                + " farther out it moves less, down to nothing at Outer.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (GeodataWidgets.decimal("Move by", select.moveBy, " units")) {
            select.moveBy.set(oneDecimal(Math.clamp(select.moveBy.get(), -16384, 16384)));
        }
        tip("How far Move selection lifts the selected ground, in world units. A negative number lowers it.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        var busy = viewport.isBusy() || controller.isStrokeActive() || dragging;
        ImGui.beginDisabled(busy || selection.isEmpty());
        var column = GeodataWidgets.columnWidth(2);
        if (GeodataWidgets.actionButton("terrainSelectMove", Glyph.TERRAIN, "Move selection", column, false)) {
            var weights = select.computeWeights(selection, sampleSpacing(controller));
            var raw = select.moveBy.get() / rawUnit(info);
            var owner = controller.getSelectedOwner();
            run(() -> viewport.moveSelection(owner, weights, raw, SculptBrush.SELECT.step));
        }
        tip("Move the selected ground by Move by, with its soft edge, as one Undo step.");
        ImGui.sameLine();
        if (GeodataWidgets.actionButton("terrainSelectClear", Glyph.CLOSE, "Clear", column, false)) {
            selection.clear();
        }
        tip("Select nothing. No height changes.");
        ImGui.endDisabled();
    }

    private void drawFalloff(boolean whole) {
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.rowLabel("Falloff", LABEL_WIDTH);
        ImGui.beginDisabled(whole);
        var picked = GeodataWidgets.segmented(
                "terrainFalloff",
                new String[] {"Smooth", "Linear", "Sharp"},
                falloff.ordinal(),
                ImGui.getContentRegionAvailX());
        if (picked >= 0) {
            falloff = Falloff.values()[picked];
        }
        ImGui.endDisabled();
        tip(
                whole
                        ? "Set height fills the whole circle at the target height, so it has no falloff."
                        : "How the brush fades from its centre to its edge: Smooth eases in and out, Linear falls"
                                + " evenly, and Sharp keeps most of its effect near the centre.");
    }

    private void drawTarget(TerrainEditingController controller) {
        GeodataWidgets.space(GeodataWidgets.ROW);
        var bottom = host == null ? null : host.getSelectionBottom();
        if (target == FlattenTarget.SELECTION && bottom == null) {
            target = FlattenTarget.DRAG_START;
        }
        GeodataWidgets.rowLabel("Target", LABEL_WIDTH);
        ImGui.setNextItemWidth(-1);
        if (HudWidgets.beginCombo("##terrainTarget", target.label, 0)) {
            for (var each : FlattenTarget.values()) {
                var unavailable = each == FlattenTarget.SELECTION && bottom == null;
                if (ImGui.selectable(each.label, each == target, unavailable ? ImGuiSelectableFlags.Disabled : 0)) {
                    target = each;
                    if (each == FlattenTarget.SELECTION && bottom != null) {
                        targetHeight.set(oneDecimal(bottom));
                    }
                }
                if (unavailable && ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
                    HudWidgets.showTooltip("Select actors first: the ground goes to the bottom of their bounds.");
                }
            }
            ImGui.endCombo();
        }
        tip("Where the drag starts takes the height under the first point of each drag. Height takes the number"
                + " below. Picked takes the height you Alt+click on the ground. Under the selection takes the"
                + " bottom of the selected actors.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        switch (target) {
            case DRAG_START -> GeodataWidgets.hint("Each drag keeps the height under its first point.");
            case HEIGHT, PICKED -> {
                if (GeodataWidgets.decimal("Height", targetHeight, " units")) {
                    targetHeight.set(oneDecimal(targetHeight.get()));
                }
                tip("The world height the ground goes to, in world units (" + EditorUi.meters(targetHeight.get())
                        + "). Alt+click on the ground picks the height there.");
                if (target == FlattenTarget.PICKED) {
                    GeodataWidgets.space(GeodataWidgets.ROW);
                    GeodataWidgets.hint("Alt+click on the ground picks another height.");
                }
            }
            case SELECTION ->
                GeodataWidgets.value("Height", bottom == null ? "nothing selected" : Math.round(bottom) + " units");
        }
    }

    private void drawPaintBrush(TerrainEditingController controller) {
        drawPaintTarget();
        if (paintTarget == PaintTarget.DECORATIONS) {
            drawDecorationBrush(controller);
            return;
        }
        followPaintLayer(controller);
        var paint = controller.getPaint();
        if (paint == null) {
            EditorUi.section("Layer");
            if (!controller.getPaintError().isEmpty()) {
                EditorUi.message(Tone.ERROR, "The layers cannot be painted: " + controller.getPaintError());
            } else {
                GeodataWidgets.note(withMovingDots("Reading the layers"), GeodataWidgets.ACCENT);
            }
            return;
        }
        var layers = paint.getLayers();
        if (paintLayer < 0
                || paintLayer >= layers.size()
                || !layers.get(paintLayer).isPaintable()) {
            paintLayer = layers.stream()
                    .filter(Layer::isPaintable)
                    .mapToInt(Layer::index)
                    .findFirst()
                    .orElse(-1);
        }
        var shown = layers.stream()
                .filter(layer ->
                        !layer.texture().isEmpty() || !layer.weightTexture().isEmpty())
                .toList();
        if (titleRow(
                "LAYER",
                "terms/terrain-layer",
                "terrainShowWeights",
                Glyph.EYE,
                showWeights && paintLayer >= 0,
                paintLayer >= 0,
                (showWeights ? "Hide the layer mask" : "Show the layer mask")
                        + ": tint the ground where the chosen layer shows, stronger where it shows more. Only the"
                        + " viewport shows the tint; nothing is written.")) {
            showWeights = !showWeights;
        }
        var textures = new long[shown.size()];
        var locked = new boolean[shown.size()];
        var tips = new String[shown.size()];
        var chosen = -1;
        for (var i = 0; i < shown.size(); i++) {
            var layer = shown.get(i);
            textures[i] = swatch(layer.texture());
            locked[i] = !layer.isPaintable();
            tips[i] = layer.index()
                    + " · "
                    + shortName(layer.texture())
                    + "  ·  "
                    + layerDetail(layer)
                    + "\n"
                    + (layer.isPaintable()
                            ? "Where it shows is kept in the layer mask " + layer.weightTexture()
                                    + ". Later layers are drawn over earlier ones. Alt+click on the ground chooses the"
                                    + " layer that shows most there."
                            : layer.restriction())
                    + swatchWait(textures[i]);
            chosen = layer.index() == paintLayer ? i : chosen;
        }
        var clicked = GeodataWidgets.pictureGrid(
                "terrainLayers",
                textures,
                Glyph.LAYERS,
                locked,
                tips,
                chosen,
                LAYER_SWATCH,
                ImGui.getContentRegionAvailX());
        if (clicked >= 0) {
            paintLayer = shown.get(clicked).index();
        }
        if (paintLayer < 0) {
            EditorUi.muted("No layer of this Terrain can be painted.");
            return;
        }
        drawLayerLine(layers.get(paintLayer));
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.setNextItemOpen(mappingOpen, ImGuiCond.Always);
        mappingOpen = GeodataWidgets.header("Mapping##terrainMapping");
        tip("Scale, pan and turn the texture of " + layerName(paint, paintLayer) + ".");
        if (mappingOpen) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            drawMapping(controller);
        }
        EditorUi.section("Brush");
        var picked =
                GeodataWidgets.segmented("terrainPaintMode", new String[] {"Paint", "Erase"}, paintBrush.ordinal());
        if (picked >= 0) {
            paintBrush = TerrainPaintHistory.Brush.values()[picked];
        }
        tip("Paint makes the chosen layer show under the brush, Erase removes it there. Shift while dragging swaps"
                + " them.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        drawSize(controller);
        HudWidgets.sliderFloat("Strength", strength.getData(), .05f, 1, "%.2f");
        tip("How fast the layer appears or fades at the brush centre. 1 paints it fully in about a quarter of a"
                + " second. Shift+[ and Shift+] or Shift+wheel change it.");
        if (ImGui.checkbox("Cover the layers above##terrainCoverAbove", coverAbove)) {
            coverAbove = !coverAbove;
        }
        tip("Later layers are drawn over earlier ones. With this on, Paint and Fill also fade the painted layers"
                + " above the chosen one, so it shows. Erase changes only the chosen layer.");
        GeodataWidgets.space(GeodataWidgets.SECTION);
        ImGui.setNextItemOpen(fillOpen, ImGuiCond.Always);
        fillOpen = GeodataWidgets.header("Fill##terrainFill");
        tip("Paint " + layerName(paint, paintLayer)
                + " at full weight over the whole Terrain, by slope or by height, as one Undo step.");
        if (fillOpen) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            drawFill(
                    controller,
                    shortName(paint.getLayers().get(paintLayer).texture()),
                    paint.getLayers().get(paintLayer));
        }
    }

    enum PaintTarget {
        LAYERS,
        DECORATIONS
    }

    private void drawPaintTarget() {
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var left = ImGui.getCursorScreenPosX();
        var width = ImGui.getContentRegionAvailX();
        var picked = GeodataWidgets.segmented(
                "terrainPaintTarget", new String[] {"Layers", "Decorations"}, paintTarget.ordinal());
        tip(
                GeodataWidgets.hoveredSegment(left, width, 2) == 0
                        ? "Paint the textures of the ground: where each layer shows."
                        : "Paint where the grass, flowers and bushes of the Terrain grow: the density map of each"
                                + " decoration layer.");
        if (picked >= 0 && !dragging) {
            paintTarget = PaintTarget.values()[picked];
        }
    }

    private void drawDecorationBrush(TerrainEditingController controller) {
        var density = controller.getDecorations();
        var sources = controller.getDecorationSources();
        if (density == null || sources == null) {
            EditorUi.section("Decoration");
            if (!controller.getDecorationError().isEmpty()) {
                EditorUi.message(Tone.ERROR, "The decorations cannot be painted: " + controller.getDecorationError());
            } else {
                GeodataWidgets.note(withMovingDots("Reading the decorations"), GeodataWidgets.ACCENT);
            }
            return;
        }
        var shown = sources.decorations().stream()
                .filter(decoration -> !decoration.densityMap().isEmpty())
                .toList();
        EditorUi.section("Decoration");
        if (shown.isEmpty()) {
            EditorUi.muted("No decoration grows on this Terrain.");
            return;
        }
        if (library != null) {
            library.start();
        }
        var chosenDecoration = chosenDecoration(controller);
        if (chosenDecoration == null || !chosenDecoration.isPaintable()) {
            paintDecoration = shown.stream()
                    .filter(Decoration::isPaintable)
                    .mapToInt(Decoration::index)
                    .findFirst()
                    .orElse(-1);
        }
        var textures = new long[shown.size()];
        var locked = new boolean[shown.size()];
        var tips = new String[shown.size()];
        var chosen = -1;
        for (var i = 0; i < shown.size(); i++) {
            var decoration = shown.get(i);
            textures[i] = thumbnail(decoration.mesh());
            locked[i] = !decoration.isPaintable();
            var also = decoration.getAlsoChanges();
            tips[i] = decorationName(decoration)
                    + "\n"
                    + (decoration.isPaintable()
                            ? "It grows where its density map " + decoration.densityMap()
                                    + " says. Alt+click on the ground chooses the decoration that grows most there."
                                    + (also.isEmpty() ? "" : "\nA change also changes " + also + ".")
                            : decoration.restriction());
            chosen = decoration.index() == paintDecoration ? i : chosen;
        }
        var clicked = GeodataWidgets.pictureGrid(
                "terrainDecorations",
                textures,
                Glyph.SPROUT,
                locked,
                tips,
                chosen,
                LAYER_SWATCH,
                ImGui.getContentRegionAvailX());
        if (clicked >= 0 && !dragging) {
            paintDecoration = shown.get(clicked).index();
        }
        var current = chosenDecoration(controller);
        if (current == null || !current.isPaintable()) {
            EditorUi.muted("No decoration of this Terrain can be painted.");
            return;
        }
        var slot = density.getLayers().get(current.slot());
        drawDecorationLine(current, slot);
        if (!current.getAlsoChanges().isEmpty()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.note("Also changes " + current.getAlsoChanges() + ".", GeodataWidgets.WARNING);
        }
        EditorUi.section("Brush");
        var picked = GeodataWidgets.segmented(
                "terrainDecorationMode", new String[] {"Paint", "Erase"}, paintBrush.ordinal());
        if (picked >= 0) {
            paintBrush = TerrainPaintHistory.Brush.values()[picked];
        }
        tip("Paint makes the chosen decoration grow under the brush, Erase takes it away there. Shift while dragging"
                + " swaps them.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        drawSize(controller);
        HudWidgets.sliderFloat("Strength", strength.getData(), .05f, 1, "%.2f");
        tip("How fast the density rises or falls at the brush centre. 1 paints it fully in about a quarter of a"
                + " second. Shift+[ and Shift+] or Shift+wheel change it.");
        GeodataWidgets.space(GeodataWidgets.SECTION);
        ImGui.setNextItemOpen(fillOpen, ImGuiCond.Always);
        fillOpen = GeodataWidgets.header("Fill##terrainDecorationFill");
        tip("Paint " + decorationName(current) + " at full density over the whole Terrain, by slope or by height, as"
                + " one Undo step.");
        if (fillOpen) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            drawFill(controller, shortName(current.mesh()), slot);
        }
    }

    private void drawDecorationLine(Decoration decoration, Layer slot) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var draw = ImGui.getWindowDrawList();
        var title = HudWidgets.truncateToWidth(decorationName(decoration), width);
        draw.addText(x, y, HudWidgets.TEXT, title);
        var after = ImGui.calcTextSizeX(title);
        var room = width - after;
        var detail = " · " + slot.width() + "×" + slot.height() + " density";
        if (room > ImGui.calcTextSizeX(" · ab…")) {
            draw.addText(x + after, y, HudWidgets.MUTED, HudWidgets.truncateToWidth(detail, room));
        }
        ImGui.dummy(width, ImGui.getTextLineHeight());
        tip(decoration.mesh() + "\nIt grows where its density map " + decoration.densityMap() + " says.");
    }

    private long thumbnail(String mesh) {
        if (library == null || mesh.isEmpty()) {
            return 0;
        }
        var entry = library.getMesh(mesh);
        return entry == null ? 0 : library.getThumbnail(entry);
    }

    private @Nullable Decoration chosenDecoration(TerrainEditingController controller) {
        var sources = controller.getDecorationSources();
        if (sources == null) {
            return null;
        }
        for (var decoration : sources.decorations()) {
            if (decoration.index() == paintDecoration) {
                return decoration;
            }
        }
        return null;
    }

    private int decorationSlot(TerrainEditingController controller) {
        var chosen = chosenDecoration(controller);
        return chosen == null || !chosen.isPaintable() ? -1 : chosen.slot();
    }

    private boolean isPaintReady(TerrainEditingController controller) {
        if (paintTarget == PaintTarget.DECORATIONS) {
            return controller.getDecorations() != null && decorationSlot(controller) >= 0;
        }
        return controller.getPaint() != null && paintLayer >= 0;
    }

    private void pickDecoration(TerrainEditingController controller, float x, float y) {
        var density = controller.getDecorations();
        var sources = controller.getDecorationSources();
        if (density == null || sources == null) {
            return;
        }
        var best = -1;
        var bestDensity = 0;
        for (var decoration : sources.decorations()) {
            if (!decoration.isPaintable()) {
                continue;
            }
            var value = density.getWeightAt(decoration.slot(), x, y);
            if (value > bestDensity) {
                best = decoration.index();
                bestDensity = value;
            }
        }
        if (best >= 0) {
            paintDecoration = best;
        }
    }

    private void followPaintLayer(TerrainEditingController controller) {
        var owner = controller.getSelectedOwner();
        var paint = controller.getPaint();
        if (owner == null || owner.equals(paintOwner) || paint == null) {
            return;
        }
        var before = paintOwner.isEmpty() ? null : controller.findDocument(paintOwner);
        var beforeLayers = before == null ? null : before.getPaint();
        var texture = beforeLayers != null
                        && paintLayer >= 0
                        && paintLayer < beforeLayers.getLayers().size()
                ? beforeLayers.getLayers().get(paintLayer).texture()
                : "";
        if (!paintOwner.isEmpty()) {
            paintLayers.put(paintOwner, paintLayer);
        }
        var next = paintLayers.getOrDefault(owner, -1);
        for (var layer : paint.getLayers()) {
            if (!texture.isEmpty() && layer.isPaintable() && layer.texture().equalsIgnoreCase(texture)) {
                next = layer.index();
                break;
            }
        }
        paintLayer = next;
        paintOwner = owner;
    }

    private void drawLayerLine(Layer layer) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var draw = ImGui.getWindowDrawList();
        var title = HudWidgets.truncateToWidth(layer.index() + " · " + shortName(layer.texture()), width);
        draw.addText(x, y, HudWidgets.TEXT, title);
        var after = ImGui.calcTextSizeX(title);
        var room = width - after;
        if (room > ImGui.calcTextSizeX(" · ab…")) {
            draw.addText(x + after, y, HudWidgets.MUTED, HudWidgets.truncateToWidth(" · " + layerDetail(layer), room));
        }
        ImGui.dummy(width, ImGui.getTextLineHeight());
        tip(layer.texture() + "\nWhere it shows is kept in the layer mask " + layer.weightTexture() + ".");
    }

    private void drawMapping(TerrainEditingController controller) {
        var layers = controller.getMapping();
        if (layers == null) {
            if (!controller.getMappingError().isEmpty()) {
                EditorUi.message(Tone.ERROR, "The layer mapping cannot be changed: " + controller.getMappingError());
            } else {
                GeodataWidgets.note(withMovingDots("Reading the layers"), GeodataWidgets.ACCENT);
            }
            return;
        }
        var current = layers.getMapping(paintLayer);
        if (current == null) {
            EditorUi.muted(layers.getRestriction(paintLayer) + ".");
            return;
        }
        var owner = controller.getSelectedOwner();
        if (mappingLayer != paintLayer || mappingRevision != layers.getRevision() || !mappingOwner.equals(owner)) {
            mappingLayer = paintLayer;
            mappingRevision = layers.getRevision();
            mappingOwner = owner;
            scaleU.set(current.uScale());
            scaleV.set(current.vScale());
            panU.set(current.uPan());
            panV.set(current.vPan());
            rotation.set(oneDecimal(current.textureRotation() * 360 / TerrainLayerMapping.FULL_TURN));
        }
        var name = layerName(controller.getPaint(), paintLayer);
        var busy = viewport.isBusy() || controller.isStrokeActive() || dragging;
        ImGui.beginDisabled(busy);
        var half = GeodataWidgets.columnWidth(2);
        var scale = GeodataWidgets.columns(
                () -> GeodataWidgets.compactDecimal("Scale U", "##terrainScaleU", scaleU, "", half),
                () -> GeodataWidgets.compactDecimal("Scale V", "##terrainScaleV", scaleV, "", half));
        tip("How many points of the ground one repeat of the texture covers, across and along: 1 repeats it every "
                + Math.round(sampleSpacing(controller)) + " units, 2 makes it twice as large. (TexScale)");
        GeodataWidgets.space(GeodataWidgets.ROW);
        var pan = GeodataWidgets.columns(
                () -> GeodataWidgets.compactDecimal("Pan U", "##terrainPanU", panU, "", half),
                () -> GeodataWidgets.compactDecimal("Pan V", "##terrainPanV", panV, "", half));
        tip("Where the repeat starts, in whole textures: 0.5 moves the texture by half of its size. (TexPan)");
        GeodataWidgets.space(GeodataWidgets.ROW);
        var turned = GeodataWidgets.decimal("Rotation", rotation, "°");
        tip("How far the texture is turned, in degrees. The game turns it in steps of about 0.02 degrees. (TexRotate)");
        ImGui.endDisabled();
        if (scale[0] || scale[1]) {
            var u = Math.clamp(scaleU.get(), .01f, 1024);
            var v = Math.clamp(scaleV.get(), .01f, 1024);
            commitMapping(current.withScale(u, v), "Scale " + name);
        } else if (pan[0] || pan[1]) {
            commitMapping(current.withPan(panU.get(), panV.get()), "Pan " + name);
        } else if (turned) {
            var degrees = rotation.get() % 360;
            commitMapping(current.withRotation(degrees * TerrainLayerMapping.FULL_TURN / 360), "Turn " + name);
        }
    }

    private void commitMapping(TerrainLayerMapping mapping, String label) {
        mappingRevision = -1;
        if (!Float.isFinite(mapping.uPan())
                || !Float.isFinite(mapping.vPan())
                || !Float.isFinite(mapping.textureRotation())) {
            return;
        }
        var layer = paintLayer;
        var owner = viewport.getController().getSelectedOwner();
        run(() -> viewport.setLayerMapping(owner, layer, mapping, label));
    }

    private void drawFill(TerrainEditingController controller, String name, Layer layer) {
        var busy = viewport.isBusy() || controller.isStrokeActive() || dragging;
        var decorations = paintTarget == PaintTarget.DECORATIONS;
        var slot = decorations ? decorationSlot(controller) : paintLayer;
        var what = decorations ? " at full density" : " at full weight";
        if (GeodataWidgets.decimal("Slope above", slopeDegrees, "°")) {
            slopeDegrees.set(oneDecimal(Math.clamp(slopeDegrees.get(), 0, 89)));
        }
        tip("The steepness, in degrees, above which Fill by slope paints. 0 is flat ground, 45 a slope as steep as"
                + " it is long.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.beginDisabled(busy);
        var owner = controller.getSelectedOwner();
        if (GeodataWidgets.actionButton("terrainFillSlope", Glyph.TERRAIN, "Fill by slope", -1, false)) {
            var degrees = slopeDegrees.get();
            Function<TerrainHeightField, BitSet> mask =
                    field -> TerrainBrushes.slopeMask(field, layer.width(), layer.height(), degrees);
            var label = "Fill " + name + " by slope";
            if (decorations) {
                run(() -> viewport.fillDecoration(owner, slot, mask, label));
            } else {
                run(() -> viewport.fill(owner, slot, mask, coverAbove, label));
            }
        }
        ImGui.endDisabled();
        tip("Paint " + name + what + " wherever the ground of this Terrain is steeper than "
                + Math.round(slopeDegrees.get()) + " degrees, as one Undo step.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        var half = GeodataWidgets.columnWidth(2);
        var left = GeodataWidgets.columns(
                () -> GeodataWidgets.compactDecimal("From", "##terrainFillFrom", heightFrom, " units", half),
                () -> GeodataWidgets.compactDecimal("To", "##terrainFillTo", heightTo, " units", half));
        if (left[0] || left[1]) {
            heightFrom.set(oneDecimal(heightFrom.get()));
            heightTo.set(oneDecimal(heightTo.get()));
        }
        tip("The world heights between which Fill by height paints, in world units.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.beginDisabled(busy);
        if (GeodataWidgets.actionButton("terrainFillHeight", Glyph.TERRAIN, "Fill by height", -1, false)) {
            var from = heightFrom.get();
            var to = heightTo.get();
            Function<TerrainHeightField, BitSet> mask =
                    field -> TerrainBrushes.heightMask(field, layer.width(), layer.height(), from, to);
            var label = "Fill " + name + " by height";
            if (decorations) {
                run(() -> viewport.fillDecoration(owner, slot, mask, label));
            } else {
                run(() -> viewport.fill(owner, slot, mask, coverAbove, label));
            }
        }
        ImGui.endDisabled();
        tip("Paint " + name + what
                + " wherever the ground of this Terrain lies between the two heights, as one Undo step.");
    }

    private void drawSize(TerrainEditingController controller) {
        var spacing = sampleSpacing(controller);
        drawPresets(spacing);
        var units = new float[] {radius.get() * spacing};
        HudWidgets.sliderFloat("Size", units, spacing, 32 * spacing, "%.0f units");
        radius.set(Math.clamp(units[0] / spacing, 1, 32));
        tip("Brush radius in world units, now "
                + Math.round(units[0])
                + " units, "
                + EditorUi.meters(units[0])
                + ". [ and ] or "
                + COMMAND_KEY
                + "+wheel change it.");
    }

    private void drawPresets(float spacing) {
        var startX = ImGui.getCursorScreenPosX();
        var startY = ImGui.getCursorScreenPosY();
        var presets = BrushPreset.values();
        var chip = 24f;
        var gap = 4f;
        var right = ImGui.getWindowPosX() + ImGui.getWindowContentRegionMaxX() - 72 - GeodataWidgets.ROW;
        var chosen = BrushPreset.of(radius.get());
        for (var index = 0; index < presets.length; index++) {
            var preset = presets[index];
            ImGui.setCursorScreenPos(right - (presets.length - index) * (chip + gap) + gap, startY);
            if (GeodataWidgets.chip(
                    "terrainSize" + preset.letter, null, preset.letter, "", preset == chosen, chip, PRESET_HEIGHT)) {
                radius.set(preset.points);
            }
            presetAreas.put(preset.letter, new float[] {
                ImGui.getItemRectMinX(), ImGui.getItemRectMinY(), ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY()
            });
            tip(preset.getTip(spacing));
        }
        ImGui.setCursorScreenPos(startX, startY);
    }

    enum BrushPreset {
        SMALL("S", "Small", 4),
        MEDIUM("M", "Medium", 8),
        LARGE("L", "Large", 16);

        final String letter;
        final String name;
        final int points;

        BrushPreset(String letter, String name, int points) {
            this.letter = letter;
            this.name = name;
            this.points = points;
        }

        static @Nullable BrushPreset of(float radius) {
            for (var preset : values()) {
                if (Math.abs(radius - preset.points) < 1e-3f) {
                    return preset;
                }
            }
            return null;
        }

        String getTip(float spacing) {
            return name + " · " + points + " points · " + EditorUi.unitsAndMeters(Math.round(points * spacing));
        }
    }

    private void drawLighting() {
        var controller = viewport.getController();
        var facts = controller.getLighting();
        if (facts == null) {
            GeodataWidgets.note(withMovingDots("Reading the lighting scene"), GeodataWidgets.ACCENT);
            return;
        }
        if (!facts.error().isEmpty()) {
            EditorUi.message(Tone.ERROR, "Save is blocked: " + facts.error());
            return;
        }
        var summary = facts.summary();
        GeodataWidgets.value("Lights", summary.steadyCandidates() + " steady in this zone");
        GeodataWidgets.value(
                "Sunlight",
                controller.getScene() == null
                                || controller.getScene().getSlots().isEmpty()
                        ? "none saved"
                        : controller.getScene().getSlots().size() + " time slots");
        GeodataWidgets.value("Casters", summary.shadowMeshes() + " static meshes, BSP");
        var fidelity = facts.fidelity();
        var parts = new ArrayList<String>();
        if (fidelity != null && fidelity.timeSamples() > 0) {
            parts.add(String.format(Locale.ROOT, "%.1f%% of the saved sunlight", 100 * fidelity.timeLitAgreement()));
        }
        if (fidelity != null && fidelity.maskBits() > 0) {
            parts.add(String.format(Locale.ROOT, "%.1f%% of the steady light masks", 100 * fidelity.maskAgreement()));
        }
        if (!parts.isEmpty()) {
            EditorUi.muted("A rebuild of "
                    + fidelity.sectors()
                    + " unedited sectors matches "
                    + String.join(" and ", parts)
                    + ".");
            tip(String.format(
                    Locale.ROOT,
                    "Exact sunlight bytes %.2f%%, mean difference %.2f. Exact vertex colours %.2f%%. Save rebuilds"
                            + " only the vertices whose height, normal or zone changed, and the rays that moved, new or"
                            + " deleted actors can shadow.",
                    100 * fidelity.timeExactShare(),
                    fidelity.timeMeanDiff(),
                    100 * fidelity.colorExactShare()));
        }
        var beast = controller.getScene() == null ? null : controller.getScene().getBeast();
        if (beast != null && beast != beastChecked) {
            beastChecked = beast;
            beastFlat = beast.isFlat();
        }
        if (beast != null && beast.fit().samples() == 0) {
            EditorUi.muted(
                    "Beast lightmaps light this Terrain, but it draws none of its quads, so the save keeps them.");
        } else if (beast != null && beastFlat) {
            EditorUi.muted(
                    "Beast lightmaps light this Terrain. They are one flat colour without light or shadow, so the save"
                            + " keeps them.");
        } else if (beast != null) {
            var fit = beast.fit();
            EditorUi.muted(
                    "Beast lightmaps light this Terrain. Save updates the texels whose light an edit changes, with an"
                            + " approximate baker.");
            tip(String.format(
                    Locale.ROOT,
                    "Beast bakes cannot be rebuilt exactly: the baker is not part of the client. Cruma fitted its"
                        + " approximate baker to %d texels of this Terrain's own lightmap. On sectors it did not use,"
                        + " the sun bake differs by %.0f of 255 on average (%.0f%% within 16) and the local light bake"
                        + " by %.0f. Texels whose rays no stroke or actor edit changes keep their saved bytes.",
                    fit.samples(),
                    fit.slot0After().meanAbsolute(),
                    100 * fit.slot0After().within16(),
                    fit.slot1After().meanAbsolute()));
        }
        if (!facts.sharedWith().isEmpty()) {
            EditorUi.message(
                    Tone.WARNING,
                    "The height texture is shared with " + String.join(", ", facts.sharedWith())
                            + ". Their Terrain changes too, but their lighting is not rebuilt.");
        }
        for (var note : summary.notes()) {
            EditorUi.message(Tone.INFO, note);
        }
        var current = computeEstimate(controller);
        if (current.samples() == 0) {
            EditorUi.muted("Sculpt to see what the save rebuilds. Painting changes no lighting.");
            return;
        }
        EditorUi.message(
                Tone.INFO,
                "Lighting will be rebuilt for "
                        + EditorUi.plural(current.sectors(), "sector", "sectors")
                        + " around "
                        + EditorUi.plural(current.samples(), "changed sample", "changed samples")
                        + ".");
        if (current.bspSurfaces() > 0) {
            EditorUi.message(
                    Tone.INFO,
                    EditorUi.plural(current.bspSurfaces(), "BSP surface stands", "BSP surfaces stand")
                            + " near the edit. Save rebuilds the BSP lightmaps whose shadows the edit changes.");
        }
        if (current.meshActors() > 0) {
            EditorUi.message(
                    Tone.INFO,
                    EditorUi.plural(current.meshActors(), "static mesh actor stands", "static mesh actors stand")
                            + " near the edit. Their baked lighting does not depend on the Terrain, but check that"
                            + " they still sit on the ground.");
        }
    }

    private Estimate computeEstimate(TerrainEditingController controller) {
        var history = controller.getHistory();
        var owner = controller.getSelectedOwner();
        var same = estimate != null && estimate.owner().equals(owner);
        if (same && (history.isStrokeActive() || estimate.revision() == history.getRevision())) {
            return estimate;
        }
        if (history.isStrokeActive()) {
            return new Estimate(owner, -1, 0, 0, 0, 0);
        }
        var changes = history.getSourceChanges();
        var width = changes.getWidth();
        var sectors = new TreeSet<Integer>();
        for (var i = 0; i < changes.size(); i++) {
            var x = changes.getIndex(i) % width;
            var y = changes.getIndex(i) / width;
            for (var ny = y - 1; ny <= y + 1; ny++) {
                for (var nx = x - 1; nx <= x + 1; nx++) {
                    for (var sy = Math.max(0, (ny - 1) / 16); sy <= Math.min(15, ny / 16); sy++) {
                        for (var sx = Math.max(0, (nx - 1) / 16); sx <= Math.min(15, nx / 16); sx++) {
                            sectors.add(sy * 16 + sx);
                        }
                    }
                }
            }
        }
        var bsp = 0;
        var meshes = 0;
        var scene = controller.getScene();
        if (scene != null && !sectors.isEmpty()) {
            var toWorld = controller.getOwnerInfo().toWorld();
            float minX = Float.POSITIVE_INFINITY, minY = minX, maxX = Float.NEGATIVE_INFINITY, maxY = maxX;
            for (var sector : sectors) {
                var corner0 = toWorld.transform(sector % 16 * 16, sector / 16 * 16, 0);
                var corner1 = toWorld.transform(
                        Math.min(255, sector % 16 * 16 + 16), Math.min(255, sector / 16 * 16 + 16), 0);
                minX = Math.min(minX, Math.min(corner0.x(), corner1.x()));
                minY = Math.min(minY, Math.min(corner0.y(), corner1.y()));
                maxX = Math.max(maxX, Math.max(corner0.x(), corner1.x()));
                maxY = Math.max(maxY, Math.max(corner0.y(), corner1.y()));
            }
            for (var box : scene.getBspSurfaces()) {
                if (box.min().x() <= maxX
                        && box.max().x() >= minX
                        && box.min().y() <= maxY
                        && box.max().y() >= minY) {
                    bsp++;
                }
            }
            for (var actor : scene.getMeshActors()) {
                var box = actor.bounds();
                if (box.min().x() <= maxX
                        && box.max().x() >= minX
                        && box.min().y() <= maxY
                        && box.max().y() >= minY) {
                    meshes++;
                }
            }
        }
        estimate = new Estimate(owner, history.getRevision(), changes.size(), sectors.size(), bsp, meshes);
        return estimate;
    }

    private String label(String map) {
        return map.equals(cameraMap) ? map + "  (camera)" : map;
    }

    private void startInspect(String name) {
        var root = session.getClientRoot();
        var files = session.getMapFiles();
        pending = CompletableFuture.supplyAsync(() -> inspect(root, files, name));
    }

    private void changeSize(boolean bigger) {
        radius.set(Math.clamp(bigger ? radius.get() * 1.15f : radius.get() / 1.15f, 1, 32));
    }

    private void changeStrength(boolean stronger) {
        var factor = stronger ? 1.15f : 1 / 1.15f;
        if (tool == Tool.SCULPT && sculptBrush == SculptBrush.RAISE) {
            speed.set(Math.clamp(speed.get() * factor, 20, 4000));
        } else {
            strength.set(Math.clamp(strength.get() * factor, .05f, 1));
        }
    }

    private Shape startShape(OpenGround ground, Vector hit) {
        var info = ground.getBase();
        var brush = sculptBrush.brush == null ? Brush.RAISE : sculptBrush.brush;
        var raw = rawAt(ground, hit.x(), hit.y());
        if (target != FlattenTarget.DRAG_START) {
            var world = info.toWorld().transform(hit.x(), hit.y(), 0);
            var height = target == FlattenTarget.SELECTION && host != null
                    ? host.getSelectionBottom()
                    : Float.valueOf(targetHeight.get());
            if (height != null) {
                raw = Math.round(info.toHeightmap()
                        .transform(world.x(), world.y(), height)
                        .z());
            }
        }
        var spacing = sampleSpacing(viewport.getController());
        return new Shape(
                brush,
                radius.get(),
                Math.clamp(raw, 0, 65535),
                falloff,
                noiseSeeds++,
                noiseAmplitude.get() / rawUnit(info),
                Math.max(.5f, noiseScale.get() / spacing));
    }

    private void pick(TerrainEditingController controller, OpenGround ground, float x, float y) {
        if (tool == Tool.SCULPT) {
            targetHeight.set(oneDecimal(ground.getWorldAt(x, y).z()));
            target = FlattenTarget.PICKED;
            if (host != null) {
                host.showNote(pickedNote(targetHeight.get()));
            }
            return;
        }
        if (paintTarget == PaintTarget.DECORATIONS) {
            pickDecoration(controller, x, y);
            return;
        }
        var paint = controller.getPaint();
        if (paint == null) {
            return;
        }
        var best = -1;
        var bestWeight = 0;
        for (var layer : paint.getLayers()) {
            var weight = paint.getWeightAt(layer.index(), x, y);
            if (weight > bestWeight) {
                best = layer.index();
                bestWeight = weight;
            }
        }
        if (best >= 0) {
            paintLayer = best;
        }
    }

    private void queue(float x, float y, float seconds, boolean inverse) {
        if (!queued.isEmpty() && inverse != queuedInverse && !viewport.isBusy()) {
            flushStamps();
        }
        if (!queued.isEmpty() && inverse != queuedInverse || queued.size() >= 512) {
            return;
        }
        queuedInverse = inverse;
        float amount;
        if (tool == Tool.PAINT) {
            amount = Math.min(1, strength.get() * 4 * seconds);
        } else if (sculptBrush == SculptBrush.RAISE) {
            amount = Math.min(65535, speed.get() / rawUnit(strokeInfo()) * seconds);
        } else if (sculptBrush == SculptBrush.SET_HEIGHT) {
            amount = 1;
        } else {
            amount = (float) (1 - Math.pow(1 - Math.min(.999f, strength.get()), seconds / .25f * 4));
        }
        queued.add(new TerrainBrushStamp(x, y, amount));
    }

    private void flushStamps() {
        var stamps = List.copyOf(queued);
        queued.clear();
        if (tool == Tool.SCULPT && isQuadBrush()) {
            var brush = sculptBrush == SculptBrush.HOLES
                    ? queuedInverse ? TerrainQuadHistory.Brush.SHOW : TerrainQuadHistory.Brush.HIDE
                    : queuedInverse ? TerrainQuadHistory.Brush.RESTORE_TURN : TerrainQuadHistory.Brush.TURN;
            run(() -> viewport.quads(strokeOwner, brush, radius.get(), stamps));
            return;
        }
        if (tool == Tool.PAINT) {
            var mode = queuedInverse
                    ? (paintBrush == TerrainPaintHistory.Brush.PAINT
                            ? TerrainPaintHistory.Brush.ERASE
                            : TerrainPaintHistory.Brush.PAINT)
                    : paintBrush;
            if (paintTarget == PaintTarget.DECORATIONS) {
                var slot = decorationSlot(viewport.getController());
                run(() -> viewport.paintDecoration(strokeOwner, slot, mode, radius.get(), stamps));
                return;
            }
            run(() -> viewport.paint(strokeOwner, paintLayer, mode, radius.get(), coverAbove, stamps));
            return;
        }
        var start = strokeShape;
        if (start == null) {
            return;
        }
        var brush = start.brush() == Brush.RAISE && queuedInverse ? Brush.LOWER : start.brush();
        var amplitude = start.brush() == Brush.NOISE && queuedInverse ? -start.amplitude() : start.amplitude();
        var shape =
                new Shape(brush, radius.get(), start.target(), start.falloff(), start.seed(), amplitude, start.scale());
        run(() -> viewport.sculpt(strokeOwner, shape, stamps));
    }

    private boolean isTargetHeld(boolean typing) {
        return dragging
                || typing
                || !queued.isEmpty()
                || finishRequested
                || cancelRequested
                || !Float.isNaN(pendingMove)
                || viewport.isBusy()
                || viewport.getController().isStrokeActive();
    }

    private boolean crossesBorders() {
        return tool == Tool.SCULPT && (sculptBrush.brush != null || sculptBrush == SculptBrush.RAMP);
    }

    private Info strokeInfo() {
        var controller = viewport.getController();
        var anchor = strokeOwner == null ? null : controller.findDocument(strokeOwner);
        return anchor != null ? anchor.getInfo() : controller.getOwnerInfo();
    }

    private OpenGround openGround(TerrainEditingController controller, TerrainDocument base, TerrainHeightField field) {
        var parts = new ArrayList<Part>();
        for (var other : controller.getDocuments()) {
            if (other == base || !viewport.isResident(other.getOwner())) {
                continue;
            }
            var link = TerrainTileLink.of(base.getInfo(), other.getInfo());
            var ground = link.fits() ? ground(other) : null;
            if (ground != null) {
                parts.add(new Part(link, other.getInfo(), ground));
            }
        }
        return new OpenGround(base.getInfo(), field, parts);
    }

    private void openBesideNear(TerrainEditingController controller, TerrainDocument under, Vector hit) {
        if (besideRead != null || besideOpen != null) {
            return;
        }
        neighbors.setMapFiles(session.getMapFiles());
        var map = neighbors.next(
                controller.getDocuments(), session.getOpenedMaps(), under.getInfo(), hit.x(), hit.y(), radius.get());
        if (map == null) {
            return;
        }
        neighbors.asked(map);
        besideMap = map;
        besideFrom = under.getOwner();
        var root = session.getClientRoot();
        var files = session.getMapFiles();
        besideRead = CompletableFuture.supplyAsync(() -> inspect(root, files, map));
    }

    private void pollBeside() {
        if (besideRead != null && besideRead.isDone()) {
            var read = besideRead.join();
            besideRead = null;
            var editable = read.owners().stream()
                    .filter(owner -> owner.heightsDecoded() && owner.isNativeExportSupported())
                    .toList();
            var why = "";
            if (!read.error().isEmpty()) {
                why = read.error();
            } else if (read.owners().isEmpty()) {
                why = "Its map has no Terrain.";
            } else if (editable.isEmpty()) {
                var restriction = read.owners().getFirst().exportRestriction();
                why = "It cannot be edited" + (restriction.isEmpty() ? "." : ": " + restriction);
            } else if (editable.size() > 1) {
                why = "Its map has " + editable.size() + " Terrains, so choose one in the More menu.";
            }
            if (!why.isEmpty() || viewport == null) {
                neighbors.refuse(besideMap, besideNote(besideMap, why));
            } else {
                try {
                    besideOpen = viewport.openBeside(
                            findMap(session.getClientRoot(), session.getMapFiles(), besideMap),
                            editable.getFirst().owner(),
                            besideFrom);
                } catch (IOException | RuntimeException failure) {
                    neighbors.refuse(besideMap, besideNote(besideMap, UserMessages.plain(failure.getMessage())));
                }
            }
        }
        if (besideOpen != null && besideOpen.isDone()) {
            var why = besideOpen.join();
            besideOpen = null;
            if (why.isEmpty()) {
                neighbors.opened(besideMap);
            } else {
                neighbors.refuse(besideMap, besideNote(besideMap, why));
            }
        }
    }

    private @Nullable Aim nearestAim(
            TerrainEditingController controller, Vec3 origin, Vec3 direction, @Nullable TerrainDocument base) {
        Aim best = null;
        for (var document : controller.getDocuments()) {
            if (!viewport.isResident(document.getOwner())) {
                continue;
            }
            if (base != null
                    && document != base
                    && !TerrainTileLink.of(base.getInfo(), document.getInfo()).fits()) {
                continue;
            }
            var ground = ground(document);
            if (ground == null) {
                continue;
            }
            var info = document.getInfo();
            var hit = intersect(info, ground, origin, direction);
            if (hit == null) {
                continue;
            }
            var world = info.toWorld().transform(hit.x(), hit.y(), hit.z());
            var dx = (double) world.x() - origin.getX();
            var dy = (double) world.y() - origin.getY();
            var dz = (double) world.z() - origin.getZ();
            var distance = dx * dx + dy * dy + dz * dz;
            if (best == null || distance < best.distance()) {
                best = new Aim(document, ground, hit, distance);
            }
        }
        return best;
    }

    private @Nullable Aim seamAim(
            TerrainEditingController controller,
            ViewerRenderBackend backend,
            Vec3 origin,
            Vec3 direction,
            float nx,
            float ny,
            @Nullable TerrainDocument base) {
        var hit = backend.pickDebugSceneObject(nx, ny, Float.MAX_VALUE, EnumSet.allOf(Category.class));
        if (hit == null || hit.object().category() != Category.TERRAIN) {
            return null;
        }
        var x = origin.getX() + direction.getX() * hit.distance();
        var y = origin.getY() + direction.getY() * hit.distance();
        var z = origin.getZ() + direction.getZ() * hit.distance();
        for (var document : controller.getDocuments()) {
            if (!viewport.isResident(document.getOwner())
                    || base != null
                            && document != base
                            && !TerrainTileLink.of(base.getInfo(), document.getInfo())
                                    .fits()) {
                continue;
            }
            var info = document.getInfo();
            var sample = info.toHeightmap().transform(x, y, z);
            var ground = ground(document);
            if (ground != null
                            && sample.x() >= info.width() - 1
                            && sample.x() <= info.width()
                            && sample.y() >= 0
                            && sample.y() <= info.height()
                    || ground != null
                            && sample.y() >= info.height() - 1
                            && sample.y() <= info.height()
                            && sample.x() >= 0
                            && sample.x() <= info.width()) {
                return new Aim(document, ground, sample, hit.distance());
            }
        }
        return null;
    }

    private @Nullable TerrainHeightField ground(TerrainDocument document) {
        var samples = viewport.getVisibleSamples(document.getOwner());
        if (samples == null) {
            return null;
        }
        var cached = grounds.get(document.getOwner());
        if (cached != null && cached.samples() == samples) {
            return cached.field();
        }
        if (grounds.size() > TerrainEditingController.MOST_OPEN) {
            grounds.clear();
        }
        var info = document.getInfo();
        var field = new TerrainHeightField(info.width(), info.height(), samples, info.toWorld());
        grounds.put(document.getOwner(), new Ground(samples, field));
        return field;
    }

    private boolean offerOther(Camera camera, ViewerRenderBackend backend, float nx, float ny) {
        var io = ImGui.getIO();
        if (io.getWantCaptureMouse() || ImGui.isMouseDown(1) || viewport.isBusy() || pending != null) {
            return false;
        }
        var map = terrainMapAt(camera, backend, nx, ny);
        if (map == null) {
            return false;
        }
        for (var document : viewport.getController().getDocuments()) {
            if (document.getMap().equals(map)) {
                return false;
            }
        }
        HudWidgets.showTooltip("Terrain of " + map + "\nClick to edit it too");
        if (ImGui.isMouseClicked(0)) {
            readToOpen(map);
        }
        return true;
    }

    private void readToOpen(String map) {
        otherMap = !session.getOpenedMaps().contains(map);
        mapName.set(map);
        openOnInspect = map;
        startInspect(map);
    }

    private boolean openUnderPointer(
            Camera camera, ViewerRenderBackend backend, float left, float top, float right, float bottom) {
        var io = ImGui.getIO();
        var x = ImGui.getMousePosX();
        var y = ImGui.getMousePosY();
        if (x < left || x > right || y < top || y > bottom || io.getWantCaptureMouse() || ImGui.isMouseDown(1)) {
            return false;
        }
        if (viewport.isBusy() || pending != null) {
            return true;
        }
        var nx = x / io.getDisplaySizeX() * 2 - 1;
        var ny = 1 - y / io.getDisplaySizeY() * 2;
        var map = terrainMapAt(camera, backend, nx, ny);
        if (map == null) {
            return true;
        }
        HudWidgets.showTooltip("Terrain of " + map + "\nClick to edit it");
        if (ImGui.isMouseClicked(0)) {
            readToOpen(map);
        }
        return true;
    }

    private String strokeName(boolean inverse, TerrainEditingController controller) {
        if (tool == Tool.PAINT && paintTarget == PaintTarget.DECORATIONS) {
            var erase = paintBrush == TerrainPaintHistory.Brush.ERASE != inverse;
            var chosen = chosenDecoration(controller);
            return (erase ? "Erase " : "Paint ") + (chosen == null ? "decorations" : decorationName(chosen));
        }
        if (tool == Tool.PAINT) {
            var erase = paintBrush == TerrainPaintHistory.Brush.ERASE != inverse;
            var paint = controller.getPaint();
            var layer = paint == null || paintLayer < 0
                    ? "layer"
                    : shortName(paint.getLayers().get(paintLayer).texture());
            return (erase ? "Erase " : "Paint ") + layer;
        }
        if (sculptBrush == SculptBrush.HOLES && inverse) {
            return "Show ground";
        }
        if (sculptBrush == SculptBrush.EDGES && inverse) {
            return "Restore edges";
        }
        return sculptBrush == SculptBrush.RAISE && inverse ? "Lower ground" : sculptBrush.step;
    }

    private boolean isQuadBrush() {
        return tool == Tool.SCULPT && (sculptBrush == SculptBrush.HOLES || sculptBrush == SculptBrush.EDGES);
    }

    private void drawQuads(TerrainEditingController controller) {
        GeodataWidgets.space(GeodataWidgets.ROW);
        var bits = controller.getQuads();
        if (bits == null) {
            if (!controller.getQuadsError().isEmpty()) {
                EditorUi.message(Tone.ERROR, "The cells cannot be changed: " + controller.getQuadsError());
            } else {
                GeodataWidgets.note(withMovingDots("Reading the cells"), GeodataWidgets.ACCENT);
            }
            return;
        }
        var visibility = bits.getChangedVisibility();
        var hidden = 0;
        var shown = 0;
        for (var quad = visibility.nextSetBit(0); quad >= 0; quad = visibility.nextSetBit(quad + 1)) {
            if (bits.isVisible(quad % bits.getWidth(), quad / bits.getWidth())) {
                shown++;
            } else {
                hidden++;
            }
        }
        GeodataWidgets.value("Hidden", hidden + " · shown again " + shown);
        tip("Cells of the ground that the game does not draw and walks through, compared with the client, and"
                + " hidden cells that are drawn again.");
        var turned = bits.getChangedTurns().cardinality();
        GeodataWidgets.value("Turned", String.valueOf(turned));
        tip("Cells whose diagonal differs from the client. The diagonal decides the two triangles of a cell, so"
                + " the shape, the light and the collision there.");
        if (hidden + shown + turned > 0) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.note("Generate this map's geodata again after Save.", GeodataWidgets.WARNING);
            tip("The server's geodata does not know the new ground: the cells that are hidden or turned.");
        }
    }

    private void drawQuadMarks(
            ScreenProjection projection, Info info, TerrainHeightField ground, TerrainQuadHistory bits, Vector hit) {
        var draw = ImGui.getBackgroundDrawList();
        var reach = radius.get() + 2;
        var minX = Math.max(0, (int) Math.floor(hit.x() - reach));
        var maxX = Math.min(info.width() - 2, (int) Math.ceil(hit.x() + reach));
        var minY = Math.max(0, (int) Math.floor(hit.y() - reach));
        var maxY = Math.min(info.height() - 2, (int) Math.ceil(hit.y() + reach));
        var corners = new float[8];
        var hiddenColor = EditorUi.fill(EditorUi.WARNING, .8f);
        var edgeColor = EditorUi.fill(HudWidgets.ACCENT, .55f);
        for (var y = minY; y <= maxY; y++) {
            for (var x = minX; x <= maxX; x++) {
                var visible = bits.isVisible(x, y);
                if (visible && sculptBrush != SculptBrush.EDGES) {
                    continue;
                }
                var shown = true;
                for (var corner = 0; corner < 4; corner++) {
                    var cx = x + (corner == 1 || corner == 2 ? 1 : 0);
                    var cy = y + (corner >= 2 ? 1 : 0);
                    var world = info.toWorld().transform(cx, cy, ground.getHeightAt(cx, cy));
                    shown &= projection.project(world.x(), world.y(), world.z(), corners, corner * 2);
                }
                if (!shown) {
                    continue;
                }
                if (!visible) {
                    draw.addQuad(
                            corners[0],
                            corners[1],
                            corners[2],
                            corners[3],
                            corners[4],
                            corners[5],
                            corners[6],
                            corners[7],
                            hiddenColor,
                            1.5f);
                } else if (bits.isTurned(x, y)) {
                    draw.addLine(corners[6], corners[7], corners[2], corners[3], edgeColor, 1.5f);
                } else {
                    draw.addLine(corners[0], corners[1], corners[4], corners[5], edgeColor, 1.5f);
                }
            }
        }
    }

    private String tooltip(TerrainEditingController controller) {
        if (tool == Tool.PAINT) {
            return controller.getPaint() == null
                    ? "The layers are still being read"
                    : "Choose a layer to paint in the panel";
        }
        if (isQuadBrush() && controller.getQuads() == null) {
            return controller.getQuadsError().isEmpty()
                    ? "The cells are still being read"
                    : "The cells cannot be changed: " + controller.getQuadsError();
        }
        return "";
    }

    private float @Nullable [] drawOutline(
            ScreenProjection projection, OpenGround ground, float centerX, float centerY, int color) {
        return drawCircle(projection, ground, centerX, centerY, radius.get(), color, 2, true);
    }

    private void drawReadout(float @Nullable [] ring, boolean inverse) {
        if (ring == null) {
            return;
        }
        var size = EditorUi.unitsAndMeters(Math.round(radius.get() * sampleSpacing(viewport.getController())));
        String power;
        if (tool == Tool.PAINT) {
            power = String.format(Locale.ROOT, "%.2f", strength.get());
        } else if (sculptBrush == SculptBrush.RAISE) {
            power = (inverse ? "-" : "") + Math.round(speed.get()) + " per s";
        } else if (sculptBrush == SculptBrush.SET_HEIGHT) {
            power = "full";
        } else if (isQuadBrush()) {
            power = sculptBrush == SculptBrush.HOLES ? inverse ? "show" : "hide" : inverse ? "as saved" : "turn";
        } else {
            power = String.format(Locale.ROOT, "%.2f", strength.get());
        }
        drawTag(ring, size + " · " + power);
    }

    private void drawWeights(
            ScreenProjection projection, Info info, TerrainHeightField ground, TerrainEditingController controller) {
        var paint = controller.getPaint();
        if (paint == null || paintLayer < 0) {
            return;
        }
        var cells = (info.width() - 1) / WEIGHT_CELL;
        var lattice = new float[(cells + 1) * (cells + 1) * 2];
        var shown = new boolean[(cells + 1) * (cells + 1)];
        var toWorld = info.toWorld();
        for (var gy = 0; gy <= cells; gy++) {
            for (var gx = 0; gx <= cells; gx++) {
                var sx = gx * WEIGHT_CELL;
                var sy = gy * WEIGHT_CELL;
                var world = toWorld.transform(sx, sy, ground.getHeightAt(sx, sy));
                var index = gy * (cells + 1) + gx;
                shown[index] = projection.project(world.x(), world.y(), world.z(), lattice, index * 2);
            }
        }
        var draw = ImGui.getBackgroundDrawList();
        var width = ImGui.getIO().getDisplaySizeX();
        var height = ImGui.getIO().getDisplaySizeY();
        for (var gy = 0; gy < cells; gy++) {
            for (var gx = 0; gx < cells; gx++) {
                var a = gy * (cells + 1) + gx;
                var b = a + 1;
                var c = a + cells + 2;
                var d = a + cells + 1;
                if (!shown[a] || !shown[b] || !shown[c] || !shown[d]) {
                    continue;
                }
                var minX = Math.min(Math.min(lattice[a * 2], lattice[b * 2]), Math.min(lattice[c * 2], lattice[d * 2]));
                var maxX = Math.max(Math.max(lattice[a * 2], lattice[b * 2]), Math.max(lattice[c * 2], lattice[d * 2]));
                var minY = Math.min(
                        Math.min(lattice[a * 2 + 1], lattice[b * 2 + 1]),
                        Math.min(lattice[c * 2 + 1], lattice[d * 2 + 1]));
                var maxY = Math.max(
                        Math.max(lattice[a * 2 + 1], lattice[b * 2 + 1]),
                        Math.max(lattice[c * 2 + 1], lattice[d * 2 + 1]));
                if (maxX < 0 || minX > width || maxY < 0 || minY > height) {
                    continue;
                }
                var weight = paint.getWeightAt(paintLayer, (gx + .5f) * WEIGHT_CELL, (gy + .5f) * WEIGHT_CELL);
                if (weight < 8) {
                    continue;
                }
                draw.addQuadFilled(
                        lattice[a * 2],
                        lattice[a * 2 + 1],
                        lattice[b * 2],
                        lattice[b * 2 + 1],
                        lattice[c * 2],
                        lattice[c * 2 + 1],
                        lattice[d * 2],
                        lattice[d * 2 + 1],
                        EditorUi.fill(HudWidgets.ACCENT, .6f * weight / 255f));
            }
        }
    }

    private boolean drawRampTool(
            ScreenProjection projection, OpenGround open, TerrainDocument under, Vector hit, boolean picking) {
        var io = ImGui.getIO();
        var raw = rawAt(open, hit.x(), hit.y());
        var pointer = new RampPoint(hit.x(), hit.y(), raw);
        var controller = viewport.getController();
        var start = rampOwner == null ? null : controller.findDocument(rampOwner);
        var end = pointer;
        if (rampStart != null && start != null && start != under) {
            var link = TerrainTileLink.of(under.getInfo(), start.getInfo());
            end = link.fits() ? link.toRampPoint(pointer) : null;
        }
        var startGround = start == null ? null : ground(start);
        if (rampStart != null && startGround != null) {
            drawRamp(projection, openGround(controller, start, startGround), rampStart, end);
        }
        var point = new float[2];
        var world = open.getBase().toWorld().transform(hit.x(), hit.y(), raw);
        if (projection.project(world.x(), world.y(), world.z(), point, 0)) {
            ImGui.getBackgroundDrawList().addCircleFilled(point[0], point[1], 4, HudWidgets.ACCENT);
        }
        if (picking) {
            HudWidgets.showTooltip("Click to pick this height, "
                    + Math.round(open.getWorldAt(hit.x(), hit.y()).z()) + " units");
            if (ImGui.isMouseClicked(0) && !io.getWantCaptureMouse()) {
                pick(controller, open, hit.x(), hit.y());
            }
            return true;
        }
        HudWidgets.showTooltip(rampStart == null ? "Click the start of the ramp" : "Click the end of the ramp");
        if (ImGui.isMouseClicked(0) && !io.getWantCaptureMouse() && !viewport.isBusy()) {
            if (rampStart == null) {
                rampStart = pointer;
                rampOwner = under.getOwner();
            } else if (end != null && rampOwner != null) {
                var from = rampStart;
                var to = end;
                var owner = rampOwner;
                rampStart = null;
                rampOwner = null;
                var width = rampWidth.get() / sampleSpacing(controller);
                var shape = falloff;
                run(() -> viewport.ramp(owner, from, to, width, shape, SculptBrush.RAMP.step));
            }
        }
        return true;
    }

    private boolean drawSelectTool(ScreenProjection projection, OpenGround open, @Nullable Vector hit) {
        var info = open.getBase();
        var ground = open.getField();
        var io = ImGui.getIO();
        var controller = viewport.getController();
        var selection = select.getSelection(controller.getSelectedOwner(), info);
        var spacing = sampleSpacing(controller);
        select.drawDots(
                projection,
                info,
                ground,
                selectDrag == SelectDrag.MOVE && moveWeights != null
                        ? moveWeights
                        : select.computeWeights(selection, spacing));
        if (dragging && selectDrag == SelectDrag.MOVE) {
            var units = (moveStartY - ImGui.getMousePosY()) * unitsPerPixel;
            pendingMove = units / rawUnit(info);
            HudWidgets.showTooltip(String.format(Locale.ROOT, "%+.0f units", units));
            return true;
        }
        if (hit == null) {
            return true;
        }
        var ring = drawOutline(projection, open, hit.x(), hit.y(), HudWidgets.ACCENT);
        if (ring != null) {
            var size = EditorUi.unitsAndMeters(Math.round(radius.get() * spacing));
            drawTag(ring, size + " · " + (io.getKeyAlt() ? "move" : io.getKeyShift() ? "deselect" : "select"));
        }
        if (ImGui.isMouseClicked(0) && !dragging && !io.getWantCaptureMouse()) {
            if (io.getKeyAlt()) {
                if (selection.isEmpty() || viewport.isBusy()) {
                    return true;
                }
                dragging = true;
                strokeOwner = controller.getSelectedOwner();
                selectDrag = SelectDrag.MOVE;
                strokeLabel = SculptBrush.SELECT.step;
                moveStartY = ImGui.getMousePosY();
                unitsPerPixel = unitsPerPixel(projection, info, ground, hit);
                moveWeights = select.computeWeights(selection, spacing).clone();
                return true;
            }
            dragging = true;
            strokeOwner = controller.getSelectedOwner();
            selectDrag = io.getKeyShift() ? SelectDrag.REMOVE : SelectDrag.ADD;
            strokeLabel = "";
            lastX = hit.x();
            lastY = hit.y();
            brushSelection(selection, hit.x(), hit.y());
        } else if (dragging && ImGui.isMouseDown(0) && selectDrag != SelectDrag.NONE) {
            var distance = (float) Math.hypot(hit.x() - lastX, hit.y() - lastY);
            var steps = Math.max(1, (int) Math.ceil(distance / Math.max(.25f, radius.get() * .25f)));
            for (var step = 1; step <= steps; step++) {
                var t = step / (float) steps;
                brushSelection(selection, lastX + (hit.x() - lastX) * t, lastY + (hit.y() - lastY) * t);
            }
            lastX = hit.x();
            lastY = hit.y();
        }
        return true;
    }

    private void brushSelection(TerrainSoftSelection selection, float x, float y) {
        if (selectDrag == SelectDrag.REMOVE) {
            selection.remove(x, y, radius.get());
        } else {
            selection.add(x, y, radius.get());
        }
    }

    private void drawRamp(ScreenProjection projection, OpenGround ground, RampPoint from, @Nullable RampPoint to) {
        var draw = ImGui.getBackgroundDrawList();
        var point = new float[4];
        var info = ground.getBase();
        var start = info.toWorld().transform(from.x(), from.y(), from.height());
        if (projection.project(start.x(), start.y(), start.z(), point, 0)) {
            draw.addCircleFilled(point[0], point[1], 5, HudWidgets.ACCENT);
            draw.addCircle(point[0], point[1], 7, ImColor.rgba(8, 14, 20, 200), 0, 2);
        }
        if (to == null) {
            return;
        }
        var dx = to.x() - from.x();
        var dy = to.y() - from.y();
        var length = (float) Math.hypot(dx, dy);
        if (length < 1e-3f) {
            return;
        }
        var half = rampWidth.get() / sampleSpacing(viewport.getController()) * .5f;
        var sideX = -dy / length * half;
        var sideY = dx / length * half;
        for (var side = -1; side <= 1; side++) {
            var previous = false;
            for (var step = 0; step <= 24; step++) {
                var t = step / 24f;
                var x = from.x() + dx * t + sideX * side;
                var y = from.y() + dy * t + sideY * side;
                var world = side == 0
                        ? info.toWorld().transform(x, y, from.height() + (to.height() - from.height()) * t)
                        : ground.getWorldAt(x, y);
                var shown = projection.project(world.x(), world.y(), world.z(), point, 2);
                if (shown && previous) {
                    draw.addLine(
                            point[0],
                            point[1],
                            point[2],
                            point[3],
                            side == 0 ? HudWidgets.ACCENT : EditorUi.fill(HudWidgets.ACCENT, .5f),
                            side == 0 ? 2 : 1);
                }
                previous = shown;
                point[0] = point[2];
                point[1] = point[3];
            }
        }
    }

    private long swatch(String texture) {
        if (library == null || texture.isEmpty()) {
            return 0;
        }
        var entry = library.getMaterial(texture);
        return entry == null ? 0 : library.getSwatch(entry);
    }

    private String swatchWait(long texture) {
        if (texture != 0 || library == null || library.areMaterialsReady()) {
            return "";
        }
        var progress = library.getMaterialProgress();
        return "\nIts picture shows once the materials of the client are read: " + progress[0] + " of " + progress[1]
                + " packages.";
    }

    private void run(Runnable action) {
        try {
            action.run();
            feedback = "";
        } catch (RuntimeException failure) {
            feedback = UserMessages.plain(failure.getMessage());
        }
    }

    private @Nullable String terrainMapAt(Camera camera, ViewerRenderBackend backend, float nx, float ny) {
        var hit = backend.pickDebugSceneObject(nx, ny, Float.MAX_VALUE, EnumSet.allOf(Category.class));
        if (hit == null || hit.object().category() != Category.TERRAIN) {
            return null;
        }
        var origin = camera.getCursorRayOrigin(nx, ny);
        var direction = camera.getCursorRayDirection(nx, ny);
        return session.getMapAt(
                origin.getX() + direction.getX() * hit.distance(), origin.getY() + direction.getY() * hit.distance());
    }

    private static List<float[]> changedSectors(TerrainDocument document) {
        var changes = document.getHistory().getSourceChanges();
        var width = changes.getWidth();
        var sectors = new TreeSet<Integer>();
        for (var i = 0; i < changes.size(); i++) {
            sectors.add(changes.getIndex(i) / width / 16 * 16 + changes.getIndex(i) % width / 16);
        }
        var toWorld = document.getInfo().toWorld();
        var rects = new ArrayList<float[]>();
        for (var sector : sectors) {
            var a = toWorld.transform(sector % 16 * 16, sector / 16 * 16, 0);
            var b = toWorld.transform(sector % 16 * 16 + 16, sector / 16 * 16 + 16, 0);
            rects.add(new float[] {
                Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.max(a.x(), b.x()), Math.max(a.y(), b.y())
            });
        }
        return rects;
    }

    private static long heightKey(TerrainEditingController controller) {
        var key = 17L;
        for (var document : controller.getDocuments()) {
            key = key * 31 + System.identityHashCode(document.getHistory());
            key = key * 31 + document.getHistory().getRevision();
        }
        return key;
    }

    private static boolean hasHeightStroke(TerrainEditingController controller) {
        for (var document : controller.getDocuments()) {
            if (document.getHistory().isStrokeActive()) {
                return true;
            }
        }
        return false;
    }

    private static List<TerrainChange> terrainChanges(TerrainDocument document) {
        var owner = document.getOwner();
        var map = document.getMap();
        var paint = document.getPaint();
        var layers = document.getMapping();
        var bits = document.getQuads();
        var rows = new ArrayList<TerrainChange>();
        var samples = document.getHistory().getSourceChanges().size();
        if (samples > 0) {
            rows.add(new TerrainChange(
                    EditingSession.shortName(owner),
                    String.format(
                            Locale.ROOT, "Sculpted, %,d %s · %s", samples, samples == 1 ? "point" : "points", map),
                    union(changedSectors(document)),
                    -1,
                    ChangeKind.HEIGHTS,
                    owner));
        }
        if (paint != null) {
            var info = document.getInfo();
            for (var edit : paint.getSourceChanges()) {
                var layer = edit.layer();
                var scaleX = info.width() / (float) layer.width();
                var scaleY = info.height() / (float) layer.height();
                var a = info.toWorld().transform((edit.x0() + .5f) * scaleX - .5f, (edit.y0() + .5f) * scaleY - .5f, 0);
                var b = info.toWorld().transform((edit.x1() + .5f) * scaleX - .5f, (edit.y1() + .5f) * scaleY - .5f, 0);
                rows.add(new TerrainChange(
                        shortName(layer.texture()),
                        String.format(
                                Locale.ROOT,
                                "Painted, %,d %s · %s",
                                edit.changedTexels(),
                                edit.changedTexels() == 1 ? "pixel" : "pixels",
                                map),
                        new float[] {
                            Math.min(a.x(), b.x()),
                            Math.min(a.y(), b.y()),
                            Math.max(a.x(), b.x()),
                            Math.max(a.y(), b.y())
                        },
                        layer.index(),
                        ChangeKind.WEIGHTS,
                        owner));
            }
        }
        var density = document.getDecorations();
        var decorations = document.getDecorationSources();
        if (density != null && decorations != null) {
            var info = document.getInfo();
            for (var edit : density.getSourceChanges()) {
                var layer = edit.layer();
                var scaleX = info.width() / (float) layer.width();
                var scaleY = info.height() / (float) layer.height();
                var a = info.toWorld().transform((edit.x0() + .5f) * scaleX - .5f, (edit.y0() + .5f) * scaleY - .5f, 0);
                var b = info.toWorld().transform((edit.x1() + .5f) * scaleX - .5f, (edit.y1() + .5f) * scaleY - .5f, 0);
                var readers = decorations.decorations().stream()
                        .filter(decoration -> decoration.slot() == layer.index())
                        .toList();
                var also = readers.isEmpty() ? "" : readers.getFirst().getAlsoChanges();
                rows.add(new TerrainChange(
                        readers.isEmpty() ? shortName(layer.weightTexture()) : decorationName(readers.getFirst()),
                        String.format(
                                        Locale.ROOT,
                                        "Painted decorations, %,d %s · %s",
                                        edit.changedTexels(),
                                        edit.changedTexels() == 1 ? "pixel" : "pixels",
                                        map)
                                + (also.isEmpty() ? "" : " · also " + also),
                        new float[] {
                            Math.min(a.x(), b.x()),
                            Math.min(a.y(), b.y()),
                            Math.max(a.x(), b.x()),
                            Math.max(a.y(), b.y())
                        },
                        layer.index(),
                        ChangeKind.DECORATIONS,
                        owner));
            }
        }
        if (layers != null) {
            var info = document.getInfo();
            var a = info.toWorld().transform(0, 0, 0);
            var b = info.toWorld().transform(info.width() - 1, info.height() - 1, 0);
            var whole = new float[] {
                Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.max(a.x(), b.x()), Math.max(a.y(), b.y())
            };
            for (var layer : layers.getSourceChanges().keySet()) {
                rows.add(new TerrainChange(
                        layerName(paint, layer),
                        "New texture mapping · " + map,
                        whole,
                        layer,
                        ChangeKind.MAPPING,
                        owner));
            }
        }
        if (bits != null && bits.hasSourceChanges()) {
            var changed = bits.getChangedVisibility();
            changed.or(bits.getChangedTurns());
            var info = document.getInfo();
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (var quad = changed.nextSetBit(0); quad >= 0; quad = changed.nextSetBit(quad + 1)) {
                for (var corner = 0; corner < 4; corner++) {
                    var point = info.toWorld()
                            .transform(quad % info.width() + (corner & 1), quad / info.width() + (corner >> 1), 0);
                    minX = Math.min(minX, point.x());
                    minY = Math.min(minY, point.y());
                    maxX = Math.max(maxX, point.x());
                    maxY = Math.max(maxY, point.y());
                }
            }
            var count = changed.cardinality();
            rows.add(new TerrainChange(
                    "Holes and edges of " + EditingSession.shortName(owner),
                    String.format(Locale.ROOT, "%,d %s · %s", count, count == 1 ? "cell" : "cells", map),
                    new float[] {minX, minY, maxX, maxY},
                    -1,
                    ChangeKind.QUADS,
                    owner));
        }
        return rows;
    }

    private static void closeOnEscape() {
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false) && !ImGui.isAnyItemActive()) {
            ImGui.closeCurrentPopup();
        }
    }

    private static boolean titleRow(String title, String id, Glyph glyph, boolean on, String buttonTip) {
        return titleRow(title, null, id, glyph, on, true, buttonTip);
    }

    private static boolean titleRow(
            String title,
            @Nullable String term,
            String id,
            Glyph glyph,
            boolean on,
            boolean enabled,
            String buttonTip) {
        var button = HudWidgets.CONTROL_HEIGHT;
        var lift = (button - ImGui.getFontSize()) * .5f;
        GeodataWidgets.space(GeodataWidgets.SECTION - lift);
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        ImGui.getWindowDrawList().addText(x, y + lift - 1, HudWidgets.MUTED, title);
        if (term != null) {
            ImGui.setCursorScreenPos(x + ImGui.calcTextSizeX(title) + 4, y + lift - 1);
            HudWidgets.helpMark(term);
        }
        ImGui.setCursorScreenPos(x + width - button, y);
        ImGui.beginDisabled(!enabled);
        var clicked = HudWidgets.iconButton(id, glyph, on, HudWidgets.TEXT);
        tip(buttonTip);
        ImGui.endDisabled();
        ImGui.setCursorScreenPos(x, y + button);
        ImGui.dummy(width, 0);
        return clicked;
    }

    private static String joined(List<String> owners) {
        if (owners.size() <= 1) {
            return owners.isEmpty() ? "" : owners.getFirst();
        }
        return String.join(", ", owners.subList(0, owners.size() - 1)) + " and " + owners.getLast();
    }

    private static String layerDetail(Layer layer) {
        return layer.isPaintable() ? layer.width() + "×" + layer.height() + " weights" : "read-only";
    }

    private static String layerName(@Nullable TerrainPaintHistory paint, int layer) {
        if (paint != null
                && layer >= 0
                && layer < paint.getLayers().size()
                && !paint.getLayers().get(layer).texture().isEmpty()) {
            return shortName(paint.getLayers().get(layer).texture());
        }
        return "Layer " + layer;
    }

    private static String withMovingDots(String status) {
        return status.replace("...", "") + ".".repeat((int) (ImGui.getTime() * 3) % 4);
    }

    private static int rawAt(OpenGround ground, float x, float y) {
        var world = ground.getWorldAt(x, y);
        return Math.round(ground.getBase()
                .toHeightmap()
                .transform(world.x(), world.y(), world.z())
                .z());
    }

    private static @Nullable Vector intersect(Info info, TerrainHeightField ground, Vec3 origin, Vec3 direction) {
        var toHeightmap = info.toHeightmap();
        var start = toHeightmap.transform(origin.getX(), origin.getY(), origin.getZ());
        var end = toHeightmap.transform(
                origin.getX() + direction.getX(), origin.getY() + direction.getY(), origin.getZ() + direction.getZ());
        return ground.intersect(start, new Vector(end.x() - start.x(), end.y() - start.y(), end.z() - start.z()));
    }

    private static float @Nullable [] drawCircle(
            ScreenProjection projection,
            OpenGround ground,
            float centerX,
            float centerY,
            float circleRadius,
            int color,
            float thickness,
            boolean center) {
        var draw = ImGui.getBackgroundDrawList();
        var point = new float[4];
        var previous = false;
        var right = -Float.MAX_VALUE;
        var top = Float.MAX_VALUE;
        var bottom = -Float.MAX_VALUE;
        for (var i = 0; i <= CIRCLE_POINTS; i++) {
            var angle = Math.PI * 2 * i / CIRCLE_POINTS;
            var world = ground.getWorldAt(
                    centerX + circleRadius * (float) Math.cos(angle), centerY + circleRadius * (float) Math.sin(angle));
            var shown = projection.project(world.x(), world.y(), world.z(), point, 2);
            if (shown) {
                right = Math.max(right, point[2]);
                top = Math.min(top, point[3]);
                bottom = Math.max(bottom, point[3]);
            }
            if (shown && previous) {
                draw.addLine(point[0], point[1], point[2], point[3], ImColor.rgba(8, 14, 20, 170), thickness + 2);
                draw.addLine(point[0], point[1], point[2], point[3], color, thickness);
            }
            previous = shown;
            point[0] = point[2];
            point[1] = point[3];
        }
        var middle = ground.getWorldAt(centerX, centerY);
        if (center && projection.project(middle.x(), middle.y(), middle.z(), point, 0)) {
            draw.addCircleFilled(point[0], point[1], 3, color);
        }
        return right == -Float.MAX_VALUE ? null : new float[] {right, (top + bottom) * .5f};
    }

    private static void drawTag(float[] ring, String text) {
        var draw = ImGui.getBackgroundDrawList();
        var x = ring[0] + 14;
        var y = ring[1] - ImGui.getFontSize() * .5f - 3;
        draw.addRectFilled(
                x - 6,
                y,
                x + ImGui.calcTextSizeX(text) + 6,
                y + ImGui.getFontSize() + 6,
                ImColor.rgba(10, 16, 22, 205),
                6f);
        draw.addText(x, y + 3, HudWidgets.TEXT, text);
    }

    private static float unitsPerPixel(ScreenProjection projection, Info info, TerrainHeightField ground, Vector hit) {
        var world = info.toWorld().transform(hit.x(), hit.y(), (float) ground.getSurfaceAt(hit.x(), hit.y()));
        var points = new float[4];
        if (!projection.project(world.x(), world.y(), world.z(), points, 0)
                || !projection.project(world.x(), world.y(), world.z() + 100, points, 2)) {
            return 4;
        }
        var pixels = (float) Math.hypot(points[2] - points[0], points[3] - points[1]);
        return pixels < 1 ? 4 : Math.min(400, 100 / pixels);
    }

    private static float[] union(List<float[]> rects) {
        var result = new float[] {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (var rect : rects) {
            result[0] = Math.min(result[0], rect[0]);
            result[1] = Math.min(result[1], rect[1]);
            result[2] = Math.max(result[2], rect[2]);
            result[3] = Math.max(result[3], rect[3]);
        }
        return rects.isEmpty() ? new float[4] : result;
    }

    private static float sampleSpacing(TerrainEditingController controller) {
        return TerrainEditingController.sampleSpacing(controller.getOwnerInfo());
    }

    private static float rawUnit(Info info) {
        return TerrainEditingController.rawUnit(info);
    }

    private static String shortName(String path) {
        if (path.isEmpty()) {
            return "no texture";
        }
        return path.substring(path.lastIndexOf('.') + 1);
    }

    private static Path findMap(Path root, EditingMaps files, String name) throws IOException {
        return TerrainEditingController.mapFile(root, files.getSource(name));
    }

    private static String decorationName(Decoration decoration) {
        return decoration.index() + " · " + shortName(decoration.mesh());
    }
}
