package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_COLOR;
import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_DEPTH;
import static org.lwjgl.bgfx.BGFX.BGFX_DEBUG_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_DEBUG_PROFILER;
import static org.lwjgl.bgfx.BGFX.BGFX_FRAME_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_PCI_ID_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_COUNT;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_DIRECT3D11;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_DIRECT3D12;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_METAL;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_NOOP;
import static org.lwjgl.bgfx.BGFX.BGFX_RENDERER_TYPE_VULKAN;
import static org.lwjgl.bgfx.BGFX.BGFX_RESET_MAXANISOTROPY;
import static org.lwjgl.bgfx.BGFX.BGFX_RESET_MSAA_X2;
import static org.lwjgl.bgfx.BGFX.BGFX_RESET_MSAA_X4;
import static org.lwjgl.bgfx.BGFX.BGFX_RESET_NONE;
import static org.lwjgl.bgfx.BGFX.BGFX_RESET_SUSPEND;
import static org.lwjgl.bgfx.BGFX.BGFX_RESET_VSYNC;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_COUNT;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_D24S8;
import static org.lwjgl.bgfx.BGFX.BGFX_VIEW_MODE_SEQUENTIAL;
import static org.lwjgl.bgfx.BGFX.bgfx_frame;
import static org.lwjgl.bgfx.BGFX.bgfx_get_caps;
import static org.lwjgl.bgfx.BGFX.bgfx_get_renderer_name;
import static org.lwjgl.bgfx.BGFX.bgfx_get_renderer_type;
import static org.lwjgl.bgfx.BGFX.bgfx_get_stats;
import static org.lwjgl.bgfx.BGFX.bgfx_init;
import static org.lwjgl.bgfx.BGFX.bgfx_init_ctor;
import static org.lwjgl.bgfx.BGFX.bgfx_reset;
import static org.lwjgl.bgfx.BGFX.bgfx_set_debug;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_clear;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_mode;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_name;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;
import static org.lwjgl.bgfx.BGFX.bgfx_shutdown;
import static org.lwjgl.bgfx.BGFX.bgfx_touch;
import static org.lwjgl.bgfx.BGFXPlatform.BGFX_RENDER_FRAME_NO_CONTEXT;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_COCOA;
import static org.lwjgl.glfw.GLFW.glfwGetPlatform;

import com.rost.cruma.viewer.application.WindowContext;
import com.rost.cruma.viewer.render.api.RenderDiagnostics;
import com.rost.cruma.viewer.render.api.RenderExtents;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.api.RenderStats;
import com.rost.cruma.viewer.render.api.RenderStats.ViewTiming;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.Renderer;
import com.rost.cruma.viewer.render.bgfx.BgfxResourceRegistry.Kind;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.bgfx.BGFXPlatform;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;

public final class BgfxDevice implements AutoCloseable {
    public static final int CLEAR_VIEW = 0;

    public static final int BUFFER_COPY_VIEW = CLEAR_VIEW + 1;

    private static final int SHUTDOWN_DRAIN_FRAMES = 4;
    private static final int COPY_POOL_CYCLE_FRAMES =
            Math.max(0, Integer.getInteger("cruma.viewer.bgfx.copy_pool_cycle_frames", 0));
    private static final boolean POOLED_UPLOAD_COPIES = Boolean.parseBoolean(System.getProperty(
            "cruma.viewer.bgfx.pooled_upload_copies", Boolean.toString(Platform.get() == Platform.WINDOWS)));
    private static final boolean MAX_ANISOTROPY =
            Boolean.parseBoolean(System.getProperty("cruma.viewer.bgfx.max_anisotropy", "true"));

    private static final boolean DEBUG_LOOP_TIMINGS = Boolean.getBoolean("cruma.viewer.debug_loop_timings");
    private static final Logger log = LogManager.getLogger(BgfxDevice.class);

    private final Thread apiThread = Thread.currentThread();
    private final WindowContext windowContext;
    private final ViewerGraphicsConfig config;
    private int resetFlags;

