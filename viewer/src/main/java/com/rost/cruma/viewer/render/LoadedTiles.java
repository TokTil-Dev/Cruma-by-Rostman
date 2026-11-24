package com.rost.cruma.viewer.render;

import com.rost.cruma.core.geodata.GeoTile;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class LoadedTiles {
    public static final int SIZE = 32768;
    private static final Pattern NAME = Pattern.compile("(\\d+)_(\\d+)");

    private final Set<Long> tiles = ConcurrentHashMap.newKeySet();
    private volatile long revision;

    public void add(String mapName) {
        var key = parseTileKey(mapName);
        if (key != null && tiles.add(key)) {
            revision++;
        }
    }

    public void remove(String mapName) {
        var key = parseTileKey(mapName);
        if (key != null && tiles.remove(key)) {
            revision++;
        }
    }

    public boolean isEmpty() {
        return tiles.isEmpty();
    }

    public boolean has(String mapName) {
        var key = parseTileKey(mapName);
        return key != null && tiles.contains(key);
    }

    public boolean contains(double x, double y) {
        return tiles.contains(
                pack(Math.floorDiv((int) Math.floor(x), SIZE) + 20, Math.floorDiv((int) Math.floor(y), SIZE) + 18));
    }

    public boolean intersects(double minX, double minY, double maxX, double maxY) {
        var fromX = Math.floorDiv((int) Math.floor(minX), SIZE) + 20;
        var toX = Math.floorDiv((int) Math.floor(maxX), SIZE) + 20;
        var fromY = Math.floorDiv((int) Math.floor(minY), SIZE) + 18;
        var toY = Math.floorDiv((int) Math.floor(maxY), SIZE) + 18;
        if ((long) (toX - fromX + 1) * (toY - fromY + 1) > 1024) {
            return !tiles.isEmpty();
        }
        for (var x = fromX; x <= toX; x++) {
            for (var y = fromY; y <= toY; y++) {
                if (tiles.contains(pack(x, y))) {
                    return true;
                }
            }
        }
        return false;
    }

    public double[] getBounds() {
        var minX = Integer.MAX_VALUE;
        var minY = Integer.MAX_VALUE;
        var maxX = Integer.MIN_VALUE;
        var maxY = Integer.MIN_VALUE;
        for (var key : tiles) {
            var x = (int) (key >> 32);
            var y = (int) (long) key;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        if (minX > maxX) {
            return null;
        }
        return new double[] {
            (double) (minX - 20) * SIZE,
            (double) (minY - 18) * SIZE,
            (double) (maxX - 19) * SIZE,
            (double) (maxY - 17) * SIZE
        };
    }

    public List<GeoTile> getRegions() {
        var result = new ArrayList<GeoTile>();
        for (var key : tiles) {
            result.add(new GeoTile((int) (key >> 32), (int) (long) key));
        }
        return result;
    }

    public long getRevision() {
        return revision;
    }

    private static Long parseTileKey(String mapName) {
        var matcher = NAME.matcher(mapName == null ? "" : mapName);
        return matcher.matches() ? pack(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))) : null;
    }

    private static long pack(int x, int y) {
        return ((long) x << 32) | (y & 0xffffffffL);
    }
}
