package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.audio.SoundClip;
import com.rost.cruma.core.npc.ClientCatalog;
import com.rost.cruma.core.npc.ClientCatalog.Combo;
import com.rost.cruma.core.npc.ClientCatalog.SkillSound;
import com.rost.cruma.core.unreal.SkeletalPose;
import com.rost.cruma.core.unreal.SkillEffects;
import com.rost.cruma.core.unreal.SkillEffects.Action;
import com.rost.cruma.core.unreal.SkillEffects.Phase;
import com.rost.cruma.core.unreal.SkillEffects.Skill;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.EffectParts;
import com.rost.cruma.core.world.EmitterSound;
import com.rost.cruma.core.world.NpcAnimation;
import com.rost.cruma.viewer.editing.SkillActions;
import com.rost.cruma.viewer.geodata.ClientFloors;
import com.rost.cruma.viewer.render.api.EffectPreview;
import com.rost.cruma.viewer.spawn.ScriptEffects;
import com.rost.cruma.viewer.spawn.SpawnModelData;
import com.rost.cruma.viewer.ui.EffectsPanel.Spot;
import com.rost.cruma.viewer.ui.SkillProjectile.Bend;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.eclipse.collections.impl.list.mutable.primitive.FloatArrayList;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.jetbrains.annotations.Nullable;

final class SkillShow {
    static final float CAST_SECONDS = 1.2f;

    static final float SHOT_LEAD = .15f;

    static final float PAUSE_SECONDS = 1f;

    static final float LONGEST_SECONDS = 5f;

    static final float SEEK_STEP = 1f / 60f;

    static final float SCALE_RADIUS = 9f;

    private static final float SAME_MOMENT = 1e-3f;
    private static final float LIFE_AFTER_SHOT = .2f;
    private static final float LIFE_BEFORE_SHOT = .46666667f;
    private static final float LIFE_OF_STYLE_15 = 2f;
    private static final float CHANNEL_RELEASE = 2.6666667f;
    private static final int UNSEEN_ARROW = -1;
    private static final float[] NONE = new float[0];

    private static final String BOW = "Bow";
    private static final float DEFAULT_HALF_HEIGHT = 24f;
    private static final float RADIUS_SHARE = .4f;

    private final Skill skill;
    private final Map<String, EffectParts> clientParts;
    private Skill played;
    private Map<String, EffectParts> parts;
    private List<Cast> casts;
    private final RandomGenerator random;
    private Cast cast;
    private float speed = 1f;
    private @Nullable NpcAnimation laidOutFor;
    private List<Segment> segments = List.of();
    private final List<Planned> plan = new ArrayList<>();
    private final List<Planned> shotPlan = new ArrayList<>();
    private final Set<Planned> channeled = Collections.newSetFromMap(new IdentityHashMap<>());
    private float firstPartSeconds;
    private final List<Flight> flights = new ArrayList<>();
    private final List<Action> carriedHit = new ArrayList<>();
    private final List<Sound> carriedSounds = new ArrayList<>();
    private final List<Planned> waiting = new ArrayList<>();
    private float arrival = -1f;
    private final List<Attached> attached = new ArrayList<>();
    private final List<Forced> forcedEnds = new ArrayList<>();
    private @Nullable Planned arrowFlight;
    private final IntArrayList heldArrows = new IntArrayList();
    private boolean handUnseen;
    private float[] preShots = NONE;
    private float[] earlyShots = NONE;
    private int nextPreShot;
    private int nextEarlyShot;
    private float[] channelMarks = NONE;
    private boolean channelByMarks;
    private int nextChannel;
    private final Set<Planned> channelCopies = Collections.newSetFromMap(new IdentityHashMap<>());
    private @Nullable Pawn shooter;
    private @Nullable Pawn aimedAt;
    private float @Nullable [] shotTimes;
    private float @Nullable [] hitTimes;
    private List<Sound> sounds = List.of();
    private final List<PlannedSound> plannedSounds = new ArrayList<>();
    private int nextSound;
    private float time;
    private float endedFor;
    private float pause = PAUSE_SECONDS;
    private float length;
    private float plannedEnd;
    private boolean keepLength;
    private boolean lengthOutdated;
    private boolean endSeen;
    private int next;

    record Pawn(
            Vec3 feet,
            float yaw,
            @Nullable SpawnModelData model,
            Supplier<Map<String, Vec3>> bones,
            float collisionHeight,
            Supplier<Map<String, float[]>> axes) {
        Pawn(
                Vec3 feet,
                float yaw,
                @Nullable SpawnModelData model,
                Supplier<Map<String, Vec3>> bones,
                float collisionHeight) {
            this(feet, yaw, model, bones, collisionHeight, Map::of);
        }

        Pawn(Vec3 feet, float yaw, @Nullable SpawnModelData model) {
            this(feet, yaw, model, () -> model == null ? Map.of() : model.bones());
        }

        Pawn(Vec3 feet, float yaw, @Nullable SpawnModelData model, Supplier<Map<String, Vec3>> bones) {
            this(feet, yaw, model, bones, 0f);
        }

        float halfHeight() {
            if (collisionHeight > 0) {
                return collisionHeight;
            }
            return model == null || model.lowest() <= 1f ? DEFAULT_HALF_HEIGHT : model.lowest();
        }

        float radius() {
            return halfHeight() * RADIUS_SHARE;
        }

        Vec3 location() {
            return new Vec3(feet.getX(), feet.getY(), feet.getZ() + halfHeight());
        }

        Vec3 effectTarget() {
            if (model == null || model.effectBone().isEmpty()) {
                return location();
            }
            var local = bones.get().get(model.effectBone());
            return local == null ? location() : inWorld(local);
        }

        float sizeScale(Action action) {
            return action.scale() > 0f ? radius() * action.scale() / SCALE_RADIUS : 1f;
        }

        Vec3 point(Action action) {
            return point(action, yaw);
        }

        Vec3 point(Action action, float spawnYaw) {
            var base = place(action);
            var offset = action.offset();
            var x = offset.getX();
            var y = offset.getY();
            var z = offset.getZ();
            var along = yaw;
            if (action.relativeToCylinder()) {
                x *= radius();
                y *= radius();
                z *= halfHeight();
                along = spawnYaw;
            }
            var cos = (float) Math.cos(along);
            var sin = (float) Math.sin(along);
            return new Vec3(base.getX() + x * cos - y * sin, base.getY() + x * sin + y * cos, base.getZ() + z);
        }

        @Nullable
        Vec3 turn(Action action) {
            if (model == null || action.attachOn() != SkillEffects.AM_BONE_SPECIFIED) {
                return null;
            }
            if (action.absolute()) {
                return action.turn().vector();
            }
            var bone = axes.get().get(SkeletalPose.boneKey(action.bone()));
            if (bone == null) {
                return null;
            }
            return orthoRotation(within(turnedAxes(bone, yaw), action.turn()));
        }

        @Nullable
        Vec3 handTurn(Action action) {
            if (model == null) {
                return null;
            }
            if (action.absolute()) {
                return action.turn().vector();
            }
            var all = axes.get();
            for (var name : Spot.RIGHT_HAND.bones) {
                var bone = all.get(name);
                if (bone != null) {
                    return orthoRotation(within(turnedAxes(bone, yaw), action.turn()));
                }
            }
            return null;
        }

        @Nullable
        Vec3 boneAxis(String name) {
            if (model == null || name.isEmpty()) {
                return null;
            }
            var bone = axes.get().get(SkeletalPose.boneKey(name));
            if (bone == null) {
                return null;
            }
            var world = turnedAxes(bone, yaw);
            return new Vec3(world[0], world[1], world[2]);
        }

        private Vec3 place(Action action) {
            Vec3 local = null;
            if (model != null) {
                var now = bones.get();
                local = switch (action.attachOn()) {
                    case SkillEffects.AM_RH, SkillEffects.AM_RA -> Spot.RIGHT_HAND.on(now, model.height());
                    case SkillEffects.AM_LH, SkillEffects.AM_LA -> Spot.LEFT_HAND.on(now, model.height());
                    case SkillEffects.AM_WING -> Spot.CHEST.on(now, model.height());
                    case SkillEffects.AM_BONE_SPECIFIED -> now.get(SkeletalPose.boneKey(action.bone()));
                    case SkillEffects.AM_RIGHT_FOOT -> now.get(SkeletalPose.boneKey("Bip01 R Foot"));
                    case SkillEffects.AM_LEFT_FOOT -> now.get(SkeletalPose.boneKey("Bip01 L Foot"));
                    default -> null;
                };
            }
            return local == null ? location() : inWorld(local);
        }

        private Vec3 inWorld(Vec3 local) {
            return new Vec3(
                    feet.getX() + turnX(local.getX(), local.getY()),
                    feet.getY() + turnY(local.getX(), local.getY()),
                    modelZ() + local.getZ());
        }

        private float modelZ() {
            return collisionHeight > 0 && model != null ? feet.getZ() + collisionHeight - model.lowest() : feet.getZ();
        }

        private float turnX(float x, float y) {
            return (float) (x * Math.cos(yaw) - y * Math.sin(yaw));
        }

        private float turnY(float x, float y) {
            return (float) (x * Math.sin(yaw) + y * Math.cos(yaw));
        }
    }

