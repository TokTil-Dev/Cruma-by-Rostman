package com.rost.cruma.core.geodata.generation;

import java.util.Arrays;

public final class CollisionWorld {
    static final int GRID = 64;

    private static final Kind[] KINDS = Kind.values();

    private static final int QUAD_FLOATS = 8;

    private static final int GRID_FLOATS = 7;

    private static final int PLAN_NONE = 0;
    private static final int PLAN_SINGLE = 1;
    private static final int PLAN_ROWS = 2;
    private static final int PLAN_COLUMNS = 3;
    private static final byte MESH = 0;
    private static final byte TERRAIN = 1;

    private static final double FIRST_ENTRY = -1;

    private static final float SMALL_NUMBER = 1e-8f;

    private final float[] triangles;
    private final byte[] kinds;

    private final float[] quads;

    private final float[] grids;

    private final int[] terrainSlots;

    private final int terrainCount;
    private volatile TerrainCache terrainCache;

    private final int[][] gridQuads;

    private final int[] gridWidths;

    private final float[] bounds;
    private final float originX;
    private final float originY;
    private final int columns;
    private final int rows;
    private final int cellColumns;
    private final int cellRows;
    private final int[] cellStart;
    private int[] references;

    private float[] referenceBounds;

    private CollisionWorld(
            float[] source,
            byte[] sourceKinds,
            float[] sourceQuads,
            float[] grids,
            float minX,
            float minY,
            float maxX,
            float maxY) {
        this.grids = grids;
        var gridCount = grids.length / GRID_FLOATS;
        gridQuads = new int[gridCount][];
        gridWidths = new int[gridCount];
        for (var g = 0; g < gridCount; g++) {
            gridWidths[g] = (int) grids[g * GRID_FLOATS + 4] - 1;
            gridQuads[g] = new int[gridWidths[g] * ((int) grids[g * GRID_FLOATS + 5] - 1) * 2];
            Arrays.fill(gridQuads[g], -1);
        }
        originX = minX;
        originY = minY;
        columns = Math.max(1, (int) Math.ceil((maxX - minX) / GRID));
        rows = Math.max(1, (int) Math.ceil((maxY - minY) / GRID));
        var count = source.length / 9;
        var kept = new float[source.length];
        var keptKinds = new byte[count];
        var keptQuads = new float[count * QUAD_FLOATS];
        var keptBounds = new float[count * 6];
        var keptCount = 0;
        for (var t = 0; t < count; t++) {
            var o = t * 9;
            var tMinX = Math.min(source[o], Math.min(source[o + 3], source[o + 6]));
            var tMinY = Math.min(source[o + 1], Math.min(source[o + 4], source[o + 7]));
            var tMinZ = Math.min(source[o + 2], Math.min(source[o + 5], source[o + 8]));
            var tMaxX = Math.max(source[o], Math.max(source[o + 3], source[o + 6]));
            var tMaxY = Math.max(source[o + 1], Math.max(source[o + 4], source[o + 7]));
            var tMaxZ = Math.max(source[o + 2], Math.max(source[o + 5], source[o + 8]));
            if (!Float.isFinite(tMinX + tMinY + tMinZ + tMaxX + tMaxY + tMaxZ)
                    || tMaxX < minX
                    || tMinX > maxX
                    || tMaxY < minY
                    || tMinY > maxY) {
                continue;
            }
            System.arraycopy(source, o, kept, keptCount * 9, 9);
            keptKinds[keptCount] = sourceKinds[t];
            System.arraycopy(sourceQuads, t * QUAD_FLOATS, keptQuads, keptCount * QUAD_FLOATS, QUAD_FLOATS);
            var b = keptCount * 6;
            keptBounds[b] = tMinX;
            keptBounds[b + 1] = tMinY;
            keptBounds[b + 2] = tMinZ;
            keptBounds[b + 3] = tMaxX;
            keptBounds[b + 4] = tMaxY;
            keptBounds[b + 5] = tMaxZ;
            if (sourceKinds[t] == TERRAIN && (int) sourceQuads[t * QUAD_FLOATS + 4] >= 0) {
                var g = (int) sourceQuads[t * QUAD_FLOATS + 4];
                var column = (int) sourceQuads[t * QUAD_FLOATS + 5];
                var row = (int) sourceQuads[t * QUAD_FLOATS + 6];
                var slot = (row * gridWidths[g] + column) * 2;
                gridQuads[g][gridQuads[g][slot] < 0 ? slot : slot + 1] = keptCount;
                keptCount++;
                continue;
            }
            keptCount++;
        }
        triangles = Arrays.copyOf(kept, keptCount * 9);
        kinds = Arrays.copyOf(keptKinds, keptCount);
        quads = Arrays.copyOf(keptQuads, keptCount * QUAD_FLOATS);
        bounds = Arrays.copyOf(keptBounds, keptCount * 6);
        terrainSlots = new int[keptCount];
        var terrainTotal = 0;
        for (var t = 0; t < keptCount; t++) {
            terrainSlots[t] = kinds[t] == TERRAIN ? terrainTotal++ : -1;
        }
        terrainCount = terrainTotal;
        cellColumns = columns;
        cellRows = rows;
        var cellCount = cellColumns * cellRows;
        cellStart = new int[cellCount + 1];
        for (var pass = 0; pass < 2; pass++) {
            var fill = pass == 0 ? null : Arrays.copyOf(cellStart, cellCount);
            for (var t = 0; t < keptCount; t++) {
                if (kinds[t] == TERRAIN && (int) quads[t * QUAD_FLOATS + 4] >= 0) {
                    continue;
                }
                var b = t * 6;
                for (var gx = columnOf(bounds[b]); gx <= columnOf(bounds[b + 3]); gx++) {
                    for (var gy = rowOf(bounds[b + 1]); gy <= rowOf(bounds[b + 4]); gy++) {
                        var cell = gx * cellRows + gy;
                        if (fill == null) {
                            cellStart[cell + 1]++;
                            continue;
                        }
                        var r = fill[cell]++;
                        references[r] = t;
                        System.arraycopy(bounds, b, referenceBounds, r * 6, 6);
                    }
                }
            }
            if (pass == 0) {
                for (var c = 0; c < cellCount; c++) {
                    cellStart[c + 1] += cellStart[c];
                }
                references = new int[cellStart[cellCount]];
                referenceBounds = new float[references.length * 6];
            }
        }
    }

