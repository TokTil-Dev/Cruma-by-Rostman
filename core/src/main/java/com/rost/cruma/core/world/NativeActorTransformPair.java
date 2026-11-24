package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.primitives.URotator;
import glm_.vec3.Vec3;
import java.util.Objects;

public final class NativeActorTransformPair {
    private static final float[] SINES = sineTable();

    private final Parameters parameters;
    private final float[] localToWorld;
    private final float[] worldToLocal;

    public NativeActorTransformPair(Parameters effective) {
        parameters = Objects.requireNonNull(effective);
        var location = effective.location();
        var pivot = effective.prePivot();
        var rotation = effective.rotation();
        var scale = effective.drawScale3D();
        var sx = scale.x() * effective.drawScale();
        var sy = scale.y() * effective.drawScale();
        var sz = scale.z() * effective.drawScale();
        requireNonzero(sx);
        requireNonzero(sy);
        requireNonzero(sz);
        var forward = BrushTransforms.actorMatrix(
                toVec3(location),
                toVec3(pivot),
                new URotator(rotation.pitch(), rotation.yaw(), rotation.roll()),
                new Vec3(sx, sy, sz));
        localToWorld = new float[16];
        for (var i = 0; i < 16; i++) {
            localToWorld[i] = forward.get(i / 4, i % 4);
        }

        var reciprocalDraw = 1f / effective.drawScale();
        var inverseScale = identity();
        inverseScale[0] = reciprocalDraw * (1f / scale.x());
        inverseScale[5] = reciprocalDraw * (1f / scale.y());
        inverseScale[10] = reciprocalDraw * (1f / scale.z());
        var translated = translationMatrix(-location.x(), -location.y(), -location.z());
        var rotated = multiply(translated, inverseRotation(rotation));
        var scaled = multiply(rotated, inverseScale);
        worldToLocal = multiply(scaled, translationMatrix(pivot.x(), pivot.y(), pivot.z()));
        validate(localToWorld);
        validate(worldToLocal);
    }

    public record Vector(float x, float y, float z) {
        public Vector {
            requireFinite(x);
            requireFinite(y);
            requireFinite(z);
        }
    }

    public record Rotation(int pitch, int yaw, int roll) {}

    public record Parameters(Vector location, Vector prePivot, Rotation rotation, float drawScale, Vector drawScale3D) {
        public Parameters {
            Objects.requireNonNull(location);
            Objects.requireNonNull(prePivot);
            Objects.requireNonNull(rotation);
            Objects.requireNonNull(drawScale3D);
            requireNonzero(drawScale);
            requireNonzero(drawScale3D.x());
            requireNonzero(drawScale3D.y());
            requireNonzero(drawScale3D.z());
        }
    }

    public Parameters getParameters() {
        return parameters;
    }

    public float[] getLocalToWorld() {
        return localToWorld.clone();
    }

    public float[] getWorldToLocal() {
        return worldToLocal.clone();
    }

    private static float[] inverseRotation(Rotation rotation) {
        var yaw = identity();
        var sy = sine(-rotation.yaw());
        var cy = sine(16384 - rotation.yaw());
        yaw[0] = yaw[5] = cy;
        yaw[1] = sy;
        yaw[4] = -sy;
        var pitch = identity();
        var sp = sine(-rotation.pitch());
        var cp = sine(16384 - rotation.pitch());
        pitch[0] = pitch[10] = cp;
        pitch[2] = sp;
        pitch[8] = -sp;
        var roll = identity();
        var sr = sine(-rotation.roll());
        var cr = sine(16384 - rotation.roll());
        roll[5] = roll[10] = cr;
        roll[6] = -sr;
        roll[9] = sr;
        return multiply(multiply(yaw, pitch), roll);
    }

    private static float sine(int angle) {
        return SINES[(angle >> 2) & 0x3fff];
    }

    private static float[] sineTable() {
        var values = new float[16384];
        for (var i = 0; i < values.length; i++) {
            values[i] = (float) StrictMath.sin((i * 2.0) * Math.PI / 16384.0);
        }
        return values;
    }

    private static float[] identity() {
        var matrix = new float[16];
        matrix[0] = matrix[5] = matrix[10] = matrix[15] = 1f;
        return matrix;
    }

    private static float[] translationMatrix(float x, float y, float z) {
        var matrix = identity();
        matrix[12] = x;
        matrix[13] = y;
        matrix[14] = z;
        return matrix;
    }

    private static float[] multiply(float[] left, float[] right) {
        var product = new float[16];
        NativeMatrixMath.multiply(left, right, product);
        return product;
    }

    private static Vec3 toVec3(Vector value) {
        return new Vec3(value.x(), value.y(), value.z());
    }

    private static void validate(float[] matrix) {
        for (var value : matrix) {
            requireFinite(value);
        }
        var determinant = (double) matrix[0] * ((double) matrix[5] * matrix[10] - (double) matrix[9] * matrix[6])
                - (double) matrix[4] * ((double) matrix[1] * matrix[10] - (double) matrix[9] * matrix[2])
                + (double) matrix[8] * ((double) matrix[1] * matrix[6] - (double) matrix[5] * matrix[2]);
        if (!Double.isFinite(determinant) || determinant == 0) {
            throw new IllegalArgumentException("Singular native actor transform is not qualified");
        }
    }

    private static void requireNonzero(float value) {
        requireFinite(value);
        if (value == 0) {
            throw new IllegalArgumentException("Zero native actor scale is not qualified");
        }
    }

    private static void requireFinite(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite native actor transform is not qualified");
        }
    }
}
