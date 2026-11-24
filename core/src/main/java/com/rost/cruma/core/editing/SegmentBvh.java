package com.rost.cruma.core.editing;

import java.util.Arrays;
import java.util.function.IntPredicate;

final class SegmentBvh {
    private static final int LEAF_SIZE = 4;
    private static final float MARGIN = 0.5f;

    private final float[] itemBounds;
    private final float[] nodeBounds;
    private final int[] nodeChildren;
    private final int[] order;

    SegmentBvh(float[] bounds) {
        if (bounds.length % 6 != 0) {
            throw new IllegalArgumentException("Box bounds need six values per item");
        }
        itemBounds = bounds.clone();
        var count = bounds.length / 6;
        order = new int[count];
        for (var i = 0; i < count; i++) {
            order[i] = i;
        }
        var capacity = Math.max(1, 2 * count);
        nodeBounds = new float[capacity * 6];
        nodeChildren = new int[capacity * 2];
        if (count > 0) {
            var next = new int[] {1};
            build(0, 0, count, next);
        }
    }

    int size() {
        return order.length;
    }

    boolean hasAnyHit(float sx, float sy, float sz, float ex, float ey, float ez, IntPredicate hit) {
        if (order.length == 0) {
            return false;
        }
        var dx = ex - sx;
        var dy = ey - sy;
        var dz = ez - sz;
        var ix = dx == 0 ? Float.POSITIVE_INFINITY : 1f / dx;
        var iy = dy == 0 ? Float.POSITIVE_INFINITY : 1f / dy;
        var iz = dz == 0 ? Float.POSITIVE_INFINITY : 1f / dz;
        var stack = new int[64];
        var top = 0;
        stack[top++] = 0;
        while (top > 0) {
            var node = stack[--top];
            if (!overlaps(nodeBounds, node * 6, sx, sy, sz, ix, iy, iz)) {
                continue;
            }
            var left = nodeChildren[node * 2];
            var right = nodeChildren[node * 2 + 1];
            if (left < 0) {
                for (var i = -left - 1; i < right; i++) {
                    var item = order[i];
                    if (overlaps(itemBounds, item * 6, sx, sy, sz, ix, iy, iz) && hit.test(item)) {
                        return true;
                    }
                }
                continue;
            }
            if (top + 2 > stack.length) {
                stack = Arrays.copyOf(stack, stack.length * 2);
            }
            stack[top++] = left;
            stack[top++] = right;
        }
        return false;
    }

    private void build(int node, int from, int to, int[] next) {
        var base = node * 6;
        for (var a = 0; a < 3; a++) {
            nodeBounds[base + a] = Float.POSITIVE_INFINITY;
            nodeBounds[base + 3 + a] = Float.NEGATIVE_INFINITY;
        }
        for (var i = from; i < to; i++) {
            var item = order[i] * 6;
            for (var a = 0; a < 3; a++) {
                nodeBounds[base + a] = Math.min(nodeBounds[base + a], itemBounds[item + a]);
                nodeBounds[base + 3 + a] = Math.max(nodeBounds[base + 3 + a], itemBounds[item + 3 + a]);
            }
        }
        if (to - from <= LEAF_SIZE) {
            nodeChildren[node * 2] = -from - 1;
            nodeChildren[node * 2 + 1] = to;
            return;
        }
        var axis = 0;
        for (var a = 1; a < 3; a++) {
            if (nodeBounds[base + 3 + a] - nodeBounds[base + a]
                    > nodeBounds[base + 3 + axis] - nodeBounds[base + axis]) {
                axis = a;
            }
        }
        var middle = (from + to) >>> 1;
        select(from, to - 1, middle, axis);
        var left = next[0]++;
        var right = next[0]++;
        nodeChildren[node * 2] = left;
        nodeChildren[node * 2 + 1] = right;
        build(left, from, middle, next);
        build(right, middle, to, next);
    }

    private float doubledCenter(int item, int axis) {
        return itemBounds[item * 6 + axis] + itemBounds[item * 6 + 3 + axis];
    }

    private void select(int low, int high, int k, int axis) {
        while (low < high) {
            var pivot = doubledCenter(order[(low + high) >>> 1], axis);
            var i = low;
            var j = high;
            while (i <= j) {
                while (doubledCenter(order[i], axis) < pivot) {
                    i++;
                }
                while (doubledCenter(order[j], axis) > pivot) {
                    j--;
                }
                if (i <= j) {
                    var swap = order[i];
                    order[i] = order[j];
                    order[j] = swap;
                    i++;
                    j--;
                }
            }
            if (k <= j) {
                high = j;
            } else if (k >= i) {
                low = i;
            } else {
                return;
            }
        }
    }

    private static boolean overlaps(
            float[] bounds, int offset, float sx, float sy, float sz, float ix, float iy, float iz) {
        var near = 0f;
        var far = 1f;
        for (var a = 0; a < 3; a++) {
            var origin = a == 0 ? sx : a == 1 ? sy : sz;
            var inverse = a == 0 ? ix : a == 1 ? iy : iz;
            var low = bounds[offset + a] - MARGIN;
            var high = bounds[offset + 3 + a] + MARGIN;
            if (Float.isInfinite(inverse)) {
                if (origin < low || origin > high) {
                    return false;
                }
                continue;
            }
            var t0 = (low - origin) * inverse;
            var t1 = (high - origin) * inverse;
            if (t0 > t1) {
                var swap = t0;
                t0 = t1;
                t1 = swap;
            }
            if (t0 > near) {
                near = t0;
            }
            if (t1 < far) {
                far = t1;
            }
            if (near > far) {
                return false;
            }
        }
        return true;
    }
}
