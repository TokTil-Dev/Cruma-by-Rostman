package com.rost.cruma.core.export;

import com.rost.cruma.core.export.RigModel.Channel;
import com.rost.cruma.core.export.RigModel.Clip;
import com.rost.cruma.core.export.RigModel.Joint;
import com.rost.cruma.core.export.RigModel.Path;
import com.rost.cruma.core.export.RigModel.Primitive;
import com.rost.cruma.core.unreal.Bsp.BspPolygonFlags;
import com.rost.cruma.core.unreal.SkeletalPose;
import com.rost.cruma.core.unreal.UMeshAnimation.Motion;
import com.rost.cruma.core.unreal.UMeshAnimation.NamedBone;
import com.rost.cruma.core.unreal.UMeshAnimation.Track;
import com.rost.cruma.core.unreal.USkeletalMesh.Bone;
import com.rost.cruma.core.unreal.USkeletalMesh.Face;
import com.rost.cruma.core.unreal.USkeletalMesh.Influence;
import com.rost.cruma.core.unreal.USkeletalMesh.Wedge;
import com.rost.cruma.core.world.Entity.BlendMode;
import com.rost.cruma.core.world.Entity.EntityMesh;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

final class RigBuilder {
    private static final int INFLUENCES = 4;

    private static final float DEFAULT_RATE = 30;

    private RigBuilder() {}

    record Animation(String name, float rate, Motion motion, NamedBone[] bones) {}

    static RigModel build(
            String name,
            Bone[] bones,
            Vec3[] points,
            Influence[] influences,
            Wedge[] wedges,
            Face[] faces,
            long[] materialFlags,
            EntityMesh drawn,
            List<Animation> animations,
            float[] placement,
            ExportScale scale) {
        if (bones.length == 0) {
            throw new IllegalArgumentException(name + " has no bones");
        }
        var metersPerUnit = scale.metersPerUnit();
        var joints = buildJoints(bones, metersPerUnit);
        var inverseBind = inverseBind(joints);
        var count = wedges.length;
        var positions = new float[count * 3];
        for (var wedge = 0; wedge < count; wedge++) {
            var point = points[wedges[wedge].vertexIndex()];
            positions[wedge * 3] = point.getX() * metersPerUnit;
            positions[wedge * 3 + 1] = point.getZ() * metersPerUnit;
            positions[wedge * 3 + 2] = point.getY() * metersPerUnit;
        }
        var uvs = new float[count * 2];
        var drawnVertices = drawn.vertexBuffer();
        for (var wedge = 0; wedge < count && wedge < drawnVertices.size(); wedge++) {
            uvs[wedge * 2] = drawnVertices.getUvX(wedge);
            uvs[wedge * 2 + 1] = drawnVertices.getUvY(wedge);
        }

        var materials = new LocationMaterials(null);
        var primitives = new ArrayList<Primitive>();
        var twoSided = twoSidedSurfaces(faces, materialFlags);
        var indices = drawn.indices();
        var pointNormals = new double[points.length * 3];
        for (var surfaceIndex = 0; surfaceIndex < drawn.surfaces().size(); surfaceIndex++) {
            var surface = drawn.surfaces().get(surfaceIndex);
            var end = Math.min(indices.length, surface.indexOffset() + surface.indexCount());
            var triangles = new int[(end - surface.indexOffset()) / 3 * 3];
            var cursor = 0;
            for (var offset = surface.indexOffset(); offset + 2 < end; offset += 3) {
                var a = indices[offset + 2];
                var b = indices[offset + 1];
                var c = indices[offset];
                triangles[cursor++] = a;
                triangles[cursor++] = b;
                triangles[cursor++] = c;
                accumulateNormals(positions, pointNormals, wedges, a, b, c);
            }
            var doubleSided = surfaceIndex < twoSided.size() && twoSided.get(surfaceIndex);
            primitives.add(new Primitive(triangles, materials.getMaterial(surface, doubleSided)));
        }
        var normals = new float[count * 3];
        for (var wedge = 0; wedge < count; wedge++) {
            var point = wedges[wedge].vertexIndex();
            var x = pointNormals[point * 3];
            var y = pointNormals[point * 3 + 1];
            var z = pointNormals[point * 3 + 2];
            var length = Math.sqrt(x * x + y * y + z * z);
            normals[wedge * 3] = length > 1e-12 ? (float) (x / length) : 0;
            normals[wedge * 3 + 1] = length > 1e-12 ? (float) (y / length) : 1;
            normals[wedge * 3 + 2] = length > 1e-12 ? (float) (z / length) : 0;
        }

        var jointIndices = new int[count * INFLUENCES];
        var weights = new float[count * INFLUENCES];
        assignSkinWeights(points.length, influences, bones.length, wedges, jointIndices, weights);

        var clips = new ArrayList<Clip>(animations.size());
        for (var animation : animations) {
            var clip = clip(animation, bones, metersPerUnit);
            if (!clip.channels().isEmpty()) {
                clips.add(clip);
            }
        }
        return new RigModel(
                name,
                metersPerUnit,
                rootMatrix(placement, metersPerUnit),
                joints,
                inverseBind,
                positions,
                normals,
                uvs,
                jointIndices,
                weights,
                List.copyOf(primitives),
                List.copyOf(clips),
                List.copyOf(materials.getImages()),
                List.copyOf(materials.getMaterials()));
    }

