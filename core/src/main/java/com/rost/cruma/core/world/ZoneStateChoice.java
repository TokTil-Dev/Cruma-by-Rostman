package com.rost.cruma.core.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class ZoneStateChoice {
    public static final int FIRST_STATE = 1;

    public static final ZoneStateChoice EMPTY = new ZoneStateChoice(Map.of(), Map.of());

    private final Map<String, Integer> states;
    private final Map<String, String> names;

    private ZoneStateChoice(Map<String, Integer> states, Map<String, String> names) {
        this.states = states;
        this.names = names;
    }

    public static ZoneStateChoice of(Map<String, Integer> chosen) {
        var result = EMPTY;
        if (chosen != null) {
            for (var entry : new TreeMap<>(chosen).entrySet()) {
                if (entry.getValue() != null) {
                    result = result.with(entry.getKey(), entry.getValue());
                }
            }
        }
        return result;
    }

    public static String key(String map) {
        return map == null || map.isEmpty() ? "" : map.toLowerCase(Locale.ROOT).intern();
    }

    public static String mapOf(String fullName) {
        if (fullName == null) {
            return "";
        }
        var dot = fullName.indexOf('.');
        return dot > 0 ? fullName.substring(0, dot) : fullName;
    }

    public static boolean isState(int state) {
        return state >= 1 && state <= MapEventState.MAX_ZONE_STATE;
    }

    public static int defaultOf(int zoneStart, boolean essence) {
        return essence && zoneStart == 0 ? FIRST_STATE : MapEventState.AS_THE_MAP;
    }

    public static String describe(int state) {
        return state == MapEventState.AS_THE_MAP ? "as the map" : Integer.toString(state);
    }

    public boolean isChosen(String map) {
        return !states.isEmpty() && map != null && states.containsKey(map.toLowerCase(Locale.ROOT));
    }

    public int getState(String map, int defaultState) {
        if (states.isEmpty() || map == null) {
            return defaultState;
        }
        var state = states.get(map.toLowerCase(Locale.ROOT));
        return state == null ? defaultState : state;
    }

    public ZoneStateChoice with(String map, int state, int defaultState) {
        return state == defaultState ? without(map) : with(map, state);
    }

    public ZoneStateChoice without(String map) {
        return with(map, -1);
    }

    public ZoneStateChoice with(String map, int state) {
        var key = key(map);
        if (key.isEmpty()) {
            return this;
        }
        var chosen = state == MapEventState.AS_THE_MAP || isState(state);
        if (chosen
                ? Integer.valueOf(state).equals(states.get(key)) && map.equals(names.get(key))
                : !states.containsKey(key)) {
            return this;
        }
        var nextStates = new HashMap<>(states);
        var nextNames = new HashMap<>(names);
        if (chosen) {
            nextStates.put(key, state);
            nextNames.put(key, map);
        } else {
            nextStates.remove(key);
            nextNames.remove(key);
        }
        return nextStates.isEmpty()
                ? EMPTY
                : new ZoneStateChoice(Collections.unmodifiableMap(nextStates), Collections.unmodifiableMap(nextNames));
    }

    public boolean isEmpty() {
        return states.isEmpty();
    }

    public int size() {
        return states.size();
    }

    public Map<String, Integer> getStates() {
        var result = new TreeMap<String, Integer>(String.CASE_INSENSITIVE_ORDER);
        names.forEach((key, name) -> result.put(name, states.get(key)));
        return Collections.unmodifiableMap(result);
    }

    public String describe() {
        if (states.isEmpty()) {
            return "";
        }
        var parts = new ArrayList<String>();
        getStates().forEach((map, state) -> parts.add(describe(state) + " in " + map));
        return (parts.size() == 1 ? "zone state " : "zone states ") + String.join(", ", parts);
    }

    public String getSummary() {
        return states.size() <= 1 ? describe() : "zone states in " + states.size() + " maps";
    }

    @Override
    public boolean equals(Object other) {
        return other == this || other instanceof ZoneStateChoice choice && states.equals(choice.states);
    }

    @Override
    public int hashCode() {
        return states.hashCode();
    }

    @Override
    public String toString() {
        return states.isEmpty() ? "no choice" : describe();
    }
}
