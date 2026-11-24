package com.rost.cruma.core.geometry;

import glm_.mat4x4.Mat4;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.util.List;

public class Box {
    private final Vec3 min;
    private final Vec3 max;
    private float minX;
    private float minY;
    private float minZ;
    private float maxX;
    private float maxY;
    private float maxZ;

    private boolean vectorsDirty;

    private boolean isValid;

    public Box() {
        this.min = new Vec3();
        this.max = new Vec3();
        this.isValid = false;
    }

    public Box(Vec3 min, Vec3 max) {
        this.min = min;
        this.max = max;
        this.minX = min.getX();
        this.minY = min.getY();
        this.minZ = min.getZ();
        this.maxX = max.getX();
        this.maxY = max.getY();
        this.maxZ = max.getZ();
        this.isValid = true;
    }

    public Box(List<Vec3> vertices, Mat4 modelMatrix) {
        this();
        if (vertices.isEmpty()) {
            throw new IllegalArgumentException("Vertices list is empty");
        }
        for (var vertex : vertices) {
            add(modelMatrix.times(new Vec4(vertex, 1.0f)).toVec3());
        }
    }

    public Box(List<Vec3> vertices) {
        this(vertices, new Mat4(1.0f));
    }

    public Box(Vec3 min, Vec3 max, Mat4 modelMatrix) {
        this(
                List.of(
                        new Vec3(min.getX(), min.getY(), min.getZ()),
                        new Vec3(min.getX(), max.getY(), min.getZ()),
                        new Vec3(max.getX(), min.getY(), min.getZ()),
                        new Vec3(max.getX(), max.getY(), min.getZ()),
                        new Vec3(min.getX(), min.getY(), max.getZ()),
                        new Vec3(min.getX(), max.getY(), max.getZ()),
                        new Vec3(max.getX(), min.getY(), max.getZ()),
                        new Vec3(max.getX(), max.getY(), max.getZ())),
                new Mat4(modelMatrix));
    }

    public Box(Box box, Mat4 modelMatrix) {
        this(new Vec3(box.minX, box.minY, box.minZ), new Vec3(box.maxX, box.maxY, box.maxZ), new Mat4(modelMatrix));
    }

    public Box(Box box, float[] modelMatrix) {
        this();
        if (modelMatrix.length < 16) {
            throw new IllegalArgumentException("A 4x4 model matrix needs 16 values");
        }
        for (int corner = 0; corner < 8; corner++) {
            var x = (corner & 1) == 0 ? box.minX : box.maxX;
            var y = (corner & 2) == 0 ? box.minY : box.maxY;
            var z = (corner & 4) == 0 ? box.minZ : box.maxZ;
            var worldX = modelMatrix[0] * x + modelMatrix[4] * y + modelMatrix[8] * z + modelMatrix[12];
            var worldY = modelMatrix[1] * x + modelMatrix[5] * y + modelMatrix[9] * z + modelMatrix[13];
            var worldZ = modelMatrix[2] * x + modelMatrix[6] * y + modelMatrix[10] * z + modelMatrix[14];
            add(worldX, worldY, worldZ);
        }
    }

    public boolean isZero() {
        return Math.abs(maxX - minX) <= 0.0001f && Math.abs(maxY - minY) <= 0.0001f && Math.abs(maxZ - minZ) <= 0.0001f;
    }

    public void clear() {
        isValid = false;
    }

    public boolean isValid() {
        return isValid;
    }

    public float getMinX() {
        return minX;
    }

    public float getMinY() {
        return minY;
    }

    public float getMinZ() {
        return minZ;
    }

    public float getMaxX() {
        return maxX;
    }

    public float getMaxY() {
        return maxY;
    }

    public float getMaxZ() {
        return maxZ;
    }

    public Vec3 getMin() {
        synchronizeVectors();
        return min;
    }

    public Vec3 getMax() {
        synchronizeVectors();
        return max;
    }

    public Box add(Vec3 point) {
        return add(point.getX(), point.getY(), point.getZ());
    }

    public Box add(float x, float y, float z) {
        if (!isValid) {
            minX = x;
            minY = y;
            minZ = z;
            maxX = x;
            maxY = y;
            maxZ = z;
            isValid = true;
        } else {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        vectorsDirty = true;
        return this;
    }

    public boolean contains(Vec3 point) {
        return point.getX() > minX
                && point.getY() > minY
                && point.getZ() > minZ
                && point.getZ() < maxZ
                && point.getX() < maxX
                && point.getY() < maxY;
    }

    public boolean intersects(Box box) {
        return box.maxX > minX
                && box.minX < maxX
                && box.maxY > minY
                && box.minY < maxY
                && box.maxZ > minZ
                && box.minZ < maxZ;
    }

    private void synchronizeVectors() {
        if (!vectorsDirty) {
            return;
        }
        min.set_x(minX);
        min.set_y(minY);
        min.set_z(minZ);
        max.set_x(maxX);
        max.set_y(maxY);
        max.set_z(maxZ);
        vectorsDirty = false;
    }
}
