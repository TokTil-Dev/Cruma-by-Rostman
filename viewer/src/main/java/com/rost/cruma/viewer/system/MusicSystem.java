package com.rost.cruma.viewer.system;

import com.rost.cruma.core.audio.MusicPlayer;
import com.rost.cruma.core.audio.MusicTable;
import com.rost.cruma.core.audio.OggMusicPlayer;
import com.rost.cruma.core.world.MusicVolume;
import com.rost.cruma.core.world.WorldDimensions;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.core.world.ZoneStateChoice;
import com.rost.cruma.viewer.application.UiContext;
import com.rost.cruma.viewer.application.UiContext.Rendering;
import com.rost.cruma.viewer.application.WindowContext;
import com.rost.cruma.viewer.config.Config.Viewer.World;
import com.rost.cruma.viewer.config.Config.Viewer.World.Music;
import com.rost.cruma.viewer.render.RenderingContext;
import com.rost.cruma.viewer.render.SceneAudio;
import com.rost.cruma.viewer.render.SceneAudio.Cue;
import glm_.vec3.Vec3;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.collections.api.set.primitive.MutableIntSet;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.jetbrains.annotations.Nullable;

public final class MusicSystem implements System {
    private static final Logger log = LogManager.getLogger(MusicSystem.class);
    private static final String PER_PLAYER = "_id";
    private static final float SCENE_STOP_SECONDS = .1f;
    private static final float SCENE_END_SECONDS = 2f;
    private static final float VOICE_LEVEL = 2f;
    private final Music config;
    private final Path clientPath;
    private WorldVariant variant = WorldVariant.MAIN;
    private final Supplier<Vec3> cameraPosition;
    private final Supplier<List<MusicVolume>> volumes;
    private final Rendering knobs;
    private final RandomGenerator random;
    private final MutableIntSet reportedEmptyEntries = new IntHashSet();
    private MusicTable table;
    private MusicPlayer player;

    private MusicVolume activeVolume;
    private ZoneStateChoice chosenZoneState = ZoneStateChoice.EMPTY;
    private boolean canReplay;
    private float replayWaitSeconds;
    private float idleSeconds;
    private String track = "";
    private int trackMusicId;
    private float appliedVolume = Float.NaN;
    private @Nullable SceneAudio cutScene;
    private final List<MusicPlayer> voices = new ArrayList<>();
    private Supplier<MusicPlayer> voiceLines = OggMusicPlayer::new;
    private boolean voiceStarted;
    private float trackLevel = 1f;
    private BooleanSupplier pause = () -> false;
    private boolean held;
    private boolean cutSceneHolds;
    private long cutSceneRevision;

    public MusicSystem(World worldConfig, RenderingContext renderingContext, UiContext uiContext) {
        this(
                worldConfig.music(),
                worldConfig.clientPath(),
                null,
                null,
                () -> renderingContext.camera.getPosition(),
                () -> renderingContext.scene.getMusicVolumes(),
                uiContext.rendering,
                RandomGenerator.getDefault());
        this.variant = worldConfig.variant();
        this.cutScene = renderingContext.sceneAudio;
        this.pause = renderingContext.playback::isPaused;
    }

    MusicSystem(
            Music config,
            Path clientPath,
            MusicTable table,
            MusicPlayer player,
            Supplier<Vec3> cameraPosition,
            Supplier<List<MusicVolume>> volumes,
            Rendering knobs,
            RandomGenerator random) {
        this.config = config;
        this.clientPath = clientPath;
        this.table = table;
        this.player = player;
        this.cameraPosition = cameraPosition;
        this.volumes = volumes;
        this.knobs = knobs;
        this.random = random;
    }

    static int tileMusicId(Vec3 position) {
        var tileX = (int) Math.floor(position.getX() / WorldDimensions.TILE_SIZE) + WorldDimensions.TILE_ZERO_COORD_X;
        var tileY = (int) Math.floor(position.getY() / WorldDimensions.TILE_SIZE) + WorldDimensions.TILE_ZERO_COORD_Y;
        return tileX * 100 + tileY;
    }