    private boolean initialized;
    private int activeWidth;
    private int activeHeight;
    private long frameNumber;
    private long copyPoolFunction;
    private boolean copyPoolEnabled;
    private boolean renderItemLimitReported;
    private long videoMemoryFallbackFunction;
    private boolean videoMemoryFallbackReported;
    private BgfxCapabilities capabilities;
    private RenderStats stats;
    private BgfxResourceRegistry resources;
    private BgfxSubmissionBudget submissionBudget;

    private final List<Object> shutdownRetained = new ArrayList<>();

    private final List<AutoCloseable> releaseAfterShutdown = new ArrayList<>();

    public BgfxDevice(WindowContext windowContext, ViewerGraphicsConfig config) {
        this.windowContext = windowContext;
        this.config = config;
        this.resetFlags = backendResetFlags(
                rendererType(config.renderer()), config.msaaSamples(), MAX_ANISOTROPY, config.vsync());
        initialize();
    }

    static boolean copyPoolModeRequested(long frameNumber) {
        if (COPY_POOL_CYCLE_FRAMES == 0) {
            return POOLED_UPLOAD_COPIES;
        }
        var phase = frameNumber / COPY_POOL_CYCLE_FRAMES % 4;
        return phase == 0 || phase == 3;
    }

    static boolean isMaximumAnisotropyEnabled() {
        return MAX_ANISOTROPY;
    }

    static int resetFlags(int msaaSamples, boolean maximumAnisotropy) {
        var sampling = maximumAnisotropy ? BGFX_RESET_MAXANISOTROPY : 0;
        return sampling
                | switch (msaaSamples) {
                    case 0 -> BGFX_RESET_NONE;
                    case 2 -> BGFX_RESET_MSAA_X2;
                    case 4 -> BGFX_RESET_MSAA_X4;
                    default -> throw new IllegalArgumentException("unsupported MSAA sample count: " + msaaSamples);
                };
    }

    static int backendResetFlags(int rendererType, int msaaSamples, boolean maximumAnisotropy, boolean vsync) {
        var samples =
                rendererType == BGFX_RENDERER_TYPE_VULKAN || rendererType == BGFX_RENDERER_TYPE_COUNT ? 0 : msaaSamples;
        return resetFlags(samples, maximumAnisotropy)
                | (vsync || rendererType == BGFX_RENDERER_TYPE_VULKAN ? BGFX_RESET_VSYNC : 0);
    }

    public BgfxCapabilities getCapabilities() {
        return capabilities;
    }

    public ViewerGraphicsConfig.Tuning getTuning() {
        return config.tuning();
    }

    public boolean isVsync() {
        return (resetFlags & BGFX_RESET_VSYNC) != 0;
    }

    int getBackbufferMsaaSamples() {
        checkOpen();
        return (resetFlags & BGFX_RESET_MSAA_X4) != 0 ? 4 : (resetFlags & BGFX_RESET_MSAA_X2) != 0 ? 2 : 0;
    }

    public BgfxResourceRegistry getResources() {
        checkOpen();
        return resources;
    }

    void retainUntilShutdown(Object value) {
        checkApiThread();
        checkOpen();
        if (value != null) {
            shutdownRetained.add(value);
        }
    }

    void releaseAfterShutdown(AutoCloseable value) {
        checkApiThread();
        checkOpen();
        if (value != null) {
            releaseAfterShutdown.add(value);
        }
    }

    public int getUiViewId() {
        checkOpen();
        var viewId = capabilities.maxViews() - 1;
        if (viewId <= BUFFER_COPY_VIEW) {
            throw new IllegalStateException("bgfx renderer exposes too few views for world + UI");
        }
        return viewId;
    }

