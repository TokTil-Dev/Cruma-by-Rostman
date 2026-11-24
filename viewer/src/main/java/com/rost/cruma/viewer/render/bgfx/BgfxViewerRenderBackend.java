package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.editing.ActorTransform;
import com.rost.cruma.core.env.FogRanges;
import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.world.Entity;
import com.rost.cruma.core.world.Entity.TerrainAlphaAtlas;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.UnrealLoader.DecorationDensity;
import com.rost.cruma.core.world.UnrealLoader.DecorationGround;
import com.rost.cruma.core.world.WaterVolume;
import com.rost.cruma.core.world.ZoneStateChoice;
import com.rost.cruma.viewer.application.SceneRecording;
import com.rost.cruma.viewer.application.SceneRecording.SceneState;
import com.rost.cruma.viewer.application.Screenshots;
import com.rost.cruma.viewer.application.UiContext;
import com.rost.cruma.viewer.application.ViewClipping;
import com.rost.cruma.viewer.application.WindowContext;
import com.rost.cruma.viewer.application.WindowContext.Keyboard.Key;
import com.rost.cruma.viewer.application.WindowContext.Tile;
import com.rost.cruma.viewer.config.CaptureView;
import com.rost.cruma.viewer.config.Config.Capture.Output;
import com.rost.cruma.viewer.config.Config.Viewer.World;
import com.rost.cruma.viewer.config.CutCircle;
import com.rost.cruma.viewer.config.ViewContent;
import com.rost.cruma.viewer.render.EditorEffectStage;
import com.rost.cruma.viewer.render.EditorTopology;
import com.rost.cruma.viewer.render.ParticleEmitter;
import com.rost.cruma.viewer.render.RenderingContext;
import com.rost.cruma.viewer.render.SceneClearance;
import com.rost.cruma.viewer.render.ViewMode;
import com.rost.cruma.viewer.render.ViewerBenchmarkProperties;
import com.rost.cruma.viewer.render.ViewerBenchmarkScenario;
import com.rost.cruma.viewer.render.api.DebugSceneHit;
import com.rost.cruma.viewer.render.api.DebugSceneObject;
import com.rost.cruma.viewer.render.api.DebugSceneObject.Category;
import com.rost.cruma.viewer.render.api.EffectPreview;
import com.rost.cruma.viewer.render.api.ReadbackRequest;
import com.rost.cruma.viewer.render.api.ReadbackRequest.Source;
import com.rost.cruma.viewer.render.api.ReadbackTicket;
import com.rost.cruma.viewer.render.api.ReadbackTicket.State;
import com.rost.cruma.viewer.render.api.RenderDiagnostics;
import com.rost.cruma.viewer.render.api.RenderDiagnostics.CpuPhase;
import com.rost.cruma.viewer.render.api.RenderDiagnostics.OptimizationStatus;
import com.rost.cruma.viewer.render.api.RenderDiagnostics.ParallelEncoding;
import com.rost.cruma.viewer.render.api.RenderExtents;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.api.RenderStats;
import com.rost.cruma.viewer.render.api.SceneUploadSink;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.DynamicResolution;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.Upscaler;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.UpscalerAlgorithm;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.UpscalerPreset;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import com.rost.cruma.viewer.render.bgfx.BgfxBenchmarkRecorder.GpuSubmissionCounts;
import com.rost.cruma.viewer.render.bgfx.BgfxObjectPickerReadback.Request;
import com.rost.cruma.viewer.render.bgfx.BgfxSpawnShadowLayer.Snapshot;
import com.rost.cruma.viewer.render.bgfx.BgfxViewAllocator.Domain;
import com.rost.cruma.viewer.render.upscale.DynamicResolutionController;
import com.rost.cruma.viewer.render.upscale.UpscalerProvider;
import com.rost.cruma.viewer.render.upscale.UpscalerProvider.Plan;
import com.rost.cruma.viewer.render.upscale.UpscalerProviders;
import com.rost.cruma.viewer.render.upscale.UpscalerRuntimeState;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import imgui.ImGui;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.bgfx.BGFX;

public final class BgfxViewerRenderBackend implements ViewerRenderBackend {
    private static final Extent ZERO_EXTENT = new Extent(0, 0);
    private static final Extent DEFAULT_CAPTURE_EXTENT =
            new Extent(RenderExtents.CAPTURE_WIDTH, RenderExtents.CAPTURE_HEIGHT);

    private static final int SCREENSHOT_WARMUP_FRAMES = 2;

    private static final boolean DEBUG_LOOP_TIMINGS = Boolean.getBoolean("cruma.viewer.debug_loop_timings");
    private static final boolean FIXED_NEAR = Boolean.getBoolean("cruma.viewer.debug_fixed_near");
    private static final Logger log = LogManager.getLogger(BgfxViewerRenderBackend.class);
    private final WindowContext windowContext;
    private final RenderingContext renderingContext;
    private final UiContext uiContext;
    private final BgfxDevice device;
    private final BgfxSceneUploadSink uploads;
    private final BgfxSceneRenderer sceneRenderer;
    private final BgfxGeodataRenderer geodataRenderer;
    private final BgfxSpawnRenderer spawnRenderer;
    private final BgfxViewerUiRenderer uiRenderer;
    private final ViewerGraphicsConfig graphics;
    private final Output captureOutput;
    private boolean captureUiEnabled;

    private Extent captureExtent;
    private UpscalerProvider provider;
    private final UpscalerProvider nativeProvider;
    private final Upscaler nativeUpscalerConfig;
    private final BgfxViewAllocator views;
    private final BgfxFrameTargets frameTargets;
    private final BgfxDisplayTarget benchmarkVisualTarget;
    private final BgfxSpatialUpscaler upscaler;
    private final BgfxMetalRayQueries metalRayQueries;
    private final BgfxCustomLightingProcessor customLightingProcessor;
    private final BgfxPostProcessor postProcessor;
    private final UnderwaterColorTransition underwaterColorTransition;
    private final BgfxReadbackQueue readbacks;
    private final BgfxObjectPickerReadback objectPickerReadback;
    private final BgfxObjectPickerReadback[] markerReadbacks = new BgfxObjectPickerReadback[MARKERS];
    private BgfxScenePointsReadback pointsReadback;
    private final BgfxCaptureWriter captureWriter;
    private final BgfxPerformanceTracker performance;
    private final BgfxBenchmarkRecorder benchmark;
    private final ViewerBenchmarkScenario benchmarkScenario;
    private final Path benchmarkVisualOutput;
    private final Extent benchmarkDisplayExtent;
    private final int benchmarkStreamingReadyTiles;
    private final UpscalerRuntimeState upscalerRuntime;
    private DynamicResolutionController dynamicResolution;
    private Plan framePlan;
    private Upscaler cachedDynamicBase;
    private Upscaler cachedDynamicConfig;
    private double cachedDynamicScale = Double.NaN;
    private Upscaler plannedConfig;
    private Extent plannedDisplay;
    private UpscalerProvider plannedProvider;
    private String plannedFailure = "";
    private boolean plannedCapture;
    private ReadbackTicket screenshotTicket;
    private int screenshotFrames;
    private boolean screenScreenshotRequested;
    private @Nullable AgentShot agentShot;
    private @Nullable ScreenScreenshot screenScreenshot;
    private BgfxDisplayTarget screenshotTarget;
    private final @Nullable SceneRecording recording;
    private BgfxDisplayTarget recordingTarget;
    private ReadbackTicket mapCaptureTicket;
    private List<CaptureView> captureShots = List.of();
    private int captureShotsMapIndex = -1;
    private MapEventState captureClientEvents = MapEventState.ALL_SHOWN;
    private @Nullable CaptureView captureEventsShot;
    private String captureEventsMap = "";
    private @Nullable MapEventState captureShotEvents;
    private int captureShot;
    private @Nullable CutCircle viewerCircle;
    private ReadbackTicket benchmarkRawVisualTicket;
    private ReadbackTicket benchmarkVisualTicket;
    private boolean worldTargetActive;
    private boolean directToBackbuffer;
    private boolean interfaceOnlyAsked;
    private boolean interfaceOnly;
    private boolean interfaceOnlyBefore;
    private boolean afterInterfaceOnly;
    private @Nullable BgfxTexture keptWorld;
    private @Nullable Plan keptPlan;
    private Extent previousDisplay = ZERO_EXTENT;
    private String upscalerFailure = "";
    private boolean benchmarkReady;
    private boolean benchmarkVisualScheduled;
    private boolean benchmarkVisualVerified;
    private final int benchmarkVisualInterval =
            Math.max(0, Integer.getInteger("cruma.viewer.benchmark.visual_interval_frames", 0));
    private long benchmarkLastVisualFrame = -1;
    private Path benchmarkBackbufferCapture;
    private final boolean benchmarkVisualAfterMeasurement =
            Boolean.getBoolean(ViewerBenchmarkProperties.VISUAL_AFTER_MEASUREMENT);
    private boolean bloomExecuted;
    private ViewMode lastViewMode = ViewMode.LIT;
    private boolean underwaterExecuted;
    private int postPasses;
    private @Nullable Snapshot frameSpawnShadows;
    private int captureParticlesPreparedMapIndex = -1;
    private boolean closed;
    private final ViewClipping viewClipping = new ViewClipping();
    private final SceneClearance sceneClearance = new SceneClearance();

    private @Nullable BgfxEffectPreview effectPreview;
    private @Nullable BgfxEffectPreview sceneEffects;
    private @Nullable BgfxEffectPreview mapPawnEffects;
    private final List<BgfxSpawnModelRenderer.BodyLight> bodyLights = new ArrayList<>();
    private final EditorTopology editorTopology;

    public BgfxViewerRenderBackend(
            RenderingContext renderingContext, WindowContext windowContext, UiContext uiContext, World worldConfig) {
        this(renderingContext, windowContext, uiContext, worldConfig, null);
    }

    public BgfxViewerRenderBackend(
            RenderingContext renderingContext,
            WindowContext windowContext,
            UiContext uiContext,
            World worldConfig,
            Output captureOutput) {
        this(renderingContext, windowContext, uiContext, worldConfig, captureOutput, false);
    }

