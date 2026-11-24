package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.npc.ClientCatalog;
import com.rost.cruma.core.npc.NpcSpawnRate;
import com.rost.cruma.core.spawn.AiParameters;
import com.rost.cruma.core.spawn.ItemNames;
import com.rost.cruma.core.spawn.MakerAiCatalog;
import com.rost.cruma.core.spawn.NpcDataTable;
import com.rost.cruma.core.spawn.NpcDataTable.Npc;
import com.rost.cruma.core.spawn.NpcPosDocument;
import com.rost.cruma.core.spawn.NpcPosDocument.Batch;
import com.rost.cruma.core.spawn.NpcPosDocument.State;
import com.rost.cruma.core.spawn.NpcPosFile;
import com.rost.cruma.core.spawn.SpawnChecks;
import com.rost.cruma.core.spawn.SpawnChecks.Issue;
import com.rost.cruma.core.spawn.SpawnCopies;
import com.rost.cruma.core.spawn.SpawnEdits;
import com.rost.cruma.core.spawn.SpawnEdits.Carried;
import com.rost.cruma.core.spawn.SpawnHealth;
import com.rost.cruma.core.spawn.SpawnHealth.Finding;
import com.rost.cruma.core.spawn.SpawnHealth.Kind;
import com.rost.cruma.core.spawn.SpawnMaker;
import com.rost.cruma.core.spawn.SpawnNames;
import com.rost.cruma.core.spawn.SpawnNpc;
import com.rost.cruma.core.spawn.SpawnPosition;
import com.rost.cruma.core.spawn.SpawnPosition.Anywhere;
import com.rost.cruma.core.spawn.SpawnPosition.Fixed;
import com.rost.cruma.core.spawn.SpawnPosition.Weighted;
import com.rost.cruma.core.spawn.SpawnReview;
import com.rost.cruma.core.spawn.SpawnReview.Input;
import com.rost.cruma.core.spawn.SpawnSampler.Candidates;
import com.rost.cruma.core.spawn.SpawnSummary;
import com.rost.cruma.core.spawn.SpawnSummary.Event;
import com.rost.cruma.core.spawn.SpawnTerritory;
import com.rost.cruma.core.spawn.SpawnText;
import com.rost.cruma.core.spawn.TerritoryPoint;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.application.WindowContext.Keyboard;
import com.rost.cruma.viewer.application.WindowContext.Keyboard.Key;
import com.rost.cruma.viewer.config.ClientProfiles;
import com.rost.cruma.viewer.config.Config.Viewer.World.Spawns;
import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.config.ConfigLoader.Options;
import com.rost.cruma.viewer.config.OptionsModel;
import com.rost.cruma.viewer.editing.EditingCheck.SpawnSpot;
import com.rost.cruma.viewer.geodata.ClientFloors;
import com.rost.cruma.viewer.geodata.GeoOverlayLines;
import com.rost.cruma.viewer.geodata.GeodataBuildJob;
import com.rost.cruma.viewer.geodata.GeodataEditReview.SpawnCheck;
import com.rost.cruma.viewer.geodata.GeodataEditReview.SpawnImpact;
import com.rost.cruma.viewer.geodata.GeodataEditReview.SpawnProblem;
import com.rost.cruma.viewer.npc.NpcDataSource;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.LoadedTiles;
import com.rost.cruma.viewer.render.SpawnOverlayState;
import com.rost.cruma.viewer.render.SpawnOverlayState.ModelInstance;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import com.rost.cruma.viewer.spawn.SpawnAccess;
import com.rost.cruma.viewer.spawn.SpawnBookmarks;
import com.rost.cruma.viewer.spawn.SpawnFilter;
import com.rost.cruma.viewer.spawn.SpawnGround;
import com.rost.cruma.viewer.spawn.SpawnGround.CandidateKey;
import com.rost.cruma.viewer.spawn.SpawnGround.Drape;
import com.rost.cruma.viewer.spawn.SpawnGround.GroundSource;
import com.rost.cruma.viewer.spawn.SpawnIndex;
import com.rost.cruma.viewer.spawn.SpawnIndex.Extent;
import com.rost.cruma.viewer.spawn.SpawnIndex.Placement;
import com.rost.cruma.viewer.spawn.SpawnModelData;
import com.rost.cruma.viewer.spawn.SpawnModels;
import com.rost.cruma.viewer.spawn.SpawnPreview;
import com.rost.cruma.viewer.spawn.SpawnPreview.Placed;
import com.rost.cruma.viewer.spawn.SpawnProjector;
import com.rost.cruma.viewer.spawn.SpawnShapes;
import com.rost.cruma.viewer.spawn.SpawnShapes.Body;
import com.rost.cruma.viewer.spawn.SpawnSource;
import com.rost.cruma.viewer.spawn.SpawnSource.Phase;
import com.rost.cruma.viewer.spawn.SpawnStyle;
import com.rost.cruma.viewer.spawn.SpawnTotals;
import com.rost.cruma.viewer.ui.FindingWidgets.FindingList;
import com.rost.cruma.viewer.ui.FindingWidgets.FindingList.Group;
import com.rost.cruma.viewer.ui.FindingWidgets.Severity;
import com.rost.cruma.viewer.ui.GeodataEditingPanel.Leave;
import com.rost.cruma.viewer.ui.GeodataWorkspace.Viewport;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.SaveReviewDialog.Answer;
import com.rost.cruma.viewer.ui.SaveReviewDialog.Content;
import com.rost.cruma.viewer.ui.SpawnPanelWidgets.MapPoint;
import com.rost.cruma.viewer.ui.SpawnPanelWidgets.Part;
import com.rost.cruma.viewer.ui.SpawnPanelWidgets.Share;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImDrawFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiComboFlags;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImInt;
import imgui.type.ImString;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class SpawnWorkspace
        implements AutoCloseable, NpcSpawnLink, GeodataSpawnLink, InspectedView, OverlaySwitch {
    private static final float HANDLE_PIXELS = WorldHandles.PIXELS;

    private static final int MAX_LABELS = 36;

    private static final double LABEL_DISTANCE = 2400;

    private static final double NAME_DISTANCE = 1100;

    private static final double SHADOW_DISTANCE = 4000;

    private static final int CONTACT_SHADOW_ALPHA = 50;

    private static final double NPC_POINTS = 4;

    private static final double SEARCH_HEIGHT = 90;

    private static final int MAX_BODIES = 400;

    private static final int MAX_MODELS = 240;

    private static final float CURTAIN = 110;

    private static final int ZONE_LINES = GeoOverlayLines.MAX_PRIMITIVES - 2048;

    private static final int GROUND_TILES = 32;

    private static final int GROUND_KNOWN = 1;
    private static final int SELECTED = 2;
    private static final int IN_REACH = 4;

    private static final int[] NO_ZONES = new int[0];
    private static final String UNDO_KEY =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac") ? "Cmd+Z" : "Ctrl+Z";

    private static final int[] HEALTH_RADII = {2000, 5000, 10000};

    private static final float FOOTER = SaveFooter.HEIGHT;

    private static final String ESTIMATE = "An estimate from the client's floors: the server picks from its geodata.";

    private static final String AREAS_UNSAVED = "Areas has unsaved changes to areadata.txt in this folder. Save or"
            + " discard them first: the two tools share one folder.";

    private static final float BLOCK = GeodataWidgets.BLOCK;

    private static final float SECTION = GeodataWidgets.SECTION;
    private static final float ROW = GeodataWidgets.ROW;
    private static final float MAP_HEIGHT = 176;

    private static final float SHARED_CAPSULE_ALPHA = .28f;

    private static final float FOCUS_BUTTON = 26;

    private static final int LISTED_RESULTS = 200;

    private final SpawnMapLayer mapLayer = new SpawnMapLayer(this);

    private boolean selecting;

    private final TreeSet<Integer> chosen = new TreeSet<>();

    private int anchor = -1;

    private final SpawnBatchBar batchBar = new SpawnBatchBar();

    private boolean filtersOpen;

    private final EnumSet<SpawnStyle> filterKinds = EnumSet.noneOf(SpawnStyle.class);

    private int filterBand = -1;
    private String filterAi = "";
    private final ImString filterRespawn = new ImString(32);
    private String filterEvent = "";

    private final ImString aiSearch = new ImString(64);

    private final ImString eventSearch = new ImString(64);

    private boolean filterUnknown;

    private String listedFilters = "";

    private final LinkedHashSet<String> eventFilter = new LinkedHashSet<>();

    private boolean eventsOpen;
    private @Nullable State eventsState;
    private long eventsScope = -2;
    private List<Event> events = List.of();
    private @Nullable State fileEventsState;
    private List<Event> fileEvents = List.of();

    private boolean drawingZone;

    private boolean closingHot;

    private final List<int[]> drawnCorners = new ArrayList<>();

    private int placingMaker = -1;

    private boolean showCoverage;

    private final Map<Integer, Candidates> pinnedCandidates = new HashMap<>();

    private final Map<Integer, SpawnTerritory> pinnedZones = new HashMap<>();

    private @Nullable Future<List<Finding>> healthJob;

    private volatile boolean healthCancelled;
    private @Nullable List<Finding> healthFindings;
    private final FindingList healthList = new FindingList(20);
    private int healthRadius = 1;
    private @Nullable Finding healthSelected;
    private String healthArea = "";

    private final SpawnBookmarks bookmarkStore;
    private List<String> bookmarks = new ArrayList<>();
    private @Nullable Path bookmarksFolder;

    private @Nullable String editorNpc;

    private Clock clock = Clock.systemDefaultZone();

    private final Path configDirectory;
    private final SpawnSource source = new SpawnSource();
    private final SpawnGround ground = new SpawnGround();

    private final GroundClicks clicks = new GroundClicks(ground);

    private final SpawnModels models;
    private final FolderPicker folders = FolderPicker.scripts();
    private final ExecutorService indexer = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "npc-spawn-index");
        thread.setDaemon(true);
        return thread;
    });
    private final SpawnProjector projector = new SpawnProjector();
    private final float[] screen = new float[2];
    private final ImString search = new ImString(96);
    private final ImString npcFilter = new ImString(64);
    private final Map<Integer, SpawnStyle> makerStyles = new HashMap<>();

    private final Map<Integer, String> makerEvents = new HashMap<>();

    private final BitSet conditionalMakers = new BitSet();
    private final BitSet conditionalKnown = new BitSet();

    private int[] @Nullable [] shownMakers = new int[0][];
    private @Nullable State shownState;
    private @Nullable SpawnIndex shownIndex;
    private @Nullable NpcDataTable shownNpcs;
    private int shownSelected = -1;
    private boolean shownEvents;
    private Set<SpawnStyle> shownHidden = EnumSet.noneOf(SpawnStyle.class);
    private Set<String> shownEventFilter = Set.of();

    private long[] viewOrder = new long[0];

    private final byte[] groundTiles = new byte[GROUND_TILES * GROUND_TILES];

    private final float[] changedBox = new float[SpawnIndex.BOX_FLOATS];

    private volatile @Nullable SpawnIndex index;
    private State indexRequested;
    private @Nullable Future<?> indexing;
    private long indexedGeneration = -1;
    private @Nullable NpcPosDocument styledDocument;
    private long styledRevision = -1;

    private boolean visible;

    private boolean quiet;

    private @Nullable OverlaySwitch areaOverlay;

    private @Nullable Predicate<Path> folderGuard;

    private boolean showEvents;
    private boolean showDomains;
    private boolean showLabels = true;

    private boolean gameNames = true;

    private ClientCatalog catalog = ClientCatalog.empty();

    private boolean showUnused;
    private boolean showModels = true;

    private boolean showZones = true;

    private boolean showZoneCards = true;

    private int editingMaker = -1;

    private final int[] distance = {10000};

    private final EnumSet<SpawnStyle> hiddenKinds = EnumSet.noneOf(SpawnStyle.class);

    private final GeodataBuildControls geodataBuild = new GeodataBuildControls();

    private @Nullable Path geodataFolder;

    private boolean geodataBuilding;

    private int builtRegions = -1;

    private boolean geodataRequested;

    private int panelHoverMaker = -1;

    private @Nullable State compositionState;
    private long compositionScope = -2;
    private List<Share> composition = List.of();
    private @Nullable State fileCompositionState;
    private List<Share> fileComposition = List.of();

    private @Nullable Content review;

    private final SaveReviewDialog reviewDialog = new SaveReviewDialog("spawn", "npcpos.txt", "the spawn breaks");

    private final SaveFooter footer = new SaveFooter("spawn", "npcpos.txt");

    private boolean heightsOpen;

    private boolean cornersOpen;

    private boolean namesOpen;

    private boolean aiOpen;
    private final ImString aiFilter = new ImString(64);
    private @Nullable MakerAiCatalog aiCatalog;
    private @Nullable State placesState;
    private Map<String, List<int[]>> places = Map.of();
    private @Nullable NpcPosFile aiCatalogFile;

    private @Nullable State checkedSaved;
    private @Nullable State checkedState;
    private List<Issue> checkedIssues = List.of();
    private final List<float[]> hintAreas = new ArrayList<>();

    private double viewReach = 10000;

    private double lastGroundUnder = Double.NaN;

    private final Map<Integer, Candidates> shownCandidates = new HashMap<>();

    private @Nullable Hover hover;
    private @Nullable Handle hoveredHandle;
    private int selectedMaker = -1;
    private int selectedSlot = -1;
    private int lastClickedTerritory = -1;
    private int cycle;
    private long seed = 1;
    private @Nullable Drag drag;
    private final EditMerge merge = new EditMerge();
    private String searched = "";
    private List<Result> results = List.of();

    private int searchCursor;

    private int scrolledCursor = -1;

    private boolean confirmDelete;
    private boolean panelRequested;

    private @Nullable String npcEditRequest;

    private int npcExportRequest = -1;

    private @Nullable ImFont headingFont;

    private boolean deleteZones = true;

    private @Nullable Path remembered;
    private String message = "";
    private int messageColor = GeodataWidgets.MUTED;
    private @Nullable Leave pendingLeave;
    private @Nullable Leave approvedLeave;
    private boolean pointerOwned;

    private final @Nullable Path client;

    private @Nullable Camera camera;
    private @Nullable ViewerRenderBackend backend;
    private @Nullable Viewport viewport;
    private @Nullable LoadedTiles tiles;
    private @Nullable ClientFloors clientFloors;

    private @Nullable Inspected inspected;

    private boolean inspecting;

    private boolean inspectRequested;

    private List<DrawnNpc> drawnNpcs = List.of();

    private final List<Candidate> modelQueue = new ArrayList<>();

    private BitSet outsideMakers = new BitSet();
    private @Nullable State outsideState;
    private @Nullable SpawnIndex outsideIndex;
    private long outsideRevision = -2;
    private long scope;

    private @Nullable Counts counts;
    private @Nullable State countsState;
    private long countsScope = -2;

    private @Nullable State listedState;

    private long listedScope = -1;

    private List<Integer> nearbyMakers = List.of();

    private List<Integer> viewMakers = List.of();

    private int hiddenMatches;

    private @Nullable State aisState;
    private long aisScope = -2;
    private List<Entry<String, Integer>> ais = List.of();

    private @Nullable PendingFocus pendingFocus;

    private @Nullable State housekeepingState;
    private List<Integer> unusedZones = List.of();
    private List<SpawnSummary.Entry> unknownEntries = List.of();

    private int housekeepingPressed;

    private List<Entry<Integer, Double>> nearest = List.of();

    private @Nullable State presetsState;
    private List<String> presets = List.of();

    public SpawnWorkspace(Path configDirectory, Spawns config, @Nullable Path clientPath) {
        this(configDirectory, config, clientPath, WorldVariant.MAIN);
    }

    public SpawnWorkspace(Path configDirectory, Spawns config, @Nullable Path clientPath, WorldVariant variant) {
        this.configDirectory = configDirectory;
        this.client = clientPath;
        this.models = new SpawnModels(clientPath, variant);
        geodataBuild.setVariant(variant);
        this.bookmarkStore = new SpawnBookmarks(configDirectory.resolve("spawn-bookmarks.txt"));
        this.visible = config.enabled();
        this.remembered = config.scriptPath();
        if (config.scriptPath() != null) {
            source.open(config.scriptPath());
        }
    }

    private record Zone(
            int index,
            SpawnTerritory territory,
            Extent extent,
            double away,
            int[] makers,
            SpawnStyle style,
            float[] points,
            int[] corners,
            float groundZ,
            boolean grounded) {}

    private record Hover(int territory, int[] makers, Placement placement) {}

    private enum Grab {
        CORNER,
        MIDPOINT,
        ZONE,
        NPC,
        HEADING
    }

    private record Handle(Grab grab, int territory, int corner, int maker, int slot, double x, double y, double z) {}

    private static final class Drag {
        final Handle handle;
        final float pressX;
        final float pressY;
        final List<TerritoryPoint> points;
        final SpawnPosition position;

        final List<Carried> carried;

        final @Nullable Hover pressed;

        boolean applied;

        boolean keepNpcs;

        Drag(Handle handle, float pressX, float pressY, List<TerritoryPoint> points, SpawnPosition position) {
            this(handle, pressX, pressY, points, position, List.of(), null);
        }

        Drag(
                Handle handle,
                float pressX,
                float pressY,
                List<TerritoryPoint> points,
                SpawnPosition position,
                List<Carried> carried,
                @Nullable Hover pressed) {
            this.handle = handle;
            this.pressX = pressX;
            this.pressY = pressY;
            this.points = points;
            this.position = position;
            this.carried = carried;
            this.pressed = pressed;
        }
    }

    private record Result(int maker, int slot, String title, String detail, double distance) {}

    static double[] insideZones(List<SpawnTerritory> zones, double x, double y) {
        if (zones.isEmpty()) {
            return new double[] {x, y};
        }
        for (var zone : zones) {
            if (zone.containsXy(x, y)) {
                return new double[] {x, y};
            }
        }
        double[] best = null;
        SpawnTerritory bestZone = null;
        var bestDistance = Double.MAX_VALUE;
        for (var zone : zones) {
            var points = zone.points();
            for (var i = 0; i < points.size(); i++) {
                var a = points.get(i);
                var b = points.get((i + 1) % points.size());
                var dx = b.x() - a.x();
                var dy = b.y() - a.y();
                var length = dx * (double) dx + dy * (double) dy;
                var t = length == 0 ? 0 : Math.clamp(((x - a.x()) * dx + (y - a.y()) * dy) / length, 0, 1);
                var px = a.x() + dx * t;
                var py = a.y() + dy * t;
                var distance = Math.hypot(x - px, y - py);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new double[] {px, py};
                    bestZone = zone;
                }
            }
        }
        var towardX = bestZone.centerX() - best[0];
        var towardY = bestZone.centerY() - best[1];
        var towardLength = Math.max(1e-6, Math.hypot(towardX, towardY));
        for (var step : new double[] {8, 16, 32}) {
            var nx = best[0] + towardX / towardLength * Math.min(step, towardLength);
            var ny = best[1] + towardY / towardLength * Math.min(step, towardLength);
            if (bestZone.containsXy(nx, ny)) {
                return new double[] {nx, ny};
            }
        }
        return best;
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

    public void setQuiet(boolean quiet) {
        this.quiet = quiet;
    }

    public void setAreaOverlay(@Nullable OverlaySwitch areas) {
        areaOverlay = areas;
    }

    public void setFolderGuard(@Nullable Predicate<Path> guard) {
        folderGuard = guard;
    }

    public SpawnGround getGround() {
        return ground;
    }

    public void tick(@Nullable Path geodataFolder, @Nullable ClientFloors clientFloors) {
        source.poll();
        ground.setSource(geodataFolder);
        followBuild(geodataFolder);
        ground.setClient(clientFloors);
        this.clientFloors = clientFloors;
        var document = source.getDocument();
        if (indexedGeneration != source.getGeneration()) {
            indexedGeneration = source.getGeneration();
            var npcs = source.getNpcs();
            var folder = source.getFolder();
            indexer.submit(() -> {
                var items = ItemNames.empty();
                if (folder != null && Files.isRegularFile(folder.resolve("item_pch.txt"))) {
                    try {
                        items = ItemNames.read(folder.resolve("item_pch.txt"));
                    } catch (IOException | RuntimeException failure) {
                        LogManager.getLogger(SpawnWorkspace.class)
                                .warn("Could not read item_pch.txt: {}", failure.toString());
                    }
                }
                models.setScript(npcs, items);
            });
            shownCandidates.clear();
            index = null;
            indexRequested = null;
            selectedMaker = -1;
            selectedSlot = -1;
            hover = null;
            drag = null;
        }
        if (document == null) {
            return;
        }
        var state = document.getState();
        if (state != indexRequested && (indexing == null || indexing.isDone())) {
            indexRequested = state;
            indexing = indexer.submit(() -> {
                var built = SpawnIndex.build(state);
                index = built;
            });
        }
        dropStaleSelection(state);
    }

    public boolean hasDocument() {
        return source.getDocument() != null;
    }

    public boolean capturesPointer() {
        return pointerOwned;
    }

    public void handleKeys(Keyboard keys, boolean panelOpen) {
        if (keys.isClicked(Key.N) && !keys.isPressed(Key.COMMAND)) {
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
        if (keys.isClicked(Key.DELETE) && hoveredHandle != null && hoveredHandle.grab() == Grab.CORNER) {
            removeCorner(document, hoveredHandle.territory(), hoveredHandle.corner());
        }
        if (keys.isClicked(Key.F) && selectedMaker >= 0 && camera != null) {
            focus(camera, selectedMaker);
        }
    }

    public boolean handleEscape() {
        if (drag != null) {
            drag = null;
            return true;
        }
        if (drawingZone) {
            drawingZone = false;
            drawnCorners.clear();
            clicks.cancel();
            return true;
        }
        if (placingMaker >= 0) {
            placingMaker = -1;
            clicks.cancel();
            return true;
        }
        if (batchBar.isBusy()) {
            batchBar.cancel();
            return true;
        }
        if (editingMaker >= 0) {
            editingMaker = -1;
            return true;
        }
        if (selectedMaker >= 0) {
            selectedMaker = -1;
            selectedSlot = -1;
            return true;
        }
        if (selecting) {
            selecting = false;
            chosen.clear();
            return true;
        }
        return false;
    }

    @Override
    public boolean areSpawnsReady() {
        return source.getDocument() != null && index != null;
    }

    @Override
    public List<SpawnMark> findSpawnMarks(double minX, double minY, double maxX, double maxY, int limit) {
        var document = source.getDocument();
        var currentIndex = index;
        if (document == null || currentIndex == null) {
            return List.of();
        }
        var state = document.getState();
        var makers = new LinkedHashSet<Integer>();
        for (var territory : currentIndex.findTerritoriesNear(minX, minY, maxX, maxY)) {
            for (var maker : currentIndex.getMakersOf(territory)) {
                makers.add(maker);
            }
        }
        for (var placement : currentIndex.findPlacementsNear(minX, minY, maxX, maxY)) {
            makers.add(placement.makerIndex());
        }
        var npcs = source.getNpcs();
        var marks = new ArrayList<SpawnMark>();
        for (var makerIndex : makers) {
            if (makerIndex >= state.makers().size()
                    || state.makers().get(makerIndex) == null
                    || !isShown(state, makerIndex)) {
                continue;
            }
            var maker = state.makers().get(makerIndex);
            var candidates = maker.npcs().stream().anyMatch(SpawnNpc::isAnywhere)
                    ? findCandidates(state, currentIndex, makerIndex, maker)
                    : null;
            for (var placed : SpawnPreview.place(maker, candidates, seed + makerIndex)) {
                if (placed.x() < minX
                        || placed.x() > maxX
                        || placed.y() < minY
                        || placed.y() > maxY
                        || !isInLoadedTiles(placed.x(), placed.y())) {
                    continue;
                }
                var info = npcs.getNpc(placed.npcName());
                var style = maker.isConditional() ? SpawnStyle.EVENT : SpawnStyle.of(info);
                marks.add(new SpawnMark(
                        placed.x(),
                        placed.y(),
                        placed.z(),
                        style.argb,
                        worldName(placed.npcName(), info),
                        maker.displayName(),
                        !placed.random(),
                        info == null ? 0 : (int) Math.round(info.collisionRadius())));
                if (marks.size() >= limit) {
                    return marks;
                }
            }
        }
        return marks;
    }

    public List<SpawnSpot> getEditingSpots(double minX, double minY, double maxX, double maxY) {
        var document = source.getDocument();
        var currentIndex = index;
        if (document == null || currentIndex == null) {
            return List.of();
        }
        var state = document.getState();
        var npcs = source.getNpcs();
        var spots = new ArrayList<SpawnSpot>();
        for (var maker : state.makers()) {
            if (maker == null) {
                continue;
            }
            for (var npc : maker.npcs()) {
                var name = worldName(npc.npcName(), npcs.getNpc(npc.npcName()));
                switch (npc.position()) {
                    case Fixed fixed ->
                        addSpot(
                                spots,
                                name,
                                "The fixed NPC " + name,
                                fixed.x(),
                                fixed.y(),
                                fixed.z(),
                                minX,
                                minY,
                                maxX,
                                maxY);
                    case Weighted weighted -> {
                        for (var point : weighted.points()) {
                            addSpot(
                                    spots,
                                    name,
                                    "A spawn point of " + name,
                                    point.x(),
                                    point.y(),
                                    point.z(),
                                    minX,
                                    minY,
                                    maxX,
                                    maxY);
                        }
                    }
                    default -> {}
                }
            }
        }
        for (var territoryIndex : currentIndex.findTerritoriesNear(minX, minY, maxX, maxY)) {
            if (territoryIndex >= state.territories().size()
                    || state.territories().get(territoryIndex) == null) {
                continue;
            }
            var territory = state.territories().get(territoryIndex);
            for (var point : territory.points()) {
                addSpot(
                        spots,
                        territory.name(),
                        "A corner of the zone " + territory.name(),
                        point.x(),
                        point.y(),
                        point.bottom(),
                        minX,
                        minY,
                        maxX,
                        maxY);
            }
        }
        return spots.size() > 5000 ? List.copyOf(spots.subList(0, 5000)) : List.copyOf(spots);
    }

    @Override
    public @Nullable SpawnMark getSelectedSpawn() {
        var document = source.getDocument();
        var currentIndex = index;
        if (document == null || currentIndex == null || selectedMaker < 0 || selectedSlot < 0) {
            return null;
        }
        var state = document.getState();
        if (selectedMaker >= state.makers().size() || state.makers().get(selectedMaker) == null) {
            return null;
        }
        var maker = state.makers().get(selectedMaker);
        var candidates = maker.npcs().stream().anyMatch(SpawnNpc::isAnywhere)
                ? findCandidates(state, currentIndex, selectedMaker, maker)
                : null;
        for (var placed : SpawnPreview.place(maker, candidates, seed + selectedMaker)) {
            if (placed.slot() == selectedSlot) {
                var info = source.getNpcs().getNpc(placed.npcName());
                var style = maker.isConditional() ? SpawnStyle.EVENT : SpawnStyle.of(info);
                return new SpawnMark(
                        placed.x(),
                        placed.y(),
                        placed.z(),
                        style.argb,
                        worldName(placed.npcName(), info),
                        maker.displayName(),
                        !placed.random(),
                        info == null ? 0 : (int) Math.round(info.collisionRadius()));
            }
        }
        return null;
    }

    @Override
    public SpawnCheck getSpawnCheck() {
        var document = source.getDocument();
        if (document == null) {
            return null;
        }
        var state = document.getState();
        var npcs = source.getNpcs();
        return (before, after, area, cancelled) -> {
            var makers = SpawnHealth.makersIn(state, area.minX(), area.minY(), area.maxX(), area.maxY());
            var impact = SpawnHealth.findIntroduced(state, makers, npcs, before, after, cancelled);
            return new SpawnImpact(toProblems(impact.blocked()), toProblems(impact.emptied()));
        };
    }

    public boolean consumeInspectRequest() {
        var requested = inspectRequested;
        inspectRequested = false;
        return requested;
    }

    public void stopInspecting() {
        inspected = null;
    }

    @Override
    public @Nullable Header getInspectedHeader() {
        var current = inspected;
        var document = source.getDocument();
        if (current == null || document == null) {
            return null;
        }
        var info = source.getNpcs().getNpc(current.npcName());
        var maker = current.makerIndex() < document.getState().makers().size()
                ? document.getState().makers().get(current.makerIndex())
                : null;
        return new Header(
                worldName(current.npcName(), info),
                current.npcName()
                        + (info == null ? "" : " · id " + info.id())
                        + (maker == null ? "" : " · " + maker.displayName()),
                "NPC model");
    }

    @Override
    public void drawInspectedDetails() {
        var current = inspected;
        if (current == null) {
            return;
        }
        var info = source.getNpcs().getNpc(current.npcName());
        GeodataWidgets.section("MODEL", ROW);
        space(ROW);
        if (info == null) {
            GeodataWidgets.hint("npcdata.txt has no " + current.npcName() + ", so its id and model are not known.");
        } else {
            var appearance = models.getAppearance(info.id());
            var status = models.getStatus(info.id());
            var text = appearance == null && !models.getMessage().isEmpty()
                    ? models.getMessage()
                    : switch (status) {
                        case READY -> "Drawn";
                        case LOADING -> "Loading…";
                        case INVISIBLE -> "The game draws it invisible";
                        case NOT_IN_CLIENT -> "Not in this client's npcgrp.dat";
                        case MISSING -> "The client's mesh could not be read";
                    };
            GeodataWidgets.value(
                    "Status", text, status == SpawnModels.Status.READY ? HudWidgets.TEXT : GeodataWidgets.WARNING);
            if (appearance != null) {
                GeodataWidgets.value("Mesh", appearance.mesh());
                GeodataWidgets.value("Draw scale", String.format(Locale.ROOT, "%.2f", appearance.drawScale()));
                var model = status == SpawnModels.Status.READY ? models.getModel(info.id()) : null;
                if (model != null) {
                    GeodataWidgets.value("Height", String.format(Locale.ROOT, "%,.0f units", model.height()));
                    var kinds = new EnumMap<SpawnModelData.Kind, Integer>(SpawnModelData.Kind.class);
                    for (var part : model.parts()) {
                        kinds.merge(part.kind(), 1, Integer::sum);
                    }
                    var parts = new ArrayList<String>();
                    kinds.forEach(
                            (kind, count) -> parts.add(count + " " + kind.name().toLowerCase(Locale.ROOT)));
                    GeodataWidgets.value("Materials", String.join(", ", parts));
                }
                if (!appearance.textures().isEmpty()) {
                    space(ROW);
                    ImGui.textColored(GeodataWidgets.MUTED, "Textures");
                    for (var texture : appearance.textures()) {
                        GeodataWidgets.note(texture, HudWidgets.TEXT);
                    }
                }
            }
            GeodataWidgets.section("COLLISION", SECTION);
            space(ROW);
            GeodataWidgets.value("Radius", formatUnits(info.collisionRadius()));
            GeodataWidgets.value("Height", formatUnits(info.collisionHeight()));
            GeodataWidgets.hint(
                    "The game hangs the model from the middle of this cylinder, so a taller one lifts it. Its size"
                            + " comes from the client.");
            if (!info.rightHand().isEmpty() || !info.leftHand().isEmpty()) {
                GeodataWidgets.section("HOLDS", SECTION);
                space(ROW);
                if (!info.rightHand().isEmpty()) {
                    GeodataWidgets.value("Right hand", info.rightHand());
                }
                if (!info.leftHand().isEmpty()) {
                    GeodataWidgets.value("Left hand", info.leftHand());
                }
                GeodataWidgets.hint(
                        appearance != null && SpawnModels.isPlayerLike(appearance)
                                ? "Drawn in its hands. A change shows after npcdata.txt is saved."
                                : "The game shows the weapon modelled into its mesh, not these. The server still adds"
                                        + " their stats.");
            }
        }
        GeodataWidgets.section("SPAWN", SECTION);
        space(ROW);
        var document = source.getDocument();
        var maker = document == null
                        || current.makerIndex() >= document.getState().makers().size()
                ? null
                : document.getState().makers().get(current.makerIndex());
        if (maker != null) {
            GeodataWidgets.value("Maker", maker.displayName());
        }
        GeodataWidgets.value(
                "Stands at", String.format(Locale.ROOT, "%.0f, %.0f, %.0f", current.x(), current.y(), current.z()));
        space(BLOCK);
        if (GeodataWidgets.rowButton("Open in NPCs", 0, 2)) {
            npcEditRequest = current.npcName();
        }
        GeodataWidgets.tip("What it drops, its skills and numbers, in the NPCs panel.");
        if (GeodataWidgets.rowButton("Its spawn", 1, 2)) {
            selectedMaker = current.makerIndex();
            selectedSlot = current.slot();
            visible = true;
            panelRequested = true;
        }
        GeodataWidgets.tip("Select its maker in NPC Spawns.");
        if (info != null && models.getAppearance(info.id()) != null) {
            space(ROW);
            var unreadable = models.getStatus(info.id()) == SpawnModels.Status.MISSING;
            ImGui.beginDisabled(unreadable);
            if (GeodataWidgets.actionButton("spawn-npc-export", Glyph.CARD, "Export NPC…", -1, false)) {
                npcExportRequest = info.id();
            }
            ImGui.endDisabled();
            GeodataWidgets.tip(
                    unreadable
                            ? "The client's mesh could not be read, so it cannot be exported."
                            : "Save it as glTF with its skeleton and animations, for Blender and other 3D programs.");
        }
    }

    public int consumeNpcExportRequest() {
        var request = npcExportRequest;
        npcExportRequest = -1;
        return request;
    }

    public MapLayer getMapLayer() {
        return mapLayer;
    }

    @Nullable
    State getMapState() {
        var document = source.getDocument();
        return document == null ? null : document.getState();
    }

    @Nullable
    SpawnIndex getMapIndex() {
        return index;
    }

    @Nullable
    LoadedTiles getLoadedTiles() {
        return tiles;
    }

    SpawnStyle getMakerStyle(State state, int maker) {
        var document = source.getDocument();
        if (document != null) {
            refreshStyles(document);
        }
        return styleOf(state, maker);
    }

    boolean isMakerShown(State state, int maker) {
        return isShown(state, maker);
    }

    NpcDataTable getNpcData() {
        return source.getNpcs();
    }

    int getSelectedMaker() {
        return selectedMaker;
    }

    Set<Integer> getChosenMakers() {
        return chosen;
    }

    EnumSet<SpawnStyle> getHiddenKinds() {
        return hiddenKinds;
    }

    List<Share> getMapComposition() {
        var document = source.getDocument();
        if (document == null) {
            return List.of();
        }
        var state = document.getState();
        if (state != fileCompositionState) {
            fileComposition = countKinds(state, false);
            fileCompositionState = state;
        }
        return fileComposition;
    }

    boolean hasEvents() {
        var document = source.getDocument();
        return document != null && !fileEvents(document.getState()).isEmpty();
    }

    boolean areEventsShown() {
        return showEvents;
    }

    void showEventsOnMap(boolean shown) {
        showEvents = shown;
    }

    void selectOnMap(int maker) {
        selectedMaker = maker;
        selectedSlot = -1;
        editingMaker = -1;
        clearSearch();
        panelRequested = true;
        if (camera != null) {
            focus(camera, maker);
        }
    }

    void chooseOnMap(Collection<Integer> makers) {
        if (makers.isEmpty()) {
            return;
        }
        selecting = true;
        chosen.addAll(makers);
        selectedMaker = -1;
        selectedSlot = -1;
        panelRequested = true;
        setMessage(
                String.format(
                        Locale.ROOT,
                        "%,d %s from the map added to the selection.",
                        makers.size(),
                        makers.size() == 1 ? "maker" : "makers"),
                GeodataWidgets.MUTED);
    }

    public void setEditorNpc(@Nullable String npc) {
        editorNpc = npc;
    }

    public void drawViewport(
            SpawnOverlayState overlay,
            Camera camera,
            ViewerRenderBackend backend,
            Viewport viewport,
            boolean panelOpen,
            boolean pointerAllowed,
            boolean helpOpen,
            boolean inspecting,
            LoadedTiles loadedTiles) {
        this.tiles = loadedTiles;
        this.camera = camera;
        this.viewport = viewport;
        pointerOwned = false;
        this.inspecting = inspecting;
        if (!inspecting) {
            inspected = null;
        }
        var document = source.getDocument();
        var currentIndex = index;
        var io = ImGui.getIO();
        var width = io.getDisplaySizeX();
        var height = io.getDisplaySizeY();
        overlay.visible = visible && document != null && currentIndex != null && width > 0 && height > 0;
        if (!overlay.visible) {
            overlay.clear();
            hover = null;
            hoveredHandle = null;
            drag = null;
            viewMakers = List.of();
            return;
        }
        var state = document.getState();
        dropStaleSelection(state);
        refreshStyles(document);
        projector.update(camera.getViewMatrix(), camera.getProjectionMatrix(), width, height);
        var eye = camera.getPosition();
        var mouseX = ImGui.getMousePosX();
        var mouseY = ImGui.getMousePosY();
        var pointerFree = pointerAllowed && !io.getWantCaptureMouse() && viewport.contains(mouseX, mouseY);
        var ndcX = mouseX / width * 2f - 1f;
        var ndcY = 1f - mouseY / height * 2f;
        var origin = camera.getCursorRayOrigin(ndcX, ndcY);
        var direction = camera.getCursorRayDirection(ndcX, ndcY);

        var groundUnder = ground.getGroundAt(eye.getX(), eye.getY(), eye.getZ());
        if (Double.isFinite(groundUnder)) {
            lastGroundUnder = groundUnder;
        } else {
            groundUnder = lastGroundUnder;
        }
        viewReach = distance[0] + (Double.isFinite(groundUnder) ? Math.max(0, eye.getZ() - groundUnder) * 1.5 : 0);
        followPendingFocus(state, currentIndex, camera);
        var zones = findVisibleZones(state, currentIndex, camera, eye.getX(), eye.getY(), eye.getZ());
        if (editingMaker != selectedMaker) {
            editingMaker = -1;
        }
        var editing = panelOpen && selectedMaker >= 0 && editingMaker == selectedMaker && !inspecting;
        var npcSelected = panelOpen && selectedMaker >= 0 && selectedSlot >= 0 && !inspecting && !selecting;
        var handles = editing || npcSelected
                ? createHandles(state, zones, editing, eye.getX(), eye.getY())
                : List.<Handle>of();

        if (drag != null) {
            pointerOwned = true;
            if (ImGui.isMouseDown(0)) {
                continueDrag(
                        document,
                        drag,
                        origin.getX(),
                        origin.getY(),
                        origin.getZ(),
                        direction.getX(),
                        direction.getY(),
                        direction.getZ(),
                        mouseX,
                        mouseY);
            } else {
                if (!drag.applied && drag.handle.grab() == Grab.MIDPOINT) {
                    insertCorner(document, drag.handle, drag.handle.x(), drag.handle.y());
                }
                if (!drag.applied && drag.handle.grab() == Grab.ZONE && drag.pressed != null) {
                    handleClick(drag.pressed);
                }
                if (drag.applied && drag.keepNpcs) {
                    pinCandidates(state, drag);
                }
                drag = null;
            }
        }

        var gesture = drawingZone || placingMaker >= 0;
        if (gesture) {
            handleGesture(
                    document,
                    backend,
                    pointerFree,
                    ndcX,
                    ndcY,
                    origin.getX(),
                    origin.getY(),
                    origin.getZ(),
                    direction.getX(),
                    direction.getY(),
                    direction.getZ(),
                    eye.getX(),
                    eye.getY());
        }
        hoveredHandle = null;
        if (pointerFree && drag == null && !gesture) {
            hoveredHandle = handleAt(handles, mouseX, mouseY);
            hover = hoveredHandle != null
                    ? hover
                    : pick(
                            state,
                            zones,
                            eye.getX(),
                            eye.getY(),
                            origin.getX(),
                            origin.getY(),
                            origin.getZ(),
                            direction.getX(),
                            direction.getY(),
                            direction.getZ(),
                            mouseX,
                            mouseY);
        } else if (drag == null) {
            hover = null;
        }
        if (inspecting && hover != null && hover.placement() == null) {
            hover = null;
        }
        if (selecting && !inspecting) {
            if (pointerFree && !gesture && drag == null && ImGui.isMouseClicked(0) && hover != null) {
                toggleChosen(state, hover);
            }
        } else if (inspecting) {
            if (pointerFree && !gesture && drag == null && ImGui.isMouseClicked(0) && hover != null) {
                inspect(state, hover.placement());
            }
        } else if (pointerFree
                && !gesture
                && ImGui.isMouseDoubleClicked(0)
                && drag == null
                && (hoveredHandle == null
                        ? hover != null && (hover.placement() != null || hover.makers().length > 0)
                        : hoveredHandle.grab() == Grab.NPC)) {
            if (hoveredHandle != null || hover.placement() != null) {
                selectedMaker = hoveredHandle != null
                        ? hoveredHandle.maker()
                        : hover.placement().makerIndex();
                selectedSlot = hoveredHandle != null
                        ? hoveredHandle.slot()
                        : hover.placement().slot();
                var maker = state.makers().get(selectedMaker);
                if (maker != null
                        && selectedSlot >= 0
                        && selectedSlot < maker.npcs().size()) {
                    npcEditRequest = maker.npcs().get(selectedSlot).npcName();
                }
            } else {
                if (!contains(hover.makers(), selectedMaker)) {
                    handleClick(hover);
                }
                editingMaker = selectedMaker;
                visible = true;
                panelRequested = !panelOpen;
            }
        } else if (pointerFree && !gesture && ImGui.isMouseClicked(0) && drag == null) {
            if (hoveredHandle != null) {
                var maker = state.makers().get(hoveredHandle.maker());
                var npc = hoveredHandle.slot() >= 0 ? maker.npcs().get(hoveredHandle.slot()) : null;
                var territory =
                        hoveredHandle.territory() >= 0 ? state.territories().get(hoveredHandle.territory()) : null;
                drag = new Drag(
                        hoveredHandle,
                        mouseX,
                        mouseY,
                        territory == null ? List.of() : territory.points(),
                        npc == null ? SpawnPosition.ANYWHERE : npc.position());
                if (hoveredHandle.slot() >= 0) {
                    selectedSlot = hoveredHandle.slot();
                }
            } else if (editing
                    && tryStartZoneDrag(
                            state,
                            zones,
                            hover,
                            origin.getX(),
                            origin.getY(),
                            origin.getZ(),
                            direction.getX(),
                            direction.getY(),
                            direction.getZ(),
                            mouseX,
                            mouseY)) {
            } else if (panelOpen) {
                handleClick(hover);
            } else if (hover != null && (hover.placement() != null || hover.makers().length > 0)) {
                handleClick(hover);
                visible = true;
                panelRequested = true;
            }
        }
        if (hover != null || hoveredHandle != null) {
            pointerOwned = panelOpen || hover != null && (hover.placement() != null || hover.makers().length > 0);
        }

        overlay.fills.begin();
        overlay.bodies.begin();
        overlay.lines.begin();
        modelQueue.clear();
        var previewed = previewedMakers();
        drawZones(overlay, zones, eye.getZ());
        var labels = drawNpcs(overlay, state, currentIndex, zones, previewed, eye.getX(), eye.getY(), eye.getZ());
        var inView = new LinkedHashSet<Integer>();
        for (var zone : zones) {
            for (var maker : zone.makers()) {
                inView.add(maker);
            }
        }
        for (var npc : drawnNpcs) {
            if (isShown(state, npc.makerIndex())) {
                inView.add(npc.makerIndex());
            }
        }
        viewMakers = List.copyOf(inView);
        for (var handle : handles) {
            if (handle.grab() == Grab.HEADING) {
                for (var other : handles) {
                    if (other.grab() == Grab.NPC && other.maker() == handle.maker() && other.slot() == handle.slot()) {
                        overlay.lines.addLine(
                                new GeoPoint(other.x(), other.y(), other.z()),
                                new GeoPoint(handle.x(), handle.y(), handle.z()),
                                4,
                                ImColor.rgba(92, 219, 241, 150),
                                1.5f);
                    }
                }
            }
        }
        for (var handle : handles) {
            drawHandle(overlay, handle, handle.equals(hoveredHandle) || drag != null && drag.handle.equals(handle));
        }
        drawCoverage(overlay, state, currentIndex);
        drawGesture(overlay);
        overlay.fills.publish();
        overlay.bodies.publish();
        overlay.lines.publish(width);
        modelQueue.sort(Comparator.comparingDouble(Candidate::order));
        overlay.models =
                modelQueue.stream().limit(MAX_MODELS).map(Candidate::instance).toList();

        var draw = ImGui.getBackgroundDrawList();
        draw.pushClipRect(viewport.left(), viewport.top(), viewport.right(), viewport.bottom(), false);
        var zoneTag = drawZoneTag(draw, zones);
        if (showLabels && !quiet) {
            drawLabels(draw, labels, zoneTag);
        }
        hintAreas.clear();
        if (panelOpen && !helpOpen) {
            GeodataWidgets.hintStrip(draw, viewport, "NPC SPAWNS", hintItems(state), hintAreas);
        }
        drawGestureTag(draw);
        draw.popClipRect();
        var zoneOnly = hoveredHandle == null && hover != null && hover.placement() == null;
        if (pointerFree
                && !gesture
                && drag == null
                && (hover != null || hoveredHandle != null)
                && (showZoneCards || !zoneOnly)) {
            drawHoverCard(state);
        }
    }

    public void setHeadingFont(@Nullable ImFont font) {
        headingFont = font;
    }

    public void setClientCatalog(ClientCatalog catalog) {
        this.catalog = catalog;
    }

    public SpawnModels getModels() {
        return models;
    }

    public void drawPanel(Camera camera, ViewerRenderBackend backend) {
        this.camera = camera;
        this.backend = backend;
        panelHoverMaker = -1;
        drawHeader();
        drawSourceRow();
        var picked = folders.draw();
        if (picked != null && openScriptFolder(picked)) {
            visible = true;
        }
        var document = source.getDocument();
        if (source.getPhase() != Phase.READY || document == null) {
            if (source.getPhase() == Phase.EMPTY) {
                drawEmptySource();
            } else if (source.getPhase() == Phase.LOADING) {
                ImGui.dummy(0, 8);
                ImGui.textColored(GeodataWidgets.MUTED, "Reading npcpos.txt and npcdata.txt…");
            }
            return;
        }
        dropStaleSelection(document.getState());
        if (!message.isBlank()) {
            ImGui.pushTextWrapPos(0);
            ImGui.textColored(messageColor, message);
            ImGui.popTextWrapPos();
        }
        drawSearchBox(document.getState());
        listChips();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        var footer = isFooterShown(document);
        ImGui.beginChild("##spawnBody", 0, footer ? -FOOTER : 0, false);
        ImGui.popStyleVar();
        ImGui.dummy(0, BLOCK - 2 * spacing());
        ImGui.pushTextWrapPos(0);
        if (filtersOpen) {
            drawFilters(document);
            space(BLOCK);
        }
        if (drawingZone) {
            drawZoneCard();
            space(BLOCK);
        }
        refreshNearby(document.getState(), camera);
        if (selecting) {
            var listing = !searched.isBlank() || isFiltering();
            var shown = listing ? resultMakers() : viewMakers;
            var revision = document.getRevision();
            var allLabel =
                    "All " + String.format(Locale.ROOT, "%,d", shown.size()) + (listing ? " matching" : " in view");
            if (batchBar.draw(document, chosen, shown, allLabel, aiCatalog(document))) {
                selectedMaker = -1;
                selectedSlot = -1;
            }
            if (chosen.isEmpty()) {
                space(ROW);
                ImGui.pushTextWrapPos(0);
                ImGui.textColored(
                        GeodataWidgets.FAINT,
                        listing
                                ? "Tick makers in the results, or take every match with " + allLabel + "."
                                : "Click zones or NPCs in the world, tick makers in Near the camera, drag a box on the"
                                        + " world map (M), or take every maker in view with "
                                        + allLabel + ".");
                ImGui.popTextWrapPos();
            }
            if (document.getRevision() != revision) {
                merge.reset();
            }
            space(BLOCK);
        }
        if (!searched.isBlank() || isFiltering() && (selectedMaker < 0 || selecting)) {
            drawSearchResults(document.getState(), camera);
        } else if (selectedMaker >= 0) {
            drawMakerSection(document, camera);
        } else {
            drawOverview(document, camera, backend);
        }
        ImGui.popTextWrapPos();
        ImGui.dummy(0, 8);
        ImGui.endChild();
        if (footer) {
            drawFooter(document);
        }
    }

    public boolean consumePanelRequest() {
        var requested = panelRequested;
        panelRequested = false;
        return requested;
    }

    public @Nullable String consumeNpcEditRequest() {
        var request = npcEditRequest;
        npcEditRequest = null;
        return request;
    }

    public @Nullable Path getScriptFolder() {
        return source.getFolder();
    }

    public boolean openScriptFolder(Path folder) {
        if (source.isModified()) {
            setMessage("Save or undo your changes before opening another folder.", GeodataWidgets.WARNING);
            return false;
        }
        var guard = folderGuard;
        if (guard != null && guard.test(folder)) {
            setMessage(AREAS_UNSAVED, GeodataWidgets.WARNING);
            return false;
        }
        if (message.equals(AREAS_UNSAVED)) {
            message = "";
        }
        source.open(folder);
        remember(SpawnSource.resolveFolder(folder));
        return true;
    }

    public void findNpc(String name) {
        search.set(name);
        selectedMaker = -1;
        selectedSlot = -1;
        visible = true;
    }

    @Override
    public boolean isReady() {
        return source.getDocument() != null && index != null;
    }

    @Override
    public @Nullable Nearest findNearestSpawn(String npc) {
        var document = source.getDocument();
        if (document == null || index == null) {
            return null;
        }
        var state = document.getState();
        var eye = camera == null ? null : camera.getPosition();
        Nearest best = null;
        for (var place : findNpcPlaces(state).getOrDefault(npc, List.of())) {
            var where = placeLocation(state, place[0], place[1]);
            if (where == null) {
                continue;
            }
            var distance = eye == null ? 0 : Math.hypot(where[0] - eye.getX(), where[1] - eye.getY());
            var loaded = isListed(state, place[0]);
            if (best == null || loaded && !best.loaded() || loaded == best.loaded() && distance < best.distance()) {
                best = new Nearest(distance, where[0], where[1], where[2], loaded);
            }
        }
        return best;
    }

    @Override
    public Summary getSummary(String npc) {
        var document = source.getDocument();
        if (document == null) {
            return Summary.NONE;
        }
        var state = document.getState();
        var entries = 0;
        var npcs = 0;
        var kills = 0.0;
        String respawn = null;
        var mixed = false;
        for (var place : findNpcPlaces(state).getOrDefault(npc, List.of())) {
            var entry = state.makers().get(place[0]).npcs().get(place[1]);
            entries++;
            npcs += entry.total();
            kills += NpcSpawnRate.killsPerHour(entry.total(), entry.respawn());
            if (respawn == null) {
                respawn = entry.respawn();
            } else if (!respawn.equals(entry.respawn())) {
                mixed = true;
            }
        }
        return new Summary(
                entries, npcs, kills, mixed || respawn == null ? "" : SpawnPanelWidgets.formatRespawnTime(respawn));
    }

    @Override
    public Set<String> getSpawnedNames() {
        var document = source.getDocument();
        return document == null ? Set.of() : findNpcPlaces(document.getState()).keySet();
    }

    @Override
    public boolean flyTo(String npc) {
        var document = source.getDocument();
        if (document == null || index == null || camera == null) {
            return false;
        }
        var state = document.getState();
        var eye = camera.getPosition();
        int[] best = null;
        var bestDistance = Double.MAX_VALUE;
        for (var place : findNpcPlaces(state).getOrDefault(npc, List.of())) {
            var where = placeLocation(state, place[0], place[1]);
            if (where != null && isListed(state, place[0])) {
                var distance = Math.hypot(where[0] - eye.getX(), where[1] - eye.getY());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = place;
                }
            }
        }
        if (best == null) {
            return false;
        }
        selectedMaker = best[0];
        selectedSlot = best[1];
        visible = true;
        focus(camera, best[0]);
        return true;
    }

    @Override
    public boolean hasUnsavedSpawns() {
        return source.isModified();
    }

    @Override
    public HeldItems getHeldItems(int npcId) {
        var appearance = models.getAppearance(npcId);
        if (appearance == null) {
            models.getModel(npcId);
            return HeldItems.UNKNOWN;
        }
        if (appearance.mesh().isBlank() || appearance.mesh().equalsIgnoreCase("None")) {
            return HeldItems.UNKNOWN;
        }
        if (SpawnModels.isPlayerLike(appearance)) {
            return HeldItems.WORN;
        }
        var model = models.getModel(npcId);
        if (model == null) {
            return HeldItems.UNKNOWN;
        }
        return models.hasHand(npcId, model) ? HeldItems.WORN : HeldItems.PART_OF_MESH;
    }

    public void onNpcDataSaved(SpawnText text) {
        var npcs = NpcDataTable.parse(text.text());
        source.replaceNpcs(npcs);
        var folder = source.getFolder();
        indexer.submit(() -> {
            var items = ItemNames.empty();
            if (folder != null && Files.isRegularFile(folder.resolve("item_pch.txt"))) {
                try {
                    items = ItemNames.read(folder.resolve("item_pch.txt"));
                } catch (IOException | RuntimeException failure) {
                    LogManager.getLogger(SpawnWorkspace.class)
                            .warn("Could not read item_pch.txt: {}", failure.toString());
                }
            }
            models.setScript(npcs, items);
        });
        var document = source.getDocument();
        if (document != null) {
            refreshStyles(document);
        }
    }

    public boolean consumeGeodataRequest() {
        var requested = geodataRequested;
        geodataRequested = false;
        return requested;
    }

    public @Nullable String getUnavailableReason() {
        return switch (source.getPhase()) {
            case EMPTY -> "Open a script folder with npcpos.txt first: Choose folder… in the NPC Spawns panel.";
            case LOADING -> "Wait a moment: npcpos.txt and npcdata.txt are being read.";
            case FAILED -> "The script folder could not be read. Choose another one in the NPC Spawns panel.";
            case READY -> index == null ? "Wait a moment: the spawns are being sorted." : null;
        };
    }

    public @Nullable String showNearestMaker(Camera camera) {
        var document = source.getDocument();
        if (document == null || index == null) {
            return null;
        }
        var state = document.getState();
        refreshNearby(state, camera);
        if (nearest.isEmpty()) {
            return null;
        }
        var makerIndex = nearest.getFirst().getKey();
        selectedMaker = makerIndex;
        selectedSlot = -1;
        visible = true;
        focus(camera, makerIndex);
        return state.makers().get(makerIndex).displayName();
    }

    public @Nullable String showNearestFixedNpc(Camera camera) {
        var document = source.getDocument();
        var currentIndex = index;
        if (document == null || currentIndex == null) {
            return null;
        }
        var state = document.getState();
        var eye = camera.getPosition();
        Placement best = null;
        var bestDistance = Double.MAX_VALUE;
        for (var placement : shownPlacements(state, currentIndex, eye.getX(), eye.getY(), nearbyReach())) {
            var maker = state.makers().get(placement.makerIndex());
            if (maker == null || !(maker.npcs().get(placement.slot()).position() instanceof Fixed)) {
                continue;
            }
            var distance = Math.hypot(placement.x() - eye.getX(), placement.y() - eye.getY());
            if (distance < bestDistance) {
                best = placement;
                bestDistance = distance;
            }
        }
        if (best == null) {
            return null;
        }
        selectedMaker = best.makerIndex();
        selectedSlot = best.slot();
        visible = true;
        focus(camera, best.makerIndex());
        return state.makers().get(best.makerIndex()).npcs().get(best.slot()).npcName();
    }

    public @Nullable String findNearestNpc(Camera camera) {
        var document = source.getDocument();
        if (document == null || index == null) {
            return null;
        }
        var state = document.getState();
        refreshNearby(state, camera);
        for (var entry : nearest) {
            var npcs = state.makers().get(entry.getKey()).npcs();
            if (!npcs.isEmpty()) {
                return npcs.getFirst().npcName();
            }
        }
        return null;
    }

    public boolean requestLeave(Leave action) {
        if (!source.isModified()) {
            return false;
        }
        pendingLeave = action;
        return true;
    }

    public void drawGuard(@Nullable ImFont mono) {
        drawReview(mono);
        if (pendingLeave == null) {
            return;
        }
        var document = source.getDocument();
        var errors = document == null ? List.<Issue>of() : SaveReviewDialog.filterErrors(findIssues(document));
        switch (LeaveGuardDialog.draw("Unsaved spawn changes", "npcpos.txt has edits that are not saved.", errors)) {
            case SAVE -> {
                source.save(reviewDialog.isBackup());
                approvedLeave = null;
                pendingLeave = null;
            }
            case DISCARD -> {
                if (document != null) {
                    document.discardChanges();
                }
                approvedLeave = pendingLeave;
                pendingLeave = null;
            }
            case CANCEL -> pendingLeave = null;
            case NONE -> {}
        }
    }

    public @Nullable Leave consumeApprovedLeave() {
        var leave = approvedLeave;
        approvedLeave = null;
        return leave;
    }

    public SpawnAccess createAccess(NpcDataSource npcData, BooleanSupplier npcHeld) {
        return new SpawnAccess() {
            @Override
            public SpawnSource getSource() {
                return source;
            }

            @Override
            public NpcDataSource getNpcData() {
                return npcData;
            }

            @Override
            public boolean isHeld() {
                return drag != null || review != null;
            }

            @Override
            public boolean isNpcHeld() {
                return npcHeld.getAsBoolean();
            }

            @Override
            public List<String> getBookmarks() {
                loadBookmarks();
                return List.copyOf(bookmarks);
            }

            @Override
            public boolean bookmark(SpawnMaker maker, boolean on) {
                if (isBookmarked(maker) != on) {
                    toggleBookmark(maker);
                }
                return isBookmarked(maker) == on;
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
                return ground.findClientFloor(x, y, z);
            }

            @Override
            public Look look(int npcId) {
                models.getModel(npcId);
                return new Look(models.getStatus(npcId), models.getAppearance(npcId));
            }
        };
    }

    @Override
    public void close() {
        source.close();
        ground.close();
        models.close();
        indexer.shutdownNow();
    }

    static final class SpawnGroundFallback {
        private SpawnGroundFallback() {}

        static Drape flat(SpawnTerritory territory, float z) {
            var points = territory.points();
            var flat = new float[(points.size() + 1) * 3];
            var corners = new int[points.size()];
            for (var i = 0; i <= points.size(); i++) {
                var point = points.get(i % points.size());
                flat[i * 3] = point.x();
                flat[i * 3 + 1] = point.y();
                flat[i * 3 + 2] = z;
                if (i < points.size()) {
                    corners[i] = i;
                }
            }
            return new Drape(flat, corners, false);
        }
    }

    private void dropStaleSelection(State state) {
        if (selectedMaker >= 0
                && (selectedMaker >= state.makers().size() || state.makers().get(selectedMaker) == null)) {
            selectedMaker = -1;
            selectedSlot = -1;
            editingMaker = -1;
        }
        if (selectedMaker >= 0
                && selectedSlot >= state.makers().get(selectedMaker).npcs().size()) {
            selectedSlot = -1;
        }
    }

    private void inspect(State state, @Nullable Placement placement) {
        if (placement == null || placement.makerIndex() >= state.makers().size()) {
            return;
        }
        var maker = state.makers().get(placement.makerIndex());
        if (maker == null || placement.slot() >= maker.npcs().size()) {
            return;
        }
        inspected = new Inspected(
                placement.makerIndex(),
                placement.slot(),
                maker.npcs().get(placement.slot()).npcName(),
                placement.x(),
                placement.y(),
                placement.z());
        inspectRequested = true;
    }

    private boolean isInspectedAt(int makerIndex, int slot, double x, double y) {
        var current = inspected;
        return current != null
                && current.makerIndex() == makerIndex
                && current.slot() == slot
                && Math.abs(current.x() - x) < 1
                && Math.abs(current.y() - y) < 1;
    }

    private void refreshStyles(NpcPosDocument document) {
        if (styledDocument != document || styledRevision != document.getRevision()) {
            styledDocument = document;
            styledRevision = document.getRevision();
            makerStyles.clear();
            makerEvents.clear();
            conditionalKnown.clear();
        }
    }

    private SpawnStyle styleOf(State state, int maker) {
        return makerStyles.computeIfAbsent(maker, ignored -> {
            var value = maker < state.makers().size() ? state.makers().get(maker) : null;
            return value == null ? SpawnStyle.UNKNOWN : SpawnStyle.of(value, source.getNpcs());
        });
    }

    private boolean isShown(State state, int maker) {
        if (maker < 0 || maker >= state.makers().size()) {
            return false;
        }
        var value = state.makers().get(maker);
        if (value == null) {
            return false;
        }
        if (!eventFilter.isEmpty()) {
            return maker == selectedMaker || isInEvents(maker, value);
        }
        return (showEvents || maker == selectedMaker || !isConditional(maker, value))
                && (hiddenKinds.isEmpty() || maker == selectedMaker || !hiddenKinds.contains(styleOf(state, maker)));
    }

    private boolean isConditional(int index, SpawnMaker maker) {
        if (!conditionalKnown.get(index)) {
            conditionalMakers.set(index, maker.isConditional());
            conditionalKnown.set(index);
        }
        return conditionalMakers.get(index);
    }

    private boolean isInEvents(int index, SpawnMaker maker) {
        if (!isConditional(index, maker)) {
            return false;
        }
        var event = makerEvents.computeIfAbsent(index, unused -> maker.eventName());
        return !event.isEmpty() && eventFilter.contains(eventKey(false, event))
                || !maker.spawnTime().isEmpty() && eventFilter.contains(eventKey(true, maker.spawnTimeKind()));
    }

    private List<Zone> findVisibleZones(
            State state, SpawnIndex currentIndex, Camera camera, float eyeX, float eyeY, float eyeZ) {
        var reach = viewReach;
        var frustum = camera.getFrustum();
        var zones = new ArrayList<Zone>();
        var loaded = tiles == null ? null : tiles.getBounds();
        var minX = eyeX - reach;
        var minY = eyeY - reach;
        var maxX = eyeX + reach;
        var maxY = eyeY + reach;
        if (loaded != null) {
            minX = Math.min(minX, loaded[0]);
            minY = Math.min(minY, loaded[1]);
            maxX = Math.max(maxX, loaded[2]);
            maxY = Math.max(maxY, loaded[3]);
        }
        var candidates = currentIndex.findTerritoriesNear(minX, minY, maxX, maxY);
        var selectedZones = NO_ZONES;
        if (selectedMaker >= 0 && isShown(state, selectedMaker)) {
            for (var name : state.makers().get(selectedMaker).territoryNames()) {
                var territory = currentIndex.getTerritory(state, name);
                if (territory != null && !contains(candidates, territory.index())) {
                    candidates = Arrays.copyOf(candidates, candidates.length + 1);
                    candidates[candidates.length - 1] = territory.index();
                }
                var zone = currentIndex.getTerritoryIndex(name);
                if (zone >= 0) {
                    selectedZones = Arrays.copyOf(selectedZones, selectedZones.length + 1);
                    selectedZones[selectedZones.length - 1] = zone;
                }
            }
        }
        var territories = state.territories();
        var territoryCount = territories.size();
        refreshShownMakers(state, currentIndex);
        if (viewOrder.length < candidates.length) {
            viewOrder = new long[candidates.length];
        }
        var inView = 0;
        Arrays.fill(groundTiles, (byte) 0);
        var boxes = currentIndex.getBoxes();
        var indexed = currentIndex.getState() == state;
        for (var territoryIndex : candidates) {
            if (territoryIndex >= territoryCount) {
                continue;
            }
            var box = boxes;
            var offset = territoryIndex * SpawnIndex.BOX_FLOATS;
            if (indexed) {
                if (Float.isNaN(box[offset])) {
                    continue;
                }
            } else {
                var territory = territories.get(territoryIndex);
                if (territory == null) {
                    continue;
                }
                if (!currentIndex.isMeasured(territoryIndex, territory)) {
                    SpawnIndex.writeBox(Extent.of(territory), changedBox, 0);
                    box = changedBox;
                    offset = 0;
                }
            }
            var centerX = box[offset + 6];
            var centerY = box[offset + 7];
            var groundKnown = isGroundKnownOnTile(centerX, centerY);
            var selected = selectedZones.length > 0 && contains(selectedZones, territoryIndex);
            if (!selected
                    && !frustum.intersects(
                            box[offset],
                            box[offset + 1],
                            Math.max(box[offset + 2], -40000),
                            box[offset + 3],
                            box[offset + 4],
                            Math.min(box[offset + 5], 40000))) {
                continue;
            }
            var awayX = centerX - eyeX;
            var awayY = centerY - eyeY;
            var away = (float) Math.sqrt(awayX * awayX + awayY * awayY);
            var near = away <= reach + Math.max(box[offset + 3] - box[offset], box[offset + 4] - box[offset + 1]) * .5;
            viewOrder[inView++] = (long) Float.floatToRawIntBits(away) << 32
                    | (long) territoryIndex << 3
                    | (near ? IN_REACH : 0)
                    | (selected ? SELECTED : 0)
                    | (groundKnown ? GROUND_KNOWN : 0);
        }
        Arrays.sort(viewOrder, 0, inView);
        var linesLeft = ZONE_LINES;
        for (var at = 0; at < inView; at++) {
            var away = Float.intBitsToFloat((int) (viewOrder[at] >>> 32));
            var territoryIndex = (int) (viewOrder[at] & 0xffffffffL) >>> 3;
            var selected = (viewOrder[at] & SELECTED) != 0;
            if (linesLeft <= 0 && !selected && (viewOrder[at] & IN_REACH) == 0) {
                continue;
            }
            var territory = territories.get(territoryIndex);
            if (territory == null || territory.points().size() < 3 || territory.domain() && !showDomains) {
                continue;
            }
            var makers = findShownMakers(state, currentIndex, territoryIndex);
            if (makers.length == 0 && !showUnused && !territory.domain()) {
                continue;
            }
            if (!territory.domain() && (viewOrder[at] & GROUND_KNOWN) == 0) {
                continue;
            }
            var extent = currentIndex.getExtent(territoryIndex, territory);
            if (!isInLoadedTiles(extent.minX(), extent.minY(), extent.maxX(), extent.maxY())) {
                continue;
            }
            var style = makers.length == 0 ? SpawnStyle.UNKNOWN : styleOf(state, makers[0]);
            var dragged = drag != null && drag.handle.territory() == territoryIndex;
            var drape = dragged ? ground.createDrapeNow(territory) : ground.getDrape(territory);
            if (drape == null && !selected && away > reach) {
                continue;
            }
            if (drape == null) {
                drape = SpawnGroundFallback.flat(territory, Math.clamp(eyeZ - 160, extent.bottom(), extent.top()));
            }
            var height = eyeZ - drape.level();
            linesLeft -= drape.samples() / outlineStep(Math.sqrt(away * away + height * height))
                    + drape.corners().length
                    + 1;
            zones.add(new Zone(
                    territoryIndex,
                    territory,
                    extent,
                    away,
                    makers,
                    style,
                    drape.points(),
                    drape.corners(),
                    drape.level(),
                    drape.grounded()));
        }
        return zones;
    }

    private boolean isGroundKnownOnTile(double x, double y) {
        var tileX = Math.floorDiv((int) Math.floor(x), LoadedTiles.SIZE) + 20;
        var tileY = Math.floorDiv((int) Math.floor(y), LoadedTiles.SIZE) + 18;
        if (tileX < 0 || tileX >= GROUND_TILES || tileY < 0 || tileY >= GROUND_TILES) {
            return isGroundKnown(x, y);
        }
        var slot = tileX * GROUND_TILES + tileY;
        if (groundTiles[slot] == 0) {
            groundTiles[slot] = (byte) (isGroundKnown(x, y) ? 1 : 2);
        }
        return groundTiles[slot] == 1;
    }

    private void refreshShownMakers(State state, SpawnIndex currentIndex) {
        var npcs = source.getNpcs();
        if (state == shownState
                && currentIndex == shownIndex
                && npcs == shownNpcs
                && selectedMaker == shownSelected
                && showEvents == shownEvents
                && hiddenKinds.equals(shownHidden)
                && eventFilter.equals(shownEventFilter)) {
            return;
        }
        shownMakers = new int[state.territories().size()][];
        shownState = state;
        shownIndex = currentIndex;
        shownNpcs = npcs;
        shownSelected = selectedMaker;
        shownEvents = showEvents;
        shownHidden = EnumSet.copyOf(hiddenKinds);
        shownEventFilter = new LinkedHashSet<>(eventFilter);
    }

    private int[] findShownMakers(State state, SpawnIndex currentIndex, int territoryIndex) {
        var kept = territoryIndex < shownMakers.length ? shownMakers[territoryIndex] : null;
        if (kept != null) {
            return kept;
        }
        var makers = currentIndex.getMakersOf(territoryIndex);
        var shown = 0;
        for (var maker : makers) {
            if (isShown(state, maker)) {
                shown++;
            }
        }
        var result = makers;
        if (shown < makers.length) {
            result = new int[shown];
            var next = 0;
            for (var maker : makers) {
                if (isShown(state, maker)) {
                    result[next++] = maker;
                }
            }
        }
        if (territoryIndex < shownMakers.length) {
            shownMakers[territoryIndex] = result;
        }
        return result;
    }

    private List<Integer> previewedMakers() {
        var makers = new LinkedHashSet<Integer>();
        if (selectedMaker >= 0) {
            makers.add(selectedMaker);
        }
        if (panelHoverMaker >= 0) {
            makers.add(panelHoverMaker);
        }
        var finding = healthSelected;
        if (finding != null && selectedMaker < 0) {
            makers.add(finding.maker());
        }
        if (hover != null) {
            if (hover.placement() != null) {
                makers.add(hover.placement().makerIndex());
            } else {
                for (var maker : hover.makers()) {
                    makers.add(maker);
                }
            }
        }
        return List.copyOf(makers);
    }

    private @Nullable Hover pick(
            State state,
            List<Zone> zones,
            float eyeX,
            float eyeY,
            double ox,
            double oy,
            double oz,
            double dx,
            double dy,
            double dz,
            float mouseX,
            float mouseY) {
        Placement nearest = null;
        var nearestDistance = Double.MAX_VALUE;
        for (var npc : drawnNpcs) {
            if (npc.makerIndex() >= state.makers().size()
                    || state.makers().get(npc.makerIndex()) == null
                    || npc.slot() >= state.makers().get(npc.makerIndex()).npcs().size()
                    || !isOverNpc(npc, mouseX, mouseY)) {
                continue;
            }
            var distance = Math.hypot(npc.x() - eyeX, npc.y() - eyeY);
            if (distance < nearestDistance) {
                nearest = new Placement(
                        npc.makerIndex(),
                        npc.slot(),
                        (int) Math.round(npc.x()),
                        (int) Math.round(npc.y()),
                        (int) Math.round(npc.ground()),
                        0,
                        100);
                nearestDistance = distance;
            }
        }
        if (nearest != null) {
            return new Hover(-1, new int[] {nearest.makerIndex()}, nearest);
        }
        var aimed = Math.abs(dz) < 1e-6
                ? null
                : ground.findRayGround(new GeoPoint(ox, oy, oz), new GeoPoint(dx, dy, dz), viewReach * 2.5);
        if (aimed != null) {
            Zone smallest = null;
            for (var zone : zones) {
                var territory = zone.territory();
                if (!showZones && !(selectedMaker >= 0 && contains(zone.makers(), selectedMaker))
                        || !territory.containsXy(aimed.x(), aimed.y())
                        || aimed.z() < territory.bottom() - 64
                        || aimed.z() > territory.top() + 64) {
                    continue;
                }
                if (smallest == null || territory.area() < smallest.territory().area()) {
                    smallest = zone;
                }
            }
            if (smallest != null) {
                return new Hover(smallest.index(), smallest.makers(), null);
            }
        }
        Zone best = null;
        var bestT = Double.MAX_VALUE;
        for (var zone : zones) {
            if (Math.abs(dz) < 1e-6 || !showZones && !(selectedMaker >= 0 && contains(zone.makers(), selectedMaker))) {
                continue;
            }
            var t = (zone.groundZ() - oz) / dz;
            if (t <= 0 || t > viewReach * 2.5) {
                continue;
            }
            var x = ox + dx * t;
            var y = oy + dy * t;
            if (!zone.territory().containsXy(x, y)) {
                continue;
            }
            if (best == null
                    || t < bestT - 64
                    || Math.abs(t - bestT) <= 64
                            && zone.territory().area() < best.territory().area()) {
                best = zone;
                bestT = t;
            }
        }
        return best == null ? null : new Hover(best.index(), best.makers(), null);
    }

    private void toggleChosen(State state, Hover clicked) {
        var makers = new ArrayList<Integer>();
        if (clicked.placement() != null) {
            makers.add(clicked.placement().makerIndex());
        } else {
            for (var maker : clicked.makers()) {
                if (isShown(state, maker)) {
                    makers.add(maker);
                }
            }
        }
        if (makers.isEmpty()) {
            return;
        }
        if (chosen.containsAll(makers)) {
            makers.forEach(chosen::remove);
        } else {
            chosen.addAll(makers);
        }
        anchor = makers.getLast();
    }

    private void handleClick(@Nullable Hover clicked) {
        if (clicked == null) {
            selectedMaker = -1;
            selectedSlot = -1;
            lastClickedTerritory = -1;
            return;
        }
        if (clicked.placement() != null) {
            selectedMaker = clicked.placement().makerIndex();
            selectedSlot = clicked.placement().slot();
            return;
        }
        if (clicked.makers().length == 0) {
            return;
        }
        cycle = clicked.territory() == lastClickedTerritory ? cycle + 1 : 0;
        lastClickedTerritory = clicked.territory();
        selectedMaker = clicked.makers()[cycle % clicked.makers().length];
        selectedSlot = -1;
        confirmDelete = false;
    }

    private void drawZones(SpawnOverlayState overlay, List<Zone> zones, float eyeZ) {
        var hovered = hover == null ? -1 : hover.territory();
        for (var zone : zones) {
            var selected = selectedMaker >= 0 && contains(zone.makers(), selectedMaker);
            var picked = !selected && isAnyChosen(zone.makers());
            if (!showZones && !selected && !picked) {
                continue;
            }
            var focused = zone.index() == hovered
                    || selected
                    || hover != null
                            && hover.placement() != null
                            && contains(zone.makers(), hover.placement().makerIndex());
            var away = zone.away();
            var far = away > viewReach * .5;
            var height = eyeZ - zone.groundZ();
            var range = Math.sqrt(away * away + height * height);
            var lift = 4 + (float) range * .004f;
            var argb = zone.makers().length == 0 ? 0xFF8A96A3 : zone.style().argb;
            var lineAlpha = selected || picked ? 1f : focused ? .95f : far ? .7f : .82f;
            var width = selected ? 3.2f : picked ? 3f : focused ? 2.6f : 2f;
            if (quiet) {
                lineAlpha = .5f;
                width = 1.3f;
            }
            var color = picked
                    ? ImColor.rgba(255, 255, 255, 255)
                    : SpawnStyle.getImguiColor(argb, lineAlpha, focused ? 1.12f : 1f);
            var points = zone.points();
            var step = focused || picked ? 1 : outlineStep(range);
            var kept = SpawnShapes.thinOutline(points.length / 3, zone.corners(), step);
            for (var k = 0; k + 1 < kept.length; k++) {
                var i = kept[k];
                var j = kept[k + 1];
                overlay.lines.addLine(
                        points[i * 3],
                        points[i * 3 + 1],
                        points[i * 3 + 2] + lift,
                        points[j * 3],
                        points[j * 3 + 1],
                        points[j * 3 + 2] + lift,
                        color,
                        width);
            }
            if (!quiet && (focused || picked || away <= distance[0])) {
                var alpha = selected ? .42f : focused || picked ? .34f : far ? .12f : .17f;
                SpawnShapes.addCurtain(overlay.fills, points, lift, focused ? CURTAIN * 1.4f : CURTAIN, argb, alpha);
            }
        }
    }

    private boolean isAnyChosen(int[] makers) {
        if (chosen.isEmpty()) {
            return false;
        }
        for (var maker : makers) {
            if (chosen.contains(maker)) {
                return true;
            }
        }
        return false;
    }

    private record Label(double x, double y, double z, String text, int color, double distance, boolean focused) {}

    private record DrawnNpc(
            double x, double y, double ground, double height, double radius, int makerIndex, int slot) {}

    private record Candidate(ModelInstance instance, double order, BodyCandidate capsule) {}

    private double npcReach(double height) {
        return Math.min(distance[0], projector.getDistanceFor(height, NPC_POINTS));
    }

    private double feetHeight(double x, double y, double z, @Nullable Npc info) {
        if (clientFloors == null) {
            return z;
        }
        var floor = info == null
                ? clientFloors.getRestHeight(x, y, z, 0, 0)
                : clientFloors.getRestHeight(x, y, z, info.collisionRadius(), info.collisionHeight());
        return Double.isNaN(floor) ? z : floor;
    }

    private record BodyCandidate(Body body, double distance) {}

    private List<Label> drawNpcs(
            SpawnOverlayState overlay,
            State state,
            SpawnIndex currentIndex,
            List<Zone> zones,
            List<Integer> previewed,
            float eyeX,
            float eyeY,
            float eyeZ) {
        var makers = new LinkedHashSet<Integer>(previewed);
        var search = npcReach(SEARCH_HEIGHT);
        for (var zone : zones) {
            var extent = zone.extent();
            var reach = search + Math.max(extent.maxX() - extent.minX(), extent.maxY() - extent.minY()) * .5;
            if (zone.away() < reach) {
                for (var maker : zone.makers()) {
                    makers.add(maker);
                }
            }
        }
        for (var placement :
                currentIndex.findPlacementsNear(eyeX - search, eyeY - search, eyeX + search, eyeY + search)) {
            if (isShown(state, placement.makerIndex())) {
                makers.add(placement.makerIndex());
            }
        }

        var bodies = new ArrayList<BodyCandidate>();
        var modeled = new ArrayList<double[]>();
        var labels = new ArrayList<Label>();
        var drawn = new ArrayList<DrawnNpc>();
        var npcs = source.getNpcs();
        for (var makerIndex : makers) {
            if (makerIndex >= state.makers().size() || state.makers().get(makerIndex) == null) {
                continue;
            }
            var maker = state.makers().get(makerIndex);
            var focused = previewed.contains(makerIndex);
            var candidates = maker.npcs().stream().anyMatch(SpawnNpc::isAnywhere)
                    ? findCandidates(state, currentIndex, makerIndex, maker)
                    : null;
            for (var placed : SpawnPreview.place(maker, candidates, seed + makerIndex)) {
                var distance = Math.hypot(placed.x() - eyeX, placed.y() - eyeY);
                if (distance > search + 600
                        || !isInLoadedTiles(placed.x(), placed.y())
                        || !isGroundKnown(placed.x(), placed.y())) {
                    continue;
                }
                var info = npcs.getNpc(placed.npcName());
                var ground = feetHeight(placed.x(), placed.y(), placed.z(), info);
                var style = maker.isConditional() ? SpawnStyle.EVENT : SpawnStyle.of(info);
                var radius = info == null ? 12 : Math.clamp(info.collisionRadius(), 4, 160);
                double top = ground + (info == null ? 40 : Math.clamp(info.collisionHeight() * 2, radius * 2, 500));
                var emphasis = makerIndex == selectedMaker && placed.slot() == selectedSlot
                        || isInspectedAt(makerIndex, placed.slot(), placed.x(), placed.y());
                var model = showModels && info != null ? models.getModel(info.id()) : null;
                var height = model != null ? Math.max(model.height(), 10) : top - ground;
                var eyeDistance = Math.sqrt(
                        Math.pow(placed.x() - eyeX, 2) + Math.pow(placed.y() - eyeY, 2) + Math.pow(ground - eyeZ, 2));
                if (eyeDistance > npcReach(height)) {
                    continue;
                }
                var yaw = (float) (placed.heading() * Math.PI * 2 / 65536);
                var capsule = new BodyCandidate(
                        new Body(
                                placed.x(),
                                placed.y(),
                                ground,
                                radius,
                                top - ground,
                                emphasis ? 0xFFFFFFFF & style.argb | 0x00202020 : style.argb,
                                .94f),
                        distance);
                if (model != null) {
                    var base = ground + info.collisionHeight() - model.lowest();
                    modelQueue.add(new Candidate(
                            new ModelInstance(model, (float) placed.x(), (float) placed.y(), (float) base, yaw, 1f),
                            eyeDistance / height,
                            capsule));
                    top = base + Math.max(model.height(), 10);
                } else if (info != null && showModels && models.getStatus(info.id()) == SpawnModels.Status.INVISIBLE) {
                    bodies.add(new BodyCandidate(
                            new Body(placed.x(), placed.y(), ground, radius, top - ground, 0xFFC8D2DC, .16f),
                            distance));

                } else {
                    bodies.add(capsule);
                }
                if (eyeDistance < SHADOW_DISTANCE
                        && (model != null
                                || info == null
                                || models.getStatus(info.id()) != SpawnModels.Status.INVISIBLE)) {
                    drawShadow(
                            overlay,
                            placed.x(),
                            placed.y(),
                            ground,
                            radius,
                            model != null && overlay.sunShadows ? CONTACT_SHADOW_ALPHA : 120);
                }
                drawn.add(
                        new DrawnNpc(placed.x(), placed.y(), ground, top - ground, radius, makerIndex, placed.slot()));
                var hovered = hover != null
                        && hover.placement() != null
                        && hover.placement().makerIndex() == makerIndex
                        && hover.placement().slot() == placed.slot();
                var picked = chosen.contains(makerIndex)
                        && maker.npcs().get(placed.slot()).position() instanceof Fixed;
                if (emphasis || hovered || picked) {
                    drawRing(
                            overlay,
                            placed.x(),
                            placed.y(),
                            ground,
                            radius + 8,
                            emphasis
                                    ? HudWidgets.ACCENT
                                    : picked ? ImColor.rgba(255, 255, 255, 255) : ImColor.rgba(255, 255, 255, 170),
                            emphasis || picked ? 3 : 2);
                }
                if (!focused && !emphasis && eyeDistance > NAME_DISTANCE) {
                    continue;
                }
                var text = worldName(placed.npcName(), info)
                        + (focused && placed.count() > 1 ? "  \u00d7" + placed.count() : "");
                labels.add(new Label(
                        placed.x(),
                        placed.y(),
                        top + 14,
                        text,
                        focused || emphasis ? style.getImguiColor(1f, 1.15f) : style.getImguiColor(.75f, 1f),
                        eyeDistance,
                        focused || emphasis));
            }
        }
        drawnNpcs = drawn;
        modelQueue.sort(Comparator.comparingDouble(Candidate::order));
        for (var i = 0; i < modelQueue.size(); i++) {
            var capsule = modelQueue.get(i).capsule();
            if (i < MAX_MODELS) {
                modeled.add(new double[] {
                    capsule.body().x(), capsule.body().y(), capsule.body().z()
                });
            } else {
                bodies.add(capsule);
            }
        }
        bodies.replaceAll(candidate -> fadeNearModels(candidate, modeled));
        bodies.sort(Comparator.comparingDouble(BodyCandidate::distance));
        SpawnShapes.addBodies(
                overlay.bodies,
                bodies.stream().limit(MAX_BODIES).map(BodyCandidate::body).toList(),
                eyeX,
                eyeY,
                eyeZ);
        labels.sort(Comparator.comparing((Label label) -> !label.focused()).thenComparingDouble(Label::distance));
        return labels.size() > MAX_LABELS ? labels.subList(0, MAX_LABELS) : labels;
    }

    private String clientNote(@Nullable Npc info) {
        if (info == null || !showModels) {
            return "";
        }
        return switch (models.getStatus(info.id())) {
            case INVISIBLE -> " · invisible in game";
            case NOT_IN_CLIENT -> " · not in the client";
            case MISSING -> models.getMessage().isEmpty() ? " · no model" : "";
            default -> "";
        };
    }

    private @Nullable Candidates findCandidates(
            State state, SpawnIndex currentIndex, int makerIndex, SpawnMaker maker) {
        var previous = shownCandidates.get(makerIndex);
        var active = drag;
        if (active != null && previous != null) {
            if (active.keepNpcs && active.handle.territory() >= 0 && active.applied) {
                var territory = state.territories().get(active.handle.territory());
                if (territory != null
                        && maker.territoryNames().contains(territory.name())
                        && !active.points.isEmpty()) {
                    return shifted(
                            previous,
                            territory.points().getFirst().x()
                                    - active.points.getFirst().x(),
                            territory.points().getFirst().y()
                                    - active.points.getFirst().y());
                }
            }
            return previous;
        }
        var pinned = pinnedCandidates.get(makerIndex);
        if (pinned != null) {
            var zone = pinnedZones.get(makerIndex);
            if (zone != null
                    && zone.index() < state.territories().size()
                    && state.territories().get(zone.index()) == zone) {
                return pinned;
            }
            pinnedCandidates.remove(makerIndex);
            pinnedZones.remove(makerIndex);
        }
        var current = ground.getCandidates(candidateKey(state, currentIndex, maker));
        if (current == null) {
            return previous;
        }
        if (shownCandidates.size() >= 4096) {
            shownCandidates.clear();
        }
        shownCandidates.put(makerIndex, current);
        return current;
    }

    private boolean isOverNpc(DrawnNpc npc, float mouseX, float mouseY) {
        var ground = npc.ground();
        var radius = npc.radius();
        var height = Math.max(npc.height(), 1);
        if (!projector.project(npc.x(), npc.y(), ground, screen)) {
            return false;
        }
        var footX = screen[0];
        var footY = screen[1];
        if (!projector.project(npc.x(), npc.y(), ground + height, screen)) {
            return false;
        }
        var topX = screen[0];
        var topY = screen[1];
        var length = Math.hypot(topX - footX, topY - footY);
        if (length < NPC_POINTS) {
            return false;
        }
        var halfWidth = Math.max(HANDLE_PIXELS + 3, length * radius / height);
        var t = length < 1e-3
                ? 0
                : Math.clamp(
                        ((mouseX - footX) * (topX - footX) + (mouseY - footY) * (topY - footY)) / (length * length),
                        0,
                        1);
        var nearX = footX + (topX - footX) * t;
        var nearY = footY + (topY - footY) * t;
        return Math.hypot(mouseX - nearX, mouseY - nearY) <= halfWidth;
    }

    private void followBuild(@Nullable Path geodataFolder) {
        var progress = GeodataBuildJob.getShared().getProgress();
        var building = progress.running() && GeodataBuildControls.isSameFolder(progress.folder(), geodataFolder);
        var written = building ? progress.written() : -1;
        if (building && written != builtRegions || !building && geodataBuilding) {
            ground.refreshMissing();
        }
        geodataBuilding = building;
        builtRegions = written;
        this.geodataFolder = geodataFolder;
    }

    private boolean isGroundKnown(double x, double y) {
        return ground.isReady(x, y);
    }

    private boolean isInLoadedTiles(double x, double y) {
        var current = tiles;
        return current == null || current.isEmpty() || current.contains(x, y);
    }

    private boolean isInLoadedTiles(double minX, double minY, double maxX, double maxY) {
        var current = tiles;
        return current == null || current.isEmpty() || current.intersects(minX, minY, maxX, maxY);
    }

    private boolean isNotLoaded(State state, SpawnIndex currentIndex, int makerIndex) {
        var maker = makerIndex < state.makers().size() ? state.makers().get(makerIndex) : null;
        if (maker == null) {
            return false;
        }
        var placed = false;
        for (var name : maker.territoryNames()) {
            var territory = currentIndex.getTerritory(state, name);
            if (territory != null) {
                if (isInLoadedTiles(territory.minX(), territory.minY(), territory.maxX(), territory.maxY())) {
                    return false;
                }
                placed = true;
            }
        }
        for (var npc : maker.npcs()) {
            if (npc.position() instanceof Fixed fixed) {
                if (isInLoadedTiles(fixed.x(), fixed.y())) {
                    return false;
                }
                placed = true;
            }
        }
        return placed;
    }

    private BitSet findOutsideMakers(State state, SpawnIndex currentIndex) {
        var current = tiles;
        var revision = current == null ? -1 : current.getRevision();
        if (state != outsideState || currentIndex != outsideIndex || revision != outsideRevision) {
            var makers = new BitSet();
            for (var i = 0; i < state.makers().size(); i++) {
                if (isNotLoaded(state, currentIndex, i)) {
                    makers.set(i);
                }
            }
            outsideMakers = makers;
            outsideState = state;
            outsideIndex = currentIndex;
            outsideRevision = revision;
            scope++;
        }
        return outsideMakers;
    }

    private boolean isListed(State state, int maker) {
        var currentIndex = index;
        return currentIndex == null || !findOutsideMakers(state, currentIndex).get(maker);
    }

    private long listedVersion(State state) {
        var currentIndex = index;
        if (currentIndex == null) {
            return -1;
        }
        findOutsideMakers(state, currentIndex);
        return scope;
    }

    private CandidateKey candidateKey(State state, SpawnIndex currentIndex, SpawnMaker maker) {
        var territories = new ArrayList<SpawnTerritory>();
        for (var name : maker.territoryNames()) {
            var territory = currentIndex.getTerritory(state, name);
            if (territory != null) {
                territories.add(territory);
            }
        }
        var banned = new ArrayList<SpawnTerritory>();
        for (var name : maker.bannedTerritoryNames()) {
            var territory = currentIndex.getTerritory(state, name);
            if (territory != null) {
                banned.add(territory);
            }
        }
        return new CandidateKey(territories, banned, maker.maximumNpc(), maker.flying());
    }

    private void drawHandle(SpawnOverlayState overlay, Handle handle, boolean hot) {
        var point = new GeoPoint(handle.x(), handle.y(), handle.z());
        var white = ImColor.rgba(255, 255, 255, hot ? 255 : 225);
        switch (handle.grab()) {
            case CORNER -> WorldHandles.corner(overlay.lines, point, hot);
            case MIDPOINT -> WorldHandles.midpoint(overlay.lines, point, hot);
            case ZONE -> WorldHandles.move(overlay.lines, point, hot);
            case NPC -> overlay.lines.handle(point, 4, hot ? white : ImColor.rgba(255, 255, 255, 170), hot ? 12 : 9);
            case HEADING ->
                overlay.lines.handle(point, 4, hot ? HudWidgets.ACCENT : ImColor.rgba(92, 219, 241, 190), hot ? 11 : 8);
        }
    }

    private List<Handle> createHandles(State state, List<Zone> zones, boolean editing, float eyeX, float eyeY) {
        var handles = new ArrayList<Handle>();
        var maker = state.makers().get(selectedMaker);
        for (var zone : zones) {
            if (!editing
                    || !maker.territoryNames().contains(zone.territory().name())
                    || zone.territory().domain()) {
                continue;
            }
            var points = zone.points();
            var corners = zone.corners();
            for (var i = 0; i < corners.length; i++) {
                var at = corners[i] * 3;
                handles.add(new Handle(
                        Grab.CORNER, zone.index(), i, selectedMaker, -1, points[at], points[at + 1], points[at + 2]));
                var next = (i + 1 < corners.length ? corners[i + 1] : points.length / 3 - 1) * 3;
                handles.add(new Handle(
                        Grab.MIDPOINT,
                        zone.index(),
                        i,
                        selectedMaker,
                        -1,
                        (points[at] + points[next]) * .5,
                        (points[at + 1] + points[next + 1]) * .5,
                        (points[at + 2] + points[next + 2]) * .5));
            }
        }
        for (var slot = 0; slot < maker.npcs().size(); slot++) {
            if ((editing || slot == selectedSlot) && maker.npcs().get(slot).position() instanceof Fixed fixed) {
                if (Math.hypot(fixed.x() - eyeX, fixed.y() - eyeY) > npcReach(60)
                        || !isInLoadedTiles(fixed.x(), fixed.y())
                        || !isGroundKnown(fixed.x(), fixed.y())) {
                    continue;
                }
                var z = feetHeight(
                        fixed.x(),
                        fixed.y(),
                        fixed.z(),
                        source.getNpcs().getNpc(maker.npcs().get(slot).npcName()));
                var feet = new GeoPoint(fixed.x(), fixed.y(), z);
                handles.add(new Handle(Grab.NPC, -1, -1, selectedMaker, slot, fixed.x(), fixed.y(), z));
                if (slot == selectedSlot) {
                    var info = source.getNpcs().getNpc(maker.npcs().get(slot).npcName());
                    var reach = Math.clamp((info == null ? 12 : info.collisionRadius()) + 22, 30, 70);
                    var tip = pointAhead(feet, fixed.heading(), reach);
                    handles.add(new Handle(Grab.HEADING, -1, -1, selectedMaker, slot, tip.x(), tip.y(), tip.z()));
                }
            }
        }
        return handles;
    }

    private @Nullable Handle handleAt(List<Handle> handles, float mouseX, float mouseY) {
        Handle best = null;
        var bestDistance = (double) HANDLE_PIXELS;
        for (var handle : handles) {
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

    private void continueDrag(
            NpcPosDocument document,
            Drag active,
            double ox,
            double oy,
            double oz,
            double dx,
            double dy,
            double dz,
            float mouseX,
            float mouseY) {
        if (!active.applied && Math.hypot(mouseX - active.pressX, mouseY - active.pressY) < 3) {
            return;
        }
        var handle = active.handle;
        if (Math.abs(dz) < 1e-6) {
            return;
        }
        var t = (handle.z() - oz) / dz;
        if (t <= 0) {
            return;
        }
        var x = ox + dx * t;
        var y = oy + dy * t;
        var state = document.getState();
        switch (handle.grab()) {
            case CORNER ->
                editTerritory(document, active, "Move zone corner", handle.territory(), points -> {
                    var moved = new ArrayList<>(active.points);
                    moved.set(
                            handle.corner(),
                            active.points.get(handle.corner()).withXy((int) Math.round(x), (int) Math.round(y)));
                    return moved;
                });
            case MIDPOINT -> {
                if (!active.applied) {
                    insertCorner(document, handle, x, y);
                    active.applied = true;
                    return;
                }
                editTerritory(document, active, "Add zone corner", handle.territory(), points -> {
                    var moved = new ArrayList<>(points);
                    moved.set(
                            handle.corner() + 1,
                            points.get(handle.corner() + 1).withXy((int) Math.round(x), (int) Math.round(y)));
                    return moved;
                });
            }
            case ZONE -> {
                var shiftX = (int) Math.round(x - handle.x());
                var shiftY = (int) Math.round(y - handle.y());
                moveZone(document, active, shiftX, shiftY);
            }
            case NPC -> {
                if (!(active.position instanceof Fixed fixed)) {
                    return;
                }
                var inside = insideZones(makerZones(document.getState(), handle.maker()), x, y);
                var groundZ = ground.getGroundAt(inside[0], inside[1], fixed.z() + 64);
                var z = Double.isNaN(groundZ) ? fixed.z() : (int) Math.round(groundZ);
                editNpc(
                        document,
                        active,
                        "Move NPC",
                        handle.maker(),
                        handle.slot(),
                        new Fixed((int) Math.round(inside[0]), (int) Math.round(inside[1]), z, fixed.heading()));
            }
            case HEADING -> {
                if (!(active.position instanceof Fixed fixed)) {
                    return;
                }
                var angle = Math.atan2(y - fixed.y(), x - fixed.x());
                var heading = Math.floorMod((int) Math.round(angle / (Math.PI * 2) * 65536), 65536);
                editNpc(
                        document,
                        active,
                        "Turn NPC",
                        handle.maker(),
                        handle.slot(),
                        new Fixed(fixed.x(), fixed.y(), fixed.z(), heading));
            }
        }
        if (state != document.getState()) {
            makerStyles.clear();
            conditionalKnown.clear();
        }
    }

    private List<SpawnTerritory> makerZones(State state, int makerIndex) {
        var currentIndex = index;
        var zones = new ArrayList<SpawnTerritory>();
        if (currentIndex == null
                || makerIndex < 0
                || makerIndex >= state.makers().size()
                || state.makers().get(makerIndex) == null) {
            return zones;
        }
        for (var name : state.makers().get(makerIndex).territoryNames()) {
            var territory = currentIndex.getTerritory(state, name);
            if (territory != null && territory.points().size() >= 3) {
                zones.add(territory);
            }
        }
        return zones;
    }

    private boolean tryStartZoneDrag(
            State state,
            List<Zone> zones,
            @Nullable Hover pressed,
            double ox,
            double oy,
            double oz,
            double dx,
            double dy,
            double dz,
            float mouseX,
            float mouseY) {
        if (selectedMaker < 0
                || pressed == null
                || pressed.placement() != null
                || pressed.territory() < 0
                || Math.abs(dz) < 1e-6) {
            return false;
        }
        var maker = state.makers().get(selectedMaker);
        var territory = state.territories().get(pressed.territory());
        if (maker == null
                || territory == null
                || !maker.territoryNames().contains(territory.name())
                || territory.domain()) {
            return false;
        }
        Zone zone = null;
        for (var candidate : zones) {
            if (candidate.index() == pressed.territory()) {
                zone = candidate;
            }
        }
        if (zone == null) {
            return false;
        }
        var t = (zone.groundZ() - oz) / dz;
        if (t <= 0) {
            return false;
        }
        var carried = SpawnEdits.carriedBy(state, territory);
        drag = new Drag(
                new Handle(
                        Grab.ZONE, territory.index(), -1, selectedMaker, -1, ox + dx * t, oy + dy * t, zone.groundZ()),
                mouseX,
                mouseY,
                territory.points(),
                SpawnPosition.ANYWHERE,
                carried,
                pressed);
        drag.keepNpcs = ImGui.getIO().getKeyShift();
        return true;
    }

    private void moveZone(NpcPosDocument document, Drag active, int shiftX, int shiftY) {
        var moved = new ArrayList<Carried>();
        for (var npc : active.carried) {
            var x = npc.position().x() + shiftX;
            var y = npc.position().y() + shiftY;
            var groundZ = ground.getGroundAt(x, y, npc.position().z() + 64);
            var z = Double.isNaN(groundZ) ? npc.position().z() : (int) Math.round(groundZ);
            moved.add(new Carried(
                    npc.maker(), npc.slot(), new Fixed(x, y, z, npc.position().heading())));
        }
        var territoryIndex = active.handle.territory();
        var points = SpawnEdits.shifted(active.points, shiftX, shiftY, 0);
        UnaryOperator<State> edit = state -> SpawnEdits.moveZone(state, territoryIndex, points, moved);
        if (active.applied) {
            document.amend(edit);
        } else {
            document.apply("Move zone", edit);
            active.applied = true;
        }
        merge.reset();
    }

    private void editTerritory(
            NpcPosDocument document,
            Drag active,
            String label,
            int territoryIndex,
            UnaryOperator<List<TerritoryPoint>> change) {
        UnaryOperator<State> edit = state -> {
            var territory = state.territories().get(territoryIndex);
            return territory == null
                    ? state
                    : state.withTerritory(territory.withPoints(change.apply(territory.points())));
        };
        if (active.applied) {
            document.amend(edit);
        } else {
            document.apply(label, edit);
            active.applied = true;
        }
        merge.reset();
    }

    private void editNpc(
            NpcPosDocument document, Drag active, String label, int makerIndex, int slot, SpawnPosition position) {
        UnaryOperator<State> edit = state -> {
            var maker = state.makers().get(makerIndex);
            return maker == null || slot >= maker.npcs().size()
                    ? state
                    : state.withNpc(makerIndex, slot, maker.npcs().get(slot).withPosition(position));
        };
        if (active.applied) {
            document.amend(edit);
        } else {
            document.apply(label, edit);
            active.applied = true;
        }
        merge.reset();
    }

    private void insertCorner(NpcPosDocument document, Handle handle, double x, double y) {
        document.apply("Add zone corner", state -> {
            var territory = state.territories().get(handle.territory());
            if (territory == null) {
                return state;
            }
            var points = new ArrayList<>(territory.points());
            var before = points.get(handle.corner());
            var after = points.get((handle.corner() + 1) % points.size());
            points.add(
                    handle.corner() + 1,
                    new TerritoryPoint(
                            (int) Math.round(x),
                            (int) Math.round(y),
                            Math.min(before.bottom(), after.bottom()),
                            Math.max(before.top(), after.top())));
            return state.withTerritory(territory.withPoints(points));
        });
        merge.reset();
    }

    private void removeCorner(NpcPosDocument document, int territoryIndex, int corner) {
        var territory = document.getState().territories().get(territoryIndex);
        if (territory == null || territory.points().size() <= 3) {
            setMessage("A zone needs at least three corners.", GeodataWidgets.WARNING);
            return;
        }
        document.apply("Remove zone corner", state -> {
            var current = state.territories().get(territoryIndex);
            var points = new ArrayList<>(current.points());
            points.remove(corner);
            return state.withTerritory(current.withPoints(points));
        });
        merge.reset();
        hoveredHandle = null;
    }

    private void handleGesture(
            NpcPosDocument document,
            ViewerRenderBackend backend,
            boolean pointerFree,
            float ndcX,
            float ndcY,
            double ox,
            double oy,
            double oz,
            double dx,
            double dy,
            double dz,
            float eyeX,
            float eyeY) {
        hover = null;
        var snap = drawingZone && !ImGui.getIO().getKeyShift();
        if (pointerFree) {
            pointerOwned = true;
        }
        closingHot = drawingZone && isOnFirstCorner(pointerFree);
        if (closingHot && ImGui.isMouseClicked(0)) {
            var waiting = clicks.resolveNow(lastGroundUnder);
            if (waiting != null) {
                applyClick(document, waiting, eyeX, eyeY);
            }
            closeDrawnZone(document);
            return;
        }
        for (var point : clicks.update(
                backend, camera, pointerFree, ndcX, ndcY, ox, oy, oz, dx, dy, dz, snap, lastGroundUnder)) {
            applyClick(document, point, eyeX, eyeY);
        }
        if (drawingZone && !ImGui.getIO().getWantTextInput()) {
            if ((ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter))
                    && drawnCorners.size() + (clicks.isPending() ? 1 : 0) >= 3) {
                var waiting = clicks.resolveNow(lastGroundUnder);
                if (waiting != null) {
                    applyClick(document, waiting, eyeX, eyeY);
                }
                closeDrawnZone(document);
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

    private void applyClick(NpcPosDocument document, double[] point, float eyeX, float eyeY) {
        if (drawingZone) {
            drawnCorners.add(new int[] {
                (int) Math.round(point[0]),
                (int) Math.round(point[1]),
                (int) Math.round(point[2]),
                (int) Math.round(point[3])
            });
        } else if (placingMaker >= 0) {
            placeNpcHere(document, point, eyeX, eyeY);
        }
    }

    private void placeNpcHere(NpcPosDocument document, double[] point, float eyeX, float eyeY) {
        var state = document.getState();
        var makerIndex = placingMaker;
        placingMaker = -1;
        if (makerIndex < 0
                || makerIndex >= state.makers().size()
                || state.makers().get(makerIndex) == null) {
            return;
        }
        var maker = state.makers().get(makerIndex);
        var name = maker.npcs().isEmpty()
                ? source.getNpcs().getNames().isEmpty()
                        ? "gremlin"
                        : source.getNpcs().getNames().getFirst()
                : maker.npcs().getLast().npcName();
        var angle = Math.atan2(eyeY - point[1], eyeX - point[0]);
        var heading = Math.floorMod((int) Math.round(angle / (Math.PI * 2) * 65536), 65536);
        var position =
                new Fixed((int) Math.round(point[0]), (int) Math.round(point[1]), (int) Math.round(point[2]), heading);
        var npc = SpawnEdits.newNpc(makerIndex, name, position, 1, SpawnEdits.DEFAULT_RESPAWN, "");
        var slot = maker.npcs().size();
        document.apply("Add NPC here", current -> SpawnEdits.addNpc(current, makerIndex, npc));
        merge.reset();
        selectedMaker = makerIndex;
        selectedSlot = slot;
    }

    private void drawGesture(SpawnOverlayState overlay) {
        var pointer = clicks.getPointer();
        if (drawingZone) {
            var accent = HudWidgets.ACCENT;
            var closable = drawnCorners.size() >= 3;
            GeoPoint start = null;
            GeoPoint previous = null;
            for (var corner : drawnCorners) {
                var point = new GeoPoint(corner[0], corner[1], corner[3]);
                if (previous != null) {
                    overlay.lines.addLine(previous, point, 6, accent, 2.6f);
                }
                if (start == null) {
                    start = point;
                }
                if (closable && point == start) {
                    WorldHandles.closingCorner(overlay.lines, point, accent, closingHot);
                } else {
                    overlay.lines.handle(point, 6, ImColor.rgba(20, 28, 36, 230), 12);
                    overlay.lines.handle(point, 6, ImColor.rgba(255, 255, 255, 235), 8);
                }
                previous = point;
            }
            if (closingHot && start != null && previous != null) {
                overlay.lines.addLine(previous, start, 6, accent, 2.6f);
            } else if (pointer != null) {
                var at = new GeoPoint(pointer[0], pointer[1], pointer[3]);
                if (previous != null) {
                    overlay.lines.addLine(previous, at, 6, ImColor.rgba(92, 219, 241, 170), 2f);
                }
                if (drawnCorners.size() >= 2) {
                    var first = drawnCorners.getFirst();
                    overlay.lines.addLine(
                            at, new GeoPoint(first[0], first[1], first[3]), 6, ImColor.rgba(92, 219, 241, 80), 1.5f);
                }
                overlay.lines.handle(at, 6, accent, 9);
            }
        } else if (placingMaker >= 0 && pointer != null) {
            drawRing(overlay, pointer[0], pointer[1], pointer[3], 20, HudWidgets.ACCENT, 2.5f);
            overlay.lines.handle(new GeoPoint(pointer[0], pointer[1], pointer[3]), 4, HudWidgets.ACCENT, 8);
        }
    }

    private void drawGestureTag(ImDrawList draw) {
        var pointer = clicks.getPointer();
        if (pointer == null || !(drawingZone || placingMaker >= 0)) {
            return;
        }
        var text = !drawingZone
                ? "Click to place the NPC here, facing the camera"
                : closingHot ? "Click to close the zone" : drawReadout();
        tag(
                draw,
                ImGui.getMousePosX() + ImGui.calcTextSizeX(text) * .5f + 18,
                ImGui.getMousePosY() + 34,
                text,
                HudWidgets.TEXT,
                .95f);
    }

    private void drawCoverage(SpawnOverlayState overlay, State state, SpawnIndex currentIndex) {
        if (!showCoverage
                || selectedMaker < 0
                || selectedMaker >= state.makers().size()) {
            return;
        }
        var maker = state.makers().get(selectedMaker);
        if (maker == null || maker.npcs().stream().noneMatch(SpawnNpc::isAnywhere)) {
            return;
        }
        var candidates = findCandidates(state, currentIndex, selectedMaker, maker);
        if (candidates == null) {
            return;
        }
        var points = candidates.points();
        var many = points.size() > 1500;
        var step = Math.max(1, points.size() / 6000);
        var color = many ? ImColor.rgba(255, 236, 160, 150) : ImColor.rgba(255, 236, 160, 230);
        for (var i = 0; i < points.size(); i += step) {
            var point = points.get(i);
            if (isInLoadedTiles(point.x(), point.y())) {
                overlay.lines.addDot(point, 3, color, many ? 3f : 4.5f);
            }
        }
    }

    private void pinCandidates(State state, Drag finished) {
        var currentIndex = index;
        var territoryIndex = finished.handle.territory();
        if (currentIndex == null || territoryIndex < 0 || finished.points.isEmpty()) {
            return;
        }
        var territory = state.territories().get(territoryIndex);
        if (territory == null) {
            return;
        }
        var dx = territory.points().getFirst().x() - finished.points.getFirst().x();
        var dy = territory.points().getFirst().y() - finished.points.getFirst().y();
        for (var maker : currentIndex.getMakersOf(territoryIndex)) {
            var previous = shownCandidates.get(maker);
            if (previous != null) {
                pinnedCandidates.put(maker, shifted(previous, dx, dy));
                pinnedZones.put(maker, territory);
            }
        }
    }

    private void drawLabels(ImDrawList draw, List<Label> labels, @Nullable float[] reserved) {
        var placed = new ArrayList<float[]>();
        if (reserved != null) {
            placed.add(reserved);
        }
        var height = ImGui.getFontSize() + 6;
        for (var label : labels) {
            if (label.distance() > LABEL_DISTANCE || !projector.project(label.x(), label.y(), label.z(), screen)) {
                continue;
            }
            var width = ImGui.calcTextSizeX(label.text()) + 12;
            var box = new float[] {screen[0] - width * .5f, screen[1] - height, screen[0] + width * .5f, screen[1]};
            var overlaps = false;
            for (var other : placed) {
                if (box[0] < other[2] && box[2] > other[0] && box[1] < other[3] && box[3] > other[1]) {
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) {
                continue;
            }
            placed.add(box);
            var fade = (float) Math.clamp(1.3 - label.distance() / LABEL_DISTANCE, .45, 1);
            tag(draw, screen[0], screen[1], label.text(), label.color(), fade);
        }
    }

    private @Nullable float[] drawZoneTag(ImDrawList draw, List<Zone> zones) {
        var territory = hover != null && hover.placement() == null ? hover.territory() : -1;
        for (var zone : zones) {
            if (zone.index() != territory) {
                continue;
            }
            var bestX = Float.NaN;
            var bestY = Float.MAX_VALUE;
            var points = zone.points();
            for (var i = 0; i + 2 < points.length; i += 3) {
                if (projector.project(points[i], points[i + 1], points[i + 2] + 8, screen) && screen[1] < bestY) {
                    bestX = screen[0];
                    bestY = screen[1];
                }
            }
            if (Float.isNaN(bestX)) {
                return null;
            }
            var text = zone.territory().name();
            tag(draw, bestX, bestY - 4, text, HudWidgets.TEXT, .9f);
            var width = ImGui.calcTextSizeX(text) + 12;
            var height = ImGui.getFontSize() + 6;
            return new float[] {bestX - width * .5f, bestY - 4 - height, bestX + width * .5f, bestY - 4};
        }
        return null;
    }

    private void drawHoverCard(State state) {
        if (inspecting && hover != null && hover.placement() != null) {
            var placement = hover.placement();
            var maker = state.makers().get(placement.makerIndex());
            if (maker != null && placement.slot() < maker.npcs().size()) {
                var name = maker.npcs().get(placement.slot()).npcName();
                HudWidgets.showTooltip(worldName(name, source.getNpcs().getNpc(name)) + "\nClick to see its model.");
            }
            return;
        }
        var makers = new ArrayList<Integer>();
        if (hoveredHandle != null) {
            makers.add(hoveredHandle.maker());
        } else if (hover != null) {
            if (hover.placement() != null) {
                makers.add(hover.placement().makerIndex());
            } else {
                for (var maker : hover.makers()) {
                    makers.add(maker);
                }
            }
        }
        if (hoveredHandle != null) {
            HudWidgets.showTooltip(
                    switch (hoveredHandle.grab()) {
                        case CORNER -> "Drag to move this corner. " + "Delete removes it.";
                        case MIDPOINT -> "Drag or click to add a corner here.";
                        case ZONE -> "Drag to move the whole zone.";
                        case NPC ->
                            "Drag to move this NPC. Its height follows the ground. Double-click to open it in NPCs.";
                        case HEADING -> "Drag to turn this NPC.";
                    });
            return;
        }
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 12, 10);
        ImGui.beginTooltip();
        var npcs = source.getNpcs();
        if (makers.isEmpty() && hover != null && hover.territory() >= 0) {
            var territory = state.territories().get(hover.territory());
            caption("ZONE");
            heading(territory == null ? "Zone" : territory.name());
            ImGui.textColored(
                    GeodataWidgets.MUTED,
                    territory != null && territory.domain() ? "Castle domain" : "No maker spawns here.");
        }
        var first = true;
        for (var makerIndex : makers) {
            if (makerIndex >= state.makers().size() || state.makers().get(makerIndex) == null) {
                continue;
            }
            if (!first) {
                GeodataWidgets.space(GeodataWidgets.BLOCK);
                ImGui.separator();
                GeodataWidgets.space(GeodataWidgets.ROW);
            }
            first = false;
            var maker = state.makers().get(makerIndex);
            var style = styleOf(state, makerIndex);
            caption(maker.isConditional() ? "EVENT SPAWN ZONE" : "SPAWN ZONE");
            var x = ImGui.getCursorScreenPosX();
            var y = ImGui.getCursorScreenPosY();
            var lineHeight = headingFont == null ? ImGui.getFontSize() : 15;
            ImGui.getWindowDrawList().addRectFilled(x, y + 1, x + 3, y + lineHeight + 2, style.getImguiColor(1f), 1.5f);
            ImGui.setCursorScreenPos(x + 10, y);
            heading(String.join(", ", maker.territoryNames()));
            var details = new ArrayList<String>();
            if (!maker.name().isEmpty()) {
                details.add(maker.name());
            }
            if (!maker.ai().isEmpty() && !maker.ai().equals("default_maker")) {
                details.add(maker.ai());
            }
            if (!details.isEmpty()) {
                ImGui.textColored(GeodataWidgets.MUTED, String.join(" · ", details));
            }
            if (!maker.spawnTime().isEmpty()) {
                ImGui.textColored(SpawnStyle.EVENT.getImguiColor(1f), "Only during " + maker.spawnTime());
            } else if (maker.isConditional()) {
                ImGui.textColored(SpawnStyle.EVENT.getImguiColor(1f), "Event spawn");
            }
            GeodataWidgets.space(GeodataWidgets.ROW);
            caption("NPCS");
            var countWidth = 0f;
            for (var slot = 0; slot < Math.min(maker.npcs().size(), 12); slot++) {
                countWidth = Math.max(
                        countWidth,
                        ImGui.calcTextSizeX("\u00d7" + maker.npcs().get(slot).total()));
            }
            var shown = 0;
            for (var slot = 0; slot < maker.npcs().size(); slot++) {
                var npc = maker.npcs().get(slot);
                if (shown++ == 12) {
                    ImGui.textColored(GeodataWidgets.MUTED, "+" + (maker.npcs().size() - 12) + " more");
                    break;
                }
                var info = npcs.getNpc(npc.npcName());
                var highlighted = hover != null
                        && hover.placement() != null
                        && hover.placement().slot() == slot
                        && hover.placement().makerIndex() == makerIndex;
                var kind = SpawnStyle.of(info);
                var rowX = ImGui.getCursorScreenPosX();
                ImGui.textColored(kind.getImguiColor(1f, 1.15f), "\u00d7" + npc.total());
                ImGui.sameLine(rowX - ImGui.getWindowPosX() + countWidth + 6);
                var shownName = worldName(npc.npcName(), info);
                ImGui.textColored(highlighted ? HudWidgets.ACCENT : HudWidgets.TEXT, shownName);
                ImGui.sameLine(0, 8);
                ImGui.textColored(
                        GeodataWidgets.MUTED,
                        (shownName.equals(npc.npcName()) ? "" : npc.npcName() + " · ")
                                + describe(npc, info)
                                + clientNote(info));
            }
        }
        if (!makers.isEmpty()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            ImGui.textColored(
                    GeodataWidgets.FAINT,
                    selecting
                            ? chosen.containsAll(makers)
                                    ? "Click to take out of the selection"
                                    : "Click to choose for a batch edit"
                            : editingMaker >= 0 && makers.contains(editingMaker)
                                    ? "Editing: drag corners, the zone or its NPCs"
                                    : hover != null && hover.placement() != null
                                            ? "Click to select, double-click to open in NPCs"
                                            : "Click to select, double-click to edit");
        }
        ImGui.endTooltip();
        ImGui.popStyleVar();
    }

    private void heading(String text) {
        if (headingFont != null) {
            ImGui.pushFont(headingFont, 15f);
        }
        ImGui.textUnformatted(text);
        if (headingFont != null) {
            ImGui.popFont();
        }
    }

    private String worldName(String serverName, @Nullable Npc info) {
        if (!gameNames || info == null) {
            return serverName;
        }
        var client = catalog.getNpc(info.id());
        return client == null || client.name().isBlank() ? serverName : client.name();
    }

    private String gameName(Npc info) {
        var client = catalog.getNpc(info.id());
        return client == null ? "" : client.name().strip();
    }

    private String gameName(String serverName) {
        var info = source.getNpcs().getNpc(serverName);
        return info == null ? "" : gameName(info);
    }

    private Map<String, List<int[]>> findNpcPlaces(State state) {
        if (state != placesState) {
            var found = new HashMap<String, List<int[]>>();
            for (var makerIndex = 0; makerIndex < state.makers().size(); makerIndex++) {
                var maker = state.makers().get(makerIndex);
                if (maker == null) {
                    continue;
                }
                for (var slot = 0; slot < maker.npcs().size(); slot++) {
                    found.computeIfAbsent(maker.npcs().get(slot).npcName(), unused -> new ArrayList<>())
                            .add(new int[] {makerIndex, slot});
                }
            }
            places = found;
            placesState = state;
        }
        return places;
    }

    private double @Nullable [] placeLocation(State state, int makerIndex, int slot) {
        var maker = state.makers().get(makerIndex);
        if (maker == null || slot >= maker.npcs().size()) {
            return null;
        }
        if (maker.npcs().get(slot).position() instanceof Fixed fixed) {
            return new double[] {fixed.x(), fixed.y(), fixed.z()};
        }
        var currentIndex = index;
        if (currentIndex != null) {
            for (var name : maker.territoryNames()) {
                var territory = currentIndex.getTerritory(state, name);
                if (territory != null) {
                    return new double[] {
                        territory.centerX(), territory.centerY(), (territory.bottom() + territory.top()) * .5
                    };
                }
            }
        }
        return null;
    }

    private void drawHeader() {
        var spacing = ImGui.getStyle().getItemSpacingX();
        var width = (ImGui.getContentRegionAvailX() - spacing * 2) / 3;
        if (GeodataWidgets.chip("spawn-show", Glyph.EYE, "Show", "N", visible, width)) {
            visible = !visible;
        }
        GeodataWidgets.tip("Show spawn zones and NPC positions in the world.", "N");
        ImGui.sameLine();
        if (GeodataWidgets.chip("spawn-events", Glyph.SPARKLES, "Events", showEvents, width)) {
            showEvents = !showEvents;
        }
        GeodataWidgets.tip("Also show makers that spawn only during sieges, clan hall battles or AI events.");
        ImGui.sameLine();
        if (GeodataWidgets.chip("spawn-view", Glyph.SLIDERS, "View", ImGui.isPopupOpen("spawn-view"), width)) {
            ImGui.openPopup("spawn-view");
        }
        GeodataWidgets.tip("Draw distance, zones, models, labels, castle domains and unused zones.");
        drawViewPopover(ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY() + 4);
    }

    private void drawViewPopover(float right, float top) {
        ImGui.setNextWindowPos(right, top, ImGuiCond.Always, 1, 0);
        ImGui.setNextWindowSize(260, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 12, 10);
        if (ImGui.beginPopup("spawn-view")) {
            GeodataFields.slider(
                    "Draw distance",
                    distance,
                    2000,
                    20000,
                    "Zones and NPCs farther than this from the camera are not drawn. From high up zones show farther.");
            if (ImGui.checkbox("Spawn zones", showZones)) {
                showZones = !showZones;
            }
            GeodataWidgets.tip("Zone outlines and curtains. NPCs stay; the selected maker's zones show either way.");
            if (ImGui.checkbox("Zone cards on hover", showZoneCards)) {
                showZoneCards = !showZoneCards;
            }
            GeodataWidgets.tip("The card that lists a zone's NPCs under the pointer. NPCs keep theirs.");
            if (ImGui.checkbox("NPC models", showModels)) {
                showModels = !showModels;
            }
            if (ImGui.checkbox("NPC names", showLabels)) {
                showLabels = !showLabels;
            }
            ImGui.beginDisabled(!showLabels);
            if (ImGui.checkbox("In-game names", gameNames)) {
                gameNames = !gameNames;
            }
            ImGui.endDisabled();
            GeodataWidgets.tip(
                    "Names over NPCs and on zone cards as the game shows them (Goblin), from the client's NpcName"
                            + " table. Off: npcdata.txt's names (goblin). Panels keep npcdata's names, which the files"
                            + " use.");
            if (ImGui.checkbox("Castle domains", showDomains)) {
                showDomains = !showDomains;
            }
            if (ImGui.checkbox("Zones without makers", showUnused)) {
                showUnused = !showUnused;
            }
            var areas = areaOverlay;
            if (areas != null) {
                if (ImGui.checkbox("Server areas", areas.isOverlayOn())) {
                    areas.setOverlayOn(!areas.isOverlayOn());
                }
                GeodataWidgets.tip(
                        "The areas of areadata.txt, as the Areas tool draws them and L shows and hides them. While"
                                + " this panel is open they are a quiet reference: thin outlines that take no clicks.");
            }
            ImGui.dummy(0, 4);
            ImGui.textColored(GeodataWidgets.MUTED, "Colors");
            for (var style : SpawnStyle.values()) {
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
        ImGui.setCursorScreenPos(x, y);
        var clicked = ImGui.invisibleButton("spawn-source", width - 30, height);
        var hovered = ImGui.isItemHovered();
        draw.addRectFilled(x, y, x + width, y + height, hovered ? ImColor.rgb(34, 51, 63) : GeodataWidgets.CARD, 6f);
        HudWidgets.glyph(draw, Glyph.FOLDER, x + 9, y + 7, 16, GeodataWidgets.MUTED);
        var folder = source.getFolder();
        var name = folder == null
                ? "No script folder"
                : folder.getFileName() == null
                        ? folder.toString()
                        : folder.getFileName().toString();
        draw.pushClipRect(x, y, x + width - 34, y + height, true);
        draw.addText(x + 32, y + 6, GeodataWidgets.TEXT, name);
        var status = status();
        draw.addText(x + 32, y + 24, status.color, status.text);
        draw.popClipRect();
        if (hovered) {
            var counts = source.getPhase() == Phase.READY
                    ? loadedCounts(Objects.requireNonNull(source.getDocument()).getState())
                    : null;
            HudWidgets.showTooltip((folder == null ? "" : folder + "\n\n")
                    + (counts == null || counts.wholeFile()
                            ? ""
                            : "On the loaded tiles. The whole file has " + counts.allText() + ".\n\n")
                    + "Click to choose another folder.");
        }
        if (clicked) {
            folders.open(folder);
        }
        ImGui.setCursorScreenPos(x + width - 28, y + (height - 26) * .5f);
        ImGui.beginDisabled(folder == null || source.isSaving());
        if (HudWidgets.button("spawn-reload", Glyph.RELOAD, "", 26, 26, false, false)) {
            if (source.isModified()) {
                setMessage("Save or undo your changes before reading the files again.", GeodataWidgets.WARNING);
            } else {
                source.reload();
            }
        }
        ImGui.endDisabled();
        GeodataWidgets.tip("Read npcpos.txt and npcdata.txt again after they change on disk.");
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        if (!source.getMessage().isBlank() && source.getPhase() != Phase.READY || source.isMessageError()) {
            ImGui.pushTextWrapPos(0);
            ImGui.textColored(
                    source.isMessageError() ? GeodataWidgets.BLOCKED : GeodataWidgets.MUTED, source.getMessage());
            ImGui.popTextWrapPos();
        }
        if (source.getPhase() == Phase.READY) {
            drawGeodataNotice();
        }
    }

    private record Status(String text, int color) {}

    private Status status() {
        return switch (source.getPhase()) {
            case EMPTY -> new Status("Choose the server script folder", GeodataWidgets.MUTED);
            case LOADING -> new Status("Reading…", GeodataWidgets.MUTED);
            case FAILED -> new Status("Cannot read the files", GeodataWidgets.BLOCKED);
            case READY ->
                new Status(
                        loadedCounts(Objects.requireNonNull(source.getDocument())
                                        .getState())
                                .loadedText(),
                        GeodataWidgets.MUTED);
        };
    }

    private record Counts(long zones, long makers, long npcs, long allZones, long allMakers, long allNpcs) {
        String loadedText() {
            return formatCounts(zones, makers, npcs);
        }

        String allText() {
            return formatCounts(allZones, allMakers, allNpcs);
        }

        boolean wholeFile() {
            return zones == allZones && makers == allMakers && npcs == allNpcs;
        }

        private static String formatCounts(long zones, long makers, long npcs) {
            return compact(zones) + " zones · " + compact(makers) + " makers · " + compact(npcs) + " NPCs";
        }
    }

    private Counts loadedCounts(State state) {
        var currentScope = listedVersion(state);
        if (counts != null && state == countsState && currentScope == countsScope) {
            return counts;
        }
        var all = SpawnTotals.of(state);
        var loaded = SpawnTotals.count(
                state,
                territory -> isInLoadedTiles(territory.minX(), territory.minY(), territory.maxX(), territory.maxY()),
                makerIndex -> isListed(state, makerIndex));
        counts = new Counts(loaded.zones(), loaded.makers(), loaded.npcs(), all.zones(), all.makers(), all.npcs());
        countsState = state;
        countsScope = currentScope;
        return counts;
    }

    private void drawEmptySource() {
        ImGui.dummy(0, 16);
        var draw = ImGui.getWindowDrawList();
        var width = ImGui.getContentRegionAvailX();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        HudWidgets.glyph(draw, Glyph.NPC, x + width * .5f - 18, y, 36, GeodataWidgets.ACCENT);
        ImGui.dummy(width, 44);
        centeredText("Server NPC spawns");
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/npcpos");
        ImGui.pushTextWrapPos(0);
        ImGui.textColored(
                GeodataWidgets.MUTED,
                "Choose the L2OFF script folder with npcpos.txt and npcdata.txt. "
                        + "Zones and NPC positions appear in the world; hover a zone to see its NPCs.");
        ImGui.popTextWrapPos();
        ImGui.dummy(0, 8);
        if (GeodataWidgets.actionButton("spawn-browse", Glyph.FOLDER, "Choose folder…", -1, true)) {
            folders.open(null);
        }
        GeodataWidgets.section("NEW TO NPC SPAWNS?");
        space(ROW);
        HudWidgets.guideLink("What a spawn is and where its files are", "spawns/what-is-a-spawn");
        space(ROW);
        HudWidgets.guideLink("Open and read spawns, step by step", "spawns/read-spawns");
    }

    private void remember(@Nullable Path folder) {
        if (folder == null || Objects.equals(folder, remembered)) {
            return;
        }
        try {
            var model = new OptionsModel(new ConfigLoader(new Options(configDirectory, false, false)), client);
            model.set(ClientProfiles.SCRIPT_PATH, folder.toString());
            model.save();
            remembered = folder;
        } catch (Exception failure) {
            LogManager.getLogger(SpawnWorkspace.class).warn("Could not save the script folder: {}", failure.toString());
            setMessage(
                    "The folder is open, but cruma.conf could not be updated: " + failure.getMessage(),
                    GeodataWidgets.WARNING);
        }
    }

    private void drawGeodataNotice() {
        if (!ground.isAvailable()) {
            space(ROW);
            var x = ImGui.getCursorScreenPosX();
            var y = ImGui.getCursorScreenPosY();
            var width = ImGui.getContentRegionAvailX();
            var height = ImGui.getFrameHeight();
            var buttonWidth = Math.max(70, ImGui.calcTextSizeX("Set up") + 24);
            ImGui.setCursorScreenPos(x, y + (height - ImGui.getFontSize()) * .5f);
            var eye = camera == null ? null : camera.getPosition();
            var loaded = tiles;
            var floors = clientFloors;
            var reading = eye != null
                    && loaded != null
                    && loaded.contains(eye.getX(), eye.getY())
                    && (floors == null || !floors.covers(eye.getX(), eye.getY()));
            GeodataWidgets.note(
                    reading ? "Reading the client's floors…" : "On the client's floors: no geodata.",
                    GeodataWidgets.MUTED,
                    width - buttonWidth - ROW);
            GeodataWidgets.tip("Zones and NPCs stand on the floors of the loaded map. Random spawn points are an"
                    + " estimate, and Check spawns needs the server's geodata. Set up opens the Geodata panel.");
            var bottom = ImGui.getItemRectMaxY();
            ImGui.setCursorScreenPos(x + width - buttonWidth, y);
            if (HudWidgets.button("spawn-geodata", null, "Set up", buttonWidth, height, false, true)) {
                geodataRequested = true;
            }
            GeodataWidgets.tip(
                    "Opens the Geodata panel: choose the server's geodata folder, or build it from the client.");
            ImGui.setCursorScreenPos(x, Math.max(bottom, y + height));
            ImGui.dummy(width, 0);
            return;
        }
        if (geodataBuilding) {
            space(BLOCK);
            geodataBuild.progressCard("spawn-geodata-build", geodataFolder);
            space(ROW);
            GeodataWidgets.hint("Zones and NPCs stand on the client's floors until their region is built.");
            return;
        }
        var eye = camera == null ? null : camera.getPosition();
        if (eye != null && ground.isMissing(eye.getX(), eye.getY())) {
            var tile = GeoTile.fromWorld((int) eye.getX(), (int) eye.getY());
            space(ROW);
            var onFloors = ground.getGroundSource(eye.getX(), eye.getY()) == GroundSource.CLIENT;
            GeodataWidgets.hint("No geodata for " + tile.x() + "_" + tile.y()
                    + (onFloors ? ": its zones and NPCs stand on the client's floors." : "."));
            GeodataWidgets.tip("The geodata folder has no file for the region under the camera. Random spawn points"
                    + " there are an estimate. Build it from the client in the Geodata panel, or add the server's"
                    + " file.");
        }
    }

    private boolean isFooterShown(NpcPosDocument document) {
        return footerStatus(document).isShown();
    }

    private void drawFooter(NpcPosDocument document) {
        switch (footer.draw(footerStatus(document))) {
            case UNDO -> {
                document.undo();
                merge.reset();
            }
            case REDO -> {
                document.redo();
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

    private SaveFooter.Status footerStatus(NpcPosDocument document) {
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
                source.getPositionsFile());
    }

    private void setMessage(String text, int color) {
        message = text;
        messageColor = color;
    }

    private void drawSearchBox(State state) {
        space(BLOCK);
        var hasText = !search.get().isEmpty();
        ImGui.setNextItemWidth(hasText ? ImGui.getContentRegionAvailX() - 30 : -1);
        ImGui.inputTextWithHint("##spawn-search", "Find an NPC, maker or zone", search);
        var typing = ImGui.isItemActive();
        var left = ImGui.isItemDeactivated();
        var entered = left && (ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter));
        if (hasText) {
            ImGui.sameLine(0, 4);
            if (HudWidgets.button("spawn-search-clear", Glyph.CLOSE, "", 26, ImGui.getFrameHeight(), false, false)) {
                clearSearch();
            }
            GeodataWidgets.tip("Clear the search.", "Esc");
        }
        var query = search.get().strip().toLowerCase(Locale.ROOT);
        var filters = filterKey();
        var currentScope = listedVersion(state);
        var changed = !query.equals(searched) || !filters.equals(listedFilters);
        if (!filters.equals(listedFilters) && !filters.isEmpty()) {
            selectedMaker = -1;
            selectedSlot = -1;
        }
        if (changed
                || (state != listedState || currentScope != listedScope) && (!query.isEmpty() || !filters.isEmpty())) {
            searched = query;
            listedFilters = filters;
            listedState = state;
            listedScope = currentScope;
            results = query.isEmpty() && filters.isEmpty() ? List.of() : find(state, query);
            if (changed) {
                searchCursor = 0;
                scrolledCursor = 0;
            }
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
        if (entered && !results.isEmpty() && camera != null && !selecting) {
            pick(results.get(Math.clamp(searchCursor, 0, results.size() - 1)), camera);
        }
    }

    private void clearSearch() {
        search.set("");
        searched = "";
        results = List.of();
        searchCursor = 0;
        listedState = null;
    }

    private void pick(Result result, Camera camera) {
        selectedMaker = result.maker();
        selectedSlot = result.slot();
        clearSearch();
        focus(camera, result.maker());
    }

    private List<Integer> resultMakers() {
        var makers = new LinkedHashSet<Integer>();
        for (var result : results) {
            makers.add(result.maker());
        }
        return List.copyOf(makers);
    }

    private List<Result> find(State state, String query) {
        var found = new ArrayList<Result>();
        var hidden = 0;
        var eye = camera == null ? null : camera.getPosition();
        var npcs = source.getNpcs();
        var filter = filter();
        for (var makerIndex = 0; makerIndex < state.makers().size() && found.size() < 20_000; makerIndex++) {
            var maker = state.makers().get(makerIndex);
            if (maker == null || !filter.passes(maker, styleOf(state, makerIndex), npcs)) {
                continue;
            }
            var loaded = isListed(state, makerIndex);
            var where = makerLocation(state, makerIndex);
            var distance = where == null || eye == null
                    ? Double.MAX_VALUE
                    : Math.hypot(where[0] - eye.getX(), where[1] - eye.getY());
            var makerMatch = query.isEmpty()
                    || maker.displayName().toLowerCase(Locale.ROOT).contains(query)
                    || maker.territoryNames().stream()
                            .anyMatch(name -> name.toLowerCase(Locale.ROOT).contains(query));
            if (makerMatch) {
                if (loaded) {
                    found.add(new Result(makerIndex, -1, maker.displayName(), makerDetail(maker), distance));
                } else {
                    hidden++;
                }
                continue;
            }
            for (var slot = 0; slot < maker.npcs().size(); slot++) {
                var npc = maker.npcs().get(slot);
                var game = gameName(npc.npcName());
                if (npc.npcName().toLowerCase(Locale.ROOT).contains(query)
                        || game.toLowerCase(Locale.ROOT).contains(query)) {
                    if (!loaded) {
                        hidden++;
                        continue;
                    }
                    var info = npcs.getNpc(npc.npcName());
                    found.add(new Result(
                            makerIndex,
                            slot,
                            npc.npcName(),
                            (game.isEmpty() || game.equalsIgnoreCase(npc.npcName()) ? "" : game + " · ")
                                    + (info == null ? "" : "Lv " + info.level() + " · ")
                                    + "×"
                                    + npc.total()
                                    + " in "
                                    + maker.displayName(),
                            distance));
                }
            }
        }
        found.sort(Comparator.comparing((Result result) -> !result.title().equalsIgnoreCase(query)
                        && !(result.slot() >= 0 && gameName(result.title()).equalsIgnoreCase(query)))
                .thenComparingDouble(Result::distance));
        hiddenMatches = hidden;
        return found;
    }

    private String makerDetail(SpawnMaker maker) {
        var levels = SpawnSummary.computeLevels(maker, source.getNpcs());
        return (levels == null ? "" : levels.label() + " · ")
                + npcCount(maker.totalNpcs())
                + " · "
                + maker.npcs().size()
                + (maker.npcs().size() == 1 ? " entry" : " entries");
    }

    private @Nullable double[] makerLocation(State state, int makerIndex) {
        var maker = state.makers().get(makerIndex);
        var currentIndex = index;
        if (currentIndex != null) {
            for (var name : maker.territoryNames()) {
                var territory = currentIndex.getTerritory(state, name);
                if (territory != null) {
                    return new double[] {territory.centerX(), territory.centerY()};
                }
            }
        }
        for (var npc : maker.npcs()) {
            if (npc.position() instanceof Fixed fixed) {
                return new double[] {fixed.x(), fixed.y()};
            }
        }
        return null;
    }

    private void drawSearchResults(State state, Camera camera) {
        var filtered = isFiltering();
        if (results.isEmpty()) {
            var where = hiddenMatches > 0 ? " on the loaded tiles" : "";
            ImGui.textColored(
                    GeodataWidgets.MUTED,
                    searched.isBlank()
                            ? "No maker matches the filters" + where + "."
                            : "Nothing matches “" + searched + "”" + where + (filtered ? " with the filters." : "."));
            ImGui.textColored(
                    GeodataWidgets.FAINT,
                    hiddenMatches > 0 ? notLoadedMatches() : "Search looks at NPC, maker and zone names.");
            return;
        }
        var count = results.size() == 1 ? "1 match" : String.format(Locale.ROOT, "%,d matches", results.size());
        ImGui.textColored(
                GeodataWidgets.FAINT,
                count + ", nearest first" + (selecting ? " · click to choose" : " · ↑↓ and Enter"));
        space(ROW);
        var shown = Math.min(results.size(), LISTED_RESULTS);
        Result clicked = null;
        for (var i = 0; i < shown; i++) {
            var result = results.get(i);
            ImGui.pushID(i);
            var maker = state.makers().get(result.maker());
            var style = result.slot() < 0
                    ? styleOf(state, result.maker())
                    : maker != null && maker.isConditional()
                            ? SpawnStyle.EVENT
                            : SpawnStyle.of(source.getNpcs().getNpc(result.title()));
            var x = ImGui.getCursorScreenPosX();
            var y = ImGui.getCursorScreenPosY();
            var picked = selecting && chosen.contains(result.maker());
            if (ImGui.selectable(
                    "##result", i == searchCursor && !selecting || picked, 0, 0, SpawnPanelWidgets.listRowHeight())) {
                if (selecting) {
                    choose(result.maker(), ImGui.getIO().getKeyShift(), resultMakers());
                } else {
                    clicked = result;
                }
            }
            if (ImGui.isItemHovered()) {
                panelHoverMaker = result.maker();
            }
            if (i == searchCursor && !selecting) {
                panelHoverMaker = result.maker();
                if (scrolledCursor != searchCursor) {
                    var top = ImGui.getWindowPosY();
                    if (ImGui.getItemRectMinY() < top) {
                        ImGui.setScrollHereY(0);
                    } else if (ImGui.getItemRectMaxY() > top + ImGui.getWindowHeight()) {
                        ImGui.setScrollHereY(1);
                    }
                    scrolledCursor = searchCursor;
                }
            }
            var draw = ImGui.getWindowDrawList();
            var away = result.distance() == Double.MAX_VALUE
                    ? ""
                    : SpawnPanelWidgets.compact(Math.round(result.distance())) + " away";
            var left = x;
            if (selecting) {
                SpawnPanelWidgets.checkBox(draw, x + 4, y + (SpawnPanelWidgets.listRowHeight() - 16) * .5f, picked);
                left += 22;
            }
            SpawnPanelWidgets.listRow(
                    draw,
                    left,
                    y,
                    ImGui.getItemRectMaxX() - left,
                    style.argb,
                    result.title(),
                    result.detail(),
                    away,
                    searched);
            ImGui.popID();
        }
        if (clicked != null) {
            pick(clicked, camera);
            return;
        }
        if (results.size() > shown) {
            space(ROW);
            ImGui.textColored(
                    GeodataWidgets.FAINT,
                    String.format(
                            Locale.ROOT,
                            "%,d more. Narrow the search%s.",
                            results.size() - shown,
                            selecting ? ", or choose them all with All matching" : ""));
        }
        if (hiddenMatches > 0) {
            space(ROW);
            ImGui.textColored(GeodataWidgets.FAINT, notLoadedMatches());
        }
    }

    private String notLoadedMatches() {
        return String.format(
                Locale.ROOT,
                "%,d %s on tiles that are not loaded.",
                hiddenMatches,
                hiddenMatches == 1 ? "match lies" : "matches lie");
    }

    private void choose(int maker, boolean range, List<Integer> order) {
        var at = order.indexOf(maker);
        var from = order.indexOf(anchor);
        if (range && at >= 0 && from >= 0) {
            for (var i = Math.min(at, from); i <= Math.max(at, from); i++) {
                chosen.add(order.get(i));
            }
        } else if (!chosen.remove(maker)) {
            chosen.add(maker);
        }
        anchor = maker;
    }

    private boolean isFiltering() {
        return !filterKey().isEmpty();
    }

    private String filterKey() {
        if (filterKinds.isEmpty()
                && filterBand < 0
                && filterAi.isEmpty()
                && filterRespawn.get().isBlank()
                && filterEvent.isEmpty()
                && !filterUnknown) {
            return "";
        }
        return filterKinds
                + "|"
                + filterBand
                + "|"
                + filterAi
                + "|"
                + filterRespawn.get().strip()
                + "|"
                + filterEvent
                + "|"
                + filterUnknown;
    }

    private int activeFilters() {
        return filter().count();
    }

    private SpawnFilter filter() {
        return new SpawnFilter(filterKinds, filterBand, filterAi, filterRespawn.get(), filterEvent, filterUnknown);
    }

    private void listChips() {
        space(ROW);
        var spacing = ImGui.getStyle().getItemSpacingX();
        var width = (ImGui.getContentRegionAvailX() - 2 * spacing) / 3;
        var active = activeFilters();
        if (GeodataWidgets.chip(
                "spawn-filters",
                Glyph.FILTER,
                active > 0 ? "Filters " + active : "Filters",
                filtersOpen || active > 0,
                width)) {
            filtersOpen = !filtersOpen;
        }
        GeodataWidgets.tip("List only makers of a kind, a level band, an AI, a respawn time or an event.");
        ImGui.sameLine();
        if (GeodataWidgets.chip("spawn-select", Glyph.CURSOR, "Select", selecting, width)) {
            selecting = !selecting;
            batchBar.cancel();
            if (!selecting) {
                chosen.clear();
            }
        }
        GeodataWidgets.tip(
                "Choose many makers and change them at once: click zones or NPCs in the world, tick them in the lists"
                        + " (Shift-click for a range), or drag a box on the world map (M).");
        ImGui.sameLine();
        if (GeodataWidgets.chip("spawn-draw", Glyph.PENCIL, "Draw zone", drawingZone, width)) {
            if (drawingZone) {
                drawingZone = false;
                drawnCorners.clear();
                clicks.cancel();
            } else {
                drawingZone = true;
                drawnCorners.clear();
                clicks.cancel();
                placingMaker = -1;
                visible = true;
            }
        }
        GeodataWidgets.tip(
                "Click corners on the ground for a new zone, snapped to 16-unit geodata cells. A click on the first"
                        + " corner or Enter closes it, Esc cancels.");
    }

    private void drawFilters(NpcPosDocument document) {
        var state = document.getState();
        ImGui.textColored(GeodataWidgets.MUTED, "Kinds");
        var kinds = SpawnPanelWidgets.composition(
                "spawn-filter-kinds",
                compositionOf(Objects.requireNonNull(source.getDocument())),
                EnumSet.complementOf(filterKinds.isEmpty() ? EnumSet.allOf(SpawnStyle.class) : filterKinds),
                "in the list");
        if (kinds != null) {
            if (!filterKinds.remove(kinds)) {
                filterKinds.add(kinds);
            }
        }
        space(ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Level");
        var bands = new String[SpawnSummary.BAND_LABELS.length + 1];
        bands[0] = "Any";
        System.arraycopy(SpawnSummary.BAND_LABELS, 0, bands, 1, SpawnSummary.BAND_LABELS.length);
        var band = GeodataWidgets.segmented("spawn-filter-band", bands, filterBand + 1);
        if (band >= 0) {
            filterBand = band - 1;
        }
        GeodataWidgets.tip("The level of a maker's NPCs, their mean by count, from npcdata.txt.");
        space(ROW);
        var column = GeodataWidgets.columnWidth(2);
        GeodataWidgets.columns(
                () -> drawAiFilter(document, column),
                () -> GeodataWidgets.compactText(
                        "Respawn", "##spawn-filter-respawn", "such as 3hour", filterRespawn, column));
        space(ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Event");
        var eventFilter = GeodataWidgets.searchList(
                "##spawn-filter-event",
                filterEvent.isEmpty() ? "Any" : filterEvent.substring(filterEvent.indexOf(':') + 1),
                eventSearch,
                "Find an event",
                -1);
        if (eventFilter != null) {
            if (eventFilter.isEmpty() && GeodataWidgets.listItem("any", "Any", "", filterEvent.isEmpty())) {
                filterEvent = "";
            }
            for (var event : listedEvents(state)) {
                if (!event.name().toLowerCase(Locale.ROOT).contains(eventFilter)) {
                    continue;
                }
                var key = eventKey(event.spawnTime(), event.name());
                if (GeodataWidgets.listItem(
                        key,
                        event.name(),
                        (event.spawnTime() ? "spawn time · " : "")
                                + makerCount(event.makers().size()),
                        key.equals(filterEvent))) {
                    filterEvent = key;
                }
            }
            ImGui.endCombo();
        }
        space(ROW);
        if (ImGui.checkbox("NPCs npcdata.txt lacks##spawn-filter-unknown", filterUnknown)) {
            filterUnknown = !filterUnknown;
        }
        GeodataWidgets.tip("Only makers with an entry naming an NPC that npcdata.txt does not have.");
        if (isFiltering()) {
            ImGui.sameLine(ImGui.getContentRegionMaxX() - 96);
            if (HudWidgets.button("spawn-filter-clear", null, "Clear filters", 96, 24, false, true)) {
                clearFilters();
            }
        }
    }

    private boolean drawAiFilter(NpcPosDocument document, float width) {
        ImGui.beginGroup();
        ImGui.textColored(GeodataWidgets.MUTED, "AI");
        var typed = GeodataWidgets.searchList(
                "##spawn-filter-ai", filterAi.isEmpty() ? "Any" : filterAi, aiSearch, "Find an AI", width);
        if (typed != null) {
            if (typed.isEmpty() && GeodataWidgets.listItem("any", "Any", "", filterAi.isEmpty())) {
                filterAi = "";
            }
            for (var ai : loadedAis(document.getState())) {
                if (!ai.getKey().toLowerCase(Locale.ROOT).contains(typed)) {
                    continue;
                }
                if (GeodataWidgets.listItem(
                        ai.getKey(),
                        ai.getKey(),
                        makerCount(ai.getValue()),
                        ai.getKey().equals(filterAi))) {
                    filterAi = ai.getKey();
                }
            }
            ImGui.endCombo();
        }
        ImGui.endGroup();
        return false;
    }

    private List<Entry<String, Integer>> loadedAis(State state) {
        var currentScope = listedVersion(state);
        if (state != aisState || currentScope != aisScope) {
            var counts = new HashMap<String, Integer>();
            for (var makerIndex = 0; makerIndex < state.makers().size(); makerIndex++) {
                var maker = state.makers().get(makerIndex);
                if (maker != null && maker.extended() && !maker.ai().isEmpty() && isListed(state, makerIndex)) {
                    counts.merge(maker.ai(), 1, Integer::sum);
                }
            }
            ais = counts.entrySet().stream()
                    .sorted(Entry.<String, Integer>comparingByValue().reversed().thenComparing(Entry.comparingByKey()))
                    .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                    .toList();
            aisState = state;
            aisScope = currentScope;
        }
        return ais;
    }

    private void clearFilters() {
        filterKinds.clear();
        filterBand = -1;
        filterAi = "";
        filterRespawn.set("");
        filterEvent = "";
        filterUnknown = false;
    }

    private void focus(Camera camera, int makerIndex) {
        var document = source.getDocument();
        var currentIndex = index;
        if (document == null || currentIndex == null) {
            return;
        }
        var state = document.getState();
        var maker = state.makers().get(makerIndex);
        pendingFocus = null;
        if (makerIndex == selectedMaker
                && selectedSlot >= 0
                && selectedSlot < maker.npcs().size()) {
            var npc = maker.npcs().get(selectedSlot);
            if (npc.position() instanceof Fixed fixed) {
                frameNpc(
                        camera,
                        fixed.x(),
                        fixed.y(),
                        feetHeight(
                                fixed.x(),
                                fixed.y(),
                                fixed.z(),
                                source.getNpcs().getNpc(npc.npcName())));
                return;
            }
            if (frameRandomNpc(camera, state, currentIndex, makerIndex, selectedSlot)) {
                return;
            }
            pendingFocus = new PendingFocus(makerIndex, selectedSlot, System.nanoTime() + 5_000_000_000L);
        }
        var minX = Float.MAX_VALUE;
        var minY = Float.MAX_VALUE;
        var minZ = Float.MAX_VALUE;
        var maxX = -Float.MAX_VALUE;
        var maxY = -Float.MAX_VALUE;
        var maxZ = -Float.MAX_VALUE;
        for (var name : maker.territoryNames()) {
            var territory = currentIndex.getTerritory(state, name);
            if (territory == null) {
                continue;
            }
            var drape = ground.getDrape(territory);
            var z = drape == null
                    ? Math.clamp(camera.getPosition().getZ(), territory.bottom(), territory.top())
                    : drape.points()[2];
            minX = Math.min(minX, territory.minX());
            minY = Math.min(minY, territory.minY());
            maxX = Math.max(maxX, territory.maxX());
            maxY = Math.max(maxY, territory.maxY());
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z + 200);
        }
        for (var npc : maker.npcs()) {
            if (npc.position() instanceof Fixed fixed) {
                minX = Math.min(minX, fixed.x() - 300);
                minY = Math.min(minY, fixed.y() - 300);
                minZ = Math.min(minZ, fixed.z());
                maxX = Math.max(maxX, fixed.x() + 300);
                maxY = Math.max(maxY, fixed.y() + 300);
                maxZ = Math.max(maxZ, fixed.z() + 200);
            }
        }
        if (minX > maxX) {
            return;
        }
        var centerX = (minX + maxX) * .5;
        var centerY = (minY + maxY) * .5;
        var ceiling = ground.findLayerAbove(centerX, centerY, maxZ);
        if (Double.isFinite(ceiling)) {
            var look = camera.getPerspectiveLookDir();
            camera.setPosition((float) centerX, (float) centerY, (float) Math.min(maxZ + 400, ceiling - 60));
            camera.setDirection(Math.toDegrees(Math.atan2(look.getY(), look.getX())), -60);
            return;
        }
        frame(camera, new Bounds3f(minX, minY, minZ, maxX, maxY, maxZ), -50);
    }

    private record PendingFocus(int makerIndex, int slot, long until) {}

    private void followPendingFocus(State state, SpawnIndex currentIndex, Camera camera) {
        var pending = pendingFocus;
        if (pending == null) {
            return;
        }
        if (System.nanoTime() > pending.until()
                || pending.makerIndex() != selectedMaker
                || pending.slot() != selectedSlot) {
            pendingFocus = null;
        } else if (frameRandomNpc(camera, state, currentIndex, pending.makerIndex(), pending.slot())) {
            pendingFocus = null;
        }
    }

    private boolean frameRandomNpc(Camera camera, State state, SpawnIndex currentIndex, int makerIndex, int slot) {
        if (makerIndex >= state.makers().size() || state.makers().get(makerIndex) == null) {
            return false;
        }
        var maker = state.makers().get(makerIndex);
        var candidates = findCandidates(state, currentIndex, makerIndex, maker);
        if (candidates == null) {
            return false;
        }
        var eye = camera.getPosition();
        Placed nearest = null;
        for (var placed : SpawnPreview.place(maker, candidates, seed + makerIndex)) {
            if (placed.slot() == slot
                    && (nearest == null
                            || Math.hypot(placed.x() - eye.getX(), placed.y() - eye.getY())
                                    < Math.hypot(nearest.x() - eye.getX(), nearest.y() - eye.getY()))) {
                nearest = placed;
            }
        }
        if (nearest == null) {
            return false;
        }
        frameNpc(
                camera,
                nearest.x(),
                nearest.y(),
                feetHeight(
                        nearest.x(), nearest.y(), nearest.z(), source.getNpcs().getNpc(nearest.npcName())));
        return true;
    }

    private void frameNpc(Camera camera, double x, double y, double ground) {
        frame(
                camera,
                new Bounds3f(
                        (float) x - 120,
                        (float) y - 120,
                        (float) ground,
                        (float) x + 120,
                        (float) y + 120,
                        (float) ground + 160),
                -30);
    }

    private void frame(Camera camera, Bounds3f bounds, double pitch) {
        var look = camera.getPerspectiveLookDir();
        camera.setDirection(Math.toDegrees(Math.atan2(look.getY(), look.getX())), pitch);
        var io = ImGui.getIO();
        var width = io.getDisplaySizeX();
        var height = io.getDisplaySizeY();
        var area = viewport;
        if (area == null || width <= 0 || height <= 0 || area.right() <= area.left() || area.bottom() <= area.top()) {
            camera.focus(bounds);
            return;
        }
        camera.focus(
                bounds,
                (area.left() + area.right()) / width - 1f,
                1f - (area.top() + area.bottom()) / height,
                (area.right() - area.left()) / width,
                (area.bottom() - area.top()) / height);
    }

    private void drawOverview(NpcPosDocument document, Camera camera, ViewerRenderBackend backend) {
        var state = document.getState();
        title("IN THE LOADED WORLD", false);
        space(ROW);
        var kind = SpawnPanelWidgets.composition("spawn-kinds", compositionOf(document), hiddenKinds);
        if (kind != null && !hiddenKinds.remove(kind)) {
            hiddenKinds.add(kind);
        }
        drawNearby(state, camera);
        drawEditorNpcRow(state);
        space(SECTION);
        if (GeodataWidgets.actionButton("spawn-new", Glyph.PLUS, "New spawn zone here", -1, false)) {
            addNewZone(document, camera, backend);
        }
        GeodataWidgets.tip("Adds a square zone on the ground in the middle of the view, with a maker for it.");
        if (GeodataWidgets.actionButton("spawn-paste", Glyph.COPY, "Paste maker here", -1, false)) {
            pasteMaker(document, camera, backend, ImGui.getClipboardText());
        }
        GeodataWidgets.tip(
                "Adds a maker copied as npcpos.txt text, with its zones, in the middle of the view: Copy as text on a "
                        + "maker, or lines copied from a file.");
        drawBookmarkList(state, camera);
        drawEventsSection(document);
        drawHousekeeping(document);
        drawHealthSection(document, camera);
        var problems = document.getFile().getProblems();
        if (!problems.isEmpty() && GeodataWidgets.header("Problems (" + problems.size() + ")")) {
            for (var problem : problems.subList(0, Math.min(problems.size(), 200))) {
                ImGui.textColored(GeodataWidgets.WARNING, "Line " + problem.line() + ": " + problem.message());
            }
        }
    }

    private void drawEditorNpcRow(State state) {
        var npc = editorNpc;
        if (npc == null
                || findNpcPlaces(state).getOrDefault(npc, List.of()).stream()
                        .noneMatch(place -> isListed(state, place[0]))) {
            return;
        }
        space(ROW);
        if (GeodataWidgets.actionButton("spawn-editor-npc", Glyph.FOCUS, "Fly to the nearest " + npc, -1, false)) {
            flyTo(npc);
        }
        GeodataWidgets.tip("The NPC the NPCs panel has open: selects its spawn nearest to the camera and flies there.");
    }

    private double[] viewTarget(Camera camera, ViewerRenderBackend backend) {
        var origin = camera.getCursorRayOrigin(0, 0);
        var direction = camera.getCursorRayDirection(0, 0);
        var hit = backend.getSceneRayDistance(0, 0, 60_000);
        if (Float.isFinite(hit) && hit > 0) {
            return new double[] {
                origin.getX() + direction.getX() * hit,
                origin.getY() + direction.getY() * hit,
                origin.getZ() + direction.getZ() * hit
            };
        }
        var x = origin.getX() + direction.getX() * 800;
        var y = origin.getY() + direction.getY() * 800;
        var groundZ = ground.getGroundAt(x, y, origin.getZ());
        return new double[] {x, y, Double.isNaN(groundZ) ? origin.getZ() - 200 : groundZ};
    }

    private void pasteMaker(
            NpcPosDocument document, Camera camera, ViewerRenderBackend backend, @Nullable String text) {
        if (text == null || !text.contains("npcmaker")) {
            setMessage(
                    "The clipboard holds no maker. Copy one with Copy as text, or copy its lines from npcpos.txt.",
                    GeodataWidgets.WARNING);
            return;
        }
        var target = viewTarget(camera, backend);
        var copy = SpawnCopies.paste(document.getState(), text, target[0], target[1], target[2]);
        if (copy == null) {
            setMessage("The clipboard holds no maker with a zone or a fixed NPC to place.", GeodataWidgets.WARNING);
            return;
        }
        document.apply("Paste maker", unused -> copy.state());
        merge.reset();
        selectedMaker = copy.maker();
        selectedSlot = -1;
        editingMaker = copy.maker();
        visible = true;
        setMessage("Pasted " + copy.state().makers().get(copy.maker()).displayName() + " here.", GeodataWidgets.MUTED);
    }

    private void duplicateMaker(NpcPosDocument document, Camera camera, ViewerRenderBackend backend) {
        var state = document.getState();
        var maker = state.makers().get(selectedMaker);
        var zones = SpawnCopies.findZones(state, maker);
        var middle = SpawnCopies.computeMiddle(zones, maker);
        if (middle == null) {
            setMessage("This maker has no zone or fixed NPC to copy.", GeodataWidgets.WARNING);
            return;
        }
        var sourceGround = middle[2];
        for (var zone : zones) {
            var drape = ground.getDrape(zone);
            if (drape != null && drape.grounded()) {
                var sum = 0.0;
                for (var i = 2; i < drape.points().length; i += 3) {
                    sum += drape.points()[i];
                }
                sourceGround = sum / drape.samples();
                break;
            }
        }
        var target = viewTarget(camera, backend);
        var copy = SpawnCopies.duplicate(state, selectedMaker, target[0] - middle[0], target[1] - middle[1], (int)
                Math.round(target[2] - sourceGround));
        document.apply("Duplicate maker", unused -> copy.state());
        merge.reset();
        selectedMaker = copy.maker();
        selectedSlot = -1;
        editingMaker = copy.maker();
        setMessage("Copied as " + copy.state().makers().get(copy.maker()).displayName() + ".", GeodataWidgets.MUTED);
    }

    private void loadBookmarks() {
        var folder = source.getFolder();
        if (Objects.equals(folder, bookmarksFolder)) {
            return;
        }
        bookmarksFolder = folder;
        bookmarks = new ArrayList<>();
        if (folder == null) {
            return;
        }
        try {
            bookmarks.addAll(bookmarkStore.load(folder));
        } catch (IOException failure) {
            LogManager.getLogger(SpawnWorkspace.class)
                    .warn("Could not read the spawn bookmarks: {}", failure.toString());
        }
    }

    private void saveBookmarks() {
        var folder = source.getFolder();
        if (folder == null) {
            return;
        }
        try {
            bookmarkStore.save(folder, bookmarks);
        } catch (IOException failure) {
            setMessage("Could not save the bookmarks: " + failure.getMessage(), GeodataWidgets.WARNING);
        }
    }

    private boolean isBookmarked(SpawnMaker maker) {
        loadBookmarks();
        return bookmarks.contains(SpawnBookmarks.bookmarkKey(maker));
    }

    private void toggleBookmark(SpawnMaker maker) {
        loadBookmarks();
        var key = SpawnBookmarks.bookmarkKey(maker);
        if (!bookmarks.remove(key)) {
            if (bookmarks.size() >= 128) {
                setMessage("128 bookmarks is the most. Remove one under Bookmarks first.", GeodataWidgets.WARNING);
                return;
            }
            bookmarks.add(key);
        }
        saveBookmarks();
    }

    private void drawBookmarkList(State state, Camera camera) {
        loadBookmarks();
        if (bookmarks.isEmpty()) {
            return;
        }
        title("BOOKMARKS", true);
        space(ROW);
        var width = ImGui.getContentRegionAvailX();
        for (var i = 0; i < bookmarks.size(); i++) {
            var key = bookmarks.get(i);
            var makerIndex = SpawnBookmarks.find(state, key);
            ImGui.pushID("bookmark" + i);
            var x = ImGui.getCursorScreenPosX();
            var y = ImGui.getCursorScreenPosY();
            var rowHeight = SpawnPanelWidgets.listRowHeight();
            ImGui.setNextItemAllowOverlap();
            if (ImGui.selectable("##bookmark", false, 0, width, rowHeight) && makerIndex >= 0) {
                selectedMaker = makerIndex;
                selectedSlot = -1;
                focus(camera, makerIndex);
            }
            if (ImGui.isItemHovered() && makerIndex >= 0) {
                panelHoverMaker = makerIndex;
            }
            var maker = makerIndex < 0 ? null : state.makers().get(makerIndex);
            SpawnPanelWidgets.listRow(
                    ImGui.getWindowDrawList(),
                    x,
                    y,
                    width - 30,
                    maker == null ? SpawnStyle.UNKNOWN.argb : styleOf(state, makerIndex).argb,
                    key,
                    maker == null ? "No longer in npcpos.txt" : makerDetail(maker),
                    maker != null && !isListed(state, makerIndex) ? "not loaded" : "",
                    "");
            ImGui.setCursorScreenPos(x + width - 26, y + (rowHeight - 22) * .5f);
            if (HudWidgets.button("spawn-bookmark-remove", Glyph.CLOSE, "", 22, 22, false, false)) {
                bookmarks.remove(i);
                saveBookmarks();
                ImGui.popID();
                break;
            }
            GeodataWidgets.tip("Remove this bookmark.");
            ImGui.setCursorScreenPos(x, y + rowHeight);
            ImGui.dummy(0, 0);
            ImGui.popID();
        }
    }

    private List<Event> listedEvents(State state) {
        var currentScope = listedVersion(state);
        if (state != eventsState || currentScope != eventsScope) {
            var loaded = new ArrayList<Event>();
            for (var event : fileEvents(state)) {
                var makers = event.makers().stream()
                        .filter(maker -> isListed(state, maker))
                        .toList();
                if (!makers.isEmpty()) {
                    loaded.add(new Event(event.spawnTime(), event.name(), makers));
                }
            }
            events = List.copyOf(loaded);
            eventsState = state;
            eventsScope = currentScope;
        }
        return events;
    }

    private List<Event> fileEvents(State state) {
        if (state != fileEventsState) {
            fileEvents = SpawnSummary.collectEvents(state);
            fileEventsState = state;
        }
        return fileEvents;
    }

    private void drawEventsSection(NpcPosDocument document) {
        var list = listedEvents(document.getState());
        if (list.isEmpty()) {
            return;
        }
        space(SECTION);
        var label = "Events and spawn times · "
                + list.size()
                + (eventFilter.isEmpty() ? "" : " · " + eventFilter.size() + " shown alone");
        if (HudWidgets.disclosure("spawn-events-section", label, eventsOpen)) {
            eventsOpen = !eventsOpen;
        }
        GeodataWidgets.tip(
                "Makers that spawn only during an event or a siege. Turn one on to see only its makers in the world"
                        + " and on the map.");
        if (!eventsOpen) {
            return;
        }
        space(ROW);
        drawTimeline(list);
        var width = ImGui.getContentRegionAvailX();
        for (var event : list) {
            var key = eventKey(event.spawnTime(), event.name());
            ImGui.pushID(key);
            var on = eventFilter.contains(key);
            var x = ImGui.getCursorScreenPosX();
            var y = ImGui.getCursorScreenPosY();
            var rowHeight = 26f;
            ImGui.setNextItemAllowOverlap();
            if (ImGui.selectable("##event", on, 0, width, rowHeight)) {
                if (!eventFilter.remove(key)) {
                    eventFilter.add(key);
                }
                visible = true;
            }
            GeodataWidgets.tip(
                    (on
                            ? "Show every maker again."
                            : "Show only this " + (event.spawnTime() ? "spawn time" : "event")
                                    + "'s makers in the world and on the map."));
            var draw = ImGui.getWindowDrawList();
            SpawnPanelWidgets.checkBox(draw, x + 4, y + (rowHeight - 16) * .5f, on);
            var textTop = y + (rowHeight - ImGui.getFontSize()) * .5f;
            var count = event.makers().size() + (event.makers().size() == 1 ? " maker" : " makers");
            var room = width - 26 - 74 - ImGui.calcTextSizeX(count) - 12;
            draw.addText(
                    x + 26,
                    textTop,
                    on ? HudWidgets.TEXT : GeodataWidgets.MUTED,
                    HudWidgets.truncateToWidth(event.name(), room));
            draw.addText(x + width - 74 - ImGui.calcTextSizeX(count) - 6, textTop, GeodataWidgets.FAINT, count);
            ImGui.setCursorScreenPos(x + width - 70, y + 2);
            if (HudWidgets.button("spawn-event-select", null, "Select", 70, 22, false, true)) {
                selecting = true;
                chosen.addAll(event.makers());
                selectedMaker = -1;
            }
            GeodataWidgets.tip("Choose every maker of it for a batch edit.");
            ImGui.setCursorScreenPos(x, y + rowHeight);
            ImGui.dummy(0, 0);
            ImGui.popID();
        }
        if (!eventFilter.isEmpty()) {
            space(ROW);
            if (GeodataWidgets.actionButton("spawn-events-all", null, "Show every maker again", -1, false)) {
                eventFilter.clear();
            }
        }
    }

    private void drawHousekeeping(NpcPosDocument document) {
        var state = document.getState();
        if (state != housekeepingState) {
            unusedZones = state.unusedZones();
            unknownEntries = SpawnSummary.unknownNpcs(state, source.getNpcs());
            housekeepingState = state;
        }
        if (unusedZones.isEmpty() && unknownEntries.isEmpty()) {
            return;
        }
        title("HOUSEKEEPING", true);
        space(ROW);
        if (!unusedZones.isEmpty()) {
            drawHousekeepingRow(
                    "spawn-unused",
                    String.format(
                            Locale.ROOT,
                            "%,d %s no maker uses",
                            unusedZones.size(),
                            unusedZones.size() == 1 ? "zone" : "zones"),
                    showUnused ? "Hide" : "Show",
                    "Delete all");
            if (housekeepingPressed == 1) {
                showUnused = !showUnused;
                visible = true;
            } else if (housekeepingPressed == 2) {
                ImGui.openPopup("spawn-unused-confirm");
            }
            GeodataWidgets.tip(
                    "Zones that spawn nothing: no maker names them, or an earlier zone has the same name and makers"
                            + " find that one. Show draws them in the world.");
            if (ImGui.beginPopup("spawn-unused-confirm")) {
                ImGui.textUnformatted(
                        String.format(Locale.ROOT, "Delete %,d zones no maker uses?", unusedZones.size()));
                ImGui.textColored(GeodataWidgets.FAINT, "One undo step brings them back.");
                space(ROW);
                if (HudWidgets.button("spawn-unused-yes", null, "Delete", 96, 28, true, true)) {
                    var zones = List.copyOf(unusedZones);
                    document.apply(
                            "Delete " + zones.size() + " unused zones",
                            current -> current.withoutTerritories(zones),
                            new Batch("unused zones deleted", Set.of(), Set.copyOf(zones)));
                    merge.reset();
                    ImGui.closeCurrentPopup();
                }
                ImGui.sameLine();
                if (HudWidgets.button("spawn-unused-no", null, "Keep", 96, 28, false, true)) {
                    ImGui.closeCurrentPopup();
                }
                ImGui.endPopup();
            }
        }
        if (!unknownEntries.isEmpty()) {
            if (!unusedZones.isEmpty()) {
                space(BLOCK);
            }
            drawHousekeepingRow(
                    "spawn-unknown",
                    String.format(
                            Locale.ROOT,
                            "%,d %s npcdata.txt lacks",
                            unknownEntries.size(),
                            unknownEntries.size() == 1 ? "entry names an NPC" : "entries name NPCs"),
                    filterUnknown ? "Hide" : "Show",
                    "");
            if (housekeepingPressed == 1) {
                filterUnknown = !filterUnknown;
            }
            GeodataWidgets.tip("The server cannot spawn an NPC it does not know. Show lists their makers.");
        }
    }

    private void drawHousekeepingRow(String id, String text, String first, String second) {
        housekeepingPressed = 0;
        ImGui.pushTextWrapPos(0);
        ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.WARNING);
        ImGui.textUnformatted(text);
        ImGui.popStyleColor();
        ImGui.popTextWrapPos();
        space(ROW);
        if (HudWidgets.button(id + "-first", null, first, ImGui.calcTextSizeX(first) + 24, 24, false, true)) {
            housekeepingPressed = 1;
        }
        if (!second.isEmpty()) {
            ImGui.sameLine(0, 4);
            if (HudWidgets.button(id + "-second", null, second, ImGui.calcTextSizeX(second) + 24, 24, false, true)) {
                housekeepingPressed = 2;
            }
        }
    }

    private void drawHealthSection(NpcPosDocument document, Camera camera) {
        title("CHECK SPAWNS", true);
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/geodata");
        space(ROW);
        var job = healthJob;
        if (job != null && !job.isDone()) {
            if (GeodataWidgets.progress(
                    "spawn-health-progress",
                    "Checking spawns",
                    "Within " + healthArea + " of the camera",
                    -1,
                    "Stop")) {
                healthCancelled = true;
                job.cancel(true);
                healthJob = null;
            }
            return;
        }
        if (job != null) {
            healthJob = null;
            try {
                healthFindings = job.get();
                healthSelected = null;
                healthList.reset(List.of());
            } catch (Exception failure) {
                setMessage("The check failed: " + failure.getMessage(), GeodataWidgets.BLOCKED);
            }
        }
        var labels = new String[HEALTH_RADII.length];
        for (var i = 0; i < labels.length; i++) {
            labels[i] = SpawnPanelWidgets.compact(HEALTH_RADII[i]);
        }
        ImGui.alignTextToFramePadding();
        ImGui.textColored(GeodataWidgets.MUTED, "Within");
        ImGui.sameLine(0, ROW);
        var picked =
                GeodataWidgets.segmented("spawn-health-radius", labels, healthRadius, ImGui.getContentRegionAvailX());
        if (picked >= 0) {
            healthRadius = picked;
        }
        GeodataWidgets.tip("How far around the camera the check looks, in world units.");
        space(ROW);
        ImGui.beginDisabled(!ground.isAvailable());
        if (GeodataWidgets.actionButton("spawn-health-run", Glyph.SEARCH, "Check spawns", -1, true)) {
            startHealth(document, camera);
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                ground.isAvailable()
                        ? "Fixed NPCs on blocked or missing ground or out of reach, zones with too few spawn points,"
                                + " zones above the ground, zones without geodata and NPCs npcdata.txt lacks."
                        : "Needs a geodata folder.");
        var findings = healthFindings;
        if (findings == null) {
            return;
        }
        space(BLOCK);
        if (findings.isEmpty()) {
            GeodataWidgets.dot(GeodataWidgets.OPEN);
            ImGui.textColored(GeodataWidgets.OPEN, "No problems within " + healthArea + ".");
            return;
        }
        drawHealthFindingList(document, camera, findings);
    }

    private void drawHealthFindingList(NpcPosDocument document, Camera camera, List<Finding> findings) {
        var counts = new EnumMap<Kind, Integer>(Kind.class);
        for (var finding : findings) {
            counts.merge(finding.kind(), 1, Integer::sum);
        }
        var groups = new ArrayList<Group>();
        counts.forEach(
                (kind, count) -> groups.add(new Group(kind.name(), kind.title, severity(kind), count, helpText(kind))));
        healthList.groupCheckboxes("spawn-health", groups);
        var shown = findings.stream()
                .filter(finding -> healthList.isShown(finding.kind().name()))
                .toList();
        space(ROW);
        var page = healthList.drawPager("spawn-health", shown.size());
        for (var i = page[0]; i < page[1]; i++) {
            var finding = shown.get(i);
            space(ROW);
            ImGui.pushID("finding" + i);
            var pressed = FindingWidgets.card(
                    "spawn-finding",
                    severity(finding.kind()),
                    finding.kind().title,
                    finding.where() + " · " + finding.detail(),
                    fixLabels(finding.kind()),
                    finding == healthSelected);
            ImGui.popID();
            if (pressed == FindingWidgets.FLY) {
                healthSelected = finding;
                flyToFinding(camera, finding);
            } else if (pressed >= 0) {
                healthSelected = finding;
                if (fix(document, finding)) {
                    var left = new ArrayList<>(findings);
                    left.remove(finding);
                    healthFindings = left;
                    break;
                }
            }
        }
    }

    private void startHealth(NpcPosDocument document, Camera camera) {
        var currentIndex = index;
        if (currentIndex == null) {
            return;
        }
        var state = document.getState();
        var eye = camera.getPosition();
        var radius = HEALTH_RADII[healthRadius];
        var x = eye.getX();
        var y = eye.getY();
        var makers = new LinkedHashSet<Integer>();
        for (var territory : currentIndex.findTerritoriesNear(x - radius, y - radius, x + radius, y + radius)) {
            var zone =
                    territory < state.territories().size() ? state.territories().get(territory) : null;
            if (zone != null
                    && Math.hypot(zone.centerX() - x, zone.centerY() - y) <= radius
                    && isInLoadedTiles(zone.minX(), zone.minY(), zone.maxX(), zone.maxY())) {
                for (var maker : currentIndex.getMakersOf(territory)) {
                    makers.add(maker);
                }
            }
        }
        for (var placement : currentIndex.findPlacementsNear(x - radius, y - radius, x + radius, y + radius)) {
            if (Math.hypot(placement.x() - x, placement.y() - y) <= radius
                    && isInLoadedTiles(placement.x(), placement.y())) {
                makers.add(placement.makerIndex());
            }
        }
        var npcs = source.getNpcs();
        healthArea = SpawnPanelWidgets.compact(radius) + " units";
        healthCancelled = false;
        var list = List.copyOf(makers);
        healthJob = indexer.submit(() -> {
            var data = ground.readArea(x - radius - 4096, y - radius - 4096, x + radius + 4096, y + radius + 4096);
            if (data == null) {
                return List.<Finding>of();
            }
            return SpawnHealth.check(state, list, npcs, data, () -> healthCancelled);
        });
    }

    private void flyToFinding(Camera camera, Finding finding) {
        var document = source.getDocument();
        if (document == null
                || finding.maker() >= document.getState().makers().size()
                || document.getState().makers().get(finding.maker()) == null) {
            return;
        }
        visible = true;
        frame(
                camera,
                new Bounds3f(
                        (float) finding.x() - 300,
                        (float) finding.y() - 300,
                        (float) finding.z(),
                        (float) finding.x() + 300,
                        (float) finding.y() + 300,
                        (float) finding.z() + 200),
                -45);
    }

    private boolean fix(NpcPosDocument document, Finding finding) {
        var state = document.getState();
        var maker = finding.maker() < state.makers().size() ? state.makers().get(finding.maker()) : null;
        if (maker == null) {
            setMessage("The maker is gone.", GeodataWidgets.WARNING);
            return false;
        }
        switch (finding.kind()) {
            case FEET_BLOCKED -> {
                if (finding.slot() < 0
                        || finding.slot() >= maker.npcs().size()
                        || !(maker.npcs().get(finding.slot()).position() instanceof Fixed fixed)) {
                    return false;
                }
                var groundZ = ground.getGroundAt(fixed.x(), fixed.y(), fixed.z() + 64);
                if (Double.isNaN(groundZ)) {
                    groundZ = ground.getGroundAt(fixed.x(), fixed.y(), 40000);
                }
                if (Double.isNaN(groundZ)) {
                    setMessage(
                            "No floor under " + maker.npcs().get(finding.slot()).npcName() + ".",
                            GeodataWidgets.WARNING);
                    return false;
                }
                var position = new Fixed(fixed.x(), fixed.y(), (int) Math.round(groundZ), fixed.heading());
                var makerIndex = finding.maker();
                var slot = finding.slot();
                document.apply(
                        "Drop NPC to the ground",
                        current -> SpawnEdits.replaceNpc(current, makerIndex, slot, npc -> npc.withPosition(position)));
            }
            case FEW_POINTS, FLOATING_ZONE -> {
                if (finding.territory() < 0 || state.territories().get(finding.territory()) == null) {
                    return false;
                }
                if (!fitHeights(document, state.territories().get(finding.territory()))) {
                    setMessage(
                            "The ground under the zone is not known yet. Fly closer and try again.",
                            GeodataWidgets.WARNING);
                    return false;
                }
            }
            case UNKNOWN_NPC -> {
                var makerIndex = finding.maker();
                var slot = finding.slot();
                document.apply("Remove NPC entry", current -> current.withNpc(makerIndex, slot, null));
            }
            default -> {
                return false;
            }
        }
        merge.reset();
        return true;
    }

    private boolean fitHeights(NpcPosDocument document, SpawnTerritory territory) {
        var drape = ground.createDrapeNow(territory);
        if (drape == null || !drape.grounded()) {
            return false;
        }
        var low = Float.MAX_VALUE;
        var high = -Float.MAX_VALUE;
        for (var i = 2; i < drape.points().length; i += 3) {
            low = Math.min(low, drape.points()[i]);
            high = Math.max(high, drape.points()[i]);
        }
        var newBottom = Math.round(low) - 200;
        var newTop = Math.round(high) + 400;
        document.apply(
                "Fit zone heights", state -> SpawnEdits.withZoneHeights(state, territory.index(), newBottom, newTop));
        merge.reset();
        return true;
    }

    private void drawZoneCard() {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        draw.channelsSplit(2);
        draw.channelsSetCurrent(1);
        ImGui.setCursorScreenPos(x + 10, y + 8);
        ImGui.beginGroup();
        ImGui.textUnformatted("Drawing a zone");
        ImGui.textColored(GeodataWidgets.MUTED, drawReadout());
        space(ROW);
        var half = (width - 20 - ImGui.getStyle().getItemSpacingX()) / 2;
        ImGui.beginDisabled(drawnCorners.size() < 3);
        var close = HudWidgets.button("spawn-draw-close", null, "Close zone", half, 26, true, true);
        ImGui.endDisabled();
        GeodataWidgets.tip("Makes the zone, with a maker for it.", "Enter");
        ImGui.sameLine();
        var cancel = HudWidgets.button("spawn-draw-cancel", null, "Cancel", half, 26, false, true);
        GeodataWidgets.tip("Stop drawing.", "Esc");
        ImGui.endGroup();
        var bottom = ImGui.getItemRectMaxY() + 8;
        draw.channelsSetCurrent(0);
        draw.addRectFilled(x, y, x + width, bottom, GeodataWidgets.CARD, 8f);
        draw.addRect(x, y, x + width, bottom, ImColor.rgba(92, 219, 241, 90), 8, 0, 1);
        draw.channelsMerge();
        ImGui.setCursorScreenPos(x, bottom);
        ImGui.dummy(width, 0);
        var document = source.getDocument();
        if (close && document != null) {
            closeDrawnZone(document);
        } else if (cancel) {
            drawingZone = false;
            drawnCorners.clear();
            clicks.cancel();
        }
    }

    private String drawReadout() {
        var corners = new ArrayList<int[]>(drawnCorners);
        var pointer = clicks.getPointer();
        if (pointer != null) {
            corners.add(new int[] {(int) pointer[0], (int) pointer[1], (int) pointer[2]});
        }
        var area = 0.0;
        for (int i = 0, j = corners.size() - 1; i < corners.size(); j = i++) {
            area += (double) corners.get(j)[0] * corners.get(i)[1] - (double) corners.get(i)[0] * corners.get(j)[1];
        }
        area = Math.abs(area) / 2;
        var count = drawnCorners.size();
        return (count == 1 ? "1 corner" : count + " corners")
                + (corners.size() >= 3 ? " · " + SpawnPanelWidgets.compact(Math.round(area)) + " units²" : "")
                + (count < 3
                        ? " · click " + (3 - count) + " more on the ground"
                        : " · the first corner or Enter closes it");
    }

    private void closeDrawnZone(NpcPosDocument document) {
        if (drawnCorners.size() < 3) {
            return;
        }
        var state = document.getState();
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
        var bottom = low - 200;
        var top = high + 400;
        var points = new ArrayList<TerritoryPoint>();
        for (var corner : drawnCorners) {
            points.add(new TerritoryPoint(corner[0], corner[1], bottom, top));
        }
        var name = SpawnNames.newZoneName(state, sumX / drawnCorners.size(), sumY / drawnCorners.size());
        var territory = SpawnEdits.newZone(state.territories().size(), name, points);
        var makerIndex = state.makers().size();
        var maker = SpawnMaker.newMaker(makerIndex, List.of(name), SpawnNames.newMakerName(state, name), List.of());
        document.apply(
                "Draw spawn zone", current -> current.withTerritory(territory).withMaker(maker));
        merge.reset();
        drawingZone = false;
        closingHot = false;
        drawnCorners.clear();
        clicks.cancel();
        selectedMaker = makerIndex;
        editingMaker = makerIndex;
        selectedSlot = -1;
        visible = true;
    }

    private List<Share> compositionOf(NpcPosDocument document) {
        var state = document.getState();
        var currentScope = listedVersion(state);
        if (state != compositionState || currentScope != compositionScope) {
            composition = countKinds(state, true);
            compositionState = state;
            compositionScope = currentScope;
        }
        return composition;
    }

    private List<Share> countKinds(State state, boolean listedOnly) {
        var counts = new EnumMap<SpawnStyle, Long>(SpawnStyle.class);
        var npcs = source.getNpcs();
        for (var makerIndex = 0; makerIndex < state.makers().size(); makerIndex++) {
            var maker = state.makers().get(makerIndex);
            if (maker == null || listedOnly && !isListed(state, makerIndex)) {
                continue;
            }
            for (var npc : maker.npcs()) {
                var kind = maker.isConditional() ? SpawnStyle.EVENT : SpawnStyle.of(npcs.getNpc(npc.npcName()));
                counts.merge(kind, (long) Math.max(1, npc.total()), Long::sum);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new Share(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(Share::count).reversed())
                .toList();
    }

    private void refreshNearby(State state, Camera camera) {
        nearest = List.of();
        nearbyMakers = List.of();
        var currentIndex = index;
        if (currentIndex == null) {
            return;
        }
        var eye = camera.getPosition();
        var reach = nearbyReach();
        var distances = new HashMap<Integer, Double>();
        for (var territoryIndex : currentIndex.findTerritoriesNear(
                eye.getX() - reach, eye.getY() - reach, eye.getX() + reach, eye.getY() + reach)) {
            var territory = territoryIndex < state.territories().size()
                    ? state.territories().get(territoryIndex)
                    : null;
            if (territory == null
                    || !isInLoadedTiles(territory.minX(), territory.minY(), territory.maxX(), territory.maxY())) {
                continue;
            }
            var away = Math.hypot(territory.centerX() - eye.getX(), territory.centerY() - eye.getY());
            for (var maker : currentIndex.getMakersOf(territoryIndex)) {
                if (isShown(state, maker)) {
                    distances.merge(maker, away, Math::min);
                }
            }
        }
        for (var placement : shownPlacements(state, currentIndex, eye.getX(), eye.getY(), reach)) {
            distances.merge(
                    placement.makerIndex(),
                    Math.hypot(placement.x() - eye.getX(), placement.y() - eye.getY()),
                    Math::min);
        }
        nearest = distances.entrySet().stream()
                .sorted(Entry.comparingByValue())
                .limit(6)
                .toList();
        nearbyMakers = nearest.stream().map(Entry::getKey).toList();
    }

    private List<Placement> shownPlacements(State state, SpawnIndex currentIndex, double x, double y, double reach) {
        var shown = new ArrayList<Placement>();
        for (var placement : currentIndex.findPlacementsNear(x - reach, y - reach, x + reach, y + reach)) {
            if (isShown(state, placement.makerIndex()) && isInLoadedTiles(placement.x(), placement.y())) {
                shown.add(placement);
            }
        }
        return shown;
    }

    private double nearbyReach() {
        return Math.max(6000, viewReach);
    }

    private void drawNearby(State state, Camera camera) {
        if (index == null) {
            return;
        }
        title("NEAR THE CAMERA", true);
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/maker");
        space(ROW);
        if (nearest.isEmpty()) {
            ImGui.textColored(
                    GeodataWidgets.FAINT,
                    String.format(
                            Locale.ROOT,
                            "Nothing spawns within %,d units. Fly closer, or search above.",
                            Math.round(nearbyReach())));
            return;
        }
        for (var entry : nearest) {
            var makerIndex = entry.getKey();
            if (makerIndex >= state.makers().size() || state.makers().get(makerIndex) == null) {
                continue;
            }
            var maker = state.makers().get(makerIndex);
            ImGui.pushID("near" + makerIndex);
            var x = ImGui.getCursorScreenPosX();
            var y = ImGui.getCursorScreenPosY();
            var rowHeight = SpawnPanelWidgets.listRowHeight();
            var picked = selecting && chosen.contains(makerIndex);
            if (ImGui.selectable("##near", picked, 0, 0, rowHeight)) {
                if (selecting) {
                    choose(makerIndex, ImGui.getIO().getKeyShift(), nearbyMakers);
                } else {
                    selectedMaker = makerIndex;
                    selectedSlot = -1;
                    focus(camera, makerIndex);
                }
            }
            if (ImGui.isItemHovered()) {
                panelHoverMaker = makerIndex;
            }
            var draw = ImGui.getWindowDrawList();
            if (selecting) {
                SpawnPanelWidgets.checkBox(draw, x + 4, y + (rowHeight - 16) * .5f, picked);
                x += 22;
            }
            var width = ImGui.getItemRectMaxX() - x;
            var style = styleOf(state, makerIndex);
            var names = new ArrayList<String>();
            for (var npc : maker.npcs()) {
                if (names.size() == 2) {
                    names.add("+" + (maker.npcs().size() - 2));
                    break;
                }
                names.add(npc.npcName());
            }
            var levels = SpawnSummary.computeLevels(maker, source.getNpcs());
            var detail = (levels == null ? "" : levels.label() + " · ")
                    + npcCount(maker.totalNpcs())
                    + " · "
                    + String.join(", ", names);
            SpawnPanelWidgets.listRow(
                    draw,
                    x,
                    y,
                    width,
                    style.argb,
                    maker.displayName(),
                    detail,
                    SpawnPanelWidgets.compact(Math.round(entry.getValue())) + " away",
                    "");
            ImGui.popID();
        }
    }

    private List<String[]> hintItems(State state) {
        var items = new ArrayList<String[]>();
        if (drawingZone) {
            items.add(new String[] {"Click", "add corner"});
            items.add(new String[] {"Shift", "free, not on cells"});
            items.add(new String[] {"Backspace", "last corner back"});
            items.add(new String[] {"Enter", "close zone"});
            items.add(new String[] {"Esc", "cancel"});
            return items;
        }
        if (placingMaker >= 0) {
            items.add(new String[] {"Click", "place NPC"});
            items.add(new String[] {"Esc", "cancel"});
            return items;
        }
        if (drag != null) {
            items.add(new String[] {"Release", "drop"});
            items.add(new String[] {"Esc", "cancel"});
            return items;
        }
        if (selecting) {
            items.add(new String[] {"Click a zone or NPC", "choose or take out"});
            items.add(new String[] {"Drag on the map (M)", "choose in a box"});
            items.add(new String[] {"Esc", "leave Select"});
            items.add(new String[] {"F1", "all keys"});
            return items;
        }
        if (selectedMaker < 0) {
            items.add(new String[] {"Hover", "preview NPCs"});
            items.add(new String[] {"Click", "select"});
            items.add(new String[] {"Click again", "next maker"});
            items.add(new String[] {"N", "show or hide"});
            items.add(new String[] {"F1", "all keys"});
            return items;
        }
        if (editingMaker != selectedMaker) {
            var selected =
                    selectedMaker < state.makers().size() ? state.makers().get(selectedMaker) : null;
            if (selected != null
                    && selectedSlot >= 0
                    && selectedSlot < selected.npcs().size()
                    && selected.npcs().get(selectedSlot).position() instanceof Fixed) {
                items.add(new String[] {"Drag the NPC", "move, blue dot turns"});
            }
            items.add(new String[] {"Double-click a zone", "edit in the world"});
            items.add(new String[] {"Double-click an NPC", "open in NPCs"});
            items.add(new String[] {"Click again", "next maker"});
            items.add(new String[] {"F", "focus"});
            items.add(new String[] {"Esc", "deselect"});
            items.add(new String[] {"F1", "all keys"});
            return items;
        }
        items.add(new String[] {"Drag a corner", "reshape"});
        items.add(new String[] {"Drag inside", "move zone and its NPCs"});
        items.add(new String[] {"Shift-drag", "move, keep random NPCs"});
        items.add(new String[] {"Click an edge", "add corner"});
        items.add(new String[] {"Delete", "remove corner"});
        var maker = selectedMaker < state.makers().size() ? state.makers().get(selectedMaker) : null;
        if (maker != null && maker.npcs().stream().anyMatch(npc -> npc.position() instanceof Fixed)) {
            items.add(new String[] {"Drag an NPC", "move, blue dot turns"});
        }
        items.add(new String[] {"F", "focus"});
        items.add(new String[] {UNDO_KEY, "undo"});
        items.add(new String[] {"Esc", "stop editing"});
        items.add(new String[] {"F1", "all keys"});
        return items;
    }

    private void addNewZone(NpcPosDocument document, Camera camera, ViewerRenderBackend backend) {
        var origin = camera.getCursorRayOrigin(0, 0);
        var direction = camera.getCursorRayDirection(0, 0);
        var hit = backend.getSceneRayDistance(0, 0, 60_000);
        double x;
        double y;
        double z;
        if (Float.isFinite(hit) && hit > 0) {
            x = origin.getX() + direction.getX() * hit;
            y = origin.getY() + direction.getY() * hit;
            z = origin.getZ() + direction.getZ() * hit;
        } else {
            x = origin.getX() + direction.getX() * 800;
            y = origin.getY() + direction.getY() * 800;
            var groundZ = ground.getGroundAt(x, y, origin.getZ());
            z = Double.isNaN(groundZ) ? origin.getZ() - 200 : groundZ;
        }
        var state = document.getState();
        var name = SpawnNames.newZoneName(state, x, y);
        var half = 400;
        var bottom = (int) Math.round(z) - 200;
        var top = (int) Math.round(z) + 600;
        var cx = (int) Math.round(x);
        var cy = (int) Math.round(y);
        var points = SpawnEdits.square(cx, cy, half, bottom, top);
        var territory = SpawnEdits.newZone(state.territories().size(), name, points);
        var makerIndex = state.makers().size();
        var maker = SpawnMaker.newMaker(makerIndex, List.of(name), SpawnNames.newMakerName(state, name), List.of());
        document.apply(
                "New spawn zone", current -> current.withTerritory(territory).withMaker(maker));
        selectedMaker = makerIndex;
        editingMaker = makerIndex;
        selectedSlot = -1;
        visible = true;
    }

    private void edit(NpcPosDocument document, String key, String label, UnaryOperator<State> change) {
        var now = System.nanoTime();
        if (merge.shouldAmend(key, now, document.canUndo(), document.getRevision())) {
            document.amend(change);
        } else {
            document.apply(label, change);
        }
        merge.record(key, now, document.getRevision());
    }

    private void drawMakerSection(NpcPosDocument document, Camera camera) {
        var state = document.getState();
        var maker = state.makers().get(selectedMaker);
        var style = styleOf(state, selectedMaker);
        var currentIndex = index;
        if (!drawMakerCard(maker, style, camera)) {
            return;
        }
        var where = currentIndex != null && isNotLoaded(state, currentIndex, selectedMaker)
                ? makerLocation(state, selectedMaker)
                : null;
        if (where != null) {
            var tile = GeoTile.fromWorld((int) Math.floor(where[0]), (int) Math.floor(where[1]));
            space(BLOCK);
            GeodataWidgets.note(
                    "Region "
                            + tile.x()
                            + "_"
                            + tile.y()
                            + " is not loaded in this session. Its zones and NPCs show once you load it from the main"
                            + " menu.",
                    GeodataWidgets.WARNING);
        }
        if (!maker.spawnTime().isEmpty()
                || !maker.extended() && !maker.aiParameters().isEmpty()
                || !maker.bannedTerritoryNames().isEmpty()) {
            space(BLOCK);
        }
        if (!maker.spawnTime().isEmpty()) {
            GeodataWidgets.value("Spawns", "during " + maker.spawnTime(), SpawnStyle.EVENT.getImguiColor(1f));
        }
        if (!maker.extended() && !maker.aiParameters().isEmpty()) {
            GeodataWidgets.value("Parameters", maker.aiParameters());
        }
        if (!maker.bannedTerritoryNames().isEmpty()) {
            GeodataWidgets.value(
                    "Excluded", String.join(", ", maker.bannedTerritoryNames()), ImColor.rgba(255, 120, 110, 255));
        }
        space(BLOCK);
        drawZoneMap(state, maker, style, camera);
        space(BLOCK);
        var parts = new ArrayList<Part>();
        for (var npc : maker.npcs()) {
            var kind = maker.isConditional()
                    ? SpawnStyle.EVENT
                    : SpawnStyle.of(source.getNpcs().getNpc(npc.npcName()));
            parts.add(new Part(kind.argb, Math.max(1, npc.total())));
        }
        SpawnPanelWidgets.fill(parts, maker.maximumNpc());
        space(ROW);
        var maximum = new ImInt(Math.max(0, maker.maximumNpc()));
        if (GeodataWidgets.integer("Max NPC", maximum, 1)) {
            var value = Math.max(0, maximum.get());
            edit(
                    document,
                    "max/" + selectedMaker,
                    "Change maximum NPC count",
                    current -> current.withMaker(
                            current.makers().get(selectedMaker).withMaximumNpc(value)));
        }
        var total = maker.totalNpcs();
        if (maker.maximumNpc() >= 0 && total > maker.maximumNpc()) {
            if (GeodataWidgets.actionButton("spawn-fit-max", null, "Raise to " + total, -1, false)) {
                edit(
                        document,
                        "max/" + selectedMaker,
                        "Change maximum NPC count",
                        current -> current.withMaker(
                                current.makers().get(selectedMaker).withMaximumNpc(total)));
            }
        }
        drawNamesEditor(document, maker);
        if (maker.extended()) {
            drawAiEditor(document, maker);
        }
        if (currentIndex != null) {
            var first = false;
            for (var name : maker.territoryNames()) {
                var territory = currentIndex.getTerritory(state, name);
                if (territory != null) {
                    drawZoneEditor(
                            document,
                            territory,
                            maker.npcs().isEmpty() || maker.npcs().stream().anyMatch(SpawnNpc::isAnywhere),
                            first);
                    first = false;
                }
            }
        }
        drawNpcList(document, maker, camera);
        drawMakerActions(document, state, maker, camera);
        space(SECTION);
        if (!confirmDelete) {
            if (GeodataWidgets.actionButton("spawn-delete", Glyph.CLOSE, "Delete maker", -1, false)) {
                confirmDelete = true;
                deleteZones = true;
            }
            GeodataWidgets.tip("Removes the maker and its NPC entries, and the zones no other maker uses.");
        } else {
            confirmDeleteMaker(document, maker);
        }
    }

    private void drawMakerActions(NpcPosDocument document, State state, SpawnMaker maker, Camera camera) {
        space(SECTION);
        var currentBackend = backend;
        ImGui.beginDisabled(currentBackend == null);
        var duplicate = GeodataWidgets.rowButton("Duplicate here", 0, 2) && currentBackend != null;
        ImGui.endDisabled();
        GeodataWidgets.tip(
                "Copies the maker and its zones to the middle of the view, with free names. Entries, AI and caps"
                        + " stay.");
        if (GeodataWidgets.rowButton("Copy as text", 1, 2)) {
            ImGui.setClipboardText(SpawnCopies.copyAsText(
                    state, selectedMaker, document.getFile().getSource().lineEnding()));
            setMessage(
                    "Copied " + maker.displayName()
                            + " and its zones as npcpos.txt text. Paste maker here adds it elsewhere.",
                    GeodataWidgets.MUTED);
        }
        GeodataWidgets.tip("The maker and its zones in npcpos.txt syntax, for Paste maker here or another file.");
        var where = makerLocation(state, selectedMaker);
        if (where != null) {
            var tile = GeoTile.fromWorld((int) Math.floor(where[0]), (int) Math.floor(where[1]));
            var region = tile.x() + "_" + tile.y();
            if (GeodataWidgets.actionButton(
                    "spawn-select-region", Glyph.CURSOR, "Select every maker in " + region, -1, false)) {
                selectRegion(state, tile);
            }
            GeodataWidgets.tip(
                    "Choose every maker whose zone or fixed NPC lies in region " + region + " for a batch edit.");
        }
        if (duplicate && currentBackend != null) {
            duplicateMaker(document, camera, currentBackend);
        }
    }

    private void selectRegion(State state, GeoTile tile) {
        var picked = new ArrayList<Integer>();
        for (var i = 0; i < state.makers().size(); i++) {
            if (state.makers().get(i) == null) {
                continue;
            }
            var where = makerLocation(state, i);
            if (where != null
                    && GeoTile.fromWorld((int) Math.floor(where[0]), (int) Math.floor(where[1]))
                            .equals(tile)) {
                picked.add(i);
            }
        }
        selecting = true;
        chosen.addAll(picked);
        selectedMaker = -1;
        selectedSlot = -1;
        setMessage(
                String.format(Locale.ROOT, "%,d makers of %d_%d selected.", picked.size(), tile.x(), tile.y()),
                GeodataWidgets.MUTED);
    }

    private void confirmDeleteMaker(NpcPosDocument document, SpawnMaker maker) {
        var state = document.getState();
        var orphans = state.zonesOnlyUsedBy(maker.index());
        var entries = maker.npcs().size();
        ImGui.pushTextWrapPos(0);
        ImGui.textColored(
                GeodataWidgets.BLOCKED,
                "Delete "
                        + maker.displayName()
                        + (entries == 0 ? "?" : " and its " + entries + (entries == 1 ? " entry?" : " entries?")));
        ImGui.popTextWrapPos();
        if (!orphans.isEmpty()) {
            var names = new ArrayList<String>();
            for (var zone : orphans) {
                names.add(state.territories().get(zone).name());
            }
            space(ROW);
            if (ImGui.checkbox(
                    (orphans.size() == 1 ? "Also delete its zone" : "Also delete its " + orphans.size() + " zones")
                            + "##spawn-delete-zones",
                    deleteZones)) {
                deleteZones = !deleteZones;
            }
            GeodataWidgets.tip(
                    "No other maker uses " + String.join(", ", names) + ". Without a maker a zone spawns nothing.");
            ImGui.pushTextWrapPos(0);
            ImGui.textColored(GeodataWidgets.FAINT, String.join(", ", names));
            ImGui.popTextWrapPos();
        }
        space(ROW);
        if (GeodataWidgets.rowButton("Delete", 0, 2)) {
            var removed = maker.index();
            var zones = deleteZones ? orphans : List.<Integer>of();
            document.apply(
                    zones.isEmpty() ? "Delete maker" : "Delete maker and zones",
                    current -> SpawnEdits.deleteMaker(current, removed, zones));
            selectedMaker = -1;
            selectedSlot = -1;
            confirmDelete = false;
        }
        if (GeodataWidgets.rowButton("Keep", 1, 2)) {
            confirmDelete = false;
        }
    }

    private MakerAiCatalog aiCatalog(NpcPosDocument document) {
        if (aiCatalogFile != document.getFile()) {
            aiCatalog = MakerAiCatalog.of(document.getFile().getMakers());
            aiCatalogFile = document.getFile();
        }
        return aiCatalog;
    }

    private void drawNamesEditor(NpcPosDocument document, SpawnMaker maker) {
        var state = document.getState();
        var makerIndex = maker.index();
        space(BLOCK);
        var zones = maker.territoryNames();
        var label = "Names · " + (zones.isEmpty() ? maker.displayName() : String.join(", ", zones));
        if (HudWidgets.disclosure("spawn-names/" + makerIndex, label, namesOpen)) {
            namesOpen = !namesOpen;
        }
        GeodataWidgets.tip(
                "The names npcpos.txt gives the zone and the maker. Renaming a zone renames it in every maker that"
                        + " names it.");
        if (!namesOpen) {
            return;
        }
        space(ROW);
        var spacing = ImGui.getStyle().getItemSpacingX();
        var half = (ImGui.getContentRegionAvailX() - spacing) / 2;
        for (var i = 0; i < zones.size(); i++) {
            var zone = state.territory(zones.get(i));
            if (zone == null) {
                continue;
            }
            var zoneName = new ImString(zone.name(), 64);
            var makerName = new ImString(maker.name(), 64);
            var nameable = i == 0 && (maker.extended() || !maker.name().isEmpty());
            var changed = nameable
                    ? GeodataWidgets.columns(
                            () -> GeodataWidgets.compactText(
                                    "Zone", "##zone" + zone.index(), "zone name", zoneName, half),
                            () -> GeodataWidgets.compactText("Maker", "##maker", "maker name", makerName, half))
                    : new boolean[] {
                        GeodataWidgets.compactText(
                                "Zone", "##zone" + zone.index(), "zone name", zoneName, ImGui.getContentRegionAvailX()),
                        false
                    };
            if (changed[0]) {
                renameZone(document, zone, zoneName.get().strip());
            }
            if (changed[1]) {
                renameMaker(document, makerIndex, makerName.get().strip());
            }
        }
    }

    private void renameZone(NpcPosDocument document, SpawnTerritory zone, String name) {
        if (name.equals(zone.name())) {
            return;
        }
        if (!SpawnNames.isValid(name)) {
            setMessage(
                    "A zone name uses letters, digits and underscores, such as giran_2223_01.", GeodataWidgets.WARNING);
            return;
        }
        if (document.getState().territory(name) != null) {
            setMessage("Another zone is already called " + name + ".", GeodataWidgets.WARNING);
            return;
        }
        document.apply("Rename zone", state -> state.withTerritoryName(zone.index(), name));
        merge.reset();
    }

    private void renameMaker(NpcPosDocument document, int makerIndex, String name) {
        var maker = document.getState().makers().get(makerIndex);
        if (maker == null || name.equals(maker.name())) {
            return;
        }
        if (!SpawnNames.isValid(name)) {
            setMessage(
                    "A maker name uses letters, digits and underscores, such as giran_2223_01m1.",
                    GeodataWidgets.WARNING);
            return;
        }
        if (document.getState().makers().stream()
                .anyMatch(other -> other != null && other.name().equals(name))) {
            setMessage("Another maker is already called " + name + ".", GeodataWidgets.WARNING);
            return;
        }
        document.apply(
                "Rename maker",
                state -> state.withMaker(state.makers().get(makerIndex).withName(name)));
        merge.reset();
    }

    private void drawAiEditor(NpcPosDocument document, SpawnMaker maker) {
        var catalog = aiCatalog(document);
        var parameters = AiParameters.parse(maker.aiParameters());
        var makerIndex = maker.index();
        tightenSpacing();
        var label = "AI "
                + maker.ai()
                + (parameters.isEmpty()
                        ? ""
                        : " · " + parameters.size() + (parameters.size() == 1 ? " parameter" : " parameters"));
        if (HudWidgets.disclosure("spawn-ai/" + makerIndex, label, aiOpen)) {
            aiOpen = !aiOpen;
        }
        GeodataWidgets.tip(
                "The maker's AI decides when and how it spawns: default_maker spawns at once, event_maker during an"
                        + " event, and so on. The list comes from the makers in this file.");
        if (!aiOpen) {
            return;
        }
        space(ROW);
        var known = catalog.getAi(maker.ai());
        ImGui.setNextItemWidth(-1);
        if (HudWidgets.beginCombo("##ai", maker.ai(), ImGuiComboFlags.HeightLarge)) {
            if (ImGui.isWindowAppearing()) {
                aiFilter.set("");
                ImGui.setKeyboardFocusHere();
            }
            ImGui.setNextItemWidth(-1);
            ImGui.inputTextWithHint("##ai-filter", "Filter", aiFilter);
            var filter = aiFilter.get().strip().toLowerCase(Locale.ROOT);
            var shown = 0;
            for (var ai : catalog.getAis()) {
                if (!filter.isEmpty() && !ai.name().toLowerCase(Locale.ROOT).contains(filter)) {
                    continue;
                }
                if (shown++ >= 300) {
                    ImGui.textColored(GeodataWidgets.MUTED, "Type to narrow the list.");
                    break;
                }
                if (ImGui.selectable(ai.name() + "##" + ai.name(), ai.name().equals(maker.ai()))) {
                    var kept = new LinkedHashMap<String, String>();
                    parameters.forEach((name, value) -> {
                        if (ai.parameter(name) != null) {
                            kept.put(name, value);
                        }
                    });
                    setAi(document, makerIndex, "Change maker AI", null, ai.name(), kept);
                }
                ImGui.sameLine();
                ImGui.textColored(GeodataWidgets.MUTED, ai.makers() + (ai.makers() == 1 ? " maker" : " makers"));
            }
            ImGui.endCombo();
        }
        GeodataWidgets.tip(
                "AIs used by makers in this file, most used first. Parameters the new AI is not given elsewhere are"
                        + " dropped.");
        var spacing = ImGui.getStyle().getItemSpacingX();
        for (var entry : List.copyOf(parameters.entrySet())) {
            var name = entry.getKey();
            var parameter = known == null ? null : known.parameter(name);
            ImGui.pushID("ai-param/" + name);
            space(ROW);
            ImGui.textColored(GeodataWidgets.MUTED, name);
            var value = new ImString(entry.getValue(), 128);
            ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() - (26 + spacing) * (parameter == null ? 1 : 2));
            ImGui.inputText("##value", value);
            if (ImGui.isItemDeactivatedAfterEdit()) {
                var written = AiParameters.formatValue(value.get());
                if (!written.isEmpty() && !written.equals(entry.getValue())) {
                    var changed = new LinkedHashMap<>(parameters);
                    changed.put(name, written);
                    setAi(
                            document,
                            makerIndex,
                            "Change AI parameter",
                            "ai/" + makerIndex + "/" + name,
                            maker.ai(),
                            changed);
                }
            }
            GeodataWidgets.tip("Text is written in brackets, as [festival]; numbers as they are.");
            if (parameter != null) {
                ImGui.sameLine();
                if (GeodataWidgets.iconButton("values", Glyph.CHEVRON, true)) {
                    ImGui.openPopup("ai-values");
                }
                GeodataWidgets.tip("Values other " + maker.ai() + " makers give " + name + ".");
                if (ImGui.beginPopup("ai-values")) {
                    var shown = 0;
                    for (var seen : parameter.values()) {
                        if (shown++ >= 40) {
                            break;
                        }
                        if (ImGui.selectable(
                                seen.text() + "##" + seen.text(), seen.text().equals(entry.getValue()))) {
                            var changed = new LinkedHashMap<>(parameters);
                            changed.put(name, seen.text());
                            setAi(document, makerIndex, "Change AI parameter", null, maker.ai(), changed);
                        }
                        ImGui.sameLine();
                        ImGui.textColored(
                                GeodataWidgets.MUTED, seen.makers() + (seen.makers() == 1 ? " maker" : " makers"));
                    }
                    ImGui.endPopup();
                }
            }
            ImGui.sameLine();
            if (GeodataWidgets.iconButton("remove", Glyph.CLOSE, true)) {
                var changed = new LinkedHashMap<>(parameters);
                changed.remove(name);
                setAi(document, makerIndex, "Remove AI parameter", null, maker.ai(), changed);
            }
            GeodataWidgets.tip("Remove " + name + ": the AI then uses its default.");
            ImGui.popID();
        }
        space(ROW);
        if (GeodataWidgets.actionButton("spawn-ai-add", Glyph.PLUS, "Add parameter", -1, false)) {
            ImGui.openPopup("spawn-ai-add-popup");
            aiFilter.set("");
        }
        if (ImGui.beginPopup("spawn-ai-add-popup")) {
            var offered = 0;
            if (known != null) {
                for (var parameter : known.parameters()) {
                    if (parameters.containsKey(parameter.name())) {
                        continue;
                    }
                    offered++;
                    if (ImGui.selectable(parameter.name() + "##" + parameter.name())) {
                        var changed = new LinkedHashMap<>(parameters);
                        changed.put(parameter.name(), parameter.usual());
                        setAi(document, makerIndex, "Add AI parameter", null, maker.ai(), changed);
                    }
                    ImGui.sameLine();
                    ImGui.textColored(
                            GeodataWidgets.MUTED,
                            parameter.makers()
                                    + (parameter.makers() == 1 ? " maker" : " makers")
                                    + " · usually "
                                    + parameter.usual());
                }
            }
            if (offered == 0) {
                ImGui.textColored(
                        GeodataWidgets.FAINT, "No other " + maker.ai() + " maker in the file has more parameters.");
            }
            space(ROW);
            ImGui.setNextItemWidth(220);
            var typed = ImGui.inputTextWithHint(
                    "##ai-new", "Another name, then Enter", aiFilter, ImGuiInputTextFlags.EnterReturnsTrue);
            GeodataWidgets.tip("A parameter no maker in the file uses yet. It starts at 0.");
            var name = aiFilter.get().strip().replace("[", "").replace("]", "");
            if (typed && !name.isEmpty() && !parameters.containsKey(name)) {
                var changed = new LinkedHashMap<>(parameters);
                changed.put(name, "0");
                setAi(document, makerIndex, "Add AI parameter", null, maker.ai(), changed);
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
    }

    private void setAi(
            NpcPosDocument document,
            int makerIndex,
            String label,
            @Nullable String key,
            String ai,
            Map<String, String> parameters) {
        UnaryOperator<State> change =
                state -> state.withMaker(state.makers().get(makerIndex).withAi(ai, AiParameters.format(parameters)));
        if (key == null) {
            document.apply(label, change);
            merge.reset();
        } else {
            edit(document, key, label, change);
        }
    }

    private void drawZoneEditor(
            NpcPosDocument document, SpawnTerritory territory, boolean randomEntries, boolean first) {
        if (first) {
            space(BLOCK);
        } else {
            tightenSpacing();
        }
        var label =
                "Height range " + territory.bottom() + " to " + territory.top() + (randomEntries ? "" : " · unused");
        if (HudWidgets.disclosure("spawn-heights/" + territory.index(), label, heightsOpen)) {
            heightsOpen = !heightsOpen;
        }
        GeodataWidgets.tip("Random NPCs spawn only on ground between Bottom Z and Top Z of " + territory.name()
                + ". It matters where floors stack, such as in buildings and caves. Fixed NPCs ignore it.");
        if (heightsOpen) {
            drawHeightFields(document, territory);
            space(ROW);
        } else {
            tightenSpacing();
        }
        drawCornerFields(document, territory);
    }

    private void drawCornerFields(NpcPosDocument document, SpawnTerritory territory) {
        var label = "Corners · " + territory.points().size();
        if (HudWidgets.disclosure("spawn-corners/" + territory.index(), label, cornersOpen)) {
            cornersOpen = !cornersOpen;
        }
        GeodataWidgets.tip(
                "Each corner's X and Y in world units, for exact edges. Dragging a corner in the world does the same.");
        if (!cornersOpen) {
            return;
        }
        var numberWidth = ImGui.calcTextSizeX("00") + 6;
        var labelWidth = ImGui.calcTextSizeX("Y") + 6;
        var spacing = ImGui.getStyle().getItemSpacingX();
        var field = (ImGui.getContentRegionAvailX() - numberWidth - 2 * labelWidth - 3 * spacing) / 2;
        for (var i = 0; i < territory.points().size(); i++) {
            var point = territory.points().get(i);
            var corner = i;
            if (i == 0) {
                space(ROW);
            }
            ImGui.pushID("corner" + i);
            var x = new ImInt(point.x());
            var y = new ImInt(point.y());
            ImGui.alignTextToFramePadding();
            ImGui.textColored(GeodataWidgets.FAINT, Integer.toString(i + 1));
            ImGui.sameLine(numberWidth);
            ImGui.textColored(GeodataWidgets.MUTED, "X");
            ImGui.sameLine(0, 4);
            ImGui.setNextItemWidth(field);
            var changedX = ImGui.inputInt("##x", x, 0, 0);
            ImGui.sameLine();
            ImGui.textColored(GeodataWidgets.MUTED, "Y");
            ImGui.sameLine(0, 4);
            ImGui.setNextItemWidth(field);
            var changedY = ImGui.inputInt("##y", y, 0, 0);
            var changed = new boolean[] {changedX, changedY};
            if (changed[0] || changed[1]) {
                var moved = point.withXy(x.get(), y.get());
                edit(document, "corner/" + territory.index() + "/" + i, "Move zone corner", state -> {
                    var current = state.territories().get(territory.index());
                    if (current == null || corner >= current.points().size()) {
                        return state;
                    }
                    var points = new ArrayList<>(current.points());
                    points.set(
                            corner,
                            moved.withHeights(
                                    points.get(corner).bottom(),
                                    points.get(corner).top()));
                    return state.withTerritory(current.withPoints(points));
                });
            }
            ImGui.popID();
        }
    }

    private void drawHeightFields(NpcPosDocument document, SpawnTerritory territory) {
        space(ROW);
        var bottom = new ImInt(territory.bottom());
        var top = new ImInt(territory.top());
        var key = "heights/" + territory.index();
        var half = (ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX()) / 2;
        var changed = GeodataWidgets.columns(
                () -> GeodataWidgets.compactInt("Bottom Z", "##bottom", bottom, half, 50),
                () -> GeodataWidgets.compactInt("Top Z", "##top", top, half, 50));
        if (changed[0]) {
            var value = Math.min(bottom.get(), territory.top() - 1);
            edit(
                    document,
                    key,
                    "Change zone bottom",
                    state -> SpawnEdits.withZoneHeights(state, territory.index(), value, null));
        }
        if (changed[1]) {
            var value = Math.max(top.get(), territory.bottom() + 1);
            edit(
                    document,
                    key,
                    "Change zone top",
                    state -> SpawnEdits.withZoneHeights(state, territory.index(), null, value));
        }
        var drape = ground.getDrape(territory);
        if (drape != null && drape.grounded()) {
            space(ROW);
            if (GeodataWidgets.actionButton("spawn-fit/" + territory.index(), null, "Fit to the ground", -1, false)) {
                var low = Float.MAX_VALUE;
                var high = -Float.MAX_VALUE;
                for (var i = 2; i < drape.points().length; i += 3) {
                    low = Math.min(low, drape.points()[i]);
                    high = Math.max(high, drape.points()[i]);
                }
                var newBottom = Math.round(low) - 200;
                var newTop = Math.round(high) + 400;
                document.apply(
                        "Fit zone heights",
                        state -> SpawnEdits.withZoneHeights(state, territory.index(), newBottom, newTop));
                merge.reset();
            }
            GeodataWidgets.tip(
                    drape.clientFloors()
                            ? "From 200 below the lowest ground on the outline to 400 above the highest. With no"
                                    + " geodata here, the ground is the client's floor."
                            : "From 200 below the lowest ground on the outline to 400 above the highest.");
        }
    }

    private void drawNpcList(NpcPosDocument document, SpawnMaker maker, Camera camera) {
        title("NPCS · " + maker.npcs().size() + (maker.npcs().size() == 1 ? " ENTRY" : " ENTRIES"), true);
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/respawn");
        space(ROW);
        if (maker.npcs().isEmpty()) {
            ImGui.pushTextWrapPos(0);
            ImGui.textColored(GeodataWidgets.WARNING, "No NPCs yet: add one to save the file.");
            ImGui.popTextWrapPos();
            GeodataWidgets.tip("The server cannot read a maker without NPCs. Add one, or delete the maker.");
        }
        var npcs = source.getNpcs();
        var badgeWidth = 0f;
        for (var npc : maker.npcs()) {
            badgeWidth = Math.max(badgeWidth, SpawnPanelWidgets.badgeWidth("\u00d7" + npc.total()));
        }
        for (var slot = 0; slot < maker.npcs().size(); slot++) {
            var npc = maker.npcs().get(slot);
            var info = npcs.getNpc(npc.npcName());
            var kind = maker.isConditional() ? SpawnStyle.EVENT : SpawnStyle.of(info);
            ImGui.pushID("npc" + slot);
            var x = ImGui.getCursorScreenPosX();
            var y = ImGui.getCursorScreenPosY();
            var rowHeight = ImGui.getFontSize() * 2 + 10;
            var rowWidth = ImGui.getContentRegionAvailX();
            ImGui.setNextItemAllowOverlap();
            if (ImGui.selectable("##row", slot == selectedSlot, 0, rowWidth, rowHeight)) {
                selectedSlot = slot == selectedSlot ? -1 : slot;
            }
            if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(0)) {
                selectedSlot = slot;
                npcEditRequest = npc.npcName();
            }
            ImGui.setCursorScreenPos(x + rowWidth - 2 * FOCUS_BUTTON - 6, y + (rowHeight - FOCUS_BUTTON) * .5f);
            if (HudWidgets.button("spawn-npc-edit", Glyph.CARD, "", FOCUS_BUTTON, FOCUS_BUTTON, false, false)) {
                npcEditRequest = npc.npcName();
            }
            GeodataWidgets.tip(
                    "Open this NPC in the NPC editor: what it drops, its skills and numbers.", "Double-click");
            ImGui.setCursorScreenPos(x + rowWidth - FOCUS_BUTTON - 4, y + (rowHeight - FOCUS_BUTTON) * .5f);
            if (HudWidgets.button("spawn-npc-focus", Glyph.FOCUS, "", FOCUS_BUTTON, FOCUS_BUTTON, false, false)) {
                selectedSlot = slot;
                focus(camera, selectedMaker);
            }
            GeodataWidgets.tip(
                    npc.position() instanceof Fixed
                            ? "Move the camera to this NPC."
                            : "Move the camera to one of its NPCs in its zone.");
            ImGui.setCursorScreenPos(x, y + rowHeight);
            ImGui.dummy(0, 0);
            var draw = ImGui.getWindowDrawList();
            SpawnPanelWidgets.badge(
                    draw,
                    x + 2,
                    y + (rowHeight - ImGui.getFontSize() - 6) * .5f,
                    badgeWidth,
                    kind.argb,
                    "\u00d7" + npc.total());
            var badge = badgeWidth;
            draw.addText(
                    x + badge + 12,
                    y + 4,
                    info == null ? GeodataWidgets.WARNING : HudWidgets.TEXT,
                    HudWidgets.truncateToWidth(npc.npcName(), rowWidth - badge - 2 * FOCUS_BUTTON - 26));
            draw.addText(
                    x + badge + 12,
                    y + ImGui.getFontSize() + 6,
                    GeodataWidgets.MUTED,
                    HudWidgets.truncateToWidth(
                            describe(npc, info) + clientNote(info), rowWidth - badge - 2 * FOCUS_BUTTON - 26));
            if (slot == selectedSlot) {
                drawNpcEditor(document, maker, slot, npc);
            }
            ImGui.popID();
        }
        space(ROW);
        if (GeodataWidgets.rowButton("Add NPC", 0, 2)) {
            addNpc(document, maker);
        }
        GeodataWidgets.tip(
                maker.territoryNames().isEmpty()
                        ? "Adds an entry at the zone's middle."
                        : "Adds an entry that spawns at random in the zone.");
        if (GeodataWidgets.rowButton("Add NPC here", 1, 2)) {
            placingMaker = selectedMaker;
            drawingZone = false;
            visible = true;
        }
        GeodataWidgets.tip("The next click on the ground places a fixed NPC there, facing the camera. Esc cancels.");
    }

    private boolean drawMakerCard(SpawnMaker maker, SpawnStyle style, Camera camera) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var height = ImGui.getFontSize() * 2 + 22;
        draw.addRectFilled(x, y, x + width, y + height, GeodataWidgets.CARD, 8f);
        draw.addRectFilled(x, y, x + 4, y + height, style.getImguiColor(1f), 8, ImDrawFlags.RoundCornersLeft);
        var details = new ArrayList<String>();
        var zones = String.join(", ", maker.territoryNames());
        if (!zones.isEmpty() && !maker.displayName().startsWith(zones)) {
            details.add(zones);
        }
        var levels = SpawnSummary.computeLevels(maker, source.getNpcs());
        if (levels != null) {
            details.add(levels.label());
        }
        details.add(maker.isConditional() ? "event spawn" : style.label.toLowerCase(Locale.ROOT));
        if (!maker.ai().isEmpty() && !maker.ai().equals("default_maker")) {
            details.add(maker.ai());
        }
        details.add(maker.line() < 0 ? "new" : "line " + maker.line());
        draw.pushClipRect(x, y, x + width - 124, y + height, true);
        draw.addText(x + 14, y + 9, HudWidgets.TEXT, HudWidgets.truncateToWidth(maker.displayName(), width - 142));
        draw.addText(
                x + 14,
                y + ImGui.getFontSize() + 13,
                GeodataWidgets.MUTED,
                HudWidgets.truncateToWidth(String.join(" · ", details), width - 142));
        draw.popClipRect();
        ImGui.setCursorScreenPos(x + width - 120, y + (height - 26) * .5f);
        var marked = isBookmarked(maker);
        if (HudWidgets.button("spawn-bookmark", Glyph.BOOKMARK, "", 26, 26, marked, false)) {
            toggleBookmark(maker);
        }
        GeodataWidgets.tip(
                marked
                        ? "Remove the bookmark."
                        : "Bookmark this maker. Bookmarks stay with this script folder after a restart.");
        ImGui.sameLine(0, 4);
        var editing = editingMaker == selectedMaker;
        if (HudWidgets.button("spawn-edit", Glyph.PENCIL, "", 26, 26, editing, false)) {
            editingMaker = editing ? -1 : selectedMaker;
        }
        GeodataWidgets.tip(
                editing
                        ? "Stop editing: the zone and its NPCs can no longer be dragged."
                        : "Edit the zone and its NPCs in the world: drag corners, the zone and fixed NPCs.",
                editing ? "Esc" : "Double-click the zone");
        ImGui.sameLine(0, 4);
        if (HudWidgets.button("spawn-focus", Glyph.FOCUS, "", 26, 26, false, false)) {
            focus(camera, selectedMaker);
        }
        GeodataWidgets.tip("Move the camera to this maker.", "F");
        ImGui.sameLine(0, 4);
        if (HudWidgets.button("spawn-deselect", Glyph.CLOSE, "", 26, 26, false, false)) {
            selectedMaker = -1;
            selectedSlot = -1;
            return false;
        }
        GeodataWidgets.tip(isFiltering() ? "Back to the filtered list." : "Back to the overview.", "Esc");
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        return true;
    }

    private void drawZoneMap(State state, SpawnMaker maker, SpawnStyle style, Camera camera) {
        var currentIndex = index;
        var zones = new ArrayList<SpawnTerritory>();
        var banned = new ArrayList<SpawnTerritory>();
        if (currentIndex != null) {
            for (var name : maker.territoryNames()) {
                var territory = currentIndex.getTerritory(state, name);
                if (territory != null) {
                    zones.add(territory);
                }
            }
            for (var name : maker.bannedTerritoryNames()) {
                var territory = currentIndex.getTerritory(state, name);
                if (territory != null) {
                    banned.add(territory);
                }
            }
        }
        var points = new ArrayList<MapPoint>();
        var candidates = currentIndex != null && maker.npcs().stream().anyMatch(SpawnNpc::isAnywhere)
                ? findCandidates(state, currentIndex, selectedMaker, maker)
                : null;
        var npcs = source.getNpcs();
        for (var placed : SpawnPreview.place(maker, candidates, seed + selectedMaker)) {
            var npc = maker.npcs().get(placed.slot());
            var kind = maker.isConditional() ? SpawnStyle.EVENT : SpawnStyle.of(npcs.getNpc(npc.npcName()));
            points.add(new MapPoint(
                    placed.x(),
                    placed.y(),
                    placed.z(),
                    kind.argb,
                    !(npc.position() instanceof Anywhere),
                    placed.slot(),
                    placed.npcName()));
        }
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var look = camera.getPerspectiveLookDir();
        var picked = SpawnPanelWidgets.zoneMap(
                "spawn-map",
                zones,
                banned,
                style.argb,
                points,
                selectedSlot,
                MAP_HEIGHT,
                camera.getPosition().getX(),
                camera.getPosition().getY(),
                Math.toDegrees(Math.atan2(look.getY(), look.getX())),
                showCoverage && candidates != null ? candidates.points() : List.of());
        if (picked != null) {
            selectedSlot = picked.point().slot();
            if (picked.twice()) {
                var point = picked.point();
                frame(
                        camera,
                        new Bounds3f(
                                (float) point.x() - 120,
                                (float) point.y() - 120,
                                (float) point.z(),
                                (float) point.x() + 120,
                                (float) point.y() + 120,
                                (float) point.z() + 160),
                        -30);
            }
        }

        if (maker.npcs().stream().anyMatch(SpawnNpc::isAnywhere)) {
            ImGui.setCursorScreenPos(x + 6, y + 6);
            if (HudWidgets.button("spawn-shuffle", Glyph.RELOAD, "", 26, 26, false, true)) {
                seed++;
                pinnedCandidates.clear();
                pinnedZones.clear();
            }
            GeodataWidgets.tip(
                    candidates == null
                            ? "Random NPCs appear once the ground under the zone is known."
                            : candidates.clientFloors()
                                    ? "Another possible spawn. " + ESTIMATE
                                    : "Another possible spawn: the server picks random positions each time.");
        }
        ImGui.setCursorScreenPos(x, y + MAP_HEIGHT);
        ImGui.dummy(ImGui.getContentRegionAvailX(), 0);
        if (maker.npcs().stream().anyMatch(SpawnNpc::isAnywhere)) {
            space(ROW);
            if (ImGui.checkbox("Show every candidate point##spawn-coverage", showCoverage)) {
                showCoverage = !showCoverage;
                visible = true;
            }
            GeodataWidgets.tip(
                    "Every point of the server's grid where this maker's random NPCs can spawn, as dots in the world"
                            + " and on the map, so a zone over a cliff shows which part spawns.");
            if (showCoverage && candidates != null) {
                ImGui.sameLine();
                ImGui.textColored(
                        GeodataWidgets.FAINT,
                        String.format(
                                Locale.ROOT, "%,d points", candidates.points().size()));
            }
            if (candidates != null && candidates.clientFloors()) {
                space(ROW);
                GeodataWidgets.hint(ESTIMATE);
            }
        }
        if (candidates != null && candidates.points().isEmpty() && !zones.isEmpty()) {
            var bottom = zones.stream().mapToInt(SpawnTerritory::bottom).min().orElse(0);
            var top = zones.stream().mapToInt(SpawnTerritory::top).max().orElse(0);
            space(ROW);
            ImGui.pushTextWrapPos(0);
            ImGui.textColored(
                    GeodataWidgets.WARNING,
                    (candidates.clientFloors() ? "No client floor lies between " : "The geodata has no floor between ")
                            + bottom + " and " + top + " here, so random NPCs cannot be placed.");
            ImGui.popTextWrapPos();
            GeodataWidgets.tip(
                    candidates.clientFloors()
                            ? "Random NPCs stand on a floor inside the zone's height range. With no geodata here,"
                                    + " the floors are the client's, and the server's own geodata can differ."
                            : "Random NPCs stand on a geodata floor inside the zone's height range. A build from the"
                                    + " client can still miss a floor that the server's own geodata has.");
        }
    }

    private void drawNpcEditor(NpcPosDocument document, SpawnMaker maker, int slot, SpawnNpc npc) {
        ImGui.indent(10);
        space(ROW);
        var known = source.getNpcs().getNpc(npc.npcName());
        if (known != null && showModels && models.getStatus(known.id()) == SpawnModels.Status.NOT_IN_CLIENT) {
            ImGui.pushTextWrapPos(0);
            ImGui.textColored(
                    GeodataWidgets.WARNING,
                    "The game client does not know this NPC: its npcgrp.dat has no record for id " + known.id()
                            + ", so there is no model to draw and it stands as a capsule. The server may be newer than"
                            + " the client.");
            ImGui.popTextWrapPos();
            space(ROW);
        }
        var makerIndex = maker.index();
        var key = "npc/" + makerIndex + "/" + slot;
        ImGui.setNextItemWidth(-1);
        if (HudWidgets.beginCombo("##npc", npc.npcName(), ImGuiComboFlags.HeightLarge)) {
            if (ImGui.isWindowAppearing()) {
                npcFilter.set("");
                ImGui.setKeyboardFocusHere();
            }
            ImGui.setNextItemWidth(-1);
            ImGui.inputTextWithHint("##npc-filter", "Name as npcdata or the game writes it, or id", npcFilter);
            var filter = npcFilter.get().strip().toLowerCase(Locale.ROOT);
            var shown = 0;
            for (var name : source.getNpcs().getNames()) {
                var listed = source.getNpcs().getNpc(name);
                var game = listed == null ? "" : gameName(listed);
                if (!filter.isEmpty()
                        && !name.contains(filter)
                        && !game.toLowerCase(Locale.ROOT).contains(filter)
                        && !(listed != null && Integer.toString(listed.id()).equals(filter))) {
                    continue;
                }
                if (shown++ >= 300) {
                    ImGui.textColored(GeodataWidgets.MUTED, "Type to narrow the list.");
                    break;
                }
                var info = source.getNpcs().getNpc(name);
                if (ImGui.selectable(name + "##" + name, name.equals(npc.npcName()))) {
                    document.apply(
                            "Change NPC",
                            state -> SpawnEdits.replaceNpc(
                                    state, makerIndex, slot, current -> current.withNpcName(name)));
                    merge.reset();
                }
                ImGui.sameLine();
                ImGui.textColored(
                        GeodataWidgets.MUTED,
                        (game.isEmpty() || game.equalsIgnoreCase(name) ? "" : game + " · ")
                                + "Lv "
                                + info.level()
                                + " · "
                                + info.type());
            }
            ImGui.endCombo();
        }
        GeodataWidgets.tip(
                "The NPC of this entry, from npcdata.txt. Find it by npcdata's name, the game's name or its id.");
        space(ROW);
        var spacing = ImGui.getStyle().getItemSpacingX();
        var third = (ImGui.getContentRegionAvailX() - spacing * 2) / 3;
        var total = new ImInt(npc.total());
        var respawn = new ImString(npc.respawn(), 32);
        var random = new ImString(npc.respawnRandom(), 32);
        var timing = GeodataWidgets.columns(
                () -> GeodataWidgets.compactInt("Count", "##count", total, third, 0),
                () -> GeodataWidgets.compactText("Respawn", "##respawn", "30sec", respawn, third),
                () -> GeodataWidgets.compactText("Respawn \u00b1", "##respawn-random", "none", random, third));
        if (timing[0]) {
            var value = Math.max(1, total.get());
            edit(
                    document,
                    key + "/total",
                    "Change NPC count",
                    state -> SpawnEdits.replaceNpc(state, makerIndex, slot, current -> current.withTotal(value)));
        }
        if (timing[1]) {
            var value = respawn.get().strip();
            if (SpawnEdits.isTime(value) || value.equals("no")) {
                edit(
                        document,
                        key + "/respawn",
                        "Change respawn",
                        state -> SpawnEdits.replaceNpc(
                                state,
                                makerIndex,
                                slot,
                                current -> current.withRespawn(value, current.respawnRandom())));
            } else {
                setMessage(
                        "A respawn time is a number with sec, min, hour or day, such as 30sec, or no.",
                        GeodataWidgets.WARNING);
            }
        }
        if (timing[2]) {
            var value = random.get().strip();
            if (value.isEmpty() || SpawnEdits.isTime(value)) {
                edit(
                        document,
                        key + "/random",
                        "Change respawn spread",
                        state -> SpawnEdits.replaceNpc(
                                state, makerIndex, slot, current -> current.withRespawn(current.respawn(), value)));
            }
        }
        GeodataWidgets.tip(
                "Count: how many NPCs of this entry. Respawn: time until one comes back after it dies, such as 30sec,"
                        + " 5min, 1hour, 1day or no. Respawn \u00b1: a random part added or taken, such as 10sec.");
        drawRespawnPresets(document, makerIndex, slot, npc);
        space(ROW);
        var mode = npc.position() instanceof Anywhere ? 0 : 1;
        if (npc.position() instanceof Weighted weighted) {
            ImGui.textColored(
                    GeodataWidgets.MUTED, "Chosen from " + weighted.points().size() + " weighted points.");
        } else {
            var picked = GeodataWidgets.segmented("spawn-mode", new String[] {"Random in zone", "Fixed point"}, mode);
            if (picked >= 0 && picked != mode) {
                var position = picked == 0 ? SpawnPosition.ANYWHERE : defaultFixed(document.getState(), maker);
                document.apply(
                        "Change placement",
                        state -> SpawnEdits.replaceNpc(
                                state, makerIndex, slot, current -> current.withPosition(position)));
                merge.reset();
            }
        }
        if (npc.position() instanceof Fixed fixed) {
            space(ROW);
            var values = new int[] {fixed.x(), fixed.y(), fixed.z(), fixed.heading()};
            var fields =
                    new ImInt[] {new ImInt(values[0]), new ImInt(values[1]), new ImInt(values[2]), new ImInt(values[3])
                    };
            var place = GeodataWidgets.columns(
                    () -> GeodataWidgets.compactInt("X", "##pos0", fields[0], third, 0),
                    () -> GeodataWidgets.compactInt("Y", "##pos1", fields[1], third, 0),
                    () -> GeodataWidgets.compactInt("Z", "##pos2", fields[2], third, 0));
            space(ROW);
            var rowX = ImGui.getCursorScreenPosX();
            var rowY = ImGui.getCursorScreenPosY();
            var headingChanged = GeodataWidgets.compactInt("Heading", "##pos3", fields[3], third, 0);
            GeodataWidgets.tip("Heading is the facing, from 0 to 65535 for a full turn: 0 east, 16384 south. Drag the "
                    + "blue handle in the view to turn the NPC.");
            var rowBottom = ImGui.getItemRectMaxY();
            ImGui.setCursorScreenPos(rowX + third + spacing, rowY + ImGui.getTextLineHeightWithSpacing());
            var groundSource = ground.getGroundSource(fixed.x(), fixed.y());
            ImGui.beginDisabled(groundSource == GroundSource.NONE);
            var onGround = HudWidgets.button(
                    "spawn-ground",
                    null,
                    "Put on the ground",
                    third * 2 + spacing,
                    ImGui.getFrameHeight(),
                    false,
                    true);
            ImGui.endDisabled();
            GeodataWidgets.tip(
                    switch (groundSource) {
                        case GEODATA -> "Sets Z to the server geodata under the NPC.";
                        case CLIENT -> "Sets Z to the client's floor under the NPC: no geodata here.";
                        case NONE -> "Needs the ground under the NPC: its geodata region, or its map tile loaded.";
                    });
            rowBottom = Math.max(rowBottom, ImGui.getItemRectMaxY());
            ImGui.setCursorScreenPos(rowX, rowBottom);
            ImGui.dummy(0, 0);
            var changed = new boolean[] {place[0], place[1], place[2], headingChanged};
            for (var i = 0; i < 4; i++) {
                if (changed[i]) {
                    values[i] = i == 3 ? Math.floorMod(fields[i].get(), 65536) : fields[i].get();
                    var position = new Fixed(values[0], values[1], values[2], values[3]);
                    edit(
                            document,
                            key + "/pos",
                            "Move NPC",
                            state -> SpawnEdits.replaceNpc(
                                    state, makerIndex, slot, current -> current.withPosition(position)));
                }
            }
            if (onGround) {
                var groundZ = ground.getGroundAt(fixed.x(), fixed.y(), fixed.z() + 64);
                if (!Double.isNaN(groundZ)) {
                    var position = new Fixed(fixed.x(), fixed.y(), (int) Math.round(groundZ), fixed.heading());
                    document.apply(
                            "Put NPC on the ground",
                            state -> SpawnEdits.replaceNpc(
                                    state, makerIndex, slot, current -> current.withPosition(position)));
                    merge.reset();
                }
            }
        }
        space(ROW);
        if (GeodataWidgets.actionButton("spawn-remove", Glyph.CLOSE, "Remove this entry", -1, false)) {
            document.apply("Remove NPC entry", state -> state.withNpc(makerIndex, slot, null));
            selectedSlot = -1;
            merge.reset();
        }
        ImGui.unindent(10);
        space(BLOCK);
    }

    private void drawRespawnPresets(NpcPosDocument document, int makerIndex, int slot, SpawnNpc npc) {
        var state = document.getState();
        if (state != presetsState) {
            presets = SpawnSummary.respawnPresets(state, 5);
            presetsState = state;
        }
        if (presets.isEmpty()) {
            return;
        }
        space(ROW);
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var right = x + ImGui.getContentRegionAvailX();
        var left = x;
        var draw = ImGui.getWindowDrawList();
        var height = 22f;
        for (var preset : presets) {
            var text = SpawnPanelWidgets.formatRespawnTime(preset);
            var width = ImGui.calcTextSizeX(text) + 18;
            if (left > x && left + width > right) {
                left = x;
                y += height + 4;
            }
            ImGui.setCursorScreenPos(left, y);
            var current = preset.equals(npc.respawn());
            if (ImGui.invisibleButton("preset-" + preset, width, height) && !current) {
                document.apply(
                        "Change respawn",
                        next -> SpawnEdits.replaceNpc(
                                next, makerIndex, slot, entry -> entry.withRespawn(preset, entry.respawnRandom())));
                merge.reset();
            }
            var hovered = ImGui.isItemHovered();
            draw.addRectFilled(
                    left,
                    y,
                    left + width,
                    y + height,
                    current ? ImColor.rgb(30, 58, 70) : hovered ? GeodataWidgets.HOVER : GeodataWidgets.CARD,
                    11f);
            if (current) {
                draw.addRect(left, y, left + width, y + height, GeodataWidgets.ACCENT, 11, 0, 1);
            }
            draw.addText(
                    left + 9,
                    y + (height - ImGui.getFontSize()) * .5f,
                    current ? HudWidgets.TEXT : GeodataWidgets.MUTED,
                    text);
            if (hovered) {
                HudWidgets.showTooltip("Respawn " + preset + ": one of the times this file uses most.");
            }
            left += width + 4;
        }
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(0, 0);
    }

    private Fixed defaultFixed(State state, SpawnMaker maker) {
        var currentIndex = index;
        if (currentIndex != null) {
            for (var name : maker.territoryNames()) {
                var territory = currentIndex.getTerritory(state, name);
                if (territory != null) {
                    var x = (int) Math.round(territory.centerX());
                    var y = (int) Math.round(territory.centerY());
                    var z = ground.getGroundAt(x, y, territory.top());
                    return new Fixed(x, y, Double.isNaN(z) ? territory.bottom() : (int) Math.round(z), 0);
                }
            }
        }
        var eye = camera == null ? null : camera.getPosition();
        return eye == null
                ? new Fixed(0, 0, 0, 0)
                : new Fixed(Math.round(eye.getX()), Math.round(eye.getY()), Math.round(eye.getZ()), 0);
    }

    private void addNpc(NpcPosDocument document, SpawnMaker maker) {
        var name = maker.npcs().isEmpty()
                ? source.getNpcs().getNames().isEmpty()
                        ? "gremlin"
                        : source.getNpcs().getNames().getFirst()
                : maker.npcs().getLast().npcName();
        var position =
                maker.territoryNames().isEmpty() ? defaultFixed(document.getState(), maker) : SpawnPosition.ANYWHERE;
        var npc = SpawnEdits.newNpc(maker.index(), name, position, 1, SpawnEdits.DEFAULT_RESPAWN, "");
        var makerIndex = maker.index();
        var slot = maker.npcs().size();
        document.apply("Add NPC entry", state -> SpawnEdits.addNpc(state, makerIndex, npc));
        selectedSlot = slot;
        merge.reset();
    }

    private List<Issue> findIssues(NpcPosDocument document) {
        if (document.getState() != checkedState || document.getSavedState() != checkedSaved) {
            checkedIssues = SpawnChecks.findIntroduced(document.getSavedState(), document.getState(), source.getNpcs());
            checkedState = document.getState();
            checkedSaved = document.getSavedState();
        }
        return checkedIssues;
    }

    private void openReview(NpcPosDocument document) {
        if (!document.isModified() || source.isSaving()) {
            return;
        }
        var found =
                SpawnReview.of(Input.of(document, source.getNpcs()), ground.isAvailable() ? ground::readArea : null);
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

    private void drawReview(@Nullable ImFont mono) {
        var answer = reviewDialog.draw(review, mono, clock);
        if (answer == Answer.SAVE) {
            source.save(reviewDialog.isBackup());
        }
        if (answer != Answer.NONE) {
            review = null;
        }
    }

    private static int outlineStep(double range) {
        return Math.clamp(Integer.highestOneBit(Math.max(1, (int) (range / 2500))), 1, 32);
    }

    private static List<SpawnProblem> toProblems(List<Finding> findings) {
        var result = new ArrayList<SpawnProblem>();
        for (var finding : findings) {
            result.add(new SpawnProblem(finding.where(), finding.detail(), finding.x(), finding.y(), finding.z()));
        }
        return result;
    }

    private record Inspected(int makerIndex, int slot, String npcName, double x, double y, double z) {}

    private static String formatUnits(double value) {
        return String.format(Locale.ROOT, value == Math.rint(value) ? "%,.0f units" : "%,.1f units", value);
    }

    private static String eventKey(boolean spawnTime, String name) {
        return SpawnFilter.eventKey(spawnTime, name);
    }

    private static boolean contains(int[] values, int value) {
        for (var candidate : values) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }

    private static void drawShadow(SpawnOverlayState overlay, double x, double y, double z, double radius, int alpha) {
        var segments = 16;
        var size = (float) Math.clamp(radius * 1.4, 10, 220);
        var center = ImColor.rgba(0, 0, 0, alpha);
        var rim = ImColor.rgba(0, 0, 0, 0);
        var lift = (float) z + 1.5f;
        for (var i = 0; i < segments; i++) {
            var a = i * Math.PI * 2 / segments;
            var b = (i + 1) * Math.PI * 2 / segments;
            overlay.fills.addTriangle(
                    (float) x,
                    (float) y,
                    lift,
                    center,
                    (float) (x + Math.cos(a) * size),
                    (float) (y + Math.sin(a) * size),
                    lift,
                    rim,
                    (float) (x + Math.cos(b) * size),
                    (float) (y + Math.sin(b) * size),
                    lift,
                    rim);
        }
    }

    private static BodyCandidate fadeNearModels(BodyCandidate candidate, List<double[]> modeled) {
        var body = candidate.body();
        if (body.alpha() <= SHARED_CAPSULE_ALPHA) {
            return candidate;
        }
        for (var spot : modeled) {
            if (Math.hypot(spot[0] - body.x(), spot[1] - body.y()) < body.radius() + 30
                    && Math.abs(spot[2] - body.z()) < 80) {
                return new BodyCandidate(
                        new Body(
                                body.x(),
                                body.y(),
                                body.z(),
                                body.radius(),
                                body.height(),
                                body.argb(),
                                SHARED_CAPSULE_ALPHA),
                        candidate.distance());
            }
        }
        return candidate;
    }

    private static void drawRing(
            SpawnOverlayState overlay, double x, double y, double z, double radius, int color, float width) {
        var segments = 28;
        GeoPoint previous = null;
        for (var i = 0; i <= segments; i++) {
            var angle = i * Math.PI * 2 / segments;
            var point = new GeoPoint(x + Math.cos(angle) * radius, y + Math.sin(angle) * radius, z);
            if (previous != null) {
                overlay.lines.addLine(previous, point, 3, color, width);
            }
            previous = point;
        }
    }

    private static String npcCount(int count) {
        return count == 1 ? "1 NPC" : count + " NPCs";
    }

    private static GeoPoint pointAhead(GeoPoint from, int heading, double length) {
        var angle = heading * Math.PI * 2 / 65536.0;
        return new GeoPoint(from.x() + Math.cos(angle) * length, from.y() + Math.sin(angle) * length, from.z());
    }

    private static Candidates shifted(Candidates candidates, int dx, int dy) {
        if (dx == 0 && dy == 0) {
            return candidates;
        }
        var moved = new ArrayList<GeoPoint>(candidates.points().size());
        for (var point : candidates.points()) {
            moved.add(new GeoPoint(point.x() + dx, point.y() + dy, point.z()));
        }
        return new Candidates(moved, candidates.usedFallback(), candidates.geodataMissing(), candidates.clientFloors());
    }

    private static void tag(ImDrawList draw, float x, float y, String text, int color, float alpha) {
        var width = ImGui.calcTextSizeX(text);
        var height = ImGui.getFontSize();
        var left = x - width * .5f - 6;
        var top = y - height - 6;
        draw.addRectFilled(
                left, top, left + width + 12, top + height + 6, ImColor.rgba(14, 20, 27, Math.round(190 * alpha)), 5f);
        draw.addText(left + 6, top + 3, color & 0x00FFFFFF | Math.round(255 * alpha) << 24, text);
    }

    private static void caption(String text) {
        ImGui.textColored(GeodataWidgets.FAINT, text);
    }

    private static void addSpot(
            List<SpawnSpot> spots,
            String name,
            String what,
            double x,
            double y,
            double z,
            double minX,
            double minY,
            double maxX,
            double maxY) {
        if (x >= minX && x <= maxX && y >= minY && y <= maxY) {
            spots.add(new SpawnSpot(name, what, x, y, z));
        }
    }

    private static String describe(SpawnNpc npc, Npc info) {
        var parts = new ArrayList<String>();
        parts.add(info == null ? "not in npcdata" : "Lv " + info.level());
        parts.add(
                switch (npc.position()) {
                    case Anywhere _ -> "random";
                    case Fixed _ -> "fixed";
                    case Weighted weighted -> weighted.points().size() + " points";
                });
        if (!npc.respawn().isEmpty()) {
            parts.add(SpawnPanelWidgets.formatRespawnTime(npc.respawn())
                    + (npc.respawnRandom().isEmpty()
                            ? ""
                            : " \u00b1" + SpawnPanelWidgets.formatRespawnTime(npc.respawnRandom())));
        }
        return String.join(" · ", parts);
    }

    private static void dotText(int color, String text) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        ImGui.getWindowDrawList().addCircleFilled(x + 4, HudWidgets.textMiddle(y), 4, color, 12);
        ImGui.setCursorScreenPos(x + 13, y);
        ImGui.textUnformatted(text);
    }

    private static String compact(long value) {
        if (value < 10_000) {
            return String.format(Locale.ROOT, "%,d", value);
        }
        return value < 1_000_000 ? Math.round(value / 1000.0) + "k" : String.format(Locale.ROOT, "%.1fM", value / 1e6);
    }

    private static void centeredText(String text) {
        var width = ImGui.getContentRegionAvailX();
        ImGui.setCursorPosX(ImGui.getCursorPosX() + Math.max(0, (width - ImGui.calcTextSizeX(text)) * .5f));
        ImGui.textUnformatted(text);
    }

    private static String makerCount(int makers) {
        return String.format(Locale.ROOT, "%,d %s", makers, makers == 1 ? "maker" : "makers");
    }

    private static void drawTimeline(List<Event> list) {
        var times = list.stream().filter(Event::spawnTime).toList();
        if (times.size() < 2) {
            return;
        }
        ImGui.textColored(GeodataWidgets.MUTED, "Spawn times");
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var total = times.stream().mapToInt(event -> event.makers().size()).sum();
        var left = x;
        var colors = new int[] {0xFFC88CE6, 0xFF9C7CE6, 0xFFE68CC8, 0xFF8CA0E6, 0xFFE6A08C, 0xFFB4BCC8};
        for (var i = 0; i < times.size(); i++) {
            var right = i == times.size() - 1
                    ? x + width
                    : left + width * times.get(i).makers().size() / (float) total;
            draw.addRectFilled(
                    left,
                    y,
                    Math.max(left + 2, right - 1),
                    y + 8,
                    SpawnStyle.getImguiColor(colors[i % colors.length], 1f, 1f),
                    3f);
            left = right;
        }
        var lineY = y + 14;
        var lineX = x;
        for (var i = 0; i < times.size(); i++) {
            var text = times.get(i).name() + " " + times.get(i).makers().size();
            var chipWidth = ImGui.calcTextSizeX(text) + 16;
            if (lineX > x && lineX + chipWidth > x + width) {
                lineX = x;
                lineY += ImGui.getFontSize() + 4;
            }
            draw.addCircleFilled(
                    lineX + 4,
                    HudWidgets.textMiddle(lineY),
                    3.5f,
                    SpawnStyle.getImguiColor(colors[i % colors.length], 1f, 1f),
                    10);
            draw.addText(lineX + 11, lineY, GeodataWidgets.MUTED, text);
            lineX += chipWidth + 6;
        }
        ImGui.dummy(width, lineY + ImGui.getFontSize() - y);
        space(ROW);
    }

    private static Severity severity(Kind kind) {
        return kind == Kind.UNKNOWN_NPC ? Severity.ERROR : Severity.WARNING;
    }

    private static String helpText(Kind kind) {
        return switch (kind) {
            case FEET_BLOCKED ->
                "A fixed NPC stands on a blocked geodata cell, or no floor lies near its height. Drop to ground sets "
                        + "its height to the floor under it.";
            case UNDER_FLOOR ->
                "A fixed NPC stands far under the geodata floor of its cell. Often a room the geodata does not have,"
                        + " such as a shop whose cells only know the roof: the NPC stands there in the game, so check"
                        + " before moving it.";
            case UNREACHABLE ->
                "No walkable cell within " + SpawnHealth.REACH + " units leads to the NPC, so players cannot walk "
                        + "up to it. Fine for a merchant behind a counter.";
            case FEW_POINTS ->
                "The server spawns random NPCs only on the points of its grid that are ground inside the zone, in its "
                        + "height range, with room to move; this zone has fewer than " + SpawnHealth.MIN_POINTS + ".";
            case FLOATING_ZONE ->
                "The zone's bottom lies above the ground everywhere under it, so the server finds no floor in its "
                        + "height range. Fit height range sets it from the ground.";
            case NO_GEODATA ->
                "The geodata folder has no file for the region the zone lies in. The server spawns there without "
                        + "geodata, as the file says.";
            case UNKNOWN_NPC -> "The entry names an NPC that npcdata.txt does not have. The server cannot spawn it.";
        };
    }

    private static List<String> fixLabels(Kind kind) {
        return switch (kind) {
            case FEET_BLOCKED -> List.of("Drop to ground");
            case FEW_POINTS, FLOATING_ZONE -> List.of("Fit height range");
            case UNKNOWN_NPC -> List.of("Remove entry");
            default -> List.of();
        };
    }

    private static float spacing() {
        return ImGui.getStyle().getItemSpacingY();
    }

    private static void space(float pixels) {
        GeodataWidgets.space(pixels);
    }

    private static void tightenSpacing() {
        ImGui.setCursorPosY(ImGui.getCursorPosY() - spacing() + 2);
    }

    private static void title(String label, boolean spaced) {
        if (spaced) {
            GeodataWidgets.section(label);
        } else {
            ImGui.textColored(GeodataWidgets.MUTED, label);
        }
    }
}