    private record Planned(
            Action action,
            float at,
            Pawn on,
            @Nullable Pawn to,
            @Nullable Bend bend,
            boolean carries,
            @Nullable Action rider,
            float life,
            float forced) {
        Planned(
                Action action,
                float at,
                Pawn on,
                @Nullable Pawn to,
                @Nullable Bend bend,
                boolean carries,
                @Nullable Action rider,
                float life) {
            this(action, at, on, to, bend, carries, rider, life, Float.NaN);
        }

        Planned(Action action, float at, Pawn on) {
            this(action, at, on, null, null, false, null, Float.NaN);
        }

        Planned(Action action, float at, Pawn on, @Nullable Pawn to, @Nullable Bend bend, boolean carries) {
            this(action, at, on, to, bend, carries, null, Float.NaN);
        }

        Planned(
                Action action,
                float at,
                Pawn on,
                @Nullable Pawn to,
                @Nullable Bend bend,
                boolean carries,
                @Nullable Action rider) {
            this(action, at, on, to, bend, carries, rider, Float.NaN);
        }

        SkillProjectile shot(Vec3 from, EffectParts effect, float flyingTime) {
            var end = Objects.requireNonNull(to);
            var curve = action.curve();
            if (bend != null) {
                return SkillProjectile.curved(from, on.location(), end.location(), bend);
            }
            if (curve.hermite()) {
                var leaving = on.boneAxis(action.bone());
                return SkillProjectile.hermite(
                        from,
                        on.location(),
                        aim(end),
                        leaving != null
                                ? leaving
                                : new Vec3((float) Math.cos(on.yaw()), (float) Math.sin(on.yaw()), 0f),
                        flyingTime);
            }
            if (curve.bezier() && !curve.controlPoints().isEmpty()) {
                return SkillProjectile.bezier(from, end.location(), curve.controlPoints(), flyingTime);
            }
            return SkillProjectile.toTarget(from, effect.projectileSpeed(), effect.projectileAcceleration());
        }

        float flightSeconds(EffectParts effect, float flyingTime) {
            var end = Objects.requireNonNull(to);
            return SkillProjectile.flightSeconds(shot(on.point(action), effect, flyingTime), aim(end));
        }

        Planned shifted(float seconds) {
            return new Planned(action, at + seconds, on, to, bend, carries, rider, life, forced);
        }
    }

    record Sound(EmitterSound sound, SkillSound.Moment moment, boolean onTarget) {
        static Sound of(SoundClip clip, SkillSound sound) {
            return new Sound(
                    new EmitterSound(
                            clip, sound.radius(), sound.volume(), 1f, 1f, false, false, sound.delay(), 0f, 0f, 0f),
                    sound.moment(),
                    sound.onTarget());
        }
    }

    private record PlannedSound(Sound sound, float at, Pawn on) {}

    private record Forced(int handle, float at) {}

    private static final class Flight {
        final int handle;
        final Planned planned;
        final SkillProjectile shot;
        final int rider;
        float flown;

        Flight(int handle, Planned planned, SkillProjectile shot, int rider) {
            this.handle = handle;
            this.planned = planned;
            this.shot = shot;
            this.rider = rider;
        }
    }

    private static final class Attached {
        final int handle;
        final Planned planned;
        Vec3 at;

        @Nullable
        Vec3 turn;

        Attached(int handle, Planned planned, Vec3 at, @Nullable Vec3 turn) {
            this.handle = handle;
            this.planned = planned;
            this.at = at;
            this.turn = turn;
        }
    }

    record Stand(Vec3 caster, Vec3 target) {}

    SkillShow(Skill skill, Map<String, EffectParts> parts, List<Cast> casts, RandomGenerator random) {
        this.skill = skill;
        played = skill;
        clientParts = Map.copyOf(parts);
        this.parts = clientParts;
        this.random = random;
        this.casts = orDefault(casts);
        this.cast = this.casts.getFirst();
    }

    SkillShow(Skill skill, Map<String, EffectParts> parts, List<Cast> casts) {
        this(skill, parts, casts, RandomGenerator.getDefault());
    }

    SkillShow(Skill skill, Map<String, EffectParts> parts) {
        this(skill, parts, List.of());
    }

