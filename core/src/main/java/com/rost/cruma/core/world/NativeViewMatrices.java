package com.rost.cruma.core.world;

import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.util.List;

public final class NativeViewMatrices {
    private final float[] worldToCamera;
    private final float[] cameraToScreen;
    private final float[] cameraToWorld;
    private final float[] screenToCamera;
    private final float[] worldToScreen = new float[16];
    private final float[] screenToWorld = new float[16];

    public NativeViewMatrices(float[] worldToCamera, float[] cameraToScreen) {
        if (worldToCamera.length != 16 || cameraToScreen.length != 16) {
            throw new IllegalArgumentException("native view matrices require 16 coefficients");
        }
        this.worldToCamera = worldToCamera.clone();
        this.cameraToScreen = cameraToScreen.clone();
        cameraToWorld = NativeMatrixMath.inverse(worldToCamera);
        screenToCamera = NativeMatrixMath.inverse(cameraToScreen);
        NativeMatrixMath.multiply(worldToCamera, cameraToScreen, worldToScreen);
        NativeMatrixMath.multiply(screenToCamera, cameraToWorld, screenToWorld);
    }

    private NativeViewMatrices(NativeViewMatrices parent, NativeWaterReflection mirror) {
        worldToCamera = new float[16];
        cameraToWorld = new float[16];
        cameraToScreen = parent.cameraToScreen;
        screenToCamera = parent.screenToCamera;
        NativeMatrixMath.multiply(mirror.getMatrix(), parent.worldToCamera, worldToCamera);
        NativeMatrixMath.multiply(parent.cameraToWorld, mirror.getInverseMatrix(), cameraToWorld);
        NativeMatrixMath.multiply(worldToCamera, cameraToScreen, worldToScreen);
        NativeMatrixMath.multiply(screenToCamera, cameraToWorld, screenToWorld);
    }

    public NativeViewMatrices reflected(NativeWaterReflection mirror) {
        return new NativeViewMatrices(this, mirror);
    }

    public float[] getWorldToCamera() {
        return worldToCamera.clone();
    }

    public float[] getCameraToScreen() {
        return cameraToScreen.clone();
    }

    public float[] getCameraToWorld() {
        return cameraToWorld.clone();
    }

    public float[] getScreenToCamera() {
        return screenToCamera.clone();
    }

    public float[] getWorldToScreen() {
        return worldToScreen.clone();
    }

    public float[] getScreenToWorld() {
        return screenToWorld.clone();
    }

    public List<Vec4> getFrustum(int renderMode, float near, Vec3 origin) {
        return NativeViewFrustum.planes(renderMode, near, worldToScreen, screenToWorld, origin);
    }
}
