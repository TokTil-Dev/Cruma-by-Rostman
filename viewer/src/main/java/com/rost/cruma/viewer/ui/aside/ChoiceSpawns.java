package com.rost.cruma.viewer.ui.aside;

import com.rost.cruma.core.spawn.NpcDataTable;
import com.rost.cruma.core.spawn.NpcPosDocument.State;
import com.rost.cruma.core.spawn.SpawnPosition.Fixed;
import com.rost.cruma.core.spawn.SpawnTerritory;
import com.rost.cruma.core.world.WorldDimensions;
import com.rost.cruma.viewer.spawn.SpawnIndex;
import com.rost.cruma.viewer.spawn.SpawnTotals;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.collections.api.list.primitive.MutableIntList;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.jetbrains.annotations.Nullable;

final class ChoiceSpawns {
    private final State state;
    private final NpcDataTable npcs;
    private final Map<String, MutableIntList> makersByTile = new HashMap<>();

    record Census(long npcs, List<Kind> kinds, long eventNpcs) {}

    record Kind(String name, int id, int level, long count) {}

    ChoiceSpawns(State state, NpcDataTable npcs) {
        this.state = state;
        this.npcs = npcs;
        var index = SpawnIndex.build(state);
        for (var makerIndex = 0; makerIndex < state.makers().size(); makerIndex++) {
            var maker = state.makers().get(makerIndex);
            if (maker == null) {
                continue;
            }
            var tiles = new HashSet<String>();
            for (var name : maker.territoryNames()) {
                var territory = index.getTerritory(state, name);
                if (territory != null) {
                    addTiles(territory, tiles);
                }
            }
            for (var npc : maker.npcs()) {
                if (npc.position() instanceof Fixed fixed) {
                    tiles.add(tileAt(fixed.x(), fixed.y()));
                }
            }
            for (var tile : tiles) {
                makersByTile
                        .computeIfAbsent(tile, ignored -> new IntArrayList())
                        .add(makerIndex);
            }
        }
    }

    static String tileAt(double x, double y) {
        return ((int) Math.floor(x / WorldDimensions.TILE_SIZE) + WorldDimensions.TILE_ZERO_COORD_X)
                + "_"
                + ((int) Math.floor(y / WorldDimensions.TILE_SIZE) + WorldDimensions.TILE_ZERO_COORD_Y);
    }

    Census getCensus(Collection<String> tiles) {
        var makers = new HashSet<Integer>();
        for (var tile : tiles) {
            var listed = makersByTile.get(tile);
            if (listed != null) {
                listed.forEach(makers::add);
            }
        }
        var chosen = Set.copyOf(tiles);
        var totals = SpawnTotals.count(state, territory -> reaches(territory, chosen), makers::contains);
        var counts = new HashMap<String, Long>();
        var eventNpcs = 0L;
        for (var makerIndex : makers) {
            var maker = state.makers().get(makerIndex);
            for (var npc : maker.npcs()) {
                if (maker.eventName().isEmpty()) {
                    counts.merge(npc.npcName(), (long) npc.total(), Long::sum);
                } else {
                    eventNpcs += npc.total();
                }
            }
        }
        var kinds = new ArrayList<Kind>();
        for (var entry : counts.entrySet()) {
            var info = npcs.getNpc(entry.getKey());
            kinds.add(new Kind(
                    entry.getKey(), info == null ? -1 : info.id(), info == null ? 0 : info.level(), entry.getValue()));
        }
        kinds.sort(Comparator.comparingLong(Kind::count).reversed().thenComparing(Kind::name));
        return new Census(totals.npcs(), List.copyOf(kinds), eventNpcs);
    }

    private static void addTiles(SpawnTerritory territory, Set<String> tiles) {
        var size = WorldDimensions.TILE_SIZE;
        var fromX = Math.floorDiv(territory.minX(), size);
        var toX = Math.floorDiv(territory.maxX(), size);
        var fromY = Math.floorDiv(territory.minY(), size);
        var toY = Math.floorDiv(territory.maxY(), size);
        for (var x = fromX; x <= toX; x++) {
            for (var y = fromY; y <= toY; y++) {
                tiles.add((x + WorldDimensions.TILE_ZERO_COORD_X) + "_" + (y + WorldDimensions.TILE_ZERO_COORD_Y));
            }
        }
    }

    private static boolean reaches(@Nullable SpawnTerritory territory, Set<String> chosen) {
        if (territory == null) {
            return false;
        }
        var tiles = new HashSet<String>();
        addTiles(territory, tiles);
        for (var tile : tiles) {
            if (chosen.contains(tile)) {
                return true;
            }
        }
        return false;
    }
}