    public BgfxViewerRenderBackend(
            RenderingContext renderingContext,
            WindowContext windowContext,
            UiContext uiContext,
            World worldConfig,
            Output captureOutput,
            boolean mapPreview) {
        this.windowContext = windowContext;
        this.renderingContext = renderingContext;
        this.uiContext = uiContext;
        renderingContext.geodata.setDefaults(worldConfig.geodata());
        var underwaterDefaultTint = underwaterTint(worldConfig);
        this.underwaterColorTransition = new UnderwaterColorTransition(underwaterDefaultTint);
        this.captureOutput = captureOutput;
        this.recording = uiContext.recording;
        renderingContext.particles.setClientUpdateSkip(captureOutput == null && recording == null);
        this.captureExtent = recording != null
                ? new Extent(recording.getWidth(), recording.getHeight())
                : captureOutput == null
                        ? DEFAULT_CAPTURE_EXTENT
                        : new Extent(captureOutput.tileResolution(), captureOutput.tileResolution());
        windowContext.capture.width = this.captureExtent.width();
        windowContext.capture.height = this.captureExtent.height();
        this.graphics = renderingContext.camera.isOrtho()
                ? worldConfig.rendering().graphics().forCapture()
                : worldConfig.rendering().graphics();
        this.benchmarkScenario = ViewerBenchmarkScenario.load();
        this.benchmarkVisualOutput = ViewerBenchmarkProperties.visualOutput();
        this.benchmarkDisplayExtent = ViewerBenchmarkProperties.displayPixels();
        this.benchmarkStreamingReadyTiles = ViewerBenchmarkProperties.streamingReadyTiles();
        this.upscalerRuntime = new UpscalerRuntimeState(this.graphics.upscaler());
        this.provider = UpscalerProviders.forAlgorithm(this.upscalerRuntime.getAlgorithm());
        this.nativeProvider = UpscalerProviders.forAlgorithm(UpscalerAlgorithm.NATIVE);
        this.nativeUpscalerConfig = Upscaler.nativeOnly();
        this.performance = new BgfxPerformanceTracker(ViewerBenchmarkProperties.isInstrumentationEnabled());
        this.dynamicResolution = new DynamicResolutionController(this.upscalerRuntime.getConfig());
        BgfxDevice createdDevice = null;
        BgfxViewerUiRenderer createdUi = null;
        BgfxFrameTargets createdTargets = null;
        BgfxDisplayTarget createdVisualTarget = null;
        BgfxSpatialUpscaler createdUpscaler = null;
        BgfxMetalRayQueries createdMetalRayQueries = null;
        BgfxCustomLightingProcessor createdCustomLighting = null;
        BgfxPostProcessor createdPost = null;
        BgfxReadbackQueue createdReadbacks = null;
        BgfxObjectPickerReadback createdObjectPickerReadback = null;
        BgfxCaptureWriter createdWriter = null;
        BgfxSceneRenderer createdScene = null;
        BgfxSceneUploadSink createdUploads = null;
        BgfxBenchmarkRecorder createdBenchmark = null;
        BgfxViewAllocator createdViews;
        try {
            createdDevice = new BgfxDevice(windowContext, this.graphics);
            if (captureOutput != null
                    && captureExtent.width() > createdDevice.getCapabilities().maxTextureSize()) {
                throw new IllegalArgumentException(
                        "capture tile_resolution=" + captureExtent.width() + " exceeds renderer max texture size="
                                + createdDevice.getCapabilities().maxTextureSize());
            }
            if (recording != null) {
                if (Math.max(captureExtent.width(), captureExtent.height())
                        > createdDevice.getCapabilities().maxTextureSize()) {
                    throw new IllegalArgumentException("A video of "
                            + captureExtent.width()
                            + "x"
                            + captureExtent.height()
                            + " exceeds what this graphics card draws, "
                            + createdDevice.getCapabilities().maxTextureSize()
                            + " pixels");
                }
                recording.setOriginBottomLeft(createdDevice.getCapabilities().originBottomLeft());
            }
            createdBenchmark = BgfxBenchmarkRecorder.open(
                    this.graphics, createdDevice.getCapabilities(), createdDevice.getBackbufferMsaaSamples());
            createdUi = new BgfxViewerUiRenderer(createdDevice);
            createdViews = new BgfxViewAllocator(createdDevice.getCapabilities().maxViews(), 240);
            createdTargets = new BgfxFrameTargets(
                    createdDevice.getResources(),
                    createdDevice.getCapabilities().depthStencilFormat());
            if (benchmarkVisualOutput != null) {
                createdVisualTarget = new BgfxDisplayTarget(createdDevice.getResources());
            }
            createdUpscaler = new BgfxSpatialUpscaler(createdDevice);
            createdMetalRayQueries = BgfxMetalRayQueries.create(createdDevice, uiContext.rendering.state);
            createdPost = new BgfxPostProcessor(
                    createdDevice,
                    performance,
                    underwaterDefaultTint,
                    createdUpscaler.getFullscreenVertexShader(),
                    renderingContext.pictureClock::getNanos);
            createdCustomLighting = new BgfxCustomLightingProcessor(
                    createdDevice,
                    performance,
                    createdUpscaler.getFullscreenVertexShader(),
                    uiContext.rendering.state,
                    createdMetalRayQueries);
            createdReadbacks = new BgfxReadbackQueue(createdDevice, 8, 128);
            createdObjectPickerReadback = new BgfxObjectPickerReadback(createdDevice);
            createdWriter = captureOutput == null
                    ? new BgfxCaptureWriter(createdDevice.getCapabilities().originBottomLeft())
                    : new BgfxCaptureWriter(
                            createdDevice.getCapabilities().originBottomLeft(), captureOutput, worldConfig, mapPreview);
            createdScene = new BgfxSceneRenderer(
                    createdDevice,
                    renderingContext,
                    uiContext.rendering.state,
                    this.graphics,
                    performance,
                    createdMetalRayQueries);
            createdScene.loadWorldEnvironment(worldConfig);
            if (captureOutput == null) {
                createdScene.loadSky(worldConfig);
            }
            createdUploads = new BgfxSceneUploadSink(
                    renderingContext, createdScene, createdDevice, graphics.tuning(), mapPreview);
        } catch (RuntimeException | Error e) {
            closeAfterFailure(createdUploads, e);
            closeAfterFailure(createdScene, e);
            closeAfterFailure(createdWriter, e);
            closeAfterFailure(createdObjectPickerReadback, e);
            closeAfterFailure(createdReadbacks, e);
            closeAfterFailure(createdCustomLighting, e);
            closeAfterFailure(createdPost, e);
            closeAfterFailure(createdUpscaler, e);
            closeAfterFailure(createdMetalRayQueries, e);
            closeAfterFailure(createdVisualTarget, e);
            closeAfterFailure(createdTargets, e);
            closeAfterFailure(createdUi, e);
            closeAfterFailure(createdBenchmark, e);
            closeAfterFailure(createdDevice, e);
            throw e;
        }
        this.device = createdDevice;
        this.uiRenderer = createdUi;
        this.views = createdViews;
        this.frameTargets = createdTargets;
        this.benchmarkVisualTarget = createdVisualTarget;
        this.upscaler = createdUpscaler;
        this.metalRayQueries = createdMetalRayQueries;
        this.customLightingProcessor = createdCustomLighting;
        this.postProcessor = createdPost;
        this.readbacks = createdReadbacks;
        if (recording != null) {
            recording.setTicketTaker(createdReadbacks::giveBack);
        }
        this.objectPickerReadback = createdObjectPickerReadback;
        this.captureWriter = createdWriter;
        this.sceneRenderer = createdScene;
        createdScene.setOthersTarget(frameTargets::getColorOverOwnDepth);
        createdScene.setDecorationsSynchronous(recording != null);
        var stage = renderingContext.mapPawns;
        var scene = createdScene;
        renderingContext.effects.setShares(new EditorEffectStage.Shares() {
            @Override
            public boolean attach(ParticleEmitter source, ParticleEmitter twin) {
                return source.getKind() != ParticleEmitter.Kind.MESH || scene.attachMeshTwin(source, twin);
            }

            @Override
            public void detach(ParticleEmitter twin) {
                if (twin.getKind() == ParticleEmitter.Kind.MESH) {
                    scene.detachMeshTwin(twin);
                }
            }
        });
        editorTopology = new EditorTopology(List.of(
                new EditorTopology.Source() {
                    @Override
                    public long getRevision() {
                        return scene.getDebugSceneTopologyRevision();
                    }

                    @Override
                    public @Nullable Set<String> getActorsSince(long since) {
                        return scene.getEditorTopologyActorsSince(since);
                    }
                },
                new EditorTopology.Source() {
                    @Override
                    public long getRevision() {
                        return stage.getEditorTopology();
                    }

                    @Override
                    public @Nullable Set<String> getActorsSince(long since) {
                        return stage.getEditorTopologyActorsSince(since);
                    }
                },
                new EditorTopology.Source() {
                    @Override
                    public long getRevision() {
                        return renderingContext.effects.getTopology();
                    }

                    @Override
                    public @Nullable Set<String> getActorsSince(long since) {
                        return renderingContext.effects.getTopologyActorsSince(since);
                    }
                }));
        this.benchmark = createdBenchmark;
        this.uploads = createdUploads;
        this.geodataRenderer = new BgfxGeodataRenderer(device);
        this.spawnRenderer = new BgfxSpawnRenderer(device);
        if (captureOutput != null) {
            spawnRenderer.rebuildShadowsAtEveryChange();
        }
    }

    static UnderwaterState underwaterState(WaterVolume volume) {
        return volume != null && volume.isL2WaterVolume()
                ? new UnderwaterState(true, volume.getCellophaneColor())
                : new UnderwaterState(false, null);
    }

    record UnderwaterState(boolean submerged, Vec4 tint) {}

    public void setCaptureUiEnabled(boolean enabled) {
        captureUiEnabled = enabled;
    }

    @Override
    public SceneUploadSink getUploads() {
        return uploads;
    }

    @Override
    public ViewerUiRenderer getUiRenderer() {
        return uiRenderer;
    }

    @Override
    public ReadbackTicket requestReadback(ReadbackRequest request) {
        requireOpen();
        return readbacks.request(request);
    }

    @Override
    public boolean isReadbackOriginBottomLeft() {
        return device.getCapabilities().originBottomLeft();
    }

    @Override
    public void onCameraRestored() {
        geodataRenderer.cameraRestored();
        viewClipping.cameraRestored(renderingContext.camera, uiContext.rendering);
    }

    @Override
    public boolean hasPendingWork() {
        return readbacks.hasPendingWork() || captureWriter.hasPendingWork() || windowContext.pendingCaptureTile != null;
    }

    @Override
    public void setInterfaceOnly(boolean only) {
        interfaceOnlyAsked = only;
    }

    @Override
    public boolean isInterfaceOnly() {
        return interfaceOnly;
    }

    @Override
    public boolean isBenchmarkComplete() {
        return benchmark.isComplete();
    }

    @Override
    public RenderStats getStats() {
        var base = device.getStats();
        var plan = framePlan;
        var extents = plan == null
                ? base.extents()
                : RenderExtents.resolve(
                        plan.displayExtent().width(),
                        plan.displayExtent().height(),
                        Math.min(plan.actualScaleX(), plan.actualScaleY()),
                        1.0,
                        captureExtent);
        var fallbacks = new ArrayList<>(base.fallbackReasons());
        if (plan != null && plan.isFallback()) {
            fallbacks.add("upscaler: " + plan.fallbackReason());
        }
        if (performance.isEnabled()) {
            updateOptimizationStatuses();
        }
        var diagnostics = performance.isEnabled()
                ? performance.snapshot(
                        base, extents, plan, graphics, sceneRenderer.getActiveReflectionExtent(), directToBackbuffer)
                : RenderDiagnostics.empty();
        return new RenderStats(
                base.backend(),
                base.renderer(),
                plan == null ? base.activeUpscaler() : plan.provider(),
                extents,
                base.frameNumber(),
                base.cpuFrameMs(),
                base.gpuFrameMs(),
                base.draws(),
                base.computes(),
                base.blits(),
                base.textureBytes(),
                base.renderTargetBytes(),
                base.bufferBytes(),
                readbacks.snapshot().depth(),
                base.views(),
                fallbacks,
                diagnostics);
    }

    public boolean takeRayDataReload() {
        return metalRayQueries != null && metalRayQueries.takeReload();
    }

    public void followRayOptions() {
        if (metalRayQueries != null) {
            metalRayQueries.followOptions();
        }
    }

