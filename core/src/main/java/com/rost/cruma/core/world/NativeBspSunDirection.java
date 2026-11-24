package com.rost.cruma.core.world;

import com.rost.cruma.core.editing.NativeTerrainTimeLighting;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.NativeBspShadowProjection.Vector;
import glm_.vec3.Vec3;

public final class NativeBspSunDirection {
    private NativeBspSunDirection() {}

    public record Result(float time, Vector nativeDirection, int pitch, int yaw, Vector rotated, Vector towardLight) {}

    public static Result directionForSlot(int slot, int count, float longitude, float slope, boolean reverseSlope) {
        if (count < 1 || count > 256 || slot < 0 || slot >= count) {
            throw new IllegalArgumentException("Invalid native BSP sunlight slot");
        }
        var time = ((float) slot * 24f) / (float) count + (24f / (float) count) * .5f;
        var input = NativeTerrainTimeLighting.safeDirection(time, longitude, slope, reverseSlope);
        return roundTrip(time, new Vector(input.x(), input.y(), input.z()));
    }

    public static Vector fromRotation(int pitch, int yaw) {
        return normalizeNegate(toDirection(pitch, yaw));
    }

    public static Vector rawTowardFromRotation(int pitch, int yaw) {
        var raw = toDirection(pitch, yaw);
        return new Vector(-raw.x(), -raw.y(), -raw.z());
    }

    private static Result roundTrip(float time, Vector input) {
        var yawAngle = Math.atan2((double) input.y(), (double) input.x());
        var horizontalSquare = input.x() * input.x() + input.y() * input.y();
        var pitchAngle = Math.atan2((double) input.z(), Math.sqrt((double) horizontalSquare));
        var yaw = (int) ((yawAngle * 65535.0) / (2.0 * Math.PI));
        var pitch = (int) ((pitchAngle * 65535.0) / (2.0 * Math.PI));
        var rotated = toDirection(pitch, yaw);
        return new Result(time, input, pitch, yaw, rotated, normalizeNegate(rotated));
    }

    private static Vector toDirection(int pitch, int yaw) {
        var matrix = BrushTransforms.actorMatrix(new Vec3(), new Vec3(), new URotator(pitch, yaw, 0), new Vec3(1f));
        return new Vector(matrix.get(0, 0), matrix.get(0, 1), matrix.get(0, 2));
    }

    private static Vector normalizeNegate(Vector rotated) {
        var x = rotated.x();
        var y = rotated.y();
        var z = rotated.z();
        var square = (x * x + y * y) + z * z;
        if (!Float.isFinite(square) || square < 1.0e-8) {
            throw new IllegalArgumentException("Invalid native BSP sunlight rotation");
        }
        var reciprocal = 1f / (float) Math.sqrt((double) square);
        return new Vector(-(x * reciprocal), -(y * reciprocal), -(z * reciprocal));
    }
}
