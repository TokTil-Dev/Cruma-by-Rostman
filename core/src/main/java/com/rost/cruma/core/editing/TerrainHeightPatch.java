package com.rost.cruma.core.editing;

import java.util.Arrays;

public final class TerrainHeightPatch {
    private final int width, height;
    private final int[] indices;
    private final short[] before, after;

    public TerrainHeightPatch(int width, int height, int[] indices, short[] before, short[] after) {
        if (width < 4
                || height < 4
                || width > 4096
                || height > 4096
                || indices.length != before.length
                || indices.length != after.length) {
            throw new IllegalArgumentException("Invalid terrain patch dimensions");
        }
        this.width = width;
        this.height = height;
        this.indices = indices.clone();
        this.before = before.clone();
        this.after = after.clone();
        var previous = -1;
        for (var i = 0; i < indices.length; i++) {
            var index = indices[i];
            if (index <= previous || index < 0 || index >= width * height) {
                throw new IllegalArgumentException("Height changes must be unique, ordered and inside the grid");
            }
            if (before[i] == after[i]) {
                throw new IllegalArgumentException("Height patch contains an unchanged sample");
            }
            previous = index;
        }
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int size() {
        return indices.length;
    }

    public int getIndex(int element) {
        return indices[element];
    }

    public int getBefore(int element) {
        return Short.toUnsignedInt(before[element]);
    }

    public int getAfter(int element) {
        return Short.toUnsignedInt(after[element]);
    }

    public long getRetainedBytes() {
        return 8L * indices.length;
    }

    public void apply(short[] target, boolean undo) {
        if (target.length != width * height) {
            throw new IllegalArgumentException("Height patch target dimensions differ");
        }
        var expected = undo ? after : before;
        var replacement = undo ? before : after;
        for (var i = 0; i < indices.length; i++) {
            if (target[indices[i]] != expected[i]) {
                throw new IllegalStateException("Height patch source changed at sample " + indices[i]);
            }
        }
        for (var i = 0; i < indices.length; i++) {
            target[indices[i]] = replacement[i];
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TerrainHeightPatch p
                && width == p.width
                && height == p.height
                && Arrays.equals(indices, p.indices)
                && Arrays.equals(before, p.before)
                && Arrays.equals(after, p.after);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * width + height) + Arrays.hashCode(indices))
                + Arrays.hashCode(before)
                + Arrays.hashCode(after);
    }
}
