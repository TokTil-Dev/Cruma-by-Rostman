package com.rost.cruma.core.spawn;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class NpcDataTable {
    private final Map<String, Npc> byName;
    private final List<String> names;

    private NpcDataTable(Map<String, Npc> byName) {
        this.byName = Map.copyOf(byName);
        var sorted = new ArrayList<>(byName.keySet());
        sorted.sort(null);
        this.names = List.copyOf(sorted);
    }

    public record Npc(
            int id,
            String name,
            String type,
            int level,
            String race,
            double collisionRadius,
            double collisionHeight,
            boolean flying,
            String rightHand,
            String leftHand,
            double walkSpeed,
            double runSpeed) {
        public Npc(
                int id,
                String name,
                String type,
                int level,
                String race,
                double collisionRadius,
                double collisionHeight,
                boolean flying) {
            this(id, name, type, level, race, collisionRadius, collisionHeight, flying, "", "");
        }

        public Npc(
                int id,
                String name,
                String type,
                int level,
                String race,
                double collisionRadius,
                double collisionHeight,
                boolean flying,
                String rightHand,
                String leftHand) {
            this(id, name, type, level, race, collisionRadius, collisionHeight, flying, rightHand, leftHand, 0, 0);
        }
    }

    public static NpcDataTable empty() {
        return new NpcDataTable(Map.of());
    }

    public static NpcDataTable read(Path path) throws IOException {
        return parse(SpawnText.read(path).text());
    }

    public static NpcDataTable parse(String text) {
        var npcs = new HashMap<String, Npc>();
        var position = 0;
        var length = text.length();
        while (position < length) {
            var lineEnd = text.indexOf('\n', position);
            if (lineEnd < 0) {
                lineEnd = length;
            }
            var line = text.substring(position, lineEnd).strip();
            position = lineEnd + 1;
            if (line.startsWith("﻿")) {
                line = line.substring(1);
            }
            if (!line.startsWith("npc_begin")) {
                continue;
            }
            var npc = parseLine(line);
            if (npc != null) {
                npcs.putIfAbsent(npc.name(), npc);
            }
        }
        return new NpcDataTable(npcs);
    }

    public Npc getNpc(String name) {
        return byName.get(name);
    }

    public Collection<Npc> getNpcs() {
        return byName.values();
    }

    public List<String> getNames() {
        return names;
    }

    public int size() {
        return byName.size();
    }

    private static Npc parseLine(String line) {
        var fields = line.split("[\t ]+", 5);
        if (fields.length < 4 || !fields[3].startsWith("[") || !fields[3].endsWith("]")) {
            return null;
        }
        int id;
        try {
            id = Integer.parseInt(fields[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        var rest = fields.length > 4 ? fields[4] : "";
        return new Npc(
                id,
                fields[3].substring(1, fields[3].length() - 1),
                fields[1],
                (int) parseNumber(findValue(rest, "level"), 0),
                findValue(rest, "race"),
                parseFirstNumber(findValue(rest, "collision_radius"), 10),
                parseFirstNumber(findValue(rest, "collision_height"), 20),
                parseNumber(findValue(rest, "flying"), 0) == 1,
                toItemName(findValue(rest, "slot_rhand")),
                toItemName(findValue(rest, "slot_lhand")),
                parseFirstNumber(findValue(rest, "ground_low"), 0),
                parseFirstNumber(findValue(rest, "ground_high"), 0));
    }

    private static String toItemName(String value) {
        return value.replace("[", "").replace("]", "").strip();
    }

    private static String findValue(String settings, String key) {
        var at = 0;
        while (true) {
            at = settings.indexOf(key + "=", at);
            if (at < 0) {
                return "";
            }
            if (at == 0 || Character.isWhitespace(settings.charAt(at - 1))) {
                break;
            }
            at += key.length();
        }
        var start = at + key.length() + 1;
        var end = start;
        while (end < settings.length() && !Character.isWhitespace(settings.charAt(end))) {
            end++;
        }
        return settings.substring(start, end);
    }

    private static double parseFirstNumber(String value, double fallback) {
        var trimmed = value.replace("{", "").replace("}", "");
        var separator = trimmed.indexOf(';');
        return parseNumber(separator < 0 ? trimmed : trimmed.substring(0, separator), fallback);
    }

    private static double parseNumber(String value, double fallback) {
        try {
            return value.isEmpty() ? fallback : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
