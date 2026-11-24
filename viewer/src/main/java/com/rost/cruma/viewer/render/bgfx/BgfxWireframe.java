package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_ACCESS_READ;
import static org.lwjgl.bgfx.BGFX.BGFX_ACCESS_WRITE;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_MSAA;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_PT_LINES;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_RGB;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_VEC4;
import static org.lwjgl.bgfx.BGFX.bgfx_set_compute_dynamic_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;

import com.rost.cruma.viewer.render.bgfx.BgfxSceneGeometry.Primitive;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneMaterial.ProgramFamily;

final class BgfxWireframe implements AutoCloseable {
    static final long STATE = BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A | BGFX_STATE_PT_LINES | BGFX_STATE_MSAA;

    static final int BACKGROUND = 0x171f27ff;

    private static final int TRIANGLES_PER_DISPATCH = 1 << 22;
    private final BgfxProgram lines;
    private final BgfxUniform range;
    private final BgfxUniform color;
    private final BgfxProgram entity;
    private final BgfxProgram water;
    private final BgfxProgram terrain;
    private final float[] rangeData = new float[4];
    private final float[] colorData = new float[4];
    private boolean closed;

    BgfxWireframe(BgfxDevice device, BgfxScenePrograms programs) {
        var resources = device.getResources();
        var capabilities = device.getCapabilities();
        BgfxProgram createdLines = null;
        BgfxUniform createdRange = null;
        BgfxUniform createdColor = null;
        BgfxProgram createdEntity = null;
        BgfxProgram createdWater = null;
        BgfxProgram createdTerrain = null;
        try {
            createdLines = BgfxProgram.compute(
                    resources,
                    capabilities,
                    "wireframe/lines",
                    BgfxShaderResources.load(capabilities, "cs_wireframe_lines"));
            createdRange = BgfxUniform.create(resources, "u_wireRange", BGFX_UNIFORM_TYPE_VEC4, 1);
            createdColor = BgfxUniform.create(resources, "u_wireColor", BGFX_UNIFORM_TYPE_VEC4, 1);
            createdEntity = BgfxProgram.graphics(
                    resources,
                    "wireframe/entity",
                    programs.entityVertexShader,
                    BgfxShaderResources.load(capabilities, "fs_wireframe_entity"));
            createdWater = BgfxProgram.graphics(
                    resources,
                    "wireframe/water",
                    programs.waterVertexShader,
                    BgfxShaderResources.load(capabilities, "fs_wireframe_water"));
            createdTerrain = BgfxProgram.graphics(
                    resources,
                    "wireframe/terrain",
                    programs.terrainVertexShader,
                    BgfxShaderResources.load(capabilities, "fs_wireframe_terrain"));
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(createdTerrain, failure);
            closeAfterFailure(createdWater, failure);
            closeAfterFailure(createdEntity, failure);
            closeAfterFailure(createdColor, failure);
            closeAfterFailure(createdRange, failure);
            closeAfterFailure(createdLines, failure);
            throw failure;
        }
        lines = createdLines;
        range = createdRange;
        color = createdColor;
        entity = createdEntity;
        water = createdWater;
        terrain = createdTerrain;
    }

    static boolean isSupported(BgfxCapabilities capabilities) {
        return capabilities.compute() && capabilities.index32();
    }

    enum Kind {
        TERRAIN(0x6EDE8C),
        WATER(0xA4B4C3),
        BSP(0xFFC85A),
        STATIC_MESH(0x5CDBF1);

        private final int rgb;

        Kind(int rgb) {
            this.rgb = rgb;
        }

        static Kind of(Primitive primitive) {
            var material = primitive.entityMaterial();
            if (material == null) {
                return TERRAIN;
            }
            return switch (material.getProgramFamily()) {
                case WATER -> WATER;
                case CSG -> BSP;
                case ORDINARY, COMPLEX -> STATIC_MESH;
            };
        }

        int getRgb() {
            return rgb;
        }
    }

    void build(BgfxBuffer triangles, BgfxBuffer target, int firstIndex, int triangleCount) {
        for (var done = 0; done < triangleCount; done += TRIANGLES_PER_DISPATCH) {
            var count = Math.min(TRIANGLES_PER_DISPATCH, triangleCount - done);
            var first = firstIndex + done * 3;
            rangeData[0] = first >>> 12;
            rangeData[1] = first & 4095;
            rangeData[2] = count;
            bgfx_set_uniform(range.getHandle(), rangeData, 1);
            bgfx_set_compute_dynamic_index_buffer(0, triangles.getHandle(), BGFX_ACCESS_READ);
            bgfx_set_compute_dynamic_index_buffer(1, target.getHandle(), BGFX_ACCESS_WRITE);
            BgfxComputeRows.dispatch(BgfxDevice.BUFFER_COPY_VIEW, lines, count);
        }
    }

    BgfxProgram getProgram(Primitive primitive) {
        var material = primitive.entityMaterial();
        if (material == null) {
            return terrain;
        }
        return material.getProgramFamily() == ProgramFamily.WATER ? water : entity;
    }

    void bindColor(Kind kind) {
        colorData[0] = (kind.getRgb() >>> 16 & 255) / 255f;
        colorData[1] = (kind.getRgb() >>> 8 & 255) / 255f;
        colorData[2] = (kind.getRgb() & 255) / 255f;
        colorData[3] = 1f;
        bgfx_set_uniform(color.getHandle(), colorData, 1);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        failure = closeAndMerge(terrain, failure);
        failure = closeAndMerge(water, failure);
        failure = closeAndMerge(entity, failure);
        failure = closeAndMerge(color, failure);
        failure = closeAndMerge(range, failure);
        failure = closeAndMerge(lines, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException closeAndMerge(AutoCloseable resource, RuntimeException failure) {
        try {
            resource.close();
        } catch (Exception closeFailure) {
            var runtime =
                    closeFailure instanceof RuntimeException value ? value : new IllegalStateException(closeFailure);
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
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
