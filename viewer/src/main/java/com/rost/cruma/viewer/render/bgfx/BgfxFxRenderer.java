package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_ACCESS_READ;
import static org.lwjgl.bgfx.BGFX.BGFX_BUFFER_COMPUTE_READ;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_BINDINGS;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_VERTEX_STREAMS;
import static org.lwjgl.bgfx.BGFX.BGFX_INVALID_HANDLE;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIP_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_LEQUAL;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_R;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_SAMPLER;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_VEC4;
import static org.lwjgl.bgfx.BGFX.BGFX_VIEW_MODE_SEQUENTIAL;
import static org.lwjgl.bgfx.BGFX.bgfx_set_compute_dynamic_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_dynamic_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_instance_data_from_dynamic_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_state;
import static org.lwjgl.bgfx.BGFX.bgfx_set_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_set_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_mode;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_name;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_transform;
import static org.lwjgl.bgfx.BGFX.bgfx_submit;
import static org.lwjgl.bgfx.BGFX.bgfx_touch;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.core.geometry.Frustum;
import com.rost.cruma.core.world.Billboard;
import com.rost.cruma.core.world.EmitterDistanceMode;
import com.rost.cruma.core.world.Entity.BlendMode;
import com.rost.cruma.core.world.Entity.DistanceFog;
import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.Entity.SurfaceType;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.viewer.render.BeamRenderer;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.ParticleEmitter;
import com.rost.cruma.viewer.render.ParticleEmitter.Kind;
import com.rost.cruma.viewer.render.ParticleSystem;
import com.rost.cruma.viewer.render.RenderingContext;
import com.rost.cruma.viewer.render.ShaderHeightCut;
import com.rost.cruma.viewer.render.api.RenderDiagnostics.CpuPhase;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.api.RenderFrameSnapshot.RenderFamily;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.Tuning;
import com.rost.cruma.viewer.render.bgfx.BgfxFrameEnvironment.Snapshot;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneGeometry.Prepared;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneMaterial.ProgramFamily;
import com.rost.cruma.viewer.render.bgfx.BgfxScenePrograms.MeshPrograms;
import com.rost.cruma.viewer.render.bgfx.BgfxTextureCache.TextureRef;
import glm_.mat4x4.Mat4;
import glm_.quat.Quat;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;

final class BgfxFxRenderer implements AutoCloseable {
    static final int PARAM_VECTORS = 10;

    private static final Logger log = LogManager.getLogger(BgfxFxRenderer.class);
    private static final boolean FULL_RENDER_REGISTRY =
            Boolean.getBoolean("cruma.viewer.particles.full_render_registry");
    private static final boolean RETAIN_BILLBOARD_BINDINGS =
            !Boolean.getBoolean("cruma.viewer.bgfx.discard_billboard_bindings");

    private static final int BILLBOARD_INSTANCE_FLOATS =
            BgfxSceneVertexLayouts.FX_BILLBOARD_INSTANCE.getStride() / Float.BYTES;
    private static final int RIBBON_FLOATS = BgfxSceneVertexLayouts.FX_RIBBON.getStride() / Float.BYTES;
    private static final int MESH_COLOR_VARIANTS = 4;
    private static final int ACTOR_TRANSFORM_FLOATS = 12;
    private static final int ACTOR_TRANSFORM_BYTES = ACTOR_TRANSFORM_FLOATS * Float.BYTES;
    private static final int ACTOR_TRANSFORM_STAGE = 12;
    private static final int SOFT_DEPTH_SAMPLER =
            BGFX_SAMPLER_MIN_POINT | BGFX_SAMPLER_MAG_POINT | BGFX_SAMPLER_MIP_POINT;
    private static final int PARAM_HEIGHT_CUT = 8 * 4;
    private static final long EFFECT_SURFACE = SurfaceType.STATIC_MESH.getId();

    private BgfxSceneZones sceneZones = new BgfxSceneZones();
    private final BgfxDevice device;
    private final RenderingContext renderingContext;
    private final BgfxGeometryPool geometryPool;
    private final BgfxActorLightingBuffer actorLightingBuffer;
    private final BgfxGpuVisibility gpuVisibility;
    private final float minimumProjectedPixels;
    private final float simulationLodProjectedPixels;
    private final BgfxTextureCache textures;
    private final BgfxSceneUniforms sceneUniforms;
    private final BgfxPerformanceTracker performance;
    private final FloatBuffer projectionBuffer = BufferUtils.createFloatBuffer(16);
    private final MeshPrograms meshPrograms;
    private final MeshPrograms frameBlendPrograms;
    private final IdentityHashMap<BgfxVertexLayoutSpec, BgfxVertexLayout> followingFrameLayouts =
            new IdentityHashMap<>();
    private final BgfxProgram billboardProgram;
    private final BgfxProgram ribbonProgram;
    private final BgfxProgram billboardDepthMrtProgram;
    private final BgfxProgram ribbonDepthMrtProgram;
    private final BgfxShader billboardVertex;
    private final BgfxShader ribbonVertex;
    private final BgfxShader fxFragment;
    private final BgfxShader fxDepthMrtFragment;
    private final BgfxUniform sampler;
    private final BgfxUniform depthSampler;
    private final BgfxUniform params;
    private final BgfxBuffer quadVertices;
    private final BgfxBuffer actorTransforms;
    private final ByteBuffer actorTransformShadow;
    private final int actorTransformCapacity;
    private final IdentityHashMap<ParticleEmitter, Integer> actorTransformIndices = new IdentityHashMap<>();
    private final ArrayDeque<Integer> freeActorTransformIndices = new ArrayDeque<>();
    private final IdentityHashMap<ParticleEmitter, MeshBinding> meshBindings = new IdentityHashMap<>();
    private final IdentityHashMap<ParticleEmitter, SpritePayload> spritePayloads = new IdentityHashMap<>();
    private BgfxParticleRefraction particleRefraction;
    private BgfxProgram refractionRegionProgram;
    private final Map<String, IdentityHashMap<EntityMesh, AnimatedMeshAsset>> animatedMeshAssetsByOwner =
            new HashMap<>();
    private final IdentityHashMap<Texture, ParticleTexture> particleTextures = new IdentityHashMap<>();
    private final Map<String, Texture> particleTexturesByLabel = new HashMap<>();
    private final List<MeshBinding> ownedMeshBindings = new ArrayList<>();
    private final List<MeshFrameBatch> meshFrameBatches = new ArrayList<>();
    private final List<MeshEmitterBatch> meshEmitterBatches = new ArrayList<>();
    private final List<FxScratchBatch> billboardGroups = new ArrayList<>();
    private final List<FxScratchBatch> activeBillboardGroups = new ArrayList<>();
    private final IdentityHashMap<ParticleEmitter, FxScratchBatch> billboardBatchByEmitter = new IdentityHashMap<>();
    private final List<FxScratchBatch> ribbonGroups = new ArrayList<>();
    private final List<FxScratchBatch> activeRibbonGroups = new ArrayList<>();
    private final IdentityHashMap<ParticleEmitter, FxScratchBatch> ribbonBatchByEmitter = new IdentityHashMap<>();
    private final List<PreparedFxEmitter> activeFxEmitters = new ArrayList<>();
    private final Box actorSortScratch = new Box();
    private BgfxSpawnModelLighting.@Nullable Frame meshLighting;
    private final Box meshLightScratch = new Box();
    private final float[] fxParams = new float[PARAM_VECTORS * 4];
    private final float[] sceneFrame = new float[BgfxSceneUniforms.FRAME_VECTORS * 4];
    private final float[] meshPosition = new float[3];
    private final float[] beamSpineScratch = new float[BeamRenderer.SPINE_SCRATCH_FLOATS];
    private float[] beamControlScratch = new float[6];
    private ByteBuffer billboardScratch;
    private FloatBuffer billboardScratchFloats;
    private ByteBuffer ribbonScratch;
    private ByteBuffer meshScratch;
    private BgfxBuffer meshInstances;
    private int meshCapacity;
    private BgfxBuffer billboardInstances;
    private int billboardCapacity;
    private BgfxBuffer ribbonVertices;
    private int meshFrameElements;
    private int billboardFrameElements;
    private int ribbonFrameElements;
    private int ribbonCapacity;
    private long meshOrdinal;
    private long residencyRevision;
    private int actorTransformHighWater;
    private int firstDirtyActorTransform = Integer.MAX_VALUE;
    private int lastDirtyActorTransform = -1;
    private int meshDraws;
    private int billboardDraws;
    private int beamDraws;
    private DistanceFog frameFog;
    private boolean localFogActive;
    private float textureMipBias;
    private boolean closed;
    private boolean refractionShortReported;
    private boolean refractionMergeReported;
    private boolean refractionPassPerEffect;
    private RefractionCounts refractionCounts = RefractionCounts.NONE;
    private final BgfxRefractionLayers refractionLayers = new BgfxRefractionLayers();
    private final BgfxParticleRefraction.Pass refractionPass = new BgfxParticleRefraction.Pass();
    private final List<PreparedFxEmitter> refractionPassMembers = new ArrayList<>();
    private final float[] refractionViewProjection = new float[16];
    private final int[] refractionScissor = new int[4];
    private final Box refractionBounds = new Box();

    private final ByteBuffer pickModel = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
    private final float[] pickPosition = new float[3];

