package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.UMeshAnimation.Motion;
import com.rost.cruma.core.unreal.UMeshAnimation.NamedBone;
import com.rost.cruma.core.unreal.UMeshAnimation.Track;
import com.rost.cruma.core.unreal.USkeletalMesh.Bone;
import com.rost.cruma.core.unreal.USkeletalMesh.Influence;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class SkeletalPose {
    public static final String MOUNT_PREFIX = "Mount ";

    private static final double SAME_PLACE = .05;
    private static final double SAME_TURN = 1e-4;

    private SkeletalPose() {}

    public static Vec3[] pose(
            USkeletalMesh mesh, Motion motion, UMeshAnimation animation, float time, boolean conjugateChildren) {
        return pose(mesh, motion, animation, time, conjugateChildren, false);
    }

    public static Vec3[] pose(
            USkeletalMesh mesh,
            Motion motion,
            UMeshAnimation animation,
            float time,
            boolean conjugateChildren,
            boolean conjugateRoot) {
        return pose(
                mesh.getBones(),
                mesh.getPoints(),
                mesh.getInfluences(),
                motion,
                animation == null ? null : animation.getBones(),
                time,
                conjugateChildren,
                conjugateRoot);
    }

    public static Vec3[] pose(
            USkeletalMesh mesh, USkeletalMesh skeleton, Motion motion, UMeshAnimation animation, float time) {
        var byName = new HashMap<String, Integer>();
        for (var i = 0; i < skeleton.getBones().length; i++) {
            byName.putIfAbsent(boneKey(skeleton.getBones()[i].name()), i);
        }
        var influences = new ArrayList<Influence>(mesh.getInfluences().length);
        for (var influence : mesh.getInfluences()) {
            var bone = skeletonBone(mesh.getBones(), influence.bone(), byName);
            if (bone != null) {
                influences.add(new Influence(influence.point(), bone, influence.weight()));
            }
        }
        return pose(
                skeleton.getBones(),
                mesh.getPoints(),
                influences.toArray(Influence[]::new),
                motion,
                animation == null ? null : animation.getBones(),
                time,
                true,
                false);
    }

    public static String boneKey(String name) {
        return name.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    public static int[] boneMap(USkeletalMesh mesh, USkeletalMesh skeleton) {
        var byName = new HashMap<String, Integer>();
        for (var i = 0; i < skeleton.getBones().length; i++) {
            byName.putIfAbsent(boneKey(skeleton.getBones()[i].name()), i);
        }
        var result = new int[mesh.getBones().length];
        for (var i = 0; i < result.length; i++) {
            var found = skeletonBone(mesh.getBones(), i, byName);
            result[i] = found == null ? -1 : found;
        }
        return result;
    }

    static Vec3[] pose(
            Bone[] bones,
            Vec3[] points,
            Influence[] influences,
            Motion motion,
            NamedBone[] animationBones,
            float time,
            boolean conjugateChildren,
            boolean conjugateRoot) {
        var count = bones.length;
        var refWorld = referenceWorld(bones, conjugateChildren, conjugateRoot);
        var poseWorld = posedWorld(bones, motion, animationBones, time, conjugateChildren, conjugateRoot);
        var skinRotation = new double[count][];
        var skinTranslation = new double[count][];
        for (var i = 0; i < count; i++) {
            var inverse = conjugate(refWorld.rotation[i]);
            skinRotation[i] = multiply(poseWorld.rotation[i], inverse);
            var back = rotate(inverse, new double[] {
                -refWorld.translation[i][0], -refWorld.translation[i][1], -refWorld.translation[i][2]
            });
            var moved = rotate(poseWorld.rotation[i], back);
            skinTranslation[i] = new double[] {
                moved[0] + poseWorld.translation[i][0],
                moved[1] + poseWorld.translation[i][1],
                moved[2] + poseWorld.translation[i][2]
            };
        }
        var sums = new double[points.length][3];
        var weights = new double[points.length];
        for (var influence : influences) {
            var point = points[influence.point()];
            var bone = influence.bone();
            var v = rotate(skinRotation[bone], new double[] {point.getX(), point.getY(), point.getZ()});
            var w = influence.weight();
            sums[influence.point()][0] += (v[0] + skinTranslation[bone][0]) * w;
            sums[influence.point()][1] += (v[1] + skinTranslation[bone][1]) * w;
            sums[influence.point()][2] += (v[2] + skinTranslation[bone][2]) * w;
            weights[influence.point()] += w;
        }
        var posed = new Vec3[points.length];
        for (var i = 0; i < points.length; i++) {
            posed[i] = weights[i] > 1e-6
                    ? new Vec3((float) (sums[i][0] / weights[i]), (float) (sums[i][1] / weights[i]), (float)
                            (sums[i][2] / weights[i]))
                    : points[i];
        }
        return posed;
    }

    public static double @Nullable [] findPosedBone(
            USkeletalMesh skeleton, Motion motion, UMeshAnimation animation, float time, String name) {
        var bones = skeleton.getBones();
        for (var i = 0; i < bones.length; i++) {
            if (boneKey(bones[i].name()).equals(boneKey(name))) {
                var world =
                        posedWorld(bones, motion, animation == null ? null : animation.getBones(), time, true, false);
                var q = world.rotation[i];
                var t = world.translation[i];
                return new double[] {q[0], q[1], q[2], q[3], t[0], t[1], t[2]};
            }
        }
        return null;
    }

    public static double[] posedBone(
            Bone[] bones, @Nullable Motion motion, NamedBone @Nullable [] animationBones, float time, int bone) {
        var world = posedWorld(bones, motion, animationBones, time, true, false);
        var q = world.rotation[bone];
        var t = world.translation[bone];
        return new double[] {q[0], q[1], q[2], q[3], t[0], t[1], t[2]};
    }

    public static Map<String, Vec3> posedBones(
            USkeletalMesh skeleton, @Nullable Motion motion, @Nullable UMeshAnimation animation, float time) {
        return posedBones(skeleton.getBones(), motion, animation == null ? null : animation.getBones(), time);
    }

    public static Map<String, Vec3> posedBones(
            Bone[] bones, @Nullable Motion motion, NamedBone @Nullable [] animationBones, float time) {
        var world = posedWorld(bones, motion, animationBones, time, true, false);
        var places = new HashMap<String, Vec3>();
        for (var i = 0; i < bones.length; i++) {
            var t = world.translation[i];
            places.putIfAbsent(boneKey(bones[i].name()), new Vec3((float) t[0], (float) t[1], (float) t[2]));
        }
        return places;
    }

    public static Map<String, Vec3> skin(
            Bone[] bones,
            Vec3[] points,
            Influence[] influences,
            @Nullable Motion motion,
            NamedBone @Nullable [] animationBones,
            float time,
            float[] out) {
        return skin(bones, points, influences, motion, animationBones, time, out, null);
    }

    public static Map<String, Vec3> skin(
            Bone[] bones,
            Vec3[] points,
            Influence[] influences,
            @Nullable Motion motion,
            NamedBone @Nullable [] animationBones,
            float time,
            float[] out,
            @Nullable Map<String, float[]> axes) {
        var count = bones.length;
        var reference = referenceWorld(bones, true, false);
        var posed = posedWorld(bones, motion, animationBones, time, true, false);
        var skin = new double[count * 12];
        for (var i = 0; i < count; i++) {
            var inverse = conjugate(reference.rotation[i]);
            var q = multiply(posed.rotation[i], inverse);
            var back = rotate(inverse, new double[] {
                -reference.translation[i][0], -reference.translation[i][1], -reference.translation[i][2]
            });
            var moved = rotate(posed.rotation[i], back);
            var x = q[0];
            var y = q[1];
            var z = q[2];
            var w = q[3];
            var at = i * 12;
            skin[at] = w * w + x * x - y * y - z * z;
            skin[at + 1] = 2 * (x * y - w * z);
            skin[at + 2] = 2 * (x * z + w * y);
            skin[at + 3] = 2 * (x * y + w * z);
            skin[at + 4] = w * w - x * x + y * y - z * z;
            skin[at + 5] = 2 * (y * z - w * x);
            skin[at + 6] = 2 * (x * z - w * y);
            skin[at + 7] = 2 * (y * z + w * x);
            skin[at + 8] = w * w - x * x - y * y + z * z;
            skin[at + 9] = moved[0] + posed.translation[i][0];
            skin[at + 10] = moved[1] + posed.translation[i][1];
            skin[at + 11] = moved[2] + posed.translation[i][2];
        }
        var sums = new double[points.length * 3];
        var weights = new double[points.length];
        for (var influence : influences) {
            var point = points[influence.point()];
            var at = influence.bone() * 12;
            var w = influence.weight();
            var px = point.getX();
            var py = point.getY();
            var pz = point.getZ();
            var into = influence.point() * 3;
            sums[into] += (skin[at] * px + skin[at + 1] * py + skin[at + 2] * pz + skin[at + 9]) * w;
            sums[into + 1] += (skin[at + 3] * px + skin[at + 4] * py + skin[at + 5] * pz + skin[at + 10]) * w;
            sums[into + 2] += (skin[at + 6] * px + skin[at + 7] * py + skin[at + 8] * pz + skin[at + 11]) * w;
            weights[influence.point()] += w;
        }
        for (var i = 0; i < points.length; i++) {
            if (weights[i] > 1e-6) {
                out[i * 3] = (float) (sums[i * 3] / weights[i]);
                out[i * 3 + 1] = (float) (sums[i * 3 + 1] / weights[i]);
                out[i * 3 + 2] = (float) (sums[i * 3 + 2] / weights[i]);
            } else {
                out[i * 3] = points[i].getX();
                out[i * 3 + 1] = points[i].getY();
                out[i * 3 + 2] = points[i].getZ();
            }
        }
        var places = new HashMap<String, Vec3>();
        for (var i = 0; i < count; i++) {
            var t = posed.translation[i];
            var key = boneKey(bones[i].name());
            places.putIfAbsent(key, new Vec3((float) t[0], (float) t[1], (float) t[2]));
            if (axes != null && !axes.containsKey(key)) {
                var turn = new double[] {
                    posed.rotation[i][0], posed.rotation[i][1], posed.rotation[i][2], posed.rotation[i][3], 0, 0, 0
                };
                var x = place(turn, new Vec3(1, 0, 0));
                var y = place(turn, new Vec3(0, 1, 0));
                var z = place(turn, new Vec3(0, 0, 1));
                axes.put(key, new float[] {
                    x.getX(), x.getY(), x.getZ(), y.getX(), y.getY(), y.getZ(), z.getX(), z.getY(), z.getZ()
                });
            }
        }
        return places;
    }

    public static boolean isMount(USkeletalMesh mesh, USkeletalMesh skeleton) {
        var own = mesh.getBones();
        var figure = skeleton.getBones();
        if (own.length < 2 || figure.length == 0 || !boneKey(own[0].name()).equals(boneKey(figure[0].name()))) {
            return false;
        }
        var root = findPosedBone(mesh, null, null, 0, own[0].name());
        var figureRoot = findPosedBone(skeleton, null, null, 0, figure[0].name());
        if (root == null || figureRoot == null || !isSamePlace(root, figureRoot)) {
            return false;
        }
        var names = new HashSet<String>();
        for (var bone : figure) {
            names.add(boneKey(bone.name()));
        }
        var shared = 0;
        for (var bone : own) {
            shared += names.contains(boneKey(bone.name())) ? 1 : 0;
        }
        return shared * 2 < own.length;
    }

    public static boolean sharesBones(USkeletalMesh mesh, USkeletalMesh skeleton) {
        var byName = new HashMap<String, Integer>();
        for (var i = 0; i < skeleton.getBones().length; i++) {
            byName.putIfAbsent(boneKey(skeleton.getBones()[i].name()), i);
        }
        for (var influence : mesh.getInfluences()) {
            if (skeletonBone(mesh.getBones(), influence.bone(), byName) != null) {
                return true;
            }
        }
        return false;
    }

    public static @Nullable String findCarrierBone(USkeletalMesh mesh, USkeletalMesh skeleton, double reach) {
        if (mesh.getBones().length == 0) {
            return null;
        }
        var root = mesh.getBones()[0].position();
        var world = referenceWorld(skeleton.getBones(), true, false);
        String nearest = null;
        var best = reach;
        for (var i = 0; i < skeleton.getBones().length; i++) {
            var t = world.translation[i];
            var dx = t[0] - root.getX();
            var dy = t[1] - root.getY();
            var dz = t[2] - root.getZ();
            var distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance <= best) {
                best = distance;
                nearest = skeleton.getBones()[i].name();
            }
        }
        return nearest;
    }

    public static Vec3[] poseCarriedPart(
            USkeletalMesh mesh,
            @Nullable Motion ownMotion,
            @Nullable UMeshAnimation ownAnimation,
            USkeletalMesh skeleton,
            Motion motion,
            UMeshAnimation animation,
            String carrier) {
        var points = mesh.getPoints();
        if (ownMotion != null && ownAnimation != null) {
            var posed = pose(mesh, ownMotion, ownAnimation, 0, true);
            var rootName = mesh.getBones()[0].name();
            var rootPosed = findPosedBone(mesh, ownMotion, ownAnimation, 0, rootName);
            var rootReference = findPosedBone(mesh, null, null, 0, rootName);
            points = new Vec3[posed.length];
            for (var i = 0; i < posed.length; i++) {
                points[i] = place(rootReference, unplace(rootPosed, posed[i]));
            }
        }
        var reference = findPosedBone(skeleton, null, null, 0, carrier);
        var posedCarrier = findPosedBone(skeleton, motion, animation, 0, carrier);
        var carried = new Vec3[points.length];
        for (var i = 0; i < points.length; i++) {
            carried[i] = place(posedCarrier, unplace(reference, points[i]));
        }
        return carried;
    }

    public static boolean followsCarrier(
            USkeletalMesh mesh,
            Motion ownMotion,
            UMeshAnimation ownAnimation,
            USkeletalMesh skeleton,
            Motion motion,
            UMeshAnimation animation,
            String carrier) {
        if (mesh.getBones().length == 0) {
            return false;
        }
        var root = findPosedBone(mesh, ownMotion, ownAnimation, 0, mesh.getBones()[0].name());
        var bone = findPosedBone(skeleton, motion, animation, 0, carrier);
        var rootReference = findPosedBone(mesh, null, null, 0, mesh.getBones()[0].name());
        var boneReference = findPosedBone(skeleton, null, null, 0, carrier);
        return root != null
                && bone != null
                && rootReference != null
                && boneReference != null
                && isSamePlace(root, bone)
                && !isSamePlace(rootReference, boneReference);
    }

    public static Vec3[] rebaseOnCarrier(USkeletalMesh mesh, USkeletalMesh skeleton, String carrier) {
        var root = findPosedBone(mesh, null, null, 0, mesh.getBones()[0].name());
        var bone = findPosedBone(skeleton, null, null, 0, carrier);
        var points = mesh.getPoints();
        if (root == null || bone == null) {
            return points;
        }
        var moved = new Vec3[points.length];
        for (var i = 0; i < points.length; i++) {
            moved[i] = place(bone, unplace(root, points[i]));
        }
        return moved;
    }

    public static Vec3 unplace(double[] bone, Vec3 point) {
        var local = rotate(
                conjugate(new double[] {bone[0], bone[1], bone[2], bone[3]}),
                new double[] {point.getX() - bone[4], point.getY() - bone[5], point.getZ() - bone[6]});
        return new Vec3((float) local[0], (float) local[1], (float) local[2]);
    }

    public static Vec3 place(double[] bone, Vec3 point) {
        var moved = rotate(
                new double[] {bone[0], bone[1], bone[2], bone[3]},
                new double[] {point.getX(), point.getY(), point.getZ()});
        return new Vec3((float) (moved[0] + bone[4]), (float) (moved[1] + bone[5]), (float) (moved[2] + bone[6]));
    }

    static double keyAt(float[] times, int keys, float trackTime, float time) {
        if (keys <= 1) {
            return 0;
        }
        var matches = times.length == keys;
        for (var key = 1; matches && key < keys; key++) {
            matches = times[key] > times[key - 1];
        }
        var spread = Math.max(trackTime, keys) / keys;
        var first = matches ? times[0] : 0f;
        if (time <= first) {
            return 0;
        }
        for (var key = 0; key + 1 < keys; key++) {
            var start = matches ? times[key] : key * spread;
            var end = matches ? times[key + 1] : (key + 1) * spread;
            if (time < end) {
                return key + (time - start) / (end - start);
            }
        }
        return keys - 1;
    }

    static double[] conjugate(double[] q) {
        return new double[] {-q[0], -q[1], -q[2], q[3]};
    }

    static double[] normalize(double[] q) {
        var length = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        return length < 1e-9
                ? new double[] {0, 0, 0, 1}
                : new double[] {q[0] / length, q[1] / length, q[2] / length, q[3] / length};
    }

    static double[] multiply(double[] a, double[] b) {
        return new double[] {
            a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
            a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
            a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
            a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
        };
    }

    static double[] rotate(double[] q, double[] v) {
        var qv = new double[] {v[0], v[1], v[2], 0};
        var result = multiply(multiply(q, qv), conjugate(q));
        return new double[] {result[0], result[1], result[2]};
    }

    private static @Nullable Integer skeletonBone(Bone[] bones, int index, Map<String, Integer> byName) {
        for (var step = 0; step < bones.length && index >= 0 && index < bones.length; step++) {
            var found = byName.get(boneKey(bones[index].name()));
            if (found != null) {
                return found;
            }
            var parent = bones[index].parentIndex();
            if (parent == index) {
                return null;
            }
            index = parent;
        }
        return null;
    }

    private static double[][][] computeReferencePose(Bone[] bones, boolean conjugateChildren, boolean conjugateRoot) {
        var count = bones.length;
        var refRotation = new double[count][];
        var refTranslation = new double[count][];
        for (var i = 0; i < count; i++) {
            var q = bones[i].orientation();
            refRotation[i] = new double[] {q[0], q[1], q[2], q[3]};
            if (i > 0 ? conjugateChildren : conjugateRoot) {
                refRotation[i] = conjugate(refRotation[i]);
            }
            var p = bones[i].position();
            refTranslation[i] = new double[] {p.getX(), p.getY(), p.getZ()};
        }
        return new double[][][] {refRotation, refTranslation};
    }

    private static World referenceWorld(Bone[] bones, boolean conjugateChildren, boolean conjugateRoot) {
        var reference = computeReferencePose(bones, conjugateChildren, conjugateRoot);
        return computeWorld(bones, reference[0], reference[1]);
    }

    private static World posedWorld(
            Bone[] bones,
            Motion motion,
            NamedBone[] animationBones,
            float time,
            boolean conjugateChildren,
            boolean conjugateRoot) {
        var count = bones.length;
        var reference = computeReferencePose(bones, conjugateChildren, conjugateRoot);
        var poseRotation = reference[0].clone();
        var poseTranslation = reference[1].clone();
        if (motion != null && animationBones != null) {
            var trackOfBone = new HashMap<String, Integer>();
            for (var i = 0; i < animationBones.length && i < motion.tracks().length; i++) {
                trackOfBone.putIfAbsent(boneKey(animationBones[i].name()), i);
            }
            for (var i = 0; i < count; i++) {
                var track = trackOfBone.get(boneKey(bones[i].name()));
                if (track == null) {
                    continue;
                }
                var keys = motion.tracks()[track];
                if (keys.quaternionKeys() > 0) {
                    var q = sampleRotation(keys, motion.trackTime(), time);
                    poseRotation[i] = (i > 0 ? conjugateChildren : conjugateRoot) ? conjugate(q) : q;
                }
                if (keys.positionKeys() > 0) {
                    poseTranslation[i] = samplePosition(keys, motion.trackTime(), time);
                }
            }
        }
        return computeWorld(bones, poseRotation, poseTranslation);
    }

    private record World(double[][] rotation, double[][] translation) {}

    private static World computeWorld(Bone[] bones, double[][] rotation, double[][] translation) {
        var count = bones.length;
        var worldRotation = new double[count][];
        var worldTranslation = new double[count][];
        for (var i = 0; i < count; i++) {
            var parent = bones[i].parentIndex();
            if (i == 0 || parent < 0 || parent >= i) {
                worldRotation[i] = rotation[i];
                worldTranslation[i] = translation[i];
                continue;
            }
            worldRotation[i] = multiply(worldRotation[parent], rotation[i]);
            var offset = rotate(worldRotation[parent], translation[i]);
            worldTranslation[i] = new double[] {
                worldTranslation[parent][0] + offset[0],
                worldTranslation[parent][1] + offset[1],
                worldTranslation[parent][2] + offset[2]
            };
        }
        return new World(worldRotation, worldTranslation);
    }

    private static double[] sampleRotation(Track track, float trackTime, float time) {
        var keys = track.quaternionKeys();
        var q = track.quaternions();
        var at = keyAt(track.times(), keys, trackTime, time);
        var key = (int) at;
        var share = at - key;
        var rotation = new double[] {q[key * 4], q[key * 4 + 1], q[key * 4 + 2], q[key * 4 + 3]};
        if (share > 0 && key + 1 < keys) {
            var next = key + 1;
            var dot = rotation[0] * q[next * 4]
                    + rotation[1] * q[next * 4 + 1]
                    + rotation[2] * q[next * 4 + 2]
                    + rotation[3] * q[next * 4 + 3];
            var sign = dot < 0 ? -1 : 1;
            for (var component = 0; component < 4; component++) {
                rotation[component] = rotation[component] * (1 - share) + q[next * 4 + component] * sign * share;
            }
        }
        return normalize(rotation);
    }

    private static double[] samplePosition(Track track, float trackTime, float time) {
        var keys = track.positionKeys();
        var p = track.positions();
        var at = keyAt(track.times(), keys, trackTime, time);
        var key = (int) at;
        var share = at - key;
        var position = new double[] {p[key * 3], p[key * 3 + 1], p[key * 3 + 2]};
        if (share > 0 && key + 1 < keys) {
            for (var axis = 0; axis < 3; axis++) {
                position[axis] += (p[(key + 1) * 3 + axis] - position[axis]) * share;
            }
        }
        return position;
    }

    private static boolean isSamePlace(double[] a, double[] b) {
        var dot = Math.abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]);
        var dx = a[4] - b[4];
        var dy = a[5] - b[5];
        var dz = a[6] - b[6];
        return dot > 1 - SAME_TURN && Math.sqrt(dx * dx + dy * dy + dz * dz) < SAME_PLACE;
    }
}
