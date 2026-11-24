package com.rost.cruma.viewer.agent.spawn;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.npc.DropList;
import com.rost.cruma.core.npc.DropList.Group;
import com.rost.cruma.core.npc.DropList.Item;
import com.rost.cruma.core.npc.NpcAiCatalog;
import com.rost.cruma.core.npc.NpcAiCatalog.ValueKind;
import com.rost.cruma.core.npc.NpcBatchEdits;
import com.rost.cruma.core.npc.NpcDataDocument;
import com.rost.cruma.core.npc.NpcDataDocument.Batch;
import com.rost.cruma.core.npc.NpcDataDocument.State;
import com.rost.cruma.core.npc.NpcDataIndex;
import com.rost.cruma.core.npc.NpcDataReview;
import com.rost.cruma.core.npc.NpcDataReview.Issue;
import com.rost.cruma.core.npc.NpcExperience;
import com.rost.cruma.core.npc.NpcRecord;
import com.rost.cruma.core.npc.NpcValues;
import com.rost.cruma.core.npc.NpcValues.Ai;
import com.rost.cruma.core.npc.NpcValues.AiParameter;
import com.rost.cruma.core.spawn.NpcPosDocument;
import com.rost.cruma.viewer.agent.AgentSteps;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.npc.NpcDataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.Nullable;

final class NpcEditor {
    static final String[] OPS = {
        "set",
        "reset",
        "duplicate",
        "delete",
        "item_add",
        "item_change",
        "item_remove",
        "group_add",
        "group_change",
        "group_remove",
        "group_even",
        "share",
        "skill_add",
        "skill_remove",
        "ai",
        "ai_set",
        "batch",
        "undo",
        "redo"
    };

    static final String[] LISTS = {"drop", "spoil", "herbs", "extra", "make"};

    private static final int SHOWN_PROBLEMS = 10;
    private static final int SHOWN_NAMES = 5;
    private static final double NEW_GROUP_CHANCE = 10;
    private static final double NEW_ITEM_CHANCE = 10;

    private static @Nullable NpcDataIndex index;

    private final ToolArgs args;
    private final NpcDataSource data;
    private final NpcDataDocument document;
    private final NpcPosDocument.@Nullable State spawns;

    NpcEditor(ToolArgs args, NpcDataSource data, NpcPosDocument.@Nullable State spawns) {
        this.args = args;
        this.data = data;
        this.document = Objects.requireNonNull(data.getDocument(), "document");
        this.spawns = spawns;
    }

    private record Plan(
            String label,
            UnaryOperator<State> edit,
            @Nullable Batch batch,
            JSONObject said,
            Set<Integer> records) {}

    static synchronized NpcDataIndex index(NpcDataDocument document) {
        if (index == null || index.getDocument() != document) {
            index = NpcDataIndex.of(document);
        }
        return index;
    }

    static JSONObject describe(int g, Group group) {
        var item = new JSONObject();
        item.put("group", g);
        item.put("chance", group.chance());
        item.put("items", describeItems(group.items()));
        item.put("sum", DropList.round(group.itemChanceSum()));
        if (!group.items().isEmpty() && Math.abs(group.itemChanceSum() - 100) > .01) {
            item.put("note", "The items do not add up to 100%. group_even scales them, as Make 100% does.");
        }
        return item;
    }

    static JSONArray describeIssues(List<Issue> issues) {
        var list = new JSONArray();
        for (var issue : issues.subList(0, Math.min(SHOWN_PROBLEMS, issues.size()))) {
            var item = new JSONObject();
            item.put("severity", issue.error() ? "error" : "warning");
            item.put("npc", issue.npc());
            item.put("setting", issue.key());
            item.put("message", issue.message());
            list.add(item);
        }
        return list;
    }

    static String value(ToolArgs args) throws ToolProblem {
        var raw = args.getJson().get("value");
        if (raw instanceof Number number) {
            var exact = new BigDecimal(number.toString()).stripTrailingZeros();
            return exact.scale() <= 0 ? exact.toBigInteger().toString() : exact.toPlainString();
        }
        if (raw instanceof Boolean flag) {
            return flag ? "1" : "0";
        }
        return args.getRequired("value");
    }

