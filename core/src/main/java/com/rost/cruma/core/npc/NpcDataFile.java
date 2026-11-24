package com.rost.cruma.core.npc;

import com.rost.cruma.core.spawn.SpawnText;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class NpcDataFile {
    private final SpawnText source;
    private final List<NpcRecord> records;
    private final Map<String, Integer> byName;
    private final Map<Integer, Integer> byId;
    private final List<Problem> problems;

    private NpcDataFile(SpawnText source, List<NpcRecord> records, List<Problem> problems) {
        this.source = source;
        this.records = Collections.unmodifiableList(records);
        this.problems = List.copyOf(problems);
        var names = new HashMap<String, Integer>(records.size() * 2);
        var ids = new HashMap<Integer, Integer>(records.size() * 2);
        for (var record : records) {
            names.putIfAbsent(record.name(), record.getIndex());
            ids.putIfAbsent(record.getId(), record.getIndex());
        }
        this.byName = names;
        this.byId = ids;
    }

    public record Problem(int line, String message) {}

    public static NpcDataFile read(Path path) throws IOException {
        return parse(SpawnText.read(path));
    }

    public static NpcDataFile parse(String text) {
        return parse(SpawnText.of(text));
    }

    public static NpcDataFile parse(SpawnText source) {
        var reader = new Reader(source.text());
        reader.read();
        return new NpcDataFile(source, reader.records, reader.problems);
    }

    public SpawnText getSource() {
        return source;
    }

    public List<NpcRecord> getRecords() {
        return records;
    }

    public NpcRecord record(int index) {
        return records.get(index);
    }

    public @Nullable NpcRecord byName(String name) {
        var index = byName.get(name);
        return index == null ? null : records.get(index);
    }

    public @Nullable NpcRecord byId(int id) {
        var index = byId.get(id);
        return index == null ? null : records.get(index);
    }

    public List<Problem> getProblems() {
        return problems;
    }

    public int size() {
        return records.size();
    }

    private static final class Reader {
        private final String text;
        private final List<NpcRecord> records = new ArrayList<>();
        private final List<Problem> problems = new ArrayList<>();
        private final Map<String, String> keyNames = new HashMap<>();
        private int position;
        private int line = 1;
        private int lineCountedTo;

        Reader(String text) {
            this.text = text;
        }

        void read() {
            while (true) {
                var start = nextToken();
                if (start < 0) {
                    return;
                }
                var end = tokenEnd(start);
                if (!is(start, end, "npc_begin")) {
                    addProblem(start, "Skipped text outside an NPC: " + preview(start, end));
                    position = end;
                    continue;
                }
                position = end;
                readRecord(start);
            }
        }

        private void readRecord(int begin) {
            var header = new String[3];
            var keys = new ArrayList<String>();
            var starts = new ArrayList<Integer>();
            var ends = new ArrayList<Integer>();
            var headerCount = 0;
            while (true) {
                var start = nextToken();
                if (start < 0) {
                    addProblem(begin, "An NPC without npc_end at the end of the file");
                    return;
                }
                var end = tokenEnd(start);
                if (is(start, end, "npc_end")) {
                    position = end;
                    finish(begin, start, end, header, headerCount, keys, starts, ends);
                    return;
                }
                if (is(start, end, "npc_begin")) {
                    addProblem(begin, "An NPC without npc_end before the next npc_begin");
                    return;
                }
                position = end;
                if (headerCount < 3) {
                    header[headerCount++] = text.substring(start, end);
                    continue;
                }
                var equals = text.indexOf('=', start);
                if (equals < 0 || equals >= end) {
                    addProblem(start, "A setting without '=': " + preview(start, end));
                    continue;
                }
                keys.add(keyName(text.substring(start, equals)));
                starts.add(equals + 1);
                ends.add(end);
            }
        }

        private void finish(
                int begin,
                int endTokenStart,
                int end,
                String[] header,
                int headerCount,
                List<String> keys,
                List<Integer> starts,
                List<Integer> ends) {
            if (headerCount < 3 || !header[2].startsWith("[") || !header[2].endsWith("]")) {
                addProblem(begin, "An NPC without a type, id and [name]");
                return;
            }
            int id;
            try {
                id = Integer.parseInt(header[1]);
            } catch (NumberFormatException notNumber) {
                addProblem(begin, "An NPC id that is not a number: " + header[1]);
                return;
            }
            var valueStarts = new int[starts.size()];
            var valueEnds = new int[ends.size()];
            for (var i = 0; i < valueStarts.length; i++) {
                valueStarts[i] = starts.get(i);
                valueEnds[i] = ends.get(i);
            }
            var separatorStart = endTokenStart;
            while (separatorStart > begin && isSpace(text.charAt(separatorStart - 1))) {
                separatorStart--;
            }
            var separator = separatorStart < endTokenStart ? text.substring(separatorStart, endTokenStart) : "\t";
            records.add(new NpcRecord(
                    records.size(),
                    lineAt(begin),
                    header[0],
                    id,
                    header[2].substring(1, header[2].length() - 1),
                    begin,
                    end,
                    separatorStart,
                    separator.contains("\n") ? "\t" : separator,
                    text,
                    keys.toArray(String[]::new),
                    valueStarts,
                    valueEnds));
        }

        private String keyName(String key) {
            return keyNames.computeIfAbsent(key, name -> name);
        }

        private int nextToken() {
            var length = text.length();
            while (position < length) {
                var c = text.charAt(position);
                if (isSpace(c) || c == '﻿') {
                    position++;
                } else if (c == '/' && position + 1 < length && text.charAt(position + 1) == '/') {
                    var newline = text.indexOf('\n', position);
                    position = newline < 0 ? length : newline + 1;
                } else if (c == '/' && position + 1 < length && text.charAt(position + 1) == '*') {
                    var close = text.indexOf("*/", position + 2);
                    position = close < 0 ? length : close + 2;
                } else {
                    return position;
                }
            }
            return -1;
        }

        private int tokenEnd(int start) {
            var depth = 0;
            var length = text.length();
            var i = start;
            while (i < length) {
                var c = text.charAt(i);
                if (c == '{' || c == '[') {
                    depth++;
                } else if ((c == '}' || c == ']') && depth > 0) {
                    depth--;
                } else if (depth == 0 && isSpace(c)) {
                    break;
                } else if (c == '\n' || c == '\r') {
                    break;
                }
                i++;
            }
            return i;
        }

        private boolean is(int start, int end, String word) {
            return end - start == word.length() && text.startsWith(word, start);
        }

        private int lineAt(int offset) {
            for (var i = lineCountedTo; i < offset; i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                }
            }
            lineCountedTo = Math.max(lineCountedTo, offset);
            return line;
        }

        private void addProblem(int offset, String message) {
            problems.add(new Problem(lineAt(offset), message));
        }

        private String preview(int start, int end) {
            var shown = text.substring(start, Math.min(end, start + 40));
            return end - start > 40 ? shown + "…" : shown;
        }

        private static boolean isSpace(char c) {
            return c == ' ' || c == '\t' || c == '\r' || c == '\n';
        }
    }
}
