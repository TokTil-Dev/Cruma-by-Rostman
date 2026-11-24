package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeLevelShadowQuery.State;
import com.rost.cruma.core.editing.P152FreshHashQuery.Ray;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

final class ShadowTriangleQuery {
    private static final int MAX_TRIANGLES = 1_000_000;
    private static final int LEAF_SIZE = 8;

    private final List<Triangle> triangles;
    private final Integer[] order;
    private final Node root;

    ShadowTriangleQuery(List<Triangle> triangles, BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        if (triangles.size() > MAX_TRIANGLES) {
            throw new IllegalArgumentException("Shadow triangle count exceeds editor bounds");
        }
        this.triangles = List.copyOf(triangles);
        order = new Integer[triangles.size()];
        for (int i = 0; i < order.length; i++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            order[i] = i;
        }
        root = order.length == 0 ? null : build(0, order.length, cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
    }

    enum Sides {
        BOTH,
        FRONT,
        BACK
    }

    record Triangle(int owner, int part, int face, Vector a, Vector b, Vector c) {
        Triangle {
            requireFinite(a);
            requireFinite(b);
            requireFinite(c);
        }
    }

    record Hit(int triangle, int owner, int part, int face, double fraction, boolean front) {}

    private record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        boolean intersects(Ray ray, double limit) {
            double lo = 0, hi = limit;
            for (int axis = 0; axis < 3; axis++) {
                double start = component(ray.start(), axis);
                double delta = (double) component(ray.end(), axis) - start;
                double min = axis == 0 ? minX : axis == 1 ? minY : minZ;
                double max = axis == 0 ? maxX : axis == 1 ? maxY : maxZ;
                double end = component(ray.end(), axis);
                if (Math.min(start, end) > max || Math.max(start, end) < min) {
                    return false;
                }
                if (delta == 0) {
                    if (start < min || start > max) {
                        return false;
                    }
                } else {
                    double t0 = (min - start) / delta, t1 = (max - start) / delta;
                    lo = Math.max(lo, Math.min(t0, t1));
                    hi = Math.min(hi, Math.max(t0, t1));
                    if (lo > hi) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    private record Node(Bounds bounds, int start, int end, Node left, Node right) {}

    int size() {
        return triangles.size();
    }

    Triangle getTriangle(int index) {
        return triangles.get(index);
    }

    List<State> evaluate(List<Ray> rays, Sides sides, BooleanSupplier cancelled) {
        Objects.requireNonNull(sides);
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        if (rays.isEmpty() || rays.size() > 65536) {
            throw new IllegalArgumentException("Shadow query requires one bounded nonempty ray batch");
        }
        var frozen = List.copyOf(rays);
        var states = new ArrayList<State>(frozen.size());
        for (var ray : frozen) {
            states.add(findFirstHit(ray, sides, cancelled).isPresent() ? State.BLOCKED : State.CLEAR);
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return List.copyOf(states);
    }

    Optional<Hit> findFirstHit(Ray ray, Sides sides, BooleanSupplier cancelled) {
        Objects.requireNonNull(sides);
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        P152FreshHashQuery.validate(ray);
        var hit = root == null ? null : visit(root, ray, sides, null, cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        return Optional.ofNullable(hit);
    }

    private Node build(int start, int end, BooleanSupplier cancelled) {
        P152FreshHashQuery.checkCancelled(cancelled);
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int i = start; i < end; i++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var triangle = triangles.get(order[i]);
            for (int vertex = 0; vertex < 3; vertex++) {
                var point = vertex == 0 ? triangle.a : vertex == 1 ? triangle.b : triangle.c;
                minX = Math.min(minX, point.x());
                maxX = Math.max(maxX, point.x());
                minY = Math.min(minY, point.y());
                maxY = Math.max(maxY, point.y());
                minZ = Math.min(minZ, point.z());
                maxZ = Math.max(maxZ, point.z());
            }
        }
        var bounds = new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
        if (end - start <= LEAF_SIZE) {
            return new Node(bounds, start, end, null, null);
        }
        int axis = maxX - minX >= maxY - minY ? 0 : 1;
        if (maxZ - minZ > (axis == 0 ? maxX - minX : maxY - minY)) {
            axis = 2;
        }
        final int splitAxis = axis;
        Arrays.sort(order, start, end, Comparator.comparingDouble(index -> {
            P152FreshHashQuery.checkCancelled(cancelled);
            var t = triangles.get(index);
            return ((double) component(t.a, splitAxis) + component(t.b, splitAxis) + component(t.c, splitAxis)) / 3;
        }));
        int middle = (start + end) >>> 1;
        return new Node(bounds, start, end, build(start, middle, cancelled), build(middle, end, cancelled));
    }

    private Hit visit(Node node, Ray ray, Sides sides, Hit closest, BooleanSupplier cancelled) {
        P152FreshHashQuery.checkCancelled(cancelled);
        if (!node.bounds.intersects(ray, closest == null ? 1 : closest.fraction)) {
            return closest;
        }
        if (node.left != null) {
            closest = visit(node.left, ray, sides, closest, cancelled);
            return visit(node.right, ray, sides, closest, cancelled);
        }
        for (int i = node.start; i < node.end; i++) {
            P152FreshHashQuery.checkCancelled(cancelled);
            int index = order[i];
            var hit = intersect(triangles.get(index), index, ray, sides);
            if (hit != null
                    && (closest == null
                            || hit.fraction < closest.fraction
                            || hit.fraction == closest.fraction && hit.triangle < closest.triangle)) {
                closest = hit;
            }
        }
        return closest;
    }

    private static Hit intersect(Triangle t, int index, Ray ray, Sides sides) {
        double dx = (double) ray.end().x() - ray.start().x();
        double dy = (double) ray.end().y() - ray.start().y();
        double dz = (double) ray.end().z() - ray.start().z();
        double e1x = (double) t.b.x() - t.a.x(), e1y = (double) t.b.y() - t.a.y(), e1z = (double) t.b.z() - t.a.z();
        double e2x = (double) t.c.x() - t.a.x(), e2y = (double) t.c.y() - t.a.y(), e2z = (double) t.c.z() - t.a.z();
        double px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
        double determinant = e1x * px + e1y * py + e1z * pz;
        if (determinant == 0 || sides == Sides.FRONT && determinant < 0 || sides == Sides.BACK && determinant > 0) {
            return null;
        }
        double tx = (double) ray.start().x() - t.a.x(),
                ty = (double) ray.start().y() - t.a.y(),
                tz = (double) ray.start().z() - t.a.z();
        double nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
        double startDistance = tx * nx + ty * ny + tz * nz;
        double endDistance = ((double) ray.end().x() - t.a.x()) * nx
                + ((double) ray.end().y() - t.a.y()) * ny
                + ((double) ray.end().z() - t.a.z()) * nz;
        if (startDistance > 0 && endDistance > 0 || startDistance < 0 && endDistance < 0) {
            return null;
        }
        double u = (tx * px + ty * py + tz * pz) / determinant;
        if (u < 0 || u > 1) {
            return null;
        }
        double qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
        double v = (dx * qx + dy * qy + dz * qz) / determinant;
        if (v < 0 || u + v > 1) {
            return null;
        }
        double fraction = (e2x * qx + e2y * qy + e2z * qz) / determinant;
        return fraction < 0 || fraction > 1 ? null : new Hit(index, t.owner, t.part, t.face, fraction, determinant > 0);
    }

    private static float component(Vector v, int axis) {
        return axis == 0 ? v.x() : axis == 1 ? v.y() : v.z();
    }

    private static void requireFinite(Vector v) {
        if (v == null || !Float.isFinite(v.x()) || !Float.isFinite(v.y()) || !Float.isFinite(v.z())) {
            throw new IllegalArgumentException("Non-finite shadow triangle vertex");
        }
    }
}
