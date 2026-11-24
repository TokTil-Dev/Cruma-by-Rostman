package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.SkeletalPose;
import com.rost.cruma.core.unreal.UMeshAnimation;
import com.rost.cruma.core.unreal.UMeshAnimation.Motion;
import com.rost.cruma.core.unreal.UMeshAnimation.NamedBone;
import com.rost.cruma.core.unreal.UMeshAnimation.Sequence;
import com.rost.cruma.core.unreal.USkeletalMesh;
import com.rost.cruma.core.unreal.USkeletalMesh.Bone;
import com.rost.cruma.core.unreal.USkeletalMesh.Face;
import com.rost.cruma.core.unreal.USkeletalMesh.Influence;
import com.rost.cruma.core.unreal.USkeletalMesh.Wedge;
import com.rost.cruma.core.world.UnrealLoader.NpcRig;
import com.rost.cruma.core.world.UnrealLoader.PlayerRig;
import glm_.vec3.Vec3;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import org.apache.logging.log4j.LogManager;
import org.eclipse.collections.api.factory.primitive.FloatLists;
import org.eclipse.collections.api.list.primitive.ImmutableFloatList;
import org.jetbrains.annotations.Nullable;

public final class NpcAnimation {
    private static final float DEFAULT_RATE = 30;

    private final NpcModel model;
    private final Bone[] bones;
    private final Vec3[] points;
    private final Influence[] influences;
    private final int[] wedgePoints;
    private final int[] triangles;
    private final float[] toModel;
    private final NamedBone[] animationBones;
    private final List<Sequence> sequences;
    private final IntFunction<Motion> decode;
    private final @Nullable Motion[] motions;
    private final boolean[] unreadable;
    private final int idle;
    private final int fight;
    private final boolean namedWait;

    public record Pose(float[] positions, float[] normals, Map<String, Vec3> bones, Map<String, float[]> axes) {
        public Pose(float[] positions, float[] normals, Map<String, Vec3> bones) {
            this(positions, normals, bones, Map.of());
        }
    }

    NpcAnimation(
            NpcModel model,
            Bone[] bones,
            Vec3[] points,
            Influence[] influences,
            Wedge[] wedges,
            Face[] faces,
            float[] toModel,
            NamedBone[] animationBones,
            List<Sequence> sequences,
            IntFunction<Motion> decode,
            int idle) {
        this(
                model,
                bones,
                points,
                influences,
                wedges,
                faces,
                toModel,
                animationBones,
                sequences,
                decode,
                idle,
                -1,
                false);
    }

    NpcAnimation(
            NpcModel model,
            Bone[] bones,
            Vec3[] points,
            Influence[] influences,
            Wedge[] wedges,
            Face[] faces,
            float[] toModel,
            NamedBone[] animationBones,
            List<Sequence> sequences,
            IntFunction<Motion> decode,
            int idle,
            int fight,
            boolean namedWait) {
        this.fight = fight;
        this.namedWait = namedWait;
        this.model = model;
        this.bones = bones;
        this.points = points;
        this.influences = influences;
        wedgePoints = new int[wedges.length];
        for (var i = 0; i < wedges.length; i++) {
            wedgePoints[i] = wedges[i].vertexIndex();
        }
        triangles = new int[faces.length * 3];
        for (var i = 0; i < faces.length; i++) {
            triangles[i * 3] = faces[i].wedge0();
            triangles[i * 3 + 1] = faces[i].wedge1();
            triangles[i * 3 + 2] = faces[i].wedge2();
        }
        this.toModel = toModel.clone();
        this.animationBones = animationBones;
        this.sequences = List.copyOf(sequences);
        this.decode = decode;
        motions = new Motion[sequences.size()];
        unreadable = new boolean[sequences.size()];
        this.idle = Math.clamp(idle, 0, Math.max(0, sequences.size() - 1));
    }

    public static @Nullable NpcAnimation of(NpcRig rig) {
        return of(rig, null, null);
    }

