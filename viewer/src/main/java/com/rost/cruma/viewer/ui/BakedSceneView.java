package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.world.GameScenes.Music;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.viewer.render.SceneAudio;
import com.rost.cruma.viewer.render.SceneDoors.Moved;
import com.rost.cruma.viewer.render.SceneOverlay;
import com.rost.cruma.viewer.render.ScenePostEffects;
import com.rost.cruma.viewer.render.SpawnOverlayState;
import com.rost.cruma.viewer.render.SpawnOverlayState.LivePose;
import com.rost.cruma.viewer.render.SpawnOverlayState.ModelInstance;
import com.rost.cruma.viewer.render.api.EffectPreview;
import com.rost.cruma.viewer.ui.BakedScene.Body;
import com.rost.cruma.viewer.ui.BakedScene.Clip;
import com.rost.cruma.viewer.ui.BakedScene.Cue;
import com.rost.cruma.viewer.ui.BakedScene.PlayedEffect;
import com.rost.cruma.viewer.ui.BakedScene.Quiet;
import glm_.vec3.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

public final class BakedSceneView {
    private static final int EFFECT_FRAMES = 60;
    private static final double EFFECT_STEP = 1.0 / EFFECT_FRAMES;
    private static final double SAME_MOMENT = 1e-6;
    private static final double SHORT_WAY = .25;
    private static final Music MAP_MUSIC_HELD = new Music("", true, false, false, false, false, 1, 0);

    private final SpawnOverlayState spawns;
    private final Supplier<@Nullable EffectPreview> effects;
    private final Consumer<Moved> doors;
    private final ScenePostEffects posts = new ScenePostEffects();
    private final Audio audio = new Audio();
    private final Consumer<@Nullable SceneAudio> speakers;
    private final Map<Body, LivePose> poses = new IdentityHashMap<>();
    private final Map<String, float[]> movedDoors = new HashMap<>();
    private final List<Live> live = new ArrayList<>();
    private @Nullable BakedScene shown;
    private double seconds = Double.NaN;
    private @Nullable Nudge nudge;
    private List<ModelInstance> cast = List.of();
    private @Nullable SceneOverlay overlay;
    private boolean postsOn;
    private double effectsAt = Double.NaN;
    private int nextEffect;
    private int nextCue;
    private boolean heard;

    BakedSceneView(
            SpawnOverlayState spawns,
            Supplier<@Nullable EffectPreview> effects,
            Consumer<Moved> doors,
            Consumer<@Nullable SceneAudio> speakers) {
        this.spawns = spawns;
        this.effects = effects;
        this.doors = doors;
        this.speakers = speakers;
    }

    public record Nudge(int index, float dx, float dy, float dz, float turn, float aboutX, float aboutY) {}

    @Nullable
    ScenePostEffects getPosts() {
        return postsOn ? posts : null;
    }

    public @Nullable SceneOverlay getOverlay() {
        return overlay;
    }

    @Nullable
    Scene getScene() {
        return shown == null ? null : shown.getScene();
    }

    double getEffectsAt() {
        return effectsAt;
    }

    void show(BakedScene baked, double moment, boolean dragging, boolean playing) {
        show(baked, moment, dragging, playing, null);
    }

    void show(BakedScene baked, double moment, boolean dragging, boolean playing, @Nullable Nudge moved) {
        var changed = baked != shown;
        if (changed) {
            poses.clear();
            effectsAt = Double.NaN;
        }
        if (changed || Math.abs(moment - seconds) > SAME_MOMENT || !Objects.equals(moved, nudge)) {
            shown = baked;
            seconds = moment;
            nudge = moved;
            stand(baked, moment);
            overlay = baked.getOverlay(moment);
            moveDoors(baked, moment);
            showPosts(baked, moment);
        }
        spawns.cast = cast;
        var starts = playing && (!heard || changed);
        if (!playing) {
            silence();
        }
        if (dragging) {
            hideEffects();
        } else if (!Double.isNaN(effectsAt) && moment >= effectsAt && (playing || moment - effectsAt < SHORT_WAY)) {
            runEffects(baked, moment, playing && !starts);
        } else if (Double.isNaN(effectsAt) || Math.abs(effectsAt - moment) > SAME_MOMENT) {
            catchUp(baked, moment);
        }
        if (starts) {
            listen(baked, moment);
        }
    }

