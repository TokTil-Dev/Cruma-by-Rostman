package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.world.AuthoredMaterialPassProgram;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.SpawnOverlayState;
import com.rost.cruma.viewer.render.SpawnOverlayState.LivePose;
import com.rost.cruma.viewer.render.SpawnOverlayState.ModelInstance;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.bgfx.BgfxShadowRenderer.Snapshot;
import com.rost.cruma.viewer.render.bgfx.BgfxTextureCache.TextureRef;
import com.rost.cruma.viewer.render.bgfx.BgfxViewAllocator.Domain;
import com.rost.cruma.viewer.spawn.SpawnModelData;
import com.rost.cruma.viewer.spawn.SpawnModelData.Bitmap;
import com.rost.cruma.viewer.spawn.SpawnModelData.Kind;
import com.rost.cruma.viewer.spawn.SpawnModelData.Part;
import com.rost.cruma.viewer.spawn.SpawnModelData.Specular;
import glm_.vec3.Vec3;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

final class BgfxSpawnModelRenderer implements AutoCloseable {
    static final int MAX_BODY_LIGHTS = 4;

    static final int[] PASS_STAGES = {0, 7, 8, 9, 10, 11, 12, 13};

    static final int SPECULAR_VECTORS = 3;

    static final long SEPARATE_SPECULAR_STATE = BGFX.BGFX_STATE_WRITE_RGB
            | BGFX.BGFX_STATE_DEPTH_TEST_LEQUAL
            | BGFX.BGFX_STATE_MSAA
            | BGFX.BGFX_STATE_BLEND_FUNC(BGFX.BGFX_STATE_BLEND_ONE, BGFX.BGFX_STATE_BLEND_ONE);

    private static final Logger log = LogManager.getLogger(BgfxSpawnModelRenderer.class);

    private static final BgfxVertexLayoutSpec LAYOUT = BgfxVertexLayoutSpec.builder()
            .add(BGFX.BGFX_ATTRIB_POSITION, 3, BGFX.BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX.BGFX_ATTRIB_NORMAL, 3, BGFX.BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX.BGFX_ATTRIB_TEXCOORD0, 2, BGFX.BGFX_ATTRIB_TYPE_FLOAT)
            .build();
    private static final float DEPTH_TOLERANCE = 2;

    private static final float[] OWN_LIGHT = ownLight();

    private static final int EVICT_FRAMES = 900;

    private static final int EVICT_POSE_FRAMES = 120;

    private static final int CLAMP_POINT = BGFX.BGFX_SAMPLER_U_CLAMP
            | BGFX.BGFX_SAMPLER_V_CLAMP
            | BGFX.BGFX_SAMPLER_MIN_POINT
            | BGFX.BGFX_SAMPLER_MAG_POINT;

    private final BgfxDevice device;
    private final Map<String, Resident> residents = new HashMap<>();
    private final Map<LivePose, Moving> moving = new IdentityHashMap<>();
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final float[] projectionTerms = new float[16];
    private final float[] transform = new float[16];
    private BgfxProgram program;
    private BgfxUniform textureSampler;
    private BgfxUniform specularSampler;
    private BgfxUniform specularMaskSampler;
    private BgfxUniform specularUniform;
    private BgfxUniform depthSampler;
    private BgfxUniform projectionUniform;
    private BgfxUniform lightUniform;
    private BgfxUniform eyeUniform;

    private final float[] sun = {.36f, .28f, .89f};

    private final float[] eye = new float[3];
    private final BgfxUniform[] shadowSamplers = new BgfxUniform[BgfxShadowRenderer.CASCADE_COUNT];
    private BgfxUniform shadowMatricesUniform;
    private BgfxUniform shadowUniform;
    private final float[] shadowMatrices = new float[BgfxShadowRenderer.CASCADE_COUNT * 16];
    private final float[] shadowParams = new float[16];

    private BgfxUniform selfShadowSampler;
    private BgfxUniform selfShadowUniform;
    private BgfxUniform selfAtlasUniform;
    private final float[] selfShadow = new float[24];
    private BgfxSpawnSelfShadows.Snapshot selfShadows = BgfxSpawnSelfShadows.Snapshot.DISABLED;

    @Nullable
    private Snapshot sunShadows;

    private BgfxUniform paramsUniform;
    private BgfxUniform colorUniform;
    private BgfxUniform bodyLightsUniform;
    private BgfxUniform bodyLightCountUniform;
    private BgfxUniform lightsUniform;
    private BgfxUniform ambientUniform;
    private BgfxProgram passProgram;
    private BgfxUniform passValuesUniform;
    private final BgfxUniform[] passSamplers = new BgfxUniform[PASS_STAGES.length];
    private @Nullable BgfxTextureCache passTextures;
    private float seconds;
    private double bitmapSeconds;
    private BgfxSpawnModelLighting.@Nullable Frame lighting;
    private final float[] bodyLights = new float[MAX_BODY_LIGHTS * 8];
    private int bodyLightCount;
    private BgfxTexture white;
    private BgfxTexture ownDepth;
    private boolean drew;
    private BgfxFramebuffer framebuffer;
    private BgfxTexture framebufferColor;
    private long frame;
    private BgfxProgram stampProgram;
    private BgfxUniform stampDepthSampler;
    private BgfxFullscreenGeometry fullscreen;
    private BgfxFramebuffer stampFramebuffer;
    private BgfxTexture stampColor;
    private BgfxTexture stampDepth;

    BgfxSpawnModelRenderer(BgfxDevice device) {
        this.device = device;
    }

