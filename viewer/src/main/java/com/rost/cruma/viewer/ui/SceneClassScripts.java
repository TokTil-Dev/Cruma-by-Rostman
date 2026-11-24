package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.npc.script.ScriptPawn;
import com.rost.cruma.core.npc.script.ScriptPawn.Host;
import com.rost.cruma.core.npc.script.ScriptValues.Coords;
import com.rost.cruma.core.npc.script.ScriptValues.Rotator;
import com.rost.cruma.core.npc.script.ScriptValues.Vector;
import com.rost.cruma.core.npc.script.ScriptedClass;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.SceneRig;
import com.rost.cruma.viewer.render.GameScenePlayer.SceneActor;
import com.rost.cruma.viewer.render.api.EffectPreview;
import com.rost.cruma.viewer.spawn.ScriptEffects;
import com.rost.cruma.viewer.spawn.ScriptEffects.Body;
import com.rost.cruma.viewer.spawn.ScriptEffects.Bone;
import com.rost.cruma.viewer.ui.SceneCasts.Reads;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.jetbrains.annotations.Nullable;

final class SceneClassScripts {
    private static final double RADIANS_PER_UNIT = Math.PI * 2 / 65536;

    private final Reads reads;
    private final Supplier<@Nullable EffectPreview> previews;
    private final Map<SceneActor, Scripted> pawns = new IdentityHashMap<>();
    private final List<Integer> orphans = new ArrayList<>();
    private @Nullable RandomGenerator chance;

    SceneClassScripts(Reads reads, Supplier<@Nullable EffectPreview> previews) {
        this.reads = reads;
        this.previews = previews;
    }

    void setChance(@Nullable RandomGenerator picks) {
        chance = picks;
    }

    void arrive(
            SceneActor actor,
            ScriptedClass type,
            @Nullable SceneRig rig,
            float collisionHeight,
            float collisionRadius) {
        var scripted = new Scripted(actor, type, rig, collisionHeight, collisionRadius);
        pawns.put(actor, scripted);
        scripted.pawn.postBeginPlay();
        scripted.pawn.postSetPawnResource();
        scripted.effects.settle(previews.get());
    }

    boolean runs(SceneActor actor) {
        return pawns.containsKey(actor);
    }

    void playing(SceneActor actor, String shown, float seconds, float length, boolean once) {
        var scripted = pawns.get(actor);
        if (scripted == null) {
            return;
        }
        if (actor.getEnds() != scripted.ends) {
            scripted.ends = actor.getEnds();
            if (!scripted.ended) {
                scripted.ended = true;
                scripted.pawn.animEnd(0);
            }
        }
        if (actor.getPlays() != scripted.plays) {
            scripted.plays = actor.getPlays();
            var ordered = actor.getAnimation();
            if (!ordered.isEmpty()) {
                scripted.ended = false;
                if (ordered.equalsIgnoreCase(shown)) {
                    scripted.sequence = shown;
                }
                scripted.pawn.animBegin(ordered);
            }
        }
        if (!shown.equalsIgnoreCase(scripted.sequence)) {
            scripted.sequence = shown;
            scripted.ended = false;
            scripted.pawn.animBegin(shown);
        }
        scripted.seconds = seconds;
        var rig = scripted.rig;
        if (rig != null) {
            var index = rig.findSequence(shown);
            scripted.rigSequence = index >= 0 ? index : rig.getIdle();
        }
        if (once && !scripted.ended && seconds >= length) {
            scripted.ended = true;
            scripted.pawn.animEnd(0);
        }
        scripted.effects.settle(previews.get());
    }

    void died(SceneActor actor) {
        var scripted = pawns.get(actor);
        if (scripted != null) {
            scripted.pawn.notifyDie();
            scripted.effects.settle(previews.get());
        }
    }

    void left(Collection<SceneActor> standing) {
        var preview = previews.get();
        for (var iterator = pawns.values().iterator(); iterator.hasNext(); ) {
            var scripted = iterator.next();
            if (standing.contains(scripted.actor)) {
                continue;
            }
            iterator.remove();
            scripted.pawn.destroyed();
            scripted.effects.settle(preview);
            orphans.addAll(scripted.effects.leave(preview));
        }
    }

    void update(float seconds) {
        var preview = previews.get();
        for (var scripted : List.copyOf(pawns.values())) {
            if (seconds > 0) {
                scripted.pawn.tick(seconds);
                scripted.effects.settle(preview);
            }
            if (preview != null) {
                scripted.effects.show(preview, scripted);
                scripted.effects.follow(preview, scripted);
            }
        }
        if (preview != null) {
            for (var iterator = orphans.iterator(); iterator.hasNext(); ) {
                var handle = iterator.next();
                if (preview.isFinished(handle)) {
                    preview.remove(handle);
                    iterator.remove();
                }
            }
        }
    }