    static RigModel still(String name, EntityMesh drawn, ExportScale scale) {
        var metersPerUnit = scale.metersPerUnit();
        var vertices = drawn.vertexBuffer();
        var count = vertices.size();
        var positions = new float[count * 3];
        var normals = new float[count * 3];
        var uvs = new float[count * 2];
        var jointIndices = new int[count * INFLUENCES];
        var weights = new float[count * INFLUENCES];
        for (var vertex = 0; vertex < count; vertex++) {
            positions[vertex * 3] = vertices.getPositionX(vertex) * metersPerUnit;
            positions[vertex * 3 + 1] = vertices.getPositionZ(vertex) * metersPerUnit;
            positions[vertex * 3 + 2] = vertices.getPositionY(vertex) * metersPerUnit;
            normals[vertex * 3] = vertices.getNormalX(vertex);
            normals[vertex * 3 + 1] = vertices.getNormalZ(vertex);
            normals[vertex * 3 + 2] = vertices.getNormalY(vertex);
            uvs[vertex * 2] = vertices.getUvX(vertex);
            uvs[vertex * 2 + 1] = vertices.getUvY(vertex);
            weights[vertex * INFLUENCES] = 1;
        }
        var materials = new LocationMaterials(null);
        var primitives = new ArrayList<Primitive>();
        var indices = drawn.indices();
        for (var surface : drawn.surfaces()) {
            if (surface.blendMode() == BlendMode.INVISIBLE || surface.indexCount() < 3) {
                continue;
            }
            var end = Math.min(indices.length, surface.indexOffset() + surface.indexCount());
            var triangles = new int[(end - surface.indexOffset()) / 3 * 3];
            var cursor = 0;
            for (var offset = surface.indexOffset(); offset + 2 < end; offset += 3) {
                triangles[cursor++] = indices[offset + 2];
                triangles[cursor++] = indices[offset + 1];
                triangles[cursor++] = indices[offset];
            }
            primitives.add(new Primitive(triangles, materials.getMaterial(surface, false)));
        }
        return new RigModel(
                name,
                metersPerUnit,
                new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
                List.of(new Joint("root", -1, new float[3], new float[] {0, 0, 0, 1})),
                new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
                positions,
                normals,
                uvs,
                jointIndices,
                weights,
                List.copyOf(primitives),
                List.of(),
                List.copyOf(materials.getImages()),
                List.copyOf(materials.getMaterials()));
    }

    static float[] toGltfRotation(double x, double y, double z, double w, boolean conjugated) {
        if (conjugated) {
            x = -x;
            y = -y;
            z = -z;
        }
        var length = Math.sqrt(x * x + y * y + z * z + w * w);
        if (length < 1e-12) {
            return new float[] {0, 0, 0, 1};
        }
        return new float[] {(float) (-x / length), (float) (-z / length), (float) (-y / length), (float) (w / length)};
    }

    static float[] toGltfTranslation(double x, double y, double z, float metersPerUnit) {
        return new float[] {(float) (x * metersPerUnit), (float) (z * metersPerUnit), (float) (y * metersPerUnit)};
    }

    static float[] computeKeyTimes(float[] frames, int count, float trackTime, float rate) {
        var result = new float[count];
        if (count == 1) {
            return result;
        }
        var matches = frames.length == count;
        for (var key = 1; matches && key < count; key++) {
            matches = frames[key] > frames[key - 1];
        }
        for (var key = 0; key < count; key++) {
            var frame = matches ? frames[key] : key * Math.max(trackTime, count) / count;
            result[key] = frame / rate;
        }
        return result;
    }

    private static List<Joint> buildJoints(Bone[] bones, float metersPerUnit) {
        var joints = new ArrayList<Joint>(bones.length);
        for (var index = 0; index < bones.length; index++) {
            var bone = bones[index];
            var parent = bone.parentIndex();
            if (index == 0 || parent < 0 || parent >= index) {
                parent = -1;
            }
            var q = bone.orientation();
            var rotation = toGltfRotation(q[0], q[1], q[2], q[3], index > 0);
            var position = bone.position();
            joints.add(new Joint(
                    bone.name(),
                    parent,
                    toGltfTranslation(position.getX(), position.getY(), position.getZ(), metersPerUnit),
                    rotation));
        }
        return List.copyOf(joints);
    }