    record BodyLight(Vec3 position, float radius, Vec3 color, float brightness) {}

    static final class Resident implements AutoCloseable {
        final BgfxBuffer vertices;
        final BgfxBuffer indices;
        final List<Part> parts;
        final List<BgfxTexture> textures;
        final List<@Nullable BgfxAuthoredPasses> passes;
        final List<TextureRef> passTextures;
        final List<@Nullable BgfxTexture> specularTextures;
        final List<@Nullable BgfxTexture> specularMasks;
        long lastFrame;

        Resident(
                BgfxBuffer vertices,
                BgfxBuffer indices,
                List<Part> parts,
                List<BgfxTexture> textures,
                List<@Nullable BgfxAuthoredPasses> passes,
                List<TextureRef> passTextures,
                List<@Nullable BgfxTexture> specularTextures,
                List<@Nullable BgfxTexture> specularMasks) {
            this.vertices = vertices;
            this.indices = indices;
            this.parts = parts;
            this.textures = textures;
            this.passes = passes;
            this.passTextures = passTextures;
            this.specularTextures = specularTextures;
            this.specularMasks = specularMasks;
        }

        @Override
        public void close() {
            vertices.close();
            indices.close();
            for (var texture : textures) {
                if (texture != null) {
                    texture.close();
                }
            }
            for (var texture : passTextures) {
                texture.close();
            }
            for (var texture : specularTextures) {
                if (texture != null) {
                    texture.close();
                }
            }
            for (var texture : specularMasks) {
                if (texture != null) {
                    texture.close();
                }
            }
        }
    }

    private static final class Moving implements AutoCloseable {
        final BgfxBuffer vertices;
        float @Nullable [] uploaded;
        long lastFrame;

        Moving(BgfxBuffer vertices) {
            this.vertices = vertices;
        }

        @Override
        public void close() {
            vertices.close();
        }
    }

    static float[] selfAtlas(BgfxSpawnSelfShadows.Snapshot tiles, boolean originBottomLeft) {
        var atlas = new float[20];
        atlas[0] = tiles.nearCount();
        atlas[2] = originBottomLeft ? 1f : 0f;
        atlas[3] = tiles.available() ? 1f : 0f;
        if (tiles.available()) {
            for (var axis = 0; axis < 3; axis++) {
                atlas[4 + axis] = -tiles.basis()[8 + axis];
            }
            atlas[7] = tiles.groups().length / 8f;
        }
        atlas[8] = (float) BgfxSpawnSelfShadows.REGION_WIDTH / BgfxSpawnShadowLayer.WIDTH;
        atlas[9] = (float) BgfxSpawnShadowLayer.RESOLUTION / BgfxSpawnShadowLayer.HEIGHT;
        atlas[10] = (float) BgfxSpawnSelfShadows.REGION_HEIGHT / BgfxSpawnShadowLayer.HEIGHT;
        atlas[12] = 1f / BgfxSpawnShadowLayer.WIDTH;
        atlas[13] = 1f / BgfxSpawnShadowLayer.HEIGHT;
        atlas[14] = 6f / BgfxSpawnSelfShadows.NEAR_TILE;
        atlas[15] = 6f / BgfxSpawnSelfShadows.FAR_TILE;
        atlas[16] = (float) BgfxSpawnSelfShadows.NEAR_TILE / BgfxSpawnSelfShadows.REGION_WIDTH;
        atlas[17] = (float) BgfxSpawnSelfShadows.NEAR_TILE / BgfxSpawnSelfShadows.REGION_HEIGHT;
        atlas[18] = (float) BgfxSpawnSelfShadows.FAR_TILE / BgfxSpawnSelfShadows.REGION_WIDTH;
        atlas[19] = (float) BgfxSpawnSelfShadows.FAR_TILE / BgfxSpawnSelfShadows.REGION_HEIGHT;
        return atlas;
    }

    static void writeModelTransform(ModelInstance instance, float[] transform) {
        var matrix = instance.matrix();
        if (matrix != null && matrix.length == 16) {
            System.arraycopy(matrix, 0, transform, 0, 16);
            return;
        }
        var cos = (float) Math.cos(instance.yaw()) * instance.scale();
        var sin = (float) Math.sin(instance.yaw()) * instance.scale();
        transform[0] = cos;
        transform[1] = sin;
        transform[2] = 0;
        transform[3] = 0;
        transform[4] = -sin;
        transform[5] = cos;
        transform[6] = 0;
        transform[7] = 0;
        transform[8] = 0;
        transform[9] = 0;
        transform[10] = instance.scale();
        transform[11] = 0;
        transform[12] = instance.x();
        transform[13] = instance.y();
        transform[14] = instance.z();
        transform[15] = 1;
    }

    static BgfxTexture whiteTexture(BgfxResourceRegistry resources, String label) {
        var pixel = MemoryUtil.memAlloc(4);
        try {
            pixel.put((byte) -1).put((byte) -1).put((byte) -1).put((byte) -1).flip();
            return BgfxTexture.create2D(
                    resources, label, 1, 1, false, 1, BGFX.BGFX_TEXTURE_FORMAT_RGBA8, BGFX.BGFX_SAMPLER_NONE, pixel);
        } finally {
            MemoryUtil.memFree(pixel);
        }
    }

