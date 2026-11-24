package com.rost.cruma.core.spawn;

import com.rost.cruma.core.spawn.NpcPosDocument.State;
import com.rost.cruma.core.spawn.SpawnNpc.Spans;
import com.rost.cruma.core.spawn.SpawnPosition.Fixed;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.Nullable;

public final class SpawnEdits {
    public static final String DEFAULT_RESPAWN = "60sec";

    private SpawnEdits() {}

    public static boolean isTime(String value) {
        return value.matches("\\d+(sec|min|hour|day)");
    }

    public record Carried(int maker, int slot, Fixed position) {}

    public static SpawnNpc newNpc(
            int maker, String name, SpawnPosition position, int total, String respawn, String respawnRandom) {
        return new SpawnNpc(
                -1, maker, name, position, total, respawn, respawnRandom, "", "", "", "", -1, Spans.NONE, List.of());
    }

    public static SpawnTerritory newZone(int index, String name, List<TerritoryPoint> points) {
        return new SpawnTerritory(index, name, false, 0, points, -1, SourceSpan.NONE, SourceSpan.NONE, SourceSpan.NONE);
    }

    public static List<TerritoryPoint> square(int x, int y, int half, int bottom, int top) {
        return List.of(
                new TerritoryPoint(x - half, y - half, bottom, top),
                new TerritoryPoint(x + half, y - half, bottom, top),
                new TerritoryPoint(x + half, y + half, bottom, top),
                new TerritoryPoint(x - half, y + half, bottom, top));
    }

    public static State addNpc(State state, int makerIndex, SpawnNpc npc) {
        var current = state.makers().get(makerIndex);
        var next = state.withNpc(makerIndex, current.npcs().size(), npc);
        var updated = next.makers().get(makerIndex);
        return updated.maximumNpc() >= 0 && updated.totalNpcs() > updated.maximumNpc()
                ? next.withMaker(updated.withMaximumNpc(updated.totalNpcs()))
                : next;
    }

    public static State replaceNpc(State state, int makerIndex, int slot, UnaryOperator<SpawnNpc> change) {
        var maker = state.makers().get(makerIndex);
        if (maker == null || slot >= maker.npcs().size()) {
            return state;
        }
        return state.withNpc(makerIndex, slot, change.apply(maker.npcs().get(slot)));
    }

    public static State withZoneHeights(
            State state, int territoryIndex, @Nullable Integer bottom, @Nullable Integer top) {
        var territory = state.territories().get(territoryIndex);
        if (territory == null) {
            return state;
        }
        var points = territory.points().stream()
                .map(point ->
                        point.withHeights(bottom == null ? point.bottom() : bottom, top == null ? point.top() : top))
                .toList();
        return state.withTerritory(territory.withPoints(points));
    }

    public static List<Carried> carriedBy(State state, SpawnTerritory territory) {
        var carried = new ArrayList<Carried>();
        var first = state.territory(territory.name());
        if (first == null || first.index() != territory.index()) {
            return carried;
        }
        for (var owner : state.makers()) {
            if (owner == null || !owner.territoryNames().contains(territory.name())) {
                continue;
            }
            for (var slot = 0; slot < owner.npcs().size(); slot++) {
                if (owner.npcs().get(slot).position() instanceof Fixed fixed
                        && territory.containsXy(fixed.x(), fixed.y())) {
                    carried.add(new Carried(owner.index(), slot, fixed));
                }
            }
        }
        return carried;
    }

    public static List<TerritoryPoint> shifted(List<TerritoryPoint> points, int shiftX, int shiftY, int shiftZ) {
        return points.stream()
                .map(point -> new TerritoryPoint(
                        point.x() + shiftX, point.y() + shiftY, point.bottom() + shiftZ, point.top() + shiftZ))
                .toList();
    }

    public static State moveZone(State state, int territoryIndex, List<TerritoryPoint> points, List<Carried> moved) {
        var territory = state.territories().get(territoryIndex);
        if (territory == null) {
            return state;
        }
        var next = state.withTerritory(territory.withPoints(points));
        for (var npc : moved) {
            var maker = next.makers().get(npc.maker());
            if (maker != null && npc.slot() < maker.npcs().size()) {
                next = next.withNpc(
                        npc.maker(), npc.slot(), maker.npcs().get(npc.slot()).withPosition(npc.position()));
            }
        }
        return next;
    }

    public static State deleteMaker(State state, int makerIndex, Collection<Integer> zones) {
        var next = state.withoutMaker(makerIndex);
        for (var zone : zones) {
            next = next.withoutTerritory(zone);
        }
        return next;
    }
}
