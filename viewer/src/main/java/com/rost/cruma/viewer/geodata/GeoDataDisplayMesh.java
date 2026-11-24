package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataAccess.Cell;
import com.rost.cruma.core.geodata.GeoDataRegion;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

final class GeoDataDisplayMesh {
    static final float WALL_HEIGHT = 14;

    static final int RISER_LIMIT = 48;

    static final float WALL_INSET = .75f;

    static final float LIFT = 1;

    private static final int[] MASKS = {GeoDataRegion.NORTH, GeoDataRegion.SOUTH, GeoDataRegion.WEST, GeoDataRegion.EAST
    };
    private static final int[] DX = {0, 0, -16, 16};
    private static final int[] DY = {-16, 16, 0, 0};
    private static final int NONE = Integer.MIN_VALUE;
    private static final int SCAN_LIMIT = Integer.MAX_VALUE;

    private GeoDataDisplayMesh() {}

    static GeoDataMesh build(
            GeoDataAccess data,
            int centerX,
            int centerY,
            int radius,
            int maximumVertices,
            GeoDataDisplay display,
            BooleanSupplier cancelled) {
        var minX = Math.floorDiv(centerX - radius, 16) * 16;
        var minY = Math.floorDiv(centerY - radius, 16) * 16;
        var maxX = centerX + GeoDataOverlayLoader.SNAP_SIZE + radius;
        var maxY = centerY + GeoDataOverlayLoader.SNAP_SIZE + radius;
        return build(
                        data,
                        minX,
                        minY,
                        maxX,
                        maxY,
                        centerX,
                        centerY,
                        maximumVertices,
                        Math.clamp(maximumVertices, 4096, GeoDataMesh.MAX_VERTICES),
                        display,
                        cancelled)
                .mesh();
    }

    record Piece(GeoDataMesh mesh, int work, float minZ, float maxZ) {}

    static Piece build(
            GeoDataAccess data,
            int minX,
            int minY,
            int maxX,
            int maxY,
            int focusX,
            int focusY,
            int maximumVertices,
            int maximumWork,
            GeoDataDisplay display,
            BooleanSupplier cancelled) {
        var sectors = new ArrayList<Sector>();
        for (var x = Math.floorDiv(minX, 128) * 128; x < maxX; x += 128) {
            for (var y = Math.floorDiv(minY, 128) * 128; y < maxY; y += 128) {
                sectors.add(new Sector(x, y));
            }
        }
        sectors.sort(Comparator.comparingLong(s -> s.distanceSquared(focusX, focusY)));
        var cells = new CellCache(data, minX - 16, minY - 16, maxX + 16, maxY + 16);
        var result = new Builder(maximumVertices);
        var work = new ScanBudget(maximumWork);
        var open = new HashMap<Key, Float>();
        var openOrder = new ArrayList<Key>();
        outer:
        for (var sector : sectors) {
            check(cancelled);
            open.clear();
            openOrder.clear();
            for (var x = Math.max(minX, sector.x); x < Math.min(maxX, sector.x + 128); x += 16) {
                for (var y = Math.max(minY, sector.y); y < Math.min(maxY, sector.y + 128); y += 16) {
                    if (!work.use()) {
                        result.truncated = true;
                        break outer;
                    }
                    var cell = cells.at(x, y);
                    if (cell.availability() != Availability.AVAILABLE) {
                        continue;
                    }
                    for (var stored : cell.layers()) {
                        check(cancelled);
                        if (!work.use()) {
                            result.truncated = true;
                            break outer;
                        }
                        var alpha = display.layerAlpha(data, cell, stored);
                        if (alpha == 0) {
                            continue;
                        }
                        result.layers++;
                        var z = stored.height();
                        var nswe = stored.nswe() & 15;
                        if (nswe == 15) {
                            var key = new Key(x, y, z);
                            if (open.putIfAbsent(key, alpha) == null) {
                                openOrder.add(key);
                            }
                        } else if (!result.isTop(
                                x,
                                y,
                                1,
                                1,
                                z,
                                ~nswe & 15,
                                nswe == 0 ? GeoDataMesh.OBSTACLE_COLOR : GeoDataMesh.OPEN_COLOR,
                                alpha)) {
                            break outer;
                        }
                        for (var direction = 0; direction < 4; direction++) {
                            if (nswe != 0
                                    && (nswe & MASKS[direction]) == 0
                                    && !result.addWall(x, y, z, direction, alpha)) {
                                break outer;
                            }
                            var bottom = riserBottom(cells, x, y, z, direction, work);
                            if (bottom == SCAN_LIMIT) {
                                result.truncated = true;
                                break outer;
                            }
                            if (bottom != NONE
                                    && !result.addRiser(
                                            x,
                                            y,
                                            z,
                                            bottom,
                                            direction,
                                            nswe == 0 ? GeoDataMesh.OBSTACLE_COLOR : GeoDataMesh.OPEN_COLOR,
                                            alpha)) {
                                break outer;
                            }
                        }
                    }
                }
            }
            if (!mergeOpen(open, openOrder, result, cancelled)) {
                break;
            }
        }
        return result.finish(work.getUsed());
    }

