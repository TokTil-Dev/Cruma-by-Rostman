package com.rost.cruma.viewer.render.api;

import java.util.List;

public record RenderStats(
        String backend,
        String renderer,
        String activeUpscaler,
        RenderExtents extents,
        long frameNumber,
        double cpuFrameMs,
        double gpuFrameMs,
        int draws,
        int computes,
        int blits,
        long textureBytes,
        long renderTargetBytes,
        long bufferBytes,
        int readbackQueueDepth,
        List<ViewTiming> views,
        List<String> fallbackReasons,
        RenderDiagnostics diagnostics) {
    public RenderStats {
        backend = backend == null ? "unknown" : backend;
        renderer = renderer == null ? "unknown" : renderer;
        activeUpscaler = activeUpscaler == null ? "native" : activeUpscaler;
        views = views == null ? List.of() : List.copyOf(views);
        fallbackReasons = fallbackReasons == null ? List.of() : List.copyOf(fallbackReasons);
        diagnostics = diagnostics == null ? RenderDiagnostics.empty() : diagnostics;
    }

    public static RenderStats unavailable(String backend, RenderExtents extents) {
        return new RenderStats(
                backend,
                "uninitialized",
                "native",
                extents,
                0,
                Double.NaN,
                Double.NaN,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                List.of(),
                List.of(),
                RenderDiagnostics.empty());
    }

    public record ViewTiming(String name, int id, double cpuMs, double gpuMs) {}
}
