package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.geometry.Bounds3f;
import org.jetbrains.annotations.Nullable;

final class ClearanceBoxes {
    static final float WIDE = 1024f;

    static final int CELLS = 16;

    static final float SMALLEST_CELL = 256f;

    static final int FLOATS = 6;

    private ClearanceBoxes() {}

    interface Positions {
        float getX(int vertex);

        float getY(int vertex);

        float getZ(int vertex);
    }

    static boolean isWide(Bounds3f box) {
        return box.maxX() - box.minX() >= WIDE || box.maxY() - box.minY() >= WIDE || box.maxZ() - box.minZ() >= WIDE;
    }

    static float @Nullable [] of(Positions positions, int[] indices) {
        var triangles = indices.length / 3;
        if (triangles == 0) {
            return null;
        }
        var min = new float[] {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        var max = new float[] {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        var point = new float[3];
        for (var cursor = 0; cursor < triangles * 3; cursor++) {
            read(positions, indices[cursor], point);
            for (var axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], point[axis]);
                max[axis] = Math.max(max[axis], point[axis]);
            }
        }
        var cells = new int[3];
        var wide = false;
        for (var axis = 0; axis < 3; axis++) {
            var extent = max[axis] - min[axis];
            if (!Float.isFinite(extent)) {
                return null;
            }
            wide |= extent >= WIDE;
            cells[axis] = Math.clamp((int) Math.ceil(extent / SMALLEST_CELL), 1, CELLS);
        }
        if (!wide) {
            return null;
        }
        var boxes = new float[cells[0] * cells[1] * cells[2] * FLOATS];
        var used = new boolean[cells[0] * cells[1] * cells[2]];
        var low = new float[3];
        var high = new float[3];
        var count = 0;
        for (var triangle = 0; triangle < triangles; triangle++) {
            for (var corner = 0; corner < 3; corner++) {
                read(positions, indices[triangle * 3 + corner], point);
                for (var axis = 0; axis < 3; axis++) {
                    low[axis] = corner == 0 ? point[axis] : Math.min(low[axis], point[axis]);
                    high[axis] = corner == 0 ? point[axis] : Math.max(high[axis], point[axis]);
                }
            }
            var cell = 0;
            for (var axis = 2; axis >= 0; axis--) {
                var extent = max[axis] - min[axis];
                var middle = (low[axis] + high[axis]) * .5f;
                var index = extent > 0f
                        ? Math.clamp((int) ((middle - min[axis]) / extent * cells[axis]), 0, cells[axis] - 1)
                        : 0;
                cell = cell * cells[axis] + index;
            }
            var offset = cell * FLOATS;
            if (!used[cell]) {
                used[cell] = true;
                count++;
                System.arraycopy(low, 0, boxes, offset, 3);
                System.arraycopy(high, 0, boxes, offset + 3, 3);
            } else {
                for (var axis = 0; axis < 3; axis++) {
                    boxes[offset + axis] = Math.min(boxes[offset + axis], low[axis]);
                    boxes[offset + 3 + axis] = Math.max(boxes[offset + 3 + axis], high[axis]);
                }
            }
        }
        var result = new float[count * FLOATS];
        var next = 0;
        for (var cell = 0; cell < used.length; cell++) {
            if (used[cell]) {
                System.arraycopy(boxes, cell * FLOATS, result, next, FLOATS);
                next += FLOATS;
            }
        }
        return result;
    }

    static double nearest(float[] boxes, float[] matrix, int offset, float x, float y, float z, double best) {
        for (var box = 0; box + FLOATS <= boxes.length; box += FLOATS) {
            var middleX = (boxes[box] + (double) boxes[box + 3]) * .5;
            var middleY = (boxes[box + 1] + (double) boxes[box + 4]) * .5;
            var middleZ = (boxes[box + 2] + (double) boxes[box + 5]) * .5;
            var halfX = boxes[box + 3] - middleX;
            var halfY = boxes[box + 4] - middleY;
            var halfZ = boxes[box + 5] - middleZ;
            var squared = 0.0;
            for (var axis = 0; axis < 3; axis++) {
                var middle = matrix[offset + axis] * middleX
                        + matrix[offset + 4 + axis] * middleY
                        + matrix[offset + 8 + axis] * middleZ
                        + matrix[offset + 12 + axis];
                var half = Math.abs(matrix[offset + axis]) * halfX
                        + Math.abs(matrix[offset + 4 + axis]) * halfY
                        + Math.abs(matrix[offset + 8 + axis]) * halfZ;
                var away = Math.abs((axis == 0 ? x : axis == 1 ? y : z) - middle) - half;
                if (away > 0) {
                    squared += away * away;
                }
            }
            if (squared < best * best) {
                best = Math.sqrt(squared);
            }
        }
        return best;
    }

    private static void read(Positions positions, int vertex, float[] point) {
        point[0] = positions.getX(vertex);
        point[1] = positions.getY(vertex);
        point[2] = positions.getZ(vertex);
    }
}
