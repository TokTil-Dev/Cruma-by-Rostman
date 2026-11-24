package com.rost.cruma.core.geodata.generation;

import java.util.Arrays;

final class StartPointList {
    private float[] values = new float[64];
    private int size;

    static boolean hasLayer(BuiltRegion region, float x, float y, int lowest, int highest) {
        var cellX = region.toCellX(x);
        var cellY = region.toCellY(y);
        if (cellX < 0 || cellY < 0) {
            return false;
        }
        for (var layer = region.getFirstLayer(cellX, cellY); layer >= 0; layer = region.nextLayer(layer)) {
            var height = region.getHeight(layer);
            if (height >= lowest && height <= highest) {
                return true;
            }
        }
        return false;
    }

    void add(float x, float y, float dropFrom, float lowest, float highest) {
        if (size + 5 > values.length) {
            values = Arrays.copyOf(values, values.length * 2);
        }
        values[size++] = x;
        values[size++] = y;
        values[size++] = dropFrom;
        values[size++] = lowest;
        values[size++] = highest;
    }

    float[] toArray() {
        return Arrays.copyOf(values, size);
    }
}
