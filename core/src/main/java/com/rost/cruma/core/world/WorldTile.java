package com.rost.cruma.core.world;

import java.util.Collection;
import java.util.regex.Pattern;

public record WorldTile(int x, int y) {
    private static final Pattern MAP_NAME = Pattern.compile("[0-9]+_[0-9]+(_classic)?", Pattern.CASE_INSENSITIVE);

    public static WorldTile at(float worldX, float worldY) {
        return new WorldTile((int) (((double) (int) worldX + 360_448.0) / 32_768.0 + 9.0), (int)
                (((double) (int) worldY + 294_912.0) / 32_768.0 + 9.0));
    }

    public static boolean isMapName(String name) {
        return MAP_NAME.matcher(name).matches();
    }

    public static WorldTile ofMap(String name, float originX, float originY) {
        return isMapName(name) ? fromMapName(name) : at(originX + 16_384f, originY + 16_384f);
    }

    public static String mapAt(double worldX, double worldY, Collection<String> loaded) {
        var tile = at((float) worldX, (float) worldY);
        String named = null;
        for (var name : loaded) {
            if (!isMapName(name)) {
                named = named == null ? name : named;
            } else if (fromMapName(name).equals(tile)) {
                return name;
            }
        }
        return named != null ? named : tile.x() + "_" + tile.y();
    }

    public static WorldTile fromMapName(String name) {
        var parts = name.split("_", 3);
        if (parts.length < 2) {
            throw new IllegalArgumentException("Map name has no tile coordinates: " + name);
        }
        return new WorldTile(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }
}
