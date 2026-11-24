package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIP_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_ALPHA;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_MSAA;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_RGB;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_SAMPLER;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_VEC4;
import static org.lwjgl.bgfx.BGFX.bgfx_set_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;

import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneGeometry.Primitive;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneMaterial.ProgramFamily;
import java.util.List;

final class BgfxHoverHighlight implements AutoCloseable {
    static final long STATE = BGFX_STATE_WRITE_RGB | BGFX_STATE_BLEND_ALPHA | BGFX_STATE_MSAA;

    private static final int DEPTH_STAGE = 13;
    private static final float[] COLOR = {0x5C / 255f, 0xDB / 255f, 0xF1 / 255f, .38f};

    private final BgfxResourceRegistry resources;
    private final BgfxProgram entity;
    private final BgfxProgram terrain;
    private final BgfxUniform color;
    private final BgfxUniform projection;
    private final BgfxUniform depthSampler;
    private final float[] projectionTerms = new float[16];
    private final float[] projectionData = new float[4];
    private BgfxFramebuffer framebuffer;
    private BgfxTexture framebufferColor;
    private boolean closed;

    BgfxHoverHighlight(BgfxDevice device, BgfxScenePrograms programs) {
        resources = device.getResources();
        var capabilities = device.getCapabilities();
        BgfxProgram createdEntity = null;
        BgfxProgram createdTerrain = null;
        BgfxUniform createdColor = null;
        BgfxUniform createdProjection = null;
        try {
            createdEntity = BgfxProgram.graphics(
                    resources,
                    "hover/entity",
                    programs.entityVertexShader,
                    BgfxShaderResources.load(capabilities, "fs_hover_entity"));
            createdTerrain = BgfxProgram.graphics(
                    resources,
                    "hover/terrain",
                    programs.terrainVertexShader,
                    BgfxShaderResources.load(capabilities, "fs_hover_terrain"));
            createdColor = BgfxUniform.create(resources, "u_hoverColor", BGFX_UNIFORM_TYPE_VEC4, 1);
            createdProjection = BgfxUniform.create(resources, "u_hoverProjection", BGFX_UNIFORM_TYPE_VEC4, 1);
            depthSampler = BgfxUniform.create(resources, "s_hoverDepth", BGFX_UNIFORM_TYPE_SAMPLER, 1);
        } catch (RuntimeException | Error failure) {
            for (var created : new AutoCloseable[] {createdProjection, createdColor, createdTerrain, createdEntity}) {
                try {
                    if (created != null) {
                        created.close();
                    }
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
        entity = createdEntity;
        terrain = createdTerrain;
        color = createdColor;
        projection = createdProjection;
    }

    BgfxFramebuffer getFramebuffer(BgfxTexture target) {
        if (framebuffer == null || framebufferColor != target) {
            if (framebuffer != null) {
                framebuffer.close();
            }
            framebuffer = BgfxFramebuffer.create(resources, "hover/framebuffer", List.of(target));
            framebufferColor = target;
        }
        return framebuffer;
    }

    BgfxProgram getProgram(Primitive primitive) {
        var material = primitive.entityMaterial();
        if (material == null) {
            return terrain;
        }
        return material.getProgramFamily() == ProgramFamily.WATER ? null : entity;
    }

    void bind(BgfxTexture depth, Camera camera) {
        var source = camera.getProjectionMatrix().duplicate();
        source.position(0).limit(16);
        source.get(projectionTerms, 0, 16);
        projectionData[0] = projectionTerms[10];
        projectionData[1] = projectionTerms[11];
        projectionData[2] = projectionTerms[14];
        projectionData[3] = projectionTerms[15];
        bgfx_set_uniform(projection.getHandle(), projectionData, 1);
        bgfx_set_uniform(color.getHandle(), COLOR, 1);
        bgfx_set_texture(
                DEPTH_STAGE,
                depthSampler.getHandle(),
                depth.getHandle(),
                BGFX_SAMPLER_U_CLAMP
                        | BGFX_SAMPLER_V_CLAMP
                        | BGFX_SAMPLER_MIN_POINT
                        | BGFX_SAMPLER_MAG_POINT
                        | BGFX_SAMPLER_MIP_POINT);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        for (var resource : new AutoCloseable[] {framebuffer, depthSampler, projection, color, terrain, entity}) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = new IllegalStateException("Closing the hover highlight", exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
