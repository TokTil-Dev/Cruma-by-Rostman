package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainNativeCodec.Coords;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.UActor.StaticMeshActor;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.ULevel;
import com.rost.cruma.core.unreal.UMaterial;
import com.rost.cruma.core.unreal.UMaterial.UColorModifier;
import com.rost.cruma.core.unreal.UMaterial.UFinalBlend;
import com.rost.cruma.core.unreal.UMaterial.UModifier;
import com.rost.cruma.core.unreal.UMaterial.UShader;
import com.rost.cruma.core.unreal.UMaterial.UTexture;
import com.rost.cruma.core.unreal.UObject;
import com.rost.cruma.core.unreal.UStaticMesh;
import com.rost.cruma.core.unreal.UTerrain.UTerrainInfoActor;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

final class BspShadowSceneCapture {
    static final String[] MESH_ACTOR_CLASSES = {"StaticMeshActor", "MovableStaticMeshActor", "L2MovableStaticMeshActor"
    };

    private BspShadowSceneCapture() {}

    record Counts(
            int bspTriangles,
            int meshActors,
            int meshTriangles,
            int terrainTriangles,
            @Nullable String terrain) {}

    record Result(BspShadowScene scene, Counts counts, float[] surfaceFlags, List<String> actorPaths) {}

