package com.rost.cruma.core.npc;

import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class NpcRecord {
    private final int index;
    private final int line;
    private final String type;
    private final int id;
    private final String name;
    private final int start;
    private final int end;
    private final int insertAt;
    private final String separator;
    private final String text;
    private final String[] keys;
    private final int[] valueStarts;
    private final int[] valueEnds;

    NpcRecord(
            int index,
            int line,
            String type,
            int id,
            String name,
            int start,
            int end,
            int insertAt,
            String separator,
            String text,
            String[] keys,
            int[] valueStarts,
            int[] valueEnds) {
        this.index = index;
        this.line = line;
        this.type = type;
        this.id = id;
        this.name = name;
        this.start = start;
        this.end = end;
        this.insertAt = insertAt;
        this.separator = separator;
        this.text = text;
        this.keys = keys;
        this.valueStarts = valueStarts;
        this.valueEnds = valueEnds;
    }

    NpcRecord reindexed(int newIndex, int newLine) {
        return new NpcRecord(
                newIndex, newLine, type, id, name, start, end, insertAt, separator, text, keys, valueStarts, valueEnds);
    }

    public int getIndex() {
        return index;
    }

    public int getLine() {
        return line;
    }

    public String getType() {
        return type;
    }

    public int getId() {
        return id;
    }

    public String name() {
        return name;
    }

    public List<String> getKeys() {
        return Arrays.asList(keys);
    }

    public boolean has(String key) {
        return findSlot(key) >= 0;
    }

    public @Nullable String getValue(String key) {
        var slot = findSlot(key);
        return slot < 0 ? null : text.substring(valueStarts[slot], valueEnds[slot]);
    }

    int getValueStart(String key) {
        var slot = findSlot(key);
        return slot < 0 ? -1 : valueStarts[slot];
    }

    int getValueEnd(String key) {
        var slot = findSlot(key);
        return slot < 0 ? -1 : valueEnds[slot];
    }

    int getInsertAt() {
        return insertAt;
    }

    String getSeparator() {
        return separator;
    }

    int getStart() {
        return start;
    }

    int getEnd() {
        return end;
    }

    public String getText() {
        return text.substring(start, end);
    }

    @Override
    public String toString() {
        return type + " " + id + " [" + name + "]";
    }

    private int findSlot(String key) {
        for (var i = 0; i < keys.length; i++) {
            if (keys[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }
}
