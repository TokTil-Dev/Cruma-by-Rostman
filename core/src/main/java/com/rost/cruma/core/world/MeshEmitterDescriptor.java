package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UEmitter.UMeshEmitter;
import com.rost.cruma.core.unreal.UEmitter.UParticleEmitter;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.Entity.EntityMesh;
import glm_.vec3.Vec3;
import java.util.List;

public record MeshEmitterDescriptor(
        UParticleEmitter emitter,
        Vec3 actorPos,
        Vec3 prePivot,
        URotator rotation,
        Vec3 scale,
        EntityMesh mesh,
        long seed,
        boolean blackBackground,
        EmitterVisibility visibility,
        String name,
        List<EntityMesh> animationFrames,
        int animationStartFrame,
        int animationFrameCount,
        float animationFramesPerSecond,
        boolean renderTwoSided,
        int translucentRenderPriority,
        String actorName,
        int actorOrder,
        int childOrder,
        int particleColorSurfaces,
        int unlitSurfaces,
        int severalPassSurfaces) {
    public MeshEmitterDescriptor {
        animationFrames = animationFrames == null ? List.of() : List.copyOf(animationFrames);
        if (emitter == null
                || actorPos == null
                || prePivot == null
                || rotation == null
                || scale == null
                || mesh == null
                || visibility == null
                || name == null
                || name.isBlank()) {
            throw new IllegalArgumentException("invalid mesh-emitter descriptor");
        }
    }

    public MeshEmitterDescriptor(
            UMeshEmitter emitter,
            Vec3 actorPos,
            Vec3 prePivot,
            URotator rotation,
            Vec3 scale,
            EntityMesh mesh,
            long seed,
            boolean blackBackground,
            EmitterVisibility visibility,
            String name) {
        this(
                emitter,
                actorPos,
                prePivot,
                rotation,
                scale,
                mesh,
                seed,
                blackBackground,
                visibility,
                name,
                -1,
                "synthetic",
                0,
                0);
    }

    public MeshEmitterDescriptor(
            UMeshEmitter emitter,
            Vec3 actorPos,
            Vec3 prePivot,
            URotator rotation,
            Vec3 scale,
            EntityMesh mesh,
            long seed,
            boolean blackBackground,
            EmitterVisibility visibility,
            String name,
            List<EntityMesh> animationFrames,
            int animationStartFrame,
            int animationFrameCount,
            float animationFramesPerSecond,
            boolean renderTwoSided) {
        this(
                emitter,
                actorPos,
                prePivot,
                rotation,
                scale,
                mesh,
                seed,
                blackBackground,
                visibility,
                name,
                animationFrames,
                animationStartFrame,
                animationFrameCount,
                animationFramesPerSecond,
                renderTwoSided,
                -1,
                "synthetic",
                0,
                0,
                0,
                0,
                0);
    }

    public MeshEmitterDescriptor(
            UMeshEmitter emitter,
            Vec3 actorPos,
            Vec3 prePivot,
            URotator rotation,
            Vec3 scale,
            EntityMesh mesh,
            long seed,
            boolean blackBackground,
            EmitterVisibility visibility,
            String name,
            int translucentRenderPriority) {
        this(
                emitter,
                actorPos,
                prePivot,
                rotation,
                scale,
                mesh,
                seed,
                blackBackground,
                visibility,
                name,
                translucentRenderPriority,
                "synthetic",
                0,
                0);
    }

    public MeshEmitterDescriptor(
            UMeshEmitter emitter,
            Vec3 actorPos,
            Vec3 prePivot,
            URotator rotation,
            Vec3 scale,
            EntityMesh mesh,
            long seed,
            boolean blackBackground,
            EmitterVisibility visibility,
            String name,
            int translucentRenderPriority,
            String actorName,
            int actorOrder,
            int childOrder) {
        this(
                emitter,
                actorPos,
                prePivot,
                rotation,
                scale,
                mesh,
                seed,
                blackBackground,
                visibility,
                name,
                List.of(),
                0,
                0,
                0f,
                emitter.isRenderTwoSided(),
                translucentRenderPriority,
                actorName,
                actorOrder,
                childOrder,
                0,
                0,
                0);
    }

    public boolean isVertexAnimated() {
        return !animationFrames.isEmpty();
    }

    public MeshEmitterDescriptor withSurfaceRules(
            EntityMesh recolored, int particleColorSurfaces, int unlitSurfaces, int severalPassSurfaces) {
        return new MeshEmitterDescriptor(
                emitter,
                actorPos,
                prePivot,
                rotation,
                scale,
                recolored,
                seed,
                blackBackground,
                visibility,
                name,
                animationFrames,
                animationStartFrame,
                animationFrameCount,
                animationFramesPerSecond,
                renderTwoSided,
                translucentRenderPriority,
                actorName,
                actorOrder,
                childOrder,
                particleColorSurfaces,
                unlitSurfaces,
                severalPassSurfaces);
    }
}
