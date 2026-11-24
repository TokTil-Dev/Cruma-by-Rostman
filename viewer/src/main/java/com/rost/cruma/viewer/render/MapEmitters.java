package com.rost.cruma.viewer.render;

import com.rost.cruma.core.world.BeamEmitterDescriptor;
import com.rost.cruma.core.world.MeshEmitterDescriptor;
import com.rost.cruma.core.world.SpriteEmitterDescriptor;
import com.rost.cruma.core.world.UMap;
import com.rost.cruma.viewer.render.ParticleEmitter.Kind;
import glm_.quat.Quat;
import java.util.ArrayList;
import java.util.List;

public final class MapEmitters {
    private MapEmitters() {}

    public static ParticleEmitter sprite(SpriteEmitterDescriptor descriptor) {
        var emitter = new ParticleEmitter(
                descriptor.emitter(), descriptor.actorPos(), Kind.SPRITE, descriptor.blend(), descriptor.seed());
        emitter.setActorTransform(new Quat(descriptor.rotation().vector()), descriptor.scale());
        emitter.setVisibility(descriptor.visibility());
        emitter.setRenderActorIdentity(descriptor.actorName(), descriptor.actorOrder(), descriptor.childOrder());
        emitter.setSpriteImage(descriptor.sprite());
        return emitter;
    }

    public static ParticleEmitter mesh(MeshEmitterDescriptor descriptor) {
        var emitter = new ParticleEmitter(
                descriptor.emitter(),
                descriptor.actorPos(),
                Kind.MESH,
                descriptor.emitter().getDrawStyle(),
                descriptor.seed());
        emitter.setActorTransform(new Quat(descriptor.rotation().vector()), descriptor.scale());
        emitter.setMeshPrePivot(descriptor.prePivot());
        emitter.setMeshSurfaceRules(
                descriptor.particleColorSurfaces(), descriptor.unlitSurfaces(), descriptor.severalPassSurfaces());
        emitter.setVisibility(descriptor.visibility());
        emitter.setRenderActorIdentity(descriptor.actorName(), descriptor.actorOrder(), descriptor.childOrder());
        if (descriptor.isVertexAnimated()) {
            emitter.configureMeshAnimation(
                    descriptor.animationFrames().size(),
                    descriptor.animationStartFrame(),
                    descriptor.animationFrameCount(),
                    descriptor.animationFramesPerSecond());
        }
        return emitter;
    }

    public static ParticleEmitter beam(BeamEmitterDescriptor descriptor) {
        var emitter = new ParticleEmitter(
                descriptor.emitter(), descriptor.actorPos(), Kind.BEAM, descriptor.blend(), descriptor.seed());
        emitter.setActorTransform(new Quat(descriptor.rotation().vector()), descriptor.scale());
        emitter.setVisibility(descriptor.visibility());
        emitter.setRenderActorIdentity(descriptor.actorName(), descriptor.actorOrder(), descriptor.childOrder());
        emitter.setSpriteImage(descriptor.texture());
        return emitter;
    }

    public static List<ParticleEmitter> sounding(UMap map) {
        var emitters = new ArrayList<ParticleEmitter>();
        for (var descriptor : map.spriteEmitters()) {
            if (!descriptor.visibility().sounds().isEmpty()) {
                emitters.add(sprite(descriptor));
            }
        }
        for (var descriptor : map.meshEmitters()) {
            if (!descriptor.visibility().sounds().isEmpty()) {
                emitters.add(mesh(descriptor));
            }
        }
        for (var descriptor : map.beamEmitters()) {
            if (!descriptor.visibility().sounds().isEmpty()) {
                emitters.add(beam(descriptor));
            }
        }
        return emitters;
    }
}
