package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.SpawnOverlayState;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.api.RenderState;
import com.rost.cruma.viewer.render.bgfx.BgfxViewAllocator.Domain;
import com.rost.cruma.viewer.spawn.SpawnTriangles;
import glm_.vec3.Vec3;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXTransientVertexBuffer;
import org.lwjgl.system.MemoryStack;

final class BgfxSpawnRenderer implements AutoCloseable {
    private static final int ASK_ALL = 1 << 20;

    private static final double FILL_SHARE = .25;

    private static final double BODY_SHARE = .1;

    private static final double AREA_SHARE = .2;

    private static final BgfxVertexLayoutSpec LAYOUT = BgfxVertexLayoutSpec.builder()
            .add(BGFX.BGFX_ATTRIB_POSITION, 3, BGFX.BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX.BGFX_ATTRIB_COLOR0, 4, BGFX.BGFX_ATTRIB_TYPE_UINT8, true, false)
            .build();

    private static final float DEPTH_TOLERANCE = 32;

    private static final float HIDDEN_FILL = 0;

    private static final float HIDDEN_BODY = 0;

    private static final int CLAMP_POINT = BGFX.BGFX_SAMPLER_U_CLAMP
            | BGFX.BGFX_SAMPLER_V_CLAMP
            | BGFX.BGFX_SAMPLER_MIN_POINT
            | BGFX.BGFX_SAMPLER_MAG_POINT;
    private final BgfxDevice device;
    private final BgfxOverlayLinePass linePass;
    private final BgfxLightGlowPass glowPass;
    private final BgfxSpawnModelRenderer models;
    private final BgfxSpawnShadowLayer shadowLayer;
    private final BgfxSpawnSelfShadows selfShadows;
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final float[] projectionTerms = new float[16];
    private BgfxProgram program;
    private BgfxUniform projectionUniform;
    private BgfxUniform depthUniform;
    private BgfxUniform depthSampler;
    private BgfxUniform modelDepthSampler;
    private BgfxFramebuffer framebuffer;
    private BgfxTexture color;
    private BgfxTexture depth;
    private boolean modelsInScene;

    BgfxSpawnRenderer(BgfxDevice device) {
        this.device = device;
        this.linePass = new BgfxOverlayLinePass(device);
        this.glowPass = new BgfxLightGlowPass(device);
        this.models = new BgfxSpawnModelRenderer(device);
        this.shadowLayer = new BgfxSpawnShadowLayer(device, models);
        this.selfShadows = new BgfxSpawnSelfShadows(device, shadowLayer);
    }

    BgfxSpawnShadowLayer.Snapshot renderSunShadows(
            SpawnOverlayState state,
            BgfxShadowRenderer.Snapshot sun,
            RenderState rendering,
            Camera camera,
            BgfxViewAllocator views) {
        if (!shadowLayer.isUsable(state, sun, rendering)) {
            selfShadows.forget();
            models.setSelfShadows(BgfxSpawnSelfShadows.Snapshot.DISABLED);
            return shadowLayer.render(state, sun, rendering, views, Set.of(), 0);
        }
        var eye = camera.getPosition();
        var tiles = selfShadows.render(
                state.getShadowCasters(),
                eye.getX(),
                eye.getY(),
                eye.getZ(),
                BgfxSpawnSelfShadows.sunTowards(sun),
                views);
        models.setSelfShadows(tiles);
        return shadowLayer
                .render(state, sun, rendering, views, tiles.tiles().keySet(), tiles.signature())
                .withTiles(tiles);
    }

    void setSunShadows(SpawnOverlayState state, BgfxShadowRenderer.Snapshot sun, RenderState rendering) {
        var active = rendering.isCustomLightingEffective() && rendering.settings.isCustomSunShadowsEnabled();
        models.setSunShadows(active ? sun : null);
        if (!active) {
            models.setSelfShadows(BgfxSpawnSelfShadows.Snapshot.DISABLED);
        }
        state.sunShadows = active && shadowLayer.getSnapshot().available();
    }

