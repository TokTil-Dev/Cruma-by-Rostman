package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_INVALID_HANDLE;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_ALPHA;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_RGB;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA16F;
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

import com.rost.cruma.core.env.FogRanges;
import com.rost.cruma.core.world.PostEffects.Effect;
import com.rost.cruma.viewer.render.ScenePostEffects;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.api.RenderFrameSnapshot.RenderFamily;
import com.rost.cruma.viewer.render.bgfx.BgfxViewAllocator.Domain;
import glm_.vec4.Vec4;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

final class BgfxPostProcessor implements AutoCloseable {
    static final float MIN_DISPLAY_GAMMA = 0.5f;
    static final float MAX_DISPLAY_GAMMA = 2.0f;
    static final float NEUTRAL_DISPLAY_GAMMA = 1.0f;
    private static final float DISPLAY_GAMMA_EPSILON = 1e-4f;
    private static final long FULLSCREEN_STATE = BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A;
    private static final long BLEND_STATE = BGFX_STATE_WRITE_RGB | BGFX_STATE_BLEND_ALPHA;
    private static final int CLAMP_LINEAR = BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP;
    private static final float BLOOM_THRESHOLD = 253f / 255f;
    private static final float BLOOM_COMPOSITE_STRENGTH = 4f * 64f / 255f;

    private final BgfxResourceRegistry resources;
    private final BgfxFullscreenGeometry fullscreen;
    private final BgfxShader fullscreenVertex;
    private final BgfxProgram fxaaProgram;
    private final BgfxProgram bloomExtractProgram;
    private final BgfxProgram bloomBlurProgram;
    private final BgfxProgram compositeProgram;
    private final BgfxProgram sceneProgram;
    private final LongSupplier clock;
    private final BgfxUniform sourceSampler;
    private final BgfxUniform bloomSampler;
    private final BgfxUniform paramsUniform;
    private final BgfxPerformanceTracker performance;
    private final Vec4 underwaterTint;
    private final float[] params = new float[16];

    private Extent extent = new Extent(0, 0);
    private Extent bloomExtent = new Extent(0, 0);
    private BgfxTexture fullA;
    private BgfxFramebuffer fullAFb;
    private BgfxTexture fullB;
    private BgfxFramebuffer fullBFb;
    private BgfxTexture bloomA;
    private BgfxFramebuffer bloomAFb;
    private BgfxTexture bloomB;
    private BgfxFramebuffer bloomBFb;
    private BgfxTexture history;
    private BgfxFramebuffer historyFb;
    private long lastBlurNanos;
    private boolean closed;

    BgfxPostProcessor(
            BgfxDevice device,
            BgfxPerformanceTracker performance,
            Vec4 underwaterTint,
            BgfxShader sharedFullscreenVertex) {
        this(device, performance, underwaterTint, sharedFullscreenVertex, System::nanoTime);
    }

