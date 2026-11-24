package com.rost.cruma.core.unreal.primitives;

import com.rost.cruma.core.unreal.UArchive;
import glm_.vec3.Vec3;

public record URotator(int pitch, int yaw, int roll) {
    private static final float RADIANS_PER_UNIT = (float) Math.PI / 32768.0f;

    public static float toRadians(int units) {
        return (short) units * RADIANS_PER_UNIT;
    }

    public static int toUnits(float radians) {
        return Math.round(radians / RADIANS_PER_UNIT);
    }

    public static URotator of(UArchive archive) {
        return new URotator(archive.readInt(), archive.readInt(), archive.readInt());
    }

    public static URotator ofVector(Vec3 vector) {
        return new URotator(toUnits(-vector.getY()), toUnits(vector.getZ()), toUnits(-vector.getX()));
    }

    public Vec3 vector() {
        return new Vec3(-toRadians(roll), -toRadians(pitch), toRadians(yaw));
    }
}
