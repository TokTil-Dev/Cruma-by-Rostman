package com.rost.cruma.core.unreal.edit;

import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.MeshDecorationData;
import com.rost.cruma.core.unreal.UActor;
import com.rost.cruma.core.unreal.UActor.StaticMeshActor;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UDecryptor;
import com.rost.cruma.core.unreal.ULevel;
import com.rost.cruma.core.unreal.UTerrain.UTerrainInfoActor;
import glm_.vec3.Vec3;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class MapTileCheck {
    private static final float POSITION_TOLERANCE = 0.1f;
    private static final float PLANE_TOLERANCE = 1f;
    private static final float TEXEL_TOLERANCE = 0.5f;
    private static final int MAX_PROBLEMS = 20;

    private MapTileCheck() {}

    public static List<String> compare(Path clientRoot, Path original, Path moved, int tileDx, int tileDy) {
        var problems = new ArrayList<String>();
        var dx = tileDx * MapTileShift.TILE_SIZE;
        var dy = tileDy * MapTileShift.TILE_SIZE;
        var loader = MapTileShift.newLoader(clientRoot);
        try {
            var before = open(original, loader);
            var after = open(moved, loader);
            checkActors(before, after, dx, dy, problems);
            checkTerrains(before, after, tileDx, tileDy, problems);
            checkModel(before, after, dx, dy, problems);
        } catch (RuntimeException failure) {
            problems.add("The moved tile cannot be read: " + failure.getMessage());
        } finally {
            loader.clearArchives();
        }
        return problems.size() > MAX_PROBLEMS ? List.copyOf(problems.subList(0, MAX_PROBLEMS)) : List.copyOf(problems);
    }

    private static UArchive open(Path file, UArchiveLoader loader) {
        var name = file.getFileName().toString().replaceFirst("(?i)\\.unr$", "");
        return new UArchive(name, UDecryptor.decrypt(file), loader);
    }

    private static void checkActors(UArchive before, UArchive after, float dx, float dy, List<String> problems) {
        var old = loadActors(before);
        var moved = loadActors(after);
        if (old.size() != moved.size()) {
            problems.add("Actors: " + old.size() + " before, " + moved.size() + " after");
        }
        for (var entry : old.entrySet()) {
            var a = entry.getValue();
            var b = moved.get(entry.getKey());
            if (b == null) {
                problems.add("Actor " + entry.getKey() + " is missing after the move");
                continue;
            }
            var unplaced = a.getLocation().getX() == 0f
                    && a.getLocation().getY() == 0f
                    && b.getLocation().getX() == 0f
                    && b.getLocation().getY() == 0f;
            if (!unplaced && shiftError(a.getLocation(), b.getLocation(), dx, dy) > POSITION_TOLERANCE) {
                problems.add("Actor " + entry.getKey() + " moved from " + a.getLocation() + " to " + b.getLocation());
            }
            if (a instanceof StaticMeshActor meshA && b instanceof StaticMeshActor meshB) {
                var problem = decorationProblem(meshA.getDecoLayerData(), meshB.getDecoLayerData(), dx, dy);
                if (problem == null) {
                    problem =
                            decorationProblem(meshA.getDecoLayerDataForQuad(), meshB.getDecoLayerDataForQuad(), dx, dy);
                }
                if (problem != null) {
                    problems.add("Decorations of " + entry.getKey() + " " + problem);
                }
            }
        }
    }

    private static @Nullable String decorationProblem(
            List<MeshDecorationData.Instances> before, List<MeshDecorationData.Instances> after, float dx, float dy) {
        if (before.size() != after.size()) {
            return "have " + before.size() + " layers before and " + after.size() + " after";
        }
        for (var layer = 0; layer < before.size(); layer++) {
            var a = before.get(layer);
            var b = after.get(layer);
            if (a.size() != b.size()
                    || !Arrays.equals(a.rotations(), b.rotations())
                    || !Arrays.equals(a.scales(), b.scales())
                    || !Arrays.equals(a.colors(), b.colors())) {
                return "of layer " + layer + " changed beyond the move";
            }
            var locationsA = a.locations();
            var locationsB = b.locations();
            for (var at = 0; at < locationsA.length; at += 3) {
                if (Math.abs(locationsB[at] - locationsA[at] - dx) > POSITION_TOLERANCE
                        || Math.abs(locationsB[at + 1] - locationsA[at + 1] - dy) > POSITION_TOLERANCE
                        || locationsB[at + 2] != locationsA[at + 2]) {
                    return "of layer " + layer + ": instance " + at / 3 + " did not move by the tile";
                }
            }
        }
        return null;
    }

    private static void checkTerrains(UArchive before, UArchive after, int tileDx, int tileDy, List<String> problems) {
        var old = loadTerrains(before);
        var moved = loadTerrains(after);
        var dx = tileDx * MapTileShift.TILE_SIZE;
        var dy = tileDy * MapTileShift.TILE_SIZE;
        var sample = new Vec3(100f, 200f, 300f);
        for (var entry : old.entrySet()) {
            var a = entry.getValue();
            var b = moved.get(entry.getKey());
            if (b == null) {
                problems.add("Terrain " + entry.getKey() + " is missing after the move");
                continue;
            }
            if (b.getMapX() - a.getMapX() != tileDx || b.getMapY() - a.getMapY() != tileDy) {
                problems.add("Terrain "
                        + entry.getKey()
                        + " MapX/MapY "
                        + a.getMapX()
                        + "_"
                        + a.getMapY()
                        + " became "
                        + b.getMapX()
                        + "_"
                        + b.getMapY());
            }
            if (shiftError(a.getLocation(), b.getLocation(), dx, dy) > POSITION_TOLERANCE) {
                problems.add("Terrain " + entry.getKey() + " moved from " + a.getLocation() + " to " + b.getLocation());
            }
            if ((a.getToWorld() == null) != (b.getToWorld() == null)) {
                problems.add("Terrain " + entry.getKey() + " transforms no longer read");
            } else if (a.getToWorld() != null) {
                var world = shiftError(
                        a.getToWorld().transformPoint(sample), b.getToWorld().transformPoint(sample), dx, dy);
                var heightMap = b.getToHeightMap()
                        .transformPoint(new Vec3(sample.getX() + dx, sample.getY() + dy, sample.getZ()))
                        .minus(a.getToHeightMap().transformPoint(sample));
                if (world > POSITION_TOLERANCE
                        || Math.abs(heightMap.getX()) + Math.abs(heightMap.getY()) + Math.abs(heightMap.getZ())
                                > 0.01f) {
                    problems.add("Terrain " + entry.getKey() + " transforms are off by " + world);
                }
            }
        }
    }

    private static void checkModel(UArchive before, UArchive after, float dx, float dy, List<String> problems) {
        var a = loadModel(before);
        var b = loadModel(after);
        if (a == null || b == null) {
            if (a != b) {
                problems.add("The level BSP no longer reads");
            }
            return;
        }
        for (var i = 0; i < a.getPoints().length; i++) {
            if (shiftError(a.getPoints()[i], b.getPoints()[i], dx, dy) > POSITION_TOLERANCE) {
                problems.add("BSP point " + i + " moved from " + a.getPoints()[i] + " to " + b.getPoints()[i]);
                break;
            }
        }
        if (planeError(b) > planeError(a) + PLANE_TOLERANCE) {
            problems.add("BSP planes no longer meet their points: "
                    + planeError(a)
                    + " before, "
                    + planeError(b)
                    + " after");
        }
        var hullProblem = hullBoxProblem(a, b, dx, dy);
        if (hullProblem != null) {
            problems.add(hullProblem);
        }
        if ((a.getLightmapTailError() == null) != (b.getLightmapTailError() == null)) {
            problems.add("BSP lightmaps no longer read: " + b.getLightmapTailError());
        }
        if (a.getBspSections() != null && b.getBspSections() != null) {
            sections:
            for (var s = 0; s < a.getBspSections().length; s++) {
                var x = a.getBspSections()[s].vertices();
                var y = b.getBspSections()[s].vertices();
                for (var i = 0; i < x.length; i++) {
                    if (shiftError(x[i].position(), y[i].position(), dx, dy) > POSITION_TOLERANCE
                            || Float.compare(x[i].u(), y[i].u()) != 0
                            || Float.compare(x[i].v(), y[i].v()) != 0
                            || Float.compare(x[i].u2(), y[i].u2()) != 0
                            || Float.compare(x[i].v2(), y[i].v2()) != 0) {
                        problems.add("BSP section " + s + " vertex " + i + " changed beyond the move");
                        break sections;
                    }
                }
            }
        }
        if (a.getLightmaps() != null && b.getLightmaps() != null && a.getPoints().length > 0) {
            for (var i = 0; i < a.getLightmaps().length; i++) {
                var m0 = a.getLightmaps()[i].uvMatrix();
                var m1 = b.getLightmaps()[i].uvMatrix();
                var p = a.getPoints()[i % a.getPoints().length];
                for (var row = 0; row < 2; row++) {
                    var u0 = m0[row] * p.getX() + m0[4 + row] * p.getY() + m0[8 + row] * p.getZ() + m0[12 + row];
                    var u1 = m1[row] * (p.getX() + dx)
                            + m1[4 + row] * (p.getY() + dy)
                            + m1[8 + row] * p.getZ()
                            + m1[12 + row];
                    if (Math.abs(u0 - u1) > TEXEL_TOLERANCE) {
                        problems.add("Lightmap " + i + " is off by " + Math.abs(u0 - u1) + " texels");
                        return;
                    }
                }
            }
        }
    }

    private static Map<String, UActor> loadActors(UArchive archive) {
        var result = new HashMap<String, UActor>();
        for (var ref : loadLevel(archive).getActors()) {
            try {
                if (ref.get() instanceof UActor actor && actor.getLocation() != null) {
                    result.put(actor.getName(), actor);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return result;
    }

    private static Map<String, UTerrainInfoActor> loadTerrains(UArchive archive) {
        var terrains = new ArrayList<UTerrainInfoActor>();
        archive.loadObjects("TerrainInfo", terrains);
        var result = new HashMap<String, UTerrainInfoActor>();
        for (var terrain : terrains) {
            result.put(terrain.getName(), terrain);
        }
        return result;
    }

    private static UModel loadModel(UArchive archive) {
        return loadLevel(archive).getModel().get();
    }

    private static ULevel loadLevel(UArchive archive) {
        var levels = new ArrayList<ULevel>();
        archive.loadObjects("Level", levels);
        if (levels.size() != 1) {
            throw new IllegalStateException("expected one Level, found " + levels.size());
        }
        return levels.getFirst();
    }

    private static @Nullable String hullBoxProblem(UModel a, UModel b, float dx, float dy) {
        var before = a.getLeafHulls();
        var after = b.getLeafHulls();
        if (before == null || after == null) {
            return before == after ? null : "BSP collision hulls no longer read";
        }
        if (before.length != after.length) {
            return "BSP collision hulls: " + before.length + " values before, " + after.length + " after";
        }
        for (var n = 0; n < a.getNodes().length; n++) {
            var start = a.getNodes()[n].getCollisionBound();
            if (start < 0) {
                continue;
            }
            var end = start;
            while (end < before.length && before[end] != -1) {
                if (after[end] != before[end]) {
                    return "BSP collision hull of node " + n + " lists other nodes";
                }
                end++;
            }
            if (end + 6 >= before.length) {
                continue;
            }
            for (var corner = 0; corner < 2; corner++) {
                var at = end + 1 + corner * 3;
                var x = Float.intBitsToFloat(after[at]) - Float.intBitsToFloat(before[at]) - dx;
                var y = Float.intBitsToFloat(after[at + 1]) - Float.intBitsToFloat(before[at + 1]) - dy;
                if (Math.max(Math.abs(x), Math.abs(y)) > POSITION_TOLERANCE || after[at + 2] != before[at + 2]) {
                    return "BSP collision box of node " + n + " did not move by the offset";
                }
            }
        }
        return null;
    }

    private static float planeError(UModel model) {
        var worst = 0f;
        for (var node : model.getNodes()) {
            var plane = node.getPlane();
            for (var i = 0; i < node.getVertexCount(); i++) {
                var p = model.getPoints()[model.getVertices()[node.getVertexPoolIndex() + i].getVertexIndex()];
                worst = Math.max(
                        worst,
                        Math.abs(plane.getX() * p.getX()
                                + plane.getY() * p.getY()
                                + plane.getZ() * p.getZ()
                                - plane.getW()));
            }
        }
        return worst;
    }

    private static float shiftError(Vec3 a, Vec3 b, float dx, float dy) {
        return Math.max(
                Math.abs(b.getX() - a.getX() - dx),
                Math.max(Math.abs(b.getY() - a.getY() - dy), Math.abs(b.getZ() - a.getZ())));
    }
}
