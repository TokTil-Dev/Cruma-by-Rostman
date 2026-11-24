package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.core.unreal.UEmitter.UParticleEmitter;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.EffectParts;
import com.rost.cruma.core.world.EmitterLife;
import com.rost.cruma.core.world.EmitterLight;
import com.rost.cruma.core.world.EmitterSound;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.MaterialClient;
import com.rost.cruma.core.world.NativeLightTemporal;
import com.rost.cruma.core.world.NativeRandom;
import com.rost.cruma.viewer.render.ParticleEmitter;
import com.rost.cruma.viewer.render.ParticleEmitter.Kind;
import com.rost.cruma.viewer.render.RenderingContext;
import com.rost.cruma.viewer.render.api.DebugMaterialSlot;
import com.rost.cruma.viewer.render.api.EffectPreview;
import com.rost.cruma.viewer.render.bgfx.BgfxSpawnModelRenderer.BodyLight;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

final class BgfxEffectPreview implements EffectPreview {
    private static final Logger log = LogManager.getLogger(BgfxEffectPreview.class);

    private final RenderingContext renderingContext;
    private final BgfxSceneRenderer scene;
    private final String owner;
    private final String sounds;
    private final Map<Integer, Instance> instances = new LinkedHashMap<>();
    private final Box box = new Box();
    private int nextHandle = 1;
    private int nextSound;
    private boolean held;
    private final NativeLightTemporal temporal = new NativeLightTemporal(new NativeRandom(), 0f, 0);

    private record Binding(
            ParticleEmitter emitter,
            UParticleEmitter source,
            boolean mesh,
            @Nullable Texture texture) {}

    private record Instance(
            List<Binding> bindings,
            float forcedLifeTime,
            @Nullable EmitterLight light,
            Vec3 position) {
        private float seconds() {
            var seconds = 0f;
            for (var binding : bindings) {
                seconds = Math.max(seconds, binding.emitter().getSimulatedSeconds());
            }
            return seconds;
        }
    }

    BgfxEffectPreview(RenderingContext renderingContext, BgfxSceneRenderer scene, String owner) {
        this.renderingContext = renderingContext;
        this.scene = scene;
        this.owner = owner;
        sounds = owner + ":sounds";
    }

    @Override
    public void show(EffectParts effect, Vec3 position, float yaw) {
        clear();
        add(effect, position, yaw);
    }

    @Override
    public int add(EffectParts effect, Vec3 position, float yaw) {
        return add(effect, position, yaw, null, 1f);
    }

    @Override
    public int add(EffectParts effect, Vec3 position, float yaw, float sizeScale) {
        return add(effect, position, yaw, null, effect.setsSizeScale() ? sizeScale : 1f);
    }

    @Override
    public int addTurned(EffectParts effect, Vec3 position, Vec3 rotation) {
        return add(effect, position, rotation.getZ(), rotation, 1f);
    }

    @Override
    public int addTurned(EffectParts effect, Vec3 position, Vec3 rotation, float sizeScale) {
        return add(effect, position, rotation.getZ(), rotation, effect.setsSizeScale() ? sizeScale : 1f);
    }

    @Override
    public void playSound(EmitterSound sound, Vec3 position) {
        renderingContext.soundCues.play(sounds, nextSound++, position, sound);
    }

    @Override
    public void playSound(EmitterSound sound) {
        renderingContext.soundCues.playHere(sounds, nextSound++, sound);
    }

    @Override
    public void playSound(EmitterSound sound, @Nullable Vec3 position, double fromSeconds) {
        renderingContext.soundCues.play(sounds, nextSound++, position, sound, fromSeconds);
    }

    @Override
    public void stopSounds() {
        renderingContext.soundCues.stop(sounds);
    }

    @Override
    public void move(int handle, Vec3 position) {
        var instance = instances.get(handle);
        if (instance == null) {
            return;
        }
        for (var binding : instance.bindings()) {
            binding.emitter().moveActor(position);
        }
        instances.put(
                handle,
                new Instance(instance.bindings(), instance.forcedLifeTime(), instance.light(), new Vec3(position)));
    }

    @Override
    public void kill(int handle) {
        var instance = instances.get(handle);
        if (instance == null) {
            return;
        }
        for (var binding : instance.bindings()) {
            binding.emitter().kill();
        }
    }

    @Override
    public void adjustLife(int handle, float seconds) {
        var instance = instances.get(handle);
        if (instance == null) {
            return;
        }
        for (var binding : instance.bindings()) {
            binding.emitter().adjustLife(EmitterLife.adjusted(binding.source(), seconds, instance.forcedLifeTime()));
        }
        if (instance.light() != null) {
            var light = EmitterLight.adjusted(instance.light(), seconds, instance.forcedLifeTime());
            instances.put(
                    handle, new Instance(instance.bindings(), instance.forcedLifeTime(), light, instance.position()));
        }
    }

    @Override
    public void fixLife(int handle, float seconds) {
        var instance = instances.get(handle);
        if (instance == null) {
            return;
        }
        for (var binding : instance.bindings()) {
            binding.emitter().adjustLife(EmitterLife.fixed(binding.source(), seconds));
        }
    }

