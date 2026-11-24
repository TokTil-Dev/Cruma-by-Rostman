package com.rost.cruma.viewer.render.api;

import com.rost.cruma.viewer.render.api.RenderExtents.Domain;
import com.rost.cruma.viewer.render.api.RenderFrameSnapshot.RenderFamily;
import java.util.List;

public record RenderDiagnostics(
        List<PhaseTiming> cpuPhases,
        List<FamilyCounter> families,
        List<OptimizationStatus> optimizations,
        List<ResolutionDomain> resolutionDomains,
        DynamicResolution dynamicResolution,
        Visibility visibility,
        ParallelEncoding parallelEncoding,
        Reconciliation reconciliation,
        int reflectionReplays,
        int reflectionUpdates,
        int reflectionReuses,
        double attributedGpuMs,
        double residualGpuMs) {
    public RenderDiagnostics {
        cpuPhases = cpuPhases == null ? List.of() : List.copyOf(cpuPhases);
        families = families == null ? List.of() : List.copyOf(families);
        optimizations = optimizations == null ? List.of() : List.copyOf(optimizations);
        resolutionDomains = resolutionDomains == null ? List.of() : List.copyOf(resolutionDomains);
        dynamicResolution =
                dynamicResolution == null ? DynamicResolution.disabled("not configured") : dynamicResolution;
        visibility = visibility == null ? Visibility.empty() : visibility;
        parallelEncoding = parallelEncoding == null ? ParallelEncoding.disabled("not configured") : parallelEncoding;
        reconciliation = reconciliation == null ? Reconciliation.empty() : reconciliation;
        if (reflectionReplays < 0 || reflectionUpdates < 0 || reflectionReuses < 0) {
            throw new IllegalArgumentException("reflection counters cannot be negative");
        }
    }

    public static RenderDiagnostics empty() {
        return new RenderDiagnostics(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                DynamicResolution.disabled("unavailable"),
                Visibility.empty(),
                ParallelEncoding.disabled("unavailable"),
                Reconciliation.empty(),
                0,
                0,
                0,
                Double.NaN,
                Double.NaN);
    }

    public static RenderDiagnostics forLegacyRenderer(int draws, double directSceneCpuMs) {
        if (draws < 0 || !Double.isFinite(directSceneCpuMs) || directSceneCpuMs < 0.0) {
            return empty();
        }
        return new RenderDiagnostics(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                DynamicResolution.disabled("legacy backend"),
                Visibility.empty(),
                ParallelEncoding.disabled("legacy backend"),
                new Reconciliation(
                        0, draws, draws, 0L, 0L, draws == 0, directSceneCpuMs, Double.NaN, Double.NaN, Double.NaN),
                0,
                0,
                0,
                Double.NaN,
                Double.NaN);
    }

    public enum CpuPhase {
        TOPOLOGY_UPDATE,
        MOVEMENT_UPDATE,
        TRAVERSAL,
        CANDIDATE_GENERATION,
        VISIBILITY,
        SORTING,
        FX_SIMULATION,
        FX_MESH_ENCODING,
        FX_BILLBOARD_ENCODING,
        FX_BILLBOARD_SCAN,
        FX_BILLBOARD_PACK,
        FX_BILLBOARD_UPLOAD,
        FX_BILLBOARD_SUBMIT,
        FX_BEAM_ENCODING,
        FX_ORDERING,
        FX_SUBMISSION,
        MATERIAL_TABLE_UPDATE,
        OPAQUE_ENCODING,
        TRANSLUCENT_ENCODING,
        PROJECTOR_RECEIVERS,
        COMMAND_ENCODING,
        RESOURCE_UPLOAD,
        READBACK
    }

    public record PhaseTiming(CpuPhase phase, double milliseconds, boolean measured) {
        public PhaseTiming {
            if (phase == null || measured && (!Double.isFinite(milliseconds) || milliseconds < 0.0)) {
                throw new IllegalArgumentException("invalid CPU phase timing");
            }
            if (!measured) {
                milliseconds = Double.NaN;
            }
        }
    }

    public record FamilyCounter(
            RenderFamily family,
            int directDraws,
            int auxiliaryDraws,
            int indirectCommands,
            int instances,
            long triangles) {
        public FamilyCounter {
            if (family == null
                    || directDraws < 0
                    || auxiliaryDraws < 0
                    || indirectCommands < 0
                    || instances < 0
                    || triangles < 0L) {
                throw new IllegalArgumentException("invalid render-family counters");
            }
        }
    }

    public record OptimizationStatus(
            String name,
            boolean requested,
            boolean active,
            String reason,
            long savedDraws,
            long shadedPixelProxy,
            double gpuMilliseconds) {
        public OptimizationStatus(
                String name,
                boolean requested,
                boolean active,
                String reason,
                long savedDraws,
                double gpuMilliseconds) {
            this(name, requested, active, reason, savedDraws, 0L, gpuMilliseconds);
        }

        public OptimizationStatus {
            if (name == null || name.isBlank() || savedDraws < 0L || shadedPixelProxy < 0L) {
                throw new IllegalArgumentException("invalid optimization status");
            }
            reason = reason == null ? "" : reason;
            if (active && !requested) {
                throw new IllegalArgumentException("an unrequested optimization cannot be active");
            }
        }
    }

    public record ResolutionDomain(Domain domain, int width, int height, String format, int samples, long pixels) {
        public ResolutionDomain {
            if (domain == null
                    || width < 0
                    || height < 0
                    || format == null
                    || format.isBlank()
                    || samples < 1
                    || pixels != (long) width * height * samples) {
                throw new IllegalArgumentException("invalid resolution-domain accounting");
            }
        }
    }

    public record DynamicResolution(
            boolean requested,
            boolean active,
            double targetFrameMs,
            double currentScale,
            double controllerErrorMs,
            int cooldownRemaining,
            String disabledReason) {
        public DynamicResolution {
            disabledReason = disabledReason == null ? "" : disabledReason;
            if (!Double.isFinite(targetFrameMs)
                    || targetFrameMs <= 0.0
                    || !Double.isFinite(currentScale)
                    || currentScale <= 0.0
                    || !Double.isFinite(controllerErrorMs)
                    || cooldownRemaining < 0) {
                throw new IllegalArgumentException("invalid dynamic-resolution diagnostics");
            }
            if (active && !requested) {
                throw new IllegalArgumentException("unrequested dynamic resolution cannot be active");
            }
        }

        public static DynamicResolution disabled(String reason) {
            return new DynamicResolution(false, false, 16.67, 1.0, 0.0, 0, reason);
        }
    }

    public record Visibility(
            int traversedObjects,
            int candidates,
            int frustumCulled,
            int distanceCulled,
            int occlusionCulled,
            int compacted,
            int overflowFallbacks,
            boolean cpuFallback,
            boolean historyValid,
            boolean countersValid) {
        public Visibility {
            if (traversedObjects < 0
                    || candidates < 0
                    || frustumCulled < 0
                    || distanceCulled < 0
                    || occlusionCulled < 0
                    || compacted < 0
                    || overflowFallbacks < 0
                    || compacted > candidates) {
                throw new IllegalArgumentException("invalid visibility diagnostics");
            }
        }

        public static Visibility empty() {
            return new Visibility(0, 0, 0, 0, 0, 0, 0, true, false, false);
        }
    }

    public record ParallelEncoding(
            boolean requested,
            boolean active,
            int workers,
            double queueWaitMs,
            double encodeMs,
            double joinMs,
            String reason) {
        public ParallelEncoding {
            reason = reason == null ? "" : reason;
            if (workers < 0
                    || !isFiniteOrNaN(queueWaitMs)
                    || !isFiniteOrNaN(encodeMs)
                    || !isFiniteOrNaN(joinMs)
                    || active && (!requested || workers < 2)) {
                throw new IllegalArgumentException("invalid parallel-encoding diagnostics");
            }
        }

        public static ParallelEncoding disabled(String reason) {
            return new ParallelEncoding(false, false, 0, Double.NaN, Double.NaN, Double.NaN, reason);
        }
    }

    public record Reconciliation(
            int familyDraws,
            int rendererDraws,
            int untrackedDraws,
            long familyInstances,
            long familyTriangles,
            boolean exactFamilyTotals,
            double directSceneCpuMs,
            double accountedSceneCpuMs,
            double cpuCoverage,
            double gpuCoverage) {
        public Reconciliation {
            if (familyDraws < 0
                    || rendererDraws < 0
                    || untrackedDraws < 0
                    || familyInstances < 0L
                    || familyTriangles < 0L
                    || !isFiniteRatioOrNaN(cpuCoverage)
                    || !isFiniteRatioOrNaN(gpuCoverage)
                    || !isFiniteOrNaN(directSceneCpuMs)
                    || !isFiniteOrNaN(accountedSceneCpuMs)) {
                throw new IllegalArgumentException("invalid render-counter reconciliation");
            }
        }

        public static Reconciliation empty() {
            return new Reconciliation(0, 0, 0, 0L, 0L, true, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
    }

    private static boolean isFiniteOrNaN(double value) {
        return Double.isNaN(value) || Double.isFinite(value) && value >= 0.0;
    }

    private static boolean isFiniteRatioOrNaN(double value) {
        return Double.isNaN(value) || Double.isFinite(value) && value >= 0.0 && value <= 1.000001;
    }
}
