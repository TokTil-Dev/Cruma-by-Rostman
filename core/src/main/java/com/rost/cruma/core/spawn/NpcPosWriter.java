package com.rost.cruma.core.spawn;

import com.rost.cruma.core.spawn.NpcPosDocument.State;
import com.rost.cruma.core.spawn.SpawnNpc.Spans;
import com.rost.cruma.core.spawn.SpawnPosition.Anywhere;
import com.rost.cruma.core.spawn.SpawnPosition.Fixed;
import com.rost.cruma.core.spawn.SpawnPosition.Weighted;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class NpcPosWriter {
    private final String source;
    private final String lineEnding;
    private final List<Change> changes = new ArrayList<>();

    private NpcPosWriter(SpawnText text) {
        this(text.text(), text.lineEnding());
    }

    private NpcPosWriter(String source, String lineEnding) {
        this.source = source;
        this.lineEnding = lineEnding;
    }

    private record Change(int start, int end, String text, int order) {}

    public static SpawnText write(NpcPosFile file, State state) {
        var writer = new NpcPosWriter(file.getSource());
        writer.writeTerritories(file, state);
        writer.writeMakers(file, state);
        return file.getSource().withText(writer.apply());
    }

    public static String formatPoints(List<TerritoryPoint> points) {
        return points.stream()
                .map(point -> "{" + point.x() + ";" + point.y() + ";" + point.bottom() + ";" + point.top() + "}")
                .collect(Collectors.joining(";", "{", "}"));
    }

    public static String formatPosition(SpawnPosition position) {
        return "pos="
                + switch (position) {
                    case Anywhere _ -> "anywhere";
                    case Fixed fixed ->
                        "{" + fixed.x() + ";" + fixed.y() + ";" + fixed.z() + ";" + fixed.heading() + "}";
                    case Weighted weighted ->
                        weighted.points().stream()
                                .map(point -> "{"
                                        + point.x()
                                        + ";"
                                        + point.y()
                                        + ";"
                                        + point.z()
                                        + ";"
                                        + point.heading()
                                        + ";"
                                        + point.percent()
                                        + "%}")
                                .collect(Collectors.joining(";", "{", "}"));
                };
    }

    public static String territory(SpawnTerritory territory) {
        return "territory_begin\t[" + territory.name() + "]\t" + formatPoints(territory.points()) + "\tterritory_end";
    }

    public static String npc(SpawnNpc npc, boolean extended) {
        var text = new StringBuilder(extended ? "npc_ex_begin" : "npc_begin");
        text.append("\t[").append(npc.npcName()).append("]\t").append(formatPosition(npc.position()));
        text.append("\ttotal=").append(npc.total());
        if (!npc.respawn().isEmpty()) {
            text.append("\trespawn=").append(npc.respawn());
        }
        if (!npc.respawnRandom().isEmpty()) {
            text.append("\trespawn_rand=").append(npc.respawnRandom());
        }
        if (!npc.nickname().isEmpty()) {
            text.append("\tnickname=[").append(npc.nickname()).append(']');
        }
        for (var setting : npc.extra()) {
            text.append('\t').append(setting);
        }
        return text.append(extended ? "\tnpc_ex_end" : "\tnpc_end").toString();
    }

    public static String formatMaker(SpawnMaker maker, String lineEnding) {
        return new NpcPosWriter("", lineEnding).formatMaker(maker);
    }

    private void writeTerritories(NpcPosFile file, State state) {
        var base = file.getTerritories();
        for (var i = 0; i < base.size(); i++) {
            var before = base.get(i);
            var after = i < state.territories().size() ? state.territories().get(i) : null;
            if (after == null) {
                removeLine(before.span());
                continue;
            }
            if (!after.name().equals(before.name())) {
                replace(before.nameSpan(), "[" + after.name() + "]");
            }
            if (!after.points().equals(before.points())) {
                replace(before.pointsSpan(), formatPoints(after.points()));
            }
        }
        var added = new StringBuilder();
        for (var i = base.size(); i < state.territories().size(); i++) {
            var territory = state.territories().get(i);
            if (territory != null) {
                added.append(territory(territory)).append(lineEnding);
            }
        }
        appendBlock(added.toString(), 0);
    }

    private void writeMakers(NpcPosFile file, State state) {
        var base = file.getMakers();
        for (var i = 0; i < base.size(); i++) {
            var before = base.get(i);
            var after = i < state.makers().size() ? state.makers().get(i) : null;
            if (after == null) {
                removeLine(before.span());
                continue;
            }
            if (after.maximumNpc() != before.maximumNpc()) {
                if (before.maximumNpcSpan().isPresent()) {
                    replace(before.maximumNpcSpan(), "maximum_npc=" + after.maximumNpc());
                } else {
                    insert(before.header().end(), "\tmaximum_npc=" + after.maximumNpc(), 0);
                }
            }
            if (!after.territoryNames().equals(before.territoryNames())
                    && before.territoriesSpan().isPresent()) {
                replace(
                        before.territoriesSpan(),
                        after.territoryNames().stream()
                                .map(name -> "[" + name + "]")
                                .collect(Collectors.joining(";")));
            }
            if (!after.bannedTerritoryNames().equals(before.bannedTerritoryNames())) {
                setting(
                        before,
                        before.bannedSpan(),
                        after.bannedTerritoryNames().isEmpty()
                                ? ""
                                : "banned_territory="
                                        + after.bannedTerritoryNames().stream()
                                                .map(name -> "[" + name + "]")
                                                .collect(Collectors.joining(";", "{", "}")));
            }
            if (!after.name().equals(before.name())) {
                setting(before, before.nameSpan(), after.name().isEmpty() ? "" : "name=[" + after.name() + "]");
            }
            if (!after.ai().equals(before.ai())) {
                setting(before, before.aiSpan(), "ai=[" + after.ai() + "]");
            }
            if (!after.aiParameters().equals(before.aiParameters())) {
                setting(
                        before,
                        before.aiParametersSpan(),
                        after.aiParameters().isEmpty() ? "" : "ai_parameters=" + after.aiParameters());
            }
            writeNpcs(before, after);
        }
        var added = new StringBuilder();
        for (var i = base.size(); i < state.makers().size(); i++) {
            var maker = state.makers().get(i);
            if (maker != null) {
                added.append(formatMaker(maker));
            }
        }
        appendBlock(added.toString(), 1);
    }

    private void writeNpcs(SpawnMaker before, SpawnMaker after) {
        var kept = new HashMap<Integer, SpawnNpc>();
        var inserted = new ArrayList<SpawnNpc>();
        for (var npc : after.npcs()) {
            if (npc.spans().entry().isPresent()) {
                kept.put(npc.index(), npc);
            } else {
                inserted.add(npc);
            }
        }
        for (var original : before.npcs()) {
            var edited = kept.get(original.index());
            if (edited == null) {
                removeLine(original.spans().entry());
                continue;
            }
            npc(original, edited);
        }
        if (inserted.isEmpty()) {
            return;
        }
        var text = inserted.stream().map(npc -> npc(npc, after.extended())).collect(Collectors.joining(lineEnding));
        var end = before.end();
        if (!end.isPresent()) {
            end = new SourceSpan(before.span().end(), before.span().end());
        }
        var lineStart = lineStart(end.start());
        if (source.substring(lineStart, end.start()).isBlank()) {
            insert(lineStart, text + lineEnding, 2);
        } else {
            insert(end.start(), lineEnding + text + lineEnding, 2);
        }
    }

    private void npc(SpawnNpc before, SpawnNpc after) {
        var spans = before.spans();
        if (!after.npcName().equals(before.npcName())) {
            replace(spans.name(), "[" + after.npcName() + "]");
        }
        if (!after.position().equals(before.position())) {
            setting(spans.position(), spans, formatPosition(after.position()));
        }
        if (after.total() != before.total()) {
            setting(spans.total(), spans, "total=" + after.total());
        }
        if (!after.respawn().equals(before.respawn())) {
            setting(spans.respawn(), spans, after.respawn().isEmpty() ? "" : "respawn=" + after.respawn());
        }
        if (!after.respawnRandom().equals(before.respawnRandom())) {
            setting(
                    spans.respawnRandom(),
                    spans,
                    after.respawnRandom().isEmpty() ? "" : "respawn_rand=" + after.respawnRandom());
        }
    }

    private void setting(SourceSpan span, Spans spans, String text) {
        if (span.isPresent()) {
            if (text.isEmpty()) {
                var start = span.start();
                while (start > 0 && (source.charAt(start - 1) == '\t' || source.charAt(start - 1) == ' ')) {
                    start--;
                }
                replace(new SourceSpan(start, span.end()), "");
            } else {
                replace(span, text);
            }
        } else if (!text.isEmpty() && spans.end().isPresent()) {
            insert(spans.end().start(), text + "\t", 0);
        }
    }

    private void appendBlock(String text, int order) {
        if (text.isEmpty()) {
            return;
        }
        var prefix = source.isEmpty() || source.endsWith("\n") ? "" : lineEnding;
        insert(source.length(), prefix + text, 10 + order);
    }

    private void setting(SpawnMaker maker, SourceSpan span, String text) {
        if (span.isPresent()) {
            var start = span.start();
            if (text.isEmpty()) {
                while (start > 0 && (source.charAt(start - 1) == '\t' || source.charAt(start - 1) == ' ')) {
                    start--;
                }
            }
            replace(new SourceSpan(start, span.end()), text);
        } else if (!text.isEmpty()) {
            insert(maker.header().end(), "\t" + text, 1);
        }
    }

    private void removeLine(SourceSpan span) {
        var start = lineStart(span.start());
        var end = span.end();
        var lineEnd = source.indexOf('\n', end);
        lineEnd = lineEnd < 0 ? source.length() : lineEnd + 1;
        var whole = source.substring(start, span.start()).isBlank()
                && source.substring(end, lineEnd).isBlank();
        replace(whole ? new SourceSpan(start, lineEnd) : span, "");
    }

    private int lineStart(int offset) {
        return source.lastIndexOf('\n', offset - 1) + 1;
    }

    private void replace(SourceSpan span, String text) {
        changes.add(new Change(span.start(), span.end(), text, 0));
    }

    private void insert(int offset, String text, int order) {
        changes.add(new Change(offset, offset, text, order));
    }

    private String apply() {
        if (changes.isEmpty()) {
            return source;
        }
        changes.sort(Comparator.comparingInt(Change::start)
                .thenComparingInt(Change::end)
                .thenComparingInt(Change::order));
        var result = new StringBuilder(source.length() + 256);
        var cursor = 0;
        for (var change : changes) {
            if (change.start() < cursor) {
                continue;
            }
            result.append(source, cursor, change.start()).append(change.text());
            cursor = change.end();
        }
        return result.append(source, cursor, source.length()).toString();
    }

    private String formatMaker(SpawnMaker maker) {
        Objects.requireNonNull(maker);
        var text = new StringBuilder(maker.extended() ? "npcmaker_ex_begin" : "npcmaker_begin");
        text.append('\t')
                .append(maker.territoryNames().stream()
                        .map(name -> "[" + name + "]")
                        .collect(Collectors.joining(";")));
        if (!maker.bannedTerritoryNames().isEmpty()) {
            text.append("\tbanned_territory=")
                    .append(maker.bannedTerritoryNames().stream()
                            .map(name -> "[" + name + "]")
                            .collect(Collectors.joining(";", "{", "}")));
        }
        for (var setting : maker.extra()) {
            if (!maker.extended() && setting.startsWith("event_name")) {
                text.append('\t').append(setting);
            }
        }
        if (maker.extended()) {
            text.append("\tname=[")
                    .append(maker.name())
                    .append("]\tai=[")
                    .append(maker.ai().isEmpty() ? "default_maker" : maker.ai())
                    .append(']');
            if (!maker.aiParameters().isEmpty()) {
                text.append("\tai_parameters=").append(maker.aiParameters());
            }
        } else {
            if (!maker.initialSpawn().isEmpty()) {
                text.append("\tinitial_spawn=").append(maker.initialSpawn());
            }
            if (!maker.spawnTime().isEmpty()) {
                text.append("\tspawn_time=").append(maker.spawnTime());
            }
        }
        var maximum = maker.maximumNpc() >= 0
                ? maker.maximumNpc()
                : maker.npcs().stream().mapToInt(SpawnNpc::total).sum();
        text.append("\tmaximum_npc=").append(maximum);
        if (maker.flying()) {
            text.append("\tflying=1");
        }
        for (var setting : maker.extra()) {
            if (maker.extended() || !setting.startsWith("event_name")) {
                text.append('\t').append(setting);
            }
        }
        text.append(lineEnding);
        for (var npc : maker.npcs()) {
            text.append(npc(npc, maker.extended())).append(lineEnding);
        }
        return text.append(maker.extended() ? "npcmaker_ex_end" : "npcmaker_end")
                .append(lineEnding)
                .toString();
    }
}