    @Override
    public void aimBeam(int handle, Vec3 target, float seconds, boolean doubled) {
        var instance = instances.get(handle);
        if (instance == null) {
            return;
        }
        for (var binding : instance.bindings()) {
            var emitter = binding.emitter();
            if (emitter.getKind() == Kind.BEAM && emitter.getRenderChildOrder() == 0) {
                EffectPreview.aimFirstBeam(emitter, target, seconds, doubled);
            }
        }
    }

    @Override
    public void turn(int handle, Vec3 rotation) {
        var instance = instances.get(handle);
        if (instance == null) {
            return;
        }
        var turn = URotator.ofVector(rotation);
        for (var binding : instance.bindings()) {
            binding.emitter().turnActor(turn);
        }
    }

    @Override
    public void fly(int handle, Vec3 rotation) {
        var instance = instances.get(handle);
        if (instance == null) {
            return;
        }
        var direction = URotator.ofVector(rotation);
        for (var binding : instance.bindings()) {
            binding.emitter().flyActor(direction);
        }
    }

    @Override
    public void remove(int handle) {
        var instance = instances.remove(handle);
        if (instance == null) {
            return;
        }
        var removed = new ArrayList<ParticleEmitter>();
        unregister(instance, removed);
        renderingContext.particles.removeAll(removed);
    }

    @Override
    public void clear() {
        if (instances.isEmpty()) {
            return;
        }
        var removed = new ArrayList<ParticleEmitter>();
        for (var instance : instances.values()) {
            unregister(instance, removed);
        }
        renderingContext.particles.removeAll(removed);
        instances.clear();
    }

    @Override
    public void hold(boolean held) {
        this.held = held;
        for (var instance : instances.values()) {
            for (var binding : instance.bindings()) {
                binding.emitter().setHeld(held);
            }
        }
    }

    @Override
    public void replay() {
        for (var instance : instances.values()) {
            for (var binding : instance.bindings()) {
                binding.emitter().resetSimulation();
            }
        }
    }

    @Override
    public void advance(float seconds) {
        for (var instance : instances.values()) {
            var bindings = new ArrayList<>(instance.bindings());
            bindings.sort(Comparator.comparingInt(binding -> binding.emitter().getRenderChildOrder()));
            for (var binding : bindings) {
                binding.emitter().update(seconds);
            }
        }
    }

    @Override
    public boolean isShown() {
        return !instances.isEmpty();
    }

