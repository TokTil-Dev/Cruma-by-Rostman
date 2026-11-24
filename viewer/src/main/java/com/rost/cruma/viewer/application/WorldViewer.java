package com.rost.cruma.viewer.application;

import com.rost.cruma.core.world.ClientFov;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.NamedMapStart;
import com.rost.cruma.core.world.WorldMapCatalog;
import com.rost.cruma.viewer.agent.Access;
import com.rost.cruma.viewer.agent.AgentRuntime;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.agent.ViewerEndpoint;
import com.rost.cruma.viewer.application.IdleFrames.State;
import com.rost.cruma.viewer.config.AgentSettings;
import com.rost.cruma.viewer.config.Config.Capture.Output;
import com.rost.cruma.viewer.config.Config.Viewer.Agent;
import com.rost.cruma.viewer.config.Config.Viewer.Camera;
import com.rost.cruma.viewer.config.Config.Viewer.Editing;
import com.rost.cruma.viewer.config.Config.Viewer.World;
import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.config.ConfigLoader.Options;
import com.rost.cruma.viewer.config.InterfaceScale;
import com.rost.cruma.viewer.launch.FileManager;
import com.rost.cruma.viewer.render.RenderingContext;
import com.rost.cruma.viewer.render.ScreenLens;
import com.rost.cruma.viewer.render.ViewerBenchmarkScenario;
import com.rost.cruma.viewer.render.bgfx.BgfxViewerRenderBackend;
import com.rost.cruma.viewer.spawn.MapPawnModels;
import com.rost.cruma.viewer.system.AmbientSoundSystem;
import com.rost.cruma.viewer.system.CameraSystem;
import com.rost.cruma.viewer.system.CaptureProgressSystem;
import com.rost.cruma.viewer.system.LevelSystem;
import com.rost.cruma.viewer.system.LoadingSystem;
import com.rost.cruma.viewer.system.MapPawnSystem;
import com.rost.cruma.viewer.system.MusicSystem;
import com.rost.cruma.viewer.system.System;
import com.rost.cruma.viewer.system.UiSystem;
import com.rost.cruma.viewer.system.WindowSystem;
import com.rost.cruma.viewer.system.WindowSystem.Placement;
import com.rost.cruma.viewer.ui.CrumaBranding;
import com.rost.cruma.viewer.ui.Motion;
import com.rost.cruma.viewer.ui.WorkspaceLayout.Mode;
import glm_.detail.GlmCoordinateSystem;
import glm_.detail.GlmDepthClipSpace;
import glm_.detail.SetupKt;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

