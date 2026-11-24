package com.rost.cruma.core.spawn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MakerAiCatalog {
    private final List<Ai> ais;
    private final Map<String, Ai> byName = new HashMap<>();

    private MakerAiCatalog(List<Ai> ais) {
        this.ais = List.copyOf(ais);
        ais.forEach(ai -> byName.put(ai.name(), ai));
    }

    public record Value(String text, int makers) {}

    public record Parameter(String name, int makers, List<Value> values) {
        public String usual() {
            return values.getFirst().text();
        }
    }

    public record Ai(String name, int makers, List<Parameter> parameters) {
        public Parameter parameter(String parameterName) {
            for (var parameter : parameters) {
                if (parameter.name().equals(parameterName)) {
                    return parameter;
                }
            }
            return null;
        }
    }

    public static MakerAiCatalog of(List<SpawnMaker> makers) {
        var counts = new HashMap<String, Integer>();
        var values = new HashMap<String, Map<String, Map<String, Integer>>>();
        for (var maker : makers) {
            if (maker == null || !maker.extended() || maker.ai().isEmpty()) {
                continue;
            }
            counts.merge(maker.ai(), 1, Integer::sum);
            var parameters = values.computeIfAbsent(maker.ai(), ai -> new HashMap<>());
            AiParameters.parse(maker.aiParameters())
                    .forEach((name, value) -> parameters
                            .computeIfAbsent(name, key -> new HashMap<>())
                            .merge(value, 1, Integer::sum));
        }
        var ais = new ArrayList<Ai>();
        counts.forEach((name, count) -> {
            var parameters = new ArrayList<Parameter>();
            values.get(name).forEach((parameter, seen) -> {
                var list = new ArrayList<Value>();
                seen.forEach((text, uses) -> list.add(new Value(text, uses)));
                list.sort(Comparator.comparingInt(Value::makers).reversed().thenComparing(Value::text));
                parameters.add(new Parameter(
                        parameter, list.stream().mapToInt(Value::makers).sum(), list));
            });
            parameters.sort(
                    Comparator.comparingInt(Parameter::makers).reversed().thenComparing(Parameter::name));
            ais.add(new Ai(name, count, parameters));
        });
        ais.sort(Comparator.comparingInt(Ai::makers).reversed().thenComparing(Ai::name));
        return new MakerAiCatalog(ais);
    }

    public List<Ai> getAis() {
        return ais;
    }

    public Ai getAi(String name) {
        return byName.get(name);
    }
}
