package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoQueryEngine;
import com.rost.cruma.core.world.Entity.BlendMode;
import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.Entity.Surface;
import com.rost.cruma.core.world.Entity.TerrainMesh;
import com.rost.cruma.core.world.UMap;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

public final class ClientFloorGrid {
    public static final int TILE = 32768;
    static final int STEP = 32;
    static final int CELLS = TILE / STEP;

    private static final int MAX_PER_CELL = 16;

    private static final int MERGE = 2;

    private static final int BLOCK = 32;
    private static final int[] NO_FLOORS = new int[0];

    private final String owner;
    private final int originX;
    private final int originY;
    private final byte[] counts;
    private final int[] blockStarts;
    private final short[] heights;

    private ClientFloorGrid(String owner, int originX, int originY, byte[] counts, int[] blockStarts, short[] heights) {
        this.owner = owner;
        this.originX = originX;
        this.originY = originY;
        this.counts = counts;
        this.blockStarts = blockStarts;
        this.heights = heights;
    }

    public static ClientFloorGrid build(UMap map, BooleanSupplier cancelled) {
        var builder = new Builder(
                map.name(),
                GeoQueryEngine.serverInt(map.position().getX()),
                GeoQueryEngine.serverInt(map.position().getY()));
        for (var entity : map.entities()) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            if (entity.getMovement() != null || entity.isDoor() || entity.isZoneLookCopy()) {
                continue;
            }
            switch (entity.getMesh()) {
                case EntityMesh mesh -> {
                    if (mesh.billboard() || mesh.swayFrequency() != 0 || mesh.decorationFadeMax() > 0) {
                        continue;
                    }
                    var vertices = mesh.vertexBuffer();
                    for (var instance : entity.getInstanceMatrices()) {
                        var matrix = entity.getModelMatrix().times(instance).to(new float[16]);
                        for (var surface : mesh.surfaces()) {
                            if (!isGround(surface, false)) {
                                continue;
                            }
                            var indices = mesh.indices();
                            var end = surface.indexOffset() + surface.indexCount();
                            for (var offset = surface.indexOffset(); offset + 2 < end; offset += 3) {
                                var nx = 0d;
                                var ny = 0d;
                                var nz = 0d;
                                var coordinates = new float[9];
                                for (var point = 0; point < 3; point++) {
                                    var vertex = indices[offset + point];
                                    transform(
                                            matrix,
                                            vertices.getPositionX(vertex),
                                            vertices.getPositionY(vertex),
                                            vertices.getPositionZ(vertex),
                                            coordinates,
                                            point);
                                    nx += vertices.getNormalX(vertex);
                                    ny += vertices.getNormalY(vertex);
                                    nz += vertices.getNormalZ(vertex);
                                }
                                builder.triangle(coordinates, matrix, nx, ny, nz);
                            }
                        }
                    }
                }
                case TerrainMesh mesh -> {
                    if (!isGround(mesh.surface(), true)) {
                        continue;
                    }
                    var vertices = mesh.vertexBuffer();
                    var matrix = entity.getModelMatrix().to(new float[16]);
                    var indices = mesh.indices();
                    var surface = mesh.surface();
                    var end = surface.indexOffset() + surface.indexCount();
                    for (var offset = surface.indexOffset(); offset + 2 < end; offset += 3) {
                        var coordinates = new float[9];
                        for (var point = 0; point < 3; point++) {
                            var vertex = indices[offset + point];
                            transform(
                                    matrix,
                                    vertices.getPositionX(vertex),
                                    vertices.getPositionY(vertex),
                                    vertices.getPositionZ(vertex),
                                    coordinates,
                                    point);
                        }
                        builder.triangle(coordinates, null, 0, 0, 0);
                    }
                }
            }
        }
        return builder.finish();
    }

    public String getOwner() {
        return owner;
    }

    public int getOriginX() {
        return originX;
    }

    public int getOriginY() {
        return originY;
    }

    public long getRetainedBytes() {
        return counts.length + blockStarts.length * 4L + heights.length * 2L;
    }

    public double findHighestFloor(double x, double y, double low, double high) {
        var cellX = (int) Math.floor((x - originX) / STEP);
        var cellY = (int) Math.floor((y - originY) / STEP);
        if (cellX < 0 || cellY < 0 || cellX >= CELLS || cellY >= CELLS) {
            return Double.NaN;
        }
        var cell = cellY * CELLS + cellX;
        var block = cell / BLOCK;
        var start = blockStarts[block];
        for (var i = block * BLOCK; i < cell; i++) {
            start += counts[i];
        }
        var best = Double.NaN;
        for (var i = start; i < start + counts[cell]; i++) {
            var height = heights[i];
            if (height >= low && height <= high) {
                best = height;
            }
        }
        return best;
    }

    public double findNearestFloor(double x, double y, double height, double reach) {
        var cellX = (int) Math.floor((x - originX) / STEP);
        var cellY = (int) Math.floor((y - originY) / STEP);
        if (cellX < 0 || cellY < 0 || cellX >= CELLS || cellY >= CELLS) {
            return Double.NaN;
        }
        var cell = cellY * CELLS + cellX;
        var block = cell / BLOCK;
        var start = blockStarts[block];
        for (var i = block * BLOCK; i < cell; i++) {
            start += counts[i];
        }
        var best = Double.NaN;
        var nearest = reach;
        for (var i = start; i < start + counts[cell]; i++) {
            var off = Math.abs(heights[i] - height);
            if (off <= nearest) {
                nearest = off;
                best = heights[i];
            }
        }
        return best;
    }

    public int[] getFloors(double x, double y) {
        var cellX = (int) Math.floor((x - originX) / STEP);
        var cellY = (int) Math.floor((y - originY) / STEP);
        if (cellX < 0 || cellY < 0 || cellX >= CELLS || cellY >= CELLS) {
            return NO_FLOORS;
        }
        var cell = cellY * CELLS + cellX;
        if (counts[cell] == 0) {
            return NO_FLOORS;
        }
        var block = cell / BLOCK;
        var start = blockStarts[block];
        for (var i = block * BLOCK; i < cell; i++) {
            start += counts[i];
        }
        var floors = new int[counts[cell]];
        for (var i = 0; i < floors.length; i++) {
            floors[i] = heights[start + i];
        }
        return floors;
    }

    static final class Builder {
        private final String owner;
        private final int originX;
        private final int originY;
        private final int[] head = new int[CELLS * CELLS];
        private int[] next = new int[1 << 20];
        private short[] values = new short[1 << 20];
        private final byte[] counts = new byte[CELLS * CELLS];
        private int size;

        Builder(String owner, int originX, int originY) {
            this.owner = owner;
            this.originX = originX;
            this.originY = originY;
            Arrays.fill(head, -1);
        }

        void triangle(float[] c, float[] matrix, double normalX, double normalY, double normalZ) {
            var e1x = c[3] - c[0];
            var e1y = c[4] - c[1];
            var e1z = c[5] - c[2];
            var e2x = c[6] - c[0];
            var e2y = c[7] - c[1];
            var e2z = c[8] - c[2];
            var nx = (double) e1y * e2z - (double) e1z * e2y;
            var ny = (double) e1z * e2x - (double) e1x * e2z;
            var nz = (double) e1x * e2y - (double) e1y * e2x;
            var length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (!Double.isFinite(length) || length < 1e-8 || Math.abs(nz) < 1e-8) {
                return;
            }
            var orientation = matrix == null
                    ? Math.signum(nz)
                    : GeoClientSurfaceIndex.orientation(matrix, normalX, normalY, normalZ, nx, ny, nz);
            var upward = (orientation == 0 ? Math.abs(nz) : orientation * nz) / length;
            if (upward < Math.sqrt(.5)) {
                return;
            }
            var minX = Math.min(c[0], Math.min(c[3], c[6])) - originX;
            var maxX = Math.max(c[0], Math.max(c[3], c[6])) - originX;
            var minY = Math.min(c[1], Math.min(c[4], c[7])) - originY;
            var maxY = Math.max(c[1], Math.max(c[4], c[7])) - originY;
            var fromX = Math.max(0, (int) Math.ceil((minX - STEP / 2.0) / STEP));
            var toX = Math.min(CELLS - 1, (int) Math.floor((maxX - STEP / 2.0) / STEP));
            var fromY = Math.max(0, (int) Math.ceil((minY - STEP / 2.0) / STEP));
            var toY = Math.min(CELLS - 1, (int) Math.floor((maxY - STEP / 2.0) / STEP));
            if (fromX > toX || fromY > toY) {
                return;
            }
            var ax = c[0] - originX;
            var ay = c[1] - originY;
            var bx = c[3] - originX;
            var by = c[4] - originY;
            var cx = c[6] - originX;
            var cy = c[7] - originY;
            var determinant = ((double) by - cy) * (ax - cx) + ((double) cx - bx) * (ay - cy);
            if (Math.abs(determinant) < 1e-9) {
                return;
            }
            for (var cellY = fromY; cellY <= toY; cellY++) {
                var y = cellY * STEP + STEP / 2.0;
                for (var cellX = fromX; cellX <= toX; cellX++) {
                    var x = cellX * STEP + STEP / 2.0;
                    var a = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / determinant;
                    var b = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / determinant;
                    var w = 1 - a - b;
                    if (a < -1e-6 || b < -1e-6 || w < -1e-6) {
                        continue;
                    }
                    add(cellY * CELLS + cellX, a * c[2] + b * c[5] + w * c[8]);
                }
            }
        }

        ClientFloorGrid finish() {
            var packed = new short[size];
            var blockStarts = new int[CELLS * CELLS / BLOCK];
            var cursor = 0;
            for (var cell = 0; cell < head.length; cell++) {
                if (cell % BLOCK == 0) {
                    blockStarts[cell / BLOCK] = cursor;
                }
                var start = cursor;
                for (var i = head[cell]; i >= 0; i = next[i]) {
                    packed[cursor++] = values[i];
                }
                Arrays.sort(packed, start, cursor);
            }
            return new ClientFloorGrid(owner, originX, originY, counts, blockStarts, packed);
        }

        private void add(int cell, double height) {
            if (counts[cell] >= MAX_PER_CELL || height < Short.MIN_VALUE || height > Short.MAX_VALUE) {
                return;
            }
            var rounded = (short) Math.round(height);
            for (var i = head[cell]; i >= 0; i = next[i]) {
                if (Math.abs(values[i] - rounded) <= MERGE) {
                    return;
                }
            }
            if (size == values.length) {
                next = Arrays.copyOf(next, size * 2);
                values = Arrays.copyOf(values, size * 2);
            }
            values[size] = rounded;
            next[size] = head[cell];
            head[cell] = size++;
            counts[cell]++;
        }
    }

    private static boolean isGround(Surface surface, boolean terrain) {
        if ((surface.type() & GeoClientSurfaceIndex.NOT_CLIENT_GEOMETRY) != 0
                || surface.water()
                || surface.blendMode() == BlendMode.INVISIBLE) {
            return false;
        }
        return terrain || surface.blendMode() == BlendMode.OPAQUE || surface.blendMode() == BlendMode.MASKED;
    }

    private static void transform(float[] m, float x, float y, float z, float[] out, int point) {
        out[point * 3] = m[0] * x + m[4] * y + m[8] * z + m[12];
        out[point * 3 + 1] = m[1] * x + m[5] * y + m[9] * z + m[13];
        out[point * 3 + 2] = m[2] * x + m[6] * y + m[10] * z + m[14];
    }
}
