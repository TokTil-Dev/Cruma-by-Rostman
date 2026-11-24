package com.rost.cruma.viewer.render;

import com.rost.cruma.core.unreal.EffectClasses.Quake;

final class SceneShake {
    private static final float TURN = 0x10000;
    private static final float DEGREES_PER_TURN = 360;
    private static final float SPEED_PER_INTENSITY = 50;
    private static final float FIRST_SWINGS = 2;

    private float delay;
    private float left;
    private float swing;
    private float speed;
    private float swings;
    private float allSwings;
    private float angle;
    private float yawShare;
    private float pitchShare;

    void start(Quake quake, double distance) {
        delay = quake.delay();
        left = quake.seconds();
        var fade = quake.range() > 0 ? (float) (1 / Math.cosh(distance / quake.range())) : 1;
        swing = quake.intensity() * fade;
        speed = quake.intensity() * SPEED_PER_INTENSITY;
        swings = quake.count();
        allSwings = quake.count() + FIRST_SWINGS;
        angle = 0;
        var length = (float) Math.hypot(quake.x(), quake.y());
        yawShare = length == 0 ? 0 : Math.abs(quake.x()) / length;
        pitchShare = length == 0 ? 1 : Math.abs(quake.y()) / length;
    }

    void stop() {
        left = 0;
        delay = 0;
        angle = 0;
    }

    void advance(double seconds) {
        if (left <= 0) {
            return;
        }
        if (delay > 0) {
            delay -= (float) seconds;
            return;
        }
        left -= (float) seconds;
        if (left <= 0) {
            angle = 0;
            return;
        }
        angle += speed * (float) seconds;
        swingBack();
    }

    float getYawDegrees() {
        return angle * yawShare * DEGREES_PER_TURN / TURN;
    }

    float getPitchDegrees() {
        return angle * pitchShare * DEGREES_PER_TURN / TURN;
    }

    private void swingBack() {
        var reached = swing <= 0 ? angle <= swing : angle >= swing;
        if (!reached) {
            return;
        }
        angle = swing;
        if (swings <= 1) {
            left = 0;
            angle = 0;
            return;
        }
        swing = swing <= 0 ? -swing : (1 / (allSwings - swings) - 1) * swing;
        swings--;
        speed = -speed;
    }
}
