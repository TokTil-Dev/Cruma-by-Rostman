package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.audio.SoundClip;
import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.npc.ClientCatalog;
import com.rost.cruma.core.unreal.SkillEffects.Skill;
import com.rost.cruma.core.world.EffectParts;
import com.rost.cruma.core.world.EmitterSound;
import com.rost.cruma.core.world.GameScenes.Music;
import com.rost.cruma.core.world.NpcAnimation;
import com.rost.cruma.core.world.NpcAnimation.Pose;
import com.rost.cruma.viewer.render.GameScenePlayer.EffectCue;
import com.rost.cruma.viewer.render.GameScenePlayer.SceneActor;
import com.rost.cruma.viewer.render.GameScenePlayer.SkillCue;
import com.rost.cruma.viewer.render.api.EffectPreview;
import com.rost.cruma.viewer.spawn.SpawnModels.Animated;
import com.rost.cruma.viewer.ui.SkillShow.Moment;
import com.rost.cruma.viewer.ui.SkillShow.Pawn;
import com.rost.cruma.viewer.ui.SkillShow.Sound;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.collections.api.list.primitive.MutableIntList;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.jetbrains.annotations.Nullable;

final class SceneCasts {
    static final float LONGEST_SECONDS = 15f;

    private static final float SOUND_RADIUS = 500f;
    private static final SkillShow.Cast NO_BODY = new SkillShow.Cast(0f, List.of(), -1, "");
    private static final int UNITS_PER_TURN = 65536;
    private static final float LATER_ORDER_SECONDS = .05f;
    private static final Logger log = LogManager.getLogger(SceneCasts.class);

    private final Reads reads;
    private final Supplier<@Nullable EffectPreview> previews;
    private final List<Running> running = new ArrayList<>();
    private final List<Placed> placed = new ArrayList<>();
    private final Map<SceneActor, Supplier<Pose>> poses = new IdentityHashMap<>();
    private @Nullable RandomGenerator chance;
    private boolean active;

    SceneCasts(Reads reads, Supplier<@Nullable EffectPreview> previews) {
        this.reads = reads;
        this.previews = previews;
    }

    void cast(SkillCue cue, @Nullable Animated caster, @Nullable Animated target) {
        var skill = reads.getSkill(cue.skill());
        var preview = previews.get();
        if (skill == null || preview == null) {
            return;
        }
        var instant = skill.instant();
        if (!instant) {
            cue.caster().stopMoving();
            interrupt(actor -> actor == cue.caster());
        }
        var casts = instant
                ? List.of(NO_BODY)
                : reads.getCasts(skill, cue.caster().getSpawn().npc());
        var picks = chance;
        var show = picks == null
                ? new SkillShow(skill.skill(), skill.parts(), casts)
                : new SkillShow(skill.skill(), skill.parts(), casts, picks);
        show.setSounds(skill.sounds());
        var from = new Body(cue.caster(), caster);
        var to = cue.target() == cue.caster() ? from : new Body(cue.target(), target);
        show.start(from.pawn, to.pawn);
        running.add(new Running(show, new CastEffects(preview), from, to, instant));
        active = true;
    }

    void effect(EffectCue cue) {
        var parts = reads.getEffect(cue.effect());
        var preview = previews.get();
        if (parts == null || preview == null) {
            log.debug("The client has no effect {} to draw", cue.effect());
            return;
        }
        var on = cue.on();
        var at = on == null
                ? new Vec3((float) cue.x(), (float) cue.y(), (float) cue.z())
                : new Vec3(on.x(), on.y(), on.z());
        var handle = preview.add(parts, new Vec3(at), on == null ? 0f : yaw(on));
        placed.add(new Placed(handle, on, cue.loop(), at));
        active = true;
    }

    void entranceEffect(String name, SceneActor pawn, float feet, float sizeScale) {
        var parts = reads.getEffect(name);
        var preview = previews.get();
        if (parts == null || preview == null) {
            log.debug("The client has no effect {} to draw", name);
            return;
        }
        var at = new Vec3(pawn.x(), pawn.y(), feet);
        var handle = preview.add(parts, new Vec3(at), yaw(pawn), sizeScale);
        placed.add(new Placed(handle, null, false, at));
        active = true;
    }

