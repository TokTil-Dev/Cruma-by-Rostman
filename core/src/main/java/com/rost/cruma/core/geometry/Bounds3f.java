package com.rost.cruma.core.geometry;

public record Bounds3f(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    public Bounds3f {
        if (!areFinite(minX, minY, minZ, maxX, maxY, maxZ) || minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("invalid world bounds");
        }
    }

    public float centerX() {
        return (minX + maxX) * 0.5f;
    }

    public float centerY() {
        return (minY + maxY) * 0.5f;
    }

    public float centerZ() {
        return (minZ + maxZ) * 0.5f;
    }

    private static boolean areFinite(float... values) {
        for (var value : values) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
    }
}
