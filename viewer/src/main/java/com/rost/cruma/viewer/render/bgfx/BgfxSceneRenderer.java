package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_DEPTH;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_STENCIL;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_BINDINGS;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_STATE;
import static org.lwjgl.bgfx.BGFX.BGFX_INVALID_HANDLE;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIP_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_MIRROR;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_MIRROR;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_BLIT_DST;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_VIEW_MODE_DEFAULT;
import static org.lwjgl.bgfx.BGFX.BGFX_VIEW_MODE_SEQUENTIAL;
import static org.lwjgl.bgfx.BGFX.bgfx_blit;
import static org.lwjgl.bgfx.BGFX.bgfx_discard;
import static org.lwjgl.bgfx.BGFX.bgfx_set_instance_data_from_dynamic_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_palette_color_rgba8;
import static org.lwjgl.bgfx.BGFX.bgfx_set_scissor;
import static org.lwjgl.bgfx.BGFX.bgfx_set_state;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_clear;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_clear_mrt;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_mode;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_name;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_transform;
import static org.lwjgl.bgfx.BGFX.bgfx_submit;
import static org.lwjgl.bgfx.BGFX.bgfx_submit_indirect;
import static org.lwjgl.bgfx.BGFX.bgfx_touch;

import com.rost.cruma.core.editing.ActorTransform;
import com.rost.cruma.core.editing.TerrainEditorGeometry;
import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.geometry.Frustum;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.Entity;
import com.rost.cruma.core.world.Entity.BlendMode;
import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.Entity.Mesh;
import com.rost.cruma.core.world.Entity.SourcePart;
import com.rost.cruma.core.world.Entity.StaticMeshLod;
import com.rost.cruma.core.world.Entity.SurfaceType;
import com.rost.cruma.core.world.Entity.TerrainAlphaAtlas;
import com.rost.cruma.core.world.Entity.TerrainMesh;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.L2Movement;
import com.rost.cruma.core.world.Light;
import com.rost.cruma.core.world.MaterialClient;
import com.rost.cruma.core.world.NativeLightmapScale;
import com.rost.cruma.core.world.NativeWaterReflection;
import com.rost.cruma.core.world.SkyZones;
import com.rost.cruma.core.world.TextureFilters;
import com.rost.cruma.core.world.UMap;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.UnrealLoader.DecorationDensity;
import com.rost.cruma.core.world.UnrealLoader.DecorationGround;
import com.rost.cruma.core.world.UnrealLoader.DecorationTemplate;
import com.rost.cruma.core.world.ZoneStateChoice;
import com.rost.cruma.viewer.config.Config.Viewer.World;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.CoronaSystem;
import com.rost.cruma.viewer.render.ParticleEmitter;
import com.rost.cruma.viewer.render.RenderingContext;
import com.rost.cruma.viewer.render.SceneExtent;
import com.rost.cruma.viewer.render.ShaderHeightCut;
import com.rost.cruma.viewer.render.ViewMode;
import com.rost.cruma.viewer.render.WaterRenderRoute;
import com.rost.cruma.viewer.render.api.DebugMaterialSlot;
import com.rost.cruma.viewer.render.api.DebugSceneHit;
import com.rost.cruma.viewer.render.api.DebugSceneObject;
import com.rost.cruma.viewer.render.api.DebugSceneObject.Category;
import com.rost.cruma.viewer.render.api.RenderDiagnostics.CpuPhase;
import com.rost.cruma.viewer.render.api.RenderDiagnostics.Visibility;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.api.RenderFrameSnapshot.RenderFamily;
import com.rost.cruma.viewer.render.api.RenderSettings;
import com.rost.cruma.viewer.render.api.RenderState;
import com.rost.cruma.viewer.render.api.SceneUploadStats;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import com.rost.cruma.viewer.render.bgfx.BgfxFxRenderer.DrawCounts;
import com.rost.cruma.viewer.render.bgfx.BgfxFxRenderer.RenderBucket;
import com.rost.cruma.viewer.render.bgfx.BgfxGeometryPool.IndirectBinding;
import com.rost.cruma.viewer.render.bgfx.BgfxGpuVisibility.Command;
import com.rost.cruma.viewer.render.bgfx.BgfxGpuVisibility.Group;
import com.rost.cruma.viewer.render.bgfx.BgfxGpuVisibility.SubmissionSpan;
import com.rost.cruma.viewer.render.bgfx.BgfxGpuVisibility.Topology;
import com.rost.cruma.viewer.render.bgfx.BgfxMapProjectorRenderer.ProjectedDraw;
import com.rost.cruma.viewer.render.bgfx.BgfxPreparedScenePayload.EntityPayload;
import com.rost.cruma.viewer.render.bgfx.BgfxReflectionCache.Entry;
import com.rost.cruma.viewer.render.bgfx.BgfxReflectionCache.Key;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneGeometry.Kind;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneGeometry.Prepared;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneGeometry.Primitive;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneMaterial.GpuTextureBindingCache;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneMaterial.ProgramFamily;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneMaterial.WaterParameters;
import com.rost.cruma.viewer.render.bgfx.BgfxTextureCache.ArrayPageMemory;
import com.rost.cruma.viewer.render.bgfx.BgfxTextureCache.TextureRef;
import com.rost.cruma.viewer.render.bgfx.BgfxWaterTexture.Binding;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.Platform;

final class BgfxSceneRenderer implements AutoCloseable {
    static final String GLOBAL_RESIDENCY_OWNER = "\u0000viewer-global";

    static final float PICK_TOLERANCE = 1.5f;

    static final float PICK_TOLERANCE_PER_UNIT = 0.0001f;

    static final String EDITOR_SURFACES = ViewerRenderBackend.EDITOR_SURFACES;

    private static final int STREAMING_TOPOLOGY_BATCH_MAPS = 32;
    private static final int STREAMING_TOPOLOGY_MAX_DEFER_FRAMES = 120;
    private static final int EDITOR_TOPOLOGY_JOURNAL = 64;
    private static final Extent ZERO_EXTENT = new Extent(0, 0);
    private static final Extent ONE_EXTENT = new Extent(1, 1);
    private static final float REFLECTION_WORKING_SET_SQ_RADIUS = 49_152f * 49_152f;

    private static final int REFLECTION_REFRESH_VIEW_COST = 8;

    private static final boolean DEBUG_GPU_GROUP_SORT = Boolean.getBoolean("cruma.viewer.bgfx.debug_gpu_group_sort");
    private static final boolean CACHED_GPU_GROUP_SORT = !Boolean.getBoolean("cruma.viewer.bgfx.direct_gpu_group_sort");
    private static final boolean VERIFY_GPU_GROUP_SORT = Boolean.getBoolean("cruma.viewer.bgfx.verify_gpu_group_sort");
    private static final boolean DIRECT_DRAW_PRIMITIVE_LOOKUP =
            Boolean.getBoolean("cruma.viewer.bgfx.direct_draw_primitive_lookup");
    private static final boolean RETAIN_WATER_TEXTURE_BINDINGS =
            Boolean.parseBoolean(System.getProperty("cruma.viewer.bgfx.water_texture_binding_cache", "true"));
    private static final int WATER_TEXTURE_BINDING_CYCLE_FRAMES =
            Math.max(0, Integer.getInteger("cruma.viewer.bgfx.water_texture_binding_cache_cycle_frames", 0));
    private static final boolean EXACT_WATER_INSTANCE_BATCHES =
            Boolean.getBoolean("cruma.viewer.bgfx.exact_water_instance_batches");
    private static final boolean RETAIN_SEQUENTIAL_TEXTURE_BINDINGS = Boolean.parseBoolean(System.getProperty(
            "cruma.viewer.bgfx.sequential_texture_binding_cache",
            Boolean.toString(Platform.get() == Platform.WINDOWS)));
    private static final boolean RESIDUAL_TRAVERSAL_HIERARCHY =
            Boolean.getBoolean("cruma.viewer.bgfx.residual_traversal_hierarchy");
    private static final int RESIDUAL_TRAVERSAL_CYCLE_FRAMES =
            Math.max(0, Integer.getInteger("cruma.viewer.bgfx.residual_traversal_cycle_frames", 0));
    private static final int RESIDUAL_TRAVERSAL_AUDIT_FRAMES =
            Math.max(0, Integer.getInteger("cruma.viewer.bgfx.residual_traversal_audit_frames", 0));
    private static final boolean DEBUG_MOVEMENT_TIMINGS =
            Boolean.getBoolean("cruma.viewer.bgfx.debug_movement_timings");
    private static final ThreadMXBean MOVEMENT_THREAD_TIMINGS =
            DEBUG_MOVEMENT_TIMINGS ? ManagementFactory.getThreadMXBean() : null;
    private static final int GPU_GROUP_SORT_WORKERS = Math.clamp(
            Integer.getInteger(
                    "cruma.viewer.bgfx.group_sort_workers",
                    Math.min(4, Runtime.getRuntime().availableProcessors())),
            1,
            4);
    private static final Comparator<DrawCommand> OPAQUE_ORDER = Comparator.comparingInt(
                    (DrawCommand command) -> opaqueProgramBin(command.primitive()))
            .thenComparingInt(command -> opaqueBindingBin(command.primitive()))
            .thenComparingLong(command -> opaqueStateBin(command.primitive()))
            .thenComparingDouble(command -> command.distanceSquared)
            .thenComparingLong(command -> command.primitive().stableId())
            .thenComparingLong(command -> command.object.getStableId());
    private static final Comparator<DrawCommand> TRANSLUCENT_ORDER = Comparator.comparingDouble(
                    (DrawCommand command) -> command.distanceSquared)
            .reversed()
            .thenComparingLong(command -> command.primitive().stableId());

    private static final Comparator<DrawCommand> OTHERS_ORDER = Comparator.comparingInt(BgfxSceneRenderer::othersRank)
            .thenComparingDouble(command -> command.distanceSquared);

    private static final boolean DEBUG_DRAW_LIST = Boolean.getBoolean("cruma.viewer.bgfx.debug_drawlist");

    private static final Logger log = LogManager.getLogger(BgfxSceneRenderer.class);
    private static final long SCENE_BOUNDS_NANOS = 1_000_000_000L;
    private static final float WIDEST_GROUPED_OBJECT = 40_000f;

    private final double gpuGroupSortCellSize;
    private final BgfxDevice device;
    private final RenderingContext renderingContext;
    private final RenderState state;
    private final ViewerGraphicsConfig graphics;
    private final BgfxPerformanceTracker performance;
    private final BgfxMetalRayQueries metalRayQueries;
    private final FloatBuffer mainProjectionBuffer = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer reflectionProjectionBuffer = BufferUtils.createFloatBuffer(16);
    private final BgfxFrameEnvironment environment;
    private final BgfxCoverageDebug coverageDebug;
    private final BgfxGeometryPool geometryPool;
    private final BgfxInstancePool instancePool;
    private final BgfxActorLightingBuffer actorLightingBuffer;
    private final BgfxNativeVertexLightmaps nativeVertexLightmaps;
    private final BgfxTextureCache textures;
    private final BgfxSceneUniforms uniforms;
    private BgfxShadowRenderer shadowRenderer;
    private BgfxLocalShadowAtlas localShadowAtlas;
    private final BgfxLocalLightSet localLightSet = new BgfxLocalLightSet();
    private final BgfxNativeLightSources nativeLightSources;
    private final BgfxNativeWaterLighting nativeWaterLighting;
    private BgfxNativeWaterLighting.View mainNativeWaterView;
    private final BgfxNativeMeshDraws nativeMeshDraws;
    private BgfxNativeMeshDraws.View mainNativeMeshView;
    private final BgfxSpawnModelLighting spawnModelLighting;
    private @Nullable BgfxNativeView spawnModelView;
    private double spawnModelSeconds;
    private BgfxSpawnModelLighting.Environment spawnModelEnvironment = BgfxSpawnModelLighting.Environment.NONE;
    private @Nullable Submission mainSubmission;
    private long nativeLightDiagnosticKey = Long.MIN_VALUE;
    private BgfxLocalLightSet.Snapshot localLightSnapshot = BgfxLocalLightSet.Snapshot.disabled();
    private boolean shadowResourcesAttempted;
    private boolean localShadowResourcesAttempted;
    private final BgfxScenePrograms programs;
    private final BgfxMapProjectorRenderer mapProjectors;
    private final BgfxFxRenderer fxRenderer;
    private final BgfxCoronaRenderer coronaRenderer;
    private final BgfxCoronaLineTests coronaLineTests;
    private double coronaClock;
    private final BgfxSkyRenderer skyRenderer;
    private final BgfxReflectionCache reflections;
    private @Nullable BgfxWireframe wireframe;
    private boolean wireframeUnsupported;
    private final BgfxGpuVisibility gpuVisibility;
    private TextureRef waterNormal;
    private TextureRef waterLegacyBump;
    private BgfxWaterTexture waterTexture;
    private Vec4 waterColorReference = new Vec4(0f, 0f, 0f, 0f);
    private float waterReflectionReference;
    private float waterBumpAmplitude;
    private boolean waterLinearFiltering;
    private float textureMipBias;
    private TextureFilters waterNormalFilters = TextureFilters.INHERITED;

    private Extent waterReflectionExtent = new Extent(256, 256);

    private final Map<String, IdentityHashMap<Mesh, BgfxSceneGeometry>> geometryByOwnerIdentity = new HashMap<>();
    private final Map<String, Map<Integer, BgfxSceneGeometry>> bspGeometryByOwner = new HashMap<>();
    private final Map<String, BgfxSceneGeometry> geometryByName = new HashMap<>();
    private final List<BgfxSceneGeometry> geometries = new ArrayList<>();
    private final ObjectList objects = new ObjectList();
    private @Nullable DebugSceneObject hovered;
    private boolean hoveredWholeActor;
    private @Nullable BgfxHoverHighlight hoverHighlight;
    private boolean hoverHighlightFailed;
    private final Set<BgfxSceneObject> isolatedObjects = Collections.newSetFromMap(new IdentityHashMap<>());
    private long isolatedObjectsRevision = -1;
    private @Nullable Bounds3f sceneBounds;
    private int sceneBoundsObjects = -1;
    private long sceneBoundsNanos;
    private final List<BgfxSceneObject> movingObjects = new ArrayList<>();
    private final List<ClearanceGroup> clearanceGroups = new ArrayList<>();
    private int clearanceGroupsChanges = -1;
    private int clearanceIgnoredChanges;
    private long zoneStateShownRevision;
    private final BgfxSceneZones sceneZones = new BgfxSceneZones();
    private int runFirst = -1;
    private int runCount;
    private long movingObjectsRevision = Long.MIN_VALUE;
    private final Map<String, List<BgfxSceneObject>> stagedObjects = new LinkedHashMap<>();

    private final Map<String, List<Light>> customLightSources = new HashMap<>();

    private final Set<String> detailedTerrainOwners = new HashSet<>();

    private final Set<String> reportedRenderTargetFailures = new HashSet<>();
    private boolean detailedTerrainOwnersDirty = true;
    private final Map<String, List<BgfxSceneObject>> terrainDetails = new HashMap<>();
    private final Map<String, List<BgfxSceneObject>> terrainProxies = new HashMap<>();
    private final Map<String, List<BgfxSceneObject>> terrainSectors = new HashMap<>();

    private final List<BgfxSceneObject> gpuResidualObjects = new ArrayList<>();

