package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.viewer.render.CoronaSystem.Drawn;
import com.rost.cruma.viewer.render.bgfx.BgfxTextureCache.TextureRef;
import java.util.List;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXTransientIndexBuffer;
import org.lwjgl.bgfx.BGFXTransientVertexBuffer;
import org.lwjgl.system.MemoryStack;

final class BgfxCoronaRenderer implements AutoCloseable {
    private static final int CORNER_FLOATS = 9;
    private static final float[][] CORNER_UV = {{0f, 0f}, {1f, 0f}, {1f, 1f}, {0f, 1f}};
    private static final long STATE = BGFX.BGFX_STATE_WRITE_RGB
            | BGFX.BGFX_STATE_BLEND_FUNC(BGFX.BGFX_STATE_BLEND_ONE, BGFX.BGFX_STATE_BLEND_INV_SRC_COLOR)
            | BGFX.BGFX_STATE_MSAA;

    private final BgfxDevice device;
    private float[] corners = new float[0];
    private int[] quadOrder = new int[0];
    private @Nullable BgfxProgram program;
    private @Nullable BgfxUniform sampler;
    private @Nullable BgfxUniform params;
    private boolean closed;

    BgfxCoronaRenderer(BgfxDevice device) {
        this.device = device;
    }

    int render(int view, List<Drawn> drawn, Function<Texture, @Nullable TextureRef> textures) {
        if (closed || drawn.isEmpty()) {
            return 0;
        }
        ensureProgram();
        try (var stack = MemoryStack.stackPush()) {
            var count = drawn.size();
            var layout = BgfxSceneVertexLayouts.FX_RIBBON.createNative(stack);
            if (BGFX.bgfx_get_avail_transient_vertex_buffer(count * 4, layout) < count * 4
                    || BGFX.bgfx_get_avail_transient_index_buffer(count * 6, true) < count * 6) {
                return 0;
            }
            var vertices = BGFXTransientVertexBuffer.calloc(stack);
            var indices = BGFXTransientIndexBuffer.calloc(stack);
            if (!BGFX.bgfx_alloc_transient_buffers(vertices, layout, count * 4, indices, count * 6, true)) {
                return 0;
            }
            if (corners.length < count * 4 * CORNER_FLOATS) {
                corners = new float[count * 4 * CORNER_FLOATS];
            }
            var at = 0;
            for (var corona : drawn) {
                for (var corner = 0; corner < 4; corner++) {
                    corners[at] = corona.corners()[corner * 3];
                    corners[at + 1] = corona.corners()[corner * 3 + 1];
                    corners[at + 2] = corona.corners()[corner * 3 + 2];
                    corners[at + 3] = CORNER_UV[corner][0];
                    corners[at + 4] = CORNER_UV[corner][1];
                    corners[at + 5] = corona.red();
                    corners[at + 6] = corona.green();
                    corners[at + 7] = corona.blue();
                    corners[at + 8] = 1f;
                    at += CORNER_FLOATS;
                }
            }
            vertices.data().asFloatBuffer().put(corners, 0, at);
            indices.data().asIntBuffer().put(findQuadOrder(count), 0, count * 6);
            var draws = 0;
            for (var index = 0; index < count; index++) {
                var corona = drawn.get(index).corona();
                var texture = textures.apply(corona.texture());
                if (texture == null) {
                    continue;
                }
                BGFX.bgfx_set_texture(
                        0, sampler.getHandle(), texture.getTexture().getHandle(), texture.getSamplerFlags());
                BGFX.bgfx_set_uniform(
                        params.getHandle(), stack.floats(corona.isAlphaTested() ? 1f : 0f, 0f, 0f, 0f), 1);
                BGFX.bgfx_set_transient_vertex_buffer(0, vertices, 0, count * 4);
                BGFX.bgfx_set_transient_index_buffer(indices, index * 6, 6);
                BGFX.bgfx_set_state(STATE, 0);
                BGFX.bgfx_submit(view, program.getHandle(), 0, BGFX.BGFX_DISCARD_ALL);
                draws++;
            }
            return draws;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (program != null) {
            program.close();
            program = null;
        }
        if (sampler != null) {
            sampler.close();
            sampler = null;
        }
        if (params != null) {
            params.close();
            params = null;
        }
    }

    private void ensureProgram() {
        if (program != null) {
            return;
        }
        var resources = device.getResources();
        program = BgfxProgram.graphics(
                resources,
                "coronas",
                BgfxShaderResources.load(device.getCapabilities(), "vs_corona"),
                BgfxShaderResources.load(device.getCapabilities(), "fs_corona"));
        sampler = BgfxUniform.create(resources, "s_coronaTexture", BGFX.BGFX_UNIFORM_TYPE_SAMPLER, 1);
        params = BgfxUniform.create(resources, "u_coronaParams", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
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
}
