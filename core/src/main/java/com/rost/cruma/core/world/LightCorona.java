package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.Entity.Texture;
import glm_.vec3.Vec3;

public record LightCorona(
        String actorPath,
        Vec3 location,
        URotator rotation,
        Texture texture,
        String textureName,
        int hue,
        int saturation,
        float drawScale,
        Vec3 drawScale3d,
        float minSize,
        float maxSize,
        float rotationRate,
        float rotationOffset,
        float scaleGlow,
        float radius,
        float cullDistance,
        boolean ownFinalBlend) {
    public float reach() {
        return CoronaRules.reach(radius);
    }

    public boolean isAlphaTested() {
        return texture.alphaTexture();
    }

    public LightCorona withActorPath(String path) {
        return new LightCorona(
                path,
                location,
                rotation,
                texture,
                textureName,
                hue,
                saturation,
                drawScale,
                drawScale3d,
                minSize,
                maxSize,
                rotationRate,
                rotationOffset,
                scaleGlow,
                radius,
                cullDistance,
                ownFinalBlend);
    }

    public LightCorona carriedTo(Vec3 place, URotator turn) {
        return new LightCorona(
                actorPath,
                place,
                turn,
                texture,
                textureName,
                hue,
                saturation,
                drawScale,
                drawScale3d,
                minSize,
                maxSize,
                rotationRate,
                rotationOffset,
                scaleGlow,
                radius,
                cullDistance,
                ownFinalBlend);
    }

    public LightCorona withTexture(Texture shared) {
        return new LightCorona(
                actorPath,
                location,
                rotation,
                shared,
                textureName,
                hue,
                saturation,
                drawScale,
                drawScale3d,
                minSize,
                maxSize,
                rotationRate,
                rotationOffset,
                scaleGlow,
                radius,
                cullDistance,
                ownFinalBlend);
    }

    public LightCorona movedTo(Vec3 place, int nextHue, int nextSaturation, float nextRadius) {
        return new LightCorona(
                actorPath,
                place,
                rotation,
                texture,
                textureName,
                nextHue,
                nextSaturation,
                drawScale,
                drawScale3d,
                minSize,
                maxSize,
                rotationRate,
                rotationOffset,
                scaleGlow,
                nextRadius,
                cullDistance,
                ownFinalBlend);
    }
}
