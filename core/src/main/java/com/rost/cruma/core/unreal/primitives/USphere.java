package com.rost.cruma.core.unreal.primitives;

import com.rost.cruma.core.unreal.UArchive;
import glm_.vec3.Vec3;

public record USphere(Vec3 location, float radius) {
    public static USphere of(UArchive archive) {
        return new USphere(archive.readVector(), archive.readFloat());
    }
}
