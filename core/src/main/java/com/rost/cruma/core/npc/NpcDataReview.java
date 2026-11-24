package com.rost.cruma.core.npc;

import com.rost.cruma.core.npc.DropList.Group;
import com.rost.cruma.core.npc.DropList.Item;
import com.rost.cruma.core.npc.NpcDataDocument.Batch;
import com.rost.cruma.core.npc.NpcDataDocument.State;
import com.rost.cruma.core.spawn.LineDiff;
import com.rost.cruma.core.spawn.SpawnChanges;
import com.rost.cruma.core.spawn.SpawnChanges.Line;
import com.rost.cruma.core.spawn.SpawnChanges.Span;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

public final class NpcDataReview {
    public static final String ADDED = "(new NPC)";

    public static final String REMOVED = "(deleted NPC)";

    private static final List<String> RACE_PREFIXES = List.of("s_npc_prop_race_", "s_race_");

    private static final String WEAPON_PREFIX = "s_npc_prop_weapon_type_";

    private NpcDataReview() {}

    public record Change(
            int record,
            String npc,
            int line,
            String key,
            String before,
            String after,
            List<String> details,
            List<Line> lines) {}

    public record Issue(int record, String npc, String key, String message, boolean error) {}

    public record Section(String title, @Nullable Batch batch, List<Change> changes) {}

    public static List<Change> collectChanges(NpcDataFile file, State state, Function<String, String> itemLabel) {
        return collectChanges(new NpcDataDocument(file), state, itemLabel);
    }

    public static List<Change> collectChanges(
            NpcDataDocument document, State state, Function<String, String> itemLabel) {
        var changes = new ArrayList<Change>();
        var file = document.getFile();
        var records = new TreeSet<Integer>(state.edits().keySet());
        records.addAll(state.removed());
        for (var i = 0; i < state.added().size(); i++) {
            records.add(file.size() + i);
        }
        for (var index : records) {
            var record = document.record(state, index);
            var added = document.isAdded(index);
            if (state.isRemoved(index)) {
                if (!added) {
                    changes.add(new Change(
                            index,
                            record.name(),
                            record.getLine(),
                            REMOVED,
                            record.getText(),
                            "",
                            List.of("Deleted " + record.name() + " (id " + record.getId() + "): its whole line goes"),
                            List.of()));
                }
                continue;
            }
            if (added) {
                changes.add(new Change(
                        index,
                        record.name(),
                        record.getLine(),
                        ADDED,
                        "",
                        record.getText(),
                        List.of("New NPC " + record.name() + " with id " + record.getId() + " at the end of the file"),
                        List.of()));
            }
            var values = state.edits().getOrDefault(index, Map.of());
            values.forEach((key, after) -> {
                var before = Objects.requireNonNullElse(record.getValue(key), "");
                changes.add(new Change(
                        index,
                        record.name(),
                        record.getLine(),
                        key,
                        before,
                        after,
                        describeSetting(key, before, after, itemLabel),
                        diffLines(before, after)));
            });
        }
        return changes;
    }

    public static List<Section> groupIntoSections(List<Change> changes, List<Batch> batches) {
        var groups = new ArrayList<Section>();
        var claimed = new boolean[changes.size()];
        for (var batch : batches) {
            var members = new ArrayList<Change>();
            for (var i = 0; i < changes.size(); i++) {
                var change = changes.get(i);
                if (!claimed[i]
                        && batch.records().contains(change.record())
                        && batch.keys().contains(change.key())) {
                    claimed[i] = true;
                    members.add(change);
                }
            }
            if (!members.isEmpty()) {
                var npcs = members.stream().mapToInt(Change::record).distinct().count();
                groups.add(new Section(npcs + (npcs == 1 ? " NPC: " : " NPCs: ") + batch.label(), batch, members));
            }
        }
        var lastRecord = -1;
        List<Change> current = null;
        for (var i = 0; i < changes.size(); i++) {
            if (claimed[i]) {
                continue;
            }
            var change = changes.get(i);
            if (change.record() != lastRecord) {
                current = new ArrayList<>();
                groups.add(new Section("", null, current));
                lastRecord = change.record();
            }
            current.add(change);
        }
        return groups;
    }

    static List<Line> diffLines(String before, String after) {
        var old = split(before);
        var now = split(after);
        var lines = new ArrayList<Line>();
        for (var hunk : LineDiff.diff(old, now)) {
            var paired = Math.min(hunk.removed().size(), hunk.added().size());
            var removed = new ArrayList<Line>();
            var added = new ArrayList<Line>();
            for (var i = 0; i < paired; i++) {
                var words = SpawnChanges.diffWords(
                        hunk.removed().get(i), hunk.added().get(i));
                removed.add(new Line(true, words.get(0)));
                added.add(new Line(false, words.get(1)));
            }
            for (var i = paired; i < hunk.removed().size(); i++) {
                removed.add(new Line(true, List.of(new Span(hunk.removed().get(i), true))));
            }
            for (var i = paired; i < hunk.added().size(); i++) {
                added.add(new Line(false, List.of(new Span(hunk.added().get(i), true))));
            }
            lines.addAll(removed);
            lines.addAll(added);
        }
        return lines;
    }

