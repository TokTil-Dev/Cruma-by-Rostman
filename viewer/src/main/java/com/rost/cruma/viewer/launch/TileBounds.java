package com.rost.cruma.viewer.launch;

import java.util.Collection;

public record TileBounds(int minX, int minY, int maxX, int maxY) {
    public static TileBounds of(Collection<String> tiles) {
        if (tiles.isEmpty()) {
            throw new IllegalArgumentException("No tiles to bound.");
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (var tile : tiles) {
            var x = x(tile);
            var y = y(tile);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        return new TileBounds(minX, minY, maxX, maxY);
    }

    public static int x(String tile) {
        return Integer.parseInt(tile.substring(0, tile.indexOf('_')));
    }

    public static int y(String tile) {
        return Integer.parseInt(tile.substring(tile.indexOf('_') + 1));
    }

    public String getMiddleTile() {
        return Math.floorDiv(minX + maxX + 1, 2) + "_" + Math.floorDiv(minY + maxY + 1, 2);
    }

    public String getRange() {
        return minX + "_" + minY + " to " + maxX + "_" + maxY;
    }

    public boolean contains(int x, int y) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY;
    }
}
