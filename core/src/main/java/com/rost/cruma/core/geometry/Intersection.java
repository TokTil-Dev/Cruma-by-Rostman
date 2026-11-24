package com.rost.cruma.core.geometry;

import glm_.vec3.Vec3;

public class Intersection {
    public Vec3 normal;
    public float depth;

    public Intersection() {
        this.normal = new Vec3();
        this.depth = 0.0f;
    }

    public Intersection(Vec3 normal, float depth) {
        this.normal = normal;
        this.depth = depth;
    }
}
