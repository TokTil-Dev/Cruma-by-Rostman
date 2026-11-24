package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_RGB;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_SAMPLER;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_VEC4;
import static org.lwjgl.bgfx.BGFX.BGFX_VIEW_MODE_SEQUENTIAL;
import static org.lwjgl.bgfx.BGFX.bgfx_set_state;
import static org.lwjgl.bgfx.BGFX.bgfx_set_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_mode;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_name;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;
import static org.lwjgl.bgfx.BGFX.bgfx_submit;

import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.api.RenderFrameSnapshot.RenderFamily;
import com.rost.cruma.viewer.render.api.RenderState;
import com.rost.cruma.viewer.render.bgfx.BgfxViewAllocator.Domain;
import glm_.vec3.Vec3;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;

final class BgfxCustomLightingProcessor implements AutoCloseable {
    private static final long FULLSCREEN_STATE = BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A;
    private static final int CLAMP_LINEAR = BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP;
    private static final int CLAMP_POINT = CLAMP_LINEAR | BGFX_SAMPLER_MIN_POINT | BGFX_SAMPLER_MAG_POINT;

    private static final int NPC_GROUP_SQUARES = 12;

    private static final int NPC_TILE_AREAS = NPC_GROUP_SQUARES + BgfxSpawnSelfShadows.MAX_GROUPS;

    private static final int NPC_TILE_PARAMS = NPC_TILE_AREAS + BgfxSpawnSelfShadows.MAX_NPCS;

    private static final int NPC_GROUP_RANGES = NPC_TILE_PARAMS + BgfxSpawnSelfShadows.MAX_NPCS;

    private static final int NPC_SHADOW_VECTORS = NPC_GROUP_RANGES + BgfxSpawnSelfShadows.MAX_GROUPS;
    private final BgfxResourceRegistry resources;
    private final BgfxPerformanceTracker performance;
    private final BgfxMetalRayQueries metalRayQueries;
    private final BgfxRayVisibilityTemporalDenoiser rayTemporalDenoiser;
    private final BgfxLocalLightTiles localLightTiles;
    private final boolean originBottomLeft;

    private final int depthFormat;

    private final BgfxFullscreenGeometry fullscreen;
    private final BgfxProgram program;
    private final BgfxUniform sourceSampler;
    private final BgfxUniform depthSampler;
    private final BgfxUniform[] shadowSamplers;
    private final BgfxUniform localShadowSampler;
    private final BgfxUniform rayVisibilitySampler;
    private final BgfxUniform waterMaskSampler;
    private final BgfxUniform normalSampler;
    private final BgfxUniform materialAuxSampler;
    private final BgfxUniform directLocalTiles0Sampler;
    private final BgfxUniform directLocalTiles1Sampler;
    private final BgfxUniform paramsUniform;
    private final BgfxUniform inverseViewProjectionUniform;
    private final BgfxUniform inverseViewUniform;
    private final BgfxUniform shadowMatricesUniform;
    private final BgfxUniform shadowParamsUniform;
    private final BgfxUniform npcShadowSampler;
    private final BgfxUniform npcShadowUniform;

    private final BgfxUniform localMatricesUniform;
    private final BgfxUniform localParamsUniform;
    private final BgfxUniform localAtlasUniform;
    private final BgfxUniform directLocalParamsUniform;
    private final BgfxUniform rayReprojectionUniform;
    private final BgfxUniform raySamplingUniform;
    private final BgfxUniform realisticLightingUniform;
    private BgfxGlobalIllumination globalIllumination;
    private final float[] params = new float[16];
    private final float[] inverseViewProjection = new float[16];
    private final float[] inverseView = new float[16];
    private final float[] shadowMatrices = new float[BgfxShadowRenderer.CASCADE_COUNT * 16];
    private final float[] shadowParams = new float[24];
    private final float[] npcShadow = new float[NPC_SHADOW_VECTORS * 4];
    private final float[] localMatrices = new float[BgfxLocalShadowAtlas.MATRIX_FLOATS];
    private final float[] localParams = new float[BgfxLocalShadowAtlas.PARAM_FLOATS];
    private final float[] localAtlas = new float[4];
    private final float[] directLocalParams = new float[BgfxLocalLightSet.PARAM_FLOATS + 4];
    private final float[] rayReprojection = new float[52];
    private final float[] raySampling = new float[4];
    private final float[] realisticLighting = new float[32];
    private final float[] projection = new float[16];
    private Extent capacity = new Extent(0, 0);
    private BgfxTexture output;
    private BgfxFramebuffer outputFramebuffer;
    private boolean closed;

