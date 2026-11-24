package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.npc.script.ScriptEffect;
import com.rost.cruma.core.npc.script.ScriptPawn;
import com.rost.cruma.core.npc.script.ScriptValues.Rotator;
import com.rost.cruma.core.npc.script.ScriptValues.Vector;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.EffectParts;
import com.rost.cruma.viewer.render.api.EffectPreview;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class ScriptEffects {
    private static final Logger log = LogManager.getLogger(ScriptEffects.class);

    private final ScriptPawn pawn;
    private final String owner;
    private final Function<String, @Nullable EffectParts> parts;
    private final Map<ScriptEffect, Shown> shown = new IdentityHashMap<>();
    private final Set<ScriptEffect> hidden = Collections.newSetFromMap(new IdentityHashMap<>());

    public ScriptEffects(ScriptPawn pawn, String owner, Function<String, @Nullable EffectParts> parts) {
        this.pawn = pawn;
        this.owner = owner;
        this.parts = parts;
    }

    public interface Body {
        Vec3 getPlace();

        URotator getTurn();

        @Nullable
        Bone findWorldBone(String name);
    }

    public record Bone(Vec3 place, float[] axes, float[] meshAxes) {}

    public record Placed(Vec3 place, Vec3 turn) {}

    public static Placed place(ScriptEffect effect, Body body) {
        var name = effect.getBone();
        var bone = name == null ? null : body.findWorldBone(name);
        if (bone != null) {
            return hang(
                    bone.place(),
                    effect.isAbsoluteAttach() ? bone.meshAxes() : bone.axes(),
                    bone.axes(),
                    vec(effect.getRelativeLocation()),
                    rotation(effect.getRelativeRotation()),
                    effect.isAbsoluteAttach());
        }
        var spawned = rotation(effect.getSpawnRotation()).vector();
        if (effect.isBased()) {
            return new Placed(body.getPlace().plus(vec(effect.getRelativeLocation())), spawned);
        }
        if (effect.isTrailer() && effect.isOwned()) {
            var offset = new Vec3();
            if (effect.isRelativeTrail()) {
                offset = turn(body.getTurn(), vec(effect.getRelativeTrailOffset()));
            } else if (effect.isTrailerPrePivot()) {
                offset = vec(effect.getTrailerPrePivotOffset());
            }
            var rotation = effect.isTrailerSameRotation() ? body.getTurn().vector() : spawned;
            return new Placed(body.getPlace().plus(offset), rotation);
        }
        return new Placed(vec(effect.getSpawnLocation()), spawned);
    }

    public static Placed hang(
            Vec3 bone,
            float[] along,
            float[] boneAxes,
            Vec3 relativeLocation,
            URotator relativeRotation,
            boolean absolute) {
        var x = bone.getX();
        var y = bone.getY();
        var z = bone.getZ();
        for (var axis = 0; axis < 3; axis++) {
            var amount =
                    axis == 0 ? relativeLocation.getX() : axis == 1 ? relativeLocation.getY() : relativeLocation.getZ();
            x += along[axis * 3] * amount;
            y += along[axis * 3 + 1] * amount;
            z += along[axis * 3 + 2] * amount;
        }
        var turn = absolute ? relativeRotation.vector() : orthoRotation(within(boneAxes, relativeRotation));
        return new Placed(new Vec3(x, y, z), turn);
    }

    public static float[] within(float[] bone, URotator turn) {
        var own = axes(turn);
        var result = new float[9];
        for (var axis = 0; axis < 3; axis++) {
            for (var row = 0; row < 3; row++) {
                result[axis * 3 + row] = own[axis * 3] * bone[row]
                        + own[axis * 3 + 1] * bone[3 + row]
                        + own[axis * 3 + 2] * bone[6 + row];
            }
        }
        return result;
    }

    public static float[] axes(URotator turn) {
        var pitch = URotator.toRadians(turn.pitch());
        var yaw = URotator.toRadians(turn.yaw());
        var roll = URotator.toRadians(turn.roll());
        var sp = (float) Math.sin(pitch);
        var cp = (float) Math.cos(pitch);
        var sy = (float) Math.sin(yaw);
        var cy = (float) Math.cos(yaw);
        var sr = (float) Math.sin(roll);
        var cr = (float) Math.cos(roll);
        return new float[] {
            cp * cy,
            cp * sy,
            sp,
            sr * sp * cy - cr * sy,
            sr * sp * sy + cr * cy,
            -sr * cp,
            -(cr * sp * cy + sr * sy),
            cy * sr - cr * sp * sy,
            cr * cp
        };
    }

    public static Vec3 orthoRotation(float[] axes) {
        var xx = axes[0];
        var xy = axes[1];
        var xz = axes[2];
        var pitch = Math.atan2(xz, Math.sqrt(xx * xx + xy * xy));
        var yaw = Math.atan2(xy, xx);
        var plainY = new double[] {-Math.sin(yaw), Math.cos(yaw), 0};
        var roll = Math.atan2(
                axes[6] * plainY[0] + axes[7] * plainY[1] + axes[8] * plainY[2],
                axes[3] * plainY[0] + axes[4] * plainY[1] + axes[5] * plainY[2]);
        return new Vec3((float) -roll, (float) -pitch, (float) yaw);
    }

    public static Vec3 turn(URotator rotation, Vec3 vector) {
        var axes = axes(rotation);
        var x = vector.getX();
        var y = vector.getY();
        var z = vector.getZ();
        return new Vec3(
                axes[0] * x + axes[3] * y + axes[6] * z,
                axes[1] * x + axes[4] * y + axes[7] * z,
                axes[2] * x + axes[5] * y + axes[8] * z);
    }

    public void settle(@Nullable EffectPreview preview) {
        for (var effect : List.copyOf(pawn.getEffects())) {
            var drawn = shown.get(effect);
            if (drawn == null) {
                if (effect.isDestroyed()) {
                    hidden.remove(effect);
                    pawn.forget(effect);
                }
                continue;
            }
            if (effect.isDestroyed()) {
                if (preview != null) {
                    preview.remove(drawn.handle);
                }
                shown.remove(effect);
                pawn.forget(effect);
            } else if (effect.isKilled() && !drawn.killed && preview != null) {
                preview.kill(drawn.handle);
                drawn.killed = true;
            }
        }
    }

    public void show(EffectPreview preview, Body body) {
        for (var effect : List.copyOf(pawn.getEffects())) {
            if (shown.containsKey(effect) || effect.isStarted() && !hidden.contains(effect)) {
                continue;
            }
            hidden.remove(effect);
            effect.markStarted();
            var drawn = parts.apply(effect.getEffectClass());
            if (drawn == null) {
                log.debug("The client has no effect {} for the script of {}", effect.getEffectClass(), owner);
                pawn.forget(effect);
                continue;
            }
            var at = place(effect, body);
            var handle = preview.addTurned(drawn, at.place(), at.turn(), effect.getSizeScale());
            var entry = new Shown(handle, effect, at);
            shown.put(effect, entry);
            if (effect.isKilled()) {
                preview.kill(handle);
                entry.killed = true;
            }
        }
    }

    public void follow(EffectPreview preview, Body body) {
        for (var iterator = shown.values().iterator(); iterator.hasNext(); ) {
            var drawn = iterator.next();
            if (preview.isFinished(drawn.handle)) {
                preview.remove(drawn.handle);
                iterator.remove();
                pawn.forget(drawn.effect);
                continue;
            }
            var at = place(drawn.effect, body);
            if (!at.place().equals(drawn.at.place())) {
                preview.move(drawn.handle, at.place());
            }
            if (!at.turn().equals(drawn.at.turn())) {
                preview.turn(drawn.handle, at.turn());
            }
            drawn.at = at;
        }
    }

    public void hide(@Nullable EffectPreview preview) {
        for (var drawn : shown.values()) {
            if (preview != null) {
                preview.remove(drawn.handle);
            }
            hidden.add(drawn.effect);
        }
        shown.clear();
    }

    public List<Integer> leave(@Nullable EffectPreview preview) {
        var left = new ArrayList<Integer>();
        for (var drawn : shown.values()) {
            if (drawn.effect.isTrailer() && drawn.effect.isTrailerNoOwnerDestroy() && preview != null) {
                preview.remove(drawn.handle);
            } else {
                left.add(drawn.handle);
            }
        }
        shown.clear();
        hidden.clear();
        return left;
    }

    public void remove(@Nullable EffectPreview preview) {
        if (preview != null) {
            for (var drawn : shown.values()) {
                preview.remove(drawn.handle);
            }
        }
        shown.clear();
        hidden.clear();
    }

    public int getShownCount() {
        return shown.size();
    }

    private static Vec3 vec(Vector vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    private static URotator rotation(Rotator rotator) {
        return new URotator(rotator.pitch(), rotator.yaw(), rotator.roll());
    }

    private static final class Shown {
        final int handle;
        final ScriptEffect effect;
        Placed at;
        boolean killed;

        Shown(int handle, ScriptEffect effect, Placed at) {
            this.handle = handle;
            this.effect = effect;
            this.at = at;
        }
    }
}
