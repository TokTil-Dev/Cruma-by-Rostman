package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.viewer.geodata.GeoOverlayLines;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXTransientIndexBuffer;
import org.lwjgl.bgfx.BGFXTransientVertexBuffer;
import org.lwjgl.system.MemoryStack;

final class BgfxOverlayLinePass implements AutoCloseable {
    static final BgfxVertexLayoutSpec LINE_LAYOUT = BgfxVertexLayoutSpec.builder()
            .add(BGFX.BGFX_ATTRIB_POSITION, 3, BGFX.BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX.BGFX_ATTRIB_COLOR0, 4, BGFX.BGFX_ATTRIB_TYPE_UINT8, true, false)
            .add(BGFX.BGFX_ATTRIB_TEXCOORD1, 4, BGFX.BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX.BGFX_ATTRIB_TEXCOORD2, 4, BGFX.BGFX_ATTRIB_TYPE_FLOAT)
            .build();

    static final float LINE_TOLERANCE = 24;

    static final float EDGE_TOLERANCE = 6;

    static final float MODEL_TOLERANCE = 4;

    private static final int ASK_ALL = 1 << 20;

    private static final int RESERVE_VERTICES = 21_845;

    private static final int RESERVE_INDICES = 131_072;

    private static final float TOLERANCE_GROWTH = 1f / 4096f;

    private static final float NEAR_CLIP = 2;

    private static final int CORNER_FLOATS = 12;

    private static final int CLAMP_POINT = BGFX.BGFX_SAMPLER_U_CLAMP
            | BGFX.BGFX_SAMPLER_V_CLAMP
            | BGFX.BGFX_SAMPLER_MIN_POINT
            | BGFX.BGFX_SAMPLER_MAG_POINT;
    private final BgfxDevice device;
    private final float[] projectionTerms = new float[16];

    private float[] corners = new float[0];
    private int[] quadOrder = new int[0];
    private BgfxProgram lineProgram;
    private BgfxUniform lineViewUniform;
    private BgfxUniform projectionUniform;
    private BgfxUniform heightUniform;
    private BgfxUniform depthSampler;
    private BgfxUniform modelDepthSampler;

    BgfxOverlayLinePass(BgfxDevice device) {
        this.device = device;
    }

    void submit(
            int view, Camera camera, Extent extent, BgfxTexture sceneDepth, GeoOverlayLines lines, MemoryStack stack) {
        submit(view, camera, extent, sceneDepth, null, lines, 1, stack);
    }

    void submitEdges(
            int view,
            Camera camera,
            Extent extent,
            BgfxTexture sceneDepth,
            @Nullable BgfxTexture modelDepth,
            GeoOverlayLines edges,
            MemoryStack stack) {
        submit(
                view,
                camera,
                extent,
                sceneDepth,
                modelDepth,
                edges,
                1,
                camera.isOrtho() ? 0 : TOLERANCE_GROWTH,
                EDGE_TOLERANCE,
                stack);
    }

    void submit(
            int view,
            Camera camera,
            Extent extent,
            BgfxTexture sceneDepth,
            @Nullable BgfxTexture modelDepth,
            GeoOverlayLines lines,
            float hiddenOpacity,
            MemoryStack stack) {
        submit(
                view,
                camera,
                extent,
                sceneDepth,
                modelDepth,
                lines,
                hiddenOpacity,
                TOLERANCE_GROWTH,
                LINE_TOLERANCE,
                stack);
    }

    void submitMarks(
            int view,
            Camera camera,
            Extent extent,
            BgfxTexture sceneDepth,
            @Nullable BgfxTexture modelDepth,
            GeoOverlayLines marks,
            MemoryStack stack) {
        submit(
                view,
                camera,
                extent,
                sceneDepth,
                modelDepth,
                marks,
                1,
                camera.isOrtho() ? 0 : TOLERANCE_GROWTH,
                LINE_TOLERANCE,
                stack);
    }

    @Override
    public void close() {
        if (lineProgram != null) {
            lineProgram.close();
            lineProgram = null;
        }
        for (var uniform :
                new BgfxUniform[] {lineViewUniform, projectionUniform, heightUniform, depthSampler, modelDepthSampler
                }) {
            if (uniform != null) {
                uniform.close();
            }
        }
        lineViewUniform = null;
        projectionUniform = null;
        heightUniform = null;
        depthSampler = null;
        modelDepthSampler = null;
    }

