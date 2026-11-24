package com.rost.cruma.core.npc;

import com.rost.cruma.core.npc.DropList.Item;
import com.rost.cruma.core.npc.NpcDataDocument.State;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class NpcDataIndex {
    public static final List<String> LISTS =
            List.of(DropList.DROP, DropList.SPOIL, DropList.EXTRA, DropList.HERBS, DropList.MAKE);

    private final NpcDataDocument document;
    private final Facts[] base;
    private final Map<String, List<ItemUse>> items = new HashMap<>();
    private final Map<String, List<SkillUse>> skills = new HashMap<>();
    private final Map<String, List<Integer>> lists = new HashMap<>();
    private State overlaid = State.NONE;
    private Map<Integer, Facts> overlay = Map.of();

    private NpcDataIndex(NpcDataDocument document) {
        this.document = document;
        var file = document.getFile();
        base = new Facts[file.size()];
        for (var i = 0; i < file.size(); i++) {
            var facts = collectFacts(State.NONE, i);
            base[i] = facts;
            for (var use : facts.items()) {
                items.computeIfAbsent(use.item().name(), unused -> new ArrayList<>())
                        .add(use);
            }
            for (var use : facts.skills()) {
                skills.computeIfAbsent(use.skill(), unused -> new ArrayList<>()).add(use);
            }
            var at = i;
            facts.lists()
                    .forEach((key, text) -> lists.computeIfAbsent(listKey(key, text), unused -> new ArrayList<>())
                            .add(at));
        }
    }

    public record ItemUse(int record, String key, int group, Item item, double perKill) {}

    public record SkillUse(int record, String skill, String setting) {}

    private record Facts(List<ItemUse> items, List<SkillUse> skills, Map<String, String> lists, String ai) {
        static final Facts NONE = new Facts(List.of(), List.of(), Map.of(), "");
    }

    public static List<ItemUse> itemsOf(NpcDataDocument document, State state, int record) {
        return collectFacts(document, state, record).items();
    }

    public static List<SkillUse> skillsOf(NpcDataDocument document, State state, int record) {
        return collectFacts(document, state, record).skills();
    }

    public static String aiOf(NpcDataDocument document, State state, int record) {
        return collectFacts(document, state, record).ai();
    }

    public static NpcDataIndex of(NpcDataDocument document) {
        return new NpcDataIndex(document);
    }

    public NpcDataDocument getDocument() {
        return document;
    }

    public List<ItemUse> findDroppedBy(State state, String item) {
        refresh(state);
        var found = new ArrayList<ItemUse>();
        for (var use : items.getOrDefault(item, List.of())) {
            if (isBaseCurrent(state, use.record())) {
                found.add(use);
            }
        }
        overlay.values().forEach(facts -> {
            for (var use : facts.items()) {
                if (use.item().name().equals(item)) {
                    found.add(use);
                }
            }
        });
        found.sort(Comparator.comparingDouble(ItemUse::perKill).reversed().thenComparingInt(ItemUse::record));
        return found;
    }

    public List<SkillUse> findKnownBy(State state, String skill) {
        refresh(state);
        var found = new ArrayList<SkillUse>();
        for (var use : skills.getOrDefault(skill, List.of())) {
            if (isBaseCurrent(state, use.record())) {
                found.add(use);
            }
        }
        overlay.values().forEach(facts -> {
            for (var use : facts.skills()) {
                if (use.skill().equals(skill)) {
                    found.add(use);
                }
            }
        });
        found.sort(Comparator.comparingInt(SkillUse::record));
        return found;
    }

    public List<Integer> findSameList(State state, String key, String text) {
        refresh(state);
        var found = new ArrayList<Integer>();
        for (var record : lists.getOrDefault(listKey(key, text), List.of())) {
            if (isBaseCurrent(state, record)) {
                found.add(record);
            }
        }
        overlay.forEach((record, facts) -> {
            if (text.equals(facts.lists().get(key))) {
                found.add(record);
            }
        });
        found.sort(null);
        return found;
    }

    public String getAi(State state, int record) {
        refresh(state);
        var facts = overlay.get(record);
        if (facts != null) {
            return facts.ai();
        }
        return record < base.length ? base[record].ai() : "";
    }

    public List<ItemUse> getItems(State state, int record) {
        refresh(state);
        var facts = overlay.get(record);
        return facts != null ? facts.items() : record < base.length ? base[record].items() : List.of();
    }

    public List<SkillUse> getSkills(State state, int record) {
        refresh(state);
        var facts = overlay.get(record);
        return facts != null ? facts.skills() : record < base.length ? base[record].skills() : List.of();
    }

    private boolean isBaseCurrent(State state, int record) {
        return !overlay.containsKey(record) && !state.isRemoved(record);
    }

    private void refresh(State state) {
        if (state == overlaid) {
            return;
        }
        var next = new HashMap<Integer, Facts>();
        state.edits().keySet().forEach(record -> {
            if (!state.isRemoved(record) && record < document.getSize(state)) {
                next.put(record, collectFacts(state, record));
            }
        });
        for (var i = 0; i < state.added().size(); i++) {
            var record = document.getFile().size() + i;
            if (!state.isRemoved(record) && !next.containsKey(record)) {
                next.put(record, collectFacts(state, record));
            }
        }
        overlay = next;
        overlaid = state;
    }

    private Facts collectFacts(State state, int record) {
        return collectFacts(document, state, record);
    }

    private static Facts collectFacts(NpcDataDocument document, State state, int record) {
        var uses = new ArrayList<ItemUse>();
        var lists = new HashMap<String, String>(2);
        for (var key : LISTS) {
            var value = document.getValue(state, record, key);
            if (value == null || value.isBlank() || value.equals("{}")) {
                continue;
            }
            if (key.equals(DropList.DROP) || key.equals(DropList.SPOIL)) {
                lists.put(key, value);
            }
            try {
                if (DropList.isGrouped(key)) {
                    var groups = DropList.parseGroups(value);
                    for (var g = 0; g < groups.size(); g++) {
                        var group = groups.get(g);
                        for (var item : group.items()) {
                            uses.add(new ItemUse(record, key, g, item, group.chance() * item.chance() / 100));
                        }
                    }
                } else {
                    for (var item : DropList.parseItems(value)) {
                        uses.add(new ItemUse(record, key, -1, item, item.chance()));
                    }
                }
            } catch (IllegalArgumentException unreadable) {
            }
        }
        var known = new ArrayList<SkillUse>();
        var skillList = document.getValue(state, record, NpcValues.SKILLS);
        if (skillList != null) {
            try {
                for (var skill : NpcValues.parseSkills(skillList)) {
                    known.add(new SkillUse(record, skill, ""));
                }
            } catch (IllegalArgumentException unreadable) {
            }
        }
        var ai = "";
        var aiValue = document.getValue(state, record, NpcValues.AI);
        if (aiValue != null) {
            try {
                var parsed = NpcValues.parseAi(aiValue);
                ai = parsed.name();
                for (var parameter : parsed.parameters()) {
                    if (!parameter.skill().isEmpty()) {
                        known.add(new SkillUse(record, parameter.skill(), parameter.name()));
                    }
                }
            } catch (IllegalArgumentException unreadable) {
            }
        }
        if (uses.isEmpty() && known.isEmpty() && lists.isEmpty() && ai.isEmpty()) {
            return Facts.NONE;
        }
        return new Facts(List.copyOf(uses), List.copyOf(known), Map.copyOf(lists), ai);
    }

    private static String listKey(String key, String text) {
        return key + "\u0000" + text;
    }
}
