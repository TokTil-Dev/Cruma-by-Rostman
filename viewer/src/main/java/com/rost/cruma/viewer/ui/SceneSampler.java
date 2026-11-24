package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.audio.SoundClipCache;
import com.rost.cruma.core.audio.VorbisDecoder;
import com.rost.cruma.core.export.LocationExport.Progress;
import com.rost.cruma.core.export.NpcExport;
import com.rost.cruma.core.export.NpcExport.Character;
import com.rost.cruma.core.export.NpcExport.Npc;
import com.rost.cruma.core.export.SceneTimeline;
import com.rost.cruma.core.export.SceneTimeline.Actor;
import com.rost.cruma.core.export.SceneTimeline.Door;
import com.rost.cruma.core.export.SceneTimeline.DoorKey;
import com.rost.cruma.core.export.SceneTimeline.Effect;
import com.rost.cruma.core.export.SceneTimeline.Kind;
import com.rost.cruma.core.export.SceneTimeline.Place;
import com.rost.cruma.core.export.SceneTimeline.Rig;
import com.rost.cruma.core.export.SceneTimeline.Sound;
import com.rost.cruma.core.export.SceneTimeline.View;
import com.rost.cruma.core.export.SceneTrack;
import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.npc.ClientCatalog;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.unreal.DecryptedPackages;
import com.rost.cruma.core.world.AmbientSound;
import com.rost.cruma.core.world.AmbientVolume;
import com.rost.cruma.core.world.ClientFov;
import com.rost.cruma.core.world.DxtChainCache;
import com.rost.cruma.core.world.EffectParts;
import com.rost.cruma.core.world.EmitterSound;
import com.rost.cruma.core.world.GameScenes.Music;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.NpcAnimation;
import com.rost.cruma.core.world.SceneOrders;
import com.rost.cruma.core.world.SceneOrders.Size;
import com.rost.cruma.core.world.TextureQuality;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.WaterVolume;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.geodata.ClientFloorGrid;
import com.rost.cruma.viewer.geodata.ClientFloors;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.Context;
import com.rost.cruma.viewer.render.GameScenePlayer;
import com.rost.cruma.viewer.render.GameScenePlayer.Ground;
import com.rost.cruma.viewer.render.GameScenePlayer.SceneActor;
import com.rost.cruma.viewer.render.MapEmitters;
import com.rost.cruma.viewer.render.ParticleEmitter;
import com.rost.cruma.viewer.render.ParticleSystem;
import com.rost.cruma.viewer.render.SilentEffectPreview;
import com.rost.cruma.viewer.render.SoundCues;
import com.rost.cruma.viewer.render.SpawnOverlayState;
import com.rost.cruma.viewer.render.api.EffectPreview;
import com.rost.cruma.viewer.spawn.AirshipModel;
import com.rost.cruma.viewer.spawn.AirshipModel.Part;
import com.rost.cruma.viewer.spawn.SceneCharacter;
import com.rost.cruma.viewer.spawn.SpawnModels;
import com.rost.cruma.viewer.spawn.SpawnModels.Animated;
import com.rost.cruma.viewer.system.MusicSystem;
import com.rost.cruma.viewer.system.SceneSounds;
import com.rost.cruma.viewer.ui.BakedScene.Body;
import com.rost.cruma.viewer.ui.BakedScene.Builder;
import com.rost.cruma.viewer.ui.BakedScene.Clip;
import com.rost.cruma.viewer.ui.BakedScene.Cue;
import com.rost.cruma.viewer.ui.BakedScene.PlayedEffect;
import glm_.vec3.Vec3;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

final class SceneSampler {
    static final int RATE = 30;

    private static final float NEAR = 10;
    private static final int UNITS_PER_TURN = 65536;
    private static final float MUSIC_VOLUME = .3f;
    private static final float VOICE_VOLUME = .6f;
    private static final long READING_NAP_MILLIS = 5;
    private static final long IDLE_PACKAGE_BYTES = 512L << 20;

