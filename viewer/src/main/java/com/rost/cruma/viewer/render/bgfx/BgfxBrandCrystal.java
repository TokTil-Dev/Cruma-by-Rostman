package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_NORMAL;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_POSITION;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD0;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_FLOAT;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_DEPTH;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_INVALID_HANDLE;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIP_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_LEQUAL;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_RGB;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_Z;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT_WRITE_ONLY;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_SAMPLER;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_VEC4;
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

import com.rost.cruma.core.world.BrandCrystal;
import com.rost.cruma.core.world.LoadingScene.Picture;
import com.rost.cruma.viewer.render.api.ViewerBrandCrystal;
import imgui.ImGui;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class BgfxBrandCrystal implements ViewerBrandCrystal, AutoCloseable {
    static final int CELL = 128, CELLS = 4;

    static final float PITCH = .2f;

    private static final BgfxVertexLayoutSpec LAYOUT = BgfxVertexLayoutSpec.builder()
            .add(BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_NORMAL, 3, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD0, 2, BGFX_ATTRIB_TYPE_FLOAT)
            .build();
    private static final int VERTEX_FLOATS = 8;
    private static final int WIDTH = CELL * CELLS;
    private static final int SUPER = 4;
    private static final float EXTENT = (float) Math.cos(PITCH) * MARK_UNITS / (MARK_UNITS - 2 * TIP_MARGIN);
    private static final float REACH = 1.75f;
    private static final float[] LIGHT = {-.55f, .65f, -.52f};
    private static final float SHADE = .55f;
    private static final float RING = .1f;
    private static final long POINT_SAMPLER = BGFX_SAMPLER_U_CLAMP
            | BGFX_SAMPLER_V_CLAMP
            | BGFX_SAMPLER_MIN_POINT
            | BGFX_SAMPLER_MAG_POINT
            | BGFX_SAMPLER_MIP_POINT;
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    private final BgfxDevice device;
    private final BgfxViewerUiRenderer ui;
    private final int sceneView;
    private final int resolveView;
    private final BgfxProgram sceneProgram;
    private final BgfxProgram resolveProgram;
    private final BgfxUniform bodySampler;
    private final BgfxUniform sheenSampler;
    private final BgfxUniform sceneSampler;
    private final BgfxUniform sheenPivot;
    private final BgfxUniform sheenAngles;
    private final BgfxUniform light;
    private final BgfxUniform viewer;
    private final BgfxUniform empty;
    private final BgfxBuffer vertices;
    private final BgfxTexture body;
    private final BgfxTexture sheen;
    private final BgfxTexture sceneColor;
    private final BgfxFramebuffer sceneTarget;
    private final BgfxTexture image;
    private final BgfxFramebuffer imageTarget;
    private final BgfxFullscreenGeometry fullscreen;
    private final long imageId;
    private final float yaw;
    private final boolean bottomUp;
    private final boolean homogeneousDepth;
    private final float[] sheenRates = new float[3];
    private final float[] sheenPivotData = new float[4];
    private final float[] sheenAnglesData = new float[4];
    private final float[] lightData = new float[4];
    private final float[] viewerData = new float[4];
    private final float[] emptyData = new float[4];
    private final float[] transform = new float[16];
    private final int[] slotPixels = new int[CELLS];
    private final int[] slotFrames = new int[CELLS];
    private final @Nullable Drawn[] slotDrawn = new Drawn[CELLS];
    private final List<AutoCloseable> owned = new ArrayList<>();
    private int shownFrame = Integer.MIN_VALUE;
    private boolean closed;

    BgfxBrandCrystal(BgfxDevice device, BgfxViewerUiRenderer ui, BrandCrystal crystal) {
        this.device = device;
        this.ui = ui;
        var resources = device.getResources();
        var capabilities = device.getCapabilities();
        sceneView = device.getUiViewId() - 4;
        resolveView = device.getUiViewId() - 3;
        bottomUp = capabilities.originBottomLeft();
        homogeneousDepth = capabilities.homogeneousDepth();
        try {
            sceneProgram = own(BgfxProgram.graphics(
                    resources,
                    "brand-crystal/scene",
                    BgfxShaderResources.load(capabilities, "vs_brand_crystal"),
                    BgfxShaderResources.load(capabilities, "fs_brand_crystal")));
            resolveProgram = own(BgfxProgram.graphics(
                    resources,
                    "brand-crystal/resolve",
                    BgfxShaderResources.load(capabilities, "vs_fullscreen"),
                    BgfxShaderResources.load(capabilities, "fs_brand_crystal_resolve")));
            bodySampler = own(BgfxUniform.create(resources, "s_bcBody", BGFX_UNIFORM_TYPE_SAMPLER, 1));
            sheenSampler = own(BgfxUniform.create(resources, "s_bcSheen", BGFX_UNIFORM_TYPE_SAMPLER, 1));
            sceneSampler = own(BgfxUniform.create(resources, "s_bcScene", BGFX_UNIFORM_TYPE_SAMPLER, 1));
            sheenPivot = own(BgfxUniform.create(resources, "u_bcSheenPivot", BGFX_UNIFORM_TYPE_VEC4, 1));
            sheenAngles = own(BgfxUniform.create(resources, "u_bcSheenAngles", BGFX_UNIFORM_TYPE_VEC4, 1));
            light = own(BgfxUniform.create(resources, "u_bcLight", BGFX_UNIFORM_TYPE_VEC4, 1));
            viewer = own(BgfxUniform.create(resources, "u_bcViewer", BGFX_UNIFORM_TYPE_VEC4, 1));
            empty = own(BgfxUniform.create(resources, "u_bcEmpty", BGFX_UNIFORM_TYPE_VEC4, 1));
            var bytes = ByteBuffer.allocateDirect(crystal.vertices().length * Float.BYTES)
                    .order(ByteOrder.nativeOrder());
            bytes.asFloatBuffer().put(crystal.vertices());
            vertices = own(BgfxBuffer.staticVertex(resources, "brand-crystal/vertices", bytes, LAYOUT, 0));
            body = texture("brand-crystal/body", crystal.body());
            sheen = crystal.sheen() == null ? body : texture("brand-crystal/sheen", crystal.sheen());
            sceneColor = own(BgfxTexture.create2D(
                    resources,
                    "brand-crystal/scene-color",
                    WIDTH * SUPER,
                    CELL * SUPER,
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA8,
                    BGFX_TEXTURE_RT,
                    null));
            var sceneDepth = own(BgfxTexture.create2D(
                    resources,
                    "brand-crystal/scene-depth",
                    WIDTH * SUPER,
                    CELL * SUPER,
                    false,
                    1,
                    capabilities.depthStencilFormat(),
                    BGFX_TEXTURE_RT_WRITE_ONLY,
                    null));
            sceneTarget =
                    own(BgfxFramebuffer.create(resources, "brand-crystal/scene", List.of(sceneColor, sceneDepth)));
            image = own(BgfxTexture.create2D(
                    resources,
                    "brand-crystal/image",
                    WIDTH,
                    CELL,
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA8,
                    BGFX_TEXTURE_RT,
                    null));
            imageTarget = own(BgfxFramebuffer.create(resources, "brand-crystal/image-target", List.of(image)));
            fullscreen = own(new BgfxFullscreenGeometry(resources, bottomUp));
        } catch (RuntimeException | Error failure) {
            closeOwned(failure);
            throw failure;
        }
        imageId = ui.registerTexture(image);
        yaw = frontYaw(crystal.vertices());
        light(yaw, lightData);
        float[] right = new float[3], up = new float[3], away = new float[3];
        axes(yaw, right, up, away);
        for (var axis = 0; axis < 3; axis++) {
            viewerData[axis] = -away[axis];
        }
        meanColor(crystal, emptyData);
        Arrays.fill(slotFrames, Integer.MIN_VALUE);
        var turn = crystal.sheenTurn();
        if (crystal.sheen() != null) {
            sheenPivotData[2] = 1;
            if (turn.mode() == 2) {
                sheenPivotData[0] = turn.paramX();
                sheenPivotData[1] = turn.paramY();
                sheenRates[0] = turn.paramW();
                sheenRates[1] = turn.paramZ();
                sheenRates[2] = turn.extra();
            }
        }
    }

    static float frontYaw(float[] vertices) {
        var found = false;
        float frontX = 0, frontY = -1, nearest = -2;
        for (var at = 0; at + VERTEX_FLOATS <= vertices.length; at += VERTEX_FLOATS) {
            float x = vertices[at], y = vertices[at + 1];
            var length = (float) Math.hypot(x, y);
            if (Math.abs(vertices[at + 2]) < RING && length > RING && -y / length > nearest) {
                nearest = -y / length;
                frontX = x;
                frontY = y;
                found = true;
            }
        }
        return found ? (float) Math.atan2(-frontY, -frontX) : (float) (Math.PI / 2);
    }

    static void axes(float yaw, float[] right, float[] up, float[] away) {
        float cosYaw = (float) Math.cos(yaw), sinYaw = (float) Math.sin(yaw);
        float cosPitch = (float) Math.cos(PITCH), sinPitch = (float) Math.sin(PITCH);
        right[0] = -sinYaw;
        right[1] = cosYaw;
        right[2] = 0;
        up[0] = cosYaw * sinPitch;
        up[1] = sinYaw * sinPitch;
        up[2] = cosPitch;
        away[0] = cosYaw * cosPitch;
        away[1] = sinYaw * cosPitch;
        away[2] = -sinPitch;
    }

    static void place(float[] m, float yaw, int slot, int pixels, boolean bottomUp, boolean homogeneousDepth) {
        float[] right = new float[3], up = new float[3], away = new float[3];
        axes(yaw, right, up, away);
        float across = pixels / (WIDTH * EXTENT), along = pixels / (CELL * EXTENT);
        var depth = (homogeneousDepth ? 1 : .5f) / REACH;
        for (var axis = 0; axis < 3; axis++) {
            m[axis * 4] = right[axis] * across;
            m[axis * 4 + 1] = up[axis] * along;
            m[axis * 4 + 2] = away[axis] * depth;
            m[axis * 4 + 3] = 0;
        }
        m[12] = -1 + (2f * slot * CELL + pixels) / WIDTH;
        m[13] = (bottomUp ? -1 : 1) * (1 - pixels / (float) CELL);
        m[14] = homogeneousDepth ? 0 : .5f;
        m[15] = 1;
    }

    static void light(float yaw, float[] out) {
        float[] right = new float[3], up = new float[3], away = new float[3];
        axes(yaw, right, up, away);
        var length = (float) Math.sqrt(LIGHT[0] * LIGHT[0] + LIGHT[1] * LIGHT[1] + LIGHT[2] * LIGHT[2]);
        for (var axis = 0; axis < 3; axis++) {
            out[axis] = (LIGHT[0] * right[axis] + LIGHT[1] * up[axis] + LIGHT[2] * away[axis]) / length;
        }
        out[3] = SHADE;
    }

    static void meanColor(BrandCrystal crystal, float[] out) {
        var body = crystal.body().rgba();
        var sheen = crystal.sheen() == null ? new byte[0] : crystal.sheen().rgba();
        for (var channel = 0; channel < 3; channel++) {
            double sum = 0, weight = 0, added = 0;
            for (var at = 0; at + 3 < body.length; at += 4) {
                var alpha = Byte.toUnsignedInt(body[at + 3]);
                sum += Byte.toUnsignedInt(body[at + channel]) * alpha;
                weight += alpha * 255;
            }
            for (var at = 0; at + 3 < sheen.length; at += 4) {
                added += Byte.toUnsignedInt(sheen[at + channel]);
            }
            var mean = (weight > 0 ? sum / weight : 0) + (sheen.length > 0 ? added / (sheen.length / 4 * 255.0) : 0);
            out[channel] = (float) Math.min(1, mean);
        }
    }

    static float sheenAngle(float rate, double seconds) {
        return (float) Math.IEEEremainder(rate * seconds, 2 * Math.PI);
    }

    @Override
    public boolean isMoving() {
        return !closed && (sheenRates[0] != 0 || sheenRates[1] != 0 || sheenRates[2] != 0);
    }

    @Override
    public @Nullable Drawn draw(int pixels, double seconds) {
        if (closed) {
            return null;
        }
        var size = Math.clamp(pixels, 1, CELL);
        var frame = ImGui.getFrameCount();
        if (frame != shownFrame) {
            shownFrame = frame;
            beginFrame();
        }
        var slot = slotOf(size, frame);
        if (slotFrames[slot] != frame) {
            slotFrames[slot] = frame;
            if (slotPixels[slot] != size) {
                slotPixels[slot] = size;
                slotDrawn[slot] = drawnOf(slot, size);
            }
            submit(slot, size, seconds);
        }
        return slotDrawn[slot];
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ui.unregisterTexture(imageId);
        bgfx_set_view_frame_buffer(sceneView, BGFX_INVALID_HANDLE);
        bgfx_set_view_frame_buffer(resolveView, BGFX_INVALID_HANDLE);
        var failure = closeOwned(null);
        if (failure != null) {
            throw new IllegalStateException("Closing the mark's crystal", failure);
        }
    }

    private <T extends AutoCloseable> T own(T resource) {
        owned.add(resource);
        return resource;
    }

    private @Nullable Throwable closeOwned(@Nullable Throwable failure) {
        for (var i = owned.size() - 1; i >= 0; i--) {
            try {
                owned.get(i).close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        owned.clear();
        return failure;
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

    private void beginFrame() {
        bgfx_set_view_name(sceneView, "brand-crystal");
        bgfx_set_view_rect(sceneView, 0, 0, WIDTH * SUPER, CELL * SUPER);
        bgfx_set_view_frame_buffer(sceneView, sceneTarget.getHandle());
        bgfx_set_view_clear(sceneView, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH, 0, 1f, 0);
        bgfx_set_view_transform(sceneView, IDENTITY, IDENTITY);
        bgfx_touch(sceneView);
        bgfx_set_view_name(resolveView, "brand-crystal-resolve");
        bgfx_set_view_rect(resolveView, 0, 0, WIDTH, CELL);
        bgfx_set_view_frame_buffer(resolveView, imageTarget.getHandle());
        bgfx_set_view_clear(resolveView, BGFX_CLEAR_NONE, 0, 1f, 0);
        bgfx_set_view_transform(resolveView, IDENTITY, IDENTITY);
        fullscreen.bind();
        bgfx_set_texture(0, sceneSampler.getHandle(), sceneColor.getHandle(), (int) POINT_SAMPLER);
        bgfx_set_uniform(empty.getHandle(), emptyData, 1);
        bgfx_set_state(BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A, 0);
        bgfx_submit(resolveView, resolveProgram.getHandle(), 0, BGFX_DISCARD_ALL);
    }

    private int slotOf(int size, int frame) {
        int oldest = -1, nearest = 0;
        for (var slot = 0; slot < CELLS; slot++) {
            if (slotPixels[slot] == size) {
                return slot;
            }
            if (slotFrames[slot] != frame && (oldest < 0 || slotFrames[slot] < slotFrames[oldest])) {
                oldest = slot;
            }
            if (Math.abs(slotPixels[slot] - size) < Math.abs(slotPixels[nearest] - size)) {
                nearest = slot;
            }
        }
        return oldest >= 0 ? oldest : nearest;
    }

    private Drawn drawnOf(int slot, int size) {
        float u0 = slot * CELL / (float) WIDTH, u1 = (slot * CELL + size) / (float) WIDTH, v = size / (float) CELL;
        return bottomUp ? new Drawn(imageId, u0, v, u1, 0) : new Drawn(imageId, u0, 0, u1, v);
    }

    private void submit(int slot, int size, double seconds) {
        place(transform, yaw, slot, size, bottomUp, homogeneousDepth);
        for (var axis = 0; axis < 3; axis++) {
            sheenAnglesData[axis] = sheenAngle(sheenRates[axis], seconds);
        }
        bgfx_set_vertex_buffer(0, vertices.getHandle(), 0, -1);
        bgfx_set_transform(transform);
        bgfx_set_texture(0, bodySampler.getHandle(), body.getHandle(), 0);
        bgfx_set_texture(1, sheenSampler.getHandle(), sheen.getHandle(), 0);
        bgfx_set_uniform(sheenPivot.getHandle(), sheenPivotData, 1);
        bgfx_set_uniform(sheenAngles.getHandle(), sheenAnglesData, 1);
        bgfx_set_uniform(light.getHandle(), lightData, 1);
        bgfx_set_uniform(viewer.getHandle(), viewerData, 1);
        bgfx_set_state(
                BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A | BGFX_STATE_WRITE_Z | BGFX_STATE_DEPTH_TEST_LEQUAL, 0);
        bgfx_submit(sceneView, sceneProgram.getHandle(), 0, BGFX_DISCARD_ALL);
    }
}