    static Hung hang(
            Vec3 pawn,
            float yaw,
            Vec3 bone,
            float[] along,
            float[] boneAxes,
            Vec3 relativeLocation,
            URotator relativeRotation,
            boolean absolute) {
        var cos = (float) Math.cos(yaw);
        var sin = (float) Math.sin(yaw);
        var place = new Vec3(
                pawn.getX() + bone.getX() * cos - bone.getY() * sin,
                pawn.getY() + bone.getX() * sin + bone.getY() * cos,
                pawn.getZ() + bone.getZ());
        var hung = ScriptEffects.hang(
                place, turnedAxes(along, yaw), turnedAxes(boneAxes, yaw), relativeLocation, relativeRotation, absolute);
        return new Hung(hung.place(), hung.turn());
    }

    record Hung(Vec3 place, Vec3 turn) {}

    static float[] within(float[] bone, URotator turn) {
        return ScriptEffects.within(bone, turn);
    }

    static float[] axes(URotator turn) {
        return ScriptEffects.axes(turn);
    }

    static Vec3 orthoRotation(float[] axes) {
        return ScriptEffects.orthoRotation(axes);
    }

    static Stand stand(Vec3 place, float yaw, float apart) {
        var acrossX = (float) Math.cos(yaw + Math.PI / 2);
        var acrossY = (float) Math.sin(yaw + Math.PI / 2);
        return new Stand(place, new Vec3(place.getX() - acrossX * apart, place.getY() - acrossY * apart, place.getZ()));
    }

    static Vec3 onFloor(Vec3 feet, ClientFloors floors, @Nullable SpawnModelData model) {
        var pawn = new Pawn(feet, 0f, model);
        var floor = floors.findStandHeight(feet.getX(), feet.getY(), feet.getZ(), pawn.radius(), pawn.halfHeight() * 2);
        if (Double.isNaN(floor)) {
            floor = floors.findFloorBelow(feet.getX(), feet.getY(), feet.getZ());
        }
        return Double.isNaN(floor) ? feet : new Vec3(feet.getX(), feet.getY(), (float) floor);
    }

    record Moment(int sequence, float seconds) {}

    record Cast(float seconds, List<String> parts, int loop, String animation, List<String> named, int style) {
        Cast {
            parts = List.copyOf(parts);
            named = List.copyOf(named);
        }

        Cast(float seconds, List<String> parts, int loop, String animation, List<String> named) {
            this(seconds, parts, loop, animation, named, -1);
        }

        Cast(float seconds, List<String> parts, int loop, String animation) {
            this(seconds, parts, loop, animation, List.of());
        }

        boolean drains() {
            return style == 4 || style == 20;
        }

        boolean drainsBack() {
            return style == 19;
        }

        String named(int i) {
            return i < named.size() ? named.get(i) : "";
        }
    }

    record Segment(int sequence, float start, float length, float speed, float period) {}

    static List<Cast> casts(ClientCatalog.@Nullable Cast cast, Function<String, Combo> combos, @Nullable String own) {
        return casts(cast, combos, own, _ -> null);
    }

    static List<Cast> casts(
            ClientCatalog.@Nullable Cast cast,
            Function<String, Combo> combos,
            @Nullable String own,
            Function<String, @Nullable String> kinds) {
        if (cast == null) {
            return own == null ? List.of() : List.of(new Cast(CAST_SECONDS, List.of(own), -1, own, List.of(own)));
        }
        var style = cast.style();
        if (cast.animations().isEmpty()) {
            return List.of(
                    own == null
                            ? new Cast(cast.seconds(), List.of(), -1, "", List.of(), style)
                            : new Cast(cast.seconds(), List.of(own), -1, own, List.of(own), style));
        }
        var casts = new ArrayList<Cast>();
        for (var animation : cast.animations()) {
            var combo = combos.apply(animation);
            var parts = new ArrayList<>(combo == null ? List.<String>of() : combo.parts());
            var named = new ArrayList<String>(parts.size());
            for (var part : parts) {
                var sequence = kinds.apply(part);
                named.add(sequence == null ? "" : sequence);
            }
            if (own != null) {
                if (parts.isEmpty()) {
                    parts.add(own);
                    named.add(own);
                } else {
                    parts.set(parts.size() - 1, own);
                    named.set(named.size() - 1, own);
                }
            }
            casts.add(new Cast(
                    cast.seconds(),
                    parts,
                    combo == null ? -1 : combo.loop(),
                    animation,
                    named.stream().allMatch(String::isEmpty) ? List.of() : named,
                    style));
        }
        return casts;
    }

    static List<Segment> layOut(int[] sequences, float[] lengths, float[] shots, int loop, float shotSeconds) {
        var segments = new ArrayList<Segment>();
        var count = sequences.length;
        var marked = -1;
        var mark = 1f;
        for (var i = count - 1; i >= 0 && marked < 0; i--) {
            if (i != loop && sequences[i] >= 0 && shots[i] > 0) {
                marked = i;
                mark = shots[i];
            }
        }
        for (var i = count - 1; i >= 0 && marked < 0; i--) {
            if (i != loop && sequences[i] >= 0) {
                marked = i;
            }
        }
        var repeats = loop >= 0 && loop < count && loop < Math.max(marked, count - 1);
        if (marked < 0) {
            if (repeats && sequences[loop] >= 0 && shotSeconds > 0) {
                segments.add(new Segment(sequences[loop], 0f, shotSeconds, 1f, lengths[loop]));
            }
            return segments;
        }
        var toMark = lengths[marked] * mark;
        for (var i = 0; i < marked; i++) {
            if (i != loop && sequences[i] >= 0) {
                toMark += lengths[i];
            }
        }
        var speed = shotSeconds > 0 && toMark > 0 && (!repeats || toMark > shotSeconds) ? toMark / shotSeconds : 1f;
        var hold = repeats ? Math.max(0f, shotSeconds - toMark / speed) : 0f;
        var at = 0f;
        for (var i = 0; i < count; i++) {
            if (repeats && i == loop) {
                if (sequences[i] >= 0 && hold > 0) {
                    segments.add(new Segment(sequences[i], at, hold, 1f, lengths[i]));
                }
                at += hold;
            } else if (sequences[i] >= 0) {
                segments.add(new Segment(sequences[i], at, lengths[i] / speed, speed, 0f));
                at += lengths[i] / speed;
            }
        }
        return segments;
    }

    static float[] marksAt(List<Segment> segments, int[] sequences, float[][] marks, float before) {
        var times = new FloatArrayList();
        for (var segment : segments) {
            float[] own = null;
            for (var i = 0; i < sequences.length && own == null; i++) {
                if (sequences[i] == segment.sequence()) {
                    own = marks[i];
                }
            }
            if (own == null) {
                continue;
            }
            var turn = segment.period() > 0 ? segment.period() : segment.length();
            for (var mark : own) {
                var at = segment.start() + mark * turn;
                if (at < segment.start() + segment.length() && at < before) {
                    times.add(at);
                }
            }
        }
        return times.sortThis().toArray();
    }

