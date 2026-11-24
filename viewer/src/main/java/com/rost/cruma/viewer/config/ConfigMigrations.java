package com.rost.cruma.viewer.config;

import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import java.util.Map;

public final class ConfigMigrations {
    private static final Map<String, Rename> RENAMES = Map.of(
            "viewer.world.rendering.water_color_linear_filtering",
            new Rename("viewer.world.rendering.water_linear_filtering", "Unreleased"),
            "capture.world.rendering.water_color_linear_filtering",
            new Rename("capture.world.rendering.water_linear_filtering", "Unreleased"));
    private static final Map<String, String> ALIASES = Map.of(
            "capture.output.mosaic.enabled", "capture.output.radar.enabled",
            "capture.output.mosaic.file", "capture.output.radar.file",
            "capture.output.mosaic.tile_resolution", "capture.output.radar.tile_resolution");
    private static final Removal GEODATA_DISTANCE_FADE = new Removal(
            "Unreleased", "Geodata opacity now varies only by floor height. Distance fading is no longer used.", true);
    private static final Removal GEODATA_FAR_FIELD =
            new Removal("Unreleased", "Geodata is shown only inside its radius. The far tint was removed.", true);
    private static final Removal GAME_EVENT_STATES = new Removal(
            "Unreleased",
            "Map events are now chosen for each client, in the Map events tool, Export location and client_profiles.",
            true);
    private static final Map<String, Removal> REMOVALS = Map.of(
            "capture.output.mosaic.max_pixels",
                    new Removal(
                            "Unreleased", "Radar map pixels are stored on disk. This limit is no longer used.", true),
            "viewer.world.geodata.display.fade_start", GEODATA_DISTANCE_FADE,
            "capture.world.geodata.display.fade_start", GEODATA_DISTANCE_FADE,
            "viewer.world.geodata.far_field", GEODATA_FAR_FIELD,
            "capture.world.geodata.far_field", GEODATA_FAR_FIELD,
            "viewer.world.rendering.game_event_states", GAME_EVENT_STATES,
            "capture.world.rendering.game_event_states", GAME_EVENT_STATES);

    private ConfigMigrations() {}

    public record Rename(String newPath, String sinceVersion) {}

    public record Removal(String sinceVersion, String reason, boolean ignored) {
        public Removal(String sinceVersion, String reason) {
            this(sinceVersion, reason, false);
        }
    }

    public static Map<String, Rename> getRenames() {
        return RENAMES;
    }

    public static Map<String, Removal> getRemovals() {
        return REMOVALS;
    }

    public static boolean isAcceptedAlias(String path) {
        return ALIASES.containsKey(path);
    }

    public static com.typesafe.config.Config applyAliases(com.typesafe.config.Config user) {
        var result = user;
        for (var alias : ALIASES.entrySet()) {
            var oldValue = valueAtPath(user, alias.getKey());
            if (oldValue != null) {
                if (valueAtPath(user, alias.getValue()) == null) {
                    result = result.withValue(alias.getValue(), oldValue);
                }
                result = result.withoutPath(alias.getKey());
            }
        }
        for (var removal : REMOVALS.entrySet()) {
            if (removal.getValue().ignored()) {
                result = result.withoutPath(removal.getKey());
            }
        }
        if (valueAtPath(result, "capture.output.mosaic") instanceof ConfigObject legacy && legacy.isEmpty()) {
            result = result.withoutPath("capture.output.mosaic");
        }
        return result;
    }

    public static com.typesafe.config.Config mergeWithDefaults(
            com.typesafe.config.Config user, com.typesafe.config.Config defaults) {
        var merged = applyAliases(user).withFallback(defaults);
        for (var alias : ALIASES.entrySet()) {
            merged = merged.withValue(alias.getKey(), valueAtPath(merged, alias.getValue()));
        }
        return applyAliases(merged.resolve());
    }

    private static ConfigValue valueAtPath(com.typesafe.config.Config config, String path) {
        ConfigValue value = config.root();
        for (var key : path.split("\\.")) {
            value = value instanceof ConfigObject object ? object.get(key) : null;
            if (value == null) {
                break;
            }
        }
        return value;
    }
}
