package com.rost.cruma.viewer.render;

import com.rost.cruma.core.audio.SoundClip;
import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.unreal.UEmitter.UParticleEmitter;
import com.rost.cruma.core.world.EffectParts;
import com.rost.cruma.core.world.EmitterLife;
import com.rost.cruma.core.world.EmitterSound;
import com.rost.cruma.core.world.EmitterVisibility;
import com.rost.cruma.viewer.render.ParticleEmitter.Kind;
import com.rost.cruma.viewer.render.api.EffectPreview;
import glm_.quat.Quat;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class SilentEffectPreview implements EffectPreview {
    private static final String SOUNDS = SCENE + ":sounds";

    private final ParticleSystem particles;
    private final SoundCues cues;
    private final Map<Integer, List<ParticleEmitter>> effects = new LinkedHashMap<>();
    private final Map<Integer, EffectParts> classes = new HashMap<>();
    private final Set<SoundClip> clips = Collections.newSetFromMap(new IdentityHashMap<>());
    private int nextEffect = 1;
    private int nextSound;

    public SilentEffectPreview(ParticleSystem particles, SoundCues cues) {
        this.particles = particles;
        this.cues = cues;
    }

    public boolean plays(SoundClip clip) {
        return clips.contains(clip);
    }

    @Override
    public void show(EffectParts effect, Vec3 position, float yaw) {
        clear();
        add(effect, position, yaw);
    }

    @Override
    public int add(EffectParts effect, Vec3 position, float yaw) {
        var handle = nextEffect++;
        var actor = SCENE + ":" + handle + ":" + effect.effect().path();
        var emitters = new ArrayList<ParticleEmitter>();
        for (var descriptor : effect.sprites()) {
            var emitter = new ParticleEmitter(
                    descriptor.emitter(), position, Kind.SPRITE, descriptor.blend(), descriptor.seed());
            place(emitter, descriptor.rotation().vector(), yaw, descriptor.scale());
            name(emitter, descriptor.visibility(), actor, descriptor.childOrder());
            emitters.add(emitter);
        }
        for (var descriptor : effect.meshes()) {
            var emitter = new ParticleEmitter(
                    descriptor.emitter(),
                    position,
                    Kind.MESH,
                    descriptor.emitter().getDrawStyle(),
                    descriptor.seed());
            place(emitter, descriptor.rotation().vector(), yaw, descriptor.scale());
            emitter.setMeshPrePivot(descriptor.prePivot());
            emitter.setMeshSurfaceRules(
                    descriptor.particleColorSurfaces(), descriptor.unlitSurfaces(), descriptor.severalPassSurfaces());
            name(emitter, descriptor.visibility(), actor, descriptor.childOrder());
            if (descriptor.isVertexAnimated()) {
                emitter.configureMeshAnimation(
                        descriptor.animationFrames().size(),
                        descriptor.animationStartFrame(),
                        descriptor.animationFrameCount(),
                        descriptor.animationFramesPerSecond());
            }
            emitters.add(emitter);
        }
        for (var descriptor : effect.beams()) {
            var emitter = new ParticleEmitter(
                    descriptor.emitter(), position, Kind.BEAM, descriptor.blend(), descriptor.seed());
            place(emitter, descriptor.rotation().vector(), yaw, descriptor.scale());
            name(emitter, descriptor.visibility(), actor, descriptor.childOrder());
            emitters.add(emitter);
        }
        emitters.forEach(particles::add);
        effects.put(handle, emitters);
        classes.put(handle, effect);
        return handle;
    }

    @Override
    public void adjustLife(int handle, float seconds) {
        var emitters = effects.get(handle);
        var effect = classes.get(handle);
        if (emitters == null || effect == null) {
            return;
        }
        var sources = new ArrayList<UParticleEmitter>();
        effect.sprites().forEach(descriptor -> sources.add(descriptor.emitter()));
        effect.meshes().forEach(descriptor -> sources.add(descriptor.emitter()));
        effect.beams().forEach(descriptor -> sources.add(descriptor.emitter()));
        for (var at = 0; at < emitters.size(); at++) {
            emitters.get(at).adjustLife(EmitterLife.adjusted(sources.get(at), seconds, effect.forcedLifeTime()));
        }
    }

    @Override
    public void fixLife(int handle, float seconds) {
        var emitters = effects.get(handle);
        var effect = classes.get(handle);
        if (emitters == null || effect == null) {
            return;
        }
        var sources = new ArrayList<UParticleEmitter>();
        effect.sprites().forEach(descriptor -> sources.add(descriptor.emitter()));
        effect.meshes().forEach(descriptor -> sources.add(descriptor.emitter()));
        effect.beams().forEach(descriptor -> sources.add(descriptor.emitter()));
        for (var at = 0; at < emitters.size(); at++) {
            emitters.get(at).adjustLife(EmitterLife.fixed(sources.get(at), seconds));
        }
    }

    @Override
    public void aimBeam(int handle, Vec3 target, float seconds, boolean doubled) {
        for (var emitter : effects.getOrDefault(handle, List.of())) {
            if (emitter.getKind() == Kind.BEAM && emitter.getRenderChildOrder() == 0) {
                EffectPreview.aimFirstBeam(emitter, target, seconds, doubled);
            }
        }
    }

    @Override
    public void move(int handle, Vec3 position) {
        for (var emitter : effects.getOrDefault(handle, List.of())) {
            emitter.moveActor(position);
        }
    }

    @Override
    public void kill(int handle) {
        for (var emitter : effects.getOrDefault(handle, List.of())) {
            emitter.kill();
        }
    }

    @Override
    public void remove(int handle) {
        var removed = effects.remove(handle);
        classes.remove(handle);
        if (removed != null) {
            particles.removeAll(removed);
        }
    }

    @Override
    public void clear() {
        for (var emitters : effects.values()) {
            particles.removeAll(emitters);
        }
        effects.clear();
        classes.clear();
    }

    @Override
    public void playSound(EmitterSound sound, Vec3 position) {
        clips.add(sound.clip());
        cues.play(SOUNDS, nextSound++, position, sound);
    }

    @Override
    public void playSound(EmitterSound sound) {
        clips.add(sound.clip());
        cues.playHere(SOUNDS, nextSound++, sound);
    }

    @Override
    public void stopSounds() {
        cues.stop(SOUNDS);
    }

    @Override
    public void replay() {
        throw new UnsupportedOperationException("A scene plays once");
    }

    @Override
    public void advance(float seconds) {
        throw new UnsupportedOperationException("A scene never seeks");
    }

    @Override
    public boolean isShown() {
        return !effects.isEmpty();
    }

    @Override
    public boolean isFinished() {
        for (var emitters : effects.values()) {
            if (!isFinished(emitters)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isFinished(int handle) {
        var emitters = effects.get(handle);
        return emitters == null || isFinished(emitters);
    }

    @Override
    public int getLiveParticles() {
        var live = 0;
        for (var emitters : effects.values()) {
            for (var emitter : emitters) {
                for (var index = 0; index < emitter.getActiveParticleLimit(); index++) {
                    if (emitter.isActive(index)) {
                        live++;
                    }
                }
            }
        }
        return live;
    }

    @Override
    public @Nullable Bounds3f getBounds() {
        return null;
    }

    private void name(ParticleEmitter emitter, EmitterVisibility visibility, String actor, int childOrder) {
        emitter.setVisibility(visibility);
        emitter.setRenderActorIdentity(actor, 0, childOrder);
        for (var sound : visibility.sounds()) {
            clips.add(sound.clip());
        }
    }

    private static void place(ParticleEmitter emitter, Vec3 rotation, float yaw, Vec3 scale) {
        emitter.setActorTransform(new Quat(new Vec3(rotation.getX(), rotation.getY(), rotation.getZ() + yaw)), scale);
        emitter.startAsSpawned();
    }

    private static boolean isFinished(List<ParticleEmitter> emitters) {
        for (var emitter : emitters) {
            if (!emitter.isWarmedUp() || !emitter.isFinished()) {
                return false;
            }
        }
        return true;
    }
}
