package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.UActor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

public final class NativeBspTrace {
    private static final int MAX_RECORDS = 1_000_000;
    private static final float PLANE_EPSILON = 0.001f;
    private static final int NOT_CSG_OR_NEW = 0x21;
    private static final int IGNORE_INITIAL_SOLID = 0x10;

    private static final Result CLEAR = new Result(true, Optional.empty());
    private static final Result EMPTY_SOLID = new Result(false, Optional.empty());

    private final List<Node> nodes;
    private final List<Integer> surfaceFlags;
    private final boolean rootOutside;

    public NativeBspTrace(List<Node> nodes, List<Integer> surfaceFlags, boolean rootOutside) {
        if (nodes.size() > MAX_RECORDS || surfaceFlags.size() > MAX_RECORDS) {
            throw new IllegalArgumentException("Native BSP snapshot exceeds editor bounds");
        }
        this.nodes = List.copyOf(nodes);
        this.surfaceFlags = List.copyOf(surfaceFlags);
        this.rootOutside = rootOutside;
        validate();
    }

    public record Node(
            Vector normal,
            float distance,
            int flags,
            int vertexCount,
            int front,
            int back,
            int coplanar,
            int surface) {}

    public record Travel(float fraction, float surfaceFraction) {}

    public record Hit(
            Optional<Travel> travel, Vector position, Vector normal, int node, int surface, int surfaceFlags) {
        public Hit {
            Objects.requireNonNull(travel);
            Objects.requireNonNull(position);
            Objects.requireNonNull(normal);
        }
    }

    public record Result(boolean clear, Optional<Hit> hit) {
        public Result {
            Objects.requireNonNull(hit);
            if (clear && hit.isPresent()) {
                throw new IllegalArgumentException("A clear BSP query cannot carry a hit");
            }
        }
    }

    private record Segment(int node, int lastCrossing, Vector start, Vector end, boolean outside) {}

    public static NativeBspTrace snapshot(UModel model, @Nullable UActor owner) {
        Objects.requireNonNull(model);
        if (owner != null) {
            throw new IllegalArgumentException("Transformed actor BSP is not qualified");
        }
        requireCompleteModel(model);
        return copy(model);
    }

    public static NativeBspTrace shadowSnapshot(UModel model) {
        Objects.requireNonNull(model);
        requireTraceModel(model);
        return copy(model);
    }

    static void requireTraceModel(UModel model) {
        if (model.getVectors() == null
                || model.getPoints() == null
                || model.getNodes() == null
                || model.getSurfaces() == null
                || model.getVertices() == null
                || model.getZones() == null
                || !model.isRootOutsideRead()) {
            throw new IllegalArgumentException("Incomplete native BSP model for shadow traces");
        }
        if (model.getNodes().length > MAX_RECORDS || model.getSurfaces().length > MAX_RECORDS) {
            throw new IllegalArgumentException("Native BSP snapshot exceeds editor bounds");
        }
    }

    static void requireCompleteModel(UModel model) {
        if (model.getVectors() == null
                || model.getPoints() == null
                || model.getNodes() == null
                || model.getSurfaces() == null
                || model.getVertices() == null
                || model.getZones() == null
                || model.getLightmapTailError() != null
                || model.getLightingMetadataError() != null
                || model.getBspSections() == null
                || model.getLightmaps() == null
                || model.getLightmapAtlases() == null
                || model.getLightmapExtents() == null) {
            throw new IllegalArgumentException("Incomplete native BSP model or unqualified model tail");
        }
        if (model.getNodes().length > MAX_RECORDS || model.getSurfaces().length > MAX_RECORDS) {
            throw new IllegalArgumentException("Native BSP snapshot exceeds editor bounds");
        }
    }

    public static boolean doesSingleLineCheckSkip(Hit hit, boolean hitActorIsLevelInfo, int traceFlags) {
        return hitActorIsLevelInfo && (traceFlags & 0x100) != 0 && (hit.surfaceFlags() & 0x80) != 0;
    }

    public Result checkLine(Vector start, Vector end, Vector extent, int nodeExtraFlags, int traceFlags) {
        requireFinite(extent);
        if (extent.x() != 0 || extent.y() != 0 || extent.z() != 0) {
            throw new IllegalArgumentException("Nonzero-extent BSP query is not qualified");
        }
        return checkLine(start, end, nodeExtraFlags, traceFlags);
    }

