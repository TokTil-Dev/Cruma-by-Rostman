package com.rost.cruma.core.world;

import glm_.vec3.Vec3;

public final class StaticMeshLightPriority {
    private StaticMeshLightPriority() {}

    public static int score(
            Vec3 position,
            Vec3 direction,
            float radius,
            float brightness,
            int effect,
            int cone,
            Vec3 sphereCenter,
            float sphereRadius,
            boolean programmable) {
        if (effect == 19) {
            return Integer.MAX_VALUE - 1;
        }
        var dx = sphereCenter.getX() - position.getX();
        var dy = sphereCenter.getY() - position.getY();
        var dz = sphereCenter.getZ() - position.getZ();
        if (effect == 8 || effect == 12) {
            var lengthSquared = dx * dx + dy * dy + dz * dz;
            if (lengthSquared < 1e-8) {
                return 0;
            }
            var inverseLength = 1f / (float) Math.sqrt(lengthSquared);
            var cosine = direction.getY() * (dy * inverseLength)
                    + direction.getX() * (dx * inverseLength)
                    + direction.getZ() * (dz * inverseLength);
            var cutoff = 1f - (cone & 255) * (1f / 256f);
            if (!(cosine > 0f) || !(cosine > cutoff * cutoff)) {
                return 0;
            }
        }
        var reach = radius + sphereRadius;
        var distanceSquared = dy * dy + dx * dx + dz * dz;
        var operand = (1f - distanceSquared / (reach * reach)) * brightness * 1024f;
        if (!Float.isFinite(operand)) {
            throw new IllegalArgumentException("Light priority requires a finite rounding operand");
        }
        var rounded = Math.floor((double) operand + .5);
        var priority = rounded < Integer.MIN_VALUE || rounded > Integer.MAX_VALUE ? Integer.MIN_VALUE : (int) rounded;
        return programmable && priority != 0 ? priority + 1 : priority;
    }
}
