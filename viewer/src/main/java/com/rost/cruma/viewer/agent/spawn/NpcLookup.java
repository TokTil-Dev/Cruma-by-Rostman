package com.rost.cruma.viewer.agent.spawn;

import com.rost.cruma.core.npc.ClientCatalog;
import com.rost.cruma.core.npc.NpcDataDocument;
import com.rost.cruma.core.npc.NpcDataDocument.State;
import com.rost.cruma.core.npc.NpcRecord;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.spawn.SpawnStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

final class NpcLookup {
    static final String[] KINDS = {"monster", "boss", "townsfolk"};

    private static final int SERVER_ID_OFFSET = 1_000_000;
    private static final int SHOWN = 6;
    private static final Pattern LEVELS = Pattern.compile("lv\\s*(\\d+)(?:\\s*-\\s*(\\d+))?");

    private NpcLookup() {}

    record Hit(NpcRecord record, int rank) {}

    static List<Hit> search(
            NpcDataDocument document, State state, ClientCatalog catalog, @Nullable String query, @Nullable String kind)
            throws ToolProblem {
        var style = style(kind);
        var wanted = query == null ? "" : words(query);
        var parts = wanted.isEmpty() ? new String[0] : wanted.split("\\s+");
        var number = !wanted.isEmpty() && wanted.chars().allMatch(Character::isDigit) && wanted.length() < 10
                ? Integer.parseInt(wanted)
                : -1;
        var levels = LEVELS.matcher(wanted);
        var from = -1;
        var to = -1;
        if (levels.matches()) {
            from = Integer.parseInt(levels.group(1));
            to = levels.group(2) == null ? from : Integer.parseInt(levels.group(2));
            if (to < from) {
                var swap = to;
                to = from;
                from = swap;
            }
        }
        var found = new ArrayList<Hit>();
        for (var record : document.getRecords(state)) {
            if (style != null && SpawnStyle.ofType(record.getType()) != style) {
                continue;
            }
            int rank;
            if (wanted.isEmpty()) {
                rank = 4;
            } else if (from >= 0) {
                var level = level(document, state, record.getIndex());
                rank = level >= from && level <= to ? 4 : -1;
            } else {
                rank = rank(record, catalog, wanted, parts, number);
            }
            if (rank >= 0) {
                found.add(new Hit(record, rank));
            }
        }
        found.sort(Comparator.comparingInt(Hit::rank)
                .thenComparingInt(hit -> hit.record().getIndex()));
        return found;
    }

    static NpcRecord one(NpcDataDocument document, State state, ClientCatalog catalog, String query)
            throws ToolProblem {
        if (document.getSize(state) == 0) {
            throw new ToolProblem("The script folder has no npcdata.txt, so NPCs are not known.");
        }
        var found = search(document, state, catalog, query, null);
        if (found.isEmpty()) {
            throw new ToolProblem("No NPC fits \"" + query + "\". Call npc_find with fewer words.");
        }
        var best = found.getFirst().rank();
        var equal = found.stream().filter(hit -> hit.rank() == best).toList();
        if (equal.size() > 1) {
            var some = equal.stream()
                    .limit(SHOWN)
                    .map(hit -> hit.record().name() + " (" + hit.record().getId() + ")")
                    .toList();
            throw new ToolProblem(
                    equal.size() + " NPCs fit \"" + query + "\", such as " + String.join(", ", some) + ". Use an id.");
        }
        return equal.getFirst().record();
    }

    static int level(NpcDataDocument document, State state, int record) {
        var value = document.getValue(state, record, "level");
        try {
            return value == null ? -1 : Integer.parseInt(value.strip());
        } catch (NumberFormatException notLevel) {
            return -1;
        }
    }

    private static @Nullable SpawnStyle style(@Nullable String kind) throws ToolProblem {
        if (kind == null) {
            return null;
        }
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "monster", "monsters" -> SpawnStyle.MONSTER;
            case "boss", "bosses" -> SpawnStyle.BOSS;
            case "townsfolk" -> SpawnStyle.CITIZEN;
            default -> throw new ToolProblem("kind is monster, boss or townsfolk, as the NPCs panel lists them.");
        };
    }

    private static int rank(NpcRecord npc, ClientCatalog catalog, String wanted, String[] parts, int number) {
        if (npc.getId() == number || npc.getId() + SERVER_ID_OFFSET == number) {
            return 0;
        }
        var name = words(npc.name());
        var client = catalog.getNpc(npc.getId());
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

    private static String words(String name) {
        return name.strip().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