    @Override
    public void frameBegin(long frameTime) {
        requireOpen();
        afterInterfaceOnly = interfaceOnly || interfaceOnlyBefore;
        interfaceOnlyBefore = interfaceOnly;
        interfaceOnly = interfaceOnlyAsked && canShowKeptWorld();
        interfaceOnlyAsked = false;
        var beginStarted = DEBUG_LOOP_TIMINGS ? System.nanoTime() : 0L;
        var completedStats = getStats();
        followRayOptions();
        var statsFinished = DEBUG_LOOP_TIMINGS ? System.nanoTime() : 0L;
        if (!afterInterfaceOnly) {
            publishHudStats(completedStats);
        }
        var hudFinished = DEBUG_LOOP_TIMINGS ? System.nanoTime() : 0L;
        if (benchmark.isEnabled() && isBenchmarkReady()) {
            if (benchmark.getSeenFrames() == 0 && Boolean.getBoolean("cruma.viewer.bgfx.debug_gpu_memory")) {
                device.logMemoryInventory();
                log.info(
                        "GPU pools: geometry={} instances={} textureArrays={}",
                        sceneRenderer.getGeometryUploadSnapshot(),
                        sceneRenderer.getInstanceUploadSnapshot(),
                        sceneRenderer.getTextureArrayMemorySnapshot());
            }
            benchmarkScenario.applyFrame(
                    renderingContext.camera, benchmark.getSeenFrames(), benchmark.getWarmupFrames());
            renderingContext.playback.setSeconds(benchmarkScenario.getSimulationTimeSeconds(benchmark.getSeenFrames()));
            renderingContext.context.time = renderingContext.playback.getSeconds();
            var submissions = new GpuSubmissionCounts(
                    sceneRenderer.getGpuVisibilityGroupCount(),
                    sceneRenderer.getGpuOpaqueActiveGroups(),
                    sceneRenderer.getGpuOpaqueSubmitBatches(),
                    sceneRenderer.getGpuOpaqueCommandRanges());
            if (benchmark.isInventoryCaptureNeeded()) {
                benchmark.record(
                        completedStats,
                        uploads.getSceneCensus(),
                        uploads.getUploadStats(),
                        uiContext.rendering.stats.loopLatencyNs / 1_000_000.0,
                        submissions);
            } else {
                benchmark.record(
                        completedStats, null, null, uiContext.rendering.stats.loopLatencyNs / 1_000_000.0, submissions);
            }
        }
        if (DEBUG_LOOP_TIMINGS) {
            var benchmarkFinished = System.nanoTime();
            if (benchmarkFinished - beginStarted > 5_000_000L) {
                log.info(
                        "Frame preparation: frame={} statsMs={} hudMs={} benchmarkMs={}",
                        completedStats.frameNumber(),
                        (statsFinished - beginStarted) / 1_000_000.0,
                        (hudFinished - statsFinished) / 1_000_000.0,
                        (benchmarkFinished - hudFinished) / 1_000_000.0);
            }
        }
        performance.beginFrame();
        var readbackStarted = performance.start();
        readbacks.poll(device.getStats().frameNumber());
        objectPickerReadback.poll(device.getStats().frameNumber());
        pollMarkers();
        verifyBenchmarkVisualTicket();
        captureWriter.poll();
        clearTerminalCaptureTickets();
        performance.addPhase(CpuPhase.READBACK, performance.getElapsed(readbackStarted));
        device.beginFrame();
        views.beginFrame();
        if (interfaceOnly) {
            return;
        }
        sceneRenderer.beginFrame();
        if (captureOutput == null) {
            geodataRenderer.update(renderingContext.geodata, renderingContext.camera);
        }
        applyRuntimeUpscalerControls();
        viewClipping.beginFrame(renderingContext.camera, uiContext.rendering);
        if (captureOutput != null) {
            applyCaptureShot();
        } else {
            viewClipping.cut(renderingContext.camera, uiContext.rendering, viewerCircle());
        }
        if ((windowContext.screenshot || agentShot != null)
                && screenshotFrames == 0
                && screenshotTicket == null
                && screenScreenshot == null
                && captureOutput == null) {
            if (agentShot == null
                    ? uiContext.screenshots.isIncludeInterface() != windowContext.screenshotOtherKind
                    : agentShot.withInterface()) {
                screenScreenshotRequested = true;
                uiContext.screenshots.setWindowCaptured(true);
            } else {
                screenshotFrames = SCREENSHOT_WARMUP_FRAMES + 1;
            }
        }
        var forceCaptureExtent = captureExtentRequired();
        var framebufferWidth = forceCaptureExtent
                ? captureExtent.width()
                : benchmarkDisplayExtent != null ? benchmarkDisplayExtent.width() : windowContext.framebuffer.width;
        var framebufferHeight = forceCaptureExtent
                ? captureExtent.height()
                : benchmarkDisplayExtent != null ? benchmarkDisplayExtent.height() : windowContext.framebuffer.height;
        var display = framebufferWidth <= 0 || framebufferHeight <= 0
                ? ZERO_EXTENT
                : framebufferWidth == captureExtent.width()
                                && framebufferHeight == captureExtent.height()
                                && forceCaptureExtent
                        ? captureExtent
                        : previousDisplay.width() == framebufferWidth && previousDisplay.height() == framebufferHeight
                                ? previousDisplay
                                : new Extent(framebufferWidth, framebufferHeight);
        if (!display.equals(previousDisplay)) {
            dynamicResolution.reset("display resize");
            previousDisplay = display;
        }
        if (forceCaptureExtent) {
            dynamicResolution.reset("capture bypass");
        } else if (upscalerRuntime.getAlgorithm() == UpscalerAlgorithm.NATIVE) {
            dynamicResolution.reset("native provider has no scaled render domain");
        } else if (!afterInterfaceOnly) {
            var completed = device.getStats();
            dynamicResolution.update(completed.gpuFrameMs(), completed.cpuFrameMs());
        }
        if (performance.isEnabled()) {
            performance.dynamicResolution(dynamicResolution.getDiagnostics());
        }
        var activeUpscaler = dynamicUpscalerConfig(upscalerRuntime.getConfig());
        var nextFramePlan = resolveFramePlan(activeUpscaler, display, forceCaptureExtent);
        if (framePlan != null
                && (!framePlan.provider().equals(nextFramePlan.provider())
                        || !framePlan.renderExtent().equals(nextFramePlan.renderExtent()))) {
            sceneRenderer.invalidateVisibilityHistory("upscaler provider/render extent changed");
        }
        framePlan = nextFramePlan;
        directToBackbuffer = !forceCaptureExtent
                && benchmarkVisualOutput == null
                && screenshotFrames == 0
                && !readbacks.hasPendingWork()
                && !display.isZero()
                && "native".equals(framePlan.provider())
                && framePlan.renderExtent().equals(framePlan.displayExtent())
                && !sceneRenderer.requiresOffscreen()
                && !uiContext.rendering.isObjectPickerEnabled()
                && !uiContext.rendering.customLighting
                && !uiContext.rendering.bloom
                && !BgfxPostProcessor.isDisplayGammaActive(uiContext.rendering.displayGamma.get())
                && !underwaterState().submerged();
        worldTargetActive = !display.isZero();
        if (directToBackbuffer) {
            frameTargets.ensure(ZERO_EXTENT);
        } else {
            frameTargets.reserve(framePlan.displayExtent());
            worldTargetActive = frameTargets.ensure(framePlan.renderExtent());
        }
        renderingContext.context.framebuffer.width = framePlan.renderExtent().width();
        renderingContext.context.framebuffer.height = framePlan.renderExtent().height();
    }

    @Override
    public void frameEnd(long frameTime) {
        requireOpen();
        if (interfaceOnly) {
            presentWithFallback(keptWorld, null);
            finishFrame();
            return;
        }
        keptWorld = null;
        keptPlan = null;
        if (captureOutput == null) {
            viewClipping.beginFrame(renderingContext.camera, uiContext.rendering);
            viewClipping.cut(renderingContext.camera, uiContext.rendering, viewerCircle());
        }
        followClearance();
        var interactiveDelta = recording != null
                ? (float) advanceRecording()
                : !benchmarkReady && captureOutput == null
                        ? (float) renderingContext.playback.advanceMillis(frameTime)
                        : 0f;
        renderingContext.context.time = renderingContext.playback.getSeconds();
        var viewMode = renderingContext.getViewMode();
        uiContext.rendering.state.setSceneLit(viewMode.isLit());
        if (viewMode.isFilled() != lastViewMode.isFilled()) {
            sceneRenderer.invalidateVisibilityHistory("wireframe view turned on or off");
        }
        lastViewMode = viewMode;
        bloomExecuted = false;
        underwaterExecuted = false;
        postPasses = 0;
        uiContext.rendering.stats.customLightingSuppressedByAuthoredInterior = uiContext.rendering.customLighting
                && uiContext.rendering.customProtectAuthoredInteriors
                && sceneRenderer.isCameraInAuthoredInterior();
        if (!uiContext.rendering.isEffectiveGlobalIllumination()) {
            uiContext.rendering.stats.customGiActive = false;
            uiContext.rendering.stats.customGiStatus = "Off";
        }
        if (worldTargetActive) {
            var interactiveEffects = (recording != null || !captureExtentRequired())
                    && (!renderingContext.camera.isOrtho() || renderingContext.camera.isInteractiveOrtho());
            var selectedVolume = renderingContext.scene.findContainingWaterVolume(
                    renderingContext.camera.getPosition(), zoneState());
            var underwater =
                    underwaterColorTransition.admit(interactiveEffects && viewMode.isLit() ? selectedVolume : null);
            if (!directToBackbuffer) {
                customLightingProcessor.prepare(
                        framePlan.renderExtent(),
                        renderingContext.camera,
                        uiContext.rendering.state,
                        sceneRenderer.getCurrentSunDirection());
            }
            var tileCapture = captureOutput != null;
            if (tileCapture
                    && windowContext.pendingCaptureTile != null
                    && captureParticlesPreparedMapIndex != windowContext.mapIndex) {
                renderingContext.particles.configureCapture(
                        uiContext.rendering.getGameEventStates(), uiContext.rendering.getTimeOfDay());
                var fxSnapshot =
                        renderingContext.particles.prepareDeterministicSnapshot(renderingContext.camera.getPosition());
                log.info(
                        "Capture effects: tile={} registered={} nearby={} populated={} particles={}",
                        windowContext.pendingCaptureTile,
                        fxSnapshot.registeredEmitters(),
                        fxSnapshot.nearbyEmitters(),
                        fxSnapshot.populatedEmitters(),
                        fxSnapshot.activeParticles());
                captureParticlesPreparedMapIndex = windowContext.mapIndex;
            }
            var simulationDeltaSeconds = benchmarkReady
                    ? benchmarkScenario.getSimulationTimeStepSeconds()
                    : tileCapture ? 0f : interactiveDelta;
            var sceneEffectsEnabled = tileCapture
                    || (recording != null || !captureExtentRequired())
                            && (!renderingContext.camera.isOrtho() || renderingContext.camera.isInteractiveOrtho());
            var realisticRelighting = uiContext.rendering.isEffectiveCustomRealisticLighting();
            frameSpawnShadows = null;
            spawnRenderer.setSeconds(
                    tileCapture ? 0f : renderingContext.playback.getSeconds(),
                    renderingContext.pictureClock.getSeconds());
            BgfxSceneRenderer.ActorPass actorPass = directToBackbuffer
                            || !spawnRenderer.hasModels(renderingContext.spawns, tileCapture)
                    ? null
                    : (color, depth, extent, passViews) -> {
                        spawnRenderer.setSun(sceneRenderer.getCurrentSunDirection());
                        var bodies = collectBodyLights();
                        spawnRenderer.setBodyLights(bodies);
                        spawnRenderer.setLighting(sceneRenderer.openSpawnModelLighting(bodies));
                        spawnRenderer.setSunShadows(
                                renderingContext.spawns, sceneRenderer.getShadowSnapshot(), uiContext.rendering.state);
                        spawnSunShadows(passViews);
                        spawnRenderer.renderModelsInScene(
                                renderingContext.spawns,
                                renderingContext.camera,
                                color,
                                frameTargets.getWaterMask(),
                                depth,
                                extent,
                                passViews,
                                tileCapture);
                    };
            var sceneGpuMs = uiContext.rendering.stats.sceneGpuMs;
            var frameEnvironment = sceneRenderer.render(
                    directToBackbuffer ? null : frameTargets.getFramebuffer(),
                    directToBackbuffer ? null : frameTargets.getColor(),
                    directToBackbuffer ? null : frameTargets.getPackedDepth(),
                    directToBackbuffer ? null : frameTargets.getNormal(),
                    directToBackbuffer ? null : frameTargets.getDepth(),
                    framePlan.renderExtent(),
                    views,
                    tileCapture ? 0f : renderingContext.playback.getSeconds(),
                    simulationDeltaSeconds,
                    sceneEffectsEnabled,
                    realisticRelighting
                            ? environment -> customLightingProcessor.process(
                                    frameTargets.getColor(),
                                    frameTargets.getDepth(),
                                    frameTargets.getNormal(),
                                    frameTargets.getWaterMask(),
                                    frameTargets.getMaterialAux(),
                                    framePlan.renderExtent(),
                                    views,
                                    renderingContext.camera,
                                    uiContext.rendering.state,
                                    sceneRenderer.getShadowSnapshot(),
                                    spawnSunShadows(views),
                                    sceneRenderer.getLocalShadowSnapshot(),
                                    sceneRenderer.getLocalLightSnapshot(),
                                    environment)
                            : null,
                    actorPass);
            if (afterInterfaceOnly) {
                uiContext.rendering.stats.sceneGpuMs = sceneGpuMs;
            }
            if (!directToBackbuffer) {
                objectPickerReadback.submit(
                        frameTargets.getPackedDepth(), frameTargets.getDepth(), framePlan.renderExtent(), views);
                for (var marker : markerReadbacks) {
                    if (marker != null) {
                        marker.submit(frameTargets.getPackedDepth(), null, framePlan.renderExtent(), views);
                    }
                }
                if (pointsReadback != null) {
                    pointsReadback.submit(frameTargets.getPackedDepth(), framePlan.renderExtent(), views);
                }
            }
            spawnRenderer.setSun(sceneRenderer.getCurrentSunDirection());
            var bodies = collectBodyLights();
            spawnRenderer.setBodyLights(bodies);
            spawnRenderer.setLighting(sceneRenderer.openSpawnModelLighting(bodies));
            spawnRenderer.setSunShadows(
                    renderingContext.spawns, sceneRenderer.getShadowSnapshot(), uiContext.rendering.state);
            renderingContext.spawns.marksHidden = screenshotFrames > 0;
            if (directToBackbuffer && !tileCapture) {
                geodataRenderer.render(
                        renderingContext.geodata, renderingContext.camera, null, null, framePlan.renderExtent(), views);
                spawnRenderer.render(
                        renderingContext.spawns, renderingContext.camera, null, null, framePlan.renderExtent(), views);
            }
            if (!directToBackbuffer) {
                var litWorld = realisticRelighting
                        ? frameTargets.getColor()
                        : customLightingProcessor.process(
                                frameTargets.getColor(),
                                frameTargets.getDepth(),
                                frameTargets.getNormal(),
                                frameTargets.getWaterMask(),
                                frameTargets.getMaterialAux(),
                                framePlan.renderExtent(),
                                views,
                                renderingContext.camera,
                                uiContext.rendering.state,
                                sceneRenderer.getShadowSnapshot(),
                                spawnSunShadows(views),
                                sceneRenderer.getLocalShadowSnapshot(),
                                sceneRenderer.getLocalLightSnapshot(),
                                frameEnvironment);
                if (!tileCapture) {
                    sceneRenderer.renderHoverHighlight(
                            litWorld,
                            frameTargets.getDepth(),
                            framePlan.renderExtent(),
                            views,
                            renderingContext.playback.getSeconds());
                    geodataRenderer.render(
                            renderingContext.geodata,
                            renderingContext.camera,
                            litWorld,
                            frameTargets.getDepth(),
                            spawnRenderer.getModelDepthInScene(),
                            framePlan.renderExtent(),
                            views);
                    spawnRenderer.render(
                            renderingContext.spawns,
                            renderingContext.camera,
                            litWorld,
                            frameTargets.getDepth(),
                            framePlan.renderExtent(),
                            views);
                } else {
                    spawnRenderer.renderPawns(
                            renderingContext.spawns,
                            renderingContext.camera,
                            litWorld,
                            frameTargets.getDepth(),
                            framePlan.renderExtent(),
                            views);
                }
                var cellophane =
                        underwater ? underwaterColorTransition.render(sceneRenderer.getTimeSinceLastFogChange()) : null;
                var post = postProcessor.process(
                        litWorld,
                        framePlan.renderExtent(),
                        views,
                        graphics.msaaSamples() > 0,
                        interactiveEffects && uiContext.rendering.bloom && viewMode.isFilled(),
                        interactiveEffects && underwater,
                        cellophane,
                        interactiveEffects && viewMode.isFilled()
                                ? uiContext.rendering.displayGamma.get()
                                : BgfxPostProcessor.NEUTRAL_DISPLAY_GAMMA,
                        interactiveEffects && viewMode.isFilled() ? renderingContext.getScenePostEffects() : null);
                bloomExecuted = post.bloom();
                underwaterExecuted = post.underwater();
                postPasses = post.passes();
                var worldColor = post.output();
                var readbackStarted = performance.start();
                if (isBenchmarkVisualPending()) {
                    captureBenchmarkVisual(frameTargets.getColor(), worldColor);
                } else if (recording != null) {
                    renderRecordingFrame(worldColor);
                } else if (screenshotFrames > 0 && captureOutput == null) {
                    renderScreenshotFrame(worldColor);
                } else {
                    if (captureOutput == null) {
                        presentWithFallback(worldColor, null);
                        keptWorld = worldColor;
                        keptPlan = framePlan;
                    }
                    scheduleAutomaticCaptures();
                    readbacks.submitWorld(worldColor, views);
                }
                performance.addPhase(CpuPhase.READBACK, performance.getElapsed(readbackStarted));
                acknowledgeSubmittedTile();
            }
        }
        finishFrame();
    }

