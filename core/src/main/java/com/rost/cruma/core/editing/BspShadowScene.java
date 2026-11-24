package com.rost.cruma.core.editing;

import java.util.Arrays;
import java.util.List;

final class BspShadowScene {
    static final int TWO_SIDED = 1;

    static final int FAKE_BACKDROP = 2;

    static final int MESH = 4;

    static final int TERRAIN = 8;

    static final int BSP = 16;

    static final int WATER = 32;

    private static final int LEAF_SIZE = 4;

    private final float[] vertices;
    private final int[] flags;
    private final int[] owners;
    private final float[] nodeBounds;
    private final int[] nodeChildren;
    private final int[] order;
    private final float[] planes;
    private final int[] nodeLinks;

    BspShadowScene(float[] vertices, int[] flags, int[] owners, float[] bspPlanes, int[] bspLinks) {
        if (vertices.length % 9 != 0 || flags.length * 9 != vertices.length || owners.length != flags.length) {
            throw new IllegalArgumentException("Invalid shadow triangles");
        }
        this.vertices = vertices;
        this.flags = flags;
        this.owners = owners;
        this.planes = bspPlanes;
        this.nodeLinks = bspLinks;
        var count = flags.length;
        order = new int[count];
        for (var i = 0; i < count; i++) {
            order[i] = i;
        }
        var capacity = Math.max(1, 2 * count);
        nodeBounds = new float[capacity * 6];
        nodeChildren = new int[capacity * 2];
        var centers = new float[count * 3];
        var bounds = new float[count * 6];
        for (var i = 0; i < count; i++) {
            var o = i * 9;
            for (var axis = 0; axis < 3; axis++) {
                var a = vertices[o + axis];
                var b = vertices[o + 3 + axis];
                var c = vertices[o + 6 + axis];
                var min = Math.min(a, Math.min(b, c));
                var max = Math.max(a, Math.max(b, c));
                bounds[i * 6 + axis] = min;
                bounds[i * 6 + 3 + axis] = max;
                centers[i * 3 + axis] = (min + max) * .5f;
            }
        }
        if (count > 0) {
            build(0, 0, count, new int[] {1}, centers, bounds);
        }
    }

    BspShadowScene withScenes(List<BspShadowScene> others, int[] surfaceOffsets, int[] actorOffsets, int kinds) {
        var count = flags.length;
        for (var other : others) {
            for (var kind : other.flags) {
                count += isIncluded(kind, kinds) ? 1 : 0;
            }
        }
        var joinedVertices = Arrays.copyOf(vertices, count * 9);
        var joinedFlags = Arrays.copyOf(flags, count);
        var joinedOwners = Arrays.copyOf(owners, count);
        var next = flags.length;
        for (var i = 0; i < others.size(); i++) {
            var other = others.get(i);
            for (var t = 0; t < other.flags.length; t++) {
                var kind = other.flags[t];
                if (!isIncluded(kind, kinds)) {
                    continue;
                }
                System.arraycopy(other.vertices, t * 9, joinedVertices, next * 9, 9);
                joinedFlags[next] = kind;
                joinedOwners[next] = other.owners[t]
                        + ((kind & BSP) != 0 ? surfaceOffsets[i] : (kind & MESH) != 0 ? actorOffsets[i] : 0);
                next++;
            }
        }
        return new BspShadowScene(joinedVertices, joinedFlags, joinedOwners, planes, nodeLinks);
    }

    int getTriangleCount() {
        return flags.length;
    }

    int getFlags(int triangle) {
        return flags[triangle];
    }

    int getOwner(int triangle) {
        return owners[triangle];
    }

    int findZone(float x, float y, float z) {
        if (nodeLinks.length == 0) {
            return 0;
        }
        var node = 0;
        var parent = 0;
        var front = false;
        do {
            parent = node;
            var o = node * 4;
            front = (planes[o + 1] * y + planes[o] * x) + planes[o + 2] * z - planes[o + 3] >= 0f;
            node = nodeLinks[node * 4 + (front ? 0 : 1)];
        } while (node >= 0);
        return nodeLinks[parent * 4 + (front ? 2 : 3)];
    }

    @FunctionalInterface
    interface Filter {
        boolean skip(int triangle);
    }

    boolean isBlocked(
            double ox,
            double oy,
            double oz,
            double dx,
            double dy,
            double dz,
            double tMin,
            double tMax,
            int cull,
            Filter filter) {
        return findBlocker(ox, oy, oz, dx, dy, dz, tMin, tMax, cull, filter) >= 0;
    }

    int findBlocker(
            double ox,
            double oy,
            double oz,
            double dx,
            double dy,
            double dz,
            double tMin,
            double tMax,
            int cull,
            Filter filter) {
        if (order.length == 0 || !(tMax >= tMin)) {
            return -1;
        }
        var ix = dx == 0 ? Double.POSITIVE_INFINITY : 1.0 / dx;
        var iy = dy == 0 ? Double.POSITIVE_INFINITY : 1.0 / dy;
        var iz = dz == 0 ? Double.POSITIVE_INFINITY : 1.0 / dz;
        var stack = new int[128];
        var top = 0;
        stack[top++] = 0;
        while (top > 0) {
            var node = stack[--top];
            if (!overlaps(node * 6, ox, oy, oz, ix, iy, iz, tMin, tMax)) {
                continue;
            }
            var left = nodeChildren[node * 2];
            var right = nodeChildren[node * 2 + 1];
            if (left < 0) {
                for (var i = -left - 1; i < right; i++) {
                    var triangle = order[i];
                    if (filter != null && filter.skip(triangle)) {
                        continue;
                    }
                    if (hitsTriangle(triangle, ox, oy, oz, dx, dy, dz, tMin, tMax, cull)) {
                        return triangle;
                    }
                }
            } else {
                if (top + 2 > stack.length) {
                    stack = Arrays.copyOf(stack, stack.length * 2);
                }
                stack[top++] = left;
                stack[top++] = right;
            }
        }
        return -1;
    }

