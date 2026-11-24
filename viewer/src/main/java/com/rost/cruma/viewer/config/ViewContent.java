package com.rost.cruma.viewer.config;

import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.MapEventState.Start;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public record ViewContent(
        boolean effects,
        @Nullable Start mapEvents,
        List<Integer> events,
        @Nullable Integer castleSides,
        @Nullable Integer zoneState) {
    public static final ViewContent AS_THE_CLIENT = new ViewContent(true, null, List.of(), null, null);

    public ViewContent {
        events =
                events == null ? List.of() : events.stream().distinct().sorted().toList();
        if (mapEvents == Start.SERVER_START) {
            throw new IllegalArgumentException("map_events of a capture view is all_shown or game_start");
        }
        for (var id : events) {
            if (id == null || id <= 0) {
                throw new IllegalArgumentException("An event of a capture view is a number above 0, got " + id);
            }
        }
        if (castleSides != null && (castleSides < MapEventState.NO_SIDE || castleSides > MapEventState.BOTH_SIDES)) {
            throw new IllegalArgumentException(
                    "castle_sides of a capture view is both, light, dark or none, got " + castleSides);
        }
        if (castleSides != null && mapEvents != null && castleSides == sideOf(mapEvents)) {
            castleSides = null;
        }
        if (zoneState != null && (zoneState < MapEventState.AS_THE_MAP || zoneState > MapEventState.MAX_ZONE_STATE)) {
            throw new IllegalArgumentException("zone_state of a capture view must be within 0.."
                    + MapEventState.MAX_ZONE_STATE + ", got " + zoneState);
        }
    }

    public static int sideOf(Start start) {
        return new MapEventState(start, Set.of(), Map.of(), Map.of()).castleSide(1);
    }

    public boolean isAsTheClient() {
        return equals(AS_THE_CLIENT);
    }

    public MapEventState over(@Nullable MapEventState client, String map) {
        var state = client == null ? MapEventState.ALL_SHOWN : client;
        if (mapEvents != null) {
            state = new MapEventState(mapEvents, Set.of(), Map.of(), Map.of(), state.clocks(), state.zoneStates())
                    .withExceptions(events);
        } else {
            for (var id : events) {
                state = state.withEvent(id, false);
            }
        }
        if (castleSides != null) {
            state = state.withCastleSideForAll(castleSides);
        }
        if (zoneState != null) {
            state = state.withZoneState(map, zoneState);
        }
        return state;
    }
}
