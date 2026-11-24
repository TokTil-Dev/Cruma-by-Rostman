package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.world.WorldDimensions;
import java.util.List;
import java.util.Set;

public final class WorldMapCoordinates {
    private final Set<String> tiles;
    public final int minX;
    public final int minY;
    public final int columns;
    public final int rows;

    public WorldMapCoordinates(List<String> tiles) {
        if (tiles.isEmpty()) {
            throw new IllegalArgumentException("No map tiles");
        }
        this.tiles = Set.copyOf(tiles);
        var xs = tiles.stream()
                .mapToInt(tile -> Integer.parseInt(tile.split("_")[0]))
                .summaryStatistics();
        var ys = tiles.stream()
                .mapToInt(tile -> Integer.parseInt(tile.split("_")[1]))
                .summaryStatistics();
        minX = xs.getMin();
        minY = ys.getMin();
        columns = Math.toIntExact((long) xs.getMax() - minX + 1);
        rows = Math.toIntExact((long) ys.getMax() - minY + 1);
    }

    public float toU(float worldX) {
        return (worldX / WorldDimensions.TILE_SIZE + WorldDimensions.TILE_ZERO_COORD_X - minX) / columns;
    }

    public float toV(float worldY) {
        return (worldY / WorldDimensions.TILE_SIZE + WorldDimensions.TILE_ZERO_COORD_Y - minY) / rows;
    }

    public float toWorldX(float u) {
        return (minX - WorldDimensions.TILE_ZERO_COORD_X + u * columns) * WorldDimensions.TILE_SIZE;
    }

    public float toWorldY(float v) {
        return (minY - WorldDimensions.TILE_ZERO_COORD_Y + v * rows) * WorldDimensions.TILE_SIZE;
    }

    public String getTileAt(float u, float v) {
        if (!Float.isFinite(u) || !Float.isFinite(v) || u < 0 || u >= 1 || v < 0 || v >= 1) {
            return null;
        }
        return (minX + (int) (u * columns)) + "_" + (minY + (int) (v * rows));
    }

    public boolean isInstalled(String tile) {
        return tile != null && tiles.contains(tile);
    }
}