    static float[] specularUniforms(@Nullable Specular specular, boolean masked, float seconds) {
        var values = new float[SPECULAR_VECTORS * 4];
        if (specular == null) {
            return values;
        }
        var source = BgfxSceneMaterial.environmentSourceOf(specular.environment());
        var animation = specular.animation();
        values[0] = 1f;
        values[1] = source == 1 ? 2f : source;
        values[2] = specular.modulate2x() ? 1f : 0f;
        values[3] = specular.lit() ? 1f : 0f;
        values[4] = animation.mode();
        values[5] = masked ? 1f : 0f;
        values[6] = animation.extra();
        values[7] = seconds;
        values[8] = animation.paramX();
        values[9] = animation.paramY();
        values[10] = animation.paramZ();
        values[11] = animation.paramW();
        return values;
    }

    static long getPassState(Part part, BgfxAuthoredPasses passes, int draw) {
        var framebuffer = passes.getFramebuffer(draw);
        if (framebuffer != null) {
            return BgfxSceneState.authoredFramebuffer(framebuffer, 0L, false, false);
        }
        var material = part.passes() == null ? null : part.passes().framebuffer();
        return passes.getState(
                material == null ? getPartState(part) : BgfxSceneState.authoredFramebuffer(material, 0L, false, false),
                draw);
    }

    static float getPassAlphaRef(Part part, BgfxAuthoredPasses passes, int draw) {
        var framebuffer = passes.getFramebuffer(draw);
        if (framebuffer == null && part.passes() != null) {
            framebuffer = part.passes().framebuffer();
        }
        if (framebuffer == null) {
            return part.alphaRef();
        }
        return framebuffer.alphaReference() < 0 ? 0f : (framebuffer.alphaReference() + .5f) / 255f;
    }

    void render(
            SpawnOverlayState state,
            Camera camera,
            BgfxTexture targetColor,
            BgfxTexture sceneDepth,
            Extent extent,
            BgfxViewAllocator views) {
        render(state.getDrawnModels(), camera, targetColor, sceneDepth, extent, views);
    }

    void render(
            List<ModelInstance> models,
            Camera camera,
            BgfxTexture targetColor,
            BgfxTexture sceneDepth,
            Extent extent,
            BgfxViewAllocator views) {
        frame++;
        drew = false;
        evict();
        if (models.isEmpty() || targetColor == null) {
            return;
        }
        if (passTextures != null) {
            passTextures.advanceFrame(bitmapSeconds);
        }
        drew = true;
        createProgram();
        ensureTargets(targetColor);
        var view = views.allocate("spawn-models", Domain.RENDER, true);
        BGFX.bgfx_set_view_mode(view, BGFX.BGFX_VIEW_MODE_SEQUENTIAL);
        BGFX.bgfx_set_view_frame_buffer(view, framebuffer.getHandle());
        BGFX.bgfx_set_view_rect(view, 0, 0, extent.width(), extent.height());
        BGFX.bgfx_set_view_clear(view, BGFX.BGFX_CLEAR_DEPTH, 0, 1f, 0);
        BGFX.bgfx_set_view_transform(
                view,
                BgfxMatrices.firstSixteenFloats(camera.getViewMatrix()),
                BgfxMatrices.toBackendProjection(
                        camera.getProjectionMatrix(), device.getCapabilities().homogeneousDepth(), projection));
        BGFX.bgfx_touch(view);
        bindSunShadows();
        var position = camera.getPosition();
        eye[0] = position.getX();
        eye[1] = position.getY();
        eye[2] = position.getZ();
        var source = camera.getProjectionMatrix().duplicate();
        source.position(0).limit(16);
        source.get(projectionTerms, 0, 16);
        var blended = new ArrayList<Runnable>();
        for (var instance : models) {
            if (instance.model().indices().length == 0 || instance.model().vertices().length == 0) {
                continue;
            }
            var resident = getResident(instance.model());
            resident.lastFrame = frame;
            var lit = light(instance);
            for (var i = 0; i < resident.parts.size(); i++) {
                var part = resident.parts.get(i);
                if (part.indexCount() == 0) {
                    continue;
                }
                var texture = resident.textures.get(i);
                var passes = resident.passes.get(i);
                if (part.kind() == Kind.BLENDED || passes != null && isBlended(part)) {
                    var partIndex = i;
                    blended.add(() -> {
                        try (var inner = MemoryStack.stackPush()) {
                            if (passes != null) {
                                drawPasses(view, instance, resident, part, passes, sceneDepth, lit, inner);
                            } else {
                                draw(view, instance, resident, partIndex, sceneDepth, lit, inner);
                            }
                        }
                    });
                    continue;
                }
                try (var stack = MemoryStack.stackPush()) {
                    if (passes != null) {
                        drawPasses(view, instance, resident, part, passes, sceneDepth, lit, stack);
                    } else {
                        draw(view, instance, resident, i, sceneDepth, lit, stack);
                    }
                }
            }
        }
        for (var draw : blended) {
            draw.run();
        }
    }

    void stampDepth(
            BgfxTexture targetColor,
            @Nullable BgfxTexture waterMask,
            BgfxTexture sceneDepth,
            Extent extent,
            BgfxViewAllocator views) {
        if (!drew || sceneDepth == null) {
            return;
        }
        createStampProgram();
        var color = waterMask != null ? waterMask : targetColor;
        if (stampFramebuffer == null || stampColor != color || stampDepth != sceneDepth) {
            releaseStampTarget();
            stampFramebuffer = BgfxFramebuffer.create(
                    device.getResources(), "spawn-models/stamp-framebuffer", List.of(color, sceneDepth));
            stampColor = color;
            stampDepth = sceneDepth;
        }
        var view = views.allocate("spawn-models-stamp", Domain.RENDER, false);
        BGFX.bgfx_set_view_frame_buffer(view, stampFramebuffer.getHandle());
        BGFX.bgfx_set_view_rect(view, 0, 0, extent.width(), extent.height());
        BGFX.bgfx_set_texture(0, stampDepthSampler.getHandle(), ownDepth.getHandle(), CLAMP_POINT);
        fullscreen.bind();
        var state = BGFX.BGFX_STATE_WRITE_Z | BGFX.BGFX_STATE_DEPTH_TEST_ALWAYS;
        if (waterMask != null) {
            state |= BGFX.BGFX_STATE_WRITE_A;
        }
        BGFX.bgfx_set_state(state, 0);
        BGFX.bgfx_submit(view, stampProgram.getHandle(), 0, BGFX.BGFX_DISCARD_ALL);
    }