    static List<String> split(String value) {
        var lines = new ArrayList<String>();
        if (value.isEmpty()) {
            return lines;
        }
        var start = 0;
        for (var i = 0; i < value.length() - 1; i++) {
            var next = value.charAt(i + 1);
            if (value.charAt(i) == ';' && (next == '{' || next == '@')) {
                lines.add(value.substring(start, i + 1));
                start = i + 1;
            }
        }
        lines.add(value.substring(start));
        return lines;
    }

    static List<String> describeSetting(String key, String before, String after, Function<String, String> itemLabel) {
        try {
            if (DropList.isGrouped(key)) {
                return groupDetails(DropList.parseGroups(orEmpty(before)), DropList.parseGroups(after), itemLabel);
            }
            if (key.equals(DropList.SPOIL) || key.equals(DropList.EXTRA) || key.equals(DropList.MAKE)) {
                var lines = new ArrayList<String>();
                addItemDetails(DropList.parseItems(orEmpty(before)), DropList.parseItems(after), "", itemLabel, lines);
                return lines;
            }
            if (key.equals(NpcValues.SKILLS)) {
                var old = new LinkedHashSet<>(NpcValues.parseSkills(orEmpty(before)));
                var now = new LinkedHashSet<>(NpcValues.parseSkills(after));
                var lines = new ArrayList<String>();
                for (var skill : now) {
                    if (!old.contains(skill)) {
                        lines.add("Added skill " + skill);
                    }
                }
                for (var skill : old) {
                    if (!now.contains(skill)) {
                        lines.add("Removed skill " + skill);
                    }
                }
                return lines;
            }
        } catch (IllegalArgumentException unreadable) {
        }
        return List.of(key + " " + shorten(before.isEmpty() ? "(none)" : before) + " → " + shorten(after));
    }

    public static List<Issue> findIssues(NpcDataFile file, State state, ServerItems items, ServerSkills skills) {
        return findIssues(new NpcDataDocument(file), state, items, skills);
    }

    public static List<Issue> findIssues(
            NpcDataDocument document, State state, ServerItems items, ServerSkills skills) {
        var issues = new ArrayList<Issue>();
        var file = document.getFile();
        var experience = state.edits().isEmpty() ? null : NpcExperience.of(file);
        for (var i = 0; i < state.added().size(); i++) {
            var index = file.size() + i;
            if (!state.isRemoved(index)) {
                var record = document.record(state, index);
                issues.add(new Issue(
                        index,
                        record.name(),
                        ADDED,
                        "New id " + record.getId()
                                + ": the game client shows it without a model or name until its npcgrp and "
                                + "NpcName tables have it",
                        false));
            }
        }
        state.edits().forEach((index, values) -> {
            if (state.isRemoved(index)) {
                return;
            }
            var record = document.record(state, index);
            checkConsistency(document, state, record, values.keySet(), skills, issues);
            if (!values.containsKey(NpcExperience.LEVEL) && !values.containsKey(NpcExperience.EXP)) {
                return;
            }
            var level = values.getOrDefault(NpcExperience.LEVEL, record.getValue(NpcExperience.LEVEL));
            var exp = values.getOrDefault(NpcExperience.EXP, record.getValue(NpcExperience.EXP));
            if (level == null || exp == null || !level.strip().matches("\\d+")) {
                return;
            }
            var expected = experience.getExp(Integer.parseInt(level.strip()));
            if (expected == null) {
                issues.add(new Issue(
                        index,
                        record.name(),
                        NpcExperience.EXP,
                        "No other NPC has level " + level.strip() + ", so exp stays " + exp,
                        false));
            } else if (!expected.equals(exp)) {
                issues.add(new Issue(
                        index,
                        record.name(),
                        NpcExperience.EXP,
                        "exp " + exp + " does not follow level " + level.strip() + " (the file uses " + expected + ")",
                        false));
            }
        });
        state.edits().forEach((index, values) -> {
            if (state.isRemoved(index)) {
                return;
            }
            var record = document.record(state, index);
            values.forEach((key, value) -> check(record, key, value, items, skills, issues));
        });
        return issues;
    }

    public static String raceSkill(List<String> skills) {
        for (var skill : skills) {
            for (var prefix : RACE_PREFIXES) {
                if (skill.startsWith(prefix)) {
                    return skill;
                }
            }
        }
        return "";
    }

