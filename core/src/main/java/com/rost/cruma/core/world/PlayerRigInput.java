package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.SkeletalPose;
import com.rost.cruma.core.unreal.USkeletalMesh;
import com.rost.cruma.core.unreal.USkeletalMesh.Bone;
import com.rost.cruma.core.unreal.USkeletalMesh.Face;
import com.rost.cruma.core.unreal.USkeletalMesh.Influence;
import com.rost.cruma.core.unreal.USkeletalMesh.Wedge;
import com.rost.cruma.core.world.UnrealLoader.PlayerRig;
import com.rost.cruma.core.world.UnrealLoader.PlayerRigPart;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public final class PlayerRigInput {
    private static final double CARRY_REACH = 4;

    private PlayerRigInput() {}

    public record Input(
            Bone[] bones, Vec3[] points, Influence[] influences, Wedge[] wedges, Face[] faces, long[] flags) {}

    public static Input of(PlayerRig rig) {
        return of(rig.frame(), rig.parts());
    }

    public static Input of(USkeletalMesh frame, List<PlayerRigPart> parts) {
        var byName = new HashMap<String, Integer>();
        for (var index = 0; index < frame.getBones().length; index++) {
            byName.putIfAbsent(SkeletalPose.boneKey(frame.getBones()[index].name()), index);
        }
        var bones = new ArrayList<>(List.of(frame.getBones()));
        var points = new ArrayList<Vec3>();
        var influences = new ArrayList<Influence>();
        var wedges = new ArrayList<Wedge>();
        var faces = new ArrayList<Face>();
        var flags = new ArrayList<Long>();
        for (var part : parts) {
            var mesh = part.mesh();
            var pointBase = points.size();
            var wedgeBase = wedges.size();
            var materialBase = flags.size();
            if (part.bone() != null) {
                addRigidPart(frame, mesh, part.bone(), part.accessory(), part.scale(), byName, points, influences);
            } else if (SkeletalPose.isMount(mesh, frame)) {
                var offset = bones.size() - 1;
                var own = mesh.getBones();
                for (var index = 1; index < own.length; index++) {
                    var parent = own[index].parentIndex();
                    bones.add(new Bone(
                            SkeletalPose.MOUNT_PREFIX + own[index].name(),
                            own[index].orientation(),
                            own[index].position(),
                            own[index].childCount(),
                            parent <= 0 ? 0 : offset + parent));
                }
                points.addAll(List.of(mesh.getPoints()));
                for (var influence : mesh.getInfluences()) {
                    var bone = influence.bone() <= 0 ? 0 : offset + influence.bone();
                    influences.add(new Influence(pointBase + influence.point(), bone, influence.weight()));
                }
            } else if (!SkeletalPose.sharesBones(mesh, frame)) {
                var carrier = SkeletalPose.findCarrierBone(mesh, frame, CARRY_REACH);
                var bone = carrier == null ? 0 : byName.getOrDefault(SkeletalPose.boneKey(carrier), 0);
                var own = part.points() != null ? part.points() : mesh.getPoints();
                for (var index = 0; index < own.length; index++) {
                    points.add(own[index]);
                    influences.add(new Influence(pointBase + index, bone, 1f));
                }
            } else {
                var map = SkeletalPose.boneMap(mesh, frame);
                points.addAll(List.of(mesh.getPoints()));
                for (var influence : mesh.getInfluences()) {
                    var bone = influence.bone() >= 0 && influence.bone() < map.length ? map[influence.bone()] : -1;
                    if (bone >= 0) {
                        influences.add(new Influence(pointBase + influence.point(), bone, influence.weight()));
                    }
                }
            }
            for (var wedge : mesh.getWedges()) {
                wedges.add(new Wedge(wedge.vertexIndex() + pointBase, wedge.uv()));
            }
            for (var face : toSectionFaces(mesh)) {
                faces.add(new Face(
                        face.wedge0() + wedgeBase,
                        face.wedge1() + wedgeBase,
                        face.wedge2() + wedgeBase,
                        face.materialIndex() + materialBase));
            }
            for (var materialFlags : part.materialFlags()) {
                flags.add(materialFlags);
            }
        }
        var flagArray = new long[flags.size()];
        for (var index = 0; index < flagArray.length; index++) {
            flagArray[index] = flags.get(index);
        }
        return new Input(
                bones.toArray(Bone[]::new),
                points.toArray(Vec3[]::new),
                influences.toArray(Influence[]::new),
                wedges.toArray(Wedge[]::new),
                faces.toArray(Face[]::new),
                flagArray);
    }

    public static Face[] toSectionFaces(USkeletalMesh mesh) {
        var faces = mesh.getFaces();
        var sections = mesh.getFaceSections();
        var result = new Face[faces.length];
        for (var index = 0; index < faces.length; index++) {
            var face = faces[index];
            result[index] = new Face(face.wedge0(), face.wedge1(), face.wedge2(), sections[index]);
        }
        return result;
    }

    private static void addRigidPart(
            USkeletalMesh frame,
            USkeletalMesh mesh,
            String boneName,
            boolean accessory,
            float scale,
            HashMap<String, Integer> byName,
            List<Vec3> points,
            List<Influence> influences) {
        var base = points.size();
        var bone = byName.getOrDefault(SkeletalPose.boneKey(boneName), 0);
        var rest = SkeletalPose.findPosedBone(frame, null, null, 0, boneName);
        for (var index = 0; index < mesh.getPoints().length; index++) {
            var point = mesh.getPoints()[index];
            var local = (accessory ? mesh.convertAttachedToLocal(point) : mesh.convertMeshToLocal(point)).times(scale);
            points.add(rest == null ? local : SkeletalPose.place(rest, local));
            influences.add(new Influence(base + index, bone, 1f));
        }
    }
}