    ToolResult run(boolean hidden) throws ToolProblem {
        var op = args.getRequired("action");
        if (op.equals("undo") || op.equals("redo")) {
            return history(op, hidden);
        }
        var plan = plan(op);
        var revision = document.getRevision();
        var label = AgentSteps.PREFIX + plan.label();
        document.apply(label, plan.edit(), plan.batch());
        if (document.getRevision() == revision) {
            return ToolResult.text("Nothing changed: the values were already so.");
        }
        var said = plan.said();
        said.put("step", label);
        said.put("unsaved", document.isModified());
        addProblems(said, plan.records());
        return ToolResult.data(said);
    }

    private Plan plan(String op) throws ToolProblem {
        if (op.equals("batch")) {
            return batch();
        }
        var record = record();
        return switch (op) {
            case "set" -> set(record);
            case "reset" ->
                simple(record, "Reset " + record.name(), state -> document.withoutEdits(state, record.getIndex()));
            case "duplicate" -> duplicate(record);
            case "delete" -> delete(record);
            case "item_add", "item_change", "item_remove" -> item(op, record);
            case "group_add", "group_change", "group_remove", "group_even" -> group(op, record);
            case "share" -> share(record);
            case "skill_add", "skill_remove" -> skill(op, record);
            case "ai", "ai_set" -> ai(op, record);
            default -> throw new ToolProblem("action cannot be " + op + ". Use one of " + String.join(", ", OPS) + ".");
        };
    }

    private Plan simple(NpcRecord record, String label, UnaryOperator<State> edit) {
        return new Plan(label, edit, null, said(record), Set.of(record.getIndex()));
    }

    private Plan set(NpcRecord record) throws ToolProblem {
        var key = args.getRequired("key");
        var value = value(args);
        if (key.equals(NpcExperience.EXP)) {
            throw new ToolProblem("exp follows the level: set level, and exp takes the value the file uses for it.");
        }
        if (!record.has(key)) {
            throw new ToolProblem(record.name() + " has no setting " + key
                    + ", and the editor adds none. npc_get with detail lists its settings.");
        }
        check(key, value);
        var index = record.getIndex();
        UnaryOperator<State> edit;
        if (key.equals(NpcExperience.LEVEL)) {
            if (!value.matches("\\d{1,3}") || Integer.parseInt(value) < 1) {
                throw new ToolProblem("level must be a whole number from 1.");
            }
            var experience = NpcExperience.of(document.getFile());
            edit = state -> experience.withLevel(document, state, index, value);
        } else {
            edit = state -> document.with(state, index, key, value);
        }
        var said = said(record);
        said.put("key", key);
        if (key.equals("race") && args.readFlag("race_skill", false)) {
            var swap = raceSkill(record, value);
            var raced = edit;
            edit = state -> {
                var next = raced.apply(state);
                return swap == null ? next : document.with(next, index, NpcValues.SKILLS, swap);
            };
            said.put("race_skill", swap == null ? "kept: skilldata.txt has no race skill for " + value : "swapped");
        }
        return new Plan("Set " + key + " of " + record.name(), edit, null, said, Set.of(index));
    }

