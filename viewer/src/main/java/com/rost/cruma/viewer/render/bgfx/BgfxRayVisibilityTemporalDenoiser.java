package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_RGB;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA16F;
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

import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.api.RenderFrameSnapshot.RenderFamily;
import com.rost.cruma.viewer.render.bgfx.BgfxMetalRayQueries.Snapshot;
import com.rost.cruma.viewer.render.bgfx.BgfxViewAllocator.Domain;
import java.util.Arrays;
import java.util.List;

final class BgfxRayVisibilityTemporalDenoiser implements AutoCloseable {
    private static final long FULLSCREEN_STATE = BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A;
    private static final int CLAMP_POINT =
            BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP | BGFX_SAMPLER_MIN_POINT | BGFX_SAMPLER_MAG_POINT;
    private static final float HISTORY_WEIGHT = 0.78f;
    private static final float HIT_DISTANCE_TOLERANCE = 32f;
    private static final float NORMAL_THRESHOLD = 0.75f;
    private static final float MAX_CAMERA_CUT_TRANSLATION = 4_000f;
    private static final float MIN_CAMERA_CUT_FORWARD_DOT = 0.5f;

    private final BgfxResourceRegistry resources;
    private final BgfxPerformanceTracker performance;
    private final BgfxFullscreenGeometry fullscreen;
    private final BgfxProgram program;
    private final BgfxUniform currentSampler;
    private final BgfxUniform historySampler;
    private final BgfxUniform paramsUniform;
    private final float[] params = new float[17 * 4];
    private final BgfxTexture[] historyTextures = new BgfxTexture[2];
    private final BgfxFramebuffer[] historyFramebuffers = new BgfxFramebuffer[2];

    private Extent capacity = new Extent(0, 0);
    private int historyIndex = -1;
    private Snapshot previousSnapshot = Snapshot.disabled();
    private int previousPublishedWidth;
    private int previousPublishedHeight;
    private float previousSunX;
    private float previousSunY;
    private float previousSunZ;
    private float previousTimeOfDay;
    private boolean closed;