    public Result checkLine(Vector start, Vector end, int nodeExtraFlags, int traceFlags) {
        requireFinite(start);
        requireFinite(end);
        if ((traceFlags & 0x1000) != 0) {
            throw new IllegalArgumentException("BSP material-refinement trace is not qualified");
        }
        if (nodes.isEmpty()) {
            return rootOutside ? CLEAR : EMPTY_SOLID;
        }
        var delta = subtract(end, start);
        requireFinite(delta);
        var lengthSquared = dot(delta, delta);
        if (!Float.isFinite(lengthSquared)
                || (lengthSquared == 0 && (delta.x() != 0 || delta.y() != 0 || delta.z() != 0))) {
            throw new IllegalArgumentException("BSP query exceeds native float range");
        }
        var pending = new ArrayDeque<Segment>();
        var nodeIndex = 0;
        var lastCrossing = 0;
        var segmentStart = start;
        var segmentEnd = end;
        var outside = rootOutside;
        var seenOutside = false;
        long visited = 0;
        while (true) {
            while (nodeIndex != -1) {
                if (++visited > 2L * nodes.size()) {
                    throw new IllegalArgumentException("Native BSP query exceeds bounded topology traversal");
                }
                var node = nodes.get(nodeIndex);
                var startDistance = planeDot(segmentStart, node);
                var endDistance = planeDot(segmentEnd, node);
                if (startDistance > -PLANE_EPSILON && endDistance > -PLANE_EPSILON) {
                    outside |= isCsg(node, nodeExtraFlags & ~IGNORE_INITIAL_SOLID);
                    nodeIndex = node.front();
                } else if (startDistance < PLANE_EPSILON && endDistance < PLANE_EPSILON) {
                    outside &= !isCsg(node, nodeExtraFlags & ~IGNORE_INITIAL_SOLID);
                    nodeIndex = node.back();
                } else {
                    var denominator = endDistance - startDistance;
                    if (!Float.isFinite(denominator)) {
                        throw new IllegalArgumentException("BSP split exceeds native float range");
                    }
                    var scale = startDistance / denominator;
                    var middle = add(segmentStart, multiply(subtract(segmentStart, segmentEnd), scale));
                    requireFinite(middle);
                    var fromFront = startDistance > 0;
                    var csg = isCsg(node, nodeExtraFlags);
                    var farOutside = fromFront ? outside && !csg : outside || csg;
                    pending.push(new Segment(
                            fromFront ? node.back() : node.front(), nodeIndex, middle, segmentEnd, farOutside));
                    outside = fromFront ? outside || csg : outside && !csg;
                    nodeIndex = fromFront ? node.front() : node.back();
                    segmentEnd = middle;
                }
            }
            if (outside) {
                seenOutside = true;
            } else if (seenOutside || (nodeExtraFlags & IGNORE_INITIAL_SOLID) == 0) {
                return computeHit(start, delta, lengthSquared, segmentStart, lastCrossing);
            }
            if (pending.isEmpty()) {
                return CLEAR;
            }
            var next = pending.pop();
            nodeIndex = next.node();
            lastCrossing = next.lastCrossing();
            segmentStart = next.start();
            segmentEnd = next.end();
            outside = next.outside();
        }
    }

    private Result computeHit(Vector start, Vector delta, float lengthSquared, Vector surfacePosition, int item) {
        var node = nodes.get(item);
        var normal = node.normal();
        var position = start;
        Optional<Travel> travel = Optional.empty();
        if (lengthSquared != 0) {
            var raw = dot(subtract(surfacePosition, start), delta) / lengthSquared;
            var fraction = Math.max(0f, Math.min(1f, raw - 0.5f / (float) Math.sqrt(lengthSquared)));
            if (!Float.isFinite(raw)) {
                throw new IllegalArgumentException("BSP hit exceeds native float range");
            }
            position = add(start, multiply(delta, fraction));
            requireFinite(position);
            travel = Optional.of(new Travel(fraction, raw));
        }
        var facing = normal.y() * -delta.y() + normal.x() * -delta.x() + normal.z() * -delta.z();
        if (!Float.isFinite(facing)) {
            throw new IllegalArgumentException("BSP hit normal exceeds native float range");
        }
        if (facing < 0) {
            normal = multiply(normal, -1f);
        }
        return new Result(
                false,
                Optional.of(new Hit(travel, position, normal, item, node.surface(), surfaceFlags.get(node.surface()))));
    }

