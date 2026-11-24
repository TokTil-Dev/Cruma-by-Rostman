package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.Bsp.BspPolygonFlags;
import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.UActor;
import com.rost.cruma.core.unreal.UActor.BlockingVolumeActor;
import com.rost.cruma.core.unreal.UActor.StaticMeshActor;
import com.rost.cruma.core.unreal.UL2MovableStaticMeshActor;
import com.rost.cruma.core.unreal.UStaticMesh;
import glm_.vec3.Vec3;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public record MapCollision(
        float[] bspTriangles,
        float[] meshTriangles,
        float[] modelTriangles,
        float[] volumeTriangles,
        float[] terrainTriangles,
        float[] terrainGrid,
        float[] playerStarts,
        int simpleCollisionActors,
        int triangleCollisionActors) {
    public static final int TERRAIN_STRIDE = 15;

    public static final int GRID_SIZE = 7;

    static final long NOT_BLOCKING = BspPolygonFlags.PF_NotSolid
            | BspPolygonFlags.PF_Portal
            | BspPolygonFlags.PF_FakeBackdrop
            | BspPolygonFlags.PF_L2_Water;

    static boolean isBlocking(long polygonFlags) {
        return (polygonFlags & NOT_BLOCKING) == 0;
    }

    static MapCollision of(
            @Nullable UModel level,
            List<StaticMeshActor> meshActors,
            List<BlockingVolumeActor> volumes,
            List<UActor> starts,
            float[] terrainTriangles,
            float[] terrainGrid) {
        var bsp = new Triangles();
        if (level != null) {
            addModel(bsp, level, null, true);
        }
        var meshes = new Triangles();
        var models = new Triangles();
        var simple = 0;
        var triangles = 0;
        for (var actor : meshActors) {
            if (actor.isDeleteMe()
                    || !actor.isCollideActors()
                    || !actor.isBlockActors()
                    || actor instanceof UL2MovableStaticMeshActor) {
                continue;
            }
            var mesh = actor.getStaticMesh().get();
            if (mesh == null) {
                continue;
            }
            var matrix = Entity.actorModelMatrix(
                            actor.getLocation(),
                            actor.getPrePivot(),
                            actor.getRotation().vector(),
                            actor.getScale())
                    .to(new float[16]);
            var model =
                    mesh.isUseSimpleBoxCollision() ? mesh.getCollisionModel().get() : null;
            if (model != null && model.getNodes() != null && model.getNodes().length > 0) {
                addModel(models, model, matrix, false);
                simple++;
            } else {
                addMesh(meshes, mesh, matrix);
                triangles++;
            }
        }
        var blocking = new Triangles();
        for (var volume : volumes) {
            if (!volume.isCollideActors() || !volume.isBlockActors()) {
                continue;
            }
            var brush = volume.getBrush().get();
            var polys = brush == null ? null : brush.getPolys().get();
            if (polys == null) {
                continue;
            }
            var matrix = BrushTransforms.actorMatrix(
                            volume.getLocation(), volume.getPrePivot(), volume.getRotation(), volume.getScale())
                    .to(new float[16]);
            var polygon = new float[16 * 3];
            for (var face : polys.getPolygons()) {
                var vertices = face.vertices();
                if (vertices.size() < 3) {
                    continue;
                }
                for (var i = 0; i < vertices.size(); i++) {
                    transform(matrix, vertices.get(i), polygon, i * 3);
                }
                blocking.fan(polygon, vertices.size());
            }
        }
        var startPoints = new float[starts.size() * 3];
        for (var i = 0; i < starts.size(); i++) {
            var location = starts.get(i).getLocation();
            startPoints[i * 3] = location.getX();
            startPoints[i * 3 + 1] = location.getY();
            startPoints[i * 3 + 2] = location.getZ();
        }
        return new MapCollision(
                bsp.toArray(),
                meshes.toArray(),
                models.toArray(),
                blocking.toArray(),
                terrainTriangles,
                terrainGrid,
                startPoints,
                simple,
                triangles);
    }

    public int triangleCount() {
        return (bspTriangles.length + meshTriangles.length + modelTriangles.length + volumeTriangles.length) / 9
                + terrainTriangles.length / TERRAIN_STRIDE;
    }

    private static void addModel(Triangles out, UModel model, float @Nullable [] matrix, boolean level) {
        var points = model.getPoints();
        var pool = model.getVertices();
        var polygon = new float[16 * 3];
        for (var node : model.getNodes()) {
            var count = node.getVertexCount();
            if (count < 3 || count > 16) {
                continue;
            }
            var flags = model.getSurfaces()[node.getSurfaceIndex()].getPolygonFlags();
            if (level && !isBlocking(flags)) {
                continue;
            }
            for (var i = 0; i < count; i++) {
                var point = points[pool[node.getVertexPoolIndex() + i].getVertexIndex()];
                if (matrix == null) {
                    polygon[i * 3] = point.getX();
                    polygon[i * 3 + 1] = point.getY();
                    polygon[i * 3 + 2] = point.getZ();
                } else {
                    transform(matrix, point, polygon, i * 3);
                }
            }
            out.fan(polygon, count);
        }
    }

    private static void addMesh(Triangles out, UStaticMesh mesh, float[] matrix) {
        var vertices = mesh.getVertexStream().getVertices();
        var indices = mesh.getIndexStream().getIndices();
        var surfaces = mesh.getSurfaces();
        var materials = mesh.getMaterials();
        var triangle = new float[9];
        for (var s = 0; s < surfaces.length; s++) {
            if (s >= materials.length || !materials[s].isEnableCollision()) {
                continue;
            }
            var first = surfaces[s].getFirstIndex();
            for (var t = 0; t < surfaces[s].getTriangleMax(); t++) {
                for (var corner = 0; corner < 3; corner++) {
                    transform(matrix, vertices[indices[first + t * 3 + corner]].getLocation(), triangle, corner * 3);
                }
                out.fan(triangle, 3);
            }
        }
    }

    private static void transform(float[] m, Vec3 point, float[] out, int offset) {
        var x = point.getX();
        var y = point.getY();
        var z = point.getZ();
        out[offset] = m[0] * x + m[4] * y + m[8] * z + m[12];
        out[offset + 1] = m[1] * x + m[5] * y + m[9] * z + m[13];
        out[offset + 2] = m[2] * x + m[6] * y + m[10] * z + m[14];
    }

    private static final class Triangles {
        private float[] data = new float[9 * 256];
        private int size;

        void fan(float[] polygon, int count) {
            for (var i = 1; i + 1 < count; i++) {
                if (size + 9 > data.length) {
                    data = Arrays.copyOf(data, data.length * 2);
                }
                System.arraycopy(polygon, 0, data, size, 3);
                System.arraycopy(polygon, i * 3, data, size + 3, 6);
                size += 9;
            }
        }

        float[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }
}
