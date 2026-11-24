package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.SkeletalPose;
import com.rost.cruma.core.unreal.UMeshAnimation;
import com.rost.cruma.core.unreal.UMeshAnimation.CameraMark;
import com.rost.cruma.core.unreal.UMeshAnimation.Motion;
import com.rost.cruma.core.unreal.UMeshAnimation.NamedBone;
import com.rost.cruma.core.unreal.UMeshAnimation.Sequence;
import com.rost.cruma.core.unreal.USkeletalMesh;
import com.rost.cruma.core.unreal.USkeletalMesh.Bone;
import glm_.vec3.Vec3;
import java.util.List;
import java.util.function.IntFunction;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class SceneRig {
    private static final float DEFAULT_RATE = 30;

    private final Bone[] bones;
    private final NamedBone[] animationBones;
    private final List<Sequence> sequences;
    private final IntFunction<Motion> decode;
    private final float[] toLocal;
    private final float scale;
    private final @Nullable Motion[] motions;
    private final boolean[] unreadable;
    private final int wait;
    private final int fight;

    public SceneRig(
            Bone[] bones,
            NamedBone[] animationBones,
            List<Sequence> sequences,
            IntFunction<Motion> decode,
            float[] toLocal,
            float scale) {
        this(bones, animationBones, sequences, decode, toLocal, scale, -1, -1);
    }

    private SceneRig(
            Bone[] bones,
            NamedBone[] animationBones,
            List<Sequence> sequences,
            IntFunction<Motion> decode,
            float[] toLocal,
            float scale,
            int wait,
            int fight) {
        this.wait = wait;
        this.fight = fight;
        this.bones = bones;
        this.animationBones = animationBones;
        this.sequences = List.copyOf(sequences);
        this.decode = decode;
        this.toLocal = toLocal.clone();
        this.scale = scale;
        motions = new Motion[sequences.size()];
        unreadable = new boolean[sequences.size()];
    }

    static SceneRig of(USkeletalMesh mesh, UMeshAnimation animation, float scale) {
        var origin = mesh.convertMeshToLocal(new Vec3(0, 0, 0));
        var x = mesh.convertMeshToLocal(new Vec3(1, 0, 0));
        var y = mesh.convertMeshToLocal(new Vec3(0, 1, 0));
        var z = mesh.convertMeshToLocal(new Vec3(0, 0, 1));
        var toLocal = new float[] {
            x.getX() - origin.getX(),
            x.getY() - origin.getY(),
            x.getZ() - origin.getZ(),
            y.getX() - origin.getX(),
            y.getY() - origin.getY(),
            y.getZ() - origin.getZ(),
            z.getX() - origin.getX(),
            z.getY() - origin.getY(),
            z.getZ() - origin.getZ(),
            origin.getX(),
            origin.getY(),
            origin.getZ()
        };
        return new SceneRig(
                mesh.getBones(),
                animation.getBones(),
                List.of(animation.getSequences()),
                animation::decodeMotion,
                toLocal,
                scale);
    }

    public SceneRig scaled(float drawScale) {
        return drawScale == scale
                ? this
                : new SceneRig(bones, animationBones, sequences, decode, toLocal, drawScale, wait, fight);
    }

    public SceneRig waiting(@Nullable String waitName, @Nullable String fightName) {
        var named = waitName == null ? -1 : findSequence(waitName);
        var fighting = fightName == null ? -1 : findSequence(fightName);
        return named == wait && fighting == fight
                ? this
                : new SceneRig(bones, animationBones, sequences, decode, toLocal, scale, named, fighting);
    }

    public int findSequence(String name) {
        for (var i = 0; i < sequences.size(); i++) {
            if (sequences.get(i).name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    public String getName(int sequence) {
        return sequences.get(sequence).name();
    }

    public int getIdle() {
        if (wait >= 0) {
            return wait;
        }
        var exact = findSequence("wait");
        if (exact >= 0) {
            return exact;
        }
        var found = find("wait", "idle", "atkwait");
        return Math.max(0, found);
    }

    public int getFightIdle() {
        return fight >= 0 ? fight : find("atkwait");
    }

    public boolean waits() {
        return wait >= 0 || find("wait", "idle", "atkwait") >= 0;
    }

    public int find(String... prefixes) {
        for (var prefix : prefixes) {
            for (var i = 0; i < sequences.size(); i++) {
                if (sequences.get(i).name().regionMatches(true, 0, prefix, 0, prefix.length())) {
                    return i;
                }
            }
        }
        return -1;
    }

    public int getSequenceCount() {
        return sequences.size();
    }

    public int findBone(String name) {
        for (var i = 0; i < bones.length; i++) {
            if (bones[i].name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    public @Nullable Coords getCoords(int sequence, float seconds, int bone) {
        if (bone < 0 || bone >= bones.length || sequence < 0 || sequence >= sequences.size()) {
            return null;
        }
        var frame = Math.clamp(seconds, 0f, getSeconds(sequence)) * rate(sequences.get(sequence));
        var posed = SkeletalPose.posedBone(bones, motion(sequence), animationBones, frame, bone);
        var turn = new double[] {posed[0], posed[1], posed[2], posed[3], 0, 0, 0};
        var axes = new float[9];
        for (var axis = 0; axis < 3; axis++) {
            var unit = SkeletalPose.place(turn, new Vec3(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0));
            var scaled = scaled(unit);
            axes[axis * 3] = scaled.getX();
            axes[axis * 3 + 1] = scaled.getY();
            axes[axis * 3 + 2] = scaled.getZ();
        }
        return new Coords(place((float) posed[4], (float) posed[5], (float) posed[6]), axes);
    }

    public float[] getMeshAxes() {
        return new float[] {
            toLocal[0] * scale,
            toLocal[1] * scale,
            toLocal[2] * scale,
            toLocal[3] * scale,
            toLocal[4] * scale,
            toLocal[5] * scale,
            toLocal[6] * scale,
            toLocal[7] * scale,
            toLocal[8] * scale
        };
    }

    public float getSeconds(int sequence) {
        var played = sequences.get(sequence);
        return Math.max(1, played.frameCount()) / rate(played);
    }

    public int getFrames(int sequence) {
        return sequences.get(sequence).frameCount();
    }

    public List<CameraMark> getMarks(int sequence) {
        return sequences.get(sequence).cameraMarks();
    }

    public @Nullable Frame frame(int sequence, float seconds, int bone) {
        if (bone < 0 || bone >= bones.length) {
            return null;
        }
        var frame = Math.clamp(seconds, 0f, getSeconds(sequence)) * rate(sequences.get(sequence));
        var posed = SkeletalPose.posedBone(bones, motion(sequence), animationBones, frame, bone);
        var turn = new double[] {posed[0], posed[1], posed[2], posed[3], 0, 0, 0};
        return new Frame(
                place((float) posed[4], (float) posed[5], (float) posed[6]),
                axis(SkeletalPose.place(turn, new Vec3(1, 0, 0))),
                axis(SkeletalPose.place(turn, new Vec3(0, 1, 0))));
    }

    public record Frame(Vec3 place, Vec3 look, Vec3 up) {}

    public record Coords(Vec3 place, float[] axes) {}

    private Vec3 place(float x, float y, float z) {
        return new Vec3(
                (toLocal[0] * x + toLocal[3] * y + toLocal[6] * z + toLocal[9]) * scale,
                (toLocal[1] * x + toLocal[4] * y + toLocal[7] * z + toLocal[10]) * scale,
                (toLocal[2] * x + toLocal[5] * y + toLocal[8] * z + toLocal[11]) * scale);
    }

    private Vec3 scaled(Vec3 bone) {
        return new Vec3(
                (toLocal[0] * bone.getX() + toLocal[3] * bone.getY() + toLocal[6] * bone.getZ()) * scale,
                (toLocal[1] * bone.getX() + toLocal[4] * bone.getY() + toLocal[7] * bone.getZ()) * scale,
                (toLocal[2] * bone.getX() + toLocal[5] * bone.getY() + toLocal[8] * bone.getZ()) * scale);
    }

    private Vec3 axis(Vec3 bone) {
        var x = toLocal[0] * bone.getX() + toLocal[3] * bone.getY() + toLocal[6] * bone.getZ();
        var y = toLocal[1] * bone.getX() + toLocal[4] * bone.getY() + toLocal[7] * bone.getZ();
        var z = toLocal[2] * bone.getX() + toLocal[5] * bone.getY() + toLocal[8] * bone.getZ();
        var length = (float) Math.sqrt(x * x + y * y + z * z);
        return length > 0 ? new Vec3(x / length, y / length, z / length) : bone;
    }

    private @Nullable Motion motion(int sequence) {
        if (motions[sequence] == null && !unreadable[sequence]) {
            try {
                motions[sequence] = decode.apply(sequence);
            } catch (RuntimeException failure) {
                unreadable[sequence] = true;
                LogManager.getLogger(SceneRig.class)
                        .debug("Cannot read {}: {}", sequences.get(sequence).name(), failure.toString());
            }
        }
        return motions[sequence];
    }

    private static float rate(Sequence sequence) {
        return sequence.rate() > 0 ? sequence.rate() : DEFAULT_RATE;
    }
}
