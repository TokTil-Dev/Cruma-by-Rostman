package com.rost.cruma.core.npc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class NpcStatReference {
    public static final int MINIMUM = 5;

    public static final int REACH = 5;

    public static final double SPREAD = 2;

    private final NpcDataFile file;
    private final Map<String, Map<Long, double[]>> byKey = new HashMap<>();

    private NpcStatReference(NpcDataFile file) {
        this.file = file;
    }

    public record Band(
            NpcKind kind,
            int fromLevel,
            int toLevel,
            int count,
            double low,
            double q1,
            double median,
            double q3,
            double high) {
        public boolean isUnusual(double value) {
            return value < low || value > high;
        }

        public String levels() {
            return fromLevel == toLevel ? "Lv " + fromLevel : "Lv " + fromLevel + "–" + toLevel;
        }
    }

    public static NpcStatReference of(NpcDataFile file) {
        return new NpcStatReference(file);
    }

    static Band computeBand(NpcKind kind, int fromLevel, int toLevel, double[] sorted) {
        var q1 = quantile(sorted, .25);
        var median = quantile(sorted, .5);
        var q3 = quantile(sorted, .75);
        var spread = q3 - q1;
        var margin = spread > 0 ? spread * SPREAD : Math.abs(median) * .1;
        return new Band(kind, Math.max(1, fromLevel), toLevel, sorted.length, q1 - margin, q1, median, q3, q3 + margin);
    }

    static double quantile(double[] sorted, double fraction) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        var at = fraction * (sorted.length - 1);
        var below = (int) Math.floor(at);
        var above = Math.min(sorted.length - 1, below + 1);
        return sorted[below] + (sorted[above] - sorted[below]) * (at - below);
    }

    public @Nullable Band findBand(NpcKind kind, int level, String key) {
        var values = byKey.computeIfAbsent(key, this::collect);
        var band = kind.band();
        var collected = new ArrayList<double[]>();
        var count = 0;
        for (var reach = 0; reach <= REACH; reach++) {
            collected.clear();
            count = 0;
            for (var at = level - reach; at <= level + reach; at++) {
                var found = values.get(slotKey(band, at));
                if (found != null) {
                    collected.add(found);
                    count += found.length;
                }
            }
            if (count >= MINIMUM) {
                return computeBand(band, level - reach, level + reach, mergeSorted(collected, count));
            }
        }
        return null;
    }

    private Map<Long, double[]> collect(String key) {
        var lists = new HashMap<Long, List<Double>>();
        for (var record : file.getRecords()) {
            var level = parseNumber(record.getValue(NpcExperience.LEVEL));
            var value = parseNumber(record.getValue(key));
            if (level == null || value == null) {
                continue;
            }
            lists.computeIfAbsent(
                            slotKey(NpcKind.ofType(record.getType()).band(), (int) Math.round(level)),
                            unused -> new ArrayList<>())
                    .add(value);
        }
        var sorted = new HashMap<Long, double[]>();
        lists.forEach((slot, list) -> {
            var array = new double[list.size()];
            for (var i = 0; i < array.length; i++) {
                array[i] = list.get(i);
            }
            Arrays.sort(array);
            sorted.put(slot, array);
        });
        return sorted;
    }

    private static double[] mergeSorted(List<double[]> parts, int count) {
        var all = new double[count];
        var at = 0;
        for (var part : parts) {
            System.arraycopy(part, 0, all, at, part.length);
            at += part.length;
        }
        Arrays.sort(all);
        return all;
    }

    private static long slotKey(NpcKind kind, int level) {
        return (long) kind.ordinal() << 32 | (level & 0xffffffffL);
    }

    private static @Nullable Double parseNumber(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            var parsed = Double.parseDouble(value.strip());
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException notNumber) {
            return null;
        }
    }
}
