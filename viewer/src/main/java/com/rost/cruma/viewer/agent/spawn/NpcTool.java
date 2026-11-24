package com.rost.cruma.viewer.agent.spawn;

import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.PERSON_NANOS;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.after;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.areNpcsReady;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.checkScripts;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.describeChanges;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.document;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.limit;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.npcDocument;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.waitForPerson;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.npc.ClientCatalog;
import com.rost.cruma.core.npc.DropList;
import com.rost.cruma.core.npc.NpcDataDocument;
import com.rost.cruma.core.npc.NpcDataIndex;
import com.rost.cruma.core.npc.NpcDataIndex.ItemUse;
import com.rost.cruma.core.npc.NpcKind;
import com.rost.cruma.core.npc.NpcRecord;
import com.rost.cruma.core.npc.NpcValues;
import com.rost.cruma.core.npc.ServerItems;
import com.rost.cruma.core.npc.ServerSkills;
import com.rost.cruma.core.spawn.NpcPosDocument.State;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.spawn.NpcLookup.Hit;
import com.rost.cruma.viewer.agent.spawn.SpawnTools.Desk;
import com.rost.cruma.viewer.npc.NpcDataSource;
import com.rost.cruma.viewer.spawn.SpawnAccess.Look;
import com.rost.cruma.viewer.spawn.SpawnModels.Status;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

final class NpcTool {
    private static final long MODEL_NANOS = TimeUnit.SECONDS.toNanos(20);
    private static final int SHOWN_DROPS = 8;
    private static final int SHOWN_SKILLS = 12;
    private static final Map<String, String> STATS = stats();

    private NpcTool() {}

    static Step npcFind(ToolArgs args, Desk desk) throws ToolProblem {
        checkScripts(args, desk);
        var query = args.getText("query");
        var kind = args.getText("kind");
        var limit = limit(args);
        return after(() -> areNpcsReady(desk), () -> {
            var data = desk.spawns().getNpcData();
            var document = npcDocument(desk);
            var hits = NpcLookup.search(document, document.getState(), data.getCatalog(), query, kind);
            return Step.done(ToolResult.data(
                    listNpcs(document, hits, data.getCatalog(), document(desk).getState(), limit)));
        });
    }

    static Step npcGet(ToolArgs args, Desk desk) throws ToolProblem {
        checkScripts(args, desk);
        var query = args.getText("npc");
        var item = args.getText("item");
        var skill = args.getText("skill");
        var changes = args.readFlag("changes", false);
        if (query == null && item == null && skill == null && !changes) {
            throw new ToolProblem("Give npc, item, skill or changes.");
        }
        var detail = args.readFlag("detail", false);
        var limit = limit(args);
        return after(() -> areNpcsReady(desk), () -> {
            var data = desk.spawns().getNpcData();
            var document = npcDocument(desk);
            if (changes) {
                return Step.done(ToolResult.data(describeChanges(data, document, limit)));
            }
            if (item != null || skill != null) {
                return Step.done(ToolResult.data(users(data, document, item, skill, limit)));
            }
            var record = NpcLookup.one(document, document.getState(), data.getCatalog(), query);
            var counts = spawned(document(desk).getState());
            var deadline = System.nanoTime() + MODEL_NANOS;
            return () -> {
                var look = desk.spawns().look(record.getId());
                if (look != null && look.status() == Status.LOADING && System.nanoTime() < deadline) {
                    return null;
                }
                return ToolResult.data(describe(
                        record, document, data.getCatalog(), data.getItems(), data.getSkills(), look, counts, detail));
            };
        });
    }

    static Step npcEdit(ToolArgs args, Desk desk) throws ToolProblem {
        args.getRequired("action");
        var deadline = System.nanoTime() + PERSON_NANOS;
        return after(() -> areNpcsReady(desk), () -> () -> {
            var spawns = desk.spawns();
            if (spawns.isNpcHeld() || spawns.getNpcData().isSaving()) {
                return waitForPerson(deadline);
            }
            npcDocument(desk);
            var positions = spawns.getSource().getDocument();
            return new NpcEditor(args, spawns.getNpcData(), positions == null ? null : positions.getState())
                    .run(desk.hidden());
        });
    }

