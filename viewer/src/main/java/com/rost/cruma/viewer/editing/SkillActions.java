package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.EffectEditSession.ActionInfo;
import com.rost.cruma.core.editing.EffectEdits.ActionEdit;
import com.rost.cruma.core.editing.EffectEdits.ActionMove;
import com.rost.cruma.core.editing.EffectEdits.ActionSlot;
import com.rost.cruma.core.editing.EffectEdits.AddedAction;
import com.rost.cruma.core.editing.EffectEdits.SkillEdit;
import com.rost.cruma.core.unreal.SkillEffects;
import com.rost.cruma.core.unreal.SkillEffects.Action;
import com.rost.cruma.core.unreal.SkillEffects.Phase;
import com.rost.cruma.core.unreal.UEmitter.UParticleEmitter;
import com.rost.cruma.core.world.EffectParts;
import com.rost.cruma.core.world.EmitterLife;
import com.rost.cruma.viewer.render.ParticleEmitter;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

public final class SkillActions {
    public static final float LONGEST_DELAY = 3600;

    public static final float LONGEST_FLIGHT = 60;
    public static final float LARGEST_SCALE = 100_000;
    public static final float FURTHEST_OFFSET = 1_000_000;

    public static final List<Attach> ATTACH_POINTS = List.of(
            new Attach(SkillEffects.AM_NONE, "None"),
            new Attach(SkillEffects.AM_LOCATION, "Place"),
            new Attach(SkillEffects.AM_TRAIL, "Follows the body"),
            new Attach(SkillEffects.AM_RH, "Right hand"),
            new Attach(SkillEffects.AM_LH, "Left hand"),
            new Attach(SkillEffects.AM_RA, "Right arm"),
            new Attach(SkillEffects.AM_LA, "Left arm"),
            new Attach(SkillEffects.AM_WING, "Chest"),
            new Attach(SkillEffects.AM_BONE_SPECIFIED, "Bone"),
            new Attach(SkillEffects.AM_ALIAS_SPECIFIED, "Alias"),
            new Attach(SkillEffects.AM_AGATHION, "Agathion"),
            new Attach(SkillEffects.AM_RIGHT_FOOT, "Right foot"),
            new Attach(SkillEffects.AM_LEFT_FOOT, "Left foot"));

    private static final String ADDED_NAME = "L2EffectEmitter_added_";

    private SkillActions() {}

    public record Attach(int value, String name) {}

    public static String attachName(int value) {
        for (var point : ATTACH_POINTS) {
            if (point.value() == value) {
                return point.name();
            }
        }
        return "Value " + value;
    }

    public static Action merged(Action action, ActionEdit edit) {
        var offset = edit.offset();
        return new Action(
                edit.effectClass() != null ? edit.effectClass() : action.effect(),
                edit.attachOn() != null ? edit.attachOn() : action.attachOn(),
                edit.attachBoneName() != null ? edit.attachBoneName() : action.bone(),
                edit.spawnOnTarget() != null ? edit.spawnOnTarget() : action.onTarget(),
                edit.onMultiTarget() != null ? edit.onMultiTarget() : action.onMultiTarget(),
                edit.relativeToCylinder() != null ? edit.relativeToCylinder() : action.relativeToCylinder(),
                offset != null ? new Vec3(offset.get(0), offset.get(1), offset.get(2)) : action.offset(),
                edit.scaleSize() != null ? edit.scaleSize() : action.scale(),
                edit.spawnDelay() != null ? edit.spawnDelay() : action.delay(),
                action.absolute(),
                action.turn(),
                action.curve(),
                action.adjustLife(),
                action.forceLife(),
                action.channeling(),
                action.chaining());
    }

    public static ActionEdit withoutClientValues(ActionEdit edit, @Nullable Action client) {
        if (client == null) {
            return edit;
        }
        var result = edit;
        if (edit.effectClass() != null && edit.effectClass().equalsIgnoreCase(client.effect())) {
            result = result.withEffectClass(null);
        }
        if (edit.attachOn() != null && edit.attachOn() == client.attachOn()) {
            result = result.withAttachOn(null);
        }
        if (edit.attachBoneName() != null && edit.attachBoneName().equalsIgnoreCase(client.bone())) {
            result = result.withAttachBoneName(null);
        }
        if (edit.spawnOnTarget() != null && edit.spawnOnTarget() == client.onTarget()) {
            result = result.withSpawnOnTarget(null);
        }
        if (edit.onMultiTarget() != null && edit.onMultiTarget() == client.onMultiTarget()) {
            result = result.withOnMultiTarget(null);
        }
        if (edit.relativeToCylinder() != null && edit.relativeToCylinder() == client.relativeToCylinder()) {
            result = result.withRelativeToCylinder(null);
        }
        var offset = edit.offset();
        if (offset != null
                && offset.get(0) == client.offset().getX()
                && offset.get(1) == client.offset().getY()
                && offset.get(2) == client.offset().getZ()) {
            result = result.withOffset(null);
        }
        if (edit.scaleSize() != null && edit.scaleSize() == client.scale()) {
            result = result.withScaleSize(null);
        }
        if (edit.spawnDelay() != null && edit.spawnDelay() == client.delay()) {
            result = result.withSpawnDelay(null);
        }
        return result;
    }

