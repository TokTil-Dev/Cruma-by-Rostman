package com.rost.cruma.viewer.agent.spawn;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.npc.NpcAiCatalog;
import com.rost.cruma.core.npc.NpcDataFile;
import com.rost.cruma.core.spawn.MakerAiCatalog;
import com.rost.cruma.core.spawn.NpcPosDocument;
import com.rost.cruma.core.spawn.NpcPosDocument.State;
import com.rost.cruma.core.spawn.SpawnArea.Hit;
import com.rost.cruma.core.spawn.SpawnCopies;
import com.rost.cruma.core.spawn.SpawnSummary;
import com.rost.cruma.viewer.agent.Access;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.spawn.SpawnTools.Desk;
import com.rost.cruma.viewer.spawn.SpawnBookmarks;
import com.rost.cruma.viewer.spawn.SpawnFilter;
import com.rost.cruma.viewer.spawn.SpawnStyle;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

final class SpawnLists {
    private static final int SHOWN_VALUES = 5;
    private static final int SHOWN_SETTINGS = 8;

    private static @Nullable NpcAiCatalog npcAis;
    private static @Nullable NpcDataFile npcAisFile;

    private SpawnLists() {}

    static SpawnFilter filter(ToolArgs args, State state) throws ToolProblem {
        Set<SpawnStyle> kinds = EnumSet.noneOf(SpawnStyle.class);
        var kind = args.getText("kind");
        if (kind != null) {
            kinds.add(
                    switch (kind) {
                        case "monster" -> SpawnStyle.MONSTER;
                        case "boss" -> SpawnStyle.BOSS;
                        case "townsfolk" -> SpawnStyle.CITIZEN;
                        case "guard" -> SpawnStyle.GUARD;
                        case "summon" -> SpawnStyle.SUMMON;
                        case "object" -> SpawnStyle.OBJECT;
                        case "unknown" -> SpawnStyle.UNKNOWN;
                        case "event" -> SpawnStyle.EVENT;
                        default ->
                            throw new ToolProblem(
                                    "kind is monster, boss, townsfolk, guard, summon, object, unknown or event.");
                    });
        }
        var band = -1;
        var level = args.getText("level");
        if (level != null) {
            for (var i = 0; i < SpawnSummary.BAND_LABELS.length; i++) {
                if (SpawnSummary.BAND_LABELS[i].replace('\u2013', '-').equals(level)) {
                    band = i;
                }
            }
            if (band < 0) {
                throw new ToolProblem("level is a band: 1-20, 21-40, 41-60, 61-76 or 77+.");
            }
        }
        return new SpawnFilter(
                kinds,
                band,
                args.getText("ai", ""),
                args.getText("respawn", ""),
                eventKey(args.getText("event"), state),
                args.readFlag("unknown", false));
    }

    static JSONObject maker(State state, NpcPosDocument document, String text, Desk desk) throws ToolProblem {
        var maker = SpawnEditor.findMaker(state, text);
        var npcs = desk.spawns().getSource().getNpcs();
        var item = SpawnFindTool.describeMaker(
                maker,
                new Hit(maker.index(), 0),
                false,
                null,
                npcs,
                desk.spawns().getNpcData().getCatalog(),
                Integer.MAX_VALUE);
        item.put("kind", SpawnStyle.of(maker, npcs).label);
        var zones = new JSONArray();
        for (var zone : SpawnCopies.findZones(state, maker)) {
            var row = new JSONObject();
            row.put("zone", zone.name());
            row.put("number", SpawnEditor.number(zone.index()));
            row.put("middle", Math.round(zone.centerX()) + "," + Math.round(zone.centerY()));
            row.put("bottom", zone.bottom());
            row.put("top", zone.top());
            row.put("corners", zone.points().size());
            zones.add(row);
        }
        item.put("zone_list", zones);
        if (maker.extended() && !maker.aiParameters().isEmpty()) {
            item.put("ai_parameters", maker.aiParameters());
        }
        item.put("bookmarked", desk.spawns().getBookmarks().contains(SpawnBookmarks.bookmarkKey(maker)));
        item.put(
                "text",
                SpawnCopies.copyAsText(
                        state, maker.index(), document.getFile().getSource().lineEnding()));
        return item;
    }

