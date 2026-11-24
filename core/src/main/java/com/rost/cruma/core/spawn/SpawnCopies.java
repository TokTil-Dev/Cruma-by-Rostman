package com.rost.cruma.core.spawn;

import com.rost.cruma.core.spawn.NpcPosDocument.State;
import com.rost.cruma.core.spawn.SpawnPosition.Anywhere;
import com.rost.cruma.core.spawn.SpawnPosition.Fixed;
import com.rost.cruma.core.spawn.SpawnPosition.Point;
import com.rost.cruma.core.spawn.SpawnPosition.Weighted;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class SpawnCopies {
    private SpawnCopies() {}

    public record Copy(State state, int maker) {}

    public static String copyAsText(State state, int makerIndex, String lineEnding) {
        var maker = state.makers().get(makerIndex);
        var text = new StringBuilder();
        for (var zone : findZones(state, maker)) {
            text.append(NpcPosWriter.territory(zone)).append(lineEnding);
        }
        return text.append(NpcPosWriter.formatMaker(maker, lineEnding)).toString();
    }

    public static Copy duplicate(State state, int makerIndex, double dx, double dy, int dz) {
        var maker = state.makers().get(makerIndex);
        return place(state, findZones(state, maker), maker, (int) Math.round(dx), (int) Math.round(dy), dz);
    }

    public static @Nullable Copy paste(State state, String text, double x, double y, double z) {
        var file = NpcPosFile.parse(text);
        if (file.getMakers().isEmpty()) {
            return null;
        }
        var maker = file.getMakers().getFirst();
        var zones = new ArrayList<SpawnTerritory>();
        for (var name : zoneNames(maker)) {
            var zone = file.getTerritory(name);
            if (zone != null) {
                zones.add(zone);
            }
        }
        var middle = computeMiddle(zones, maker);
        if (middle == null) {
            return null;
        }
        return place(state, zones, maker, (int) Math.round(x - middle[0]), (int) Math.round(y - middle[1]), (int)
                Math.round(z - middle[2]));
    }

    public static double @Nullable [] computeMiddle(List<SpawnTerritory> zones, SpawnMaker maker) {
        if (!zones.isEmpty()) {
            var minX = Double.MAX_VALUE;
            var minY = Double.MAX_VALUE;
            var maxX = -Double.MAX_VALUE;
            var maxY = -Double.MAX_VALUE;
            var bottom = Integer.MAX_VALUE;
            var top = Integer.MIN_VALUE;
            for (var zone : zones) {
                minX = Math.min(minX, zone.minX());
                minY = Math.min(minY, zone.minY());
                maxX = Math.max(maxX, zone.maxX());
                maxY = Math.max(maxY, zone.maxY());
                bottom = Math.min(bottom, zone.bottom());
                top = Math.max(top, zone.top());
            }
            for (var npc : maker.npcs()) {
                if (npc.position() instanceof Fixed fixed) {
                    return new double[] {(minX + maxX) * .5, (minY + maxY) * .5, fixed.z()};
                }
            }
            return new double[] {(minX + maxX) * .5, (minY + maxY) * .5, (bottom + (double) top) * .5};
        }
        for (var npc : maker.npcs()) {
            if (npc.position() instanceof Fixed fixed) {
                return new double[] {fixed.x(), fixed.y(), fixed.z()};
            }
        }
        return null;
    }

    public static List<SpawnTerritory> findZones(State state, SpawnMaker maker) {
        var zones = new ArrayList<SpawnTerritory>();
        for (var name : zoneNames(maker)) {
            var zone = state.territory(name);
            if (zone != null) {
                zones.add(zone);
            }
        }
        return zones;
    }

    private static List<String> zoneNames(SpawnMaker maker) {
        var names = new ArrayList<>(maker.territoryNames());
        for (var name : maker.bannedTerritoryNames()) {
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private static Copy place(State state, List<SpawnTerritory> zones, SpawnMaker maker, int dx, int dy, int dz) {
        var next = state;
        var renamed = new LinkedHashMap<String, String>();
        for (var zone : zones) {
            var points = zone.points().stream()
                    .map(point ->
                            new TerritoryPoint(point.x() + dx, point.y() + dy, point.bottom() + dz, point.top() + dz))
                    .toList();
            var name = SpawnNames.newZoneName(next, zone.centerX() + dx, zone.centerY() + dy);
            renamed.put(zone.name(), name);
            next = next.withTerritory(new SpawnTerritory(
                    next.territories().size(),
                    name,
                    false,
                    0,
                    points,
                    -1,
                    SourceSpan.NONE,
                    SourceSpan.NONE,
                    SourceSpan.NONE));
        }
        var index = next.makers().size();
        var npcs = new ArrayList<SpawnNpc>();
        for (var npc : maker.npcs()) {
            npcs.add(npc.copied(index, moved(npc.position(), dx, dy, dz)));
        }
        var names = rename(maker.territoryNames(), renamed);
        var banned = rename(maker.bannedTerritoryNames(), renamed);
        var name = maker.name().isEmpty()
                ? ""
                : SpawnNames.newMakerName(next, names.isEmpty() ? maker.name() : names.getFirst());
        next = next.withMaker(maker.copied(index, names, banned, name, npcs));
        return new Copy(next, index);
    }

    private static List<String> rename(List<String> names, Map<String, String> renamed) {
        return names.stream().map(name -> renamed.getOrDefault(name, name)).toList();
    }

    private static SpawnPosition moved(SpawnPosition position, int dx, int dy, int dz) {
        return switch (position) {
            case Anywhere anywhere -> anywhere;
            case Fixed fixed -> new Fixed(fixed.x() + dx, fixed.y() + dy, fixed.z() + dz, fixed.heading());
            case Weighted weighted ->
                new Weighted(weighted.points().stream()
                        .map(point -> new Point(
                                point.x() + dx, point.y() + dy, point.z() + dz, point.heading(), point.percent()))
                        .toList());
        };
    }
}