    void rebuildShadowsAtEveryChange() {
        shadowLayer.setRebuildFrames(0);
        selfShadows.setRebuildFrames(0);
    }

    BgfxSpawnShadowLayer getShadowLayer() {
        return shadowLayer;
    }

    BgfxSpawnSelfShadows getSelfShadows() {
        return selfShadows;
    }

    void setSeconds(float seconds, double bitmapSeconds) {
        models.setSeconds(seconds, bitmapSeconds);
    }

    void setSun(Vec3 towards) {
        models.setSun(towards);
    }

    void setLighting(BgfxSpawnModelLighting.@Nullable Frame frame) {
        models.setLighting(frame);
    }

    void setBodyLights(List<BgfxSpawnModelRenderer.BodyLight> lights) {
        models.setBodyLights(lights);
    }

    boolean hasModels(SpawnOverlayState state, boolean pawnsOnly) {
        if (pawnsOnly) {
            return !state.pawns.isEmpty();
        }
        return state.visible || !state.staged.isEmpty() || !state.cast.isEmpty() || !state.pawns.isEmpty();
    }

    void renderModelsInScene(
            SpawnOverlayState state,
            Camera camera,
            BgfxTexture targetColor,
            @Nullable BgfxTexture waterMask,
            BgfxTexture targetDepth,
            Extent extent,
            BgfxViewAllocator views,
            boolean pawnsOnly) {
        models.render(
                pawnsOnly ? state.pawns : state.getDrawnModels(), camera, targetColor, targetDepth, extent, views);
        models.stampDepth(targetColor, waterMask, targetDepth, extent, views);
        modelsInScene = true;
    }

    @Nullable
    BgfxTexture getModelDepthInScene() {
        return modelsInScene ? models.getDepth() : null;
    }

    void renderPawns(
            SpawnOverlayState state,
            Camera camera,
            BgfxTexture targetColor,
            BgfxTexture targetDepth,
            Extent extent,
            BgfxViewAllocator views) {
        var drawn = modelsInScene;
        modelsInScene = false;
        if (!drawn) {
            models.render(state.pawns, camera, targetColor, targetDepth, extent, views);
        }
    }

