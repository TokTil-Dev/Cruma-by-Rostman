package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.Bsp.BspNodeFlags;
import com.rost.cruma.core.unreal.Bsp.BspPolygonFlags;
import com.rost.cruma.core.unreal.Bsp.UBspNode;
import com.rost.cruma.core.unreal.Bsp.UBspSurface;
import com.rost.cruma.core.unreal.Bsp.UModel;
import glm_.vec3.Vec3;
import java.util.Arrays;
import org.jetbrains.annotations.Nullable;

final class SunVisibility {
    static final Vec3 SUN_POINT = new Vec3(349580f, -245600f, 159000f);

    private static final float ORIGIN_BIAS_Z = 8f;
    private static final float T_MIN = 4f;
    private static final float PLANE_EPS = 1e-6f;
    private static final float EDGE_EPS = 1e-3f;
    private static final float MESH_OCCLUDER_MIN_EXTENT = 256f;
    private static final float UNDER_TERRAIN_MARGIN = 1000f;
    private static final float MESH_GRID_CELL = 2048f;
    private static final int AABB_BVH_LEAF_ITEMS = 8;
    private static final int AABB_BVH_MIN_ITEMS = 32;

    private static final float[] SKY_DIRS = buildSkyDirs();

    private SunVisibility() {}

    static boolean isOccluded(@Nullable Occluders csg, Vec3 pos) {
        return isOccluded(csg, null, null, -1, pos);
    }

