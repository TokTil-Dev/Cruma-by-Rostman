package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorTransform.Vector3;
import java.util.Objects;

public record BspSurfaceMapping(Vector3 base, Vector3 u, Vector3 v) {
    static final float MIN_AXIS = 1e-5f;

    static final float MAX_AXIS = 4096f;

    public BspSurfaceMapping {
        base = positiveZeros(Objects.requireNonNull(base));
        u = positiveZeros(Objects.requireNonNull(u));
        v = positiveZeros(Objects.requireNonNull(v));
    }

    static float length(Vector3 axis) {
        return (float) Math.sqrt(lengthSquared(axis));
    }

    public BspSurfaceMapping pan(float panU, float panV) {
        var su = lengthSquared(u);
        var sv = lengthSquared(v);
        var x = base.x() + u.x() / su * panU + v.x() / sv * panV;
        var y = base.y() + u.y() / su * panU + v.y() / sv * panV;
        var z = base.z() + u.z() / su * panU + v.z() / sv * panV;
        return new BspSurfaceMapping(new Vector3(x, y, z), u, v);
    }

    public BspSurfaceMapping resetPan() {
        return new BspSurfaceMapping(Vector3.ZERO, u, v);
    }

    public BspSurfaceMapping multiply(float uu, float uv, float vu, float vv) {
        return new BspSurfaceMapping(base, combine(u, uu, v, uv), combine(u, vu, v, vv));
    }

    public BspSurfaceMapping rotate(double degrees) {
        var radians = Math.toRadians(degrees);
        var cos = (float) Math.cos(radians);
        var sin = (float) Math.sin(radians);
        if (degrees % 90 == 0) {
            cos = Math.round(cos);
            sin = Math.round(sin);
        }
        return multiply(cos, sin, -sin, cos);
    }

    public BspSurfaceMapping flipU() {
        return multiply(-1, 0, 0, 1);
    }

    public BspSurfaceMapping flipV() {
        return multiply(1, 0, 0, -1);
    }

    public float scaleU() {
        return 1f / length(u);
    }

    public float scaleV() {
        return 1f / length(v);
    }

    public BspSurfaceMapping withScale(float scaleU, float scaleV) {
        if (!(scaleU > 0) || !(scaleV > 0) || !Float.isFinite(scaleU) || !Float.isFinite(scaleV)) {
            throw new IllegalArgumentException("A texture scale must be a positive number");
        }
        return new BspSurfaceMapping(base, resize(u, 1f / scaleU), resize(v, 1f / scaleV));
    }

    public BspSurfaceMapping alignFloor(Vector3 normal) {
        var n = normalized(normal);
        if (n == null || Math.abs(n.z()) < 0.05f) {
            return null;
        }
        return new BspSurfaceMapping(
                base, inPlane(new Vector3(length(u), 0, 0), n), inPlane(new Vector3(0, length(v), 0), n));
    }

    public BspSurfaceMapping alignWall(Vector3 normal) {
        var n = normalized(normal);
        if (n == null || Math.abs(n.z()) > 0.95f) {
            return null;
        }
        var right = normalized(new Vector3(-n.y(), n.x(), 0));
        var down = normalized(inPlane(new Vector3(0, 0, -1), n));
        if (right == null || down == null) {
            return null;
        }
        return new BspSurfaceMapping(base, resize(right, length(u)), resize(down, length(v)));
    }

    public String restriction() {
        for (var axis : new Vector3[] {u, v}) {
            var length = length(axis);
            if (!(length >= MIN_AXIS) || length > MAX_AXIS) {
                return "The texture would be too large or too small on this surface";
            }
        }
        var cross = cross(u, v);
        if (length(cross) <= 1e-6f * length(u) * length(v)) {
            return "The U and V directions of the texture would be the same";
        }
        for (var value : new float[] {base.x(), base.y(), base.z()}) {
            if (Math.abs(value) > 1e9f) {
                return "The texture would move too far";
            }
        }
        return "";
    }

    private static Vector3 positiveZeros(Vector3 value) {
        return new Vector3(value.x() + 0f, value.y() + 0f, value.z() + 0f);
    }

    private static Vector3 combine(Vector3 a, float ka, Vector3 b, float kb) {
        return new Vector3(a.x() * ka + b.x() * kb, a.y() * ka + b.y() * kb, a.z() * ka + b.z() * kb);
    }

    private static Vector3 inPlane(Vector3 axis, Vector3 n) {
        var d = axis.x() * n.x() + axis.y() * n.y() + axis.z() * n.z();
        return new Vector3(axis.x() - d * n.x(), axis.y() - d * n.y(), axis.z() - d * n.z());
    }

    private static Vector3 resize(Vector3 axis, float length) {
        var current = length(axis);
        return new Vector3(axis.x() / current * length, axis.y() / current * length, axis.z() / current * length);
    }

    private static Vector3 cross(Vector3 a, Vector3 b) {
        return new Vector3(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
    }

    private static Vector3 normalized(Vector3 axis) {
        var length = length(axis);
        return length < 1e-6f ? null : new Vector3(axis.x() / length, axis.y() / length, axis.z() / length);
    }

    private static float lengthSquared(Vector3 axis) {
        return axis.x() * axis.x() + axis.y() * axis.y() + axis.z() * axis.z();
    }
}
