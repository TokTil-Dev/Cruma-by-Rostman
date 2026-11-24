package com.rost.cruma.viewer.agent.spawn;

import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.after;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.areNpcsReady;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.area;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.checkScripts;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.document;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.limit;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.namesReady;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.point;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.npc.ClientCatalog;
import com.rost.cruma.core.spawn.NpcDataTable;
import com.rost.cruma.core.spawn.NpcDataTable.Npc;
import com.rost.cruma.core.spawn.NpcPosDocument.State;
import com.rost.cruma.core.spawn.SpawnArea;
import com.rost.cruma.core.spawn.SpawnArea.Hit;
import com.rost.cruma.core.spawn.SpawnMaker;
import com.rost.cruma.core.spawn.SpawnNpc;
import com.rost.cruma.core.spawn.SpawnPosition.Anywhere;
import com.rost.cruma.core.spawn.SpawnPosition.Fixed;
import com.rost.cruma.core.spawn.SpawnPosition.Weighted;
import com.rost.cruma.core.spawn.SpawnSummary;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.spawn.SpawnTools.Desk;
import com.rost.cruma.viewer.spawn.SpawnFilter;
import com.rost.cruma.viewer.spawn.SpawnStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

final class SpawnFindTool {
    private static final int SHOWN_ENTRIES = 6;
    private static final int SERVER_ID_OFFSET = 1_000_000;
    private static final List<String> FILTERS = List.of("kind", "level", "ai", "respawn", "event", "unknown");

    private SpawnFindTool() {}

    static Step spawnFind(ToolArgs args, Desk desk) throws ToolProblem {
        checkScripts(args, desk);
        var query = args.getText("npc");
        var listed = args.getText("list");
        var makerText = args.getText("maker");
        var filtering = FILTERS.stream().anyMatch(args::has);
        if (query == null && args.getText("target") == null && listed == null && makerText == null && !filtering) {
            throw new ToolProblem("Give a target, an npc, a maker, a filter or a list.");
        }
        var limit = limit(args);
        return after(() -> "npc_ais".equals(listed) ? areNpcsReady(desk) : namesReady(desk), () -> {
            var state = document(desk).getState();
            if (listed != null) {
                return Step.done(ToolResult.data(SpawnLists.list(listed, args, desk, state, limit)));
            }
            if (makerText != null) {
                return Step.done(ToolResult.data(SpawnLists.maker(state, document(desk), makerText, desk)));
            }
            var area = area(args, desk);
            var filter = SpawnLists.filter(args, state);
            var npcs = desk.spawns().getSource().getNpcs();
            var catalog = desk.spawns().getNpcData().getCatalog();
            return Step.onWorker(() -> {
                var name = query == null ? null : one(npcs, catalog, query).name();
                return ToolResult.data(listSpawns(state, area, name, filter, npcs, catalog, limit));
            });
        });
    }

    static JSONObject describeMaker(
            SpawnMaker maker, Hit hit, boolean near, @Nullable String npc, NpcDataTable npcs, ClientCatalog catalog) {
        return describeMaker(maker, hit, near, npc, npcs, catalog, SHOWN_ENTRIES);
    }

    static JSONObject describeMaker(
            SpawnMaker maker,
            Hit hit,
            boolean near,
            @Nullable String npc,
            NpcDataTable npcs,
            ClientCatalog catalog,
            int shown) {
        var item = new JSONObject();
        item.put("maker", SpawnEditor.number(maker.index()));
        item.put("name", maker.displayName());
        if (near) {
            item.put("distance", Math.round(hit.distance()));
        }
        item.put("zones", maker.territoryNames());
        if (!maker.bannedTerritoryNames().isEmpty()) {
            item.put("banned", maker.bannedTerritoryNames());
        }
        item.put("total", maker.totalNpcs());
        item.put("maximum", maker.maximumNpc());
        if (!maker.ai().isEmpty()) {
            item.put("ai", maker.ai());
        }
        if (maker.isConditional()) {
            item.put("event", maker.eventName().isEmpty() ? maker.spawnTimeKind() : maker.eventName());
        }
        var levels = SpawnSummary.computeLevels(maker, npcs);
        if (levels != null) {
            item.put("levels", levels.label());
        }
        var entries = new JSONArray();
        var left = 0;
        for (var slot = 0; slot < maker.npcs().size(); slot++) {
            var entry = maker.npcs().get(slot);
            if (npc != null && !entry.npcName().equals(npc)) {
                continue;
            }
            if (entries.size() < shown) {
                entries.add(describeEntry(entry, slot, npcs, catalog));
            } else {
                left++;
            }
        }
        item.put("entries", entries);
        if (left > 0) {
            item.put("more_entries", left);
        }
        return item;
    }

