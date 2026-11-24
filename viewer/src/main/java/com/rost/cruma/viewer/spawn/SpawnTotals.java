package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.spawn.NpcPosDocument.State;
import com.rost.cruma.core.spawn.SpawnTerritory;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

public record SpawnTotals(long zones, long makers, long npcs) {
    public static SpawnTotals of(State state) {
        return count(state, zone -> true, maker -> true);
    }

    public static SpawnTotals count(State state, Predicate<SpawnTerritory> zone, IntPredicate maker) {
        long zones = 0;
        for (var territory : state.territories()) {
            if (territory != null && zone.test(territory)) {
                zones++;
            }
        }
        long makers = 0;
        long npcs = 0;
        for (var index = 0; index < state.makers().size(); index++) {
            var spawner = state.makers().get(index);
            if (spawner == null || !maker.test(index)) {
                continue;
            }
            makers++;
            for (var npc : spawner.npcs()) {
                npcs += npc.total();
            }
        }
        return new SpawnTotals(zones, makers, npcs);
    }
}
