package com.rost.cruma.viewer.editing;

import java.util.Locale;

final class RelativeNumber {
    static final String RULE =
            "Type a number to set it, also a negative one such as -3400. +32, -=10, *2 or /2 change the current value.";

    private RelativeNumber() {}

    static float apply(String text, float current) {
        var typed = text.strip().replace(',', '.');
        if (typed.isEmpty()) {
            throw new IllegalArgumentException(RULE);
        }
        var operator = typed.charAt(0);
        var rest = typed.substring(1).strip();
        var compound = rest.startsWith("=");
        if (compound) {
            rest = rest.substring(1).strip();
        }
        char operation;
        String number;
        if (operator == '+' || operator == '*' || operator == '/' || operator == '-' && compound) {
            operation = operator;
            number = rest;
        } else if (operator == '-') {
            operation = '=';
            number = "-" + rest;
        } else {
            operation = '=';
            number = typed;
        }
        float value;
        try {
            value = Float.parseFloat(number.toLowerCase(Locale.ROOT));
        } catch (NumberFormatException notNumber) {
            throw new IllegalArgumentException(RULE);
        }
        var result = switch (operation) {
            case '+' -> current + value;
            case '-' -> current - value;
            case '*' -> current * value;
            case '/' -> value == 0 ? Float.NaN : current / value;
            default -> value;
        };
        if (!Float.isFinite(result) || !Float.isFinite(value)) {
            throw new IllegalArgumentException(RULE);
        }
        return result;
    }

    static String format(float value) {
        var text = String.format(Locale.ROOT, "%.3f", value);
        text = text.contains(".") ? text.replaceAll("0+$", "").replaceAll("\\.$", "") : text;
        return text.equals("-0") ? "0" : text;
    }
}
