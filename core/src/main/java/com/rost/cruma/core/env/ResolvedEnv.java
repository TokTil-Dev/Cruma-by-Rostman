package com.rost.cruma.core.env;

import glm_.vec3.Vec3;
import java.util.Arrays;
import org.jetbrains.annotations.Nullable;

public record ResolvedEnv(
        Vec3 skyBox,
        Vec3 haze,
        Vec3 cloud1,
        Vec3 cloud2,
        Vec3 cloud3,
        Vec3 sunColor,
        Vec3 moonColor,
        float sunScale,
        float moonScale,
        Vec3 terrainAmbient,
        Vec3 terrainSun,
        Vec3 actorAmbient,
        Vec3 actorSun,
        Vec3 staticMeshAmbient,
        Vec3 staticMeshSun,
        Vec3 bspAmbient,
        Vec3 bspSun,
        float beastLightmap1,
        float beastLightmap2,
        Vec3[] moreClouds) {
    public @Nullable Vec3 cloud(int index) {
        return switch (index) {
            case 0 -> cloud1;
            case 1 -> cloud2;
            case 2 -> cloud3;
            default -> index - 3 < moreClouds.length && index >= 3 ? moreClouds[index - 3] : null;
        };
    }

    public ResolvedEnv withSky(Vec3 skyBox, Vec3 haze, Vec3 cloud1, Vec3 cloud2, Vec3 cloud3) {
        return new ResolvedEnv(
                skyBox,
                haze,
                cloud1,
                cloud2,
                cloud3,
                sunColor,
                moonColor,
                sunScale,
                moonScale,
                terrainAmbient,
                terrainSun,
                actorAmbient,
                actorSun,
                staticMeshAmbient,
                staticMeshSun,
                bspAmbient,
                bspSun,
                beastLightmap1,
                beastLightmap2,
                moreClouds);
    }

    public ResolvedEnv withSkyClouds(Vec3 skyBox, Vec3 haze, Vec3[] clouds) {
        return new ResolvedEnv(
                skyBox,
                haze,
                clouds[0],
                clouds[1],
                clouds[2],
                sunColor,
                moonColor,
                sunScale,
                moonScale,
                terrainAmbient,
                terrainSun,
                actorAmbient,
                actorSun,
                staticMeshAmbient,
                staticMeshSun,
                bspAmbient,
                bspSun,
                beastLightmap1,
                beastLightmap2,
                Arrays.copyOfRange(clouds, 3, clouds.length));
    }
}
