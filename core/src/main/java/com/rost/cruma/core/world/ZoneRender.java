package com.rost.cruma.core.world;

import org.jetbrains.annotations.Nullable;

public record ZoneRender(
        int mask,
        int zoneStart,
        boolean hidesWithoutState,
        @Nullable ZoneBlend blend,
        String map,
        boolean essence) {
    public static final int NO_ZONE_STATES = -1;

    public static final ZoneRender EVERY_STATE = new ZoneRender(0, NO_ZONE_STATES, false);

    public ZoneRender {
        map = ZoneStateChoice.key(map);
        essence |= hidesWithoutState;
    }

    public ZoneRender(int mask, int zoneStart, boolean hidesWithoutState, @Nullable ZoneBlend blend, String map) {
        this(mask, zoneStart, hidesWithoutState, blend, map, hidesWithoutState);
    }

    public ZoneRender(int mask, int zoneStart, boolean hidesWithoutState) {
        this(mask, zoneStart, hidesWithoutState, null, "");
    }

    public ZoneRender(int mask, int zoneStart, boolean hidesWithoutState, String map) {
        this(mask, zoneStart, hidesWithoutState, null, map);
    }

    public static ZoneRender ofSound(int mask, int zoneStart, boolean essence, String map) {
        return mask == 0 || zoneStart == NO_ZONE_STATES ? EVERY_STATE : new ZoneRender(mask, zoneStart, essence, map);
    }

    public static ZoneRender ofTerrainLayer(int mask, boolean terrainZoneState, int zoneStart, String map) {
        return mask == 0 || !terrainZoneState ? EVERY_STATE : new ZoneRender(mask, zoneStart, true, map);
    }

    public int defaultState() {
        return ZoneStateChoice.defaultOf(zoneStart, essence);
    }

    public boolean shows(ZoneStateChoice choice) {
        return mask == 0 || shows(choiceIn(choice));
    }

    public boolean shows(int choice) {
        if (mask == 0) {
            return true;
        }
        if (zoneStart == NO_ZONE_STATES) {
            return !hidesWithoutState;
        }
        var state = choice >= 1 && choice <= MapEventState.MAX_ZONE_STATE ? choice : zoneStart;
        if (state < 1 || state > MapEventState.MAX_ZONE_STATE) {
            return !hidesWithoutState;
        }
        return (mask & 1 << (state - 1)) != 0;
    }

    public boolean shows(MapEventState state) {
        return shows(state.zoneStates());
    }

    public int choiceIn(ZoneStateChoice choice) {
        return choice.getState(map, defaultState());
    }

    public ZoneRender hidingWithoutState() {
        return mask == 0 || hidesWithoutState ? this : new ZoneRender(mask, zoneStart, true, blend, map, true);
    }

    public ZoneRender inEssence() {
        return essence ? this : new ZoneRender(mask, zoneStart, hidesWithoutState, blend, map, true);
    }

    public ZoneRender withBlend(@Nullable ZoneBlend blend) {
        return blend == this.blend ? this : new ZoneRender(mask, zoneStart, hidesWithoutState, blend, map, essence);
    }

    public int zoneStateOf(ZoneStateChoice choice) {
        return zoneStart == NO_ZONE_STATES ? NO_ZONE_STATES : zoneStateOf(choiceIn(choice));
    }

    public int zoneStateOf(int choice) {
        if (zoneStart == NO_ZONE_STATES) {
            return NO_ZONE_STATES;
        }
        return choice >= 1 && choice <= MapEventState.MAX_ZONE_STATE ? choice : zoneStart;
    }
}