    void setSunShadows(Snapshot shadows) {
        sunShadows = shadows != null
                        && shadows.available()
                        && shadows.depthTextures().size() == BgfxShadowRenderer.CASCADE_COUNT
                        && shadows.lightViewProjections().length == shadowMatrices.length
                        && shadows.splits().length == BgfxShadowRenderer.CASCADE_COUNT
                        && shadows.cameraPosition().length == 3
                ? shadows
                : null;
    }

    void setSelfShadows(BgfxSpawnSelfShadows.Snapshot shadows) {
        selfShadows = shadows;
    }

    void setBodyLights(List<BodyLight> lights) {
        bodyLightCount = Math.min(lights.size(), MAX_BODY_LIGHTS);
        Arrays.fill(bodyLights, 0f);
        for (var index = 0; index < bodyLightCount; index++) {
            var light = lights.get(index);
            var at = index * 8;
            bodyLights[at] = light.position().getX();
            bodyLights[at + 1] = light.position().getY();
            bodyLights[at + 2] = light.position().getZ();
            bodyLights[at + 3] = light.radius();
            bodyLights[at + 4] = light.color().getX();
            bodyLights[at + 5] = light.color().getY();
            bodyLights[at + 6] = light.color().getZ();
        }
    }

    void setLighting(BgfxSpawnModelLighting.@Nullable Frame frame) {
        lighting = frame;
    }

    void setSeconds(float seconds, double bitmapSeconds) {
        this.seconds = seconds;
        this.bitmapSeconds = bitmapSeconds;
    }

    void setSun(@Nullable Vec3 towards) {
        if (towards == null || !Float.isFinite(towards.getX()) || !Float.isFinite(towards.getZ())) {
            return;
        }
        var x = towards.getX();
        var y = towards.getY();
        var z = Math.max(towards.getZ(), .35f);
        var length = (float) Math.sqrt(x * x + y * y + z * z);
        sun[0] = x / length;
        sun[1] = y / length;
        sun[2] = z / length;
    }

    Resident getResident(SpawnModelData model) {
        var resident = residents.get(model.key());
        if (resident != null) {
            return resident;
        }
        var vertexBytes = MemoryUtil.memAlloc(model.vertices().length * 4);
        var indexBytes = MemoryUtil.memAlloc(model.indices().length * 4);
        try {
            vertexBytes.asFloatBuffer().put(model.vertices());
            indexBytes.asIntBuffer().put(model.indices());
            var resources = device.getResources();
            var vertices = BgfxBuffer.staticVertex(resources, "spawn-model/" + model.key(), vertexBytes, LAYOUT, 0);
            var indices =
                    BgfxBuffer.staticIndex(resources, "spawn-model/" + model.key() + "/indices", indexBytes, true, 0);
            var textures = new ArrayList<BgfxTexture>();
            var passes = new ArrayList<@Nullable BgfxAuthoredPasses>();
            var passTextures = new ArrayList<TextureRef>();
            var specularTextures = new ArrayList<@Nullable BgfxTexture>();
            var specularMasks = new ArrayList<@Nullable BgfxTexture>();
            for (var index = 0; index < model.parts().size(); index++) {
                var part = model.parts().get(index);
                textures.add(
                        part.rgba() == null
                                ? null
                                : createTexture(
                                        "spawn-model/" + model.key() + "/texture",
                                        new Bitmap(part.textureWidth(), part.textureHeight(), part.rgba())));
                var specular = part.specular();
                var name = "spawn-model/" + model.key() + "/part-" + index;
                specularTextures.add(specular == null ? null : createTexture(name + "/specular", specular.texture()));
                specularMasks.add(
                        specular == null || specular.mask() == null
                                ? null
                                : createTexture(name + "/specular-mask", specular.mask()));
                passes.add(
                        part.passes() == null
                                ? null
                                : createPasses(
                                        model.key() + "/part-" + index,
                                        part.passes().program(),
                                        passTextures));
            }
            resident = new Resident(
                    vertices, indices, model.parts(), textures, passes, passTextures, specularTextures, specularMasks);
            residents.put(model.key(), resident);
            return resident;
        } finally {
            MemoryUtil.memFree(vertexBytes);
            MemoryUtil.memFree(indexBytes);
        }
    }