    private static int riserBottom(CellCache cells, int x, int y, int z, int direction, ScanBudget work) {
        var neighbor = cells.at(x + DX[direction], y + DY[direction]);
        if (neighbor.availability() != Availability.AVAILABLE) {
            return NONE;
        }
        var bottom = NONE;
        for (var layer : neighbor.layers()) {
            if (!work.use()) {
                return SCAN_LIMIT;
            }
            var height = layer.height();
            if (height >= z && height - z <= RISER_LIMIT) {
                return NONE;
            }
            if (height < z && z - height <= RISER_LIMIT && height > bottom) {
                bottom = height;
            }
        }
        return bottom;
    }

    private static boolean mergeOpen(
            HashMap<Key, Float> open, ArrayList<Key> order, Builder result, BooleanSupplier cancelled) {
        for (var key : order) {
            check(cancelled);
            var alpha = open.get(key);
            if (alpha == null) {
                continue;
            }
            var width = 1;
            while (alpha.equals(open.get(new Key(key.x + width * 16, key.y, key.z)))) {
                width++;
            }
            var height = 1;
            grow:
            while (true) {
                for (var x = 0; x < width; x++) {
                    if (!alpha.equals(open.get(new Key(key.x + x * 16, key.y + height * 16, key.z)))) {
                        break grow;
                    }
                }
                height++;
            }
            for (var x = 0; x < width; x++) {
                for (var y = 0; y < height; y++) {
                    open.remove(new Key(key.x + x * 16, key.y + y * 16, key.z));
                }
            }
            if (!result.isTop(key.x, key.y, width, height, key.z, 0, GeoDataMesh.OPEN_COLOR, alpha)) {
                return false;
            }
        }
        return true;
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }

    private static final class ScanBudget {
        private final int limit;
        private int remaining;

        ScanBudget(int budget) {
            limit = Math.max(4096, budget);
            remaining = limit;
        }

        boolean use() {
            return remaining-- > 0;
        }

        int getUsed() {
            return limit - Math.max(0, remaining);
        }
    }

    private static final class CellCache {
        private final GeoDataAccess data;
        private final int minX;
        private final int minY;
        private final int width;
        private final Cell[] cells;

        CellCache(GeoDataAccess data, int minX, int minY, int maxX, int maxY) {
            this.data = data;
            this.minX = minX;
            this.minY = minY;
            width = Math.ceilDiv(maxX - minX, 16);
            cells = new Cell[width * Math.ceilDiv(maxY - minY, 16)];
        }

        Cell at(int x, int y) {
            var column = Math.floorDiv(x - minX, 16);
            var row = Math.floorDiv(y - minY, 16);
            if (column < 0 || row < 0 || column >= width || row * width + column >= cells.length) {
                return data.cellAt(x, y);
            }
            var index = row * width + column;
            var cell = cells[index];
            if (cell == null) {
                cell = data.cellAt(x, y);
                cells[index] = cell;
            }
            return cell;
        }
    }

    private record Key(int x, int y, int z) {}

    private record Sector(int x, int y) {
        long distanceSquared(int cx, int cy) {
            return (long) (x - cx) * (x - cx) + (long) (y - cy) * (y - cy);
        }
    }

    private static final class Builder {
        private static final int QUAD_FLOATS = 6 * GeoDataMesh.FLOATS_PER_VERTEX;
        private final int maximumQuads;
        private float[] vertices = new float[QUAD_FLOATS * 64];
        private long[] order = new long[64];
        private int quads;
        private int layers;
        private boolean truncated;

        private Builder(int maximumVertices) {
            maximumQuads = Math.clamp(maximumVertices, 0, GeoDataMesh.MAX_VERTICES) / 6;
        }

        private boolean isTop(int x, int y, int width, int height, int z, int blocked, int color, float alpha) {
            var top = z + LIFT;
            return addQuad(
                    z,
                    GeoDataMesh.TOP,
                    x,
                    y,
                    top,
                    x + width * 16,
                    y,
                    top,
                    x + width * 16,
                    y + height * 16,
                    top,
                    x,
                    y + height * 16,
                    top,
                    color,
                    alpha,
                    blocked,
                    0,
                    0,
                    0,
                    0);
        }

