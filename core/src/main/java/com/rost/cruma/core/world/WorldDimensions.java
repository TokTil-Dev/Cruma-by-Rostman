package com.rost.cruma.core.world;

public final class WorldDimensions {
    public static final int TILE_X_MIN = 11;
    public static final int TILE_X_MAX = 26;
    public static final int TILE_Y_MIN = 10;
    public static final int TILE_Y_MAX = 25;

    public static final int TILES_X = TILE_X_MAX - TILE_X_MIN + 1;
    public static final int TILES_Y = TILE_Y_MAX - TILE_Y_MIN + 1;

    public static final int TILE_ZERO_COORD_X = 20;
    public static final int TILE_ZERO_COORD_Y = 18;
    public static final int TILE_SIZE = 32768;

    public static final int WORLD_X_MIN = (TILE_X_MIN - TILE_ZERO_COORD_X) * TILE_SIZE;
    public static final int WORLD_X_MAX = ((TILE_X_MAX - TILE_ZERO_COORD_X) + 1) * TILE_SIZE;
    public static final int WORLD_Y_MIN = (TILE_Y_MIN - TILE_ZERO_COORD_Y) * TILE_SIZE;
    public static final int WORLD_Y_MAX = ((TILE_Y_MAX - TILE_ZERO_COORD_Y) + 1) * TILE_SIZE;
    public static final int WORLD_Z_MAX = 16410;
    public static final long WORLD_AREA = (long) (WORLD_X_MAX - WORLD_X_MIN) * (WORLD_Y_MAX - WORLD_Y_MIN);

    public static final int WORLD_X_LEN = WORLD_X_MAX - WORLD_X_MIN;
    public static final int WORLD_Y_LEN = WORLD_Y_MAX - WORLD_Y_MIN;

    private WorldDimensions() {}

    public static boolean isOutOfWorld(int x, int y) {
        return isOutOfWorld(x, x, y, y);
    }

    public static boolean isOutOfWorld(int minX, int maxX, int minY, int maxY) {
        return minX < WORLD_X_MIN || maxX > WORLD_X_MAX || minY < WORLD_Y_MIN || maxY > WORLD_Y_MAX;
    }

    public static int getTileIndex(float x, float y) {
        var tileX = (int) ((x - WORLD_X_MIN) / TILE_SIZE);
        var tileY = (int) ((y - WORLD_Y_MIN) / TILE_SIZE);
        return tileY * TILES_X + tileX;
    }

    public static int getTileIndex(int tileX, int tileY) {
        return (tileY - TILE_Y_MIN) * TILES_X + (tileX - TILE_X_MIN);
    }
}
