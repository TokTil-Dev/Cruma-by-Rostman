package com.rost.cruma.viewer.render;

import glm_.vec3.Vec3;

public enum CameraView {
    PERSPECTIVE("Free", null, null, -1, 0f),
    TOP("Top", new Vec3(0f, 0f, -1f), new Vec3(0f, -1f, 0f), 2, 100_000f),
    FRONT("Front", new Vec3(0f, -1f, 0f), new Vec3(0f, 0f, 1f), 1, 500_000f),
    SIDE("Side", new Vec3(1f, 0f, 0f), new Vec3(0f, 0f, 1f), 0, -500_000f);

    private final String label;
    private final Vec3 look;
    private final Vec3 up;
    private final int depthAxis;
    private final float eyeOffset;

    CameraView(String label, Vec3 look, Vec3 up, int depthAxis, float eyeOffset) {
        this.label = label;
        this.look = look;
        this.up = up;
        this.depthAxis = depthAxis;
        this.eyeOffset = eyeOffset;
    }

    public String getLabel() {
        return label;
    }

    public boolean isOrtho() {
        return this != PERSPECTIVE;
    }

    public boolean isHorizontal() {
        return this == FRONT || this == SIDE;
    }

    public boolean isLit() {
        return !isHorizontal();
    }

    public Vec3 getLook() {
        return new Vec3(look);
    }

    public Vec3 getUp() {
        return new Vec3(up);
    }

    public int getDepthAxis() {
        return depthAxis;
    }

    public int getDepthDirection() {
        return (depthAxis == 0 ? look.getX() : depthAxis == 1 ? look.getY() : look.getZ()) > 0 ? 1 : -1;
    }

    public int getUpAxis() {
        return up.getZ() != 0 ? 2 : 1;
    }

    public int getRightAxis() {
        return 3 - depthAxis - getUpAxis();
    }

    public float getEyeOffset() {
        return eyeOffset;
    }
}