    public void beginFrame() {
        checkApiThread();
        checkOpen();
        submissionBudget.beginFrame();
        resizeIfNeeded();
        var width = Math.max(1, activeWidth);
        var height = Math.max(1, activeHeight);
        bgfx_set_view_name(CLEAR_VIEW, "display-clear");
        bgfx_set_view_rect(CLEAR_VIEW, 0, 0, width, height);
        bgfx_set_view_clear(CLEAR_VIEW, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH, 0x1a1a1aff, 1.0f, 0);
        bgfx_touch(CLEAR_VIEW);
        bgfx_set_view_name(BUFFER_COPY_VIEW, "buffer-copy");
        bgfx_set_view_mode(BUFFER_COPY_VIEW, BGFX_VIEW_MODE_SEQUENTIAL);
    }

    public void endFrame() {
        checkApiThread();
        checkOpen();
        submissionBudget.reportDrops(frameNumber);
        var started = DEBUG_LOOP_TIMINGS ? System.nanoTime() : 0L;
        frameNumber = Integer.toUnsignedLong(bgfx_frame(BGFX_FRAME_NONE));
        var submitted = DEBUG_LOOP_TIMINGS ? System.nanoTime() : 0L;
        stats = copyStats();
        if (!renderItemLimitReported && (long) stats.draws() + stats.computes() >= capabilities.maxDrawCalls()) {
            renderItemLimitReported = true;
            log.warn(
                    "Frame reached the native render-item limit: draws={} computes={} limit={}; "
                            + "later submissions, including final presentation, may have been dropped (reported once)",
                    stats.draws(),
                    stats.computes(),
                    capabilities.maxDrawCalls());
        }
        if (videoMemoryFallbackFunction != 0L && !videoMemoryFallbackReported) {
            var fallbacks = JNI.invokeI(videoMemoryFallbackFunction);
            if (fallbacks > 0) {
                videoMemoryFallbackReported = true;
                log.warn(
                        "Video memory is full. GPU allocations in system memory: {}. Rendering may be slower; "
                                + "load a smaller area, lower texture_detail or use Direct3D 11 (reported once)",
                        fallbacks);
            }
        }
        if (COPY_POOL_CYCLE_FRAMES > 0) {
            updateCopyPoolMode();
        }
        if (DEBUG_LOOP_TIMINGS) {
            var finished = System.nanoTime();
            if (finished - started > 5_000_000L) {
                log.info(
                        "Frame boundary: frame={} nativeMs={} statsMs={}",
                        frameNumber,
                        (submitted - started) / 1_000_000.0,
                        (finished - submitted) / 1_000_000.0);
            }
        }
    }

    BgfxSubmissionBudget getSubmissionBudget() {
        checkOpen();
        return submissionBudget;
    }

    long getNativeRendererContext() {
        checkApiThread();
        checkOpen();
        var internal = BGFXPlatform.bgfx_get_internal_data();
        return internal == null ? 0L : internal.context();
    }

    void drainPendingFrames() {
        checkApiThread();
        checkOpen();
        for (var index = 0; index < SHUTDOWN_DRAIN_FRAMES; index++) {
            frameNumber = Integer.toUnsignedLong(bgfx_frame(BGFX_FRAME_NONE));
        }
    }

    public RenderStats getStats() {
        return stats;
    }

    void logMemoryInventory() {
        checkApiThread();
        checkOpen();
        var nativeStats = bgfx_get_stats();
        log.info(
                "GPU memory: renderer={} localUsage={} localBudget={} textures={} targets={} bufferFamilies={}",
                capabilities.renderer(),
                nativeStats.gpuMemoryUsed(),
                nativeStats.gpuMemoryMax(),
                nativeStats.textureMemoryUsed(),
                nativeStats.rtMemoryUsed(),
                resources.getBufferBytesByFamily());
    }