    void render(
            SpawnOverlayState state,
            Camera camera,
            BgfxTexture targetColor,
            BgfxTexture targetDepth,
            Extent extent,
            BgfxViewAllocator views) {
        var drawn = modelsInScene;
        modelsInScene = false;
        var softDepth = targetColor != null && targetDepth != null;
        var visible = state.visible;
        var withModels = hasModels(state, false);
        if (withModels && !drawn) {
            models.render(state, camera, targetColor, softDepth ? targetDepth : null, extent, views);
        }
        var modelDepth = softDepth && withModels ? models.getDepth() : null;
        var fills = visible ? state.fills.getShownCount() : 0;
        var bodies = visible ? state.bodies.getShownCount() : 0;
        var lines = visible ? state.lines.getShownCount() : 0;
        var areaFills = state.areasVisible ? state.areaFills.getShownCount() : 0;
        var areaLines = state.areasVisible ? state.areaLines.getShownCount() : 0;
        var marks = state.marksHidden ? 0 : state.marks.getShownCount();
        var editorLines = state.marksHidden ? 0 : state.editorLines.getShownCount();
        var glow = softDepth && !state.marksHidden ? state.editorLight.getShown() : null;
        state.editorLight.setDrawn(glow != null);
        var editorEdges = state.marksHidden ? 0 : state.editorEdges.getShownCount();
        if (fills == 0
                && bodies == 0
                && lines == 0
                && areaFills == 0
                && areaLines == 0
                && marks == 0
                && editorLines == 0
                && editorEdges == 0
                && glow == null) {
            return;
        }
        ensureFramebuffer(targetColor, softDepth ? null : targetDepth);
        var view = views.allocate("spawns", Domain.RENDER, true);
        BGFX.bgfx_set_view_mode(view, BGFX.BGFX_VIEW_MODE_SEQUENTIAL);
        BGFX.bgfx_set_view_frame_buffer(view, framebuffer == null ? BGFX.BGFX_INVALID_HANDLE : framebuffer.getHandle());
        BGFX.bgfx_set_view_rect(view, 0, 0, extent.width(), extent.height());
        BGFX.bgfx_set_view_transform(
                view,
                BgfxMatrices.firstSixteenFloats(camera.getViewMatrix()),
                BgfxMatrices.toBackendProjection(
                        camera.getProjectionMatrix(), device.getCapabilities().homogeneousDepth(), projection));
        try (var stack = MemoryStack.stackPush()) {
            if (glow != null) {
                var scale = Math.clamp(extent.width() / Math.max(1f, state.editorLines.getShownPoints()), 1f, 4f);
                glowPass.submit(view, camera, extent, targetDepth, modelDepth, glow, scale);
            }
            if (fills > 0) {
                submit(
                        view,
                        camera,
                        state.fills,
                        softDepth ? targetDepth : null,
                        modelDepth,
                        HIDDEN_FILL,
                        FILL_SHARE,
                        stack);
            }
            if (areaFills > 0) {
                submit(
                        view,
                        camera,
                        state.areaFills,
                        softDepth ? targetDepth : null,
                        modelDepth,
                        HIDDEN_FILL,
                        AREA_SHARE,
                        stack);
            }
            if (bodies > 0) {
                submit(
                        view,
                        camera,
                        state.bodies,
                        softDepth ? targetDepth : null,
                        modelDepth,
                        HIDDEN_BODY,
                        BODY_SHARE,
                        stack);
            }
            if (lines > 0) {
                linePass.submit(
                        view, camera, extent, softDepth ? targetDepth : null, modelDepth, state.lines, 0, stack);
            }
            if (areaLines > 0) {
                linePass.submit(
                        view, camera, extent, softDepth ? targetDepth : null, modelDepth, state.areaLines, 0, stack);
            }
            if (marks > 0) {
                linePass.submitMarks(
                        view, camera, extent, softDepth ? targetDepth : null, modelDepth, state.marks, stack);
            }
            if (editorEdges > 0) {
                linePass.submitEdges(
                        view, camera, extent, softDepth ? targetDepth : null, modelDepth, state.editorEdges, stack);
            }
            if (editorLines > 0) {
                linePass.submitMarks(
                        view, camera, extent, softDepth ? targetDepth : null, modelDepth, state.editorLines, stack);
            }
        }
    }

    @Override
    public void close() {
        releaseFramebuffer();
        linePass.close();
        glowPass.close();
        selfShadows.close();
        shadowLayer.close();
        models.close();
        if (program != null) {
            program.close();
            program = null;
        }
        for (var uniform : new BgfxUniform[] {projectionUniform, depthUniform, depthSampler, modelDepthSampler}) {
            if (uniform != null) {
                uniform.close();
            }
        }
    }

