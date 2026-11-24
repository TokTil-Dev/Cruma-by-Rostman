package com.rost.cruma.viewer.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class ShortcutKeys {
    private static final Set<String> NAMED = Set.of(
            "shift",
            "ctrl",
            "cmd",
            "alt",
            "esc",
            "escape",
            "enter",
            "tab",
            "space",
            "delete",
            "del",
            "backspace",
            "end",
            "home",
            "pgup",
            "pgdn",
            "up",
            "down",
            "left",
            "right",
            "arrows",
            "w a s d");
    private static final Pattern KEY = Pattern.compile("F\\d{1,2}|\\d ?- ?\\d|\\S");

    private ShortcutKeys() {}

    static List<Piece> parse(String keys) {
        var pieces = new ArrayList<Piece>();
        for (var way : keys.split(" / ")) {
            if (!pieces.isEmpty()) {
                pieces.add(new Piece("/", Kind.JOIN));
            }
            var text = way.strip();
            if (isKey(text)) {
                pieces.add(new Piece(text, Kind.KEY));
                continue;
            }
            var held = new StringBuilder();
            var first = true;
            for (var part : text.split("\\+")) {
                var name = part.strip();
                if (isKey(name)) {
                    held.append(held.isEmpty() ? "" : "+").append(name);
                    continue;
                }
                if (!held.isEmpty()) {
                    pieces.add(new Piece(held.toString(), Kind.KEY));
                    held.setLength(0);
                    first = false;
                }
                if (!first) {
                    pieces.add(new Piece("+", Kind.JOIN));
                }
                pieces.add(new Piece(name, Kind.WORD));
                first = false;
            }
            if (!held.isEmpty()) {
                if (!first) {
                    pieces.add(new Piece("+", Kind.JOIN));
                }
                pieces.add(new Piece(held.toString(), Kind.KEY));
            }
        }
        return pieces;
    }

    private static boolean isKey(String text) {
        return NAMED.contains(text.toLowerCase(Locale.ROOT))
                || KEY.matcher(text).matches();
    }

    enum Kind {
        KEY,
        WORD,
        JOIN
    }

    record Piece(String text, Kind kind) {}
}