    BgfxFxRenderer(
            BgfxDevice device,
            RenderingContext renderingContext,
            Tuning tuning,
            BgfxGeometryPool geometryPool,
            BgfxActorLightingBuffer actorLightingBuffer,
            BgfxGpuVisibility gpuVisibility,
            BgfxTextureCache textures,
            BgfxSceneUniforms sceneUniforms,
            BgfxPerformanceTracker performance,
            MeshPrograms meshPrograms,
            MeshPrograms frameBlendPrograms) {
        this.device = device;
        this.renderingContext = renderingContext;
        this.geometryPool = geometryPool;
        this.actorLightingBuffer = actorLightingBuffer;
        this.gpuVisibility = gpuVisibility;
        this.minimumProjectedPixels = renderingContext.camera.isOrtho() ? 0f : (float) tuning.fxMinProjectedPixels();
        this.simulationLodProjectedPixels =
                Math.max(minimumProjectedPixels, (float) tuning.fxSimulationLodProjectedPixels());
        this.textures = textures;
        this.sceneUniforms = sceneUniforms;
        this.performance = performance;
        this.meshPrograms = meshPrograms;
        this.frameBlendPrograms = frameBlendPrograms;
        BgfxProgram createdBillboard = null;
        BgfxProgram createdRibbon = null;
        BgfxProgram createdBillboardDepthMrt = null;
        BgfxProgram createdRibbonDepthMrt = null;
        BgfxShader createdBillboardVertex = null;
        BgfxShader createdRibbonVertex = null;
        BgfxShader createdFragment = null;
        BgfxShader createdDepthMrtFragment = null;
        BgfxUniform createdSampler = null;
        BgfxUniform createdDepthSampler = null;
        BgfxUniform createdParams = null;
        BgfxBuffer createdQuad = null;
        BgfxBuffer createdActorTransforms = null;
        actorTransformCapacity = tuning.fxActorTransformCapacity();
        actorTransformShadow = allocateBuffer(Math.multiplyExact(actorTransformCapacity, ACTOR_TRANSFORM_BYTES));
        try {
            createdBillboardVertex = BgfxShader.create(
                    device.getResources(),
                    "fx/billboard-vs",
                    BgfxShaderResources.load(device.getCapabilities(), "vs_fx_billboard"));
            createdRibbonVertex = BgfxShader.create(
                    device.getResources(),
                    "fx/ribbon-vs",
                    BgfxShaderResources.load(device.getCapabilities(), "vs_fx_ribbon"));
            createdFragment = BgfxShader.create(
                    device.getResources(), "fx/shared-fs", BgfxShaderResources.load(device.getCapabilities(), "fs_fx"));
            createdDepthMrtFragment = BgfxShader.create(
                    device.getResources(),
                    "fx/depth-mrt-shared-fs",
                    BgfxShaderResources.load(device.getCapabilities(), "fs_fx_depth_mrt"));
            createdBillboard = BgfxProgram.graphics(
                    device.getResources(), "fx/billboard", createdBillboardVertex, createdFragment);
            createdRibbon =
                    BgfxProgram.graphics(device.getResources(), "fx/ribbon", createdRibbonVertex, createdFragment);
            createdBillboardDepthMrt = BgfxProgram.graphics(
                    device.getResources(), "fx/billboard-depth-mrt", createdBillboardVertex, createdDepthMrtFragment);
            createdRibbonDepthMrt = BgfxProgram.graphics(
                    device.getResources(), "fx/ribbon-depth-mrt", createdRibbonVertex, createdDepthMrtFragment);
            createdSampler = BgfxUniform.create(device.getResources(), "s_fxTexture", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            createdDepthSampler = BgfxUniform.create(device.getResources(), "s_fxDepth", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            createdParams =
                    BgfxUniform.create(device.getResources(), "u_fxParams", BGFX_UNIFORM_TYPE_VEC4, PARAM_VECTORS);
            createdQuad = BgfxBuffer.staticVertex(
                    device.getResources(), "fx/unit-quad", unitQuad(), BgfxSceneVertexLayouts.FX_QUAD, 0);
            createdActorTransforms = BgfxBuffer.dynamicVertex(
                    device.getResources(),
                    "fx/actor-transforms",
                    Math.multiplyExact(actorTransformCapacity, 3),
                    BgfxSceneVertexLayouts.FX_ACTOR_TRANSFORM,
                    BGFX_BUFFER_COMPUTE_READ);
        } catch (RuntimeException | Error e) {
            closeAfterFailure(createdActorTransforms, e);
            closeAfterFailure(createdQuad, e);
            closeAfterFailure(createdParams, e);
            closeAfterFailure(createdDepthSampler, e);
            closeAfterFailure(createdSampler, e);
            closeAfterFailure(createdRibbonDepthMrt, e);
            closeAfterFailure(createdBillboardDepthMrt, e);
            closeAfterFailure(createdRibbon, e);
            closeAfterFailure(createdBillboard, e);
            closeAfterFailure(createdDepthMrtFragment, e);
            closeAfterFailure(createdFragment, e);
            closeAfterFailure(createdRibbonVertex, e);
            closeAfterFailure(createdBillboardVertex, e);
            throw e;
        }
        billboardProgram = createdBillboard;
        ribbonProgram = createdRibbon;
        billboardDepthMrtProgram = createdBillboardDepthMrt;
        ribbonDepthMrtProgram = createdRibbonDepthMrt;
        billboardVertex = createdBillboardVertex;
        ribbonVertex = createdRibbonVertex;
        fxFragment = createdFragment;
        fxDepthMrtFragment = createdDepthMrtFragment;
        sampler = createdSampler;
        depthSampler = createdDepthSampler;
        params = createdParams;
        quadVertices = createdQuad;
        actorTransforms = createdActorTransforms;
    }

    enum RenderBucket {
        ALL("all") {
            @Override
            boolean includes(ParticleEmitter emitter) {
                return !emitter.isLargeRefraction();
            }
        },
        IMMEDIATE("immediate") {
            @Override
            boolean includes(ParticleEmitter emitter) {
                return !emitter.isLargeRefraction() && !emitter.isDeferredAfterWater();
            }
        },
        DEFERRED("deferred") {
            @Override
            boolean includes(ParticleEmitter emitter) {
                return !emitter.isLargeRefraction() && emitter.isDeferredAfterWater();
            }
        },
        REFRACTION("refraction") {
            @Override
            boolean includes(ParticleEmitter emitter) {
                return emitter.isLargeRefraction();
            }
        };

        private final String viewSuffix;

        RenderBucket(String viewSuffix) {
            this.viewSuffix = viewSuffix;
        }

        abstract boolean includes(ParticleEmitter emitter);
    }

    static int compareNativeActorOrder(
            int leftPriority,
            float leftDistanceSquared,
            String leftActorName,
            int leftActorOrder,
            int leftChildOrder,
            int rightPriority,
            float rightDistanceSquared,
            String rightActorName,
            int rightActorOrder,
            int rightChildOrder) {
        var byPriority = Integer.compare(rightPriority, leftPriority);
        if (byPriority != 0) {
            return byPriority;
        }
        var byDistance = Float.compare(finiteDistance(rightDistanceSquared), finiteDistance(leftDistanceSquared));
        if (byDistance != 0) {
            return byDistance;
        }

        return compareSourceActorOrder(
                leftActorName, leftActorOrder, leftChildOrder, rightActorName, rightActorOrder, rightChildOrder);
    }

    static int compareSourceActorOrder(
            String leftActorName,
            int leftActorOrder,
            int leftChildOrder,
            String rightActorName,
            int rightActorOrder,
            int rightChildOrder) {
        var byPackage = actorPackage(leftActorName).compareTo(actorPackage(rightActorName));
        if (byPackage != 0) {
            return byPackage;
        }
        var byActor = Integer.compare(leftActorOrder, rightActorOrder);
        if (byActor != 0) {
            return byActor;
        }
        var byName = normalizedActorName(leftActorName).compareTo(normalizedActorName(rightActorName));
        return byName != 0 ? byName : Integer.compare(leftChildOrder, rightChildOrder);
    }

    static float @Nullable [] meshLightFactor(@Nullable Vec3 ambient) {
        return ambient == null ? null : new float[] {ambient.getX() * 2f, ambient.getY() * 2f, ambient.getZ() * 2f};
    }

    static int meshColorVariant(ParticleEmitter emitter, int surface) {
        return (emitter.hasParticleColorStage(surface) ? 1 : 0)
                | (emitter.drawsConstantAsParticleColor(surface) ? 2 : 0);
    }

    static float meshInstanceColor(float channel, int variant, boolean doubled) {
        var stage = (variant & 1) != 0 ? channel * (doubled ? 2f : 1f) : 1f;
        return (variant & 2) != 0 ? stage * channel : stage;
    }

    static float meshInstanceAlpha(float alpha, int variant) {
        return variant == 0 ? 1f : alpha;
    }

    static float @Nullable [] meshSurfaceLight(ParticleEmitter emitter, int surface, float @Nullable [] light) {
        return light != null && emitter.takesLight(surface) ? light : null;
    }

    static int countMeshInstances(ParticleEmitter emitter, int animationFrame) {
        var count = 0;
        for (var index = 0; index < emitter.getActiveParticleLimit(); index++) {
            if (emitter.isActive(index)
                    && (animationFrame < 0 || emitter.getMeshAnimationFrame(index) == animationFrame)) {
                count++;
            }
        }
        return count;
    }

    static float meshParticleAlphaCutoff(boolean useMeshBlendMode, boolean alphaTest, int alphaRef) {
        if (useMeshBlendMode || !alphaTest) {
            return -1f;
        }
        return Math.max(0, Math.min(255, alphaRef)) / 255f;
    }

    static float spriteAlphaCutoff(boolean alphaTest, int alphaRef) {
        return alphaTest ? Math.max(0, Math.min(255, alphaRef)) / 255f : -1f;
    }

    static int meshParticleBlendCode(boolean useMeshBlendMode, boolean blackBackground, int drawStyle) {
        if (useMeshBlendMode) {
            return -1;
        }
        if (blackBackground && drawStyle == 0) {
            return 2;
        }
        return switch (drawStyle) {
            case 6, 3 -> 2;
            case 2, 5 -> 3;
            case 1, 4 -> 1;
            default -> 0;
        };
    }

    static float particleFogMode(boolean fogEnabled, boolean disabledByActor, boolean localFog) {
        return !fogEnabled || disabledByActor ? 0f : localFog ? 2f : 1f;
    }

    static boolean writeActorTransform(ByteBuffer target, int offset, ParticleEmitter emitter) {
        var rotation = emitter.getActorRotation();
        var position = emitter.getActorPos();
        var changed = writeActorFloat(target, offset, rotation.x.floatValue());
        changed |= writeActorFloat(target, offset + 4, rotation.y.floatValue());
        changed |= writeActorFloat(target, offset + 8, rotation.z.floatValue());
        changed |= writeActorFloat(target, offset + 12, rotation.w.floatValue());
        changed |= writeActorFloat(target, offset + 16, position.getX());
        changed |= writeActorFloat(target, offset + 20, position.getY());
        changed |= writeActorFloat(target, offset + 24, position.getZ());
        changed |= writeActorFloat(target, offset + 28, 0f);
        changed |= writeActorFloat(target, offset + 32, emitter.getSubUScale());
        changed |= writeActorFloat(target, offset + 36, emitter.getSubVScale());
        changed |= writeActorFloat(target, offset + 40, emitter.getDirectionUsage());
        changed |= writeActorFloat(target, offset + 44, 0f);
        return changed;
    }

    static void putParticleMatrix(
            ByteBuffer target,
            float positionX,
            float positionY,
            float positionZ,
            Quat actor,
            float eulerX,
            float eulerY,
            float eulerZ,
            float scaleX,
            float scaleY,
            float scaleZ) {
        putParticleMatrix(
                target, positionX, positionY, positionZ, actor, eulerX, eulerY, eulerZ, scaleX, scaleY, scaleZ, null);
    }

    static void putParticleMatrix(
            ByteBuffer target,
            float positionX,
            float positionY,
            float positionZ,
            Quat actor,
            float eulerX,
            float eulerY,
            float eulerZ,
            float scaleX,
            float scaleY,
            float scaleZ,
            Vec3 prePivot) {
        var hx = eulerX * 0.5f;
        var hy = eulerY * 0.5f;
        var hz = eulerZ * 0.5f;
        var cx = (float) Math.cos(hx);
        var cy = (float) Math.cos(hy);
        var cz = (float) Math.cos(hz);
        var sx = (float) Math.sin(hx);
        var sy = (float) Math.sin(hy);
        var sz = (float) Math.sin(hz);
        var pw = cx * cy * cz + sx * sy * sz;
        var px = sx * cy * cz - cx * sy * sz;
        var py = cx * sy * cz + sx * cy * sz;
        var pz = cx * cy * sz - sx * sy * cz;

        float qw;
        float qx;
        float qy;
        float qz;
        if (actor == null) {
            qw = pw;
            qx = px;
            qy = py;
            qz = pz;
        } else {
            var aw = actor.w.floatValue();
            var ax = actor.x.floatValue();
            var ay = actor.y.floatValue();
            var az = actor.z.floatValue();
            qw = aw * pw - ax * px - ay * py - az * pz;
            qx = aw * px + ax * pw + ay * pz - az * py;
            qy = aw * py - ax * pz + ay * pw + az * px;
            qz = aw * pz + ax * py - ay * px + az * pw;
        }

        var xx = qx * qx;
        var yy = qy * qy;
        var zz = qz * qz;
        var xy = qx * qy;
        var xz = qx * qz;
        var yz = qy * qz;
        var wx = qw * qx;
        var wy = qw * qy;
        var wz = qw * qz;

        var m00 = (1f - 2f * (yy + zz)) * scaleX;
        var m10 = (2f * (xy + wz)) * scaleX;
        var m20 = (2f * (xz - wy)) * scaleX;
        var m01 = (2f * (xy - wz)) * scaleY;
        var m11 = (1f - 2f * (xx + zz)) * scaleY;
        var m21 = (2f * (yz + wx)) * scaleY;
        var m02 = (2f * (xz + wy)) * scaleZ;
        var m12 = (2f * (yz - wx)) * scaleZ;
        var m22 = (1f - 2f * (xx + yy)) * scaleZ;
        target.putFloat(m00);
        target.putFloat(m10);
        target.putFloat(m20);
        target.putFloat(0f);
        target.putFloat(m01);
        target.putFloat(m11);
        target.putFloat(m21);
        target.putFloat(0f);
        target.putFloat(m02);
        target.putFloat(m12);
        target.putFloat(m22);
        target.putFloat(0f);
        var pivotX = prePivot == null ? 0f : prePivot.getX();
        var pivotY = prePivot == null ? 0f : prePivot.getY();
        var pivotZ = prePivot == null ? 0f : prePivot.getZ();
        target.putFloat(positionX - m00 * pivotX - m01 * pivotY - m02 * pivotZ);
        target.putFloat(positionY - m10 * pivotX - m11 * pivotY - m12 * pivotZ);
        target.putFloat(positionZ - m20 * pivotX - m21 * pivotY - m22 * pivotZ);
        target.putFloat(1f);
    }

    static Vec3 billboardCameraUp(Camera camera, Vec3 right) {
        return camera.isOrtho() ? camera.getForwardDir() : cameraUp(right, camera.getForwardDir());
    }

    private record FxView(
            String viewName,
            BgfxViewAllocator.Domain domain,
            FloatBuffer view,
            FloatBuffer projection,
            Vec3 position,
            Vec3 right,
            Vec3 up,
            Frustum frustum,
            Vec4 clipPlane,
            boolean reflected) {}

    void useSceneZones(BgfxSceneZones zones) {
        sceneZones = zones;
    }

    void registerMeshEmitter(String owner, ParticleEmitter emitter, EntityMesh source) {
        registerMeshEmitter(owner, emitter, source, null);
    }

    void configureFrameEnvironment(Snapshot environment, float mipBias) {
        frameFog = environment == null ? null : environment.fog();
        localFogActive = environment != null && environment.localFogActive();
        textureMipBias = mipBias;
    }

    void registerMeshEmitter(String owner, ParticleEmitter emitter, EntityMesh source, Prepared prepared) {
        requireOpen();
        requireOwner(owner);
        if (meshBindings.containsKey(emitter)) {
            throw new IllegalStateException("particle emitter already has BGFX geometry");
        }
        var label = emitter.getDebugName() == null || emitter.getDebugName().isBlank()
                ? "mesh-" + meshOrdinal
                : sanitize(emitter.getDebugName()) + '-' + meshOrdinal;
        var discriminator = meshOrdinal++;
        var geometry = prepared == null
                ? BgfxSceneGeometry.uploadParticle(geometryPool, textures, source, label, discriminator)
                : BgfxSceneGeometry.installParticle(geometryPool, textures, source, prepared);
        try {
            var binding = new MeshBinding(owner, geometry, new MeshPickData(source));
            emitter.setMeshBounds(source.boundingBox());
            meshBindings.put(emitter, binding);
            ownedMeshBindings.add(binding);
            residencyRevision++;
        } catch (RuntimeException | Error e) {
            closeAfterFailure(geometry, e);
            throw e;
        }
    }

    void registerAnimatedMeshEmitter(
            String owner, ParticleEmitter emitter, List<EntityMesh> frames, List<Prepared> preparedFrames) {
        requireOpen();
        requireOwner(owner);
        if (meshBindings.containsKey(emitter)) {
            throw new IllegalStateException("particle emitter already has BGFX geometry");
        }
        if (frames == null
                || frames.isEmpty()
                || preparedFrames == null
                || (!preparedFrames.isEmpty() && preparedFrames.size() != frames.size())) {
            throw new IllegalArgumentException("invalid animated particle mesh frames");
        }

        var assets = animatedMeshAssetsByOwner.computeIfAbsent(owner, ignored -> new IdentityHashMap<>());
        var key = frames.getFirst();
        var asset = assets.get(key);
        if (asset == null) {
            var label = emitter.getDebugName() == null || emitter.getDebugName().isBlank()
                    ? "vert-mesh-" + meshOrdinal
                    : sanitize(emitter.getDebugName()) + '-' + meshOrdinal;
            var discriminator = meshOrdinal++;
            var installed = new ArrayList<BgfxSceneGeometry>(frames.size());
            try {
                for (var frame = 0; frame < frames.size(); frame++) {
                    var source = frames.get(frame);
                    var geometry = preparedFrames.isEmpty()
                            ? BgfxSceneGeometry.uploadParticle(
                                    geometryPool, textures, source, label + "-frame-" + frame, discriminator)
                            : BgfxSceneGeometry.installParticle(
                                    geometryPool, textures, source, preparedFrames.get(frame));
                    installed.add(geometry);
                }
                asset = new AnimatedMeshAsset(key, installed, MeshPickData.ofFrames(frames));
                assets.put(key, asset);
            } catch (RuntimeException | Error failure) {
                for (var index = installed.size() - 1; index >= 0; index--) {
                    closeAfterFailure(installed.get(index), failure);
                }
                if (assets.isEmpty()) {
                    animatedMeshAssetsByOwner.remove(owner);
                }
                throw failure;
            }
        }
        asset.retain();
        var animatedBounds = new Box();
        for (var frame : frames) {
            animatedBounds.add(frame.boundingBox().getMin());
            animatedBounds.add(frame.boundingBox().getMax());
        }
        emitter.setMeshBounds(animatedBounds);
        var binding = new MeshBinding(owner, asset);
        meshBindings.put(emitter, binding);
        ownedMeshBindings.add(binding);
        residencyRevision++;
    }

    boolean attachMeshTwin(ParticleEmitter source, ParticleEmitter twin) {
        if (closed) {
            return false;
        }
        var binding = meshBindings.get(source);
        if (binding == null) {
            return false;
        }
        meshBindings.put(twin, binding);
        residencyRevision++;
        return true;
    }

    void detachMeshTwin(ParticleEmitter twin) {
        if (closed) {
            return;
        }
        if (meshBindings.remove(twin) != null) {
            activeFxEmitters.removeIf(prepared -> prepared.emitter == twin);
            residencyRevision++;
        }
    }

    void unregisterMeshEmitter(String owner, ParticleEmitter emitter) {
        requireOpen();
        requireOwner(owner);
        var binding = meshBindings.remove(emitter);
        if (binding == null) {
            return;
        }
        if (!owner.equals(binding.owner)) {
            meshBindings.put(emitter, binding);
            throw new IllegalArgumentException("mesh emitter belongs to another residency owner");
        }
        ownedMeshBindings.remove(binding);
        activeFxEmitters.removeIf(prepared -> prepared.emitter == emitter);
        for (var batch : meshFrameBatches) {
            if (batch.binding == binding) {
                batch.clear();
            }
        }
        if (binding.animatedAsset == null) {
            binding.close();
        } else if (binding.animatedAsset.release()) {
            var assets = animatedMeshAssetsByOwner.get(owner);
            if (assets != null) {
                assets.remove(binding.animatedAsset.key);
                if (assets.isEmpty()) {
                    animatedMeshAssetsByOwner.remove(owner);
                }
            }
            binding.animatedAsset.close();
        }
        residencyRevision++;
    }

    Texture registerParticleTexture(String owner, Texture source, String label) {
        requireOpen();
        requireOwner(owner);
        if (source == null) {
            return null;
        }
        if (label != null) {
            var canonical = particleTexturesByLabel.putIfAbsent(label, source);
            if (canonical != null) {
                source = canonical;
            }
        }
        var binding = particleTextures.get(source);
        if (binding == null) {
            binding = new ParticleTexture(textures.load(source, label));
            particleTextures.put(source, binding);
        }
        binding.retain(owner);
        residencyRevision++;
        return source;
    }

    void unregisterParticleTexture(String owner, Texture source) {
        requireOpen();
        requireOwner(owner);
        if (source == null) {
            return;
        }
        var binding = particleTextures.get(source);
        if (binding == null) {
            return;
        }
        if (binding.release(owner)) {
            particleTextures.remove(source);
            var released = source;
            particleTexturesByLabel.values().removeIf(texture -> texture == released);
            binding.texture.close();
        }
        residencyRevision++;
    }

    void unregisterParticleTexture(String owner, ParticleEmitter emitter, Texture source) {
        releaseActorTransform(emitter);
        spritePayloads.remove(emitter);
        releaseScratchBatch(billboardBatchByEmitter, billboardGroups, activeBillboardGroups, emitter);
        releaseScratchBatch(ribbonBatchByEmitter, ribbonGroups, activeRibbonGroups, emitter);
        activeFxEmitters.removeIf(prepared -> prepared.emitter == emitter);
        unregisterParticleTexture(owner, source);
    }

    long getResidencyRevision() {
        return residencyRevision;
    }

    @Nullable
    TextureRef findParticleTexture(Texture source) {
        var binding = particleTextures.get(source);
        return binding == null ? null : binding.texture;
    }

    Map<String, OwnedResidency> getResidency(Map<TextureRef, Set<String>> textureOwners) {
        var result = new HashMap<String, OwnedResidency>();
        var bindingTextures = new HashSet<TextureRef>();
        var seenByOwner = new HashMap<String, Set<BgfxSceneGeometry>>();
        var seenPickData = Collections.newSetFromMap(new IdentityHashMap<MeshPickData, Boolean>());
        for (var binding : ownedMeshBindings) {
            for (var pick : binding.pickFrames()) {
                if (seenPickData.add(pick)) {
                    var old = result.getOrDefault(binding.owner, OwnedResidency.EMPTY);
                    result.put(
                            binding.owner,
                            new OwnedResidency(
                                    Math.addExact(old.retainedCpuBytes, pick.getRetainedBytes()),
                                    old.geometryGpuBytes));
                }
            }
            var seen = seenByOwner.computeIfAbsent(
                    binding.owner, ignored -> Collections.newSetFromMap(new IdentityHashMap<>()));
            for (var geometry : binding.geometries()) {
                if (!seen.add(geometry)) {
                    continue;
                }
                var old = result.getOrDefault(binding.owner, OwnedResidency.EMPTY);
                result.put(
                        binding.owner,
                        new OwnedResidency(
                                Math.addExact(old.retainedCpuBytes, geometry.getRetainedCpuBytes()),
                                Math.addExact(old.geometryGpuBytes, geometry.getEstimatedGpuBytes())));
                bindingTextures.clear();
                geometry.collectTextures(bindingTextures);
                for (var texture : bindingTextures) {
                    addTextureOwner(textureOwners, texture, binding.owner);
                }
            }
        }
        for (var binding : particleTextures.values()) {
            for (var owner : binding.referencesByOwner.keySet()) {
                addTextureOwner(textureOwners, binding.texture, owner);
            }
        }
        return result;
    }

    long getNonEvictableCpuBytes() {
        var bytes = 0L;
        var seen = Collections.newSetFromMap(new IdentityHashMap<BgfxSceneGeometry, Boolean>());
        var seenPickData = Collections.newSetFromMap(new IdentityHashMap<MeshPickData, Boolean>());
        for (var binding : ownedMeshBindings) {
            if (BgfxSceneRenderer.GLOBAL_RESIDENCY_OWNER.equals(binding.owner)) {
                for (var pick : binding.pickFrames()) {
                    if (seenPickData.add(pick)) {
                        bytes = Math.addExact(bytes, pick.getRetainedBytes());
                    }
                }
                for (var geometry : binding.geometries()) {
                    if (seen.add(geometry)) {
                        bytes = Math.addExact(bytes, geometry.getRetainedCpuBytes());
                    }
                }
            }
        }
        bytes = Math.addExact(bytes, capacityOf(billboardScratch));
        bytes = Math.addExact(bytes, capacityOf(ribbonScratch));
        bytes = Math.addExact(bytes, capacityOf(meshScratch));
        for (var group : billboardGroups) {
            bytes = Math.addExact(bytes, capacityOf(group.data));
            bytes = Math.addExact(
                    bytes, group.spritePayloads == null ? 0L : (long) group.spritePayloads.length * Long.BYTES);
        }
        for (var group : ribbonGroups) {
            bytes = Math.addExact(bytes, capacityOf(group.data));
        }
        for (var payload : spritePayloads.values()) {
            bytes = Math.addExact(bytes, (long) capacityOf(payload.data) * Float.BYTES);
        }
        bytes = Math.addExact(bytes, (long) beamSpineScratch.length * Float.BYTES);
        bytes = Math.addExact(bytes, (long) beamControlScratch.length * Float.BYTES);
        return bytes;
    }

    int render(
            BgfxFramebuffer target,
            Extent extent,
            BgfxViewAllocator views,
            float elapsedSeconds,
            float deltaSeconds,
            boolean enabled,
            boolean depthMrt) {
        return render(
                target, extent, views, elapsedSeconds, deltaSeconds, enabled, depthMrt, RenderBucket.ALL, true, null);
    }

    int render(
            BgfxFramebuffer target,
            Extent extent,
            BgfxViewAllocator views,
            float elapsedSeconds,
            float deltaSeconds,
            boolean enabled,
            boolean depthMrt,
            RenderBucket bucket,
            boolean updateSimulation) {
        return render(
                target,
                extent,
                views,
                elapsedSeconds,
                deltaSeconds,
                enabled,
                depthMrt,
                bucket,
                updateSimulation,
                null,
                mainView(bucket));
    }

    int render(
            BgfxFramebuffer target,
            Extent extent,
            BgfxViewAllocator views,
            float elapsedSeconds,
            float deltaSeconds,
            boolean enabled,
            boolean depthMrt,
            RenderBucket bucket,
            boolean updateSimulation,
            BgfxTexture softDepth) {
        return render(
                target,
                extent,
                views,
                elapsedSeconds,
                deltaSeconds,
                enabled,
                depthMrt,
                bucket,
                updateSimulation,
                softDepth,
                mainView(bucket));
    }

    void setMeshLighting(BgfxSpawnModelLighting.@Nullable Frame lighting) {
        meshLighting = lighting;
    }

    void beginFrame() {
        requireOpen();
        meshFrameElements = 0;
        billboardFrameElements = 0;
        ribbonFrameElements = 0;
    }

    int renderReflection(
            BgfxFramebuffer target,
            Extent extent,
            BgfxViewAllocator views,
            float elapsedSeconds,
            boolean enabled,
            String viewName,
            FloatBuffer view,
            FloatBuffer projection,
            FloatBuffer cullingProjection,
            Vec3 cameraPosition,
            Vec3 cameraRight,
            Vec3 cameraUp,
            Vec4 clipPlane) {
        var cullingProjectionMatrix = new Mat4(firstSixteenFloats(cullingProjection), 0);
        var viewMatrix = new Mat4(firstSixteenFloats(view), 0);
        var reflectedView = new FxView(
                viewName,
                BgfxViewAllocator.Domain.REFLECTION,
                firstSixteenFloats(view),
                firstSixteenFloats(projection),
                cameraPosition,
                cameraRight,
                cameraUp,
                new Frustum(cullingProjectionMatrix, viewMatrix),
                clipPlane,
                true);
        return render(
                target,
                extent,
                views,
                elapsedSeconds,
                0f,
                enabled,
                false,
                RenderBucket.ALL,
                false,
                null,
                reflectedView);
    }

    DrawCounts getDrawCounts() {
        return new DrawCounts(meshDraws, billboardDraws, beamDraws);
    }

    boolean hasLargeRefraction() {
        for (var emitter : renderingContext.particles.getEmitters(Kind.SPRITE)) {
            if (renderingContext.inspection.acceptsEmitter(emitter) && emitter.isLargeRefraction()) {
                return true;
            }
        }
        return false;
    }

    RefractionCounts getRefractionCounts() {
        return refractionCounts;
    }

    void setRefractionPassPerEffect(boolean passPerEffect) {
        refractionPassPerEffect = passPerEffect;
    }

    int renderRefraction(BgfxTexture color, BgfxTexture depth, Extent extent, BgfxViewAllocator views) {
        refractionCounts = RefractionCounts.NONE;
        if (color == null
                || depth == null
                || renderingContext.particles.getVisibilityDistance() <= 0f
                || !hasLargeRefraction()) {
            refractionShortReported = false;
            return 0;
        }
        var renderView = mainView(RenderBucket.REFRACTION);
        setFxCamera(renderView.right, renderView.up, renderView.position, renderView.clipPlane);
        setFxDepthFrame(extent, null);
        activeFxEmitters.clear();
        prepareBillboards(extent, RenderBucket.REFRACTION, null, renderView);
        if (activeFxEmitters.isEmpty()) {
            refractionShortReported = false;
            return 0;
        }
        flushActorTransforms();
        for (var prepared : activeFxEmitters) {
            prepared.sortDistanceSquared =
                    finiteDistance(actorSortDistanceSquared(prepared.emitter, renderView.position));
        }
        activeFxEmitters.sort((left, right) -> compareNativeRenderOrder(RenderBucket.DEFERRED, left, right));
        var total = activeFxEmitters.size();
        var capacity = BgfxViewAllocator.countFitting(
                views.getRemaining(), BgfxParticleRefraction.VIEWS, BgfxViewAllocator.END_OF_FRAME_RESERVE);
        if (capacity == 0) {
            if (!refractionShortReported) {
                refractionShortReported = true;
                log.warn(
                        "Large refraction: the frame has no views left for it and leaves out its {} effects of this"
                                + " kind until a frame has room for them.",
                        total);
            }
            refractionCounts = new RefractionCounts(total, 0, 0, false);
            return 0;
        }
        refractionShortReported = false;
        var passes = assignRefractionPasses(extent, renderView, capacity);
        if (particleRefraction == null) {
            particleRefraction = new BgfxParticleRefraction(device);
        }
        if (refractionRegionProgram == null) {
            refractionRegionProgram = BgfxProgram.graphics(
                    device.getResources(),
                    "fx/refraction-region",
                    billboardVertex,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_fx_refraction_region"));
        }
        var draws = 0;
        var drawn = 0;
        for (var pass = 0; pass < passes; pass++) {
            refractionPass.clear();
            refractionPassMembers.clear();
            for (var effect = 0; effect < total; effect++) {
                if (refractionLayers.getLayer(effect) != pass) {
                    continue;
                }
                var emitter = activeFxEmitters.get(effect).emitter;
                refractionLayers.getScissor(effect, refractionScissor);
                refractionPass.add(
                        emitter.getRefrUScale(),
                        emitter.getRefrVScale(),
                        refractionScissor[0],
                        refractionScissor[1],
                        refractionScissor[2],
                        refractionScissor[3]);
                refractionPassMembers.add(activeFxEmitters.get(effect));
            }
            if (refractionPassMembers.isEmpty()) {
                continue;
            }
            particleRefraction.render(
                    color,
                    depth,
                    extent,
                    views,
                    renderView.view,
                    renderView.projection,
                    refractionPass,
                    view -> {
                        for (var prepared : refractionPassMembers) {
                            submitBillboardEmitter(
                                    view,
                                    false,
                                    null,
                                    renderView,
                                    prepared.scratch,
                                    null,
                                    false,
                                    refractionRegionProgram,
                                    BGFX_STATE_WRITE_R | BGFX_STATE_DEPTH_TEST_LEQUAL);
                        }
                    },
                    view -> {
                        for (var prepared : refractionPassMembers) {
                            submitBillboardEmitter(view, false, null, renderView, prepared.scratch, null, false);
                        }
                    });
            draws += 2 * refractionPassMembers.size();
            drawn += refractionPassMembers.size();
        }
        refractionCounts = new RefractionCounts(total, drawn, passes, refractionLayers.isMerged());
        if (refractionLayers.isMerged() != refractionMergeReported) {
            refractionMergeReported = refractionLayers.isMerged();
            if (refractionMergeReported) {
                log.debug(
                        "Large refraction: {} effects need more passes than the {} that fit. The effects of the last"
                                + " pass bend one copy of the picture.",
                        total,
                        capacity);
            }
        }
        return draws;
    }

    void configure(float distance, int distanceModeOrdinal, MapEventState gameEventStates, float timeOfDay) {
        if (renderingContext.camera.isOrtho() && !renderingContext.camera.isInteractiveOrtho()) {
            renderingContext.particles.configureCapture(gameEventStates, timeOfDay);
            return;
        }
        renderingContext.particles.setVisibilityDistance(distance);
        renderingContext.particles.setDistanceMode(EmitterDistanceMode.of(distanceModeOrdinal));
        renderingContext.particles.setGameEventStates(gameEventStates);
        renderingContext.particles.setTimeOfDay(timeOfDay);
    }

    boolean hasSoftParticles(RenderBucket bucket) {
        for (var emitter : renderingContext.particles.getEmitters()) {
            if (renderingContext.inspection.acceptsEmitter(emitter)
                    && bucket.includes(emitter)
                    && emitter.isUseSoftParticle()) {
                return true;
            }
        }
        return false;
    }

    Bounds3f getDebugMeshBounds(ParticleEmitter emitter) {
        var binding = meshBindings.get(emitter);
        if (binding == null) {
            return null;
        }
        var bounds = new Box();
        for (var index = 0; index < emitter.getActiveParticleLimit(); index++) {
            if (!emitter.isActive(index)) {
                continue;
            }
            emitter.writeMeshWorldPos(index, pickPosition);
            pickModel.clear();
            putParticleMatrix(
                    pickModel,
                    pickPosition[0],
                    pickPosition[1],
                    pickPosition[2],
                    emitter.doesMeshUseActorRenderRotation() ? emitter.getActorRotation() : null,
                    -emitter.getRoll(index),
                    -emitter.getYaw(index),
                    emitter.getPitch(index),
                    emitter.getScaleX(index),
                    emitter.getScaleY(index),
                    emitter.getScaleZ(index),
                    emitter.getMeshPrePivot());
            var frame = binding.frameCount() == 1 ? 0 : emitter.getMeshAnimationFrame(index);
            var data = binding.animatedAsset == null ? binding.pickData : binding.animatedAsset.pickFrames.get(frame);
            data.appendWorldBounds(pickModel, bounds);
        }
        return bounds.isValid()
                ? new Bounds3f(
                        bounds.getMinX(),
                        bounds.getMinY(),
                        bounds.getMinZ(),
                        bounds.getMaxX(),
                        bounds.getMaxY(),
                        bounds.getMaxZ())
                : null;
    }

    float pickMeshDistance(ParticleEmitter emitter, Vec3 origin, Vec3 direction, float maxDistance) {
        var binding = meshBindings.get(emitter);
        if (binding == null) {
            return -1;
        }
        var nearest = maxDistance;
        var found = false;
        for (var index = 0; index < emitter.getActiveParticleLimit(); index++) {
            if (!emitter.isActive(index)) {
                continue;
            }
            emitter.writeMeshWorldPos(index, pickPosition);
            pickModel.clear();
            putParticleMatrix(
                    pickModel,
                    pickPosition[0],
                    pickPosition[1],
                    pickPosition[2],
                    emitter.doesMeshUseActorRenderRotation() ? emitter.getActorRotation() : null,
                    -emitter.getRoll(index),
                    -emitter.getYaw(index),
                    emitter.getPitch(index),
                    emitter.getScaleX(index),
                    emitter.getScaleY(index),
                    emitter.getScaleZ(index),
                    emitter.getMeshPrePivot());
            var frame = binding.frameCount() == 1 ? 0 : emitter.getMeshAnimationFrame(index);
            var data = binding.animatedAsset == null ? binding.pickData : binding.animatedAsset.pickFrames.get(frame);
            var hit = data.computeDistance(origin, direction, pickModel, nearest);
            if (hit >= 0) {
                nearest = hit;
                found = true;
            }
        }
        return found ? nearest : -1;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        failure = closeAndMerge(ribbonVertices, failure);
        failure = closeAndMerge(billboardInstances, failure);
        failure = closeAndMerge(meshInstances, failure);
        for (var index = ownedMeshBindings.size() - 1; index >= 0; index--) {
            failure = closeAndMerge(ownedMeshBindings.get(index), failure);
        }
        ownedMeshBindings.clear();
        meshBindings.clear();
        spritePayloads.clear();
        for (var assets : animatedMeshAssetsByOwner.values()) {
            for (var asset : assets.values()) {
                failure = closeAndMerge(asset, failure);
            }
        }
        animatedMeshAssetsByOwner.clear();
        meshFrameBatches.clear();
        meshEmitterBatches.clear();
        billboardGroups.clear();
        activeBillboardGroups.clear();
        billboardBatchByEmitter.clear();
        ribbonGroups.clear();
        activeRibbonGroups.clear();
        ribbonBatchByEmitter.clear();
        activeFxEmitters.clear();
        actorTransformIndices.clear();
        freeActorTransformIndices.clear();
        billboardScratch = null;
        billboardScratchFloats = null;
        ribbonScratch = null;
        meshScratch = null;
        for (var texture : particleTextures.values()) {
            failure = closeAndMerge(texture.texture, failure);
        }
        particleTextures.clear();
        failure = closeAndMerge(actorTransforms, failure);
        failure = closeAndMerge(quadVertices, failure);
        failure = closeAndMerge(params, failure);
        failure = closeAndMerge(depthSampler, failure);
        failure = closeAndMerge(sampler, failure);
        failure = closeAndMerge(ribbonDepthMrtProgram, failure);
        failure = closeAndMerge(billboardDepthMrtProgram, failure);
        failure = closeAndMerge(ribbonProgram, failure);
        failure = closeAndMerge(billboardProgram, failure);
        failure = closeAndMerge(refractionRegionProgram, failure);
        failure = closeAndMerge(particleRefraction, failure);
        failure = closeAndMerge(fxDepthMrtFragment, failure);
        failure = closeAndMerge(fxFragment, failure);
        failure = closeAndMerge(ribbonVertex, failure);
        failure = closeAndMerge(billboardVertex, failure);
        for (var layout : followingFrameLayouts.values()) {
            failure = closeAndMerge(layout, failure);
        }
        followingFrameLayouts.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private int render(
            BgfxFramebuffer target,
            Extent extent,
            BgfxViewAllocator views,
            float elapsedSeconds,
            float deltaSeconds,
            boolean enabled,
            boolean depthMrt,
            RenderBucket bucket,
            boolean updateSimulation,
            BgfxTexture softDepth,
            FxView renderView) {
        requireOpen();
        if (updateSimulation) {
            meshDraws = 0;
            billboardDraws = 0;
            beamDraws = 0;
        }
        var particles = renderingContext.particles;
        if (!enabled || extent.isZero() || particles.isEmpty()) {
            return 0;
        }
        if (particles.getVisibilityDistance() <= 0f) {
            return 0;
        }
        if (updateSimulation) {
            var simulationStarted = performance.start();
            particles.update(
                    deltaSeconds,
                    renderingContext.camera.getWorldFocusPosition(),
                    renderingContext.camera.getFrustum(),
                    gpuVisibility.getPreparedViewProjection(),
                    extent.width(),
                    extent.height(),
                    minimumProjectedPixels,
                    simulationLodProjectedPixels);
            performance.addPhase(CpuPhase.FX_SIMULATION, performance.getElapsed(simulationStarted));
        }

        var viewName = renderView.viewName;
        var view = views.allocate(viewName, renderView.domain, true);
        bgfx_set_view_name(view, viewName);
        bgfx_set_view_rect(view, 0, 0, extent.width(), extent.height());
        bgfx_set_view_frame_buffer(view, target == null ? BGFX_INVALID_HANDLE : target.getHandle());
        bgfx_set_view_mode(view, BGFX_VIEW_MODE_SEQUENTIAL);
        sceneUniforms.beginView(true);
        bgfx_set_view_transform(view, renderView.view, renderView.projection);
        bgfx_touch(view);

        setFxCamera(renderView.right, renderView.up, renderView.position, renderView.clipPlane);
        setFxDepthFrame(extent, softDepth);

        activeFxEmitters.clear();
        var meshStarted = performance.start();
        prepareMeshes(extent, bucket, renderView);
        performance.addPhase(CpuPhase.FX_MESH_ENCODING, performance.getElapsed(meshStarted));
        var billboardStarted = performance.start();
        prepareBillboards(extent, bucket, softDepth, renderView);
        performance.addPhase(CpuPhase.FX_BILLBOARD_ENCODING, performance.getElapsed(billboardStarted));
        var beamStarted = performance.start();
        prepareBeams(extent, bucket, softDepth, renderView);
        performance.addPhase(CpuPhase.FX_BEAM_ENCODING, performance.getElapsed(beamStarted));
        var orderingStarted = performance.start();
        for (var prepared : activeFxEmitters) {
            prepared.sortDistanceSquared =
                    finiteDistance(actorSortDistanceSquared(prepared.emitter, renderView.position));
        }
        activeFxEmitters.sort((left, right) -> compareNativeRenderOrder(bucket, left, right));
        performance.addPhase(CpuPhase.FX_ORDERING, performance.getElapsed(orderingStarted));
        var passMeshDraws = 0;
        var passBillboardDraws = 0;
        var passBeamDraws = 0;
        var submissionStarted = performance.start();
        TextureRef retainedBillboardTexture = null;
        for (var preparedIndex = 0; preparedIndex < activeFxEmitters.size(); preparedIndex++) {
            var prepared = activeFxEmitters.get(preparedIndex);
            switch (prepared.kind) {
                case MESH ->
                    passMeshDraws += submitMeshEmitter(
                            view, extent, elapsedSeconds, depthMrt, softDepth, renderView, prepared.mesh);
                case BILLBOARD -> {
                    var retain = RETAIN_BILLBOARD_BINDINGS
                            && preparedIndex + 1 < activeFxEmitters.size()
                            && activeFxEmitters.get(preparedIndex + 1).kind == PreparedFxKind.BILLBOARD;
                    passBillboardDraws += submitBillboardEmitter(
                            view, depthMrt, softDepth, renderView, prepared.scratch, retainedBillboardTexture, retain);
                    retainedBillboardTexture = retain ? prepared.scratch.texture : null;
                }
                case BEAM ->
                    passBeamDraws += submitBeamEmitter(view, depthMrt, softDepth, renderView, prepared.scratch);
            }
        }
        performance.addPhase(CpuPhase.FX_SUBMISSION, performance.getElapsed(submissionStarted));
        meshDraws += passMeshDraws;
        billboardDraws += passBillboardDraws;
        beamDraws += passBeamDraws;
        return passMeshDraws + passBillboardDraws + passBeamDraws;
    }

    private void prepareMeshes(Extent extent, RenderBucket bucket, FxView renderView) {
        var particles = renderingContext.particles;
        var frustum = renderView.frustum;
        var activeBatches = 0;
        for (var emitter : renderEmitters(particles, Kind.MESH)) {
            if (!renderingContext.inspection.acceptsEmitter(emitter) || !bucket.includes(emitter)) {
                continue;
            }
            var binding = meshBindings.get(emitter);
            if (binding == null
                    || !particles.isVisibleThisFrame(emitter, frustum)
                    || !sceneZones.drawsEmitter(emitter)) {
                continue;
            }
            if (!renderView.reflected
                    && gpuVisibility.isProjectedSizeInvisible(particles, emitter, extent, minimumProjectedPixels)) {
                continue;
            }
            var light = meshLightOf(emitter, binding);
            for (var animationFrame = 0; animationFrame < binding.frameCount(); animationFrame++) {
                var frameFilter = binding.frameCount() == 1 ? -1 : animationFrame;
                var count = countMeshInstances(emitter, frameFilter);
                if (count == 0) {
                    continue;
                }
                var batch = meshFrameBatch(activeBatches++);
                batch.emitter = emitter;
                batch.binding = binding;
                batch.animationFrame = animationFrame;
                batch.followingFrame = followingFrame(binding, emitter, animationFrame);
                batch.count = count;
                batch.light = light;
                batch.variants = 0;
                for (var primitive : binding.geometry(animationFrame).getPrimitives()) {
                    batch.variants |= 1 << meshColorVariant(emitter, primitive.sourceSurfaceOrdinal());
                }
            }
        }
        var totalInstances = 0;
        for (var index = 0; index < activeBatches; index++) {
            var batch = meshFrameBatches.get(index);
            var primitiveCount =
                    batch.binding.geometry(batch.animationFrame).getPrimitives().size();
            batch.selected = device.getSubmissionBudget()
                    .reserve(
                            BgfxSubmissionBudget.Domain.FX,
                            Math.multiplyExact((long) BgfxSubmissionBudget.ENTITY_SCENE_DRAW_BYTES, primitiveCount),
                            primitiveCount);
            if (batch.selected) {
                totalInstances = Math.addExact(
                        totalInstances, Math.multiplyExact(batch.count, Integer.bitCount(batch.variants)));
            }
        }
        if (totalInstances == 0) {
            return;
        }

        var selectedFirst = meshFrameElements;
        for (var index = 0; index < activeBatches; index++) {
            var batch = meshFrameBatches.get(index);
            if (!batch.selected) {
                continue;
            }
            for (var variant = 0; variant < MESH_COLOR_VARIANTS; variant++) {
                if ((batch.variants >>> variant & 1) != 0) {
                    batch.first[variant] = selectedFirst;
                    selectedFirst = Math.addExact(selectedFirst, batch.count);
                }
            }
        }

        var nextMeshElements = Math.addExact(meshFrameElements, totalInstances);
        ensureMeshCapacity(nextMeshElements);
        ensureMeshScratch(Math.multiplyExact(totalInstances, BgfxSceneVertexLayouts.INSTANCE.getStride()));
        meshScratch.clear();
        for (var index = 0; index < activeBatches; index++) {
            var batch = meshFrameBatches.get(index);
            if (!batch.selected) {
                continue;
            }
            var frameFilter = batch.binding.frameCount() == 1 ? -1 : batch.animationFrame;
            for (var variant = 0; variant < MESH_COLOR_VARIANTS; variant++) {
                if ((batch.variants >>> variant & 1) == 0) {
                    continue;
                }
                var written = appendMeshInstances(
                        batch.emitter,
                        meshScratch,
                        frameFilter,
                        batch.followingFrame >= 0,
                        variant,
                        batch.light != null && batch.emitter.isLitByActor());
                if (written != batch.count) {
                    throw new IllegalStateException(
                            "mesh particle activity changed while building the frame instance stream");
                }
            }
        }
        meshScratch.flip();
        meshInstances.update(meshFrameElements, meshScratch);
        meshFrameElements = nextMeshElements;

        var activeEmitters = 0;
        for (var firstBatch = 0; firstBatch < activeBatches; ) {
            var emitter = meshFrameBatches.get(firstBatch).emitter;
            var endBatch = firstBatch + 1;
            var selected = meshFrameBatches.get(firstBatch).selected;
            while (endBatch < activeBatches && meshFrameBatches.get(endBatch).emitter == emitter) {
                selected |= meshFrameBatches.get(endBatch).selected;
                endBatch++;
            }
            if (selected) {
                var emitterBatch = meshEmitterBatch(activeEmitters++);
                emitterBatch.emitter = emitter;
                emitterBatch.firstFrameBatch = firstBatch;
                emitterBatch.frameBatchCount = endBatch - firstBatch;
                emitterBatch.selected = true;
                activeFxEmitters.add(emitterBatch.prepared());
            }
            firstBatch = endBatch;
        }
        for (var index = activeEmitters; index < meshEmitterBatches.size(); index++) {
            meshEmitterBatches.get(index).clear();
        }
    }

    private int submitMeshEmitter(
            int view,
            Extent extent,
            float elapsedSeconds,
            boolean depthMrt,
            BgfxTexture softDepth,
            FxView renderView,
            MeshEmitterBatch emitterBatch) {
        var draws = 0;
        var end = emitterBatch.firstFrameBatch + emitterBatch.frameBatchCount;
        for (var index = emitterBatch.firstFrameBatch; index < end; index++) {
            var batch = meshFrameBatches.get(index);
            if (!batch.selected) {
                continue;
            }
            var geometry = batch.binding.geometry(batch.animationFrame);
            var following = batch.followingFrame < 0 ? null : batch.binding.geometry(batch.followingFrame);
            for (var primitive : geometry.getPrimitives()) {
                var material = primitive.entityMaterial();
                setParticleFrame(
                        extent,
                        batch.emitter,
                        softDepth,
                        renderView,
                        batch.emitter.isUseMeshBlendMode() ? material.getBlendMode() : null,
                        meshSurfaceLight(batch.emitter, primitive.sourceSurfaceOrdinal(), batch.light));
                geometry.bind(primitive.indexOffset(), primitive.indexCount());
                if (following != null) {
                    following.bindVertices(1, followingFrameLayout(geometry.getVertexLayout()));
                }
                bgfx_set_instance_data_from_dynamic_vertex_buffer(
                        meshInstances.getHandle(),
                        batch.first[meshColorVariant(batch.emitter, primitive.sourceSurfaceOrdinal())],
                        batch.count);
                material.bind(sceneUniforms, elapsedSeconds, true, null, 0f, 0f);
                if (material.getProgramFamily() == ProgramFamily.WATER) {
                    sceneUniforms.bindNativeWaterLights(null);
                }
                bgfx_set_texture(
                        8,
                        sceneUniforms.getSceneSampler(8).getHandle(),
                        (softDepth == null ? textures.getWhite().getTexture() : softDepth).getHandle(),
                        SOFT_DEPTH_SAMPLER);
                actorLightingBuffer.bind();
                bgfx_set_state(BgfxSceneState.particleMesh(material, batch.emitter), 0);
                bgfx_submit(
                        view, programFor(material, depthMrt, following != null).getHandle(), 0, BGFX_DISCARD_ALL);
                performance.addDraw(
                        RenderFamily.PARTICLE,
                        renderView.reflected,
                        false,
                        batch.count,
                        (long) primitive.indexCount() / 3L * batch.count);
                draws++;
            }
        }
        return draws;
    }

    private float actorSortDistanceSquared(ParticleEmitter emitter, Vec3 cameraPos) {
        actorSortScratch.clear();
        if (!renderingContext.particles.addNativeActorSortBounds(emitter, actorSortScratch)) {
            return actorDistanceSquared(emitter, cameraPos);
        }
        var dx = (actorSortScratch.getMinX() + actorSortScratch.getMaxX()) * 0.5f - cameraPos.getX();
        var dy = (actorSortScratch.getMinY() + actorSortScratch.getMaxY()) * 0.5f - cameraPos.getY();
        var dz = (actorSortScratch.getMinZ() + actorSortScratch.getMaxZ()) * 0.5f - cameraPos.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private int assignRefractionPasses(Extent extent, FxView renderView, int capacity) {
        var width = extent.width();
        var height = extent.height();
        refractionLayers.begin(width, height);
        BgfxRefractionLayers.multiply(renderView.projection, renderView.view, refractionViewProjection);
        for (var prepared : activeFxEmitters) {
            var emitter = prepared.emitter;
            refractionBounds.clear();
            var placed = !refractionPassPerEffect
                    && emitter.computeWorldBounds(refractionBounds)
                    && BgfxRefractionLayers.project(
                            refractionViewProjection, refractionBounds, width, height, refractionScissor);
            refractionLayers.add(placed ? refractionScissor : null, emitter.getRefrUScale(), emitter.getRefrVScale());
        }
        return refractionLayers.computePasses(capacity);
    }

    private void prepareBillboards(Extent extent, RenderBucket bucket, BgfxTexture softDepth, FxView renderView) {
        var particles = renderingContext.particles;
        var frustum = renderView.frustum;
        var scanStarted = performance.start();
        beginGroups(activeBillboardGroups);
        var total = 0;
        for (var emitter : renderEmitters(particles, Kind.SPRITE)) {
            if (!renderingContext.inspection.acceptsEmitter(emitter) || !bucket.includes(emitter)) {
                continue;
            }
            if (!particles.isVisibleThisFrame(emitter, frustum) || !sceneZones.drawsEmitter(emitter)) {
                continue;
            }
            if (!renderView.reflected
                    && emitter.isProjectedParticleSizeInvisibleStable(
                            gpuVisibility.getPreparedViewProjection(),
                            extent.width(),
                            extent.height(),
                            minimumProjectedPixels)) {
                continue;
            }
            var source = emitter.getSpriteImage();
            var texture = source == null ? textures.getWhite() : particleTexture(source, emitter);
            var group = emitterBatchFor(
                    billboardBatchByEmitter,
                    billboardGroups,
                    activeBillboardGroups,
                    emitter,
                    texture,
                    emitter.getBlend(),
                    source != null,
                    softDepth != null && emitter.isUseSoftParticle(),
                    emitter.getSoftParticleFadeDist());
            var actorTransformCode = actorTransformCode(emitter);
            var payload = spritePayloads.computeIfAbsent(emitter, ignored -> new SpritePayload());
            var payloadRevision = emitter.getSpritePayloadRevision();
            if (payload.revision != payloadRevision
                    || Float.floatToIntBits(payload.actorTransformCode) != Float.floatToIntBits(actorTransformCode)) {
                var requiredFloats = Math.multiplyExact(emitter.getActiveParticleLimit(), BILLBOARD_INSTANCE_FLOATS);
                if (payload.data == null || payload.data.length < requiredFloats) {
                    payload.data = payload.data == null
                            ? new float[nextPowerOfTwo(requiredFloats)]
                            : Arrays.copyOf(payload.data, nextPowerOfTwo(requiredFloats));
                }
                payload.count = emitter.appendSpriteGpuInstances(
                        payload.data, 0, BILLBOARD_INSTANCE_FLOATS, actorTransformCode);
                payload.floatCount = Math.multiplyExact(payload.count, BILLBOARD_INSTANCE_FLOATS);
                payload.actorTransformCode = actorTransformCode;
                payload.revision = payloadRevision;
            }
            appendSpritePayload(group, payload);
            group.count = Math.addExact(group.count, payload.count);
            total = Math.addExact(total, payload.count);
        }
        performance.addPhase(CpuPhase.FX_BILLBOARD_SCAN, performance.getElapsed(scanStarted));
        if (total == 0) {
            return;
        }
        var packStarted = performance.start();
        var nextBillboardElements = Math.addExact(billboardFrameElements, total);
        ensureBillboardCapacity(nextBillboardElements);
        ensureBillboardScratch(Math.multiplyExact(total, BgfxSceneVertexLayouts.FX_BILLBOARD_INSTANCE.getStride()));
        billboardScratch.clear();
        billboardScratchFloats.clear();
        var first = billboardFrameElements;
        for (var group : activeBillboardGroups) {
            group.first = first;
            for (var payloadIndex = 0; payloadIndex < group.spritePayloadCount; payloadIndex++) {
                var payload = group.spritePayloads[payloadIndex];
                billboardScratchFloats.put(payload.data, 0, payload.floatCount);
            }
            first += group.count;
        }
        billboardScratch.position(0).limit(Math.multiplyExact(billboardScratchFloats.position(), Float.BYTES));
        performance.addPhase(CpuPhase.FX_BILLBOARD_PACK, performance.getElapsed(packStarted));
        var uploadStarted = performance.start();
        billboardInstances.update(billboardFrameElements, billboardScratch);
        billboardFrameElements = nextBillboardElements;
        flushActorTransforms();
        performance.addPhase(CpuPhase.FX_BILLBOARD_UPLOAD, performance.getElapsed(uploadStarted));
        for (var batch : activeBillboardGroups) {
            if (batch.count > 0) {
                activeFxEmitters.add(batch.prepared(PreparedFxKind.BILLBOARD));
            }
        }
    }

    private int submitBillboardEmitter(
            int view,
            boolean depthMrt,
            BgfxTexture softDepth,
            FxView renderView,
            FxScratchBatch batch,
            TextureRef retainedTexture,
            boolean retainBindings) {
        return submitBillboardEmitter(
                view, depthMrt, softDepth, renderView, batch, retainedTexture, retainBindings, null, 0);
    }

    private int submitBillboardEmitter(
            int view,
            boolean depthMrt,
            BgfxTexture softDepth,
            FxView renderView,
            FxScratchBatch batch,
            TextureRef retainedTexture,
            boolean retainBindings,
            BgfxProgram override,
            long state) {
        var submitStarted = performance.start();
        setFxBatch(batch);
        if (retainedTexture == null) {
            bgfx_set_vertex_buffer(0, quadVertices.getHandle(), 0, quadVertices.getCapacityElements());
            bindFxDepth(softDepth);
            bgfx_set_compute_dynamic_vertex_buffer(
                    ACTOR_TRANSFORM_STAGE, actorTransforms.getHandle(), BGFX_ACCESS_READ);
        }
        if (retainedTexture != batch.texture) {
            bgfx_set_texture(
                    0, sampler.getHandle(), batch.texture.getTexture().getHandle(), batch.texture.getSamplerFlags());
        }
        bgfx_set_instance_data_from_dynamic_vertex_buffer(billboardInstances.getHandle(), batch.first, batch.count);
        bgfx_set_state(override == null ? BgfxSceneState.particleFx(batch.blend) : state, 0);
        bgfx_submit(
                view,
                (override != null ? override : depthMrt ? billboardDepthMrtProgram : billboardProgram).getHandle(),
                0,
                retainBindings
                        ? BGFX_DISCARD_ALL & ~(BGFX_DISCARD_BINDINGS | BGFX_DISCARD_VERTEX_STREAMS)
                        : BGFX_DISCARD_ALL);
        performance.addDraw(RenderFamily.BILLBOARD, renderView.reflected, false, batch.count, (long) batch.count * 2L);
        performance.addPhase(CpuPhase.FX_BILLBOARD_SUBMIT, performance.getElapsed(submitStarted));
        return 1;
    }

    private void prepareBeams(Extent extent, RenderBucket bucket, BgfxTexture softDepth, FxView renderView) {
        var particles = renderingContext.particles;
        var cameraPos = renderView.position;
        var frustum = renderView.frustum;
        beginGroups(activeRibbonGroups);
        for (var emitter : renderEmitters(particles, Kind.BEAM)) {
            if (!renderingContext.inspection.acceptsEmitter(emitter)
                    || !bucket.includes(emitter)
                    || emitter.getSpriteImage() == null
                    || !particles.isVisibleThisFrame(emitter, frustum)
                    || !sceneZones.drawsEmitter(emitter)) {
                continue;
            }
            if (!renderView.reflected
                    && gpuVisibility.isProjectedSizeInvisible(particles, emitter, extent, minimumProjectedPixels)) {
                continue;
            }
            var texture = particleTexture(emitter.getSpriteImage(), emitter);
            var group = emitterBatchFor(
                    ribbonBatchByEmitter,
                    ribbonGroups,
                    activeRibbonGroups,
                    emitter,
                    texture,
                    emitter.getBlend(),
                    true,
                    softDepth != null && emitter.isUseSoftParticle(),
                    emitter.getSoftParticleFadeDist());
            ensureBeamControlCapacity(BeamRenderer.requiredControlFloats(emitter));
            var mostFloats = BeamRenderer.getMostRibbonFloats(emitter);
            for (var index = 0; index < emitter.getActiveParticleLimit(); index++) {
                if (!emitter.isActive(index)) {
                    continue;
                }
                ensureGroupFloats(
                        group, Math.addExact(group.floatData == null ? 0 : group.floatData.position(), mostFloats));
                var written = BeamRenderer.appendRibbon(
                        emitter, index, cameraPos, beamSpineScratch, beamControlScratch, group.floatData);
                if (written > 0) {
                    group.count = Math.addExact(group.count, written / RIBBON_FLOATS);
                }
            }
        }
        var totalFloats = 0;
        for (var group : activeRibbonGroups) {
            if (group.floatData != null) {
                totalFloats = Math.addExact(totalFloats, group.floatData.position());
            }
        }
        if (totalFloats == 0) {
            return;
        }
        var totalVertices = totalFloats / RIBBON_FLOATS;
        var nextRibbonElements = Math.addExact(ribbonFrameElements, totalVertices);
        ensureRibbonCapacity(nextRibbonElements);
        ensureRibbonScratch(Math.multiplyExact(totalFloats, Float.BYTES));
        ribbonScratch.clear();
        var firstVertex = ribbonFrameElements;
        for (var group : activeRibbonGroups) {
            group.first = firstVertex;
            var floatCount = group.floatData == null ? 0 : group.floatData.position();
            if (floatCount > 0) {
                group.data.position(0);
                group.data.limit(Math.multiplyExact(floatCount, Float.BYTES));
                ribbonScratch.put(group.data);
                group.data.clear();
            }
            firstVertex += group.count;
        }
        ribbonScratch.flip();
        ribbonVertices.update(ribbonFrameElements, ribbonScratch);
        ribbonFrameElements = nextRibbonElements;
        for (var batch : activeRibbonGroups) {
            if (batch.count > 0) {
                activeFxEmitters.add(batch.prepared(PreparedFxKind.BEAM));
            }
        }
    }

    private int submitBeamEmitter(
            int view, boolean depthMrt, BgfxTexture softDepth, FxView renderView, FxScratchBatch batch) {
        setFxBatch(batch);
        bgfx_set_texture(
                0, sampler.getHandle(), batch.texture.getTexture().getHandle(), batch.texture.getSamplerFlags());
        bindFxDepth(softDepth);
        bgfx_set_dynamic_vertex_buffer(0, ribbonVertices.getHandle(), batch.first, batch.count);
        bgfx_set_state(BgfxSceneState.particleFx(batch.blend), 0);
        bgfx_submit(view, (depthMrt ? ribbonDepthMrtProgram : ribbonProgram).getHandle(), 0, BGFX_DISCARD_ALL);
        performance.addDraw(RenderFamily.BEAM, renderView.reflected, false, 1, batch.count / 3L);
        return 1;
    }

    private float @Nullable [] meshLightOf(ParticleEmitter emitter, MeshBinding binding) {
        if (meshLighting == null) {
            return null;
        }
        var lit = false;
        for (var primitive : binding.geometry(0).getPrimitives()) {
            lit |= emitter.takesLight(primitive.sourceSurfaceOrdinal());
        }
        if (!lit) {
            return null;
        }
        meshLightScratch.clear();
        if (!renderingContext.particles.addNativeActorSortBounds(emitter, meshLightScratch)) {
            var place = emitter.getActorPos();
            meshLightScratch.add(place.getX() - .5f, place.getY() - .5f, place.getZ() - .5f);
            meshLightScratch.add(place.getX() + .5f, place.getY() + .5f, place.getZ() + .5f);
        }
        return meshLightFactor(meshLighting.computeAmbient(meshLightScratch));
    }

    private int appendMeshInstances(
            ParticleEmitter emitter,
            ByteBuffer target,
            int animationFrame,
            boolean frameBlend,
            int variant,
            boolean doubled) {
        var actorRotation = emitter.doesMeshUseActorRenderRotation() ? emitter.getActorRotation() : null;
        var count = 0;
        for (var index = 0; index < emitter.getActiveParticleLimit(); index++) {
            if (!emitter.isActive(index)) {
                continue;
            }
            if (animationFrame >= 0 && emitter.getMeshAnimationFrame(index) != animationFrame) {
                continue;
            }
            emitter.writeMeshWorldPos(index, meshPosition);
            var instance = target.position();
            putParticleMatrix(
                    target,
                    meshPosition[0],
                    meshPosition[1],
                    meshPosition[2],
                    actorRotation,
                    -emitter.getRoll(index),
                    -emitter.getYaw(index),
                    emitter.getPitch(index),
                    emitter.getScaleX(index),
                    emitter.getScaleY(index),
                    emitter.getScaleZ(index),
                    emitter.getMeshPrePivot());
            if (frameBlend) {
                target.putFloat(instance + 3 * Float.BYTES, emitter.getMeshAnimationBlend(index));
            }
            target.putFloat(meshInstanceColor(emitter.getR(index), variant, doubled));
            target.putFloat(meshInstanceColor(emitter.getG(index), variant, doubled));
            target.putFloat(meshInstanceColor(emitter.getB(index), variant, doubled));
            target.putFloat(meshInstanceAlpha(emitter.getA(index), variant));
            count++;
        }
        return count;
    }

    private void setParticleFrame(
            Extent extent,
            ParticleEmitter emitter,
            BgfxTexture softDepth,
            FxView renderView,
            BlendMode materialBlend,
            float @Nullable [] light) {
        Arrays.fill(sceneFrame, 0f);
        sceneFrame[60] = light == null ? 1f : light[0];
        sceneFrame[61] = light == null ? 1f : light[1];
        sceneFrame[62] = light == null ? 1f : light[2];
        sceneFrame[31] = textureMipBias;
        if (renderView.clipPlane != null) {
            sceneFrame[0] = renderView.clipPlane.getX();
            sceneFrame[1] = renderView.clipPlane.getY();
            sceneFrame[2] = renderView.clipPlane.getZ();
            sceneFrame[3] = renderView.clipPlane.getW();
            sceneFrame[4] = 1f;
        }
        sceneFrame[5] = device.getCapabilities().originBottomLeft() ? 1f : 0f;
        sceneFrame[6] = softDepth == null
                ? 1f
                : (float) extent.width() / softDepth.getDescriptor().width();
        sceneFrame[7] = softDepth == null
                ? 1f
                : (float) extent.height() / softDepth.getDescriptor().height();
        sceneFrame[8] = extent.width();
        sceneFrame[9] = extent.height();
        sceneFrame[48] =
                meshParticleBlendCode(emitter.isUseMeshBlendMode(), emitter.isBlackBackground(), emitter.getBlend());
        sceneFrame[49] = softDepth != null && emitter.isUseSoftParticle() ? 1f : 0f;
        sceneFrame[50] = emitter.getSoftParticleFadeDist();
        sceneFrame[51] = -1f;
        var alphaCutoff =
                meshParticleAlphaCutoff(emitter.isUseMeshBlendMode(), emitter.isAlphaTest(), emitter.getAlphaRef());
        sceneFrame[25] = alphaCutoff >= 0f ? 1f : 0f;
        sceneFrame[26] = Math.max(alphaCutoff, 0f);
        if (frameFog != null && !emitter.getVisibility().disableFogging()) {
            sceneFrame[13] = particleFogMode(true, false, localFogActive);
            if (materialBlend != null) {
                putFogTarget(sceneFrame, 40, materialBlend, frameFog.color());
            } else {
                putParticleFogTarget(sceneFrame, 40, emitter.getBlend(), emitter.isBlackBackground(), frameFog.color());
            }
            sceneFrame[44] = frameFog.start();
            sceneFrame[45] = frameFog.end();
        }
        ShaderHeightCut.put(
                renderingContext.camera.getShaderHeightCut(),
                EFFECT_SURFACE,
                sceneFrame,
                BgfxSceneUniforms.FRAME_HEIGHT_CUT);
        sceneUniforms.bindFrame(sceneFrame);
        sceneUniforms.bindVertexFrame(sceneFrame, false, renderView.position);
    }

    private void setFxCamera(Vec3 right, Vec3 up, Vec3 camera, Vec4 clipPlane) {
        Arrays.fill(fxParams, 0f);
        fxParams[30] = textureMipBias;
        fxParams[0] = right.getX();
        fxParams[1] = right.getY();
        fxParams[2] = right.getZ();
        fxParams[4] = up.getX();
        fxParams[5] = up.getY();
        fxParams[6] = up.getZ();
        fxParams[8] = camera.getX();
        fxParams[9] = camera.getY();
        fxParams[10] = camera.getZ();
        if (clipPlane != null) {
            fxParams[3] = 1f;
            fxParams[20] = clipPlane.getX();
            fxParams[21] = clipPlane.getY();
            fxParams[22] = clipPlane.getZ();
            fxParams[23] = clipPlane.getW();
        }
        ShaderHeightCut.put(renderingContext.camera.getShaderHeightCut(), EFFECT_SURFACE, fxParams, PARAM_HEIGHT_CUT);
    }

    private void setFxDepthFrame(Extent extent, BgfxTexture softDepth) {
        fxParams[12] = softDepth == null
                ? 1f
                : (float) extent.width() / softDepth.getDescriptor().width();
        fxParams[13] = softDepth == null
                ? 1f
                : (float) extent.height() / softDepth.getDescriptor().height();
        fxParams[14] = extent.width();
        fxParams[15] = extent.height();
        fxParams[18] = device.getCapabilities().originBottomLeft() ? 1f : 0f;
        if (frameFog != null) {
            fxParams[27] = 1f;
            fxParams[28] = frameFog.start();
            fxParams[29] = frameFog.end();
        }
    }

    private void setFxBatch(FxScratchBatch batch) {
        fxParams[7] = batch.hasTexture ? 1f : 0f;
        fxParams[11] = batch.blend;
        fxParams[19] = batch.alphaCutoff;
        fxParams[16] = batch.softParticle ? 1f : 0f;
        fxParams[17] = batch.softParticleFadeDist;
        fxParams[27] = particleFogMode(frameFog != null, batch.fogDisabled, localFogActive);
        if (frameFog != null) {
            putBillboardFogTarget(fxParams, 24, batch.blend, frameFog.color());
        }
        bgfx_set_uniform(params.getHandle(), fxParams, PARAM_VECTORS);
    }

    private FxView mainView(RenderBucket bucket) {
        var camera = renderingContext.camera;
        var right = camera.getRightDir();
        return new FxView(
                "scene-fx-" + bucket.viewSuffix,
                BgfxViewAllocator.Domain.RENDER,
                firstSixteenFloats(camera.getViewMatrix()),
                BgfxMatrices.toBackendProjection(
                        camera.getProjectionMatrix(), device.getCapabilities().homogeneousDepth(), projectionBuffer),
                camera.getPosition(),
                right,
                billboardCameraUp(camera, right),
                camera.getFrustum(),
                null,
                false);
    }

    private void bindFxDepth(BgfxTexture softDepth) {
        bgfx_set_texture(
                1,
                depthSampler.getHandle(),
                (softDepth == null ? textures.getWhite().getTexture() : softDepth).getHandle(),
                SOFT_DEPTH_SAMPLER);
    }

    private void ensureBillboardScratch(int neededBytes) {
        if (billboardScratch == null || billboardScratch.capacity() < neededBytes) {
            billboardScratch = allocateBuffer(nextPowerOfTwo(neededBytes));
            billboardScratchFloats = billboardScratch.asFloatBuffer();
        }
    }

    private void ensureMeshScratch(int neededBytes) {
        if (meshScratch == null || meshScratch.capacity() < neededBytes) {
            meshScratch = allocateBuffer(nextPowerOfTwo(neededBytes));
        }
    }

    private void ensureMeshCapacity(int needed) {
        if (meshInstances != null && needed <= meshCapacity) {
            return;
        }
        var replacementCapacity = nextPowerOfTwo(needed);
        if (meshInstances != null) {
            meshInstances.close();
        }
        meshInstances = null;
        meshCapacity = 0;
        var replacement = BgfxBuffer.dynamicVertex(
                device.getResources(), "fx/mesh-instances", replacementCapacity, BgfxSceneVertexLayouts.INSTANCE, 0);
        meshInstances = replacement;
        meshCapacity = replacementCapacity;
    }

    private MeshFrameBatch meshFrameBatch(int index) {
        while (meshFrameBatches.size() <= index) {
            meshFrameBatches.add(new MeshFrameBatch());
        }
        return meshFrameBatches.get(index);
    }

    private MeshEmitterBatch meshEmitterBatch(int index) {
        while (meshEmitterBatches.size() <= index) {
            meshEmitterBatches.add(new MeshEmitterBatch());
        }
        return meshEmitterBatches.get(index);
    }

    private void ensureRibbonScratch(int neededBytes) {
        if (ribbonScratch == null || ribbonScratch.capacity() < neededBytes) {
            ribbonScratch = allocateBuffer(nextPowerOfTwo(neededBytes));
        }
    }

    private void ensureBeamControlCapacity(int neededFloats) {
        if (beamControlScratch.length < neededFloats) {
            beamControlScratch = Arrays.copyOf(beamControlScratch, nextPowerOfTwo(neededFloats));
        }
    }

    private void ensureBillboardCapacity(int needed) {
        if (billboardInstances != null && needed <= billboardCapacity) {
            return;
        }
        var replacementCapacity = nextPowerOfTwo(needed);
        if (billboardInstances != null) {
            billboardInstances.close();
        }
        billboardInstances = null;
        billboardCapacity = 0;
        var replacement = BgfxBuffer.dynamicVertex(
                device.getResources(),
                "fx/billboard-instances",
                replacementCapacity,
                BgfxSceneVertexLayouts.FX_BILLBOARD_INSTANCE,
                0);
        billboardInstances = replacement;
        billboardCapacity = replacementCapacity;
    }

    private void ensureRibbonCapacity(int needed) {
        if (ribbonVertices != null && needed <= ribbonCapacity) {
            return;
        }
        var replacementCapacity = nextPowerOfTwo(needed);
        if (ribbonVertices != null) {
            ribbonVertices.close();
        }
        ribbonVertices = null;
        ribbonCapacity = 0;
        var replacement = BgfxBuffer.dynamicVertex(
                device.getResources(), "fx/ribbon-vertices", replacementCapacity, BgfxSceneVertexLayouts.FX_RIBBON, 0);
        ribbonVertices = replacement;
        ribbonCapacity = replacementCapacity;
    }

    private BgfxProgram programFor(BgfxSceneMaterial material, boolean depthMrt, boolean frameBlend) {
        return (frameBlend ? frameBlendPrograms : meshPrograms).program(material.getProgramFamily(), depthMrt);
    }

    private BgfxVertexLayout followingFrameLayout(BgfxVertexLayoutSpec frameLayout) {
        var layout = followingFrameLayouts.get(frameLayout);
        if (layout == null) {
            layout = BgfxVertexLayout.create(
                    device.getResources(), "fx/following-frame", BgfxSceneVertexLayouts.followingFrame(frameLayout));
            followingFrameLayouts.put(frameLayout, layout);
        }
        return layout;
    }

    private float actorTransformCode(ParticleEmitter emitter) {
        var existing = actorTransformIndices.get(emitter);
        var index = existing == null ? allocateActorTransform(emitter) : existing;
        if (existing != null && writeActorTransform(actorTransformShadow, index * ACTOR_TRANSFORM_BYTES, emitter)) {
            firstDirtyActorTransform = Math.min(firstDirtyActorTransform, index);
            lastDirtyActorTransform = Math.max(lastDirtyActorTransform, index);
        }
        var oneBased = (float) (index + 1);
        return emitter.doesMeshUseActorRenderRotation() ? oneBased : -oneBased;
    }

    private int allocateActorTransform(ParticleEmitter emitter) {
        var recycled = freeActorTransformIndices.pollFirst();
        var index = recycled == null ? actorTransformHighWater++ : recycled;
        if (index >= actorTransformCapacity) {
            if (recycled == null) {
                actorTransformHighWater--;
            } else {
                freeActorTransformIndices.addFirst(recycled);
            }
            throw new IllegalStateException("FX actor-transform table exhausted at " + actorTransformCapacity
                    + " emitters. Increase graphics.tuning.fx_actor_transform_capacity in cruma.conf");
        }
        writeActorTransform(actorTransformShadow, Math.multiplyExact(index, ACTOR_TRANSFORM_BYTES), emitter);
        actorTransformIndices.put(emitter, index);
        firstDirtyActorTransform = Math.min(firstDirtyActorTransform, index);
        lastDirtyActorTransform = Math.max(lastDirtyActorTransform, index);
        return index;
    }

    private void flushActorTransforms() {
        if (lastDirtyActorTransform < firstDirtyActorTransform) {
            return;
        }
        var firstByte = Math.multiplyExact(firstDirtyActorTransform, ACTOR_TRANSFORM_BYTES);
        var lastByte = Math.multiplyExact(lastDirtyActorTransform + 1, ACTOR_TRANSFORM_BYTES);
        var upload = actorTransformShadow.duplicate().order(ByteOrder.nativeOrder());
        upload.position(firstByte).limit(lastByte);
        actorTransforms.update(firstDirtyActorTransform * 3, upload.slice().order(ByteOrder.nativeOrder()));
        firstDirtyActorTransform = Integer.MAX_VALUE;
        lastDirtyActorTransform = -1;
    }

    private void releaseActorTransform(ParticleEmitter emitter) {
        var index = actorTransformIndices.remove(emitter);
        if (index != null) {
            freeActorTransformIndices.addLast(index);
        }
    }

    private TextureRef particleTexture(Texture source, ParticleEmitter emitter) {
        var binding = particleTextures.get(source);
        if (binding != null) {
            return binding.texture;
        }
        var uploaded = textures.load(source, "fx/" + sanitize(emitter.getDebugName()));
        var created = new ParticleTexture(uploaded);
        created.retain(BgfxSceneRenderer.GLOBAL_RESIDENCY_OWNER);
        particleTextures.put(source, created);
        residencyRevision++;
        return uploaded;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("BGFX FX renderer is closed");
        }
    }

    private static List<ParticleEmitter> renderEmitters(ParticleSystem particles, Kind kind) {
        return FULL_RENDER_REGISTRY ? particles.getEmitters(kind) : particles.getFrameEmitters(kind);
    }

    private static float finiteDistance(float value) {
        return Float.isFinite(value) && value >= 0f ? value : Float.POSITIVE_INFINITY;
    }

    private static float actorDistanceSquared(ParticleEmitter emitter, Vec3 cameraPos) {
        var actor = emitter.getActorPos();
        var dx = actor.getX() - cameraPos.getX();
        var dy = actor.getY() - cameraPos.getY();
        var dz = actor.getZ() - cameraPos.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static int compareNativeRenderOrder(
            RenderBucket bucket, PreparedFxEmitter leftPrepared, PreparedFxEmitter rightPrepared) {
        var left = leftPrepared.emitter;
        var right = rightPrepared.emitter;
        if (bucket == RenderBucket.ALL) {
            var byBucket = Boolean.compare(left.isDeferredAfterWater(), right.isDeferredAfterWater());
            if (byBucket != 0) {
                return byBucket;
            }
        }
        if (bucket == RenderBucket.IMMEDIATE || bucket == RenderBucket.ALL && !left.isDeferredAfterWater()) {
            return compareSourceActorOrder(left, right);
        }
        var byPriority = Integer.compare(right.getTranslucentRenderPriority(), left.getTranslucentRenderPriority());
        if (byPriority != 0) {
            return byPriority;
        }
        var byDistance = Float.compare(rightPrepared.sortDistanceSquared, leftPrepared.sortDistanceSquared);
        return byDistance != 0 ? byDistance : compareSourceActorOrder(left, right);
    }

    private static int compareSourceActorOrder(ParticleEmitter left, ParticleEmitter right) {
        var byPackage = left.getRenderActorPackage().compareTo(right.getRenderActorPackage());
        if (byPackage != 0) {
            return byPackage;
        }
        var byActor = Integer.compare(left.getRenderActorOrder(), right.getRenderActorOrder());
        if (byActor != 0) {
            return byActor;
        }
        var byName = left.getRenderActorName().compareTo(right.getRenderActorName());
        return byName != 0 ? byName : Integer.compare(left.getRenderChildOrder(), right.getRenderChildOrder());
    }

    private static String actorPackage(String actorName) {
        var normalized = normalizedActorName(actorName);
        var separator = normalized.lastIndexOf('.');
        return separator < 0 ? normalized : normalized.substring(0, separator);
    }

    private static String normalizedActorName(String actorName) {
        return actorName == null || actorName.isBlank() ? "synthetic" : actorName;
    }

    private static void putBillboardFogTarget(float[] target, int offset, int blend, Vec3 sceneColor) {
        if (blend == Billboard.BLEND_MODULATE) {
            put3(target, offset, 127f / 255f, 127f / 255f, 127f / 255f);
        } else if (blend == Billboard.BLEND_ADDITIVE
                || blend == Billboard.BLEND_SCREEN
                || blend == Billboard.BLEND_DARKEN) {
            put3(target, offset, 0f, 0f, 0f);
        } else {
            put3(target, offset, sceneColor.getX(), sceneColor.getY(), sceneColor.getZ());
        }
    }

    private static void putParticleFogTarget(
            float[] target, int offset, int drawStyle, boolean blackBackground, Vec3 sceneColor) {
        if (drawStyle == 2) {
            put3(target, offset, 127f / 255f, 127f / 255f, 127f / 255f);
        } else if (drawStyle == 3 || drawStyle == 5 || drawStyle == 6 || blackBackground && drawStyle == 0) {
            put3(target, offset, 0f, 0f, 0f);
        } else {
            put3(target, offset, sceneColor.getX(), sceneColor.getY(), sceneColor.getZ());
        }
    }

    private static void putFogTarget(float[] target, int offset, BlendMode blend, Vec3 sceneColor) {
        switch (blend) {
            case MODULATE -> put3(target, offset, 127f / 255f, 127f / 255f, 127f / 255f);
            case ADDITIVE, SCREEN, BRIGHTEN, DARKEN -> put3(target, offset, 0f, 0f, 0f);
            default -> put3(target, offset, sceneColor.getX(), sceneColor.getY(), sceneColor.getZ());
        }
    }

    private static void put3(float[] target, int offset, float x, float y, float z) {
        target[offset] = x;
        target[offset + 1] = y;
        target[offset + 2] = z;
    }

    private static void beginGroups(List<FxScratchBatch> active) {
        for (var group : active) {
            group.active = false;
            group.first = 0;
            group.count = 0;
            if (group.data != null) {
                group.data.clear();
            }
            if (group.floatData != null) {
                group.floatData.clear();
            }
            if (group.spritePayloadCount > 0) {
                Arrays.fill(group.spritePayloads, 0, group.spritePayloadCount, null);
            }
            group.spritePayloadCount = 0;
        }
        active.clear();
    }

    private static FxScratchBatch emitterBatchFor(
            IdentityHashMap<ParticleEmitter, FxScratchBatch> byEmitter,
            List<FxScratchBatch> all,
            List<FxScratchBatch> active,
            ParticleEmitter emitter,
            TextureRef texture,
            int blend,
            boolean hasTexture,
            boolean softParticle,
            float softParticleFadeDist) {
        var result = byEmitter.get(emitter);
        if (result == null) {
            result = new FxScratchBatch();
            result.emitter = emitter;
            byEmitter.put(emitter, result);
            all.add(result);
        }
        result.texture = texture;
        result.blend = blend;
        result.hasTexture = hasTexture;
        result.softParticle = softParticle;
        result.softParticleFadeDist = softParticleFadeDist;
        result.fogDisabled = emitter.getVisibility().disableFogging();
        result.alphaCutoff = spriteAlphaCutoff(emitter.isAlphaTest(), emitter.getAlphaRef());
        if (!result.active) {
            result.active = true;
            active.add(result);
        }
        return result;
    }

    private static void releaseScratchBatch(
            IdentityHashMap<ParticleEmitter, FxScratchBatch> byEmitter,
            List<FxScratchBatch> all,
            List<FxScratchBatch> active,
            ParticleEmitter emitter) {
        var batch = byEmitter.remove(emitter);
        if (batch == null) {
            return;
        }
        active.remove(batch);
        all.remove(batch);
        batch.emitter = null;
        batch.active = false;
        if (batch.prepared != null) {
            batch.prepared.emitter = null;
        }
    }

    private static void ensureGroupBytes(FxScratchBatch group, int neededBytes) {
        if (group.data != null && group.data.capacity() >= neededBytes) {
            return;
        }
        var replacement = allocateBuffer(nextPowerOfTwo(neededBytes));
        if (group.data != null) {
            group.data.flip();
            replacement.put(group.data);
        }
        group.data = replacement;
    }

    private static void ensureGroupFloats(FxScratchBatch group, int neededFloats) {
        if (group.floatData != null && group.floatData.capacity() >= neededFloats) {
            return;
        }
        var replacement = allocateBuffer(Math.multiplyExact(nextPowerOfTwo(neededFloats), Float.BYTES));
        var replacementFloats = replacement.asFloatBuffer();
        if (group.floatData != null) {
            group.floatData.flip();
            replacementFloats.put(group.floatData);
        }
        group.data = replacement;
        group.floatData = replacementFloats;
    }

    private static void appendSpritePayload(FxScratchBatch group, SpritePayload payload) {
        if (group.spritePayloads == null) {
            group.spritePayloads = new SpritePayload[8];
        } else if (group.spritePayloadCount == group.spritePayloads.length) {
            group.spritePayloads =
                    Arrays.copyOf(group.spritePayloads, Math.multiplyExact(group.spritePayloads.length, 2));
        }
        group.spritePayloads[group.spritePayloadCount++] = payload;
    }

    private static int followingFrame(MeshBinding binding, ParticleEmitter emitter, int frame) {
        if (binding.frameCount() == 1) {
            return -1;
        }
        var following = emitter.getMeshAnimationFollowingFrame(frame);
        if (following == frame
                || following < 0
                || following >= binding.frameCount()
                || binding.geometry(following).getVertexCount()
                        != binding.geometry(frame).getVertexCount()) {
            return -1;
        }
        return following;
    }

    private static ByteBuffer unitQuad() {
        var vertices = new float[] {
            -0.5f, -0.5f, 0f, 0f, 1f, 0.5f, -0.5f, 0f, 1f, 1f, 0.5f, 0.5f, 0f, 1f, 0f, -0.5f, -0.5f, 0f, 0f, 1f, 0.5f,
            0.5f, 0f, 1f, 0f, -0.5f, 0.5f, 0f, 0f, 0f
        };
        var result = ByteBuffer.allocateDirect(vertices.length * Float.BYTES).order(ByteOrder.nativeOrder());
        result.asFloatBuffer().put(vertices);
        return result;
    }

    private static boolean writeActorFloat(ByteBuffer target, int offset, float value) {
        var bits = Float.floatToRawIntBits(value);
        if (target.getInt(offset) == bits) {
            return false;
        }
        target.putInt(offset, bits);
        return true;
    }

    private static ByteBuffer allocateBuffer(int bytes) {
        return ByteBuffer.allocateDirect(Math.max(1, bytes)).order(ByteOrder.nativeOrder());
    }

    private static int capacityOf(ByteBuffer buffer) {
        return buffer == null ? 0 : buffer.capacity();
    }

    private static int capacityOf(float[] values) {
        return values == null ? 0 : values.length;
    }

    private static Vec3 cameraUp(Vec3 right, Vec3 forward) {
        var x = right.getY() * forward.getZ() - right.getZ() * forward.getY();
        var y = right.getZ() * forward.getX() - right.getX() * forward.getZ();
        var z = right.getX() * forward.getY() - right.getY() * forward.getX();
        var length = (float) Math.sqrt(x * x + y * y + z * z);
        return length == 0f ? new Vec3(0f, 0f, 1f) : new Vec3(x / length, y / length, z / length);
    }

    private static FloatBuffer firstSixteenFloats(FloatBuffer source) {
        var duplicate = source.duplicate();
        duplicate.position(0);
        duplicate.limit(16);
        return duplicate;
    }

    private static int nextPowerOfTwo(int value) {
        return value <= 1 ? 1 : Integer.highestOneBit(value - 1) << 1;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unnamed";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    private static RuntimeException closeAndMerge(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (Exception e) {
            var runtime = e instanceof RuntimeException r ? r : new IllegalStateException(e);
            if (failure == null) {
                return runtime;
            }
            failure.addSuppressed(runtime);
        }
        return failure;
    }

    private static void closeAfterFailure(AutoCloseable resource, Throwable failure) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception e) {
            failure.addSuppressed(e);
        }
    }

    private static final class FxScratchBatch {
        private ParticleEmitter emitter;
        private TextureRef texture;
        private int blend;
        private boolean hasTexture;
        private boolean softParticle;
        private float softParticleFadeDist;
        private boolean fogDisabled;
        private float alphaCutoff = -1f;
        private ByteBuffer data;
        private FloatBuffer floatData;
        private SpritePayload[] spritePayloads;
        private int spritePayloadCount;
        private int first;
        private int count;
        private boolean active;
        private PreparedFxEmitter prepared;

        private PreparedFxEmitter prepared(PreparedFxKind kind) {
            if (prepared == null) {
                prepared = new PreparedFxEmitter();
            }
            prepared.emitter = emitter;
            prepared.kind = kind;
            prepared.mesh = null;
            prepared.scratch = this;
            return prepared;
        }
    }

    private enum PreparedFxKind {
        MESH,
        BILLBOARD,
        BEAM
    }

    private static final class PreparedFxEmitter {
        private float sortDistanceSquared;
        private ParticleEmitter emitter;
        private PreparedFxKind kind;
        private MeshEmitterBatch mesh;
        private FxScratchBatch scratch;
    }

    private static final class SpritePayload {
        private float[] data;
        private int floatCount;
        private int count;
        private float actorTransformCode;
        private long revision = Long.MIN_VALUE;
    }

    private static final class MeshFrameBatch {
        private ParticleEmitter emitter;
        private MeshBinding binding;
        private int animationFrame;
        private int followingFrame = -1;
        private final int[] first = new int[MESH_COLOR_VARIANTS];
        private int variants;
        private float @Nullable [] light;
        private int count;
        private boolean selected;

        private void clear() {
            emitter = null;
            binding = null;
            animationFrame = 0;
            followingFrame = -1;
            Arrays.fill(first, 0);
            variants = 0;
            light = null;
            count = 0;
            selected = false;
        }
    }

    private static final class MeshEmitterBatch {
        private ParticleEmitter emitter;
        private int firstFrameBatch;
        private int frameBatchCount;
        private boolean selected;
        private PreparedFxEmitter prepared;

        private PreparedFxEmitter prepared() {
            if (prepared == null) {
                prepared = new PreparedFxEmitter();
            }
            prepared.emitter = emitter;
            prepared.kind = PreparedFxKind.MESH;
            prepared.mesh = this;
            prepared.scratch = null;
            return prepared;
        }

        private void clear() {
            if (prepared != null) {
                prepared.emitter = null;
            }
            emitter = null;
            firstFrameBatch = 0;
            frameBatchCount = 0;
            selected = false;
        }
    }

    private static final class ParticleTexture {
        private final TextureRef texture;
        private final Map<String, Integer> referencesByOwner = new HashMap<>();
        private int references;

        private ParticleTexture(TextureRef texture) {
            this.texture = texture;
        }

        private void retain(String owner) {
            references = Math.addExact(references, 1);
            referencesByOwner.merge(owner, 1, (left, right) -> Math.addExact(left, right));
        }

        private boolean release(String owner) {
            var owned = referencesByOwner.get(owner);
            if (owned == null || owned < 1) {
                throw new IllegalArgumentException("particle texture belongs to another residency owner");
            }
            if (owned == 1) {
                referencesByOwner.remove(owner);
            } else {
                referencesByOwner.put(owner, owned - 1);
            }
            references--;
            if (references < 0) {
                throw new IllegalStateException("particle texture reference count underflow");
            }
            return references == 0;
        }
    }

    private static final class MeshBinding implements AutoCloseable {
        private final String owner;
        private final BgfxSceneGeometry geometry;
        private final AnimatedMeshAsset animatedAsset;
        private final MeshPickData pickData;

        private MeshBinding(String owner, BgfxSceneGeometry geometry, MeshPickData pickData) {
            this.owner = owner;
            this.geometry = geometry;
            this.animatedAsset = null;
            this.pickData = pickData;
        }

        private MeshBinding(String owner, AnimatedMeshAsset animatedAsset) {
            this.owner = owner;
            this.geometry = null;
            this.animatedAsset = animatedAsset;
            this.pickData = null;
        }

        @Override
        public void close() {
            if (geometry != null) {
                geometry.close();
            }
        }

        private int frameCount() {
            return animatedAsset == null ? 1 : animatedAsset.frames.size();
        }

        private BgfxSceneGeometry geometry(int frame) {
            return animatedAsset == null ? geometry : animatedAsset.frames.get(frame);
        }

        private List<BgfxSceneGeometry> geometries() {
            return animatedAsset == null ? List.of(geometry) : animatedAsset.frames;
        }

        private List<MeshPickData> pickFrames() {
            return animatedAsset == null ? List.of(pickData) : animatedAsset.pickFrames;
        }
    }

    private static final class AnimatedMeshAsset implements AutoCloseable {
        private final EntityMesh key;
        private final List<BgfxSceneGeometry> frames;
        private final List<MeshPickData> pickFrames;
        private int references;
        private boolean closed;

        private AnimatedMeshAsset(EntityMesh key, List<BgfxSceneGeometry> frames, List<MeshPickData> pickFrames) {
            this.key = key;
            this.frames = List.copyOf(frames);
            this.pickFrames = List.copyOf(pickFrames);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            RuntimeException failure = null;
            for (var index = frames.size() - 1; index >= 0; index--) {
                failure = closeAndMerge(frames.get(index), failure);
            }
            if (failure != null) {
                throw failure;
            }
        }

        private void retain() {
            references = Math.addExact(references, 1);
        }

        private boolean release() {
            references--;
            if (references < 0) {
                throw new IllegalStateException("animated particle mesh reference count underflow");
            }
            return references == 0;
        }
    }

    record DrawCounts(int meshes, int billboards, int beams) {}

    record RefractionCounts(int effects, int drawn, int passes, boolean merged) {
        static final RefractionCounts NONE = new RefractionCounts(0, 0, 0, false);
    }

    record OwnedResidency(long retainedCpuBytes, long geometryGpuBytes) {
        private static final OwnedResidency EMPTY = new OwnedResidency(0L, 0L);

        OwnedResidency {
            if (retainedCpuBytes < 0L || geometryGpuBytes < 0L) {
                throw new IllegalArgumentException("FX residency bytes cannot be negative");
            }
        }
    }

    private static void addTextureOwner(Map<TextureRef, Set<String>> target, TextureRef texture, String owner) {
        target.computeIfAbsent(texture, ignored -> new HashSet<>()).add(owner);
    }

    private static void requireOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("FX residency owner cannot be null or blank");
        }
    }
}