    public static @Nullable NpcAnimation of(NpcRig rig, @Nullable String waitName, @Nullable String fightName) {
        var animation = rig.animation();
        if (animation == null || animation.getSequences().length == 0) {
            return null;
        }
        var idle = UnrealLoader.npcIdleSequence(animation, waitName);
        var fight = fightName == null ? -1 : animation.findSequenceIndex(fightName);
        var named = waitName != null && animation.findSequenceIndex(waitName) >= 0;
        var mesh = rig.mesh();
        if (!rig.parts().isEmpty()) {
            var input = PlayerRigInput.of(mesh, rig.parts());
            return create(
                    rig.model(),
                    mesh,
                    input.bones(),
                    input.points(),
                    input.influences(),
                    input.wedges(),
                    input.faces(),
                    animation,
                    idle,
                    fight,
                    named);
        }
        return create(
                rig.model(),
                mesh,
                mesh.getBones(),
                mesh.getPoints(),
                mesh.getInfluences(),
                mesh.getWedges(),
                mesh.getFaces(),
                animation,
                idle,
                fight,
                named);
    }

    public static @Nullable NpcAnimation of(PlayerRig rig, @Nullable String wait, @Nullable String stance) {
        var animation = rig.animation();
        if (animation == null || animation.getSequences().length == 0) {
            return null;
        }
        var input = PlayerRigInput.of(rig);
        return create(
                rig.model(),
                rig.frame(),
                input.bones(),
                input.points(),
                input.influences(),
                input.wedges(),
                input.faces(),
                animation,
                UnrealLoader.playerSequence(animation, wait, stance),
                -1,
                false);
    }

    public NpcModel getModel() {
        return model;
    }

    public float[] getMeshAxes() {
        return java.util.Arrays.copyOf(toModel, 9);
    }

    public int getSequenceCount() {
        return sequences.size();
    }

    public String getName(int sequence) {
        return sequences.get(sequence).name();
    }

    public float getSeconds(int sequence) {
        var played = sequences.get(sequence);
        return Math.max(1, played.frameCount()) / rate(played);
    }

    public float getShotTime(int sequence) {
        return sequence < 0 || sequence >= sequences.size()
                ? -1f
                : sequences.get(sequence).shotTime();
    }

    public ImmutableFloatList getShotTimes(int sequence) {
        return sequence < 0 || sequence >= sequences.size()
                ? FloatLists.immutable.empty()
                : sequences.get(sequence).shotTimes();
    }

    public ImmutableFloatList getPreShotTimes(int sequence) {
        return sequence < 0 || sequence >= sequences.size()
                ? FloatLists.immutable.empty()
                : sequences.get(sequence).preShotTimes();
    }

    public ImmutableFloatList getChannelTimes(int sequence) {
        return sequence < 0 || sequence >= sequences.size()
                ? FloatLists.immutable.empty()
                : sequences.get(sequence).channelTimes();
    }

    public int getIdle() {
        return idle;
    }

    public int getFightIdle() {
        return fight >= 0 ? fight : find("atkwait");
    }

    public boolean waits() {
        return namedWait || find("wait", "idle", "atkwait") >= 0;
    }

    public int find(String... prefixes) {
        for (var prefix : prefixes) {
            var lower = prefix.toLowerCase(Locale.ROOT);
            for (var i = 0; i < sequences.size(); i++) {
                if (sequences.get(i).name().toLowerCase(Locale.ROOT).startsWith(lower)) {
                    return i;
                }
            }
        }
        return -1;
    }

