package com.rost.cruma.viewer.render.api;

import java.util.Locale;

public record ViewerGraphicsConfig(
        Renderer renderer,
        boolean validation,
        boolean profiler,
        boolean vsync,
        int frameRateCap,
        int msaaSamples,
        Upscaler upscaler,
        Optimizations optimizations,
        Tuning tuning) {
    public static final int MIN_FRAME_RATE_CAP = 1;

    public static final int MAX_FRAME_RATE_CAP = 1000;

    public ViewerGraphicsConfig {
        if (renderer == null || upscaler == null || optimizations == null || tuning == null) {
            throw new IllegalArgumentException("viewer graphics configuration cannot contain null values");
        }
        if (frameRateCap < MIN_FRAME_RATE_CAP || frameRateCap > MAX_FRAME_RATE_CAP) {
            throw new IllegalArgumentException("frame_rate_cap must be between 1 and 1000, got " + frameRateCap);
        }
        if (msaaSamples != 0 && msaaSamples != 2 && msaaSamples != 4) {
            throw new IllegalArgumentException("msaa_samples must be 0, 2 or 4, got " + msaaSamples);
        }
    }

    public static ViewerGraphicsConfig defaults() {
        return bgfxDefaults();
    }

    public static ViewerGraphicsConfig bgfxDefaults() {
        return new ViewerGraphicsConfig(
                Renderer.AUTO,
                false,
                false,
                false,
                144,
                2,
                Upscaler.nativeOnly(),
                Optimizations.optimized(),
                Tuning.defaults());
    }

    public ViewerGraphicsConfig forBenchmarkCheckpoint(String checkpoint) {
        if (checkpoint == null || checkpoint.isBlank()) {
            throw new IllegalArgumentException("benchmark checkpoint cannot be blank");
        }
        var nativeUpscaler = Upscaler.nativeOnly();
        var parity = Optimizations.parity();
        return switch (checkpoint.trim().toUpperCase(Locale.ROOT)) {
            case "BGFX_PARITY_NATIVE", "BGFX_PARITY_INSTRUMENTATION_OFF" ->
                new ViewerGraphicsConfig(
                        renderer,
                        validation,
                        profiler,
                        vsync,
                        frameRateCap,
                        msaaSamples,
                        nativeUpscaler,
                        parity,
                        tuning);
            case "P1_NATIVE" ->
                new ViewerGraphicsConfig(
                        renderer,
                        validation,
                        profiler,
                        vsync,
                        frameRateCap,
                        msaaSamples,
                        nativeUpscaler,
                        stagedOptimizations(true, false, false),
                        tuning);
            case "P2_NATIVE" ->
                new ViewerGraphicsConfig(
                        renderer,
                        validation,
                        profiler,
                        vsync,
                        frameRateCap,
                        msaaSamples,
                        nativeUpscaler,
                        stagedOptimizations(true, true, false),
                        tuning);
            case "P3_NATIVE" ->
                new ViewerGraphicsConfig(
                        renderer,
                        validation,
                        profiler,
                        vsync,
                        frameRateCap,
                        msaaSamples,
                        nativeUpscaler,
                        stagedOptimizations(true, true, true),
                        tuning);
            case "BGFX_OPTIMIZED_NATIVE" ->
                new ViewerGraphicsConfig(
                        renderer,
                        validation,
                        profiler,
                        vsync,
                        frameRateCap,
                        msaaSamples,
                        nativeUpscaler,
                        Optimizations.optimized(),
                        tuning);
            case "BGFX_OPTIMIZED_FSR1_ULTRA_QUALITY" ->
                new ViewerGraphicsConfig(
                        renderer,
                        validation,
                        profiler,
                        vsync,
                        frameRateCap,
                        msaaSamples,
                        fsr1(UpscalerPreset.ULTRA_QUALITY),
                        Optimizations.optimized(),
                        tuning);
            case "BGFX_OPTIMIZED_FSR1_QUALITY" ->
                new ViewerGraphicsConfig(
                        renderer,
                        validation,
                        profiler,
                        vsync,
                        frameRateCap,
                        msaaSamples,
                        fsr1(UpscalerPreset.QUALITY),
                        Optimizations.optimized(),
                        tuning);
            case "BGFX_OPTIMIZED_FSR1_QUALITY_NO_RCAS" ->
                new ViewerGraphicsConfig(
                        renderer,
                        validation,
                        profiler,
                        vsync,
                        frameRateCap,
                        msaaSamples,
                        new Upscaler(
                                UpscalerAlgorithm.FSR1,
                                UpscalerPreset.QUALITY,
                                UpscalerPreset.QUALITY.getScale(1.0),
                                false,
                                0.2,
                                DynamicResolution.disabled()),
                        Optimizations.optimized(),
                        tuning);
            case "BGFX_OPTIMIZED_FSR1_BALANCED" ->
                new ViewerGraphicsConfig(
                        renderer,
                        validation,
                        profiler,
                        vsync,
                        frameRateCap,
                        msaaSamples,
                        fsr1(UpscalerPreset.BALANCED),
                        Optimizations.optimized(),
                        tuning);
            case "BGFX_OPTIMIZED_FSR1_PERFORMANCE" ->
                new ViewerGraphicsConfig(
                        renderer,
                        validation,
                        profiler,
                        vsync,
                        frameRateCap,
                        msaaSamples,
                        fsr1(UpscalerPreset.PERFORMANCE),
                        Optimizations.optimized(),
                        tuning);
            case "BGFX_OPTIMIZED_FSR1_QUALITY_SYNC" ->
                new ViewerGraphicsConfig(
                        renderer,
                        validation,
                        profiler,
                        vsync,
                        frameRateCap,
                        msaaSamples,
                        fsr1(UpscalerPreset.QUALITY),
                        stagedOptimizations(true, true, true),
                        tuning);
            default -> throw new IllegalArgumentException("unknown viewer benchmark checkpoint: " + checkpoint);
        };
    }

    public ViewerGraphicsConfig withFrameRateCap(int cap) {
        return new ViewerGraphicsConfig(
                renderer, validation, profiler, vsync, cap, msaaSamples, upscaler, optimizations, tuning);
    }

    public ViewerGraphicsConfig forCapture() {
        return new ViewerGraphicsConfig(
                renderer,
                validation,
                false,
                false,
                frameRateCap,
                0,
                Upscaler.nativeOnly(),
                optimizations.withoutDynamicVisibility(),
                tuning);
    }

    public record Tuning(
            int terrainProxyGridStep,
            int actorLightingWords,
            int materialPageTargetLayers,
            int ordinaryDiffusePageGroupSize,
            int commandHierarchyLeafCommands,
            double fxMinProjectedPixels,
            double fxSimulationLodProjectedPixels,
            int fxActorTransformCapacity,
            int visibilityMaxCandidates,
            int visibilityMaxCommands,
            double visibilityMinProjectedPixels,
            double visibilityTerrainDetailPixels,
            double gpuGroupSortCellSize) {
        public Tuning {
            if (terrainProxyGridStep < 2 || terrainProxyGridStep > 16 || Integer.bitCount(terrainProxyGridStep) != 1) {
                throw new IllegalArgumentException(
                        "terrain_proxy_grid_step must be a power of two in [2, 16], got " + terrainProxyGridStep);
            }
            if (actorLightingWords < 1024 || actorLightingWords > Integer.MAX_VALUE / Integer.BYTES) {
                throw new IllegalArgumentException("actor_lighting_words must be in 1024..536870911");
            }
            if (materialPageTargetLayers < 1) {
                throw new IllegalArgumentException("material_page_target_layers must be positive");
            }
            if (ordinaryDiffusePageGroupSize != 1
                    && ordinaryDiffusePageGroupSize != 2
                    && ordinaryDiffusePageGroupSize != 4) {
                throw new IllegalArgumentException("ordinary_diffuse_page_group_size must be 1, 2 or 4");
            }
            if (commandHierarchyLeafCommands < 2
                    || commandHierarchyLeafCommands > 32
                    || Integer.bitCount(commandHierarchyLeafCommands) != 1) {
                throw new IllegalArgumentException("command_hierarchy_leaf_commands must be a power of two in [2, 32]");
            }
            if (!(fxMinProjectedPixels >= 0.0) || !(fxSimulationLodProjectedPixels >= 0.0)) {
                throw new IllegalArgumentException("fx projected-pixel thresholds cannot be negative");
            }
            if (fxActorTransformCapacity < 1024) {
                throw new IllegalArgumentException("fx_actor_transform_capacity must be at least 1024");
            }
            if (visibilityMaxCandidates < 1 || visibilityMaxCommands < 1) {
                throw new IllegalArgumentException("visibility buffer ceilings must be positive");
            }
            if (!(visibilityMinProjectedPixels >= 0.0) || !(visibilityTerrainDetailPixels >= 0.0)) {
                throw new IllegalArgumentException("visibility projected-pixel thresholds cannot be negative");
            }
            if (!(gpuGroupSortCellSize >= 1_024.0 && gpuGroupSortCellSize <= 65_536.0)) {
                throw new IllegalArgumentException("gpu_group_sort_cell_size must be within [1024, 65536]");
            }
        }

        public static Tuning defaults() {
            return new Tuning(
                    8,
                    96 * 1024 * 1024,
                    128,
                    4,
                    16,
                    1.0,
                    4.0,
                    65_536,
                    16_777_215,
                    16_777_215,
                    3.0,
                    200.0,
                    8_192.0);
        }
    }

    public enum Renderer {
        AUTO,
        METAL,
        DIRECT3D11,
        DIRECT3D12,
        VULKAN,
        NOOP;

        public static Renderer parse(String value) {
            return parseEnum(Renderer.class, value, "renderer");
        }
    }

    public enum UpscalerAlgorithm {
        NATIVE,
        BILINEAR,
        FSR1;

        public static UpscalerAlgorithm parse(String value) {
            return parseEnum(UpscalerAlgorithm.class, value, "upscaler.algorithm");
        }
    }

    public enum UpscalerPreset {
        NATIVE(1.0),
        ULTRA_QUALITY(1.0 / 1.3),
        QUALITY(1.0 / 1.5),
        BALANCED(1.0 / 1.7),
        PERFORMANCE(0.5),
        CUSTOM(Double.NaN);

        private final double scale;

        UpscalerPreset(double scale) {
            this.scale = scale;
        }

        public static UpscalerPreset parse(String value) {
            return parseEnum(UpscalerPreset.class, value, "upscaler.preset");
        }

        public double getScale(double customScale) {
            return this == CUSTOM ? customScale : scale;
        }
    }

    public enum OptimizationProfile {
        PARITY,
        OPTIMIZED,
        CUSTOM;

        public static OptimizationProfile parse(String value) {
            return parseEnum(OptimizationProfile.class, value, "optimizations.profile");
        }
    }

    public record DynamicResolution(
            boolean enabled, double targetFrameMs, double minScale, double maxScale, int cooldownFrames) {
        public DynamicResolution {
            if (!Double.isFinite(targetFrameMs) || targetFrameMs <= 0.0) {
                throw new IllegalArgumentException("dynamic target_frame_ms must be finite and positive");
            }
            checkScale(minScale, "dynamic min_scale");
            checkScale(maxScale, "dynamic max_scale");
            if (minScale > maxScale) {
                throw new IllegalArgumentException("dynamic min_scale cannot exceed max_scale");
            }
            if (cooldownFrames < 1) {
                throw new IllegalArgumentException("dynamic cooldown_frames must be positive");
            }
        }

        public static DynamicResolution disabled() {
            return new DynamicResolution(false, 16.67, 0.5, 1.0, 30);
        }
    }

    public record Upscaler(
            UpscalerAlgorithm algorithm,
            UpscalerPreset preset,
            double customScale,
            boolean rcasEnabled,
            double sharpness,
            DynamicResolution dynamicResolution) {
        public Upscaler {
            if (algorithm == null || preset == null || dynamicResolution == null) {
                throw new IllegalArgumentException("upscaler configuration cannot contain null values");
            }
            checkScale(customScale, "upscaler custom_scale");
            if (!Double.isFinite(sharpness) || sharpness < 0.0 || sharpness > 1.0) {
                throw new IllegalArgumentException("upscaler sharpness must be within 0..1");
            }
            if (algorithm == UpscalerAlgorithm.NATIVE && preset != UpscalerPreset.NATIVE) {
                throw new IllegalArgumentException("native upscaler requires the native preset");
            }
            if (algorithm != UpscalerAlgorithm.NATIVE && preset == UpscalerPreset.NATIVE) {
                throw new IllegalArgumentException("non-native upscaler cannot use the native preset");
            }
        }

        public static Upscaler nativeOnly() {
            return new Upscaler(
                    UpscalerAlgorithm.NATIVE, UpscalerPreset.NATIVE, 1.0, false, 0.0, DynamicResolution.disabled());
        }

        public static Upscaler fsr1Quality() {
            return new Upscaler(
                    UpscalerAlgorithm.FSR1, UpscalerPreset.QUALITY, 1.0 / 1.5, true, 0.2, DynamicResolution.disabled());
        }

        public double requestedScale() {
            return algorithm == UpscalerAlgorithm.NATIVE ? 1.0 : preset.getScale(customScale);
        }
    }

    public record Optimizations(
            OptimizationProfile profile,
            boolean fillReflection,
            boolean gpuVisibility,
            boolean indirectSubmission,
            boolean parallelEncoders) {
        public Optimizations {
            if (profile == null) {
                throw new IllegalArgumentException("optimization profile cannot be null");
            }
            if (profile == OptimizationProfile.PARITY
                    && (fillReflection || gpuVisibility || indirectSubmission || parallelEncoders)) {
                throw new IllegalArgumentException("parity profile must disable P1-P4 optimizations");
            }
            if (indirectSubmission && !gpuVisibility) {
                throw new IllegalArgumentException("indirect submission requires GPU visibility");
            }
        }

        public static Optimizations parity() {
            return new Optimizations(OptimizationProfile.PARITY, false, false, false, false);
        }

        public static Optimizations optimized() {
            return new Optimizations(OptimizationProfile.OPTIMIZED, true, true, true, false);
        }

        private Optimizations withoutDynamicVisibility() {
            return new Optimizations(
                    profile == OptimizationProfile.PARITY ? profile : OptimizationProfile.CUSTOM,
                    fillReflection,
                    false,
                    false,
                    parallelEncoders);
        }
    }

    private static Upscaler fsr1(UpscalerPreset preset) {
        return new Upscaler(
                UpscalerAlgorithm.FSR1, preset, preset.getScale(1.0), true, 0.2, DynamicResolution.disabled());
    }

    private static Optimizations stagedOptimizations(
            boolean fillReflection, boolean gpuVisibility, boolean indirectSubmission) {
        return new Optimizations(OptimizationProfile.CUSTOM, fillReflection, gpuVisibility, indirectSubmission, false);
    }

    private static void checkScale(double value, String name) {
        if (!Double.isFinite(value) || value < 0.5 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be finite and within 0.5..1.0, got " + value);
        }
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String raw, String key) {
        if (raw == null) {
            throw new IllegalArgumentException(key + " cannot be null");
        }
        var normalized = raw.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(type, normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown " + key + " value: " + raw, e);
        }
    }
}
