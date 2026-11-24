package com.rost.cruma.viewer.geodata;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.Nullable;

public final class ClientFloors {
    public static final double PAWN_CLEARANCE = 2.15;

    private static final int SWEEPS = 30;
    private static final int SWEEP_STEP = 30;
    private static final double LOWEST_FLOOR = -1_000_000;
    private static final double SAME_FLOOR = ClientFloorGrid.STEP;
    private final Map<Long, ClientFloorGrid> grids = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong();

    public record Hit(double x, double y, double z) {}

    public void put(ClientFloorGrid grid) {
        grids.put(key(grid.getOriginX(), grid.getOriginY()), grid);
        revision.incrementAndGet();
    }

    public void remove(String owner) {
        if (grids.values().removeIf(grid -> grid.getOwner().equals(owner))) {
            revision.incrementAndGet();
        }
    }

    public void clear() {
        grids.clear();
        revision.incrementAndGet();
    }

    public boolean holds(String map) {
        for (var grid : grids.values()) {
            if (grid.getOwner().equalsIgnoreCase(map)) {
                return true;
            }
        }
        return false;
    }

    public boolean covers(double x, double y) {
        return grids.containsKey(key((int) Math.floor(x), (int) Math.floor(y)));
    }

    public long getRevision() {
        return revision.get();
    }

    public double findStandHeight(double x, double y, double z, double radius, double height) {
        if (!Double.isFinite(z) || grids.isEmpty()) {
            return Double.NaN;
        }
        for (var attempt = 0; attempt < SWEEPS; attempt++) {
            var high = z + 20 + SWEEP_STEP * attempt;
            var low = z - SWEEP_STEP * (attempt + 1) - height;
            var best = highestFloor(x, y, low, high);
            if (radius > 0) {
                best = max(best, highestFloor(x + radius, y, low, high));
                best = max(best, highestFloor(x - radius, y, low, high));
                best = max(best, highestFloor(x, y + radius, low, high));
                best = max(best, highestFloor(x, y - radius, low, high));
            }
            if (!Double.isNaN(best)) {
                return best;
            }
            if (attempt == 0 && grids.get(key((int) Math.floor(x), (int) Math.floor(y))) == null) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    public double getRestHeight(double x, double y, double z, double radius, double height) {
        return findStandHeight(x, y, z, radius, height) + PAWN_CLEARANCE;
    }

    public double getSurfaceHeight(double x, double y, double z) {
        var stand = findStandHeight(x, y, z, 0, 0);
        if (Double.isNaN(stand)) {
            return Double.NaN;
        }
        var grid = grids.get(key((int) Math.floor(x), (int) Math.floor(y)));
        var cellsX = (x - grid.getOriginX()) / ClientFloorGrid.STEP - .5;
        var cellsY = (y - grid.getOriginY()) / ClientFloorGrid.STEP - .5;
        var cellX = Math.floor(cellsX);
        var cellY = Math.floor(cellsY);
        var shareX = cellsX - cellX;
        var shareY = cellsY - cellY;
        var height = 0.0;
        var weight = 0.0;
        for (var corner = 0; corner < 4; corner++) {
            var right = corner & 1;
            var up = corner >> 1;
            var part = (right == 0 ? 1 - shareX : shareX) * (up == 0 ? 1 - shareY : shareY);
            if (part > 0) {
                var floor = nearestFloor(
                        grid.getOriginX() + (cellX + right + .5) * ClientFloorGrid.STEP,
                        grid.getOriginY() + (cellY + up + .5) * ClientFloorGrid.STEP,
                        stand);
                if (!Double.isNaN(floor)) {
                    height += floor * part;
                    weight += part;
                }
            }
        }
        return weight > 0 ? height / weight : stand;
    }

    public double findFloorBelow(double x, double y, double z) {
        return highestFloor(x, y, LOWEST_FLOOR, z + 20);
    }

    public double snap(double x, double y, double z) {
        var floor = findStandHeight(x, y, z, 0, 0);
        return Double.isNaN(floor) ? z : floor;
    }

    public @Nullable Hit findRayHit(double x, double y, double z, double dx, double dy, double dz, double reach) {
        var length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-9 || grids.isEmpty()) {
            return null;
        }
        var stepX = dx / length * ClientFloorGrid.STEP;
        var stepY = dy / length * ClientFloorGrid.STEP;
        var stepZ = dz / length * ClientFloorGrid.STEP;
        var under = highestFloor(x, y, LOWEST_FLOOR, z);
        var steps = (int) Math.ceil(reach / ClientFloorGrid.STEP);
        for (var step = 1; step <= steps; step++) {
            var nextX = x + stepX;
            var nextY = y + stepY;
            var nextZ = z + stepZ;
            if (nextZ <= z) {
                var crossed = highestFloor(nextX, nextY, nextZ, z);
                if (!Double.isNaN(crossed)) {
                    return new Hit(nextX, nextY, crossed);
                }
            }
            if (!Double.isNaN(under)) {
                var slope = highestFloor(nextX, nextY, nextZ, under + ClientFloorGrid.STEP);
                if (!Double.isNaN(slope)) {
                    return new Hit(nextX, nextY, slope);
                }
            }
            under = highestFloor(nextX, nextY, LOWEST_FLOOR, nextZ);
            x = nextX;
            y = nextY;
            z = nextZ;
        }
        return null;
    }

    public int @Nullable [] getFloors(double x, double y) {
        var grid = grids.get(key((int) Math.floor(x), (int) Math.floor(y)));
        return grid == null ? null : grid.getFloors(x, y);
    }

    private double highestFloor(double x, double y, double low, double high) {
        var grid = grids.get(key((int) Math.floor(x), (int) Math.floor(y)));
        return grid == null ? Double.NaN : grid.findHighestFloor(x, y, low, high);
    }

    private double nearestFloor(double x, double y, double height) {
        var grid = grids.get(key((int) Math.floor(x), (int) Math.floor(y)));
        return grid == null ? Double.NaN : grid.findNearestFloor(x, y, height, SAME_FLOOR);
    }

    private static double max(double a, double b) {
        return Double.isNaN(a) ? b : Double.isNaN(b) ? a : Math.max(a, b);
    }

    private static long key(int x, int y) {
        return (long) Math.floorDiv(x, ClientFloorGrid.TILE) << 32
                | Math.floorDiv(y, ClientFloorGrid.TILE) & 0xffffffffL;
    }
}
