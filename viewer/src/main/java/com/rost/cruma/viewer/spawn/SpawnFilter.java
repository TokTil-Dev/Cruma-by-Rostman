package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.spawn.NpcDataTable;
import com.rost.cruma.core.spawn.SpawnMaker;
import com.rost.cruma.core.spawn.SpawnSummary;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public record SpawnFilter(Set<SpawnStyle> kinds, int band, String ai, String respawn, String event, boolean unknown) {
    public SpawnFilter {
        kinds = kinds.isEmpty() ? Set.of() : EnumSet.copyOf(kinds);
        respawn = respawn.strip().toLowerCase(Locale.ROOT);
    }

    public static String eventKey(boolean spawnTime, String name) {
        return (spawnTime ? "time:" : "event:") + name;
    }

    public boolean isEmpty() {
        return kinds.isEmpty() && band < 0 && ai.isEmpty() && respawn.isEmpty() && event.isEmpty() && !unknown;
    }

    public int count() {
        var count = kinds.isEmpty() ? 0 : 1;
        count += band >= 0 ? 1 : 0;
        count += ai.isEmpty() ? 0 : 1;
        count += respawn.isEmpty() ? 0 : 1;
        count += event.isEmpty() ? 0 : 1;
        return count + (unknown ? 1 : 0);
    }

    public boolean passes(SpawnMaker maker, SpawnStyle kind, NpcDataTable npcs) {
        if (!kinds.isEmpty() && !kinds.contains(kind)) {
            return false;
        }
        if (band >= 0) {
            var levels = SpawnSummary.computeLevels(maker, npcs);
            if (levels == null || SpawnSummary.levelBand(levels.mean()) != band) {
                return false;
            }
        }
        if (!ai.isEmpty() && !maker.ai().equalsIgnoreCase(ai)) {
            return false;
        }
        if (!respawn.isEmpty()
                && maker.npcs().stream()
                        .noneMatch(npc -> npc.respawn().toLowerCase(Locale.ROOT).equals(respawn))) {
            return false;
        }
        if (!event.isEmpty()
                && !eventKey(false, maker.eventName()).equals(event)
                && !(maker.spawnTime().isEmpty() ? "" : eventKey(true, maker.spawnTimeKind())).equals(event)) {
            return false;
        }
        if (unknown) {
            return npcs.size() > 0 && maker.npcs().stream().anyMatch(npc -> npcs.getNpc(npc.npcName()) == null);
        }
        return true;
    }
}
