package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_INVALID_HANDLE;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_RGB;
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

import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.bgfx.BgfxViewAllocator.Domain;
import com.rost.cruma.viewer.render.upscale.Fsr1Constants;
import com.rost.cruma.viewer.render.upscale.UpscalerProvider.Plan;
import java.util.List;

public final class BgfxSpatialUpscaler implements AutoCloseable {
    private static final long FULLSCREEN_STATE = BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A;
    private static final int CLAMP_LINEAR = BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP;
    private static final int CLAMP_POINT = CLAMP_LINEAR | BGFX_SAMPLER_MIN_POINT | BGFX_SAMPLER_MAG_POINT;
    private static final String UPSCALE_NATIVE = "upscale-native";
    private static final String UPSCALE_BILINEAR = "upscale-bilinear";

    private final BgfxResourceRegistry resources;
    private final BgfxFullscreenGeometry fullscreen;
    private final BgfxShader fullscreenVertexShader;
    private final BgfxProgram copyProgram;
    private final BgfxProgram easuProgram;
    private final BgfxProgram rcasProgram;
    private final BgfxUniform sourceSampler;
    private final BgfxUniform easuCon0;
    private final BgfxUniform easuCon1;
    private final BgfxUniform outputSize;
    private final BgfxUniform rcasCon;
    private final float[] con0Data = new float[4];
    private final float[] con1Data = new float[4];
    private final float[] outputSizeData = new float[4];
    private final float[] rcasData = new float[4];

    private BgfxTexture rcasInput;
    private BgfxFramebuffer rcasFramebuffer;
    private Extent rcasExtent = new Extent(0, 0);
    private boolean closed;

    public BgfxSpatialUpscaler(BgfxDevice device) {
        resources = device.getResources();
        fullscreen =
                new BgfxFullscreenGeometry(resources, device.getCapabilities().originBottomLeft());
        fullscreenVertexShader = BgfxShader.create(
                resources,
                "upscale/fullscreen-vs",
                BgfxShaderResources.load(device.getCapabilities(), "vs_fullscreen"));
        copyProgram = BgfxProgram.graphics(
                resources,
                "upscale/copy",
                fullscreenVertexShader,
                BgfxShaderResources.load(device.getCapabilities(), "fs_copy"));
        easuProgram = BgfxProgram.graphics(
                resources,
                "upscale/fsr1-easu",
                fullscreenVertexShader,
                BgfxShaderResources.load(device.getCapabilities(), "fs_fsr1_easu"));
        rcasProgram = BgfxProgram.graphics(
                resources,
                "upscale/fsr1-rcas",
                fullscreenVertexShader,
                BgfxShaderResources.load(device.getCapabilities(), "fs_fsr1_rcas"));
        sourceSampler = BgfxUniform.create(resources, "s_source", BGFX_UNIFORM_TYPE_SAMPLER, 1);
        easuCon0 = BgfxUniform.create(resources, "u_easuCon0", BGFX_UNIFORM_TYPE_VEC4, 1);
        easuCon1 = BgfxUniform.create(resources, "u_easuCon1", BGFX_UNIFORM_TYPE_VEC4, 1);
        outputSize = BgfxUniform.create(resources, "u_fsrOutputSize", BGFX_UNIFORM_TYPE_VEC4, 1);
        rcasCon = BgfxUniform.create(resources, "u_rcasCon", BGFX_UNIFORM_TYPE_VEC4, 1);
    }

    public void present(BgfxTexture input, Plan plan, BgfxViewAllocator views) {
        present(input, plan, views, null);
    }

    BgfxShader getFullscreenVertexShader() {
        requireOpen();
        return fullscreenVertexShader;
    }