    static float[] everyTurn(List<Segment> segments, int[] sequences, float[][] marks, float before) {
        var times = new FloatArrayList();
        for (var segment : segments) {
            float[] own = null;
            for (var i = 0; i < sequences.length && own == null; i++) {
                if (sequences[i] == segment.sequence()) {
                    own = marks[i];
                }
            }
            if (own == null || own.length == 0) {
                continue;
            }
            var turn = segment.period() > 0 ? segment.period() : segment.length();
            if (!(turn > 0f)) {
                continue;
            }
            for (var start = segment.start(); start < segment.start() + segment.length(); start += turn) {
                for (var mark : own) {
                    var at = start + mark * turn;
                    if (at < segment.start() + segment.length() && at < before) {
                        times.add(at);
                    }
                }
            }
        }
        return times.sortThis().toArray();
    }

    static Moment at(List<Segment> segments, float time, int idle, float idleSeconds) {
        var end = 0f;
        for (var segment : segments) {
            if (time >= segment.start() && time < segment.start() + segment.length()) {
                var into = time - segment.start();
                return new Moment(
                        segment.sequence(),
                        segment.period() > 0 ? loop(into, segment.period()) : into * segment.speed());
            }
            end = Math.max(end, segment.start() + segment.length());
        }
        return new Moment(idle, loop(time >= end ? time - end : time, idleSeconds));
    }

    static String stanceOf(String idle) {
        var words = idle.split("_");
        return words.length >= 2 ? words[1] : "";
    }

    static int sequenceOf(NpcAnimation animation, String part, String stance) {
        var found = stance.isEmpty() ? -1 : animation.find(part + "_" + stance + "_");
        if (found < 0 && !stance.isEmpty()) {
            found = animation.findSequence(part + "_" + stance);
        }
        if (found < 0) {
            found = animation.find(part + "_");
        }
        return found >= 0 ? found : animation.findSequence(part);
    }

    static Moment waiting(NpcAnimation animation, float seconds) {
        return new Moment(animation.getIdle(), loop(seconds, animation.getSeconds(animation.getIdle())));
    }

    static boolean isTargeted(Skill skill) {
        for (var phase : Phase.values()) {
            for (var action : skill.actions(phase)) {
                if (action.onTarget()) {
                    return true;
                }
            }
        }
        return false;
    }

    void casts(List<Cast> casts) {
        this.casts = orDefault(casts);
        cast = this.casts.getFirst();
        forgetLayout();
    }

    Cast getCast() {
        return cast;
    }

    int getCastChoices() {
        return casts.size();
    }

    void speed(float factor) {
        if (factor > 0f && factor != speed) {
            speed = factor;
            forgetLayout();
        }
    }

    float getShotSeconds() {
        return Math.max(0f, getCastSeconds() - Math.max(SHOT_LEAD, played.flyingTime()));
    }

    float getPhaseSeconds(Phase phase) {
        return switch (phase) {
            case CASTING -> 0f;
            case CHANNELING -> channelByMarks && channelMarks.length > 0 ? channelMarks[0] : 0f;
            case PRESHOT, SHOT -> getShotSeconds();
            case EXPLOSION -> getHitSeconds();
            case UNION_TARGET -> getLandSeconds();
        };
    }

    float startSeconds(Phase phase, Action action) {
        return getPhaseSeconds(phase) + Math.max(0f, action.delay());
    }

    float getLife(Phase phase, Action action) {
        var shot = getShotSeconds();
        if (phase != Phase.CASTING || !action.adjustLife() || !(shot > 0f)) {
            return Float.NaN;
        }
        var fixed = fixedLifeTime(action, shot);
        if (fixed != 0f) {
            return fixed;
        }
        return switch (cast.style()) {
            case 13 -> shot - LIFE_BEFORE_SHOT;
            case 15 -> played.isVisual() ? shot + LIFE_AFTER_SHOT : LIFE_OF_STYLE_15;
            default -> shot + LIFE_AFTER_SHOT;
        };
    }

    float getChannelLife(Action action) {
        var shot = getShotSeconds();
        if (played.isVisual() || !(shot > 0f) || cast.style() == 13 || cast.style() == 16) {
            return Float.NaN;
        }
        if (cast.style() == 12) {
            return shot - CHANNEL_RELEASE / getPartSpeed() + LIFE_AFTER_SHOT;
        }
        if (!action.adjustLife()) {
            return Float.NaN;
        }
        var fixed = fixedLifeTime(action, shot);
        return fixed != 0f ? fixed : shot - firstPartSeconds + LIFE_BEFORE_SHOT;
    }

    float getPartLeft(float seconds) {
        for (var segment : segments) {
            var into = seconds - segment.start();
            if (into < 0f || into >= segment.length()) {
                continue;
            }
            var turn = segment.period() > 0f ? segment.period() : segment.length();
            var share = turn > 0f ? (into % turn) / turn : 1f;
            return (1f - share) * turn * segment.speed();
        }
        return 0f;
    }

    boolean isChannelLifeFixed() {
        return cast.style() == 12;
    }

    float getForcedLife(Phase phase, Action action) {
        if (phase != Phase.CASTING || !(action.forceLife() > 0f) || !(getShotSeconds() > 0f)) {
            return Float.NaN;
        }
        return action.forceLife();
    }

    float getCastSeconds() {
        return Math.max(0f, cast.seconds()) / speed;
    }

    Skill getSkill() {
        return skill;
    }

    Skill getPlayed() {
        return played;
    }

    Map<String, EffectParts> getParts() {
        return parts;
    }

    Map<String, EffectParts> getClientParts() {
        return clientParts;
    }

    boolean plays(Skill candidate, Map<String, EffectParts> effects) {
        effects = withClientArrows(effects);
        if (!played.equals(candidate) || !parts.keySet().equals(effects.keySet())) {
            return false;
        }
        for (var entry : parts.entrySet()) {
            if (entry.getValue() != effects.get(entry.getKey())) {
                return false;
            }
        }
        return true;
    }

    void play(Skill edited, Map<String, EffectParts> effects) {
        played = edited;
        parts = withClientArrows(effects);
        keepLength = true;
        forgetLayout();
    }

    void setSounds(List<Sound> sounds) {
        this.sounds = List.copyOf(sounds);
    }

    List<Sound> getSounds() {
        return sounds;
    }