    private static float[] inverseBind(List<Joint> joints) {
        var rotations = new double[joints.size()][];
        var translations = new double[joints.size()][];
        var result = new float[joints.size() * 16];
        for (var index = 0; index < joints.size(); index++) {
            var joint = joints.get(index);
            var q = toDouble(joint.rotation());
            var t = toDouble(joint.translation());
            if (joint.parent() >= 0) {
                var parentRotation = rotations[joint.parent()];
                var moved = rotate(parentRotation, t);
                var parentTranslation = translations[joint.parent()];
                t = new double[] {
                    parentTranslation[0] + moved[0], parentTranslation[1] + moved[1], parentTranslation[2] + moved[2]
                };
                q = multiply(parentRotation, q);
            }
            rotations[index] = q;
            translations[index] = t;
            var inverse = new double[] {-q[0], -q[1], -q[2], q[3]};
            var back = rotate(inverse, new double[] {-t[0], -t[1], -t[2]});
            var matrix = toRotationMatrix(inverse);
            for (var column = 0; column < 3; column++) {
                for (var row = 0; row < 3; row++) {
                    result[index * 16 + column * 4 + row] = (float) matrix[row * 3 + column];
                }
            }
            result[index * 16 + 12] = (float) back[0];
            result[index * 16 + 13] = (float) back[1];
            result[index * 16 + 14] = (float) back[2];
            result[index * 16 + 15] = 1;
        }
        return result;
    }

    private static List<Boolean> twoSidedSurfaces(Face[] faces, long[] materialFlags) {
        var count = Math.max(materialFlags.length, 1);
        var used = new boolean[count];
        for (var face : faces) {
            used[Math.clamp(face.materialIndex(), 0, count - 1)] = true;
        }
        var result = new ArrayList<Boolean>();
        for (var material = 0; material < count; material++) {
            if (used[material]) {
                result.add(material < materialFlags.length
                        && (materialFlags[material] & BspPolygonFlags.PF_TwoSided) != 0);
            }
        }
        return result;
    }

    private static void accumulateNormals(float[] positions, double[] normals, Wedge[] wedges, int a, int b, int c) {
        var ax = positions[a * 3];
        var ay = positions[a * 3 + 1];
        var az = positions[a * 3 + 2];
        double ex = positions[b * 3] - ax;
        double ey = positions[b * 3 + 1] - ay;
        double ez = positions[b * 3 + 2] - az;
        double fx = positions[c * 3] - ax;
        double fy = positions[c * 3 + 1] - ay;
        double fz = positions[c * 3 + 2] - az;
        var nx = ey * fz - ez * fy;
        var ny = ez * fx - ex * fz;
        var nz = ex * fy - ey * fx;
        for (var wedge : new int[] {a, b, c}) {
            var point = wedges[wedge].vertexIndex();
            normals[point * 3] += nx;
            normals[point * 3 + 1] += ny;
            normals[point * 3 + 2] += nz;
        }
    }

    private static void assignSkinWeights(
            int pointCount,
            Influence[] influences,
            int boneCount,
            Wedge[] wedges,
            int[] jointIndices,
            float[] weights) {
        var perPoint = new HashMap<Integer, List<Influence>>();
        for (var influence : influences) {
            if (influence.point() >= 0
                    && influence.point() < pointCount
                    && influence.bone() >= 0
                    && influence.bone() < boneCount
                    && influence.weight() > 0) {
                perPoint.computeIfAbsent(influence.point(), ignored -> new ArrayList<>())
                        .add(influence);
            }
        }
        var pointJoints = new int[pointCount * INFLUENCES];
        var pointWeights = new float[pointCount * INFLUENCES];
        for (var point = 0; point < pointCount; point++) {
            var list = perPoint.get(point);
            if (list == null || list.isEmpty()) {
                pointWeights[point * INFLUENCES] = 1;
                continue;
            }
            list.sort((first, second) -> Float.compare(second.weight(), first.weight()));
            var total = 0f;
            for (var slot = 0; slot < INFLUENCES && slot < list.size(); slot++) {
                total += list.get(slot).weight();
            }
            for (var slot = 0; slot < INFLUENCES && slot < list.size(); slot++) {
                pointJoints[point * INFLUENCES + slot] = list.get(slot).bone();
                pointWeights[point * INFLUENCES + slot] = list.get(slot).weight() / total;
            }
        }
        for (var wedge = 0; wedge < wedges.length; wedge++) {
            var point = wedges[wedge].vertexIndex();
            System.arraycopy(pointJoints, point * INFLUENCES, jointIndices, wedge * INFLUENCES, INFLUENCES);
            System.arraycopy(pointWeights, point * INFLUENCES, weights, wedge * INFLUENCES, INFLUENCES);
        }
    }

