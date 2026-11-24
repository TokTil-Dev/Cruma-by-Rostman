package com.rost.cruma.viewer.editing;

import static com.rost.cruma.viewer.editing.EditorUi.BUTTON_HEIGHT;
import static com.rost.cruma.viewer.editing.EditorUi.tip;

import com.rost.cruma.core.editing.ActorRecord;
import com.rost.cruma.core.editing.ActorTransform;
import com.rost.cruma.core.editing.ActorTransform.Rotator;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.editing.BspSurfaceFlag;
import com.rost.cruma.core.editing.BspSurfaceMapping;
import com.rost.cruma.core.editing.BspSurfaceSelect;
import com.rost.cruma.core.editing.LightRecord;
import com.rost.cruma.core.editing.MaterialReference;
import com.rost.cruma.core.editing.PackageEditDocument;
import com.rost.cruma.core.editing.PackageEditDocument.BrushFacts;
import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.editing.ActorClipboard.Clip;
import com.rost.cruma.viewer.editing.BoundsAlign.Edge;
import com.rost.cruma.viewer.editing.EditingCheck.Actor;
import com.rost.cruma.viewer.editing.EditingCheck.Finding;
import com.rost.cruma.viewer.editing.EditingCheck.GeodataSource;
import com.rost.cruma.viewer.editing.EditingCheck.Input;
import com.rost.cruma.viewer.editing.EditingCheck.Kind;
import com.rost.cruma.viewer.editing.EditingCheck.Result;
import com.rost.cruma.viewer.editing.EditingCheck.SpawnSource;
import com.rost.cruma.viewer.editing.EditingCheck.SpawnSpot;
import com.rost.cruma.viewer.editing.EditingCheck.Surface;
import com.rost.cruma.viewer.editing.EditingSession.BaseReader;
import com.rost.cruma.viewer.editing.EditingSession.BrushRef;
import com.rost.cruma.viewer.editing.EditingSession.Change;
import com.rost.cruma.viewer.editing.EditingSession.ChangeKind;
import com.rost.cruma.viewer.editing.EditingSession.MaterialChoice;
import com.rost.cruma.viewer.editing.EditingSession.PivotMode;
import com.rost.cruma.viewer.editing.EditingSession.Placement;
import com.rost.cruma.viewer.editing.EditingSession.SurfaceRef;
import com.rost.cruma.viewer.editing.EditorUi.Tone;
import com.rost.cruma.viewer.editing.GroundStick.Anchor;
import com.rost.cruma.viewer.editing.MaterialSlots.Slot;
import com.rost.cruma.viewer.editing.MeshLibrary.MaterialEntry;
import com.rost.cruma.viewer.editing.PackageExportService.PackageSummary;
import com.rost.cruma.viewer.editing.PackageExportService.Plan;
import com.rost.cruma.viewer.editing.Placing.Options;
import com.rost.cruma.viewer.editing.TerrainCapabilityPanel.Host;
import com.rost.cruma.viewer.editing.TerrainCapabilityPanel.TerrainChange;
import com.rost.cruma.viewer.launch.FileManager;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.SceneInspection;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import com.rost.cruma.viewer.ui.FindingWidgets;
import com.rost.cruma.viewer.ui.FindingWidgets.FindingList;
import com.rost.cruma.viewer.ui.FindingWidgets.FindingList.Group;
import com.rost.cruma.viewer.ui.FindingWidgets.Severity;
import com.rost.cruma.viewer.ui.FolderPicker;
import com.rost.cruma.viewer.ui.FontManager;
import com.rost.cruma.viewer.ui.GeodataWidgets;
import com.rost.cruma.viewer.ui.GeodataWidgets.FooterButton;
import com.rost.cruma.viewer.ui.GeodataWidgets.ToolTab;
import com.rost.cruma.viewer.ui.GeodataWorkspace.Viewport;
import com.rost.cruma.viewer.ui.HudWidgets;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.IsolateControls;
import com.rost.cruma.viewer.ui.Motion;
import com.rost.cruma.viewer.ui.NativeDialogs;
import com.rost.cruma.viewer.ui.NativeDialogs.Outcome;
import imgui.ImColor;
import imgui.ImGui;
import imgui.ImGuiListClipper;
import imgui.extension.imguizmo.ImGuizmo;
import imgui.extension.imguizmo.flag.Mode;
import imgui.extension.imguizmo.flag.Operation;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiPopupFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImFloat;
import imgui.type.ImString;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class EditingPanel {
    static final String COMMAND_KEY =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac") ? "Cmd" : "Ctrl";

    static final String PAWNS_NOT_COPIED = "Pawns cannot be copied or saved as a prefab. Duplicate copies them.";

    static final String PAWN_NOT_PLACED = "Place places static meshes. Duplicate copies a pawn.";

    private static final String SHORTCUTS = """
        Q / P / T / B: Select, Place, Sculpt, Paint tool
        I / U: Lights, Surfaces tool
        Lights: N new light (Shift+N: spotlight), Del delete, Ctrl+D copy, arrows move it
        Lights: Ctrl+click or a rectangle chooses several, Ctrl+Shift+F aims a spotlight from the camera
        Surfaces: Shift+B brush, Shift+T texture, Shift+J / C / W / F / Y adjacent all, coplanar, walls, floors, slants
        Surfaces: Shift+Q reverse, Shift+A all, Shift+N none, Shift+M / R memorize, recall, Shift+O / U / X or, and, xor
        Place: click places, Ctrl+click on an object takes its mesh
        Library: drag a mesh into the view to place it there
        Click: select an object, double-click: focus it, click again at the same place: the next object behind
        Ctrl+click: add or remove, Shift+click: add
        Drag: select the visible objects in a rectangle (Alt as you let go: also behind others, Ctrl or Shift adds)
        W / E / R: move, rotate, scale tool; Alt+drag the gizmo: copy
        Drag the gizmo along one axis and type a number: the exact distance, degrees or factor; Enter applies, Esc cancels
        Alt+drag elsewhere: orbit around the selection
        Arrows, Page Up / Down: nudge by the grid step (Shift: ten steps)
        End: drop to the ground (Shift: follow its slope)
        Hold V and drag: follow the surface under the pointer and snap to the objects near it
        Hold M and drag: measure from point to point
        Ctrl+G / Shift+Ctrl+G: group, ungroup; a second click on a group's object takes it alone, Esc the group
        H / Shift+H / Alt+H / L: hide, isolate, show all, lock
        Right click: menu; on Undo or Redo: the list of steps
        Ctrl+D: duplicate, Del or Backspace: delete selection
        Ctrl+C / Ctrl+V: copy, paste under the pointer
        [ / ] or Ctrl+wheel: brush size
        Shift+[ / ] or Shift+wheel: brush strength, the speed of Raise
        Alt+click: pick a height or a layer
        Ctrl+Z / Ctrl+Y: undo, redo
        Ctrl+S: save
        Hold right mouse + WASD: fly the camera, the wheel: its speed\
        """.replace("Ctrl", COMMAND_KEY);

    private static final String SELECT_MENU = "##editingSelectSurfaces";
    private static final int[] SELECT_KEYS = {
        ImGuiKey.B,
        ImGuiKey.T,
        ImGuiKey.I,
        ImGuiKey.J,
        ImGuiKey.C,
        ImGuiKey.W,
        ImGuiKey.F,
        ImGuiKey.Y,
        ImGuiKey.Q,
        ImGuiKey.A
    };
    private static final BspSurfaceSelect.Command[] SELECT_COMMANDS = {
        BspSurfaceSelect.Command.BRUSH,
        BspSurfaceSelect.Command.TEXTURE,
        BspSurfaceSelect.Command.ITEMS,
        BspSurfaceSelect.Command.ADJACENT,
        BspSurfaceSelect.Command.COPLANAR,
        BspSurfaceSelect.Command.WALLS,
        BspSurfaceSelect.Command.FLOORS,
        BspSurfaceSelect.Command.SLANTS,
        BspSurfaceSelect.Command.REVERSE,
        BspSurfaceSelect.Command.ALL
    };
    private static final long CHECK_NANOS = 8_000_000L;
    private static final String PICKER = "##editingMaterialPicker";
    private static final float PICKER_WIDTH = 340;
    private static final float SLOT_SWATCH = 24;
    private static final int SURFACE_TARGET = 1 << 16;
    private static final String MORE_MENU = "##editingMore";
    private static final String PLACE_MENU = "##placeMore";
    private static final String OFFSET_POPUP = "##editingOffsetPopup";
    private static final String MAP_POPUP = "##editingMapPopup";
    private static final String RANDOM_POPUP = "##editingRandomPopup";
    private static final String COORDINATES_POPUP = "##placeCoordinatesPopup";
    private static final String GROUP_POPUP = "##editingGroupPopup";
    private static final String ALIGN_POPUP = "##editingAlignPopup";
    private static final String HISTORY_MENU = "##editingHistory";
    private static final int HISTORY_ROWS = 30;
    private static final int HISTORY_SHOWN = 12;
    private static final float POPUP_WIDTH = 280;
    private static final int PLAN_LINES = 8;
    private static final float ROW_LABEL = 64;
    private static final long NOTE_NANOS = 4_000_000_000L;
    private static final long NOTE_WITH_ACTION_NANOS = 6_000_000_000L;
    private static final int TOAST_SHOWN = "editing/toast".hashCode();
    private static final long PREVIEW_NANOS = 250_000_000L;
    private static final float COPY_PIXELS = 2;
    private static final int[] DIGIT_KEYS = {
        ImGuiKey._0,
        ImGuiKey._1,
        ImGuiKey._2,
        ImGuiKey._3,
        ImGuiKey._4,
        ImGuiKey._5,
        ImGuiKey._6,
        ImGuiKey._7,
        ImGuiKey._8,
        ImGuiKey._9
    };
    private static final int[] KEYPAD_KEYS = {
        ImGuiKey.Keypad0,
        ImGuiKey.Keypad1,
        ImGuiKey.Keypad2,
        ImGuiKey.Keypad3,
        ImGuiKey.Keypad4,
        ImGuiKey.Keypad5,
        ImGuiKey.Keypad6,
        ImGuiKey.Keypad7,
        ImGuiKey.Keypad8,
        ImGuiKey.Keypad9
    };
    private static final int RECENT_MESHES = 6;
    private static final float RECENT_PICTURE = 48;
    private static final float WELCOME_WIDTH = 600;
    private static final int WELCOME_SHOWN = "editing/welcome".hashCode();
    private static final String WELCOME_GUIDE = "First steps in the Guide";
    private static final List<String> WELCOME_STEPS = List.of(
            "1 · Click an object to move it, or press P and pick a mesh in the Library to place one.",
            "2 · T sculpts the ground, B paints it, I adds lights.",
            ("3 · Ctrl+S checks and saves the changed maps to a folder; Install puts them into the client.")
                    .replace("Ctrl", COMMAND_KEY));

    private @Nullable Set<String> isolatedActors;
    private long isolatedRevision;
    private @Nullable SceneInspection inspection;
    private final EditingSession session;
    private final EditingExportCoordinator exports;
    private final TerrainCapabilityPanel terrainCapability;
    private final EditingTimeline timeline;
    private final float[] location = new float[3];
    private final float[] rotation = new float[3];
    private final float[] scale = new float[3];
    private final ImFloat uniform = new ImFloat(1);
    private final ImString output = new ImString(1024);
    private final ImString dependencyPath = new ImString(1024);
    private Plan exportPlan;
    private final ImFloat gridSnap = new ImFloat(16);
    private final ImFloat angleSnap = new ImFloat(15);
    private final ImFloat scaleSnap = new ImFloat(.1f);
    private boolean gridOn = true;
    private boolean angleOn = true;
    private boolean scaleOn = true;
    private boolean stickToGround;
    private @Nullable GroundStick stick;
    private @Nullable Bounds3f dragBounds;
    private final ImString[] locationText = texts(3);
    private final ImString[] rotationText = texts(3);
    private final ImString[] scaleText = texts(4);
    private final ImString[] groupMoveText = texts(3);
    private final ImString[] groupTurnText = texts(3);
    private final ImString[] groupScaleText = texts(4);
    private boolean randomYaw = true;
    private boolean randomScale = true;
    private final ImFloat randomMin = new ImFloat(.9f);
    private final ImFloat randomMax = new ImFloat(1.1f);
    private final Random seeds = new Random();
    private int dragCopies;
    private int dropRequest;
    private boolean moveActorsToTheirTile = true;
    private @Nullable EditorViewport viewport;
    private final WorldGizmo worldGizmo = new WorldGizmo();
    private final float[] snapValues = new float[3];
    private long revision = -1;
    private String feedback = "";
    private String error = "";
    private long noteAt;
    private long errorAt;
    private int noteDot = GeodataWidgets.ACCENT;
    private long toastStamp;
    private String backgroundStatus = "";
    private String backgroundTip = "";
    private @Nullable FontManager fonts;
    private @Nullable String pendingPopup;
    private float popupRight;
    private float popupBottom;
    private boolean moreRequested;
    private final ImString groupName = new ImString(64);
    private @Nullable String groupRenamed;
    private float headerRight;
    private float headerBottom;
    private int pendingHistory;
    private boolean historyRequested;
    private int operation = Operation.TRANSLATE;
    private float[] gizmo;
    private float[] gizmoStart;
    private boolean dragging;
    private boolean captured;
    private boolean localAxes;
    private boolean surfaceSnap;
    private boolean surfaceLatched;
    private boolean copyPending;
    private float pressX;
    private float pressY;
    private final TypedDrag typed = new TypedDrag();
    private int hoverAxis = -1;
    private int dragAxis = -1;
    private boolean dragSettled;
    private String viewTag = "";
    private boolean welcomeShown;
    private boolean helpRequested;
    private float @Nullable [] welcomeArea;
    private float @Nullable [] welcomeLink;
    private Tool tool = Tool.SELECT;
    private final SurfaceChoice selectedSurfaces = new SurfaceChoice();
    private List<SurfaceRef> memorizedSurfaces = List.of();
    private Set<SurfaceRef> memorizedSet = Set.of();
    private boolean brushWireframe;
    private long seenSelectionRequests;
    private @Nullable SurfaceFacts surfaceFacts;
    private final Map<BrushRef, String> brushAnswers = new HashMap<>();
    private long brushAnswersRevision = -1;
    private @Nullable String selectMenuMap;
    private boolean selectMenuRequested;
    private int surfacePanStep = 16;
    private final float[] surfaceScale = new float[2];
    private final ImString[] surfaceScaleText = texts(2);
    private final LightTool lights;
    private boolean reviewOpen;
    private boolean installAfterWrite;
    private boolean installPending;
    private boolean published;
    private boolean revealWritten;
    private int installChanges;
    private boolean focusRequested;
    private boolean reloadRequested;
    private @Nullable ExportSettings exportSettings;
    private String recoveryStatus = "";
    private String recoveryFailure = "";
    private final HashMap<String, FolderPicker> filePickers = new HashMap<>();
    private final FolderPicker outputPicker = new FolderPicker(
            "Choose where to save", "The saved folder is created inside the folder you choose.", false);
    private final ClientInstallPanel install = new ClientInstallPanel();
    private final ArrayList<float[]> hintAreas = new ArrayList<>();
    private final ImString targetMap = new ImString(64);
    private final float[] duplicateOffset = {64, 0, 0};
    private final ImString[] duplicateText = texts(3);
    private final float[] createPosition = new float[3];
    private final ImString[] createText = texts(3);
    private @Nullable PlaceMesh placeMesh;
    private @Nullable Prefab placePrefab;
    private boolean alignToNormal;
    private boolean placeRandomYaw;
    private boolean placeRandomScale;
    private boolean snapPlaceToGrid;
    private final ImFloat placeScaleMin = new ImFloat(.9f);
    private final ImFloat placeScaleMax = new ImFloat(1.1f);
    private long placeSeed = 1;
    private float @Nullable [] pointerNormal;
    private @Nullable LibraryDrag libraryDrag;
    private @Nullable Vector3 cameraLook;
    private @Nullable Vector3 cameraEye;
    private String teleportCommand = "";
    private boolean facePick;
    private boolean placeBrush;
    private final ImFloat spacing = new ImFloat(128);
    private final ImFloat jitter = new ImFloat(.3f);
    private String spacingMesh = "";
    private @Nullable MeshLibrary library;
    private @Nullable MeshLibraryPanel libraryPanel;
    private @Nullable ViewerUiRenderer uiRenderer;
    private boolean libraryRequested;
    private boolean replacePick;
    private Set<String> replaceTargets = Set.of();
    private @Nullable String copied;
    private boolean pasteRequested;
    private @Nullable Path prefabFolder;
    private Clip prefabClip;
    private @Nullable Path prefabWritten;
    private final FolderPicker prefabPicker = FolderPicker.file(
            "Save as prefab",
            "Choose the folder and the name of the prefab file. The Library lists the prefabs of this folder.",
            "Prefab.json");
    private final List<float[]> slotAreas = new ArrayList<>();
    private int lastSlot;
    private int pickerSlot = -1;
    private boolean pickerOpening;
    private boolean pickerClosing;
    private float pickerX;
    private float pickerY;
    private Scope pickerScope = Scope.ACTOR;
    private final ImString pickerFilter = new ImString(256);
    private final ImGuiListClipper pickerClipper = new ImGuiListClipper();
    private List<MaterialEntry> pickerSource = List.of();
    private List<MaterialEntry> pickerShown = List.of();
    private String pickerFiltered = "\u0000";
    private @Nullable String hoveredKey;
    private @Nullable MaterialChoice hoveredChoice;
    private @Nullable String hoverKey;
    private @Nullable MaterialChoice hoverChoice;
    private @Nullable String previewKey;
    private int eyedropperSlot = -1;
    private Scope eyedropperScope = Scope.ACTOR;
    private @Nullable Path browsedFile;
    private List<MaterialReference> browsedMaterials = List.of();
    private int reopenPicker = -1;
    private final FolderPicker packagePicker =
            FolderPicker.file("Choose a texture package", "Pick the folder of the .utx file, and its name below.", "");
    private final FolderPicker imagePicker = FolderPicker.file(
            "Choose an image", "Pick the folder of the PNG, JPEG, BMP or DDS file, and its name below.", "");
    private List<String> imageActors = List.of();
    private int imageSlot;
    private List<SurfaceRef> imageSurfaces = List.of();
    private List<SurfaceRef> pointedSurfaces = List.of();
    private long surfaceListRevision = -1;
    private Set<SurfaceRef> editedSurfaceList = Set.of();
    private List<HiddenSurface> hiddenSurfaceList = List.of();
    private List<DeletedBrush> deletedBrushList = List.of();
    private @Nullable DeletePlan deletePlan;
    private int deletePlanFrame = -1;
    private @Nullable PendingBrushDelete pendingBrushDelete;
    private final ImGuiListClipper hiddenClipper = new ImGuiListClipper();
    private final HoverOnce hoverOnce = new HoverOnce();
    private final ImageImportDialog importDialog = new ImageImportDialog();
    private @Nullable Drop pendingDrop;
    private Map<String, List<String>> placeMeshes = Map.of();
    private Set<String> placeMeshMaps = Set.of();
    private long placeMeshCommands = -1;
    private final Set<String> openedForPlacing = new HashSet<>();
    private Set<String> viewportMaps = Set.of();
    private float bodyScroll;
    private float bodyScrollMax;
    private float bodyHeight;
    private boolean scrollBodyToTop;
    private boolean openHeaders;
    private int shownChanges;
    private final GroundProbe probe;
    private List<String> floating = List.of();
    private long floatingHeights = Long.MIN_VALUE;
    private long floatingActors = Long.MIN_VALUE;
    private boolean floatingPending;
    private @Nullable String hoveredChange;
    private final FindingList changePages = new FindingList(12);
    private final EditingCheck check = new EditingCheck();
    private @Nullable CompletableFuture<Result> checkRun;
    private AtomicBoolean checkCancelled = new AtomicBoolean();
    private Result checkResult;
    private final FindingList findingList = new FindingList(8);
    private final Map<Finding, String> exportProblems = new HashMap<>();
    private float findingsTop;
    private float scrollBodyTo = -1;
    private final Set<Finding> fixedFindings = new HashSet<>();
    private @Nullable Finding selectedFinding;
    private SpawnSource spawnSource;
    private GeodataSource geodataSource;
    private long firstEdit;
    private @Nullable Bounds3f flyRequest;
    private boolean spacingEditing;

    public EditingPanel(EditingSession session) {
        this.session = session;
        exports = session.getExportCoordinator();
        terrainCapability = new TerrainCapabilityPanel(session);
        lights = new LightTool(this, session);
        seenSelectionRequests = session.getSelectionRequests();
        timeline = session.getTimeline();
        probe = session.getGroundProbe();
        terrainCapability.setHost(new Host() {
            @Override
            public @Nullable Float getSelectionBottom() {
                return viewport == null ? null : viewport.getSelectionBottom();
            }

            @Override
            public void drawFloatingMeshes() {
                drawFloatingCallout();
            }

            @Override
            public void showNote(String text) {
                EditingPanel.this.showNote(text);
            }
        });
    }

    enum Tool {
        SELECT("Select", Glyph.CURSOR, "Q", "Select objects and move, turn or scale them with the gizmo."),
        PLACE("Place", Glyph.PLUS, "P", "Choose a mesh, then click a surface in the viewport to place it there."),
        SCULPT("Sculpt", Glyph.TERRAIN, "T", "Raise, lower, flatten and smooth the Terrain under the brush."),
        PAINT("Paint", Glyph.PENCIL, "B", "Paint the layers of the Terrain, such as grass or rock, under the brush."),
        LIGHTS(
                "Lights",
                Glyph.SUN,
                "I",
                "Add lights to the map, and change the brightness, colour, place and radius of a light, copy it or"
                        + " delete it."),
        SURFACES(
                "Surfaces",
                Glyph.LAYERS,
                "U",
                "Choose walls and floors of the level geometry: give them another texture, move, turn, scale or"
                        + " align it, hide them, or delete a sheet or a brush that stands apart.");

        final String label;
        final Glyph glyph;
        final String key;
        final String tip;

        Tool(String label, Glyph glyph, String key, String tip) {
            this.label = label;
            this.glyph = glyph;
            this.key = key;
            this.tip = tip;
        }
    }

    record PlaceMesh(String meshPath, @Nullable Path packageFile) {
        String name() {
            return EditingSession.shortName(meshPath);
        }
    }

    record Prefab(String name, Path file, Clip clip) {}

    record LibraryDrag(@Nullable PlaceMesh mesh, @Nullable Prefab prefab, long texture) {
        String name() {
            return prefab != null ? prefab.name() : mesh == null ? "" : mesh.name();
        }
    }

    enum Scope {
        ACTOR,
        SELECTION,
        MESH
    }

    static boolean actsOnActors(Tool tool) {
        return tool != Tool.LIGHTS && tool != Tool.SURFACES;
    }

    static boolean deletePressed() {
        return ImGui.isKeyPressed(ImGuiKey.Delete, false) || ImGui.isKeyPressed(ImGuiKey.Backspace, false);
    }

    static String faceLabel(Set<String> paths) {
        return "Turn "
                + (paths.size() == 1
                        ? EditingSession.shortName(paths.iterator().next())
                        : EditorUi.plural(paths.size(), "object", "objects"));
    }

    static String getDeleteOffText(int stays, String reason, @Nullable String brushName, int brushSides) {
        if (stays == 0) {
            return "Delete is off: the chosen surfaces are hidden or deleted already.";
        }
        var why = reason.isEmpty() ? "" : " " + reason + ".";
        if (brushName != null) {
            var sides = EditorUi.plural(brushSides, "side", "sides");
            return stays == 1
                    ? "Delete is off. This surface is a side of " + brushName + ", a brush of " + sides + "." + why
                    : "Delete is off. These surfaces are sides of " + brushName + ", a brush of " + sides + "." + why;
        }
        return stays == 1
                ? "Delete is off." + why
                : "Delete is off: none of the " + stays + " chosen surfaces can go. The first one:" + why;
    }

    static String readyToSave(List<PackageSummary> packages) {
        var maps = (int) packages.stream().filter(item -> isMap(item.path())).count();
        return (maps > 0
                        ? EditorUi.plural(maps, "map", "maps")
                        : EditorUi.plural(packages.size(), "package", "packages"))
                + " ready to save";
    }

    static boolean isMap(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".unr");
    }

    public void showSave() {
        openReview(false);
    }

    public boolean capturesMouse() {
        return captured;
    }

    public void setTerrainSession(TerrainViewportSession terrain) {
        terrainCapability.setViewport(terrain);
        exports.setTerrain(terrain);
        timeline.bindTerrain(terrain);
    }

    public void setExportSettings(@Nullable ExportSettings settings) {
        exportSettings = settings;
        if (settings != null) {
            installAfterWrite = settings.isInstallAfterWrite();
            welcomeShown = settings.isEditWelcomeShown();
        }
    }

    public void drawWelcome(float left, float top, float right, float bottom) {
        welcomeArea = null;
        welcomeLink = null;
        if (welcomeShown || reviewOpen || exports.isBusy()) {
            return;
        }
        var width = Math.min(WELCOME_WIDTH, right - left - 2 * GeodataWidgets.BLOCK);
        if (width < 280) {
            return;
        }
        var height = GeodataWidgets.stepsCardHeight(WELCOME_STEPS, width);
        var x = Math.round((left + right - width) * .5f);
        var y = Math.round((top + bottom - height) * .5f);
        welcomeArea = new float[] {x, y, x + width, y + height};
        ImGui.setNextWindowPos(x, y, ImGuiCond.Always);
        ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowMinSize, 1, 1);
        ImGui.begin(
                "##editingWelcome",
                ImGuiWindowFlags.NoDecoration
                        | ImGuiWindowFlags.NoSavedSettings
                        | ImGuiWindowFlags.NoBackground
                        | ImGuiWindowFlags.NoFocusOnAppearing
                        | ImGuiWindowFlags.NoNav
                        | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.popStyleVar(3);
        Motion.open(Motion.appear(WELCOME_SHOWN, Motion.OPEN), 0, 12, .98f);
        var pressed = GeodataWidgets.stepsCard(
                "editingWelcome", Glyph.PENCIL, "Edit this map", WELCOME_STEPS, "Got it", "All keys (F1)", width);
        drawWelcomeGuideLink(x, y, width, height);
        ImGui.end();
        if (pressed == 1) {
            closeWelcome();
        } else if (pressed == 2) {
            helpRequested = true;
        }
    }

    public boolean consumeHelpRequest() {
        var requested = helpRequested;
        helpRequested = false;
        return requested;
    }

    public void setFonts(@Nullable FontManager fonts) {
        this.fonts = fonts;
    }

    @Nullable
    FontManager getFonts() {
        return fonts;
    }

    void setBackgroundStatus(String status, String tip) {
        backgroundStatus = status;
        backgroundTip = tip;
    }

    public void setMoveActorsToTheirTile(boolean value) {
        moveActorsToTheirTile = value;
    }

    public void setTeleportCommand(String template) {
        teleportCommand = template == null ? "" : template;
    }

    String getTeleportCommand() {
        return teleportCommand;
    }

    void showDone(String text) {
        note(text, true);
    }

    public void setRecoveryNote(@Nullable String status, @Nullable String failure) {
        recoveryStatus = status == null ? "" : status;
        recoveryFailure = failure == null ? "" : failure;
    }

    public boolean drawTerrainViewport(
            Camera camera, ViewerRenderBackend backend, float left, float top, float right, float bottom) {
        timeline.sync();
        terrainCapability.updateProbe(probe);
        if (exports.isBusy()) {
            return false;
        }
        var active = reviewOpen
                ? null
                : switch (tool) {
                    case SCULPT -> TerrainEditingController.Tool.SCULPT;
                    case PAINT -> TerrainEditingController.Tool.PAINT;
                    default -> null;
                };
        return terrainCapability.drawViewport(camera, backend, left, top, right, bottom, active);
    }

    public String getError() {
        return error;
    }

    public boolean isSurfaceSnapArmed() {
        return surfaceSnap;
    }

    public void toggleSurfaceSnap() {
        if (surfaceSnap) {
            surfaceSnap = false;
            return;
        }
        if (session.getSelection().isEmpty()) {
            showError("Select objects before snapping to a surface.");
            return;
        }
        tool = Tool.SELECT;
        reviewOpen = false;
        surfaceSnap = true;
    }

    public void showError(String message) {
        error = message == null ? "Unknown editing error" : UserMessages.plain(message);
        if (!error.isEmpty()) {
            errorAt = System.nanoTime();
        }
    }

    public boolean noteIfUneditable(@Nullable String message) {
        if (message == null || !message.startsWith(UserMessages.NOT_IN_MAP)) {
            return false;
        }
        showError("");
        showNote(UserMessages.plain(message));
        return true;
    }

    public boolean consumeFocusRequest() {
        var requested = focusRequested;
        focusRequested = false;
        return requested;
    }

    public boolean consumeReloadRequest() {
        var requested = reloadRequested;
        reloadRequested = false;
        return requested;
    }

    public void handleKeys(boolean panelKeys) {
        var io = ImGui.getIO();
        if (libraryDrag != null && !ImGui.isMouseDown(0) && !ImGui.isMouseReleased(0)) {
            libraryDrag = null;
        }
        if (io.getWantTextInput()
                || ImGui.isAnyItemActive()
                || dragging
                || session.isGestureActive() && !session.isExportLocked()
                || terrainCapability.isStroking()
                || viewport != null && viewport.isScattering()
                || ImGui.isMouseDown(1)
                || ImGui.isPopupOpen("", ImGuiPopupFlags.AnyPopupId | ImGuiPopupFlags.AnyPopupLevel)) {
            return;
        }
        var command = io.getKeyCtrl() || io.getKeySuper();
        var actorKeys = actsOnActors(tool);
        if (command) {
            if (ImGui.isKeyPressed(ImGuiKey.Z, false)) {
                if (io.getKeyShift()) {
                    redo();
                } else {
                    undo();
                }
            } else if (ImGui.isKeyPressed(ImGuiKey.Y, false)) {
                redo();
            } else if (ImGui.isKeyPressed(ImGuiKey.S, false)) {
                if (io.getKeyShift()) {
                    openReview(true);
                } else if (reviewOpen && canWrite()) {
                    write();
                } else {
                    openReview(false);
                }
            } else if (panelKeys
                    && !io.getKeyShift()
                    && ImGui.isKeyPressed(ImGuiKey.D, false)
                    && tool == Tool.LIGHTS
                    && !reviewOpen
                    && !exports.isBusy()
                    && lights.copySelected()) {
            } else if (panelKeys
                    && io.getKeyShift()
                    && ImGui.isKeyPressed(ImGuiKey.F, false)
                    && tool == Tool.LIGHTS
                    && !reviewOpen
                    && !exports.isBusy()
                    && lights.isAimFromCamera()) {
            } else if (panelKeys
                    && actorKeys
                    && ImGui.isKeyPressed(ImGuiKey.G, false)
                    && !session.getSelection().isEmpty()) {
                if (io.getKeyShift()) {
                    ungroupSelection();
                } else {
                    requestGroupName(null, headerRight, headerBottom);
                }
            } else if (panelKeys
                    && actorKeys
                    && !io.getKeyShift()
                    && ImGui.isKeyPressed(ImGuiKey.D, false)
                    && !session.getSelection().isEmpty()
                    && !exports.isBusy()) {
                duplicate();
            } else if (panelKeys
                    && actorKeys
                    && !io.getKeyShift()
                    && ImGui.isKeyPressed(ImGuiKey.C, false)
                    && !session.getSelection().isEmpty()) {
                run(this::copySelection);
            } else if (panelKeys
                    && actorKeys
                    && !io.getKeyShift()
                    && ImGui.isKeyPressed(ImGuiKey.V, false)
                    && !exports.isBusy()) {
                requestPaste();
            }
            return;
        }
        if (!panelKeys || exports.isBusy()) {
            return;
        }
        var shift = io.getKeyShift();
        var alt = io.getKeyAlt();
        if (tool == Tool.LIGHTS && !reviewOpen && lights.handleKeys(shift, alt)) {
            return;
        }
        if (actorKeys && !alt && !session.getSelection().isEmpty() && handleNudge(shift)) {
            return;
        }
        if (actorKeys
                && !alt
                && ImGui.isKeyPressed(ImGuiKey.End, false)
                && !session.getSelection().isEmpty()) {
            requestDrop(shift);
            return;
        }
        if (ImGui.isKeyPressed(ImGuiKey.H, false)) {
            if (alt) {
                showAll();
            } else if (!actorKeys) {
            } else if (shift) {
                isolateSelection();
            } else {
                hideSelection();
            }
            return;
        }
        if (shift && !alt && tool == Tool.SURFACES && !reviewOpen && handleSelectKeys()) {
            return;
        }
        if (shift || alt) {
            return;
        }
        if (ImGui.isKeyPressed(ImGuiKey.L, false)) {
            if (actorKeys) {
                toggleLock();
            }
        } else if (ImGui.isKeyPressed(ImGuiKey.Q, false)) {
            setTool(Tool.SELECT);
        } else if (ImGui.isKeyPressed(ImGuiKey.P, false)) {
            setTool(Tool.PLACE);
        } else if (ImGui.isKeyPressed(ImGuiKey.T, false)) {
            setTool(Tool.SCULPT);
        } else if (ImGui.isKeyPressed(ImGuiKey.B, false)) {
            setTool(Tool.PAINT);
        } else if (ImGui.isKeyPressed(ImGuiKey.I, false)) {
            setTool(Tool.LIGHTS);
        } else if (ImGui.isKeyPressed(ImGuiKey.U, false)) {
            setTool(Tool.SURFACES);
        } else if (actorKeys
                && ImGui.isKeyPressed(ImGuiKey.F, false)
                && !session.getSelection().isEmpty()) {
            focusRequested = true;
        } else if (actorKeys && deletePressed() && !session.getSelection().isEmpty()) {
            deleteSelection();
        } else if (tool == Tool.SURFACES && !reviewOpen && deletePressed() && !selectedSurfaces.isEmpty()) {
            deleteSurfaces();
        }
    }

    float getMoveSnap() {
        var io = ImGui.getIO();
        var step = gridSnap.get();
        return gridOn != (io.getKeyCtrl() || io.getKeySuper()) && step > 0 ? step : 0;
    }

    float getAngleSnapStep() {
        var io = ImGui.getIO();
        var step = angleSnap.get();
        return angleOn != (io.getKeyCtrl() || io.getKeySuper()) && step > 0 ? step : 0;
    }

    float getGridStep() {
        return gridSnap.get() > 0 ? gridSnap.get() : 1;
    }

    void move(Runnable change, @Nullable String label) {
        run(() -> {
            session.beginStep();
            try {
                change.run();
                moveToTheirTiles();
            } finally {
                session.endStep(label);
            }
        });
    }

    void endMove(@Nullable String label) {
        try {
            run(this::moveToTheirTiles);
        } finally {
            session.endStep(label);
        }
        var note = session.takeStepNote();
        if (note != null) {
            showNote(note);
        }
    }

    void requestDrop(boolean slope) {
        if (session.getSelection().isEmpty()) {
            showError("Select objects to drop them to the ground.");
            return;
        }
        dropRequest = slope ? 2 : 1;
    }

    int consumeDropRequest() {
        var request = dropRequest;
        dropRequest = 0;
        return request;
    }

    void requestReplaceMesh() {
        if (session.getSelection().isEmpty()) {
            return;
        }
        replacePick = !replacePick;
        replaceTargets = Set.copyOf(session.getSelection());
        libraryRequested = replacePick;
    }

    void duplicate() {
        var copied = new boolean[1];
        run(() -> {
            session.duplicateSelected(toVector(duplicateOffset));
            copied[0] = true;
        });
        var note = session.takeStepNote();
        if (copied[0] && note != null) {
            showNote(note);
        }
    }

    void deleteSelection() {
        run(session::deleteSelected);
    }

    void resetSelection() {
        run(session::resetSelected);
    }

    void requestFocus() {
        focusRequested = true;
    }

    void hideSelection() {
        if (session.getSelection().isEmpty()) {
            return;
        }
        var count = session.getSelection().size();
        run(() -> session.hide(List.copyOf(session.getSelection())));
        note(EditorUi.plural(count, "object", "objects") + " hidden. Alt+H shows them.", false);
    }

    public void setInspection(SceneInspection inspection) {
        this.inspection = inspection;
    }

    void isolateSelection() {
        if (session.getSelection().isEmpty()) {
            return;
        }
        isolatedActors = Set.copyOf(session.getSelection());
        isolatedRevision = session.getRevision();
        note("Only the selection shows. Alt+H shows the rest.", false);
    }

    public @Nullable Set<String> getIsolatedActors() {
        if (isolatedActors != null) {
            isolatedActors = IsolatedGroup.after(isolatedActors, session.getSelection(), this::isMadeInSession);
            if (session.getRevision() != isolatedRevision) {
                isolatedRevision = session.getRevision();
                var left = new HashSet<String>();
                for (var actor : isolatedActors) {
                    if (standsInScene(actor)) {
                        left.add(actor);
                    }
                }
                isolatedActors = left.isEmpty() ? null : Set.copyOf(left);
            }
        }
        return isolatedActors;
    }

    public void endIsolate() {
        isolatedActors = null;
    }

    void showAll() {
        if (isolatedActors != null) {
            isolatedActors = null;
            note("", false);
        }
        if (session.getHiddenActors().isEmpty()) {
            return;
        }
        run(() -> session.show(List.copyOf(session.getHiddenActors())));
        note("", false);
    }

    void toggleLock() {
        if (session.getSelection().isEmpty()) {
            return;
        }
        var lock = !session.getLockedActors().containsAll(session.getSelection());
        session.setLocked(session.getSelection(), lock);
        note(
                lock
                        ? EditorUi.plural(session.getSelection().size(), "object", "objects")
                                + " locked: moves and clicks leave them alone. L unlocks."
                        : "Unlocked.",
                false);
    }

    public void requestGroupName(@Nullable String renamed, float right, float bottom) {
        if (renamed == null && session.getSelection().size() < 2) {
            showError("Select two or more objects to group them.");
            return;
        }
        groupRenamed = renamed;
        groupName.set(renamed != null ? renamed : session.nextGroupName());
        openSoon(GROUP_POPUP, right, bottom);
    }

    void ungroupSelection() {
        var name = session.getSelectedGroup();
        if (name == null) {
            name = session.getEnteredGroup();
        }
        if (name == null) {
            showError("The selection is not a group. Select a group in the Objects list or with a click on one of its"
                    + " objects.");
            return;
        }
        ungroup(name);
    }

    public void ungroup(String name) {
        session.ungroup(name);
        note("Ungrouped " + name + ". Its objects stay where they are.", true);
    }

    void align(int axis, Edge edge) {
        arrange(BoundsAlign.align(selectionBounds(), axis, edge), axis, "Align ");
    }

    void sameHeight() {
        arrange(BoundsAlign.sameHeight(selectionBounds()), 2, "Align ");
    }

    void distribute(int axis) {
        arrange(BoundsAlign.distribute(selectionBounds(), axis), axis, "Distribute ");
    }

    void moveSelectionTo(String tile) {
        run(() -> session.transferSelected(tile));
    }

    public void showNote(String text) {
        note(text, false);
    }

    void setViewport(EditorViewport viewport) {
        this.viewport = viewport;
    }

    public boolean handleEscape() {
        if (ImGui.isPopupOpen("", ImGuiPopupFlags.AnyPopupId | ImGuiPopupFlags.AnyPopupLevel)) {
            return true;
        }
        if (libraryDrag != null) {
            libraryDrag = null;
            return true;
        }
        if (cancelDrag()) {
            return true;
        }
        if (terrainCapability.cancelStroke()) {
            return true;
        }
        if (viewport != null && viewport.cancelGesture()) {
            return true;
        }
        if (surfaceSnap) {
            surfaceSnap = false;
            return true;
        }
        if (facePick) {
            facePick = false;
            return true;
        }
        if (eyedropperSlot >= 0) {
            eyedropperSlot = -1;
            return true;
        }
        if (replacePick) {
            replacePick = false;
            return true;
        }
        if (surfaceLatched) {
            surfaceLatched = false;
            return true;
        }
        if (reviewOpen) {
            reviewOpen = false;
            return true;
        }
        if (tool == Tool.LIGHTS && lights.cancel()) {
            return true;
        }
        if (pendingBrushDelete != null) {
            pendingBrushDelete = null;
            return true;
        }
        if (tool != Tool.SELECT) {
            setTool(Tool.SELECT);
            return true;
        }
        if (exports.isBusy()) {
            return false;
        }
        try {
            return session.leaveGroup();
        } catch (RuntimeException failure) {
            showError(failure.getMessage());
            return true;
        }
    }

    public void draw() {
        keepOneChoice();
        slotAreas.clear();
        checkReplacePick();
        exports.poll();
        exportPlan = exports.getPlan();
        if (exports.hasPublished() && !published) {
            revealWritten = true;
            closeWelcome();
        }
        published = exports.hasPublished();
        installWritten();
        if (revision != session.getRevision()) {
            refresh();
            revision = session.getRevision();
        }
        timeline.sync();
        stepThroughHistory();
        if (firstEdit == 0 && timeline.nextUndo() != null) {
            firstEdit = System.currentTimeMillis();
        }
        if (!session.isPreviewingMaterial()) {
            shownChanges = getUnsavedChanges();
        }
        var changes = shownChanges;
        lights.updateStatus();
        drawHeader(changes);
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        drawToolTabs();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.beginChild("##editingBody/" + (reviewOpen ? "save" : tool.name()), 0, -footerHeight(changes), false);
        ImGui.popStyleVar();
        if (scrollBodyToTop) {
            ImGui.setScrollY(0);
            scrollBodyToTop = false;
        }
        if (scrollBodyTo >= 0) {
            ImGui.setScrollY(scrollBodyTo);
            scrollBodyTo = -1;
        }
        ImGui.dummy(0, GeodataWidgets.BLOCK - 2 * ImGui.getStyle().getItemSpacingY());
        if (reviewOpen) {
            drawReview();
        } else {
            ImGui.beginDisabled(exports.isBusy());
            drawToolBody();
            if (!session.getSelection().isEmpty() && tool == Tool.SELECT) {
                drawSelectionCard();
            } else if (!session.getSelection().isEmpty()
                    && (tool == Tool.PLACE || tool == Tool.SCULPT || tool == Tool.PAINT)) {
                drawSelectionLine();
            } else if (!session.getHiddenActors().isEmpty() || isolatedActors != null) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                drawShowAll(ImGui.getContentRegionAvailX());
            }
            drawPendingPopup();
            ImGui.endDisabled();
        }
        drawRecoveryNote();
        GeodataWidgets.space(GeodataWidgets.ROW);
        bodyScroll = ImGui.getScrollY();
        bodyScrollMax = ImGui.getScrollMaxY();
        bodyHeight = ImGui.getCursorPosY();
        ImGui.endChild();
        drawFooter(changes);
        terrainCapability.drawClosePopup();
        var forSurfaces = pickerSlot == SURFACE_TARGET || eyedropperSlot == SURFACE_TARGET;
        var shown = forSurfaces
                ? !selectedSurfaces.isEmpty() && !reviewOpen && tool == Tool.SURFACES
                : !session.getSelection().isEmpty() && !reviewOpen && tool == Tool.SELECT;
        if (!shown) {
            eyedropperSlot = -1;
            if (pickerSlot >= 0) {
                closePicker();
            }
        }
        if (tool != Tool.SURFACES || reviewOpen) {
            pointedSurfaces = List.of();
            pendingBrushDelete = null;
        }
        drawPicker();
        drawImportDialog();
        drawPrefabPicker();
        drawFilePickers();
        if (library != null) {
            library.poll(uiRenderer);
        }
    }

    public float drawMessage(float centerX, float left, float right, float bottom) {
        var now = System.nanoTime();
        String text;
        int dot;
        long since;
        long life;
        if (!error.isEmpty()) {
            text = error;
            dot = GeodataWidgets.BLOCKED;
            since = errorAt;
            life = Long.MAX_VALUE;
        } else if (!feedback.isEmpty()) {
            text = feedback;
            dot = noteDot;
            since = noteAt;
            life = prefabWritten != null ? NOTE_WITH_ACTION_NANOS : NOTE_NANOS;
        } else {
            return 0;
        }
        var age = now - since;
        if (age >= life) {
            return 0;
        }
        var action = !error.isEmpty() || prefabWritten == null ? null : "Show folder";
        var width = HudWidgets.toastWidth(text, action, Math.max(160, Math.min(720, right - left)));
        var height = HudWidgets.TOAST_HEIGHT;
        var x = Math.max(left, Math.min(centerX - width * .5f, right - width));
        ImGui.setNextWindowPos(Math.round(x), bottom - height, ImGuiCond.Always);
        ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowMinSize, 1, 1);
        ImGui.begin(
                "##editingToast",
                ImGuiWindowFlags.NoDecoration
                        | ImGuiWindowFlags.NoSavedSettings
                        | ImGuiWindowFlags.NoBackground
                        | ImGuiWindowFlags.NoFocusOnAppearing
                        | ImGuiWindowFlags.NoNav
                        | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.popStyleVar(3);
        imgui.internal.ImGui.bringWindowToDisplayFront(imgui.internal.ImGui.getCurrentWindow());
        if (since != toastStamp) {
            toastStamp = since;
            Motion.restart(TOAST_SHOWN);
        }
        var rise = Motion.easeOut(Motion.appear(TOAST_SHOWN, Motion.OPEN));
        if (life != Long.MAX_VALUE && life - age < 1_000_000_000L) {
            Motion.appear(TOAST_SHOWN + 1, 1.2f);
        }
        var leave = life == Long.MAX_VALUE ? 1 : (float) Math.clamp((life - age) / (Motion.OPEN * 1e9), 0, 1);
        Motion.fade(
                Math.min(Math.clamp(rise * 1.6f, 0, 1), leave),
                1,
                0,
                12 * (1 - rise) + 8 * (1 - leave),
                1 - .04f * (1 - rise),
                0);
        var result = HudWidgets.toast("editingToast", text, dot, Glyph.FOLDER, action, width);
        if (life != Long.MAX_VALUE && ImGui.isWindowHovered() && life - age < 1_500_000_000L) {
            noteAt = now - life + 1_500_000_000L;
        }
        ImGui.end();
        if (result == HudWidgets.TOAST_ACTION && prefabWritten != null) {
            FileManager.tryShow(prefabWritten);
        } else if (result == HudWidgets.TOAST_CLICKED) {
            error = "";
            feedback = "";
            prefabWritten = null;
        }
        return height;
    }

    List<SurfaceRef> getSelectedSurfaces() {
        return tool == Tool.SURFACES && !reviewOpen ? selectedSurfaces.getList() : List.of();
    }

    List<SurfaceRef> getPointedSurfaces() {
        return tool == Tool.SURFACES && !reviewOpen ? pointedSurfaces : List.of();
    }

    boolean isBrushWireframeShown() {
        return tool == Tool.SURFACES && !reviewOpen && brushWireframe;
    }

    boolean isSurfaceEyedropper() {
        return (eyedropperSlot >= 0 ? eyedropperSlot : pickerSlot) == SURFACE_TARGET;
    }

    void pickSurface(@Nullable SurfaceRef surface, boolean add) {
        if (!add) {
            if (surface != null && selectedSurfaces.size() == 1 && selectedSurfaces.contains(surface)) {
                return;
            }
            selectedSurfaces.clear();
        }
        if (surface == null) {
            return;
        }
        if (add && selectedSurfaces.remove(surface)) {
            return;
        }
        selectedSurfaces.addFirst(surface);
        releaseObjectsForSurfaces();
    }

    void selectSurfaces(BspSurfaceSelect.Command command) {
        var before = selectedSurfaces.getList();
        var map = before.isEmpty() ? cameraMap() : null;
        if (before.isEmpty() && map == null) {
            showError("Choose a surface first, or stand the camera over a loaded map.");
            return;
        }
        run(() -> {
            var after = session.selectSurfaces(before, command, map);
            var added = countNew(after);
            selectedSurfaces.set(after);
            releaseObjectsForSurfaces();
            if (command == BspSurfaceSelect.Command.BRUSH) {
                note(added == 0 ? "Nothing more to choose." : chosenBrushText(before, after), added > 0);
            } else if (command == BspSurfaceSelect.Command.REVERSE) {
                note(after.isEmpty() ? "Nothing else to choose." : "Chose " + surfacesText(after.size()) + ".", true);
            } else {
                note(added == 0 ? "Nothing more to choose." : "Chose " + added + " more surfaces.", added > 0);
            }
        });
    }

    void combineSurfaces(Memory memory) {
        var before = selectedSurfaces.getList();
        switch (memory) {
            case MEMORIZE -> {
                memorizedSurfaces = before;
                memorizedSet = Set.copyOf(before);
                note("Memorized " + surfacesText(before.size()) + ".", true);
            }
            case RECALL -> {
                selectedSurfaces.set(memorizedSurfaces);
                note(
                        "Chose "
                                + EditorUi.plural(memorizedSurfaces.size(), "memorized surface", "memorized surfaces")
                                + ".",
                        true);
            }
            case OR -> {
                var after = new ArrayList<>(before);
                for (var surface : memorizedSurfaces) {
                    if (!selectedSurfaces.contains(surface)) {
                        after.add(surface);
                    }
                }
                var added = after.size() - before.size();
                selectedSurfaces.set(after);
                note(added == 0 ? "Nothing more to choose." : "Chose " + added + " more surfaces.", added > 0);
            }
            case AND -> {
                var after = new ArrayList<SurfaceRef>();
                for (var surface : before) {
                    if (memorizedSet.contains(surface)) {
                        after.add(surface);
                    }
                }
                selectedSurfaces.set(after);
                note("Kept " + surfacesText(after.size()) + " that are memorized too.", true);
            }
            case XOR -> {
                var after = new ArrayList<SurfaceRef>();
                for (var surface : before) {
                    if (!memorizedSet.contains(surface)) {
                        after.add(surface);
                    }
                }
                for (var surface : memorizedSurfaces) {
                    if (!selectedSurfaces.contains(surface)) {
                        after.add(surface);
                    }
                }
                selectedSurfaces.set(after);
                note("Chose " + surfacesText(after.size()) + " that only one of the two holds.", true);
            }
        }
        releaseObjectsForSurfaces();
    }

    enum Memory {
        MEMORIZE,
        RECALL,
        OR,
        AND,
        XOR
    }

    List<SurfaceRef> getMemorizedSurfaces() {
        return memorizedSurfaces;
    }

    LightTool getLights() {
        return lights;
    }

    List<LightRecord> getLightMarks() {
        if (tool != Tool.LIGHTS || reviewOpen) {
            return List.of();
        }
        return lights.getMarks();
    }

    @Nullable
    String getCameraMap() {
        return terrainCapability.getCameraMap();
    }

    @Nullable
    Tool getTool() {
        return reviewOpen ? null : tool;
    }

    public boolean isSelecting() {
        return getTool() == Tool.SELECT;
    }

    public boolean isSelectTool() {
        return getTool() == Tool.SELECT && !surfaceSnap;
    }

    @Nullable
    PlaceMesh getPlaceMesh() {
        return placeMesh;
    }

    boolean isPlacing() {
        return isPlaceToolActive() && (placeMesh != null || placePrefab != null);
    }

    boolean isPlaceToolActive() {
        return !reviewOpen && tool == Tool.PLACE && !exports.isBusy();
    }

    String getPlaceName() {
        return placePrefab != null ? placePrefab.name() : placeMesh != null ? placeMesh.name() : "";
    }

    void placeAt(Vector3 point, @Nullable BaseReader base, Set<String> loadedMaps) {
        viewportMaps = Set.copyOf(loadedMaps);
        if (placePrefab != null) {
            var prefab = placePrefab;
            pasteClip(prefab.clip(), point, loadedMaps, "Place " + prefab.name());
            return;
        }
        if (placeMesh == null) {
            return;
        }
        var note = new StringBuilder();
        var map = targetMap(point, loadedMaps, note);
        var chosen = placeMesh;
        var bounds = getPlaceBounds();
        var transform = Placing.transform(
                point, alignToNormal ? pointerNormal : null, bounds, placeOptions(), new Random(placeSeed));
        var placement =
                new Placement(map, chosen.meshPath(), chosen.packageFile(), transform, List.of(), bounds == null);
        run(() -> {
            session.placeMany(List.of(placement), "Create " + chosen.name(), bounds == null ? base : null);
            placeSeed = seeds.nextLong();
            note(note.toString(), false);
            if (library != null) {
                library.used(chosen.meshPath());
            }
        });
    }

    void scatter(
            List<float[]> points, List<float @Nullable []> normals, @Nullable BaseReader base, Set<String> loadedMaps) {
        if (placeMesh == null || points.isEmpty()) {
            return;
        }
        var chosen = placeMesh;
        var bounds = getPlaceBounds();
        var random = new Random(seeds.nextLong());
        var options = placeOptions();
        var placements = new ArrayList<Placement>();
        var note = new StringBuilder();
        for (var i = 0; i < points.size(); i++) {
            var p = points.get(i);
            var point = new Vector3(p[0], p[1], p[2]);
            var normal = alignToNormal && i < normals.size() ? normals.get(i) : null;
            placements.add(new Placement(
                    targetMap(point, loadedMaps, note),
                    chosen.meshPath(),
                    chosen.packageFile(),
                    Placing.transform(point, normal, bounds, options, random),
                    List.of(),
                    bounds == null));
        }
        run(() -> {
            session.placeMany(
                    placements, "Scatter " + placements.size() + " " + chosen.name(), bounds == null ? base : null);
            note(note.toString(), false);
            if (library != null) {
                library.used(chosen.meshPath());
            }
        });
    }

    void erase(Collection<String> actors) {
        if (placeMesh == null || actors.isEmpty()) {
            return;
        }
        var name = placeMesh.name();
        run(() -> session.deleteActors(actors, "Erase " + actors.size() + " " + name));
    }

    boolean isScatterBrush() {
        return placeBrush && placeMesh != null && isPlacing();
    }

    float getScatterSpacing() {
        return Math.max(1, spacing.get());
    }

    float getScatterJitter() {
        return Math.clamp(jitter.get(), 0, 1);
    }

    boolean alignsToGround() {
        return alignToNormal;
    }

    void setPointerNormal(float @Nullable [] normal) {
        pointerNormal = normal;
    }

    @Nullable
    ActorTransform nextPlacement(Vector3 point) {
        return nextPlacement(point, placeMesh);
    }

    @Nullable
    ActorTransform nextPlacement(Vector3 point, @Nullable PlaceMesh mesh) {
        var bounds = placeBounds(mesh);
        if (mesh == null || bounds == null) {
            return null;
        }
        return Placing.transform(
                point, alignToNormal ? pointerNormal : null, bounds, placeOptions(), new Random(placeSeed));
    }

    float @Nullable [] getPlaceBounds() {
        return placeBounds(placeMesh);
    }

    float @Nullable [] placeBounds(@Nullable PlaceMesh mesh) {
        if (mesh == null) {
            return null;
        }
        var entry = library == null ? null : library.getMesh(mesh.meshPath());
        if (entry != null && entry.width() > 0) {
            return entry.bounds();
        }
        return viewport == null ? null : viewport.getLocalBounds(mesh.meshPath());
    }

    List<float[][]> getPrefabGhost(Vector3 point) {
        return getPrefabGhost(point, placePrefab);
    }

    List<float[][]> getPrefabGhost(Vector3 point, @Nullable Prefab prefab) {
        if (prefab == null || viewport == null) {
            return List.of();
        }
        var result = new ArrayList<float[][]>();
        for (var item : prefab.clip().items()) {
            var entry = library == null ? null : library.getMesh(item.mesh());
            var bounds = entry != null ? entry.bounds() : viewport.getLocalBounds(item.mesh());
            if (bounds == null) {
                continue;
            }
            var o = item.offset();
            var t = new ActorTransform(
                    new Vector3(
                            point.x() + o.x(),
                            point.y() + o.y(),
                            point.z() + prefab.clip().base() + o.z()),
                    item.rotation(),
                    item.drawScale(),
                    item.drawScale3d(),
                    Vector3.ZERO);
            result.add(Placing.corners(t, bounds));
        }
        return result;
    }

    void startLibraryDrag(String meshPath, @Nullable Path packageFile, long texture) {
        if (canDragFromLibrary() && !meshPath.isBlank()) {
            libraryDrag = new LibraryDrag(new PlaceMesh(meshPath, packageFile), null, texture);
        }
    }

    void startPrefabDrag(Path file) {
        if (!canDragFromLibrary()) {
            return;
        }
        try {
            var clip = ActorClipboard.decode(Files.readString(file));
            var name = file.getFileName().toString();
            libraryDrag = new LibraryDrag(
                    null,
                    new Prefab(
                            name.toLowerCase(Locale.ROOT).endsWith(".json")
                                    ? name.substring(0, name.length() - 5)
                                    : name,
                            file,
                            clip),
                    0);
        } catch (IOException | IllegalArgumentException unreadable) {
            showError("The prefab " + file.getFileName() + " cannot be read: " + unreadable.getMessage());
        }
    }

    boolean canDragFromLibrary() {
        return !replacePick && !reviewOpen && !isWriting() && !exports.isBusy();
    }

    @Nullable
    LibraryDrag getLibraryDrag() {
        return libraryDrag;
    }

    void cancelLibraryDrag() {
        libraryDrag = null;
    }

    void dropLibraryDrag(Vector3 point, @Nullable BaseReader base, Set<String> loadedMaps) {
        var dropped = libraryDrag;
        libraryDrag = null;
        if (dropped == null) {
            return;
        }
        if (dropped.prefab() != null) {
            placePrefab = dropped.prefab();
            placeMesh = null;
            setTool(Tool.PLACE);
        } else if (dropped.mesh() != null) {
            chooseMesh(dropped.mesh().meshPath(), dropped.mesh().packageFile());
        }
        if (isPlacing()) {
            placeAt(point, base, loadedMaps);
        }
    }

    @Nullable
    Vector3 getCameraLook() {
        return cameraLook;
    }

    void setCameraLook(Vector3 look) {
        cameraLook = look;
    }

    void setCameraEye(Vector3 eye) {
        cameraEye = eye;
    }

    void faceCamera() {
        var eye = cameraEye;
        if (eye == null) {
            showError("The camera's place is not known yet.");
            return;
        }
        faceTowards(eye.x(), eye.y());
    }

    void armFacePoint() {
        if (session.getSelection().isEmpty()) {
            showError("Select objects to turn them towards a point.");
            return;
        }
        surfaceSnap = false;
        facePick = true;
    }

    boolean isFacePointArmed() {
        return facePick;
    }

    void disarmFacePoint() {
        facePick = false;
    }

    void faceTowards(float x, float y) {
        var values = new LinkedHashMap<String, ActorTransform>();
        var locked = session.getLockedActors();
        try {
            for (var path : session.getSelection()) {
                if (!locked.contains(path)) {
                    var t = session.getActor(path).transform();
                    values.put(path, EditorTransforms.facing(t, x, y));
                }
            }
        } catch (IllegalArgumentException failure) {
            showError(failure.getMessage());
            return;
        }
        if (values.isEmpty()) {
            showError(
                    session.getSelection().isEmpty()
                            ? "Select objects to turn them."
                            : "The selected objects are locked. L unlocks them.");
            return;
        }
        move(() -> session.transformActors(values, faceLabel(values.keySet())), null);
    }

    List<String> getRecentMeshes() {
        return getRecentMeshes(getSceneMeshCounts());
    }

    public void setLibrary(MeshLibrary value, @Nullable ViewerUiRenderer renderer) {
        if (libraryPanel != null) {
            libraryPanel.close();
        }
        library = value;
        uiRenderer = renderer;
        libraryPanel = new MeshLibraryPanel(this, value);
        terrainCapability.setLibrary(value);
        value.start();
        value.startMaterials();
    }

    public void drawLibrary(String query) {
        if (libraryPanel == null) {
            GeodataWidgets.hint("The Library opens with Edit.");
            return;
        }
        libraryPanel.draw(query);
    }

    boolean isWriting() {
        return exports.isBusy();
    }

    public boolean consumeLibraryRequest() {
        var requested = libraryRequested;
        libraryRequested = false;
        return requested;
    }

    public String getGuideTab() {
        return reviewOpen ? "save" : tool.name().toLowerCase(Locale.ROOT);
    }

    public boolean chooseTool(String name) {
        for (var each : Tool.values()) {
            if (each.name().equalsIgnoreCase(name)) {
                setTool(each);
                return tool == each && !reviewOpen;
            }
        }
        return false;
    }

    public void openLibrary() {
        libraryRequested = true;
    }

    public boolean hasSelection() {
        return !session.getSelection().isEmpty();
    }

    public void frameSelection() {
        focusRequested = true;
    }

    public boolean isBusy() {
        return exports.isBusy();
    }

    public float @Nullable [] getWelcomeGuideLink() {
        return welcomeLink;
    }

    public void close() {
        if (libraryPanel != null) {
            libraryPanel.close();
        }
        if (library != null) {
            library.close();
        }
        if (checkRun != null) {
            checkCancelled.set(true);
        }
        check.close();
        session.endMaterialPreview();
    }

    int getLibraryPickCount() {
        return replacePick ? session.getSelection().size() : 0;
    }

    void cancelLibraryPick() {
        replacePick = false;
    }

    @Nullable
    String getChosenMeshPath() {
        return replacePick || placeMesh == null ? null : placeMesh.meshPath();
    }

    @Nullable
    Path getChosenPrefab() {
        return placePrefab == null ? null : placePrefab.file();
    }

    Map<String, Integer> getSceneMeshCounts() {
        var result = new TreeMap<String, Integer>(String.CASE_INSENSITIVE_ORDER);
        if (viewport != null) {
            result.putAll(viewport.getMeshCounts());
        }
        for (var mesh : placeMeshes().keySet()) {
            result.putIfAbsent(mesh, 0);
        }
        return result;
    }

    void chooseMesh(String meshPath, @Nullable Path packageFile) {
        if (meshPath.isBlank()) {
            return;
        }
        checkReplacePick();
        if (replacePick) {
            replacePick = false;
            replaceSelection(meshPath, packageFile);
            return;
        }
        placeMesh = new PlaceMesh(meshPath, packageFile);
        placePrefab = null;
        setTool(Tool.PLACE);
    }

    void choosePrefab(Path file) {
        try {
            var clip = ActorClipboard.decode(Files.readString(file));
            var name = file.getFileName().toString();
            placePrefab = new Prefab(
                    name.toLowerCase(Locale.ROOT).endsWith(".json") ? name.substring(0, name.length() - 5) : name,
                    file,
                    clip);
            placeMesh = null;
            setTool(Tool.PLACE);
        } catch (IOException | IllegalArgumentException unreadable) {
            showError("The prefab " + file.getFileName() + " cannot be read: " + unreadable.getMessage());
        }
    }

    Path getPrefabFolder() {
        return prefabFolder != null ? prefabFolder : ActorClipboard.prefabFolder();
    }

    void applyLibraryMaterial(MaterialEntry material) {
        if (session.getSelection().isEmpty()) {
            showError("Select objects to give them " + material.name() + ".");
            return;
        }
        var actors = List.copyOf(session.getSelection());
        var slot = Math.max(0, lastSlot);
        run(() -> session.setMaterials(
                actors, slot, new MaterialChoice(material.path(), material.packageFile()), materialLabel(actors)));
    }

    String getLibraryMaterialHint() {
        return session.getSelection().isEmpty()
                ? "Select objects to give them this material."
                : "Click to give slot "
                        + Math.max(0, lastSlot)
                        + " of the "
                        + EditorUi.plural(session.getSelection().size(), "selected object", "selected objects")
                        + " this material.";
    }

    void takeMeshOf(String actor) {
        try {
            if (session.isPawn(actor)) {
                showError(PAWN_NOT_PLACED);
                return;
            }
            var mesh = session.getActor(actor).meshSourcePath();
            if (!mesh.isBlank()) {
                chooseMesh(mesh, null);
                note(
                        "Placing "
                                + EditingSession.shortName(mesh)
                                + ", the mesh of "
                                + EditingSession.shortName(actor)
                                + ".",
                        false);
            }
        } catch (RuntimeException unreadable) {
            showError(unreadable.getMessage());
        }
    }

    void copySelection() {
        var clip = selectionClip();
        if (clip == null) {
            return;
        }
        var text = ActorClipboard.encode(clip);
        copied = text;
        ImGui.setClipboardText(text);
        note(
                "Copied " + EditorUi.plural(clip.items().size(), "object", "objects") + ". " + COMMAND_KEY
                        + "+V pastes them under the pointer.",
                true);
    }

    void requestPaste() {
        pasteRequested = true;
    }

    boolean consumePasteRequest() {
        var requested = pasteRequested;
        pasteRequested = false;
        return requested;
    }

    void paste(Vector3 point, Set<String> loadedMaps) {
        var text = ImGui.getClipboardText();
        if (!ActorClipboard.looksLikeClip(text)) {
            text = copied;
        }
        if (text == null) {
            showError("Nothing to paste. " + COMMAND_KEY + "+C copies the selected objects.");
            return;
        }
        try {
            var clip = ActorClipboard.decode(text);
            pasteClip(
                    clip,
                    point,
                    loadedMaps,
                    "Paste " + EditorUi.plural(clip.items().size(), "actor", "actors"));
        } catch (IllegalArgumentException invalid) {
            showError(invalid.getMessage());
        }
    }

    boolean canPaste() {
        return copied != null || ActorClipboard.looksLikeClip(ImGui.getClipboardText());
    }

    void savePrefab() {
        var clip = selectionClip();
        if (clip == null) {
            return;
        }
        prefabClip = clip;
        var name = clip.items().size() == 1
                ? EditingSession.shortName(clip.items().getFirst().mesh())
                : "Prefab";
        prefabPicker.openPath(getPrefabFolder().resolve(name + ".json").toString());
    }

    boolean axisRow(
            String id,
            String label,
            String labelTip,
            String[] axes,
            ImString[] texts,
            float[] values,
            String[] tips,
            boolean @Nullable [] enabled) {
        GeodataWidgets.rowLabel(label, ROW_LABEL);
        tip(labelTip);
        var spacing = ImGui.getStyle().getItemSpacingX();
        var width = (ImGui.getContentRegionAvailX() - spacing * (axes.length - 1)) / axes.length;
        var changed = false;
        for (var i = 0; i < axes.length; i++) {
            if (i > 0) {
                ImGui.sameLine();
            }
            var off = enabled != null && !enabled[i];
            if (off) {
                texts[i].set("");
            }
            ImGui.beginDisabled(off);
            var left = GeodataWidgets.axisField(axes[i], "##edit/" + id + i, texts[i], width);
            ImGui.endDisabled();
            tip(off ? tips[i] : tips[i] + exactValue(values[i]));
            if (!left || off) {
                continue;
            }
            try {
                var value = RelativeNumber.apply(texts[i].get(), values[i]);
                changed |= value != values[i];
                values[i] = value;
            } catch (IllegalArgumentException invalid) {
                showError(invalid.getMessage());
            }
            texts[i].set(EditorUi.compact(values[i]));
        }
        return changed;
    }

    boolean transformRow(String id, ImString[] texts, String[] labels, float[] values, String help) {
        var width = GeodataWidgets.columnWidth(texts.length);
        var fields = new BooleanSupplier[texts.length];
        for (var i = 0; i < texts.length; i++) {
            var index = i;
            fields[i] = () -> {
                var left = GeodataWidgets.compactText(labels[index], "##edit/" + id + index, "", texts[index], width);
                tip(help);
                return left;
            };
        }
        var left = GeodataWidgets.columns(fields);
        var changed = false;
        for (var i = 0; i < texts.length; i++) {
            if (!left[i]) {
                continue;
            }
            try {
                var value = RelativeNumber.apply(texts[i].get(), values[i]);
                changed |= value != values[i];
                values[i] = value;
            } catch (IllegalArgumentException invalid) {
                showError(invalid.getMessage());
            }
            texts[i].set(RelativeNumber.format(values[i]));
        }
        return changed;
    }

    boolean isEyedropperArmed() {
        return eyedropperSlot >= 0 || pickerSlot >= 0;
    }

    boolean doesEyedropperNeedAlt() {
        return eyedropperSlot < 0;
    }

    void takeMaterial(String actor, int surface, String sceneMaterial) {
        var slot = eyedropperSlot >= 0 ? eyedropperSlot : pickerSlot;
        var scope = eyedropperSlot >= 0 ? eyedropperScope : pickerScope;
        eyedropperSlot = -1;
        closePicker();
        if (slot < 0) {
            return;
        }
        if (slot == SURFACE_TARGET) {
            takeForSurfaces(actor, surface, sceneMaterial);
            return;
        }
        try {
            var entry = library == null
                    ? null
                    : library.getMesh(session.getActor(actor).meshSourcePath());
            var material =
                    MaterialSlots.taken(session, actor, slot, entry == null ? List.of() : entry.slots(), each -> {
                        if (each == surface) {
                            return sceneMaterial;
                        }
                        var scene = viewport == null ? List.<String>of() : viewport.getSceneSlots(actor);
                        return each < scene.size() ? scene.get(each) : null;
                    });
            if (material == null) {
                showError(
                        EditingSession.shortName(actor) + " has no material in slot " + slot + " that Cruma can take.");
                return;
            }
            var choice = new MaterialChoice(material, packageOf(material));
            var actors = scopeActors(scope);
            var taken = material;
            run(() -> {
                session.setMaterials(actors, slot, choice, materialLabel(actors));
                note(
                        "Took " + EditingSession.shortName(taken) + " from " + EditingSession.shortName(actor) + ".",
                        true);
            });
        } catch (RuntimeException failure) {
            showError(failure.getMessage());
        }
    }

    void takeSurfaceMaterial(@Nullable SurfaceRef from) {
        eyedropperSlot = -1;
        closePicker();
        if (from == null) {
            showError("No BSP surface is under the pointer.");
            return;
        }
        try {
            var material = session.getSurfaceMaterial(from);
            if (material.isBlank()) {
                showError(from.path() + " has no material that Cruma can take.");
                return;
            }
            giveSurfaces(new MaterialChoice(material, packageOf(material)), "Surface " + from.surface());
        } catch (IOException | RuntimeException failure) {
            showError(failure.getMessage());
        }
    }

    void openImport(Path image, List<String> actors, int slot) {
        if (!session.canImportTextures()) {
            showError("Image import is unavailable.");
            return;
        }
        if (slot == SURFACE_TARGET) {
            if (selectedSurfaces.isEmpty()) {
                showError("Choose a surface first.");
                return;
            }
            imageSurfaces = selectedSurfaces.getList();
            importDialog.open(image, List.of(), slot, surfacesName(imageSurfaces));
            return;
        }
        if (actors.isEmpty()) {
            showError("Select an object, or drop the image on one.");
            return;
        }
        var target = "slot "
                + slot
                + " of "
                + (actors.size() == 1 ? EditingSession.shortName(actors.getFirst()) : actors.size() + " objects");
        importDialog.open(image, actors, slot, target);
    }

    public void acceptDroppedFiles(List<Path> files, float x, float y, boolean overViewport) {
        Path image = null;
        for (var file : files) {
            var name = file.getFileName() == null
                    ? ""
                    : file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".png")
                    || name.endsWith(".jpg")
                    || name.endsWith(".jpeg")
                    || name.endsWith(".bmp")
                    || name.endsWith(".dds")) {
                image = file;
                break;
            }
        }
        if (image == null) {
            showError("Drop a PNG, JPEG, BMP or DDS file to import it as a texture.");
            return;
        }
        var primary = session.getPrimary();
        for (var area : slotAreas) {
            if (x < area[0] || x > area[2] || y < area[1] || y > area[3]) {
                continue;
            }
            if ((int) area[4] == SURFACE_TARGET) {
                openImport(image, List.of(), SURFACE_TARGET);
                return;
            }
            if (primary != null) {
                openImport(image, List.of(primary), (int) area[4]);
                return;
            }
        }
        if (overViewport) {
            pendingDrop = new Drop(image, x, y);
            return;
        }
        showError("Drop the image on an object in the viewport or on a slot of the Materials part.");
    }

    @Nullable
    Drop consumeDrop() {
        var drop = pendingDrop;
        pendingDrop = null;
        return drop;
    }

    record Drop(Path image, float x, float y) {}

    GroundProbe getGroundProbe() {
        return probe;
    }

    public void setCheckSources(SpawnSource spawns, GeodataSource geodata) {
        spawnSource = spawns;
        geodataSource = geodata;
    }

    EditingSession getSession() {
        return session;
    }

    List<float[]> getChangedTerrain() {
        return terrainCapability.getChangedSectors();
    }

    List<TerrainChange> getTerrainChanges() {
        return terrainCapability.getTerrainChanges();
    }

    List<String> getWarningActors() {
        return tool == Tool.SCULPT && !reviewOpen ? floatingMeshes() : List.of();
    }

    Set<String> getGhostActors() {
        var result = new HashSet<String>();
        var candidates = new ArrayList<>(session.getSelection());
        if (hoveredChange != null) {
            candidates.add(hoveredChange);
        }
        if (candidates.isEmpty()) {
            return result;
        }
        var changes = new HashMap<String, Change>();
        for (var change : session.getChanges()) {
            changes.put(change.path(), change);
        }
        for (var path : candidates) {
            var change = changes.get(path);
            if (change != null
                    && !change.kinds().contains(ChangeKind.NEW)
                    && !change.kinds().contains(ChangeKind.COPY)
                    && !change.kinds().contains(ChangeKind.DELETED)
                    && !change.kinds().contains(ChangeKind.TRANSFERRED)
                    && !change.kinds().contains(ChangeKind.MESH)
                    && (change.kinds().contains(ChangeKind.MOVED)
                            || change.kinds().contains(ChangeKind.ROTATED)
                            || change.kinds().contains(ChangeKind.SCALED))) {
                result.add(path);
            }
        }
        return result;
    }

    public @Nullable Bounds3f consumeFlyRequest() {
        var request = flyRequest;
        flyRequest = null;
        return request;
    }

    void runCheck() {
        openReview(false);
        startCheck();
    }

    void flyTo(float x, float y, float z, float size) {
        flyRequest = new Bounds3f(x - size, y - size, z - size, x + size, y + size, z + size);
    }

    void revertChange(Change change) {
        run(() -> session.revert(change));
    }

    void goToStep(int steps) {
        pendingHistory = steps;
        stepThroughHistory();
    }

    public int getUnsavedChanges() {
        var maps = session.getDirtyMaps();
        if (maps == 0 && session.isDirty()) {
            maps = 1;
        }
        return maps + terrainCapability.getDirtyOwners().size();
    }

    public float getHintsBottom() {
        return hintAreas.isEmpty() ? 0 : hintAreas.getLast()[3];
    }

    public void drawHints(float left, float top, float right, float bottom) {
        var items = new ArrayList<String[]>();
        String label;
        var undo = COMMAND_KEY + "+Z";
        if (exports.isBusy()) {
            label = exports.isSaving() ? "SAVE" : "CHECK";
            items.add(new String[] {"Wait", (exports.isSaving() ? "saving" : "checking") + ", edits are paused"});
        } else if (reviewOpen) {
            label = "SAVE";
            items.add(new String[] {COMMAND_KEY + "+S", "save"});
            items.add(new String[] {"Esc", "back"});
        } else if (eyedropperSlot >= 0 || pickerSlot >= 0) {
            label = "EYEDROPPER";
            items.add(new String[] {
                eyedropperSlot >= 0 ? "Click" : "Alt+Click",
                isSurfaceEyedropper() ? "take the material of a surface or an object" : "take an object's material"
            });
            items.add(new String[] {"Esc", "cancel"});
        } else if (replacePick) {
            label = "REPLACE";
            items.add(new String[] {"Library", "pick the new mesh"});
            items.add(new String[] {"Esc", "keep the mesh"});
        } else if (surfaceSnap) {
            label = "SNAP";
            items.add(new String[] {"Click", "place the pivot on a surface"});
            items.add(new String[] {"Esc", "cancel"});
        } else if (facePick) {
            label = "FACE";
            items.add(new String[] {"Click", "turn towards it"});
            items.add(new String[] {"Esc", "cancel"});
        } else if (viewport != null && viewport.isSurfaceDragging()) {
            label = "SURFACE";
            items.add(new String[] {"Drag", "follow the surface"});
            items.add(new String[] {"Release", "place"});
            items.add(new String[] {"Esc", "cancel"});
        } else if (viewport != null && viewport.isMeasuring()) {
            label = "MEASURE";
            items.add(new String[] {"Drag", "measure from point to point"});
            items.add(new String[] {"Release", "keep it"});
            items.add(new String[] {"Esc", "clear"});
        } else if (dragging && dragAxis >= 0 && !copyPending) {
            label = operation == Operation.ROTATE ? "ROTATE" : operation == Operation.SCALE ? "SCALE" : "MOVE";
            items.add(new String[] {
                "0-9",
                operation == Operation.ROTATE
                        ? "type the degrees"
                        : operation == Operation.SCALE ? "type the factor" : "type the distance"
            });
            items.add(new String[] {"-", "other way"});
            items.add(new String[] {"Enter", "apply"});
            items.add(new String[] {"Esc", "cancel"});
        } else if (viewport != null && viewport.isOrbiting()) {
            label = "ORBIT";
            items.add(new String[] {"Drag", "turn around the middle"});
            items.add(new String[] {"Release", "stop"});
        } else if (viewport != null && viewport.isRectangleActive()) {
            label = "SELECT";
            items.add(new String[] {"Release", "select the visible objects"});
            items.add(new String[] {"Alt", "also behind others"});
            items.add(new String[] {COMMAND_KEY + "/Shift", "add"});
            items.add(new String[] {"Esc", "cancel"});
        } else if (tool == Tool.LIGHTS) {
            label = lights.getHints(items, undo);
        } else if (tool == Tool.SURFACES) {
            label = "SURFACES";
            items.add(new String[] {"Click", "choose a surface"});
            items.add(new String[] {COMMAND_KEY + "+Click", "add"});
            if (!selectedSurfaces.isEmpty()) {
                items.add(new String[] {"Shift+B", "choose brush"});
                items.add(new String[] {"Del", "delete sheets or a brush"});
            }
            items.add(new String[] {undo, "undo"});
        } else if (tool == Tool.SCULPT || tool == Tool.PAINT) {
            var paint = tool == Tool.PAINT;
            label = paint ? "PAINT" : "SCULPT";
            if (terrainCapability.isEditorOpen() && !paint && terrainCapability.isRamp()) {
                label = "RAMP";
                items.add(new String[] {
                    "Click", terrainCapability.isRampStarted() ? "end of the ramp" : "start of the ramp"
                });
                items.add(new String[] {"Alt+Click", "pick height"});
                items.add(new String[] {undo, "undo"});
                items.add(new String[] {"Esc", terrainCapability.isRampStarted() ? "start again" : "select tool"});
            } else if (terrainCapability.isEditorOpen() && !paint && terrainCapability.getBrushHints() != null) {
                items.addAll(List.of(terrainCapability.getBrushHints()));
            } else if (terrainCapability.isEditorOpen()) {
                items.add(new String[] {"Drag", paint ? "paint" : "sculpt"});
                items.add(new String[] {"Shift+Drag", paint ? "erase" : "opposite"});
                items.add(new String[] {"[ ]", "size"});
                items.add(new String[] {"Shift+[ ]", !paint && terrainCapability.usesSpeed() ? "speed" : "strength"});
                items.add(new String[] {
                    "Alt+Click",
                    !paint
                            ? "pick height"
                            : terrainCapability.isPaintingDecorations() ? "pick decoration" : "pick layer"
                });
                items.add(new String[] {"Esc", "select tool"});
            } else {
                items.add(new String[] {"Click", "open the Terrain there"});
                items.add(new String[] {undo, "undo"});
                items.add(new String[] {"Esc", "select tool"});
                items.add(new String[] {"Right mouse", "fly"});
            }
        } else if (tool == Tool.PLACE) {
            label = "PLACE";
            if (placeBrush && placeMesh != null) {
                items.add(new String[] {"Drag", "paint " + placeMesh.name()});
                items.add(new String[] {"Shift+Drag", "erase"});
            } else if (placePrefab != null) {
                items.add(new String[] {"Click", "place " + placePrefab.name()});
            } else {
                items.add(
                        placeMesh != null
                                ? new String[] {"Click", "place " + placeMesh.name()}
                                : new String[] {"Click", "select"});
            }
            items.add(new String[] {COMMAND_KEY + "+Click", "take mesh"});
            items.add(new String[] {undo, "undo"});
            items.add(new String[] {"Esc", "select tool"});
        } else if (session.getSelection().isEmpty()) {
            label = "SELECT";
            items.add(new String[] {"Click", "select"});
            items.add(new String[] {"Drag", "rectangle"});
            items.add(new String[] {COMMAND_KEY + "+Click", "add or remove"});
            items.add(new String[] {"Alt+Drag", "orbit"});
            items.add(new String[] {"Right click", "menu"});
            items.add(
                    isBehindPointer() ? new String[] {"Click again", "next behind"} : new String[] {"P T B", "tools"});
        } else {
            label = operation == Operation.ROTATE ? "ROTATE" : operation == Operation.SCALE ? "SCALE" : "MOVE";
            items.add(new String[] {
                "Drag",
                surfaceLatched
                        ? "follow the surface"
                        : operation == Operation.ROTATE ? "turn" : operation == Operation.SCALE ? "scale" : "move"
            });
            items.add(new String[] {"Alt+Drag", "copy or orbit"});
            items.add(new String[] {"W E R", "tool"});
            var group = session.getSelectedGroup() != null
                    ? new String[] {"Shift+" + COMMAND_KEY + "+G", "ungroup"}
                    : session.getEnteredGroup() != null
                            ? new String[] {"Esc", "whole group"}
                            : session.getSelection().size() > 1 ? new String[] {COMMAND_KEY + "+G", "group"} : null;
            items.add(group != null ? group : new String[] {"Arrows", "nudge"});
            items.add(new String[] {"End", "drop"});
            items.add(
                    isBehindPointer()
                            ? new String[] {"Click again", "next behind"}
                            : new String[] {"V+Drag", "surface"});
        }
        viewTag = "";
        if (right - left < 260) {
            return;
        }
        var draw = ImGui.getBackgroundDrawList();
        draw.pushClipRect(left, top, right, bottom, true);
        var stripRight = right;
        var tag = getStateTag();
        var tagRight = right - GeodataWidgets.BLOCK;
        if (tag != null
                && tagRight - GeodataWidgets.viewTagWidth(tag.text()) - GeodataWidgets.BLOCK
                        >= GeodataWidgets.hintStripLeft(new Viewport(left, top, right, bottom))
                                + GeodataWidgets.hintStripFirst(label, items)) {
            viewTag = tag.text();
            stripRight = GeodataWidgets.viewTag(
                    draw, tagRight, top, tag.text(), tag.warning() ? GeodataWidgets.WARNING : GeodataWidgets.MUTED);
        }
        GeodataWidgets.hintStrip(draw, new Viewport(left, top, stripRight, bottom), label, items, hintAreas);
        draw.popClipRect();
        if (!hintAreas.isEmpty() && !ImGui.getIO().getWantCaptureMouse()) {
            var area = hintAreas.getFirst();
            var x = ImGui.getMousePosX();
            var y = ImGui.getMousePosY();
            if (x >= area[0] && x <= area[0] + 10 + ImGui.calcTextSizeX(label) + 9 && y >= area[1] && y <= area[3]) {
                var note = viewport == null ? "" : viewport.getRectangleNote();
                HudWidgets.showTooltip(note.isEmpty() ? "F1 lists every key." : note + "\n\nF1 lists every key.");
            }
        }
    }

    @Nullable
    StateTag getStateTag() {
        if (exports.isBusy() || reviewOpen) {
            return null;
        }
        if (surfaceSnap) {
            return new StateTag("Snap to surface · click a surface · Esc cancels", true);
        }
        if (facePick) {
            return new StateTag("Face a point · click the scene · Esc cancels", true);
        }
        if (replacePick) {
            return new StateTag(
                    "Replacing the mesh of " + EditorUi.plural(replaceTargets.size(), "object", "objects")
                            + " · Esc keeps it",
                    true);
        }
        if (tool != Tool.SELECT) {
            return null;
        }
        var isolated = getIsolatedActors();
        if (isolated != null) {
            return new StateTag(
                    "Isolated · " + EditorUi.plural(isolated.size(), "object", "objects") + " · Alt+H shows all", true);
        }
        var selection = session.getSelection();
        if (!selection.isEmpty() && session.getLockedActors().containsAll(selection)) {
            return new StateTag(
                    "Locked · " + EditorUi.plural(selection.size(), "object", "objects") + " · L unlocks", false);
        }
        return null;
    }

    record StateTag(String text, boolean warning) {}

    public void drawGizmo(Camera camera, float left, float top, float right, float bottom, boolean interactive) {
        captured = false;
        if (exports.isBusy()) {
            return;
        }
        var io = ImGui.getIO();
        if (!dragging
                && !io.getWantTextInput()
                && !ImGui.isAnyItemActive()
                && !ImGui.isMouseDown(1)
                && !io.getKeyCtrl()
                && !io.getKeySuper()) {
            if (ImGui.isKeyPressed(ImGuiKey.W, false)) {
                operation = Operation.TRANSLATE;
            }
            if (ImGui.isKeyPressed(ImGuiKey.E, false)) {
                operation = Operation.ROTATE;
            }
            if (ImGui.isKeyPressed(ImGuiKey.R, false)) {
                operation = Operation.SCALE;
            }
        }
        if (session.getSelection().isEmpty()) {
            if (dragging) {
                endDrag();
            }
            ImGuizmo.enable(false);
            return;
        }
        if (operation == Operation.SCALE && !dragging && isEffectsOnly()) {
            ImGuizmo.enable(false);
            return;
        }
        if (!dragging) {
            if (session.getSelection().size() == 1) {
                var t = session.getActor(session.getPrimary()).transform();
                gizmo = EditorTransforms.modelMatrix(
                        new ActorTransform(t.location(), t.rotation(), t.drawScale(), t.drawScale3d(), Vector3.ZERO));
            } else {
                gizmo = localAxes
                        ? EditorTransforms.modelMatrix(new ActorTransform(
                                Vector3.ZERO,
                                session.getActor(session.getPrimary())
                                        .transform()
                                        .rotation(),
                                1,
                                Vector3.ONE,
                                Vector3.ZERO))
                        : EditorTransforms.identity();
                var p = session.getPivot();
                gizmo[12] = p.x();
                gizmo[13] = p.y();
                gizmo[14] = p.z();
            }
            gizmoStart = gizmo.clone();
        }
        var inside = WorldGizmo.isPointerInside(left, top, right, bottom);
        if (dragSettled && !ImGui.isMouseDown(0)) {
            dragSettled = false;
        }
        var active = !dragSettled && (dragging || interactive && inside && !ImGui.isAnyItemActive());
        var mode = operation == Operation.SCALE || localAxes ? Mode.LOCAL : Mode.WORLD;
        var step = operation == Operation.ROTATE
                ? angleSnap.get()
                : operation == Operation.SCALE ? scaleSnap.get() : gridSnap.get();
        var on = operation == Operation.ROTATE ? angleOn : operation == Operation.SCALE ? scaleOn : gridOn;
        var snapping = on != (io.getKeyCtrl() || io.getKeySuper()) && step > 0 && typed.isEmpty();
        snapValues[0] = snapValues[1] = snapValues[2] = step;
        worldGizmo.manipulate(
                camera, left, top, right, bottom, active, operation, mode, gizmo, snapping ? snapValues : null);
        if (dragSettled) {
            captured = true;
            return;
        }
        captured = ImGuizmo.isOver() || ImGuizmo.isUsing();
        if (!dragging && !ImGuizmo.isUsing()) {
            hoverAxis = axisUnderPointer();
        }
        if (ImGuizmo.isUsing()) {
            if (!dragging) {
                dragging = true;
                copyPending = io.getKeyAlt();
                pressX = ImGui.getMousePosX();
                pressY = ImGui.getMousePosY();
                dragAxis = hoverAxis;
                typed.clear();
                if (!copyPending) {
                    beginDrag(false);
                }
            }
            if (copyPending) {
                if (Math.hypot(ImGui.getMousePosX() - pressX, ImGui.getMousePosY() - pressY) <= COPY_PIXELS) {
                    return;
                }
                copyPending = false;
                beginDrag(true);
            }
            var enter = dragAxis >= 0 && readTypedKeys();
            if (dragAxis >= 0 && !typed.isEmpty()) {
                gizmo = typed.apply(typedKind(), gizmoStart, dragAxis, mode == Mode.LOCAL);
                drawTypedTag(camera, left, top, right, bottom);
            }
            if (operation == Operation.TRANSLATE) {
                var raw =
                        new Vector3(gizmo[12] - gizmoStart[12], gizmo[13] - gizmoStart[13], gizmo[14] - gizmoStart[14]);
                var offset = raw;
                if (!typed.isEmpty()) {
                    if (viewport != null) {
                        viewport.clearSnapEdges();
                    }
                } else if (isSurfaceDragOn() && dragBounds != null && viewport != null) {
                    offset = viewport.snapToNeighbors(dragBounds, raw, new boolean[] {
                        Math.abs(raw.x()) > 1e-3f, Math.abs(raw.y()) > 1e-3f, Math.abs(raw.z()) > 1e-3f
                    });
                } else if (viewport != null) {
                    viewport.clearSnapEdges();
                }
                var moved = offset;
                var heights = stick == null ? Map.<String, Float>of() : stick.getHeights(moved.x(), moved.y());
                run(() -> session.previewTranslationGesture(moved, heights));
            } else if (!Arrays.equals(gizmo, gizmoStart)) {
                run(() ->
                        session.previewGesture(EditorTransforms.multiply(gizmo, EditorTransforms.inverse(gizmoStart))));
            }
            if (enter) {
                endDrag();
                dragSettled = true;
            }
        } else if (dragging) {
            endDrag();
        }
    }

    boolean cancelDrag() {
        if (!dragging) {
            return false;
        }
        dragging = false;
        stick = null;
        typed.clear();
        dragAxis = -1;
        dragSettled = ImGui.isMouseDown(0);
        if (viewport != null) {
            viewport.clearSnapEdges();
        }
        if (copyPending) {
            copyPending = false;
            return true;
        }
        try {
            session.cancelGesture();
        } catch (RuntimeException locked) {
        } finally {
            session.endStep(null);
        }
        return true;
    }

    String getTypedTag() {
        return dragging && dragAxis >= 0 && !typed.isEmpty() ? typed.getTag(typedKind(), dragAxis) : "";
    }

    boolean isGizmoHovered() {
        return captured;
    }

    boolean isSurfaceDragOn() {
        return surfaceLatched || ImGui.isKeyDown(ImGuiKey.V) && !ImGui.getIO().getWantTextInput();
    }

    boolean isDragging() {
        return dragging;
    }

    void applyDrop(float dz, float @Nullable [] normal) {
        var values = new LinkedHashMap<String, ActorTransform>();
        try {
            for (var path : session.getSelection()) {
                if (session.getLockedActors().contains(path)) {
                    continue;
                }
                var t = session.getActor(path).transform();
                if (normal != null) {
                    t = EditorTransforms.alignedTo(t, normal[0], normal[1], normal[2]);
                }
                var p = t.location();
                values.put(
                        path,
                        new ActorTransform(
                                new Vector3(p.x(), p.y(), p.z() + dz),
                                t.rotation(),
                                t.drawScale(),
                                t.drawScale3d(),
                                t.prePivot()));
            }
        } catch (IllegalArgumentException failure) {
            showError(failure.getMessage());
            return;
        }
        if (values.isEmpty()) {
            showError("The selected objects are locked. L unlocks them.");
            return;
        }
        var label = "Drop "
                + (values.size() == 1
                        ? EditingSession.shortName(values.keySet().iterator().next())
                        : values.size() + " actors")
                + " to the ground";
        move(() -> session.transformActors(values, label), null);
    }

    void releaseObjects() {
        if (session.getSelection().isEmpty()) {
            return;
        }
        try {
            session.select(null, false);
        } catch (RuntimeException kept) {
        }
    }

    void run(Runnable action) {
        try {
            action.run();
            error = "";
        } catch (RuntimeException failure) {
            showError(failure.getMessage());
        }
    }

    private void drawWelcomeGuideLink(float x, float y, float width, float height) {
        welcomeLink = null;
        var padding = 16f;
        var buttons = Math.max(80, ImGui.calcTextSizeX("Got it") + 28)
                + GeodataWidgets.ROW
                + Math.max(80, ImGui.calcTextSizeX("All keys (F1)") + 28);
        var link = 16 + 8 + ImGui.calcTextSizeX(WELCOME_GUIDE);
        if (width - 2 * padding - buttons - GeodataWidgets.BLOCK < link) {
            return;
        }
        var buttonTop = y + height - padding - HudWidgets.CONTROL_HEIGHT;
        var line = Math.max(16, ImGui.getTextLineHeight());
        var top = Math.round(buttonTop + (HudWidgets.CONTROL_HEIGHT - line) * .5f);
        ImGui.setCursorScreenPos(x + padding, top);
        HudWidgets.guideLink(WELCOME_GUIDE, "edit/first-steps");
        welcomeLink = new float[] {x + padding, top, x + padding + link, top + line};
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
    }

    private void closeWelcome() {
        welcomeShown = true;
        if (exportSettings != null) {
            exportSettings.setEditWelcomeShown(true);
        }
    }

    private boolean handleNudge(boolean shift) {
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
        var step = getGridStep() * (shift ? 10 : 1);
        var offset = new Vector3(dx * step, dy * step, dz * step);
        var heights =
                stickToGround ? groundStick(Map.of()).getHeights(offset.x(), offset.y()) : Map.<String, Float>of();
        move(() -> session.move(offset, heights), null);
        return true;
    }

    private void moveToTheirTiles() {
        if (!moveActorsToTheirTile) {
            return;
        }
        var tiles = session.moveToTheirTiles(this::isLoaded);
        if (!tiles.isEmpty()) {
            note("Moved to " + String.join(", ", tiles), true);
        }
    }

    private boolean isLoaded(String map) {
        return viewport != null
                ? viewport.getLoadedMaps().contains(map)
                : session.getOpenedMaps().contains(map);
    }

    private void checkReplacePick() {
        if (replacePick && !session.getSelection().equals(replaceTargets)) {
            replacePick = false;
        }
    }

    private boolean isMadeInSession(String actor) {
        try {
            return session.getActor(actor).created();
        } catch (IllegalArgumentException deleted) {
            return false;
        }
    }

    private boolean standsInScene(String actor) {
        if (session.getHiddenActors().contains(actor)) {
            return false;
        }
        try {
            session.getActor(actor);
            return true;
        } catch (IllegalArgumentException deleted) {
            return false;
        }
    }

    private Map<String, Bounds3f> selectionBounds() {
        if (viewport == null) {
            return Map.of();
        }
        var movable = new LinkedHashSet<String>();
        for (var path : session.getSelection()) {
            if (!session.getLockedActors().contains(path)) {
                movable.add(path);
            }
        }
        return viewport.getCurrentBounds(movable);
    }

    private void arrange(Map<String, Float> shifts, int axis, String verb) {
        if (shifts.size() < 2) {
            showError("Select two or more objects that are not locked and show in the view.");
            return;
        }
        var values =
                BoundsAlign.moved(shifts, axis, path -> session.getActor(path).transform());
        var label = verb + EditorUi.plural(shifts.size(), "object", "objects");
        if (values.isEmpty()) {
            note("The objects are lined up already.", false);
            return;
        }
        move(() -> session.transformActors(values, label), label);
    }

    private void note(String text, boolean done) {
        feedback = text;
        prefabWritten = null;
        noteDot = done ? GeodataWidgets.OPEN : GeodataWidgets.ACCENT;
        if (!text.isEmpty()) {
            noteAt = System.nanoTime();
        }
    }

    private void drawHeader(int changes) {
        var selected = session.getSelection().size();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var button = BUTTON_HEIGHT;
        var draw = ImGui.getWindowDrawList();
        var light = tool == Tool.LIGHTS && !reviewOpen ? lights.getChosenName() : null;
        var surfaces = tool == Tool.SURFACES && !reviewOpen ? selectedSurfaces.size() : 0;
        var what = light != null
                ? light + " chosen"
                : surfaces > 0
                        ? surfacesText(surfaces) + " chosen"
                        : selected == 0
                                ? "Nothing selected"
                                : EditorUi.plural(selected, "object", "objects") + " selected";
        var state = changes > 0 ? EditorUi.plural(changes, "change", "changes") + " not saved" : "All saved";
        var room = width - 2 * button - 4 - GeodataWidgets.ROW;
        var textTop = y + (button - ImGui.getFontSize()) * .5f - 1;
        var shownWhat = HudWidgets.truncateToWidth(what, room);
        draw.addText(x, textTop, HudWidgets.TEXT, shownWhat);
        var after = ImGui.calcTextSizeX(shownWhat);
        var statusLeft = 0f;
        var statusRight = 0f;
        if (!backgroundStatus.isEmpty()
                && shownWhat.equals(what)
                && after + ImGui.calcTextSizeX(" · " + backgroundStatus) <= room) {
            draw.addText(x + after, textTop, GeodataWidgets.MUTED, " · ");
            statusLeft = x + after + ImGui.calcTextSizeX(" · ");
            draw.addText(statusLeft, textTop, GeodataWidgets.ACCENT, backgroundStatus);
            statusRight = statusLeft + ImGui.calcTextSizeX(backgroundStatus);
            after = statusRight - x;
        }
        var rest = HudWidgets.truncateToWidth(" · " + state, room - after);
        var cut = !rest.equals(" · " + state);
        if (shownWhat.equals(what) && rest.length() > 4 && !(statusRight > statusLeft && cut)) {
            draw.addText(x + after, textTop, changes > 0 ? GeodataWidgets.WARNING : GeodataWidgets.MUTED, rest);
        }
        ImGui.dummy(Math.max(1, room), button);
        if (statusRight > statusLeft && ImGui.isMouseHoveringRect(statusLeft, y, statusRight, y + button)) {
            tip(backgroundTip);
        } else {
            tip(
                    changes > 0
                            ? changesTip() + " Save… at the bottom opens the review (" + COMMAND_KEY + "+S)."
                            : "Every change is saved.");
        }
        var running = Motion.toggle("editing/backgroundLine", !backgroundStatus.isEmpty(), Motion.QUICK);
        if (running > 0) {
            var lineTop = y + button + 4;
            draw.addRectFilled(x, lineTop, x + width, lineTop + 2, Motion.alpha(GeodataWidgets.DIVIDER, running), 1f);
            var span = width * .3f;
            var start = Motion.isEnabled() ? (float) ((ImGui.getTime() * .6) % 1.3 - .3) * width : 0;
            draw.addRectFilled(
                    x + Math.max(0, start),
                    lineTop,
                    x + Math.min(width, start + span),
                    lineTop + 2,
                    Motion.alpha(GeodataWidgets.ACCENT, running),
                    1f);
        }
        headerRight = x + width;
        headerBottom = y + button;
        ImGui.setCursorScreenPos(x + width - 2 * button - 4, y);
        ImGui.beginDisabled(exports.isBusy());
        var busy = dragging || terrainCapability.isStroking();
        var undo = timeline.nextUndo();
        ImGui.beginDisabled(busy || !timeline.canUndo());
        if (HudWidgets.iconButton("editingUndo", Glyph.UNDO, false, HudWidgets.TEXT)) {
            undo();
        }
        var history = wasHistoryClicked();
        tip((undo == null
                        ? "Nothing to undo (" + COMMAND_KEY + "+Z)."
                        : "Undo " + undo.label() + " (" + COMMAND_KEY
                                + "+Z). Object changes and Terrain strokes go back in the order they were made.")
                + " A right click lists the steps.");
        ImGui.endDisabled();
        ImGui.sameLine(0, 4);
        var redo = timeline.nextRedo();
        ImGui.beginDisabled(busy || !timeline.canRedo());
        if (HudWidgets.iconButton("editingRedo", Glyph.REDO, false, HudWidgets.TEXT)) {
            redo();
        }
        history |= wasHistoryClicked();
        tip((redo == null
                        ? "Nothing to redo (" + COMMAND_KEY + "+Y)."
                        : "Redo " + redo.label() + " (" + COMMAND_KEY + "+Y or Shift+" + COMMAND_KEY + "+Z).")
                + " A right click lists the steps.");
        ImGui.endDisabled();
        ImGui.endDisabled();
        if ((history || historyRequested) && !busy && !exports.isBusy()) {
            historyRequested = false;
            ImGui.openPopup(HISTORY_MENU);
        }
        drawHistory(x + width, y + button);
        ImGui.setCursorScreenPos(x, y + button);
        ImGui.dummy(width, 0);
    }

    private void drawToolTabs() {
        var tools = Tool.values();
        var tabs = new ArrayList<ToolTab>(tools.length);
        for (var each : tools) {
            tabs.add(new ToolTab(each.label, each.glyph, each.key, each.tip));
        }
        var clicked = GeodataWidgets.toolTabs("editingTools", tabs, reviewOpen ? -1 : tool.ordinal());
        if (clicked >= 0) {
            setTool(tools[clicked]);
        }
    }

    private void keepOneChoice() {
        var requests = session.getSelectionRequests();
        var selectedNow = requests != seenSelectionRequests;
        seenSelectionRequests = requests;
        if (reviewOpen || session.getSelection().isEmpty()) {
            return;
        }
        if (tool == Tool.SURFACES && !selectedSurfaces.isEmpty()) {
            if (selectedNow) {
                selectedSurfaces.clear();
            } else {
                releaseObjects();
            }
        } else if (tool == Tool.LIGHTS && lights.getChosenName() != null) {
            if (selectedNow) {
                lights.release();
            } else {
                releaseObjects();
            }
        }
    }

    private void releaseObjectsForSurfaces() {
        if (!selectedSurfaces.isEmpty()) {
            releaseObjects();
        }
    }

    private void setTool(Tool next) {
        if (dragging || terrainCapability.isStroking()) {
            return;
        }
        reviewOpen = false;
        if (next != Tool.SELECT) {
            surfaceSnap = false;
            facePick = false;
            replacePick = false;
            if (isolatedActors != null) {
                endIsolate();
                note("Isolate ended: " + next.label + " needs the whole scene", false);
            }
        }
        tool = next;
        if (next == Tool.SURFACES) {
            releaseObjectsForSurfaces();
        } else if (next == Tool.LIGHTS && lights.getChosenName() != null) {
            releaseObjects();
        }
    }

    private void drawToolBody() {
        switch (tool) {
            case SELECT -> {
                if (session.getSelection().isEmpty()) {
                    drawEmptyState();
                } else {
                    drawGizmoSection();
                }
            }
            case PLACE -> drawPlaceTool();
            case SCULPT -> terrainCapability.draw(TerrainEditingController.Tool.SCULPT);
            case PAINT -> terrainCapability.draw(TerrainEditingController.Tool.PAINT);
            case LIGHTS -> lights.draw();
            case SURFACES -> drawSurfacesTool();
        }
    }

    private void drawSurfacesTool() {
        EditorUi.firstSection("Surfaces");
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/bsp");
        slotAreas.clear();
        pointedSurfaces = List.of();
        drawBrushDeleteConfirm();
        if (selectedSurfaces.isEmpty()) {
            EditorUi.muted("Click a wall or a floor of the level geometry in the viewport. " + COMMAND_KEY
                    + "+click adds more. Static meshes and the Terrain are not BSP surfaces.");
            GeodataWidgets.space(GeodataWidgets.ROW);
            drawSelectButtons(null);
            drawHiddenSurfaces();
            return;
        }
        var active = selectedSurfaces.getFirst();
        BspSurfaceMapping mapping;
        float[] tile;
        try {
            mapping = session.getSurfaceMapping(active);
            tile = session.getSurfaceTile(active);
        } catch (IOException | RuntimeException unreadable) {
            EditorUi.muted(unreadable.getMessage() == null ? "The surface cannot be read." : unreadable.getMessage());
            return;
        }
        var facts = getSurfaceFacts();
        var refused = facts.refused();
        var title = selectedSurfaces.size() == 1
                ? "Surface " + active.surface() + " of " + active.map()
                : EditorUi.plural(selectedSurfaces.size(), "surface", "surfaces")
                        + ", the last is "
                        + active.surface()
                        + " of "
                        + active.map();
        GeodataWidgets.value("Chosen", title);
        if (tile != null) {
            GeodataWidgets.value(
                    "Tile",
                    String.format(
                            Locale.ROOT,
                            "%.0f x %.0f texels, %,.0f x %,.0f units",
                            tile[0],
                            tile[1],
                            tile[0] * mapping.scaleU(),
                            tile[1] * mapping.scaleV()));
        }
        drawBrushRow(facts);
        GeodataWidgets.space(GeodataWidgets.ROW);
        drawSelectButtons(facts);
        for (var reason : refused) {
            GeodataWidgets.note(reason + ".", GeodataWidgets.WARNING);
        }
        if (refused.size() == 1 && facts.allRefused()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            drawSurfaceButtons(active);
            drawHiddenSurfaces();
            return;
        }
        EditorUi.section("Material");
        drawSurfaceMaterial(active);
        EditorUi.section("Pan");
        var steps = new int[] {1, 4, 16, 64};
        var labels = new String[] {"1", "4", "16", "64"};
        var picked = GeodataWidgets.segmented(
                "editingSurfacePanStep",
                labels,
                Math.max(0, Arrays.stream(steps).boxed().toList().indexOf(surfacePanStep)));
        if (picked >= 0) {
            surfacePanStep = steps[picked];
        }
        tip("How many texels one press of the pan buttons moves the texture.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        var width = GeodataWidgets.columnWidth(4);
        var step = surfacePanStep;
        surfaceButton(
                "editingSurfaceLeft",
                Glyph.ARROW,
                "U -",
                width,
                "Pan texture",
                (current, normal) -> current.pan(-step, 0),
                "Moves the texture along its U direction by the step, as UnrealEd's Pan U does.");
        ImGui.sameLine();
        surfaceButton(
                "editingSurfaceRight",
                Glyph.ARROW,
                "U +",
                width,
                "Pan texture",
                (current, normal) -> current.pan(step, 0),
                "Moves the texture the other way along U.");
        ImGui.sameLine();
        surfaceButton(
                "editingSurfaceUp",
                Glyph.ARROW,
                "V -",
                width,
                "Pan texture",
                (current, normal) -> current.pan(0, -step),
                "Moves the texture along its V direction by the step.");
        ImGui.sameLine();
        surfaceButton(
                "editingSurfaceDown",
                Glyph.ARROW,
                "V +",
                width,
                "Pan texture",
                (current, normal) -> current.pan(0, step),
                "Moves the texture the other way along V.");
        EditorUi.section("Turn");
        var angles = new int[] {-90, -45, 45, 90};
        for (var i = 0; i < angles.length; i++) {
            var angle = angles[i];
            if (i > 0) {
                ImGui.sameLine();
            }
            surfaceButton(
                    "editingSurfaceTurn" + angle,
                    Glyph.RELOAD,
                    (angle > 0 ? "+" : "") + angle + "°",
                    width,
                    "Turn texture",
                    (current, normal) -> current.rotate(angle),
                    "Turns the texture on the surface by " + Math.abs(angle) + " degrees.");
        }
        EditorUi.section("Scale");
        surfaceScale[0] = mapping.scaleU();
        surfaceScale[1] = mapping.scaleV();
        if (!ImGui.isAnyItemActive()) {
            surfaceScaleText[0].set(RelativeNumber.format(surfaceScale[0]));
            surfaceScaleText[1].set(RelativeNumber.format(surfaceScale[1]));
        }
        var scaleHelp = "World units per texel: 1 is the size a new surface gets, 2 draws the texture twice as large. "
                + RelativeNumber.RULE + " Every chosen surface takes the new scale.";
        if (transformRow("surfaceScale", surfaceScaleText, new String[] {"U", "V"}, surfaceScale, scaleHelp)) {
            var scaleU = surfaceScale[0];
            var scaleV = surfaceScale[1];
            if (scaleU > 0 && scaleV > 0) {
                editSurfaces("Scale texture", (current, normal) -> current.withScale(scaleU, scaleV));
            } else {
                showError("A texture scale must be more than 0.");
            }
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        width = GeodataWidgets.columnWidth(2);
        surfaceButton(
                "editingSurfaceHalf",
                Glyph.MINUS,
                "Half size",
                width,
                "Scale texture",
                (current, normal) -> current.multiply(2, 0, 0, 2),
                "Draws the texture at half its size on every chosen surface.");
        ImGui.sameLine();
        surfaceButton(
                "editingSurfaceDouble",
                Glyph.PLUS,
                "Double size",
                width,
                "Scale texture",
                (current, normal) -> current.multiply(0.5f, 0, 0, 0.5f),
                "Draws the texture twice as large on every chosen surface.");
        EditorUi.section("Flip and align");
        surfaceButton(
                "editingSurfaceFlipU",
                Glyph.ARROW,
                "Flip U",
                width,
                "Flip texture",
                (current, normal) -> current.flipU(),
                "Mirrors the texture along U.");
        ImGui.sameLine();
        surfaceButton(
                "editingSurfaceFlipV",
                Glyph.ARROW,
                "Flip V",
                width,
                "Flip texture",
                (current, normal) -> current.flipV(),
                "Mirrors the texture along V.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        surfaceButton(
                "editingSurfaceFloor",
                Glyph.LAYERS,
                "Floor",
                width,
                "Align texture",
                BspSurfaceMapping::alignFloor,
                "Lays the texture flat as seen from above, like on a floor, with the same texel size. Steep walls keep"
                        + " their mapping.");
        ImGui.sameLine();
        surfaceButton(
                "editingSurfaceWall",
                Glyph.LAYERS,
                "Wall",
                width,
                "Align texture",
                BspSurfaceMapping::alignWall,
                "Stands the texture upright, level along the wall, with the same texel size. Floors and ceilings keep"
                        + " their mapping.");
        EditorUi.section("Flags");
        drawSurfaceFlags(facts);
        GeodataWidgets.space(GeodataWidgets.ROW);
        drawSurfaceButtons(active);
        GeodataWidgets.space(GeodataWidgets.ROW);
        EditorUi.muted("The viewport shows each change at once, with the tile grid and the U and V directions of the"
                + " chosen surfaces. The brush polygons keep the old texture, so a rebuild of the geometry in"
                + " UnrealEd would bring it back.");
        drawHiddenSurfaces();
    }

    private void drawSurfaceMaterial(SurfaceRef active) {
        var facts = getSurfaceFacts();
        if (!facts.materialProblem().isEmpty()) {
            GeodataWidgets.hint(facts.materialProblem());
            return;
        }
        String own;
        try {
            own = session.getSourceSurfaceMaterial(active);
        } catch (IOException | RuntimeException unreadable) {
            GeodataWidgets.hint(
                    unreadable.getMessage() == null ? "The material cannot be read." : unreadable.getMessage());
            return;
        }
        String current;
        try {
            current = session.getSurfaceMaterial(active);
        } catch (IOException unreadable) {
            current = "";
        }
        var count = facts.materials();
        var title = count > 1 ? count + " materials" : current.isBlank() ? "None" : EditingSession.shortName(current);
        var detail = count > 1
                ? "the last chosen has " + EditingSession.shortName(current)
                : current.equalsIgnoreCase(own)
                        ? "as in the client"
                        : "new · the client has " + EditingSession.shortName(own);
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var button = BUTTON_HEIGHT;
        var height = SLOT_SWATCH + 8;
        var spacingX = ImGui.getStyle().getItemSpacingX();
        if (GeodataWidgets.thumbnailLine(
                "surfaceMaterial",
                swatchOf(current),
                Glyph.LAYERS,
                title,
                detail,
                SLOT_SWATCH,
                pickerSlot == SURFACE_TARGET || eyedropperSlot == SURFACE_TARGET,
                width - button - spacingX)) {
            openPicker(SURFACE_TARGET, x, y);
        }
        tip("The material of the chosen surfaces. Click to choose another from the list. Pointing at one shows it on"
                + " the surfaces. The texture keeps its texel size, so a larger texture repeats less often."
                + (session.canImportTextures() ? " Drop a PNG, JPEG, BMP or DDS file here to import it." : ""));
        slotAreas.add(new float[] {x, y, x + width, y + height, SURFACE_TARGET});
        ImGui.setCursorScreenPos(x + width - button, y + (height - button) * .5f);
        if (HudWidgets.iconButton(
                "surfaceEyedropper", Glyph.EYEDROPPER, eyedropperSlot == SURFACE_TARGET, HudWidgets.TEXT)) {
            eyedropperSlot = eyedropperSlot == SURFACE_TARGET ? -1 : SURFACE_TARGET;
        }
        tip("Take the material of another surface or of an object: click it in the viewport. Esc cancels. While the"
                + " list is open, Alt+click does the same.");
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
    }

    private void drawHiddenSurfaces() {
        refreshSurfaceLists();
        var hidden = hiddenSurfaceList;
        var brushes = deletedBrushList;
        if (hidden.isEmpty() && brushes.isEmpty()) {
            return;
        }
        EditorUi.section("Hidden · " + (hidden.size() + brushes.size()));
        var spacingX = ImGui.getStyle().getItemSpacingX();
        var showWidth = ImGui.calcTextSizeX("Show") + 24;
        var height = SLOT_SWATCH + 8;
        for (var brush : brushes) {
            drawDeletedBrush(brush, height, spacingX);
        }
        hiddenClipper.begin(hidden.size(), height + ImGui.getStyle().getItemSpacingY());
        while (hiddenClipper.step()) {
            for (var row = hiddenClipper.getDisplayStart(); row < hiddenClipper.getDisplayEnd(); row++) {
                drawHiddenSurface(hidden.get(row), height, showWidth, spacingX);
            }
        }
        hiddenClipper.end();
        if (hidden.size() + brushes.size() > 1) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            if (GeodataWidgets.actionButton(
                    "editingSurfaceShowAll", Glyph.EYE, "Show all", ImGui.getContentRegionAvailX(), false)) {
                var surfaces = hidden.stream().map(HiddenSurface::surface).toList();
                var deleted = brushes.stream().map(DeletedBrush::brush).toList();
                run(() -> session.showAll(surfaces, deleted, "Show " + (surfaces.size() + deleted.size()) + " rows"));
            }
            tip("Draws every surface of this list again, and brings every deleted sheet and brush back, as one Undo"
                    + " step.");
        }
    }

    private void drawDeletedBrush(DeletedBrush row, float height, float spacingX) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var backWidth = ImGui.calcTextSizeX("Bring back") + 24;
        var id = row.brush().map() + "#" + row.brush().brush();
        if (GeodataWidgets.thumbnailLine(
                "deletedBrush" + id,
                swatchOf(row.material()),
                Glyph.TRASH,
                row.name(),
                row.brush().map() + " · deleted brush · "
                        + EditorUi.plural(row.surfaces().size(), "surface", "surfaces"),
                SLOT_SWATCH,
                false,
                width - backWidth - spacingX)) {
            if (!row.surfaces().isEmpty()) {
                pickSurface(row.surfaces().getFirst(), false);
            }
        }
        if (ImGui.isItemHovered()) {
            pointedSurfaces = row.surfaces();
        }
        tip("A brush deleted by this session: its solid is air in the game, which neither draws it nor blocks there."
                + " The viewport outlines it while the pointer is here.");
        ImGui.setCursorScreenPos(x + width - backWidth, y + (height - BUTTON_HEIGHT) * .5f);
        if (HudWidgets.button("restoreBrush" + id, Glyph.UNDO, "Bring back", backWidth, BUTTON_HEIGHT, false, true)) {
            run(() -> session.restoreBrushes(List.of(row.brush()), "Bring back " + row.name()));
        }
        if (ImGui.isItemHovered()) {
            pointedSurfaces = row.surfaces();
        }
        tip("Gives the brush its solid back, as the client has it.");
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
    }

    private void drawHiddenSurface(HiddenSurface row, float height, float showWidth, float spacingX) {
        var surface = row.surface();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var detail = surface.map()
                + (row.sheet() ? " · deleted sheet" : "")
                + (row.material().isBlank() ? "" : " · " + EditingSession.shortName(row.material()));
        if (GeodataWidgets.thumbnailLine(
                "hiddenSurface" + surface.path(),
                swatchOf(row.material()),
                row.sheet() ? Glyph.TRASH : Glyph.EYE_OFF,
                "Surface " + surface.surface(),
                detail,
                SLOT_SWATCH,
                selectedSurfaces.contains(surface),
                width - showWidth - spacingX)) {
            pickSurface(surface, false);
        }
        if (ImGui.isItemHovered()) {
            pointedSurfaces = List.of(surface);
        }
        tip(
                row.sheet()
                        ? "A sheet deleted by this session. It never blocked, so it is gone from the game. The viewport"
                                + " outlines it while the pointer is here. A click chooses it."
                        : "Hidden by this session. It still blocks the player in the game. The viewport outlines it"
                                + " while the pointer is here. A click chooses it.");
        ImGui.setCursorScreenPos(x + width - showWidth, y + (height - BUTTON_HEIGHT) * .5f);
        if (HudWidgets.button(
                "showSurface" + surface.path(), Glyph.EYE, "Show", showWidth, BUTTON_HEIGHT, false, true)) {
            run(() -> session.setSurfacesHidden(List.of(surface), false, "Show surface"));
        }
        if (ImGui.isItemHovered()) {
            pointedSurfaces = List.of(surface);
        }
        tip("Draws this surface again, as the client does.");
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
    }

    private void refreshSurfaceLists() {
        var revision = session.getRevision();
        if (revision == surfaceListRevision) {
            return;
        }
        surfaceListRevision = revision;
        editedSurfaceList = Set.copyOf(session.getEditedSurfaces());
        var rows = new ArrayList<HiddenSurface>();
        for (var surface : session.getHiddenSurfaces()) {
            String material;
            try {
                material = session.getSurfaceMaterial(surface);
            } catch (IOException | RuntimeException unreadable) {
                material = "";
            }
            rows.add(new HiddenSurface(surface, material, session.isSurfaceSheet(surface)));
        }
        hiddenSurfaceList = List.copyOf(rows);
        var brushes = new ArrayList<DeletedBrush>();
        for (var brush : session.getDeletedBrushes()) {
            var surfaces = session.getBrushSurfaces(brush);
            String material;
            try {
                material = surfaces.isEmpty() ? "" : session.getSurfaceMaterial(surfaces.getFirst());
            } catch (IOException | RuntimeException unreadable) {
                material = "";
            }
            brushes.add(new DeletedBrush(brush, session.getBrushName(brush), surfaces, material));
        }
        deletedBrushList = List.copyOf(brushes);
    }

    private record HiddenSurface(SurfaceRef surface, String material, boolean sheet) {}

    private record DeletedBrush(BrushRef brush, String name, List<SurfaceRef> surfaces, String material) {}

    private record PendingBrushDelete(
            List<BrushRef> brushes,
            List<SurfaceRef> sheets,
            int stays,
            String reason,
            List<SurfaceRef> choice,
            List<String> resting) {}

    private record DeletePlan(
            long choice,
            long revision,
            List<SurfaceRef> sheets,
            List<BrushRef> brushes,
            int stays,
            String reason,
            @Nullable String brushName,
            int brushSides,
            boolean checking,
            String activeLine) {
        boolean isDeleting() {
            return !sheets.isEmpty() || !brushes.isEmpty();
        }
    }

    private void surfaceButton(
            String id,
            Glyph glyph,
            String label,
            float width,
            String undoLabel,
            BiFunction<BspSurfaceMapping, Vector3, @Nullable BspSurfaceMapping> change,
            String help) {
        if (GeodataWidgets.actionButton(id, glyph, label, width, false)) {
            editSurfaces(undoLabel, change);
        }
        tip(help);
    }

    private void editSurfaces(
            String label, BiFunction<BspSurfaceMapping, Vector3, @Nullable BspSurfaceMapping> change) {
        run(() -> {
            if (session.editSurfaces(selectedSurfaces.getList(), label, change) == 0) {
                showError("No chosen surface could take this change.");
            }
        });
    }

    private void drawSurfaceFlags(SurfaceFacts facts) {
        GeodataWidgets.columns(
                () -> drawSurfaceFlag(
                        facts.flags().get(BspSurfaceFlag.TWO_SIDED.ordinal()),
                        BspSurfaceFlag.TWO_SIDED,
                        "Two-sided",
                        "The game draws the surface from both sides, also when the camera is behind it, and a light"
                                + " behind it lights it. It matters for a sheet, such as a fence, a cloth or a net."
                                + " Collision does not change, and the lightmap stays."),
                () -> drawSurfaceFlag(
                        facts.flags().get(BspSurfaceFlag.UNLIT.ordinal()),
                        BspSurfaceFlag.UNLIT,
                        "Unlit",
                        "The game draws the surface at full brightness: no lightmap, no shadow and no dynamic light."
                                + " Its lightmap stays in the map unused, so the light comes back when Unlit goes."
                                + " Collision does not change. A surface that the client saves as unlit has no"
                                + " lightmap, so Unlit cannot go from it."));
    }

    private boolean drawSurfaceFlag(FlagFacts facts, BspSurfaceFlag flag, String label, String help) {
        var off = facts.changeable() == 0;
        ImGui.beginDisabled(off);
        var clicked = ImGui.checkbox(label + "##editingSurfaceFlag" + flag.name(), facts.isAll());
        ImGui.endDisabled();
        if (off) {
            tip(facts.reason().isEmpty() ? "No chosen surface can take this change." : facts.reason() + ". " + help);
        } else {
            tip(help);
        }
        if (facts.isSome()) {
            ImGui.sameLine();
            ImGui.textColored(GeodataWidgets.MUTED, "some");
            tip(facts.on() + " of " + EditorUi.plural(facts.readable(), "chosen surface has", "chosen surfaces have")
                    + " it. A click gives it to every one that can take it.");
        }
        if (clicked && !off) {
            setSurfaceFlag(flag, label, facts.target());
        }
        return clicked;
    }

    private void setSurfaceFlag(BspSurfaceFlag flag, String label, boolean on) {
        var surfaces = selectedSurfaces.getList();
        run(() -> {
            var step =
                    label + (on ? "" : " off") + (surfaces.size() == 1 ? "" : " of " + surfaces.size() + " surfaces");
            if (session.setSurfaceFlag(surfaces, flag, on, step) == 0) {
                showError("No chosen surface could take this change.");
            }
        });
    }

    private void drawSurfaceButtons(SurfaceRef active) {
        var facts = getSurfaceFacts();
        var width = GeodataWidgets.columnWidth(2);
        var show = facts.allHidden();
        if (GeodataWidgets.actionButton(
                "editingSurfaceHide", show ? Glyph.EYE : Glyph.EYE_OFF, show ? "Show" : "Hide", width, false)) {
            hideSurfaces(!show);
        }
        tip(
                show
                        ? "Draws the chosen surfaces again, as the client does."
                        : "Hides the chosen surfaces. The game no longer draws them, but a hidden wall still blocks"
                                + " the player, as the BSP's nodes decide what blocks, not the surface flags. The map"
                                + " keeps the hide when saved, unlike Hide of an object. HIDDEN lists them with Show.");
        ImGui.sameLine();
        var plan = getDeletePlan();
        var off = !plan.isDeleting() && pendingBrushDelete == null && !plan.checking();
        var offText = off ? getDeleteOffText(plan.stays(), plan.reason(), plan.brushName(), plan.brushSides()) : "";
        ImGui.beginDisabled(off);
        if (GeodataWidgets.actionButton("editingSurfaceDelete", Glyph.TRASH, "Delete", width, false)) {
            deleteSurfaces();
        }
        ImGui.endDisabled();
        if (off) {
            tip(offText);
        } else {
            tip("Deletes the chosen sheets and brushes (Del or Backspace). A sheet is a thin surface that never"
                + " blocked, such as a swamp, a cloth or a net. The side of a wall or floor goes only with its whole"
                + " brush, and only when that brush stands apart, such as a floor disc, a deck between two posts or a"
                + " floating plate: Delete first chooses the brush, and a second press deletes it."
                    + (plan.stays() == 0
                            ? ""
                            : " " + EditorUi.plural(plan.stays(), "chosen surface stays", "chosen surfaces stay") + ": "
                                    + plan.reason() + "."));
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (GeodataWidgets.actionButton("editingSurfaceFocus", Glyph.FOCUS, "Focus", width, false)) {
            try {
                var center = EditingSession.center(session.getSurfacePolygons(active));
                flyTo(center.x(), center.y(), center.z(), 512);
            } catch (IOException | RuntimeException unreadable) {
                showError(unreadable.getMessage());
            }
        }
        tip("Flies the camera to the last chosen surface.");
        ImGui.sameLine();
        var changed = facts.changed();
        ImGui.beginDisabled(changed.isEmpty());
        if (GeodataWidgets.actionButton("editingSurfaceReset", Glyph.UNDO, "Revert", width, false)) {
            run(() -> session.resetSurfaces(changed, "Revert surface"));
        }
        ImGui.endDisabled();
        tip("Give the chosen surfaces their texture, its mapping, their flags and their visibility in the client back."
                + " A side of a deleted brush brings the brush back.");
        if (plan.checking()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            EditorUi.muted("Checking the brushes…");
        } else if (!offText.isEmpty()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            EditorUi.muted(offText);
        }
    }

    private DeletePlan getDeletePlan() {
        var choice = selectedSurfaces.getRevision();
        var revision = session.getRevision();
        var plan = deletePlan;
        if (plan == null
                || plan.revision() != revision
                || plan.choice() != choice
                || plan.checking() && ImGui.getFrameCount() != deletePlanFrame) {
            try {
                plan = planDelete(selectedSurfaces.getList(), choice, revision, System.nanoTime() + CHECK_NANOS);
                if (plan.checking()) {
                    deletePlanFrame = ImGui.getFrameCount();
                }
            } catch (RuntimeException unreadable) {
                var message = unreadable.getMessage() == null ? "The surfaces cannot be read" : unreadable.getMessage();
                plan = new DeletePlan(
                        choice,
                        revision,
                        List.of(),
                        List.of(),
                        selectedSurfaces.size(),
                        message,
                        null,
                        0,
                        false,
                        "Delete cannot take it.");
            }
            deletePlan = plan;
        }
        return plan;
    }

    private DeletePlan planDelete(List<SurfaceRef> surfaces, long choice, long revision, long deadline) {
        if (brushAnswersRevision != revision) {
            brushAnswers.clear();
            brushAnswersRevision = revision;
        }
        var frame = deadline != Long.MAX_VALUE;
        var sheets = new ArrayList<SurfaceRef>();
        var brushes = new LinkedHashSet<BrushRef>();
        var stays = 0;
        var reason = "";
        BrushRef first = null;
        var oneBrush = true;
        var checking = false;
        var activeLine = surfaces.isEmpty() ? "" : getActiveDeleteLine(surfaces.getFirst(), frame);
        for (var surface : surfaces) {
            if (session.isSurfaceHidden(surface) || session.isSurfaceInDeletedBrush(surface)) {
                continue;
            }
            var refusal = session.getSurfaceDeleteRestriction(surface);
            if (refusal.isEmpty()) {
                sheets.add(surface);
                continue;
            }
            var brush = session.getSurfaceBrush(surface);
            if (brush != null) {
                var answer = findBrushAnswer(brush, frame, deadline);
                if (answer == null) {
                    checking = true;
                    continue;
                }
                refusal = answer;
                if (refusal.isEmpty()) {
                    brushes.add(brush);
                    continue;
                }
            }
            if (stays == 0) {
                first = brush;
                reason = refusal;
            } else if (!Objects.equals(first, brush)) {
                oneBrush = false;
            }
            stays++;
        }
        var named = first != null && oneBrush && !checking;
        return new DeletePlan(
                choice,
                revision,
                List.copyOf(sheets),
                List.copyOf(brushes),
                stays,
                reason,
                named ? session.getBrushName(first) : null,
                named ? session.getBrushSurfaces(first).size() : 0,
                checking,
                activeLine);
    }

    private @Nullable String findBrushAnswer(BrushRef brush, boolean frame, long deadline) {
        var answer = brushAnswers.get(brush);
        if (answer != null) {
            return answer;
        }
        if (frame && (!session.prepareBrushes(brush.map()) || System.nanoTime() > deadline)) {
            return null;
        }
        answer = session.getBrushDeleteRestriction(brush);
        brushAnswers.put(brush, answer);
        return answer;
    }

    private String getActiveDeleteLine(SurfaceRef active, boolean frame) {
        if (session.isSurfaceInDeletedBrush(active)) {
            return "This session deleted it.";
        }
        if (session.isSurfaceHidden(active)) {
            return "Delete cannot take it.";
        }
        if (session.getSurfaceDeleteRestriction(active).isEmpty()) {
            return "Delete can take it.";
        }
        var brush = session.getSurfaceBrush(active);
        if (brush == null) {
            return "Delete cannot take it.";
        }
        var answer = findBrushAnswer(brush, frame, Long.MAX_VALUE);
        return answer == null ? "" : answer.isEmpty() ? "Delete can take it." : "Delete cannot take it.";
    }

    private SurfaceFacts getSurfaceFacts() {
        var choice = selectedSurfaces.getRevision();
        var revision = session.getRevision();
        var preview = session.isPreviewingMaterial();
        var facts = surfaceFacts;
        if (facts != null && facts.choice() == choice && facts.revision() == revision && facts.preview() == preview) {
            return facts;
        }
        refreshSurfaceLists();
        var refused = new ArrayList<String>();
        var allRefused = true;
        var allHidden = true;
        var materials = new HashSet<String>();
        var materialProblem = "";
        var changed = new ArrayList<SurfaceRef>();
        var anyBrush = false;
        var flags = new ArrayList<FlagFacts>();
        for (var flag : BspSurfaceFlag.values()) {
            flags.add(getFlagFacts(flag));
        }
        for (var surface : selectedSurfaces.getList()) {
            var reason = session.getSurfaceRestriction(surface);
            if (reason.isEmpty()) {
                allRefused = false;
            } else if (!refused.contains(reason)) {
                refused.add(reason);
            }
            allHidden &= session.isSurfaceHidden(surface);
            if (editedSurfaceList.contains(surface) || session.isSurfaceInDeletedBrush(surface)) {
                changed.add(surface);
            }
            if (materialProblem.isEmpty()) {
                try {
                    materials.add(session.getSurfaceMaterial(surface).toLowerCase(Locale.ROOT));
                } catch (IOException | RuntimeException unreadable) {
                    materialProblem =
                            unreadable.getMessage() == null ? "The material cannot be read." : unreadable.getMessage();
                }
            }
            try {
                anyBrush = anyBrush || session.getSurfaceBrushActor(surface) != null;
            } catch (RuntimeException unreadable) {
            }
        }
        BrushFacts brush = null;
        if (!selectedSurfaces.isEmpty()) {
            try {
                var active = session.getSurfaceBrushActor(selectedSurfaces.getFirst());
                brush = active == null ? null : session.getBrushFacts(active);
            } catch (RuntimeException unreadable) {
            }
        }
        facts = new SurfaceFacts(
                choice,
                revision,
                preview,
                List.copyOf(refused),
                allRefused,
                materials.size(),
                materialProblem,
                allHidden,
                List.copyOf(changed),
                anyBrush,
                brush,
                List.copyOf(flags));
        surfaceFacts = facts;
        return facts;
    }

    private FlagFacts getFlagFacts(BspSurfaceFlag flag) {
        var on = 0;
        var readable = 0;
        var surfaces = selectedSurfaces.getList();
        var states = new boolean[surfaces.size()];
        var known = new boolean[surfaces.size()];
        for (var i = 0; i < surfaces.size(); i++) {
            try {
                states[i] = session.isSurfaceFlagOn(surfaces.get(i), flag);
                known[i] = true;
                readable++;
                on += states[i] ? 1 : 0;
            } catch (IOException | RuntimeException unreadable) {
            }
        }
        var target = readable == 0 || on < readable;
        var changeable = 0;
        var reason = "";
        for (var i = 0; i < surfaces.size(); i++) {
            if (!known[i] || states[i] == target) {
                continue;
            }
            var refusal = session.getSurfaceFlagRestriction(surfaces.get(i), flag, target);
            if (refusal.isEmpty()) {
                changeable++;
            } else if (reason.isEmpty()) {
                reason = refusal;
            }
        }
        return new FlagFacts(on, readable, target, changeable, reason);
    }

    private record FlagFacts(int on, int readable, boolean target, int changeable, String reason) {
        boolean isAll() {
            return readable > 0 && on == readable;
        }

        boolean isSome() {
            return on > 0 && on < readable;
        }
    }

    private record SurfaceFacts(
            long choice,
            long revision,
            boolean preview,
            List<String> refused,
            boolean allRefused,
            int materials,
            String materialProblem,
            boolean allHidden,
            List<SurfaceRef> changed,
            boolean anyBrush,
            @Nullable BrushFacts brush,
            List<FlagFacts> flags) {}

    private void drawBrushRow(SurfaceFacts facts) {
        var brush = facts.brush();
        GeodataWidgets.value("Brush", brush == null ? "None" : EditingSession.describeBrush(brush));
        tip(
                brush == null
                        ? "The last chosen surface names no brush of its map."
                        : "The brush that the last chosen surface came from. A brush that adds solid makes walls and"
                                + " floors around it, and one that cuts solid out makes a room. Its sides are the"
                                + " surfaces of the BSP that came from it. Choose brush chooses all of them.");
        var line = getDeletePlan().activeLine();
        if (!line.isEmpty()) {
            EditorUi.muted(line);
        }
        ImGui.beginDisabled(!facts.anyBrush());
        if (ImGui.checkbox("Brush wireframe##editingBrushWireframe", brushWireframe && facts.anyBrush())) {
            brushWireframe = !brushWireframe;
        }
        ImGui.endDisabled();
        tip(
                facts.anyBrush()
                        ? "Draws the brushes of the chosen surfaces in thin lines: the polygons that each brush saved,"
                                + " where it stands. The BSP keeps only the pieces of them that the other brushes"
                                + " left, so the lines also show the parts of a brush that the map cut away. A"
                                + " line behind a wall or a floor is faint and dashed."
                        : "No chosen surface names a brush. This draws the brushes of the chosen surfaces in thin"
                                + " lines.");
    }

    private void drawSelectButtons(@Nullable SurfaceFacts facts) {
        var width = GeodataWidgets.columnWidth(2);
        var anyBrush = facts != null && facts.anyBrush();
        ImGui.beginDisabled(!anyBrush);
        if (GeodataWidgets.actionButton("editingChooseBrush", Glyph.BOX, "Choose brush", width, false)) {
            selectSurfaces(BspSurfaceSelect.Command.BRUSH);
        }
        ImGui.endDisabled();
        tip((anyBrush
                        ? "Chooses every side of the brushes of the chosen surfaces"
                        : "No chosen surface names a brush. This chooses every side of the brushes of the chosen"
                                + " surfaces")
                + " (Shift+B). UnrealEd calls it Select Matching Brush.");
        ImGui.sameLine();
        if (GeodataWidgets.actionButton("editingSelectMenu", Glyph.CURSOR, "Select", width, false)
                || selectMenuRequested) {
            selectMenuRequested = false;
            selectMenuMap = selectedSurfaces.isEmpty() ? cameraMap() : null;
            ImGui.openPopup(SELECT_MENU);
        }
        tip("More ways to choose surfaces, as the Select Surfaces menu of UnrealEd: by texture, the adjacent ones of a"
                + " kind, the others, all of a map, and a memorized choice. Each has a key with Shift.");
        drawSelectMenu();
    }

    private void drawSelectMenu() {
        if (!HudWidgets.beginFittedMenu(SELECT_MENU, 268, HudWidgets.menuHeight(16, 3))) {
            return;
        }
        closeOnEscape();
        var chosen = !selectedSurfaces.isEmpty();
        var whole = chosen || selectMenuMap != null;
        var memory = !memorizedSurfaces.isEmpty();
        var none = "Choose a surface first.";
        selectRow(
                "selectTexture",
                Glyph.LAYERS,
                "Matching texture",
                "Shift+T",
                chosen,
                "Chooses every surface whose material is the material of a chosen one, in the maps of the choice.",
                none,
                BspSurfaceSelect.Command.TEXTURE);
        selectRow(
                "selectItems",
                Glyph.COPY,
                "Matching items",
                "Shift+I",
                chosen,
                "Chooses the sides of the brushes of the choice whose brush polygon has the item name of a chosen"
                        + " one, such as every Wall of a cylinder or every Step of stairs. The tool that made a"
                        + " brush gave its polygons these names. Most polygons have none, and then this chooses"
                        + " every side without a name of those brushes.",
                none,
                BspSurfaceSelect.Command.ITEMS);
        selectRow(
                "selectZone",
                Glyph.LASSO,
                "Matching zone",
                "",
                chosen,
                "Chooses every surface with a part in a zone of a chosen one. Zone portals cut a map into zones,"
                        + " such as the rooms of a house. The space that no portal closes is no zone, and it"
                        + " chooses nothing.",
                none,
                BspSurfaceSelect.Command.ZONE);
        HudWidgets.menuRule();
        selectRow(
                "selectAdjacent",
                Glyph.GROUP,
                "All adjacent",
                "Shift+J",
                chosen,
                "Chooses every surface that touches the choice at a point, then the ones that touch those, until"
                        + " none is left.",
                none,
                BspSurfaceSelect.Command.ADJACENT);
        selectRow(
                "selectCoplanar",
                Glyph.EQUAL,
                "Adjacent coplanar",
                "Shift+C",
                chosen,
                "The same, only surfaces in the plane of a chosen one, such as the rest of a floor.",
                none,
                BspSurfaceSelect.Command.COPLANAR);
        selectRow(
                "selectWalls",
                Glyph.ALIGN_CENTER_X,
                "Adjacent walls",
                "Shift+W",
                chosen,
                "The same, only walls: surfaces that stand upright.",
                none,
                BspSurfaceSelect.Command.WALLS);
        selectRow(
                "selectFloors",
                Glyph.LEVEL,
                "Adjacent floors and ceilings",
                "Shift+F",
                chosen,
                "The same, only floors and ceilings: surfaces that lie flat or nearly flat.",
                none,
                BspSurfaceSelect.Command.FLOORS);
        selectRow(
                "selectSlants",
                Glyph.SLOPE,
                "Adjacent slants",
                "Shift+Y",
                chosen,
                "The same, only slants: surfaces between a wall and a floor, such as a ramp or a roof.",
                none,
                BspSurfaceSelect.Command.SLANTS);
        HudWidgets.menuRule();
        selectRow(
                "selectReverse",
                Glyph.SHUFFLE,
                "Reverse",
                "Shift+Q",
                whole,
                "Chooses every other surface of the maps of the choice, and none of the chosen ones. With nothing"
                        + " chosen, every surface of the map under the camera.",
                "Choose a surface first, or stand the camera over a loaded map.",
                BspSurfaceSelect.Command.REVERSE);
        selectRow(
                "selectAll",
                Glyph.BOX,
                "All of the map",
                "Shift+A",
                whole,
                "Chooses every surface of the maps of the choice. With nothing chosen, of the map under the camera.",
                "Stand the camera over a loaded map.",
                BspSurfaceSelect.Command.ALL);
        if (HudWidgets.menuRow("selectNone", Glyph.CLOSE, "None", "Shift+N", chosen)) {
            selectedSurfaces.clear();
        }
        GeodataWidgets.tip(chosen ? "Chooses no surface." : "Nothing is chosen.");
        HudWidgets.menuRule();
        memoryRow(
                "selectMemorize",
                Glyph.BOOKMARK,
                "Memorize",
                "Shift+M",
                chosen,
                "Keeps the choice in memory, for Recall and the three rows under it. The memory lasts while Edit"
                        + " is open and is no Undo step.",
                none,
                Memory.MEMORIZE);
        memoryRow(
                "selectRecall",
                Glyph.UNDO,
                "Recall",
                "Shift+R",
                memory,
                "Chooses the memorized surfaces again, in place of the choice.",
                "Nothing is memorized yet.",
                Memory.RECALL);
        memoryRow(
                "selectOr",
                Glyph.PLUS,
                "Or with memorized",
                "Shift+O",
                memory,
                "Adds the memorized surfaces to the choice.",
                "Nothing is memorized yet.",
                Memory.OR);
        memoryRow(
                "selectAnd",
                Glyph.FILTER,
                "And with memorized",
                "Shift+U",
                memory && chosen,
                "Keeps only the chosen surfaces that are memorized too.",
                memory ? none : "Nothing is memorized yet.",
                Memory.AND);
        memoryRow(
                "selectXor",
                Glyph.MIRROR,
                "Xor with memorized",
                "Shift+X",
                memory,
                "Chooses the surfaces that are chosen or memorized, but not both.",
                "Nothing is memorized yet.",
                Memory.XOR);
        HudWidgets.endMenu();
    }

    private void selectRow(
            String id,
            Glyph glyph,
            String label,
            String key,
            boolean enabled,
            String help,
            String off,
            BspSurfaceSelect.Command command) {
        if (HudWidgets.menuRow(id, glyph, label, key, enabled)) {
            selectSurfaces(command);
        }
        GeodataWidgets.tip(enabled ? help : off);
    }

    private void memoryRow(
            String id, Glyph glyph, String label, String key, boolean enabled, String help, String off, Memory memory) {
        if (HudWidgets.menuRow(id, glyph, label, key, enabled)) {
            combineSurfaces(memory);
        }
        GeodataWidgets.tip(enabled ? help : off);
    }

    private boolean handleSelectKeys() {
        var chosen = !selectedSurfaces.isEmpty();
        var memory = !memorizedSurfaces.isEmpty();
        for (var i = 0; i < SELECT_KEYS.length; i++) {
            if (!ImGui.isKeyPressed(SELECT_KEYS[i], false)) {
                continue;
            }
            var command = SELECT_COMMANDS[i];
            if (command == BspSurfaceSelect.Command.ALL || command == BspSurfaceSelect.Command.REVERSE) {
                selectSurfaces(command);
            } else if (chosen) {
                selectSurfaces(command);
            }
            return true;
        }
        if (ImGui.isKeyPressed(ImGuiKey.N, false)) {
            selectedSurfaces.clear();
        } else if (ImGui.isKeyPressed(ImGuiKey.M, false)) {
            if (chosen) {
                combineSurfaces(Memory.MEMORIZE);
            }
        } else if (ImGui.isKeyPressed(ImGuiKey.R, false)) {
            if (memory) {
                combineSurfaces(Memory.RECALL);
            }
        } else if (ImGui.isKeyPressed(ImGuiKey.O, false)) {
            if (memory) {
                combineSurfaces(Memory.OR);
            }
        } else if (ImGui.isKeyPressed(ImGuiKey.U, false)) {
            if (memory && chosen) {
                combineSurfaces(Memory.AND);
            }
        } else if (ImGui.isKeyPressed(ImGuiKey.X, false)) {
            if (memory) {
                combineSurfaces(Memory.XOR);
            }
        } else {
            return false;
        }
        return true;
    }

    private int countNew(List<SurfaceRef> after) {
        var added = 0;
        for (var surface : after) {
            if (!selectedSurfaces.contains(surface)) {
                added++;
            }
        }
        return added;
    }

    private String chosenBrushText(List<SurfaceRef> before, List<SurfaceRef> after) {
        var brushes = new LinkedHashSet<BrushRef>();
        for (var surface : before) {
            var brush = session.getSurfaceBrushActor(surface);
            if (brush != null) {
                brushes.add(brush);
            }
        }
        var sides = 0;
        for (var surface : after) {
            if (brushes.contains(session.getSurfaceBrushActor(surface))) {
                sides++;
            }
        }
        if (brushes.size() == 1) {
            return "Chose " + surfacesText(sides) + " of " + session.getBrushName(brushes.getFirst()) + ".";
        }
        return "Chose " + surfacesText(sides) + " of " + brushes.size() + " brushes.";
    }

    private @Nullable String cameraMap() {
        var eye = cameraEye;
        if (eye == null || viewport == null) {
            return null;
        }
        var loaded = viewport.getLoadedMaps();
        var map = WorldTile.mapAt(eye.x(), eye.y(), loaded);
        return loaded.contains(map) ? map : null;
    }

    private void deleteSurfaces() {
        var surfaces = selectedSurfaces.getList();
        var pending = pendingBrushDelete;
        if (pending != null && pending.choice().equals(surfaces)) {
            confirmBrushDelete(pending);
            return;
        }
        run(() -> {
            var plan = planDelete(surfaces, selectedSurfaces.getRevision(), session.getRevision(), Long.MAX_VALUE);
            var sheets = plan.sheets();
            var reason = plan.reason();
            if (!plan.brushes().isEmpty()) {
                askBrushDelete(plan.brushes(), sheets, plan.stays(), reason);
                return;
            }
            var walls = plan.stays();
            var label = sheets.size() == 1 ? "Delete sheet" : "Delete " + sheets.size() + " sheets";
            var deleted = session.deleteSurfaces(sheets, label);
            if (deleted == 0) {
                showError(reason.isEmpty() ? "No chosen surface could be deleted." : reason + ".");
            } else if (walls > 0) {
                note(
                        "Deleted " + EditorUi.plural(deleted, "sheet", "sheets") + ". "
                                + EditorUi.plural(walls, "surface stays", "surfaces stay") + ": " + reason + ".",
                        true);
            }
        });
    }

    private void askBrushDelete(List<BrushRef> brushes, List<SurfaceRef> sheets, int stays, String reason) {
        var choice = new LinkedHashSet<SurfaceRef>();
        var active = selectedSurfaces.isEmpty() ? null : selectedSurfaces.getFirst();
        for (var brush : brushes) {
            choice.addAll(session.getBrushSurfaces(brush));
        }
        choice.addAll(sheets);
        var ordered = new ArrayList<SurfaceRef>(choice.size());
        if (active != null && choice.remove(active)) {
            ordered.add(active);
        }
        ordered.addAll(choice);
        selectedSurfaces.set(ordered);
        releaseObjectsForSurfaces();
        var resting = new TreeSet<String>();
        for (var brush : brushes) {
            resting.addAll(getObjectsOnBrush(brush));
        }
        pendingBrushDelete = new PendingBrushDelete(
                brushes, List.copyOf(sheets), stays, reason, selectedSurfaces.getList(), List.copyOf(resting));
    }

    private void confirmBrushDelete(PendingBrushDelete pending) {
        pendingBrushDelete = null;
        run(() -> {
            var deleted = session.deleteBrushes(pending.brushes(), pending.sheets(), brushDeleteLabel(pending));
            if (deleted == 0) {
                showError("No chosen brush could be deleted.");
                return;
            }
            selectedSurfaces.clear();
            var said = "Deleted " + brushDeleteNames(pending) + ".";
            note(
                    pending.stays() == 0
                            ? said
                            : said + " " + EditorUi.plural(pending.stays(), "surface stays", "surfaces stay") + ": "
                                    + pending.reason() + ".",
                    true);
        });
    }

    private void drawBrushDeleteConfirm() {
        var pending = pendingBrushDelete;
        if (pending == null) {
            return;
        }
        if (!pending.choice().equals(selectedSurfaces.getList())) {
            pendingBrushDelete = null;
            return;
        }
        var surfaces = pending.choice().size() - pending.sheets().size();
        var one = pending.brushes().size() == 1;
        EditorUi.message(
                Tone.WARNING,
                "Delete " + brushDeleteNames(pending) + "? " + EditorUi.plural(surfaces, "surface is", "surfaces are")
                        + " chosen. " + (one ? "Its solid turns" : "Their solid turns")
                        + " into air: the game no longer draws " + (one ? "it" : "them")
                        + " or blocks there. Press Delete again to delete, or Esc to keep " + (one ? "it." : "them."));
        if (!pending.resting().isEmpty()) {
            GeodataWidgets.note(
                    EditorUi.plural(pending.resting().size(), "object stands", "objects stand") + " on "
                            + (one ? "it" : "them") + " or against " + (one ? "it" : "them")
                            + " and may float in the air after the delete. The Check lists them.",
                    GeodataWidgets.WARNING);
        }
        if (pending.stays() > 0) {
            GeodataWidgets.note(
                    EditorUi.plural(pending.stays(), "surface stays", "surfaces stay") + ": " + pending.reason() + ".",
                    GeodataWidgets.WARNING);
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        var half = GeodataWidgets.columnWidth(2);
        var brushes = pending.brushes().size();
        if (EditorUi.dangerButton(
                (brushes == 1 ? "Delete brush" : "Delete " + brushes + " brushes") + "##editingBrushDelete", half)) {
            confirmBrushDelete(pending);
        }
        tip("Deletes the chosen brushes and sheets as one Undo step (Del or Backspace). HIDDEN lists a deleted brush"
                + " with Bring back.");
        ImGui.sameLine();
        if (HudWidgets.button("editingBrushDeleteCancel", null, "Cancel", half, BUTTON_HEIGHT, false, true)) {
            pendingBrushDelete = null;
        }
        tip(one ? "Keeps the brush (Esc)." : "Keeps the brushes (Esc).");
        GeodataWidgets.space(GeodataWidgets.BLOCK);
    }

    private List<String> getObjectsOnBrush(BrushRef brush) {
        return viewport == null ? List.of() : EditingCheck.getActorsOnBrush(session, brush, sceneBounds(brush.map()));
    }

    private List<EditingCheck.Resting> resting(Change change) {
        return viewport == null ? List.of() : EditingCheck.resting(session, change, sceneBounds(change.map()));
    }

    private Map<String, Bounds3f> sceneBounds(String map) {
        var result = new HashMap<String, Bounds3f>();
        if (viewport == null) {
            return result;
        }
        var prefix = map.toLowerCase(Locale.ROOT) + ".";
        for (var path : viewport.getSceneActors()) {
            if (path.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                var bounds = viewport.getActorBounds(path);
                if (bounds != null) {
                    result.put(path, bounds);
                }
            }
        }
        return result;
    }

    private String brushDeleteLabel(PendingBrushDelete pending) {
        return "Delete " + brushDeleteNames(pending);
    }

    private String brushDeleteNames(PendingBrushDelete pending) {
        var brushes = pending.brushes().size() == 1
                ? session.getBrushName(pending.brushes().getFirst())
                : EditorUi.plural(pending.brushes().size(), "brush", "brushes");
        return pending.sheets().isEmpty()
                ? brushes
                : brushes + " and " + EditorUi.plural(pending.sheets().size(), "sheet", "sheets");
    }

    private void hideSurfaces(boolean hide) {
        var surfaces = selectedSurfaces.getList();
        run(() -> {
            var label = (hide ? "Hide " : "Show ") + (surfaces.size() == 1 ? "surface" : surfaces.size() + " surfaces");
            if (session.setSurfacesHidden(surfaces, hide, label) == 0 && hide) {
                var reason = surfaces.stream()
                        .map(session::getSurfaceHideRestriction)
                        .filter(text -> !text.isEmpty())
                        .findFirst()
                        .orElse("No chosen surface could be hidden");
                showError(reason + ".");
            }
        });
    }

    private void takeForSurfaces(String actor, int part, String sceneMaterial) {
        try {
            var entry = library == null
                    ? null
                    : library.getMesh(session.getActor(actor).meshSourcePath());
            var material =
                    MaterialSlots.taken(session, actor, part, entry == null ? List.of() : entry.slots(), each -> {
                        if (each == part) {
                            return sceneMaterial;
                        }
                        var scene = viewport == null ? List.<String>of() : viewport.getSceneSlots(actor);
                        return each < scene.size() ? scene.get(each) : null;
                    });
            if (material == null) {
                showError(EditingSession.shortName(actor) + " has no material there that Cruma can take.");
                return;
            }
            giveSurfaces(new MaterialChoice(material, packageOf(material)), EditingSession.shortName(actor));
        } catch (RuntimeException failure) {
            showError(failure.getMessage());
        }
    }

    private void giveSurfaces(MaterialChoice choice, String from) {
        var surfaces = selectedSurfaces.getList();
        if (surfaces.isEmpty()) {
            return;
        }
        run(() -> {
            if (session.setSurfaceMaterials(surfaces, choice, surfaceMaterialLabel(surfaces)) == 0) {
                showError("No chosen surface could take " + EditingSession.shortName(choice.path()) + ".");
                return;
            }
            note("Took " + EditingSession.shortName(choice.path()) + " from " + from + ".", true);
        });
    }

    private void drawEmptyState() {
        ImGui.textUnformatted("Nothing selected");
        GeodataWidgets.space(GeodataWidgets.ROW);
        EditorUi.muted("Click an object in the viewport or in the Objects list, or drag a rectangle around objects. "
                + COMMAND_KEY + "+click adds more.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        EditorUi.muted("F1 lists every key.");
        tip(SHORTCUTS);
    }

    private boolean isEffectsOnly() {
        var selection = session.getSelection();
        return !selection.isEmpty() && selection.stream().allMatch(session::isEmitter);
    }

    private void drawGizmoSection() {
        EditorUi.firstSection("Gizmo");
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/gizmo");
        var spacing = ImGui.getStyle().getItemSpacingX();
        var total = ImGui.getContentRegionAvailX();
        var picked = GeodataWidgets.segmented(
                "editingTool",
                new String[] {"Move", "Rotate", "Scale"},
                operation == Operation.ROTATE ? 1 : operation == Operation.SCALE ? 2 : 0,
                total);
        if (picked >= 0) {
            operation = picked == 1 ? Operation.ROTATE : picked == 2 ? Operation.SCALE : Operation.TRANSLATE;
        }
        tip("Move (W) drags the arrows or planes, Rotate (E) the rings and Scale (R) the handles. The center"
                + " handle scales uniformly.");
        if (operation == Operation.SCALE && isEffectsOnly()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.note(PackageEditDocument.EFFECT_SCALE, GeodataWidgets.MUTED, total);
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        var half = GeodataWidgets.columnWidth(2);
        var rowX = ImGui.getCursorScreenPosX();
        var rowY = ImGui.getCursorScreenPosY();
        var axes = GeodataWidgets.segmented("editingAxes", new String[] {"World", "Local"}, localAxes ? 1 : 0, half);
        if (axes >= 0) {
            localAxes = axes == 1;
        }
        tip("The axes of the gizmo. World axes follow the world X, Y and Z. Local axes follow the rotation of the"
                + " active object. Scaling always uses local axes.");
        var rowBottom = ImGui.getItemRectMaxY();
        ImGui.setCursorScreenPos(rowX + half + spacing, rowY + 1);
        if (GeodataWidgets.chip("##editingSurfaceDrag", Glyph.PLACE, "Surface", "V", isSurfaceDragOn(), half)) {
            surfaceLatched = !surfaceLatched;
        }
        tip(
                surfaceLatched
                        ? "On: a drag of the gizmo moves the selection along the surface under the pointer. Click"
                                + " again or press Esc to turn it off."
                        : "A drag of the gizmo moves the selection along the surface under the pointer, while V is"
                                + " held. A click keeps it on until you click again or press Esc.");
        ImGui.setCursorScreenPos(rowX, rowBottom);
        ImGui.dummy(total, 0);
        GeodataWidgets.space(GeodataWidgets.ROW);
        drawSnapFields();
        GeodataWidgets.space(GeodataWidgets.ROW);
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var stickWidth = ImGui.getFrameHeight()
                + ImGui.getStyle().getItemInnerSpacingX()
                + ImGui.calcTextSizeX("Stick to ground");
        var shared = stickWidth <= half;
        if (shared) {
            ImGui.setCursorScreenPos(x, y + (BUTTON_HEIGHT - ImGui.getFrameHeight()) * .5f);
        }
        if (ImGui.checkbox("Stick to ground##editingStick", stickToGround)) {
            stickToGround = !stickToGround;
        }
        tip("When a drag of the gizmo or an arrow key moves the selection sideways, each object that stands on the"
                + " Terrain goes up and down with it. It keeps the height above the Terrain that it has in the"
                + " client, so a tree keeps its roots in the ground and a mesh that an earlier move left in the"
                + " air comes back down. A new object keeps the height it had when the move started. Objects on"
                + " level geometry or on other meshes keep their height, and the Z arrow and Page Up and Down"
                + " still lift and lower. The Terrain is the only ground this looks at; End drops onto any ground"
                + " in view.");
        if (shared) {
            ImGui.setCursorScreenPos(x + half + spacing, y);
        } else {
            GeodataWidgets.space(GeodataWidgets.ROW);
        }
        if (HudWidgets.button(
                "surfaceSnap",
                surfaceSnap ? Glyph.CLOSE : Glyph.PLACE,
                surfaceSnap ? "Cancel snap" : "Snap to surface",
                shared ? half : -1,
                BUTTON_HEIGHT,
                surfaceSnap,
                true)) {
            toggleSurfaceSnap();
        }
        tip(
                surfaceSnap
                        ? "Click a surface in the viewport to place the pivot there, or press Esc to cancel."
                        : "Then click a surface in the viewport: the selection moves so that its pivot lands on that"
                              + " point. Holding V while you drag in the viewport moves it along the surface under the"
                              + " pointer instead.");
        if (shared) {
            ImGui.setCursorScreenPos(x, y + BUTTON_HEIGHT);
            ImGui.dummy(total, 0);
        }
        if (session.getSelection().size() > 1 && operation == Operation.SCALE) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            EditorUi.muted(
                    "Use the center handle for uniform group scale. Axis scaling requires objects turned the same"
                            + " way.");
        }
    }

    private void drawSnapFields() {
        var width = GeodataWidgets.columnWidth(3);
        var command = COMMAND_KEY + " during a drag turns snapping the other way.";
        GeodataWidgets.columns(
                () -> {
                    var result = GeodataWidgets.compactToggle("Grid", "##editingGridSnap", gridOn, gridSnap, width);
                    gridOn ^= result.toggled();
                    if (result.left()) {
                        gridSnap.set(Math.clamp(gridSnap.get(), .1f, 8192));
                    }
                    tip("Move snaps to steps of this many world units, 16 by default (64 cm). The arrow keys move"
                            + " by this step too. "
                            + command);
                    return result.toggled();
                },
                () -> {
                    var result = GeodataWidgets.compactToggle("Angle", "##editingAngleSnap", angleOn, angleSnap, width);
                    angleOn ^= result.toggled();
                    if (result.left()) {
                        angleSnap.set(Math.clamp(angleSnap.get(), .1f, 180));
                    }
                    tip("Rotate snaps to steps of this many degrees. " + command);
                    return result.toggled();
                },
                () -> {
                    var result = GeodataWidgets.compactToggle("Scale", "##editingScaleSnap", scaleOn, scaleSnap, width);
                    scaleOn ^= result.toggled();
                    if (result.left()) {
                        scaleSnap.set(Math.clamp(scaleSnap.get(), .001f, 10));
                    }
                    tip("Scale snaps to steps of this factor, such as 0.1. " + command);
                    return result.toggled();
                });
    }

    private boolean isBehindPointer() {
        return viewport != null && viewport.getObjectsBehindNow() > 0;
    }

    private Options placeOptions() {
        var low = Math.min(placeScaleMin.get(), placeScaleMax.get());
        var high = Math.max(placeScaleMin.get(), placeScaleMax.get());
        return new Options(
                alignToNormal,
                placeRandomYaw,
                placeRandomScale,
                Math.max(.01f, low),
                Math.max(.01f, high),
                snapPlaceToGrid,
                getGridStep());
    }

    private String targetMap(Vector3 point, Set<String> loadedMaps, StringBuilder note) {
        var tileMap = WorldTile.mapAt(point.x(), point.y(), loadedMaps);
        if (loadedMaps.contains(tileMap) || session.getOpenedMaps().contains(tileMap)) {
            return tileMap;
        }
        var primary = session.getPrimary();
        var map = primary != null ? mapOf(primary) : terrainCapability.getCameraMap();
        note.setLength(0);
        note.append("Placed in ")
                .append(map)
                .append(", because the map ")
                .append(tileMap)
                .append(" is not loaded.");
        return map;
    }

    private void drawPlaceTool() {
        openCameraMap();
        EditorUi.firstSection("Mesh");
        drawChosenMesh();
        if (placeMesh == null && placePrefab == null) {
            drawRecentMeshes();
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        var primary = session.getPrimary();
        var selectedMesh = primary == null || session.isPawn(primary)
                ? ""
                : session.getActor(primary).meshSourcePath();
        var spacingX = ImGui.getStyle().getItemSpacingX();
        var half = (ImGui.getContentRegionAvailX() - BUTTON_HEIGHT - 2 * spacingX) / 2;
        if (HudWidgets.button("row/Library", null, "Library", half, BUTTON_HEIGHT, false, true)) {
            libraryRequested = true;
        }
        tip("Open the Library on the left: the meshes of the client with their pictures. A click on one chooses it.");
        ImGui.sameLine();
        ImGui.beginDisabled(selectedMesh.isEmpty());
        if (HudWidgets.button("row/Take selected", null, "Take selected", half, BUTTON_HEIGHT, false, true)) {
            chooseMesh(selectedMesh, null);
        }
        ImGui.endDisabled();
        tip(
                selectedMesh.isEmpty()
                        ? "Select an object to place more of its mesh. " + COMMAND_KEY
                                + "+click on an object in the viewport does it too."
                        : "Place more of " + EditingSession.shortName(selectedMesh)
                                + ", the mesh of the selected object. " + COMMAND_KEY
                                + "+click on an object does it too.");
        ImGui.sameLine();
        if (HudWidgets.iconButton("placeMore", Glyph.MORE, ImGui.isPopupOpen(PLACE_MENU), HudWidgets.TEXT)) {
            ImGui.openPopup(PLACE_MENU);
        }
        tip("More: place at coordinates.");
        var right = ImGui.getItemRectMaxX();
        var bottom = ImGui.getItemRectMaxY();
        if (HudWidgets.beginMenu(PLACE_MENU, 236)) {
            closeOnEscape();
            if (HudWidgets.menuRow("placeCoordinates", Glyph.PLACE, "Place at coordinates…", "")) {
                openSoon(COORDINATES_POPUP, right, bottom);
            }
            GeodataWidgets.tip(
                    "Create one object of the chosen mesh, or the chosen prefab, at typed world coordinates.");
            HudWidgets.endMenu();
        }
        EditorUi.section("Options");
        drawPlaceOptions();
        EditorUi.section("Brush");
        var third = GeodataWidgets.columnWidth(3);
        var brush = placeBrush && placePrefab == null;
        if (brush) {
            resetSpacing();
            if (!spacingEditing) {
                spacing.set(TerrainCapabilityPanel.oneDecimal(spacing.get()));
            }
        }
        GeodataWidgets.columns(
                () -> {
                    ImGui.textColored(GeodataWidgets.MUTED, "Place with");
                    ImGui.beginDisabled(placePrefab != null);
                    var mode = GeodataWidgets.segmented(
                            "placeBrush", new String[] {"Click", "Brush"}, brush ? 1 : 0, third);
                    ImGui.endDisabled();
                    if (mode >= 0) {
                        placeBrush = mode == 1;
                    }
                    tip(
                            placePrefab != null
                                    ? "The brush paints one mesh, so a prefab is placed with clicks. Choose a mesh in"
                                            + " the Library to paint with the brush."
                                    : "Click places one object where you click. Brush paints objects along a drag on"
                                            + " the ground, and Shift with a drag erases them. One drag is one Undo"
                                            + " step.");
                    return mode >= 0;
                },
                () -> {
                    if (!brush) {
                        return false;
                    }
                    var left = GeodataWidgets.compactDecimal("Spacing", "##placeSpacing", spacing, " units", third);
                    spacingEditing = ImGui.isItemActive();
                    if (left) {
                        spacing.set(TerrainCapabilityPanel.oneDecimal(Math.clamp(spacing.get(), 1, 100_000)));
                    }
                    tip("How far apart the objects stand along the drag, in units (1 unit is 4 cm). Twice the"
                            + " mesh's width at first. Shift and a drag erase the objects of this mesh within this"
                            + " distance of the pointer.");
                    return left;
                },
                () -> {
                    if (!brush) {
                        return false;
                    }
                    var left = GeodataWidgets.compactDecimal("Jitter", "##placeJitter", jitter, "", third);
                    if (left) {
                        jitter.set(Math.clamp(jitter.get(), 0, 1));
                    }
                    tip("How far each object may move from its place on the path, as a share of the spacing: 0"
                            + " keeps a line, 1 scatters them widely.");
                    return left;
                });
    }

    private void drawPlaceOptions() {
        var half = GeodataWidgets.columnWidth(2);
        var box = ImGui.getFrameHeight() + ImGui.getStyle().getItemInnerSpacingX();
        var grid = "Snap to grid (" + RelativeNumber.format(getGridStep()) + ")";
        var widest = box
                + Math.max(
                        ImGui.calcTextSizeX("Align to ground"),
                        Math.max(ImGui.calcTextSizeX("Random yaw"), ImGui.calcTextSizeX(grid)));
        BooleanSupplier align = () -> {
            var clicked = ImGui.checkbox("Align to ground##placeAlign", alignToNormal);
            if (clicked) {
                alignToNormal = !alignToNormal;
            }
            tip("Turn each new object so its up axis follows the slope under the pointer. It keeps its turn around"
                    + " the vertical.");
            return clicked;
        };
        BooleanSupplier yaw = () -> {
            var clicked = ImGui.checkbox("Random yaw##placeYaw", placeRandomYaw);
            if (clicked) {
                placeRandomYaw = !placeRandomYaw;
            }
            tip("Turn each new object to a random direction around its vertical axis.");
            return clicked;
        };
        BooleanSupplier snap = () -> {
            var clicked = ImGui.checkbox(grid + "##placeGrid", snapPlaceToGrid);
            if (clicked) {
                snapPlaceToGrid = !snapPlaceToGrid;
            }
            tip("Put each new object on the grid of the Select tool's Grid snap, in X and Y.");
            return clicked;
        };
        if (widest <= half) {
            GeodataWidgets.columns(align, yaw);
            GeodataWidgets.columns(snap, () -> false);
        } else {
            align.getAsBoolean();
            yaw.getAsBoolean();
            snap.getAsBoolean();
        }
        if (ImGui.checkbox("Random scale##placeScale", placeRandomScale)) {
            placeRandomScale = !placeRandomScale;
        }
        tip("Give each new object a random size between the two factors.");
        ImGui.sameLine(0, GeodataWidgets.ROW);
        scaleRange("place", placeScaleMin, placeScaleMax);
    }

    private void drawChosenMesh() {
        long texture = 0;
        String title;
        String detail;
        var glyph = Glyph.BOX;
        if (placePrefab != null) {
            title = placePrefab.name();
            detail = "Prefab · " + EditorUi.plural(placePrefab.clip().items().size(), "object", "objects");
            glyph = Glyph.LAYERS;
        } else if (placeMesh != null) {
            var entry = library == null ? null : library.getMesh(placeMesh.meshPath());
            texture = entry == null ? 0 : library.getThumbnail(entry);
            title = placeMesh.name();
            var bounds = getPlaceBounds();
            detail = (entry == null ? mapOf(placeMesh.meshPath()) : entry.packageName())
                    + (bounds == null
                            ? ""
                            : " · "
                                    + Math.round(bounds[3] - bounds[0])
                                    + " × "
                                    + Math.round(bounds[4] - bounds[1])
                                    + " × "
                                    + Math.round(bounds[5] - bounds[2]));
        } else {
            title = "No mesh chosen";
            detail = "Choose one in the Library";
            glyph = Glyph.PLUS;
        }
        if (GeodataWidgets.thumbnailRow(
                "placeChosen",
                texture,
                glyph,
                title,
                detail,
                MeshLibraryPanel.THUMBNAIL,
                placeMesh != null || placePrefab != null)) {
            libraryRequested = true;
        }
        tip(
                placeMesh != null
                        ? placeMesh.meshPath()
                                + (placeMesh.packageFile() == null
                                        ? ""
                                        : " from " + placeMesh.packageFile().getFileName())
                                + "\n"
                                + "Each click on a surface places one, standing on it. A click here opens the Library."
                        : placePrefab != null
                                ? placePrefab.file()
                                        + "\n"
                                        + "Each click on a surface places the whole group. A click here opens the"
                                        + " Library."
                                : "A click here opens the Library. " + COMMAND_KEY
                                        + "+click on an object in the viewport takes its mesh.");
    }

    private List<String> getRecentMeshes(Map<String, Integer> counts) {
        var result = new ArrayList<String>();
        if (library != null) {
            result.addAll(library.getRecent());
        }
        var used = new ArrayList<>(counts.keySet());
        used.sort(Comparator.comparingInt((String mesh) -> -counts.get(mesh))
                .thenComparing(String.CASE_INSENSITIVE_ORDER));
        for (var mesh : used) {
            if (result.size() >= RECENT_MESHES) {
                break;
            }
            if (result.stream().noneMatch(mesh::equalsIgnoreCase)) {
                result.add(mesh);
            }
        }
        return result.size() > RECENT_MESHES ? List.copyOf(result.subList(0, RECENT_MESHES)) : result;
    }

    private void drawRecentMeshes() {
        var counts = getSceneMeshCounts();
        var meshes = getRecentMeshes(counts);
        if (meshes.isEmpty()) {
            return;
        }
        var width = ImGui.getContentRegionAvailX();
        var fit = Math.max(1, (int) ((width - 4 + GeodataWidgets.ROW) / (RECENT_PICTURE + GeodataWidgets.ROW)));
        if (meshes.size() > fit) {
            meshes = meshes.subList(0, fit);
        }
        var placed = library == null ? List.<String>of() : library.getRecent();
        var textures = new long[meshes.size()];
        var tips = new String[meshes.size()];
        for (var i = 0; i < meshes.size(); i++) {
            var mesh = meshes.get(i);
            var entry = library == null ? null : library.getMesh(mesh);
            textures[i] = entry == null ? 0 : library.getThumbnail(entry);
            var count = counts.getOrDefault(mesh, 0);
            tips[i] = EditingSession.shortName(mesh)
                    + (placed.stream().anyMatch(mesh::equalsIgnoreCase)
                            ? " · placed in this session"
                            : count > 0 ? " · " + count + " in the loaded maps" : "")
                    + "\nClick to place it.";
        }
        GeodataWidgets.section("RECENT", GeodataWidgets.ROW);
        var clicked = GeodataWidgets.pictureGrid(
                "placeRecent", textures, Glyph.BOX, new boolean[meshes.size()], tips, -1, RECENT_PICTURE, width);
        if (clicked >= 0) {
            var mesh = meshes.get(clicked);
            var entry = library == null ? null : library.getMesh(mesh);
            chooseMesh(mesh, entry == null ? null : entry.packageFile());
        }
    }

    private void resetSpacing() {
        var mesh = placeMesh == null ? "" : placeMesh.meshPath();
        if (mesh.equals(spacingMesh)) {
            return;
        }
        var bounds = getPlaceBounds();
        if (bounds == null && !mesh.isEmpty()) {
            return;
        }
        spacingMesh = mesh;
        if (bounds != null) {
            spacing.set(Math.max(16, Math.round(2 * Math.max(bounds[3] - bounds[0], bounds[4] - bounds[1]))));
        }
    }

    private void openCameraMap() {
        var map = terrainCapability.getCameraMap();
        if (map.isEmpty()
                || exports.isBusy()
                || !openedForPlacing.add(map)
                || session.getOpenedMaps().contains(map)) {
            return;
        }
        try {
            session.openMap(map);
        } catch (RuntimeException missing) {
        }
    }

    private Map<String, List<String>> placeMeshes() {
        var maps = session.getOpenedMaps();
        if (!maps.equals(placeMeshMaps) || session.getCommittedCommands() != placeMeshCommands) {
            placeMeshes = session.getMeshReferences();
            placeMeshMaps = maps;
            placeMeshCommands = session.getCommittedCommands();
        }
        return placeMeshes;
    }

    private void replaceSelection(String meshPath, @Nullable Path packageFile) {
        var entry = library == null ? null : library.getMesh(meshPath);
        var kept = entry == null || entry.slots().isEmpty()
                ? Integer.MAX_VALUE
                : entry.slots().size();
        run(() -> session.replaceMesh(
                meshPath, packageFile != null ? packageFile : entry == null ? null : entry.packageFile(), kept));
    }

    private void pasteClip(Clip clip, Vector3 point, Set<String> loadedMaps, String label) {
        var note = new StringBuilder();
        var placements = ActorClipboard.placements(clip, point, location -> targetMap(location, loadedMaps, note));
        run(() -> {
            session.placeMany(placements, label, null);
            note(note.toString(), false);
        });
    }

    private Clip selectionClip() {
        if (session.getSelection().isEmpty()) {
            return null;
        }
        var clip = ActorClipboard.of(
                session,
                session.getSelection(),
                session.getPivot(),
                viewport == null ? null : viewport.getSelectionBottom(),
                this::packageOf);
        if (clip == null) {
            showError(
                    session.getSelection().stream().allMatch(EditorUi::isPawn)
                            ? PAWNS_NOT_COPIED
                            : "The selected objects have no mesh to copy.");
        }
        return clip;
    }

    private @Nullable Path packageOf(String objectPath) {
        if (library != null) {
            var mesh = library.getMesh(objectPath);
            if (mesh != null) {
                return mesh.packageFile();
            }
            var material = library.getMaterial(objectPath);
            if (material != null) {
                return material.packageFile();
            }
        }
        return session.getPackageFile(objectPath);
    }

    private void drawPrefabPicker() {
        var file = prefabPicker.draw();
        if (file == null || prefabClip == null) {
            return;
        }
        var target = ActorClipboard.prefabFile(file);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, ActorClipboard.encode(prefabClip));
            prefabFolder = target.getParent();
            prefabClip = null;
            error = "";
            note("Prefab saved: " + target.getFileName() + ". The Library lists it under Prefabs.", true);
            prefabWritten = target;
        } catch (IOException failed) {
            showError("The prefab could not be saved: " + failed.getMessage());
        }
    }

    private void drawShowAll(float width) {
        var hidden = session.getHiddenActors().size();
        if (HudWidgets.button(
                "editingShowAll",
                Glyph.EYE,
                hidden > 0 ? "Show all · " + hidden + " hidden" : "Show all",
                width,
                BUTTON_HEIGHT,
                false,
                false)) {
            showAll();
        }
        tip(
                isolatedActors != null
                        ? "End Isolate and show all that is hidden again (Alt+H)."
                        : "Show all that is hidden again (Alt+H).");
        if (isolatedActors != null && inspection != null) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            IsolateControls.draw(inspection, width);
        }
    }

    private void drawSelectionCard() {
        var selection = session.getSelection();
        var primary = session.getPrimary();
        var actor = session.getActor(primary);
        var restriction = session.getStructuralRestriction();
        GeodataWidgets.space(GeodataWidgets.SECTION);
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var pad = GeodataWidgets.BLOCK;
        var inner = width - 2 * pad;
        var left = x + pad;
        draw.channelsSplit(2);
        draw.channelsSetCurrent(1);
        ImGui.setCursorScreenPos(left, y + pad);
        ImGui.indent(pad);
        var top = ImGui.getCursorScreenPosY();
        var icons = 3 * BUTTON_HEIGHT + 2 * 4;
        var room = inner - icons - GeodataWidgets.ROW;
        String title;
        String detail;
        String about;
        if (selection.size() == 1 && actor.emitter()) {
            title = EditingSession.shortName(primary);
            var group = session.findGroupOf(primary);
            var emitters = session.getEmitterCount(primary);
            detail = "Effect · "
                    + mapOf(primary)
                    + " · "
                    + EditorUi.plural(emitters, "emitter", "emitters")
                    + (actor.created() ? " · new" : session.isChanged(primary) ? " · changed" : "")
                    + (group == null ? "" : " · " + group);
            about = primary + " (" + actor.className() + ")";
        } else if (selection.size() == 1) {
            var mesh = actor.meshSourcePath();
            title = mesh.isEmpty() ? actor.className() : EditingSession.shortName(mesh);
            var group = session.findGroupOf(primary);
            detail = EditingSession.shortName(primary)
                    + " · "
                    + mapOf(primary)
                    + (actor.created() ? " · new" : session.isChanged(primary) ? " · changed" : "")
                    + (group == null ? "" : " · " + group);
            about = primary + (mesh.isEmpty() ? "" : " · " + mesh) + " (" + actor.className() + ")";
        } else {
            var maps = new TreeSet<String>();
            selection.forEach(path -> maps.add(mapOf(path)));
            var group = session.getSelectedGroup();
            if (group != null) {
                title = group + " · " + EditorUi.plural(selection.size(), "object", "objects");
                detail = String.join(", ", maps) + " · " + meshNames(selection);
                about = group
                        + " is a group of this session, kept in the draft and never in the maps. A click on one of its"
                        + " objects selects the group, a second click the object alone, and Esc the group again.";
            } else {
                title = EditorUi.plural(selection.size(), "object", "objects") + " · " + String.join(", ", maps);
                detail = meshNames(selection);
                about = "The active object is " + primary + ": the materials are its own.";
            }
        }
        var font = fonts == null ? null : fonts.getHeadingFont(15);
        if (font != null) {
            ImGui.pushFont(font, 15f);
        }
        var titleSize = ImGui.getFontSize();
        draw.addText(left, top, HudWidgets.TEXT, HudWidgets.truncateToWidth(title, room));
        if (font != null) {
            ImGui.popFont();
        }
        var detailTop = top + titleSize + 4;
        draw.addText(left, detailTop, GeodataWidgets.MUTED, HudWidgets.truncateToWidth(detail, room));
        var titleHeight = Math.max(BUTTON_HEIGHT, detailTop + ImGui.getFontSize() - top);
        ImGui.dummy(room, titleHeight);
        tip(about);
        ImGui.setCursorScreenPos(left + inner - icons, top);
        if (HudWidgets.iconButton("editingFocus", Glyph.FOCUS, false, HudWidgets.TEXT)) {
            focusRequested = true;
        }
        tip("Focus (F): frame the selection with the camera.");
        ImGui.sameLine(0, 4);
        var locked = session.getLockedActors().containsAll(selection);
        if (HudWidgets.iconButton("editingLock", Glyph.LOCK, locked, HudWidgets.TEXT)) {
            toggleLock();
        }
        tip(
                locked
                        ? "Unlock (L): moves and clicks reach the selection again."
                        : "Lock (L): moves, clicks and the rectangle leave the selection alone. Nothing is saved.");
        ImGui.sameLine(0, 4);
        if (HudWidgets.iconButton("editingHide", Glyph.EYE_OFF, false, HudWidgets.TEXT)) {
            hideSelection();
        }
        tip("Hide (H): hide the selection while you work. It stays in its map, and Alt+H shows it again.");
        ImGui.setCursorScreenPos(left, top + titleHeight);
        ImGui.dummy(0, 0);
        if (!actor.restriction().isBlank()) {
            GeodataWidgets.note(actor.restriction(), GeodataWidgets.WARNING, inner);
        }
        if (!restriction.isBlank()) {
            GeodataWidgets.note(restriction, GeodataWidgets.WARNING, inner);
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        var rowTop = ImGui.getCursorScreenPosY();
        ImGui.beginDisabled(!restriction.isBlank());
        if (HudWidgets.iconButton("editingDuplicate", Glyph.COPY, false, HudWidgets.TEXT)) {
            duplicate();
        }
        tip("Duplicate (" + COMMAND_KEY + "+D): copy the selection " + offsetText()
                + " away. Duplicate offset in ⋯ sets how far. Alt and a drag of the gizmo copies it where you drag.");
        ImGui.sameLine(0, 4);
        if (HudWidgets.iconButton("editingDelete", Glyph.TRASH, false, GeodataWidgets.BLOCKED)) {
            deleteSelection();
        }
        tip("Delete (Del): take the selection out of its maps. Undo brings it back.");
        ImGui.endDisabled();
        ImGui.sameLine(0, 4);
        if (HudWidgets.iconButton("editingIsolate", Glyph.FILTER, isolatedActors != null, HudWidgets.TEXT)) {
            isolateSelection();
        }
        tip("Isolate (Shift+H): look at the selection alone until Show all (Alt+H). The rest of the scene shows"
                + " only as faintly as Others says, and clicks reach only the selection. Nothing changes in the"
                + " maps.");
        ImGui.sameLine(0, 4);
        if (HudWidgets.iconButton("editingDrop", Glyph.DROP, false, HudWidgets.TEXT)) {
            requestDrop(ImGui.getIO().getKeyShift());
        }
        tip("Drop to ground (End): lower or raise the selection onto the ground that the camera sees around it."
                + " Shift+End also turns it to the slope there.");
        ImGui.sameLine(0, 4);
        var pawns = selection.stream().anyMatch(EditorUi::isPawn);
        var nonStatic = selection.stream().anyMatch(session::isNonStatic);
        var effects = selection.stream().anyMatch(session::isEmitter);
        var keepsMesh = pawns || nonStatic || effects;
        ImGui.beginDisabled(keepsMesh);
        if (HudWidgets.iconButton(
                "editingReplaceMesh", Glyph.BOX, replacePick, keepsMesh ? GeodataWidgets.FAINT : HudWidgets.TEXT)) {
            requestReplaceMesh();
        }
        tip(
                pawns
                        ? PackageEditDocument.PAWN_LOOK
                        : effects
                                ? PackageEditDocument.EFFECT_KEEPS
                                : nonStatic
                                        ? PackageEditDocument.MOVING_KEEPS
                                        : replacePick
                                                ? "Choose the new mesh in the Library on the left, or press Esc to"
                                                        + " keep the mesh."
                                                : "Replace mesh…: give the selection another mesh from the Library."
                                                        + " Place, turn, size and materials stay.");
        ImGui.endDisabled();
        ImGui.setCursorScreenPos(left + inner - BUTTON_HEIGHT, rowTop);
        if (HudWidgets.iconButton("editingMore", Glyph.MORE, ImGui.isPopupOpen(MORE_MENU), HudWidgets.TEXT)
                || moreRequested) {
            moreRequested = false;
            ImGui.openPopup(MORE_MENU);
        }
        tip("More: duplicate offset, mirror, group, align, move to another map, save as prefab and revert to the"
                + " client.");
        drawMoreMenu(restriction, selection.size());
        var tile = moveTarget(session.getMapFiles(), primary, actor);
        if (tile != null && restriction.isBlank()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            if (HudWidgets.button(
                    "editingMoveToTile", Glyph.GO, "Move to " + tile, inner, BUTTON_HEIGHT, false, true)) {
                moveSelectionTo(tile);
            }
            var why = !moveActorsToTheirTile
                    ? " Moves keep objects in their maps, as the setting Move actors to their tile is off."
                    : isLoaded(tile)
                            ? ""
                            : " The map " + tile + " is not loaded, so the move left it in " + mapOf(primary) + ".";
            tip(EditingSession.shortName(primary)
                    + " stands in "
                    + tile
                    + " but belongs to "
                    + mapOf(primary)
                    + "."
                    + why
                    + " Move to puts it into the package of "
                    + tile
                    + "; it keeps its place.");
        }
        if (!session.getHiddenActors().isEmpty() || isolatedActors != null) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            drawShowAll(inner);
        }
        var bottom = ImGui.getItemRectMaxY() + pad;
        ImGui.unindent(pad);
        draw.channelsSetCurrent(0);
        draw.addRectFilled(x, y, x + width, bottom, GeodataWidgets.CARD, 8f);
        draw.channelsMerge();
        ImGui.setCursorScreenPos(x, bottom);
        ImGui.dummy(width, 0);
        if (selection.size() == 1) {
            drawTransform(primary, actor);
            var motion = session.getMotionDescription(primary);
            if (motion != null) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                EditorUi.message(Tone.INFO, motion);
            }
        } else {
            drawGroupTransform();
        }
        if (!actor.meshSourcePath().isBlank() && !actor.pawn() && !actor.emitter()) {
            drawMaterials(primary, actor);
        }
    }

    private void drawSelectionLine() {
        GeodataWidgets.space(GeodataWidgets.SECTION);
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var room = width - BUTTON_HEIGHT - GeodataWidgets.ROW;
        var selected = EditorUi.plural(session.getSelection().size(), "object", "objects") + " selected";
        var text = selected + " · " + meshNames(session.getSelection()) + " · Q to edit";
        if (ImGui.calcTextSizeX(text) > room) {
            text = selected + " · Q to edit";
        }
        var clicked = ImGui.invisibleButton("editingSelectionLine", Math.max(1, room), BUTTON_HEIGHT);
        var hovered = ImGui.isItemHovered();
        ImGui.getWindowDrawList()
                .addText(
                        x,
                        y + (BUTTON_HEIGHT - ImGui.getFontSize()) * .5f - 1,
                        hovered ? HudWidgets.TEXT : GeodataWidgets.MUTED,
                        HudWidgets.truncateToWidth(text, room));
        tip("Edit the selection with the Select tool (Q).");
        if (clicked) {
            setTool(Tool.SELECT);
        }
        ImGui.setCursorScreenPos(x + width - BUTTON_HEIGHT, y);
        if (HudWidgets.iconButton("editingLineFocus", Glyph.FOCUS, false, HudWidgets.TEXT)) {
            focusRequested = true;
        }
        tip("Focus (F): frame the selection with the camera.");
    }

    private String meshNames(Collection<String> actors) {
        var names = new LinkedHashSet<String>();
        for (var path : actors) {
            try {
                var actor = session.getActor(path);
                names.add(
                        actor.meshSourcePath().isBlank()
                                ? actor.className()
                                : EditingSession.shortName(actor.meshSourcePath()));
            } catch (IllegalArgumentException deleted) {
            }
        }
        var list = List.copyOf(names);
        return switch (list.size()) {
            case 0 -> "";
            case 1 -> list.getFirst();
            case 2 -> list.get(0) + " and " + list.get(1);
            default -> list.get(0) + ", " + list.get(1) + " and " + (list.size() - 2) + " more";
        };
    }

    private String offsetText() {
        return RelativeNumber.format(duplicateOffset[0])
                + ", "
                + RelativeNumber.format(duplicateOffset[1])
                + ", "
                + RelativeNumber.format(duplicateOffset[2]);
    }

    private void drawMoreMenu(String restriction, int count) {
        var right = ImGui.getItemRectMaxX();
        var bottom = ImGui.getItemRectMaxY();
        var several = count > 1;
        var rows = 9 + (several ? 3 : 0) + (count > 2 ? 2 : 0);
        if (!HudWidgets.beginFittedMenu(MORE_MENU, 236, HudWidgets.menuHeight(rows, several ? 4 : 3))) {
            return;
        }
        closeOnEscape();
        var free = restriction.isBlank();
        var effects = session.getSelection().stream().anyMatch(session::isEmitter);
        if (HudWidgets.menuRow("moreOffset", Glyph.COPY, "Duplicate offset…", "", free)) {
            openSoon(OFFSET_POPUP, right, bottom);
        }
        GeodataWidgets.tip(free ? "How far Duplicate puts the copies, now " + offsetText() + "." : restriction);
        HudWidgets.menuRule();
        for (var axis = 0; axis < 3; axis++) {
            var name = String.valueOf("XYZ".charAt(axis));
            if (HudWidgets.menuRow("moreMirror" + name, Glyph.MIRROR, "Mirror " + name, "", !effects)) {
                var chosen = axis;
                run(() -> session.mirror(chosen));
            }
            GeodataWidgets.tip(
                    effects
                            ? PackageEditDocument.EFFECT_SCALE
                            : "Turn the sign of the selection's " + name
                                    + " scale. Each object mirrors around its own origin. One Undo step.");
        }
        if (HudWidgets.menuRow("moreFaceCamera", Glyph.CAMERA, "Face the camera", "")) {
            faceCamera();
        }
        GeodataWidgets.tip(
                "Turn each selected object around the vertical axis, so that its front faces the camera. Pitch and"
                        + " roll stay. One Undo step.");
        if (HudWidgets.menuRow("moreFacePoint", Glyph.FOCUS, "Face a point…", "")) {
            armFacePoint();
        }
        GeodataWidgets.tip(
                "Click the scene next: each selected object turns around the vertical axis to face that point. Esc"
                        + " cancels.");
        if (several) {
            HudWidgets.menuRule();
            var group = session.getSelectedGroup();
            if (group != null) {
                if (HudWidgets.menuRow("moreUngroup", Glyph.UNGROUP, "Ungroup", "Shift+" + COMMAND_KEY + "+G")) {
                    ungroup(group);
                }
                GeodataWidgets.tip(
                        "Take the group " + group + " away. Its objects stay where they are and stay selected.");
            } else {
                if (HudWidgets.menuRow("moreGroup", Glyph.GROUP, "Group…", COMMAND_KEY + "+G")) {
                    requestGroupName(null, right, bottom);
                }
                GeodataWidgets.tip(
                        "Make a named group of the selection. A click on one of its objects then selects the whole"
                                + " group. Groups stay in this session and its draft, never in the maps.");
            }
            if (HudWidgets.menuRow("moreAlign", Glyph.ALIGN_LEFT, "Align…", "")) {
                openSoon(ALIGN_POPUP, right, bottom);
            }
            GeodataWidgets.tip(
                    "Line the selected objects up by their bounds: on the left, the middle or the right along X, the"
                            + " front, the middle or the back along Y, or at the same height.");
            if (count > 2) {
                for (var axis = 0; axis < 2; axis++) {
                    var name = axis == 0 ? "X" : "Y";
                    var chosen = axis;
                    if (HudWidgets.menuRow(
                            "moreDistribute" + name,
                            axis == 0 ? Glyph.DISTRIBUTE_X : Glyph.DISTRIBUTE_Y,
                            "Distribute " + name,
                            "")) {
                        distribute(chosen);
                    }
                    GeodataWidgets.tip("Leave equal gaps between the bounds of the selected objects along " + name
                            + ". The first and the last keep their place. One Undo step.");
                }
            }
        }
        HudWidgets.menuRule();
        var pawns = session.getSelection().stream().anyMatch(EditorUi::isPawn);
        var nonStatic = session.getSelection().stream().anyMatch(session::isNonStatic);
        if (HudWidgets.menuRow(
                "moreMap", Glyph.GO, "Move to another map…", "", free && !pawns && !nonStatic && !effects)) {
            openSoon(MAP_POPUP, right, bottom);
        }
        GeodataWidgets.tip(
                !free
                        ? restriction
                        : pawns
                                ? PackageEditDocument.PAWN_MAP
                                : effects
                                        ? PackageEditDocument.EFFECT_KEEPS
                                        : nonStatic
                                                ? PackageEditDocument.MOVING_KEEPS
                                                : "Put the selection into the package of another map, such as"
                                                        + " 21_18. Each object keeps its place.");
        if (several) {
            if (HudWidgets.menuRow("moreRandom", Glyph.DICE, "Randomize…", "", !effects)) {
                openSoon(RANDOM_POPUP, right, bottom);
            }
            GeodataWidgets.tip(
                    effects
                            ? PackageEditDocument.EFFECT_SCALE
                            : "Give each selected object a random turn and size, around its own origin.");
        }
        if (HudWidgets.menuRow("morePrefab", Glyph.FILE, "Save as prefab…", "", !effects)) {
            savePrefab();
        }
        GeodataWidgets.tip(
                effects
                        ? EditorUi.EFFECT_PREFAB
                        : "Save the selection as a prefab file. The Library lists the prefabs of its folder, and the"
                                + " Place tool places the whole group.");
        HudWidgets.menuRule();
        if (HudWidgets.menuRow("moreRevert", Glyph.RELOAD, "Revert to the client", "")) {
            resetSelection();
        }
        GeodataWidgets.tip("Return the selection to its place, turn, size and materials in the client. One Undo step.");
        HudWidgets.endMenu();
    }

    private void openSoon(String popup, float right, float bottom) {
        pendingPopup = popup;
        popupRight = right;
        popupBottom = bottom;
    }

    private void drawPendingPopup() {
        if (pendingPopup != null) {
            if (pendingPopup.equals(OFFSET_POPUP)) {
                for (var i = 0; i < 3; i++) {
                    duplicateText[i].set(EditorUi.compact(duplicateOffset[i]));
                }
            } else if (pendingPopup.equals(COORDINATES_POPUP)) {
                for (var i = 0; i < 3; i++) {
                    createText[i].set(EditorUi.compact(createPosition[i]));
                }
            }
            ImGui.openPopup(pendingPopup);
            pendingPopup = null;
        }
        if (HudWidgets.beginPopupBelow(OFFSET_POPUP, POPUP_WIDTH, popupRight, popupBottom)) {
            closeOnEscape();
            ImGui.textColored(GeodataWidgets.MUTED, "DUPLICATE OFFSET");
            tip("Copies stand this far from the originals, in world units. " + RelativeNumber.RULE);
            fieldRow("duplicateOffset", new String[] {"X", "Y", "Z"}, duplicateText, duplicateOffset, new String[] {
                "Along world X, in world units.", "Along world Y, in world units.", "Along world Z, in world units."
            });
            GeodataWidgets.space(GeodataWidgets.ROW);
            ImGui.beginDisabled(session.getSelection().isEmpty()
                    || !session.getStructuralRestriction().isBlank());
            if (GeodataWidgets.actionButton("offsetDuplicate", Glyph.COPY, "Duplicate", -1, true)) {
                duplicate();
                ImGui.closeCurrentPopup();
            }
            ImGui.endDisabled();
            tip("Copy the selection by this offset (" + COMMAND_KEY + "+D). The copies become the selection.");
            ImGui.endPopup();
        }
        if (HudWidgets.beginPopupBelow(GROUP_POPUP, POPUP_WIDTH, popupRight, popupBottom)) {
            closeOnEscape();
            ImGui.textColored(GeodataWidgets.MUTED, groupRenamed == null ? "NEW GROUP" : "RENAME GROUP");
            if (ImGui.isWindowAppearing()) {
                ImGui.setKeyboardFocusHere();
            }
            var named = GeodataWidgets.actionField(
                    "groupName",
                    "Name, such as Fence",
                    groupName,
                    groupRenamed == null
                            ? "The name of the group of the "
                                    + EditorUi.plural(
                                            session.getSelection().size(), "selected object", "selected objects")
                                    + ". Groups stay in this session and its draft, never in the maps."
                            : "The new name of " + groupRenamed + ".",
                    Glyph.CHECK,
                    groupRenamed == null ? "Make the group (Enter)." : "Rename the group (Enter).",
                    !groupName.get().isBlank());
            if (named.action()) {
                var renamed = groupRenamed;
                var name = groupName.get().trim();
                run(() -> {
                    if (renamed == null) {
                        var made = session.groupSelection(name);
                        note(
                                "Grouped "
                                        + EditorUi.plural(
                                                session.getGroupMembers(made).size(), "object", "objects")
                                        + " as " + made + ". A click on one selects them all.",
                                true);
                    } else {
                        session.renameGroup(renamed, name);
                    }
                });
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
        if (HudWidgets.beginPopupBelow(ALIGN_POPUP, POPUP_WIDTH, popupRight, popupBottom)) {
            closeOnEscape();
            ImGui.textColored(GeodataWidgets.MUTED, "ALIGN");
            tip("Line the selected objects up by their bounds, as one Undo step. X and Y go as the world map shows"
                    + " them, north up.");
            var glyphs = new Glyph[] {
                Glyph.ALIGN_LEFT,
                Glyph.ALIGN_CENTER_X,
                Glyph.ALIGN_RIGHT,
                Glyph.ALIGN_FRONT,
                Glyph.ALIGN_CENTER_Y,
                Glyph.ALIGN_BACK,
                Glyph.SAME_HEIGHT
            };
            var tips = new String[] {
                "Align left: the smallest X of the selection.",
                "Align centre along X: the middle of the selection along X.",
                "Align right: the largest X of the selection.",
                "Align front: the smallest Y of the selection, to the north on the world map.",
                "Align centre along Y: the middle of the selection along Y.",
                "Align back: the largest Y of the selection, to the south on the world map.",
                "Same height: every bottom at the lowest bottom of the selection."
            };
            ImGui.beginDisabled(session.getSelection().size() < 2
                    || !session.getStructuralRestriction().isBlank());
            for (var index = 0; index < glyphs.length; index++) {
                if (index > 0) {
                    ImGui.sameLine(0, index == 3 || index == 6 ? GeodataWidgets.ROW + 4 : 4);
                }
                if (HudWidgets.iconButton("editingAlign" + index, glyphs[index], false, HudWidgets.TEXT)) {
                    if (index == 6) {
                        sameHeight();
                    } else {
                        align(index < 3 ? 0 : 1, Edge.values()[index % 3]);
                    }
                }
                tip(tips[index] + " One Undo step.");
            }
            ImGui.endDisabled();
            ImGui.endPopup();
        }
        if (HudWidgets.beginPopupBelow(MAP_POPUP, POPUP_WIDTH, popupRight, popupBottom)) {
            closeOnEscape();
            ImGui.textColored(GeodataWidgets.MUTED, "MOVE TO ANOTHER MAP");
            if (ImGui.isWindowAppearing()) {
                ImGui.setKeyboardFocusHere();
            }
            var transfer = GeodataWidgets.actionField(
                    "transferMap",
                    "Map, such as 21_18",
                    targetMap,
                    "The map whose package takes the selection. Each object keeps its place.",
                    Glyph.GO,
                    "Move the selection into this map.",
                    !targetMap.get().isBlank() && !session.getSelection().isEmpty());
            if (transfer.action()) {
                run(() -> session.transferSelected(targetMap.get().trim()));
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
        if (HudWidgets.beginPopupBelow(RANDOM_POPUP, POPUP_WIDTH, popupRight, popupBottom)) {
            closeOnEscape();
            ImGui.textColored(GeodataWidgets.MUTED, "RANDOMIZE");
            if (ImGui.checkbox("Yaw from 0 to 360 degrees##editingRandomYaw", randomYaw)) {
                randomYaw = !randomYaw;
            }
            tip("Turn each object to a random direction around its vertical axis.");
            if (ImGui.checkbox("Scale##editingRandomScale", randomScale)) {
                randomScale = !randomScale;
            }
            tip("Give each object a random size between the two factors, times its size in the client. 1 is the"
                    + " size in the client.");
            ImGui.sameLine(0, GeodataWidgets.ROW);
            scaleRange("editingRandom", randomMin, randomMax);
            GeodataWidgets.space(GeodataWidgets.ROW);
            ImGui.beginDisabled(
                    !randomYaw && !randomScale || session.getSelection().size() < 2);
            if (GeodataWidgets.actionButton("editingRandomize", Glyph.DICE, "Randomize", -1, true)) {
                var seed = seeds.nextLong();
                var low = Math.min(randomMin.get(), randomMax.get());
                var high = Math.max(randomMin.get(), randomMax.get());
                run(() -> session.randomize(seed, randomYaw, randomScale, low, high));
            }
            ImGui.endDisabled();
            tip("Give each selected object new random values, as one Undo step. Press it again for other values.");
            ImGui.endPopup();
        }
        if (HudWidgets.beginPopupBelow(COORDINATES_POPUP, POPUP_WIDTH, popupRight, popupBottom)) {
            closeOnEscape();
            ImGui.textColored(GeodataWidgets.MUTED, "PLACE AT COORDINATES");
            tip("Where the new object stands in the world, in units (1 unit is 4 cm). It goes into the map of the"
                    + " tile there.");
            fieldRow("createPosition", new String[] {"X", "Y", "Z"}, createText, createPosition, new String[] {
                "World X of the new object.", "World Y of the new object.", "World Z of the new object."
            });
            GeodataWidgets.space(GeodataWidgets.ROW);
            var name = getPlaceName();
            ImGui.beginDisabled(name.isEmpty());
            if (GeodataWidgets.actionButton(
                    "placeAtCoordinates", Glyph.PLACE, name.isEmpty() ? "Place" : "Place " + name, -1, true)) {
                placeAt(toVector(createPosition), null, viewportMaps);
                ImGui.closeCurrentPopup();
            }
            ImGui.endDisabled();
            tip(
                    name.isEmpty()
                            ? "Choose a mesh or a prefab first."
                            : placePrefab != null
                                    ? "Place the whole group of " + name + " around these coordinates."
                                    : "Create one " + name + " with its location at these coordinates.");
            ImGui.endPopup();
        }
    }

    private void fieldRow(String id, String[] axes, ImString[] texts, float[] values, String[] tips) {
        var spacing = ImGui.getStyle().getItemSpacingX();
        var width = (ImGui.getContentRegionAvailX() - spacing * (axes.length - 1)) / axes.length;
        for (var i = 0; i < axes.length; i++) {
            if (i > 0) {
                ImGui.sameLine();
            }
            if (GeodataWidgets.axisField(axes[i], "##edit/" + id + i, texts[i], width)) {
                try {
                    values[i] = RelativeNumber.apply(texts[i].get(), values[i]);
                } catch (IllegalArgumentException invalid) {
                    showError(invalid.getMessage());
                }
                texts[i].set(EditorUi.compact(values[i]));
            }
            tip(tips[i] + exactValue(values[i]) + " " + RelativeNumber.RULE);
        }
    }

    private void drawTransform(String path, ActorRecord actor) {
        var rule = " " + RelativeNumber.RULE + " Enter or leaving the field applies it as one Undo step.";
        EditorUi.section("Transform");
        tip("Where " + EditingSession.shortName(path) + " stands, how it is turned and how large it is." + rule);
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/pivot");
        var t = actor.transform();
        var degrees = new float[] {rotation[0], rotation[1], rotation[2]};
        var moved = axisRow(
                "location",
                "Location",
                "Where the object stands in the world, in units (1 unit is 4 cm), now "
                        + EditorUi.metersXyz(location[0], location[1], location[2]) + "." + rule,
                new String[] {"X", "Y", "Z"},
                locationText,
                location,
                new String[] {"World X.", "World Y.", "World Z, up."},
                null);
        GeodataWidgets.space(GeodataWidgets.ROW);
        var turned = axisRow(
                "rotation",
                "Rotation",
                "Degrees. The map keeps a turn rounded to 0.005°, so a typed value may change a little when it is"
                        + " applied."
                        + rule + " (Rotation)",
                new String[] {"P", "Y", "R"},
                rotationText,
                degrees,
                new String[] {
                    "Pitch: the tilt up and down, in degrees.",
                    "Yaw: the turn around the vertical axis, in degrees.",
                    "Roll: the tilt to the side, in degrees."
                },
                null);
        GeodataWidgets.space(GeodataWidgets.ROW);
        var sizes = new float[] {uniform.get(), scale[0], scale[1], scale[2]};
        ImGui.beginDisabled(actor.emitter());
        var scaled = axisRow(
                "scale",
                "Scale",
                "The size: 1 is the size in the client. U sizes the whole mesh, X, Y and Z give it a size per axis."
                        + rule + " (DrawScale, and DrawScale3D per axis)",
                new String[] {"U", "X", "Y", "Z"},
                scaleText,
                sizes,
                new String[] {
                    "Uniform: the size of the whole mesh. (DrawScale)",
                    "X: the size along the mesh's own X axis. (DrawScale3D)",
                    "Y: the size along the mesh's own Y axis. (DrawScale3D)",
                    "Z: the size along the mesh's own Z axis. (DrawScale3D)"
                },
                null);
        ImGui.endDisabled();
        if (actor.emitter()) {
            tip(PackageEditDocument.EFFECT_SCALE);
            scaled = false;
        }
        if (moved || turned || scaled) {
            var value = new ActorTransform(
                    toVector(location),
                    turned
                            ? EditorTransforms.editedRotation(degrees[0], degrees[1], degrees[2], t.rotation())
                            : t.rotation(),
                    sizes[0],
                    new Vector3(sizes[1], sizes[2], sizes[3]),
                    t.prePivot());
            move(() -> session.setTransform(path, value), null);
            refresh();
        }
        var p = t.prePivot();
        if (p.x() != 0 || p.y() != 0 || p.z() != 0) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.value(
                    "Mesh offset",
                    RelativeNumber.format(p.x())
                            + ", "
                            + RelativeNumber.format(p.y())
                            + ", "
                            + RelativeNumber.format(p.z()));
            tip("The mesh is drawn this far from where the object stands, in units (1 unit is 4 cm). (PrePivot)");
        }
    }

    private void drawGroupTransform() {
        var rule = " " + RelativeNumber.RULE + " Enter or leaving the field applies it as one Undo step.";
        EditorUi.section("Transform");
        tip("Move, turn and scale the selection together." + rule);
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/pivot");
        var pivot = session.getPivot();
        var pivotTip = "The point the selection turns and scales around, now "
                + Math.round(pivot.x())
                + ", "
                + Math.round(pivot.y())
                + ", "
                + Math.round(pivot.z())
                + ". Center: the middle of the selection. Active: the last selected object. Each: every object around"
                + " its own origin.";
        GeodataWidgets.rowLabel("Pivot", ROW_LABEL);
        tip(pivotTip);
        var modes = PivotMode.values();
        var labels = new String[modes.length];
        for (var i = 0; i < modes.length; i++) {
            labels[i] = pivotLabel(modes[i]);
        }
        var picked = GeodataWidgets.segmented(
                "editingPivot", labels, session.getPivotMode().ordinal(), ImGui.getContentRegionAvailX());
        if (picked >= 0) {
            session.setPivotMode(modes[picked]);
        }
        tip(pivotTip);
        GeodataWidgets.space(GeodataWidgets.ROW);
        var offset = new float[3];
        if (axisRow(
                "groupMove",
                "Move by",
                "How far to move every selected object, in units (1 unit is 4 cm)." + rule,
                new String[] {"X", "Y", "Z"},
                groupMoveText,
                offset,
                new String[] {"Along world X.", "Along world Y.", "Along world Z, up."},
                null)) {
            run(() -> session.move(toVector(offset)));
        }
        resetGroupRow(groupMoveText, "0", null);
        GeodataWidgets.space(GeodataWidgets.ROW);
        var turn = new float[3];
        var turnOnly = "Several objects turn only around the vertical axis through the pivot.";
        if (axisRow(
                "groupTurn",
                "Turn by",
                "How far to turn the selection around the pivot, in degrees." + rule,
                new String[] {"P", "Y", "R"},
                groupTurnText,
                turn,
                new String[] {
                    turnOnly, "Yaw: the turn around the vertical axis through the pivot, in degrees.", turnOnly
                },
                new boolean[] {false, true, false})) {
            run(() -> {
                var r = EditorTransforms.modelMatrix(new ActorTransform(
                        Vector3.ZERO, new Rotator(0, toRotationUnits(turn[1]), 0), 1, Vector3.ONE, Vector3.ZERO));
                session.transformGroup(aroundPivot(r, session.getPivot()));
            });
        }
        resetGroupRow(groupTurnText, "0", new boolean[] {false, true, false});
        GeodataWidgets.space(GeodataWidgets.ROW);
        var factor = new float[] {1, 1, 1, 1};
        var wholeOnly = "Several objects scale only as a whole, around the pivot.";
        if (axisRow(
                "groupScale",
                "Scale by",
                "A factor for the size of the selection and the distances in it: 2 doubles them, 0.5 halves them."
                        + rule,
                new String[] {"U", "X", "Y", "Z"},
                groupScaleText,
                factor,
                new String[] {"Uniform: the factor for the whole selection.", wholeOnly, wholeOnly, wholeOnly},
                new boolean[] {true, false, false, false})) {
            run(() -> {
                if (!(factor[0] > 0)) {
                    throw new IllegalArgumentException("Group scale must be positive.");
                }
                var matrix = EditorTransforms.identity();
                matrix[0] = matrix[5] = matrix[10] = factor[0];
                session.transformGroup(aroundPivot(matrix, session.getPivot()));
            });
        }
        resetGroupRow(groupScaleText, "1", new boolean[] {true, false, false, false});
    }

    private void drawMaterials(String path, ActorRecord actor) {
        EditorUi.section("Materials");
        if (session.getSelection().size() > 1) {
            tip("The slots of "
                    + EditingSession.shortName(path)
                    + ", the active object. The picker can apply a material to all "
                    + session.getSelection().size()
                    + ".");
        }
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/material-slot");
        slotAreas.clear();
        var slots = slotsOf(path, actor);
        var spacingX = ImGui.getStyle().getItemSpacingX();
        var button = BUTTON_HEIGHT;
        var height = SLOT_SWATCH + 8;
        for (var slot : slots) {
            var x = ImGui.getCursorScreenPosX();
            var y = ImGui.getCursorScreenPosY();
            var width = ImGui.getContentRegionAvailX();
            var shown = slot.override() != null ? slot.override() : slot.meshMaterial();
            if (GeodataWidgets.thumbnailLine(
                    "slot" + slot.index(),
                    swatchOf(shown),
                    Glyph.LAYERS,
                    slot.index() + " · " + slot.name(),
                    slot.assignment(),
                    SLOT_SWATCH,
                    pickerSlot == slot.index() || eyedropperSlot == slot.index(),
                    width - button - spacingX)) {
                openPicker(slot.index(), x, y);
            }
            tip("Slot "
                    + slot.index()
                    + " of "
                    + EditingSession.shortName(actor.meshSourcePath())
                    + (slot.meshMaterial().isBlank() ? "" : ", whose own material is " + slot.meshMaterial())
                    + ". It draws "
                    + slot.assignment()
                    + ". Click to choose its material; pointing at one in the list shows it on the object."
                    + (session.canImportTextures() ? " Drop a PNG, JPEG, BMP or DDS file here to import it." : ""));
            slotAreas.add(new float[] {x, y, x + width, y + height, slot.index()});
            ImGui.setCursorScreenPos(x + width - button, y + (height - button) * .5f);
            if (HudWidgets.iconButton(
                    "slotEyedropper" + slot.index(),
                    Glyph.EYEDROPPER,
                    eyedropperSlot == slot.index(),
                    HudWidgets.TEXT)) {
                eyedropperSlot = eyedropperSlot == slot.index() ? -1 : slot.index();
                eyedropperScope = session.getSelection().size() > 1 ? Scope.SELECTION : Scope.ACTOR;
                lastSlot = slot.index();
            }
            tip("Take the material of slot " + slot.index()
                    + " from another object: click it in the viewport. Esc cancels. While the picker is open,"
                    + " Alt+click does the same.");
            ImGui.setCursorScreenPos(x, y + height);
            ImGui.dummy(width, 0);
        }
    }

    private List<Slot> slotsOf(String path, ActorRecord actor) {
        var overrides = new ArrayList<@Nullable String>();
        for (var reference : actor.skinReferences()) {
            overrides.add(reference == 0 ? null : session.getMaterialName(path, reference));
        }
        var entry = library == null ? null : library.getMesh(actor.meshSourcePath());
        List<String> meshMaterials;
        if (entry != null) {
            meshMaterials = entry.slots();
        } else {
            var scene = viewport == null ? List.<String>of() : viewport.getSceneSlots(path);
            var known = new ArrayList<String>(scene);
            for (var i = 0; i < known.size(); i++) {
                if (i < overrides.size() && overrides.get(i) != null) {
                    known.set(i, "");
                }
            }
            meshMaterials = known;
        }
        return MaterialSlots.of(meshMaterials, overrides);
    }

    private long swatchOf(@Nullable String material) {
        if (library == null || material == null || material.isBlank()) {
            return 0;
        }
        var entry = library.getMaterial(material);
        return entry == null ? 0 : library.getSwatch(entry);
    }

    private void openPicker(int slot, float x, float y) {
        pickerSlot = slot;
        lastSlot = slot;
        pickerOpening = true;
        pickerX = x;
        pickerY = y;
        eyedropperSlot = -1;
        pickerScope = session.getSelection().size() > 1 ? Scope.SELECTION : Scope.ACTOR;
        if (library != null) {
            library.startMaterials();
        }
    }

    private void closePicker() {
        pickerSlot = -1;
        pickerClosing = false;
        hoverKey = null;
        if (previewKey != null) {
            previewKey = null;
            session.endMaterialPreview();
        }
    }

    private void drawPicker() {
        if (pickerSlot < 0) {
            return;
        }
        var forSurfaces = pickerSlot == SURFACE_TARGET;
        var primary = session.getPrimary();
        if (exports.isBusy() || (forSurfaces ? selectedSurfaces.isEmpty() : primary == null)) {
            closePicker();
            return;
        }
        var io = ImGui.getIO();
        if (pickerOpening) {
            pickerOpening = false;
            pickerFilter.set("");
            ImGui.openPopup(PICKER);
        }
        var width = Math.min(PICKER_WIDTH, io.getDisplaySizeX() - 24);
        var height = Math.min(480, io.getDisplaySizeY() - 24);
        ImGui.setNextWindowPos(
                Math.clamp(pickerX - width - GeodataWidgets.BLOCK, 12, Math.max(12, io.getDisplaySizeX() - width - 12)),
                Math.clamp(pickerY - 40, 12, Math.max(12, io.getDisplaySizeY() - height - 12)),
                ImGuiCond.Appearing);
        ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
        if (!ImGui.beginPopup(PICKER)) {
            if (!pickerClosing && ImGui.isMouseClicked(0) && io.getKeyAlt()) {
                pickerClosing = true;
                return;
            }
            closePicker();
            return;
        }
        Slot slot;
        if (forSurfaces) {
            var active = selectedSurfaces.getFirst();
            String own;
            String current;
            try {
                own = session.getSourceSurfaceMaterial(active);
                current = session.getSurfaceMaterial(active);
            } catch (IOException | RuntimeException unreadable) {
                own = "";
                current = "";
            }
            slot = new Slot(SURFACE_TARGET, "Material", own, current.equalsIgnoreCase(own) ? null : current);
            ImGui.textUnformatted("Material");
            ImGui.sameLine();
            ImGui.textColored(GeodataWidgets.FAINT, "of " + surfacesName(selectedSurfaces.getList()));
            EditorUi.colored(GeodataWidgets.FAINT, "Alt+click a surface or an object takes its material");
            GeodataWidgets.space(GeodataWidgets.ROW);
        } else {
            var actor = session.getActor(primary);
            var slots = slotsOf(primary, actor);
            slot = pickerSlot < slots.size()
                    ? slots.get(pickerSlot)
                    : new Slot(pickerSlot, "Slot " + pickerSlot, "", null);
            ImGui.textUnformatted("Slot " + slot.index() + " · " + slot.name());
            ImGui.sameLine();
            ImGui.textColored(GeodataWidgets.FAINT, "of " + EditingSession.shortName(primary));
            EditorUi.colored(GeodataWidgets.FAINT, "Alt+click an object takes its material");
            GeodataWidgets.space(GeodataWidgets.ROW);
            var sameMesh = sameMeshActors(primary, actor.meshSourcePath());
            var labels = new String[] {
                "This object", session.getSelection().size() + " selected", sameMesh.size() + " with this mesh"
            };
            var scope = GeodataWidgets.segmented("pickerScope", labels, pickerScope.ordinal());
            if (scope >= 0) {
                pickerScope = Scope.values()[scope];
            }
            tip("Which objects a click gives the material: the active object, every selected object, or every object"
                    + " of " + EditingSession.shortName(actor.meshSourcePath())
                    + " in the loaded maps. One Undo step either way.");
            GeodataWidgets.space(GeodataWidgets.ROW);
        }
        GeodataWidgets.searchField(
                "##pickerSearch",
                "Search materials",
                pickerFilter,
                ImGui.getContentRegionAvailX(),
                ImGui.isWindowAppearing());
        GeodataWidgets.space(GeodataWidgets.ROW);
        var filter = pickerFilter.get().strip().toLowerCase(Locale.ROOT);
        var footer = BUTTON_HEIGHT + GeodataWidgets.ROW + ImGui.getStyle().getItemSpacingY();
        ImGui.beginChild("##pickerRows", 0, -footer, false);
        hoveredKey = null;
        hoveredChoice = null;
        var defaultLabel = forSurfaces ? "Client material" : "Mesh default";
        if (filter.isEmpty() || defaultLabel.toLowerCase(Locale.ROOT).contains(filter)) {
            pickerRow(
                    "default",
                    forSurfaces ? swatchOf(slot.meshMaterial()) : 0,
                    Glyph.RELOAD,
                    defaultLabel,
                    slot.meshMaterial().isBlank()
                            ? forSurfaces ? "The material each surface has in the client" : "The mesh's own material"
                            : EditingSession.shortName(slot.meshMaterial()),
                    null,
                    slot.override() == null);
        }
        var inMap = new ArrayList<MaterialReference>();
        for (var material :
                session.getMaterials(forSurfaces ? selectedSurfaces.getFirst().path() : primary)) {
            if (filter.isEmpty()
                    || material.sourcePath().toLowerCase(Locale.ROOT).contains(filter)) {
                inMap.add(material);
            }
        }
        GeodataWidgets.section("IN THIS MAP · " + inMap.size());
        for (var material : inMap) {
            pickerRow(
                    "map/" + material.sourcePath(),
                    swatchOf(material.sourcePath()),
                    Glyph.LAYERS,
                    EditingSession.shortName(material.sourcePath()),
                    mapOf(material.sourcePath()) + " · " + material.className(),
                    new MaterialChoice(material.sourcePath(), null),
                    material.sourcePath().equalsIgnoreCase(slot.override()));
        }
        if (library != null) {
            var all = library.getMaterials();
            if (all != pickerSource || !filter.equals(pickerFiltered)) {
                pickerSource = all;
                pickerFiltered = filter;
                var shown = new ArrayList<MaterialEntry>();
                for (var material : all) {
                    if (filter.isEmpty()
                            || material.path().toLowerCase(Locale.ROOT).contains(filter)) {
                        shown.add(material);
                    }
                }
                pickerShown = shown;
            }
            GeodataWidgets.section("CLIENT · " + pickerShown.size());
            if (!library.areMaterialsReady()) {
                var progress = library.getMaterialProgress();
                GeodataWidgets.hint(
                        progress[1] == 0
                                ? "Looking for texture packages…"
                                : "Reading texture packages · " + progress[0] + " of " + progress[1]);
            }
            var rowHeight = Math.max(SLOT_SWATCH + 8, 2 * ImGui.getFontSize() + 12)
                    + ImGui.getStyle().getItemSpacingY();
            pickerClipper.begin(pickerShown.size(), rowHeight);
            while (pickerClipper.step()) {
                for (var i = pickerClipper.getDisplayStart(); i < pickerClipper.getDisplayEnd(); i++) {
                    var material = pickerShown.get(i);
                    pickerRow(
                            "client/" + material.path(),
                            library.getSwatch(material),
                            Glyph.LAYERS,
                            material.name(),
                            material.packageName() + " · " + material.className(),
                            new MaterialChoice(material.path(), material.packageFile()),
                            material.path().equalsIgnoreCase(slot.override()));
                }
            }
            pickerClipper.end();
        }
        if (browsedFile != null) {
            GeodataWidgets.section(
                    "FROM " + browsedFile.getFileName().toString().toUpperCase(Locale.ROOT));
            for (var material : browsedMaterials) {
                if (filter.isEmpty()
                        || material.sourcePath().toLowerCase(Locale.ROOT).contains(filter)) {
                    pickerRow(
                            "file/" + material.sourcePath(),
                            0,
                            Glyph.LAYERS,
                            EditingSession.shortName(material.sourcePath()),
                            material.className(),
                            new MaterialChoice(material.sourcePath(), browsedFile),
                            material.sourcePath().equalsIgnoreCase(slot.override()));
                }
            }
        }
        ImGui.endChild();
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (GeodataWidgets.rowButton("Browse a .utx file…", 0, 2)) {
            browsePackage();
        }
        tip("Read the materials of a texture package outside the client. Its package goes into the save.");
        ImGui.beginDisabled(!session.canImportTextures());
        if (GeodataWidgets.rowButton("Import an image…", 1, 2)) {
            chooseImage();
        }
        ImGui.endDisabled();
        tip(
                forSurfaces
                        ? "Make a texture from a PNG, JPEG, BMP or DDS file and give it to the chosen surfaces."
                                + " Dropping the file on the Material row does the same."
                        : "Make a texture from a PNG, JPEG, BMP or DDS file and give it to this slot. Dropping the"
                                + " file on the slot or on an object does the same.");
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
        updatePreview(primary);
    }

    private void pickerRow(
            String key,
            long texture,
            Glyph glyph,
            String title,
            String detail,
            @Nullable MaterialChoice choice,
            boolean current) {
        if (GeodataWidgets.thumbnailRow("picker/" + key, texture, glyph, title, detail, SLOT_SWATCH, current)) {
            commitMaterial(choice);
        }
        if (ImGui.isItemHovered()) {
            hoveredKey = key;
            hoveredChoice = choice;
            var surfaces = pickerSlot == SURFACE_TARGET;
            HudWidgets.showTooltip((choice == null
                            ? surfaces ? "The material in the client" : "The mesh's own material"
                            : choice.path())
                    + (surfaces
                            ? "\nPoint to see it on the surfaces, click to give it to them."
                            : "\nPoint to see it on the object, click to apply it."));
        }
    }

    private void updatePreview(@Nullable String primary) {
        var now = System.nanoTime();
        if (!Objects.equals(hoveredKey, hoverKey)) {
            hoverKey = hoveredKey;
            hoverChoice = hoveredChoice;
        }
        hoverOnce.point(hoveredKey, now);
        if (previewKey != null && !session.isPreviewingMaterial()) {
            previewKey = null;
            hoverOnce.again(now);
        }
        if (hoverKey == null) {
            if (previewKey != null) {
                previewKey = null;
                session.endMaterialPreview();
            }
            return;
        }
        if (pickerSlot >= 0 && hoverOnce.isDue(now, PREVIEW_NANOS)) {
            var slot = pickerSlot;
            var choice = hoverChoice;
            if (slot == SURFACE_TARGET) {
                var surfaces = selectedSurfaces.getList();
                run(() -> session.previewSurfaceMaterial(surfaces, choice));
            } else if (primary != null) {
                run(() -> session.previewMaterial(primary, slot, choice));
            }
            previewKey = session.isPreviewingMaterial() ? hoverKey : null;
        }
    }

    private void commitMaterial(@Nullable MaterialChoice choice) {
        var slot = pickerSlot;
        previewKey = null;
        if (slot == SURFACE_TARGET) {
            var surfaces = selectedSurfaces.getList();
            run(() -> {
                if (session.setSurfaceMaterials(surfaces, choice, surfaceMaterialLabel(surfaces)) == 0) {
                    showError("No chosen surface could take this material.");
                }
            });
        } else {
            var actors = scopeActors(pickerScope);
            run(() -> session.setMaterials(actors, slot, choice, materialLabel(actors)));
        }
        closePicker();
        ImGui.closeCurrentPopup();
    }

    private List<String> scopeActors(Scope scope) {
        var primary = session.getPrimary();
        if (primary == null) {
            return List.of();
        }
        return switch (scope) {
            case ACTOR -> List.of(primary);
            case SELECTION -> List.copyOf(session.getSelection());
            case MESH -> sameMeshActors(primary, session.getActor(primary).meshSourcePath());
        };
    }

    private List<String> sameMeshActors(String primary, String mesh) {
        var result = new ArrayList<String>();
        result.add(primary);
        if (viewport != null && !mesh.isBlank()) {
            for (var actor : viewport.findActorsWithMesh(mesh)) {
                if (!actor.equals(primary) && !session.getHiddenActors().contains(actor)) {
                    result.add(actor);
                }
            }
        }
        return result;
    }

    private void browsePackage() {
        ImGui.closeCurrentPopup();
        reopenPicker = pickerSlot;
        if (NativeDialogs.isAvailable()) {
            var chosen = NativeDialogs.chooseFile(
                    "Choose a texture package", null, List.of("*.utx"), "Texture packages (*.utx)");
            if (chosen.outcome() == Outcome.CHOSEN) {
                readBrowsed(chosen.path());
            }
            return;
        }
        packagePicker.openPath("");
    }

    private void readBrowsed(Path file) {
        run(() -> {
            try {
                browsedMaterials = PackageEditDocument.inspectMaterials(file);
                browsedFile = file;
            } catch (IOException failure) {
                throw new IllegalArgumentException(failure.getMessage(), failure);
            }
        });
    }

    private void chooseImage() {
        var slot = pickerSlot;
        var actors = slot == SURFACE_TARGET ? List.<String>of() : scopeActors(pickerScope);
        ImGui.closeCurrentPopup();
        closePicker();
        if (NativeDialogs.isAvailable()) {
            var chosen = NativeDialogs.chooseFile("Choose an image", null, ImageImportDialog.PATTERNS, "Images");
            if (chosen.outcome() == Outcome.CHOSEN) {
                openImport(chosen.path(), actors, slot);
            }
            return;
        }
        imageActors = actors;
        imageSlot = slot;
        imagePicker.openPath("");
    }

    private void drawImportDialog() {
        var request = importDialog.draw();
        if (request == null) {
            return;
        }
        run(() -> {
            var material = session.importImage(request.image(), request.packageName(), request.textureName());
            if (request.slot() == SURFACE_TARGET) {
                session.setSurfaceMaterials(imageSurfaces, material, surfaceMaterialLabel(imageSurfaces));
            } else {
                session.setMaterials(request.actors(), request.slot(), material, materialLabel(request.actors()));
            }
            note("Imported " + request.textureName() + " into " + request.packageName() + ".", true);
        });
    }

    private void drawFilePickers() {
        var packageFile = packagePicker.draw();
        if (packageFile != null) {
            readBrowsed(packageFile);
        }
        var image = imagePicker.draw();
        if (image != null) {
            openImport(image, imageActors, imageSlot);
        }
        if (reopenPicker >= 0 && !ImGui.isPopupOpen("", ImGuiPopupFlags.AnyPopupId | ImGuiPopupFlags.AnyPopupLevel)) {
            var slot = reopenPicker;
            reopenPicker = -1;
            if (slot == SURFACE_TARGET ? !selectedSurfaces.isEmpty() : session.getPrimary() != null) {
                openPicker(slot, pickerX, pickerY);
            }
        }
    }

    private List<String> floatingMeshes() {
        if (viewport == null || !terrainCapability.isEditorOpen()) {
            return List.of();
        }
        var heights = terrainCapability.getHeightRevision();
        var actors = viewport.getActorsVersion() * 31 + session.getRevision();
        if (heights == floatingHeights && actors == floatingActors && !floatingPending) {
            return floating;
        }
        floatingHeights = heights;
        floatingActors = actors;
        floatingPending = false;
        var result = new ArrayList<String>();
        for (var path : viewport.findActorsIn(terrainCapability.getChangedSectors())) {
            var shift = groundShift(path);
            if (shift == null) {
                continue;
            }
            if (Float.isNaN(shift)) {
                floatingPending = true;
            } else if (Math.abs(shift) > EditingCheck.TOLERANCE) {
                result.add(path);
            }
        }
        floating = List.copyOf(result);
        return floating;
    }

    private @Nullable Float groundShift(String path) {
        var bounds = viewport == null ? null : viewport.getActorBounds(path);
        ActorRecord actor;
        try {
            actor = session.getActor(path);
        } catch (RuntimeException unknown) {
            return null;
        }
        var source = session.getSourceTransform(path);
        if (bounds == null || source == null || actor.created()) {
            return null;
        }
        var location = actor.transform().location();
        var dx = location.x() - source.location().x();
        var dy = location.y() - source.location().y();
        var dz = location.z() - source.location().z();
        var cx = (bounds.minX() + bounds.maxX()) * .5f;
        var cy = (bounds.minY() + bounds.maxY()) * .5f;
        var now = probe.getCurrent(cx, cy);
        var then = probe.getSaved(cx - dx, cy - dy);
        if (now == null || then == null) {
            return probe.isReady(cx, cy) && probe.isReady(cx - dx, cy - dy) ? null : Float.NaN;
        }
        return bounds.minZ() - now - (bounds.minZ() - dz - then);
    }

    private void drawFloatingCallout() {
        var meshes = floatingMeshes();
        if (meshes.isEmpty()) {
            return;
        }
        GeodataWidgets.space(GeodataWidgets.SECTION);
        var title = EditorUi.plural(meshes.size(), "mesh may float or sink", "meshes may float or sink");
        if (GeodataWidgets.callout(
                "editingFloatingShow", Glyph.TERRAIN, title, "The ground moved under them", "Show")) {
            run(() -> session.selectMany(meshes, false));
            requestFocus();
        }
        tip("Meshes over the changed ground whose bottom is now more than 8 units farther above or below the"
                + " Terrain than in the client. The viewport outlines them. Show selects them.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (GeodataWidgets.actionButton("editingFloatingDrop", Glyph.PLACE, "Drop them to the ground", -1, true)) {
            dropFloating(meshes);
        }
        tip("Move each of them up or down by as much as the ground moved under it, so it stands on the Terrain as"
                + " it did in the client, as one Undo step.");
    }

    private void dropFloating(List<String> meshes) {
        var values = new LinkedHashMap<String, ActorTransform>();
        for (var path : meshes) {
            var shift = groundShift(path);
            if (shift == null || Float.isNaN(shift)) {
                continue;
            }
            var t = session.getActor(path).transform();
            var p = t.location();
            values.put(
                    path,
                    new ActorTransform(
                            new Vector3(p.x(), p.y(), p.z() - shift),
                            t.rotation(),
                            t.drawScale(),
                            t.drawScale3d(),
                            t.prePivot()));
        }
        if (values.isEmpty()) {
            return;
        }
        var label = "Drop "
                + (values.size() == 1
                        ? EditingSession.shortName(values.keySet().iterator().next())
                        : values.size() + " meshes")
                + " to the ground";
        move(() -> session.transformActors(values, label), null);
    }

    private void drawChanges() {
        EditorUi.section("Changes");
        tip("Every save holds all changes from the original client, also those saved before.");
        hoveredChange = null;
        var actors = session.getChanges();
        var terrain = terrainCapability.getTerrainChanges();
        var total = actors.size() + terrain.size();
        if (total == 0) {
            EditorUi.muted(
                    session.isDirty()
                            ? "Only added packages, no object or Terrain change."
                            : "No map has changes yet.");
            return;
        }
        var range = changePages.drawPager("editingChangePages", total, "change", "changes");
        for (var index = range[0]; index < range[1]; index++) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            var top = ImGui.getCursorScreenPosY();
            if (index < actors.size()) {
                var change = actors.get(index);
                var path = change.path();
                var pressed = FindingWidgets.card(
                        "editingChange" + index,
                        Severity.WARNING,
                        change.title(),
                        change.detail(),
                        List.of("Revert"),
                        session.getSelection().contains(path));
                if (ImGui.isMouseHoveringRect(
                        ImGui.getWindowPosX(),
                        top,
                        ImGui.getWindowPosX() + ImGui.getWindowWidth(),
                        ImGui.getCursorScreenPosY())) {
                    hoveredChange = path;
                }
                if (pressed == FindingWidgets.FLY) {
                    var location = change.location();
                    if (!change.kinds().contains(ChangeKind.DELETED)) {
                        run(() -> session.select(path, false));
                    }
                    flyTo(location.x(), location.y(), location.z(), 256);
                } else if (pressed == 0) {
                    revertChange(change);
                }
            } else {
                var row = terrain.get(index - actors.size());
                var pressed = FindingWidgets.card(
                        "editingChange" + index, Severity.WARNING, row.title(), row.detail(), List.of("Revert"), false);
                if (pressed == FindingWidgets.FLY) {
                    var rect = row.rect();
                    var cx = (rect[0] + rect[2]) * .5f;
                    var cy = (rect[1] + rect[3]) * .5f;
                    var z = probe.getCurrent(cx, cy);
                    flyTo(
                            cx,
                            cy,
                            z == null ? 0 : z,
                            Math.max(512, Math.max(rect[2] - rect[0], rect[3] - rect[1]) * .5f));
                } else if (pressed == 0 && terrainCapability.canRevert()) {
                    terrainCapability.revert(row);
                }
            }
        }
    }

    private void startCheck() {
        if (checkRun != null && !checkRun.isDone()) {
            return;
        }
        var actors = new ArrayList<Actor>();
        var points = new ArrayList<float[]>();
        var changedMaps = new TreeSet<String>();
        var changes = new HashMap<String, Change>();
        var surfaces = new ArrayList<Surface>();
        for (var change : session.getChanges()) {
            if (change.kinds().contains(ChangeKind.SURFACE)) {
                var location = change.location();
                surfaces.add(EditingCheck.surfaceOf(change, resting(change)));
                if (change.kinds().contains(ChangeKind.BRUSH)) {
                    changedMaps.add(change.map());
                }
                continue;
            }
            changes.put(change.path(), change);
            changedMaps.add(change.map());
            var location = change.location();
            points.add(new float[] {location.x(), location.y(), location.z()});
            if (change.source() != null) {
                points.add(new float[] {
                    change.source().x(), change.source().y(), change.source().z()
                });
            }
        }
        if (viewport != null) {
            for (var path : viewport.getSceneActors()) {
                var bounds = viewport.getActorBounds(path);
                ActorRecord actor;
                try {
                    actor = session.getActor(path);
                } catch (RuntimeException unknown) {
                    continue;
                }
                if (bounds == null) {
                    continue;
                }
                var location = actor.transform().location();
                var source = session.getSourceTransform(path);
                var change = changes.get(path);
                String outside = null;
                try {
                    outside = change == null ? null : session.getTileOutsideMap(path);
                } catch (RuntimeException unknown) {
                }
                var moved = change != null
                        && (change.kinds().contains(ChangeKind.MOVED)
                                || change.kinds().contains(ChangeKind.ROTATED)
                                || change.kinds().contains(ChangeKind.SCALED));
                actors.add(new Actor(
                        path,
                        viewport.getActorMesh(path),
                        new float[] {
                            bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ()
                        },
                        location.x(),
                        location.y(),
                        location.z(),
                        source == null
                                ? null
                                : new float[] {
                                    source.location().x(),
                                    source.location().y(),
                                    source.location().z()
                                },
                        actor.created(),
                        change != null,
                        moved,
                        outside));
            }
        }
        var rects = terrainCapability.getChangedSectors();
        changedMaps.addAll(terrainCapability.getChangedMaps());
        var problems = List.<String>of();
        List<SpawnSpot> spots = null;
        if (spawnSource != null && (!points.isEmpty() || !rects.isEmpty())) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (var point : points) {
                minX = Math.min(minX, point[0]);
                minY = Math.min(minY, point[1]);
                maxX = Math.max(maxX, point[0]);
                maxY = Math.max(maxY, point[1]);
            }
            for (var rect : rects) {
                minX = Math.min(minX, rect[0]);
                minY = Math.min(minY, rect[1]);
                maxX = Math.max(maxX, rect[2]);
                maxY = Math.max(maxY, rect[3]);
            }
            spots = spawnSource.getSpots(
                    minX - EditingCheck.REACH,
                    minY - EditingCheck.REACH,
                    maxX + EditingCheck.REACH,
                    maxY + EditingCheck.REACH);
        }
        var geodata = new HashMap<String, Long>();
        var geodataLoaded = geodataSource != null && geodataSource.getRegionModified("") >= 0;
        if (geodataLoaded) {
            for (var map : changedMaps) {
                geodata.put(map, geodataSource.getRegionModified(map));
            }
        }
        var input = new Input(
                List.copyOf(actors),
                List.copyOf(points),
                List.copyOf(rects),
                probe.snapshot(),
                problems,
                spots == null ? null : List.copyOf(spots),
                Map.copyOf(geodata),
                geodataLoaded,
                firstEdit,
                session.getClientRoot(),
                List.copyOf(surfaces));
        checkCancelled = new AtomicBoolean();
        checkRun = check.start(input, checkCancelled);
        fixedFindings.clear();
        selectedFinding = null;
    }

    private void drawFindings() {
        findingsTop = ImGui.getCursorPosY();
        EditorUi.section("Findings");
        if (checkRun != null && checkRun.isDone()) {
            try {
                checkResult = checkRun.join();
                findingList.reset(List.of());
            } catch (RuntimeException failure) {
                error = "The check failed: " + failure.getMessage();
            }
            checkRun = null;
        }
        var shown = new ArrayList<Finding>();
        exportProblems.clear();
        if (exportPlan != null && exports.isCurrent()) {
            var changes = session.getChanges();
            var place = changes.isEmpty() ? null : changes.getFirst().location();
            for (var problem : exportPlan.getErrors()) {
                var finding = EditingCheck.exportFinding(
                        problem,
                        place == null ? 0 : place.x(),
                        place == null ? 0 : place.y(),
                        place == null ? 0 : place.z());
                shown.add(finding);
                exportProblems.put(finding, problem);
            }
        }
        var result = checkResult;
        if (result != null) {
            for (var finding : result.findings()) {
                if (!fixedFindings.contains(finding)) {
                    shown.add(finding);
                }
            }
        }
        if (checkRun != null) {
            var cardTop = ImGui.getCursorScreenPosY();
            if (GeodataWidgets.progress("editingCheckBusy", "Checking…", "", -1, "Cancel")) {
                checkCancelled.set(true);
            }
            if (ImGui.isMouseHoveringRect(
                    ImGui.getWindowPosX(),
                    cardTop,
                    ImGui.getWindowPosX() + ImGui.getWindowWidth(),
                    ImGui.getCursorScreenPosY())) {
                HudWidgets.showTooltip("Checking the loaded maps: reading the Terrain under the objects.");
            }
        } else if (result == null && shown.isEmpty()) {
            GeodataWidgets.hint(
                    "Looks for meshes that float or sink near an edit, objects outside their map, copies in one place,"
                            + " problems that block the save, NPC Spawns in meshes and old geodata.");
        } else if (shown.isEmpty()) {
            EditorUi.message(Tone.SUCCESS, "Nothing found. The changes look right.");
        }
        if (!shown.isEmpty()) {
            if (checkRun != null) {
                GeodataWidgets.space(GeodataWidgets.ROW);
            }
            drawFindingCards(shown);
        }
        if (result != null && checkRun == null) {
            for (var note : result.notes()) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                GeodataWidgets.note(note, GeodataWidgets.FAINT);
                tip("The Check looks at NPC Spawns while a file is open in NPC Spawns, and at geodata while the"
                        + " Geodata tool has a folder open.");
            }
        }
        if (checkRun != null) {
            return;
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (GeodataWidgets.actionButton(
                "editingRunCheck", Glyph.SHIELD, result == null ? "Check" : "Check again", -1, false)) {
            checkAgain();
        }
        tip("Check every reference and the lighting of the changes as they are now, and look at the loaded maps"
                + " for what they may have left wrong. Nothing is changed or saved.");
    }

    private void checkAgain() {
        if (!exports.isBusy() && exports.hasExportableChanges()) {
            startValidation();
        }
        startCheck();
    }

    private void drawFindingCards(List<Finding> shown) {
        var groups = new ArrayList<Group>();
        for (var kind : Kind.values()) {
            var count = (int)
                    shown.stream().filter(finding -> finding.kind() == kind).count();
            if (count > 0) {
                groups.add(new Group(kind.name(), kind.title, kind.severity, count, kind.help));
            }
        }
        findingList.groupCheckboxes("editingFindingGroups", groups);
        var visible = shown.stream()
                .filter(finding -> findingList.isShown(finding.kind().name()))
                .toList();
        GeodataWidgets.space(GeodataWidgets.ROW);
        var range = findingList.drawPager("editingFindingPages", visible.size());
        for (var index = range[0]; index < range[1]; index++) {
            var finding = visible.get(index);
            GeodataWidgets.space(GeodataWidgets.ROW);
            var fixes = fixesOf(finding);
            var top = ImGui.getCursorScreenPosY();
            var raw = exportProblems.get(finding);
            var pressed = FindingWidgets.card(
                    "editingFinding" + index,
                    finding.kind().severity,
                    finding.title(),
                    finding.detail(),
                    fixes,
                    finding == selectedFinding,
                    raw == null);
            if (raw != null
                    && !raw.equals(finding.detail())
                    && ImGui.isMouseHoveringRect(
                            ImGui.getWindowPosX(),
                            top,
                            ImGui.getWindowPosX() + ImGui.getWindowWidth(),
                            ImGui.getCursorScreenPosY())) {
                HudWidgets.showTooltip("The check says: " + raw);
            }
            if (pressed == FindingWidgets.FLY) {
                selectedFinding = finding;
                if (!finding.actor().isEmpty()) {
                    run(() -> session.select(finding.actor(), false));
                }
                flyTo(finding.x(), finding.y(), finding.z(), 384);
            } else if (pressed == 0) {
                fix(List.of(finding));
            }
        }
        for (var kind : Kind.values()) {
            if (kind.fix.isEmpty() || !findingList.isShown(kind.name())) {
                continue;
            }
            var fixable = visible.stream()
                    .filter(finding ->
                            finding.kind() == kind && !fixesOf(finding).isEmpty())
                    .toList();
            if (fixable.size() < 2) {
                continue;
            }
            GeodataWidgets.space(GeodataWidgets.ROW);
            if (GeodataWidgets.actionButton(
                    "editingFixAll" + kind.name(), null, "Fix all " + fixable.size() + " · " + kind.fix, -1, false)) {
                fix(fixable);
            }
            tip(kind.fix + " for every finding of \"" + kind.title + "\", as one Undo step.");
        }
    }

    private void fix(List<Finding> findings) {
        if (findings.isEmpty() || EditingCheck.fixOf(findings.getFirst()).isEmpty()) {
            return;
        }
        run(() -> EditingCheck.fix(session, findings, EditingCheck.fixLabel(findings), this::moveToTheirTiles));
        if (error.isEmpty()) {
            fixedFindings.addAll(findings);
        }
    }

    private void undo() {
        pendingHistory = 0;
        run(timeline::undo);
    }

    private void redo() {
        pendingHistory = 0;
        run(timeline::redo);
    }

    private void stepThroughHistory() {
        while (pendingHistory != 0 && !dragging && !terrainCapability.isStroking()) {
            var redo = pendingHistory > 0;
            if (redo ? !timeline.canRedo() : !timeline.canUndo()) {
                if ((redo ? timeline.nextRedo() : timeline.nextUndo()) == null) {
                    pendingHistory = 0;
                }
                return;
            }
            try {
                if ((redo ? timeline.redo() : timeline.undo()) == null) {
                    return;
                }
            } catch (RuntimeException failure) {
                pendingHistory = 0;
                showError(failure.getMessage());
                return;
            }
            pendingHistory += redo ? -1 : 1;
        }
    }

    private void drawHistory(float right, float bottom) {
        var rows = timeline.getHistory(HISTORY_ROWS);
        var shown = Math.min(rows.size(), HISTORY_SHOWN);
        var height = HudWidgets.menuHeight(Math.max(1, shown), 0);
        ImGui.setNextWindowSizeConstraints(300, 0, 300, height);
        if (!HudWidgets.beginMenu(HISTORY_MENU, 300, right, bottom)) {
            return;
        }
        closeOnEscape();
        if (rows.isEmpty()) {
            HudWidgets.menuRow("historyEmpty", Glyph.UNDO, "No steps yet", "", false);
            GeodataWidgets.tip("Each change of objects, lights, surfaces and the Terrain is a step here.");
        }
        for (var index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            var glyph = row.current() ? Glyph.CHECK : row.redo() ? Glyph.REDO : Glyph.UNDO;
            var key = row.current() ? "Now" : row.redo() ? "Redo" : "";
            if (row.current() && ImGui.isWindowAppearing()) {
                ImGui.setScrollHereY(.5f);
            }
            if (HudWidgets.menuRow("historyRow" + index, glyph, row.label(), key) && row.steps() != 0) {
                goToStep(row.steps());
            }
            var first = row.label().equals(EditingTimeline.BEFORE_FIRST);
            GeodataWidgets.tip(
                    row.current()
                            ? first
                                    ? "Nothing in the list is done now."
                                    : "The scene shows the result of this step now."
                            : row.redo()
                                    ? "Redo up to "
                                            + row.label()
                                            + ": "
                                            + EditorUi.plural(row.steps(), "step", "steps")
                                            + "."
                                    : first
                                            ? "Undo every step of the list: "
                                                    + EditorUi.plural(-row.steps(), "step", "steps") + "."
                                            : "Undo back to "
                                                    + row.label()
                                                    + ": "
                                                    + EditorUi.plural(-row.steps(), "step", "steps")
                                                    + ".");
        }
        HudWidgets.endMenu();
    }

    private float footerHeight(int changes) {
        if (reviewOpen) {
            return GeodataWidgets.SAVE_BAR;
        }
        return changes > 0 || exports.isBusy() ? GeodataWidgets.SAVE_BAR : ImGui.getFontSize() + GeodataWidgets.BLOCK;
    }

    private void drawFooter(int changes) {
        if (exports.isBusy()) {
            if (GeodataWidgets.busyBar(
                    "editingBusyBar", exports.isSaving() ? "Saving…" : "Checking…", -1, "Cancel", true)) {
                exports.cancel();
            }
            tip((exports.isSaving()
                            ? "Saving the checked maps to the folder. "
                            : "Checking the references and the lighting of the changes. ")
                    + "Cancel stops it: nothing is saved, and the changes stay.");
            return;
        }
        if (reviewOpen) {
            drawReviewFooter();
            return;
        }
        if (changes > 0) {
            if (GeodataWidgets.saveBar(
                    "editingSaveBar", EditorUi.plural(changes, "change", "changes") + " not saved", "Save…", true)) {
                openReview(false);
            }
            tip(changesTip() + " Save opens the review: the folder, the changes, the check and Save (" + COMMAND_KEY
                    + "+S).");
            return;
        }
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY() + GeodataWidgets.BLOCK * .5f;
        var draw = ImGui.getWindowDrawList();
        draw.addCircleFilled(x + 4, HudWidgets.textMiddle(y), 3, GeodataWidgets.OPEN);
        draw.addText(x + 14, y, GeodataWidgets.FAINT, "All saved");
        ImGui.dummy(ImGui.getContentRegionAvailX(), ImGui.getFontSize() + GeodataWidgets.BLOCK * .5f);
        tip("Every change is saved. New edits are kept in a recovery draft until you save them.");
    }

    private void drawReviewFooter() {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        ImGui.getWindowDrawList().addLine(x, y, x + width, y, GeodataWidgets.DIVIDER, 1);
        ImGui.setCursorScreenPos(x, y + GeodataWidgets.SAVE_BAR - GeodataWidgets.FOOTER);
        var state = reviewState();
        var limits =
                "Static package references and native lighting rules are checked. Script lookups at run time and the"
                        + " game client itself are not tested.";
        var pressed = GeodataWidgets.footer(
                state.status(),
                state.shortStatus(),
                state.color(),
                state.more().isEmpty() ? limits : state.more() + "\n\n" + limits,
                List.of(
                        new FooterButton(
                                "editingReviewBack",
                                null,
                                "Back",
                                64,
                                false,
                                true,
                                "Back to the tools (Esc). A check or a save goes on in the bar at the bottom."),
                        new FooterButton(
                                "editingWrite",
                                null,
                                "Save",
                                76,
                                true,
                                canWrite(),
                                canWrite()
                                        ? "Save the checked maps to the folder (" + COMMAND_KEY + "+S)."
                                        : "Save needs a folder and a check that passed for the current changes.")));
        if (pressed == 0) {
            reviewOpen = false;
        } else if (pressed == 1) {
            write();
        }
    }

    private ReviewState reviewState() {
        var said = exports.getStatus();
        if (exports.hasPublished() && exports.getLastOutput() != null) {
            var saved = said.endsWith(".") ? said.substring(0, said.length() - 1) : said;
            var count = saved.contains(" to ") ? saved.substring(0, saved.indexOf(" to ")) : "Saved";
            return new ReviewState(saved, count, GeodataWidgets.OPEN, "Saved to " + exports.getLastOutput() + ".");
        }
        var end = said.indexOf(". ");
        var sentence =
                end >= 0 ? said.substring(0, end) : said.endsWith(".") ? said.substring(0, said.length() - 1) : said;
        if (!exports.getError().isEmpty()) {
            return new ReviewState(
                    sentence.isEmpty() ? "Failed" : sentence,
                    "Failed",
                    GeodataWidgets.BLOCKED,
                    said + "\n\n" + exports.getError());
        }
        if (exportPlan != null && exports.isCurrent()) {
            if (exportPlan.isValid()) {
                var ready = readyToSave(exportPlan.getPackages());
                return new ReviewState(
                        ready,
                        ready.replace(" to save", ""),
                        GeodataWidgets.OPEN,
                        packagesTip(exportPlan.getPackages()));
            }
            var count = exportPlan.getErrors().size();
            return new ReviewState(
                    EditorUi.plural(count, "problem blocks the save", "problems block the save"),
                    EditorUi.plural(count, "problem", "problems"),
                    GeodataWidgets.BLOCKED,
                    "They are listed under Findings.");
        }
        if (!exports.hasExportableChanges()) {
            return new ReviewState("Nothing to save yet", "Nothing to save", GeodataWidgets.MUTED, "");
        }
        if (exportPlan != null) {
            return new ReviewState(
                    "Check again: the changes moved on",
                    "Check again",
                    GeodataWidgets.WARNING,
                    "The changes moved on since the check. Check again under Findings.");
        }
        if (!sentence.isEmpty()) {
            return new ReviewState(sentence, "Not checked", GeodataWidgets.WARNING, "Check again under Findings.");
        }
        return new ReviewState("Checking…", "Checking…", GeodataWidgets.WARNING, "");
    }

    private record ReviewState(String status, String shortStatus, int color, String more) {}

    private String changesTip() {
        var parts = new ArrayList<String>();
        var maps = session.getOpenedMaps().stream()
                .filter(session::willExportMap)
                .sorted()
                .toList();
        if (!maps.isEmpty()) {
            parts.add("Object changes in " + String.join(", ", maps));
        }
        if (terrainCapability.isTerrainDirty()) {
            parts.add("Terrain strokes on " + String.join(", ", terrainCapability.getDirtyOwners()));
        }
        return (parts.isEmpty() ? "Changes" : String.join(" and ", parts))
                + " are not saved yet. The recovery draft keeps them if the application closes.";
    }

    private void openReview(boolean pickFolder) {
        if (!reviewOpen) {
            reviewOpen = true;
            scrollBodyToTop = true;
            revealWritten = false;
            freshOutput();
            if (exportSettings != null) {
                exportSettings.refresh();
                installAfterWrite = exportSettings.isInstallAfterWrite();
            }
            if (!exports.isBusy() && exports.hasExportableChanges() && !(exportPlan != null && exports.isCurrent())) {
                startValidation();
            }
            startCheck();
        }
        if (pickFolder) {
            outputPicker.open(parentOf(output.get()));
        }
    }

    private void drawReview() {
        EditorUi.firstSection("Save to");
        if (GeodataWidgets.folderField(
                "editingOutput",
                "",
                output.get(),
                "A new folder outside the game client. Save puts Maps, Textures and StaticMeshes folders and a"
                    + " dependency manifest there. It never changes the client: installing is a separate choice below."
                    + " A click chooses the folder it goes into, where a path can also be typed.")) {
            outputPicker.open(parentOf(output.get()));
        }
        var parent = outputPicker.draw();
        if (parent != null) {
            output.set(parent.resolve("Cruma export " + stamp()).toString());
        }
        drawChanges();
        drawCheck();
        drawFindings();
        EditorUi.section("Install");
        if (ImGui.checkbox("Also install into the client after Save##editingInstallAfter", installAfterWrite)) {
            installAfterWrite = !installAfterWrite;
            if (exportSettings != null) {
                exportSettings.installAfterWrite(installAfterWrite);
            }
        }
        tip("After Save, ask to copy the saved packages into a client folder. The client files it replaces are"
                + " kept in the saved folder, and Restore client puts them back. The next save starts with this"
                + " choice.");
        if (installAfterWrite) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            install.drawClientField(session.getClientRoot());
        }
        GeodataWidgets.space(GeodataWidgets.SECTION);
        if (header("Advanced")) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            drawLightingOptions();
            GeodataWidgets.space(GeodataWidgets.ROW);
            fileField(
                    "editingDependency",
                    "Extra .utx, .usx or .u package",
                    dependencyPath,
                    "Supply a package that is referenced by your changes but missing from the client, for example a"
                            + " custom texture library.",
                    "Choose a dependency package",
                    List.of("*.utx", "*.usx", "*.u", "*.ukx", "*.uax"),
                    "Unreal packages");
            GeodataWidgets.space(GeodataWidgets.ROW);
            ImGui.beginDisabled(dependencyPath.get().isBlank());
            if (GeodataWidgets.actionButton("editingAddDependency", Glyph.PLUS, "Add dependency package", -1, false)) {
                run(() -> session.addDependency(Path.of(dependencyPath.get())));
            }
            ImGui.endDisabled();
        }
        drawWritten();
    }

    private void drawCheck() {
        EditorUi.section("Check");
        if (exports.isBusy()) {
            EditorUi.muted(exports.getStatus());
            return;
        }
        if (exportPlan != null && exports.isCurrent()) {
            drawPlan();
            return;
        }
        if (!exports.getError().isEmpty()) {
            EditorUi.message(Tone.ERROR, exports.getError());
        } else if (exportPlan != null) {
            EditorUi.muted("The changes moved on since the check. Check again under Findings.");
        } else if (exports.hasPublished()) {
            EditorUi.muted("The changes are saved. Check again to save them into another folder.");
        } else if (!exports.getStatus().isEmpty()) {
            EditorUi.muted(exports.getStatus());
        }
        if (!exports.hasExportableChanges()) {
            EditorUi.muted("Nothing to check yet.");
        }
    }

    private void drawLightingOptions() {
        var on = (session.isRebuildAllLighting() ? 1 : 0)
                + (session.isRebuildAllBspLightmaps() ? 1 : 0)
                + (session.isRebuildAllTerrainLighting() ? 1 : 0)
                + (session.isKeepNeighborBeast() ? 1 : 0);
        var open = header("Lighting options" + (on == 0 ? "" : " (" + on + " on)") + "###editingLightingOptions");
        tip("Save always rebuilds the lighting that an edit can change. These options rebuild whole maps instead,"
                + " as a full lighting build in the game editor does.");
        if (!open) {
            return;
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.beginDisabled(exports.isBusy());
        if (ImGui.checkbox("Every actor##editingAllLighting", session.isRebuildAllLighting())) {
            run(() -> session.rebuildAllLighting(!session.isRebuildAllLighting()));
        }
        tip("Rebuild the baked lighting of every static mesh of each edited map, not only of moved, new and copied"
                + " actors and the actors near them. It takes longer.");
        if (ImGui.checkbox("Every BSP lightmap##editingAllBspLightmaps", session.isRebuildAllBspLightmaps())) {
            run(() -> session.rebuildAllBspLightmaps(!session.isRebuildAllBspLightmaps()));
        }
        tip("Rebuild every BSP lightmap of each edited map, not only those of walls and floors that a moved, new"
                + " or deleted actor or an edited Terrain can shadow. Lightmaps then follow Cruma's rebuild"
                + " everywhere.");
        if (ImGui.checkbox("All Terrain lighting##editingAllTerrainLighting", session.isRebuildAllTerrainLighting())) {
            run(() -> session.rebuildAllTerrainLighting(!session.isRebuildAllTerrainLighting()));
        }
        tip("Rebuild every Terrain vertex of each edited map, not only sculpted ground and the ground that a"
                + " moved, new or deleted actor can shadow. The Terrain lighting then follows Cruma's rebuild"
                + " everywhere.");
        if (ImGui.checkbox("Keep the Beast bakes around the edit##editingKeepBeast", session.isKeepNeighborBeast())) {
            run(() -> session.keepNeighborBeast(!session.isKeepNeighborBeast()));
        }
        tip("Only for maps lit by Beast lightmaps (P152, High Five and Essence). Save gives edited actors and"
                + " sculpted Terrain an approximate Beast bake. Unless this is on, it also updates the texels of"
                + " nearby actors, BSP surfaces and other Terrain whose sun, sky or local light the edit changes."
                + " Every other texel keeps its saved value.");
        ImGui.endDisabled();
    }

    private void drawPlan() {
        var plan = exportPlan;
        if (plan.isValid()) {
            EditorUi.message(Tone.SUCCESS, readyToSave(plan.getPackages()));
        } else {
            EditorUi.message(
                    Tone.WARNING,
                    EditorUi.plural(plan.getErrors().size(), "problem blocks", "problems block") + " the save");
        }
        tip(packagesTip(plan.getPackages())
                + (plan.getPackages().isEmpty() ? "" : "\n\n")
                + EditorUi.plural(
                        plan.getRequirements().size(), "existing client package is", "existing client packages are")
                + " required but not copied.");
        for (var i = 0; i < plan.getLighting().size(); i++) {
            EditorUi.message(Tone.INFO, plan.getLighting().get(i));
            var lineTip =
                    i < plan.getLightingTips().size() ? plan.getLightingTips().get(i) : "";
            if (!lineTip.isEmpty()) {
                tip(lineTip);
            }
        }
        if (!plan.getPackages().isEmpty()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            var packages = plan.getPackages();
            var lines = Math.min(packages.size(), PLAN_LINES);
            for (var i = 0; i < lines; i++) {
                var item = packages.get(i);
                EditorUi.colored(HudWidgets.MUTED, item.path().toString());
                tip(packageTip(item));
            }
            if (lines < packages.size()) {
                EditorUi.colored(HudWidgets.MUTED, "and " + (packages.size() - lines) + " more");
                var rest = new StringBuilder();
                for (var item : packages.subList(lines, packages.size())) {
                    rest.append(rest.isEmpty() ? "" : "\n").append(item.path());
                }
                tip(rest.toString());
            }
        }
        for (var terrainReport : exports.getTerrainReports()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            var lines = TerrainReports.summaryLines(terrainReport);
            EditorUi.message(Tone.INFO, lines.getFirst());
            for (var line : lines.subList(1, lines.size())) {
                var beastLine = line.startsWith(TerrainReports.BEAST_LINE);
                EditorUi.message(line.startsWith("Saves") || beastLine ? Tone.INFO : Tone.WARNING, line);
                if (beastLine) {
                    tip(TerrainReports.beastTip(terrainReport));
                }
            }
        }
        var decorations = TerrainReports.decorationLines(exports.getTerrainDecorations());
        if (!decorations.isEmpty()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            for (var line : decorations) {
                EditorUi.message(line.startsWith("Also") ? Tone.WARNING : Tone.INFO, line);
            }
        }
    }

    private void drawWritten() {
        var written = exports.getLastOutput();
        if (written == null) {
            return;
        }
        EditorUi.section("Saved");
        if (exports.hasPublished()) {
            GeodataWidgets.writtenResult("editingExported", exports.getStatus(), GeodataWidgets.OPEN, written);
        } else {
            GeodataWidgets.writtenResult(
                    "editingExported", "The last save is in " + written.getFileName() + ".", HudWidgets.MUTED, written);
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        install.drawState(written, exports.isBusy());
        if (install.getChanges() != installChanges) {
            installChanges = install.getChanges();
            revealWritten = true;
        }
        var installed = install.isWorking() ? null : install.getInstalledIn();
        if (ClientInstallPanel.isEditedClient(installed, session.getClientRoot())) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            EditorUi.message(Tone.WARNING, "Installed into the client. Reload Edit to keep editing these maps.");
            GeodataWidgets.space(GeodataWidgets.ROW);
            if (GeodataWidgets.actionButton("editingReload", Glyph.RELOAD, "Reload", -1, false)) {
                reloadRequested = true;
            }
            tip("Load the area again from the client's new files, as entering Edit does. The camera stays where it"
                    + " is.");
        }
        if (revealWritten) {
            ImGui.setScrollHereY(1);
            revealWritten = install.isWorking();
        }
    }

    private boolean canWrite() {
        return !exports.isBusy()
                && exportPlan != null
                && exports.isCurrent()
                && exportPlan.isValid()
                && !output.get().isBlank();
    }

    private void startValidation() {
        run(() -> {
            try {
                exports.beginPrepare();
            } catch (IOException failure) {
                throw new IllegalArgumentException(failure.getMessage(), failure);
            }
        });
    }

    private void write() {
        if (!canWrite()) {
            return;
        }
        run(() -> {
            var target = Path.of(output.get().trim());
            try {
                exports.beginSave(target);
            } catch (IOException | RuntimeException failure) {
                throw new IllegalArgumentException(failure.getMessage(), failure);
            }
            installPending = installAfterWrite;
            rememberFolder(target.getParent());
        });
    }

    private void installWritten() {
        if (!installPending || exports.isBusy()) {
            return;
        }
        installPending = false;
        var written = exports.getLastOutput();
        if (exports.hasPublished() && written != null && exports.getError().isEmpty()) {
            install.askAfterWrite(written);
        }
    }

    private void freshOutput() {
        var text = output.get().trim();
        try {
            if (!text.isEmpty() && !Files.exists(Path.of(text))) {
                return;
            }
        } catch (InvalidPathException invalid) {
        }
        output.set(defaultOutput().toString());
    }

    private Path defaultOutput() {
        if (exportSettings != null) {
            exportSettings.refresh();
            var remembered = exportSettings.getFolder();
            if (!remembered.equals(ExportSettings.defaultFolder(home()))) {
                return remembered.resolve("Cruma export " + stamp());
            }
        }
        return editingExports().resolve(stamp());
    }

    private void rememberFolder(@Nullable Path parent) {
        if (exportSettings != null
                && parent != null
                && !parent.toAbsolutePath()
                        .normalize()
                        .equals(editingExports().toAbsolutePath().normalize())) {
            exportSettings.setFolder(parent);
        }
    }

    private String folderName() {
        try {
            var name = Path.of(output.get().trim()).getFileName();
            return name == null ? "the folder" : name.toString();
        } catch (InvalidPathException invalid) {
            return "the folder";
        }
    }

    private boolean fileField(
            String id,
            String label,
            ImString value,
            String help,
            String dialogTitle,
            List<String> patterns,
            String description) {
        ImGui.textColored(HudWidgets.MUTED, label);
        var field = GeodataWidgets.actionField(id, "Path", value, help, Glyph.FOLDER, "Browse for the file", true);
        var entered = ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter);
        if (field.action() && !entered) {
            if (NativeDialogs.isAvailable()) {
                var chosen = NativeDialogs.chooseFile(dialogTitle, parentOf(value.get()), patterns, description);
                if (chosen.outcome() == Outcome.CHOSEN) {
                    value.set(chosen.path().toString());
                    return true;
                }
            } else {
                filePickers
                        .computeIfAbsent(
                                id,
                                ignored -> FolderPicker.file(
                                        dialogTitle, "Pick the folder of the file, and its name below.", ""))
                        .openPath(value.get());
            }
        }
        var picker = filePickers.get(id);
        var picked = picker == null ? null : picker.draw();
        if (picked != null) {
            value.set(picked.toString());
            return true;
        }
        return field.action() && entered;
    }

    private boolean header(String label) {
        if (openHeaders) {
            ImGui.setNextItemOpen(true);
        }
        return GeodataWidgets.header(label);
    }

    private void drawRecoveryNote() {
        if (recoveryStatus.isBlank() && recoveryFailure.isBlank()) {
            return;
        }
        GeodataWidgets.space(GeodataWidgets.SECTION);
        HudWidgets.line(ImGui.getContentRegionAvailX());
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (recoveryFailure.isBlank()) {
            EditorUi.muted(recoveryStatus);
            tip("Cruma keeps a recovery draft of changes that are not saved. After a crash it offers to restore"
                    + " them, including Undo history.");
        } else {
            EditorUi.message(
                    Tone.ERROR, (recoveryStatus.isBlank() ? "Recovery" : recoveryStatus) + ": " + recoveryFailure);
        }
    }

    private boolean readTypedKeys() {
        if (ImGui.getIO().getWantTextInput()) {
            return false;
        }
        for (var digit = 0; digit < 10; digit++) {
            if (ImGui.isKeyPressed(DIGIT_KEYS[digit], true) || ImGui.isKeyPressed(KEYPAD_KEYS[digit], true)) {
                typed.type((char) ('0' + digit));
            }
        }
        if (ImGui.isKeyPressed(ImGuiKey.Minus, false) || ImGui.isKeyPressed(ImGuiKey.KeypadSubtract, false)) {
            typed.type('-');
        }
        if (ImGui.isKeyPressed(ImGuiKey.Period, false)
                || ImGui.isKeyPressed(ImGuiKey.Comma, false)
                || ImGui.isKeyPressed(ImGuiKey.KeypadDecimal, false)) {
            typed.type('.');
        }
        if (ImGui.isKeyPressed(ImGuiKey.Backspace, true)) {
            typed.backspace();
        }
        return (ImGui.isKeyPressed(ImGuiKey.Enter, false) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter, false))
                && !Double.isNaN(typed.getValue());
    }

    private TypedDrag.Kind typedKind() {
        return operation == Operation.ROTATE
                ? TypedDrag.Kind.ROTATE
                : operation == Operation.SCALE ? TypedDrag.Kind.SCALE : TypedDrag.Kind.MOVE;
    }

    private int axisUnderPointer() {
        var first = operation == Operation.ROTATE
                ? Operation.ROTATE_X
                : operation == Operation.SCALE ? Operation.SCALE_X : Operation.TRANSLATE_X;
        var found = -1;
        for (var axis = 0; axis < 3; axis++) {
            if (ImGuizmo.isOver(first << axis)) {
                if (found >= 0) {
                    return -1;
                }
                found = axis;
            }
        }
        return found;
    }

    private void drawTypedTag(Camera camera, float left, float top, float right, float bottom) {
        var io = ImGui.getIO();
        var at = EditorViewport.screenPoint(
                camera, gizmoStart[12], gizmoStart[13], gizmoStart[14], (int) io.getDisplaySizeX(), (int)
                        io.getDisplaySizeY());
        if (at == null) {
            return;
        }
        var text = typed.getTag(typedKind(), dragAxis);
        var draw = ImGui.getForegroundDrawList();
        draw.pushClipRect(left, top, right, bottom, true);
        var x = at[0] + 28;
        var y = at[1] - 36;
        draw.addRectFilled(
                x - 8,
                y - 4,
                x + ImGui.calcTextSizeX(text) + 8,
                y + ImGui.getFontSize() + 4,
                ImColor.rgba(10, 16, 22, 220),
                6f);
        draw.addRect(
                x - 8,
                y - 4,
                x + ImGui.calcTextSizeX(text) + 8,
                y + ImGui.getFontSize() + 4,
                GeodataWidgets.ACCENT,
                6,
                0,
                1);
        draw.addText(x, y, HudWidgets.TEXT, text);
        draw.popClipRect();
    }

    private void beginDrag(boolean copy) {
        dragCopies = 0;
        stick = null;
        dragBounds = null;
        run(session::beginStep);
        var sources = List.copyOf(session.getSelection());
        if (viewport != null && operation == Operation.TRANSLATE) {
            Bounds3f union = null;
            for (var entry : viewport.getCurrentBounds(Set.copyOf(sources)).entrySet()) {
                if (!session.getLockedActors().contains(entry.getKey())) {
                    var box = entry.getValue();
                    union = union == null
                            ? box
                            : new Bounds3f(
                                    Math.min(union.minX(), box.minX()),
                                    Math.min(union.minY(), box.minY()),
                                    Math.min(union.minZ(), box.minZ()),
                                    Math.max(union.maxX(), box.maxX()),
                                    Math.max(union.maxY(), box.maxY()),
                                    Math.max(union.maxZ(), box.maxZ()));
                }
            }
            dragBounds = union;
        }
        var copies = new ArrayList<String>();
        if (copy) {
            run(() -> copies.addAll(session.duplicateSelected(Vector3.ZERO)));
            dragCopies = copies.size();
        }
        if (stickToGround && operation == Operation.TRANSLATE) {
            var alias = new LinkedHashMap<String, String>();
            for (var i = 0; i < Math.min(sources.size(), copies.size()); i++) {
                alias.put(sources.get(i), copies.get(i));
            }
            stick = groundStick(alias);
        }
        run(session::beginGesture);
    }

    private GroundStick groundStick(Map<String, String> alias) {
        var anchors = new LinkedHashMap<String, Anchor>();
        if (viewport != null) {
            var locked = session.getLockedActors();
            var paths = alias.isEmpty() ? session.getSelection() : alias.keySet();
            viewport.getCurrentBounds(Set.copyOf(paths)).forEach((path, bounds) -> {
                var target = alias.getOrDefault(path, path);
                if (locked.contains(target)) {
                    return;
                }
                try {
                    var actor = session.getActor(path);
                    var source = actor.created() ? null : session.getSourceTransform(path);
                    anchors.put(
                            target,
                            Anchor.of(bounds, actor.transform().location(), source == null ? null : source.location()));
                } catch (RuntimeException unknown) {
                }
            });
        }
        return new GroundStick(probe, anchors);
    }

    private void endDrag() {
        stick = null;
        dragging = false;
        typed.clear();
        dragAxis = -1;
        if (copyPending) {
            copyPending = false;
            return;
        }
        session.finishGesture();
        endMove(dragCopies > 0 ? "Duplicate and move " + EditorUi.plural(dragCopies, "actor", "actors") : null);
        dragCopies = 0;
    }

    private void refresh() {
        if (session.getPrimary() == null) {
            return;
        }
        var t = session.getActor(session.getPrimary()).transform();
        location[0] = t.location().x();
        location[1] = t.location().y();
        location[2] = t.location().z();
        rotation[0] = (short) t.rotation().pitch() * 360f / 65536;
        rotation[1] = (short) t.rotation().yaw() * 360f / 65536;
        rotation[2] = (short) t.rotation().roll() * 360f / 65536;
        scale[0] = t.drawScale3d().x();
        scale[1] = t.drawScale3d().y();
        scale[2] = t.drawScale3d().z();
        uniform.set(t.drawScale());
        for (var i = 0; i < 3; i++) {
            locationText[i].set(EditorUi.compact(location[i]));
            rotationText[i].set(EditorUi.compact(rotation[i]));
            scaleText[i + 1].set(EditorUi.compact(scale[i]));
        }
        scaleText[0].set(EditorUi.compact(uniform.get()));
        createPosition[0] = t.location().x() + duplicateOffset[0];
        createPosition[1] = t.location().y() + duplicateOffset[1];
        createPosition[2] = t.location().z() + duplicateOffset[2];
    }

    private static boolean wasHistoryClicked() {
        return ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled) && ImGui.isMouseClicked(1);
    }

    private static String pivotLabel(PivotMode mode) {
        return switch (mode) {
            case CENTER -> "Center";
            case ACTIVE -> "Active";
            case INDIVIDUAL -> "Each";
        };
    }

    private static void scaleRange(String id, ImFloat min, ImFloat max) {
        var field =
                Math.max(48, (ImGui.getContentRegionAvailX() - ImGui.calcTextSizeX("to") - 2 * GeodataWidgets.ROW) / 2);
        ImGui.setNextItemWidth(field);
        ImGui.inputFloat("##" + id + "ScaleMin", min, 0, 0, "%g");
        if (ImGui.isItemDeactivatedAfterEdit()) {
            min.set(Math.clamp(min.get(), .01f, 100));
        }
        tip("The smallest factor.");
        ImGui.sameLine(0, GeodataWidgets.ROW);
        ImGui.textColored(HudWidgets.MUTED, "to");
        ImGui.sameLine(0, GeodataWidgets.ROW);
        ImGui.setNextItemWidth(field);
        ImGui.inputFloat("##" + id + "ScaleMax", max, 0, 0, "%g");
        if (ImGui.isItemDeactivatedAfterEdit()) {
            max.set(Math.clamp(max.get(), .01f, 100));
        }
        tip("The largest factor.");
    }

    private static String surfacesText(int count) {
        return EditorUi.plural(count, "surface", "surfaces");
    }

    private static void closeOnEscape() {
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false) && !ImGui.isAnyItemActive()) {
            ImGui.closeCurrentPopup();
        }
    }

    private static @Nullable String moveTarget(EditingMaps files, String path, ActorRecord actor) {
        var map = files.getTile(mapOf(path));
        if (!map.matches("[0-9]+_[0-9]+")) {
            return null;
        }
        var location = actor.transform().location();
        var tile = WorldTile.at(location.x(), location.y());
        var name = tile.x() + "_" + tile.y();
        return name.equals(map) ? null : files.getSource(name);
    }

    private static String mapOf(String actorPath) {
        var dot = actorPath.indexOf('.');
        return dot < 0 ? actorPath : actorPath.substring(0, dot);
    }

    private static void resetGroupRow(ImString[] texts, String none, boolean @Nullable [] enabled) {
        if (ImGui.getIO().getWantTextInput()) {
            return;
        }
        for (var i = 0; i < texts.length; i++) {
            texts[i].set(enabled == null || enabled[i] ? none : "");
        }
    }

    private static String exactValue(float value) {
        var exact = RelativeNumber.format(value);
        return exact.equals(EditorUi.compact(value)) ? "" : " Exactly " + exact + ".";
    }

    private static String materialLabel(Collection<String> actors) {
        return "Material of "
                + (actors.size() == 1
                        ? EditingSession.shortName(actors.iterator().next())
                        : actors.size() + " actors");
    }

    private static float[] aroundPivot(float[] m, Vector3 p) {
        m[12] = p.x() - m[0] * p.x() - m[4] * p.y() - m[8] * p.z();
        m[13] = p.y() - m[1] * p.x() - m[5] * p.y() - m[9] * p.z();
        m[14] = p.z() - m[2] * p.x() - m[6] * p.y() - m[10] * p.z();
        return m;
    }

    private static List<String> fixesOf(Finding finding) {
        var fix = EditingCheck.fixOf(finding);
        return fix.isEmpty() ? List.of() : List.of(fix);
    }

    private static String packagesTip(List<PackageSummary> packages) {
        if (packages.isEmpty()) {
            return "";
        }
        var names = new StringBuilder();
        for (var item : packages) {
            names.append(names.isEmpty() ? "" : ", ").append(item.name());
        }
        return "The save writes " + EditorUi.plural(packages.size(), "package", "packages") + ": " + names + ".";
    }

    private static String packageTip(PackageSummary item) {
        return item.name() + " · version " + item.fileVersion() + "/" + item.licenseVersion() + " · " + item.origin();
    }

    private static Path editingExports() {
        return home().resolve("Documents").resolve("Cruma").resolve("Exports");
    }

    private static Path home() {
        return Path.of(System.getProperty("user.home"));
    }

    private static String stamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));
    }

    private static Path parentOf(String text) {
        try {
            return text.isBlank() ? null : Path.of(text.trim()).getParent();
        } catch (InvalidPathException invalid) {
            return null;
        }
    }

    private static ImString[] texts(int count) {
        var result = new ImString[count];
        for (var i = 0; i < count; i++) {
            result[i] = new ImString(32);
        }
        return result;
    }

    private static Vector3 toVector(float[] value) {
        return new Vector3(value[0], value[1], value[2]);
    }

    private static int toRotationUnits(float degrees) {
        if (!Float.isFinite(degrees)) {
            throw new IllegalArgumentException("Rotation must be finite.");
        }
        return (int) Math.round((double) degrees / 360 * 65536);
    }

    private static String surfaceMaterialLabel(List<SurfaceRef> surfaces) {
        return "Material of " + (surfaces.size() == 1 ? "surface" : surfaces.size() + " surfaces");
    }

    private static String surfacesName(List<SurfaceRef> surfaces) {
        if (surfaces.size() == 1) {
            return "Surface " + surfaces.getFirst().surface() + " of "
                    + surfaces.getFirst().map();
        }
        return surfaces.size() + " surfaces";
    }
}