    public void present(BgfxTexture input, Plan plan, BgfxViewAllocator views, BgfxFramebuffer displayOutput) {
        requireOpen();
        if (input == null || plan == null || views == null) {
            throw new IllegalArgumentException("upscaler input, plan and view allocator are required");
        }
        if (plan.displayExtent().isZero()) {
            return;
        }
        var descriptor = input.getDescriptor();
        if (descriptor.width() < plan.renderExtent().width()
                || descriptor.height() < plan.renderExtent().height()) {
            throw new IllegalArgumentException("upscaler input extent "
                    + descriptor.width()
                    + "x"
                    + descriptor.height()
                    + " does not match plan "
                    + plan.renderExtent());
        }
        switch (plan.provider()) {
            case "native", "bilinear" -> submitCopy(input, plan, views, displayOutput);
            case "fsr1" -> submitFsr1(input, plan, views, displayOutput);
            default -> throw new IllegalArgumentException("unsupported bgfx upscaler provider " + plan.provider());
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeRcasTarget();
        close(rcasCon);
        close(outputSize);
        close(easuCon1);
        close(easuCon0);
        close(sourceSampler);
        close(rcasProgram);
        close(easuProgram);
        close(copyProgram);
        close(fullscreenVertexShader);
        close(fullscreen);
    }

    private void submitCopy(BgfxTexture input, Plan plan, BgfxViewAllocator views, BgfxFramebuffer displayOutput) {
        var viewName = "native".equals(plan.provider()) ? UPSCALE_NATIVE : UPSCALE_BILINEAR;
        var view = views.allocate(viewName, Domain.DISPLAY, true);
        setupView(view, viewName, plan.displayExtent(), displayOutput);
        bind(input, CLAMP_LINEAR);
        con0Data[0] =
                (float) plan.renderExtent().width() / input.getDescriptor().width();
        con0Data[1] =
                (float) plan.renderExtent().height() / input.getDescriptor().height();
        con0Data[2] = 0f;
        con0Data[3] = 0f;
        bgfx_set_uniform(easuCon0.getHandle(), con0Data, 1);
        bgfx_submit(view, copyProgram.getHandle(), 0, BGFX_DISCARD_ALL);
    }

    private void submitFsr1(BgfxTexture input, Plan plan, BgfxViewAllocator views, BgfxFramebuffer displayOutput) {
        var display = plan.displayExtent();
        Fsr1Constants.easuInto(
                plan.renderExtent().width(),
                plan.renderExtent().height(),
                input.getDescriptor().width(),
                input.getDescriptor().height(),
                display.width(),
                display.height(),
                con0Data,
                con1Data);
        outputSizeData[0] = display.width();
        outputSizeData[1] = display.height();
        outputSizeData[2] = plan.renderExtent().width();
        outputSizeData[3] = plan.renderExtent().height();

        BgfxFramebuffer easuOutput = null;
        if (plan.rcas()) {
            ensureRcasTarget(display);
            easuOutput = rcasFramebuffer;
        } else {
            easuOutput = displayOutput;
        }
        var easuView = views.allocate("upscale-fsr1-easu", Domain.DISPLAY, true);
        setupView(easuView, "upscale-fsr1-easu", display, easuOutput);
        bind(input, CLAMP_POINT);
        bgfx_set_uniform(easuCon0.getHandle(), con0Data, 1);
        bgfx_set_uniform(easuCon1.getHandle(), con1Data, 1);
        bgfx_set_uniform(outputSize.getHandle(), outputSizeData, 1);
        bgfx_submit(easuView, easuProgram.getHandle(), 0, BGFX_DISCARD_ALL);

        if (plan.rcas()) {
            rcasData[0] = Fsr1Constants.rcasAttenuation(plan.sharpness());
            rcasData[1] = 0f;
            rcasData[2] = 0f;
            rcasData[3] = 0f;
            var rcasView = views.allocate("upscale-fsr1-rcas", Domain.DISPLAY, true);
            setupView(rcasView, "upscale-fsr1-rcas", display, displayOutput);
            bind(rcasInput, CLAMP_POINT);
            outputSizeData[2] = 1f / rcasInput.getDescriptor().width();
            outputSizeData[3] = 1f / rcasInput.getDescriptor().height();
            bgfx_set_uniform(rcasCon.getHandle(), rcasData, 1);
            bgfx_set_uniform(outputSize.getHandle(), outputSizeData, 1);
            bgfx_submit(rcasView, rcasProgram.getHandle(), 0, BGFX_DISCARD_ALL);
        }
    }

    private void bind(BgfxTexture source, int samplerFlags) {
        fullscreen.bind();
        bgfx_set_texture(0, sourceSampler.getHandle(), source.getHandle(), samplerFlags);
        bgfx_set_state(FULLSCREEN_STATE, 0);
    }

    private void ensureRcasTarget(Extent extent) {
        if (rcasExtent.width() >= extent.width() && rcasExtent.height() >= extent.height()) {
            return;
        }
        var capacity = new Extent(
                Math.max(rcasExtent.width(), extent.width()), Math.max(rcasExtent.height(), extent.height()));
        BgfxTexture nextInput = null;
        BgfxFramebuffer nextFramebuffer = null;
        try {
            nextInput = BgfxTexture.create2D(
                    resources,
                    "upscale/fsr1-rcas-input",
                    capacity.width(),
                    capacity.height(),
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA8,
                    BGFX_TEXTURE_RT,
                    null);
            nextFramebuffer = BgfxFramebuffer.create(resources, "upscale/fsr1-rcas-framebuffer", List.of(nextInput));
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(nextFramebuffer, failure);
            closeAfterFailure(nextInput, failure);
            throw failure;
        }
        var previousInput = rcasInput;
        var previousFramebuffer = rcasFramebuffer;
        rcasInput = nextInput;
        rcasFramebuffer = nextFramebuffer;
        rcasExtent = capacity;
        close(previousFramebuffer);
        close(previousInput);
    }

    private void closeRcasTarget() {
        close(rcasFramebuffer);
        rcasFramebuffer = null;
        close(rcasInput);
        rcasInput = null;
        rcasExtent = new Extent(0, 0);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("bgfx spatial upscaler is closed");
        }
    }

    private static void setupView(int view, String name, Extent extent, BgfxFramebuffer framebuffer) {
        bgfx_set_view_name(view, name);
        bgfx_set_view_rect(view, 0, 0, extent.width(), extent.height());
        bgfx_set_view_mode(view, BGFX_VIEW_MODE_SEQUENTIAL);
        bgfx_set_view_frame_buffer(view, framebuffer == null ? BGFX_INVALID_HANDLE : framebuffer.getHandle());
    }

    private static void close(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to close bgfx upscaler resource", e);
        }
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
