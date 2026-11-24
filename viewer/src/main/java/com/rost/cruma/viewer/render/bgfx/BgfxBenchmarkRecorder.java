package com.rost.cruma.viewer.render.bgfx;

import com.alibaba.fastjson2.JSON;
import com.rost.cruma.viewer.render.ViewerBenchmarkProperties;
import com.rost.cruma.viewer.render.ViewerBenchmarkScenario;
import com.rost.cruma.viewer.render.api.RenderDiagnostics;
import com.rost.cruma.viewer.render.api.RenderDiagnostics.CpuPhase;
import com.rost.cruma.viewer.render.api.RenderStats;
import com.rost.cruma.viewer.render.api.SceneCensus;
import com.rost.cruma.viewer.render.api.SceneUploadStats;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

public final class BgfxBenchmarkRecorder implements AutoCloseable {
    public static final String OUTPUT_PROPERTY = ViewerBenchmarkProperties.OUTPUT;
    public static final String SCENARIO_PROPERTY = ViewerBenchmarkProperties.SCENARIO;
    public static final String WARMUP_PROPERTY = ViewerBenchmarkProperties.WARMUP_FRAMES;
    public static final String FRAMES_PROPERTY = ViewerBenchmarkProperties.FRAMES;
    public static final String CHECKPOINT_PROPERTY = ViewerBenchmarkProperties.CHECKPOINT;
    public static final String COMMIT_PROPERTY = ViewerBenchmarkProperties.COMMIT;

    private static final int QUEUE_CAPACITY = 2_048;
    private static final Object STOP = new Object();

    private final boolean enabled;
    private final int warmupFrames;
    private final int measuredFrames;
    private final ArrayBlockingQueue<Object> queue;
    private final AtomicReference<Throwable> failure;
    private final Thread writerThread;

    private long seenFrames;
    private long queuedFrames;
    private boolean closed;

    private BgfxBenchmarkRecorder() {
        enabled = false;
        warmupFrames = 0;
        measuredFrames = 0;
        queue = null;
        failure = null;
        writerThread = null;
    }

    private BgfxBenchmarkRecorder(
            Path output,
            String scenario,
            String checkpoint,
            String commit,
            int warmupFrames,
            int measuredFrames,
            ViewerGraphicsConfig graphics,
            Object capabilities,
            int backbufferMsaaSamples,
            Map<String, Object> shaderManifest) {
        this.enabled = true;
        this.warmupFrames = warmupFrames;
        this.measuredFrames = measuredFrames;
        this.queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        this.failure = new AtomicReference<>();
        var scenarioContract = ViewerBenchmarkScenario.load().getContract();
        if (!scenario.equals(scenarioContract.get("id"))) {
            throw new IllegalArgumentException("scenario contract does not match " + scenario);
        }
        this.writerThread = new Thread(
                () -> writeLoop(
                        output,
                        createHeader(
                                scenario,
                                checkpoint,
                                commit,
                                warmupFrames,
                                measuredFrames,
                                graphics,
                                capabilities,
                                backbufferMsaaSamples,
                                shaderManifest,
                                scenarioContract)),
                "viewer-bgfx-benchmark-writer");
        this.writerThread.setDaemon(false);
        this.writerThread.start();
    }

