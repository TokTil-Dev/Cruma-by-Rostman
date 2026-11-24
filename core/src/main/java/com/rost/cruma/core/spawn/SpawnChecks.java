package com.rost.cruma.core.spawn;

import com.rost.cruma.core.spawn.NpcPosDocument.State;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SpawnChecks {
    private SpawnChecks() {}

    public enum Severity {
        ERROR,
        WARNING
    }

    public record Issue(Severity severity, String key, String where, String message) {
        public boolean isError() {
            return severity == Severity.ERROR;
        }
    }

    public static final class Checker {
        private final Map<String, Integer> zones;
        private final NpcDataTable npcs;

        private Checker(State state, NpcDataTable npcData) {
            zones = zoneNames(state);
            npcs = nonEmptyOrNull(npcData);
        }

        public List<Issue> checkZone(SpawnTerritory zone) {
            var issues = new ArrayList<Issue>();
            SpawnChecks.checkZone(zone, zones, issues);
            return issues;
        }

        public List<Issue> checkMaker(SpawnMaker maker) {
            var issues = new ArrayList<Issue>();
            SpawnChecks.checkMaker(maker, zones, npcs, issues);
            return issues;
        }
    }

    public static Checker checker(State state, NpcDataTable npcData) {
        return new Checker(state, npcData);
    }

    public static List<Issue> of(State state, NpcDataTable npcData) {
        var issues = new ArrayList<Issue>();
        var checker = checker(state, npcData);
        for (var zone : state.territories()) {
            if (zone != null && !zone.domain()) {
                issues.addAll(checker.checkZone(zone));
            }
        }
        for (var maker : state.makers()) {
            if (maker != null) {
                issues.addAll(checker.checkMaker(maker));
            }
        }
        return issues;
    }

    public static List<Issue> findIntroduced(State saved, State now, NpcDataTable npcData) {
        if (saved == now) {
            return List.of();
        }
        var npcs = nonEmptyOrNull(npcData);
        var savedZones = zoneNames(saved);
        var nowZones = zoneNames(now);
        var gone = new HashSet<>(savedZones.keySet());
        gone.removeAll(nowZones.keySet());
        var before = new ArrayList<Issue>();
        var after = new ArrayList<Issue>();
        for (var i = 0; i < now.territories().size(); i++) {
            var zone = now.territories().get(i);
            var old = i < saved.territories().size() ? saved.territories().get(i) : null;
            if (zone != old) {
                if (old != null && !old.domain()) {
                    checkZone(old, savedZones, before);
                }
                if (zone != null && !zone.domain()) {
                    checkZone(zone, nowZones, after);
                }
            }
        }
        for (var i = 0; i < now.makers().size(); i++) {
            var maker = now.makers().get(i);
            var old = i < saved.makers().size() ? saved.makers().get(i) : null;
            if (maker != old || maker != null && namesAnyZone(maker, gone)) {
                if (old != null) {
                    checkMaker(old, savedZones, npcs, before);
                }
                if (maker != null) {
                    checkMaker(maker, nowZones, npcs, after);
                }
            }
        }
        var old = new HashSet<String>();
        for (var issue : before) {
            old.add(issue.key());
        }
        var fresh = new ArrayList<Issue>();
        for (var issue : after) {
            if (!old.contains(issue.key())) {
                fresh.add(issue);
            }
        }
        return fresh;
    }

    static boolean hasCrossingSides(List<TerritoryPoint> points) {
        return Polygons.hasCrossingSides(points);
    }

    private static NpcDataTable nonEmptyOrNull(NpcDataTable npcData) {
        return npcData != null && npcData.size() > 0 ? npcData : null;
    }

    private static Map<String, Integer> zoneNames(State state) {
        var zones = new HashMap<String, Integer>();
        for (var zone : state.territories()) {
            if (zone != null) {
                zones.merge(zone.name(), 1, Integer::sum);
            }
        }
        return zones;
    }

    private static boolean namesAnyZone(SpawnMaker maker, Set<String> zones) {
        if (zones.isEmpty()) {
            return false;
        }
        for (var name : maker.territoryNames()) {
            if (zones.contains(name)) {
                return true;
            }
        }
        for (var name : maker.bannedTerritoryNames()) {
            if (zones.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static void checkZone(SpawnTerritory zone, Map<String, Integer> zones, List<Issue> issues) {
        var where = "zone " + zone.name();
        if (zones.getOrDefault(zone.name(), 0) > 1) {
            issues.add(new Issue(Severity.ERROR, "zone-name|" + zone.name(), where, "Another zone has the same name"));
        }
        var points = zone.points();
        if (points.size() < 3) {
            issues.add(new Issue(
                    Severity.ERROR,
                    "zone-corners|" + zone.name(),
                    where,
                    "Has " + points.size() + (points.size() == 1 ? " corner" : " corners") + "; a zone needs 3"));
        }
        for (var point : points) {
            if (point.bottom() > point.top()) {
                issues.add(new Issue(
                        Severity.ERROR,
                        "zone-heights|" + zone.name(),
                        where,
                        "A corner's bottom " + point.bottom() + " is above its top " + point.top()));
                break;
            }
        }
        if (hasCrossingSides(points)) {
            issues.add(new Issue(
                    Severity.WARNING,
                    "zone-crossing|" + zone.name(),
                    where,
                    "Its sides cross, so its area is not what the outline suggests"));
        }
    }

    private static void checkMaker(
            SpawnMaker maker, Map<String, Integer> zones, NpcDataTable npcData, List<Issue> issues) {
        var id = "maker|" + maker.index();
        var where = "maker " + maker.displayName();
        if (maker.npcs().isEmpty()) {
            issues.add(new Issue(Severity.ERROR, id + "|empty", where, "Has no NPCs; the server cannot read it"));
        }
        if (maker.territoryNames().isEmpty()) {
            issues.add(new Issue(Severity.ERROR, id + "|no-zone", where, "Names no zone to spawn in"));
        }
        for (var name : maker.territoryNames()) {
            if (!zones.containsKey(name)) {
                issues.add(new Issue(
                        Severity.ERROR,
                        id + "|zone|" + name,
                        where,
                        "Names zone " + name + ", which the file does not have"));
            }
        }
        for (var name : maker.bannedTerritoryNames()) {
            if (!zones.containsKey(name)) {
                issues.add(new Issue(
                        Severity.ERROR,
                        id + "|banned|" + name,
                        where,
                        "Excludes zone " + name + ", which the file does not have"));
            }
        }
        var total = 0;
        for (var npc : maker.npcs()) {
            total += npc.total();
            if (npc.npcName().isBlank()) {
                issues.add(
                        new Issue(Severity.ERROR, id + "|npc-blank|" + npc.index(), where, "An NPC entry has no name"));
            } else if (npcData != null && npcData.getNpc(npc.npcName()) == null) {
                issues.add(new Issue(
                        Severity.ERROR,
                        id + "|npc|" + npc.npcName(),
                        where,
                        "NPC " + npc.npcName() + " is not in npcdata.txt"));
            }
        }
        if (!maker.npcs().isEmpty() && total > maker.maximumNpc()) {
            issues.add(new Issue(
                    Severity.WARNING,
                    id + "|over",
                    where,
                    "Its NPCs add up to "
                            + total
                            + ", more than maximum_npc "
                            + maker.maximumNpc()
                            + ": only "
                            + maker.maximumNpc()
                            + " live at once"));
        }
    }
}
