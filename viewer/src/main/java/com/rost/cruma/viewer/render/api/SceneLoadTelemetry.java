package com.rost.cruma.viewer.render.api;

public record SceneLoadTelemetry(
        int uploadedMaps,
        long uploadNanos,
        long stagingCopyCalls,
        long stagingCopyNanos,
        long stagingCopyBytes,
        long indexSubmissions,
        long stagedIndexBytes,
        long submittedIndexBytes,
        long instanceSubmissions,
        long instanceUploadedBytes,
        int geometryPoolChunks,
        long geometryPoolCapacityBytes,
        long geometryPoolLiveBytes,
        int instancePoolChunks,
        long instancePoolCapacityBytes,
        long instancePoolLiveBytes,
        long topologyBuilds,
        long topologyNanos,
        long topologyUploadBytes) {
    public SceneLoadTelemetry {
        if (uploadedMaps < 0
                || uploadNanos < 0L
                || stagingCopyCalls < 0L
                || stagingCopyNanos < 0L
                || stagingCopyBytes < 0L
                || indexSubmissions < 0L
                || stagedIndexBytes < 0L
                || submittedIndexBytes < 0L
                || instanceSubmissions < 0L
                || instanceUploadedBytes < 0L
                || geometryPoolChunks < 0
                || geometryPoolCapacityBytes < 0L
                || geometryPoolLiveBytes < 0L
                || instancePoolChunks < 0
                || instancePoolCapacityBytes < 0L
                || instancePoolLiveBytes < 0L
                || topologyBuilds < 0L
                || topologyNanos < 0L
                || topologyUploadBytes < 0L) {
            throw new IllegalArgumentException("scene load telemetry counters cannot be negative");
        }
    }

    public static SceneLoadTelemetry empty() {
        return new SceneLoadTelemetry(0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0, 0L, 0L, 0, 0L, 0L, 0L, 0L, 0L);
    }
}
