package com.rost.cruma.core.npc;

import java.util.ArrayList;
import java.util.List;

public final class NpcValues {
    public static final String SKILLS = "skill_list";

    public static final String AI = "npc_ai";

    private NpcValues() {}

    public record AiParameter(String name, String value) {
        public String skill() {
            return value.startsWith("@") ? value.substring(1) : "";
        }
    }

    public record Ai(String name, List<AiParameter> parameters) {
        public Ai {
            parameters = List.copyOf(parameters);
        }

        public Ai withParameter(int index, AiParameter parameter) {
            var copy = new ArrayList<>(parameters);
            if (index == copy.size()) {
                copy.add(parameter);
            } else {
                copy.set(index, parameter);
            }
            return new Ai(name, copy);
        }

        public Ai withoutParameter(int index) {
            var copy = new ArrayList<>(parameters);
            copy.remove(index);
            return new Ai(name, copy);
        }

        public Ai withName(String value) {
            return new Ai(value, parameters);
        }
    }

    public static List<String> parseSkills(String value) {
        var skills = new ArrayList<String>();
        for (var element : ScriptValue.list(ScriptValue.parse(value))) {
            var atom = ScriptValue.atom(element);
            skills.add(atom.startsWith("@") ? atom.substring(1) : atom);
        }
        return skills;
    }

    public static String formatSkills(List<String> skills) {
        var out = new StringBuilder("{");
        for (var i = 0; i < skills.size(); i++) {
            if (i > 0) {
                out.append(';');
            }
            out.append('@').append(skills.get(i));
        }
        return out.append('}').toString();
    }

    public static String parseItem(String value) {
        var trimmed = value.strip();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return trimmed.substring(1, trimmed.length() - 1).strip();
        }
        return trimmed;
    }

    public static String formatItem(String name) {
        return "[" + name + "]";
    }

    public static Ai parseAi(String value) {
        var elements = ScriptValue.list(ScriptValue.parse(value));
        if (elements.isEmpty()) {
            return new Ai("", List.of());
        }
        var name = ScriptValue.bracketed(elements.getFirst());
        var parameters = new ArrayList<AiParameter>();
        for (var element : elements.subList(1, elements.size())) {
            var setting = ScriptValue.list(element);
            if (setting.size() != 1) {
                throw new IllegalArgumentException("An AI setting needs {[name]=value}: " + element);
            }
            var atom = ScriptValue.atom(setting.getFirst());
            var close = atom.indexOf(']');
            var equals = atom.indexOf('=', close + 1);
            if (!atom.startsWith("[")
                    || close < 0
                    || equals < 0
                    || !atom.substring(close + 1, equals).isBlank()) {
                throw new IllegalArgumentException("An AI setting needs {[name]=value}: " + atom);
            }
            parameters.add(new AiParameter(
                    atom.substring(1, close), atom.substring(equals + 1).strip()));
        }
        return new Ai(name, parameters);
    }

    public static String formatAi(Ai ai) {
        var out = new StringBuilder("{[").append(ai.name()).append(']');
        for (var parameter : ai.parameters()) {
            out.append(";{[")
                    .append(parameter.name())
                    .append("]=")
                    .append(parameter.value())
                    .append('}');
        }
        return out.append('}').toString();
    }
}