    void hide() {
        if (shown == null && cast.isEmpty()) {
            return;
        }
        silence();
        speakers.accept(null);
        hideEffects();
        var preview = effects.get();
        if (preview != null) {
            preview.hold(false);
        }
        spawns.cast = List.of();
        cast = List.of();
        overlay = null;
        postsOn = false;
        shown = null;
        seconds = Double.NaN;
        nudge = null;
        poses.clear();
        restoreDoors();
    }

    private void stand(BakedScene baked, double moment) {
        var stands = baked.getStands(moment);
        var bodies = new ArrayList<ModelInstance>(stands.size());
        for (var stand : stands) {
            var body = stand.body();
            var figure = body.getFigure();
            LivePose pose = null;
            if (figure != null) {
                pose = poses.computeIfAbsent(
                        body, _ -> new LivePose(body.getModel().vertices()));
                pose.set(figure.vertices(figure.animation().getPose(stand.sequence(), stand.seconds())));
            }
            var x = stand.x();
            var y = stand.y();
            var z = stand.z();
            var yaw = stand.yaw();
            var moved = nudge;
            if (moved != null && moved.index() == body.getIndex()) {
                var cos = (float) Math.cos(moved.turn());
                var sin = (float) Math.sin(moved.turn());
                var aroundX = x - moved.aboutX();
                var aroundY = y - moved.aboutY();
                x = moved.aboutX() + aroundX * cos - aroundY * sin + moved.dx();
                y = moved.aboutY() + aroundX * sin + aroundY * cos + moved.dy();
                z += moved.dz();
                yaw += moved.turn();
            }
            bodies.add(new ModelInstance(body.getModel(), x, y, z - body.getLift(), yaw, 1f, pose));
        }
        cast = List.copyOf(bodies);
    }

    private void showPosts(BakedScene baked, double moment) {
        var post = baked.post(moment);
        postsOn = post != null;
        if (post != null) {
            posts.show(post.blurAlpha(), post.color(), post.colorShare());
        }
    }

    private void moveDoors(BakedScene baked, double moment) {
        var wanted = baked.getDoors(moment);
        var moved = new HashMap<String, float[]>();
        for (var door : wanted.entrySet()) {
            if (movedDoors.get(door.getKey()) != door.getValue()) {
                moved.put(door.getKey(), door.getValue());
            }
        }
        var restored = new HashSet<>(movedDoors.keySet());
        restored.removeAll(wanted.keySet());
        if (!moved.isEmpty() || !restored.isEmpty()) {
            movedDoors.clear();
            movedDoors.putAll(wanted);
            doors.accept(new Moved(moved, restored));
        }
    }

    private void restoreDoors() {
        if (!movedDoors.isEmpty()) {
            doors.accept(new Moved(Map.of(), new HashSet<>(movedDoors.keySet())));
            movedDoors.clear();
        }
    }

    private void hideEffects() {
        if (!Double.isNaN(effectsAt) || !live.isEmpty()) {
            var preview = effects.get();
            if (preview != null) {
                preview.clear();
                preview.stopSounds();
            }
            live.clear();
        }
        effectsAt = Double.NaN;
    }

    private void catchUp(BakedScene baked, double moment) {
        hideEffects();
        var preview = effects.get();
        if (preview != null) {
            preview.hold(true);
        }
        nextEffect = baked.getEffects().size();
        var oldest = moment;
        for (var index = baked.getEffects().size() - 1; index >= 0; index--) {
            var effect = baked.getEffects().get(index);
            if (effect.alive(moment)) {
                oldest = Math.min(oldest, effect.start());
                nextEffect = index;
            } else if (effect.start() > moment) {
                nextEffect = index;
            }
        }
        nextCue = 0;
        while (nextCue < baked.getCues().size() && baked.getCues().get(nextCue).seconds() <= moment) {
            nextCue++;
        }
        effectsAt = oldest;
        runEffects(baked, moment, false);
        effectsAt = moment;
    }

    private void runEffects(BakedScene baked, double moment, boolean sounds) {
        var preview = effects.get();
        if (preview != null) {
            while (effectsAt < moment - SAME_MOMENT) {
                var step = Math.min(EFFECT_STEP, moment - effectsAt);
                effectsAt += step;
                placeEffects(baked, preview, effectsAt);
                preview.advance((float) step);
            }
            placeEffects(baked, preview, moment);
        }
        effectsAt = moment;
        if (sounds) {
            sound(baked, moment, preview);
        }
    }