    void bindVertices(ModelInstance instance, Resident resident) {
        var pose = instance.pose();
        var vertices = pose == null ? null : pose.getVertices();
        if (vertices == null || vertices.length != instance.model().vertices().length) {
            BGFX.bgfx_set_vertex_buffer(0, resident.vertices.getHandle(), 0, -1);
            return;
        }
        var count = vertices.length / SpawnModelData.FLOATS;
        var buffer = moving.get(pose);
        if (buffer == null) {
            buffer = new Moving(BgfxBuffer.dynamicVertex(
                    device.getResources(), "spawn-model/" + instance.model().key() + "/pose", count, LAYOUT, 0));
            moving.put(pose, buffer);
        }
        buffer.lastFrame = frame;
        if (buffer.uploaded != vertices) {
            var bytes = MemoryUtil.memAlloc(vertices.length * 4);
            try {
                bytes.asFloatBuffer().put(vertices);
                buffer.vertices.update(0, bytes);
            } finally {
                MemoryUtil.memFree(bytes);
            }
            buffer.uploaded = vertices;
        }
        BGFX.bgfx_set_dynamic_vertex_buffer(0, buffer.vertices.getHandle(), 0, count);
    }

    @Nullable
    BgfxTexture getDepth() {
        return drew ? ownDepth : null;
    }

    @Override
    public void close() {
        for (var resident : residents.values()) {
            resident.close();
        }
        residents.clear();
        for (var buffer : moving.values()) {
            buffer.close();
        }
        moving.clear();
        if (passTextures != null) {
            passTextures.close();
            passTextures = null;
        }
        if (passProgram != null) {
            passProgram.close();
            passProgram = null;
        }
        for (var index = 0; index < passSamplers.length; index++) {
            if (passSamplers[index] != null) {
                passSamplers[index].close();
                passSamplers[index] = null;
            }
        }
        if (passValuesUniform != null) {
            passValuesUniform.close();
            passValuesUniform = null;
        }
        releaseTargets();
        releaseStampTarget();
        for (var resource : new AutoCloseable[] {stampProgram, stampDepthSampler, fullscreen}) {
            if (resource != null) {
                try {
                    resource.close();
                } catch (Exception ignored) {
                }
            }
        }
        stampProgram = null;
        stampDepthSampler = null;
        fullscreen = null;
        if (white != null) {
            white.close();
            white = null;
        }
        if (program != null) {
            program.close();
            program = null;
        }
        for (var uniform : new BgfxUniform[] {
            textureSampler,
            specularSampler,
            specularMaskSampler,
            specularUniform,
            depthSampler,
            projectionUniform,
            lightUniform,
            eyeUniform,
            paramsUniform,
            colorUniform,
            bodyLightsUniform,
            bodyLightCountUniform,
            lightsUniform,
            ambientUniform,
            shadowMatricesUniform,
            shadowUniform,
            selfShadowSampler,
            selfShadowUniform,
            selfAtlasUniform
        }) {
            if (uniform != null) {
                uniform.close();
            }
        }
        for (var uniform : shadowSamplers) {
            if (uniform != null) {
                uniform.close();
            }
        }
    }

    private void draw(
            int view,
            ModelInstance instance,
            Resident resident,
            int partIndex,
            BgfxTexture sceneDepth,
            float[] lit,
            MemoryStack stack) {
        var part = resident.parts.get(partIndex);
        var texture = resident.textures.get(partIndex);
        var specular = part.specular();
        var specularTexture = resident.specularTextures.get(partIndex);
        var specularMask = resident.specularMasks.get(partIndex);
        var separate = specular != null && specular.separatePass();
        for (var pass = 0; pass < (separate ? 2 : 1); pass++) {
            var specularPass = pass == 1;
            bindDraw(
                    instance,
                    resident,
                    part,
                    sceneDepth,
                    lit,
                    specularPass ? -1f : part.alphaRef(),
                    texture != null,
                    stack);
            BGFX.bgfx_set_texture(
                    0,
                    textureSampler.getHandle(),
                    texture == null ? white.getHandle() : texture.getHandle(),
                    0xffffffff);
            var values = specularUniforms(separate && !specularPass ? null : specular, specularMask != null, seconds);
            if (specularPass) {
                values[0] = 2f;
            }
            BGFX.bgfx_set_uniform(specularUniform.getHandle(), stack.floats(values), SPECULAR_VECTORS);
            BGFX.bgfx_set_texture(
                    7,
                    specularSampler.getHandle(),
                    specularTexture == null ? white.getHandle() : specularTexture.getHandle(),
                    specular == null ? 0xffffffff : specularSamplerFlags(specular));
            BGFX.bgfx_set_texture(
                    8,
                    specularMaskSampler.getHandle(),
                    specularMask == null ? white.getHandle() : specularMask.getHandle(),
                    0xffffffff);
            BGFX.bgfx_set_state(specularPass ? SEPARATE_SPECULAR_STATE : getPartState(part), 0);
            BGFX.bgfx_submit(view, program.getHandle(), 0, BGFX.BGFX_DISCARD_ALL);
        }
    }

    private void drawPasses(
            int view,
            ModelInstance instance,
            Resident resident,
            Part part,
            BgfxAuthoredPasses passes,
            BgfxTexture sceneDepth,
            float[] lit,
            MemoryStack stack) {
        createPassProgram();
        passes.prepare(seconds, bitmapSeconds, true, passTextures.getPagedWhite());
        for (var draw = 0; draw < passes.getDrawCount(); draw++) {
            bindDraw(instance, resident, part, sceneDepth, lit, getPassAlphaRef(part, passes, draw), true, stack);
            BGFX.bgfx_set_uniform(passValuesUniform.getHandle(), passes.draw(draw), BgfxAuthoredPasses.VECTORS);
            for (var sample = 0; sample < PASS_STAGES.length; sample++) {
                BGFX.bgfx_set_texture(
                        PASS_STAGES[sample],
                        passSamplers[sample].getHandle(),
                        passes.getSampleTexture(sample).getTexture().getHandle(),
                        passes.getSampleFlags(sample, true));
            }
            BGFX.bgfx_set_state(getPassState(part, passes, draw), 0);
            BGFX.bgfx_submit(view, passProgram.getHandle(), 0, BGFX.BGFX_DISCARD_ALL);
        }
    }