    public static SkillEdit moved(SkillEdit edit, String name, Phase from, Phase to) {
        if (isAdded(edit, name)) {
            var added = new ArrayList<AddedAction>();
            for (var action : edit.added()) {
                added.add(action.name().equalsIgnoreCase(name) ? new AddedAction(action.name(), to) : action);
            }
            return new SkillEdit(edit.flyingTime(), edit.actions(), added, edit.removed(), edit.moved());
        }
        var moves = new ArrayList<>(edit.moved());
        var start = from;
        for (var iterator = moves.iterator(); iterator.hasNext(); ) {
            var move = iterator.next();
            if (move.name().equalsIgnoreCase(name) && move.to() == from) {
                start = move.from();
                iterator.remove();
            }
        }
        if (start != to) {
            moves.add(new ActionMove(name, start, to));
        }
        return new SkillEdit(edit.flyingTime(), edit.actions(), edit.added(), edit.removed(), moves);
    }

    public static SkillEdit removed(SkillEdit edit, String name, Phase phase) {
        if (isAdded(edit, name)) {
            return edit.withRemoved(new ActionSlot(name, phase));
        }
        var moves = new ArrayList<>(edit.moved());
        var start = phase;
        for (var iterator = moves.iterator(); iterator.hasNext(); ) {
            var move = iterator.next();
            if (move.name().equalsIgnoreCase(name) && move.to() == phase) {
                start = move.from();
                iterator.remove();
            }
        }
        return new SkillEdit(edit.flyingTime(), edit.actions(), edit.added(), edit.removed(), moves)
                .withRemoved(new ActionSlot(name, start));
    }

    public static String freeName(List<ActionInfo> actions, SkillEdit edit, String className) {
        var taken = new HashSet<String>();
        for (var action : actions) {
            taken.add(action.key().toLowerCase(Locale.ROOT));
        }
        for (var action : edit.added()) {
            taken.add(action.name().toLowerCase(Locale.ROOT));
        }
        var dot = className.lastIndexOf('.');
        var base = dot < 0 ? ADDED_NAME : "SkillAction_" + className.substring(dot + 1) + "_added_";
        var number = 0;
        while (taken.contains((base + letters(number)).toLowerCase(Locale.ROOT))) {
            number++;
        }
        return base + letters(number);
    }

    public static float seconds(EffectParts parts) {
        return seconds(parts, Float.NaN);
    }

    public static float seconds(EffectParts parts, float life) {
        return seconds(parts, life, false);
    }

    public static float seconds(EffectParts parts, float life, boolean fixed) {
        var longest = 0f;
        for (var sprite : parts.sprites()) {
            longest = longer(longest, seconds(sprite.emitter(), parts, life, fixed));
        }
        for (var mesh : parts.meshes()) {
            longest = longer(longest, seconds(mesh.emitter(), parts, life, fixed));
        }
        for (var beam : parts.beams()) {
            longest = longer(longest, seconds(beam.emitter(), parts, life, fixed));
        }
        return longest > 0f || longest == 0f && parts.count() > 0 ? longest : -1f;
    }

    private static float seconds(UParticleEmitter emitter, EffectParts parts, float life, boolean fixed) {
        var own = EmitterLife.of(emitter);
        var values = Float.isNaN(life)
                ? own
                : fixed
                        ? EmitterLife.fixed(emitter, life)
                        : EmitterLife.adjusted(emitter, life, parts.forcedLifeTime());
        var changed = values.lifetimeMax() != own.lifetimeMax();
        var lifetime = changed ? Math.max(0f, values.lifetimeMax()) : values.lifetimeMax();
        if (emitter.isRespawnDeadParticles() || emitter.getParticlesPerSecond() > 0f || lifetime <= 0f && !changed) {
            return -1f;
        }
        var count = values.maxParticles();
        if (values.maxParticles() != own.maxParticles()) {
            if (count <= 0) {
                return emitter.getInitialDelayMax();
            }
            count = Math.min(count, ParticleEmitter.POOL_CAP);
        }
        var fill = 0f;
        if (emitter.isAutomaticInitialSpawning()) {
            fill = Math.max(0f, (values.lifetimeMin() + lifetime) * .5f);
        } else if (emitter.getInitialParticlesPerSecond() > 0f) {
            fill = count / emitter.getInitialParticlesPerSecond();
        }
        return emitter.getInitialDelayMax() + fill + lifetime;
    }

    private static float longer(float longest, float seconds) {
        return longest < 0f || seconds < 0f ? -1f : Math.max(longest, seconds);
    }

    private static boolean isAdded(SkillEdit edit, String name) {
        for (var action : edit.added()) {
            if (action.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static String letters(int number) {
        var text = new StringBuilder();
        for (var left = number; left >= 0; left = left / 26 - 1) {
            text.insert(0, (char) ('a' + left % 26));
        }
        return text.toString();
    }
}
