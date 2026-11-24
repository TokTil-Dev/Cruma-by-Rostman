package com.rost.cruma.core.npc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class NpcAiCatalog {
    private final List<Ai> ais;
    private final Map<String, Ai> byName = new HashMap<>();
    private final List<Parameter> parameters;

    private NpcAiCatalog(List<Ai> ais, List<Parameter> parameters) {
        this.ais = List.copyOf(ais);
        this.parameters = List.copyOf(parameters);
        ais.forEach(ai -> byName.put(ai.name(), ai));
    }

    public record Value(String text, int npcs) {}

    public record Parameter(String name, int npcs, List<Value> values) {
        public String usual() {
            return values.isEmpty() ? "" : values.getFirst().text();
        }
    }

    public record Ai(String name, int npcs, List<Parameter> parameters) {}

    public enum ValueKind {
        NUMBER,
        SKILL,
        NAME,
        TEXT
    }

    public static NpcAiCatalog of(NpcDataFile file) {
        var counts = new HashMap<String, Integer>();
        var values = new HashMap<String, Map<String, Map<String, Integer>>>();
        var everywhere = new HashMap<String, Map<String, Integer>>();
        for (var record : file.getRecords()) {
            var value = record.getValue(NpcValues.AI);
            if (value == null) {
                continue;
            }
            NpcValues.Ai ai;
            try {
                ai = NpcValues.parseAi(value);
            } catch (IllegalArgumentException unreadable) {
                continue;
            }
            if (ai.name().isEmpty()) {
                continue;
            }
            counts.merge(ai.name(), 1, Integer::sum);
            var own = values.computeIfAbsent(ai.name(), unused -> new HashMap<>());
            for (var parameter : ai.parameters()) {
                own.computeIfAbsent(parameter.name(), unused -> new HashMap<>())
                        .merge(parameter.value(), 1, Integer::sum);
                everywhere
                        .computeIfAbsent(parameter.name(), unused -> new HashMap<>())
                        .merge(parameter.value(), 1, Integer::sum);
            }
        }
        var ais = new ArrayList<Ai>();
        counts.forEach((name, count) -> ais.add(new Ai(name, count, toParameters(values.get(name)))));
        ais.sort(Comparator.comparingInt(Ai::npcs).reversed().thenComparing(Ai::name));
        return new NpcAiCatalog(ais, toParameters(everywhere));
    }

    public static ValueKind kind(String name, String value) {
        var lower = name.toLowerCase(Locale.ROOT);
        if (value.startsWith("@") || lower.startsWith("skill") && lower.endsWith("_id")) {
            return ValueKind.SKILL;
        }
        if (value.startsWith("[")) {
            return ValueKind.NAME;
        }
        if (lower.endsWith("rate") || lower.contains("social") || value.matches("-?\\d+(\\.\\d+)?")) {
            return ValueKind.NUMBER;
        }
        return ValueKind.TEXT;
    }

    public List<Ai> getAis() {
        return ais;
    }

    public @Nullable Ai getAi(String name) {
        return byName.get(name);
    }

    public List<Parameter> getParameters() {
        return parameters;
    }

    public List<Parameter> suggestParameters(String ai, List<String> present) {
        var found = new ArrayList<Parameter>();
        var seen = new HashSet<>(present);
        var own = byName.get(ai);
        if (own != null) {
            for (var parameter : own.parameters()) {
                if (seen.add(parameter.name())) {
                    found.add(parameter);
                }
            }
        }
        for (var parameter : parameters) {
            if (seen.add(parameter.name())) {
                found.add(parameter);
            }
        }
        return found;
    }

    private static List<Parameter> toParameters(Map<String, Map<String, Integer>> seen) {
        var parameters = new ArrayList<Parameter>();
        seen.forEach((name, uses) -> {
            var list = new ArrayList<Value>();
            uses.forEach((text, count) -> list.add(new Value(text, count)));
            list.sort(Comparator.comparingInt(Value::npcs).reversed().thenComparing(Value::text));
            parameters.add(
                    new Parameter(name, list.stream().mapToInt(Value::npcs).sum(), list));
        });
        parameters.sort(Comparator.comparingInt(Parameter::npcs).reversed().thenComparing(Parameter::name));
        return parameters;
    }
}
