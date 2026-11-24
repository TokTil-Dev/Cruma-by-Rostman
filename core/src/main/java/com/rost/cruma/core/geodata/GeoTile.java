package com.rost.cruma.core.geodata;

public record GeoTile(int x, int y) {
    public static final int WORLD_SIZE = 32768;

    public static GeoTile fromWorld(int worldX, int worldY) {
        return new GeoTile(Math.floorDiv(worldX, WORLD_SIZE) + 20, Math.floorDiv(worldY, WORLD_SIZE) + 18);
    }

    public boolean containsWorld() {
        return x >= 10 && x <= 29 && y >= 10 && y <= 26;
    }

    public int worldMinX() {
        return (x - 20) * WORLD_SIZE;
    }

    public int worldMinY() {
        return (y - 18) * WORLD_SIZE;
    }

    public String fileName() {
        return x + "_" + y + "_conv.dat";
    }
}
