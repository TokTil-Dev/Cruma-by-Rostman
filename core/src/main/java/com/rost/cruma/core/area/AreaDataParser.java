package com.rost.cruma.core.area;

import com.rost.cruma.core.area.AreaDataFile.Problem;
import com.rost.cruma.core.area.AreaDataLexer.Kind;
import com.rost.cruma.core.area.AreaDataLexer.Token;
import com.rost.cruma.core.spawn.SourceSpan;
import com.rost.cruma.core.spawn.SpawnText;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class AreaDataParser {
    private static final String AREA_BEGIN = "area_begin";
    private static final String AREA_END = "area_end";
    private static final String DEFAULT_BEGIN = "default_setting_begin";
    private static final String DEFAULT_END = "default_setting_end";
    private static final Set<String> TIME_UNITS = Set.of("sec", "min", "hour", "day");

    private final SpawnText source;
    private final String text;
    private final List<Problem> problems = new ArrayList<>();
    private final List<Token> tokens;
    private final List<ServerArea> areas = new ArrayList<>();
    private final List<ServerArea> defaults = new ArrayList<>();
    private int next;

    AreaDataParser(SpawnText source) {
        this.source = source;
        this.text = source.text();
        this.tokens = new AreaDataLexer(text, problems).scanAll();
    }

    private record Value(int end, boolean broken) {
        static final Value MISSING = new Value(-1, false);
        static final Value BROKEN = new Value(-1, true);

        boolean isPresent() {
            return end >= 0;
        }
    }

    AreaDataFile parse() {
        while (peek(0).kind() != Kind.END) {
            var token = peek(0);
            if (token.isWord(AREA_BEGIN)) {
                parseRecord(AREA_END, areas);
            } else if (token.isWord(DEFAULT_BEGIN)) {
                parseRecord(DEFAULT_END, defaults);
            } else {
                addProblem(token, "Unexpected '" + token.text() + "' outside an area");
                next++;
                while (peek(0).kind() != Kind.END && !isRecordStart(peek(0))) {
                    next++;
                }
            }
        }
        return new AreaDataFile(source, areas, defaults, problems);
    }

    private void parseRecord(String endWord, List<ServerArea> into) {
        var begin = take();
        var settings = new ArrayList<AreaSetting>();
        Token end;
        while (true) {
            var token = peek(0);
            if (token.kind() == Kind.END || isRecordStart(token)) {
                addProblem(begin, begin.text() + " has no " + endWord + ", so it is left out");
                return;
            }
            take();
            if (token.isWord(endWord)) {
                end = token;
                break;
            }
            if (token.kind() != Kind.WORD) {
                addProblem(token, "Unexpected '" + token.text() + "' in " + begin.text());
                continue;
            }
            if (!peek(0).is('=')) {
                addProblem(token, "Expected '=' after " + token.text());
                continue;
            }
            take();
            var first = peek(0);
            var value = parseValue(token.text(), endWord);
            if (value.broken()) {
                addProblem(token, "The value of " + token.text() + " has no closing bracket, so the area is left out");
                if (peek(0).isWord(endWord)) {
                    take();
                }
                return;
            }
            if (!value.isPresent()) {
                addProblem(token, token.text() + " has no value");
                continue;
            }
            var setting = new AreaSetting(
                    token.text(),
                    text.substring(first.start(), value.end()),
                    new SourceSpan(token.start(), value.end()),
                    new SourceSpan(first.start(), value.end()));
            checkValue(token, setting);
            settings.add(setting);
        }
        var noteSpan = findNote(begin.start());
        var note = noteSpan.isPresent() ? text.substring(noteSpan.start(), noteSpan.end()) : "";
        into.add(new ServerArea(
                into.size(), settings, note, noteSpan, begin.line(), new SourceSpan(begin.start(), end.end())));
    }

    private Value parseValue(String key, String endWord) {
        var first = peek(0);
        if (first.kind() == Kind.END || isRecordStart(first) || first.isWord(endWord)) {
            return Value.MISSING;
        }
        if (first.is('{') || first.is('(')) {
            take();
            var depth = 1;
            while (depth > 0) {
                var token = peek(0);
                if (token.kind() == Kind.END || isRecordStart(token) || token.isWord(endWord)) {
                    return Value.BROKEN;
                }
                take();
                if (token.is('{') || token.is('(')) {
                    depth++;
                } else if (token.is('}') || token.is(')')) {
                    depth--;
                }
                if (depth == 0) {
                    return new Value(token.end(), false);
                }
            }
        }
        if (first.kind() == Kind.NAME) {
            return new Value(take().end(), false);
        }
        if (first.kind() == Kind.WORD) {
            if (peek(1).is('=')) {
                return Value.MISSING;
            }
            return new Value(take().end(), false);
        }
        if (first.kind() != Kind.NUMBER) {
            take();
            addProblem(first, "Unexpected '" + first.text() + "' after " + key + "=");
            return Value.MISSING;
        }
        var end = take().end();
        if (isUnitAt(0, end)) {
            end = take().end();
            while (peek(0).kind() == Kind.NUMBER && isUnitAt(1, peek(0).end())) {
                take();
                end = take().end();
            }
        }
        while (peek(0).is(';') && peek(1).kind() == Kind.NUMBER) {
            take();
            end = take().end();
        }
        return new Value(end, false);
    }

    private boolean isUnitAt(int ahead, int numberEnd) {
        var token = peek(ahead);
        return token.kind() == Kind.WORD && TIME_UNITS.contains(token.text()) && token.start() == numberEnd;
    }

    private void checkValue(Token key, AreaSetting setting) {
        var value = setting.value();
        var reads = switch (setting.key()) {
            case "range", "teleport_range" -> AreaValues.parsePoints(value) != null;
            case "banned_range" -> AreaValues.parsePolygons(value) != null;
            case "water_range" -> AreaValues.parseWaterBox(value) != null;
            case "teleport_points", "restart_points" -> AreaValues.parsePlaces(value) != null;
            case "map_no" -> {
                var numbers = AreaValues.parseInts(value);
                yield numbers != null && numbers.length == 2;
            }
            default -> true;
        };
        if (!reads) {
            addProblem(key, "The value of " + setting.key() + " does not read: " + shorten(value));
        }
    }

    private SourceSpan findNote(int begin) {
        var lineStart = text.lastIndexOf('\n', begin - 1) + 1;
        if (lineStart == 0 || !text.substring(lineStart, begin).isBlank()) {
            return SourceSpan.NONE;
        }
        var previousEnd = lineStart - 1;
        var previousStart = text.lastIndexOf('\n', previousEnd - 1) + 1;
        var start = previousStart;
        var end = previousEnd;
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (!text.startsWith("//", start)) {
            return SourceSpan.NONE;
        }
        start += 2;
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        var words = false;
        for (var i = start; i < end && !words; i++) {
            words = Character.isLetterOrDigit(text.charAt(i));
        }
        if (!words || text.startsWith(AREA_BEGIN, start) || text.startsWith(DEFAULT_BEGIN, start)) {
            return SourceSpan.NONE;
        }
        return new SourceSpan(start, end);
    }

    private Token peek(int ahead) {
        return tokens.get(Math.min(next + ahead, tokens.size() - 1));
    }

    private Token take() {
        var token = peek(0);
        if (next < tokens.size() - 1) {
            next++;
        }
        return token;
    }

    private void addProblem(Token token, String message) {
        problems.add(new Problem(token.line(), message));
    }

    private static boolean isRecordStart(Token token) {
        return token.isWord(AREA_BEGIN) || token.isWord(DEFAULT_BEGIN);
    }

    private static String shorten(String value) {
        return value.length() > 40 ? value.substring(0, 40) + "…" : value;
    }
}