    void start(Pawn caster, Pawn target) {
        restart();
        plan.clear();
        shotPlan.clear();
        channeled.clear();
        carriedHit.clear();
        carriedSounds.clear();
        arrival = -1f;
        shooter = caster;
        aimedAt = target;
        shotTimes = null;
        hitTimes = null;
        var cast = getShotSeconds();
        var carriers = new ArrayList<Planned>();
        var shots = played.actions(Phase.SHOT);
        var arrow = shots.isEmpty() ? null : findArrow();
        arrowFlight = null;
        var rider = arrow == null ? null : shots.getFirst();
        for (var phase : Phase.values()) {
            var swapped = phase == Phase.SHOT && this.cast.drains();
            var from = swapped ? target : caster;
            var to = swapped ? caster : target;
            var curves = 0;
            var actions = played.actions(phase);
            for (var slot = 0; slot < actions.size(); slot++) {
                var action = actions.get(slot);
                var effect = parts.get(SkillEffects.key(action.effect()));
                if (effect == null || phase == Phase.SHOT && slot == 0 && rider != null) {
                    continue;
                }
                if (phase == Phase.EXPLOSION && !carriers.isEmpty()) {
                    carriedHit.add(action);
                    continue;
                }
                var at = phase == Phase.CHANNELING ? Math.max(0f, action.delay()) : startSeconds(phase, action);
                var on = action.onTarget() ? to : from;
                Planned planned;
                if (action == arrow) {
                    planned = new Planned(action, getShotSeconds(), caster, target, null, true, rider);
                    arrowFlight = planned;
                } else if (phase != Phase.SHOT || action.onTarget() || !isProjectile(effect)) {
                    planned = new Planned(
                            action,
                            at,
                            on,
                            null,
                            null,
                            false,
                            null,
                            getLife(phase, action),
                            getForcedLife(phase, action));
                } else if (swapped) {
                    planned = new Planned(action, at, on, to, Bend.drain(curves++ == 0), false);
                } else {
                    planned = new Planned(action, at, on, to, null, true);
                }
                plan.add(planned);
                if (phase == Phase.CHANNELING) {
                    channeled.add(planned);
                }
                if (planned.carries()) {
                    carriers.add(planned);
                }
                if (phase == Phase.SHOT || action == arrow) {
                    shotPlan.add(planned);
                }
            }
        }
        if (!carriers.isEmpty()) {
            var first = Float.MAX_VALUE;
            for (var carrier : carriers) {
                var effect = parts.get(SkillEffects.key(carrier.action().effect()));
                first = Math.min(
                        first,
                        carrier.at() + carrier.flightSeconds(Objects.requireNonNull(effect), played.flyingTime()));
            }
            arrival = first;
        }
        plan.sort(Comparator.comparingDouble(Planned::at));
        var hit = getHitSeconds();
        plannedEnd = plannedEnd(hit);
        lengthOutdated = keepLength && length > 0f;
        if (!keepLength) {
            length = 0f;
        }
        keepLength = false;
        plannedSounds.clear();
        for (var sound : sounds) {
            if (sound.moment() == SkillSound.Moment.HIT && !carriedHit.isEmpty()) {
                carriedSounds.add(sound);
                continue;
            }
            var at = switch (sound.moment()) {
                case CAST -> 0f;
                case SHOT -> cast;
                case HIT -> hit;
            };
            plannedSounds.add(new PlannedSound(sound, at, sound.onTarget() ? target : caster));
        }
        plannedSounds.sort(Comparator.comparingDouble(PlannedSound::at));
    }

    void seek(EffectPreview preview, float seconds) {
        preview.clear();
        rewind();
        var target = Math.max(0f, seconds);
        while (time < target - 1e-5f) {
            var step = Math.min(SEEK_STEP, target - time);
            advance(preview, step, false, false);
            preview.advance(step);
        }
    }

    float length() {
        if (length > 0f && !lengthOutdated) {
            return length;
        }
        return Math.max(length > 0f ? length : plannedEnd, time);
    }

    float getShownTime() {
        return endSeen ? Math.min(time, length()) : time;
    }

    float getHitSeconds() {
        return arrival >= 0f ? arrival : getLandSeconds();
    }

    float getLandSeconds() {
        return getShotSeconds() + Math.max(0f, played.flyingTime());
    }

    float[] getShotTimes() {
        if (shotTimes == null) {
            markShots();
        }
        return Objects.requireNonNull(shotTimes).clone();
    }

    float[] getHitTimes() {
        if (hitTimes == null) {
            markShots();
        }
        return Objects.requireNonNull(hitTimes).clone();
    }

    int getPlanned() {
        return plan.size();
    }

    Map<String, Float> getLives() {
        var lives = new LinkedHashMap<String, Float>();
        for (var planned : plan) {
            var life = lifeOf(planned);
            if (!Float.isNaN(life)) {
                var effect = planned.action().effect();
                lives.put(effect.substring(effect.indexOf('.') + 1), life);
            }
        }
        return lives;
    }

    void update(EffectPreview preview, float seconds, boolean loop) {
        advance(preview, seconds, loop, true);
    }

    float getTime() {
        return time;
    }

    boolean hasBegunAll() {
        if (next < plan.size() || nextSound < plannedSounds.size() || time < getHitSeconds()) {
            return false;
        }
        if (!waiting.isEmpty()
                || nextEarlyShot < earlyShots.length
                || nextChannel < channelMarks.length
                || flights.stream().anyMatch(flight -> flight.planned.carries())) {
            return false;
        }
        for (var segment : segments) {
            if (time < segment.start() + segment.length()) {
                return false;
            }
        }
        return true;
    }

    float getLastStart() {
        return plan.isEmpty() ? 0f : plan.getLast().at();
    }

    void repeatPause(float seconds) {
        pause = Math.max(0f, seconds);
    }

    Moment getCasterMoment(NpcAnimation animation) {
        return getCasterMoment(animation, true);
    }

