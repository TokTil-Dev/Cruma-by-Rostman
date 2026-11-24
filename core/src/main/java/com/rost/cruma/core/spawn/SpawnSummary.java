package com.rost.cruma.core.spawn;

import com.rost.cruma.core.spawn.NpcPosDocument.State;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class SpawnSummary {
    public static final int[] BAND_TOPS = {20, 40, 60, 76, Integer.MAX_VALUE};

    public static final String[] BAND_LABELS = {"1–20", "21–40", "41–60", "61–76", "77+"};

    private SpawnSummary() {}

    public record Event(boolean spawnTime, String name, List<Integer> makers) {
        public Event {
            makers = List.copyOf(makers);
        }
    }

    public record Entry(int maker, int slot) {}

    public record Levels(int lowest, int highest, double mean) {
        public String label() {
            return lowest == highest ? "Lv " + lowest : "Lv " + lowest + "–" + highest;
        }
    }

    public static List<Event> collectEvents(State state) {
        var events = new LinkedHashMap<String, List<Integer>>();
        var times = new LinkedHashMap<String, List<Integer>>();
        for (var i = 0; i < state.makers().size(); i++) {
            var maker = state.makers().get(i);
            if (maker == null) {
                continue;
            }
            var event = maker.eventName();
            if (!event.isEmpty()) {
                events.computeIfAbsent(event, unused -> new ArrayList<>()).add(i);
            }
            if (!maker.spawnTime().isEmpty()) {
                times.computeIfAbsent(maker.spawnTimeKind(), unused -> new ArrayList<>())
                        .add(i);
            }
        }
        var result = new ArrayList<Event>();
        events.forEach((name, makers) -> result.add(new Event(false, name, makers)));
        result.sort(
                Comparator.comparingInt((Event event) -> -event.makers().size()).thenComparing(Event::name));
        var timed = new ArrayList<Event>();
        times.forEach((name, makers) -> timed.add(new Event(true, name, makers)));
        timed.sort(
                Comparator.comparingInt((Event event) -> -event.makers().size()).thenComparing(Event::name));
        result.addAll(timed);
        return result;
    }

    public static List<Entry> unknownNpcs(State state, NpcDataTable npcs) {
        var unknown = new ArrayList<Entry>();
        if (npcs == null || npcs.size() == 0) {
            return unknown;
        }
        for (var i = 0; i < state.makers().size(); i++) {
            var maker = state.makers().get(i);
            if (maker == null) {
                continue;
            }
            for (var slot = 0; slot < maker.npcs().size(); slot++) {
                if (npcs.getNpc(maker.npcs().get(slot).npcName()) == null) {
                    unknown.add(new Entry(i, slot));
                }
            }
        }
        return unknown;
    }

    public static List<String> respawnPresets(State state, int count) {
        var uses = new HashMap<String, Integer>();
        for (var maker : state.makers()) {
            if (maker == null) {
                continue;
            }
            for (var npc : maker.npcs()) {
                if (!npc.respawn().isEmpty()) {
                    uses.merge(npc.respawn(), 1, Integer::sum);
                }
            }
        }
        return uses.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue()
                        .reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(count)
                .map(Map.Entry::getKey)
                .toList();
    }

    public static @Nullable Levels computeLevels(SpawnMaker maker, NpcDataTable npcs) {
        var lowest = Integer.MAX_VALUE;
        var highest = Integer.MIN_VALUE;
        var sum = 0.0;
        var count = 0;
        for (var npc : maker.npcs()) {
            var info = npcs == null ? null : npcs.getNpc(npc.npcName());
            if (info == null) {
                continue;
            }
            lowest = Math.min(lowest, info.level());
            highest = Math.max(highest, info.level());
            var weight = Math.max(1, npc.total());
            sum += info.level() * (double) weight;
            count += weight;
        }
        return count == 0 ? null : new Levels(lowest, highest, sum / count);
    }

    public static int levelBand(double level) {
        for (var i = 0; i < BAND_TOPS.length; i++) {
            if (Math.round(level) <= BAND_TOPS[i]) {
                return i;
            }
        }
        return BAND_TOPS.length - 1;
    }
}
