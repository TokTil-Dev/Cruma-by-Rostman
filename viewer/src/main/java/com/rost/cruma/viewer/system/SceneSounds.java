package com.rost.cruma.viewer.system;

import com.rost.cruma.core.audio.AmbientMixer;
import com.rost.cruma.core.audio.AmbientOutput;
import com.rost.cruma.core.audio.AmbientVoice;
import com.rost.cruma.core.audio.SoundClip;
import com.rost.cruma.core.export.SceneTimeline.Kind;
import com.rost.cruma.core.export.SceneTimeline.Level;
import com.rost.cruma.core.export.SceneTimeline.Sound;
import com.rost.cruma.core.export.SceneTrack;
import com.rost.cruma.core.world.AmbientSound;
import com.rost.cruma.core.world.AmbientVolume;
import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.WaterVolume;
import com.rost.cruma.viewer.application.UiContext.Rendering;
import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.render.GameScenePlayer;
import com.rost.cruma.viewer.render.ParticleSystem;
import com.rost.cruma.viewer.render.SoundCues;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class SceneSounds {
    private static final float KEY_STEPS = 1000f;
    private static final long SEED = 1;

    private final AmbientSoundSystem system;
    private final Predicate<SoundClip> ofTheScene;
    private final List<Voice> voices = new ArrayList<>();
    private double now;
    private long millis;
    private float master = 1;

    public SceneSounds(
            GameScenePlayer scene,
            Supplier<Vec3> cameraPosition,
            Supplier<Vec3> cameraRight,
            ParticleSystem particles,
            SoundCues cues,
            List<AmbientSound> sounds,
            List<WaterVolume> waterVolumes,
            List<AmbientVolume> volumes,
            Predicate<SoundClip> ofTheScene,
            float hour,
            MapEventState events) {
        this.ofTheScene = ofTheScene;
        var world = ConfigLoader.builtInDefaults().viewer().world();
        var knobs = new Rendering();
        knobs.setSoundDefaults(world.sound());
        knobs.soundEnabled = true;
        knobs.timeOfDay.set(hour);
        knobs.gameEventStates = events;
        system = new AmbientSoundSystem(
                world.sound(),
                new Output(),
                cameraPosition,
                cameraRight,
                () -> sounds,
                () -> waterVolumes,
                particles::drainActorSpawns,
                particles::drainRemovedActors,
                knobs,
                new Random(SEED),
                () -> 0L,
                cues);
        system.followCutScenes(scene);
        system.followVolumes(() -> volumes);
    }

    public void step(double seconds) {
        var at = Math.round(seconds * 1000);
        var passed = at - millis;
        millis = at;
        now = seconds;
        system.frameEnd(passed);
    }

    public List<Sound> getSounds(double seconds) {
        var sounds = new ArrayList<Sound>(voices.size());
        for (var voice : voices) {
            if (voice.isHeard()) {
                sounds.add(voice.getSound(seconds));
            }
        }
        return sounds;
    }

    private final class Output implements AmbientOutput {
        @Override
        public AmbientVoice play(SoundClip clip, boolean loop, float pitch, float gain, float pan) {
            var voice = new Voice(clip, loop, pitch, now);
            voice.set(gain, pan);
            voices.add(voice);
            return voice;
        }

        @Override
        public void setVolume(float volume) {
            master = volume;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int getActiveVoices() {
            var active = 0;
            for (var voice : voices) {
                active += voice.isFinished() ? 0 : 1;
            }
            return active;
        }

        @Override
        public void close() {}
    }

    private final class Voice implements AmbientVoice {
        private final SoundClip clip;
        private final boolean loop;
        private final float pitch;
        private final double start;
        private final List<Level> levels = new ArrayList<>();
        private double end = Double.NaN;

        Voice(SoundClip clip, boolean loop, float pitch, double start) {
            this.clip = clip;
            this.loop = loop;
            this.pitch = pitch;
            this.start = start;
        }

        @Override
        public void set(float gain, float pan) {
            var level = new Level(now, rounded(gain * master), rounded(pan));
            SceneTrack.keep(levels, level, Level::sameAs);
        }

        @Override
        public void stop() {
            if (Double.isNaN(end)) {
                end = now;
            }
        }

        @Override
        public boolean isFinished() {
            if (!Double.isNaN(end)) {
                return now >= end + AmbientMixer.STOP_FADE_SECONDS;
            }
            return !loop && now >= start + clip.frames() / ((double) clip.sampleRate() * pitch);
        }

        boolean isHeard() {
            return levels.stream().anyMatch(level -> level.volume() > 0);
        }

        Sound getSound(double seconds) {
            return new Sound(
                    ofTheScene.test(clip) ? Kind.SOUND : Kind.AMBIENT,
                    clip.name(),
                    null,
                    clip,
                    start,
                    Double.isNaN(end) ? seconds : end,
                    levels.getFirst().volume(),
                    0,
                    AmbientMixer.STOP_FADE_SECONDS,
                    pitch,
                    loop,
                    levels);
        }

        private static float rounded(float value) {
            return Math.round(value * KEY_STEPS) / KEY_STEPS;
        }
    }
}
