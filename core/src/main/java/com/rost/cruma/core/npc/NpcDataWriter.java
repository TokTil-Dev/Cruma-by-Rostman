package com.rost.cruma.core.npc;

import com.rost.cruma.core.npc.NpcDataDocument.State;
import com.rost.cruma.core.spawn.SpawnText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;

public final class NpcDataWriter {
    private NpcDataWriter() {}

    private record Change(int start, int end, String text) {}

    public static SpawnText write(NpcDataFile file, State state) {
        return write(new NpcDataDocument(file), state);
    }

    public static SpawnText write(NpcDataDocument document, State state) {
        var file = document.getFile();
        var source = file.getSource();
        if (state.isEmpty()) {
            return source;
        }
        var text = source.text();
        var changes = new ArrayList<Change>();
        for (var index : state.removed()) {
            if (index < file.size()) {
                changes.add(createRemoval(text, file.record(index)));
            }
        }
        state.edits().forEach((index, values) -> {
            if (index < file.size() && !state.isRemoved(index)) {
                addValueChanges(file.record(index), values, 0, changes);
            }
        });
        changes.sort(Comparator.comparingInt(Change::start));
        var out = new StringBuilder(text.length() + 1024);
        var at = 0;
        for (var change : changes) {
            out.append(text, at, change.start()).append(change.text());
            at = change.end();
        }
        out.append(text, at, text.length());
        var lineEnd = source.lineEnding();
        for (var i = 0; i < state.added().size(); i++) {
            var index = file.size() + i;
            if (state.isRemoved(index)) {
                continue;
            }
            var record = document.recordText(state, index);
            if (out.isEmpty() || out.charAt(out.length() - 1) == '\n') {
                out.append(record).append(lineEnd);
            } else {
                out.append(lineEnd).append(record);
            }
        }
        return source.withText(out.toString());
    }

    static String recordText(NpcRecord record, Map<String, String> values) {
        var changes = new ArrayList<Change>();
        addValueChanges(record, values, record.getStart(), changes);
        changes.sort(Comparator.comparingInt(Change::start));
        var text = record.getText();
        var out = new StringBuilder(text.length() + 256);
        var at = 0;
        for (var change : changes) {
            out.append(text, at, change.start()).append(change.text());
            at = change.end();
        }
        return out.append(text, at, text.length()).toString();
    }

    private static void addValueChanges(
            NpcRecord record, Map<String, String> values, int base, ArrayList<Change> changes) {
        var added = new StringBuilder();
        values.forEach((key, value) -> {
            if (record.has(key)) {
                changes.add(new Change(record.getValueStart(key) - base, record.getValueEnd(key) - base, value));
            } else {
                added.append(record.getSeparator()).append(key).append('=').append(value);
            }
        });
        if (!added.isEmpty()) {
            changes.add(new Change(record.getInsertAt() - base, record.getInsertAt() - base, added.toString()));
        }
    }

    private static Change createRemoval(String text, NpcRecord record) {
        var start = record.getStart();
        var end = record.getEnd();
        var lineStart = start;
        while (lineStart > 0 && isBlank(text.charAt(lineStart - 1))) {
            lineStart--;
        }
        var markOnly = lineStart == 1 && text.charAt(0) == '﻿';
        if (lineStart > 0 && text.charAt(lineStart - 1) != '\n' && !markOnly) {
            return new Change(start, end, "");
        }
        var lineEnd = end;
        while (lineEnd < text.length() && isBlank(text.charAt(lineEnd))) {
            lineEnd++;
        }
        if (lineEnd < text.length() && text.charAt(lineEnd) == '\r') {
            lineEnd++;
        }
        if (lineEnd < text.length() && text.charAt(lineEnd) == '\n') {
            return new Change(lineStart, lineEnd + 1, "");
        }
        if (lineEnd < text.length()) {
            return new Change(start, end, "");
        }
        var before = lineStart;
        if (before > 0 && text.charAt(before - 1) == '\n') {
            before--;
            if (before > 0 && text.charAt(before - 1) == '\r') {
                before--;
            }
        }
        return new Change(before, lineEnd, "");
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }
}
