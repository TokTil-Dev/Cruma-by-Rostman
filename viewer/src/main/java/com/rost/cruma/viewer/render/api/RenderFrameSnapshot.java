package com.rost.cruma.viewer.render.api;

import com.rost.cruma.core.geometry.Bounds3f;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RenderFrameSnapshot {
    private final long frameNumber;
    private final double simulationSeconds;
    private final RenderExtents extents;
    private final Camera camera;
    private final Settings settings;
    private final List<DrawCandidate> candidates;
    private final Set<HistoryReset> historyResets;

    public RenderFrameSnapshot(
            long frameNumber,
            double simulationSeconds,
            RenderExtents extents,
            Camera camera,
            Settings settings,
            List<DrawCandidate> candidates,
            Set<HistoryReset> historyResets) {
        if (frameNumber < 0L
                || !Double.isFinite(simulationSeconds)
                || simulationSeconds < 0.0
                || extents == null
                || camera == null
                || settings == null
                || candidates == null
                || historyResets == null) {
            throw new IllegalArgumentException("invalid render-frame snapshot");
        }
        this.frameNumber = frameNumber;
        this.simulationSeconds = simulationSeconds;
        this.extents = extents;
        this.camera = camera;
        this.settings = settings;
        this.candidates = List.copyOf(candidates);
        this.historyResets = Set.copyOf(historyResets);
        validateCandidates(this.candidates);
    }

    public long getFrameNumber() {
        return frameNumber;
    }

    public double getSimulationSeconds() {
        return simulationSeconds;
    }

    public RenderExtents getExtents() {
        return extents;
    }

    public Camera getCamera() {
        return camera;
    }

    public Settings getSettings() {
        return settings;
    }

    public List<DrawCandidate> getCandidates() {
        return candidates;
    }

    public Set<HistoryReset> getHistoryResets() {
        return historyResets;
    }

    public boolean isHistoryValid() {
        return historyResets.isEmpty();
    }

    private static void validateCandidates(List<DrawCandidate> candidates) {
        var stableIds = new HashSet<Long>(Math.max(16, candidates.size() * 2));
        for (var candidate : candidates) {
            if (candidate == null) {
                throw new IllegalArgumentException("frame candidates cannot contain null");
            }
            if (!stableIds.add(candidate.stableId())) {
                throw new IllegalArgumentException("duplicate frame candidate stable ID " + candidate.stableId());
            }
        }
    }

    public record Camera(
            float x,
            float y,
            float z,
            Matrix4fSnapshot view,
            Matrix4fSnapshot projection,
            Matrix4fSnapshot previousViewProjection,
            boolean ortho,
            float jitterX,
            float jitterY) {
        public Camera {
            if (!Float.isFinite(x)
                    || !Float.isFinite(y)
                    || !Float.isFinite(z)
                    || !Float.isFinite(jitterX)
                    || !Float.isFinite(jitterY)
                    || view == null
                    || projection == null
                    || previousViewProjection == null) {
                throw new IllegalArgumentException("invalid camera snapshot");
            }
        }
    }

    public record Settings(
            long surfaceFilter, boolean capture, boolean timeOfDay, boolean bloom, boolean water, boolean particles) {}

    public record DrawCandidate(
            long stableId,
            long geometryId,
            long materialId,
            RenderFamily family,
            Bounds3f worldBounds,
            Matrix4fSnapshot model,
            int firstIndex,
            int indexCount,
            int firstInstance,
            int instanceCount,
            long stateKey,
            float depthKey,
            boolean ordered) {
        public DrawCandidate {
            if (stableId < 0L
                    || geometryId < 0L
                    || materialId < 0L
                    || family == null
                    || worldBounds == null
                    || model == null
                    || firstIndex < 0
                    || indexCount < 1
                    || firstInstance < 0
                    || instanceCount < 1
                    || !Float.isFinite(depthKey)) {
                throw new IllegalArgumentException("invalid draw candidate");
            }
            if (ordered && family.isGpuDrivenEligible()) {
                throw new IllegalArgumentException("ordered candidates cannot enter a GPU-driven family");
            }
        }
    }

    public enum RenderFamily {
        SKY(false),
        TERRAIN_OPAQUE(true),
        CSG_OPAQUE(true),
        STATIC_MESH_OPAQUE(true),
        MASKED_OPAQUE(true),
        TRANSLUCENT(false),
        WATER(false),
        PARTICLE(false),
        BILLBOARD(false),
        BEAM(false),
        DEBUG(false);

        private final boolean gpuDrivenEligible;

        RenderFamily(boolean gpuDrivenEligible) {
            this.gpuDrivenEligible = gpuDrivenEligible;
        }

        public boolean isGpuDrivenEligible() {
            return gpuDrivenEligible;
        }
    }

    public enum HistoryReset {
        FIRST_FRAME,
        CAMERA_CUT,
        RESIZE,
        RENDER_SCALE,
        PROVIDER_CHANGE,
        MAP_CHANGE,
        ORTHO_CHANGE,
        TOPOLOGY_CHANGE
    }
}