    static boolean isOccluded(
            @Nullable Occluders csg,
            @Nullable MeshOccluders meshes,
            @Nullable TerrainField terrain,
            int selfIndex,
            Vec3 pos) {
        if (terrain != null && terrain.occludes(pos.getX(), pos.getY(), pos.getZ())) {
            return true;
        }
        var haveCsg = csg != null && csg.count > 0;
        var haveMesh = meshes != null && meshes.count > 0;
        if (!haveCsg && !haveMesh) {
            return false;
        }
        var ox = pos.getX();
        var oy = pos.getY();
        var oz = pos.getZ() + ORIGIN_BIAS_Z;
        var dx = SUN_POINT.getX() - ox;
        var dy = SUN_POINT.getY() - oy;
        var dz = SUN_POINT.getZ() - oz;
        var len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4f) {
            return false;
        }
        var ndx = dx / len;
        var ndy = dy / len;
        var ndz = dz / len;
        if (haveCsg && csg.blocks(ox, oy, oz, ndx, ndy, ndz)) {
            return true;
        }
        return haveMesh && meshes.blocks(selfIndex, ox, oy, oz, ndx, ndy, ndz);
    }

    static int skyDirCount() {
        return SKY_DIRS.length / 3;
    }

    static float skyVisibility(
            @Nullable Occluders csg,
            @Nullable MeshOccluders meshes,
            @Nullable TerrainField terrain,
            int selfIndex,
            Vec3 pos) {
        if (terrain != null && terrain.occludes(pos.getX(), pos.getY(), pos.getZ())) {
            return 0f;
        }
        var haveCsg = csg != null && csg.getCount() > 0;
        var haveMesh = meshes != null && meshes.getCount() > 0;
        if (!haveCsg && !haveMesh) {
            return 1f;
        }
        var ox = pos.getX();
        var oy = pos.getY();
        var oz = pos.getZ() + ORIGIN_BIAS_Z;
        var n = SKY_DIRS.length / 3;
        var escaped = 0;
        for (int i = 0; i < n; i++) {
            var dx = SKY_DIRS[i * 3];
            var dy = SKY_DIRS[i * 3 + 1];
            var dz = SKY_DIRS[i * 3 + 2];
            var blocked = haveCsg && csg.blocks(ox, oy, oz, dx, dy, dz);
            if (!blocked && haveMesh) {
                blocked = meshes.blocks(selfIndex, ox, oy, oz, dx, dy, dz);
            }
            if (!blocked) {
                escaped++;
            }
        }
        return escaped / (float) n;
    }

    static final class Occluders {
        private final int count;
        private final float[] planes;
        private final float[] aabb;
        private final float[] verts;
        private final int[] vertOffset;
        private final int[] vertCount;
        private @Nullable AabbHierarchy hierarchy;
        private final int[] traversal = new int[64];

        Occluders(int count, float[] planes, float[] aabb, float[] verts, int[] vertOffset, int[] vertCount) {
            this.count = count;
            this.planes = planes;
            this.aabb = aabb;
            this.verts = verts;
            this.vertOffset = vertOffset;
            this.vertCount = vertCount;
        }

        int getCount() {
            return count;
        }

        boolean blocks(float ox, float oy, float oz, float dx, float dy, float dz) {
            var upward = dz > 0f;
            if (count < AABB_BVH_MIN_ITEMS) {
                for (int i = 0; i < count; i++) {
                    if (blocksRay(i, upward, ox, oy, oz, dx, dy, dz)) {
                        return true;
                    }
                }
                return false;
            }
            if (hierarchy == null) {
                hierarchy = new AabbHierarchy(aabb, count);
            }
            var pending = 0;
            traversal[pending++] = 0;
            while (pending > 0) {
                var node = traversal[--pending];
                if (!doesRayHitAabb(hierarchy.nodeBounds, node * 6, ox, oy, oz, dx, dy, dz)) {
                    continue;
                }
                var nodeCount = hierarchy.nodeItemCount[node];
                if (nodeCount == 0) {
                    traversal[pending++] = hierarchy.nodeLeft[node];
                    traversal[pending++] = hierarchy.nodeRight[node];
                    continue;
                }
                var end = hierarchy.nodeFirst[node] + nodeCount;
                for (int k = hierarchy.nodeFirst[node]; k < end; k++) {
                    if (blocksRay(hierarchy.itemOrder[k], upward, ox, oy, oz, dx, dy, dz)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean blocksRay(int i, boolean upward, float ox, float oy, float oz, float dx, float dy, float dz) {
            var b = i * 6;
            if (upward && aabb[b + 5] < oz) {
                return false;
            }
            if (!doesRayHitAabb(aabb, b, ox, oy, oz, dx, dy, dz)) {
                return false;
            }
            var p = i * 4;
            var nd = planes[p] * dx + planes[p + 1] * dy + planes[p + 2] * dz;
            if (nd > -PLANE_EPS && nd < PLANE_EPS) {
                return false;
            }
            var t = (planes[p + 3] - (planes[p] * ox + planes[p + 1] * oy + planes[p + 2] * oz)) / nd;
            if (t < T_MIN) {
                return false;
            }
            var hx = ox + t * dx;
            var hy = oy + t * dy;
            var hz = oz + t * dz;
            return isPointInConvexPolygon(planes, p, verts, vertOffset[i], vertCount[i], hx, hy, hz);
        }
    }

    private static final class AabbHierarchy {
        private final int[] itemOrder;
        private final float[] nodeBounds;
        private final int[] nodeLeft;
        private final int[] nodeRight;
        private final int[] nodeFirst;
        private final int[] nodeItemCount;
        private int nodes;

        AabbHierarchy(float[] itemBounds, int count) {
            itemOrder = new int[count];
            var centers = new float[count * 3];
            for (int i = 0; i < count; i++) {
                itemOrder[i] = i;
                var b = i * 6;
                var c = i * 3;
                centers[c] = (itemBounds[b] + itemBounds[b + 3]) * 0.5f;
                centers[c + 1] = (itemBounds[b + 1] + itemBounds[b + 4]) * 0.5f;
                centers[c + 2] = (itemBounds[b + 2] + itemBounds[b + 5]) * 0.5f;
            }
            var capacity = Math.max(1, count * 2);
            nodeBounds = new float[capacity * 6];
            nodeLeft = new int[capacity];
            nodeRight = new int[capacity];
            nodeFirst = new int[capacity];
            nodeItemCount = new int[capacity];
            build(0, count, itemBounds, centers);
        }

        private int build(int first, int end, float[] itemBounds, float[] centers) {
            var node = nodes++;
            var nb = node * 6;
            nodeBounds[nb] = Float.MAX_VALUE;
            nodeBounds[nb + 1] = Float.MAX_VALUE;
            nodeBounds[nb + 2] = Float.MAX_VALUE;
            nodeBounds[nb + 3] = -Float.MAX_VALUE;
            nodeBounds[nb + 4] = -Float.MAX_VALUE;
            nodeBounds[nb + 5] = -Float.MAX_VALUE;
            var minCx = Float.MAX_VALUE;
            var minCy = Float.MAX_VALUE;
            var minCz = Float.MAX_VALUE;
            var maxCx = -Float.MAX_VALUE;
            var maxCy = -Float.MAX_VALUE;
            var maxCz = -Float.MAX_VALUE;
            for (int i = first; i < end; i++) {
                var item = itemOrder[i];
                var b = item * 6;
                nodeBounds[nb] = Math.min(nodeBounds[nb], itemBounds[b]);
                nodeBounds[nb + 1] = Math.min(nodeBounds[nb + 1], itemBounds[b + 1]);
                nodeBounds[nb + 2] = Math.min(nodeBounds[nb + 2], itemBounds[b + 2]);
                nodeBounds[nb + 3] = Math.max(nodeBounds[nb + 3], itemBounds[b + 3]);
                nodeBounds[nb + 4] = Math.max(nodeBounds[nb + 4], itemBounds[b + 4]);
                nodeBounds[nb + 5] = Math.max(nodeBounds[nb + 5], itemBounds[b + 5]);
                var c = item * 3;
                minCx = Math.min(minCx, centers[c]);
                minCy = Math.min(minCy, centers[c + 1]);
                minCz = Math.min(minCz, centers[c + 2]);
                maxCx = Math.max(maxCx, centers[c]);
                maxCy = Math.max(maxCy, centers[c + 1]);
                maxCz = Math.max(maxCz, centers[c + 2]);
            }
            var count = end - first;
            if (count <= AABB_BVH_LEAF_ITEMS) {
                nodeFirst[node] = first;
                nodeItemCount[node] = count;
                return node;
            }
            var xExtent = maxCx - minCx;
            var yExtent = maxCy - minCy;
            var zExtent = maxCz - minCz;
            var axis = xExtent >= yExtent && xExtent >= zExtent ? 0 : (yExtent >= zExtent ? 1 : 2);
            sortByCenter(first, end - 1, axis, centers);
            var middle = (first + end) >>> 1;
            nodeLeft[node] = build(first, middle, itemBounds, centers);
            nodeRight[node] = build(middle, end, itemBounds, centers);
            return node;
        }

        private void sortByCenter(int low, int high, int axis, float[] centers) {
            SunVisibility.sortByCenter(itemOrder, low, high, axis, centers);
        }
    }

    static @Nullable Occluders build(@Nullable UModel model) {
        if (model == null || model.getNodes() == null || model.getNodes().length == 0) {
            return null;
        }
        var nodes = model.getNodes();
        var points = model.getPoints();
        var verticesPool = model.getVertices();
        var surfaces = model.getSurfaces();
        if (points == null || verticesPool == null) {
            return null;
        }
        var n = 0;
        var totalVerts = 0;
        for (var node : nodes) {
            if (isOccluder(node, surfaces)) {
                n++;
                totalVerts += node.getVertexCount();
            }
        }
        if (n == 0) {
            return null;
        }
        var planes = new float[n * 4];
        var aabb = new float[n * 6];
        var verts = new float[totalVerts * 3];
        var vertOffset = new int[n];
        var vertCount = new int[n];
        var oi = 0;
        var vBase = 0;
        for (var node : nodes) {
            if (!isOccluder(node, surfaces)) {
                continue;
            }
            var pl = node.getPlane();
            planes[oi * 4] = pl.getX();
            planes[oi * 4 + 1] = pl.getY();
            planes[oi * 4 + 2] = pl.getZ();
            planes[oi * 4 + 3] = pl.getW();
            var vc = node.getVertexCount();
            vertOffset[oi] = vBase;
            vertCount[oi] = vc;
            var minX = Float.MAX_VALUE;
            var minY = Float.MAX_VALUE;
            var minZ = Float.MAX_VALUE;
            var maxX = -Float.MAX_VALUE;
            var maxY = -Float.MAX_VALUE;
            var maxZ = -Float.MAX_VALUE;
            for (int i = 0; i < vc; i++) {
                var pt = points[verticesPool[node.getVertexPoolIndex() + i].getVertexIndex()];
                var x = pt.getX();
                var y = pt.getY();
                var z = pt.getZ();
                verts[(vBase + i) * 3] = x;
                verts[(vBase + i) * 3 + 1] = y;
                verts[(vBase + i) * 3 + 2] = z;
                if (x < minX) {
                    minX = x;
                }
                if (y < minY) {
                    minY = y;
                }
                if (z < minZ) {
                    minZ = z;
                }
                if (x > maxX) {
                    maxX = x;
                }
                if (y > maxY) {
                    maxY = y;
                }
                if (z > maxZ) {
                    maxZ = z;
                }
            }
            aabb[oi * 6] = minX;
            aabb[oi * 6 + 1] = minY;
            aabb[oi * 6 + 2] = minZ;
            aabb[oi * 6 + 3] = maxX;
            aabb[oi * 6 + 4] = maxY;
            aabb[oi * 6 + 5] = maxZ;
            vBase += vc;
            oi++;
        }
        return new Occluders(n, planes, aabb, verts, vertOffset, vertCount);
    }

    static boolean doesRayHitAabb(float[] aabb, int base, float ox, float oy, float oz, float dx, float dy, float dz) {
        var tMin = 0f;
        var tMax = Float.MAX_VALUE;
        if (dx > -1e-9f && dx < 1e-9f) {
            if (ox < aabb[base] || ox > aabb[base + 3]) {
                return false;
            }
        } else {
            var inv = 1f / dx;
            var t1 = (aabb[base] - ox) * inv;
            var t2 = (aabb[base + 3] - ox) * inv;
            if (t1 > t2) {
                var tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tMin) {
                tMin = t1;
            }
            if (t2 < tMax) {
                tMax = t2;
            }
            if (tMin > tMax) {
                return false;
            }
        }
        if (dy > -1e-9f && dy < 1e-9f) {
            if (oy < aabb[base + 1] || oy > aabb[base + 4]) {
                return false;
            }
        } else {
            var inv = 1f / dy;
            var t1 = (aabb[base + 1] - oy) * inv;
            var t2 = (aabb[base + 4] - oy) * inv;
            if (t1 > t2) {
                var tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tMin) {
                tMin = t1;
            }
            if (t2 < tMax) {
                tMax = t2;
            }
            if (tMin > tMax) {
                return false;
            }
        }
        if (dz > -1e-9f && dz < 1e-9f) {
            if (oz < aabb[base + 2] || oz > aabb[base + 5]) {
                return false;
            }
        } else {
            var inv = 1f / dz;
            var t1 = (aabb[base + 2] - oz) * inv;
            var t2 = (aabb[base + 5] - oz) * inv;
            if (t1 > t2) {
                var tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tMin) {
                tMin = t1;
            }
            if (t2 < tMax) {
                tMax = t2;
            }
            if (tMin > tMax) {
                return false;
            }
        }
        return tMax >= 0f;
    }

    static boolean isPointInConvexPolygon(
            float[] planes, int planeBase, float[] verts, int vertOffset, int vertCount, float hx, float hy, float hz) {
        var nx = planes[planeBase];
        var ny = planes[planeBase + 1];
        var nz = planes[planeBase + 2];
        var sign = 0f;
        for (int i = 0; i < vertCount; i++) {
            var a = (vertOffset + i) * 3;
            var bIdx = (vertOffset + (i + 1) % vertCount) * 3;
            var ex = verts[bIdx] - verts[a];
            var ey = verts[bIdx + 1] - verts[a + 1];
            var ez = verts[bIdx + 2] - verts[a + 2];
            var wx = hx - verts[a];
            var wy = hy - verts[a + 1];
            var wz = hz - verts[a + 2];
            var cx = ey * wz - ez * wy;
            var cy = ez * wx - ex * wz;
            var cz = ex * wy - ey * wx;
            var d = cx * nx + cy * ny + cz * nz;
            if (d > -EDGE_EPS && d < EDGE_EPS) {
                continue;
            }
            var s = Math.signum(d);
            if (sign == 0f) {
                sign = s;
            } else if (s != sign) {
                return false;
            }
        }
        return true;
    }

    static final class MeshOccluders {
        private static final int BVH_LEAF_TRIANGLES = 8;
        private static final int BVH_MIN_TRIANGLES = 32;
        final int count;
        private final float[] aabb;
        private final float[] invMatrix;
        private final int[] triMeshId;
        private final TriangleMesh[] triMeshes;
        private final int[] actorIndex;
        private final float maxZ;
        private final float gridMinX;
        private final float gridMinY;
        private final int gridW;
        private final int gridH;
        private final int[] cellStart;
        private final int[] cellItems;
        private final int[] visitedEpoch;
        private final int[] triangleTraversal = new int[64];
        private int currentEpoch;

        private MeshOccluders(
                int count,
                float[] aabb,
                float[] invMatrix,
                int[] triMeshId,
                TriangleMesh[] triMeshes,
                int[] actorIndex,
                float maxZ,
                float gridMinX,
                float gridMinY,
                int gridW,
                int gridH,
                int[] cellStart,
                int[] cellItems) {
            this.count = count;
            this.aabb = aabb;
            this.invMatrix = invMatrix;
            this.triMeshId = triMeshId;
            this.triMeshes = triMeshes;
            this.actorIndex = actorIndex;
            this.maxZ = maxZ;
            this.gridMinX = gridMinX;
            this.gridMinY = gridMinY;
            this.gridW = gridW;
            this.gridH = gridH;
            this.cellStart = cellStart;
            this.cellItems = cellItems;
            visitedEpoch = new int[count];
        }

        int getCount() {
            return count;
        }

        boolean blocks(int selfIndex, float ox, float oy, float oz, float dx, float dy, float dz) {
            if (count == 0) {
                return false;
            }
            var upward = dz > 1e-6f;
            if (upward && oz > maxZ) {
                return false;
            }
            float ex;
            float ey;
            if (upward) {
                var tCap = (maxZ - oz) / dz;
                ex = ox + tCap * dx;
                ey = oy + tCap * dy;
            } else {
                ex = ox + dx * 1e9f;
                ey = oy + dy * 1e9f;
            }
            var cx0 = cellX(Math.min(ox, ex));
            var cx1 = cellX(Math.max(ox, ex));
            var cy0 = cellY(Math.min(oy, ey));
            var cy1 = cellY(Math.max(oy, ey));
            var epoch = ++currentEpoch;
            if (epoch == 0) {
                Arrays.fill(visitedEpoch, 0);
                epoch = ++currentEpoch;
            }
            for (int cy = cy0; cy <= cy1; cy++) {
                for (int cx = cx0; cx <= cx1; cx++) {
                    var c = cy * gridW + cx;
                    for (int k = cellStart[c]; k < cellStart[c + 1]; k++) {
                        var oi = cellItems[k];
                        if (visitedEpoch[oi] == epoch) {
                            continue;
                        }
                        visitedEpoch[oi] = epoch;
                        if (actorIndex[oi] == selfIndex) {
                            continue;
                        }
                        var b = oi * 6;
                        if (aabb[b + 2] <= oz) {
                            continue;
                        }
                        if (doesRayHitAabb(aabb, b, ox, oy, oz, dx, dy, dz)
                                && hitsTriangles(oi, ox, oy, oz, dx, dy, dz)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private boolean hitsTriangles(int oi, float ox, float oy, float oz, float dx, float dy, float dz) {
            var m = invMatrix;
            var mb = oi * 16;
            var lox = m[mb] * ox + m[mb + 4] * oy + m[mb + 8] * oz + m[mb + 12];
            var loy = m[mb + 1] * ox + m[mb + 5] * oy + m[mb + 9] * oz + m[mb + 13];
            var loz = m[mb + 2] * ox + m[mb + 6] * oy + m[mb + 10] * oz + m[mb + 14];
            var ldx = m[mb] * dx + m[mb + 4] * dy + m[mb + 8] * dz;
            var ldy = m[mb + 1] * dx + m[mb + 5] * dy + m[mb + 9] * dz;
            var ldz = m[mb + 2] * dx + m[mb + 6] * dy + m[mb + 10] * dz;
            return triMeshes[triMeshId[oi]].hits(lox, loy, loz, ldx, ldy, ldz, triangleTraversal);
        }

        private int cellX(float wx) {
            var c = (int) Math.floor((wx - gridMinX) / MESH_GRID_CELL);
            return c < 0 ? 0 : Math.min(c, gridW - 1);
        }

        private int cellY(float wy) {
            var c = (int) Math.floor((wy - gridMinY) / MESH_GRID_CELL);
            return c < 0 ? 0 : Math.min(c, gridH - 1);
        }

        static final class TriangleMesh {
            private final float[] triangles;
            private int[] triangleOrder;
            private float[] nodeBounds;
            private int[] nodeLeft;
            private int[] nodeRight;
            private int[] nodeFirst;
            private int[] nodeTriangleCount;
            private int nodes;

            TriangleMesh(float[] triangles) {
                if (triangles == null || triangles.length % 9 != 0) {
                    throw new IllegalArgumentException("occluder triangles must be x,y,z triples of three corners");
                }
                this.triangles = triangles;
            }

            boolean hits(float ox, float oy, float oz, float dx, float dy, float dz, int[] traversal) {
                var triangleCount = triangles.length / 9;
                if (triangleCount < BVH_MIN_TRIANGLES) {
                    return hitsLinear(ox, oy, oz, dx, dy, dz);
                }
                if (nodeBounds == null) {
                    buildHierarchy();
                }
                var pending = 0;
                traversal[pending++] = 0;
                while (pending > 0) {
                    var node = traversal[--pending];
                    if (!doesRayHitAabb(nodeBounds, node * 6, ox, oy, oz, dx, dy, dz)) {
                        continue;
                    }
                    var count = nodeTriangleCount[node];
                    if (count == 0) {
                        traversal[pending++] = nodeLeft[node];
                        traversal[pending++] = nodeRight[node];
                        continue;
                    }
                    var end = nodeFirst[node] + count;
                    for (int i = nodeFirst[node]; i < end; i++) {
                        var t = triangleOrder[i] * 9;
                        if (doesRayHitTriangle(
                                ox,
                                oy,
                                oz,
                                dx,
                                dy,
                                dz,
                                triangles[t],
                                triangles[t + 1],
                                triangles[t + 2],
                                triangles[t + 3],
                                triangles[t + 4],
                                triangles[t + 5],
                                triangles[t + 6],
                                triangles[t + 7],
                                triangles[t + 8])) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private void buildHierarchy() {
                var triangleCount = triangles.length / 9;
                triangleOrder = new int[triangleCount];
                var bounds = new float[triangleCount * 6];
                var centers = new float[triangleCount * 3];
                for (int triangle = 0; triangle < triangleCount; triangle++) {
                    triangleOrder[triangle] = triangle;
                    var t = triangle * 9;
                    var b = triangle * 6;
                    var cx = triangle * 3;
                    var minX = Math.min(triangles[t], Math.min(triangles[t + 3], triangles[t + 6]));
                    var minY = Math.min(triangles[t + 1], Math.min(triangles[t + 4], triangles[t + 7]));
                    var minZ = Math.min(triangles[t + 2], Math.min(triangles[t + 5], triangles[t + 8]));
                    var maxX = Math.max(triangles[t], Math.max(triangles[t + 3], triangles[t + 6]));
                    var maxY = Math.max(triangles[t + 1], Math.max(triangles[t + 4], triangles[t + 7]));
                    var maxZ = Math.max(triangles[t + 2], Math.max(triangles[t + 5], triangles[t + 8]));
                    bounds[b] = minX;
                    bounds[b + 1] = minY;
                    bounds[b + 2] = minZ;
                    bounds[b + 3] = maxX;
                    bounds[b + 4] = maxY;
                    bounds[b + 5] = maxZ;
                    centers[cx] = (minX + maxX) * 0.5f;
                    centers[cx + 1] = (minY + maxY) * 0.5f;
                    centers[cx + 2] = (minZ + maxZ) * 0.5f;
                }
                var capacity = Math.max(1, triangleCount * 2);
                nodeBounds = new float[capacity * 6];
                nodeLeft = new int[capacity];
                nodeRight = new int[capacity];
                nodeFirst = new int[capacity];
                nodeTriangleCount = new int[capacity];
                if (triangleCount > 0) {
                    build(0, triangleCount, bounds, centers);
                }
            }

            private boolean hitsLinear(float ox, float oy, float oz, float dx, float dy, float dz) {
                for (int t = 0; t < triangles.length; t += 9) {
                    if (doesRayHitTriangle(
                            ox,
                            oy,
                            oz,
                            dx,
                            dy,
                            dz,
                            triangles[t],
                            triangles[t + 1],
                            triangles[t + 2],
                            triangles[t + 3],
                            triangles[t + 4],
                            triangles[t + 5],
                            triangles[t + 6],
                            triangles[t + 7],
                            triangles[t + 8])) {
                        return true;
                    }
                }
                return false;
            }

            private int build(int first, int end, float[] triangleBounds, float[] centers) {
                var node = nodes++;
                var nb = node * 6;
                nodeBounds[nb] = Float.MAX_VALUE;
                nodeBounds[nb + 1] = Float.MAX_VALUE;
                nodeBounds[nb + 2] = Float.MAX_VALUE;
                nodeBounds[nb + 3] = -Float.MAX_VALUE;
                nodeBounds[nb + 4] = -Float.MAX_VALUE;
                nodeBounds[nb + 5] = -Float.MAX_VALUE;
                var minCx = Float.MAX_VALUE;
                var minCy = Float.MAX_VALUE;
                var minCz = Float.MAX_VALUE;
                var maxCx = -Float.MAX_VALUE;
                var maxCy = -Float.MAX_VALUE;
                var maxCz = -Float.MAX_VALUE;
                for (int i = first; i < end; i++) {
                    var triangle = triangleOrder[i];
                    var b = triangle * 6;
                    nodeBounds[nb] = Math.min(nodeBounds[nb], triangleBounds[b]);
                    nodeBounds[nb + 1] = Math.min(nodeBounds[nb + 1], triangleBounds[b + 1]);
                    nodeBounds[nb + 2] = Math.min(nodeBounds[nb + 2], triangleBounds[b + 2]);
                    nodeBounds[nb + 3] = Math.max(nodeBounds[nb + 3], triangleBounds[b + 3]);
                    nodeBounds[nb + 4] = Math.max(nodeBounds[nb + 4], triangleBounds[b + 4]);
                    nodeBounds[nb + 5] = Math.max(nodeBounds[nb + 5], triangleBounds[b + 5]);
                    var c = triangle * 3;
                    minCx = Math.min(minCx, centers[c]);
                    minCy = Math.min(minCy, centers[c + 1]);
                    minCz = Math.min(minCz, centers[c + 2]);
                    maxCx = Math.max(maxCx, centers[c]);
                    maxCy = Math.max(maxCy, centers[c + 1]);
                    maxCz = Math.max(maxCz, centers[c + 2]);
                }
                var count = end - first;
                if (count <= BVH_LEAF_TRIANGLES) {
                    nodeFirst[node] = first;
                    nodeTriangleCount[node] = count;
                    return node;
                }
                var xExtent = maxCx - minCx;
                var yExtent = maxCy - minCy;
                var zExtent = maxCz - minCz;
                var axis = xExtent >= yExtent && xExtent >= zExtent ? 0 : (yExtent >= zExtent ? 1 : 2);
                sortByCenter(triangleOrder, first, end - 1, axis, centers);
                var middle = (first + end) >>> 1;
                nodeLeft[node] = build(first, middle, triangleBounds, centers);
                nodeRight[node] = build(middle, end, triangleBounds, centers);
                return node;
            }
        }
    }

    static @Nullable MeshOccluders buildMeshOccluders(
            float[] aabb,
            float[] invMatrix,
            int[] triMeshId,
            MeshOccluders.TriangleMesh[] triMeshes,
            int[] actorIndex,
            int count) {
        if (count == 0) {
            return null;
        }
        var minX = Float.MAX_VALUE;
        var minY = Float.MAX_VALUE;
        var maxX = -Float.MAX_VALUE;
        var maxY = -Float.MAX_VALUE;
        var maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            var b = i * 6;
            if (aabb[b] < minX) {
                minX = aabb[b];
            }
            if (aabb[b + 1] < minY) {
                minY = aabb[b + 1];
            }
            if (aabb[b + 3] > maxX) {
                maxX = aabb[b + 3];
            }
            if (aabb[b + 4] > maxY) {
                maxY = aabb[b + 4];
            }
            if (aabb[b + 5] > maxZ) {
                maxZ = aabb[b + 5];
            }
        }
        var gridW = Math.max(1, (int) Math.floor((maxX - minX) / MESH_GRID_CELL) + 1);
        var gridH = Math.max(1, (int) Math.floor((maxY - minY) / MESH_GRID_CELL) + 1);
        var cellCount = gridW * gridH;
        var cellStart = new int[cellCount + 1];
        for (int i = 0; i < count; i++) {
            forEachCell(aabb, i, minX, minY, gridW, gridH, (cx, cy) -> cellStart[cy * gridW + cx + 1]++);
        }
        for (int c = 0; c < cellCount; c++) {
            cellStart[c + 1] += cellStart[c];
        }
        var cellItems = new int[cellStart[cellCount]];
        var cursor = new int[cellCount];
        for (int i = 0; i < count; i++) {
            var oi = i;
            forEachCell(aabb, i, minX, minY, gridW, gridH, (cx, cy) -> {
                var c = cy * gridW + cx;
                cellItems[cellStart[c] + cursor[c]++] = oi;
            });
        }
        return new MeshOccluders(
                count,
                aabb,
                invMatrix,
                triMeshId,
                triMeshes,
                actorIndex,
                maxZ,
                minX,
                minY,
                gridW,
                gridH,
                cellStart,
                cellItems);
    }

    private interface CellVisitor {
        void visit(int cx, int cy);
    }

    static float getMeshOccluderMinExtent() {
        return MESH_OCCLUDER_MIN_EXTENT;
    }

    static boolean doesRayHitTriangle(
            float ox,
            float oy,
            float oz,
            float dx,
            float dy,
            float dz,
            float ax,
            float ay,
            float az,
            float bx,
            float by,
            float bz,
            float cx,
            float cy,
            float cz) {
        var e1x = bx - ax;
        var e1y = by - ay;
        var e1z = bz - az;
        var e2x = cx - ax;
        var e2y = cy - ay;
        var e2z = cz - az;
        var px = dy * e2z - dz * e2y;
        var py = dz * e2x - dx * e2z;
        var pz = dx * e2y - dy * e2x;
        var det = e1x * px + e1y * py + e1z * pz;
        if (det > -1e-8f && det < 1e-8f) {
            return false;
        }
        var inv = 1f / det;
        var tx = ox - ax;
        var ty = oy - ay;
        var tz = oz - az;
        var u = (tx * px + ty * py + tz * pz) * inv;
        if (u < 0f || u > 1f) {
            return false;
        }
        var qx = ty * e1z - tz * e1y;
        var qy = tz * e1x - tx * e1z;
        var qz = tx * e1y - ty * e1x;
        var v = (dx * qx + dy * qy + dz * qz) * inv;
        if (v < 0f || u + v > 1f) {
            return false;
        }
        var tt = (e2x * qx + e2y * qy + e2z * qz) * inv;
        return tt > 1e-4f;
    }

    static final class TerrainField {
        private final byte[] heights;
        private final int width;
        private final float px;
        private final float py;
        private final float pz;
        private final float sx;
        private final float sy;
        private final float sz;

        TerrainField(byte[] heights, int width, float px, float py, float pz, float sx, float sy, float sz) {
            this.heights = heights;
            this.width = width;
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
        }

        float getSurfaceZ(float wx, float wy) {
            var gx = (wx - px) / sx;
            var gy = (wy - py) / sy;
            if (gx < 0f || gy < 0f || gx > width - 1 || gy > width - 1) {
                return Float.NaN;
            }
            var x0 = (int) Math.floor(gx);
            var y0 = (int) Math.floor(gy);
            var x1 = Math.min(x0 + 1, width - 1);
            var y1 = Math.min(y0 + 1, width - 1);
            var fx = gx - x0;
            var fy = gy - y0;
            var h00 = height(x0, y0);
            var h10 = height(x1, y0);
            var h01 = height(x0, y1);
            var h11 = height(x1, y1);
            var top = h00 * (1f - fx) + h10 * fx;
            var bot = h01 * (1f - fx) + h11 * fx;
            return (top * (1f - fy) + bot * fy) * sz + pz;
        }

        boolean occludes(float wx, float wy, float wz) {
            var s = getSurfaceZ(wx, wy);
            return !Float.isNaN(s) && wz < s - UNDER_TERRAIN_MARGIN;
        }

        private float height(int x, int y) {
            var i = (y * width + x) * 2;
            return (heights[i] & 0xFF) | ((heights[i + 1] & 0xFF) << 8);
        }
    }

    private static float[] buildSkyDirs() {
        var elevationsDeg = new float[] {30f, 60f};
        var azimuths = 8;
        var dirs = new float[(1 + elevationsDeg.length * azimuths) * 3];
        dirs[0] = 0f;
        dirs[1] = 0f;
        dirs[2] = 1f;
        var k = 3;
        for (var elevDeg : elevationsDeg) {
            var elev = Math.toRadians(elevDeg);
            var dz = (float) Math.sin(elev);
            var horiz = (float) Math.cos(elev);
            for (int a = 0; a < azimuths; a++) {
                var az = 2.0 * Math.PI * a / azimuths;
                dirs[k++] = (float) (horiz * Math.cos(az));
                dirs[k++] = (float) (horiz * Math.sin(az));
                dirs[k++] = dz;
            }
        }
        return dirs;
    }

    private static void sortByCenter(int[] order, int low, int high, int axis, float[] centers) {
        var i = low;
        var j = high;
        var pivot = centers[order[(low + high) >>> 1] * 3 + axis];
        while (i <= j) {
            while (centers[order[i] * 3 + axis] < pivot) {
                i++;
            }
            while (centers[order[j] * 3 + axis] > pivot) {
                j--;
            }
            if (i <= j) {
                var swap = order[i];
                order[i++] = order[j];
                order[j--] = swap;
            }
        }
        if (low < j) {
            sortByCenter(order, low, j, axis, centers);
        }
        if (i < high) {
            sortByCenter(order, i, high, axis, centers);
        }
    }

    private static boolean isOccluder(UBspNode node, UBspSurface[] surfaces) {
        if (node.getVertexCount() < 3) {
            return false;
        }
        if ((node.getFlags() & (BspNodeFlags.NF_NotCsg | BspNodeFlags.NF_NotVisBlocking)) != 0) {
            return false;
        }
        var si = node.getSurfaceIndex();
        if (surfaces == null || si < 0 || si >= surfaces.length) {
            return true;
        }
        var skip = BspPolygonFlags.PF_Invisible
                | BspPolygonFlags.PF_Portal
                | BspPolygonFlags.PF_FakeBackdrop
                | BspPolygonFlags.PF_NotSolid
                | BspPolygonFlags.PF_Translucent
                | BspPolygonFlags.PF_Masked
                | BspPolygonFlags.PF_Modulated;
        return (surfaces[si].getPolygonFlags() & skip) == 0;
    }

    private static void forEachCell(
            float[] aabb, int i, float gridMinX, float gridMinY, int gridW, int gridH, CellVisitor v) {
        var b = i * 6;
        var cx0 = clampCell((int) Math.floor((aabb[b] - gridMinX) / MESH_GRID_CELL), gridW);
        var cx1 = clampCell((int) Math.floor((aabb[b + 3] - gridMinX) / MESH_GRID_CELL), gridW);
        var cy0 = clampCell((int) Math.floor((aabb[b + 1] - gridMinY) / MESH_GRID_CELL), gridH);
        var cy1 = clampCell((int) Math.floor((aabb[b + 4] - gridMinY) / MESH_GRID_CELL), gridH);
        for (int cy = cy0; cy <= cy1; cy++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                v.visit(cx, cy);
            }
        }
    }

    private static int clampCell(int c, int size) {
        return c < 0 ? 0 : Math.min(c, size - 1);
    }
}