    BgfxCustomLightingProcessor(
            BgfxDevice device,
            BgfxPerformanceTracker performance,
            BgfxShader sharedFullscreenVertex,
            RenderState rendering,
            BgfxMetalRayQueries metalRayQueries) {
        if (sharedFullscreenVertex == null || rendering == null) {
            throw new IllegalArgumentException("custom lighting requires fullscreen shader and UI state");
        }
        resources = device.getResources();
        this.performance = performance;
        this.metalRayQueries = metalRayQueries;
        originBottomLeft = device.getCapabilities().originBottomLeft();
        depthFormat = device.getCapabilities().depthStencilFormat();
        BgfxFullscreenGeometry createdFullscreen = null;
        BgfxProgram createdProgram = null;
        BgfxUniform createdSource = null;
        BgfxUniform createdDepth = null;
        BgfxUniform[] createdShadowSamplers = new BgfxUniform[BgfxShadowRenderer.CASCADE_COUNT];
        BgfxUniform createdLocalShadowSampler = null;
        BgfxUniform createdRayVisibilitySampler = null;
        BgfxUniform createdWaterMaskSampler = null;
        BgfxUniform createdNormalSampler = null;
        BgfxUniform createdMaterialAuxSampler = null;
        BgfxUniform createdDirectLocalTiles0Sampler = null;
        BgfxUniform createdDirectLocalTiles1Sampler = null;
        BgfxUniform createdParams = null;
        BgfxUniform createdInverseViewProjection = null;
        BgfxUniform createdInverseView = null;
        BgfxUniform createdShadowMatrices = null;
        BgfxUniform createdShadowParams = null;
        BgfxUniform createdNpcShadowSampler = null;
        BgfxUniform createdNpcShadow = null;
        BgfxUniform createdLocalMatrices = null;
        BgfxUniform createdLocalParams = null;
        BgfxUniform createdLocalAtlas = null;
        BgfxUniform createdDirectLocalParams = null;
        BgfxUniform createdRayReprojection = null;
        BgfxUniform createdRaySampling = null;
        BgfxUniform createdRealisticLighting = null;
        BgfxRayVisibilityTemporalDenoiser createdRayTemporalDenoiser = null;
        try {
            createdFullscreen = new BgfxFullscreenGeometry(
                    resources, device.getCapabilities().originBottomLeft());
            createdProgram = BgfxProgram.graphics(
                    resources,
                    "custom-lighting/composite",
                    sharedFullscreenVertex,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_custom_lighting"));
            createdSource = BgfxUniform.create(resources, "s_customLightingSource", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            createdDepth = BgfxUniform.create(resources, "s_customLightingDepth", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            for (var cascade = 0; cascade < createdShadowSamplers.length; cascade++) {
                createdShadowSamplers[cascade] =
                        BgfxUniform.create(resources, "s_customShadow" + cascade, BGFX_UNIFORM_TYPE_SAMPLER, 1);
            }
            createdLocalShadowSampler =
                    BgfxUniform.create(resources, "s_customLocalShadow", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            createdRayVisibilitySampler =
                    BgfxUniform.create(resources, "s_customRayVisibility", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            createdWaterMaskSampler = BgfxUniform.create(resources, "s_customWaterMask", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            createdNormalSampler =
                    BgfxUniform.create(resources, "s_customLightingNormal", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            createdMaterialAuxSampler =
                    BgfxUniform.create(resources, "s_customLightingMaterialAux", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            createdDirectLocalTiles0Sampler =
                    BgfxUniform.create(resources, "s_customDirectLocalTiles0", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            createdDirectLocalTiles1Sampler =
                    BgfxUniform.create(resources, "s_customDirectLocalTiles1", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            createdParams = BgfxUniform.create(resources, "u_customLighting", BGFX_UNIFORM_TYPE_VEC4, 4);
            createdInverseViewProjection =
                    BgfxUniform.create(resources, "u_customInverseViewProjection", BGFX_UNIFORM_TYPE_VEC4, 4);
            createdInverseView = BgfxUniform.create(resources, "u_customInverseView", BGFX_UNIFORM_TYPE_VEC4, 4);
            createdShadowMatrices = BgfxUniform.create(
                    resources, "u_customShadowMatrices", BGFX_UNIFORM_TYPE_VEC4, BgfxShadowRenderer.CASCADE_COUNT * 4);
            createdShadowParams = BgfxUniform.create(resources, "u_customShadowParams", BGFX_UNIFORM_TYPE_VEC4, 6);
            createdNpcShadowSampler = BgfxUniform.create(resources, "s_customNpcShadow", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            createdNpcShadow =
                    BgfxUniform.create(resources, "u_customNpcShadow", BGFX_UNIFORM_TYPE_VEC4, NPC_SHADOW_VECTORS);
            createdLocalMatrices = BgfxUniform.create(
                    resources,
                    "u_customLocalMatrices",
                    BGFX_UNIFORM_TYPE_VEC4,
                    BgfxLocalShadowAtlas.MAX_LIGHTS * BgfxLocalShadowAtlas.FACE_COUNT * 4);
            createdLocalParams = BgfxUniform.create(
                    resources, "u_customLocalParams", BGFX_UNIFORM_TYPE_VEC4, BgfxLocalShadowAtlas.MAX_LIGHTS * 3);
            createdLocalAtlas = BgfxUniform.create(resources, "u_customLocalAtlas", BGFX_UNIFORM_TYPE_VEC4, 1);
            createdDirectLocalParams = BgfxUniform.create(
                    resources,
                    "u_customDirectLocalParams",
                    BGFX_UNIFORM_TYPE_VEC4,
                    BgfxLocalLightSet.MAX_LIGHTS * 3 + 1);
            createdRayReprojection =
                    BgfxUniform.create(resources, "u_customRayReprojection", BGFX_UNIFORM_TYPE_VEC4, 13);
            createdRaySampling = BgfxUniform.create(resources, "u_customRaySampling", BGFX_UNIFORM_TYPE_VEC4, 1);
            createdRealisticLighting =
                    BgfxUniform.create(resources, "u_customRealisticLighting", BGFX_UNIFORM_TYPE_VEC4, 8);
            createdRayTemporalDenoiser = new BgfxRayVisibilityTemporalDenoiser(
                    device, performance, sharedFullscreenVertex, createdFullscreen);
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(createdRayTemporalDenoiser, failure);
            closeAfterFailure(createdRealisticLighting, failure);
            closeAfterFailure(createdRaySampling, failure);
            closeAfterFailure(createdWaterMaskSampler, failure);
            closeAfterFailure(createdNormalSampler, failure);
            closeAfterFailure(createdMaterialAuxSampler, failure);
            closeAfterFailure(createdDirectLocalTiles1Sampler, failure);
            closeAfterFailure(createdDirectLocalTiles0Sampler, failure);
            closeAfterFailure(createdRayReprojection, failure);
            closeAfterFailure(createdDirectLocalParams, failure);
            closeAfterFailure(createdLocalAtlas, failure);
            closeAfterFailure(createdLocalParams, failure);
            closeAfterFailure(createdLocalMatrices, failure);
            closeAfterFailure(createdNpcShadow, failure);
            closeAfterFailure(createdNpcShadowSampler, failure);
            closeAfterFailure(createdShadowParams, failure);
            closeAfterFailure(createdShadowMatrices, failure);
            closeAfterFailure(createdInverseViewProjection, failure);
            closeAfterFailure(createdInverseView, failure);
            closeAfterFailure(createdParams, failure);
            for (var index = createdShadowSamplers.length - 1; index >= 0; index--) {
                closeAfterFailure(createdShadowSamplers[index], failure);
            }
            closeAfterFailure(createdLocalShadowSampler, failure);
            closeAfterFailure(createdDepth, failure);
            closeAfterFailure(createdRayVisibilitySampler, failure);
            closeAfterFailure(createdSource, failure);
            closeAfterFailure(createdProgram, failure);
            closeAfterFailure(createdFullscreen, failure);
            throw failure;
        }
        fullscreen = createdFullscreen;
        program = createdProgram;
        sourceSampler = createdSource;
        depthSampler = createdDepth;
        shadowSamplers = createdShadowSamplers;
        localShadowSampler = createdLocalShadowSampler;
        rayVisibilitySampler = createdRayVisibilitySampler;
        waterMaskSampler = createdWaterMaskSampler;
        normalSampler = createdNormalSampler;
        materialAuxSampler = createdMaterialAuxSampler;
        directLocalTiles0Sampler = createdDirectLocalTiles0Sampler;
        directLocalTiles1Sampler = createdDirectLocalTiles1Sampler;
        paramsUniform = createdParams;
        inverseViewProjectionUniform = createdInverseViewProjection;
        inverseViewUniform = createdInverseView;
        shadowMatricesUniform = createdShadowMatrices;
        shadowParamsUniform = createdShadowParams;
        npcShadowSampler = createdNpcShadowSampler;
        npcShadowUniform = createdNpcShadow;
        localMatricesUniform = createdLocalMatrices;
        localParamsUniform = createdLocalParams;
        localAtlasUniform = createdLocalAtlas;
        directLocalParamsUniform = createdDirectLocalParams;
        rayReprojectionUniform = createdRayReprojection;
        raySamplingUniform = createdRaySampling;
        realisticLightingUniform = createdRealisticLighting;
        rayTemporalDenoiser = createdRayTemporalDenoiser;
        localLightTiles = new BgfxLocalLightTiles(resources);
    }

    void prepare(Extent extent, Camera camera, RenderState rendering, Vec3 sunDirection) {
        requireOpen();
        if (!rendering.isGlobalIlluminationEffective()) {
            rendering.stats.customGiActive = false;
        }
        if (metalRayQueries == null || !rendering.isCustomLightingEffective() || extent.isZero()) {
            rendering.stats.customRayTracingActive = false;
            return;
        }
        var raySunRequested = rendering.stats.customRayTracingAvailable
                && rendering.settings.isCustomRayTracedSunReferenceEnabled()
                && rendering.settings.isCustomSunShadowsEnabled();
        metalRayQueries.prepare(camera, extent, raySunRequested, rendering.settings.getTimeOfDay(), sunDirection);
    }

    BgfxTexture process(
            BgfxTexture source,
            BgfxTexture depth,
            BgfxTexture normal,
            BgfxTexture waterMask,
            BgfxTexture materialAux,
            Extent extent,
            BgfxViewAllocator views,
            Camera camera,
            RenderState rendering,
            BgfxShadowRenderer.Snapshot shadows,
            BgfxSpawnShadowLayer.Snapshot npcShadows,
            BgfxLocalShadowAtlas.Snapshot localShadows,
            BgfxLocalLightSet.Snapshot localLights,
            BgfxFrameEnvironment.Snapshot environment) {
        requireOpen();
        if (!rendering.isCustomLightingEffective() || extent.isZero()) {
            rendering.stats.customRayTracingActive = false;
            return source;
        }
        var raySunRequested = rendering.stats.customRayTracingAvailable
                && rendering.settings.isCustomRayTracedSunReferenceEnabled()
                && rendering.settings.isCustomSunShadowsEnabled();
        if (globalIllumination == null) {
            globalIllumination = new BgfxGlobalIllumination(resources, metalRayQueries);
        }
        if (rendering.isGlobalIlluminationEffective()) {
            globalIllumination.update(camera, rendering, environment, localLights);
        } else {
            rendering.stats.customGiActive = false;
        }
        var timeOfDay = rendering.settings.getTimeOfDay();
        var rayVisibility = metalRayQueries == null
                ? BgfxMetalRayQueries.Snapshot.disabled()
                : metalRayQueries.update(camera, extent, raySunRequested, timeOfDay, environment.sunDirection());
        var raySun = environment.sunDirection();
        rayVisibility = rayTemporalDenoiser.process(
                rayVisibility, views, camera, raySun.getX(), raySun.getY(), raySun.getZ(), timeOfDay);
        ensure(source, depth, normal, waterMask, materialAux);
        var localTiles =
                localLightTiles.update(camera, extent, localLights, rendering.isCustomRealisticLightingEffective());
        fillParams(
                source,
                extent,
                camera,
                rendering,
                shadows,
                localShadows,
                localLights,
                localTiles,
                rayVisibility,
                environment);
        var viewId = views.allocate("custom-lighting-composite", Domain.RENDER, true);
        bgfx_set_view_name(viewId, "custom-lighting-composite");
        bgfx_set_view_rect(viewId, 0, 0, extent.width(), extent.height());
        bgfx_set_view_mode(viewId, BGFX_VIEW_MODE_SEQUENTIAL);
        bgfx_set_view_frame_buffer(viewId, outputFramebuffer.getHandle());
        fullscreen.bind();
        bgfx_set_texture(0, sourceSampler.getHandle(), source.getHandle(), CLAMP_LINEAR);
        bgfx_set_texture(1, depthSampler.getHandle(), depth.getHandle(), CLAMP_POINT);
        var shadowFlags = CLAMP_LINEAR | BGFX_SAMPLER_MIN_POINT | BGFX_SAMPLER_MAG_POINT;
        var shadowAvailable = shadows != null
                && shadows.available()
                && shadows.depthTextures().size() == shadowSamplers.length;
        for (var cascade = 0; cascade < shadowSamplers.length; cascade++) {
            var texture = shadowAvailable ? shadows.depthTextures().get(cascade) : depth;
            bgfx_set_texture(2 + cascade, shadowSamplers[cascade].getHandle(), texture.getHandle(), shadowFlags);
        }
        var npcAvailable = shadowAvailable
                && npcShadows != null
                && npcShadows.available()
                && npcShadows.depth() != null
                && rendering.settings.isCustomSunShadowsEnabled()
                && npcShadows.cascades() == BgfxSpawnShadowLayer.CASCADES
                && npcShadows.areas().length == BgfxSpawnShadowLayer.CASCADES * 4;
        Arrays.fill(npcShadow, 0f);
        if (npcAvailable) {
            npcShadow[0] = npcShadows.cascades();
            npcShadow[2] = (float) BgfxSpawnShadowLayer.RESOLUTION / BgfxSpawnShadowLayer.HEIGHT;
            npcShadow[3] = originBottomLeft ? 1f : 0f;
            System.arraycopy(npcShadows.areas(), 0, npcShadow, 4, npcShadows.areas().length);
            var tiles = npcShadows.tiles();
            if (tiles.available()) {
                npcShadow[1] = tiles.tiles().size();
                System.arraycopy(tiles.basis(), 0, npcShadow, 12, 12);
                System.arraycopy(BgfxSpawnModelRenderer.selfAtlas(tiles, originBottomLeft), 0, npcShadow, 24, 20);
                var all = 44;
                npcShadow[all] = npcShadow[all + 1] = Float.MAX_VALUE;
                npcShadow[all + 2] = npcShadow[all + 3] = -Float.MAX_VALUE;
                var groups = tiles.groups();
                for (var group = 0; group < groups.length / 8; group++) {
                    npcShadow[all] = Math.min(npcShadow[all], groups[group * 8]);
                    npcShadow[all + 1] = Math.min(npcShadow[all + 1], groups[group * 8 + 1]);
                    npcShadow[all + 2] = Math.max(npcShadow[all + 2], groups[group * 8 + 2]);
                    npcShadow[all + 3] = Math.max(npcShadow[all + 3], groups[group * 8 + 3]);
                    System.arraycopy(groups, group * 8, npcShadow, NPC_GROUP_SQUARES * 4 + group * 4, 4);
                    System.arraycopy(groups, group * 8 + 4, npcShadow, NPC_GROUP_RANGES * 4 + group * 4, 4);
                }
                System.arraycopy(tiles.areas(), 0, npcShadow, NPC_TILE_AREAS * 4, tiles.areas().length);
                System.arraycopy(tiles.params(), 0, npcShadow, NPC_TILE_PARAMS * 4, tiles.params().length);
            }
        }
        bgfx_set_texture(
                15,
                npcShadowSampler.getHandle(),
                npcAvailable ? npcShadows.depth().getHandle() : depth.getHandle(),
                shadowFlags);
        var localAvailable = localShadows != null && localShadows.available() && localShadows.atlasDepth() != null;
        bgfx_set_texture(
                6,
                localShadowSampler.getHandle(),
                localAvailable ? localShadows.atlasDepth().getHandle() : depth.getHandle(),
                shadowFlags);
        bgfx_set_texture(
                7,
                rayVisibilitySampler.getHandle(),
                rayVisibility.available() ? rayVisibility.texture().getHandle() : depth.getHandle(),
                CLAMP_POINT);
        bgfx_set_texture(8, waterMaskSampler.getHandle(), waterMask.getHandle(), CLAMP_POINT);
        bgfx_set_texture(9, normalSampler.getHandle(), normal.getHandle(), CLAMP_POINT);
        bgfx_set_texture(10, materialAuxSampler.getHandle(), materialAux.getHandle(), CLAMP_POINT);
        bgfx_set_texture(
                11,
                directLocalTiles0Sampler.getHandle(),
                localTiles.available() ? localTiles.first().getHandle() : materialAux.getHandle(),
                CLAMP_POINT);
        bgfx_set_texture(
                12,
                directLocalTiles1Sampler.getHandle(),
                localTiles.available() ? localTiles.second().getHandle() : materialAux.getHandle(),
                CLAMP_POINT);
        globalIllumination.bind(depth, rendering.isGlobalIlluminationEffective());
        bgfx_set_uniform(paramsUniform.getHandle(), params, 4);
        bgfx_set_uniform(inverseViewProjectionUniform.getHandle(), inverseViewProjection, 4);
        bgfx_set_uniform(inverseViewUniform.getHandle(), inverseView, 4);
        bgfx_set_uniform(shadowMatricesUniform.getHandle(), shadowMatrices, BgfxShadowRenderer.CASCADE_COUNT * 4);
        bgfx_set_uniform(shadowParamsUniform.getHandle(), shadowParams, 6);
        bgfx_set_uniform(npcShadowUniform.getHandle(), npcShadow, NPC_SHADOW_VECTORS);
        bgfx_set_uniform(
                localMatricesUniform.getHandle(),
                localMatrices,
                BgfxLocalShadowAtlas.MAX_LIGHTS * BgfxLocalShadowAtlas.FACE_COUNT * 4);
        bgfx_set_uniform(localParamsUniform.getHandle(), localParams, BgfxLocalShadowAtlas.MAX_LIGHTS * 3);
        bgfx_set_uniform(localAtlasUniform.getHandle(), localAtlas, 1);
        bgfx_set_uniform(directLocalParamsUniform.getHandle(), directLocalParams, BgfxLocalLightSet.MAX_LIGHTS * 3 + 1);
        bgfx_set_uniform(rayReprojectionUniform.getHandle(), rayReprojection, 13);
        bgfx_set_uniform(raySamplingUniform.getHandle(), raySampling, 1);
        bgfx_set_uniform(realisticLightingUniform.getHandle(), realisticLighting, 8);
        bgfx_set_state(FULLSCREEN_STATE, 0);
        bgfx_submit(viewId, program.getHandle(), 0, BGFX_DISCARD_ALL);
        performance.addDraw(RenderFamily.DEBUG, false, false, 1, 13);
        rendering.stats.customRayTracingActive = rayVisibility.available();
        return output;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        failure = closeAndMerge(outputFramebuffer, failure);
        outputFramebuffer = null;
        failure = closeAndMerge(output, failure);
        failure = closeAndMerge(globalIllumination, failure);
        failure = closeAndMerge(localLightTiles, failure);
        failure = closeAndMerge(paramsUniform, failure);
        failure = closeAndMerge(npcShadowUniform, failure);
        failure = closeAndMerge(npcShadowSampler, failure);
        failure = closeAndMerge(shadowParamsUniform, failure);
        failure = closeAndMerge(directLocalParamsUniform, failure);
        failure = closeAndMerge(localAtlasUniform, failure);
        failure = closeAndMerge(rayReprojectionUniform, failure);
        failure = closeAndMerge(raySamplingUniform, failure);
        failure = closeAndMerge(realisticLightingUniform, failure);
        failure = closeAndMerge(localParamsUniform, failure);
        failure = closeAndMerge(localMatricesUniform, failure);
        failure = closeAndMerge(shadowMatricesUniform, failure);
        failure = closeAndMerge(inverseViewProjectionUniform, failure);
        failure = closeAndMerge(inverseViewUniform, failure);
        for (var index = shadowSamplers.length - 1; index >= 0; index--) {
            failure = closeAndMerge(shadowSamplers[index], failure);
        }
        failure = closeAndMerge(localShadowSampler, failure);
        failure = closeAndMerge(waterMaskSampler, failure);
        failure = closeAndMerge(normalSampler, failure);
        failure = closeAndMerge(materialAuxSampler, failure);
        failure = closeAndMerge(directLocalTiles1Sampler, failure);
        failure = closeAndMerge(directLocalTiles0Sampler, failure);
        failure = closeAndMerge(rayVisibilitySampler, failure);
        failure = closeAndMerge(depthSampler, failure);
        failure = closeAndMerge(sourceSampler, failure);
        failure = closeAndMerge(rayTemporalDenoiser, failure);
        failure = closeAndMerge(program, failure);
        failure = closeAndMerge(fullscreen, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private void fillParams(
            BgfxTexture source,
            Extent extent,
            Camera camera,
            RenderState rendering,
            BgfxShadowRenderer.Snapshot shadows,
            BgfxLocalShadowAtlas.Snapshot localShadows,
            BgfxLocalLightSet.Snapshot localLights,
            BgfxLocalLightTiles.Snapshot localTiles,
            BgfxMetalRayQueries.Snapshot rayVisibility,
            BgfxFrameEnvironment.Snapshot environment) {
        Arrays.fill(params, 0f);
        var descriptor = source.getDescriptor();
        params[0] = 1f / descriptor.width();
        params[1] = 1f / descriptor.height();
        params[2] = (float) extent.width() / descriptor.width();
        params[3] = (float) extent.height() / descriptor.height();
        copy(camera.getProjectionMatrix(), projection);
        params[4] = projection[0];
        params[5] = projection[5];
        params[6] = projection[10];
        params[7] = projection[11];
        params[8] = projection[14];
        params[9] = projection[15];
        params[10] = originBottomLeft ? 1f : 0f;
        params[11] = rayVisibility.sunVisibilityAvailable() ? 1f : 0f;
        var sun = environment.sunDirection();
        params[12] = sun.getX();
        params[13] = sun.getY();
        params[14] = sun.getZ();
        params[15] = rendering.settings.isCustomSunShadowsEnabled() ? 1f : 0f;
        camera.copyInverseViewProjection(inverseViewProjection);
        camera.copyInverseView(inverseView);
        Arrays.fill(shadowMatrices, 0f);
        Arrays.fill(shadowParams, 0f);
        shadowParams[7] = rendering.isCustomRealisticLightingEffective()
                ? 0.62f
                : Math.max(0f, Math.min(1f, rendering.settings.getCustomAuthoredShadowStrength() / 100f));
        var shadowAvailable = shadows != null
                && shadows.available()
                && rendering.settings.isCustomSunShadowsEnabled()
                && shadows.lightViewProjections().length == shadowMatrices.length
                && shadows.splits().length == BgfxShadowRenderer.CASCADE_COUNT
                && shadows.cameraPosition().length == 3;
        if (shadowAvailable) {
            System.arraycopy(shadows.lightViewProjections(), 0, shadowMatrices, 0, shadowMatrices.length);
            System.arraycopy(shadows.splits(), 0, shadowParams, 0, 4);
            var shadowCamera = shadows.cameraPosition();
            shadowParams[4] = shadowCamera[0];
            shadowParams[5] = shadowCamera[1];
            shadowParams[6] = shadowCamera[2];
            shadowParams[8] = 1f / shadows.resolution();
            System.arraycopy(
                    BgfxShadowRenderer.depthBias(shadows.splits(), shadows.resolution()), 0, shadowParams, 16, 4);
            System.arraycopy(BgfxShadowRenderer.penumbraScale(shadows.splits()), 0, shadowParams, 20, 4);
            shadowParams[10] = shadows.originBottomLeft() ? 1f : 0f;
            shadowParams[11] = 1f;
            for (var cascade = 0; cascade < BgfxShadowRenderer.CASCADE_COUNT; cascade++) {
                shadowParams[12 + cascade] = (shadows.readyMask() & (1 << cascade)) != 0 ? 1f : 0f;
            }
        }
        Arrays.fill(localMatrices, 0f);
        Arrays.fill(localParams, 0f);
        Arrays.fill(localAtlas, 0f);
        Arrays.fill(directLocalParams, 0f);
        Arrays.fill(rayReprojection, 0f);
        Arrays.fill(raySampling, 0f);
        var localAvailable = localShadows != null
                && localShadows.available()
                && rendering.settings.isCustomLocalLightShadowsEnabled()
                && localShadows.matrices().length == localMatrices.length
                && localShadows.lightParams().length == localParams.length;
        if (localAvailable) {
            System.arraycopy(localShadows.matrices(), 0, localMatrices, 0, localMatrices.length);
            System.arraycopy(localShadows.lightParams(), 0, localParams, 0, localParams.length);
            localAtlas[0] = 1f / localShadows.atlasWidth();
            localAtlas[1] = 1f / localShadows.atlasHeight();
            localAtlas[2] = localShadows.tileSize();
            localAtlas[3] = localShadows.lightCount() + (localShadows.originBottomLeft() ? 0.25f : 0f);
        }
        var directLocalAvailable = localLights != null
                && localLights.available()
                && rendering.isCustomRealisticLightingEffective()
                && localLights.params().length == BgfxLocalLightSet.PARAM_FLOATS;
        if (directLocalAvailable) {
            System.arraycopy(localLights.params(), 0, directLocalParams, 0, BgfxLocalLightSet.PARAM_FLOATS);
            directLocalParams[BgfxLocalLightSet.PARAM_FLOATS] = localLights.lightCount();
            if (localTiles != null && localTiles.available()) {
                directLocalParams[BgfxLocalLightSet.PARAM_FLOATS + 1] = localTiles.inverseWidth();
                directLocalParams[BgfxLocalLightSet.PARAM_FLOATS + 2] = localTiles.inverseHeight();
                directLocalParams[BgfxLocalLightSet.PARAM_FLOATS + 3] = 1f;
            }
        }
        if (rayVisibility.available()) {
            System.arraycopy(rayVisibility.viewProjection(), 0, rayReprojection, 0, 16);
            System.arraycopy(rayVisibility.inverseViewProjection(), 0, rayReprojection, 16, 16);
            System.arraycopy(rayVisibility.camera(), 0, rayReprojection, 32, 4);
            System.arraycopy(rayVisibility.rayBasis(), 0, rayReprojection, 36, 16);
            var rayDescriptor = rayVisibility.texture().getDescriptor();
            raySampling[0] = 1f / rayDescriptor.width();
            raySampling[1] = 1f / rayDescriptor.height();
            raySampling[2] = rayVisibility.uvScaleX() * rayDescriptor.width();
            raySampling[3] = rayVisibility.uvScaleY() * rayDescriptor.height();
        }
        fillEnvironmentLighting(rendering, environment);
    }

    private void fillEnvironmentLighting(RenderState rendering, BgfxFrameEnvironment.Snapshot environment) {
        Arrays.fill(realisticLighting, 0f);
        if (environment == null) {
            return;
        }
        realisticLighting[0] = rendering.isCustomRealisticLightingEffective() ? 1f : 0f;
        var fog = environment.fog();
        if (fog != null) {
            realisticLighting[1] = 1f;
            realisticLighting[2] = fog.start();
            realisticLighting[3] = fog.end();
            put(realisticLighting, 4, fog.color());
        }
        if (!rendering.isCustomRealisticLightingEffective()) {
            return;
        }
        put(realisticLighting, 8, environment.staticMeshAmbient());
        put(realisticLighting, 12, environment.staticMeshSun());
        put(realisticLighting, 16, environment.bspAmbient());
        put(realisticLighting, 20, environment.bspSun());
        put(realisticLighting, 24, environment.terrainAmbient());
        put(realisticLighting, 28, environment.terrainSun());
    }

    private void ensure(
            BgfxTexture source,
            BgfxTexture sceneDepth,
            BgfxTexture normal,
            BgfxTexture waterMask,
            BgfxTexture materialAux) {
        var descriptor = source.getDescriptor();
        var depthDescriptor = sceneDepth.getDescriptor();
        var maskDescriptor = waterMask.getDescriptor();
        var normalDescriptor = normal.getDescriptor();
        var auxDescriptor = materialAux.getDescriptor();
        if (depthDescriptor.width() != descriptor.width()
                || depthDescriptor.height() != descriptor.height()
                || depthDescriptor.format() != depthFormat
                || maskDescriptor.width() != descriptor.width()
                || maskDescriptor.height() != descriptor.height()
                || maskDescriptor.format() != BGFX_TEXTURE_FORMAT_RGBA8
                || normalDescriptor.width() != descriptor.width()
                || normalDescriptor.height() != descriptor.height()
                || normalDescriptor.format() != BGFX_TEXTURE_FORMAT_RGBA8
                || auxDescriptor.width() != descriptor.width()
                || auxDescriptor.height() != descriptor.height()
                || auxDescriptor.format() != BGFX_TEXTURE_FORMAT_RGBA8) {
            throw new IllegalArgumentException(
                    "custom lighting requires matching source, depth-stencil, normal and material targets");
        }
        var requested = new Extent(descriptor.width(), descriptor.height());
        if (capacity.width() >= requested.width() && capacity.height() >= requested.height()) {
            return;
        }
        var next = new Extent(
                Math.max(capacity.width(), requested.width()), Math.max(capacity.height(), requested.height()));
        BgfxTexture createdTexture = null;
        BgfxFramebuffer createdFramebuffer = null;
        try {
            createdTexture = BgfxTexture.create2D(
                    resources,
                    "custom-lighting/output",
                    next.width(),
                    next.height(),
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA8,
                    BGFX_TEXTURE_RT,
                    null);
            createdFramebuffer =
                    BgfxFramebuffer.create(resources, "custom-lighting/output-fb", List.of(createdTexture));
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(createdFramebuffer, failure);
            closeAfterFailure(createdTexture, failure);
            throw failure;
        }
        var oldFramebuffer = outputFramebuffer;
        var oldOutput = output;
        outputFramebuffer = createdFramebuffer;
        output = createdTexture;
        capacity = next;
        RuntimeException closeFailure = null;
        closeFailure = closeAndMerge(oldFramebuffer, closeFailure);
        closeFailure = closeAndMerge(oldOutput, closeFailure);
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("custom lighting processor is closed");
        }
    }

    private static void put(float[] target, int offset, Vec3 value) {
        target[offset] = value.getX();
        target[offset + 1] = value.getY();
        target[offset + 2] = value.getZ();
    }

    private static void copy(FloatBuffer source, float[] target) {
        var duplicate = source.duplicate();
        duplicate.position(0);
        duplicate.limit(16);
        duplicate.get(target, 0, 16);
    }

    private static RuntimeException closeAndMerge(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                return exception;
            }
            failure.addSuppressed(exception);
        } catch (Exception exception) {
            var wrapped = new IllegalStateException("failed to close custom-lighting resource", exception);
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
}
