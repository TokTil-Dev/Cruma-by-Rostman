package com.rost.cruma.core.spawn;

import com.rost.cruma.core.spawn.NpcPosDocument.State;
import com.rost.cruma.core.spawn.SpawnPosition.Anywhere;
import com.rost.cruma.core.spawn.SpawnPosition.Fixed;
import com.rost.cruma.core.spawn.SpawnPosition.Point;
import com.rost.cruma.core.spawn.SpawnPosition.Weighted;
import com.rost.cruma.core.world.WorldDimensions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SpawnTileMove {
    private SpawnTileMove() {}

    public record Result(State state, int zones, int makers) {}

    record Selection(Set<Integer> zones, Set<Integer> makers) {}

    public static Result copy(State state, int fromX, int fromY, int toX, int toY) {
        var selection = select(state, fromX, fromY);
        var dx = (toX - fromX) * WorldDimensions.TILE_SIZE;
        var dy = (toY - fromY) * WorldDimensions.TILE_SIZE;
        var next = state;
        var renamed = new HashMap<String, String>();
        for (var index : selection.zones()) {
            var zone = state.territories().get(index);
            var name = SpawnNames.newZoneName(next, zone.centerX() + dx, zone.centerY() + dy);
            renamed.put(zone.name(), name);
            next = next.withTerritory(new SpawnTerritory(
                    next.territories().size(),
                    name,
                    false,
                    0,
                    shifted(zone.points(), dx, dy),
                    -1,
                    SourceSpan.NONE,
                    SourceSpan.NONE,
                    SourceSpan.NONE));
        }
        for (var index : selection.makers()) {
            var maker = state.makers().get(index);
            var copyIndex = next.makers().size();
            var npcs = new ArrayList<SpawnNpc>();
            for (var npc : maker.npcs()) {
                npcs.add(npc.copied(copyIndex, moved(npc.position(), dx, dy)));
            }
            var names = rename(maker.territoryNames(), renamed);
            var banned = rename(maker.bannedTerritoryNames(), renamed);
            var name = maker.name().isEmpty()
                    ? ""
                    : SpawnNames.newMakerName(next, names.isEmpty() ? maker.name() : names.getFirst());
            next = next.withMaker(maker.copied(copyIndex, names, banned, name, npcs));
        }
        return new Result(next, selection.zones().size(), selection.makers().size());
    }

    public static Result move(State state, int fromX, int fromY, int toX, int toY) {
        var selection = select(state, fromX, fromY);
        return new Result(
                shift(
                        state,
                        selection,
                        (toX - fromX) * WorldDimensions.TILE_SIZE,
                        (toY - fromY) * WorldDimensions.TILE_SIZE),
                selection.zones().size(),
                selection.makers().size());
    }

    public static Result swap(State state, int aX, int aY, int bX, int bY) {
        var first = select(state, aX, aY);
        var second = select(state, bX, bY);
        var dx = (bX - aX) * WorldDimensions.TILE_SIZE;
        var dy = (bY - aY) * WorldDimensions.TILE_SIZE;
        var next = shift(state, first, dx, dy);
        next = shift(next, second, -dx, -dy);
        return new Result(
                next,
                first.zones().size() + second.zones().size(),
                first.makers().size() + second.makers().size());
    }

    static Selection select(State state, int tileX, int tileY) {
        var minX = (tileX - WorldDimensions.TILE_ZERO_COORD_X) * WorldDimensions.TILE_SIZE;
        var minY = (tileY - WorldDimensions.TILE_ZERO_COORD_Y) * WorldDimensions.TILE_SIZE;
        var maxX = minX + WorldDimensions.TILE_SIZE;
        var maxY = minY + WorldDimensions.TILE_SIZE;
        var zones = new LinkedHashSet<Integer>();
        var byName = new HashMap<String, Integer>();
        for (var index = 0; index < state.territories().size(); index++) {
            var zone = state.territories().get(index);
            if (zone == null || zone.domain() || zone.points().isEmpty()) {
                continue;
            }
            var inside = true;
            for (var point : zone.points()) {
                inside &= point.x() >= minX && point.x() < maxX && point.y() >= minY && point.y() < maxY;
            }
            if (inside) {
                zones.add(index);
                byName.putIfAbsent(zone.name(), index);
            }
        }
        var makers = new LinkedHashSet<Integer>();
        for (var index = 0; index < state.makers().size(); index++) {
            var maker = state.makers().get(index);
            if (maker == null) {
                continue;
            }
            var belongs = maker.territoryNames().isEmpty() ? !maker.npcs().isEmpty() : true;
            for (var name : maker.territoryNames()) {
                belongs &= byName.containsKey(name);
            }
            for (var name : maker.bannedTerritoryNames()) {
                belongs &= byName.containsKey(name) || state.territory(name) == null;
            }
            for (var npc : maker.npcs()) {
                belongs &= isPlacedInside(
                        npc.position(),
                        minX,
                        minY,
                        maxX,
                        maxY,
                        maker.territoryNames().isEmpty());
            }
            if (belongs) {
                makers.add(index);
            }
        }
        var changed = true;
        while (changed) {
            changed = false;
            var kept = new HashSet<String>();
            for (var index = 0; index < state.makers().size(); index++) {
                var maker = state.makers().get(index);
                if (maker != null && !makers.contains(index)) {
                    kept.addAll(maker.territoryNames());
                    kept.addAll(maker.bannedTerritoryNames());
                }
            }
            changed |= zones.removeIf(
                    index -> kept.contains(state.territories().get(index).name()));
            var moving = new HashSet<String>();
            for (var index : zones) {
                moving.add(state.territories().get(index).name());
            }
            changed |= makers.removeIf(index -> {
                var maker = state.makers().get(index);
                return maker.territoryNames().stream().anyMatch(name -> !moving.contains(name))
                        || maker.bannedTerritoryNames().stream()
                                .anyMatch(name -> !moving.contains(name) && state.territory(name) != null);
            });
        }
        return new Selection(zones, makers);
    }

    private static boolean isPlacedInside(
            SpawnPosition position, int minX, int minY, int maxX, int maxY, boolean required) {
        return switch (position) {
            case Anywhere ignored -> !required;
            case Fixed fixed -> fixed.x() >= minX && fixed.x() < maxX && fixed.y() >= minY && fixed.y() < maxY;
            case Weighted weighted -> {
                var inside = !weighted.points().isEmpty();
                for (var point : weighted.points()) {
                    inside &= point.x() >= minX && point.x() < maxX && point.y() >= minY && point.y() < maxY;
                }
                yield inside;
            }
        };
    }

    private static State shift(State state, Selection selection, int dx, int dy) {
        var next = state;
        for (var index : selection.zones()) {
            var zone = next.territories().get(index);
            next = next.withTerritory(zone.withPoints(shifted(zone.points(), dx, dy)));
        }
        var changed = new LinkedHashMap<Integer, SpawnMaker>();
        for (var index : selection.makers()) {
            var maker = next.makers().get(index);
            var npcs = new ArrayList<SpawnNpc>();
            for (var npc : maker.npcs()) {
                npcs.add(npc.withPosition(moved(npc.position(), dx, dy)));
            }
            changed.put(index, maker.withNpcs(npcs));
        }
        return next.withMakers(changed);
    }

    private static List<TerritoryPoint> shifted(List<TerritoryPoint> points, int dx, int dy) {
        return points.stream()
                .map(point -> point.withXy(point.x() + dx, point.y() + dy))
                .toList();
    }

    private static SpawnPosition moved(SpawnPosition position, int dx, int dy) {
        return switch (position) {
            case Anywhere anywhere -> anywhere;
            case Fixed fixed -> new Fixed(fixed.x() + dx, fixed.y() + dy, fixed.z(), fixed.heading());
            case Weighted weighted ->
                new Weighted(weighted.points().stream()
                        .map(point ->
                                new Point(point.x() + dx, point.y() + dy, point.z(), point.heading(), point.percent()))
                        .toList());
        };
    }

    private static List<String> rename(List<String> names, Map<String, String> renamed) {
        return names.stream().map(name -> renamed.getOrDefault(name, name)).toList();
    }
}
