package com.rost.cruma.core.spawn;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.spawn.NpcPosDocument.State;
import com.rost.cruma.core.spawn.SpawnArea.Hit;
import com.rost.cruma.core.spawn.SpawnChecks.Issue;
import com.rost.cruma.core.spawn.SpawnChecks.Severity;
import com.rost.cruma.core.spawn.SpawnHealth.Finding;
import com.rost.cruma.core.spawn.SpawnHealth.Kind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class SpawnAreaCheck {
    private static final List<Kind> WORST_FIRST = List.of(
            Kind.UNKNOWN_NPC,
            Kind.FEET_BLOCKED,
            Kind.UNDER_FLOOR,
            Kind.UNREACHABLE,
            Kind.FEW_POINTS,
            Kind.FLOATING_ZONE,
            Kind.NO_GEODATA);

    private SpawnAreaCheck() {}

    public record Item(Severity severity, String title, String where, String detail, double x, double y, double z) {}

    public record Report(
            int makers,
            int zones,
            boolean geodataChecked,
            Map<Kind, Integer> health,
            int errors,
            int warnings,
            List<Item> items) {}

    public static Report of(
            State state,
            SpawnArea area,
            NpcDataTable npcs,
            @Nullable GeoDataAccess geodata,
            BooleanSupplier cancelled) {
        var makers = new ArrayList<Integer>();
        for (var hit : area.makers(state)) {
            makers.add(hit.index());
        }
        var zones = area.zones(state);
        var findings =
                geodata == null ? List.<Finding>of() : SpawnHealth.check(state, makers, npcs, geodata, cancelled);
        var health = new EnumMap<Kind, Integer>(Kind.class);
        for (var finding : findings) {
            health.merge(finding.kind(), 1, Integer::sum);
        }
        var items = new ArrayList<Item>();
        addFindings(area, findings, items);
        addIssues(state, npcs, makers, zones, findings, items);
        var errors = (int)
                items.stream().filter(item -> item.severity() == Severity.ERROR).count();
        return new Report(makers.size(), zones.size(), geodata != null, health, errors, items.size() - errors, items);
    }

    public static Map<String, Integer> titled(Map<Kind, Integer> health) {
        var titled = new LinkedHashMap<String, Integer>();
        for (var kind : WORST_FIRST) {
            var count = health.get(kind);
            if (count != null) {
                titled.put(kind.title, count);
            }
        }
        return titled;
    }

    private static void addFindings(SpawnArea area, List<Finding> findings, List<Item> items) {
        var sorted = new ArrayList<>(findings);
        sorted.sort(Comparator.<Finding>comparingInt(finding -> WORST_FIRST.indexOf(finding.kind()))
                .thenComparingDouble(finding -> Math.hypot(finding.x() - area.x(), finding.y() - area.y())));
        var regions = new HashSet<String>();
        for (var finding : sorted) {
            if (finding.kind() == Kind.NO_GEODATA && !regions.add(region(finding))) {
                continue;
            }
            items.add(new Item(
                    finding.isError() ? Severity.ERROR : Severity.WARNING,
                    finding.kind().title,
                    finding.where(),
                    finding.detail(),
                    finding.x(),
                    finding.y(),
                    finding.z()));
        }
    }

    private static String region(Finding finding) {
        return Math.floorDiv((long) finding.x(), 32768L) + "_" + Math.floorDiv((long) finding.y(), 32768L);
    }

    private static void addIssues(
            State state,
            NpcDataTable npcs,
            List<Integer> makers,
            List<Hit> zones,
            List<Finding> findings,
            List<Item> items) {
        var told = new HashSet<String>();
        for (var finding : findings) {
            if (finding.kind() == Kind.UNKNOWN_NPC) {
                told.add("maker|" + finding.maker() + "|npc|"
                        + state.makers()
                                .get(finding.maker())
                                .npcs()
                                .get(finding.slot())
                                .npcName());
            }
        }
        var checker = SpawnChecks.checker(state, npcs);
        var errors = new ArrayList<Item>();
        var warnings = new ArrayList<Item>();
        for (var hit : zones) {
            var zone = state.territories().get(hit.index());
            for (var issue : checker.checkZone(zone)) {
                add(issue, zone.centerX(), zone.centerY(), (zone.bottom() + zone.top()) * .5, told, errors, warnings);
            }
        }
        for (var index : makers) {
            var maker = state.makers().get(index);
            var middle = SpawnCopies.computeMiddle(SpawnCopies.findZones(state, maker), maker);
            var x = middle == null ? 0 : middle[0];
            var y = middle == null ? 0 : middle[1];
            var z = middle == null ? 0 : middle[2];
            for (var issue : checker.checkMaker(maker)) {
                add(issue, x, y, z, told, errors, warnings);
            }
        }
        items.addAll(0, errors);
        items.addAll(warnings);
    }

    private static void add(
            Issue issue, double x, double y, double z, HashSet<String> told, List<Item> errors, List<Item> warnings) {
        if (!told.add(issue.key())) {
            return;
        }
        var title = issue.isError() ? "Stops a save" : "Worth a look";
        var item = new Item(issue.severity(), title, issue.where(), issue.message(), x, y, z);
        (issue.isError() ? errors : warnings).add(item);
    }
}
