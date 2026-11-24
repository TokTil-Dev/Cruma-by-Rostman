package com.rost.cruma.core.area;

public final class AreaTiles {
    public static final int SIZE = 32768;
    public static final int MIN_X = 10;
    public static final int MAX_X = 29;
    public static final int MIN_Y = 10;
    public static final int MAX_Y = 26;

    private AreaTiles() {}

    public static int tileX(double x) {
        return (((int) x + 0x50000) >> 15) + 10;
    }

    public static int tileY(double y) {
        return (((int) y + 0x40000) >> 15) + 10;
    }

    public static int originX(int tileX) {
        return (tileX - 20) * SIZE;
    }

    public static int originY(int tileY) {
        return (tileY - 18) * SIZE;
    }

    public static boolean isInTable(int tileX, int tileY) {
        return tileX >= MIN_X && tileX <= MAX_X && tileY >= MIN_Y && tileY <= MAX_Y;
    }

    public static String format(int tileX, int tileY) {
        return tileX + "_" + tileY;
    }
}
