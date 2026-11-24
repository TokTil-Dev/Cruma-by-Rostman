package com.rost.cruma.core.area;

import com.rost.cruma.core.area.AreaDocument.State;
import com.rost.cruma.core.spawn.NpcPosWriter;
import com.rost.cruma.core.spawn.SourceSpan;
import com.rost.cruma.core.spawn.SpawnText;
import com.rost.cruma.core.spawn.TerritoryPoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class AreaDataWriter {
    private static final String AREA_BEGIN = "area_begin";
    private static final String AREA_END = "area_end";
    private static final int AREA_ORDER = 100;
    private static final int NOTE_ORDER = 1_000_000;

    private final String source;
    private final String lineEnding;
    private final List<Change> changes = new ArrayList<>();
    private int sequence;

    private AreaDataWriter(SpawnText text) {
        this.source = text.text();
        this.lineEnding = text.lineEnding();
    }

    private record Change(int start, int end, String text, int order) {}

    public static SpawnText write(AreaDataFile file, State state) {
        var writer = new AreaDataWriter(file.getSource());
        writer.writeAreas(file, state);
        return file.getSource().withText(writer.apply());
    }

    public static String formatArea(ServerArea area) {
        var text = new StringBuilder(AREA_BEGIN);
        for (var setting : area.settings()) {
            text.append(' ').append(setting.format());
        }
        return text.append(' ').append(AREA_END).toString();
    }

    public static String formatPoints(List<TerritoryPoint> points) {
        return NpcPosWriter.formatPoints(points);
    }

    public static String formatPolygons(List<List<TerritoryPoint>> polygons) {
        return polygons.stream().map(AreaDataWriter::formatPoints).collect(Collectors.joining(";", "{", "}"));
    }

    public static String formatPlaces(List<AreaPoint> places) {
        return places.stream()
                .map(place -> "{" + place.x() + ";" + place.y() + ";" + place.z() + "}")
                .collect(Collectors.joining(";", "{", "}"));
    }

    public static String formatWaterBox(WaterBox box) {
        return String.format(
                Locale.ROOT,
                "{Min:(%.6f %.6f %.6f) Max:(%.6f %.6f %.6f)}",
                box.minX(),
                box.minY(),
                box.minZ(),
                box.maxX(),
                box.maxY(),
                box.maxZ());
    }

    public static String formatMap(int tileX, int tileY) {
        return "{" + tileX + ";" + tileY + "}";
    }

    private void writeAreas(AreaDataFile file, State state) {
        var base = file.getAreas();
        for (var i = 0; i < base.size(); i++) {
            var before = base.get(i);
            var after = state.area(i);
            if (after == null) {
                removeLines(before.span());
                if (before.noteSpan().isPresent()) {
                    removeLine(before.noteSpan().start());
                }
                continue;
            }
            if (after.equals(before)) {
                continue;
            }
            writeSettings(before, after);
            writeNote(before, after);
        }
        for (var i = base.size(); i < state.areas().size(); i++) {
            var area = state.areas().get(i);
            if (area == null) {
                continue;
            }
            var offset = findPlace(base.size(), state, area.typeWord());
            var text = new StringBuilder();
            if (offset == source.length() && !source.isEmpty() && !source.endsWith("\n")) {
                text.append(lineEnding);
            }
            var note = AreaEdits.cleanNote(area.note());
            if (!note.isEmpty()) {
                text.append("//").append(note).append(lineEnding);
            }
            text.append(formatArea(area)).append(lineEnding);
            insert(offset, text.toString(), AREA_ORDER + sequence++);
        }
    }

    private int findPlace(int fileAreas, State state, String typeWord) {
        for (var i = fileAreas - 1; i >= 0; i--) {
            var area = state.area(i);
            if (area != null && area.typeWord().equals(typeWord)) {
                var lineEnd = source.indexOf('\n', area.span().end());
                return lineEnd < 0 ? source.length() : lineEnd + 1;
            }
        }
        return source.length();
    }

    private void writeSettings(ServerArea before, ServerArea after) {
        var kept = new HashMap<SourceSpan, AreaSetting>();
        var added = new ArrayList<AreaSetting>();
        for (var setting : after.settings()) {
            if (setting.isInFile()) {
                kept.put(setting.span(), setting);
            } else {
                added.add(setting);
            }
        }
        for (var original : before.settings()) {
            var edited = kept.get(original.span());
            if (edited == null) {
                removeSetting(original.span());
            } else if (!edited.key().equals(original.key())) {
                replace(original.span(), edited.format());
            } else if (!edited.value().equals(original.value())) {
                replace(original.valueSpan(), edited.value());
            }
        }
        if (added.isEmpty()) {
            return;
        }
        var separator = findSeparator(before);
        var end = before.span().end() - AREA_END.length();
        for (var setting : added) {
            insert(end, setting.format() + separator, sequence++);
        }
    }

    private void writeNote(ServerArea before, ServerArea after) {
        var note = AreaEdits.cleanNote(after.note());
        if (before.noteSpan().isPresent()) {
            if (note.isEmpty()) {
                removeLine(before.noteSpan().start());
            } else if (!note.equals(before.note())) {
                replace(before.noteSpan(), note);
            }
        } else if (!note.isEmpty()) {
            insert(lineStart(before.span().start()), "//" + note + lineEnding, NOTE_ORDER + sequence++);
        }
    }

    private String findSeparator(ServerArea area) {
        var previous = area.span().start() + AREA_BEGIN.length();
        for (var setting : area.settings()) {
            if (source.substring(previous, setting.span().start()).indexOf('\t') >= 0) {
                return "\t";
            }
            previous = setting.span().end();
        }
        return source.substring(previous, area.span().end() - AREA_END.length()).indexOf('\t') >= 0 ? "\t" : " ";
    }

    private void removeSetting(SourceSpan span) {
        var start = span.start();
        while (start > 0 && (source.charAt(start - 1) == '\t' || source.charAt(start - 1) == ' ')) {
            start--;
        }
        replace(new SourceSpan(start, span.end()), "");
    }

    private void removeLines(SourceSpan span) {
        var start = lineStart(span.start());
        var lineEnd = source.indexOf('\n', span.end());
        lineEnd = lineEnd < 0 ? source.length() : lineEnd + 1;
        var after = source.substring(span.end(), lineEnd).strip();
        var whole = source.substring(start, span.start()).isBlank() && (after.isEmpty() || after.startsWith("//"));
        replace(whole ? new SourceSpan(start, lineEnd) : span, "");
    }

    private void removeLine(int offset) {
        var lineEnd = source.indexOf('\n', offset);
        replace(new SourceSpan(lineStart(offset), lineEnd < 0 ? source.length() : lineEnd + 1), "");
    }

    private int lineStart(int offset) {
        return source.lastIndexOf('\n', offset - 1) + 1;
    }

    private void replace(SourceSpan span, String text) {
        changes.add(new Change(span.start(), span.end(), text, 0));
    }

    private void insert(int offset, String text, int order) {
        changes.add(new Change(offset, offset, text, order));
    }

    private String apply() {
        if (changes.isEmpty()) {
            return source;
        }
        changes.sort(Comparator.comparingInt(Change::start)
                .thenComparingInt(Change::end)
                .thenComparingInt(Change::order));
        var result = new StringBuilder(source.length() + 256);
        var cursor = 0;
        for (var change : changes) {
            if (change.start() < cursor) {
                continue;
            }
            result.append(source, cursor, change.start()).append(change.text());
            cursor = change.end();
        }
        return result.append(source, cursor, source.length()).toString();
    }
}
