package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geodata.GeoCellRef;
import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Layer;
import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.core.geodata.GeoDataRegion;
import com.rost.cruma.core.geodata.GeoDataWriter;
import com.rost.cruma.core.geodata.GeoEditCheck;
import com.rost.cruma.core.geodata.GeoEditDocument.CellKey;
import com.rost.cruma.core.geodata.GeoFormat;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.GeoQueryEngine.Direction;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.core.geodata.PathNodeGraph;
import com.rost.cruma.core.geodata.PathNodeGraph.ZoneTable;
import com.rost.cruma.viewer.geodata.GeoClientRebuild;
import com.rost.cruma.viewer.geodata.GeoDataPicker.Hit;
import com.rost.cruma.viewer.geodata.GeoEditingSession;
import com.rost.cruma.viewer.geodata.GeoEditingSession.Clip;
import com.rost.cruma.viewer.geodata.GeoEditingSession.EdgeRef;
import com.rost.cruma.viewer.geodata.GeoEditingSession.HeightRule;
import com.rost.cruma.viewer.geodata.GeoEditingSession.HeightRule.Offset;
import com.rost.cruma.viewer.geodata.GeoEditingSession.HeightRule.Slope;
import com.rost.cruma.viewer.geodata.GeoEditingSession.HeightRule.Smooth;
import com.rost.cruma.viewer.geodata.GeoEditingSession.LayerEdit;
import com.rost.cruma.viewer.geodata.GeoInspection;
import com.rost.cruma.viewer.geodata.GeoInspection.Selection;
import com.rost.cruma.viewer.geodata.GeoRegionCatalog;
import com.rost.cruma.viewer.geodata.GeodataBuildJob.StartSources;
import com.rost.cruma.viewer.geodata.GeodataEditIo;
import com.rost.cruma.viewer.geodata.GeodataEditIo.Operation;
import com.rost.cruma.viewer.geodata.GeodataEditReview;
import com.rost.cruma.viewer.geodata.GeodataEditReview.SpawnImpact;
import com.rost.cruma.viewer.geodata.GeodataEditReview.SpawnProblem;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.GeodataState;
import com.rost.cruma.viewer.ui.FindingWidgets.Severity;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import glm_.vec2.Vec2;
import glm_.vec3.Vec3;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class GeodataEditingPanel implements AutoCloseable {
    static final int REVIEW_CARDS = 4;

    static final String SERVER_HELP = "Pick by the server program, not the chronicle: Interlude runs on both.\n\n"
        + "320 zones: the C1 and C4 server builds, and Interlude packs built on the C4 server, such as Vanganth. They"
        + " have no zone for tile Y 26, so a build leaves those regions out of geo_index.txt and the graph; their"
        + " files stay.\n\n"
        + "340 zones: the Gracia Final server build and later, AdvExt's Interlude included. These load tile Y 26.\n\n"
        + "A server stops loading paths at the other kind.";
    static final String PATCH_HELP = "A patch is a small JSON file with only your changed cells. It leaves the geodata "
            + "untouched. Load it later into the same files to keep working, or share it.";

    static final int SLOPE_FOLLOW = 64;

    static final int EDGE_STROKE_HEIGHT = 48;

    static final int MARKED_CELLS = 8192;

    static final float MARK_DISTANCE = 6000;

    static final int REVIEW_ROWS = 50;

    static final int AREA_TOLERANCE = 32;

    static final double FLOOR_REACH = 4096;

    private static final int TOOLS_IN_ROW = 3;

    private static final String[] ZONE_TABLE_LABELS = {"Auto", "320 zones", "340 zones"};
    private static final String[] FORMAT_LABELS = {"L2OFF", "L2J"};

    private static final String REPLACE_POPUP = "Replace files##geodata-export";

    private static final int REPLACE_LISTED = 6;

    private final GeodataEditIo files = new GeodataEditIo();
    private @Nullable GeoClientRebuild rebuilder;
    private @Nullable RebuildStroke pendingRebuild;
    private @Nullable GeoTile pendingRegion;
    private final HashSet<GeoTile> rebuiltRegions = new HashSet<>();
    private final GeodataEditReview review = new GeodataEditReview();
    private GeodataSpawnLink spawns = GeodataSpawnLink.NONE;
    private final ImInt height = new ImInt();
    private final ImInt paintHeight = new ImInt();
    private final int[] tolerance = {16};
    private final int[] rebuildTolerance = {64};
    private final int[] floorTolerance = {GeoEditingSession.STEP_HEIGHT};
    private HeightMode heightMode = HeightMode.FLAT;
    private final ImInt stepHeight = new ImInt(0);
    private final ImInt raiseBy = new ImInt(8);
    private GeoPoint rectangleStartOf;
    private GeoPoint rectangleEndOf;

    private final int[] brushRadius = {32};
    private final ImBoolean replace = new ImBoolean(false);
    private final FolderPicker exportPicker = new FolderPicker(
            "Choose where to export", "The new folder is created inside the folder you select.", false);
    private final FolderPicker patchPicker = new FolderPicker(
            "Choose where to save the patch", "The patch file is saved in the folder you select.", false);
    private final ImBoolean backup = new ImBoolean(true);
    private final ImBoolean pathNodes = new ImBoolean(false);
    private final ImBoolean exportPathNodes = new ImBoolean(false);
    private GeoPoint flyTo;
    private final ImString patchPath = new ImString(2048);
    private final ImString exportPath = new ImString(2048);
    private int zoneTableChoice;
    private Path zoneTableSource;
    private long zoneTableEpoch;
    private ZoneTable autoTable;
    private String autoZoneTable = "";
    private final ArrayList<GeoPoint> stroke = new ArrayList<>();
    private boolean rectangleShape;
    private boolean strokeRectangle;
    private boolean shiftHeld;
    private boolean altHeld;
    private GeoPoint rectangleStart;
    private GeoPoint rectangleEnd;
    private final LinkedHashMap<String, PendingEdge> edgeStroke = new LinkedHashMap<>();
    private final ImBoolean markChanges = new ImBoolean(true);
    private final ArrayList<int[]> markedCells = new ArrayList<>();
    private long markRevision = Long.MIN_VALUE;
    private boolean edgeOpen;
    private boolean edgeOneSide;
    private int edgeHeight;
    private EditTool tool = EditTool.EDGES;
    private boolean heightFromStroke = true;
    private GeoCellRef strokeSeed;
    private @Nullable GeoPoint rayOrigin;
    private @Nullable GeoPoint rayDirection;
    private @Nullable GeoPoint floorPointer;
    private GeoCellRef heightEditRef;
    private Path defaultsFor;

    private GeoFormat exportFormat;

    private List<String> pendingReplace = List.of();

    private GeoFormat pendingFormat;
    private boolean pendingPathNodes;

    private boolean askReplace;

    private List<String> replacedFiles = List.of();
    private String replacedKey = "";
    private long replacedAt;

    private boolean filesOpen;

    private boolean revealFiles;

    private int strokeHeight;
    private String message = "";
    private String done = "";

    private String fileMessage = "";
    private String fileDone = "";

    private @Nullable Path fileWritten;

    private Operation fileOperation;
    private Leave pendingLeave;
    private Leave approvedLeave;
    private boolean saveThenLeave;
    private float guardRowX;
    private float guardRowY;
    private float guardRowWidth;
    private float guardSaveX;
    private float guardSaveY;
    private long diffRevision = Long.MIN_VALUE;
    private List<CellKey> diffCells = List.of();
    private Map<GeoTile, Integer> diffRegions = Map.of();
    private int diffPage;

    public enum Leave {
        SOURCE,
        END_EDITING,
        MENU,
        CLOSE
    }

    public enum EditTool {
        EDGES(
                "Edges",
                "Opens or blocks walking across one side of a cell, like a fence or a gap in it. Point near a side"
                    + " until it lights up, then click, or drag along cells to set a whole wall in one step. A click"
                    + " in the middle of a cell selects it. Shift changes only this cell's side, which makes a one-way"
                    + " edge."),
        BLOCK(
                "Block",
                "Drag to paint obstacles: cells nobody can stand on, as under a rock or a wall. Their "
                        + "neighbors are blocked at the shared edge too."),
        OPEN(
                "Open",
                "Drag to paint walkable ground, for example to remove a wrong obstacle. Sides toward walkable "
                        + "neighbors open both ways."),
        FLOOR(
                "Floor",
                "Press on a floor and drag over cells that have none at its height, as through a doorway or along"
                        + " a bridge. Each gets a walkable layer at that height, open toward walkable neighbors. Layers"
                        + " above and below stay."),
        HEIGHT(
                "Height",
                "Drag to change the height of the painted ground: one flat height, an even slope or stairs, smoother"
                        + " ground, or up and down by an amount. Use it where the geodata floor sits above or below the"
                        + " client ground, so characters would float or sink."),
        REBUILD(
                "Rebuild",
                "Drag over cells to build them again from the client's maps, with the same builder as Build. Only"
                    + " layers near the height of the first click change, so floors above and below stay. The first"
                    + " stroke in a region builds it, which takes a few seconds; later strokes there apply at once.");

        final String label;
        final String help;

        EditTool(String label, String help) {
            this.label = label;
            this.help = help;
        }

        boolean isBrush() {
            return this != EDGES;
        }
    }

    static String startPointsNote(StartSources sources) {
        var npcpos = sources.npcpos();
        return npcpos == null ? "" : " Like Build, it also starts from the NPC spawns of " + npcpos.getFileName() + ".";
    }

    static String shortFolder(Path folder, float width) {
        var text = folder.toString();
        var home = System.getProperty("user.home");
        if (home != null && !home.isBlank() && folder.startsWith(Path.of(home))) {
            text = "~" + text.substring(home.length());
        }
        if (ImGui.calcTextSizeX(text) <= width || folder.getNameCount() < 3) {
            return text;
        }
        return "\u2026" + folder.getFileSystem().getSeparator()
                + folder.subpath(folder.getNameCount() - 2, folder.getNameCount());
    }

    public static Direction edgeAt(GeoPoint position, GeoCellRef cell) {
        return GeodataWidgets.sideAt(
                (float) (position.x() - cell.x() - 8) / 8, (float) (position.y() - cell.y() - 8) / 8);
    }

    static void drawOutline(
            Camera camera,
            ImDrawList draw,
            float minX,
            float minY,
            float maxX,
            float maxY,
            float z,
            int color,
            int width,
            int height) {
        var a = camera.projectToScreenRaw(new Vec3(minX, minY, z), width, height);
        var b = camera.projectToScreenRaw(new Vec3(maxX, minY, z), width, height);
        var c = camera.projectToScreenRaw(new Vec3(maxX, maxY, z), width, height);
        var d = camera.projectToScreenRaw(new Vec3(minX, maxY, z), width, height);
        if (a == null || b == null || c == null || d == null) {
            return;
        }
        draw.pathLineTo(a.getX(), a.getY());
        draw.pathLineTo(b.getX(), b.getY());
        draw.pathLineTo(c.getX(), c.getY());
        draw.pathLineTo(d.getX(), d.getY());
        draw.pathStroke(color, 1, 2);
    }

    static String layerText(List<Layer> layers) {
        if (layers.isEmpty()) {
            return "no layer";
        }
        var text = new StringBuilder();
        for (var layer : layers) {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append("Z ").append(layer.height()).append(' ');
            for (var direction : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
                text.append((layer.nswe() & direction.getMask()) != 0 ? GeodataWidgets.letterOf(direction) : "\u00b7");
            }
            if (text.length() > 60) {
                text.append(" \u2026");
                break;
            }
        }
        return text.toString();
    }

    enum AreaAction {
        OPEN_ALL,
        BLOCK_ALL,
        SET_HEIGHT,
        DELETE_LAYER
    }

    public boolean isRectangleShape() {
        return rectangleShape;
    }

    public EditTool getTool() {
        return tool;
    }

    public void setTool(EditTool next) {
        tool = next;
        cancelStroke();
    }

    public void setBrushEnabled(boolean enabled) {
        setTool(enabled ? EditTool.OPEN : EditTool.EDGES);
    }

    public boolean isBrushActive(GeodataState state) {
        return tool.isBrush() && state.queryData() != null && !files.isBusy() && pendingLeave == null;
    }

    public void draw(GeodataState state) {
        poll(state);
        var session = state.editing;
        drawSessionBar(state);
        if (state.editing != session) {
            return;
        }
        if (session != null) {
            drawExperimentalWarning();
        }
        var tools = EditTool.values();
        for (var first = 0; first < tools.length; first += TOOLS_IN_ROW) {
            var labels = new String[Math.min(TOOLS_IN_ROW, tools.length - first)];
            for (var index = 0; index < labels.length; index++) {
                labels[index] = tools[first + index].label;
            }
            var chosen = tool.ordinal() - first;
            var picked = GeodataWidgets.segmented(
                    "edit-tool-" + first, labels, chosen >= 0 && chosen < labels.length ? chosen : -1);
            if (picked >= 0) {
                setTool(tools[first + picked]);
            }
        }
        ImGui.pushTextWrapPos(0);
        ImGui.textColored(GeodataWidgets.MUTED, tool.help);
        ImGui.popTextWrapPos();
        if (tool.isBrush()) {
            var shape =
                    GeodataWidgets.segmented("edit-shape", new String[] {"Brush", "Rectangle"}, rectangleShape ? 1 : 0);
            if (shape >= 0) {
                rectangleShape = shape == 1;
            }
            GeodataWidgets.tip(
                    "Brush paints round strokes. Rectangle paints every cell between where you press and where you "
                            + "release. Holding Shift while pressing draws a rectangle with the brush too.");
            if (!rectangleShape) {
                GeodataFields.slider(
                        "Brush radius",
                        brushRadius,
                        8,
                        512,
                        "Radius in world units. One cell is 16 units. [ and ] change it.");
            }
            GeodataFields.slider(
                    "Height tolerance",
                    tolerance(),
                    0,
                    256,
                    switch (tool) {
                        case REBUILD ->
                            "Layers this close to the height under the first click are replaced by the client's"
                                    + " layers this close. Floors above and below stay untouched.";
                        case FLOOR ->
                            "A cell with a layer this close to the height under the first click has its floor"
                                    + " already and gets no new one.";
                        default ->
                            "Only layers this close to the height under the first click are painted. Floors above"
                                    + " and below stay untouched.";
                    });
            if (tool == EditTool.REBUILD) {
                rebuildControls(state);
            }
            if (tool == EditTool.HEIGHT) {
                drawHeightControls();
            }
        }
        drawCellEditor(state);
        drawFilesSection(state);
        drawMessages();
    }

    void setRebuilder(GeoClientRebuild rebuilder) {
        this.rebuilder = rebuilder;
    }

    boolean rebuildProgress(String id) {
        if (rebuilder == null) {
            return false;
        }
        var status = rebuilder.getStatus();
        if (status.running() && status.tile() != null) {
            var name = status.tile().x() + "_" + status.tile().y();
            if (GeodataWidgets.progress(
                    id, "Building " + name + " from the client", status.stage(), status.fraction(), "Stop")) {
                rebuilder.cancel();
                pendingRebuild = null;
                pendingRegion = null;
            }
            GeodataWidgets.tip("The region is built in memory. Strokes and the region rebuild apply when it is done.");
            return true;
        }
        return false;
    }

    void rebuildRegion(GeodataState state, GeoTile tile) {
        if (rebuilder == null || !rebuilder.isAvailable()) {
            message = "Rebuild needs the client: open a client to build from its maps.";
            return;
        }
        var built = rebuilder.getBuiltRegion(tile);
        if (built == null) {
            pendingRegion = tile;
            done = "Building " + tile.x() + "_" + tile.y() + " from the client. It loads as an edit when it is done.";
            return;
        }
        pendingRegion = null;
        var changed = replaceRegion(
                state,
                tile,
                built.getRegion(tile.x(), tile.y()),
                "Rebuild " + tile.x() + "_" + tile.y() + " from the client");
        if (changed >= 0) {
            rebuiltRegions.add(tile);
            done = String.format(
                    Locale.ROOT,
                    "Rebuilt %d_%d from the client: %,d cells differ from the source file. Review them, then save.",
                    tile.x(),
                    tile.y(),
                    changed);
        }
    }

    int replaceRegion(GeodataState state, GeoTile tile, GeoDataRegion region, String label) {
        var changed = new int[] {-1};
        message = "";
        applyChange(state, () -> changed[0] = state.editing.replaceRegion(tile, region, label));
        return changed[0];
    }

    int revertRegion(GeodataState state, GeoTile tile) {
        if (state.editing == null) {
            return 0;
        }
        var changed = new int[] {-1};
        message = "";
        applyChange(state, () -> changed[0] = state.editing.revertRegion(tile));
        rebuiltRegions.remove(tile);
        return changed[0];
    }

    @Nullable
    Path exportFolder(GeodataState state) {
        fillDefaultPaths(state);
        try {
            return exportPath.get().isBlank() ? null : Path.of(exportPath.get());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    void setSpawns(GeodataSpawnLink link) {
        spawns = link;
    }

    public boolean saveBarShown(GeodataState state) {
        return files.isBusy() || state.editing != null && state.editing.hasUnsavedChanges();
    }

    public boolean saveBar(GeodataState state) {
        var progress = fileProgress();
        if (progress != null) {
            ImGui.beginDisabled(!files.canCancelExport());
            if (GeodataWidgets.busyBar("geodata-busy-bar", progress.title(), progress.fraction(), "Cancel", true)) {
                fileMessage = files.cancelExport() ? "" : "The files are being put in place, so it can no longer stop.";
            }
            ImGui.endDisabled();
            GeodataWidgets.tip("Cancel removes the temporary files and leaves the folders as they were.");
            return false;
        }
        if (state.editing == null) {
            return false;
        }
        var pressed = GeodataWidgets.saveBar(
                "geodata-save-bar", changedText(state.editing) + " not saved", "Save\u2026", true);
        GeodataWidgets.tip(
                "Opens Save and export: save into this folder, export to another folder in L2OFF or L2J, or keep a"
                        + " patch.");
        if (pressed) {
            filesOpen = true;
            revealFiles = true;
        }
        return pressed;
    }

    public @Nullable String getBusyStatus() {
        var progress = fileProgress();
        return progress == null ? null : progress.title();
    }

    public String getBusyLabel() {
        var operation = files.getActiveOperation();
        if (operation == null) {
            return null;
        }
        return switch (operation) {
            case SAVE_PATCH -> "Saving the patch";
            case LOAD_PATCH -> "Loading the patch";
            case EXPORT -> "Exporting";
            case EXPORT_PATH_NODES -> "Exporting and rebuilding pathnode";
            case SAVE_SOURCE -> "Saving into the geodata folder";
            case SAVE_SOURCE_PATH_NODES -> "Saving and rebuilding pathnode";
        };
    }

    public void setModifiers(boolean shift, boolean alt) {
        shiftHeld = shift;
        altHeld = alt;
    }

    public void setPointerRay(@Nullable GeoPoint origin, @Nullable GeoPoint direction) {
        rayOrigin = origin;
        rayDirection = direction;
    }

    public void handlePointer(
            GeodataState state, List<Hit> hits, boolean pressed, boolean down, boolean released, boolean cancel) {
        if (cancel || !isBrushActive(state)) {
            cancelStroke();
            return;
        }
        if (pressed && !hits.isEmpty() && altHeld && tool == EditTool.HEIGHT) {
            var first = hits.getFirst().cell();
            var layers = state.queryData().cellAt(first.x(), first.y()).layers();
            if (first.layer() >= 0 && first.layer() < layers.size()) {
                paintHeight.set(layers.get(first.layer()).height());
                heightFromStroke = false;
                message = "";
                done = "Height " + paintHeight.get() + " taken from the cell.";
            }
            return;
        }
        if (pressed && !hits.isEmpty()) {
            var first = hits.getFirst();
            ensureSession(state);
            if (state.editing == null) {
                return;
            }
            try {
                strokeSeed = state.editing.select(
                        first.cell().x(), first.cell().y(), first.cell().layer());
                strokeHeight = (int) Math.round(first.position().z());
                if (tool == EditTool.FLOOR) {
                    strokeHeight = state.queryData()
                            .cellAt(first.cell().x(), first.cell().y())
                            .layers()
                            .get(first.cell().layer())
                            .height();
                }
                stroke.clear();
                strokeRectangle = rectangleShape || shiftHeld;
                rectangleStart = first.position();
                rectangleEnd = first.position();
            } catch (RuntimeException unavailable) {
                message = unavailable.getMessage();
                cancelStroke();
                return;
            }
        }
        if (strokeSeed != null && !strokeSeed.belongsTo(state.queryData())) {
            cancelStroke();
            message = "Stroke cancelled because the data changed.";
            return;
        }
        floorPointer = strokeSeed != null && tool == EditTool.FLOOR ? pointOnPlane(strokeHeight) : null;
        if (strokeSeed != null && down && (floorPointer != null || !hits.isEmpty())) {
            GeoPoint nearest = floorPointer;
            var distance = Double.POSITIVE_INFINITY;
            var slope = tool == EditTool.HEIGHT && heightMode == HeightMode.SLOPE;
            var reference = slope && !stroke.isEmpty()
                    ? stroke.getLast().z()
                    : slope && strokeRectangle ? rectangleEnd.z() : strokeHeight;
            var limit = slope ? SLOPE_FOLLOW : tolerance()[0];
            for (var hit : hits) {
                var candidate = Math.abs(hit.position().z() - reference);
                if (floorPointer == null && candidate <= limit && candidate < distance) {
                    distance = candidate;
                    nearest = hit.position();
                }
            }
            if (nearest != null && strokeRectangle) {
                rectangleEnd = nearest;
            } else if (nearest != null && (stroke.isEmpty() || !stroke.getLast().equals(nearest))) {
                if (stroke.size() == 1024) {
                    cancelStroke();
                    message = "Stroke exceeds 1024 samples. Use shorter strokes.";
                    return;
                }
                stroke.add(nearest);
            }
        }
        if (released && strokeSeed != null) {
            var seed = strokeSeed;
            var points = List.copyOf(stroke);
            var targetHeight = heightFromStroke ? Math.round(strokeHeight / 8f) * 8 : paintHeight.get();
            var rectangle = strokeRectangle ? rectangleBounds() : null;
            rectangleStartOf = rectangleStart;
            rectangleEndOf = rectangleEnd;
            cancelStroke();
            var floor = strokeHeight;
            var rule = heightRule(points, rectangle);
            if (tool == EditTool.REBUILD) {
                ensureSession(state);
                if (state.editing != null) {
                    rebuildStroke(state, seed, points, rectangle);
                }
            } else if (tool == EditTool.FLOOR) {
                if (rectangle != null || !points.isEmpty()) {
                    applyChange(state, () -> {
                        var result = rectangle != null
                                ? state.editing.paintFloorRectangle(seed, rectangle, floor, floorTolerance[0])
                                : state.editing.paintFloor(seed, points, brushRadius[0], floor, floorTolerance[0]);
                        message = result.skippedCells() < result.visitedCells()
                                ? ""
                                : "No cell got a new floor: every painted cell has a layer within the height"
                                        + " tolerance of Z " + floor + ". Press on the floor to go on from and drag"
                                        + " out past its edge.";
                    });
                }
            } else if (rule != null && (rectangle != null || !points.isEmpty())) {
                applyChange(state, () -> {
                    var result = rectangle != null
                            ? state.editing.paintHeightsRectangle(seed, rectangle, tolerance[0], rule)
                            : state.editing.paintHeights(seed, points, brushRadius[0], tolerance[0], rule);
                    message = result.skippedCells() == 0
                            ? ""
                            : result.skippedCells()
                                    + " cells skipped: no layer within the height tolerance, or another layer at that"
                                    + " height.";
                });
            } else if (rectangle != null) {
                applyChange(state, () -> {
                    var result = switch (tool) {
                        case BLOCK -> state.editing.paintPassabilityRectangle(seed, rectangle, tolerance[0], false);
                        case OPEN -> state.editing.paintPassabilityRectangle(seed, rectangle, tolerance[0], true);
                        default ->
                            state.editing.paintRectangle(
                                    seed,
                                    rectangle,
                                    tolerance[0],
                                    new LayerEdit(Math.clamp(targetHeight, -16384, 16376), null));
                    };
                    message = result.skippedCells() == 0
                            ? ""
                            : result.skippedCells() + " cells skipped: no layer within the height tolerance.";
                });
            } else if (!points.isEmpty()) {
                applyChange(state, () -> {
                    var result = switch (tool) {
                        case BLOCK -> state.editing.paintPassability(seed, points, brushRadius[0], tolerance[0], false);
                        case OPEN -> state.editing.paintPassability(seed, points, brushRadius[0], tolerance[0], true);
                        default ->
                            state.editing.paintBrush(
                                    seed,
                                    points,
                                    brushRadius[0],
                                    tolerance[0],
                                    new LayerEdit(Math.clamp(targetHeight, -16384, 16376), null));
                    };
                    message = result.skippedCells() == 0
                            ? ""
                            : result.skippedCells() + " cells skipped: no layer within the height tolerance.";
                });
            }
        }
    }

    public void beginEdges(GeodataState state, Selection hovered, Direction direction, boolean oneSide) {
        if (hovered == null || files.isBusy() || pendingLeave != null) {
            return;
        }
        if (direction == null) {
            state.inspection.selected = hovered;
            return;
        }
        edgeStroke.clear();
        edgeOpen = (hovered.nswe() & direction.getMask()) == 0;
        edgeOneSide = oneSide;
        edgeHeight = hovered.height();
        edgeStroke.put(edgeKey(hovered, direction), new PendingEdge(hovered, direction));
        state.inspection.selected = hovered;
    }

    public void dragEdges(GeodataState state, Selection hovered, GeoPoint hoverPoint, boolean down, boolean released) {
        if (edgeStroke.isEmpty()) {
            return;
        }
        if (down
                && hovered != null
                && hoverPoint != null
                && Math.abs(hovered.height() - edgeHeight) <= EDGE_STROKE_HEIGHT) {
            var direction = edgeAt(hoverPoint, hovered.ref());
            if (direction != null) {
                edgeStroke.putIfAbsent(edgeKey(hovered, direction), new PendingEdge(hovered, direction));
            }
        }
        if (!released && down) {
            return;
        }
        var edges = List.copyOf(edgeStroke.values());
        var open = edgeOpen;
        var bothSides = !edgeOneSide;
        edgeStroke.clear();
        applyChange(state, () -> {
            var refs = new ArrayList<EdgeRef>();
            for (var edge : edges) {
                refs.add(new EdgeRef(rebase(state, edge.cell()), edge.direction()));
            }
            if (refs.size() == 1) {
                state.editing.setEdge(refs.getFirst().cell(), refs.getFirst().direction(), open, bothSides);
            } else {
                state.editing.setEdges(refs, open, bothSides);
            }
        });
    }

    public void drawOverlay(
            Camera camera, ImDrawList draw, int width, int height, Selection hovered, GeoPoint hoveredPoint) {
        for (var point : stroke) {
            var screen = camera.projectToScreenRaw(
                    new Vec3((float) point.x(), (float) point.y(), (float) point.z() + 3), width, height);
            if (screen != null) {
                draw.addCircleFilled(screen.getX(), screen.getY(), 2.5f, GeodataWidgets.ACCENT, 8);
            }
        }
        var onPlane = tool == EditTool.FLOOR && strokeSeed != null;
        var hoverPoint = onPlane ? floorPointer : hoveredPoint;
        if (hoverPoint == null || !onPlane && hovered == null) {
            return;
        }
        if (tool == EditTool.EDGES) {
            for (var edge : edgeStroke.values()) {
                drawEdgeLine(camera, draw, edge.cell(), edge.direction(), edgeOpen, width, height);
            }
            var direction = edgeAt(hoverPoint, hovered.ref());
            if (direction == null) {
                return;
            }
            drawEdgeLine(
                    camera,
                    draw,
                    hovered,
                    direction,
                    edgeStroke.isEmpty() ? (hovered.nswe() & direction.getMask()) != 0 : edgeOpen,
                    width,
                    height);
            return;
        }
        if (strokeSeed != null && strokeRectangle) {
            var bounds = rectangleBounds();
            drawOutline(
                    camera,
                    draw,
                    bounds.minX(),
                    bounds.minY(),
                    bounds.maxX(),
                    bounds.maxY(),
                    strokeHeight + 3,
                    brushColor(),
                    width,
                    height);
            var corner = camera.projectToScreenRaw(
                    new Vec3((float) rectangleEnd.x(), (float) rectangleEnd.y(), strokeHeight + 3), width, height);
            if (corner != null) {
                var text = (bounds.maxX() - bounds.minX()) / 16 + " \u00d7 " + (bounds.maxY() - bounds.minY()) / 16
                        + " cells";
                drawLabel(draw, corner.getX() + 14, corner.getY() - ImGui.getFontSize() - 14, text);
            }
            return;
        }
        if (rectangleShape || shiftHeld) {
            var x = Math.floorDiv((int) Math.floor(hoverPoint.x()), 16) * 16;
            var y = Math.floorDiv((int) Math.floor(hoverPoint.y()), 16) * 16;
            drawOutline(camera, draw, x, y, x + 16, y + 16, (float) hoverPoint.z() + 3, brushColor(), width, height);
            return;
        }
        var segments = 40;
        var previous = (Vec2) null;
        var first = (Vec2) null;
        for (var index = 0; index <= segments; index++) {
            var angle = index * Math.PI * 2 / segments;
            var screen = camera.projectToScreenRaw(
                    new Vec3(
                            (float) (hoverPoint.x() + Math.cos(angle) * brushRadius[0]),
                            (float) (hoverPoint.y() + Math.sin(angle) * brushRadius[0]),
                            (float) hoverPoint.z() + 3),
                    width,
                    height);
            if (screen != null && previous != null) {
                draw.addLine(previous.getX(), previous.getY(), screen.getX(), screen.getY(), brushColor(), 2);
            }
            if (first == null) {
                first = screen;
            }
            previous = screen;
        }
        if (tool == EditTool.FLOOR) {
            var center = camera.projectToScreenRaw(
                    new Vec3((float) hoverPoint.x(), (float) hoverPoint.y(), (float) hoverPoint.z() + 3),
                    width,
                    height);
            if (center != null) {
                var z = onPlane ? strokeHeight : hovered.height();
                drawLabel(draw, center.getX() + 14, center.getY() - ImGui.getFontSize() - 14, "Floor at Z " + z);
            }
        }
        if (tool == EditTool.HEIGHT) {
            var target = !heightFromStroke
                    ? paintHeight.get()
                    : Math.round((strokeSeed != null ? strokeHeight : (float) hoverPoint.z()) / 8f) * 8;
            var center = camera.projectToScreenRaw(
                    new Vec3((float) hoverPoint.x(), (float) hoverPoint.y(), (float) hoverPoint.z() + 3),
                    width,
                    height);
            if (center != null) {
                var text = switch (heightMode) {
                    case FLAT -> "Z \u2192 " + target;
                    case SLOPE ->
                        strokeSeed != null && !stroke.isEmpty()
                                ? "Slope " + Math.round(stroke.getFirst().z()) + " \u2192 "
                                        + Math.round(stroke.getLast().z())
                                : stepHeight.get() > 0 ? "Stairs" : "Slope";
                    case SMOOTH -> "Smooth";
                    case RAISE -> (raiseBy.get() >= 0 ? "+" : "") + Math.round(raiseBy.get() / 8f) * 8;
                };
                drawLabel(draw, center.getX() + 14, center.getY() - ImGui.getFontSize() - 14, text);
            }
        }
    }

    public void resizeBrush(boolean grow) {
        var radius = brushRadius[0];
        brushRadius[0] = Math.clamp(
                grow
                        ? Math.max(radius + 8, Math.round(radius * 1.25f))
                        : Math.min(radius - 8, Math.round(radius / 1.25f)),
                8,
                512);
    }

    public void drawChanges(GeodataState state, Camera camera, ImDrawList draw, int width, int height) {
        var session = state.editing;
        if (session == null || !markChanges.get()) {
            return;
        }
        var document = session.getDocument();
        if (markRevision != document.revision()) {
            markRevision = document.revision();
            markedCells.clear();
            var data = state.queryData();
            for (var key : document.getChangedCells().keySet()) {
                var before = document.getBase().cellAt(key.x(), key.y()).layers();
                var after = data.cellAt(key.x(), key.y()).layers();
                for (var now : after) {
                    var same = false;
                    for (var was : before) {
                        same |= was.height() == now.height() && was.nswe() == now.nswe();
                    }
                    if (!same && markedCells.size() < MARKED_CELLS) {
                        markedCells.add(new int[] {key.x(), key.y(), now.height()});
                    }
                }
                for (var was : before) {
                    var kept = after.size() >= before.size();
                    for (var now : after) {
                        kept |= now.height() == was.height();
                    }
                    if (!kept && markedCells.size() < MARKED_CELLS) {
                        markedCells.add(new int[] {key.x(), key.y(), was.height()});
                    }
                }
            }
        }
        var eye = camera.getPosition();
        var color = ImColor.rgba(255, 196, 92, 210);
        for (var cell : markedCells) {
            var dx = cell[0] + 8 - eye.getX();
            var dy = cell[1] + 8 - eye.getY();
            if (dx * dx + dy * dy > MARK_DISTANCE * MARK_DISTANCE) {
                continue;
            }
            var z = cell[2] + 1.5f;
            var a = camera.projectToScreenRaw(new Vec3(cell[0] + .5f, cell[1] + .5f, z), width, height);
            var b = camera.projectToScreenRaw(new Vec3(cell[0] + 15.5f, cell[1] + .5f, z), width, height);
            var c = camera.projectToScreenRaw(new Vec3(cell[0] + 15.5f, cell[1] + 15.5f, z), width, height);
            var d = camera.projectToScreenRaw(new Vec3(cell[0] + .5f, cell[1] + 15.5f, z), width, height);
            if (a == null || b == null || c == null || d == null) {
                continue;
            }
            draw.pathLineTo(a.getX(), a.getY());
            draw.pathLineTo(b.getX(), b.getY());
            draw.pathLineTo(c.getX(), c.getY());
            draw.pathLineTo(d.getX(), d.getY());
            draw.pathStroke(color, 1, 1.5f);
        }
    }

    public boolean writesFiles() {
        return files.isBusy();
    }

    public boolean hasStroke() {
        return strokeSeed != null || !edgeStroke.isEmpty();
    }

    public void cancelInteraction() {
        cancelStroke();
        edgeStroke.clear();
    }

    public List<GeoPoint> getStrokePoints() {
        return List.copyOf(stroke);
    }

    public void undo(GeodataState state) {
        if (state.editing != null && !hasStroke() && !files.isBusy()) {
            applyChange(state, state.editing::undo);
        }
    }

    public void redo(GeodataState state) {
        if (state.editing != null && !hasStroke() && !files.isBusy()) {
            applyChange(state, state.editing::redo);
        }
    }

    public boolean requestLeave(GeodataState state, Leave action) {
        cancelStroke();
        if (files.isBusy() || state.editing != null && state.editing.hasUnsavedChanges()) {
            if (pendingLeave == null) {
                message = "";
                done = "";
            }
            pendingLeave = action;
            return true;
        }
        pendingLeave = null;
        saveThenLeave = false;
        if (action == Leave.END_EDITING || action == Leave.SOURCE) {
            state.discardEditingAndChangeSource();
        }
        return false;
    }

    public void drawGuard(GeodataState state) {
        poll(state);
        if (state.sourceChangePending && pendingLeave == null) {
            requestLeave(state, Leave.SOURCE);
        }
        if (pendingLeave == null) {
            guardRowWidth = 0;
            return;
        }
        fillDefaultPaths(state);
        ImGui.openPopup("Unsaved geodata changes");
        var io = ImGui.getIO();
        var popupWidth = Math.max(280, Math.min(560, io.getDisplaySizeX() - 32));
        ImGui.setNextWindowPos(io.getDisplaySizeX() / 2, io.getDisplaySizeY() / 2, ImGuiCond.Always, .5f, .5f);
        ImGui.setNextWindowSize(popupWidth, 0);
        if (ImGui.beginPopupModal(
                "Unsaved geodata changes",
                ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.AlwaysAutoResize)) {
            Motion.dialog();
            ImGui.pushTextWrapPos(0);
            ImGui.textUnformatted(
                    state.editing == null
                            ? "Geodata files are still being written."
                            : unsavedText(state.editing.getDocument().getChangedCellCount()));
            ImGui.popTextWrapPos();
            ImGui.dummy(0, 2);
            drawExperimentalWarning();
            GeodataWidgets.section("SAVE INTO THE FOLDER");
            hint("Replaces the edited region files in " + folderName(state) + ". Other files stay as they are.");
            saveOptions(state);
            ImGui.beginDisabled(files.isBusy() || state.editing == null || isServerUnknown());
            guardSaveX = ImGui.getCursorScreenPosX();
            guardSaveY = ImGui.getCursorScreenPosY();
            if (GeodataWidgets.actionButton("guard-save-source", null, "Save to folder and continue", -1, true)) {
                saveThenLeave = true;
                saveSource(state);
            }
            ImGui.endDisabled();
            GeodataWidgets.section("OR SAVE A PATCH");
            hint(PATCH_HELP);
            GeodataWidgets.textField("Patch file", patchPath);
            drawPathExplanation(state, patchPath.get(), false);
            ImGui.checkbox("Replace an existing patch##guard", replace);
            ImGui.dummy(0, 4);
            guardRowX = ImGui.getCursorScreenPosX();
            guardRowY = ImGui.getCursorScreenPosY();
            guardRowWidth = ImGui.getContentRegionAvailX();
            ImGui.beginDisabled(files.isBusy());
            ImGui.beginDisabled(patchPath.get().isBlank() || state.editing == null);
            if (GeodataWidgets.rowButton("Save patch and continue", 0, 3)) {
                saveThenLeave = true;
                submit(state, Operation.SAVE_PATCH, patchPath.get());
            }
            ImGui.endDisabled();
            if (GeodataWidgets.rowButton("Discard", 1, 3)) {
                approveLeave(state);
                ImGui.closeCurrentPopup();
            }
            ImGui.endDisabled();
            if (GeodataWidgets.rowButton("Keep editing", 2, 3)
                    || !files.isBusy() && GeodataWidgets.wasClickedOutside()) {
                pendingLeave = null;
                saveThenLeave = false;
                state.keepEditingSource();
                ImGui.closeCurrentPopup();
            }
            if (files.isBusy()) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                drawOperationCard("leave-progress");
            }
            var saveFailed = fileOperation == Operation.SAVE_SOURCE
                    || fileOperation == Operation.SAVE_SOURCE_PATH_NODES
                    || fileOperation == Operation.SAVE_PATCH;
            var problem = !message.isBlank() ? message : saveFailed ? fileMessage : "";
            if (!problem.isBlank()) {
                ImGui.pushTextWrapPos(0);
                ImGui.textColored(GeodataWidgets.WARNING, problem);
                ImGui.popTextWrapPos();
            }
            ImGui.endPopup();
        }
    }

    public Leave consumeApprovedLeave() {
        var result = approvedLeave;
        approvedLeave = null;
        return result;
    }

    @Override
    public void close() {
        files.close();
        review.close();
    }

    public int apply(GeodataState state, String label, Map<CellKey, List<Layer>> cells) {
        var changed = new int[] {-1};
        message = "";
        applyChange(state, () -> changed[0] = state.editing.applyCells(label, cells));
        if (changed[0] < 0 && message.isBlank()) {
            message = files.isBusy() ? "Wait until the files are written." : "Load geodata before editing.";
        }
        return changed[0];
    }

    public String getProblem() {
        return message;
    }

    public @Nullable GeoPoint takeFlyTo() {
        var point = flyTo;
        flyTo = null;
        return point;
    }

    int applyAreaAction(GeodataState state, Bounds bounds, Selection seed, AreaAction action, int height) {
        var changed = new int[] {-1};
        message = "";
        applyChange(state, () -> {
            var ref = rebase(state, seed);
            var result = switch (action) {
                case OPEN_ALL -> state.editing.paintRectangle(ref, bounds, AREA_TOLERANCE, new LayerEdit(null, 15));
                case BLOCK_ALL -> state.editing.paintRectangle(ref, bounds, AREA_TOLERANCE, new LayerEdit(null, 0));
                case SET_HEIGHT ->
                    state.editing.paintRectangle(
                            ref,
                            bounds,
                            AREA_TOLERANCE,
                            new LayerEdit(Math.clamp(Math.round(height / 8f) * 8, -16384, 16376), null));
                case DELETE_LAYER -> state.editing.removeLayers(ref, bounds, AREA_TOLERANCE);
            };
            changed[0] = result.changedCells();
        });
        return changed[0];
    }

    int paste(GeodataState state, Clip clip, Selection at) {
        var changed = new int[] {-1};
        message = "";
        applyChange(
                state,
                () -> changed[0] =
                        state.editing.paste(clip, at.ref().x(), at.ref().y(), at.height()));
        return changed[0];
    }

    private void rebuildControls(GeodataState state) {
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (rebuilder == null || !rebuilder.isAvailable()) {
            warning("Rebuild needs the client: the builder reads its maps. Open a client to use it.");
            return;
        }
        rebuildProgress("edit-rebuild-progress");
        var tile = focusTile(state);
        if (tile == null) {
            return;
        }
        var name = tile.x() + "_" + tile.y();
        ImGui.beginDisabled(files.isBusy() || rebuilder.getStatus().running());
        if (GeodataWidgets.actionButton("edit-rebuild-region", Glyph.GEODATA, "Rebuild region " + name, -1, false)) {
            rebuildRegion(state, tile);
        }
        ImGui.endDisabled();
        GeodataWidgets.tip("Builds all of " + name
                + " from the client and loads it as one edit, so you can review it, undo it or save it like other"
                + " edits. Nothing is written until you save."
                + startPointsNote(rebuilder.getStartSources()));
    }

    private void finishRebuilds(GeodataState state) {
        if (rebuilder == null) {
            return;
        }
        var status = rebuilder.getStatus();
        var region = pendingRegion;
        if (region != null && !status.running()) {
            if (rebuilder.getBuiltRegion(region) != null) {
                rebuildRegion(state, region);
            } else if (!status.problem().isBlank() && region.equals(status.tile())) {
                message = status.problem();
                pendingRegion = null;
            }
        }
        var stroke = pendingRebuild;
        if (stroke != null && !status.running()) {
            var built = rebuilder.getBuiltRegion(stroke.tile());
            if (built != null) {
                pendingRebuild = null;
                applyRebuild(state, stroke, built);
            } else if (!status.problem().isBlank() && stroke.tile().equals(status.tile())) {
                message = status.problem();
                pendingRebuild = null;
            }
        }
    }

    private record RebuildStroke(
            GeoTile tile,
            GeoCellRef seed,
            List<GeoPoint> points,
            @Nullable Bounds rectangle,
            int radius,
            int tolerance) {}

    private void rebuildStroke(GeodataState state, GeoCellRef seed, List<GeoPoint> points, @Nullable Bounds rectangle) {
        if (rebuilder == null || !rebuilder.isAvailable()) {
            message = "Rebuild needs the client: open a client to build from its maps.";
            return;
        }
        var tile = GeoTile.fromWorld(seed.x(), seed.y());
        var stroke = new RebuildStroke(tile, seed, points, rectangle, brushRadius[0], rebuildTolerance[0]);
        var built = rebuilder.getBuiltRegion(tile);
        if (built == null) {
            pendingRebuild = stroke;
            done = "Building " + tile.x() + "_" + tile.y() + " from the client. The stroke applies when it is done.";
            return;
        }
        applyRebuild(state, stroke, built);
    }

    private void applyRebuild(GeodataState state, RebuildStroke stroke, GeoDataAccess built) {
        applyChange(state, () -> {
            var seed = rebase(state, stroke.seed());
            var result = stroke.rectangle() != null
                    ? state.editing.paintRebuildRectangle(seed, stroke.rectangle(), stroke.tolerance(), built)
                    : state.editing.paintRebuild(seed, stroke.points(), stroke.radius(), stroke.tolerance(), built);
            done = result.changedCells() == 0 ? "The client's build has the same layers there." : "";
            message = result.skippedCells() == 0
                    ? ""
                    : result.skippedCells() + " cells skipped: outside the built region.";
        });
    }

    private void drawSessionBar(GeodataState state) {
        var session = state.editing;
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var height = 40f;
        var draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + height, GeodataWidgets.CARD, 6f);
        String title;
        String detail;
        int color;
        if (session == null) {
            title = "Not editing yet";
            detail = "Your first change starts editing";
            color = GeodataWidgets.FAINT;
            if (ImGui.calcTextSizeX(detail) > width - 30) {
                detail = "First change starts it";
            }
        } else if (files.isBusy()) {
            title = getBusyLabel() + "\u2026";
            var progress = fileProgress();
            detail = progress != null && !progress.brief().isBlank()
                    ? progress.brief()
                    : "Edits are paused until it finishes";
            color = GeodataWidgets.ACCENT;
        } else {
            var document = session.getDocument();
            var changed = document.getChangedCellCount();
            title = changed + (changed == 1 ? " changed cell" : " changed cells");
            var regions = document.getBase().getTiles().size();
            detail = (session.hasUnsavedChanges() ? "Unsaved" : "Saved")
                    + " · "
                    + regions
                    + (regions == 1 ? " region" : " regions")
                    + " locked";
            color = session.hasUnsavedChanges() ? GeodataWidgets.WARNING : GeodataWidgets.OPEN;
            if (ImGui.calcTextSizeX(detail) > width - 22 - 96) {
                detail = session.hasUnsavedChanges() ? "Unsaved" : "Saved";
            }
        }
        draw.addCircleFilled(x + 12, y + 13, 4, color, 12);
        draw.addText(x + 22, y + 5, GeodataWidgets.TEXT, title);
        draw.pushClipRect(x, y, x + width - (session == null || files.isBusy() ? 8 : 96), y + height, true);
        draw.addText(x + 22, y + 21, GeodataWidgets.MUTED, detail);
        draw.popClipRect();
        if (files.isBusy()) {
            var progress = fileProgress();
            var barY = y + height - 3;
            draw.addRectFilled(x + 6, barY, x + width - 6, barY + 2, ImColor.rgba(255, 255, 255, 30), 1f);
            if (progress != null && progress.fraction() >= 0) {
                var fraction = (float) Math.clamp(progress.fraction(), 0, 1);
                draw.addRectFilled(x + 6, barY, x + 6 + (width - 12) * fraction, barY + 2, GeodataWidgets.ACCENT, 1f);
            } else {
                var span = (width - 12) * .3f;
                var start = (float) ((ImGui.getTime() * .6) % 1.3 - .3) * (width - 12);
                draw.addRectFilled(
                        x + 6 + Math.max(0, start),
                        barY,
                        x + 6 + Math.min(width - 12, start + span),
                        barY + 2,
                        GeodataWidgets.ACCENT,
                        1f);
            }
        }
        ImGui.dummy(width, height);
        if (session != null && session.isModified()) {
            GeodataWidgets.tip(
                    "The editing session keeps the loaded regions fixed while you work. The stored pathnode graph no"
                            + " longer matches edited cells: export with pathnode before server use.");
        }
        if (session == null || files.isBusy()) {
            return;
        }
        var after = ImGui.getCursorScreenPos();
        ImGui.setCursorScreenPos(x + width - 92, y + 7);
        ImGui.beginDisabled(
                files.isBusy() || hasStroke() || !session.getDocument().canUndo());
        if (HudWidgets.button("edit-undo", Glyph.UNDO, "", 26, 26, false, false)) {
            undo(state);
        }
        GeodataWidgets.tip("Undo " + session.getDocument().getUndoLabel(), "Ctrl+Z or Cmd+Z");
        ImGui.endDisabled();
        ImGui.sameLine(0, 2);
        ImGui.beginDisabled(
                files.isBusy() || hasStroke() || !session.getDocument().canRedo());
        if (HudWidgets.button("edit-redo", Glyph.REDO, "", 26, 26, false, false)) {
            redo(state);
        }
        GeodataWidgets.tip("Redo " + session.getDocument().getRedoLabel(), "Shift+Ctrl+Z or Shift+Cmd+Z");
        ImGui.endDisabled();
        ImGui.sameLine(0, 2);
        if (HudWidgets.button("edit-finish", Glyph.CLOSE, "", 26, 26, false, false)) {
            requestLeave(state, Leave.END_EDITING);
        }
        GeodataWidgets.tip("Finish editing. Unsaved changes ask first.");
        ImGui.setCursorScreenPos(after.x, after.y);
        ImGui.dummy(0, 0);
    }

    private void drawCellEditor(GeodataState state) {
        var data = state.queryData();
        var selected = state.inspection.selected;
        var resolved = GeoInspection.resolve(data, selected);
        GeodataWidgets.section("SELECTED CELL");
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/nswe");
        if (resolved == null) {
            ImGui.textColored(GeodataWidgets.MUTED, "Click a cell with the Edges tool, or select one in Inspect.");
            return;
        }
        var ref = selected.ref();
        var size = 84f;
        var top = ImGui.getCursorScreenPosY();
        var busy = files.isBusy();
        var direction = GeodataWidgets.compass("edit-compass", selected.nswe(), size, !busy);
        if (direction != null) {
            var open = (selected.nswe() & direction.getMask()) == 0;
            var oneSide = ImGui.getIO().getKeyShift();
            applyChange(state, () -> state.editing.setEdge(rebase(state, selected), direction, open, !oneSide));
        }
        ImGui.sameLine();
        ImGui.beginGroup();
        ImGui.beginDisabled(busy);
        ImGui.textUnformatted(ref.x() + ", " + ref.y());
        ImGui.textColored(
                GeodataWidgets.MUTED,
                "Layer " + (ref.layer() + 1) + " of " + resolved.cell().layers().size());
        if (heightEditRef == null || !heightEditRef.equals(ref)) {
            heightEditRef = ref;
            height.set(selected.height());
        }
        ImGui.alignTextToFramePadding();
        ImGui.textColored(GeodataWidgets.MUTED, "Z");
        ImGui.sameLine(0, 6);
        ImGui.setNextItemWidth(Math.max(80, ImGui.getContentRegionAvailX()));
        if (ImGui.inputInt("##edit-height", height, 8, 64) && height.get() != selected.height()) {
            var value = Math.clamp(Math.round(height.get() / 8f) * 8, -16384, 16376);
            applyChange(state, () -> state.editing.editLayer(rebase(state, selected), value, selected.nswe()));
        }
        GeodataWidgets.tip("Layer height. Steps of 8 units; arrows change it at once.");
        var halfWidth = (ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX()) / 2;
        if (HudWidgets.button("edit-open-all", null, "Open all", halfWidth, 24, false, true)) {
            applyChange(state, () -> state.editing.editLayer(rebase(state, selected), selected.height(), 15));
        }
        ImGui.sameLine();
        if (HudWidgets.button("edit-block-all", null, "Block all", halfWidth, 24, false, true)) {
            applyChange(state, () -> state.editing.editLayer(rebase(state, selected), selected.height(), 0));
        }
        GeodataWidgets.tip("Only this layer. Use the Block brush to also close the edges that lead into it.");
        ImGui.endDisabled();
        ImGui.endGroup();
        ImGui.setCursorScreenPos(ImGui.getCursorScreenPosX(), Math.max(ImGui.getCursorScreenPosY(), top + size + 6));
        ImGui.beginDisabled(busy);
        if (GeodataWidgets.rowButton("Add layer above", 0, 2)) {
            applyChange(
                    state,
                    () -> state.editing.addLayer(
                            rebase(state, selected),
                            selected.ref().layer(),
                            Math.min(16376, selected.height() + 64),
                            15));
        }
        GeodataWidgets.tip(
                "Inserts a walkable layer 64 units higher, before the selected one in the stored order. To go"
                        + " on with a floor over the cells beside it, drag with the Floor tool.");
        if (GeodataWidgets.rowButton("Delete layer", 1, 2)) {
            applyChange(state, () -> state.editing.removeLayer(rebase(state, selected)));
        }
        ImGui.endDisabled();
    }

    private void drawFilesSection(GeodataState state) {
        var session = state.editing;
        GeodataWidgets.space(GeodataWidgets.SECTION);
        if (revealFiles) {
            ImGui.setScrollHereY(0);
            revealFiles = false;
        }
        var unsaved = session != null && session.hasUnsavedChanges();
        filesOpen = GeodataWidgets.disclosureCard(
                "geodata-files",
                Glyph.FOLDER,
                "Save and export",
                unsaved ? changedText(session) + " not saved" : "Copy, convert or patch",
                unsaved ? GeodataWidgets.WARNING : GeodataWidgets.MUTED,
                filesOpen);
        GeodataWidgets.tip(
                "Save into this folder, export to another folder in L2OFF or L2J, or keep the changes as a patch.");
        if (!filesOpen) {
            return;
        }
        fillDefaultPaths(state);
        var reviewed = drawReview(state);
        GeodataWidgets.section("THIS FOLDER", reviewed ? GeodataWidgets.SECTION : GeodataWidgets.ROW);
        saveOptions(state);
        if (isRunning(Operation.SAVE_SOURCE, Operation.SAVE_SOURCE_PATH_NODES)) {
            drawOperationCard("save-progress");
        } else {
            var edited = session != null && session.isModified();
            var graphOnly = !edited && pathNodes.get() && state.getSourceSnapshot() != null;
            ImGui.beginDisabled(files.isBusy() || !edited && !graphOnly || isServerUnknown());
            if (GeodataWidgets.actionButton(
                    "save-source",
                    null,
                    graphOnly ? "Rebuild pathnode in this folder" : "Save to geodata folder",
                    -1,
                    true)) {
                ensureSession(state);
                if (state.editing != null) {
                    saveSource(state);
                }
            }
            ImGui.endDisabled();
            GeodataWidgets.tip(
                    graphOnly
                            ? "Builds the pathnode graph of every region in " + folderName(state)
                                    + " again and replaces its pathnode files. The region files stay as they are."
                            : "Replaces the edited region files in "
                                    + folderName(state)
                                    + (pathNodes.get() ? " and its pathnode files" : "")
                                    + ". Other files stay as they are. Afterwards the folder is loaded again.");
        }
        drawFileResult(Operation.SAVE_SOURCE, Operation.SAVE_SOURCE_PATH_NODES);
        GeodataWidgets.section("ANOTHER FOLDER");
        GeodataWidgets.textField("Folder", exportPath);
        drawPathExplanation(state, exportPath.get(), true);
        if (GeodataWidgets.actionButton("export-where", Glyph.FOLDER, "Choose where\u2026", -1, false)) {
            exportPicker.open(parentOf(exportPath.get()));
        }
        var exportParent = exportPicker.draw();
        if (exportParent != null) {
            exportPath.set(exportParent
                    .resolve(nameOf(exportPath.get(), "geodata-edited"))
                    .toString());
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        var source = sourceFormat(state);
        var target = exportFormatChoice(state);
        var converting = target != source;
        var changed = session != null && session.isModified();
        var withPathNodes = exportPathNodesOption(state, target);
        var nothingNew = !converting && !changed && !withPathNodes;
        hint(exportHint(source, target, changed, withPathNodes));
        var replaced = nothingNew ? List.<String>of() : replacedFiles(state, target, withPathNodes, false);
        if (!replaced.isEmpty()) {
            ImGui.pushTextWrapPos(0);
            ImGui.textColored(
                    GeodataWidgets.WARNING, filesText(replaced.size()) + " there will be replaced. Export asks first.");
            ImGui.popTextWrapPos();
            GeodataWidgets.tip(String.join("\n", replaced));
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        var exporting = isRunning(Operation.EXPORT, Operation.EXPORT_PATH_NODES);
        if (exporting) {
            drawOperationCard("export-progress");
        }
        ImGui.beginDisabled(
                files.isBusy() || exportPath.get().isBlank() || nothingNew || isServerUnknown(withPathNodes));
        if (!exporting
                && GeodataWidgets.actionButton(
                        "export",
                        null,
                        converting ? "Export as " + target.name() + " to folder" : "Export to folder",
                        -1,
                        false)) {
            ensureSession(state);
            if (state.editing != null) {
                var conflicts = replacedFiles(state, target, withPathNodes, true);
                pendingFormat = converting ? target : null;
                pendingPathNodes = withPathNodes;
                if (conflicts.isEmpty()) {
                    startExport(state, false);
                } else {
                    pendingReplace = conflicts;
                    askReplace = true;
                }
            }
        }
        ImGui.endDisabled();
        if (askReplace) {
            ImGui.openPopup(REPLACE_POPUP);
            askReplace = false;
        }
        replaceConfirm(state);
        var changes = changed ? " with your changes" : "";
        GeodataWidgets.tip((converting
                        ? "Writes every region of the folder as " + target.name() + " files" + changes
                        : "Writes every region of the folder" + changes)
                + (target == GeoFormat.L2OFF ? ", and geo_index.txt," : "")
                + " into the folder above, which is created when it does not exist"
                + (withPathNodes ? ", with a rebuilt pathnode graph" : "")
                + ". Other files there stay. The source stays untouched.");
        drawFileResult(Operation.EXPORT, Operation.EXPORT_PATH_NODES);
        GeodataWidgets.section("PATCH");
        hint(PATCH_HELP);
        GeodataWidgets.textField("Patch file", patchPath);
        drawPathExplanation(state, patchPath.get(), false);
        if (GeodataWidgets.actionButton("patch-where", Glyph.FOLDER, "Choose where\u2026", -1, false)) {
            patchPicker.open(parentOf(patchPath.get()));
        }
        var patchParent = patchPicker.draw();
        if (patchParent != null) {
            patchPath.set(patchParent
                    .resolve(nameOf(patchPath.get(), "geodata-patch.json"))
                    .toString());
        }
        ImGui.checkbox("Replace an existing patch", replace);
        ImGui.beginDisabled(files.isBusy() || patchPath.get().isBlank());
        ImGui.beginDisabled(session == null);
        if (GeodataWidgets.rowButton("Save patch", 0, 2)) {
            submit(state, Operation.SAVE_PATCH, patchPath.get());
        }
        ImGui.endDisabled();
        GeodataWidgets.tip("Saves only the changed cells as JSON. Load it later into the same source files.");
        ImGui.beginDisabled(session != null && session.isModified());
        if (GeodataWidgets.rowButton("Load patch", 1, 2)) {
            ensureSession(state);
            submit(state, Operation.LOAD_PATCH, patchPath.get());
        }
        ImGui.endDisabled();
        ImGui.endDisabled();
        drawFileResult(Operation.SAVE_PATCH, Operation.LOAD_PATCH);
        if (session != null) {
            GeodataWidgets.space(GeodataWidgets.BLOCK);
        }
        if (session != null
                && GeodataWidgets.header(
                        "Changed cells (" + session.getDocument().getChangedCellCount() + ")",
                        ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.checkbox("Outline them in the view", markChanges);
            GeodataWidgets.tip(
                    "Draws an amber outline on every changed floor of a cell, in every tool, while editing.");
            drawEdits(state);
        }
    }

    private boolean drawReview(GeodataState state) {
        var session = state.editing;
        if (session == null || !session.isModified()) {
            return false;
        }
        review.request(session, spawns.getSpawnCheck());
        GeodataWidgets.section("WHAT THE EDITS CHANGE", GeodataWidgets.ROW);
        var result = review.getResultFor(session.getDocument().revision());
        if (result == null) {
            hint("Checking the edited cells and a margin around them\u2026");
            return true;
        }
        if (!result.error().isBlank()) {
            warning("The check failed: " + result.error());
            return true;
        }
        var scan = result.scan();
        var problems = scan.introduced();
        GeodataWidgets.dot(problems.isEmpty() ? GeodataWidgets.OPEN : GeodataWidgets.WARNING);
        ImGui.textUnformatted(
                problems.isEmpty()
                        ? "The edits add no problems"
                        : "The edits add " + problems.size() + (problems.size() == 1 ? " problem" : " problems"));
        GeodataWidgets.tip("The scanner of Check ran on the edited cells and " + GeoEditCheck.MARGIN
                + " units around them, on the source and on the edits. Only findings the source does not have"
                + " are listed. Saving works either way.");
        hint(String.format(
                Locale.ROOT,
                "%,d cells checked%s%s",
                scan.scannedCells(),
                scan.information() == 0
                        ? ""
                        : scan.information() == 1
                                ? " \u00b7 1 new long drop or other note"
                                : String.format(
                                        Locale.ROOT, " \u00b7 %,d new long drops and other notes", scan.information()),
                scan.partial() ? " \u00b7 partial" : ""));
        for (var index = 0; index < Math.min(REVIEW_CARDS, problems.size()); index++) {
            var finding = problems.get(index);
            GeodataWidgets.space(GeodataWidgets.ROW);
            var position = finding.position();
            if (FindingWidgets.card(
                            "edit-review-" + index,
                            GeodataDiagnosticsPanel.severity(finding),
                            GeodataDiagnosticsPanel.title(finding),
                            Math.round(position.x()) + ", " + Math.round(position.y()) + " \u00b7 " + finding.detail(),
                            List.of(),
                            false)
                    == FindingWidgets.FLY) {
                flyTo = position;
            }
        }
        if (problems.size() > REVIEW_CARDS) {
            hint("And " + (problems.size() - REVIEW_CARDS) + " more. Scan the area in Check to see and fix them all.");
        }
        spawnReview(result.spawns());
        return true;
    }

    private void spawnReview(SpawnImpact impact) {
        if (impact == null) {
            return;
        }
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var blocked = impact.blocked().size();
        var emptied = impact.emptied().size();
        GeodataWidgets.dot(blocked + emptied == 0 ? GeodataWidgets.OPEN : GeodataWidgets.WARNING);
        if (blocked + emptied == 0) {
            ImGui.textUnformatted("No spawn breaks");
        } else {
            var parts = new ArrayList<String>();
            if (blocked > 0) {
                parts.add(blocked + (blocked == 1 ? " fixed NPC now stands" : " fixed NPCs now stand")
                        + " on blocked cells");
            }
            if (emptied > 0) {
                parts.add(emptied + (emptied == 1 ? " random zone lost" : " random zones lost") + " all candidates");
            }
            ImGui.pushTextWrapPos(0);
            ImGui.textUnformatted(String.join(", ", parts));
            ImGui.popTextWrapPos();
        }
        GeodataWidgets.tip(
                "NPC Spawns' file checked against the source and the edited geodata, for the makers with spawns in the"
                        + " edited area. Only what the edits change is listed.");
        var problems = new ArrayList<SpawnProblem>(impact.blocked());
        problems.addAll(impact.emptied());
        for (var index = 0; index < Math.min(REVIEW_CARDS, problems.size()); index++) {
            var problem = problems.get(index);
            GeodataWidgets.space(GeodataWidgets.ROW);
            if (FindingWidgets.card(
                            "edit-review-spawn-" + index,
                            Severity.WARNING,
                            problem.title(),
                            problem.detail(),
                            List.of(),
                            false)
                    == FindingWidgets.FLY) {
                flyTo = new GeoPoint(problem.x(), problem.y(), problem.z());
            }
        }
        if (problems.size() > REVIEW_CARDS) {
            hint("And " + (problems.size() - REVIEW_CARDS) + " more. NPC Spawns' check lists them all.");
        }
    }

    private GeoFormat exportFormatChoice(GeodataState state) {
        var source = sourceFormat(state);
        var target = exportFormat == null ? source : exportFormat;
        ImGui.textColored(GeodataWidgets.MUTED, "Format");
        var picked = GeodataWidgets.segmented("export-format", FORMAT_LABELS, target.ordinal());
        if (picked >= 0 && picked != target.ordinal()) {
            var oldDefault = defaultExportPath(state, target);
            target = GeoFormat.values()[picked];
            exportFormat = target == source ? null : target;
            if (oldDefault != null && exportPath.get().equals(oldDefault)) {
                exportPath.set(defaultExportPath(state, target));
            }
        }
        GeodataWidgets.tip(
                "L2OFF writes X_Y_conv.dat files for NCsoft's servers, L2J writes X_Y.l2j files for L2J Server. "
                        + "This folder is " + source.name()
                        + ". Pick the other format to convert it: every region is written in that format, the same"
                        + " cells either way.");
        return target;
    }

    private void startExport(GeodataState state, boolean replaceFiles) {
        try {
            if (pendingPathNodes) {
                files.exportPathNodes(state.editing, Path.of(exportPath.get()), chosenZoneTable(), replaceFiles);
            } else {
                files.export(state.editing, Path.of(exportPath.get()), pendingFormat, replaceFiles);
            }
            fileMessage = "";
            fileDone = "";
            fileOperation = pendingPathNodes ? Operation.EXPORT_PATH_NODES : Operation.EXPORT;
        } catch (RuntimeException exception) {
            fileMessage = exception.getMessage();
            fileOperation = pendingPathNodes ? Operation.EXPORT_PATH_NODES : Operation.EXPORT;
            saveThenLeave = false;
        }
    }

    private void replaceConfirm(GeodataState state) {
        var io = ImGui.getIO();
        ImGui.setNextWindowPos(io.getDisplaySizeX() / 2, io.getDisplaySizeY() / 2, ImGuiCond.Always, .5f, .5f);
        ImGui.setNextWindowSize(Math.max(280, Math.min(420, io.getDisplaySizeX() - 32)), 0);
        if (!ImGui.beginPopupModal(
                REPLACE_POPUP,
                ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.AlwaysAutoResize)) {
            return;
        }
        Motion.dialog();
        if (pendingReplace.isEmpty()) {
            ImGui.closeCurrentPopup();
            ImGui.endPopup();
            return;
        }
        var folder = Path.of(exportPath.get()).getFileName();
        ImGui.pushTextWrapPos(0);
        ImGui.textUnformatted(
                "Replace " + filesText(pendingReplace.size()).toLowerCase(Locale.ROOT) + " in " + folder + "?");
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.textColored(
                GeodataWidgets.MUTED,
                pendingReplace.size() == 1
                        ? "The folder already has a file with a name this export writes:"
                        : "The folder already has files with names this export writes:");
        var shown = Math.min(pendingReplace.size(), REPLACE_LISTED);
        for (var index = 0; index < shown; index++) {
            ImGui.textColored(GeodataWidgets.MUTED, "  \u00b7 " + pendingReplace.get(index));
        }
        if (pendingReplace.size() > shown) {
            ImGui.textColored(GeodataWidgets.MUTED, "  and " + (pendingReplace.size() - shown) + " more");
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.FAINT, "Other files in the folder stay as they are.");
        ImGui.popTextWrapPos();
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var width = GeodataWidgets.columnWidth(2);
        if (HudWidgets.button("export-replace-yes", null, "Replace", width, 28, true, true)) {
            ImGui.closeCurrentPopup();
            pendingReplace = List.of();
            startExport(state, true);
        }
        ImGui.sameLine();
        if (HudWidgets.button("export-replace-no", null, "Cancel", width, 28, false, true)
                || ImGui.isKeyPressed(ImGuiKey.Escape)
                || GeodataWidgets.wasClickedOutside()) {
            ImGui.closeCurrentPopup();
            pendingReplace = List.of();
        }
        ImGui.endPopup();
    }

    private List<String> replacedFiles(GeodataState state, GeoFormat target, boolean withPathNodes, boolean fresh) {
        var snapshot = state.getSourceSnapshot();
        if (snapshot == null || exportPath.get().isBlank()) {
            return List.of();
        }
        Path folder;
        try {
            folder = Path.of(exportPath.get()).toAbsolutePath().normalize();
        } catch (RuntimeException invalid) {
            return List.of();
        }
        var key = folder + "|" + target + "|" + withPathNodes + "|" + snapshot.getSourcePath();
        var now = System.nanoTime();
        if (fresh || !key.equals(replacedKey) || now - replacedAt > 1_000_000_000L) {
            replacedKey = key;
            replacedAt = now;
            try {
                var format = target == snapshot.getFormat() ? null : target;
                replacedFiles = GeoDataWriter.existingFiles(
                        folder, GeoDataWriter.exportFileNames(snapshot, format, withPathNodes));
            } catch (IOException unreadable) {
                replacedFiles = List.of();
            }
        }
        return replacedFiles;
    }

    private @Nullable FileProgress fileProgress() {
        var operation = files.getActiveOperation();
        if (operation == null) {
            return null;
        }
        if (files.exportCancellationRequested()) {
            return new FileProgress(
                    "Cancelling\u2026", "Removing the temporary files", "Cancelling, removing the temporary files", -1);
        }
        var verb = switch (operation) {
            case EXPORT, EXPORT_PATH_NODES -> "Exporting";
            case SAVE_SOURCE, SAVE_SOURCE_PATH_NODES -> "Saving";
            case SAVE_PATCH -> "Saving the patch";
            case LOAD_PATCH -> "Loading the patch";
        };
        var graph = files.getPathNodeProgress();
        var regions = files.getExportProgress();
        if (regions != null && regions.total() > 0 && regions.done() < regions.total()) {
            var count = Math.min(regions.done() + 1, regions.total()) + " of " + regions.total();
            return new FileProgress(
                    verb + " region " + count,
                    regions.file(),
                    "Region " + count + (regions.file().isBlank() ? "" : " \u00b7 " + regions.file()),
                    (double) regions.done() / regions.total());
        }
        if (graph != null
                && (operation == Operation.EXPORT_PATH_NODES || operation == Operation.SAVE_SOURCE_PATH_NODES)) {
            return new FileProgress(
                    "Rebuilding pathnode",
                    graph.detail(),
                    graph.detail(),
                    graph.total() > 0 ? (double) graph.completed() / graph.total() : -1);
        }
        if (regions != null && regions.total() > 0) {
            return new FileProgress(verb + "\u2026", "Putting the files in place", "Putting the files in place", 1);
        }
        return new FileProgress(verb + "\u2026", "", "", -1);
    }

    private boolean isRunning(Operation... operations) {
        var current = files.getActiveOperation();
        for (var operation : operations) {
            if (operation == current) {
                return true;
            }
        }
        return false;
    }

    private void drawOperationCard(String id) {
        var progress = fileProgress();
        if (progress == null) {
            return;
        }
        ImGui.beginDisabled(!files.canCancelExport());
        if (GeodataWidgets.progress(id, progress.title(), progress.detail(), progress.fraction(), "Cancel")) {
            fileMessage = files.cancelExport() ? "" : "The files are being put in place, so it can no longer stop.";
        }
        ImGui.endDisabled();
        GeodataWidgets.tip("Cancel removes the temporary files and leaves the folders as they were.");
    }

    private void drawFileResult(Operation... operations) {
        if (fileOperation == null || fileDone.isBlank() && fileMessage.isBlank()) {
            return;
        }
        for (var operation : operations) {
            if (operation == fileOperation) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                ImGui.pushTextWrapPos(0);
                if (!fileDone.isBlank()) {
                    ImGui.textColored(GeodataWidgets.OPEN, fileDone);
                }
                if (!fileMessage.isBlank()) {
                    ImGui.textColored(GeodataWidgets.WARNING, fileMessage);
                }
                ImGui.popTextWrapPos();
                if (!fileDone.isBlank() && fileWritten != null) {
                    GeodataWidgets.space(GeodataWidgets.ROW);
                    GeodataWidgets.showFolder("geo-file-result-folder", fileWritten);
                }
                return;
            }
        }
    }

    private void drawMessages() {
        ImGui.pushTextWrapPos(0);
        if (!done.isBlank()) {
            ImGui.textColored(GeodataWidgets.MUTED, done);
        }
        if (!message.isBlank()) {
            ImGui.textColored(GeodataWidgets.WARNING, message);
        }
        ImGui.popTextWrapPos();
    }

    private void saveOptions(GeodataState state) {
        ImGui.checkbox("Back up the files it replaces", backup);
        GeodataWidgets.tip("Before replacing anything, copies each file that changes into a new folder next to the "
                + "geodata folder, named after it and the time.");
        var data = state.getSourceSnapshot();
        if (data != null && data.getFormat() != GeoFormat.L2OFF) {
            pathNodes.set(false);
            return;
        }
        ImGui.checkbox("Rebuild pathnode", pathNodes);
        GeodataWidgets.tip("Rebuilds the server's pathnode graph from the edited geodata, so monsters find paths "
                + "through your changes. It takes a while for the whole world. Without it, an existing graph no longer "
                + "matches the edited cells.\n\n" + SERVER_HELP);
        if (pathNodes.get()) {
            drawZoneTableChoice(state, "pathnode-table");
        }
    }

    private boolean exportPathNodesOption(GeodataState state, GeoFormat target) {
        if (target != GeoFormat.L2OFF) {
            return false;
        }
        ImGui.checkbox("With pathnode", exportPathNodes);
        GeodataWidgets.tip(
                "Also builds the server's pathnode graph for the copy, so monsters find paths on an L2OFF server. From"
                        + " an L2J folder the regions are converted first. It takes a while for the whole world.\n\n"
                        + SERVER_HELP);
        if (!exportPathNodes.get()) {
            return false;
        }
        drawZoneTableChoice(state, "export-pathnode-table");
        return true;
    }

    private void drawZoneTableChoice(GeodataState state, String id) {
        var picked = GeodataWidgets.segmented(id, ZONE_TABLE_LABELS, zoneTableChoice);
        if (picked >= 0) {
            zoneTableChoice = picked;
        }
        var auto = autoZoneTable(state);
        if (zoneTableChoice == 0 && autoTable == null) {
            ImGui.pushTextWrapPos(0);
            ImGui.textColored(GeodataWidgets.WARNING, "Server: " + auto + ".");
            ImGui.popTextWrapPos();
            GeodataWidgets.tip(SERVER_HELP);
        } else {
            hint(zoneTableChoice == 0 ? "Server: " + auto : SERVER_HELP);
        }
    }

    private boolean isServerUnknown() {
        return isServerUnknown(pathNodes.get());
    }

    private boolean isServerUnknown(boolean withPathNodes) {
        return withPathNodes && zoneTableChoice == 0 && autoTable == null;
    }

    private ZoneTable chosenZoneTable() {
        return switch (zoneTableChoice) {
            case 1 -> ZoneTable.Y10_TO_25;
            case 2 -> ZoneTable.Y10_TO_26;
            default -> null;
        };
    }

    private @Nullable HeightRule heightRule(List<GeoPoint> points, @Nullable Bounds rectangle) {
        if (tool != EditTool.HEIGHT) {
            return null;
        }
        return switch (heightMode) {
            case FLAT -> null;
            case SLOPE -> {
                var from = rectangle != null ? rectangleStartOf : points.isEmpty() ? null : points.getFirst();
                var to = rectangle != null ? rectangleEndOf : points.isEmpty() ? null : points.getLast();
                yield from == null || to == null ? null : new Slope(from, to, Math.max(0, stepHeight.get()));
            }
            case SMOOTH -> new Smooth();
            case RAISE -> new Offset(Math.round(raiseBy.get() / 8f) * 8);
        };
    }

    enum HeightMode {
        FLAT("Flat", "Every painted layer at one height: the height under the first click, or the one typed below."),
        SLOPE(
                "Slope",
                "Heights rise or fall evenly from the stroke's first point to its last, along the line between them."
                        + " With a step, they round to stairs."),
        SMOOTH(
                "Smooth",
                "Each painted layer takes the average height of its walkable neighbors within the tolerance, once per"
                        + " stroke."),
        RAISE("Raise", "Each painted layer goes up by the amount below, or down when it is negative.");

        final String label;
        final String help;

        HeightMode(String label, String help) {
            this.label = label;
            this.help = help;
        }
    }

    private void drawHeightControls() {
        GeodataWidgets.space(GeodataWidgets.ROW);
        var labels = new String[HeightMode.values().length];
        for (var index = 0; index < labels.length; index++) {
            labels[index] = HeightMode.values()[index].label;
        }
        var picked = GeodataWidgets.segmented("edit-height-mode", labels, heightMode.ordinal());
        if (picked >= 0) {
            heightMode = HeightMode.values()[picked];
        }
        GeodataWidgets.tip(heightMode.help);
        switch (heightMode) {
            case FLAT -> {
                GeodataWidgets.space(GeodataWidgets.ROW);
                if (ImGui.checkbox("Use height under first click", heightFromStroke)) {
                    heightFromStroke = !heightFromStroke;
                }
                ImGui.beginDisabled(heightFromStroke);
                GeodataWidgets.integer("Height", paintHeight, 8);
                ImGui.endDisabled();
                ImGui.textColored(GeodataWidgets.FAINT, "Alt+click a cell to take its height.");
            }
            case SLOPE -> {
                GeodataWidgets.space(GeodataWidgets.ROW);
                GeodataWidgets.integer("Step every", stepHeight, 8);
                GeodataWidgets.tip(
                        "Stairs: heights round to steps of this many units from the first point. 0 makes an even"
                                + " slope.");
                hint("Press on the lower or upper end and drag to the other. The ground between takes the line's"
                        + " height.");
            }
            case SMOOTH -> hint("Drag over bumps and holes. Floors more than the tolerance away are not averaged in.");
            case RAISE -> {
                GeodataWidgets.space(GeodataWidgets.ROW);
                GeodataWidgets.integer("By", raiseBy, 8);
                GeodataWidgets.tip("Units, in steps of 8. A negative number lowers.");
            }
        }
    }

    private int[] tolerance() {
        return switch (tool) {
            case REBUILD -> rebuildTolerance;
            case FLOOR -> floorTolerance;
            default -> tolerance;
        };
    }

    private @Nullable GeoPoint pointOnPlane(int planeHeight) {
        if (rayOrigin == null || rayDirection == null || Math.abs(rayDirection.z()) < 1e-6) {
            return null;
        }
        var along = (planeHeight - rayOrigin.z()) / rayDirection.z();
        if (!(along > 0) || along * Math.hypot(rayDirection.x(), rayDirection.y()) > FLOOR_REACH) {
            return null;
        }
        return new GeoPoint(
                rayOrigin.x() + rayDirection.x() * along, rayOrigin.y() + rayDirection.y() * along, planeHeight);
    }

    private int brushColor() {
        return switch (tool) {
            case BLOCK -> GeodataWidgets.BLOCKED;
            case OPEN -> GeodataWidgets.OPEN;
            case REBUILD -> ImColor.rgb(180, 140, 255);
            default -> GeodataWidgets.ACCENT;
        };
    }

    private void ensureSession(GeodataState state) {
        if (state.editing != null) {
            return;
        }
        var source = state.getSourceSnapshot();
        if (source == null || source.getFingerprints().isEmpty()) {
            message = "Load geodata before editing.";
            return;
        }
        state.startEditing(source);
        message = "";
    }

    private void applyChange(GeodataState state, Runnable action) {
        if (files.isBusy()) {
            message = "Wait until the files are written. Edits are paused until then.";
            return;
        }
        ensureSession(state);
        if (state.editing == null) {
            return;
        }
        var selected = state.inspection.selected;
        try {
            action.run();
            state.followEdit(selected);
            heightEditRef = null;
        } catch (RuntimeException exception) {
            message = exception.getMessage();
        }
    }

    private void fillDefaultPaths(GeodataState state) {
        var source = state.getSourceSnapshot() == null
                ? null
                : state.getSourceSnapshot().getSourcePath();
        if (source == null || source.equals(defaultsFor) || source.getParent() == null) {
            return;
        }
        defaultsFor = source;
        exportFormat = null;
        var name =
                source.getFileName() == null ? "geodata" : source.getFileName().toString();
        if (patchPath.get().isBlank()) {
            patchPath.set(source.getParent().resolve(name + "-patch.json").toString());
        }
        if (exportPath.get().isBlank()) {
            exportPath.set(source.getParent().resolve(name + "-edited").toString());
        }
    }

    private void drawEdits(GeodataState state) {
        var document = state.editing.getDocument();
        if (diffRevision != document.revision()) {
            var cells = new ArrayList<>(document.getChangedCells().keySet());
            cells.sort(Comparator.<CellKey, GeoTile>comparing(
                            key -> GeoTile.fromWorld(key.x(), key.y()),
                            Comparator.comparingInt(GeoTile::x).thenComparingInt(GeoTile::y))
                    .thenComparingInt(CellKey::x)
                    .thenComparingInt(CellKey::y));
            diffCells = List.copyOf(cells);
            diffRevision = document.revision();
            var counts = new LinkedHashMap<GeoTile, Integer>();
            for (var key : cells) {
                counts.merge(GeoTile.fromWorld(key.x(), key.y()), 1, Integer::sum);
            }
            diffRegions = counts;
        }
        var data = state.queryData();
        var listed = 0;
        var index = 0;
        for (var entry : diffRegions.entrySet()) {
            var tile = entry.getKey();
            var name = tile.x() + "_" + tile.y();
            GeodataWidgets.space(GeodataWidgets.ROW);
            var right = ImGui.getCursorPosX() + ImGui.getContentRegionAvailX() - 72;
            ImGui.alignTextToFramePadding();
            ImGui.textUnformatted(name);
            ImGui.sameLine();
            ImGui.alignTextToFramePadding();
            ImGui.textColored(
                    GeodataWidgets.MUTED,
                    String.format(Locale.ROOT, "%,d %s", entry.getValue(), entry.getValue() == 1 ? "cell" : "cells"));
            ImGui.sameLine(right);
            if (HudWidgets.button("edit-revert-" + name, Glyph.UNDO, "Revert", 72, 24, false, true)) {
                var cells = revertRegion(state, tile);
                done = cells < 0
                        ? ""
                        : "Reverted "
                                + name
                                + ": "
                                + cells
                                + (cells == 1 ? " cell is" : " cells are")
                                + " as in the file again.";
                return;
            }
            GeodataWidgets.tip("Drops every edit of " + name + " as one undo step.");
            var shownHere = 0;
            while (index < diffCells.size()
                    && GeoTile.fromWorld(
                                    diffCells.get(index).x(),
                                    diffCells.get(index).y())
                            .equals(tile)) {
                var key = diffCells.get(index);
                index++;
                if (listed >= REVIEW_ROWS) {
                    continue;
                }
                listed++;
                shownHere++;
                var before = document.getBase().cellAt(key.x(), key.y());
                var after = data.cellAt(key.x(), key.y());
                ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.MUTED);
                var picked = ImGui.selectable(key.x()
                        + ", "
                        + key.y()
                        + "   "
                        + layerText(before.layers())
                        + "  \u2192  "
                        + layerText(after.layers())
                        + "##edit-diff-"
                        + key.x()
                        + "-"
                        + key.y());
                ImGui.popStyleColor();
                if (picked) {
                    state.inspection.select(
                            data, key.x(), key.y(), after.layers().isEmpty() ? -1 : 0);
                    var layers = after.layers().isEmpty() ? before.layers() : after.layers();
                    flyTo = new GeoPoint(
                            key.x() + 8,
                            key.y() + 8,
                            layers.isEmpty() ? 0 : layers.getFirst().height());
                }
                GeodataWidgets.tip("Fly to this cell and select it");
            }
            if (shownHere < entry.getValue()) {
                ImGui.textColored(
                        GeodataWidgets.FAINT,
                        String.format(
                                Locale.ROOT,
                                "%s%,d more %s",
                                shownHere == 0 ? "" : "and ",
                                entry.getValue() - shownHere,
                                entry.getValue() - shownHere == 1 ? "cell" : "cells"));
            }
        }
    }

    private void saveSource(GeodataState state) {
        try {
            files.saveToSource(state.editing, pathNodes.get(), backup.get(), chosenZoneTable());
            fileMessage = "";
            fileDone = "";
            fileOperation = pathNodes.get() ? Operation.SAVE_SOURCE_PATH_NODES : Operation.SAVE_SOURCE;
        } catch (RuntimeException exception) {
            fileMessage = exception.getMessage();
            fileOperation = pathNodes.get() ? Operation.SAVE_SOURCE_PATH_NODES : Operation.SAVE_SOURCE;
            saveThenLeave = false;
        }
    }

    private void submit(GeodataState state, Operation operation, String path) {
        try {
            if (operation == Operation.EXPORT_PATH_NODES) {
                files.exportPathNodes(state.editing, Path.of(path), chosenZoneTable(), false);
            } else {
                files.submit(operation, state.editing, Path.of(path), replace.get());
            }
            fileMessage = "";
            fileDone = "";
            fileOperation = operation;
        } catch (RuntimeException exception) {
            fileMessage = exception.getMessage();
            fileOperation = operation;
            saveThenLeave = false;
        }
    }

    private String autoZoneTable(GeodataState state) {
        var snapshot = state.getSourceSnapshot();
        var source = snapshot == null ? null : snapshot.getSourcePath();
        if (source == null) {
            autoTable = null;
            zoneTableSource = null;
            return "follows the source pathnode.idx";
        }
        if (!source.equals(zoneTableSource) || snapshot.getSourceEpoch() != zoneTableEpoch) {
            zoneTableSource = source;
            zoneTableEpoch = snapshot.getSourceEpoch();
            try {
                autoTable = PathNodeGraph.zoneTable(source).orElse(null);
                autoZoneTable = autoTable == null
                        ? "the source has no pathnode.idx, so pick the server"
                        : autoTable.getZones() + " zones, as in the source pathnode.idx";
            } catch (IOException error) {
                autoTable = null;
                autoZoneTable = "the source pathnode.idx is not recognized, so pick the server";
            }
        }
        return autoZoneTable;
    }

    private void poll(GeodataState state) {
        finishRebuilds(state);
        var result = files.poll(state.editing);
        if (result != null) {
            fileMessage = result.applied() ? "" : result.message();
            fileDone = result.applied() ? result.message() : "";
            fileOperation = result.operation();
            fileWritten = result.applied() && result.operation() != Operation.LOAD_PATCH ? result.path() : null;
            var savedSource = result.applied()
                    && (result.operation() == Operation.SAVE_SOURCE
                            || result.operation() == Operation.SAVE_SOURCE_PATH_NODES);
            if (savedSource && !rebuiltRegions.isEmpty() && state.editing != null) {
                try {
                    GeoRegionCatalog.markRebuilt(
                            state.editing.getDocument().getBase().getSourcePath(), rebuiltRegions);
                } catch (IOException | RuntimeException unwritable) {
                    LogManager.getLogger(GeodataEditingPanel.class)
                            .warn("Could not note the rebuilt regions: {}", unwritable.toString());
                }
                rebuiltRegions.clear();
            }
            if (saveThenLeave
                    && (result.operation() == Operation.SAVE_PATCH || savedSource)
                    && result.applied()
                    && !state.editing.hasUnsavedChanges()) {
                var leave = pendingLeave;
                approveLeave(state);
                if (savedSource && leave == Leave.END_EDITING) {
                    state.requestReload();
                }
            } else if (savedSource && !state.editing.hasUnsavedChanges()) {
                state.requestReload();
            }
            saveThenLeave = false;
            if (state.queryData() != null) {
                state.inspection.update(state.queryData());
            }
        }
    }

    private void approveLeave(GeodataState state) {
        approvedLeave = pendingLeave;
        pendingLeave = null;
        saveThenLeave = false;
        state.discardEditingAndChangeSource();
        tool = EditTool.EDGES;
    }

    private void cancelStroke() {
        strokeSeed = null;
        floorPointer = null;
        stroke.clear();
        strokeRectangle = false;
    }

    private Bounds rectangleBounds() {
        var minX = Math.floorDiv((int) Math.floor(Math.min(rectangleStart.x(), rectangleEnd.x())), 16) * 16;
        var minY = Math.floorDiv((int) Math.floor(Math.min(rectangleStart.y(), rectangleEnd.y())), 16) * 16;
        var maxX = Math.floorDiv((int) Math.floor(Math.max(rectangleStart.x(), rectangleEnd.x())), 16) * 16 + 16;
        var maxY = Math.floorDiv((int) Math.floor(Math.max(rectangleStart.y(), rectangleEnd.y())), 16) * 16 + 16;
        return new Bounds(minX, minY, maxX, maxY);
    }

    private static @Nullable GeoTile focusTile(GeodataState state) {
        var selected = state.inspection.selected;
        if (selected != null) {
            return GeoTile.fromWorld(selected.ref().x(), selected.ref().y());
        }
        var focus = state.viewFocus;
        return focus == null ? null : GeoTile.fromWorld((int) Math.floor(focus.x()), (int) Math.floor(focus.y()));
    }

    private static GeoCellRef rebase(GeodataState state, GeoCellRef ref) {
        return state.editing.select(ref.x(), ref.y(), ref.layer());
    }

    private static void warning(String text) {
        ImGui.pushTextWrapPos(0);
        ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.WARNING);
        ImGui.textUnformatted(text);
        ImGui.popStyleColor();
        ImGui.popTextWrapPos();
    }

    private static String exportHint(GeoFormat source, GeoFormat target, boolean changed, boolean withPathNodes) {
        if (target != source) {
            return "Converts every region from "
                    + source.name()
                    + " to "
                    + target.name()
                    + (changed ? ", with your changes" : "")
                    + (withPathNodes ? ", and builds a pathnode graph for it." : ".");
        }
        if (!changed && !withPathNodes) {
            var other = source == GeoFormat.L2OFF ? GeoFormat.L2J : GeoFormat.L2OFF;
            return "No changes to export. Pick " + other.name() + " to convert the folder.";
        }
        return "Writes a copy of the folder with "
                + (changed ? "your changes" : "")
                + (changed && withPathNodes ? " and " : "")
                + (withPathNodes ? "a rebuilt pathnode graph" : "")
                + ". The folder itself stays as it is.";
    }

    private static GeoFormat sourceFormat(GeodataState state) {
        var source = state.getSourceSnapshot();
        return source == null ? GeoFormat.L2OFF : source.getFormat();
    }

    private static String defaultExportPath(GeodataState state, GeoFormat target) {
        var snapshot = state.getSourceSnapshot();
        var source = snapshot == null ? null : snapshot.getSourcePath();
        if (source == null || source.getParent() == null) {
            return null;
        }
        var name =
                source.getFileName() == null ? "geodata" : source.getFileName().toString();
        var suffix =
                target == snapshot.getFormat() ? "-edited" : "-" + target.name().toLowerCase(Locale.ROOT);
        return source.getParent().resolve(name + suffix).toString();
    }

    private static String filesText(int count) {
        return count + (count == 1 ? " file" : " files");
    }

    private static String changedText(GeoEditingSession session) {
        var cells = session.getDocument().getChangedCellCount();
        return cells + (cells == 1 ? " changed cell" : " changed cells");
    }

    private record FileProgress(String title, String detail, String brief, double fraction) {}

    private static String unsavedText(int cells) {
        return cells + (cells == 1 ? " changed cell is" : " changed cells are")
                + " not saved. Keep them before leaving?";
    }

    private static void drawExperimentalWarning() {
        ImGui.pushTextWrapPos(0);
        ImGui.textColored(
                GeodataWidgets.WARNING,
                "Geodata editing is experimental. Keep a copy of the folder, or leave the backup on.");
        ImGui.popTextWrapPos();
        ImGui.dummy(0, 2);
    }

    private static void hint(String text) {
        ImGui.pushTextWrapPos(0);
        ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.MUTED);
        ImGui.textUnformatted(text);
        ImGui.popStyleColor();
        ImGui.popTextWrapPos();
    }

    private static void drawPathExplanation(GeodataState state, String typed, boolean folder) {
        Path path;
        try {
            path = Path.of(typed).toAbsolutePath().normalize();
        } catch (RuntimeException invalid) {
            ImGui.textColored(GeodataWidgets.WARNING, "This is not a valid path.");
            return;
        }
        var name = path.getFileName();
        var parent = path.getParent();
        if (name == null || parent == null) {
            return;
        }
        var source = state.getSourceSnapshot() == null
                ? null
                : state.getSourceSnapshot().getSourcePath();
        var beside = source != null
                && parent.equals(source.toAbsolutePath().normalize().getParent());
        ImGui.pushTextWrapPos(0);
        var exists = folder && Files.isDirectory(path);
        ImGui.textColored(
                GeodataWidgets.MUTED,
                (folder ? exists ? "Writes into the folder " : "Creates the folder " : "Saves the file ") + name);
        ImGui.textColored(
                GeodataWidgets.FAINT,
                "in " + shortFolder(parent, ImGui.getContentRegionAvailX() - 30)
                        + (beside ? ", next to the geodata folder" : ""));
        ImGui.popTextWrapPos();
        if (ImGui.isItemHovered()) {
            HudWidgets.showTooltip(path.toString());
        }
    }

    private static Path parentOf(String typed) {
        try {
            var parent = Path.of(typed).toAbsolutePath().normalize().getParent();
            return parent != null && Files.isDirectory(parent) ? parent : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String nameOf(String typed, String fallback) {
        try {
            var name = Path.of(typed).getFileName();
            return name == null ? fallback : name.toString();
        } catch (RuntimeException invalid) {
            return fallback;
        }
    }

    private static String folderName(GeodataState state) {
        var source = state.getSourceSnapshot() == null
                ? null
                : state.getSourceSnapshot().getSourcePath();
        return source == null || source.getFileName() == null
                ? "the geodata folder"
                : source.getFileName().toString();
    }

    private static void drawEdgeLine(
            Camera camera, ImDrawList draw, Selection cell, Direction direction, boolean open, int width, int height) {
        var x = cell.ref().x();
        var y = cell.ref().y();
        float x1 = x, y1 = y, x2 = x + 16, y2 = y + 16;
        switch (direction) {
            case NORTH -> y2 = y;
            case SOUTH -> y1 = y + 16;
            case WEST -> x2 = x;
            default -> x1 = x + 16;
        }
        var z = cell.height() + 2f;
        var from = camera.projectToScreenRaw(new Vec3(x1, y1, z), width, height);
        var to = camera.projectToScreenRaw(new Vec3(x2, y2, z), width, height);
        if (from == null || to == null) {
            return;
        }
        draw.addLine(from.getX(), from.getY(), to.getX(), to.getY(), ImColor.rgba(8, 14, 20, 200), 7);
        draw.addLine(
                from.getX(), from.getY(), to.getX(), to.getY(), open ? GeodataWidgets.OPEN : GeodataWidgets.BLOCKED, 4);
    }

    private record PendingEdge(Selection cell, Direction direction) {}

    private static String edgeKey(Selection cell, Direction direction) {
        return cell.ref().x() + "," + cell.ref().y() + "," + cell.ref().layer() + "," + direction;
    }

    private static void drawLabel(ImDrawList draw, float x, float y, String text) {
        var textWidth = ImGui.calcTextSizeX(text);
        draw.addRectFilled(
                x - 6, y - 3, x + textWidth + 6, y + ImGui.getFontSize() + 3, ImColor.rgba(8, 14, 20, 220), 5f);
        draw.addText(x, y, GeodataWidgets.TEXT, text);
    }

    private static GeoCellRef rebase(GeodataState state, Selection selection) {
        var ref = selection.ref();
        return state.editing.select(ref.x(), ref.y(), ref.layer());
    }

    private static String layerSummary(List<Layer> layers) {
        if (layers.isEmpty()) {
            return "empty";
        }
        var text = new StringBuilder();
        for (var layer : layers) {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(layer.height()).append('/').append(layer.nswe());
            if (text.length() > 96) {
                text.append(" …");
                break;
            }
        }
        return text.toString();
    }
}