    public static String raceOf(String skill) {
        for (var prefix : RACE_PREFIXES) {
            if (skill.startsWith(prefix)) {
                return skill.substring(prefix.length());
            }
        }
        return "";
    }

    public static String raceSkillFor(String race, String current, ServerSkills skills) {
        var prefix = RACE_PREFIXES.getFirst();
        for (var candidate : RACE_PREFIXES) {
            if (current.startsWith(candidate)) {
                prefix = candidate;
            }
        }
        var name = prefix + race;
        return skills.byName(name) != null ? name : "";
    }

    public static String weaponOf(String skill) {
        if (!skill.startsWith(WEAPON_PREFIX)) {
            return "";
        }
        var type = skill.substring(WEAPON_PREFIX.length()).replaceAll("\\d+$", "");
        return type.startsWith("2h") ? type.substring(2) : type;
    }

    public static String formatPercent(double value) {
        return ScriptValue.number(DropList.round(value)) + "%";
    }

    public static String formatAmount(Item item) {
        return item.min() == item.max() ? Long.toString(item.min()) : item.min() + "–" + item.max();
    }

    private static List<String> groupDetails(List<Group> old, List<Group> now, Function<String, String> itemLabel) {
        var lines = new ArrayList<String>();
        var shared = Math.min(old.size(), now.size());
        for (var i = 0; i < shared; i++) {
            var a = old.get(i);
            var b = now.get(i);
            var where = " in group " + (i + 1);
            if (a.chance() != b.chance()) {
                lines.add("Group "
                        + (i + 1)
                        + " chance "
                        + formatPercent(a.chance())
                        + " → "
                        + formatPercent(b.chance()));
            }
            addItemDetails(a.items(), b.items(), where, itemLabel, lines);
        }
        for (var i = shared; i < now.size(); i++) {
            lines.add("Added group "
                    + (i + 1)
                    + " with "
                    + count(now.get(i).items().size(), "item")
                    + ", "
                    + formatPercent(now.get(i).chance()));
        }
        for (var i = shared; i < old.size(); i++) {
            lines.add("Removed group " + (i + 1) + " with "
                    + count(old.get(i).items().size(), "item"));
        }
        return lines;
    }

    private static void addItemDetails(
            List<Item> old, List<Item> now, String where, Function<String, String> itemLabel, List<String> lines) {
        var matched = new boolean[old.size()];
        for (var item : now) {
            var at = -1;
            for (var i = 0; i < old.size(); i++) {
                if (!matched[i] && old.get(i).name().equals(item.name())) {
                    at = i;
                    break;
                }
            }
            var label = itemLabel.apply(item.name());
            if (at < 0) {
                lines.add("Added " + label + " " + formatAmount(item) + ", " + formatPercent(item.chance()) + where);
                continue;
            }
            matched[at] = true;
            var was = old.get(at);
            if (was.min() != item.min() || was.max() != item.max()) {
                lines.add(label + where + " count " + formatAmount(was) + " → " + formatAmount(item));
            }
            if (was.chance() != item.chance()) {
                lines.add(label
                        + where
                        + " chance "
                        + formatPercent(was.chance())
                        + " → "
                        + formatPercent(item.chance()));
            }
        }
        for (var i = 0; i < old.size(); i++) {
            if (!matched[i]) {
                lines.add("Removed " + itemLabel.apply(old.get(i).name()) + where);
            }
        }
    }

    private static void checkConsistency(
            NpcDataDocument document,
            State state,
            NpcRecord record,
            Set<String> edited,
            ServerSkills skills,
            List<Issue> issues) {
        var index = record.getIndex();
        var skillValue = document.getValue(state, index, NpcValues.SKILLS);
        List<String> list;
        try {
            list = skillValue == null ? List.of() : NpcValues.parseSkills(skillValue);
        } catch (IllegalArgumentException unreadable) {
            return;
        }
        var skillsEdited = edited.contains(NpcValues.SKILLS);
        var race = document.getValue(state, index, "race");
        if (race != null && (skillsEdited || edited.contains("race"))) {
            var skill = raceSkill(list);
            var given = raceOf(skill);
            var fitting = raceSkillFor(race.strip(), skill, skills);
            if (!skill.isEmpty() && !given.equals(race.strip()) && (skills.size() == 0 || !fitting.isEmpty())) {
                issues.add(new Issue(
                        index,
                        record.name(),
                        "race",
                        "race is " + race.strip() + " but its race skill " + skill + " gives " + given,
                        false));
            }
        }
        var attack = document.getValue(state, index, "base_attack_type");
        if (attack != null && (skillsEdited || edited.contains("base_attack_type"))) {
            for (var skill : list) {
                var weapon = weaponOf(skill);
                if (!weapon.isEmpty() && !weapon.equals(attack.strip())) {
                    issues.add(new Issue(
                            index,
                            record.name(),
                            "base_attack_type",
                            "base_attack_type is " + attack.strip() + " but " + skill + " gives " + weapon,
                            false));
                }
            }
        }
        if (skillsEdited) {
            var seen = new HashSet<String>();
            var twice = new HashSet<String>();
            for (var skill : list) {
                if (!seen.add(skill) && twice.add(skill)) {
                    issues.add(new Issue(
                            index, record.name(), NpcValues.SKILLS, skill + " is in the skill list twice", false));
                }
            }
        }
    }