    public static @Nullable Path voiceFile(Path client, String name) {
        var folder = client.resolve("Voice");
        var line = name.toLowerCase(Locale.ROOT).endsWith(PER_PLAYER)
                ? name.substring(0, name.length() - PER_PLAYER.length()) + "_1"
                : name;
        var file = folder.resolve(line + "-e.ogg");
        if (Files.isRegularFile(file)) {
            return file;
        }
        var plain = folder.resolve(line + ".ogg");
        if (Files.isRegularFile(plain)) {
            return plain;
        }
        var prefix = (line + "-").toLowerCase(Locale.ROOT);
        try (var entries = Files.list(folder)) {
            return entries.filter(path -> {
                        var entry = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return entry.startsWith(prefix) && entry.endsWith(".ogg");
                    })
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException | UncheckedIOException unreadable) {
            return null;
        }
    }

    public static @Nullable MusicVolume locate(List<MusicVolume> volumes, Vec3 position) {
        MusicVolume found = null;
        for (var volume : volumes) {
            if (volume.contains(position)) {
                if (volume.isForcePlay()) {
                    return volume;
                }
                found = volume;
            }
        }
        return found;
    }

    void followCutScenes(SceneAudio scenes) {
        cutScene = scenes;
    }

    void playVoicesWith(Supplier<MusicPlayer> lines) {
        voiceLines = lines;
    }

    void followPause(BooleanSupplier paused) {
        pause = paused;
    }

    @Override
    public void start() {
        if (table == null) {
            try {
                table = MusicTable.load(clientPath, variant);
                log.info("Loaded musicinfo.dat: {} entries", table.size());
            } catch (IOException | RuntimeException e) {
                log.warn("Cannot read musicinfo.dat; music is off: {}", e.toString());
                table = MusicTable.parse(new byte[] {0, 0, 0, 0});
            }
        }
        if (player == null) {
            player = new OggMusicPlayer();
        }
    }

    @Override
    public void frameEnd(long frameTime) {
        if (player == null || !player.isAvailable()) {
            return;
        }
        var volume = Math.clamp(knobs.musicVolume.get() / 100f, 0f, 1f);
        if (hold()) {
            return;
        }
        var level = volume * trackLevel;
        if (level != appliedVolume) {
            player.setVolume(level);
            appliedVolume = level;
        }
        if (!knobs.musicEnabled) {
            if (player.isPlaying() || activeVolume != null) {
                player.stop(fadeSeconds());
                activeVolume = null;
                canReplay = false;
                replayWaitSeconds = 0f;
                idleSeconds = 0f;
                track = "";
            }
            knobs.stats.musicStatus = "off";
            return;
        }
        if (playCutScene(volume)) {
            knobs.stats.musicStatus = player.isPlaying() ? track + "  (cut scene)" : "cut scene";
            return;
        }
        var seconds = frameTime / 1000f;
        var playing = player.isPlaying();
        var position = cameraPosition.get();
        var containing = locate(volumes.get(), position);
        var zoneState = zoneState();
        var night = isNight(knobs.getTimeOfDay());
        if (!playing) {
            if (containing != null && containing.isLoop()) {
                canReplay = true;
                replayWaitSeconds = 0f;
            } else if (!canReplay) {
                replayWaitSeconds += seconds;
                if (replayWaitSeconds >= knobs.musicWaitSeconds.get()) {
                    canReplay = true;
                    replayWaitSeconds = 0f;
                }
            }
        }
        if (containing != null) {
            var musicId = containing.getMusicIdAt(night, zoneState);
            if (containing != activeVolume
                    || !zoneState.equals(chosenZoneState)
                            && musicId != containing.getMusicIdAt(night, chosenZoneState)) {
                if (playing) {
                    player.stop(fadeSeconds());
                }
                startTrack(musicId);
            } else if (canReplay && !playing) {
                startTrack(musicId);
            }
        }
        activeVolume = containing;
        chosenZoneState = zoneState;
        if (containing != null) {
            idleSeconds = 0f;
        } else if (!playing) {
            idleSeconds += seconds;
            if (idleSeconds >= knobs.musicIdleWaitSeconds.get()) {
                idleSeconds = 0f;
                startTrack(tileMusicId(position));
            }
        }
        knobs.stats.musicStatus = statusText(player.isPlaying());
    }

    @Override
    public void stop(WindowContext windowContext) {
        if (player != null) {
            player.close();
        }
        voices.forEach(MusicPlayer::close);
    }

    boolean isNight(float hour) {
        var start = config.nightStartHour();
        var end = config.nightEndHour();
        return start <= end ? hour >= start && hour < end : hour >= start || hour < end;
    }

