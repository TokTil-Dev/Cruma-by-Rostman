package com.rost.cruma.core.npc;

import com.rost.cruma.core.spawn.ItemNames;
import com.rost.cruma.core.spawn.SpawnText;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class ServerItems {
    private final Map<String, Item> byName;
    private final Map<Integer, Item> byId;
    private final List<Item> sorted;

    private ServerItems(Collection<Item> items) {
        var names = new HashMap<String, Item>(items.size() * 2);
        var ids = new HashMap<Integer, Item>(items.size() * 2);
        for (var item : items) {
            names.putIfAbsent(item.name(), item);
            ids.putIfAbsent(item.id(), item);
        }
        this.byName = names;
        this.byId = ids;
        var list = new ArrayList<>(names.values());
        list.sort(Comparator.comparingInt(Item::id));
        this.sorted = List.copyOf(list);
    }

    public record Item(int id, String name, String type, String grade, boolean stackable) {}

    public static ServerItems empty() {
        return new ServerItems(List.of());
    }

    public static ServerItems of(Collection<Item> items) {
        return new ServerItems(items);
    }

    public static ServerItems read(@Nullable Path itemData, @Nullable Path itemPch) throws IOException {
        if (itemData != null) {
            return parse(SpawnText.read(itemData).text());
        }
        if (itemPch != null) {
            return fromPch(SpawnText.read(itemPch).text());
        }
        return empty();
    }

    public static ServerItems parse(String text) {
        var items = new ArrayList<Item>();
        var at = 0;
        while (at < text.length()) {
            var lineEnd = text.indexOf('\n', at);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            var begin = text.indexOf("item_begin", at);
            if (begin >= 0 && begin < lineEnd) {
                var item = parseItem(text.substring(begin, lineEnd));
                if (item != null) {
                    items.add(item);
                }
            }
            at = lineEnd + 1;
        }
        return new ServerItems(items);
    }

    static ServerItems fromPch(String text) {
        var names = ItemNames.parse(text);
        var items = new ArrayList<Item>();
        for (var entry : names.getEntries().entrySet()) {
            items.add(new Item(entry.getValue(), entry.getKey(), "", "none", false));
        }
        return new ServerItems(items);
    }

    static String setting(String settings, String key) {
        var at = 0;
        while (true) {
            at = settings.indexOf(key, at);
            if (at < 0) {
                return "";
            }
            var before = at == 0 ? ' ' : settings.charAt(at - 1);
            var after = at + key.length();
            while (after < settings.length() && settings.charAt(after) == ' ') {
                after++;
            }
            if ((before == ' ' || before == '\t') && after < settings.length() && settings.charAt(after) == '=') {
                var start = after + 1;
                while (start < settings.length() && settings.charAt(start) == ' ') {
                    start++;
                }
                var end = start;
                while (end < settings.length()
                        && settings.charAt(end) != '\t'
                        && settings.charAt(end) != '\r'
                        && settings.charAt(end) != '\n') {
                    end++;
                }
                return settings.substring(start, end).strip();
            }
            at += key.length();
        }
    }

    public @Nullable Item byName(String name) {
        return byName.get(name);
    }

    public @Nullable Item byId(int id) {
        return byId.get(id);
    }

    public List<Item> getItems() {
        return sorted;
    }

    public int size() {
        return sorted.size();
    }

    private static @Nullable Item parseItem(String line) {
        var fields = line.split("[\t ]+", 5);
        if (fields.length < 4 || !fields[3].startsWith("[") || !fields[3].endsWith("]")) {
            return null;
        }
        try {
            var settings = fields.length > 4 ? fields[4] : "";
            var grade = setting(settings, "crystal_type");
            var consume = setting(settings, "consume_type");
            return new Item(
                    Integer.parseInt(fields[2]),
                    fields[3].substring(1, fields[3].length() - 1),
                    fields[1],
                    grade.isEmpty() ? "none" : grade,
                    consume.equals("consume_type_stackable") || consume.equals("consume_type_asset"));
        } catch (NumberFormatException notNumber) {
            return null;
        }
    }
}
