package com.rost.cruma.core.geometry;

import glm_.mat4x4.Mat4;
import glm_.vec4.Vec4;

public class Frustum {
    public static final int OUTSIDE = -1;
    public static final int INTERSECTING = 0;
    public static final int INSIDE = 1;

    private final Vec4[] planes = new Vec4[6];
    private final float[] planeX = new float[6];
    private final float[] planeY = new float[6];
    private final float[] planeZ = new float[6];
    private final float[] planeW = new float[6];
    private final float[] planeNormalLength = new float[6];

    public Frustum(Mat4 projectionMatrix, Mat4 viewMatrix) {
        var transposed = projectionMatrix.times(viewMatrix).transpose();

        planes[0] = transposed.get(3).plus(transposed.get(0));
        planes[1] = transposed.get(3).minus(transposed.get(0));
        planes[2] = transposed.get(3).plus(transposed.get(1));
        planes[3] = transposed.get(3).minus(transposed.get(1));
        planes[4] = transposed.get(3).plus(transposed.get(2));
        planes[5] = transposed.get(3).minus(transposed.get(2));
        for (var i = 0; i < planes.length; i++) {
            var plane = planes[i];
            var x = plane.getX();
            var y = plane.getY();
            var z = plane.getZ();
            planeX[i] = x;
            planeY[i] = y;
            planeZ[i] = z;
            planeW[i] = plane.getW();
            planeNormalLength[i] = (float) Math.sqrt(x * x + y * y + z * z);
        }
    }

    public Vec4 getNearPlane() {
        return planes[4];
    }

    public boolean intersects(Box box) {
        return intersects(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
    }

    public boolean intersects(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return intersectsPlane(0, minX, minY, minZ, maxX, maxY, maxZ)
                && intersectsPlane(1, minX, minY, minZ, maxX, maxY, maxZ)
                && intersectsPlane(2, minX, minY, minZ, maxX, maxY, maxZ)
                && intersectsPlane(3, minX, minY, minZ, maxX, maxY, maxZ)
                && intersectsPlane(4, minX, minY, minZ, maxX, maxY, maxZ)
                && intersectsPlane(5, minX, minY, minZ, maxX, maxY, maxZ);
    }

    public int classify(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        var fullyInside = true;
        var classification = classifyPlane(0, minX, minY, minZ, maxX, maxY, maxZ);
        if (classification == OUTSIDE) {
            return OUTSIDE;
        }
        fullyInside = classification == INSIDE;
        classification = classifyPlane(1, minX, minY, minZ, maxX, maxY, maxZ);
        if (classification == OUTSIDE) {
            return OUTSIDE;
        }
        fullyInside &= classification == INSIDE;
        classification = classifyPlane(2, minX, minY, minZ, maxX, maxY, maxZ);
        if (classification == OUTSIDE) {
            return OUTSIDE;
        }
        fullyInside &= classification == INSIDE;
        classification = classifyPlane(3, minX, minY, minZ, maxX, maxY, maxZ);
        if (classification == OUTSIDE) {
            return OUTSIDE;
        }
        fullyInside &= classification == INSIDE;
        classification = classifyPlane(4, minX, minY, minZ, maxX, maxY, maxZ);
        if (classification == OUTSIDE) {
            return OUTSIDE;
        }
        fullyInside &= classification == INSIDE;
        classification = classifyPlane(5, minX, minY, minZ, maxX, maxY, maxZ);
        if (classification == OUTSIDE) {
            return OUTSIDE;
        }
        return fullyInside && classification == INSIDE ? INSIDE : INTERSECTING;
    }

    public boolean intersectsSphere(float x, float y, float z, float radius) {
        var r = Math.abs(radius);
        for (var i = 0; i < planeX.length; i++) {
            var distance = x * planeX[i] + y * planeY[i] + z * planeZ[i] + planeW[i];
            if (distance <= -r * planeNormalLength[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean intersectsPlane(int plane, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        var x = planeX[plane];
        var y = planeY[plane];
        var z = planeZ[plane];
        var distance =
                (x >= 0f ? maxX : minX) * x + (y >= 0f ? maxY : minY) * y + (z >= 0f ? maxZ : minZ) * z + planeW[plane];
        return distance > 0f;
    }

    private int classifyPlane(int plane, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        var x = planeX[plane];
        var y = planeY[plane];
        var z = planeZ[plane];
        var positiveDistance =
                (x >= 0f ? maxX : minX) * x + (y >= 0f ? maxY : minY) * y + (z >= 0f ? maxZ : minZ) * z + planeW[plane];
        if (positiveDistance <= 0f) {
            return OUTSIDE;
        }
        var negativeDistance =
                (x >= 0f ? minX : maxX) * x + (y >= 0f ? minY : maxY) * y + (z >= 0f ? minZ : maxZ) * z + planeW[plane];
        return negativeDistance > 0f ? INSIDE : INTERSECTING;
    }
}