    BgfxPostProcessor(
            BgfxDevice device,
            BgfxPerformanceTracker performance,
            Vec4 underwaterTint,
            BgfxShader sharedFullscreenVertex,
            LongSupplier clock) {
        this.clock = clock;
        this.resources = device.getResources();
        this.performance = performance;
        this.underwaterTint = underwaterTint == null ? FogRanges.defaultCellophaneColor() : new Vec4(underwaterTint);
        BgfxFullscreenGeometry createdFullscreen = null;
        if (sharedFullscreenVertex == null) {
            throw new IllegalArgumentException("post processor requires the shared fullscreen vertex shader");
        }
        BgfxShader createdVertex = sharedFullscreenVertex;
        BgfxProgram createdFxaa = null;
        BgfxProgram createdExtract = null;
        BgfxProgram createdBlur = null;
        BgfxProgram createdComposite = null;
        BgfxProgram createdScene = null;
        BgfxUniform createdSource = null;
        BgfxUniform createdBloom = null;
        BgfxUniform createdParams = null;
        try {
            createdFullscreen = new BgfxFullscreenGeometry(
                    resources, device.getCapabilities().originBottomLeft());
            createdFxaa = BgfxProgram.graphics(
                    resources,
                    "post/fxaa",
                    createdVertex,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_post_fxaa"));
            createdExtract = BgfxProgram.graphics(
                    resources,
                    "post/bloom-extract",
                    createdVertex,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_bloom_extract"));
            createdBlur = BgfxProgram.graphics(
                    resources,
                    "post/bloom-blur",
                    createdVertex,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_bloom_blur"));
            createdComposite = BgfxProgram.graphics(
                    resources,
                    "post/composite",
                    createdVertex,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_post_composite"));
            createdScene = BgfxProgram.graphics(
                    resources,
                    "post/scene",
                    createdVertex,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_post_scene"));
            createdSource = BgfxUniform.create(resources, "s_postSource", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            createdBloom = BgfxUniform.create(resources, "s_postBloom", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            createdParams = BgfxUniform.create(resources, "u_postParams", BGFX_UNIFORM_TYPE_VEC4, 4);
        } catch (RuntimeException | Error e) {
            closeAfterFailure(createdParams, e);
            closeAfterFailure(createdBloom, e);
            closeAfterFailure(createdSource, e);
            closeAfterFailure(createdScene, e);
            closeAfterFailure(createdComposite, e);
            closeAfterFailure(createdBlur, e);
            closeAfterFailure(createdExtract, e);
            closeAfterFailure(createdFxaa, e);
            closeAfterFailure(createdFullscreen, e);
            throw e;
        }
        fullscreen = createdFullscreen;
        fullscreenVertex = createdVertex;
        fxaaProgram = createdFxaa;
        bloomExtractProgram = createdExtract;
        bloomBlurProgram = createdBlur;
        compositeProgram = createdComposite;
        sceneProgram = createdScene;
        sourceSampler = createdSource;
        bloomSampler = createdBloom;
        paramsUniform = createdParams;
    }

    static float sanitizeDisplayGamma(float gamma) {
        if (!Float.isFinite(gamma)) {
            return NEUTRAL_DISPLAY_GAMMA;
        }
        return Math.max(MIN_DISPLAY_GAMMA, Math.min(MAX_DISPLAY_GAMMA, gamma));
    }

    static boolean isDisplayGammaActive(float gamma) {
        return Math.abs(sanitizeDisplayGamma(gamma) - NEUTRAL_DISPLAY_GAMMA) > DISPLAY_GAMMA_EPSILON;
    }

    static float applyDisplayGamma(float channel, float gamma) {
        var clampedChannel = Math.max(0f, Math.min(1f, channel));
        return (float) Math.pow(clampedChannel, 1f / sanitizeDisplayGamma(gamma));
    }

    Result process(
            BgfxTexture source,
            Extent requestedExtent,
            BgfxViewAllocator views,
            boolean antiAlias,
            boolean bloom,
            boolean underwater,
            Vec4 activeUnderwaterTint,
            float displayGamma,
            @Nullable ScenePostEffects scene) {
        requireOpen();
        if (source == null || requestedExtent == null || views == null) {
            throw new IllegalArgumentException("post-process source, extent and views are required");
        }
        var gamma = sanitizeDisplayGamma(displayGamma);
        var gammaActive = isDisplayGammaActive(gamma);
        var blur = scene != null && scene.blurs();
        var color = scene == null ? null : scene.getColor();
        if (!blur) {
            closeHistory();
        }
        if (requestedExtent.isZero()
                || (!antiAlias && !bloom && !underwater && !gammaActive && !blur && color == null)) {
            return new Result(source, 0, false, false, false);
        }
        ensure(source, requestedExtent, bloom);
        var activeBloomExtent = bloomExtent(requestedExtent);
        var current = source;
        var passes = 0;
        if (antiAlias) {
            setSourceDomain(current, requestedExtent);
            submit(views, "post-fxaa", requestedExtent, fullAFb, current, null, fxaaProgram);
            current = fullA;
            passes++;
        }

        if (bloom) {
            setSourceDomain(current, requestedExtent);
            params[4] = BLOOM_THRESHOLD;
            submit(views, "post-bloom-extract", activeBloomExtent, bloomAFb, current, null, bloomExtractProgram);
            setSourceDomain(bloomA, activeBloomExtent);
            params[5] = 1f;
            params[6] = 0f;
            submit(views, "post-bloom-blur-x", activeBloomExtent, bloomBFb, bloomA, null, bloomBlurProgram);
            setSourceDomain(bloomB, activeBloomExtent);
            params[5] = 0f;
            params[6] = 1f;
            submit(views, "post-bloom-blur-y", activeBloomExtent, bloomAFb, bloomB, null, bloomBlurProgram);
            passes += 3;
        }

        if (bloom || underwater || gammaActive) {
            Arrays.fill(params, 0f);
            setSourceDomain(current, requestedExtent);
            params[4] = bloom ? 1f : 0f;
            params[5] = BLOOM_COMPOSITE_STRENGTH;
            params[6] = underwater ? 1f : 0f;
            params[7] = gammaActive ? 1f / gamma : 0f;
            var tint = activeUnderwaterTint == null ? underwaterTint : activeUnderwaterTint;
            params[8] = tint.getX();
            params[9] = tint.getY();
            params[10] = tint.getZ();
            params[11] = tint.getW();
            if (bloom) {
                params[12] = (float) activeBloomExtent.width()
                        / bloomA.getDescriptor().width();
                params[13] = (float) activeBloomExtent.height()
                        / bloomA.getDescriptor().height();
                params[14] = 1f / bloomA.getDescriptor().width();
                params[15] = 1f / bloomA.getDescriptor().height();
            }
            var output = current == fullA ? fullB : fullA;
            var outputFb = current == fullA ? fullBFb : fullAFb;
            submit(
                    views,
                    "post-composite",
                    requestedExtent,
                    outputFb,
                    current,
                    bloom ? bloomA : source,
                    compositeProgram);
            current = output;
            passes++;
        }

        if (blur || color != null) {
            var output = current == fullA ? fullB : fullA;
            var outputFb = current == fullA ? fullBFb : fullAFb;
            if (blur) {
                blend(current, requestedExtent, views, scene);
                current = history;
                passes++;
                setSceneParams(current, requestedExtent, 1f, null, 0f);
            } else {
                setSceneParams(current, requestedExtent, 1f, color, scene.getColorShare());
            }
            submit(views, "post-scene", requestedExtent, outputFb, current, null, sceneProgram);
            current = output;
            passes++;
        }
        for (var ignored = 0; ignored < passes; ignored++) {
            performance.addDraw(RenderFamily.DEBUG, false, false, 1, 2);
        }
        return new Result(current, passes, antiAlias, bloom, underwater);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = closeTargets();
        failure = closeAndMerge(historyFb, failure);
        failure = closeAndMerge(history, failure);
        failure = closeAndMerge(paramsUniform, failure);
        failure = closeAndMerge(bloomSampler, failure);
        failure = closeAndMerge(sourceSampler, failure);
        failure = closeAndMerge(sceneProgram, failure);
        failure = closeAndMerge(compositeProgram, failure);
        failure = closeAndMerge(bloomBlurProgram, failure);
        failure = closeAndMerge(bloomExtractProgram, failure);
        failure = closeAndMerge(fxaaProgram, failure);
        failure = closeAndMerge(fullscreen, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private void blend(BgfxTexture frame, Extent passExtent, BgfxViewAllocator views, ScenePostEffects scene) {
        var first = history == null
                || !contains(
                        new Extent(
                                history.getDescriptor().width(),
                                history.getDescriptor().height()),
                        extent);
        if (first) {
            closeHistory();
            history = BgfxTexture.create2D(
                    resources,
                    "post/motion-blur",
                    extent.width(),
                    extent.height(),
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA16F,
                    BGFX_TEXTURE_RT,
                    null);
            historyFb = createFramebuffer("post/motion-blur-fb", history);
        }
        var now = clock.getAsLong();
        var share = first ? 1f : ScenePostEffects.share(scene.getBlurAlpha(), (now - lastBlurNanos) / 1e9f);
        lastBlurNanos = now;
        setSceneParams(frame, passExtent, share, scene.getColor(), scene.getColorShare());
        submit(
                views,
                "post-motion-blur",
                passExtent,
                historyFb,
                frame,
                null,
                sceneProgram,
                first ? FULLSCREEN_STATE : BLEND_STATE);
    }

    private void setSceneParams(BgfxTexture source, Extent viewport, float alpha, Effect color, float share) {
        Arrays.fill(params, 0f);
        setSourceDomain(source, viewport);
        params[4] = alpha;
        if (color != null) {
            params[5] = share;
            params[6] = color.keepsChroma() ? 1f : 0f;
            params[7] = color.mode();
            params[8] = color.first().x();
            params[9] = color.first().y();
            params[10] = color.first().z();
            params[11] = 1f;
            params[12] = color.second().x();
            params[13] = color.second().y();
            params[14] = color.second().z();
        }
    }

    private void closeHistory() {
        var failure = closeAndMerge(historyFb, null);
        failure = closeAndMerge(history, failure);
        historyFb = null;
        history = null;
        if (failure != null) {
            throw failure;
        }
    }

    private void submit(
            BgfxViewAllocator views,
            String name,
            Extent passExtent,
            BgfxFramebuffer target,
            BgfxTexture source,
            BgfxTexture bloom,
            BgfxProgram program) {
        submit(views, name, passExtent, target, source, bloom, program, FULLSCREEN_STATE);
    }

    private void submit(
            BgfxViewAllocator views,
            String name,
            Extent passExtent,
            BgfxFramebuffer target,
            BgfxTexture source,
            BgfxTexture bloom,
            BgfxProgram program,
            long state) {
        var view = views.allocate(name, Domain.RENDER, true);
        bgfx_set_view_name(view, name);
        bgfx_set_view_rect(view, 0, 0, passExtent.width(), passExtent.height());
        bgfx_set_view_mode(view, BGFX_VIEW_MODE_SEQUENTIAL);
        bgfx_set_view_frame_buffer(view, target == null ? BGFX_INVALID_HANDLE : target.getHandle());
        fullscreen.bind();
        bgfx_set_texture(0, sourceSampler.getHandle(), source.getHandle(), CLAMP_LINEAR);
        if (bloom != null) {
            bgfx_set_texture(1, bloomSampler.getHandle(), bloom.getHandle(), CLAMP_LINEAR);
        }
        bgfx_set_uniform(paramsUniform.getHandle(), params, 4);
        bgfx_set_state(state, 0);
        bgfx_submit(view, program.getHandle(), 0, BGFX_DISCARD_ALL);
    }

    private void ensure(BgfxTexture source, Extent requested, boolean needsBloom) {
        var descriptor = source.getDescriptor();
        var requestedCapacity = new Extent(
                Math.max(requested.width(), descriptor.width()), Math.max(requested.height(), descriptor.height()));
        if (!contains(extent, requestedCapacity)) {
            replaceFullTargets(new Extent(
                    Math.max(extent.width(), requestedCapacity.width()),
                    Math.max(extent.height(), requestedCapacity.height())));
        }
        var requestedBloom = bloomExtent(extent);
        if (needsBloom && !contains(bloomExtent, requestedBloom)) {
            replaceBloomTargets(new Extent(
                    Math.max(bloomExtent.width(), requestedBloom.width()),
                    Math.max(bloomExtent.height(), requestedBloom.height())));
        }
    }

    private void setSourceDomain(BgfxTexture source, Extent viewport) {
        var descriptor = source.getDescriptor();
        params[0] = 1f / descriptor.width();
        params[1] = 1f / descriptor.height();
        params[2] = (float) viewport.width() / descriptor.width();
        params[3] = (float) viewport.height() / descriptor.height();
    }

    private void replaceFullTargets(Extent requested) {
        BgfxTexture createdA = null;
        BgfxFramebuffer createdAFb = null;
        BgfxTexture createdB = null;
        BgfxFramebuffer createdBFb = null;
        try {
            createdA = createTexture("post/full-a", requested);
            createdAFb = createFramebuffer("post/full-a-fb", createdA);
            createdB = createTexture("post/full-b", requested);
            createdBFb = createFramebuffer("post/full-b-fb", createdB);
        } catch (RuntimeException | Error e) {
            closeAfterFailure(createdBFb, e);
            closeAfterFailure(createdB, e);
            closeAfterFailure(createdAFb, e);
            closeAfterFailure(createdA, e);
            throw e;
        }
        var oldA = fullA;
        var oldAFb = fullAFb;
        var oldB = fullB;
        var oldBFb = fullBFb;
        fullA = createdA;
        fullAFb = createdAFb;
        fullB = createdB;
        fullBFb = createdBFb;
        extent = requested;
        RuntimeException failure = null;
        failure = closeAndMerge(oldBFb, failure);
        failure = closeAndMerge(oldB, failure);
        failure = closeAndMerge(oldAFb, failure);
        failure = closeAndMerge(oldA, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private void replaceBloomTargets(Extent requested) {
        BgfxTexture createdA = null;
        BgfxFramebuffer createdAFb = null;
        BgfxTexture createdB = null;
        BgfxFramebuffer createdBFb = null;
        try {
            createdA = createTexture("post/bloom-a", requested);
            createdAFb = createFramebuffer("post/bloom-a-fb", createdA);
            createdB = createTexture("post/bloom-b", requested);
            createdBFb = createFramebuffer("post/bloom-b-fb", createdB);
        } catch (RuntimeException | Error e) {
            closeAfterFailure(createdBFb, e);
            closeAfterFailure(createdB, e);
            closeAfterFailure(createdAFb, e);
            closeAfterFailure(createdA, e);
            throw e;
        }
        var oldA = bloomA;
        var oldAFb = bloomAFb;
        var oldB = bloomB;
        var oldBFb = bloomBFb;
        bloomA = createdA;
        bloomAFb = createdAFb;
        bloomB = createdB;
        bloomBFb = createdBFb;
        bloomExtent = requested;
        RuntimeException failure = null;
        failure = closeAndMerge(oldBFb, failure);
        failure = closeAndMerge(oldB, failure);
        failure = closeAndMerge(oldAFb, failure);
        failure = closeAndMerge(oldA, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private BgfxTexture createTexture(String label, Extent targetExtent) {
        return BgfxTexture.create2D(
                resources,
                label,
                targetExtent.width(),
                targetExtent.height(),
                false,
                1,
                BGFX_TEXTURE_FORMAT_RGBA8,
                BGFX_TEXTURE_RT,
                null);
    }

    private BgfxFramebuffer createFramebuffer(String label, BgfxTexture texture) {
        return BgfxFramebuffer.create(resources, label, List.of(texture));
    }

    private RuntimeException closeTargets() {
        RuntimeException failure = closeBloomTargets();
        failure = closeAndMerge(fullBFb, failure);
        fullBFb = null;
        failure = closeAndMerge(fullB, failure);
        fullB = null;
        failure = closeAndMerge(fullAFb, failure);
        fullAFb = null;
        failure = closeAndMerge(fullA, failure);
        fullA = null;
        extent = new Extent(0, 0);
        return failure;
    }

    private RuntimeException closeBloomTargets() {
        RuntimeException failure = null;
        failure = closeAndMerge(bloomBFb, failure);
        bloomBFb = null;
        failure = closeAndMerge(bloomB, failure);
        bloomB = null;
        failure = closeAndMerge(bloomAFb, failure);
        bloomAFb = null;
        failure = closeAndMerge(bloomA, failure);
        bloomA = null;
        bloomExtent = new Extent(0, 0);
        return failure;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("BGFX post processor is closed");
        }
    }

    private static Extent bloomExtent(Extent full) {
        return new Extent(Math.max(1, (full.width() + 3) / 4), Math.max(1, (full.height() + 3) / 4));
    }

    private static boolean contains(Extent capacity, Extent requested) {
        return capacity.width() >= requested.width() && capacity.height() >= requested.height();
    }

    private static RuntimeException closeAndMerge(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                return e;
            }
            failure.addSuppressed(e);
        } catch (Exception e) {
            var wrapped = new IllegalStateException("failed to close BGFX post-process resource", e);
            if (failure == null) {
                return wrapped;
            }
            failure.addSuppressed(wrapped);
        }
        return failure;
    }

    private static void closeAfterFailure(AutoCloseable resource, Throwable failure) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception e) {
            failure.addSuppressed(e);
        }
    }

    record Result(BgfxTexture output, int passes, boolean antiAlias, boolean bloom, boolean underwater) {}
}
