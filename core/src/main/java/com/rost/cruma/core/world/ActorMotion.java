package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.primitives.URotator;
import glm_.mat4x4.Mat4;

public interface ActorMotion {
    Mat4 advance(float seconds, Mat4 output);

    URotator getRotation();

    float getLocationX();

    float getLocationY();

    Mat4 writeSavedMatrix(Mat4 output);
}