    private @Nullable String raceSkill(NpcRecord record, String race) {
        var list = document.getValue(document.getState(), record.getIndex(), NpcValues.SKILLS);
        if (list == null) {
            return null;
        }
        try {
            var skills = new ArrayList<>(NpcValues.parseSkills(list));
            var current = NpcDataReview.raceSkill(skills);
            var fitting = current.isEmpty() ? "" : NpcDataReview.raceSkillFor(race.strip(), current, data.getSkills());
            if (fitting.isEmpty()) {
                return null;
            }
            skills.set(skills.indexOf(current), fitting);
            return NpcValues.formatSkills(skills);
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }

    private Plan duplicate(NpcRecord record) {
        var index = record.getIndex();
        var copy = document.copy(document.getState(), index);
        var said = new JSONObject();
        said.put("copy", copy.name());
        said.put("id", copy.id());
        said.put("from", record.name());
        said.put(
                "note",
                "NPC Spawns knows the copy after spawn_save with file npcdata. The client shows a new id without a"
                        + " model or name.");
        return new Plan(
                "Duplicate " + record.name(),
                state -> document.copy(state, index).state(),
                null,
                said,
                Set.of(copy.index()));
    }

    private Plan delete(NpcRecord record) {
        var said = said(record);
        var entries = 0;
        if (spawns != null) {
            for (var maker : spawns.makers()) {
                if (maker != null) {
                    for (var npc : maker.npcs()) {
                        entries += npc.npcName().equals(record.name()) ? 1 : 0;
                    }
                }
            }
            said.put("spawn_entries", entries);
            if (entries > 0) {
                said.put(
                        "note",
                        "npcpos.txt still spawns it in " + entries
                                + " entries. The server cannot spawn an NPC npcdata.txt lacks: remove them with"
                                + " spawn_edit.");
            }
        }
        return new Plan(
                "Delete " + record.name(),
                state -> document.withRemoved(state, record.getIndex(), true),
                null,
                said,
                Set.of(record.getIndex()));
    }

    private String listKey(NpcRecord record) throws ToolProblem {
        var word = args.getText("list", "drop");
        var key = switch (word) {
            case "drop" -> DropList.DROP;
            case "spoil" -> DropList.SPOIL;
            case "herbs" -> DropList.HERBS;
            case "extra" -> DropList.EXTRA;
            case "make" -> DropList.MAKE;
            default -> throw new ToolProblem("list is drop, spoil, herbs, extra or make.");
        };
        if (!record.has(key)) {
            throw new ToolProblem(
                    record.name() + " has no " + key + " in npcdata.txt, and the editor adds no setting.");
        }
        return key;
    }

    private String itemName() throws ToolProblem {
        var text = args.getText("item");
        if (text == null) {
            throw new ToolProblem("item is missing: an itemdata.txt name or an id.");
        }
        return itemName(text);
    }

    private String itemName(String text) throws ToolProblem {
        var items = data.getItems();
        if (items.size() == 0) {
            return text;
        }
        var known = items.byName(text);
        if (known == null && text.chars().allMatch(Character::isDigit) && text.length() < 10) {
            known = items.byId(Integer.parseInt(text));
        }
        if (known == null) {
            throw new ToolProblem("itemdata.txt has no item " + text
                    + ". Give its id: assets_find with kind item finds it by the name the game shows.");
        }
        return known.name();
    }

    private Item counted(Item item) throws ToolProblem {
        var min = args.has("min") ? args.getInteger("min", 1) : (int) item.min();
        var max = args.has("max") ? args.getInteger("max", min) : Math.max(min, (int) item.max());
        if (min < 1 || max < min) {
            throw new ToolProblem("min must be 1 or more, and max at least min.");
        }
        var chance = args.getNumber("chance");
        if (chance != null && (chance < 0 || chance > 100)) {
            throw new ToolProblem("chance is a percent from 0 to 100.");
        }
        var counted = item.withCount(min, max);
        return chance == null ? counted : counted.withChance(DropList.round(chance));
    }

    private Plan item(String op, NpcRecord record) throws ToolProblem {
        var key = listKey(record);
        var index = record.getIndex();
        var value = Objects.requireNonNullElse(document.getValue(index, key), "{}");
        var said = said(record);
        said.put("list", key);
        String next;
        String label;
        try {
            if (DropList.isGrouped(key)) {
                var groups = new ArrayList<>(DropList.parseGroups(value));
                var g = groupOf(groups);
                var group = groups.get(g);
                Group changed;
                if (op.equals("item_add")) {
                    var name = itemName();
                    var item = counted(new Item(name, 1, 1, 0));
                    changed = args.has("chance")
                            ? group.withItem(group.items().size(), item)
                            : group.withAddedItem(item, Set.of());
                    label = "Add " + name;
                } else {
                    var at = itemIndex(group.items());
                    var old = group.items().get(at);
                    if (op.equals("item_remove")) {
                        changed = group.withoutItem(at).normalizedKeeping(Set.of());
                        label = "Remove " + old.name();
                    } else {
                        changed = group.withItem(at, counted(renamed(old)));
                        label = "Change " + old.name();
                    }
                }
                groups.set(g, changed);
                next = DropList.formatGroups(groups);
                said.put("group", describe(g, changed));
            } else {
                var items = new ArrayList<>(DropList.parseItems(value));
                if (op.equals("item_add")) {
                    var name = itemName();
                    items.add(counted(new Item(name, 1, 1, NEW_ITEM_CHANCE)));
                    label = "Add " + name;
                } else {
                    var at = itemIndex(items);
                    var old = items.get(at);
                    if (op.equals("item_remove")) {
                        items.remove(at);
                        label = "Remove " + old.name();
                    } else {
                        items.set(at, counted(renamed(old)));
                        label = "Change " + old.name();
                    }
                }
                next = DropList.formatItems(items);
                said.put("items", describeItems(items));
            }
        } catch (IllegalArgumentException unreadable) {
            throw new ToolProblem("The "
                    + key
                    + " of "
                    + record.name()
                    + " cannot be read: "
                    + unreadable.getMessage()
                    + ". set can replace it.");
        }
        return new Plan(
                label + " in " + key + " of " + record.name(),
                state -> document.with(state, index, key, next),
                null,
                said,
                Set.of(index));
    }

    private Item renamed(Item old) throws ToolProblem {
        var other = args.getText("value");
        return other == null ? old : old.withName(itemName(other));
    }

    private int itemIndex(List<Item> items) throws ToolProblem {
        var name = itemName();
        for (var i = 0; i < items.size(); i++) {
            if (items.get(i).name().equals(name)) {
                return i;
            }
        }
        throw new ToolProblem("This list has no " + name + ". npc_get with detail lists its items.");
    }

    private int groupOf(List<Group> groups) throws ToolProblem {
        if (!args.has("group")) {
            if (groups.size() == 1) {
                return 0;
            }
            throw new ToolProblem("This list has " + groups.size()
                    + " groups. Say which with group, counted from 0, as npc_get lists them.");
        }
        var g = args.getInteger("group", -1);
        if (g < 0 || g >= groups.size()) {
            throw new ToolProblem("There is no group " + g + ": the list has " + groups.size() + ", counted from 0.");
        }
        return g;
    }

    private Plan group(String op, NpcRecord record) throws ToolProblem {
        var key = listKey(record);
        if (!DropList.isGrouped(key)) {
            throw new ToolProblem(
                    "Only drop and herbs have groups. Use item_add, item_change or item_remove for " + key + ".");
        }
        var index = record.getIndex();
        List<Group> parsed;
        try {
            parsed = DropList.parseGroups(Objects.requireNonNullElse(document.getValue(index, key), "{}"));
        } catch (IllegalArgumentException unreadable) {
            throw new ToolProblem("The "
                    + key
                    + " of "
                    + record.name()
                    + " cannot be read: "
                    + unreadable.getMessage()
                    + ". set can replace it.");
        }
        var groups = new ArrayList<>(parsed);
        var said = said(record);
        said.put("list", key);
        String label;
        if (op.equals("group_add")) {
            var chance = args.getNumber("chance", NEW_GROUP_CHANCE);
            if (chance <= 0 || chance > 100) {
                throw new ToolProblem("chance is a percent above 0, at most 100.");
            }
            var item = counted(new Item(itemName(), 1, 1, 100)).withChance(100);
            groups.add(new Group(DropList.round(chance), List.of(item)));
            said.put("group", describe(groups.size() - 1, groups.getLast()));
            label = "Add group";
        } else {
            var g = groupOf(groups);
            var group = groups.get(g);
            switch (op) {
                case "group_change" -> {
                    var chance = args.getNumber("chance");
                    if (chance == null || chance < 0 || chance > 100) {
                        throw new ToolProblem("group_change needs chance, a percent from 0 to 100.");
                    }
                    groups.set(g, group.withChance(DropList.round(chance)));
                    label = "Group chance";
                }
                case "group_even" -> {
                    groups.set(g, group.normalized());
                    label = "Even out chances";
                }
                default -> {
                    groups.remove(g);
                    label = "Delete group";
                }
            }
            if (!op.equals("group_remove")) {
                said.put("group", describe(g, groups.get(g)));
            }
            said.put("groups", groups.size());
        }
        var next = DropList.formatGroups(groups);
        return new Plan(
                label + " in " + key + " of " + record.name(),
                state -> document.with(state, index, key, next),
                null,
                said,
                Set.of(index));
    }

    private Plan share(NpcRecord record) throws ToolProblem {
        var key = listKey(record);
        if (!key.equals(DropList.DROP) && !key.equals(DropList.SPOIL)) {
            throw new ToolProblem("share works on the drop and spoil lists, as the panel counts only those.");
        }
        var index = record.getIndex();
        var current = document.getValue(index, key);
        var original = document.isAdded(index)
                ? null
                : document.getFile().record(index).getValue(key);
        if (current == null || original == null || current.equals(original) || original.equals("{}")) {
            throw new ToolProblem("The " + key + " of " + record.name()
                    + " is as in the file, so there is nothing to share. Edit it first.");
        }
        var state = document.getState();
        var others = new ArrayList<>(index(document).findSameList(state, key, original));
        others.remove((Integer) index);
        if (others.isEmpty()) {
            throw new ToolProblem("No other NPC had the " + key + " of " + record.name() + " as it was.");
        }
        UnaryOperator<State> edit = from -> {
            var next = from;
            for (var other : others) {
                next = document.with(next, other, key, current);
            }
            return next;
        };
        var changed = NpcBatchEdits.findChangedRecords(state, edit.apply(state));
        var noun = (key.equals(DropList.DROP) ? "drop" : "spoil") + " list as on " + record.name();
        var said = said(record);
        said.put("npcs", changed.size());
        said.put("names", names(changed));
        return new Plan(
                "Copy " + noun + " to " + changed.size() + " NPCs",
                edit,
                new Batch(noun, changed, Set.of(key)),
                said,
                changed);
    }

    private String skillName() throws ToolProblem {
        var name = args.getRequired("skill");
        var skills = data.getSkills();
        if (skills.size() > 0 && skills.byName(name) == null) {
            throw new ToolProblem("skilldata.txt has no skill " + name
                    + ". npc_get with skill lists who has a skill; the server names skills such as"
                    + " s_npc_prop_race_animal.");
        }
        return name;
    }

    private Plan skill(String op, NpcRecord record) throws ToolProblem {
        if (!record.has(NpcValues.SKILLS)) {
            throw new ToolProblem(record.name() + " has no skill_list.");
        }
        var skill = skillName();
        var records = List.of(record.getIndex());
        var add = op.equals("skill_add");
        return new Plan(
                (add ? "Add skill " : "Remove skill ") + skill + (add ? " to " : " from ") + record.name(),
                state -> add
                        ? NpcBatchEdits.addSkill(document, state, records, skill)
                        : NpcBatchEdits.removeSkill(document, state, records, skill),
                null,
                said(record),
                Set.of(record.getIndex()));
    }

    private Plan ai(String op, NpcRecord record) throws ToolProblem {
        var index = record.getIndex();
        var value = document.getValue(index, NpcValues.AI);
        if (value == null) {
            throw new ToolProblem(record.name() + " has no npc_ai.");
        }
        Ai ai;
        try {
            ai = NpcValues.parseAi(value);
        } catch (IllegalArgumentException unreadable) {
            throw new ToolProblem("The npc_ai of "
                    + record.name()
                    + " cannot be read: "
                    + unreadable.getMessage()
                    + ". set can replace it.");
        }
        var catalog = NpcAiCatalog.of(document.getFile());
        var said = said(record);
        String label;
        if (op.equals("ai")) {
            var name = args.getRequired("value");
            if (!name.matches("[\\w.]+")) {
                throw new ToolProblem("An AI class is a name such as warrior_passive, without brackets.");
            }
            ai = ai.withName(name);
            var known = catalog.getAi(name);
            said.put("ai", name);
            said.put("npcs_with_it", known == null ? 0 : known.npcs());
            if (known == null) {
                said.put("note", "No NPC of the file has this AI. The server's compiled AI must have it.");
            }
            label = "AI " + name;
        } else {
            var key = args.getRequired("key");
            var at = -1;
            for (var i = 0; i < ai.parameters().size(); i++) {
                if (ai.parameters().get(i).name().equals(key)) {
                    at = i;
                }
            }
            var setting = args.getJson().get("value");
            if (setting == null) {
                if (at < 0) {
                    throw new ToolProblem(record.name() + " has no AI setting " + key + ".");
                }
                ai = ai.withoutParameter(at);
                label = "Remove AI setting " + key;
            } else {
                var text = String.valueOf(setting).strip();
                if (NpcAiCatalog.kind(key, text) == ValueKind.NUMBER
                        && !text.matches("-?\\d+(\\.\\d+)?")
                        && !text.startsWith("[")) {
                    throw new ToolProblem(key + " is a number.");
                }
                if (text.startsWith("@")
                        && data.getSkills().size() > 0
                        && data.getSkills().byName(text.substring(1)) == null) {
                    throw new ToolProblem("skilldata.txt has no skill " + text.substring(1) + ".");
                }
                ai = ai.withParameter(at < 0 ? ai.parameters().size() : at, new AiParameter(key, text));
                label = (at < 0 ? "Add AI setting " : "AI setting ") + key;
                if (at < 0
                        && !catalog.getParameters().stream()
                                .anyMatch(known -> known.name().equals(key))) {
                    said.put(
                            "note",
                            "No NPC of the file has the setting " + key
                                    + ". The AI reads only settings its class knows.");
                }
            }
            said.put("settings", ai.parameters().size());
        }
        var next = NpcValues.formatAi(ai);
        return new Plan(
                label + " of " + record.name(),
                state -> document.with(state, index, NpcValues.AI, next),
                null,
                said,
                Set.of(index));
    }

    private Plan batch() throws ToolProblem {
        var state = document.getState();
        var records = new LinkedHashSet<Integer>();
        for (var text : args.list("npcs")) {
            records.add(NpcLookup.one(document, state, data.getCatalog(), text).getIndex());
        }
        if (args.has("query") || args.has("kind")) {
            for (var hit :
                    NpcLookup.search(document, state, data.getCatalog(), args.getText("query"), args.getText("kind"))) {
                records.add(hit.record().getIndex());
            }
        }
        if (records.isEmpty()) {
            throw new ToolProblem("batch needs npcs, or a query or kind as npc_find takes them, that finds some.");
        }
        var chosen = List.copyOf(records);
        var action = args.getRequired("batch_action");
        var factor = args.getNumber("factor");
        var times = factor == null ? "" : NpcBatchEdits.formatFactor(factor);
        String label;
        String noun;
        String key;
        UnaryOperator<State> edit;
        switch (action) {
            case "scale_drop", "scale_spoil", "scale_exp", "scale_sp" -> {
                if (factor == null || !Double.isFinite(factor) || factor <= 0) {
                    throw new ToolProblem("factor must be a number above 0, such as 2 or 0.5.");
                }
                var what = switch (action) {
                    case "scale_drop" -> "drop chances";
                    case "scale_spoil" -> "spoil chances";
                    case "scale_exp" -> "exp rate";
                    default -> "SP";
                };
                label = times + " " + what;
                noun = what + " " + times;
                key = switch (action) {
                    case "scale_drop" -> DropList.DROP;
                    case "scale_spoil" -> DropList.SPOIL;
                    case "scale_exp" -> NpcExperience.EXP_RATE;
                    default -> NpcExperience.SP;
                };
                var points = !NpcExperience.of(document.getFile()).hasSpRate();
                edit = from -> switch (action) {
                    case "scale_drop" -> NpcBatchEdits.scaleDrop(document, from, chosen, factor);
                    case "scale_spoil" -> NpcBatchEdits.scaleSpoil(document, from, chosen, factor);
                    case "scale_exp" ->
                        NpcBatchEdits.scaleNumber(document, from, chosen, NpcExperience.EXP_RATE, factor, false);
                    default -> NpcBatchEdits.scaleNumber(document, from, chosen, NpcExperience.SP, factor, points);
                };
            }
            case "set" -> {
                key = args.getRequired("key");
                var value = value(args);
                if (key.equals(NpcExperience.EXP) || key.equals(NpcExperience.LEVEL)) {
                    throw new ToolProblem(
                            "A batch does not set level or exp, as exp follows the level. Set the level of each NPC.");
                }
                check(key, value);
                label = key + "=" + value;
                noun = key + " set to " + value;
                edit = from -> NpcBatchEdits.setValue(document, from, chosen, key, value);
            }
            case "skill_add", "skill_remove" -> {
                var skill = skillName();
                key = NpcValues.SKILLS;
                var add = action.equals("skill_add");
                label = (add ? "Add " : "Remove ") + skill;
                noun = (add ? "added " : "removed ") + skill;
                edit = from -> add
                        ? NpcBatchEdits.addSkill(document, from, chosen, skill)
                        : NpcBatchEdits.removeSkill(document, from, chosen, skill);
            }
            default ->
                throw new ToolProblem("batch_action is scale_drop, scale_spoil, scale_exp, scale_sp, set, skill_add or"
                        + " skill_remove.");
        }
        var changed = NpcBatchEdits.findChangedRecords(state, edit.apply(state));
        if (changed.isEmpty()) {
            throw new ToolProblem("Nothing changed: none of the " + chosen.size()
                    + " NPCs could take it, such as one without the setting.");
        }
        var said = new JSONObject();
        said.put("npcs_asked", chosen.size());
        said.put("npcs_changed", changed.size());
        said.put("names", names(changed));
        var stepKey = key;
        return new Plan(
                label + " on " + changed.size() + (changed.size() == 1 ? " NPC" : " NPCs"),
                edit,
                new Batch(noun, changed, Set.of(stepKey)),
                said,
                changed);
    }

    private NpcRecord record() throws ToolProblem {
        var text = args.getText("npc");
        if (text == null) {
            throw new ToolProblem("npc is missing. npc_find names the NPCs.");
        }
        return NpcLookup.one(document, document.getState(), data.getCatalog(), text);
    }

    private List<String> names(Set<Integer> records) {
        var names = new ArrayList<String>();
        for (var record : records) {
            if (names.size() == SHOWN_NAMES) {
                names.add("and " + (records.size() - SHOWN_NAMES) + " more");
                break;
            }
            names.add(document.record(record).name());
        }
        return names;
    }

    private void addProblems(JSONObject said, Set<Integer> records) {
        var issues = new ArrayList<Issue>();
        for (var issue : NpcDataReview.findIssues(document, document.getState(), data.getItems(), data.getSkills())) {
            if (records.contains(issue.record())) {
                issues.add(issue);
            }
        }
        if (issues.isEmpty()) {
            return;
        }
        issues.sort(Comparator.comparing((Issue issue) -> !issue.error()));
        said.put("problems", describeIssues(issues));
        said.put("more_problems", Math.max(0, issues.size() - SHOWN_PROBLEMS));
    }

    private ToolResult history(String op, boolean hidden) throws ToolProblem {
        var undo = op.equals("undo");
        if (undo ? !document.canUndo() : !document.canRedo()) {
            throw new ToolProblem(undo ? "There is nothing to undo." : "There is nothing to redo.");
        }
        var label = undo ? document.getUndoLabel() : document.getRedoLabel();
        if (!AgentSteps.isOwn(label, hidden)) {
            throw new ToolProblem(AgentSteps.held(label));
        }
        if (undo) {
            document.undo();
        } else {
            document.redo();
        }
        var said = new JSONObject();
        said.put(undo ? "undone" : "redone", label);
        said.put("next_undo", document.getUndoLabel());
        said.put("next_redo", document.getRedoLabel());
        said.put("unsaved", document.isModified());
        return ToolResult.data(said);
    }

    private static void check(String key, String value) throws ToolProblem {
        try {
            if (DropList.isGrouped(key)) {
                DropList.parseGroups(value);
            } else if (List.of(DropList.SPOIL, DropList.EXTRA, DropList.MAKE).contains(key)) {
                DropList.parseItems(value);
            } else if (key.equals(NpcValues.SKILLS)) {
                NpcValues.parseSkills(value);
            } else if (key.equals(NpcValues.AI)) {
                NpcValues.parseAi(value);
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException unreadable) {
            throw new ToolProblem("This is not a value of " + key + ": " + unreadable.getMessage());
        }
    }

    private static JSONArray describeItems(List<Item> items) {
        var list = new JSONArray();
        for (var item : items) {
            list.add(item.name()
                    + " "
                    + NpcDataReview.formatAmount(item)
                    + " "
                    + NpcDataReview.formatPercent(item.chance()));
        }
        return list;
    }

    private static JSONObject said(NpcRecord record) {
        var said = new JSONObject();
        said.put("npc", record.name());
        said.put("id", record.getId());
        return said;
    }
}
