package com.rost.cruma.core.editing;

import java.util.Objects;

public record ActorTransform(
        Vector3 location, Rotator rotation, float drawScale, Vector3 drawScale3d, Vector3 prePivot) {
    public static final ActorTransform IDENTITY =
            new ActorTransform(Vector3.ZERO, new Rotator(0, 0, 0), 1f, Vector3.ONE, Vector3.ZERO);

    public ActorTransform {
        Objects.requireNonNull(location);
        Objects.requireNonNull(rotation);
        Objects.requireNonNull(drawScale3d);
        Objects.requireNonNull(prePivot);
        if (!Float.isFinite(drawScale)) {
            throw new IllegalArgumentException("DrawScale must be finite");
        }
    }

    public record Vector3(float x, float y, float z) {
        public static final Vector3 ZERO = new Vector3(0, 0, 0);
        public static final Vector3 ONE = new Vector3(1, 1, 1);

        public Vector3 {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                throw new IllegalArgumentException("Transform coordinates must be finite");
            }
        }
    }

    public record Rotator(int pitch, int yaw, int roll) {}
}
