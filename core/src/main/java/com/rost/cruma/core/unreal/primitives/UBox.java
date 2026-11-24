package com.rost.cruma.core.unreal.primitives;

import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.core.unreal.UArchive;
import glm_.vec3.Vec3;

public record UBox(Vec3 min, Vec3 max, boolean isValid) {
    public static UBox of(UArchive archive) {
        return new UBox(archive.readVector(), archive.readVector(), archive.readBool());
    }

    public Box toBox() {
        return new Box(min, max);
    }
}
