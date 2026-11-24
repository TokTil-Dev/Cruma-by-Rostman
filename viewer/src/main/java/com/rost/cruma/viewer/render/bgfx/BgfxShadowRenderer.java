package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_DEPTH;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_SAMPLER;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_VEC4;
import static org.lwjgl.bgfx.BGFX.BGFX_VIEW_MODE_SEQUENTIAL;
import static org.lwjgl.bgfx.BGFX.bgfx_set_state;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_clear;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_mode;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_name;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_transform;
import static org.lwjgl.bgfx.BGFX.bgfx_submit;
import static org.lwjgl.bgfx.BGFX.bgfx_touch;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.world.Entity.BlendMode;
import com.rost.cruma.core.world.Entity.SurfaceType;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.api.RenderState;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneMaterial.ProgramFamily;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class BgfxShadowRenderer implements AutoCloseable {
    static final int CASCADE_COUNT = 4;

    static final float SUN_WIDTH = .00925f;

    static final int SAMPLE_FLAGS =
            BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP | BGFX_SAMPLER_MIN_POINT | BGFX_SAMPLER_MAG_POINT;

    private static final float[] CASCADE_SPLITS = {2_500f, 10_000f, 50_000f, 320_000f};

    private static final float BIAS_TEXELS = 6f;

    private static final float CAMERA_REBUILD_DISTANCE = CASCADE_SPLITS[0] * 0.5f;
    private static final float CASTER_PADDING = 32_000f;
    private static final float RECEIVER_PADDING = 2_000f;
    private static final int MIN_RESOLUTION = 512;
    private static final int PREFERRED_RESOLUTION = 4_096;
    private static final int BUFFER_COUNT = 2;
    private static final int COMPLETE_READY_MASK = (1 << CASCADE_COUNT) - 1;
    private static final Logger log = LogManager.getLogger(BgfxShadowRenderer.class);

    private final BgfxDevice device;
    private final BgfxResourceRegistry resources;
    private final int resolution;
    private final List<BgfxTexture> depthTextures = new ArrayList<>(CASCADE_COUNT * BUFFER_COUNT);
    private final List<BgfxFramebuffer> framebuffers = new ArrayList<>(CASCADE_COUNT * BUFFER_COUNT);
    private final BgfxShader entityVertex;
    private final BgfxShader terrainVertex;
    private final BgfxShader depthFragment;
    private final BgfxProgram entityDepthProgram;
    private final BgfxProgram entityMaskedProgram;
    private final BgfxProgram terrainDepthProgram;
    private final BgfxUniform shadowMaterial;
    private final BgfxUniform shadowDiffuse;
    private final BgfxUniform shadowOpacity;
    private final float[] materialData = new float[16];
    private final float[][] lightViews = new float[CASCADE_COUNT][16];
    private final float[][] lightProjections = new float[CASCADE_COUNT][16];
    private final float[] cascadeRadii = new float[CASCADE_COUNT];
    private final float[] cascadeNearDepths = new float[CASCADE_COUNT];
    private final float[] cascadeFarDepths = new float[CASCADE_COUNT];
    private final float[] lightViewProjections = new float[CASCADE_COUNT * 16];
    private final ArrayList<BgfxSceneObject> casterCandidates = new ArrayList<>();
    private final float[] cachedCameraPosition = new float[3];
    private final float[] cachedSunDirection = new float[3];
    private final boolean[] cascadeStarted = new boolean[CASCADE_COUNT];
    private long cachedSceneRevision = Long.MIN_VALUE;
    private long cachedMovementRevision = Long.MIN_VALUE;
    private int nextCascade;
    private int nextObjectIndex;
    private int nextPrimitiveOrdinal;
    private int readyMask;
    private boolean cacheValid;
    private boolean buildInProgress;
    private int activeBuffer = -1;
    private int buildBuffer;
    private boolean incompleteBuildLogged;
    private long buildStartedNanos;
    private long publicationGeneration;
    private Snapshot snapshot = Snapshot.disabled();
    private boolean closed;

    BgfxShadowRenderer(BgfxDevice device) {
        this.device = device;
        resources = device.getResources();
        resolution = Math.min(PREFERRED_RESOLUTION, device.getCapabilities().maxTextureSize());
        if (!isSupported(device.getCapabilities()) || resolution < MIN_RESOLUTION) {
            throw new UnsupportedOperationException(
                    "custom cascaded shadows require a sampled depth-stencil target of at least " + MIN_RESOLUTION
                            + " pixels");
        }
        BgfxShader createdEntityVertex = null;
        BgfxShader createdTerrainVertex = null;
        BgfxShader createdDepthFragment = null;
        BgfxProgram createdEntityDepth = null;
        BgfxProgram createdEntityMasked = null;
        BgfxProgram createdTerrainDepth = null;
        BgfxUniform createdMaterial = null;
        BgfxUniform createdDiffuse = null;
        BgfxUniform createdOpacity = null;
        try {
            for (var buffer = 0; buffer < BUFFER_COUNT; buffer++) {
                for (var cascade = 0; cascade < CASCADE_COUNT; cascade++) {
                    var texture = BgfxTexture.create2D(
                            resources,
                            "custom-lighting/cascade-" + buffer + '-' + cascade,
                            resolution,
                            resolution,
                            false,
                            1,
                            device.getCapabilities().depthStencilFormat(),
                            BGFX_TEXTURE_RT,
                            null);
                    depthTextures.add(texture);
                    framebuffers.add(BgfxFramebuffer.create(
                            resources, "custom-lighting/cascade-fb-" + buffer + '-' + cascade, List.of(texture)));
                }
            }
            createdEntityVertex = BgfxShader.create(
                    resources,
                    "custom-lighting/shadow-entity-vs",
                    BgfxShaderResources.load(device.getCapabilities(), "vs_shadow_entity"));
            createdTerrainVertex = BgfxShader.create(
                    resources,
                    "custom-lighting/shadow-terrain-vs",
                    BgfxShaderResources.load(device.getCapabilities(), "vs_shadow_terrain"));
            createdDepthFragment = BgfxShader.create(
                    resources,
                    "custom-lighting/shadow-depth-fs",
                    BgfxShaderResources.load(device.getCapabilities(), "fs_shadow_depth"));
            createdEntityDepth = BgfxProgram.graphics(
                    resources, "custom-lighting/shadow-entity-depth", createdEntityVertex, createdDepthFragment);
            createdEntityMasked = BgfxProgram.graphics(
                    resources,
                    "custom-lighting/shadow-entity-masked",
                    createdEntityVertex,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_shadow_masked"));
            createdTerrainDepth = BgfxProgram.graphics(
                    resources, "custom-lighting/shadow-terrain-depth", createdTerrainVertex, createdDepthFragment);
            createdMaterial = BgfxUniform.create(resources, "u_shadowMaterial", BGFX_UNIFORM_TYPE_VEC4, 4);
            createdDiffuse = BgfxUniform.create(resources, "s_shadowDiffuse", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            createdOpacity = BgfxUniform.create(resources, "s_shadowOpacity", BGFX_UNIFORM_TYPE_SAMPLER, 1);
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(createdOpacity, failure);
            closeAfterFailure(createdDiffuse, failure);
            closeAfterFailure(createdMaterial, failure);
            closeAfterFailure(createdTerrainDepth, failure);
            closeAfterFailure(createdEntityMasked, failure);
            closeAfterFailure(createdEntityDepth, failure);
            closeAfterFailure(createdDepthFragment, failure);
            closeAfterFailure(createdTerrainVertex, failure);
            closeAfterFailure(createdEntityVertex, failure);
            closeListAfterFailure(framebuffers, failure);
            closeListAfterFailure(depthTextures, failure);
            throw failure;
        }
        entityVertex = createdEntityVertex;
        terrainVertex = createdTerrainVertex;
        depthFragment = createdDepthFragment;
        entityDepthProgram = createdEntityDepth;
        entityMaskedProgram = createdEntityMasked;
        terrainDepthProgram = createdTerrainDepth;
        shadowMaterial = createdMaterial;
        shadowDiffuse = createdDiffuse;
        shadowOpacity = createdOpacity;
    }

    static float[] depthBias(float[] splits, int resolution) {
        var bias = new float[splits.length];
        for (var cascade = 0; cascade < splits.length; cascade++) {
            var radius = (float) Math.ceil(splits[cascade] / 64f) * 64f;
            var texel = Math.max(1f, radius * 2f / resolution);
            bias[cascade] = BIAS_TEXELS * texel / (radius * 2f + CASTER_PADDING + RECEIVER_PADDING);
        }
        return bias;
    }

    static float[] penumbraScale(float[] splits) {
        var scale = new float[splits.length];
        for (var cascade = 0; cascade < splits.length; cascade++) {
            var radius = (float) Math.ceil(splits[cascade] / 64f) * 64f;
            var range = radius * 2f + CASTER_PADDING + RECEIVER_PADDING;
            scale[cascade] = range * SUN_WIDTH / (radius * 2f);
        }
        return scale;
    }

    static boolean isSupported(BgfxCapabilities capabilities) {
        return capabilities.depthStencilSampled() && capabilities.maxTextureSize() >= MIN_RESOLUTION;
    }

    static boolean doesCameraRequireRebuild(
            float anchorX, float anchorY, float anchorZ, float cameraX, float cameraY, float cameraZ) {
        var dx = cameraX - anchorX;
        var dy = cameraY - anchorY;
        var dz = cameraZ - anchorZ;
        return dx * dx + dy * dy + dz * dz > CAMERA_REBUILD_DISTANCE * CAMERA_REBUILD_DISTANCE;
    }

    static boolean intersectsViewVolume(Bounds3f bounds, float[] view, float radius, float near, float far) {
        var centerX = bounds.centerX();
        var centerY = bounds.centerY();
        var centerZ = bounds.centerZ();
        var extentX = (bounds.maxX() - bounds.minX()) * 0.5f;
        var extentY = (bounds.maxY() - bounds.minY()) * 0.5f;
        var extentZ = (bounds.maxZ() - bounds.minZ()) * 0.5f;
        var lightX = view[0] * centerX + view[4] * centerY + view[8] * centerZ + view[12];
        var lightY = view[1] * centerX + view[5] * centerY + view[9] * centerZ + view[13];
        var lightZ = view[2] * centerX + view[6] * centerY + view[10] * centerZ + view[14];
        var projectedX = Math.abs(view[0]) * extentX + Math.abs(view[4]) * extentY + Math.abs(view[8]) * extentZ;
        var projectedY = Math.abs(view[1]) * extentX + Math.abs(view[5]) * extentY + Math.abs(view[9]) * extentZ;
        var projectedZ = Math.abs(view[2]) * extentX + Math.abs(view[6]) * extentY + Math.abs(view[10]) * extentZ;
        return lightX + projectedX >= -radius
                && lightX - projectedX <= radius
                && lightY + projectedY >= -radius
                && lightY - projectedY <= radius
                && lightZ + projectedZ >= near
                && lightZ - projectedZ <= far;
    }

    static void setView(
            float[] target,
            float[] right,
            float[] up,
            float[] forward,
            float translateX,
            float translateY,
            float translateZ) {
        target[0] = right[0];
        target[1] = up[0];
        target[2] = forward[0];
        target[3] = 0f;
        target[4] = right[1];
        target[5] = up[1];
        target[6] = forward[1];
        target[7] = 0f;
        target[8] = right[2];
        target[9] = up[2];
        target[10] = forward[2];
        target[11] = 0f;
        target[12] = translateX;
        target[13] = translateY;
        target[14] = translateZ;
        target[15] = 1f;
    }

    static void setOrthoZeroToOne(
            float[] target, float left, float right, float bottom, float top, float near, float far) {
        Arrays.fill(target, 0f);
        target[0] = 2f / (right - left);
        target[5] = 2f / (top - bottom);
        target[10] = 1f / (far - near);
        target[12] = -(right + left) / (right - left);
        target[13] = -(top + bottom) / (top - bottom);
        target[14] = -near / (far - near);
        target[15] = 1f;
    }

    static void multiply(float[] left, float[] right, float[] target, int offset) {
        for (var column = 0; column < 4; column++) {
            for (var row = 0; row < 4; row++) {
                var value = 0f;
                for (var index = 0; index < 4; index++) {
                    value += left[index * 4 + row] * right[column * 4 + index];
                }
                target[offset + column * 4 + row] = value;
            }
        }
    }

    Snapshot render(
            List<BgfxSceneObject> objects,
            Predicate<BgfxSceneObject> hiddenObject,
            BgfxSceneUniforms sceneUniforms,
            BgfxViewAllocator views,
            Camera camera,
            RenderState rendering,
            BgfxFrameEnvironment.Snapshot environment,
            float elapsedSeconds,
            long sceneRevision,
            long movementRevision) {
        requireOpen();
        if (!rendering.isCustomLightingEffective() || !rendering.settings.isCustomSunShadowsEnabled()) {
            invalidateCache();
            snapshot = Snapshot.disabled();
            return snapshot;
        }
        var worldFocus = camera.getWorldFocusPosition();
        var exactBuild = isSameBuild(worldFocus, environment.sunDirection(), sceneRevision, movementRevision);
        if (!buildInProgress && exactBuild && cacheValid) {
            return snapshot;
        }
        if (buildInProgress) {
            if (cachedSceneRevision != sceneRevision) {
                beginBuild(worldFocus, environment.sunDirection(), sceneRevision, movementRevision);
            }
        } else if (!cacheValid || !exactBuild) {
            beginBuild(worldFocus, environment.sunDirection(), sceneRevision, movementRevision);
        }
        var budgetTruncated = false;
        var submittedCasters = 0;
        while (nextCascade < CASCADE_COUNT) {
            var cascade = nextCascade;
            var view = views.allocate("custom-sun-shadow-" + cascade, BgfxViewAllocator.Domain.RENDER, true);
            bgfx_set_view_name(view, "custom-sun-shadow-" + cascade);
            bgfx_set_view_rect(view, 0, 0, resolution, resolution);
            bgfx_set_view_mode(view, BGFX_VIEW_MODE_SEQUENTIAL);
            bgfx_set_view_frame_buffer(
                    view, framebuffers.get(textureIndex(buildBuffer, cascade)).getHandle());
            bgfx_set_view_clear(view, cascadeStarted[cascade] ? 0 : BGFX_CLEAR_DEPTH, 0, 1f, 0);
            bgfx_set_view_transform(view, lightViews[cascade], lightProjections[cascade]);
            bgfx_touch(view);
            cascadeStarted[cascade] = true;
            var submission = submitCascade(
                    view,
                    cascade,
                    objects,
                    hiddenObject,
                    sceneUniforms,
                    cachedBuildCamera(),
                    rendering,
                    elapsedSeconds,
                    nextObjectIndex,
                    nextPrimitiveOrdinal);
            submittedCasters = Math.addExact(submittedCasters, submission.submitted());
            if (!submission.complete()) {
                nextObjectIndex = submission.nextObjectIndex();
                nextPrimitiveOrdinal = submission.nextPrimitiveOrdinal();
                budgetTruncated = true;
                break;
            }
            readyMask |= 1 << cascade;
            nextCascade++;
            nextObjectIndex = 0;
            nextPrimitiveOrdinal = 0;
        }
        var completed = nextCascade == CASCADE_COUNT;
        if (completed) {
            cacheValid = true;
            buildInProgress = false;
            activeBuffer = buildBuffer;
            buildBuffer = 1 - activeBuffer;
            snapshot = new Snapshot(
                    true,
                    textureSet(activeBuffer),
                    lightViewProjections.clone(),
                    CASCADE_SPLITS.clone(),
                    cachedCameraPosition.clone(),
                    resolution,
                    device.getCapabilities().originBottomLeft(),
                    COMPLETE_READY_MASK);
            var generation = ++publicationGeneration;
            if (incompleteBuildLogged) {
                incompleteBuildLogged = false;
                log.debug(
                        "CSM state=ready publishedReadyMask={} "
                                + "front={} generation={} buildMs={} anchor={},{},{} sceneRevision={}",
                        COMPLETE_READY_MASK,
                        activeBuffer,
                        generation,
                        Math.max(0L, System.nanoTime() - buildStartedNanos) / 1_000_000L,
                        cachedCameraPosition[0],
                        cachedCameraPosition[1],
                        cachedCameraPosition[2],
                        sceneRevision);
            }
        } else if (!incompleteBuildLogged) {
            incompleteBuildLogged = true;
            log.debug(
                    "CSM state=building publishedReadyMask={} "
                            + "internalReadyMask={} front={} back={} sceneRevision={}",
                    snapshot.readyMask(),
                    readyMask,
                    activeBuffer,
                    buildBuffer,
                    sceneRevision);
        }
        if (budgetTruncated) {
            log.debug(
                    "CSM cascade {} retained {} nearest volume-tested "
                            + "draws this frame; build will resume at object {} primitive {}",
                    nextCascade,
                    submittedCasters,
                    nextObjectIndex,
                    nextPrimitiveOrdinal);
        }
        return snapshot;
    }

    Snapshot getSnapshot() {
        return snapshot;
    }

    boolean buildComplete() {
        return cacheValid && !buildInProgress && snapshot.available();
    }

    BgfxProgram getEntityDepthProgram() {
        return entityDepthProgram;
    }

    BgfxProgram getEntityMaskedProgram() {
        return entityMaskedProgram;
    }

    BgfxProgram getTerrainDepthProgram() {
        return terrainDepthProgram;
    }

    BgfxUniform getShadowMaterial() {
        return shadowMaterial;
    }

    BgfxUniform getShadowDiffuse() {
        return shadowDiffuse;
    }

    BgfxUniform getShadowOpacity() {
        return shadowOpacity;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        failure = closeAndMerge(shadowOpacity, failure);
        failure = closeAndMerge(shadowDiffuse, failure);
        failure = closeAndMerge(shadowMaterial, failure);
        failure = closeAndMerge(terrainDepthProgram, failure);
        failure = closeAndMerge(entityMaskedProgram, failure);
        failure = closeAndMerge(entityDepthProgram, failure);
        failure = closeAndMerge(depthFragment, failure);
        failure = closeAndMerge(terrainVertex, failure);
        failure = closeAndMerge(entityVertex, failure);
        for (var index = framebuffers.size() - 1; index >= 0; index--) {
            failure = closeAndMerge(framebuffers.get(index), failure);
        }
        for (var index = depthTextures.size() - 1; index >= 0; index--) {
            failure = closeAndMerge(depthTextures.get(index), failure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private boolean isSameBuild(Vec3 camera, Vec3 sunDirection, long sceneRevision, long movementRevision) {
        if (cachedSceneRevision != sceneRevision
                || cachedMovementRevision != movementRevision
                || !areSameBits(cachedSunDirection[0], sunDirection.getX())
                || !areSameBits(cachedSunDirection[1], sunDirection.getY())
                || !areSameBits(cachedSunDirection[2], sunDirection.getZ())) {
            return false;
        }
        return !doesCameraRequireRebuild(
                cachedCameraPosition[0],
                cachedCameraPosition[1],
                cachedCameraPosition[2],
                camera.getX(),
                camera.getY(),
                camera.getZ());
    }

    private Vec3 cachedBuildCamera() {
        return new Vec3(cachedCameraPosition[0], cachedCameraPosition[1], cachedCameraPosition[2]);
    }

    private void beginBuild(Vec3 camera, Vec3 sunDirection, long sceneRevision, long movementRevision) {
        cachedCameraPosition[0] = camera.getX();
        cachedCameraPosition[1] = camera.getY();
        cachedCameraPosition[2] = camera.getZ();
        cachedSunDirection[0] = sunDirection.getX();
        cachedSunDirection[1] = sunDirection.getY();
        cachedSunDirection[2] = sunDirection.getZ();
        cachedSceneRevision = sceneRevision;
        cachedMovementRevision = movementRevision;
        casterCandidates.clear();
        Arrays.fill(cascadeStarted, false);
        nextCascade = 0;
        nextObjectIndex = 0;
        nextPrimitiveOrdinal = 0;
        readyMask = 0;
        cacheValid = false;
        buildInProgress = true;
        buildStartedNanos = System.nanoTime();
        buildBuffer = activeBuffer < 0 ? 0 : 1 - activeBuffer;
        for (var cascade = 0; cascade < CASCADE_COUNT; cascade++) {
            buildCascade(camera, sunDirection, CASCADE_SPLITS[cascade], cascade);
        }
    }

    private void invalidateCache() {
        cachedSceneRevision = Long.MIN_VALUE;
        cachedMovementRevision = Long.MIN_VALUE;
        casterCandidates.clear();
        Arrays.fill(cascadeStarted, false);
        nextCascade = 0;
        nextObjectIndex = 0;
        nextPrimitiveOrdinal = 0;
        readyMask = 0;
        cacheValid = false;
        buildInProgress = false;
        activeBuffer = -1;
        buildBuffer = 0;
        incompleteBuildLogged = false;
        buildStartedNanos = 0L;
    }

    private List<BgfxTexture> textureSet(int buffer) {
        var first = textureIndex(buffer, 0);
        return List.copyOf(depthTextures.subList(first, first + CASCADE_COUNT));
    }

    private CascadeSubmission submitCascade(
            int view,
            int cascade,
            List<BgfxSceneObject> objects,
            Predicate<BgfxSceneObject> hiddenObject,
            BgfxSceneUniforms sceneUniforms,
            Vec3 camera,
            RenderState rendering,
            float elapsedSeconds,
            int startObjectIndex,
            int startPrimitiveOrdinal) {
        if (startObjectIndex == 0 && startPrimitiveOrdinal == 0) {
            casterCandidates.clear();
            for (var object : objects) {
                if (hiddenObject.test(object)
                        || object.getStaticMeshLod() != null
                                && object.getStaticMeshLod().level() > 0
                        || !intersectsCascade(object.getWorldBounds(), cascade)) {
                    continue;
                }
                casterCandidates.add(object);
            }
            casterCandidates.sort(Comparator.comparingDouble(
                            (BgfxSceneObject object) -> distanceSquared(object.getWorldBounds(), camera))
                    .thenComparingLong(BgfxSceneObject::getStableId));
        }
        var submitted = 0;
        for (var objectIndex = startObjectIndex; objectIndex < casterCandidates.size(); objectIndex++) {
            var object = casterCandidates.get(objectIndex);
            var geometry = object.getGeometry();
            for (var primitiveOrdinal = objectIndex == startObjectIndex ? startPrimitiveOrdinal : 0;
                    primitiveOrdinal < geometry.getPrimitives().size();
                    primitiveOrdinal++) {
                if (!intersectsCascade(object.getPrimitiveBounds(primitiveOrdinal), cascade)) {
                    continue;
                }
                var primitive = geometry.getPrimitives().get(primitiveOrdinal);
                var material = primitive.entityMaterial();
                var surfaceType = material == null ? SurfaceType.TERRAIN.getId() : material.getSurfaceType();
                if (!BgfxSceneRenderer.isSurfaceEnabled(surfaceType, rendering.settings)
                        || material != null && !castsShadow(material)) {
                    continue;
                }
                var masked = material != null
                        && (material.getBlendMode() == BlendMode.MASKED
                                || material.getBlendMode() == BlendMode.MASKED_BLEND);
                var uniformBytes = material == null
                        ? BgfxSubmissionBudget.TERRAIN_SHADOW_DRAW_BYTES
                        : masked
                                ? BgfxSubmissionBudget.MASKED_SHADOW_DRAW_BYTES
                                : BgfxSubmissionBudget.ENTITY_SHADOW_DRAW_BYTES;
                if (!device.getSubmissionBudget().reserve(BgfxSubmissionBudget.Domain.SHADOW, uniformBytes, 1)) {
                    return new CascadeSubmission(submitted, false, objectIndex, primitiveOrdinal);
                }
                geometry.bind(primitive.indexOffset(), primitive.indexCount());
                if (material == null) {
                    sceneUniforms.bindTerrainModel(object.getTerrainModel());
                    bgfx_set_state(BgfxSceneState.terrainShadow(), 0);
                    bgfx_submit(view, terrainDepthProgram.getHandle(), 0, BGFX_DISCARD_ALL);
                    submitted = Math.addExact(submitted, 1);
                    continue;
                }
                object.bindInstances();
                if (masked) {
                    material.bindShadow(
                            shadowMaterial,
                            shadowDiffuse,
                            shadowOpacity,
                            materialData,
                            elapsedSeconds,
                            rendering.settings.isTexturesEnabled(),
                            object.isFoliage());
                }
                bgfx_set_state(BgfxSceneState.shadow(material), 0);
                bgfx_submit(
                        view,
                        masked ? entityMaskedProgram.getHandle() : entityDepthProgram.getHandle(),
                        0,
                        BGFX_DISCARD_ALL);
                submitted = Math.addExact(submitted, 1);
            }
        }
        return new CascadeSubmission(submitted, true, casterCandidates.size(), 0);
    }

    private boolean intersectsCascade(Bounds3f bounds, int cascade) {
        return intersectsViewVolume(
                bounds,
                lightViews[cascade],
                cascadeRadii[cascade],
                cascadeNearDepths[cascade],
                cascadeFarDepths[cascade]);
    }

    private void buildCascade(Vec3 camera, Vec3 sunDirection, float farDistance, int cascade) {
        var center = new float[] {camera.getX(), camera.getY(), camera.getZ()};
        var radius = (float) Math.ceil(farDistance / 64f) * 64f;
        var sun = normalize(sunDirection.getX(), sunDirection.getY(), sunDirection.getZ());
        var forward = new float[] {-sun[0], -sun[1], -sun[2]};
        var referenceUp = Math.abs(forward[2]) > 0.94f ? new float[] {0f, 1f, 0f} : new float[] {0f, 0f, 1f};
        var right = normalize(cross(referenceUp, forward));
        var up = normalize(cross(forward, right));
        var centerX = dot(center, right);
        var centerY = dot(center, up);
        var texel = Math.max(1f, radius * 2f / resolution);
        var snappedX = Math.round(centerX / texel) * texel;
        var snappedY = Math.round(centerY / texel) * texel;
        var originZ = dot(center, forward) - radius - CASTER_PADDING;
        var view = lightViews[cascade];
        setView(view, right, up, forward, -snappedX, -snappedY, -originZ);
        var near = 1f;
        var far = radius * 2f + CASTER_PADDING + RECEIVER_PADDING;
        cascadeRadii[cascade] = radius;
        cascadeNearDepths[cascade] = near;
        cascadeFarDepths[cascade] = far;
        var projection = lightProjections[cascade];
        setOrthoZeroToOne(projection, -radius, radius, -radius, radius, near, far);
        BgfxMatrices.adaptProjection(projection, device.getCapabilities().homogeneousDepth());
        multiply(projection, view, lightViewProjections, cascade * 16);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("shadow renderer is closed");
        }
    }

    private static int textureIndex(int buffer, int cascade) {
        return buffer * CASCADE_COUNT + cascade;
    }

    private static boolean areSameBits(float left, float right) {
        return Float.floatToIntBits(left) == Float.floatToIntBits(right);
    }

    private static boolean castsShadow(BgfxSceneMaterial material) {
        if ((material.getSurfaceType() & SurfaceType.FAKE_BACKDROP.getId()) != 0
                || material.getProgramFamily() == ProgramFamily.WATER
                || material.getBlendMode() == BlendMode.INVISIBLE) {
            return false;
        }
        return switch (material.getBlendMode()) {
            case OPAQUE, MASKED, MASKED_BLEND, UNLIT -> true;
            default -> false;
        };
    }

    private static double distanceSquared(Bounds3f bounds, Vec3 point) {
        var dx = axisDistance(point.getX(), bounds.minX(), bounds.maxX());
        var dy = axisDistance(point.getY(), bounds.minY(), bounds.maxY());
        var dz = axisDistance(point.getZ(), bounds.minZ(), bounds.maxZ());
        return (double) dx * dx + (double) dy * dy + (double) dz * dz;
    }

    private static float axisDistance(float value, float minimum, float maximum) {
        return value < minimum ? minimum - value : value > maximum ? value - maximum : 0f;
    }

    private static float[] cross(float[] left, float[] right) {
        return new float[] {
            left[1] * right[2] - left[2] * right[1],
            left[2] * right[0] - left[0] * right[2],
            left[0] * right[1] - left[1] * right[0]
        };
    }

    private static float dot(float[] left, float[] right) {
        return left[0] * right[0] + left[1] * right[1] + left[2] * right[2];
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float[] normalize(float x, float y, float z) {
        var length = length(x, y, z);
        if (length < 1e-7f) {
            return new float[] {0f, 0f, 1f};
        }
        return new float[] {x / length, y / length, z / length};
    }

    private static float[] normalize(float[] value) {
        return normalize(value[0], value[1], value[2]);
    }

    private static RuntimeException closeAndMerge(AutoCloseable value, RuntimeException failure) {
        if (value == null) {
            return failure;
        }
        try {
            value.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                return exception;
            }
            failure.addSuppressed(exception);
        } catch (Exception exception) {
            var wrapped = new IllegalStateException("failed to close custom-shadow resource", exception);
            if (failure == null) {
                return wrapped;
            }
            failure.addSuppressed(wrapped);
        }
        return failure;
    }

    private static void closeAfterFailure(AutoCloseable value, Throwable failure) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void closeListAfterFailure(List<? extends AutoCloseable> values, Throwable failure) {
        for (var index = values.size() - 1; index >= 0; index--) {
            closeAfterFailure(values.get(index), failure);
        }
        values.clear();
    }

    record Snapshot(
            boolean available,
            List<BgfxTexture> depthTextures,
            float[] lightViewProjections,
            float[] splits,
            float[] cameraPosition,
            int resolution,
            boolean originBottomLeft,
            int readyMask) {
        private static final Snapshot DISABLED =
                new Snapshot(false, List.of(), new float[0], new float[0], new float[0], 0, false, 0);

        static Snapshot disabled() {
            return DISABLED;
        }
    }

    private record CascadeSubmission(int submitted, boolean complete, int nextObjectIndex, int nextPrimitiveOrdinal) {}
}
