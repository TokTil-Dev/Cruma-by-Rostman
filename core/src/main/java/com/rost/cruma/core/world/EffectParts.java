package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.EffectClasses.Effect;
import com.rost.cruma.core.unreal.UEmitter.UParticleEmitter;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public record EffectParts(
        Effect effect,
        List<SpriteEmitterDescriptor> sprites,
        List<MeshEmitterDescriptor> meshes,
        List<BeamEmitterDescriptor> beams,
        boolean setsSizeScale,
        float projectileSpeed,
        float projectileAcceleration,
        float forcedLifeTime,
        @Nullable EmitterLight light) {
    public static final float PROJECTILE_DEFAULT = 10f;

    public EffectParts {
        sprites = List.copyOf(sprites);
        meshes = List.copyOf(meshes);
        beams = List.copyOf(beams);
    }

    public EffectParts(
            Effect effect,
            List<SpriteEmitterDescriptor> sprites,
            List<MeshEmitterDescriptor> meshes,
            List<BeamEmitterDescriptor> beams,
            boolean setsSizeScale,
            float projectileSpeed,
            float projectileAcceleration,
            float forcedLifeTime) {
        this(
                effect,
                sprites,
                meshes,
                beams,
                setsSizeScale,
                projectileSpeed,
                projectileAcceleration,
                forcedLifeTime,
                null);
    }

    public EffectParts(
            Effect effect,
            List<SpriteEmitterDescriptor> sprites,
            List<MeshEmitterDescriptor> meshes,
            List<BeamEmitterDescriptor> beams,
            boolean setsSizeScale,
            float projectileSpeed,
            float projectileAcceleration) {
        this(
                effect,
                sprites,
                meshes,
                beams,
                setsSizeScale,
                projectileSpeed,
                projectileAcceleration,
                EmitterLife.longestForced(emitters(sprites, meshes, beams)));
    }

    public EffectParts(
            Effect effect,
            List<SpriteEmitterDescriptor> sprites,
            List<MeshEmitterDescriptor> meshes,
            List<BeamEmitterDescriptor> beams,
            boolean setsSizeScale) {
        this(effect, sprites, meshes, beams, setsSizeScale, PROJECTILE_DEFAULT, PROJECTILE_DEFAULT);
    }

    public EffectParts(
            Effect effect,
            List<SpriteEmitterDescriptor> sprites,
            List<MeshEmitterDescriptor> meshes,
            List<BeamEmitterDescriptor> beams) {
        this(effect, sprites, meshes, beams, true);
    }

    public boolean isEmpty() {
        return sprites.isEmpty() && meshes.isEmpty() && beams.isEmpty();
    }

    public int count() {
        return sprites.size() + meshes.size() + beams.size();
    }

    private static List<UParticleEmitter> emitters(
            List<SpriteEmitterDescriptor> sprites,
            List<MeshEmitterDescriptor> meshes,
            List<BeamEmitterDescriptor> beams) {
        var emitters = new ArrayList<UParticleEmitter>();
        for (var sprite : sprites) {
            emitters.add(sprite.emitter());
        }
        for (var mesh : meshes) {
            emitters.add(mesh.emitter());
        }
        for (var beam : beams) {
            emitters.add(beam.emitter());
        }
        return emitters;
    }
}