    @Override
    public void stop(WindowContext windowContext) {
        close();
    }

    public void takeAgentShot(Path file, BiConsumer<Path, Throwable> done) {
        takeAgentShot(file, false, done);
    }

    public void takeAgentShot(Path file, boolean withInterface, BiConsumer<Path, Throwable> done) {
        agentShot = new AgentShot(file, withInterface, done);
    }

    private record ScreenScreenshot(
            Path folder,
            Path tga,
            Path output,
            long deadline,
            @Nullable CompletableFuture<Boolean> conversion,
            @Nullable BiConsumer<Path, Throwable> done) {}

    @Override
    public long getDebugSceneRevision() {
        return sceneRenderer.getDebugSceneRevision()
                + renderingContext.mapPawns.getEditorRevision()
                + renderingContext.effects.getTopology();
    }

    @Override
    public List<DebugSceneObject> getDebugSceneObjects() {
        if (!editsPawns()) {
            return sceneRenderer.getDebugSceneObjects();
        }
        var rows = new ArrayList<>(sceneRenderer.getDebugSceneObjects());
        rows.addAll(renderingContext.mapPawns.getEditorRows());
        rows.addAll(renderingContext.effects.getRows());
        return rows;
    }

    @Override
    public int getDebugSceneObjectGroups() {
        return sceneRenderer.getDebugSceneObjectGroups() + (editsPawns() ? 2 : 0);
    }

    @Override
    public List<DebugSceneObject> getDebugSceneObjects(int group) {
        var scene = sceneRenderer.getDebugSceneObjectGroups();
        if (group < scene) {
            return sceneRenderer.getDebugSceneObjects(group);
        }
        return group == scene ? renderingContext.mapPawns.getEditorRows() : renderingContext.effects.getRows();
    }

    @Override
    public boolean doesDebugSceneGroupMeet(int group, Bounds3f box) {
        return group >= sceneRenderer.getDebugSceneObjectGroups() || sceneRenderer.doesDebugSceneGroupMeet(group, box);
    }

    @Override
    public DebugSceneObject refreshDebugSceneObject(DebugSceneObject selected) {
        return sceneRenderer.refreshDebugSceneObject(selected);
    }

    @Override
    public boolean canPreviewActorTransform(String sourceActor) {
        return sceneRenderer.canPreviewActorTransform(sourceActor)
                || editsPawns()
                        && (renderingContext.mapPawns.hasPawn(sourceActor)
                                || renderingContext.effects.hasEffect(sourceActor));
    }

    @Override
    public boolean isMovingPlacement(String sourceActor) {
        return sceneRenderer.isMovingPlacement(sourceActor)
                || editsPawns() && renderingContext.effects.hasEffect(sourceActor);
    }

    @Override
    public void previewMovingPlacements(Map<String, ActorTransform> placements) {
        var effects = renderingContext.effects;
        Map<String, ActorTransform> scene = placements;
        Map<String, ActorTransform> placed = Map.of();
        for (var actor : placements.keySet()) {
            if (effects.hasEffect(actor)) {
                scene = new LinkedHashMap<>(placements);
                scene.keySet().removeIf(effects::hasEffect);
                placed = new LinkedHashMap<>(placements);
                placed.keySet().removeIf(path -> !effects.hasEffect(path));
                break;
            }
        }
        effects.place(placed);
        sceneRenderer.previewMovingPlacements(scene);
        objectPickerReadback.invalidate();
    }

    @Override
    public long getDebugSceneTopologyRevision() {
        return editorTopology.getRevision(editsPawns());
    }

    @Override
    public @Nullable Set<String> getEditorTopologyActorsSince(long since) {
        return editorTopology.getActorsSince(since, editsPawns());
    }

    @Override
    public @Nullable Bounds3f getSavedActorBounds(String actor) {
        return sceneRenderer.getSavedActorBounds(actor);
    }

    @Override
    public List<DebugSceneObject> getEditorActorObjects(Set<String> actors) {
        var objects = sceneRenderer.getEditorActorObjects(actors);
        if (!editsPawns() || actors.isEmpty()) {
            return objects;
        }
        var pawns = renderingContext.mapPawns.getEditorRows(actors);
        var effects = renderingContext.effects.getRows(actors);
        if (pawns.isEmpty() && effects.isEmpty()) {
            return objects;
        }
        var rows = new ArrayList<>(objects);
        rows.addAll(pawns);
        rows.addAll(effects);
        return rows;
    }

    @Override
    public void previewActorTransformDeltas(Map<String, float[]> deltas) {
        var pawns = new HashMap<String, float[]>();
        var scene = splitPawns(deltas, pawns);
        renderingContext.mapPawns.replaceEditorDeltas(pawns);
        sceneRenderer.previewActorTransformDeltas(scene);
        objectPickerReadback.invalidate();
    }

    @Override
    public void previewActorTransformDeltaChanges(Map<String, float[]> updates, Set<String> restores) {
        var pawns = new HashMap<String, float[]>();
        var scene = splitPawns(updates, pawns);
        var stage = renderingContext.mapPawns;
        var pawnRestores = new HashSet<String>();
        var sceneRestores = restores;
        for (var actor : restores) {
            if (stage.hasPawn(actor)) {
                pawnRestores.add(actor);
            }
        }
        if (!pawnRestores.isEmpty()) {
            sceneRestores = new HashSet<>(restores);
            sceneRestores.removeAll(pawnRestores);
        }
        stage.setEditorDeltas(pawns, pawnRestores);
        sceneRenderer.previewActorTransformDeltaChanges(scene, sceneRestores);
        objectPickerReadback.invalidate();
    }

    @Override
    public void replaceEditorActors(Map<String, List<Entity>> replacements) {
        var scene = replacements;
        for (var actor : replacements.keySet()) {
            if (renderingContext.mapPawns.hasPawn(actor)) {
                scene = new LinkedHashMap<>(replacements);
                scene.keySet().removeIf(renderingContext.mapPawns::hasPawn);
                break;
            }
        }
        sceneRenderer.replaceEditorActors(scene);
        objectPickerReadback.invalidate();
    }

    @Override
    public boolean updateEditorBspSurfaces(
            String map, List<Entity> bsp, Set<Integer> surfaces, Set<Integer> collapsed) {
        var updated = sceneRenderer.updateEditorBspSurfaces(map, bsp, surfaces, collapsed);
        objectPickerReadback.invalidate();
        return updated;
    }

    @Override
    public void replaceEditorBspSurfaces(String map, List<Entity> entities) {
        sceneRenderer.replaceEditorBspSurfaces(map, entities);
        objectPickerReadback.invalidate();
    }

    @Override
    public void replaceEditorTerrain(String owner, Map<String, List<Entity>> sectors, boolean initialize) {
        sceneRenderer.replaceEditorTerrain(owner, sectors, initialize);
        objectPickerReadback.invalidate();
    }

    @Override
    public void replaceEditorDecorations(Map<String, List<Entity>> replacements) {
        sceneRenderer.replaceEditorDecorations(replacements);
        objectPickerReadback.invalidate();
    }

    @Override
    public boolean streamsDecorations(String owner, int layerIndex) {
        return sceneRenderer.streamsDecorations(owner, layerIndex);
    }

    @Override
    public void showDecorationSources(
            String owner, Map<Integer, DecorationDensity> densities, @Nullable DecorationGround ground) {
        sceneRenderer.showDecorationSources(owner, densities, ground);
        objectPickerReadback.invalidate();
    }

    @Override
    public void restoreDecorationSources(String owner) {
        sceneRenderer.restoreDecorationSources(owner);
        objectPickerReadback.invalidate();
    }

    @Override
    public boolean updateEditorTerrain(String owner, Map<String, List<Entity>> sectors) {
        return sceneRenderer.updateEditorTerrainVertices(owner, sectors);
    }

    @Override
    public boolean updateEditorTerrainAlpha(TerrainAlphaAtlas atlas, int x, int y, int width, int height) {
        return sceneRenderer.updateEditorTerrainAlpha(atlas, x, y, width, height);
    }

    @Override
    public long updateEditorLightmap(String map, int key, Texture texels) {
        return sceneRenderer.updateEditorLightmap(map, key, texels);
    }

    @Override
    public void clearEditorTerrain(String owner) {
        sceneRenderer.clearEditorTerrain(owner);
        objectPickerReadback.invalidate();
    }

    @Override
    public void clearEditorPreview() {
        sceneRenderer.clearEditorPreview();
        renderingContext.mapPawns.clearEditor();
        objectPickerReadback.invalidate();
    }

    @Override
    public EffectPreview getEffectPreview() {
        if (effectPreview == null) {
            effectPreview = new BgfxEffectPreview(renderingContext, sceneRenderer, EffectPreview.TOOL);
        }
        return effectPreview;
    }