    BgfxRayVisibilityTemporalDenoiser(
            BgfxDevice device,
            BgfxPerformanceTracker performance,
            BgfxShader sharedFullscreenVertex,
            BgfxFullscreenGeometry fullscreen) {
        if (device == null || performance == null || sharedFullscreenVertex == null || fullscreen == null) {
            throw new IllegalArgumentException("ray temporal denoiser requires device and fullscreen resources");
        }
        resources = device.getResources();
        this.performance = performance;
        this.fullscreen = fullscreen;
        BgfxProgram createdProgram = null;
        BgfxUniform createdCurrent = null;
        BgfxUniform createdHistory = null;
        BgfxUniform createdParams = null;
        try {
            createdProgram = BgfxProgram.graphics(
                    resources,
                    "ray-visibility/temporal",
                    sharedFullscreenVertex,
                    BgfxShaderResources.load(device.getCapabilities(), "fs_ray_visibility_temporal"));
            createdCurrent = BgfxUniform.create(resources, "s_rayTemporalCurrent", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            createdHistory = BgfxUniform.create(resources, "s_rayTemporalHistory", BGFX_UNIFORM_TYPE_SAMPLER, 1);
            createdParams = BgfxUniform.create(resources, "u_rayTemporal", BGFX_UNIFORM_TYPE_VEC4, 17);
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(createdParams, failure);
            closeAfterFailure(createdHistory, failure);
            closeAfterFailure(createdCurrent, failure);
            closeAfterFailure(createdProgram, failure);
            throw failure;
        }
        program = createdProgram;
        currentSampler = createdCurrent;
        historySampler = createdHistory;
        paramsUniform = createdParams;
    }

    static boolean isHistoryCompatible(
            Snapshot previous,
            Snapshot current,
            int previousWidth,
            int previousHeight,
            int currentWidth,
            int currentHeight,
            float previousSunX,
            float previousSunY,
            float previousSunZ,
            float currentSunX,
            float currentSunY,
            float currentSunZ,
            float previousTimeOfDay,
            float currentTimeOfDay) {
        if (previous == null
                || current == null
                || !previous.available()
                || !current.available()
                || previous.serial() + 1L != current.serial()
                || previousWidth != currentWidth
                || previousHeight != currentHeight
                || Float.floatToIntBits(previousTimeOfDay) != Float.floatToIntBits(currentTimeOfDay)
                || previous.camera().length < 3
                || current.camera().length < 3
                || previous.forward().length < 3
                || current.forward().length < 3) {
            return false;
        }
        var dx = current.camera()[0] - previous.camera()[0];
        var dy = current.camera()[1] - previous.camera()[1];
        var dz = current.camera()[2] - previous.camera()[2];
        if (dx * dx + dy * dy + dz * dz > MAX_CAMERA_CUT_TRANSLATION * MAX_CAMERA_CUT_TRANSLATION) {
            return false;
        }
        var forwardDot = normalizedDot(previous.forward(), current.forward());
        return forwardDot >= MIN_CAMERA_CUT_FORWARD_DOT
                && normalizedDot(previousSunX, previousSunY, previousSunZ, currentSunX, currentSunY, currentSunZ)
                        >= 0.999999f;
    }

    Snapshot process(
            Snapshot current,
            BgfxViewAllocator views,
            Camera camera,
            float sunX,
            float sunY,
            float sunZ,
            float timeOfDay) {
        requireOpen();
        if (current == null || !current.available() || current.texture() == null || camera == null || views == null) {
            resetHistory();
            return current == null ? Snapshot.disabled() : current;
        }
        var descriptor = current.texture().getDescriptor();
        if (descriptor.format() != BGFX_TEXTURE_FORMAT_RGBA16F) {
            resetHistory();
            return current;
        }
        var publishedWidth = publishedExtent(descriptor.width(), current.uvScaleX());
        var publishedHeight = publishedExtent(descriptor.height(), current.uvScaleY());
        ensure(descriptor.width(), descriptor.height());

        if (historyIndex >= 0
                && previousSnapshot != null
                && previousSnapshot.available()
                && previousSnapshot.serial() == current.serial()
                && previousPublishedWidth == publishedWidth
                && previousPublishedHeight == publishedHeight) {
            return current.withTexture(
                    historyTextures[historyIndex],
                    (float) publishedWidth / capacity.width(),
                    (float) publishedHeight / capacity.height());
        }

        var useHistory = historyIndex >= 0
                && isHistoryCompatible(
                        previousSnapshot,
                        current,
                        previousPublishedWidth,
                        previousPublishedHeight,
                        publishedWidth,
                        publishedHeight,
                        previousSunX,
                        previousSunY,
                        previousSunZ,
                        sunX,
                        sunY,
                        sunZ,
                        previousTimeOfDay,
                        timeOfDay);
        Arrays.fill(params, 0f);
        params[0] = 1f / descriptor.width();
        params[1] = 1f / descriptor.height();
        params[2] = publishedWidth;
        params[3] = publishedHeight;
        var previousTexture = useHistory ? historyTextures[historyIndex] : current.texture();
        var previousDescriptor = previousTexture.getDescriptor();
        params[4] = 1f / previousDescriptor.width();
        params[5] = 1f / previousDescriptor.height();
        params[6] = useHistory ? previousPublishedWidth : publishedWidth;
        params[7] = useHistory ? previousPublishedHeight : publishedHeight;
        System.arraycopy(current.inverseViewProjection(), 0, params, 8, 16);
        if (useHistory) {
            System.arraycopy(previousSnapshot.viewProjection(), 0, params, 24, 16);
        }
        System.arraycopy(current.camera(), 0, params, 40, 4);
        if (useHistory) {
            System.arraycopy(previousSnapshot.camera(), 0, params, 44, 4);
        }
        params[48] = useHistory ? 1f : 0f;
        params[49] = HISTORY_WEIGHT;
        params[50] = HIT_DISTANCE_TOLERANCE;
        params[51] = NORMAL_THRESHOLD;
        System.arraycopy(current.rayBasis(), 0, params, 52, 16);

        var writeIndex = historyIndex == 0 ? 1 : 0;
        var viewId = views.allocate("ray-visibility-temporal", Domain.RENDER, true);
        bgfx_set_view_name(viewId, "ray-visibility-temporal");
        bgfx_set_view_rect(viewId, 0, 0, publishedWidth, publishedHeight);
        bgfx_set_view_mode(viewId, BGFX_VIEW_MODE_SEQUENTIAL);
        bgfx_set_view_frame_buffer(viewId, historyFramebuffers[writeIndex].getHandle());
        fullscreen.bind();
        bgfx_set_texture(0, currentSampler.getHandle(), current.texture().getHandle(), CLAMP_POINT);
        bgfx_set_texture(1, historySampler.getHandle(), previousTexture.getHandle(), CLAMP_POINT);
        bgfx_set_uniform(paramsUniform.getHandle(), params, 17);
        bgfx_set_state(FULLSCREEN_STATE, 0);
        bgfx_submit(viewId, program.getHandle(), 0, BGFX_DISCARD_ALL);
        performance.addDraw(RenderFamily.DEBUG, false, false, 1, 2);

        historyIndex = writeIndex;
        previousSnapshot = current;
        previousPublishedWidth = publishedWidth;
        previousPublishedHeight = publishedHeight;
        previousSunX = sunX;
        previousSunY = sunY;
        previousSunZ = sunZ;
        previousTimeOfDay = timeOfDay;
        return current.withTexture(
                historyTextures[writeIndex],
                (float) publishedWidth / capacity.width(),
                (float) publishedHeight / capacity.height());
    }

    void reset() {
        requireOpen();
        resetHistory();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        for (var index = historyFramebuffers.length - 1; index >= 0; index--) {
            failure = closeAndMerge(historyFramebuffers[index], failure);
            historyFramebuffers[index] = null;
            failure = closeAndMerge(historyTextures[index], failure);
            historyTextures[index] = null;
        }
        failure = closeAndMerge(paramsUniform, failure);
        failure = closeAndMerge(historySampler, failure);
        failure = closeAndMerge(currentSampler, failure);
        failure = closeAndMerge(program, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private void ensure(int width, int height) {
        if (capacity.width() >= width && capacity.height() >= height) {
            return;
        }
        var next = new Extent(Math.max(capacity.width(), width), Math.max(capacity.height(), height));
        var createdTextures = new BgfxTexture[2];
        var createdFramebuffers = new BgfxFramebuffer[2];
        try {
            for (var index = 0; index < createdTextures.length; index++) {
                createdTextures[index] = BgfxTexture.create2D(
                        resources,
                        "ray-visibility/temporal-" + index,
                        next.width(),
                        next.height(),
                        false,
                        1,
                        BGFX_TEXTURE_FORMAT_RGBA16F,
                        BGFX_TEXTURE_RT,
                        null);
                createdFramebuffers[index] = BgfxFramebuffer.create(
                        resources, "ray-visibility/temporal-fb-" + index, List.of(createdTextures[index]));
            }
        } catch (RuntimeException | Error failure) {
            for (var index = createdFramebuffers.length - 1; index >= 0; index--) {
                closeAfterFailure(createdFramebuffers[index], failure);
                closeAfterFailure(createdTextures[index], failure);
            }
            throw failure;
        }
        RuntimeException closeFailure = null;
        for (var index = historyFramebuffers.length - 1; index >= 0; index--) {
            closeFailure = closeAndMerge(historyFramebuffers[index], closeFailure);
            closeFailure = closeAndMerge(historyTextures[index], closeFailure);
            historyFramebuffers[index] = createdFramebuffers[index];
            historyTextures[index] = createdTextures[index];
        }
        capacity = next;
        resetHistory();
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private void resetHistory() {
        historyIndex = -1;
        previousSnapshot = Snapshot.disabled();
        previousPublishedWidth = 0;
        previousPublishedHeight = 0;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("ray temporal denoiser is closed");
        }
    }

    private static int publishedExtent(int allocation, float scale) {
        return Math.max(1, Math.min(allocation, Math.round(allocation * scale)));
    }

    private static float normalizedDot(float[] left, float[] right) {
        return normalizedDot(left[0], left[1], left[2], right[0], right[1], right[2]);
    }

    private static float normalizedDot(float ax, float ay, float az, float bx, float by, float bz) {
        var aLength = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        var bLength = (float) Math.sqrt(bx * bx + by * by + bz * bz);
        if (aLength < 0.0001f || bLength < 0.0001f) {
            return -1f;
        }
        return (ax * bx + ay * by + az * bz) / (aLength * bLength);
    }

    private static RuntimeException closeAndMerge(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                return exception;
            }
            failure.addSuppressed(exception);
        } catch (Exception exception) {
            var wrapped = new IllegalStateException("failed to close ray temporal resource", exception);
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
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
