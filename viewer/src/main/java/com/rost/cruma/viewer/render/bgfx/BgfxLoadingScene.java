package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_NORMAL;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_POSITION;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD0;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_FLOAT;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_DEPTH;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIP_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_ADD;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_ALPHA;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_LEQUAL;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_MSAA;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_RGB;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_Z;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT_MSAA_X4;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT_WRITE_ONLY;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_MAT4;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_SAMPLER;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_VEC4;
import static org.lwjgl.bgfx.BGFX.bgfx_set_dynamic_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_state;
import static org.lwjgl.bgfx.BGFX.bgfx_set_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_transform;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_set_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_clear;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_name;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_transform;
import static org.lwjgl.bgfx.BGFX.bgfx_submit;
import static org.lwjgl.bgfx.BGFX.bgfx_touch;

import com.rost.cruma.core.world.LoadingScene.Batch;
import com.rost.cruma.core.world.LoadingScene.Figure;
import com.rost.cruma.core.world.LoadingScene.Group;
import com.rost.cruma.core.world.LoadingScene.Landmarks;
import com.rost.cruma.core.world.LoadingScene.Picture;
import com.rost.cruma.viewer.render.api.ViewerLoadingScene;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class BgfxLoadingScene implements ViewerLoadingScene, AutoCloseable {
    private static final BgfxVertexLayoutSpec LAYOUT = BgfxVertexLayoutSpec.builder()
            .add(BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_NORMAL, 3, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD0, 2, BGFX_ATTRIB_TYPE_FLOAT)
            .build();
    private static final int VERTEX_FLOATS = 8;
    private static final int SUN_MAP_SIZE = 2048;
    private static final float SUN_EXTENT = 2.1f, SUN_DEPTH = 3.5f, MIDDLE_HEIGHT = .5f;
    private static final float[] SUN = {-.5f, .72f, .48f, 1.25f / SUN_MAP_SIZE};
    private static final float[] SUN_COLOR = {1.2f, 1.08f, .88f, 0};
    private static final float[] SKY_COLOR = {.68f, .74f, .86f, 0};
    private static final float[] GROUND_COLOR = {.4f, .36f, .35f, 0};
    private static final float[] HAZE_COLOR = {42 / 255f, 47 / 255f, 62 / 255f, 0};
    private static final float[] STONE = {205 / 255f, 209 / 255f, 214 / 255f, 0};
    private static final float[] FILLED = {75 / 255f, 237 / 255f, 255 / 255f, 0};
    private static final float[] EDGE = {205 / 255f, 250 / 255f, 255 / 255f, 0};
    private static final float[] FIGURE = {225 / 255f, 228 / 255f, 232 / 255f, 0};
    private static final float TOWER_GAIN = 1.25f, TOI_GAIN = 1.5f;
    private static final float GROUND_FADE_FROM = 1.2f, GROUND_FADE_TO = HALF_WIDTH;
    private static final float GROUND_LIGHT_AT = .05f;
    private static final float[] GROUND_BELOW = {31 / 255f, 34 / 255f, 40 / 255f, .35f};
    private static final float BURIED_UNDER = .02f;
    private static final float FAR_HAZE = .4f, TOI_HAZE = .12f;
    private static final int CLEAR_COLOR = 0x2a2f3e00;
    private static final long SHADOW_SAMPLER = BGFX_SAMPLER_U_CLAMP
            | BGFX_SAMPLER_V_CLAMP
            | BGFX_SAMPLER_MIN_POINT
            | BGFX_SAMPLER_MAG_POINT
            | BGFX_SAMPLER_MIP_POINT;
    private static final long PREPARE_NANOS = 4_000_000L;
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    private final BgfxDevice device;
    private final BgfxViewerUiRenderer ui;
    private final int shadowView;
    private final int sceneView;
    private final BgfxProgram litProgram;
    private final BgfxProgram shadowProgram;
    private final BgfxUniform textureSampler;
    private final BgfxUniform shadowSampler;
    private final BgfxUniform sunMatrix;
    private final BgfxUniform sun;
    private final BgfxUniform sunColor;
    private final BgfxUniform sky;
    private final BgfxUniform ground;
    private final BgfxUniform material;
    private final BgfxUniform fill;
    private final BgfxUniform tint;
    private final BgfxUniform filled;
    private final BgfxUniform edge;
    private final BgfxUniform haze;
    private final BgfxUniform depthRules;
    private final BgfxUniform groundLook;
    private final BgfxUniform groundBelow;
    private final BgfxTexture shadowDepth;
    private final BgfxFramebuffer shadowTarget;
    private final float[] sunProjection = new float[16];
    private final float[] sceneProjection = new float[16];
    private final float[] depthRulesData = new float[4];
    private final float[] materialData = new float[4];
    private final float[] fillData = new float[4];
    private final float[] noFill = new float[4];
    private final float[] freeLook = {0, 0, 0, -1};
    private final float[] landmarkLook = {0, 0, 0, -1};
    private final float[] groundLookData = {1, GROUND_FADE_FROM, GROUND_FADE_TO, GROUND_LIGHT_AT};
    private final float[] transform = new float[16];
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    private final List<Drawn> drawn = new ArrayList<>();
    private final List<@Nullable Posed> figures = new ArrayList<>();
    private final List<AutoCloseable> owned = new ArrayList<>();
    private @Nullable BgfxTexture color;
    private @Nullable BgfxTexture depth;
    private @Nullable BgfxFramebuffer target;
    private long imageId;
    private int imageWidth;
    private int imageHeight;
    private boolean closed;

    private record Drawn(Group group, BgfxBuffer vertices, BgfxTexture texture) {}

    private static final class Posed {
        final Figure figure;
        final BgfxBuffer vertices;
        final BgfxBuffer indices;
        final BgfxTexture atlas;
        final float[] pose;
        final float[] placed;
        final float[] normals;
        final ByteBuffer staging;

        Posed(Figure figure, BgfxBuffer vertices, BgfxBuffer indices, BgfxTexture atlas) {
            this.figure = figure;
            this.vertices = vertices;
            this.indices = indices;
            this.atlas = atlas;
            pose = new float[figure.keys()[0].length];
            placed = new float[pose.length];
            normals = new float[pose.length];
            staging = ByteBuffer.allocateDirect(figure.wedgePoints().length * VERTEX_FLOATS * Float.BYTES)
                    .order(ByteOrder.nativeOrder());
        }
    }

    BgfxLoadingScene(BgfxDevice device, BgfxViewerUiRenderer ui, Landmarks landmarks, List<@Nullable Figure> looks) {
        this.device = device;
        this.ui = ui;
        var resources = device.getResources();
        var capabilities = device.getCapabilities();
        shadowView = device.getUiViewId() - 2;
        sceneView = device.getUiViewId() - 1;
        litProgram = own(BgfxProgram.graphics(
                resources,
                "loading-scene/lit",
                BgfxShaderResources.load(capabilities, "vs_loading_scene"),
                BgfxShaderResources.load(capabilities, "fs_loading_scene")));
        shadowProgram = own(BgfxProgram.graphics(
                resources,
                "loading-scene/shadow",
                BgfxShaderResources.load(capabilities, "vs_loading_scene_shadow"),
                BgfxShaderResources.load(capabilities, "fs_loading_scene_shadow")));
        textureSampler = own(BgfxUniform.create(resources, "s_lsTexture", BGFX_UNIFORM_TYPE_SAMPLER, 1));
        shadowSampler = own(BgfxUniform.create(resources, "s_lsShadow", BGFX_UNIFORM_TYPE_SAMPLER, 1));
        sunMatrix = own(BgfxUniform.create(resources, "u_lsSunMatrix", BGFX_UNIFORM_TYPE_MAT4, 1));
        sun = vec4("u_lsSun");
        sunColor = vec4("u_lsSunColor");
        sky = vec4("u_lsSky");
        ground = vec4("u_lsGround");
        material = vec4("u_lsMaterial");
        fill = vec4("u_lsFill");
        tint = vec4("u_lsTint");
        filled = vec4("u_lsFilled");
        edge = vec4("u_lsEdge");
        haze = vec4("u_lsHaze");
        depthRules = vec4("u_lsDepth");
        groundLook = vec4("u_lsGroundLook");
        groundBelow = vec4("u_lsGroundBelow");
        shadowDepth = own(BgfxTexture.create2D(
                resources,
                "loading-scene/sun-depth",
                SUN_MAP_SIZE,
                SUN_MAP_SIZE,
                false,
                1,
                capabilities.depthStencilFormat(),
                BGFX_TEXTURE_RT,
                null));
        shadowTarget = own(BgfxFramebuffer.create(resources, "loading-scene/sun-target", List.of(shadowDepth)));
        sunMatrix(capabilities.homogeneousDepth(), sunProjection);
        sceneMatrix(capabilities.homogeneousDepth(), sceneProjection);
        depthRulesData[0] = capabilities.originBottomLeft() ? 0 : 1;
        depthRulesData[1] = capabilities.homogeneousDepth() ? 1 : 0;
        for (var batch : landmarks.batches()) {
            pending.add(() -> upload(batch));
            if (batch.group() == Group.GROUND) {
                landmarkLook[3] = BURIED_UNDER;
            }
        }
        for (var look : looks) {
            figures.add(null);
            var slot = figures.size() - 1;
            if (look != null) {
                pending.add(() -> figures.set(slot, upload(look)));
            }
        }
    }

    static void sunMatrix(boolean homogeneousDepth, float[] m) {
        var length = (float) Math.hypot(SUN[2], SUN[0]);
        float ax = -SUN[2] / length, az = SUN[0] / length;
        float bx = SUN[1] * az, by = SUN[2] * ax - SUN[0] * az, bz = -SUN[1] * ax;
        var scale = 1 / SUN_EXTENT;
        var depth = homogeneousDepth ? -1 / SUN_DEPTH : -.5f / SUN_DEPTH;
        var base = homogeneousDepth ? 0 : .5f;
        m[0] = ax * scale;
        m[1] = bx * scale;
        m[2] = SUN[0] * depth;
        m[3] = 0;
        m[4] = 0;
        m[5] = by * scale;
        m[6] = SUN[1] * depth;
        m[7] = 0;
        m[8] = az * scale;
        m[9] = bz * scale;
        m[10] = SUN[2] * depth;
        m[11] = 0;
        m[12] = 0;
        m[13] = -MIDDLE_HEIGHT * by * scale;
        m[14] = base - MIDDLE_HEIGHT * SUN[1] * depth;
        m[15] = 1;
    }

    static void sceneMatrix(boolean homogeneousDepth, float[] m) {
        var depth = homogeneousDepth ? -1 / SUN_DEPTH : -.5f / SUN_DEPTH;
        var halfHeight = (TOP - BOTTOM) * .5f;
        Arrays.fill(m, 0);
        m[0] = 1 / HALF_WIDTH;
        m[5] = 1 / halfHeight;
        m[10] = depth;
        m[13] = -(TOP + BOTTOM) * .5f / halfHeight;
        m[14] = homogeneousDepth ? 0 : .5f;
        m[15] = 1;
    }

    @Override
    public boolean prepare() {
        var deadline = System.nanoTime() + PREPARE_NANOS;
        while (!pending.isEmpty() && System.nanoTime() < deadline) {
            pending.poll().run();
        }
        return pending.isEmpty();
    }

    @Override
    public boolean isFlipped() {
        return device.getCapabilities().originBottomLeft();
    }

    @Override
    public long render(Frame frame) {
        ensureImage(frame.width(), frame.height());
        bgfx_set_view_name(shadowView, "loading-scene-sun");
        bgfx_set_view_rect(shadowView, 0, 0, SUN_MAP_SIZE, SUN_MAP_SIZE);
        bgfx_set_view_frame_buffer(shadowView, shadowTarget.getHandle());
        bgfx_set_view_clear(shadowView, BGFX_CLEAR_DEPTH, 0, 1f, 0);
        bgfx_set_view_transform(shadowView, IDENTITY, sunProjection);
        bgfx_touch(shadowView);
        bgfx_set_view_name(sceneView, "loading-scene");
        bgfx_set_view_rect(sceneView, 0, 0, frame.width(), frame.height());
        bgfx_set_view_frame_buffer(sceneView, target.getHandle());
        bgfx_set_view_clear(sceneView, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH, CLEAR_COLOR, 1f, 0);
        bgfx_set_view_transform(sceneView, IDENTITY, sceneProjection);
        bgfx_touch(sceneView);
        for (var part : drawn) {
            if (part.group() != Group.GLOW && part.group() != Group.GROUND) {
                place(part.group(), frame);
                drawLandmark(part, frame);
            }
        }
        for (var placement : frame.placements()) {
            var posed = figures.get(placement.figure());
            if (posed != null) {
                pose(posed, placement, frame.seconds());
                drawFigure(posed);
            }
        }
        for (var part : drawn) {
            if (part.group() == Group.GROUND) {
                drawGround(part);
            }
        }
        for (var part : drawn) {
            if (part.group() == Group.GLOW) {
                drawGlow(part, frame);
            }
        }
        return imageId;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeImage();
        RuntimeException failure = null;
        for (var i = owned.size() - 1; i >= 0; i--) {
            try {
                owned.get(i).close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = new IllegalStateException("Closing the loading scene", exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private <T extends AutoCloseable> T own(T resource) {
        owned.add(resource);
        return resource;
    }

    private BgfxUniform vec4(String name) {
        return own(BgfxUniform.create(device.getResources(), name, BGFX_UNIFORM_TYPE_VEC4, 1));
    }

    private void upload(Batch batch) {
        var bytes =
                ByteBuffer.allocateDirect(batch.vertices().length * Float.BYTES).order(ByteOrder.nativeOrder());
        bytes.asFloatBuffer().put(batch.vertices());
        var vertices =
                own(BgfxBuffer.staticVertex(device.getResources(), "loading-scene/" + batch.group(), bytes, LAYOUT, 0));
        drawn.add(new Drawn(batch.group(), vertices, texture("loading-scene/texture", batch.texture())));
    }

    private BgfxTexture texture(String label, Picture picture) {
        var rgba = ByteBuffer.allocateDirect(picture.rgba().length)
                .put(picture.rgba())
                .flip();
        return own(BgfxTexture.create2D(
                device.getResources(),
                label,
                picture.width(),
                picture.height(),
                true,
                1,
                BGFX_TEXTURE_FORMAT_RGBA8,
                0,
                BgfxUiMipmaps.createRgbaMipChain(rgba, picture.width(), picture.height())));
    }

    private Posed upload(Figure figure) {
        var wedges = figure.wedgePoints().length;
        var index32 = wedges > 0xffff;
        var triangles = figure.triangles();
        var indices = ByteBuffer.allocateDirect(triangles.length * (index32 ? Integer.BYTES : Short.BYTES))
                .order(ByteOrder.nativeOrder());
        for (var wedge : triangles) {
            if (index32) {
                indices.putInt(wedge);
            } else {
                indices.putShort((short) wedge);
            }
        }
        indices.flip();
        var posed = new Posed(
                figure,
                own(BgfxBuffer.dynamicVertex(device.getResources(), "loading-scene/figure", wedges, LAYOUT, 0)),
                own(BgfxBuffer.staticIndex(device.getResources(), "loading-scene/figure-indices", indices, index32, 0)),
                texture("loading-scene/figure-atlas", figure.atlas()));
        var uvs = figure.uvs();
        var floats = posed.staging.asFloatBuffer();
        for (var wedge = 0; wedge < wedges; wedge++) {
            floats.put(wedge * VERTEX_FLOATS + 6, uvs[wedge * 2]);
            floats.put(wedge * VERTEX_FLOATS + 7, uvs[wedge * 2 + 1]);
        }
        return posed;
    }

    private void ensureImage(int width, int height) {
        if (color != null && imageWidth == width && imageHeight == height) {
            return;
        }
        closeImage();
        var resources = device.getResources();
        color = BgfxTexture.create2D(
                resources,
                "loading-scene/image",
                width,
                height,
                false,
                1,
                BGFX_TEXTURE_FORMAT_RGBA8,
                BGFX_TEXTURE_RT | BGFX_TEXTURE_RT_MSAA_X4,
                null);
        depth = BgfxTexture.create2D(
                resources,
                "loading-scene/depth",
                width,
                height,
                false,
                1,
                device.getCapabilities().depthStencilFormat(),
                BGFX_TEXTURE_RT_WRITE_ONLY | BGFX_TEXTURE_RT_MSAA_X4,
                null);
        target = BgfxFramebuffer.create(resources, "loading-scene/target", List.of(color, depth));
        imageId = ui.registerTexture(color);
        imageWidth = width;
        imageHeight = height;
    }

    private void closeImage() {
        if (color == null) {
            return;
        }
        ui.unregisterTexture(imageId);
        target.close();
        depth.close();
        color.close();
        target = null;
        depth = null;
        color = null;
    }

    private void place(Group group, Frame frame) {
        System.arraycopy(IDENTITY, 0, transform, 0, 16);
        switch (group) {
            case ROCKS_LEFT -> transform[13] = frame.rockDrift();
            case ROCKS_RIGHT -> transform[13] = -frame.rockDrift();
            case TOI -> {
                transform[0] = transform[5] = transform[10] = frame.toiHeight();
                transform[12] = frame.toiX();
                transform[14] = frame.toiZ();
            }
            default -> {}
        }
    }

    private void drawLandmark(Drawn part, Frame frame) {
        var toi = part.group() == Group.TOI;
        materialData[0] = toi ? TOI_GAIN : TOWER_GAIN;
        materialData[1] = toi ? TOI_HAZE : 0;
        materialData[2] = toi ? 0 : FAR_HAZE;
        materialData[3] = 0;
        fillData[0] = frame.level();
        fillData[1] = frame.edge();
        fillData[2] = frame.pulse();
        fillData[3] = 1;
        submitLit(
                () -> bgfx_set_vertex_buffer(0, part.vertices().getHandle(), 0, -1),
                part.texture(),
                transform,
                materialData,
                fillData,
                STONE,
                landmarkLook,
                BGFX_STATE_WRITE_RGB
                        | BGFX_STATE_WRITE_A
                        | BGFX_STATE_WRITE_Z
                        | BGFX_STATE_DEPTH_TEST_LEQUAL
                        | BGFX_STATE_MSAA);
        submitShadow(() -> bgfx_set_vertex_buffer(0, part.vertices().getHandle(), 0, -1), part.texture(), transform);
    }

    private void drawGround(Drawn part) {
        materialData[0] = 1;
        materialData[1] = 0;
        materialData[2] = FAR_HAZE;
        materialData[3] = 0;
        submitLit(
                () -> bgfx_set_vertex_buffer(0, part.vertices().getHandle(), 0, -1),
                part.texture(),
                IDENTITY,
                materialData,
                noFill,
                STONE,
                groundLookData,
                BGFX_STATE_WRITE_RGB
                        | BGFX_STATE_WRITE_A
                        | BGFX_STATE_WRITE_Z
                        | BGFX_STATE_DEPTH_TEST_LEQUAL
                        | BGFX_STATE_BLEND_ALPHA
                        | BGFX_STATE_MSAA);
    }

    private void drawGlow(Drawn part, Frame frame) {
        materialData[0] = frame.glow();
        materialData[1] = 0;
        materialData[2] = 0;
        materialData[3] = 1;
        submitLit(
                () -> bgfx_set_vertex_buffer(0, part.vertices().getHandle(), 0, -1),
                part.texture(),
                IDENTITY,
                materialData,
                noFill,
                STONE,
                freeLook,
                BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A | BGFX_STATE_BLEND_ADD | BGFX_STATE_MSAA);
    }

    private void drawFigure(Posed posed) {
        materialData[0] = 1;
        materialData[1] = 0;
        materialData[2] = 0;
        materialData[3] = 0;
        Runnable geometry = () -> {
            bgfx_set_dynamic_vertex_buffer(0, posed.vertices.getHandle(), 0, posed.figure.wedgePoints().length);
            bgfx_set_index_buffer(posed.indices.getHandle(), 0, posed.figure.triangles().length);
        };
        submitLit(
                geometry,
                posed.atlas,
                IDENTITY,
                materialData,
                noFill,
                FIGURE,
                freeLook,
                BGFX_STATE_WRITE_RGB
                        | BGFX_STATE_WRITE_A
                        | BGFX_STATE_WRITE_Z
                        | BGFX_STATE_DEPTH_TEST_LEQUAL
                        | BGFX_STATE_MSAA);
        submitShadow(geometry, posed.atlas, IDENTITY);
    }

    private void submitLit(
            Runnable geometry,
            BgfxTexture texture,
            float[] model,
            float[] materialValues,
            float[] fillValues,
            float[] tintValue,
            float[] groundLookValue,
            long state) {
        geometry.run();
        bgfx_set_transform(model);
        bgfx_set_texture(0, textureSampler.getHandle(), texture.getHandle(), 0);
        bgfx_set_texture(1, shadowSampler.getHandle(), shadowDepth.getHandle(), (int) SHADOW_SAMPLER);
        bgfx_set_uniform(sunMatrix.getHandle(), sunProjection, 1);
        bgfx_set_uniform(sun.getHandle(), SUN, 1);
        bgfx_set_uniform(sunColor.getHandle(), SUN_COLOR, 1);
        bgfx_set_uniform(sky.getHandle(), SKY_COLOR, 1);
        bgfx_set_uniform(ground.getHandle(), GROUND_COLOR, 1);
        bgfx_set_uniform(material.getHandle(), materialValues, 1);
        bgfx_set_uniform(fill.getHandle(), fillValues, 1);
        bgfx_set_uniform(tint.getHandle(), tintValue, 1);
        bgfx_set_uniform(filled.getHandle(), FILLED, 1);
        bgfx_set_uniform(edge.getHandle(), EDGE, 1);
        bgfx_set_uniform(haze.getHandle(), HAZE_COLOR, 1);
        bgfx_set_uniform(depthRules.getHandle(), depthRulesData, 1);
        bgfx_set_uniform(groundLook.getHandle(), groundLookValue, 1);
        bgfx_set_uniform(groundBelow.getHandle(), GROUND_BELOW, 1);
        bgfx_set_state(state, 0);
        bgfx_submit(sceneView, litProgram.getHandle(), 0, BGFX_DISCARD_ALL);
    }

    private void submitShadow(Runnable geometry, BgfxTexture texture, float[] model) {
        geometry.run();
        bgfx_set_transform(model);
        bgfx_set_texture(0, textureSampler.getHandle(), texture.getHandle(), 0);
        bgfx_set_state(BGFX_STATE_WRITE_Z | BGFX_STATE_DEPTH_TEST_LEQUAL, 0);
        bgfx_submit(shadowView, shadowProgram.getHandle(), 0, BGFX_DISCARD_ALL);
    }

    private static void pose(Posed posed, Placement placement, float seconds) {
        var figure = posed.figure;
        figure.pose(seconds, posed.pose);
        float cos = (float) Math.cos(placement.turned()), sin = (float) Math.sin(placement.turned());
        var scale = placement.scale();
        var up = placement.upsideDown() ? -scale : scale;
        var pose = posed.pose;
        var placed = posed.placed;
        for (var i = 0; i < pose.length; i += 3) {
            float x = pose[i], y = pose[i + 1];
            placed[i] = placement.x() + (x * cos - y * sin) * scale;
            placed[i + 1] = placement.y() + pose[i + 2] * up;
            placed[i + 2] = placement.z() - (x * sin + y * cos) * scale;
        }
        var normals = posed.normals;
        Arrays.fill(normals, 0);
        var wedgePoints = figure.wedgePoints();
        var triangles = figure.triangles();
        for (var t = 0; t + 2 < triangles.length; t += 3) {
            int a = wedgePoints[triangles[t]] * 3,
                    b = wedgePoints[triangles[t + 1]] * 3,
                    c = wedgePoints[triangles[t + 2]] * 3;
            float ux = placed[b] - placed[a], uy = placed[b + 1] - placed[a + 1], uz = placed[b + 2] - placed[a + 2];
            float vx = placed[c] - placed[a], vy = placed[c + 1] - placed[a + 1], vz = placed[c + 2] - placed[a + 2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            for (var point : new int[] {a, b, c}) {
                normals[point] += nx;
                normals[point + 1] += ny;
                normals[point + 2] += nz;
            }
        }
        var floats = posed.staging.asFloatBuffer();
        for (var wedge = 0; wedge < wedgePoints.length; wedge++) {
            var point = wedgePoints[wedge] * 3;
            var at = wedge * VERTEX_FLOATS;
            float nx = normals[point], ny = normals[point + 1], nz = normals[point + 2];
            var length = Math.max(1e-12f, (float) Math.sqrt(nx * nx + ny * ny + nz * nz));
            floats.put(at, placed[point]);
            floats.put(at + 1, placed[point + 1]);
            floats.put(at + 2, placed[point + 2]);
            floats.put(at + 3, nx / length);
            floats.put(at + 4, ny / length);
            floats.put(at + 5, nz / length);
        }
        posed.vertices.update(0, posed.staging.duplicate().order(ByteOrder.nativeOrder()));
    }
}