    private void validate() {
        var incoming = new int[nodes.size()];
        for (var node : nodes) {
            requireFinite(node.normal());
            var norm = dot(node.normal(), node.normal());
            if (!Float.isFinite(node.distance())
                    || !Float.isFinite(norm)
                    || norm == 0
                    || node.flags() < 0
                    || node.flags() > 255
                    || node.vertexCount() < 0
                    || node.vertexCount() > 255
                    || node.surface() < 0
                    || node.surface() >= surfaceFlags.size()) {
                throw new IllegalArgumentException("Invalid native BSP node fields");
            }
            link(node.front(), incoming);
            link(node.back(), incoming);
            link(node.coplanar(), incoming);
        }
        var ready = new int[nodes.size()];
        var tail = 0;
        for (var i = 0; i < incoming.length; i++) {
            if (incoming[i] == 0) {
                ready[tail++] = i;
            }
        }
        var head = 0;
        while (head < tail) {
            var node = nodes.get(ready[head++]);
            tail = release(node.front(), incoming, ready, tail);
            tail = release(node.back(), incoming, ready, tail);
            tail = release(node.coplanar(), incoming, ready, tail);
        }
        if (head != nodes.size()) {
            throw new IllegalArgumentException("Cyclic native BSP topology");
        }
    }

    private static NativeBspTrace copy(UModel model) {
        var nodes = new ArrayList<Node>(model.getNodes().length);
        for (var node : model.getNodes()) {
            if (node == null || node.getPlane() == null) {
                throw new IllegalArgumentException("Incomplete native BSP node");
            }
            var plane = node.getPlane();
            nodes.add(new Node(
                    new Vector(plane.getX(), plane.getY(), plane.getZ()),
                    plane.getW(),
                    node.getFlags(),
                    node.getVertexCount(),
                    node.getFrontIndex(),
                    node.getBackIndex(),
                    node.getPlaneIndex(),
                    node.getSurfaceIndex()));
        }
        var surfaces = new ArrayList<Integer>(model.getSurfaces().length);
        for (var surface : model.getSurfaces()) {
            if (surface == null) {
                throw new IllegalArgumentException("Incomplete native BSP surface");
            }
            surfaces.add((int) surface.getPolygonFlags());
        }
        return new NativeBspTrace(nodes, surfaces, model.isRootOutside());
    }

    private static void link(int index, int[] incoming) {
        if (index < -1 || index >= incoming.length) {
            throw new IllegalArgumentException("Invalid native BSP child index");
        }
        if (index != -1) {
            incoming[index]++;
        }
    }

    private static int release(int index, int[] incoming, int[] ready, int tail) {
        if (index != -1 && --incoming[index] == 0) {
            ready[tail++] = index;
        }
        return tail;
    }

    private static boolean isCsg(Node node, int extraFlags) {
        return node.vertexCount() != 0 && (node.flags() & (NOT_CSG_OR_NEW | extraFlags)) == 0;
    }

    private static float planeDot(Vector point, Node node) {
        var normal = node.normal();
        var value = (point.y() * normal.y() + point.x() * normal.x() + point.z() * normal.z()) - node.distance();
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("BSP plane distance exceeds native float range");
        }
        return value;
    }

    private static float dot(Vector a, Vector b) {
        return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
    }

    private static Vector subtract(Vector a, Vector b) {
        return new Vector(a.x() - b.x(), a.y() - b.y(), a.z() - b.z());
    }

    private static Vector add(Vector a, Vector b) {
        return new Vector(a.x() + b.x(), a.y() + b.y(), a.z() + b.z());
    }

    private static Vector multiply(Vector a, float scale) {
        return new Vector(a.x() * scale, a.y() * scale, a.z() * scale);
    }

    private static void requireFinite(Vector vector) {
        if (vector == null
                || !Float.isFinite(vector.x())
                || !Float.isFinite(vector.y())
                || !Float.isFinite(vector.z())) {
            throw new IllegalArgumentException("Non-finite native BSP vector");
        }
    }
}
