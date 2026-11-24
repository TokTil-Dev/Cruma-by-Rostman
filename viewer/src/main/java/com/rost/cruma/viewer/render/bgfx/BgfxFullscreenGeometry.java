package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_POSITION;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD0;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_FLOAT;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.bgfx.BGFX;

final class BgfxFullscreenGeometry implements AutoCloseable {
    private static final int VERTEX_COUNT = 4;
    private static final int INDEX_COUNT = 6;

    private final BgfxBuffer vertices;
    private final BgfxBuffer indices;

    BgfxFullscreenGeometry(BgfxResourceRegistry resources, boolean originBottomLeft) {
        var layout = BgfxVertexLayoutSpec.builder()
                .add(BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT)
                .add(BGFX_ATTRIB_TEXCOORD0, 2, BGFX_ATTRIB_TYPE_FLOAT)
                .build();
        var topV = originBottomLeft ? 1f : 0f;
        var bottomV = originBottomLeft ? 0f : 1f;
        var vertexData =
                ByteBuffer.allocateDirect(VERTEX_COUNT * layout.getStride()).order(ByteOrder.nativeOrder());
        vertexData.asFloatBuffer().put(new float[] {
            -1f, 1f, 0f, 0f, topV, 1f, 1f, 0f, 1f, topV, 1f, -1f, 0f, 1f, bottomV, -1f, -1f, 0f, 0f, bottomV
        });
        var indexData = ByteBuffer.allocateDirect(INDEX_COUNT * Short.BYTES).order(ByteOrder.nativeOrder());
        indexData.asShortBuffer().put(new short[] {0, 1, 2, 0, 2, 3});
        vertices = BgfxBuffer.staticVertex(resources, "fullscreen/vertices", vertexData, layout, 0);
        indices = BgfxBuffer.staticIndex(resources, "fullscreen/indices", indexData, false, 0);
    }

    void bind() {
        BGFX.bgfx_set_vertex_buffer(0, vertices.getHandle(), 0, VERTEX_COUNT);
        BGFX.bgfx_set_index_buffer(indices.getHandle(), 0, INDEX_COUNT);
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            indices.close();
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            vertices.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
