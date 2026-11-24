package com.rost.cruma.viewer.render;

import com.rost.cruma.core.geometry.Bounds3f;
import java.util.Arrays;
import org.jetbrains.annotations.Nullable;

public final class SceneExtent {
    static final float TALLEST_PART = 30_000f;

    static final double TRIMMED = .01;

    private final float[] mins;
    private final float[] maxes;
    private int count;
    private float terrainMin = Float.POSITIVE_INFINITY;
    private float terrainMax = Float.NEGATIVE_INFINITY;

    public SceneExtent(int capacity) {
        mins = new float[capacity * 3];
        maxes = new float[capacity * 3];
    }

    public void add(Bounds3f part, boolean terrain) {
        if (part.maxZ() - part.minZ() > TALLEST_PART || count * 3 >= mins.length) {
            return;
        }
        if (terrain) {
            terrainMin = Math.min(terrainMin, part.minZ());
            terrainMax = Math.max(terrainMax, part.maxZ());
        }
        var offset = count * 3;
        mins[offset] = part.minX();
        mins[offset + 1] = part.minY();
        mins[offset + 2] = part.minZ();
        maxes[offset] = part.maxX();
        maxes[offset + 1] = part.maxY();
        maxes[offset + 2] = part.maxZ();
        count++;
    }

    public @Nullable Bounds3f getBounds() {
        if (count == 0) {
            return null;
        }
        return new Bounds3f(
                low(0), low(1), Math.min(low(2), terrainMin), high(0), high(1), Math.max(high(2), terrainMax));
    }

    private float low(int axis) {
        return sorted(mins, axis)[(int) (count * TRIMMED)];
    }

    private float high(int axis) {
        return sorted(maxes, axis)[count - 1 - (int) (count * TRIMMED)];
    }

    private float[] sorted(float[] values, int axis) {
        var result = new float[count];
        for (var index = 0; index < count; index++) {
            result[index] = values[index * 3 + axis];
        }
        Arrays.sort(result);
        return result;
    }
}