    private boolean playCutScene(float volume) {
        var scene = cutScene;
        if (scene == null) {
            return false;
        }
        if (scene.getRevision() != cutSceneRevision) {
            cutSceneRevision = scene.getRevision();
            endCutScene();
        }
        var cue = scene.takeCue();
        while (cue != null) {
            playCue(cue, volume);
            cue = scene.takeCue();
        }
        if (!scene.isPlaying()) {
            endCutScene();
        }
        return cutSceneHolds;
    }

    private void endCutScene() {
        var scene = cutScene;
        var fade = held ? 0f : scene != null && scene.hasRunOut() ? SCENE_END_SECONDS : SCENE_STOP_SECONDS;
        if (voiceStarted) {
            voiceStarted = false;
            for (var line : voices) {
                if (line.isPlaying()) {
                    line.stop(fade);
                }
            }
        }
        if (cutSceneHolds) {
            cutSceneHolds = false;
            player.stop(fade);
            track = "";
        }
        trackLevel = 1f;
    }

    private boolean hold() {
        var paused = pause.getAsBoolean();
        if (paused != held) {
            held = paused;
            player.pause(held);
            for (var line : voices) {
                line.pause(held);
            }
        }
        var scene = cutScene;
        if (held && (scene == null || !scene.isPlaying())) {
            endCutScene();
        }
        return held;
    }

    private void playCue(Cue heard, float volume) {
        var cue = heard.music();
        if (cue.stopMusic()) {
            cutSceneHolds = true;
            activeVolume = null;
            if (player.isPlaying()) {
                player.stop(fadeSeconds());
            }
        }
        if (cue.name().isEmpty() || cue.sound()) {
            return;
        }
        if (cue.voice()) {
            var file = voiceFile(clientPath, cue.name());
            if (file == null) {
                log.warn("No file for the voice line {} in {}", cue.name(), clientPath.resolve("Voice"));
                return;
            }
            var line = freeVoice();
            line.setVolume(Math.min(1f, volume * VOICE_LEVEL) * (float) cue.volume());
            line.play(file, (float) cue.fadeSeconds(), 0f, heard.from());
            voiceStarted = true;
            return;
        }
        var file = clientPath.resolve("music").resolve(cue.name() + ".ogg");
        if (Files.isRegularFile(file)) {
            cutSceneHolds = true;
            activeVolume = null;
            trackLevel = (float) cue.volume();
            player.setVolume(volume * trackLevel);
            appliedVolume = volume * trackLevel;
            player.play(file, (float) cue.fadeSeconds(), fadeSeconds(), heard.from());
            track = cue.name();
        }
    }

    private MusicPlayer freeVoice() {
        for (var line : voices) {
            if (!line.isPlaying()) {
                return line;
            }
        }
        var line = voiceLines.get();
        voices.add(line);
        return line;
    }

    private void startTrack(int musicId) {
        var playlist = table.getPlaylist(musicId);
        if (playlist.isEmpty()) {
            if (reportedEmptyEntries.add(musicId)) {
                log.info("Music {} has no tracks in musicinfo.dat", musicId);
            }
            return;
        }
        var name = playlist.get(playlist.size() == 1 ? 0 : random.nextInt(playlist.size()));
        player.play(clientPath.resolve("music").resolve(name + ".ogg"), 0f, fadeSeconds());
        track = name;
        trackMusicId = musicId;
        canReplay = false;
        replayWaitSeconds = 0f;
    }

    private String statusText(boolean playing) {
        if (playing) {
            var nowPlaying = track + "  (music " + trackMusicId + ")";
            return activeVolume == null ? nowPlaying + " · finishing outside music zone" : nowPlaying;
        }
        if (activeVolume == null) {
            return "no music volume here";
        }
        var musicId = activeVolume.getMusicIdAt(isNight(knobs.getTimeOfDay()), zoneState());
        if (canReplay) {
            return "music " + musicId + " has no tracks";
        }
        return "replay in " + Math.round(knobs.musicWaitSeconds.get() - replayWaitSeconds) + " s  (music " + musicId
                + ")";
    }

    private ZoneStateChoice zoneState() {
        return knobs.gameEventStates == null ? ZoneStateChoice.EMPTY : knobs.gameEventStates.zoneStates();
    }

    private float fadeSeconds() {
        return Math.max(0f, knobs.musicFadeSeconds.get());
    }
}