    public int findSequence(String name) {
        for (var i = 0; i < sequences.size(); i++) {
            if (sequences.get(i).name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    public Pose getPose(int sequence, float seconds) {
        var played = sequences.get(sequence);
        return pose(motion(sequence), Math.clamp(seconds, 0f, getSeconds(sequence)) * rate(played));
    }

    public Pose getReferencePose() {
        return pose(null, 0);
    }

    private Pose pose(@Nullable Motion motion, float frame) {
        var skinned = new float[points.length * 3];
        var meshAxes = new HashMap<String, float[]>();
        var places = SkeletalPose.skin(
                bones, points, influences, motion, motion == null ? null : animationBones, frame, skinned, meshAxes);
        var placed = new float[skinned.length];
        for (var i = 0; i < points.length; i++) {
            place(skinned, i * 3, placed, i * 3);
        }
        var pointNormals = new float[placed.length];
        for (var i = 0; i + 2 < triangles.length; i += 3) {
            var p0 = wedgePoints[triangles[i]] * 3;
            var p1 = wedgePoints[triangles[i + 1]] * 3;
            var p2 = wedgePoints[triangles[i + 2]] * 3;
            var ax = placed[p1] - placed[p0];
            var ay = placed[p1 + 1] - placed[p0 + 1];
            var az = placed[p1 + 2] - placed[p0 + 2];
            var bx = placed[p2] - placed[p0];
            var by = placed[p2 + 1] - placed[p0 + 1];
            var bz = placed[p2 + 2] - placed[p0 + 2];
            var nx = ay * bz - az * by;
            var ny = az * bx - ax * bz;
            var nz = ax * by - ay * bx;
            for (var corner : new int[] {p0, p1, p2}) {
                pointNormals[corner] += nx;
                pointNormals[corner + 1] += ny;
                pointNormals[corner + 2] += nz;
            }
        }
        var positions = new float[wedgePoints.length * 3];
        var normals = new float[wedgePoints.length * 3];
        for (var i = 0; i < wedgePoints.length; i++) {
            var point = wedgePoints[i] * 3;
            System.arraycopy(placed, point, positions, i * 3, 3);
            var nx = pointNormals[point];
            var ny = pointNormals[point + 1];
            var nz = pointNormals[point + 2];
            var length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            normals[i * 3] = length > 1e-7f ? nx / length : 0f;
            normals[i * 3 + 1] = length > 1e-7f ? ny / length : 0f;
            normals[i * 3 + 2] = length > 1e-7f ? nz / length : 1f;
        }
        var placedBones = new HashMap<String, Vec3>();
        var at = new float[3];
        var into = new float[3];
        for (var bone : places.entrySet()) {
            at[0] = bone.getValue().getX();
            at[1] = bone.getValue().getY();
            at[2] = bone.getValue().getZ();
            place(at, 0, into, 0);
            placedBones.put(bone.getKey(), new Vec3(into[0], into[1], into[2]));
        }
        var placedAxes = new HashMap<String, float[]>();
        for (var bone : meshAxes.entrySet()) {
            var axes = bone.getValue();
            var turned = new float[9];
            for (var axis = 0; axis < 3; axis++) {
                var x = axes[axis * 3];
                var y = axes[axis * 3 + 1];
                var z = axes[axis * 3 + 2];
                for (var row = 0; row < 3; row++) {
                    turned[axis * 3 + row] = toModel[row] * x + toModel[3 + row] * y + toModel[6 + row] * z;
                }
            }
            placedAxes.put(bone.getKey(), turned);
        }
        return new Pose(positions, normals, placedBones, placedAxes);
    }

    private void place(float[] from, int at, float[] to, int into) {
        var x = from[at];
        var y = from[at + 1];
        var z = from[at + 2];
        to[into] = toModel[0] * x + toModel[3] * y + toModel[6] * z + toModel[9];
        to[into + 1] = toModel[1] * x + toModel[4] * y + toModel[7] * z + toModel[10];
        to[into + 2] = toModel[2] * x + toModel[5] * y + toModel[8] * z + toModel[11];
    }

    private synchronized @Nullable Motion motion(int sequence) {
        if (motions[sequence] == null && !unreadable[sequence]) {
            try {
                motions[sequence] = decode.apply(sequence);
            } catch (RuntimeException failure) {
                unreadable[sequence] = true;
                LogManager.getLogger(NpcAnimation.class)
                        .debug("{} cannot read {}: {}", model.mesh(), getName(sequence), failure.toString());
            }
        }
        return motions[sequence];
    }

    private static @Nullable NpcAnimation create(
            NpcModel model,
            USkeletalMesh space,
            Bone[] bones,
            Vec3[] points,
            Influence[] influences,
            Wedge[] wedges,
            Face[] faces,
            UMeshAnimation animation,
            int idle,
            int fight,
            boolean namedWait) {
        if (wedges.length != model.entityMesh().vertices().size()) {
            LogManager.getLogger(NpcAnimation.class)
                    .debug(
                            "{} has {} wedges for {} drawn vertices; it stays still",
                            model.mesh(),
                            wedges.length,
                            model.entityMesh().vertices().size());
            return null;
        }
        var origin = space.convertMeshToLocal(new Vec3(0, 0, 0));
        var x = space.convertMeshToLocal(new Vec3(1, 0, 0));
        var y = space.convertMeshToLocal(new Vec3(0, 1, 0));
        var z = space.convertMeshToLocal(new Vec3(0, 0, 1));
        var toModel = new float[] {
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
            origin.getZ() + model.lowest()
        };
        return new NpcAnimation(
                model,
                bones,
                points,
                influences,
                wedges,
                faces,
                toModel,
                animation.getBones(),
                List.of(animation.getSequences()),
                animation::decodeMotion,
                idle,
                fight,
                namedWait);
    }

    private static float rate(Sequence sequence) {
        return sequence.rate() > 0 ? sequence.rate() : DEFAULT_RATE;
    }
}
