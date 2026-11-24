package com.rost.cruma.core.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public record MapEventState(
        Start start,
        Set<Integer> startOn,
        Map<Integer, Integer> events,
        Map<Integer, Integer> castleSides,
        Map<String, Clock> clocks,
        ZoneStateChoice zoneStates) {
    public static final int NONE = 0;
    public static final int SHOW = 1;
    public static final int PLAY = 2;
    public static final int CASTLE_LIGHT = 3;
    public static final int CASTLE_DARK = 4;
    public static final int NPC_SPAWN_TO_SHOW = 5;
    public static final int NPC_SPAWN_TO_HIDE = 6;
    public static final int NO_SIDE = 0;
    public static final int LIGHT = 1;
    public static final int DARK = 2;
    public static final int BOTH_SIDES = 3;
    public static final int CASTLES = 9;
    public static final String ALL_HOURS = "all";
    public static final int AS_THE_MAP = 0;
    public static final int MAX_ZONE_STATE = 10;
    public static final MapEventState ALL_SHOWN = new MapEventState(Start.ALL_SHOWN, Set.of(), Map.of(), Map.of());
    public static final MapEventState GAME_START = new MapEventState(Start.GAME_START, Set.of(), Map.of(), Map.of());

    public enum Start {
        ALL_SHOWN("all_shown", "all shown", "every effect shown"),
        GAME_START("game_start", "game start", "hidden as at game start"),
        SERVER_START("server_start", "server start", "as the server starts");

        public final String key;

        public final String label;

        private final String words;

        Start(String key, String label, String words) {
            this.key = key;
            this.label = label;
            this.words = words;
        }

        public static Start parse(String key) {
            for (var start : values()) {
                if (start.key.equals(key)) {
                    return start;
                }
            }
            return null;
        }
    }

    public enum Clock {
        FOLLOW("clock", "By clock"),
        ALWAYS("always", "Always"),
        OFF("off", "Off");

        public final String key;

        public final String label;

        Clock(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public static Clock parse(String key) {
            for (var clock : values()) {
                if (clock.key.equals(key)) {
                    return clock;
                }
            }
            return null;
        }
    }

    public MapEventState {
        start = start == null ? Start.ALL_SHOWN : start;
        startOn = start == Start.SERVER_START && startOn != null ? Set.copyOf(startOn) : Set.of();
        var kept = new HashMap<Integer, Integer>();
        if (events != null) {
            var knownStart = start != Start.SERVER_START || !startOn.isEmpty();
            for (var entry : events.entrySet()) {
                var id = entry.getKey();
                var value = entry.getValue();
                if (id == null || value == null) {
                    continue;
                }
                var on = value != 0;
                if (!knownStart || on != onAtStart(start, startOn, id)) {
                    kept.put(id, on ? 1 : 0);
                }
            }
        }
        var sides = new HashMap<Integer, Integer>();
        if (castleSides != null) {
            for (var entry : castleSides.entrySet()) {
                var castle = entry.getKey();
                var side = entry.getValue();
                if (castle == null || side == null) {
                    continue;
                }
                var known = side == LIGHT || side == DARK || side == BOTH_SIDES ? side : NO_SIDE;
                if (known != defaultSide(start)) {
                    sides.put(castle, known);
                }
            }
        }
        var chosen = new HashMap<String, Clock>();
        if (clocks != null) {
            var rule = clocks.get(ALL_HOURS) == null ? Clock.FOLLOW : clocks.get(ALL_HOURS);
            if (rule != Clock.FOLLOW) {
                chosen.put(ALL_HOURS, rule);
            }
            for (var entry : clocks.entrySet()) {
                if (entry.getKey() != null
                        && !entry.getKey().equals(ALL_HOURS)
                        && entry.getValue() != null
                        && entry.getValue() != rule) {
                    chosen.put(entry.getKey(), entry.getValue());
                }
            }
        }
        events = Map.copyOf(kept);
        castleSides = Map.copyOf(sides);
        clocks = Map.copyOf(chosen);
        zoneStates = zoneStates == null ? ZoneStateChoice.EMPTY : zoneStates;
    }

    public MapEventState(
            Start start,
            Set<Integer> startOn,
            Map<Integer, Integer> events,
            Map<Integer, Integer> castleSides,
            Map<String, Clock> clocks) {
        this(start, startOn, events, castleSides, clocks, ZoneStateChoice.EMPTY);
    }

    public MapEventState(
            Start start, Set<Integer> startOn, Map<Integer, Integer> events, Map<Integer, Integer> castleSides) {
        this(start, startOn, events, castleSides, Map.of());
    }

    public MapEventState(boolean gameStart, Map<Integer, Integer> events, Map<Integer, Integer> castleSides) {
        this(gameStart ? Start.GAME_START : Start.ALL_SHOWN, Set.of(), events, castleSides);
    }

    public MapEventState(Map<Integer, Integer> events, Map<Integer, Integer> castleSides) {
        this(Start.ALL_SHOWN, Set.of(), events, castleSides);
    }

    public static MapEventState serverStart(Set<Integer> on) {
        return new MapEventState(Start.SERVER_START, on, Map.of(), Map.of());
    }

    public static List<Integer> zoneStates(int mask) {
        var states = new ArrayList<Integer>();
        for (var state = 1; state <= MAX_ZONE_STATE; state++) {
            if ((mask & 1 << (state - 1)) != 0) {
                states.add(state);
            }
        }
        return states;
    }

    public static boolean switchesVisibility(int command) {
        return command == SHOW || command == CASTLE_LIGHT || command == CASTLE_DARK;
    }

    public static List<Integer> parseIds(String text) {
        var ids = new LinkedHashSet<Integer>();
        for (var part : text.trim().split("[\\s,;]+")) {
            if (part.isEmpty()) {
                continue;
            }
            try {
                var id = Integer.parseInt(part);
                if (id < 0) {
                    throw new NumberFormatException();
                }
                ids.add(id);
            } catch (NumberFormatException notAnId) {
                throw new IllegalArgumentException(
                        "\"" + part + "\" is not an event id. An id is a whole number, such as 22226292.");
            }
        }
        return List.copyOf(ids);
    }

    public static String formatIds(Collection<Integer> ids) {
        return String.join(", ", ids.stream().map(String::valueOf).toList());
    }

    public static String sideName(int side) {
        return switch (side) {
            case LIGHT -> "light";
            case DARK -> "dark";
            case BOTH_SIDES -> "both sides";
            default -> "no side";
        };
    }

    public boolean gameStart() {
        return start != Start.ALL_SHOWN;
    }

    public boolean isEmpty() {
        return start == Start.ALL_SHOWN
                && events.isEmpty()
                && castleSides.isEmpty()
                && clocks.isEmpty()
                && zoneStates.isEmpty();
    }

    public boolean onAtStart(int id) {
        return onAtStart(start, startOn, id);
    }

    public boolean isOn(int id) {
        var value = events.get(id);
        return value != null ? value != 0 : onAtStart(id);
    }

    public int castleSide(int castle) {
        return castleSides.getOrDefault(castle, defaultSide(start));
    }

    public boolean showsCastle(int castle, boolean dark) {
        var side = castleSide(castle);
        return side == BOTH_SIDES || side == (dark ? DARK : LIGHT);
    }

    public boolean hides(List<int[]> entries) {
        if (entries.isEmpty() || isEmpty()) {
            return false;
        }
        var hidden = gameStart();
        var switched = false;
        for (var entry : entries) {
            if (!switchesVisibility(entry[1])) {
                continue;
            }
            switched = true;
            var shown = entry[1] == SHOW ? isOn(entry[0]) : showsCastle(entry[0], entry[1] == CASTLE_DARK);
            if (hidden && shown) {
                return false;
            }
            if (!hidden && !shown) {
                return true;
            }
        }
        return hidden && switched;
    }

    public MapEventState withEvent(int id, boolean on) {
        var next = new HashMap<>(events);
        next.put(id, on ? 1 : 0);
        return new MapEventState(start, startOn, next, castleSides, clocks, zoneStates);
    }

    public MapEventState withCastleSide(int castle, Integer side) {
        var next = new HashMap<>(castleSides);
        if (side == null) {
            next.remove(castle);
        } else {
            next.put(castle, side);
        }
        return new MapEventState(start, startOn, events, next, clocks, zoneStates);
    }

    public Integer castleSideForAll() {
        var side = castleSide(1);
        for (var castle = 2; castle <= CASTLES; castle++) {
            if (castleSide(castle) != side) {
                return null;
            }
        }
        for (var castle : castleSides.keySet()) {
            if (castleSide(castle) != side) {
                return null;
            }
        }
        return side;
    }

    public MapEventState withCastleSideForAll(int side) {
        var next = new HashMap<Integer, Integer>();
        for (var castle = 1; castle <= CASTLES; castle++) {
            next.put(castle, side);
        }
        for (var castle : castleSides.keySet()) {
            next.put(castle, side);
        }
        return new MapEventState(start, startOn, events, next, clocks, zoneStates);
    }

    public MapEventState withStartOn(Set<Integer> on) {
        return start == Start.SERVER_START
                ? new MapEventState(start, on, events, castleSides, clocks, zoneStates)
                : this;
    }

    public Clock clock(String windows) {
        return clocks.isEmpty() ? Clock.FOLLOW : clocks.getOrDefault(windows, clockForAll());
    }

    public Clock clockForAll() {
        return clocks.getOrDefault(ALL_HOURS, Clock.FOLLOW);
    }

    public MapEventState withClockForAll(Clock clock) {
        return new MapEventState(start, startOn, events, castleSides, Map.of(ALL_HOURS, clock), zoneStates);
    }

    public Map<String, Clock> clocksApart() {
        var apart = new HashMap<>(clocks);
        apart.remove(ALL_HOURS);
        return apart;
    }

    public MapEventState withClock(String windows, Clock clock) {
        var next = new HashMap<>(clocks);
        next.put(windows, clock);
        return new MapEventState(start, startOn, events, castleSides, next, zoneStates);
    }

    public MapEventState withClocks(Map<String, Clock> chosen) {
        return new MapEventState(start, startOn, events, castleSides, chosen, zoneStates);
    }

    public int zoneStateOf(String map, int defaultState) {
        return zoneStates.getState(map, defaultState);
    }

    public int zoneStateOf(String map) {
        return zoneStateOf(map, AS_THE_MAP);
    }

    public MapEventState withZoneState(String map, int state) {
        return withZoneStates(zoneStates.with(map, state));
    }

    public MapEventState withZoneState(String map, int state, int defaultState) {
        return withZoneStates(zoneStates.with(map, state, defaultState));
    }

    public MapEventState withZoneStates(ZoneStateChoice choice) {
        var next = choice == null ? ZoneStateChoice.EMPTY : choice;
        return next.equals(zoneStates) ? this : new MapEventState(start, startOn, events, castleSides, clocks, next);
    }

    public boolean showsInZoneState(ZoneRender render) {
        return render.shows(zoneStates);
    }

    public float timeRatio(EmitterTimeWindows windows, double hour) {
        if (windows.always()) {
            return 1f;
        }
        return switch (clock(windows.label())) {
            case FOLLOW -> windows.ratio(hour);
            case ALWAYS -> 1f;
            case OFF -> 0f;
        };
    }

    public boolean showsDuring(EmitterTimeWindows windows, double from, double to) {
        if (windows.always()) {
            return true;
        }
        return switch (clock(windows.label())) {
            case FOLLOW -> windows.showsDuring(from, to);
            case ALWAYS -> true;
            case OFF -> false;
        };
    }

    public boolean playsInHours(AmbientSoundHours hours, double hour) {
        if (hours.isEmpty()) {
            return false;
        }
        if (clocks.isEmpty()) {
            return hours.contains(hour);
        }
        return switch (clock(hours.label())) {
            case FOLLOW -> hours.contains(hour);
            case ALWAYS -> true;
            case OFF -> false;
        };
    }

    public List<Integer> exceptions() {
        return events.keySet().stream().sorted().toList();
    }

    public List<Integer> hidden() {
        return ids(0);
    }

    public List<Integer> shown() {
        return ids(1);
    }

    public MapEventState withExceptions(Collection<Integer> ids) {
        var next = new HashMap<Integer, Integer>();
        for (var id : ids) {
            next.put(id, onAtStart(id) ? 0 : 1);
        }
        return new MapEventState(start, startOn, next, castleSides, clocks, zoneStates);
    }

    public MapEventState withHidden(Collection<Integer> ids) {
        return with(ids, 0);
    }

    public MapEventState withShown(Collection<Integer> ids) {
        return with(ids, 1);
    }

    public String describe() {
        var text = new StringBuilder(start.words);
        var changes = new ArrayList<String>();
        if (!hidden().isEmpty()) {
            changes.add(words(hidden()) + " off");
        }
        if (!shown().isEmpty()) {
            changes.add(words(shown()) + " on");
        }
        if (!changes.isEmpty()) {
            text.append(", except ").append(String.join(" and ", changes));
        }
        if (!castleSides.isEmpty()) {
            var castles = new ArrayList<String>();
            castleSides.keySet().stream()
                    .sorted()
                    .forEach(castle ->
                            castles.add(MapEventCatalog.castleName(castle) + " " + sideName(castleSides.get(castle))));
            text.append("; castles: ").append(String.join(", ", castles));
        }
        if (!clocks.isEmpty()) {
            text.append("; by time of day: ").append(clockWords());
        }
        if (!zoneStates.isEmpty()) {
            text.append("; ").append(zoneStates.describe());
        }
        return text.toString();
    }

    public String clockWords() {
        var parts = new ArrayList<String>();
        if (clocks.containsKey(ALL_HOURS)) {
            parts.add(ALL_HOURS + " " + clockForAll().key);
        }
        var apart = clocksApart();
        apart.keySet().stream().sorted().forEach(windows -> parts.add(windows + " " + apart.get(windows).key));
        return String.join("; ", parts);
    }

    public String summary() {
        var text = new StringBuilder(start.label);
        if (!hidden().isEmpty()) {
            text.append(", ").append(count(hidden().size())).append(" off");
        }
        if (!shown().isEmpty()) {
            text.append(", ").append(count(shown().size())).append(" on");
        }
        if (castleSides.size() == 1) {
            var castle = castleSides.keySet().iterator().next();
            text.append(", ")
                    .append(MapEventCatalog.castleName(castle))
                    .append(' ')
                    .append(sideName(castleSides.get(castle)));
        } else if (!castleSides.isEmpty()) {
            text.append(", ").append(castleSides.size()).append(" castles set");
        }
        if (clockForAll() != Clock.FOLLOW) {
            text.append(", time windows ").append(clockForAll().key);
        }
        var apart = clocksApart().size();
        if (apart > 0) {
            text.append(", ").append(apart).append(apart == 1 ? " time window set" : " time windows set");
        }
        if (!zoneStates.isEmpty()) {
            text.append(", ").append(zoneStates.getSummary());
        }
        return text.toString();
    }

    private List<Integer> ids(int value) {
        return events.entrySet().stream()
                .filter(entry -> entry.getValue() == value)
                .map(Entry::getKey)
                .sorted()
                .toList();
    }

    private MapEventState with(Collection<Integer> ids, int value) {
        var next = new HashMap<Integer, Integer>();
        events.forEach((id, other) -> {
            if (other != value) {
                next.put(id, other);
            }
        });
        for (var id : ids) {
            next.put(id, value);
        }
        return new MapEventState(start, startOn, next, castleSides, clocks, zoneStates);
    }

    private static String words(List<Integer> ids) {
        return (ids.size() == 1 ? "event " : "events ") + formatIds(ids);
    }

    private static String count(int events) {
        return events + (events == 1 ? " event" : " events");
    }

    private static boolean onAtStart(Start start, Set<Integer> startOn, int id) {
        return switch (start) {
            case ALL_SHOWN -> true;
            case GAME_START -> false;
            case SERVER_START -> startOn.contains(id);
        };
    }

    private static int defaultSide(Start start) {
        return start == Start.ALL_SHOWN ? BOTH_SIDES : NO_SIDE;
    }
}
