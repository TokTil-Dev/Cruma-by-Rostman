package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.world.WorldMapCatalog;
import com.rost.cruma.core.world.WorldVariant;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class EditingMaps {
    public static final EditingMaps MAIN = new EditingMaps(Map.of());

    private final Map<String, String> sources;
    private final Map<String, String> tiles;

    EditingMaps(Map<String, String> packages) {
        var bySource = new HashMap<String, String>();
        var byTile = new HashMap<String, String>();
        packages.forEach((tile, source) -> {
            if (!tile.equalsIgnoreCase(source)) {
                bySource.put(key(tile), source);
                byTile.put(key(source), tile);
            }
        });
        sources = Map.copyOf(bySource);
        tiles = Map.copyOf(byTile);
    }

    public static EditingMaps of(Path clientRoot, WorldVariant variant) {
        return variant.getMapWorld() == WorldVariant.MAIN
                ? MAIN
                : new EditingMaps(WorldMapCatalog.findPackages(clientRoot, variant));
    }

    public String getSource(String map) {
        return sources.getOrDefault(key(map), map);
    }

    public Set<String> getSources(Collection<String> maps) {
        var result = new LinkedHashSet<String>();
        for (var map : maps) {
            result.add(getSource(map));
        }
        return result;
    }

    public String getTile(String source) {
        return tiles.getOrDefault(key(source), source);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