    public enum Kind {
        MESH,
        TERRAIN,
        MODEL
    }

    public static final class Builder {
        private float[] triangles = new float[9 * 1024];
        private byte[] kinds = new byte[1024];
        private float[] quads = new float[QUAD_FLOATS * 1024];
        private float[] grids = new float[0];
        private int count;

        public Builder add(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz) {
            return add(ax, ay, az, bx, by, bz, cx, cy, cz, Kind.MESH);
        }

        public Builder add(
                float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, Kind kind) {
            if (kind == Kind.TERRAIN) {
                return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax) < 0
                        ? addTerrain(ax, ay, az, ax, ay, az, cx, cy, cz, bx, by, bz, 0)
                        : addTerrain(ax, ay, az, ax, ay, az, bx, by, bz, cx, cy, cz, 0);
            }
            return addTriangle(ax, ay, az, bx, by, bz, cx, cy, cz, kind);
        }

        public int addTerrainGrid(
                float originX, float originY, float quadX, float quadY, int width, int height, float heightScale) {
            if (!(quadX > 0) || !(quadY > 0) || !(heightScale > 0) || width < 2 || height < 2) {
                throw new IllegalArgumentException("Bad terrain grid");
            }
            var offset = grids.length;
            grids = Arrays.copyOf(grids, offset + GRID_FLOATS);
            grids[offset] = originX;
            grids[offset + 1] = originY;
            grids[offset + 2] = quadX;
            grids[offset + 3] = quadY;
            grids[offset + 4] = width;
            grids[offset + 5] = height;
            grids[offset + 6] = heightScale;
            return offset / GRID_FLOATS;
        }

        public Builder addTerrain(
                float referenceX,
                float referenceY,
                float referenceZ,
                float ax,
                float ay,
                float az,
                float bx,
                float by,
                float bz,
                float cx,
                float cy,
                float cz,
                int planeCorner) {
            return addTerrain(
                    referenceX, referenceY, referenceZ, ax, ay, az, bx, by, bz, cx, cy, cz, planeCorner, -1, 0, 0);
        }

        public Builder addTerrain(
                float referenceX,
                float referenceY,
                float referenceZ,
                float ax,
                float ay,
                float az,
                float bx,
                float by,
                float bz,
                float cx,
                float cy,
                float cz,
                int planeCorner,
                int grid,
                int column,
                int row) {
            if (planeCorner < 0 || planeCorner > 2) {
                throw new IllegalArgumentException("Plane corner " + planeCorner);
            }
            if (grid >= grids.length / GRID_FLOATS) {
                throw new IllegalArgumentException("Unknown terrain grid " + grid);
            }
            addTriangle(ax, ay, az, bx, by, bz, cx, cy, cz, Kind.TERRAIN);
            var offset = (count - 1) * QUAD_FLOATS;
            quads[offset] = referenceX;
            quads[offset + 1] = referenceY;
            quads[offset + 2] = referenceZ;
            quads[offset + 3] = planeCorner;
            quads[offset + 4] = grid;
            quads[offset + 5] = column;
            quads[offset + 6] = row;
            return this;
        }

