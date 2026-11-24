package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class NativeTerrainTrace {
    private final int width;
    private final Vector[] vertices;
    private final BitSet visible, turned;
    private final boolean inverted;
    private final Node root;

    public NativeTerrainTrace(TerrainHeightField field, BitSet visibleQuads, BitSet edgeTurns, boolean inverted) {
        Objects.requireNonNull(field);
        width = field.getWidth();
        var count = Math.multiplyExact(width, field.getHeight());
        if (width > 256 || field.getHeight() > 256 || visibleQuads.length() > count || edgeTurns.length() > count) {
            throw new IllegalArgumentException("Unqualified native terrain grid");
        }
        visible = (BitSet) visibleQuads.clone();
        turned = (BitSet) edgeTurns.clone();
        this.inverted = inverted;
        vertices = new Vector[count];
        for (var y = 0; y < field.getHeight(); y++) {
            for (var x = 0; x < width; x++) {
                var point = field.getWorldAt(x, y);
                requireFinite(point);
                vertices[y * width + x] = point;
            }
        }
        root = build(0, 0, width - 1, field.getHeight() - 1);
    }

    public record Hit(
            float fraction,
            float surfaceFraction,
            Vector position,
            Vector normal,
            int quadX,
            int quadY,
            int triangle) {}

    private record Node(int minX, int minY, int maxX, int maxY, Vector minimum, Vector maximum, List<Node> children) {}

    public NativeTerrainTrace withHeights(TerrainHeightField field) {
        return new NativeTerrainTrace(field, visible, turned, inverted);
    }

    public boolean clear(Vector start, Vector end) {
        return findFirstHit(start, end).isEmpty();
    }

    public Optional<Hit> findFirstHit(Vector start, Vector end) {
        requireFinite(start);
        requireFinite(end);
        var delta = subtract(end, start);
        var lengthSquared = dot(delta, delta);
        if (!Float.isFinite(lengthSquared)) {
            throw new IllegalArgumentException("Terrain query segment overflows native float");
        }
        if (lengthSquared == 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(traceNode(root, start, end, delta, lengthSquared, null));
    }

    private Hit traceNode(Node node, Vector start, Vector end, Vector delta, float lengthSquared, Hit nearest) {
        if (!intersects(node, start, delta)) {
            return nearest;
        }
        if (!node.children().isEmpty()) {
            for (var child : node.children()) {
                nearest = traceNode(child, start, end, delta, lengthSquared, nearest);
            }
            return nearest;
        }
        for (var y = node.minY(); y < node.maxY(); y++) {
            for (var x = node.minX(); x < node.maxX(); x++) {
                var index = y * width + x;
                if (!visible.get(index)) {
                    continue;
                }
                var a = vertices[index];
                var b = subtract(vertices[index + 1], a);
                var c = subtract(vertices[index + width], a);
                var d = subtract(vertices[index + width + 1], a);
                var s = subtract(start, a);
                var e = subtract(end, a);
                var zero = new Vector(0, 0, 0);
                if (turned.get(index)) {
                    nearest = traceTriangle(zero, b, c, s, e, start, delta, lengthSquared, x, y, 0, nearest);
                    nearest = traceTriangle(c, b, d, s, e, start, delta, lengthSquared, x, y, 1, nearest);
                } else {
                    nearest = traceTriangle(zero, b, d, s, e, start, delta, lengthSquared, x, y, 0, nearest);
                    nearest = traceTriangle(zero, d, c, s, e, start, delta, lengthSquared, x, y, 1, nearest);
                }
            }
        }
        return nearest;
    }

    private Hit traceTriangle(
            Vector a,
            Vector b,
            Vector c,
            Vector startLocal,
            Vector endLocal,
            Vector start,
            Vector delta,
            float lengthSquared,
            int x,
            int y,
            int triangle,
            Hit nearest) {
        var upright = normalize(cross(subtract(b, a), subtract(c, a)));
        if (upright == null) {
            throw new IllegalArgumentException("Degenerate native terrain triangle");
        }
        var normal = inverted ? scale(upright, -1f) : upright;
        if (!(dot(normal, delta) < -0.0001f)) {
            return nearest;
        }
        var reverse = subtract(startLocal, endLocal);
        var fromEnd = (dot(normal, a) - dot(endLocal, normal)) / dot(reverse, normal);
        var intersection = add(endLocal, scale(reverse, fromEnd));
        var fraction = dot(subtract(intersection, startLocal), delta) / lengthSquared;
        if (!(fraction >= 0 && fraction <= 1)
                || (nearest != null && fraction >= nearest.surfaceFraction())
                || !isInside(a, b, intersection, upright)
                || !isInside(b, c, intersection, upright)
                || !isInside(c, a, intersection, upright)) {
            return nearest;
        }
        var retreat = Math.max(0f, Math.min(1f, fraction - 0.5f / (float) Math.sqrt(lengthSquared)));
        return new Hit(retreat, fraction, add(start, scale(delta, retreat)), normal, x, y, triangle);
    }

    private Node build(int minX, int minY, int maxX, int maxY) {
        float lx = Float.POSITIVE_INFINITY, ly = lx, lz = lx;
        float hx = Float.NEGATIVE_INFINITY, hy = hx, hz = hx;
        for (var y = minY; y <= maxY; y++) {
            for (var x = minX; x <= maxX; x++) {
                var point = vertices[y * width + x];
                lx = Math.min(lx, point.x());
                ly = Math.min(ly, point.y());
                lz = Math.min(lz, point.z());
                hx = Math.max(hx, point.x());
                hy = Math.max(hy, point.y());
                hz = Math.max(hz, point.z());
            }
        }
        var children = new ArrayList<Node>();
        if (maxX - minX > 4 || maxY - minY > 4) {
            var midX = maxX - minX > 4 ? (minX + maxX) / 2 : maxX;
            var midY = maxY - minY > 4 ? (minY + maxY) / 2 : maxY;
            children.add(build(minX, minY, midX, midY));
            if (midX < maxX) {
                children.add(build(midX, minY, maxX, midY));
            }
            if (midY < maxY) {
                children.add(build(minX, midY, midX, maxY));
            }
            if (midX < maxX && midY < maxY) {
                children.add(build(midX, midY, maxX, maxY));
            }
        }
        return new Node(minX, minY, maxX, maxY, new Vector(lx, ly, lz), new Vector(hx, hy, hz), List.copyOf(children));
    }

    private static boolean isInside(Vector a, Vector b, Vector point, Vector upright) {
        var plane = normalize(cross(subtract(b, a), upright));
        return plane != null && dot(plane, point) - dot(plane, a) <= 0;
    }

    private static boolean intersects(Node node, Vector start, Vector delta) {
        double near = 0, far = 1;
        for (var axis = 0; axis < 3; axis++) {
            var lo = Math.nextDown(component(node.minimum(), axis));
            var hi = Math.nextUp(component(node.maximum(), axis));
            var p = component(start, axis);
            var d = component(delta, axis);
            if (d == 0) {
                if (p < lo || p > hi) {
                    return false;
                }
            } else {
                var first = ((double) lo - p) / d;
                var last = ((double) hi - p) / d;
                near = Math.max(near, Math.min(first, last));
                far = Math.min(far, Math.max(first, last));
                if (near > far) {
                    return false;
                }
            }
        }
        return true;
    }

    private static float component(Vector v, int axis) {
        return axis == 0 ? v.x() : axis == 1 ? v.y() : v.z();
    }

    private static Vector normalize(Vector value) {
        var squared = dot(value, value);
        if (!(squared > 0) || !Float.isFinite(squared)) {
            return null;
        }
        return scale(value, 1f / (float) Math.sqrt(squared));
    }

    private static Vector cross(Vector a, Vector b) {
        return new Vector(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
    }

    private static float dot(Vector a, Vector b) {
        return a.y() * b.y() + a.x() * b.x() + a.z() * b.z();
    }

    private static Vector subtract(Vector a, Vector b) {
        return new Vector(a.x() - b.x(), a.y() - b.y(), a.z() - b.z());
    }

    private static Vector add(Vector a, Vector b) {
        return new Vector(a.x() + b.x(), a.y() + b.y(), a.z() + b.z());
    }

    private static Vector scale(Vector a, float scalar) {
        return new Vector(a.x() * scalar, a.y() * scalar, a.z() * scalar);
    }

    private static void requireFinite(Vector point) {
        Objects.requireNonNull(point);
        if (!Float.isFinite(point.x()) || !Float.isFinite(point.y()) || !Float.isFinite(point.z())) {
            throw new IllegalArgumentException("Non-finite native terrain query coordinate");
        }
    }
}