    @Override
    public EffectPreview getSceneEffects() {
        if (sceneEffects == null) {
            sceneEffects = new BgfxEffectPreview(renderingContext, sceneRenderer, EffectPreview.SCENE);
        }
        return sceneEffects;
    }

    @Override
    public EffectPreview getMapPawnEffects() {
        if (mapPawnEffects == null) {
            mapPawnEffects = new BgfxEffectPreview(renderingContext, sceneRenderer, EffectPreview.MAP_PAWNS);
        }
        return mapPawnEffects;
    }

    @Override
    public float pickDebugMeshEmitter(ParticleEmitter emitter, Vec3 origin, Vec3 direction, float maxDistance) {
        return sceneRenderer.pickDebugMeshEmitter(emitter, origin, direction, maxDistance);
    }

    @Override
    public Bounds3f getDebugMeshEmitterBounds(ParticleEmitter emitter) {
        return sceneRenderer.getDebugMeshEmitterBounds(emitter);
    }

    @Override
    public void highlightHovered(@Nullable DebugSceneObject object, boolean wholeActor) {
        sceneRenderer.highlightHovered(object, wholeActor);
    }

    @Override
    public @Nullable Bounds3f getDebugInstanceBounds(DebugSceneObject object) {
        return sceneRenderer.getInstanceBounds(object);
    }

    @Override
    public void isolateEditorActors(@Nullable Set<String> actors) {
        sceneRenderer.isolateEditorActors(actors);
        renderingContext.mapPawns.isolateEditor(actors);
        objectPickerReadback.invalidate();
    }

    @Override
    public @Nullable Bounds3f getSceneBounds() {
        return sceneRenderer.getSceneBounds();
    }

    @Override
    public DebugSceneHit pickDebugSceneObject(float ndcX, float ndcY, float maxDistance, Set<Category> categories) {
        if (drawsNothing()) {
            return null;
        }
        var sample = objectPickerReadback.findLatestMatching(requestScenePick(ndcX, ndcY, maxDistance, categories));
        if (sample == null || !sample.hasGeometry()) {
            return null;
        }
        return sceneRenderer.pickDebugSceneObjectAtDistance(
                sample.request().origin(),
                sample.request().direction(),
                sample.rayDistance(),
                sample.request().maxDistance(),
                sample.request().categories(),
                renderingContext.camera.isInteractiveOrtho()
                        ? BgfxSceneRenderer.PICK_TOLERANCE
                        : Math.max(
                                BgfxSceneRenderer.PICK_TOLERANCE,
                                sample.rayDistance() * BgfxSceneRenderer.PICK_TOLERANCE_PER_UNIT));
    }

    @Override
    public boolean isScenePickReady(float ndcX, float ndcY, float maxDistance, Set<Category> categories) {
        return drawsNothing()
                || objectPickerReadback.findLatestMatching(requestScenePick(ndcX, ndcY, maxDistance, categories))
                        != null;
    }

    public @Nullable DebugSceneHit findObjectOnRay(
            Vec3 origin, Vec3 direction, float distance, Set<Category> categories) {
        return sceneRenderer.pickDebugSceneObjectAtDistance(
                origin,
                direction,
                distance,
                Float.MAX_VALUE,
                categories,
                Math.max(BgfxSceneRenderer.PICK_TOLERANCE, distance * BgfxSceneRenderer.PICK_TOLERANCE_PER_UNIT));
    }

    @Override
    public float getMarkerRayDistance(int marker, float ndcX, float ndcY, float maxDistance) {
        if (marker < 0 || marker >= markerReadbacks.length || drawsNothing()) {
            return Float.NaN;
        }
        if (markerReadbacks[marker] == null) {
            markerReadbacks[marker] = new BgfxObjectPickerReadback(device);
        }
        var readback = markerReadbacks[marker];
        var camera = renderingContext.camera;
        var direction = camera.getCursorRayDirection(ndcX, ndcY);
        var forward = camera.getCursorRayDirection(0f, 0f);
        var viewDepthScale = Math.abs(direction.getX() * forward.getX()
                + direction.getY() * forward.getY()
                + direction.getZ() * forward.getZ());
        var request = readback.request(
                ndcX,
                ndcY,
                camera.getCursorRayOrigin(ndcX, ndcY),
                direction,
                viewDepthScale,
                maxDistance,
                EnumSet.allOf(Category.class));
        var sample = readback.findLatestMatching(request);
        if (sample == null) {
            return Float.NaN;
        }
        return sample.hasGeometry() ? sample.rayDistance() : Float.POSITIVE_INFINITY;
    }

    @Override
    public float[] getSceneRayDistances(float[] ndc, float maxDistance) {
        if (ndc == null || ndc.length < 2 || ndc.length % 2 != 0 || ndc.length > RAY_POINTS * 2) {
            return null;
        }
        if (drawsNothing()) {
            var none = new float[ndc.length / 2];
            Arrays.fill(none, Float.NaN);
            return none;
        }
        if (pointsReadback == null) {
            pointsReadback = new BgfxScenePointsReadback(device);
        }
        var camera = renderingContext.camera;
        var forward = camera.getCursorRayDirection(0f, 0f);
        var request = pointsReadback.request(ndc, camera.getCursorRayOrigin(0f, 0f), forward, maxDistance);
        var depths = pointsReadback.findLatestMatching(request);
        if (depths == null) {
            return null;
        }
        var distances = new float[depths.length];
        for (var index = 0; index < depths.length; index++) {
            var direction = camera.getCursorRayDirection(ndc[index * 2], ndc[index * 2 + 1]);
            var scale = Math.abs(direction.getX() * forward.getX()
                    + direction.getY() * forward.getY()
                    + direction.getZ() * forward.getZ());
            var distance = scale > 0 ? depths[index] / scale : Float.NaN;
            distances[index] =
                    Float.isFinite(distance) && distance >= 0 && distance <= maxDistance ? distance : Float.NaN;
        }
        return distances;
    }

    @Override
    public float getSceneRayDistance(float ndcX, float ndcY, float maxDistance) {
        if (drawsNothing()) {
            return Float.NaN;
        }
        var camera = renderingContext.camera;
        var direction = camera.getCursorRayDirection(ndcX, ndcY);
        var forward = camera.getCursorRayDirection(0f, 0f);
        var viewDepthScale = Math.abs(direction.getX() * forward.getX()
                + direction.getY() * forward.getY()
                + direction.getZ() * forward.getZ());
        var request = objectPickerReadback.request(
                ndcX,
                ndcY,
                camera.getCursorRayOrigin(ndcX, ndcY),
                direction,
                viewDepthScale,
                maxDistance,
                EnumSet.allOf(Category.class));
        var sample = objectPickerReadback.findLatestMatching(request);
        return sample == null || !sample.hasGeometry() ? Float.NaN : sample.rayDistance();
    }

    private void followClearance() {
        var camera = renderingContext.camera;
        if (FIXED_NEAR || camera.isOrtho() || uiContext.rendering.loading.active) {
            camera.setClearance(0f);
            return;
        }
        var eye = camera.getPosition();
        var clear = sceneClearance.at(
                eye.getX(),
                eye.getY(),
                eye.getZ(),
                sceneRenderer.getClearanceRevision(),
                sceneRenderer.hasMovingObjects(),
                System.nanoTime(),
                sceneRenderer::measureClearance);
        if (clear > 0f) {
            clear = SceneClearance.ofModels(
                    renderingContext.spawns.getDrawnModels(), eye.getX(), eye.getY(), eye.getZ(), clear);
        }
        camera.setClearance(clear);
    }

    private Request requestScenePick(float ndcX, float ndcY, float maxDistance, Set<Category> categories) {
        var camera = renderingContext.camera;
        var origin = camera.getCursorRayOrigin(ndcX, ndcY);
        var direction = camera.getCursorRayDirection(ndcX, ndcY);
        if (camera.isInteractiveOrtho()) {
            return objectPickerReadback.request(
                    ndcX,
                    ndcY,
                    origin,
                    direction,
                    1f,
                    Float.MAX_VALUE,
                    categories,
                    camera.getProjectionNear(),
                    camera.getOrthoFar());
        }
        var forward = camera.getCursorRayDirection(0f, 0f);
        var viewDepthScale = Math.abs(direction.getX() * forward.getX()
                + direction.getY() * forward.getY()
                + direction.getZ() * forward.getZ());
        return objectPickerReadback.request(ndcX, ndcY, origin, direction, viewDepthScale, maxDistance, categories);
    }

    private Snapshot spawnSunShadows(BgfxViewAllocator views) {
        if (frameSpawnShadows == null) {
            frameSpawnShadows = spawnRenderer.renderSunShadows(
                    renderingContext.spawns,
                    sceneRenderer.getShadowSnapshot(),
                    uiContext.rendering.state,
                    renderingContext.camera,
                    views);
        }
        return frameSpawnShadows;
    }

    private List<BgfxSpawnModelRenderer.BodyLight> collectBodyLights() {
        bodyLights.clear();
        var client = sceneRenderer.getMaterialClient();
        if (effectPreview != null) {
            effectPreview.collectBodyLights(client, bodyLights);
        }
        if (sceneEffects != null) {
            sceneEffects.collectBodyLights(client, bodyLights);
        }
        if (mapPawnEffects != null) {
            mapPawnEffects.collectBodyLights(client, bodyLights);
        }
        return bodyLights;
    }

