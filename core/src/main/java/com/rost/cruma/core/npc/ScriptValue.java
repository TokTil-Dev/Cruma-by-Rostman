package com.rost.cruma.core.npc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class ScriptValue {
    private ScriptValue() {}

    public static Object parse(String text) {
        var reader = new Parser(text.strip());
        var value = reader.parseValue();
        if (reader.at != reader.text.length()) {
            throw new IllegalArgumentException(
                    "Unexpected '" + reader.text.charAt(reader.at) + "' at " + reader.at + " in " + text);
        }
        return value;
    }

    public static List<Object> list(Object value) {
        if (value instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            var elements = (List<Object>) list;
            return elements;
        }
        throw new IllegalArgumentException("Expected a list, found " + value);
    }

    public static String atom(Object value) {
        if (value instanceof String atom) {
            return atom;
        }
        throw new IllegalArgumentException("Expected a value, found a list");
    }

    public static String bracketed(Object value) {
        var atom = atom(value);
        if (atom.length() < 2 || atom.charAt(0) != '[' || atom.charAt(atom.length() - 1) != ']') {
            throw new IllegalArgumentException("Expected a [name], found " + atom);
        }
        return atom.substring(1, atom.length() - 1);
    }

    public static double number(Object value) {
        try {
            return Double.parseDouble(atom(value));
        } catch (NumberFormatException notNumber) {
            throw new IllegalArgumentException("Expected a number, found " + value);
        }
    }

    public static long whole(Object value) {
        var number = number(value);
        if (number != Math.rint(number)) {
            throw new IllegalArgumentException("Expected a whole number, found " + value);
        }
        return (long) number;
    }

    public static String number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static final class Parser {
        private final String text;
        private int at;

        Parser(String text) {
            this.text = text;
        }

        Object parseValue() {
            if (at < text.length() && text.charAt(at) == '{') {
                at++;
                var elements = new ArrayList<Object>();
                if (at < text.length() && text.charAt(at) == '}') {
                    at++;
                    return elements;
                }
                while (true) {
                    var element = parseValue();
                    if (!(element instanceof String atom
                            && atom.isEmpty()
                            && at < text.length()
                            && text.charAt(at) == '}')) {
                        elements.add(element);
                    }
                    if (at >= text.length()) {
                        throw new IllegalArgumentException("Missing '}' in " + text);
                    }
                    var c = text.charAt(at++);
                    if (c == '}') {
                        return elements;
                    }
                    if (c != ';') {
                        throw new IllegalArgumentException("Unexpected '" + c + "' at " + (at - 1) + " in " + text);
                    }
                }
            }
            var start = at;
            var depth = 0;
            while (at < text.length()) {
                var c = text.charAt(at);
                if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth--;
                } else if (depth == 0 && (c == ';' || c == '}' || c == '{')) {
                    break;
                }
                at++;
            }
            return text.substring(start, at);
        }
    }
}
