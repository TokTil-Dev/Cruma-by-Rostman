package com.rost.cruma.core.npc;

import com.rost.cruma.core.npc.NpcDataDocument.State;
import com.rost.cruma.core.spawn.SpawnText;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.jetbrains.annotations.Nullable;

public final class NpcPch {
    private static final int DEFAULT_OFFSET = 1_000_000;

    private final SpawnText source;
    private final Map<String, Long> numbers;

    private NpcPch(SpawnText source, Map<String, Long> numbers) {
        this.source = source;
        this.numbers = numbers;
    }

    public record Change(List<String> addedLines, List<String> removedNames) {
        public Change {
            addedLines = List.copyOf(addedLines);
            removedNames = List.copyOf(removedNames);
        }

        public boolean isEmpty() {
            return addedLines.isEmpty() && removedNames.isEmpty();
        }
    }

    public static NpcPch parse(SpawnText source) {
        var numbers = new HashMap<String, Long>();
        for (var line : source.text().split("\n")) {
            var name = name(line);
            if (name == null) {
                continue;
            }
            var equals = line.indexOf('=', line.indexOf(']'));
            try {
                numbers.putIfAbsent(
                        name, Long.parseLong(line.substring(equals + 1).strip()));
            } catch (NumberFormatException | StringIndexOutOfBoundsException notNumber) {
            }
        }
        return new NpcPch(source, numbers);
    }

    public SpawnText getSource() {
        return source;
    }

    public @Nullable Long getNumber(String name) {
        return numbers.get(name);
    }

    public int size() {
        return numbers.size();
    }

    public long findIdOffset(NpcDataFile file) {
        var counts = new HashMap<Long, Integer>();
        for (var record : file.getRecords()) {
            var number = numbers.get(record.name());
            if (number != null) {
                counts.merge(number - record.getId(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .max(Entry.<Long, Integer>comparingByValue())
                .map(Entry::getKey)
                .orElse((long) DEFAULT_OFFSET);
    }

    public Change computeChange(NpcDataDocument document, State state) {
        var file = document.getFile();
        var offset = findIdOffset(file);
        var added = new ArrayList<String>();
        for (var i = 0; i < state.added().size(); i++) {
            var index = file.size() + i;
            if (!state.isRemoved(index)) {
                var record = document.record(state, index);
                added.add("[" + record.name() + "] = " + (record.getId() + offset));
            }
        }
        var removed = new ArrayList<String>();
        for (var index : state.removed()) {
            if (index < file.size()) {
                var name = file.record(index).name();
                if (numbers.containsKey(name)) {
                    removed.add(name);
                }
            }
        }
        return new Change(added, removed);
    }

    public SpawnText write(Change change) {
        if (change.isEmpty()) {
            return source;
        }
        var text = source.text();
        var lineEnd = source.lineEnding();
        var replaced = new HashMap<String, String>();
        for (var line : change.addedLines()) {
            replaced.put(name(line), line);
        }
        var out = new StringBuilder(text.length() + 64 * change.addedLines().size());
        var at = 0;
        while (at < text.length()) {
            var newline = text.indexOf('\n', at);
            var end = newline < 0 ? text.length() : newline + 1;
            var line = text.substring(at, end);
            var name = name(line);
            if (name != null && change.removedNames().contains(name)) {
                at = end;
                continue;
            }
            if (name != null && replaced.containsKey(name)) {
                var body = line.endsWith("\r\n")
                        ? line.substring(0, line.length() - 2)
                        : line.endsWith("\n") ? line.substring(0, line.length() - 1) : line;
                out.append(replaced.remove(name)).append(line.substring(body.length()));
            } else {
                out.append(line);
            }
            at = end;
        }
        for (var line : change.addedLines()) {
            if (!replaced.containsKey(name(line))) {
                continue;
            }
            if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
                out.append(lineEnd);
            }
            out.append(line).append(lineEnd);
        }
        return source.withText(out.toString());
    }

    private static @Nullable String name(String line) {
        var open = line.indexOf('[');
        var close = line.indexOf(']', open + 1);
        if (open < 0 || close < 0 || !line.substring(0, open).replace("﻿", "").isBlank()) {
            return null;
        }
        return line.substring(open + 1, close);
    }
}
