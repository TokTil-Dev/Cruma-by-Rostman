package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_RGB;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_BLIT_DST;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_R8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_SAMPLER;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_VEC4;
import static org.lwjgl.bgfx.BGFX.BGFX_VIEW_MODE_SEQUENTIAL;
import static org.lwjgl.bgfx.BGFX.bgfx_blit;
import static org.lwjgl.bgfx.BGFX.bgfx_set_scissor;
import static org.lwjgl.bgfx.BGFX.bgfx_set_state;
import static org.lwjgl.bgfx.BGFX.bgfx_set_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_clear;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_mode;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_transform;
import static org.lwjgl.bgfx.BGFX.bgfx_submit;
import static org.lwjgl.bgfx.BGFX.bgfx_touch;

import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.bgfx.BgfxViewAllocator.Domain;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;

final class BgfxParticleRefraction implements AutoCloseable {
    static final int VIEWS = 3;

    private final BgfxDevice device;
    private BgfxFullscreenGeometry fullscreen;
    private BgfxProgram composite;
    private BgfxUniform fieldSampler, sceneSampler, regionSampler, parameters;
    private final float[] values = new float[4];
    private final Pass single = new Pass();
    private BgfxTexture scene, field, region, sourceColor, sourceDepth;
    private BgfxFramebuffer fieldTarget, regionTarget, output;