    void sound(Music cue) {
        var clip = reads.getSound(cue.name());
        var preview = previews.get();
        if (clip == null || preview == null) {
            log.debug("The client has no sound {}", cue.name());
            return;
        }
        preview.playSound(
                new EmitterSound(clip, SOUND_RADIUS, (float) cue.volume(), 1f, 1f, false, false, 0f, 0f, 0f, 0f));
        active = true;
    }

    void sound(String name, Vec3 at, float volume, float radius) {
        var clip = reads.getSound(name);
        var preview = previews.get();
        if (clip == null || preview == null) {
            log.debug("The client has no sound {}", name);
            return;
        }
        preview.playSound(
                new EmitterSound(
                        clip, radius > 0 ? radius : SOUND_RADIUS, volume / 255f, 1f, 1f, false, false, 0f, 0f, 0f, 0f),
                new Vec3(at));
        active = true;
    }

    @Nullable
    Moment findMoment(SceneActor actor, NpcAnimation animation) {
        for (var index = running.size() - 1; index >= 0; index--) {
            var cast = running.get(index);
            if (cast.caster.actor == actor && !cast.instant) {
                if (!actor.getAnimation().isEmpty()
                        && actor.getAnimationSeconds() + LATER_ORDER_SECONDS < cast.show.getTime()) {
                    return null;
                }
                var moment = cast.show.getCasterMoment(animation);
                return cast.show.isCasterBusy() ? moment : null;
            }
        }
        return null;
    }

    void left(Collection<SceneActor> standing) {
        interrupt(actor -> !standing.contains(actor));
    }

    void posed(SceneActor actor, Pose pose) {
        poses.put(actor, () -> pose);
    }

    void posed(SceneActor actor, Supplier<Pose> pose) {
        poses.put(actor, pose);
    }

    void setChance(@Nullable RandomGenerator picks) {
        chance = picks;
    }

    void update(float seconds) {
        if (seconds <= 0f) {
            return;
        }
        for (var iterator = running.iterator(); iterator.hasNext(); ) {
            var cast = iterator.next();
            cast.caster.follow();
            cast.target.follow();
            cast.show.update(cast.effects, seconds, false);
            if (cast.over()) {
                cast.effects.clear();
                iterator.remove();
            }
        }
        var preview = previews.get();
        if (preview == null) {
            return;
        }
        for (var iterator = placed.iterator(); iterator.hasNext(); ) {
            var effect = iterator.next();
            if (!effect.loop() && preview.isFinished(effect.handle())) {
                preview.remove(effect.handle());
                iterator.remove();
            } else {
                effect.follow(preview);
            }
        }
    }

    void stop() {
        poses.clear();
        if (!active) {
            return;
        }
        active = false;
        for (var cast : running) {
            cast.effects.clear();
        }
        running.clear();
        var preview = previews.get();
        if (preview != null) {
            for (var effect : placed) {
                preview.remove(effect.handle());
            }
            preview.stopSounds();
        }
        placed.clear();
    }

    private void interrupt(Predicate<SceneActor> ended) {
        for (var iterator = running.iterator(); iterator.hasNext(); ) {
            var cast = iterator.next();
            if (!cast.instant && cast.show.getTime() < cast.show.getHitSeconds() && ended.test(cast.caster.actor)) {
                cast.effects.clear();
                iterator.remove();
            }
        }
    }

    private static float yaw(SceneActor actor) {
        return (float) (actor.getYaw() * Math.PI * 2 / UNITS_PER_TURN);
    }

    interface Reads {
        @Nullable
        SceneSkill getSkill(int id);

        List<SkillShow.Cast> getCasts(SceneSkill skill, int npc);

        @Nullable
        EffectParts getEffect(String name);

        @Nullable
        SoundClip getSound(String name);
    }

    record SceneSkill(
            int id, ClientCatalog.Cast cast, Skill skill, Map<String, EffectParts> parts, List<Sound> sounds) {
        static final int INSTANT = 0;

        boolean instant() {
            return cast.style() == INSTANT;
        }
    }