    static Result capture(
            UArchive map,
            ULevel level,
            UModel model,
            Map<String, TerrainHeightField> terrainHeights,
            BooleanSupplier cancelled) {
        var vertices = new FloatList();
        var flags = new IntList();
        var owners = new IntList();
        var nodes = model.getNodes();
        var surfaces = model.getSurfaces();
        var points = model.getPoints();
        var verts = model.getVertices();
        var surfaceFlags = new float[surfaces == null ? 0 : surfaces.length];
        var bspTriangles = 0;
        if (nodes != null && surfaces != null && points != null && verts != null) {
            for (var i = 0; i < surfaces.length; i++) {
                surfaceFlags[i] = Float.intBitsToFloat((int) surfaces[i].getPolygonFlags());
            }
            for (var node : nodes) {
                var count = node.getVertexCount() & 0xff;
                if (count < 3 || node.getSurfaceIndex() < 0 || node.getSurfaceIndex() >= surfaces.length) {
                    continue;
                }
                var surfaceFlagBits = (int) surfaces[node.getSurfaceIndex()].getPolygonFlags();
                var plane = node.getPlane();
                var polygon = new float[count * 3];
                var valid = true;
                for (var k = 0; k < count; k++) {
                    var vertex = node.getVertexPoolIndex() + k;
                    if (vertex < 0 || vertex >= verts.length) {
                        valid = false;
                        break;
                    }
                    var point = verts[vertex].getVertexIndex();
                    if (point < 0 || point >= points.length) {
                        valid = false;
                        break;
                    }
                    polygon[k * 3] = points[point].getX();
                    polygon[k * 3 + 1] = points[point].getY();
                    polygon[k * 3 + 2] = points[point].getZ();
                }
                if (!valid) {
                    continue;
                }
                var nx = 0.0;
                var ny = 0.0;
                var nz = 0.0;
                for (var k = 1; k + 1 < count; k++) {
                    var e1x = polygon[k * 3] - polygon[0];
                    var e1y = polygon[k * 3 + 1] - polygon[1];
                    var e1z = polygon[k * 3 + 2] - polygon[2];
                    var e2x = polygon[k * 3 + 3] - polygon[0];
                    var e2y = polygon[k * 3 + 4] - polygon[1];
                    var e2z = polygon[k * 3 + 5] - polygon[2];
                    nx += e1y * e2z - e1z * e2y;
                    ny += e1z * e2x - e1x * e2z;
                    nz += e1x * e2y - e1y * e2x;
                }
                var reverse = nx * plane.getX() + ny * plane.getY() + nz * plane.getZ() < 0;
                var triangleFlags = BspShadowScene.BSP
                        | ((surfaceFlagBits & 0x100) != 0 ? BspShadowScene.TWO_SIDED : 0)
                        | ((surfaceFlagBits & 0x80) != 0 ? BspShadowScene.FAKE_BACKDROP : 0)
                        | ((surfaceFlagBits & 0x80000) != 0 ? BspShadowScene.WATER | BspShadowScene.TWO_SIDED : 0);
                for (var k = 1; k + 1 < count; k++) {
                    var b = reverse ? k + 1 : k;
                    var c = reverse ? k : k + 1;
                    vertices.add(polygon[0], polygon[1], polygon[2]);
                    vertices.add(polygon[b * 3], polygon[b * 3 + 1], polygon[b * 3 + 2]);
                    vertices.add(polygon[c * 3], polygon[c * 3 + 1], polygon[c * 3 + 2]);
                    flags.add(triangleFlags);
                    owners.add(node.getSurfaceIndex());
                    bspTriangles++;
                }
            }
        }
        var registered = new HashSet<String>();
        for (var reference : level.getActors()) {
            var path = reference.peekExportSourcePath();
            if (path != null) {
                registered.add(path);
            }
        }
        var actors = new ArrayList<StaticMeshActor>();
        for (var type : MESH_ACTOR_CLASSES) {
            map.loadObjects(type, actors);
        }
        var seen = new HashSet<String>();
        var meshTriangleCache = new IdentityHashMap<UStaticMesh, float[]>();
        var meshActors = 0;
        var meshTriangles = 0;
        var actorIndex = 0;
        var actorPaths = new ArrayList<String>();
        for (var actor : actors) {
            checkCancelled(cancelled);
            if (!registered.contains(actor.getSourcePath()) || actor.isDeleteMe() || !seen.add(actor.getSourcePath())) {
                continue;
            }
            var index = actorIndex++;
            actorPaths.add(actor.getSourcePath());
            if (!actor.isShadowCast() || actor.isHidden()) {
                continue;
            }
            UStaticMesh mesh;
            try {
                mesh = actor.getStaticMesh().get();
            } catch (RuntimeException missing) {
                mesh = null;
            }
            if (mesh == null) {
                continue;
            }
            float[] matrix;
            try {
                matrix = ActorLightingCapture.transform(actor).getLocalToWorld();
            } catch (IllegalArgumentException singular) {
                continue;
            }
            var local = meshTriangleCache.computeIfAbsent(mesh, BspShadowSceneCapture::sectionTriangles);
            var twoSided = sectionTwoSided(mesh, actor.getSkins());
            for (var t = 0; t < local.length / 10; t++) {
                var section = (int) local[t * 10 + 9];
                for (var corner = 0; corner < 3; corner++) {
                    var o = t * 10 + corner * 3;
                    var x = local[o];
                    var y = local[o + 1];
                    var z = local[o + 2];
                    vertices.add(
                            (matrix[8] * z + (matrix[0] * x + matrix[4] * y)) + matrix[12],
                            (matrix[9] * z + (matrix[1] * x + matrix[5] * y)) + matrix[13],
                            (matrix[10] * z + (matrix[2] * x + matrix[6] * y)) + matrix[14]);
                }
                flags.add(BspShadowScene.MESH
                        | (section < twoSided.length && twoSided[section] ? BspShadowScene.TWO_SIDED : 0));
                owners.add(index);
                meshTriangles++;
            }
            meshActors++;
        }
        var planes = new float[nodes == null ? 0 : nodes.length * 4];
        var links = new int[nodes == null ? 0 : nodes.length * 4];
        if (nodes != null) {
            for (var i = 0; i < nodes.length; i++) {
                var plane = nodes[i].getPlane();
                planes[i * 4] = plane.getX();
                planes[i * 4 + 1] = plane.getY();
                planes[i * 4 + 2] = plane.getZ();
                planes[i * 4 + 3] = plane.getW();
                links[i * 4] = nodes[i].getFrontIndex();
                links[i * 4 + 1] = nodes[i].getBackIndex();
                links[i * 4 + 2] = nodes[i].getZone()[1];
                links[i * 4 + 3] = nodes[i].getZone()[0];
            }
        }
        var regions = new BspShadowScene(new float[0], new int[0], new int[0], planes, links);
        var terrainTriangles = 0;
        String terrainPath = null;
        var terrain = selectedTerrain(map, registered, regions);
        if (terrain != null) {
            var field = terrainHeights.get(terrain.getSourcePath());
            if (field == null) {
                field = heightField(terrain);
            }
            if (field != null) {
                terrainPath = terrain.getSourcePath();
                terrainTriangles = addTerrain(terrain, field, vertices, flags, owners);
            }
        }
        var scene = new BspShadowScene(vertices.toArray(), flags.toArray(), owners.toArray(), planes, links);
        return new Result(
                scene,
                new Counts(bspTriangles, meshActors, meshTriangles, terrainTriangles, terrainPath),
                surfaceFlags,
                List.copyOf(actorPaths));
    }