    Moment getCasterMoment(NpcAnimation animation, boolean drawn) {
        handUnseen = !drawn;
        if (laidOutFor != animation) {
            laidOutFor = animation;
            var stance = stanceOf(animation.getName(animation.getIdle()));
            if (shoots()) {
                stance = BOW;
            }
            var sequences = new int[cast.parts().size()];
            var any = false;
            for (var i = 0; i < sequences.length; i++) {
                var named = cast.named(i).isEmpty() ? -1 : animation.findSequence(cast.named(i));
                sequences[i] =
                        named >= 0 ? named : sequenceOf(animation, cast.parts().get(i), stance);
                any |= sequences[i] >= 0;
            }
            var loop = cast.loop();
            if (!any && !cast.parts().isEmpty()) {
                sequences = new int[] {-1, animation.find("magic", "spatk", "atk")};
                loop = 0;
            }
            var lengths = new float[sequences.length];
            var shots = new float[sequences.length];
            for (var i = 0; i < sequences.length; i++) {
                lengths[i] = sequences[i] < 0 ? 0f : animation.getSeconds(sequences[i]);
                shots[i] = animation.getShotTime(sequences[i]);
            }
            segments = layOut(sequences, lengths, shots, loop, getShotSeconds());
            var first = segments.isEmpty() ? null : segments.getFirst();
            firstPartSeconds = sequences.length > 0
                            && sequences[0] >= 0
                            && first != null
                            && first.start() == 0f
                            && first.sequence() == sequences[0]
                    ? first.length()
                    : 0f;
            var bow = shoots();
            var visual = played.isVisual();
            var multiShot = !visual && cast.style() >= 8 && cast.style() <= 10;
            var preShotMarks = new float[sequences.length][];
            var shotMarks = new float[sequences.length][];
            for (var i = 0; i < sequences.length; i++) {
                preShotMarks[i] = bow ? animation.getPreShotTimes(sequences[i]).toArray() : NONE;
                shotMarks[i] = multiShot ? animation.getShotTimes(sequences[i]).toArray() : NONE;
            }
            preShots = marksAt(segments, sequences, preShotMarks, getShotSeconds());
            if (visual && preShots.length > 1) {
                preShots = Arrays.copyOf(preShots, 1);
            }
            earlyShots = marksAt(segments, sequences, shotMarks, getShotSeconds() - SAME_MOMENT);
            channelByMarks = !visual;
            var channelTimes = new float[sequences.length][];
            for (var i = 0; i < sequences.length; i++) {
                channelTimes[i] = visual || channeled.isEmpty()
                        ? NONE
                        : animation.getChannelTimes(sequences[i]).toArray();
            }
            channelMarks = everyTurn(segments, sequences, channelTimes, getShotSeconds());
            nextPreShot = countBefore(preShots, time);
            nextEarlyShot = countBefore(earlyShots, time);
            nextChannel = countBefore(channelMarks, time);
            plannedEnd = plannedEnd(getHitSeconds());
            shotTimes = null;
            hitTimes = null;
        }
        return at(segments, time, animation.getIdle(), animation.getSeconds(animation.getIdle()));
    }

    boolean isCasterBusy() {
        return segments.stream()
                .anyMatch(segment -> time >= segment.start() && time < segment.start() + segment.length());
    }

    boolean isTargeted() {
        return isTargeted(played);
    }

    private List<Cast> orDefault(List<Cast> casts) {
        return casts.isEmpty()
                ? List.of(new Cast(
                        CAST_SECONDS,
                        List.of("CastMid", "CastEnd", isTargeted() ? "MagicShot" : "MagicNoTarget"),
                        1,
                        ""))
                : List.copyOf(casts);
    }

    private void restart() {
        var picked = casts.get(casts.size() == 1 ? 0 : random.nextInt(casts.size()));
        if (picked != cast) {
            cast = picked;
            forgetLayout();
        }
        rewind();
    }

    private void rewind() {
        time = 0f;
        endedFor = 0f;
        endSeen = false;
        next = 0;
        nextSound = 0;
        flights.clear();
        attached.clear();
        forcedEnds.clear();
        waiting.clear();
        heldArrows.clear();
        nextPreShot = 0;
        nextEarlyShot = 0;
        nextChannel = 0;
        channelCopies.clear();
    }

    private void advance(EffectPreview preview, float seconds, boolean loop, boolean audible) {
        time += seconds;
        while (true) {
            var planned = next < plan.size() ? plan.get(next).at() : Float.MAX_VALUE;
            var held = nextPreShot < preShots.length ? preShots[nextPreShot] : Float.MAX_VALUE;
            var shot = nextEarlyShot < earlyShots.length ? earlyShots[nextEarlyShot] : Float.MAX_VALUE;
            var channel = nextChannel < channelMarks.length ? channelMarks[nextChannel] : Float.MAX_VALUE;
            if (Math.min(Math.min(planned, channel), Math.min(held, shot)) > time) {
                break;
            }
            if (held <= planned && held <= shot && held <= channel) {
                nextPreShot++;
                holdArrow(preview);
            } else if (shot <= planned && shot <= channel) {
                nextEarlyShot++;
                shootEarly(preview, shot, audible);
            } else if (channel <= planned) {
                nextChannel++;
                startChannel(channel);
            } else {
                var due = plan.get(next++);
                if (!channelByMarks || !channeled.contains(due)) {
                    spawn(preview, due);
                }
            }
        }
        waiting.sort(Comparator.comparingDouble(Planned::at));
        while (!waiting.isEmpty() && waiting.getFirst().at() <= time) {
            spawn(preview, waiting.removeFirst());
        }
        while (nextSound < plannedSounds.size() && plannedSounds.get(nextSound).at() <= time) {
            var planned = plannedSounds.get(nextSound++);
            if (audible) {
                preview.playSound(planned.sound().sound(), planned.on().location());
            }
        }
        for (var iterator = forcedEnds.iterator(); iterator.hasNext(); ) {
            var forced = iterator.next();
            if (forced.at() <= time) {
                iterator.remove();
                preview.remove(forced.handle());
                attached.removeIf(hanging -> hanging.handle == forced.handle());
            }
        }
        moveHeldArrows(preview);
        for (var hanging : attached) {
            var on = hanging.planned.on();
            var at = on.point(hanging.planned.action(), getSpawnYaw(on));
            if (!at.equals(hanging.at)) {
                hanging.at = at;
                preview.move(hanging.handle, at);
            }
            if (hanging.turn != null) {
                var turn = hanging.planned.on().turn(hanging.planned.action());
                if (turn != null && !turn.equals(hanging.turn)) {
                    hanging.turn = turn;
                    preview.turn(hanging.handle, turn);
                }
            }
        }
        for (var iterator = flights.iterator(); iterator.hasNext(); ) {
            var flight = iterator.next();
            var to = Objects.requireNonNull(flight.planned.to());
            while (!flight.shot.hasArrived()
                    && flight.flown + SkillProjectile.STEP <= time - flight.planned.at() + 1e-4f) {
                flight.shot.step(aim(to));
                flight.flown += SkillProjectile.STEP;
            }
            if (flight.shot.hasArrived() || flight.flown >= SkillProjectile.LONGEST_SECONDS) {
                preview.remove(flight.handle);
                if (flight.rider >= 0) {
                    preview.kill(flight.rider);
                }
                iterator.remove();
                if (flight.shot.hasArrived() && flight.planned.carries()) {
                    arrive(preview, flight.planned, flight.planned.at() + flight.flown, audible);
                }
                continue;
            }
            preview.move(flight.handle, flight.shot.getPlace());
            var turn = flight.shot.getTurn();
            if (turn != null) {
                preview.fly(flight.handle, turn);
            }
            if (flight.rider >= 0) {
                preview.move(flight.rider, flight.shot.getPlace());
                if (turn != null) {
                    preview.turn(flight.rider, turn);
                }
            }
        }
        if (next < plan.size() || !waiting.isEmpty()) {
            return;
        }
        if (preview.isFinished() || time >= plannedEnd) {
            if (!endSeen) {
                endSeen = true;
                var end = preview.isFinished() ? time : Math.min(time, plannedEnd);
                length = lengthOutdated ? end : Math.max(length, end);
                endedFor = time - end;
                lengthOutdated = false;
            } else {
                endedFor += seconds;
            }
            if (loop && endedFor >= pause) {
                preview.clear();
                restart();
            }
        }
    }