    private final Scene scene;
    private final Shared shared;
    private final Path client;
    private final float musicFade;
    private final boolean showsScreen;
    private final int rate;
    private final double step;
    private final Camera camera;
    private final GameScenePlayer player;
    private final Builder baked;
    private final Map<SceneActor, Track> tracks = new IdentityHashMap<>();
    private final List<Track> order = new ArrayList<>();
    private final List<View> views = new ArrayList<>();
    private final List<PlayedSound> sounds = new ArrayList<>();
    private final List<Recorded> effectOrder = new ArrayList<>();
    private final Map<String, DoorTrack> doors = new LinkedHashMap<>();
    private @Nullable PlayedSound track;
    private @Nullable Recorder recorder;
    private double now;

    private SceneSampler(Scene scene, Shared shared, float musicFade, boolean showsScreen, int rate, boolean bakes) {
        this.scene = scene;
        this.shared = shared;
        client = shared.client;
        this.musicFade = musicFade;
        this.showsScreen = showsScreen;
        this.rate = rate;
        step = 1.0 / rate;
        camera = shared.camera;
        player = shared.player;
        baked = bakes ? new Builder(scene, rate) : null;
    }

    record Surroundings(
            Ground ground,
            List<AmbientSound> sounds,
            List<WaterVolume> waterVolumes,
            List<AmbientVolume> volumes,
            List<ParticleEmitter> emitters,
            float hour,
            MapEventState events) {
        static Surroundings of(Ground ground) {
            return of(ground, 12);
        }

        static Surroundings of(Ground ground, float hour) {
            return new Surroundings(ground, List.of(), List.of(), List.of(), List.of(), hour, MapEventState.ALL_SHOWN);
        }
    }

    static SceneTimeline sample(
            Scene scene,
            List<String> tiles,
            Path client,
            WorldVariant variant,
            TextureQuality quality,
            float musicFade,
            float hour,
            MapEventState events,
            Progress progress,
            BooleanSupplier cancelled) {
        progress.update("Reading the floors", 0);
        var around = surroundings(client, variant, tiles, hour, events, cancelled);
        return sample(scene, around, client, variant, quality, musicFade, progress, cancelled);
    }

    static BakedScene bake(
            Scene scene, Surroundings around, Shared shared, int rate, Progress progress, BooleanSupplier cancelled) {
        var sampler = new SceneSampler(scene, shared, 0, false, rate, true);
        var timeline = sampler.run(around, progress, cancelled);
        return sampler.baked.build(timeline);
    }

    static SceneTimeline sound(
            Scene scene,
            List<String> tiles,
            Path client,
            WorldVariant variant,
            TextureQuality quality,
            float musicFade,
            float hour,
            MapEventState events,
            BooleanSupplier cancelled) {
        var around = surroundings(client, variant, tiles, hour, events, cancelled);
        try (var shared = new Shared(client, variant, quality)) {
            return new SceneSampler(scene, shared, musicFade, false, RATE, false).run(around, (_, _) -> {}, cancelled);
        }
    }

    static SceneTimeline sample(
            Scene scene,
            Surroundings around,
            Path client,
            WorldVariant variant,
            TextureQuality quality,
            float musicFade,
            Progress progress,
            BooleanSupplier cancelled) {
        try (var shared = new Shared(client, variant, quality)) {
            return new SceneSampler(scene, shared, musicFade, true, RATE, false).run(around, progress, cancelled);
        }
    }

    private SceneTimeline run(Surroundings around, Progress progress, BooleanSupplier cancelled) {
        shared.start(this);
        try {
            return play(around, progress, cancelled);
        } finally {
            shared.end();
        }
    }

