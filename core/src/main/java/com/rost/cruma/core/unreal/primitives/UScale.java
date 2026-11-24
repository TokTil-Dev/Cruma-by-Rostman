package com.rost.cruma.core.unreal.primitives;

import com.rost.cruma.core.unreal.UArchive;
import glm_.vec3.Vec3;

public record UScale(Vec3 scale, float sheerRate, short sheerAxis) {
    public static UScale of(UArchive archive) {
        return new UScale(archive.readVector(), archive.readFloat(), archive.readUInt8());
    }
}
