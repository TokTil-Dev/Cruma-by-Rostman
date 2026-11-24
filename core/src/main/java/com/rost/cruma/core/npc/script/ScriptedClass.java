package com.rost.cruma.core.npc.script;

import com.rost.cruma.core.npc.script.ScriptTree.Function;
import com.rost.cruma.core.npc.script.ScriptTree.Variable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class ScriptedClass {
    private final String name;
    private final List<ClassScript> chain;
    private final Map<String, Variable> variables = new HashMap<>();
    private final Map<String, Object> defaults = new HashMap<>();

    private ScriptedClass(String name, List<ClassScript> chain, List<Source> sources) {
        this.name = name;
        this.chain = List.copyOf(chain);
        for (var index = chain.size() - 1; index >= 0; index--) {
            variables.putAll(chain.get(index).getVariables());
        }
        for (var index = sources.size() - 1; index >= 0; index--) {
            for (var value : sources.get(index).defaults()) {
                var key = value.name().toLowerCase(Locale.ROOT);
                var variable = variables.get(key);
                if (variable == null) {
                    continue;
                }
                if (variable.size() > 0) {
                    var array = (Object[]) defaults.computeIfAbsent(key, _ -> new Object[variable.size()]);
                    if (value.index() >= 0 && value.index() < array.length) {
                        array[value.index()] = value.value();
                    }
                } else if (value.index() == 0) {
                    defaults.put(key, value.value());
                }
            }
        }
    }

    public record Source(String name, String text, List<Default> defaults) {}

    public record Default(String name, int index, Object value) {}

    public static @Nullable ScriptedClass of(List<Source> sources) {
        if (sources.isEmpty()) {
            return null;
        }
        var chain = new ArrayList<ClassScript>();
        for (var source : sources) {
            var dot = source.name().indexOf('.');
            chain.add(ClassScript.parse(source.name().substring(dot + 1), source.text()));
        }
        return new ScriptedClass(sources.getFirst().name(), chain, sources);
    }

    public String name() {
        return name;
    }

    public Set<String> getEffects() {
        var effects = new LinkedHashSet<String>();
        for (var script : chain) {
            effects.addAll(script.getClassLiterals());
        }
        return effects;
    }

    public boolean declares(String function) {
        for (var script : chain) {
            if (script.declares(function)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> getUnsupported() {
        return ScriptCheck.unsupported(this);
    }

    List<ClassScript> getChain() {
        return chain;
    }

    @Nullable
    Variable getVariable(String key) {
        return variables.get(key);
    }

    Map<String, Variable> getVariables() {
        return variables;
    }

    @Nullable
    Object getDefaultValue(String key) {
        return defaults.get(key);
    }

    @Nullable
    Found find(String key, int level) {
        for (var index = Math.max(0, level); index < chain.size(); index++) {
            var function = chain.get(index).getFunction(key);
            if (function != null) {
                return new Found(function, index);
            }
        }
        return null;
    }

    record Found(Function function, int level) {}
}