        public int size() {
            return count;
        }

        public CollisionWorld build(float minX, float minY, float maxX, float maxY) {
            if (!(minX < maxX) || !(minY < maxY)) {
                throw new IllegalArgumentException("Empty collision bounds");
            }
            return new CollisionWorld(
                    Arrays.copyOf(triangles, count * 9),
                    Arrays.copyOf(kinds, count),
                    Arrays.copyOf(quads, count * QUAD_FLOATS),
                    grids.clone(),
                    minX,
                    minY,
                    maxX,
                    maxY);
        }

        private Builder addTriangle(
                float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, Kind kind) {
            if (count == triangles.length / 9) {
                triangles = Arrays.copyOf(triangles, triangles.length * 2);
                kinds = Arrays.copyOf(kinds, kinds.length * 2);
                quads = Arrays.copyOf(quads, quads.length * 2);
            }
            kinds[count] = (byte) kind.ordinal();
            var offset = count * 9;
            triangles[offset] = ax;
            triangles[offset + 1] = ay;
            triangles[offset + 2] = az;
            triangles[offset + 3] = bx;
            triangles[offset + 4] = by;
            triangles[offset + 5] = bz;
            triangles[offset + 6] = cx;
            triangles[offset + 7] = cy;
            triangles[offset + 8] = cz;
            count++;
            return this;
        }
    }

    public static final class Hit {
        public double time;

        public float normalX;
        public float normalY;
        public float normalZ;
        public Kind kind;
    }

    public final class Query {
        private int[] meetings = new int[32];
        private int met;
        private double enter;
        private double exit;
        private double enterX;
        private double enterY;
        private double enterZ;
        private double time;
        private double terrainTime;
        private TerrainCache terrain;
        private float moveStartX;
        private float moveStartY;
        private float moveStartZ;
        private float moveEndX;
        private float moveEndY;
        private float moveEndZ;
        private float moveDeltaX;
        private float moveDeltaY;
        private float moveDeltaZ;
        private float moveSquare;
        private float moveSize;
        private boolean found;
        private double best;
        private long bestRank;
        private int spanY;
        private int lowX;
        private int lowY;
        private Hit hit;
        private boolean backOff;
        private double length;
        private int planMode;
        private int planColumn;
        private int planRow;
        private int planFirst;
        private int planLast;
        private int planWidth;
        private int planHeight;
        private float planSlope;
        private float planIntercept;
        private float planExtent;
        private double normalX;
        private double normalY;
        private double normalZ;

        private Query() {}

        public boolean sweep(
                double sx,
                double sy,
                double sz,
                double dx,
                double dy,
                double dz,
                double ex,
                double ey,
                double ez,
                Hit hit) {
            return sweep(sx, sy, sz, dx, dy, dz, ex, ey, ez, hit, true);
        }

        public boolean sweepToContact(
                double sx,
                double sy,
                double sz,
                double dx,
                double dy,
                double dz,
                double ex,
                double ey,
                double ez,
                Hit hit) {
            return sweep(sx, sy, sz, dx, dy, dz, ex, ey, ez, hit, false);
        }

        private boolean sweep(
                double sx,
                double sy,
                double sz,
                double dx,
                double dy,
                double dz,
                double ex,
                double ey,
                double ez,
                Hit hit,
                boolean backOff) {
            var minX = Math.min(sx, sx + dx) - ex;
            var minY = Math.min(sy, sy + dy) - ey;
            var minZ = Math.min(sz, sz + dz) - ez;
            var maxX = Math.max(sx, sx + dx) + ex;
            var maxY = Math.max(sy, sy + dy) + ey;
            var maxZ = Math.max(sz, sz + dz) + ez;
            var lowX = Math.max(0, (int) Math.floor((minX - originX) / GRID));
            var lowY = Math.max(0, (int) Math.floor((minY - originY) / GRID));
            var highX = Math.min(columns - 1, (int) Math.floor((maxX - originX) / GRID));
            var highY = Math.min(rows - 1, (int) Math.floor((maxY - originY) / GRID));
            if (lowX > highX || lowY > highY) {
                return false;
            }
            var length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            prepareTerrainMove(sx, sy, sz, dx, dy, dz, ez);
            found = false;
            best = Double.MAX_VALUE;
            spanY = highY - lowY + 1;
            this.lowX = lowX;
            this.lowY = lowY;
            this.hit = hit;
            this.backOff = backOff;
            this.length = length;
            var manyCells = lowX != highX || lowY != highY;
            met = 0;
            for (var gx = lowX; gx <= highX; gx++) {
                for (var gy = lowY; gy <= highY; gy++) {
                    var cell = gx * cellRows + gy;
                    for (var r = cellStart[cell]; r < cellStart[cell + 1]; r++) {
                        var box = r * 6;
                        if (referenceBounds[box + 2] > maxZ
                                || referenceBounds[box + 5] < minZ
                                || referenceBounds[box] > maxX
                                || referenceBounds[box + 3] < minX
                                || referenceBounds[box + 1] > maxY
                                || referenceBounds[box + 4] < minY) {
                            continue;
                        }
                        var triangle = references[r];
                        if (manyCells && !isFirstMeeting(triangle)) {
                            continue;
                        }
                        var b = triangle * 6;
                        if (bounds[b] > maxX
                                || bounds[b + 3] < minX
                                || bounds[b + 1] > maxY
                                || bounds[b + 4] < minY
                                || bounds[b + 2] > maxZ
                                || bounds[b + 5] < minZ) {
                            continue;
                        }
                        testCandidate(triangle, sx, sy, sz, dx, dy, dz, ex, ey, ez);
                    }
                }
            }
            for (var grid = 0; grid < gridQuads.length; grid++) {
                terrainQuads(grid, sx, sy, sz, dx, dy, dz, ex, ey, ez, minX, minY, minZ, maxX, maxY, maxZ);
            }
            return found;
        }

        private int terrainQuads(
                int grid,
                double sx,
                double sy,
                double sz,
                double dx,
                double dy,
                double dz,
                double ex,
                double ey,
                double ez,
                double minX,
                double minY,
                double minZ,
                double maxX,
                double maxY,
                double maxZ) {
            planQuadWalk(grid, sx, sy, sz, dx, dy, dz, ex);
            var tested = 0;
            switch (planMode) {
                case PLAN_SINGLE ->
                    tested += terrainQuad(
                            grid,
                            planColumn,
                            planRow,
                            sx,
                            sy,
                            sz,
                            dx,
                            dy,
                            dz,
                            ex,
                            ey,
                            ez,
                            minX,
                            minY,
                            minZ,
                            maxX,
                            maxY,
                            maxZ);
                case PLAN_ROWS -> {
                    for (var row = Math.min(planFirst, planLast); row <= Math.max(planFirst, planLast); row++) {
                        var from = spanFrom(row, planWidth);
                        var to = spanTo(row, planWidth);
                        for (var column = from; column <= to; column++) {
                            tested += terrainQuad(
                                    grid, column, row, sx, sy, sz, dx, dy, dz, ex, ey, ez, minX, minY, minZ, maxX, maxY,
                                    maxZ);
                        }
                    }
                }
                case PLAN_COLUMNS -> {
                    for (var column = Math.min(planFirst, planLast);
                            column <= Math.max(planFirst, planLast);
                            column++) {
                        var from = spanFrom(column, planHeight);
                        var to = spanTo(column, planHeight);
                        for (var row = from; row <= to; row++) {
                            tested += terrainQuad(
                                    grid, column, row, sx, sy, sz, dx, dy, dz, ex, ey, ez, minX, minY, minZ, maxX, maxY,
                                    maxZ);
                        }
                    }
                }
                default -> {
                }
            }
            return tested;
        }

        private int terrainQuad(
                int grid,
                int column,
                int row,
                double sx,
                double sy,
                double sz,
                double dx,
                double dy,
                double dz,
                double ex,
                double ey,
                double ez,
                double minX,
                double minY,
                double minZ,
                double maxX,
                double maxY,
                double maxZ) {
            var quads = gridQuads[grid];
            var width = gridWidths[grid];
            if (column >= width || row >= quads.length / 2 / width) {
                return 0;
            }
            var tested = 0;
            for (var slot = 0; slot < 2; slot++) {
                var triangle = quads[(row * width + column) * 2 + slot];
                if (triangle < 0) {
                    continue;
                }
                var b = triangle * 6;
                if (bounds[b] > maxX
                        || bounds[b + 3] < minX
                        || bounds[b + 1] > maxY
                        || bounds[b + 4] < minY
                        || bounds[b + 2] > maxZ
                        || bounds[b + 5] < minZ) {
                    continue;
                }
                tested++;
                testCandidate(triangle, sx, sy, sz, dx, dy, dz, ex, ey, ez);
            }
            return tested;
        }

        private int spanFrom(int along, int size) {
            var a = along * planSlope + planIntercept;
            var b = (along + 1) * planSlope + planIntercept;
            return clamp((int) (Math.min(a, b) - planExtent), size);
        }

        private int spanTo(int along, int size) {
            var a = along * planSlope + planIntercept;
            var b = (along + 1) * planSlope + planIntercept;
            return clamp((int) (Math.max(a, b) + planExtent), size);
        }

        private void testCandidate(
                int triangle,
                double sx,
                double sy,
                double sz,
                double dx,
                double dy,
                double dz,
                double ex,
                double ey,
                double ez) {
            var kind = kinds[triangle];
            if (kind == TERRAIN
                    && (int) quads[triangle * QUAD_FLOATS + 4] < 0
                    && !isTestedByTrace(triangle, sx, sy, dx, dy)) {
                return;
            }
            var touches = kind == TERRAIN
                    ? testTerrainTriangle(triangle, ex)
                    : sweepTriangle(triangle, sx, sy, sz, dx, dy, dz, ex, ey, ez);
            if (!touches) {
                return;
            }
            if (backOff) {
                time = kind == TERRAIN ? terrainTime : backOff(kind, time, length);
            }
            if (time > best) {
                return;
            }
            var b = triangle * 6;
            var firstX = Math.max(lowX, columnOf(bounds[b]));
            var firstY = Math.max(lowY, rowOf(bounds[b + 1]));
            var rank = ((long) ((firstX - lowX) * spanY + (firstY - lowY)) << 32) | triangle;
            if (time == best && rank > bestRank) {
                return;
            }
            best = time;
            bestRank = rank;
            hit.time = time;
            hit.normalX = (float) normalX;
            hit.normalY = (float) normalY;
            hit.normalZ = (float) normalZ;
            hit.kind = KINDS[kind];
            found = true;
        }

        private boolean isTestedByTrace(int triangle, double sx, double sy, double dx, double dy) {
            if (dx != 0 || dy != 0) {
                return true;
            }
            var b = triangle * 6;
            return sx >= bounds[b] && sx < bounds[b + 3] && sy >= bounds[b + 1] && sy < bounds[b + 4];
        }

        private void planQuadWalk(
                int grid, double sx, double sy, double sz, double dx, double dy, double dz, double halfWidth) {
            var g = grid * GRID_FLOATS;
            var originX = grids[g];
            var originY = grids[g + 1];
            var quadX = grids[g + 2];
            var quadY = grids[g + 3];
            planWidth = (int) grids[g + 4];
            planHeight = (int) grids[g + 5];
            var heightScale = grids[g + 6];
            var startX = (float) sx;
            var startY = (float) sy;
            var endX = startX + (float) dx;
            var endY = startY + (float) dy;
            var endZ = (float) sz + (float) dz;
            var fromX = (startX - originX) / quadX;
            var fromY = (startY - originY) / quadY;
            var toX = (endX - originX) / quadX;
            var toY = (endY - originY) / quadY;
            var directionX = toX - fromX;
            var directionY = toY - fromY;
            var directionZ = (endZ - (float) sz) / heightScale;
            var squareSum = directionX * directionX + directionY * directionY + directionZ * directionZ;
            if (squareSum < SMALL_NUMBER) {
                directionX = 0;
                directionY = 0;
            } else {
                var scale = 1f / (float) Math.sqrt(squareSum);
                directionX *= scale;
                directionY *= scale;
            }
            planExtent = Math.abs((float) halfWidth / quadX);
            fromX -= directionX * planExtent;
            fromY -= directionY * planExtent;
            toX += directionX * planExtent;
            toY += directionY * planExtent;
            if (!((-0.005f < fromX || -0.005f <= toX)
                    && (-0.005f <= fromY || -0.005f <= toY)
                    && (fromX < planWidth || toX < planWidth)
                    && (fromY < planHeight || toY < planHeight))) {
                planMode = PLAN_NONE;
                return;
            }
            var firstColumn = clamp((int) fromX, planWidth);
            var lastColumn = clamp((int) toX, planWidth);
            if (firstColumn == lastColumn) {
                var firstRow = clamp((int) fromY, planHeight);
                var lastRow = clamp((int) toY, planHeight);
                if (firstRow == lastRow) {
                    planMode = PLAN_SINGLE;
                    planColumn = firstColumn;
                    planRow = firstRow;
                    return;
                }
                planMode = PLAN_ROWS;
                planFirst = firstRow;
                planLast = lastRow;
                planSlope = (toX - fromX) / (toY - fromY);
                planIntercept = toX - planSlope * toY;
                return;
            }
            planMode = PLAN_COLUMNS;
            planFirst = firstColumn;
            planLast = lastColumn;
            planSlope = (toY - fromY) / (toX - fromX);
            planIntercept = toY - planSlope * toX;
        }

        private boolean testTerrainTriangle(int triangle, double halfWidth) {
            var cache = terrain;
            var slot = terrainSlots[triangle];
            if ((cache.flags[slot] & TerrainCache.NO_NORMAL) != 0) {
                return false;
            }
            var v = cache.floats;
            var c = slot * TerrainCache.FLOATS;
            var nx = v[c];
            var ny = v[c + 1];
            var nz = v[c + 2];
            if (!(ny * moveDeltaY + nx * moveDeltaX + nz * moveDeltaZ < -0.0001)) {
                return false;
            }
            var q = triangle * QUAD_FLOATS;
            var originX = quads[q];
            var originY = quads[q + 1];
            var originZ = quads[q + 2];
            var fromX = moveStartX - originX;
            var fromY = moveStartY - originY;
            var fromZ = moveStartZ - originZ;
            var toX = moveEndX - originX;
            var toY = moveEndY - originY;
            var toZ = moveEndZ - originZ;
            var t = (v[c + 3] - (toY * ny + toX * nx + toZ * nz))
                    / ((fromY - toY) * ny + (fromX - toX) * nx + (fromZ - toZ) * nz);
            var hitX = (fromX - toX) * t + toX;
            var hitY = (fromY - toY) * t + toY;
            var hitZ = (fromZ - toZ) * t + toZ;
            var along = ((hitY - fromY) * moveDeltaY + (hitX - fromX) * moveDeltaX + (hitZ - fromZ) * moveDeltaZ)
                    / moveSquare;
            if (!(0 <= along && along <= 1)) {
                return false;
            }
            var margin = (float) halfWidth;
            var flags = cache.flags[slot];
            for (var edge = 0; edge < 3; edge++) {
                if ((flags & TerrainCache.NO_EDGE << edge) != 0) {
                    continue;
                }
                var e = c + 4 + edge * 4;
                if ((v[e] * hitX + hitY * v[e + 1] + hitZ * v[e + 2]) - v[e + 3] > margin) {
                    return false;
                }
            }
            var backed = along - 0.5f / moveSize;
            time = along;
            terrainTime = backed < 0 ? 0 : backed >= 1 ? 1 : backed;
            normalX = nx;
            normalY = ny;
            normalZ = nz;
            return true;
        }

        private void prepareTerrainMove(double sx, double sy, double sz, double dx, double dy, double dz, double ez) {
            terrain = terrainCache((float) ez);
            moveStartX = (float) sx;
            moveStartY = (float) sy;
            moveStartZ = (float) sz;
            moveEndX = moveStartX + (float) dx;
            moveEndY = moveStartY + (float) dy;
            moveEndZ = moveStartZ + (float) dz;
            moveDeltaX = moveEndX - moveStartX;
            moveDeltaY = moveEndY - moveStartY;
            moveDeltaZ = moveEndZ - moveStartZ;
            moveSquare = moveDeltaY * moveDeltaY + moveDeltaX * moveDeltaX + moveDeltaZ * moveDeltaZ;
            moveSize = (float) Math.sqrt(moveDeltaX * moveDeltaX + moveDeltaY * moveDeltaY + moveDeltaZ * moveDeltaZ);
        }

        private boolean sweepTriangle(
                int triangle,
                double sx,
                double sy,
                double sz,
                double dx,
                double dy,
                double dz,
                double ex,
                double ey,
                double ez) {
            var o = triangle * 9;
            var ax = (double) triangles[o];
            var ay = (double) triangles[o + 1];
            var az = (double) triangles[o + 2];
            var bx = (double) triangles[o + 3];
            var by = (double) triangles[o + 4];
            var bz = (double) triangles[o + 5];
            var cx = (double) triangles[o + 6];
            var cy = (double) triangles[o + 7];
            var cz = (double) triangles[o + 8];
            var e0x = bx - ax;
            var e0y = by - ay;
            var e0z = bz - az;
            var e1x = cx - bx;
            var e1y = cy - by;
            var e1z = cz - bz;
            var e2x = ax - cx;
            var e2y = ay - cy;
            var e2z = az - cz;
            if (kinds[triangle] != MESH) {
                var fx = (by - ay) * (cz - az) - (bz - az) * (cy - ay);
                var fy = (bz - az) * (cx - ax) - (bx - ax) * (cz - az);
                var fz = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
                if (fx * dx + fy * dy + fz * dz >= 0 || fx * (sx - ax) + fy * (sy - ay) + fz * (sz - az) < 0) {
                    return false;
                }
            }
            enter = -Double.MAX_VALUE;
            exit = Double.MAX_VALUE;
            if (!narrowByAxis(
                            e0y * e2z - e0z * e2y,
                            e0z * e2x - e0x * e2z,
                            e0x * e2y - e0y * e2x,
                            ax,
                            ay,
                            az,
                            bx,
                            by,
                            bz,
                            cx,
                            cy,
                            cz,
                            sx,
                            sy,
                            sz,
                            dx,
                            dy,
                            dz,
                            ex,
                            ey,
                            ez)
                    || !narrowByAxis(0, 0, 1, ax, ay, az, bx, by, bz, cx, cy, cz, sx, sy, sz, dx, dy, dz, ex, ey, ez)
                    || !narrowByAxis(1, 0, 0, ax, ay, az, bx, by, bz, cx, cy, cz, sx, sy, sz, dx, dy, dz, ex, ey, ez)
                    || !narrowByAxis(0, 1, 0, ax, ay, az, bx, by, bz, cx, cy, cz, sx, sy, sz, dx, dy, dz, ex, ey, ez)
                    || !narrowByAxis(
                            0, e0z, -e0y, ax, ay, az, bx, by, bz, cx, cy, cz, sx, sy, sz, dx, dy, dz, ex, ey, ez)
                    || !narrowByAxis(
                            -e0z, 0, e0x, ax, ay, az, bx, by, bz, cx, cy, cz, sx, sy, sz, dx, dy, dz, ex, ey, ez)
                    || !narrowByAxis(
                            e0y, -e0x, 0, ax, ay, az, bx, by, bz, cx, cy, cz, sx, sy, sz, dx, dy, dz, ex, ey, ez)
                    || !narrowByAxis(
                            0, e1z, -e1y, ax, ay, az, bx, by, bz, cx, cy, cz, sx, sy, sz, dx, dy, dz, ex, ey, ez)
                    || !narrowByAxis(
                            -e1z, 0, e1x, ax, ay, az, bx, by, bz, cx, cy, cz, sx, sy, sz, dx, dy, dz, ex, ey, ez)
                    || !narrowByAxis(
                            e1y, -e1x, 0, ax, ay, az, bx, by, bz, cx, cy, cz, sx, sy, sz, dx, dy, dz, ex, ey, ez)
                    || !narrowByAxis(
                            0, e2z, -e2y, ax, ay, az, bx, by, bz, cx, cy, cz, sx, sy, sz, dx, dy, dz, ex, ey, ez)
                    || !narrowByAxis(
                            -e2z, 0, e2x, ax, ay, az, bx, by, bz, cx, cy, cz, sx, sy, sz, dx, dy, dz, ex, ey, ez)
                    || !narrowByAxis(
                            e2y, -e2x, 0, ax, ay, az, bx, by, bz, cx, cy, cz, sx, sy, sz, dx, dy, dz, ex, ey, ez)) {
                return false;
            }
            if (enter <= FIRST_ENTRY || enter > 1 || enterX == 0 && enterY == 0 && enterZ == 0) {
                return false;
            }
            var length = Math.sqrt(enterX * enterX + enterY * enterY + enterZ * enterZ);
            time = Math.max(0, enter);
            normalX = enterX / length;
            normalY = enterY / length;
            normalZ = enterZ / length;
            return true;
        }

        private boolean narrowByAxis(
                double nx,
                double ny,
                double nz,
                double ax,
                double ay,
                double az,
                double bx,
                double by,
                double bz,
                double cx,
                double cy,
                double cz,
                double sx,
                double sy,
                double sz,
                double dx,
                double dy,
                double dz,
                double ex,
                double ey,
                double ez) {
            var lengthSquared = nx * nx + ny * ny + nz * nz;
            if (lengthSquared < 1e-12) {
                return true;
            }
            var p0 = nx * ax + ny * ay + nz * az;
            var p1 = nx * bx + ny * by + nz * bz;
            var p2 = nx * cx + ny * cy + nz * cz;
            var low = Math.min(p0, Math.min(p1, p2));
            var high = Math.max(p0, Math.max(p1, p2));
            var radius = ex * Math.abs(nx) + ey * Math.abs(ny) + ez * Math.abs(nz);
            var center = nx * sx + ny * sy + nz * sz;
            var speed = nx * dx + ny * dy + nz * dz;
            low -= radius;
            high += radius;
            if (speed * speed <= 1e-18 * lengthSquared) {
                return center >= low && center <= high;
            }
            var first = (low - center) / speed;
            var last = (high - center) / speed;
            if (first > last) {
                var swap = first;
                first = last;
                last = swap;
            }
            if (first > enter + 1e-9) {
                enter = first;
                var sign = speed > 0 ? -1 : 1;
                enterX = nx * sign;
                enterY = ny * sign;
                enterZ = nz * sign;
            }
            if (last < exit) {
                exit = last;
            }
            return enter <= exit && exit >= 0;
        }

        private boolean isFirstMeeting(int triangle) {
            for (var i = 0; i < met; i++) {
                if (meetings[i] == triangle) {
                    return false;
                }
            }
            if (met == meetings.length) {
                meetings = Arrays.copyOf(meetings, met * 2);
            }
            meetings[met++] = triangle;
            return true;
        }

        private static double backOff(byte kind, double time, double length) {
            if (length <= 0) {
                return time;
            }
            var limit = kind == MESH ? 1.0 : 4.0;
            var back = Math.clamp(0.1, 0.1 / length, limit / length);
            return Math.clamp(time - back, 0, 1);
        }

        private static int clamp(int index, int size) {
            return index < 0 ? 0 : Math.min(index, size - 1);
        }
    }

    private final class TerrainCache {
        static final int FLOATS = 16;
        static final byte NO_NORMAL = 1;
        static final byte NO_EDGE = 2;

        final float raise;
        final byte[] flags;
        final float[] floats;

        TerrainCache(float raise) {
            this.raise = raise;
            var count = terrainCount;
            flags = new byte[count];
            floats = new float[count * FLOATS];
            for (var t = 0; t < kinds.length; t++) {
                var slot = terrainSlots[t];
                if (slot >= 0) {
                    computeValues(t, slot);
                }
            }
        }

        private void computeValues(int triangle, int slot) {
            var o = triangle * 9;
            var q = triangle * QUAD_FLOATS;
            var originX = quads[q];
            var originY = quads[q + 1];
            var originZ = quads[q + 2];
            var ax = triangles[o] - originX;
            var ay = triangles[o + 1] - originY;
            var az = (triangles[o + 2] - originZ) + raise;
            var bx = triangles[o + 3] - originX;
            var by = triangles[o + 4] - originY;
            var bz = (triangles[o + 5] - originZ) + raise;
            var cx = triangles[o + 6] - originX;
            var cy = triangles[o + 7] - originY;
            var cz = (triangles[o + 8] - originZ) + raise;
            var nx = (by - ay) * (cz - az) - (bz - az) * (cy - ay);
            var ny = (bz - az) * (cx - ax) - (bx - ax) * (cz - az);
            var nz = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
            var squareSum = nx * nx + ny * ny + nz * nz;
            if (squareSum < SMALL_NUMBER) {
                flags[slot] = NO_NORMAL;
                return;
            }
            var scale = 1f / (float) Math.sqrt(squareSum);
            nx *= scale;
            ny *= scale;
            nz *= scale;
            var plane = (int) quads[q + 3];
            var px = plane == 0 ? ax : plane == 1 ? bx : cx;
            var py = plane == 0 ? ay : plane == 1 ? by : cy;
            var pz = plane == 0 ? az : plane == 1 ? bz : cz;
            var c = slot * FLOATS;
            floats[c] = nx;
            floats[c + 1] = ny;
            floats[c + 2] = nz;
            floats[c + 3] = ny * py + nx * px + nz * pz;
            computeEdge(slot, 0, ax, ay, az, bx, by, bz, nx, ny, nz);
            computeEdge(slot, 1, bx, by, bz, cx, cy, cz, nx, ny, nz);
            computeEdge(slot, 2, cx, cy, cz, ax, ay, az, nx, ny, nz);
        }

        private void computeEdge(
                int slot,
                int edge,
                float ax,
                float ay,
                float az,
                float bx,
                float by,
                float bz,
                float nx,
                float ny,
                float nz) {
            var ox = (by - ay) * nz - (bz - az) * ny;
            var oy = (bz - az) * nx - (bx - ax) * nz;
            var oz = (bx - ax) * ny - (by - ay) * nx;
            var squareSum = ox * ox + oy * oy + oz * oz;
            if (squareSum < SMALL_NUMBER) {
                flags[slot] |= (byte) (NO_EDGE << edge);
                return;
            }
            var scale = 1f / (float) Math.sqrt(squareSum);
            ox *= scale;
            oy *= scale;
            oz *= scale;
            var e = slot * FLOATS + 4 + edge * 4;
            floats[e] = ox;
            floats[e + 1] = oy;
            floats[e + 2] = oz;
            floats[e + 3] = oy * ay + ox * ax + oz * az;
        }
    }

    public Query query() {
        return new Query();
    }

    public int getTriangleCount() {
        return triangles.length / 9;
    }

    private TerrainCache terrainCache(float raise) {
        var cache = terrainCache;
        if (cache != null && cache.raise == raise) {
            return cache;
        }
        synchronized (this) {
            cache = terrainCache;
            if (cache == null || cache.raise != raise) {
                cache = new TerrainCache(raise);
                terrainCache = cache;
            }
            return cache;
        }
    }

    private int columnOf(float x) {
        return Math.clamp((int) Math.floor((x - originX) / GRID), 0, columns - 1);
    }

    private int rowOf(float y) {
        return Math.clamp((int) Math.floor((y - originY) / GRID), 0, rows - 1);
    }
}