    private void submit(
            int view,
            Camera camera,
            Extent extent,
            BgfxTexture sceneDepth,
            @Nullable BgfxTexture modelDepth,
            GeoOverlayLines lines,
            float hiddenOpacity,
            float toleranceGrowth,
            float tolerance,
            MemoryStack stack) {
        if (lineProgram == null) {
            var resources = device.getResources();
            lineProgram = BgfxProgram.graphics(
                    resources,
                    "geodata/lines",
                    BgfxShaderResources.load(device.getCapabilities(), "vs_geodata_line"),
                    BgfxShaderResources.load(device.getCapabilities(), "fs_geodata_line"));
            lineViewUniform = BgfxUniform.create(resources, "u_geodataLineView", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
            projectionUniform = BgfxUniform.create(resources, "u_geodataProjection", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
            heightUniform = BgfxUniform.create(resources, "u_geodataHeight", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
            depthSampler = BgfxUniform.create(resources, "s_geodataDepth", BGFX.BGFX_UNIFORM_TYPE_SAMPLER, 1);
            modelDepthSampler = BgfxUniform.create(resources, "s_geodataModelDepth", BGFX.BGFX_UNIFORM_TYPE_SAMPLER, 1);
        }
        var data = lines.getShown();
        var count = lines.getShownCount();
        var pixelsPerPoint = extent.width() / Math.max(1f, lines.getShownPoints());
        var eye = camera.getPosition();
        var forward = camera.getCursorRayDirection(0, 0);
        count = Math.min(
                count,
                Math.min(
                        Math.max(
                                        0,
                                        BGFX.bgfx_get_avail_transient_vertex_buffer(
                                                        ASK_ALL, LINE_LAYOUT.createNative(stack))
                                                - RESERVE_VERTICES)
                                / 4,
                        Math.max(0, BGFX.bgfx_get_avail_transient_index_buffer(ASK_ALL, true) - RESERVE_INDICES) / 6));
        if (count == 0) {
            return;
        }
        var vertices = BGFXTransientVertexBuffer.calloc(stack);
        var indices = BGFXTransientIndexBuffer.calloc(stack);
        if (!BGFX.bgfx_alloc_transient_buffers(
                vertices, LINE_LAYOUT.createNative(stack), count * 4, indices, count * 6, true)) {
            return;
        }
        if (corners.length < count * 4 * CORNER_FLOATS) {
            corners = new float[count * 4 * CORNER_FLOATS];
        }
        var eyeX = eye.getX();
        var eyeY = eye.getY();
        var eyeZ = eye.getZ();
        var forwardX = forward.getX();
        var forwardY = forward.getY();
        var forwardZ = forward.getZ();
        var at = 0;
        for (var index = 0; index < count; index++) {
            var offset = index * GeoOverlayLines.FLOATS;
            var ax = data[offset];
            var ay = data[offset + 1];
            var az = data[offset + 2];
            var bx = data[offset + 3];
            var by = data[offset + 4];
            var bz = data[offset + 5];
            var width = data[offset + 6] * pixelsPerPoint;
            var color = data[offset + 7];
            var kind = data[offset + 8];
            var dot = kind != GeoOverlayLines.LINE;
            var length = data[offset + 9];
            var depthA = (ax - eyeX) * forwardX + (ay - eyeY) * forwardY + (az - eyeZ) * forwardZ;
            var depthB = (bx - eyeX) * forwardX + (by - eyeY) * forwardY + (bz - eyeZ) * forwardZ;
            if (depthA < NEAR_CLIP && depthB < NEAR_CLIP) {
                continue;
            }
            var startAlong = 0f;
            var endAlong = length;
            if (depthA < NEAR_CLIP) {
                var t = (NEAR_CLIP - depthA) / (depthB - depthA);
                ax += (bx - ax) * t;
                ay += (by - ay) * t;
                az += (bz - az) * t;
                startAlong = length * t;
            } else if (depthB < NEAR_CLIP) {
                var t = (NEAR_CLIP - depthB) / (depthA - depthB);
                bx += (ax - bx) * t;
                by += (ay - by) * t;
                bz += (az - bz) * t;
                endAlong = length * (1 - t);
            }
            if (dot) {
                at = putCorner(corners, at, ax, ay, az, color, ax, ay, az, -1, width, 0, kind, -1);
                at = putCorner(corners, at, ax, ay, az, color, ax, ay, az, 1, width, 0, kind, -1);
                at = putCorner(corners, at, ax, ay, az, color, ax, ay, az, 1, width, 0, kind, 1);
                at = putCorner(corners, at, ax, ay, az, color, ax, ay, az, -1, width, 0, kind, 1);
            } else {
                at = putCorner(corners, at, ax, ay, az, color, bx, by, bz, -1, width, startAlong, 0, 0);
                at = putCorner(corners, at, ax, ay, az, color, bx, by, bz, 1, width, startAlong, 0, 0);
                at = putCorner(corners, at, bx, by, bz, color, ax, ay, az, 1, width, endAlong, 0, 1);
                at = putCorner(corners, at, bx, by, bz, color, ax, ay, az, -1, width, endAlong, 0, 1);
            }
        }
        var written = at / (4 * CORNER_FLOATS);
        if (written == 0) {
            return;
        }
        vertices.data().asFloatBuffer().put(corners, 0, at);
        indices.data().asIntBuffer().put(findQuadOrder(written), 0, written * 6);
        var source = camera.getProjectionMatrix().duplicate();
        source.position(0).limit(16);
        source.get(projectionTerms, 0, 16);
        BGFX.bgfx_set_uniform(
                lineViewUniform.getHandle(),
                stack.floats(2f / extent.width(), 2f / extent.height(), toleranceGrowth, 0),
                1);
        BGFX.bgfx_set_uniform(
                projectionUniform.getHandle(),
                stack.floats(projectionTerms[10], projectionTerms[11], projectionTerms[14], projectionTerms[15]),
                1);
        BGFX.bgfx_set_uniform(
                heightUniform.getHandle(),
                stack.floats(
                        sceneDepth != null && modelDepth != null ? 1 : 0,
                        MODEL_TOLERANCE,
                        hiddenOpacity,
                        sceneDepth == null ? 0 : tolerance),
                1);
        if (sceneDepth != null) {
            BGFX.bgfx_set_texture(0, depthSampler.getHandle(), sceneDepth.getHandle(), CLAMP_POINT);
            if (modelDepth != null) {
                BGFX.bgfx_set_texture(1, modelDepthSampler.getHandle(), modelDepth.getHandle(), CLAMP_POINT);
            }
        }
        BGFX.bgfx_set_transient_vertex_buffer(0, vertices, 0, written * 4);
        BGFX.bgfx_set_transient_index_buffer(indices, 0, written * 6);
        BGFX.bgfx_set_state(
                BGFX.BGFX_STATE_WRITE_RGB
                        | BGFX.BGFX_STATE_WRITE_A
                        | BGFX.BGFX_STATE_BLEND_ALPHA
                        | BGFX.BGFX_STATE_MSAA,
                0);
        BGFX.bgfx_submit(view, lineProgram.getHandle(), 0, BGFX.BGFX_DISCARD_ALL);
    }

    private int[] findQuadOrder(int quads) {
        if (quadOrder.length < quads * 6) {
            quadOrder = new int[Math.max(quads, quadOrder.length / 3) * 6];
            for (var quad = 0; quad * 6 < quadOrder.length; quad++) {
                var first = quad * 4;
                var offset = quad * 6;
                quadOrder[offset] = first;
                quadOrder[offset + 1] = first + 1;
                quadOrder[offset + 2] = first + 2;
                quadOrder[offset + 3] = first;
                quadOrder[offset + 4] = first + 2;
                quadOrder[offset + 5] = first + 3;
            }
        }
        return quadOrder;
    }

    private static int putCorner(
            float[] out,
            int at,
            float x,
            float y,
            float z,
            float color,
            float otherX,
            float otherY,
            float otherZ,
            float side,
            float width,
            float along,
            float kind,
            float last) {
        out[at] = x;
        out[at + 1] = y;
        out[at + 2] = z;
        out[at + 3] = color;
        out[at + 4] = otherX;
        out[at + 5] = otherY;
        out[at + 6] = otherZ;
        out[at + 7] = side;
        out[at + 8] = width;
        out[at + 9] = along;
        out[at + 10] = kind;
        out[at + 11] = last;
        return at + CORNER_FLOATS;
    }
}
