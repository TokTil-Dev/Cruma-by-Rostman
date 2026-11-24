package com.rost.cruma.core.geodata.generation;

import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.core.geodata.generation.GeoWorldBuilder.StartRounds;
import com.rost.cruma.core.spawn.NpcPosFile;
import com.rost.cruma.core.spawn.SpawnPosition.Fixed;
import com.rost.cruma.core.spawn.SpawnPosition.Weighted;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

final class SpawnStartPoints implements ExtraStartPoints {
    static final int NEAR = 128;

    static final int KEEP = 64;

    static final int DROP_OVER = 32;

    private final HashMap<GeoTile, List<int[]>> fixed = new HashMap<>();
    private final String name;

    SpawnStartPoints(NpcPosFile file, String name) {
        this.name = name;
        for (var maker : file.getMakers()) {
            if (maker == null) {
                continue;
            }
            for (var npc : maker.npcs()) {
                if (npc.position() instanceof Fixed at) {
                    addFixed(at.x(), at.y(), at.z());
                } else if (npc.position() instanceof Weighted weighted) {
                    for (var point : weighted.points()) {
                        addFixed(point.x(), point.y(), point.z());
                    }
                }
            }
        }
    }

    @Override
    public List<StartRounds> forRegion(GeoTile tile) {
        var npcs = fixed.get(tile);
        if (npcs == null) {
            return List.of();
        }
        return List.of((region, round) -> round == 0 ? points(region, npcs) : new float[0]);
    }

    @Override
    public String describe() {
        return "NPC spawns of " + name;
    }

    private void addFixed(int x, int y, int z) {
        fixed.computeIfAbsent(GeoTile.fromWorld(x, y), tile -> new ArrayList<>())
                .add(new int[] {x, y, z});
    }

    private static float[] points(BuiltRegion region, List<int[]> npcs) {
        var points = new StartPointList();
        for (var npc : npcs) {
            var x = npc[0];
            var y = npc[1];
            var z = npc[2];
            if (!StartPointList.hasLayer(region, x, y, z - NEAR, z + NEAR)) {
                points.add(x, y, z + DROP_OVER, z - KEEP, z + KEEP);
            }
        }
        return points.toArray();
    }
}