    private void bindDraw(
            ModelInstance instance,
            Resident resident,
            Part part,
            BgfxTexture sceneDepth,
            float[] lit,
            float alphaRef,
            boolean textured,
            MemoryStack stack) {
        bindSelfShadow(instance);
        writeModelTransform(instance, transform);
        var matrix = stack.malloc(64);
        matrix.asFloatBuffer().put(transform);
        BGFX.bgfx_set_transform(matrix);
        BGFX.bgfx_set_uniform(
                projectionUniform.getHandle(),
                stack.floats(projectionTerms[10], projectionTerms[11], projectionTerms[14], projectionTerms[15]),
                1);
        BGFX.bgfx_set_uniform(lightUniform.getHandle(), stack.floats(sun[0], sun[1], sun[2], 0f), 1);
        BGFX.bgfx_set_uniform(eyeUniform.getHandle(), stack.floats(eye[0], eye[1], eye[2], 1f), 1);
        BGFX.bgfx_set_uniform(
                paramsUniform.getHandle(),
                stack.floats(alphaRef, textured ? 1f : 0f, sceneDepth == null ? 0f : DEPTH_TOLERANCE, 1f),
                1);
        BGFX.bgfx_set_uniform(colorUniform.getHandle(), stack.floats(.72f, .72f, .74f, 1f), 1);
        BGFX.bgfx_set_uniform(bodyLightsUniform.getHandle(), stack.floats(bodyLights), MAX_BODY_LIGHTS * 2);
        BGFX.bgfx_set_uniform(bodyLightCountUniform.getHandle(), stack.floats(bodyLightCount, 0f, 0f, 0f), 1);
        var lights = stack.mallocFloat(BgfxSpawnModelLighting.SLOTS * 16);
        lights.put(lit, 0, BgfxSpawnModelLighting.SLOTS * 16).flip();
        BGFX.bgfx_set_uniform(lightsUniform.getHandle(), lights, BgfxSpawnModelLighting.SLOTS * 4);
        var ambient = BgfxSpawnModelLighting.SLOTS * 16;
        BGFX.bgfx_set_uniform(
                ambientUniform.getHandle(),
                stack.floats(lit[ambient], lit[ambient + 1], lit[ambient + 2], lit[ambient + 3]),
                1);
        if (sceneDepth != null) {
            BGFX.bgfx_set_texture(1, depthSampler.getHandle(), sceneDepth.getHandle(), CLAMP_POINT);
        }
        var self = selfShadows;
        BGFX.bgfx_set_texture(
                6,
                selfShadowSampler.getHandle(),
                self.available() ? self.depth().getHandle() : white.getHandle(),
                CLAMP_POINT);
        var shadows = sunShadows;
        for (var cascade = 0; cascade < shadowSamplers.length; cascade++) {
            var map = shadows == null ? white : shadows.depthTextures().get(cascade);
            BGFX.bgfx_set_texture(2 + cascade, shadowSamplers[cascade].getHandle(), map.getHandle(), CLAMP_POINT);
        }
        bindVertices(instance, resident);
        BGFX.bgfx_set_index_buffer(resident.indices.getHandle(), part.indexOffset(), part.indexCount());
    }

    private @Nullable BgfxAuthoredPasses createPasses(
            String name, AuthoredMaterialPassProgram program, List<TextureRef> owned) {
        if (!BgfxAuthoredPasses.supports(program)) {
            return null;
        }
        if (passTextures == null) {
            passTextures = new BgfxTextureCache(device, device.getTuning());
        }
        var cache = passTextures;
        var loaded = new ArrayList<TextureRef>();
        try {
            var passes = new BgfxAuthoredPasses(
                    program,
                    (texture, suffix) -> {
                        var ref = cache.loadPaged(texture, name + '/' + suffix);
                        if (!ref.isBaseline()) {
                            loaded.add(ref);
                        }
                        return ref;
                    },
                    cache.getBitmapClocks(),
                    cache.getAuthoredMaterials());
            owned.addAll(loaded);
            return passes;
        } catch (IllegalArgumentException unsupported) {
            log.debug("{} draws its one image: {}", name, unsupported.getMessage());
            for (var ref : loaded) {
                ref.close();
            }
            return null;
        }
    }

    private float[] light(ModelInstance instance) {
        var frameLighting = lighting;
        if (frameLighting == null) {
            return OWN_LIGHT;
        }
        writeModelTransform(instance, transform);
        var lit = frameLighting.computeUniforms(instance, transform);
        return lit == null ? OWN_LIGHT : lit;
    }

    private void bindSelfShadow(ModelInstance instance) {
        var self = selfShadows;
        var tile = self.available() ? self.tiles().get(instance) : null;
        Arrays.fill(selfShadow, 0f);
        if (tile != null) {
            System.arraycopy(self.matrices(), tile * 16, selfShadow, 0, 16);
            System.arraycopy(self.params(), tile * 4, selfShadow, 16, 4);
            var scale = self.scale(tile);
            selfShadow[20] = 1f;
            selfShadow[21] = scale[0];
            selfShadow[22] = scale[1];
            selfShadow[23] =
                    6f / (tile < self.nearCount() ? BgfxSpawnSelfShadows.NEAR_TILE : BgfxSpawnSelfShadows.FAR_TILE);
        }
        BGFX.bgfx_set_uniform(selfShadowUniform.getHandle(), selfShadow, 6);
    }

