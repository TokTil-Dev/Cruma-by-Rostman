package com.rost.cruma.core.world;

import com.rost.cruma.core.geometry.Box;
import glm_.vec3.Vec3;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public final class NativeModelLighting {
    public static final int MAX_LIGHTS = 4;

    public static final int POINT = 1;

    public static final int SPOT = 2;
    public static final int DIRECTIONAL = 3;

    private static final float SPOT_EDGE = (float) Math.cos(Math.PI / 4);

    private NativeModelLighting() {}

    public record HardwareLight(
            int type, Vec3 diffuse, Vec3 position, Vec3 direction, float range, float a0, float a1, boolean sun) {
        public HardwareLight {
            Objects.requireNonNull(diffuse);
            Objects.requireNonNull(position);
            Objects.requireNonNull(direction);
        }
    }

    public static boolean isSunAffect(NativeLightScene scene, int[] leaves) {
        for (var leaf : leaves) {
            if (scene.getZoneLight(scene.getLeaf(leaf).zone()).sunAffect()) {
                return true;
            }
        }
        return false;
    }

    public static int[] getZoneAmbient(NativeLightScene scene, int[] leaves, int ambientGlow, double levelSeconds) {
        var red = 0f;
        var green = 0f;
        var blue = 0f;
        for (var leaf : leaves) {
            var ambient = scene.getZoneLight(scene.getLeaf(leaf).zone()).ambient();
            red = Math.max(red, ambient.getX());
            green = Math.max(green, ambient.getY());
            blue = Math.max(blue, ambient.getZ());
        }
        var glow = ambientGlow == 255 ? (float) (Math.sin(levelSeconds * 8.0) * 0.2 + 0.25) : ambientGlow / 255f;
        return new int[] {toByte(red + glow), toByte(green + glow), toByte(blue + glow)};
    }

    public static Vec3 getAmbient(boolean sunAffect, @Nullable Vec3 actorAmbient, int[] zoneBytes) {
        if (sunAffect && actorAmbient != null) {
            return new Vec3(
                    (toEnvironmentByte(actorAmbient.getX()) >> 1) / 255f,
                    (toEnvironmentByte(actorAmbient.getY()) >> 1) / 255f,
                    (toEnvironmentByte(actorAmbient.getZ()) >> 1) / 255f);
        }
        return new Vec3(zoneBytes[0] / 255f, zoneBytes[1] / 255f, zoneBytes[2] / 255f);
    }

    public static @Nullable Vec3 getActorAmbient(
            NativeLightScene scene,
            Box box,
            int[] nodeStack,
            @Nullable Vec3 actorAmbient,
            @Nullable Vec3 beastActorAmbient,
            double levelSeconds) {
        var leaves = scene.collectLeaves(box, nodeStack);
        if (leaves.length == 0) {
            return null;
        }
        return getAmbient(
                isSunAffect(scene, leaves),
                scene.isBeastEnvironment() ? beastActorAmbient : actorAmbient,
                getZoneAmbient(scene, leaves, 0, levelSeconds));
    }

    public static Vec3 shade(
            Vec3 position, Vec3 normal, Vec3 ambient, List<HardwareLight> lights, float sunVisibility) {
        var red = ambient.getX();
        var green = ambient.getY();
        var blue = ambient.getZ();
        for (var light : lights) {
            var factor = getFactor(light, position, normal);
            if (light.sun()) {
                factor *= sunVisibility;
            }
            red += light.diffuse().getX() * factor;
            green += light.diffuse().getY() * factor;
            blue += light.diffuse().getZ() * factor;
        }
        return new Vec3(Math.clamp(red, 0f, 1f), Math.clamp(green, 0f, 1f), Math.clamp(blue, 0f, 1f));
    }

    private static float getFactor(HardwareLight light, Vec3 position, Vec3 normal) {
        if (light.type() == DIRECTIONAL) {
            var direction = light.direction();
            var length = (float) Math.sqrt(direction.dot(direction));
            return length == 0 ? 0f : Math.max(-normal.dot(direction) / length, 0f);
        }
        var toLight = light.position().minus(position);
        var distance = (float) Math.sqrt(toLight.dot(toLight));
        if (distance > light.range() || distance == 0) {
            return 0f;
        }
        var attenuation = light.a0() + light.a1() * distance;
        var factor = attenuation <= 0 ? 0f : 1f / attenuation;
        if (light.type() == SPOT) {
            var direction = light.direction();
            var length = (float) Math.sqrt(direction.dot(direction));
            var rho = length == 0 ? 0f : -toLight.dot(direction) / (distance * length);
            if (!(rho > SPOT_EDGE)) {
                return 0f;
            }
        }
        return factor * Math.max(normal.dot(toLight) / distance, 0f);
    }

    private static int toByte(float channel) {
        return Math.clamp((int) Math.floor((float) (channel * 255.0)), 0, 255);
    }

    private static int toEnvironmentByte(float channel) {
        return Math.clamp(Math.round(channel * 255f), 0, 255);
    }
}
