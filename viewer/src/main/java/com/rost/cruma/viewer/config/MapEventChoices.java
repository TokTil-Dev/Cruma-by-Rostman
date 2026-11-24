package com.rost.cruma.viewer.config;

import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.MapEventState.Clock;
import com.rost.cruma.core.world.MapEventState.Start;
import com.rost.cruma.core.world.ServerEvents;
import com.rost.cruma.core.world.ZoneStateChoice;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigRenderOptions;
import com.typesafe.config.ConfigValueFactory;
import com.typesafe.config.ConfigValueType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class MapEventChoices {
    static final String START = "start";
    static final String EVENTS = "events";
    static final String CASTLES = "castles";
    static final String BY_TIME = "by_time";
    static final String ZONE_STATES = "zone_states";
    static final String OLD_ZONE_STATE = "zone_state";
    static final String MIXED_SIDES = "mixed";
    private static final Set<String> KEYS = Set.of(START, EVENTS, CASTLES, BY_TIME, ZONE_STATES, OLD_ZONE_STATE);

    private final @Nullable ConfigLoader loader;

    private final Map<Path, MapEventState> remembered = new HashMap<>();

    private final List<BiConsumer<Path, MapEventState>> listeners = new ArrayList<>();

    private MapEventChoices(@Nullable ConfigLoader loader) {
        this.loader = loader;
    }

    public static MapEventChoices in(ConfigLoader loader) {
        return new MapEventChoices(loader);
    }

    public static MapEventChoices session() {
        return new MapEventChoices(null);
    }

    static MapEventState parse(com.typesafe.config.Config conf, String label) {
        for (var key : conf.root().keySet()) {
            if (!KEYS.contains(key)) {
                throw new ConfigurationProblem(label + " has an unknown key " + key
                        + ". It can contain start, events, castles, by_time and zone_states.");
            }
        }
        var start = Start.parse(conf.hasPath(START) ? string(conf, START, label) : Start.ALL_SHOWN.key);
        if (start == null) {
            throw new ConfigurationProblem(label + ".start must be all_shown, game_start or server_start.");
        }
        var events = numbers(conf, EVENTS, label, 0, 1);
        var castles = numbers(conf, CASTLES, label, MapEventState.NO_SIDE, MapEventState.BOTH_SIDES);
        return new MapEventState(start, Set.of(), events, castles, clocks(conf, label), zoneStates(conf, label));
    }

    public static String text(MapEventState state) {
        var block = toConfig(state).render(ConfigRenderOptions.concise());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(block.getBytes(StandardCharsets.UTF_8));
    }

    public static MapEventState read(String text, @Nullable Path scripts) {
        String block;
        try {
            block = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException wrong) {
            throw new ConfigurationProblem("--record-events is not a state of the map events.");
        }
        var state = parse(ConfigFactory.parseString(block), "--record-events");
        return state.start() == Start.SERVER_START
                ? state.withStartOn(ServerEvents.of(scripts).onAtStart())
                : state;
    }

    static String castleSidesKey(MapEventState state) {
        var side = state.castleSideForAll();
        if (side == null) {
            return MIXED_SIDES;
        }
        return switch (side) {
            case MapEventState.LIGHT -> "light";
            case MapEventState.DARK -> "dark";
            case MapEventState.BOTH_SIDES -> "both";
            default -> "none";
        };
    }

    static @Nullable Integer castleSide(String key) {
        return switch (key) {
            case "light" -> MapEventState.LIGHT;
            case "dark" -> MapEventState.DARK;
            case "both" -> MapEventState.BOTH_SIDES;
            case "none" -> MapEventState.NO_SIDE;
            case MIXED_SIDES -> null;
            default -> throw new ConfigurationProblem("Castle sides must be both, light, dark or none.");
        };
    }

    static ConfigObject toConfig(MapEventState state) {
        var events = new LinkedHashMap<String, Integer>();
        for (var id : state.exceptions()) {
            events.put(Integer.toString(id), state.events().get(id));
        }
        var castles = new LinkedHashMap<String, Integer>();
        state.castleSides().keySet().stream()
                .sorted()
                .forEach(castle -> castles.put(
                        Integer.toString(castle), state.castleSides().get(castle)));
        var clocks = new LinkedHashMap<String, String>();
        state.clocks().keySet().stream()
                .sorted()
                .forEach(windows -> clocks.put(windows, state.clocks().get(windows).key));
        var block = new LinkedHashMap<String, Object>();
        block.put(START, state.start().key);
        block.put(EVENTS, events);
        block.put(CASTLES, castles);
        block.put(BY_TIME, clocks);
        block.put(ZONE_STATES, new LinkedHashMap<>(state.zoneStates().getStates()));
        return ConfigValueFactory.fromMap(block);
    }

    public MapEventState of(Path client) {
        var key = client.toAbsolutePath().normalize();
        if (loader != null) {
            try {
                loader.reload();
                return ClientProfiles.mapEvents(loader.getEffectiveSettings(), client, loader.getConfigDirectory());
            } catch (RuntimeException unreadable) {
                LogManager.getLogger(MapEventChoices.class)
                        .warn("Could not read the map events of {}: {}", client, unreadable.toString());
            }
        }
        return remembered.getOrDefault(key, MapEventState.ALL_SHOWN);
    }

    public void onChange(BiConsumer<Path, MapEventState> listener) {
        listeners.add(listener);
    }

    public void save(Path client, MapEventState state) {
        var key = client.toAbsolutePath().normalize();
        remembered.put(key, state);
        for (var listener : listeners) {
            listener.accept(key, state);
        }
        if (loader == null) {
            return;
        }
        try {
            loader.reload();
            var path = ClientProfiles.mapEventsSettingPath(
                    loader.getEffectiveSettings(), client, loader.getConfigDirectory());
            var model = new OptionsModel(loader, client);
            model.setValue(path, toConfig(state));
            if (model.hasChanges()) {
                model.save();
            }
        } catch (IOException | RuntimeException failure) {
            LogManager.getLogger(MapEventChoices.class)
                    .warn("Could not save the map events of {}: {}", client, failure.toString());
        }
    }

    private static ZoneStateChoice zoneStates(com.typesafe.config.Config conf, String label) {
        if (!conf.hasPath(ZONE_STATES)) {
            return ZoneStateChoice.EMPTY;
        }
        if (!(conf.getValue(ZONE_STATES) instanceof ConfigObject object)) {
            throw new ConfigurationProblem(label + "." + ZONE_STATES + " must be an object of \"map\" = state.");
        }
        var chosen = new HashMap<String, Integer>();
        for (var entry : object.entrySet()) {
            var value = entry.getValue();
            var number = value.valueType() == ConfigValueType.NUMBER ? (Number) value.unwrapped() : null;
            var state = number == null ? -1 : number.intValue();
            if (number == null
                    || number.doubleValue() != state
                    || state < MapEventState.AS_THE_MAP
                    || state > MapEventState.MAX_ZONE_STATE) {
                throw new ConfigurationProblem(label + "." + ZONE_STATES + " \"" + entry.getKey()
                        + "\" must be a whole number from 0 to " + MapEventState.MAX_ZONE_STATE + ".");
            }
            chosen.put(entry.getKey(), state);
        }
        return ZoneStateChoice.of(chosen);
    }

    private static Map<String, Clock> clocks(com.typesafe.config.Config conf, String label) {
        if (!conf.hasPath(BY_TIME)) {
            return Map.of();
        }
        if (!(conf.getValue(BY_TIME) instanceof ConfigObject object)) {
            throw new ConfigurationProblem(label + "." + BY_TIME + " must be an object of \"time windows\" = clock.");
        }
        var clocks = new HashMap<String, Clock>();
        for (var entry : object.entrySet()) {
            var value = entry.getValue();
            var clock = value.valueType() == ConfigValueType.STRING ? Clock.parse((String) value.unwrapped()) : null;
            if (clock == null) {
                throw new ConfigurationProblem(
                        label + "." + BY_TIME + " \"" + entry.getKey() + "\" must be clock, always or off.");
            }
            clocks.put(entry.getKey(), clock);
        }
        return clocks;
    }

    private static String string(com.typesafe.config.Config conf, String key, String label) {
        if (conf.getValue(key).valueType() != ConfigValueType.STRING) {
            throw new ConfigurationProblem(label + "." + key + " must be a string.");
        }
        return conf.getString(key);
    }

    private static Map<Integer, Integer> numbers(
            com.typesafe.config.Config conf, String key, String label, int min, int max) {
        if (!conf.hasPath(key)) {
            return Map.of();
        }
        if (!(conf.getValue(key) instanceof ConfigObject object)) {
            throw new ConfigurationProblem(label + "." + key + " must be an object of id = value.");
        }
        var values = new HashMap<Integer, Integer>();
        for (var entry : object.entrySet()) {
            int id;
            int value;
            try {
                id = Integer.parseInt(entry.getKey());
                value = entry.getValue().atKey("value").getInt("value");
            } catch (NumberFormatException | ConfigException notANumber) {
                throw new ConfigurationProblem(
                        label + "." + key + " needs a whole number for " + entry.getKey() + " and its value.");
            }
            if (id < 0 || value < min || value > max) {
                throw new ConfigurationProblem(
                        label + "." + key + "." + entry.getKey() + " must be from " + min + " to " + max + ".");
            }
            values.put(id, value);
        }
        return values;
    }
}