    private void bindSunShadows() {
        Arrays.fill(shadowParams, 0f);
        var shadows = sunShadows;
        if (shadows != null) {
            System.arraycopy(shadows.lightViewProjections(), 0, shadowMatrices, 0, shadowMatrices.length);
            System.arraycopy(shadows.splits(), 0, shadowParams, 0, 4);
            System.arraycopy(shadows.cameraPosition(), 0, shadowParams, 4, 3);
            shadowParams[7] = 1f;
            shadowParams[8] = 1f / shadows.resolution();
            System.arraycopy(
                    BgfxShadowRenderer.depthBias(shadows.splits(), shadows.resolution()), 0, shadowParams, 12, 4);
            shadowParams[10] = shadows.originBottomLeft() ? 1f : 0f;
        }
        BGFX.bgfx_set_uniform(shadowMatricesUniform.getHandle(), shadowMatrices, BgfxShadowRenderer.CASCADE_COUNT * 4);
        BGFX.bgfx_set_uniform(shadowUniform.getHandle(), shadowParams, 4);
        BGFX.bgfx_set_uniform(
                selfAtlasUniform.getHandle(),
                selfAtlas(selfShadows, device.getCapabilities().originBottomLeft()),
                5);
    }

    private BgfxTexture createTexture(String label, Bitmap bitmap) {
        var levels = new ArrayList<byte[]>();
        var width = bitmap.width();
        var height = bitmap.height();
        var level = bitmap.rgba();
        levels.add(level);
        while (width > 1 || height > 1) {
            var nextWidth = Math.max(1, width / 2);
            var nextHeight = Math.max(1, height / 2);
            var next = new byte[nextWidth * nextHeight * 4];
            for (var y = 0; y < nextHeight; y++) {
                for (var x = 0; x < nextWidth; x++) {
                    for (var channel = 0; channel < 4; channel++) {
                        var sum = 0;
                        for (var dy = 0; dy < 2; dy++) {
                            for (var dx = 0; dx < 2; dx++) {
                                var sx = Math.min(width - 1, x * 2 + dx);
                                var sy = Math.min(height - 1, y * 2 + dy);
                                sum += level[(sy * width + sx) * 4 + channel] & 0xff;
                            }
                        }
                        next[(y * nextWidth + x) * 4 + channel] = (byte) (sum / 4);
                    }
                }
            }
            levels.add(next);
            level = next;
            width = nextWidth;
            height = nextHeight;
        }
        var total = levels.stream().mapToInt(bytes -> bytes.length).sum();
        var data = MemoryUtil.memAlloc(total);
        try {
            for (var bytes : levels) {
                data.put(bytes);
            }
            data.flip();
            return BgfxTexture.create2D(
                    device.getResources(),
                    label,
                    bitmap.width(),
                    bitmap.height(),
                    true,
                    1,
                    BGFX.BGFX_TEXTURE_FORMAT_RGBA8,
                    BGFX.BGFX_SAMPLER_NONE,
                    data);
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private void evict() {
        var iterator = residents.values().iterator();
        while (iterator.hasNext()) {
            var resident = iterator.next();
            if (frame - resident.lastFrame > EVICT_FRAMES) {
                resident.close();
                iterator.remove();
            }
        }
        var poses = moving.values().iterator();
        while (poses.hasNext()) {
            var buffer = poses.next();
            if (frame - buffer.lastFrame > EVICT_POSE_FRAMES) {
                buffer.close();
                poses.remove();
            }
        }
    }

    private void ensureTargets(BgfxTexture targetColor) {
        if (ownDepth != null && framebufferColor == targetColor) {
            return;
        }
        releaseTargets();
        ownDepth = BgfxTexture.create2D(
                device.getResources(),
                "spawn-models/depth",
                targetColor.getDescriptor().width(),
                targetColor.getDescriptor().height(),
                false,
                1,
                device.getCapabilities().depthStencilFormat(),
                BGFX.BGFX_TEXTURE_RT,
                null);
        framebuffer = BgfxFramebuffer.create(
                device.getResources(), "spawn-models/framebuffer", List.of(targetColor, ownDepth));
        framebufferColor = targetColor;
    }

    private void createPassProgram() {
        if (passProgram != null) {
            return;
        }
        var resources = device.getResources();
        passProgram = BgfxProgram.graphics(
                resources,
                "spawns/model-passes",
                BgfxShaderResources.load(device.getCapabilities(), "vs_spawn_model_passes"),
                BgfxShaderResources.load(device.getCapabilities(), "fs_spawn_model_passes"));
        passValuesUniform = BgfxUniform.create(
                resources, "u_authoredPasses", BGFX.BGFX_UNIFORM_TYPE_VEC4, BgfxAuthoredPasses.VECTORS);
        for (var sample = 0; sample < passSamplers.length; sample++) {
            passSamplers[sample] = BgfxUniform.create(
                    resources,
                    sample == 0 ? "s_sceneDiffuse" : "s_authored" + sample,
                    BGFX.BGFX_UNIFORM_TYPE_SAMPLER,
                    1);
        }
    }

    private void releaseStampTarget() {
        if (stampFramebuffer != null) {
            stampFramebuffer.close();
            stampFramebuffer = null;
        }
        stampColor = null;
        stampDepth = null;
    }

    private void createStampProgram() {
        if (stampProgram != null) {
            return;
        }
        var resources = device.getResources();
        var capabilities = device.getCapabilities();
        fullscreen = new BgfxFullscreenGeometry(resources, capabilities.originBottomLeft());
        stampProgram = BgfxProgram.graphics(
                resources,
                "spawns/model-stamp",
                BgfxShaderResources.load(capabilities, "vs_fullscreen"),
                BgfxShaderResources.load(capabilities, "fs_spawn_model_stamp"));
        stampDepthSampler = BgfxUniform.create(resources, "s_spawnModelStampDepth", BGFX.BGFX_UNIFORM_TYPE_SAMPLER, 1);
    }

    private void releaseTargets() {
        if (framebuffer != null) {
            framebuffer.close();
            framebuffer = null;
        }
        if (ownDepth != null) {
            ownDepth.close();
            ownDepth = null;
        }
        framebufferColor = null;
    }

    private void createProgram() {
        if (program != null) {
            return;
        }
        var resources = device.getResources();
        program = BgfxProgram.graphics(
                resources,
                "spawns/models",
                BgfxShaderResources.load(device.getCapabilities(), "vs_spawn_model_lit"),
                BgfxShaderResources.load(device.getCapabilities(), "fs_spawn_model"));
        textureSampler = BgfxUniform.create(resources, "s_spawnModelTexture", BGFX.BGFX_UNIFORM_TYPE_SAMPLER, 1);
        specularSampler = BgfxUniform.create(resources, "s_spawnModelSpecular", BGFX.BGFX_UNIFORM_TYPE_SAMPLER, 1);
        specularMaskSampler =
                BgfxUniform.create(resources, "s_spawnModelSpecularMask", BGFX.BGFX_UNIFORM_TYPE_SAMPLER, 1);
        specularUniform =
                BgfxUniform.create(resources, "u_spawnModelSpecular", BGFX.BGFX_UNIFORM_TYPE_VEC4, SPECULAR_VECTORS);
        depthSampler = BgfxUniform.create(resources, "s_spawnModelDepth", BGFX.BGFX_UNIFORM_TYPE_SAMPLER, 1);
        projectionUniform = BgfxUniform.create(resources, "u_spawnModelProjection", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
        lightUniform = BgfxUniform.create(resources, "u_spawnModelLight", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
        eyeUniform = BgfxUniform.create(resources, "u_spawnModelEye", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
        paramsUniform = BgfxUniform.create(resources, "u_spawnModelParams", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
        colorUniform = BgfxUniform.create(resources, "u_spawnModelColor", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
        bodyLightsUniform = BgfxUniform.create(
                resources, "u_spawnModelBodyLights", BGFX.BGFX_UNIFORM_TYPE_VEC4, MAX_BODY_LIGHTS * 2);
        bodyLightCountUniform =
                BgfxUniform.create(resources, "u_spawnModelBodyLightCount", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
        lightsUniform = BgfxUniform.create(
                resources, "u_spawnModelLights", BGFX.BGFX_UNIFORM_TYPE_VEC4, BgfxSpawnModelLighting.SLOTS * 4);
        ambientUniform = BgfxUniform.create(resources, "u_spawnModelAmbient", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
        for (var cascade = 0; cascade < shadowSamplers.length; cascade++) {
            shadowSamplers[cascade] =
                    BgfxUniform.create(resources, "s_spawnModelShadow" + cascade, BGFX.BGFX_UNIFORM_TYPE_SAMPLER, 1);
        }
        shadowMatricesUniform = BgfxUniform.create(
                resources,
                "u_spawnModelShadowMatrices",
                BGFX.BGFX_UNIFORM_TYPE_VEC4,
                BgfxShadowRenderer.CASCADE_COUNT * 4);
        shadowUniform = BgfxUniform.create(resources, "u_spawnModelShadow", BGFX.BGFX_UNIFORM_TYPE_VEC4, 4);
        selfShadowSampler = BgfxUniform.create(resources, "s_spawnModelSelfShadow", BGFX.BGFX_UNIFORM_TYPE_SAMPLER, 1);
        selfShadowUniform = BgfxUniform.create(resources, "u_spawnModelSelf", BGFX.BGFX_UNIFORM_TYPE_VEC4, 6);
        selfAtlasUniform = BgfxUniform.create(resources, "u_spawnModelSelfAtlas", BGFX.BGFX_UNIFORM_TYPE_VEC4, 5);
        white = whiteTexture(resources, "spawn-models/white");
    }

    private static boolean isBlended(Part part) {
        var framebuffer = part.passes() == null ? null : part.passes().framebuffer();
        return framebuffer != null && framebuffer.blending();
    }

    private static long getPartState(Part part) {
        var state = BGFX.BGFX_STATE_WRITE_RGB
                | BGFX.BGFX_STATE_WRITE_A
                | BGFX.BGFX_STATE_DEPTH_TEST_LEQUAL
                | BGFX.BGFX_STATE_MSAA;
        if (part.kind() == Kind.BLENDED) {
            state |= BGFX.BGFX_STATE_BLEND_ALPHA;
        }
        if (part.depthWrite()) {
            state |= BGFX.BGFX_STATE_WRITE_Z;
        }
        return state;
    }

    private static float[] ownLight() {
        var result = new float[BgfxSpawnModelLighting.VECTORS * 4];
        result[result.length - 1] = -1f;
        return result;
    }

    private static int specularSamplerFlags(Specular specular) {
        return (specular.clampU() ? BGFX.BGFX_SAMPLER_U_CLAMP : 0)
                | (specular.clampV() ? BGFX.BGFX_SAMPLER_V_CLAMP : 0);
    }
}