        private boolean addWall(int x, int y, int z, int direction, float alpha) {
            var bottom = z + LIFT;
            var top = bottom + WALL_HEIGHT;
            float x1 = x, y1 = y, x2 = x + 16, y2 = y + 16;
            switch (direction) {
                case 0 -> y2 = y1 = y + WALL_INSET;
                case 1 -> y1 = y2 = y + 16 - WALL_INSET;
                case 2 -> x2 = x1 = x + WALL_INSET;
                default -> x1 = x2 = x + 16 - WALL_INSET;
            }
            return addQuad(
                    z,
                    GeoDataMesh.WALL,
                    x1,
                    y1,
                    bottom,
                    x2,
                    y2,
                    bottom,
                    x2,
                    y2,
                    top,
                    x1,
                    y1,
                    top,
                    GeoDataMesh.WALL_COLOR,
                    alpha,
                    0,
                    0,
                    0,
                    1,
                    1);
        }

        private boolean addRiser(int x, int y, int z, int lower, int direction, int color, float alpha) {
            float x1 = x, y1 = y, x2 = x + 16, y2 = y + 16;
            switch (direction) {
                case 0 -> y2 = y1;
                case 1 -> y1 = y2;
                case 2 -> x2 = x1;
                default -> x1 = x2;
            }
            var bottom = lower + LIFT;
            var top = z + LIFT;
            return addQuad(
                    z,
                    GeoDataMesh.RISER,
                    x1,
                    y1,
                    bottom,
                    x2,
                    y2,
                    bottom,
                    x2,
                    y2,
                    top,
                    x1,
                    y1,
                    top,
                    color,
                    alpha,
                    z - lower,
                    0,
                    0,
                    1,
                    1);
        }

        private boolean addQuad(
                int floor,
                float kind,
                float ax,
                float ay,
                float az,
                float bx,
                float by,
                float bz,
                float cx,
                float cy,
                float cz,
                float dx,
                float dy,
                float dz,
                int color,
                float alpha,
                int blocked,
                float ta,
                float tb,
                float tc,
                float td) {
            if (quads >= maximumQuads) {
                truncated = true;
                return false;
            }
            if ((quads + 1) * QUAD_FLOATS > vertices.length) {
                vertices = Arrays.copyOf(vertices, vertices.length * 2);
                order = Arrays.copyOf(order, order.length * 2);
            }
            var rgba = Float.intBitsToFloat((color & 0xffffff) | Math.round(alpha * 255) << 24);
            var offset = quads * QUAD_FLOATS;
            offset = putVertex(offset, ax, ay, az, rgba, blocked, kind, ta, floor);
            offset = putVertex(offset, bx, by, bz, rgba, blocked, kind, tb, floor);
            offset = putVertex(offset, cx, cy, cz, rgba, blocked, kind, tc, floor);
            offset = putVertex(offset, ax, ay, az, rgba, blocked, kind, ta, floor);
            offset = putVertex(offset, cx, cy, cz, rgba, blocked, kind, tc, floor);
            putVertex(offset, dx, dy, dz, rgba, blocked, kind, td, floor);
            order[quads] = (long) (floor + 32768) << 34 | (long) kind << 32 | quads;
            quads++;
            return true;
        }

        private int putVertex(
                int offset, float x, float y, float z, float rgba, int blocked, float kind, float fraction, int floor) {
            vertices[offset++] = x;
            vertices[offset++] = y;
            vertices[offset++] = z;
            vertices[offset++] = rgba;
            vertices[offset++] = blocked;
            vertices[offset++] = kind;
            vertices[offset++] = fraction;
            vertices[offset++] = floor;
            return offset;
        }

        private Piece finish(int work) {
            if (quads == 0 && !truncated) {
                return new Piece(
                        layers == 0 ? GeoDataMesh.EMPTY : new GeoDataMesh(new float[0], layers, false, 0), work, 0, 0);
            }
            var keys = Arrays.copyOf(order, quads);
            Arrays.sort(keys);
            var sorted = new float[quads * QUAD_FLOATS];
            var minZ = Float.POSITIVE_INFINITY;
            var maxZ = Float.NEGATIVE_INFINITY;
            for (var index = 0; index < quads; index++) {
                var from = (int) keys[index] * QUAD_FLOATS;
                System.arraycopy(vertices, from, sorted, index * QUAD_FLOATS, QUAD_FLOATS);
                minZ = Math.min(minZ, vertices[from + 2]);
                maxZ = Math.max(maxZ, vertices[from + 2 * GeoDataMesh.FLOATS_PER_VERTEX + 2]);
            }
            return new Piece(
                    new GeoDataMesh(sorted, layers, truncated, quads),
                    work,
                    quads == 0 ? 0 : minZ,
                    quads == 0 ? 0 : maxZ);
        }
    }
}
