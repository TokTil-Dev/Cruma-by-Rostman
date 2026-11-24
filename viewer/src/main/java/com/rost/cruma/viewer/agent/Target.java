package com.rost.cruma.viewer.agent;

import com.rost.cruma.viewer.config.Config.Viewer.Camera.Location;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public record Target(
        Kind kind, String text, float x, float y, @Nullable Float z) {
    public enum Kind {
        POINT,
        TILE,
        CAMERA,
        SELECTION,
        MARK,
        NAME
    }

    private static final Pattern POINT = Pattern.compile(
            "\\s*(-?\\d+(?:\\.\\d+)?)\\s*[, ]\\s*(-?\\d+(?:\\.\\d+)?)(?:\\s*[, ]\\s*(-?\\d+(?:\\.\\d+)?))?\\s*");
    private static final Pattern TILE = Pattern.compile("\\s*(\\d{1,2})_(\\d{1,2})\\s*");
    private static final Pattern MARK = Pattern.compile("\\s*#(\\d+)\\s*");
    private static final int TILE_SIZE = 32768;
    private static final int FIRST_TILE_X = 20;
    private static final int FIRST_TILE_Y = 18;

    public static Target parse(String text) {
        var point = POINT.matcher(text);
        if (point.matches()) {
            return new Target(
                    Kind.POINT,
                    text,
                    Float.parseFloat(point.group(1)),
                    Float.parseFloat(point.group(2)),
                    point.group(3) == null ? null : Float.valueOf(point.group(3)));
        }
        var tile = TILE.matcher(text);
        if (tile.matches()) {
            var x = (Integer.parseInt(tile.group(1)) - FIRST_TILE_X) * TILE_SIZE + TILE_SIZE / 2f;
            var y = (Integer.parseInt(tile.group(2)) - FIRST_TILE_Y) * TILE_SIZE + TILE_SIZE / 2f;
            return new Target(Kind.TILE, text, x, y, null);
        }
        if (MARK.matcher(text).matches()) {
            return new Target(Kind.MARK, text.strip(), 0, 0, null);
        }
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "camera" -> new Target(Kind.CAMERA, text, 0, 0, null);
            case "selection" -> new Target(Kind.SELECTION, text, 0, 0, null);
            default -> new Target(Kind.NAME, text.strip(), 0, 0, null);
        };
    }

    public static Optional<Location> place(List<Location> places, String name) {
        var wanted = name.strip().toLowerCase(Locale.ROOT);
        var words = wanted.split("\\s+");
        Location starts = null;
        Location holds = null;
        for (var place : places) {
            var candidate = place.name().toLowerCase(Locale.ROOT);
            if (candidate.equals(wanted)) {
                return Optional.of(place);
            }
            if (starts == null && candidate.startsWith(wanted)) {
                starts = place;
            }
            if (holds == null && List.of(words).stream().allMatch(candidate::contains)) {
                holds = place;
            }
        }
        return Optional.ofNullable(starts != null ? starts : holds);
    }
}
