package com.rost.cruma.core.geometry;

import glm_.vec3.Vec3;
import java.util.List;

public class Sphere {
    private final Vec3 center;
    private final float radius;

    public Sphere(Vec3 center, float radius) {
        this.center = center;
        this.radius = radius;
    }

    public boolean intersects(Triangle triangle, Intersection intersection) {
        var closestPoint = triangle.getClosestPointTo(center);
        var vector = center.minus(closestPoint);
        var length = vector.length();

        intersection.normal = vector.normalize();
        intersection.depth = radius - length;

        return length <= radius;
    }

    public boolean intersects(List<Triangle> triangles) {
        var intersection = new Intersection();
        for (var triangle : triangles) {
            if (intersects(triangle, intersection)) {
                return true;
            }
        }
        return false;
    }
}