    private float plannedEnd(float hit) {
        var lastArrival = -1f;
        var lastFlight = 0f;
        for (var planned : plan) {
            if (planned.to() != null) {
                var effect = Objects.requireNonNull(
                        parts.get(SkillEffects.key(planned.action().effect())));
                var arrives = planned.at() + planned.flightSeconds(effect, played.flyingTime());
                lastFlight = Math.max(lastFlight, arrives);
                if (planned.carries()) {
                    lastArrival = Math.max(lastArrival, arrives);
                }
            }
        }
        var lastStart = getLastStart();
        for (var action : carriedHit) {
            lastStart = Math.max(lastStart, lastArrival + Math.max(0f, action.delay()));
        }
        var lastChannel = !channelByMarks ? 0f : channelMarks.length > 0 ? channelMarks[channelMarks.length - 1] : -1f;
        for (var planned : channeled) {
            if (lastChannel >= 0f) {
                lastStart = Math.max(lastStart, planned.at() + lastChannel);
            }
        }
        var latest = lastStart + LONGEST_SECONDS;
        var end = 0f;
        for (var planned : plan) {
            var shift = channeled.contains(planned) ? lastChannel : 0f;
            if (planned.to() == null && shift >= 0f) {
                var at = planned.at() + shift;
                var ends = effectEnd(planned.action(), at, latest, lifeOf(planned), isLifeFixed(planned));
                if (!Float.isNaN(planned.forced())) {
                    ends = Math.min(ends, at + planned.forced());
                }
                end = Math.max(end, ends);
            }
        }
        for (var action : carriedHit) {
            end = Math.max(
                    end, effectEnd(action, lastArrival + Math.max(0f, action.delay()), latest, Float.NaN, false));
        }
        return Math.max(Math.max(end, lastFlight), hit + .5f);
    }

    private float effectEnd(Action action, float at, float latest, float life, boolean fixed) {
        var effect = parts.get(SkillEffects.key(action.effect()));
        var empty = effect != null
                && effect.sprites().isEmpty()
                && effect.meshes().isEmpty()
                && effect.beams().isEmpty();
        var seconds = empty ? 0f : effect == null ? -1f : SkillActions.seconds(effect, life, fixed);
        return seconds < 0f ? latest : Math.min(latest, at + seconds);
    }

    private float getPartSpeed() {
        var repeats = false;
        var partSpeed = Float.NaN;
        for (var segment : segments) {
            if (segment.period() > 0f) {
                repeats = true;
            } else if (Float.isNaN(partSpeed)) {
                partSpeed = segment.speed();
            }
        }
        if (Float.isNaN(partSpeed) || repeats && partSpeed == 1f) {
            return speed;
        }
        return partSpeed;
    }

    private float lifeOf(Planned planned) {
        return isChanneled(planned) ? getChannelLife(planned.action()) : planned.life();
    }

    private boolean isLifeFixed(Planned planned) {
        return isChanneled(planned) && isChannelLifeFixed();
    }

    private void spawn(EffectPreview preview, Planned planned) {
        var effect = parts.get(SkillEffects.key(planned.action().effect()));
        if (effect == null) {
            return;
        }
        if (planned.rider() != null) {
            if (!heldArrows.isEmpty()) {
                removeArrow(preview, heldArrows.removeAtIndex(heldArrows.size() - 1));
            }
            if (planned == arrowFlight) {
                heldArrows.forEach(arrow -> removeArrow(preview, arrow));
                heldArrows.clear();
            }
        }
        var turn = planned.on().turn(planned.action());
        var size = planned.on().sizeScale(planned.action());
        var to = planned.to();
        var yaw = to == null ? getSpawnYaw(planned.on()) : towards(planned.on(), to);
        var from = planned.on().point(planned.action(), yaw);
        var handle = turn == null ? preview.add(effect, from, yaw, size) : preview.addTurned(effect, from, turn, size);
        var life = lifeOf(planned);
        if (!Float.isNaN(life) && isLifeFixed(planned)) {
            preview.fixLife(handle, life);
        } else if (!Float.isNaN(life)) {
            preview.adjustLife(handle, life);
        }
        if (!Float.isNaN(planned.forced())) {
            forcedEnds.add(new Forced(handle, planned.at() + planned.forced()));
        }
        if (planned.action().channeling()) {
            aimBeam(preview, handle, planned);
        }
        if (to != null) {
            flights.add(new Flight(
                    handle,
                    planned,
                    planned.shot(from, effect, played.flyingTime()),
                    ride(preview, planned, from, yaw)));
        } else if (follows(planned)) {
            attached.add(new Attached(handle, planned, from, turn));
        }
    }

    private void aimBeam(EffectPreview preview, int handle, Planned planned) {
        var end = shotPlan.contains(planned) && cast.drains() ? shooter : aimedAt;
        if (end == null) {
            return;
        }
        var chaining = planned.action().chaining();
        var counted = cast.style() == 13 || cast.style() == 16 && !chaining;
        var seconds = counted ? getShotSeconds() - firstPartSeconds + getPartLeft(planned.at()) : Float.NaN;
        preview.aimBeam(handle, end.location(), seconds, cast.style() == 16 && chaining);
    }

    private void holdArrow(EffectPreview preview) {
        var flight = arrowFlight;
        if (flight == null) {
            return;
        }
        var action = flight.action();
        var effect = parts.get(SkillEffects.key(action.effect()));
        if (effect == null) {
            return;
        }
        if (handUnseen) {
            heldArrows.add(UNSEEN_ARROW);
            return;
        }
        var pawn = flight.on();
        var from = pawn.point(action);
        var turn = pawn.handTurn(action);
        var size = pawn.sizeScale(action);
        heldArrows.add(
                turn == null
                        ? preview.add(effect, from, pawn.yaw(), size)
                        : preview.addTurned(effect, from, turn, size));
    }

    private void removeArrow(EffectPreview preview, int arrow) {
        if (arrow != UNSEEN_ARROW) {
            preview.remove(arrow);
        }
    }

    private void moveHeldArrows(EffectPreview preview) {
        var flight = arrowFlight;
        if (flight == null || heldArrows.isEmpty()) {
            return;
        }
        var at = flight.on().point(flight.action());
        var turn = flight.on().handTurn(flight.action());
        for (var i = 0; i < heldArrows.size(); i++) {
            if (heldArrows.get(i) == UNSEEN_ARROW) {
                continue;
            }
            preview.move(heldArrows.get(i), at);
            if (turn != null) {
                preview.turn(heldArrows.get(i), turn);
            }
        }
    }

