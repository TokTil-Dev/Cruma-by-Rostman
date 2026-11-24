package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.export.LocationExport.Area;
import com.rost.cruma.core.spawn.SpawnMaker;
import com.rost.cruma.core.spawn.SpawnNpc;
import com.rost.cruma.core.spawn.SpawnTerritory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class PlacelessSpawns {
    private final Map<Integer, Spawner> makers = new LinkedHashMap<>();

    private record Spawner(int spawns, List<SpawnTerritory> territories, List<SpawnTerritory> banned) {
        boolean lands(double minX, double minY, double maxX, double maxY) {
            for (var territory : banned) {
                if (territory.covers(minX, minY, maxX, maxY)) {
                    return false;
                }
            }
            for (var territory : territories) {
                if (territory.reaches(minX, minY, maxX, maxY)) {
                    return true;
                }
            }
            return false;
        }
    }

    static boolean meets(List<SpawnTerritory> territories, int minX, int minY, int size) {
        for (var territory : territories) {
            if (territory.maxX() >= minX
                    && territory.minX() < minX + size
                    && territory.maxY() >= minY
                    && territory.minY() < minY + size) {
                return true;
            }
        }
        return false;
    }

    void add(
            SpawnMaker maker,
            List<SpawnTerritory> territories,
            List<SpawnTerritory> banned,
            int minX,
            int minY,
            int size) {
        var spawner = new Spawner(random(maker), territories, banned);
        if (spawner.spawns() > 0 && spawner.lands(minX, minY, minX + size, minY + size)) {
            makers.putIfAbsent(maker.index(), spawner);
        }
    }

    int count(@Nullable Area area) {
        var count = 0;
        for (var spawner : makers.values()) {
            if (area == null || spawner.lands(area.minX(), area.minY(), area.maxX(), area.maxY())) {
                count += spawner.spawns();
            }
        }
        return count;
    }

    private static int random(SpawnMaker maker) {
        var count = 0;
        for (var npc : maker.npcs()) {
            if (npc.isAnywhere()) {
                count += spawns(npc);
            }
        }
        return count;
    }

    private static int spawns(SpawnNpc npc) {
        return Math.max(1, npc.total());
    }
}
