package com.rost.cruma.viewer.spawn;

import java.nio.FloatBuffer;

public final class SpawnProjector {
    private final float[] matrix = new float[16];
    private float width;
    private float height;
    private float focal = 1;

    public void update(FloatBuffer view, FloatBuffer projection, float width, float height) {
        var v = new float[16];
        var p = new float[16];
        view.duplicate().position(0).get(v, 0, 16);
        projection.duplicate().position(0).get(p, 0, 16);
        for (var column = 0; column < 4; column++) {
            for (var row = 0; row < 4; row++) {
                var sum = 0f;
                for (var k = 0; k < 4; k++) {
                    sum += p[k * 4 + row] * v[column * 4 + k];
                }
                matrix[column * 4 + row] = sum;
            }
        }
        this.width = width;
        this.height = height;
        this.focal = Math.max(1e-3f, p[5] * height * .5f);
    }

    public double getPointsTall(double length, double distance) {
        return length * focal / Math.max(distance, 1);
    }

    public double getDistanceFor(double length, double points) {
        return length * focal / points;
    }

    public boolean project(double x, double y, double z, float[] out) {
        var clipX = matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12];
        var clipY = matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13];
        var clipW = matrix[3] * x + matrix[7] * y + matrix[11] * z + matrix[15];
        if (clipW <= 1e-3) {
            return false;
        }
        out[0] = (float) ((clipX / clipW * .5 + .5) * width);
        out[1] = (float) ((1 - (clipY / clipW * .5 + .5)) * height);
        return true;
    }
}
