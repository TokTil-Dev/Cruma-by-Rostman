package com.rost.cruma.viewer.system;

import com.rost.cruma.core.env.LobbyTimeEnv;
import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.core.spawn.NpcGrpTable;
import com.rost.cruma.core.world.AmbientSound;
import com.rost.cruma.core.world.EmitterDistanceMode;
import com.rost.cruma.core.world.GameScenes;
import com.rost.cruma.core.world.MapEventCatalog;
import com.rost.cruma.core.world.MapEventCatalog.Placed;
import com.rost.cruma.core.world.MapEventCatalog.TimedPlaced;
import com.rost.cruma.core.world.MapEventCatalog.TimedSound;
import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.MapScenes;
import com.rost.cruma.core.world.MapScenes.Scene;
import com.rost.cruma.core.world.SceneHelper;
import com.rost.cruma.core.world.SceneSources;
import com.rost.cruma.core.world.ServerEvents;
import com.rost.cruma.core.world.ServerScenes;
import com.rost.cruma.core.world.SkyZones;
import com.rost.cruma.core.world.WorldMapCatalog;
import com.rost.cruma.core.world.ZoneStateFog;
import com.rost.cruma.viewer.agent.AgentFindings.Item;
import com.rost.cruma.viewer.agent.AgentRuntime;
import com.rost.cruma.viewer.application.SceneRecording;
import com.rost.cruma.viewer.application.SceneRequest;
import com.rost.cruma.viewer.application.UiContext;
import com.rost.cruma.viewer.application.UiContext.Rendering;
import com.rost.cruma.viewer.application.WindowContext;
import com.rost.cruma.viewer.application.WindowContext.Keyboard.Key;
import com.rost.cruma.viewer.config.AudioSettings;
import com.rost.cruma.viewer.config.Config;
import com.rost.cruma.viewer.config.Config.Viewer.Camera.Location;
import com.rost.cruma.viewer.config.Config.Viewer.World;
import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.config.ConfigLoader.Options;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.config.FrameLimitSettings;
import com.rost.cruma.viewer.config.InterfaceScale;
import com.rost.cruma.viewer.config.MapEventChoices;
import com.rost.cruma.viewer.editing.EditingPanel;
import com.rost.cruma.viewer.editing.EffectMarks;
import com.rost.cruma.viewer.editing.SceneFileStore;
import com.rost.cruma.viewer.geodata.ClientFloorBackfill;
import com.rost.cruma.viewer.geodata.GeoDataFocusMode;
import com.rost.cruma.viewer.guide.GuideHistory;
import com.rost.cruma.viewer.guide.GuideLibrary;
import com.rost.cruma.viewer.guide.GuideRequests;
import com.rost.cruma.viewer.guide.GuideTopics;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.Camera.Snapshot;
import com.rost.cruma.viewer.render.CameraView;
import com.rost.cruma.viewer.render.GameScenePlayer.Ground;
import com.rost.cruma.viewer.render.ParticleEmitter;
import com.rost.cruma.viewer.render.ParticleEmitter.Kind;
import com.rost.cruma.viewer.render.RenderingContext;
import com.rost.cruma.viewer.render.SceneInspection;
import com.rost.cruma.viewer.render.api.DebugSceneObject;
import com.rost.cruma.viewer.render.api.DebugSceneObject.Category;
import com.rost.cruma.viewer.render.api.DebugSelection;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import com.rost.cruma.viewer.render.api.WorldLoadProgress.WorldLoadStage;
import com.rost.cruma.viewer.spawn.AreaAccess;
import com.rost.cruma.viewer.spawn.MapPawnStage.Hit;
import com.rost.cruma.viewer.spawn.SpawnAccess;
import com.rost.cruma.viewer.ui.AreaWorkspace;
import com.rost.cruma.viewer.ui.CrumaBranding;
import com.rost.cruma.viewer.ui.EffectsPanel;
import com.rost.cruma.viewer.ui.EffectsPanel.Order;
import com.rost.cruma.viewer.ui.FontManager;
import com.rost.cruma.viewer.ui.GameSceneStage;
import com.rost.cruma.viewer.ui.GeodataEditingPanel.Leave;
import com.rost.cruma.viewer.ui.GeodataWidgets;
import com.rost.cruma.viewer.ui.GeodataWorkspace;
import com.rost.cruma.viewer.ui.GeodataWorkspace.Viewport;
import com.rost.cruma.viewer.ui.GuideHost;
import com.rost.cruma.viewer.ui.GuideHost.Areas;
import com.rost.cruma.viewer.ui.GuideHost.Edit;
import com.rost.cruma.viewer.ui.GuideHost.Effects;
import com.rost.cruma.viewer.ui.GuideHost.Geodata;
import com.rost.cruma.viewer.ui.GuideHost.MapEvents;
import com.rost.cruma.viewer.ui.GuideHost.OpenWorld;
import com.rost.cruma.viewer.ui.GuideHost.Scenes;
import com.rost.cruma.viewer.ui.GuideHost.Spawns;
import com.rost.cruma.viewer.ui.GuideWindow;
import com.rost.cruma.viewer.ui.HudTheme;
import com.rost.cruma.viewer.ui.HudWidgets;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.InspectedView;
import com.rost.cruma.viewer.ui.MapEventsPanel;
import com.rost.cruma.viewer.ui.MapPawnCard;
import com.rost.cruma.viewer.ui.Motion;
import com.rost.cruma.viewer.ui.NpcWorkspace;
import com.rost.cruma.viewer.ui.OrthoGrid;
import com.rost.cruma.viewer.ui.OrthoSliceControls;
import com.rost.cruma.viewer.ui.SceneHourHold;
import com.rost.cruma.viewer.ui.SceneRecordedSound;
import com.rost.cruma.viewer.ui.SceneSettingsDefaults;
import com.rost.cruma.viewer.ui.ScenesPanel;
import com.rost.cruma.viewer.ui.ShortcutReference;
import com.rost.cruma.viewer.ui.ShortcutReference.Action;
import com.rost.cruma.viewer.ui.ShortcutReference.View;
import com.rost.cruma.viewer.ui.SpawnWorkspace;
import com.rost.cruma.viewer.ui.ToolWindows.Tool;
import com.rost.cruma.viewer.ui.ViewerExports;
import com.rost.cruma.viewer.ui.ViewerWorldMap;
import com.rost.cruma.viewer.ui.WorkspaceHud;
import com.rost.cruma.viewer.ui.WorkspaceLayout.Mode;
import glm_.vec2.Vec2;
import glm_.vec3.Vec3;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.extension.imguizmo.ImGuizmo;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImInt;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class UiSystem implements System, GuideHost {
    private static final long HIGHLIGHT_HOLD_NANOS = 120_000_000L;

    private static final String LEAVE_EDIT =
            "Leave Edit first with Back to Viewer in the Viewer menu: the tiles load again.";
    private static final String EFFECTS_WITHOUT_WORLD =
            "The Effects mode has no world. Open the world of a client from the start menu.";
    private static final String[] SKY_PRESET_NAMES = {"Normal", "Dusk", "Dawn", "Cursed"};
    private static final String[] LOBBY_PRESET_NAMES = {"Lobby"};
    private static final String[] MOON_NAMES = {
        "Auto (Sky Preset)", "Moon 0 (Default)", "Moon 1", "Moon 2", "Moon 3", "Moon 4"
    };
    private static final String[] WATER_EFFECT_NAMES = {"None (Authored Water)", "All", "Sky"};
    private static final String[] EMITTER_DISTANCE_MODE_NAMES = {"Authored (Client)", "Custom"};
    private static final String[] BACKDROP_NAMES = {"Dark", "Client's sky"};
    private static final int OBJECT_NAME_SHADOW_COLOR = ImColor.rgb(0, 0, 0);
    private static final int OBJECT_NAME_CSG_COLOR = ImColor.rgb(120, 215, 255);
    private static final int OBJECT_NAME_MESH_COLOR = ImColor.rgb(255, 200, 90);
    private static final int OBJECT_NAME_TERRAIN_COLOR = ImColor.rgb(150, 255, 150);
    private static final int OBJECT_NAME_EMITTER_COLOR = ImColor.rgb(255, 120, 220);
    private static final int OBJECT_NAME_SELECT_COLOR = ImColor.rgb(143, 222, 243);
    private static final int OBJECT_NAME_HOVER_COLOR = ImColor.rgb(255, 255, 255);
    private static final int HUD_ACCENT = ImColor.rgb(120, 215, 255);
    private static final int CUT_CIRCLE_SEGMENTS = 96;
    private static final int HUD_ACCENT_BRIGHT = ImColor.rgb(180, 236, 255);
    private static final int HUD_TERRAIN = ImColor.rgb(134, 229, 160);
    private static final int HUD_MESH = ImColor.rgb(255, 200, 90);
    private static final int HUD_CSG = ImColor.rgb(120, 215, 255);
    private static final int HUD_OTHER = ImColor.rgb(138, 151, 162);
    private static final int HUD_RED = ImColor.rgb(255, 107, 107);
    private static final int HUD_TEXT = HudWidgets.TEXT;
    private static final int HUD_TEXT_DIM = HudWidgets.MUTED;
    private static final int HUD_PILL_ON = HudWidgets.PILL_ON;
    private static final int HUD_PILL_OFF = HudWidgets.PILL_OFF;
    private static final int HUD_ROW_HOVER = HudWidgets.ROW_HOVER;
    private static final int HUD_SPARK_BG = ImColor.rgba(0, 0, 0, 70);
    private static final int HUD_BUDGET_LINE = ImColor.rgba(120, 215, 255, 95);
    private static final int HUD_HAIR = ImColor.rgba(120, 215, 255, 45);
    private static final int HUD_TROUGH = ImColor.rgba(255, 255, 255, 20);

    private static final float HUD_MARGIN = 10f;
    private static final float HUD_GAP = 8f;
    private static final float HUD_PAD_X = 10f;
    private static final float HUD_PAD_Y = 9f;
    private static final float TOP_BAR_HEIGHT = 42f;
    private static final float COMMAND_BAR_WIDTH = 620f;
    private static final float PERFORMANCE_PROFILE_WIDTH = 360f;
    private static final float PERFORMANCE_PROFILE_MIN_WIDTH = 280f;
    private static final float PERFORMANCE_PROFILE_RIGHT_INSET = 8f;
    private static final float BUDGET_MS = 1000f / 60f;
    private static final float SPARK_MAX_MS = 40f;

    private static final int LOADING_FILL = "loading/fill".hashCode();
    private static final float LOADING_SCENE_HEIGHT = 240f, LOADING_TITLE_ROOM = 80f, LOADING_UNDER_ROOM = 40f;
    private static final int LOADING_REVEAL = "loading/reveal".hashCode();
    private static final int PROFILE_FLAGS = ImGuiWindowFlags.NoTitleBar
            | ImGuiWindowFlags.NoResize
            | ImGuiWindowFlags.NoMove
            | ImGuiWindowFlags.NoCollapse
            | ImGuiWindowFlags.NoSavedSettings
            | ImGuiWindowFlags.NoNavInputs
            | ImGuiWindowFlags.NoFocusOnAppearing
            | ImGuiWindowFlags.NoBringToFrontOnFocus
            | ImGuiWindowFlags.AlwaysAutoResize;
    private static final int DRAWER_FLAGS = ImGuiWindowFlags.NoTitleBar
            | ImGuiWindowFlags.NoResize
            | ImGuiWindowFlags.NoMove
            | ImGuiWindowFlags.NoCollapse
            | ImGuiWindowFlags.NoSavedSettings
            | ImGuiWindowFlags.NoNavInputs
            | ImGuiWindowFlags.NoFocusOnAppearing
            | ImGuiWindowFlags.NoBringToFrontOnFocus;

    private static final int PROFILE_PASS_ROWS = 24;

    private static final String PROFILE_PASSES_TOOLTIP =
            "GPU and CPU time of every bgfx view this frame. Needs graphics.profiler = true in cruma.conf.";

    private static final Logger log = LogManager.getLogger(UiSystem.class);

    private @Nullable DebugSceneObject highlighted;
    private long highlightedNanos;

    private final float[] frameHistory = new float[120];
    private int frameHistoryIdx;

    private boolean profileDetails = Boolean.getBoolean("cruma.viewer.benchmark.profile_details");
    private ProfileTab profileTab = initialProfileTab();
    private SceneViewTab sceneTab = initialSceneViewTab();
    private boolean hudVisible = true;
    private boolean hideHudAfterFrameLimit;
    private boolean recordedSoundStarted;
    private boolean loadingShown;
    private boolean revealing;

    private final SceneInspection inspection;
    private final WorkspaceHud workspace;
    private final ViewerWorldMap worldMap;
    private final ViewerExports exports;

    private final UiContext uiContext;
    private final World worldConfig;
    private final Path geodataConfigDirectory;
    private final GeodataWorkspace geodata;
    private final SpawnWorkspace spawns;
    private final AreaWorkspace areas;
    private final ClientFloorBackfill floorBackfill;
    private final @Nullable String namedMap;
    private final boolean lobbyLight;
    private final ImInt lobbyPreset = new ImInt();
    private final NpcWorkspace npcs;
    private final MapEventsPanel mapEvents = new MapEventsPanel();
    private final ScenesPanel scenesPanel = new ScenesPanel();
    private final SceneHourHold sceneHour = new SceneHourHold();
    private final GameSceneStage gameStage;
    private final MapPawnCard mapPawnCard;
    private volatile @Nullable List<Scene> foundScenes;
    private volatile @Nullable List<GameScenes.Scene> foundGameScenes;
    private volatile List<GameScenes.Scene> foundServerScenes = List.of();
    private final EffectsPanel effects = new EffectsPanel();
    private final MapEventChoices mapEventChoices;
    private final AudioSettings audioSettings;
    private final FrameLimitSettings frameLimit;
    private final WindowContext windowContext;
    private final RenderingContext renderingContext;
    private final ViewerRenderBackend renderBackend;
    private final ViewerUiRenderer uiRenderer;
    private final CrumaBranding branding;

    private final SingleWindowImGuiGlfw imGuiImplGlfw =
            new SingleWindowImGuiGlfw(Boolean.getBoolean("cruma.viewer.ui.poll_window_hover"));
    private final FontManager fontManager = new FontManager();
    private final GuideWindow guide;
    private final GuideGeodata guideGeodata = new GuideGeodata();
    private final GuideWorld guideWorld = new GuideWorld();
    private final GuideSpawns guideSpawns = new GuideSpawns();
    private final GuideMapEvents guideMapEvents = new GuideMapEvents();
    private final GuideAreas guideAreas = new GuideAreas();
    private final GuideEffects guideEffects = new GuideEffects();
    private final GuideScenes guideScenes = new GuideScenes();
    private final GuideEdit guideEdit = new GuideEdit();
    private final ShortcutReference shortcuts = new ShortcutReference();
    private final List<Location> locations;
    private long mapEventsRevision = -1;
    private long mapEventSoundsRevision = -1;
    private long serverEventsRevision = -1;
    private long serverEventsSaves;
    private long zoneStateMeshesRevision = -1;
    private long zoneStateFogsRevision = -1;
    private long zoneStateSoundsRevision = -1;
    private long zoneStateVolumesRevision = -1;
    private long zoneStateItemsRevision = -1;

    public UiSystem(
            World worldConfig,
            @Nullable Config.Viewer.Camera cameraConfig,
            UiContext uiContext,
            WindowContext windowContext,
            RenderingContext renderingContext,
            ViewerRenderBackend renderBackend) {
        this(worldConfig, cameraConfig, uiContext, windowContext, renderingContext, renderBackend, null, null);
    }

    public UiSystem(
            World worldConfig,
            @Nullable Config.Viewer.Camera cameraConfig,
            UiContext uiContext,
            WindowContext windowContext,
            RenderingContext renderingContext,
            ViewerRenderBackend renderBackend,
            @Nullable Path configDirectory,
            @Nullable List<String> selectedTiles) {
        this(
                worldConfig,
                cameraConfig,
                uiContext,
                windowContext,
                renderingContext,
                renderBackend,
                configDirectory,
                selectedTiles,
                InterfaceScale.AUTOMATIC);
    }

    public UiSystem(
            World worldConfig,
            @Nullable Config.Viewer.Camera cameraConfig,
            UiContext uiContext,
            WindowContext windowContext,
            RenderingContext renderingContext,
            ViewerRenderBackend renderBackend,
            @Nullable Path configDirectory,
            @Nullable List<String> selectedTiles,
            InterfaceScale interfaceScale) {
        this.worldConfig = worldConfig;
        namedMap = selectedTiles == null ? null : WorldMapCatalog.findStandaloneMap(selectedTiles);
        lobbyLight = !LobbyTimeEnv.files(worldConfig.clientPath(), namedMap).isEmpty();
        geodataConfigDirectory = configDirectory == null
                ? new ConfigLoader(new Options(null, false, false)).getConfigDirectory()
                : configDirectory;
        geodata = new GeodataWorkspace(geodataConfigDirectory, worldConfig.clientPath(), worldConfig.variant());
        spawns = new SpawnWorkspace(
                geodataConfigDirectory, worldConfig.spawns(), worldConfig.clientPath(), worldConfig.variant());
        areas = new AreaWorkspace(spawns.getGround());
        areas.setSpawnOverlay(spawns);
        spawns.setAreaOverlay(areas);
        spawns.setFolderGuard(areas::holdFolder);
        floorBackfill = ClientFloorBackfill.of(worldConfig.clientPath(), worldConfig.variant());
        npcs = new NpcWorkspace(worldConfig.clientPath(), worldConfig.variant());
        npcs.setSpawnLink(spawns);
        geodata.setSpawnLink(spawns);
        this.uiContext = uiContext;
        this.windowContext = windowContext;
        this.renderingContext = renderingContext;
        this.inspection = renderingContext.inspection;
        renderingContext.spawns.wanted = wantsFloors();
        GameSceneStage.useScripts(
                worldConfig.clientPath(),
                worldConfig.spawns() == null ? null : worldConfig.spawns().scriptPath());
        gameStage = uiContext.recording != null
                ? GameSceneStage.forFilm(
                        worldConfig.clientPath(),
                        worldConfig.variant(),
                        worldConfig.rendering().textureQuality(),
                        renderBackend::getSceneEffects)
                : new GameSceneStage(
                        worldConfig.clientPath(),
                        worldConfig.variant(),
                        worldConfig.rendering().textureQuality(),
                        renderBackend::getSceneEffects);
        gameStage.standOn(renderingContext.spawns.floors);
        mapPawnCard = new MapPawnCard(renderingContext.mapPawns);
        this.renderBackend = renderBackend;
        this.uiRenderer = renderBackend.getUiRenderer();
        this.locations = new ArrayList<>(cameraConfig == null ? List.of() : cameraConfig.locations());

        if (windowContext.windowHandle == 0) {
            throw new IllegalStateException("Window must be initialized");
        }

        ImGui.createContext();
        var initialized = false;
        try {
            imGuiImplGlfw.init(windowContext.windowHandle, true);
            imGuiImplGlfw.setInterfaceScale(interfaceScale);
            fontManager.initViewerFonts(ImGui.getIO(), imGuiImplGlfw.getPixelDensity(windowContext.windowHandle));
            uiRenderer.initialize();
            branding = new CrumaBranding(uiRenderer);
            initialized = true;
        } finally {
            if (!initialized) {
                uiRenderer.close();
                imGuiImplGlfw.shutdown();
                ImGui.destroyContext();
            }
        }
        ImGui.styleColorsDark();

        uiContext.rendering.setDefaults(worldConfig.rendering());
        uiContext.rendering.setMusicDefaults(worldConfig.music());
        uiContext.rendering.setSoundDefaults(worldConfig.sound());

        uiContext.geodata.setDefaults();

        Arrays.fill(frameHistory, BUDGET_MS);
        workspace = new WorkspaceHud(
                uiContext,
                renderingContext,
                renderBackend,
                fontManager,
                branding,
                worldConfig.clientPath().toString());
        workspace.setInspectedView(InspectedView.firstOf(mapPawnCard, spawns));
        workspace.setUnderView(this::drawOrthoGrid);
        guide = new GuideWindow(fontManager, this, () -> uiRenderer);
        workspace.setGuide(guide);
        workspace.setGuideTopic(this::guideTopic);
        GuideLibrary.warmUp();
        workspace.setConfigDirectory(geodataConfigDirectory);
        if (namedMap != null) {
            workspace.showNamedMap(namedMap);
            renderingContext.geodata.enabled = false;
            readScenes(worldConfig.clientPath(), namedMap);
            if (uiContext.sceneAtStart != null || uiContext.sceneFromFile != null) {
                readGameScenes(worldConfig.clientPath());
            }
        } else if (!uiContext.effectsOnly) {
            readGameScenes(worldConfig.clientPath());
        }
        windowContext.allowClose = () -> {
            if (workspace.holdWindowClose()) {
                hudVisible = true;
                uiContext.worldMapOpen = false;
                return false;
            }
            var guarded = uiContext.editingPrepared && workspace.guardWindowClose()
                    || geodata.requestLeave(renderingContext.geodata, Leave.CLOSE)
                    || spawns.requestLeave(Leave.CLOSE)
                    || areas.requestLeave(Leave.CLOSE)
                    || npcs.requestLeave(Leave.CLOSE);
            if (guarded) {
                hudVisible = true;
                uiContext.worldMapOpen = false;
            }
            return !guarded;
        };
        worldMap = new ViewerWorldMap(
                worldConfig,
                locations,
                selectedTiles,
                configDirectory == null
                        ? new ConfigLoader(new Options(null, false, false)).getConfigDirectory()
                        : configDirectory,
                uiRenderer,
                fontManager);
        worldMap.setLayers(List.of(
                geodata.getMapLayer(), spawns.getMapLayer(), areas.getMapLayer(), workspace.getEditingMapLayer()));
        workspace.setEditingCheckSources(
                (minX, minY, maxX, maxY) ->
                        spawns.hasDocument() ? spawns.getEditingSpots(minX, minY, maxX, maxY) : null,
                geodata::getRegionModified);
        var exportSettings = ExportSettings.in(new ConfigLoader(new Options(geodataConfigDirectory, false, false)));
        mapEventChoices = MapEventChoices.in(new ConfigLoader(new Options(geodataConfigDirectory, false, false)));
        audioSettings = configDirectory == null || uiContext.recording != null
                ? AudioSettings.session(uiContext.rendering.getAudioValues())
                : AudioSettings.in(
                        new ConfigLoader(new Options(configDirectory, false, false)),
                        uiContext.rendering.getAudioValues());
        frameLimit = configDirectory == null || uiContext.recording != null
                ? FrameLimitSettings.session(uiContext.rendering.frameRateCap)
                : FrameLimitSettings.in(
                        new ConfigLoader(new Options(configDirectory, false, false)), uiContext.rendering.frameRateCap);
        var client = worldConfig.clientPath().toAbsolutePath().normalize();
        mapEventChoices.onChange((changed, state) -> {
            if (changed.equals(client)) {
                uiContext.rendering.gameEventStates = state;
            }
        });
        workspace.setSettingsSaved(() -> {
            var saved = mapEventChoices.of(worldConfig.clientPath());
            if (!saved.equals(uiContext.rendering.gameEventStates)) {
                chooseMapEvents(saved);
            }
        });
        mapEvents.setServerEvents(serverEvents());
        exports = new ViewerExports(worldConfig, locations, worldMap, uiRenderer, exportSettings, mapEventChoices);
        scenesPanel.setExport(
                worldConfig.clientPath(),
                worldConfig.variant(),
                worldConfig.rendering().textureQuality(),
                geodataConfigDirectory,
                worldConfig.spawns() == null ? null : worldConfig.spawns().scriptPath(),
                exportSettings,
                mapEventChoices,
                this::viewerHour,
                uiContext.rendering.timeOfDay::get,
                uiContext.rendering.musicFadeSeconds::get);
        if (!uiContext.effectsOnly && uiContext.recording == null) {
            var editing = renderingContext.getEditingSettings();
            var folder = uiContext.scenesFolder;
            scenesPanel.setDirector(
                    renderingContext.camera,
                    exportSettings::getVideoRate,
                    client.getFileName() == null ? "" : client.getFileName().toString(),
                    worldConfig.variant().getKey(),
                    folder == null || editing == null
                            ? null
                            : SceneFileStore.of(folder, editing.recoveryDirectory(), client));
            scenesPanel.setDirectorWorld(
                    worldConfig.clientPath(),
                    worldConfig.variant(),
                    worldConfig.rendering().textureQuality(),
                    renderingContext.spawns,
                    renderBackend::getSceneEffects,
                    renderingContext::holdDoors,
                    renderingContext.sceneAudio::follow);
            scenesPanel.setVideoShape(
                    () -> exportSettings.getVideoWidth() / (double) exportSettings.getVideoHeight(),
                    renderingContext.camera::getLens);
            scenesPanel.setDirectorMap(namedMap);
            scenesPanel.setDirectorInto(
                    exportSettings,
                    map -> map.equalsIgnoreCase(namedMap) && renderingContext.doesEditingNeedSave(),
                    this::rereadScenes);
            workspace.showDirector(scenesPanel::isDirectorOpen, scenesPanel::drawDirectorTray);
        }
        workspace.setExportSettings(exportSettings);
        effects.setDialogs(fontManager, exportSettings);
        if (Boolean.getBoolean("cruma.viewer.benchmark.tune_layer")) {
            workspace.tools.open(Tool.SCENE);
        }
        if (Boolean.getBoolean("cruma.viewer.benchmark.profile_layer")) {
            workspace.tools.open(Tool.PERFORMANCE);
        }
        if (Boolean.getBoolean("cruma.viewer.benchmark.geodata_layer")) {
            workspace.tools.open(Tool.GEODATA);
        }
        if (Boolean.getBoolean("cruma.viewer.benchmark.npcs_layer")) {
            workspace.tools.open(Tool.NPCS);
        }
        if (Boolean.getBoolean("cruma.viewer.benchmark.spawns_layer")) {
            workspace.tools.open(Tool.SPAWNS);
        }
        if (Boolean.getBoolean("cruma.viewer.benchmark.events_layer")) {
            workspace.tools.open(Tool.EVENTS);
        }
        if (uiContext.effectsOnly) {
            workspace.showEffectsOnly();
            renderingContext.geodata.enabled = false;
        }
        effects.withoutWorld(uiContext.effectsOnly);
        var effect = java.lang.System.getProperty("cruma.viewer.benchmark.effect", "");
        if (!effect.isBlank()) {
            workspace.tools.open(Tool.EFFECTS);
            effects.chooseWhenListed(effect);
        }
    }

    private enum SceneViewTab {
        SCENE("World", Glyph.GLOBE),
        RENDER("Lighting", Glyph.SUN),
        ATMOSPHERE("Atmosphere", Glyph.CLOUD),
        EFFECTS("Effects", Glyph.SPARKLES),
        WATER("Water", Glyph.WAVES),
        MUSIC("Audio", Glyph.AUDIO);

        private final String label;
        private final Glyph icon;

        SceneViewTab(String label, Glyph icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    private enum ProfileTab {
        FRAME,
        VISIBILITY,
        RENDER,
        PASSES
    }

    static float loadingProgress(int loaded, int total) {
        if (total <= 0) {
            return 0f;
        }
        return Math.clamp(loaded / (float) total, 0f, 1f);
    }

    static String loadingProgressLabel(int loaded, int total) {
        var loadedText = String.format(Locale.ROOT, "%,d", Math.max(0, loaded));
        var totalText = String.format(Locale.ROOT, "%,d", Math.max(0, total));
        var paddedLoaded = " ".repeat(Math.max(0, totalText.length() - loadedText.length())) + loadedText;
        return String.format(
                Locale.ROOT,
                "PROGRESS %s / %s TILES (%3d%%)",
                paddedLoaded,
                totalText,
                Math.round(loadingProgress(loaded, total) * 100f));
    }

    static String formatLoadingDuration(long totalSeconds) {
        var bounded = Math.max(0L, totalSeconds);
        var hours = bounded / 3_600L;
        var minutes = bounded % 3_600L / 60L;
        var seconds = bounded % 60L;
        return hours == 0L
                ? String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
                : String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
    }

    static boolean picksEffects(boolean editing) {
        return !editing;
    }

    public WorkspaceHud getWorkspace() {
        return workspace;
    }

    public SpawnWorkspace getSpawns() {
        return spawns;
    }

    public EffectsPanel getEffects() {
        return effects;
    }

    @Override
    public void stop(WindowContext windowContext) {
        guide.release();
        scenesPanel.close();
        gameStage.close();
        effects.dispose();
        workspace.close();
        geodata.close();
        spawns.close();
        areas.close();
        floorBackfill.close();
        npcs.close();
        exports.close();
        try {
            uiRenderer.close();
        } finally {
            imGuiImplGlfw.shutdown();
            ImGui.destroyContext();
        }
    }

    @Override
    public void frameBegin(long frameTime) {
        renderingContext.tickEditingRecovery();
        prepareFrame();
        ImGui.newFrame();
        if (workspace.getMode() == Mode.EDITING || workspace.isDirectorShown()) {
            ImGuizmo.beginFrame();
            renderingContext.editorInputCaptured = false;
        }

        geodata.tick(renderingContext.geodata, renderingContext.camera, renderBackend);
        renderingContext.spawns.wanted = wantsFloors();
        var eye = renderingContext.camera.getPosition();
        floorBackfill.tick(
                renderingContext.spawns.wanted,
                renderingContext.tiles,
                renderingContext.spawns.floors,
                eye.getX(),
                eye.getY());
        spawns.tick(renderingContext.geodata.path, renderingContext.spawns.floors);
        areas.tick(spawns.getScriptFolder(), renderingContext.spawns.floors);
        npcs.tick(spawns.getScriptFolder(), uiRenderer);
        spawns.setClientCatalog(npcs.getCatalog());
        spawns.setEditorNpc(npcs.openNpc());
        var savedNpcs = npcs.consumeSaved();
        if (savedNpcs != null) {
            spawns.onNpcDataSaved(savedNpcs);
        }
        renderingWindow(frameTime);
        if (!hudVisible || !workspace.tools.isOpen(Tool.GEODATA)) {
            geodata.cancelInteraction();
        }
        drawGeodataGuard();
    }

    public SpawnAccess getSpawnAccess() {
        return spawns.createAccess(npcs.getSource(), npcs::isHeld);
    }

    public AreaAccess getAreaAccess() {
        return areas.createAccess();
    }

    public void closeEditingSession() {
        workspace.closeEditingSession();
    }

    public boolean doesGeodataHoldEdits() {
        return geodata.holdsEdits();
    }

    public void workspaceMode(Mode mode) {
        workspace.setMode(mode);
    }

    public void discardEditing() throws IOException {
        workspace.discardEditing();
    }

    public void prepareFrame() {
        imGuiImplGlfw.newFrame();
        var io = ImGui.getIO();
        var recording = uiContext.recording;
        if (recording != null) {
            var scale = recording.getInterfaceScale();
            io.setDisplaySize(recording.getWidth() / scale, recording.getHeight() / scale);
            io.setDisplayFramebufferScale(scale, scale);
        }
        updateFonts(io);
        uiRenderer.newFrame();
    }

    @Override
    public @Nullable String getOpenTool() {
        if (workspace.getMode() == Mode.EDITING && workspace.tools.isOpen(Tool.OBJECT)) {
            return "edit";
        }
        for (var tool : Tool.values()) {
            if (workspace.tools.isOpen(tool)) {
                return tool.name().toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    @Override
    public @Nullable String getOpenTab() {
        if ("edit".equals(getOpenTool())) {
            var panel = workspace.getEditingPanel();
            return panel == null ? null : panel.getGuideTab();
        }
        if (workspace.tools.isOpen(Tool.GEODATA) && renderingContext.geodata.path != null) {
            return geodata.getTool().name().toLowerCase(Locale.ROOT);
        }
        if (workspace.tools.isOpen(Tool.EFFECTS) && effects.areSkillsShown()) {
            return "skills";
        }
        if (workspace.tools.isOpen(Tool.SCENES) && scenesPanel.isDirectorOpen()) {
            return "director";
        }
        return null;
    }

    @Override
    public boolean hasTool(String tool) {
        var found = tool(tool);
        return found != null && workspace.tools.getShown().contains(found);
    }

    @Override
    public @Nullable String getToolHidden(String tool) {
        var found = tool(tool);
        if (found == null || !workspace.tools.isHidden(found)) {
            return null;
        }
        if (uiContext.effectsOnly) {
            return EFFECTS_WITHOUT_WORLD;
        }
        return workspace.getMode() == Mode.EDITING ? LEAVE_EDIT : null;
    }

    @Override
    public void openTool(String tool) {
        var found = tool(tool);
        if (found != null && !workspace.tools.isOpen(found)) {
            workspace.tools.open(found);
        }
        uiContext.worldMapOpen = false;
    }

    @Override
    public Snapshot getView() {
        return renderingContext.camera.snapshot();
    }

    @Override
    public void restoreView(Snapshot view) {
        renderingContext.camera.restoreSnapshot(view);
    }

    @Override
    public @Nullable Geodata getGeodata() {
        return namedMap != null || uiContext.effectsOnly ? null : guideGeodata;
    }

    @Override
    public OpenWorld getWorld() {
        return guideWorld;
    }

    @Override
    public @Nullable Spawns getNpcSpawns() {
        return namedMap != null || uiContext.effectsOnly ? null : guideSpawns;
    }

    @Override
    public @Nullable Areas getAreas() {
        return namedMap != null || uiContext.effectsOnly ? null : guideAreas;
    }

    @Override
    public @Nullable MapEvents getMapEvents() {
        return namedMap != null || uiContext.effectsOnly ? null : guideMapEvents;
    }

    @Override
    public @Nullable Effects getEffectsTool() {
        return guideEffects;
    }

    @Override
    public @Nullable Scenes getScenes() {
        return guideScenes;
    }

    @Override
    public @Nullable Edit getEdit() {
        return uiContext.effectsOnly ? null : guideEdit;
    }

    @Override
    public @Nullable String getNpcExportUnavailable() {
        if (worldConfig.clientPath() == null) {
            return "Export NPC needs a game client. Open one from the start menu first.";
        }
        return workspace.getMode() == Mode.EDITING ? LEAVE_EDIT : null;
    }

    @Override
    public void openNpcExport() {
        uiContext.exportNpcRequested = true;
    }

    @Override
    public void frameEnd(long frameTime) {
        if (!ImGui.isAnyItemActive()) {
            audioSettings.follow(uiContext.rendering.getAudioValues());
        }
        frameLimit.follow(uiContext.rendering.frameRateCap);
        if (!uiContext.rendering.loading.active) {
            updateGeodataFocus();
        }
        if (hudVisible && !uiContext.rendering.loading.active) {
            drawCutCircle();
            drawMapEvents();
            if (workspace.isDirectorShown() && !uiContext.worldMapOpen) {
                scenesPanel.drawDirectorMarks(
                        renderingContext.spawns.marks, ImGui.getIO().getDisplaySizeX());
            }
            if (onWorldTiles()) {
                drawGeodataPicker();
                drawSpawns();
                drawAreas();
            }
            effects.drawViewport(
                    new Viewport(
                            workspace.getViewportLeft(),
                            workspace.getViewportTop(),
                            workspace.getViewportRight(),
                            workspace.getViewportBottom()),
                    workspace.tools.isOpen(Tool.EFFECTS) && !uiContext.worldMapOpen,
                    workspace.helpOpen);
            drawObjectPicker();
        } else {
            renderingContext.spawns.marks.clear();
            renderingContext.spawns.editorLines.clear();
            renderingContext.spawns.editorEdges.clear();
            renderingContext.spawns.editorLight.clear();
        }
        ScenesPanel.drawFade(renderingContext.scenes);
        var free = hudVisible
                ? new Viewport(
                        workspace.getBottomOverlayLeft(),
                        workspace.getViewportTop(),
                        workspace.getViewportRight(),
                        workspace.getViewportBottom())
                : null;
        scenesPanel.pollDirectorPictures(uiRenderer);
        var moment = scenesPanel.getDirectorOverlay();
        if (moment != null) {
            gameStage.drawScreen(scenesPanel.getDirectorFilm(), moment, uiRenderer, fontManager, free);
        } else {
            gameStage.drawScreen(renderingContext.gameScenes, uiRenderer, fontManager, free);
        }
        if (uiContext.recording != null) {
            uiContext.recording.setPicturesRead(gameStage.arePicturesRead());
        }
        if (hudVisible && !uiContext.rendering.loading.active) {
            scenesPanel.drawViewport(
                    renderingContext.scenes.isPlaying() || renderingContext.gameScenes.isPlaying(),
                    workspace.isDirectorShown(),
                    new Viewport(
                            workspace.getViewportLeft(),
                            workspace.getViewportTop(),
                            workspace.getViewportRight(),
                            workspace.getViewportBottom()),
                    workspace.helpOpen);
        }
        if (!uiContext.rendering.loading.active
                && !uiContext.dialogOpen
                && !workspace.isRecoveryPending()
                && !worldMap.isEditingLocation()) {
            workspace.handlePauseKey();
        }
        drawLoadingReveal();
        ImGui.render();
    }

    public FrameHistoryStats getFrameHistoryStats() {
        var sorted = Arrays.copyOf(frameHistory, frameHistory.length);
        Arrays.sort(sorted);
        var p95Index = Math.min(sorted.length - 1, Math.max(0, (int) Math.ceil(sorted.length * 0.95) - 1));
        var overBudget = 0;
        for (var sample : frameHistory) {
            if (sample > BUDGET_MS) {
                overBudget++;
            }
        }
        return new FrameHistoryStats(sorted[p95Index], overBudget);
    }

    public void resetSceneSettings() {
        if (workspace.getComparison().isActive()) {
            workspace.getComparison().finish(renderingContext.playback);
        }
        SceneSettingsDefaults.restore(uiContext.rendering);
        SceneSettingsDefaults.restore(renderingContext.geodata);
    }

    public void playScene(GameScenes.Scene scene) {
        renderingContext.gameScenes.play(scene, sceneGround());
    }

    public MapEventCatalog getMapEventCatalog() {
        refreshMapEvents();
        return mapEvents.getCatalog();
    }

    public Map<String, List<Integer>> getZoneStateMaps() {
        refreshMapEvents();
        return mapEvents.getZoneStateMaps();
    }

    public Map<String, Integer> getZoneStateDefaults() {
        refreshMapEvents();
        return mapEvents.getZoneStateDefaults();
    }

    public void chooseMapEvents(MapEventState state) {
        mapEventChoices.save(worldConfig.clientPath(), state);
    }

    public List<Scene> getLobbyScenes() {
        return scenesPanel.getScenes();
    }

    public void setAgent(AgentRuntime agent) {
        workspace.showAgent(agent, this::flyTo);
    }

    private void updateFonts(ImGuiIO io) {
        if (io.getDisplaySizeX() > 0
                && io.getDisplaySizeY() > 0
                && (fontManager.updateViewerScale(
                                io, Math.max(io.getDisplayFramebufferScaleX(), io.getDisplayFramebufferScaleY()))
                        || fontManager.updateLetters(io))) {
            uiRenderer.refreshFontAtlas();
        }
    }

    private void drawGeodataGuard() {
        if (uiContext.returnToMenuRequested && geodata.requestLeave(renderingContext.geodata, Leave.MENU)) {
            uiContext.returnToMenuRequested = false;
        }
        if (uiContext.returnToMenuRequested && spawns.requestLeave(Leave.MENU)) {
            uiContext.returnToMenuRequested = false;
        }
        if (uiContext.returnToMenuRequested && areas.requestLeave(Leave.MENU)) {
            uiContext.returnToMenuRequested = false;
        }
        if (uiContext.returnToMenuRequested && npcs.requestLeave(Leave.MENU)) {
            uiContext.returnToMenuRequested = false;
        }
        pushHudTheme();
        geodata.drawGuard(renderingContext.geodata);
        spawns.drawGuard(fontManager.getMonoFont());
        areas.drawGuard(fontManager.getMonoFont());
        npcs.drawGuard(fontManager.getMonoFont());
        popHudTheme();
        var geodataLeave = geodata.consumeApprovedLeave();
        if (geodataLeave == Leave.CLOSE) {
            windowContext.closeApproved = !spawns.requestLeave(Leave.CLOSE)
                    && !areas.requestLeave(Leave.CLOSE)
                    && !npcs.requestLeave(Leave.CLOSE);
        } else if (geodataLeave == Leave.MENU) {
            uiContext.returnToMenuRequested = !spawns.requestLeave(Leave.MENU)
                    && !areas.requestLeave(Leave.MENU)
                    && !npcs.requestLeave(Leave.MENU);
        }
        var spawnLeave = spawns.consumeApprovedLeave();
        if (spawnLeave == Leave.CLOSE) {
            windowContext.closeApproved = !areas.requestLeave(Leave.CLOSE) && !npcs.requestLeave(Leave.CLOSE);
        } else if (spawnLeave == Leave.MENU) {
            uiContext.returnToMenuRequested = !areas.requestLeave(Leave.MENU) && !npcs.requestLeave(Leave.MENU);
        }
        var areaLeave = areas.consumeApprovedLeave();
        if (areaLeave == Leave.CLOSE) {
            windowContext.closeApproved = !npcs.requestLeave(Leave.CLOSE);
        } else if (areaLeave == Leave.MENU) {
            uiContext.returnToMenuRequested = !npcs.requestLeave(Leave.MENU);
        }
        var npcLeave = npcs.consumeApprovedLeave();
        if (npcLeave == Leave.CLOSE) {
            windowContext.closeApproved = true;
        } else if (npcLeave == Leave.MENU) {
            uiContext.returnToMenuRequested = true;
        }
    }

    private void drawLoadingReveal() {
        if (!revealing) {
            return;
        }
        var leaving = Motion.since(LOADING_REVEAL, .8f);
        if (leaving >= 1) {
            revealing = false;
            return;
        }
        pushHudTheme();
        loadingScreen(Math.max(leaving, .001f));
        popHudTheme();
    }

    private void renderingWindow(long frameTime) {
        var recording = uiContext.recording;
        if (recording != null) {
            mixRecordedScene(recording);
        }
        if (uiContext.rendering.loading.active) {
            sampleTelemetry(frameTime);
            if (recording == null) {
                pushHudTheme();
                loadingScreen(0);
                popHudTheme();
                loadingShown = true;
            }
            return;
        }
        if (!revealing) {
            branding.releaseLoadingScene();
        }
        if (loadingShown) {
            loadingShown = false;
            Motion.restart(LOADING_REVEAL);
            revealing = Motion.isEnabled();
        }
        var found = foundScenes;
        if (found != null) {
            foundScenes = null;
            scenesPanel.setScenes(namedMap, found);
            workspace.showScenes(found.size());
        }
        var foundCuts = foundGameScenes;
        if (foundCuts != null) {
            foundGameScenes = null;
            scenesPanel.setGameScenes(foundCuts);
            scenesPanel.setServerScenes(foundServerScenes);
            workspace.showScenes(foundCuts.size() + foundServerScenes.size());
            playSceneAtStart();
        }
        drawDirectorHandle();
        var keys = windowContext.keyboard;
        var mapEscape = uiContext.worldMapOpen && keys.isClicked(Key.ESCAPE);
        if (mapEscape && worldMap.dismiss()) {
            uiContext.worldMapOpen = false;
        }
        if (hideHudAfterFrameLimit) {
            hideHudAfterFrameLimit = false;
            hudVisible = false;
        }
        uiContext.dialogOpen = exports.isOpen() || workspace.isSettingsOpen();
        if (!workspace.isRecoveryPending()
                && !renderingContext.isEditingOperationActive()
                && !ImGui.getIO().getWantTextInput()
                && !worldMap.isEditingLocation()
                && !uiContext.dialogOpen) {
            var geodataOpen = workspace.tools.isOpen(Tool.GEODATA) && hudVisible && !uiContext.worldMapOpen;
            var spawnsOpen = workspace.tools.isOpen(Tool.SPAWNS) && hudVisible && !uiContext.worldMapOpen;
            var areasOpen = workspace.tools.isOpen(Tool.AREAS) && hudVisible && !uiContext.worldMapOpen;
            if (onWorldTiles()) {
                geodata.handleKeys(renderingContext.geodata, renderingContext.camera, keys, geodataOpen);
                spawns.handleKeys(keys, spawnsOpen && !areasOpen);
                if (workspace.getMode() != Mode.EDITING) {
                    areas.handleKeys(keys, areasOpen);
                }
            }
            var npcsOpen = workspace.tools.isOpen(Tool.NPCS) && hudVisible && !uiContext.worldMapOpen;
            npcs.handleKeys(keys, npcsOpen);
            var eventsOpen = workspace.tools.isOpen(Tool.EVENTS) && hudVisible && !uiContext.worldMapOpen;
            var stepped = mapEvents.handleKeys(
                    keys, renderingContext.camera, eventsOpen, uiContext.rendering.gameEventStates);
            if (!stepped.equals(uiContext.rendering.gameEventStates)) {
                mapEventChoices.save(worldConfig.clientPath(), stepped);
            }
            var directorKeys = workspace.isDirectorShown() && hudVisible && !uiContext.worldMapOpen;
            if (directorKeys) {
                scenesPanel.handleDirectorKeys(keys);
            }
            if (keys.isClicked(Key.ESCAPE) && !mapEscape) {
                if (workspace.closeFrameLimit()) {
                } else if (renderingContext.scenes.isPlaying()) {
                    renderingContext.scenes.stop();
                } else if (renderingContext.gameScenes.isPlaying()) {
                    renderingContext.gameScenes.stop();
                } else if (geodata.hasStroke()) {
                    geodata.cancelInteraction();
                } else if (geodataOpen && geodata.handleEscape(renderingContext.geodata)) {
                } else if (areasOpen && areas.handleEscape()) {
                } else if (spawnsOpen && spawns.handleEscape()) {
                } else if (npcsOpen && npcs.handleEscape()) {
                } else if (eventsOpen && mapEvents.handleEscape()) {
                } else if (workspace.tools.isOpen(Tool.EFFECTS)
                        && hudVisible
                        && !uiContext.worldMapOpen
                        && effects.handleEscape(renderBackend)) {
                } else if (!workspace.helpOpen && workspace.handleEditingEscape()) {
                } else if (directorKeys && scenesPanel.handleDirectorEscape()) {
                } else if (workspace.isGuideOpen()) {
                    workspace.closeGuide();
                } else if (workspace.helpOpen) {
                    workspace.helpOpen = false;
                } else if (workspace.tools.isOpen()) {
                    workspace.tools.close();
                } else if (workspace.getComparison().isActive()) {
                    workspace.getComparison().finish(renderingContext.playback);
                } else {
                    workspace.select(null, false);
                }
            }
            if (keys.isClicked(Key.M) && !workspace.measureTookMapKey()) {
                workspace.toggleMap();
                hudVisible = true;
            }
            if (keys.isClicked(Key.H) && workspace.getMode() != Mode.EDITING) {
                if (hudVisible && workspace.closeFrameLimit()) {
                    hideHudAfterFrameLimit = true;
                } else {
                    hudVisible = !hudVisible;
                }
            }
            if (keys.isClicked(Key.F1)) {
                if (keys.isPressed(Key.SHIFT)) {
                    GuideRequests.toggleTopic();
                } else {
                    workspace.helpOpen = !workspace.helpOpen;
                    if (workspace.helpOpen) {
                        workspace.closeGuide();
                    }
                }
                uiContext.worldMapOpen = false;
                hudVisible = true;
            }
            if (keys.isClicked(Key.F2)) {
                workspace.cycleViewerMode();
                hudVisible = true;
            }
            if (keys.isClicked(Key.F3)) {
                workspace.toggleTool(Tool.SCENE);
                hudVisible = true;
            }
            if (keys.isClicked(Key.F4)) {
                workspace.toggleTool(Tool.PERFORMANCE);
                hudVisible = true;
            }
            if (keys.isClicked(Key.TAB) && !ImGui.getIO().getWantCaptureKeyboard()) {
                workspace.toggleTool(Tool.OBJECT);
                hudVisible = true;
            }
        }
        var frameMs = sampleTelemetry(frameTime);
        var sceneSeconds = renderingContext.playback.getLastDelta();
        renderingContext.scenes.update(sceneSeconds);
        gameStage.prepare(renderingContext.gameScenes);
        renderingContext.gameScenes.update(sceneSeconds);
        renderingContext.moveSceneDoors(renderBackend);
        var playing = renderingContext.gameScenes.getScene();
        var film = playing != null ? playing : scenesPanel.getDirectorFilm();
        renderingContext.blackBackdrop = film != null && ServerScenes.isClosedHall(film);
        renderingContext.skyHidden = effects.hidesSky() || renderingContext.blackBackdrop;
        gameStage.update(renderingContext.gameScenes, renderingContext.spawns, sceneSeconds);
        scenesPanel.updateDirector(
                frameTime / 1000.0,
                workspace.isDirectorShown(),
                !renderingContext.scenes.isPlaying() && !renderingContext.gameScenes.isPlaying());
        uiContext.rendering.timeOfDay.set(
                sceneHour.getHour(scenesPanel.getDirectorHour(), uiContext.rendering.timeOfDay.get()));
        renderingContext.heldPostEffects = scenesPanel.getDirectorPosts();
        if (scenesPanel.takeDirectorGrown()) {
            workspace.growDirector();
        }
        uiContext.directorShown = workspace.isDirectorShown() && hudVisible;
        uiContext.directorPlaying = scenesPanel.isDirectorPlaying();
        uiContext.sceneHoldsCamera = scenesPanel.doesDirectorHoldCamera() || renderingContext.gameScenes.isPlaying();
        if (renderingContext.geodata.sourceChangePending) {
            hudVisible = true;
        }
        if (recording != null) {
            hudVisible = false;
        }
        if (!hudVisible) {
            return;
        }
        pushHudTheme();
        if (windowContext.screenshot) {
            workspace.holdShutter();
        }
        if (!windowContext.droppedFiles.isEmpty()) {
            workspace.dropFiles(List.copyOf(windowContext.droppedFiles));
            windowContext.droppedFiles.clear();
        }
        GeodataWidgets.keepHintsRightOf(
                workspace.isGuideOpen() && !uiContext.worldMapOpen ? workspace.getOverlayLeft() : 0);
        workspace.draw();
        if (workspace.takeWindowCloseRequest() && windowContext.allowClose.getAsBoolean()) {
            windowContext.closeApproved = true;
        }
        if (uiContext.editingPrepared) {
            if (workspace.consumeApprovedWindowClose()) {
                windowContext.closeApproved = !geodata.requestLeave(renderingContext.geodata, Leave.CLOSE)
                        && !spawns.requestLeave(Leave.CLOSE)
                        && !areas.requestLeave(Leave.CLOSE)
                        && !npcs.requestLeave(Leave.CLOSE);
            }
        }
        if (workspace.tools.isOpen(Tool.SCENE)) {
            drawControlDrawer();
        }
        if (workspace.tools.isOpen(Tool.PERFORMANCE)) {
            drawTelemetryPanel(frameTime, frameMs);
        }
        renderingContext.geodata.toolsOpen = workspace.tools.isOpen(Tool.GEODATA);
        if (renderingContext.geodata.toolsOpen) {
            workspace.beginToolWindow("##geodataTools", "Geodata");
            geodata.drawPanel(renderingContext.geodata, renderingContext.camera, renderBackend);
            ImGui.end();
        }
        if (workspace.tools.isOpen(Tool.SPAWNS)) {
            workspace.beginToolWindow("##spawnTools", "NPC Spawns");
            spawns.drawPanel(renderingContext.camera, renderBackend);
            ImGui.end();
            if (spawns.consumeGeodataRequest()) {
                workspace.tools.open(Tool.GEODATA);
            }
        }
        if (workspace.tools.isOpen(Tool.AREAS)) {
            workspace.beginToolWindow("##areaTools", "Areas");
            areas.drawPanel(renderingContext.camera, renderBackend, fontManager.getMonoFont());
            ImGui.end();
        }
        var areaFolder = areas.consumeFolderRequest();
        if (areaFolder != null && !spawns.openScriptFolder(areaFolder)) {
            areas.setMessage("NPC Spawns has unsaved changes. Save or undo them there before opening another folder:"
                    + " the two tools share one.");
        }
        if (workspace.tools.isOpen(Tool.NPCS)) {
            workspace.beginToolWindow("##npcTools", "NPCs");
            npcs.drawPanel();
            ImGui.end();
        }
        if (workspace.tools.isOpen(Tool.EVENTS)) {
            refreshMapEvents();
            workspace.beginToolWindow("##mapEventTools", "Map events");
            var chosen = mapEvents.drawPanel(
                    uiContext.rendering.gameEventStates, renderingContext.camera, uiContext.rendering.getTimeOfDay());
            if (!chosen.equals(uiContext.rendering.gameEventStates)) {
                mapEventChoices.save(worldConfig.clientPath(), chosen);
            }
            var hour = mapEvents.consumeRequestedHour();
            if (!Float.isNaN(hour)) {
                uiContext.rendering.timeOfDay.set(hour);
            }
            ImGui.end();
        }
        workspace.drawAgentPanel();
        if (workspace.tools.isOpen(Tool.SCENES)) {
            workspace.beginToolWindow("##sceneTools", "Scenes");
            scenesPanel.drawPanel(
                    renderingContext.scenes, renderingContext.gameScenes, renderingContext.tiles, sceneGround());
            ImGui.end();
            var opening = scenesPanel.takeOpening();
            if (opening != null) {
                openScene(opening, scenesPanel.takeOpeningCopy());
            }
            var reloading = scenesPanel.takeDirectorReload();
            if (reloading != null) {
                openSceneFile(reloading);
            }
        }
        if (workspace.tools.isOpen(Tool.EFFECTS)) {
            var editing = renderingContext.getEditingSettings();
            effects.setEditsFolder(editing == null ? null : editing.recoveryDirectory());
            effects.setClient(
                    worldConfig.clientPath(), worldConfig.rendering().textureQuality(), worldConfig.variant());
            effects.setCharacters(spawns.getModels(), npcs.getCatalog(), renderingContext.spawns);
            workspace.beginToolWindow("##effectTools", "Effects");
            effects.drawPanel(renderingContext.camera, renderBackend, renderingContext.playback);
            ImGui.end();
        } else if (workspace.tools.isOpen()) {
            effects.playOn(renderingContext.camera, renderBackend, renderingContext.playback);
        } else {
            effects.close(renderBackend);
        }
        var npcToEdit = spawns.consumeNpcEditRequest();
        if (npcToEdit != null) {
            workspace.tools.open(Tool.NPCS);
            npcs.select(npcToEdit);
        }
        var npcSpawns = npcs.consumeSpawnRequest();
        if (npcSpawns != null) {
            workspace.tools.open(Tool.SPAWNS);
            if (!npcSpawns.isEmpty()) {
                spawns.findNpc(npcSpawns);
            }
        }
        renderingContext.geodata.showComparison(geodata.getComparison(renderingContext.geodata));
        takeGuideRequest();
        if (workspace.isGuideOpen() && !uiContext.worldMapOpen) {
            var left = workspace.getGuideLeft();
            guide.placeAt(
                    left,
                    workspace.getViewportTop() + 8f,
                    workspace.getLeftOverlayBottom(),
                    workspace.getViewportRight() - left);
        }
        if (workspace.helpOpen) {
            drawShortcutHelpPanel();
        }
        if (uiContext.worldMapOpen) {
            var action = worldMap.draw(renderingContext.camera, renderingContext.playback.isComparisonLocked());
            if (action != null) {
                switch (action.command()) {
                    case CLOSE -> uiContext.worldMapOpen = false;
                    case MENU -> uiContext.returnToMenuRequested = !workspace.guardReturnToMenu();
                    case MOVE -> navigateTo(action.destination());
                    case EXPORT_AREA -> {
                        uiContext.worldMapOpen = false;
                        exports.openArea(
                                action.destination().x(), action.destination().y(), viewerHour());
                    }
                    case EXPORT_TILE -> {
                        uiContext.worldMapOpen = false;
                        exports.openTile(action.destination().name(), viewerHour());
                    }
                }
            }
        }
        if (!uiContext.worldMapOpen) {
            worldMap.cancelCreation();
        }
        if (!workspace.isSettingsOpen()) {
            openRequestedExports();
        }
        exports.draw(fontManager);
        guide.drawPlaced();
        popHudTheme();
    }

    private void openRequestedExports() {
        if (uiContext.exportHereRequested) {
            uiContext.exportHereRequested = false;
            if (namedMap != null) {
                exports.openMap(namedMap, viewerHour());
            } else {
                var here = renderingContext.camera.getWorldFocusPosition();
                exports.openArea(here.getX(), here.getY(), viewerHour());
            }
        }
        if (uiContext.exportNpcRequested) {
            uiContext.exportNpcRequested = false;
            exports.openNpcList();
        }
        var npcToExport = spawns.consumeNpcExportRequest();
        if (npcToExport < 0) {
            npcToExport = npcs.consumeExportRequest();
        }
        if (npcToExport >= 0) {
            exports.openNpc(npcToExport);
        }
    }

    private void loadingScreen(float leaving) {
        var r = uiContext.rendering;
        var vp = ImGui.getMainViewport();
        ImGui.setNextWindowPos(vp.getWorkPosX(), vp.getWorkPosY());
        ImGui.setNextWindowSize(vp.getWorkSizeX(), vp.getWorkSizeY());
        ImGui.pushStyleColor(ImGuiCol.WindowBg, ImColor.rgb(16, 23, 30));
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0f);
        ImGui.begin(
                "##worldLoading",
                DRAWER_FLAGS | ImGuiWindowFlags.NoScrollbar | (leaving > 0 ? ImGuiWindowFlags.NoInputs : 0));
        if (leaving > 0) {
            imgui.internal.ImGui.bringWindowToDisplayFront(imgui.internal.ImGui.getCurrentWindow());
            var gone = Motion.easeInOut(leaving);
            Motion.fade(1 - gone, 1 - Motion.easeOut(leaving), 0, 0, 1 + .05f * gone, 0);
        }
        var towerHeight = Math.min(
                LOADING_SCENE_HEIGHT,
                Math.min(
                        Math.max(80f, vp.getWorkSizeY() - 272f - LOADING_UNDER_ROOM),
                        (vp.getWorkSizeX() - 48f) / 2.4f));
        var center = vp.getWorkSizeX() * .5f;
        var titleY = Math.max(24f, (vp.getWorkSizeY() - 138f) * .5f);
        var towerY = Math.min(
                titleY + LOADING_TITLE_ROOM,
                Math.max(24f + LOADING_TITLE_ROOM, vp.getWorkSizeY() - towerHeight - 134f - LOADING_UNDER_ROOM));
        titleY = towerY - LOADING_TITLE_ROOM;
        var indexing = r.loading.stage == WorldLoadStage.INDEXING;
        var loaded = indexing ? 0f : loadingProgress(r.loading.tilesLoaded, r.loading.tilesTotal);
        var progress = leaving > 0 ? 1 : Motion.follow(LOADING_FILL, loaded, .45f);
        branding.drawLoadingBackground(
                ImGui.getWindowDrawList(),
                vp.getWorkPosX(),
                vp.getWorkPosY(),
                vp.getWorkPosX() + vp.getWorkSizeX(),
                vp.getWorkPosY() + vp.getWorkSizeY(),
                vp.getWorkPosX() + center,
                vp.getWorkPosY() + towerY + towerHeight,
                towerHeight);
        ImGui.pushFont(fontManager.getHeadingFont(32), 32f);
        ImGui.setCursorPos(center - ImGui.calcTextSizeX("Cruma") * .5f, titleY);
        ImGui.textUnformatted("Cruma");
        ImGui.popFont();
        branding.useClient(worldConfig.clientPath());
        branding.drawLoadingTower(
                ImGui.getWindowDrawList(), vp.getWorkPosX() + center, vp.getWorkPosY() + towerY, towerHeight, progress);
        var stage = loadingStageTitle(r.loading.stage)
                + (indexing
                        ? ".".repeat(1 + (int) ((java.lang.System.nanoTime() / 450_000_000L) % 3))
                        : r.loading.tilesTotal > 0
                                ? " "
                                        + Math.round(progress * 100f)
                                        + "% ("
                                        + Math.clamp(r.loading.tilesLoaded, 0, r.loading.tilesTotal)
                                        + "/"
                                        + r.loading.tilesTotal
                                        + ")"
                                : "");
        ImGui.pushFont(fontManager.getTextFont(16), 16f);
        ImGui.setCursorPos(center - ImGui.calcTextSizeX(stage) * .5f, towerY + towerHeight + LOADING_UNDER_ROOM + 28);
        ImGui.textUnformatted(stage);
        ImGui.popFont();
        ImGui.pushFont(fontManager.getTextFont(14), 14f);
        var timing = loadingTiming(r);
        ImGui.setCursorPos(center - ImGui.calcTextSizeX(timing) * .5f, towerY + towerHeight + LOADING_UNDER_ROOM + 56);
        ImGui.textColored(HUD_TEXT_DIM, timing);
        ImGui.popFont();
        ImGui.setCursorPos(Math.max(12f, center - 91f), vp.getWorkSizeY() - 52f);
        if (HudWidgets.button("loadingBackToMenu", null, "Back to main menu", 182, 32, false, true)) {
            uiContext.returnToMenuRequested = true;
        }
        ImGui.end();
        ImGui.popStyleVar(2);
        ImGui.popStyleColor();
    }

    private float sampleTelemetry(long frameTime) {
        var r = uiContext.rendering;
        var frameMs = r.stats.loopLatencyNs > 0 ? r.stats.loopLatencyNs / 1_000_000f : (float) frameTime;
        frameHistory[frameHistoryIdx] = frameMs;
        frameHistoryIdx = (frameHistoryIdx + 1) % frameHistory.length;

        return frameMs;
    }

    private PerformanceStatus performanceStatus(float frameMs) {
        var r = uiContext.rendering;
        if (r.loading.active) {
            return new PerformanceStatus("STREAMING WORLD", HUD_ACCENT);
        }
        if (r.stats.fps <= 0) {
            return new PerformanceStatus("WARMING UP", HUD_TEXT_DIM);
        }
        if (frameMs > BUDGET_MS * 2f || r.stats.fps < 30) {
            return new PerformanceStatus("FRAME PRESSURE", HUD_RED);
        }
        if (r.gpuProfiling && r.stats.sceneGpuMs > r.stats.sceneSubmitMs + 3f) {
            return new PerformanceStatus("GPU BOUND", HUD_MESH);
        }
        if (r.stats.draws > 6000) {
            return new PerformanceStatus("DRAW-CALL PRESSURE", HUD_MESH);
        }
        if (r.stats.sceneSubmitMs > BUDGET_MS) {
            return new PerformanceStatus("CPU SUBMIT", HUD_MESH);
        }
        if (frameMs > BUDGET_MS * 1.1f) {
            return new PerformanceStatus("OVER BUDGET", HUD_MESH);
        }
        return new PerformanceStatus("WITHIN BUDGET", HUD_TERRAIN);
    }

    private void drawTelemetryPanel(long frameTime, float frameMs) {
        var r = uiContext.rendering;
        workspace.beginToolWindow("##hudPerformance", "Performance");
        ImGui.beginChild("##performanceBody", 0, 0, false);

        var status = performanceStatus(frameMs);
        var headerLabel = "FRAME HEALTH";
        ImGui.textColored(HUD_TEXT_DIM, headerLabel);
        var statusWidth = ImGui.calcTextSizeX(status.label());
        if (ImGui.calcTextSizeX(headerLabel) + statusWidth + 18f <= ImGui.getContentRegionAvailX()) {
            ImGui.sameLine(ImGui.getWindowContentRegionMaxX() - PERFORMANCE_PROFILE_RIGHT_INSET - statusWidth);
        }
        ImGui.textColored(status.color(), status.label());
        tip("Automatic diagnosis from frame time, FPS, GPU time and scene-submit pressure.");
        ImGui.separator();

        if (r.loading.active) {
            drawProfileNotice(
                    String.format("STREAMING WORLD  %,d / %,d TILES", r.loading.tilesLoaded, r.loading.tilesTotal),
                    HUD_ACCENT,
                    "Progressive world loading is active. Topology publication and resource upload may dominate "
                            + "frame time until every requested tile is installed.");
        }

        var history = getFrameHistoryStats();
        var heroAvailable = ImGui.getContentRegionAvailX();
        var heroColumns = heroAvailable >= PERFORMANCE_PROFILE_MIN_WIDTH;
        var heroWidth = heroColumns ? (heroAvailable - 16f) / 3f : heroAvailable;
        var heroStart = ImGui.getCursorPosX();
        drawProfileHeroMetric(
                "FPS",
                Integer.toString(r.stats.fps),
                fpsColor(r.stats.fps),
                heroWidth,
                "Completed frames per second. Green is at least 55 FPS, amber is 30-54 FPS, red is below 30 FPS.");
        if (heroColumns) {
            ImGui.sameLine(heroStart + heroWidth + 8f);
        }
        drawProfileHeroMetric(
                "FRAME",
                String.format("%.1f ms", frameMs),
                budgetColor(frameMs),
                heroWidth,
                String.format(
                        "Measured work time for the most recently completed viewer frame. The simulation step is "
                                + "%d ms of world time and is not a CPU/GPU cost.",
                        frameTime));
        if (heroColumns) {
            ImGui.sameLine(heroStart + (heroWidth + 8f) * 2f);
        }
        drawProfileHeroMetric(
                "P95",
                String.format("%.1f ms", history.p95Ms()),
                budgetColor(history.p95Ms()),
                heroWidth,
                "95th-percentile frame time over the last 120 frames. Only roughly one frame in twenty was "
                        + "slower, so this exposes recurring stutter better than instantaneous FPS.");

        drawSparkline(ImGui.getContentRegionAvailX(), 38f);
        var graphLabel = String.format("120 FRAMES  ·  %d OVER BUDGET", history.overBudgetFrames());
        ImGui.textColored(HUD_TEXT_DIM, graphLabel);
        tip(String.format(
                "Rolling frame-time history. The cyan line is the %.1f ms / 60-FPS budget. The count reports "
                        + "samples above that budget.",
                BUDGET_MS));

        drawProfileSection("BUDGET");
        drawProfileBudgetLane(
                "CPU",
                r.stats.bgfxFrameCpuMs,
                "CPU time reported by bgfx for the completed frame. This includes render-thread/backend work, "
                        + "but not necessarily every part of the viewer loop.");
        drawProfileBudgetLane(
                "SUBMIT",
                r.stats.sceneSubmitMs,
                "CPU time spent preparing and submitting the world scene. A high value with a lower GPU value "
                        + "usually indicates command-generation or draw-submission pressure.");
        drawProfileBudgetLane(
                "GPU",
                r.stats.sceneGpuMs,
                "Completed-frame GPU time. Compare it with CPU and Submit: the largest budget lane is the most "
                        + "likely current bottleneck.");

        drawProfileSection("WORKLOAD");
        var workAvailable = ImGui.getContentRegionAvailX();
        var workColumns = workAvailable >= PERFORMANCE_PROFILE_MIN_WIDTH;
        var workWidth = workColumns ? (workAvailable - 16f) / 3f : workAvailable;
        var workStart = ImGui.getCursorPosX();
        drawProfileWorkMetric(
                "SUBMITS",
                formatCompactCount(r.stats.draws),
                workWidth,
                "Actual bgfx draw submissions issued for the world scene this frame. This is not the number of "
                        + "resident indirect command ranges, individual triangles or visible objects.");
        if (workColumns) {
            ImGui.sameLine(workStart + workWidth + 8f);
        }
        drawProfileWorkMetric(
                "VISIBLE",
                r.stats.gpuVisibilityCountersValid ? formatCompactCount(r.stats.gpuVisibilityCompacted) : "sampling",
                workWidth,
                "Latest asynchronous Hi-Z visibility result: compacted candidates that survived occlusion, "
                        + "frustum and distance rejection. 'sampling' means no fresh counter readback yet.");
        if (workColumns) {
            ImGui.sameLine(workStart + (workWidth + 8f) * 2f);
        }
        drawProfileWorkMetric(
                "FX",
                formatCompactCount(
                        r.stats.particleMeshDraws + r.stats.particleBillboardDraws + r.stats.particleBeamDraws),
                workWidth,
                "Particle draw batches submitted this frame across mesh, sprite and beam emitters.");

        if (!r.stats.graphicsFallback.isEmpty()) {
            drawProfileNotice(
                    "GRAPHICS FALLBACK ACTIVE",
                    HUD_MESH,
                    "Requested graphics path could not be used: " + r.stats.graphicsFallback);
        }
        if (r.stats.gpuVisibilityCpuFallback) {
            drawProfileNotice(
                    "VISIBILITY CPU FALLBACK",
                    HUD_RED,
                    r.stats.gpuVisibilityReason.isEmpty()
                            ? "GPU visibility is unavailable. The viewer is using its CPU fallback."
                            : r.stats.gpuVisibilityReason);
        }
        if (r.stats.readbackQueueDepth > 1) {
            drawProfileNotice(
                    "READBACK QUEUE  " + r.stats.readbackQueueDepth,
                    HUD_MESH,
                    "Pending asynchronous GPU readbacks. A persistent queue can make visibility or diagnostic "
                            + "samples stale, but an occasional entry is normal.");
        }

        if (profileDisclosureButton(profileDetails)) {
            profileDetails = !profileDetails;
        }
        if (profileDetails) {
            drawProfileTabs();
            switch (profileTab) {
                case FRAME -> drawProfileFrameDetails(r);
                case VISIBILITY -> drawProfileVisibilityDetails(r);
                case RENDER -> drawProfileRenderDetails(r);
                case PASSES -> drawProfilePassDetails(r);
            }
        }

        ImGui.endChild();
        ImGui.end();
    }

    private void drawProfileTabs() {
        ImGui.spacing();
        var width = ImGui.getContentRegionAvailX();
        if (width < PERFORMANCE_PROFILE_MIN_WIDTH) {
            drawProfileTabButton(
                    ProfileTab.FRAME, "FRAME", width, "CPU/GPU phase timing and actual world-scene submission load.");
            drawProfileTabButton(
                    ProfileTab.VISIBILITY,
                    "VISIBILITY",
                    width,
                    "Hi-Z culling, compacted candidates and indirect submission.");
            drawProfileTabButton(
                    ProfileTab.RENDER, "RENDER", width, "Renderer, resolution, upscaler, reflections and FX routing.");
            drawProfileTabButton(ProfileTab.PASSES, "PASSES", width, PROFILE_PASSES_TOOLTIP);
            return;
        }
        var tabWidth = width / ProfileTab.values().length;
        drawProfileTabButton(
                ProfileTab.FRAME, "FRAME", tabWidth, "CPU/GPU phase timing and actual world-scene submission load.");
        ImGui.sameLine(0f, 0f);
        drawProfileTabButton(
                ProfileTab.VISIBILITY,
                "VISIBILITY",
                tabWidth,
                "Hi-Z culling, compacted candidates and indirect submission.");
        ImGui.sameLine(0f, 0f);
        drawProfileTabButton(
                ProfileTab.RENDER, "RENDER", tabWidth, "Renderer, resolution, upscaler, reflections and FX routing.");
        ImGui.sameLine(0f, 0f);
        drawProfileTabButton(ProfileTab.PASSES, "PASSES", width - tabWidth * 3f, PROFILE_PASSES_TOOLTIP);
    }

    private void drawProfilePassDetails(Rendering r) {
        drawProfileSection("GPU PASSES");
        var timed = 0;
        for (var pass : r.stats.gpuPasses) {
            if (Double.isFinite(pass.gpuMs())) {
                timed++;
            }
        }
        if (timed == 0) {
            drawProfileMetricRow(
                    "Per-pass timing",
                    "off",
                    HUD_TEXT_DIM,
                    "Set graphics.profiler = true in cruma.conf to time every bgfx view. Without it the active "
                            + "renderer exposes only whole-frame timing.");
            return;
        }
        var passes = new ArrayList<>(r.stats.gpuPasses);
        passes.sort((a, b) -> Double.compare(finiteOrZero(b.gpuMs()), finiteOrZero(a.gpuMs())));
        var shown = 0;
        var totalGpu = 0.0;
        for (var pass : passes) {
            var gpu = pass.gpuMs();
            if (!Double.isFinite(gpu)) {
                continue;
            }
            totalGpu += gpu;
            if (shown >= PROFILE_PASS_ROWS || gpu < 0.005) {
                continue;
            }
            drawProfileMetricRow(
                    pass.name(),
                    String.format("%.2f ms  ·  cpu %.2f", gpu, pass.cpuMs()),
                    gpu >= 2.0 ? HUD_MESH : HUD_TEXT,
                    "GPU time of this bgfx view followed by the CPU time spent encoding it.");
            shown++;
        }
        drawProfileMetricRow(
                "Sum of views",
                String.format("%.2f ms", totalGpu),
                HUD_TEXT_DIM,
                "GPU time attributed to all timed views. Views below 0.005 ms are counted but not listed. "
                        + "Views overlap on the GPU, so a view that waits for earlier work is charged for that wait: "
                        + "compare the sum between changes, not one row.");
    }

    private void drawProfileTabButton(ProfileTab tab, String label, float width, String tooltip) {
        var selected = profileTab == tab;
        var origin = ImGui.getCursorScreenPos();
        var height = ImGui.getTextLineHeight() + 10f;
        var pressed = ImGui.invisibleButton(label + "##profileTab", width, height);
        var hovered = ImGui.isItemHovered();
        var textWidth = ImGui.calcTextSizeX(label);
        var textX = Math.round(origin.x + (width - textWidth) * 0.5f);
        var textY = Math.round(origin.y + (height - ImGui.getTextLineHeight()) * 0.5f - 1f);
        var drawList = ImGui.getWindowDrawList();
        drawList.addText(textX, textY, selected ? HUD_ACCENT_BRIGHT : hovered ? HUD_TEXT : HUD_TEXT_DIM, label);
        if (selected) {
            drawList.addRectFilled(
                    origin.x + 5f, origin.y + height - 2f, origin.x + width - 5f, origin.y + height, HUD_ACCENT);
        }
        if (hovered) {
            showWrappedTooltip(tooltip);
        }
        if (pressed) {
            profileTab = tab;
        }
    }

    private void drawProfileFrameDetails(Rendering r) {
        drawProfileSection("FRAME DETAIL");
        drawProfileMetricRow(
                "Backend CPU",
                formatProfileMsWithUnit(r.stats.bgfxFrameCpuMs),
                budgetColor(r.stats.bgfxFrameCpuMs),
                "CPU duration reported by bgfx for the completed backend frame.");
        drawProfileMetricRow(
                "Scene submit",
                formatProfileMsWithUnit(r.stats.sceneSubmitMs),
                budgetColor(r.stats.sceneSubmitMs),
                "CPU time used to traverse, sort, encode and submit the visible scene.");
        drawProfileMetricRow(
                "Topology / upload",
                formatProfileMs(r.stats.bgfxTopologyMs) + " / " + formatProfileMs(r.stats.bgfxUploadMs) + " ms",
                budgetColor(r.stats.bgfxTopologyMs + r.stats.bgfxUploadMs),
                "CPU time outside scene submission: visibility-topology publication followed by frame-budgeted "
                        + "native resource upload.");
        drawProfileMetricRow(
                "Walk / visibility / encode",
                formatProfileMs(r.stats.bgfxWalkMs)
                        + " / "
                        + formatProfileMs(r.stats.bgfxVisibilityMs)
                        + " / "
                        + formatProfileMs(r.stats.bgfxSortEncodeMs)
                        + " ms",
                HUD_TEXT,
                "CPU scene phases: residual direct-tail walk, visibility preparation, then sorting and bgfx "
                        + "command encoding.");
        drawProfileMetricRow(
                "GPU total",
                formatProfileMsWithUnit(r.stats.sceneGpuMs),
                budgetColor(r.stats.sceneGpuMs),
                "Total completed-frame GPU time exposed by the active backend.");
        if (hasFinite(r.stats.bgfxGpuOpaqueMs, r.stats.bgfxGpuTranslucentMs, r.stats.bgfxGpuFxMs)) {
            drawProfileMetricRow(
                    "GPU world",
                    formatProfileMs(r.stats.bgfxGpuOpaqueMs)
                            + " opaque / "
                            + formatProfileMs(r.stats.bgfxGpuTranslucentMs)
                            + " trans / "
                            + formatProfileMs(r.stats.bgfxGpuFxMs)
                            + " FX ms",
                    HUD_TEXT,
                    "Per-view GPU time for opaque world geometry, order-dependent translucent geometry and "
                            + "particle effects. Hidden when the active renderer exposes only whole-frame timing.");
        }
        if (hasFinite(r.stats.bgfxGpuVisibilityMs, r.stats.bgfxGpuReflectionMs, r.stats.bgfxGpuPostMs)) {
            drawProfileMetricRow(
                    "GPU auxiliary",
                    formatProfileMs(r.stats.bgfxGpuVisibilityMs)
                            + " vis / "
                            + formatProfileMs(r.stats.bgfxGpuReflectionMs)
                            + " refl / "
                            + formatProfileMs(r.stats.bgfxGpuPostMs)
                            + " post ms",
                    HUD_TEXT,
                    "Per-view GPU time for Hi-Z visibility, reflection views and post-processing. Hidden when "
                            + "the active renderer exposes only whole-frame timing.");
        }
        drawProfileMetricRow(
                "Draw submissions",
                String.format("%,d", r.stats.draws),
                r.stats.draws > 6000 ? HUD_MESH : HUD_TEXT,
                "Actual bgfx draw submissions issued for the world scene. Visibility Details separately shows "
                        + "the larger resident indirect-range topology, including zero-instance ranges.");
    }

    private void drawProfileVisibilityDetails(Rendering r) {
        drawProfileSection("VISIBILITY DETAIL");
        var visibilityMode = r.stats.gpuVisibilityCpuFallback
                ? "CPU fallback"
                : r.stats.gpuVisibilityHistory ? "Hi-Z history" : "frustum only";
        drawProfileMetricRow(
                "Mode",
                visibilityMode,
                r.stats.gpuVisibilityCpuFallback ? HUD_RED : r.stats.gpuVisibilityHistory ? HUD_TERRAIN : HUD_MESH,
                r.stats.gpuVisibilityReason.isEmpty()
                        ? "Visibility path active for the current frame."
                        : r.stats.gpuVisibilityReason);
        drawProfileMetricRow(
                "Candidates",
                String.format("%,d", r.stats.gpuVisibilityCandidates),
                HUD_TEXT,
                "Geometry instances considered by the current visibility topology before rejection.");
        drawProfileMetricRow(
                "Occlusion culled",
                String.format("%,d", r.stats.gpuVisibilityOcclusionCulled),
                HUD_TEXT,
                "Candidates rejected by the previous-frame Hi-Z depth hierarchy.");
        drawProfileMetricRow(
                "Frustum culled",
                String.format("%,d", r.stats.gpuVisibilityFrustumCulled),
                HUD_TEXT,
                "Candidates outside the current camera frustum.");
        drawProfileMetricRow(
                "Distance culled",
                String.format("%,d", r.stats.gpuVisibilityDistanceCulled),
                HUD_TEXT,
                "Candidates beyond their active authored or viewer distance limit.");
        drawProfileMetricRow(
                "Visible",
                r.stats.gpuVisibilityCountersValid ? String.format("%,d", r.stats.gpuVisibilityCompacted) : "sampling",
                r.stats.gpuVisibilityCountersValid ? HUD_TERRAIN : HUD_TEXT_DIM,
                "Compacted candidates that survived all visibility tests. The counter is asynchronously sampled.");
        if (r.stats.gpuVisibilityCountersValid) {
            drawProfileMetricRow(
                    "Sample age",
                    Math.max(0, r.stats.gpuVisibilityStatsAgeFrames) + " frames",
                    HUD_TEXT_DIM,
                    "Age of the latest asynchronous GPU visibility counter readback.");
        }
        drawProfileMetricRow(
                "Submits / ranges",
                String.format("%,d / %,d", r.stats.gpuOpaqueSubmitBatches, r.stats.gpuOpaqueCommandRanges),
                HUD_TEXT,
                "Actual Java/bgfx indirect submissions versus authored indirect command ranges.");
        drawProfileMetricRow(
                "Active / total groups",
                String.format("%,d / %,d", r.stats.gpuOpaqueActiveGroups, r.stats.gpuVisibilityGroups),
                HUD_TEXT,
                "Indirect topology groups used by the current frame versus all resident groups.");
        if (r.stats.walkItemsTested > 0) {
            drawProfileMetricRow(
                    "Flat walk",
                    String.format(
                            "%,d / %,d items  ·  %,d / %,d cells",
                            r.stats.walkItemsVisible,
                            r.stats.walkItemsTested,
                            r.stats.walkCellsSkipped,
                            r.stats.walkCellsTotal),
                    r.stats.walkLive ? HUD_TEXT : HUD_TEXT_DIM,
                    r.stats.walkLive
                            ? "Visible/tested flat-walk items followed by skipped/total spatial cells."
                            : "The flat walk did not run this frame, so this sample is intentionally marked stale.");
        }
    }

    private void drawProfileRenderDetails(Rendering r) {
        drawProfileSection("RENDER DETAIL");
        drawProfileMetricRow(
                "Renderer",
                r.stats.graphicsRenderer,
                HUD_TEXT,
                "Native renderer selected by bgfx for this viewer session.");
        drawProfileMetricRow(
                "Display / render",
                r.stats.displayWidth
                        + "x"
                        + r.stats.displayHeight
                        + " / "
                        + r.stats.renderWidth
                        + "x"
                        + r.stats.renderHeight,
                HUD_TEXT,
                "Window output resolution followed by the internal world-render resolution.");
        drawProfileMetricRow(
                "Upscaler",
                r.stats.requestedUpscaler + " -> " + r.stats.activeUpscaler,
                r.stats.graphicsFallback.isEmpty() ? HUD_TEXT : HUD_MESH,
                "Requested upscaler followed by the path actually active after capability checks.");
        drawProfileMetricRow(
                "Quality",
                r.stats.upscalerPreset + "  ·  " + String.format("%.3fx", r.stats.renderScale),
                HUD_TEXT,
                "Active upscaler preset and internal render-resolution scale relative to the display.");
        drawProfileMetricRow(
                "Dynamic resolution",
                r.stats.dynamicResolutionActive
                        ? String.format("active  ·  error %+.2f ms", r.stats.dynamicErrorMs)
                        : "off",
                r.stats.dynamicResolutionActive ? HUD_ACCENT : HUD_TEXT_DIM,
                "Whether the renderer is adjusting internal scale toward its frame-time target. Error is the "
                        + "current difference from that target.");
        drawProfileMetricRow(
                "Reflection update / reuse",
                r.stats.reflectionUpdates + " / " + r.stats.reflectionReuses,
                HUD_TEXT,
                "Reflection targets rendered this frame versus cached reflection targets reused without redraw.");
        drawProfileMetricRow(
                "FX mesh / sprite / beam",
                r.stats.particleMeshDraws + " / " + r.stats.particleBillboardDraws + " / " + r.stats.particleBeamDraws,
                HUD_TEXT,
                "Particle draw batches split by mesh emitters, billboard sprites and beam/ribbon emitters.");
        if (r.stats.readbackQueueDepth > 0) {
            drawProfileMetricRow(
                    "Readback queue",
                    Integer.toString(r.stats.readbackQueueDepth),
                    r.stats.readbackQueueDepth > 1 ? HUD_MESH : HUD_TEXT_DIM,
                    "Pending asynchronous GPU readbacks used by visibility and viewer diagnostics.");
        }
        if (!r.stats.graphicsFallback.isEmpty()) {
            drawProfileMetricRow(
                    "Fallback",
                    r.stats.graphicsFallback,
                    HUD_MESH,
                    "Reason the requested graphics path could not be used.");
        }
    }

    private void drawProfileHeroMetric(String label, String value, int valueColor, float width, String tooltip) {
        ImGui.beginGroup();
        ImGui.textColored(HUD_TEXT_DIM, label);
        var heading = fontManager.getHeadingFont(20);
        if (heading != null) {
            ImGui.pushFont(heading, 20);
        }
        ImGui.textColored(valueColor, value);
        if (heading != null) {
            ImGui.popFont();
        }
        var used = ImGui.getItemRectSizeX();
        if (used < width) {
            ImGui.dummy(width - used, 0f);
        }
        ImGui.endGroup();
        tip(tooltip);
    }

    private void drawControlDrawer() {
        var r = uiContext.rendering;
        workspace.beginToolWindow("##sceneView", "Scene settings");
        var sections = uiContext.effectsOnly
                ? new SceneViewTab[] {
                    SceneViewTab.RENDER, SceneViewTab.ATMOSPHERE, SceneViewTab.EFFECTS, SceneViewTab.MUSIC
                }
                : SceneViewTab.values();
        if (!List.of(sections).contains(sceneTab)) {
            sceneTab = SceneViewTab.EFFECTS;
        }
        var gap = 4f;
        var buttonWidth = (ImGui.getContentRegionAvailX() - gap * (sections.length - 1)) / sections.length;
        for (var section : sections) {
            if (section != sections[0]) {
                ImGui.sameLine(0, gap);
            }
            if (HudWidgets.button(
                    "sceneCategory/" + section, section.icon, "", buttonWidth, 32, sceneTab == section, false)) {
                sceneTab = section;
            }
            tip(section.label);
        }
        ImGui.separator();
        ImGui.textColored(HudWidgets.ACCENT, sceneTab.label);
        ImGui.spacing();
        var bodyX = ImGui.getCursorScreenPosX();
        var footerY = workspace.getToolPanelTop() + workspace.getToolPanelHeight() - 50;
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.beginChild(
                "##sceneSettings/" + sceneTab, 0, Math.max(1, footerY - ImGui.getCursorScreenPosY() - 6), false);
        ImGui.popStyleVar();
        ImGui.pushTextWrapPos(0);
        switch (sceneTab) {
            case SCENE -> {
                drawSceneControls(r);
                drawSceneLodControls(r);
                drawVisibilityControls(r);
                drawDrawerSection("DEBUG VIEW");
                r.debugTint = hudToggle(
                        "Surface Tint",
                        r.debugTint,
                        "Colour surfaces by type to diagnose geometry: mesh, unlit CSG, lightmapped CSG and other"
                                + " CSG.");
            }
            case RENDER -> {
                r.customLighting = hudToggle(
                        "Custom Lighting",
                        r.customLighting,
                        uiContext.effectsOnly
                                ? "Add viewer sun shadows to the characters: an arm on the body, a cloak on the legs."
                                : "Add viewer lighting and shadows to the authored scene.");
                renderControls(r);
            }
            case ATMOSPHERE -> {
                var sky = !uiContext.effectsOnly || effects.isSkyShown();
                if (uiContext.effectsOnly) {
                    drawBackdropChoice();
                }
                drawSceneSlider("Time of day", r.timeOfDay.getData(), 0f, 24f, "%.1f h");
                tip(
                        sky
                                ? "World time. Animation playback speed is independent."
                                : "The hour of the client's light on the characters, and of the effects that show only"
                                        + " at some hours. Animation playback speed is independent.");
                var skyZone = r.getSkyZone();
                if (skyZone.hasTime()) {
                    GeodataWidgets.hint(skyZoneNote(skyZone));
                }
                drawAtmosphereControls(r, sky);
            }
            case EFFECTS -> drawEffectsControls(r);
            case WATER -> {
                drawRealisticWaterToggle(r);
                ImGui.spacing();
                drawWaterControls(r);
            }
            case MUSIC -> {
                if (uiContext.effectsOnly) {
                    drawEffectSoundControls(r);
                } else {
                    drawMusicControls(r);
                }
            }
        }
        ImGui.popTextWrapPos();
        ImGui.endChild();
        ImGui.setCursorScreenPos(bodyX, footerY);
        ImGui.separator();
        if (HudWidgets.button("resetSceneSettings", null, "Reset to defaults", -1, 28, false, true)) {
            resetSceneSettings();
        }
        tip("Restore all scene settings to the viewer's built-in defaults.");
        ImGui.end();
    }

    private void drawRealisticWaterToggle(Rendering r) {
        var enabled = r.l2WaterShader && r.waterBackTex;
        var next = hudToggle(
                "Use Realistic Water Effects",
                enabled,
                "Detailed water with reflections, animated waves, refraction and depth-dependent colour.");
        if (next != enabled) {
            r.l2WaterShader = next;
            r.waterBackTex = next;
        }
    }

    private void drawSceneControls(Rendering r) {
        r.textures = hudToggle("Textures", r.textures, "Draw surfaces textured. Off = flat, untextured geometry.");
        r.terrain = hudToggle("Terrain", r.terrain, "Draw the terrain heightmap surface.");
        r.staticMeshes = hudToggle(
                "Static Meshes",
                r.staticMeshes,
                "Draw static-mesh props (trees, rocks, decor, furniture) and the figures a map places, such as flags.");
        r.csg = hudToggle("CSG Geometry", r.csg, "Draw CSG/BSP building geometry (walls, floors, interiors).");
        r.passable = hudToggle(
                "PASSABLE surfaces",
                r.passable,
                "Render authored PASSABLE scene surfaces, including translucent effect geometry. This is not geodata.");
    }

    private void drawSceneLodControls(Rendering r) {
        drawDrawerSection("LEVEL OF DETAIL");
        drawSceneSlider("Terrain detail threshold", r.terrainDetailPixels.getData(), 0f, 1024f, "%.0f px");
        r.terrainDetailPixels.set(r.getTerrainDetailThresholdPixels());
        tip("Switch between detailed terrain and its coarse proxy by tile size on screen. Lower values keep detail"
                + " farther away. 0 always keeps detailed terrain. Applies immediately with GPU visibility"
                + " enabled.");
        drawSceneSlider("Impostor distance", r.impostorDistanceMultiplier.getData(), 1f, 20f, "%.1fx");
        r.impostorDistanceMultiplier.set(Math.clamp(r.impostorDistanceMultiplier.get(), 1f, 20f));
        tip("Distance multiplier for switching models to their far versions: simpler meshes and flat cards. 1"
                + " uses client distances. 2 doubles them. Higher values increase rendering work. Applies"
                + " immediately.");
        drawSceneSlider("Grass distance", r.grassDistanceMultiplier.getData(), 1f, 20f, "%.1fx");
        r.grassDistanceMultiplier.set(r.getGrassDistanceMultiplier());
        tip("Keep small terrain grass visible farther away. 1 uses the standard screen-size cutoff. "
                + "Higher values increase rendering work. Applies immediately with GPU visibility enabled.");
    }

    private void renderControls(Rendering r) {
        if (!r.customLighting) {
            return;
        }
        var world = !uiContext.effectsOnly;
        if (world) {
            r.customProtectAuthoredInteriors = hudToggle(
                    "Protect Authored Interiors",
                    r.customProtectAuthoredInteriors,
                    "Use the camera's exact BSP zone. ZoneInfo.bSunAffect=false keeps only HF-authored "
                            + "ambient, lightmaps and local lights. Viewer shadows and relighting are bypassed.");
            if (r.stats.customLightingSuppressedByAuthoredInterior) {
                ImGui.textDisabled("Authored interior active - Custom Lighting bypassed");
            }
            ImGui.textDisabled("Lighting model");
            ImGui.radioButton("Authored Hybrid", r.customLightingModel, 0);
            tip("Preserves the completed HF colour and adds bounded viewer shadow visibility. It cannot "
                    + "separate already-composed ambient, sun and local-light energy.");
            ImGui.radioButton("Realistic Relighting (Experimental)", r.customLightingModel, 1);
            tip("Replaces the diffuse direct term only for supported opaque materials. HF terrain, "
                    + "lightmaps and static-mesh lighting streams remain authored where decomposition is "
                    + "not lossless.");
            ImGui.beginDisabled(!r.stats.customRayTracingAvailable);
            ImGui.radioButton("Global Illumination (Prototype)", r.customLightingModel, 2);
            tip("World-space light probes: sky light and one sunlight bounce. Requires Metal ray tracing. The"
                    + " nearby static scene is relit. Animated FX and water keep their authored appearance. The"
                    + " first use in a session loads the maps again behind the screen, as the world keeps no ray"
                    + " data before that.");
            ImGui.endDisabled();
            if (r.customLightingModel.get() == 2) {
                ImGui.textWrapped(r.stats.customGiStatus);
                drawSceneSlider("Indirect Light", r.customGiStrength.getData(), 0f, 2f, "%.2fx");
                ImGui.textDisabled(String.format(Locale.ROOT, "Last batch: %.2f ms GPU", r.stats.customGiGpuMillis));
                ImGui.textDisabled(
                        String.format(Locale.ROOT, "Last volume: %.2f ms GPU", r.stats.customGiUpdateGpuMillis));
            }
        }
        if (r.customLightingModel.get() == 0) {
            drawSceneSlider("Hybrid Shadow Strength", r.customAuthoredShadowStrength.getData(), 0f, 100f, "%.0f%%");
            r.customAuthoredShadowStrength.set(Math.max(0f, Math.min(100f, r.customAuthoredShadowStrength.get())));
            tip("Viewer shadow contrast applied to the already-lit HF frame. 0% keeps authored colour. "
                    + "100% permits fully dark visibility. The 45% default keeps every Hybrid receiver "
                    + "at least 55% of its authored brightness.");
        }
        r.customSunShadows = hudToggle(
                "Sun Cascades",
                r.customSunShadows,
                r.stats.customSunShadowsAvailable,
                "Complete world-space directional-sun visibility from stable cascaded shadow maps.");
        if (world) {
            r.customLocalLightShadows = hudToggle(
                    "Local Light Shadows",
                    r.customLocalLightShadows,
                    r.stats.customLocalLightShadowsAvailable,
                    "Shadow a bounded set of nearby client-authored lamps through a shared atlas.");
            r.customRayTracedSunReference = hudToggle(
                    "Ray-Traced Sun (Reference)",
                    r.customRayTracedSunReference,
                    r.stats.customRayTracingAvailable && r.customSunShadows,
                    "Eight world-space area-sun rays for the current camera pose. This correctness reference blocks"
                        + " the render thread. Raster Sun Cascades remain the fallback outside local TLAS. The first"
                        + " use in a session loads the maps again behind the screen, as the world keeps no ray data"
                        + " before that.");
            ImGui.textDisabled(
                    r.stats.customRayTracingActive
                            ? "Ray queries active"
                            : r.stats.customRayTracingAvailable && r.customRayTracedSunReference
                                    ? "Ray queries preparing (effects stay neutral)"
                                    : r.stats.customRayTracingAvailable
                                            ? "Ray queries on demand (standby)"
                                            : "Ray queries unavailable");
            tip(r.stats.customRayTracingReason);
        }
    }

    private void drawVisibilityControls(Rendering r) {
        drawDrawerSection("VISIBILITY");
        r.culling = hudToggle(
                "Frustum Culling", r.culling, "Cull geometry outside the view. Off = draw everything (slow).");
        r.staticMeshesDistCulling = hudToggle(
                "Mesh Distance Culling",
                r.staticMeshesDistCulling,
                "Cull static-mesh props beyond the configured distance. No replacement geometry is drawn.");
        if (r.staticMeshesDistCulling) {
            drawSceneNumber("Mesh Distance", r.staticMeshesCullingDist, 1000);
            tip("Distance (world units) beyond which static-mesh props disappear.");
        }
        drawDrawerSection("CLIPPING");
        drawSceneNumber("View Distance", r.viewDistance, 10000);
        tip("Far clip plane (world units). It does not change tile residency or mesh distance.");
        var view = renderingContext.camera.getView();
        if (view != CameraView.TOP) {
            ImGui.textDisabled(
                    view.isOrtho()
                            ? "The Slice button in the status bar cuts this view."
                            : "Press O for the top view, or choose Front or Side in the view bar. The Slice button in"
                                    + " the status bar then cuts the view.");
            return;
        }
        drawDrawerSection("HEIGHT CUT");
        OrthoSliceControls.drawHeightCuts(r, renderingContext.camera, renderBackend.getSceneBounds());
        if (!r.orthoCutTop) {
            drawSceneNumber("Ortho Near Plane", r.zNear, 10);
            r.zNear.set(Math.max(0, Math.min(999999, r.zNear.get())));
            tip("Near boundary of the orthographic view volume, counted down from the camera. Raising it "
                    + "clips more geometry nearest the top-down camera. Cut Above Z does the same in world heights.");
        }
    }

    private void drawOrthoGrid() {
        var camera = renderingContext.camera;
        if (uiContext.worldMapOpen || !uiContext.rendering.orthoGrid || !camera.isInteractiveOrtho()) {
            return;
        }
        var io = ImGui.getIO();
        OrthoGrid.draw(
                ImGui.getBackgroundDrawList(),
                camera,
                io.getDisplaySizeX(),
                io.getDisplaySizeY(),
                new Viewport(
                        workspace.getViewportLeft(),
                        workspace.getViewportTop(),
                        workspace.getViewportRight(),
                        workspace.getViewportBottom()),
                workspace.getBottomOverlayLeft(),
                workspace.getTopOverlayBottom(),
                workspace.getLeftOverlayBottom(),
                uiContext.rendering.orthoGridDensity,
                uiContext.rendering.orthoGridStep);
    }

    private void drawCutCircle() {
        var r = uiContext.rendering;
        var camera = renderingContext.camera;
        if (uiContext.worldMapOpen
                || !r.orthoCircle
                || !(r.orthoCutTop || r.orthoCutBottom)
                || camera.getView() != CameraView.TOP) {
            return;
        }
        var io = ImGui.getIO();
        var screenWidth = (int) io.getDisplaySizeX();
        var screenHeight = (int) io.getDisplaySizeY();
        if (screenWidth <= 0 || screenHeight <= 0) {
            return;
        }
        var drawList = ImGui.getBackgroundDrawList();
        drawList.pushClipRect(
                workspace.getViewportLeft(),
                workspace.getViewportTop(),
                workspace.getViewportRight(),
                workspace.getViewportBottom(),
                true);
        var z = camera.getWorldFocusPosition().getZ();
        var radius = r.orthoCircleRadius[0];
        Vec2 previous = null;
        for (int i = 0; i <= CUT_CIRCLE_SEGMENTS; i++) {
            var angle = 2.0 * Math.PI * i / CUT_CIRCLE_SEGMENTS;
            var point = camera.projectToScreenRaw(
                    new Vec3(
                            (float) (r.orthoCircleX.get() + radius * Math.cos(angle)),
                            (float) (r.orthoCircleY.get() + radius * Math.sin(angle)),
                            z),
                    screenWidth,
                    screenHeight);
            if (previous != null && point != null) {
                drawList.addLine(previous.getX(), previous.getY(), point.getX(), point.getY(), HUD_ACCENT, 1.5f);
            }
            previous = point;
        }
        drawList.popClipRect();
    }

    private void drawBackdropChoice() {
        drawSceneFieldLabel("Backdrop");
        var chosen = GeodataWidgets.segmented("sceneBackdrop", BACKDROP_NAMES, effects.isSkyShown() ? 1 : 0);
        if (chosen >= 0) {
            effects.showSky(chosen == 1);
        }
        ImGui.spacing();
        tip("What stands behind the effects with no world: dark grey, on which bright effects stand out, or the"
                + " client's sky at the Time of day. On Dark the sky's own settings are hidden.");
    }

    private void drawAtmosphereControls(Rendering r, boolean sky) {
        if (lobbyLight) {
            drawSceneChoice("Sky Preset", lobbyPreset, LOBBY_PRESET_NAMES);
            tip("The game lights its lobby with a light of its own, LobbyTimeEnv, not with the world's presets.");
        } else {
            drawSceneChoice("Sky Preset", r.skyPreset, SKY_PRESET_NAMES);
            tip(
                    sky
                            ? "Client TimeEnv day/night colour preset."
                            : "Client TimeEnv day/night colour preset: the colour of the light on the characters.");
        }
        if (!sky) {
            return;
        }
        drawSceneChoice("Moon", r.moonVariant, MOON_NAMES);
        tip("Auto follows the selected preset. The moon is visible at night.");
        drawSceneSlider("Sun Size", r.realSkySunSize.getData(), 10f, 400f, "%.0f");
        tip("Sun sprite base world size.");
        drawDrawerSection("FOG");
        drawSceneSlider("Fog Strength", r.fogStrength.getData(), 0f, 200f, "%.0f%%");
        r.fogStrength.set(Math.max(0f, Math.min(200f, r.fogStrength.get())));
        tip("Density multiplier for the resolved HF distance fog. 100% preserves the authored fog, "
                + "0% disables it, and 200% reaches the same fog colour in half the authored distance.");
    }

    private void drawEffectsControls(Rendering r) {
        drawSceneSlider("Gamma", r.displayGamma.getData(), 0.5f, 2.0f, "%.2f");
        tip("HF client display gamma. 1.00 is neutral. Values above 1 brighten midtones. "
                + "Older clients persist the user's selected value as Gamma in Option.ini.");
        r.bloom = hudToggle("Bloom", r.bloom, "UE2 UGlow: blur near-white pixels and add them back to the frame.");
        drawSceneNumber("Particle Distance", r.particleVisibilityDistance, 10000);
        r.particleVisibilityDistance.set(Math.max(0, r.particleVisibilityDistance.get()));
        renderingContext.particles.setVisibilityDistance(r.particleVisibilityDistance.get());
        tip("Particle visibility distance in world units. Set to 0 to disable particle and emitter effects.");
        drawSceneChoice("Emitter Distance", r.emitterDistanceMode, EMITTER_DISTANCE_MODE_NAMES);
        renderingContext.particles.setDistanceMode(EmitterDistanceMode.of(r.emitterDistanceMode.get()));
        tip("Which draw distance the emitters obey. Authored is the client rule: every Emitter actor keeps "
                + "its own VisibleLimit window, so effects the client hides at a distance stay hidden here. "
                + "Custom ignores those windows and gives every emitter - sprite, mesh and beam - the "
                + "Particle Distance above as its far limit, fading each one out over its own interp range.");
    }

    private void drawWaterControls(Rendering r) {
        var nextShaderWater = r.l2WaterShader && r.waterBackTex;
        ImGui.textWrapped("Current frame: " + r.stats.realisticWaterStatus);
        if (nextShaderWater) {
            ImGui.beginDisabled(true);
        }
        drawSceneChoice("Legacy Reflection", r.l2WaterEffectType, WATER_EFFECT_NAMES);
        tip("Available when Use Realistic Water Effects is off. None uses the authored surface, "
                + "All reflects the complete scene, and Sky reflects only the sky and celestial objects.");
        if (nextShaderWater) {
            ImGui.endDisabled();
        }
    }

    private void drawEffectSoundControls(Rendering r) {
        r.soundEnabled =
                hudToggle("Sounds", r.soundEnabled, "Play the sounds of the effects and of the skills' casts.");
        drawSceneSlider("Volume", r.soundVolume.getData(), 0f, 100f, "%.0f%%");
        r.soundVolume.set(Math.max(0f, Math.min(100f, r.soundVolume.get())));
        tip("The volume of the effects' and the casts' sounds. The client ships Option.ini SoundVolume=0.2, i.e. 20%.");
    }

    private void drawMusicControls(Rendering r) {
        r.musicEnabled = hudToggle(
                "Music",
                r.musicEnabled,
                "Location music. Leaving a music zone lets the track finish; entering another zone changes the music.");
        drawSceneSlider("Volume", r.musicVolume.getData(), 0f, 100f, "%.0f%%");
        r.musicVolume.set(Math.max(0f, Math.min(100f, r.musicVolume.get())));
        tip("Master music volume. The client ships Option.ini MusicVolume=0.2, i.e. 20%.");
        drawSceneSlider("Replay Gap", r.musicWaitSeconds.getData(), 0f, 300f, "%.0f s");
        r.musicWaitSeconds.set(Math.max(0f, Math.min(300f, r.musicWaitSeconds.get())));
        tip("Silence before a finished track replays inside a brush without bLoopMusic. The client waits 60 s.");
        drawSceneSlider("Idle Gap", r.musicIdleWaitSeconds.getData(), 0f, 300f, "%.0f s");
        r.musicIdleWaitSeconds.set(Math.max(0f, Math.min(300f, r.musicIdleWaitSeconds.get())));
        tip("Silence outside every brush before the tile's own entry is tried. The client waits 30 s.");
        drawSceneSlider("Fade", r.musicFadeSeconds.getData(), 0f, 10f, "%.1f s");
        r.musicFadeSeconds.set(Math.max(0f, Math.min(10f, r.musicFadeSeconds.get())));
        tip("Fade-out of the old track when the brush changes. The client fades over 2.5 s.");
        drawDrawerSection("NOW PLAYING");
        ImGui.textWrapped(r.stats.musicStatus.isEmpty() ? "-" : r.stats.musicStatus);
        drawDrawerSection("AMBIENT SOUNDS");
        r.soundEnabled =
                hudToggle("Ambient", r.soundEnabled, "Play the AmbientSoundObject placements around the camera.");
        drawSceneSlider("Sound Volume", r.soundVolume.getData(), 0f, 100f, "%.0f%%");
        r.soundVolume.set(Math.max(0f, Math.min(100f, r.soundVolume.get())));
        tip("Master ambient volume. The client ships Option.ini SoundVolume=0.2, i.e. 20%.");
        drawSceneIntSlider("Max Voices", r.soundMaxVoices.getData(), 1, 64);
        r.soundMaxVoices.set(Math.max(1, Math.min(64, r.soundMaxVoices.get())));
        tip("Loudest placements playing at once. The rest wait until they come closer.");
        ImGui.textWrapped(r.stats.soundStatus.isEmpty() ? "-" : r.stats.soundStatus);
    }

    private @Nullable String guideTopic() {
        var tool = getOpenTool();
        return tool == null ? null : GuideTopics.topic(tool, getOpenTab());
    }

    private void takeGuideRequest() {
        var request = GuideRequests.take();
        if (request == null) {
            return;
        }
        if (request.toggle() && guide.isOpen()) {
            guide.close();
            return;
        }
        var page = request.page();
        if (page == null) {
            var topic = guideTopic();
            page = topic == null ? GuideHistory.HOME : topic;
        }
        guide.open(page);
        workspace.helpOpen = false;
        uiContext.worldMapOpen = false;
    }

    private void drawShortcutHelpPanel() {
        var left = workspace.getGuideLeft();
        var view = new View(
                uiContext.effectsOnly,
                workspace.tools.isOpen(Tool.GEODATA),
                workspace.getMode() == Mode.EDITING,
                scenesPanel.hasScenes(),
                uiContext.screenshots.getFolder());
        var action = shortcuts.draw(
                view,
                fontManager.getHeadingFont(18),
                left,
                workspace.getViewportTop() + 8f,
                workspace.getLeftOverlayBottom(),
                workspace.getGuideWidth());
        if (action == Action.GUIDE) {
            GuideRequests.openTopic();
        } else if (action == Action.CLOSE) {
            workspace.helpOpen = false;
        }
    }

    private int viewerHour() {
        return Math.floorMod((int) Math.floor(uiContext.rendering.timeOfDay.get()), 24);
    }

    private void navigateTo(Location location) {
        if (renderingContext.playback.isComparisonLocked()) {
            return;
        }
        renderingContext.camera.setPosition(location.x(), location.y(), location.z());
        uiContext.worldMapOpen = false;
    }

    private boolean hudToggle(String label, boolean value) {
        return hudToggle(label, value, true, null);
    }

    private boolean hudToggle(String label, boolean value, String tooltip) {
        return hudToggle(label, value, true, tooltip);
    }

    private boolean hudToggle(String label, boolean value, boolean enabled, String tooltip) {
        return HudWidgets.switchRow(label, value, enabled, tooltip);
    }

    private void drawSparkline(float width, float height) {
        var dl = ImGui.getWindowDrawList();
        var p = ImGui.getCursorScreenPos();
        ImGui.dummy(width, height);
        if (ImGui.isItemHovered()) {
            showWrappedTooltip(String.format(
                    "Frame time over the last 120 frames vs the %.1f ms / 60-FPS budget line.\n"
                            + "green = under budget   amber = over   red = spike",
                    BUDGET_MS));
        }
        var x0 = p.x;
        var y0 = p.y;
        var x1 = p.x + width;
        var y1 = p.y + height;
        dl.addRectFilled(x0, y0, x1, y1, HUD_SPARK_BG, 5f);
        var budgetY = y1 - (BUDGET_MS / SPARK_MAX_MS) * height;
        dl.addLine(x0, budgetY, x1, budgetY, HUD_BUDGET_LINE, 1f);
        var n = frameHistory.length;
        var step = width / (n - 1);
        var prevX = x0;
        var prevY = 0f;
        for (int i = 0; i < n; i++) {
            var ms = frameHistory[(frameHistoryIdx + i) % n];
            var cy = y1 - (Math.min(ms, SPARK_MAX_MS) / SPARK_MAX_MS) * height;
            var cx = x0 + i * step;
            if (i > 0) {
                var color = ms > 2f * BUDGET_MS ? HUD_RED : (ms > BUDGET_MS ? HUD_MESH : HUD_TERRAIN);
                dl.addLine(prevX, prevY, cx, cy, color, 1.6f);
            }
            prevX = cx;
            prevY = cy;
        }
        dl.addCircleFilled(prevX, prevY, 2.5f, HUD_ACCENT_BRIGHT);
        dl.addRect(x0, y0, x1, y1, HUD_HAIR, 5f, 0, 1f);
    }

    private void pushHudTheme() {
        HudTheme.push(HUD_PAD_X, HUD_PAD_Y);
    }

    private void popHudTheme() {
        HudTheme.pop();
    }

    private void drawObjectPicker() {
        if (geodata.capturesPointer(renderingContext.geodata, renderingContext.geodata.toolsOpen)
                || spawns.capturesPointer()
                || areas.capturesPointer()) {
            return;
        }
        if (workspace.getMode() != Mode.EDITING
                && workspace.isDirectorShown()
                && hudVisible
                && scenesPanel.doesDirectorPointAtActor()) {
            return;
        }
        if (uiContext.worldMapOpen) {
            return;
        }
        if (!uiContext.rendering.objectPicker
                || uiContext.rendering.agentPickFrames > 0
                || windowContext.screenshot
                || windowContext.mapIndex >= 0) {
            return;
        }
        var io = ImGui.getIO();
        var screenWidth = (int) io.getDisplaySizeX();
        var screenHeight = (int) io.getDisplaySizeY();
        if (screenWidth <= 0 || screenHeight <= 0) {
            return;
        }

        var camera = renderingContext.camera;
        var cameraPos = camera.getWorldFocusPosition();
        DebugSelection hovered = null;
        Hit pawnHit = null;
        var editing = workspace.getMode() == Mode.EDITING;
        var editingClick = editing ? workspace.consumeEditingClick() : null;
        var onMarker = mapEvents.isPointerOnMarker();
        if (onMarker && editingClick != null) {
            mapEvents.clickMarker(camera, editingClick.twice());
            editingClick = null;
        }
        var click = editing
                ? editingClick != null
                : ImGui.isMouseClicked(0)
                        && !io.getWantCaptureMouse()
                        && !renderingContext.editorInputCaptured
                        && !onMarker;
        if (!io.getWantCaptureMouse() && !renderingContext.editorInputCaptured && !onMarker) {
            var ndcX = ImGui.getMousePosX() / screenWidth * 2f - 1f;
            var ndcY = 1f - ImGui.getMousePosY() / screenHeight * 2f;
            var origin = camera.getCursorRayOrigin(ndcX, ndcY);
            var direction = camera.getCursorRayDirection(ndcX, ndcY);
            var categories = EnumSet.noneOf(Category.class);
            if (uiContext.rendering.csg) {
                categories.add(Category.CSG);
            }
            if (uiContext.rendering.staticMeshes) {
                categories.add(Category.STATIC_MESH);
            }
            if (uiContext.rendering.terrain) {
                categories.add(Category.TERRAIN);
            }
            var sceneHit = renderBackend.pickDebugSceneObject(
                    ndcX, ndcY, uiContext.rendering.objectPickMaxDist.get(), categories);
            if (sceneHit != null) {
                hovered = DebugSelection.scene(sceneHit.object(), sceneHit.distance());
            }
            if (picksEffects(editing)) {
                var reach = camera.isInteractiveOrtho() ? Float.MAX_VALUE : uiContext.rendering.objectPickMaxDist.get();
                var fxHit = pickEmitter(
                        origin, direction, camera.isInteractiveOrtho(), sceneHit == null ? reach : sceneHit.distance());
                if (fxHit != null) {
                    hovered = fxHit;
                }
            }
            if (uiContext.rendering.staticMeshes) {
                var limit = hovered != null
                        ? hovered.hitDistance()
                        : camera.isInteractiveOrtho() ? Float.MAX_VALUE : uiContext.rendering.objectPickMaxDist.get();
                pawnHit = renderingContext.mapPawns.pick(origin, direction, limit);
                if (pawnHit != null) {
                    hovered = null;
                    if (editing) {
                        var rows = renderingContext.mapPawns.getEditorRows(List.of(pawnHit.path()));
                        if (!rows.isEmpty()) {
                            hovered = DebugSelection.scene(rows.getFirst(), pawnHit.distance());
                        }
                        pawnHit = null;
                    }
                }
            }
            var panel = editing ? workspace.getEditingPanel() : null;
            if (panel != null && panel.isSelecting()) {
                var eye = camera.getPosition();
                var rows = renderingContext.effects.getRowsNear(eye.getX(), eye.getY(), EffectMarks.REACH);
                var marks = new ArrayList<EffectMarks.Effect>(rows.size());
                var hidden = renderingContext.getEditing().getHiddenActors();
                for (var row : rows) {
                    if (!hidden.contains(row.actorName())) {
                        marks.add(new EffectMarks.Effect(row.actorName(), row.bounds()));
                    }
                }
                var mark = EffectMarks.markAt(
                        camera,
                        ImGui.getMousePosX(),
                        ImGui.getMousePosY(),
                        (int) screenWidth,
                        (int) screenHeight,
                        marks);
                if (mark != null) {
                    for (var row : rows) {
                        if (row.actorName().equals(mark.path())) {
                            var at = camera.getPosition();
                            var dx = (mark.bounds().minX() + mark.bounds().maxX()) * .5f - at.getX();
                            var dy = (mark.bounds().minY() + mark.bounds().maxY()) * .5f - at.getY();
                            var dz = (mark.bounds().minZ() + mark.bounds().maxZ()) * .5f - at.getZ();
                            hovered = DebugSelection.scene(row, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
                            pawnHit = null;
                            break;
                        }
                    }
                }
            }
        }

        highlightHovered(
                hovered, editing, io.getWantCaptureMouse() || renderingContext.editorInputCaptured || onMarker);
        var drawList = ImGui.getBackgroundDrawList();
        drawList.pushClipRect(
                workspace.getViewportLeft(),
                workspace.getViewportTop(),
                workspace.getViewportRight(),
                workspace.getViewportBottom(),
                true);
        var editingLabel = editing ? workspace.drawEditingHover(drawList, hovered) : null;
        if (!editing) {
            workspace.pointAt(hovered);
        }
        if (hovered != null) {
            drawHoverBadge(drawList, hovered, editingLabel, camera.isInteractiveOrtho(), screenWidth, screenHeight);
        } else if (pawnHit != null) {
            var mesh = pawnHit.mesh();
            drawBadge(
                    drawList,
                    "pawn  " + mesh.substring(mesh.lastIndexOf('.') + 1)
                            + (camera.isInteractiveOrtho() ? "" : "  " + Math.round(pawnHit.distance()) + "u"),
                    screenWidth,
                    screenHeight);
        }
        if (click) {
            if (editingClick != null) {
                workspace.selectInEditing(hovered, editingClick);
            } else if (pawnHit != null) {
                workspace.select(null, false);
                mapPawnCard.inspect(pawnHit);
                workspace.tools.open(Tool.OBJECT);
            } else {
                workspace.select(hovered, io.getKeyCtrl());
            }
            if (pawnHit == null) {
                mapPawnCard.stopInspecting();
            }
            spawns.stopInspecting();
        }
        drawSelectedObject(camera, drawList, screenWidth, screenHeight);
        drawList.popClipRect();
    }

    private void highlightHovered(@Nullable DebugSelection hovered, boolean editing, boolean pointerBusy) {
        var target = pointerBusy ? null : workspace.hoverTarget(hovered);
        var now = java.lang.System.nanoTime();
        if (target != null) {
            highlighted = target;
            highlightedNanos = now;
        } else if (pointerBusy || now - highlightedNanos > HIGHLIGHT_HOLD_NANOS) {
            highlighted = null;
        }
        renderBackend.highlightHovered(highlighted, editing);
    }

    private void updateGeodataFocus() {
        var state = renderingContext.geodata;
        var io = ImGui.getIO();
        var width = io.getDisplaySizeX();
        var height = io.getDisplaySizeY();
        if (width <= 0 || height <= 0 || uiContext.worldMapOpen) {
            return;
        }
        var mx = ImGui.getMousePosX();
        var my = ImGui.getMousePosY();
        var left = hudVisible ? workspace.getViewportLeft() : 0;
        var right = hudVisible ? workspace.getViewportRight() : width;
        var top = hudVisible ? workspace.getViewportTop() : 0;
        var bottom = hudVisible ? workspace.getViewportBottom() : height;
        var inViewport = mx >= left && mx < right && my >= top && my < bottom;
        if (state.focusMode == GeoDataFocusMode.CAMERA) {
            state.focusNdcX = (left + right) / width - 1;
            state.focusNdcY = 1 - (top + bottom) / height;
        } else if (!io.getWantCaptureMouse() && inViewport) {
            state.focusNdcX = mx / width * 2 - 1;
            state.focusNdcY = 1 - my / height * 2;
        }
    }

    private void drawSpawns() {
        var blocked = uiContext.worldMapOpen || windowContext.mapIndex >= 0;
        var geodataOwns = geodata.capturesPointer(renderingContext.geodata, renderingContext.geodata.toolsOpen);
        var areasOpen = workspace.tools.isOpen(Tool.AREAS);
        spawns.setHeadingFont(fontManager.getHeadingFont(15));
        spawns.setQuiet(areasOpen);
        spawns.drawViewport(
                renderingContext.spawns,
                renderingContext.camera,
                renderBackend,
                new Viewport(
                        workspace.getViewportLeft(),
                        workspace.getViewportTop(),
                        workspace.getViewportRight(),
                        workspace.getViewportBottom()),
                workspace.tools.isOpen(Tool.SPAWNS),
                !blocked && !geodataOwns && !mapEvents.isPointerOnMarker() && !areasOpen,
                workspace.helpOpen,
                uiContext.rendering.objectPicker,
                renderingContext.tiles);
        if (spawns.consumePanelRequest()) {
            workspace.tools.open(Tool.SPAWNS);
        }
        if (spawns.consumeInspectRequest()) {
            inspection.select(null);
            mapPawnCard.stopInspecting();
            workspace.tools.open(Tool.OBJECT);
        }
    }

    private void drawAreas() {
        var blocked = uiContext.worldMapOpen || windowContext.mapIndex >= 0;
        var geodataOwns = geodata.capturesPointer(renderingContext.geodata, renderingContext.geodata.toolsOpen);
        areas.drawViewport(
                renderingContext.spawns,
                renderingContext.camera,
                renderBackend,
                new Viewport(
                        workspace.getViewportLeft(),
                        workspace.getViewportTop(),
                        workspace.getViewportRight(),
                        workspace.getViewportBottom()),
                renderingContext.tiles,
                workspace.tools.isOpen(Tool.AREAS),
                !blocked && !geodataOwns && !mapEvents.isPointerOnMarker(),
                workspace.tools.isOpen(Tool.SPAWNS),
                workspace.helpOpen,
                uiContext.rendering.objectPicker);
        if (areas.consumePanelRequest()) {
            workspace.tools.open(Tool.AREAS);
        }
    }

    private void drawMapEvents() {
        var open = workspace.tools.isOpen(Tool.EVENTS) && !uiContext.worldMapOpen && windowContext.mapIndex < 0;
        mapEvents.drawViewport(
                renderingContext.camera,
                new Viewport(
                        workspace.getViewportLeft(),
                        workspace.getViewportTop(),
                        workspace.getViewportRight(),
                        workspace.getViewportBottom()),
                uiContext.rendering.gameEventStates,
                uiContext.rendering.getTimeOfDay(),
                open,
                workspace.helpOpen,
                !renderingContext.editorInputCaptured,
                renderingContext.spawns.marks);
        if (workspace.getMode() != Mode.EDITING && mapEvents.isPointerOnMarker() && ImGui.isMouseClicked(0)) {
            mapEvents.clickMarker(renderingContext.camera, ImGui.isMouseDoubleClicked(0));
        }
    }

    private void drawDirectorHandle() {
        if (workspace.getMode() == Mode.EDITING) {
            return;
        }
        var shown = workspace.isDirectorShown() && hudVisible && !uiContext.worldMapOpen && !uiContext.dialogOpen;
        renderingContext.editorInputCaptured = shown
                && scenesPanel.drawDirectorHandle(
                        renderingContext.camera,
                        workspace.getViewportLeft(),
                        workspace.getViewportTop(),
                        workspace.getViewportRight(),
                        workspace.getViewportBottom());
    }

    private void readGameScenes(Path client) {
        Thread.ofVirtual().name("cruma-cut-scenes").start(() -> {
            try {
                var spawns = worldConfig.spawns();
                foundServerScenes =
                        ServerScenes.find(spawns == null ? null : spawns.scriptPath(), client, worldConfig.variant());
                scenesPanel.setSceneModel(GameScenes.model(client));
                scenesPanel.setSceneFiles(
                        SceneSources.clientSha256(client),
                        SceneSources.serverSha256(spawns == null ? null : spawns.scriptPath()));
                var scenes = GameScenes.find(client);
                scenesPanel.setSceneHelper(sceneHelper(client, scenes));
                foundGameScenes = scenes;
            } catch (RuntimeException failure) {
                log.warn("Could not read the cut scenes of the client: {}", failure.toString());
                foundGameScenes = List.of();
            }
        });
    }

    private int sceneHelper(Path client, List<GameScenes.Scene> scenes) {
        try {
            return SceneHelper.of(
                    scenes, NpcGrpTable.read(client.resolve("system"), worldConfig.variant())::getAppearance);
        } catch (IOException | RuntimeException failure) {
            log.warn("Could not read the NPCs of the client for the video director: {}", failure.toString());
            return 0;
        }
    }

    private void playSceneAtStart() {
        var saved = uiContext.fileAtStart;
        if (saved != null) {
            uiContext.fileAtStart = null;
            uiContext.sceneAtStart = null;
            workspace.tools.open(Tool.SCENES);
            scenesPanel.openFile(saved);
            return;
        }
        var fromFile = uiContext.sceneFromFile;
        if (fromFile != null) {
            uiContext.sceneFromFile = null;
            workspace.tools.open(Tool.SCENES);
            renderingContext.gameScenes.play(fromFile, sceneGround());
            return;
        }
        var name = uiContext.sceneAtStart;
        if (name == null) {
            return;
        }
        uiContext.sceneAtStart = null;
        var scene = scenesPanel.findGameScene(name);
        if (scene == null) {
            log.warn("The client has no cut scene {}", name);
            return;
        }
        workspace.tools.open(Tool.SCENES);
        if (uiContext.copyAtStart) {
            uiContext.copyAtStart = false;
            scenesPanel.openCopy(scene);
            return;
        }
        renderingContext.gameScenes.play(scene, sceneGround());
    }

    private void mixRecordedScene(SceneRecording recording) {
        var scenes = foundGameScenes;
        if (recordedSoundStarted || scenes == null) {
            return;
        }
        recordedSoundStarted = true;
        var fromFile = uiContext.sceneFromFile;
        if (fromFile != null) {
            recording.scene(fromFile.seconds(), mixedSound(fromFile, recording));
            return;
        }
        var all = new ArrayList<>(foundServerScenes);
        all.addAll(scenes);
        for (var scene : all) {
            if (scene.name().equalsIgnoreCase(uiContext.sceneAtStart)) {
                recording.scene(scene.seconds(), mixedSound(scene, recording));
                return;
            }
        }
        recording.fail("The client has no cut scene " + uiContext.sceneAtStart);
    }

    private CompletableFuture<List<String>> mixedSound(GameScenes.Scene scene, SceneRecording recording) {
        var rendering = uiContext.rendering;
        return SceneRecordedSound.mix(
                scene,
                worldConfig,
                rendering.musicFadeSeconds.get(),
                rendering.timeOfDay.get(),
                rendering.gameEventStates,
                recording);
    }

    private void openScene(GameScenes.Scene scene, boolean copy) {
        var request = SceneRequest.of(scene, worldConfig.clientPath(), worldConfig.variant());
        if (request.tiles().isEmpty()) {
            if (copy) {
                scenesPanel.openCopy(scene);
            } else {
                renderingContext.gameScenes.play(scene, sceneGround());
            }
            return;
        }
        uiContext.sceneRequest = copy ? request.asCopy() : request;
        uiContext.returnToMenuRequested = true;
    }

    private void openSceneFile(Path file) {
        try {
            var scene = SceneFileStore.read(file).scene();
            var request = SceneRequest.of(scene, worldConfig.clientPath(), worldConfig.variant());
            if (!request.tiles().isEmpty()) {
                uiContext.sceneRequest = request.fromFile(file);
                uiContext.returnToMenuRequested = true;
            }
        } catch (IOException refused) {
            log.warn("The scene {} did not open: {}", file, refused.toString());
        }
    }

    private boolean wantsFloors() {
        return spawns.isWanted()
                || areas.isWanted()
                || uiContext.sceneAtStart != null
                || uiContext.sceneFromFile != null;
    }

    private void flyTo(Item finding) {
        if (renderingContext.playback.isComparisonLocked()) {
            return;
        }
        var camera = renderingContext.camera;
        var height = camera.getPosition().getZ();
        var z = finding.z() != null ? finding.z().doubleValue() : standHeight(finding.x(), finding.y(), height);
        GeodataWorkspace.focus(camera, new GeoPoint(finding.x(), finding.y(), Double.isNaN(z) ? height : z));
        uiContext.worldMapOpen = false;
    }

    private Ground sceneGround() {
        return GameSceneStage.ground(renderingContext.spawns.floors);
    }

    private double standHeight(double x, double y, double z) {
        return renderingContext.spawns.floors.getSurfaceHeight(x, y, z);
    }

    private void readScenes(Path client, String map) {
        Thread.ofVirtual().name("cruma-lobby-scenes").start(() -> {
            try {
                scenesPanel.setSceneModel(GameScenes.model(client));
                scenesPanel.setMapFile(SceneSources.mapSha256(client, map));
                foundScenes = MapScenes.find(client, map);
            } catch (RuntimeException failure) {
                log.warn("Could not read the scenes of {}: {}", map, failure.toString());
                foundScenes = List.of();
            }
        });
    }

    private void rereadScenes() {
        if (namedMap != null) {
            readScenes(worldConfig.clientPath(), namedMap);
        } else {
            readGameScenes(worldConfig.clientPath());
        }
    }

    private boolean onWorldTiles() {
        return namedMap == null && !uiContext.effectsOnly;
    }

    private void refreshMapEvents() {
        var scene = renderingContext.scene;
        if (scene.getZoneStateMeshesRevision() != zoneStateMeshesRevision) {
            zoneStateMeshesRevision = scene.getZoneStateMeshesRevision();
            mapEvents.setZoneStateMeshes(scene.getZoneStateMeshes());
        }
        if (scene.getReflectionBspModelsRevision() != zoneStateFogsRevision) {
            zoneStateFogsRevision = scene.getReflectionBspModelsRevision();
            var fogs = new ArrayList<ZoneStateFog>();
            for (var model : scene.getReflectionBspModels().values()) {
                fogs.addAll(model.getZoneStateFogs());
            }
            mapEvents.setZoneStateFogs(fogs);
        }
        if (scene.getAmbientSoundsRevision() != zoneStateSoundsRevision
                || scene.getAmbientVolumesRevision() != zoneStateVolumesRevision) {
            zoneStateSoundsRevision = scene.getAmbientSoundsRevision();
            zoneStateVolumesRevision = scene.getAmbientVolumesRevision();
            mapEvents.setZoneStateSounds(scene.getAmbientSounds(), scene.getAmbientVolumes());
        }
        if (scene.getZoneStateItemsRevision() != zoneStateItemsRevision) {
            zoneStateItemsRevision = scene.getZoneStateItemsRevision();
            mapEvents.setZoneStateItems(scene.getZoneStateItems());
        }
        var revision = renderingContext.particles.getRegistryRevision();
        var saves = areas.getSource().getSaveCount();
        if (revision != serverEventsRevision || saves != serverEventsSaves) {
            serverEventsRevision = revision;
            serverEventsSaves = saves;
            mapEvents.setServerEvents(serverEvents());
        }
        refreshMapEventCatalog();
    }

    private void refreshMapEventCatalog() {
        var particles = renderingContext.particles;
        var scene = renderingContext.scene;
        if (particles.getRegistryRevision() == mapEventsRevision
                && scene.getAmbientSoundsRevision() == mapEventSoundsRevision) {
            return;
        }
        mapEventsRevision = particles.getRegistryRevision();
        mapEventSoundsRevision = scene.getAmbientSoundsRevision();
        var seen = new HashSet<String>();
        var placed = new ArrayList<Placed>();
        var timed = new ArrayList<TimedPlaced>();
        for (var emitter : particles.getEmitters()) {
            var position = emitter.getActorPos();
            if (!emitter.getTimeWindows().always()) {
                timed.add(new TimedPlaced(
                        emitter.getRenderActorName(),
                        emitter.getRenderActorPackage(),
                        position.getX(),
                        position.getY(),
                        position.getZ(),
                        emitter.getTimeWindows(),
                        effectName(emitter)));
            }
            var entries = emitter.getVisibility().gameEvents();
            if (entries.isEmpty() || !seen.add(emitter.getRenderActorName())) {
                continue;
            }
            placed.add(new Placed(
                    emitter.getRenderActorName(),
                    emitter.getRenderActorPackage(),
                    position.getX(),
                    position.getY(),
                    position.getZ(),
                    entries));
        }
        var timedSounds = new ArrayList<TimedSound>();
        for (var sound : scene.getAmbientSounds()) {
            if (sound.type() != AmbientSound.Type.RANGE_TIME || sound.hours().isEmpty()) {
                continue;
            }
            var actor = sound.actor();
            var dot = actor.indexOf('.');
            var position = sound.position();
            var clip = sound.clip().name();
            timedSounds.add(new TimedSound(
                    actor,
                    dot > 0 ? actor.substring(0, dot) : actor,
                    position.getX(),
                    position.getY(),
                    position.getZ(),
                    sound.hours(),
                    clip.substring(clip.lastIndexOf('.') + 1)));
        }
        mapEvents.setCatalog(MapEventCatalog.of(placed, timed, timedSounds));
    }

    private ServerEvents serverEvents() {
        var open = spawns == null ? null : spawns.getScriptFolder();
        if (open != null) {
            return ServerEvents.of(open);
        }
        var settings = worldConfig.spawns();
        return ServerEvents.of(settings == null ? null : settings.scriptPath());
    }

    private void drawGeodataPicker() {
        var blocked = uiContext.worldMapOpen || windowContext.mapIndex >= 0;
        geodata.drawViewport(
                renderingContext.geodata,
                renderingContext.camera,
                renderBackend,
                new Viewport(
                        workspace.getViewportLeft(),
                        workspace.getViewportTop(),
                        workspace.getViewportRight(),
                        workspace.getViewportBottom()),
                renderingContext.geodata.toolsOpen,
                blocked,
                workspace.helpOpen);
    }

    private @Nullable DebugSelection pickEmitter(Vec3 origin, Vec3 direction, boolean ortho, float maxDistance) {
        if (uiContext.rendering.particleVisibilityDistance.get() <= 0) {
            return null;
        }
        DebugSelection nearest = null;
        var nearestDistance = maxDistance;
        for (var emitter : renderingContext.particles.getEmitters()) {
            if (!inspection.acceptsEmitter(emitter)) {
                continue;
            }
            if (!ortho && !renderingContext.particles.isNear(emitter, origin)) {
                continue;
            }
            var bounds = new Box();
            if (!emitter.computeWorldBounds(bounds)) {
                continue;
            }
            var distance = rayBoxDistance(origin, direction, bounds.getMin(), bounds.getMax());
            if (distance < 0f || distance >= nearestDistance) {
                continue;
            }
            if (emitter.getKind() == Kind.MESH) {
                distance = renderBackend.pickDebugMeshEmitter(emitter, origin, direction, nearestDistance);
                if (distance < 0f) {
                    continue;
                }
            }
            nearestDistance = distance;
            nearest = DebugSelection.fx(emitter, distance);
        }
        return nearest;
    }

    private void drawSelectedObject(Camera camera, ImDrawList drawList, int width, int height) {
        if (inspection.selected == null || !workspace.isSelectionShown(inspection.selected)) {
            return;
        }
        var center = inspection.selected.center();
        if (inspection.selected.emitter() != null) {
            var geometryBounds = renderBackend.getDebugMeshEmitterBounds(inspection.selected.emitter());
            if (geometryBounds != null) {
                center = new Vec3(geometryBounds.centerX(), geometryBounds.centerY(), geometryBounds.centerZ());
            }
        }
        var screen = camera.projectToScreen(center, width, height);
        if (screen == null) {
            return;
        }
        var x = Math.clamp(screen.getX(), workspace.getViewportLeft() + 14f, workspace.getViewportRight() - 14f);
        var y = Math.clamp(screen.getY(), workspace.getViewportTop() + 28f, workspace.getViewportBottom() - 14f);
        var accent = HudWidgets.ACCENT;
        drawList.addCircle(x, y, 8, accent, 24, 2f);
        drawList.addCircleFilled(x, y, 3.5f, accent, 16);
        if (workspace.getMode() == Mode.ANALYZE) {
            drawList.addLine(x - 14, y, x - 6, y, accent, 1.5f);
            drawList.addLine(x + 6, y, x + 14, y, accent, 1.5f);
            drawList.addLine(x, y - 14, x, y - 6, accent, 1.5f);
            drawList.addLine(x, y + 6, x, y + 14, accent, 1.5f);
        }
        ImGui.pushFont(fontManager.getTextFont(13), 13f);
        var info = inspection.selected.shortName();
        var labelWidth = ImGui.calcTextSizeX(info) + 20;
        var labelX = Math.max(
                workspace.getViewportLeft() + 8f, Math.min(x + 22f, workspace.getViewportRight() - labelWidth - 8f));
        drawList.addRectFilled(labelX, y - 14, labelX + labelWidth, y + 14, ImColor.rgba(20, 29, 37, 242), 5f);
        drawList.addRect(labelX, y - 14, labelX + labelWidth, y + 14, ImColor.rgb(66, 84, 99), 5, 0, 1);
        drawList.addText(labelX + 10, y - 7, ImColor.rgb(231, 238, 246), info);
        ImGui.popFont();
    }

    private static ProfileTab initialProfileTab() {
        var configured = java.lang.System.getProperty("cruma.viewer.benchmark.profile_tab", "FRAME");
        try {
            return ProfileTab.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return ProfileTab.FRAME;
        }
    }

    private static SceneViewTab initialSceneViewTab() {
        var configured = java.lang.System.getProperty("cruma.viewer.benchmark.inspector_tab");
        if (configured == null || configured.isBlank()) {
            return SceneViewTab.SCENE;
        }
        try {
            return SceneViewTab.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return SceneViewTab.SCENE;
        }
    }

    private static String loadingStageTitle(WorldLoadStage stage) {
        return switch (stage) {
            case INDEXING -> "Finding World Assets";
            case ASSEMBLING -> "Loading World";
            case UPLOADING -> "Preparing Scene";
            case FINALIZING -> "Finishing Up";
            case READY -> "Ready";
        };
    }

    private static String loadingTiming(Rendering rendering) {
        var elapsedNanos = rendering.loading.startedNanos == 0L
                ? 0L
                : Math.max(0L, java.lang.System.nanoTime() - rendering.loading.startedNanos);
        var elapsed = formatLoadingDuration(elapsedNanos / 1_000_000_000L);
        var canEstimate = rendering.loading.stage == WorldLoadStage.ASSEMBLING
                || rendering.loading.stage == WorldLoadStage.UPLOADING;
        return "Elapsed "
                + elapsed
                + (canEstimate && rendering.loading.etaSeconds >= 0
                        ? "   ·   Remaining ~" + formatLoadingDuration(rendering.loading.etaSeconds)
                        : "");
    }

    private static String formatWorldTime(float hour) {
        var minutes = Math.floorMod(Math.round(hour * 60f), 24 * 60);
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    private record PerformanceStatus(String label, int color) {}

    private static int fpsColor(int fps) {
        return HudWidgets.fpsColor(fps);
    }

    private static boolean profileDisclosureButton(boolean expanded) {
        var width = ImGui.getContentRegionAvailX();
        var height = ImGui.getTextLineHeight() + 10f;
        var origin = ImGui.getCursorScreenPos();
        var pressed = ImGui.invisibleButton("##profileDetails", width, height);
        var hovered = ImGui.isItemHovered();
        var drawList = ImGui.getWindowDrawList();
        var surfaceColor = expanded ? HUD_PILL_ON : hovered ? HUD_ROW_HOVER : HUD_PILL_OFF;
        drawList.addRectFilled(origin.x, origin.y, origin.x + width, origin.y + height, surfaceColor, 4f);
        drawList.addRect(
                origin.x, origin.y, origin.x + width, origin.y + height, expanded ? HUD_ACCENT : HUD_HAIR, 4f, 0, 1f);
        var label = "DETAILS";
        var labelWidth = ImGui.calcTextSizeX(label);
        var textX = Math.round(origin.x + (width - labelWidth) * 0.5f);
        var textY = Math.round(origin.y + (height - ImGui.getTextLineHeight()) * 0.5f - 1f);
        drawList.addText(textX, textY, expanded || hovered ? HUD_TEXT : HUD_TEXT_DIM, label);

        var iconSize = ImGui.getFontSize();
        HudWidgets.glyph(
                drawList,
                expanded ? Glyph.CHEVRON_UP : Glyph.CHEVRON,
                origin.x + width - PERFORMANCE_PROFILE_RIGHT_INSET - iconSize,
                textY + 1,
                iconSize,
                expanded ? HUD_ACCENT_BRIGHT : hovered ? HUD_TEXT : HUD_TEXT_DIM);
        if (hovered) {
            showWrappedTooltip(
                    expanded
                            ? "Hide engineering counters and return to the compact frame-health dashboard."
                            : "Show bounded FRAME, VISIBILITY and RENDER diagnostics.");
        }
        return pressed;
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static void drawProfileSection(String title) {
        ImGui.spacing();
        ImGui.separator();
        ImGui.textColored(HUD_ACCENT, title);
    }

    private static void drawProfileWorkMetric(String label, String value, float width, String tooltip) {
        ImGui.beginGroup();
        ImGui.textColored(HUD_TEXT_DIM, label);
        ImGui.textColored(HUD_TEXT, value);
        var used = ImGui.getItemRectSizeX();
        if (used < width) {
            ImGui.dummy(width - used, 0f);
        }
        ImGui.endGroup();
        tip(tooltip);
    }

    private static void drawProfileBudgetLane(String label, double valueMs, String tooltip) {
        var width = ImGui.getContentRegionAvailX();
        var height = ImGui.getTextLineHeight() + 7f;
        var origin = ImGui.getCursorScreenPos();
        ImGui.invisibleButton(label + "##profileBudget", width, height);
        var drawList = ImGui.getWindowDrawList();
        var labelWidth = 54f;
        var value = formatProfileMsWithUnit(valueMs);
        var valueWidth = ImGui.calcTextSizeX(value);
        var barX = origin.x + labelWidth;
        var valueRight = origin.x + width - PERFORMANCE_PROFILE_RIGHT_INSET;
        var barRight = Math.max(barX + 24f, valueRight - valueWidth - 9f);
        var barY = origin.y + (height - 7f) * 0.5f;
        drawList.addText(origin.x, origin.y + 2f, HUD_TEXT_DIM, label);
        drawList.addRectFilled(barX, barY, barRight, barY + 7f, HUD_TROUGH, 3f);
        if (Double.isFinite(valueMs) && valueMs >= 0d) {
            var fraction = (float) Math.min(1d, valueMs / BUDGET_MS);
            drawList.addRectFilled(
                    barX, barY, barX + (barRight - barX) * fraction, barY + 7f, budgetColor(valueMs), 3f);
        }
        drawList.addText(
                valueRight - valueWidth,
                origin.y + 2f,
                Double.isFinite(valueMs) ? budgetColor(valueMs) : HUD_TEXT_DIM,
                value);
        if (ImGui.isItemHovered()) {
            showWrappedTooltip(tooltip + String.format("\n60-FPS budget: %.1f ms.", BUDGET_MS));
        }
    }

    private static void drawProfileMetricRow(String label, String value, int valueColor, String tooltip) {
        ImGui.beginGroup();
        var contentMax = ImGui.getWindowContentRegionMaxX() - PERFORMANCE_PROFILE_RIGHT_INSET;
        var start = ImGui.getCursorPosX();
        var available = Math.max(1f, contentMax - start);
        var labelWidth = ImGui.calcTextSizeX(label);
        var valueWidth = ImGui.calcTextSizeX(value);
        if (labelWidth + valueWidth + 14f <= available) {
            ImGui.textColored(HUD_TEXT_DIM, label);
            ImGui.sameLine(contentMax - valueWidth);
            ImGui.textColored(valueColor, value);
        } else {
            ImGui.textColored(HUD_TEXT_DIM, label);
            ImGui.pushTextWrapPos(contentMax);
            ImGui.textColored(valueColor, value);
            ImGui.popTextWrapPos();
        }
        ImGui.endGroup();
        tip(tooltip);
    }

    private static void drawProfileNotice(String text, int color, String tooltip) {
        ImGui.pushTextWrapPos(ImGui.getWindowContentRegionMaxX());
        ImGui.textColored(color, text);
        ImGui.popTextWrapPos();
        tip(tooltip);
    }

    private static int budgetColor(double milliseconds) {
        if (!Double.isFinite(milliseconds)) {
            return HUD_TEXT_DIM;
        }
        return milliseconds > BUDGET_MS * 2d ? HUD_RED : milliseconds > BUDGET_MS ? HUD_MESH : HUD_TERRAIN;
    }

    private static boolean hasFinite(double... values) {
        for (var value : values) {
            if (Double.isFinite(value)) {
                return true;
            }
        }
        return false;
    }

    private static String formatProfileMs(double value) {
        return Double.isFinite(value) ? String.format("%.1f", value) : "n/a";
    }

    private static String formatProfileMsWithUnit(double value) {
        return Double.isFinite(value) ? String.format("%.1f ms", value) : "n/a";
    }

    private static String formatCompactCount(int value) {
        var magnitude = Math.abs((long) value);
        if (magnitude >= 1_000_000L) {
            return String.format("%.1fM", value / 1_000_000d);
        }
        if (magnitude >= 1_000L) {
            return String.format("%.1fk", value / 1_000d);
        }
        return Integer.toString(value);
    }

    public record FrameHistoryStats(float p95Ms, int overBudgetFrames) {}

    private static void drawSceneFieldLabel(String label) {
        ImGui.spacing();
        ImGui.textColored(HudWidgets.MUTED, label);
        ImGui.setNextItemWidth(-1);
    }

    private static void drawSceneSlider(String label, float[] value, float min, float max, String format) {
        HudWidgets.sliderFloat(label, value, min, max, format);
    }

    private static void drawSceneIntSlider(String label, int[] value, int min, int max) {
        HudWidgets.sliderInt(label, value, min, max);
    }

    private static void drawSceneChoice(String label, ImInt value, String[] options) {
        drawSceneFieldLabel(label);
        ImGui.combo("##" + label, value, options);
    }

    private static void drawSceneNumber(String label, ImInt value, int step) {
        drawSceneFieldLabel(label);
        ImGui.inputInt("##" + label, value, step);
    }

    private static @Nullable Tool tool(String name) {
        for (var tool : Tool.values()) {
            if (tool.name().toLowerCase(Locale.ROOT).equals(name)) {
                return tool;
            }
        }
        return null;
    }

    private static void drawDrawerSection(String title) {
        ImGui.spacing();
        ImGui.separator();
        ImGui.textColored(HUD_ACCENT, title);
    }

    private static String skyZoneNote(SkyZones.View zone) {
        var minutes = Math.round(zone.hour() * 60f);
        var light = zone.timeEnv() > 0 ? ", in the light TimeEnv" + zone.timeEnv() : "";
        return String.format(
                Locale.ROOT,
                "This place has its own time of day, %d:%02d%s. Outside it the hour above shows again.",
                Math.floorMod(minutes / 60, 24),
                Math.floorMod(minutes, 60),
                light);
    }

    private static void tip(String text) {
        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
            showWrappedTooltip(text);
        }
    }

    private static void showWrappedTooltip(String text) {
        HudWidgets.showTooltip(text);
    }

    private static String effectName(ParticleEmitter emitter) {
        var asset = emitter.getDiagnosticSource().asset();
        if (asset == null || asset.isBlank()) {
            return emitter.getDiagnosticSource().type();
        }
        return asset.substring(asset.lastIndexOf('.') + 1);
    }

    private static void drawHoverBadge(
            ImDrawList drawList,
            DebugSelection hovered,
            @Nullable String label,
            boolean ortho,
            int screenWidth,
            int screenHeight) {
        var text = label != null
                ? label
                : hovered.typeName()
                        + "  "
                        + hovered.shortName()
                        + (ortho ? "" : "  " + Math.round(hovered.hitDistance()) + "u");
        drawBadge(drawList, text, screenWidth, screenHeight);
    }

    private static void drawBadge(ImDrawList drawList, String text, int screenWidth, int screenHeight) {
        var size = ImGui.calcTextSize(text);
        var x = Math.min(ImGui.getMousePosX() + 16f, screenWidth - size.x - 18f);
        var y = Math.min(ImGui.getMousePosY() + 16f, screenHeight - size.y - 14f);
        x = Math.max(4f, x);
        y = Math.max(4f, y);
        drawList.addRectFilled(x - 6f, y - 4f, x + size.x + 6f, y + size.y + 4f, ImColor.rgba(10, 15, 19, 225), 5f);
        drawList.addRect(x - 6f, y - 4f, x + size.x + 6f, y + size.y + 4f, HUD_ACCENT);
        drawList.addText(x + 1f, y + 1f, OBJECT_NAME_SHADOW_COLOR, text);
        drawList.addText(x, y, HUD_ACCENT, text);
    }

    private static float rayBoxDistance(Vec3 origin, Vec3 dir, Vec3 min, Vec3 max) {
        var tmin = Float.NEGATIVE_INFINITY;
        var tmax = Float.POSITIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            var o = axis == 0 ? origin.getX() : axis == 1 ? origin.getY() : origin.getZ();
            var d = axis == 0 ? dir.getX() : axis == 1 ? dir.getY() : dir.getZ();
            var lo = axis == 0 ? min.getX() : axis == 1 ? min.getY() : min.getZ();
            var hi = axis == 0 ? max.getX() : axis == 1 ? max.getY() : max.getZ();
            if (Math.abs(d) < 1e-8f) {
                if (o < lo || o > hi) {
                    return -1f;
                }
            } else {
                var t1 = (lo - o) / d;
                var t2 = (hi - o) / d;
                tmin = Math.max(tmin, Math.min(t1, t2));
                tmax = Math.min(tmax, Math.max(t1, t2));
            }
        }
        if (tmax < tmin || tmax < 0f) {
            return -1f;
        }
        return tmin >= 0f ? tmin : tmax;
    }

    private static String emitterLabel(ParticleEmitter emitter) {
        var kind = switch (emitter.getKind()) {
            case SPRITE -> "sprite";
            case MESH -> "mesh";
            case BEAM -> "beam";
        };
        var name = emitter.getDebugName();
        return name == null || name.isEmpty() ? kind : kind + ": " + name;
    }

    private static float distanceSquared(Vec3 a, Vec3 b) {
        var dx = a.getX() - b.getX();
        var dy = a.getY() - b.getY();
        var dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private final class GuideGeodata implements Geodata {
        @Override
        public @Nullable String getUnavailable() {
            var state = renderingContext.geodata;
            if (state.path == null) {
                return "Open a geodata folder first: Choose folder… in the Geodata panel, or build one from the"
                        + " client.";
            }
            if (state.getToolsUnavailableReason().isEmpty()) {
                return null;
            }
            if (state.loading) {
                return "Wait a moment: the regions around the view are loading.";
            }
            return !state.enabled && !state.toolsOpen
                    ? null
                    : "No region of the folder is loaded here. Fly over a region the folder holds.";
        }

        @Override
        public @Nullable GeoDataAccess getCells() {
            var state = renderingContext.geodata;
            return state.getToolsUnavailableReason().isEmpty() ? state.queryData() : null;
        }

        @Override
        public double[] getCamera() {
            var position = renderingContext.camera.getPosition();
            return new double[] {position.getX(), position.getY(), position.getZ()};
        }

        @Override
        public void show() {
            workspace.tools.open(Tool.GEODATA);
            renderingContext.geodata.enabled = true;
            renderingContext.geodata.toolsOpen = true;
            geodata.setTool(GeodataWorkspace.Tool.INSPECT);
            uiContext.worldMapOpen = false;
        }

        @Override
        public void goTo(double x, double y, double z) {
            geodata.goTo(renderingContext.geodata, renderingContext.camera, x, y, z);
        }
    }

    private final class GuideWorld implements OpenWorld {
        @Override
        public String getWorkspace() {
            return workspace.getMode() == Mode.EDITING
                    ? "edit"
                    : workspace.getMode().name().toLowerCase(Locale.ROOT);
        }

        @Override
        public @Nullable String getWorkspaceUnavailable(String name) {
            if (uiContext.effectsOnly) {
                return "The Effects mode has no world to explore.";
            }
            if (workspace.getMode() == Mode.EDITING) {
                return LEAVE_EDIT;
            }
            return null;
        }

        @Override
        public void showWorkspace(String name) {
            for (var mode : Mode.values()) {
                if (mode != Mode.EDITING && mode.name().equalsIgnoreCase(name)) {
                    workspace.setMode(mode);
                }
            }
        }

        @Override
        public @Nullable String getMapUnavailable() {
            if (uiContext.effectsOnly) {
                return "The Effects mode has no world map.";
            }
            return namedMap != null ? "A map with a name of its own, such as a lobby, has no world map." : null;
        }

        @Override
        public void openMap() {
            if (!uiContext.worldMapOpen) {
                workspace.toggleMap();
            }
        }

        @Override
        public @Nullable String getExportUnavailable() {
            if (uiContext.effectsOnly) {
                return "The Effects mode has no world to export.";
            }
            return workspace.getMode() == Mode.EDITING ? "Edit saves its own way: Export here is in the Viewer." : null;
        }

        @Override
        public void exportHere() {
            uiContext.exportHereRequested = true;
        }
    }

    private final class GuideSpawns implements Spawns {
        @Override
        public @Nullable String getUnavailable() {
            return spawns.getUnavailableReason();
        }

        @Override
        public @Nullable String showNearestMaker() {
            openSpawns();
            return spawns.showNearestMaker(renderingContext.camera);
        }

        @Override
        public @Nullable String showNearestFixedNpc() {
            openSpawns();
            return spawns.showNearestFixedNpc(renderingContext.camera);
        }

        @Override
        public @Nullable String openNearestNpc() {
            var name = spawns.findNearestNpc(renderingContext.camera);
            if (name != null) {
                workspace.tools.open(Tool.NPCS);
                npcs.select(name);
                uiContext.worldMapOpen = false;
            }
            return name;
        }

        private void openSpawns() {
            workspace.tools.open(Tool.SPAWNS);
            uiContext.worldMapOpen = false;
        }
    }

    private final class GuideAreas implements Areas {
        @Override
        public @Nullable String getUnavailable() {
            return areas.getUnavailableReason();
        }

        @Override
        public @Nullable String showNearestArea() {
            workspace.tools.open(Tool.AREAS);
            uiContext.worldMapOpen = false;
            return areas.showNearestArea(renderingContext.camera);
        }
    }

    private final class GuideMapEvents implements MapEvents {
        @Override
        public @Nullable String getUnavailable() {
            refreshMapEventCatalog();
            return mapEvents.getCatalog().isEmpty()
                    ? "No map events in the loaded tiles. Towns, castle towns and some dungeons have them."
                    : null;
        }

        @Override
        public @Nullable String showNearest() {
            workspace.tools.open(Tool.EVENTS);
            uiContext.worldMapOpen = false;
            refreshMapEvents();
            return mapEvents.showNearest(renderingContext.camera);
        }
    }

    private final class GuideEffects implements Effects {
        @Override
        public @Nullable String getUnavailable() {
            return worldConfig.clientPath() == null ? "Open a game client first." : null;
        }

        @Override
        public void open() {
            workspace.tools.open(Tool.EFFECTS);
            uiContext.worldMapOpen = false;
        }

        @Override
        public boolean isListing() {
            return effects.isListing();
        }

        @Override
        public @Nullable String playExample() {
            var example = effects.getExample();
            if (example == null) {
                return null;
            }
            effects.play(new Order(example, null, null, null, null, null, null, null, null, null, null, true));
            return example.name();
        }
    }

    private final class GuideScenes implements Scenes {
        @Override
        public @Nullable String getDirectorUnavailable() {
            if (!scenesPanel.hasDirector()) {
                return "This view has no video director.";
            }
            if (workspace.getMode() == Mode.ANALYZE || workspace.getMode() == Mode.EDITING) {
                return "The tray of the video director shows in Explore and Inspect. Switch to one of them first.";
            }
            return !scenesPanel.isDirectorOpen() && scenesPanel.hasDirectorDraftFailed()
                    ? "A draft of the video director did not open. The Scenes panel says why."
                    : null;
        }

        @Override
        public void openDirector() {
            workspace.tools.open(Tool.SCENES);
            uiContext.worldMapOpen = false;
            scenesPanel.openDirector();
        }

        @Override
        public @Nullable String getExampleUnavailable() {
            if (namedMap != null) {
                return "A lobby has no cut scenes. Its own camera scenes play from the Scenes panel.";
            }
            if (!scenesPanel.hasCutScenes()) {
                return "This client has no cut scenes. Interlude and older clients have none, and the video director"
                        + " works on all of them.";
            }
            return scenesPanel.findExampleScene(renderingContext.tiles) == null
                    ? "No cut scene plays on the loaded tiles. A row of the Scenes panel opens the tiles of its scene."
                    : null;
        }

        @Override
        public @Nullable String playExample() {
            var scene = scenesPanel.findExampleScene(renderingContext.tiles);
            if (scene == null) {
                return null;
            }
            workspace.tools.open(Tool.SCENES);
            uiContext.worldMapOpen = false;
            renderingContext.gameScenes.play(scene, sceneGround());
            return scene.name();
        }
    }

    private final class GuideEdit implements Edit {
        @Override
        public @Nullable String getUnavailable() {
            if (workspace.getMode() != Mode.EDITING) {
                return "Open Edit first: Edit on the top bar.";
            }
            if (workspace.isRecoveryPending()) {
                return "Answer Recover the Edit session first.";
            }
            var panel = workspace.getEditingPanel();
            if (panel == null) {
                return "Wait until Edit has loaded.";
            }
            return panel.isBusy() ? "Wait for the check or the save to finish." : null;
        }

        @Override
        public boolean chooseTool(String tool) {
            var panel = openPanel();
            return panel != null && panel.chooseTool(tool);
        }

        @Override
        public void openLibrary() {
            var panel = workspace.getEditingPanel();
            if (panel != null) {
                panel.openLibrary();
                uiContext.worldMapOpen = false;
            }
        }

        @Override
        public boolean hasSelection() {
            var panel = workspace.getEditingPanel();
            return panel != null && panel.hasSelection();
        }

        @Override
        public void frameSelection() {
            var panel = workspace.getEditingPanel();
            if (panel != null) {
                panel.frameSelection();
                uiContext.worldMapOpen = false;
            }
        }

        @Override
        public void openSave() {
            var panel = openPanel();
            if (panel != null) {
                panel.showSave();
            }
        }

        private @Nullable EditingPanel openPanel() {
            var panel = workspace.getEditingPanel();
            if (panel != null && !workspace.tools.isOpen(Tool.OBJECT)) {
                workspace.tools.open(Tool.OBJECT);
            }
            uiContext.worldMapOpen = false;
            return panel;
        }
    }
}
