package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.ActorTransform;
import com.rost.cruma.core.editing.ActorTransform.Rotator;
import com.rost.cruma.core.editing.ActorTransform.Vector3;

public final class EditorTransforms {
    private EditorTransforms() {}

    public static float[] identity() {
        return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    public static float[] modelMatrix(ActorTransform value) {
        var rotation = value.rotation();
        var x = -(short) rotation.roll() * Math.PI / 32768;
        var y = -(short) rotation.pitch() * Math.PI / 32768;
        var z = (short) rotation.yaw() * Math.PI / 32768;
        var cx = Math.cos(x);
        var sx = Math.sin(x);
        var cy = Math.cos(y);
        var sy = Math.sin(y);
        var cz = Math.cos(z);
        var sz = Math.sin(z);
        var scale = value.drawScale3d();
        var a = value.drawScale() * scale.x();
        var b = value.drawScale() * scale.y();
        var c = value.drawScale() * scale.z();
        var result = identity();
        result[0] = (float) (cz * cy * a);
        result[1] = (float) (sz * cy * a);
        result[2] = (float) (-sy * a);
        result[4] = (float) ((cz * sy * sx - sz * cx) * b);
        result[5] = (float) ((sz * sy * sx + cz * cx) * b);
        result[6] = (float) (cy * sx * b);
        result[8] = (float) ((cz * sy * cx + sz * sx) * c);
        result[9] = (float) ((sz * sy * cx - cz * sx) * c);
        result[10] = (float) (cy * cx * c);
        var p = value.prePivot();
        result[12] = value.location().x() - result[0] * p.x() - result[4] * p.y() - result[8] * p.z();
        result[13] = value.location().y() - result[1] * p.x() - result[5] * p.y() - result[9] * p.z();
        result[14] = value.location().z() - result[2] * p.x() - result[6] * p.y() - result[10] * p.z();
        return result;
    }

    public static float[] multiply(float[] a, float[] b) {
        var result = new float[16];
        for (var col = 0; col < 4; col++) {
            for (var row = 0; row < 4; row++) {
                for (var k = 0; k < 4; k++) {
                    result[col * 4 + row] += a[k * 4 + row] * b[col * 4 + k];
                }
            }
        }
        return result;
    }

    public static float[] inverse(float[] m) {
        var a = m[0];
        var b = m[4];
        var c = m[8];
        var d = m[1];
        var e = m[5];
        var f = m[9];
        var g = m[2];
        var h = m[6];
        var i = m[10];
        var det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (!Float.isFinite(det) || Math.abs(det) < 1e-12f) {
            throw new IllegalArgumentException("Zero scale cannot be edited with the gizmo.");
        }
        var r = identity();
        r[0] = (e * i - f * h) / det;
        r[4] = (c * h - b * i) / det;
        r[8] = (b * f - c * e) / det;
        r[1] = (f * g - d * i) / det;
        r[5] = (a * i - c * g) / det;
        r[9] = (c * d - a * f) / det;
        r[2] = (d * h - e * g) / det;
        r[6] = (b * g - a * h) / det;
        r[10] = (a * e - b * d) / det;
        for (var row = 0; row < 3; row++) {
            r[12 + row] = -r[row] * m[12] - r[4 + row] * m[13] - r[8 + row] * m[14];
        }
        return r;
    }

    public static float[] delta(ActorTransform original, ActorTransform edited) {
        return multiply(modelMatrix(edited), inverse(modelMatrix(original)));
    }

    public static void adaptCameraForGizmo(float[] view, float[] projection) {
        for (var col = 0; col < 4; col++) {
            view[col * 4 + 2] = -view[col * 4 + 2];
        }
        for (var row = 0; row < 4; row++) {
            projection[8 + row] = -projection[8 + row];
        }
        for (var col = 0; col < 4; col++) {
            projection[col * 4 + 2] = 2 * projection[col * 4 + 2] - projection[col * 4 + 3];
        }
    }

    public static Rotator editedRotation(float pitchDegrees, float yawDegrees, float rollDegrees, Rotator original) {
        return new Rotator(
                editedUnits(pitchDegrees, original.pitch()),
                editedUnits(yawDegrees, original.yaw()),
                editedUnits(rollDegrees, original.roll()));
    }

    public static ActorTransform fromModel(float[] m, ActorTransform reference) {
        var uniform = reference.drawScale();
        if (Math.abs(uniform) < 1e-8f) {
            throw new IllegalArgumentException("DrawScale must be nonzero.");
        }
        var old = reference.drawScale3d();
        var scales = new float[] {
            length(m, 0) * Math.signum(uniform * old.x()),
            length(m, 4) * Math.signum(uniform * old.y()),
            length(m, 8) * Math.signum(uniform * old.z())
        };
        var r = m.clone();
        for (var col = 0; col < 3; col++) {
            if (Math.abs(scales[col]) < 1e-6f) {
                throw new IllegalArgumentException("Scale must stay above zero.");
            }
            for (var row = 0; row < 3; row++) {
                r[col * 4 + row] /= scales[col];
            }
        }
        if (Math.abs(dot(r, 0, 4)) > .001f || Math.abs(dot(r, 0, 8)) > .001f || Math.abs(dot(r, 4, 8)) > .001f) {
            throw new IllegalArgumentException("This operation creates shear. Use uniform group scaling.");
        }
        var determinant = r[0] * (r[5] * r[10] - r[9] * r[6])
                - r[4] * (r[1] * r[10] - r[9] * r[2])
                + r[8] * (r[1] * r[6] - r[5] * r[2]);
        if (determinant < 0) {
            throw new IllegalArgumentException(
                    "Gizmo scaling cannot mirror an actor. Enter a negative numeric scale instead.");
        }
        var y = Math.asin(Math.clamp(-r[2], -1f, 1f));
        var x = Math.abs(Math.cos(y)) > 1e-5 ? Math.atan2(r[6], r[10]) : 0;
        var z = Math.abs(Math.cos(y)) > 1e-5 ? Math.atan2(r[1], r[0]) : Math.atan2(-r[4], r[5]);
        var p = reference.prePivot();
        return new ActorTransform(
                new Vector3(
                        m[12] + m[0] * p.x() + m[4] * p.y() + m[8] * p.z(),
                        m[13] + m[1] * p.x() + m[5] * p.y() + m[9] * p.z(),
                        m[14] + m[2] * p.x() + m[6] * p.y() + m[10] * p.z()),
                new Rotator(toRotationUnits(-y), toRotationUnits(z), toRotationUnits(-x)),
                uniform,
                new Vector3(scales[0] / uniform, scales[1] / uniform, scales[2] / uniform),
                p);
    }

    public static ActorTransform facing(ActorTransform value, float x, float y) {
        var at = value.location();
        var dx = (double) x - at.x();
        var dy = (double) y - at.y();
        if (Math.hypot(dx, dy) < 1e-3) {
            return value;
        }
        var rotation = value.rotation();
        var yaw = (int) Math.round(Math.atan2(dy, dx) * (32768 / Math.PI));
        if ((short) yaw == (short) rotation.yaw()) {
            return value;
        }
        return new ActorTransform(
                at,
                new Rotator(rotation.pitch(), yaw, rotation.roll()),
                value.drawScale(),
                value.drawScale3d(),
                value.prePivot());
    }

    public static ActorTransform alignedTo(ActorTransform value, float nx, float ny, float nz) {
        var length = Math.sqrt((double) nx * nx + (double) ny * ny + (double) nz * nz);
        if (!(length > 1e-6)) {
            throw new IllegalArgumentException("The surface has no direction to follow.");
        }
        double x = nx / length, y = ny / length, z = nz / length;
        var level = new ActorTransform(
                value.location(),
                new Rotator(0, value.rotation().yaw(), 0),
                value.drawScale(),
                value.drawScale3d(),
                value.prePivot());
        var turn = identity();
        var sin = Math.sqrt(x * x + y * y);
        if (sin > 1e-9) {
            var kx = -y / sin;
            var ky = x / sin;
            var cos = z;
            var rest = 1 - cos;
            turn[0] = (float) (cos + rest * kx * kx);
            turn[1] = (float) (rest * kx * ky);
            turn[2] = (float) (-sin * ky);
            turn[4] = (float) (rest * kx * ky);
            turn[5] = (float) (cos + rest * ky * ky);
            turn[6] = (float) (sin * kx);
            turn[8] = (float) (sin * ky);
            turn[9] = (float) (-sin * kx);
            turn[10] = (float) cos;
        } else if (z < 0) {
            turn[5] = -1;
            turn[10] = -1;
        }
        var p = value.location();
        turn[12] = p.x() - turn[0] * p.x() - turn[4] * p.y() - turn[8] * p.z();
        turn[13] = p.y() - turn[1] * p.x() - turn[5] * p.y() - turn[9] * p.z();
        turn[14] = p.z() - turn[2] * p.x() - turn[6] * p.y() - turn[10] * p.z();
        return fromModel(multiply(turn, modelMatrix(level)), level);
    }

    private static int editedUnits(float degrees, int original) {
        if (!Float.isFinite(degrees)) {
            throw new IllegalArgumentException("Rotation must be finite.");
        }
        return degrees == (short) original * 360f / 65536 ? original : (int) Math.round((double) degrees / 360 * 65536);
    }

    private static float length(float[] m, int offset) {
        return (float) Math.sqrt(dot(m, offset, offset));
    }

    private static float dot(float[] m, int a, int b) {
        return m[a] * m[b] + m[a + 1] * m[b + 1] + m[a + 2] * m[b + 2];
    }

    private static int toRotationUnits(double radians) {
        return (int) Math.round(radians * 32768 / Math.PI);
    }
}
