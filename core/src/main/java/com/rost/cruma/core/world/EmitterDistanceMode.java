package com.rost.cruma.core.world;

import java.util.Locale;

public enum EmitterDistanceMode {
    AUTHORED,
    CUSTOM;

    public static EmitterDistanceMode parse(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "authored" -> AUTHORED;
            case "custom" -> CUSTOM;
            default ->
                throw new IllegalArgumentException(
                        "emitter_distance_mode must be \"authored\" or \"custom\", got \"" + value + "\"");
        };
    }

    public static EmitterDistanceMode of(int index) {
        var modes = values();
        return index >= 0 && index < modes.length ? modes[index] : AUTHORED;
    }
}
