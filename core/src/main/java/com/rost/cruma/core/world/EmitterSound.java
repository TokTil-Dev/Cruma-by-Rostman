package com.rost.cruma.core.world;

import com.rost.cruma.core.audio.SoundClip;

public record EmitterSound(
        SoundClip clip,
        float radius,
        float volume,
        float pitchMin,
        float pitchMax,
        boolean looping,
        boolean uiSound,
        float delaySeconds,
        float fadeInSeconds,
        float fadeOutStartSeconds,
        float fadeOutSeconds) {
    public EmitterSound {
        if (clip == null) {
            throw new IllegalArgumentException("an emitter sound needs its clip");
        }
        if (!(radius > 0f) || !Float.isFinite(radius)) {
            throw new IllegalArgumentException("SoundRadius must be positive, got " + radius);
        }
        volume = Math.clamp(volume, 0f, 1f);
        pitchMin = pitchMin > 0f && Float.isFinite(pitchMin) ? pitchMin : 1f;
        pitchMax = pitchMax > 0f && Float.isFinite(pitchMax) ? pitchMax : pitchMin;
        delaySeconds = Math.max(0f, delaySeconds);
        fadeInSeconds = Math.max(0f, fadeInSeconds);
        fadeOutStartSeconds = Math.max(0f, fadeOutStartSeconds);
        fadeOutSeconds = Math.max(0f, fadeOutSeconds);
    }

    public EmitterSound withDelay(float seconds) {
        return new EmitterSound(
                clip,
                radius,
                volume,
                pitchMin,
                pitchMax,
                looping,
                uiSound,
                seconds,
                fadeInSeconds,
                fadeOutStartSeconds,
                fadeOutSeconds);
    }

    public double seconds() {
        return clip.frames() / (clip.sampleRate() * (pitchMin + pitchMax) * .5);
    }

    public float gainAt(float distance) {
        if (distance <= radius) {
            return volume;
        }
        return volume * radius / distance;
    }

    public float pitchFor(float unitRandom) {
        return pitchMin + unitRandom * (pitchMax - pitchMin);
    }

    public boolean fadesOut() {
        return fadeOutStartSeconds > 0f;
    }
}
