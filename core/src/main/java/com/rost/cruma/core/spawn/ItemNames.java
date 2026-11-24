package com.rost.cruma.core.spawn;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class ItemNames {
    private final Map<String, Integer> ids;

    private ItemNames(Map<String, Integer> ids) {
        this.ids = Map.copyOf(ids);
    }

    public static ItemNames empty() {
        return new ItemNames(Map.of());
    }

    public static ItemNames read(Path path) throws IOException {
        return parse(SpawnText.read(path).text());
    }

    public static ItemNames parse(String text) {
        var ids = new HashMap<String, Integer>();
        for (var line : text.split("\n")) {
            var open = line.indexOf('[');
            var close = line.indexOf(']', open + 1);
            var equals = line.indexOf('=', close + 1);
            if (open < 0 || close < 0 || equals < 0) {
                continue;
            }
            try {
                ids.putIfAbsent(
                        line.substring(open + 1, close),
                        Integer.parseInt(line.substring(equals + 1).strip()));
            } catch (NumberFormatException ignored) {
            }
        }
        return new ItemNames(ids);
    }

    public int getId(String name) {
        return ids.getOrDefault(name, -1);
    }

    public Map<String, Integer> getEntries() {
        return ids;
    }
}