    private void updateOptimizationStatuses() {
        var requested = graphics.optimizations();
        var caps = device.getCapabilities();
        var statuses = new ArrayList<OptimizationStatus>(12);
        var coverageDebug = sceneRenderer.getCoverageDebugDescription();
        statuses.add(new OptimizationStatus(
                "p0_coverage_debug",
                !"off".equals(coverageDebug),
                !"off".equals(coverageDebug),
                coverageDebug,
                0L,
                Double.NaN));
        statuses.add(new OptimizationStatus(
                "p1_fill_reflection",
                requested.fillReflection(),
                requested.fillReflection(),
                requested.fillReflection()
                        ? sceneRenderer.hasReflectionConsumer()
                                ? "active: semantic-bin front-to-back, terrain zero-tap, masked ordering, "
                                        + "shared tiered/reprojected reflections and empty-consumer skips"
                                : "active: semantic-bin front-to-back, terrain zero-tap, masked ordering "
                                        + "and empty-consumer skips. No loaded reflection consumer"
                        : "disabled by profile",
                sceneRenderer.getReflectionSavedDraws(),
                saturatedAdd(sceneRenderer.getOpaqueShadedPixelProxy(), sceneRenderer.getReflectionShadedPixelProxy()),
                viewGpuMs("water-reflection")));
        var opaqueGpuMs = viewGpuMs("scene-opaque");
        statuses.add(new OptimizationStatus(
                "p1_opaque_front_to_back",
                requested.fillReflection(),
                requested.fillReflection() && sceneRenderer.getOpaqueShadedPixelProxy() > 0L,
                requested.fillReflection()
                        ? "sequential semantic bins. Order-sensitive MASKED_BLEND/depthless barriers preserved"
                        : "disabled by profile. Stable upload order is authoritative",
                0L,
                sceneRenderer.getOpaqueShadedPixelProxy(),
                opaqueGpuMs));
        statuses.add(new OptimizationStatus(
                "p1_terrain_zero_tap",
                requested.fillReflection(),
                requested.fillReflection() && sceneRenderer.getTerrainShadedPixelProxy() > 0L,
                requested.fillReflection()
                        ? "explicit-gradient shader skips mathematically zero diffuse taps"
                        : "disabled by profile",
                0L,
                sceneRenderer.getTerrainShadedPixelProxy(),
                opaqueGpuMs));
        statuses.add(new OptimizationStatus(
                "p1_masked_front_to_back",
                requested.fillReflection(),
                requested.fillReflection() && sceneRenderer.getMaskedShadedPixelProxy() > 0L,
                requested.fillReflection()
                        ? "alpha-test semantics retained. Blended/depthless masked work remains ordered direct"
                        : "disabled by profile",
                0L,
                sceneRenderer.getMaskedShadedPixelProxy(),
                opaqueGpuMs));
        statuses.add(new OptimizationStatus(
                "p1_reflection_share_latch",
                requested.fillReflection(),
                requested.fillReflection() && sceneRenderer.getVisibleWaterConsumers() > 0,
                !requested.fillReflection()
                        ? "disabled by profile"
                        : sceneRenderer.getVisibleWaterConsumers() == 0
                                ? "no visible water consumer. Reflection and depth copy skipped"
                                : "strict-key coplanar sharing, projected tiers and timed world-space reprojection"
                                        + " active",
                sceneRenderer.getReflectionSavedDraws(),
                sceneRenderer.getReflectionShadedPixelProxy(),
                viewGpuMs("water-reflection")));
        var skippedPasses = skippedNoConsumerPasses();
        statuses.add(new OptimizationStatus(
                "p1_empty_consumer_skip",
                requested.fillReflection(),
                requested.fillReflection(),
                "saved full-screen/child passes="
                        + skippedPasses
                        + ". Executed post passes="
                        + postPasses
                        + ". Depth-copy="
                        + sceneRenderer.wasDepthCopyExecuted()
                        + ". Back-copy="
                        + sceneRenderer.wasWaterBackCopyExecuted(),
                skippedPasses,
                Math.multiplyExact(
                        (long) skippedPasses,
                        framePlan == null ? 0L : framePlan.renderExtent().pixels()),
                skippedPasses > 0 ? 0.0 : viewGpuMs("post-")));
        statuses.add(new OptimizationStatus(
                "p1_selective_depth_prepass",
                requested.fillReflection(),
                false,
                "not retained: no positive target-scene result after front-to-back/Hi-Z",
                0L,
                sceneRenderer.getTerrainShadedPixelProxy(),
                Double.NaN));
        var visibilityCapable = caps.compute()
                && caps.imageReadWrite()
                && caps.drawIndirect()
                && caps.r32fImageReadWrite()
                && caps.rgba8ImageWrite()
                && caps.depthStencilSampled();
        statuses.add(new OptimizationStatus(
                "p2_gpu_visibility",
                requested.gpuVisibility(),
                sceneRenderer.isGpuVisibilityActive(),
                sceneRenderer.getGpuVisibilityReason()
                        + ". Topology resource builds="
                        + sceneRenderer.getTopologyFullResourceBuilds()
                        + ", delta updates="
                        + sceneRenderer.getTopologyDeltaUpdates()
                        + ", uploaded delta bytes="
                        + sceneRenderer.getTopologyDeltaUploadBytes(),
                0L,
                viewGpuMs("visibility")));
        statuses.add(new OptimizationStatus(
                "p3_indirect_submission",
                requested.indirectSubmission(),
                sceneRenderer.isGroupedIndirectActive(),
                !requested.indirectSubmission()
                        ? "disabled by profile"
                        : !visibilityCapable
                                ? "requires active GPU visibility"
                                : sceneRenderer.isGroupedIndirectActive()
                                        ? "active. Multi-command coverage="
                                                + Math.round(sceneRenderer.getIndirectGroupCoverage() * 100.0) + "%"
                                        : sceneRenderer.getGpuVisibilityReason(),
                sceneRenderer.getIndirectSavedSubmits(),
                Double.NaN));
        statuses.add(new OptimizationStatus(
                "p4_parallel_encoders",
                requested.parallelEncoders(),
                false,
                requested.parallelEncoders()
                        ? "removed: the Metal parallel-encoder probe missed the 15% CPU gate"
                        : "removed: the single-encoder path is authoritative",
                0L,
                Double.NaN));
        performance.parallelEncoding(new ParallelEncoding(
                requested.parallelEncoders(),
                false,
                0,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                requested.parallelEncoders()
                        ? "removed: the Metal parallel-encoder probe missed the 15% CPU gate"
                        : "removed: the single-encoder path is authoritative"));
        performance.optimizations(statuses);
    }

    private Upscaler dynamicUpscalerConfig(Upscaler requested) {
        if (!requested.dynamicResolution().enabled() || requested.algorithm() == UpscalerAlgorithm.NATIVE) {
            return requested;
        }
        var scale = dynamicResolution.getScale();
        if (requested.equals(cachedDynamicBase)
                && Double.compare(scale, cachedDynamicScale) == 0
                && cachedDynamicConfig != null) {
            return cachedDynamicConfig;
        }
        cachedDynamicBase = requested;
        cachedDynamicScale = scale;
        cachedDynamicConfig = new Upscaler(
                requested.algorithm(),
                UpscalerPreset.CUSTOM,
                scale,
                requested.rcasEnabled(),
                requested.sharpness(),
                requested.dynamicResolution());
        return cachedDynamicConfig;
    }

    private void applyRuntimeUpscalerControls() {
        var changed = false;
        if (windowContext.keyboard.isClicked(Key.F5)) {
            changed |= upscalerRuntime.cycleAlgorithm();
        }
        if (windowContext.keyboard.isClicked(Key.F6)) {
            changed |= upscalerRuntime.cyclePreset();
        }
        if (!changed) {
            return;
        }
        provider = UpscalerProviders.forAlgorithm(upscalerRuntime.getAlgorithm());
        dynamicResolution = new DynamicResolutionController(upscalerRuntime.getConfig());
        dynamicResolution.reset("provider/preset change");
        sceneRenderer.invalidateVisibilityHistory("upscaler provider/preset changed");
        upscalerFailure = "";
        invalidatePlanCache();
    }

    private Plan resolveFramePlan(Upscaler requested, Extent display, boolean forceCapture) {
        var effectiveConfig = forceCapture ? nativeUpscalerConfig : requested;
        if (framePlan != null
                && effectiveConfig.equals(plannedConfig)
                && display.equals(plannedDisplay)
                && provider == plannedProvider
                && forceCapture == plannedCapture
                && upscalerFailure.equals(plannedFailure)) {
            return framePlan;
        }
        var result = forceCapture
                ? nativeProvider.plan(nativeUpscalerConfig, display)
                : planWithFallback(requested, display);
        plannedConfig = effectiveConfig;
        plannedDisplay = display;
        plannedProvider = provider;
        plannedCapture = forceCapture;
        plannedFailure = upscalerFailure;
        return result;
    }

    private void invalidatePlanCache() {
        plannedConfig = null;
        plannedDisplay = null;
        plannedProvider = null;
        plannedFailure = "";
    }

    private Plan planWithFallback(Upscaler requested, Extent display) {
        if (!upscalerFailure.isEmpty()) {
            return withFallback(nativeProvider.plan(nativeUpscalerConfig, display), upscalerFailure);
        }
        try {
            return provider.plan(requested, display);
        } catch (RuntimeException e) {
            upscalerFailure = provider.getId() + " planning failed: " + e.getMessage();
            dynamicResolution.reset("provider failure");
            sceneRenderer.invalidateVisibilityHistory("upscaler provider failed");
            return withFallback(nativeProvider.plan(nativeUpscalerConfig, display), upscalerFailure);
        }
    }

    private boolean canShowKeptWorld() {
        return keptWorld != null
                && keptPlan != null
                && keptPlan == framePlan
                && windowContext.framebuffer.width == keptPlan.displayExtent().width()
                && windowContext.framebuffer.height == keptPlan.displayExtent().height()
                && recording == null
                && captureOutput == null
                && !benchmark.isEnabled()
                && benchmarkVisualOutput == null
                && !windowContext.screenshot
                && agentShot == null
                && screenshotFrames == 0
                && screenshotTicket == null
                && screenScreenshot == null
                && !screenScreenshotRequested
                && !hasPendingWork()
                && uiContext.rendering.agentDepthFrames == 0
                && uiContext.rendering.agentPickFrames == 0;
    }

    private void finishFrame() {
        if (recording == null && (captureOutput == null || captureUiEnabled)) {
            uiRenderer.render(ImGui.getDrawData(), windowContext.framebuffer.width, windowContext.framebuffer.height);
        }
        if (screenScreenshotRequested) {
            screenScreenshotRequested = false;
            uiContext.screenshots.setWindowCaptured(false);
            requestScreenScreenshot();
        }
        if (benchmarkBackbufferCapture != null) {
            BGFX.bgfx_request_screen_shot(BGFX.BGFX_INVALID_HANDLE, benchmarkBackbufferCapture.toString());
            benchmarkBackbufferCapture = null;
        }
        device.endFrame();
        uiContext.rendering.agentDepthFrames = Math.max(0, uiContext.rendering.agentDepthFrames - 1);
        uiContext.rendering.agentPickFrames = Math.max(0, uiContext.rendering.agentPickFrames - 1);
        var readbackStarted = performance.start();
        pollScreenScreenshot();
        readbacks.poll(device.getStats().frameNumber());
        objectPickerReadback.poll(device.getStats().frameNumber());
        pollMarkers();
        verifyBenchmarkVisualTicket();
        captureWriter.poll();
        performance.addPhase(CpuPhase.READBACK, performance.getElapsed(readbackStarted));
    }

    private void presentWithFallback(BgfxTexture source, BgfxFramebuffer displayOutput) {
        try {
            upscaler.present(source, framePlan, views, displayOutput);
        } catch (RuntimeException e) {
            upscalerFailure = framePlan.provider() + " presentation failed: " + e.getMessage();
            invalidatePlanCache();
            dynamicResolution.reset("provider failure");
            sceneRenderer.invalidateVisibilityHistory("upscaler presentation failed");
            var rescueProvider = UpscalerProviders.forAlgorithm(UpscalerAlgorithm.BILINEAR);
            var rescueConfig = new Upscaler(
                    UpscalerAlgorithm.BILINEAR,
                    UpscalerPreset.CUSTOM,
                    Math.min(framePlan.actualScaleX(), framePlan.actualScaleY()),
                    false,
                    0.0,
                    DynamicResolution.disabled());
            framePlan = withFallback(rescueProvider.plan(rescueConfig, framePlan.displayExtent()), upscalerFailure);
            upscaler.present(source, framePlan, views, displayOutput);
        }
    }

    private double viewGpuMs(String prefix) {
        return viewGpuMs(device.getStats(), prefix);
    }

    private int skippedNoConsumerPasses() {
        var skipped = 0;
        if (!bloomExecuted) {
            skipped += 4;
        }
        if (!underwaterExecuted) {
            skipped++;
        }
        if (sceneRenderer.getVisibleWaterConsumers() == 0) {
            skipped += 2;
        }
        return skipped;
    }

