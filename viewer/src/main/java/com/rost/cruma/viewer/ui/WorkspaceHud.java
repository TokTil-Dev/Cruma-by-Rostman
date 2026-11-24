package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.HudWidgets.Glyph.BOX;
import static com.rost.cruma.viewer.ui.HudWidgets.Glyph.CARD;
import static com.rost.cruma.viewer.ui.HudWidgets.Glyph.CHEVRON;
import static com.rost.cruma.viewer.ui.HudWidgets.Glyph.CLOSE;
import static com.rost.cruma.viewer.ui.HudWidgets.Glyph.COPY;
import static com.rost.cruma.viewer.ui.HudWidgets.Glyph.EYE;
import static com.rost.cruma.viewer.ui.HudWidgets.Glyph.FILE;
import static com.rost.cruma.viewer.ui.HudWidgets.Glyph.FOCUS;
import static com.rost.cruma.viewer.ui.HudWidgets.Glyph.GEODATA;
import static com.rost.cruma.viewer.ui.HudWidgets.Glyph.GLOBE;
import static com.rost.cruma.viewer.ui.HudWidgets.Glyph.HELP;
import static com.rost.cruma.viewer.ui.HudWidgets.Glyph.LOGO;
import static com.rost.cruma.viewer.ui.HudWidgets.Glyph.MENU;
import static com.rost.cruma.viewer.ui.HudWidgets.Glyph.PAUSE;
import static com.rost.cruma.viewer.ui.HudWidgets.Glyph.PENCIL;
import static com.rost.cruma.viewer.ui.HudWidgets.Glyph.PLAY;
import static com.rost.cruma.viewer.ui.HudWidgets.Glyph.PROFILE;
import static com.rost.cruma.viewer.ui.HudWidgets.Glyph.SEARCH;
import static com.rost.cruma.viewer.ui.HudWidgets.Glyph.SLIDERS;
import static com.rost.cruma.viewer.ui.HudWidgets.Glyph.STEP;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.viewer.agent.AgentFindings.Item;
import com.rost.cruma.viewer.agent.AgentRuntime;
import com.rost.cruma.viewer.application.UiContext;
import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.config.ConfigLoader.Options;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.editing.EditingCheck.GeodataSource;
import com.rost.cruma.viewer.editing.EditingCheck.SpawnSource;
import com.rost.cruma.viewer.editing.EditingMapLayer;
import com.rost.cruma.viewer.editing.EditingPanel;
import com.rost.cruma.viewer.editing.EditingRecoveryPanel;
import com.rost.cruma.viewer.editing.EditorUi;
import com.rost.cruma.viewer.editing.EditorViewport;
import com.rost.cruma.viewer.editing.EditorViewport.Click;
import com.rost.cruma.viewer.editing.MeshLibrary;
import com.rost.cruma.viewer.guide.GuideRequests;
import com.rost.cruma.viewer.launch.FileManager;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.CameraView;
import com.rost.cruma.viewer.render.EditorEffectStage;
import com.rost.cruma.viewer.render.RenderingContext;
import com.rost.cruma.viewer.render.ViewMode;
import com.rost.cruma.viewer.render.api.DebugSceneObject;
import com.rost.cruma.viewer.render.api.DebugSceneObject.Category;
import com.rost.cruma.viewer.render.api.DebugSelection;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import com.rost.cruma.viewer.ui.EditLeave.Choice;
import com.rost.cruma.viewer.ui.EditLeave.Outcome;
import com.rost.cruma.viewer.ui.EditLeave.Reason;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.IsolateFraming.Move;
import com.rost.cruma.viewer.ui.LaunchOptions.Host;
import com.rost.cruma.viewer.ui.ObjectCatalog.Entry;
import com.rost.cruma.viewer.ui.ToolWindows.Tool;
import com.rost.cruma.viewer.ui.WorkspaceLayout.Mode;
import com.rost.cruma.viewer.ui.WorkspaceLayout.Tab;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiListClipper;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiPopupFlags;
import imgui.flag.ImGuiSelectableFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class WorkspaceHud {
    public static final float TOP = HudWidgets.BAR_HEIGHT + 24f;
    public static final float STATUS = HudWidgets.BAR_HEIGHT;
    private static final float STATUS_FOLD = 20f;

    public static final float STATUS_END = 12 + 8 + STATUS_FOLD + 6;

    private static final float ORTHO_GRID_MENU = 20f;
    public static final float ORTHO_TOOLS_WIDTH = 24 + 28 + GeodataWidgets.ROW + 28 + ORTHO_GRID_MENU;

    private static final float EFFECTS_TOOLBAR_WIDTH = 440f;
    private static final float MARGIN = 12f;
    private static final float MIN_CAMERA_SPEED = 100f;
    private static final float MAX_CAMERA_SPEED = 50000f;
    private static final float VIEW_BAR_PADDING = (HudWidgets.BAR_HEIGHT - HudWidgets.CONTROL_HEIGHT) * .5f;
    private static final float VIEW_SEGMENT_WIDTH = 48f;
    private static final float TREE_TAB = 40f;
    private static final float VIEW_SECOND_ROW = 26f + GeodataWidgets.ROW;
    private static final float VIEW_POPUP_PADDING = GeodataWidgets.ROW;
    private static final float VIEW_TOGGLE_WIDTH = 124f;
    private static final float MENU_END = 38;
    private static final float GUIDE_END = MENU_END + HudWidgets.CONTROL_HEIGHT + GeodataWidgets.ROW;
    private static final float RIGHT_END =
            60f + GeodataWidgets.ROW + VIEW_TOGGLE_WIDTH + GeodataWidgets.ROW + GUIDE_END;
    private static final float VIEWER_ONE_ROW = 148f + 66 * 4 + GeodataWidgets.ROW + RIGHT_END;
    private static final float VIEWER_BAR_WIDTH = VIEWER_ONE_ROW + 140 + GeodataWidgets.ROW;
    private static final float EDITING_ONE_ROW = 148f + RIGHT_END;
    private static final float EDITING_BAR_WIDTH = EDITING_ONE_ROW + 180;
    private static final String ORTHO_KEYS =
            "W, A, S and D pan. The wheel, E and Space zoom. Alt and the wheel move the slice.";
    private static final Tool[] VIEWING_TOOLS = {
        Tool.GEODATA, Tool.SPAWNS, Tool.AREAS, Tool.NPCS, Tool.EVENTS, Tool.EFFECTS
    };
    private static final float ORTHO_POPUP_WIDTH = 304f;
    private static final String GRID_TIP =
            "Grid · Lines and numbers in world units over this view, with the tile borders. Hidden with the HUD.";
    private static final int TEXT = HudWidgets.TEXT;
    private static final int MUTED = HudWidgets.MUTED;
    private static final int ACCENT = HudWidgets.ACCENT;
    private static final int PANEL = ImGuiWindowFlags.NoTitleBar
            | ImGuiWindowFlags.NoMove
            | ImGuiWindowFlags.NoResize
            | ImGuiWindowFlags.NoCollapse
            | ImGuiWindowFlags.NoSavedSettings
            | ImGuiWindowFlags.NoFocusOnAppearing
            | ImGuiWindowFlags.NoBringToFrontOnFocus;
    private static final int FLIGHT = (PANEL & ~ImGuiWindowFlags.NoBringToFrontOnFocus) | ImGuiWindowFlags.NoInputs;
    private static final double[] SPEEDS = {.1, .25, .5, 1, 1.5, 2, 4};
    private static final String[] SPEED_LABELS = {"0.1x", "0.25x", "0.5x", "1x", "1.5x", "2x", "4x"};

    private static final long NOTICE_NANOS = 4_000_000_000L;
    private static final float RELOAD_CARD = 40f;
    private static final int PANEL_OPEN = "workspace/panel/open".hashCode();
    private static final int PANEL_SWAP = "workspace/panel/swap".hashCode();
    private static final int PANEL_CLOSE = "workspace/panel/close".hashCode();
    private static final int NOTICE_SHOWN = "workspace/notice".hashCode();
    private static final int SHUTTER = "workspace/shutter".hashCode();
    private static final int CARD_SHOWN = "workspace/card".hashCode();
    private static final int CARD_SWAP = "workspace/card/swap".hashCode();
    private static final int TREE_SHOWN = "workspace/tree".hashCode();
    private static final int TRAY_SHOWN = "workspace/tray".hashCode();
    private static final int DIRECTOR_SHOWN = "workspace/director".hashCode();
    private static final float DIRECTOR_PADDING = 12;
    private static final float DIRECTOR_SHARE = .4f;
    private static final float DIRECTOR_LOWEST = 108;
    private static final float DIRECTOR_WITH_ITEMS = 248;
    private static final int PLAYBACK_SHOWN = "workspace/playback".hashCode();
    private static final int STATUS_RIGHT = "workspace/status/right".hashCode();
    private static final int STATUS_WIDTH = "workspace/status/width".hashCode();
    private static final int STATUS_TURN = "workspace/status/turn".hashCode();
    private static final int STATUS_LANDED = "workspace/status/landed".hashCode();
    private static final int STATUS_SQUASH = "workspace/status/squash".hashCode();
    private static final int ACCENT_FILL = ImColor.rgb(30, 51, 61);
    private static final String GROUP_MENU = "##groupRowMenu";
    private static final String COMMAND_KEY =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac") ? "Cmd" : "Ctrl";
    private final UiContext ui;
    private final RenderingContext scene;
    private final ViewerRenderBackend backend;
    private final FontManager fonts;
    private final CrumaBranding branding;
    private final String client;
    private final ObjectCatalog catalog = new ObjectCatalog();
    private final ViewComparison comparison = new ViewComparison();
    private final ImString query = new ImString(256);
    private final ImString commandQuery = new ImString(256);
    private final LogSliderField cameraSpeed = new LogSliderField(
            "cameraSpeed",
            "Speed",
            MIN_CAMERA_SPEED,
            MAX_CAMERA_SPEED,
            "",
            "Camera speed in world units per second. Keys 1–5 choose presets. + / - adjust it.",
            "Camera speed · 100–50,000 world units per second. Drag to adjust. Type an exact value on the right.",
            "Type a speed and press Enter or click elsewhere. Range: 100–50,000.");
    private final LogSliderField zoom = new LogSliderField(
            "orthoZoom",
            "Zoom",
            Camera.MIN_ZOOM_PERCENT,
            Camera.MAX_ZOOM_PERCENT,
            "%",
            "Zoom of the Top, Front and Side views. 100% shows one tile, 32,768 units, across the view.",
            "Zoom · 10%–1,000%. Drag to adjust, scroll the wheel over the view, or hold E and Space. 100% shows one"
                    + " tile across the view.",
            "Type a zoom in percent and press Enter or click elsewhere. Range: 10%–1,000%.");
    private final float[] zoomPercent = {100};
    private final Set<String> expanded = new HashSet<>();
    private final ImGuiListClipper clipper = new ImGuiListClipper();
    private final WorkspaceLayout layout;
    private List<TreeRow> rows = List.of();
    private long rowRevision = -1;
    private String rowQuery = "";
    private String revealedKey = "";
    private String feedback = "";
    private long feedbackUntil;
    private float editingMessage;
    private long shownScreenshot;
    private long screenshotUntil;
    private boolean rowsDirty = true;
    private boolean compareTab;
    private TreeFilter treeFilter = TreeFilter.ALL;
    private boolean treeMarkHovered;
    private long treeFilterRevision = Long.MIN_VALUE;
    private int changedCount;
    private long changedRevision = Long.MIN_VALUE;
    private long changedCatalog = Long.MIN_VALUE;
    private long groupRowsRevision = Long.MIN_VALUE;
    private @Nullable String groupMenu;
    private float groupMenuRight;
    private float groupMenuBottom;
    private boolean animationDetails;
    private final EnumMap<Tab, Boolean> propertyOverflow = new EnumMap<>(Tab.class);
    private float topHeight = TOP;
    public final ToolWindows tools = new ToolWindows();
    public boolean helpOpen;
    private Supplier<@Nullable String> guideTopic = () -> null;
    private @Nullable GuideWindow guide;
    private final float[] guideButton = new float[4];

    private @Nullable InspectedView inspectedView;

    private @Nullable String namedMap;
    private boolean effectsOnly;
    private @Nullable Mode savedMode;

    private float rightWidth;
    private float leftWidth;
    private @Nullable Set<String> isolatedActors;
    private final IsolateFraming isolateFraming = new IsolateFraming();
    private Runnable underView = () -> {};
    private final float[] freePlace = new float[3];
    private float zoomBeforeFraming = 1;
    private boolean editingTools;
    private float commandBarRight;
    private float viewToggleX;
    private float viewToggleY;
    private float viewToggleWidth;
    private float trayHeight;
    private float width;
    private float height;
    private @Nullable AgentRuntime agent;
    private @Nullable AgentPanel agentPanel;
    private BooleanSupplier directorOpen = () -> false;
    private boolean statusShown = true;
    private @Nullable Boolean statusWasDocked;
    private float statusSince = 1;
    private float statusRight = Float.NaN;
    private float statusStep;
    private boolean statusLanded = true;
    private boolean statusSettled = true;
    private DirectorTray directorTray = (_, _) -> {};
    private EditingPanel editingPanel;
    private boolean libraryTab;
    private final ImString librarySearch = new ImString(256);
    private @Nullable Path configDirectory;
    private @Nullable ExportSettings exportSettings;
    private SpawnSource editingSpawns;
    private GeodataSource editingGeodata;
    private final EditingMapLayer editingMapLayer = new EditingMapLayer(this::openEditingPanel);
    private EditingRecoveryPanel recoveryPanel;
    private EditorViewport editingViewport;
    private @Nullable DebugSelection pointed;
    private boolean confirmEditingExit;
    private Reason leaveReason = Reason.VIEWER;
    private boolean approvedWindowClose;
    private boolean settingsWanted;
    private @Nullable LaunchOptions settings;
    private int settingsReloadQuiet;
    private Runnable settingsSaved = () -> {};
    private boolean windowCloseAfterSettings;
    private boolean windowCloseAgain;
    private String panelShown = "";
    private int panelFrame = Integer.MIN_VALUE;
    private final float[] panelRect = new float[4];
    private boolean panelClosing;
    private String noticeShown = "";
    private boolean shutterHeld;
    private String cardShown = "";
    private final FrameLimitPopup frameLimit;

    public WorkspaceHud(
            UiContext ui,
            RenderingContext scene,
            ViewerRenderBackend backend,
            FontManager fonts,
            CrumaBranding branding,
            String client) {
        this.ui = ui;
        this.scene = scene;
        this.backend = backend;
        this.fonts = fonts;
        this.client = client;
        this.branding = branding;
        frameLimit = new FrameLimitPopup(fonts);
        WorkspaceLayout loaded;
        try {
            loaded = WorkspaceLayout.load(WorkspaceLayout.defaultPath());
        } catch (IOException invalid) {
            loaded = new WorkspaceLayout();
            notify("Could not read saved layout. Using defaults.");
        }
        layout = loaded;
        var override = System.getProperty("cruma.viewer.hud.mode");
        layout.mode = ui.recording != null
                ? Mode.EXPLORE
                : WorkspaceLayout.initialMode(ui.editingPrepared, ui.workspaceMode, override);
        ui.workspaceMode = layout.mode;
        if (!ui.editingPrepared && layout.mode == Mode.EDITING) {
            ui.editingReloadRequested = true;
        }
        ui.rendering.objectPicker = layout.mode != Mode.EXPLORE;
        if (layout.mode != Mode.EXPLORE) {
            tools.open(Tool.OBJECT);
        }
    }

    private record TreeRow(
            String id,
            String label,
            String detail,
            int depth,
            DebugSelection selection,
            @Nullable DebugSelection pick,
            RowKind kind,
            @Nullable String group) {
        TreeRow(
                String id,
                String label,
                String detail,
                int depth,
                DebugSelection selection,
                @Nullable DebugSelection pick) {
            this(id, label, detail, depth, selection, pick, RowKind.OBJECT, null);
        }
    }

    private enum RowKind {
        OBJECT,
        GROUPS,
        GROUP,
        MEMBER
    }

    private enum TreeFilter {
        ALL,
        SELECTED,
        CHANGED
    }

    public Mode getMode() {
        return layout.mode;
    }

    public void showNamedMap(String name) {
        namedMap = name;
        tools.remove(Tool.GEODATA, Tool.SPAWNS, Tool.AREAS, Tool.NPCS, Tool.EFFECTS, Tool.SCENES);
        ui.worldMapOpen = false;
    }

    public void showScenes(int count) {
        if (count > 0) {
            tools.add(Tool.SCENES);
        }
    }

    public void showDirector(BooleanSupplier open, DirectorTray body) {
        tools.add(Tool.SCENES);
        directorOpen = open;
        directorTray = body;
    }

    public void showAgent(AgentRuntime runtime, Consumer<Item> fly) {
        agent = runtime;
        agentPanel = new AgentPanel(runtime, fly, fonts);
        tools.add(Tool.AGENT);
    }

    public void drawAgentPanel() {
        if (agentPanel != null && tools.isOpen(Tool.AGENT)) {
            beginToolWindow("##agentTools", "AI agent");
            agentPanel.draw();
            ImGui.end();
        }
    }

    @FunctionalInterface
    public interface DirectorTray {
        void draw(float width, float height);
    }

    public void growDirector() {
        layout.directorHeight = Math.max(layout.directorHeight, DIRECTOR_WITH_ITEMS);
    }

    public boolean isDirectorShown() {
        return directorOpen.getAsBoolean()
                && tools.isOpen(Tool.SCENES)
                && layout.mode != Mode.ANALYZE
                && layout.mode != Mode.EDITING;
    }

    public void showEffectsOnly() {
        effectsOnly = true;
        savedMode = layout.mode;
        layout.mode = Mode.EXPLORE;
        ui.editingReloadRequested = false;
        ui.rendering.objectPicker = false;
        tools.hide(Tool.OBJECT, Tool.GEODATA, Tool.SPAWNS, Tool.AREAS, Tool.NPCS, Tool.EVENTS, Tool.SCENES);
        tools.open(Tool.EFFECTS);
        ui.worldMapOpen = false;
    }

    public boolean isEffectsOnly() {
        return effectsOnly;
    }

    public void setInspectedView(@Nullable InspectedView view) {
        inspectedView = view;
    }

    public boolean isRecoveryPending() {
        return scene.getEditingRecovery() != null && scene.getEditingRecovery().getAvailable() != null;
    }

    public void discardEditing() throws IOException {
        scene.discardEditingRecovery();
        ui.workspaceMode = Mode.EXPLORE;
        ui.editingReloadRequested = true;
        applyMode(Mode.EXPLORE);
    }

    public void setMode(Mode mode) {
        if (effectsOnly) {
            return;
        }
        ui.workspaceMode = mode;
        if (layout.mode == Mode.EDITING && mode != Mode.EDITING && scene.doesEditingNeedSave()) {
            leaveReason = Reason.VIEWER;
            confirmEditingExit = true;
            return;
        }
        if ((mode == Mode.EDITING) != ui.editingPrepared) {
            ui.editingReloadRequested = true;
        }
        applyMode(mode);
    }

    public void cycleViewerMode() {
        var next = switch (layout.mode) {
            case EXPLORE -> Mode.INSPECT;
            case INSPECT -> Mode.ANALYZE;
            case ANALYZE -> Mode.EXPLORE;
            case EDITING -> null;
        };
        if (next != null) {
            setMode(next);
        }
    }

    public WorkspaceLayout getLayout() {
        return layout;
    }

    public ViewComparison getComparison() {
        return comparison;
    }

    public float getViewportTop() {
        return topHeight;
    }

    public float getCommandBarRight() {
        return commandBarRight;
    }

    public float getViewportLeft() {
        if (leftWidth > 0) {
            return leftWidth + MARGIN * 2;
        }
        return isTreeFolded() ? TREE_TAB + MARGIN * 2 : 0;
    }

    public float getViewportRight() {
        return (rightWidth > 0 ? getToolPanelX() : getToolRailX()) - MARGIN;
    }

    public float getViewportBottom() {
        return statusY() - MARGIN;
    }

    public float getTopOverlayBottom() {
        return layout.mode == Mode.EDITING && editingPanel != null
                ? Math.max(getViewportTop(), editingPanel.getHintsBottom())
                : getViewportTop();
    }

    public float getLeftOverlayBottom() {
        return isObjectCardShown() ? compactPanelY(Math.min(304, width - 24), 108) - MARGIN : getViewportBottom();
    }

    public float getBottomOverlayLeft() {
        return isObjectCardShown() ? MARGIN + Math.min(304, width - 24) + MARGIN : getViewportLeft();
    }

    public float getToolPanelX() {
        return getToolRailX() - rightWidth;
    }

    public float getToolPanelTop() {
        return MARGIN;
    }

    public float getToolPanelWidth() {
        return rightWidth;
    }

    public float getToolPanelHeight() {
        var bottom = height - MARGIN;
        if (rightWidth > 0 && !isStatusDocked() && getToolPanelX() < (width + statusBarWidth()) * .5f) {
            bottom = Math.min(bottom, statusY() - MARGIN);
        }
        return Math.max(1, bottom - MARGIN);
    }

    public float getToolRailX() {
        return width - MARGIN - ToolWindows.RAIL_WIDTH;
    }

    public boolean isStatusDocked() {
        return layout.statusDocked && !comparison.isActive();
    }

    public boolean isStatusInPlace() {
        return !isStatusDocked() && statusSettled;
    }

    public float getStatusDockY() {
        var line = statusHomeY() + (STATUS - ToolWindows.BUTTON_HEIGHT) * .5f;
        var underTools = MARGIN + ToolWindows.RAIL_PADDING + tools.getShown().size() * ToolWindows.BUTTON_STEP;
        return Math.max(line, underTools);
    }

    public float[] getStatusFoldButton() {
        var bar = statusBarWidth();
        var left = Math.round((width - bar) * .5f) + bar - 6 - STATUS_FOLD;
        var top = statusHomeY() + (STATUS - HudWidgets.CONTROL_HEIGHT) * .5f;
        return new float[] {left, top, left + STATUS_FOLD, top + HudWidgets.CONTROL_HEIGHT};
    }

    public float getToolButtonY(Tool tool) {
        return MARGIN
                + ToolWindows.RAIL_PADDING
                + Math.max(0, tools.getShown().indexOf(tool)) * ToolWindows.BUTTON_STEP;
    }

    public void toggleTool(Tool tool) {
        tools.toggle(tool);
        helpOpen = false;
        ui.worldMapOpen = false;
    }

    public void close() {
        comparison.finish(scene.playback);
        if (savedMode != null) {
            layout.mode = savedMode;
        }
        try {
            if (ui.recording == null && !ui.agentViewer) {
                layout.save(WorkspaceLayout.defaultPath());
            }
        } catch (IOException failure) {
            LogManager.getLogger(WorkspaceHud.class)
                    .warn("Could not save viewer layout at {}: {}", WorkspaceLayout.defaultPath(), failure.toString());
        }
        clipper.destroy();
        if (agentPanel != null) {
            agentPanel.close();
        }
        if (editingPanel != null) {
            editingPanel.close();
        }
    }

    public void setConfigDirectory(@Nullable Path directory) {
        configDirectory = directory;
    }

    public void setUnderView(Runnable draw) {
        underView = draw;
    }

    public void setGuideTopic(Supplier<@Nullable String> topic) {
        guideTopic = topic;
    }

    public void setGuide(GuideWindow guide) {
        this.guide = guide;
    }

    public boolean isGuideOpen() {
        return guide != null && guide.isOpen();
    }

    public boolean isSettingsOpen() {
        return settings != null || settingsWanted;
    }

    public void setSettingsSaved(Runnable action) {
        settingsSaved = action;
    }

    public boolean holdWindowClose() {
        settingsWanted = false;
        var open = settings;
        if (open == null) {
            return false;
        }
        windowCloseAfterSettings = !open.hasUnsavedChanges();
        open.requestClose();
        return true;
    }

    public boolean takeWindowCloseRequest() {
        var again = windowCloseAgain;
        windowCloseAgain = false;
        return again;
    }

    public void closeGuide() {
        if (guide != null) {
            guide.close();
        }
    }

    public float getGuideLeft() {
        return Math.max(16f, getViewportLeft() + MARGIN);
    }

    public float getGuideWidth() {
        return GuideWindow.width(getViewportRight() - getGuideLeft());
    }

    public float getOverlayLeft() {
        return isGuideOpen() && !ui.worldMapOpen ? getGuideLeft() + getGuideWidth() : getViewportLeft();
    }

    public float[] getGuideButton() {
        return guideButton.clone();
    }

    public void dropFiles(List<Path> files) {
        if (layout.mode != Mode.EDITING || ui.worldMapOpen || isRecoveryPending() || isSettingsOpen()) {
            return;
        }
        var x = ImGui.getMousePosX();
        var y = ImGui.getMousePosY();
        var overViewport =
                x >= getViewportLeft() && x <= getViewportRight() && y >= getViewportTop() && y <= getViewportBottom();
        editor().acceptDroppedFiles(files, x, y, overViewport);
    }

    public void draw() {
        width = ImGui.getIO().getDisplaySizeX();
        height = ImGui.getIO().getDisplaySizeY();
        refreshSelection();
        syncEditingTools();
        if (agent != null && agent.takePanelWanted()) {
            tools.open(Tool.AGENT);
            helpOpen = false;
        }
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 8, 4);
        topHeight = TOP;
        leftWidth = isTreeShown() ? Math.min(layout.treeWidth, width * .25f) : 0;
        trayHeight = trayHeight();
        toolRail();
        updatePanelWidth();
        topBar();
        leftWidth = isTreeShown() ? Math.min(layout.treeWidth, width * .25f) : 0;
        trayHeight = trayHeight();
        updatePanelWidth();
        syncEditingIsolate();
        frameIsolated();
        underView.run();
        scene.spawns.editorLines.begin();
        scene.spawns.editorEdges.begin();
        scene.spawns.editorLight.begin();
        ui.rendering.objectPicker = (layout.mode != Mode.EXPLORE || tools.isOpen(Tool.OBJECT))
                && scene.getViewMode().picksObjects();
        if (layout.mode == Mode.EDITING && tools.isOpen(Tool.OBJECT)) {
            beginToolWindow("##mapEditing", "Edit", false);
            var editing = editor();
            var recovery = scene.getEditingRecovery();
            editing.setRecoveryNote(
                    recovery == null ? "" : recovery.getStatus(), recovery == null ? "" : recovery.getError());
            ImGui.beginDisabled(isRecoveryPending());
            editing.draw();
            ImGui.endDisabled();
            ImGui.end();
        } else if (tools.isOpen(Tool.OBJECT)) {
            drawInspector();
        } else if (layout.mode == Mode.EXPLORE && !effectsOnly) {
            compactCard();
        }
        if (isDirectorShown()) {
            drawDirectorTray();
        }
        if (layout.mode == Mode.ANALYZE) {
            drawObjectTreeOrTab();
            drawAnalysisTray();
        } else if (layout.mode == Mode.EDITING) {
            drawObjectTreeOrTab();
            if (!ui.worldMapOpen && !isRecoveryPending()) {
                if (editingViewport == null) {
                    editingViewport = new EditorViewport(scene.getEditing(), backend, editor());
                    editingViewport.setWorldLines(scene.spawns.editorLines);
                    editingViewport.setWorldEdges(scene.spawns.editorEdges);
                    editingViewport.setLightGlow(scene.spawns.editorLight);
                }
                editingViewport.setResidentMaps(scene.getEditingResidentMaps());
                editor().handleKeys(!tools.isOpen() || tools.isOpen(Tool.OBJECT));
                scene.editorInputCaptured = editor().drawTerrainViewport(
                                scene.camera,
                                backend,
                                getViewportLeft(),
                                getViewportTop(),
                                getViewportRight(),
                                getViewportBottom());
                if (!scene.editorInputCaptured) {
                    scene.editorInputCaptured = editingViewport.draw(
                            scene.camera, getViewportLeft(), getViewportTop(), getViewportRight(), getViewportBottom());
                } else {
                    editingViewport.drawMarks(
                            scene.camera, getViewportLeft(), getViewportTop(), getViewportRight(), getViewportBottom());
                }
                if (!helpOpen) {
                    var hintsRight = tools.isOpen(Tool.OBJECT)
                            ? Math.min(getViewportRight(), getToolPanelX() - MARGIN)
                            : getViewportRight();
                    editor().drawHints(getViewportLeft(), getViewportTop(), hintsRight, getViewportBottom());
                    editor().drawWelcome(getOverlayLeft(), getViewportTop(), hintsRight, getViewportBottom());
                }
                if (editor().consumeHelpRequest()) {
                    helpOpen = true;
                    closeGuide();
                }
                if (editor().consumeFocusRequest()) {
                    focusEditingSelection();
                }
                var fly = editor().consumeFlyRequest();
                if (fly != null) {
                    focusEditingBox(fly);
                }
                if (editor().consumeReloadRequest()) {
                    reloadEditing();
                }
                if (editor().consumeLibraryRequest()) {
                    libraryTab = true;
                    layout.treeFolded = false;
                }
            } else {
                scene.editorInputCaptured = isRecoveryPending();
            }
        } else if (layout.mode == Mode.INSPECT) {
            beginPanel(
                    "##workspacePlayback",
                    MARGIN,
                    compactPanelY(208, STATUS),
                    208,
                    STATUS,
                    true,
                    HudWidgets.PANEL_RADIUS);
            Motion.open(Motion.appear(PLAYBACK_SHOWN, Motion.OPEN), 0, 16, 1);
            ImGui.setCursorPos(6, 6);
            compactPlayback();
            ImGui.end();
        }
        if (layout.mode != Mode.EDITING) {
            markSelection();
        }
        scene.spawns.editorLines.publish(width);
        scene.spawns.editorEdges.publish(width);
        scene.spawns.editorLight.publish();
        statusShown = !ui.sceneHoldsCamera;
        if (statusShown) {
            drawStatusBar();
        }
        drawTileReload();
        drawEditingMessage();
        drawNotice();
        resizeGrips();
        drawEditingExitConfirmation();
        drawSettings();
        if (layout.mode == Mode.EDITING && scene.getEditingRecovery() != null && !isSettingsOpen()) {
            if (recoveryPanel == null) {
                recoveryPanel = new EditingRecoveryPanel();
            }
            recoveryPanel.draw(scene.getEditingRecovery(), () -> setMode(Mode.EXPLORE));
        }
        drawClosingPanel();
        ImGui.popStyleVar();
    }

    public void holdShutter() {
        shutterHeld = true;
    }

    public @Nullable EditingPanel getEditingPanel() {
        return layout.mode == Mode.EDITING ? editingPanel : null;
    }

    public @Nullable Click consumeEditingClick() {
        return layout.mode == Mode.EDITING && editingViewport != null ? editingViewport.consumeClick() : null;
    }

    public @Nullable String drawEditingHover(ImDrawList draw, @Nullable DebugSelection hovered) {
        if (layout.mode != Mode.EDITING || editingViewport == null) {
            return null;
        }
        editingViewport.hover(hovered);
        if (hovered == null
                || hovered.sceneObject() == null
                || hovered.sceneObject().category() != Category.STATIC_MESH) {
            return null;
        }
        if (editor().isSelectTool() && !scene.getEditing().getSelection().contains(hovered.actor())) {
            editingViewport.drawHover(draw, scene.camera, hovered.actor());
        }
        var locked = scene.getEditing().getLockedActors().contains(hovered.actor());
        var behind = locked ? 0 : editingViewport.countObjectsBehind(scene.camera, hovered.actor());
        return DebugSelection.shortName(hovered.assetName())
                + " · "
                + DebugSelection.shortName(hovered.actor())
                + (locked ? " · locked" : "")
                + (behind > 0 ? " · " + behind + " more behind" : "");
    }

    public @Nullable DebugSceneObject hoverTarget(@Nullable DebugSelection hovered) {
        var object = hovered == null ? null : hovered.sceneObject();
        if (object == null) {
            return null;
        }
        if (layout.mode != Mode.EDITING) {
            return SelectionMark.tints(hovered, scene.inspection.selected) ? object : null;
        }
        var selectable = object.category() == Category.STATIC_MESH
                && editor().isSelectTool()
                && !scene.getEditing().getSelection().contains(hovered.actor());
        return selectable ? object : null;
    }

    public void selectInEditing(@Nullable DebugSelection selected, Click click) {
        selectInEditing(selected, click, true);
    }

    public boolean measureTookMapKey() {
        return layout.mode == Mode.EDITING && editingViewport != null && editingViewport.consumeMeasureKey();
    }

    public void setExportSettings(@Nullable ExportSettings settings) {
        exportSettings = settings;
        if (editingPanel != null) {
            editingPanel.setExportSettings(settings);
        }
    }

    public void setEditingCheckSources(SpawnSource spawns, GeodataSource geodata) {
        editingSpawns = spawns;
        editingGeodata = geodata;
        if (editingPanel != null) {
            editingPanel.setCheckSources(spawns, geodata);
        }
    }

    public MapLayer getEditingMapLayer() {
        return editingMapLayer;
    }

    public boolean closeFrameLimit() {
        return frameLimit.close();
    }

    public boolean handleEditingEscape() {
        return layout.mode == Mode.EDITING
                && editingPanel != null
                && !isRecoveryPending()
                && editingPanel.handleEscape();
    }

    public void closeEditingSession() {
        if (editingPanel != null) {
            editingPanel.close();
            editingPanel = null;
        }
        editingViewport = null;
        recoveryPanel = null;
        treeFilterRevision = Long.MIN_VALUE;
        groupRowsRevision = Long.MIN_VALUE;
        scene.editorInputCaptured = false;
    }

    public void pointAt(@Nullable DebugSelection hovered) {
        pointed = hovered;
    }

    public boolean isSelectionShown(DebugSelection selected) {
        var rendering = ui.rendering;
        if (selected.emitter() != null) {
            return rendering.particleVisibilityDistance.get() > 0
                    && scene.particles.isNear(selected.emitter(), scene.camera.getWorldFocusPosition());
        }
        return switch (selected.sceneObject().category()) {
            case CSG -> rendering.csg;
            case STATIC_MESH -> rendering.staticMeshes;
            case TERRAIN -> rendering.terrain;
        };
    }

    public void select(DebugSelection selected, boolean additive) {
        if (isRecoveryPending()) {
            return;
        }
        if (layout.mode == Mode.EDITING) {
            selectInEditing(
                    selected,
                    new Click(additive ? EditorViewport.Mode.TOGGLE : EditorViewport.Mode.REPLACE, false),
                    false);
            return;
        }
        scene.inspection.select(selected);
    }

    public boolean guardReturnToMenu() {
        ui.settingsReloadRequested = false;
        return guardLeave();
    }

    public boolean guardWindowClose() {
        if (layout.mode != Mode.EDITING || !scene.doesEditingNeedSave()) {
            return false;
        }
        leaveReason = Reason.WINDOW;
        confirmEditingExit = true;
        return true;
    }

    public boolean consumeApprovedWindowClose() {
        var approved = approvedWindowClose;
        approvedWindowClose = false;
        return approved;
    }

    public void beginToolWindow(String id, String title) {
        beginToolWindow(id, title, false);
    }

    public float[] getViewToggle() {
        return new float[] {viewToggleX, viewToggleY, viewToggleWidth};
    }

    public void handlePauseKey() {
        if (effectsOnly
                || layout.mode == Mode.EDITING
                || ImGui.getIO().getWantTextInput()
                || !ImGui.isKeyPressed(ImGuiKey.P, false)) {
            return;
        }
        var playback = scene.playback;
        if (playback.claimPauseKey(ImGui.getFrameCount())) {
            playback.setPaused(!playback.isPaused());
        }
    }

    public void toggleMap() {
        ui.worldMapOpen = namedMap == null && !effectsOnly && !ui.worldMapOpen;
        helpOpen = false;
    }

    public void selectAndFocus(DebugSelection selected, Bounds3f box) {
        select(selected, false);
        focusBox(box);
    }

    public String getDiagnosticContext() {
        var selected = scene.inspection.selected;
        var r = ui.rendering;
        var p = scene.camera.getWorldFocusPosition();
        var d = scene.camera.getCursorRayDirection(0, 0);
        var out = new StringBuilder("Cruma diagnostic context\nclient=")
                .append(client)
                .append("\ncamera=")
                .append(p.getX())
                .append(' ')
                .append(p.getY())
                .append(' ')
                .append(p.getZ())
                .append("\nlook=")
                .append(d.getX())
                .append(' ')
                .append(d.getY())
                .append(' ')
                .append(d.getZ())
                .append("\nprojection=")
                .append(scene.camera.isOrtho() ? "orthographic" : "perspective")
                .append("\nfovDegrees=")
                .append(scene.camera.getFov())
                .append("\nprojectionNear=")
                .append(scene.camera.getProjectionNear())
                .append("\northoZoom=")
                .append(scene.camera.getOrthoZoomFactor())
                .append("\nsceneTime=")
                .append(scene.playback.getPreciseSeconds())
                .append("\npaused=")
                .append(scene.playback.isPaused())
                .append("\nplaybackSpeed=")
                .append(scene.playback.getSpeed());
        if (selected != null) {
            out.append("\nselection=")
                    .append(selected.fullName())
                    .append("\nkey=")
                    .append(selected.key());
            for (var slot : selected.materials()) {
                out.append("\nmaterial[")
                        .append(slot.slot())
                        .append("]=")
                        .append(slot.name())
                        .append(" | blend=")
                        .append(slot.blend())
                        .append(" | graph=")
                        .append(slot.materialType());
            }
        }
        out.append("\ncustomLighting.effective=")
                .append(r.isEffectiveCustomLighting())
                .append("\ncustomLighting.interiorSuppression=")
                .append(r.stats.customLightingSuppressedByAuthoredInterior)
                .append("\nwater.reflectionReady=")
                .append(r.stats.waterReflectionReady)
                .append("\nwater.backgroundReady=")
                .append(r.stats.waterBackReady)
                .append("\nwater.effective=")
                .append(r.stats.realisticWaterStatus);
        ViewSettingsSnapshot.capture(r)
                .values()
                .forEach((name, value) ->
                        out.append('\n').append(name).append('=').append(value));
        return out.toString();
    }

    private void syncEditingTools() {
        var editing = layout.mode == Mode.EDITING;
        if (!effectsOnly && editing != editingTools) {
            editingTools = editing;
            if (editing) {
                tools.hide(VIEWING_TOOLS);
            } else {
                tools.show(VIEWING_TOOLS);
            }
        }
    }

    private void syncEditingIsolate() {
        var actors = layout.mode == Mode.EDITING ? editor().getIsolatedActors() : null;
        if (!Objects.equals(actors, isolatedActors)) {
            backend.isolateEditorActors(actors);
            isolatedActors = actors;
        }
    }

    private void frameIsolated() {
        var camera = scene.camera;
        var view = camera.getView();
        Object target = isolatedActors != null
                ? isolatedActors
                : scene.inspection.isIsolated() ? scene.inspection.selected.key() : null;
        var moved = isolateFraming.isMoved();
        var move = isolateFraming.next(view, target);
        if (!scene.playback.isComparisonLocked()) {
            if (move == Move.FRAME) {
                var box = isolatedBox();
                if (box != null) {
                    if (!moved) {
                        zoomBeforeFraming = camera.getOrthoZoomFactor();
                    }
                    focusEditingBox(box);
                }
            } else if (move == Move.RESTORE) {
                camera.setPosition(freePlace[0], freePlace[1], freePlace[2]);
                camera.setOrthoZoomFactor(zoomBeforeFraming);
            }
        }
        if (view == CameraView.PERSPECTIVE) {
            var place = camera.getPosition();
            freePlace[0] = place.getX();
            freePlace[1] = place.getY();
            freePlace[2] = place.getZ();
        }
    }

    private @Nullable Bounds3f isolatedBox() {
        if (isolatedActors != null) {
            return editorActorsBox(isolatedActors);
        }
        var selected = scene.inspection.selected;
        if (selected == null) {
            return null;
        }
        var box = selected.emitter() != null
                ? backend.getDebugMeshEmitterBounds(selected.emitter())
                : selected.sceneObject() == null ? null : backend.getDebugInstanceBounds(selected.sceneObject());
        return box == null ? selected.bounds() : box;
    }

    private void applyMode(Mode mode) {
        if (mode != Mode.EDITING && editingPanel != null) {
            editingPanel.endIsolate();
        }
        layout.mode = mode;
        ui.worldMapOpen = false;
        ui.rendering.objectPicker = mode != Mode.EXPLORE;
        if (mode != Mode.EXPLORE) {
            tools.open(Tool.OBJECT);
        } else if (tools.isOpen(Tool.OBJECT)) {
            tools.close();
        }
    }

    private boolean isTreeShown() {
        return (layout.mode == Mode.ANALYZE || layout.mode == Mode.EDITING) && !layout.treeFolded;
    }

    private boolean isTreeFolded() {
        return (layout.mode == Mode.ANALYZE || layout.mode == Mode.EDITING) && layout.treeFolded;
    }

    private boolean isObjectCardShown() {
        return scene.inspection.selected != null
                && !tools.isOpen(Tool.OBJECT)
                && layout.mode == Mode.EXPLORE
                && !effectsOnly;
    }

    private float analysisY() {
        return height - MARGIN - trayHeight;
    }

    private float statusY() {
        return isStatusDocked() ? statusBase() : statusHomeY();
    }

    private float statusHomeY() {
        return statusBase() - MARGIN - STATUS;
    }

    private float statusBase() {
        return trayHeight > 0 ? analysisY() : height;
    }

    private void turnStatus() {
        var docked = isStatusDocked();
        if (statusWasDocked != null && statusWasDocked != docked) {
            Motion.restart(STATUS_TURN);
            statusLanded = false;
            statusSettled = false;
        }
        statusWasDocked = docked;
        statusSince = Motion.since(STATUS_TURN, 1);
    }

    private float sidePanelBottom() {
        return trayHeight > 0 ? analysisY() - MARGIN : getViewportBottom();
    }

    private void notify(String message) {
        feedback = message;
        feedbackUntil = System.nanoTime() + NOTICE_NANOS;
    }

    private boolean isTileReloadShown() {
        var loading = ui.rendering.loading;
        return loading.reloading && loading.tilesToReload > 0;
    }

    private void drawTileReload() {
        if (!isTileReloadShown()) {
            return;
        }
        var loading = ui.rendering.loading;
        var text = String.format(
                Locale.ROOT,
                "Loading tiles for %s · %d of %d",
                loading.reloadingForEditing ? "editing" : "viewing",
                loading.tilesReloaded,
                loading.tilesToReload);
        var cardWidth = ImGui.calcTextSizeX(text) + 40;
        beginPanel(
                "##tileReload",
                Math.round((width - cardWidth) * .5f),
                statusY() - MARGIN - RELOAD_CARD,
                cardWidth,
                RELOAD_CARD,
                true,
                HudWidgets.PANEL_RADIUS);
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getWindowPosX();
        var y = ImGui.getWindowPosY();
        var textTop = y + 8;
        draw.addCircleFilled(x + 14, HudWidgets.textMiddle(textTop), 4, ACCENT, 12);
        draw.addText(x + 26, textTop, TEXT, text);
        var barLeft = x + 26;
        var barRight = x + cardWidth - 14;
        var barTop = y + RELOAD_CARD - 11;
        draw.addRectFilled(barLeft, barTop, barRight, barTop + 3, GeodataWidgets.DIVIDER, 2f);
        var done = Math.clamp((float) loading.tilesReloaded / loading.tilesToReload, 0f, 1f);
        if (done > 0) {
            draw.addRectFilled(
                    barLeft, barTop, barLeft + Math.max(3, (barRight - barLeft) * done), barTop + 3, ACCENT, 2f);
        }
        ImGui.setCursorPos(0, 0);
        ImGui.invisibleButton("##tileReloadHover", cardWidth, RELOAD_CARD);
        tooltip(
                loading.reloadingForEditing
                        ? "Each tile changes when it has loaded, nearest to the camera first. Props that viewing"
                                + " merges into one mesh can be selected once their tile has loaded."
                        : "Each tile changes when it has loaded, nearest to the camera first. Viewing merges small"
                                + " props again, so the world draws faster.");
        ImGui.end();
    }

    private void drawEditingMessage() {
        editingMessage = 0;
        if (layout.mode != Mode.EDITING || editingPanel == null || ui.worldMapOpen) {
            return;
        }
        var reloadCard = isTileReloadShown() ? RELOAD_CARD + 8 : 0;
        var right = (rightWidth > 0 ? getToolPanelX() : getToolRailX()) - MARGIN;
        var left = right - getViewportLeft() >= 320 ? getViewportLeft() : MARGIN;
        var shown = editingPanel.drawMessage(width * .5f, left, right, statusY() - MARGIN - reloadCard);
        editingMessage = shown > 0 ? shown + 8 : 0;
    }

    private void drawNotice() {
        var now = System.nanoTime();
        var shots = ui.screenshots;
        if (shots.isWindowCaptured()) {
            screenshotUntil = Math.min(screenshotUntil, now);
            shutterHeld = true;
        } else if (shots.getRevision() != shownScreenshot) {
            shownScreenshot = shots.getRevision();
            screenshotUntil = now + NOTICE_NANOS + 2_000_000_000L;
            if (shots.getFailure().isBlank()) {
                Motion.restart(SHUTTER);
                shutterHeld = false;
            }
        }
        var shutter = Motion.since(SHUTTER, .35f);
        if (shutter < 1 && !shutterHeld) {
            var fading = 1 - shutter;
            ImGui.getForegroundDrawList()
                    .addRectFilled(
                            0, 0, width, height, Motion.alpha(ImColor.rgb(255, 255, 255), .22f * fading * fading));
        }
        var shot = shots.getLast() != null && now < screenshotUntil;
        if (!shot && (feedback.isBlank() || now >= feedbackUntil)) {
            return;
        }
        var failed = shot && !shots.getFailure().isBlank();
        var text = !shot
                ? feedback
                : failed
                        ? "Screenshot not saved: " + shots.getFailure()
                        : "Screenshot saved \u00b7 " + shots.getLast().getFileName();
        var maxText = Math.max(160, width - MARGIN * 4 - 140);
        if (ImGui.calcTextSizeX(text) > maxText) {
            text = text.substring(0, Math.max(0, (int) (text.length() * maxText / ImGui.calcTextSizeX(text)) - 1))
                    + "\u2026";
        }
        var until = shot ? screenshotUntil : feedbackUntil;
        var open = shot && !failed;
        var buttonWidth = open ? 108f : 0f;
        var noticeWidth = ImGui.calcTextSizeX(text) + 28 + (open ? buttonWidth + 10 : 0);
        var noticeHeight = 42f;
        var reloadCard = (isTileReloadShown() ? RELOAD_CARD + 8 : 0) + editingMessage;
        beginPanel(
                "##workspaceNotice",
                Math.round((width - noticeWidth) * .5f),
                statusY() - MARGIN - noticeHeight - reloadCard,
                noticeWidth,
                noticeHeight,
                true,
                HudWidgets.PANEL_RADIUS);
        imgui.internal.ImGui.bringWindowToDisplayFront(imgui.internal.ImGui.getCurrentWindow());
        if (!text.equals(noticeShown)) {
            noticeShown = text;
            Motion.restart(NOTICE_SHOWN);
        }
        var rise = Motion.appear(NOTICE_SHOWN, .32f);
        var leave = (float) Math.clamp((until - now) / 300_000_000.0, 0, 1);
        Motion.fade(
                Math.min(Math.clamp(rise * 2, 0, 1), leave),
                1,
                0,
                18 * (1 - Motion.easeOutBack(rise)) + 10 * (1 - leave),
                1 - .06f * (1 - Motion.easeOutBack(rise)),
                0);
        ImGui.setCursorPos(14, (noticeHeight - ImGui.getFontSize()) * .5f);
        ImGui.textColored(failed ? GeodataWidgets.WARNING : HudWidgets.TEXT, text);
        if (shot) {
            tooltip(shots.getLast().toString());
        }
        if (open) {
            ImGui.setCursorPos(noticeWidth - buttonWidth - 8, (noticeHeight - 28) * .5f);
            if (HudWidgets.button("openScreenshots", Glyph.FOLDER, "Open folder", buttonWidth, 28, false, false)) {
                FileManager.tryOpenFolder(shots.getLast().getParent());
            }
        }
        ImGui.end();
    }

    private EditingPanel editor() {
        if (editingPanel == null) {
            editingPanel = new EditingPanel(scene.getEditing());
            editingPanel.setFonts(fonts);
            editingPanel.setInspection(scene.inspection);
            editingPanel.setTerrainSession(scene.getTerrainEditing());
            editingPanel.setExportSettings(exportSettings);
            editingPanel.setCheckSources(editingSpawns, editingGeodata);
            var settings = scene.getEditingSettings();
            editingPanel.setMoveActorsToTheirTile(settings == null || settings.moveActorsToTheirTile());
            if (settings != null) {
                editingPanel.setTeleportCommand(settings.teleportCommand());
            }
            if (configDirectory != null) {
                editingPanel.setLibrary(
                        new MeshLibrary(configDirectory, scene.getEditing().getClientRoot()), backend.getUiRenderer());
            }
        }
        return editingPanel;
    }

    private void selectInEditing(@Nullable DebugSelection selected, Click click, boolean fromView) {
        if (isRecoveryPending() || layout.mode != Mode.EDITING) {
            return;
        }
        var session = scene.getEditing();
        var actor = selected == null
                        || selected.sceneObject() == null
                        || selected.sceneObject().category() != Category.STATIC_MESH
                ? null
                : selected.actor();
        if (fromView && actor != null && click.next() != null) {
            actor = click.next();
        }
        try {
            if (actor == null) {
                if (click.mode() == EditorViewport.Mode.REPLACE) {
                    session.select(null, false);
                    scene.inspection.select(null);
                }
                editor().showError("");
                return;
            }
            if (session.getLockedActors().contains(actor)
                    && !session.getSelection().contains(actor)) {
                editor().showNote(DebugSelection.shortName(actor)
                        + " is locked. Its lock in the Objects list or L unlocks it.");
                return;
            }
            if (session.getHiddenActors().contains(actor)) {
                session.show(List.of(actor));
            }
            var group = fromView ? session.findGroupOf(actor) : null;
            if (group == null) {
                switch (click.mode()) {
                    case REPLACE -> session.select(actor, false);
                    case TOGGLE -> session.select(actor, true);
                    case ADD -> session.selectMany(List.of(actor), true);
                }
            } else {
                selectGroupMember(actor, click.mode());
            }
            if (click.twice()) {
                session.select(actor, false);
                focusEditingSelection();
            }
            editor().showError("");
        } catch (RuntimeException failure) {
            if (!editor().noteIfUneditable(failure.getMessage())) {
                editor().showError(failure.getMessage());
            }
            return;
        }
        scene.inspection.select(session.getSelection().contains(actor) ? selected : null);
    }

    private void selectGroupMember(String actor, EditorViewport.Mode mode) {
        var session = scene.getEditing();
        if (mode == EditorViewport.Mode.REPLACE) {
            var targets = session.getClickTargets(actor);
            if (targets.size() == 1) {
                session.select(actor, false);
            } else {
                session.selectMany(targets, false);
            }
            return;
        }
        var targets = session.getGroupTargets(actor);
        if (mode == EditorViewport.Mode.TOGGLE && session.getSelection().containsAll(targets)) {
            for (var member : targets) {
                session.select(member, true);
            }
        } else {
            session.selectMany(targets, true);
        }
    }

    private @Nullable EditingPanel openEditingPanel() {
        return layout.mode == Mode.EDITING && scene.hasEditingSession() ? editingPanel : null;
    }

    private void focusEditingSelection() {
        var union = editorActorsBox(scene.getEditing().getSelection());
        if (union != null) {
            focusEditingBox(union);
        }
    }

    private @Nullable Bounds3f editorActorsBox(Set<String> actors) {
        Bounds3f union = null;
        for (var object : backend.getEditorActorObjects(actors)) {
            var box = object.bounds();
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
        return union;
    }

    private void focusEditingBox(Bounds3f box) {
        if (scene.playback.isComparisonLocked()) {
            return;
        }
        scene.camera.focus(
                box,
                (getViewportLeft() + getViewportRight()) / width - 1f,
                1f - (topHeight + getViewportBottom()) / height,
                (getViewportRight() - getViewportLeft()) / width,
                (getViewportBottom() - topHeight) / height);
    }

    private void reloadEditing() {
        ui.workspaceMode = Mode.EDITING;
        if (scene.doesEditingNeedSave()) {
            leaveReason = Reason.RELOAD;
            confirmEditingExit = true;
        } else {
            ui.editingReloadRequested = true;
        }
    }

    private void drawEditingExitConfirmation() {
        if (confirmEditingExit && !isSettingsOpen()) {
            ImGui.openPopup(EditLeave.TITLE);
            confirmEditingExit = false;
        }
        ImGui.setNextWindowPos(width / 2f, height / 2f, ImGuiCond.Appearing, .5f, .5f);
        if (!ImGui.beginPopupModal(
                EditLeave.TITLE,
                ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoSavedSettings)) {
            return;
        }
        Motion.dialog();
        var reason = leaveReason;
        ImGui.textUnformatted(EditLeave.line(editor().getUnsavedChanges(), reason));
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        Choice chosen = null;
        var first = true;
        for (var choice : EditLeave.choices(reason)) {
            if (!first) {
                ImGui.sameLine();
            }
            first = false;
            var label = EditLeave.label(choice, reason);
            var buttonWidth = Math.max(88, ImGui.calcTextSizeX(label) + 32);
            var busy = choice == Choice.KEEP && scene.isEditingOperationActive();
            var pressed = switch (choice) {
                case SAVE -> EditorUi.primaryButton(label + "##editingLeaveSave", buttonWidth);
                case KEEP ->
                    HudWidgets.button(
                            "editingLeaveKeep", null, label, buttonWidth, EditorUi.BUTTON_HEIGHT, false, !busy);
                case DISCARD -> EditorUi.dangerButton(label + "##editingLeaveDiscard", buttonWidth);
            };
            EditorUi.tip(
                    busy
                            ? "Wait until the Terrain work is done, so the draft can keep it."
                            : EditLeave.tip(choice, reason));
            if (pressed) {
                chosen = choice;
            }
        }
        if (chosen == null && ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            ui.workspaceMode = layout.mode;
            tools.open(Tool.OBJECT);
            ImGui.closeCurrentPopup();
            ImGui.endPopup();
            return;
        }
        if (chosen != null && leave(EditLeave.outcome(chosen, reason))) {
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
    }

    private boolean leave(Outcome outcome) {
        if (outcome.stay()) {
            ui.workspaceMode = layout.mode;
            tools.open(Tool.OBJECT);
            editor().showSave();
            return true;
        }
        if (outcome.dropDraft()) {
            try {
                scene.discardEditingRecovery();
            } catch (IOException failure) {
                editor().showError("Could not discard the recovery draft: " + failure.getMessage());
                return false;
            }
        }
        switch (outcome.reason()) {
            case WINDOW -> approvedWindowClose = true;
            case MENU -> ui.returnToMenuRequested = true;
            case VIEWER, RELOAD -> {
                ui.editingReloadRequested = true;
                applyMode(ui.workspaceMode);
            }
        }
        return true;
    }

    private boolean guardLeave() {
        if (layout.mode != Mode.EDITING || !scene.doesEditingNeedSave()) {
            return false;
        }
        leaveReason = Reason.MENU;
        confirmEditingExit = true;
        return true;
    }

    private boolean isSettingsOffered() {
        return ui.configDirectory != null && !ui.agentViewer && ui.recording == null;
    }

    private void drawSettings() {
        var quiet = ui.settingsReloadRequested
                && !ui.returnToMenuRequested
                && !confirmEditingExit
                && !ImGui.isPopupOpen("", ImGuiPopupFlags.AnyPopupId | ImGuiPopupFlags.AnyPopupLevel);
        settingsReloadQuiet = quiet ? settingsReloadQuiet + 1 : 0;
        if (settingsReloadQuiet >= 2) {
            ui.settingsReloadRequested = false;
            settingsReloadQuiet = 0;
        }
        if (settingsWanted) {
            settingsWanted = false;
            var folder = ui.configDirectory;
            if (folder == null) {
                return;
            }
            try {
                var opened = new LaunchOptions(
                        new ConfigLoader(new Options(folder, false, false)), Host.VIEWER, clientPath());
                opened.selectTiles(ui.loadedMaps);
                settings = opened;
                ImGui.openPopup(LaunchOptions.TITLE);
            } catch (IOException | RuntimeException failure) {
                notify("Could not open Settings: " + failure.getMessage());
            }
        }
        var open = settings;
        if (open == null) {
            return;
        }
        if (!ImGui.isPopupOpen(LaunchOptions.TITLE)) {
            if (ImGui.isPopupOpen("", ImGuiPopupFlags.AnyPopupId | ImGuiPopupFlags.AnyPopupLevel)) {
                return;
            }
            ImGui.openPopup(LaunchOptions.TITLE);
        }
        open.setAutomaticScalePercent(ui.automaticScalePercent.getAsInt());
        if (open.draw(width, height, fonts)) {
            windowCloseAfterSettings = false;
            return;
        }
        settings = null;
        windowCloseAgain = windowCloseAfterSettings;
        windowCloseAfterSettings = false;
        if (open.isSaved()) {
            settingsSaved.run();
        }
        if (open.isReloadRequested()) {
            requestSettingsReload();
        }
    }

    private void requestSettingsReload() {
        ui.settingsReloadRequested = true;
        settingsReloadQuiet = 0;
        ui.workspaceMode = layout.mode;
        ui.returnToMenuRequested = !guardLeave();
    }

    private @Nullable Path clientPath() {
        return client.isEmpty() ? null : Path.of(client);
    }

    private void updatePanelWidth() {
        rightWidth = tools.isOpen()
                ? Math.min(panelWidth(), Math.max(1, width - ToolWindows.RAIL_WIDTH - MARGIN * 2 - leftWidth - 160))
                : 0;
    }

    private boolean isEditPanelOpen() {
        return layout.mode == Mode.EDITING && tools.isOpen(Tool.OBJECT);
    }

    private float panelWidth() {
        return isEditPanelOpen() ? layout.editingWidth : layout.inspectorWidth;
    }

    private void markSelection() {
        var hovered = pointed;
        pointed = null;
        if (ui.worldMapOpen) {
            return;
        }
        var chosen = scene.inspection.selected;
        SelectionMark.draw(
                scene.spawns.editorLines,
                chosen != null && isSelectionShown(chosen) ? chosen : null,
                hovered,
                scene.inspection.showBounds);
    }

    private void refreshSelection() {
        var selected = scene.inspection.selected;
        if (selected == null) {
            return;
        }
        if (layout.mode == Mode.EDITING
                && scene.hasEditingSession()
                && !scene.getEditing().getSelection().contains(selected.actor())) {
            scene.inspection.select(null);
            return;
        }
        if (layout.mode == Mode.EDITING && selected.sceneObject() != null) {
            var current = backend.getEditorActorObjects(Set.of(selected.actor()));
            if (!current.isEmpty()) {
                scene.inspection.selected = DebugSelection.scene(current.getFirst(), selected.hitDistance());
            } else {
                scene.inspection.select(null);
            }
            return;
        }
        if (selected.sceneObject() != null) {
            var current = backend.refreshDebugSceneObject(selected.sceneObject());
            if (current == null) {
                scene.inspection.select(null);
            } else {
                scene.inspection.selected = DebugSelection.scene(current, selected.hitDistance());
            }
        } else if (!scene.particles.getEmitters().contains(selected.emitter())) {
            scene.inspection.select(null);
        }
    }

    private void toolRail() {
        var railX = getToolRailX();
        ImGui.setNextWindowPos(railX, MARGIN, ImGuiCond.Always);
        ImGui.setNextWindowSize(ToolWindows.RAIL_WIDTH, Math.max(1, height - MARGIN * 2), ImGuiCond.Always);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowMinSize, 1, 1);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, HudWidgets.PANEL_RADIUS);
        ImGui.setNextWindowBgAlpha(1f);
        ImGui.begin("##toolRail", PANEL | ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        var icons = new Glyph[] {
            SLIDERS,
            BOX,
            PROFILE,
            GEODATA,
            Glyph.NPC,
            Glyph.AREA,
            Glyph.CARD,
            Glyph.SWITCH,
            Glyph.SPARKLES,
            Glyph.CLAPPERBOARD,
            Glyph.BOT
        };
        var hints = new String[] {
            "Scene settings · F3",
            layout.mode == Mode.EDITING ? "Edit · Tab" : "Object inspector · Tab",
            "Performance · F4",
            "Geodata · G shows or hides the overlay",
            "NPC Spawns · N shows or hides the zones",
            "Areas · L shows or hides the server's areas",
            "NPCs · what they drop, their skills and numbers",
            "Map events · server events and effects by time of day",
            "Effects · play the client's effect classes",
            namedMap != null
                    ? "Scenes · the camera scenes of this map, and the video director"
                    : "Scenes · the cut scenes of the game, and the video director",
            "AI agent · what an agent does in this window"
        };
        var shown = tools.getShown();
        var openRow = -1;
        for (var row = 0; row < shown.size(); row++) {
            var tool = shown.get(row);
            var index = tool.ordinal();
            var y = ToolWindows.RAIL_PADDING + row * ToolWindows.BUTTON_STEP;
            ImGui.setCursorPos(4, y);
            if (HudWidgets.railButton(
                    "tool/" + tool,
                    icons[index],
                    ToolWindows.RAIL_WIDTH - 8,
                    ToolWindows.BUTTON_HEIGHT,
                    ToolWindows.ICON_SIZE,
                    tools.isOpen(tool))) {
                toggleTool(tool);
            }
            tooltip(hints[index] + " · Click again to collapse");
            if (tools.isOpen(tool)) {
                openRow = row;
            }
        }
        var shownId = ImGui.getID("##railMarkShown");
        var markId = ImGui.getID("##railMark");
        var target = ToolWindows.RAIL_PADDING + Math.max(openRow, 0) * ToolWindows.BUTTON_STEP;
        if (openRow >= 0 && Motion.peek(shownId, 1) < .05f) {
            Motion.jump(markId, target);
        }
        var markShown = Motion.toggle(shownId, openRow >= 0, Motion.QUICK * 1.5f);
        var markY = openRow >= 0 ? Motion.spring("##railMark", target) : Motion.peek(markId, target);
        if (markShown > 0) {
            var middle = MARGIN + markY + ToolWindows.BUTTON_HEIGHT * .5f;
            var half = (ToolWindows.BUTTON_HEIGHT * .5f - 8) * Motion.easeOut(markShown);
            ImGui.getWindowDrawList()
                    .addRectFilled(
                            railX + 1, middle - half, railX + 3, middle + half, Motion.alpha(ACCENT, markShown), 1f);
        }
        turnStatus();
        drawStatusDock(railX);
        ImGui.end();
        ImGui.popStyleVar(3);
    }

    private void drawStatusDock(float railX) {
        if (ui.sceneHoldsCamera || !isStatusDocked()) {
            return;
        }
        var top = getStatusDockY();
        var side = ToolWindows.RAIL_WIDTH - 8;
        ImGui.setCursorPos(4, top - MARGIN);
        if (HudWidgets.button("statusDock", null, "", side, ToolWindows.BUTTON_HEIGHT, false, false)) {
            layout.statusDocked = false;
        }
        tooltip("Status bar · " + statusSummary() + " · Click to bring it back");
        if (statusLanded) {
            var size = ToolWindows.ICON_SIZE;
            HudWidgets.glyph(
                    ImGui.getWindowDrawList(),
                    Glyph.COMPASS,
                    railX + 4 + (side - size) * .5f,
                    top + (ToolWindows.BUTTON_HEIGHT - size) * .5f,
                    size,
                    TEXT);
        }
    }

    private String statusSummary() {
        var camera = scene.camera;
        var control = camera.isInteractiveOrtho()
                ? "Zoom " + zoom.format(Camera.zoomPercent(camera.getOrthoZoomFactor()))
                : "Speed " + cameraSpeed.format(ui.rendering.cameraSpeed.get());
        return effectsOnly ? control : coordinateText() + " · " + control;
    }

    private void beginToolWindow(String id, String title, boolean scrolling) {
        beginPanel(
                id,
                getToolPanelX(),
                getToolPanelTop(),
                getToolPanelWidth(),
                getToolPanelHeight(),
                !scrolling,
                HudWidgets.PANEL_RADIUS);
        toolPanelMotion(id);
        heading(title, 18);
        var topic = guideTopic.get();
        if (topic != null) {
            ImGui.sameLine(ImGui.getWindowContentRegionMaxX() - 24 - 4 - 24);
            HudWidgets.guideButton("guideTool", topic);
        }
        ImGui.sameLine(ImGui.getWindowContentRegionMaxX() - 24);
        if (HudWidgets.button("closeTool", CLOSE, "", 24, 24, false, false)) {
            tools.close();
        }
        tooltip("Collapse panel · Esc");
        gap(6);
        rule();
        gap(8);
    }

    private void toolPanelMotion(String id) {
        var frame = ImGui.getFrameCount();
        if (panelFrame == frame - 1 && !id.equals(panelShown)) {
            Motion.restart(PANEL_SWAP);
        }
        panelShown = id;
        panelFrame = frame;
        panelRect[0] = getToolPanelX();
        panelRect[1] = getToolPanelTop();
        panelRect[2] = getToolPanelWidth();
        panelRect[3] = getToolPanelHeight();
        panelClosing = false;
        var opening = Motion.appear(PANEL_OPEN, Motion.OPEN);
        var content = Motion.easeOut(Motion.appear(PANEL_SWAP, Motion.SWAP));
        Motion.fade(
                Math.clamp(opening * 1.8f, 0, 1),
                content,
                36 * (1 - Motion.easeOut(opening)),
                0,
                1,
                10 * (1 - content));
    }

    private void drawClosingPanel() {
        var frame = ImGui.getFrameCount();
        if (panelFrame == frame - 1 && !tools.isOpen()) {
            Motion.restart(PANEL_CLOSE);
            panelClosing = true;
        }
        if (!panelClosing) {
            return;
        }
        var progress = Motion.since(PANEL_CLOSE, .16f);
        if (progress >= 1 || tools.isOpen()) {
            panelClosing = false;
            return;
        }
        var away = progress * progress;
        var x = panelRect[0] + 40 * away;
        var draw = ImGui.getBackgroundDrawList();
        draw.addRectFilled(
                x,
                panelRect[1],
                x + panelRect[2],
                panelRect[1] + panelRect[3],
                Motion.alpha(HudWidgets.BACKGROUND, .985f * (1 - progress)),
                HudWidgets.PANEL_RADIUS);
        draw.addRect(
                x,
                panelRect[1],
                x + panelRect[2],
                panelRect[1] + panelRect[3],
                Motion.alpha(HudWidgets.BORDER, .9f * (1 - progress)),
                HudWidgets.PANEL_RADIUS,
                0,
                1);
    }

    private void topBar() {
        if (effectsOnly) {
            effectsBar();
            return;
        }
        var editing = layout.mode == Mode.EDITING;
        var barX = MARGIN;
        var barY = MARGIN;
        var available = getViewportRight() - barX;
        var fpsText = ui.rendering.stats.fps + " FPS";
        var fpsWidth = Math.max(54, ImGui.calcTextSizeX(fpsText) + 12);
        var closedWidth = editing ? EDITING_BAR_WIDTH : VIEWER_BAR_WIDTH;
        var controlsWidth = viewControlsWidth(editing);
        var unfolds = closedWidth + controlsWidth + GeodataWidgets.ROW <= available;
        var unfolded = layout.viewControlsOpen && unfolds;
        var barWidth = Math.min(
                available,
                Motion.spring(
                        "##topBarWidth", unfolded ? closedWidth + controlsWidth + GeodataWidgets.ROW : closedWidth));
        var closed = Math.min(barWidth, closedWidth);
        var compact = closed < (editing ? EDITING_ONE_ROW : VIEWER_ONE_ROW);
        var barHeight = compact ? HudWidgets.BAR_HEIGHT + 36 : HudWidgets.BAR_HEIGHT;
        topHeight = barY + barHeight + MARGIN;
        commandBarRight = barX + barWidth;
        beginPanel("##workspaceCommands", barX, barY, barWidth, barHeight, true, HudWidgets.PANEL_RADIUS);
        var y = (HudWidgets.BAR_HEIGHT - 28) * .5f;
        branding.mark(ImGui.getWindowDrawList(), barX + 10, barY + y, 28);
        var placesX = 42f;
        ImGui.setCursorPos(placesX, y);
        var toggleWidth =
                closed >= placesX + 96 + GeodataWidgets.ROW + VIEW_TOGGLE_WIDTH + GeodataWidgets.ROW + GUIDE_END
                        ? VIEW_TOGGLE_WIDTH
                        : 28;
        var showTile = closed >= placesX + 96 + GeodataWidgets.ROW + toggleWidth + GeodataWidgets.ROW + GUIDE_END;
        if (namedMap != null) {
            ImGui.setCursorPos(placesX + 4, y + (28 - ImGui.getFontSize()) * .5f - 1);
            ImGui.textColored(MUTED, HudWidgets.truncateToWidth(namedMap, 96));
            tooltip("Map " + namedMap
                    + " · loaded alone, without the world map, Geodata, NPC Spawns, NPCs and Effects");
        } else {
            var placesClicked = showTile
                    ? HudWidgets.menuButton("worldMap", GLOBE, tileName(), 96, 28, ui.worldMapOpen)
                    : HudWidgets.button("worldMap", GLOBE, "", 28, 28, ui.worldMapOpen, false);
            if (placesClicked) {
                toggleMap();
            }
            tooltip("World map · M · " + ui.rendering.loading.tilesLoaded + " tiles loaded");
        }
        var toggleX = closed - GUIDE_END - GeodataWidgets.ROW - toggleWidth;
        var fpsX = compact ? barWidth - 10 - fpsWidth : toggleX - GeodataWidgets.ROW - fpsWidth;
        var workspaceX = compact ? 10f : 148f;
        var workspaceY = compact ? y + 36 : y;
        if (editing) {
            editingName(barX, barY, workspaceX, workspaceY, fpsX);
        } else {
            workspaces(barX, barY, workspaceX, workspaceY, compact, barWidth, fpsX, fpsWidth);
        }
        drawFpsButton(fpsText, fpsX, fpsWidth, workspaceY, barY + barHeight);
        ImGui.setCursorPos(toggleX, y);
        var chevron = unfolds ? (unfolded ? Glyph.CHEVRON_LEFT : Glyph.CHEVRON_RIGHT) : Glyph.CHEVRON;
        var viewName =
                scene.camera.getView().getLabel() + " · " + scene.getViewMode().getLabel();
        if (HudWidgets.foldButton("viewToggle", toggleWidth > 28 ? viewName : "", chevron, toggleWidth, 28, unfolded)) {
            if (unfolds) {
                layout.viewControlsOpen = !layout.viewControlsOpen;
            } else {
                ImGui.openPopup("##viewControls");
            }
        }
        tooltip("View · " + viewName + ". Click for Free, Top, Front and Side, and for Lit, Unlit and Wire.");
        viewToggleX = barX + toggleX;
        viewToggleY = barY + y;
        viewToggleWidth = toggleWidth;
        if (barWidth > closed + 1) {
            drawViewControls(closed - GUIDE_END, 0, false, editing);
        }
        viewControlsPopup(barX + toggleX + toggleWidth, barY + barHeight, editing);
        ImGui.setCursorPos(barWidth - GUIDE_END, y);
        drawGuideButton();
        ImGui.setCursorPos(barWidth - MENU_END, y);
        viewerMenu(barX + barWidth, barY + barHeight);
        ImGui.end();
    }

    private void drawGuideButton() {
        if (HudWidgets.button("guide", Glyph.BOOK_OPEN, "", 28, 28, isGuideOpen(), false)) {
            GuideRequests.toggleTopic();
        }
        guideButton[0] = ImGui.getItemRectMinX();
        guideButton[1] = ImGui.getItemRectMinY();
        guideButton[2] = ImGui.getItemRectMaxX();
        guideButton[3] = ImGui.getItemRectMaxY();
        tooltip("Guide (Shift+F1)");
    }

    private void workspaces(
            float barX, float barY, float x, float y, boolean compact, float barWidth, float fpsX, float fpsWidth) {
        var modeX = x;
        var modesFit = !compact || barWidth >= 290 + fpsWidth;
        if (modesFit) {
            var pill = Motion.spring("##modePill", modeX + 66 * layout.mode.ordinal());
            ImGui.getWindowDrawList()
                    .addRectFilled(barX + pill, barY + y, barX + pill + 64, barY + y + 28, ACCENT_FILL, 6f);
            for (var mode : Mode.values()) {
                ImGui.setCursorPos(modeX, y);
                if (HudWidgets.button("mode/" + mode, null, modeName(mode), 64, 28, false, false)) {
                    setMode(mode);
                }
                tooltip(modeTooltip(mode));
                modeX += 66;
            }
        } else {
            ImGui.setCursorPos(modeX, y);
            var icon = switch (layout.mode) {
                case EXPLORE -> EYE;
                case INSPECT -> BOX;
                case ANALYZE -> PROFILE;
                case EDITING -> PENCIL;
            };
            var modeWidth = fpsX - modeX - 8;
            var modeClicked = modeWidth >= 54
                    ? HudWidgets.menuButton("workspaceMode", icon, "", modeWidth, 28, false)
                    : HudWidgets.button("workspaceMode", icon, "", modeWidth, 28, false, false);
            if (modeClicked) {
                ImGui.openPopup("##workspaceMode");
            }
            tooltip(modeTooltip(layout.mode));
            ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 12, 9);
            if (ImGui.beginPopup("##workspaceMode")) {
                for (var mode : Mode.values()) {
                    if (ImGui.menuItem(modeName(mode), "", layout.mode == mode)) {
                        setMode(mode);
                    }
                }
                ImGui.endPopup();
            }
            ImGui.popStyleVar();
        }
        var searchX = compact ? 280f : x + 66 * Mode.values().length + GeodataWidgets.ROW;
        var searchWidth = fpsX - searchX - 8;
        if (modesFit && searchWidth >= 120) {
            ImGui.setCursorPos(searchX, y);
            searchField("##objectSearch", searchWidth, "Search objects…");
        }
    }

    private void editingName(float barX, float barY, float x, float y, float fpsX) {
        var nameX = x + 26;
        if (fpsX - 8 - nameX < ImGui.calcTextSizeX("Edit")) {
            return;
        }
        var textY = y + (28 - ImGui.getFontSize()) * .5f - 1;
        HudWidgets.glyph(ImGui.getWindowDrawList(), PENCIL, barX + x, barY + y + 5, 18, ACCENT);
        ImGui.setCursorPos(nameX, textY);
        ImGui.textColored(TEXT, "Edit");
        var clientX = nameX + ImGui.calcTextSizeX("Edit ");
        var room = fpsX - 8 - clientX;
        if (room >= 48) {
            ImGui.setCursorPos(clientX, textY);
            ImGui.textColored(MUTED, HudWidgets.truncateToWidth("· " + clientName(), room));
        }
        tooltip("Edit · the maps of " + client
                + ". The menu at the end of the bar goes back to the Viewer or the main menu.");
    }

    private void viewControlsPopup(float right, float bottom, boolean editing) {
        var width = 2 * VIEW_POPUP_PADDING
                + (editing ? 0 : HudWidgets.CONTROL_HEIGHT + 2 * GeodataWidgets.ROW + 1)
                + VIEW_SEGMENT_WIDTH * CameraView.values().length;
        var height = 2 * VIEW_POPUP_PADDING + 2 * 26 + GeodataWidgets.ROW;
        ImGui.setNextWindowPos(Math.max(MARGIN, right - width), bottom + GeodataWidgets.ROW);
        ImGui.setNextWindowSize(width, height);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.PopupRounding, HudWidgets.PANEL_RADIUS);
        var open =
                ImGui.beginPopup("##viewControls", ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.popStyleVar(2);
        if (open) {
            drawViewControls(
                    VIEW_POPUP_PADDING, VIEW_POPUP_PADDING - (HudWidgets.BAR_HEIGHT - 26) * .5f, true, editing);
            ImGui.endPopup();
        }
    }

    private void drawViewControls(float x, float top, boolean twoRows, boolean editing) {
        var views = CameraView.values();
        var modes = ViewMode.values();
        var viewsWidth = VIEW_SEGMENT_WIDTH * views.length;
        var control = HudWidgets.CONTROL_HEIGHT;
        var rowHeight = HudWidgets.BAR_HEIGHT;
        var height = twoRows ? rowHeight + VIEW_SECOND_ROW : rowHeight;
        var segmentsY = top + (rowHeight - 26) * .5f;
        var windowX = ImGui.getWindowPosX();
        var windowY = ImGui.getWindowPosY();
        var draw = ImGui.getWindowDrawList();
        var playback = scene.playback;
        var camera = scene.camera;
        var viewsX = x;
        ImGui.beginDisabled(playback.isComparisonLocked());
        if (!editing) {
            var paused = playback.isPaused();
            ImGui.setCursorPos(x, top + (height - control) * .5f);
            if (HudWidgets.button("viewBar/play", paused ? PLAY : PAUSE, "", control, control, paused, false)) {
                playback.setPaused(!paused);
            }
            var playTip = paused
                    ? "Play the scene's time: effects, the sky and water move again."
                    : "Pause the scene's time: effects, the sky and water stop. While nothing moves, the view is drawn"
                            + " about once a second.";
            GeodataWidgets.tip(playTip, "P");
            var dividerX = x + control + GeodataWidgets.ROW;
            draw.addLine(
                    windowX + dividerX,
                    windowY + top + 11,
                    windowX + dividerX,
                    windowY + top + height - 11,
                    HudWidgets.BORDER,
                    1);
            viewsX = dividerX + 1 + GeodataWidgets.ROW;
        }
        var viewLabels = new String[views.length];
        for (var index = 0; index < views.length; index++) {
            viewLabels[index] = views[index].getLabel();
        }
        ImGui.setCursorPos(viewsX, segmentsY);
        var chosenView = GeodataWidgets.segmented(
                "viewBar/view", viewLabels, camera.getView().ordinal(), viewsWidth);
        if (chosenView >= 0) {
            camera.setView(views[chosenView]);
        }
        if (ImGui.isItemHovered()) {
            var hovered = views[GeodataWidgets.hoveredSegment(windowX + viewsX, viewsWidth, views.length)];
            GeodataWidgets.tip(viewTip(hovered), hovered == CameraView.TOP ? "O" : null);
        }
        ImGui.endDisabled();
        var modesDividerX = viewsX + viewsWidth + GeodataWidgets.ROW;
        var modesX = twoRows ? viewsX : modesDividerX + 1 + GeodataWidgets.ROW;
        var modesWidth = twoRows ? viewsWidth : VIEW_SEGMENT_WIDTH * modes.length;
        if (!twoRows) {
            draw.addLine(
                    windowX + modesDividerX,
                    windowY + top + 11,
                    windowX + modesDividerX,
                    windowY + top + rowHeight - 11,
                    HudWidgets.BORDER,
                    1);
        }
        var modeLabels = new String[modes.length];
        for (var index = 0; index < modes.length; index++) {
            modeLabels[index] = modes[index].getLabel();
        }
        ImGui.setCursorPos(modesX, twoRows ? segmentsY + VIEW_SECOND_ROW : segmentsY);
        var chosenMode = GeodataWidgets.segmented(
                "viewBar/mode", modeLabels, scene.getViewMode().ordinal(), modesWidth);
        if (chosenMode >= 0) {
            scene.setViewMode(modes[chosenMode]);
        }
        if (ImGui.isItemHovered()) {
            GeodataWidgets.tip(viewModeTip(
                    modes[GeodataWidgets.hoveredSegment(windowX + modesX, modesWidth, modes.length)],
                    camera.getView()));
        }
    }

    private void effectsBar() {
        var barX = 12f;
        var barY = 12f;
        var barWidth = Math.min(getViewportRight() - barX, EFFECTS_TOOLBAR_WIDTH);
        var fpsText = ui.rendering.stats.fps + " FPS";
        var fpsWidth = Math.max(54, ImGui.calcTextSizeX(fpsText) + 12);
        var fpsX = barWidth - 8 - GUIDE_END - fpsWidth;
        topHeight = barY + HudWidgets.BAR_HEIGHT + MARGIN;
        beginPanel("##workspaceCommands", barX, barY, barWidth, HudWidgets.BAR_HEIGHT, true, HudWidgets.PANEL_RADIUS);
        var y = (HudWidgets.BAR_HEIGHT - 28) * .5f;
        branding.mark(ImGui.getWindowDrawList(), barX + 10, barY + y, 28);
        var showTitle = barWidth >= 260;
        if (showTitle) {
            ImGui.setCursorPos(42, y + 3);
            heading("Cruma", 20);
        }
        var labelX = showTitle ? 112f : 42f;
        var textY = y + (28 - ImGui.getFontSize()) * .5f - 1;
        var nameX = labelX + 26;
        if (fpsX - 8 - nameX >= ImGui.calcTextSizeX("Effects")) {
            HudWidgets.glyph(ImGui.getWindowDrawList(), Glyph.SPARKLES, barX + labelX, barY + y + 5, 18, ACCENT);
            ImGui.setCursorPos(nameX, textY);
            ImGui.textColored(TEXT, "Effects");
            var clientX = nameX + ImGui.calcTextSizeX("Effects ");
            var room = fpsX - 8 - clientX;
            if (room >= 48) {
                ImGui.setCursorPos(clientX, textY);
                ImGui.textColored(MUTED, HudWidgets.truncateToWidth("· " + clientName(), room));
            }
            tooltip("Effects mode · the client's effects and skills with no world loaded · " + client);
        }
        drawFpsButton(fpsText, fpsX, fpsWidth, y, barY + HudWidgets.BAR_HEIGHT);
        ImGui.setCursorPos(barWidth - GUIDE_END, y);
        drawGuideButton();
        ImGui.setCursorPos(barWidth - MENU_END, y);
        viewerMenu(barX + barWidth, barY + HudWidgets.BAR_HEIGHT);
        ImGui.end();
    }

    private String clientName() {
        var name = Path.of(client).getFileName();
        return name == null ? client : name.toString();
    }

    private void drawFpsButton(String text, float x, float width, float y, float bottom) {
        ImGui.setCursorPos(x, y);
        frameLimit.drawButton(text, HudWidgets.fpsColor(ui.rendering.stats.fps), width, ui.rendering.frameRateCap);
        frameLimit.draw(ui.rendering, ImGui.getWindowPosX() + x + width, bottom);
    }

    private void viewerMenu(float right, float bottom) {
        if (HudWidgets.button("viewerMenu", MENU, "", 28, 28, false, false)) {
            ImGui.openPopup("##viewerMenu");
        }
        tooltip("Viewer menu");
        if (HudWidgets.beginMenu("##viewerMenu", 248, right, bottom)) {
            if (HudWidgets.menuRow("guide", Glyph.BOOK_OPEN, "Guide", "Shift+F1")) {
                GuideRequests.toggleTopic();
            }
            tooltip("What the tools are for and how to use them, with pictures and a search.");
            if (HudWidgets.menuRow("controls", Glyph.KEYBOARD, "Controls & shortcuts", "F1")) {
                helpOpen = !helpOpen;
                if (helpOpen) {
                    closeGuide();
                }
                ui.worldMapOpen = false;
            }
            if (isSettingsOffered()) {
                if (HudWidgets.menuRow("settings", Glyph.SETTINGS, "Settings…", "")) {
                    settingsWanted = true;
                    ui.worldMapOpen = false;
                }
                tooltip("Graphics, sound, folders and the settings of this client. Load the world again to apply"
                        + " them.");
            }
            HudWidgets.menuRule();
            var editing = layout.mode == Mode.EDITING;
            if (!effectsOnly && !editing) {
                if (HudWidgets.menuRow(
                        "exportHere", FILE, namedMap != null ? "Export this map…" : "Export here…", "")) {
                    ui.exportHereRequested = true;
                }
                tooltip(
                        namedMap != null
                                ? "Save the whole map " + namedMap + " as glTF, for Blender and other 3D programs."
                                : "Save a square around the camera as glTF, for Blender and other 3D programs.");
            }
            if (editing) {
                if (HudWidgets.menuRow("openViewer", EYE, "Back to Viewer", "")) {
                    setMode(Mode.EXPLORE);
                }
                tooltip("Leave Edit and look at this place in the Viewer. The tiles load again, and unsaved changes"
                        + " ask first.");
            } else {
                if (HudWidgets.menuRow("exportNpc", CARD, "Export NPC…", "")) {
                    ui.exportNpcRequested = true;
                }
                tooltip("Save an NPC or a player character with its skeleton and animations.");
            }
            HudWidgets.menuRule();
            if (HudWidgets.menuRow("mainMenu", LOGO, "Main menu", "")) {
                ui.returnToMenuRequested = !guardReturnToMenu();
            }
            HudWidgets.endMenu();
        }
    }

    private String tileName() {
        var p = scene.camera.getWorldFocusPosition();
        return String.format(
                Locale.ROOT,
                "%02d_%02d",
                (int) Math.floor(p.getX() / 32768f) + 20,
                (int) Math.floor(p.getY() / 32768f) + 18);
    }

    private void searchField(String id, float fieldWidth, String hint) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 30, 7);
        ImGui.setNextItemWidth(fieldWidth);
        var command = id.equals("##objectSearch");
        var library = id.equals("##librarySearch");
        var field = command ? commandQuery : library ? librarySearch : query;
        if (ImGui.inputTextWithHint(id, hint, field) && !library) {
            if (command) {
                query.set(commandQuery.get());
            }
            if (layout.mode != Mode.EDITING) {
                setMode(Mode.ANALYZE);
            }
            rowsDirty = true;
        }
        ImGui.popStyleVar();
        HudWidgets.glyph(ImGui.getWindowDrawList(), SEARCH, x + 9, y + 7, 15, MUTED);
    }

    private void drawInspector() {
        beginToolWindow("##objectInspector", "Object");
        var inner = ImGui.getContentRegionAvailX();
        var view = inspectedView;
        var header = view == null ? null : view.getInspectedHeader();
        if (header != null) {
            ImGui.pushFont(fonts.getHeadingFont(19), 19f);
            ImGui.textUnformatted(elide(header.title(), inner));
            ImGui.popFont();
            ImGui.pushFont(fonts.getTextFont(12), 12f);
            ImGui.textColored(MUTED, elide(header.identity(), inner));
            tooltip(header.identity());
            ImGui.textColored(MUTED, header.type());
            ImGui.popFont();
            gap(10);
            ImGui.beginChild("##inspectedView", 0, 0, false);
            view.drawInspectedDetails();
            ImGui.endChild();
            ImGui.end();
            return;
        }
        var selected = scene.inspection.selected;
        if (selected == null) {
            ImGui.textColored(MUTED, "Click an object to inspect it.");
            gap(12);
            if (HudWidgets.button("browse", SEARCH, "Browse objects", -1, 30, true, false)) {
                setMode(Mode.ANALYZE);
            }
            ImGui.end();
            return;
        }
        title(selected);
        gap(8);
        drawSelectionActions(selected);
        gap(8);
        var animated = selected.hasAnimation();
        if (!animated && layout.tab == Tab.ANIMATION) {
            layout.tab = Tab.MATERIAL;
        }
        for (var tab : Tab.values()) {
            if (tab.ordinal() > 0) {
                ImGui.sameLine(0, 0);
            }
            var disabled = tab == Tab.ANIMATION && !animated;
            ImGui.beginDisabled(disabled);
            if (HudWidgets.tab(capitalize(tab.name()), layout.tab == tab, inner / 3, 32)) {
                layout.tab = tab;
            }
            if (disabled && ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
                ImGui.setTooltip("No animation in this object or its materials.");
            }
            ImGui.endDisabled();
        }
        gap(10);
        var overflow = propertyOverflow.getOrDefault(layout.tab, false);
        ImGui.pushStyleVar(ImGuiStyleVar.ScrollbarSize, 6);
        ImGui.pushStyleVar(ImGuiStyleVar.ScrollbarRounding, 3);
        ImGui.pushStyleColor(ImGuiCol.ScrollbarBg, 0);
        ImGui.pushStyleColor(ImGuiCol.ScrollbarGrab, overflow ? ImColor.rgba(112, 139, 157, 110) : 0);
        ImGui.pushStyleColor(ImGuiCol.ScrollbarGrabHovered, overflow ? ImColor.rgba(112, 139, 157, 190) : 0);
        ImGui.pushStyleColor(ImGuiCol.ScrollbarGrabActive, overflow ? ACCENT : 0);
        ImGui.beginChild("##propertyScroll" + layout.tab.name(), 0, 0, false, ImGuiWindowFlags.AlwaysVerticalScrollbar);
        switch (layout.tab) {
            case MATERIAL -> drawMaterial(selected);
            case ANIMATION -> drawAnimation(selected);
            case SOURCE -> drawSource(selected);
        }
        propertyOverflow.put(layout.tab, ImGui.getScrollMaxY() > 0);
        ImGui.endChild();
        ImGui.popStyleColor(4);
        ImGui.popStyleVar(2);
        ImGui.end();
    }

    private void title(DebugSelection selected) {
        ImGui.pushFont(fonts.getHeadingFont(19), 19f);
        ImGui.textUnformatted(elide(selected.shortName(), ImGui.getContentRegionAvailX()));
        ImGui.popFont();
        tooltip(selected.assetName());
        ImGui.pushFont(fonts.getTextFont(12), 12f);
        var identity = selected.owner() + " / " + DebugSelection.shortName(selected.actor());
        ImGui.textColored(MUTED, elide(identity, ImGui.getContentRegionAvailX()));
        tooltip(selected.fullName());
        ImGui.textColored(MUTED, selected.typeName());
        ImGui.popFont();
    }

    private void drawSelectionActions(DebugSelection selected) {
        var w = (ImGui.getContentRegionAvailX() - 12) * .5f;
        ImGui.beginDisabled(scene.playback.isComparisonLocked());
        if (HudWidgets.button("focus", FOCUS, "Focus", w, 30, true, false)) {
            focus(selected);
        }
        tooltip(
                scene.playback.isComparisonLocked()
                        ? "Exit A/B comparison to move the camera."
                        : "Frame the selected object.");
        ImGui.endDisabled();
        ImGui.sameLine(0, 12);
        if (HudWidgets.button(
                "isolate", EYE, scene.inspection.isIsolated() ? "Show all" : "Isolate", -1, 30, false, true)) {
            scene.inspection.setIsolated(!scene.inspection.isIsolated());
        }
        tooltip("Temporarily hide other objects. Their simulation and source data are preserved.");
        if (scene.inspection.isIsolated()) {
            gap(8);
            IsolateControls.draw(scene.inspection, ImGui.getContentRegionAvailX());
        }
        if (selected.emitter() != null) {
            gap(8);
            var hidden = scene.inspection.isEffectHidden(selected.emitter());
            ImGui.beginDisabled(scene.playback.isComparisonLocked());
            if (HudWidgets.button("hideEffect", EYE, hidden ? "Show effect" : "Hide effect", -1, 30, false, true)) {
                scene.inspection.setEffectHidden(selected.emitter(), !hidden);
            }
            tooltip(
                    scene.playback.isComparisonLocked()
                            ? "Exit A/B comparison to change effect visibility."
                            : "Hide or show all layers of this emitter for the current viewing session.");
            ImGui.endDisabled();
        }
    }

    private void focus(DebugSelection selected) {
        var bounds = selected.emitter() == null ? null : backend.getDebugMeshEmitterBounds(selected.emitter());
        if (layout.mode == Mode.EDITING && selected.sceneObject() != null) {
            var objects = backend.getEditorActorObjects(Set.of(selected.actor()));
            if (!objects.isEmpty()) {
                bounds = objects.getFirst().bounds();
            }
        }
        focusBox(bounds == null ? selected.bounds() : bounds);
    }

    private void focusBox(Bounds3f box) {
        scene.camera.focus(
                box,
                (getViewportLeft() + getViewportRight()) / width - 1f,
                1f - (topHeight + getViewportBottom()) / height,
                (getViewportRight() - getViewportLeft()) / width,
                (getViewportBottom() - topHeight) / height);
    }

    private void drawMaterial(DebugSelection selected) {
        for (var slot : selected.materials()) {
            ImGui.pushID(slot.slot());
            drawMaterialCard(slot.name(), slot.slot());
            ImGui.popID();
        }
        if (selected.materials().isEmpty()) {
            drawMaterialCard(selected.materialName(), -1);
        }
        gap(12);
        rule();
        gap(12);
        sectionLabel("Object colour");
        ImGui.sameLine(ImGui.getWindowContentRegionMaxX() - 25);
        if (HudWidgets.button("colourDetails", HELP, "", 22, 22, false, false)) {
            layout.tab = Tab.SOURCE;
            layout.materialDetails = true;
        }
        tooltip("Material and colour source details");
        gap(8);
        ImGui.textColored(
                MUTED,
                selected.emitter() != null && selected.emitter().isUseParticleColor()
                        ? "Material × particle colour"
                        : "From material");
        gap(16);
        rule();
        gap(12);
        sectionLabel("Quick actions");
        gap(8);
        if (HudWidgets.listButton(
                "showBounds",
                BOX,
                scene.inspection.showBounds ? "Hide bounds" : "Show bounds",
                -1,
                30,
                scene.inspection.showBounds)) {
            scene.inspection.showBounds = !scene.inspection.showBounds;
        }
        if (HudWidgets.listButton("copyAsset", COPY, "Copy path", -1, 30, false)) {
            ImGui.setClipboardText(selected.assetName());
            notify("Asset path copied");
        }
    }

    private void drawMaterialDetails(DebugSelection selected) {
        for (var slot : selected.materials()) {
            drawDetail("Material " + slot.slot(), slot.name());
            drawDetail("Blend", slot.blend());
            drawDetail("Compiled material", slot.materialType());
            drawDetail("UV animation", slot.uvAnimation());
        }
        if (selected.emitter() != null) {
            drawDetail(
                    "Blend source",
                    selected.emitter().isUseMeshBlendMode() ? "Mesh materials" : "Emitter DrawStyle override");
        }
        if (selected.lighting() != null) {
            drawDetail("Authored lighting", selected.lighting());
        }
    }

    private void drawMaterialCard(String name, int slot) {
        sectionLabel(slot >= 0 ? "Material · slot " + slot : "Material");
        gap(6);
        var w = ImGui.getContentRegionAvailX();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var localX = ImGui.getCursorPosX();
        var localY = ImGui.getCursorPosY();
        var draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + w, y + 32, ImColor.rgb(27, 37, 46), 5f);
        draw.addText(x + 9, y + 8, TEXT, elide(DebugSelection.shortName(name), w - 44));
        ImGui.setCursorPos(localX + w - 30, localY + 2);
        copyButton("copyMaterial", name, name + ". Click to copy full path");
        ImGui.setCursorPos(localX, localY + 36);
    }

    private void drawAnimation(DebugSelection selected) {
        var emitter = selected.emitter();
        if (emitter == null) {
            drawDetail("Actor animation", selected.sceneObject().animation());
        } else {
            if (emitter.getAnimationFrames() > 0) {
                valueRow("Frames", Integer.toString(emitter.getAnimationFrames()));
                var rate = emitter.getAnimationFramesPerSecond();
                valueRow("Rate", String.format(Locale.ROOT, rate == Math.rint(rate) ? "%.0f fps" : "%.3f fps", rate));
                valueRow("Speed", String.format(Locale.ROOT, "%sx", speedLabel()));
            } else {
                valueRow("Simulation", capitalize(emitter.getKind().name()));
            }
        }
        gap(16);
        rule();
        gap(18);
        drawMaterialCard(selected.materialName(), -1);
        gap(18);
        if (HudWidgets.button("animationDetails", CHEVRON, "Animation details", -1, 32, false, false)) {
            animationDetails = !animationDetails;
        }
        if (animationDetails) {
            if (emitter != null) {
                int active = 0;
                for (var i = 0; i < emitter.getMaxActive(); i++) {
                    if (emitter.isActive(i)) {
                        active++;
                    }
                }
                valueRow("Live particles", Integer.toString(active));
            }
            valueRow("Scene time", String.format(Locale.ROOT, "%.3f s", scene.playback.getPreciseSeconds()));
            ImGui.textWrapped("Playback affects particles, animated meshes and materials throughout the scene.");
            drawPlaybackControls();
        }
    }

    private void drawSource(DebugSelection selected) {
        drawDetail("Tile / owner", selected.owner());
        drawDetail("Actor", selected.actor());
        if (selected.emitter() != null) {
            drawDetail("Child", selected.child());
            drawDetail("Child array index", Integer.toString(selected.emitter().getRenderChildOrder()));
        }
        drawDetail("Asset", selected.assetName());
        if (selected.surfaceOrdinal() >= 0) {
            drawDetail("Surface", Integer.toString(selected.surfaceOrdinal()));
        }
        if (selected.instanceOrdinal() >= 0) {
            drawDetail("Instance", Integer.toString(selected.instanceOrdinal()));
        }
        drawDetail("Client", client);
        if (ImGui.checkbox("Material details", layout.materialDetails)) {
            layout.materialDetails = !layout.materialDetails;
        }
        if (layout.materialDetails) {
            drawMaterialDetails(selected);
        }
        if (HudWidgets.listButton("copyContext", COPY, "Copy diagnostic context", -1, 32, false)) {
            ImGui.setClipboardText(getDiagnosticContext());
            notify("Diagnostic context copied");
        }
        ImGui.spacing();
        if (ImGui.button("Clear selection", -1, 28)) {
            scene.inspection.select(null);
        }
    }

    private void compactCard() {
        var selected = scene.inspection.selected;
        if (selected == null) {
            return;
        }
        var cardWidth = Math.min(304, width - 24);
        beginPanel(
                "##objectCard", MARGIN, compactPanelY(cardWidth, 108), cardWidth, 108, true, HudWidgets.PANEL_RADIUS);
        if (!selected.fullName().equals(cardShown)) {
            cardShown = selected.fullName();
            Motion.restart(CARD_SWAP);
        }
        var rise = Motion.appear(CARD_SHOWN, Motion.OPEN);
        var swap = Motion.easeOut(Motion.appear(CARD_SWAP, Motion.SWAP));
        Motion.fade(
                Math.clamp(rise * 1.8f, 0, 1),
                rise < 1 ? 1 : swap,
                0,
                20 * (1 - Motion.easeOut(rise)),
                1,
                rise < 1 ? 0 : 6 * (1 - swap));
        ImGui.pushFont(fonts.getHeadingFont(17), 17f);
        ImGui.textUnformatted(elide(selected.shortName(), cardWidth - 60));
        ImGui.popFont();
        tooltip(selected.assetName());
        ImGui.setCursorPos(cardWidth - 34, 8);
        if (HudWidgets.button("clearSelection", CLOSE, "", 24, 24, false, false)) {
            scene.inspection.select(null);
        }
        tooltip("Clear selection · Esc");
        ImGui.setCursorPos(14, 36);
        ImGui.pushFont(fonts.getTextFont(12), 12f);
        ImGui.textColored(
                MUTED,
                elide(
                        selected.owner()
                                + " / "
                                + DebugSelection.shortName(selected.actor())
                                + " · "
                                + selected.typeName().replace("Emitter", ""),
                        cardWidth - 28));
        ImGui.popFont();
        tooltip(selected.fullName());
        ImGui.setCursorPos(14, 64);
        ImGui.beginDisabled(scene.playback.isComparisonLocked());
        if (HudWidgets.button("cardFocus", FOCUS, "Focus", 96, 28, false, false)) {
            focus(selected);
        }
        ImGui.endDisabled();
        ImGui.sameLine(0, 8);
        if (HudWidgets.button("openInspector", SEARCH, "Inspect", 108, 28, true, false)) {
            setMode(Mode.INSPECT);
        }
        ImGui.end();
    }

    private void drawObjectTreeOrTab() {
        if (isTreeShown()) {
            drawObjectTree();
            return;
        }
        beginPanel("##objectTreeTab", MARGIN, topHeight, TREE_TAB, TREE_TAB, true, HudWidgets.PANEL_RADIUS);
        ImGui.setCursorPos((TREE_TAB - 28) * .5f, (TREE_TAB - 28) * .5f);
        var name = layout.mode == Mode.EDITING ? "Scene" : "Objects";
        if (HudWidgets.button("openObjectTree", Glyph.LAYERS, "", 28, 28, false, false)) {
            layout.treeFolded = false;
        }
        tooltip("Show the " + name + " panel.");
        ImGui.end();
    }

    private void drawObjectTree() {
        var editing = layout.mode == Mode.EDITING;
        if (editing) {
            catalog.refresh(
                    backend, scene.particles, true, scene.getEditingHiddenObjects(), scene.getEditingHiddenRevision());
            if (scene.getEditing().getRevision() != treeFilterRevision) {
                treeFilterRevision = scene.getEditing().getRevision();
                rowsDirty |= treeFilter != TreeFilter.ALL
                        || !scene.getEditing().isGestureActive()
                                && !scene.getEditing().getGroupNames().isEmpty();
            }
            if (scene.getEditing().getGroupsRevision() != groupRowsRevision) {
                groupRowsRevision = scene.getEditing().getGroupsRevision();
                rowsDirty = true;
            }
        } else {
            catalog.refresh(backend, scene.particles, false);
            if (treeFilter == TreeFilter.CHANGED) {
                treeFilter = TreeFilter.ALL;
                rowsDirty = true;
            }
        }
        var selected = scene.inspection.selected;
        if (selected != null && !selected.key().equals(revealedKey)) {
            expanded.add(selected.owner());
            if (!editing) {
                expanded.add(selected.owner() + "/" + selected.actor());
            }
            revealedKey = selected.key();
            rowsDirty = true;
        }
        if (rowsDirty || catalog.getRevision() != rowRevision || !query.get().equals(rowQuery)) {
            rebuildRows();
        }
        beginPanel(
                "##objectTree",
                MARGIN,
                topHeight,
                leftWidth,
                sidePanelBottom() - topHeight,
                false,
                HudWidgets.PANEL_RADIUS);
        Motion.open(Motion.appear(TREE_SHOWN, Motion.OPEN), -32, 0, 1);
        drawTreeFold(editing ? "Scene" : "Objects");
        if (editing) {
            heading("Scene", 19);
            gap(12);
            var tab = GeodataWidgets.segmented("sceneTabs", new String[] {"Objects", "Library"}, libraryTab ? 1 : 0);
            if (tab >= 0) {
                libraryTab = tab == 1;
            }
            var changed = changedObjects();
            if (changed > 0) {
                var left = ImGui.getItemRectMinX();
                var top = ImGui.getItemRectMinY();
                var segment = (ImGui.getItemRectMaxX() - left) / 2;
                var textTop = top + (ImGui.getItemRectMaxY() - top - ImGui.getFontSize()) * .5f - 1;
                ImGui.getWindowDrawList()
                        .addCircleFilled(
                                left + (segment + ImGui.calcTextSizeX("Objects")) * .5f + 7,
                                HudWidgets.textMiddle(textTop),
                                3.5f,
                                GeodataWidgets.WARNING);
            }
            tooltip((changed > 0
                            ? EditorUi.plural(changed, "object differs", "objects differ") + " from the client. "
                            : "")
                    + "Objects lists what the loaded maps hold. Library lists the client's meshes and"
                    + " materials to place and assign.");
            gap(12);
            if (libraryTab) {
                searchField("##librarySearch", ImGui.getContentRegionAvailX(), "Search meshes");
                gap(12);
                editor().drawLibrary(librarySearch.get());
                ImGui.end();
                return;
            }
        } else {
            heading("Objects", 19);
            gap(12);
        }
        searchField("##treeSearch", ImGui.getContentRegionAvailX(), "Search in scene");
        gap(12);
        drawTreeChips(editing);
        gap(12);
        ImGui.beginChild("##objectRows", 0, 0, false);
        var rowHeight = 28f;
        clipper.begin(rows.size(), rowHeight);
        while (clipper.step()) {
            for (var i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
                var row = rows.get(i);
                if (row.kind() != RowKind.OBJECT) {
                    drawGroupRow(row, rowHeight);
                    continue;
                }
                var picked = editing
                        && row.pick() != null
                        && scene.getEditing().getSelection().contains(row.pick().actor());
                var isSelected = picked
                        || row.selection() != null
                                && (editing
                                        ? scene.getEditing()
                                                .getSelection()
                                                .contains(row.selection().actor())
                                        : selected != null
                                                && row.selection().key().equals(selected.key()));
                var x = ImGui.getCursorScreenPosX();
                var y = ImGui.getCursorScreenPosY();
                var w = ImGui.getContentRegionAvailX();
                var draw = ImGui.getWindowDrawList();
                var actorPath = editing && row.depth() == 1
                        ? row.id().substring(row.id().indexOf('/') + 1)
                        : null;
                var refusal = editing ? refusal(row, actorPath) : null;
                treeMarkHovered = false;
                var marks =
                        actorPath == null || refusal != null ? 0 : drawActorMarks(draw, actorPath, x, y, w, rowHeight);
                var overMark = treeMarkHovered;
                ImGui.setCursorScreenPos(x, y);
                var inset = row.depth() * 15;
                var effectRow = row.depth() == 1
                        && catalog.isEffect(row.id().substring(row.id().indexOf('/') + 1));
                var overChevron = !(editing && effectRow)
                        && (row.pick() != null || editing && row.depth() == 1)
                        && ImGui.getMousePosX() < x + inset + 20;
                if (ImGui.selectable("##" + row.id(), isSelected, 0, w, rowHeight - 4) && !overMark) {
                    if (refusal != null && !overChevron && row.pick() == null) {
                        editor().showError("");
                        editor().showNote(refusal);
                    } else if (row.pick() != null && !overChevron) {
                        select(
                                row.pick(),
                                ImGui.getIO().getKeyCtrl() || ImGui.getIO().getKeySuper());
                        commandQuery.set("");
                    } else if (row.selection() != null) {
                        select(
                                row.selection(),
                                ImGui.getIO().getKeyCtrl() || ImGui.getIO().getKeySuper());
                        commandQuery.set("");
                    } else {
                        if (!expanded.remove(row.id())) {
                            expanded.add(row.id());
                        }
                        rowsDirty = true;
                    }
                }
                if (editing && ImGui.isItemHovered() && editingViewport != null) {
                    var hoveredActor = row.selection() != null ? row.selection().actor() : actorPath;
                    if (hoveredActor != null) {
                        editingViewport.highlight(hoveredActor);
                    }
                }
                var hiddenRow = editing
                        && row.depth() > 0
                        && (refusal != null
                                || scene.getEditing().getHiddenActors().contains(rowActor(row)));
                if (isSelected) {
                    draw.addRectFilled(x + 2, y, x + 5, y + rowHeight - 1, ACCENT, 1f);
                }
                if (row.depth() > 0) {
                    draw.addLine(x + inset - 6, y, x + inset - 6, y + rowHeight, HudWidgets.BORDER, 1);
                }
                if (row.selection() == null) {
                    var open = expanded.contains(row.id()) || !query.get().isBlank() && !(editing && row.depth() == 1);
                    if (editing && effectRow) {
                    } else if (open) {
                        HudWidgets.glyph(draw, CHEVRON, x + inset, y + 5, 17, MUTED);
                    } else {
                        draw.addLine(x + inset + 5, y + 8, x + inset + 10, y + 14, MUTED, 1.4f);
                        draw.addLine(x + inset + 10, y + 14, x + inset + 5, y + 20, MUTED, 1.4f);
                    }
                    if (row.depth() > 0) {
                        HudWidgets.glyph(draw, effectRow ? Glyph.SPARKLES : BOX, x + inset + 22, y + 5, 18, MUTED);
                    }
                } else {
                    HudWidgets.glyph(draw, FILE, x + inset + 8, y + 5, 18, TEXT);
                }
                var textX = x + inset + (row.depth() == 0 ? 26 : 48);
                var room = x + w - textX - 4 - marks;
                if (row.pick() != null) {
                    var label = elide(row.label(), room);
                    draw.addText(textX, y + 6, hiddenRow ? GeodataWidgets.FAINT : TEXT, label);
                    var after = textX + ImGui.calcTextSizeX(label);
                    var detail = label.equals(row.label())
                            ? EditorUi.rowDetail(row.detail(), textX + room - after, ImGui::calcTextSizeX)
                            : "";
                    if (!detail.isEmpty()) {
                        draw.addText(after, y + 6, hiddenRow ? GeodataWidgets.FAINT : MUTED, detail);
                    }
                } else {
                    draw.addText(
                            textX,
                            y + 6,
                            hiddenRow ? GeodataWidgets.FAINT : isSelected ? TEXT : MUTED,
                            elide(row.label(), room));
                }
                if (!overMark) {
                    var name = row.pick() != null
                            ? row.label()
                                    + (row.detail().isEmpty() ? "" : " · " + row.detail())
                                    + "\n"
                                    + row.pick().fullName()
                            : row.selection() == null
                                    ? row.label()
                                    : row.selection().fullName();
                    tooltip(
                            overChevron
                                    ? (expanded.contains(row.id()) ? "Fold the surfaces of " : "Show the surfaces of ")
                                            + row.label()
                                    : refusal != null ? name + "\n" + refusal : name);
                }
            }
        }
        clipper.end();
        if (editing) {
            drawGroupMenu();
        }
        if (rows.isEmpty() && catalog.isReading()) {
            gap(8);
            ImGui.textColored(GeodataWidgets.FAINT, "Reading the objects of the scene.");
        } else if (rows.isEmpty() && treeFilter != TreeFilter.ALL) {
            gap(8);
            ImGui.textColored(
                    GeodataWidgets.FAINT,
                    treeFilter == TreeFilter.SELECTED ? "Nothing is selected." : "No object has changed yet.");
        }
        ImGui.endChild();
        ImGui.end();
    }

    private void drawGroupRow(TreeRow row, float rowHeight) {
        var session = scene.getEditing();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var w = ImGui.getContentRegionAvailX();
        var draw = ImGui.getWindowDrawList();
        if (row.kind() == RowKind.GROUPS) {
            ImGui.dummy(w, rowHeight - 4);
            draw.addText(x + 4, y + 6, MUTED, row.label());
            return;
        }
        var name = Objects.requireNonNull(row.group());
        var whole = row.kind() == RowKind.GROUP;
        var members = session.getSelectableMembers(name);
        var actor = whole ? null : row.id().substring(row.id().lastIndexOf('/') + 1);
        var isSelected = whole
                ? !members.isEmpty() && session.getSelection().equals(members)
                : session.getSelection().contains(actor);
        var inset = row.depth() * 15;
        var overChevron = whole && ImGui.getMousePosX() < x + inset + 20;
        var open = expanded.contains(row.id()) || !query.get().isBlank();
        if (ImGui.selectable("##" + row.id(), isSelected, ImGuiSelectableFlags.AllowDoubleClick, w, rowHeight - 4)) {
            if (whole && (overChevron || ImGui.isMouseDoubleClicked(0))) {
                if (!expanded.remove(row.id())) {
                    expanded.add(row.id());
                }
                rowsDirty = true;
            } else if (whole) {
                var additive = ImGui.getIO().getKeyCtrl() || ImGui.getIO().getKeySuper();
                runEditing(() -> session.selectGroup(name, additive));
            } else {
                runEditing(() -> session.selectInGroup(name, actor));
            }
            commandQuery.set("");
        }
        var hovered = ImGui.isItemHovered();
        if (whole && ImGui.isItemClicked(1)) {
            groupMenu = name;
            groupMenuRight = x + w;
            groupMenuBottom = y + rowHeight - 4;
            ImGui.openPopup(GROUP_MENU);
        }
        if (hovered && actor != null && editingViewport != null) {
            editingViewport.highlight(actor);
        }
        if (isSelected) {
            draw.addRectFilled(x + 2, y, x + 5, y + rowHeight - 1, ACCENT, 1f);
        }
        float textX;
        if (whole) {
            if (open) {
                HudWidgets.glyph(draw, CHEVRON, x + inset, y + 5, 17, MUTED);
            } else {
                HudWidgets.glyph(draw, Glyph.CHEVRON_RIGHT, x + inset, y + 5, 17, MUTED);
            }
            HudWidgets.glyph(draw, Glyph.GROUP, x + inset + 22, y + 5, 18, MUTED);
            textX = x + inset + 48;
        } else {
            draw.addLine(x + inset - 6, y, x + inset - 6, y + rowHeight, HudWidgets.BORDER, 1);
            HudWidgets.glyph(draw, BOX, x + inset + 8, y + 5, 18, MUTED);
            textX = x + inset + 32;
        }
        var room = x + w - textX - 4;
        var label = elide(row.label(), room);
        draw.addText(textX, y + 6, TEXT, label);
        var after = textX + ImGui.calcTextSizeX(label);
        var detail = label.equals(row.label())
                ? EditorUi.rowDetail(row.detail(), textX + room - after, ImGui::calcTextSizeX)
                : "";
        if (!detail.isEmpty()) {
            draw.addText(after, y + 6, MUTED, detail);
        }
        if (hovered) {
            HudWidgets.showTooltip(
                    whole
                            ? (overChevron
                                    ? (open ? "Fold the objects of " : "Show the objects of ") + name
                                    : name + " · " + row.detail()
                                            + "\n"
                                            + "A click selects them all, a double click shows them, and a right click"
                                            + " renames or ungroups the group.")
                            : row.label()
                                    + " · "
                                    + row.detail()
                                    + " in "
                                    + name
                                    + "\n"
                                    + "A click selects it alone, inside its group. Esc selects the whole group"
                                    + " again.");
        }
    }

    private void drawGroupMenu() {
        if (!ImGui.isPopupOpen(GROUP_MENU) || groupMenu == null) {
            return;
        }
        var name = groupMenu;
        var width = 220f;
        if (!HudWidgets.beginMenuAt(
                GROUP_MENU,
                width,
                HudWidgets.menuHeight(2, 0),
                groupMenuRight - width,
                groupMenuBottom + GeodataWidgets.ROW)) {
            return;
        }
        if (HudWidgets.menuRow("groupRename", PENCIL, "Rename…", "")) {
            editor().requestGroupName(name, groupMenuRight, groupMenuBottom);
        }
        GeodataWidgets.tip("Give " + name + " another name.");
        if (HudWidgets.menuRow("groupUngroup", Glyph.UNGROUP, "Ungroup", "Shift+" + COMMAND_KEY + "+G")) {
            runEditing(() -> editor().ungroup(name));
        }
        GeodataWidgets.tip("Take the group away. Its objects stay where they are.");
        HudWidgets.endMenu();
    }

    private void drawTreeFold(String name) {
        var start = ImGui.getCursorPos();
        ImGui.setCursorPos(ImGui.getWindowWidth() - ImGui.getStyle().getWindowPaddingX() - 24, start.y);
        if (HudWidgets.button("foldObjectTree", Glyph.CHEVRON_LEFT, "", 24, 24, false, false)) {
            layout.treeFolded = true;
        }
        tooltip("Fold the " + name + " panel away, for more of the view.");
        ImGui.setCursorPos(start.x, start.y);
    }

    private void drawTreeChips(boolean editing) {
        var filters = editing ? TreeFilter.values() : new TreeFilter[] {TreeFilter.ALL, TreeFilter.SELECTED};
        var spacing = ImGui.getStyle().getItemSpacingX();
        var room = ImGui.getContentRegionAvailX() - spacing * (filters.length - 1);
        var changed = editing ? changedObjects() : 0;
        var labels = new String[filters.length];
        for (var index = 0; index < filters.length; index++) {
            labels[index] = switch (filters[index]) {
                case ALL -> "All";
                case SELECTED -> "Selected";
                case CHANGED -> changed > 0 ? "Changed · " + changed : "Changed";
            };
        }
        var widths = chipWidths(labels, room);
        if (widths == null) {
            labels[filters.length - 1] = editing ? "Changed" : labels[filters.length - 1];
            widths = new float[filters.length];
            Arrays.fill(widths, room / filters.length);
        }
        var icons = true;
        for (var index = 0; index < filters.length; index++) {
            icons &= 16 + ImGui.calcTextSizeX(labels[index]) + 6 + 16 <= widths[index];
        }
        for (var index = 0; index < filters.length; index++) {
            var filter = filters[index];
            if (index > 0) {
                ImGui.sameLine();
            }
            var label = labels[index];
            var glyph = switch (filter) {
                case ALL -> BOX;
                case SELECTED -> Glyph.CURSOR;
                case CHANGED -> PENCIL;
            };
            if (GeodataWidgets.chip(
                    "treeFilter" + filter, icons ? glyph : null, label, treeFilter == filter, widths[index])) {
                treeFilter = filter;
                rowsDirty = true;
            }
            tooltip(
                    switch (filter) {
                        case ALL -> "Every object of the loaded scene.";
                        case SELECTED -> editing ? "Only the selected objects." : "Only the selected object.";
                        case CHANGED ->
                            (changed > 0
                                            ? EditorUi.plural(changed, "object differs", "objects differ")
                                                    + " from the client. "
                                            : "")
                                    + "Only the objects that differ from the client: moved, turned, scaled, new or"
                                    + " with other materials. A dot marks them in every list.";
                    });
        }
    }

    private int changedObjects() {
        var session = scene.getEditing();
        if (session.isGestureActive()
                || session.getRevision() == changedRevision && catalog.getRevision() == changedCatalog) {
            return changedCount;
        }
        changedRevision = session.getRevision();
        changedCatalog = catalog.getRevision();
        var actors = new HashSet<String>();
        for (var entry : catalog.search("")) {
            var actor = entry.selection().actor();
            if (actor != null && !actors.contains(actor) && session.isChanged(actor)) {
                actors.add(actor);
            }
        }
        changedCount = actors.size();
        return changedCount;
    }

    private float drawActorMarks(ImDrawList draw, String actor, float x, float y, float w, float rowHeight) {
        var session = scene.getEditing();
        var hidden = session.getHiddenActors().contains(actor);
        var locked = session.getLockedActors().contains(actor);
        var mouseX = ImGui.getMousePosX();
        var mouseY = ImGui.getMousePosY();
        var rowHovered =
                ImGui.isWindowHovered() && mouseY >= y && mouseY < y + rowHeight - 4 && mouseX >= x && mouseX < x + w;
        var right = x + w - 4;
        var size = 16f;
        var top = y + (rowHeight - 4 - size) * .5f;
        if (hidden || rowHovered) {
            var left = right - size;
            var over = rowHovered && mouseX >= left - 2 && mouseX <= right + 2;
            HudWidgets.glyph(draw, hidden ? Glyph.EYE_OFF : EYE, left, top, size, over ? TEXT : GeodataWidgets.FAINT);
            if (over) {
                treeMarkHovered = true;
                HudWidgets.showTooltip(
                        hidden
                                ? "Show this object (Alt+H shows all)."
                                : "Hide this object until you show it again (H).");
                if (ImGui.isMouseClicked(0)) {
                    runEditing(() -> {
                        if (hidden) {
                            session.show(List.of(actor));
                        } else {
                            session.hide(List.of(actor));
                        }
                    });
                }
            }
            right -= size + 6;
        }
        if (locked || rowHovered) {
            var left = right - size;
            var over = rowHovered && mouseX >= left - 2 && mouseX <= right + 2;
            HudWidgets.glyph(draw, Glyph.LOCK, left, top, size, over ? TEXT : locked ? MUTED : GeodataWidgets.FAINT);
            if (over) {
                treeMarkHovered = true;
                HudWidgets.showTooltip(
                        locked
                                ? "Unlock this object, so clicks and moves take it again (L)."
                                : "Lock this object: clicks, the rectangle and moves leave it alone (L).");
                if (ImGui.isMouseClicked(0)) {
                    session.setLocked(List.of(actor), !locked);
                }
            }
            right -= size + 6;
        }
        if (session.isChanged(actor)) {
            draw.addCircleFilled(right - 4, HudWidgets.textMiddle(y + 6), 3.5f, GeodataWidgets.WARNING);
            right -= 12;
        }
        return x + w - 4 - right;
    }

    private void runEditing(Runnable change) {
        try {
            change.run();
        } catch (RuntimeException failure) {
            editor().showError(failure.getMessage());
        }
    }

    private @Nullable String refusal(TreeRow row, @Nullable String actorPath) {
        if (row.depth() == 1) {
            if (row.pick() == null) {
                return EditorUi.EFFECTS_CANNOT_BE_EDITED;
            }
            var object = row.pick().sceneObject();
            return object != null && object.category() == Category.STATIC_MESH && actorPath != null
                    ? uneditableActor(actorPath)
                    : null;
        }
        var selection = row.selection();
        if (row.depth() != 2 || selection == null) {
            return null;
        }
        if (selection.emitter() != null) {
            return EditorUi.EFFECTS_CANNOT_BE_EDITED;
        }
        return selection.sceneObject().category() == Category.STATIC_MESH ? uneditableActor(selection.actor()) : null;
    }

    private @Nullable String uneditableActor(String actor) {
        var known = scene.getEditing().checkEditable(actor);
        if (known == null) {
            return EditorUi.uneditable(actor);
        }
        return known ? null : EditorUi.cannotEdit(actor);
    }

    private @Nullable String rowActor(TreeRow row) {
        if (row.selection() != null) {
            return row.selection().actor();
        }
        return row.depth() == 1 ? row.id().substring(row.id().indexOf('/') + 1) : null;
    }

    private void rebuildRows() {
        var next = new ArrayList<TreeRow>();
        String owner = null;
        String actor = null;
        var text = query.get();
        if (layout.mode == Mode.EDITING && treeFilter == TreeFilter.ALL) {
            addGroupRows(next, text);
        }
        var folding = text.isBlank() && treeFilter == TreeFilter.ALL;
        var foldingParts = folding || treeFilter == TreeFilter.ALL && layout.mode == Mode.EDITING;
        var source = treeRowsSource(text);
        var index = 0;
        while (index < source.size()) {
            var object = source.get(index).selection();
            if (!object.owner().equals(owner)) {
                owner = object.owner();
                actor = null;
                next.add(new TreeRow(owner, owner, "", 0, null, null));
            }
            if (folding && !expanded.contains(owner)) {
                index = catalog.nextOwner(index);
                continue;
            }
            var actorId = owner + "/" + object.actor();
            var effect = layout.mode == Mode.EDITING
                    && object.emitter() == null
                    && EditorEffectStage.isEffectRow(object.sceneObject());
            if (!actorId.equals(actor)) {
                actor = actorId;
                var actorName = DebugSelection.shortName(object.actor());
                var named = layout.mode == Mode.EDITING && object.emitter() == null;
                next.add(
                        named
                                ? new TreeRow(
                                        actorId,
                                        effect ? actorName : object.shortName(),
                                        effect ? "" : actorName,
                                        1,
                                        null,
                                        object)
                                : new TreeRow(actorId, actorName, "", 1, null, null));
            }
            if (effect) {
                index++;
                continue;
            }
            if (foldingParts && !expanded.contains(actorId)) {
                if (folding) {
                    index = catalog.nextActor(index);
                } else {
                    var name = object.actor();
                    while (index < source.size()
                            && source.get(index).selection().owner().equals(owner)
                            && source.get(index).selection().actor().equals(name)) {
                        index++;
                    }
                }
                continue;
            }
            var name = object.emitter() == null
                    ? object.shortName() + " · " + object.surfaceOrdinal()
                    : DebugSelection.shortName(object.child());
            next.add(new TreeRow(object.key(), name, "", 2, object, null));
            index++;
        }
        rows = List.copyOf(next);
        rowRevision = catalog.getRevision();
        rowQuery = query.get();
        rowsDirty = false;
    }

    private void addGroupRows(List<TreeRow> next, String text) {
        var session = scene.getEditing();
        var names = session.getGroupNames();
        if (names.isEmpty()) {
            return;
        }
        var search = text.trim().toLowerCase(Locale.ROOT);
        var groups = new ArrayList<TreeRow>();
        for (var name : names) {
            var members = session.getGroupMembers(name);
            var memberRows = new ArrayList<TreeRow>();
            var id = "group:" + name;
            for (var actor : members) {
                var mesh = "";
                try {
                    mesh = session.getActor(actor).meshSourcePath();
                } catch (IllegalArgumentException gone) {
                    continue;
                }
                var label = mesh.isBlank() ? DebugSelection.shortName(actor) : DebugSelection.shortName(mesh);
                var actorName = DebugSelection.shortName(actor);
                if (search.isEmpty()
                        || (label + " " + actorName).toLowerCase(Locale.ROOT).contains(search)) {
                    memberRows.add(
                            new TreeRow(id + "/" + actor, label, actorName, 1, null, null, RowKind.MEMBER, name));
                }
            }
            var found = search.isEmpty() || name.toLowerCase(Locale.ROOT).contains(search) || !memberRows.isEmpty();
            if (!found) {
                continue;
            }
            groups.add(new TreeRow(
                    id,
                    name,
                    EditorUi.plural(members.size(), "object", "objects"),
                    0,
                    null,
                    null,
                    RowKind.GROUP,
                    name));
            if (expanded.contains(id) || !search.isEmpty()) {
                groups.addAll(memberRows);
            }
        }
        if (!groups.isEmpty()) {
            next.add(new TreeRow("groups", "GROUPS", "", 0, null, null, RowKind.GROUPS, null));
            next.addAll(groups);
        }
    }

    private List<Entry> treeRowsSource(String text) {
        return switch (treeFilter) {
            case ALL -> catalog.search(text);
            case SELECTED -> {
                if (layout.mode == Mode.EDITING) {
                    yield catalog.getRowsOf(scene.getEditing().getSelection(), text);
                }
                var selected = scene.inspection.selected;
                if (selected == null) {
                    yield List.of();
                }
                var key = selected.key();
                yield catalog.getRowsOf(selected.owner(), selected.actor(), text).stream()
                        .filter(entry -> entry.selection().key().equals(key))
                        .toList();
            }
            case CHANGED ->
                layout.mode != Mode.EDITING
                        ? List.of()
                        : catalog.search(text).stream()
                                .filter(entry -> scene.getEditing()
                                        .isChanged(entry.selection().actor()))
                                .toList();
        };
    }

    private float trayHeight() {
        if (layout.mode == Mode.ANALYZE) {
            return Math.min(layout.analysisHeight, height * .3f);
        }
        return isDirectorShown() ? Math.min(layout.directorHeight, height * DIRECTOR_SHARE) : 0;
    }

    private void drawDirectorTray() {
        var trayWidth = Math.max(1, getViewportRight() - MARGIN);
        beginPanel("##directorTray", MARGIN, analysisY(), trayWidth, trayHeight, true, HudWidgets.PANEL_RADIUS);
        Motion.open(Motion.appear(DIRECTOR_SHOWN, Motion.OPEN), 0, 24, 1);
        ImGui.setCursorPos(DIRECTOR_PADDING, GeodataWidgets.ROW);
        directorTray.draw(trayWidth - 2 * DIRECTOR_PADDING, trayHeight - 2 * GeodataWidgets.ROW);
        ImGui.end();
    }

    private void drawAnalysisTray() {
        var trayWidth = Math.max(1, getViewportRight() - MARGIN);
        beginPanel("##analysisTray", MARGIN, analysisY(), trayWidth, trayHeight, true, HudWidgets.PANEL_RADIUS);
        Motion.open(Motion.appear(TRAY_SHOWN, Motion.OPEN), 0, 24, 1);
        ImGui.setCursorPos(14, 6);
        if (HudWidgets.tab("Animation", !compareTab, 116, 30)) {
            compareTab = false;
        }
        ImGui.sameLine(0, 10);
        if (HudWidgets.tab("Compare A/B", compareTab, 148, 30)) {
            compareTab = true;
        }
        rule();
        ImGui.setCursorPos(14, 49);
        if (!compareTab) {
            var playback = scene.playback;
            ImGui.beginDisabled(playback.isComparisonLocked());
            if (HudWidgets.button("trayPlay", PLAY, "", 58, 30, !playback.isPaused(), true)) {
                playback.setPaused(false);
            }
            tooltip("Play the scene");
            ImGui.sameLine(0, 12);
            if (HudWidgets.button("trayPause", PAUSE, "", 52, 30, playback.isPaused(), true)) {
                playback.setPaused(true);
            }
            tooltip("Pause animation. Keep camera navigation active");
            ImGui.sameLine(0, 12);
            if (HudWidgets.button("trayStep", STEP, "", 58, 30, false, true)) {
                playback.step();
            }
            tooltip("Advance the shared simulation by 1/60 second");
            ImGui.endDisabled();
            ImGui.sameLine(0, 24);
            ImGui.setCursorPosY(56);
            ImGui.textColored(MUTED, "Animation speed");
            ImGui.sameLine(0, 10);
            ImGui.setCursorPosY(50);
            drawSpeedCombo(82);
            ImGui.sameLine(0, 24);
            ImGui.setCursorPosY(56);
            ImGui.textUnformatted(String.format(
                    Locale.ROOT,
                    "%s  ·  %.2f s",
                    playback.isPaused() ? "Paused" : "Playing",
                    playback.getPreciseSeconds()));
            tooltip("Scene simulation time. Step advances 1/60 second while paused.");
            var selected = scene.inspection.selected;
            var emitter = selected == null ? null : selected.emitter();
            var frames = emitter == null ? 0 : emitter.getAnimationFrames();
            var frame = 0;
            if (frames > 0) {
                for (var i = 0; i < emitter.getMaxActive(); i++) {
                    if (emitter.isActive(i)) {
                        frame = emitter.getMeshAnimationFrame(i) - emitter.getAnimationStartFrame() + 1;
                        break;
                    }
                }
            }
            if (frames > 0) {
                ImGui.sameLine(0, 24);
                ImGui.textColored(MUTED, "Selected mesh  ·  Frame " + frame + " / " + frames);
                tooltip("Current frame of the first active particle in the selected mesh emitter.");
            }
        } else {
            if (!comparison.isActive()) {
                if (HudWidgets.button("startComparison", null, "Start comparison", 174, 30, true, true)) {
                    comparison.begin(ui.rendering, scene.playback);
                }
                ImGui.sameLine(214);
                ImGui.textColored(MUTED, "Freeze the view, edit Scene settings, then save alternatives A and B.");
            } else {
                if (modeButton("A", comparison.getShown().equals("A"), 45)) {
                    comparison.show("A", ui.rendering);
                }
                ImGui.sameLine();
                if (modeButton("B", comparison.getShown().equals("B"), 45)) {
                    comparison.show("B", ui.rendering);
                }
                ImGui.sameLine();
                if (ImGui.button("Save A", 90, 30)) {
                    comparison.save("A", ui.rendering);
                }
                ImGui.sameLine();
                if (ImGui.button("Save B", 90, 30)) {
                    comparison.save("B", ui.rendering);
                }
                ImGui.sameLine();
                if (ImGui.button("Scene settings", 120, 30)) {
                    tools.open(Tool.SCENE);
                }
                ImGui.sameLine();
                if (ImGui.button("Exit comparison", 156, 30)) {
                    comparison.finish(scene.playback);
                }
                ImGui.sameLine();
                ImGui.textColored(MUTED, comparison.isModified(ui.rendering) ? "Unsaved preview" : "View locked");
            }
        }
        ImGui.end();
    }

    private String speedLabel() {
        var value = scene.playback.getSpeed();
        return value == Math.rint(value) ? Integer.toString((int) value) : Double.toString(value);
    }

    private void drawSpeedCombo(float controlWidth) {
        var playback = scene.playback;
        ImGui.beginDisabled(playback.isComparisonLocked());
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        if (HudWidgets.button(
                "playbackSpeed", null, speedLabel() + "x     ", controlWidth, 28, false, layout.mode == Mode.ANALYZE)) {
            ImGui.openPopup("##playbackSpeedMenu");
        }
        HudWidgets.glyph(ImGui.getWindowDrawList(), CHEVRON, x + controlWidth - 25, y + 6, 16, MUTED);
        tooltip("Animation speed. Camera speed is independent");
        if (ImGui.beginPopup("##playbackSpeedMenu")) {
            for (var i = 0; i < SPEEDS.length; i++) {
                if (ImGui.selectable(SPEED_LABELS[i], SPEEDS[i] == playback.getSpeed())) {
                    playback.setSpeed(SPEEDS[i]);
                }
            }
            ImGui.endPopup();
        }
        ImGui.endDisabled();
    }

    private void compactPlayback() {
        var playback = scene.playback;
        ImGui.beginDisabled(playback.isComparisonLocked());
        if (HudWidgets.button(
                "compactPlay",
                playback.isPaused() ? PAUSE : PLAY,
                playback.isPaused() ? "Paused" : "Playing",
                124,
                28,
                playback.isPaused(),
                playback.isPaused())) {
            playback.setPaused(!playback.isPaused());
        }
        tooltip(
                playback.isPaused()
                        ? "Animation is paused. Click to resume."
                        : "Animation is playing. Click to pause.");
        ImGui.endDisabled();
        ImGui.sameLine(0, 4);
        drawSpeedCombo(60);
    }

    private void drawPlaybackControls() {
        var playback = scene.playback;
        ImGui.beginDisabled(playback.isComparisonLocked());
        if (HudWidgets.button(
                "playbackToggle",
                playback.isPaused() ? PAUSE : PLAY,
                playback.isPaused() ? "Paused" : "Playing",
                100,
                30,
                playback.isPaused(),
                true)) {
            playback.setPaused(!playback.isPaused());
        }
        tooltip(
                playback.isPaused()
                        ? "Animation is paused. Click to resume."
                        : "Animation is playing. Click to pause.");
        ImGui.sameLine();
        if (HudWidgets.button("playbackStep", STEP, "", 38, 30, false, true)) {
            playback.step();
        }
        tooltip("Advance one 1/60-second simulation tick");
        ImGui.endDisabled();
        ImGui.sameLine();
        drawSpeedCombo(72);
    }

    private String coordinateText() {
        var p = scene.camera.getWorldFocusPosition();
        return String.format(Locale.ROOT, "X %.0f   Y %.0f   Z %.0f", p.getX(), p.getY(), p.getZ());
    }

    private float statusBarWidth(float coordinateWidth) {
        var controlsWidth = comparison.isActive()
                ? 100
                : LogSliderField.width(cameraLabelWidth())
                        + (scene.camera.isInteractiveOrtho() ? ORTHO_TOOLS_WIDTH : 0);
        return controlsX(coordinateWidth) + controlsWidth + statusEnd();
    }

    private float statusBarWidth() {
        var fullWidth = statusBarWidth(coordinateWidth(coordinateText()));
        return width - MARGIN * 2 < fullWidth
                ? controlsX(28) + (comparison.isActive() ? 100 : 28) + statusEnd()
                : fullWidth;
    }

    private float statusEnd() {
        return comparison.isActive() ? 12 : STATUS_END;
    }

    private float controlsX(float coordinateWidth) {
        return effectsOnly ? 12 : 12 + coordinateWidth + 24;
    }

    private float compactPanelY(float panelWidth, float panelHeight) {
        var barLeft = Math.round((width - statusBarWidth()) * .5f);
        var bottom = trayHeight == 0 && MARGIN + panelWidth + MARGIN <= barLeft ? height - MARGIN : statusY() - MARGIN;
        return Math.max(topHeight + 8, bottom - panelHeight);
    }

    private void drawStatusBar() {
        var p = scene.camera.getWorldFocusPosition();
        var coordinates = coordinateText();
        var available = width - MARGIN * 2;
        var compact = available < statusBarWidth(coordinateWidth(coordinates));
        var coordinateWidth = compact ? 28 : coordinateWidth(coordinates);
        var controlsX = controlsX(coordinateWidth);
        var barWidth = compact
                ? controlsX + (comparison.isActive() ? 100 : 28) + statusEnd()
                : statusBarWidth(coordinateWidth);
        var barX = Math.round((width - barWidth) * .5f);
        var open = flyStatus(barX, barWidth);
        var shift = 0f;
        if (open > 1.5f) {
            return;
        } else if (open < 0) {
            beginPanel("##workspaceStatus", barX, statusHomeY(), barWidth, STATUS, true, HudWidgets.PANEL_RADIUS);
        } else if (open < .12f) {
            ImGui.end();
            return;
        } else {
            shift = ImGui.getWindowWidth() - barWidth;
        }
        var y = (ImGui.getWindowHeight() - HudWidgets.CONTROL_HEIGHT) * .5f;
        if (!effectsOnly) {
            ImGui.setCursorPos(shift + 12, y);
            if (HudWidgets.button(
                    "copyCoordinates",
                    compact ? COPY : null,
                    compact ? "" : coordinates,
                    coordinateWidth,
                    HudWidgets.CONTROL_HEIGHT,
                    false,
                    false)) {
                ImGui.setClipboardText(String.format(Locale.ROOT, "%.0f %.0f %.0f", p.getX(), p.getY(), p.getZ()));
                notify("Coordinates copied");
            }
            tooltip("Copy coordinates · " + coordinates);
            var dividerX = ImGui.getWindowPosX() + shift + controlsX - 12;
            var dividerY = ImGui.getWindowPosY();
            ImGui.getWindowDrawList()
                    .addLine(dividerX, dividerY + 11, dividerX, dividerY + STATUS - 11, HudWidgets.BORDER, 1);
        }
        ImGui.setCursorPos(shift + controlsX, y);
        if (comparison.isActive()) {
            if (HudWidgets.button("leaveComparison", null, "Exit A/B", 100, HudWidgets.CONTROL_HEIGHT, true, false)) {
                comparison.finish(scene.playback);
            }
        } else if (compact) {
            var zooming = scene.camera.isInteractiveOrtho();
            var popup = zooming ? "##orthoZoomPopup" : "##cameraSpeedPopup";
            if (HudWidgets.button(
                    zooming ? "orthoZoomPopup" : "cameraSpeedPopup",
                    zooming ? Glyph.ZOOM : Glyph.CAMERA,
                    "",
                    28,
                    HudWidgets.CONTROL_HEIGHT,
                    zooming && ui.rendering.isSliced(scene.camera.getView()),
                    false)) {
                ImGui.openPopup(popup);
            }
            tooltip(
                    zooming
                            ? "Zoom · " + zoom.format(Camera.zoomPercent(scene.camera.getOrthoZoomFactor())) + "\n"
                                    + OrthoSliceControls.describe(ui.rendering, scene.camera.getView())
                            : "Camera speed · " + cameraSpeed.format(ui.rendering.cameraSpeed.get()));
            if (zooming) {
                if (HudWidgets.beginPopupAbove(popup, width * .5f, ORTHO_POPUP_WIDTH, ImGui.getWindowPosY() - MARGIN)) {
                    drawCameraControl();
                    GeodataWidgets.section("SLICE");
                    OrthoSliceControls.draw(ui.rendering, scene.camera, backend.getSceneBounds(), scene.tiles);
                    GeodataWidgets.section("SCALE");
                    OrthoGridControls.draw(ui.rendering, scene.camera, GRID_TIP);
                    ImGui.endPopup();
                }
            } else {
                closePopup("##orthoZoomPopup");
                if (ImGui.beginPopup(popup)) {
                    drawCameraControl();
                    ImGui.endPopup();
                }
            }
        } else {
            drawCameraControl();
            if (scene.camera.isInteractiveOrtho()) {
                drawOrthoTools(shift + controlsX + LogSliderField.width(cameraLabelWidth()), y);
            } else {
                closePopup("##orthoSlicePopup");
                closePopup("##orthoGridPopup");
            }
        }
        if (!comparison.isActive()) {
            drawStatusFold(shift + barWidth - 6 - STATUS_FOLD, y);
        }
        ImGui.end();
    }

    private float flyStatus(float barX, float barWidth) {
        turnStatus();
        var docked = isStatusDocked();
        var side = ToolWindows.RAIL_WIDTH - 8;
        var dockRight = getToolRailX() + 4 + side;
        var barRight = barX + barWidth;
        float right;
        float w;
        if (docked) {
            w = Motion.follow(STATUS_WIDTH, side, .22f);
            right = Motion.drop(STATUS_RIGHT, dockRight, .3f, 18);
        } else {
            right = Motion.spring(STATUS_RIGHT, barRight, 2.4f, .74f);
            w = Motion.spring(STATUS_WIDTH, barWidth, 2.4f, .74f);
        }
        var moved = right - statusRight;
        if (docked && statusStep > 0 && moved <= 0) {
            Motion.jump(STATUS_SQUASH, Math.min(10, statusStep * .25f));
        }
        statusStep = Float.isNaN(moved) ? 0 : moved;
        var squash = Motion.spring(STATUS_SQUASH, 0, 6, .4f);
        var landed = docked && right == dockRight && (statusRight == dockRight || statusSince >= 1);
        statusRight = right;
        statusLanded = landed;
        var fade = Motion.toggle(STATUS_LANDED, landed, .12f);
        if (!docked && !statusSettled) {
            statusSettled = right == barRight && w == barWidth || statusSince >= 1;
        }
        if (docked ? fade >= 1 : statusSettled) {
            return docked ? 2 : -1;
        }
        var open = Math.clamp((w - side) / Math.max(1, barWidth - side), 0, 1);
        var h = STATUS + squash * .5f;
        w -= squash;
        var barMiddle = statusHomeY() + STATUS * .5f;
        var dockMiddle = getStatusDockY() + ToolWindows.BUTTON_HEIGHT * .5f;
        var along = Math.clamp((right - barRight) / Math.max(1, dockRight - barRight), 0, 1);
        var middle = barMiddle + (dockMiddle - barMiddle) * along;
        beginPanel(
                "##workspaceStatusFlight",
                right - w,
                middle - h * .5f,
                w,
                h,
                6 + (HudWidgets.PANEL_RADIUS - 6) * open,
                FLIGHT | ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        var compass = Math.clamp(1 - open / .15f, 0, 1);
        if (compass > 0) {
            var size = ToolWindows.ICON_SIZE;
            HudWidgets.glyph(
                    ImGui.getWindowDrawList(),
                    Glyph.COMPASS,
                    right - (w + size) * .5f,
                    middle - size * .5f,
                    size,
                    Motion.alpha(TEXT, compass));
        }
        Motion.fade(docked ? 1 - fade : 1, Math.clamp((open - .12f) / .25f, 0, 1), 0, 0, 1, 0);
        return open;
    }

    private void drawStatusFold(float x, float y) {
        var dividerX = ImGui.getWindowPosX() + x - 8;
        var dividerY = ImGui.getWindowPosY();
        ImGui.getWindowDrawList()
                .addLine(dividerX, dividerY + 11, dividerX, dividerY + STATUS - 11, HudWidgets.BORDER, 1);
        ImGui.setCursorPos(x, y);
        if (HudWidgets.button(
                "statusFold", Glyph.CHEVRON_RIGHT, "", STATUS_FOLD, HudWidgets.CONTROL_HEIGHT, false, false)) {
            layout.statusDocked = true;
            closePopup("##orthoSlicePopup");
            closePopup("##orthoGridPopup");
            closePopup("##orthoZoomPopup");
            closePopup("##cameraSpeedPopup");
        }
        tooltip("Tuck into the rail · The compass at the foot of the rail brings the bar back");
    }

    private void drawOrthoTools(float x, float y) {
        var view = scene.camera.getView();
        var barWidth = ImGui.getWindowWidth();
        var barCenter = ImGui.getWindowPosX() + barWidth * .5f;
        var popupBottom = ImGui.getWindowPosY() - MARGIN;
        var dividerX = ImGui.getWindowPosX() + x + 12;
        var dividerY = ImGui.getWindowPosY();
        ImGui.getWindowDrawList()
                .addLine(dividerX, dividerY + 11, dividerX, dividerY + STATUS - 11, HudWidgets.BORDER, 1);
        ImGui.setCursorPos(x + 24, y);
        if (HudWidgets.button(
                "orthoSlice", Glyph.SLICE, "", 28, HudWidgets.CONTROL_HEIGHT, ui.rendering.isSliced(view), false)) {
            ImGui.openPopup("##orthoSlicePopup");
        }
        tooltip(OrthoSliceControls.describe(ui.rendering, view));
        if (HudWidgets.beginPopupAbove("##orthoSlicePopup", barCenter, barWidth, popupBottom)) {
            GeodataWidgets.section("SLICE", 0);
            OrthoSliceControls.draw(ui.rendering, scene.camera, backend.getSceneBounds(), scene.tiles);
            ImGui.endPopup();
        }
        ImGui.setCursorPos(x + 24 + 28 + GeodataWidgets.ROW, y);
        if (HudWidgets.button(
                "orthoGrid", Glyph.RULER, "", 28, HudWidgets.CONTROL_HEIGHT, ui.rendering.orthoGrid, false)) {
            ui.rendering.orthoGrid = !ui.rendering.orthoGrid;
        }
        tooltip(GRID_TIP);
        ImGui.setCursorPos(x + 24 + 28 + GeodataWidgets.ROW + 28, y);
        if (HudWidgets.button(
                "orthoGridChoices", Glyph.CHEVRON_UP, "", ORTHO_GRID_MENU, HudWidgets.CONTROL_HEIGHT, false, false)) {
            ImGui.openPopup("##orthoGridPopup");
        }
        tooltip("Grid density · How close the lines stand, or a step of world units.");
        if (HudWidgets.beginPopupAbove("##orthoGridPopup", barCenter, barWidth, popupBottom)) {
            OrthoGridControls.draw(ui.rendering, scene.camera, GRID_TIP);
            ImGui.endPopup();
        }
    }

    private void drawCameraControl() {
        var camera = scene.camera;
        if (!camera.isInteractiveOrtho()) {
            cameraSpeed.draw(ui.rendering.cameraSpeed.getData(), cameraLabelWidth());
            return;
        }
        zoomPercent[0] = Camera.zoomPercent(camera.getOrthoZoomFactor());
        if (zoom.draw(zoomPercent, cameraLabelWidth())) {
            camera.setOrthoZoomFactor(Camera.zoomFactorOfPercent(zoomPercent[0]));
        }
    }

    private void drawDetail(String label, String value) {
        ImGui.pushID(label);
        var x = ImGui.getCursorPosX();
        var y = ImGui.getCursorPosY();
        ImGui.setCursorPosY(y + 6);
        ImGui.textColored(MUTED, label);
        ImGui.setCursorPos(ImGui.getWindowContentRegionMaxX() - 26, y);
        copyButton("copy", value, "Copy " + label.toLowerCase(Locale.ROOT));
        ImGui.setCursorPos(x, y + 28);
        ImGui.pushFont(fonts.getMonoFont(), 14f);
        ImGui.textWrapped(value);
        ImGui.popFont();
        ImGui.spacing();
        ImGui.popID();
    }

    private void resizeGrips() {
        if (rightWidth > 0) {
            var delta = drawGrip(
                    "##resizeInspector", getToolPanelX() - 3, getToolPanelTop(), 6, getToolPanelHeight(), false);
            if (isEditPanelOpen()) {
                layout.editingWidth = Float.isNaN(delta)
                        ? new WorkspaceLayout().editingWidth
                        : Math.clamp(layout.editingWidth - delta, 280, 700);
            } else if (Float.isNaN(delta)) {
                layout.inspectorWidth = new WorkspaceLayout().inspectorWidth;
            } else {
                layout.inspectorWidth = Math.clamp(layout.inspectorWidth - delta, 280, 700);
            }
        }
        if (leftWidth > 0) {
            var delta = drawGrip(
                    "##resizeObjects", MARGIN + leftWidth - 3, topHeight, 6, sidePanelBottom() - topHeight, false);
            layout.treeWidth = Float.isNaN(delta)
                    ? new WorkspaceLayout().treeWidth
                    : Math.clamp(layout.treeWidth + delta, 200, Math.max(200, width * .25f));
        }
        if (trayHeight > 0 && isDirectorShown()) {
            var delta = drawGrip(
                    "##resizeDirector", MARGIN, analysisY() - 3, Math.max(1, getViewportRight() - MARGIN), 6, true);
            layout.directorHeight = Float.isNaN(delta)
                    ? new WorkspaceLayout().directorHeight
                    : Math.clamp(
                            layout.directorHeight - delta,
                            DIRECTOR_LOWEST,
                            Math.max(DIRECTOR_LOWEST, height * DIRECTOR_SHARE));
        } else if (trayHeight > 0) {
            var delta = drawGrip(
                    "##resizeAnalysis", MARGIN, analysisY() - 3, Math.max(1, getViewportRight() - MARGIN), 6, true);
            layout.analysisHeight = Float.isNaN(delta)
                    ? new WorkspaceLayout().analysisHeight
                    : Math.clamp(layout.analysisHeight - delta, 92, Math.max(92, height * .3f));
        }
    }

    private void sectionLabel(String text) {
        ImGui.pushFont(fonts.getHeadingFont(15), 15f);
        ImGui.textUnformatted(text);
        ImGui.popFont();
    }

    private void heading(String text, int size) {
        ImGui.pushFont(fonts.getHeadingFont(size), size);
        ImGui.textUnformatted(text);
        ImGui.popFont();
    }

    private void copyButton(String id, String value, String hint) {
        if (HudWidgets.button(id, COPY, "", 26, 26, false, false)) {
            ImGui.setClipboardText(value);
            notify("Copied");
        }
        tooltip(hint);
    }

    private static String modeName(Mode mode) {
        return mode == Mode.EDITING ? "Edit" : capitalize(mode.name());
    }

    private static String modeTooltip(Mode mode) {
        return mode == Mode.EDITING
                ? "Edit workspace · the tiles load again with editable objects, nearest first"
                : capitalize(mode.name()) + " workspace · F2 cycles Explore, Inspect and Analyze";
    }

    private static float viewControlsWidth(boolean editing) {
        var views = VIEW_SEGMENT_WIDTH * CameraView.values().length;
        var modes = VIEW_SEGMENT_WIDTH * ViewMode.values().length;
        var divider = GeodataWidgets.ROW + 1 + GeodataWidgets.ROW;
        return (editing ? 0 : HudWidgets.CONTROL_HEIGHT + divider) + views + divider + modes;
    }

    private static String viewTip(CameraView view) {
        return switch (view) {
            case PERSPECTIVE ->
                "Free camera: look around with the right mouse button and move with W, A, S and D, with perspective.";
            case TOP -> "Top: look straight down on the map, without perspective, north at the top. " + ORTHO_KEYS;
            case FRONT ->
                "Front: look north across the map, without perspective, east to the right and up at the top, as"
                        + " UnrealEd's Front view. "
                        + ORTHO_KEYS + " The scene is drawn Unlit or Wireframe here.";
            case SIDE ->
                "Side: look east across the map, without perspective, south to the right and up at the top, as"
                        + " UnrealEd's Side view. "
                        + ORTHO_KEYS + " The scene is drawn Unlit or Wireframe here.";
        };
    }

    private static String viewModeTip(ViewMode mode, CameraView view) {
        return switch (mode) {
            case LIT ->
                view.isLit()
                        ? "Lit: the scene as the game lights it, with lightmaps, sun, shadows and fog."
                        : "Lit: the scene as the game lights it. The Front and Side views draw it Unlit, because their"
                                + " eye stands beside the map, where the sun shadows and the lamps have no place. Free"
                                + " and Top draw it lit again.";
            case UNLIT ->
                "Unlit: every surface in its own colors, with no light, shadows, projectors or fog. Effects and the"
                        + " sky stay.";
            case WIREFRAME ->
                "Wireframe: every triangle as lines, one color for each kind: meshes, BSP, terrain and water. No sky"
                        + " or effects.";
        };
    }

    private static float @Nullable [] chipWidths(String[] labels, float room) {
        var pad = 12f;
        var widths = new float[labels.length];
        var equal = room / labels.length;
        var needed = 0f;
        var fitsEqual = true;
        for (var index = 0; index < labels.length; index++) {
            widths[index] = ImGui.calcTextSizeX(labels[index]) + pad;
            needed += widths[index];
            fitsEqual &= widths[index] <= equal;
        }
        if (fitsEqual) {
            Arrays.fill(widths, equal);
            return widths;
        }
        if (needed > room) {
            return null;
        }
        for (var index = 0; index < labels.length; index++) {
            widths[index] += (room - needed) / labels.length;
        }
        return widths;
    }

    private static float cameraLabelWidth() {
        return Math.max(ImGui.calcTextSizeX("Speed"), ImGui.calcTextSizeX("Zoom"));
    }

    private static float coordinateWidth(String coordinates) {
        return ImGui.calcTextSizeX(coordinates) + 20;
    }

    private static void closePopup(String id) {
        if (ImGui.beginPopup(id)) {
            ImGui.closeCurrentPopup();
            ImGui.endPopup();
        }
    }

    private static float drawGrip(String id, float x, float y, float w, float h, boolean horizontal) {
        ImGui.setNextWindowPos(x, y);
        ImGui.setNextWindowSize(w, h);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowMinSize, 1, 1);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0);
        ImGui.begin(id, PANEL | ImGuiWindowFlags.NoBackground | ImGuiWindowFlags.NoScrollbar);
        ImGui.invisibleButton("##drag", w, h);
        var active = ImGui.isItemActive();
        var hovered = ImGui.isItemHovered();
        if (active || hovered) {
            ImGui.setMouseCursor(horizontal ? ImGuiMouseCursor.ResizeNS : ImGuiMouseCursor.ResizeEW);
            var draw = ImGui.getForegroundDrawList();
            if (horizontal) {
                draw.addRectFilled(
                        x + 12,
                        y + h * .5f - 1,
                        x + w - 12,
                        y + h * .5f + 1,
                        active ? ACCENT : ImColor.rgba(92, 219, 241, 140),
                        1f);
            } else {
                draw.addRectFilled(
                        x + w * .5f - 1,
                        y + 12,
                        x + w * .5f + 1,
                        y + h - 12,
                        active ? ACCENT : ImColor.rgba(92, 219, 241, 140),
                        1f);
            }
        }
        if (hovered && !active && !horizontal) {
            HudWidgets.showTooltip("Drag to resize · Double-click for the default width");
        }
        if (hovered && ImGui.isMouseDoubleClicked(0)) {
            ImGui.end();
            ImGui.popStyleVar(3);
            return Float.NaN;
        }
        var delta = active
                ? horizontal ? ImGui.getIO().getMouseDeltaY() : ImGui.getIO().getMouseDeltaX()
                : 0;
        ImGui.end();
        ImGui.popStyleVar(3);
        return delta;
    }

    private static void gap(float height) {
        ImGui.dummy(0, Math.max(0, height - ImGui.getStyle().getItemSpacingY()));
    }

    private static void rule() {
        HudWidgets.line(ImGui.getContentRegionAvailX());
    }

    private static String elide(String text, float width) {
        if (ImGui.calcTextSizeX(text) <= width) {
            return text;
        }
        var length = text.length();
        while (length > 0 && ImGui.calcTextSizeX(text.substring(0, length) + "…") > width) {
            length--;
        }
        return text.substring(0, length) + "…";
    }

    private static void valueRow(String label, String value) {
        var y = ImGui.getCursorPosY();
        ImGui.textColored(MUTED, label);
        ImGui.sameLine(ImGui.getWindowContentRegionMaxX() * .57f);
        ImGui.textUnformatted(value);
        ImGui.setCursorPosY(y + 28);
    }

    private static void beginPanel(String id, float x, float y, float w, float h, boolean noScroll) {
        beginPanel(id, x, y, w, h, noScroll, 0);
    }

    private static void beginPanel(String id, float x, float y, float w, float h, boolean noScroll, float rounding) {
        beginPanel(
                id,
                x,
                y,
                w,
                h,
                rounding,
                PANEL | (noScroll ? ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse : 0));
    }

    private static void beginPanel(String id, float x, float y, float w, float h, float rounding, int flags) {
        ImGui.setNextWindowPos(x, y, ImGuiCond.Always);
        ImGui.setNextWindowSize(Math.max(1, w), Math.max(1, h), ImGuiCond.Always);
        ImGui.setNextWindowBgAlpha(.985f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, rounding);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 14, 12);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowMinSize, 1, 1);
        ImGui.begin(id, flags);
        ImGui.popStyleVar(3);
    }

    private static boolean modeButton(String label, boolean active, float width) {
        if (active) {
            ImGui.pushStyleColor(ImGuiCol.Button, .22f, .36f, .43f, 1f);
        }
        var pressed = ImGui.button(label, width, 30);
        if (active) {
            ImGui.popStyleColor();
        }
        return pressed;
    }

    private static String capitalize(String value) {
        return value.charAt(0) + value.substring(1).toLowerCase(Locale.ROOT);
    }

    private static void tooltip(String text) {
        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
            HudWidgets.showTooltip(text);
        }
    }
}
