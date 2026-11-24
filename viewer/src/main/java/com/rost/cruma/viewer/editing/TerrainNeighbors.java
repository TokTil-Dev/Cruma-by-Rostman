package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.TerrainNativeCodec.Info;
import com.rost.cruma.core.world.WorldTile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class TerrainNeighbors {
    static final int MARGIN = 8;

    private @Nullable List<TerrainDocument> documents;
    private Set<String> loaded = Set.of();
    private final Map<String, String> refused = new LinkedHashMap<>();
    private final Set<String> asked = new HashSet<>();
    private EditingMaps files = EditingMaps.MAIN;

    public record Beyond(String map, float x, float y) {}

    public static List<Beyond> mapsBeyond(Info info, float minX, float minY, float maxX, float maxY) {
        var last = info.width() - 1;
        var lastY = info.height() - 1;
        var sideX = minX < 0 ? -1 : maxX > last ? 1 : 0;
        var sideY = minY < 0 ? -1 : maxY > lastY ? 1 : 0;
        var maps = new ArrayList<Beyond>();
        var alongX = Math.clamp((minX + maxX) * .5f, 0, last);
        var alongY = Math.clamp((minY + maxY) * .5f, 0, lastY);
        var pastX = sideX < 0 ? -1 : last + 2;
        var pastY = sideY < 0 ? -1 : lastY + 2;
        if (sideX != 0) {
            maps.add(beyond(info, pastX, alongY));
        }
        if (sideY != 0) {
            maps.add(beyond(info, alongX, pastY));
        }
        if (sideX != 0 && sideY != 0) {
            maps.add(beyond(info, pastX, pastY));
        }
        return maps;
    }

    static List<String> mapsNear(Info info, float x, float y, float radius) {
        var reach = radius + MARGIN;
        var maps = new ArrayList<String>();
        for (var beyond : mapsBeyond(info, x - reach, y - reach, x + reach, y + reach)) {
            maps.add(beyond.map());
        }
        return maps;
    }

    void setMapFiles(EditingMaps value) {
        files = value;
    }

    @Nullable
    String next(List<TerrainDocument> open, Set<String> maps, Info under, float x, float y, float radius) {
        forgetWhenChanged(open, maps);
        for (var tile : mapsNear(under, x, y, radius)) {
            var map = files.getSource(tile);
            if (!maps.contains(map) || refused.containsKey(map) || asked.contains(map) || isOpen(open, map)) {
                continue;
            }
            return map;
        }
        return null;
    }

    void asked(String map) {
        asked.add(map);
    }

    void opened(String map) {
        asked.remove(map);
    }

    void refuse(String map, String why) {
        asked.remove(map);
        refused.put(map, why);
    }

    List<String> getNotes(List<TerrainDocument> open, Set<String> maps) {
        forgetWhenChanged(open, maps);
        var notes = new ArrayList<String>();
        for (var why : refused.values()) {
            if (!why.isEmpty()) {
                notes.add(why);
            }
        }
        return notes;
    }

    private void forgetWhenChanged(List<TerrainDocument> open, Set<String> maps) {
        if (open == documents && maps.equals(loaded)) {
            return;
        }
        documents = open;
        loaded = Set.copyOf(maps);
        refused.clear();
    }

    private static boolean isOpen(List<TerrainDocument> open, String map) {
        for (var document : open) {
            if (document.getMap().equalsIgnoreCase(map)) {
                return true;
            }
        }
        return false;
    }

    private static Beyond beyond(Info info, float x, float y) {
        var world = info.toWorld().transform(x, y, 0);
        var tile = WorldTile.at(world.x(), world.y());
        return new Beyond(tile.x() + "_" + tile.y(), x, y);
    }
}
