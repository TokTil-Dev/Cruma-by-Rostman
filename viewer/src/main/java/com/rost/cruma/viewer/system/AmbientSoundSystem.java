package com.rost.cruma.viewer.system;

import com.rost.cruma.core.audio.AmbientMixer;
import com.rost.cruma.core.audio.AmbientOutput;
import com.rost.cruma.core.audio.AmbientVoice;
import com.rost.cruma.core.audio.SoundClip;
import com.rost.cruma.core.world.AmbientSound;
import com.rost.cruma.core.world.AmbientSoundChance;
import com.rost.cruma.core.world.AmbientSoundCycle;
import com.rost.cruma.core.world.AmbientSoundHours;
import com.rost.cruma.core.world.AmbientVolume;
import com.rost.cruma.core.world.AmbientVolumePlayer;
import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.WaterVolume;
import com.rost.cruma.core.world.ZoneStateChoice;
import com.rost.cruma.viewer.application.UiContext;
import com.rost.cruma.viewer.application.UiContext.Rendering;
import com.rost.cruma.viewer.application.WindowContext;
import com.rost.cruma.viewer.config.Config.Viewer.World;
import com.rost.cruma.viewer.config.Config.Viewer.World.Sound;
import com.rost.cruma.viewer.render.ParticleSystem.ActorSpawn;
import com.rost.cruma.viewer.render.RenderingContext;
import com.rost.cruma.viewer.render.SceneAudio;
import com.rost.cruma.viewer.render.SoundCues;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class AmbientSoundSystem implements System {
    private static final int THROTTLED_INDEX_SIZE = 10_000;
    private static final float QUIET_SECONDS = 1f;
    private static final int RAND_BOUND = 32_768;
    private static final Comparator<Candidate> LOUDEST_FIRST = (a, b) -> Float.compare(b.gain(), a.gain());

    private static final Logger log = LogManager.getLogger(AmbientSoundSystem.class);
    private final Sound config;
    private final Supplier<Vec3> cameraPosition;
    private final Supplier<Vec3> cameraRight;
    private final Supplier<List<AmbientSound>> sounds;
    private final LongSupplier soundsRevision;
    private final Supplier<List<WaterVolume>> waterVolumes;
    private final Supplier<List<ActorSpawn>> actorSpawns;
    private final Supplier<List<String>> removedActors;
    private final Rendering knobs;
    private final RandomGenerator random;
    private final EmitterSoundPlayer emitterSounds;
    private final SoundCues cues;
    private AmbientOutput output;
    private @Nullable SceneAudio cutScene;
    private BooleanSupplier pause = () -> false;
    private Supplier<List<AmbientVolume>> volumes = List::of;
    private final AmbientVolumePlayer volumePlayer;
    private final Map<AmbientVolumePlayer.Voice, AmbientVoice> volumeVoices = new IdentityHashMap<>();
    private final List<AmbientVolume> takenVolumes = new ArrayList<>();
    private final AmbientSoundChance chances;
    private boolean held;
    private float ambientLevel = 1f;
    private float emitterLevel = 1f;

    private final Map<AmbientSound, Playing> playing = new IdentityHashMap<>();
    private final List<Playing> voices = new ArrayList<>();
    private final List<Candidate> candidates = new ArrayList<>();
    private final List<Candidate> listCandidates = new ArrayList<>();
    private final Map<AmbientSound, ListCycle> cycles = new IdentityHashMap<>();
    private final List<ListCycle> cycleOrder = new ArrayList<>();
    private final List<Candidate> frameCandidates = new ArrayList<>();
    private long frame;
    private float clockSeconds;
    private double rollSeconds;
    private int activeCount;
    private float appliedVolume = Float.NaN;
    private boolean announced;
    private boolean selectionValid;
    private long selectionRevision;
    private final int[] selectionCamera = new int[6];
    private ZoneStateChoice selectionZoneState = ZoneStateChoice.EMPTY;
    private AmbientSoundIndex placementIndex;
    private long placementRevision;
    private long placementRebuildNotBefore;

    public AmbientSoundSystem(World worldConfig, RenderingContext renderingContext, UiContext uiContext) {
        this(
                worldConfig.sound(),
                null,
                () -> renderingContext.camera.getPosition(),
                () -> renderingContext.camera.getRightDir(),
                () -> renderingContext.scene.getAmbientSounds(),
                () -> renderingContext.scene.getWaterVolumes(),
                renderingContext.particles::drainActorSpawns,
                renderingContext.particles::drainRemovedActors,
                uiContext.rendering,
                RandomGenerator.getDefault(),
                renderingContext.scene::getAmbientSoundsRevision,
                renderingContext.soundCues);
        cutScene = renderingContext.sceneAudio;
        pause = renderingContext.playback::isPaused;
        volumes = renderingContext.scene::getAmbientVolumes;
    }

    AmbientSoundSystem(
            Sound config,
            AmbientOutput output,
            Supplier<Vec3> cameraPosition,
            Supplier<Vec3> cameraRight,
            Supplier<List<AmbientSound>> sounds,
            Supplier<List<WaterVolume>> waterVolumes,
            Rendering knobs,
            RandomGenerator random) {
        this(config, output, cameraPosition, cameraRight, sounds, waterVolumes, List::of, List::of, knobs, random);
    }

    AmbientSoundSystem(
            Sound config,
            AmbientOutput output,
            Supplier<Vec3> cameraPosition,
            Supplier<Vec3> cameraRight,
            Supplier<List<AmbientSound>> sounds,
            Supplier<List<WaterVolume>> waterVolumes,
            Supplier<List<ActorSpawn>> actorSpawns,
            Supplier<List<String>> removedActors,
            Rendering knobs,
            RandomGenerator random) {
        this(
                config,
                output,
                cameraPosition,
                cameraRight,
                sounds,
                waterVolumes,
                actorSpawns,
                removedActors,
                knobs,
                random,
                null,
                new SoundCues());
    }

    AmbientSoundSystem(
            Sound config,
            AmbientOutput output,
            Supplier<Vec3> cameraPosition,
            Supplier<Vec3> cameraRight,
            Supplier<List<AmbientSound>> sounds,
            Supplier<List<WaterVolume>> waterVolumes,
            Supplier<List<ActorSpawn>> actorSpawns,
            Supplier<List<String>> removedActors,
            Rendering knobs,
            RandomGenerator random,
            LongSupplier soundsRevision) {
        this(
                config,
                output,
                cameraPosition,
                cameraRight,
                sounds,
                waterVolumes,
                actorSpawns,
                removedActors,
                knobs,
                random,
                soundsRevision,
                new SoundCues());
    }

    AmbientSoundSystem(
            Sound config,
            AmbientOutput output,
            Supplier<Vec3> cameraPosition,
            Supplier<Vec3> cameraRight,
            Supplier<List<AmbientSound>> sounds,
            Supplier<List<WaterVolume>> waterVolumes,
            Supplier<List<ActorSpawn>> actorSpawns,
            Supplier<List<String>> removedActors,
            Rendering knobs,
            RandomGenerator random,
            LongSupplier soundsRevision,
            SoundCues cues) {
        this.config = config;
        this.cues = cues;
        this.output = output;
        this.cameraPosition = cameraPosition;
        this.cameraRight = cameraRight;
        this.sounds = sounds;
        this.soundsRevision = soundsRevision;
        this.waterVolumes = waterVolumes;
        this.actorSpawns = actorSpawns;
        this.removedActors = removedActors;
        this.knobs = knobs;
        this.random = random;
        this.emitterSounds = new EmitterSoundPlayer(random, (float) config.maxRadiusMultiplier());
        this.volumePlayer = new AmbientVolumePlayer(this::drawRand);
        this.chances = new AmbientSoundChance(this::drawRand);
    }

    void followCutScenes(SceneAudio scenes) {
        cutScene = scenes;
    }

    void followPause(BooleanSupplier paused) {
        pause = paused;
    }

    void followVolumes(Supplier<List<AmbientVolume>> ambientVolumes) {
        volumes = ambientVolumes;
    }

    private record Candidate(AmbientSound sound, float gain, float pan) {}

    private static final class ListCycle {
        final AmbientSound sound;
        final AmbientSoundCycle state;
        long seen;

        ListCycle(AmbientSound sound, AmbientSoundCycle state) {
            this.sound = sound;
            this.state = state;
        }
    }

    private static final class Playing {
        final AmbientSound sound;
        final AmbientVoice voice;

        Playing(AmbientSound sound, AmbientVoice voice) {
            this.sound = sound;
            this.voice = voice;
        }
    }

    @Override
    public void start() {
        if (output == null) {
            output = new AmbientMixer();
        }
    }

    @Override
    public void frameEnd(long frameTime) {
        var spawned = actorSpawns.get();
        var removed = removedActors.get();
        var stoppedOwners = cues.drainStopped();
        var cued = cues.drainPlayed();
        if (output == null || !output.isAvailable()) {
            return;
        }
        var paused = pause.getAsBoolean();
        if (paused != held) {
            held = paused;
            output.pause(held);
        }
        if (held) {
            removed.forEach(emitterSounds::remove);
            stoppedOwners.forEach(emitterSounds::remove);
            return;
        }
        var seconds = frameTime / 1000f;
        clockSeconds += seconds;
        rollSeconds += frameTime / 1000.0;
        chances.advance(rollSeconds);
        var scene = cutScene;
        ambientLevel = turned(ambientLevel, scene != null && scene.stopsAmbient(), seconds);
        emitterLevel = turned(emitterLevel, scene != null && scene.stopsEffects(), seconds);
        emitterSounds.setMapLevel(emitterLevel);
        var volume = Math.clamp(knobs.soundVolume.get() / 100f, 0f, 1f);
        if (volume != appliedVolume) {
            output.setVolume(volume);
            appliedVolume = volume;
        }
        if (!knobs.soundEnabled) {
            if (!playing.isEmpty()) {
                voices.forEach(entry -> entry.voice.stop());
                voices.clear();
                playing.clear();
            }
            cycles.clear();
            cycleOrder.clear();
            volumeVoices.values().forEach(AmbientVoice::stop);
            volumeVoices.clear();
            volumePlayer.clear();
            emitterSounds.suspend();
            knobs.stats.soundStatus = "off";
            return;
        }
        var position = cameraPosition.get();
        var right = cameraRight.get();
        for (var spawn : spawned) {
            emitterSounds.spawn(
                    spawn.actorName(), spawn.position(), spawn.settings().sounds(), clockSeconds);
        }
        for (var actor : removed) {
            emitterSounds.remove(actor);
        }
        for (var owner : stoppedOwners) {
            emitterSounds.remove(owner);
        }
        for (var cue : cued) {
            emitterSounds.play(cue.owner(), cue.index(), cue.position(), cue.sound(), clockSeconds, cue.from());
        }
        emitterSounds.frame(output, clockSeconds, position, right);
        var hour = knobs.getTimeOfDay();
        var zoneState = knobs.gameEventStates == null ? ZoneStateChoice.EMPTY : knobs.gameEventStates.zoneStates();
        var underWater = isUnderWater(position, zoneState);
        var maxVoices = Math.max(1, knobs.soundMaxVoices.get());
        var reach = (float) config.maxRadiusMultiplier();
        var volumeVoiceCount = playVolumes(position, underWater, zoneState, hour, seconds, maxVoices);

        if (!reuseSelection(position, right, zoneState)) {
            candidates.clear();
            listCandidates.clear();
            for (var sound : nearbySounds(position, reach)) {
                if (!sound.playsInZoneState(zoneState)) {
                    continue;
                }
                var dx = sound.position().getX() - position.getX();
                var dy = sound.position().getY() - position.getY();
                var dz = sound.position().getZ() - position.getZ();
                var distanceSquared = dx * dx + dy * dy + dz * dz;
                var cutoff = sound.radius() * reach;
                if (distanceSquared > cutoff * cutoff) {
                    continue;
                }
                var distance = (float) Math.sqrt(distanceSquared);
                var pan = distance < 1f ? 0f : (dx * right.getX() + dy * right.getY() + dz * right.getZ()) / distance;
                var candidate = new Candidate(sound, sound.gainAt(distance), Math.clamp(pan, -1f, 1f));
                (sound.soundList() == null ? candidates : listCandidates).add(candidate);
            }
            candidates.sort(LOUDEST_FIRST);
            listCandidates.sort(LOUDEST_FIRST);
            if (placementRevision != selectionRevision) {
                selectionValid = false;
            }
        }

        endFinishedVoices();
        var events = knobs.gameEventStates == null ? MapEventState.ALL_SHOWN : knobs.gameEventStates;
        var chosen = chooseVoices(seconds, Math.max(0, maxVoices - volumeVoiceCount), hour, underWater, events);
        var wanted = new IdentityHashMap<AmbientSound, Candidate>();
        for (var candidate : chosen) {
            wanted.put(candidate.sound(), candidate);
        }
        var iterator = voices.iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var sound = entry.sound;
            var voice = entry.voice;
            var candidate = wanted.get(sound);
            var cycle = cycles.get(sound);
            if (candidate == null || voice.isFinished()) {
                if (candidate == null) {
                    voice.stop();
                }
                iterator.remove();
                playing.remove(sound);
                if (cycle != null) {
                    cycle.state.onStop();
                }
            } else {
                voice.set(candidate.gain() * ambientLevel, candidate.pan());
            }
        }
        for (var candidate : chosen) {
            var sound = candidate.sound();
            if (playing.containsKey(sound)) {
                continue;
            }
            var cycle = cycles.get(sound);
            SoundClip clip;
            boolean loop;
            if (cycle != null) {
                if (!cycle.state.canRun()) {
                    continue;
                }
                clip = cycle.state.getSelectedSound();
                loop = cycle.state.isLoop();
            } else {
                clip = sound.clip();
                loop = sound.isLooping();
            }
            var voice = output.play(clip, loop, sound.pitch(), candidate.gain() * ambientLevel, candidate.pan());
            var started = new Playing(sound, voice);
            playing.put(sound, started);
            voices.add(started);
            if (cycle != null) {
                cycle.state.onPlay();
            }
            if (!announced) {
                announced = true;
                log.info(
                        "First ambient voice: {} at gain {} ({} placements in reach)",
                        clip.name(),
                        String.format("%.2f", candidate.gain()),
                        activeCount);
            }
        }
        var emitterVoices = emitterSounds.getActiveVoices();
        knobs.stats.soundStatus = playing.size()
                + " of "
                + activeCount
                + " in reach"
                + (volumeVoiceCount > 0 ? ", " + volumeVoiceCount + " volume" : "")
                + (emitterVoices > 0 ? ", " + emitterVoices + " emitter" : "")
                + (underWater ? ", under water" : AmbientSoundHours.isSoundDay(hour) ? "" : ", night");
    }

    @Override
    public void stop(WindowContext windowContext) {
        emitterSounds.stopAll();
        if (output != null) {
            output.close();
        }
    }

    double getRollSeconds() {
        return rollSeconds;
    }

    private int playVolumes(
            Vec3 position, boolean underWater, ZoneStateChoice zoneState, float hour, float seconds, int maxVoices) {
        var all = volumes.get();
        if (all.isEmpty() && volumeVoices.isEmpty() && volumePlayer.getVoices().isEmpty()) {
            return 0;
        }
        for (var voice : List.copyOf(volumePlayer.getVoices())) {
            var playing = volumeVoices.get(voice);
            if (playing != null && playing.isFinished()) {
                volumeVoices.remove(voice);
                volumePlayer.ended(voice);
            }
        }
        takenVolumes.clear();
        for (var volume : all) {
            if (volume.contains(position) && volume.isTaken(zoneState, knobs.gameEventStates)) {
                takenVolumes.add(volume);
            }
        }
        var inWater =
                underWater && !takenVolumes.isEmpty() && takenVolumes.getFirst().isSilentUnderWater();
        volumePlayer.update(takenVolumes, inWater, AmbientSoundHours.isSoundDay(hour), seconds, maxVoices);
        var kept = Collections.newSetFromMap(new IdentityHashMap<AmbientVolumePlayer.Voice, Boolean>());
        for (var voice : volumePlayer.getVoices()) {
            kept.add(voice);
            var gain = Math.min(1f, voice.getGain()) * ambientLevel;
            var playing = volumeVoices.get(voice);
            if (playing == null) {
                var sound = voice.getSound();
                volumeVoices.put(voice, output.play(sound.clip(), sound.loops(), sound.playPitch(), gain, 0f));
            } else {
                playing.set(gain, 0f);
            }
        }
        var iterator = volumeVoices.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!kept.contains(entry.getKey())) {
                entry.getValue().stop();
                iterator.remove();
            }
        }
        return volumePlayer.getVoices().size();
    }

    private List<Candidate> chooseVoices(
            float seconds, int maxVoices, float hour, boolean underWater, MapEventState events) {
        frame++;
        frameCandidates.clear();
        activeCount = 0;
        for (var candidate : candidates) {
            var sound = candidate.sound();
            if (!sound.playsAt(hour, underWater, events)) {
                continue;
            }
            activeCount++;
            if (sound.isLooping() || playing.containsKey(sound) || chances.roll(sound)) {
                frameCandidates.add(candidate);
            }
        }
        for (var candidate : listCandidates) {
            var sound = candidate.sound();
            if (!sound.playsAt(hour, underWater, events)) {
                continue;
            }
            activeCount++;
            var cycle = cycles.get(sound);
            if (cycle == null) {
                cycle = new ListCycle(sound, new AmbientSoundCycle(sound.soundList(), this::drawRand));
                cycles.put(sound, cycle);
                cycleOrder.add(cycle);
            }
            cycle.seen = frame;
            cycle.state.overlap(seconds);
            if (playing.containsKey(sound) || cycle.state.canRun() && cycle.state.validateSelectedSound()) {
                frameCandidates.add(candidate);
            }
        }
        var iterator = cycleOrder.iterator();
        while (iterator.hasNext()) {
            var cycle = iterator.next();
            if (cycle.seen != frame) {
                cycle.state.nonOverlap();
                cycles.remove(cycle.sound);
                iterator.remove();
            }
        }
        if (!listCandidates.isEmpty()) {
            frameCandidates.sort(LOUDEST_FIRST);
        }
        var count = Math.min(frameCandidates.size(), maxVoices);
        for (var i = count; i < frameCandidates.size(); i++) {
            var sound = frameCandidates.get(i).sound();
            var cycle = cycles.get(sound);
            if (cycle != null && !playing.containsKey(sound)) {
                cycle.state.onStop();
            }
        }
        return frameCandidates.subList(0, count);
    }

    private void endFinishedVoices() {
        var iterator = voices.iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.voice.isFinished()) {
                continue;
            }
            iterator.remove();
            playing.remove(entry.sound);
            var cycle = cycles.get(entry.sound);
            if (cycle != null) {
                cycle.state.onStop();
            }
        }
    }

    private int drawRand() {
        return random.nextInt(RAND_BOUND);
    }

    private boolean reuseSelection(Vec3 position, Vec3 right, ZoneStateChoice zoneState) {
        if (soundsRevision == null) {
            return false;
        }
        var revision = soundsRevision.getAsLong();
        var x = Float.floatToIntBits(position.getX());
        var y = Float.floatToIntBits(position.getY());
        var z = Float.floatToIntBits(position.getZ());
        var rx = Float.floatToIntBits(right.getX());
        var ry = Float.floatToIntBits(right.getY());
        var rz = Float.floatToIntBits(right.getZ());
        if (selectionValid
                && selectionRevision == revision
                && zoneState.equals(selectionZoneState)
                && selectionCamera[0] == x
                && selectionCamera[1] == y
                && selectionCamera[2] == z
                && selectionCamera[3] == rx
                && selectionCamera[4] == ry
                && selectionCamera[5] == rz) {
            return true;
        }
        selectionValid = true;
        selectionRevision = revision;
        selectionZoneState = zoneState;
        selectionCamera[0] = x;
        selectionCamera[1] = y;
        selectionCamera[2] = z;
        selectionCamera[3] = rx;
        selectionCamera[4] = ry;
        selectionCamera[5] = rz;
        return false;
    }

    private List<AmbientSound> nearbySounds(Vec3 position, float reach) {
        if (soundsRevision == null) {
            return sounds.get();
        }
        var revision = soundsRevision.getAsLong();
        if (placementIndex == null
                || placementRevision != revision && java.lang.System.nanoTime() >= placementRebuildNotBefore) {
            var placements = sounds.get();
            var started = java.lang.System.nanoTime();
            placementIndex = new AmbientSoundIndex(placements, reach);
            placementRevision = revision;
            if (chances.getStartedCount() > 0) {
                var loaded = Collections.newSetFromMap(new IdentityHashMap<AmbientSound, Boolean>());
                loaded.addAll(placements);
                chances.retain(loaded);
            }
            var finished = java.lang.System.nanoTime();
            placementRebuildNotBefore =
                    placements.size() < THROTTLED_INDEX_SIZE ? finished : finished + 49L * (finished - started);
        }
        return placementIndex.findCandidates(position.getX(), position.getY(), position.getZ());
    }

    private boolean isUnderWater(Vec3 position, ZoneStateChoice zoneState) {
        for (var volume : waterVolumes.get()) {
            if (volume.isWaterInZoneState(zoneState) && volume.contains(position)) {
                return true;
            }
        }
        return false;
    }

    private static float turned(float level, boolean quiet, float seconds) {
        var step = seconds / QUIET_SECONDS;
        return Math.clamp(quiet ? level - step : level + step, 0f, 1f);
    }
}