    private static Map<String, String> stats() {
        var stats = new LinkedHashMap<String, String>();
        stats.put("hp", "org_hp");
        stats.put("mp", "org_mp");
        stats.put("p_atk", "base_physical_attack");
        stats.put("m_atk", "base_magic_attack");
        stats.put("p_def", "base_defend");
        stats.put("m_def", "base_magic_defend");
        stats.put("attack_speed", "base_attack_speed");
        stats.put("attack_range", "base_attack_range");
        stats.put("aggro_range", "agro_range");
        stats.put("exp_rate", "acquire_exp_rate");
        stats.put("sp", "acquire_sp");
        return stats;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static Map<String, int[]> spawned(State state) {
        var counts = new HashMap<String, int[]>();
        for (var maker : state.makers()) {
            if (maker != null) {
                for (var npc : maker.npcs()) {
                    var count = counts.computeIfAbsent(npc.npcName(), unused -> new int[2]);
                    count[0]++;
                    count[1] += npc.total();
                }
            }
        }
        return counts;
    }

    private static JSONObject listNpcs(
            NpcDataDocument document, List<Hit> found, ClientCatalog catalog, State state, int limit) {
        var counts = spawned(state);
        var list = new JSONArray();
        var npcState = document.getState();
        for (var hit : found.subList(0, Math.min(limit, found.size()))) {
            var npc = hit.record();
            var item = new JSONObject();
            item.put("id", npc.getId());
            item.put("name", npc.name());
            var client = catalog.getNpc(npc.getId());
            if (client != null) {
                item.put("game", client.name());
                if (!client.title().isBlank()) {
                    item.put("title", client.title());
                }
            }
            item.put("type", npc.getType());
            item.put("level", NpcLookup.level(document, npcState, npc.getIndex()));
            var count = counts.get(npc.name());
            item.put("spawned", count == null ? 0 : count[1]);
            if (document.isAdded(npc.getIndex())) {
                item.put("new", true);
            } else if (npcState.isEdited(npc.getIndex())) {
                item.put("edited", true);
            }
            list.add(item);
        }
        var result = new JSONObject();
        result.put("found", found.size());
        result.put("more", Math.max(0, found.size() - list.size()));
        result.put("npcs", list);
        if (found.isEmpty()) {
            result.put("next", "No NPC fits. Try fewer words, or an id.");
        }
        return result;
    }

    private static JSONObject users(
            NpcDataSource data, NpcDataDocument document, @Nullable String item, @Nullable String skill, int limit)
            throws ToolProblem {
        var state = document.getState();
        var index = NpcEditor.index(document);
        var catalog = data.getCatalog();
        var list = new JSONArray();
        var result = new JSONObject();
        int found;
        if (item != null) {
            var name = item;
            var known = data.getItems().byName(item);
            if (known == null && item.chars().allMatch(Character::isDigit) && item.length() < 10) {
                known = data.getItems().byId(Integer.parseInt(item));
                name = known == null ? item : known.name();
            }
            var uses = index.findDroppedBy(state, name);
            found = uses.size();
            result.put("item", name);
            for (var use : uses.subList(0, Math.min(limit, uses.size()))) {
                var row = user(document, state, catalog, use.record());
                row.put("list", listName(use.key()));
                if (use.group() >= 0) {
                    row.put("group", use.group());
                }
                row.put(
                        "count",
                        use.item().min() == use.item().max()
                                ? String.valueOf(use.item().min())
                                : use.item().min() + "-" + use.item().max());
                row.put(
                        use.key().equals(DropList.SPOIL) ? "per_spoil" : "per_kill",
                        Math.round(use.perKill() * 10000) / 10000.0);
                list.add(row);
            }
        } else {
            var uses = index.findKnownBy(state, skill);
            found = uses.size();
            result.put("skill", skill);
            for (var use : uses.subList(0, Math.min(limit, uses.size()))) {
                var row = user(document, state, catalog, use.record());
                row.put("setting", use.setting().isEmpty() ? NpcValues.SKILLS : use.setting());
                list.add(row);
            }
        }
        result.put("found", found);
        result.put("more", found - list.size());
        result.put("npcs", list);
        if (found == 0) {
            result.put(
                    "next",
                    item != null
                            ? "No NPC has this item. Use the itemdata.txt name or the id, as assets_find with kind"
                                    + " item gives it."
                            : "No NPC has this skill. Use the skilldata.txt name, such as s_power_strike.");
        }
        return result;
    }

    private static JSONObject user(
            NpcDataDocument document, NpcDataDocument.State state, ClientCatalog catalog, int index) {
        var record = document.record(state, index);
        var row = new JSONObject();
        row.put("npc", record.name());
        row.put("id", record.getId());
        var client = catalog.getNpc(record.getId());
        if (client != null) {
            row.put("game", client.name());
        }
        row.put("level", NpcLookup.level(document, state, index));
        row.put("kind", NpcKind.ofType(record.getType()).name().toLowerCase(Locale.ROOT));
        return row;
    }

    private static JSONObject describe(
            NpcRecord record,
            NpcDataDocument document,
            ClientCatalog catalog,
            ServerItems items,
            ServerSkills skills,
            @Nullable Look look,
            Map<String, int[]> counts,
            boolean detail) {
        var result = new JSONObject();
        result.put("id", record.getId());
        result.put("name", record.name());
        var client = catalog.getNpc(record.getId());
        if (client != null) {
            result.put("game", client.name());
            if (!client.title().isBlank()) {
                result.put("title", client.title());
            }
        }
        result.put("type", record.getType());
        result.put("kind", NpcKind.ofType(record.getType()).name().toLowerCase(Locale.ROOT));
        var state = document.getState();
        var values = new JSONObject();
        var level = document.getValue(state, record.getIndex(), "level");
        if (level != null) {
            result.put("level", number(level));
        }
        for (var key : List.of("race", "sex")) {
            var value = document.getValue(state, record.getIndex(), key);
            if (value != null) {
                result.put(key, value);
            }
        }
        STATS.forEach((word, key) -> {
            var value = document.getValue(state, record.getIndex(), key);
            if (value != null) {
                values.put(word, number(value));
            }
        });
        addFirst(values, "walk", document.getValue(state, record.getIndex(), "ground_low"));
        addFirst(values, "run", document.getValue(state, record.getIndex(), "ground_high"));
        addFirst(values, "collision_radius", document.getValue(state, record.getIndex(), "collision_radius"));
        addFirst(values, "collision_height", document.getValue(state, record.getIndex(), "collision_height"));
        result.put("stats", values);
        result.put("ai", describeAi(document, record, detail));
        result.put("skills", describeSkills(document, record, skills, catalog, detail));
        var drops = describeDrops(document, record, items, catalog, detail);
        var shared = new JSONObject();
        for (var key : List.of(DropList.DROP, DropList.SPOIL)) {
            var list = document.getValue(state, record.getIndex(), key);
            if (list != null && !list.equals("{}")) {
                var same =
                        NpcEditor.index(document).findSameList(state, key, list).size() - 1;
                if (same > 0) {
                    shared.put(listName(key), same);
                }
            }
        }
        if (!shared.isEmpty()) {
            drops.put("same_list_as_others", shared);
        }
        result.put("drops", drops);
        result.put("model", describeModel(look));
        var count = counts.get(record.name());
        var placed = new JSONObject();
        placed.put("entries", count == null ? 0 : count[0]);
        placed.put("npcs", count == null ? 0 : count[1]);
        result.put("spawned", placed);
        if (detail) {
            var settings = new JSONObject();
            for (var key : record.getKeys()) {
                settings.put(key, document.getValue(state, record.getIndex(), key));
            }
            result.put("settings", settings);
        }
        return result;
    }

    private static Object number(String value) {
        try {
            var parsed = round(Double.parseDouble(value.strip()));
            return parsed == Math.rint(parsed) ? (Object) (long) parsed : (Object) parsed;
        } catch (NumberFormatException text) {
            return value;
        }
    }

    private static void addFirst(JSONObject values, String word, @Nullable String list) {
        if (list == null) {
            return;
        }
        var text = list.replace("{", "").replace("}", "");
        var end = text.indexOf(';');
        values.put(word, number(end < 0 ? text : text.substring(0, end)));
    }

    private static JSONObject describeAi(NpcDataDocument document, NpcRecord record, boolean detail) {
        var state = document.getState();
        var ai = new JSONObject();
        ai.put("name", NpcDataIndex.aiOf(document, state, record.getIndex()));
        var value = document.getValue(state, record.getIndex(), NpcValues.AI);
        if (value == null) {
            return ai;
        }
        try {
            var parameters = NpcValues.parseAi(value).parameters();
            ai.put("parameters", parameters.size());
            if (detail) {
                var list = new JSONObject();
                for (var parameter : parameters) {
                    list.put(parameter.name(), parameter.value());
                }
                ai.put("values", list);
            }
        } catch (IllegalArgumentException unreadable) {
            ai.put("note", "The file's npc_ai cannot be read.");
        }
        return ai;
    }

    private static JSONObject describeSkills(
            NpcDataDocument document, NpcRecord record, ServerSkills skills, ClientCatalog catalog, boolean detail) {
        var uses = NpcDataIndex.skillsOf(document, document.getState(), record.getIndex());
        var list = new JSONArray();
        var hidden = 0;
        for (var use : uses) {
            if (!detail && (use.skill().startsWith("s_npc_prop") || list.size() >= SHOWN_SKILLS)) {
                hidden++;
                continue;
            }
            var item = new JSONObject();
            item.put("skill", use.skill());
            var known = skills.byName(use.skill());
            var client = known == null ? null : catalog.getSkill(known.id(), known.level());
            if (client != null) {
                item.put("game", client.name());
            }
            if (!use.setting().isEmpty()) {
                item.put("setting", use.setting());
            }
            list.add(item);
        }
        var result = new JSONObject();
        result.put("count", uses.size());
        result.put("more", hidden);
        result.put("list", list);
        return result;
    }

    private static JSONObject describeDrops(
            NpcDataDocument document, NpcRecord record, ServerItems items, ClientCatalog catalog, boolean detail) {
        var uses = new ArrayList<>(NpcDataIndex.itemsOf(document, document.getState(), record.getIndex()));
        uses.sort(Comparator.comparingDouble(ItemUse::perKill).reversed());
        var list = new JSONArray();
        for (var use : uses.subList(0, detail ? uses.size() : Math.min(SHOWN_DROPS, uses.size()))) {
            var item = new JSONObject();
            item.put("item", use.item().name());
            var known = items.byName(use.item().name());
            var client = known == null ? null : catalog.getItem(known.id());
            if (client != null) {
                item.put("game", client.name());
            }
            item.put(
                    "count",
                    use.item().min() == use.item().max()
                            ? String.valueOf(use.item().min())
                            : use.item().min() + "-" + use.item().max());
            item.put("percent", Math.round(use.perKill() * 10000) / 10000.0);
            item.put("list", listName(use.key()));
            if (use.group() >= 0) {
                item.put("group", use.group());
            }
            list.add(item);
        }
        var result = new JSONObject();
        result.put("count", uses.size());
        result.put("more", uses.size() - list.size());
        result.put("list", list);
        return result;
    }

    private static String listName(String key) {
        return switch (key) {
            case DropList.DROP -> "drop";
            case DropList.SPOIL -> "spoil";
            case DropList.HERBS -> "herbs";
            case DropList.EXTRA -> "extra";
            case DropList.MAKE -> "make";
            default -> key;
        };
    }

    private static JSONObject describeModel(@Nullable Look look) {
        var model = new JSONObject();
        if (look == null) {
            model.put("status", "not read, as no viewer is open");
            model.put("use", "assets_find with kind npc gives its class and mesh.");
            return model;
        }
        model.put(
                "status",
                switch (look.status()) {
                    case READY -> "has a model";
                    case LOADING -> "still loading";
                    case MISSING -> "no model";
                    case INVISIBLE -> "invisible in game";
                    case NOT_IN_CLIENT -> "not in the client";
                });
        var appearance = look.appearance();
        if (appearance != null) {
            model.put("mesh", appearance.mesh());
            model.put("textures", appearance.textures());
            model.put("class", appearance.npcClass());
            model.put("scale", round(appearance.drawScale()));
            model.put(
                    "animations",
                    "assets_find with kind animation and model " + appearance.id()
                            + " lists them, and says whether it flies.");
        }
        return model;
    }
}
