package com.rost.cruma.core.geodata.generation;

import com.rost.cruma.core.geodata.generation.CollisionWorld.Builder;
import com.rost.cruma.core.geodata.generation.CollisionWorld.Kind;
import com.rost.cruma.core.world.MapCollision;

public final class ClientCollision {
    private ClientCollision() {}

    public static void addMapCollision(Builder builder, MapCollision collision) {
        addTriangles(builder, collision.bspTriangles(), Kind.MODEL);
        addTriangles(builder, collision.meshTriangles(), Kind.MESH);
        addTriangles(builder, collision.modelTriangles(), Kind.MODEL);
        addTriangles(builder, collision.volumeTriangles(), Kind.MODEL);
        addTerrain(builder, collision.terrainTriangles(), collision.terrainGrid());
    }

    public static CollisionWorld buildRegionWorld(Builder builder, int tileX, int tileY, int margin) {
        var minX = (tileX - 20) * BuiltRegion.REGION_SIZE;
        var minY = (tileY - 18) * BuiltRegion.REGION_SIZE;
        return builder.build(
                minX - margin,
                minY - margin,
                minX + BuiltRegion.REGION_SIZE + margin,
                minY + BuiltRegion.REGION_SIZE + margin);
    }

    private static void addTerrain(Builder builder, float[] t, float[] grid) {
        if (t.length == 0 || grid.length < MapCollision.GRID_SIZE) {
            return;
        }
        var id = builder.addTerrainGrid(grid[0], grid[1], grid[2], grid[3], (int) grid[4], (int) grid[5], grid[6]);
        for (var o = 0; o + MapCollision.TERRAIN_STRIDE <= t.length; o += MapCollision.TERRAIN_STRIDE) {
            builder.addTerrain(
                    t[o],
                    t[o + 1],
                    t[o + 2],
                    t[o + 3],
                    t[o + 4],
                    t[o + 5],
                    t[o + 6],
                    t[o + 7],
                    t[o + 8],
                    t[o + 9],
                    t[o + 10],
                    t[o + 11],
                    (int) t[o + 12],
                    id,
                    (int) t[o + 13],
                    (int) t[o + 14]);
        }
    }

    private static void addTriangles(Builder builder, float[] triangles, Kind kind) {
        for (var o = 0; o + 8 < triangles.length; o += 9) {
            builder.add(
                    triangles[o],
                    triangles[o + 1],
                    triangles[o + 2],
                    triangles[o + 3],
                    triangles[o + 4],
                    triangles[o + 5],
                    triangles[o + 6],
                    triangles[o + 7],
                    triangles[o + 8],
                    kind);
        }
    }
}
