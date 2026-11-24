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
import static org.lwjgl.bgfx.BGFX.bgfx_set_state;
import static org.lwjgl.bgfx.BGFX.bgfx_set_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_mode;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_name;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;
import static org.lwjgl.bgfx.BGFX.bgfx_submit;

import com.rost.cruma.viewer.render.bgfx.BgfxViewAllocator.Domain;
import java.util.List;

final class BgfxOrthoPickDepth implements AutoCloseable {
    private final BgfxTexture target;
    private final float[] pixel = new float[4];
    private BgfxFramebuffer framebuffer;
    private BgfxFullscreenGeometry geometry;
    private BgfxShader vertex;
    private BgfxUniform depthSampler;
    private BgfxUniform pixelUniform;
    private BgfxProgram program;
    private boolean closed;

    BgfxOrthoPickDepth(BgfxDevice device) {
        target = BgfxTexture.create2D(
                device.getResources(),
                "object-picker/ortho-depth",
                1,
                1,
                false,
                1,
                BGFX_TEXTURE_FORMAT_RGBA8,
                BGFX_TEXTURE_RT | BGFX_TEXTURE_BLIT_DST,
                null);
        try {
            framebuffer =
                    BgfxFramebuffer.create(device.getResources(), "object-picker/ortho-depth-target", List.of(target));
            geometry = new BgfxFullscreenGeometry(
                    device.getResources(), device.getCapabilities().originBottomLeft());
            vertex = BgfxShader.create(
                    device.getResources(),
                    "object-picker/ortho-depth-vs",
                    BgfxShaderResources.load(device.getCapabilities(), "vs_fullscreen"));
            depthSampler = BgfxUniform.create(device.getResources(), "s_pickDepth", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            pixelUniform = BgfxUniform.create(device.getResources(), "u_pickPixel", BGFX_UNIFORM_TYPE_VEC4, 1);
            program = BgfxProgram.graphics(
                    device.getResources(),
                    "object-picker/ortho-depth",
                    vertex,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_ortho_pick_depth"));
        } catch (RuntimeException failure) {
            try {
                close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    static double decode(int red, int green, int blue) {
        return (red << 16 | green << 8 | blue) / 16777215.0;
    }

    void draw(BgfxTexture depth, int x, int y, BgfxViewAllocator views) {
        var view = views.allocate("object-picker-ortho-depth", Domain.RENDER, false);
        bgfx_set_view_name(view, "object-picker-ortho-depth");
        bgfx_set_view_mode(view, BGFX_VIEW_MODE_SEQUENTIAL);
        bgfx_set_view_rect(view, 0, 0, 1, 1);
        bgfx_set_view_frame_buffer(view, framebuffer.getHandle());
        geometry.bind();
        bgfx_set_texture(
                0,
                depthSampler.getHandle(),
                depth.getHandle(),
                BGFX_SAMPLER_U_CLAMP
                        | BGFX_SAMPLER_V_CLAMP
                        | BGFX_SAMPLER_MIN_POINT
                        | BGFX_SAMPLER_MAG_POINT
                        | BGFX_SAMPLER_MIP_POINT);
        pixel[0] = x;
        pixel[1] = y;
        bgfx_set_uniform(pixelUniform.getHandle(), pixel, 1);
        bgfx_set_state(BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A, 0);
        bgfx_submit(view, program.getHandle(), 0, BGFX_DISCARD_ALL);
    }

    BgfxTexture getTarget() {
        return target;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        for (var resource :
                new AutoCloseable[] {program, pixelUniform, depthSampler, vertex, geometry, framebuffer, target}) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = new IllegalStateException("Closing the object picker's depth", exception);
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