    static BgfxBenchmarkRecorder open(
            ViewerGraphicsConfig graphics, BgfxCapabilities capabilities, int backbufferMsaaSamples) {
        var raw = System.getProperty(OUTPUT_PROPERTY, "").trim();
        if (raw.isEmpty()) {
            return new BgfxBenchmarkRecorder();
        }
        var warmup = positiveOrZeroProperty(WARMUP_PROPERTY, 600);
        var frames = positiveProperty(FRAMES_PROPERTY, 1_000);
        var scenario = requiredRecordedProperty(SCENARIO_PROPERTY).toUpperCase(Locale.ROOT);
        var checkpoint = requiredRecordedProperty(CHECKPOINT_PROPERTY).toUpperCase(Locale.ROOT);
        var commit = requiredCommit();
        return new BgfxBenchmarkRecorder(
                Path.of(raw).toAbsolutePath().normalize(),
                scenario,
                checkpoint,
                commit,
                warmup,
                frames,
                graphics,
                capabilities,
                backbufferMsaaSamples,
                BgfxShaderResources.releaseManifest(capabilities));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isComplete() {
        return enabled && queuedFrames >= measuredFrames;
    }

    public long getSeenFrames() {
        return seenFrames;
    }

    public int getWarmupFrames() {
        return warmupFrames;
    }

    public boolean isInventoryCaptureNeeded() {
        return enabled
                && queuedFrames < measuredFrames
                && (seenFrames == 0L && warmupFrames > 0
                        || seenFrames >= warmupFrames && (queuedFrames == 0L || queuedFrames == measuredFrames - 1L));
    }

    public void record(RenderStats stats) {
        record(stats, null, null, Double.NaN);
    }

    public void record(RenderStats stats, SceneCensus sceneCensus, SceneUploadStats uploadStats) {
        record(stats, sceneCensus, uploadStats, Double.NaN);
    }

    public void record(RenderStats stats, SceneCensus sceneCensus, SceneUploadStats uploadStats, double loopFrameMs) {
        record(stats, sceneCensus, uploadStats, loopFrameMs, null);
    }

    void record(
            RenderStats stats,
            SceneCensus sceneCensus,
            SceneUploadStats uploadStats,
            double loopFrameMs,
            GpuSubmissionCounts submissions) {
        if (!enabled || queuedFrames >= measuredFrames) {
            return;
        }
        checkFailure();
        var frame = seenFrames++;
        if (frame < warmupFrames) {
            return;
        }
        var runtime = Runtime.getRuntime();
        var captureInventory = queuedFrames == 0L || queuedFrames == measuredFrames - 1L;
        var row = new FrameRow(
                queuedFrames,
                stats,
                Math.max(0L, runtime.totalMemory() - runtime.freeMemory()),
                loopFrameMs,
                captureInventory ? sceneCensus : null,
                captureInventory ? uploadStats : null,
                submissions);
        if (!queue.offer(row)) {
            throw new IllegalStateException(
                    "benchmark writer queue exhausted at frame " + queuedFrames + "; the artifact would be incomplete");
        }
        queuedFrames++;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (!enabled) {
            return;
        }
        while (!queue.offer(STOP)) {
            checkFailure();
            Thread.onSpinWait();
        }
        try {
            writerThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while closing benchmark artifact", e);
        }
        checkFailure();
    }

    private void writeLoop(Path output, Map<String, Object> header) {
        try {
            var parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (var writer = Files.newBufferedWriter(
                    output,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                writeJsonLine(writer, header);
                var cpu = new ArrayList<Double>();
                var gpu = new ArrayList<Double>();
                var totalFrame = new ArrayList<Double>();
                var loopFrame = new ArrayList<Double>();
                var cpuCoverage = new ArrayList<Double>();
                var gpuCoverage = new ArrayList<Double>();
                var directSceneCpu = new ArrayList<Double>();
                var candidateVisibilityCpu = new ArrayList<Double>();
                var textureBytes = new ArrayList<Double>();
                var renderTargetBytes = new ArrayList<Double>();
                var bufferBytes = new ArrayList<Double>();
                var heapBytes = new ArrayList<Double>();
                var readbackDepth = new ArrayList<Double>();
                var draws = new ArrayList<Double>();
                var viewCount = new ArrayList<Double>();
                var reflectionReplays = new ArrayList<Double>();
                var reflectionUpdates = new ArrayList<Double>();
                var reflectionReuses = new ArrayList<Double>();
                var cpuPhases = new LinkedHashMap<String, ArrayList<Double>>();
                var gpuViews = new LinkedHashMap<String, ArrayList<Double>>();
                var gpuViewGroups = new LinkedHashMap<String, ArrayList<Double>>();
                var activeUpscalerFrames = new LinkedHashMap<String, Long>();
                var optimizationRequestedFrames = new LinkedHashMap<String, Long>();
                var optimizationActiveFrames = new LinkedHashMap<String, Long>();
                var fallbackReasons = new LinkedHashMap<String, Long>();
                SceneCensus firstSceneCensus = null;
                SceneCensus finalSceneCensus = null;
                SceneUploadStats firstUploadStats = null;
                SceneUploadStats finalUploadStats = null;
                long exactFamilyFrames = 0L;
                long cpuGateFrames = 0L;
                long gpuGateFrames = 0L;
                long count = 0L;
                while (true) {
                    var next = queue.take();
                    if (next == STOP) {
                        break;
                    }
                    var frame = (FrameRow) next;
                    var row = new LinkedHashMap<String, Object>();
                    row.put("type", "frame");
                    row.put("sample", frame.sample());
                    row.put("stats", frame.stats());
                    row.put(
                            "water_texture_binding_cache",
                            BgfxSceneRenderer.shouldRetainWaterTextureBindings(
                                    Math.max(0L, frame.stats().frameNumber() - 1)));
                    row.put(
                            "pooled_upload_copies_requested",
                            BgfxDevice.copyPoolModeRequested(
                                    Math.max(0L, frame.stats().frameNumber() - 1)));
                    row.put(
                            "residual_traversal_hierarchy",
                            BgfxSceneRenderer.isResidualTraversalModeRequested(
                                    Math.max(0L, frame.stats().frameNumber() - 1)));
                    row.put(
                            "direct_uniform_uploads",
                            BgfxSceneUniforms.isDirectUniformModeRequested(
                                    Math.max(0L, frame.stats().frameNumber() - 1)));
                    row.put(
                            "compact_authored_uniforms",
                            BgfxSceneUniforms.isCompactAuthoredModeRequested(
                                    Math.max(0L, frame.stats().frameNumber() - 1)));
                    row.put(
                            "submission_span_cost",
                            BgfxGpuVisibility.submissionCostRequested(
                                    Math.max(0L, frame.stats().frameNumber() - 1)));
                    row.put("gpu_submissions", frame.submissions());
                    row.put("heap_used_bytes", frame.heapUsedBytes());
                    row.put("loop_frame_ms", Double.isFinite(frame.loopFrameMs()) ? frame.loopFrameMs() : null);
                    row.put("timing_support", timingSupport(frame.stats()));
                    writeJsonLine(writer, row);
                    addFinite(cpu, frame.stats().cpuFrameMs());
                    addFinite(gpu, frame.stats().gpuFrameMs());
                    addTotalFrame(
                            totalFrame,
                            frame.stats().cpuFrameMs(),
                            frame.stats().gpuFrameMs());
                    addFinite(loopFrame, frame.loopFrameMs());
                    var reconciliation = frame.stats().diagnostics().reconciliation();
                    addFinite(cpuCoverage, reconciliation.cpuCoverage());
                    addFinite(gpuCoverage, reconciliation.gpuCoverage());
                    addFinite(directSceneCpu, reconciliation.directSceneCpuMs());
                    addFramePhases(frame.stats().diagnostics(), cpuPhases, candidateVisibilityCpu);
                    addFrameViews(frame.stats(), gpuViews, gpuViewGroups);
                    addFinite(textureBytes, frame.stats().textureBytes());
                    addFinite(renderTargetBytes, frame.stats().renderTargetBytes());
                    addFinite(bufferBytes, frame.stats().bufferBytes());
                    addFinite(heapBytes, frame.heapUsedBytes());
                    addFinite(readbackDepth, frame.stats().readbackQueueDepth());
                    addFinite(draws, frame.stats().draws());
                    addFinite(viewCount, frame.stats().views().size());
                    addFinite(reflectionReplays, frame.stats().diagnostics().reflectionReplays());
                    addFinite(reflectionUpdates, frame.stats().diagnostics().reflectionUpdates());
                    addFinite(reflectionReuses, frame.stats().diagnostics().reflectionReuses());
                    if (frame.sceneCensus() != null) {
                        if (firstSceneCensus == null) {
                            firstSceneCensus = frame.sceneCensus();
                        }
                        finalSceneCensus = frame.sceneCensus();
                    }
                    if (frame.uploadStats() != null) {
                        if (firstUploadStats == null) {
                            firstUploadStats = frame.uploadStats();
                        }
                        finalUploadStats = frame.uploadStats();
                    }
                    increment(
                            activeUpscalerFrames,
                            frame.stats().activeUpscaler().trim().toLowerCase(Locale.ROOT));
                    for (var status : frame.stats().diagnostics().optimizations()) {
                        if (status.requested()) {
                            increment(optimizationRequestedFrames, status.name());
                        }
                        if (status.active()) {
                            increment(optimizationActiveFrames, status.name());
                        }
                    }
                    for (var reason : frame.stats().fallbackReasons()) {
                        if (reason != null && !reason.isBlank()) {
                            increment(fallbackReasons, reason.trim());
                        }
                    }
                    if (reconciliation.exactFamilyTotals()) {
                        exactFamilyFrames++;
                    }
                    if (reconciliation.cpuCoverage() >= 0.95) {
                        cpuGateFrames++;
                    }
                    if (reconciliation.gpuCoverage() >= 0.90) {
                        gpuGateFrames++;
                    }
                    count++;
                }
                var summary = new LinkedHashMap<String, Object>();
                summary.put("type", "summary");
                summary.put("completed_at", Instant.now().toString());
                summary.put("samples", count);
                summary.put("cpu_ms", computePercentiles(cpu));
                summary.put("gpu_ms", computePercentiles(gpu));
                summary.put("total_frame_ms", computePercentiles(totalFrame));
                summary.put("loop_frame_ms", computePercentiles(loopFrame));
                summary.put("direct_scene_cpu_ms", computePercentiles(directSceneCpu));
                summary.put("candidate_visibility_cpu_ms", computePercentiles(candidateVisibilityCpu));
                summary.put("cpu_phases_ms", percentilesByName(cpuPhases));
                summary.put("gpu_views_ms", percentilesByName(gpuViews));
                summary.put("gpu_view_groups_ms", percentilesByName(gpuViewGroups));
                summary.put("texture_bytes", computePercentiles(textureBytes));
                summary.put("render_target_bytes", computePercentiles(renderTargetBytes));
                summary.put("buffer_bytes", computePercentiles(bufferBytes));
                summary.put("heap_used_bytes", computePercentiles(heapBytes));
                summary.put("readback_queue_depth", computePercentiles(readbackDepth));
                summary.put("draws", computePercentiles(draws));
                summary.put("view_count", computePercentiles(viewCount));
                summary.put("reflection_replays", computePercentiles(reflectionReplays));
                summary.put("reflection_updates", computePercentiles(reflectionUpdates));
                summary.put("reflection_reuses", computePercentiles(reflectionReuses));
                summary.put("scene_census_start", firstSceneCensus);
                summary.put("scene_census_final", finalSceneCensus);
                summary.put("upload_stats_start", firstUploadStats);
                summary.put("upload_stats_final", finalUploadStats);
                summary.put("active_upscaler_frames", activeUpscalerFrames);
                summary.put("optimization_requested_frames", optimizationRequestedFrames);
                summary.put("optimization_active_frames", optimizationActiveFrames);
                summary.put("fallback_reasons", fallbackReasons);
                summary.put("cpu_phase_coverage", computePercentiles(cpuCoverage));
                summary.put("named_view_gpu_coverage", computePercentiles(gpuCoverage));
                summary.put("exact_family_frames", exactFamilyFrames);
                summary.put("cpu_coverage_gate_frames", cpuGateFrames);
                summary.put("gpu_coverage_gate_frames", gpuGateFrames);
                summary.put("reconciliation_pass", count > 0L && exactFamilyFrames == count);
                summary.put("complete", count == measuredFrames);
                writeJsonLine(writer, summary);
            }
        } catch (Throwable e) {
            failure.compareAndSet(null, e);
        }
    }

    private void checkFailure() {
        var problem = failure.get();
        if (problem == null) {
            return;
        }
        if (problem instanceof IOException io) {
            throw new UncheckedIOException("failed to write viewer benchmark artifact", io);
        }
        if (problem instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new IllegalStateException("failed to write viewer benchmark artifact", problem);
    }

    private static Map<String, Object> createHeader(
            String scenario,
            String checkpoint,
            String commit,
            int warmup,
            int frames,
            ViewerGraphicsConfig graphics,
            Object capabilities,
            int backbufferMsaaSamples,
            Map<String, Object> shaderManifest,
            Map<String, Object> scenarioContract) {
        var result = new LinkedHashMap<String, Object>();
        result.put("type", "header");
        result.put("schema", "cruma.viewer.bgfx-benchmark/v3");
        result.put("started_at", Instant.now().toString());
        result.put("scenario", scenario);
        result.put("checkpoint", checkpoint);
        result.put("commit", commit);
        result.put("instrumentation_enabled", ViewerBenchmarkProperties.isInstrumentationEnabled());
        result.put(
                "water_texture_binding_cache_cycle_frames",
                Math.max(0, Integer.getInteger("cruma.viewer.bgfx.water_texture_binding_cache_cycle_frames", 0)));
        result.put(
                "exact_water_instance_batches", Boolean.getBoolean("cruma.viewer.bgfx.exact_water_instance_batches"));
        result.put("native_uniform_value_cache", BgfxSceneUniforms.isNativeUniformValueCacheEnabled());
        result.put("sequential_texture_binding_cache", BgfxSceneRenderer.isSequentialTextureBindingCacheEnabled());
        result.put("pooled_upload_copies_requested", BgfxDevice.copyPoolModeRequested(0));
        result.put("residual_traversal_hierarchy", BgfxSceneRenderer.isResidualTraversalModeRequested(0));
        result.put(
                "residual_traversal_cycle_frames",
                Math.max(0, Integer.getInteger("cruma.viewer.bgfx.residual_traversal_cycle_frames", 0)));
        result.put(
                "residual_traversal_audit_frames",
                Math.max(0, Integer.getInteger("cruma.viewer.bgfx.residual_traversal_audit_frames", 0)));
        result.put("direct_uniform_uploads", BgfxSceneUniforms.isDirectUniformModeRequested(0));
        result.put("adaptive_texture_pages", BgfxTextureCache.isAdaptiveTexturePagesEnabled());
        result.put("maximum_anisotropy", BgfxDevice.isMaximumAnisotropyEnabled());
        result.put("compact_authored_uniforms", BgfxSceneUniforms.isCompactAuthoredModeRequested(0));
        result.put(
                "compact_authored_cycle_frames",
                Math.max(0, Integer.getInteger("cruma.viewer.bgfx.compact_authored_cycle_frames", 0)));
        result.put(
                "submission_span_cost",
                Math.clamp(Integer.getInteger("cruma.viewer.bgfx.submission_span_cost", 4), 1, 1024));
        result.put(
                "submission_span_cost_cycle_frames",
                Math.max(0, Integer.getInteger("cruma.viewer.bgfx.submission_span_cost_cycle_frames", 0)));
        result.put(
                "direct_uniform_cycle_frames",
                Math.max(0, Integer.getInteger("cruma.viewer.bgfx.direct_uniform_cycle_frames", 0)));
        result.put(
                "copy_pool_cycle_frames",
                Math.max(0, Integer.getInteger("cruma.viewer.bgfx.copy_pool_cycle_frames", 0)));
        result.put("streaming_ready_tiles", ViewerBenchmarkProperties.streamingReadyTiles());
        result.put("warmup_frames", warmup);
        result.put("inventory_warmup", warmup > 0);
        result.put("measured_frames", frames);
        result.put(
                "visual_capture_timing",
                ViewerBenchmarkProperties.visualOutput() == null
                        ? "disabled"
                        : Boolean.getBoolean(ViewerBenchmarkProperties.VISUAL_AFTER_MEASUREMENT)
                                ? "after-measurement"
                                : "first-measured-frame");
        result.put("graphics", graphics);
        result.put("backbuffer_msaa_samples", backbufferMsaaSamples);
        result.put("world_fxaa", graphics.msaaSamples() > 0);
        result.put("coverage_debug", System.getProperty(BgfxCoverageDebug.PROPERTY, "off"));
        result.put("capabilities", capabilities);
        result.put("os_name", System.getProperty("os.name"));
        result.put("os_version", System.getProperty("os.version"));
        result.put("os_arch", System.getProperty("os.arch"));
        result.put("java_version", System.getProperty("java.version"));
        result.put("available_processors", Runtime.getRuntime().availableProcessors());
        result.put("max_heap_bytes", Runtime.getRuntime().maxMemory());
        result.put("shader_manifest", shaderManifest);
        result.put("scenario_contract", scenarioContract);
        return result;
    }

    private static Map<String, Object> timingSupport(RenderStats stats) {
        var result = new LinkedHashMap<String, Object>();
        result.put("cpu_frame", Double.isFinite(stats.cpuFrameMs()));
        result.put("gpu_frame", Double.isFinite(stats.gpuFrameMs()));
        var unsupportedViews = new ArrayList<String>();
        for (var view : stats.views()) {
            if (!Double.isFinite(view.gpuMs())) {
                unsupportedViews.add(view.name());
            }
        }
        result.put("named_gpu_views", unsupportedViews.isEmpty());
        result.put("unsupported_gpu_views", unsupportedViews);
        return result;
    }

    private static Map<String, Double> computePercentiles(ArrayList<Double> values) {
        var result = new LinkedHashMap<String, Double>();
        if (values.isEmpty()) {
            result.put("p50", Double.NaN);
            result.put("p95", Double.NaN);
            result.put("p99", Double.NaN);
            result.put("max", Double.NaN);
            return result;
        }
        var sorted = new double[values.size()];
        for (var index = 0; index < sorted.length; index++) {
            sorted[index] = values.get(index);
        }
        Arrays.sort(sorted);
        result.put("p50", percentile(sorted, 0.50));
        result.put("p95", percentile(sorted, 0.95));
        result.put("p99", percentile(sorted, 0.99));
        result.put("max", sorted[sorted.length - 1]);
        return result;
    }

    private static Map<String, Map<String, Double>> percentilesByName(Map<String, ArrayList<Double>> series) {
        var result = new LinkedHashMap<String, Map<String, Double>>();
        for (var entry : series.entrySet()) {
            result.put(entry.getKey(), computePercentiles(entry.getValue()));
        }
        return result;
    }

    private static void addFramePhases(
            RenderDiagnostics diagnostics,
            Map<String, ArrayList<Double>> phases,
            ArrayList<Double> candidateVisibility) {
        var combined = 0.0;
        var combinedMeasured = false;
        for (var timing : diagnostics.cpuPhases()) {
            if (!timing.measured() || !Double.isFinite(timing.milliseconds())) {
                continue;
            }
            var name = timing.phase().name().toLowerCase(Locale.ROOT);
            addFinite(phases.computeIfAbsent(name, ignored -> new ArrayList<>()), timing.milliseconds());
            if (timing.phase() == CpuPhase.CANDIDATE_GENERATION || timing.phase() == CpuPhase.VISIBILITY) {
                combined += timing.milliseconds();
                combinedMeasured = true;
            }
        }
        if (combinedMeasured) {
            addFinite(candidateVisibility, combined);
        }
    }

    private static void addFrameViews(
            RenderStats stats, Map<String, ArrayList<Double>> views, Map<String, ArrayList<Double>> groups) {
        var frameViews = new LinkedHashMap<String, Double>();
        var frameGroups = new LinkedHashMap<String, Double>();
        for (var view : stats.views()) {
            if (!Double.isFinite(view.gpuMs()) || view.gpuMs() < 0.0) {
                continue;
            }
            frameViews.merge(view.name(), view.gpuMs(), Double::sum);
            var name = view.name();
            if (name.contains("reflection")) {
                frameGroups.merge("reflection", view.gpuMs(), Double::sum);
            }
            if (name.startsWith("visibility-")) {
                frameGroups.merge("visibility", view.gpuMs(), Double::sum);
            }
            if (name.startsWith("upscale-")) {
                frameGroups.merge("upscaler", view.gpuMs(), Double::sum);
            }
            if (!name.startsWith("display-") && !name.startsWith("upscale-") && !name.startsWith("readback-")) {
                frameGroups.merge("render_resolution", view.gpuMs(), Double::sum);
            }
        }
        for (var entry : frameViews.entrySet()) {
            addFinite(views.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()), entry.getValue());
        }
        for (var entry : frameGroups.entrySet()) {
            addFinite(groups.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()), entry.getValue());
        }
    }

    private static void addSeries(Map<String, ArrayList<Double>> series, String name, double value) {
        addFinite(series.computeIfAbsent(name, ignored -> new ArrayList<>()), value);
    }

    private static void increment(Map<String, Long> counts, String name) {
        counts.merge(name, 1L, Long::sum);
    }

    private static double percentile(double[] sorted, double quantile) {
        var index = (int) Math.ceil(quantile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static void addFinite(ArrayList<Double> target, double value) {
        if (Double.isFinite(value) && value >= 0.0) {
            target.add(value);
        }
    }

    private static void addTotalFrame(ArrayList<Double> target, double cpu, double gpu) {
        if (Double.isFinite(cpu) && cpu >= 0.0 && Double.isFinite(gpu) && gpu >= 0.0) {
            target.add(Math.max(cpu, gpu));
        } else if (Double.isFinite(cpu) && cpu >= 0.0) {
            target.add(cpu);
        } else if (Double.isFinite(gpu) && gpu >= 0.0) {
            target.add(gpu);
        }
    }

    private static void writeJsonLine(BufferedWriter writer, Object value) throws IOException {
        writer.write(JSON.toJSONString(value));
        writer.newLine();
    }

    private static int positiveProperty(String name, int defaultValue) {
        var value = Integer.getInteger(name, defaultValue);
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int positiveOrZeroProperty(String name, int defaultValue) {
        var value = Integer.getInteger(name, defaultValue);
        if (value < 0) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
        return value;
    }

    private static String benchmarkCommit() {
        var configured = System.getProperty(COMMIT_PROPERTY, "").trim();
        if (!configured.isEmpty()) {
            return configured;
        }
        for (var environmentName : new String[] {"GITHUB_SHA", "CI_COMMIT_SHA", "BUILD_VCS_NUMBER"}) {
            var value = System.getenv(environmentName);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "UNRECORDED";
    }

    private static String requiredRecordedProperty(String name) {
        var value = System.getProperty(name, "").trim();
        if (value.isEmpty() || "UNSPECIFIED".equalsIgnoreCase(value) || "UNRECORDED".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(name + " must be explicitly recorded in benchmark mode");
        }
        return value;
    }

    private static String requiredCommit() {
        var commit = benchmarkCommit();
        if ("UNRECORDED".equalsIgnoreCase(commit)) {
            throw new IllegalArgumentException(
                    COMMIT_PROPERTY + " (or a supported CI commit environment variable) is required");
        }
        return commit;
    }

    private record FrameRow(
            long sample,
            RenderStats stats,
            long heapUsedBytes,
            double loopFrameMs,
            SceneCensus sceneCensus,
            SceneUploadStats uploadStats,
            GpuSubmissionCounts submissions) {}

    record GpuSubmissionCounts(int groups, int activeGroups, int submitBatches, int commandRanges) {}
}