    private void publishHudStats(RenderStats stats) {
        var target = uiContext.rendering;
        target.stats.graphicsBackend = stats.backend();
        target.stats.graphicsRenderer = stats.renderer();
        target.stats.vsync = device.isVsync();
        target.stats.requestedUpscaler = upscalerRuntime.getAlgorithm().name().toLowerCase();
        target.stats.activeUpscaler = stats.activeUpscaler();
        target.stats.upscalerPreset =
                framePlan == null ? "unknown" : framePlan.preset().name().toLowerCase();
        target.stats.graphicsFallback = String.join(". ", stats.fallbackReasons());
        target.stats.optimizationSummary = stats.diagnostics().optimizations().stream()
                .map(status -> status.name() + '=' + (status.active() ? "on" : "off"))
                .collect(Collectors.joining(", "));
        target.stats.displayWidth = stats.extents().display().width();
        target.stats.displayHeight = stats.extents().display().height();
        target.stats.renderWidth = stats.extents().render().width();
        target.stats.renderHeight = stats.extents().render().height();
        target.stats.renderScale =
                target.stats.displayWidth == 0 ? 1.0 : (double) target.stats.renderWidth / target.stats.displayWidth;
        target.stats.upscalerSharpness = framePlan == null ? 0.0 : framePlan.sharpness();
        var dynamic = stats.diagnostics().dynamicResolution();
        target.stats.dynamicResolutionActive = dynamic.active();
        target.stats.dynamicTargetMs = dynamic.targetFrameMs();
        target.stats.dynamicErrorMs = dynamic.controllerErrorMs();
        target.stats.readbackQueueDepth = stats.readbackQueueDepth();
        target.stats.reflectionUpdates = stats.diagnostics().reflectionUpdates();
        target.stats.reflectionReuses = stats.diagnostics().reflectionReuses();
        var visibility = stats.diagnostics().visibility();
        target.stats.gpuVisibilityReason = sceneRenderer.getGpuVisibilityReason();
        target.stats.gpuVisibilityCandidates = visibility.candidates();
        target.stats.gpuVisibilityFrustumCulled = visibility.frustumCulled();
        target.stats.gpuVisibilityDistanceCulled = visibility.distanceCulled();
        target.stats.gpuVisibilityOcclusionCulled = visibility.occlusionCulled();
        target.stats.gpuVisibilityCompacted = visibility.compacted();
        target.stats.gpuVisibilityHistory = visibility.historyValid();
        target.stats.gpuVisibilityCountersValid = visibility.countersValid();
        target.stats.gpuVisibilityCpuFallback = visibility.cpuFallback();
        target.stats.gpuVisibilityStatsAgeFrames = sceneRenderer.getGpuVisibilityStatsAgeFrames();
        target.stats.gpuVisibilityCommands = sceneRenderer.getGpuVisibilityCommandCount();
        target.stats.gpuVisibilityGroups = sceneRenderer.getGpuVisibilityGroupCount();
        target.stats.gpuOpaqueSubmitBatches = sceneRenderer.getGpuOpaqueSubmitBatches();
        target.stats.gpuOpaqueCommandRanges = sceneRenderer.getGpuOpaqueCommandRanges();
        target.stats.gpuOpaqueActiveGroups = sceneRenderer.getGpuOpaqueActiveGroups();
        target.stats.bgfxTopologyMs = cpuPhaseMs(stats.diagnostics(), CpuPhase.TOPOLOGY_UPDATE);
        target.stats.bgfxUploadMs = cpuPhaseMs(stats.diagnostics(), CpuPhase.RESOURCE_UPLOAD);
        target.stats.bgfxWalkMs = cpuPhaseMs(stats.diagnostics(), CpuPhase.TRAVERSAL)
                + cpuPhaseMs(stats.diagnostics(), CpuPhase.CANDIDATE_GENERATION);
        target.stats.bgfxVisibilityMs = cpuPhaseMs(stats.diagnostics(), CpuPhase.VISIBILITY);
        target.stats.bgfxSortEncodeMs = cpuPhaseMs(stats.diagnostics(), CpuPhase.SORTING)
                + cpuPhaseMs(stats.diagnostics(), CpuPhase.COMMAND_ENCODING);
        target.stats.bgfxFrameCpuMs = stats.cpuFrameMs();
        target.stats.bgfxGpuOpaqueMs = viewGpuMs(stats, "scene-opaque");
        target.stats.bgfxGpuTranslucentMs = viewGpuMs(stats, "scene-translucent");
        target.stats.bgfxGpuFxMs = viewGpuMs(stats, "scene-fx");
        target.stats.bgfxGpuVisibilityMs = viewGpuMs(stats, "visibility-");
        target.stats.bgfxGpuReflectionMs = reflectionGpuMs(stats);
        target.stats.bgfxGpuPostMs = viewGpuMs(stats, "post-");
        target.stats.bgfxGpuUiMs = viewGpuMs(stats, "display-ui");
        target.stats.gpuPasses = stats.views();
        var fx = sceneRenderer.getFxDrawCounts();
        target.stats.particleMeshDraws = fx.meshes();
        target.stats.particleBillboardDraws = fx.billboards();
        target.stats.particleBeamDraws = fx.beams();
        if (Double.isFinite(stats.gpuFrameMs())) {
            target.gpuProfiling = true;
            target.stats.sceneGpuMs = (float) stats.gpuFrameMs();
        }
    }

