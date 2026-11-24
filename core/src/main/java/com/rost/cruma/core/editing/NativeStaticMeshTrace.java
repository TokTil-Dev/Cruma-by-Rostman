package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeStaticMeshCollisionCodec.Collision;
import com.rost.cruma.core.editing.NativeStaticMeshCollisionCodec.Face;
import com.rost.cruma.core.editing.NativeStaticMeshCollisionCodec.Node;
import com.rost.cruma.core.editing.NativeStaticMeshCollisionCodec.Plane;
import com.rost.cruma.core.editing.TerrainNativeCodec.Box;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class NativeStaticMeshTrace {
    private static final int MAX_RECORDS = 1_000_000;
    private static final float PLANE_EPSILON = 0.001f;
    private static final float ZERO_DELTA = 1e-7f;

    private final List<Face> faces;
    private final List<Node> nodes;
    private final MatrixPair matrices;

    public NativeStaticMeshTrace(Collision collision, MatrixPair matrices, boolean effectiveUseCylinderCollision) {
        Objects.requireNonNull(collision);
        this.matrices = Objects.requireNonNull(matrices);
        if (effectiveUseCylinderCollision) {
            throw new IllegalArgumentException("Native cylinder collision is not qualified");
        }
        if (collision.faces().size() > MAX_RECORDS || collision.nodes().size() > MAX_RECORDS) {
            throw new IllegalArgumentException("Native mesh collision exceeds editor bounds");
        }
        faces = List.copyOf(collision.faces());
        nodes = List.copyOf(collision.nodes());
        validate();
    }

    public static final class MatrixPair {
        private final float[] worldToLocal;
        private final float[] localToWorld;

        public MatrixPair(float[] worldToLocal, float[] localToWorld) {
            this.worldToLocal = copyValidMatrix(worldToLocal);
            this.localToWorld = copyValidMatrix(localToWorld);
        }

        private Vector localPoint(Vector point) {
            return transform(worldToLocal, point, false);
        }

        private Vector forwardNormal(Vector normal) {
            return transform(localToWorld, normal, true);
        }

        private static float[] copyValidMatrix(float[] values) {
            if (values == null || values.length != 16) {
                throw new IllegalArgumentException("Native actor matrix must contain sixteen floats");
            }
            var copy = values.clone();
            for (var value : copy) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("Non-finite native actor matrix");
                }
            }
            if (copy[3] != 0 || copy[7] != 0 || copy[11] != 0 || copy[15] != 1) {
                throw new IllegalArgumentException("Non-affine native actor matrix");
            }
            var determinant = (double) copy[0] * ((double) copy[5] * copy[10] - (double) copy[9] * copy[6])
                    - (double) copy[4] * ((double) copy[1] * copy[10] - (double) copy[9] * copy[2])
                    + (double) copy[8] * ((double) copy[1] * copy[6] - (double) copy[5] * copy[2]);
            if (!Double.isFinite(determinant) || determinant == 0) {
                throw new IllegalArgumentException("Singular native actor transform is not qualified");
            }
            return copy;
        }

        private static Vector transform(float[] m, Vector value, boolean normal) {
            var w = normal ? 0f : 1f;
            var x = m[4] * value.y() + m[0] * value.x() + m[8] * value.z() + m[12] * w;
            var y = m[1] * value.x() + m[5] * value.y() + m[9] * value.z() + m[13] * w;
            var z = m[2] * value.x() + m[6] * value.y() + m[10] * value.z() + m[14] * w;
            var result = new Vector(x, y, z);
            requireFinite(result);
            return result;
        }
    }

    public record Hit(
            float fraction, float surfaceFraction, Vector position, Vector rawForwardNormal, int face, int material) {}

    private record Step(int node, boolean testFace, float fraction) {}

    public boolean clear(Vector start, Vector end, int traceFlags) {
        return findFirstHit(start, end, traceFlags).isEmpty();
    }

    public Optional<Hit> findFirstHit(Vector start, Vector end, Vector extent, int traceFlags) {
        requireFinite(extent);
        if (extent.x() != 0 || extent.y() != 0 || extent.z() != 0) {
            throw new IllegalArgumentException("Nonzero-extent mesh query is not qualified");
        }
        return findFirstHit(start, end, traceFlags);
    }

    public Optional<Hit> findFirstHit(Vector start, Vector end, int traceFlags) {
        requireFinite(start);
        requireFinite(end);
        if ((traceFlags & 0x100) == 0) {
            throw new IllegalArgumentException("Only native shadow mesh queries are qualified");
        }
        if (nodes.isEmpty()) {
            return Optional.empty();
        }
        var worldDelta = subtract(end, start);
        requireFinite(worldDelta);
        var lengthSquared = dot(worldDelta, worldDelta);
        if (!Float.isFinite(lengthSquared)
                || (lengthSquared == 0 && (worldDelta.x() != 0 || worldDelta.y() != 0 || worldDelta.z() != 0))) {
            throw new IllegalArgumentException("Mesh query exceeds native float range");
        }
        if (lengthSquared == 0) {
            return Optional.empty();
        }
        var localStart = matrices.localPoint(start);
        var localEnd = matrices.localPoint(end);
        var originalDelta = subtract(localEnd, localStart);
        requireFinite(originalDelta);
        var delta = new Vector(nonzero(originalDelta.x()), nonzero(originalDelta.y()), nonzero(originalDelta.z()));
        var inverse = new Vector(1f / delta.x(), 1f / delta.y(), 1f / delta.z());
        requireFinite(inverse);
        var pending = new ArrayDeque<Step>();
        pending.push(new Step(0, false, 0));
        var remainingSteps = 2L * nodes.size();
        while (!pending.isEmpty()) {
            var step = pending.pop();
            if (step.node() == -1) {
                continue;
            }
            if (--remainingSteps < 0) {
                throw new IllegalArgumentException("Native mesh traversal exceeds its topology budget");
            }
            var node = nodes.get(step.node());
            var face = faces.get(node.face());
            if (step.testFace()) {
                var point = add(localStart, multiply(delta, step.fraction()));
                requireFinite(point);
                if (distance(point, face.edge0()) < 0
                        && distance(point, face.edge1()) < 0
                        && distance(point, face.edge2()) < 0) {
                    return Optional.of(
                            computeHit(start, worldDelta, lengthSquared, step.fraction(), node.face(), face));
                }
                continue;
            }
            if (!intersects(node.bounds(), localStart, delta, inverse)) {
                continue;
            }
            var startDistance = distance(localStart, face.surface());
            var endDistance = distance(localEnd, face.surface());
            if (startDistance > -PLANE_EPSILON && endDistance > -PLANE_EPSILON) {
                pending.push(new Step(node.front(), false, 0));
            } else if (startDistance < PLANE_EPSILON && endDistance < PLANE_EPSILON) {
                pending.push(new Step(node.back(), false, 0));
            } else {
                var denominator = endDistance - startDistance;
                if (!Float.isFinite(denominator)) {
                    throw new IllegalArgumentException("Mesh plane crossing exceeds native float range");
                }
                var fraction = -(startDistance / denominator);
                if (!Float.isFinite(fraction) || fraction < 0 || fraction > 1) {
                    throw new IllegalArgumentException("Invalid native mesh plane crossing");
                }
                var fromFront = startDistance > -PLANE_EPSILON;
                pending.push(new Step(fromFront ? node.back() : node.front(), false, 0));
                pending.push(new Step(step.node(), true, fraction));
                pending.push(new Step(node.coplanar(), false, 0));
                pending.push(new Step(fromFront ? node.front() : node.back(), false, 0));
            }
        }
        return Optional.empty();
    }

    private Hit computeHit(
            Vector start, Vector worldDelta, float lengthSquared, float surfaceFraction, int faceIndex, Face face) {
        var length = (float) Math.sqrt(lengthSquared);
        var retreat = 0.1f / length;
        if (retreat <= 0.1f) {
            retreat = 1f / length;
            if (retreat > 0.1f) {
                retreat = 0.1f;
            }
        }
        var fraction = Math.max(0f, Math.min(1f, surfaceFraction - retreat));
        var position = add(start, multiply(worldDelta, fraction));
        requireFinite(position);
        var normal = matrices.forwardNormal(face.surface().normal());
        if (normal.x() == 0 && normal.y() == 0 && normal.z() == 0) {
            throw new IllegalArgumentException("Native mesh normal underflows to zero");
        }
        return new Hit(fraction, surfaceFraction, position, normal, faceIndex, face.material());
    }

    private void validate() {
        for (var face : faces) {
            requireValidPlane(face.surface());
            requireValidPlane(face.edge0());
            requireValidPlane(face.edge1());
            requireValidPlane(face.edge2());
            if (face.vertex0() < 0 || face.vertex1() < 0 || face.vertex2() < 0 || face.material() < 0) {
                throw new IllegalArgumentException("Invalid native mesh face identity");
            }
        }
        var incoming = new int[nodes.size()];
        for (var node : nodes) {
            if (node.face() < 0 || node.face() >= faces.size()) {
                throw new IllegalArgumentException("Invalid native mesh face reference");
            }
            var box = node.bounds();
            requireFinite(box.min());
            requireFinite(box.max());
            if (box.valid() != 0 && box.valid() != 1
                    || box.min().x() > box.max().x()
                    || box.min().y() > box.max().y()
                    || box.min().z() > box.max().z()) {
                throw new IllegalArgumentException("Unqualified native mesh node bounds");
            }
            link(node.front(), incoming);
            link(node.back(), incoming);
            link(node.coplanar(), incoming);
        }
        var ready = new int[nodes.size()];
        var head = 0;
        var tail = 0;
        for (var i = 0; i < incoming.length; i++) {
            if (incoming[i] == 0) {
                ready[tail++] = i;
            }
        }
        while (head < tail) {
            var node = nodes.get(ready[head++]);
            tail = release(node.front(), incoming, ready, tail);
            tail = release(node.back(), incoming, ready, tail);
            tail = release(node.coplanar(), incoming, ready, tail);
        }
        if (head != nodes.size()) {
            throw new IllegalArgumentException("Cyclic native mesh collision topology");
        }
    }

    private static boolean intersects(Box box, Vector start, Vector delta, Vector inverse) {
        var inside = true;
        var entry = 0f;
        for (var axis = 0; axis < 3; axis++) {
            var s = component(start, axis);
            var low = component(box.min(), axis);
            var high = component(box.max(), axis);
            var direction = component(delta, axis);
            float time;
            if (s < low) {
                if (direction <= 0) {
                    return false;
                }
                time = (low - s) * component(inverse, axis);
                inside = false;
            } else if (s > high) {
                if (direction >= 0) {
                    return false;
                }
                time = (high - s) * component(inverse, axis);
                inside = false;
            } else {
                time = 0;
            }
            if (!Float.isFinite(time)) {
                throw new IllegalArgumentException("Native mesh bounds query exceeds float range");
            }
            entry = Math.max(entry, time);
        }
        if (inside) {
            return true;
        }
        if (entry < 0 || entry > 1) {
            return false;
        }
        var point = add(start, multiply(delta, entry));
        requireFinite(point);
        for (var axis = 0; axis < 3; axis++) {
            var low = component(box.min(), axis) - 0.1f;
            var high = component(box.max(), axis) + 0.1f;
            var value = component(point, axis);
            if (!Float.isFinite(low) || !Float.isFinite(high)) {
                throw new IllegalArgumentException("Native mesh padded bounds exceed float range");
            }
            if (!(low < value && value < high)) {
                return false;
            }
        }
        return true;
    }

    private static void link(int index, int[] incoming) {
        if (index < -1 || index >= incoming.length) {
            throw new IllegalArgumentException("Invalid native mesh node reference");
        }
        if (index >= 0) {
            incoming[index]++;
        }
    }

    private static int release(int index, int[] incoming, int[] ready, int tail) {
        if (index >= 0 && --incoming[index] == 0) {
            ready[tail++] = index;
        }
        return tail;
    }

    private static void requireValidPlane(Plane plane) {
        requireFinite(plane.normal());
        var squared = dot(plane.normal(), plane.normal());
        if (!Float.isFinite(plane.distance()) || !Float.isFinite(squared) || squared == 0) {
            throw new IllegalArgumentException("Degenerate native mesh collision plane");
        }
    }

    private static float distance(Vector point, Plane plane) {
        var n = plane.normal();
        var value = point.y() * n.y() + point.x() * n.x() + point.z() * n.z() - plane.distance();
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Native mesh plane query exceeds float range");
        }
        return value;
    }

    private static float nonzero(float value) {
        return value == 0 ? ZERO_DELTA : value;
    }

    private static float component(Vector value, int axis) {
        return axis == 0 ? value.x() : axis == 1 ? value.y() : value.z();
    }

    private static Vector subtract(Vector a, Vector b) {
        return new Vector(a.x() - b.x(), a.y() - b.y(), a.z() - b.z());
    }

    private static Vector add(Vector a, Vector b) {
        return new Vector(a.x() + b.x(), a.y() + b.y(), a.z() + b.z());
    }

    private static Vector multiply(Vector vector, float scale) {
        return new Vector(vector.x() * scale, vector.y() * scale, vector.z() * scale);
    }

    private static float dot(Vector a, Vector b) {
        return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
    }

    private static void requireFinite(Vector vector) {
        if (vector == null
                || !Float.isFinite(vector.x())
                || !Float.isFinite(vector.y())
                || !Float.isFinite(vector.z())) {
            throw new IllegalArgumentException("Non-finite native mesh vector");
        }
    }
}
