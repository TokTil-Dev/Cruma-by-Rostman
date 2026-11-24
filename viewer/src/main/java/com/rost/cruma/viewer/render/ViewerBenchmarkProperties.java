package com.rost.cruma.viewer.render;

import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import java.nio.file.Path;
import java.util.Locale;

public final class ViewerBenchmarkProperties {
    public static final String OUTPUT = "cruma.viewer.benchmark.output";
    public static final String SCENARIO = "cruma.viewer.benchmark.scenario";
    public static final String WARMUP_FRAMES = "cruma.viewer.benchmark.warmup_frames";
    public static final String FRAMES = "cruma.viewer.benchmark.frames";
    public static final String CHECKPOINT = "cruma.viewer.benchmark.checkpoint";
    public static final String COMMIT = "cruma.viewer.benchmark.commit";
    public static final String VISUAL_OUTPUT = "cruma.viewer.benchmark.visual_output";
    public static final String VISUAL_AFTER_MEASUREMENT = "cruma.viewer.benchmark.visual_after_measurement";
    public static final String DISPLAY_PIXELS = "cruma.viewer.benchmark.display_pixels";
    public static final String STREAMING_READY_TILES = "cruma.viewer.benchmark.streaming_ready_tiles";

    public static final String INSTRUMENTATION_OFF_CHECKPOINT = "BGFX_PARITY_INSTRUMENTATION_OFF";

    private ViewerBenchmarkProperties() {}

    public static boolean isEnabled() {
        return !System.getProperty(OUTPUT, "").isBlank();
    }

    public static boolean isInstrumentationEnabled() {
        return !isEnabled()
                || !INSTRUMENTATION_OFF_CHECKPOINT.equalsIgnoreCase(
                        System.getProperty(CHECKPOINT, "").trim());
    }

    public static Path visualOutput() {
        var value = System.getProperty(VISUAL_OUTPUT, "").trim();
        if (value.isEmpty()) {
            return null;
        }
        var output = Path.of(value).toAbsolutePath().normalize();
        if (!output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
            throw new IllegalArgumentException(VISUAL_OUTPUT + " must end in .png");
        }
        return output;
    }

    public static Extent displayPixels() {
        var value = System.getProperty(DISPLAY_PIXELS, "").trim();
        if (!isEnabled() || value.isEmpty()) {
            return null;
        }
        if (visualOutput() == null) {
            throw new IllegalArgumentException(DISPLAY_PIXELS + " requires " + VISUAL_OUTPUT);
        }
        var dimensions = value.split("x", -1);
        try {
            if (dimensions.length == 2) {
                var width = Integer.parseInt(dimensions[0]);
                var height = Integer.parseInt(dimensions[1]);
                if (width > 0 && height > 0) {
                    return new Extent(width, height);
                }
            }
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException(DISPLAY_PIXELS + " must be positive WIDTHxHEIGHT pixels");
    }

    public static int streamingReadyTiles() {
        var value = System.getProperty(STREAMING_READY_TILES, "").trim();
        if (value.isEmpty()) {
            return 0;
        }
        final int tiles;
        try {
            tiles = Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(STREAMING_READY_TILES + " must be a non-negative integer", failure);
        }
        if (tiles < 0) {
            throw new IllegalArgumentException(STREAMING_READY_TILES + " must be a non-negative integer");
        }
        return tiles;
    }
}