    private void submit(
            int view,
            Camera camera,
            SpawnTriangles triangles,
            BgfxTexture sceneDepth,
            @Nullable BgfxTexture modelDepth,
            float hiddenOpacity,
            double share,
            MemoryStack stack) {
        createProgram();
        var data = triangles.getShown();
        var count = triangles.getShownCount() / 3 * 3;
        var available = BGFX.bgfx_get_avail_transient_vertex_buffer(ASK_ALL, LAYOUT.createNative(stack));
        count = Math.min(count, (int) (available * share) / 3 * 3);
        if (count == 0) {
            return;
        }
        var vertices = BGFXTransientVertexBuffer.calloc(stack);
        BGFX.bgfx_alloc_transient_vertex_buffer(vertices, count, LAYOUT.createNative(stack));
        var out = vertices.data();
        for (var index = 0; index < count; index++) {
            var offset = index * SpawnTriangles.FLOATS;
            var colorBits = Float.floatToRawIntBits(data[offset + 3]);
            out.putFloat(data[offset]).putFloat(data[offset + 1]).putFloat(data[offset + 2]);
            out.put((byte) colorBits)
                    .put((byte) (colorBits >>> 8))
                    .put((byte) (colorBits >>> 16))
                    .put((byte) (colorBits >>> 24));
        }
        var source = camera.getProjectionMatrix().duplicate();
        source.position(0).limit(16);
        source.get(projectionTerms, 0, 16);
        BGFX.bgfx_set_uniform(
                projectionUniform.getHandle(),
                stack.floats(projectionTerms[10], projectionTerms[11], projectionTerms[14], projectionTerms[15]),
                1);
        BGFX.bgfx_set_uniform(
                depthUniform.getHandle(),
                stack.floats(
                        sceneDepth != null && modelDepth != null ? 1 : 0,
                        BgfxOverlayLinePass.MODEL_TOLERANCE,
                        hiddenOpacity,
                        sceneDepth == null ? 0 : DEPTH_TOLERANCE),
                1);
        if (sceneDepth != null) {
            BGFX.bgfx_set_texture(0, depthSampler.getHandle(), sceneDepth.getHandle(), CLAMP_POINT);
            if (modelDepth != null) {
                BGFX.bgfx_set_texture(1, modelDepthSampler.getHandle(), modelDepth.getHandle(), CLAMP_POINT);
            }
        }
        BGFX.bgfx_set_transient_vertex_buffer(0, vertices, 0, count);
        var state = BGFX.BGFX_STATE_WRITE_RGB
                | BGFX.BGFX_STATE_WRITE_A
                | BGFX.BGFX_STATE_BLEND_ALPHA
                | BGFX.BGFX_STATE_MSAA;
        if (sceneDepth == null && depth != null) {
            state |= BGFX.BGFX_STATE_DEPTH_TEST_LEQUAL;
        }
        BGFX.bgfx_set_state(state, 0);
        BGFX.bgfx_submit(view, program.getHandle(), 0, BGFX.BGFX_DISCARD_ALL);
    }

    private void createProgram() {
        if (program != null) {
            return;
        }
        var resources = device.getResources();
        program = BgfxProgram.graphics(
                resources,
                "spawns/surface",
                BgfxShaderResources.load(device.getCapabilities(), "vs_spawn"),
                BgfxShaderResources.load(device.getCapabilities(), "fs_spawn"));
        projectionUniform = BgfxUniform.create(resources, "u_spawnProjection", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
        depthUniform = BgfxUniform.create(resources, "u_spawnDepth", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
        depthSampler = BgfxUniform.create(resources, "s_spawnDepth", BGFX.BGFX_UNIFORM_TYPE_SAMPLER, 1);
        modelDepthSampler = BgfxUniform.create(resources, "s_spawnModelDepth", BGFX.BGFX_UNIFORM_TYPE_SAMPLER, 1);
    }

    private void ensureFramebuffer(BgfxTexture targetColor, BgfxTexture targetDepth) {
        if (color == targetColor && depth == targetDepth && (targetColor == null || framebuffer != null)) {
            return;
        }
        releaseFramebuffer();
        if (targetColor != null) {
            framebuffer = BgfxFramebuffer.create(
                    device.getResources(),
                    "spawns/framebuffer",
                    targetDepth == null ? List.of(targetColor) : List.of(targetColor, targetDepth));
        }
        color = targetColor;
        depth = targetDepth;
    }

    private void releaseFramebuffer() {
        if (framebuffer != null) {
            framebuffer.close();
            framebuffer = null;
        }
        color = null;
        depth = null;
    }
}