    @Override
    public boolean isFinished() {
        for (var instance : instances.values()) {
            if (!isFinished(instance)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isFinished(int handle) {
        var instance = instances.get(handle);
        return instance == null || isFinished(instance);
    }

    @Override
    public int getLiveParticles() {
        var live = 0;
        for (var instance : instances.values()) {
            for (var binding : instance.bindings()) {
                var emitter = binding.emitter();
                for (var index = 0; index < emitter.getActiveParticleLimit(); index++) {
                    if (emitter.isActive(index)) {
                        live++;
                    }
                }
            }
        }
        return live;
    }

    void collectBodyLights(MaterialClient client, List<BodyLight> out) {
        for (var instance : instances.values()) {
            var light = instance.light();
            if (light == null || !light.isOnBodies()) {
                continue;
            }
            var power = light.computePower(instance.seconds(), temporal);
            if (power > 0f) {
                out.add(new BodyLight(
                        new Vec3(instance.position()),
                        light.getWorldRadius(),
                        light.computeNativeColor().times(power),
                        light.brightness()));
            }
        }
    }

    @Override
    public @Nullable Bounds3f getBounds() {
        var all = new Box();
        var any = false;
        for (var instance : instances.values()) {
            for (var binding : instance.bindings()) {
                box.clear();
                if (binding.emitter().computeWorldBounds(box)) {
                    all.add(box.getMin());
                    all.add(box.getMax());
                    any = true;
                }
            }
        }
        return any
                ? new Bounds3f(all.getMinX(), all.getMinY(), all.getMinZ(), all.getMaxX(), all.getMaxY(), all.getMaxZ())
                : null;
    }

    private int add(EffectParts effect, Vec3 position, float yaw, @Nullable Vec3 whole, float sizeScale) {
        var handle = nextHandle++;
        var actor = owner + ":" + handle + ":" + effect.effect().path();
        var bindings = new ArrayList<Binding>();
        var failed = 0;
        for (var descriptor : effect.sprites()) {
            var emitter = emitter(
                    new ParticleEmitter(
                            descriptor.emitter(), position, Kind.SPRITE, descriptor.blend(), descriptor.seed()),
                    whole != null ? URotator.ofVector(whole) : turned(descriptor.rotation(), yaw),
                    descriptor.scale(),
                    sizeScale);
            emitter.setVisibility(descriptor.visibility());
            emitter.setTranslucentRenderPriority(descriptor.translucentRenderPriority());
            emitter.setRenderActorIdentity(actor, 0, descriptor.childOrder());
            emitter.setDebugName(descriptor.name());
            try {
                var sprite = scene.registerParticleTexture(
                        owner, descriptor.sprite(), BgfxSceneUploadSink.particleTextureName(descriptor.textureName()));
                emitter.setSpriteImage(sprite);
                bindings.add(bind(new Binding(emitter, descriptor.emitter(), false, sprite)));
            } catch (RuntimeException failure) {
                failed++;
            }
        }
        for (var descriptor : effect.meshes()) {
            var emitter = emitter(
                    new ParticleEmitter(
                            descriptor.emitter(),
                            position,
                            Kind.MESH,
                            descriptor.emitter().getDrawStyle(),
                            descriptor.seed()),
                    whole != null ? URotator.ofVector(whole) : turned(descriptor.rotation(), yaw),
                    descriptor.scale(),
                    sizeScale);
            emitter.setMeshPrePivot(descriptor.prePivot());
            emitter.setMeshSurfaceRules(
                    descriptor.particleColorSurfaces(), descriptor.unlitSurfaces(), descriptor.severalPassSurfaces());
            var surfaces = descriptor.mesh().surfaces();
            var materials = new ArrayList<DebugMaterialSlot>();
            for (var slot = 0; slot < surfaces.size(); slot++) {
                materials.add(DebugMaterialSlot.fromSurface(slot, surfaces.get(slot)));
            }
            emitter.setDiagnosticMaterials(materials);
            emitter.setBlackBackground(descriptor.blackBackground());
            emitter.setRenderTwoSided(descriptor.renderTwoSided());
            emitter.setVisibility(descriptor.visibility());
            emitter.setTranslucentRenderPriority(descriptor.translucentRenderPriority());
            emitter.setRenderActorIdentity(actor, 0, descriptor.childOrder());
            emitter.setDebugName(descriptor.name());
            try {
                if (descriptor.isVertexAnimated()) {
                    emitter.configureMeshAnimation(
                            descriptor.animationFrames().size(),
                            descriptor.animationStartFrame(),
                            descriptor.animationFrameCount(),
                            descriptor.animationFramesPerSecond());
                    scene.registerAnimatedMeshEmitter(owner, emitter, descriptor.animationFrames(), List.of());
                } else {
                    scene.registerMeshEmitter(owner, emitter, descriptor.mesh());
                }
                bindings.add(bind(new Binding(emitter, descriptor.emitter(), true, null)));
            } catch (RuntimeException failure) {
                failed++;
            }
        }
        for (var descriptor : effect.beams()) {
            var emitter = emitter(
                    new ParticleEmitter(
                            descriptor.emitter(), position, Kind.BEAM, descriptor.blend(), descriptor.seed()),
                    whole != null ? URotator.ofVector(whole) : turned(descriptor.rotation(), yaw),
                    descriptor.scale(),
                    sizeScale);
            emitter.setVisibility(descriptor.visibility());
            emitter.setTranslucentRenderPriority(descriptor.translucentRenderPriority());
            emitter.setRenderActorIdentity(actor, 0, descriptor.childOrder());
            emitter.setDebugName(descriptor.name());
            try {
                var texture = scene.registerParticleTexture(
                        owner, descriptor.texture(), BgfxSceneUploadSink.particleTextureName(descriptor.textureName()));
                emitter.setSpriteImage(texture);
                bindings.add(bind(new Binding(emitter, descriptor.emitter(), false, texture)));
            } catch (RuntimeException failure) {
                failed++;
            }
        }
        if (failed > 0) {
            log.warn(
                    "{} of {} emitters of {} could not be shown",
                    failed,
                    effect.count(),
                    effect.effect().path());
        }
        instances.put(handle, new Instance(bindings, effect.forcedLifeTime(), effect.light(), new Vec3(position)));
        return handle;
    }

    private Binding bind(Binding binding) {
        binding.emitter().setHeld(held);
        renderingContext.particles.add(binding.emitter());
        return binding;
    }

    private void unregister(Instance instance, List<ParticleEmitter> removed) {
        var bindings = instance.bindings();
        for (var index = bindings.size() - 1; index >= 0; index--) {
            var binding = bindings.get(index);
            if (binding.mesh()) {
                scene.unregisterMeshEmitter(owner, binding.emitter());
            } else {
                scene.unregisterParticleTexture(owner, binding.emitter(), binding.texture());
            }
            removed.add(binding.emitter());
        }
    }

    private static ParticleEmitter emitter(ParticleEmitter emitter, URotator rotation, Vec3 scale, float sizeScale) {
        emitter.setActorTransform(rotation, scale);
        emitter.setSizeScale(sizeScale);
        emitter.startAsSpawned();
        return emitter;
    }

    private static URotator turned(URotator rotation, float yaw) {
        return new URotator(rotation.pitch(), rotation.yaw() + URotator.toUnits(yaw), rotation.roll());
    }

    private static boolean isFinished(Instance instance) {
        for (var binding : instance.bindings()) {
            if (!binding.emitter().isWarmedUp() || !binding.emitter().isFinished()) {
                return false;
            }
        }
        return true;
    }
}
