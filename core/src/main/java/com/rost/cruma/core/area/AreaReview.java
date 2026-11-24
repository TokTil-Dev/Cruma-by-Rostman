package com.rost.cruma.core.area;

import com.rost.cruma.core.area.AreaChecks.Finding;
import com.rost.cruma.core.area.AreaDocument.State;
import com.rost.cruma.core.spawn.LineDiff;
import com.rost.cruma.core.spawn.SpawnChanges;
import com.rost.cruma.core.spawn.SpawnChanges.Change;
import com.rost.cruma.core.spawn.SpawnChanges.Line;
import com.rost.cruma.core.spawn.SpawnChanges.Section;
import com.rost.cruma.core.spawn.SpawnChanges.Span;
import com.rost.cruma.core.spawn.SpawnChecks.Issue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

public record AreaReview(
        List<Change> changes,
        List<Section> sections,
        int removed,
        int added,
        List<AreaChange> areas,
        List<Finding> findings,
        List<Issue> errors,
        List<Issue> warnings) {
    private static final int SHORT_VALUE = 16;
    private static final int MOST_WHAT = 3;

    public enum ChangeKind {
        ADDED,
        REMOVED,
        CHANGED
    }

    public record AreaChange(int index, String name, ChangeKind kind, List<String> what) {
        public AreaChange {
            what = List.copyOf(what);
        }

        public String summary() {
            return String.join(", ", what);
        }
    }

    public static AreaReview of(AreaDocument document) {
        return of(document.getFile(), document.getSavedState(), document.getState());
    }

    public static AreaReview of(AreaDataFile file, State saved, State now) {
        var before = LineDiff.splitLines(AreaDataWriter.write(file, saved).text());
        var after = LineDiff.splitLines(AreaDataWriter.write(file, now).text());
        var hunks = LineDiff.diff(before, after);
        var removed = hunks.stream().mapToInt(hunk -> hunk.removed().size()).sum();
        var added = hunks.stream().mapToInt(hunk -> hunk.added().size()).sum();
        var changes =
                joinRenames(
                                SpawnChanges.of(before, after, hunks).stream()
                                        .map(AreaReview::nameEdit)
                                        .toList(),
                                saved,
                                now)
                        .stream()
                        .map(change -> nameNote(change, before))
                        .toList();
        var sections = changes.isEmpty() ? List.<Section>of() : List.of(new Section("", null, changes));
        var findings = AreaChecks.findIntroduced(saved, now, AreaDocument.collectTypes(file, saved));
        var errors =
                findings.stream().filter(Finding::isError).map(Finding::toIssue).toList();
        var warnings = findings.stream()
                .filter(finding -> !finding.isError())
                .map(Finding::toIssue)
                .toList();
        return new AreaReview(
                changes, sections, removed, added, describeChanges(saved, now), findings, errors, warnings);
    }

    public static List<AreaChange> describeChanges(State saved, State now) {
        var changes = new ArrayList<AreaChange>();
        var count = Math.max(saved.areas().size(), now.areas().size());
        for (var i = 0; i < count; i++) {
            var old = saved.area(i);
            var area = now.area(i);
            if (old == area || old != null && old.equals(area)) {
                continue;
            }
            if (old == null) {
                changes.add(new AreaChange(i, area.name(), ChangeKind.ADDED, List.of("Added area " + area.name())));
            } else if (area == null) {
                changes.add(new AreaChange(i, old.name(), ChangeKind.REMOVED, List.of("Removed area " + old.name())));
            } else {
                changes.add(new AreaChange(i, area.name(), ChangeKind.CHANGED, describe(old, area)));
            }
        }
        return changes;
    }

    public static List<String> describe(ServerArea before, ServerArea after) {
        var what = new ArrayList<String>();
        if (!before.name().equals(after.name())) {
            what.add("Renamed " + before.name() + " → " + after.name());
        }
        if (before.isWater() || after.isWater()) {
            if (!Objects.equals(before.waterBox(), after.waterBox())) {
                describePoints(before, after, what);
            }
        } else if (!before.points().equals(after.points())) {
            describePoints(before, after, what);
        }
        var keys = new LinkedHashSet<String>();
        before.settings().forEach(setting -> keys.add(setting.key()));
        after.settings().forEach(setting -> keys.add(setting.key()));
        keys.removeAll(List.of("name", "range", "water_range"));
        for (var key : keys) {
            var old = before.values(key);
            var now = after.values(key);
            if (old.equals(now)) {
                continue;
            }
            if (old.isEmpty()) {
                var value = String.join(";", now);
                what.add(value.length() <= SHORT_VALUE ? "Added " + key + " " + value : "Added " + key);
            } else if (now.isEmpty()) {
                what.add("Removed " + key);
            } else if (key.equals("banned_range") || key.equals("teleport_points") || key.equals("restart_points")) {
                describeList(key, before, after, what);
            } else {
                var was = String.join(";", old);
                var is = String.join(";", now);
                what.add(
                        was.length() <= SHORT_VALUE && is.length() <= SHORT_VALUE
                                ? key + " " + was + " → " + is
                                : "Changed " + key);
            }
        }
        if (!before.note().equals(after.note())) {
            what.add(before.note().isEmpty() ? "Added note" : after.note().isEmpty() ? "Removed note" : "Changed note");
        }
        if (what.isEmpty()) {
            what.add("Spacing");
        }
        return what;
    }

    private static void describePoints(ServerArea before, ServerArea after, List<String> what) {
        var old = before.points();
        var now = after.points();
        if (old.size() != now.size()) {
            what.add("Corners " + old.size() + " → " + now.size());
        } else if (!old.isEmpty()) {
            var dx = now.getFirst().x() - old.getFirst().x();
            var dy = now.getFirst().y() - old.getFirst().y();
            var alike = true;
            var moved = 0;
            for (var i = 0; i < old.size(); i++) {
                var a = old.get(i);
                var b = now.get(i);
                if (a.x() != b.x() || a.y() != b.y()) {
                    moved++;
                }
                if (b.x() - a.x() != dx || b.y() - a.y() != dy) {
                    alike = false;
                }
            }
            if (alike && (dx != 0 || dy != 0)) {
                what.add("Moved by " + dx + ", " + dy);
            } else if (moved > 0) {
                what.add("Moved " + moved + (moved == 1 ? " corner" : " corners"));
            }
        }
        if (before.bottom() != after.bottom()) {
            what.add("Bottom " + before.bottom() + " → " + after.bottom());
        }
        if (before.top() != after.top()) {
            what.add("Top " + before.top() + " → " + after.top());
        }
    }

    private static void describeList(String key, ServerArea before, ServerArea after, List<String> what) {
        var old = countItems(key, before);
        var now = countItems(key, after);
        what.add(old == now ? "Changed " + key : key + " " + old + " → " + now);
    }

    private static int countItems(String key, ServerArea area) {
        return switch (key) {
            case "banned_range" -> area.banned().size();
            case "teleport_points" -> area.teleportPoints().size();
            default -> area.restartPoints().size();
        };
    }

    private static List<Change> joinRenames(List<Change> changes, State saved, State now) {
        var renamed = new HashMap<String, String>();
        for (var i = 0; i < Math.min(saved.areas().size(), now.areas().size()); i++) {
            var old = saved.area(i);
            var area = now.area(i);
            if (old != null && area != null && !old.name().equals(area.name())) {
                renamed.put(old.name(), area.name());
            }
        }
        if (renamed.isEmpty()) {
            return changes;
        }
        var result = new ArrayList<>(changes);
        for (var k = 0; k < result.size(); k++) {
            var removal = result.get(k);
            if (removal.lines().size() != 1 || !removal.lines().getFirst().removed()) {
                continue;
            }
            var oldText = textOf(removal.lines().getFirst());
            var old = parseText(oldText);
            var newName = old == null ? null : renamed.get(old.name());
            if (newName == null) {
                continue;
            }
            for (var m = k + 1; m < Math.min(result.size(), k + 3); m++) {
                var addition = result.get(m);
                if (addition.lines().stream().anyMatch(Line::removed)) {
                    continue;
                }
                var at = -1;
                for (var n = 0; n < addition.lines().size() && at < 0; n++) {
                    var parsed = parseText(textOf(addition.lines().get(n)));
                    if (parsed != null && parsed.name().equals(newName)) {
                        at = n;
                    }
                }
                if (at < 0) {
                    continue;
                }
                var spans =
                        SpawnChanges.diffWords(oldText, textOf(addition.lines().get(at)));
                result.set(
                        k,
                        nameEdit(new Change(
                                removal.line(),
                                removal.where(),
                                "",
                                List.of(new Line(true, spans.get(0)), new Line(false, spans.get(1))))));
                var rest = new ArrayList<>(addition.lines());
                rest.remove(at);
                if (rest.isEmpty()) {
                    result.remove(m);
                } else {
                    result.set(m, new Change(addition.line(), addition.where(), "Added " + describeLines(rest), rest));
                }
                break;
            }
        }
        return result;
    }

    private static Change nameNote(Change change, List<String> before) {
        if (change.lines().isEmpty()
                || !change.lines().stream()
                        .allMatch(line -> textOf(line).strip().startsWith("//"))) {
            return change;
        }
        var added = change.isAddition();
        var removed = change.lines().stream().allMatch(Line::removed);
        var below = added ? change.line() : change.line() - 1 + change.lines().size();
        var area = below >= 0 && below < before.size() ? parseText(before.get(below)) : null;
        if (area == null || !added && !removed) {
            return change;
        }
        return new Change(change.line(), "area " + area.name(), added ? "Added note" : "Removed note", change.lines());
    }

    private static String describeLines(List<Line> lines) {
        var first = parseText(textOf(lines.getFirst()));
        var more = lines.size() - 1;
        var name = first == null ? "lines" : "area " + first.name();
        return more == 0 ? name : name + " and " + more + (more == 1 ? " more line" : " more lines");
    }

    private static Change nameEdit(Change change) {
        var lines = change.lines();
        if (lines.size() != 2 || !lines.get(0).removed() || lines.get(1).removed()) {
            return change;
        }
        var before = parseLine(lines.get(0));
        var after = parseLine(lines.get(1));
        if (before == null || after == null) {
            return change;
        }
        var what = describe(before, after);
        var text = String.join(", ", what.subList(0, Math.min(what.size(), MOST_WHAT)))
                + (what.size() > MOST_WHAT ? ", …" : "");
        return new Change(change.line(), "area " + after.name(), text, lines);
    }

    private static @Nullable ServerArea parseLine(Line line) {
        return parseText(textOf(line));
    }

    private static @Nullable ServerArea parseText(String text) {
        if (!text.strip().startsWith("area_begin")) {
            return null;
        }
        var areas = AreaDataFile.parse(text).getAreas();
        return areas.size() == 1 ? areas.getFirst() : null;
    }

    private static String textOf(Line line) {
        return line.spans().stream().map(Span::text).collect(Collectors.joining());
    }
}
