package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIP_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_RGB;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_BLIT_DST;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_SAMPLER;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_VEC4;
import static org.lwjgl.bgfx.BGFX.BGFX_VIEW_MODE_SEQUENTIAL;
import static org.lwjgl.bgfx.BGFX.bgfx_blit;
import static org.lwjgl.bgfx.BGFX.bgfx_set_state;
import static org.lwjgl.bgfx.BGFX.bgfx_set_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_mode;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_name;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;
import static org.lwjgl.bgfx.BGFX.bgfx_submit;

import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.bgfx.BgfxViewAllocator.Domain;
import java.util.List;

final class BgfxWaterBackCopy implements AutoCloseable {
    private final BgfxDevice device;
    private final Extent extent;
    private final BgfxTexture texture;
    private final float[] sourceScale = new float[4];
    private BgfxFramebuffer framebuffer;
    private BgfxFullscreenGeometry geometry;
    private BgfxShader vertex;
    private BgfxProgram program;
    private BgfxUniform sampler;
    private BgfxUniform scale;
    private boolean closed;

    BgfxWaterBackCopy(BgfxDevice device, Extent extent) {
        this.device = device;
        this.extent = extent;
        texture = BgfxTexture.create2D(
                device.getResources(),
                "water/back-copy",
                extent.width(),
                extent.height(),
                false,
                1,
                BGFX_TEXTURE_FORMAT_RGBA8,
                BGFX_TEXTURE_RT | BGFX_TEXTURE_BLIT_DST,
                null);
        ensureScaling();
    }

    BgfxTexture getTexture() {
        return texture;
    }

    boolean canShareSurfaceView(Extent sourceExtent) {
        return sourceExtent.equals(extent);
    }

    int copy(String name, BgfxTexture source, Extent sourceExtent, BgfxViewAllocator views) {
        var view = views.allocate(name, Domain.RENDER, false);
        if (sourceExtent.equals(extent)) {
            bgfx_blit(
                    view,
                    texture.getHandle(),
                    0,
                    0,
                    0,
                    0,
                    source.getHandle(),
                    0,
                    0,
                    0,
                    0,
                    extent.width(),
                    extent.height(),
                    1);
            return view;
        }
        ensureScaling();
        bgfx_set_view_name(view, name);
        bgfx_set_view_mode(view, BGFX_VIEW_MODE_SEQUENTIAL);
        bgfx_set_view_rect(view, 0, 0, extent.width(), extent.height());
        bgfx_set_view_frame_buffer(view, framebuffer.getHandle());
        geometry.bind();
        bgfx_set_texture(
                0,
                sampler.getHandle(),
                source.getHandle(),
                BGFX_SAMPLER_U_CLAMP
                        | BGFX_SAMPLER_V_CLAMP
                        | BGFX_SAMPLER_MIN_POINT
                        | BGFX_SAMPLER_MAG_POINT
                        | BGFX_SAMPLER_MIP_POINT);
        sourceScale[0] = (float) sourceExtent.width() / source.getDescriptor().width();
        sourceScale[1] = (float) sourceExtent.height() / source.getDescriptor().height();
        bgfx_set_uniform(scale.getHandle(), sourceScale, 1);
        bgfx_set_state(BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A, 0);
        bgfx_submit(view, program.getHandle(), 0, BGFX_DISCARD_ALL);
        return -1;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        for (var resource : new AutoCloseable[] {program, scale, sampler, vertex, geometry, framebuffer, texture}) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = new IllegalStateException("Closing water BackTex", exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void ensureScaling() {
        if (program != null) {
            return;
        }
        try {
            framebuffer = BgfxFramebuffer.create(device.getResources(), "water/back-copy-target", List.of(texture));
            geometry = new BgfxFullscreenGeometry(
                    device.getResources(), device.getCapabilities().originBottomLeft());
            vertex = BgfxShader.create(
                    device.getResources(),
                    "water/back-copy-vs",
                    BgfxShaderResources.load(device.getCapabilities(), "vs_fullscreen"));
            sampler = BgfxUniform.create(device.getResources(), "s_source", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            scale = BgfxUniform.create(device.getResources(), "u_easuCon0", BGFX_UNIFORM_TYPE_VEC4, 1);
            program = BgfxProgram.graphics(
                    device.getResources(),
                    "water/back-copy-scale",
                    vertex,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_copy"));
        } catch (RuntimeException failure) {
            try {
                close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }
}