    private static Clip clip(Animation animation, Bone[] bones, float metersPerUnit) {
        var rate = animation.rate() > 0 ? animation.rate() : DEFAULT_RATE;
        var tracks = animation.motion().tracks();
        var trackOfBone = new LinkedHashMap<String, Integer>();
        for (var index = 0; index < animation.bones().length && index < tracks.length; index++) {
            trackOfBone.putIfAbsent(SkeletalPose.boneKey(animation.bones()[index].name()), index);
        }
        var channels = new ArrayList<Channel>();
        for (var bone = 0; bone < bones.length; bone++) {
            var track = trackOfBone.get(SkeletalPose.boneKey(bones[bone].name()));
            if (track == null) {
                continue;
            }
            var keys = tracks[track];
            if (keys.quaternionKeys() > 0) {
                channels.add(buildRotationChannel(bone, keys, animation.motion().trackTime(), rate));
            }
            if (keys.positionKeys() > 0) {
                channels.add(
                        buildTranslationChannel(bone, keys, animation.motion().trackTime(), rate, metersPerUnit));
            }
        }
        return new Clip(animation.name(), List.copyOf(channels));
    }

    private static Channel buildRotationChannel(int bone, Track track, float trackTime, float rate) {
        var count = track.quaternionKeys();
        var q = track.quaternions();
        var values = new float[count * 4];
        for (var key = 0; key < count; key++) {
            var rotation = toGltfRotation(q[key * 4], q[key * 4 + 1], q[key * 4 + 2], q[key * 4 + 3], bone > 0);
            if (key > 0) {
                var dot = rotation[0] * values[(key - 1) * 4]
                        + rotation[1] * values[(key - 1) * 4 + 1]
                        + rotation[2] * values[(key - 1) * 4 + 2]
                        + rotation[3] * values[(key - 1) * 4 + 3];
                if (dot < 0) {
                    for (var component = 0; component < 4; component++) {
                        rotation[component] = -rotation[component];
                    }
                }
            }
            System.arraycopy(rotation, 0, values, key * 4, 4);
        }
        return new Channel(bone, Path.ROTATION, computeKeyTimes(track.times(), count, trackTime, rate), values);
    }

    private static Channel buildTranslationChannel(
            int bone, Track track, float trackTime, float rate, float metersPerUnit) {
        var count = track.positionKeys();
        var p = track.positions();
        var values = new float[count * 3];
        for (var key = 0; key < count; key++) {
            System.arraycopy(
                    toGltfTranslation(p[key * 3], p[key * 3 + 1], p[key * 3 + 2], metersPerUnit),
                    0,
                    values,
                    key * 3,
                    3);
        }
        return new Channel(bone, Path.TRANSLATION, computeKeyTimes(track.times(), count, trackTime, rate), values);
    }

    private static float[] rootMatrix(float[] m, float metersPerUnit) {
        var result = new float[16];
        int[] swap = {0, 2, 1};
        for (var column = 0; column < 3; column++) {
            for (var row = 0; row < 3; row++) {
                result[column * 4 + row] = m[swap[column] * 4 + swap[row]];
            }
        }
        result[12] = m[12] * metersPerUnit;
        result[13] = m[14] * metersPerUnit;
        result[14] = m[13] * metersPerUnit;
        result[15] = 1;
        return result;
    }

    private static double[] toDouble(float[] values) {
        var result = new double[values.length];
        for (var i = 0; i < values.length; i++) {
            result[i] = values[i];
        }
        return result;
    }

    private static double[] multiply(double[] a, double[] b) {
        return new double[] {
            a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
            a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
            a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
            a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
        };
    }

    private static double[] rotate(double[] q, double[] v) {
        var result =
                multiply(multiply(q, new double[] {v[0], v[1], v[2], 0}), new double[] {-q[0], -q[1], -q[2], q[3]});
        return new double[] {result[0], result[1], result[2]};
    }

    private static double[] toRotationMatrix(double[] q) {
        var x = q[0];
        var y = q[1];
        var z = q[2];
        var w = q[3];
        return new double[] {
            1 - 2 * (y * y + z * z),
            2 * (x * y - z * w),
            2 * (x * z + y * w),
            2 * (x * y + z * w),
            1 - 2 * (x * x + z * z),
            2 * (y * z - x * w),
            2 * (x * z - y * w),
            2 * (y * z + x * w),
            1 - 2 * (x * x + y * y)
        };
    }
}
