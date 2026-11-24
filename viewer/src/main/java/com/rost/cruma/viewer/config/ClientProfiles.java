package com.rost.cruma.viewer.config;

import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.MapEventState.Clock;
import com.rost.cruma.core.world.MapEventState.Start;
import com.rost.cruma.core.world.ServerEvents;
import com.rost.cruma.core.world.WorldVariant;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigUtil;
import com.typesafe.config.ConfigValueFactory;
import com.typesafe.config.ConfigValueType;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ClientProfiles {
    public static final String LIST = "client_profiles";
    public static final String GEODATA_PATH = "viewer.world.geodata.path";

    public static final String SCRIPT_PATH = "viewer.world.spawns.script_path";

    public static final String VARIANT = "viewer.world.variant";

    public static final String CAPTURE_VARIANT = "capture.world.variant";

    public static final String TIME_WINDOWS = "viewer.world.rendering.time_windows";

    public static final String CAPTURE_TIME_WINDOWS = "capture.world.rendering.time_windows";
    public static final String CASTLE_SIDES = "viewer.world.rendering.castle_sides";
    public static final String CAPTURE_CASTLE_SIDES = "capture.world.rendering.castle_sides";

    private static final String GEODATA_KEY = "geodata_path";
    private static final String SCRIPT_KEY = "script_path";
    private static final String VARIANT_KEY = "variant";
    private static final String MAP_EVENTS_KEY = "map_events";
    private static final Set<String> FOLDER_KEYS = Set.of(GEODATA_KEY, SCRIPT_KEY);
    private static final List<String> WORLD_KEYS = worldKeys();
    private static final Set<String> KEYS = keys();

    private ClientProfiles() {}

    public static Config migrateLegacy(Config config, Path base) {
        var profiles = parseProfiles(config, base);
        var client = config.getString("viewer.world.client_path");
        var source = config.getString(GEODATA_PATH);
        if (!client.isBlank() && !source.isBlank()) {
            var identity = resolveClient(Path.of(client), base);
            if (profiles.stream().noneMatch(profile -> profile.client().equals(identity))) {
                config = withGeodataPath(config, identity, source, base);
            }
        }
        return config.withValue(GEODATA_PATH, ConfigValueFactory.fromAnyRef(""));
    }

    public static Config apply(Config config, Path client, Path base) {
        var variant = variantFor(config, client, base);
        config = applyVariant(config, variant);
        config = config.withValue(GEODATA_PATH, ConfigValueFactory.fromAnyRef(geodataPath(config, client, base)));
        var script = findProfile(config, client, base)
                .map(profile -> profile.script(variant))
                .orElse("");
        var clock = ConfigValueFactory.fromAnyRef(findProfile(config, client, base)
                .map(profile -> profile.mapEvents().clockForAll())
                .orElse(Clock.FOLLOW)
                .key);
        var sides = ConfigValueFactory.fromAnyRef(MapEventChoices.castleSidesKey(
                findProfile(config, client, base).map(Profile::mapEvents).orElse(MapEventState.ALL_SHOWN)));
        return config.withValue(SCRIPT_PATH, ConfigValueFactory.fromAnyRef(script))
                .withValue(TIME_WINDOWS, clock)
                .withValue(CAPTURE_TIME_WINDOWS, clock)
                .withValue(CASTLE_SIDES, sides)
                .withValue(CAPTURE_CASTLE_SIDES, sides);
    }

    public static Config applyVariant(Config config, Path client, Path base) {
        return applyVariant(config, variantFor(config, client, base));
    }

    public static Config bindVariant(Config config, Path base) {
        var client = config.getString("viewer.world.client_path");
        var value = config.hasPath(VARIANT) ? config.getString(VARIANT) : "";
        if (client.isBlank() || value.isBlank() || WorldVariant.parse(value) == WorldVariant.MAIN) {
            return config;
        }
        var chosen = findProfile(config, Path.of(client), base)
                .map(profile -> !profile.variant().isBlank())
                .orElse(false);
        return chosen
                ? config
                : config.withValue(
                        variantSettingPath(config, Path.of(client), base), ConfigValueFactory.fromAnyRef(value));
    }

    public static WorldVariant variantFor(Config config, Path client, Path base) {
        var variant = findProfile(config, client, base)
                .map(Profile::variant)
                .filter(value -> !value.isBlank())
                .map(WorldVariant::parse)
                .orElse(WorldVariant.MAIN);
        var identity = resolveClient(client, base);
        if (variant == WorldVariant.MAIN || identity == null) {
            return WorldVariant.MAIN;
        }
        try {
            return variant.isOfferedBy(identity) ? variant : WorldVariant.MAIN;
        } catch (UncheckedIOException unreadable) {
            return WorldVariant.MAIN;
        }
    }

    public static String geodataPath(Config config, Path client, Path base) {
        return geodataPath(config, client, base, variantFor(config, client, base));
    }

    public static String geodataPath(Config config, Path client, Path base, WorldVariant variant) {
        return findProfile(config, client, base)
                .map(profile -> profile.geodata(variant))
                .orElse("");
    }

    public static String scriptPath(Config config, Path client, Path base, WorldVariant variant) {
        return findProfile(config, client, base)
                .map(profile -> profile.script(variant))
                .orElse("");
    }

    public static MapEventState mapEvents(Config config, Path client, Path base) {
        var profile = findProfile(config, client, base);
        if (profile.isEmpty()) {
            return MapEventState.ALL_SHOWN;
        }
        var events = profile.get().mapEvents();
        if (events.start() != Start.SERVER_START) {
            return events;
        }
        var script = profile.get().script(variantFor(config, client, base));
        var folder = script.isBlank() ? null : UserPaths.resolve(script, base);
        return events.withStartOn(ServerEvents.of(folder).onAtStart());
    }

    public static String mapEventsSettingPath(Config config, Path client, Path base) {
        return settingPath(config, client, base, WorldVariant.MAIN, MAP_EVENTS_KEY, "map events");
    }

    public static Config withGeodataPath(Config config, Path client, String rawFolder, Path base) {
        var identity = resolveClient(client, base);
        if (identity == null) {
            throw new ConfigurationProblem("Choose a game client before setting its geodata folder.");
        }
        if (rawFolder == null) {
            throw new ConfigurationProblem(
                    "A client geodata folder must be a string; use an empty string to clear it.");
        }
        if (!rawFolder.isBlank()) {
            UserPaths.resolve(rawFolder, base);
        }
        return config.withValue(geodataSettingPath(config, client, base), ConfigValueFactory.fromAnyRef(rawFolder));
    }

    public static String geodataSettingPath(Config config, Path client, Path base) {
        return geodataSettingPath(config, client, base, variantFor(config, client, base));
    }

    public static String geodataSettingPath(Config config, Path client, Path base, WorldVariant variant) {
        return settingPath(config, client, base, variant, GEODATA_KEY, "geodata folder");
    }

    public static String scriptSettingPath(Config config, Path client, Path base) {
        return scriptSettingPath(config, client, base, variantFor(config, client, base));
    }

    public static String scriptSettingPath(Config config, Path client, Path base, WorldVariant variant) {
        return settingPath(config, client, base, variant, SCRIPT_KEY, "script folder");
    }

    public static String variantSettingPath(Config config, Path client, Path base) {
        return settingPath(config, client, base, WorldVariant.MAIN, VARIANT_KEY, "world");
    }

    private static Config applyVariant(Config config, WorldVariant variant) {
        var value = ConfigValueFactory.fromAnyRef(variant.getKey());
        return config.withValue(VARIANT, value).withValue(CAPTURE_VARIANT, value);
    }

    private static Optional<Profile> findProfile(Config config, Path client, Path base) {
        var identity = resolveClient(client, base);
        if (identity != null) {
            for (var profile : parseProfiles(config, base)) {
                if (profile.client().equals(identity)) {
                    return Optional.of(profile);
                }
            }
        }
        return Optional.empty();
    }

    private static String settingPath(
            Config config, Path client, Path base, WorldVariant variant, String key, String what) {
        var selected = resolveClient(client, base);
        if (selected == null) {
            throw new ConfigurationProblem("Choose a game client before setting its " + what + ".");
        }
        var profileKey = selected.toString();
        for (var profile : parseProfiles(config, base)) {
            if (profile.client().equals(selected)) {
                profileKey = profile.key();
                break;
            }
        }
        return variant == WorldVariant.MAIN || !FOLDER_KEYS.contains(key)
                ? ConfigUtil.joinPath(LIST, profileKey, key)
                : ConfigUtil.joinPath(LIST, profileKey, variant.getKey(), key);
    }

    private static Path resolveClient(Path client, Path base) {
        return client == null || client.toString().isBlank() ? null : UserPaths.resolve(client.toString(), base);
    }

    private static List<Profile> parseProfiles(Config config, Path base) {
        if (!config.hasPathOrNull(LIST)) {
            return List.of();
        }
        var result = new ArrayList<Profile>();
        var clients = new HashSet<Path>();
        for (var entry : config.getObject(LIST).entrySet()) {
            var label = ConfigUtil.joinPath(LIST, entry.getKey());
            if (!(entry.getValue() instanceof ConfigObject object)
                    || object.isEmpty()
                    || !KEYS.containsAll(object.keySet())) {
                throw new ConfigurationProblem(label
                        + " must contain geodata_path, script_path, variant, "
                        + String.join(", ", WORLD_KEYS)
                        + " or map_events.");
            }
            var mapEvents = MapEventState.ALL_SHOWN;
            for (var key : object.keySet()) {
                if (key.equals(MAP_EVENTS_KEY)) {
                    if (!(object.get(key) instanceof ConfigObject events)) {
                        throw new ConfigurationProblem(label + ".map_events must be an object.");
                    }
                    mapEvents = MapEventChoices.parse(events.toConfig(), label + ".map_events");
                } else if (WORLD_KEYS.contains(key)) {
                    if (!(object.get(key) instanceof ConfigObject world) || !FOLDER_KEYS.containsAll(world.keySet())) {
                        throw new ConfigurationProblem(
                                label + "." + key + " must contain geodata_path, script_path or both.");
                    }
                    for (var folderKey : world.keySet()) {
                        if (world.get(folderKey).valueType() != ConfigValueType.STRING) {
                            throw new ConfigurationProblem(label + "." + key + "." + folderKey + " must be a string.");
                        }
                    }
                } else if (object.get(key).valueType() != ConfigValueType.STRING) {
                    throw new ConfigurationProblem(label + "." + key + " must be a string.");
                }
            }
            var client = resolveClient(Path.of(entry.getKey()), base);
            if (client == null) {
                throw new ConfigurationProblem(LIST + " client keys must not be blank.");
            }
            if (!clients.add(client)) {
                throw new ConfigurationProblem(label + " duplicates a normalized client folder.");
            }
            var values = object.toConfig();
            var geodata = new EnumMap<WorldVariant, String>(WorldVariant.class);
            var script = new EnumMap<WorldVariant, String>(WorldVariant.class);
            for (var world : WorldVariant.values()) {
                var block = world == WorldVariant.MAIN ? "" : world.getKey() + ".";
                geodata.put(world, stringOrEmpty(values, block + GEODATA_KEY));
                script.put(world, stringOrEmpty(values, block + SCRIPT_KEY));
            }
            var variant = stringOrEmpty(values, VARIANT_KEY);
            if (!variant.isBlank()) {
                try {
                    WorldVariant.parse(variant);
                } catch (IllegalArgumentException invalid) {
                    throw new ConfigurationProblem(label + ".variant must be " + WorldVariant.describeKeys() + ".");
                }
            }
            for (var folders : List.of(geodata, script)) {
                for (var folder : folders.values()) {
                    if (!folder.isBlank()) {
                        UserPaths.resolve(folder, base);
                    }
                }
            }
            result.add(
                    new Profile(client, Map.copyOf(geodata), Map.copyOf(script), variant, mapEvents, entry.getKey()));
        }
        return result;
    }

    private static String stringOrEmpty(Config values, String path) {
        return values.hasPath(path) ? values.getString(path) : "";
    }

    private static List<String> worldKeys() {
        var keys = new ArrayList<String>();
        for (var world : WorldVariant.values()) {
            if (world != WorldVariant.MAIN) {
                keys.add(world.getKey());
            }
        }
        return List.copyOf(keys);
    }

    private static Set<String> keys() {
        var keys = new HashSet<>(List.of(GEODATA_KEY, SCRIPT_KEY, VARIANT_KEY, MAP_EVENTS_KEY));
        keys.addAll(WORLD_KEYS);
        return Set.copyOf(keys);
    }

    private record Profile(
            Path client,
            Map<WorldVariant, String> geodata,
            Map<WorldVariant, String> script,
            String variant,
            MapEventState mapEvents,
            String key) {
        String geodata(WorldVariant world) {
            return geodata.getOrDefault(world, "");
        }

        String script(WorldVariant world) {
            return script.getOrDefault(world, "");
        }
    }
}