public class WorldViewer {
    private static final long MAX_FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);
    private static final long FPS_UPDATE_FREQUENCY_NS = TimeUnit.SECONDS.toNanos(1L);

    private static final boolean DEBUG_LOOP_TIMINGS = Boolean.getBoolean("cruma.viewer.debug_loop_timings");
    private static final double EFFECTS_PITCH = -10;
    private static final Logger log = LogManager.getLogger(WorldViewer.class);

    private final World worldConfig;
    private final @Nullable Camera cameraConfig;
    private final List<String> maps;
    private final @Nullable Path configDirectory;
    private final InterfaceScale interfaceScale;
    private @Nullable Placement placement;
    private @Nullable Path screenshotFolder;
    private boolean screenshotInterface = true;
    private boolean effectsOnly;
    private boolean editingAtStart;
    private @Nullable Mode modeAtStart;
    private boolean cameraResumed;
    private boolean reloadRequested;
    private @Nullable Pose cameraAtClose;
    private @Nullable Mode modeAtClose;
    private @Nullable String sceneAtStart;
    private boolean copyAtStart;
    private @Nullable Path fileAtStart;
    private @Nullable Scene sceneFromFile;
    private @Nullable Path scenesFolder;
    private @Nullable SceneRequest sceneRequest;
    private @Nullable SceneRecording recording;
    private double recordedHour = Double.NaN;
    private @Nullable MapEventState recordedEvents;

    private Editing editingSettings;
    private Agent agentSettings = Agent.DEFAULT;
    private boolean agentOwned;
    private boolean agentHidden;
    private boolean agentHeadless;

    public WorldViewer(World worldConfig, @Nullable Camera cameraConfig) {
        this(
                worldConfig,
                cameraConfig,
                WorldMapCatalog.discover(
                        worldConfig.clientPath(),
                        worldConfig.variant(),
                        worldConfig.minXTile(),
                        worldConfig.maxXTile(),
                        worldConfig.minYTile(),
                        worldConfig.maxYTile()));
    }

    public WorldViewer(World worldConfig, @Nullable Camera cameraConfig, List<String> maps) {
        this(worldConfig, cameraConfig, maps, null);
    }

    public WorldViewer(
            World worldConfig, @Nullable Camera cameraConfig, List<String> maps, @Nullable Path configDirectory) {
        this(worldConfig, cameraConfig, maps, configDirectory, InterfaceScale.AUTOMATIC);
    }

    public WorldViewer(
            World worldConfig,
            @Nullable Camera cameraConfig,
            List<String> maps,
            @Nullable Path configDirectory,
            InterfaceScale interfaceScale) {
        this.worldConfig = worldConfig;
        this.cameraConfig = cameraConfig;
        this.maps = List.copyOf(maps);
        this.configDirectory = configDirectory;
        this.interfaceScale = interfaceScale;
    }

    static void switchEditing(
            UiSystem ui,
            BgfxViewerRenderBackend backend,
            RenderingContext scene,
            UiContext context,
            LoadingSystem loading) {
        var editing = context.workspaceMode == Mode.EDITING;
        var everyMap = editing && context.editingPrepared;
        if (context.editingPrepared) {
            scene.restoreEditedSurfaces();
            ui.closeEditingSession();
            backend.clearEditorPreview();
            scene.closeEditing();
        }
        if (editing) {
            scene.prepareEditing(loading.getLoadedMaps());
        }
        context.editingPrepared = editing;
        var camera = scene.camera.getPosition();
        loading.reload(editing, camera.getX(), camera.getY(), everyMap);
    }

    public WorldViewer editingSettings(Editing settings) {
        editingSettings = settings;
        return this;
    }

    public WorldViewer agent(Agent settings, boolean owned, boolean hidden, boolean headless) {
        agentSettings = settings;
        agentOwned = owned;
        agentHidden = hidden;
        agentHeadless = headless;
        return this;
    }

    public WorldViewer openAt(@Nullable Placement placement) {
        this.placement = placement;
        return this;
    }

    public WorldViewer screenshotsIn(@Nullable Path folder) {
        this.screenshotFolder = folder;
        return this;
    }

    public WorldViewer effectsOnly(boolean effects) {
        this.effectsOnly = effects;
        return this;
    }

    public WorldViewer sceneAtStart(@Nullable String scene) {
        this.sceneAtStart = scene;
        return this;
    }

    public WorldViewer copyAtStart(boolean copy) {
        this.copyAtStart = copy;
        return this;
    }

    public WorldViewer fileAtStart(@Nullable Path file) {
        this.fileAtStart = file;
        return this;
    }

    public WorldViewer scenesIn(@Nullable Path folder) {
        this.scenesFolder = folder;
        return this;
    }

    public WorldViewer sceneFromFile(@Nullable Scene scene) {
        this.sceneFromFile = scene;
        return this;
    }

    public WorldViewer recording(SceneRecording recording, double hour, @Nullable MapEventState events) {
        this.recording = recording;
        recordedHour = hour;
        recordedEvents = events;
        return this;
    }

    public @Nullable SceneRequest getSceneRequest() {
        return sceneRequest;
    }

    public WorldViewer editingAtStart(boolean editing) {
        this.editingAtStart = editing;
        return this;
    }

    public WorldViewer modeAtStart(@Nullable Mode mode) {
        this.modeAtStart = mode;
        return this;
    }

    public WorldViewer resumeCamera(boolean resumed) {
        this.cameraResumed = resumed;
        return this;
    }

    public boolean isReloadRequested() {
        return reloadRequested;
    }

    public @Nullable Pose getCameraAtClose() {
        return cameraAtClose;
    }

    public @Nullable Mode getModeAtClose() {
        return modeAtClose;
    }

    public record Pose(float x, float y, float z, double yaw, double pitch) {
        static Pose of(com.rost.cruma.viewer.render.Camera camera) {
            var place = camera.getWorldFocusPosition();
            return new Pose(place.getX(), place.getY(), place.getZ(), camera.getYawDegrees(), camera.getPitchDegrees());
        }
    }

    public WorldViewer screenshotInterface(boolean include) {
        this.screenshotInterface = include;
        return this;
    }

    public @Nullable Placement getPlacement() {
        return placement;
    }

    public boolean run() {
        return run(true);
    }

    public boolean run(boolean visible) {
        if (cameraConfig == null) {
            throw new IllegalStateException("The interactive viewer needs the viewer.camera config block");
        }
        SetupKt.setGLM_COORDINATE_SYSTEM(GlmCoordinateSystem.LEFT_HANDED);
        SetupKt.setGLM_DEPTH_CLIP_SPACE(GlmDepthClipSpace.ZERO_TO_ONE);

        var applicationContext = new ApplicationContext();
        var windowContext = new WindowContext();
        var uiContext = new UiContext();
        if (screenshotFolder != null) {
            uiContext.screenshots.setFolder(screenshotFolder);
        }
        uiContext.screenshots.includeInterface(screenshotInterface);
        uiContext.effectsOnly = effectsOnly;
        uiContext.sceneAtStart = sceneAtStart;
        uiContext.copyAtStart = copyAtStart;
        uiContext.fileAtStart = fileAtStart;
        uiContext.sceneFromFile = sceneFromFile;
        uiContext.scenesFolder = scenesFolder;
        uiContext.recording = recording;
        uiContext.agentViewer = agentOwned;
        uiContext.configDirectory = recording == null ? configDirectory : null;
        uiContext.loadedMaps = maps;
        if (modeAtStart != null) {
            uiContext.workspaceMode = modeAtStart;
        } else if (editingAtStart) {
            uiContext.workspaceMode = Mode.EDITING;
        }
        var renderingContext =
                new RenderingContext(false, worldConfig.rendering().nearPlane());
        var benchmarkScenario = ViewerBenchmarkScenario.load();

        var start = cameraConfig.start();
        var named = WorldMapCatalog.findStandaloneMap(maps);
        renderingContext.setStandaloneMap(named);
        if (named != null) {
            var lobby = ClientFov.readLobby(worldConfig.clientPath());
            renderingContext.camera.setLens(ScreenLens.lobby(lobby.leastShape()));
            renderingContext.camera.setFov(Math.clamp(lobby.fov(), 1, 170));
            log.info(
                    "Map {} is drawn as a lobby: field of view {}, least shape {}",
                    named,
                    lobby.fov(),
                    lobby.leastShape());
        }
        var namedStart = named == null || cameraResumed
                ? null
                : NamedMapStart.find(worldConfig.clientPath(), named).orElse(null);
        if (namedStart != null) {
            renderingContext.camera.setPosition(namedStart.x(), namedStart.y(), namedStart.z());
            renderingContext.camera.setDirection(namedStart.yawDegrees(), namedStart.pitchDegrees());
            log.info(
                    "Camera starts in map {} at ({}, {}, {}) yaw={} pitch={}",
                    named,
                    namedStart.x(),
                    namedStart.y(),
                    namedStart.z(),
                    namedStart.yawDegrees(),
                    namedStart.pitchDegrees());
        } else {
            renderingContext.camera.setPosition(start.x(), start.y(), start.z());
            renderingContext.camera.setDirection(cameraConfig.startYaw(), cameraConfig.startPitch());
            log.info(
                    "Camera starts at '{}' ({}, {}, {}) yaw={} pitch={}",
                    start.name(),
                    start.x(),
                    start.y(),
                    start.z(),
                    cameraConfig.startYaw(),
                    cameraConfig.startPitch());
        }
        if (effectsOnly && maps.isEmpty() && !cameraResumed) {
            renderingContext.camera.setDirection(cameraConfig.startYaw(), EFFECTS_PITCH);
        }

        var systems = new ArrayList<System>();
        var windowSystem = new WindowSystem(
                windowContext,
                applicationContext,
                "Cruma",
                benchmarkScenario.getDisplayWidth(1440),
                benchmarkScenario.getDisplayHeight(1000),
                visible,
                false,
                benchmarkScenario.isEnabled() ? null : placement);
        if (!benchmarkScenario.isEnabled()) {
            windowSystem.fitInterfaceScale(interfaceScale);
        }
        uiContext.automaticScalePercent =
                () -> Math.round(windowSystem.getInterfaceFactor(InterfaceScale.AUTOMATIC) * 100);
        var renderBackend = new BgfxViewerRenderBackend(renderingContext, windowContext, uiContext, worldConfig);
        renderingContext.configureEditing(worldConfig, renderBackend);
        renderingContext.configureRecovery(editingSettings);

        systems.add(windowSystem);
        systems.add(renderBackend);
        systems.add(new MapPawnSystem(
                renderingContext,
                uiContext,
                new MapPawnModels(worldConfig.clientPath(), worldConfig.variant()),
                renderBackend::getMapPawnEffects));
        var uiSystem = new UiSystem(
                worldConfig,
                cameraConfig,
                uiContext,
                windowContext,
                renderingContext,
                renderBackend,
                configDirectory,
                maps,
                interfaceScale);
        systems.add(uiSystem);
        var agent = new AgentRuntime(
                Access.parse(agentSettings.access()), agentSettings.enabled(), agentOwned, agentHidden);
        agent.setHeadless(agentHeadless);
        if (configDirectory != null && recording == null) {
            agent.prepare(
                    ViewerEndpoint.folder(configDirectory),
                    worldConfig.clientPath().toString(),
                    maps,
                    AgentSettings.in(new ConfigLoader(new Options(configDirectory, false, false))));
            if (!agentHidden) {
                uiSystem.setAgent(agent);
            }
        }
        if (recording != null) {
            showRecorded(uiContext, renderingContext);
        }
        var editingPrepared = uiContext.editingReloadRequested;
        if (editingPrepared) {
            uiContext.editingReloadRequested = false;
            uiContext.editingPrepared = true;
            renderingContext.prepareEditing();
        }
        uiContext.rendering.mouseSensitivity = (float) cameraConfig.mouseSensitivity();
        systems.add(new CameraSystem(renderingContext, windowContext, uiContext));
        if (!effectsOnly && recording == null && !agentHidden) {
            systems.add(new MusicSystem(worldConfig, renderingContext, uiContext));
        }
        if (recording == null && !agentHidden) {
            systems.add(new AmbientSoundSystem(worldConfig, renderingContext, uiContext));
        }
        var loading = new LoadingSystem(
                worldConfig,
                maps,
                renderBackend.getUploads(),
                windowContext,
                renderingContext.camera.getPosition().getX(),
                renderingContext.camera.getPosition().getY(),
                uiContext,
                editingPrepared);
        systems.add(loading);
        var agentView = new ViewerContext(
                worldConfig,
                cameraConfig,
                maps,
                configDirectory,
                applicationContext,
                uiContext,
                windowContext,
                renderingContext,
                renderBackend,
                uiSystem,
                loading,
                agent);

        applicationContext.running = true;

        Throwable viewerFailure = null;
        try {
            for (var system : systems) {
                system.start();
            }
            benchmarkScenario.applyInitial(renderingContext.camera, uiContext);
            renderBackend.followRayOptions();
            agent.start();

            var pacer = new FramePacer(
                    worldConfig.rendering().graphics().frameRateCap(), MAX_FRAME_NANOS, java.lang.System.nanoTime());
            var idle = new IdleFrames(visible && !benchmarkScenario.isEnabled());

            long fpsWindowStartedAtNs = java.lang.System.nanoTime();
            int completedFrames = 0;
            long worldDrawnNs = fpsWindowStartedAtNs;
            var beginTimes = DEBUG_LOOP_TIMINGS ? new long[systems.size()] : null;
            var endTimes = DEBUG_LOOP_TIMINGS ? new long[systems.size()] : null;
            while (applicationContext.running) {
                if (agent.ends()) {
                    break;
                }
                if (agent.rests(uiContext.rendering.loading.active || uiContext.rendering.loading.reloading)) {
                    GLFW.glfwPollEvents();
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                    continue;
                }
                if (uiContext.rendering.frameRateCap != pacer.getFrameRateCap()) {
                    pacer.setFrameRateCap(uiContext.rendering.frameRateCap);
                }
                var nowNs = java.lang.System.nanoTime();
                if (recording != null) {
                    recording.poll();
                    if (recording.isFinished()) {
                        break;
                    }
                    if (recording.waitsForEncoder()) {
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
                        continue;
                    }
                } else {
                    var waitNs = pacer.waitNanos(nowNs);
                    if (waitNs > 0) {
                        LockSupport.parkNanos(Math.min(waitNs, TimeUnit.MILLISECONDS.toNanos(1L)));
                        continue;
                    }
                }
                var markMoved = CrumaBranding.takeMarkMoved();
                var idleState = idleState(windowContext, uiContext, renderingContext, renderBackend, agent, markMoved);
                if (idle.waits(idleState, nowNs)) {
                    GLFW.glfwWaitEventsTimeout(idle.waitSeconds(idleState));
                    var waitedNs = nowNs;
                    nowNs = java.lang.System.nanoTime();
                    if (windowContext.input.getLastNanos() > waitedNs) {
                        pacer.restart(nowNs);
                    }
                }
                var frameMs = pacer.beginFrame(nowNs);
                var agentCalls = agent.getActivity().getRevision();
                var agentCalled = agent.isBusy();
                if (agent.isOpen() || agentCalled) {
                    agent.frame(agentView);
                }
                agentCalled |= agent.isBusy() || agent.getActivity().getRevision() != agentCalls;
                var rested = idleState(windowContext, uiContext, renderingContext, renderBackend, agent, markMoved);
                renderBackend.setInterfaceOnly(
                        !agentCalled && idle.drawsInterfaceOnly(rested, java.lang.System.nanoTime(), worldDrawnNs));

                if (DEBUG_LOOP_TIMINGS) {
                    for (var index = 0; index < systems.size(); index++) {
                        var started = java.lang.System.nanoTime();
                        systems.get(index).frameBegin(frameMs);
                        beginTimes[index] = java.lang.System.nanoTime() - started;
                    }
                    for (var index = systems.size() - 1; index >= 0; index--) {
                        var started = java.lang.System.nanoTime();
                        systems.get(index).frameEnd(frameMs);
                        endTimes[index] = java.lang.System.nanoTime() - started;
                    }
                } else {
                    for (var system : systems) {
                        system.frameBegin(frameMs);
                    }
                    for (var system : systems.reversed()) {
                        system.frameEnd(frameMs);
                    }
                }
                if (renderBackend.isBenchmarkComplete() && !renderBackend.hasPendingWork()) {
                    applicationContext.running = false;
                }
                if (uiContext.returnToMenuRequested) {
                    applicationContext.running = false;
                } else if (uiContext.editingReloadRequested) {
                    uiContext.editingReloadRequested = false;
                    switchEditing(uiSystem, renderBackend, renderingContext, uiContext, loading);
                } else if (renderBackend.takeRayDataReload()) {
                    var camera = renderingContext.camera.getPosition();
                    loading.reload(uiContext.editingPrepared, camera.getX(), camera.getY(), true);
                }

                var frameCompletedAtNs = java.lang.System.nanoTime();
                var worldDrawn = !renderBackend.isInterfaceOnly();
                if (worldDrawn) {
                    completedFrames++;
                    worldDrawnNs = frameCompletedAtNs;
                    uiContext.rendering.stats.loopLatencyNs = frameCompletedAtNs - nowNs;
                }
                if (DEBUG_LOOP_TIMINGS && worldDrawn && uiContext.rendering.stats.loopLatencyNs > 16_666_667L) {
                    var phases = new StringBuilder();
                    for (var index = 0; index < systems.size(); index++) {
                        phases.append(systems.get(index).getClass().getSimpleName())
                                .append('=')
                                .append(beginTimes[index] / 1_000_000.0)
                                .append('/')
                                .append(endTimes[index] / 1_000_000.0)
                                .append(' ');
                    }
                    log.info(
                            "Loop timing: frame={} completedNs={} loopMs={} begin/endMs {}",
                            renderBackend.getStats().frameNumber(),
                            frameCompletedAtNs,
                            uiContext.rendering.stats.loopLatencyNs / 1_000_000.0,
                            phases);
                }
                var fpsWindowNs = frameCompletedAtNs - fpsWindowStartedAtNs;
                if (fpsWindowNs >= FPS_UPDATE_FREQUENCY_NS) {
                    var fps = (int) Math.round(completedFrames * 1_000_000_000.0 / fpsWindowNs);
                    completedFrames = 0;
                    fpsWindowStartedAtNs = frameCompletedAtNs;
                    uiContext.rendering.stats.fps = fps;
                }
            }
            cameraAtClose = Pose.of(renderingContext.camera);
            modeAtClose = uiContext.workspaceMode;
        } catch (RuntimeException | Error failure) {
            viewerFailure = failure;
            throw failure;
        } finally {
            agent.end();
            try {
                renderingContext.closeEditing();
            } finally {
                stopSystems(systems, windowContext, viewerFailure);
            }
            if (windowSystem.getPlacement() != null) {
                placement = windowSystem.getPlacement();
            }
        }
        sceneRequest = uiContext.sceneRequest;
        reloadRequested = uiContext.returnToMenuRequested && uiContext.settingsReloadRequested && sceneRequest == null;
        return uiContext.returnToMenuRequested && !reloadRequested;
    }

    public void capture(Output output) {
        capture(output, false);
    }

    public boolean capture(Output output, boolean interactive) {
        return capture(output, interactive, false);
    }

    public boolean capture(Output output, boolean interactive, Consumer<Path> folderReady) {
        return capture(output, interactive, false, folderReady);
    }

    public boolean capturePreview(Output output, boolean interactive) {
        return capture(output, interactive, true);
    }

    private void showRecorded(UiContext ui, RenderingContext scene) {
        if (!Double.isNaN(recordedHour)) {
            ui.rendering.timeOfDay.set((float) recordedHour);
        }
        if (recordedEvents != null) {
            ui.rendering.gameEventStates = recordedEvents;
        }
        scene.geodata.enabled = false;
    }

    private boolean capture(Output output, boolean interactive, boolean mapPreview) {
        return capture(output, interactive, mapPreview, FileManager::tryOpenFolder);
    }

    private boolean capture(Output output, boolean interactive, boolean mapPreview, Consumer<Path> folderReady) {
        SetupKt.setGLM_COORDINATE_SYSTEM(GlmCoordinateSystem.LEFT_HANDED);
        SetupKt.setGLM_DEPTH_CLIP_SPACE(GlmDepthClipSpace.ZERO_TO_ONE);
        CaptureFolders.prepare(output, mapPreview);
        if (!mapPreview && output.deleteOldImages()) {
            log.info("Deleted {} old image(s) from {}", OldCaptureImages.delete(output), output.directory());
        }

        var applicationContext = new ApplicationContext();
        var windowContext = new WindowContext();
        var uiContext = new UiContext();
        var renderingContext =
                new RenderingContext(true, worldConfig.rendering().nearPlane());
        uiContext.rendering.setDefaults(worldConfig.rendering());
        uiContext.rendering.customLocalLightShadows = false;
        uiContext.geodata.setDefaults();
        uiContext.rendering.zNear.set(1);

        var systems = new ArrayList<System>();
        var windowSystem = new WindowSystem(
                windowContext,
                applicationContext,
                "Cruma | Capture",
                interactive ? 680 : 64,
                interactive ? 350 : 64,
                interactive);
        windowSystem.fitInterfaceScale(interfaceScale);
        var renderBackend = new BgfxViewerRenderBackend(
                renderingContext, windowContext, uiContext, worldConfig, output, mapPreview);
        var levelSystem = new LevelSystem(worldConfig, maps, renderBackend.getUploads(), windowContext);

        systems.add(windowSystem);
        systems.add(levelSystem);
        systems.add(renderBackend);
        if (!mapPreview) {
            systems.add(new MapPawnSystem(
                    renderingContext,
                    uiContext,
                    new MapPawnModels(worldConfig.clientPath(), worldConfig.variant()),
                    () -> null));
        }
        if (interactive) {
            renderBackend.setCaptureUiEnabled(true);
            systems.add(new CaptureProgressSystem(
                    windowContext,
                    applicationContext,
                    renderBackend.getUiRenderer(),
                    levelSystem.getMapCount(),
                    output.directory().toAbsolutePath().toString(),
                    interfaceScale));
        }
        applicationContext.running = true;

        Throwable captureFailure = null;
        try {
            for (var system : systems) {
                system.start();
            }

            log.info(
                    "Capture starts: {} tile(s) at {}x{}, tiles={} radar={}",
                    levelSystem.getMapCount(),
                    output.tileResolution(),
                    output.tileResolution(),
                    output.writeTiles() ? output.directory() : "disabled",
                    output.radar().enabled() ? output.radarPath() : "disabled");
            if (!mapPreview) {
                for (var view : output.viewsOutside(levelSystem.getMapNames())) {
                    log.warn(
                            "View {} is skipped. Tile {} is not loaded: the client has no such map, or it is outside"
                                    + " the tile range",
                            view.label(),
                            view.tile());
                }
            }

            if (interactive) {
                for (var system : systems) {
                    if (system != levelSystem) {
                        system.frameBegin(0);
                    }
                }
                for (var system : systems.reversed()) {
                    if (system != levelSystem) {
                        system.frameEnd(0);
                    }
                }
            }
            long lastUpdateAt = TimeUnit.NANOSECONDS.toMillis(java.lang.System.nanoTime());
            while (applicationContext.running && (!levelSystem.isComplete() || renderBackend.hasPendingWork())) {
                var currentTime = TimeUnit.NANOSECONDS.toMillis(java.lang.System.nanoTime());
                var passedTime = currentTime - lastUpdateAt;
                lastUpdateAt = currentTime;

                for (var system : systems) {
                    system.frameBegin(passedTime);
                }
                for (var system : systems.reversed()) {
                    system.frameEnd(passedTime);
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
            }
        } catch (RuntimeException | Error e) {
            captureFailure = e;
            throw e;
        } finally {
            stopSystems(systems, windowContext, captureFailure);
        }
        if (applicationContext.running
                && !mapPreview
                && output.openFolderOnComplete()
                && !windowContext.tiles.isEmpty()) {
            var directory = output.writeTiles()
                    ? output.directory()
                    : output.radarPath().toAbsolutePath().getParent();
            folderReady.accept(directory);
        }
        return applicationContext.running;
    }

    private static State idleState(
            WindowContext window,
            UiContext ui,
            RenderingContext scene,
            BgfxViewerRenderBackend backend,
            AgentRuntime agent,
            boolean markMoved) {
        var loading = ui.rendering.loading;
        return new State(
                scene.playback.isPaused(),
                window.input.isHeld(),
                window.input.getLastNanos(),
                loading.active || loading.reloading || ui.editingReloadRequested,
                Motion.isAnimating(),
                backend.hasPendingWork() || scene.hasEditingWork() || ui.directorPlaying || agent.isBusy(),
                markMoved);
    }

    private static void stopSystems(List<System> systems, WindowContext windowContext, Throwable failure) {
        Throwable stopFailure = null;
        for (var system : systems.reversed()) {
            try {
                system.stop(windowContext);
            } catch (RuntimeException | Error e) {
                if (failure != null) {
                    if (e != failure) {
                        failure.addSuppressed(e);
                    }
                } else if (stopFailure == null) {
                    stopFailure = e;
                } else {
                    stopFailure.addSuppressed(e);
                }
            }
        }
        if (failure == null && stopFailure instanceof Error error) {
            throw error;
        }
        if (failure == null && stopFailure instanceof RuntimeException runtime) {
            throw runtime;
        }
    }
}
