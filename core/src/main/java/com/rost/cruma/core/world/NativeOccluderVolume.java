package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UConvexVolume;
import com.rost.cruma.core.unreal.UConvexVolume.Edge;
import com.rost.cruma.core.unreal.UConvexVolume.Face;
import com.rost.cruma.core.world.BspVisibilityVolumes.Volume;
import glm_.mat4x4.Mat4;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.util.ArrayList;
import java.util.List;

public final class NativeOccluderVolume {
    private NativeOccluderVolume() {}

    public record Geometry(List<Face> faces, List<Edge> edges) {
        public Geometry {
            var copied = new ArrayList<Face>(faces.size());
            for (var face : faces) {
                var vertices = new ArrayList<Vec3>(face.vertices().size());
                for (var vertex : face.vertices()) {
                    vertices.add(new Vec3(vertex));
                }
                copied.add(new Face(new Vec4(face.plane()), List.copyOf(vertices)));
            }
            faces = List.copyOf(copied);
            edges = List.copyOf(edges);
        }

        public static Geometry capture(UConvexVolume volume) {
            return new Geometry(volume.getFaces(), volume.getEdges());
        }
    }

    public static Volume build(Geometry geometry, Mat4 actorToWorld, Vec3 origin) {
        var planes = new ArrayList<Vec4>(32);
        var distances = new float[geometry.faces().size()];
        for (var i = 0; i < distances.length; i++) {
            if (planes.size() == 32) {
                return new Volume(planes);
            }
            var p = geometry.faces().get(i).plane();
            var transformed =
                    BrushTransforms.worldPlanes(actorToWorld, new float[] {p.getX(), p.getY(), p.getZ(), p.getW()});
            var distance = origin.getY() * transformed[1]
                    + origin.getX() * transformed[0]
                    + origin.getZ() * transformed[2]
                    - transformed[3];
            distances[i] = distance;
            if (distance > 0f) {
                planes.add(new Vec4(transformed[0], transformed[1], transformed[2], transformed[3]));
            }
        }
        for (var edge : geometry.edges()) {
            if (planes.size() == 32) {
                break;
            }
            if (!(distances[edge.face0()] * distances[edge.face1()] < 0f)) {
                continue;
            }
            var positiveSecond = distances[edge.face1()] > 0f;
            var negativeFace = positiveSecond ? edge.face0() : edge.face1();
            var negativeVertex = positiveSecond ? edge.vertex0() : edge.vertex1();
            var positiveFace = positiveSecond ? edge.face1() : edge.face0();
            var positiveVertex = positiveSecond ? edge.vertex1() : edge.vertex0();
            var a = worldPoint(
                            actorToWorld,
                            geometry.faces().get(negativeFace).vertices().get(negativeVertex))
                    .minus(origin);
            var b = worldPoint(
                            actorToWorld,
                            geometry.faces().get(positiveFace).vertices().get(positiveVertex))
                    .minus(origin);
            var x = b.getY() * a.getZ() - b.getZ() * a.getY();
            var y = b.getZ() * a.getX() - b.getX() * a.getZ();
            var z = b.getX() * a.getY() - b.getY() * a.getX();
            var square = x * x + y * y + z * z;
            if (square < 1.0e-8) {
                x = y = z = 0f;
            } else {
                var reciprocal = 1f / (float) Math.sqrt(square);
                x *= reciprocal;
                y *= reciprocal;
                z *= reciprocal;
            }
            planes.add(new Vec4(x, y, z, origin.getY() * y + origin.getX() * x + origin.getZ() * z));
        }
        return new Volume(planes);
    }

    private static Vec3 worldPoint(Mat4 matrix, Vec3 point) {
        var result = new Vec3();
        for (var c = 0; c < 3; c++) {
            result.set(
                    c,
                    matrix.get(0, c) * point.getX()
                            + matrix.get(1, c) * point.getY()
                            + matrix.get(2, c) * point.getZ()
                            + matrix.get(3, c));
        }
        return result;
    }
}