    private void placeEffects(BakedScene baked, EffectPreview preview, double moment) {
        var list = baked.getEffects();
        while (nextEffect < list.size() && list.get(nextEffect).start() <= moment) {
            var effect = list.get(nextEffect++);
            if (!effect.alive(moment)) {
                continue;
            }
            var place = effect.place(moment);
            var at = new Vec3(place.x(), place.y(), place.z());
            var handle = preview.add(effect.parts(), at, effect.yaw());
            if (!Float.isNaN(effect.life()) && effect.fixedLife()) {
                preview.fixLife(handle, effect.life());
            } else if (!Float.isNaN(effect.life())) {
                preview.adjustLife(handle, effect.life());
            }
            live.add(new Live(effect, handle, at));
        }
        for (var iterator = live.iterator(); iterator.hasNext(); ) {
            var running = iterator.next();
            if (!running.effect.alive(moment)) {
                preview.remove(running.handle);
                iterator.remove();
                continue;
            }
            var place = running.effect.place(moment);
            if (place.x() != running.at.getX() || place.y() != running.at.getY() || place.z() != running.at.getZ()) {
                running.at = new Vec3(place.x(), place.y(), place.z());
                preview.move(running.handle, running.at);
            }
        }
    }

    private void sound(BakedScene baked, double moment, @Nullable EffectPreview preview) {
        var cues = baked.getCues();
        while (nextCue < cues.size() && cues.get(nextCue).seconds() <= moment) {
            var cue = cues.get(nextCue++);
            if (cue.music() != null) {
                audio.add(cue.music());
            } else if (preview != null && cue.sound() != null) {
                play(preview, cue);
            }
        }
    }

    private void listen(BakedScene baked, double moment) {
        heard = true;
        audio.start();
        speakers.accept(audio);
        var quiet = baked.getQuiet(moment);
        audio.quiet(quiet);
        var preview = effects.get();
        if (preview != null) {
            preview.stopSounds();
        }
        for (var sounding : baked.getSounding(moment)) {
            var clip = sounding.clip();
            if (clip.music() != null) {
                audio.add(clip.music(), sounding.from());
            } else if (preview != null && clip.sound() != null) {
                play(preview, clip, sounding.from());
            }
        }
        nextCue = 0;
        var cues = baked.getCues();
        while (nextCue < cues.size() && cues.get(nextCue).seconds() <= moment) {
            nextCue++;
        }
    }

    private void silence() {
        if (!heard) {
            return;
        }
        heard = false;
        audio.stop();
        speakers.accept(audio);
        var preview = effects.get();
        if (preview != null) {
            preview.stopSounds();
        }
    }

    private static final class Live {
        final PlayedEffect effect;
        final int handle;
        Vec3 at;

        Live(PlayedEffect effect, int handle, Vec3 at) {
            this.effect = effect;
            this.handle = handle;
            this.at = at;
        }
    }

    private static final class Audio implements SceneAudio {
        private final ArrayDeque<Cue> cues = new ArrayDeque<>();
        private boolean playing;
        private boolean stopsAmbient;
        private boolean stopsEffects;
        private long revision;

        void start() {
            playing = true;
            stopsAmbient = false;
            stopsEffects = false;
            revision++;
        }

        void stop() {
            playing = false;
            cues.clear();
        }

        void add(Music cue) {
            add(cue, 0);
        }

        void add(Music cue, double from) {
            cues.add(new Cue(cue, from));
            stopsAmbient |= cue.stopAmbient();
            stopsEffects |= cue.stopEffects();
        }

        void quiet(Quiet quiet) {
            stopsAmbient = quiet.ambient();
            stopsEffects = quiet.effects();
            if (quiet.music()) {
                cues.add(new Cue(MAP_MUSIC_HELD, 0));
            }
        }

        @Override
        public @Nullable Music takeMusic() {
            var cue = cues.poll();
            return cue == null ? null : cue.music();
        }

        @Override
        public @Nullable Cue takeCue() {
            return cues.poll();
        }

        @Override
        public boolean isPlaying() {
            return playing;
        }

        @Override
        public boolean hasRunOut() {
            return false;
        }

        @Override
        public long getRevision() {
            return revision;
        }

        @Override
        public boolean stopsAmbient() {
            return playing && stopsAmbient;
        }

        @Override
        public boolean stopsEffects() {
            return playing && stopsEffects;
        }
    }

    private static void play(EffectPreview preview, Cue cue) {
        if (cue.at() == null) {
            preview.playSound(cue.sound());
        } else {
            preview.playSound(cue.sound(), cue.at());
        }
    }

    private static void play(EffectPreview preview, Clip clip, double since) {
        var sound = clip.sound();
        var delay = sound.delaySeconds();
        preview.playSound(sound.withDelay((float) Math.max(0, delay - since)), clip.at(), Math.max(0, since - delay));
    }
}
