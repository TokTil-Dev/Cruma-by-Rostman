package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UEmitter.UParticleEmitter;

public record EmitterLife(float lifetimeMin, float lifetimeMax, float fadeOutStartTime, int maxParticles) {
    public static EmitterLife of(UParticleEmitter emitter) {
        return new EmitterLife(
                emitter.getLifetimeMin(),
                emitter.getLifetimeMax(),
                emitter.getFadeOutStartTime(),
                emitter.getMaxParticles());
    }

    public static float longestForced(Iterable<? extends UParticleEmitter> emitters) {
        var longest = 0f;
        for (var emitter : emitters) {
            if (emitter != null && emitter.isForcedLifeTime() && emitter.getLifetimeMax() > longest) {
                longest = emitter.getLifetimeMax();
            }
        }
        return longest;
    }

    public static EmitterLife adjusted(UParticleEmitter emitter, float seconds, float longest) {
        var min = emitter.getLifetimeMin();
        var max = emitter.getLifetimeMax();
        var fade = emitter.getFadeOutStartTime();
        var count = emitter.getMaxParticles();
        if (emitter.isForcedLifeTime()) {
            var change = seconds - longest;
            min += change;
            max += change;
            if (emitter.isForcedFade()) {
                fade += change;
            }
        }
        if (emitter.isForcedMaxParticles()) {
            var middle = (float) (((double) min + max) * .5);
            count = (int) ((seconds - (double) middle) * (int) emitter.getInitialParticlesPerSecond());
        }
        return new EmitterLife(min, max, fade, count);
    }

    public static EmitterLife fixed(UParticleEmitter emitter, float seconds) {
        return new EmitterLife(seconds, seconds, emitter.getFadeOutStartTime(), emitter.getMaxParticles());
    }
}