    static Result withNeighbors(Result own, List<String> prefixes, List<Result> others, int kinds) {
        var scenes = new ArrayList<BspShadowScene>();
        var surfaceOffsets = new int[others.size()];
        var actorOffsets = new int[others.size()];
        var surfaces = own.surfaceFlags().length;
        var actors = new ArrayList<>(own.actorPaths());
        var bspTriangles = own.counts().bspTriangles();
        var meshActors = own.counts().meshActors();
        var meshTriangles = own.counts().meshTriangles();
        var terrainTriangles = own.counts().terrainTriangles();
        for (var i = 0; i < others.size(); i++) {
            var other = others.get(i);
            scenes.add(other.scene());
            surfaceOffsets[i] = surfaces;
            actorOffsets[i] = actors.size();
            surfaces += other.surfaceFlags().length;
            for (var path : other.actorPaths()) {
                actors.add(prefixes.get(i) + ":" + path);
            }
            bspTriangles += other.counts().bspTriangles();
            meshActors += other.counts().meshActors();
            meshTriangles += other.counts().meshTriangles();
            terrainTriangles += other.counts().terrainTriangles();
        }
        var flags = Arrays.copyOf(own.surfaceFlags(), surfaces);
        for (var i = 0; i < others.size(); i++) {
            var other = others.get(i).surfaceFlags();
            System.arraycopy(other, 0, flags, surfaceOffsets[i], other.length);
        }
        return new Result(
                own.scene().withScenes(scenes, surfaceOffsets, actorOffsets, kinds),
                new Counts(
                        bspTriangles,
                        meshActors,
                        meshTriangles,
                        terrainTriangles,
                        own.counts().terrain()),
                flags,
                List.copyOf(actors));
    }

    static float[] sectionTriangles(UStaticMesh mesh) {
        var vertices = mesh.getVertexStream().getVertices();
        var indices = mesh.getIndexStream().getIndices();
        var surfaces = mesh.getSurfaces();
        var count = 0;
        for (var surface : surfaces) {
            count += surface.getTriangleCount();
        }
        var result = new float[count * 10];
        var size = 0;
        for (var section = 0; section < surfaces.length; section++) {
            var surface = surfaces[section];
            for (var face = 0; face < surface.getTriangleCount(); face++) {
                var first = surface.getFirstIndex() + face * 3;
                if (first + 2 >= indices.length) {
                    break;
                }
                var valid = true;
                for (var corner = 0; corner < 3; corner++) {
                    var index = indices[first + corner];
                    valid &= index >= 0 && index < vertices.length;
                }
                if (!valid) {
                    continue;
                }
                for (var corner = 0; corner < 3; corner++) {
                    var location = vertices[indices[first + corner]].getLocation();
                    result[size++] = location.getX();
                    result[size++] = location.getY();
                    result[size++] = location.getZ();
                }
                result[size++] = section;
            }
        }
        return Arrays.copyOf(result, size);
    }

    static boolean[] sectionTwoSided(UStaticMesh mesh, List<UObject> skins) {
        var materials = mesh.getMaterials();
        var count = mesh.getSurfaces().length;
        var result = new boolean[count];
        for (var section = 0; section < count; section++) {
            UMaterial material = null;
            if (skins != null && section < skins.size() && skins.get(section) instanceof UMaterial skin) {
                material = skin;
            } else if (materials != null && section < materials.length) {
                try {
                    material = materials[section].getMaterial().get();
                } catch (RuntimeException missing) {
                    material = null;
                }
            }
            result[section] = isTwoSided(material, 0);
        }
        return result;
    }

    static boolean isTwoSided(@Nullable UMaterial material, int depth) {
        if (material == null || depth > 8) {
            return false;
        }
        try {
            if (material instanceof UFinalBlend blend) {
                return blend.isTwoSided() || isTwoSided(blend.getMaterial().get(), depth + 1);
            }
            if (material instanceof UColorModifier modifier) {
                return modifier.isRenderTwoSided();
            }
            if (material instanceof UModifier modifier) {
                return isTwoSided(modifier.getMaterial().get(), depth + 1);
            }
            if (material instanceof UShader shader) {
                return shader.isTwoSided() || shader.isTreatAsTwoSided();
            }
            if (material instanceof UTexture texture) {
                return texture.isTwoSided();
            }
        } catch (RuntimeException unreadable) {
            return false;
        }
        return false;
    }

