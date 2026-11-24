package com.rost.cruma.viewer.agent.view;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.Camera.Snapshot;
import org.jetbrains.annotations.Nullable;

record Lens(float[] matrix, float x, float y, float z, boolean ortho, Snapshot camera) {
    record Area(double left, double top, double right, double bottom) {
        double width() {
            return right - left;
        }

        double height() {
            return bottom - top;
        }

        double middleX() {
            return (left + right) / 2;
        }

        double middleY() {
            return (top + bottom) / 2;
        }
    }

    static Lens of(Camera camera) {
        var matrix = new float[16];
        camera.copyViewProjection(matrix);
        var eye = camera.getPosition();
        return new Lens(matrix, eye.getX(), eye.getY(), eye.getZ(), camera.isOrtho(), camera.snapshot());
    }

    double @Nullable [] project(double worldX, double worldY, double worldZ) {
        var w = matrix[3] * worldX + matrix[7] * worldY + matrix[11] * worldZ + matrix[15];
        if (w <= 0) {
            return null;
        }
        var clipX = matrix[0] * worldX + matrix[4] * worldY + matrix[8] * worldZ + matrix[12];
        var clipY = matrix[1] * worldX + matrix[5] * worldY + matrix[9] * worldZ + matrix[13];
        return new double[] {clipX / w, clipY / w, w};
    }

    @Nullable
    Area area(Bounds3f box) {
        var left = Double.POSITIVE_INFINITY;
        var right = Double.NEGATIVE_INFINITY;
        var top = Double.NEGATIVE_INFINITY;
        var bottom = Double.POSITIVE_INFINITY;
        var behind = 0;
        for (var corner = 0; corner < 8; corner++) {
            var point = project(
                    (corner & 1) == 0 ? box.minX() : box.maxX(),
                    (corner & 2) == 0 ? box.minY() : box.maxY(),
                    (corner & 4) == 0 ? box.minZ() : box.maxZ());
            if (point == null) {
                behind++;
                continue;
            }
            left = Math.min(left, point[0]);
            right = Math.max(right, point[0]);
            bottom = Math.min(bottom, point[1]);
            top = Math.max(top, point[1]);
        }
        if (behind == 8) {
            return null;
        }
        if (behind > 0) {
            left = -1;
            right = 1;
            bottom = -1;
            top = 1;
        }
        var shownLeft = Math.max(-1, left);
        var shownRight = Math.min(1, right);
        var shownBottom = Math.max(-1, bottom);
        var shownTop = Math.min(1, top);
        if (shownLeft >= shownRight || shownBottom >= shownTop) {
            return null;
        }
        return new Area((shownLeft + 1) / 2, (1 - shownTop) / 2, (shownRight + 1) / 2, (1 - shownBottom) / 2);
    }
}
