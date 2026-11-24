package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class ActorLightingTrace {
    public static final int TERRAIN = -2;

    public static final float SUN_RAY_LENGTH = 262144f * 1.375f;

    private static final float PLANE_EPSILON = 0.001f;
    private static final int NOT_CSG_OR_NEW = 0x21;
    private static final int FAKE_BACKDROP = 0x80;
    private static final float ZERO_DELTA = 1e-7f;
    private static final int LEAF_SIZE = 4;

    private final Bsp bsp;
    private final Caster[] casters;
    private final List<NativeTerrainTrace> terrains;
    private final float[] treeBoxes;
    private final int[] treeLinks;
    private final int[] treeItems;

    public ActorLightingTrace(Bsp bsp, List<Caster> casters) {
        this(bsp, casters, List.of());
    }

    public ActorLightingTrace(Bsp bsp, List<Caster> casters, List<NativeTerrainTrace> terrains) {
        this.bsp = Objects.requireNonNull(bsp);
        this.casters = casters.toArray(Caster[]::new);
        this.terrains = List.copyOf(terrains);
        var boxes = new float[this.casters.length * 6];
        for (var i = 0; i < this.casters.length; i++) {
            System.arraycopy(this.casters[i].bounds(), 0, boxes, i * 6, 6);
        }
        var tree = new BoxTree(boxes);
        treeBoxes = tree.getBoxes();
        treeLinks = tree.getLinks();
        treeItems = tree.getItems();
    }

    private ActorLightingTrace(ActorLightingTrace shared, List<NativeTerrainTrace> terrains) {
        this.bsp = shared.bsp;
        this.casters = shared.casters;
        this.terrains = List.copyOf(terrains);
        this.treeBoxes = shared.treeBoxes;
        this.treeLinks = shared.treeLinks;
        this.treeItems = shared.treeItems;
    }

    public record Bsp(float[] planes, int[] nodes, int[] surfaceFlags, boolean rootOutside) {
        public static final Bsp EMPTY = new Bsp(new float[0], new int[0], new int[0], true);

        public Bsp {
            if (planes.length / 4 != nodes.length / 5 || planes.length % 4 != 0 || nodes.length % 5 != 0) {
                throw new IllegalArgumentException("BSP arrays differ");
            }
            for (var node = 0; node < nodes.length / 5; node++) {
                var surface = nodes[node * 5 + 4];
                if (surface < 0 || surface >= surfaceFlags.length) {
                    throw new IllegalArgumentException("BSP node surface out of range");
                }
                for (var child = 2; child <= 3; child++) {
                    var index = nodes[node * 5 + child];
                    if (index < -1 || index >= nodes.length / 5) {
                        throw new IllegalArgumentException("BSP child out of range");
                    }
                }
            }
        }

        int nodeCount() {
            return nodes.length / 5;
        }
    }

    public static final class Triangles {
        private final float[] vertices;
        private final float[] treeBoxes;
        private final int[] treeLinks;
        private final int[] treeItems;

        public Triangles(float[] vertices) {
            if (vertices.length % 9 != 0) {
                throw new IllegalArgumentException("Triangle vertices must come in groups of nine floats");
            }
            this.vertices = vertices.clone();
            var count = vertices.length / 9;
            var boxes = new float[count * 6];
            for (var i = 0; i < count; i++) {
                for (var axis = 0; axis < 3; axis++) {
                    var a = vertices[i * 9 + axis];
                    var b = vertices[i * 9 + 3 + axis];
                    var c = vertices[i * 9 + 6 + axis];
                    boxes[i * 6 + axis] = Math.min(a, Math.min(b, c));
                    boxes[i * 6 + 3 + axis] = Math.max(a, Math.max(b, c));
                }
            }
            var tree = new BoxTree(boxes);
            treeBoxes = tree.getBoxes();
            treeLinks = tree.getLinks();
            treeItems = tree.getItems();
        }

        public int getCount() {
            return vertices.length / 9;
        }
    }

    public record Caster(
            String path,
            float[] planes,
            int[] nodes,
            float[] boxes,
            float[] worldToLocal,
            float[] bounds,
            @Nullable Triangles sceneTriangles,
            @Nullable Triangles sunTriangles) {
        public Caster(String path, float[] planes, int[] nodes, float[] boxes, float[] worldToLocal, float[] bounds) {
            this(path, planes, nodes, boxes, worldToLocal, bounds, null, null);
        }

        public Caster {
            Objects.requireNonNull(path);
            if (planes.length % 16 != 0
                    || nodes.length % 4 != 0
                    || boxes.length / 6 != nodes.length / 4
                    || worldToLocal.length != 16
                    || bounds.length != 6) {
                throw new IllegalArgumentException("Invalid shadow caster " + path);
            }
            var faces = planes.length / 16;
            var count = nodes.length / 4;
            for (var node = 0; node < count; node++) {
                if (nodes[node * 4] < 0 || nodes[node * 4] >= faces) {
                    throw new IllegalArgumentException("Collision node face out of range in " + path);
                }
                for (var child = 1; child < 4; child++) {
                    var index = nodes[node * 4 + child];
                    if (index < -1 || index >= count) {
                        throw new IllegalArgumentException("Collision node child out of range in " + path);
                    }
                }
            }
        }
    }

    static boolean doesSegmentTouchBox(
            float[] box, int offset, float sx, float sy, float sz, float ex, float ey, float ez) {
        var low = 0.0;
        var high = 1.0;
        for (var axis = 0; axis < 3; axis++) {
            double s = axis == 0 ? sx : axis == 1 ? sy : sz;
            double e = axis == 0 ? ex : axis == 1 ? ey : ez;
            var min = box[offset + axis] - 1.0;
            var max = box[offset + 3 + axis] + 1.0;
            var d = e - s;
            if (Math.abs(d) < 1e-12) {
                if (s < min || s > max) {
                    return false;
                }
                continue;
            }
            var t0 = (min - s) / d;
            var t1 = (max - s) / d;
            if (t0 > t1) {
                var swap = t0;
                t0 = t1;
                t1 = swap;
            }
            low = Math.max(low, t0);
            high = Math.min(high, t1);
            if (low > high) {
                return false;
            }
        }
        return true;
    }

    private static final class BoxTree {
        private final float[] items;
        private final int[] order;
        private float[] boxes = new float[64 * 6];
        private int[] links = new int[64 * 2];
        private int count;

        BoxTree(float[] itemBoxes) {
            items = itemBoxes;
            order = new int[itemBoxes.length / 6];
            for (var i = 0; i < order.length; i++) {
                order[i] = i;
            }
            if (order.length > 0) {
                build(allocate(), 0, order.length);
            }
        }

        float[] getBoxes() {
            return Arrays.copyOf(boxes, count * 6);
        }

        int[] getLinks() {
            return Arrays.copyOf(links, count * 2);
        }

        int[] getItems() {
            return order.clone();
        }

        private int allocate() {
            if (count * 6 + 6 > boxes.length) {
                boxes = Arrays.copyOf(boxes, boxes.length * 2);
                links = Arrays.copyOf(links, links.length * 2);
            }
            return count++;
        }

        private void build(int node, int from, int to) {
            var min = new float[] {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            var max = new float[] {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (var i = from; i < to; i++) {
                var o = order[i] * 6;
                for (var axis = 0; axis < 3; axis++) {
                    min[axis] = Math.min(min[axis], items[o + axis]);
                    max[axis] = Math.max(max[axis], items[o + 3 + axis]);
                }
            }
            for (var axis = 0; axis < 3; axis++) {
                boxes[node * 6 + axis] = min[axis];
                boxes[node * 6 + 3 + axis] = max[axis];
            }
            if (to - from <= LEAF_SIZE) {
                links[node * 2] = -1 - from;
                links[node * 2 + 1] = to - from;
                return;
            }
            var axis = 0;
            for (var a = 1; a < 3; a++) {
                if (max[a] - min[a] > max[axis] - min[axis]) {
                    axis = a;
                }
            }
            var split = axis;
            var sorted = Arrays.stream(Arrays.copyOfRange(order, from, to))
                    .boxed()
                    .sorted((a, b) -> Float.compare(
                            items[a * 6 + split] + items[a * 6 + 3 + split],
                            items[b * 6 + split] + items[b * 6 + 3 + split]))
                    .mapToInt(Integer::intValue)
                    .toArray();
            System.arraycopy(sorted, 0, order, from, sorted.length);
            var middle = (from + to) >>> 1;
            var left = allocate();
            allocate();
            links[node * 2] = left;
            links[node * 2 + 1] = 0;
            build(left, from, middle);
            build(left + 1, middle, to);
        }
    }

    public ActorLightingTrace withTerrains(List<NativeTerrainTrace> replacement) {
        return new ActorLightingTrace(this, replacement);
    }

    public ActorLightingTrace withCasters(Set<String> removed, List<Caster> added) {
        var kept = new ArrayList<Caster>();
        for (var caster : casters) {
            if (!removed.contains(caster.path())) {
                kept.add(caster);
            }
        }
        kept.addAll(added);
        return new ActorLightingTrace(bsp, kept, terrains);
    }

    public List<float[]> getCasterBounds(Set<String> paths) {
        var result = new ArrayList<float[]>();
        for (var caster : casters) {
            if (paths.contains(caster.path())) {
                result.add(caster.bounds().clone());
            }
        }
        return result;
    }

    public List<NativeTerrainTrace> getTerrains() {
        return terrains;
    }

    public int getCasterCount() {
        return casters.length;
    }

    public Caster getCaster(int index) {
        return casters[index];
    }

    public Query query() {
        return new Query();
    }

    public final class Query {
        private static final int BSP_CLEAR = 0;
        private static final int BSP_HIT = 1;
        private static final int BSP_EMPTY_SOLID = 2;
        private int[] bspNodes = new int[64];
        private int[] bspCrossings = new int[64];
        private float[] bspPoints = new float[64 * 6];
        private boolean[] bspOutside = new boolean[64];
        private int[] meshNodes = new int[64];
        private float[] meshFractions = new float[64];
        private boolean[] meshFaces = new boolean[64];
        private int[] treeStack = new int[64];
        private int[] triangleStack = new int[64];
        private long traces;

        private float hitFraction;
        private float hitX, hitY, hitZ;
        private int hitSurfaceFlags;

        public int hitCaster;

        public float hitLocationX, hitLocationY, hitLocationZ;

        private Query() {}

        public long getTraces() {
            return traces;
        }

        public boolean clear(float sx, float sy, float sz, float ex, float ey, float ez, boolean sun) {
            return trace(sx, sy, sz, ex, ey, ez, false, sun);
        }

        public boolean findFirstHit(float sx, float sy, float sz, float ex, float ey, float ez, boolean sun) {
            return !trace(sx, sy, sz, ex, ey, ez, true, sun);
        }

        private boolean trace(
                float sx, float sy, float sz, float ex, float ey, float ez, boolean nearest, boolean sun) {
            traces++;
            var dx = ex - sx;
            var dy = ey - sy;
            var dz = ez - sz;
            if (dx == 0 && dy == 0 && dz == 0) {
                return true;
            }
            var endX = ex;
            var endY = ey;
            var endZ = ez;
            var alpha = 1f;
            var bspBlocked = false;
            var bspFraction = 0f;
            var bspX = 0f;
            var bspY = 0f;
            var bspZ = 0f;
            var bspResult = bspLineCheck(sx, sy, sz, ex, ey, ez);
            if (bspResult != BSP_CLEAR) {
                if (bspResult == BSP_EMPTY_SOLID) {
                    hitCaster = -1;
                    hitLocationX = sx;
                    hitLocationY = sy;
                    hitLocationZ = sz;
                    return false;
                }
                var backdrop = (hitSurfaceFlags & FAKE_BACKDROP) != 0;
                if (!backdrop && !nearest) {
                    return false;
                }
                bspBlocked = !backdrop;
                bspFraction = hitFraction;
                bspX = hitX;
                bspY = hitY;
                bspZ = hitZ;
                var hx = hitX - sx;
                var hy = hitY - sy;
                var hz = hitZ - sz;
                var distance = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
                alpha = ((distance + 5f) * hitFraction) / (distance + .0001f);
                if (alpha >= 1f) {
                    alpha = 1f;
                }
                endX = sx + (ex - sx) * alpha;
                endY = sy + (ey - sy) * alpha;
                endZ = sz + (ez - sz) * alpha;
            }
            var bestCaster = -1;
            var bestTime = Float.MAX_VALUE;
            var bestX = 0f;
            var bestY = 0f;
            var bestZ = 0f;
            if (treeLinks.length > 0) {
                var top = 0;
                treeStack[0] = 0;
                while (top >= 0) {
                    var node = treeStack[top--];
                    if (!doesSegmentTouchBox(treeBoxes, node * 6, sx, sy, sz, endX, endY, endZ)) {
                        continue;
                    }
                    var link = treeLinks[node * 2];
                    if (link >= 0) {
                        if (top + 3 >= treeStack.length) {
                            treeStack = Arrays.copyOf(treeStack, treeStack.length * 2);
                        }
                        treeStack[++top] = link;
                        treeStack[++top] = link + 1;
                        continue;
                    }
                    var first = -1 - link;
                    var count = treeLinks[node * 2 + 1];
                    for (var i = first; i < first + count; i++) {
                        var index = treeItems[i];
                        var caster = casters[index];
                        if (!doesSegmentTouchBox(caster.bounds(), 0, sx, sy, sz, endX, endY, endZ)) {
                            continue;
                        }
                        var triangles =
                                sun && caster.sunTriangles() != null ? caster.sunTriangles() : caster.sceneTriangles();
                        if (triangles != null
                                ? !findTriangleFirstHit(caster, triangles, sx, sy, sz, endX, endY, endZ)
                                : !findMeshFirstHit(caster, sx, sy, sz, endX, endY, endZ)) {
                            continue;
                        }
                        if (!nearest) {
                            return false;
                        }
                        if (hitFraction < bestTime) {
                            bestTime = hitFraction;
                            bestCaster = index;
                            bestX = hitX;
                            bestY = hitY;
                            bestZ = hitZ;
                        }
                    }
                }
            }
            for (var terrain : terrains) {
                var hit = terrain.findFirstHit(new Vector(sx, sy, sz), new Vector(endX, endY, endZ));
                if (hit.isEmpty()) {
                    continue;
                }
                if (!nearest) {
                    return false;
                }
                if (hit.get().fraction() < bestTime) {
                    bestTime = hit.get().fraction();
                    bestCaster = TERRAIN;
                    bestX = hit.get().position().x();
                    bestY = hit.get().position().y();
                    bestZ = hit.get().position().z();
                }
            }
            if (bestCaster != -1 && (!bspBlocked || bestTime * alpha < bspFraction)) {
                hitCaster = bestCaster;
                hitLocationX = bestX;
                hitLocationY = bestY;
                hitLocationZ = bestZ;
                return false;
            }
            if (bspBlocked) {
                hitCaster = -1;
                hitLocationX = bspX;
                hitLocationY = bspY;
                hitLocationZ = bspZ;
                return false;
            }
            return true;
        }

        private int bspLineCheck(float sx, float sy, float sz, float ex, float ey, float ez) {
            var planes = bsp.planes;
            var nodes = bsp.nodes;
            if (nodes.length == 0) {
                return bsp.rootOutside ? BSP_CLEAR : BSP_EMPTY_SOLID;
            }
            var deltaX = ex - sx;
            var deltaY = ey - sy;
            var deltaZ = ez - sz;
            var lengthSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
            var pending = 0;
            var nodeIndex = 0;
            var lastCrossing = 0;
            var ax = sx;
            var ay = sy;
            var az = sz;
            var bx = ex;
            var by = ey;
            var bz = ez;
            var outside = bsp.rootOutside;
            while (true) {
                while (nodeIndex != -1) {
                    var p = nodeIndex * 4;
                    var n = nodeIndex * 5;
                    var startDistance = (ay * planes[p + 1] + ax * planes[p] + az * planes[p + 2]) - planes[p + 3];
                    var endDistance = (by * planes[p + 1] + bx * planes[p] + bz * planes[p + 2]) - planes[p + 3];
                    var csg = nodes[n + 1] != 0 && (nodes[n] & NOT_CSG_OR_NEW) == 0;
                    if (startDistance > -PLANE_EPSILON && endDistance > -PLANE_EPSILON) {
                        outside |= csg;
                        nodeIndex = nodes[n + 2];
                    } else if (startDistance < PLANE_EPSILON && endDistance < PLANE_EPSILON) {
                        outside &= !csg;
                        nodeIndex = nodes[n + 3];
                    } else {
                        var scale = startDistance / (endDistance - startDistance);
                        var mx = ax + (ax - bx) * scale;
                        var my = ay + (ay - by) * scale;
                        var mz = az + (az - bz) * scale;
                        var fromFront = startDistance > 0;
                        var farOutside = fromFront ? outside && !csg : outside || csg;
                        if (pending == bspNodes.length) {
                            growBsp();
                        }
                        bspNodes[pending] = fromFront ? nodes[n + 3] : nodes[n + 2];
                        bspCrossings[pending] = nodeIndex;
                        var q = pending * 6;
                        bspPoints[q] = mx;
                        bspPoints[q + 1] = my;
                        bspPoints[q + 2] = mz;
                        bspPoints[q + 3] = bx;
                        bspPoints[q + 4] = by;
                        bspPoints[q + 5] = bz;
                        bspOutside[pending] = farOutside;
                        pending++;
                        outside = fromFront ? outside || csg : outside && !csg;
                        nodeIndex = fromFront ? nodes[n + 2] : nodes[n + 3];
                        bx = mx;
                        by = my;
                        bz = mz;
                    }
                }
                if (!outside) {
                    var raw = ((ax - sx) * deltaX + (ay - sy) * deltaY + (az - sz) * deltaZ) / lengthSquared;
                    var fraction = Math.max(0f, Math.min(1f, raw - 0.5f / (float) Math.sqrt(lengthSquared)));
                    hitFraction = fraction;
                    hitX = sx + deltaX * fraction;
                    hitY = sy + deltaY * fraction;
                    hitZ = sz + deltaZ * fraction;
                    hitSurfaceFlags = bsp.surfaceFlags[nodes[lastCrossing * 5 + 4]];
                    return BSP_HIT;
                }
                if (pending == 0) {
                    return BSP_CLEAR;
                }
                pending--;
                nodeIndex = bspNodes[pending];
                lastCrossing = bspCrossings[pending];
                var q = pending * 6;
                ax = bspPoints[q];
                ay = bspPoints[q + 1];
                az = bspPoints[q + 2];
                bx = bspPoints[q + 3];
                by = bspPoints[q + 4];
                bz = bspPoints[q + 5];
                outside = bspOutside[pending];
            }
        }

        private void growBsp() {
            var size = bspNodes.length * 2;
            bspNodes = Arrays.copyOf(bspNodes, size);
            bspCrossings = Arrays.copyOf(bspCrossings, size);
            bspPoints = Arrays.copyOf(bspPoints, size * 6);
            bspOutside = Arrays.copyOf(bspOutside, size);
        }

        private boolean findMeshFirstHit(Caster caster, float sx, float sy, float sz, float ex, float ey, float ez) {
            var nodes = caster.nodes();
            if (nodes.length == 0) {
                return false;
            }
            var worldDeltaX = ex - sx;
            var worldDeltaY = ey - sy;
            var worldDeltaZ = ez - sz;
            var lengthSquared = worldDeltaX * worldDeltaX + worldDeltaY * worldDeltaY + worldDeltaZ * worldDeltaZ;
            if (lengthSquared == 0) {
                return false;
            }
            var m = caster.worldToLocal();
            var lsx = m[4] * sy + m[0] * sx + m[8] * sz + m[12];
            var lsy = m[1] * sx + m[5] * sy + m[9] * sz + m[13];
            var lsz = m[2] * sx + m[6] * sy + m[10] * sz + m[14];
            var lex = m[4] * ey + m[0] * ex + m[8] * ez + m[12];
            var ley = m[1] * ex + m[5] * ey + m[9] * ez + m[13];
            var lez = m[2] * ex + m[6] * ey + m[10] * ez + m[14];
            var dx = nonzero(lex - lsx);
            var dy = nonzero(ley - lsy);
            var dz = nonzero(lez - lsz);
            var ix = 1f / dx;
            var iy = 1f / dy;
            var iz = 1f / dz;
            var planes = caster.planes();
            var boxes = caster.boxes();
            var top = 0;
            meshNodes[0] = 0;
            meshFaces[0] = false;
            meshFractions[0] = 0;
            var budget = 2L * (nodes.length / 4);
            while (top >= 0) {
                var node = meshNodes[top];
                var testFace = meshFaces[top];
                var fraction = meshFractions[top];
                top--;
                if (node == -1) {
                    continue;
                }
                if (--budget < 0) {
                    throw new IllegalStateException("Collision tree traversal exceeds its budget in " + caster.path());
                }
                var face = nodes[node * 4];
                var f = face * 16;
                if (testFace) {
                    var px = lsx + dx * fraction;
                    var py = lsy + dy * fraction;
                    var pz = lsz + dz * fraction;
                    if (planeDistance(planes, f + 4, px, py, pz) < 0
                            && planeDistance(planes, f + 8, px, py, pz) < 0
                            && planeDistance(planes, f + 12, px, py, pz) < 0) {
                        var length = (float) Math.sqrt(lengthSquared);
                        var retreat = 0.1f / length;
                        if (retreat <= 0.1f) {
                            retreat = 1f / length;
                            if (retreat > 0.1f) {
                                retreat = 0.1f;
                            }
                        }
                        var time = Math.max(0f, Math.min(1f, fraction - retreat));
                        hitFraction = time;
                        hitX = sx + worldDeltaX * time;
                        hitY = sy + worldDeltaY * time;
                        hitZ = sz + worldDeltaZ * time;
                        return true;
                    }
                    continue;
                }
                if (!doesRayTouchNodeBox(boxes, node * 6, lsx, lsy, lsz, dx, dy, dz, ix, iy, iz)) {
                    continue;
                }
                var startDistance = planeDistance(planes, f, lsx, lsy, lsz);
                var endDistance = planeDistance(planes, f, lex, ley, lez);
                if (top + 5 >= meshNodes.length) {
                    growMesh();
                }
                if (startDistance > -PLANE_EPSILON && endDistance > -PLANE_EPSILON) {
                    push(++top, nodes[node * 4 + 3], false, 0);
                } else if (startDistance < PLANE_EPSILON && endDistance < PLANE_EPSILON) {
                    push(++top, nodes[node * 4 + 2], false, 0);
                } else {
                    var crossing = -(startDistance / (endDistance - startDistance));
                    var fromFront = startDistance > -PLANE_EPSILON;
                    push(++top, fromFront ? nodes[node * 4 + 2] : nodes[node * 4 + 3], false, 0);
                    push(++top, node, true, crossing);
                    push(++top, nodes[node * 4 + 1], false, 0);
                    push(++top, fromFront ? nodes[node * 4 + 3] : nodes[node * 4 + 2], false, 0);
                }
            }
            return false;
        }

        private boolean findTriangleFirstHit(
                Caster caster, Triangles set, float sx, float sy, float sz, float ex, float ey, float ez) {
            if (set.treeLinks.length == 0) {
                return false;
            }
            var m = caster.worldToLocal();
            var ax = m[4] * sy + m[0] * sx + m[8] * sz + m[12];
            var ay = m[1] * sx + m[5] * sy + m[9] * sz + m[13];
            var az = m[2] * sx + m[6] * sy + m[10] * sz + m[14];
            var bx = m[4] * ey + m[0] * ex + m[8] * ez + m[12];
            var by = m[1] * ex + m[5] * ey + m[9] * ez + m[13];
            var bz = m[2] * ex + m[6] * ey + m[10] * ez + m[14];
            var dx = bx - ax;
            var dy = by - ay;
            var dz = bz - az;
            var t = set.vertices;
            var best = Float.MAX_VALUE;
            var top = 0;
            triangleStack[0] = 0;
            while (top >= 0) {
                var node = triangleStack[top--];
                if (!doesSegmentTouchBox(set.treeBoxes, node * 6, ax, ay, az, bx, by, bz)) {
                    continue;
                }
                var link = set.treeLinks[node * 2];
                if (link >= 0) {
                    if (top + 3 >= triangleStack.length) {
                        triangleStack = Arrays.copyOf(triangleStack, triangleStack.length * 2);
                    }
                    triangleStack[++top] = link;
                    triangleStack[++top] = link + 1;
                    continue;
                }
                var first = -1 - link;
                var count = set.treeLinks[node * 2 + 1];
                for (var item = first; item < first + count; item++) {
                    var i = set.treeItems[item] * 9;
                    var e1x = t[i + 3] - t[i];
                    var e1y = t[i + 4] - t[i + 1];
                    var e1z = t[i + 5] - t[i + 2];
                    var e2x = t[i + 6] - t[i];
                    var e2y = t[i + 7] - t[i + 1];
                    var e2z = t[i + 8] - t[i + 2];
                    var px = dy * e2z - dz * e2y;
                    var py = dz * e2x - dx * e2z;
                    var pz = dx * e2y - dy * e2x;
                    var det = e1x * px + e1y * py + e1z * pz;
                    if (Math.abs(det) < 1e-12f) {
                        continue;
                    }
                    var inverse = 1f / det;
                    var tx = ax - t[i];
                    var ty = ay - t[i + 1];
                    var tz = az - t[i + 2];
                    var u = (tx * px + ty * py + tz * pz) * inverse;
                    if (u < 0 || u > 1) {
                        continue;
                    }
                    var qx = ty * e1z - tz * e1y;
                    var qy = tz * e1x - tx * e1z;
                    var qz = tx * e1y - ty * e1x;
                    var v = (dx * qx + dy * qy + dz * qz) * inverse;
                    if (v < 0 || u + v > 1) {
                        continue;
                    }
                    var time = (e2x * qx + e2y * qy + e2z * qz) * inverse;
                    if (time >= 0 && time <= 1 && time < best) {
                        best = time;
                    }
                }
            }
            if (best == Float.MAX_VALUE) {
                return false;
            }
            var wx = ex - sx;
            var wy = ey - sy;
            var wz = ez - sz;
            var length = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
            var retreat = 0.1f / length;
            if (retreat <= 0.1f) {
                retreat = 1f / length;
                if (retreat > 0.1f) {
                    retreat = 0.1f;
                }
            }
            var time = Math.max(0f, Math.min(1f, best - retreat));
            hitFraction = time;
            hitX = sx + wx * time;
            hitY = sy + wy * time;
            hitZ = sz + wz * time;
            return true;
        }

        private void push(int slot, int node, boolean face, float fraction) {
            meshNodes[slot] = node;
            meshFaces[slot] = face;
            meshFractions[slot] = fraction;
        }

        private void growMesh() {
            var size = meshNodes.length * 2;
            meshNodes = Arrays.copyOf(meshNodes, size);
            meshFaces = Arrays.copyOf(meshFaces, size);
            meshFractions = Arrays.copyOf(meshFractions, size);
        }
    }

    private static float planeDistance(float[] planes, int offset, float x, float y, float z) {
        return y * planes[offset + 1] + x * planes[offset] + z * planes[offset + 2] - planes[offset + 3];
    }

    private static float nonzero(float value) {
        return value == 0 ? ZERO_DELTA : value;
    }

    private static boolean doesRayTouchNodeBox(
            float[] boxes,
            int offset,
            float sx,
            float sy,
            float sz,
            float dx,
            float dy,
            float dz,
            float ix,
            float iy,
            float iz) {
        var inside = true;
        var entry = 0f;
        for (var axis = 0; axis < 3; axis++) {
            var s = axis == 0 ? sx : axis == 1 ? sy : sz;
            var direction = axis == 0 ? dx : axis == 1 ? dy : dz;
            var inverse = axis == 0 ? ix : axis == 1 ? iy : iz;
            var low = boxes[offset + axis];
            var high = boxes[offset + 3 + axis];
            float time;
            if (s < low) {
                if (direction <= 0) {
                    return false;
                }
                time = (low - s) * inverse;
                inside = false;
            } else if (s > high) {
                if (direction >= 0) {
                    return false;
                }
                time = (high - s) * inverse;
                inside = false;
            } else {
                time = 0;
            }
            entry = Math.max(entry, time);
        }
        if (inside) {
            return true;
        }
        if (entry < 0 || entry > 1) {
            return false;
        }
        var px = sx + dx * entry;
        var py = sy + dy * entry;
        var pz = sz + dz * entry;
        return boxes[offset] - 0.1f < px
                && px < boxes[offset + 3] + 0.1f
                && boxes[offset + 1] - 0.1f < py
                && py < boxes[offset + 4] + 0.1f
                && boxes[offset + 2] - 0.1f < pz
                && pz < boxes[offset + 5] + 0.1f;
    }
}
