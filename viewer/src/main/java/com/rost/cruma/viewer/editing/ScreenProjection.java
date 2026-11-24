package com.rost.cruma.viewer.editing;

import com.rost.cruma.viewer.render.Camera;

final class ScreenProjection {
    private final float[] view = new float[16];
    private final float[] projection = new float[16];
    private final float width;
    private final float height;

    private ScreenProjection(Camera camera, float width, float height) {
        var viewMatrix = camera.getViewMatrix();
        var projectionMatrix = camera.getProjectionMatrix();
        for (var i = 0; i < 16; i++) {
            view[i] = viewMatrix.get(i);
            projection[i] = projectionMatrix.get(i);
        }
        this.width = width;
        this.height = height;
    }

    static ScreenProjection of(Camera camera, float width, float height) {
        return new ScreenProjection(camera, width, height);
    }

    boolean project(float x, float y, float z, float[] out, int at) {
        var vx = view[0] * x + view[4] * y + view[8] * z + view[12];
        var vy = view[1] * x + view[5] * y + view[9] * z + view[13];
        var vz = view[2] * x + view[6] * y + view[10] * z + view[14];
        var clipZ = projection[2] * vx + projection[6] * vy + projection[10] * vz + projection[14];
        var w = projection[3] * vx + projection[7] * vy + projection[11] * vz + projection[15];
        if (w <= 0 || clipZ < 0 || clipZ > w) {
            return false;
        }
        var clipX = projection[0] * vx + projection[4] * vy + projection[8] * vz + projection[12];
        var clipY = projection[1] * vx + projection[5] * vy + projection[9] * vz + projection[13];
        out[at] = (clipX / w + 1) * width / 2;
        out[at + 1] = (1 - clipY / w) * height / 2;
        return true;
    }
}
