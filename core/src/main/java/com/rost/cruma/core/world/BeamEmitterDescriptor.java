package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UEmitter.UBeamEmitter;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.Entity.Texture;
import glm_.vec3.Vec3;

public record BeamEmitterDescriptor(
        UBeamEmitter emitter,
        Vec3 actorPos,
        URotator rotation,
        Vec3 scale,
        Texture texture,
        int blend,
        long seed,
        EmitterVisibility visibility,
        String name,
        String textureName,
        int translucentRenderPriority,
        String actorName,
        int actorOrder,
        int childOrder
        ) {}