    private final List<int[]> gpuResidualPrimitiveOrdinals = new ArrayList<>();
    private final BitSet projectorReplayedCommands = new BitSet();
    private long sceneObjectsRevision;
    private long movementRevision;
    private EditorScene editorScene;
    private long editorUploadRevision;
    private long editorTransformRevision;
    private final BgfxDecorations decorations = new BgfxDecorations(new DecorationHost());
    private long decorationRevision;
    private final LinkedHashMap<Long, Set<String>> editorTopologyActors = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Set<String>> eldest) {
            return size() > EDITOR_TOPOLOGY_JOURNAL;
        }
    };
    private long editorClosedTopologyGeneration = Long.MIN_VALUE;
    private final IdentityHashMap<BgfxSceneObject, Boolean> retiredObjects = new IdentityHashMap<>();
    private long retiredTopologyGeneration = Long.MIN_VALUE;
    private final Set<String> movedOwners = new HashSet<>();
    private @Nullable ZoneStateChoice appliedZoneState;
    private long zoneStateSceneRevision = Long.MIN_VALUE;
    private long appliedZoneFadesRevision = Long.MIN_VALUE;
    private final List<BgfxSceneObject> fadingObjects = new ArrayList<>();
    private int fadeFactor;
    private final List<DrawCommand> fadingOpaque = new ArrayList<>();
    private long gpuResidualObjectsRevision = Long.MIN_VALUE;
    private long gpuResidualTopologyGeneration = Long.MIN_VALUE;
    private final List<BgfxSceneObject> traversalObjects = new ArrayList<>();
    private final List<int[]> traversalPrimitiveOrdinals = new ArrayList<>();
    private final BgfxResidualTraversal residualTraversal = new BgfxResidualTraversal();
    private long residualTraversalSceneRevision = Long.MIN_VALUE;
    private long residualTraversalTopologyGeneration = Long.MIN_VALUE;
    private boolean traversalCacheValid;
    private long traversalSceneRevision = Long.MIN_VALUE;
    private long traversalTopologyGeneration = Long.MIN_VALUE;
    private Frustum traversalFrustum;
    private boolean traversalDeferredOpaque;
    private boolean traversalUnculledReference;
    private boolean traversalHierarchical;
    private int traversalFrustumCulled;
    private final List<DrawCommand> candidates = new ArrayList<>();
    private final List<DrawCommand> opaqueCandidates = new ArrayList<>();
    private final List<DrawCommand> opaque = new ArrayList<>();
    private final List<DrawCommand> translucent = new ArrayList<>();
    private final List<DrawCommand> water = new ArrayList<>();
    private final List<DrawCommand> othersCandidates = new ArrayList<>();
    private final List<DrawCommand> others = new ArrayList<>();
    private final List<DrawCommand> commandPool = new ArrayList<>();
    private final List<Command> gpuCommandOrder = new ArrayList<>();
    private final List<Group> gpuGroupOrder = new ArrayList<>();
    private final List<Group> gpuPlannedGroupOrder = new ArrayList<>();
    private final List<Command> reflectionGpuCommands = new ArrayList<>();
    private final List<Group> reflectionGpuGroups = new ArrayList<>();
    private final List<DrawCommand> reflectionDirectOpaque = new ArrayList<>();
    private final List<DrawCommand> reflectionDirectTranslucent = new ArrayList<>();
    private final Map<Key, WaterGroup> waterGroups = new LinkedHashMap<>();
    private final List<WaterGroup> activeWaterGroups = new ArrayList<>();
    private final Set<DrawCommand> reflectedWaterCommands = Collections.newSetFromMap(new IdentityHashMap<>());
    private final IdentityHashMap<BgfxSceneObject, Key[]> reflectionKeysByObject = new IdentityHashMap<>();
    private final float[] frameUniforms = new float[BgfxSceneUniforms.FRAME_VECTORS * 4];
    private final float[] gpuFrameUniformCache = new float[BgfxSceneUniforms.FRAME_VECTORS * 4];
    private final Submission mainSubmissionState = new Submission();
    private final Submission reflectionSubmissionState = new Submission();
    private final Submission waterSubmissionState = new Submission();
    private final Submission othersSubmissionState = new Submission();
    private @Nullable Supplier<BgfxFramebuffer> othersTarget;
    private int othersFactor;
    private DrawCommand[] drawSortScratch = new DrawCommand[0];
    private Command[] gpuCommandSortScratch = new Command[0];
    private Group[] gpuGroupSortScratch = new Group[0];
    private final BgfxGroupSortKeys gpuGroupSortKeys = new BgfxGroupSortKeys();
    private long gpuGroupOrderTopologyGeneration = Long.MIN_VALUE;
    private long gpuGroupOrderCameraCellX = Long.MIN_VALUE;
    private long gpuGroupOrderCameraCellY = Long.MIN_VALUE;
    private long gpuGroupOrderCameraCellZ = Long.MIN_VALUE;
    private long gpuPlannedGroupOrderRevision = Long.MIN_VALUE;
    private List<Group> gpuPlannedGroupOrderSource;
    private final GpuTextureBindingCache gpuTextureBindings = new GpuTextureBindingCache();
    private IndirectBinding gpuGeometryBinding;
    private Kind gpuBindingKind;
    private ProgramFamily gpuTextureProgramFamily;
    private boolean gpuMaterialTableBound;
    private boolean gpuInstanceDataBound;
    private boolean gpuFrameUniformCacheValid;
    private boolean gpuFrameUniformCacheTerrain;
    private int gpuFrameUniformCacheProgram = -1;
    private long gpuFrameUniformCacheSurfaceType = Long.MIN_VALUE;
    private BlendMode gpuFrameUniformCacheBlendMode;
    private RenderFamily gpuFrameUniformCacheFamily;
    private NativeLightmapScale gpuFrameUniformCacheLightmapScale;
    private boolean gpuFrameUniformCacheLit;

    private Map<String, MapResidency> residencyCache = Map.of();
    private long sharedRetainedCpuBytes;
    private long cachedFxResidencyRevision = -1L;
    private long cachedSkyResidencyRevision = -1L;
    private boolean residencyDirty = true;

    private Extent depthCopyExtent = ZERO_EXTENT;
    private Extent activeReflectionExtent = ZERO_EXTENT;
    private BgfxTexture depthCopy;
    private BgfxWaterBackCopy waterBackCopy;
    private int loadedMaps;
    private int uploadFailures;
    private int reflectionUpdates;
    private int reflectionReuses;
    private int reflectedFxDraws;
    private long reflectionSavedDraws;
    private long opaqueShadedPixelProxy;
    private long terrainShadedPixelProxy;
    private long maskedShadedPixelProxy;
    private long reflectionShadedPixelProxy;
    private long translucentShadedPixelProxy;
    private int visibleWaterConsumers;
    private boolean depthCopyExecuted;
    private float retainedInWaterPlaneZ;
    private int waterBackCopyView = -1;
    private boolean waterBackCopyExecuted;
    private int softParticleDepthCopies;

    private final BgfxLegacyWaterClock legacyWaterClock = new BgfxLegacyWaterClock();

    private boolean opaqueMaterialized;
    private int gpuOpaqueSubmitBatches;
    private int gpuOpaqueCommandRanges;
    private int gpuOpaqueActiveGroups;
    private boolean hasWater;
    private boolean baked;
    private int bakedObjectCount;
    private int commandPoolUsed;
    private boolean gpuTopologyDirty;

    private boolean topologyRequiresHistoryReset;

    private int pendingTopologyPublications;
    private int topologyDirtyFrames;
    private boolean closed;
    private int debugDrawListFramesLeft = DEBUG_DRAW_LIST ? 3 : 0;

    private final Map<String, List<DebugMaterialSlot>> partMaterials = new HashMap<>();
    private long partMaterialsRevision = -1;

    private BgfxSceneObject refreshedSelectionObject;
    private long refreshedSelectionRevision = -1;

    BgfxSceneRenderer(
            BgfxDevice device, RenderingContext renderingContext, RenderState state, ViewerGraphicsConfig graphics) {
        this(device, renderingContext, state, graphics, new BgfxPerformanceTracker(), null);
    }

    BgfxSceneRenderer(
            BgfxDevice device,
            RenderingContext renderingContext,
            RenderState state,
            ViewerGraphicsConfig graphics,
            BgfxPerformanceTracker performance) {
        this(device, renderingContext, state, graphics, performance, null);
    }

    BgfxSceneRenderer(
            BgfxDevice device,
            RenderingContext renderingContext,
            RenderState state,
            ViewerGraphicsConfig graphics,
            BgfxPerformanceTracker performance,
            BgfxMetalRayQueries metalRayQueries) {
        this.device = device;
        this.renderingContext = renderingContext;
        nativeLightSources = new BgfxNativeLightSources(
                renderingContext.scene.getNativeRandom(), renderingContext.scene.getVertexLightmaps()::getActor);
        nativeWaterLighting = new BgfxNativeWaterLighting(nativeLightSources);
        spawnModelLighting = new BgfxSpawnModelLighting(nativeLightSources);
        nativeMeshDraws = new BgfxNativeMeshDraws(renderingContext.scene.getMeshTangents());
        this.state = state;
        this.graphics = graphics;
        this.gpuGroupSortCellSize = graphics.tuning().gpuGroupSortCellSize();
        this.performance = performance;
        this.metalRayQueries = metalRayQueries;
        this.environment = new BgfxFrameEnvironment(renderingContext, state);
        this.coverageDebug = BgfxCoverageDebug.fromSystemProperty();
        BgfxGeometryPool createdGeometryPool = null;
        BgfxInstancePool createdInstancePool = null;
        BgfxActorLightingBuffer createdActorLightingBuffer = null;
        BgfxTextureCache createdTextures = null;
        BgfxSceneUniforms createdUniforms = null;
        BgfxScenePrograms createdPrograms = null;
        BgfxMapProjectorRenderer createdMapProjectors = null;
        BgfxFxRenderer createdFx = null;
        BgfxSkyRenderer createdSky = null;
        BgfxReflectionCache createdReflections = null;
        BgfxGpuVisibility createdGpuVisibility = null;
        try {
            createdGeometryPool = new BgfxGeometryPool(device);
            createdInstancePool = new BgfxInstancePool(device);
            createdActorLightingBuffer =
                    new BgfxActorLightingBuffer(device, graphics.tuning().actorLightingWords());
            createdTextures = new BgfxTextureCache(device, graphics.tuning(), renderingContext.scene.getNativeRandom());
            createdUniforms = new BgfxSceneUniforms(device.getResources());
            createdPrograms = new BgfxScenePrograms(device);
            createdMapProjectors = new BgfxMapProjectorRenderer(
                    device,
                    createdTextures,
                    createdPrograms.entityVertexShader,
                    createdPrograms.gpuEntityVertexShader,
                    createdPrograms.terrainVertexShader);
            createdGpuVisibility =
                    new BgfxGpuVisibility(device, renderingContext, state, graphics.optimizations(), graphics.tuning());
            createdFx = new BgfxFxRenderer(
                    device,
                    renderingContext,
                    graphics.tuning(),
                    createdGeometryPool,
                    createdActorLightingBuffer,
                    createdGpuVisibility,
                    createdTextures,
                    createdUniforms,
                    performance,
                    createdPrograms.meshPrograms,
                    createdPrograms.frameBlendMeshPrograms);
            createdSky = new BgfxSkyRenderer(device, renderingContext, state, createdTextures, performance);
            createdReflections = new BgfxReflectionCache(
                    device.getResources(), device.getCapabilities().depthStencilFormat());
        } catch (RuntimeException | Error e) {
            closeAfterFailure(createdMapProjectors, e);
            closeAfterFailure(createdGpuVisibility, e);
            closeAfterFailure(createdReflections, e);
            closeAfterFailure(createdSky, e);
            closeAfterFailure(createdFx, e);
            closeAfterFailure(createdPrograms, e);
            closeAfterFailure(createdUniforms, e);
            closeAfterFailure(createdTextures, e);
            closeAfterFailure(createdActorLightingBuffer, e);
            closeAfterFailure(createdInstancePool, e);
            closeAfterFailure(createdGeometryPool, e);
            throw e;
        }
        geometryPool = createdGeometryPool;
        instancePool = createdInstancePool;
        actorLightingBuffer = createdActorLightingBuffer;
        nativeVertexLightmaps = new BgfxNativeVertexLightmaps(actorLightingBuffer);
        textures = createdTextures;
        uniforms = createdUniforms;
        programs = createdPrograms;
        state.stats.customSunShadowsAvailable = BgfxShadowRenderer.isSupported(device.getCapabilities());
        state.stats.customLocalLightShadowsAvailable =
                state.stats.customSunShadowsAvailable && BgfxLocalShadowAtlas.isSupported(device.getCapabilities());
        mapProjectors = createdMapProjectors;
        fxRenderer = createdFx;
        fxRenderer.useSceneZones(sceneZones);
        coronaRenderer = new BgfxCoronaRenderer(device);
        coronaLineTests = new BgfxCoronaLineTests(device);
        skyRenderer = createdSky;
        reflections = createdReflections;
        gpuVisibility = createdGpuVisibility;
    }

    @FunctionalInterface
    interface OpaqueLightingPass {
        BgfxTexture process(BgfxFrameEnvironment.Snapshot environment);
    }

    @FunctionalInterface
    interface ActorPass {
        void render(BgfxTexture targetColor, BgfxTexture targetDepth, Extent extent, BgfxViewAllocator views);
    }

    static boolean isSequentialTextureBindingCacheEnabled() {
        return RETAIN_SEQUENTIAL_TEXTURE_BINDINGS;
    }

    static boolean isResidualTraversalModeRequested(long frameNumber) {
        if (RESIDUAL_TRAVERSAL_CYCLE_FRAMES == 0) {
            return RESIDUAL_TRAVERSAL_HIERARCHY;
        }
        var phase = frameNumber / RESIDUAL_TRAVERSAL_CYCLE_FRAMES % 4;
        return phase == 0 || phase == 3;
    }

    static Extent reflectionExtent(int effectResolution) {
        if (effectResolution < 1) {
            throw new IllegalArgumentException("water effect resolution must be positive, got " + effectResolution);
        }
        return new Extent(effectResolution, effectResolution);
    }

    static boolean isType2TerrainDecorationVisible(int decorationZone, int excludedTerrainZone) {
        return decorationZone >= 0 && decorationZone != excludedTerrainZone;
    }

    static String describeLighting(
            boolean beast,
            int lightmapBaseLayer,
            float beastSunWeight,
            float beastStaticWeight,
            float ambientRed,
            float ambientGreen,
            float ambientBlue,
            boolean actorLighting,
            boolean lightmapBound,
            int lightmapVariants,
            boolean terrainLighting) {
        if (beast) {
            if (!lightmapBound) {
                return "Beast placement without a bound bake array";
            }
            return String.format(
                    Locale.ROOT,
                    "Beast bakes at page layers %d/%d, I1 %.2f, I2 %.2f, ambient %d/%d/%d, x2",
                    lightmapBaseLayer,
                    lightmapBaseLayer + 1,
                    beastSunWeight,
                    beastStaticWeight,
                    Math.round(ambientRed * 255f),
                    Math.round(ambientGreen * 255f),
                    Math.round(ambientBlue * 255f));
        }
        if (lightmapBound) {
            return lightmapVariants > 1 ? "lightmap, " + lightmapVariants + " TimeEnv variants" : "lightmap";
        }
        if (actorLighting) {
            return "vertex light stream (env masks + scene lights), x2";
        }
        if (terrainLighting) {
            return "terrain-sampled instance light";
        }
        return "flat instance light";
    }

    static String shortDebugName(String name) {
        var trimmed = name == null ? "" : name.trim();
        var separator = Math.max(
                Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\')),
                Math.max(trimmed.lastIndexOf('.'), trimmed.lastIndexOf(':')));
        var result = separator + 1 < trimmed.length() ? trimmed.substring(separator + 1) : trimmed;
        if (result.isBlank()) {
            result = "<unnamed>";
        }
        return result.length() <= 44 ? result : result.substring(0, 41) + "...";
    }

    static boolean contains(Bounds3f bounds, float x, float y, float z, float tolerance) {
        return x >= bounds.minX() - tolerance
                && x <= bounds.maxX() + tolerance
                && y >= bounds.minY() - tolerance
                && y <= bounds.maxY() + tolerance
                && z >= bounds.minZ() - tolerance
                && z <= bounds.maxZ() + tolerance;
    }

    static void putBeastLightmapWeights(float[] frame, NativeLightmapScale scale, float first, float second) {
        frame[35] = scale.resolve(first);
        frame[39] = scale.resolve(second);
    }

    static int waterFilterFlags(boolean linear) {
        return linear ? 0 : BGFX_SAMPLER_MIN_POINT | BGFX_SAMPLER_MAG_POINT | BGFX_SAMPLER_MIP_POINT;
    }

    static boolean shouldRetainWaterTextureBindings(long frameNumber) {
        return RETAIN_WATER_TEXTURE_BINDINGS
                && (WATER_TEXTURE_BINDING_CYCLE_FRAMES == 0
                        || frameNumber / WATER_TEXTURE_BINDING_CYCLE_FRAMES % 2 == 0);
    }

    static float semanticDepthBias(long surfaceType, boolean orthographic, boolean homogeneousDepth) {
        if (orthographic || (surfaceType & SurfaceType.CSG.getId()) == 0) {
            return 0f;
        }
        var windowUnits = Entity.csgDepthLayer(surfaceType) * 32f / 16_777_215f;
        return homogeneousDepth ? windowUnits * 2f : windowUnits;
    }

    static boolean isSurfaceEnabled(long type, RenderSettings settings) {
        if ((type & SurfaceType.FAKE_BACKDROP.getId()) != 0) {
            return false;
        }
        if ((type & SurfaceType.PASSABLE.getId()) != 0 && !settings.isPassableEnabled()) {
            return false;
        }
        if ((type & SurfaceType.TERRAIN.getId()) != 0 && !settings.isTerrainEnabled()) {
            return false;
        }
        if ((type & SurfaceType.STATIC_MESH.getId()) != 0 && !settings.isStaticMeshesEnabled()) {
            return false;
        }
        if ((type & SurfaceType.CSG.getId()) != 0 && !settings.isCsgEnabled()) {
            return false;
        }
        return (type & SurfaceType.BOUNDING_BOX.getId()) == 0;
    }

    private enum ScreenRectangle {
        EMPTY,
        PARTIAL,
        FULL
    }

    static float[] stableIdColor(long stableId) {
        var result = new float[3];
        putStableIdColor(stableId, result, 0);
        return result;
    }

    void beginFrame() {
        requireOpen();
        if (clearanceGroupsChanges != getClearanceChanges() && !clearanceGroups.isEmpty()) {
            clearanceGroups.clear();
            clearanceGroupsChanges = -1;
        }
        fxRenderer.beginFrame();
        uniforms.beginView(false);
        textures.advanceFrame(renderingContext.pictureClock.getSeconds());
        geometryPool.advanceFrame();
        instancePool.advanceFrame();
        actorLightingBuffer.advanceFrame();
        if (metalRayQueries != null) {
            metalRayQueries.pollEditorRetirements();
        }
    }

    void loadWorldEnvironment(World worldConfig) {
        environment.load(worldConfig);
        nativeLightSources.configure(environment.getShadowTickInterval());
        nativeLightDiagnosticKey = Long.MIN_VALUE;
        configureWater(worldConfig);
        residencyDirty = true;
    }

    void loadSky(World worldConfig) {
        skyRenderer.load(worldConfig);
        residencyDirty = true;
    }

    void registerMeshEmitter(String owner, ParticleEmitter emitter, EntityMesh source) {
        fxRenderer.registerMeshEmitter(owner, emitter, source);
        residencyDirty = true;
    }

    void registerMeshEmitter(String owner, ParticleEmitter emitter, EntityMesh source, Prepared prepared) {
        fxRenderer.registerMeshEmitter(owner, emitter, source, prepared);
        residencyDirty = true;
    }

    void registerAnimatedMeshEmitter(
            String owner, ParticleEmitter emitter, List<EntityMesh> frames, List<Prepared> preparedFrames) {
        fxRenderer.registerAnimatedMeshEmitter(owner, emitter, frames, preparedFrames);
        residencyDirty = true;
    }

    void unregisterMeshEmitter(String owner, ParticleEmitter emitter) {
        fxRenderer.unregisterMeshEmitter(owner, emitter);
        residencyDirty = true;
    }

    boolean attachMeshTwin(ParticleEmitter source, ParticleEmitter twin) {
        var shared = fxRenderer.attachMeshTwin(source, twin);
        residencyDirty = true;
        return shared;
    }

    void detachMeshTwin(ParticleEmitter twin) {
        fxRenderer.detachMeshTwin(twin);
        residencyDirty = true;
    }

    Texture registerParticleTexture(String owner, Texture source, String label) {
        var registered = fxRenderer.registerParticleTexture(owner, source, label);
        residencyDirty = true;
        return registered;
    }

    void unregisterParticleTexture(String owner, Texture source) {
        fxRenderer.unregisterParticleTexture(owner, source);
        residencyDirty = true;
    }

    void unregisterParticleTexture(String owner, ParticleEmitter emitter, Texture source) {
        fxRenderer.unregisterParticleTexture(owner, emitter, source);
        residencyDirty = true;
    }

    void upload(UMap map) {
        upload(map, null);
    }

    void upload(UMap map, BgfxPreparedScenePayload payload) {
        requireOpen();
        if (map == null) {
            throw new IllegalArgumentException("map cannot be null");
        }
        if (payload != null && payload.getEntityCount() != map.entities().size()) {
            throw new IllegalArgumentException("prepared payload does not match map " + map.name());
        }
        skyRenderer.registerLocalSky(map);
        var uploadStarted = performance.start();
        if (payload == null) {
            for (var ordinal = 0; ordinal < map.entities().size(); ordinal++) {
                uploadEntity(map, ordinal);
            }
        } else {
            textures.withPrepared(payload.getTextures(), () -> {
                for (var ordinal = 0; ordinal < map.entities().size(); ordinal++) {
                    uploadEntity(map, ordinal, payload.getEntity(ordinal));
                }
            });
        }
        finishUpload(map);
        recordUploadNanos(performance.getElapsed(uploadStarted));
    }

    int uploadPart(UMap map, BgfxPreparedScenePayload payload, int first, long nanos) {
        requireOpen();
        if (map == null
                || payload == null
                || payload.getEntityCount() != map.entities().size()) {
            throw new IllegalArgumentException("prepared payload does not match map");
        }
        if (first == 0) {
            skyRenderer.registerLocalSky(map);
        }
        var uploadStarted = performance.start();
        var started = System.nanoTime();
        var count = map.entities().size();
        var next = new int[] {first};
        textures.withPrepared(payload.getTextures(), () -> {
            while (next[0] < count && System.nanoTime() - started < nanos) {
                uploadEntity(map, next[0], payload.getEntity(next[0]));
                next[0]++;
            }
        });
        if (next[0] == count) {
            finishUpload(map);
        }
        recordUploadNanos(performance.getElapsed(uploadStarted));
        return next[0];
    }

    void uploadEntity(UMap map, int ordinal) {
        uploadEntity(map, ordinal, null);
    }

    void uploadEntity(UMap map, int ordinal, EntityPayload prepared) {
        requireOpen();
        if (map == null || ordinal < 0 || ordinal >= map.entities().size()) {
            throw new IllegalArgumentException("invalid incremental map upload entity");
        }
        if (prepared != null && prepared.ordinal() != ordinal) {
            throw new IllegalArgumentException("prepared entity payload ordinal does not match upload");
        }
        var source = map.entities().get(ordinal);
        BgfxSceneGeometry geometry = null;
        Prepared geometryPayload = prepared == null ? null : prepared.geometry();
        var created = false;
        try {
            var meshName = source.getMeshName();
            var namedMesh = meshName != null && !meshName.isBlank();
            var sharedGeometryKey = namedMesh ? BgfxSceneGeometry.sharedCacheKey(source) : null;
            var identityCache = namedMesh
                    ? null
                    : geometryByOwnerIdentity.computeIfAbsent(map.name(), ignored -> new IdentityHashMap<>());
            if (namedMesh) {
                geometry = geometryByName.get(sharedGeometryKey);
            } else {
                geometry = identityCache.get(source.getMesh());
            }
            if (geometry == null) {
                if (geometryPayload == null) {
                    geometryPayload = BgfxSceneGeometry.prepare(source, map.name(), ordinal);
                }
                geometry = BgfxSceneGeometry.install(geometryPool, textures, source, geometryPayload);
                created = true;
            }
            var objectPayload = prepared == null
                    ? BgfxSceneObject.prepare(source, geometry, map.name(), ordinal)
                    : prepared.object();
            var object = BgfxSceneObject.install(
                    instancePool, actorLightingBuffer, textures, source, geometry, objectPayload, nativeLightSources);
            if (source.getMotionLink() != null && source.getMovement() == null) {
                object.bindMotion(source, renderingContext.swings.createMotion(map.name(), source.getMotionLink()));
            }
            if (source.getMovement() != null && source.getSourceActor() != null) {
                object.replaceMotion(renderingContext.swings.createMovementMotion(
                        map.name(), source.getSourceActor(), source.getMovement()));
            }
            object.setNativeVertexLightmap(
                    renderingContext.scene.getVertexLightmaps().bind(map.name(), source));
            if (object.getNativeVertexLightmap() != null) {
                nativeVertexLightmaps.register(
                        map.name(), object.getNativeVertexLightmap().getInstanceId());
            }
            if (metalRayQueries != null) {
                if (prepared == null || prepared.rayData()) {
                    metalRayQueries.register(
                            source,
                            geometryPayload,
                            objectPayload,
                            created,
                            prepared == null ? null : prepared.giColors());
                } else {
                    metalRayQueries.leftOut();
                }
            }
            if (created) {
                geometries.add(geometry);
                if (namedMesh) {
                    geometryByName.put(sharedGeometryKey, geometry);
                } else {
                    identityCache.put(source.getMesh(), geometry);
                }
            }
            if (source.getBspOrdinal() >= 0) {
                bspGeometryByOwner
                        .computeIfAbsent(map.name(), ignored -> new HashMap<>())
                        .put(source.getBspOrdinal(), geometry);
            }
            stagedObjects
                    .computeIfAbsent(map.name(), ignored -> new ArrayList<>())
                    .add(object);
            residencyDirty = true;
        } catch (RuntimeException | Error e) {
            uploadFailures++;
            if (created && geometry != null) {
                closeAfterFailure(geometry, e);
            }
            log.error("Failed to install {} entity {} ({})", map.name(), ordinal, source.getDebugName(), e);
            throw e;
        }
    }

    void withPreparedTextures(BgfxPreparedTexturePayloads payload, Runnable action) {
        textures.withPrepared(payload, action);
    }

    void finishUpload(UMap map) {
        requireOpen();
        mapProjectors.publish(map.name(), map.projectors());
        if (map.lights().isEmpty()) {
            customLightSources.remove(map.name());
        } else {
            customLightSources.put(map.name(), List.copyOf(map.lights()));
        }
        localLightSet.publish(map.name(), map.lights(), map.animatedLights());
        nativeLightSources.publish(map.name(), map.nativeLights());
        if (localShadowAtlas != null) {
            localShadowAtlas.publish(map.name(), map.lights());
        }
        var staged = stagedObjects.remove(map.name());
        if (staged != null && !staged.isEmpty()) {
            topologyRequiresHistoryReset |= replacesTerrainProxy(map.name(), staged);
            objects.addAll(staged);
            markSceneObjectsChanged();
            hasWater |= staged.stream()
                    .flatMap(object -> object.getGeometry().getPrimitives().stream())
                    .anyMatch(primitive -> primitive.entityMaterial() != null
                            && primitive.entityMaterial().getProgramFamily() == ProgramFamily.WATER);
        }
        decorations.register(map);
        loadedMaps++;
        baked = false;
        bakeInstances();
        renderingContext.restoreEditedMapPreview(map.name());
    }

    void recordUploadNanos(long nanos) {
        performance.addPhase(CpuPhase.RESOURCE_UPLOAD, nanos);
    }

    void invalidateVisibilityHistory(String reason) {
        gpuVisibility.invalidateHistory(reason);
    }

    Map<String, MapResidency> getResidencyByOwner() {
        if (!residencyDirty
                && cachedFxResidencyRevision == fxRenderer.getResidencyRevision()
                && cachedSkyResidencyRevision == skyRenderer.getResidencyRevision()) {
            return residencyCache;
        }
        var owned = new HashMap<String, MutableResidency>();
        var geometryOwners = new IdentityHashMap<BgfxSceneGeometry, Set<String>>();
        var textureOwners = new HashMap<TextureRef, Set<String>>();
        for (var object : objects) {
            var owner = object.getOwner();
            var bytes = owned.computeIfAbsent(owner, ignored -> new MutableResidency());
            bytes.retainedCpuBytes = Math.addExact(bytes.retainedCpuBytes, object.getRetainedCpuBytes());
            bytes.geometryGpuBytes = Math.addExact(bytes.geometryGpuBytes, object.getEstimatedGpuBytes());
            geometryOwners
                    .computeIfAbsent(object.getGeometry(), ignored -> new HashSet<>())
                    .add(owner);
            addTextureOwner(textureOwners, object.getLightmap(), owner);
        }
        for (var staged : stagedObjects.values()) {
            for (var object : staged) {
                var owner = object.getOwner();
                var bytes = owned.computeIfAbsent(owner, ignored -> new MutableResidency());
                bytes.retainedCpuBytes = Math.addExact(bytes.retainedCpuBytes, object.getRetainedCpuBytes());
                bytes.geometryGpuBytes = Math.addExact(bytes.geometryGpuBytes, object.getEstimatedGpuBytes());
                geometryOwners
                        .computeIfAbsent(object.getGeometry(), ignored -> new HashSet<>())
                        .add(owner);
                addTextureOwner(textureOwners, object.getLightmap(), owner);
            }
        }

        long sharedCpu = 0L;
        var geometryTextures = new HashSet<TextureRef>();
        for (var entry : geometryOwners.entrySet()) {
            var geometry = entry.getKey();
            var owners = entry.getValue();
            if (owners.size() == 1) {
                var owner = owners.iterator().next();
                var bytes = owned.computeIfAbsent(owner, ignored -> new MutableResidency());
                bytes.retainedCpuBytes = Math.addExact(bytes.retainedCpuBytes, geometry.getRetainedCpuBytes());
                bytes.geometryGpuBytes = Math.addExact(bytes.geometryGpuBytes, geometry.getEstimatedGpuBytes());
            } else {
                sharedCpu = Math.addExact(sharedCpu, geometry.getRetainedCpuBytes());
            }
            geometryTextures.clear();
            geometry.collectTextures(geometryTextures);
            for (var texture : geometryTextures) {
                for (var owner : owners) {
                    addTextureOwner(textureOwners, texture, owner);
                }
            }
        }

        for (var entry : fxRenderer.getResidency(textureOwners).entrySet()) {
            if (GLOBAL_RESIDENCY_OWNER.equals(entry.getKey())) {
                continue;
            }
            var target = owned.computeIfAbsent(entry.getKey(), ignored -> new MutableResidency());
            target.retainedCpuBytes =
                    Math.addExact(target.retainedCpuBytes, entry.getValue().retainedCpuBytes());
            target.geometryGpuBytes =
                    Math.addExact(target.geometryGpuBytes, entry.getValue().geometryGpuBytes());
        }

        var globalTextures = new HashSet<TextureRef>();
        skyRenderer.collectTextures(globalTextures);
        if (waterNormal != null) {
            globalTextures.add(waterNormal);
        }
        if (waterLegacyBump != null) {
            globalTextures.add(waterLegacyBump);
        }
        if (waterTexture != null) {
            waterTexture.collectTextures(globalTextures);
        }
        for (var texture : globalTextures) {
            addTextureOwner(textureOwners, texture, GLOBAL_RESIDENCY_OWNER);
        }
        for (var entry : textureOwners.entrySet()) {
            var texture = entry.getKey();
            var owners = entry.getValue();
            if (texture.isBaseline() || owners.size() != 1) {
                continue;
            }
            var owner = owners.iterator().next();
            if (GLOBAL_RESIDENCY_OWNER.equals(owner)) {
                continue;
            }
            var target = owned.computeIfAbsent(owner, ignored -> new MutableResidency());
            target.textureGpuBytes = Math.addExact(target.textureGpuBytes, texture.getEstimatedBytes());
        }

        var immutable = new HashMap<String, MapResidency>(owned.size());
        for (var entry : owned.entrySet()) {
            immutable.put(entry.getKey(), entry.getValue().snapshot());
        }
        residencyCache = Map.copyOf(immutable);
        sharedRetainedCpuBytes = sharedCpu;
        cachedFxResidencyRevision = fxRenderer.getResidencyRevision();
        cachedSkyResidencyRevision = skyRenderer.getResidencyRevision();
        residencyDirty = false;
        return residencyCache;
    }

    long getNonEvictableCpuBytes() {
        getResidencyByOwner();
        return Math.addExact(sharedRetainedCpuBytes, fxRenderer.getNonEvictableCpuBytes());
    }

    boolean evictMap(String owner) {
        return evictMaps(Set.of(owner));
    }

    boolean evictMaps(Set<String> owners) {
        return evictMaps(owners, false);
    }

    boolean replaceMaps(Set<String> owners) {
        return evictMaps(owners, true);
    }

    void bakeInstances() {
        requireOpen();
        if (baked) {
            return;
        }
        var topologyStarted = performance.start();
        var groups = new LinkedHashMap<InstanceGroupKey, List<BgfxSceneObject>>();
        var prefix = Math.min(bakedObjectCount, objects.size());
        var result = new ArrayList<BgfxSceneObject>(objects.size());
        result.addAll(objects.subList(0, prefix));
        for (var objectIndex = prefix; objectIndex < objects.size(); objectIndex++) {
            var object = objects.get(objectIndex);
            if (object.getGeometry().getKind() == Kind.TERRAIN
                    || object.isMoving()
                    || object.getNativeStaticMeshDraw() != null) {
                object.publishDeferredInstances(instancePool);
                result.add(object);
                continue;
            }
            groups.computeIfAbsent(
                            new InstanceGroupKey(
                                    object.getOwner(),
                                    object.getGeometry(),
                                    object.getLightmap(),
                                    object.usesVertexLightmapLayers(),
                                    object.getLightmapVariantCount(),
                                    object.getLightmapVariantStride(),
                                    object.getLightmapVariantSlots(),
                                    object.hasTerrainLighting(),
                                    object.getTerrainDecorationZone(),
                                    object.getDecorationFadeMax(),
                                    object.isWireframe(),
                                    object.acceptsProjectors(),
                                    object.getStaticMeshLod(),
                                    object.hasNativeStaticMeshAmbient(),
                                    object.getLightmapScale(),
                                    object.isZoneLimited()),
                            _ -> new ArrayList<>())
                    .add(object);
        }
        var merged = new ArrayList<BgfxSceneObject>();
        try {
            for (var group : groups.values()) {
                if (group.size() == 1) {
                    var object = group.getFirst();
                    object.publishDeferredInstances(instancePool);
                    result.add(object);
                } else {
                    var object = BgfxSceneObject.merge(instancePool, group);
                    merged.add(object);
                    result.add(object);
                }
            }
        } catch (RuntimeException | Error e) {
            for (var object : merged) {
                closeAfterFailure(object, e);
            }
            throw e;
        }
        for (var group : groups.values()) {
            if (group.size() > 1) {
                for (var object : group) {
                    reflectionKeysByObject.remove(object);
                    object.close();
                }
            }
        }
        objects.clear();
        objects.addAll(result);
        if (editorScene != null && editorScene.originals != null) {
            editorScene.originals.addAll(result.subList(prefix, result.size()));
        }
        markSceneObjectsChanged();
        bakedObjectCount = objects.size();
        baked = true;
        residencyDirty = true;
        var replacedWaterOwner = groups.values().stream()
                .filter(group -> group.size() > 1)
                .flatMap(List::stream)
                .anyMatch(BgfxSceneRenderer::ownsWaterPrimitive);
        if (replacedWaterOwner) {
            retainReflectionOwners();
        }
        if (!gpuTopologyDirty) {
            topologyDirtyFrames = 0;
        }
        gpuTopologyDirty = true;
        pendingTopologyPublications = Math.addExact(pendingTopologyPublications, 1);
        log.debug("Instance bake: {} groups, {} scene objects", groups.size(), objects.size());
        performance.addPhase(CpuPhase.TOPOLOGY_UPDATE, performance.getElapsed(topologyStarted));
    }

    void flushPendingTopology() {
        flushPendingTopology(true);
    }

    void render(
            BgfxFramebuffer target,
            BgfxTexture targetDepth,
            Extent extent,
            BgfxViewAllocator views,
            float elapsedSeconds) {
        render(target, null, null, null, targetDepth, extent, views, elapsedSeconds, 0f, false, null);
    }

    void render(
            BgfxFramebuffer target,
            BgfxTexture targetDepth,
            Extent extent,
            BgfxViewAllocator views,
            float elapsedSeconds,
            float deltaSeconds,
            boolean effectsEnabled) {
        render(
                target,
                null,
                null,
                null,
                targetDepth,
                extent,
                views,
                elapsedSeconds,
                deltaSeconds,
                effectsEnabled,
                null);
    }

    void render(
            BgfxFramebuffer target,
            BgfxTexture targetColor,
            BgfxTexture targetDepth,
            Extent extent,
            BgfxViewAllocator views,
            float elapsedSeconds,
            float deltaSeconds,
            boolean effectsEnabled) {
        render(
                target,
                targetColor,
                null,
                null,
                targetDepth,
                extent,
                views,
                elapsedSeconds,
                deltaSeconds,
                effectsEnabled,
                null);
    }

    void render(
            BgfxFramebuffer target,
            BgfxTexture targetColor,
            BgfxTexture targetPackedDepth,
            BgfxTexture targetDepth,
            Extent extent,
            BgfxViewAllocator views,
            float elapsedSeconds,
            float deltaSeconds,
            boolean effectsEnabled) {
        render(
                target,
                targetColor,
                targetPackedDepth,
                null,
                targetDepth,
                extent,
                views,
                elapsedSeconds,
                deltaSeconds,
                effectsEnabled,
                null);
    }

    void render(
            BgfxFramebuffer target,
            BgfxTexture targetColor,
            BgfxTexture targetPackedDepth,
            BgfxTexture targetNormal,
            BgfxTexture targetDepth,
            Extent extent,
            BgfxViewAllocator views,
            float elapsedSeconds,
            float deltaSeconds,
            boolean effectsEnabled) {
        render(
                target,
                targetColor,
                targetPackedDepth,
                targetNormal,
                targetDepth,
                extent,
                views,
                elapsedSeconds,
                deltaSeconds,
                effectsEnabled,
                null);
    }

    BgfxFrameEnvironment.Snapshot render(
            BgfxFramebuffer target,
            BgfxTexture targetColor,
            BgfxTexture targetPackedDepth,
            BgfxTexture targetNormal,
            BgfxTexture targetDepth,
            Extent extent,
            BgfxViewAllocator views,
            float elapsedSeconds,
            float deltaSeconds,
            boolean effectsEnabled,
            OpaqueLightingPass opaqueLightingPass) {
        return render(
                target,
                targetColor,
                targetPackedDepth,
                targetNormal,
                targetDepth,
                extent,
                views,
                elapsedSeconds,
                deltaSeconds,
                effectsEnabled,
                opaqueLightingPass,
                null);
    }

    BgfxFrameEnvironment.Snapshot render(
            BgfxFramebuffer target,
            BgfxTexture targetColor,
            BgfxTexture targetPackedDepth,
            BgfxTexture targetNormal,
            BgfxTexture targetDepth,
            Extent extent,
            BgfxViewAllocator views,
            float elapsedSeconds,
            float deltaSeconds,
            boolean effectsEnabled,
            OpaqueLightingPass opaqueLightingPass,
            @Nullable ActorPass actorPass) {
        requireOpen();
        uniforms.beginFrame(device.getStats().frameNumber());
        if (extent == null || extent.isZero()) {
            return null;
        }
        var started = performance.start();
        if (state.loading.active) {
            return renderLoadingFrame(target, targetPackedDepth, extent, views, started, elapsedSeconds);
        }
        updateDecorations();
        if (gpuTopologyDirty) {
            topologyDirtyFrames = Math.addExact(topologyDirtyFrames, 1);
            flushPendingTopology(!gpuVisibility.isAvailable()
                    || pendingTopologyPublications >= STREAMING_TOPOLOGY_BATCH_MAPS
                    || topologyDirtyFrames >= STREAMING_TOPOLOGY_MAX_DEFER_FRAMES);
        }
        releaseRetiredObjects();
        var movementStarted = performance.start();
        var movementCpuStarted = DEBUG_MOVEMENT_TIMINGS && MOVEMENT_THREAD_TIMINGS.isThreadCpuTimeEnabled()
                ? MOVEMENT_THREAD_TIMINGS.getCurrentThreadCpuTime()
                : -1L;
        var movementCopies = DEBUG_MOVEMENT_TIMINGS ? BgfxMemoryCopy.snapshot() : null;
        if (movingObjectsRevision != sceneObjectsRevision) {
            movingObjects.clear();
            for (var object : objects) {
                if (object.isMoving()) {
                    movingObjects.add(object);
                }
            }
            movingObjectsRevision = sceneObjectsRevision;
        }
        movedOwners.clear();
        if (editorScene != null && !editorScene.movedOwners.isEmpty()) {
            movedOwners.addAll(editorScene.movedOwners);
            editorScene.movedOwners.clear();
        }
        renderingContext.swings.advance(deltaSeconds);
        for (var object : movingObjects) {
            if (object.advanceMovement(deltaSeconds, instancePool)) {
                movedOwners.add(object.getOwner());
                if (metalRayQueries != null) {
                    metalRayQueries.queueGiMovement(object);
                }
            }
        }
        advanceZoneFades(deltaSeconds);
        applyZoneStates();
        advanceSkyZones(deltaSeconds);
        if (!movedOwners.isEmpty()) {
            movementRevision++;
            traversalCacheValid = false;
        }
        var movementElapsed = performance.getElapsed(movementStarted);
        performance.addPhase(CpuPhase.MOVEMENT_UPDATE, movementElapsed);
        if (DEBUG_MOVEMENT_TIMINGS && movementElapsed > 1_500_000L) {
            var cpuElapsed = movementCpuStarted >= 0L
                    ? MOVEMENT_THREAD_TIMINGS.getCurrentThreadCpuTime() - movementCpuStarted
                    : -1L;
            var copies = BgfxMemoryCopy.snapshot().since(movementCopies);
            log.info(
                    "Movement timing: previousFrame={} wallMs={} cpuMs={} copies={} copyMs={} objects={}",
                    device.getStats().frameNumber(),
                    movementElapsed / 1_000_000.0,
                    cpuElapsed < 0L ? Double.NaN : cpuElapsed / 1_000_000.0,
                    copies.calls(),
                    copies.nanos() / 1_000_000.0,
                    movingObjects.size());
        }
        var drawRevision = sceneObjectsRevision + movementRevision;
        var unculledStableIdReference = coverageDebug.isUnculledStableIdReference();
        var visibilityCpuAudit = Boolean.getBoolean("cruma.viewer.bgfx.visibility_cpu_audit");
        var wireframeView = renderingContext.getViewMode() == ViewMode.WIREFRAME && ensureWireframe();
        sceneZones.update(
                renderingContext.blackBackdrop
                        ? nativeLightSources.reach(BgfxNativeView.main(renderingContext.camera))
                        : null);
        if (sceneZones.isActive()) {
            gpuVisibility.skipFrame();
        }
        var gpuOpaque = !wireframeView
                && !renderingContext.inspection.isIsolated()
                && !visibilityCpuAudit
                && !sceneZones.isActive()
                && !unculledStableIdReference
                && gpuVisibility.prepare(targetDepth, extent, views);
        var deferOpaqueVisibility = gpuOpaque;
        var activeWaterRoute = waterRoute();
        buildCommands(deferOpaqueVisibility, unculledStableIdReference, activeWaterRoute, extent);

        var encodingStarted = performance.start();
        var camera = renderingContext.camera;
        var frameEnvironment = environment.resolve(effectsEnabled, deltaSeconds);
        refreshNativeLights(elapsedSeconds, deltaSeconds, true);
        fxRenderer.configureFrameEnvironment(frameEnvironment, textureMipBias);
        var mainView = firstSixteenFloats(camera.getViewMatrix());
        var mainProjection = BgfxMatrices.toBackendProjection(
                camera.getProjectionMatrix(), device.getCapabilities().homogeneousDepth(), mainProjectionBuffer);
        var fxEnabled = effectsEnabled && !state.loading.active;
        var softParticleShaders = state.settings.isL2WaterShaderEnabled();
        var softFxActive = fxEnabled && softParticleShaders && fxRenderer.hasSoftParticles(RenderBucket.ALL);
        var depthMrt = targetPackedDepth != null
                && (activeWaterRoute == 1 && !water.isEmpty()
                        || softFxActive
                        || state.settings.isObjectPickerEnabled()
                        || state.isCustomRealisticLightingEffective()
                        || coronaLineTests.isDue(coronaClock + Math.max(0f, deltaSeconds)));
        var nativeView = BgfxNativeView.main(camera);
        mainNativeWaterView = nativeWaterLighting.open(nativeView);
        spawnModelView = nativeView;
        spawnModelSeconds = elapsedSeconds;
        spawnModelEnvironment = frameEnvironment == null
                ? BgfxSpawnModelLighting.Environment.NONE
                : new BgfxSpawnModelLighting.Environment(
                        environment.getModelLightRule(),
                        frameEnvironment.actorAmbient(),
                        frameEnvironment.beastResolved().actorAmbient(),
                        frameEnvironment.beastLightmap2());
        fxRenderer.setMeshLighting(
                spawnModelLighting.open(nativeView, elapsedSeconds, spawnModelEnvironment, List.of()));
        mainNativeMeshView = nativeMeshDraws.open(
                nativeView,
                camera.isOrtho(),
                renderingContext.inspection.isIsolated(),
                state.settings.getImpostorDistanceRatio(),
                camera.isOrtho() && !camera.isInteractiveOrtho());
        var mainSubmission = mainSubmissionState.main(
                extent, camera.getPosition(), frameEnvironment, depthMrt, getMainWaterCoveragePlane(activeWaterRoute));
        mainSubmission.nativeWaterView = mainNativeWaterView;
        mainSubmission.nativeMeshView = mainNativeMeshView;
        this.mainSubmission = mainSubmission;
        if (wireframeView) {
            return renderWireframe(
                    target,
                    targetPackedDepth,
                    extent,
                    views,
                    elapsedSeconds,
                    mainView,
                    mainProjection,
                    mainSubmission,
                    frameEnvironment,
                    started,
                    encodingStarted);
        }
        ensureCustomLightingResources();
        localLightSnapshot = localLightSet.resolve(
                camera.getWorldFocusPosition(),
                state.isCustomRealisticLightingEffective() && (!camera.isOrtho() || camera.isInteractiveOrtho()),
                elapsedSeconds,
                environment.getMaterialClient());
        if (state.isCustomLightingEffective() && (shadowRenderer != null || localShadowAtlas != null)) {
            refreshDetailedTerrainOwners();
        }
        if (shadowRenderer != null) {
            shadowRenderer.render(
                    objects,
                    this::isShadowedTerrainProxy,
                    uniforms,
                    views,
                    camera,
                    state,
                    frameEnvironment,
                    elapsedSeconds,
                    sceneObjectsRevision,
                    movementRevision);
        }
        if (localShadowAtlas != null) {
            localShadowAtlas.render(
                    objects,
                    this::isShadowedTerrainProxy,
                    uniforms,
                    views,
                    camera,
                    state,
                    elapsedSeconds,
                    drawRevision);
        }
        if (gpuOpaque) {
            if (performance.isEnabled()) {
                performance.visibility(gpuVisibility.getDiagnostics(objects.size()));
            }
        } else {
            ensureCpuOpaqueCommands();
        }
        var sortingStarted = performance.start();
        if (graphics.optimizations().fillReflection() && !opaque.isEmpty()) {
            sortReorderableOpaqueSegments(opaque);
        }
        stableSortDrawCommands(translucent, 0, translucent.size(), TRANSLUCENT_ORDER);
        stableSortDrawCommands(water, 0, water.size(), TRANSLUCENT_ORDER);
        performance.addPhase(CpuPhase.SORTING, performance.getElapsed(sortingStarted));
        updateShadedPixelProxies(extent);
        var clearView = views.allocate("world-clear", BgfxViewAllocator.Domain.RENDER, false);
        setupView(clearView, "world-clear", target, extent, BGFX_VIEW_MODE_DEFAULT);
        if (targetPackedDepth != null) {
            bgfx_set_palette_color_rgba8(0, renderingContext.blackBackdrop ? 0x000000ff : 0x1a1a1aff);
            bgfx_set_palette_color_rgba8(1, 0xffffff00);
            bgfx_set_palette_color_rgba8(2, 0x00000000);
            bgfx_set_view_clear_mrt(
                    clearView,
                    BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH | BGFX_CLEAR_STENCIL,
                    1.0f,
                    0,
                    (byte) 0,
                    (byte) 1,
                    (byte) 2,
                    (byte) 2,
                    (byte) 2,
                    (byte) 0xff,
                    (byte) 0xff,
                    (byte) 0xff);
        } else {
            bgfx_set_view_clear(
                    clearView,
                    BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH | BGFX_CLEAR_STENCIL,
                    renderingContext.blackBackdrop ? 0x000000ff : 0x1a1a1aff,
                    1.0f,
                    0);
        }
        bgfx_touch(clearView);
        var submitted = skyRenderer.renderMain(target, extent, views, elapsedSeconds, effectsEnabled, frameEnvironment);
        var othersView = target == null || others.isEmpty()
                ? -1
                : views.allocate("scene-isolate-others", BgfxViewAllocator.Domain.RENDER, true);
        var opaqueView = views.allocate("scene-opaque", BgfxViewAllocator.Domain.RENDER, false);
        setupView(opaqueView, "scene-opaque", target, extent, BGFX_VIEW_MODE_SEQUENTIAL);
        bgfx_set_view_transform(opaqueView, mainView, mainProjection);
        bgfx_touch(opaqueView);
        gpuOpaqueSubmitBatches = 0;
        gpuOpaqueCommandRanges = 0;
        gpuOpaqueActiveGroups = 0;
        var opaqueEncodingStarted = performance.start();
        if (gpuOpaque) {
            submitted += submitGpuOpaque(opaqueView, elapsedSeconds, mainSubmission);
        }
        for (var command : opaque) {
            if (submit(opaqueView, command, elapsedSeconds, mainSubmission)) {
                submitted++;
            }
        }
        performance.addPhase(CpuPhase.OPAQUE_ENCODING, performance.getElapsed(opaqueEncodingStarted));

        if (opaqueLightingPass != null && targetColor != null) {
            var relit = opaqueLightingPass.process(frameEnvironment);
            if (relit != null && relit != targetColor) {
                var relightCopyView =
                        views.allocate("scene-realistic-relight-copy", BgfxViewAllocator.Domain.RENDER, false);
                bgfx_blit(
                        relightCopyView,
                        targetColor.getHandle(),
                        0,
                        0,
                        0,
                        0,
                        relit.getHandle(),
                        0,
                        0,
                        0,
                        0,
                        extent.width(),
                        extent.height(),
                        1);
            }
        }

        var projectorReceiversStarted = performance.start();
        var projectorDraws = state.isSceneLit()
                ? mapProjectors.collectDraws(objects, sceneObjectsRevision, movedOwners)
                : List.<ProjectedDraw>of();
        performance.addPhase(CpuPhase.PROJECTOR_RECEIVERS, performance.getElapsed(projectorReceiversStarted));
        if (!projectorDraws.isEmpty()) {
            var projectorView = views.allocate("scene-map-projectors", BgfxViewAllocator.Domain.RENDER, true);
            setupView(projectorView, "scene-map-projectors", target, extent, BGFX_VIEW_MODE_SEQUENTIAL);
            bgfx_set_view_transform(projectorView, mainView, mainProjection);
            bgfx_touch(projectorView);
            var projectorFrustum = state.settings.isCullingEnabled() ? renderingContext.camera.getFrustum() : null;
            var projectorViewProjection = gpuVisibility.getPreparedViewProjection();
            var projectorTopology = gpuVisibility.isPreparedThisFrame() ? gpuVisibility.getTopology() : null;
            var scissor = new int[4];
            Object replayedProjector = null;
            for (var draw : projectorDraws) {
                var primitive = draw.primitive();
                var material = primitive.entityMaterial();
                var surfaceType = material == null ? SurfaceType.TERRAIN.getId() : material.getSurfaceType();
                if (!isSurfaceEnabled(surfaceType, state.settings)) {
                    continue;
                }
                if (projectorFrustum != null && !intersects(projectorFrustum, draw.visibleBounds())) {
                    continue;
                }
                if (replayedProjector != draw.projector()) {
                    replayedProjector = draw.projector();
                    projectorReplayedCommands.clear();
                }
                var command = projectorTopology == null || material == null
                        ? null
                        : projectorTopology.getCommandFor(draw.object(), draw.primitiveOrdinal());
                if (command != null) {
                    if (projectorReplayedCommands.get(command.commandIndex())) {
                        continue;
                    }
                    projectorReplayedCommands.set(command.commandIndex());
                    if (!applyProjectorScissor(projectorViewProjection, draw.volumeBounds(), extent, scissor)) {
                        continue;
                    }
                    if (submitGpuMapProjector(
                            projectorView, command, draw, elapsedSeconds, mainSubmission, projectorTopology)) {
                        submitted++;
                    }
                    continue;
                }
                if (!isResidualReceiverDrawn(
                        draw.object(), draw.primitiveOrdinal(), primitive, material, projectorFrustum, extent)) {
                    continue;
                }
                if (!applyProjectorScissor(projectorViewProjection, draw.visibleBounds(), extent, scissor)) {
                    continue;
                }
                if (submitMapProjector(projectorView, draw, elapsedSeconds, mainSubmission)) {
                    submitted++;
                }
            }
        }

        var historyBuilt = false;
        if (actorPass != null && targetColor != null && targetDepth != null) {
            gpuVisibility.buildHistory(targetDepth, extent, views);
            historyBuilt = true;
            actorPass.render(targetColor, targetDepth, extent, views);
        }

        fxRenderer.configure(
                Math.max(0, state.settings.getParticleVisibilityDistance()),
                state.settings.getEmitterDistanceMode(),
                state.settings.getGameEventStates(),
                state.settings.getTimeOfDay());
        depthCopyExecuted = false;
        waterBackCopyExecuted = false;
        state.stats.waterBackReady = false;
        state.stats.realisticWaterStatus = !state.settings.isL2WaterShaderEnabled()
                ? "Off"
                : !state.settings.isWaterBackTexEnabled()
                        ? "Unavailable. Background texture disabled"
                        : activeWaterRoute != WaterRenderRoute.PS19
                                ? "Unavailable. Shader water route unavailable"
                                : activeWaterGroups.isEmpty()
                                        ? "Ready. No eligible water surfaces in view"
                                        : target == null || targetDepth == null
                                                ? "Unavailable. Required render targets missing"
                                                : "Active";
        softParticleDepthCopies = 0;
        reflectionUpdates = 0;
        reflectionReuses = 0;
        reflectedFxDraws = 0;
        reflectionSavedDraws = 0L;
        reflectionShadedPixelProxy = 0L;
        activeReflectionExtent = ZERO_EXTENT;
        state.stats.waterReflectionReady = false;
        var waterPassActive = activeWaterRoute != 0 && !water.isEmpty() && target != null && targetDepth != null;
        if (waterPassActive) {
            BgfxTexture immediateSoftDepth = null;
            if (targetPackedDepth != null
                    && fxEnabled
                    && softParticleShaders
                    && fxRenderer.hasSoftParticles(RenderBucket.IMMEDIATE)
                    && copyPackedDepth("fx-immediate-depth-copy", targetPackedDepth, extent, views)) {
                immediateSoftDepth = depthCopy;
                softParticleDepthCopies++;
            }
            submitted += fxRenderer.render(
                    target,
                    extent,
                    views,
                    elapsedSeconds,
                    deltaSeconds,
                    fxEnabled,
                    depthMrt,
                    RenderBucket.IMMEDIATE,
                    true,
                    immediateSoftDepth);
        }

        if (waterPassActive) {
            if (activeWaterRoute == 1
                    && targetPackedDepth != null
                    && copyPackedDepth("water-depth-copy", targetPackedDepth, extent, views)) {
                depthCopyExecuted = true;
            }
            submitted += renderWaterChildren(
                    target,
                    targetColor,
                    extent,
                    views,
                    elapsedSeconds,
                    mainView,
                    mainProjection,
                    frameEnvironment,
                    activeWaterRoute,
                    fxEnabled);
        }

        var translucentStarted = performance.start();
        mainSubmission.inWaterPlaneZ = retainedInWaterPlaneZ;
        var translucentView = views.allocate("scene-translucent", BgfxViewAllocator.Domain.RENDER, true);
        setupView(translucentView, "scene-translucent", target, extent, BGFX_VIEW_MODE_SEQUENTIAL);
        bgfx_set_view_transform(translucentView, mainView, mainProjection);
        bgfx_touch(translucentView);
        dumpDebugDrawList();
        if (RETAIN_SEQUENTIAL_TEXTURE_BINDINGS) {
            uniforms.beginTextureBatch();
        }
        try {
            var fading = !fadingObjects.isEmpty() && !renderingContext.inspection.isIsolated();
            if (fading) {
                submitted += submitFadingOpaque(translucentView, elapsedSeconds, mainSubmission);
            }
            for (var command : translucent) {
                if (submit(translucentView, command, elapsedSeconds, mainSubmission)) {
                    submitted++;
                }
                if (fading && command.object.hasFadingInstances()) {
                    submitted += submitFading(translucentView, command, elapsedSeconds, mainSubmission);
                }
            }
            for (var command : water) {
                if (activeWaterRoute != 0
                        && reflectionKey(command.object, command.primitiveOrdinal, command.primitive()) != null
                        && reflectedWaterCommands.contains(command)) {
                    continue;
                }
                if (submit(
                        translucentView,
                        command,
                        elapsedSeconds,
                        waterSubmissionState.forWater(
                                extent,
                                null,
                                null,
                                waterBackCopyExecuted ? waterBackCopy.getTexture() : null,
                                depthCopyExecuted ? depthCopy : null,
                                null,
                                0f,
                                frameEnvironment))) {
                    submitted++;
                }
            }
        } finally {
            if (RETAIN_SEQUENTIAL_TEXTURE_BINDINGS) {
                uniforms.endTextureBatch();
            }
        }
        performance.addPhase(CpuPhase.TRANSLUCENT_ENCODING, performance.getElapsed(translucentStarted));
        if (othersView >= 0) {
            submitted += submitOthers(
                    othersView, extent, elapsedSeconds, mainView, mainProjection, nativeView, mainSubmission);
        }
        var finalFxBucket = waterPassActive ? RenderBucket.DEFERRED : RenderBucket.ALL;
        BgfxTexture finalSoftDepth = null;
        if (targetPackedDepth != null
                && fxEnabled
                && softParticleShaders
                && fxRenderer.hasSoftParticles(finalFxBucket)
                && copyPackedDepth("fx-final-depth-copy", targetPackedDepth, extent, views)) {
            finalSoftDepth = depthCopy;
            softParticleDepthCopies++;
        }
        submitted += fxRenderer.render(
                target,
                extent,
                views,
                elapsedSeconds,
                deltaSeconds,
                fxEnabled,
                depthMrt,
                finalFxBucket,
                !waterPassActive,
                finalSoftDepth);
        if (fxEnabled) {
            submitted += fxRenderer.renderRefraction(targetColor, targetDepth, extent, views);
        }
        submitted += renderCoronas(
                target,
                extent,
                views,
                mainView,
                mainProjection,
                deltaSeconds,
                fxEnabled,
                targetPackedDepth != null,
                depthMrt ? targetPackedDepth : null);
        if (!historyBuilt) {
            gpuVisibility.buildHistory(targetDepth, extent, views);
        }
        performance.addPhase(CpuPhase.COMMAND_ENCODING, performance.getElapsed(encodingStarted));
        performance.directSceneSubmission(performance.getElapsed(started));
        performance.reflections(reflectionUpdates, reflectionReuses);
        if (performance.isEnabled()) {
            updateUiCounters(submitted, started, gpuOpaque);
        }
        return frameEnvironment;
    }

    SceneUploadStats getUploadStats() {
        return new SceneUploadStats(
                geometryByOwnerIdentity.values().stream().mapToInt(Map::size).sum(),
                geometryByName.size(),
                textures.getIdentityCacheSize(),
                textures.getNamedCacheSize(),
                0,
                0,
                0L,
                0,
                false,
                0,
                0,
                0,
                new int[8],
                0,
                0,
                0L);
    }

    BgfxGeometryPool.Snapshot getGeometryUploadSnapshot() {
        return geometryPool.snapshot();
    }

    BgfxInstancePool.Snapshot getInstanceUploadSnapshot() {
        return instancePool.snapshot();
    }

    ArrayPageMemory getTextureArrayMemorySnapshot() {
        return textures.getArrayPageMemory();
    }

    int getLoadedMaps() {
        return loadedMaps;
    }

    Set<BgfxTextureContent> getResidentTextureContents() {
        return textures.getResidentContents();
    }

    boolean collectsRayData() {
        return metalRayQueries != null && metalRayQueries.isCollecting();
    }

    int getRenderableObjectCount() {
        return objects.size();
    }

    int getStagedObjectCount() {
        return stagedObjects.values().stream().mapToInt(List::size).sum();
    }

    int getUploadFailures() {
        return uploadFailures;
    }

    boolean requiresOffscreen() {
        return hasWater || fxRenderer.hasLargeRefraction() || gpuVisibility.requiresDepthTarget();
    }

    boolean hasReflectionConsumer() {
        return hasWater && waterRoute() != 0;
    }

    boolean isGpuVisibilityActive() {
        return gpuVisibility.isPreparedThisFrame();
    }

    boolean isGpuVisibilityHistoryUsed() {
        return gpuVisibility.isHistoryUsedThisFrame();
    }

    Visibility getGpuVisibilityDiagnostics() {
        return gpuVisibility.getDiagnostics(objects.size());
    }

    boolean isGroupedIndirectActive() {
        return gpuVisibility.isGroupedThisFrame();
    }

    String getGpuVisibilityReason() {
        return gpuVisibility.getReason();
    }

    double getIndirectGroupCoverage() {
        return gpuVisibility.getMultiGroupCoverage();
    }

    long getIndirectSavedSubmits() {
        return gpuVisibility.getGroupedSavedSubmits();
    }

    int getGpuOpaqueSubmitBatches() {
        return gpuOpaqueSubmitBatches;
    }

    int getGpuOpaqueCommandRanges() {
        return gpuOpaqueCommandRanges;
    }

    int getGpuOpaqueActiveGroups() {
        return gpuOpaqueActiveGroups;
    }

    int getGpuVisibilityStatsAgeFrames() {
        return gpuVisibility.getStatsAgeFrames();
    }

    int getGpuVisibilityCommandCount() {
        return gpuVisibility.getCommandCount();
    }

    int getGpuVisibilityGroupCount() {
        return gpuVisibility.getGroupCount();
    }

    long getTopologyDeltaUploadBytes() {
        return gpuVisibility.getTopologyDeltaUploadBytes();
    }

    long getTopologyFullResourceBuilds() {
        return gpuVisibility.getTopologyFullResourceBuilds();
    }

    long getTopologyDeltaUpdates() {
        return gpuVisibility.getTopologyDeltaUpdates();
    }

    long getTopologyBuilds() {
        return gpuVisibility.getTopologyBuilds();
    }

    long getTopologyBuildNanos() {
        return gpuVisibility.getTopologyBuildNanos();
    }

    void logTopologySummary(String reason) {
        gpuVisibility.logTopologySummary(reason);
        logActorLightingSummary(reason);
    }

    void logActorLightingSummary(String reason) {
        var lighting = actorLightingBuffer.snapshot();
        log.debug(
                "{} actor lighting: live={} MiB highWater={} MiB capacity={} MiB, "
                        + "uploads={} / {} MiB (RGB8+mask. Shared geometry)",
                reason,
                lighting.liveWords() * Integer.BYTES / (1024L * 1024L),
                (long) lighting.highWaterWords() * Integer.BYTES / (1024L * 1024L),
                (long) lighting.capacityWords() * Integer.BYTES / (1024L * 1024L),
                lighting.uploadSubmissions(),
                lighting.uploadedBytes() / (1024L * 1024L));
    }

    DrawCounts getFxDrawCounts() {
        return fxRenderer.getDrawCounts();
    }

    BgfxFxRenderer.RefractionCounts getRefractionCounts() {
        return fxRenderer.getRefractionCounts();
    }

    void setRefractionPassPerEffect(boolean passPerEffect) {
        fxRenderer.setRefractionPassPerEffect(passPerEffect);
    }

    int getReflectionUpdates() {
        return reflectionUpdates;
    }

    int getReflectedFxDraws() {
        return reflectedFxDraws;
    }

    int getReflectionTargetCount() {
        return reflections.size();
    }

    int getReflectionReuses() {
        return reflectionReuses;
    }

    long getReflectionSavedDraws() {
        return reflectionSavedDraws;
    }

    long getOpaqueShadedPixelProxy() {
        return opaqueShadedPixelProxy;
    }

    long getTerrainShadedPixelProxy() {
        return terrainShadedPixelProxy;
    }

    long getMaskedShadedPixelProxy() {
        return maskedShadedPixelProxy;
    }

    long getReflectionShadedPixelProxy() {
        return reflectionShadedPixelProxy;
    }

    int getVisibleWaterConsumers() {
        return visibleWaterConsumers;
    }

    void traceWaterFrame(long frame, int remainingViews) {
        log.info(
                "Water capture: frame={} sheets={} groups={} refreshed={} reused={} back={} viewsLeft={}"
                        + " producerHeight={}",
                frame,
                visibleWaterConsumers,
                activeWaterGroups.size(),
                reflectionUpdates,
                reflectionReuses,
                waterBackCopyExecuted,
                remainingViews,
                getMainWaterCoveragePlane(waterRoute()));
    }

    boolean wasDepthCopyExecuted() {
        return depthCopyExecuted;
    }

    boolean wasWaterBackCopyExecuted() {
        return waterBackCopyExecuted;
    }

    int getSoftParticleDepthCopies() {
        return softParticleDepthCopies;
    }

    Extent getActiveReflectionExtent() {
        return activeReflectionExtent;
    }

    BgfxSpawnModelLighting.@Nullable Frame openSpawnModelLighting(List<BgfxSpawnModelRenderer.BodyLight> bodies) {
        var view = spawnModelView;
        return view == null ? null : spawnModelLighting.open(view, spawnModelSeconds, spawnModelEnvironment, bodies);
    }

    Vec3 getCurrentSunDirection() {
        return environment.getCurrentSunDirection();
    }

    MaterialClient getMaterialClient() {
        return environment.getMaterialClient();
    }

    boolean isCameraInAuthoredInterior() {
        return environment.isCameraInAuthoredInterior();
    }

    float getTimeSinceLastFogChange() {
        return environment.getTimeSinceLastFogChange();
    }

    BgfxShadowRenderer.Snapshot getShadowSnapshot() {
        return shadowRenderer == null ? BgfxShadowRenderer.Snapshot.disabled() : shadowRenderer.getSnapshot();
    }

    boolean areSunShadowsBuilt() {
        return !state.isCustomLightingEffective()
                || !state.settings.isCustomSunShadowsEnabled()
                || shadowRenderer == null
                || shadowRenderer.buildComplete();
    }

    boolean captureLightingReady() {
        if (!state.isCustomLightingEffective() || !state.settings.isCustomSunShadowsEnabled()) {
            return true;
        }
        if (shadowRenderer == null) {
            throw new IllegalStateException(
                    "Custom lighting Capture requires sampled depth textures and available sun-shadow resources."
                            + " Disable Custom lighting or select another renderer.");
        }
        return shadowRenderer.buildComplete();
    }

    BgfxLocalShadowAtlas.Snapshot getLocalShadowSnapshot() {
        return localShadowAtlas == null ? BgfxLocalShadowAtlas.Snapshot.disabled() : localShadowAtlas.getSnapshot();
    }

    BgfxLocalLightSet.Snapshot getLocalLightSnapshot() {
        return localLightSnapshot;
    }

    String getCoverageDebugDescription() {
        return coverageDebug.getDescription();
    }

    void clearTransientCaches() {
        geometryByOwnerIdentity.clear();
    }

    void clearNamedTextureCache() {
        textures.clearNamed();
    }

    boolean canPreviewActorTransform(String sourceActor) {
        if (sourceActor == null || sourceActor.isBlank()) {
            return false;
        }
        var placements = editorActors().get(sourceActor);
        return placements != null
                && !placements.isEmpty()
                && placements.stream().allMatch(BgfxSceneObject::canPreviewTransform);
    }

    void previewActorTransformDeltas(Map<String, float[]> deltas) {
        requireOpen();
        BgfxEditorTransforms.validate(deltas);
        if (deltas.isEmpty() && (editorScene == null || editorScene.deltas.isEmpty())) {
            return;
        }
        editorActors();
        var restores = new HashSet<>(editorScene.deltas.keySet());
        restores.removeAll(deltas.keySet());
        previewActorTransformDeltaChanges(deltas, restores);
    }

    void previewActorTransformDeltaChanges(Map<String, float[]> updates, Set<String> restores) {
        requireOpen();
        BgfxEditorTransforms.validate(updates);
        if (restores == null
                || restores.stream()
                        .anyMatch(actor -> actor == null || actor.isBlank() || updates.containsKey(actor))) {
            throw new IllegalArgumentException("Restored actors must be nonblank and separate from updated actors");
        }
        if (updates.isEmpty() && (editorScene == null || editorScene.deltas.isEmpty())) {
            return;
        }
        for (var actor : updates.keySet()) {
            if (isMovingPlacement(actor)) {
                throw new IllegalArgumentException(
                        "A mesh that moves by itself takes its saved place, not a preview delta: " + actor);
            }
            if (!canPreviewActorTransform(actor)) {
                throw new IllegalArgumentException("Actor is not available for transform editing: " + actor
                        + ". Merged actors can be transformed only after their tile has loaded for Editing.");
            }
        }
        var editor = editorScene;
        var changedActors = new HashSet<String>();
        for (var entry : updates.entrySet()) {
            if (!Arrays.equals(entry.getValue(), editor.deltas.get(entry.getKey()))) {
                changedActors.add(entry.getKey());
            }
        }
        for (var actor : restores) {
            if (editor.deltas.containsKey(actor)) {
                changedActors.add(actor);
            }
        }
        if (changedActors.isEmpty()) {
            return;
        }
        preserveEditorBaseline();
        var left = new ArrayList<BgfxSceneObject>();
        var topologyChanged = splitEditorActors(updates.keySet(), left);
        for (var actor : changedActors) {
            var updated = updates.get(actor);
            if (updated == null) {
                editor.deltas.remove(actor);
            } else {
                editor.deltas.put(actor, updated.clone());
            }
        }
        var affected = Collections.newSetFromMap(new IdentityHashMap<BgfxSceneObject, Boolean>());
        var index = editorActors();
        for (var actor : changedActors) {
            affected.addAll(index.getOrDefault(actor, List.of()));
        }
        var changed = false;
        for (var object : affected) {
            var wasPreviewed = object.hasEditorPreview();
            if (object.previewTransforms(editor.deltas, instancePool)) {
                changed = true;
                topologyChanged |= wasPreviewed != object.hasEditorPreview();
                if (!wasPreviewed && object.hasEditorPreview()) {
                    left.add(object);
                }
                editor.movedOwners.add(object.getOwner());
                if (metalRayQueries != null) {
                    metalRayQueries.queueGiMovement(object);
                }
            }
        }
        if (!changed) {
            return;
        }
        movementRevision++;
        editorTransformRevision++;
        traversalCacheValid = false;
        if (topologyChanged) {
            onEditorObjectsChanged(left, changedActors);
        } else {
            gpuVisibility.invalidateHistory("Actor transform preview changed");
        }
    }

    boolean isMovingPlacement(String sourceActor) {
        if (sourceActor == null || sourceActor.isBlank()) {
            return false;
        }
        if (renderingContext.swings.isMoving(sourceActor)
                || editorScene != null && editorScene.movements.containsKey(sourceActor)) {
            return true;
        }
        var placements = editorActors().get(sourceActor);
        return placements != null && placements.stream().anyMatch(object -> object.getMotion() != null);
    }

    void previewMovingPlacements(Map<String, ActorTransform> placements) {
        requireOpen();
        if (placements.isEmpty()) {
            return;
        }
        var index = editorActors();
        var refreshed = new LinkedHashSet<String>();
        for (var entry : placements.entrySet()) {
            var actor = entry.getKey();
            var transform = entry.getValue();
            var location = new Vec3(
                    transform.location().x(),
                    transform.location().y(),
                    transform.location().z());
            var rotation = new URotator(
                    transform.rotation().pitch(),
                    transform.rotation().yaw(),
                    transform.rotation().roll());
            var drawScale3d = new Vec3(
                    transform.drawScale3d().x(),
                    transform.drawScale3d().y(),
                    transform.drawScale3d().z());
            var prePivot = new Vec3(
                    transform.prePivot().x(),
                    transform.prePivot().y(),
                    transform.prePivot().z());
            if (!renderingContext.swings.place(
                    actor, location, rotation, drawScale3d.times(transform.drawScale()), prePivot)) {
                var source = renderingContext.swings.getMovementSource(actor);
                if (source != null) {
                    var saved = editorScene.movements.getOrDefault(actor, source);
                    var placed = isSavedPlace(saved, location, rotation, drawScale3d, prePivot, transform)
                            ? saved
                            : saved.withPlacement(location, rotation, drawScale3d, prePivot, transform.drawScale());
                    renderingContext.swings.placeMovement(actor, placed);
                }
            }
            refreshed.add(actor);
        }
        refreshed.addAll(renderingContext.swings.getCarriedMeshes(placements.keySet()));
        var moved = false;
        for (var actor : refreshed) {
            for (var object : index.getOrDefault(actor, List.of())) {
                if (object.refreshMovement(instancePool)) {
                    moved = true;
                    editorScene.movedOwners.add(object.getOwner());
                    if (metalRayQueries != null) {
                        metalRayQueries.queueGiMovement(object);
                    }
                }
            }
        }
        if (moved) {
            movementRevision++;
            editorTransformRevision++;
            traversalCacheValid = false;
        }
    }

    void replaceEditorActors(Map<String, List<Entity>> replacements) {
        replaceEditorObjects(replacements, EditorReplacement.ACTORS);
    }

    void replaceEditorTerrain(String owner, Map<String, List<Entity>> sectors, boolean initialize) {
        requireOpen();
        if (owner == null || owner.indexOf('.') < 1 || sectors == null) {
            throw new IllegalArgumentException("Terrain replacement requires an owner and sectors");
        }
        var replacements = new LinkedHashMap<String, List<Entity>>();
        for (var entry : sectors.entrySet()) {
            if (!entry.getKey().startsWith(owner + "$terrain$")
                    || entry.getValue() == null
                    || entry.getValue().stream()
                            .anyMatch(entity -> entity == null || !(entity.getMesh() instanceof TerrainMesh))) {
                throw new IllegalArgumentException(
                        "Terrain sector replacement belongs to another owner or geometry kind");
            }
            replacements.put(entry.getKey(), entry.getValue());
        }
        if (initialize) {
            var resident = editorActors().getOrDefault(owner, List.of());
            var hiddenOriginal = editorScene != null
                    && editorScene.originals != null
                    && editorScene.originals.stream()
                            .anyMatch(object -> object.containsSourceActor(owner)
                                    && object.getGeometry().getKind() == Kind.TERRAIN);
            if (resident.isEmpty() && !hiddenOriginal) {
                throw new IllegalArgumentException("Terrain tile is not resident: " + owner);
            }
            if (sectors.isEmpty()) {
                throw new IllegalArgumentException("Terrain tile has no visible sector geometry");
            }
            replacements.put(owner, List.of());
            editorScene.terrainGiPixels.clear();
        }
        replaceEditorObjects(replacements, EditorReplacement.TERRAIN);
    }

    void replaceEditorDecorations(Map<String, List<Entity>> replacements) {
        requireOpen();
        if (replacements == null) {
            throw new IllegalArgumentException("Decoration replacements cannot be null");
        }
        for (var key : replacements.keySet()) {
            if (!DecorationTemplate.isDecorationActor(key)) {
                throw new IllegalArgumentException("Not a Terrain decoration layer: " + key);
            }
        }
        if (editorScene == null || replacements.isEmpty()) {
            return;
        }
        replaceEditorObjects(replacements, EditorReplacement.ACTORS);
    }

    long updateEditorLightmap(String map, int key, Texture texels) {
        requireOpen();
        return textures.replaceLightmapLayers(map, key, texels);
    }

    boolean updateEditorBspSurfaces(String map, List<Entity> bsp, Set<Integer> surfaces, Set<Integer> collapsed) {
        requireOpen();
        var resident = map == null ? null : bspGeometryByOwner.get(map);
        if (bsp == null || surfaces == null || collapsed == null || resident == null) {
            return false;
        }
        var targets = new ArrayList<BgfxSceneGeometry>();
        var sources = new ArrayList<Entity>();
        var ranges = new ArrayList<int[]>();
        var points = new ArrayList<int[]>();
        for (var entity : bsp) {
            var spans = entity.getBspSpans();
            if (spans == null || !(entity.getMesh() instanceof EntityMesh mesh)) {
                continue;
            }
            var chosen = new IntArrayList();
            var gone = new IntArrayList();
            for (var span = 0; span + 2 < spans.length; span += 3) {
                if (surfaces.contains(spans[span])) {
                    chosen.add(spans[span + 1]);
                    chosen.add(spans[span + 2]);
                    if (collapsed.contains(spans[span])) {
                        gone.add(spans[span + 1]);
                        gone.add(spans[span + 2]);
                    }
                }
            }
            if (chosen.isEmpty()) {
                continue;
            }
            var geometry = resident.get(entity.getBspOrdinal());
            if (geometry == null
                    || geometry.getKind() != Kind.ENTITY
                    || geometry.getVertexCount() != mesh.vertexBuffer().size()) {
                return false;
            }
            targets.add(geometry);
            sources.add(entity);
            ranges.add(chosen.toArray());
            points.add(gone.toArray());
        }
        for (var index = 0; index < targets.size(); index++) {
            var entity = sources.get(index);
            targets.get(index)
                    .updateEntityVertices(
                            (EntityMesh) entity.getMesh(),
                            entity.getLightmapLayers(),
                            ranges.get(index),
                            points.get(index));
        }
        if (!targets.isEmpty()) {
            gpuVisibility.invalidateHistory("BSP surfaces changed");
        }
        return true;
    }

    void replaceEditorBspSurfaces(String map, List<Entity> entities) {
        requireOpen();
        if (map == null || entities == null) {
            throw new IllegalArgumentException("BSP surface replacement requires a map and entities");
        }
        var key = map + EDITOR_SURFACES;
        for (var entity : entities) {
            if (entity == null || !(entity.getMesh() instanceof EntityMesh) || entity.getBspSpans() == null) {
                throw new IllegalArgumentException("BSP surface replacement takes BSP entities only");
            }
            entity.setSourceActor(key);
        }
        if (entities.isEmpty() && editorActors().getOrDefault(key, List.of()).isEmpty()) {
            return;
        }
        replaceEditorObjects(Map.of(key, List.copyOf(entities)), EditorReplacement.BSP);
    }

    boolean updateEditorTerrainVertices(String owner, Map<String, List<Entity>> sectors) {
        requireOpen();
        if (owner == null || sectors == null || editorScene == null || editorScene.originals == null) {
            return false;
        }
        var index = editorActors();
        var updates = new ArrayList<Map.Entry<BgfxSceneGeometry, TerrainMesh>>();
        for (var entry : sectors.entrySet()) {
            if (!entry.getKey().startsWith(owner + "$terrain$")
                    || entry.getValue() == null
                    || entry.getValue().size() != 1
                    || !(entry.getValue().getFirst().getMesh() instanceof TerrainMesh mesh)) {
                return false;
            }
            var resident = index.getOrDefault(entry.getKey(), List.of());
            if (resident.size() != 1
                    || resident.getFirst().getGeometry().getKind() != Kind.TERRAIN
                    || resident.getFirst().getGeometry().getVertexCount()
                            != mesh.vertices().size()
                    || resident.getFirst().getGeometry().getIndexCount() != mesh.indices().length) {
                return false;
            }
            updates.add(Map.entry(resident.getFirst().getGeometry(), mesh));
        }
        if (updates.isEmpty()) {
            return true;
        }
        for (var update : updates) {
            update.getKey().updateTerrainVertices(update.getValue());
        }
        movementRevision++;
        traversalCacheValid = false;
        gpuVisibility.invalidateHistory("Terrain brush preview changed");
        return true;
    }

    boolean updateEditorTerrainAlpha(TerrainAlphaAtlas atlas, int x, int y, int width, int height) {
        requireOpen();
        return textures.updateTerrainAlphaAtlas(atlas, x, y, width, height);
    }

    void clearEditorTerrain(String owner) {
        requireOpen();
        if (editorScene == null || editorScene.originals == null) {
            return;
        }
        editorScene.terrainGiPixels.clear();
        var prefix = owner + "$terrain$";
        var remove = objects.stream()
                .filter(object -> object.getSourceActors().stream().anyMatch(actor -> actor.startsWith(prefix)))
                .toList();
        var owners = new HashSet<String>();
        var actors = new HashSet<String>();
        actors.add(owner);
        for (var object : remove) {
            owners.add(object.getOwner());
            actors.addAll(object.getSourceActors());
            objects.remove(object);
            reflectionKeysByObject.remove(object);
            if (editorScene.ownedObjects.remove(object)) {
                closeEditorObject(object);
            }
        }
        for (var object : editorScene.originals) {
            if (object.containsSourceActor(owner)
                    && object.getGeometry().getKind() == Kind.TERRAIN
                    && !objects.contains(object)) {
                objects.add(object);
                owners.add(object.getOwner());
            }
        }
        refreshEditorRayOwners(owners);
        releaseUnusedEditorGeometries();
        editorScene.byActor = null;
        onEditorTopologyChanged();
        recordEditorTopology(actors);
    }

    void clearEditorPreview() {
        requireOpen();
        if (editorScene == null) {
            return;
        }
        var editor = editorScene;
        if (editor.originals == null) {
            restoreMovingMeshes(editor, objects);
            editorScene = null;
            return;
        }
        var owners = new HashSet<String>();
        objects.forEach(object -> owners.add(object.getOwner()));
        for (var object : editor.originals) {
            object.previewTransforms(Map.of(), instancePool);
            owners.add(object.getOwner());
        }
        objects.clear();
        objects.addAll(editor.originals);
        editor.ownedObjects.forEach(this::closeEditorObject);
        geometries.removeIf(editor.ownedGeometries::contains);
        restoreMovingMeshes(editor, editor.originals);
        editorScene = null;
        refreshEditorRayOwners(owners);
        for (var geometry : editor.ownedGeometries) {
            if (metalRayQueries != null) {
                metalRayQueries.releaseEditorGeometry(geometry);
            }
            geometry.close();
        }
        onEditorTopologyChanged();
    }

    @Nullable
    Set<String> getEditorTopologyActorsSince(long since) {
        if (since > sceneObjectsRevision) {
            return null;
        }
        var result = new HashSet<String>();
        for (var revision = since + 1; revision <= sceneObjectsRevision; revision++) {
            var actors = editorTopologyActors.get(revision);
            if (actors == null) {
                return null;
            }
            result.addAll(actors);
        }
        return result;
    }

    BgfxDecorations.Snapshot getDecorationSnapshot() {
        return decorations.getSnapshot();
    }

    void requestTopologyBuild() {
        if (!gpuTopologyDirty) {
            topologyDirtyFrames = 0;
        }
        gpuTopologyDirty = true;
    }

    void setDecorationsSynchronous(boolean synchronous) {
        decorations.setSynchronous(synchronous);
    }

    boolean streamsDecorations(String owner, int layerIndex) {
        return decorations.streams(owner, layerIndex);
    }

    void showDecorationSources(
            String owner, Map<Integer, DecorationDensity> densities, @Nullable DecorationGround ground) {
        requireOpen();
        decorations.setSources(owner, densities, ground);
    }

    void restoreDecorationSources(String owner) {
        requireOpen();
        decorations.restoreSources(owner);
    }

    private final class DecorationHost implements BgfxDecorations.Host {
        @Override
        public BgfxSceneGeometry installGeometry(Entity source, BgfxSceneGeometry.Prepared prepared) {
            var geometry = BgfxSceneGeometry.install(geometryPool, textures, source, prepared);
            geometries.add(geometry);
            return geometry;
        }

        @Override
        public BgfxSceneObject createPool(
                Entity representative, BgfxSceneGeometry geometry, long stableId, int capacity, Bounds3f bounds) {
            return BgfxSceneObject.createDecorationPool(
                    instancePool, actorLightingBuffer, textures, representative, geometry, stableId, capacity, bounds);
        }

        @Override
        public void publishPool(BgfxSceneObject pool, @Nullable BgfxSceneObject replaced) {
            if (replaced != null) {
                withdrawDecorationPool(replaced);
            }
            var changes = objects.getChanges();
            objects.add(Math.min(bakedObjectCount, objects.size()), pool);
            clearanceIgnoredChanges += objects.getChanges() - changes;
            bakedObjectCount = Math.min(bakedObjectCount + 1, objects.size());
            if (editorScene != null && editorScene.originals != null) {
                editorScene.originals.add(pool);
            }
            decorationObjectsChanged();
        }

        @Override
        public void removePool(BgfxSceneObject pool) {
            withdrawDecorationPool(pool);
            decorationObjectsChanged();
        }

        @Override
        public boolean isInTopology(BgfxSceneObject pool) {
            return gpuVisibility.includes(pool);
        }

        @Override
        public long getTopologyGeneration() {
            return gpuVisibility.getTopologyGeneration();
        }

        @Override
        public void requestTopology() {
            requestTopologyBuild();
        }

        @Override
        public void writeSlots(BgfxSceneObject pool, int first, int count) {
            gpuVisibility.writeDecorationSlots(pool, first, count);
        }

        @Override
        public void slotsChanged() {
            decorationRevision++;
        }

        @Override
        public boolean registerRays(
                String owner, Entity source, BgfxSceneGeometry.Prepared geometry, BgfxSceneObject.Prepared placement) {
            return metalRayQueries != null && metalRayQueries.registerDecorations(owner, source, geometry, placement);
        }

        @Override
        public void removeRays(String owner) {
            if (metalRayQueries != null) {
                metalRayQueries.removeOwner(owner);
            }
        }
    }

    private static final class EditorScene {
        Map<String, List<BgfxSceneObject>> byActor;
        Map<String, List<EditorPlacement>> placementsByActor;
        List<BgfxSceneObject> originals;
        final Set<BgfxSceneObject> ownedObjects = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<BgfxSceneGeometry> ownedGeometries = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<String, float[]> deltas = new HashMap<>();
        final Map<String, L2Movement> movements = new HashMap<>();
        final Set<String> movedOwners = new HashSet<>();
        final IdentityHashMap<BgfxSceneObject, EditorBinding> bindings = new IdentityHashMap<>();
        final IdentityHashMap<Texture, byte[]> terrainGiPixels = new IdentityHashMap<>();
        final Map<String, String> packageMaps = new HashMap<>();
    }

    private record EditorBinding(Entity source, String leaseOwner) {}

    private record EditorPlacement(BgfxSceneObject object, int instance, int part) {}

    private record EditorUpload(
            Entity source,
            Prepared prepared,
            BgfxSceneObject.Prepared placement,
            BgfxSceneGeometry geometry,
            BgfxSceneObject object) {}

    float getMainWaterCoveragePlane(int route) {
        if (route != WaterRenderRoute.PS19) {
            return retainedInWaterPlaneZ;
        }
        var extended = false;
        var highest = Float.NEGATIVE_INFINITY;
        for (var group : activeWaterGroups) {
            var height =
                    HfInWaterDepthCoverage.planeWorldZ(group.key.authoredPlaneZ(), group.key.authoredPlaneW(), route);
            if (height != 0f) {
                highest = Math.max(highest, height);
            }
            for (var command : group.commands) {
                if (command.distanceSquared > REFLECTION_WORKING_SET_SQ_RADIUS) {
                    extended = true;
                }
            }
        }
        return extended && Float.isFinite(highest) ? highest : retainedInWaterPlaneZ;
    }

    long getClearanceRevision() {
        return getClearanceChanges() + editorTransformRevision + zoneStateShownRevision;
    }

    boolean hasMovingObjects() {
        return !movingObjects.isEmpty();
    }

    float measureClearance(float x, float y, float z, float limit) {
        if (objects.isEmpty()) {
            return 0f;
        }
        if (clearanceGroupsChanges != getClearanceChanges()) {
            groupForClearance();
        }
        var best = (double) limit * limit;
        for (var group : clearanceGroups) {
            if (group.getDistanceSquared(x, y, z) >= best) {
                continue;
            }
            for (var object : group.objects) {
                if (distanceSquared(object.getWorldBounds(), x, y, z) < best) {
                    best = object.getClearanceSquared(x, y, z, best);
                    if (!(best > 0)) {
                        return 0f;
                    }
                }
            }
        }
        for (var object : movingObjects) {
            if (distanceSquared(object.getWorldBounds(), x, y, z) < best) {
                best = object.getClearanceSquared(x, y, z, best);
                if (!(best > 0)) {
                    return 0f;
                }
            }
        }
        return (float) Math.sqrt(best);
    }

    @Nullable
    Bounds3f getSceneBounds() {
        var now = System.nanoTime();
        if (objects.size() == sceneBoundsObjects && now - sceneBoundsNanos < SCENE_BOUNDS_NANOS) {
            return sceneBounds;
        }
        var parts = 0;
        for (var object : objects) {
            parts += object.getGeometry().getPrimitives().size();
        }
        var extent = new SceneExtent(parts);
        for (var object : objects) {
            var terrain = object.getGeometry().getKind() == BgfxSceneGeometry.Kind.TERRAIN;
            for (var ordinal = 0; ordinal < object.getGeometry().getPrimitives().size(); ordinal++) {
                extent.add(object.getPrimitiveBounds(ordinal), terrain);
            }
        }
        sceneBounds = extent.getBounds();
        sceneBoundsObjects = objects.size();
        sceneBoundsNanos = now;
        return sceneBounds;
    }

    DebugSceneHit pickDebugSceneObjectAtDistance(
            Vec3 origin, Vec3 direction, float visibleDistance, float maxDistance, Set<Category> categories) {
        return pickDebugSceneObjectAtDistance(
                origin,
                direction,
                visibleDistance,
                maxDistance,
                categories,
                Math.max(PICK_TOLERANCE, visibleDistance * PICK_TOLERANCE_PER_UNIT));
    }

    DebugSceneHit pickDebugSceneObjectAtDistance(
            Vec3 origin,
            Vec3 direction,
            float visibleDistance,
            float maxDistance,
            Set<Category> categories,
            float tolerance) {
        requireOpen();
        if (origin == null
                || direction == null
                || categories == null
                || categories.isEmpty()
                || !Float.isFinite(visibleDistance)
                || visibleDistance < 0f
                || !Float.isFinite(maxDistance)
                || maxDistance <= 0f
                || visibleDistance > maxDistance) {
            return null;
        }
        var pointX = origin.getX() + direction.getX() * visibleDistance;
        var pointY = origin.getY() + direction.getY() * visibleDistance;
        var pointZ = origin.getZ() + direction.getZ() * visibleDistance;
        DebugSceneObject nearest = null;
        var nearestVolume = Float.POSITIVE_INFINITY;
        refreshDetailedTerrainOwners();
        for (var object : objects) {
            if (!acceptsInspection(object) || isShadowedTerrainProxy(object)) {
                continue;
            }
            var geometry = object.getGeometry();
            for (var ordinal = 0; ordinal < geometry.getPrimitives().size(); ordinal++) {
                var primitive = geometry.getPrimitives().get(ordinal);
                var material = primitive.entityMaterial();
                if (material != null && material.getBlendMode() == BlendMode.INVISIBLE) {
                    continue;
                }
                var surfaceType = material == null ? SurfaceType.TERRAIN.getId() : material.getSurfaceType();
                if (!isSurfaceEnabled(surfaceType, state.settings)) {
                    continue;
                }
                var category = material == null
                        ? Category.TERRAIN
                        : (material.getSurfaceType() & SurfaceType.CSG.getId()) != 0
                                ? Category.CSG
                                : Category.STATIC_MESH;
                var coarseBounds = object.getPrimitiveBounds(ordinal);
                if (!categories.contains(category) || !contains(coarseBounds, pointX, pointY, pointZ, tolerance)) {
                    continue;
                }
                var materialName = material == null ? "terrain" : material.getMaterialName();
                if (materialName == null || materialName.isBlank()) {
                    materialName = "<unnamed>";
                }
                if (!object.getSourceParts().isEmpty()) {
                    for (var partIndex = 0; partIndex < object.getSourceParts().size(); partIndex++) {
                        var part = object.getSourceParts().get(partIndex);
                        if (!doesPartIntersect(part, primitive)
                                || renderingContext.inspection.getIsolatedSourceActor() != null
                                        && !part.actor().equals(renderingContext.inspection.getIsolatedSourceActor())
                                || !contains(part.bounds(), pointX, pointY, pointZ, tolerance)) {
                            continue;
                        }
                        var volume = boundsVolume(part.bounds());
                        if (volume < nearestVolume) {
                            nearestVolume = volume;
                            nearest = debugPart(object, ordinal, partIndex);
                        }
                    }
                    continue;
                }
                if (!geometry.getKind().isInstanced()) {
                    var volume = boundsVolume(coarseBounds);
                    if (volume < nearestVolume) {
                        nearestVolume = volume;
                        nearest = debugSceneObject(
                                object,
                                primitive,
                                category,
                                materialName,
                                coarseBounds,
                                -1,
                                lightingDescription(object));
                    }
                    continue;
                }
                var records = object.getInstanceRecords();
                for (var offset = 0; offset < records.length; offset += BgfxSceneObject.INSTANCE_FLOATS) {
                    if (renderingContext.inspection.getIsolatedInstance() >= 0
                                    && offset / BgfxSceneObject.INSTANCE_FLOATS
                                            != renderingContext.inspection.getIsolatedInstance()
                            || object.isZoneHidden(offset / BgfxSceneObject.INSTANCE_FLOATS)
                            || object.isEmptySlot(offset / BgfxSceneObject.INSTANCE_FLOATS)) {
                        continue;
                    }
                    var billboard = BgfxBillboardTilt.isBillboard(records, offset);
                    var bounds = billboard
                            ? BgfxSceneObject.transformBillboard(primitive.localBounds(), records, offset)
                            : BgfxSceneObject.transform(primitive.localBounds(), records, offset);
                    if (!contains(bounds, pointX, pointY, pointZ, tolerance)) {
                        continue;
                    }
                    var volume = boundsVolume(bounds);
                    if (volume < nearestVolume) {
                        nearestVolume = volume;
                        nearest = debugSceneObject(
                                object,
                                primitive,
                                category,
                                materialName,
                                bounds,
                                offset / BgfxSceneObject.INSTANCE_FLOATS,
                                lightingDescription(object));
                    }
                }
            }
        }
        return nearest == null ? null : new DebugSceneHit(nearest, visibleDistance);
    }

    long getDebugSceneRevision() {
        return sceneObjectsRevision + editorTransformRevision + decorationRevision;
    }

    long getDebugSceneTopologyRevision() {
        return sceneObjectsRevision;
    }

    @Nullable
    Bounds3f getSavedActorBounds(String actor) {
        requireOpen();
        Bounds3f union = null;
        for (var object : editorActors().getOrDefault(actor, List.of())) {
            var saved = object.getSavedBounds();
            if (saved != null) {
                union = union == null
                        ? saved
                        : new Bounds3f(
                                Math.min(union.minX(), saved.minX()),
                                Math.min(union.minY(), saved.minY()),
                                Math.min(union.minZ(), saved.minZ()),
                                Math.max(union.maxX(), saved.maxX()),
                                Math.max(union.maxY(), saved.maxY()),
                                Math.max(union.maxZ(), saved.maxZ()));
            }
        }
        return union;
    }

    List<DebugSceneObject> getEditorActorObjects(Set<String> actors) {
        requireOpen();
        if (actors.isEmpty()) {
            return List.of();
        }
        editorActors();
        var result = new ArrayList<DebugSceneObject>();
        for (var actor : actors) {
            for (var placement : editorScene.placementsByActor.getOrDefault(actor, List.of())) {
                var object = placement.object;
                if (placement.part < 0 && placement.instance >= 0 && object.isHiddenLook(placement.instance)) {
                    continue;
                }
                for (var ordinal = 0;
                        ordinal < object.getGeometry().getPrimitives().size();
                        ordinal++) {
                    var primitive = object.getGeometry().getPrimitives().get(ordinal);
                    if (primitive.entityMaterial() != null
                            && primitive.entityMaterial().getBlendMode() == BlendMode.INVISIBLE) {
                        continue;
                    }
                    if (placement.part >= 0) {
                        if (doesPartIntersect(object.getSourceParts().get(placement.part), primitive)) {
                            result.add(debugPart(object, ordinal, placement.part));
                        }
                    } else {
                        result.add(debugPlacement(object, ordinal, placement.instance));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    Bounds3f getDebugMeshEmitterBounds(ParticleEmitter emitter) {
        return fxRenderer.getDebugMeshBounds(emitter);
    }

    float pickDebugMeshEmitter(ParticleEmitter emitter, Vec3 origin, Vec3 direction, float maxDistance) {
        return fxRenderer.pickMeshDistance(emitter, origin, direction, maxDistance);
    }

    @Nullable
    Bounds3f getInstanceBounds(DebugSceneObject placed) {
        if (placed.instanceOrdinal() < 0) {
            return null;
        }
        for (var object : objects) {
            if (object.getStableId() != placed.renderObjectId()
                    || !object.getGeometry().getKind().isInstanced()) {
                continue;
            }
            var records = object.getInstanceRecords();
            var offset = placed.instanceOrdinal() * BgfxSceneObject.INSTANCE_FLOATS;
            if (offset >= records.length || object.isEmptySlot(placed.instanceOrdinal())) {
                return null;
            }
            var billboard = BgfxBillboardTilt.isBillboard(records, offset);
            Bounds3f union = null;
            for (var primitive : object.getGeometry().getPrimitives()) {
                var material = primitive.entityMaterial();
                if (material != null && material.getBlendMode() == BlendMode.INVISIBLE) {
                    continue;
                }
                var box = billboard
                        ? BgfxSceneObject.transformBillboard(primitive.localBounds(), records, offset)
                        : BgfxSceneObject.transform(primitive.localBounds(), records, offset);
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
        return null;
    }

    void highlightHovered(@Nullable DebugSceneObject object, boolean wholeActor) {
        hovered = object;
        hoveredWholeActor = wholeActor;
    }

    void renderHoverHighlight(
            BgfxTexture color, BgfxTexture depth, Extent extent, BgfxViewAllocator views, float elapsedSeconds) {
        requireOpen();
        var object = hovered;
        hovered = null;
        if (object == null
                || mainSubmission == null
                || color == null
                || depth == null
                || renderingContext.getViewMode() == ViewMode.WIREFRAME) {
            return;
        }
        var highlight = hoverHighlight();
        if (highlight == null) {
            return;
        }
        var view = views.allocate("hover-highlight", BgfxViewAllocator.Domain.RENDER, false);
        setupView(view, "hover-highlight", highlight.getFramebuffer(color), extent, BGFX_VIEW_MODE_SEQUENTIAL);
        var camera = renderingContext.camera;
        bgfx_set_view_transform(
                view,
                firstSixteenFloats(camera.getViewMatrix()),
                BgfxMatrices.toBackendProjection(
                        camera.getProjectionMatrix(),
                        device.getCapabilities().homogeneousDepth(),
                        mainProjectionBuffer));
        if (hoveredWholeActor) {
            editorActors();
            for (var placement : editorScene.placementsByActor.getOrDefault(object.actorName(), List.of())) {
                highlightPlacement(
                        highlight,
                        view,
                        placement.object(),
                        placement.instance(),
                        placement.part(),
                        -1,
                        depth,
                        elapsedSeconds);
            }
            return;
        }
        for (var candidate : objects) {
            if (candidate.getStableId() == object.renderObjectId()) {
                highlightPlacement(
                        highlight,
                        view,
                        candidate,
                        object.instanceOrdinal(),
                        object.sourcePartOrdinal(),
                        object.surfaceOrdinal(),
                        depth,
                        elapsedSeconds);
                return;
            }
        }
    }

    void isolateEditorActors(@Nullable Set<String> actors) {
        requireOpen();
        if (actors != null) {
            preserveEditorBaseline();
            var left = new ArrayList<BgfxSceneObject>();
            if (splitEditorActors(actors, left)) {
                onEditorObjectsChanged(left, actors);
            }
        }
        renderingContext.inspection.isolateActors(actors);
        isolatedObjectsRevision = -1;
        traversalCacheValid = false;
    }

    void setOthersTarget(Supplier<BgfxFramebuffer> target) {
        othersTarget = target;
    }

    List<DebugSceneObject> getDebugSceneObjects() {
        requireOpen();
        var result = new ArrayList<DebugSceneObject>();
        for (var object : objects) {
            addDebugObjects(object, result);
        }
        return List.copyOf(result);
    }

    int getDebugSceneObjectGroups() {
        requireOpen();
        return objects.size();
    }

    List<DebugSceneObject> getDebugSceneObjects(int group) {
        requireOpen();
        var result = new ArrayList<DebugSceneObject>();
        addDebugObjects(objects.get(group), result);
        return result;
    }

    boolean doesDebugSceneGroupMeet(int group, Bounds3f box) {
        requireOpen();
        var bounds = objects.get(group).getWorldBounds();
        return bounds == null
                || bounds.minX() <= box.maxX()
                        && bounds.maxX() >= box.minX()
                        && bounds.minY() <= box.maxY()
                        && bounds.maxY() >= box.minY()
                        && bounds.minZ() <= box.maxZ()
                        && bounds.maxZ() >= box.minZ();
    }

    DebugSceneObject refreshDebugSceneObject(DebugSceneObject selected) {
        if (refreshedSelectionRevision != sceneObjectsRevision
                || refreshedSelectionObject == null
                || refreshedSelectionObject.getStableId() != selected.renderObjectId()) {
            refreshedSelectionObject = null;
            for (var object : objects) {
                if (object.getStableId() == selected.renderObjectId()) {
                    refreshedSelectionObject = object;
                    break;
                }
            }
            refreshedSelectionRevision = sceneObjectsRevision;
        }
        var object = refreshedSelectionObject;
        if (object != null && object.isEmptySlot(Math.max(0, selected.instanceOrdinal()))) {
            return null;
        }
        if (object != null) {
            for (var ordinal = 0; ordinal < object.getGeometry().getPrimitives().size(); ordinal++) {
                if (object.getGeometry().getPrimitives().get(ordinal).sourceSurfaceOrdinal()
                        == selected.surfaceOrdinal()) {
                    if (selected.sourcePartOrdinal() >= 0) {
                        return selected.sourcePartOrdinal()
                                        < object.getSourceParts().size()
                                ? debugPart(object, ordinal, selected.sourcePartOrdinal())
                                : null;
                    }
                    return debugPlacement(object, ordinal, selected.instanceOrdinal());
                }
            }
        }
        return null;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        RuntimeException failure = null;
        failure = closeAndMerge(decorations, failure);
        failure = closeAndMerge(gpuVisibility, failure);
        resetWaterGroups();
        waterGroups.clear();
        reflectionKeysByObject.clear();
        gpuCommandOrder.clear();
        gpuGroupOrder.clear();
        gpuPlannedGroupOrder.clear();
        gpuPlannedGroupOrderSource = null;
        reflectionGpuCommands.clear();
        reflectionGpuGroups.clear();
        reflectionDirectOpaque.clear();
        reflectionDirectTranslucent.clear();
        resetCommandPool();
        commandPool.clear();
        for (var index = objects.size() - 1; index >= 0; index--) {
            failure = closeAndMerge(objects.get(index), failure);
        }
        objects.clear();
        for (var entry : retiredObjects.entrySet()) {
            var object = entry.getKey();
            failure = entry.getValue()
                    ? closeAndMerge(() -> closeEditorObject(object), failure)
                    : closeAndMerge(object, failure);
        }
        retiredObjects.clear();
        if (editorScene != null && editorScene.originals != null) {
            for (var object : editorScene.ownedObjects) {
                failure = closeAndMerge(() -> closeEditorObject(object), failure);
            }
            for (var object : editorScene.originals) {
                failure = closeAndMerge(object, failure);
            }
            editorScene = null;
        }
        movingObjects.clear();
        for (var staged : stagedObjects.values()) {
            for (var index = staged.size() - 1; index >= 0; index--) {
                failure = closeAndMerge(staged.get(index), failure);
            }
        }
        stagedObjects.clear();
        customLightSources.clear();
        nativeLightSources.clear();
        nativeWaterLighting.clear();
        spawnModelLighting.clear();
        fxRenderer.setMeshLighting(null);
        spawnModelView = null;
        mainNativeWaterView = null;
        mainNativeMeshView = null;
        mainSubmission = null;
        if (hoverHighlight != null) {
            failure = closeAndMerge(hoverHighlight, failure);
            hoverHighlight = null;
        }
        localLightSet.clear();
        localLightSnapshot = BgfxLocalLightSet.Snapshot.disabled();
        detailedTerrainOwners.clear();
        detailedTerrainOwnersDirty = false;
        gpuResidualObjects.clear();
        gpuResidualPrimitiveOrdinals.clear();
        traversalObjects.clear();
        traversalPrimitiveOrdinals.clear();
        residualTraversal.clear();
        residualTraversalSceneRevision = Long.MIN_VALUE;
        residualTraversalTopologyGeneration = Long.MIN_VALUE;
        for (var index = geometries.size() - 1; index >= 0; index--) {
            failure = closeAndMerge(geometries.get(index), failure);
        }
        geometries.clear();
        geometryByOwnerIdentity.clear();
        bspGeometryByOwner.clear();
        geometryByName.clear();
        failure = closeAndMerge(depthCopy, failure);
        depthCopy = null;
        failure = closeAndMerge(waterBackCopy, failure);
        waterBackCopy = null;
        failure = closeAndMerge(waterNormal, failure);
        waterNormal = null;
        failure = closeAndMerge(waterLegacyBump, failure);
        waterLegacyBump = null;
        failure = closeAndMerge(waterTexture, failure);
        waterTexture = null;
        failure = closeAndMerge(skyRenderer, failure);
        failure = closeAndMerge(fxRenderer, failure);
        failure = closeAndMerge(coronaRenderer, failure);
        failure = closeAndMerge(coronaLineTests, failure);
        failure = closeAndMerge(reflections, failure);
        failure = closeAndMerge(mapProjectors, failure);
        if (wireframe != null) {
            failure = closeAndMerge(wireframe, failure);
            wireframe = null;
        }
        failure = closeAndMerge(programs, failure);
        failure = closeAndMerge(localShadowAtlas, failure);
        failure = closeAndMerge(shadowRenderer, failure);
        failure = closeAndMerge(uniforms, failure);
        failure = closeAndMerge(textures, failure);
        failure = closeAndMerge(nativeVertexLightmaps, failure);
        failure = closeAndMerge(actorLightingBuffer, failure);
        failure = closeAndMerge(instancePool, failure);
        failure = closeAndMerge(geometryPool, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private int getClearanceChanges() {
        return objects.getChanges() - clearanceIgnoredChanges;
    }

    private void groupForClearance() {
        var byOwner = new HashMap<String, ClearanceGroup>();
        clearanceGroups.clear();
        String lastOwner = null;
        ClearanceGroup lastGroup = null;
        for (var object : objects) {
            if (BgfxDecorations.OWNER.equals(object.getOwner())) {
                continue;
            }
            var bounds = object.getWorldBounds();
            var owner = bounds.maxX() - bounds.minX() > WIDEST_GROUPED_OBJECT
                            || bounds.maxY() - bounds.minY() > WIDEST_GROUPED_OBJECT
                    ? ""
                    : object.getOwner();
            var group = owner.equals(lastOwner) ? lastGroup : byOwner.get(owner);
            if (group == null) {
                group = new ClearanceGroup();
                byOwner.put(owner, group);
                clearanceGroups.add(group);
            }
            lastOwner = owner;
            lastGroup = group;
            group.add(object, bounds);
        }
        clearanceGroupsChanges = getClearanceChanges();
    }

    private void updateDecorations() {
        var camera = renderingContext.camera;
        var position = camera.getPosition();
        var forward = camera.getForwardDir();
        decorations.update(
                new BgfxDecorations.Camera(
                        position.getX(),
                        position.getY(),
                        position.getZ(),
                        forward.getX(),
                        forward.getY(),
                        forward.getZ(),
                        camera.isOrtho()),
                state.settings.getDecorationFadeMultiplier(),
                zoneStateChoice());
    }

    private void decorationObjectsChanged() {
        var index = editorScene == null ? null : editorScene.byActor;
        markSceneObjectsChanged();
        if (editorScene != null) {
            editorScene.byActor = index;
        }
        residencyDirty = true;
        recordEditorTopology(Set.of());
    }

    private void withdrawDecorationPool(BgfxSceneObject pool) {
        var index = objects.indexOf(pool);
        if (index < 0) {
            return;
        }
        var changes = objects.getChanges();
        objects.remove(index);
        clearanceIgnoredChanges += objects.getChanges() - changes;
        if (index < bakedObjectCount) {
            bakedObjectCount--;
        }
        reflectionKeysByObject.remove(pool);
        if (editorScene != null && editorScene.originals != null) {
            editorScene.originals.remove(pool);
        }
        var withdrawn = new ArrayList<BgfxSceneObject>();
        closeOrRetire(pool, false, true, withdrawn);
        if (!withdrawn.isEmpty()) {
            gpuVisibility.withdraw(withdrawn);
            retiredTopologyGeneration = gpuVisibility.getTopologyGeneration();
        }
    }

    private void configureWater(World worldConfig) {
        var configured = worldConfig.rendering().waterColorReference();
        waterColorReference = new Vec4(
                configured.red() / 255f,
                configured.green() / 255f,
                configured.blue() / 255f,
                configured.alpha() / 255f);
        waterReflectionReference = configured.reflectionReference() / 255f;
        waterBumpAmplitude = (float) configured.bumpAmplitude();
        waterLinearFiltering = worldConfig.rendering().waterLinearFiltering();
        textureMipBias = (float) worldConfig.rendering().textureMipBias();
        waterNormalFilters = TextureFilters.INHERITED;
        waterReflectionExtent = reflectionExtent(worldConfig.rendering().waterEffectResolution());
        if (waterNormal != null) {
            waterNormal.close();
            waterNormal = null;
        }
        if (waterLegacyBump != null) {
            waterLegacyBump.close();
            waterLegacyBump = null;
        }
        if (waterTexture != null) {
            waterTexture.close();
            waterTexture = null;
        }
        try {
            var rendering = worldConfig.rendering();
            var loader = new UnrealLoader(
                    worldConfig.clientPath(),
                    new Options(rendering.dxtMipCodecEnabled(), rendering.dxtMipMinSize()),
                    false,
                    rendering.textureQuality());
            var normal = loader.loadHighWaterNormalTexture(worldConfig.clientPath());
            if (normal.isPresent()) {
                waterNormal = textures.load(normal.get(), "water/cubemaps.water_normal");
                waterNormalFilters = normal.get().filters();
                log.debug(
                        "Loaded HF WaterSurfacePS normal cubemaps.water_normal ({}x{})",
                        normal.get().width(),
                        normal.get().height());
            } else {
                log.warn("HF WaterSurfacePS normal cubemaps.water_normal is unavailable");
            }
        } catch (RuntimeException unavailable) {
            log.warn("HF WaterSurfacePS normal is unavailable: {}", unavailable.toString());
        }
        try {
            var rendering = worldConfig.rendering();
            var loader = new UnrealLoader(
                    worldConfig.clientPath(),
                    new Options(rendering.dxtMipCodecEnabled(), rendering.dxtMipMinSize()),
                    false,
                    rendering.textureQuality());
            var sequence = loader.loadHighWaterDiffuseTexture(worldConfig.clientPath());
            waterTexture = new BgfxWaterTexture(
                    textures,
                    sequence.orElse(null),
                    System.nanoTime() * 1e-9,
                    waterLinearFiltering,
                    textures.getBitmapClocks());
            if (sequence.isEmpty()) {
                log.warn("WaterSurface bitmap FX_E_T.ocean011 is unavailable");
            }
        } catch (RuntimeException unavailable) {
            log.warn("WaterSurface bitmap is unavailable: {}", unavailable.toString());
        }
        try {
            var normal = BgfxWaterNormalData.read(worldConfig.clientPath());
            waterLegacyBump = textures.createGeneratedRgba(
                    "water/wform", normal.width(), normal.height(), normal.rgba(), false, false);
            log.debug("Loaded HF legacy TEXBEM field system/Wform.bm ({}x{})", normal.width(), normal.height());
        } catch (IOException unavailable) {
            log.warn("HF legacy TEXBEM field unavailable; legacy ripple disabled: {}", unavailable.toString());
        }
    }

    private boolean replacesTerrainProxy(String owner, List<BgfxSceneObject> staged) {
        if (BgfxSceneUploadSink.isTerrainProxyOwner(owner)
                || staged.stream().noneMatch(object -> object.getGeometry().getKind() == Kind.TERRAIN)) {
            return false;
        }
        var proxyOwner = "@terrain/" + owner;
        return objects.stream()
                .anyMatch(object -> proxyOwner.equals(object.getOwner())
                        && object.getGeometry().getKind() == Kind.TERRAIN);
    }

    private boolean evictMaps(Set<String> owners, boolean keepTopology) {
        requireOpen();
        if (owners == null) {
            throw new IllegalArgumentException("map owners cannot be null");
        }
        if (owners.isEmpty()) {
            return false;
        }
        for (var owner : owners) {
            if (owner == null || owner.isBlank()) {
                throw new IllegalArgumentException("map owner cannot be null or blank");
            }
        }
        for (var owner : owners) {
            renderingContext.onEditorMapEvicted(owner);
        }
        decorations.evict(owners);
        var removedEditorObjects = false;
        var withdrawn = new ArrayList<BgfxSceneObject>();
        if (editorScene != null && editorScene.originals != null) {
            removedEditorObjects = editorScene.originals.removeIf(object -> {
                if (!owners.contains(object.getOwner())) {
                    return false;
                }
                closeOrRetire(object, false, keepTopology, withdrawn);
                return true;
            });
            removedEditorObjects |= editorScene.ownedObjects.removeIf(object -> {
                if (!owners.contains(object.getOwner())) {
                    return false;
                }
                closeOrRetire(object, true, keepTopology, withdrawn);
                return true;
            });
            editorScene.deltas.keySet().removeIf(actor -> owners.contains(sceneMapOf(actor)));
        }
        skyRenderer.evictMaps(owners);
        if (metalRayQueries != null) {
            owners.forEach(metalRayQueries::removeOwner);
        }
        mapProjectors.evict(owners);
        var removed = removedEditorObjects;
        var removedBakedObjects = 0;
        var retainedObjects = 0;
        var originalObjectCount = objects.size();
        for (var originalObjectIndex = 0; originalObjectIndex < originalObjectCount; originalObjectIndex++) {
            var object = objects.get(originalObjectIndex);
            if (!owners.contains(object.getOwner())) {
                objects.set(retainedObjects++, object);
                continue;
            }
            if (originalObjectIndex < bakedObjectCount) {
                removedBakedObjects++;
            }
            reflectionKeysByObject.remove(object);
            closeOrRetire(object, false, keepTopology, withdrawn);
            removed = true;
        }
        gpuVisibility.withdraw(withdrawn);
        if (retainedObjects < originalObjectCount) {
            objects.subList(retainedObjects, originalObjectCount).clear();
        }
        for (var owner : owners) {
            var staged = stagedObjects.remove(owner);
            if (staged != null) {
                for (var object : staged) {
                    reflectionKeysByObject.remove(object);
                    closeEditorObject(object);
                    removed = true;
                }
            }
        }
        if (localShadowAtlas != null) {
            localShadowAtlas.evict(owners);
        }
        localLightSet.evict(owners);
        for (var owner : owners) {
            customLightSources.remove(owner);
            nativeLightSources.remove(owner);
            nativeWaterLighting.remove(owner);
            nativeVertexLightmaps.remove(owner);
            renderingContext.scene.getVertexLightmaps().remove(owner);
        }
        if (!removed) {
            return false;
        }
        markSceneObjectsChanged();
        releaseUnreferencedGeometries();
        hasWater = objects.stream()
                .flatMap(object -> object.getGeometry().getPrimitives().stream())
                .anyMatch(primitive -> primitive.entityMaterial() != null
                        && primitive.entityMaterial().getProgramFamily() == ProgramFamily.WATER);
        traversalObjects.clear();
        traversalPrimitiveOrdinals.clear();
        candidates.clear();
        opaqueCandidates.clear();
        opaque.clear();
        translucent.clear();
        water.clear();
        resetWaterGroups();
        gpuCommandOrder.clear();
        gpuGroupOrder.clear();
        reflectionGpuCommands.clear();
        reflectionGpuGroups.clear();
        reflectionDirectOpaque.clear();
        reflectionDirectTranslucent.clear();
        resetCommandPool();
        retainReflectionOwners();
        bakedObjectCount = Math.max(0, bakedObjectCount - removedBakedObjects);
        if (keepTopology && baked && !objects.isEmpty()) {
            if (!withdrawn.isEmpty()) {
                retiredTopologyGeneration = gpuVisibility.getTopologyGeneration();
            }
            if (!gpuTopologyDirty) {
                topologyDirtyFrames = 0;
            }
            gpuTopologyDirty = true;
        } else if (baked && !objects.isEmpty()) {
            gpuVisibility.rebuild(gpuVisibilityObjects());
            clearPendingTopology();
        } else {
            if (objects.isEmpty()) {
                bakedObjectCount = 0;
            }
            gpuVisibility.invalidateTopology(
                    objects.isEmpty()
                            ? "map residency eviction emptied opaque topology"
                            : "map residency changed before instance bake");
            clearPendingTopology();
        }
        residencyDirty = true;
        return true;
    }

    private void closeOrRetire(
            BgfxSceneObject object, boolean editorObject, boolean keepTopology, List<BgfxSceneObject> withdrawn) {
        if (retiredObjects.containsKey(object)) {
            retiredObjects.put(object, retiredObjects.get(object) || editorObject);
            return;
        }
        if (keepTopology && gpuVisibility.includes(object)) {
            retiredObjects.put(object, editorObject);
            withdrawn.add(object);
            return;
        }
        if (editorObject) {
            closeEditorObject(object);
        } else {
            object.close();
        }
    }

    private void releaseRetiredObjects() {
        if (retiredObjects.isEmpty() || gpuVisibility.getTopologyGeneration() == retiredTopologyGeneration) {
            return;
        }
        var retired = new ArrayList<>(retiredObjects.entrySet());
        retiredObjects.clear();
        for (var entry : retired) {
            if (entry.getValue()) {
                closeEditorObject(entry.getKey());
            } else {
                entry.getKey().close();
            }
        }
        releaseUnreferencedGeometries();
    }

    private void releaseUnreferencedGeometries() {
        var referenced = new IdentityHashMap<BgfxSceneGeometry, Boolean>();
        for (var object : objects) {
            referenced.put(object.getGeometry(), Boolean.TRUE);
        }
        if (editorScene != null && editorScene.originals != null) {
            for (var object : editorScene.originals) {
                referenced.put(object.getGeometry(), Boolean.TRUE);
            }
        }
        for (var pending : stagedObjects.values()) {
            for (var object : pending) {
                referenced.put(object.getGeometry(), Boolean.TRUE);
            }
        }
        for (var object : retiredObjects.keySet()) {
            referenced.put(object.getGeometry(), Boolean.TRUE);
        }
        if (editorScene != null && metalRayQueries != null) {
            for (var geometry : editorScene.ownedGeometries) {
                if (!referenced.containsKey(geometry)) {
                    metalRayQueries.releaseEditorGeometry(geometry);
                }
            }
        }
        if (referenced.isEmpty()) {
            for (var geometry : geometries) {
                geometry.close();
            }
            geometries.clear();
            geometryByOwnerIdentity.clear();
            bspGeometryByOwner.clear();
            geometryByName.clear();
        } else {
            for (var iterator = geometries.iterator(); iterator.hasNext(); ) {
                var geometry = iterator.next();
                if (referenced.containsKey(geometry)) {
                    continue;
                }
                iterator.remove();
                geometry.close();
            }
            for (var cache : geometryByOwnerIdentity.values()) {
                cache.entrySet().removeIf(entry -> !referenced.containsKey(entry.getValue()));
            }
            geometryByName.entrySet().removeIf(entry -> !referenced.containsKey(entry.getValue()));
            geometryByOwnerIdentity
                    .entrySet()
                    .removeIf(entry -> entry.getValue().isEmpty());
            for (var resident : bspGeometryByOwner.values()) {
                resident.values().removeIf(geometry -> !referenced.containsKey(geometry));
            }
            bspGeometryByOwner.values().removeIf(Map::isEmpty);
        }
        if (editorScene != null) {
            editorScene.ownedGeometries.removeIf(geometry -> !referenced.containsKey(geometry));
        }
    }

    private void flushPendingTopology(boolean force) {
        if (!gpuTopologyDirty || !force) {
            return;
        }
        var topologyStarted = performance.start();
        if (objects.isEmpty()) {
            gpuVisibility.invalidateTopology("streaming batch contains no opaque topology");
        } else {
            rebuildPendingTopology();
        }
        clearPendingTopology();
        performance.addPhase(CpuPhase.TOPOLOGY_UPDATE, performance.getElapsed(topologyStarted));
    }

    private void rebuildPendingTopology() {
        var visibleObjects = gpuVisibilityObjects();
        if (topologyRequiresHistoryReset) {
            gpuVisibility.rebuild(visibleObjects);
        } else {
            gpuVisibility.rebuildPreservingHistory(visibleObjects);
        }
    }

    private void clearPendingTopology() {
        gpuTopologyDirty = false;
        topologyRequiresHistoryReset = false;
        pendingTopologyPublications = 0;
        topologyDirtyFrames = 0;
    }

    private int submitOthers(
            int view,
            Extent extent,
            float elapsedSeconds,
            FloatBuffer mainView,
            FloatBuffer mainProjection,
            BgfxNativeView nativeView,
            Submission main) {
        var camera = renderingContext.camera;
        setupView(view, "scene-isolate-others", othersTarget.get(), extent, BGFX_VIEW_MODE_SEQUENTIAL);
        bgfx_set_view_clear(view, BGFX_CLEAR_DEPTH, 0, 1.0f, 0);
        bgfx_set_view_transform(view, mainView, mainProjection);
        bgfx_touch(view);
        var submission =
                othersSubmissionState.main(extent, camera.getPosition(), main.environment, false, main.inWaterPlaneZ);
        submission.nativeWaterView = main.nativeWaterView;
        submission.nativeMeshView = nativeMeshDraws.open(
                nativeView, camera.isOrtho(), false, state.settings.getImpostorDistanceRatio(), false);
        var level = Math.max(1, Math.round(renderingContext.inspection.getOthersOpacity() * 255f));
        othersFactor = level << 24 | level << 16 | level << 8 | level;
        var submitted = 0;
        try {
            for (var command : others) {
                if (submit(view, command, elapsedSeconds, submission)) {
                    submitted++;
                }
            }
        } finally {
            othersFactor = 0;
        }
        return submitted;
    }

    private boolean ensureWireframe() {
        if (wireframe == null && !wireframeUnsupported) {
            if (!BgfxWireframe.isSupported(device.getCapabilities())) {
                wireframeUnsupported = true;
                log.warn("Wireframe needs compute shaders and 32-bit indices. The view is drawn unlit instead");
                return false;
            }
            wireframe = new BgfxWireframe(device, programs);
            geometryPool.enableLines(wireframe);
        }
        return wireframe != null;
    }

    private BgfxFrameEnvironment.Snapshot renderWireframe(
            BgfxFramebuffer target,
            BgfxTexture targetPackedDepth,
            Extent extent,
            BgfxViewAllocator views,
            float elapsedSeconds,
            FloatBuffer mainView,
            FloatBuffer mainProjection,
            Submission submission,
            BgfxFrameEnvironment.Snapshot frameEnvironment,
            long started,
            long encodingStarted) {
        var clearView = views.allocate("world-clear", BgfxViewAllocator.Domain.RENDER, false);
        setupView(clearView, "world-clear", target, extent, BGFX_VIEW_MODE_DEFAULT);
        if (targetPackedDepth != null) {
            bgfx_set_palette_color_rgba8(0, BgfxWireframe.BACKGROUND);
            bgfx_set_palette_color_rgba8(1, 0xffffff00);
            bgfx_set_palette_color_rgba8(2, 0x00000000);
            bgfx_set_view_clear_mrt(
                    clearView,
                    BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH | BGFX_CLEAR_STENCIL,
                    1.0f,
                    0,
                    (byte) 0,
                    (byte) 1,
                    (byte) 2,
                    (byte) 2,
                    (byte) 2,
                    (byte) 0xff,
                    (byte) 0xff,
                    (byte) 0xff);
        } else {
            bgfx_set_view_clear(
                    clearView,
                    BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH | BGFX_CLEAR_STENCIL,
                    BgfxWireframe.BACKGROUND,
                    1.0f,
                    0);
        }
        bgfx_touch(clearView);
        var lineView = views.allocate("scene-wireframe", BgfxViewAllocator.Domain.RENDER, false);
        setupView(lineView, "scene-wireframe", target, extent, BGFX_VIEW_MODE_SEQUENTIAL);
        bgfx_set_view_transform(lineView, mainView, mainProjection);
        bgfx_touch(lineView);
        var submitted = 0;
        for (var kind : BgfxWireframe.Kind.values()) {
            submitted += submitWireframe(lineView, opaque, kind, elapsedSeconds, submission);
            submitted += submitWireframe(lineView, translucent, kind, elapsedSeconds, submission);
            submitted += submitWireframe(lineView, water, kind, elapsedSeconds, submission);
        }
        state.stats.waterReflectionReady = false;
        state.stats.waterBackReady = false;
        performance.addPhase(CpuPhase.COMMAND_ENCODING, performance.getElapsed(encodingStarted));
        performance.directSceneSubmission(performance.getElapsed(started));
        if (performance.isEnabled()) {
            updateUiCounters(submitted, started, false);
        }
        return frameEnvironment;
    }

    private int submitWireframe(
            int view,
            List<DrawCommand> commands,
            BgfxWireframe.Kind kind,
            float elapsedSeconds,
            Submission submission) {
        var submitted = 0;
        for (var command : commands) {
            if (BgfxWireframe.Kind.of(command.primitive()) == kind
                    && submitWireframe(view, command, kind, elapsedSeconds, submission)) {
                submitted++;
            }
        }
        return submitted;
    }

    private boolean submitWireframe(
            int view, DrawCommand command, BgfxWireframe.Kind kind, float elapsedSeconds, Submission submission) {
        var actor = renderingContext.inspection.getIsolatedSourceActor();
        var primitive = command.primitive();
        if (actor == null) {
            return submitWireframeRange(
                    view, command, kind, elapsedSeconds, submission, primitive.indexOffset(), primitive.indexCount());
        }
        var submitted = false;
        for (var part : command.object.getSourceParts()) {
            if (!part.actor().equals(actor)) {
                continue;
            }
            var first = Math.max(primitive.indexOffset(), part.indexOffset());
            var end =
                    Math.min(primitive.indexOffset() + primitive.indexCount(), part.indexOffset() + part.indexCount());
            if (first < end) {
                submitted |= submitWireframeRange(view, command, kind, elapsedSeconds, submission, first, end - first);
            }
        }
        return submitted;
    }

    private boolean submitWireframeRange(
            int view,
            DrawCommand command,
            BgfxWireframe.Kind kind,
            float elapsedSeconds,
            Submission submission,
            int firstIndex,
            int indexCount) {
        var object = command.object;
        var geometry = object.getGeometry();
        var primitive = command.primitive();
        BgfxNativeMeshDraws.Draw nativeDraw = null;
        if (object.getNativeStaticMeshDraw() != null && mainNativeMeshView != null) {
            nativeDraw = mainNativeMeshView.prepare(
                    object.getStableId(),
                    object.getNativeStaticMeshDraw(),
                    object.getNativeActorX(),
                    object.getNativeActorY());
            if (!nativeDraw.visible()) {
                return false;
            }
        }
        if (!device.getSubmissionBudget().reserve(BgfxSubmissionBudget.Domain.MAIN, sceneUniformBytes(command), 1)) {
            return false;
        }
        if (!geometry.bindLines(firstIndex, indexCount)) {
            return false;
        }
        bindFrame(submission, object, primitive, elapsedSeconds);
        var instances = 1;
        if (geometry.getKind().isInstanced()) {
            var isolatedInstance = renderingContext.inspection.getIsolatedInstance();
            if (isolatedInstance >= 0) {
                object.bindInstances(isolatedInstance, 1);
            } else {
                object.bindInstances();
                instances = object.getInstanceCount();
            }
            actorLightingBuffer.bind();
            var material = primitive.entityMaterial();
            material.bind(
                    uniforms,
                    elapsedSeconds,
                    state.settings.isTexturesEnabled(),
                    object.getLightmap(submission.environment.shadowVariant()),
                    object.getLightmapLayer(submission.environment.shadowVariant()),
                    semanticDepthBias(material.getSurfaceType()));
            if (nativeDraw != null) {
                uniforms.bindNativeMeshDraw(nativeDraw);
            }
            if (material.getProgramFamily() == ProgramFamily.WATER) {
                uniforms.bindNativeWaterLights(null);
                uniforms.bindNativeVertexLightmap(null);
            }
        } else {
            uniforms.bindTerrainModel(object.getTerrainModel());
            primitive
                    .terrainMaterial()
                    .bind(
                            uniforms,
                            state.settings.isTexturesEnabled(),
                            submission.environment.terrainAmbient(),
                            submission.environment.terrainSun(),
                            graphics.optimizations().fillReflection(),
                            zoneStateChoice());
        }
        wireframe.bindColor(kind);
        bgfx_set_state(BgfxWireframe.STATE, 0);
        bgfx_submit(view, wireframe.getProgram(primitive).getHandle(), 0, BGFX_DISCARD_ALL);
        recordDirectSceneDraw(familyOf(primitive), object, primitive, indexCount, instances, false);
        return true;
    }

    private BgfxFrameEnvironment.Snapshot renderLoadingFrame(
            BgfxFramebuffer target,
            BgfxTexture targetPackedDepth,
            Extent extent,
            BgfxViewAllocator views,
            long started,
            float elapsedSeconds) {
        var encodingStarted = performance.start();
        var frameEnvironment = environment.resolve(false, 0f);
        refreshNativeLights(elapsedSeconds, 0f, false);
        fxRenderer.configureFrameEnvironment(frameEnvironment, textureMipBias);
        fxRenderer.setMeshLighting(null);
        var clearView = views.allocate("world-loading-clear", BgfxViewAllocator.Domain.RENDER, false);
        setupView(clearView, "world-loading-clear", target, extent, BGFX_VIEW_MODE_DEFAULT);
        if (targetPackedDepth != null) {
            bgfx_set_palette_color_rgba8(0, renderingContext.blackBackdrop ? 0x000000ff : 0x1a1a1aff);
            bgfx_set_palette_color_rgba8(1, 0xffffff00);
            bgfx_set_palette_color_rgba8(2, 0x00000000);
            bgfx_set_view_clear_mrt(
                    clearView,
                    BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH | BGFX_CLEAR_STENCIL,
                    1.0f,
                    0,
                    (byte) 0,
                    (byte) 1,
                    (byte) 2,
                    (byte) 2,
                    (byte) 2,
                    (byte) 0xff,
                    (byte) 0xff,
                    (byte) 0xff);
        } else {
            bgfx_set_view_clear(
                    clearView,
                    BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH | BGFX_CLEAR_STENCIL,
                    renderingContext.blackBackdrop ? 0x000000ff : 0x1a1a1aff,
                    1.0f,
                    0);
        }
        bgfx_touch(clearView);
        state.stats.waterReflectionReady = false;
        state.stats.waterBackReady = false;
        state.stats.realisticWaterStatus =
                state.settings.isL2WaterShaderEnabled() ? "Inactive. Scene is not rendered" : "Off";
        performance.addPhase(CpuPhase.COMMAND_ENCODING, performance.getElapsed(encodingStarted));
        performance.directSceneSubmission(performance.getElapsed(started));
        return frameEnvironment;
    }

    private void ensureCustomLightingResources() {
        var rendering = state;
        if (!rendering.isCustomLightingEffective()) {
            return;
        }
        var needsDepthPrograms =
                rendering.settings.isCustomSunShadowsEnabled() || rendering.settings.isCustomLocalLightShadowsEnabled();
        if (needsDepthPrograms && shadowRenderer == null && !shadowResourcesAttempted) {
            shadowResourcesAttempted = true;
            try {
                shadowRenderer = new BgfxShadowRenderer(device);
            } catch (RuntimeException unavailable) {
                rendering.stats.customSunShadowsAvailable = false;
                rendering.stats.customLocalLightShadowsAvailable = false;
                log.warn("Custom shadow resources unavailable: {}", unavailable.toString());
            }
        }
        if (rendering.settings.isCustomLocalLightShadowsEnabled()
                && shadowRenderer != null
                && localShadowAtlas == null
                && !localShadowResourcesAttempted) {
            localShadowResourcesAttempted = true;
            try {
                localShadowAtlas = new BgfxLocalShadowAtlas(device, shadowRenderer);
                for (var entry : customLightSources.entrySet()) {
                    localShadowAtlas.publish(entry.getKey(), entry.getValue());
                }
            } catch (RuntimeException unavailable) {
                rendering.stats.customLocalLightShadowsAvailable = false;
                log.warn("Custom local-shadow atlas unavailable: {}", unavailable.toString());
            }
        }
        rendering.stats.customSunShadowsAvailable = shadowRenderer != null
                || !shadowResourcesAttempted && BgfxShadowRenderer.isSupported(device.getCapabilities());
        rendering.stats.customLocalLightShadowsAvailable = localShadowAtlas != null
                || !localShadowResourcesAttempted
                        && rendering.stats.customSunShadowsAvailable
                        && BgfxLocalShadowAtlas.isSupported(device.getCapabilities());
    }

    private void buildCommands(
            boolean deferOpaqueVisibility, boolean unculledStableIdReference, int activeWaterRoute, Extent extent) {
        resetCommandPool();
        candidates.clear();
        opaqueCandidates.clear();
        opaque.clear();
        translucent.clear();
        water.clear();
        othersCandidates.clear();
        others.clear();
        resetWaterGroups();
        opaqueMaterialized = !deferOpaqueVisibility;
        var settings = state;
        var frustum = settings.settings.isCullingEnabled() ? renderingContext.camera.getFrustum() : null;
        var camera = renderingContext.camera.getPosition();
        var traversalStarted = performance.start();
        var frustumCulled = 0;
        refreshDetailedTerrainOwners();
        List<BgfxSceneObject> sourceObjects = objects;
        if (deferOpaqueVisibility) {
            refreshGpuResidualObjects();
            sourceObjects = gpuResidualObjects;
        }
        var topologyGeneration = deferOpaqueVisibility ? gpuVisibility.getTopologyGeneration() : Long.MIN_VALUE;
        var hierarchyFrame = device.getStats().frameNumber();
        var hierarchicalTraversal = isResidualTraversalModeRequested(hierarchyFrame) && deferOpaqueVisibility;
        if (hierarchicalTraversal
                && (residualTraversalSceneRevision != sceneObjectsRevision
                        || residualTraversalTopologyGeneration != topologyGeneration)) {
            residualTraversal.rebuild(sourceObjects, this::isShadowedTerrainProxy);
            residualTraversalSceneRevision = sceneObjectsRevision;
            residualTraversalTopologyGeneration = topologyGeneration;
        }
        if (!traversalCacheValid
                || traversalSceneRevision != sceneObjectsRevision
                || traversalTopologyGeneration != topologyGeneration
                || traversalFrustum != frustum
                || traversalDeferredOpaque != deferOpaqueVisibility
                || traversalHierarchical != hierarchicalTraversal
                || traversalUnculledReference != unculledStableIdReference) {
            traversalObjects.clear();
            traversalPrimitiveOrdinals.clear();
            if (hierarchicalTraversal) {
                residualTraversal.collect(unculledStableIdReference ? null : frustum);
                frustumCulled = residualTraversal.getCulledPrimitives();
                if (RESIDUAL_TRAVERSAL_AUDIT_FRAMES > 0 && hierarchyFrame % RESIDUAL_TRAVERSAL_AUDIT_FRAMES == 0) {
                    residualTraversal.audit(unculledStableIdReference ? null : frustum);
                    log.info(
                            "Residual traversal audit: frame={} sourceObjects={} visible={} rejectedPrimitives={}",
                            hierarchyFrame,
                            sourceObjects.size(),
                            residualTraversal.getCount(),
                            frustumCulled);
                }
            }
            var sourceCount = hierarchicalTraversal ? residualTraversal.getCount() : sourceObjects.size();
            for (var sourceOrdinal = 0; sourceOrdinal < sourceCount; sourceOrdinal++) {
                var sourceIndex =
                        hierarchicalTraversal ? residualTraversal.getObjectIndex(sourceOrdinal) : sourceOrdinal;
                var object = sourceObjects.get(sourceIndex);
                if (!hierarchicalTraversal && isShadowedTerrainProxy(object)) {
                    continue;
                }
                if (!hierarchicalTraversal
                        && !unculledStableIdReference
                        && frustum != null
                        && !intersects(frustum, object.getWorldBounds())) {
                    frustumCulled += object.getGeometry().getPrimitives().size();
                    continue;
                }
                traversalObjects.add(object);
                if (deferOpaqueVisibility) {
                    traversalPrimitiveOrdinals.add(gpuResidualPrimitiveOrdinals.get(sourceIndex));
                }
            }
            traversalCacheValid = true;
            traversalSceneRevision = sceneObjectsRevision;
            traversalTopologyGeneration = topologyGeneration;
            traversalFrustum = frustum;
            traversalDeferredOpaque = deferOpaqueVisibility;
            traversalUnculledReference = unculledStableIdReference;
            traversalHierarchical = hierarchicalTraversal;
            traversalFrustumCulled = frustumCulled;
        } else {
            frustumCulled = traversalFrustumCulled;
        }
        performance.addPhase(CpuPhase.TRAVERSAL, performance.getElapsed(traversalStarted));

        var candidateStarted = performance.start();
        var inspection = renderingContext.inspection;
        var othersShown = othersTarget != null && inspection.areOthersShown();
        var zoneRule = sceneZones.isActive();
        var partlyIsolated = inspection.getIsolatedSourceActor() != null || inspection.getIsolatedInstance() >= 0;
        for (var objectIndex = 0; objectIndex < traversalObjects.size(); objectIndex++) {
            var object = traversalObjects.get(objectIndex);
            var accepted = acceptsInspection(object);
            var faint = othersShown && (!accepted || partlyIsolated);
            if (!accepted && !faint) {
                continue;
            }
            var primitives = object.getGeometry().getPrimitives();
            var residualOrdinals = deferOpaqueVisibility ? traversalPrimitiveOrdinals.get(objectIndex) : null;
            var primitiveCount = residualOrdinals == null ? primitives.size() : residualOrdinals.length;
            for (var primitiveIndex = 0; primitiveIndex < primitiveCount; primitiveIndex++) {
                var primitiveOrdinal = residualOrdinals == null ? primitiveIndex : residualOrdinals[primitiveIndex];
                var primitive = primitives.get(primitiveOrdinal);
                var material = primitive.entityMaterial();
                var surfaceType = material == null ? SurfaceType.TERRAIN.getId() : material.getSurfaceType();
                if (!isSurfaceEnabled(surfaceType, settings.settings)
                        || material != null && material.getBlendMode() == BlendMode.INVISIBLE) {
                    continue;
                }
                if (zoneRule && !sceneZones.draws(object, primitive, primitiveOrdinal)) {
                    continue;
                }
                var bounds = object.getPrimitiveBounds(primitiveOrdinal);
                var distance = distanceSquared(bounds, camera.getX(), camera.getY(), camera.getZ());
                if (accepted) {
                    candidates.add(acquireCommand(object, primitiveOrdinal, distance));
                }
                if (faint) {
                    othersCandidates.add(acquireCommand(object, primitiveOrdinal, distance));
                }
            }
        }
        performance.addPhase(CpuPhase.CANDIDATE_GENERATION, performance.getElapsed(candidateStarted));

        var visibilityStarted = performance.start();
        var distanceCulled = 0;
        for (var command : candidates) {
            var object = command.object;
            var primitive = command.primitive();
            var material = primitive.entityMaterial();
            var surfaceType = material == null ? SurfaceType.TERRAIN.getId() : material.getSurfaceType();
            if (unculledStableIdReference
                    && (material != null
                            && (material.isTranslucent() || material.getProgramFamily() == ProgramFamily.WATER))) {
                continue;
            }
            var bounds = object.getPrimitiveBounds(command.primitiveOrdinal);
            if (!unculledStableIdReference && frustum != null && !intersects(frustum, bounds)) {
                frustumCulled++;
                continue;
            }
            if (!unculledStableIdReference
                    && settings.settings.isStaticMeshesDistCullingEnabled()
                    && isBeyondStaticMeshDistance(primitive, command.distanceSquared)) {
                distanceCulled++;
                continue;
            }
            if (material != null && material.getProgramFamily() == ProgramFamily.WATER) {
                water.add(command);
                if (activeWaterRoute != 0) {
                    var key = reflectionKey(object, command.primitiveOrdinal, primitive);
                    if (key != null
                            && primitive.waterSheetSource().isInFrontOf(camera)
                            && (activeWaterRoute == WaterRenderRoute.PS19
                                    || command.distanceSquared <= REFLECTION_WORKING_SET_SQ_RADIUS)) {
                        var group = waterGroups.get(key);
                        if (group == null) {
                            group = new WaterGroup(key);
                            waterGroups.put(key, group);
                        }
                        if (group.commands.isEmpty()) {
                            activeWaterGroups.add(group);
                        }
                        group.commands.add(command);
                        reflectedWaterCommands.add(command);
                    }
                }
            } else if (material != null && material.isTranslucent()) {
                if (!unculledStableIdReference
                        && (surfaceType & SurfaceType.STATIC_MESH.getId()) != 0
                        && gpuVisibility.isProjectedSizeInvisible(
                                bounds, extent, object.getTerrainDecorationZone() >= 0)) {
                    distanceCulled++;
                    continue;
                }
                translucent.add(command);
            } else {
                opaque.add(command);
            }
        }
        for (var command : othersCandidates) {
            if (isOtherDrawn(command, frustum)) {
                others.add(command);
            }
        }
        others.sort(OTHERS_ORDER);
        performance.addPhase(CpuPhase.VISIBILITY, performance.getElapsed(visibilityStarted));
        visibleWaterConsumers = water.size();
        if (performance.isEnabled()) {
            performance.visibility(new Visibility(
                    objects.size(),
                    candidates.size(),
                    frustumCulled,
                    distanceCulled,
                    0,
                    opaque.size() + translucent.size() + water.size(),
                    0,
                    true,
                    false,
                    true));
        }
    }

    private boolean isOtherDrawn(DrawCommand command, @Nullable Frustum frustum) {
        var primitive = command.primitive();
        var material = primitive.entityMaterial();
        if (material != null && (material.isTranslucent() || material.getProgramFamily() == ProgramFamily.WATER)) {
            return false;
        }
        if (frustum != null && !intersects(frustum, command.object.getPrimitiveBounds(command.primitiveOrdinal))) {
            return false;
        }
        return !state.settings.isStaticMeshesDistCullingEnabled()
                || !isBeyondStaticMeshDistance(primitive, command.distanceSquared);
    }

    private void ensureCpuOpaqueCommands() {
        if (opaqueMaterialized) {
            return;
        }
        var started = performance.start();
        var settings = state;
        var frustum = settings.settings.isCullingEnabled() ? renderingContext.camera.getFrustum() : null;
        if (gpuVisibility.isPreparedThisFrame()) {
            var camera = renderingContext.camera.getPosition();
            for (var gpuCommand : gpuVisibility.getTopology().getCommandsList()) {
                for (var seed : gpuCommand.seeds()) {
                    var object = seed.object();
                    var primitiveOrdinal = seed.primitiveOrdinal();
                    var primitive = seed.primitive();
                    var material = primitive.entityMaterial();
                    var surfaceType = material == null ? SurfaceType.TERRAIN.getId() : material.getSurfaceType();
                    if (!isSurfaceEnabled(surfaceType, settings.settings)) {
                        continue;
                    }
                    var bounds = object.getPrimitiveBounds(primitiveOrdinal);
                    if (frustum != null && !intersects(frustum, bounds)) {
                        continue;
                    }
                    var distance = distanceSquared(bounds, camera.getX(), camera.getY(), camera.getZ());
                    if (settings.settings.isStaticMeshesDistCullingEnabled()
                            && isBeyondStaticMeshDistance(primitive, distance)) {
                        continue;
                    }
                    opaque.add(acquireCommand(object, primitiveOrdinal, distance));
                }
            }
        }
        for (var command : opaqueCandidates) {
            var primitive = command.primitive();
            var material = primitive.entityMaterial();
            var surfaceType = material == null ? SurfaceType.TERRAIN.getId() : material.getSurfaceType();
            if (frustum != null && !intersects(frustum, command.object.getPrimitiveBounds(command.primitiveOrdinal))) {
                continue;
            }
            if (settings.settings.isStaticMeshesDistCullingEnabled()
                    && isBeyondStaticMeshDistance(primitive, command.distanceSquared)) {
                continue;
            }
            opaque.add(command);
        }
        opaqueMaterialized = true;
        if (graphics.optimizations().fillReflection()) {
            sortReorderableOpaqueSegments(opaque);
        }
        performance.addPhase(CpuPhase.VISIBILITY, performance.getElapsed(started));
    }

    private List<BgfxSceneObject> gpuVisibilityObjects() {
        return new ArrayList<>(objects);
    }

    private void refreshDetailedTerrainOwners() {
        if (!detailedTerrainOwnersDirty) {
            return;
        }
        detailedTerrainOwners.clear();
        terrainDetails.clear();
        terrainProxies.clear();
        terrainSectors.clear();
        for (var object : objects) {
            if (object.getGeometry().getKind() != Kind.TERRAIN) {
                continue;
            }
            if (BgfxSceneUploadSink.isTerrainProxyOwner(object.getOwner())) {
                terrainProxies
                        .computeIfAbsent(object.getTerrainPair(), ignored -> new ArrayList<>())
                        .add(object);
                continue;
            }
            detailedTerrainOwners.add(object.getOwner());
            terrainDetails
                    .computeIfAbsent(object.getTerrainPair(), ignored -> new ArrayList<>())
                    .add(object);
            var terrain = TerrainEditorGeometry.sectorOwner(object.getSourceActor(0));
            if (terrain != null) {
                terrainSectors
                        .computeIfAbsent(terrain, ignored -> new ArrayList<>())
                        .add(object);
            }
        }
        detailedTerrainOwnersDirty = false;
    }

    private void applyZoneStates() {
        var choice = zoneStateChoice();
        var fades = renderingContext.zoneFades;
        var fadesRevision = fades.getRevision();
        var sceneChanged = !choice.equals(appliedZoneState) || zoneStateSceneRevision != sceneObjectsRevision;
        if (!sceneChanged && fadesRevision == appliedZoneFadesRevision) {
            return;
        }
        appliedZoneState = choice;
        zoneStateSceneRevision = sceneObjectsRevision;
        appliedZoneFadesRevision = fadesRevision;
        var changed = false;
        fadingObjects.clear();
        for (var object : objects) {
            if (!object.isZoneLimited() || !sceneChanged && !object.hasZoneFades()) {
                if (object.hasFadingInstances()) {
                    fadingObjects.add(object);
                }
                continue;
            }
            if (object.applyZoneState(choice, fades, instancePool)) {
                changed = true;
                movedOwners.add(object.getOwner());
                if (metalRayQueries != null) {
                    metalRayQueries.queueGiMovement(object);
                }
            }
            if (object.hasFadingInstances()) {
                fadingObjects.add(object);
            }
        }
        if (changed) {
            gpuVisibility.invalidateHistory("zone state changed");
            zoneStateShownRevision++;
        }
    }

    private void advanceZoneFades(float deltaSeconds) {
        var fades = renderingContext.zoneFades;
        var playing = deltaSeconds > 0f;
        fades.choose(zoneStateChoice(), playing);
        if (playing) {
            fades.advance(deltaSeconds);
        } else {
            fades.settle();
        }
    }

    private void advanceSkyZones(float deltaSeconds) {
        var boxes = renderingContext.scene.getSkyZones();
        var camera = renderingContext.camera;
        if (boxes.isEmpty() || camera.isOrtho()) {
            state.settings.setSkyZone(SkyZones.View.NONE);
            return;
        }
        state.settings.setSkyZone(renderingContext.skyZones.update(
                boxes, zoneStateChoice(), renderingContext.zoneFades, camera.getPosition(), deltaSeconds));
    }

    private ZoneStateChoice zoneStateChoice() {
        var events = state.settings.getGameEventStates();
        return events == null ? ZoneStateChoice.EMPTY : events.zoneStates();
    }

    private void markSceneObjectsChanged() {
        sceneObjectsRevision++;
        detailedTerrainOwnersDirty = true;
        gpuResidualObjectsRevision = Long.MIN_VALUE;
        traversalCacheValid = false;
        if (editorScene != null) {
            editorScene.byActor = null;
        }
    }

    private Map<String, List<BgfxSceneObject>> editorActors() {
        if (editorScene == null) {
            editorScene = new EditorScene();
        }
        if (editorScene.byActor == null) {
            editorScene.byActor = new HashMap<>();
            editorScene.placementsByActor = new HashMap<>();
            for (var object : objects) {
                indexEditorObject(object);
            }
        }
        return editorScene.byActor;
    }

    private void restoreMovingMeshes(EditorScene editor, List<BgfxSceneObject> sceneObjects) {
        renderingContext.swings.restoreEdited();
        var moved = false;
        for (var object : sceneObjects) {
            if (!object.isMoving()) {
                continue;
            }
            for (var actor : object.getSourceActors()) {
                var saved = editor.movements.get(actor);
                if (saved != null && renderingContext.swings.getMovementSource(actor) != saved) {
                    renderingContext.swings.placeMovement(actor, saved);
                    break;
                }
            }
            if (object.refreshMovement(instancePool)) {
                moved = true;
                if (metalRayQueries != null) {
                    metalRayQueries.queueGiMovement(object);
                }
            }
        }
        if (moved) {
            movementRevision++;
            editorTransformRevision++;
            traversalCacheValid = false;
        }
    }

    private void indexEditorObject(BgfxSceneObject object) {
        if (object.isDecorationPool()) {
            return;
        }
        var identities = new HashSet<>(object.getSourceActors());
        if (object.getMotion() != null) {
            for (var actor : identities) {
                var saved = renderingContext.swings.getSavedMovement(actor);
                if (saved != null) {
                    editorScene.movements.putIfAbsent(actor, saved);
                }
            }
        }
        if (object.getSourceParts().isEmpty()) {
            for (var instance = 0; instance < object.getSourceActors().size(); instance++) {
                editorScene
                        .placementsByActor
                        .computeIfAbsent(object.getSourceActor(instance), ignored -> new ArrayList<>())
                        .add(new EditorPlacement(object, instance, -1));
            }
        } else {
            for (var partIndex = 0; partIndex < object.getSourceParts().size(); partIndex++) {
                var part = object.getSourceParts().get(partIndex);
                identities.add(part.actor());
                editorScene
                        .placementsByActor
                        .computeIfAbsent(part.actor(), ignored -> new ArrayList<>())
                        .add(new EditorPlacement(object, -1, partIndex));
            }
        }
        for (var actor : identities) {
            editorScene
                    .byActor
                    .computeIfAbsent(actor, ignored -> new ArrayList<>())
                    .add(object);
        }
    }

    private void reindexEditorObjects(Collection<BgfxSceneObject> removed, Collection<BgfxSceneObject> added) {
        if (editorScene.byActor == null) {
            return;
        }
        for (var object : removed) {
            var identities = new HashSet<>(object.getSourceActors());
            object.getSourceParts().forEach(part -> identities.add(part.actor()));
            for (var actor : identities) {
                removeIndexed(editorScene.byActor, actor, entry -> entry == object);
                removeIndexed(editorScene.placementsByActor, actor, placement -> placement.object() == object);
            }
        }
        added.forEach(this::indexEditorObject);
    }

    private void preserveEditorBaseline() {
        editorActors();
        if (editorScene.originals == null) {
            editorScene.originals = new ArrayList<>(objects);
        }
    }

    private boolean splitEditorActors(Set<String> actors, List<BgfxSceneObject> left) {
        var index = editorActors();
        var split = new IdentityHashMap<BgfxSceneObject, List<BgfxSceneObject>>();
        var candidates = Collections.newSetFromMap(new IdentityHashMap<BgfxSceneObject, Boolean>());
        for (var actor : actors) {
            candidates.addAll(index.getOrDefault(actor, List.of()));
        }
        try {
            for (var object : candidates) {
                if (object.getSourceActors().size() < 2
                        || object.getSourceActors().stream().distinct().limit(2).count() < 2) {
                    continue;
                }
                var selected = new LinkedHashMap<String, List<Integer>>();
                var remaining = new ArrayList<Integer>();
                for (var instance = 0; instance < object.getInstanceCount(); instance++) {
                    var actor = object.getSourceActor(instance);
                    if (actors.contains(actor)) {
                        selected.computeIfAbsent(actor, ignored -> new ArrayList<>())
                                .add(instance);
                    } else {
                        remaining.add(instance);
                    }
                }
                var copies = new ArrayList<BgfxSceneObject>();
                split.put(object, copies);
                for (var instances : selected.values()) {
                    copies.add(object.copyInstances(instancePool, instances));
                }
                if (!remaining.isEmpty()) {
                    copies.add(object.copyInstances(instancePool, remaining));
                }
            }
        } catch (RuntimeException | Error failure) {
            split.values().forEach(copies -> copies.forEach(copy -> closeAfterFailure(copy, failure)));
            throw failure;
        }
        if (split.isEmpty()) {
            return false;
        }
        var next = new ArrayList<BgfxSceneObject>(objects.size() + actors.size());
        var owners = new HashSet<String>();
        var replaced = new ArrayList<BgfxSceneObject>();
        var added = new ArrayList<BgfxSceneObject>();
        for (var object : objects) {
            var copies = split.get(object);
            if (copies == null) {
                next.add(object);
            } else {
                next.addAll(copies);
                replaced.add(object);
                added.addAll(copies);
                editorScene.ownedObjects.addAll(copies);
                owners.add(object.getOwner());
                left.add(object);
                if (editorScene.ownedObjects.remove(object)) {
                    closeEditorObject(object);
                }
            }
        }
        objects.clear();
        objects.addAll(next);
        reindexEditorObjects(replaced, added);
        refreshEditorRayOwners(owners);
        return true;
    }

    private enum EditorReplacement {
        ACTORS,
        TERRAIN,
        BSP
    }

    private void replaceEditorObjects(Map<String, List<Entity>> replacements, EditorReplacement kind) {
        requireOpen();
        var terrain = kind == EditorReplacement.TERRAIN;
        if (replacements == null) {
            throw new IllegalArgumentException("Actor replacements cannot be null");
        }
        if (replacements.isEmpty()) {
            return;
        }
        var index = editorActors();
        var uploads = new ArrayList<EditorUpload>();
        var left = new ArrayList<BgfxSceneObject>();
        var owners = new HashSet<String>();
        var revision = ++editorUploadRevision;
        var giMaterial = BgfxGiMaterial.sharing(terrain ? editorScene.terrainGiPixels : new IdentityHashMap<>());
        try {
            for (var entry : replacements.entrySet()) {
                var actor = entry.getKey();
                if (actor == null || actor.indexOf('.') < 1 || entry.getValue() == null) {
                    throw new IllegalArgumentException(
                            "Replacement requires a package-qualified actor path and entity list");
                }
                var current = index.getOrDefault(actor, List.of());
                if (current.stream().anyMatch(object -> !isReplaceable(object, kind))) {
                    throw new IllegalArgumentException("Actor cannot be replaced in Editing mode: " + actor);
                }
                var owner = current.isEmpty()
                        ? sceneMapOf(actor)
                        : current.getFirst().getOwner();
                owners.add(owner);
                var ordinal = 0;
                for (var entity : entry.getValue()) {
                    var kept = entity != null
                                    && kind == EditorReplacement.ACTORS
                                    && entity.getMovement() == null
                                    && entity.getMotionLink() == null
                                    && !entity.isWireframe()
                            ? renderingContext.swings.continueMotion(actor)
                            : null;
                    var moving = entity != null
                            && (entity.getMovement() != null || entity.getMotionLink() != null || kept != null);
                    if (entity == null
                            || !actor.equals(entity.getSourceActor())
                            || moving && kind != EditorReplacement.ACTORS
                            || !entity.getSourceParts().isEmpty()) {
                        throw new IllegalArgumentException(
                                "Replacement entities must belong to the unmerged static actor " + actor);
                    }
                    var prepared = BgfxSceneGeometry.prepareEditor(entity, actor, revision, ordinal);
                    var geometry = BgfxSceneGeometry.install(geometryPool, textures, entity, prepared);
                    BgfxSceneObject object = null;
                    try {
                        var placement = BgfxSceneObject.prepare(entity, geometry, owner, ordinal++);
                        object = BgfxSceneObject.install(
                                instancePool,
                                actorLightingBuffer,
                                textures,
                                entity,
                                geometry,
                                placement,
                                nativeLightSources);
                        if (kept != null) {
                            object.bindMotion(entity, kept);
                        } else if (moving && entity.getMovement() == null) {
                            object.bindMotion(
                                    entity, renderingContext.swings.createEditedMotion(owner, entity.getMotionLink()));
                        } else if (entity.getMovement() != null) {
                            object.replaceMotion(renderingContext.swings.createEditedMovementMotion(
                                    owner, actor, entity.getMovement()));
                        }
                        if (moving) {
                            object.refreshMovement(instancePool);
                        }
                        if (kind == EditorReplacement.BSP
                                ? object.getGeometry().getKind() != Kind.ENTITY
                                : !isReplaceable(object, kind)) {
                            throw new IllegalArgumentException(
                                    "Only static-mesh actor entities can be replaced: " + actor);
                        }
                        object.publishDeferredInstances(instancePool);
                        object.previewTransforms(editorScene.deltas, instancePool);
                        uploads.add(new EditorUpload(entity, prepared, placement, geometry, object));
                    } catch (RuntimeException | Error failure) {
                        closeAfterFailure(object, failure);
                        closeAfterFailure(geometry, failure);
                        throw failure;
                    }
                }
            }
            preserveEditorBaseline();
            if (kind == EditorReplacement.ACTORS) {
                splitEditorActors(replacements.keySet(), left);
            }
        } catch (RuntimeException | Error failure) {
            for (var upload : uploads) {
                closeAfterFailure(upload.object, failure);
                closeAfterFailure(upload.geometry, failure);
            }
            throw failure;
        }
        var remove = Collections.newSetFromMap(new IdentityHashMap<BgfxSceneObject, Boolean>());
        index = editorActors();
        for (var actor : replacements.keySet()) {
            remove.addAll(index.getOrDefault(actor, List.of()));
        }
        objects.removeIf(remove::contains);
        left.addAll(remove);
        var retired = new ArrayList<BgfxSceneObject>();
        for (var object : remove) {
            reflectionKeysByObject.remove(object);
            if (!editorScene.ownedObjects.remove(object)) {
                continue;
            }
            if (kind != EditorReplacement.BSP) {
                closeEditorObject(object);
                continue;
            }
            closeOrRetire(object, true, true, retired);
            if (retiredObjects.containsKey(object)) {
                continue;
            }
            var geometry = object.getGeometry();
            if (editorScene.ownedGeometries.remove(geometry)) {
                if (metalRayQueries != null) {
                    metalRayQueries.releaseEditorGeometry(geometry);
                }
                geometries.remove(geometry);
                geometry.close();
            }
        }
        if (!retired.isEmpty()) {
            retiredTopologyGeneration = gpuVisibility.getTopologyGeneration();
            if (!gpuTopologyDirty) {
                topologyDirtyFrames = 0;
            }
            gpuTopologyDirty = true;
        }
        for (var upload : uploads) {
            objects.add(upload.object);
            editorScene.ownedObjects.add(upload.object);
            editorScene.ownedGeometries.add(upload.geometry);
            geometries.add(upload.geometry);
            var binding = renderingContext.scene.getVertexLightmaps().bind(upload.object.getOwner(), upload.source);
            upload.object.setNativeVertexLightmap(binding);
            if (binding != null) {
                var leaseOwner = upload.object.getOwner() + "/editor/" + upload.object.getStableId();
                nativeVertexLightmaps.register(leaseOwner, binding.getInstanceId());
                editorScene.bindings.put(upload.object, new EditorBinding(upload.source, leaseOwner));
            }
            if (metalRayQueries != null && kind != EditorReplacement.BSP) {
                metalRayQueries.register(upload.source, upload.prepared, upload.placement, true, null, giMaterial);
            }
        }
        if (kind != EditorReplacement.BSP) {
            refreshEditorRayOwners(owners);
            releaseUnusedEditorGeometries();
        }
        editorScene.movedOwners.addAll(owners);
        reindexEditorObjects(
                remove, uploads.stream().map(upload -> upload.object).toList());
        if (terrain) {
            onEditorTopologyChanged();
            recordEditorTopology(replacements.keySet());
        } else {
            onEditorObjectsChanged(left, replacements.keySet());
        }
    }

    private String sceneMapOf(String actor) {
        var dot = actor.indexOf('.');
        var name = dot < 0 ? actor : actor.substring(0, dot);
        var known = editorScene.packageMaps.get(name);
        if (known != null) {
            return known;
        }
        var index = editorScene.byActor;
        if (index != null) {
            var prefix = name + '.';
            for (var entry : index.entrySet()) {
                if (!entry.getKey().startsWith(prefix)) {
                    continue;
                }
                for (var object : entry.getValue()) {
                    var owner = object.getOwner();
                    if (owner != null && !owner.startsWith("@")) {
                        editorScene.packageMaps.put(name, owner);
                        return owner;
                    }
                }
            }
        }
        return name;
    }

    private boolean isReplaceable(BgfxSceneObject object, EditorReplacement kind) {
        return switch (kind) {
            case ACTORS -> object.canPreviewTransform();
            case TERRAIN -> object.getGeometry().getKind() == Kind.TERRAIN;
            case BSP -> editorScene != null && editorScene.ownedObjects.contains(object);
        };
    }

    private void releaseUnusedEditorGeometries() {
        var retained = Collections.newSetFromMap(new IdentityHashMap<BgfxSceneGeometry, Boolean>());
        objects.forEach(object -> retained.add(object.getGeometry()));
        editorScene.ownedGeometries.removeIf(geometry -> {
            if (retained.contains(geometry)) {
                return false;
            }
            if (metalRayQueries != null) {
                metalRayQueries.releaseEditorGeometry(geometry);
            }
            geometry.close();
            geometries.remove(geometry);
            return true;
        });
    }

    private void closeEditorObject(BgfxSceneObject object) {
        if (gpuVisibility.includes(object)) {
            editorClosedTopologyGeneration = gpuVisibility.getTopologyGeneration();
        }
        if (editorScene != null) {
            var binding = editorScene.bindings.remove(object);
            if (binding != null) {
                renderingContext.scene.getVertexLightmaps().unbind(object.getOwner(), binding.source);
                nativeVertexLightmaps.remove(binding.leaseOwner);
            }
        }
        object.close();
    }

    private void refreshEditorRayOwners(Set<String> owners) {
        if (metalRayQueries == null || !metalRayQueries.isAvailable()) {
            return;
        }
        owners.forEach(metalRayQueries::removeOwner);
        for (var object : objects) {
            if (owners.contains(object.getOwner())) {
                metalRayQueries.queueGiMovement(object);
            }
        }
        metalRayQueries.captureGiMovement();
    }

    private void onEditorTopologyChanged() {
        editorSceneObjectsChanged();
        gpuVisibility.rebuild(gpuVisibilityObjects());
        clearPendingTopology();
    }

    private void onEditorObjectsChanged(Collection<BgfxSceneObject> left, Set<String> actors) {
        if (editorClosedTopologyGeneration == gpuVisibility.getTopologyGeneration()) {
            onEditorTopologyChanged();
        } else {
            editorSceneObjectsChanged();
            gpuVisibility.withdraw(left);
        }
        recordEditorTopology(actors);
    }

    private void editorSceneObjectsChanged() {
        var index = editorScene == null ? null : editorScene.byActor;
        markSceneObjectsChanged();
        if (editorScene != null) {
            editorScene.byActor = index;
        }
        movementRevision++;
        bakedObjectCount = objects.size();
        residencyDirty = true;
        hasWater = objects.stream().anyMatch(BgfxSceneRenderer::ownsWaterPrimitive);
        retainReflectionOwners();
    }

    private void recordEditorTopology(Set<String> actors) {
        editorTopologyActors.put(sceneObjectsRevision, Set.copyOf(actors));
    }

    private void refreshGpuResidualObjects() {
        var topologyGeneration = gpuVisibility.getTopologyGeneration();
        if (gpuResidualObjectsRevision == sceneObjectsRevision && gpuResidualTopologyGeneration == topologyGeneration) {
            return;
        }
        refreshDetailedTerrainOwners();
        gpuResidualObjects.clear();
        gpuResidualPrimitiveOrdinals.clear();
        traversalObjects.clear();
        traversalPrimitiveOrdinals.clear();
        traversalCacheValid = false;
        for (var object : objects) {
            if (isShadowedTerrainProxy(object)) {
                continue;
            }
            var residualOrdinals = gpuVisibility.getResidualPrimitiveOrdinals(object);
            if (residualOrdinals.length > 0) {
                gpuResidualObjects.add(object);
                gpuResidualPrimitiveOrdinals.add(residualOrdinals);
            }
        }
        gpuResidualObjectsRevision = sceneObjectsRevision;
        gpuResidualTopologyGeneration = topologyGeneration;
    }

    private boolean isShadowedTerrainProxy(BgfxSceneObject object) {
        var detailOwner = object.getTerrainProxyDetailOwner();
        return detailOwner != null && detailedTerrainOwners.contains(detailOwner);
    }

    private boolean isBeyondStaticMeshDistance(Primitive primitive, double distanceSquared) {
        if (renderingContext.camera.isOrtho()) {
            return false;
        }
        var material = primitive.entityMaterial();
        if (material == null || (material.getSurfaceType() & SurfaceType.STATIC_MESH.getId()) == 0) {
            return false;
        }
        if (primitive.waterSheetSource() != null
                && material.getProgramFamily() == ProgramFamily.WATER
                && waterRoute() == WaterRenderRoute.PS19) {
            return false;
        }
        var maximum = Math.max(0, state.settings.getStaticMeshesCullingDist());
        return distanceSquared > (double) maximum * maximum;
    }

    private int renderWaterChildren(
            BgfxFramebuffer target,
            BgfxTexture targetColor,
            Extent extent,
            BgfxViewAllocator views,
            float elapsedSeconds,
            FloatBuffer mainView,
            FloatBuffer mainProjection,
            BgfxFrameEnvironment.Snapshot environment,
            int activeWaterRoute,
            boolean fxEnabled) {
        reflectionUpdates = 0;
        reflectionReuses = 0;
        reflectedFxDraws = 0;
        reflectionSavedDraws = 0L;
        reflectionShadedPixelProxy = 0L;
        activeReflectionExtent = ZERO_EXTENT;
        var waterDraws = 0;
        var camera = renderingContext.camera;
        var adaptive = graphics.optimizations().fillReflection();
        var waterGroupIndex = 0;
        Entry nativeSharedReflection = null;
        for (var group : activeWaterGroups) {
            var key = group.key;
            var commands = group.commands;
            if (commands.isEmpty()) {
                continue;
            }
            var planeWorldZ =
                    HfInWaterDepthCoverage.planeWorldZ(key.authoredPlaneZ(), key.authoredPlaneW(), activeWaterRoute);
            retainedInWaterPlaneZ = planeWorldZ;
            stableSortDrawCommands(commands, 0, commands.size(), TRANSLUCENT_ORDER);
            var reflectionExtent = waterReflectionExtent;
            if (reflectionExtent.pixels() > activeReflectionExtent.pixels()) {
                activeReflectionExtent = reflectionExtent;
            }
            var nativeWithinFrameReuse = key.usesNativeSharedTarget() && nativeSharedReflection != null;
            var entry = nativeWithinFrameReuse ? nativeSharedReflection : reflections.acquire(key, reflectionExtent);
            if (key.usesNativeSharedTarget() && nativeSharedReflection == null) {
                nativeSharedReflection = entry;
            }
            var reflectionTexture = entry.getColor();
            var sampledReflectionExtent = entry.getExtent();
            var replayDraws =
                    (long) (opaqueMaterialized ? opaque.size() : opaque.size() + gpuVisibility.getCommandCount())
                            + translucent.size();
            if (adaptive && commands.size() > 1) {
                reflectionSavedDraws += replayDraws * (commands.size() - 1L);
            }
            var childViews = activeWaterRoute == WaterRenderRoute.PS19
                            && targetColor != null
                            && waterBackCopy != null
                            && !waterBackCopy.canShareSurfaceView(extent)
                    ? 2
                    : 1;
            var mandatoryWaterViews = (activeWaterGroups.size() - waterGroupIndex) * childViews;
            var canRefresh = views.getRemaining()
                    >= BgfxViewAllocator.END_OF_FRAME_RESERVE + mandatoryWaterViews + REFLECTION_REFRESH_VIEW_COST;
            if (nativeWithinFrameReuse) {
                reflectionReuses++;
                reflectionSavedDraws += replayDraws;
                if (!entry.hasContent()) {
                    reflectionTexture = textures.getBlack().getTexture();
                    sampledReflectionExtent = ONE_EXTENT;
                }
            } else if (canRefresh) {
                var renderPixels = Math.max(1L, extent.pixels());
                var reflectionPixels = entry.getExtent().pixels();
                reflectionShadedPixelProxy = saturatedAdd(
                        reflectionShadedPixelProxy,
                        Math.round((opaqueShadedPixelProxy + translucentShadedPixelProxy)
                                * (reflectionPixels / (double) renderPixels)));
                var replayWorld =
                        activeWaterRoute == WaterRenderRoute.PS19 || state.settings.getL2WaterEffectType() != 2;
                var replayReducedType2Decorations = activeWaterRoute == WaterRenderRoute.LEGACY_REFLECTION
                        && state.settings.getL2WaterEffectType() == 2;
                var replayGeometry = replayWorld || replayReducedType2Decorations;
                var reflectedView = entry.getReflectedView(camera);
                var rawReflectedProjection = firstSixteenFloats(camera.getReflectionProjectionMatrix());
                var reflectedProjection = BgfxMatrices.toBackendProjection(
                        rawReflectedProjection,
                        device.getCapabilities().homogeneousDepth(),
                        reflectionProjectionBuffer);
                var gpuReflection = false;
                var gpuReflectionSelection = new GpuReflectionSelection(0L, 0);
                var reflectedCamera = entry.getReflectedCamera(camera);
                if (replayWorld
                        && !renderingContext.inspection.isIsolated()
                        && gpuVisibility.isPreparedThisFrame()
                        && !opaqueMaterialized) {
                    gpuReflection = gpuVisibility.prepareReflection(
                            group.reflectionViewName,
                            reflectedView,
                            reflectedProjection,
                            reflectedCamera.getX(),
                            reflectedCamera.getY(),
                            reflectedCamera.getZ(),
                            REFLECTION_WORKING_SET_SQ_RADIUS,
                            entry.getExtent(),
                            views);
                    if (gpuReflection) {
                        gpuReflectionSelection = selectGpuReflectionSubmission(
                                gpuVisibility.getTopology(),
                                reflectedCamera.getX(),
                                reflectedCamera.getY(),
                                reflectedCamera.getZ());
                    }
                }
                if (replayGeometry && !gpuReflection) {
                    ensureCpuOpaqueCommands();
                }
                var directReflectionSelection = replayReducedType2Decorations
                        ? selectType2TerrainDecorationSubmission(
                                key.viewZone(), reflectedCamera.getX(), reflectedCamera.getY(), reflectedCamera.getZ())
                        : replayWorld
                                ? selectDirectReflectionSubmission(
                                        reflectedCamera.getX(), reflectedCamera.getY(), reflectedCamera.getZ())
                                : new GpuReflectionSelection(0L, 0);
                var reflectionClearView =
                        views.allocate(group.reflectionClearViewName, BgfxViewAllocator.Domain.REFLECTION, true);
                setupView(
                        reflectionClearView,
                        "water-reflection-clear",
                        entry.getFramebuffer(),
                        entry.getExtent(),
                        BGFX_VIEW_MODE_SEQUENTIAL);
                bgfx_set_view_clear(
                        reflectionClearView,
                        BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH,
                        0x000000ff,
                        1f,
                        0);
                bgfx_touch(reflectionClearView);
                skyRenderer.renderReflection(
                        entry.getFramebuffer(), entry.getExtent(), views, elapsedSeconds, key.plane(), environment);
                var reflectionView =
                        views.allocate(group.reflectionViewName, BgfxViewAllocator.Domain.REFLECTION, true);
                setupView(
                        reflectionView,
                        "water-reflection",
                        entry.getFramebuffer(),
                        entry.getExtent(),
                        BGFX_VIEW_MODE_SEQUENTIAL);
                bgfx_set_view_transform(reflectionView, reflectedView, reflectedProjection);
                bgfx_touch(reflectionView);
                var clip = HfInWaterDepthCoverage.reflectionClipPlane(planeWorldZ, group.clipPlane);
                var reflectionSubmission =
                        reflectionSubmissionState.forReflection(entry.getExtent(), clip, reflectedCamera, environment);
                var nativeReflectedView = BgfxNativeView.reflected(
                        camera,
                        new NativeWaterReflection(
                                key.authoredPlaneX(), key.authoredPlaneY(), key.authoredPlaneZ(), key.authoredPlaneW()),
                        key.viewZone());
                reflectionSubmission.nativeWaterView = nativeWaterLighting.open(nativeReflectedView);
                reflectionSubmission.nativeMeshView = nativeMeshDraws.open(
                        nativeReflectedView,
                        camera.isOrtho(),
                        renderingContext.inspection.isIsolated(),
                        state.settings.getImpostorDistanceRatio(),
                        camera.isOrtho() && !camera.isInteractiveOrtho());
                var replayUniformBytes = 0L;
                var replayCount = 0;
                if (replayGeometry) {
                    replayUniformBytes = directReflectionSelection.uniformBytes();
                    replayCount = directReflectionSelection.commands();
                    if (gpuReflection) {
                        replayUniformBytes = Math.addExact(replayUniformBytes, gpuReflectionSelection.uniformBytes());
                        replayCount = Math.addExact(replayCount, gpuReflectionSelection.commands());
                    }
                }
                if (replayGeometry
                        && device.getSubmissionBudget()
                                .reserve(BgfxSubmissionBudget.Domain.REFLECTION, replayUniformBytes, replayCount)) {
                    if (gpuReflection) {
                        submitGpuReflection(reflectionView, elapsedSeconds, reflectionSubmission, true);
                    }
                    for (var command : reflectionDirectOpaque) {
                        submit(reflectionView, command, elapsedSeconds, reflectionSubmission, true);
                    }
                    for (var command : reflectionDirectTranslucent) {
                        submit(reflectionView, command, elapsedSeconds, reflectionSubmission, true);
                    }
                    if (replayWorld) {
                        var mainRight = camera.getRightDir();
                        var reflectedFx = fxRenderer.renderReflection(
                                entry.getFramebuffer(),
                                entry.getExtent(),
                                views,
                                elapsedSeconds,
                                fxEnabled,
                                group.reflectionViewName + "-fx",
                                reflectedView,
                                reflectedProjection,
                                rawReflectedProjection,
                                reflectedCamera,
                                key.plane().reflectDirection(mainRight),
                                key.plane().reflectDirection(BgfxFxRenderer.billboardCameraUp(camera, mainRight)),
                                clip);
                        reflectedFxDraws += reflectedFx;
                        waterDraws += reflectedFx;
                    }
                }
                entry.markRendered(reflectedView, reflectedProjection);
                reflectionUpdates++;
            } else if (entry.hasContent()) {
                reflectionReuses++;
                reflectionSavedDraws += replayDraws;
            } else if (views.getRemaining() >= BgfxViewAllocator.END_OF_FRAME_RESERVE + mandatoryWaterViews + 1) {
                var deferredClearView = views.allocate(
                        group.reflectionClearViewName + "-deferred", BgfxViewAllocator.Domain.REFLECTION, true);
                setupView(
                        deferredClearView,
                        "water-reflection-deferred-clear",
                        entry.getFramebuffer(),
                        entry.getExtent(),
                        BGFX_VIEW_MODE_SEQUENTIAL);
                bgfx_set_view_clear(deferredClearView, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH, 0x000000ff, 1f, 0);
                bgfx_touch(deferredClearView);
                entry.markCleared();
            } else {
                reflectionTexture = textures.getBlack().getTexture();
                sampledReflectionExtent = ONE_EXTENT;
            }

            var shareBackView = waterBackCopy == null || waterBackCopy.canShareSurfaceView(extent);
            var childBackReady = activeWaterRoute == WaterRenderRoute.PS19
                    && copyWaterBack(
                            shareBackView ? group.waterViewName : group.backViewName, targetColor, extent, views);
            waterBackCopyExecuted |= childBackReady;
            state.stats.waterBackReady |= childBackReady;
            if (activeWaterRoute == WaterRenderRoute.PS19 && !childBackReady) {
                state.stats.realisticWaterStatus = "Degraded. Background copy unavailable";
            }
            var waterView = childBackReady && waterBackCopyView >= 0
                    ? waterBackCopyView
                    : views.allocate(group.waterViewName, BgfxViewAllocator.Domain.RENDER, true);
            setupView(waterView, "water-surface", target, extent, BGFX_VIEW_MODE_SEQUENTIAL);
            bgfx_set_view_transform(waterView, mainView, mainProjection);
            bgfx_touch(waterView);
            var waterSubmission = waterSubmissionState.forWater(
                    extent,
                    sampledReflectionExtent,
                    reflectionTexture,
                    childBackReady ? waterBackCopy.getTexture() : null,
                    activeWaterRoute == 1 && depthCopyExecuted ? depthCopy : null,
                    entry.getSamplingViewProjection(),
                    planeWorldZ,
                    environment);
            for (var command : commands) {
                if (submit(waterView, command, elapsedSeconds, waterSubmission)) {
                    waterDraws++;
                }
            }
            state.stats.waterReflectionReady = true;
            waterGroupIndex++;
        }
        return waterDraws;
    }

    private boolean ensureDepthCopy(Extent extent) {
        if (depthCopy != null
                && depthCopyExtent.width() >= extent.width()
                && depthCopyExtent.height() >= extent.height()) {
            return true;
        }
        var capacity = new Extent(
                Math.max(depthCopyExtent.width(), extent.width()), Math.max(depthCopyExtent.height(), extent.height()));
        BgfxTexture next = null;
        try {
            next = BgfxTexture.create2D(
                    device.getResources(),
                    "water/depth-copy",
                    capacity.width(),
                    capacity.height(),
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA8,
                    BGFX_TEXTURE_BLIT_DST,
                    null);
        } catch (RuntimeException failure) {
            warnRenderTargetFailure("HF DepthTex sampling copy unavailable", failure);
            return false;
        }
        reportedRenderTargetFailures.remove("HF DepthTex sampling copy unavailable");
        var previous = depthCopy;
        depthCopy = next;
        depthCopyExtent = capacity;
        if (previous != null) {
            previous.close();
        }
        return true;
    }

    private boolean copyPackedDepth(String viewName, BgfxTexture source, Extent extent, BgfxViewAllocator views) {
        if (source == null || !ensureDepthCopy(extent)) {
            return false;
        }
        var depthView = views.allocate(viewName, BgfxViewAllocator.Domain.RENDER, false);
        bgfx_blit(
                depthView,
                depthCopy.getHandle(),
                0,
                0,
                0,
                0,
                source.getHandle(),
                0,
                0,
                0,
                0,
                extent.width(),
                extent.height(),
                1);
        return true;
    }

    private boolean copyWaterBack(String viewName, BgfxTexture source, Extent extent, BgfxViewAllocator views) {
        waterBackCopyView = -1;
        if (source == null) {
            return false;
        }
        try {
            if (waterBackCopy == null) {
                waterBackCopy = new BgfxWaterBackCopy(device, extent);
                return false;
            }
            waterBackCopyView = waterBackCopy.copy(viewName, source, extent, views);
            reportedRenderTargetFailures.remove("Water BackTex unavailable. Using black fallback");
            return true;
        } catch (RuntimeException failure) {
            warnRenderTargetFailure("Water BackTex unavailable. Using black fallback", failure);
            return false;
        }
    }

    private void warnRenderTargetFailure(String message, RuntimeException failure) {
        if (reportedRenderTargetFailures.add(message)) {
            log.warn("{} (retries continue; repeated failures are suppressed)", message, failure);
        }
    }

    private int submitGpuOpaque(int view, float elapsedSeconds, Submission submission) {
        var topology = gpuVisibility.getTopology();
        var materialUpdateStarted = performance.start();
        gpuVisibility.updateMaterialTable(
                elapsedSeconds,
                state.settings.isTexturesEnabled(),
                submission.environment.shadowVariant(),
                renderingContext.camera.isOrtho(),
                surfaceType -> semanticDepthBias(surfaceType));
        performance.addPhase(CpuPhase.MATERIAL_TABLE_UPDATE, performance.getElapsed(materialUpdateStarted));
        if (gpuVisibility.isGroupedThisFrame()) {
            var groups = topology.getGroups();
            if (graphics.optimizations().fillReflection() && groups.size() > 1) {
                groups = orderedGpuGroups(topology);
            }
            groups = plannedGpuGroups(groups);
            var submitted = 0;
            resetGpuBindingCache();
            try {
                for (var group : groups) {
                    var spans = gpuVisibility.getMainSubmissionSpans(group);
                    var ranges = spans == null
                            ? submitGpuGroup(
                                    view,
                                    group,
                                    elapsedSeconds,
                                    submission,
                                    topology,
                                    BgfxSubmissionBudget.Domain.MAIN,
                                    false,
                                    true)
                            : submitGpuGroupSpans(
                                    view,
                                    group,
                                    spans,
                                    elapsedSeconds,
                                    submission,
                                    topology,
                                    BgfxSubmissionBudget.Domain.MAIN,
                                    true);
                    submitted += ranges;
                    if (ranges > 0) {
                        gpuOpaqueActiveGroups++;
                        gpuOpaqueSubmitBatches += spans == null ? 1 : spans.size();
                        gpuOpaqueCommandRanges += ranges;
                    }
                }
            } finally {
                bgfx_discard(BGFX_DISCARD_ALL);
                resetGpuBindingCache();
            }
            return submitted;
        }
        var commands = topology.getCommandsList();
        if (graphics.optimizations().fillReflection()) {
            gpuCommandOrder.clear();
            gpuCommandOrder.addAll(commands);
            stableSortGpuCommands(gpuCommandOrder);
            commands = gpuCommandOrder;
        }
        var submitted = 0;
        for (var command : commands) {
            var ranges = submitGpuCommand(
                    view, command, elapsedSeconds, submission, topology, BgfxSubmissionBudget.Domain.MAIN, false);
            submitted += ranges;
            if (ranges > 0) {
                gpuOpaqueActiveGroups++;
                gpuOpaqueSubmitBatches++;
                gpuOpaqueCommandRanges += ranges;
            }
        }
        return submitted;
    }

    private int submitGpuReflection(int view, float elapsedSeconds, Submission submission, boolean uniformPreReserved) {
        var topology = gpuVisibility.getTopology();
        if (gpuVisibility.isGroupedThisFrame()) {
            var submitted = 0;
            for (var group : reflectionGpuGroups) {
                submitted += submitGpuGroup(
                        view,
                        group,
                        elapsedSeconds,
                        submission,
                        topology,
                        BgfxSubmissionBudget.Domain.REFLECTION,
                        uniformPreReserved,
                        false);
            }
            return submitted;
        }
        var submitted = 0;
        for (var command : reflectionGpuCommands) {
            submitted += submitGpuCommand(
                    view,
                    command,
                    elapsedSeconds,
                    submission,
                    topology,
                    BgfxSubmissionBudget.Domain.REFLECTION,
                    uniformPreReserved);
        }
        return submitted;
    }

    private int submitGpuGroup(
            int view,
            Group group,
            float elapsedSeconds,
            Submission submission,
            Topology topology,
            BgfxSubmissionBudget.Domain domain,
            boolean uniformPreReserved,
            boolean retainBindings) {
        var kind = group.binding().getKind();
        var uniformBytes = gpuSceneUniformBytes(kind);
        if (!uniformPreReserved && !device.getSubmissionBudget().reserve(domain, uniformBytes, 1)) {
            return 0;
        }
        var object = group.representativeObject();
        var primitive = group.representativePrimitive();
        bindGpuGeometry(group.binding(), retainBindings);
        bindGpuDraw(object, primitive, elapsedSeconds, submission, kind, topology, retainBindings);
        var key = opaqueSubmissionKey(
                object,
                group.representativePrimitiveOrdinal(),
                distanceSquared(
                        object.getPrimitiveBounds(group.representativePrimitiveOrdinal()),
                        renderingContext.camera.getPosition().getX(),
                        renderingContext.camera.getPosition().getY(),
                        renderingContext.camera.getPosition().getZ()));
        var discard = retainBindings ? BGFX_DISCARD_STATE : BGFX_DISCARD_ALL;
        bgfx_submit_indirect(
                view,
                gpuProgram(primitive, submission.depthMrt).getHandle(),
                topology.getIndirect().getHandle(),
                group.firstCommand(),
                group.commandCount(),
                key,
                discard);
        var overlays = submitTerrainOverlays(
                object,
                primitive,
                submission,
                coverageDebug.selects(group.family(), submission.mirrored),
                domain,
                () -> bindGpuGeometry(group.binding(), retainBindings),
                program -> bgfx_submit_indirect(
                        view,
                        program,
                        topology.getIndirect().getHandle(),
                        group.firstCommand(),
                        group.commandCount(),
                        key,
                        discard));
        for (var overlay = 0; overlay < overlays; overlay++) {
            performance.addIndirectBatch(
                    group.family(),
                    submission.mirrored,
                    group.commandCount(),
                    group.potentialInstances(),
                    group.potentialTriangles());
        }
        printDebugDraw(
                "gpu-group" + (submission.mirrored ? "-mirror" : ""),
                group.family(),
                object,
                primitive,
                group.potentialInstances(),
                group.potentialTriangles());
        performance.addIndirectBatch(
                group.family(),
                submission.mirrored,
                group.commandCount(),
                group.potentialInstances(),
                group.potentialTriangles());
        return group.commandCount();
    }

    private int submitGpuGroupSpans(
            int view,
            Group group,
            List<SubmissionSpan> spans,
            float elapsedSeconds,
            Submission submission,
            Topology topology,
            BgfxSubmissionBudget.Domain domain,
            boolean retainBindings) {
        if (spans.isEmpty()) {
            return 0;
        }
        var spanCount = spans.size();
        var kind = group.binding().getKind();
        var uniformBytes = Math.multiplyExact((long) gpuSceneUniformBytes(kind), spanCount);
        var selectedRanges = 0;
        for (var spanIndex = 0; spanIndex < spanCount; spanIndex++) {
            selectedRanges = Math.addExact(selectedRanges, spans.get(spanIndex).getCommandCount());
        }
        if (!device.getSubmissionBudget().reserve(domain, uniformBytes, spanCount)) {
            return 0;
        }
        var object = group.representativeObject();
        var primitive = group.representativePrimitive();
        bindGpuGeometry(group.binding(), retainBindings);
        bindGpuDraw(object, primitive, elapsedSeconds, submission, kind, topology, retainBindings);
        var key = opaqueSubmissionKey(
                object,
                group.representativePrimitiveOrdinal(),
                distanceSquared(
                        object.getPrimitiveBounds(group.representativePrimitiveOrdinal()),
                        renderingContext.camera.getPosition().getX(),
                        renderingContext.camera.getPosition().getY(),
                        renderingContext.camera.getPosition().getZ()));
        var programHandle = gpuProgram(primitive, submission.depthMrt).getHandle();
        var indirectHandle = topology.getIndirect().getHandle();
        var overlaid = primitive.terrainMaterial() != null
                && primitive.terrainMaterial().getPassCount() > 1;
        for (var spanIndex = 0; spanIndex < spanCount; spanIndex++) {
            var span = spans.get(spanIndex);
            bgfx_submit_indirect(
                    view,
                    programHandle,
                    indirectHandle,
                    span.getFirstCommand(),
                    span.getCommandCount(),
                    key,
                    spanIndex + 1 == spanCount
                            ? retainBindings ? BGFX_DISCARD_STATE : BGFX_DISCARD_ALL
                            : BGFX_DISCARD_NONE);
            printDebugDraw(
                    "gpu-span" + (submission.mirrored ? "-mirror" : ""),
                    group.family(),
                    object,
                    primitive,
                    topology.getPotentialInstances(span.getFirstCommand(), span.getCommandCount()),
                    topology.getPotentialTriangles(span.getFirstCommand(), span.getCommandCount()));
            performance.addIndirectBatch(
                    group.family(),
                    submission.mirrored,
                    span.getCommandCount(),
                    topology.getPotentialInstances(span.getFirstCommand(), span.getCommandCount()),
                    topology.getPotentialTriangles(span.getFirstCommand(), span.getCommandCount()));
        }
        if (overlaid) {
            submitTerrainOverlays(
                    object,
                    primitive,
                    submission,
                    coverageDebug.selects(group.family(), submission.mirrored),
                    domain,
                    () -> bindGpuGeometry(group.binding(), retainBindings),
                    program -> {
                        for (var spanIndex = 0; spanIndex < spanCount; spanIndex++) {
                            var span = spans.get(spanIndex);
                            bgfx_submit_indirect(
                                    view,
                                    program,
                                    indirectHandle,
                                    span.getFirstCommand(),
                                    span.getCommandCount(),
                                    key,
                                    spanIndex + 1 == spanCount
                                            ? retainBindings ? BGFX_DISCARD_STATE : BGFX_DISCARD_ALL
                                            : BGFX_DISCARD_NONE);
                            performance.addIndirectBatch(
                                    group.family(),
                                    submission.mirrored,
                                    span.getCommandCount(),
                                    topology.getPotentialInstances(span.getFirstCommand(), span.getCommandCount()),
                                    topology.getPotentialTriangles(span.getFirstCommand(), span.getCommandCount()));
                        }
                    });
        }
        return selectedRanges;
    }

    private int submitGpuCommand(
            int view,
            Command command,
            float elapsedSeconds,
            Submission submission,
            Topology topology,
            BgfxSubmissionBudget.Domain domain,
            boolean uniformPreReserved) {
        var uniformBytes = gpuSceneUniformBytes(command.object().getGeometry().getKind());
        if (!uniformPreReserved && !device.getSubmissionBudget().reserve(domain, uniformBytes, 1)) {
            return 0;
        }
        var object = command.object();
        var primitive = command.primitive();
        var kind = object.getGeometry().getKind();
        object.getGeometry().getIndirectBinding().bind();
        bindGpuDraw(object, primitive, elapsedSeconds, submission, kind, topology, false);
        var key = opaqueSubmissionKey(object, command.primitiveOrdinal(), gpuCommandDistanceSquared(command));
        bgfx_submit_indirect(
                view,
                gpuProgram(primitive, submission.depthMrt).getHandle(),
                topology.getIndirect().getHandle(),
                command.commandIndex(),
                1,
                key,
                BGFX_DISCARD_ALL);
        var overlays = submitTerrainOverlays(
                object,
                primitive,
                submission,
                coverageDebug.selects(command.family(), submission.mirrored),
                domain,
                () -> object.getGeometry().getIndirectBinding().bind(),
                program -> bgfx_submit_indirect(
                        view,
                        program,
                        topology.getIndirect().getHandle(),
                        command.commandIndex(),
                        1,
                        key,
                        BGFX_DISCARD_ALL));
        for (var overlay = 0; overlay < overlays; overlay++) {
            performance.addIndirectBatch(
                    command.family(),
                    submission.mirrored,
                    1,
                    command.maxInstances(),
                    (long) primitive.indexCount() / 3L * command.maxInstances());
        }
        printDebugDraw(
                "gpu-cmd" + (submission.mirrored ? "-mirror" : ""),
                command.family(),
                object,
                primitive,
                command.maxInstances(),
                (long) primitive.indexCount() / 3L * command.maxInstances());
        performance.addIndirectBatch(
                command.family(),
                submission.mirrored,
                1,
                command.maxInstances(),
                (long) primitive.indexCount() / 3L * command.maxInstances());
        return 1;
    }

    private void bindGpuDraw(
            BgfxSceneObject object,
            Primitive primitive,
            float elapsedSeconds,
            Submission submission,
            Kind kind,
            Topology topology,
            boolean retainBindings) {
        if (retainBindings && kind.isInstanced() && gpuBindingKind == Kind.TERRAIN) {
            bgfx_discard(BGFX_DISCARD_BINDINGS);
            gpuTextureBindings.reset();
            gpuMaterialTableBound = false;
        }
        if (retainBindings) {
            gpuBindingKind = kind;
        }
        bindFrame(submission, object, primitive, elapsedSeconds, true, retainBindings);
        var debugSelected = coverageDebug.selects(familyOf(primitive), submission.mirrored);
        if (kind.isInstanced()) {
            actorLightingBuffer.bind();
            if (!retainBindings || !gpuMaterialTableBound) {
                topology.bindMaterialTable();
                if (retainBindings) {
                    gpuMaterialTableBound = true;
                }
            }
            if (!retainBindings || !gpuInstanceDataBound) {
                bgfx_set_instance_data_from_dynamic_vertex_buffer(
                        topology.getInstancesOut().getHandle(), 0, topology.getCandidateCount());
                if (retainBindings) {
                    gpuInstanceDataBound = true;
                }
            }
            var material = primitive.entityMaterial();
            if (retainBindings) {
                if (gpuTextureProgramFamily != material.getProgramFamily()) {
                    gpuTextureBindings.reset();
                    gpuTextureProgramFamily = material.getProgramFamily();
                }
                material.bindGpuTextures(
                        uniforms,
                        elapsedSeconds,
                        state.settings.isTexturesEnabled(),
                        object.getLightmap(),
                        gpuTextureBindings);
            } else {
                material.bindGpuTextures(
                        uniforms, elapsedSeconds, state.settings.isTexturesEnabled(), object.getLightmap());
            }
            bgfx_set_state(
                    coverageDebug.getState(
                            BgfxSceneState.of(material, renderingContext.camera.isOrtho(), submission.mirrored),
                            debugSelected),
                    0);
        } else {
            if (retainBindings) {
                gpuTextureBindings.reset();
                gpuMaterialTableBound = false;
                gpuTextureProgramFamily = null;
            }
            uniforms.bindTerrainModel(object.getTerrainModel());
            primitive
                    .terrainMaterial()
                    .bind(
                            uniforms,
                            state.settings.isTexturesEnabled(),
                            submission.environment.terrainAmbient(),
                            submission.environment.terrainSun(),
                            graphics.optimizations().fillReflection(),
                            zoneStateChoice());
            bgfx_set_state(
                    coverageDebug.getState(
                            BgfxSceneState.terrain(renderingContext.camera.isOrtho(), submission.mirrored),
                            debugSelected),
                    0);
        }
    }

    private void bindGpuGeometry(IndirectBinding binding, boolean retainBindings) {
        if (!retainBindings || gpuGeometryBinding != binding) {
            binding.bind();
            if (retainBindings) {
                gpuGeometryBinding = binding;
            }
        }
    }

    private void resetGpuBindingCache() {
        gpuGeometryBinding = null;
        gpuBindingKind = null;
        gpuTextureProgramFamily = null;
        gpuMaterialTableBound = false;
        gpuInstanceDataBound = false;
        gpuFrameUniformCacheValid = false;
        gpuFrameUniformCacheProgram = -1;
        gpuFrameUniformCacheSurfaceType = Long.MIN_VALUE;
        gpuFrameUniformCacheBlendMode = null;
        gpuFrameUniformCacheFamily = null;
        gpuFrameUniformCacheLightmapScale = null;
        gpuFrameUniformCacheLit = false;
        gpuTextureBindings.reset();
    }

    private BgfxProgram gpuProgram(Primitive primitive, boolean depthMrt) {
        var material = primitive.entityMaterial();
        if (material == null) {
            return depthMrt ? programs.terrainDepthMrtProgram : programs.terrainProgram;
        }
        return switch (material.getProgramFamily()) {
            case ORDINARY -> depthMrt ? programs.gpuOrdinaryDepthMrtProgram : programs.gpuOrdinaryProgram;
            case COMPLEX -> depthMrt ? programs.gpuComplexDepthMrtProgram : programs.gpuComplexProgram;
            case CSG -> depthMrt ? programs.gpuCsgDepthMrtProgram : programs.gpuCsgProgram;
            case WATER -> throw new IllegalArgumentException("water cannot enter the GPU opaque material-table path");
        };
    }

    private boolean submit(int view, DrawCommand command, float elapsedSeconds, Submission submission) {
        return submit(view, command, elapsedSeconds, submission, false);
    }

    private boolean submit(
            int view, DrawCommand command, float elapsedSeconds, Submission submission, boolean uniformPreReserved) {
        var actor = othersFactor != 0 ? null : renderingContext.inspection.getIsolatedSourceActor();
        var primitive = command.primitive();
        if (actor == null && (sceneZones.isActive() && primitive.zones() == -1L || isDrawnFaded(command.object))) {
            return submitInView(view, command, elapsedSeconds, submission, uniformPreReserved);
        }
        if (actor == null) {
            return submitRange(
                    view,
                    command,
                    elapsedSeconds,
                    submission,
                    uniformPreReserved,
                    primitive.indexOffset(),
                    primitive.indexCount());
        }
        var firstDraw = true;
        for (var part : command.object.getSourceParts()) {
            if (!part.actor().equals(actor)) {
                continue;
            }
            var first = Math.max(primitive.indexOffset(), part.indexOffset());
            var end =
                    Math.min(primitive.indexOffset() + primitive.indexCount(), part.indexOffset() + part.indexCount());
            if (first >= end) {
                continue;
            }
            if (!submitRange(
                    view, command, elapsedSeconds, submission, uniformPreReserved && firstDraw, first, end - first)) {
                return false;
            }
            firstDraw = false;
        }
        return true;
    }

    private boolean isDrawnFaded(BgfxSceneObject object) {
        return fadeFactor == 0 && object.hasFadingInstances();
    }

    private boolean drawsInstanceInView(BgfxSceneObject object, int instance, boolean byZones) {
        return (!byZones || sceneZones.drawsInstance(object, instance))
                && !(fadeFactor == 0 && object.isFading(instance));
    }

    private int submitFadingOpaque(int view, float elapsedSeconds, Submission submission) {
        fadingOpaque.clear();
        for (var command : opaque) {
            if (command.object.hasFadingInstances()) {
                fadingOpaque.add(command);
            }
        }
        fadingOpaque.sort(TRANSLUCENT_ORDER);
        var submitted = 0;
        for (var command : fadingOpaque) {
            submitted += submitFading(view, command, elapsedSeconds, submission);
        }
        fadingOpaque.clear();
        return submitted;
    }

    private int submitFading(int view, DrawCommand command, float elapsedSeconds, Submission submission) {
        var object = command.object;
        var submitted = 0;
        for (var instance = 0; instance < object.getInstanceCount(); instance++) {
            if (!object.isFading(instance)) {
                continue;
            }
            var level = (int) (object.getFadeOpacity(instance) * 255f + 0.5f);
            if (level <= 0) {
                continue;
            }
            runFirst = instance;
            runCount = 1;
            fadeFactor = level;
            try {
                if (submitRange(
                        view,
                        command,
                        elapsedSeconds,
                        submission,
                        false,
                        command.primitive().indexOffset(),
                        command.primitive().indexCount())) {
                    submitted++;
                }
            } finally {
                runFirst = -1;
                runCount = 0;
                fadeFactor = 0;
                uniforms.setMaterialAlphaScale(1f);
            }
        }
        return submitted;
    }

    private boolean submitInView(
            int view, DrawCommand command, float elapsedSeconds, Submission submission, boolean uniformPreReserved) {
        var object = command.object;
        var primitive = command.primitive();
        var firstDraw = true;
        var byZones = sceneZones.isActive() && primitive.zones() == -1L;
        if (byZones && !object.getSourceParts().isEmpty()) {
            for (var ordinal = 0; ordinal < object.getSourceParts().size(); ordinal++) {
                var part = object.getSourceParts().get(ordinal);
                var first = Math.max(primitive.indexOffset(), part.indexOffset());
                var end = Math.min(
                        primitive.indexOffset() + primitive.indexCount(), part.indexOffset() + part.indexCount());
                if (first < end && sceneZones.drawsPart(object, ordinal)) {
                    if (!submitRange(
                            view,
                            command,
                            elapsedSeconds,
                            submission,
                            uniformPreReserved && firstDraw,
                            first,
                            end - first)) {
                        return false;
                    }
                    firstDraw = false;
                }
            }
            return true;
        }
        var instance = 0;
        while (instance < object.getInstanceCount()) {
            if (!drawsInstanceInView(object, instance, byZones)) {
                instance++;
                continue;
            }
            var end = instance + 1;
            while (end < object.getInstanceCount() && drawsInstanceInView(object, end, byZones)) {
                end++;
            }
            runFirst = instance;
            runCount = end - instance;
            var submitted = submitRange(
                    view,
                    command,
                    elapsedSeconds,
                    submission,
                    uniformPreReserved && firstDraw,
                    primitive.indexOffset(),
                    primitive.indexCount());
            runFirst = -1;
            runCount = 0;
            if (!submitted) {
                return false;
            }
            firstDraw = false;
            instance = end;
        }
        return true;
    }

    private boolean submitRange(
            int view,
            DrawCommand command,
            float elapsedSeconds,
            Submission submission,
            boolean uniformPreReserved,
            int firstIndex,
            int indexCount) {
        var object = command.object;
        var geometry = object.getGeometry();
        var primitive = command.primitive();
        BgfxNativeMeshDraws.Draw nativeDraw = null;
        if (object.getNativeStaticMeshDraw() != null) {
            var meshView = submission.nativeMeshView == null ? mainNativeMeshView : submission.nativeMeshView;
            if (meshView != null) {
                nativeDraw = meshView.prepare(
                        object.getStableId(),
                        object.getNativeStaticMeshDraw(),
                        object.getNativeActorX(),
                        object.getNativeActorY());
                if (!nativeDraw.visible()) {
                    return true;
                }
            }
        }
        if (!uniformPreReserved) {
            var domain =
                    submission.mirrored ? BgfxSubmissionBudget.Domain.REFLECTION : BgfxSubmissionBudget.Domain.MAIN;
            if (!device.getSubmissionBudget().reserve(domain, sceneUniformBytes(command), 1)) {
                return false;
            }
        }
        var renderFamily = familyOf(primitive);
        var debugSelected = coverageDebug.selects(renderFamily, submission.mirrored);
        geometry.bind(firstIndex, indexCount);
        int depthKey = familyOf(primitive).isGpuDrivenEligible()
                ? opaqueSubmissionKey(object, command.primitiveOrdinal, command.distanceSquared)
                : depthKey(command.distanceSquared);
        bindFrame(submission, object, primitive, elapsedSeconds);
        if (geometry.getKind().isInstanced()) {
            var material = primitive.entityMaterial();
            var waterBinding = waterParameters(submission, command);
            var nativeStaticMesh = object.hasNativeStaticMeshAmbient();
            var waterMaterial = material.getProgramFamily() == ProgramFamily.WATER;
            var splitWaterInstances = waterMaterial
                    && nativeStaticMesh
                    && !object.isMoving()
                    && (waterBinding == null || waterBinding.route() == 0);
            var isolatedInstance = runFirst >= 0
                    ? runFirst
                    : othersFactor != 0 ? -1 : renderingContext.inspection.getIsolatedInstance();
            var splitInstances = splitWaterInstances || isolatedInstance >= 0;
            var batches = isolatedInstance >= 0 ? 1 : splitInstances ? object.getInstanceCount() : 1;
            var passes = material.getAuthoredDrawCount(waterBinding, nativeStaticMesh);
            var mergeWaterInstances = EXACT_WATER_INSTANCE_BATCHES
                    && splitWaterInstances
                    && isolatedInstance < 0
                    && passes == 1
                    && object.getNativeVertexLightmap() == null;
            var lightView = submission.nativeWaterView == null ? mainNativeWaterView : submission.nativeWaterView;
            var retainWaterTextures =
                    shouldRetainWaterTextureBindings(device.getStats().frameNumber())
                            && !uniforms.isTextureBatchActive()
                            && batches > 1
                            && splitWaterInstances
                            && material.usesAuthoredPasses(waterBinding, nativeStaticMesh);
            if (retainWaterTextures) {
                uniforms.beginTextureBatch();
            }
            try {
                BgfxNativeWaterLighting.Draw pendingLights = null;
                var pendingInstance = -1;
                for (var batch = 0; batch < batches; ) {
                    var instance = isolatedInstance >= 0 ? isolatedInstance : batch;
                    var lights = pendingInstance == instance
                            ? pendingLights
                            : splitWaterInstances
                                    ? prepareWaterInstanceLights(
                                            object, instance, elapsedSeconds, submission, nativeDraw, lightView)
                                    : null;
                    pendingInstance = -1;
                    var end = batch + 1;
                    if (mergeWaterInstances) {
                        while (end < batches) {
                            var next = prepareWaterInstanceLights(
                                    object, end, elapsedSeconds, submission, nativeDraw, lightView);
                            if (!BgfxWaterInstanceBatching.hasSameLighting(lights, next)) {
                                pendingInstance = end;
                                pendingLights = next;
                                break;
                            }
                            end++;
                        }
                    }
                    var drawInstances =
                            runFirst >= 0 ? runCount : splitInstances ? end - batch : object.getInstanceCount();
                    var directional = lights != null && object.getNativeVertexLightmap() != null
                            ? nativeVertexLightmaps.prepare(object.getNativeVertexLightmap())
                            : null;
                    for (var draw = 0; draw < passes; draw++) {
                        if (draw != 0 || instance != 0) {
                            geometry.bind(firstIndex, indexCount);
                        }
                        if (splitInstances) {
                            object.bindInstances(instance, drawInstances);
                        } else {
                            object.bindInstances();
                        }
                        actorLightingBuffer.bind();
                        var drawState = waterMaterial
                                ? BgfxSceneState.water(
                                        material,
                                        waterBinding != null && waterBinding.route() != 0,
                                        renderingContext.camera.isOrtho(),
                                        submission.mirrored)
                                : BgfxSceneState.of(material, renderingContext.camera.isOrtho(), submission.mirrored);
                        drawState = material.getAuthoredPassState(
                                drawState,
                                waterBinding,
                                nativeStaticMesh,
                                draw,
                                renderingContext.camera.isOrtho(),
                                submission.mirrored);
                        if (fadeFactor != 0) {
                            uniforms.setMaterialAlphaScale(BgfxSceneState.hasBlend(drawState) ? fadeFactor / 255f : 1f);
                        }
                        material.bind(
                                uniforms,
                                elapsedSeconds,
                                state.settings.isTexturesEnabled(),
                                object.getLightmap(submission.environment.shadowVariant()),
                                object.getLightmapLayer(submission.environment.shadowVariant()),
                                semanticDepthBias(material.getSurfaceType()),
                                waterBinding,
                                nativeStaticMesh,
                                draw,
                                lights == null ? null : lights.features(),
                                nativeDraw != null && nativeDraw.useTangent());
                        if (nativeDraw != null) {
                            uniforms.bindNativeMeshDraw(nativeDraw);
                        }
                        if (waterMaterial) {
                            uniforms.bindNativeWaterLights(lights);
                            uniforms.bindNativeVertexLightmap(directional);
                            bindWaterInputs(submission);
                        }
                        setSceneState(drawState, debugSelected);
                        bgfx_submit(
                                view,
                                programFor(material, submission).getHandle(),
                                depthKey,
                                uniforms.isTextureBatchActive() ? BGFX_DISCARD_STATE : BGFX_DISCARD_ALL);
                        recordDirectSceneDraw(
                                renderFamily, object, primitive, indexCount, drawInstances, submission.mirrored);
                    }
                    batch = end;
                }
            } finally {
                if (retainWaterTextures) {
                    uniforms.endTextureBatch();
                }
            }
        } else {
            uniforms.bindTerrainModel(object.getTerrainModel());
            primitive
                    .terrainMaterial()
                    .bind(
                            uniforms,
                            state.settings.isTexturesEnabled(),
                            submission.environment.terrainAmbient(),
                            submission.environment.terrainSun(),
                            graphics.optimizations().fillReflection(),
                            zoneStateChoice());
            setSceneState(
                    BgfxSceneState.terrain(renderingContext.camera.isOrtho(), submission.mirrored), debugSelected);
            bgfx_submit(
                    view,
                    (submission.depthMrt ? programs.terrainDepthMrtProgram : programs.terrainProgram).getHandle(),
                    depthKey,
                    BGFX_DISCARD_ALL);
            recordDirectSceneDraw(renderFamily, object, primitive, indexCount, 1, submission.mirrored);
            var overlays = submitTerrainOverlays(
                    object,
                    primitive,
                    submission,
                    debugSelected,
                    submission.mirrored ? BgfxSubmissionBudget.Domain.REFLECTION : BgfxSubmissionBudget.Domain.MAIN,
                    () -> geometry.bind(firstIndex, indexCount),
                    program -> bgfx_submit(view, program, depthKey, BGFX_DISCARD_ALL));
            for (var overlay = 0; overlay < overlays; overlay++) {
                recordDirectSceneDraw(renderFamily, object, primitive, indexCount, 1, submission.mirrored);
            }
        }
        return true;
    }

    @FunctionalInterface
    private interface OverlaySubmission {
        void submit(short program);
    }

    private int submitTerrainOverlays(
            BgfxSceneObject object,
            Primitive primitive,
            Submission submission,
            boolean debugSelected,
            BgfxSubmissionBudget.Domain domain,
            Runnable bindGeometry,
            OverlaySubmission submit) {
        var material = primitive.terrainMaterial();
        if (material == null || material.getPassCount() < 2) {
            return 0;
        }
        var zoneState = zoneStateChoice();
        var program = submission.depthMrt ? programs.terrainOverlayDepthMrtProgram : programs.terrainOverlayProgram;
        var cover = fadeFactor != 0 ? fadeFactor / 255f : othersFactor != 0 ? (othersFactor & 0xff) / 255f : 1f;
        var overlayState = coverageDebug.getState(
                BgfxSceneState.terrainOverlay(renderingContext.camera.isOrtho(), submission.mirrored), debugSelected);
        var drawn = 0;
        for (var pass = 1; pass < material.getPassCount(); pass++) {
            if (!material.hasOverlayLayers(pass, zoneState)) {
                continue;
            }
            if (!device.getSubmissionBudget().reserve(domain, BgfxSubmissionBudget.TERRAIN_OVERLAY_DRAW_BYTES, 1)) {
                break;
            }
            bindGeometry.run();
            uniforms.bindTerrainModel(object.getTerrainModel());
            material.bindOverlay(
                    uniforms,
                    pass,
                    state.settings.isTexturesEnabled(),
                    submission.environment.terrainAmbient(),
                    submission.environment.terrainSun(),
                    graphics.optimizations().fillReflection(),
                    zoneState,
                    object.getTerrainModel(),
                    cover);
            bgfx_set_state(overlayState, 0);
            submit.submit(program.getHandle());
            drawn++;
        }
        return drawn;
    }

    private void setSceneState(long state, boolean debugSelected) {
        if (fadeFactor != 0) {
            var level = fadeFactor;
            bgfx_set_state(BgfxSceneState.fading(state), level << 24 | level << 16 | level << 8 | level);
        } else if (othersFactor != 0) {
            bgfx_set_state(BgfxSceneState.faint(state), othersFactor);
        } else {
            bgfx_set_state(coverageDebug.getState(state, debugSelected), 0);
        }
    }

    private BgfxNativeWaterLighting.Draw prepareWaterInstanceLights(
            BgfxSceneObject object,
            int instance,
            float elapsedSeconds,
            Submission submission,
            BgfxNativeMeshDraws.Draw nativeDraw,
            BgfxNativeWaterLighting.View lightView) {
        var receiver = object.getNativeLightReceiver(instance);
        var inputs = object.getNativeLightingInputs(instance);
        if (receiver == null || inputs == null || lightView == null) {
            return null;
        }
        return lightView.prepare(
                object.getOwner(),
                receiver,
                inputs,
                elapsedSeconds,
                submission.environment.beastLightmap2(),
                nativeDraw != null && nativeDraw.useTangent(),
                () -> {
                    var binding = object.getNativeVertexLightmap();
                    if (binding != null) {
                        nativeLightSources.updateVertexLightmap(
                                object.getOwner(),
                                renderingContext.scene.getVertexLightmaps(),
                                binding,
                                renderingContext.scene.getNativeRandom());
                    }
                });
    }

    private void recordDirectSceneDraw(
            RenderFamily renderFamily,
            BgfxSceneObject object,
            Primitive primitive,
            int indexCount,
            int instances,
            boolean mirrored) {
        var triangles = (long) indexCount / 3L * instances;
        printDebugDraw("direct" + (mirrored ? "-mirror" : ""), renderFamily, object, primitive, instances, triangles);
        performance.addDraw(renderFamily, mirrored, false, instances, triangles);
    }

    private boolean submitGpuMapProjector(
            int view,
            Command command,
            ProjectedDraw draw,
            float elapsedSeconds,
            Submission submission,
            Topology topology) {
        var object = command.object();
        var primitive = command.primitive();
        var kind = object.getGeometry().getKind();
        if (!device.getSubmissionBudget().reserve(BgfxSubmissionBudget.Domain.MAIN, gpuSceneUniformBytes(kind), 1)) {
            return false;
        }
        object.getGeometry().getIndirectBinding().bind();
        bindGpuDraw(object, primitive, elapsedSeconds, submission, kind, topology, false);
        mapProjectors.bind(draw, renderingContext.camera.getShaderHeightCut());
        mapProjectors.submitIndirect(
                view,
                draw,
                renderingContext.camera.isOrtho(),
                submission.depthMrt,
                topology.getIndirect().getHandle(),
                command.commandIndex(),
                opaqueSubmissionKey(object, command.primitiveOrdinal(), gpuCommandDistanceSquared(command)));
        var triangles = (long) primitive.indexCount() / 3L * command.maxInstances();
        printDebugDraw("projector-gpu", familyOf(primitive), object, primitive, command.maxInstances(), triangles);
        performance.addIndirectBatch(familyOf(primitive), false, 1, command.maxInstances(), triangles);
        return true;
    }

    private boolean isResidualReceiverDrawn(
            BgfxSceneObject object,
            int primitiveOrdinal,
            Primitive primitive,
            BgfxSceneMaterial material,
            Frustum frustum,
            Extent extent) {
        if (isShadowedTerrainProxy(object)) {
            return false;
        }
        if (coverageDebug.isUnculledStableIdReference()) {
            return true;
        }
        var bounds = object.getPrimitiveBounds(primitiveOrdinal);
        if (frustum != null && (!intersects(frustum, object.getWorldBounds()) || !intersects(frustum, bounds))) {
            return false;
        }
        var camera = renderingContext.camera.getPosition();
        var distance = distanceSquared(bounds, camera.getX(), camera.getY(), camera.getZ());
        if (state.settings.isStaticMeshesDistCullingEnabled() && isBeyondStaticMeshDistance(primitive, distance)) {
            return false;
        }
        return material == null
                || !material.isTranslucent()
                || material.getProgramFamily() == ProgramFamily.WATER
                || (material.getSurfaceType() & SurfaceType.STATIC_MESH.getId()) == 0
                || !gpuVisibility.isProjectedSizeInvisible(bounds, extent, object.getTerrainDecorationZone() >= 0);
    }

    private boolean submitMapProjector(int view, ProjectedDraw draw, float elapsedSeconds, Submission submission) {
        var object = draw.object();
        if (!acceptsInspection(object)) {
            return false;
        }
        var actor = renderingContext.inspection.getIsolatedSourceActor();
        var primitive = draw.primitive();
        if (actor == null) {
            return submitMapProjectorRange(
                    view, draw, elapsedSeconds, submission, primitive.indexOffset(), primitive.indexCount());
        }
        var submitted = false;
        for (var part : object.getSourceParts()) {
            if (!part.actor().equals(actor)) {
                continue;
            }
            var first = Math.max(primitive.indexOffset(), part.indexOffset());
            var end =
                    Math.min(primitive.indexOffset() + primitive.indexCount(), part.indexOffset() + part.indexCount());
            if (first < end) {
                submitted |= submitMapProjectorRange(view, draw, elapsedSeconds, submission, first, end - first);
            }
        }
        return submitted;
    }

    private boolean submitMapProjectorRange(
            int view, ProjectedDraw draw, float elapsedSeconds, Submission submission, int firstIndex, int indexCount) {
        var object = draw.object();
        var primitive = draw.primitive();
        var geometry = object.getGeometry();
        var rows = geometry.getTerrainRows();
        if (rows != null && isTranslation(object.getTerrainModel())) {
            var model = object.getTerrainModel();
            var minY = draw.visibleBounds().minY() - model[7];
            var maxY = draw.visibleBounds().maxY() - model[7];
            var rangeFirst = Math.max(firstIndex, rows.firstIndex(minY));
            var rangeEnd = Math.min(firstIndex + indexCount, rows.endIndex(maxY));
            if (rangeEnd <= rangeFirst) {
                return false;
            }
            firstIndex = rangeFirst;
            indexCount = rangeEnd - rangeFirst;
        }
        geometry.bind(firstIndex, indexCount);
        bindFrame(submission, object, primitive, elapsedSeconds);
        if (geometry.getKind().isInstanced()) {
            if (renderingContext.inspection.getIsolatedInstance() >= 0) {
                object.bindInstances(renderingContext.inspection.getIsolatedInstance(), 1);
            } else {
                object.bindInstances();
            }
            actorLightingBuffer.bind();
            var material = primitive.entityMaterial();
            material.bind(
                    uniforms,
                    elapsedSeconds,
                    state.settings.isTexturesEnabled(),
                    object.getLightmap(submission.environment.shadowVariant()),
                    object.getLightmapLayer(submission.environment.shadowVariant()),
                    semanticDepthBias(material.getSurfaceType()));
        } else {
            uniforms.bindTerrainModel(object.getTerrainModel());
            primitive
                    .terrainMaterial()
                    .bind(
                            uniforms,
                            state.settings.isTexturesEnabled(),
                            submission.environment.terrainAmbient(),
                            submission.environment.terrainSun(),
                            graphics.optimizations().fillReflection(),
                            zoneStateChoice());
        }
        mapProjectors.bind(draw, renderingContext.camera.getShaderHeightCut());
        mapProjectors.submit(view, draw, renderingContext.camera.isOrtho(), submission.depthMrt);
        var drawInstances = geometry.getKind().isInstanced() ? object.getInstanceCount() : 1;
        printDebugDraw(
                "projector",
                familyOf(primitive),
                object,
                primitive,
                drawInstances,
                (long) indexCount / 3L * drawInstances);
        performance.addDraw(familyOf(primitive), false, false, drawInstances, (long) indexCount / 3L * drawInstances);
        return true;
    }

    private GpuReflectionSelection selectGpuReflectionSubmission(
            Topology topology, float cameraX, float cameraY, float cameraZ) {
        reflectionGpuGroups.clear();
        reflectionGpuCommands.clear();
        var bytes = 0L;
        var commands = 0;
        if (gpuVisibility.isGroupedThisFrame()) {
            for (var group : topology.getGroups()) {
                var selected = false;
                var end = Math.addExact(group.firstCommand(), group.commandCount());
                for (var index = group.firstCommand(); index < end; index++) {
                    var command = topology.getCommandsList().get(index);
                    if (distanceSquared(command.worldBounds(), cameraX, cameraY, cameraZ)
                            <= REFLECTION_WORKING_SET_SQ_RADIUS) {
                        selected = true;
                        break;
                    }
                }
                if (!selected) {
                    continue;
                }
                reflectionGpuGroups.add(group);
                bytes = Math.addExact(
                        bytes, gpuSceneUniformBytes(group.binding().getKind()));
                commands = Math.addExact(commands, group.commandCount());
            }
            if (graphics.optimizations().fillReflection() && reflectionGpuGroups.size() > 1) {
                stableSortGpuGroups(reflectionGpuGroups);
            }
            return new GpuReflectionSelection(bytes, commands);
        }
        for (var command : topology.getCommandsList()) {
            if (distanceSquared(command.worldBounds(), cameraX, cameraY, cameraZ) > REFLECTION_WORKING_SET_SQ_RADIUS) {
                continue;
            }
            reflectionGpuCommands.add(command);
            bytes = Math.addExact(
                    bytes, gpuSceneUniformBytes(command.object().getGeometry().getKind()));
            commands = Math.addExact(commands, 1);
        }
        if (graphics.optimizations().fillReflection() && reflectionGpuCommands.size() > 1) {
            stableSortGpuCommands(reflectionGpuCommands);
        }
        return new GpuReflectionSelection(bytes, commands);
    }

    private GpuReflectionSelection selectDirectReflectionSubmission(float cameraX, float cameraY, float cameraZ) {
        reflectionDirectOpaque.clear();
        reflectionDirectTranslucent.clear();
        var bytes = 0L;
        var commands = 0;
        for (var command : opaque) {
            if (distanceSquared(command.object.getPrimitiveBounds(command.primitiveOrdinal), cameraX, cameraY, cameraZ)
                    > REFLECTION_WORKING_SET_SQ_RADIUS) {
                continue;
            }
            reflectionDirectOpaque.add(command);
            bytes = Math.addExact(bytes, sceneUniformBytes(command));
            commands = Math.addExact(commands, 1);
        }
        for (var command : translucent) {
            if (distanceSquared(command.object.getPrimitiveBounds(command.primitiveOrdinal), cameraX, cameraY, cameraZ)
                    > REFLECTION_WORKING_SET_SQ_RADIUS) {
                continue;
            }
            reflectionDirectTranslucent.add(command);
            bytes = Math.addExact(bytes, sceneUniformBytes(command));
            commands = Math.addExact(commands, 1);
        }
        reflectionDirectTranslucent.sort(Comparator.comparingDouble((DrawCommand command) -> distanceSquared(
                        command.object.getPrimitiveBounds(command.primitiveOrdinal), cameraX, cameraY, cameraZ))
                .reversed()
                .thenComparingLong(command -> command.primitive().stableId()));
        return new GpuReflectionSelection(bytes, commands);
    }

    private GpuReflectionSelection selectType2TerrainDecorationSubmission(
            int excludedTerrainZone, float cameraX, float cameraY, float cameraZ) {
        reflectionDirectOpaque.clear();
        reflectionDirectTranslucent.clear();
        var bytes = 0L;
        var commands = 0;
        for (var command : opaque) {
            if (!isType2TerrainDecorationVisible(command.object, excludedTerrainZone)
                    || distanceSquared(
                                    command.object.getPrimitiveBounds(command.primitiveOrdinal),
                                    cameraX,
                                    cameraY,
                                    cameraZ)
                            > REFLECTION_WORKING_SET_SQ_RADIUS) {
                continue;
            }
            reflectionDirectOpaque.add(command);
            bytes = Math.addExact(bytes, sceneUniformBytes(command));
            commands = Math.addExact(commands, 1);
        }
        for (var command : translucent) {
            if (!isType2TerrainDecorationVisible(command.object, excludedTerrainZone)
                    || distanceSquared(
                                    command.object.getPrimitiveBounds(command.primitiveOrdinal),
                                    cameraX,
                                    cameraY,
                                    cameraZ)
                            > REFLECTION_WORKING_SET_SQ_RADIUS) {
                continue;
            }
            reflectionDirectTranslucent.add(command);
            bytes = Math.addExact(bytes, sceneUniformBytes(command));
            commands = Math.addExact(commands, 1);
        }
        reflectionDirectTranslucent.sort(Comparator.comparingDouble((DrawCommand command) -> distanceSquared(
                        command.object.getPrimitiveBounds(command.primitiveOrdinal), cameraX, cameraY, cameraZ))
                .reversed()
                .thenComparingLong(command -> command.primitive().stableId()));
        return new GpuReflectionSelection(bytes, commands);
    }

    private void printDebugDraw(
            String path,
            RenderFamily family,
            BgfxSceneObject object,
            Primitive primitive,
            int instances,
            long triangles) {
        if (debugDrawListFramesLeft <= 0) {
            return;
        }
        var material = primitive == null ? null : primitive.entityMaterial();
        System.err.println("[debug_draw] "
                + path
                + " family="
                + family
                + " inst="
                + instances
                + " tris="
                + triangles
                + " "
                + object.getOwner()
                + " / "
                + object.getGeometry().getLabel()
                + " / "
                + (material == null
                        ? "terrain"
                        : material.getMaterialName()
                                + " blend="
                                + material.getBlendMode()
                                + " type="
                                + material.getSurfaceType()
                                + " pf="
                                + material.getProgramFamily()));
    }

    private void dumpDebugDrawList() {
        if (debugDrawListFramesLeft <= 0) {
            return;
        }
        debugDrawListFramesLeft--;
        var sb = new StringBuilder("[debug_drawlist] opaque="
                + opaque.size()
                + " translucent="
                + translucent.size()
                + " water="
                + water.size());
        for (var list : List.of(
                Map.entry("opaque", opaque), Map.entry("translucent", translucent), Map.entry("water", water))) {
            var shown = 0;
            for (var command : list.getValue()) {
                if (shown++ >= 200) {
                    break;
                }
                var material = command.primitive().entityMaterial();
                sb.append('\n')
                        .append(list.getKey())
                        .append(' ')
                        .append(command.object.getOwner())
                        .append(" / ")
                        .append(command.object.getGeometry().getLabel())
                        .append(" / ")
                        .append(material == null ? "terrain" : material.getMaterialName())
                        .append(" blend=")
                        .append(material == null ? "-" : material.getBlendMode())
                        .append(" type=")
                        .append(material == null ? "-" : material.getSurfaceType())
                        .append(" family=")
                        .append(material == null ? "-" : material.getProgramFamily());
            }
        }
        if (gpuVisibility.getTopology() != null) {
            var byCategory = new TreeMap<Long, Integer>();
            var shown = 0;
            for (var gpuCommand : gpuVisibility.getTopology().getCommandsList()) {
                for (var seed : gpuCommand.seeds()) {
                    var material = seed.primitive().entityMaterial();
                    var type = material == null ? SurfaceType.TERRAIN.getId() : material.getSurfaceType();
                    byCategory.merge(type & 0xFL, 1, Integer::sum);
                    if ((type & 0xFL) == 0 && shown++ < 60) {
                        sb.append("\ngpu-uncategorised ")
                                .append(seed.object().getOwner())
                                .append(" / ")
                                .append(seed.object().getGeometry().getLabel())
                                .append(" / ")
                                .append(material == null ? "terrain" : material.getMaterialName())
                                .append(" blend=")
                                .append(material == null ? "-" : material.getBlendMode())
                                .append(" type=")
                                .append(type)
                                .append(" instances=")
                                .append(seed.instanceCount());
                    }
                }
            }
            sb.append("\ngpu seeds by category bits: ").append(byCategory);
        }
        System.err.println(sb);
    }

    private void highlightPlacement(
            BgfxHoverHighlight highlight,
            int view,
            BgfxSceneObject object,
            int instance,
            int part,
            int surface,
            BgfxTexture depth,
            float elapsedSeconds) {
        for (var primitive : object.getGeometry().getPrimitives()) {
            var material = primitive.entityMaterial();
            var program = highlight.getProgram(primitive);
            if (program == null
                    || material != null && material.getBlendMode() == BlendMode.INVISIBLE
                    || surface >= 0 && material != null && primitive.sourceSurfaceOrdinal() != surface) {
                continue;
            }
            var first = primitive.indexOffset();
            var end = first + primitive.indexCount();
            if (part >= 0) {
                var range = object.getSourceParts().get(part);
                first = Math.max(first, range.indexOffset());
                end = Math.min(end, range.indexOffset() + range.indexCount());
            }
            if (first < end) {
                submitHighlight(
                        highlight,
                        program,
                        view,
                        object,
                        primitive,
                        instance,
                        first,
                        end - first,
                        depth,
                        elapsedSeconds);
            }
        }
    }

    private void submitHighlight(
            BgfxHoverHighlight highlight,
            BgfxProgram program,
            int view,
            BgfxSceneObject object,
            Primitive primitive,
            int instance,
            int firstIndex,
            int indexCount,
            BgfxTexture depth,
            float elapsedSeconds) {
        var geometry = object.getGeometry();
        BgfxNativeMeshDraws.Draw nativeDraw = null;
        if (object.getNativeStaticMeshDraw() != null && mainNativeMeshView != null) {
            nativeDraw = mainNativeMeshView.prepare(
                    object.getStableId(),
                    object.getNativeStaticMeshDraw(),
                    object.getNativeActorX(),
                    object.getNativeActorY());
            if (!nativeDraw.visible()) {
                return;
            }
        }
        var bytes = geometry.getKind() == Kind.TERRAIN
                ? BgfxSubmissionBudget.TERRAIN_SCENE_DRAW_BYTES
                : BgfxSubmissionBudget.ENTITY_SCENE_DRAW_BYTES;
        if (!device.getSubmissionBudget().reserve(BgfxSubmissionBudget.Domain.MAIN, bytes, 1)) {
            return;
        }
        geometry.bind(firstIndex, indexCount);
        bindFrame(mainSubmission, object, primitive, elapsedSeconds);
        if (geometry.getKind().isInstanced()) {
            if (instance >= 0) {
                object.bindInstances(instance, 1);
            } else {
                object.bindInstances();
            }
            actorLightingBuffer.bind();
            var material = primitive.entityMaterial();
            material.bind(
                    uniforms,
                    elapsedSeconds,
                    state.settings.isTexturesEnabled(),
                    object.getLightmap(mainSubmission.environment.shadowVariant()),
                    object.getLightmapLayer(mainSubmission.environment.shadowVariant()),
                    semanticDepthBias(material.getSurfaceType()));
            if (nativeDraw != null) {
                uniforms.bindNativeMeshDraw(nativeDraw);
            }
        } else {
            uniforms.bindTerrainModel(object.getTerrainModel());
            primitive
                    .terrainMaterial()
                    .bind(
                            uniforms,
                            state.settings.isTexturesEnabled(),
                            mainSubmission.environment.terrainAmbient(),
                            mainSubmission.environment.terrainSun(),
                            graphics.optimizations().fillReflection(),
                            zoneStateChoice());
        }
        highlight.bind(depth, renderingContext.camera);
        bgfx_set_state(BgfxHoverHighlight.STATE, 0);
        bgfx_submit(view, program.getHandle(), 0, BGFX_DISCARD_ALL);
    }

    private @Nullable BgfxHoverHighlight hoverHighlight() {
        if (hoverHighlight == null && !hoverHighlightFailed) {
            try {
                hoverHighlight = new BgfxHoverHighlight(device, programs);
            } catch (RuntimeException failure) {
                hoverHighlightFailed = true;
                log.warn("The object under the pointer cannot be tinted: {}", failure.toString());
            }
        }
        return hoverHighlight;
    }

    private Set<BgfxSceneObject> isolatedObjects(Set<String> actors) {
        if (isolatedObjectsRevision != sceneObjectsRevision) {
            isolatedObjects.clear();
            refreshDetailedTerrainOwners();
            var index = editorActors();
            for (var actor : actors) {
                isolatedObjects.addAll(index.getOrDefault(actor, List.of()));
                isolatedObjects.addAll(terrainSectors.getOrDefault(actor, List.of()));
            }
            var copies = new ArrayList<BgfxSceneObject>();
            for (var object : isolatedObjects) {
                if (object.getTerrainPair() != null) {
                    copies.addAll(terrainProxies.getOrDefault(object.getTerrainPair(), List.of()));
                }
            }
            isolatedObjects.addAll(copies);
            isolatedObjectsRevision = sceneObjectsRevision;
        }
        return isolatedObjects;
    }

    private boolean acceptsInspection(BgfxSceneObject object) {
        var inspection = renderingContext.inspection;
        var isolatedActors = inspection.getIsolatedActors();
        if (isolatedActors != null) {
            return isolatedObjects(isolatedActors).contains(object);
        }
        var actor = inspection.getIsolatedSourceActor();
        if (actor == null) {
            if (object.getTerrainProxyDetailOwner() != null && inspection.isIsolated()) {
                refreshDetailedTerrainOwners();
                var details = terrainDetails.get(object.getTerrainPair());
                if (details != null) {
                    for (var detail : details) {
                        if (inspection.acceptsObject(detail.getStableId())) {
                            return true;
                        }
                    }
                    return false;
                }
            }
            return inspection.acceptsObject(object.getStableId());
        }
        return object.getOwner().equals(inspection.selected.owner())
                && object.getSourceParts().stream()
                        .anyMatch(part -> part.actor().equals(actor));
    }

    private void refreshPartMaterials() {
        if (partMaterialsRevision == sceneObjectsRevision) {
            return;
        }
        partMaterials.clear();
        var collected = new HashMap<String, TreeMap<Integer, DebugMaterialSlot>>();
        for (var object : objects) {
            for (var part : object.getSourceParts()) {
                var bySlot = collected.computeIfAbsent(object.getOwner() + "/" + part.actor(), _ -> new TreeMap<>());
                for (var primitive : object.getGeometry().getPrimitives()) {
                    if (doesPartIntersect(part, primitive) && primitive.entityMaterial() != null) {
                        bySlot.put(
                                part.materialSlot(), primitive.entityMaterial().getDiagnosticSlot(part.materialSlot()));
                        break;
                    }
                }
            }
        }
        collected.forEach((key, value) -> partMaterials.put(key, List.copyOf(value.values())));
        partMaterialsRevision = sceneObjectsRevision;
    }

    private DebugSceneObject debugPart(BgfxSceneObject object, int primitiveOrdinal, int partIndex) {
        var part = object.getSourceParts().get(partIndex);
        var primitive = object.getGeometry().getPrimitives().get(primitiveOrdinal);
        refreshPartMaterials();
        var materials = partMaterials.getOrDefault(object.getOwner() + "/" + part.actor(), List.of());
        var material = primitive.entityMaterial();
        return new DebugSceneObject(
                shortDebugName(part.asset()),
                part.asset(),
                material == null || material.getMaterialName() == null ? "Unavailable" : material.getMaterialName(),
                object.getOwner(),
                primitive.sourceSurfaceOrdinal(),
                -1,
                part.bounds(),
                Category.STATIC_MESH,
                lightingDescription(object),
                object.getStableId(),
                part.actor(),
                materials,
                object.getAnimationDescription(),
                partIndex,
                object.hasActorAnimation());
    }

    private void addDebugObjects(BgfxSceneObject object, List<DebugSceneObject> result) {
        refreshDetailedTerrainOwners();
        if (isShadowedTerrainProxy(object)) {
            return;
        }
        for (var ordinal = 0; ordinal < object.getGeometry().getPrimitives().size(); ordinal++) {
            var primitive = object.getGeometry().getPrimitives().get(ordinal);
            if (primitive.entityMaterial() != null
                    && primitive.entityMaterial().getBlendMode() == BlendMode.INVISIBLE) {
                continue;
            }
            if (!object.getSourceParts().isEmpty()) {
                for (var partIndex = 0; partIndex < object.getSourceParts().size(); partIndex++) {
                    if (doesPartIntersect(object.getSourceParts().get(partIndex), primitive)) {
                        result.add(debugPart(object, ordinal, partIndex));
                    }
                }
                continue;
            }
            var count = object.getGeometry().getKind().isInstanced()
                    ? object.getInstanceRecords().length / BgfxSceneObject.INSTANCE_FLOATS
                    : 1;
            for (var instance = 0; instance < count; instance++) {
                if (object.isEmptySlot(instance)
                        || object.getGeometry().getKind().isInstanced() && object.isHiddenLook(instance)) {
                    continue;
                }
                result.add(debugPlacement(
                        object, ordinal, object.getGeometry().getKind().isInstanced() ? instance : -1));
            }
        }
    }

    private DebugSceneObject debugPlacement(BgfxSceneObject object, int ordinal, int instance) {
        var primitive = object.getGeometry().getPrimitives().get(ordinal);
        var material = primitive.entityMaterial();
        var category = material == null
                ? Category.TERRAIN
                : (material.getSurfaceType() & SurfaceType.CSG.getId()) != 0 ? Category.CSG : Category.STATIC_MESH;
        var bounds = object.getPrimitiveBounds(ordinal);
        var records = object.getPlacementRecords(instance);
        var offset = instance * BgfxSceneObject.INSTANCE_FLOATS;
        if (instance >= 0 && offset + BgfxSceneObject.INSTANCE_FLOATS <= records.length) {
            bounds = BgfxBillboardTilt.isBillboard(records, offset)
                    ? BgfxSceneObject.transformBillboard(primitive.localBounds(), records, offset)
                    : BgfxSceneObject.transform(primitive.localBounds(), records, offset);
        }
        var name = material == null ? "terrain" : material.getMaterialName();
        return debugSceneObject(
                object,
                primitive,
                category,
                name == null || name.isBlank() ? "<unnamed>" : name,
                bounds,
                instance,
                lightingDescription(object));
    }

    private String lightingDescription(BgfxSceneObject object) {
        var lightmap = object.getLightmap();
        var mainEnvironment = mainSubmissionState.environment;
        var first = mainEnvironment == null ? frameUniforms[35] : mainEnvironment.beastLightmap1();
        var second = mainEnvironment == null ? frameUniforms[39] : mainEnvironment.beastLightmap2();
        return describeLighting(
                object.hasBeastLightmap(),
                lightmap == null ? -1 : lightmap.getBaseLayer(),
                object.getLightmapScale().resolve(first),
                object.getLightmapScale().resolve(second),
                frameUniforms[52],
                frameUniforms[53],
                frameUniforms[54],
                object.hasActorLighting(),
                lightmap != null,
                object.getLightmapVariantCount(),
                object.hasTerrainLighting());
    }

    private void bindFrame(Submission submission, BgfxSceneObject object, Primitive primitive, float elapsedSeconds) {
        bindFrame(submission, object, primitive, elapsedSeconds, false, false);
    }

    private void bindFrame(
            Submission submission,
            BgfxSceneObject object,
            Primitive primitive,
            float elapsedSeconds,
            boolean gpuCompacted,
            boolean reuseIdenticalGpuFrame) {
        var entityMaterial = primitive.entityMaterial();
        var terrain = entityMaterial == null;
        var program = reuseIdenticalGpuFrame
                ? Short.toUnsignedInt(gpuProgram(primitive, submission.depthMrt).getHandle())
                : -1;
        var surfaceType = terrain ? SurfaceType.TERRAIN.getId() : entityMaterial.getSurfaceType();
        var blendMode = terrain ? null : entityMaterial.getBlendMode();
        var renderFamily = familyOf(primitive);
        var semanticCacheEligible = reuseIdenticalGpuFrame
                && !state.settings.isDebugTintEnabled()
                && !coverageDebug.isUnculledStableIdReference();
        if (semanticCacheEligible
                && gpuFrameUniformCacheValid
                && gpuFrameUniformCacheTerrain == terrain
                && gpuFrameUniformCacheProgram == program
                && gpuFrameUniformCacheSurfaceType == surfaceType
                && gpuFrameUniformCacheBlendMode == blendMode
                && gpuFrameUniformCacheFamily == renderFamily
                && object.getLightmapScale().equals(gpuFrameUniformCacheLightmapScale)
                && gpuFrameUniformCacheLit == state.isSceneLit()) {
            return;
        }
        Arrays.fill(frameUniforms, 0f);
        if (submission.clipPlane != null) {
            frameUniforms[0] = submission.clipPlane.getX();
            frameUniforms[1] = submission.clipPlane.getY();
            frameUniforms[2] = submission.clipPlane.getZ();
            frameUniforms[3] = submission.clipPlane.getW();
            frameUniforms[4] = 1f;
        } else if (submission.reflectionViewProjection != null) {
            frameUniforms[4] = -1f;
        }
        frameUniforms[5] = device.getCapabilities().originBottomLeft() ? 1f : 0f;
        frameUniforms[6] = submission.depth == null
                ? 1f
                : (float) submission.extent.width()
                        / submission.depth.getDescriptor().width();
        frameUniforms[7] = submission.depth == null
                ? 1f
                : (float) submission.extent.height()
                        / submission.depth.getDescriptor().height();
        frameUniforms[8] = submission.depth == null
                ? 0f
                : 0.5f / submission.depth.getDescriptor().width();
        frameUniforms[9] = submission.depth == null
                ? 0f
                : 0.5f / submission.depth.getDescriptor().height();
        frameUniforms[10] = submission.reflection == null
                ? 1f
                : (float) submission.reflectionExtent.width()
                        / submission.reflection.getDescriptor().width();
        frameUniforms[11] = submission.reflection == null
                ? 1f
                : (float) submission.reflectionExtent.height()
                        / submission.reflection.getDescriptor().height();
        var frameEnvironment = submission.environment;
        var fog = frameEnvironment.fog();
        frameUniforms[13] = fog == null ? 0f : 1f;
        frameUniforms[14] = frameEnvironment.shadowVariant();
        frameUniforms[15] = coverageDebug.isOverdraw() ? 1f : 0f;
        frameUniforms[12] = submission.reflection == null
                ? 0f
                : 0.5f / submission.reflection.getDescriptor().width();
        frameUniforms[14] = submission.reflection == null
                ? frameEnvironment.shadowVariant()
                : 0.5f / submission.reflection.getDescriptor().height();
        frameUniforms[19] = renderingContext.inspection.isIsolated() && othersFactor == 0
                ? 2f
                : renderingContext.camera.isOrtho() ? 1f : -state.settings.getImpostorDistanceRatio();
        frameUniforms[23] = frameEnvironment.shadowSlot() + (!gpuCompacted && object.isFoliage() ? 16f : 0f);
        frameUniforms[24] = submission.inWaterPlaneZ;
        frameUniforms[27] = gpuCompacted ? -Math.max(0f, elapsedSeconds) : 0f;
        var nativeCsgPlaneSide = entityMaterial != null
                && (entityMaterial.getSurfaceType() & SurfaceType.CSG_NATIVE_PLANE_SIDE.getId()) != 0;
        if (nativeCsgPlaneSide) {
            var viewOrigin =
                    submission.viewOrigin == null ? renderingContext.camera.getPosition() : submission.viewOrigin;
            put3(frameUniforms, 4, viewOrigin);
        }
        put3(frameUniforms, 7, frameEnvironment.sunDirection());
        put3(frameUniforms, 14, frameEnvironment.movableSunDirection());
        put3(frameUniforms, 15, frameEnvironment.actorSun());
        frameUniforms[31] = textureMipBias;
        if (gpuCompacted && primitive.entityMaterial() != null && !nativeCsgPlaneSide) {
            put3(frameUniforms, 4, frameEnvironment.terrainSun());
            put3(frameUniforms, 5, frameEnvironment.terrainAmbient());
        }
        if (primitive.entityMaterial() == null || !gpuCompacted && object.hasTerrainLighting()) {
            put3(frameUniforms, 8, frameEnvironment.terrainSun());
            put3(frameUniforms, 9, frameEnvironment.terrainAmbient());
        } else {
            var material = primitive.entityMaterial();
            put3(frameUniforms, 8, frameEnvironment.entitySun(material.getSurfaceType()));
            put3(frameUniforms, 9, frameEnvironment.entityAmbient(material.getSurfaceType()));
        }
        putBeastLightmapWeights(
                frameUniforms,
                object.getLightmapScale(),
                frameEnvironment.beastLightmap1(),
                frameEnvironment.beastLightmap2());
        put3(frameUniforms, 13, frameEnvironment.beastAmbient());
        frameUniforms[55] = waterLinearFiltering ? 1f : 0f;
        if (fog != null) {
            if (primitive.entityMaterial() == null) {
                put3(frameUniforms, 10, fog.color());
            } else {
                putFogTarget(frameUniforms, 10, primitive.entityMaterial().getBlendMode(), fog.color());
            }
            frameUniforms[44] = fog.start();
            frameUniforms[45] = fog.end();
        }
        if (!state.isSceneLit()) {
            frameUniforms[13] = 0f;
            frameUniforms[BgfxSceneUniforms.FRAME_UNLIT] = 1f;
        }
        frameUniforms[46] = coverageDebug.isEnabled() ? 1f : 0f;
        frameUniforms[47] = coverageDebug.selects(familyOf(primitive), submission.mirrored) ? 1f : 0f;
        ShaderHeightCut.put(
                renderingContext.camera.getShaderHeightCut(),
                surfaceType,
                frameUniforms,
                BgfxSceneUniforms.FRAME_HEIGHT_CUT);
        if (state.settings.isDebugTintEnabled() && primitive.entityMaterial() != null) {
            putDebugSurfaceTint(frameUniforms, object, primitive.entityMaterial());
        }
        if (coverageDebug.isUnculledStableIdReference()
                && !submission.mirrored
                && familyOf(primitive).isGpuDrivenEligible()) {
            putStableIdColor(object.getStableId() ^ Long.rotateLeft(primitive.stableId(), 23), frameUniforms, 48);
            frameUniforms[51] = 1f;
        }
        if (reuseIdenticalGpuFrame
                && gpuFrameUniformCacheValid
                && gpuFrameUniformCacheTerrain == terrain
                && gpuFrameUniformCacheProgram == program
                && Arrays.equals(gpuFrameUniformCache, frameUniforms)) {
            return;
        }
        uniforms.bindFrame(
                frameUniforms,
                submission.back == null
                        ? 0.5f
                        : 0.5f / submission.back.getDescriptor().width(),
                submission.back == null
                        ? 0.5f
                        : 0.5f / submission.back.getDescriptor().height(),
                0.5f / submission.extent.width(),
                0.5f / submission.extent.height());
        uniforms.bindVertexFrame(
                frameUniforms, terrain, renderingContext.camera.getPosition(), frameEnvironment.beastLightmap1());
        if (reuseIdenticalGpuFrame) {
            System.arraycopy(frameUniforms, 0, gpuFrameUniformCache, 0, frameUniforms.length);
            gpuFrameUniformCacheValid = true;
            gpuFrameUniformCacheTerrain = terrain;
            gpuFrameUniformCacheProgram = program;
            gpuFrameUniformCacheSurfaceType = surfaceType;
            gpuFrameUniformCacheBlendMode = blendMode;
            gpuFrameUniformCacheFamily = renderFamily;
            gpuFrameUniformCacheLightmapScale = object.getLightmapScale();
            gpuFrameUniformCacheLit = state.isSceneLit();
        }
    }

    private void bindWaterInputs(Submission submission) {
        var route = waterRoute();
        var selectedNormal = route == WaterRenderRoute.LEGACY_REFLECTION ? waterLegacyBump : waterNormal;
        var normal = selectedNormal == null ? textures.getWhite() : selectedNormal;
        var depth = submission.depth == null ? textures.getWhite().getTexture() : submission.depth;
        var reflection = submission.reflection == null ? textures.getBlack().getTexture() : submission.reflection;
        var back = submission.back == null ? textures.getBlack().getTexture() : submission.back;
        var pointFlags = BGFX_SAMPLER_MIN_POINT | BGFX_SAMPLER_MAG_POINT | BGFX_SAMPLER_MIP_POINT;
        var colorFilterFlags = waterFilterFlags(waterLinearFiltering);
        var clampedColorFlags = BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP | colorFilterFlags;
        var normalFlags = route == WaterRenderRoute.LEGACY_REFLECTION
                ? colorFilterFlags
                : BgfxBitmapSampler.water(normal.getSamplerFlags(), waterNormalFilters, waterLinearFiltering);
        var reflectionFlags = route == WaterRenderRoute.LEGACY_REFLECTION
                ? BGFX_SAMPLER_U_MIRROR | BGFX_SAMPLER_V_MIRROR | colorFilterFlags
                : clampedColorFlags;
        uniforms.bindTexture(
                3, uniforms.getSceneSampler(3).getHandle(), normal.getTexture().getHandle(), normalFlags);
        uniforms.bindTexture(8, uniforms.getSceneSampler(8).getHandle(), depth.getHandle(), pointFlags);
        uniforms.bindTexture(9, uniforms.getSceneSampler(9).getHandle(), reflection.getHandle(), reflectionFlags);
        uniforms.bindTexture(10, uniforms.getSceneSampler(10).getHandle(), back.getHandle(), clampedColorFlags);
    }

    private int waterRoute() {
        var rendering = state;
        return WaterRenderRoute.select(
                rendering.settings.isL2WaterShaderEnabled(),
                rendering.settings.isWaterBackTexEnabled(),
                rendering.settings.getL2WaterEffectType());
    }

    private WaterParameters waterParameters(Submission submission, DrawCommand command) {
        var material = command.primitive().entityMaterial();
        if (material.getProgramFamily() != ProgramFamily.WATER || submission.reflection == null) {
            return null;
        }
        var route = waterRoute();
        var binding = (Binding) null;
        if (route == WaterRenderRoute.PS19) {
            if (waterTexture == null) {
                waterTexture = new BgfxWaterTexture(
                        textures, null, textures.getBitmapSeconds(), waterLinearFiltering, textures.getBitmapClocks());
            }
            var source = command.primitive().waterSheetSource();
            binding = waterTexture.select(
                    command.object.getLightmap(0),
                    command.object.getLightmapVariantCount(),
                    source == null || source.frontZoneSunAffected(),
                    submission.environment.shadowVariant(),
                    textures.getBitmapSeconds());
        }
        return new WaterParameters(
                route,
                waterColorReference,
                waterReflectionReference,
                waterBumpAmplitude,
                route == WaterRenderRoute.LEGACY_REFLECTION ? waterLegacyBump != null : waterNormal != null,
                binding,
                route == WaterRenderRoute.LEGACY_REFLECTION
                        ? legacyWaterClock.sample(renderingContext.pictureClock.getSeconds(), waterBumpAmplitude)
                        : null);
    }

    private float semanticDepthBias(long surfaceType) {
        return semanticDepthBias(
                surfaceType,
                renderingContext.camera.isOrtho(),
                device.getCapabilities().homogeneousDepth());
    }

    private BgfxProgram programFor(BgfxSceneMaterial material, Submission submission) {
        return switch (material.getProgramFamily()) {
            case ORDINARY -> submission.depthMrt ? programs.ordinaryDepthMrtProgram : programs.ordinaryProgram;
            case COMPLEX -> submission.depthMrt ? programs.complexDepthMrtProgram : programs.complexProgram;
            case CSG -> submission.depthMrt ? programs.csgDepthMrtProgram : programs.csgProgram;
            case WATER ->
                state.isCustomLightingEffective() && !submission.mirrored
                        ? programs.waterReceiverMrtProgram
                        : programs.waterProgram;
        };
    }

    private void updateUiCounters(int submitted, long started, boolean gpuOpaque) {
        var rendering = state;
        rendering.stats.draws = submitted;
        rendering.stats.drawsTerrain = 0;
        rendering.stats.drawsCsg = 0;
        rendering.stats.drawsStaticMesh = 0;
        rendering.stats.drawsOther = 0;
        if (gpuOpaque) {
            rendering.stats.drawsTerrain = gpuVisibility.getCommandCategoryCount(0);
            rendering.stats.drawsCsg = gpuVisibility.getCommandCategoryCount(1);
            rendering.stats.drawsStaticMesh = gpuVisibility.getCommandCategoryCount(2);
            rendering.stats.drawsOther = gpuVisibility.getCommandCategoryCount(3);
        } else {
            for (var command : opaque) {
                incrementCategory(rendering, command.primitive());
            }
        }
        if (gpuOpaque) {
            for (var command : opaque) {
                incrementCategory(rendering, command.primitive());
            }
        }
        for (var command : translucent) {
            incrementCategory(rendering, command.primitive());
        }
        for (var command : water) {
            incrementCategory(rendering, command.primitive());
        }
        rendering.stats.sceneSubmitMs = performance.getElapsed(started) / 1_000_000f;
        rendering.stats.sceneGpuMs = device.getStats() == null
                        || !Double.isFinite(device.getStats().gpuFrameMs())
                ? Float.NaN
                : (float) device.getStats().gpuFrameMs();
        var visibility = gpuOpaque ? gpuVisibility.getDiagnostics(objects.size()) : null;
        var gpuVisible = visibility == null
                ? opaque.size()
                : visibility.countersValid() ? visibility.compacted() : visibility.candidates();
        rendering.stats.walkItemsTested =
                visibility == null ? candidates.size() : visibility.candidates() + candidates.size();
        rendering.stats.walkItemsVisible = gpuVisible + opaque.size() + translucent.size() + water.size();
        rendering.stats.walkCellsSkipped = 0;
        rendering.stats.walkCellsTotal = 0;
        rendering.stats.walkZoneRejected = 0;
        rendering.stats.walkLive = true;
    }

    private int renderCoronas(
            BgfxFramebuffer target,
            Extent extent,
            BgfxViewAllocator views,
            FloatBuffer mainView,
            FloatBuffer mainProjection,
            float deltaSeconds,
            boolean fxEnabled,
            boolean depthToRead,
            @Nullable BgfxTexture packedDepth) {
        var coronas = renderingContext.coronas;
        coronaLineTests.poll(device.getStats().frameNumber(), coronas);
        if (coronas.isEmpty()) {
            return 0;
        }
        renderingContext.refreshCoronaEdits();
        coronaClock += Math.max(0f, deltaSeconds);
        var camera = renderingContext.camera;
        var shown = fxEnabled
                && !camera.isOrtho()
                && renderingContext.getViewMode() != ViewMode.WIREFRAME
                && state.settings.getParticleVisibilityDistance() > 0;
        var drawn = coronas.update(coronaClock, shown ? coronaView(camera) : null, sceneZones::drawsPoint);
        coronaLineTests.ask(coronas, depthToRead);
        if (packedDepth != null) {
            coronaLineTests.submit(packedDepth, extent, views, coronaClock, camera, coronas);
        }
        if (drawn.isEmpty()) {
            return 0;
        }
        var view = views.allocate("scene-coronas", BgfxViewAllocator.Domain.RENDER, true);
        setupView(view, "scene-coronas", target, extent, BGFX_VIEW_MODE_SEQUENTIAL);
        bgfx_set_view_transform(view, mainView, mainProjection);
        return coronaRenderer.render(view, drawn, fxRenderer::findParticleTexture);
    }

    private CoronaSystem.View coronaView(Camera camera) {
        var matrix = camera.getViewMatrix().duplicate();
        var terms = new float[16];
        matrix.position(0).limit(16);
        matrix.get(terms, 0, 16);
        var right = new Vec3(terms[0], terms[4], terms[8]).normalize();
        var up = new Vec3(terms[1], terms[5], terms[9]).normalize();
        var look = new Vec3(terms[2], terms[6], terms[10]).normalize();
        var forward = camera.getCursorRayDirection(0f, 0f);
        if (look.getX() * forward.getX() + look.getY() * forward.getY() + look.getZ() * forward.getZ() < 0f) {
            look = look.negate();
        }
        return new CoronaSystem.View(
                new Vec3(camera.getPosition()), look, right, up, camera.getFrustum(), camera.getFov());
    }

    private void setupView(int view, String name, BgfxFramebuffer target, Extent extent, int mode) {
        uniforms.beginView(mode == BGFX_VIEW_MODE_SEQUENTIAL);
        bgfx_set_view_name(view, name);
        bgfx_set_view_rect(view, 0, 0, extent.width(), extent.height());
        bgfx_set_view_frame_buffer(view, target == null ? BGFX_INVALID_HANDLE : target.getHandle());
        bgfx_set_view_mode(view, mode);
    }

    private DrawCommand acquireCommand(BgfxSceneObject object, int primitiveOrdinal, double distanceSquared) {
        DrawCommand command;
        if (commandPoolUsed == commandPool.size()) {
            command = new DrawCommand();
            commandPool.add(command);
        } else {
            command = commandPool.get(commandPoolUsed);
        }
        commandPoolUsed++;
        command.set(object, primitiveOrdinal, distanceSquared);
        return command;
    }

    private void resetCommandPool() {
        for (var index = 0; index < commandPoolUsed; index++) {
            commandPool.get(index).reset();
        }
        commandPoolUsed = 0;
    }

    private void resetWaterGroups() {
        for (var group : activeWaterGroups) {
            group.commands.clear();
        }
        activeWaterGroups.clear();
        reflectedWaterCommands.clear();
    }

    private Key reflectionKey(BgfxSceneObject object, int primitiveOrdinal, Primitive primitive) {
        var keys = reflectionKeysByObject.get(object);
        if (keys == null) {
            keys = new Key[object.getGeometry().getPrimitives().size()];
            reflectionKeysByObject.put(object, keys);
        }
        var key = keys[primitiveOrdinal];
        if (key == null) {
            key = BgfxReflectionCache.keyFor(
                    primitive,
                    graphics.optimizations().fillReflection()
                            ? 0L
                            : object.getStableId() ^ Long.rotateLeft(primitive.stableId(), 17));
            keys[primitiveOrdinal] = key;
        }
        return key;
    }

    private void retainReflectionOwners() {
        var retained = new HashSet<Key>();
        for (var object : objects) {
            var primitives = object.getGeometry().getPrimitives();
            for (var ordinal = 0; ordinal < primitives.size(); ordinal++) {
                var primitive = primitives.get(ordinal);
                var material = primitive.entityMaterial();
                if (material == null || material.getProgramFamily() != ProgramFamily.WATER) {
                    continue;
                }
                var key = reflectionKey(object, ordinal, primitive);
                if (key != null) {
                    retained.add(key);
                }
            }
        }
        reflections.retain(retained);
        for (var iterator = waterGroups.entrySet().iterator(); iterator.hasNext(); ) {
            if (!retained.contains(iterator.next().getKey())) {
                iterator.remove();
            }
        }
    }

    private void sortReorderableOpaqueSegments(List<DrawCommand> commands) {
        var first = 0;
        while (first < commands.size()) {
            while (first < commands.size()
                    && !isReorderableOpaque(commands.get(first).primitive())) {
                first++;
            }
            var end = first;
            while (end < commands.size()
                    && isReorderableOpaque(commands.get(end).primitive())) {
                end++;
            }
            if (end - first > 1) {
                stableSortDrawCommands(commands, first, end, OPAQUE_ORDER);
            }
            first = end;
        }
    }

    private void stableSortDrawCommands(
            List<DrawCommand> commands, int first, int end, Comparator<DrawCommand> comparator) {
        var size = end - first;
        if (size < 2) {
            return;
        }
        if (drawSortScratch.length < size) {
            drawSortScratch = new DrawCommand[grownCapacity(drawSortScratch.length, size)];
        }
        var width = 1;
        while (width < size) {
            for (var index = 0; index < size; index++) {
                drawSortScratch[index] = commands.get(first + index);
            }
            for (var run = 0; run < size; run += width << 1) {
                var middle = Math.min(size, run + width);
                var limit = Math.min(size, middle + width);
                var left = run;
                var right = middle;
                for (var destination = run; destination < limit; destination++) {
                    if (right >= limit
                            || left < middle
                                    && comparator.compare(drawSortScratch[left], drawSortScratch[right]) <= 0) {
                        commands.set(first + destination, drawSortScratch[left++]);
                    } else {
                        commands.set(first + destination, drawSortScratch[right++]);
                    }
                }
            }
            if (width > size / 2) {
                break;
            }
            width <<= 1;
        }
        for (var index = 0; index < size; index++) {
            drawSortScratch[index] = null;
        }
    }

    private void stableSortGpuCommands(List<Command> commands) {
        var size = commands.size();
        if (size < 2) {
            return;
        }
        if (gpuCommandSortScratch.length < size) {
            gpuCommandSortScratch = new Command[grownCapacity(gpuCommandSortScratch.length, size)];
        }
        var width = 1;
        while (width < size) {
            for (var index = 0; index < size; index++) {
                gpuCommandSortScratch[index] = commands.get(index);
            }
            for (var run = 0; run < size; run += width << 1) {
                var middle = Math.min(size, run + width);
                var limit = Math.min(size, middle + width);
                var left = run;
                var right = middle;
                for (var destination = run; destination < limit; destination++) {
                    if (right >= limit
                            || left < middle
                                    && compareGpuCommands(gpuCommandSortScratch[left], gpuCommandSortScratch[right])
                                            <= 0) {
                        commands.set(destination, gpuCommandSortScratch[left++]);
                    } else {
                        commands.set(destination, gpuCommandSortScratch[right++]);
                    }
                }
            }
            if (width > size / 2) {
                break;
            }
            width <<= 1;
        }
        for (var index = 0; index < size; index++) {
            gpuCommandSortScratch[index] = null;
        }
    }

    private void stableSortGpuGroups(List<Group> groups) {
        var size = groups.size();
        if (size < 2) {
            return;
        }
        if (gpuGroupSortScratch.length < size) {
            gpuGroupSortScratch = new Group[grownCapacity(gpuGroupSortScratch.length, size)];
        }
        var width = 1;
        while (width < size) {
            for (var index = 0; index < size; index++) {
                gpuGroupSortScratch[index] = groups.get(index);
            }
            for (var run = 0; run < size; run += width << 1) {
                var middle = Math.min(size, run + width);
                var limit = Math.min(size, middle + width);
                var left = run;
                var right = middle;
                for (var destination = run; destination < limit; destination++) {
                    if (right >= limit
                            || left < middle
                                    && compareGpuGroups(gpuGroupSortScratch[left], gpuGroupSortScratch[right]) <= 0) {
                        groups.set(destination, gpuGroupSortScratch[left++]);
                    } else {
                        groups.set(destination, gpuGroupSortScratch[right++]);
                    }
                }
            }
            if (width > size / 2) {
                break;
            }
            width <<= 1;
        }
        for (var index = 0; index < size; index++) {
            gpuGroupSortScratch[index] = null;
        }
    }

    private int compareGpuCommands(Command left, Command right) {
        var comparison = compareOpaqueBins(left.primitive(), right.primitive());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Double.compare(gpuCommandDistanceSquared(left), gpuCommandDistanceSquared(right));
        if (comparison != 0) {
            return comparison;
        }
        comparison = Long.compare(left.primitive().stableId(), right.primitive().stableId());
        return comparison != 0
                ? comparison
                : Long.compare(left.object().getStableId(), right.object().getStableId());
    }

    private int compareGpuGroups(Group left, Group right) {
        return CACHED_GPU_GROUP_SORT
                ? gpuGroupSortKeys.compare(left.groupIndex(), right.groupIndex())
                : compareGpuGroupsDirect(left, right);
    }

    private int compareGpuGroupsDirect(Group left, Group right) {
        if (left.orderSensitive() || right.orderSensitive()) {
            if (left.orderSensitive() != right.orderSensitive()) {
                return left.orderSensitive() ? 1 : -1;
            }
            return Integer.compare(left.groupIndex(), right.groupIndex());
        }
        var comparison = Integer.compare(
                opaqueProgramBin(left.representativePrimitive()), opaqueProgramBin(right.representativePrimitive()));
        if (comparison == 0) {
            comparison = Integer.compare(gpuGroupBindingBin(left), gpuGroupBindingBin(right));
        }
        if (comparison == 0) {
            comparison = Long.compare(
                    opaqueStateBin(left.representativePrimitive()), opaqueStateBin(right.representativePrimitive()));
        }
        if (comparison != 0) {
            return comparison;
        }
        comparison = Double.compare(
                gpuGroupSortKeys.getDistance(left.groupIndex()), gpuGroupSortKeys.getDistance(right.groupIndex()));
        if (comparison != 0) {
            return comparison;
        }
        comparison = Long.compare(
                left.representativePrimitive().stableId(),
                right.representativePrimitive().stableId());
        return comparison != 0
                ? comparison
                : Long.compare(
                        left.representativeObject().getStableId(),
                        right.representativeObject().getStableId());
    }

    private void prepareGpuGroupDistanceKeys(Topology topology) {
        var groups = topology.getGroups();
        var camera = renderingContext.camera.getPosition();
        var x = camera.getX();
        var y = camera.getY();
        var z = camera.getZ();
        gpuGroupSortKeys.prepare(
                groups.size(),
                CACHED_GPU_GROUP_SORT ? GPU_GROUP_SORT_WORKERS : 1,
                index -> prepareGpuGroupSortKey(groups.get(index), topology, x, y, z));
    }

    private void prepareGpuGroupSortKey(Group group, Topology topology, float x, float y, float z) {
        var index = group.groupIndex();
        gpuGroupSortKeys.setDistance(
                index,
                CACHED_GPU_GROUP_SORT
                        ? topology.getCommandHierarchy().getMinimumGroupDistanceSquared(index, x, y, z)
                        : gpuGroupDistanceSquared(group, topology));
        if (CACHED_GPU_GROUP_SORT) {
            var primitive = group.representativePrimitive();
            gpuGroupSortKeys.set(
                    index,
                    group.orderSensitive(),
                    opaqueProgramBin(primitive),
                    gpuGroupBindingBin(group),
                    opaqueStateBin(primitive),
                    primitive.stableId(),
                    group.representativeObject().getStableId());
        }
    }

    private long gpuGroupSortCell(double coordinate) {
        return (long) Math.floor(coordinate / gpuGroupSortCellSize);
    }

    private List<Group> orderedGpuGroups(Topology topology) {
        var camera = renderingContext.camera.getPosition();
        var cameraCellX = gpuGroupSortCell(camera.getX());
        var cameraCellY = gpuGroupSortCell(camera.getY());
        var cameraCellZ = gpuGroupSortCell(camera.getZ());
        var generation = gpuVisibility.getTopologyGeneration();
        if (gpuGroupOrderTopologyGeneration != generation
                || gpuGroupOrderCameraCellX != cameraCellX
                || gpuGroupOrderCameraCellY != cameraCellY
                || gpuGroupOrderCameraCellZ != cameraCellZ) {
            var keyStarted = DEBUG_GPU_GROUP_SORT ? System.nanoTime() : 0L;
            prepareGpuGroupDistanceKeys(topology);
            var keyFinished = DEBUG_GPU_GROUP_SORT ? System.nanoTime() : 0L;
            gpuGroupOrder.clear();
            var topologyGroups = topology.getGroups();
            for (var groupIndex = 0; groupIndex < topologyGroups.size(); groupIndex++) {
                gpuGroupOrder.add(topologyGroups.get(groupIndex));
            }
            var sortStarted = DEBUG_GPU_GROUP_SORT ? System.nanoTime() : 0L;
            stableSortGpuGroups(gpuGroupOrder);
            if (DEBUG_GPU_GROUP_SORT) {
                var sortFinished = System.nanoTime();
                log.info(
                        "GPU group sort: frame={} cell={}/{}/{} commands={} groups={} keysMs={} copyMs={} sortMs={}",
                        device.getStats().frameNumber(),
                        cameraCellX,
                        cameraCellY,
                        cameraCellZ,
                        topology.getCommandCount(),
                        topologyGroups.size(),
                        (keyFinished - keyStarted) / 1_000_000.0,
                        (sortStarted - keyFinished) / 1_000_000.0,
                        (sortFinished - sortStarted) / 1_000_000.0);
            }
            if (VERIFY_GPU_GROUP_SORT) {
                verifyGpuGroupSort(topology);
            }
            gpuGroupOrderTopologyGeneration = generation;
            gpuGroupOrderCameraCellX = cameraCellX;
            gpuGroupOrderCameraCellY = cameraCellY;
            gpuGroupOrderCameraCellZ = cameraCellZ;
        }
        return gpuGroupOrder;
    }

    private void verifyGpuGroupSort(Topology topology) {
        var expected = new ArrayList<>(topology.getGroups());
        for (var group : expected) {
            var originalDistance = gpuGroupDistanceSquared(group, topology);
            if (Double.doubleToLongBits(originalDistance)
                    != Double.doubleToLongBits(gpuGroupSortKeys.getDistance(group.groupIndex()))) {
                throw new IllegalStateException("cached group distance differs for group " + group.groupIndex());
            }
        }
        expected.sort(this::compareGpuGroupsDirect);
        for (var index = 0; index < expected.size(); index++) {
            if (expected.get(index) != gpuGroupOrder.get(index)) {
                throw new IllegalStateException("cached group order differs at index " + index);
            }
        }
        log.info("GPU group sort verified {} exact distances and ordered group identities", expected.size());
    }

    private List<Group> plannedGpuGroups(List<Group> groups) {
        if (!gpuVisibility.isDelayedSubmissionPlanActive()) {
            return groups;
        }
        var revision = gpuVisibility.getSubmissionPlanRevision();
        if (gpuPlannedGroupOrderRevision != revision || gpuPlannedGroupOrderSource != groups) {
            gpuPlannedGroupOrder.clear();
            for (var group : groups) {
                if (gpuVisibility.isMainSubmissionGroupActive(group)) {
                    gpuPlannedGroupOrder.add(group);
                }
            }
            gpuPlannedGroupOrderRevision = revision;
            gpuPlannedGroupOrderSource = groups;
        }
        return gpuPlannedGroupOrder;
    }

    private double gpuGroupDistanceSquared(Group group, Topology topology) {
        var minimum = Double.POSITIVE_INFINITY;
        var commands = topology.getCommandsList();
        var limit = group.firstCommand() + group.commandCount();
        for (var index = group.firstCommand(); index < limit; index++) {
            minimum = Math.min(minimum, gpuCommandDistanceSquared(commands.get(index)));
        }
        return minimum;
    }

    private double gpuCommandDistanceSquared(Command command) {
        var camera = renderingContext.camera.getPosition();
        return distanceSquared(command.worldBounds(), camera.getX(), camera.getY(), camera.getZ());
    }

    private int opaqueSubmissionKey(BgfxSceneObject object, int primitiveOrdinal, double distanceSquared) {
        if (graphics.optimizations().fillReflection()) {
            return depthKey(distanceSquared);
        }
        var primitive = object.getGeometry().getPrimitives().get(primitiveOrdinal);
        var stable = object.getStableId() ^ Long.rotateLeft(primitive.stableId(), 29);
        return (int) (stable ^ stable >>> 32);
    }

    private void updateShadedPixelProxies(Extent extent) {
        opaqueShadedPixelProxy = 0L;
        terrainShadedPixelProxy = 0L;
        maskedShadedPixelProxy = 0L;
        translucentShadedPixelProxy = 0L;
        for (var command : opaque) {
            addShadedPixelProxy(command, extent);
        }
        if (!opaqueMaterialized) {
            for (var command : opaqueCandidates) {
                addShadedPixelProxy(command, extent);
            }
        }
        for (var command : translucent) {
            translucentShadedPixelProxy = saturatedAdd(translucentShadedPixelProxy, shadedPixelProxy(command, extent));
        }
    }

    private void addShadedPixelProxy(DrawCommand command, Extent extent) {
        var pixels = shadedPixelProxy(command, extent);
        opaqueShadedPixelProxy = saturatedAdd(opaqueShadedPixelProxy, pixels);
        switch (familyOf(command.primitive())) {
            case TERRAIN_OPAQUE -> terrainShadedPixelProxy = saturatedAdd(terrainShadedPixelProxy, pixels);
            case MASKED_OPAQUE -> maskedShadedPixelProxy = saturatedAdd(maskedShadedPixelProxy, pixels);
            default -> {}
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("bgfx scene renderer is closed");
        }
    }

    private void refreshNativeLights(float elapsedSeconds, float deltaSeconds, boolean renderWorld) {
        nativeLightSources.update(
                environment.getNativeLightFrame(), elapsedSeconds, deltaSeconds, renderWorld, true, true);
        actorLightingBuffer.refreshSuns();
        if (!Boolean.getBoolean("cruma.viewer.bgfx.debug_native_lights")) {
            return;
        }
        var state = nativeLightSources.snapshot();
        var ranges = actorLightingBuffer.getCachedSunCount();
        var key = ((state.sources() * 31L + state.cached()) * 31L + ranges) * 31L + state.frameLights();
        if (key == nativeLightDiagnosticKey) {
            return;
        }
        nativeLightDiagnosticKey = key;
        log.info(
                "Native lights: sources={} suns={} cached={} updates={} ranges={} tailUploads={} hour={} sampleHour={}"
                        + " admitted={}",
                state.sources(),
                state.suns(),
                state.cached(),
                state.updates(),
                ranges,
                actorLightingBuffer.getCachedSunUploads(),
                environment.getNativeLightFrame().hour(),
                state.sampleHour(),
                state.frameLights());
    }

    private static int othersRank(DrawCommand command) {
        var material = command.primitive().entityMaterial();
        if (material == null) {
            return 2;
        }
        return (material.getSurfaceType() & SurfaceType.CSG.getId()) != 0 ? 1 : 0;
    }

    private static <T> void removeIndexed(Map<String, List<T>> index, String actor, Predicate<T> match) {
        var entries = index.get(actor);
        if (entries != null) {
            entries.removeIf(match);
            if (entries.isEmpty()) {
                index.remove(actor);
            }
        }
    }

    private static boolean isGpuEligibleOpaque(Primitive primitive) {
        var material = primitive.entityMaterial();
        return material == null
                || !material.isTranslucent()
                        && material.getProgramFamily() != ProgramFamily.WATER
                        && material.getBlendMode() != BlendMode.INVISIBLE
                        && material.hasDepthWrite()
                        && familyOf(primitive).isGpuDrivenEligible();
    }

    private static boolean applyProjectorScissor(
            float[] viewProjection, Bounds3f bounds, Extent extent, int[] scissor) {
        if (viewProjection == null) {
            return true;
        }
        var rect = screenRectangle(viewProjection, bounds, extent, scissor);
        if (rect == ScreenRectangle.EMPTY) {
            return false;
        }
        if (rect == ScreenRectangle.PARTIAL) {
            bgfx_set_scissor(scissor[0], scissor[1], scissor[2], scissor[3]);
        }
        return true;
    }

    private static boolean isTranslation(float[] model) {
        if (model == null || model.length != 16) {
            return false;
        }
        for (var row = 0; row < 3; row++) {
            for (var column = 0; column < 3; column++) {
                var expected = row == column ? 1f : 0f;
                if (Math.abs(model[row * 4 + column] - expected) > 1e-5f) {
                    return false;
                }
            }
        }
        return model[12] == 0f && model[13] == 0f && model[14] == 0f && Math.abs(model[15] - 1f) <= 1e-5f;
    }

    private static long sceneUniformBytes(List<DrawCommand> commands) {
        var bytes = 0L;
        for (var command : commands) {
            bytes = Math.addExact(bytes, sceneUniformBytes(command));
        }
        return bytes;
    }

    private static boolean isType2TerrainDecorationVisible(BgfxSceneObject object, int excludedTerrainZone) {
        return isType2TerrainDecorationVisible(object.getTerrainDecorationZone(), excludedTerrainZone);
    }

    private static int gpuSceneUniformBytes(Kind kind) {
        return kind == Kind.TERRAIN
                ? BgfxSubmissionBudget.TERRAIN_SCENE_DRAW_BYTES
                : BgfxSubmissionBudget.ENTITY_GPU_SCENE_DRAW_BYTES;
    }

    private static int sceneUniformBytes(DrawCommand command) {
        if (command.object.getGeometry().getKind() == Kind.TERRAIN) {
            return BgfxSubmissionBudget.TERRAIN_SCENE_DRAW_BYTES;
        }
        var water = familyOf(command.primitive()) == RenderFamily.WATER;
        var bytes = BgfxSubmissionBudget.ENTITY_SCENE_DRAW_BYTES
                + (water
                        ? (BgfxAuthoredMaterial.VECTORS
                                        + BgfxAuthoredPasses.VECTORS
                                        + 2 * BgfxNativeWaterLighting.VECTORS
                                        + 3)
                                * 16
                        : 0);
        var draws = water
                ? command.primitive()
                        .entityMaterial()
                        .getAuthoredDrawCount(null, command.object.hasNativeStaticMeshAmbient())
                : 1;
        var instances = water && command.object.hasNativeStaticMeshAmbient() ? command.object.getInstanceCount() : 1;
        return Math.multiplyExact(bytes, Math.multiplyExact(draws, instances));
    }

    private static RenderFamily familyOf(Primitive primitive) {
        var material = primitive.entityMaterial();
        if (material == null) {
            return RenderFamily.TERRAIN_OPAQUE;
        }
        if (material.getProgramFamily() == ProgramFamily.WATER) {
            return RenderFamily.WATER;
        }
        if ((material.getSurfaceType() & SurfaceType.CSG.getId()) != 0) {
            return RenderFamily.CSG_OPAQUE;
        }
        if (material.getBlendMode() == BlendMode.MASKED || material.getBlendMode() == BlendMode.MASKED_BLEND) {
            return RenderFamily.MASKED_OPAQUE;
        }
        if (material.isTranslucent()) {
            return RenderFamily.TRANSLUCENT;
        }
        return RenderFamily.STATIC_MESH_OPAQUE;
    }

    private static DebugSceneObject debugSceneObject(
            BgfxSceneObject object,
            Primitive primitive,
            Category category,
            String materialName,
            Bounds3f bounds,
            int instanceOrdinal,
            String lighting) {
        var assetName = object.getSourceAsset();
        var shortSource = category == Category.CSG ? materialName : assetName;
        return new DebugSceneObject(
                shortDebugName(shortSource),
                assetName,
                materialName,
                object.getOwner(),
                primitive.sourceSurfaceOrdinal(),
                instanceOrdinal,
                bounds,
                category,
                lighting,
                object.getStableId(),
                object.getSourceActor(instanceOrdinal),
                object.getDiagnosticMaterials(),
                object.getAnimationDescription(),
                -1,
                object.hasActorAnimation());
    }

    private static boolean doesPartIntersect(SourcePart part, Primitive primitive) {
        return part.indexOffset() < primitive.indexOffset() + primitive.indexCount()
                && primitive.indexOffset() < part.indexOffset() + part.indexCount();
    }

    private static float boundsVolume(Bounds3f bounds) {
        return Math.max(0f, bounds.maxX() - bounds.minX())
                * Math.max(0f, bounds.maxY() - bounds.minY())
                * Math.max(0f, bounds.maxZ() - bounds.minZ());
    }

    private static void incrementCategory(RenderState rendering, Primitive primitive) {
        var material = primitive.entityMaterial();
        var type = material == null ? SurfaceType.TERRAIN.getId() : material.getSurfaceType();
        if ((type & SurfaceType.TERRAIN.getId()) != 0) {
            rendering.stats.drawsTerrain++;
        } else if ((type & SurfaceType.CSG.getId()) != 0) {
            rendering.stats.drawsCsg++;
        } else if ((type & SurfaceType.STATIC_MESH.getId()) != 0) {
            rendering.stats.drawsStaticMesh++;
        } else {
            rendering.stats.drawsOther++;
        }
    }

    private static FloatBuffer firstSixteenFloats(FloatBuffer source) {
        var duplicate = source.duplicate();
        duplicate.position(0);
        duplicate.limit(16);
        return duplicate;
    }

    private static ScreenRectangle screenRectangle(float[] viewProjection, Bounds3f bounds, Extent extent, int[] out) {
        var minimumX = Float.POSITIVE_INFINITY;
        var minimumY = Float.POSITIVE_INFINITY;
        var maximumX = Float.NEGATIVE_INFINITY;
        var maximumY = Float.NEGATIVE_INFINITY;
        for (var corner = 0; corner < 8; corner++) {
            var x = (corner & 1) == 0 ? bounds.minX() : bounds.maxX();
            var y = (corner & 2) == 0 ? bounds.minY() : bounds.maxY();
            var z = (corner & 4) == 0 ? bounds.minZ() : bounds.maxZ();
            var clipW = viewProjection[3] * x + viewProjection[7] * y + viewProjection[11] * z + viewProjection[15];
            if (clipW <= 1e-4f) {
                return ScreenRectangle.FULL;
            }
            var clipX = viewProjection[0] * x + viewProjection[4] * y + viewProjection[8] * z + viewProjection[12];
            var clipY = viewProjection[1] * x + viewProjection[5] * y + viewProjection[9] * z + viewProjection[13];
            var ndcX = clipX / clipW;
            var ndcY = clipY / clipW;
            minimumX = Math.min(minimumX, ndcX);
            minimumY = Math.min(minimumY, ndcY);
            maximumX = Math.max(maximumX, ndcX);
            maximumY = Math.max(maximumY, ndcY);
        }
        if (maximumX < -1f || minimumX > 1f || maximumY < -1f || minimumY > 1f) {
            return ScreenRectangle.EMPTY;
        }
        if (minimumX <= -1f && maximumX >= 1f && minimumY <= -1f && maximumY >= 1f) {
            return ScreenRectangle.FULL;
        }
        var width = extent.width();
        var height = extent.height();
        var left = Math.max(0, (int) Math.floor((minimumX * 0.5f + 0.5f) * width) - 1);
        var right = Math.min(width, (int) Math.ceil((maximumX * 0.5f + 0.5f) * width) + 1);
        var top = Math.max(0, (int) Math.floor((0.5f - maximumY * 0.5f) * height) - 1);
        var bottom = Math.min(height, (int) Math.ceil((0.5f - minimumY * 0.5f) * height) + 1);
        if (right <= left || bottom <= top) {
            return ScreenRectangle.EMPTY;
        }
        out[0] = left;
        out[1] = top;
        out[2] = right - left;
        out[3] = bottom - top;
        return ScreenRectangle.PARTIAL;
    }

    private static boolean intersects(Frustum frustum, Bounds3f bounds) {
        return frustum.intersects(
                bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }

    private static double distanceSquared(Bounds3f bounds, float x, float y, float z) {
        var dx = x < bounds.minX() ? bounds.minX() - x : x > bounds.maxX() ? x - bounds.maxX() : 0f;
        var dy = y < bounds.minY() ? bounds.minY() - y : y > bounds.maxY() ? y - bounds.maxY() : 0f;
        var dz = z < bounds.minZ() ? bounds.minZ() - z : z > bounds.maxZ() ? z - bounds.maxZ() : 0f;
        return (double) dx * dx + (double) dy * dy + (double) dz * dz;
    }

    private static boolean ownsWaterPrimitive(BgfxSceneObject object) {
        for (var primitive : object.getGeometry().getPrimitives()) {
            var material = primitive.entityMaterial();
            if (material != null && material.getProgramFamily() == ProgramFamily.WATER) {
                return true;
            }
        }
        return false;
    }

    private static int gpuGroupBindingBin(Group group) {
        var material = group.representativePrimitive().entityMaterial();
        return material == null
                ? 0
                : material.computeGpuBatchHash(group.representativeObject().getLightmap());
    }

    private static int compareOpaqueBins(Primitive left, Primitive right) {
        var comparison = Integer.compare(opaqueProgramBin(left), opaqueProgramBin(right));
        if (comparison == 0) {
            comparison = Integer.compare(opaqueBindingBin(left), opaqueBindingBin(right));
        }
        return comparison == 0 ? Long.compare(opaqueStateBin(left), opaqueStateBin(right)) : comparison;
    }

    private static int grownCapacity(int current, int required) {
        var result = Math.max(16, current);
        while (result < required) {
            if (result > Integer.MAX_VALUE / 2) {
                return required;
            }
            result <<= 1;
        }
        return result;
    }

    private static boolean isReorderableOpaque(Primitive primitive) {
        var material = primitive.entityMaterial();
        return material == null || material.hasDepthWrite() && material.getBlendMode() != BlendMode.MASKED_BLEND;
    }

    private static int opaqueProgramBin(Primitive primitive) {
        var material = primitive.entityMaterial();
        return material == null ? 0 : material.getProgramFamily().ordinal() + 1;
    }

    private static int opaqueBindingBin(Primitive primitive) {
        var material = primitive.entityMaterial();
        return material == null ? 0 : material.getBindingHash();
    }

    private static long opaqueStateBin(Primitive primitive) {
        var material = primitive.entityMaterial();
        if (material == null) {
            return 0L;
        }
        return BgfxSceneMaterial.gpuBatchSurfaceType(material.getSurfaceType())
                ^ (long) material.getBlendMode().ordinal() << 56
                ^ (material.hasDepthWrite() ? 1L << 55 : 0L);
    }

    private static long shadedPixelProxy(DrawCommand command, Extent extent) {
        if (extent.isZero()) {
            return 0L;
        }
        var bounds = command.object.getPrimitiveBounds(command.primitiveOrdinal);
        var dx = bounds.maxX() - bounds.minX();
        var dy = bounds.maxY() - bounds.minY();
        var dz = bounds.maxZ() - bounds.minZ();
        var radiusSquared = Math.max(1.0, 0.25 * ((double) dx * dx + (double) dy * dy + (double) dz * dz));
        var fraction = Math.min(1.0, 2.0 * radiusSquared / Math.max(radiusSquared, command.distanceSquared));
        return Math.max(1L, Math.min(extent.pixels(), Math.round(extent.pixels() * fraction)));
    }

    private static void putStableIdColor(long stableId, float[] target, int offset) {
        var mixed = stableId + 0x9e3779b97f4a7c15L;
        mixed = (mixed ^ mixed >>> 30) * 0xbf58476d1ce4e5b9L;
        mixed = (mixed ^ mixed >>> 27) * 0x94d049bb133111ebL;
        mixed ^= mixed >>> 31;
        target[offset] = 0.15f + 0.85f * ((mixed >>> 40) & 0xffL) / 255f;
        target[offset + 1] = 0.15f + 0.85f * ((mixed >>> 24) & 0xffL) / 255f;
        target[offset + 2] = 0.15f + 0.85f * ((mixed >>> 8) & 0xffL) / 255f;
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0L) {
            return left;
        }
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static int depthKey(double distanceSquared) {
        if (!Double.isFinite(distanceSquared) || distanceSquared <= 0.0) {
            return 0;
        }
        return (int) Math.min(0xffffffffL, Math.round(Math.sqrt(distanceSquared) * 16.0));
    }

    private static void put3(float[] target, int vector, Vec3 value) {
        var offset = vector * 4;
        target[offset] = value.getX();
        target[offset + 1] = value.getY();
        target[offset + 2] = value.getZ();
    }

    private static void putDebugSurfaceTint(float[] target, BgfxSceneObject object, BgfxSceneMaterial material) {
        var type = material.getSurfaceType();
        var offset = 12 * 4;
        if ((type & SurfaceType.STATIC_MESH.getId()) != 0 && (type & SurfaceType.BOUNDING_BOX.getId()) == 0) {
            target[offset] = 1f;
            target[offset + 1] = 0f;
            target[offset + 2] = 1f;
        } else if (material.getBlendMode() == BlendMode.UNLIT) {
            target[offset] = 1f;
            target[offset + 1] = 0f;
            target[offset + 2] = 0f;
        } else if (object.getLightmap() != null) {
            target[offset] = 0f;
            target[offset + 1] = 1f;
            target[offset + 2] = 0f;
        } else {
            target[offset] = 0f;
            target[offset + 1] = 1f;
            target[offset + 2] = 1f;
        }
        target[offset + 3] = 1f;
    }

    private static void putFogTarget(float[] target, int vector, BlendMode blendMode, Vec3 sceneColor) {
        var offset = vector * 4;
        switch (blendMode) {
            case ADDITIVE, SCREEN, BRIGHTEN, DARKEN -> {
                target[offset] = 0f;
                target[offset + 1] = 0f;
                target[offset + 2] = 0f;
            }
            case MODULATE -> {
                target[offset] = 127f / 255f;
                target[offset + 1] = 127f / 255f;
                target[offset + 2] = 127f / 255f;
            }
            case OPAQUE, MASKED, MASKED_BLEND, UNLIT, TRANSLUCENT, EMISSIVE, INVISIBLE -> {
                target[offset] = sceneColor.getX();
                target[offset + 1] = sceneColor.getY();
                target[offset + 2] = sceneColor.getZ();
            }
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
            var wrapped = new IllegalStateException("failed to close bgfx scene resource", e);
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

    private static void addTextureOwner(Map<TextureRef, Set<String>> target, TextureRef texture, String owner) {
        if (texture != null) {
            target.computeIfAbsent(texture, ignored -> new HashSet<>()).add(owner);
        }
    }

    private static boolean isSavedPlace(
            L2Movement saved,
            Vec3 location,
            URotator rotation,
            Vec3 drawScale3d,
            Vec3 prePivot,
            ActorTransform transform) {
        return saved.location().equals(location)
                && saved.rotation().equals(rotation)
                && saved.scale().equals(drawScale3d)
                && saved.prePivot().equals(prePivot)
                && Float.compare(saved.drawScale(), transform.drawScale()) == 0;
    }

    record MapResidency(long retainedCpuBytes, long geometryGpuBytes, long textureGpuBytes) {
        MapResidency {
            if (retainedCpuBytes < 0L || geometryGpuBytes < 0L || textureGpuBytes < 0L) {
                throw new IllegalArgumentException("map residency bytes cannot be negative");
            }
        }

        long gpuBytes() {
            return Math.addExact(geometryGpuBytes, textureGpuBytes);
        }
    }

    private static final class ObjectList extends ArrayList<BgfxSceneObject> {
        int getChanges() {
            return modCount;
        }
    }

    private static final class ClearanceGroup {
        final List<BgfxSceneObject> objects = new ArrayList<>();
        private float minX = Float.POSITIVE_INFINITY;
        private float minY = Float.POSITIVE_INFINITY;
        private float minZ = Float.POSITIVE_INFINITY;
        private float maxX = Float.NEGATIVE_INFINITY;
        private float maxY = Float.NEGATIVE_INFINITY;
        private float maxZ = Float.NEGATIVE_INFINITY;

        void add(BgfxSceneObject object, Bounds3f bounds) {
            objects.add(object);
            minX = Math.min(minX, bounds.minX());
            minY = Math.min(minY, bounds.minY());
            minZ = Math.min(minZ, bounds.minZ());
            maxX = Math.max(maxX, bounds.maxX());
            maxY = Math.max(maxY, bounds.maxY());
            maxZ = Math.max(maxZ, bounds.maxZ());
        }

        double getDistanceSquared(float x, float y, float z) {
            var dx = x < minX ? minX - x : x > maxX ? x - maxX : 0f;
            var dy = y < minY ? minY - y : y > maxY ? y - maxY : 0f;
            var dz = z < minZ ? minZ - z : z > maxZ ? z - maxZ : 0f;
            return (double) dx * dx + (double) dy * dy + (double) dz * dz;
        }
    }

    private static final class MutableResidency {
        private long retainedCpuBytes;
        private long geometryGpuBytes;
        private long textureGpuBytes;

        private MapResidency snapshot() {
            return new MapResidency(retainedCpuBytes, geometryGpuBytes, textureGpuBytes);
        }
    }

    private record InstanceGroupKey(
            String owner,
            BgfxSceneGeometry geometry,
            TextureRef lightmap,
            boolean vertexLightmapLayers,
            int lightmapVariantCount,
            int lightmapVariantStride,
            List<Integer> lightmapVariantSlots,
            boolean terrainLighting,
            int terrainDecorationZone,
            float decorationFadeMax,
            boolean wireframe,
            boolean acceptsProjectors,
            StaticMeshLod staticMeshLod,
            boolean nativeStaticMeshAmbient,
            NativeLightmapScale lightmapScale,
            boolean zoneLimited) {}

    private record GpuReflectionSelection(long uniformBytes, int commands) {
        private GpuReflectionSelection {
            if (uniformBytes < 0L || commands < 0) {
                throw new IllegalArgumentException("invalid GPU reflection selection");
            }
        }
    }

    private static final class Submission {
        private Extent extent;
        private Vec4 clipPlane;
        private Extent reflectionExtent;
        private BgfxTexture reflection;
        private BgfxTexture back;
        private BgfxTexture depth;
        private float[] reflectionViewProjection;
        private boolean mirrored;
        private boolean depthMrt;
        private float inWaterPlaneZ;
        private Vec3 viewOrigin;
        private BgfxFrameEnvironment.Snapshot environment;
        private BgfxNativeWaterLighting.View nativeWaterView;
        private BgfxNativeMeshDraws.View nativeMeshView;

        private Submission main(
                Extent extent,
                Vec3 viewOrigin,
                BgfxFrameEnvironment.Snapshot environment,
                boolean depthMrt,
                float inWaterPlaneZ) {
            return set(
                    extent,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    depthMrt,
                    inWaterPlaneZ,
                    viewOrigin,
                    environment);
        }

        private Submission forReflection(
                Extent extent, Vec4 clipPlane, Vec3 viewOrigin, BgfxFrameEnvironment.Snapshot environment) {
            return set(extent, clipPlane, null, null, null, null, null, true, false, 0f, viewOrigin, environment);
        }

        private Submission forWater(
                Extent extent,
                Extent reflectionExtent,
                BgfxTexture reflection,
                BgfxTexture back,
                BgfxTexture depth,
                float[] reflectionViewProjection,
                float inWaterPlaneZ,
                BgfxFrameEnvironment.Snapshot environment) {
            return set(
                    extent,
                    null,
                    reflectionExtent,
                    reflection,
                    back,
                    depth,
                    reflectionViewProjection,
                    false,
                    false,
                    inWaterPlaneZ,
                    null,
                    environment);
        }

        private Submission set(
                Extent extent,
                Vec4 clipPlane,
                Extent reflectionExtent,
                BgfxTexture reflection,
                BgfxTexture back,
                BgfxTexture depth,
                float[] reflectionViewProjection,
                boolean mirrored,
                boolean depthMrt,
                float inWaterPlaneZ,
                Vec3 viewOrigin,
                BgfxFrameEnvironment.Snapshot environment) {
            this.nativeWaterView = null;
            this.nativeMeshView = null;
            this.extent = extent;
            this.clipPlane = clipPlane;
            this.reflectionExtent = reflectionExtent;
            this.reflection = reflection;
            this.back = back;
            this.depth = depth;
            this.reflectionViewProjection = reflectionViewProjection;
            this.mirrored = mirrored;
            this.depthMrt = depthMrt;
            this.inWaterPlaneZ = inWaterPlaneZ;
            this.viewOrigin = viewOrigin;
            this.environment = environment;
            return this;
        }
    }

    private static final class WaterGroup {
        private final Key key;
        private final List<DrawCommand> commands = new ArrayList<>();
        private final Vec4 clipPlane = new Vec4();
        private final String reflectionClearViewName;
        private final String reflectionViewName;
        private final String backViewName;
        private final String waterViewName;

        private WaterGroup(Key key) {
            this.key = key;
            var suffix = Integer.toUnsignedString(key.hashCode(), 16);
            reflectionClearViewName = "water-reflection-clear-" + suffix;
            reflectionViewName = "water-reflection-" + suffix;
            backViewName = "water-back-copy-" + suffix;
            waterViewName = "water-surface-" + suffix;
        }
    }

    private static final class DrawCommand {
        private BgfxSceneObject object;
        private int primitiveOrdinal;
        private Primitive primitive;
        private double distanceSquared;

        private void set(BgfxSceneObject object, int primitiveOrdinal, double distanceSquared) {
            this.object = object;
            this.primitiveOrdinal = primitiveOrdinal;
            primitive = object.getGeometry().getPrimitives().get(primitiveOrdinal);
            this.distanceSquared = distanceSquared;
        }

        private void reset() {
            object = null;
            primitiveOrdinal = 0;
            primitive = null;
            distanceSquared = 0.0;
        }

        private Primitive primitive() {
            return DIRECT_DRAW_PRIMITIVE_LOOKUP
                    ? object.getGeometry().getPrimitives().get(primitiveOrdinal)
                    : primitive;
        }
    }
}
