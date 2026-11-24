package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess;
import java.util.function.BooleanSupplier;

public record GeoDataMesh(float[] vertices, int layerCount, boolean truncated, int quadCount) {
    public static final int FLOATS_PER_VERTEX = 8;
    public static final GeoDataMesh EMPTY = new GeoDataMesh(new float[0], 0, false, 0);
    public static final int DEFAULT_VERTEX_LIMIT = 3_000_000;
    public static final int MAX_VERTICES = 4_000_000;

    public static final float TOP = 0;

    public static final float RISER = 1;
    public static final float WALL = 2;

    public static final int OPEN_COLOR = 0xff8cde4e;

    public static final int OBSTACLE_COLOR = 0xff5c48e8;

    public static final int WALL_COLOR = 0xff4856ff;

    public static GeoDataMesh build(
            GeoDataAccess data,
            int centerX,
            int centerY,
            int radius,
            int maximumVertices,
            GeoDataDisplay display,
            BooleanSupplier cancelled) {
        return GeoDataDisplayMesh.build(data, centerX, centerY, radius, maximumVertices, display, cancelled);
    }

    record Tile(int x, int y) {
        static Tile at(int worldX, int worldY) {
            return new Tile(Math.floorDiv(worldX, 32768) + 20, Math.floorDiv(worldY, 32768) + 18);
        }

        String fileName() {
            return x + "_" + y + "_conv.dat";
        }
    }

    public int vertexCount() {
        return vertices.length / FLOATS_PER_VERTEX;
    }

    public int gpuVertexCount() {
        return vertexCount();
    }
}
