package com.rost.cruma.core.spawn;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.GeoQueryEngine;
import com.rost.cruma.core.spawn.NpcPosDocument.State;
import com.rost.cruma.core.spawn.SpawnPosition.Anywhere;
import com.rost.cruma.core.spawn.SpawnPosition.Fixed;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class SpawnHealth {
    public static final int MIN_POINTS = 4;

    public static final int REACH = 64;

    private static final int FLOOR_GAP = 128;

    private static final int SINK_GAP = 64;

    private SpawnHealth() {}

    public enum Kind {
        FEET_BLOCKED("Stands on blocked or missing geodata"),
        UNDER_FLOOR("Stands under the geodata floor"),
        UNREACHABLE("No walkable cell leads to it"),
        FEW_POINTS("Too few points to spawn on"),
        FLOATING_ZONE("Zone floats above the ground"),
        NO_GEODATA("No geodata for its region"),
        UNKNOWN_NPC("NPC not in npcdata.txt");

        public final String title;

        Kind(String title) {
            this.title = title;
        }
    }

    public record Finding(
            Kind kind, int maker, int slot, int territory, String where, String detail, double x, double y, double z) {
        public boolean isError() {
            return kind == Kind.UNKNOWN_NPC;
        }
    }

    public static List<Finding> check(
            State state,
            Collection<Integer> makers,
            NpcDataTable npcs,
            GeoDataAccess geodata,
            BooleanSupplier cancelled) {
        var zones = new HashMap<String, SpawnTerritory>();
        for (var zone : state.territories()) {
            if (zone != null && !zone.domain()) {
                zones.putIfAbsent(zone.name(), zone);
            }
        }
        var engine = new GeoQueryEngine(geodata);
        var findings = new ArrayList<Finding>();
        for (var index : makers) {
            if (cancelled.getAsBoolean()) {
                break;
            }
            if (index < 0 || index >= state.makers().size() || state.makers().get(index) == null) {
                continue;
            }
            checkMaker(state.makers().get(index), zones, npcs, geodata, engine, findings);
        }
        return findings;
    }

    static boolean isReachable(GeoQueryEngine engine, GeoDataAccess geodata, int x, int y, int z) {
        var target = new GeoPoint(x, y, z);
        var homeX = Math.floorDiv(x, 16) * 16;
        var homeY = Math.floorDiv(y, 16) * 16;
        var cells = REACH / 16;
        var tried = 0;
        for (var dy = -cells; dy <= cells; dy++) {
            for (var dx = -cells; dx <= cells; dx++) {
                if (dx == 0 && dy == 0 || dx * dx + dy * dy > cells * cells) {
                    continue;
                }
                var cell = geodata.cellAt(homeX + dx * 16, homeY + dy * 16);
                if (cell.availability() != Availability.AVAILABLE
                        || cell.layers().isEmpty()) {
                    continue;
                }
                var selection = engine.findBaseCell(cell, z);
                if (selection.layer().nswe() == 0 || Math.abs(selection.layer().height() - z) > REACH) {
                    continue;
                }
                tried++;
                var from = selection.center();
                if (engine.moveStraight(from, target, Math.hypot(from.x() - x, from.y() - y) + 1)
                        .success()) {
                    return true;
                }
            }
        }
        return tried == 0;
    }

    static double floatingGround(SpawnTerritory zone, GeoDataAccess geodata) {
        var samples = 0;
        var highest = -Double.MAX_VALUE;
        var minX = zone.minX();
        var minY = zone.minY();
        var width = zone.maxX() - minX;
        var height = zone.maxY() - minY;
        for (var i = 0; i < 5; i++) {
            for (var j = 0; j < 5; j++) {
                var x = minX + width * (i + .5) / 5;
                var y = minY + height * (j + .5) / 5;
                if (!zone.containsXy(x, y)) {
                    continue;
                }
                var cell = geodata.cellAt((int) Math.floor(x), (int) Math.floor(y));
                if (cell.availability() != Availability.AVAILABLE
                        || cell.layers().isEmpty()) {
                    continue;
                }
                var below = -Double.MAX_VALUE;
                for (var layer : cell.layers()) {
                    if (layer.height() >= zone.bottom() && layer.height() <= zone.top()) {
                        return Double.NaN;
                    }
                    if (layer.height() < zone.bottom()) {
                        below = Math.max(below, layer.height());
                    }
                }
                if (below == -Double.MAX_VALUE) {
                    return Double.NaN;
                }
                samples++;
                highest = Math.max(highest, below);
            }
        }
        return samples == 0 ? Double.NaN : highest;
    }

    public static @Nullable Fixed dropToFloor(Fixed fixed, GeoDataAccess geodata) {
        var ground = SpawnSampler.findGround(new GeoQueryEngine(geodata), geodata, fixed.x(), fixed.y(), fixed.z());
        if (ground == null) {
            return null;
        }
        return new Fixed(fixed.x(), fixed.y(), (int) Math.round(ground.z()), fixed.heading());
    }

    public static List<Integer> makersIn(State state, double minX, double minY, double maxX, double maxY) {
        var zones = new HashMap<String, SpawnTerritory>();
        for (var zone : state.territories()) {
            if (zone != null && !zone.domain()) {
                zones.putIfAbsent(zone.name(), zone);
            }
        }
        var result = new ArrayList<Integer>();
        for (var index = 0; index < state.makers().size(); index++) {
            var maker = state.makers().get(index);
            if (maker == null) {
                continue;
            }
            var inside = false;
            for (var npc : maker.npcs()) {
                if (npc.position() instanceof Fixed fixed
                        && fixed.x() >= minX
                        && fixed.x() <= maxX
                        && fixed.y() >= minY
                        && fixed.y() <= maxY) {
                    inside = true;
                }
            }
            for (var name : maker.territoryNames()) {
                var zone = zones.get(name);
                if (zone != null
                        && !zone.points().isEmpty()
                        && zone.maxX() >= minX
                        && zone.minX() <= maxX
                        && zone.maxY() >= minY
                        && zone.minY() <= maxY) {
                    inside = true;
                }
            }
            if (inside) {
                result.add(index);
            }
        }
        return result;
    }

    public static Impact findIntroduced(
            State state,
            Collection<Integer> makers,
            NpcDataTable npcs,
            GeoDataAccess before,
            GeoDataAccess after,
            BooleanSupplier cancelled) {
        var known = new HashSet<String>();
        for (var finding : check(state, makers, npcs, before, cancelled)) {
            if (isStandingFinding(finding)) {
                known.add(finding.maker() + ":" + finding.slot());
            }
        }
        var blocked = new ArrayList<Finding>();
        for (var finding : check(state, makers, npcs, after, cancelled)) {
            if (isStandingFinding(finding) && !known.contains(finding.maker() + ":" + finding.slot())) {
                blocked.add(finding);
            }
        }
        var zones = new HashMap<String, SpawnTerritory>();
        for (var zone : state.territories()) {
            if (zone != null && !zone.domain()) {
                zones.putIfAbsent(zone.name(), zone);
            }
        }
        var emptied = new ArrayList<Finding>();
        for (var index : makers) {
            if (cancelled.getAsBoolean()) {
                break;
            }
            var maker = index < 0 || index >= state.makers().size()
                    ? null
                    : state.makers().get(index);
            if (maker == null
                    || maker.flying()
                    || maker.npcs().stream().noneMatch(npc -> npc.position() instanceof Anywhere)) {
                continue;
            }
            var own = new ArrayList<SpawnTerritory>();
            for (var name : maker.territoryNames()) {
                var zone = zones.get(name);
                if (zone != null && zone.points().size() >= 3) {
                    own.add(zone);
                }
            }
            if (own.isEmpty()) {
                continue;
            }
            var banned = new ArrayList<SpawnTerritory>();
            for (var name : maker.bannedTerritoryNames()) {
                var zone = zones.get(name);
                if (zone != null) {
                    banned.add(zone);
                }
            }
            var had = countSpawnPoints(maker, own, banned, before);
            if (had > 0 && countSpawnPoints(maker, own, banned, after) == 0) {
                var zone = own.getFirst();
                emptied.add(new Finding(
                        Kind.FEW_POINTS,
                        maker.index(),
                        -1,
                        zone.index(),
                        "maker " + maker.displayName(),
                        "Zone "
                                + zone.name()
                                + " had "
                                + had
                                + (had == 1 ? " point" : " points")
                                + " to spawn on and has none after the edits",
                        zone.centerX(),
                        zone.centerY(),
                        (zone.bottom() + zone.top()) * .5));
            }
        }
        return new Impact(blocked, emptied);
    }

    private static void checkMaker(
            SpawnMaker maker,
            Map<String, SpawnTerritory> zones,
            NpcDataTable npcs,
            GeoDataAccess geodata,
            GeoQueryEngine engine,
            List<Finding> findings) {
        var where = "maker " + maker.displayName();
        var own = new ArrayList<SpawnTerritory>();
        for (var name : maker.territoryNames()) {
            var zone = zones.get(name);
            if (zone != null && zone.points().size() >= 3) {
                own.add(zone);
            }
        }
        var banned = new ArrayList<SpawnTerritory>();
        for (var name : maker.bannedTerritoryNames()) {
            var zone = zones.get(name);
            if (zone != null) {
                banned.add(zone);
            }
        }
        var known = npcs != null && npcs.size() > 0;
        for (var slot = 0; slot < maker.npcs().size(); slot++) {
            var npc = maker.npcs().get(slot);
            var at = place(npc, own);
            if (known && !npc.npcName().isBlank() && npcs.getNpc(npc.npcName()) == null) {
                findings.add(new Finding(
                        Kind.UNKNOWN_NPC,
                        maker.index(),
                        slot,
                        -1,
                        where,
                        "Entry " + npc.npcName() + " names an NPC npcdata.txt lacks",
                        at[0],
                        at[1],
                        at[2]));
            }
        }
        var regionMissing = false;
        for (var zone : own) {
            if (isMissing(geodata, zone.centerX(), zone.centerY())) {
                regionMissing = true;
                findings.add(new Finding(
                        Kind.NO_GEODATA,
                        maker.index(),
                        -1,
                        zone.index(),
                        where,
                        "Zone "
                                + zone.name()
                                + " lies in region "
                                + regionName(zone.centerX(), zone.centerY())
                                + ", which has no geodata file",
                        zone.centerX(),
                        zone.centerY(),
                        (zone.bottom() + zone.top()) * .5));
                break;
            }
        }
        for (var slot = 0; slot < maker.npcs().size(); slot++) {
            if (maker.npcs().get(slot).position() instanceof Fixed fixed) {
                checkFixed(maker, slot, fixed, where, geodata, engine, findings);
            }
        }
        if (regionMissing || own.isEmpty()) {
            return;
        }
        var floating = false;
        for (var zone : own) {
            var ground = floatingGround(zone, geodata);
            if (!Double.isNaN(ground)) {
                floating = true;
                findings.add(new Finding(
                        Kind.FLOATING_ZONE,
                        maker.index(),
                        -1,
                        zone.index(),
                        where,
                        "Zone "
                                + zone.name()
                                + " starts at "
                                + zone.bottom()
                                + ", above the ground at about "
                                + Math.round(ground),
                        zone.centerX(),
                        zone.centerY(),
                        ground));
            }
        }
        var random = false;
        for (var npc : maker.npcs()) {
            random |= npc.position() instanceof Anywhere;
        }
        if (random && !floating && !maker.flying()) {
            var candidates = SpawnSampler.findCandidates(
                    own, banned, maker.maximumNpc(), false, geodata, new Random(maker.index()), () -> false);
            var points = candidates.usedFallback() ? 0 : candidates.points().size();
            if (!candidates.geodataMissing() && points < MIN_POINTS) {
                var zone = own.getFirst();
                findings.add(new Finding(
                        Kind.FEW_POINTS,
                        maker.index(),
                        -1,
                        zone.index(),
                        where,
                        (points == 0 ? "No point" : points == 1 ? "Only 1 point" : "Only " + points + " points")
                                + " of the server's grid is ground inside the zone with room to move",
                        zone.centerX(),
                        zone.centerY(),
                        (zone.bottom() + zone.top()) * .5));
            }
        }
    }

    private static void checkFixed(
            SpawnMaker maker,
            int slot,
            Fixed fixed,
            String where,
            GeoDataAccess geodata,
            GeoQueryEngine engine,
            List<Finding> findings) {
        var cell = geodata.cellAt(fixed.x(), fixed.y());
        if (cell.availability() != Availability.AVAILABLE) {
            return;
        }
        var name = maker.npcs().get(slot).npcName();
        if (cell.layers().isEmpty()) {
            findings.add(new Finding(
                    Kind.FEET_BLOCKED,
                    maker.index(),
                    slot,
                    -1,
                    where,
                    name + " stands on a cell without geodata ground",
                    fixed.x(),
                    fixed.y(),
                    fixed.z()));
            return;
        }
        var nearest = cell.layers().getFirst();
        for (var layer : cell.layers()) {
            if (Math.abs(layer.height() - fixed.z()) < Math.abs(nearest.height() - fixed.z())) {
                nearest = layer;
            }
        }
        var floor = nearest.height();
        if (fixed.z() - floor > FLOOR_GAP) {
            findings.add(new Finding(
                    Kind.FEET_BLOCKED,
                    maker.index(),
                    slot,
                    -1,
                    where,
                    name + " stands " + (fixed.z() - floor) + " units over the nearest floor, at " + floor,
                    fixed.x(),
                    fixed.y(),
                    fixed.z()));
            return;
        }
        if (floor - fixed.z() > SINK_GAP) {
            findings.add(new Finding(
                    Kind.UNDER_FLOOR,
                    maker.index(),
                    slot,
                    -1,
                    where,
                    name + " stands " + (floor - fixed.z()) + " units under the nearest floor, at " + floor,
                    fixed.x(),
                    fixed.y(),
                    fixed.z()));
            return;
        }
        if (nearest.nswe() == 0) {
            findings.add(new Finding(
                    Kind.FEET_BLOCKED,
                    maker.index(),
                    slot,
                    -1,
                    where,
                    name + " stands on a blocked cell",
                    fixed.x(),
                    fixed.y(),
                    floor));
            return;
        }
        if (!isReachable(engine, geodata, fixed.x(), fixed.y(), floor)) {
            findings.add(new Finding(
                    Kind.UNREACHABLE,
                    maker.index(),
                    slot,
                    -1,
                    where,
                    name + " cannot be reached from any walkable cell within " + REACH + " units",
                    fixed.x(),
                    fixed.y(),
                    floor));
        }
    }

    private static boolean isMissing(GeoDataAccess geodata, double x, double y) {
        return geodata.cellAt((int) Math.floor(x), (int) Math.floor(y)).availability() == Availability.MISSING;
    }

    private static String regionName(double x, double y) {
        return ((int) Math.floor(x / 32768) + 20) + "_" + ((int) Math.floor(y / 32768) + 18);
    }

    private static double[] place(SpawnNpc npc, List<SpawnTerritory> zones) {
        if (npc.position() instanceof Fixed fixed) {
            return new double[] {fixed.x(), fixed.y(), fixed.z()};
        }
        if (!zones.isEmpty()) {
            var zone = zones.getFirst();
            return new double[] {zone.centerX(), zone.centerY(), (zone.bottom() + zone.top()) * .5};
        }
        return new double[] {0, 0, 0};
    }

    public record Impact(List<Finding> blocked, List<Finding> emptied) {
        public Impact {
            blocked = List.copyOf(blocked);
            emptied = List.copyOf(emptied);
        }
    }

    private static boolean isStandingFinding(Finding finding) {
        return finding.slot() >= 0
                && (finding.kind() == Kind.FEET_BLOCKED
                        || finding.kind() == Kind.UNDER_FLOOR
                        || finding.kind() == Kind.UNREACHABLE);
    }

    private static int countSpawnPoints(
            SpawnMaker maker, List<SpawnTerritory> own, List<SpawnTerritory> banned, GeoDataAccess geodata) {
        var candidates = SpawnSampler.findCandidates(
                own, banned, maker.maximumNpc(), false, geodata, new Random(maker.index()), () -> false);
        return candidates.usedFallback() || candidates.geodataMissing()
                ? 0
                : candidates.points().size();
    }
}