    private void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        try {
            device.drainPendingFrames();
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            benchmark.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        try {
            captureWriter.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        try {
            readbacks.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (pointsReadback != null) {
            try {
                pointsReadback.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            pointsReadback = null;
        }
        for (var index = 0; index < markerReadbacks.length; index++) {
            if (markerReadbacks[index] != null) {
                try {
                    markerReadbacks[index].close();
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
                markerReadbacks[index] = null;
            }
        }
        try {
            objectPickerReadback.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        try {
            uploads.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        try {
            geodataRenderer.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        try {
            spawnRenderer.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        try {
            sceneRenderer.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        try {
            uiRenderer.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        try {
            postProcessor.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        try {
            customLightingProcessor.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        try {
            metalRayQueries.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        try {
            upscaler.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (screenshotTarget != null) {
            try {
                screenshotTarget.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            screenshotTarget = null;
        }
        if (recordingTarget != null) {
            try {
                recordingTarget.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            recordingTarget = null;
        }
        if (benchmarkVisualTarget != null) {
            try {
                benchmarkVisualTarget.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        try {
            frameTargets.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        try {
            device.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("bgfx viewer backend is closed");
        }
    }

    private boolean isBenchmarkReady() {
        if (benchmarkReady) {
            return true;
        }
        var rendering = uiContext.rendering;
        if (benchmarkStreamingReadyTiles > 0) {
            if (rendering.loading.tilesTotal > 0 && benchmarkStreamingReadyTiles > rendering.loading.tilesTotal) {
                throw new IllegalStateException(
                        ViewerBenchmarkProperties.STREAMING_READY_TILES + "=" + benchmarkStreamingReadyTiles
                                + " exceeds the world tile count " + rendering.loading.tilesTotal);
            }
            benchmarkReady = rendering.loading.tilesLoaded >= benchmarkStreamingReadyTiles;
        } else {
            benchmarkReady = !rendering.loading.active && rendering.loading.tilesLoaded >= rendering.loading.tilesTotal;
        }
        if (benchmarkReady) {
            benchmarkScenario.applyInitial(renderingContext.camera, uiContext);
            renderingContext.particles.prepareDeterministicSnapshot(renderingContext.camera.getPosition());
            renderingContext.playback.setSeconds(benchmarkScenario.getSimulationTimeSeconds());
            renderingContext.context.time = renderingContext.playback.getSeconds();
        }
        return benchmarkReady;
    }

    private boolean captureExtentRequired() {
        return captureOutput != null
                || recording != null
                || mapCaptureTicket != null && mapCaptureTicket.getState() == State.QUEUED;
    }

    private double advanceRecording() {
        var loading = uiContext.rendering.loading;
        var scene = renderingContext.gameScenes;
        var delta = recording.advance(new SceneState(
                loading.active || loading.reloading,
                scene.isPlaying(),
                scene.isReading(),
                sceneRenderer.areSunShadowsBuilt(),
                scene.getSeconds()));
        if (recording.isStarted()) {
            renderingContext.pictureClock.fix();
        }
        if (recording.isSettled()) {
            renderingContext.particles.prepareDeterministicSnapshot(renderingContext.camera.getPosition());
        }
        renderingContext.pictureClock.advance(delta);
        return renderingContext.playback.advanceExact(delta);
    }

    private void renderRecordingFrame(BgfxTexture worldColor) {
        var display = framePlan.displayExtent();
        if (recordingTarget == null) {
            recordingTarget = new BgfxDisplayTarget(device.getResources());
        }
        recordingTarget.ensure(display);
        presentWithFallback(worldColor, recordingTarget.getFramebuffer());
        uiRenderer.render(
                ImGui.getDrawData(),
                display.width(),
                display.height(),
                recordingTarget.getFramebuffer(),
                views.allocate("record-interface", Domain.DISPLAY, true));
        if (recording.wantsFrame()) {
            recording.requested(requestReadback(new ReadbackRequest(Source.WORLD_COLOR, display, "record")));
        }
        readbacks.submitWorld(recordingTarget.getColor(), views);
    }

    private void renderScreenshotFrame(BgfxTexture worldColor) {
        var display = framePlan.displayExtent();
        BgfxTexture source;
        if ("native".equals(framePlan.provider()) && framePlan.renderExtent().equals(display)) {
            presentWithFallback(worldColor, null);
            source = worldColor;
        } else {
            if (screenshotTarget == null) {
                screenshotTarget = new BgfxDisplayTarget(device.getResources());
            }
            screenshotTarget.ensure(display);
            presentWithFallback(worldColor, screenshotTarget.getFramebuffer());
            upscaler.present(screenshotTarget.getColor(), nativeProvider.plan(nativeUpscalerConfig, display), views);
            source = screenshotTarget.getColor();
        }
        screenshotFrames--;
        if (screenshotFrames == 0) {
            screenshotTicket = requestReadback(new ReadbackRequest(Source.WORLD_COLOR, display, "screenshot"));
            var screenshots = uiContext.screenshots;
            var shot = agentShot;
            agentShot = null;
            if (shot != null) {
                captureWriter.addShot(screenshotTicket, shot.file(), shot.done());
                scheduleAutomaticCaptures();
                readbacks.submitWorld(source, views);
                return;
            }
            var file = Screenshots.nextFile(screenshots.getFolder(), LocalDateTime.now());
            captureWriter.addShot(screenshotTicket, file, (written, failure) -> {
                if (failure == null) {
                    log.info("Saved a {}x{} screenshot to {}", display.width(), display.height(), written);
                    screenshots.recordSaved(written);
                } else {
                    log.warn("Could not save a screenshot to {}", written, failure);
                    screenshots.recordFailure(written, failure.getMessage());
                }
            });
        }
        scheduleAutomaticCaptures();
        readbacks.submitWorld(source, views);
    }

    private boolean editsPawns() {
        return renderingContext.hasEditingSession();
    }

    private Map<String, float[]> splitPawns(Map<String, float[]> deltas, Map<String, float[]> pawns) {
        var stage = renderingContext.mapPawns;
        Map<String, float[]> scene = deltas;
        for (var entry : deltas.entrySet()) {
            if (stage.hasPawn(entry.getKey())) {
                if (scene == deltas) {
                    scene = new LinkedHashMap<>(deltas);
                }
                pawns.put(entry.getKey(), entry.getValue());
                scene.remove(entry.getKey());
            }
        }
        return scene;
    }

    private boolean drawsNothing() {
        return "Noop".equals(device.getCapabilities().renderer());
    }

    private record AgentShot(Path file, boolean withInterface, BiConsumer<Path, Throwable> done) {}

    private void requestScreenScreenshot() {
        var screenshots = uiContext.screenshots;
        var shot = agentShot;
        agentShot = null;
        var output = shot != null ? shot.file() : Screenshots.nextFile(screenshots.getFolder(), LocalDateTime.now());
        var done = shot != null ? shot.done() : null;
        try {
            var folder = Files.createTempDirectory("cruma-screenshot-");
            var base = folder.resolve("screen");
            BGFX.bgfx_request_screen_shot(BGFX.BGFX_INVALID_HANDLE, base.toString());
            screenScreenshot = new ScreenScreenshot(
                    folder, folder.resolve("screen.tga"), output, System.nanoTime() + 10_000_000_000L, null, done);
        } catch (IOException | RuntimeException failure) {
            log.warn("Could not start a screenshot", failure);
            reportScreenFailure(output, done, failure);
        }
    }

    private void reportScreenFailure(Path output, @Nullable BiConsumer<Path, Throwable> done, Throwable failure) {
        if (done != null) {
            done.accept(output, failure);
        } else {
            uiContext.screenshots.recordFailure(output, failure.getMessage());
        }
    }

    private void pollScreenScreenshot() {
        var pending = screenScreenshot;
        if (pending == null) {
            return;
        }
        var screenshots = uiContext.screenshots;
        if (pending.conversion() == null) {
            if (!Files.isRegularFile(pending.tga())) {
                if (System.nanoTime() > pending.deadline()) {
                    screenScreenshot = null;
                    deleteQuietly(pending);
                    reportScreenFailure(
                            pending.output(), pending.done(), new IOException("the window image did not arrive"));
                }
                return;
            }
            screenScreenshot = new ScreenScreenshot(
                    pending.folder(),
                    pending.tga(),
                    pending.output(),
                    pending.deadline(),
                    CompletableFuture.supplyAsync(() -> {
                        try {
                            return Screenshots.convertTga(pending.tga(), pending.output());
                        } catch (IOException failure) {
                            throw new UncheckedIOException(failure);
                        }
                    }),
                    pending.done());
            return;
        }
        if (!pending.conversion().isDone()) {
            return;
        }
        try {
            if (!pending.conversion().join()) {
                if (System.nanoTime() > pending.deadline()) {
                    screenScreenshot = null;
                    deleteQuietly(pending);
                    reportScreenFailure(
                            pending.output(), pending.done(), new IOException("the window image was incomplete"));
                } else {
                    screenScreenshot = new ScreenScreenshot(
                            pending.folder(),
                            pending.tga(),
                            pending.output(),
                            pending.deadline(),
                            null,
                            pending.done());
                }
                return;
            }
            log.info("Saved a screenshot with the interface to {}", pending.output());
            if (pending.done() != null) {
                pending.done().accept(pending.output(), null);
            } else {
                screenshots.recordSaved(pending.output());
            }
        } catch (RuntimeException failure) {
            var cause = failure.getCause() == null ? failure : failure.getCause();
            log.warn("Could not save a screenshot to {}", pending.output(), cause);
            reportScreenFailure(pending.output(), pending.done(), cause);
        }
        screenScreenshot = null;
        deleteQuietly(pending);
    }

    private boolean isBenchmarkVisualPending() {
        return benchmarkVisualOutput != null
                && benchmarkVisualTarget != null
                && benchmarkReady
                && (benchmarkVisualAfterMeasurement
                        ? benchmark.isComplete()
                        : benchmark.getSeenFrames() > benchmark.getWarmupFrames())
                && (!benchmarkVisualScheduled
                        || benchmarkVisualInterval > 0
                                && benchmarkVisualVerified
                                && !benchmark.isComplete()
                                && benchmark.getSeenFrames() - benchmarkLastVisualFrame >= benchmarkVisualInterval)
                && !windowContext.screenshot
                && screenshotFrames == 0
                && windowContext.mapIndex < 0
                && framePlan != null
                && !framePlan.displayExtent().isZero();
    }

    private void captureBenchmarkVisual(BgfxTexture rawWorldColor, BgfxTexture worldColor) {
        var display = framePlan.displayExtent();
        BgfxTexture captureSource;
        if ("native".equals(framePlan.provider()) && framePlan.renderExtent().equals(display)) {
            presentWithFallback(worldColor, null);
            captureSource = worldColor;
        } else {
            benchmarkVisualTarget.ensure(display);
            presentWithFallback(worldColor, benchmarkVisualTarget.getFramebuffer());
            upscaler.present(
                    benchmarkVisualTarget.getColor(), nativeProvider.plan(nativeUpscalerConfig, display), views);
            captureSource = benchmarkVisualTarget.getColor();
        }
        benchmarkRawVisualTicket = requestReadback(
                new ReadbackRequest(Source.WORLD_COLOR, framePlan.renderExtent(), "benchmark-visual-raw"));
        readbacks.submitWorld(rawWorldColor, views);
        benchmarkVisualTicket = requestReadback(new ReadbackRequest(Source.WORLD_COLOR, display, "benchmark-visual"));
        var output = benchmarkVisualOutput;
        if (benchmarkVisualScheduled) {
            var name = output.getFileName().toString();
            var dot = name.lastIndexOf('.');
            output = output.resolveSibling((dot < 0 ? name : name.substring(0, dot))
                    + "-"
                    + benchmark.getSeenFrames()
                    + (dot < 0 ? ".png" : name.substring(dot)));
        }
        captureWriter.add(benchmarkVisualTicket, output);
        if (Boolean.getBoolean("cruma.viewer.benchmark.visual_backbuffer")) {
            var name = output.getFileName().toString();
            var dot = name.lastIndexOf('.');
            benchmarkBackbufferCapture =
                    output.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + ".backbuffer");
        }
        if (Boolean.getBoolean("cruma.viewer.benchmark.visual_raw")) {
            var name = output.getFileName().toString();
            var dot = name.lastIndexOf('.');
            var rawOutput = output.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + ".raw.png");
            captureWriter.add(benchmarkRawVisualTicket, rawOutput);
        }
        benchmarkVisualScheduled = true;
        benchmarkVisualVerified = false;
        benchmarkLastVisualFrame = benchmark.getSeenFrames();
        sceneRenderer.traceWaterFrame(benchmarkLastVisualFrame, views.getRemaining());
        readbacks.submitWorld(captureSource, views);
    }

    private void verifyBenchmarkVisualTicket() {
        if (benchmarkVisualTicket == null || benchmarkVisualVerified) {
            return;
        }
        if (benchmarkRawVisualTicket == null) {
            throw new IllegalStateException("benchmark raw visual ticket was not scheduled");
        }
        if (benchmarkRawVisualTicket.getState() == State.FAILED) {
            throw new IllegalStateException(
                    "benchmark raw visual readback failed: " + benchmarkRawVisualTicket.getFailure());
        }
        if (benchmarkRawVisualTicket.getState() == State.CANCELLED) {
            throw new IllegalStateException("benchmark raw visual readback was cancelled");
        }
        if (benchmarkVisualTicket.getState() == State.FAILED) {
            throw new IllegalStateException("benchmark visual readback failed: " + benchmarkVisualTicket.getFailure());
        }
        if (benchmarkVisualTicket.getState() == State.CANCELLED) {
            throw new IllegalStateException("benchmark visual readback was cancelled");
        }
        if (benchmarkRawVisualTicket.getState() == State.READY && benchmarkVisualTicket.getState() == State.READY) {
            var rawExtent = benchmarkRawVisualTicket.getExtent();
            var rawVisible = BgfxCaptureWriter.containsVisibleRgb(
                    benchmarkRawVisualTicket.getData(), rawExtent.width(), rawExtent.height());
            var extent = benchmarkVisualTicket.getExtent();
            if (!BgfxCaptureWriter.containsVisibleRgb(
                    benchmarkVisualTicket.getData(), extent.width(), extent.height())) {
                throw new IllegalStateException("benchmark visual readback is entirely black at "
                        + extent.width()
                        + 'x'
                        + extent.height()
                        + ". Raw scene target visible="
                        + rawVisible
                        + " at "
                        + rawExtent.width()
                        + 'x'
                        + rawExtent.height());
            }
            benchmarkVisualVerified = true;
        }
    }

    private UnderwaterState underwaterState() {
        return underwaterState(
                renderingContext.scene.findContainingWaterVolume(renderingContext.camera.getPosition(), zoneState()));
    }

    private ZoneStateChoice zoneState() {
        var events = uiContext.rendering.getGameEventStates();
        return events == null ? ZoneStateChoice.EMPTY : events.zoneStates();
    }

    private void scheduleAutomaticCaptures() {
        if (windowContext.mapIndex >= 0
                && windowContext.mapCaptureSubmitted < windowContext.mapIndex
                && mapCaptureTicket == null
                && windowContext.pendingCaptureTile != null
                && sceneRenderer.captureLightingReady()
                && renderingContext.mapPawns.isSettled()
                && captureWriter.canAcceptTile()) {
            var tile = windowContext.pendingCaptureTile;
            var shot = currentCaptureShot(tile);
            mapCaptureTicket = requestReadback(new ReadbackRequest(
                    Source.WORLD_COLOR,
                    captureExtent,
                    "tile-" + tile.mapX() + '-' + tile.mapY() + (shot.main() ? "" : "-" + shot.name())));
            captureWriter.addTile(mapCaptureTicket, tile, shot);
        }
    }

    private void acknowledgeSubmittedTile() {
        if (mapCaptureTicket != null && mapCaptureTicket.getState() == State.SUBMITTED) {
            mapCaptureTicket = null;
            if (captureShot + 1 < captureShots.size()) {
                captureShot++;
                return;
            }
            windowContext.mapCaptureSubmitted = windowContext.mapIndex;
            windowContext.pendingCaptureTile = null;
        }
    }

    private CaptureView currentCaptureShot(Tile tile) {
        if (captureShotsMapIndex != windowContext.mapIndex) {
            captureShots = captureOutput.shots(tile.mapX(), tile.mapY());
            captureShotsMapIndex = windowContext.mapIndex;
            captureShot = 0;
        }
        return captureShots.get(captureShot);
    }

    private void applyCaptureShot() {
        var tile = windowContext.pendingCaptureTile;
        var camera = renderingContext.camera;
        if (tile == null) {
            uiContext.rendering.passable = true;
            camera.cutHeights(null, null);
            applyCaptureContent(null, "");
            return;
        }
        var shot = currentCaptureShot(tile);
        applyCaptureContent(shot, windowContext.pendingCaptureMap);
        var size = shot.resolution() == null ? captureOutput.tileResolution() : shot.resolution();
        if (size != captureExtent.width()) {
            if (size > device.getCapabilities().maxTextureSize()) {
                throw new IllegalArgumentException("capture view "
                        + shot.label()
                        + " resolution="
                        + size
                        + " exceeds renderer max texture size="
                        + device.getCapabilities().maxTextureSize());
            }
            captureExtent = new Extent(size, size);
            windowContext.capture.width = size;
            windowContext.capture.height = size;
        }
        var eye = shot.top() != null && shot.top() >= BgfxSceneUploadSink.CAPTURE_EYE_Z
                ? shot.top() + 1f
                : BgfxSceneUploadSink.CAPTURE_EYE_Z;
        var position = camera.getPosition();
        if (position.getZ() != eye) {
            camera.setPosition(position.getX(), position.getY(), eye);
        }
        camera.cutHeights(shot.top(), shot.bottom(), shot.kept(), shot.circle());
        uiContext.rendering.passable = !shot.hidePassable();
    }

    private void applyCaptureContent(@Nullable CaptureView shot, String map) {
        var rendering = uiContext.rendering;
        var fromClient = rendering.gameEventStates != captureShotEvents;
        if (fromClient) {
            captureClientEvents = rendering.gameEventStates;
        }
        var content = shot == null ? ViewContent.AS_THE_CLIENT : shot.content();
        if (fromClient || shot != captureEventsShot || !map.equals(captureEventsMap)) {
            captureEventsShot = shot;
            captureEventsMap = map;
            captureShotEvents = content.over(captureClientEvents, map);
        }
        rendering.gameEventStates = captureShotEvents;
        renderingContext.particles.setCaptureEffects(content.effects());
    }

    private @Nullable CutCircle viewerCircle() {
        var rendering = uiContext.rendering;
        if (!rendering.orthoCircle) {
            return null;
        }
        var x = rendering.orthoCircleX.get();
        var y = rendering.orthoCircleY.get();
        var radius = Math.clamp(rendering.orthoCircleRadius[0], 1, CutCircle.MAX_RADIUS);
        if (viewerCircle == null || viewerCircle.x() != x || viewerCircle.y() != y || viewerCircle.radius() != radius) {
            viewerCircle = new CutCircle(x, y, radius);
        }
        return viewerCircle;
    }

    private void clearTerminalCaptureTickets() {
        if (screenshotTicket != null
                && switch (screenshotTicket.getState()) {
                    case READY, CANCELLED, FAILED -> true;
                    case QUEUED, SUBMITTED -> false;
                }) {
            screenshotTicket = null;
        }
        if (mapCaptureTicket != null
                && switch (mapCaptureTicket.getState()) {
                    case READY, CANCELLED, FAILED -> true;
                    case QUEUED, SUBMITTED -> false;
                }) {
            mapCaptureTicket = null;
        }
    }

    private void pollMarkers() {
        for (var marker : markerReadbacks) {
            if (marker != null) {
                marker.poll(device.getStats().frameNumber());
            }
        }
        if (pointsReadback != null) {
            pointsReadback.poll(device.getStats().frameNumber());
        }
    }

    private static Plan withFallback(Plan plan, String reason) {
        return new Plan(
                plan.provider(),
                plan.preset(),
                plan.renderExtent(),
                plan.displayExtent(),
                plan.actualScaleX(),
                plan.actualScaleY(),
                plan.rcas(),
                plan.sharpness(),
                plan.passCount(),
                reason);
    }

    private static double viewGpuMs(RenderStats stats, String prefix) {
        var total = 0.0;
        var found = false;
        for (var view : stats.views()) {
            if (view.name().startsWith(prefix) && Double.isFinite(view.gpuMs())) {
                total += view.gpuMs();
                found = true;
            }
        }
        return found ? total : Double.NaN;
    }

    private static double reflectionGpuMs(RenderStats stats) {
        var total = 0.0;
        var found = false;
        for (var view : stats.views()) {
            if (view.name().contains("reflection") && Double.isFinite(view.gpuMs())) {
                total += view.gpuMs();
                found = true;
            }
        }
        return found ? total : Double.NaN;
    }

    private static long saturatedAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static double cpuPhaseMs(RenderDiagnostics diagnostics, CpuPhase phase) {
        for (var timing : diagnostics.cpuPhases()) {
            if (timing.phase() == phase) {
                return timing.measured() ? timing.milliseconds() : 0.0;
            }
        }
        return 0.0;
    }

    private static void deleteQuietly(ScreenScreenshot pending) {
        try {
            Files.deleteIfExists(pending.tga());
            Files.deleteIfExists(pending.folder());
        } catch (IOException ignored) {
        }
    }

    private static Vec4 underwaterTint(World worldConfig) {
        try {
            var ranges =
                    FogRanges.parse(worldConfig.clientPath().resolve("system").resolve("env.int"));
            var color = ranges.underwater();
            return new Vec4(
                    color.cellophaneRed(), color.cellophaneGreen(),
                    color.cellophaneBlue(), color.cellophaneAlpha());
        } catch (RuntimeException ignored) {
            return FogRanges.defaultCellophaneColor();
        }
    }

    private static void closeAfterFailure(AutoCloseable resource, Throwable failure) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