    private static void check(
            NpcRecord record, String key, String value, ServerItems items, ServerSkills skills, List<Issue> issues) {
        var npc = record.name();
        try {
            if (DropList.isGrouped(key)) {
                var groups = DropList.parseGroups(value);
                for (var g = 0; g < groups.size(); g++) {
                    var group = groups.get(g);
                    var where = " in group " + (g + 1);
                    if (group.items().isEmpty()) {
                        issues.add(new Issue(record.getIndex(), npc, key, "Group " + (g + 1) + " has no items", true));
                    }
                    if (group.chance() <= 0 || group.chance() > 100) {
                        issues.add(new Issue(
                                record.getIndex(),
                                npc,
                                key,
                                "Group "
                                        + (g + 1)
                                        + " chance "
                                        + formatPercent(group.chance())
                                        + " is not between 0 and 100",
                                true));
                    }
                    var sum = group.itemChanceSum();
                    if (!group.items().isEmpty() && Math.abs(sum - 100) > 0.01) {
                        issues.add(new Issue(
                                record.getIndex(),
                                npc,
                                key,
                                "Items" + where + " add up to " + formatPercent(sum) + ", not 100%",
                                false));
                    }
                    checkItems(record, key, group.items(), where, items, issues);
                }
            } else if (key.equals(DropList.SPOIL) || key.equals(DropList.EXTRA) || key.equals(DropList.MAKE)) {
                checkItems(record, key, DropList.parseItems(value), "", items, issues);
            } else if (key.equals(NpcValues.SKILLS)) {
                for (var skill : NpcValues.parseSkills(value)) {
                    if (skills.size() > 0 && skills.byName(skill) == null) {
                        issues.add(new Issue(record.getIndex(), npc, key, "Unknown skill " + skill, true));
                    }
                }
            } else if (key.startsWith("slot_")) {
                var item = NpcValues.parseItem(value);
                if (!item.isEmpty() && items.size() > 0 && items.byName(item) == null) {
                    issues.add(new Issue(record.getIndex(), npc, key, "Unknown item " + item, true));
                }
            } else if (key.equals(NpcValues.AI)) {
                NpcValues.parseAi(value);
            } else if (value.isBlank()) {
                issues.add(new Issue(record.getIndex(), npc, key, key + " is empty", true));
            } else if (value.startsWith("{") || value.startsWith("[")) {
                ScriptValue.parse(value);
            }
        } catch (IllegalArgumentException unreadable) {
            issues.add(
                    new Issue(record.getIndex(), npc, key, key + " cannot be read: " + unreadable.getMessage(), true));
        }
    }

    private static void checkItems(
            NpcRecord record, String key, List<Item> list, String where, ServerItems items, List<Issue> issues) {
        var seen = new HashSet<String>();
        var twice = new HashSet<String>();
        for (var item : list) {
            if (!seen.add(item.name()) && twice.add(item.name())) {
                issues.add(new Issue(
                        record.getIndex(), record.name(), key, item.name() + where + " is in the list twice", false));
            }
            if (item.chance() == 0) {
                issues.add(new Issue(
                        record.getIndex(),
                        record.name(),
                        key,
                        item.name() + where + " has chance 0, so it never drops",
                        false));
                continue;
            }
            if (items.size() > 0 && items.byName(item.name()) == null) {
                issues.add(
                        new Issue(record.getIndex(), record.name(), key, "Unknown item " + item.name() + where, true));
            }
            if (item.min() < 1 || item.max() < item.min()) {
                issues.add(new Issue(
                        record.getIndex(),
                        record.name(),
                        key,
                        item.name() + where + " count " + formatAmount(item) + " is not a range from 1 up",
                        true));
            }
            if (item.chance() < 0 || item.chance() > 100) {
                issues.add(new Issue(
                        record.getIndex(),
                        record.name(),
                        key,
                        item.name() + where + " chance " + formatPercent(item.chance()) + " is not between 0 and 100",
                        true));
            }
        }
    }

    private static String count(int value, String noun) {
        return value + " " + noun + (value == 1 ? "" : "s");
    }

    private static String orEmpty(@Nullable String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }

    private static String shorten(String value) {
        return value.length() > 60 ? value.substring(0, 59) + "…" : value;
    }
}
