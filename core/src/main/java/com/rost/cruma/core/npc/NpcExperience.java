package com.rost.cruma.core.npc;

import com.rost.cruma.core.npc.NpcDataDocument.State;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import org.jetbrains.annotations.Nullable;

public final class NpcExperience {
    public static final String LEVEL = "level";
    public static final String EXP = "exp";
    public static final String EXP_RATE = "acquire_exp_rate";
    public static final String SP = "acquire_sp";

    private final Map<Integer, String> byLevel;
    private final boolean spRate;

    private NpcExperience(Map<Integer, String> byLevel, boolean spRate) {
        this.byLevel = Map.copyOf(byLevel);
        this.spRate = spRate;
    }

    public static NpcExperience of(NpcDataFile file) {
        var counts = new HashMap<Integer, Map<String, Integer>>();
        var fractions = 0;
        var points = 0;
        for (var record : file.getRecords()) {
            var sp = record.getValue(SP);
            if (sp != null) {
                try {
                    var value = Double.parseDouble(sp.strip());
                    if (value > 0) {
                        points++;
                        fractions += value < 1 ? 1 : 0;
                    }
                } catch (NumberFormatException notNumber) {
                }
            }
            var level = parseWhole(record.getValue(LEVEL));
            var exp = record.getValue(EXP);
            if (level != null && exp != null) {
                counts.computeIfAbsent(level, unused -> new HashMap<>()).merge(exp, 1, Integer::sum);
            }
        }
        var byLevel = new HashMap<Integer, String>();
        counts.forEach((level, values) -> byLevel.put(
                level,
                values.entrySet().stream()
                        .max(Entry.<String, Integer>comparingByValue().thenComparing(Entry.comparingByKey()))
                        .orElseThrow()
                        .getKey()));
        return new NpcExperience(byLevel, fractions * 2 > points);
    }

    public static double @Nullable [] computeReward(
            @Nullable String level, @Nullable String expRate, @Nullable String sp, boolean spRate) {
        var lv = parseWhole(level);
        if (lv == null) {
            return null;
        }
        try {
            var square = (double) lv * lv;
            return new double[] {
                expRate == null ? 0 : square * Double.parseDouble(expRate.strip()),
                sp == null ? 0 : (spRate ? square : 1) * Double.parseDouble(sp.strip())
            };
        } catch (NumberFormatException notNumber) {
            return null;
        }
    }

    public @Nullable String getExp(int level) {
        return byLevel.get(level);
    }

    public State withLevel(NpcDataDocument document, State from, int record, String level) {
        var next = document.with(from, record, LEVEL, level);
        var value = parseWhole(level);
        var exp = value == null ? null : getExp(value);
        if (exp != null && document.getFile().record(record).has(EXP)) {
            next = document.with(next, record, EXP, exp);
        }
        return next;
    }

    public boolean hasSpRate() {
        return spRate;
    }

    public double @Nullable [] computeReward(@Nullable String level, @Nullable String expRate, @Nullable String sp) {
        return computeReward(level, expRate, sp, spRate);
    }

    private static @Nullable Integer parseWhole(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException notWhole) {
            return null;
        }
    }
}
