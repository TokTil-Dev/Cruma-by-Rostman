package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_ACCESS_READ;
import static org.lwjgl.bgfx.BGFX.BGFX_ACCESS_READWRITE;
import static org.lwjgl.bgfx.BGFX.BGFX_ACCESS_WRITE;
import static org.lwjgl.bgfx.BGFX.BGFX_BUFFER_COMPUTE_READ;
import static org.lwjgl.bgfx.BGFX.BGFX_BUFFER_COMPUTE_READ_WRITE;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIP_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_BLIT_DST;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_COMPUTE_WRITE;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_R32F;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_READ_BACK;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_MAT4;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_SAMPLER;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_VEC4;
import static org.lwjgl.bgfx.BGFX.bgfx_blit;
import static org.lwjgl.bgfx.BGFX.bgfx_dispatch;
import static org.lwjgl.bgfx.BGFX.bgfx_read_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_compute_dynamic_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_compute_dynamic_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_compute_indirect_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_image;
import static org.lwjgl.bgfx.BGFX.bgfx_set_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_name;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_transform;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.geometry.Frustum;
import com.rost.cruma.core.world.Entity.BlendMode;
import com.rost.cruma.core.world.Entity.StaticMeshLod;
import com.rost.cruma.core.world.Entity.SurfaceType;
import com.rost.cruma.viewer.render.ParticleEmitter;
import com.rost.cruma.viewer.render.ParticleSystem;
import com.rost.cruma.viewer.render.RenderingContext;
import com.rost.cruma.viewer.render.api.RenderDiagnostics.Visibility;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.api.RenderFrameSnapshot.RenderFamily;
import com.rost.cruma.viewer.render.api.RenderState;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.Optimizations;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.Tuning;
import com.rost.cruma.viewer.render.bgfx.BgfxCommandRangeHierarchy.Query;
import com.rost.cruma.viewer.render.bgfx.BgfxGeometryPool.IndirectBinding;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneGeometry.Kind;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneGeometry.Primitive;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneMaterial.ProgramFamily;
import com.rost.cruma.viewer.render.bgfx.BgfxViewAllocator.Domain;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongToDoubleFunction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class BgfxGpuVisibility implements AutoCloseable {
    static final float MAX_HISTORY_TRANSLATION = 256f;
    static final int MATERIAL_TABLE_STAGE = 10;
    static final int FLAG_ORDERED = 1 << 7;

    private static final int GEOMETRIC_CAPACITY_LIMIT = 1 << 20;

    private static final int PYRAMID_THREADS = 8;
    private static final int MAX_EXACT_FLOAT_INTEGER = 1 << 24;

    private static final float EYE_PLANE_EPSILON = 0.00001f;
    private static final Bounds3f EMPTY_SLOT = new Bounds3f(0f, 0f, 0f, 0f, 0f, 0f);
    private static final float EMPTY_SLOT_REACH = 1e-3f;

    private static final int STATS_SLOT_COUNT = 4;
    private static final long STATS_SAMPLE_INTERVAL = 30L;
    private static final int SUBMISSION_EQUIVALENT_RANGES = 4;
    private static final int REQUESTED_SUBMISSION_COST = Math.clamp(
            Integer.getInteger("cruma.viewer.bgfx.submission_span_cost", SUBMISSION_EQUIVALENT_RANGES), 1, 1024);
    private static final int SUBMISSION_COST_CYCLE_FRAMES =
            Math.max(0, Integer.getInteger("cruma.viewer.bgfx.submission_span_cost_cycle_frames", 0));
    private static final float MAX_HISTORY_TRANSLATION_SQUARED = MAX_HISTORY_TRANSLATION * MAX_HISTORY_TRANSLATION;
    private static final float MIN_HISTORY_FORWARD_DOT = 0.98480775f;
    private static final int CATEGORY_PASSABLE = 1;
    private static final int CATEGORY_TERRAIN = 1 << 1;
    private static final int CATEGORY_STATIC_MESH = 1 << 2;
    private static final int CATEGORY_CSG = 1 << 3;
    private static final int CATEGORY_BOUNDING_BOX = 1 << 4;
    private static final int FLAG_TERRAIN_PROXY = 1 << 5;
    private static final int FLAG_TERRAIN_DETAIL = 1 << 6;
    private static final int MATERIAL_RECORD_SHIFT = 8;
    private static final int MATERIAL_RECORD_STRIDE = BgfxSceneUniforms.MATERIAL_VECTORS;
    private static final int MATERIAL_RECORD_BYTES = MATERIAL_RECORD_STRIDE * 4 * Float.BYTES;
    private static final long PYRAMID_FLAGS = BGFX_TEXTURE_COMPUTE_WRITE
            | BGFX_SAMPLER_MIN_POINT
            | BGFX_SAMPLER_MAG_POINT
            | BGFX_SAMPLER_MIP_POINT
            | BGFX_SAMPLER_U_CLAMP
            | BGFX_SAMPLER_V_CLAMP;
    private static final int DEPTH_SAMPLER_FLAGS = BGFX_SAMPLER_MIN_POINT
            | BGFX_SAMPLER_MAG_POINT
            | BGFX_SAMPLER_MIP_POINT
            | BGFX_SAMPLER_U_CLAMP
            | BGFX_SAMPLER_V_CLAMP;
    private static final String[] PYRAMID_VIEW_NAMES = pyramidViewNames();

    private static final Logger log = LogManager.getLogger(BgfxGpuVisibility.class);

    private final BgfxDevice device;
    private final RenderingContext renderingContext;
    private final RenderState state;
    private final Optimizations optimizations;
    private final boolean requested;
    private final boolean groupedRequested;
    private final int maximumCandidates;
    private final int maximumCommands;
    private final float minimumProjectedPixels;
    private float terrainDetailPixels;
    private float impostorDistanceRatio = 1f;
    private float grassDistanceMultiplier = 1f;
    private long lodSettingsRevision;
    private final int commandHierarchyLeafCommands;

    private BgfxProgram copyDepthProgram;
    private BgfxProgram downsampleProgram;
    private BgfxProgram resetProgram;
    private BgfxProgram cullProgram;
    private BgfxProgram orderCountProgram;
    private BgfxProgram orderMoveProgram;
    private BgfxProgram finalizeProgram;
    private BgfxUniform depthSampler;
    private BgfxUniform configUniform;
    private BgfxUniform extentUniform;
    private BgfxUniform cameraUniform;
    private BgfxUniform optionsUniform;
    private BgfxUniform lodUniform;
    private BgfxUniform previousViewProjectionUniform;
    private BgfxUniform pyramidUniform;
    private StatsSlot[] statsSlots;
    private BgfxTexture statsClassifications;
    private Extent statsExtent = new Extent(0, 0);
    private int nextStatsSlot;
    private int latestFrustumCulled;
    private int latestDistanceCulled;
    private int latestOcclusionCulled;
    private int latestCompacted;
    private boolean latestStatsValid;
    private int unchangedCameraFrames;
    private boolean inconsistentClassificationWarned;
    private long latestStatsSubmissionFrame = Long.MIN_VALUE;
    private final String geometryAuditOutput =
            System.getProperty("cruma.viewer.bgfx.geometry_workload_audit", "").trim();
    private int geometryAuditSamples;
    private long lastStatsSubmissionFrame = Long.MIN_VALUE;
    private List<List<SubmissionSpan>> latestSubmissionPlan = List.of();

    private final ArrayList<List<SubmissionSpan>> delayedSubmissionPlan = new ArrayList<>();

    private final ArrayList<PooledSubmissionSpanList> delayedSubmissionGroupPool = new ArrayList<>();
    private boolean[] delayedVisibleCommands = new boolean[0];
    private final float[] latestSubmissionView = new float[16];
    private final float[] latestSubmissionProjection = new float[16];
    private Extent latestSubmissionExtent = new Extent(0, 0);
    private int latestSubmissionCategoryMask;
    private float latestSubmissionMaximumDistanceSquared;
    private long latestSubmissionTopologyGeneration = Long.MIN_VALUE;
    private int submissionCost = SUBMISSION_EQUIVALENT_RANGES;
    private int latestSubmissionCost;
    private int latestPlannedCommandRanges;
    private boolean delayedSubmissionPlanThisFrame;
    private final ArrayList<SubmissionSpan> immediateSubmissionSpans = new ArrayList<>();
    private final ArrayList<SubmissionSpan> immediateSubmissionSpanPool = new ArrayList<>();
    private int immediateSubmissionSpanPoolUsed;
    private int[] immediateVisibleCommands = new int[0];
    private int[] immediateVisibleCommandEpoch = new int[0];
    private int immediateVisibilityEpoch;
    private int[] immediateHierarchyStack = new int[0];
    private final int visibilityWorkers = Math.clamp(
            Integer.getInteger(
                    "cruma.viewer.bgfx.visibility_workers",
                    Math.min(4, Runtime.getRuntime().availableProcessors())),
            1,
            4);
    private final boolean verifyVisibilityWorkers = Boolean.getBoolean("cruma.viewer.bgfx.verify_visibility_workers");
    private final ImmediateQuery[] immediateQueries = {
        new ImmediateQuery(), new ImmediateQuery(), new ImmediateQuery(), new ImmediateQuery()
    };
    private int[] parallelVisibleCounts = new int[0];
    private int[] parallelVisitedNodes = new int[0];
    private int[] parallelTestedCommands = new int[0];
    private boolean parallelImmediatePrepared;
    private int parallelImmediateEpoch;
    private long verifiedParallelGroups;
    private int currentHierarchyVisitedNodes;
    private int currentHierarchyTestedCommands;
    private Frustum immediateSubmissionFrustum;
    private boolean immediateSubmissionPlanThisFrame;
    private int currentPlannedCommandRanges;
    private long topologyGeneration;
    private long submissionPlanRevision;

    private Topology topology;
    private BgfxTexture[] pyramids;
    private Extent pyramidExtent = new Extent(0, 0);
    private Extent historyExtent = new Extent(0, 0);
    private int readablePyramid;
    private int mipCount;
    private boolean historyAvailable;
    private final float[] currentView = new float[16];
    private final float[] currentProjection = new float[16];
    private final float[] currentViewProjection = new float[16];
    private final float[] historyView = new float[16];
    private final float[] historyProjection = new float[16];
    private final float[] historyViewProjection = identity();
    private final float[] historyCamera = new float[6];
    private final float[] configData = new float[4];
    private final float[] extentData = new float[4];
    private final float[] cameraData = new float[4];
    private final float[] optionsData = new float[4];
    private final float[] lodData = new float[4];
    private final float[] pyramidData = new float[4];
    private final float[] orderData = new float[4];
    private int historyDepthHandle = -1;
    private boolean preparedThisFrame;
    private boolean historyUsedThisFrame;
    private String historyResetReason = "first frame";
    private String historyBypassReason = "first frame";
    private boolean closed;
    private String fallbackReason;
    private final HashSet<String> reportedFallbacks = new HashSet<>();
    private int overflowFallbacks;
    private long topologyDeltaUploadBytes;
    private long topologyFullResourceBuilds;
    private long topologyDeltaUpdates;
    private long topologyBuilds;
    private long topologyBuildNanos;
    private int terrainLodPairs;

    BgfxGpuVisibility(
            BgfxDevice device,
            RenderingContext renderingContext,
            RenderState state,
            Optimizations optimizations,
            Tuning tuning) {
        this.device = device;
        this.renderingContext = renderingContext;
        this.state = state;
        this.optimizations = optimizations;
        this.requested = optimizations.gpuVisibility();
        this.groupedRequested =
                optimizations.indirectSubmission() && !Boolean.getBoolean("cruma.viewer.bgfx.indirect_grouping_audit");
        this.maximumCandidates = dispatchCapacity(tuning.visibilityMaxCandidates());
        this.maximumCommands = dispatchCapacity(tuning.visibilityMaxCommands());
        this.minimumProjectedPixels = (float) tuning.visibilityMinProjectedPixels();
        this.terrainDetailPixels = (float) tuning.visibilityTerrainDetailPixels();
        this.commandHierarchyLeafCommands = tuning.commandHierarchyLeafCommands();
        fallbackReason = capabilityFailure(device.getCapabilities());
        if (!requested || fallbackReason != null) {
            return;
        }
        createPipeline();
    }

    static int commandMetadataElements(int commandCapacity, int materialCapacity) {
        if (commandCapacity < 0 || materialCapacity < 0) {
            throw new IllegalArgumentException("visibility metadata capacities cannot be negative");
        }
        return Math.multiplyExact(commandCapacity, 4);
    }

    static int candidateFlags(Primitive primitive, int topologyFlags, int materialRecord) {
        var flags = categoryFlags(primitive) | topologyFlags;
        if (isOrderSensitive(primitive)) {
            flags |= FLAG_ORDERED;
        }
        if (materialRecord >= 0) {
            flags |= materialRecord << MATERIAL_RECORD_SHIFT;
        }
        return flags;
    }

    static Extent statisticsCapacity(
            int candidateCount, int maximumTextureSize, int maximumCandidates, Extent current) {
        if (candidateCount < 1 || maximumTextureSize < 1 || maximumCandidates < candidateCount || current == null) {
            throw new IllegalArgumentException("invalid visibility statistics capacity request");
        }
        if (current.pixels() >= candidateCount) {
            return current;
        }
        var maximumTexels =
                Math.min((long) maximumCandidates, Math.multiplyExact((long) maximumTextureSize, maximumTextureSize));
        if (candidateCount > maximumTexels) {
            throw new CapacityOverflow(
                    "visibility classification texture exceeds maximum texel count: " + candidateCount);
        }
        var target = capacityFor(candidateCount, Math.toIntExact(maximumTexels));
        var width = Math.min(maximumTextureSize, Math.max(1, (int) Math.ceil(Math.sqrt(target))));
        var height = Math.max(1, (target + width - 1) / width);
        if (height > maximumTextureSize) {
            throw new CapacityOverflow(
                    "visibility classification texture exceeds maximum extent: " + width + 'x' + height);
        }
        return new Extent(width, height);
    }

    static boolean requiresSubmission(ByteBuffer classifications, int firstCandidate, int count) {
        if (firstCandidate < 0 || count < 0 || (long) firstCandidate + count > classifications.limit() / 4) {
            throw new IllegalArgumentException("invalid candidate classification range");
        }
        var end = firstCandidate + count;
        for (var candidate = firstCandidate; candidate < end; candidate++) {
            var classification = classifications.get(candidate * 4) & 0xff;
            if (classification != 1 && classification != 2) {
                return true;
            }
        }
        return false;
    }

    static List<SubmissionSpan> submissionSpans(boolean[] visibleCommands, int firstCommand, int commandCount) {
        return submissionSpans(visibleCommands, firstCommand, commandCount, SUBMISSION_EQUIVALENT_RANGES);
    }

    static List<SubmissionSpan> submissionSpans(
            boolean[] visibleCommands, int firstCommand, int commandCount, int submissionCost) {
        if (visibleCommands == null
                || firstCommand < 0
                || commandCount < 0
                || firstCommand > visibleCommands.length - commandCount
                || submissionCost < 1) {
            throw new IllegalArgumentException("invalid visibility command span");
        }
        var spans = new PooledSubmissionSpanList();
        updateSubmissionSpans(spans, visibleCommands, firstCommand, commandCount, submissionCost);
        return List.copyOf(spans);
    }

    static int submissionCostRequested(long frameNumber) {
        if (SUBMISSION_COST_CYCLE_FRAMES == 0) {
            return REQUESTED_SUBMISSION_COST;
        }
        var phase = frameNumber / SUBMISSION_COST_CYCLE_FRAMES % 4;
        return phase == 0 || phase == 3 ? REQUESTED_SUBMISSION_COST : SUBMISSION_EQUIVALENT_RANGES;
    }

    static String capabilityFailure(BgfxCapabilities capabilities) {
        if (!capabilities.compute()) {
            return "compute unsupported";
        }
        if (!capabilities.imageReadWrite()) {
            return "image read/write unsupported";
        }
        if (!capabilities.drawIndirect()) {
            return "draw indirect unsupported (required by GPU visibility compaction)";
        }
        if (!capabilities.r32fImageReadWrite()) {
            return "R32F image read/write unsupported";
        }
        if (!capabilities.rgba8ImageWrite()) {
            return "RGBA8 image write unsupported";
        }
        if (!capabilities.blit()) {
            return "classification blit unsupported";
        }
        if (!capabilities.readback()) {
            return "asynchronous texture readback unsupported";
        }
        if (!capabilities.depthStencilSampled()) {
            return capabilities.getDepthStencilName() + " sampling unsupported";
        }
        if (capabilities.maxComputeBindings() < 7) {
            return "seven compute bindings required (reported " + capabilities.maxComputeBindings() + ')';
        }
        return null;
    }

    static int groupsFor(int count, int width) {
        return Math.max(1, (count + width - 1) / width);
    }

    static int dispatchCapacity(int configured) {
        return Math.min(configured, MAX_EXACT_FLOAT_INTEGER - 1);
    }

    static float[] multiply(float[] left, float[] right) {
        if (left == null || right == null || left.length != 16 || right.length != 16) {
            throw new IllegalArgumentException("matrix multiplication requires two 4x4 matrices");
        }
        var result = new float[16];
        multiply(left, right, result);
        return result;
    }

    void rebuild(List<BgfxSceneObject> objects) {
        rebuild(objects, false);
    }

    void rebuildPreservingHistory(List<BgfxSceneObject> objects) {
        rebuild(objects, true);
    }

    void logTopologySummary(String reason) {
        var current = topology;
        if (current == null) {
            log.debug(
                    "{} topology unavailable after {} rebuilds ({} ms): {}",
                    reason,
                    topologyBuilds,
                    topologyBuildNanos / 1_000_000L,
                    fallbackReason == null ? "no compatible opaque topology" : fallbackReason);
            return;
        }
        log.debug(
                "{} topology: commands={} candidates={} groups={} materials={} "
                        + "multiGroupCoverage={}%, poolChunks={}, retainedCpu={} KiB, rebuilds={} ({} ms), "
                        + "deltaUpload={} KiB, commandBvh={}/{} nodes/leaves ({} KiB, {} ms), "
                        + "terrainLod={} pairs @{}px",
                reason,
                current.getCommandCount(),
                current.getCandidateCount(),
                current.getGroups().size(),
                current.getMaterialCount(),
                Math.round(current.getMultiGroupCoverage() * 100.0),
                current.getBindingCount(),
                current.getRetainedCpuBytes() / 1024L,
                topologyBuilds,
                topologyBuildNanos / 1_000_000L,
                topologyDeltaUploadBytes / 1024L,
                current.getCommandHierarchy().getNodeCount(),
                current.getCommandHierarchy().getLeafCount(),
                current.getCommandHierarchy().getRetainedBytes() / 1024L,
                current.getCommandHierarchy().getBuildNanos() / 1_000_000L,
                terrainLodPairs,
                terrainDetailPixels);
    }

    void invalidateTopology(String reason) {
        requireOpen();
        preparedThisFrame = false;
        topologyGeneration++;
        invalidateSubmissionPlan();
        historyAvailable = false;
        latestStatsValid = false;
        var previous = topology;
        topology = null;
        closeQuietly(previous);
        if (isPipelineAvailable()) {
            fallbackReason = reason == null || reason.isBlank() ? "topology invalidated" : reason;
        }
        historyResetReason = reason == null || reason.isBlank() ? "topology invalidated" : reason;
        historyBypassReason = historyResetReason;
    }

    void invalidateHistory(String reason) {
        requireOpen();
        preparedThisFrame = false;
        historyAvailable = false;
        historyUsedThisFrame = false;
        historyResetReason = reason == null || reason.isBlank() ? "history invalidated" : reason;
        historyBypassReason = historyResetReason;
    }

    void skipFrame() {
        preparedThisFrame = false;
    }

    boolean prepare(BgfxTexture targetDepth, Extent extent, BgfxViewAllocator views) {
        requireOpen();
        submissionCost = submissionCostRequested(device.getStats().frameNumber());
        copy16(renderingContext.camera.getViewMatrix(), currentView);
        BgfxMatrices.toBackendProjection(
                renderingContext.camera.getProjectionMatrix(),
                device.getCapabilities().homogeneousDepth(),
                currentProjection);
        updateLodSettings();
        pollStats(extent);
        parallelImmediatePrepared = false;
        preparedThisFrame = false;
        historyUsedThisFrame = false;
        delayedSubmissionPlanThisFrame = false;
        immediateSubmissionPlanThisFrame = false;
        immediateSubmissionFrustum = null;
        currentPlannedCommandRanges = 0;
        currentHierarchyVisitedNodes = 0;
        currentHierarchyTestedCommands = 0;
        if (!isAvailable() || targetDepth == null || extent == null || extent.isZero()) {
            return false;
        }
        try {
            var depthDescriptor = targetDepth.getDescriptor();
            ensurePyramids(depthDescriptor.width(), depthDescriptor.height());
        } catch (RuntimeException | Error e) {
            fallbackReason = "Hi-Z allocation failed: " + describeFailure(e);
            historyAvailable = false;
            warnFallback(fallbackReason, e);
            return false;
        }
        multiply4x4(currentProjection, currentView, currentViewProjection);
        mipCount = 32 - Integer.numberOfLeadingZeros(Math.max(extent.width(), extent.height()));
        historyBypassReason = null;
        if (!historyAvailable) {
            historyBypassReason = historyResetReason == null ? "history unavailable" : historyResetReason;
        } else if (!historyExtent.equals(extent)) {
            historyBypassReason = "render extent changed";
        } else if (historyDepthHandle != Short.toUnsignedInt(targetDepth.getHandle())) {
            historyBypassReason = "depth target changed";
        } else if (!areMatricesEqual(currentProjection, historyProjection)) {
            historyBypassReason = "projection changed";
        } else if (!isCameraHistoryCompatible()) {
        }
        historyUsedThisFrame = historyBypassReason == null;

        var topology = this.topology;
        configData[0] = topology.getCandidateCount();
        configData[1] = topology.getCommandCount();
        configData[2] = historyUsedThisFrame ? 1f : 0f;
        configData[3] = Math.max(0, mipCount - 1);
        var resetView = views.allocate("visibility-reset", Domain.RENDER, false);
        bgfx_set_view_name(resetView, "visibility-reset");
        bgfx_set_uniform(configUniform.getHandle(), configData, 1);
        bgfx_set_compute_dynamic_index_buffer(0, topology.commands.getHandle(), BGFX_ACCESS_READ);
        bgfx_set_compute_dynamic_index_buffer(1, topology.counts.getHandle(), BGFX_ACCESS_READWRITE);
        bgfx_set_compute_indirect_buffer(2, topology.indirect.getHandle(), BGFX_ACCESS_READWRITE);
        BgfxComputeRows.dispatch(resetView, resetProgram, topology.getCommandCount());
        var cullView = views.allocate("visibility-cull", Domain.RENDER, false);
        bgfx_set_view_name(cullView, "visibility-cull");
        bgfx_set_view_transform(cullView, currentView, currentProjection);
        bgfx_set_uniform(configUniform.getHandle(), configData, 1);
        extentData[0] = extent.width();
        extentData[1] = extent.height();
        extentData[2] = 1f / extent.width();
        extentData[3] = 1f / extent.height();
        bgfx_set_uniform(extentUniform.getHandle(), extentData, 1);
        var camera = renderingContext.camera.getPosition();
        var maximumDistanceSquared =
                !renderingContext.camera.isOrtho() && state.settings.isStaticMeshesDistCullingEnabled()
                        ? square((float) state.settings.getStaticMeshesCullingDist())
                        : -1f;
        cameraData[0] = camera.getX();
        cameraData[1] = camera.getY();
        cameraData[2] = camera.getZ();
        cameraData[3] = maximumDistanceSquared;
        bgfx_set_uniform(cameraUniform.getHandle(), cameraData, 1);
        optionsData[0] = enabledCategoryMask();
        optionsData[1] = device.getCapabilities().homogeneousDepth() ? 1f : 0f;
        optionsData[2] = device.getCapabilities().originBottomLeft() ? 1f : 0f;
        optionsData[3] = statsExtent.width();
        bgfx_set_uniform(optionsUniform.getHandle(), optionsData, 1);
        lodData[0] = minimumProjectedPixels;
        lodData[1] = terrainDetailPixels;
        lodData[2] = renderingContext.camera.isOrtho() ? 1f : -impostorDistanceRatio;
        lodData[3] = grassDistanceMultiplier;
        bgfx_set_uniform(lodUniform.getHandle(), lodData, 1);
        delayedSubmissionPlanThisFrame = doesSubmissionPlanMatch(extent, (int) optionsData[0], maximumDistanceSquared);
        if (delayedSubmissionPlanThisFrame) {
            currentPlannedCommandRanges = latestPlannedCommandRanges;
        } else if (groupedRequested && state.settings.isCullingEnabled()) {
            immediateSubmissionFrustum = renderingContext.camera.getFrustum();
            immediateSubmissionPlanThisFrame = true;
        }
        var cameraUnchanged = areMatricesIdentical(currentView, historyView)
                && areMatricesIdentical(currentProjection, historyProjection);
        unchangedCameraFrames = cameraUnchanged ? Math.min(3, unchangedCameraFrames + 1) : 0;
        var auditRefresh = !geometryAuditOutput.isEmpty()
                && geometryAuditSamples < 3
                && device.getStats().frameNumber() - latestStatsSubmissionFrame >= 120L;
        var statsSlot = delayedSubmissionPlanThisFrame && !auditRefresh || unchangedCameraFrames < 3
                ? null
                : acquireStatsSlot();
        bgfx_set_uniform(previousViewProjectionUniform.getHandle(), historyViewProjection, 1);
        bgfx_set_texture(0, depthSampler.getHandle(), pyramids[readablePyramid].getHandle(), DEPTH_SAMPLER_FLAGS);
        bgfx_set_compute_dynamic_vertex_buffer(1, topology.bounds.getHandle(), BGFX_ACCESS_READ);
        bgfx_set_compute_dynamic_vertex_buffer(2, topology.instancesIn.getHandle(), BGFX_ACCESS_READ);
        bgfx_set_compute_dynamic_index_buffer(3, topology.counts.getHandle(), BGFX_ACCESS_READWRITE);
        bgfx_set_compute_dynamic_vertex_buffer(4, topology.instancesOut.getHandle(), BGFX_ACCESS_WRITE);
        bgfx_set_compute_dynamic_index_buffer(5, topology.commands.getHandle(), BGFX_ACCESS_READ);
        bgfx_set_image(6, statsClassifications.getHandle(), 0, BGFX_ACCESS_WRITE, BGFX_TEXTURE_FORMAT_RGBA8);
        BgfxComputeRows.dispatch(cullView, cullProgram, topology.getCandidateCount());
        dispatchOrder(views, "visibility-order", Domain.RENDER, topology);

        var finalizeView = views.allocate("visibility-finalize", Domain.RENDER, false);
        bgfx_set_view_name(finalizeView, "visibility-finalize");
        bgfx_set_uniform(configUniform.getHandle(), configData, 1);
        bgfx_set_compute_dynamic_index_buffer(0, topology.commands.getHandle(), BGFX_ACCESS_READ);
        bgfx_set_compute_dynamic_index_buffer(1, topology.counts.getHandle(), BGFX_ACCESS_READ);
        bgfx_set_compute_indirect_buffer(2, topology.indirect.getHandle(), BGFX_ACCESS_READWRITE);
        BgfxComputeRows.dispatch(finalizeView, finalizeProgram, topology.getCommandCount());
        if (statsSlot != null) {
            var statsView = views.allocate("visibility-stats-readback", Domain.RENDER, false);
            bgfx_blit(
                    statsView,
                    statsSlot.staging.getHandle(),
                    0,
                    0,
                    0,
                    0,
                    statsClassifications.getHandle(),
                    0,
                    0,
                    0,
                    0,
                    statsExtent.width(),
                    statsExtent.height(),
                    1);
            statsSlot.pixels.clear();
            statsSlot.readyFrame =
                    Integer.toUnsignedLong(bgfx_read_texture(statsSlot.staging.getHandle(), statsSlot.pixels, 0, 0));
            statsSlot.candidateCount = topology.getCandidateCount();
            statsSlot.topologyGeneration = topologyGeneration;
            statsSlot.lodSettingsRevision = lodSettingsRevision;
            System.arraycopy(currentView, 0, statsSlot.view, 0, 16);
            System.arraycopy(currentProjection, 0, statsSlot.projection, 0, 16);
            statsSlot.renderExtent = extent;
            statsSlot.categoryMask = (int) optionsData[0];
            statsSlot.maximumDistanceSquared = maximumDistanceSquared;
            statsSlot.inFlight = true;
        }
        preparedThisFrame = true;
        fallbackReason = null;
        return true;
    }

    boolean prepareReflection(
            String label,
            FloatBuffer view,
            FloatBuffer projection,
            float cameraX,
            float cameraY,
            float cameraZ,
            float workingSetDistanceSquared,
            Extent extent,
            BgfxViewAllocator views) {
        requireOpen();
        if (!preparedThisFrame
                || topology == null
                || pyramids == null
                || label == null
                || label.isBlank()
                || view == null
                || projection == null
                || extent == null
                || extent.isZero()
                || views == null
                || !Float.isFinite(workingSetDistanceSquared)
                || workingSetDistanceSquared <= 0f) {
            return false;
        }

        var topology = this.topology;
        configData[0] = topology.getCandidateCount();
        configData[1] = topology.getCommandCount();
        configData[2] = 0f;
        configData[3] = -workingSetDistanceSquared;

        var resetView = views.allocate(label + "-visibility-reset", Domain.REFLECTION, false);
        bgfx_set_view_name(resetView, label + "-visibility-reset");
        bgfx_set_uniform(configUniform.getHandle(), configData, 1);
        bgfx_set_compute_dynamic_index_buffer(0, topology.commands.getHandle(), BGFX_ACCESS_READ);
        bgfx_set_compute_dynamic_index_buffer(1, topology.counts.getHandle(), BGFX_ACCESS_READWRITE);
        bgfx_set_compute_indirect_buffer(2, topology.indirect.getHandle(), BGFX_ACCESS_READWRITE);
        BgfxComputeRows.dispatch(resetView, resetProgram, topology.getCommandCount());

        var cullView = views.allocate(label + "-visibility-cull", Domain.REFLECTION, false);
        bgfx_set_view_name(cullView, label + "-visibility-cull");
        bgfx_set_view_transform(cullView, view, projection);
        bgfx_set_uniform(configUniform.getHandle(), configData, 1);
        extentData[0] = extent.width();
        extentData[1] = extent.height();
        extentData[2] = 1f / extent.width();
        extentData[3] = 1f / extent.height();
        bgfx_set_uniform(extentUniform.getHandle(), extentData, 1);
        var maximumDistanceSquared =
                !renderingContext.camera.isOrtho() && state.settings.isStaticMeshesDistCullingEnabled()
                        ? square((float) state.settings.getStaticMeshesCullingDist())
                        : -1f;
        cameraData[0] = cameraX;
        cameraData[1] = cameraY;
        cameraData[2] = cameraZ;
        cameraData[3] = maximumDistanceSquared;
        bgfx_set_uniform(cameraUniform.getHandle(), cameraData, 1);
        optionsData[0] = enabledCategoryMask();
        optionsData[1] = device.getCapabilities().homogeneousDepth() ? 1f : 0f;
        optionsData[2] = device.getCapabilities().originBottomLeft() ? 1f : 0f;
        optionsData[3] = statsExtent.width();
        bgfx_set_uniform(optionsUniform.getHandle(), optionsData, 1);
        lodData[0] = minimumProjectedPixels;
        lodData[1] = terrainDetailPixels;
        lodData[2] = renderingContext.camera.isOrtho() ? 1f : -impostorDistanceRatio;
        lodData[3] = grassDistanceMultiplier;
        bgfx_set_uniform(lodUniform.getHandle(), lodData, 1);
        bgfx_set_uniform(previousViewProjectionUniform.getHandle(), historyViewProjection, 1);
        bgfx_set_texture(0, depthSampler.getHandle(), pyramids[readablePyramid].getHandle(), DEPTH_SAMPLER_FLAGS);
        bgfx_set_compute_dynamic_vertex_buffer(1, topology.bounds.getHandle(), BGFX_ACCESS_READ);
        bgfx_set_compute_dynamic_vertex_buffer(2, topology.instancesIn.getHandle(), BGFX_ACCESS_READ);
        bgfx_set_compute_dynamic_index_buffer(3, topology.counts.getHandle(), BGFX_ACCESS_READWRITE);
        bgfx_set_compute_dynamic_vertex_buffer(4, topology.instancesOut.getHandle(), BGFX_ACCESS_WRITE);
        bgfx_set_compute_dynamic_index_buffer(5, topology.commands.getHandle(), BGFX_ACCESS_READ);
        bgfx_set_image(6, statsClassifications.getHandle(), 0, BGFX_ACCESS_WRITE, BGFX_TEXTURE_FORMAT_RGBA8);
        BgfxComputeRows.dispatch(cullView, cullProgram, topology.getCandidateCount());
        dispatchOrder(views, label + "-visibility-order", Domain.REFLECTION, topology);

        var finalizeView = views.allocate(label + "-visibility-finalize", Domain.REFLECTION, false);
        bgfx_set_view_name(finalizeView, label + "-visibility-finalize");
        bgfx_set_uniform(configUniform.getHandle(), configData, 1);
        bgfx_set_compute_dynamic_index_buffer(0, topology.commands.getHandle(), BGFX_ACCESS_READ);
        bgfx_set_compute_dynamic_index_buffer(1, topology.counts.getHandle(), BGFX_ACCESS_READ);
        bgfx_set_compute_indirect_buffer(2, topology.indirect.getHandle(), BGFX_ACCESS_READWRITE);
        BgfxComputeRows.dispatch(finalizeView, finalizeProgram, topology.getCommandCount());
        return true;
    }

    void buildHistory(BgfxTexture targetDepth, Extent extent, BgfxViewAllocator views) {
        if (!preparedThisFrame
                || targetDepth == null
                || pyramids == null
                || pyramidExtent.width() < extent.width()
                || pyramidExtent.height() < extent.height()) {
            historyAvailable = false;
            return;
        }
        var writable = 1 - readablePyramid;
        var copyView = views.allocate("visibility-pyramid-copy", Domain.RENDER, false);
        bgfx_set_view_name(copyView, "visibility-pyramid-copy");
        pyramidData[0] = extent.width();
        pyramidData[1] = extent.height();
        pyramidData[2] = 0f;
        pyramidData[3] = 0f;
        bgfx_set_uniform(pyramidUniform.getHandle(), pyramidData, 1);
        bgfx_set_texture(0, depthSampler.getHandle(), targetDepth.getHandle(), DEPTH_SAMPLER_FLAGS);
        bgfx_set_image(1, pyramids[writable].getHandle(), 0, BGFX_ACCESS_WRITE, BGFX_TEXTURE_FORMAT_R32F);
        bgfx_dispatch(
                copyView,
                copyDepthProgram.getHandle(),
                groupsFor(extent.width(), PYRAMID_THREADS),
                groupsFor(extent.height(), PYRAMID_THREADS),
                1,
                BGFX_DISCARD_ALL);

        var sourceWidth = extent.width();
        var sourceHeight = extent.height();
        for (var mip = 1; mip < mipCount; mip++) {
            var destinationWidth = Math.max(1, sourceWidth >> 1);
            var destinationHeight = Math.max(1, sourceHeight >> 1);
            var viewName = PYRAMID_VIEW_NAMES[mip];
            var view = views.allocate(viewName, Domain.RENDER, false);
            bgfx_set_view_name(view, viewName);
            pyramidData[0] = sourceWidth;
            pyramidData[1] = sourceHeight;
            pyramidData[2] = destinationWidth;
            pyramidData[3] = destinationHeight;
            bgfx_set_uniform(pyramidUniform.getHandle(), pyramidData, 1);
            bgfx_set_image(0, pyramids[writable].getHandle(), mip - 1, BGFX_ACCESS_READ, BGFX_TEXTURE_FORMAT_R32F);
            bgfx_set_image(1, pyramids[writable].getHandle(), mip, BGFX_ACCESS_WRITE, BGFX_TEXTURE_FORMAT_R32F);
            bgfx_dispatch(
                    view,
                    downsampleProgram.getHandle(),
                    groupsFor(destinationWidth, PYRAMID_THREADS),
                    groupsFor(destinationHeight, PYRAMID_THREADS),
                    1,
                    BGFX_DISCARD_ALL);
            sourceWidth = destinationWidth;
            sourceHeight = destinationHeight;
        }

        readablePyramid = writable;
        System.arraycopy(currentView, 0, historyView, 0, 16);
        System.arraycopy(currentProjection, 0, historyProjection, 0, 16);
        multiply(historyProjection, historyView, historyViewProjection);
        var position = renderingContext.camera.getPosition();
        var forward = renderingContext.camera.getForwardDir();
        historyCamera[0] = position.getX();
        historyCamera[1] = position.getY();
        historyCamera[2] = position.getZ();
        historyCamera[3] = forward.getX();
        historyCamera[4] = forward.getY();
        historyCamera[5] = forward.getZ();
        historyDepthHandle = Short.toUnsignedInt(targetDepth.getHandle());
        historyExtent = extent;
        historyAvailable = true;
        historyResetReason = null;
    }

    boolean isAvailable() {
        return isPipelineAvailable() && topology != null;
    }

    boolean isExpectedAvailable() {
        return isAvailable();
    }

    int[] getResidualPrimitiveOrdinals(BgfxSceneObject object) {
        return topology == null
                ? Topology.getResidualPrimitiveOrdinals(object, null)
                : topology.getResidualPrimitiveOrdinals(object);
    }

    boolean includes(BgfxSceneObject object) {
        return topology != null && topology.includes(object);
    }

    int withdraw(Collection<BgfxSceneObject> objects) {
        requireOpen();
        if (topology == null || objects.isEmpty()) {
            return 0;
        }
        var withdrawn = topology.withdraw(objects);
        if (withdrawn > 0) {
            invalidateHistory("editor objects left the topology");
        }
        return withdrawn;
    }

    void writeDecorationSlots(BgfxSceneObject pool, int first, int count) {
        requireOpen();
        if (topology == null || count <= 0) {
            return;
        }
        topology.writeDecorationSlots(pool, first, count, decorationMultiplier());
    }

    boolean requiresDepthTarget() {
        return isPipelineAvailable();
    }

    boolean isPreparedThisFrame() {
        return preparedThisFrame;
    }

    boolean isGroupedThisFrame() {
        return preparedThisFrame && groupedRequested;
    }

    List<SubmissionSpan> getMainSubmissionSpans(Group group) {
        if (!preparedThisFrame
                || group == null
                || group.groupIndex() < 0
                || topology == null
                || group.groupIndex() >= topology.getGroups().size()) {
            return null;
        }
        if (delayedSubmissionPlanThisFrame) {
            return group.groupIndex() < latestSubmissionPlan.size()
                    ? latestSubmissionPlan.get(group.groupIndex())
                    : null;
        }
        if (!immediateSubmissionPlanThisFrame || immediateSubmissionFrustum == null) {
            return null;
        }
        var spans = frustumSubmissionSpans(group, immediateSubmissionFrustum);
        for (var spanIndex = 0; spanIndex < spans.size(); spanIndex++) {
            var span = spans.get(spanIndex);
            currentPlannedCommandRanges = Math.addExact(currentPlannedCommandRanges, span.getCommandCount());
        }
        return spans;
    }

    boolean isDelayedSubmissionPlanActive() {
        return delayedSubmissionPlanThisFrame;
    }

    boolean isProjectedSizeInvisible(Bounds3f bounds, Extent extent, boolean grass) {
        if (!preparedThisFrame || bounds == null || extent == null || extent.isZero() || minimumProjectedPixels <= 0f) {
            return false;
        }
        var minimumX = Float.POSITIVE_INFINITY;
        var minimumY = Float.POSITIVE_INFINITY;
        var maximumX = Float.NEGATIVE_INFINITY;
        var maximumY = Float.NEGATIVE_INFINITY;
        for (var corner = 0; corner < 8; corner++) {
            var x = (corner & 1) == 0 ? bounds.minX() : bounds.maxX();
            var y = (corner & 2) == 0 ? bounds.minY() : bounds.maxY();
            var z = (corner & 4) == 0 ? bounds.minZ() : bounds.maxZ();
            var clipW = currentViewProjection[3] * x
                    + currentViewProjection[7] * y
                    + currentViewProjection[11] * z
                    + currentViewProjection[15];
            if (clipW <= EYE_PLANE_EPSILON) {
                return false;
            }
            var clipX = currentViewProjection[0] * x
                    + currentViewProjection[4] * y
                    + currentViewProjection[8] * z
                    + currentViewProjection[12];
            var clipY = currentViewProjection[1] * x
                    + currentViewProjection[5] * y
                    + currentViewProjection[9] * z
                    + currentViewProjection[13];
            var ndcX = clipX / clipW;
            var ndcY = clipY / clipW;
            minimumX = Math.min(minimumX, ndcX);
            minimumY = Math.min(minimumY, ndcY);
            maximumX = Math.max(maximumX, ndcX);
            maximumY = Math.max(maximumY, ndcY);
        }
        var projectedWidth = Math.max(0f, maximumX - minimumX) * 0.5f * extent.width();
        var projectedHeight = Math.max(0f, maximumY - minimumY) * 0.5f * extent.height();
        return Math.max(projectedWidth, projectedHeight)
                < minimumProjectedPixels / (grass ? grassDistanceMultiplier : 1f);
    }

    boolean isProjectedSizeInvisible(
            ParticleSystem particles, ParticleEmitter emitter, Extent extent, float minimumPixels) {
        return preparedThisFrame
                && extent != null
                && !extent.isZero()
                && particles.isProjectedSizeInvisibleThisFrame(
                        emitter, currentViewProjection, extent.width(), extent.height(), minimumPixels);
    }

    float[] getPreparedViewProjection() {
        return preparedThisFrame ? currentViewProjection : null;
    }

    long getSubmissionPlanRevision() {
        return submissionPlanRevision;
    }

    boolean isMainSubmissionGroupActive(Group group) {
        if (!delayedSubmissionPlanThisFrame
                || group == null
                || group.groupIndex() < 0
                || group.groupIndex() >= latestSubmissionPlan.size()) {
            return true;
        }
        return !latestSubmissionPlan.get(group.groupIndex()).isEmpty();
    }

    void updateMaterialTable(
            float elapsedSeconds,
            boolean texturesEnabled,
            int shadowVariant,
            boolean orthographic,
            LongToDoubleFunction semanticDepthBias) {
        if (topology == null) {
            return;
        }
        topology.updateMaterialTable(elapsedSeconds, texturesEnabled, shadowVariant, orthographic, semanticDepthBias);
    }

    boolean isHistoryUsedThisFrame() {
        return historyUsedThisFrame;
    }

    boolean isRequested() {
        return requested;
    }

    boolean isGroupedRequested() {
        return groupedRequested;
    }

    String getReason() {
        if (!requested) {
            return "disabled by profile";
        }
        if (fallbackReason != null) {
            return fallbackReason;
        }
        if (topology == null) {
            return "no compatible opaque topology";
        }
        return preparedThisFrame
                ? historyUsedThisFrame
                        ? "active with previous-frame Hi-Z"
                                + (delayedSubmissionPlanThisFrame
                                        ? ". Stable-view Metal plan " + currentPlannedCommandRanges + '/'
                                                + topology.getCommandCount() + " ranges"
                                        : immediateSubmissionPlanThisFrame
                                                ? ". Immediate frustum Metal plan "
                                                        + currentPlannedCommandRanges
                                                        + '/'
                                                        + topology.getCommandCount()
                                                        + " ranges"
                                                        + commandHierarchyReason()
                                                : "")
                                + terrainLodReason()
                        : "active with conservative frustum-only history bypass"
                                + (immediateSubmissionPlanThisFrame
                                        ? ". Immediate Metal plan "
                                                + currentPlannedCommandRanges
                                                + '/'
                                                + topology.getCommandCount()
                                                + " ranges"
                                                + commandHierarchyReason()
                                        : "")
                                + (historyBypassReason == null ? "" : " (" + historyBypassReason + ')')
                                + terrainLodReason()
                : "not prepared this frame";
    }

    int getOverflowFallbacks() {
        return overflowFallbacks;
    }

    double getMultiGroupCoverage() {
        return topology == null ? 0.0 : topology.getMultiGroupCoverage();
    }

    long getGroupedSavedSubmits() {
        if (topology == null || !groupedRequested) {
            return 0L;
        }
        return Math.max(0, topology.getCommandCount() - topology.getGroups().size());
    }

    int getCommandCount() {
        return topology == null ? 0 : topology.getCommandCount();
    }

    int getGroupCount() {
        return topology == null ? 0 : topology.getGroups().size();
    }

    int getCommandCategoryCount(int category) {
        return topology == null ? 0 : topology.getCommandCategoryCount(category);
    }

    int getStatsAgeFrames() {
        if (!latestStatsValid || latestStatsSubmissionFrame == Long.MIN_VALUE) {
            return -1;
        }
        var age = Integer.toUnsignedLong((int) (device.getStats().frameNumber() - latestStatsSubmissionFrame));
        return age > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) age;
    }

    long getTopologyDeltaUploadBytes() {
        return topologyDeltaUploadBytes;
    }

    long getTopologyFullResourceBuilds() {
        return topologyFullResourceBuilds;
    }

    long getTopologyDeltaUpdates() {
        return topologyDeltaUpdates;
    }

    long getTopologyBuilds() {
        return topologyBuilds;
    }

    long getTopologyBuildNanos() {
        return topologyBuildNanos;
    }

    Topology getTopology() {
        if (!preparedThisFrame || topology == null) {
            throw new IllegalStateException("GPU visibility topology is not prepared for this frame");
        }
        return topology;
    }

    long getTopologyGeneration() {
        return topologyGeneration;
    }

    Visibility getDiagnostics(int traversedObjects) {
        if (topology == null) {
            return new Visibility(traversedObjects, 0, 0, 0, 0, 0, overflowFallbacks, true, false, false);
        }
        return new Visibility(
                traversedObjects,
                topology.getCandidateCount(),
                latestStatsValid ? latestFrustumCulled : 0,
                latestStatsValid ? latestDistanceCulled : 0,
                latestStatsValid ? latestOcclusionCulled : 0,
                latestStatsValid ? latestCompacted : 0,
                overflowFallbacks,
                !preparedThisFrame,
                historyUsedThisFrame,
                latestStatsValid);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (verifyVisibilityWorkers) {
            log.info(
                    "Verified {} group queries of the visibility workers against serial traversal",
                    verifiedParallelGroups);
        }
        RuntimeException failure = null;
        failure = closeAndMerge(topology, failure);
        topology = null;
        if (pyramids != null) {
            failure = closeAndMerge(pyramids[1], failure);
            failure = closeAndMerge(pyramids[0], failure);
            pyramids = null;
        }
        failure = closeAndMerge(statsClassifications, failure);
        statsClassifications = null;
        if (statsSlots != null) {
            for (var index = statsSlots.length - 1; index >= 0; index--) {
                failure = closeAndMerge(statsSlots[index], failure);
            }
            statsSlots = null;
        }
        statsExtent = new Extent(0, 0);
        failure = closeAndMerge(pyramidUniform, failure);
        failure = closeAndMerge(previousViewProjectionUniform, failure);
        failure = closeAndMerge(lodUniform, failure);
        failure = closeAndMerge(optionsUniform, failure);
        failure = closeAndMerge(cameraUniform, failure);
        failure = closeAndMerge(extentUniform, failure);
        failure = closeAndMerge(configUniform, failure);
        failure = closeAndMerge(depthSampler, failure);
        failure = closeAndMerge(finalizeProgram, failure);
        failure = closeAndMerge(orderMoveProgram, failure);
        failure = closeAndMerge(orderCountProgram, failure);
        failure = closeAndMerge(cullProgram, failure);
        failure = closeAndMerge(resetProgram, failure);
        failure = closeAndMerge(downsampleProgram, failure);
        failure = closeAndMerge(copyDepthProgram, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private void dispatchOrder(BgfxViewAllocator views, String name, Domain domain, Topology topology) {
        if (topology.getOrderBlockCount() == 0) {
            return;
        }
        var view = views.allocate(name, domain, false);
        bgfx_set_view_name(view, name);
        BgfxVisibilityOrder.dispatch(
                view,
                orderCountProgram,
                orderMoveProgram,
                configUniform,
                orderData,
                topology.orderBuffers,
                topology.getCandidateCount(),
                topology.getCommandCount(),
                topology.getOrderBlockCount());
    }

    private void createPipeline() {
        BgfxProgram createdCopy = null;
        BgfxProgram createdDownsample = null;
        BgfxProgram createdReset = null;
        BgfxProgram createdCull = null;
        BgfxProgram createdOrderCount = null;
        BgfxProgram createdOrderMove = null;
        BgfxProgram createdFinalize = null;
        BgfxUniform createdSampler = null;
        BgfxUniform createdConfig = null;
        BgfxUniform createdExtent = null;
        BgfxUniform createdCamera = null;
        BgfxUniform createdOptions = null;
        BgfxUniform createdLod = null;
        BgfxUniform createdPrevious = null;
        BgfxUniform createdPyramid = null;
        try {
            var resources = device.getResources();
            var capabilities = device.getCapabilities();
            createdCopy = BgfxProgram.compute(
                    resources,
                    capabilities,
                    "visibility/copy-depth",
                    BgfxShaderResources.load(capabilities, "cs_visibility_copy_depth"));
            createdDownsample = BgfxProgram.compute(
                    resources,
                    capabilities,
                    "visibility/downsample",
                    BgfxShaderResources.load(capabilities, "cs_visibility_downsample"));
            createdReset = BgfxProgram.compute(
                    resources,
                    capabilities,
                    "visibility/reset",
                    BgfxShaderResources.load(capabilities, "cs_visibility_reset"));
            createdCull = BgfxProgram.compute(
                    resources,
                    capabilities,
                    "visibility/cull",
                    BgfxShaderResources.load(capabilities, "cs_visibility_cull"));
            createdOrderCount = BgfxProgram.compute(
                    resources,
                    capabilities,
                    "visibility/order-count",
                    BgfxShaderResources.load(capabilities, "cs_visibility_order_count"));
            createdOrderMove = BgfxProgram.compute(
                    resources,
                    capabilities,
                    "visibility/order-move",
                    BgfxShaderResources.load(capabilities, "cs_visibility_order_move"));
            createdFinalize = BgfxProgram.compute(
                    resources,
                    capabilities,
                    "visibility/finalize",
                    BgfxShaderResources.load(capabilities, "cs_visibility_finalize"));
            createdSampler = BgfxUniform.create(resources, "s_visibilityDepth", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            createdConfig = BgfxUniform.create(resources, "u_visibilityConfig", BGFX_UNIFORM_TYPE_VEC4, 1);
            createdExtent = BgfxUniform.create(resources, "u_visibilityExtent", BGFX_UNIFORM_TYPE_VEC4, 1);
            createdCamera = BgfxUniform.create(resources, "u_visibilityCamera", BGFX_UNIFORM_TYPE_VEC4, 1);
            createdOptions = BgfxUniform.create(resources, "u_visibilityOptions", BGFX_UNIFORM_TYPE_VEC4, 1);
            createdLod = BgfxUniform.create(resources, "u_visibilityLod", BGFX_UNIFORM_TYPE_VEC4, 1);
            createdPrevious = BgfxUniform.create(resources, "u_visibilityPreviousViewProj", BGFX_UNIFORM_TYPE_MAT4, 1);
            createdPyramid = BgfxUniform.create(resources, "u_visibilityPyramid", BGFX_UNIFORM_TYPE_VEC4, 1);
        } catch (RuntimeException | Error e) {
            closeAfterFailure(createdPyramid, e);
            closeAfterFailure(createdPrevious, e);
            closeAfterFailure(createdLod, e);
            closeAfterFailure(createdOptions, e);
            closeAfterFailure(createdCamera, e);
            closeAfterFailure(createdExtent, e);
            closeAfterFailure(createdConfig, e);
            closeAfterFailure(createdSampler, e);
            closeAfterFailure(createdFinalize, e);
            closeAfterFailure(createdOrderMove, e);
            closeAfterFailure(createdOrderCount, e);
            closeAfterFailure(createdCull, e);
            closeAfterFailure(createdReset, e);
            closeAfterFailure(createdDownsample, e);
            closeAfterFailure(createdCopy, e);
            fallbackReason = "visibility shader/resource creation failed: " + describeFailure(e);
            warnFallback(fallbackReason, e);
            return;
        }
        copyDepthProgram = createdCopy;
        downsampleProgram = createdDownsample;
        resetProgram = createdReset;
        cullProgram = createdCull;
        orderCountProgram = createdOrderCount;
        orderMoveProgram = createdOrderMove;
        finalizeProgram = createdFinalize;
        depthSampler = createdSampler;
        configUniform = createdConfig;
        extentUniform = createdExtent;
        cameraUniform = createdCamera;
        optionsUniform = createdOptions;
        lodUniform = createdLod;
        previousViewProjectionUniform = createdPrevious;
        pyramidUniform = createdPyramid;
    }

    private void rebuild(List<BgfxSceneObject> objects, boolean preserveHistory) {
        requireOpen();
        preparedThisFrame = false;
        topologyGeneration++;
        invalidateSubmissionPlan();
        if (!preserveHistory) {
            historyAvailable = false;
            historyResetReason = "opaque topology replaced";
        }
        latestStatsValid = false;
        if (!isPipelineAvailable()) {
            return;
        }
        var previous = topology;
        Topology next = null;
        var topologyStarted = System.nanoTime();
        try {
            next = buildTopology(objects, previous);
            ensureStatsResources(next.getCandidateCount());
        } catch (NoCompatibleTopology e) {
            if (next != previous) {
                closeQuietly(next);
            }
            next = null;
            fallbackReason = e.getMessage();
        } catch (CapacityOverflow e) {
            if (next != previous) {
                closeQuietly(next);
            }
            next = null;
            overflowFallbacks++;
            fallbackReason = e.getMessage();
            warnFallback(fallbackReason, null);
        } catch (RuntimeException | Error e) {
            if (next != previous) {
                closeQuietly(next);
            }
            next = null;
            fallbackReason = "visibility topology build failed: " + describeFailure(e);
            warnFallback(fallbackReason, e);
        } finally {
            topologyBuilds = Math.addExact(topologyBuilds, 1L);
            topologyBuildNanos = Math.addExact(topologyBuildNanos, Math.max(0L, System.nanoTime() - topologyStarted));
        }
        topology = next;
        if (previous != next) {
            closeQuietly(previous);
        }
        if (next != null) {
            fallbackReason = null;
            log.debug(
                    "Topology: commands={} candidates={} groups={} materials={} "
                            + "multiGroupCoverage={}%, poolChunks={}, retainedCpu={} KiB, deltaUpload={} KiB, "
                            + "commandBvh={}/{} nodes/leaves ({} KiB, {} ms)",
                    next.getCommandCount(),
                    next.getCandidateCount(),
                    next.getGroups().size(),
                    next.getMaterialCount(),
                    Math.round(next.getMultiGroupCoverage() * 100.0),
                    next.getBindingCount(),
                    next.getRetainedCpuBytes() / 1024L,
                    next.getLastDeltaUploadBytes() / 1024L,
                    next.getCommandHierarchy().getNodeCount(),
                    next.getCommandHierarchy().getLeafCount(),
                    next.getCommandHierarchy().getRetainedBytes() / 1024L,
                    next.getCommandHierarchy().getBuildNanos() / 1_000_000L);
        }
    }

    private Topology buildTopology(List<BgfxSceneObject> objects, Topology reusable) {
        var seeds = new ArrayList<Seed>();
        var materialRecords = new ArrayList<MaterialRecord>();
        var materialRecordIds = new HashMap<MaterialRecordKey, Integer>();
        var terrainDetailOwners = new HashSet<String>();
        var terrainDetails = new HashSet<String>();
        var terrainProxyDetails = new HashSet<String>();
        for (var object : objects) {
            if (object.getGeometry().getKind() != Kind.TERRAIN) {
                continue;
            }
            if (BgfxSceneUploadSink.isTerrainProxyOwner(object.getOwner())) {
                terrainProxyDetails.add(object.getTerrainPair());
            } else {
                terrainDetailOwners.add(object.getOwner());
                terrainDetails.add(object.getTerrainPair());
            }
        }
        terrainProxyDetails.retainAll(terrainDetails);
        terrainLodPairs = terrainProxyDetails.size();
        for (var object : objects) {
            if (object.isMoving() || object.getNativeStaticMeshDraw() != null || object.isZoneLimited()) {
                continue;
            }
            var terrainLodFlags = 0;
            if (object.getGeometry().getKind() == Kind.TERRAIN) {
                if (BgfxSceneUploadSink.isTerrainProxyOwner(object.getOwner())) {
                    if (terrainProxyDetails.contains(object.getTerrainPair())) {
                        terrainLodFlags = FLAG_TERRAIN_PROXY;
                    } else if (terrainDetailOwners.contains(
                            BgfxSceneUploadSink.terrainDetailOwner(object.getOwner()))) {
                        continue;
                    }
                } else if (terrainProxyDetails.contains(object.getTerrainPair())) {
                    terrainLodFlags = FLAG_TERRAIN_DETAIL;
                }
            }
            var primitives = object.getGeometry().getPrimitives();
            for (var ordinal = 0; ordinal < primitives.size(); ordinal++) {
                var primitive = primitives.get(ordinal);
                var material = primitive.entityMaterial();
                if (material != null
                        && (material.isTranslucent()
                                || material.getProgramFamily() == ProgramFamily.WATER
                                || material.getBlendMode() == BlendMode.INVISIBLE
                                || !material.hasDepthWrite())) {
                    continue;
                }
                var family = familyOf(primitive);
                if (!family.isGpuDrivenEligible()) {
                    continue;
                }
                var materialRecord = -1;
                if (material != null) {
                    var recordKey = new MaterialRecordKey(object, material);
                    var existing = materialRecordIds.get(recordKey);
                    if (existing == null) {
                        materialRecord = materialRecords.size();
                        if (materialRecord >= (MAX_EXACT_FLOAT_INTEGER >>> MATERIAL_RECORD_SHIFT)) {
                            throw new CapacityOverflow("GPU material-table capacity exceeded: " + (materialRecord + 1));
                        }
                        materialRecordIds.put(recordKey, materialRecord);
                        materialRecords.add(new MaterialRecord(object, material));
                    } else {
                        materialRecord = existing;
                    }
                }
                seeds.add(new Seed(object, ordinal, family, materialRecord, terrainLodFlags));
            }
        }
        if (seeds.isEmpty()) {
            throw new NoCompatibleTopology("no compatible opaque topology is loaded");
        }

        long candidateCountLong = 0L;
        for (var seed : seeds) {
            candidateCountLong = Math.addExact(candidateCountLong, seed.instanceCount());
        }
        if (candidateCountLong > maximumCandidates || candidateCountLong >= MAX_EXACT_FLOAT_INTEGER) {
            throw new CapacityOverflow(
                    "visibility candidate capacity exceeded: " + candidateCountLong + " > " + maximumCandidates);
        }
        var candidateCount = Math.toIntExact(candidateCountLong);

        var reusableGroupBuilders = new LinkedHashMap<GroupKey, GroupBuilder>();
        var groupBuilders = new ArrayList<GroupBuilder>();
        GroupBuilder orderedTail = null;
        for (var seed : seeds) {
            if (isOrderSensitive(seed.primitive())) {
                if (orderedTail == null || !isSameOrderedGpuBatch(orderedTail.first, seed)) {
                    orderedTail = new GroupBuilder(seed);
                    groupBuilders.add(orderedTail);
                }
                orderedTail.seeds.add(seed);
                continue;
            }
            var key = new GroupKey(seed);
            var builder = reusableGroupBuilders.get(key);
            if (builder == null) {
                builder = new GroupBuilder(seed);
                reusableGroupBuilders.put(key, builder);
                groupBuilders.add(builder);
            }
            builder.seeds.add(seed);
        }

        var flattened = new ArrayList<Command>(seeds.size());
        var groups = new ArrayList<Group>(groupBuilders.size());
        var candidateOffset = 0;
        var commandIndex = 0;
        var multiGroupedCommands = 0;
        for (var builder : groupBuilders) {
            var rangeBuilders = drawRanges(builder);
            var firstCommand = commandIndex;
            for (var range : rangeBuilders) {
                var seed = range.first;
                var primitive = seed.primitive();
                var geometry = seed.object.getGeometry();
                var command = new Command(
                        seed.object,
                        seed.primitiveOrdinal,
                        primitive,
                        seed.family,
                        commandIndex,
                        candidateOffset,
                        range.totalInstances(),
                        geometry.getIndirectVertexOffset(),
                        geometry.getIndirectIndexOffset() + primitive.indexOffset(),
                        range.worldBounds(),
                        List.copyOf(range.seeds));
                flattened.add(command);
                candidateOffset += command.maxInstances();
                commandIndex++;
            }
            var count = commandIndex - firstCommand;
            if (count > 1) {
                multiGroupedCommands += count;
            }
            groups.add(new Group(
                    groups.size(),
                    firstCommand,
                    count,
                    builder.first.object.getGeometry().getIndirectBinding(),
                    builder.first.family,
                    builder.first.object,
                    builder.first.primitiveOrdinal,
                    builder.first.primitive(),
                    isOrderSensitive(builder.first.primitive()),
                    builder.totalInstances(),
                    builder.totalTriangles()));
        }
        if (flattened.size() > maximumCommands || flattened.size() >= MAX_EXACT_FLOAT_INTEGER) {
            throw new CapacityOverflow("visibility command capacity exceeded after exact-range coalescing: "
                    + flattened.size() + " > " + maximumCommands);
        }

        var boundsData = ByteBuffer.allocateDirect(
                        Math.multiplyExact(candidateCount, BgfxSceneVertexLayouts.VISIBILITY_BOUNDS.getStride()))
                .order(ByteOrder.nativeOrder());
        var instanceData = ByteBuffer.allocateDirect(
                        Math.multiplyExact(candidateCount, BgfxSceneVertexLayouts.INSTANCE.getStride()))
                .order(ByteOrder.nativeOrder());
        var commandData = ByteBuffer.allocateDirect(Math.multiplyExact(
                        commandMetadataElements(flattened.size(), materialRecords.size()), Integer.BYTES))
                .order(ByteOrder.nativeOrder());
        var identity = identityInstance();
        for (var command : flattened) {
            var primitive = command.primitive();
            commandData.putInt(primitive.indexCount());
            commandData.putInt(command.indexOffset());
            commandData.putInt(command.vertexOffset());
            commandData.putInt(command.firstInstance());
            for (var seed : command.seeds()) {
                var seedPrimitive = seed.primitive();
                var flags = candidateFlags(seedPrimitive, seed.topologyFlags(), seed.materialRecord());
                if (seed.object().getGeometry().getKind().isInstanced()) {
                    var multiplier = decorationMultiplier();
                    for (var instance = 0; instance < seed.instanceCount(); instance++) {
                        putCandidate(
                                boundsData,
                                instanceData,
                                seed.object(),
                                seedPrimitive,
                                instance,
                                command.commandIndex(),
                                flags,
                                multiplier);
                    }
                } else {
                    putBounds(
                            boundsData,
                            seed.object().getPrimitiveBounds(seed.primitiveOrdinal()),
                            command.commandIndex(),
                            flags,
                            seed.object().getStaticMeshLod(),
                            0f);
                    for (var value : identity) {
                        instanceData.putFloat(value);
                    }
                }
            }
        }
        boundsData.flip();
        instanceData.flip();
        commandData.flip();

        var immutableCommands = List.copyOf(flattened);
        var immutableGroups = List.copyOf(groups);
        var coverage = seeds.isEmpty() ? 0.0 : multiGroupedCommands / (double) seeds.size();
        if (reusable != null
                && reusable.canFit(candidateCount, flattened.size(), Math.max(1, materialRecords.size()))) {
            var uploaded = reusable.update(
                    immutableCommands,
                    immutableGroups,
                    List.copyOf(materialRecords),
                    boundsData,
                    instanceData,
                    commandData,
                    candidateCount,
                    coverage);
            topologyDeltaUploadBytes = Math.addExact(topologyDeltaUploadBytes, uploaded);
            topologyDeltaUpdates = Math.addExact(topologyDeltaUpdates, 1L);
            return reusable;
        }
        var created = Topology.create(
                device,
                immutableCommands,
                immutableGroups,
                List.copyOf(materialRecords),
                boundsData,
                instanceData,
                commandData,
                candidateCount,
                coverage,
                capacityFor(candidateCount, maximumCandidates),
                capacityFor(flattened.size(), maximumCommands),
                capacityFor(Math.max(1, materialRecords.size()), maximumCommands),
                commandHierarchyLeafCommands);
        topologyDeltaUploadBytes = Math.addExact(topologyDeltaUploadBytes, created.getLastDeltaUploadBytes());
        topologyFullResourceBuilds = Math.addExact(topologyFullResourceBuilds, 1L);
        return created;
    }

    private float decorationMultiplier() {
        var multiplier = state.settings.getDecorationFadeMultiplier();
        return multiplier > 0f && Float.isFinite(multiplier) ? multiplier : 0f;
    }

    private void updateLodSettings() {
        var detail = state.settings.getTerrainDetailThresholdPixels();
        var impostors = state.settings.getImpostorDistanceRatio();
        var grass = Math.max(1f, state.settings.getGrassDistanceMultiplier());
        if (detail != terrainDetailPixels || impostors != impostorDistanceRatio || grass != grassDistanceMultiplier) {
            terrainDetailPixels = detail;
            impostorDistanceRatio = impostors;
            grassDistanceMultiplier = grass;
            lodSettingsRevision++;
            invalidateHistory("Level of detail settings changed");
            invalidateSubmissionPlan();
        }
    }

    private void ensureStatsResources(int candidateCount) {
        if (candidateCount < 1) {
            throw new IllegalArgumentException("visibility stats require at least one candidate");
        }
        var requested = statisticsCapacity(
                candidateCount, device.getCapabilities().maxTextureSize(), maximumCandidates, statsExtent);
        if (requested.equals(statsExtent) && statsClassifications != null && statsSlots != null) {
            return;
        }

        BgfxTexture nextClassifications = null;
        StatsSlot[] nextSlots = null;
        try {
            nextClassifications = BgfxTexture.create2D(
                    device.getResources(),
                    "visibility/classifications",
                    requested.width(),
                    requested.height(),
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA8,
                    BGFX_TEXTURE_COMPUTE_WRITE,
                    null);
            nextSlots = new StatsSlot[STATS_SLOT_COUNT];
            for (var index = 0; index < nextSlots.length; index++) {
                var staging = BgfxTexture.create2D(
                        device.getResources(),
                        "visibility/classifications-staging-" + index,
                        requested.width(),
                        requested.height(),
                        false,
                        1,
                        BGFX_TEXTURE_FORMAT_RGBA8,
                        BGFX_TEXTURE_READ_BACK | BGFX_TEXTURE_BLIT_DST,
                        null);
                nextSlots[index] = new StatsSlot(staging, requested);
            }
        } catch (RuntimeException | Error e) {
            closeAfterFailure(nextSlots, e);
            closeAfterFailure(nextClassifications, e);
            throw e;
        }
        var previousClassifications = statsClassifications;
        var previousSlots = statsSlots;
        statsClassifications = nextClassifications;
        statsSlots = nextSlots;
        statsExtent = requested;
        nextStatsSlot = 0;
        lastStatsSubmissionFrame = Long.MIN_VALUE;
        latestStatsValid = false;
        invalidateSubmissionPlan();
        closeQuietly(previousSlots);
        closeQuietly(previousClassifications);
    }

    private void ensurePyramids(int width, int height) {
        if (pyramids != null && pyramidExtent.width() >= width && pyramidExtent.height() >= height) {
            return;
        }
        var capacity = new Extent(Math.max(pyramidExtent.width(), width), Math.max(pyramidExtent.height(), height));
        BgfxTexture first = null;
        BgfxTexture second = null;
        try {
            first = BgfxTexture.create2D(
                    device.getResources(),
                    "visibility/hi-z-a",
                    capacity.width(),
                    capacity.height(),
                    true,
                    1,
                    BGFX_TEXTURE_FORMAT_R32F,
                    PYRAMID_FLAGS,
                    null);
            second = BgfxTexture.create2D(
                    device.getResources(),
                    "visibility/hi-z-b",
                    capacity.width(),
                    capacity.height(),
                    true,
                    1,
                    BGFX_TEXTURE_FORMAT_R32F,
                    PYRAMID_FLAGS,
                    null);
        } catch (RuntimeException | Error e) {
            closeAfterFailure(second, e);
            closeAfterFailure(first, e);
            throw e;
        }
        var previous = pyramids;
        pyramids = new BgfxTexture[] {first, second};
        pyramidExtent = capacity;
        readablePyramid = 0;
        historyAvailable = false;
        historyExtent = new Extent(0, 0);
        historyDepthHandle = -1;
        closeQuietly(previous);
    }

    private String terrainLodReason() {
        return terrainLodPairs == 0 || terrainDetailPixels <= 0f
                ? ""
                : ". Terrain LOD " + terrainLodPairs + " pairs @" + terrainDetailPixels + "px";
    }

    private String commandHierarchyReason() {
        return ". BVH " + currentHierarchyVisitedNodes + " nodes / " + currentHierarchyTestedCommands
                + " command AABBs";
    }

    private void pollStats(Extent extent) {
        if (statsSlots == null) {
            return;
        }
        var frame = device.getStats().frameNumber();
        for (var slot : statsSlots) {
            if (!slot.inFlight || !hasReached(frame, slot.readyFrame)) {
                continue;
            }
            slot.inFlight = false;
            if (topology == null
                    || slot.topologyGeneration != topologyGeneration
                    || slot.lodSettingsRevision != lodSettingsRevision
                    || slot.candidateCount != topology.getCandidateCount()) {
                continue;
            }
            var maximumDistanceSquared =
                    !renderingContext.camera.isOrtho() && state.settings.isStaticMeshesDistCullingEnabled()
                            ? square((float) state.settings.getStaticMeshesCullingDist())
                            : -1f;
            if ((geometryAuditOutput.isEmpty() || geometryAuditSamples >= 3)
                    && (!slot.renderExtent.equals(extent)
                            || slot.categoryMask != enabledCategoryMask()
                            || Float.floatToIntBits(slot.maximumDistanceSquared)
                                    != Float.floatToIntBits(maximumDistanceSquared)
                            || !areMatricesIdentical(slot.view, currentView)
                            || !areMatricesIdentical(slot.projection, currentProjection))) {
                continue;
            }
            var frustum = 0;
            var distance = 0;
            var occlusion = 0;
            var compacted = 0;
            var invalid = 0;
            for (var candidate = 0; candidate < slot.candidateCount; candidate++) {
                switch (slot.pixels.get(candidate * 4) & 0xff) {
                    case 1 -> frustum++;
                    case 2 -> distance++;
                    case 3 -> occlusion++;
                    case 4 -> compacted++;
                    default -> invalid++;
                }
            }
            if (invalid == 0 && (long) frustum + distance + occlusion + compacted == slot.candidateCount) {
                latestFrustumCulled = frustum;
                latestDistanceCulled = distance;
                latestOcclusionCulled = occlusion;
                latestCompacted = compacted;
                latestStatsValid = true;
                latestStatsSubmissionFrame = slot.submissionFrame;
                latestSubmissionPlan = updateSubmissionPlan(slot.pixels, topology);
                latestSubmissionCost = submissionCost;
                submissionPlanRevision++;
                latestPlannedCommandRanges = plannedCommandRanges(latestSubmissionPlan);
                System.arraycopy(slot.view, 0, latestSubmissionView, 0, 16);
                System.arraycopy(slot.projection, 0, latestSubmissionProjection, 0, 16);
                latestSubmissionExtent = slot.renderExtent;
                latestSubmissionCategoryMask = slot.categoryMask;
                latestSubmissionMaximumDistanceSquared = slot.maximumDistanceSquared;
                latestSubmissionTopologyGeneration = slot.topologyGeneration;
                if (!geometryAuditOutput.isEmpty() && geometryAuditSamples < 3) {
                    writeGeometryAudit(slot);
                }
            } else {
                latestStatsValid = false;
                invalidateSubmissionPlan();
                if (!inconsistentClassificationWarned) {
                    inconsistentClassificationWarned = true;
                    log.warn(
                            "Discarding inconsistent asynchronous visibility "
                                    + "classification: candidates={}, current={}, invalid={} "
                                    + "(further occurrences suppressed)",
                            slot.candidateCount,
                            topology == null ? 0 : topology.getCandidateCount(),
                            invalid);
                }
            }
        }
    }

    private void writeGeometryAudit(StatsSlot slot) {
        var prefix = Path.of(geometryAuditOutput).toAbsolutePath();
        var output = prefix.resolveSibling(prefix.getFileName() + "." + geometryAuditSamples + ".tsv");
        try {
            Files.createDirectories(output.getParent());
            try (var writer = Files.newBufferedWriter(output)) {
                writer.write("# submission_frame="
                        + slot.submissionFrame
                        + " render="
                        + slot.renderExtent.width()
                        + "x"
                        + slot.renderExtent.height()
                        + "\n");
                writer.write("command\tfamily\tgeometry\trepresentative_owner\tindex_count\tvisible_instances"
                        + "\toccluded_instances\tpotential_instances\tvisible_triangles\n");
                for (var command : topology.getCommandsList()) {
                    var visible = 0;
                    var occluded = 0;
                    var limit = command.firstInstance() + command.maxInstances();
                    for (var candidate = command.firstInstance(); candidate < limit; candidate++) {
                        var classification = slot.pixels.get(candidate * 4) & 0xff;
                        if (classification == 4) {
                            visible++;
                        } else if (classification == 3) {
                            occluded++;
                        }
                    }
                    if (visible == 0 && occluded == 0) {
                        continue;
                    }
                    var indices = command.primitive().indexCount();
                    writer.write(command.commandIndex()
                            + "\t"
                            + command.family()
                            + "\t"
                            + command.object()
                                    .getGeometry()
                                    .getLabel()
                                    .replace('\t', ' ')
                                    .replace('\n', ' ')
                            + "\t"
                            + command.object().getOwner().replace('\t', ' ').replace('\n', ' ')
                            + "\t"
                            + indices
                            + "\t"
                            + visible
                            + "\t"
                            + occluded
                            + "\t"
                            + command.maxInstances()
                            + "\t"
                            + (long) visible * (indices / 3)
                            + "\n");
                }
            }
            geometryAuditSamples++;
            log.info("Geometry audit: {}", output);
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot write geometry workload audit " + output, failure);
        }
    }

    private boolean doesSubmissionPlanMatch(Extent extent, int categoryMask, float maximumDistanceSquared) {
        return latestStatsValid
                && latestSubmissionCost == submissionCost
                && latestSubmissionTopologyGeneration == topologyGeneration
                && latestSubmissionPlan.size() == topology.getGroups().size()
                && latestSubmissionExtent.equals(extent)
                && latestSubmissionCategoryMask == categoryMask
                && Float.floatToIntBits(latestSubmissionMaximumDistanceSquared)
                        == Float.floatToIntBits(maximumDistanceSquared)
                && areMatricesIdentical(latestSubmissionView, currentView)
                && areMatricesIdentical(latestSubmissionProjection, currentProjection);
    }

    private void invalidateSubmissionPlan() {
        parallelImmediatePrepared = false;
        latestSubmissionPlan = List.of();
        latestSubmissionTopologyGeneration = Long.MIN_VALUE;
        latestPlannedCommandRanges = 0;
        delayedSubmissionPlanThisFrame = false;
        immediateSubmissionPlanThisFrame = false;
        immediateSubmissionFrustum = null;
        immediateSubmissionSpans.clear();
        immediateSubmissionSpanPoolUsed = 0;
        currentPlannedCommandRanges = 0;
    }

    private List<SubmissionSpan> frustumSubmissionSpans(Group group, Frustum frustum) {
        immediateSubmissionSpans.clear();
        immediateSubmissionSpanPoolUsed = 0;
        var first = group.firstCommand();
        var hierarchy = topology.getCommandHierarchy();
        ensureImmediateHierarchyScratch(hierarchy);
        var parallel = prepareParallelImmediatePlan(hierarchy, frustum);
        var visibleCount = parallel
                ? parallelVisibleCounts[group.groupIndex()]
                : hierarchy.collectVisibleCommands(
                        group.groupIndex(), frustum, immediateVisibleCommands, immediateHierarchyStack);
        if (parallel && verifyVisibilityWorkers) {
            verifyParallelGroup(group, hierarchy, frustum, visibleCount);
        }
        currentHierarchyVisitedNodes = Math.addExact(
                currentHierarchyVisitedNodes,
                parallel ? parallelVisitedNodes[group.groupIndex()] : hierarchy.getLastVisitedNodes());
        currentHierarchyTestedCommands = Math.addExact(
                currentHierarchyTestedCommands,
                parallel ? parallelTestedCommands[group.groupIndex()] : hierarchy.getLastTestedCommands());
        if (visibleCount == BgfxCommandRangeHierarchy.ALL_VISIBLE) {
            addImmediateSubmissionSpan(first, group.commandCount());
            return immediateSubmissionSpans;
        }
        if (visibleCount == 0) {
            return immediateSubmissionSpans;
        }
        var epoch = parallel ? parallelImmediateEpoch : nextImmediateVisibilityEpoch();
        if (!parallel) {
            for (var index = 0; index < visibleCount; index++) {
                immediateVisibleCommandEpoch[immediateVisibleCommands[index]] = epoch;
            }
        }
        var end = Math.addExact(first, group.commandCount());
        var cursor = first;
        while (cursor < end) {
            while (cursor < end && immediateVisibleCommandEpoch[cursor] != epoch) {
                cursor++;
            }
            if (cursor >= end) {
                break;
            }
            var spanStart = cursor++;
            var spanEnd = cursor;
            while (cursor < end) {
                var nextVisible = cursor;
                while (nextVisible < end && immediateVisibleCommandEpoch[nextVisible] != epoch) {
                    nextVisible++;
                }
                if (nextVisible >= end || nextVisible - spanEnd >= submissionCost) {
                    cursor = nextVisible;
                    break;
                }
                cursor = nextVisible + 1;
                spanEnd = cursor;
            }
            addImmediateSubmissionSpan(spanStart, spanEnd - spanStart);
        }
        if (immediateSubmissionSpans.isEmpty()) {
            return immediateSubmissionSpans;
        }
        var submittedRanges = 0L;
        for (var spanIndex = 0; spanIndex < immediateSubmissionSpans.size(); spanIndex++) {
            submittedRanges += immediateSubmissionSpans.get(spanIndex).getCommandCount();
        }
        var splitCost = submittedRanges + (long) immediateSubmissionSpans.size() * submissionCost;
        var fullCost = (long) group.commandCount() + submissionCost;
        if (splitCost >= fullCost) {
            immediateSubmissionSpans.clear();
            immediateSubmissionSpanPoolUsed = 0;
            addImmediateSubmissionSpan(first, group.commandCount());
        }
        return immediateSubmissionSpans;
    }

    private boolean prepareParallelImmediatePlan(BgfxCommandRangeHierarchy hierarchy, Frustum frustum) {
        if (visibilityWorkers < 2
                || topology.getCommandCount() < 16384
                || topology.getGroups().size() < 64) {
            return false;
        }
        if (parallelImmediatePrepared) {
            return true;
        }
        var groups = topology.getGroups();
        if (parallelVisibleCounts.length < groups.size()) {
            parallelVisibleCounts = new int[groups.size()];
            parallelVisitedNodes = new int[groups.size()];
            parallelTestedCommands = new int[groups.size()];
        }
        var epoch = nextImmediateVisibilityEpoch();
        var nextGroup = new AtomicInteger();
        var work = new CompletableFuture<?>[visibilityWorkers];
        for (var worker = 0; worker < visibilityWorkers; worker++) {
            var scratch = immediateQueries[worker];
            scratch.ensure(hierarchy);
            work[worker] = CompletableFuture.runAsync(
                    () -> {
                        for (var start = nextGroup.getAndAdd(16);
                                start < groups.size();
                                start = nextGroup.getAndAdd(16)) {
                            var end = Math.min(groups.size(), start + 16);
                            for (var index = start; index < end; index++) {
                                var count = hierarchy.collectVisibleCommands(
                                        index, frustum, scratch.visible, scratch.stack, scratch.query);
                                parallelVisibleCounts[index] = count;
                                parallelVisitedNodes[index] = scratch.query.visitedNodes;
                                parallelTestedCommands[index] = scratch.query.testedCommands;
                                for (var visible = 0; visible < count; visible++) {
                                    immediateVisibleCommandEpoch[scratch.visible[visible]] = epoch;
                                }
                            }
                        }
                    },
                    ImmediateExecutor.INSTANCE);
        }
        CompletableFuture.allOf(work).join();
        parallelImmediateEpoch = epoch;
        parallelImmediatePrepared = true;
        return true;
    }

    private void verifyParallelGroup(Group group, BgfxCommandRangeHierarchy hierarchy, Frustum frustum, int actual) {
        var expected = hierarchy.collectVisibleCommands(
                group.groupIndex(), frustum, immediateVisibleCommands, immediateHierarchyStack);
        if (expected != actual) {
            throw new IllegalStateException("parallel visibility count differs from serial query");
        }
        for (var index = 0; index < expected; index++) {
            if (immediateVisibleCommandEpoch[immediateVisibleCommands[index]] != parallelImmediateEpoch) {
                throw new IllegalStateException("parallel visibility command differs from serial query");
            }
        }
        verifiedParallelGroups++;
    }

    private static final class ImmediateQuery {
        private int[] visible = new int[0];
        private int[] stack = new int[0];
        private final Query query = new Query();

        private void ensure(BgfxCommandRangeHierarchy hierarchy) {
            if (visible.length < hierarchy.getMaximumGroupCommands()) {
                visible = new int[hierarchy.getMaximumGroupCommands()];
            }
            if (stack.length < hierarchy.getMaximumDepth() + 1) {
                stack = new int[hierarchy.getMaximumDepth() + 1];
            }
        }
    }

    private static final class ImmediateExecutor {
        private static final ExecutorService INSTANCE = Executors.newFixedThreadPool(
                4, Thread.ofPlatform().daemon().name("visibility-selection-", 0).factory());
    }

    private void ensureImmediateHierarchyScratch(BgfxCommandRangeHierarchy hierarchy) {
        if (immediateVisibleCommands.length < hierarchy.getMaximumGroupCommands()) {
            immediateVisibleCommands = new int[hierarchy.getMaximumGroupCommands()];
        }
        var requiredStack = hierarchy.getMaximumDepth() + 1;
        if (immediateHierarchyStack.length < requiredStack) {
            immediateHierarchyStack = new int[requiredStack];
        }
        if (immediateVisibleCommandEpoch.length < topology.getCommandCount()) {
            immediateVisibleCommandEpoch = new int[topology.getCommandCount()];
            immediateVisibilityEpoch = 0;
        }
    }

    private int nextImmediateVisibilityEpoch() {
        immediateVisibilityEpoch++;
        if (immediateVisibilityEpoch == 0) {
            Arrays.fill(immediateVisibleCommandEpoch, 0);
            immediateVisibilityEpoch = 1;
        }
        return immediateVisibilityEpoch;
    }

    private void addImmediateSubmissionSpan(int firstCommand, int commandCount) {
        SubmissionSpan span;
        if (immediateSubmissionSpanPoolUsed == immediateSubmissionSpanPool.size()) {
            span = new SubmissionSpan(firstCommand, commandCount);
            immediateSubmissionSpanPool.add(span);
        } else {
            span = immediateSubmissionSpanPool.get(immediateSubmissionSpanPoolUsed);
            span.set(firstCommand, commandCount);
        }
        immediateSubmissionSpanPoolUsed++;
        immediateSubmissionSpans.add(span);
    }

    private List<List<SubmissionSpan>> updateSubmissionPlan(ByteBuffer classifications, Topology topology) {
        var commandCount = topology.getCommandCount();
        if (delayedVisibleCommands.length < commandCount) {
            delayedVisibleCommands = new boolean[commandCount];
        } else {
            Arrays.fill(delayedVisibleCommands, 0, commandCount, false);
        }
        for (var command : topology.getCommandsList()) {
            delayedVisibleCommands[command.commandIndex()] =
                    requiresSubmission(classifications, command.firstInstance(), command.maxInstances());
        }
        var groups = topology.getGroups();
        while (delayedSubmissionGroupPool.size() < groups.size()) {
            delayedSubmissionGroupPool.add(new PooledSubmissionSpanList());
        }
        delayedSubmissionPlan.clear();
        for (var groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            var group = groups.get(groupIndex);
            var spans = delayedSubmissionGroupPool.get(groupIndex);
            updateSubmissionSpans(
                    spans, delayedVisibleCommands, group.firstCommand(), group.commandCount(), submissionCost);
            delayedSubmissionPlan.add(spans);
        }
        return delayedSubmissionPlan;
    }

    private StatsSlot acquireStatsSlot() {
        if (statsSlots == null) {
            return null;
        }
        var frame = device.getStats().frameNumber();
        if (lastStatsSubmissionFrame != Long.MIN_VALUE
                && Integer.toUnsignedLong((int) (frame - lastStatsSubmissionFrame)) < STATS_SAMPLE_INTERVAL) {
            return null;
        }
        for (var offset = 0; offset < statsSlots.length; offset++) {
            var index = (nextStatsSlot + offset) % statsSlots.length;
            if (!statsSlots[index].inFlight) {
                nextStatsSlot = (index + 1) % statsSlots.length;
                lastStatsSubmissionFrame = frame;
                statsSlots[index].submissionFrame = frame;
                return statsSlots[index];
            }
        }
        return null;
    }

    private int enabledCategoryMask() {
        var settings = state;
        var mask = 0;
        if (settings.settings.isPassableEnabled()) {
            mask |= CATEGORY_PASSABLE;
        }
        if (settings.settings.isTerrainEnabled()) {
            mask |= CATEGORY_TERRAIN;
        }
        if (settings.settings.isStaticMeshesEnabled()) {
            mask |= CATEGORY_STATIC_MESH;
        }
        if (settings.settings.isCsgEnabled()) {
            mask |= CATEGORY_CSG;
        }
        return mask;
    }

    private boolean isPipelineAvailable() {
        return requested && cullProgram != null;
    }

    private boolean isCameraHistoryCompatible() {
        var position = renderingContext.camera.getPosition();
        var dx = position.getX() - historyCamera[0];
        var dy = position.getY() - historyCamera[1];
        var dz = position.getZ() - historyCamera[2];
        if (dx * dx + dy * dy + dz * dz > MAX_HISTORY_TRANSLATION_SQUARED) {
            historyBypassReason = "camera translation cut";
            return false;
        }
        var forward = renderingContext.camera.getForwardDir();
        var dot = forward.getX() * historyCamera[3]
                + forward.getY() * historyCamera[4]
                + forward.getZ() * historyCamera[5];
        if (dot < MIN_HISTORY_FORWARD_DOT) {
            historyBypassReason = "camera rotation cut";
            return false;
        }
        return true;
    }

    private void warnFallback(String reason, Throwable failure) {
        if (reportedFallbacks.add(reason)) {
            if (failure == null) {
                log.warn("{}. Using CPU/direct fallback", reason);
            } else {
                log.warn("{}. Using CPU/direct fallback", reason, failure);
            }
        } else {
            log.debug("Repeated CPU/direct fallback: {}", reason);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("GPU visibility owner is closed");
        }
    }

    private static int capacityFor(int required, int maximum) {
        if (required < 1 || required > maximum) {
            throw new CapacityOverflow("invalid visibility capacity request: " + required);
        }
        if (required > GEOMETRIC_CAPACITY_LIMIT) {
            var candidate = (long) required + required / 4L;
            return (int) Math.min(maximum, candidate);
        }
        var highest = Integer.highestOneBit(required - 1);
        if (highest <= 0) {
            return 1;
        }
        var candidate = highest << 1;
        return Math.min(maximum, Math.max(required, candidate));
    }

    private static void updateSubmissionSpans(
            PooledSubmissionSpanList spans,
            boolean[] visibleCommands,
            int firstCommand,
            int commandCount,
            int submissionCost) {
        spans.reset();
        var end = firstCommand + commandCount;
        var cursor = firstCommand;
        while (cursor < end) {
            while (cursor < end && !visibleCommands[cursor]) {
                cursor++;
            }
            if (cursor >= end) {
                break;
            }
            var spanStart = cursor++;
            var spanEnd = cursor;
            while (cursor < end) {
                var nextVisible = cursor;
                while (nextVisible < end && !visibleCommands[nextVisible]) {
                    nextVisible++;
                }
                if (nextVisible >= end || nextVisible - spanEnd >= submissionCost) {
                    break;
                }
                cursor = nextVisible + 1;
                spanEnd = cursor;
            }
            spans.addSpan(spanStart, spanEnd - spanStart);
        }
        if (spans.isEmpty()) {
            return;
        }
        var submittedRanges = 0L;
        for (var spanIndex = 0; spanIndex < spans.size(); spanIndex++) {
            submittedRanges += spans.get(spanIndex).getCommandCount();
        }
        var splitCost = submittedRanges + (long) spans.size() * submissionCost;
        var fullCost = (long) commandCount + submissionCost;
        if (splitCost >= fullCost) {
            spans.reset();
            spans.addSpan(firstCommand, commandCount);
        }
    }

    private static int plannedCommandRanges(List<List<SubmissionSpan>> plan) {
        var result = 0;
        for (var groupIndex = 0; groupIndex < plan.size(); groupIndex++) {
            var group = plan.get(groupIndex);
            for (var spanIndex = 0; spanIndex < group.size(); spanIndex++) {
                result = Math.addExact(result, group.get(spanIndex).getCommandCount());
            }
        }
        return result;
    }

    private static boolean hasReached(long current, long target) {
        return Integer.compareUnsigned((int) current, (int) target) >= 0;
    }

    private static int categoryFlags(Primitive primitive) {
        var material = primitive.entityMaterial();
        var type = material == null ? SurfaceType.TERRAIN.getId() : material.getSurfaceType();
        var flags = 0;
        if ((type & SurfaceType.PASSABLE.getId()) != 0) {
            flags |= CATEGORY_PASSABLE;
        }
        if ((type & SurfaceType.TERRAIN.getId()) != 0) {
            flags |= CATEGORY_TERRAIN;
        }
        if ((type & SurfaceType.STATIC_MESH.getId()) != 0) {
            flags |= CATEGORY_STATIC_MESH;
        }
        if ((type & SurfaceType.CSG.getId()) != 0) {
            flags |= CATEGORY_CSG;
        }
        if ((type & SurfaceType.BOUNDING_BOX.getId()) != 0) {
            flags |= CATEGORY_BOUNDING_BOX;
        }
        return flags;
    }

    private static int drawCategory(Primitive primitive) {
        var material = primitive.entityMaterial();
        var type = material == null ? SurfaceType.TERRAIN.getId() : material.getSurfaceType();
        if ((type & SurfaceType.TERRAIN.getId()) != 0) {
            return 0;
        }
        if ((type & SurfaceType.CSG.getId()) != 0) {
            return 1;
        }
        if ((type & SurfaceType.STATIC_MESH.getId()) != 0) {
            return 2;
        }
        return 3;
    }

    private static void putCandidate(
            ByteBuffer bounds,
            ByteBuffer instances,
            BgfxSceneObject object,
            Primitive primitive,
            int instance,
            int commandIndex,
            int flags,
            float decorationMultiplier) {
        var records = object.getInstanceRecords();
        var offset = instance * BgfxSceneObject.INSTANCE_FLOATS;
        if (object.isEmptySlot(instance)) {
            putBounds(bounds, EMPTY_SLOT, commandIndex, flags, null, EMPTY_SLOT_REACH);
        } else {
            var matrix = new float[16];
            System.arraycopy(records, offset, matrix, 0, 16);
            var instanceBounds = BgfxBillboardTilt.isBillboard(records, offset)
                    ? BgfxSceneObject.transformBillboard(primitive.localBounds(), records, offset)
                    : BgfxSceneObject.transform(primitive.localBounds(), matrix);
            putBounds(
                    bounds,
                    instanceBounds,
                    commandIndex,
                    flags,
                    object.getStaticMeshLod(),
                    object.getDecorationFadeMax(instance) * decorationMultiplier);
        }
        for (var value = 0; value < BgfxSceneObject.INSTANCE_FLOATS; value++) {
            instances.putFloat(records[offset + value]);
        }
    }

    private static void putBounds(
            ByteBuffer target,
            Bounds3f bounds,
            int commandIndex,
            int flags,
            StaticMeshLod staticMeshLod,
            float decorationReach) {
        target.putFloat(bounds.minX())
                .putFloat(bounds.minY())
                .putFloat(bounds.minZ())
                .putFloat(commandIndex);
        target.putFloat(bounds.maxX())
                .putFloat(bounds.maxY())
                .putFloat(bounds.maxZ())
                .putFloat(flags);
        if (staticMeshLod == null && decorationReach > 0f) {
            target.putFloat(decorationReach).putFloat(0f).putFloat(-2f).putFloat(0f);
        } else if (staticMeshLod == null) {
            target.putFloat(0f).putFloat(0f).putFloat(-1f).putFloat(0f);
        } else {
            target.putFloat(staticMeshLod.distance1())
                    .putFloat(staticMeshLod.distance2())
                    .putFloat(staticMeshLod.level())
                    .putFloat((staticMeshLod.authoredBlend() ? 1f : 0f) + staticMeshLod.impostorThresholdMask() * 4f);
        }
    }

    private static float[] identityInstance() {
        var result = new float[BgfxSceneObject.INSTANCE_FLOATS];
        result[0] = 1f;
        result[5] = 1f;
        result[10] = 1f;
        result[15] = 1f;
        result[16] = 1f;
        result[17] = 1f;
        result[18] = 1f;
        result[19] = 1f;
        return result;
    }

    private static RenderFamily familyOf(Primitive primitive) {
        var material = primitive.entityMaterial();
        if (material == null) {
            return RenderFamily.TERRAIN_OPAQUE;
        }
        if ((material.getSurfaceType() & SurfaceType.CSG.getId()) != 0) {
            return RenderFamily.CSG_OPAQUE;
        }
        if (material.getBlendMode() == BlendMode.MASKED || material.getBlendMode() == BlendMode.MASKED_BLEND) {
            return RenderFamily.MASKED_OPAQUE;
        }
        return RenderFamily.STATIC_MESH_OPAQUE;
    }

    private static String[] pyramidViewNames() {
        var result = new String[32];
        for (var mip = 0; mip < result.length; mip++) {
            result[mip] = "visibility-pyramid-" + mip;
        }
        return result;
    }

    private static float square(float value) {
        return value * value;
    }

    private static void copy16(FloatBuffer source, float[] target) {
        for (var index = 0; index < 16; index++) {
            target[index] = source.get(index);
        }
    }

    private static void multiply4x4(float[] left, float[] right, float[] target) {
        for (var column = 0; column < 4; column++) {
            for (var row = 0; row < 4; row++) {
                var value = 0f;
                for (var index = 0; index < 4; index++) {
                    value += left[index * 4 + row] * right[column * 4 + index];
                }
                target[column * 4 + row] = value;
            }
        }
    }

    private static boolean areMatricesEqual(float[] first, float[] second) {
        if (first == null || second == null) {
            return false;
        }
        for (var index = 0; index < 16; index++) {
            var tolerance = 1e-5f * Math.max(1f, Math.max(Math.abs(first[index]), Math.abs(second[index])));
            if (Math.abs(first[index] - second[index]) > tolerance) {
                return false;
            }
        }
        return true;
    }

    private static boolean areMatricesIdentical(float[] first, float[] second) {
        if (first == null || second == null) {
            return false;
        }
        for (var index = 0; index < 16; index++) {
            if (Float.floatToIntBits(first[index]) != Float.floatToIntBits(second[index])) {
                return false;
            }
        }
        return true;
    }

    private static void multiply(float[] left, float[] right, float[] result) {
        if (result == null || result.length != 16) {
            throw new IllegalArgumentException("matrix multiplication output must be a 4x4 matrix");
        }
        for (var column = 0; column < 4; column++) {
            for (var row = 0; row < 4; row++) {
                var value = 0f;
                for (var k = 0; k < 4; k++) {
                    value += left[k * 4 + row] * right[column * 4 + k];
                }
                result[column * 4 + row] = value;
            }
        }
    }

    private static float[] identity() {
        var result = new float[16];
        result[0] = 1f;
        result[5] = 1f;
        result[10] = 1f;
        result[15] = 1f;
        return result;
    }

    private static String describeFailure(Throwable failure) {
        var message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception e) {
            log.warn("Failed to retire visibility resource", e);
        }
    }

    private static void closeQuietly(BgfxTexture[] resources) {
        if (resources == null) {
            return;
        }
        closeQuietly(resources[1]);
        closeQuietly(resources[0]);
    }

    private static void closeQuietly(StatsSlot[] resources) {
        if (resources == null) {
            return;
        }
        for (var index = resources.length - 1; index >= 0; index--) {
            closeQuietly(resources[index]);
        }
    }

    private static RuntimeException closeAndMerge(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                return e;
            }
            failure.addSuppressed(e);
        } catch (Exception e) {
            var wrapped = new IllegalStateException("failed to close GPU visibility resource", e);
            if (failure == null) {
                return wrapped;
            }
            failure.addSuppressed(wrapped);
        }
        return failure;
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

    private static void closeAfterFailure(StatsSlot[] resources, Throwable failure) {
        if (resources == null) {
            return;
        }
        for (var index = resources.length - 1; index >= 0; index--) {
            closeAfterFailure(resources[index], failure);
        }
    }

    private final class StatsSlot implements AutoCloseable {
        private final BgfxTexture staging;
        private final ByteBuffer pixels;
        private long readyFrame;
        private long submissionFrame;
        private int candidateCount;
        private final float[] view = new float[16];
        private final float[] projection = new float[16];
        private Extent renderExtent = new Extent(0, 0);
        private int categoryMask;
        private float maximumDistanceSquared;
        private long topologyGeneration;
        private long lodSettingsRevision;
        private boolean inFlight;

        private StatsSlot(BgfxTexture staging, Extent extent) {
            this.staging = staging;
            this.pixels = ByteBuffer.allocateDirect(Math.toIntExact(Math.multiplyExact(extent.pixels(), 4L)))
                    .order(ByteOrder.nativeOrder());
        }

        @Override
        public void close() {
            if (inFlight) {
                device.retainUntilShutdown(pixels);
                inFlight = false;
            }
            staging.close();
        }
    }

    record Command(
            BgfxSceneObject object,
            int primitiveOrdinal,
            Primitive cachedPrimitive,
            RenderFamily family,
            int commandIndex,
            int firstInstance,
            int maxInstances,
            int vertexOffset,
            int indexOffset,
            Bounds3f worldBounds,
            List<Seed> seeds) {
        Primitive primitive() {
            return cachedPrimitive;
        }
    }

    record Group(
            int groupIndex,
            int firstCommand,
            int commandCount,
            IndirectBinding binding,
            RenderFamily family,
            BgfxSceneObject representativeObject,
            int representativePrimitiveOrdinal,
            Primitive cachedRepresentativePrimitive,
            boolean orderSensitive,
            int potentialInstances,
            long potentialTriangles) {
        Primitive representativePrimitive() {
            return cachedRepresentativePrimitive;
        }
    }

    static final class SubmissionSpan {
        private int firstCommand;
        private int commandCount;

        SubmissionSpan(int firstCommand, int commandCount) {
            set(firstCommand, commandCount);
        }

        int getFirstCommand() {
            return firstCommand;
        }

        int getCommandCount() {
            return commandCount;
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof SubmissionSpan span
                            && firstCommand == span.firstCommand
                            && commandCount == span.commandCount;
        }

        @Override
        public int hashCode() {
            return 31 * firstCommand + commandCount;
        }

        @Override
        public String toString() {
            return "SubmissionSpan[firstCommand=" + firstCommand + ", commandCount=" + commandCount + ']';
        }

        private void set(int firstCommand, int commandCount) {
            if (firstCommand < 0 || commandCount < 1) {
                throw new IllegalArgumentException("invalid indirect submission span");
            }
            this.firstCommand = firstCommand;
            this.commandCount = commandCount;
        }
    }

    private static final class PooledSubmissionSpanList extends AbstractList<SubmissionSpan> {
        private final ArrayList<SubmissionSpan> pool = new ArrayList<>();
        private int activeSize;

        void reset() {
            activeSize = 0;
        }

        void addSpan(int firstCommand, int commandCount) {
            if (activeSize == pool.size()) {
                pool.add(new SubmissionSpan(firstCommand, commandCount));
            } else {
                pool.get(activeSize).set(firstCommand, commandCount);
            }
            activeSize++;
        }

        @Override
        public SubmissionSpan get(int index) {
            if (index < 0 || index >= activeSize) {
                throw new IndexOutOfBoundsException(index);
            }
            return pool.get(index);
        }

        @Override
        public int size() {
            return activeSize;
        }
    }

    static final class Topology implements AutoCloseable {
        private List<Command> commandsList;
        private List<Group> groups;
        private final BgfxBuffer bounds;
        private final BgfxBuffer instancesIn;
        private final BgfxBuffer commands;
        private final BgfxBuffer counts;
        private final BgfxBuffer instancesOut;
        private final BgfxBuffer orderedCommands;
        private final BgfxBuffer orderBlocks;
        private final BgfxBuffer orderMasks;
        private final BgfxBuffer orderBlockCounts;
        private final BgfxVisibilityOrder.Buffers orderBuffers;
        private final BgfxBuffer materialTable;
        private final BgfxIndirectBuffer indirect;
        private final BgfxCommandRangeHierarchy commandHierarchy;
        private final int candidateCapacity;
        private final int commandCapacity;
        private final int materialCapacity;
        private final ByteBuffer boundsShadow;
        private final ByteBuffer instancesShadow;
        private final ByteBuffer commandsShadow;
        private final ByteBuffer orderedCommandsShadow;
        private final ByteBuffer orderBlocksShadow;
        private final ByteBuffer materialShadow;
        private final ByteBuffer materialScratch;
        private final ByteBuffer materialRecordScratch;
        private final FloatBuffer materialRecordFloatScratch;
        private final IdentityHashMap<BgfxSceneObject, boolean[]> eligible = new IdentityHashMap<>();
        private final IdentityHashMap<BgfxSceneObject, Command[]> commandsByObject = new IdentityHashMap<>();
        private final IdentityHashMap<BgfxSceneObject, int[]> candidatesByObject = new IdentityHashMap<>();
        private final IdentityHashMap<BgfxSceneObject, int[]> decorationSeeds = new IdentityHashMap<>();
        private final int[] commandCategoryCounts = new int[4];
        private int orderBlockCount;
        private int boundsUsedBytes;
        private int instancesUsedBytes;
        private int commandsUsedBytes;
        private int orderedCommandsUsedBytes;
        private int orderBlocksUsedBytes;
        private int materialUsedBytes;
        private int candidateCount;
        private List<MaterialRecord> materialRecords;
        private int[] timeDynamicMaterialRecords = new int[0];
        private int[] commandInstancePrefix = new int[] {0};
        private long[] commandTrianglePrefix = new long[] {0L};
        private boolean materialTableInitialized;
        private boolean materialTexturesEnabled;
        private int materialShadowVariant;
        private boolean materialOrthographic;
        private long retainedCpuBytes;
        private double multiGroupCoverage;
        private long lastDeltaUploadBytes;

        private Topology(
                BgfxBuffer bounds,
                BgfxBuffer instancesIn,
                BgfxBuffer commands,
                BgfxBuffer counts,
                BgfxBuffer instancesOut,
                BgfxBuffer orderedCommands,
                BgfxBuffer orderBlocks,
                BgfxBuffer orderMasks,
                BgfxBuffer orderBlockCounts,
                BgfxBuffer materialTable,
                BgfxIndirectBuffer indirect,
                int candidateCapacity,
                int commandCapacity,
                int materialCapacity,
                int commandHierarchyLeafCommands) {
            this.commandHierarchy = new BgfxCommandRangeHierarchy(commandHierarchyLeafCommands);
            this.bounds = bounds;
            this.instancesIn = instancesIn;
            this.commands = commands;
            this.counts = counts;
            this.instancesOut = instancesOut;
            this.orderedCommands = orderedCommands;
            this.orderBlocks = orderBlocks;
            this.orderMasks = orderMasks;
            this.orderBlockCounts = orderBlockCounts;
            this.orderBuffers = new BgfxVisibilityOrder.Buffers(
                    bounds,
                    instancesIn,
                    commands,
                    counts,
                    instancesOut,
                    orderedCommands,
                    orderBlocks,
                    orderMasks,
                    orderBlockCounts);
            this.materialTable = materialTable;
            this.indirect = indirect;
            this.candidateCapacity = candidateCapacity;
            this.commandCapacity = commandCapacity;
            this.materialCapacity = materialCapacity;
            boundsShadow = directCapacity(candidateCapacity, BgfxSceneVertexLayouts.VISIBILITY_BOUNDS.getStride());
            instancesShadow = directCapacity(candidateCapacity, BgfxSceneVertexLayouts.INSTANCE.getStride());
            commandsShadow = directCapacity(commandMetadataElements(commandCapacity, materialCapacity), Integer.BYTES);
            orderedCommandsShadow = directCapacity(commandCapacity, BgfxVisibilityOrder.COMMAND_WORDS * Integer.BYTES);
            orderBlocksShadow = directCapacity(
                    BgfxVisibilityOrder.blockCapacity(candidateCapacity, commandCapacity), Integer.BYTES);
            materialShadow = directCapacity(materialCapacity, MATERIAL_RECORD_STRIDE * 4 * Float.BYTES);
            materialScratch = directCapacity(materialCapacity, MATERIAL_RECORD_STRIDE * 4 * Float.BYTES);
            materialRecordScratch = directCapacity(1, MATERIAL_RECORD_BYTES);
            materialRecordFloatScratch = materialRecordScratch.asFloatBuffer();
        }

        static void writeMaterialRecord(
                MaterialRecord record,
                FloatBuffer target,
                float elapsedSeconds,
                boolean texturesEnabled,
                int shadowVariant,
                LongToDoubleFunction semanticDepthBias) {
            var object = record.object();
            var material = record.material();
            material.writeGpuRecord(
                    target,
                    elapsedSeconds,
                    texturesEnabled,
                    object.getLightmap(shadowVariant),
                    object.getLightmapLayer(shadowVariant),
                    (float) semanticDepthBias.applyAsDouble(material.getSurfaceType()),
                    object.isFoliage());
        }

        static DeltaUploadRange deltaUploadRange(
                ByteBuffer shadow, int previousBytes, ByteBuffer nextData, int stride) {
            if (shadow == null
                    || nextData == null
                    || stride < 1
                    || previousBytes < 0
                    || previousBytes > shadow.capacity()) {
                throw new IllegalArgumentException("invalid topology delta state");
            }
            var next = nextData.duplicate().order(ByteOrder.nativeOrder());
            var nextBytes = next.remaining();
            if (nextBytes % stride != 0 || nextBytes > shadow.capacity() || previousBytes % stride != 0) {
                throw new IllegalArgumentException("invalid topology delta payload");
            }
            var commonBytes = Math.min(previousBytes, nextBytes);
            var firstChanged = -1;
            for (var offset = 0; offset < commonBytes; offset++) {
                if (shadow.get(offset) != next.get(offset)) {
                    firstChanged = offset;
                    break;
                }
            }
            if (firstChanged < 0 && nextBytes > previousBytes) {
                firstChanged = previousBytes;
            }
            if (firstChanged < 0) {
                return null;
            }
            var lastChanged = nextBytes - 1;
            if (nextBytes <= previousBytes) {
                while (lastChanged >= firstChanged && shadow.get(lastChanged) == next.get(lastChanged)) {
                    lastChanged--;
                }
            }
            if (lastChanged < firstChanged) {
                return null;
            }
            return new DeltaUploadRange(firstChanged / stride, lastChanged / stride + 1);
        }

        List<Command> getCommandsList() {
            return commandsList;
        }

        List<Group> getGroups() {
            return groups;
        }

        BgfxCommandRangeHierarchy getCommandHierarchy() {
            return commandHierarchy;
        }

        BgfxBuffer getInstancesOut() {
            return instancesOut;
        }

        void bindMaterialTable() {
            bgfx_set_compute_dynamic_vertex_buffer(MATERIAL_TABLE_STAGE, materialTable.getHandle(), BGFX_ACCESS_READ);
        }

        BgfxIndirectBuffer getIndirect() {
            return indirect;
        }

        int getCandidateCount() {
            return candidateCount;
        }

        int getCommandCount() {
            return commandsList.size();
        }

        int getPotentialInstances(int firstCommand, int commandCount) {
            var end = checkedCommandEnd(firstCommand, commandCount);
            return commandInstancePrefix[end] - commandInstancePrefix[firstCommand];
        }

        long getPotentialTriangles(int firstCommand, int commandCount) {
            var end = checkedCommandEnd(firstCommand, commandCount);
            return commandTrianglePrefix[end] - commandTrianglePrefix[firstCommand];
        }

        int getMaterialCount() {
            return materialRecords.size();
        }

        double getMultiGroupCoverage() {
            return multiGroupCoverage;
        }

        long getRetainedCpuBytes() {
            return retainedCpuBytes;
        }

        long getLastDeltaUploadBytes() {
            return lastDeltaUploadBytes;
        }

        Command getCommandFor(BgfxSceneObject object, int primitiveOrdinal) {
            var commands = commandsByObject.get(object);
            return commands == null || primitiveOrdinal < 0 || primitiveOrdinal >= commands.length
                    ? null
                    : commands[primitiveOrdinal];
        }

        int[] getResidualPrimitiveOrdinals(BgfxSceneObject object) {
            return getResidualPrimitiveOrdinals(object, eligible.get(object));
        }

        boolean includes(BgfxSceneObject object) {
            return eligible.containsKey(object);
        }

        int getOrderBlockCount() {
            return orderBlockCount;
        }

        int getCommandCategoryCount(int category) {
            if (category < 0 || category >= commandCategoryCounts.length) {
                throw new IllegalArgumentException("invalid draw category: " + category);
            }
            return commandCategoryCounts[category];
        }

        int getBindingCount() {
            var bindings = new IdentityHashMap<IndirectBinding, Boolean>();
            for (var group : groups) {
                bindings.put(group.binding(), Boolean.TRUE);
            }
            return bindings.size();
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            failure = closeAndMerge(indirect, failure);
            failure = closeAndMerge(materialTable, failure);
            failure = closeAndMerge(orderBlockCounts, failure);
            failure = closeAndMerge(orderMasks, failure);
            failure = closeAndMerge(orderBlocks, failure);
            failure = closeAndMerge(orderedCommands, failure);
            failure = closeAndMerge(instancesOut, failure);
            failure = closeAndMerge(counts, failure);
            failure = closeAndMerge(commands, failure);
            failure = closeAndMerge(instancesIn, failure);
            failure = closeAndMerge(bounds, failure);
            if (failure != null) {
                throw failure;
            }
        }

        record DeltaUploadRange(int firstElement, int endElement) {}

        private record DeltaUpdate(int usedBytes, long uploadedBytes) {}

        private boolean canFit(int candidates, int commands, int materials) {
            return candidates <= candidateCapacity && commands <= commandCapacity && materials <= materialCapacity;
        }

        private long update(
                List<Command> commandsList,
                List<Group> groups,
                List<MaterialRecord> materialRecords,
                ByteBuffer boundsData,
                ByteBuffer instanceData,
                ByteBuffer commandData,
                int candidateCount,
                double multiGroupCoverage) {
            if (!canFit(candidateCount, commandsList.size(), Math.max(1, materialRecords.size()))) {
                throw new IllegalArgumentException("visibility topology update exceeds allocated capacity");
            }
            var uploaded = 0L;
            var boundsUpdate = updateDelta(
                    bounds,
                    boundsShadow,
                    boundsUsedBytes,
                    boundsData,
                    BgfxSceneVertexLayouts.VISIBILITY_BOUNDS.getStride());
            uploaded = Math.addExact(uploaded, boundsUpdate.uploadedBytes());
            boundsUsedBytes = boundsUpdate.usedBytes();
            var instanceUpdate = updateDelta(
                    instancesIn,
                    instancesShadow,
                    instancesUsedBytes,
                    instanceData,
                    BgfxSceneVertexLayouts.INSTANCE.getStride());
            uploaded = Math.addExact(uploaded, instanceUpdate.uploadedBytes());
            instancesUsedBytes = instanceUpdate.usedBytes();
            var commandUpdate = updateDelta(commands, commandsShadow, commandsUsedBytes, commandData, Integer.BYTES);
            uploaded = Math.addExact(uploaded, commandUpdate.uploadedBytes());
            commandsUsedBytes = commandUpdate.usedBytes();
            this.commandsList = commandsList;
            this.groups = groups;
            this.materialRecords = materialRecords;
            this.timeDynamicMaterialRecords = timeDynamicMaterialRecords(materialRecords);
            rebuildCommandMetricPrefixes(commandsList);
            commandHierarchy.rebuild(commandsList, groups);
            this.materialUsedBytes = 0;
            this.materialTableInitialized = false;
            this.candidateCount = candidateCount;
            this.retainedCpuBytes = (long) boundsUsedBytes
                    + instancesUsedBytes
                    + commandsUsedBytes
                    + materialUsedBytes
                    + commandHierarchy.getRetainedBytes();
            this.multiGroupCoverage = multiGroupCoverage;
            this.lastDeltaUploadBytes = uploaded;
            eligible.clear();
            commandsByObject.clear();
            candidatesByObject.clear();
            decorationSeeds.clear();
            Arrays.fill(commandCategoryCounts, 0);
            var orderedIndexes = new int[commandsList.size()];
            var orderedCandidates = new int[commandsList.size()];
            var orderedCount = 0;
            for (var command : commandsList) {
                var candidate = command.firstInstance();
                for (var seed : command.seeds()) {
                    var count = seed.instanceCount();
                    var ranges = candidatesByObject.get(seed.object());
                    var next = ranges == null ? new int[2] : Arrays.copyOf(ranges, ranges.length + 2);
                    next[next.length - 2] = candidate;
                    next[next.length - 1] = count;
                    candidatesByObject.put(seed.object(), next);
                    if (seed.object().isDecorationPool()) {
                        var known = decorationSeeds.get(seed.object());
                        var seeds = known == null ? new int[4] : Arrays.copyOf(known, known.length + 4);
                        seeds[seeds.length - 4] = seed.primitiveOrdinal();
                        seeds[seeds.length - 3] = candidate;
                        seeds[seeds.length - 2] = command.commandIndex();
                        seeds[seeds.length - 1] =
                                candidateFlags(seed.primitive(), seed.topologyFlags(), seed.materialRecord());
                        decorationSeeds.put(seed.object(), seeds);
                    }
                    candidate += count;
                    eligible.computeIfAbsent(
                                    seed.object(),
                                    object -> new boolean
                                            [object.getGeometry()
                                                    .getPrimitives()
                                                    .size()])[seed.primitiveOrdinal()] = true;
                    commandsByObject
                            .computeIfAbsent(
                                    seed.object(),
                                    object -> new Command
                                            [object.getGeometry()
                                                    .getPrimitives()
                                                    .size()])[seed.primitiveOrdinal()] = command;
                }
                commandCategoryCounts[drawCategory(command.primitive())]++;
                if (isOrderSensitive(command.primitive())) {
                    orderedIndexes[orderedCount] = command.commandIndex();
                    orderedCandidates[orderedCount] = command.maxInstances();
                    orderedCount++;
                }
            }
            var orderLayout = BgfxVisibilityOrder.createLayout(orderedIndexes, orderedCandidates, orderedCount);
            var orderedUpdate = updateDelta(
                    orderedCommands,
                    orderedCommandsShadow,
                    orderedCommandsUsedBytes,
                    orderLayout.commands(),
                    Integer.BYTES);
            orderedCommandsUsedBytes = orderedUpdate.usedBytes();
            uploaded = Math.addExact(uploaded, orderedUpdate.uploadedBytes());
            var blocksUpdate = updateDelta(
                    orderBlocks, orderBlocksShadow, orderBlocksUsedBytes, orderLayout.blocks(), Integer.BYTES);
            orderBlocksUsedBytes = blocksUpdate.usedBytes();
            uploaded = Math.addExact(uploaded, blocksUpdate.uploadedBytes());
            orderBlockCount = orderLayout.blockCount();
            retainedCpuBytes += orderedCommandsUsedBytes + orderBlocksUsedBytes;
            lastDeltaUploadBytes = uploaded;
            return uploaded;
        }

        private void rebuildCommandMetricPrefixes(List<Command> commands) {
            var required = Math.addExact(commands.size(), 1);
            if (commandInstancePrefix.length != required) {
                commandInstancePrefix = new int[required];
                commandTrianglePrefix = new long[required];
            } else {
                commandInstancePrefix[0] = 0;
                commandTrianglePrefix[0] = 0L;
            }
            for (var index = 0; index < commands.size(); index++) {
                var command = commands.get(index);
                commandInstancePrefix[index + 1] = Math.addExact(commandInstancePrefix[index], command.maxInstances());
                commandTrianglePrefix[index + 1] = Math.addExact(
                        commandTrianglePrefix[index],
                        Math.multiplyExact((long) command.primitive().indexCount() / 3L, command.maxInstances()));
            }
        }

        private void updateMaterialTable(
                float elapsedSeconds,
                boolean texturesEnabled,
                int shadowVariant,
                boolean orthographic,
                LongToDoubleFunction semanticDepthBias) {
            if (semanticDepthBias == null) {
                throw new IllegalArgumentException("GPU material table requires a depth-bias resolver");
            }
            var bytes = Math.multiplyExact(materialRecords.size(), MATERIAL_RECORD_BYTES);
            var rebuildAll = !materialTableInitialized
                    || materialUsedBytes != bytes
                    || materialTexturesEnabled != texturesEnabled
                    || materialShadowVariant != shadowVariant
                    || materialOrthographic != orthographic;
            if (rebuildAll) {
                rebuildMaterialTable(elapsedSeconds, texturesEnabled, shadowVariant, semanticDepthBias, bytes);
                materialTableInitialized = true;
                materialTexturesEnabled = texturesEnabled;
                materialShadowVariant = shadowVariant;
                materialOrthographic = orthographic;
            } else {
                updateTimeDynamicMaterialRecords(elapsedSeconds, texturesEnabled, shadowVariant, semanticDepthBias);
            }
            retainedCpuBytes = (long) boundsUsedBytes
                    + instancesUsedBytes
                    + commandsUsedBytes
                    + materialUsedBytes
                    + orderedCommandsUsedBytes
                    + orderBlocksUsedBytes
                    + commandHierarchy.getRetainedBytes();
        }

        private void rebuildMaterialTable(
                float elapsedSeconds,
                boolean texturesEnabled,
                int shadowVariant,
                LongToDoubleFunction semanticDepthBias,
                int bytes) {
            if (bytes == 0) {
                materialUsedBytes = 0;
                return;
            }
            var target = materialScratch.duplicate().order(ByteOrder.nativeOrder());
            target.clear();
            target.limit(bytes);
            var floats = target.asFloatBuffer();
            for (var record : materialRecords) {
                writeMaterialRecord(record, floats, elapsedSeconds, texturesEnabled, shadowVariant, semanticDepthBias);
            }
            var update = updateDelta(
                    materialTable,
                    materialShadow,
                    materialUsedBytes,
                    target,
                    BgfxSceneVertexLayouts.MATERIAL_TABLE.getStride());
            materialUsedBytes = update.usedBytes();
        }

        private void updateTimeDynamicMaterialRecords(
                float elapsedSeconds,
                boolean texturesEnabled,
                int shadowVariant,
                LongToDoubleFunction semanticDepthBias) {
            for (var recordIndex : timeDynamicMaterialRecords) {
                materialRecordScratch.clear();
                materialRecordFloatScratch.clear();
                writeMaterialRecord(
                        materialRecords.get(recordIndex),
                        materialRecordFloatScratch,
                        elapsedSeconds,
                        texturesEnabled,
                        shadowVariant,
                        semanticDepthBias);
                if (doesMaterialRecordMatch(materialShadow, recordIndex, materialRecordScratch)) {
                    continue;
                }
                materialTable.update(Math.multiplyExact(recordIndex, MATERIAL_RECORD_STRIDE), materialRecordScratch);
                copyMaterialRecord(materialShadow, recordIndex, materialRecordScratch);
            }
        }

        private int checkedCommandEnd(int firstCommand, int commandCount) {
            if (firstCommand < 0 || commandCount < 0 || firstCommand > commandsList.size() - commandCount) {
                throw new IllegalArgumentException("invalid command metric range");
            }
            return firstCommand + commandCount;
        }

        private void writeDecorationSlots(BgfxSceneObject pool, int first, int count, float decorationMultiplier) {
            var seeds = decorationSeeds.get(pool);
            if (seeds == null) {
                return;
            }
            var boundsStride = BgfxSceneVertexLayouts.VISIBILITY_BOUNDS.getStride();
            var instanceStride = BgfxSceneVertexLayouts.INSTANCE.getStride();
            var boundsData = ByteBuffer.allocateDirect(Math.multiplyExact(count, boundsStride))
                    .order(ByteOrder.nativeOrder());
            var instanceData = ByteBuffer.allocateDirect(Math.multiplyExact(count, instanceStride))
                    .order(ByteOrder.nativeOrder());
            for (var index = 0; index < seeds.length; index += 4) {
                var primitive = pool.getGeometry().getPrimitives().get(seeds[index]);
                boundsData.clear();
                instanceData.clear();
                for (var slot = first; slot < first + count; slot++) {
                    putCandidate(
                            boundsData,
                            instanceData,
                            pool,
                            primitive,
                            slot,
                            seeds[index + 2],
                            seeds[index + 3],
                            decorationMultiplier);
                }
                boundsData.flip();
                instanceData.flip();
                var candidate = seeds[index + 1] + first;
                boundsShadow
                        .duplicate()
                        .position(Math.multiplyExact(candidate, boundsStride))
                        .put(boundsData.duplicate());
                bounds.update(candidate, boundsData.duplicate().order(ByteOrder.nativeOrder()));
                instancesShadow
                        .duplicate()
                        .position(Math.multiplyExact(candidate, instanceStride))
                        .put(instanceData.duplicate());
                instancesIn.update(candidate, instanceData.duplicate().order(ByteOrder.nativeOrder()));
            }
        }

        private int withdraw(Collection<BgfxSceneObject> objects) {
            var stride = BgfxSceneVertexLayouts.INSTANCE.getStride();
            ByteBuffer zeros = null;
            var withdrawn = 0;
            for (var object : objects) {
                var ranges = candidatesByObject.remove(object);
                eligible.remove(object);
                commandsByObject.remove(object);
                decorationSeeds.remove(object);
                if (ranges == null) {
                    continue;
                }
                for (var i = 0; i < ranges.length; i += 2) {
                    var bytes = Math.multiplyExact(ranges[i + 1], stride);
                    if (zeros == null || zeros.capacity() < bytes) {
                        zeros = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
                    }
                    var zero = zeros.duplicate();
                    zero.clear();
                    zero.limit(bytes);
                    instancesShadow
                            .duplicate()
                            .position(Math.multiplyExact(ranges[i], stride))
                            .put(zero.duplicate());
                    instancesIn.update(ranges[i], zero.slice().order(ByteOrder.nativeOrder()));
                }
                withdrawn++;
            }
            return withdrawn;
        }

        private static Topology create(
                BgfxDevice device,
                List<Command> commandsList,
                List<Group> groups,
                List<MaterialRecord> materialRecords,
                ByteBuffer boundsData,
                ByteBuffer instanceData,
                ByteBuffer commandData,
                int candidateCount,
                double multiGroupCoverage,
                int candidateCapacity,
                int commandCapacity,
                int materialCapacity,
                int commandHierarchyLeafCommands) {
            BgfxBuffer bounds = null;
            BgfxBuffer instancesIn = null;
            BgfxBuffer commands = null;
            BgfxBuffer counts = null;
            BgfxBuffer instancesOut = null;
            BgfxBuffer orderedCommands = null;
            BgfxBuffer orderBlocks = null;
            BgfxBuffer orderMasks = null;
            BgfxBuffer orderBlockCounts = null;
            BgfxBuffer materialTable = null;
            BgfxIndirectBuffer indirect = null;
            Topology result = null;
            try {
                var resources = device.getResources();
                bounds = BgfxBuffer.dynamicVertex(
                        resources,
                        "visibility/bounds",
                        candidateCapacity,
                        BgfxSceneVertexLayouts.VISIBILITY_BOUNDS,
                        BgfxBuffer.gpuResident(device.getCapabilities(), BGFX_BUFFER_COMPUTE_READ));
                instancesIn = BgfxBuffer.dynamicVertex(
                        resources,
                        "visibility/instances-in",
                        candidateCapacity,
                        BgfxSceneVertexLayouts.INSTANCE,
                        BgfxBuffer.gpuResident(device.getCapabilities(), BGFX_BUFFER_COMPUTE_READ));
                commands = BgfxBuffer.dynamicIndex(
                        resources,
                        "visibility/commands",
                        commandMetadataElements(commandCapacity, materialCapacity),
                        true,
                        BgfxBuffer.gpuResident(device.getCapabilities(), BGFX_BUFFER_COMPUTE_READ));
                counts = BgfxBuffer.dynamicIndex(
                        resources, "visibility/counts", commandCapacity, true, BGFX_BUFFER_COMPUTE_READ_WRITE);
                instancesOut = BgfxBuffer.dynamicVertex(
                        resources,
                        "visibility/instances-out",
                        candidateCapacity,
                        BgfxSceneVertexLayouts.INSTANCE,
                        BGFX_BUFFER_COMPUTE_READ_WRITE);
                orderedCommands = BgfxBuffer.dynamicIndex(
                        resources,
                        "visibility/ordered-commands",
                        Math.multiplyExact(commandCapacity, BgfxVisibilityOrder.COMMAND_WORDS),
                        true,
                        BgfxBuffer.gpuResident(device.getCapabilities(), BGFX_BUFFER_COMPUTE_READ));
                var orderBlockCapacity = BgfxVisibilityOrder.blockCapacity(candidateCapacity, commandCapacity);
                orderBlocks = BgfxBuffer.dynamicIndex(
                        resources,
                        "visibility/order-blocks",
                        orderBlockCapacity,
                        true,
                        BgfxBuffer.gpuResident(device.getCapabilities(), BGFX_BUFFER_COMPUTE_READ));
                orderMasks = BgfxBuffer.dynamicIndex(
                        resources,
                        "visibility/order-masks",
                        BgfxVisibilityOrder.maskCapacity(candidateCapacity, commandCapacity),
                        true,
                        BGFX_BUFFER_COMPUTE_READ_WRITE);
                orderBlockCounts = BgfxBuffer.dynamicIndex(
                        resources,
                        "visibility/order-block-counts",
                        orderBlockCapacity,
                        true,
                        BGFX_BUFFER_COMPUTE_READ_WRITE);
                materialTable = BgfxBuffer.dynamicVertex(
                        resources,
                        "visibility/material-table",
                        Math.multiplyExact(materialCapacity, MATERIAL_RECORD_STRIDE),
                        BgfxSceneVertexLayouts.MATERIAL_TABLE,
                        BgfxBuffer.gpuResident(device.getCapabilities(), BGFX_BUFFER_COMPUTE_READ));
                indirect = BgfxIndirectBuffer.create(
                        resources, device.getCapabilities(), "visibility/indirect", commandCapacity);
                result = new Topology(
                        bounds,
                        instancesIn,
                        commands,
                        counts,
                        instancesOut,
                        orderedCommands,
                        orderBlocks,
                        orderMasks,
                        orderBlockCounts,
                        materialTable,
                        indirect,
                        candidateCapacity,
                        commandCapacity,
                        materialCapacity,
                        commandHierarchyLeafCommands);
                result.update(
                        commandsList,
                        groups,
                        materialRecords,
                        boundsData,
                        instanceData,
                        commandData,
                        candidateCount,
                        multiGroupCoverage);
                return result;
            } catch (RuntimeException | Error e) {
                closeAfterFailure(result, e);
                if (result == null) {
                    closeAfterFailure(indirect, e);
                    closeAfterFailure(materialTable, e);
                    closeAfterFailure(orderBlockCounts, e);
                    closeAfterFailure(orderMasks, e);
                    closeAfterFailure(orderBlocks, e);
                    closeAfterFailure(orderedCommands, e);
                    closeAfterFailure(instancesOut, e);
                    closeAfterFailure(counts, e);
                    closeAfterFailure(commands, e);
                    closeAfterFailure(instancesIn, e);
                    closeAfterFailure(bounds, e);
                }
                throw e;
            }
        }

        private static int[] timeDynamicMaterialRecords(List<MaterialRecord> records) {
            var count = 0;
            for (var record : records) {
                if (record.material().isGpuRecordTimeDynamic()) {
                    count++;
                }
            }
            var result = new int[count];
            var offset = 0;
            for (var index = 0; index < records.size(); index++) {
                if (records.get(index).material().isGpuRecordTimeDynamic()) {
                    result[offset++] = index;
                }
            }
            return result;
        }

        private static boolean doesMaterialRecordMatch(ByteBuffer shadow, int recordIndex, ByteBuffer candidate) {
            var base = Math.multiplyExact(recordIndex, MATERIAL_RECORD_BYTES);
            for (var offset = 0; offset < MATERIAL_RECORD_BYTES; offset += Long.BYTES) {
                if (shadow.getLong(base + offset) != candidate.getLong(offset)) {
                    return false;
                }
            }
            return true;
        }

        private static void copyMaterialRecord(ByteBuffer shadow, int recordIndex, ByteBuffer source) {
            var base = Math.multiplyExact(recordIndex, MATERIAL_RECORD_BYTES);
            for (var offset = 0; offset < MATERIAL_RECORD_BYTES; offset += Long.BYTES) {
                shadow.putLong(base + offset, source.getLong(offset));
            }
        }

        private static DeltaUpdate updateDelta(
                BgfxBuffer target, ByteBuffer shadow, int previousBytes, ByteBuffer nextData, int stride) {
            var next = nextData.duplicate().order(ByteOrder.nativeOrder());
            var nextBytes = next.remaining();
            var range = deltaUploadRange(shadow, previousBytes, next, stride);
            var uploaded = 0L;
            if (range != null) {
                var upload = next.duplicate();
                upload.position(Math.multiplyExact(range.firstElement(), stride));
                upload.limit(Math.multiplyExact(range.endElement(), stride));
                target.update(range.firstElement(), upload.slice().order(ByteOrder.nativeOrder()));
                uploaded = (long) (range.endElement() - range.firstElement()) * stride;
            }
            var destination = shadow.duplicate();
            destination.clear();
            destination.limit(nextBytes);
            destination.put(next);
            return new DeltaUpdate(nextBytes, uploaded);
        }

        private static ByteBuffer directCapacity(int elements, int stride) {
            return ByteBuffer.allocateDirect(Math.multiplyExact(elements, stride))
                    .order(ByteOrder.nativeOrder());
        }

        private static int[] getResidualPrimitiveOrdinals(BgfxSceneObject object, boolean[] included) {
            var primitives = object.getGeometry().getPrimitives();
            var residualCount = 0;
            for (var ordinal = 0; ordinal < primitives.size(); ordinal++) {
                var material = primitives.get(ordinal).entityMaterial();
                if ((included == null || !included[ordinal])
                        && (material == null || material.getBlendMode() != BlendMode.INVISIBLE)) {
                    residualCount++;
                }
            }
            var residual = new int[residualCount];
            var next = 0;
            for (var ordinal = 0; ordinal < primitives.size(); ordinal++) {
                var material = primitives.get(ordinal).entityMaterial();
                if ((included == null || !included[ordinal])
                        && (material == null || material.getBlendMode() != BlendMode.INVISIBLE)) {
                    residual[next++] = ordinal;
                }
            }
            return residual;
        }
    }

    record Seed(
            BgfxSceneObject object, int primitiveOrdinal, RenderFamily family, int materialRecord, int topologyFlags) {
        Primitive primitive() {
            return object.getGeometry().getPrimitives().get(primitiveOrdinal);
        }

        int instanceCount() {
            return object.getGeometry().getKind().isInstanced() ? object.getInstanceCount() : 1;
        }
    }

    private static final class GroupBuilder {
        private final Seed first;
        private final List<Seed> seeds = new ArrayList<>();

        private GroupBuilder(Seed first) {
            this.first = first;
        }

        private int totalInstances() {
            var result = 0;
            for (var seed : seeds) {
                result = Math.addExact(result, seed.instanceCount());
            }
            return result;
        }

        private long totalTriangles() {
            var result = 0L;
            for (var seed : seeds) {
                result = Math.addExact(result, (long) seed.primitive().indexCount() / 3L * seed.instanceCount());
            }
            return result;
        }
    }

    private static List<DrawRangeBuilder> drawRanges(GroupBuilder builder) {
        if (isOrderSensitive(builder.first.primitive())) {
            var ordered = new ArrayList<DrawRangeBuilder>(builder.seeds.size());
            for (var seed : builder.seeds) {
                var range = new DrawRangeBuilder(seed);
                range.seeds.add(seed);
                ordered.add(range);
            }
            return ordered;
        }
        var coalesced = new LinkedHashMap<DrawRangeKey, DrawRangeBuilder>();
        for (var seed : builder.seeds) {
            coalesced
                    .computeIfAbsent(new DrawRangeKey(seed), ignored -> new DrawRangeBuilder(seed))
                    .seeds
                    .add(seed);
        }
        return List.copyOf(coalesced.values());
    }

    private static boolean isSameOrderedGpuBatch(Seed left, Seed right) {
        var leftObject = left.object();
        var rightObject = right.object();
        if (!isOrderSensitive(left.primitive())
                || !isOrderSensitive(right.primitive())
                || leftObject.getGeometry().getKind()
                        != rightObject.getGeometry().getKind()
                || left.family() != right.family()
                || leftObject.getGeometry().getIndirectBinding()
                        != rightObject.getGeometry().getIndirectBinding()) {
            return false;
        }
        return left.primitive()
                .entityMaterial()
                .isSameGpuBatch(
                        right.primitive().entityMaterial(), leftObject.getLightmap(), rightObject.getLightmap());
    }

    private record DrawRangeKey(IndirectBinding binding, int vertexOffset, int indexOffset, int indexCount) {
        private DrawRangeKey(Seed seed) {
            this(
                    seed.object().getGeometry().getIndirectBinding(),
                    seed.object().getGeometry().getIndirectVertexOffset(),
                    seed.object().getGeometry().getIndirectIndexOffset()
                            + seed.primitive().indexOffset(),
                    seed.primitive().indexCount());
        }
    }

    private static final class DrawRangeBuilder {
        private final Seed first;
        private final List<Seed> seeds = new ArrayList<>();

        private DrawRangeBuilder(Seed first) {
            this.first = first;
        }

        private int totalInstances() {
            var result = 0;
            for (var seed : seeds) {
                result = Math.addExact(result, seed.instanceCount());
            }
            return result;
        }

        private Bounds3f worldBounds() {
            var bounds = first.object().getPrimitiveBounds(first.primitiveOrdinal());
            for (var index = 1; index < seeds.size(); index++) {
                var seed = seeds.get(index);
                bounds = union(bounds, seed.object().getPrimitiveBounds(seed.primitiveOrdinal()));
            }
            return bounds;
        }
    }

    private static Bounds3f union(Bounds3f left, Bounds3f right) {
        return new Bounds3f(
                Math.min(left.minX(), right.minX()),
                Math.min(left.minY(), right.minY()),
                Math.min(left.minZ(), right.minZ()),
                Math.max(left.maxX(), right.maxX()),
                Math.max(left.maxY(), right.maxY()),
                Math.max(left.maxZ(), right.maxZ()));
    }

    static final class MaterialRecordKey {
        private final BgfxSceneObject object;
        private final BgfxSceneMaterial material;
        private final int hash;

        MaterialRecordKey(BgfxSceneObject object, BgfxSceneMaterial material) {
            this.object = object;
            this.material = material;
            hash = Objects.hash(
                    material.getBindingHash(),
                    object.getLightmap(),
                    object.usesVertexLightmapLayers(),
                    object.getLightmapVariantCount(),
                    object.getLightmapVariantStride(),
                    object.getLightmapVariantSlots(),
                    object.isFoliage());
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof MaterialRecordKey key
                            && material.isSameBinding(key.material)
                            && Objects.equals(object.getLightmap(), key.object.getLightmap())
                            && object.usesVertexLightmapLayers() == key.object.usesVertexLightmapLayers()
                            && object.getLightmapVariantCount() == key.object.getLightmapVariantCount()
                            && object.getLightmapVariantStride() == key.object.getLightmapVariantStride()
                            && object.getLightmapVariantSlots().equals(key.object.getLightmapVariantSlots())
                            && object.isFoliage() == key.object.isFoliage();
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    record MaterialRecord(BgfxSceneObject object, BgfxSceneMaterial material) {}

    static final class GroupKey {
        private final Seed seed;
        private final int hash;

        GroupKey(Seed seed) {
            this.seed = seed;
            var object = seed.object;
            var kind = object.getGeometry().getKind();
            var result = 31 * kind.hashCode() + seed.family.hashCode();
            result = 31 * result + object.getLightmapScale().hashCode();
            result = 31 * result + System.identityHashCode(object.getGeometry().getIndirectBinding());
            if (kind == Kind.TERRAIN || isOrderSensitive(seed.primitive())) {
                result = 31 * result + System.identityHashCode(object);
                result = 31 * result + seed.primitiveOrdinal;
            } else {
                result = 31 * result + seed.primitive().entityMaterial().computeGpuBatchHash(object.getLightmap());
            }
            hash = result;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof GroupKey key)) {
                return false;
            }
            var left = seed.object;
            var right = key.seed.object;
            var kind = left.getGeometry().getKind();
            if (kind != right.getGeometry().getKind()
                    || seed.family != key.seed.family
                    || !left.getLightmapScale().equals(right.getLightmapScale())
                    || left.getGeometry().getIndirectBinding()
                            != right.getGeometry().getIndirectBinding()) {
                return false;
            }
            if (kind == Kind.TERRAIN || isOrderSensitive(seed.primitive()) || isOrderSensitive(key.seed.primitive())) {
                return left == right && seed.primitiveOrdinal == key.seed.primitiveOrdinal;
            }
            return seed.primitive()
                    .entityMaterial()
                    .isSameGpuBatch(key.seed.primitive().entityMaterial(), left.getLightmap(), right.getLightmap());
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static boolean isOrderSensitive(Primitive primitive) {
        var material = primitive.entityMaterial();
        return material != null && material.getBlendMode() == BlendMode.MASKED_BLEND;
    }

    private static final class CapacityOverflow extends RuntimeException {
        private CapacityOverflow(String message) {
            super(message);
        }
    }

    private static final class NoCompatibleTopology extends RuntimeException {
        private NoCompatibleTopology(String message) {
            super(message);
        }
    }
}