    private static String words(String name) {
        return name.strip().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String gameName(ClientCatalog catalog, int id) {
        var npc = catalog.getNpc(id);
        return npc == null ? "" : npc.name();
    }

    private record Found(Npc npc, int rank) {}

    private static List<Found> search(NpcDataTable npcs, ClientCatalog catalog, String query) {
        var wanted = words(query);
        var parts = wanted.split("\\s+");
        var number =
                wanted.chars().allMatch(Character::isDigit) && wanted.length() < 10 ? Integer.parseInt(wanted) : -1;
        var found = new ArrayList<Found>();
        for (var npc : npcs.getNpcs()) {
            var rank = rank(npc, catalog, wanted, parts, number);
            if (rank >= 0) {
                found.add(new Found(npc, rank));
            }
        }
        found.sort(Comparator.comparingInt(Found::rank)
                .thenComparingInt(hit -> hit.npc().id()));
        return found;
    }

    private static int rank(Npc npc, ClientCatalog catalog, String wanted, String[] parts, int number) {
        if (npc.id() == number || npc.id() + SERVER_ID_OFFSET == number) {
            return 0;
        }
        var name = words(npc.name());
        var client = catalog.getNpc(npc.id());
        var game = client == null ? "" : words(client.name());
        var title = client == null ? "" : words(client.title());
        if (name.equals(wanted)) {
            return 1;
        }
        if (game.equals(wanted)) {
            return 2;
        }
        if (name.startsWith(wanted) || game.startsWith(wanted)) {
            return 3;
        }
        var all = name + " " + game + " " + title;
        for (var part : parts) {
            if (!all.contains(part)) {
                return -1;
            }
        }
        return 4;
    }

    private static Npc one(NpcDataTable npcs, ClientCatalog catalog, String query) throws ToolProblem {
        if (npcs.size() == 0) {
            throw new ToolProblem("The script folder has no npcdata.txt, so NPCs are not known.");
        }
        var found = search(npcs, catalog, query);
        if (found.isEmpty()) {
            throw new ToolProblem("No NPC fits \"" + query + "\". Call npc_find with fewer words.");
        }
        var best = found.getFirst().rank();
        var equal = found.stream().filter(hit -> hit.rank() == best).toList();
        if (equal.size() > 1) {
            var some = equal.stream()
                    .limit(SHOWN_ENTRIES)
                    .map(hit -> hit.npc().name() + " (" + hit.npc().id() + ")")
                    .toList();
            throw new ToolProblem(
                    equal.size() + " NPCs fit \"" + query + "\", such as " + String.join(", ", some) + ". Use an id.");
        }
        return equal.getFirst().npc();
    }

    private static JSONObject listSpawns(
            State state,
            @Nullable SpawnArea area,
            @Nullable String npc,
            SpawnFilter filter,
            NpcDataTable npcs,
            ClientCatalog catalog,
            int limit) {
        var hits = area == null ? allMakers(state) : area.makers(state);
        var makers = new ArrayList<Hit>();
        var total = 0;
        for (var hit : hits) {
            var maker = state.makers().get(hit.index());
            if (!filter.isEmpty() && !filter.passes(maker, SpawnStyle.of(maker, npcs), npcs)) {
                continue;
            }
            var spawning = countOf(maker, npc);
            if (npc == null || spawning > 0) {
                makers.add(hit);
                total += spawning;
            }
        }
        var list = new JSONArray();
        for (var hit : makers.subList(0, Math.min(limit, makers.size()))) {
            list.add(describeMaker(state.makers().get(hit.index()), hit, area != null, npc, npcs, catalog));
        }
        var result = new JSONObject();
        result.put("makers", makers.size());
        result.put("npcs", total);
        result.put("more", makers.size() - list.size());
        result.put("list", list);
        if (area == null) {
            result.put("scope", "the whole file, also tiles the viewer does not hold");
        }
        if (area != null && npc == null && filter.isEmpty()) {
            addZones(result, state, area, limit);
        }
        return result;
    }

    private static int countOf(SpawnMaker maker, @Nullable String npc) {
        var count = 0;
        for (var entry : maker.npcs()) {
            if (npc == null || entry.npcName().equals(npc)) {
                count += entry.total();
            }
        }
        return count;
    }

    private static List<Hit> allMakers(State state) {
        var hits = new ArrayList<Hit>();
        for (var maker : state.makers()) {
            if (maker != null) {
                hits.add(new Hit(maker.index(), 0));
            }
        }
        return hits;
    }

    private static JSONObject describeEntry(SpawnNpc entry, int slot, NpcDataTable npcs, ClientCatalog catalog) {
        var item = new JSONObject();
        item.put("slot", slot);
        item.put("npc", entry.npcName());
        var known = npcs.getNpc(entry.npcName());
        if (known != null) {
            var game = gameName(catalog, known.id());
            if (!game.isEmpty()) {
                item.put("game", game);
            }
            item.put("level", known.level());
        }
        item.put("total", entry.total());
        item.put("respawn", entry.respawn());
        if (!entry.respawnRandom().isEmpty()) {
            item.put("random", entry.respawnRandom());
        }
        item.put(
                "place",
                switch (entry.position()) {
                    case Anywhere anywhere -> "anywhere in its zones";
                    case Fixed fixed -> point(fixed.x(), fixed.y(), fixed.z());
                    case Weighted weighted -> weighted.points().size() + " weighted points";
                });
        return item;
    }

    private static void addZones(JSONObject result, State state, SpawnArea area, int limit) {
        var users = new HashMap<String, Integer>();
        for (var maker : state.makers()) {
            if (maker != null) {
                for (var name : maker.territoryNames()) {
                    users.merge(name, 1, Integer::sum);
                }
            }
        }
        var zones = area.zones(state);
        var list = new JSONArray();
        for (var hit : zones.subList(0, Math.min(limit, zones.size()))) {
            var zone = state.territories().get(hit.index());
            var item = new JSONObject();
            item.put("zone", zone.name());
            item.put("number", SpawnEditor.number(zone.index()));
            item.put("distance", Math.round(hit.distance()));
            item.put("middle", point(zone.centerX(), zone.centerY(), (zone.bottom() + zone.top()) * .5));
            item.put("bottom", zone.bottom());
            item.put("top", zone.top());
            item.put("corners", zone.points().size());
            item.put("makers", users.getOrDefault(zone.name(), 0));
            list.add(item);
        }
        result.put("zones", zones.size());
        result.put("more_zones", zones.size() - list.size());
        result.put("zone_list", list);
    }
}
