package com.rost.cruma.viewer.config;

import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.WorldVariant;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public record RecordOptions(
        Path output,
        int width,
        int height,
        int rate,
        double from,
        double to,
        double hour,
        @Nullable String events,
        @Nullable Path scripts,
        @Nullable WorldVariant variant,
        @Nullable List<String> tiles,
        boolean guarded) {
    public static final String NO_TILES = "none";

    private static final Pattern SIZE = Pattern.compile("(\\d{2,5})x(\\d{2,5})");
    private static final int MAX_RATE = 240;

    public RecordOptions withSize(int newWidth, int newHeight, int newRate) {
        return new RecordOptions(
                output,
                width > 0 ? width : newWidth,
                height > 0 ? height : newHeight,
                rate > 0 ? rate : newRate,
                from,
                to,
                hour,
                events,
                scripts,
                variant,
                tiles,
                guarded);
    }

    public double hourOf(@Nullable Scene file) {
        return Double.isNaN(hour) && file != null ? file.hour() : hour;
    }

    public List<String> arguments() {
        var arguments = new ArrayList<>(List.of("--record", output.toString()));
        if (width > 0 && height > 0) {
            arguments.addAll(List.of("--record-size", width + "x" + height));
        }
        if (rate > 0) {
            arguments.addAll(List.of("--record-rate", Integer.toString(rate)));
        }
        if (from > 0) {
            arguments.addAll(List.of("--record-from", Double.toString(from)));
        }
        if (to < Double.POSITIVE_INFINITY) {
            arguments.addAll(List.of("--record-to", Double.toString(to)));
        }
        if (!Double.isNaN(hour)) {
            arguments.addAll(List.of("--record-hour", Double.toString(hour)));
        }
        if (events != null) {
            arguments.addAll(List.of("--record-events", events));
        }
        if (scripts != null) {
            arguments.addAll(List.of("--record-scripts", scripts.toString()));
        }
        if (variant != null) {
            arguments.addAll(List.of("--record-variant", variant.getKey()));
        }
        if (tiles != null) {
            arguments.addAll(List.of("--record-tiles", tiles.isEmpty() ? NO_TILES : String.join(",", tiles)));
        }
        if (guarded) {
            arguments.add("--record-guarded");
        }
        return arguments;
    }

    static final class Parser {
        private @Nullable Path output;
        private int width;
        private int height;
        private int rate;
        private double from;
        private double to = Double.POSITIVE_INFINITY;
        private double hour = Double.NaN;
        private @Nullable String events;
        private @Nullable Path scripts;
        private @Nullable WorldVariant variant;
        private @Nullable List<String> tiles;
        private boolean guarded;

        boolean takes(String argument) {
            return argument.equals("--record") || argument.startsWith("--record-");
        }

        int read(String[] arguments, int index) {
            var option = arguments[index];
            if (option.equals("--record-guarded")) {
                guarded = true;
                return 0;
            }
            if (index + 1 >= arguments.length || arguments[index + 1].isBlank()) {
                throw new ConfigurationProblem(option + " needs a value");
            }
            var value = arguments[index + 1];
            switch (option) {
                case "--record" -> output = Paths.get(value);
                case "--record-size" -> size(value);
                case "--record-rate" -> rate(value);
                case "--record-from" -> from = seconds(option, value);
                case "--record-to" -> to = seconds(option, value);
                case "--record-hour" -> hour = seconds(option, value);
                case "--record-events" -> events = value;
                case "--record-scripts" -> scripts = Paths.get(value);
                case "--record-variant" -> variant = variant(value);
                case "--record-tiles" -> tiles = value.equals(NO_TILES) ? List.of() : List.of(value.split(","));
                default -> throw new ConfigurationProblem("Unknown option '" + option + "'.\n" + ConfigLoader.usage());
            }
            return 1;
        }

        @Nullable
        RecordOptions build(boolean scene) {
            if (output == null) {
                if (width > 0 || rate > 0 || tiles != null || guarded) {
                    throw new ConfigurationProblem(
                            "--record names the file of a video, and the other --record options need it");
                }
                return null;
            }
            if (!scene) {
                throw new ConfigurationProblem(
                        "--record needs --scene, the name of the cut scene to record, or --scene-file");
            }
            return new RecordOptions(
                    output, width, height, rate, from, to, hour, events, scripts, variant, tiles, guarded);
        }

        private void size(String value) {
            var size = SIZE.matcher(value);
            if (!size.matches()) {
                throw new ConfigurationProblem("--record-size needs a width and a height, such as 1920x1080");
            }
            width = Integer.parseInt(size.group(1));
            height = Integer.parseInt(size.group(2));
            if (width % 2 != 0 || height % 2 != 0) {
                throw new ConfigurationProblem("--record-size needs even numbers, as a video has no odd sizes");
            }
        }

        private void rate(String value) {
            try {
                rate = Integer.parseInt(value);
            } catch (NumberFormatException wrong) {
                rate = 0;
            }
            if (rate < 1 || rate > MAX_RATE) {
                throw new ConfigurationProblem("--record-rate needs a whole number from 1 to " + MAX_RATE);
            }
        }

        private static WorldVariant variant(String value) {
            try {
                return WorldVariant.parse(value);
            } catch (IllegalArgumentException wrong) {
                throw new ConfigurationProblem("--record-variant needs " + WorldVariant.describeKeys());
            }
        }

        private static double seconds(String option, String value) {
            try {
                var number = Double.parseDouble(value);
                if (Double.isFinite(number) && number >= 0) {
                    return number;
                }
            } catch (NumberFormatException wrong) {
            }
            throw new ConfigurationProblem(option + " needs a number from 0");
        }
    }
}