    private boolean hitsTriangle(
            int triangle,
            double ox,
            double oy,
            double oz,
            double dx,
            double dy,
            double dz,
            double tMin,
            double tMax,
            int cull) {
        var o = triangle * 9;
        double ax = vertices[o], ay = vertices[o + 1], az = vertices[o + 2];
        double e1x = vertices[o + 3] - ax, e1y = vertices[o + 4] - ay, e1z = vertices[o + 5] - az;
        double e2x = vertices[o + 6] - ax, e2y = vertices[o + 7] - ay, e2z = vertices[o + 8] - az;
        if (cull != 0 && (flags[triangle] & (TWO_SIDED | BSP)) == BSP) {
            var nx = e1y * e2z - e1z * e2y;
            var ny = e1z * e2x - e1x * e2z;
            var nz = e1x * e2y - e1y * e2x;
            var facing = nx * dx + ny * dy + nz * dz;
            if (cull == 1 ? facing >= 0 : facing <= 0) {
                return false;
            }
        }
        var px = dy * e2z - dz * e2y;
        var py = dz * e2x - dx * e2z;
        var pz = dx * e2y - dy * e2x;
        var det = e1x * px + e1y * py + e1z * pz;
        if (det == 0) {
            return false;
        }
        var inv = 1.0 / det;
        var sx = ox - ax;
        var sy = oy - ay;
        var sz = oz - az;
        var u = (sx * px + sy * py + sz * pz) * inv;
        if (u < 0 || u > 1) {
            return false;
        }
        var qx = sy * e1z - sz * e1y;
        var qy = sz * e1x - sx * e1z;
        var qz = sx * e1y - sy * e1x;
        var v = (dx * qx + dy * qy + dz * qz) * inv;
        if (v < 0 || u + v > 1) {
            return false;
        }
        var t = (e2x * qx + e2y * qy + e2z * qz) * inv;
        return t >= tMin && t <= tMax;
    }

    private boolean overlaps(
            int offset, double ox, double oy, double oz, double ix, double iy, double iz, double tMin, double tMax) {
        var near = tMin;
        var far = tMax;
        for (var axis = 0; axis < 3; axis++) {
            var origin = axis == 0 ? ox : axis == 1 ? oy : oz;
            var inverse = axis == 0 ? ix : axis == 1 ? iy : iz;
            var min = nodeBounds[offset + axis] - .01;
            var max = nodeBounds[offset + 3 + axis] + .01;
            if (Double.isInfinite(inverse)) {
                if (origin < min || origin > max) {
                    return false;
                }
                continue;
            }
            var t0 = (min - origin) * inverse;
            var t1 = (max - origin) * inverse;
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

    private void build(int node, int start, int end, int[] next, float[] centers, float[] bounds) {
        var o = node * 6;
        for (var axis = 0; axis < 3; axis++) {
            nodeBounds[o + axis] = Float.POSITIVE_INFINITY;
            nodeBounds[o + 3 + axis] = Float.NEGATIVE_INFINITY;
        }
        for (var i = start; i < end; i++) {
            var item = order[i];
            for (var axis = 0; axis < 3; axis++) {
                nodeBounds[o + axis] = Math.min(nodeBounds[o + axis], bounds[item * 6 + axis]);
                nodeBounds[o + 3 + axis] = Math.max(nodeBounds[o + 3 + axis], bounds[item * 6 + 3 + axis]);
            }
        }
        if (end - start <= LEAF_SIZE) {
            nodeChildren[node * 2] = -(start + 1);
            nodeChildren[node * 2 + 1] = end;
            return;
        }
        var axis = 0;
        var extent = -1f;
        for (var a = 0; a < 3; a++) {
            var e = nodeBounds[o + 3 + a] - nodeBounds[o + a];
            if (e > extent) {
                extent = e;
                axis = a;
            }
        }
        var middle = (start + end) >>> 1;
        select(start, end - 1, middle, axis, centers);
        var left = next[0]++;
        var right = next[0]++;
        nodeChildren[node * 2] = left;
        nodeChildren[node * 2 + 1] = right;
        build(left, start, middle, next, centers, bounds);
        build(right, middle, end, next, centers, bounds);
    }

    private void select(int low, int high, int k, int axis, float[] centers) {
        while (low < high) {
            var pivot = centers[order[(low + high) >>> 1] * 3 + axis];
            var i = low;
            var j = high;
            while (i <= j) {
                while (centers[order[i] * 3 + axis] < pivot) {
                    i++;
                }
                while (centers[order[j] * 3 + axis] > pivot) {
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

    private static boolean isIncluded(int kind, int kinds) {
        return (kind & kinds) != 0 && (kind & FAKE_BACKDROP) == 0;
    }
}