    private void shootEarly(EffectPreview preview, float at, boolean audible) {
        var shift = at - getShotSeconds();
        for (var planned : shotPlan) {
            var moved = planned.shifted(shift);
            if (planned == arrowFlight) {
                if (!heldArrows.isEmpty()) {
                    spawn(preview, moved);
                }
            } else if (cast.style() == 8) {
                waiting.add(moved);
            }
        }
        if (audible && shooter != null && aimedAt != null) {
            for (var sound : sounds) {
                if (sound.moment() == SkillSound.Moment.SHOT) {
                    preview.playSound(sound.sound(), (sound.onTarget() ? aimedAt : shooter).location());
                }
            }
        }
    }

    private void startChannel(float at) {
        for (var planned : channeled) {
            var moved = planned.shifted(at);
            channelCopies.add(moved);
            waiting.add(moved);
        }
    }

    private boolean isChanneled(Planned planned) {
        return channeled.contains(planned) || channelCopies.contains(planned);
    }

    private void forgetLayout() {
        laidOutFor = null;
        firstPartSeconds = 0f;
        preShots = NONE;
        earlyShots = NONE;
        channelMarks = NONE;
        channelByMarks = false;
        shotTimes = null;
        hitTimes = null;
    }

    private void markShots() {
        var shots = new FloatArrayList();
        var hits = new FloatArrayList();
        var nextHeld = 0;
        var held = 0;
        for (var early : earlyShots) {
            while (nextHeld < preShots.length && preShots[nextHeld] <= early) {
                nextHeld++;
                held++;
            }
            var shift = early - getShotSeconds();
            var first = Float.MAX_VALUE;
            var fires = false;
            for (var planned : shotPlan) {
                if (planned == arrowFlight ? held > 0 : cast.style() == 8) {
                    fires = true;
                    if (planned.carries()) {
                        first = Math.min(first, planned.at() + shift + flightSeconds(planned));
                    }
                }
            }
            if (arrowFlight != null && held > 0) {
                held--;
            }
            if (fires) {
                shots.add(early);
            }
            if (first < Float.MAX_VALUE) {
                hits.add(first);
            }
        }
        shots.add(getShotSeconds());
        hits.add(getHitSeconds());
        shotTimes = shots.toArray();
        hitTimes = hits.toArray();
    }

    private float flightSeconds(Planned planned) {
        var effect = parts.get(SkillEffects.key(planned.action().effect()));
        return effect == null ? 0f : planned.flightSeconds(effect, played.flyingTime());
    }

    private int ride(EffectPreview preview, Planned planned, Vec3 from, float yaw) {
        var rider = planned.rider();
        var effect = rider == null ? null : parts.get(SkillEffects.key(rider.effect()));
        return effect == null ? -1 : preview.add(effect, from, yaw, 1f);
    }

    private Map<String, EffectParts> withClientArrows(Map<String, EffectParts> effects) {
        var all = new HashMap<>(effects);
        for (var entry : clientParts.entrySet()) {
            var effect = entry.getValue();
            if (effect.effect().emitters() == 0 && isProjectile(effect)) {
                all.putIfAbsent(entry.getKey(), effect);
            }
        }
        return Map.copyOf(all);
    }

    private boolean shoots() {
        return !played.actions(Phase.SHOT).isEmpty() && findArrow() != null;
    }

    private @Nullable Action findArrow() {
        Action found = null;
        for (var action : played.actions(Phase.PRESHOT)) {
            var effect = parts.get(SkillEffects.key(action.effect()));
            if (effect != null && isProjectile(effect)) {
                found = action;
            }
        }
        return found;
    }

    private void arrive(EffectPreview preview, Planned carrier, float at, boolean audible) {
        var target = Objects.requireNonNull(carrier.to());
        for (var action : carriedHit) {
            var effect = parts.get(SkillEffects.key(action.effect()));
            if (effect == null) {
                continue;
            }
            var start = at + Math.max(0f, action.delay());
            waiting.add(
                    cast.drainsBack() && isProjectile(effect)
                            ? new Planned(action, start, target, carrier.on(), Bend.RETURN, false)
                            : new Planned(action, start, action.onTarget() ? target : carrier.on()));
        }
        if (audible) {
            for (var sound : carriedSounds) {
                var on = sound.onTarget() ? target : carrier.on();
                preview.playSound(sound.sound(), on.location());
            }
        }
    }

    private float getSpawnYaw(Pawn on) {
        var caster = shooter;
        var target = aimedAt;
        if (caster == null) {
            return on.yaw();
        }
        return target == null || target == caster ? caster.yaw() : towards(caster, target);
    }

    private static int countBefore(float[] moments, float time) {
        var count = 0;
        while (count < moments.length && moments[count] < time) {
            count++;
        }
        return count;
    }

    private static float towards(Pawn from, Pawn to) {
        var dx = to.feet().getX() - from.feet().getX();
        var dy = to.feet().getY() - from.feet().getY();
        return dx == 0f && dy == 0f ? from.yaw() : (float) Math.atan2(dy, dx);
    }

    private static Vec3 aim(Pawn pawn) {
        return pawn.effectTarget();
    }

    private static float fixedLifeTime(Action action, float shot) {
        var delay = action.delay();
        if (delay == 0f) {
            return 0f;
        }
        var wait = delay > 0f ? delay : shot + delay;
        return wait > 0f ? shot - wait : shot;
    }

    private static boolean isProjectile(EffectParts effect) {
        return "NSkillProjectile".equalsIgnoreCase(effect.effect().superName());
    }

    private static float[] turnedAxes(float[] bone, float yaw) {
        var cos = (float) Math.cos(yaw);
        var sin = (float) Math.sin(yaw);
        var world = new float[9];
        for (var axis = 0; axis < 3; axis++) {
            world[axis * 3] = bone[axis * 3] * cos - bone[axis * 3 + 1] * sin;
            world[axis * 3 + 1] = bone[axis * 3] * sin + bone[axis * 3 + 1] * cos;
            world[axis * 3 + 2] = bone[axis * 3 + 2];
        }
        return world;
    }

    private static float loop(float seconds, float length) {
        return length > 0 ? seconds % length : 0f;
    }

    private static boolean follows(Planned planned) {
        return planned.action().attachOn() == SkillEffects.AM_TRAIL
                || planned.on().model() != null && hangsOnBone(planned.action());
    }

    private static boolean hangsOnBone(Action action) {
        return switch (action.attachOn()) {
            case SkillEffects.AM_RH,
                    SkillEffects.AM_LH,
                    SkillEffects.AM_RA,
                    SkillEffects.AM_LA,
                    SkillEffects.AM_WING,
                    SkillEffects.AM_BONE_SPECIFIED,
                    SkillEffects.AM_RIGHT_FOOT,
                    SkillEffects.AM_LEFT_FOOT -> true;
            default -> false;
        };
    }
}
