package com.rost.cruma.viewer.agent.view;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.world.EmitterTimeWindows;
import com.rost.cruma.core.world.MapEventCatalog;
import com.rost.cruma.core.world.MapEventCatalog.Group;
import com.rost.cruma.core.world.MapEventCatalog.Timed;
import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.MapEventState.Clock;
import com.rost.cruma.core.world.MapEventState.Start;
import com.rost.cruma.core.world.ServerEvents;
import com.rost.cruma.core.world.ZoneStateChoice;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class MapEventEdit {
    static final String[] STARTS = {"all_shown", "game_start", "server_start"};
    private static final String[] ARGUMENTS = {"map_events", "events", "castles", "by_time", "zone_states", "set_time"};
    private static final String ALL = "all";
    private static final String[] SIDES = {"both", "light", "dark", "none"};
    private static final int[] SIDE_VALUES = {
        MapEventState.BOTH_SIDES, MapEventState.LIGHT, MapEventState.DARK, MapEventState.NO_SIDE
    };
    private static final int MOST_EVENTS = 40;
    private static final int EFFECT_NAMES = 3;
    private static final int ACTORS = 2;

    private MapEventEdit() {}

    record Result(MapEventState state, @Nullable Float hour, List<String> notes) {}

    static boolean asks(ToolArgs args) {
        for (var name : ARGUMENTS) {
            if (args.has(name)) {
                return true;
            }
        }
        return false;
    }

    static boolean asksServer(ToolArgs args) throws ToolProblem {
        return "server_start".equals(args.getText("map_events"));
    }

    static Result apply(
            ToolArgs args,
            MapEventState state,
            MapEventCatalog catalog,
            ServerEvents server,
            Map<String, List<Integer>> zoneMaps,
            float hour)
            throws ToolProblem {
        return apply(args, state, catalog, server, zoneMaps, Map.of(), hour);
    }

    static Result apply(
            ToolArgs args,
            MapEventState state,
            MapEventCatalog catalog,
            ServerEvents server,
            Map<String, List<Integer>> zoneMaps,
            Map<String, Integer> zoneDefaults,
            float hour)
            throws ToolProblem {
        var notes = new ArrayList<String>();
        var next = start(args, state, server);
        var events = object(args, "events");
        for (var entry : events.entrySet()) {
            var id = id(entry.getKey());
            next = next.withEvent(id, on(entry.getKey(), entry.getValue()));
            if (!isKnown(catalog, server, id)) {
                notes.add("Event " + id
                        + " has no effects in the tiles of this viewer, and the server's script folder does"
                        + " not name it. The choice is kept for other tiles and exports.");
            }
        }
        var castles = object(args, "castles");
        if (castles.containsKey(ALL)) {
            next = next.withCastleSideForAll(side(ALL, castles.get(ALL)));
        }
        for (var entry : castles.entrySet()) {
            if (!entry.getKey().equalsIgnoreCase(ALL)) {
                next = next.withCastleSide(castle(entry.getKey()), side(entry.getKey(), entry.getValue()));
            }
        }
        var clocks = object(args, "by_time");
        if (clocks.containsKey(ALL)) {
            next = next.withClockForAll(clock(ALL, clocks.get(ALL)));
        }
        for (var entry : clocks.entrySet()) {
            if (!entry.getKey().equalsIgnoreCase(ALL)) {
                next = next.withClock(
                        timed(catalog, entry.getKey()).windows().label(), clock(entry.getKey(), entry.getValue()));
            }
        }
        var zoneStates = object(args, "zone_states");
        if (zoneStates.containsKey(ALL)) {
            if (!isDefault(zoneStates.get(ALL)) && zoneState(ALL, zoneStates.get(ALL)) != MapEventState.AS_THE_MAP) {
                throw new ToolProblem("zone_states takes all only with 0 or \"default\", which clears the choice of"
                        + " every map. A state belongs to one map, such as {\"25_18_Classic\":3}.");
            }
            next = next.withZoneStates(ZoneStateChoice.EMPTY);
        }
        for (var entry : zoneStates.entrySet()) {
            var map = entry.getKey().strip();
            if (map.equalsIgnoreCase(ALL)) {
                continue;
            }
            if (map.isEmpty()) {
                throw new ToolProblem("zone_states needs a map name, such as {\"25_18_Classic\":3}.");
            }
            var byDefault = defaultOf(zoneDefaults, map);
            var zone = isDefault(entry.getValue()) ? byDefault : zoneState(map, entry.getValue());
            next = next.withZoneState(map, zone, byDefault);
            if (zone != MapEventState.AS_THE_MAP && !hasZoneStates(zoneMaps, map)) {
                notes.add("Map " + map + " has no zone states in the tiles of this viewer. The choice is kept for"
                        + " other tiles and exports.");
            }
        }
        Float time = null;
        var hours = args.getText("set_time");
        if (hours != null) {
            var timed = timed(catalog, hours);
            var label = timed.windows().label();
            if (next.clock(label) != Clock.FOLLOW) {
                throw new ToolProblem("Set time works while these hours go by clock, and they are set to "
                        + next.clock(label).key + ". Set them to clock in by_time first.");
            }
            time = timed.nextWindow(hour).middle();
        }
        return new Result(next, time, notes);
    }

    static JSONObject describe(
            MapEventState state,
            MapEventCatalog catalog,
            ServerEvents server,
            Map<String, List<Integer>> zoneMaps,
            float hour) {
        return describe(state, catalog, server, zoneMaps, Map.of(), hour);
    }

    static JSONObject describe(
            MapEventState state,
            MapEventCatalog catalog,
            ServerEvents server,
            Map<String, List<Integer>> zoneMaps,
            Map<String, Integer> zoneDefaults,
            float hour) {
        var data = new JSONObject();
        data.put("start", state.start().key);
        data.put("summary", state.summary());
        data.put(
                "server_start", server.isEmpty() ? "needs the server's script folder of this client" : "can be chosen");
        data.put(
                "zone_states",
                new JSONObject(new LinkedHashMap<>(state.zoneStates().getStates())));
        var maps = new JSONArray();
        zoneMaps.forEach((map, states) -> {
            var entry = new JSONObject();
            entry.put("map", map);
            entry.put("states", states);
            var byDefault = defaultOf(zoneDefaults, map);
            entry.put("chosen", state.zoneStateOf(map, byDefault));
            if (byDefault != MapEventState.AS_THE_MAP) {
                entry.put("default", byDefault);
            }
            maps.add(entry);
        });
        data.put("zone_state_maps", maps);
        var events = new JSONArray();
        for (var group : catalog.getEvents()) {
            if (events.size() == MOST_EVENTS) {
                break;
            }
            var event = new JSONObject();
            event.put("id", group.id());
            var name = server.name(group.id());
            if (name != null && !name.isBlank()) {
                event.put("name", name);
            }
            event.put("on", state.isOn(group.id()));
            event.put("effects", group.actors().size());
            event.put("maps", group.maps());
            event.put("actors", actors(group));
            events.add(event);
        }
        data.put("events", events);
        if (catalog.getEvents().size() > MOST_EVENTS) {
            data.put("more_events", catalog.getEvents().size() - MOST_EVENTS);
        }
        var castles = new JSONArray();
        for (var group : catalog.getCastles()) {
            var castle = new JSONObject();
            castle.put("id", group.id());
            castle.put("name", group.name());
            castle.put("side", word(state.castleSide(group.id())));
            castle.put("effects", group.actors().size());
            castle.put("actors", actors(group));
            castles.add(castle);
        }
        data.put("castles", castles);
        var clocks = new JSONObject();
        clocks.put(ALL, state.clockForAll().key);
        var groups = new JSONArray();
        for (var timed : catalog.getTimed()) {
            var group = new JSONObject();
            var label = timed.windows().label();
            group.put("hours", label);
            group.put("clock", state.clock(label).key);
            group.put(
                    "effects",
                    timed.effects()
                            .subList(0, Math.min(EFFECT_NAMES, timed.effects().size())));
            group.put("effect_count", timed.effectCount());
            group.put("sounds", timed.soundCount());
            if (timed.soundCount() > 0) {
                group.put(
                        "sound_names",
                        timed.soundNames()
                                .subList(
                                        0,
                                        Math.min(
                                                EFFECT_NAMES, timed.soundNames().size())));
            }
            group.put(
                    "set_time", EmitterTimeWindows.clock(timed.nextWindow(hour).middle()));
            group.put("actors", actors(timed.places()));
            groups.add(group);
        }
        clocks.put("groups", groups);
        data.put("by_time", clocks);
        if (!state.exceptions().isEmpty()) {
            data.put("changed_events", state.describe());
        }
        return data;
    }

    private static List<String> actors(Group group) {
        var names = new ArrayList<String>();
        for (var actor : group.actors()) {
            if (names.size() == ACTORS) {
                break;
            }
            names.add(actor.name());
        }
        return names;
    }

    private static MapEventState start(ToolArgs args, MapEventState state, ServerEvents server) throws ToolProblem {
        var word = WorldFacts.choice(args, "map_events", STARTS);
        if (word == null) {
            return state;
        }
        var start = Start.parse(word);
        if (start == state.start()) {
            return state;
        }
        if (start == Start.SERVER_START && server.isEmpty()) {
            throw new ToolProblem("server_start needs the server's script folder of this client, with areadata.txt. Set"
                    + " viewer.world.spawns.script_path with options, or ask the person to choose it in Settings.");
        }
        var next = switch (start) {
            case ALL_SHOWN -> MapEventState.ALL_SHOWN;
            case GAME_START -> MapEventState.GAME_START;
            case SERVER_START -> MapEventState.serverStart(server.onAtStart());
        };
        return next.withClocks(state.clocks()).withZoneStates(state.zoneStates());
    }

    private static Map<String, Object> object(ToolArgs args, String name) throws ToolProblem {
        var value = args.getJson().get(name);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof JSONObject object)) {
            throw new ToolProblem(name + " must be an object, such as " + example(name) + ".");
        }
        return object;
    }

    private static boolean isDefault(Object value) {
        return value instanceof String text && text.strip().equalsIgnoreCase("default");
    }

    private static int defaultOf(Map<String, Integer> zoneDefaults, String map) {
        for (var entry : zoneDefaults.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(map)) {
                return entry.getValue();
            }
        }
        return MapEventState.AS_THE_MAP;
    }

    private static int zoneState(String map, Object value) throws ToolProblem {
        if (value instanceof Number number) {
            var state = number.intValue();
            if (number.doubleValue() == state
                    && state >= MapEventState.AS_THE_MAP
                    && state <= MapEventState.MAX_ZONE_STATE) {
                return state;
            }
        }
        throw new ToolProblem("zone_states \"" + map + "\" takes 0, as the map, a state from 1 to "
                + MapEventState.MAX_ZONE_STATE + ", or \"default\".");
    }

    private static boolean hasZoneStates(Map<String, List<Integer>> zoneMaps, String map) {
        for (var loaded : zoneMaps.keySet()) {
            if (loaded.equalsIgnoreCase(map)) {
                return true;
            }
        }
        return false;
    }

    private static String example(String name) {
        return switch (name) {
            case "events" -> "{\"22226292\":\"on\"}";
            case "castles" -> "{\"Giran\":\"dark\"} or {\"all\":\"both\"}";
            case "zone_states" -> "{\"25_18_Classic\":3}, {\"22_22_Classic\":\"default\"} or {\"all\":0}";
            default -> "{\"all\":\"off\"} or {\"02:00-02:10, 04:00-04:10\":\"always\"}";
        };
    }

    private static int id(String key) throws ToolProblem {
        try {
            var id = Integer.parseInt(key.strip());
            if (id >= 0) {
                return id;
            }
        } catch (NumberFormatException wrong) {
        }
        throw new ToolProblem("\"" + key
                + "\" is not an event id. An id is a whole number, such as 22226292. Call view_settings with"
                + " show map_events for the list.");
    }

    private static boolean on(String key, Object value) throws ToolProblem {
        var word = String.valueOf(value).strip().toLowerCase(Locale.ROOT);
        return switch (word) {
            case "on", "true", "shown" -> true;
            case "off", "false", "hidden" -> false;
            default -> throw new ToolProblem("Event " + key + " takes on or off, not \"" + value + "\".");
        };
    }

    private static boolean isKnown(MapEventCatalog catalog, ServerEvents server, int id) {
        if (server.get(id) != null) {
            return true;
        }
        for (var group : catalog.getEvents()) {
            if (group.id() == id) {
                return true;
            }
        }
        return false;
    }

    private static int castle(String key) throws ToolProblem {
        var text = key.strip();
        for (var id = 1; id <= MapEventState.CASTLES; id++) {
            if (text.equalsIgnoreCase(MapEventCatalog.castleName(id)) || text.equals(Integer.toString(id))) {
                return id;
            }
        }
        var names = new ArrayList<String>();
        for (var id = 1; id <= MapEventState.CASTLES; id++) {
            names.add(MapEventCatalog.castleName(id));
        }
        throw new ToolProblem("There is no castle \""
                + key
                + "\". castles takes all, "
                + String.join(", ", names)
                + ", or their ids 1 to "
                + MapEventState.CASTLES
                + ".");
    }

    private static int side(String key, Object value) throws ToolProblem {
        var word = String.valueOf(value).strip().toLowerCase(Locale.ROOT);
        for (var index = 0; index < SIDES.length; index++) {
            if (SIDES[index].equals(word)) {
                return SIDE_VALUES[index];
            }
        }
        throw new ToolProblem("The castle " + key + " takes " + String.join(", ", SIDES) + ", not \"" + value + "\".");
    }

    private static String word(int side) {
        for (var index = 0; index < SIDE_VALUES.length; index++) {
            if (SIDE_VALUES[index] == side) {
                return SIDES[index];
            }
        }
        return "none";
    }

    private static Clock clock(String key, Object value) throws ToolProblem {
        var word = String.valueOf(value).strip().toLowerCase(Locale.ROOT);
        var clock = Clock.parse("by_clock".equals(word) ? Clock.FOLLOW.key : word);
        if (clock == null) {
            throw new ToolProblem("by_time " + key + " takes clock, always or off, not \"" + value + "\".");
        }
        return clock;
    }

    private static Timed timed(MapEventCatalog catalog, String hours) throws ToolProblem {
        var wanted = hours.strip();
        for (var timed : catalog.getTimed()) {
            if (timed.windows().label().equalsIgnoreCase(wanted)) {
                return timed;
            }
        }
        var labels = new ArrayList<String>();
        for (var timed : catalog.getTimed()) {
            labels.add(timed.windows().label());
        }
        throw new ToolProblem(
                labels.isEmpty()
                        ? "The tiles of this viewer have no effects or sounds by time of day. by_time takes all"
                                + " here."
                        : "No effects or sounds by time of day come at \"" + hours + "\" here. They come at: "
                                + String.join("; ", labels) + ".");
    }
}