    private record Placed(int handle, @Nullable SceneActor on, boolean loop, Vec3 at) {
        void follow(EffectPreview preview) {
            if (on != null && (on.x() != at.getX() || on.y() != at.getY() || on.z() != at.getZ())) {
                at.setX(on.x());
                at.setY(on.y());
                at.setZ(on.z());
                preview.move(handle, at);
            }
        }
    }

    private record Running(SkillShow show, CastEffects effects, Body caster, Body target, boolean instant) {
        boolean over() {
            if (!show.hasBegunAll()) {
                return false;
            }
            return effects.isFinished() || !instant && show.getTime() - show.getLastStart() >= LONGEST_SECONDS;
        }
    }

    private final class Body {
        final SceneActor actor;
        final Vec3 feet;
        final Pawn pawn;

        Body(SceneActor actor, @Nullable Animated figure) {
            this.actor = actor;
            feet = new Vec3(actor.x(), actor.y(), actor.getFeet());
            pawn = figure == null
                    ? new Pawn(feet, yaw(actor), null, Map::of, actor.getHalfHeight())
                    : new Pawn(
                            feet, yaw(actor), figure.model(), () -> bones(figure), actor.getHalfHeight(), this::axes);
        }

        void follow() {
            feet.setX(actor.x());
            feet.setY(actor.y());
            feet.setZ(actor.getFeet());
        }

        private Map<String, float[]> axes() {
            var pose = poses.get(actor);
            return pose == null ? Map.of() : pose.get().axes();
        }

        private Map<String, Vec3> bones(Animated figure) {
            var pose = poses.get(actor);
            return pose == null ? figure.model().bones() : figure.bones(pose.get());
        }
    }

    private static final class CastEffects implements EffectPreview {
        private final EffectPreview preview;
        private final MutableIntList handles = new IntArrayList();

        CastEffects(EffectPreview preview) {
            this.preview = preview;
        }

        @Override
        public void show(EffectParts effect, Vec3 position, float yaw) {
            clear();
            add(effect, position, yaw);
        }

        @Override
        public int add(EffectParts effect, Vec3 position, float yaw) {
            var handle = preview.add(effect, position, yaw);
            handles.add(handle);
            return handle;
        }

        @Override
        public int addTurned(EffectParts effect, Vec3 position, Vec3 rotation) {
            var handle = preview.addTurned(effect, position, rotation);
            handles.add(handle);
            return handle;
        }

        @Override
        public void move(int handle, Vec3 position) {
            preview.move(handle, position);
        }

        @Override
        public void adjustLife(int handle, float seconds) {
            preview.adjustLife(handle, seconds);
        }

        @Override
        public void fixLife(int handle, float seconds) {
            preview.fixLife(handle, seconds);
        }

        @Override
        public void turn(int handle, Vec3 rotation) {
            preview.turn(handle, rotation);
        }

        @Override
        public void fly(int handle, Vec3 rotation) {
            preview.fly(handle, rotation);
        }

        @Override
        public void remove(int handle) {
            preview.remove(handle);
            handles.remove(handle);
        }

        @Override
        public void clear() {
            handles.forEach(preview::remove);
            handles.clear();
        }

        @Override
        public void playSound(EmitterSound sound, Vec3 position) {
            preview.playSound(sound, position);
        }

        @Override
        public void playSound(EmitterSound sound) {
            preview.playSound(sound);
        }

        @Override
        public void stopSounds() {
            preview.stopSounds();
        }

        @Override
        public void replay() {
            throw new UnsupportedOperationException("A cast of a scene plays once");
        }

        @Override
        public void advance(float seconds) {
            throw new UnsupportedOperationException("A cast of a scene never seeks");
        }

        @Override
        public boolean isShown() {
            return !handles.isEmpty();
        }

        @Override
        public boolean isFinished() {
            return handles.allSatisfy(preview::isFinished);
        }

        @Override
        public boolean isFinished(int handle) {
            return preview.isFinished(handle);
        }

        @Override
        public int getLiveParticles() {
            return preview.getLiveParticles();
        }

        @Override
        public @Nullable Bounds3f getBounds() {
            return preview.getBounds();
        }
    }
}
