package com.rost.cruma.core.npc;

import com.rost.cruma.core.npc.DropList.Group;
import com.rost.cruma.core.npc.DropList.Item;
import com.rost.cruma.core.npc.NpcDataDocument.State;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class NpcBatchEdits {
    private NpcBatchEdits() {}

    public static State scaleDrop(NpcDataDocument document, State from, Collection<Integer> records, double factor) {
        var state = from;
        for (var record : records) {
            var value = document.getValue(state, record, DropList.DROP);
            if (value == null) {
                continue;
            }
            try {
                var groups = new ArrayList<Group>();
                for (var group : DropList.parseGroups(value)) {
                    groups.add(group.withChance(scaleChance(group.chance(), factor)));
                }
                state = document.with(state, record, DropList.DROP, DropList.formatGroups(groups));
            } catch (IllegalArgumentException unreadable) {
            }
        }
        return state;
    }

    public static State scaleSpoil(NpcDataDocument document, State from, Collection<Integer> records, double factor) {
        var state = from;
        for (var record : records) {
            var value = document.getValue(state, record, DropList.SPOIL);
            if (value == null) {
                continue;
            }
            try {
                var items = new ArrayList<Item>();
                for (var item : DropList.parseItems(value)) {
                    items.add(item.withChance(scaleChance(item.chance(), factor)));
                }
                state = document.with(state, record, DropList.SPOIL, DropList.formatItems(items));
            } catch (IllegalArgumentException unreadable) {
            }
        }
        return state;
    }

    public static State scaleNumber(
            NpcDataDocument document,
            State from,
            Collection<Integer> records,
            String key,
            double factor,
            boolean keepWhole) {
        var state = from;
        for (var record : records) {
            var value = document.getValue(state, record, key);
            if (value == null) {
                continue;
            }
            try {
                var number = new BigDecimal(value.strip());
                var result = number.multiply(BigDecimal.valueOf(factor));
                var whole = keepWhole && number.stripTrailingZeros().scale() <= 0;
                result = whole ? result.setScale(0, RoundingMode.HALF_UP) : result.setScale(6, RoundingMode.HALF_UP);
                state = document.with(state, record, key, toPlainText(result));
            } catch (NumberFormatException notNumber) {
            }
        }
        return state;
    }

    public static State setValue(
            NpcDataDocument document, State from, Collection<Integer> records, String key, String value) {
        var state = from;
        for (var record : records) {
            if (document.record(state, record).has(key)) {
                state = document.with(state, record, key, value);
            }
        }
        return state;
    }

    public static State addSkill(NpcDataDocument document, State from, Collection<Integer> records, String skill) {
        var state = from;
        for (var record : records) {
            var value = document.getValue(state, record, NpcValues.SKILLS);
            if (value == null) {
                continue;
            }
            try {
                var skills = new ArrayList<>(NpcValues.parseSkills(value));
                if (!skills.contains(skill)) {
                    skills.add(skill);
                    state = document.with(state, record, NpcValues.SKILLS, NpcValues.formatSkills(skills));
                }
            } catch (IllegalArgumentException unreadable) {
            }
        }
        return state;
    }

    public static State removeSkill(NpcDataDocument document, State from, Collection<Integer> records, String skill) {
        var state = from;
        for (var record : records) {
            var value = document.getValue(state, record, NpcValues.SKILLS);
            if (value == null) {
                continue;
            }
            try {
                var skills = new ArrayList<>(NpcValues.parseSkills(value));
                if (skills.removeIf(skill::equals)) {
                    state = document.with(state, record, NpcValues.SKILLS, NpcValues.formatSkills(skills));
                }
            } catch (IllegalArgumentException unreadable) {
            }
        }
        return state;
    }

    public static Set<Integer> findChangedRecords(State before, State after) {
        var records = new TreeSet<Integer>();
        records.addAll(before.edits().keySet());
        records.addAll(after.edits().keySet());
        records.removeIf(record ->
                Objects.equals(before.edits().get(record), after.edits().get(record)));
        return records;
    }

    static double scaleChance(double chance, double factor) {
        var value = DropList.round(Math.min(100, chance * factor));
        return chance > 0 && value < DropList.MINIMUM ? DropList.MINIMUM : value;
    }

    public static String formatFactor(double factor) {
        return "×" + ScriptValue.number(Math.round(factor * 10_000) / 10_000.0);
    }

    private static String toPlainText(BigDecimal value) {
        var stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }
}
