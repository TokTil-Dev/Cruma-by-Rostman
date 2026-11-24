package com.rost.cruma.core.spawn;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public final class AiParameters {
    private AiParameters() {}

    public static Map<String, String> parse(String raw) {
        var parameters = new LinkedHashMap<String, String>();
        var text = raw.strip();
        if (text.startsWith("{") && text.endsWith("}")) {
            text = text.substring(1, text.length() - 1);
        }
        var depth = 0;
        var start = 0;
        for (var i = 0; i <= text.length(); i++) {
            var c = i < text.length() ? text.charAt(i) : ';';
            if (c == '{' || c == '[' || c == '(') {
                depth++;
            } else if (c == '}' || c == ']' || c == ')') {
                depth--;
            } else if (c == ';' && depth == 0) {
                put(parameters, text.substring(start, i));
                start = i + 1;
            }
        }
        return parameters;
    }

    public static String format(Map<String, String> parameters) {
        if (parameters.isEmpty()) {
            return "";
        }
        return parameters.entrySet().stream()
                .map(entry -> "[" + entry.getKey() + "]=" + entry.getValue())
                .collect(Collectors.joining(";", "{", "}"));
    }

    public static String formatValue(String typed) {
        var text = typed.strip();
        if (text.isEmpty() || text.matches("-?\\d+(\\.\\d+)?") || text.startsWith("[") || text.startsWith("{")) {
            return text;
        }
        return "[" + text + "]";
    }

    private static void put(Map<String, String> parameters, String pair) {
        var equals = pair.indexOf('=');
        if (equals < 0) {
            return;
        }
        var name = pair.substring(0, equals).strip();
        if (name.startsWith("[") && name.endsWith("]")) {
            name = name.substring(1, name.length() - 1);
        }
        if (!name.isEmpty()) {
            parameters.put(name, pair.substring(equals + 1).strip());
        }
    }
}