    private SceneTimeline play(Surroundings around, Progress progress, BooleanSupplier cancelled) {
        var stage = shared.stage;
        var particles = new ParticleSystem();
        particles.setGameEventStates(around.events());
        particles.setTimeOfDay(around.hour());
        around.emitters().forEach(particles::add);
        var cues = new SoundCues();
        var world = new SilentEffectPreview(particles, cues);
        recorder = new Recorder(world);
        var heard = baked != null
                ? null
                : new SceneSounds(
                        player,
                        camera::getPosition,
                        camera::getRightDir,
                        particles,
                        cues,
                        around.sounds(),
                        around.waterVolumes(),
                        around.volumes(),
                        world::plays,
                        around.hour(),
                        around.events());
        player.play(scene, around.ground());
        progress.update("Reading the scene", 0);
        read(stage, cancelled);
        var overlay = new SpawnOverlayState();
        var screen = showsScreen ? new SceneScreen(client, stage::getLine, step) : null;
        var length = Math.max(scene.seconds(), step);
        var seconds = 0.0;
        while (true) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("export cancelled");
            }
            player.update(step);
            if (!player.isPlaying()) {
                break;
            }
            seconds = now = player.getSeconds();
            stage.update(player, overlay, step);
            takeMusic();
            particles.update((float) step, camera.getPosition());
            recorder.endFinished();
            if (heard != null) {
                heard.step(now);
            }
            record(stage);
            recordDoors();
            if (screen != null) {
                screen.record(now, player);
            }
            if (baked != null) {
                bakeStep();
            }
            progress.update("Playing the scene", Math.min(1, seconds / length));
        }
        endSounds(seconds);
        if (baked != null) {
            baked.speeds(stage.getSpeeds());
        }
        return timeline(stage.getSource(), seconds, heard == null ? List.of() : heard.getSounds(seconds), screen);
    }

    private void recordDoors() {
        var moved = player.takeDoors();
        if (moved != null) {
            if (baked != null) {
                baked.doors(now, moved);
            }
            moved.moved()
                    .forEach((actor, matrix) ->
                            doors.computeIfAbsent(actor, DoorTrack::new).moveTo(matrix, now - step));
            for (var actor : moved.restored()) {
                var door = doors.get(actor);
                if (door != null) {
                    door.close();
                }
            }
        }
        for (var door : doors.values()) {
            door.keep(now);
        }
    }

    private void bakeStep() {
        baked.marked(player.markHolds());
        baked.step(camera, player.holdsCamera() || player.isFramed(), player.getOverlay(), player.getPostEffects());
    }

    private void read(GameSceneStage stage, BooleanSupplier cancelled) {
        stage.prepare(player);
        while (player.isReading()) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("export cancelled");
            }
            try {
                Thread.sleep(READING_NAP_MILLIS);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new CancellationException("export cancelled");
            }
            stage.prepare(player);
        }
    }

    private void stands(SceneActor actor, Animated figure, int sequence, float seconds) {
        var body = track(actor);
        body.figure = figure;
        var animation = figure.animation();
        body.frames.plays(now, animation.getName(sequence), seconds, animation.getSeconds(sequence));
        body.sequence = sequence;
        body.moment = seconds;
    }

    private Track track(SceneActor actor) {
        var found = tracks.get(actor);
        if (found == null) {
            found = new Track(actor, now);
            tracks.put(actor, found);
            order.add(found);
        }
        return found;
    }

    private void record(GameSceneStage stage) {
        for (var actor : player.getActors()) {
            var body = track(actor);
            body.leaves = now;
            var yaw = (float) (actor.getYaw() * 2 * Math.PI / UNITS_PER_TURN);
            body.frames.place(now, actor.x(), actor.y(), actor.z(), yaw);
            if (baked != null && body.figure != null) {
                if (body.baked == null) {
                    body.baked = baked.addBody(
                            body.figure.model(),
                            body.figure,
                            body.figure.model().lowest(),
                            actor.getSpawn().index());
                    var animation = body.figure.animation();
                    baked.sequences(
                            actor.getSpawn().index(),
                            SceneOrders.byWeapon(names(animation), animation.getName(animation.getIdle())));
                    baked.lengths(actor.getSpawn().index(), lengths(animation));
                    baked.size(actor.getSpawn().index(), new Size(stage.getRadius(actor), actor.getHalfHeight()));
                }
                baked.stands(body.baked, actor.x(), actor.y(), actor.z(), yaw, body.sequence, body.moment);
            }
            var ship = actor.isAirship() ? stage.getAirshipParts() : null;
            if (ship != null) {
                record(ship, actor, body, yaw);
            }
        }
        if (player.holdsCamera() || !scene.movesCamera()) {
            var at = camera.getPosition();
            var look = camera.getPerspectiveLookDir();
            var right = camera.getRightDir();
            var view = new View(
                    now,
                    at.getX(),
                    at.getY(),
                    at.getZ(),
                    look.getX(),
                    look.getY(),
                    look.getZ(),
                    look.getY() * right.getZ() - look.getZ() * right.getY(),
                    look.getZ() * right.getX() - look.getX() * right.getZ(),
                    look.getX() * right.getY() - look.getY() * right.getX(),
                    camera.getFov());
            SceneTrack.keep(views, view, View::sameAs);
        }
    }

    private void record(AirshipModel ship, SceneActor actor, Track hull, float yaw) {
        var cos = (float) Math.cos(yaw);
        var sin = (float) Math.sin(yaw);
        for (var part : ship.parts()) {
            var body = hull.parts.computeIfAbsent(part, _ -> {
                var track = new Track(actor, now);
                track.part = part;
                order.add(track);
                return track;
            });
            body.leaves = now;
            var x = actor.x() + part.x() * cos - part.y() * sin;
            var y = actor.y() + part.x() * sin + part.y() * cos;
            body.frames.place(now, x, y, actor.z() + part.z(), yaw);
            var animated = part.animated();
            if (animated != null) {
                var animation = animated.animation();
                var idle = animation.getIdle();
                var length = animation.getSeconds(idle);
                var moment = length > 0 ? (float) (actor.getAnimationSeconds() % length) : 0;
                body.frames.plays(now, animation.getName(idle), moment, length);
                body.sequence = idle;
                body.moment = moment;
            }
            if (baked != null) {
                if (body.baked == null) {
                    body.baked = baked.addBody(
                            part.model(),
                            animated,
                            part.model().lowest(),
                            actor.getSpawn().index());
                }
                baked.stands(body.baked, x, y, actor.z() + part.z(), yaw, body.sequence, body.moment);
            }
        }
    }

    private void takeMusic() {
        var cue = player.takeMusic();
        while (cue != null) {
            playCue(cue);
            cue = player.takeMusic();
        }
    }

    private void playCue(Music cue) {
        if (baked != null) {
            baked.cue(new Cue(now, cue, null, null));
        }
        if (cue.stopMusic()) {
            endTrack();
        }
        if (cue.name().isEmpty() || cue.sound()) {
            if (baked != null && cue.sound()) {
                var clip = shared.stage.getSoundClip(cue.name());
                if (clip != null) {
                    baked.clipSeconds(cue, clip.frames() / (double) clip.sampleRate());
                }
            }
            return;
        }
        if (cue.voice()) {
            var file = MusicSystem.voiceFile(client, cue.name());
            if (file != null) {
                sounds.add(new PlayedSound(
                        cue, Kind.VOICE, file, now, VOICE_VOLUME * (float) cue.volume(), (float) cue.fadeSeconds(), 0));
                heardClip(cue, file);
            }
            return;
        }
        var file = client.resolve("music").resolve(cue.name() + ".ogg");
        if (Files.isRegularFile(file)) {
            endTrack();
            track = new PlayedSound(
                    cue,
                    Kind.MUSIC,
                    file,
                    now,
                    MUSIC_VOLUME * (float) cue.volume(),
                    (float) cue.fadeSeconds(),
                    musicFade);
            sounds.add(track);
            heardClip(cue, file);
        }
    }

    private void heardClip(Music cue, Path file) {
        if (baked != null) {
            baked.clipSeconds(cue, VorbisDecoder.seconds(file));
        }
    }

    private void endTrack() {
        if (track != null) {
            track.end(now);
            track = null;
        }
    }

    private void endSounds(double seconds) {
        for (var sound : sounds) {
            sound.end(seconds);
        }
    }

    private SceneTimeline timeline(
            SpawnModels models, double seconds, List<Sound> heard, @Nullable SceneScreen screen) {
        var catalog = shared.catalog();
        var rigs = new Rigs(models, catalog);
        var actors = new ArrayList<Actor>(order.size());
        for (var body : order) {
            if (!body.parts.isEmpty()) {
                continue;
            }
            var rig = baked == null ? rigs.of(body) : null;
            var label = rigs.getLabel(body);
            if (baked != null) {
                baked.name(body.actor.getSpawn().index(), label);
            }
            actors.add(new Actor(
                    label,
                    rig,
                    body.getLift(),
                    body.enters,
                    body.leaves,
                    body.frames.getPlaces(),
                    body.frames.getStrips()));
        }
        var played = new ArrayList<>(heard);
        for (var sound : sounds) {
            played.add(sound.getSound());
            if (baked != null) {
                var clip = baked.clipOf(sound.cue);
                baked.clip(new Clip(sound.cue, null, null, sound.start, Math.min(sound.end, sound.start + clip)));
            }
        }
        played.sort(Comparator.comparingDouble(Sound::start));
        var marks = new ArrayList<Effect>(effectOrder.size());
        for (var effect : effectOrder) {
            marks.add(new Effect(effect.name, effect.start, effect.end, effect.places));
            if (baked != null) {
                baked.effect(new PlayedEffect(
                        effect.parts,
                        effect.start,
                        effect.end,
                        effect.yaw,
                        effect.places,
                        effect.life,
                        effect.fixedLife));
            }
        }
        return new SceneTimeline(
                scene.name(),
                rate,
                seconds,
                views,
                actors,
                played,
                marks,
                screen == null ? List.of() : screen.getLayers(),
                screen == null ? List.of() : screen.getGrades(),
                doors.values().stream()
                        .map(door -> new Door(door.actor, door.keys))
                        .toList());
    }

    static final class Shared implements AutoCloseable {
        private final Path client;
        private final WorldVariant variant;
        private final float fov;
        private final Camera camera;
        private final GameScenePlayer player;
        private final GameSceneStage stage;
        private @Nullable SceneSampler running;
        private @Nullable ClientCatalog catalog;

        Shared(Path client, WorldVariant variant, TextureQuality quality) {
            this.client = client;
            this.variant = variant;
            fov = ClientFov.read(client);
            camera = new Camera(new Context(), fov, NEAR, new Vec3());
            player = new GameScenePlayer(camera);
            stage = new GameSceneStage(
                    client,
                    variant,
                    quality,
                    () -> running == null ? null : running.recorder,
                    () -> 0L,
                    (actor, figure, sequence, seconds) -> running.stands(actor, figure, sequence, seconds));
        }

        @Override
        public void close() {
            stage.close();
        }

        private void start(SceneSampler sampler) {
            running = sampler;
            camera.setPosition(0, 0, 0);
            camera.setDirection(0, 0);
            camera.setFov(fov);
        }

        private void end() {
            player.stop();
            running = null;
        }

        private ClientCatalog catalog() {
            if (catalog == null) {
                catalog = ClientCatalog.read(client, variant);
            }
            return catalog;
        }
    }

    private static final class DoorTrack {
        private static final int TRANSLATION = 12;

        final String actor;
        final List<DoorKey> keys = new ArrayList<>();
        float x;
        float y;
        float z;

        DoorTrack(String actor) {
            this.actor = actor;
        }

        void moveTo(float[] matrix, double before) {
            if (keys.isEmpty()) {
                keys.add(new DoorKey(before, 0, 0, 0));
            }
            x = matrix[TRANSLATION];
            y = matrix[TRANSLATION + 1];
            z = matrix[TRANSLATION + 2];
        }

        void close() {
            x = 0;
            y = 0;
            z = 0;
        }

        void keep(double seconds) {
            SceneTrack.keep(keys, new DoorKey(seconds, x, y, z), DoorKey::sameAs);
        }
    }

    private static final class Track {
        final SceneActor actor;
        final double enters;
        final SceneTrack frames = new SceneTrack();
        final Map<Part, Track> parts = new IdentityHashMap<>();
        double leaves;
        int sequence;
        float moment;
        Body baked;

        @Nullable
        Animated figure;

        @Nullable
        Part part;

        Track(SceneActor actor, double enters) {
            this.actor = actor;
            this.enters = enters;
            leaves = enters;
        }

        float getLift() {
            if (part != null) {
                return part.model().lowest();
            }
            return figure == null ? 0 : figure.model().lowest();
        }
    }

    private static final class PlayedSound {
        private final Music cue;
        private final Kind kind;
        private final Path file;
        private final double start;
        private final float volume;
        private final float fadeIn;
        private final float fadeOut;
        private double end = Double.NaN;

        PlayedSound(Music cue, Kind kind, Path file, double start, float volume, float fadeIn, float fadeOut) {
            this.cue = cue;
            this.kind = kind;
            this.file = file;
            this.start = start;
            this.volume = volume;
            this.fadeIn = fadeIn;
            this.fadeOut = fadeOut;
        }

        void end(double seconds) {
            if (Double.isNaN(end)) {
                end = seconds;
            }
        }

        Sound getSound() {
            return new Sound(kind, cue.name(), file, null, start, end, volume, fadeIn, fadeOut);
        }
    }

    private static final class Recorded {
        final String name;
        final EffectParts parts;
        final double start;
        final float yaw;
        final List<Place> places = new ArrayList<>();
        double end = Double.NaN;
        float life = Float.NaN;
        boolean fixedLife;

        Recorded(EffectParts parts, double start, float yaw) {
            name = parts.effect().name();
            this.parts = parts;
            this.start = start;
            this.yaw = yaw;
        }

        void end(double seconds) {
            if (Double.isNaN(end)) {
                end = seconds;
            }
        }
    }

    private final class Rigs {
        private final SpawnModels models;
        private final ClientCatalog catalog;
        private final Map<Integer, Rig> npcs = new HashMap<>();
        private final Map<SceneCharacter, Rig> people = new HashMap<>();
        private final Map<Part, Rig> parts = new IdentityHashMap<>();

        Rigs(SpawnModels models, ClientCatalog catalog) {
            this.models = models;
            this.catalog = catalog;
        }

        @Nullable
        Rig of(Track body) {
            if (body.part != null) {
                return parts.computeIfAbsent(body.part, Rigs::part);
            }
            if (body.figure == null) {
                return null;
            }
            var spawn = body.actor.getSpawn();
            if (spawn.player()) {
                return people.computeIfAbsent(SceneCharacter.of(spawn), this::person);
            }
            return npcs.computeIfAbsent(spawn.npc(), id -> npc(id, body.figure));
        }

        String getLabel(Track body) {
            var actor = body.actor;
            var spawn = actor.getSpawn();
            if (actor.isAirship()) {
                return "Airship " + spawn.index() + (body.part == null ? "" : " " + body.part.label());
            }
            if (spawn.player()) {
                return raceOf(SceneCharacter.of(spawn).figure()) + " " + spawn.index();
            }
            var named = catalog.getNpc(spawn.npc());
            var name = named == null || named.name().isBlank() ? "NPC " + spawn.npc() : named.name();
            return name + " " + spawn.index();
        }

        private @Nullable Rig npc(int id, Animated figure) {
            var appearance = models.getAppearance(id);
            if (appearance == null) {
                return null;
            }
            var named = catalog.getNpc(id);
            return new Rig(
                    new Npc(
                            id,
                            named == null ? "" : named.name(),
                            named == null ? "" : named.title(),
                            appearance.mesh(),
                            appearance.textures(),
                            figure.scale(),
                            SpawnModels.isPlayerLike(appearance)),
                    models.getOutfit(id));
        }

        private Rig person(SceneCharacter character) {
            var look = people.size() + 1;
            var label = raceOf(character.figure()) + (look == 1 ? "" : " " + look);
            var outfit = character.outfit(models.getTables());
            return new Rig(new Character(label, character.face(), outfit).npc(), outfit);
        }

        private static Rig part(Part part) {
            var npc = new Npc(0, "Airship " + part.label(), "", part.mesh(), part.textures(), 1, false);
            return new Rig(npc, null, part.animated() == null);
        }

        private static String raceOf(String figure) {
            for (var race : NpcExport.RACES) {
                if (race.figure().equalsIgnoreCase(figure)) {
                    return race.label();
                }
            }
            return figure;
        }
    }

    private final class Recorder implements EffectPreview {
        private final SilentEffectPreview world;
        private final Map<Integer, Recorded> effects = new HashMap<>();

        Recorder(SilentEffectPreview world) {
            this.world = world;
        }

        void endFinished() {
            for (var effect : effects.entrySet()) {
                if (world.isFinished(effect.getKey())) {
                    effect.getValue().end(now);
                }
            }
        }

        @Override
        public void show(EffectParts effect, Vec3 position, float yaw) {
            clear();
            add(effect, position, yaw);
        }

        @Override
        public int add(EffectParts effect, Vec3 position, float yaw) {
            var handle = world.add(effect, position, yaw);
            var recorded = new Recorded(effect, now, yaw);
            recorded.places.add(new Place(now, position.getX(), position.getY(), position.getZ(), yaw));
            effects.put(handle, recorded);
            effectOrder.add(recorded);
            return handle;
        }

        @Override
        public void move(int handle, Vec3 position) {
            world.move(handle, position);
            var recorded = effects.get(handle);
            if (recorded != null) {
                SceneTrack.keep(
                        recorded.places,
                        new Place(now, position.getX(), position.getY(), position.getZ(), 0),
                        Place::sameAs);
            }
        }

        @Override
        public void adjustLife(int handle, float seconds) {
            world.adjustLife(handle, seconds);
            var recorded = effects.get(handle);
            if (recorded != null) {
                recorded.life = seconds;
            }
        }

        @Override
        public void fixLife(int handle, float seconds) {
            world.fixLife(handle, seconds);
            var recorded = effects.get(handle);
            if (recorded != null) {
                recorded.life = seconds;
                recorded.fixedLife = true;
            }
        }

        @Override
        public void kill(int handle) {
            world.kill(handle);
        }

        @Override
        public void remove(int handle) {
            world.remove(handle);
            var recorded = effects.remove(handle);
            if (recorded != null) {
                recorded.end(now);
            }
        }

        @Override
        public void clear() {
            world.clear();
            for (var recorded : effects.values()) {
                recorded.end(now);
            }
            effects.clear();
        }

        @Override
        public void playSound(EmitterSound sound, Vec3 position) {
            world.playSound(sound, position);
            bakeSound(sound, new Vec3(position));
        }

        @Override
        public void playSound(EmitterSound sound) {
            world.playSound(sound);
            bakeSound(sound, null);
        }

        @Override
        public void stopSounds() {
            world.stopSounds();
            if (baked != null) {
                baked.soundsStop(now);
            }
        }

        @Override
        public void replay() {
            world.replay();
        }

        @Override
        public void advance(float seconds) {
            world.advance(seconds);
        }

        @Override
        public boolean isShown() {
            return world.isShown();
        }

        @Override
        public boolean isFinished() {
            return world.isFinished();
        }

        @Override
        public boolean isFinished(int handle) {
            return world.isFinished(handle);
        }

        @Override
        public int getLiveParticles() {
            return world.getLiveParticles();
        }

        @Override
        public @Nullable Bounds3f getBounds() {
            return world.getBounds();
        }

        private void bakeSound(EmitterSound sound, @Nullable Vec3 at) {
            if (baked != null) {
                baked.cue(new Cue(now, null, sound, at));
                var end = sound.looping() ? Double.POSITIVE_INFINITY : now + sound.delaySeconds() + sound.seconds();
                baked.clip(new Clip(null, sound, at, now, end));
            }
        }
    }

    private static Surroundings surroundings(
            Path client,
            WorldVariant variant,
            List<String> tiles,
            float hour,
            MapEventState events,
            BooleanSupplier cancelled) {
        var floors = new ClientFloors();
        var sounds = new ArrayList<AmbientSound>();
        var water = new ArrayList<WaterVolume>();
        var volumes = new ArrayList<AmbientVolume>();
        var emitters = new ArrayList<ParticleEmitter>();
        var packages = new DecryptedPackages(IDLE_PACKAGE_BYTES);
        var loader = new UnrealLoader(
                client,
                new DxtChainCache(new Options(false, 0)),
                new SoundClipCache(),
                true,
                TextureQuality.FULL,
                packages);
        loader.worldVariant(variant);
        try {
            for (var tile : tiles) {
                var map = loader.loadMap(tile);
                loader.trimCaches();
                if (map == null) {
                    continue;
                }
                var grid = ClientFloorGrid.build(map, cancelled);
                if (grid != null) {
                    floors.put(grid);
                }
                sounds.addAll(map.ambientSounds());
                water.addAll(map.waterVolumes());
                volumes.addAll(map.ambientVolumes());
                emitters.addAll(MapEmitters.sounding(map));
            }
        } finally {
            loader.trimCaches();
            packages.clearIdle();
        }
        return new Surroundings(GameSceneStage.ground(floors), sounds, water, volumes, emitters, hour, events);
    }

    private static List<String> names(NpcAnimation animation) {
        var names = new ArrayList<String>(animation.getSequenceCount());
        for (var sequence = 0; sequence < animation.getSequenceCount(); sequence++) {
            names.add(animation.getName(sequence));
        }
        return names;
    }

    private static Map<String, Double> lengths(NpcAnimation animation) {
        var lengths = new HashMap<String, Double>(animation.getSequenceCount());
        for (var sequence = 0; sequence < animation.getSequenceCount(); sequence++) {
            lengths.put(animation.getName(sequence), (double) animation.getSeconds(sequence));
        }
        return lengths;
    }
}