    BgfxParticleRefraction(BgfxDevice device) {
        this.device = device;
        try {
            fullscreen = new BgfxFullscreenGeometry(
                    device.getResources(), device.getCapabilities().originBottomLeft());
            composite = BgfxProgram.graphics(
                    device.getResources(),
                    "fx/refraction-composite",
                    BgfxShaderResources.load(device.getCapabilities(), "vs_fullscreen"),
                    BgfxShaderResources.load(device.getCapabilities(), "fs_fx_refraction"));
            fieldSampler = BgfxUniform.create(device.getResources(), "s_refractionField", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            sceneSampler = BgfxUniform.create(device.getResources(), "s_refractionScene", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            regionSampler =
                    BgfxUniform.create(device.getResources(), "s_refractionRegion", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            parameters = BgfxUniform.create(device.getResources(), "u_refraction", BGFX_UNIFORM_TYPE_VEC4, 1);
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    void render(
            BgfxTexture color,
            BgfxTexture depth,
            Extent extent,
            BgfxViewAllocator views,
            FloatBuffer view,
            FloatBuffer projection,
            float scaleU,
            float scaleV,
            IntConsumer drawRegion,
            IntConsumer drawField) {
        single.clear();
        single.add(scaleU, scaleV, 0, 0, extent.width(), extent.height());
        render(color, depth, extent, views, view, projection, single, drawRegion, drawField);
    }

    void render(
            BgfxTexture color,
            BgfxTexture depth,
            Extent extent,
            BgfxViewAllocator views,
            FloatBuffer view,
            FloatBuffer projection,
            Pass pass,
            IntConsumer drawRegion,
            IntConsumer drawField) {
        if (pass.size() == 0) {
            throw new IllegalArgumentException("a refraction pass needs an emitter");
        }
        ensure(color, depth);
        var regionView = setup(views, "fx-refraction-region", regionTarget, extent, 0xffffffff);
        bgfx_blit(
                regionView,
                scene.getHandle(),
                0,
                0,
                0,
                0,
                color.getHandle(),
                0,
                0,
                0,
                0,
                extent.width(),
                extent.height(),
                1);
        bgfx_set_view_transform(regionView, view, projection);
        drawRegion.accept(regionView);
        var fieldView = setup(views, "fx-refraction-field", fieldTarget, extent, 0);
        bgfx_set_view_transform(fieldView, view, projection);
        drawField.accept(fieldView);
        var compositeView = views.allocate("fx-refraction-composite", Domain.RENDER, true);
        bgfx_set_view_rect(compositeView, 0, 0, extent.width(), extent.height());
        bgfx_set_view_frame_buffer(compositeView, output.getHandle());
        bgfx_set_view_mode(compositeView, BGFX_VIEW_MODE_SEQUENTIAL);
        values[2] = (float) extent.width() / color.getDescriptor().width();
        values[3] = (float) extent.height() / color.getDescriptor().height();
        var flags = BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP;
        for (var member = 0; member < pass.size(); member++) {
            var scissor = member * 4;
            if (member > 0) {
                var width = pass.scissors[scissor + 2] - pass.scissors[scissor];
                var height = pass.scissors[scissor + 3] - pass.scissors[scissor + 1];
                if (width <= 0 || height <= 0) {
                    continue;
                }
                bgfx_set_scissor(pass.scissors[scissor], pass.scissors[scissor + 1], width, height);
            }
            values[0] = pass.scales[member * 2];
            values[1] = pass.scales[member * 2 + 1];
            fullscreen.bind();
            bgfx_set_texture(0, fieldSampler.getHandle(), field.getHandle(), flags);
            bgfx_set_texture(1, sceneSampler.getHandle(), scene.getHandle(), flags);
            bgfx_set_texture(2, regionSampler.getHandle(), region.getHandle(), flags);
            bgfx_set_uniform(parameters.getHandle(), values, 1);
            bgfx_set_state(BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A, 0);
            bgfx_submit(compositeView, composite.getHandle(), 0, BGFX_DISCARD_ALL);
        }
    }

    static final class Pass {
        private int count;
        private float[] scales = new float[8];
        private int[] scissors = new int[16];

        void clear() {
            count = 0;
        }

        void add(float scaleU, float scaleV, int left, int top, int right, int bottom) {
            if (count * 2 == scales.length) {
                scales = Arrays.copyOf(scales, scales.length * 2);
                scissors = Arrays.copyOf(scissors, scissors.length * 2);
            }
            scales[count * 2] = scaleU;
            scales[count * 2 + 1] = scaleV;
            scissors[count * 4] = left;
            scissors[count * 4 + 1] = top;
            scissors[count * 4 + 2] = right;
            scissors[count * 4 + 3] = bottom;
            count++;
        }

        int size() {
            return count;
        }
    }

    @Override
    public void close() {
        closeTargets();
        RuntimeException failure = null;
        for (var resource :
                new AutoCloseable[] {parameters, regionSampler, sceneSampler, fieldSampler, composite, fullscreen}) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Exception cleanup) {
                if (failure == null) {
                    failure = new IllegalStateException("Closing particle refraction", cleanup);
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private int setup(BgfxViewAllocator views, String name, BgfxFramebuffer target, Extent extent, int clear) {
        var view = views.allocate(name, Domain.RENDER, true);
        bgfx_set_view_rect(view, 0, 0, extent.width(), extent.height());
        bgfx_set_view_frame_buffer(view, target.getHandle());
        bgfx_set_view_mode(view, BGFX_VIEW_MODE_SEQUENTIAL);
        bgfx_set_view_clear(view, BGFX_CLEAR_COLOR, clear, 1, 0);
        bgfx_touch(view);
        return view;
    }

    private void ensure(BgfxTexture color, BgfxTexture depth) {
        if (sourceColor == color && sourceDepth == depth) {
            return;
        }
        closeTargets();
        var width = color.getDescriptor().width();
        var height = color.getDescriptor().height();
        try {
            scene = BgfxTexture.create2D(
                    device.getResources(),
                    "fx/refraction-scene",
                    width,
                    height,
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA8,
                    BGFX_TEXTURE_BLIT_DST,
                    null);
            field = BgfxTexture.create2D(
                    device.getResources(),
                    "fx/refraction-field",
                    width,
                    height,
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA8,
                    BGFX_TEXTURE_RT,
                    null);
            region = BgfxTexture.create2D(
                    device.getResources(),
                    "fx/refraction-region",
                    width,
                    height,
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_R8,
                    BGFX_TEXTURE_RT,
                    null);
            fieldTarget =
                    BgfxFramebuffer.create(device.getResources(), "fx/refraction-field-target", List.of(field, depth));
            regionTarget = BgfxFramebuffer.create(
                    device.getResources(), "fx/refraction-region-target", List.of(region, depth));
            output = BgfxFramebuffer.create(device.getResources(), "fx/refraction-output", List.of(color));
            sourceColor = color;
            sourceDepth = depth;
        } catch (RuntimeException | Error failure) {
            closeTargets();
            throw failure;
        }
    }

    private void closeTargets() {
        if (output != null) {
            output.close();
        }
        if (regionTarget != null) {
            regionTarget.close();
        }
        if (fieldTarget != null) {
            fieldTarget.close();
        }
        if (region != null) {
            region.close();
        }
        if (field != null) {
            field.close();
        }
        if (scene != null) {
            scene.close();
        }
        output = regionTarget = fieldTarget = null;
        region = field = scene = sourceColor = sourceDepth = null;
    }
}
