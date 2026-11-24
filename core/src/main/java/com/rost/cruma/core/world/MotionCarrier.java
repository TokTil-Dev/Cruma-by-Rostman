package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.primitives.URotator;
import glm_.vec3.Vec3;

public interface MotionCarrier {
    Vec3 follow(Vec3 start);

    URotator follow(URotator start);
}
