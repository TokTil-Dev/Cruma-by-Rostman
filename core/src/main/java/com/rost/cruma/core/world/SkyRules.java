package com.rost.cruma.core.world;

import com.rost.cruma.core.env.ResolvedEnv;
import com.rost.cruma.core.world.Entity.BlendMode;
import com.rost.cruma.core.world.Entity.ColorModifierEntityMaterial;
import com.rost.cruma.core.world.Entity.EntityMaterial;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import org.jetbrains.annotations.Nullable;

public final class SkyRules {
    private SkyRules() {}

    public static Vec3 tint(
            String name, int cloudIndex, Vec3 skyBox, Vec3 haze, @Nullable ResolvedEnv env, Vec3 authored) {
        switch (name) {
            case "SkybackgroundColor" -> {
                return skyBox;
            }
            case "HazeRing_Final" -> {
                return haze;
            }
            default -> {
                var cloud = cloudIndex < 0 || env == null ? null : env.cloud(cloudIndex);
                return cloud != null ? cloud : authored;
            }
        }
    }

    public static Vec3 authoredTint(@Nullable EntityMaterial material) {
        var result = new Vec3(1f);
        while (material instanceof ColorModifierEntityMaterial(EntityMaterial base, Vec4 tint)) {
            result = new Vec3(result.getX() * tint.getX(), result.getY() * tint.getY(), result.getZ() * tint.getZ());
            material = base;
        }
        return result;
    }

    public static boolean usesAlpha(BlendMode blend) {
        return blend == BlendMode.TRANSLUCENT || blend == BlendMode.EMISSIVE || blend == BlendMode.BRIGHTEN;
    }

    public static float alphaCutoff(BlendMode blend, int alphaRef) {
        if (alphaRef >= 0) {
            return Math.min(alphaRef, 255) / 255f;
        }
        return blend == BlendMode.MASKED || blend == BlendMode.MASKED_BLEND ? 0.25f : -1f;
    }

    public static @Nullable Vec3 celestialOffset(float hour, boolean sun, float radius) {
        double latitude;
        if (sun) {
            var time = hour < 6f ? hour + 24.0 : hour;
            latitude = (time - 6.0) * Math.toRadians(10.0) - Math.PI / 2.0;
        } else {
            if (hour >= 7f && hour < 23f) {
                return null;
            }
            var time = hour >= 23f ? hour - 24.0 : hour;
            latitude = time * (Math.PI / 6.0) - Math.PI / 2.0;
        }
        var x = -radius * Math.sin(latitude);
        var z = radius * Math.cos(latitude);
        var tilt = Math.toRadians(30.0);
        return new Vec3((float) x, (float) (-z * Math.sin(tilt)), (float) (z * Math.cos(tilt)));
    }
}
