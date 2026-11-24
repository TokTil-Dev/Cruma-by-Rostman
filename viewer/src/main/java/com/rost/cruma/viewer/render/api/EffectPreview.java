package com.rost.cruma.viewer.render.api;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.world.EffectParts;
import com.rost.cruma.core.world.EmitterSound;
import com.rost.cruma.viewer.render.ParticleEmitter;
import glm_.vec3.Vec3;
import org.jetbrains.annotations.Nullable;

public interface EffectPreview {
    String TOOL = "effect-preview";

    String SCENE = "scene-effects";

    String MAP_PAWNS = "map-pawn-effects";

    static boolean owns(String name) {
        return name.startsWith(TOOL) || name.startsWith(SCENE) || name.startsWith(MAP_PAWNS);
    }

    static void aimFirstBeam(ParticleEmitter beam, Vec3 target, float seconds, boolean doubled) {
        beam.aimBeam(target);
        if (!Float.isNaN(seconds)) {
            beam.setMaxParticles((int) (seconds * beam.getInitialParticlesPerSecond()));
        } else if (doubled) {
            beam.setMaxParticles(beam.getMaxParticles() * 2);
        }
    }

    void show(EffectParts effect, Vec3 position, float yaw);

    int add(EffectParts effect, Vec3 position, float yaw);

    default int addTurned(EffectParts effect, Vec3 position, Vec3 rotation) {
        return add(effect, position, rotation.getZ());
    }

    default int add(EffectParts effect, Vec3 position, float yaw, float sizeScale) {
        return add(effect, position, yaw);
    }

    default int addTurned(EffectParts effect, Vec3 position, Vec3 rotation, float sizeScale) {
        return addTurned(effect, position, rotation);
    }

    default void kill(int handle) {
        remove(handle);
    }

    default void adjustLife(int handle, float seconds) {}

    default void fixLife(int handle, float seconds) {}

    default void aimBeam(int handle, Vec3 target, float seconds, boolean doubled) {}

    default void turn(int handle, Vec3 rotation) {}

    default void fly(int handle, Vec3 rotation) {
        turn(handle, rotation);
    }

    void move(int handle, Vec3 position);

    void remove(int handle);

    void clear();

    default void hold(boolean held) {}

    void playSound(EmitterSound sound, Vec3 position);

    void playSound(EmitterSound sound);

    default void playSound(EmitterSound sound, @Nullable Vec3 position, double fromSeconds) {
        if (position == null) {
            playSound(sound);
        } else {
            playSound(sound, position);
        }
    }

    void stopSounds();

    void replay();

    void advance(float seconds);

    boolean isShown();

    boolean isFinished();

    boolean isFinished(int handle);

    int getLiveParticles();

    @Nullable
    Bounds3f getBounds();
}