    @Override
    public void close() {
        checkApiThread();
        if (!initialized) {
            return;
        }
        RuntimeException failure = null;
        try {
            drainPendingFrames();
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            resources.close();
        } catch (RuntimeException e) {
            failure = merge(failure, e);
        }
        try {
            drainPendingFrames();
        } catch (RuntimeException e) {
            failure = merge(failure, e);
        } finally {
            try {
                bgfx_shutdown();
            } catch (RuntimeException e) {
                failure = merge(failure, e);
            } finally {
                initialized = false;
                shutdownRetained.clear();
            }
            for (var index = releaseAfterShutdown.size() - 1; index >= 0; index--) {
                try {
                    releaseAfterShutdown.get(index).close();
                } catch (Exception e) {
                    var runtime = e instanceof RuntimeException exception
                            ? exception
                            : new IllegalStateException("deferred native release failed", e);
                    failure = merge(failure, runtime);
                }
            }
            releaseAfterShutdown.clear();
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void initialize() {
        checkApiThread();
        if (windowContext.windowHandle == 0L) {
            throw new IllegalStateException("A GLFW NO_API window is required before bgfx initialization");
        }
        var cocoa = glfwGetPlatform() == GLFW_PLATFORM_COCOA;
        var threadedMetal = cocoa
                && (config.renderer() == Renderer.AUTO || config.renderer() == Renderer.METAL)
                && !Boolean.getBoolean("cruma.viewer.bgfx.single_threaded_metal");
        if (cocoa && !threadedMetal) {
            var result = BGFXPlatform.bgfx_render_frame(0);
            if (result != BGFX_RENDER_FRAME_NO_CONTEXT) {
                throw new IllegalStateException("Unexpected pre-init bgfx renderFrame result: " + result);
            }
        }
        try (var stack = MemoryStack.stackPush()) {
            var init = BGFXInit.calloc(stack);
            bgfx_init_ctor(init);
            init.type(rendererType(config.renderer()));
            init.vendorId(BGFX_PCI_ID_NONE);
            init.debug(config.validation());
            init.profile(true);
            BgfxPlatformDataFactory.populate(init.platformData(), windowContext.windowHandle);
            if (threadedMetal) {
                init.platformData()
                        .nwh(BgfxCocoaLayer.attach(init.platformData().nwh()));
            }
            activeWidth = Math.max(1, windowContext.framebuffer.width);
            activeHeight = Math.max(1, windowContext.framebuffer.height);
            init.resolution().width(activeWidth).height(activeHeight).reset(resetFlags);
            init.limits().maxEncoders((short) 8);
            if (!bgfx_init(init)) {
                throw new IllegalStateException("bgfx_init returned false");
            }
        } catch (RuntimeException | Error e) {
            if (initialized) {
                bgfx_shutdown();
            }
            throw e;
        }
        initialized = true;
        resources = new BgfxResourceRegistry(apiThread);
        bgfx_set_debug(config.profiler() ? BGFX_DEBUG_PROFILER : BGFX_DEBUG_NONE);
        var rendererType = bgfx_get_renderer_type();
        var rendererName = bgfx_get_renderer_name(rendererType);
        if (COPY_POOL_CYCLE_FRAMES > 0 || POOLED_UPLOAD_COPIES) {
            copyPoolFunction = BGFX.getLibrary().getFunctionAddress("cruma_bgfx_set_copy_pool");
            if (copyPoolFunction == 0L) {
                log.warn("Requested upload-copy pool is unavailable in this bgfx runtime");
            } else {
                log.info("Native upload-copy pool available: renderer={}", rendererName);
            }
            updateCopyPoolMode();
        }
        if (rendererType == BGFX_RENDERER_TYPE_VULKAN) {
            videoMemoryFallbackFunction = BGFX.getLibrary().getFunctionAddress("cruma_bgfx_video_memory_fallbacks");
        }
        if (DEBUG_LOOP_TIMINGS) {
            var traceFunction = BGFX.getLibrary().getFunctionAddress("cruma_bgfx_set_frame_trace");
            if (traceFunction != 0L) {
                JNI.invokeV(1, traceFunction);
            }
        }
        var backendFlags = backendResetFlags(rendererType, config.msaaSamples(), MAX_ANISOTROPY, config.vsync());
        if (resetFlags != backendFlags) {
            resetFlags = backendFlags;
            bgfx_reset(activeWidth, activeHeight, resetFlags, BGFX_TEXTURE_FORMAT_COUNT);
        }
        if (rendererType == BGFX_RENDERER_TYPE_VULKAN && !config.vsync()) {
            log.info("Vulkan backend: presenting with vsync regardless of graphics.vsync");
        }
        log.info(
                "Antialiasing: requestedMSAA={} backbufferMSAA={} worldFXAA={}",
                config.msaaSamples(),
                getBackbufferMsaaSamples(),
                config.msaaSamples() > 0);
        capabilities = BgfxCapabilities.copyOf(bgfx_get_caps(), rendererName);
        submissionBudget = new BgfxSubmissionBudget(capabilities.maxDrawCalls());
        bgfx_set_view_mode(BUFFER_COPY_VIEW, BGFX_VIEW_MODE_SEQUENTIAL);
        var failures = capabilities.requiredFailures();
        if (!failures.isEmpty()) {
            close();
            throw new IllegalStateException("bgfx renderer is missing required capabilities: " + failures);
        }
        stats = RenderStats.unavailable("bgfx", extents());
        log.info(
                "Device: renderer={} vendor=0x{} device=0x{} depth01={} bottomLeft={} "
                        + "samplers={} streams={} views={} encoders={} compute={} imageRW={} indirect={} "
                        + "arrays={} blit={} readback={} external={} BC1/2/3/5={}/{}/{}/{} "
                        + "rgba32fVS={} rgba8ImageW={} depthStencil={} depthStencilSampled={}",
                capabilities.renderer(),
                Integer.toHexString(capabilities.vendorId()),
                Integer.toHexString(capabilities.deviceId()),
                capabilities.homogeneousDepth(),
                capabilities.originBottomLeft(),
                capabilities.maxTextureSamplers(),
                capabilities.maxVertexStreams(),
                capabilities.maxViews(),
                capabilities.maxEncoders(),
                capabilities.compute(),
                capabilities.imageReadWrite(),
                capabilities.drawIndirect(),
                capabilities.textureArrays(),
                capabilities.blit(),
                capabilities.readback(),
                capabilities.externalTextures(),
                capabilities.bc1(),
                capabilities.bc2(),
                capabilities.bc3(),
                capabilities.bc5(),
                capabilities.rgba32fVertex(),
                capabilities.rgba8ImageWrite(),
                capabilities.getDepthStencilName(),
                capabilities.depthStencilSampled());
    }

    private void resizeIfNeeded() {
        var requestedWidth = windowContext.framebuffer.width;
        var requestedHeight = windowContext.framebuffer.height;
        if (requestedWidth == activeWidth && requestedHeight == activeHeight) {
            return;
        }
        activeWidth = requestedWidth;
        activeHeight = requestedHeight;
        var width = Math.max(1, requestedWidth);
        var height = Math.max(1, requestedHeight);
        var flags = resetFlags;
        if (requestedWidth == 0 || requestedHeight == 0) {
            flags |= BGFX_RESET_SUSPEND;
        }
        bgfx_reset(width, height, flags, BGFX_TEXTURE_FORMAT_COUNT);
    }

    private RenderStats copyStats() {
        var nativeStats = bgfx_get_stats();
        var cpuFreq = nativeStats.cpuTimerFreq();
        var gpuFreq = nativeStats.gpuTimerFreq();
        var cpuMs = toMilliseconds(nativeStats.cpuTimeBegin(), nativeStats.cpuTimeEnd(), cpuFreq);
        var gpuMs = toMilliseconds(nativeStats.gpuTimeBegin(), nativeStats.gpuTimeEnd(), gpuFreq);
        var views = new ArrayList<ViewTiming>();
        var count = Short.toUnsignedInt(nativeStats.numViews());
        var nativeViews = nativeStats.viewStats();
        for (var i = 0; i < count; i++) {
            var view = nativeViews.get(i);
            views.add(new ViewTiming(
                    view.nameString(),
                    Short.toUnsignedInt(view.view()),
                    toMilliseconds(view.cpuTimeBegin(), view.cpuTimeEnd(), cpuFreq),
                    toMilliseconds(view.gpuTimeBegin(), view.gpuTimeEnd(), gpuFreq)));
        }
        var fallbacks = new ArrayList<String>();
        if (!capabilities.compute()
                || !capabilities.imageReadWrite()
                || !capabilities.r32fImageReadWrite()
                || !capabilities.rgba8ImageWrite()
                || !capabilities.depthStencilSampled()) {
            fallbacks.add("gpu_visibility: compute/image R32F/RGBA8 or sampled depth-stencil unsupported");
        }
        if (capabilities.depthStencilFormat() != BGFX_TEXTURE_FORMAT_D24S8) {
            fallbacks.add(
                    "depth_stencil: D24S8 render targets unsupported, using " + capabilities.getDepthStencilName());
        }
        if (!capabilities.drawIndirect()) {
            fallbacks.add("indirect_submission: draw indirect unsupported");
        }
        var resources = this.resources.snapshot();
        var bufferBytes = resources.bytes(Kind.VERTEX_BUFFER)
                + resources.bytes(Kind.INDEX_BUFFER)
                + resources.bytes(Kind.DYNAMIC_VERTEX_BUFFER)
                + resources.bytes(Kind.DYNAMIC_INDEX_BUFFER)
                + resources.bytes(Kind.INDIRECT_BUFFER);
        return new RenderStats(
                "bgfx",
                capabilities.renderer(),
                config.upscaler().algorithm().name().toLowerCase(),
                extents(),
                frameNumber,
                cpuMs,
                gpuMs,
                nativeStats.numDraw(),
                nativeStats.numCompute(),
                nativeStats.numBlit(),
                nativeStats.textureMemoryUsed(),
                nativeStats.rtMemoryUsed(),
                bufferBytes,
                0,
                views,
                fallbacks,
                RenderDiagnostics.empty());
    }

    private RenderExtents extents() {
        return RenderExtents.resolve(
                Math.max(0, windowContext.framebuffer.width),
                Math.max(0, windowContext.framebuffer.height),
                config.upscaler().requestedScale(),
                1.0,
                new Extent(windowContext.capture.width, windowContext.capture.height));
    }

    private void updateCopyPoolMode() {
        if (copyPoolFunction == 0) {
            return;
        }
        var enabled = copyPoolModeRequested(frameNumber);
        if (enabled != copyPoolEnabled) {
            JNI.invokeV(enabled ? 1 : 0, copyPoolFunction);
            copyPoolEnabled = enabled;
            if (COPY_POOL_CYCLE_FRAMES > 0) {
                log.info("Copy pool cycle: nextLoopFrame={} enabled={}", frameNumber + 1, enabled);
            }
        }
    }

    private void checkApiThread() {
        if (Thread.currentThread() != apiThread) {
            throw new IllegalStateException("bgfx frame API called from a non-owner thread");
        }
    }

    private void checkOpen() {
        if (!initialized) {
            throw new IllegalStateException("bgfx device is closed");
        }
    }

    private static double toMilliseconds(long begin, long end, long frequency) {
        if (frequency <= 0L || begin < 0L || end < begin) {
            return Double.NaN;
        }
        return (end - begin) * 1000.0 / frequency;
    }

    private static int rendererType(Renderer renderer) {
        return switch (renderer) {
            case AUTO -> BGFX_RENDERER_TYPE_COUNT;
            case METAL -> BGFX_RENDERER_TYPE_METAL;
            case DIRECT3D11 -> BGFX_RENDERER_TYPE_DIRECT3D11;
            case DIRECT3D12 -> BGFX_RENDERER_TYPE_DIRECT3D12;
            case VULKAN -> BGFX_RENDERER_TYPE_VULKAN;
            case NOOP -> BGFX_RENDERER_TYPE_NOOP;
        };
    }

    private static RuntimeException merge(RuntimeException first, RuntimeException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }
}
