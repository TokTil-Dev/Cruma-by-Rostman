package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.viewer.render.api.RenderDiagnostics;
import com.rost.cruma.viewer.render.api.RenderDiagnostics.CpuPhase;
import com.rost.cruma.viewer.render.api.RenderDiagnostics.DynamicResolution;
import com.rost.cruma.viewer.render.api.RenderDiagnostics.FamilyCounter;
import com.rost.cruma.viewer.render.api.RenderDiagnostics.OptimizationStatus;
import com.rost.cruma.viewer.render.api.RenderDiagnostics.ParallelEncoding;
import com.rost.cruma.viewer.render.api.RenderDiagnostics.PhaseTiming;
import com.rost.cruma.viewer.render.api.RenderDiagnostics.Reconciliation;
import com.rost.cruma.viewer.render.api.RenderDiagnostics.ResolutionDomain;
import com.rost.cruma.viewer.render.api.RenderDiagnostics.Visibility;
import com.rost.cruma.viewer.render.api.RenderExtents;
import com.rost.cruma.viewer.render.api.RenderExtents.Domain;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.api.RenderFrameSnapshot.RenderFamily;
import com.rost.cruma.viewer.render.api.RenderStats;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig;
import com.rost.cruma.viewer.render.upscale.UpscalerProvider.Plan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class BgfxPerformanceTracker {
    private static final CpuPhase[] PHASES = CpuPhase.values();
    private static final RenderFamily[] FAMILIES = RenderFamily.values();

    private final boolean enabled;

    private final long[] phaseNanos = new long[PHASES.length];
    private final boolean[] measuredPhases = new boolean[PHASES.length];
    private final int[] directDraws = new int[FAMILIES.length];
    private final int[] auxiliaryDraws = new int[FAMILIES.length];
    private final int[] indirectCommands = new int[FAMILIES.length];
    private final int[] instances = new int[FAMILIES.length];
    private final long[] triangles = new long[FAMILIES.length];

    private int reflectionReplays;
    private int reflectionUpdates;
    private int reflectionReuses;
    private long directSceneNanos;
    private DynamicResolution dynamicResolution = DynamicResolution.disabled("not configured");
    private Visibility visibility = Visibility.empty();
    private ParallelEncoding parallelEncoding = ParallelEncoding.disabled("not configured");
    private List<OptimizationStatus> optimizations = List.of();

    BgfxPerformanceTracker() {
        this(true);
    }

    BgfxPerformanceTracker(boolean enabled) {
        this.enabled = enabled;
    }

    boolean isEnabled() {
        return enabled;
    }

    long start() {
        return enabled ? System.nanoTime() : 0L;
    }

    long getElapsed(long started) {
        return enabled ? Math.max(0L, System.nanoTime() - started) : 0L;
    }

    void beginFrame() {
        if (!enabled) {
            return;
        }
        Arrays.fill(phaseNanos, 0L);
        Arrays.fill(measuredPhases, false);
        Arrays.fill(directDraws, 0);
        Arrays.fill(auxiliaryDraws, 0);
        Arrays.fill(indirectCommands, 0);
        Arrays.fill(instances, 0);
        Arrays.fill(triangles, 0L);
        reflectionReplays = 0;
        reflectionUpdates = 0;
        reflectionReuses = 0;
        directSceneNanos = 0L;
        visibility = Visibility.empty();
        parallelEncoding = ParallelEncoding.disabled("not active this frame");
    }

    void addPhase(CpuPhase phase, long nanos) {
        if (!enabled) {
            return;
        }
        if (nanos < 0L) {
            throw new IllegalArgumentException("phase duration cannot be negative");
        }
        var ordinal = phase.ordinal();
        phaseNanos[ordinal] += nanos;
        measuredPhases[ordinal] = true;
    }

    void addDraw(RenderFamily family, boolean auxiliary, boolean indirect, int drawInstances, long drawTriangles) {
        if (!enabled) {
            return;
        }
        var ordinal = family.ordinal();
        if (auxiliary) {
            auxiliaryDraws[ordinal]++;
        } else {
            directDraws[ordinal]++;
        }
        if (indirect) {
            indirectCommands[ordinal]++;
        }
        instances[ordinal] = Math.addExact(instances[ordinal], drawInstances);
        triangles[ordinal] = Math.addExact(triangles[ordinal], drawTriangles);
        if (auxiliary) {
            reflectionReplays++;
        }
    }

    void addIndirectBatch(RenderFamily family, int commands, int drawInstances, long drawTriangles) {
        addIndirectBatch(family, false, commands, drawInstances, drawTriangles);
    }

    void addIndirectBatch(RenderFamily family, boolean auxiliary, int commands, int drawInstances, long drawTriangles) {
        if (!enabled) {
            return;
        }
        if (commands < 1 || drawInstances < 0 || drawTriangles < 0L) {
            throw new IllegalArgumentException("invalid indirect batch counters");
        }
        var ordinal = family.ordinal();
        if (auxiliary) {
            auxiliaryDraws[ordinal] = Math.addExact(auxiliaryDraws[ordinal], commands);
            reflectionReplays = Math.addExact(reflectionReplays, commands);
        } else {
            directDraws[ordinal] = Math.addExact(directDraws[ordinal], commands);
        }
        indirectCommands[ordinal] = Math.addExact(indirectCommands[ordinal], commands);
        instances[ordinal] = Math.addExact(instances[ordinal], drawInstances);
        triangles[ordinal] = Math.addExact(triangles[ordinal], drawTriangles);
    }

    void reflections(int updates, int reuses) {
        if (!enabled) {
            return;
        }
        reflectionUpdates = updates;
        reflectionReuses = reuses;
    }

    void dynamicResolution(DynamicResolution value) {
        if (!enabled) {
            return;
        }
        dynamicResolution = value;
    }

    void visibility(Visibility value) {
        if (!enabled) {
            return;
        }
        visibility = value;
    }

    void parallelEncoding(ParallelEncoding value) {
        if (!enabled) {
            return;
        }
        parallelEncoding = value;
    }

    void directSceneSubmission(long nanos) {
        if (!enabled) {
            return;
        }
        if (nanos < 0L) {
            throw new IllegalArgumentException("direct scene duration cannot be negative");
        }
        directSceneNanos = nanos;
    }

    void optimizations(List<OptimizationStatus> value) {
        if (!enabled) {
            return;
        }
        optimizations = List.copyOf(value);
    }

    RenderDiagnostics snapshot(
            RenderStats base,
            RenderExtents extents,
            Plan plan,
            ViewerGraphicsConfig graphics,
            Extent reflectionExtent,
            boolean directToBackbuffer) {
        if (!enabled) {
            return RenderDiagnostics.empty();
        }
        var phases = new ArrayList<PhaseTiming>(PHASES.length);
        for (var phase : PHASES) {
            var ordinal = phase.ordinal();
            phases.add(new PhaseTiming(phase, phaseNanos[ordinal] / 1_000_000.0, measuredPhases[ordinal]));
        }
        var families = new ArrayList<FamilyCounter>(FAMILIES.length);
        for (var family : FAMILIES) {
            var ordinal = family.ordinal();
            families.add(new FamilyCounter(
                    family,
                    directDraws[ordinal],
                    auxiliaryDraws[ordinal],
                    indirectCommands[ordinal],
                    instances[ordinal],
                    triangles[ordinal]));
        }

        var domains = resolutionDomains(extents, plan, graphics, reflectionExtent, directToBackbuffer);
        var familyDraws = 0;
        long familyInstances = 0L;
        long familyTriangles = 0L;
        for (var ordinal = 0; ordinal < FAMILIES.length; ordinal++) {
            familyDraws = Math.addExact(familyDraws, directDraws[ordinal] + auxiliaryDraws[ordinal]);
            familyInstances = Math.addExact(familyInstances, instances[ordinal]);
            familyTriangles = Math.addExact(familyTriangles, triangles[ordinal]);
        }
        var rendererDraws = Math.max(0, base.draws());
        var attributedGpu = 0.0;
        var hasGpuTiming = false;
        for (var view : base.views()) {
            if (Double.isFinite(view.gpuMs()) && view.gpuMs() >= 0.0) {
                attributedGpu += view.gpuMs();
                hasGpuTiming = true;
            }
        }
        var totalGpu = base.gpuFrameMs();
        var residual = hasGpuTiming && Double.isFinite(totalGpu) ? Math.max(0.0, totalGpu - attributedGpu) : Double.NaN;
        long accountedSceneNanos = 0L;
        for (var phase : new CpuPhase[] {
            CpuPhase.MOVEMENT_UPDATE,
            CpuPhase.TRAVERSAL,
            CpuPhase.CANDIDATE_GENERATION,
            CpuPhase.VISIBILITY,
            CpuPhase.COMMAND_ENCODING
        }) {
            accountedSceneNanos = Math.addExact(accountedSceneNanos, phaseNanos[phase.ordinal()]);
        }
        var cpuCoverage =
                directSceneNanos > 0L ? Math.min(1.0, accountedSceneNanos / (double) directSceneNanos) : Double.NaN;
        var gpuCoverage = hasGpuTiming && Double.isFinite(totalGpu) && totalGpu > 0.0
                ? Math.min(1.0, attributedGpu / totalGpu)
                : Double.NaN;
        var reconciliation = new Reconciliation(
                familyDraws,
                rendererDraws,
                Math.max(0, rendererDraws - familyDraws),
                familyInstances,
                familyTriangles,
                familyDraws <= rendererDraws,
                directSceneNanos > 0L ? directSceneNanos / 1_000_000.0 : Double.NaN,
                accountedSceneNanos / 1_000_000.0,
                cpuCoverage,
                gpuCoverage);
        return new RenderDiagnostics(
                phases,
                families,
                optimizations,
                domains,
                dynamicResolution,
                visibility,
                parallelEncoding,
                reconciliation,
                reflectionReplays,
                reflectionUpdates,
                reflectionReuses,
                hasGpuTiming ? attributedGpu : Double.NaN,
                residual);
    }

    private static List<ResolutionDomain> resolutionDomains(
            RenderExtents extents,
            Plan plan,
            ViewerGraphicsConfig graphics,
            Extent activeReflection,
            boolean directToBackbuffer) {
        var result = new ArrayList<ResolutionDomain>(6);
        addDomain(result, Domain.DISPLAY, extents.display(), "BGRA8", 1);
        addDomain(
                result,
                Domain.RENDER,
                extents.render(),
                directToBackbuffer ? "BGRA8/backbuffer" : "RGBA8+D24S8/FXAA",
                directToBackbuffer ? Math.max(1, graphics.msaaSamples()) : 1);
        addDomain(result, Domain.REFLECTION, activeReflection, "RGBA8+D24S8", 1);
        addDomain(result, Domain.BLOOM_QUARTER, extents.bloomQuarter(), "RGBA8", 1);
        addDomain(result, Domain.CAPTURE, extents.capture(), "RGBA8", 1);
        if (plan != null && "fsr1".equals(plan.provider()) && plan.rcas()) {
            addDomain(result, Domain.DISPLAY, extents.display(), "RGBA8/RCAS", 1);
        }
        return List.copyOf(result);
    }

    private static void addDomain(
            List<ResolutionDomain> target, Domain domain, Extent extent, String format, int samples) {
        target.add(new ResolutionDomain(
                domain, extent.width(), extent.height(), format, samples, extent.pixels() * samples));
    }
}
