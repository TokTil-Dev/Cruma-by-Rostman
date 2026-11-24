package com.rost.cruma.core.spawn;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.spawn.NpcPosDocument.Batch;
import com.rost.cruma.core.spawn.NpcPosDocument.State;
import com.rost.cruma.core.spawn.SpawnChanges.Change;
import com.rost.cruma.core.spawn.SpawnChanges.Section;
import com.rost.cruma.core.spawn.SpawnChecks.Issue;
import com.rost.cruma.core.spawn.SpawnChecks.Severity;
import com.rost.cruma.core.spawn.SpawnPosition.Fixed;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public record SpawnReview(
        List<Change> changes,
        List<Section> sections,
        int removed,
        int added,
        List<Issue> errors,
        List<Issue> warnings) {
    private static final int MOST_MAKERS_CHECKED = 60;

    @FunctionalInterface
    public interface Geodata {
        @Nullable
        GeoDataAccess readArea(double minX, double minY, double maxX, double maxY);
    }

    public record Input(NpcPosFile file, State saved, State state, List<Batch> batches, NpcDataTable npcs) {
        public static Input of(NpcPosDocument document, NpcDataTable npcs) {
            return new Input(
                    document.getFile(),
                    document.getSavedState(),
                    document.getState(),
                    document.getBatchesSinceSave(),
                    npcs);
        }
    }

    public static SpawnReview of(Input input, @Nullable Geodata geodata) {
        var savedText = NpcPosWriter.write(input.file(), input.saved()).text();
        var before = LineDiff.splitLines(savedText);
        var after = LineDiff.splitLines(
                NpcPosWriter.write(input.file(), input.state()).text());
        var hunks = LineDiff.diff(before, after);
        var removed = hunks.stream().mapToInt(hunk -> hunk.removed().size()).sum();
        var added = hunks.stream().mapToInt(hunk -> hunk.added().size()).sum();
        var issues = SpawnChecks.findIntroduced(input.saved(), input.state(), input.npcs());
        var changes = SpawnChanges.of(before, after, hunks);
        var sections =
                SpawnChanges.groupIntoSections(changes, input.batches(), input.saved(), savedText, input.state());
        var errors = issues.stream().filter(Issue::isError).toList();
        var warnings = new ArrayList<>(
                issues.stream().filter(issue -> !issue.isError()).toList());
        if (geodata != null) {
            warnings.addAll(checkPlaces(input, geodata));
        }
        return new SpawnReview(changes, sections, removed, added, errors, warnings);
    }

    private static List<Issue> checkPlaces(Input input, Geodata geodata) {
        var issues = new ArrayList<Issue>();
        var saved = input.saved();
        var state = input.state();
        var changedZones = changedZones(saved, state);
        var checked = 0;
        for (var i = 0; i < state.makers().size() && checked < MOST_MAKERS_CHECKED; i++) {
            var maker = state.makers().get(i);
            var old = i < saved.makers().size() ? saved.makers().get(i) : null;
            if (maker == null || !placeChanged(old, maker, changedZones)) {
                continue;
            }
            var bounds = makerBounds(state, maker);
            if (bounds == null) {
                continue;
            }
            var data = geodata.readArea(bounds[0] - 256, bounds[1] - 256, bounds[2] + 256, bounds[3] + 256);
            if (data == null) {
                continue;
            }
            checked++;
            for (var finding : SpawnHealth.check(state, List.of(i), input.npcs(), data, () -> false)) {
                if (!finding.isError()) {
                    issues.add(new Issue(
                            Severity.WARNING,
                            "health|" + i + "|" + finding.kind() + "|" + finding.slot(),
                            finding.where(),
                            finding.kind().title + ": " + finding.detail()));
                }
            }
        }
        return issues;
    }

    private static Set<String> changedZones(State saved, State state) {
        var before = firstZones(saved);
        var changed = new HashSet<String>();
        firstZones(state).forEach((name, zone) -> {
            if (!zone.equals(before.remove(name))) {
                changed.add(name);
            }
        });
        changed.addAll(before.keySet());
        return changed;
    }

    private static Map<String, SpawnTerritory> firstZones(State state) {
        var zones = new HashMap<String, SpawnTerritory>();
        for (var zone : state.territories()) {
            if (zone != null && !zone.domain()) {
                zones.putIfAbsent(zone.name(), zone);
            }
        }
        return zones;
    }

    private static boolean placeChanged(@Nullable SpawnMaker old, SpawnMaker maker, Set<String> changedZones) {
        if (old == null
                || !old.territoryNames().equals(maker.territoryNames())
                || old.npcs().size() != maker.npcs().size()) {
            return true;
        }
        for (var slot = 0; slot < maker.npcs().size(); slot++) {
            var before = old.npcs().get(slot);
            var after = maker.npcs().get(slot);
            if (!before.npcName().equals(after.npcName()) || !before.position().equals(after.position())) {
                return true;
            }
        }
        return !Collections.disjoint(maker.territoryNames(), changedZones)
                || !Collections.disjoint(maker.bannedTerritoryNames(), changedZones);
    }

    private static double @Nullable [] makerBounds(State state, SpawnMaker maker) {
        var bounds = new double[] {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (var name : maker.territoryNames()) {
            var zone = state.territory(name);
            if (zone != null) {
                bounds[0] = Math.min(bounds[0], zone.minX());
                bounds[1] = Math.min(bounds[1], zone.minY());
                bounds[2] = Math.max(bounds[2], zone.maxX());
                bounds[3] = Math.max(bounds[3], zone.maxY());
            }
        }
        for (var npc : maker.npcs()) {
            if (npc.position() instanceof Fixed fixed) {
                bounds[0] = Math.min(bounds[0], fixed.x());
                bounds[1] = Math.min(bounds[1], fixed.y());
                bounds[2] = Math.max(bounds[2], fixed.x());
                bounds[3] = Math.max(bounds[3], fixed.y());
            }
        }
        return bounds[0] > bounds[2] ? null : bounds;
    }
}
