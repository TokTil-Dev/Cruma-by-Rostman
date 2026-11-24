package com.rost.cruma.viewer.system;

import com.rost.cruma.core.audio.AmbientOutput;
import com.rost.cruma.core.audio.AmbientVoice;
import com.rost.cruma.core.world.EmitterSound;
import com.rost.cruma.viewer.render.api.EffectPreview;
import glm_.vec3.Vec3;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.jetbrains.annotations.Nullable;

final class EmitterSoundPlayer {
    private final Map<Key, Cue> cues = new LinkedHashMap<>();
    private final RandomGenerator random;
    private final float reach;
    private float mapLevel = 1f;

    EmitterSoundPlayer(RandomGenerator random, float reach) {
        this.random = random;
        this.reach = reach;
    }

    private record Key(String actor, int index) {}

    private static final class Cue {
        final EmitterSound sound;
        final @Nullable Vec3 position;
        final float pitch;
        final boolean map;
        final double from;
        float startAt;
        AmbientVoice voice;
        float startedAt;

        Cue(EmitterSound sound, @Nullable Vec3 position, float pitch, boolean map, float startAt, double from) {
            this.sound = sound;
            this.position = position == null ? null : new Vec3(position);
            this.pitch = pitch;
            this.map = map;
            this.startAt = startAt;
            this.from = from;
        }

        boolean isPlaying() {
            return voice != null && !voice.isFinished();
        }

        boolean isPending() {
            return voice == null;
        }

        float getEnvelopeAt(float clock) {
            var elapsed = clock - startedAt;
            var envelope = 1f;
            if (sound.fadeInSeconds() > 0f) {
                envelope = Math.clamp(elapsed / sound.fadeInSeconds(), 0f, 1f);
            }
            if (sound.fadesOut() && elapsed >= sound.fadeOutStartSeconds()) {
                var out = sound.fadeOutSeconds() > 0f
                        ? Math.clamp(1f - (elapsed - sound.fadeOutStartSeconds()) / sound.fadeOutSeconds(), 0f, 1f)
                        : 0f;
                envelope *= out;
            }
            return envelope;
        }

        boolean isFadedOut(float clock) {
            return sound.fadesOut() && clock - startedAt >= sound.fadeOutStartSeconds() + sound.fadeOutSeconds();
        }
    }

    void spawn(String actor, Vec3 position, List<EmitterSound> sounds, float clock) {
        for (var index = 0; index < sounds.size(); index++) {
            play(actor, index, position, sounds.get(index), clock);
        }
    }

    void play(String actor, int index, @Nullable Vec3 position, EmitterSound sound, float clock) {
        play(actor, index, position, sound, clock, 0);
    }

    void play(String actor, int index, @Nullable Vec3 position, EmitterSound sound, float clock, double from) {
        var key = new Key(actor, index);
        var existing = cues.get(key);
        if (existing != null && (existing.isPending() || existing.isPlaying())) {
            return;
        }
        cues.put(
                key,
                new Cue(
                        sound,
                        position,
                        sound.pitchFor(random.nextFloat()),
                        !EffectPreview.owns(actor),
                        clock + sound.delaySeconds(),
                        from));
    }

    void remove(String actor) {
        var iterator = cues.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().actor().equals(actor)) {
                stop(entry.getValue());
                iterator.remove();
            }
        }
    }

    void suspend() {
        var iterator = cues.entrySet().iterator();
        while (iterator.hasNext()) {
            var cue = iterator.next().getValue();
            if (cue.sound.looping()) {
                stop(cue);
                cue.voice = null;
                cue.startAt = Float.NEGATIVE_INFINITY;
            } else {
                stop(cue);
                iterator.remove();
            }
        }
    }

    void setMapLevel(float level) {
        mapLevel = level;
    }

    void stopAll() {
        for (var cue : cues.values()) {
            stop(cue);
        }
        cues.clear();
    }

    void frame(AmbientOutput output, float clock, Vec3 listener, Vec3 right) {
        var iterator = cues.entrySet().iterator();
        while (iterator.hasNext()) {
            var cue = iterator.next().getValue();
            if (cue.voice == null) {
                if (clock < cue.startAt) {
                    continue;
                }
                cue.startedAt = clock - (float) cue.from;
                cue.voice = output.play(
                        cue.sound.clip(),
                        cue.sound.looping(),
                        cue.pitch,
                        gainOf(cue, listener) * cue.getEnvelopeAt(clock) * levelOf(cue),
                        panOf(cue, listener, right),
                        cue.from);
                continue;
            }
            if (cue.voice.isFinished()) {
                iterator.remove();
                continue;
            }
            if (cue.isFadedOut(clock)) {
                stop(cue);
                iterator.remove();
                continue;
            }
            cue.voice.set(gainOf(cue, listener) * cue.getEnvelopeAt(clock) * levelOf(cue), panOf(cue, listener, right));
        }
    }

    int getActiveVoices() {
        var count = 0;
        for (var cue : cues.values()) {
            if (cue.isPlaying()) {
                count++;
            }
        }
        return count;
    }

    int getPendingCues() {
        var count = 0;
        for (var cue : cues.values()) {
            if (cue.isPending()) {
                count++;
            }
        }
        return count;
    }

    private float levelOf(Cue cue) {
        return cue.map ? mapLevel : 1f;
    }

    private float gainOf(Cue cue, Vec3 listener) {
        if (cue.position == null) {
            return cue.sound.volume();
        }
        var distance = distance(cue.position, listener);
        if (distance > cue.sound.radius() * reach) {
            return 0f;
        }
        return cue.sound.gainAt(distance);
    }

    private static float panOf(Cue cue, Vec3 listener, Vec3 right) {
        if (cue.position == null) {
            return 0f;
        }
        var dx = cue.position.getX() - listener.getX();
        var dy = cue.position.getY() - listener.getY();
        var dz = cue.position.getZ() - listener.getZ();
        var distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1f) {
            return 0f;
        }
        var pan = (dx * right.getX() + dy * right.getY() + dz * right.getZ()) / distance;
        return Math.clamp(pan, -1f, 1f);
    }

    private static float distance(Vec3 a, Vec3 b) {
        var dx = a.getX() - b.getX();
        var dy = a.getY() - b.getY();
        var dz = a.getZ() - b.getZ();
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void stop(Cue cue) {
        if (cue.voice != null && !cue.voice.isFinished()) {
            cue.voice.stop();
        }
    }
}