    static @Nullable UTerrainInfoActor selectedTerrain(UArchive map, Set<String> registered, BspShadowScene regions) {
        var terrains = new ArrayList<UTerrainInfoActor>();
        map.loadObjects("TerrainInfo", terrains);
        UTerrainInfoActor best = null;
        var bestZone = Integer.MAX_VALUE;
        for (var info : terrains) {
            if (info.isDeleteMe() || !registered.contains(info.getSourcePath())) {
                continue;
            }
            var location = info.getLocation();
            var zone = regions.findZone(location.getX(), location.getY(), location.getZ());
            if (zone < bestZone) {
                bestZone = zone;
                best = info;
            }
        }
        return best;
    }

    static @Nullable TerrainHeightField heightField(UTerrainInfoActor info) {
        var coords = info.getToWorld();
        if (!info.hasSurface() || coords == null) {
            return null;
        }
        var texture = info.getTerrainMap().get();
        if (texture == null || texture.getMips() == null || texture.getMips().length == 0) {
            return null;
        }
        var width = texture.getUSize();
        var height = texture.getVSize();
        var data = texture.getMips()[0].getData();
        if (width < 4 || height < 4 || width > 256 || height > 256 || data.length < width * height * 2) {
            return null;
        }
        var samples = new short[width * height];
        for (var i = 0; i < samples.length; i++) {
            samples[i] = (short) ((data[i * 2] & 0xff) | (data[i * 2 + 1] & 0xff) << 8);
        }
        var toWorld = new Coords(
                toVector(coords.origin()),
                toVector(coords.xAxis()),
                toVector(coords.yAxis()),
                toVector(coords.zAxis()));
        try {
            return new TerrainHeightField(width, height, samples, toWorld);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static int addTerrain(
            UTerrainInfoActor info, TerrainHeightField field, FloatList vertices, IntList flags, IntList owners) {
        var width = field.getWidth();
        var height = field.getHeight();
        var quads = info.getQuadVisibilityBitmap();
        var turns = info.getEdgeTurnBitmap();
        var visible = new BitSet();
        var turned = new BitSet();
        for (var i = 0; i < width * height; i++) {
            if (quads != null && i < quads.size() && quads.get(i)) {
                visible.set(i);
            }
            if (turns != null && i < turns.size() && turns.get(i)) {
                turned.set(i);
            }
        }
        if (field.getQuadVisibility() != null && field.getQuadTurns() != null) {
            visible = field.getQuadVisibility();
            turned = field.getQuadTurns();
        }
        var world = new float[width * height * 3];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var point = field.getWorldAt(x, y);
                var o = (y * width + x) * 3;
                world[o] = point.x();
                world[o + 1] = point.y();
                world[o + 2] = point.z();
            }
        }
        var count = 0;
        for (var y = 0; y + 1 < height; y++) {
            for (var x = 0; x + 1 < width; x++) {
                var index = y * width + x;
                if (!visible.get(index)) {
                    continue;
                }
                var a = index;
                var b = index + 1;
                var c = index + width;
                var d = index + width + 1;
                int[][] triangles =
                        turned.get(index) ? new int[][] {{a, b, c}, {c, b, d}} : new int[][] {{a, b, d}, {a, d, c}};
                for (var triangle : triangles) {
                    var first = info.isInverted() ? 2 : 0;
                    var last = info.isInverted() ? 0 : 2;
                    for (var corner : new int[] {triangle[first], triangle[1], triangle[last]}) {
                        vertices.add(world[corner * 3], world[corner * 3 + 1], world[corner * 3 + 2]);
                    }
                    flags.add(BspShadowScene.TERRAIN);
                    owners.add(index);
                    count++;
                }
            }
        }
        return count;
    }

    private static Vector toVector(Vec3 value) {
        return new Vector(value.getX(), value.getY(), value.getZ());
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("BSP lighting build cancelled");
        }
    }

    static final class FloatList {
        private float[] values = new float[1024];
        private int size;

        void add(float x, float y, float z) {
            if (size + 3 > values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = x;
            values[size++] = y;
            values[size++] = z;
        }

        float[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }

    static final class IntList {
        private int[] values = new int[256];
        private int size;

        void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = value;
        }

        int[] toArray() {
            return Arrays.copyOf(values, size);
        }

        int size() {
            return size;
        }
    }
}