    void stop() {
        var preview = previews.get();
        for (var scripted : pawns.values()) {
            scripted.effects.remove(preview);
        }
        if (preview != null) {
            for (var handle : orphans) {
                preview.remove(handle);
            }
        }
        pawns.clear();
        orphans.clear();
    }

    private static Vec3 turnedByYaw(float[] axes, int at, float yaw) {
        var cos = (float) Math.cos(yaw);
        var sin = (float) Math.sin(yaw);
        var x = axes[at];
        var y = axes[at + 1];
        return new Vec3(x * cos - y * sin, x * sin + y * cos, axes[at + 2]);
    }

    private static float[] turnedByYaw(float[] axes, float yaw) {
        var turned = new float[9];
        for (var axis = 0; axis < 3; axis++) {
            var vector = turnedByYaw(axes, axis * 3, yaw);
            turned[axis * 3] = vector.getX();
            turned[axis * 3 + 1] = vector.getY();
            turned[axis * 3 + 2] = vector.getZ();
        }
        return turned;
    }

    private final class Scripted implements Host, Body {
        final SceneActor actor;
        final ScriptedClass type;
        final ScriptPawn pawn;
        final ScriptEffects effects;
        final @Nullable SceneRig rig;
        final float collisionHeight;
        final float collisionRadius;
        String sequence = "";
        int plays = -1;
        int ends;
        boolean ended;
        int rigSequence;
        float seconds;

        Scripted(
                SceneActor actor,
                ScriptedClass type,
                @Nullable SceneRig rig,
                float collisionHeight,
                float collisionRadius) {
            this.actor = actor;
            this.type = type;
            this.rig = rig;
            this.collisionHeight = collisionHeight;
            this.collisionRadius = collisionRadius;
            rigSequence = rig == null || rig.getSequenceCount() == 0 ? -1 : rig.getIdle();
            pawn = new ScriptPawn(type, this);
            effects = new ScriptEffects(pawn, type.name(), reads::getEffect);
        }

        @Override
        public Vector getLocation() {
            return new Vector(actor.x(), actor.y(), actor.z());
        }

        @Override
        public Rotator getRotation() {
            return new Rotator(0, actor.getYaw(), 0);
        }

        @Override
        public float getCollisionHeight() {
            return collisionHeight;
        }

        @Override
        public float getCollisionRadius() {
            return collisionRadius;
        }

        @Override
        public boolean hasBone(String bone) {
            return rig != null && rig.findBone(bone) >= 0;
        }

        @Override
        public @Nullable Coords getBoneCoords(String bone) {
            var found = findWorldBone(bone);
            if (found == null) {
                return null;
            }
            var axes = new Vector[3];
            for (var axis = 0; axis < 3; axis++) {
                var x = found.axes()[axis * 3];
                var y = found.axes()[axis * 3 + 1];
                var z = found.axes()[axis * 3 + 2];
                var length = (float) Math.sqrt(x * x + y * y + z * z);
                axes[axis] = length > 0 ? new Vector(x / length, y / length, z / length) : new Vector(x, y, z);
            }
            var place = found.place();
            return new Coords(new Vector(place.getX(), place.getY(), place.getZ()), axes[0], axes[1], axes[2]);
        }

        @Override
        public boolean canSpawn(String effectClass) {
            return reads.getEffect(effectClass) != null;
        }

        @Override
        public float random() {
            var picks = chance;
            return picks == null ? (float) Math.random() : picks.nextFloat();
        }

        @Override
        public Vec3 getPlace() {
            return new Vec3(actor.x(), actor.y(), actor.z());
        }

        @Override
        public URotator getTurn() {
            return new URotator(0, actor.getYaw(), 0);
        }

        @Override
        public @Nullable Bone findWorldBone(String name) {
            if (rig == null || rigSequence < 0) {
                return null;
            }
            var coords = rig.getCoords(rigSequence, seconds, rig.findBone(name));
            if (coords == null) {
                return null;
            }
            var yaw = (float) (actor.getYaw() * RADIANS_PER_UNIT);
            var place = turnedByYaw(
                    new float[] {
                        coords.place().getX(),
                        coords.place().getY(),
                        coords.place().getZ()
                    },
                    0,
                    yaw);
            return new Bone(
                    new Vec3(actor.x() + place.getX(), actor.y() + place.getY(), actor.z() + place.getZ()),
                    turnedByYaw(coords.axes(), yaw),
                    turnedByYaw(rig.getMeshAxes(), yaw));
        }
    }
}