    static JSONObject list(String kind, ToolArgs args, Desk desk, State state, int limit) throws ToolProblem {
        var narrow = args.getText("ai", "").toLowerCase(Locale.ROOT);
        var result = new JSONObject();
        var rows = new JSONArray();
        var found = 0;
        switch (kind) {
            case "maker_ais" -> {
                for (var ai : MakerAiCatalog.of(state.makers()).getAis()) {
                    if (!ai.name().toLowerCase(Locale.ROOT).contains(narrow)) {
                        continue;
                    }
                    found++;
                    if (rows.size() < limit) {
                        var row = new JSONObject();
                        row.put("ai", ai.name());
                        row.put("makers", ai.makers());
                        var parameters = new JSONArray();
                        for (var parameter : ai.parameters()) {
                            var values = new JSONArray();
                            for (var value : parameter
                                    .values()
                                    .subList(
                                            0,
                                            Math.min(
                                                    SHOWN_VALUES,
                                                    parameter.values().size()))) {
                                values.add(value.text() + " (" + value.makers() + ")");
                            }
                            var item = new JSONObject();
                            item.put("name", parameter.name());
                            item.put("makers", parameter.makers());
                            item.put("values", values);
                            parameters.add(item);
                        }
                        row.put("parameters", parameters);
                        rows.add(row);
                    }
                }
                result.put(
                        "use",
                        "spawn_edit change_maker or batch set_ai takes an ai, and parameter with value sets one.");
            }
            case "npc_ais" -> {
                for (var ai : npcAis(desk).getAis()) {
                    if (!ai.name().toLowerCase(Locale.ROOT).contains(narrow)) {
                        continue;
                    }
                    found++;
                    if (rows.size() < limit) {
                        var row = new JSONObject();
                        row.put("ai", ai.name());
                        row.put("npcs", ai.npcs());
                        var settings = new JSONArray();
                        for (var parameter : ai.parameters()
                                .subList(
                                        0,
                                        Math.min(SHOWN_SETTINGS, ai.parameters().size()))) {
                            settings.add(parameter.name() + "=" + parameter.usual());
                        }
                        row.put("settings", settings);
                        rows.add(row);
                    }
                }
                result.put("use", "npc_edit with action ai takes an AI class, and ai_set a setting.");
            }
            case "events" -> {
                for (var event : SpawnSummary.collectEvents(state)) {
                    found++;
                    if (rows.size() < limit) {
                        var row = new JSONObject();
                        row.put(event.spawnTime() ? "spawn_time" : "event", event.name());
                        row.put("makers", event.makers().size());
                        rows.add(row);
                    }
                }
                result.put("use", "spawn_find with event lists the makers of one.");
            }
            case "bookmarks" -> {
                for (var key : desk.spawns().getBookmarks()) {
                    found++;
                    if (rows.size() < limit) {
                        var row = new JSONObject();
                        row.put("bookmark", key);
                        var index = SpawnBookmarks.find(state, key);
                        row.put("maker", index < 0 ? "no longer in npcpos.txt" : SpawnEditor.number(index));
                        rows.add(row);
                    }
                }
            }
            case "unused_zones" -> {
                for (var index : state.unusedZones()) {
                    found++;
                    if (rows.size() < limit) {
                        var zone = state.territories().get(index);
                        var row = new JSONObject();
                        row.put("zone", zone.name());
                        row.put("number", SpawnEditor.number(index));
                        row.put("middle", Math.round(zone.centerX()) + "," + Math.round(zone.centerY()));
                        rows.add(row);
                    }
                }
                result.put("use", "spawn_edit remove_unused_zones deletes them all as one step, as Delete all does.");
            }
            default -> throw new ToolProblem("list is maker_ais, npc_ais, events, bookmarks or unused_zones.");
        }
        result.put("found", found);
        result.put("more", found - rows.size());
        result.put(kind, rows);
        return result;
    }

    static ToolResult bookmark(ToolArgs args, Desk desk, State state, boolean on) throws ToolProblem {
        if (!desk.access().allows(Access.EDIT)) {
            throw new ToolProblem(
                    "A bookmark changes the list of the panel, so it needs Edit access. Ask the person to raise it in"
                            + " Settings, AI agent.");
        }
        var text = args.getText("maker");
        if (text == null) {
            throw new ToolProblem("maker is missing. spawn_find names the makers.");
        }
        var maker = SpawnEditor.findMaker(state, text);
        if (!desk.spawns().bookmark(maker, on)) {
            throw new ToolProblem(
                    on
                            ? "The bookmark could not be added: 128 bookmarks is the most. Remove one first."
                            : "The bookmark could not be removed.");
        }
        var said = new JSONObject();
        said.put(on ? "bookmarked" : "unbookmarked", maker.displayName());
        said.put("maker", SpawnEditor.number(maker.index()));
        said.put("bookmarks", desk.spawns().getBookmarks().size());
        return ToolResult.data(said);
    }

    private static String eventKey(@Nullable String text, State state) throws ToolProblem {
        if (text == null) {
            return "";
        }
        var names = new JSONArray();
        for (var event : SpawnSummary.collectEvents(state)) {
            if (event.name().equalsIgnoreCase(text)) {
                return SpawnFilter.eventKey(event.spawnTime(), event.name());
            }
            if (names.size() < SHOWN_VALUES) {
                names.add(event.name());
            }
        }
        throw new ToolProblem("No maker has the event or spawn time " + text + ". Some there are: " + names
                + ". spawn_find with list events lists all.");
    }

    private static synchronized NpcAiCatalog npcAis(Desk desk) throws ToolProblem {
        var document = desk.spawns().getNpcData().getDocument();
        if (document == null) {
            throw new ToolProblem("npcdata.txt was closed. Call again.");
        }
        if (npcAis == null || npcAisFile != document.getFile()) {
            npcAisFile = document.getFile();
            npcAis = NpcAiCatalog.of(document.getFile());
        }
        return npcAis;
    }
}
